package com.jarvis.autonomous.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
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
import kotlinx.coroutines.withTimeoutOrNull

class JarvisForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_autonomous_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_LISTENING = "com.jarvis.autonomous.ACTION_START_LISTENING"
        const val ACTION_STOP_TASK = "com.jarvis.autonomous.ACTION_STOP_TASK"
        const val ACTION_TEXT_COMMAND = "com.jarvis.autonomous.ACTION_TEXT_COMMAND"
        const val EXTRA_COMMAND_TEXT = "extra_command_text"
        const val ACTION_REFRESH_OVERLAY = "com.jarvis.autonomous.ACTION_REFRESH_OVERLAY"
        const val ACTION_ANNOUNCE_NOTIFICATION = "com.jarvis.autonomous.ACTION_ANNOUNCE_NOTIFICATION"
        const val EXTRA_NOTIF_SENDER = "extra_notif_sender"
        const val EXTRA_NOTIF_TEXT = "extra_notif_text"
        const val ACTION_ANNOUNCE_CALL = "com.jarvis.autonomous.ACTION_ANNOUNCE_CALL"
        const val EXTRA_CALL_DISPLAY = "extra_call_display"
    }

    private lateinit var prefs: JarvisPreferences
    private lateinit var voiceManager: VoiceManager
    private lateinit var agentEngine: JarvisAgentEngine
    private lateinit var micManager: AlwaysOnMicManager
    private var floatingOverlay: FloatingJarvisOverlay? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingVoiceReply: CompletableDeferred<String?>? = null

    /**
     * 1.4.0 echo gate: EVERY speak goes through here so the
     * mic manager's VAD ignores Jarvis's own voice (setSpeaking
     * true/false around playback), except the barge-in keywords
     * "ruk"/"bas" which still stop speech via the callback below.
     */
    private suspend fun speakWrapped(text: String) {
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
        prefs = JarvisPreferences(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("JARVIS Active — Listening in background"))

        acquirePartialWakeLock()

        agentEngine = JarvisAgentEngine(
            context = this,
            prefs = prefs,
            onSpeakAndAwait = { text -> speakWrapped(text) },
            onRequestUserVoiceReply = { awaitMicReply() },
            onShowBrowserPage = { url -> JarvisBrowserHost.current?.showLoadedPage(url) }
        )

        voiceManager = VoiceManager(
            context = this,
            prefs = prefs,
            onWakeCommandDetected = { spokenCommand ->
                agentEngine.handleUserCommand(spokenCommand)
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
                if (prefs.wakeWordEnabled) {
                    voiceManager.startWakeWordListening()
                }
            }

            override fun onPartialTranscript(text: String) {
                // HUD "hearing…" text only — no engine action.
            }

            override fun onListeningStateChanged(listening: Boolean) {
                // HUD mic dot only.
            }

            override fun onMicError(reason: String) {
                JarvisStateRepository.addLog("Mic error: $reason")
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

        JarvisStateRepository.setForegroundRunning(true)
        JarvisStateRepository.addLog("Jarvis Foreground Service started.")
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
                    voiceManager.stopListeningInternal()
                    if (!micManager.start()) {
                        voiceManager.triggerManualVoiceCommand()
                    }
                } else {
                    voiceManager.triggerManualVoiceCommand()
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
                acquire(4 * 60 * 60 * 1000L) // 4 hours renewable
            }
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
