package com.jarvis.autonomous.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.autonomous.R
import com.jarvis.autonomous.agent.JarvisAgentEngine
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.ui.JarvisBrowserHost
import com.jarvis.autonomous.ui.MainActivity
import com.jarvis.autonomous.ui.overlay.FloatingJarvisOverlay
import com.jarvis.autonomous.voice.AlwaysOnMicListener
import com.jarvis.autonomous.voice.AlwaysOnMicManager
import com.jarvis.autonomous.voice.VoiceManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class JarvisForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_autonomous_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_LISTENING = "com.jarvis.autonomous.ACTION_START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.jarvis.autonomous.ACTION_STOP_LISTENING"
        const val ACTION_RETRY_MIC = "com.jarvis.autonomous.ACTION_RETRY_MIC"
        const val ACTION_STOP_TASK = "com.jarvis.autonomous.ACTION_STOP_TASK"
        const val ACTION_TEXT_COMMAND = "com.jarvis.autonomous.ACTION_TEXT_COMMAND"
        const val EXTRA_COMMAND_TEXT = "extra_command_text"
        const val ACTION_REFRESH_OVERLAY = "com.jarvis.autonomous.ACTION_REFRESH_OVERLAY"
        const val ACTION_ANNOUNCE_NOTIFICATION = "com.jarvis.autonomous.ACTION_ANNOUNCE_NOTIFICATION"
        const val EXTRA_NOTIF_SENDER = "extra_notif_sender"
        const val EXTRA_NOTIF_TEXT = "extra_notif_text"
        const val ACTION_ANNOUNCE_CALL = "com.jarvis.autonomous.ACTION_ANNOUNCE_CALL"
        const val EXTRA_CALL_DISPLAY = "extra_call_display"

        /** A-M8: wake-lock timeout + renewal before expiry. */
        const val WAKE_LOCK_TIMEOUT_MS: Long = 4 * 60 * 60 * 1000L
        const val WAKE_LOCK_RENEWAL_MS: Long = 3 * 60 * 60 * 1000L
        /** A-M8: SharedPreferences key for the mic-enabled flag. */
        const val KEY_MIC_LOOP_PERSISTED: String = "mic_loop_enabled_persisted"
    }

    private lateinit var prefs: JarvisPreferences
    private lateinit var voiceManager: VoiceManager
    private lateinit var agentEngine: JarvisAgentEngine
    private lateinit var micManager: AlwaysOnMicManager
    private var floatingOverlay: FloatingJarvisOverlay? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockHandler = Handler(Looper.getMainLooper())
    // A-M8 wake-lock renewal: re-acquires before the timeout
    // expires, so the lock never silently lapses mid-session.
    private val wakeLockRenewalRunnable: Runnable = Runnable {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                wakeLockHandler.postDelayed(
                    wakeLockRenewalRunnable, WAKE_LOCK_RENEWAL_MS
                )
            }
        }
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingVoiceReply: CompletableDeferred<String?>? = null
    /** B-M13: serialises speakWrapped so setSpeaking(true/false)
     * pairs never interleave across concurrent speaks. */
    private val speakMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 1.4.0 echo gate: EVERY speak goes through here so the
     * mic manager's VAD ignores Jarvis's own voice (setSpeaking
     * true/false around playback), except the barge-in keywords
     * "ruk"/"bas" which still stop speech via the callback below.
     */
    private suspend fun speakWrapped(text: String): Boolean {
        return speakMutex.withLock {
            if (::micManager.isInitialized) {
                micManager.setSpeaking(true, text)
                try {
                    voiceManager.speakAndWait(text)
                } finally {
                    micManager.setSpeaking(false)
                }
            } else {
                voiceManager.speakAndWait(text)
            }
        }
    }

    /**
     * 1.4.2: name the recognizer failure in plain language. The old
     * code spoke nothing and showed nothing, so ERROR_NETWORK (no
     * Google voice service) looked identical to a permission loss.
     * Raw codes: SpeechRecognizer.ERROR_NETWORK=2, ERROR_AUDIO=3,
     * ERROR_SERVER=4, ERROR_CLIENT=5, ERROR_INSUFFICIENT_PERMISSIONS=9.
     */
    private fun micErrorSpeech(code: Int, reason: String): String {
        return when (code) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_SERVER ->
                "Mic band ho gaya — Google voice service nahi mil rahi. " +
                    "Internet ya Google app check karo, phir Retry dabao."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Mic band ho gaya — microphone permission nahi mili. " +
                    "Permissions tab se mic permission do, phir Retry dabao."
            SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_CLIENT ->
                "Mic band ho gaya — mic kisi aur app ke paas hai ya " +
                    "recognizer fail ho gaya. Retry dabao."
            else -> "Mic band ho gaya — $reason Retry dabao."
        }
    }

    /**
     * A-H8: cancel a pending mid-task voice reply (complete with
     * null) so a stale reply never swallows the next command.
     */
    private fun cancelPendingVoiceReply() {
        val pending = pendingVoiceReply
        pendingVoiceReply = null
        if (pending != null && !pending.isCompleted) {
            pending.complete(null)
        }
    }

    /** A-M8: persisted mic-loop flag (same prefs file, own key). */
    private fun isMicLoopPersisted(): Boolean {
        return getSharedPreferences("jarvis_agent_prefs", Context.MODE_PRIVATE)
            .getBoolean(KEY_MIC_LOOP_PERSISTED, false)
    }

    private fun setMicLoopPersisted(value: Boolean) {
        getSharedPreferences("jarvis_agent_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MIC_LOOP_PERSISTED, value).apply()
    }

    /**
     * Silero VAD (1.4.0, CI-bundled asset, never committed):
     * after the mic loop starts, copy assets/silero_vad.onnx
     * to the files dir (skipped when already present at full
     * size) and start the Sherpa VAD monitor. The recognizer
     * turn timer stays primary: VAD segments are not used to
     * cut turns in this release, and the monitor degrades
     * silently to VAD-off on mic-busy (see AlwaysOnMicManager).
     * A missing asset only logs — it never breaks the mic loop.
     */
    private fun startVadMonitor() {
        serviceScope.launch {
            val started = runCatching {
                val target = File(filesDir, "silero_vad.onnx")
                val assetSize = runCatching {
                    assets.openFd("silero_vad.onnx").use { it.length }
                }.getOrDefault(-1L)
                if (!target.exists() || (assetSize > 0 && target.length() != assetSize)) {
                    withContext(Dispatchers.IO) {
                        assets.open("silero_vad.onnx").use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                micManager.enableVadMonitor(target) { _ ->
                    // Echo-gated segments; no per-segment action:
                    // the recognizer timer remains the primary path.
                }
            }.getOrDefault(false)
            JarvisStateRepository.addLog(
                "Silero VAD monitor started=$started " +
                    "(mic-busy degrades to VAD-off by design)."
            )
        }
    }

    /**
     * Mid-task voice replies (1.4.0): while the always-on mic
     * loop owns the mic, the NEXT completed turn IS the reply —
     * no second recognizer is started (two recognizers would
     * fight over the mic and reintroduce the pause bug). When
     * the mic loop is not running, the old single-shot listen
     * is the fallback.
     */
    private suspend fun awaitMicReply(): String? {
        if (::micManager.isInitialized && micManager.isEnabled()) {
            val deferred = CompletableDeferred<String?>()
            pendingVoiceReply = deferred
            try {
                return withTimeoutOrNull(30_000L) { deferred.await() }
            } finally {
                if (pendingVoiceReply === deferred) pendingVoiceReply = null
            }
        }
        return voiceManager.listenForSingleReply()
    }

    override fun onCreate() {
        super.onCreate()
        // 1.4.2: a startup crash used to die silently (no record of
        // WHICH init step failed — the likely "home redirect" cause).
        // Record the failure to the persisted diagnostics file first,
        // then rethrow so crash behaviour itself is unchanged.
        try {
        prefs = JarvisPreferences(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("JARVIS Active — Listening in background"))

        acquirePartialWakeLock()

        agentEngine = JarvisAgentEngine(
            context = this,
            prefs = prefs,
            onSpeakAndAwait = { text -> speakWrapped(text) },
            onRequestUserVoiceReply = { awaitMicReply() },
            onShowBrowserPage = { url -> JarvisBrowserHost.current?.showLoadedPage(url) },
            // B-M3: engine background-browse guard reads live tab
            // visibility via the shared browser host (null-safe:
            // false when the Activity/host does not exist yet).
            isBrowserTabVisible = { JarvisBrowserHost.current?.isBrowserTabVisible == true }
        )

        voiceManager = VoiceManager(
            context = this,
            prefs = prefs,
            onWakeCommandDetected = { spokenCommand ->
                agentEngine.handleUserCommand(spokenCommand)
            },
            // A-H3 single-owner gate: the wake-word recognizer
            // checks the mic loop before every start. The lambda
            // is evaluated at start time (micManager is created
            // below), so the construction order here is safe.
            isMicLoopEnabled = {
                if (::micManager.isInitialized) micManager.isEnabled() else false
            }
        )

        // 1.4.0 always-on mic (service-owned, never Activity-owned):
        // started by the user's mic tap (ACTION_START_LISTENING),
        // then stays on across tasks AND app backgrounding while
        // this service lives. The ONLY off switches are the user's
        // "mic band karo" transcript (handled inside the manager,
        // never forwarded to the engine) and service destruction.
        // A task stop never stops the mic. The mic is NOT
        // auto-started here: listening starts on user action.
        micManager = AlwaysOnMicManager(this, object : AlwaysOnMicListener {
            override fun onTurnComplete(text: String) {
                val pending = pendingVoiceReply
                if (pending != null && !pending.isCompleted) {
                    pending.complete(text)
                    return
                }
                agentEngine.handleUserCommand(text)
            }

            override fun onTurnIncomplete(text: String, reason: String) {
                serviceScope.launch {
                    speakWrapped("Aur bolo, poora sun raha hoon.")
                    micManager.continueAfterIncompletePrompt()
                }
            }

            override fun onMicStoppedByUser() {
                JarvisStateRepository.addLog("Mic stopped by user (mic band karo) — task untouched.")
                // 1.4.2: a user stop clears any fatal banner too.
                JarvisStateRepository.setMicFatal(null)
                JarvisStateRepository.setMicLoopEnabled(false)
                JarvisStateRepository.setMicListening(false)
                // A-H8: a pending reply dies with the mic; A-M8:
                // an explicit user stop clears the persisted flag
                // (service recreation must NOT revive a stopped mic).
                cancelPendingVoiceReply()
                setMicLoopPersisted(false)
                if (prefs.wakeWordEnabled) {
                    voiceManager.startWakeWordListening()
                }
            }

            override fun onPartialTranscript(text: String) {
                // HUD "hearing…" text only — no engine action.
            }

            override fun onListeningStateChanged(listening: Boolean) {
                // HUD mic dot only — plus the 1.4.2 diagnostics
                // mirror so the bug report can state live state.
                JarvisStateRepository.setMicListening(listening)
            }

            override fun onMicError(reason: String) {
                // 1.4.2: terminal mic death used to end in this
                // log line ONLY — no banner, no speech, no retry —
                // which is exactly the reported on/off/on flapping
                // with silence. Now it is VISIBLE on three channels:
                // (1) persisted diagnostics entry with the raw
                // recognizer code, (2) a command-tab banner with a
                // Retry button, (3) a spoken reason.
                val code = if (::micManager.isInitialized) {
                    micManager.lastRecognizerErrorCode
                } else -1
                val spoken = micErrorSpeech(code, reason)
                JarvisStateRepository.addLog("Mic error: $reason")
                JarvisStateRepository.addDiagnostic("mic", "REC_$code", reason)
                JarvisStateRepository.setMicFatal(
                    com.jarvis.autonomous.data.MicFatalState(spoken, code)
                )
                JarvisStateRepository.setMicLoopEnabled(false)
                JarvisStateRepository.setMicListening(false)
                serviceScope.launch {
                    runCatching { speakWrapped(spoken) }
                }
            }
        })
        micManager.setStopSpeakingCallback { voiceManager.stopSpeaking() }

        floatingOverlay = FloatingJarvisOverlay(
            context = this,
            onMicTap = { voiceManager.triggerManualVoiceCommand() },
            onStopTap = { agentEngine.cancelActiveTask("Stopped from Floating HUD.") }
        )

        if (prefs.floatingOverlayEnabled) {
            floatingOverlay?.show()
        }

        if (prefs.wakeWordEnabled) {
            voiceManager.startWakeWordListening()
        }

        // A-M8: restore the mic loop across service recreation
        // ONLY when the user-enabled flag persisted — a service
        // restart alone never auto-starts the mic without it.
        // Permission is re-checked here; a revoked grant clears
        // the flag instead of erroring on every recreation.
        if (isMicLoopPersisted()) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                setMicLoopPersisted(false)
                JarvisStateRepository.addLog(
                    "Mic not restored: RECORD_AUDIO revoked since last run."
                )
            } else {
                voiceManager.stopListeningInternal()
                if (micManager.start()) {
                    startVadMonitor()
                    JarvisStateRepository.addLog("Mic loop restored after service recreation.")
                }
            }
        }

        JarvisStateRepository.setForegroundRunning(true)
        JarvisStateRepository.addLog("Jarvis Foreground Service started.")
        } catch (e: Exception) {
            runCatching {
                com.jarvis.autonomous.data.JarvisDiagnosticsFile.appendCrash("main", e)
            }
            JarvisStateRepository.addDiagnostic(
                "nav", "STARTUP_CRASH",
                "Foreground service onCreate failed: ${e.javaClass.simpleName}: ${e.message ?: ""}"
            )
            throw e
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTENING -> {
                // Always-on mic path (1.4.0): start once, stays on.
                // Stop any wake-word recognizer first so two
                // recognizers never fight over the mic. If the
                // loop cannot start (no mic permission / no
                // recognizer), fall back to the single-shot listen.
                if (::micManager.isInitialized) {
                    // A-M8: battery-exemption prompt at mic-enable
                    // time (previously zero callers) — non-blocking:
                    // the mic still starts even if the user
                    // dismisses the prompt.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        runCatching {
                            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                                micManager.buildBatteryExemptionIntent()
                                    ?.apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }?.let { startActivity(it) }
                            }
                        }
                    }
                    voiceManager.stopListeningInternal()
                    if (micManager.start()) {
                        setMicLoopPersisted(true)
                        JarvisStateRepository.setMicFatal(null)
                        JarvisStateRepository.setMicLoopEnabled(true)
                        startVadMonitor()
                    } else {
                        voiceManager.triggerManualVoiceCommand()
                    }
                } else {
                    voiceManager.triggerManualVoiceCommand()
                }
            }

            ACTION_STOP_LISTENING -> {
                // 1.4.2: the UI had no mic-off button — buttons only
                // sent START, and start() is a no-op when already
                // enabled, so a dead loop could never be revived
                // from the UI. Stop clears the loop AND the fatal
                // banner; a later start begins fresh.
                if (::micManager.isInitialized) {
                    micManager.stop()
                }
                setMicLoopPersisted(false)
                JarvisStateRepository.setMicFatal(null)
                JarvisStateRepository.setMicLoopEnabled(false)
                JarvisStateRepository.setMicListening(false)
                JarvisStateRepository.addLog("Mic stopped from UI (mic off button).")
            }

            ACTION_RETRY_MIC -> {
                // 1.4.2: Retry after a terminal death. start() is a
                // fresh begin here because stopWithError already
                // cleared `enabled` — it is not the no-op path.
                if (::micManager.isInitialized) {
                    voiceManager.stopListeningInternal()
                    if (micManager.start()) {
                        setMicLoopPersisted(true)
                        JarvisStateRepository.setMicFatal(null)
                        JarvisStateRepository.setMicLoopEnabled(true)
                        startVadMonitor()
                        JarvisStateRepository.addLog("Mic restarted from Retry button.")
                    } else {
                        JarvisStateRepository.addLog("Mic Retry failed to start the loop.")
                    }
                }
            }

            ACTION_STOP_TASK -> {
                voiceManager.stopSpeaking()
                agentEngine.cancelActiveTask("Stopped by user.")
            }

            ACTION_TEXT_COMMAND -> {
                val cmd = intent.getStringExtra(EXTRA_COMMAND_TEXT) ?: ""
                if (cmd.isNotBlank()) {
                    agentEngine.handleUserCommand(cmd)
                }
            }

            ACTION_ANNOUNCE_NOTIFICATION -> {
                if (!agentEngine.isTaskRunning() && prefs.autoAnnounceNotifications) {
                    val sender = intent.getStringExtra(EXTRA_NOTIF_SENDER) ?: ""
                    val msg = intent.getStringExtra(EXTRA_NOTIF_TEXT) ?: ""
                    if (sender.isNotBlank() && msg.isNotBlank()) {
                        agentEngine.announceIncomingNotification(sender, msg)
                    }
                }
            }

            ACTION_ANNOUNCE_CALL -> {
                if (!agentEngine.isTaskRunning() && prefs.autoAnnounceNotifications) {
                    val display = intent.getStringExtra(EXTRA_CALL_DISPLAY) ?: ""
                    if (display.isNotBlank()) {
                        agentEngine.announceIncomingCall(display)
                    }
                }
            }

            ACTION_REFRESH_OVERLAY -> {
                if (prefs.floatingOverlayEnabled) {
                    floatingOverlay?.show()
                } else {
                    floatingOverlay?.hide()
                }
                // While the always-on mic loop runs it owns the
                // mic — never start the wake-word recognizer next
                // to it.
                if (::micManager.isInitialized && micManager.isEnabled()) {
                    // Mic loop owns listening; nothing to change.
                } else if (prefs.wakeWordEnabled) {
                    voiceManager.startWakeWordListening()
                } else {
                    voiceManager.stopListeningInternal()
                }
            }
        }
        return START_STICKY
    }

    private fun acquirePartialWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "JarvisAutonomous::BackgroundAgentWakeLock"
            ).apply {
                // A-M8: non-reference-counted + timed acquire,
                // renewed by wakeLockRenewalRunnable before expiry
                // (re-acquire extends the timeout) — the old fixed
                // 4h acquire silently lapsed after 4 hours. NOTE:
                // WakeLock has no Kotlin-readable isReferenceCounted
                // property (setter only), so call setReferenceCounted
                // explicitly — property syntax does not compile here.
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            wakeLockHandler.postDelayed(
                wakeLockRenewalRunnable, WAKE_LOCK_RENEWAL_MS
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Background Autonomous Core",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps JARVIS active in background for voice commands & multi-step tasks"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val listenIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, JarvisForegroundService::class.java).apply {
                action = ACTION_START_LISTENING
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, JarvisForegroundService::class.java).apply {
                action = ACTION_STOP_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis_core)
            .setContentTitle("JARVIS AI Autonomous Agent")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "🎙️ Speak", listenIntent)
            .addAction(0, "⏹ Stop Task", stopIntent)
            .build()
    }

    override fun onDestroy() {
        floatingOverlay?.hide()
        // A-H8: destroy cancels a pending reply (a stale reply
        // must never swallow the next command after recreation).
        // NOTE: the persisted mic flag is deliberately NOT cleared
        // here — recreation restores the mic from it (A-M8); only
        // an explicit user stop clears it.
        cancelPendingVoiceReply()
        wakeLockHandler.removeCallbacks(wakeLockRenewalRunnable)
        if (::micManager.isInitialized) {
            micManager.shutdown()
        }
        serviceScope.cancel()
        voiceManager.shutdown()
        agentEngine.cancelActiveTask("Foreground service destroyed.")
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        JarvisStateRepository.setForegroundRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
