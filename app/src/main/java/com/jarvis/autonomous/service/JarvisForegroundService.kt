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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
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
import com.jarvis.autonomous.data.AiProvider
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
import com.jarvis.autonomous.voice.GeminiLiveSession
import com.jarvis.autonomous.voice.VadFailureReason
import com.jarvis.autonomous.voice.VadMonitorResult
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
import java.security.MessageDigest

class JarvisForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_autonomous_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_LISTENING = "com.jarvis.autonomous.ACTION_START_LISTENING"
        // 1.4.8 (G8 F4): standalone voice test — speaks a sample
        // line through the normal speak path.
        const val ACTION_SPEAK_TEST = "com.jarvis.autonomous.ACTION_SPEAK_TEST"
        const val ACTION_STOP_LISTENING = "com.jarvis.autonomous.ACTION_STOP_LISTENING"
        // 1.4.17: Live mode (Gemini real-time conversation).
        // Sent by the AI Brain Save button only; the overlay
        // pill tap stops Live by calling stopLiveInternal
        // directly (same service instance, no intent needed).
        const val ACTION_START_LIVE = "com.jarvis.autonomous.ACTION_START_LIVE"
        const val ACTION_STOP_LIVE = "com.jarvis.autonomous.ACTION_STOP_LIVE"
        const val ACTION_RETRY_MIC = "com.jarvis.autonomous.ACTION_RETRY_MIC"
        const val ACTION_STOP_TASK = "com.jarvis.autonomous.ACTION_STOP_TASK"
        const val ACTION_GREET = "com.jarvis.autonomous.ACTION_GREET"
        const val ACTION_TEXT_COMMAND = "com.jarvis.autonomous.ACTION_TEXT_COMMAND"
        const val EXTRA_COMMAND_TEXT = "extra_command_text"
        const val ACTION_REFRESH_OVERLAY = "com.jarvis.autonomous.ACTION_REFRESH_OVERLAY"
        /** WAVE-2 (§A): foreground-entry re-qualification — MainActivity
         * onResume sends this so a background-downgraded service
         * re-upgrades to the microphone type when the user returns
         * to foreground (no mic start needed). */
        const val ACTION_REFRESH_FGS_TYPES = "com.jarvis.autonomous.ACTION_REFRESH_FGS_TYPES"
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
     * 1.4.13 (F-E6): FGS-layer stop epoch, mirroring the VM
     * queue epoch. Bumped on every VoiceManager stop (via the
     * onStop wiring below); a speak still waiting on the FGS
     * mutex at stop time sees the change after acquiring and
     * drops instead of speaking after the stop. @Volatile
     * because stops arrive from mic/UI threads while speaks
     * run on the service scope.
     */
    @Volatile
    private var fgsSpeakEpoch: Long = 0L

    // ---- 1.4.17 Live mode state (service-owned) ----
    // The session owns ONLY the WebSocket; this service owns
    // the Live AudioRecord (capture) and AudioTrack (playback).
    // Every Live end path runs stopLiveInternal, whose finally
    // resumes the mic loop from the pause snapshot.
    @Volatile
    private var liveActive: Boolean = false
    private var liveSession: GeminiLiveSession? = null
    private var liveSnapshot: AlwaysOnMicManager.LivePauseSnapshot? = null
    private var liveRecord: AudioRecord? = null
    @Volatile
    private var liveCaptureRunning: Boolean = false
    private var liveCaptureThread: Thread? = null
    private var liveTrack: AudioTrack? = null
    private val livePlaybackQueue: ArrayDeque<ByteArray> = ArrayDeque()
    private val livePlaybackLock: Any = Any()
    @Volatile
    private var livePlaybackRunning: Boolean = false
    private var livePlaybackThread: Thread? = null
    @Volatile
    private var liveModelSpeaking: Boolean = false
    private var liveCapRunnable: Runnable? = null
    // 9-minute wall-clock cap, measured from setupComplete.
    private val liveCapMs: Long = 9 * 60 * 1000L
    // Cooldown anchor: set ONLY on fallback ends (cap/error),
    // never on manual ends — a re-toggle within 60 s of a
    // fallback is refused with the cooldown string.
    private var lastLiveFallbackMs: Long = 0L

    private fun isLiveActive(): Boolean = liveActive

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
        // WAVE-2 (§D-8): PREPARING from request time — the
        // indicator must NOT show "speaking" during the
        // SPK_START→first-audio gap. Swara first-audio /
        // streaming (or fallback onStart) upgrades this to
        // SPEAKING; the finally below clears it to IDLE on
        // every return path (done, failed, cancelled). Applies
        // to ALL origins through here, including greeting.
        JarvisStateRepository.setPlaybackState(
            com.jarvis.autonomous.data.PlaybackState.PREPARING
        )
        JarvisStateRepository.addDiagnostic(
            "tts", "SPK_START",
            "Speak started (origin=$origin, chars=${text.length})."
        )
        // 1.4.13 (F-E6): capture the stop epoch BEFORE waiting
        // — a stop during the wait must drop this speak.
        val epochAtEntry = fgsSpeakEpoch
        val ok = speakMutex.withLock {
            // 1.4.13 (F-L3): POST-mutex speak marker. SPK_START
            // above is pre-mutex and therefore misleading as a
            // speak duration (it includes queue waiting behind
            // a running speak); this marker fires only once the
            // mutex is actually held, so SPK_END total minus
            // this wait = real playback time, and
            // SWARA_CONNECT -> SWARA_FIRST_AUDIO stays readable
            // against it. No Swara-path change, markers only.
            JarvisStateRepository.addDiagnostic(
                "tts", "SPK_ACQUIRED",
                "Speak acquired the FGS mutex after " +
                    "${System.currentTimeMillis() - startMs}ms queue wait (origin=$origin)."
            )
            // WAVE-2 (§D-8): re-assert PREPARING on acquire —
            // a predecessor speak's finally may have cleared
            // the state to IDLE while this speak was queued.
            JarvisStateRepository.setPlaybackState(
                com.jarvis.autonomous.data.PlaybackState.PREPARING
            )
            // 1.4.13 (F-E6): stopped while waiting — drop
            // instead of speaking after the stop. SPK_END below
            // still logs (ok=false), so the drop is visible.
            if (fgsSpeakEpoch != epochAtEntry) {
                JarvisStateRepository.addDiagnostic(
                    "tts", "SPK_DROPPED",
                    "Speak dropped: stop arrived while waiting for the FGS speak mutex (origin=$origin)."
                )
                return@withLock false
            }
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
                    // WAVE-2 (§D-8): cleared in speakWrapped's
                    // finally — runs before the mutex releases,
                    // so a queued successor cannot interleave
                    // between playback end and this clear.
                    JarvisStateRepository.setPlaybackState(
                        com.jarvis.autonomous.data.PlaybackState.IDLE
                    )
                }
            } else {
                val result = runCatching { voiceManager.speakAndWait(text) }.getOrElse { t ->
                    JarvisStateRepository.addDiagnostic(
                        "tts", "SPK_START",
                        "Speak failed at start: ${t.javaClass.simpleName}: ${t.message ?: ""}"
                    )
                    false
                }
                // WAVE-2 (§D-8): same in-mutex clear as the
                // mic branch's finally (no mic manager here).
                JarvisStateRepository.setPlaybackState(
                    com.jarvis.autonomous.data.PlaybackState.IDLE
                )
                result
            }
        }
        JarvisStateRepository.addDiagnostic(
            "tts", "SPK_END",
            "Speak finished: ok=$ok, ms=${System.currentTimeMillis() - startMs}."
        )
        // WAVE-2 (§D-8): cleared-on-return — marker/done/
        // cancel all land here (SPK_END is unconditional), so
        // the indicator can never stay stuck on SPEAKING or
        // PREPARING after a speak returns. A queued successor
        // re-asserts PREPARING at its own SPK_ACQUIRED.
        JarvisStateRepository.setPlaybackState(
            com.jarvis.autonomous.data.PlaybackState.IDLE
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
    /** 1.4.15: SHA-256 hex of a file, for the VAD-copy pin check. */
    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

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
            // 1.4.13 (F-H2): this defer previously had NO
            // fallback timer (unlike deferVadUntilGreeting's
            // 5 s fallback) — if the greeting completion never
            // drained it, VAD stayed deferred forever. Now it
            // gets the same 5 s fallback: the runnable retries
            // startVadMonitor, which re-defers while greeting
            // still speaks (re-arming the fallback) and starts
            // VAD the moment greeting ends. drainPendingVad
            // cancels the runnable on the normal path.
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
            // 1.4.14 (D2): asset-copy outcome and enable outcome
            // are split BEFORE any collapse, so VAD_INIT_END can
            // say WHY started=false. The copy runs in its own
            // try/catch (COPY_FAILED if it throws); the enable
            // result carries its own failure-site reason. If the
            // copy threw but enable still started, that is a
            // success with NO reason token. If a full-size
            // filesDir copy already exists, the copy is skipped
            // — and a successful enable is then started=true
            // with NO reason, even if the APK asset is missing
            // (that is a success, never MODEL_MISSING).
            // 1.4.15: the skipped copy is now SHA-256-checked
            // against PIN_VAD_SHA and re-copied once on mismatch,
            // so "full-size" alone no longer proves it is good.
            val target = File(filesDir, "silero_vad.onnx")
            var copyFailed = false
            try {
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
                // 1.4.15 hardening: the size-only skip above passes
                // a full-size but corrupt filesDir copy to native
                // load. SHA-256 the copy against the CI-enforced
                // pin (AlwaysOnMicManager.PIN_VAD_SHA, identical
                // to the workflow's bundle gate) and re-copy once
                // on mismatch. copyFailed is set ONLY if the
                // re-copy also mismatches, so a repaired copy
                // stays a clean success; a still-bad copy surfaces
                // as COPY_FAILED when enable also fails (the
                // existing started -> null rule is unchanged).
                withContext(Dispatchers.IO) {
                    if (target.exists() &&
                        sha256Hex(target) != AlwaysOnMicManager.PIN_VAD_SHA
                    ) {
                        assets.open("silero_vad.onnx").use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (sha256Hex(target) != AlwaysOnMicManager.PIN_VAD_SHA) {
                            copyFailed = true
                        }
                    }
                }
            } catch (e: Exception) {
                copyFailed = true
            }
            val vadResult = runCatching {
                micManager.enableVadMonitor(target) { _ ->
                    // Echo-gated segments; no per-segment action:
                    // the recognizer timer remains the primary path.
                }
            }.getOrDefault(
                VadMonitorResult.Failed(VadFailureReason.VAD_CONSTRUCTION_FAILED)
            )
            val started = vadResult is VadMonitorResult.Started
            val failureReason: VadFailureReason? = when {
                started -> null
                copyFailed -> VadFailureReason.COPY_FAILED
                vadResult is VadMonitorResult.Failed -> vadResult.reason
                else -> null
            }
            JarvisStateRepository.addDiagnostic(
                "tts", "VAD_INIT_END",
                "Sherpa VAD init finished (started=$started)" +
                    (if (failureReason != null) " reason=$failureReason" else "") + "."
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
        // 1.4.3 (G8 N4 reword): loop OFF — Deepgram ONLY, everywhere.
        // Deepgram one-shot first (key present); otherwise the
        // VoiceManager single-reply path, which is Deepgram-session
        // only — there is no system-recognizer second path any more
        // (Deepgram-only mic policy, 1.4.5 FINAL). Speaking stays
        // Swara either way.
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
            },
            // 1.4.13 (F-E6): every VM stop bumps the FGS epoch
            // so FGS-mutex waiters drop (see speakWrapped).
            onStop = { fgsSpeakEpoch++ }
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
        // WAVE-2 (§A): pump-resume paths (rms_spike /
        // vad_segment inside the manager) call resumeDeepgram
        // directly, with no service call site to refresh from —
        // so the manager fires this callback on every real
        // resume and the service re-qualifies here. Covers the
        // last refresh gap alongside the overlay-tap refresh
        // above and the existing mic-start refreshes.
        micManager.onMicResumed = { refreshForegroundTypes() }

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
                // 1.4.17: pill tap during Live = stop Live +
                // resume loop (new FIRST branch — the loop
                // below would otherwise mic-off entirely).
                if (isLiveActive()) {
                    stopLiveInternal(
                        GeminiLiveSession.EndKind.MANUAL, fallback = false
                    )
                } else if (!JarvisStateRepository.micLoopEnabled.value) {
                    startService(
                        Intent(this, JarvisForegroundService::class.java).apply {
                            action = ACTION_START_LISTENING
                        }
                    )
                } else if (::micManager.isInitialized && micManager.isDeepgramSuspended()) {
                    // WAVE-2 (§A): overlay-tap resume is a mic
                    // start/refresh — it previously called
                    // resumeDeepgram with NO refresh, so a
                    // background-downgraded service stayed
                    // specialUse-only after the resume.
                    micManager.resumeDeepgram("overlay-tap")
                    refreshForegroundTypes()
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
                    // 1.4.13 (Part A 4c): restore MUST publish
                    // the loop flag, like a tap start does. The
                    // old restore never called
                    // setMicLoopEnabled(true), so after every
                    // service recreation the manager loop ran
                    // (DEEPGRAM_OPEN, listening YES) while the
                    // header, the Command-tab button and the
                    // overlay pill all read loop=OFF / mode=off
                    // — the 2026-11-04 report's self-contradiction
                    // (listening YES + mode off). This publishes
                    // what restore already did; it changes NO
                    // policy (restore still runs only when the
                    // user-enabled flag persisted).
                    JarvisStateRepository.setMicLoopEnabled(true)
                    JarvisStateRepository.setMicFatal(null)
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
                    // 1.4.13 (F-H1): SUSPENDED-FIRST. A tap on an
                    // enabled-but-suspended loop must RESUME it
                    // (as the overlay pill already does) — the
                    // old path fell through to start(), which is
                    // a no-op when already enabled, so the tap
                    // did nothing and the stream stayed suspended
                    // (the "tap karta hoon, phir bhi nahi sunta"
                    // deaf trap). isLoopHealthy counts
                    // suspended-with-live-pump as healthy (resume
                    // paths exist), so it must NOT be consulted
                    // before this check — it would mask the
                    // suspended state from the tap.
                    if (micManager.isEnabled() && micManager.isDeepgramSuspended()) {
                        micManager.resumeDeepgram("command-tab-tap")
                        setMicLoopPersisted(true)
                        JarvisStateRepository.setMicFatal(null)
                        JarvisStateRepository.setMicLoopEnabled(true)
                        refreshForegroundTypes()
                        // The when is the last statement of
                        // onStartCommand, so returning its value
                        // here is the branch's early exit.
                        return START_STICKY
                    }
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
                // 1.4.17: mic-off stops BOTH Live and loop —
                // loop-off wins: end Live first (its finally
                // would otherwise restore a loop we are about
                // to stop), then stop the loop below.
                if (isLiveActive()) {
                    stopLiveInternal(
                        GeminiLiveSession.EndKind.MANUAL, fallback = false
                    )
                }
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

            ACTION_START_LIVE -> {
                // 1.4.17: AI Brain Save button path (gates live
                // inside startLive; idempotent when live).
                startLive()
            }

            ACTION_STOP_LIVE -> {
                // 1.4.17: AI Brain Save with Live toggled OFF.
                // ACTION_STOP_TASK deliberately does NOT stop
                // Live — only this action, the overlay tap,
                // mic-off, destroy, cap, goAway, or an error do.
                stopLiveInternal(
                    GeminiLiveSession.EndKind.MANUAL, fallback = false
                )
            }

            ACTION_STOP_TASK -> {
                voiceManager.stopSpeaking("user STOP_TASK")
                agentEngine.cancelActiveTask("Stopped by user.")
            }

            ACTION_GREET -> {
                // 1.4.17: greeting suppressed while Live is
                // active — the Live session owns the voice.
                if (isLiveActive()) {
                    JarvisStateRepository.addDiagnostic(
                        "live", "LIVE_ANNOUNCE_SUPPRESSED",
                        "Greeting suppressed during Live."
                    )
                    return START_STICKY
                }
                // 1.4.3: MainActivity sends this once on launch.
                if (::voiceManager.isInitialized) {
                    speakGreeting()
                } else {
                    pendingGreet = true
                }
            }

            ACTION_TEXT_COMMAND -> {
                // 1.4.17: text commands during Live are rejected
                // to the execution log, never routed to the
                // engine (Live is conversation only).
                if (isLiveActive()) {
                    JarvisStateRepository.addLog(
                        "Live mode on hai — pehle Live band karo, phir command do"
                    )
                    JarvisStateRepository.addDiagnostic(
                        "live", "LIVE_COMMAND_REJECTED",
                        "Text command rejected during Live (not routed to engine)."
                    )
                    return START_STICKY
                }
                val cmd = intent.getStringExtra(EXTRA_COMMAND_TEXT) ?: ""
                if (cmd.isNotBlank()) {
                    agentEngine.handleUserCommand(cmd)
                }
            }

            ACTION_ANNOUNCE_NOTIFICATION -> {
                // 1.4.17: announces suppressed while Live is
                // active (same diagnostic as greetings).
                if (isLiveActive()) {
                    JarvisStateRepository.addDiagnostic(
                        "live", "LIVE_ANNOUNCE_SUPPRESSED",
                        "Notification announce suppressed during Live."
                    )
                    return START_STICKY
                }
                if (!agentEngine.isTaskRunning() && prefs.autoAnnounceNotifications) {
                    val sender = intent.getStringExtra(EXTRA_NOTIF_SENDER) ?: ""
                    val msg = intent.getStringExtra(EXTRA_NOTIF_TEXT) ?: ""
                    if (sender.isNotBlank() && msg.isNotBlank()) {
                        agentEngine.announceIncomingNotification(sender, msg)
                    }
                }
            }

            ACTION_ANNOUNCE_CALL -> {
                // 1.4.17: call announces suppressed during Live.
                if (isLiveActive()) {
                    JarvisStateRepository.addDiagnostic(
                        "live", "LIVE_ANNOUNCE_SUPPRESSED",
                        "Call announce suppressed during Live."
                    )
                    return START_STICKY
                }
                if (!agentEngine.isTaskRunning() && prefs.autoAnnounceNotifications) {
                    val display = intent.getStringExtra(EXTRA_CALL_DISPLAY) ?: ""
                    if (display.isNotBlank()) {
                        agentEngine.announceIncomingCall(display)
                    }
                }
            }

            ACTION_REFRESH_FGS_TYPES -> {
                // WAVE-2 (§A): foreground-entry re-qualification
                // (MainActivity onResume). Level-triggered: if
                // the mic is live and RECORD_AUDIO is granted,
                // a background-downgraded service re-upgrades
                // here; if the mic is idle, this confirms the
                // specialUse-only downgrade. No mic state changes.
                refreshForegroundTypes()
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
        if (!::micManager.isInitialized) return liveActive
        return micManager.isEnabled() ||
            micManager.isWakeActive() ||
            micManager.isOneShotActive() ||
            liveActive
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

    /**
     * WAVE-2 (§A): returns the ACTUALLY applied type set and
     * records it in [lastFgsTypes] — the pre-wave-2 bug was
     * that [refreshForegroundTypes] recorded the COMPUTED set
     * even when this fallback ran, so a report could show
     * "microphone|specialUse (mic live)" while the system type
     * was specialUse-only. Now a fallback logs FGS_TYPES_ACTUAL
     * with computed-vs-actual, and lastFgsTypes always holds
     * the real applied set (null when even specialUse-only
     * failed, i.e. no foreground state at all).
     */
    private fun startForegroundSafe(notification: Notification): Int? {
        val requested = qualifiedForegroundTypes()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, requested)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            lastFgsTypes = requested
            return requested
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
            val fallback = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, fallback)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
            if (ok.isSuccess) {
                lastFgsTypes = fallback
                JarvisStateRepository.addDiagnostic(
                    "nav", "FGS_TYPES_ACTUAL",
                    "Foreground types ACTUAL: specialUse-only " +
                        "(computed was " +
                        if (requested and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) "microphone|specialUse" else "specialUse-only" +
                        " — fallback applied, computed record corrected)."
                )
                return fallback
            }
            ok.onFailure {
                JarvisStateRepository.addDiagnostic(
                    "nav", "FGS_START_FAILED",
                    "Foreground start failed even with specialUse-only (${it.javaClass.simpleName}) — service continues without foreground state."
                )
            }
            // 1.4.13 G8 note: total failure leaves NO foreground
            // state, so record null (the docstring above already
            // promises this) instead of retaining the previous
            // set, which would suppress the next recovery's
            // change-only FGS_TYPES_APPLIED marker. Nothing
            // reads lastFgsTypes as truth beyond that marker's
            // prev comparison, so this is marker-only.
            lastFgsTypes = null
            return null
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
        // WAVE-2 (§A): the APPLIED marker fires ONLY when the
        // actually-applied set (returned by startForegroundSafe)
        // equals the computed set AND changed — a fallback run
        // already logged FGS_TYPES_ACTUAL inside
        // startForegroundSafe and set lastFgsTypes to the real
        // specialUse-only set, so no APPLIED line can lie about it.
        val types = qualifiedForegroundTypes()
        val prev = lastFgsTypes
        val applied = startForegroundSafe(
            buildNotification("JARVIS Active — Listening in background")
        )
        if (applied != null && applied == types && applied != prev) {
            val micLive = applied and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
            JarvisStateRepository.addDiagnostic(
                "nav", "FGS_TYPES_APPLIED",
                "Foreground types now: " +
                    if (micLive) "microphone|specialUse (mic live)"
                    else "specialUse-only (mic idle)"
            )
        }
    }

    // ---- 1.4.17 Live mode implementation ----
    private val liveListener = object : GeminiLiveSession.Listener {
        override fun onSetupComplete() {
            JarvisStateRepository.addDiagnostic(
                "live", "LIVE_STARTED",
                "Live session ready (model gemini-3.8-live) — conversation only, no phone actions."
            )
            serviceScope.launch {
                startLiveCapture()
                val cap = Runnable {
                    serviceScope.launch {
                        stopLiveInternal(
                            GeminiLiveSession.EndKind.CAP, fallback = true
                        )
                    }
                }
                liveCapRunnable = cap
                wakeLockHandler.postDelayed(cap, liveCapMs)
            }
        }

        override fun onAudioFrame(pcm: ByteArray) {
            if (!liveActive) return
            synchronized(livePlaybackLock) {
                livePlaybackQueue.addLast(pcm)
                livePlaybackLock.notifyAll()
            }
            if (!liveModelSpeaking) {
                liveModelSpeaking = true
                JarvisStateRepository.setPlaybackState(
                    com.jarvis.autonomous.data.PlaybackState.SPEAKING
                )
            }
        }

        override fun onInterrupted() {
            interruptLivePlayback()
        }

        override fun onToolCallDropped() {
            // Session already logged LIVE_TOOLCALL_DROPPED;
            // nothing to answer, session continues.
        }

        override fun onEnded(kind: GeminiLiveSession.EndKind) {
            serviceScope.launch {
                stopLiveInternal(kind, fallback = true)
            }
        }
    }

    /**
     * 1.4.17: starts a Live session. Gates (refusals only log
     * + reset the persisted toggle — they never speak, except
     * the cooldown refusal which speaks its exact string):
     * already live, provider != GEMINI or blank Gemini key, mic
     * loop off, or within 60 s of a fallback end. On the normal
     * path the LIVE_STARTED line is spoken FIRST via
     * speakWrapped — it serialises behind any in-flight speak,
     * so by its return VoiceManager is not mid-speak (the
     * entry assert), and the mic speaking state is clear. Then
     * pauseForLive hands the mic over (pump-drain proven
     * there); Live AudioRecord + AudioTrack are created only
     * after the pump is proven dead. Capture starts only after
     * setupComplete (see liveListener). This never touches
     * speakMutex state, LlmClient, modelName, the fallback
     * chain, or provider handling — Live reads ONLY
     * prefs.apiKey, plus the provider gate above.
     */
    private fun startLive() {
        if (liveActive) {
            JarvisStateRepository.addDiagnostic(
                "live", "LIVE_ALREADY",
                "Live start ignored: a Live session is already active."
            )
            return
        }
        if (prefs.provider != AiProvider.GEMINI || prefs.apiKey.isBlank()) {
            prefs.liveModeEnabled = false
            JarvisStateRepository.addDiagnostic(
                "live", "LIVE_REFUSED",
                "Live refused: provider is not Gemini or no Gemini key saved."
            )
            return
        }
        if (!::micManager.isInitialized || !micManager.isEnabled()) {
            prefs.liveModeEnabled = false
            JarvisStateRepository.addDiagnostic(
                "live", "LIVE_REFUSED",
                "Live refused: mic loop is off — pehle mic on karo, phir Live on karo."
            )
            return
        }
        val nowMs = System.currentTimeMillis()
        if (lastLiveFallbackMs > 0L && nowMs - lastLiveFallbackMs < 60_000L) {
            JarvisStateRepository.addDiagnostic(
                "live", "LIVE_COOLDOWN",
                "Live re-toggle refused within 60s of a fallback end."
            )
            serviceScope.launch {
                runCatching {
                    speakWrapped(
                        "Live abhi thodi der ke liye band hai — ek minute baad phir try karo.",
                        "live-cooldown"
                    )
                }
            }
            return
        }
        serviceScope.launch {
            runCatching {
                speakWrapped(
                    "Live mode on hai — ab seedhi baat karo, koi action nahi hoga.",
                    "live-started"
                )
            }
            // A STOP sent while the line above was speaking
            // (Save OFF, overlay tap, mic-off) already flipped
            // the persisted toggle off while no live state
            // existed yet — honour it instead of starting a
            // session the user just cancelled. This is the only
            // interleave window: everything after this point in
            // the coroutine runs synchronously on Main.
            if (!prefs.liveModeEnabled) return@launch
            val snapshot = runCatching { micManager.pauseForLive() }.getOrNull()
            if (snapshot == null) {
                // Loop-off race, or pump-stuck abort (the pause
                // already restored the loop on the stuck path).
                abortLiveStart(null)
                return@launch
            }
            val record = createLiveRecord()
            if (record == null) {
                abortLiveStart(snapshot)
                return@launch
            }
            val track = createLiveTrack()
            if (track == null) {
                runCatching { record.stop() }
                runCatching { record.release() }
                abortLiveStart(snapshot)
                return@launch
            }
            liveSnapshot = snapshot
            liveRecord = record
            liveTrack = track
            liveActive = true
            JarvisStateRepository.setCurrentStepInfo("Live mode — baat chal rahi hai")
            refreshForegroundTypes()
            startLivePlayback()
            val session = GeminiLiveSession()
            liveSession = session
            val ok = runCatching { session.start(prefs.apiKey, liveListener) }
                .getOrDefault(false)
            if (!ok) {
                stopLiveInternal(
                    GeminiLiveSession.EndKind.ERROR, fallback = true
                )
            }
        }
    }

    /**
     * 1.4.17: failed-start path — no session ever became live,
     * so there is no cap/queue to tear down: just resume the
     * loop (when a snapshot exists), mark the cooldown, and
     * speak the generic fallback AFTER the loop resumes.
     */
    private fun abortLiveStart(snapshot: AlwaysOnMicManager.LivePauseSnapshot?) {
        if (snapshot != null) {
            runCatching { micManager.resumeFromLive(snapshot) }
            refreshForegroundTypes()
        }
        lastLiveFallbackMs = System.currentTimeMillis()
        JarvisStateRepository.addDiagnostic(
            "live", "LIVE_FALLBACK_ERROR",
            "Live start failed before setup — normal mic restored."
        )
        serviceScope.launch {
            runCatching {
                speakWrapped(
                    "Live me dikkat aayi hai — normal mic par wapas aa gaya hoon, baat yahin jaari rakh sakte ho.",
                    "live-fallback"
                )
            }
        }
    }

    /**
     * 1.4.17: ends a Live session on EVERY path (toggle OFF,
     * overlay tap, mic-off, destroy, 9-min cap, goAway, any
     * error). Order is fixed: cancel the cap, stop + release
     * the Live AudioRecord and AudioTrack, cancel the Live WS,
     * THEN resume the loop from the snapshot in a finally —
     * the fallback line is spoken only AFTER the loop resumes.
     * Manual ends (fallback=false) speak nothing, set no
     * cooldown, and reset the persisted toggle. Fallback ends
     * set the 60 s cooldown and speak the exact §(e) string
     * for the kind. ACTION_STOP_TASK deliberately never calls
     * this. Idempotent: a second call with no live state is a
     * no-op (the session's own onEnded cannot re-enter after
     * stop(), which clears liveSession first).
     */
    private fun stopLiveInternal(
        kind: GeminiLiveSession.EndKind,
        fallback: Boolean,
        resumeLoop: Boolean = true,
        refreshTypes: Boolean = true
    ) {
        // Manual ends reset the persisted toggle FIRST, even
        // when no live state exists yet (a stop sent while the
        // STARTED line was still speaking): the start coroutine
        // re-checks this flag after the speak and aborts.
        if (!fallback) prefs.liveModeEnabled = false
        if (!liveActive && liveSession == null && liveSnapshot == null) return
        liveActive = false
        liveCapRunnable?.let { wakeLockHandler.removeCallbacks(it) }
        liveCapRunnable = null
        liveCaptureRunning = false
        val record = liveRecord
        liveRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { liveCaptureThread?.join(1000L) }
        liveCaptureThread = null
        livePlaybackRunning = false
        synchronized(livePlaybackLock) {
            livePlaybackQueue.clear()
            livePlaybackLock.notifyAll()
        }
        val track = liveTrack
        liveTrack = null
        runCatching { track?.stop() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        runCatching { livePlaybackThread?.join(1000L) }
        livePlaybackThread = null
        liveSession?.stop()
        liveSession = null
        val snapshot = liveSnapshot
        liveSnapshot = null
        liveModelSpeaking = false
        JarvisStateRepository.setPlaybackState(
            com.jarvis.autonomous.data.PlaybackState.IDLE
        )
        JarvisStateRepository.setCurrentStepInfo("Ready — Say 'Hey Jarvis' or tap the Core")
        if (resumeLoop && snapshot != null && ::micManager.isInitialized) {
            runCatching { micManager.resumeFromLive(snapshot) }
        }
        if (refreshTypes) refreshForegroundTypes()
        JarvisStateRepository.addDiagnostic(
            "live", "LIVE_ENDED",
            "Live ended reason=$kind fallback=$fallback."
        )
        if (!fallback) {
            return
        }
        lastLiveFallbackMs = System.currentTimeMillis()
        val (text, code) = when (kind) {
            GeminiLiveSession.EndKind.CAP,
            GeminiLiveSession.EndKind.LIMIT -> Pair(
                "Live limit par pahunch gaya hai — normal mic par wapas aa gaya hoon. Thodi der baad phir Live try karna.",
                "LIVE_FALLBACK_LIMIT"
            )
            GeminiLiveSession.EndKind.NETWORK -> Pair(
                "Live connection toot gaya hai — normal mic par wapas aa gaya hoon.",
                "LIVE_FALLBACK_NETWORK"
            )
            GeminiLiveSession.EndKind.GOAWAY,
            GeminiLiveSession.EndKind.TIMEOUT -> Pair(
                "Live session khatam ho gayi hai — normal mic par wapas aa gaya hoon.",
                "LIVE_FALLBACK_TIMEOUT"
            )
            else -> Pair(
                "Live me dikkat aayi hai — normal mic par wapas aa gaya hoon, baat yahin jaari rakh sakte ho.",
                "LIVE_FALLBACK_ERROR"
            )
        }
        JarvisStateRepository.addDiagnostic("live", code, "Live fallback spoken after loop resume.")
        serviceScope.launch {
            runCatching { speakWrapped(text, "live-fallback") }
        }
    }

    private fun createLiveRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            16_000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16_000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            return null
        }
        return record
    }

    private fun createLiveTrack(): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            24_000, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(24_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        }.getOrNull()
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track?.release() }
            return null
        }
        runCatching { track.play() }
        return track
    }

    private fun startLiveCapture() {
        val record = liveRecord ?: return
        runCatching { record.startRecording() }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            serviceScope.launch {
                stopLiveInternal(
                    GeminiLiveSession.EndKind.ERROR, fallback = true
                )
            }
            return
        }
        liveCaptureRunning = true
        val thread = Thread {
            val buf = ByteArray(3200)
            try {
                while (liveCaptureRunning) {
                    val read = runCatching { record.read(buf, 0, buf.size) }
                        .getOrDefault(-1)
                    if (read > 0) {
                        liveSession?.sendPcm(buf, read)
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (_: Exception) {
                // Capture death ends via the session watchdog /
                // cap path; never throws out of the thread.
            }
        }
        liveCaptureThread = thread
        thread.start()
    }

    private fun startLivePlayback() {
        val track = liveTrack ?: return
        livePlaybackRunning = true
        val thread = Thread {
            while (true) {
                val chunk: ByteArray? = synchronized(livePlaybackLock) {
                    while (livePlaybackQueue.isEmpty() && livePlaybackRunning) {
                        runCatching { livePlaybackLock.wait() }
                    }
                    if (!livePlaybackRunning) null
                    else livePlaybackQueue.removeFirst()
                } ?: break
                runCatching { track.write(chunk, 0, chunk.size) }
                synchronized(livePlaybackLock) {
                    if (livePlaybackQueue.isEmpty() && liveModelSpeaking) {
                        liveModelSpeaking = false
                        JarvisStateRepository.setPlaybackState(
                            com.jarvis.autonomous.data.PlaybackState.IDLE
                        )
                    }
                }
            }
        }
        livePlaybackThread = thread
        thread.start()
    }

    /**
     * 1.4.17 server-native interruption: stop the Live
     * AudioTrack at once, drop the ENTIRE pending PCM queue,
     * keep capturing (mic thread untouched, no activity_end
     * sent — the session protocol has none). NEVER touches
     * BargeInDetector, TurnDetector, VoiceManager.stopSpeaking,
     * or micManager.setSpeaking on this path.
     */
    private fun interruptLivePlayback() {
        val track = liveTrack ?: return
        synchronized(livePlaybackLock) {
            livePlaybackQueue.clear()
        }
        runCatching { track.stop() }
        runCatching { track.flush() }
        runCatching { track.play() }
        liveModelSpeaking = false
        JarvisStateRepository.setPlaybackState(
            com.jarvis.autonomous.data.PlaybackState.IDLE
        )
        JarvisStateRepository.addDiagnostic(
            "live", "LIVE_INTERRUPTED",
            "Server interruption: model audio stopped, pending queue dropped, mic capture continues."
        )
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
        // 1.4.17: explicit Live end on destroy — audio + WS
        // released, loop NOT resumed (shutdown below stops it).
        if (isLiveActive() || liveSession != null || liveSnapshot != null) {
            stopLiveInternal(
                GeminiLiveSession.EndKind.MANUAL,
                fallback = false,
                resumeLoop = false,
                refreshTypes = false
            )
        }
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
        // 1.4.13 G8 MINOR: reset playback state on destroy.
        // speakWrapped sets PREPARING before waiting on
        // speakMutex; a speak cancelled while QUEUED (this
        // serviceScope.cancel() above, in the destroy path)
        // never reaches the in-mutex clears or the
        // post-withLock clear, so PREPARING would otherwise
        // survive in the singleton repository and MainActivity
        // could show "BOLNE KI TAYYARI…" with no audio until
        // the next speak. An outer try/finally around withLock
        // was NOT used: withLock releases the mutex before an
        // outer finally runs, so a queued successor could
        // acquire and re-assert PREPARING in that window and
        // the predecessor's finally would then clobber it to
        // IDLE mid-speak, and a wait-cancel clear without the
        // mutex could clobber the running holder's state.
        // Destroy is terminal — no successor can re-assert
        // after this point, and every speak finally clears to
        // IDLE anyway — so this reset cannot clobber anyone.
        JarvisStateRepository.setPlaybackState(
            com.jarvis.autonomous.data.PlaybackState.IDLE
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
