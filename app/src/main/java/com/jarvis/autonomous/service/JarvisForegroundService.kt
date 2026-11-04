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
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.jarvis.autonomous.R
import com.jarvis.autonomous.agent.JarvisAgentEngine
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.data.TestVoiceStageTracker
import com.jarvis.autonomous.data.LaunchSession
import com.jarvis.autonomous.data.clearStaleBounceReport
import com.jarvis.autonomous.data.bounceShareFile
import com.jarvis.autonomous.data.buildBounceReportText
import com.jarvis.autonomous.data.detectPreviousBounce
import com.jarvis.autonomous.data.wasLastDeathDuringGreetingSpeak
import com.jarvis.autonomous.data.writeBounceReportFile
import com.jarvis.autonomous.ui.JarvisBrowserHost
import com.jarvis.autonomous.ui.MainActivity
import com.jarvis.autonomous.ui.overlay.FloatingJarvisOverlay
import com.jarvis.autonomous.voice.AlwaysOnMicListener
import com.jarvis.autonomous.voice.AlwaysOnMicManager
import com.jarvis.autonomous.voice.DeepgramSttClient
import com.jarvis.autonomous.voice.SwaraTtsClient
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
        // 1.4.8 (G8 F4): standalone voice test — speaks a sample
        // line through the normal speak path.
        const val ACTION_SPEAK_TEST = "com.jarvis.autonomous.ACTION_SPEAK_TEST"
        const val ACTION_STOP_LISTENING = "com.jarvis.autonomous.ACTION_STOP_LISTENING"
        const val ACTION_RETRY_MIC = "com.jarvis.autonomous.ACTION_RETRY_MIC"
        const val ACTION_STOP_TASK = "com.jarvis.autonomous.ACTION_STOP_TASK"
        const val ACTION_GREET = "com.jarvis.autonomous.ACTION_GREET"
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
    /** 1.4.10: last FGS type set applied via refreshForegroundTypes
     * (change-only marker — see there). */
    private var lastFgsTypes: Int? = null
    private var pendingVoiceReply: CompletableDeferred<String?>? = null
    /**
     * 1.4.3 startup greeting: spoken ONCE per service lifetime
     * (not on every Activity rotation or service restart —
     * MainActivity sends ACTION_GREET on launch; a second send
     * is a no-op). If the greet arrives before init finishes
     * (defensive — onCreate always precedes onStartCommand),
     * [pendingGreet] replays it after init.
     */
    private var greetingDone: Boolean = false
    private var pendingGreet: Boolean = false
    // 1.4.12: greeting-speak flight flag + deferred VAD start.
    // VAD init is deferred until the greeting speak completes,
    // to avoid VAD-init vs greeting-speak contention at launch;
    // while a greeting speak is in flight, VAD init waits and
    // runs right after the greeting finishes (pendingVadStart).
    // Greeting speak is fast under Swara streaming, so the
    // window shrinks naturally. VAD is optional (mic-busy
    // degrades to VAD-off by design); greeting is the priority,
    // so VAD yields, never the reverse.
    @Volatile
    private var greetingSpeakInProgress: Boolean = false
    private var pendingVadStart: Boolean = false
    // 1.4.5 (item 3): fallback for a deferred VAD start whose
    // greeting never arrives (service start with no UI greeting):
    // after 5 s VAD starts anyway, so the restore path can never
    // leave VAD permanently deferred. Cancelled by drainPendingVad.
    private var vadFallbackRunnable: Runnable? = null
    // 1.4.4: true once the persistent notification has been
    // re-issued WITH the "Share bounce report" action, so later
    // service starts don't re-issue it pointlessly.
    private var bounceShareActionAdded: Boolean = false
    /** B-M13: serialises speakWrapped so setSpeaking(true/false)
     * pairs never interleave across concurrent speaks. */
    private val speakMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 1.4.0 echo gate: EVERY speak goes through here so the
     * mic manager's VAD ignores Jarvis's own voice (setSpeaking
     * true/false around playback), except the barge-in keywords
     * "ruk"/"bas" which still stop speech via the callback below.
     */
    private suspend fun speakWrapped(text: String, origin: String = "reply"): Boolean {
        // 1.4.3 SPEAKING->HOME shield + decisive speak window:
        // EVERY speak logs SPK_START then SPK_END (unconditional
        // — no throttle, so a first-step repro is decidable).
        // A speak-TIME throw used to propagate and kill the app
        // right after the UI showed "speaking" (onCreate's
        // runCatching cannot catch it — it fires later, per
        // utterance). Now Java throws are recorded (tts/SPK_START,
        // exact class + message, never the spoken text) and
        // degrade to "not spoken" (chat fallback) instead of a
        // crash. What this CANNOT catch: a system battery-kill
        // — that leaves NO Java crash entry, which is itself the
        // diagnostic signal (SPK_START present + no SPK_END +
        // no crash entry = system kill during speak). Swara runs
        // in-process with bounded timeouts, so a hung speak
        // returns (SPK_END is always written) instead of hanging
        // the breaker detector.
        val startMs = System.currentTimeMillis()
        JarvisStateRepository.addDiagnostic(
            "tts", "SPK_START",
            "Speak started (origin=$origin, chars=${text.length})."
        )
        val ok = speakMutex.withLock {
            // 1.4.11: Test Voice stage arming — POST-mutex, so
            // a test queued behind a running speak arms only
            // when its OWN speak actually starts. SPK_START is
            // pre-mutex and would arm too early, letting the
            // running speak's SPK_END terminalize the test
            // with the wrong outcome. Additive only: no speak
            // logic, threshold, or gate changes.
            if (origin == "test") TestVoiceStageTracker.onSpeakStarted()
            if (::micManager.isInitialized) {
                micManager.setSpeaking(true, text)
                try {
                    runCatching { voiceManager.speakAndWait(text) }.getOrElse { t ->
                        JarvisStateRepository.addDiagnostic(
                            "tts", "SPK_START",
                            "Speak failed at start: ${t.javaClass.simpleName}: ${t.message ?: ""}"
                        )
                        false
                    }
                } finally {
                    micManager.setSpeaking(false)
                }
            } else {
                runCatching { voiceManager.speakAndWait(text) }.getOrElse { t ->
                    JarvisStateRepository.addDiagnostic(
                        "tts", "SPK_START",
                        "Speak failed at start: ${t.javaClass.simpleName}: ${t.message ?: ""}"
                    )
                    false
                }
            }
        }
        JarvisStateRepository.addDiagnostic(
            "tts", "SPK_END",
            "Speak finished: ok=$ok, ms=${System.currentTimeMillis() - startMs}."
        )
        return ok
    }

    /**
     * 1.4.3 startup greeting (FIXED local lines, LLM bypassed —
     * no network, no key, no LLM failure can swallow it). The
     * chat line is ALWAYS posted (visible even with Swara OFF);
     * speech is best-effort and its failure is logged. The
     * key-prompt appends ONLY when no API key is saved.
     */
    private fun speakGreeting() {
        if (greetingDone) return
        greetingDone = true
        val base = "Namaste, main Jarvis hoon. Main sun raha hoon — bolo, kya karna hai?"
        val full = if (prefs.apiKey.isBlank()) {
            "$base Baat karne ke liye AI Brain me apni API key daalo aur Save dabao."
        } else {
            base
        }
        JarvisStateRepository.addChatMessage("Jarvis", full)
        // 1.4.5 CIRCUIT BREAKER (generic GREET_START-without-
        // SPK_END detector, kept in 1.4.12): if the previous
        // process died
        // during greeting speak (GREET_START with no SPK_END),
        // skip greeting speak for exactly ONE launch and show
        // the breaker card instead (see Command tab). Two things
        // happen at once: (a) if the app then stays open, that
        // PROVES greeting speak was the trigger; (b) the user
        // gets a readable catcher (banner + full report + Copy)
        // while the app stays open. Self-clearing: a skipped
        // greeting writes no GREET_START, so the next launch
        // cannot trip again and greeting resumes normally.
        // The skip itself is recorded as GREET_SKIPPED_AFTER_SPK_DEATH.
        if (wasLastDeathDuringGreetingSpeak()) {
            JarvisStateRepository.addDiagnostic(
                "tts", "GREET_SKIPPED_AFTER_SPK_DEATH",
                "Previous session died during greeting speak — " +
                    "greeting speak skipped once so the app stays open " +
                    "(circuit breaker; next launch greets normally)."
            )
            JarvisStateRepository.setGreetSkippedAfterSpeakDeath(true)
            JarvisStateRepository.addChatMessage(
                "System",
                "Pichhli baar greeting bolte waqt app band hua, " +
                    "isliye is baar greeting skip ki gayi taaki app khula rahe."
            )
            // 1.4.5 (item 3): no greeting speak will happen on this
            // launch, so a deferred VAD start must NOT wait for it.
            drainPendingVad()
            return
        }
        // 1.4.5 GREET_START: the greeting-specific start marker.
        // speakWrapped writes SPK_END unconditionally, so the
        // breaker detector reads GREET_START-without-SPK_END as
        // death-during-greeting. Written BEFORE the speak launch
        // so even a death before speakWrapped starts is caught.
        JarvisStateRepository.addDiagnostic(
            "tts", "GREET_START",
            "Startup greeting speak started."
        )
        greetingSpeakInProgress = true
        serviceScope.launch {
            val ok = runCatching { speakWrapped(full, "greeting") }.getOrDefault(false)
            greetingSpeakInProgress = false
            // Deferred VAD init (see startVadMonitor /
            // deferVadUntilGreeting): run it now that greeting
            // speak is done (VAD init was deferred until greeting
            // speak completes, to avoid contention at launch).
            drainPendingVad()
            if (!ok) {
                // 1.4.6: the old "(OFF or TTS failed)" text was
                // ambiguous — a report could not tell which stage
                // failed. Now the stage is named explicitly from
                // the toggle + the Swara client's last failure
                // (SWARA_* markers in diagnostics are the deeper
                // cause under a TTS_FAILED stage).
                val stage = when {
                    !prefs.swaraEnabled -> "SWARA_OFF"
                    SwaraTtsClient.lastFailure != null -> SwaraTtsClient.lastFailure
                    else -> "TTS_FAILED"
                }
                JarvisStateRepository.addDiagnostic(
                    "tts", "GREET_FAIL",
                    "Startup greeting could not be spoken — stage=$stage " +
                        "(SWARA_OFF = toggle OFF in AI Brain; " +
                        "SWARA_CONNECT_FAILED = Swara connect failed; " +
                        "SWARA_SYNTHESIZE_FAILED = Swara synth/playback failed; " +
                        "SWARA_NO_AUDIO = Swara sent no audio; " +
                        "SWARA_TIMEOUT = Swara timed out; TTS_FAILED = " +
                        "other speak failure) — text shown in chat."
                )
            }
        }
    }

    /**
     * 1.4.5 (FINAL policy): reason-based mic-error speech. The old
     * code mapped system SpeechRecognizer codes (ERROR_NETWORK=2,
     * etc.) — those codes no longer exist in the app (system
     * recognizer removed). The mapping now reads the reason
     * string: Deepgram auth (401/Unauthorized) -> key check;
     * network/DNS -> internet; permission -> Permissions tab;
     * otherwise the reason itself, truncated.
     */
    private fun micErrorSpeech(reason: String): String {
        val lower = reason.lowercase()
        return when {
            lower.contains("401") || lower.contains("unauthorized") ->
                "Mic band ho gaya — Deepgram key galat lag rahi hai. " +
                    "AI Brain me key check karo, phir Retry dabao."
            lower.contains("network") || lower.contains("unable to resolve") ||
                lower.contains("internet") || lower.contains("no network") ->
                "Mic band ho gaya — internet nahi mil raha. " +
                    "Network aate hi mic dobara connect hoga, ya Retry dabao."
            lower.contains("permission") || lower.contains("record_audio") ||
                lower.contains("not granted") ->
                "Mic band ho gaya — microphone permission nahi mili. " +
                    "Permissions tab se mic permission do, phir Retry dabao."
            lower.contains("key") ->
                "Mic band ho gaya — Deepgram key nahi hai. " +
                    "AI Brain me key daal kar Save dabao, phir mic on karo."
            else -> "Mic band ho gaya — ${reason.take(120)} Retry dabao."
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
     * 1.4.5 (item 3): drain a deferred VAD start — called from
     * speakGreeting completion AND from the circuit-breaker skip
     * path (no greeting speak will happen there, so VAD must
     * start immediately, not wait for a greeting that never comes).
     */
    private fun drainPendingVad() {
        if (!pendingVadStart) return
        pendingVadStart = false
        vadFallbackRunnable?.let { wakeLockHandler.removeCallbacks(it) }
        vadFallbackRunnable = null
        startVadMonitor()
    }

    /**
     * 1.4.5 (item 3): RESTORE DEFERRAL — the onCreate persisted-mic
     * restore path. DECISION (explicit, in code): restore + greeting
     * speak must NOT overlap — greeting wins, VAD waits. The old
     * part-2 deferral only covered startVadMonitor() calls made
     * WHILE greetingSpeakInProgress was already true; at restore
     * time that flag is still false (greeting comes later via
     * ACTION_GREET/pendingGreet), so restore VAD init overlapped
     * greeting speak. Now the restore path calls this instead of
     * startVadMonitor() when greeting has not yet run: VAD waits
     * (VAD_DEFERRED, reason restore-before-greeting) and starts on
     * greeting completion (drainPendingVad) or after the 5 s
     * fallback when no greeting arrives. If greeting already ran,
     * VAD starts immediately.
     */
    private fun deferVadUntilGreeting() {
        pendingVadStart = true
        JarvisStateRepository.addDiagnostic(
            "tts", "VAD_DEFERRED",
            "Sherpa VAD init deferred (restore-before-greeting: greeting wins, VAD waits; 5s fallback if no greeting arrives)."
        )
        vadFallbackRunnable?.let { wakeLockHandler.removeCallbacks(it) }
        val runnable = Runnable {
            vadFallbackRunnable = null
            if (pendingVadStart) {
                pendingVadStart = false
                startVadMonitor()
            }
        }
        vadFallbackRunnable = runnable
        wakeLockHandler.postDelayed(runnable, 5_000L)
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
        // 1.4.12: while a greeting speak is in flight, VAD
        // init is deferred until the greeting speak completes,
        // to avoid VAD-init vs greeting-speak contention at
        // launch. Defer (VAD is optional; greeting is the
        // priority) and run right after the greeting finishes
        // (see speakGreeting). Greeting speak is fast under
        // Swara streaming, so the window shrinks naturally.
        if (greetingSpeakInProgress) {
            pendingVadStart = true
            JarvisStateRepository.addDiagnostic(
                "tts", "VAD_DEFERRED",
                "Sherpa VAD init deferred until greeting speak " +
                    "finishes (native loads serialized at launch)."
            )
            return
        }
        serviceScope.launch {
            // 1.4.12 overlap markers: VAD_INIT_START/END timestamps
            // against SWARA_CONNECT/SWARA_FIRST_AUDIO markers show
            // whether VAD init overlapped greeting speak.
            JarvisStateRepository.addDiagnostic(
                "tts", "VAD_INIT_START",
                "Sherpa VAD init started."
            )
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
            JarvisStateRepository.addDiagnostic(
                "tts", "VAD_INIT_END",
                "Sherpa VAD init finished (started=$started)."
            )
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
        // 1.4.3: loop OFF — Deepgram one-shot FIRST (key present),
        // system single-reply second (key missing or Deepgram
        // timeout/failure). Speaking stays Swara either way.
        val dgKey = prefs.deepgramApiKey
        if (dgKey.isNotBlank()) {
            val heard = runCatching {
                DeepgramSttClient(dgKey).listenOnce()
            }.getOrNull()
            if (!heard.isNullOrBlank()) return heard
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
        // 1.4.9 (Step 1g): NEVER the raw 2-arg startForeground
        // here — it applies all manifest types
        // (microphone|specialUse) and crashes on SDK 34+ when
        // RECORD_AUDIO is absent (phone crash 23:13:31).
        startForegroundSafe(buildNotification("JARVIS Active — Listening in background"))

        acquirePartialWakeLock()

        agentEngine = JarvisAgentEngine(
            context = this,
            prefs = prefs,
            onSpeakAndAwait = { text, origin -> speakWrapped(text, origin) },
            onRequestUserVoiceReply = { awaitMicReply() },
            // 1.4.5 (item 5): voice "stop" commands actually stop
            // speech now (the engine branch previously only
            // cancelled the task — speech kept playing).
            onStopSpeaking = { voiceManager.stopSpeaking("voice stop command") },
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
            },
            // 1.4.5 FINAL policy: Deepgram-only sessions, wired to
            // the manager (service constructs VoiceManager before
            // micManager, but these lambdas run later, so the
            // construction order is safe — same pattern as the
            // isMicLoopEnabled gate above).
            // 1.4.10 (Step 5): every mic transition refreshes
            // the FGS type set (level-triggered: the final state
            // is always recomputed, so a transient ordering —
            // e.g. refresh before an async wake start lands —
            // self-corrects on the next transition).
            startWakeSession = { cb ->
                if (::micManager.isInitialized) {
                    micManager.startWakeListening(cb)
                    refreshForegroundTypes()
                }
            },
            stopWakeSession = {
                if (::micManager.isInitialized) {
                    micManager.stopWakeListening()
                    refreshForegroundTypes()
                }
            },
            listenOnceSession = { cb ->
                if (::micManager.isInitialized) {
                    micManager.listenOnceAsync(12_000L, cb)
                    refreshForegroundTypes()
                } else {
                    cb(null)
                }
            },
            cancelListenOnceSession = {
                if (::micManager.isInitialized) {
                    micManager.cancelListenOnce()
                    refreshForegroundTypes()
                }
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
                    speakWrapped("Aur bolo, poora sun raha hoon.", "incomplete")
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
                // 1.4.10 (Step 5): user stop — re-apply the type
                // set (downgrade unless wake restarted above;
                // level-triggered, so ordering self-corrects).
                refreshForegroundTypes()
            }

            override fun onPartialTranscript(text: String) {
                // HUD "hearing…" text only — no engine action.
            }

            override fun onListeningStateChanged(listening: Boolean) {
                // HUD mic dot only — plus the 1.4.2 diagnostics
                // mirror so the bug report can state live state.
                JarvisStateRepository.setMicListening(listening)
            }

            override fun onSttPathChanged(path: String, reason: String) {
                // Deepgram-only path line (1.4.5: no system branch
                // exists any more — the old "system mic se" line
                // is deleted with the system path).
                JarvisStateRepository.setSttPath(path, reason)
                JarvisStateRepository.addChatMessage(
                    "Jarvis",
                    "Sun raha hoon — Deepgram se. $reason"
                )
            }

            override fun onMicReconnecting(reason: String) {
                // 1.4.5 (item 4): the loop stays ENABLED while
                // reconnecting (banner, not a stop) — so the
                // repository loop flag stays true; only the
                // reconnecting reason is published for the banner.
                JarvisStateRepository.setMicReconnecting(reason)
                JarvisStateRepository.addLog("Mic reconnecting: $reason")
            }

            override fun onMicRecovered() {
                // 1.4.5 (item 4): recovery chat line on the FIRST
                // reconnect only (the manager fires this once per
                // reconnect episode, not per attempt — no spam).
                JarvisStateRepository.setMicReconnecting(null)
                JarvisStateRepository.addChatMessage(
                    "Jarvis",
                    "Deepgram se phir sun raha hoon."
                )
            }

            override fun onMicError(reason: String) {
                // 1.4.5 FINAL policy: onMicError now means PERMANENT
                // only (permission revoked / key missing) — transient
                // Deepgram failures go through onMicReconnecting
                // instead and never reach this banner. Visible on
                // three channels: (1) diagnostics entry, (2) a
                // command-tab banner with Retry, (3) a spoken reason.
                // Code is -1 (no system recognizer codes exist any
                // more); the banner hides the code line when < 0.
                val spoken = micErrorSpeech(reason)
                JarvisStateRepository.addLog("Mic error: $reason")
                JarvisStateRepository.addDiagnostic("mic", "MIC_ERROR", reason)
                JarvisStateRepository.setMicFatal(
                    com.jarvis.autonomous.data.MicFatalState(spoken, -1)
                )
                JarvisStateRepository.setMicLoopEnabled(false)
                JarvisStateRepository.setMicListening(false)
                // 1.4.10 (Step 5): permanent mic error — the loop
                // is dead; downgrade unless wake/one-shot is live.
                refreshForegroundTypes()
                serviceScope.launch {
                    runCatching { speakWrapped(spoken, "mic-error") }
                }
            }
        })
        micManager.setStopSpeakingCallback { voiceManager.stopSpeaking("barge-in keyword") }

        floatingOverlay = FloatingJarvisOverlay(
            context = this,
            // 1.4.8: the pill orb is now the mic ON/OFF toggle
            // (user order 22:15 IST), synced with the live loop
            // state. Loop OFF -> start via the SAME intent path
            // as the Command-tab mic button (no duplicated start
            // logic); loop ON + stream suspended -> resume now
            // (the 1.4.5 tap-resume behavior, kept); loop ON +
            // live -> stop (user-action off, same intent path as
            // the Command-tab Mic-off button). The old tap =
            // one-shot manual command behavior is replaced by
            // the toggle: with the loop, one-shot is redundant
            // on the pill, and the toggle is what the user
            // asked for. Overlay permission flow untouched:
            // this lambda only runs when the pill is shown,
            // which already requires the grant (show() fails
            // safe otherwise).
            onMicTap = {
                if (!JarvisStateRepository.micLoopEnabled.value) {
                    startService(
                        Intent(this, JarvisForegroundService::class.java).apply {
                            action = ACTION_START_LISTENING
                        }
                    )
                } else if (::micManager.isInitialized && micManager.isDeepgramSuspended()) {
                    micManager.resumeDeepgram("overlay-tap")
                } else {
                    startService(
                        Intent(this, JarvisForegroundService::class.java).apply {
                            action = ACTION_STOP_LISTENING
                        }
                    )
                }
            },
            // 1.4.5 (item 5): HUD stop actually stops SPEECH too —
            // the old path only cancelled the task, so speech kept
            // playing after the user hit stop.
            onStopTap = {
                voiceManager.stopSpeaking("HUD stop")
                agentEngine.cancelActiveTask("Stopped from Floating HUD.")
            }
        )

        if (prefs.floatingOverlayEnabled) {
            floatingOverlay?.show()
        }

        if (prefs.wakeWordEnabled) {
            // 1.4.5 (item 3): recogniser-start marker — the service
            // order has wake-word BEFORE greeting dispatch, but no
            // marker existed for it, so a launch trace could not
            // show whether wake listening started at all.
            JarvisStateRepository.addDiagnostic(
                "mic", "WAKE_START",
                "Wake-word listening starting (service onCreate, before greeting dispatch)."
            )
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
                // 1.4.5 (item 3): MIC_RESTORE marker + restore
                // deferral — greeting has NOT run yet at this point
                // (it comes via ACTION_GREET/pendingGreet below), so
                // restore VAD init must wait for it (greeting wins,
                // VAD waits), NOT start immediately and overlap.
                JarvisStateRepository.addDiagnostic(
                    "mic", "MIC_RESTORE",
                    "Persisted mic loop restoring (service onCreate) — VAD deferred until greeting."
                )
                if (micManager.start()) {
                    if (greetingDone) {
                        startVadMonitor()
                    } else {
                        deferVadUntilGreeting()
                    }
                    JarvisStateRepository.addLog("Mic loop restored after service recreation.")
                    // 1.4.9 (Step 1g): restore is a mic start —
                    // re-apply the qualified type set (onCreate
                    // may have started specialUse-only if the
                    // grant arrived after it).
                    refreshForegroundTypes()
                }
            }
        }

        JarvisStateRepository.setForegroundRunning(true)
        JarvisStateRepository.addLog("Jarvis Foreground Service started.")
        // 1.4.3 home-redirect gate: service lifecycle marker
        // (persisted — survives process death like all markers).
        JarvisStateRepository.addDiagnostic("nav", "SVC_CREATE", "Foreground service created.")
        // 1.4.3: replay a greeting that arrived before init.
        if (pendingGreet) {
            pendingGreet = false
            speakGreeting()
        }
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
        // 1.4.4 SERVICE-side bounce report (UI-independent — this
        // is the path that works when NO launch ever reaches UI):
        // every service start re-runs previous-bounce detection on
        // a background thread and writes the report file, then
        // refreshes the persistent notification WITH the Share
        // action. The Activity's own onCreate thread does the same
        // write; whichever runs first wins, the other rewrites the
        // identical file (idempotent). Fully best-effort: any
        // failure is swallowed, the service command continues.
        // 1.4.12: UI + this service share the MAIN process, and
        // Swara TTS now runs in the main process too (no :tts
        // process any more). So LaunchSession flags are shared
        // with the Activity (same process).
        // 1.4.5 (item 6): ATOMIC bounce detection — the old
        // plain-Boolean check-then-set raced across three threads
        // (Activity onCreate, this service thread, Command-tab
        // card). claimBounceCheck() lets exactly ONE thread detect
        // + publish; losers read the result from repository flows
        // (so the card recomposes when detection lands). A null
        // detection (clean launch) deletes stale report copies so
        // an old report can never be served for a new session.
        Thread {
            runCatching {
                if (LaunchSession.claimBounceCheck()) {
                    val found = detectPreviousBounce()
                    if (found != null) {
                        val path = writeBounceReportFile(
                            this, buildBounceReportText(found)
                        )
                        LaunchSession.bounce = found
                        LaunchSession.bounceFilePath = path
                        JarvisStateRepository.setBounceReport(found, path)
                        refreshBounceNotification()
                    } else {
                        LaunchSession.bounce = null
                        JarvisStateRepository.setBounceReport(null, "")
                        clearStaleBounceReport(this)
                    }
                } else if (LaunchSession.bounce != null && !bounceShareActionAdded) {
                    // File was written by the Activity thread, but
                    // this notification was built before it existed.
                    refreshBounceNotification()
                }
            }
        }.start()
        when (intent?.action) {
            // 1.4.8 (G8 F4): standalone voice test — speaks a
            // sample line through the NORMAL speak path (the
            // same speakWrapped as greetings/replies), so it
            // exercises greeting/reply speech exactly, including
            // breaker behavior. Swara OFF never refuses: the test
            // speaks via the system voice instead, and the button
            // stays enabled in AI Brain.
            ACTION_SPEAK_TEST -> {
                serviceScope.launch {
                    runCatching {
                        speakWrapped("Namaste, main Jarvis hoon. Awaaz test safal raha.", "test")
                    }
                }
            }
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
                    // 1.4.5 (item 4): DEAD-WHILE-ENABLED revival.
                    // The old handler called micManager.start(),
                    // which is a no-op when already enabled — so a
                    // dead-while-enabled loop (hung connect,
                    // suspended-forever, dead pump) could never be
                    // revived from the UI. Now an unhealthy enabled
                    // loop is stopped first, then started fresh.
                    if (micManager.isEnabled() && !micManager.isLoopHealthy()) {
                        micManager.stop()
                    }
                    if (micManager.start()) {
                        setMicLoopPersisted(true)
                        JarvisStateRepository.setMicFatal(null)
                        JarvisStateRepository.setMicLoopEnabled(true)
                        startVadMonitor()
                        // 1.4.9 (Step 1g): mic is live now —
                        // qualify for the microphone type.
                        refreshForegroundTypes()
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
                // 1.4.10 (Step 5): full mic stop — downgrade to
                // specialUse-only unless wake/one-shot is live
                // (refresh recomputes from live state).
                refreshForegroundTypes()
            }

            ACTION_RETRY_MIC -> {
                // 1.4.5 (item 4): Retry ALWAYS stops first, then
                // starts — a fresh begin is guaranteed. The old
                // comment claimed Retry was fresh "because
                // stopWithError cleared enabled"; under the new
                // no-permanent-stop design that claim is false
                // (reconnecting loops stay enabled), so RETRY must
                // force the stop itself.
                if (::micManager.isInitialized) {
                    voiceManager.stopListeningInternal()
                    micManager.stop()
                    if (micManager.start()) {
                        setMicLoopPersisted(true)
                        JarvisStateRepository.setMicFatal(null)
                        JarvisStateRepository.setMicLoopEnabled(true)
                        startVadMonitor()
                        JarvisStateRepository.addLog("Mic restarted from Retry button.")
                        // 1.4.9 (Step 1g): mic is live now —
                        // qualify for the microphone type.
                        refreshForegroundTypes()
                    } else {
                        JarvisStateRepository.addLog("Mic Retry failed to start the loop.")
                    }
                }
            }

            ACTION_STOP_TASK -> {
                voiceManager.stopSpeaking("user STOP_TASK")
                agentEngine.cancelActiveTask("Stopped by user.")
            }

            ACTION_GREET -> {
                // 1.4.3: MainActivity sends this once on launch.
                if (::voiceManager.isInitialized) {
                    speakGreeting()
                } else {
                    pendingGreet = true
                }
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

    /**
     * 1.4.9 (Step 1g): FGS-type gating — phone crash 2026-11-03
     * 23:13:31 was "Unable to create service ... Starting FGS
     * with type microphone ... requires permissions". On SDK
     * 34+, startForeground() with the microphone type requires
     * RECORD_AUDIO already granted, else SecurityException —
     * and the old 2-arg startForeground() applied ALL manifest
     * types (microphone|specialUse), so ANY service start with
     * mic permission absent crashed onCreate (the 1.4.8
     * "verified N/A" verdict was wrong: onCreate never touches
     * RECORD_AUDIO, but the manifest microphone type alone
     * triggers the check). The manifest is the upper bound;
     * the runtime call picks a subset from it. So:
     * specialUse always (no runtime prerequisite); microphone
     * only when RECORD_AUDIO is currently granted. Every
     * startForeground on this service MUST go through
     * [startForegroundSafe], never the raw 2-arg call.
     */
    /**
     * 1.4.10 (Step 5): mic-in-use qualification. 1.4.9 qualified
     * on permission alone, so onCreate applied the microphone
     * type whenever RECORD_AUDIO was granted — even with the
     * loop OFF and wake-word OFF — which is a silent
     * microphone service (user brief: wake OFF = no mic
     * service) and Play-policy surface. Now the microphone
     * type applies ONLY when RECORD_AUDIO is granted AND the
     * mic is live (loop / wake session / one-shot, read live
     * from the manager, never from prefs). Every mic
     * transition refreshes (see refreshForegroundTypes), so
     * the type set always reflects live state.
     */
    private fun isMicInUse(): Boolean {
        if (!::micManager.isInitialized) return false
        return micManager.isEnabled() ||
            micManager.isWakeActive() ||
            micManager.isOneShotActive()
    }

    private fun qualifiedForegroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED && isMicInUse()
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun startForegroundSafe(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, qualifiedForegroundTypes())
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // TOCTOU (permission revoked between check and call)
            // or a background-start restriction: retry with
            // specialUse-only (no runtime prerequisite). If even
            // that fails, record and continue — a foreground
            // promotion must never crash service creation.
            JarvisStateRepository.addDiagnostic(
                "nav", "FGS_TYPE_FALLBACK",
                "Foreground start with qualified types failed (${e.javaClass.simpleName}) — retrying specialUse-only."
            )
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }.onFailure {
                JarvisStateRepository.addDiagnostic(
                    "nav", "FGS_START_FAILED",
                    "Foreground start failed even with specialUse-only (${it.javaClass.simpleName}) — service continues without foreground state."
                )
            }
        }
    }

    /**
     * 1.4.9 (Step 1g): re-apply the qualified type set after a
     * mic start — the common case is a grant that arrived
     * AFTER onCreate (user grants RECORD_AUDIO in Settings,
     * then taps mic): onCreate started specialUse-only, and
     * only now does the service qualify for microphone.
     * Called only after micManager.start() returned true
     * (permission necessarily granted at that point).
     * 1.4.10 (Step 5): mic stop DOES downgrade now — a full
     * stop (loop off + wake off + no one-shot) drops to
     * specialUse-only on the next refresh. If wake restarts
     * after the stop, its start transition re-upgrades. The
     * type set is level-triggered, not edge-triggered: every
     * mic transition refreshes, so transient orderings
     * self-correct.
     */
    private fun refreshForegroundTypes() {
        // 1.4.10 (Step 5): change-only marker — refresh runs on
        // EVERY mic transition, so an every-call marker would
        // flood diagnostics. Only a changed type set is logged
        // (FGS_TYPES_APPLIED), which is what a phone report
        // needs to prove the mic-in-use rule.
        val types = qualifiedForegroundTypes()
        if (types != lastFgsTypes) {
            lastFgsTypes = types
            val micLive = types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
            JarvisStateRepository.addDiagnostic(
                "nav", "FGS_TYPES_APPLIED",
                "Foreground types now: " +
                    if (micLive) "microphone|specialUse (mic live)"
                    else "specialUse-only (mic idle)"
            )
        }
        startForegroundSafe(buildNotification("JARVIS Active — Listening in background"))
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

        // 1.4.4 notification Share action (UI-independent report
        // path): when a bounce report file exists, the persistent
        // notification gains "Share bounce report", which opens
        // the SYSTEM share sheet with the report file — this works
        // even if Jarvis UI never stays open, because the chooser
        // is system UI, not Jarvis UI. The file is served via
        // FileProvider (external files dir only); the URI grant
        // is read-only and single-use per share.
        val shareAction: NotificationCompat.Action? = runCatching {
            val file = bounceShareFile(this) ?: return@runCatching null
            val uri = FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Jarvis bounce report")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Jarvis launch-bounce report (poori report file me hai)."
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Share bounce report")
            val shareIntent = PendingIntent.getActivity(
                this, 3, chooser,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            NotificationCompat.Action(0, "Share bounce report", shareIntent)
        }.getOrNull()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis_core)
            .setContentTitle("JARVIS AI Autonomous Agent")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "🎙️ Speak", listenIntent)
            .addAction(0, "⏹ Stop Task", stopIntent)
        if (shareAction != null) {
            builder.addAction(shareAction)
            bounceShareActionAdded = true
        }
        return builder.build()
    }

    /**
     * 1.4.4: re-issue the persistent notification (same ID, same
     * text) so a freshly written bounce report file gains its
     * Share action. Calling startForeground again on a running
     * service only updates the notification.
     */
    private fun refreshBounceNotification() {
        runCatching {
            // 1.4.9 (Step 1g): same crash vector as onCreate —
            // the old 2-arg call re-applied all manifest types,
            // including microphone, even when RECORD_AUDIO was
            // absent. Route through the qualified helper.
            startForegroundSafe(buildNotification("JARVIS Active — Listening in background"))
        }
    }

    override fun onDestroy() {
        // 1.4.3 home-redirect gate: a clean service destroy
        // writes this marker; a battery/process kill writes
        // NOTHING after the last marker — that absence is the
        // kill-vs-crash signal in the next report.
        JarvisStateRepository.addDiagnostic("nav", "SVC_DESTROY", "Foreground service destroyed.")
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
