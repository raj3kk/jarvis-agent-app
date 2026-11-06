package com.jarvis.autonomous.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.data.JarvisStateRepository

/**
 * S1 — Always-on mic loop (JarvisAgentApp 1.4.0).
 *
 * The bug this fixes: VoiceManager's system SpeechRecognizer
 * self-stops on silence, so a user pause ("halt") ended the turn
 * and Jarvis acted on a partial command. This loop NEVER stops on
 * its own once enabled. The ONLY off switches are:
 *  1. the user's explicit "mic band karo" (see
 *     [TurnDetector.isMicStopCommand]), and
 *  2. the Settings mic toggle, which the coordinator wires to
 *     [stop] (this file owns no UI and no prefs key).
 *
 * SESSION RULE (coordinator-confirmed): mic-once-on-stays-on means
 * the listening session persists across tasks AND across app
 * backgrounding while the foreground service lives. Task execution
 * runs on the engine's own scope; this loop only delivers turns on
 * the main thread and never blocks a task. If Android kills the
 * foreground service, the mic restarts WITH the service (START_STICKY
 * in JarvisForegroundService) — that is an Android limit, not magic,
 * and no code here can listen without a live service process.
 *
 * WAKE WORD (correction 2026-11-03): the wake word IS "Jarvis"
 * (case-insensitive STT match in VoiceManager's onResults —
 * "jarvis", "hey jarvis", "ok jarvis", "sun jarvis"). It is the
 * re-activation path while this always-on loop is OFF: after
 * "mic band karo" the service restarts wake-word listening, and
 * a spoken "Jarvis" (alone, or "Jarvis <command>") re-enters the
 * session. While this loop RUNS, the coordinator suppresses
 * wake-word listening (service stops it before [start] and only
 * restarts it when the loop is not enabled), so two recognizers
 * never fight over the mic. The recognizer loop below is
 * session-gated by [start]/[stop].
 *
 * PRIMARY PATH — recognizer-only turn timer. Turn end comes from
 * [TurnDetector] (silence + completeness), never from the
 * recognizer's onEndOfSpeech/onResults. Every recognizer ending
 * (onError, onResults, onEndOfSpeech) immediately schedules a fresh
 * startListening while [isEnabled] is true, so a pause can never
 * kill the session. Partial results accumulate into
 * [TurnDetector]'s rolling buffer across restarts (see its
 * onPartial new-session append rule).
 *
 * STT LANGUAGE — Hinglish code-switch: the recognizer starts in
 * hi-IN (primary). If a whole recognizer session ends with NO
 * transcript at all (empty results, or NO_MATCH / SPEECH_TIMEOUT
 * with an empty buffer), the next session retries in en-IN. The
 * language resets to hi-IN after every completed turn. Language
 * support is device/recognizer-dependent; this is a retry order,
 * not a guarantee that either locale transcribes perfectly.
 *
 * ECHO GATE (S1 echo amendment): while Jarvis speaks, [isSpeaking]
 * is true. Two gates apply, and they are different paths:
 * - Echo gating lives in the recognizer transcript path below
 *   (barge-in scan + quarantine + echo-fingerprint stack) — no
 *   VAD segmenter exists any more (removed 1.4.23-PRO step 2c).
 * - Recognizer transcripts during TTS are still scanned, but ONLY
 *   for a barge-in ([BargeInDetector]): a leading "ruk"/"ruko"/
 *   "bas"/"chup"/"stop" (loud when RMS is known, echo-guarded
 *   against Jarvis's own current text) calls [stopSpeakingCallback]
 *   and stops speech. Ordinary transcripts during TTS are buffered
 *   as usual and only become a turn via the silence timer — they
 *   are never acted on mid-TTS by this class; the coordinator
 *   decides whether to queue or drop a turn that lands mid-speech.
 *
 * 1.4.23-PRO MIC POLICY (Item 1 staged; SUPERSEDES the 1.4.5
 * Deepgram-only policy): listening is the system SpeechRecognizer
 * ONLY — loop, one-shot, wake-word, everywhere. No API key, no
 * websocket, no per-minute cost. Every recognizer session ends
 * (onResults / onError / onEndOfSpeech) and immediately restarts
 * while the loop is enabled, so a pause can never kill the
 * session; hard errors re-enter through the capped backoff
 * (5s -> 30s) reconnect path instead of dying silently. Mic does
 * NOT auto-start on app open (tap-to-start); once the user
 * enables it, it stays ON with visible reconnect + auto-revive.
 * Mic off happens only on the user's explicit OFF action.
 * Trade-off (unchanged): no internet = no listening (the system
 * recognizer needs the network for online recognition); speaking
 * still works offline via the system voice (Swara itself needs
 * internet).
 *
 * HONEST LIMITS (1.4.23-PRO, frozen): a recognizer restart gap
 * (hundreds of ms – ~2s) can miss speech that starts exactly in
 * the gap; there is no speech_final signal (finals are
 * recognizer session finals); wake-after-silence is DEGRADED
 * (a fresh session after a long silence may miss the first
 * word); wake during media is slightly degraded; no internet =
 * no listening. NO latency improvement is claimed here — the
 * ~15s gap is LLM-side, not mic-side.
 *
 * THREADING: turn checks use [mainHandler]; SpeechRecognizer
 * callbacks arrive on the main thread (the recognizer is created
 * on it). Task work never runs here.
 *
 * PERMISSIONS (audited, see INTEGRATION_NOTES_Mic.md): the
 * manifest already declares RECORD_AUDIO, FOREGROUND_SERVICE (+
 * MICROPHONE + SPECIAL_USE), POST_NOTIFICATIONS and
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — no new permission is
 * needed. [start] still refuses to run without RECORD_AUDIO
 * granted, and the coordinator must only call [start] while the
 * foreground service is running, and must prompt the battery
 * exemption on first enable (see [buildBatteryExemptionIntent]).
 */
class AlwaysOnMicManager(
    context: Context,
    private val listener: AlwaysOnMicListener
) {

    // ---- All state is declared BEFORE the listener/runnable that
    // use it (kotlin_prepush_checks: declaration order). ----
    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val turnDetector = TurnDetector()
    private var enabled: Boolean = false
    private var listeningActive: Boolean = false

    private var currentSessionId: Int = 0
    /** B-C3: transcripts that arrived while speaking, held until
     * post-TTS silence instead of feeding the turn mid-speech. */
    private var quarantinedWhileSpeaking: MutableList<QuarantinedTranscript> = mutableListOf()
    /** B-M13: nested setSpeaking(true) calls must not be undone by
     * a single setSpeaking(false) while another speak is playing. */
    private var speakingDepth: Int = 0

    /** Current recognizer locale; hi-IN primary, en-IN fallback. */
    private var currentLanguage: String = LANGUAGE_PRIMARY_HI_IN
    private var emptySessionFallbackUsed: Boolean = false

    /** Set by the coordinator from VoiceManager.speakAndWait. */
    @Volatile
    var isSpeaking: Boolean = false
        private set

    /** Text Jarvis is currently speaking (echo guard input). */
    @Volatile
    var currentSpokenText: String = ""
        private set

    /**
     * WAVE-2 (§B): echo-fingerprint STACK — SUPERSEDES the
     * G8 MINOR-1 single-string count-retire mechanism (which
     * closed as specified at b63853: retire after the first
     * dropped final). MINOR-1's INTENT is preserved inside
     * this design (bounded, no repeat-stuck — see retire rules
     * below) but its mechanism is replaced, because a single
     * string OVERWRITES: at launch the greeting speak's
     * fingerprint was erased by the announce speak, so the
     * greeting's drain tail arrived unguarded. A new speak
     * PUSHES here and never erases prior entries, so the stack
     * covers at least the greeting + announce launch pair.
     * N = 5: matches the VoiceManager speak-queue capacity
     * (5 waiters), so the stack can hold every speak that can
     * be in flight or queued; the oldest entry evicts on the
     * 6th push (bounded — no unbounded growth, no unbounded
     * repeat-stuck). Content-gated ONLY — no timer, no grace
     * window: a live transcript drops exactly when it is a
     * subsequence of ANY stacked text, however late it arrives.
     *
     * RETIRE rules (finals only — interim drops NEVER retire,
     * verified at the live gate): (1) SEGMENT BOUNDARY — a
     * dropped final with speechFinal=true arms
     * [echoSegmentBoundaryPending]; the NEXT non-echo
     * transcript (a new segment's onset, interim or final)
     * retires the whole stack, so ALL finals of one tail
     * segment drop while a genuine fresh segment is heard.
     * (2) NON-ECHO FINAL — any genuine (non-echo) final
     * retires the stack outright. (3) TURN COMPLETE — a
     * completed turn was non-echo by construction, so it
     * retires the stack. (4) LOOP STOP — clears the stack.
     * NOT a retire trigger, by design: a new speak's PUSH
     * must NOT clear prior entries (that would reintroduce
     * the greeting-overwrite bug for the announce pair);
     * push bounds lifetime only via N-eviction. A stop
     * keyword still barges in during speech.
     */
    private val echoFingerprintStack: ArrayDeque<String> = ArrayDeque()
    private val ECHO_STACK_MAX = 6
    /**
     * 1.4.18 (SCREEN/MEDIA GATING): fresh user direction — set
     * on loop-enable tap and on wake-trigger match, consumed
     * exactly once by the next turn start (or on turn
     * completion), never left sticky. Restore does NOT set it
     * (restore is not a fresh user direction). While set, the
     * media gate at turn start never drops the turn.
     */
    private var userDirectedTurnPending: Boolean = false

    /** 1.4.18: service-supplied media-gate bypass, evaluated at
     * transcript-arrival time: true while a mid-task voice
     * reply is pending or an engine task is running. State
     * flags only, never timers. */
    var mediaGateBypass: (() -> Boolean)? = null

    /** 1.4.18: mark the next turn as user-directed (see above). */
    fun markUserDirectedTurn() {
        userDirectedTurnPending = true
    }

    /** 1.4.18: media-playback signal for the turn-start gate —
     * AudioManager.isMusicActive() sampled at transcript
     * arrival, no listener, no timer, no new permission. */
    private fun isMusicActive(): Boolean {
        val audioManager = runCatching {
            appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull()
        return audioManager?.isMusicActive == true
    }
    /** Armed by a dropped speechFinal final; cleared on retire. */
    private var echoSegmentBoundaryPending: Boolean = false
    /** Optional resume callback (WAVE-2 §A): fired when a
     * recognizer session opens after a reconnect, so the owning
     * service can re-qualify its FGS types (session restarts
     * have no service call site of their own). */
    var onMicResumed: (() -> Unit)? = null

    /**
     * 1.4.13 (F-E5): true between a barge-in trigger and the
     * speak coroutine's real exit (speakWrapped's finally ->
     * setSpeaking(false)). While true, the gate stays CLOSED
     * (isSpeaking is still true, so transcripts quarantine)
     * and a second barge-in keyword does NOT re-fire — the
     * real open event is the finally, never the callback.
     */
    private var speakStopping: Boolean = false

    private var stopSpeakingCallback: StopSpeakingCallback? = null

    // RMS baseline for the barge-in "loud" gate; tracked only while
    // NOT speaking, so Jarvis's own playback never raises it.
    private var rmsBaselineDb: Float? = null
    private var lastRmsDb: Float? = null

    // ---- System-mic STT state (1.4.23-PRO: SpeechRecognizer
    // sessions — sttPath is "system" once started, "not started"
    // before the first user-enabled start). ----
    /** Active STT path: "system" once started (Diagnostics + UI). */
    var sttPath: String = "not started"
        private set
    /** Why the current path is active (Diagnostics + UI line). */
    var sttPathReason: String = "not started"
        private set
    /** Last transcript script: devanagari|latin|mixed. */
    var lastTranscriptScript: String = "latin"
        private set
    /** The live loop recognizer; null between sessions. */
    private var loopRecognizer: SpeechRecognizer? = null
    /** True between onReadyForSpeech and the session's terminal callback. */
    @Volatile
    private var sessionActive: Boolean = false
    /** True between startSession() and onReadyForSpeech/onError. */
    @Volatile
    private var sessionStarting: Boolean = false
    /** True once the current session delivered any non-blank transcript. */
    private var sessionHadTranscript: Boolean = false
    // 1.4.7 (A1): SEGMENT assembly. A recognizer session ends at
    // every utterance pause and the next session starts fresh, but
    // the turn is the WHOLE multi-session utterance: without
    // assembly, a later session's interim REPLACED the buffer and
    // the first session's words were lost ("adha baat"). The
    // manager now assembles completed sessions' finals + the
    // current session's cumulative text, and feeds TurnDetector
    // that cumulative turn text (replace is then correct).
    private var completedSegmentsText: String = ""
    private var lastSegmentFinalText: String? = null
    private var segmentFinalSeen: Boolean = false

    // 1.4.23-PRO: NO terminal-stop design any more. A recognizer
    // failure while the user-enabled loop runs enters RECONNECTING
    // (visible banner + capped exponential backoff 5s -> 30s,
    // forever until the user turns the mic off) instead of
    // permanent silence. Only failures retrying cannot fix are
    // PERMANENT: RECORD_AUDIO revoked, recognition unavailable on
    // the device, or neither hi-IN nor en-IN supported — those
    // stop with onMicError (banner, carrying the real recognizer
    // error code) via stopPermanently(). Everything else revives.
    @Volatile
    private var reconnecting: Boolean = false
    private var reconnectAttempt: Int = 0
    private var reconnectRunnable: Runnable? = null
    // 1.4.24-PRO step 1 (RC-B): bounded fast retry for consecutive
    // BUSY (8) / CLIENT (5) errors — max 2 fast retries, 500ms
    // apart, then the normal capped backoff. The count resets on
    // onReadyForSpeech / onResults (a live session proves the
    // churn is over) and on start().
    private var busyRetryCount: Int = 0
    /** Pending 500ms BUSY/CLIENT fast-retry runnable, if any. */
    private var busyRetryRunnable: Runnable? = null
    // 1.4.24-PRO step 1 (RC-E): true when the last successful
    // (re)start recreated the recognizer. Gates the
    // SR_REUSED_SESSION diagnostic so the routine-reuse path logs
    // it once per recreate, not on every routine restart.
    private var lastRestartWasRecreate: Boolean = false
    // 1.4.17: true while the loop is paused for Live mode. The
    // loop stays enabled (so stop()/start() semantics are
    // untouched) but scheduleReconnect()/enterReconnecting()
    // no-op, the turn check only reschedules, and transcripts
    // are dropped — the Live session owns the mic instead.
    @Volatile
    private var livePaused: Boolean = false
    // Wake-word session (system recognizer, loop OFF only — see
    // A-H3 gate in startWakeListening): own recognizer, own
    // restart backoff. No key, no auth failure possible.
    private var wakeRecognizer: SpeechRecognizer? = null
    private var wakeSessionId: Int = 0
    private var wakeLanguage: String = LANGUAGE_PRIMARY_HI_IN
    private var wakeSessionHadTranscript: Boolean = false
    private var wakeEmptySessionFallbackUsed: Boolean = false
    private var wakeCallback: ((String) -> Unit)? = null
    private var wakeActive: Boolean = false
    private var wakeRestartRunnable: Runnable? = null
    private var wakeBackoffMs: Long = WAKE_RESTART_BASE_MS
    // One-shot session (loop OFF only): own recognizer + timeout.
    private var oneShotRecognizer: SpeechRecognizer? = null
    private var oneShotTimeoutRunnable: Runnable? = null
    private var oneShotDelivered: Boolean = false
    private var oneShotActive: Boolean = false
    @Volatile
    private var lastSpeechMs: Long = 0L


    /**
     * 1.4.23-PRO: the always-on loop is one SpeechRecognizer
     * session after another. 1.4.24-PRO step 1 (RC-E): ROUTINE
     * restarts reuse the live loop recognizer (same instance,
     * fresh listener + startListening from inside the terminal
     * callback — the AOSP-sanctioned pattern); only the backoff
     * path recreates it, so the async binder teardown can settle
     * during the backoff instead of racing the next start. Every
     * callback carries the session id it was created with;
     * callbacks from a destroyed session are ignored (destroy can
     * still deliver a late ERROR_CLIENT). All callbacks arrive on
     * the main thread (the recognizer is created on it).
     *
     * HONEST LIMIT: a restart gap (hundreds of ms – ~2s) can miss
     * speech that starts exactly in the gap, and there is no
     * speech_final signal — finals here are recognizer session
     * finals (speechFinal=false throughout).
     */
    private fun buildRecognizerIntent(language: String, partialResults: Boolean): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
    }

    private fun makeLoopListener(sessionId: Int): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (sessionId != currentSessionId) return
                if (!enabled || livePaused) return
                val wasReconnecting = reconnecting
                sessionStarting = false
                sessionActive = true
                reconnecting = false
                reconnectAttempt = 0
                busyRetryCount = 0
                lastSpeechMs = System.currentTimeMillis()
                if (wasReconnecting) {
                    JarvisStateRepository.addDiagnostic(
                        "stt", "SR_OPEN",
                        "System mic session opened (recovered=true)."
                    )
                }
                // A stale listening=YES must never survive a stop
                // queued before this callback: re-check live state
                // at post time.
                mainHandler.post {
                    if (!enabled || !sessionActive) return@post
                    listeningActive = true
                    listener.onListeningStateChanged(true)
                    if (wasReconnecting) {
                        listener.onMicRecovered()
                        onMicResumed?.invoke()
                    }
                }
            }

            override fun onBeginningOfSpeech() {
                if (sessionId != currentSessionId) return
                if (!enabled || livePaused) return
                lastSpeechMs = System.currentTimeMillis()
                turnDetector.onSpeechStarted(lastSpeechMs)
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (sessionId != currentSessionId) return
                if (!enabled) return
                // RMS for the barge-in loud gate, straight from the
                // recognizer (no PCM pump any more). lastRmsDb is
                // ALWAYS updated (even while speaking) so the loud
                // gate can see an interruption during speech; the
                // BASELINE stays frozen while speaking (Jarvis's own
                // playback must never raise it).
                lastRmsDb = rmsdB
                if (!isSpeaking) {
                    rmsBaselineDb = if (rmsBaselineDb == null) {
                        rmsdB
                    } else {
                        rmsBaselineDb!! * 0.9f + rmsdB * 0.1f
                    }
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                // No restart here: the terminal callback (onResults
                // or onError) follows and owns the restart.
                // Restarting here would destroy the recognizer
                // before its final result arrives and lose the
                // utterance.
                if (sessionId != currentSessionId) return
                if (!enabled || livePaused) return
                lastSpeechMs = System.currentTimeMillis()
            }

            override fun onError(error: Int) {
                if (sessionId != currentSessionId) return
                if (!enabled || livePaused) return
                sessionActive = false
                sessionStarting = false
                handleLoopRecognizerError(error)
            }

            override fun onResults(results: Bundle?) {
                if (sessionId != currentSessionId) return
                if (!enabled || livePaused) return
                sessionActive = false
                sessionStarting = false
                busyRetryCount = 0
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                if (text.isBlank()) {
                    // Empty session: no transcript at all — the
                    // language retry fires once, then immediate
                    // restart (routine silence, no banner).
                    if (!sessionHadTranscript && !emptySessionFallbackUsed) {
                        emptySessionFallbackUsed = true
                        currentLanguage = LANGUAGE_FALLBACK_EN_IN
                        JarvisStateRepository.addDiagnostic(
                            "stt", "SR_EMPTY_SESSION",
                            "Empty recognizer session — retrying next session in en-IN."
                        )
                    }
                    restartSessionNow()
                    return
                }
                sessionHadTranscript = true
                lastSpeechMs = System.currentTimeMillis()
                lastTranscriptScript = TurnDetector.scriptOf(text)
                JarvisStateRepository.setLastTranscriptScript(lastTranscriptScript)
                // A final has no speech_final on the system
                // recognizer (honest limit) — speechFinal=false.
                handleTranscript(text, isFinal = true, speechFinal = false)
                // handleTranscript may have stopped the loop
                // ("mic band karo") — restart only if still enabled.
                restartSessionNow()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (sessionId != currentSessionId) return
                if (!enabled || livePaused) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                if (text.isBlank()) return
                sessionHadTranscript = true
                lastSpeechMs = System.currentTimeMillis()
                handleTranscript(text, isFinal = false)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /**
     * Maps a loop-session error to its restart path. Routine
     * silence (NO_MATCH / SPEECH_TIMEOUT with an empty buffer)
     * restarts immediately with no banner — it is not a failure,
     * and the listening indicator stays up across the gap. A
     * language error retries once in the other locale, then stops
     * permanently (the device cannot do either locale). Permission
     * revocation stops permanently with the real recognizer code.
     * Everything else (network, server, audio, busy, client,
     * too-many-requests, server-disconnected) enters the
     * capped-backoff reconnect path (banner + listening OFF).
     */
    private fun handleLoopRecognizerError(error: Int) {
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (!sessionHadTranscript && !emptySessionFallbackUsed) {
                    emptySessionFallbackUsed = true
                    currentLanguage = LANGUAGE_FALLBACK_EN_IN
                    JarvisStateRepository.addDiagnostic(
                        "stt", "SR_EMPTY_SESSION",
                        "Empty recognizer session — retrying next session in en-IN."
                    )
                }
                restartSessionNow()
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                listeningActive = false
                listener.onListeningStateChanged(false)
                stopPermanently(
                    "RECORD_AUDIO revoked — mic loop stopped.",
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                )
            }
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                if (currentLanguage != LANGUAGE_FALLBACK_EN_IN) {
                    currentLanguage = LANGUAGE_FALLBACK_EN_IN
                    JarvisStateRepository.addDiagnostic(
                        "stt", "SR_LANGUAGE_RETRY",
                        "Recognizer language unsupported — retrying next session in en-IN."
                    )
                    restartSessionNow()
                } else {
                    listeningActive = false
                    listener.onListeningStateChanged(false)
                    stopPermanently(
                        "System speech recognition does not support hi-IN or en-IN on this device.",
                        error
                    )
                }
            }
            else -> {
                listeningActive = false
                listener.onListeningStateChanged(false)
                // 1.4.24-PRO step 1 (RC-B): BUSY (8) / CLIENT (5)
                // usually mean the new startListening raced the
                // previous session's async binder teardown — a
                // bounded fast retry (max 2, 500ms apart, via the
                // RECREATE path so the destroy settles) before
                // falling through to the capped backoff. All other
                // codes go straight to enterReconnecting.
                // Recognition callbacks already run on the main
                // thread, so enterReconnecting is called directly
                // (no mainHandler.post).
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_CLIENT
                ) {
                    if (busyRetryCount < 2) {
                        busyRetryCount++
                        JarvisStateRepository.addDiagnostic(
                            "stt", "SR_BUSY_RETRY",
                            "Recognizer busy/client error $error — fast retry $busyRetryCount/2 in 500ms."
                        )
                        val retryRunnable = Runnable {
                            busyRetryRunnable = null
                            if (!enabled || livePaused) return@Runnable
                            startSession()
                        }
                        busyRetryRunnable?.let { mainHandler.removeCallbacks(it) }
                        busyRetryRunnable = retryRunnable
                        mainHandler.postDelayed(retryRunnable, 500L)
                    } else {
                        JarvisStateRepository.addDiagnostic(
                            "stt", "SR_ERROR",
                            "Recognizer error $error — entering reconnect."
                        )
                        enterReconnecting("System mic error $error — reconnecting.")
                    }
                } else {
                    JarvisStateRepository.addDiagnostic(
                        "stt", "SR_ERROR",
                        "Recognizer error $error — entering reconnect."
                    )
                    enterReconnecting("System mic error $error — reconnecting.")
                }
            }
        }
    }

    // Turn-check loop: polls TurnDetector; emits Complete / the
    // single Incomplete per turn; never touches the recognizer.
    // B-C3: while Jarvis is speaking, the turn check is PAUSED
    // (re-posted, no check) — a turn must never complete mid-TTS
    // and quarantined transcripts are only flushed on silence
    // after speech ends (see [setSpeaking]).
    private val turnCheckRunnable = object : Runnable {
        override fun run() {
            if (!enabled) return
            // 1.4.17: while Live owns the mic the turn check
            // must NOT complete a stale pre-Live turn into the
            // engine (no-action boundary). A run queued before
            // the pause simply dies here (no reschedule) — the
            // resume re-posts exactly one chain, so two chains
            // can never run side by side.
            if (livePaused) {
                return
            }
            if (isSpeaking) {
                mainHandler.postDelayed(this, TurnThresholds.TURN_CHECK_INTERVAL_MS)
                return
            }
            val nowMs = System.currentTimeMillis()
            when (val outcome = turnDetector.check(nowMs)) {
                is TurnOutcome.Complete -> {
                    val text = outcome.text
                    turnDetector.reset()
                    resetTurnAssembly()
                    // 1.4.18: a completed turn consumes any
                    // pending user direction (never left sticky).
                    userDirectedTurnPending = false
                    // 1.4.13 (F-E1): this turn completed, so it
                    // was non-echo by construction (echo was
                    // filtered at flush / dropped live) — the
                    // echo stack retires here (WAVE-2 §B rule 3).
                    echoFingerprintStack.clear()
                    echoSegmentBoundaryPending = false
                    resetLanguageToPrimary()
                    if (TurnDetector.isMicStopCommand(text)) {
                        // TurnDetector.check already fast-tracks
                        // mic-stop phrases to Complete; they must
                        // never be emitted as a turn to the engine.
                        stop()
                        listener.onMicStoppedByUser()
                    } else {
                        listener.onTurnComplete(text)
                    }
                }
                is TurnOutcome.Incomplete -> {
                    listener.onTurnIncomplete(outcome.text, outcome.reason)
                }
                is TurnOutcome.Waiting -> {
                    // Nothing to do; keep waiting.
                }
            }
            if (enabled) {
                mainHandler.postDelayed(this, TurnThresholds.TURN_CHECK_INTERVAL_MS)
            }
        }
    }

    // ---- Public API ----

    /** True while the loop is enabled (between [start] and [stop]). */
    fun isEnabled(): Boolean = enabled

    /** 1.4.10 (Step 5): live mic-in-use signals for the FGS
     * type qualification (the service reads these, never prefs).
     * Wake counts as in-use across its restart backoff (the
     * session is live and will recapture); one-shot counts
     * only while its session is active. */
    fun isWakeActive(): Boolean = wakeActive
    fun isOneShotActive(): Boolean = oneShotActive

    /** Current recognizer locale ("hi-IN" or "en-IN"). */
    fun currentLanguage(): String = currentLanguage

    fun isRecordAudioGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts the always-on loop — system SpeechRecognizer sessions
     * (1.4.23-PRO; no key, no websocket). Safe to call twice
     * (returns true when already enabled — the service's
     * health-restart logic decides when a re-tap forces a real
     * restart via stop() + start()). A loop that is enabled but
     * has a dead session is revived by the service's stop() +
     * start(), never by start() alone. No network at start enters
     * RECONNECTING (visible banner + backoff) instead of dying —
     * the network may return. Recognition unavailable on the
     * device is a PERMANENT failure (banner via onMicError).
     */
    fun start(): Boolean {
        if (enabled) return true
        if (!isRecordAudioGranted()) {
            listener.onMicError(
                "RECORD_AUDIO not granted; mic loop not started.",
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            )
            return false
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            JarvisStateRepository.addDiagnostic(
                "stt", "NO_RECOGNIZER",
                "Mic start refused: system speech recognition is not available on this device."
            )
            listener.onMicError(
                "System speech recognition is not available on this device.",
                -1
            )
            return false
        }
        enabled = true
        reconnecting = false
        reconnectAttempt = 0
        busyRetryCount = 0
        quarantinedWhileSpeaking.clear()
        turnDetector.reset()
        // 1.4.19: clear any stale user direction on start —
        // a loop-OFF wake that left the flag set must not let
        // the next loop's first unrelated turn consume it.
        // Safe by ordering: tap/retry paths mark AFTER start()
        // returns; restore never marks.
        userDirectedTurnPending = false
        resetTurnAssembly()
        resetLanguageToPrimary()
        sttPath = "system"
        sttPathReason = if (hasNetwork()) {
            "System mic ready + network OK"
        } else {
            "No network at start — reconnecting when network returns"
        }
        JarvisStateRepository.setSttPath("system", sttPathReason)
        JarvisStateRepository.addDiagnostic("stt", "PATH_SYSTEM", sttPathReason)
        listener.onSttPathChanged("system", sttPathReason)
        if (!hasNetwork()) {
            enterReconnecting("No network — mic reconnects when network returns.")
        } else {
            startSession()
        }
        return true
    }

    /**
     * Starts one recognizer session, RECREATE path (main thread
     * only): destroys the previous session's recognizer, creates
     * a fresh one and calls startListening. Callers: start(),
     * the backoff expiry in scheduleReconnect(), the BUSY/CLIENT
     * fast retry, and resumeFromLive() — every path where the
     * old instance is already destroyed or the teardown needs
     * time to settle (the backoff / 500ms retry IS the settle).
     * Routine session ends use [startSessionReuse] instead and
     * never come here. The session id increments inside
     * stopRecognizerSession BEFORE the destroy, so late callbacks
     * from the old recognizer are ignored. A failed create is a
     * PERMANENT stop (recognition unavailable); a
     * SecurityException (permission revoked between check and
     * call) stops permanently with the real code; any other start
     * failure enters reconnecting. The turn-check chain is
     * (re)posted here — removeCallbacks first, so exactly one
     * chain runs.
     */
    private fun startSession() {
        if (!enabled || livePaused) return
        stopRecognizerSession()
        sessionStarting = true
        sessionHadTranscript = false
        val sessionId = currentSessionId
        lastSpeechMs = System.currentTimeMillis()
        val recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }.getOrNull()
        if (recognizer == null) {
            sessionStarting = false
            stopPermanently(
                "System speech recognition is not available on this device.",
                -1
            )
            return
        }
        loopRecognizer = recognizer
        recognizer.setRecognitionListener(makeLoopListener(sessionId))
        val started = runCatching {
            recognizer.startListening(buildRecognizerIntent(currentLanguage, true))
        }
        if (started.isFailure) {
            val e = started.exceptionOrNull()
            loopRecognizer = null
            runCatching { recognizer.destroy() }
            sessionStarting = false
            if (e is SecurityException) {
                stopPermanently(
                    "RECORD_AUDIO not granted — mic loop stopped.",
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                )
            } else {
                JarvisStateRepository.addDiagnostic(
                    "stt", "SR_START_FAILED",
                    "Recognizer startListening threw (${e?.javaClass?.simpleName}) — entering reconnect."
                )
                enterReconnecting("System mic start failed — reconnecting.")
            }
            return
        }
        lastRestartWasRecreate = true
        mainHandler.removeCallbacks(turnCheckRunnable)
        mainHandler.postDelayed(turnCheckRunnable, TurnThresholds.TURN_CHECK_INTERVAL_MS)
    }

    /**
     * 1.4.24-PRO step 1 (RC-E): ROUTINE restart — reuses the live
     * loop recognizer instead of destroying + recreating it.
     * Called from inside a terminal callback (onResults /
     * NO_MATCH / SPEECH_TIMEOUT / language retry) via
     * [restartSessionNow], i.e. the session has already ended, so
     * the same instance can startListening again immediately
     * (AOSP-sanctioned pattern) with no async-teardown race and
     * no ERROR_RECOGNIZER_BUSY. Increments the session id first
     * (late callbacks from the ended session are ignored) and
     * swaps in a fresh listener for the new id. If no live
     * instance exists (destroyed by a racing pause/stop), falls
     * back to the [startSession] recreate path. A startListening
     * throw on the reused instance destroys it and enters
     * reconnecting, exactly like the recreate path.
     */
    private fun startSessionReuse() {
        if (!enabled || livePaused) return
        val recognizer = loopRecognizer
        if (recognizer == null) {
            startSession()
            return
        }
        sessionStarting = true
        sessionHadTranscript = false
        currentSessionId++
        val sessionId = currentSessionId
        lastSpeechMs = System.currentTimeMillis()
        if (lastRestartWasRecreate) {
            JarvisStateRepository.addDiagnostic(
                "stt", "SR_REUSED_SESSION",
                "Routine restart reusing the live loop recognizer (no destroy/create)."
            )
        }
        lastRestartWasRecreate = false
        recognizer.setRecognitionListener(makeLoopListener(sessionId))
        val started = runCatching {
            recognizer.startListening(buildRecognizerIntent(currentLanguage, true))
        }
        if (started.isFailure) {
            val e = started.exceptionOrNull()
            loopRecognizer = null
            runCatching { recognizer.destroy() }
            sessionStarting = false
            if (e is SecurityException) {
                stopPermanently(
                    "RECORD_AUDIO not granted — mic loop stopped.",
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                )
            } else {
                JarvisStateRepository.addDiagnostic(
                    "stt", "SR_START_FAILED",
                    "Recognizer startListening threw (${e?.javaClass?.simpleName}) on reused instance — entering reconnect."
                )
                enterReconnecting("System mic start failed — reconnecting.")
            }
            return
        }
        mainHandler.removeCallbacks(turnCheckRunnable)
        mainHandler.postDelayed(turnCheckRunnable, TurnThresholds.TURN_CHECK_INTERVAL_MS)
    }

    /**
     * Destroys the loop recognizer (idempotent). The session id
     * increments first, so any late callback from the destroyed
     * recognizer is ignored by the id guard.
     */
    private fun stopRecognizerSession() {
        currentSessionId++
        sessionActive = false
        sessionStarting = false
        val recognizer = loopRecognizer
        loopRecognizer = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
    }

    /** Immediate session restart (routine session end, no banner). No-op when the loop is off or Live owns the mic. Reuses the live recognizer — see [startSessionReuse]. */
    private fun restartSessionNow() {
        if (!enabled || livePaused) return
        startSessionReuse()
    }

    /**
     * Enter RECONNECTING (main thread by convention; recognizer
     * callbacks post here). Visible (banner via onMicReconnecting
     * + Diagnostics), auto-reviving (capped exponential backoff
     * 5s -> 30s, forever until the user turns the mic off), and
     * NEVER a silent permanent stop. The loop stays enabled, so
     * a later start() tap is a no-op — revival comes from the
     * backoff, or from the UI Retry (service stops then starts).
     */
    private fun enterReconnecting(reason: String) {
        if (!enabled || livePaused) return
        // 1.4.24-PRO step 1 (RC-A): guard on the PENDING runnable,
        // not on the flag. A stale reconnecting=true with no
        // runnable pending (runnable already consumed, session
        // never opened) must schedule a fresh backoff instead of
        // no-op-ing into a dead loop.
        if (reconnecting && reconnectRunnable != null) return
        val wasAlreadyReconnecting = reconnecting
        reconnecting = true
        listeningActive = false
        listener.onListeningStateChanged(false)
        listener.onMicReconnecting(reason)
        JarvisStateRepository.addDiagnostic(
            "stt", "RECONNECT_SCHEDULED",
            "Mic reconnecting (attempt ${reconnectAttempt + 1}): $reason"
        )
        if (wasAlreadyReconnecting) {
            JarvisStateRepository.addDiagnostic(
                "stt", "SR_RECONNECT_RESCHEDULED",
                "Reconnect re-entered while already reconnecting (stale flag, no runnable pending) — fresh backoff scheduled."
            )
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (livePaused) return
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        val delayMs = (RECONNECT_BASE_MS * (1L shl reconnectAttempt.coerceAtMost(3)))
            .coerceAtMost(RECONNECT_MAX_MS)
        val runnable = Runnable {
            reconnectRunnable = null
            if (!enabled) return@Runnable
            reconnectAttempt++
            if (!isRecordAudioGranted()) {
                stopPermanently(
                    "RECORD_AUDIO revoked — mic loop stopped.",
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                )
                return@Runnable
            }
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                stopPermanently(
                    "System speech recognition is not available on this device.",
                    -1
                )
                return@Runnable
            }
            if (!hasNetwork()) {
                // Still offline: stay reconnecting, back off again.
                reconnecting = true
                scheduleReconnect()
                return@Runnable
            }
            // 1.4.24-PRO step 1 (RC-A): clear the flag BEFORE the
            // fresh start — if that start fails and re-enters
            // reconnecting, the guard must see a clean flag and
            // schedule a new backoff (onReadyForSpeech clears it
            // too, on success).
            reconnecting = false
            startSession()
        }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    /**
     * PERMANENT stop (only for failures retrying cannot fix:
     * permission revoked, recognition unavailable, language
     * unsupported for both locales). Everything else goes through
     * enterReconnecting. Tears the loop down and surfaces
     * onMicError (banner, carrying the real recognizer error
     * code) — the user, not the loop, restarts it.
     */
    private fun stopPermanently(reason: String, recognizerErrorCode: Int = -1) {
        enabled = false
        reconnecting = false
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        busyRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        busyRetryRunnable = null
        busyRetryCount = 0
        mainHandler.removeCallbacks(turnCheckRunnable)
        stopRecognizerSession()
        listeningActive = false
        listener.onListeningStateChanged(false)
        turnDetector.reset()
        resetTurnAssembly()
        listener.onMicError(reason, recognizerErrorCode)
    }

    /**
     * Health for the service's re-tap restart: enabled AND any
     * sign of a live loop (session live, session starting, or
     * backing off). A loop with no live session and no pending
     * reconnect is UNHEALTHY (only a stop + start revives it).
     */
    fun isLoopHealthy(): Boolean {
        if (!enabled) return false
        // 1.4.24-PRO step 1 (RC-A): reconnecting with NO pending
        // runnable is UNHEALTHY (dead loop) so tap revival works.
        return sessionActive || sessionStarting || (reconnecting && reconnectRunnable != null)
    }

    /** True while the loop is paused for a Live session. */
    fun isLivePaused(): Boolean = livePaused

    /**
     * 1.4.17 Live handoff — snapshot of the loop state the
     * resume must restore exactly. No wake/one-shot callback is
     * stored here: wake is owned by VoiceManager and is never
     * restarted while the loop is on (A-H3 gate), and a one-shot
     * session is single-use by definition.
     */
    data class LivePauseSnapshot(
        val wasEnabled: Boolean,
        val wasReconnecting: Boolean,
        val wasWakeActive: Boolean,
        val wasOneShotActive: Boolean,
        val reconnectAttempt: Int,
        val lastSpeechMs: Long
    )

    /**
     * 1.4.17: pauses the loop for a Live session. MUST be called
     * on the main thread (the service's Live path runs on its
     * main-dispatcher scope). Refuses (null) when the loop is
     * not enabled. Order: snapshot first, then cancel the
     * reconnect runnable and set livePaused (so
     * scheduleReconnect()/enterReconnecting() no-op from here
     * on), stop wake + one-shot, destroy the recognizer session
     * (the Live session owns the mic instead), reset the
     * speaking state (depth=0, quarantine cleared). No pump to
     * drain any more — the destroy is synchronous, so the pause
     * cannot fail on a stuck thread.
     * This does NOT change stop()/start() semantics — enabled
     * stays true and language is untouched. A stale partial turn
     * is dropped by turnDetector.reset() + resetTurnAssembly()
     * here, so it can never fire after the Live session ends.
     */
    fun pauseForLive(): LivePauseSnapshot? {
        if (!enabled) return null
        val snapshot = LivePauseSnapshot(
            wasEnabled = enabled,
            wasReconnecting = reconnecting,
            wasWakeActive = wakeActive,
            wasOneShotActive = oneShotActive,
            reconnectAttempt = reconnectAttempt,
            lastSpeechMs = lastSpeechMs
        )
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        busyRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        busyRetryRunnable = null
        mainHandler.removeCallbacks(turnCheckRunnable)
        livePaused = true
        stopWakeListening()
        cancelListenOnce()
        stopRecognizerSession()
        speakingDepth = 0
        isSpeaking = false
        currentSpokenText = ""
        quarantinedWhileSpeaking.clear()
        turnDetector.reset()
        resetTurnAssembly()
        JarvisStateRepository.addDiagnostic(
            "live", "LIVE_PAUSED",
            "Recognizer session stopped before Live mic capture."
        )
        return snapshot
    }

    /**
     * 1.4.17: resumes the loop after a Live session ends. Call
     * in a finally on EVERY Live end path. Restores exactly as
     * the snapshot dictates: wasReconnecting -> re-enter the
     * backoff reconnect (never a fresh session — the network may
     * still be down); otherwise a fresh startSession(). NEVER
     * calls start() (that would reset turn assembly/language)
     * and NEVER resets reconnectAttempt. Nothing is re-created
     * on resume. Wake is NOT
     * restarted here: if the loop is back on, the A-H3 gate
     * forbids wake beside it, and wake ownership sits with
     * VoiceManager. If the loop was stopped mid-Live (mic-off
     * wins), nothing is restored — the loop stays off. If the
     * restore itself fails, the normal enterReconnecting path
     * takes over — the mic is never left dead silently.
     */
    fun resumeFromLive(snapshot: LivePauseSnapshot) {
        livePaused = false
        if (!enabled) {
            JarvisStateRepository.addDiagnostic(
                "live", "LIVE_RESUMED",
                "Live ended with loop off (mic-off wins) — loop stays off."
            )
            return
        }
        reconnectAttempt = snapshot.reconnectAttempt
        try {
            if (snapshot.wasReconnecting) {
                // The pause froze a reconnecting flag (backoff
                // cancelled, never cleared) — clear it before the
                // fresh enterReconnecting, else it no-ops on the
                // stale true and schedules no backoff.
                reconnecting = false
                enterReconnecting("Live exit: reconnecting.")
            } else {
                startSession()
            }
        } catch (e: Exception) {
            JarvisStateRepository.addDiagnostic(
                "live", "LIVE_RESUME_FAILED",
                "Live-exit loop restore threw (${e.javaClass.simpleName}) — entering reconnect."
            )
            reconnecting = false
            enterReconnecting("Live exit restore failed — reconnecting.")
        }
        JarvisStateRepository.addDiagnostic(
            "live", "LIVE_RESUMED",
            "Loop restored after Live (wasReconnecting=${snapshot.wasReconnecting})."
        )
    }







    /** Clears the 1.4.7 segment-assembly state with the turn. */
    private fun resetTurnAssembly() {
        completedSegmentsText = ""
        lastSegmentFinalText = null
        segmentFinalSeen = false
    }

    /**
     * 1.4.7 (A1): feeds one recognizer transcript through the
     * segment assembler and into TurnDetector as the cumulative
     * TURN text. Finals append their segment to
     * [completedSegmentsText] (duplicate re-sends of the same
     * final, and finals already present as the tail — e.g. via
     * the speak-quarantine flush — are skipped, never doubled);
     * interims feed completed-segments + current-segment text.
     * Returns false only when the transcript was a skipped
     * duplicate (caller then posts no partial line).
     */
    private fun feedAssembled(text: String, isFinal: Boolean, nowMs: Long): Boolean {
        val fedText: String? = if (isFinal) {
            if ((segmentFinalSeen && text == lastSegmentFinalText) ||
                completedSegmentsText == text ||
                completedSegmentsText.endsWith(" $text")
            ) {
                null
            } else {
                completedSegmentsText = if (completedSegmentsText.isBlank()) text
                    else "$completedSegmentsText $text"
                lastSegmentFinalText = text
                segmentFinalSeen = true
                completedSegmentsText
            }
        } else {
            segmentFinalSeen = false
            if (completedSegmentsText.isBlank()) text else "$completedSegmentsText $text"
        }
        if (fedText == null) return false
        if (isFinal) turnDetector.onFinal(fedText, nowMs, false)
        else turnDetector.onPartial(fedText, nowMs, false)
        listener.onPartialTranscript(turnDetector.currentText())
        return true
    }







    private fun hasNetwork(): Boolean {
        return runCatching {
            val cm = appContext.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as? ConnectivityManager ?: return@runCatching false
            val net = cm.activeNetwork ?: return@runCatching false
            cm.getNetworkCapabilities(net)?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            ) == true
        }.getOrDefault(false)
    }

    /**
     * Stops the loop. The ONLY programmatic off switch besides the
     * user's "mic band karo" transcript (handled in [handleTranscript]).
     * Stops the recognizer; does NOT stop the foreground service
     * itself (coordinator-owned).
     */
    fun stop() {
        if (!enabled && !reconnecting) return
        enabled = false
        reconnecting = false
        // A cancelled reconnect must never revive a stopped loop.
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        busyRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        busyRetryRunnable = null
        busyRetryCount = 0
        quarantinedWhileSpeaking.clear()
        speakingDepth = 0
        isSpeaking = false
        currentSpokenText = ""
        // 1.4.13 (F-E1/F-E5): a stopped loop holds no fingerprint
        // and no stopping state — a later start speaks fresh.
        // WAVE-2 (§B): the stack clears on stop (rule 4).
        echoFingerprintStack.clear()
        echoSegmentBoundaryPending = false
        // 1.4.18: loop stop also clears pending user direction.
        userDirectedTurnPending = false
        speakStopping = false
        mainHandler.removeCallbacks(turnCheckRunnable)
        stopRecognizerSession()
        sttPathReason = "stopped"
        listeningActive = false
        listener.onListeningStateChanged(false)
        turnDetector.reset()
        resetTurnAssembly()
    }

    /** Releases everything; call from the owning service's onDestroy. */
    fun shutdown() {
        stop()
        stopWakeListening()
        cancelListenOnce()
    }

    /**
     * Coordinator wiring for barge-in: called with VoiceManager's
     * stop path. CORRECTION (1.4.13, F-E4): the old docstring
     * claimed this stops "Swara / system TTS" — that was false.
     * VoiceManager.stopSpeaking stops Swara playback AND, since
     * 1.4.13, the system-TTS fallback via its static stop handle
     * — but it NEVER stops mic listening. A barge-in must cut
     * speech, never kill the mic.
     */
    fun setStopSpeakingCallback(callback: StopSpeakingCallback?) {
        stopSpeakingCallback = callback
    }

    /**
     * Coordinator MUST call this around every speak: true before
     * VoiceManager.speakAndWait, false right after it returns.
     * False flushes quarantined transcripts (echo-filtered) so
     * post-TTS speech forms a turn on fresh user audio only.
     */
    fun setSpeaking(speaking: Boolean, spokenText: String = "") {
        // B-M13: depth-counted — setSpeaking(false) from one
        // finished utterance must NOT reopen the echo gate while
        // another speak is still playing.
        if (speaking) {
            speakingDepth++
            isSpeaking = true
            currentSpokenText = spokenText
            // WAVE-2 (§B): PUSH, never overwrite — prior
            // entries keep their tail coverage (greeting +
            // announce pair). 1.4.18: the old "retire on next
            // speak" rule is DELETED — retirement stays ONLY
            // on: non-echo transcript (live gate), turn
            // complete, loop stop. The first genuine final
            // retires the stack anyway; no timer/grace window
            // is introduced by this removal.
            // (No clear here: a new speak never erases the stack.)
            if (spokenText.isNotBlank()) {
                echoFingerprintStack.addLast(spokenText)
                while (echoFingerprintStack.size > ECHO_STACK_MAX) {
                    echoFingerprintStack.removeFirst()
                }
            }
            // 1.4.13 (F-E1/F-E5): a new speak supersedes any
            // stopping state left by the previous speak's barge-in.
            speakStopping = false
        } else {
            if (speakingDepth > 0) speakingDepth--
            if (speakingDepth > 0) return
            isSpeaking = false
            currentSpokenText = ""
            // 1.4.13 (F-E5): this is the REAL open event (the
            // speak coroutine's finally) — the stopping window
            // ends here, and only here, does the gate open.
            speakStopping = false
            // B-C3: flush quarantined transcripts now, stamped
            // with post-TTS time, so they form a turn only after
            // post-TTS silence — never mid-speech.
            // 1.4.5 (item 4): ECHO FILTER on flush. Because the
            // recognizer keeps listening during speech (barge-in
            // alive), Jarvis's own voice can arrive as a
            // quarantined transcript;
            // flushing it verbatim would make Jarvis answer its own
            // echo. 1.4.18: the filter checks the WHOLE stack —
            // a held item drops when it is an echo of ANY stacked
            // spoken text (this subsumes the old single-text check
            // against the just-cleared currentSpokenText).
            // Genuine user speech during TTS (non-keyword) still
            // flushes as a turn; keyword barge-in already fired
            // promptly via handleTranscript.
            if (enabled && quarantinedWhileSpeaking.isNotEmpty()) {
                val held = quarantinedWhileSpeaking.toList()
                quarantinedWhileSpeaking.clear()
                val nowMs = System.currentTimeMillis()
                for (item in held) {
                    if (echoFingerprintStack.any { isEchoOfSpoken(item.text, it) }) continue
                    // 1.4.19: same shared wake matcher as the
                    // live path, applied to every held item
                    // (flush items are already-finalized
                    // history, so the live finals-only rule
                    // does not apply here). Bare wake sets the
                    // directed flag + LISTENING visual and drops
                    // the item (no spoken filler, no feed);
                    // wake + remainder feeds the remainder as
                    // the turn (flag persists until that turn
                    // completes, as in the live path).
                    val wakeMatched = TurnDetector.matchWakeTrigger(item.text)
                    if (wakeMatched != null) {
                        val (_, wakeRemainder) = wakeMatched
                        JarvisStateRepository.addDiagnostic(
                            "stt", "LOOP_WAKE_MATCHED",
                            "Loop wake trigger matched in speak-quarantine flush (content-free; trigger not logged)."
                        )
                        userDirectedTurnPending = true
                        if (wakeRemainder.isEmpty()) {
                            JarvisStateRepository.setAgentState(
                                AgentRunState.LISTENING,
                                "Listening... बोलो भाई क्या करना है?"
                            )
                            continue
                        }
                        feedAssembled(wakeRemainder, item.isFinal, nowMs)
                        continue
                    }
                    // 1.4.7 (A1): held items go through the same
                    // segment assembler as live transcripts, so
                    // the post-speech turn continues from them
                    // instead of a later live transcript
                    // replacing them.
                    feedAssembled(item.text, item.isFinal, nowMs)
                }
            }
        }
    }

    /**
     * Coordinator MUST call this for an Incomplete turn — the
     * turn continues silently (1.4.18: no filler speak) and
     * this starts the ~5s continuation window in [TurnDetector].
     */
    fun continueAfterIncompletePrompt() {
        turnDetector.markIncompletePromptSent(System.currentTimeMillis())
    }

    /**
     * Battery-exemption prompt intent for first enable. The
     * coordinator launches this (startActivity) when the user first
     * turns the mic on and the app is not yet exempt; the manifest
     * already declares REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
     * Returns null only if the intent cannot be built (never on
     * API 23+ in practice).
     */
    fun buildBatteryExemptionIntent(): Intent? {
        return runCatching {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:" + appContext.packageName)
            }
        }.getOrNull()
    }

    // ---- Internals (main thread unless noted) ----

    private fun handleTranscript(
        text: String,
        isFinal: Boolean,
        speechFinal: Boolean = false
    ) {
        // 1.4.17: while Live owns the mic, a transcript runnable
        // posted just before the pause must never feed a turn —
        // the recognizer session is already destroyed, so
        // anything here is stale pre-Live audio.
        if (livePaused) return
        if (text.isBlank()) {
            // A-M6: an empty session flips NOTHING — heard-nothing
            // is not a locale failure. The fallback fires only
            // after a transcribed session fails (see onError).
            return
        }
        // A-M6: ANY successful (non-empty) transcript resets the
        // locale to primary — not just completed turns.
        resetLanguageToPrimary()
        // B-C3: barge-in is FINAL transcripts only, checked
        // first while speaking; a leading stop keyword interrupts
        // TTS and never becomes a turn by itself.
        // 1.4.13 (F-E5): while a speak is already stopping, a
        // second keyword does NOT re-fire — it quarantines like
        // any other in-flight transcript and is flushed (echo
        // filtered) when the speak coroutine really exits. The
        // gate opens ONLY at speakWrapped's finally, never here:
        // this branch stops playback and marks stopping, but
        // deliberately does NOT call setSpeaking(false) — the
        // old immediate call reopened the gate while the
        // playback drain tail was still sounding, so tail echo
        // fed live turns. Pre-barge-in quarantined items are
        // discarded here (same net effect as the old
        // flush-then-reset): the barge-in replaces them.
        if (isFinal && !speakStopping && BargeInDetector.isBargeIn(
                transcript = text,
                isSpeaking = isSpeaking,
                spokenText = currentSpokenText,
                rmsDb = lastRmsDb,
                rmsBaselineDb = rmsBaselineDb
            )
        ) {
            // 1.4.5 (item 5): NO log here any more — the callback
            // (VoiceManager.stopSpeaking("barge-in keyword")) logs
            // the single SPEAK_ABORT entry. Logging here too made
            // ONE barge-in produce TWO abort entries in Diagnostics.
            speakStopping = true
            stopSpeakingCallback?.stopSpeaking()
            quarantinedWhileSpeaking.clear()
            val remainder = BargeInDetector.remainderAfterKeyword(text)
            if (remainder.isNotEmpty()) {
                // "ruk, youtube band karo" -> the follow-up command
                // seeds the next turn; a bare "ruk" seeds nothing.
                // 1.4.7 (A1): the assembly seeds from the remainder
                // too, so a following live transcript continues
                // from it instead of replacing it.
                turnDetector.reset()
                resetTurnAssembly()
                turnDetector.onPartial(remainder, System.currentTimeMillis(), isNewSession = true)
                completedSegmentsText = remainder
                listener.onPartialTranscript(remainder)
            } else {
                turnDetector.reset()
                resetTurnAssembly()
            }
            return
        }
        // B-C3: ordinary transcripts timestamped while speaking
        // are QUARANTINED (held, never fed to the turn detector
        // mid-TTS) and flushed by setSpeaking(false) on post-TTS
        // silence. Echo of Jarvis's own voice therefore never
        // becomes a turn while it is still speaking.
        if (isSpeaking) {
            if (quarantinedWhileSpeaking.size >= MAX_QUARANTINED) {
                quarantinedWhileSpeaking.removeAt(0)
            }
            quarantinedWhileSpeaking.add(QuarantinedTranscript(text, isFinal))
            return
        }
        // 1.4.19 (LOOP WAKE): loop-ON wake matching, AFTER the
        // speaking-quarantine branch above and BEFORE the media
        // gate below — gate order is deliberately unchanged
        // (media/echo gates are NOT reordered). Gated to finals
        // only + turn-inactive (TurnDetector current text
        // blank) + not livePaused (already early-returned at
        // the top of this function). Echo exclusion FIRST: if
        // the raw transcript is an echo of any stacked spoken
        // text, matching is SKIPPED entirely and the transcript
        // falls through to the existing echo drop below — a
        // wake word inside Jarvis's own TTS output never
        // self-triggers. Matched WITH remainder: the directed
        // flag is set, the local text is replaced by the
        // remainder, and execution falls through — the media
        // gate below sees the bypass and consumes the flag at
        // turn start, and the echo gate still applies to the
        // remainder. Matched BARE: the directed flag is set +
        // the same LISTENING visual as VoiceManager's mic tap,
        // nothing is fed, return — NO spoken filler (no
        // greeting speak, no "main sun raha hoon" line), visual
        // only. The flag persists until the next turn
        // start/completion or loop stop — identical to existing
        // loop-enable-tap semantics; NO timer is introduced.
        // The loop-OFF wake session in VoiceManager is
        // UNCHANGED by this block (coordinator Reading A).
        var effectiveText = text
        if (isFinal && turnDetector.currentText().isBlank()) {
            val rawIsEcho = echoFingerprintStack.any { isEchoOfSpoken(text, it) }
            if (!rawIsEcho) {
                val wakeMatched = TurnDetector.matchWakeTrigger(text)
                if (wakeMatched != null) {
                    val (_, wakeRemainder) = wakeMatched
                    JarvisStateRepository.addDiagnostic(
                        "stt", "LOOP_WAKE_MATCHED",
                        "Loop wake trigger matched (content-free; trigger/remainder not logged)."
                    )
                    userDirectedTurnPending = true
                    if (wakeRemainder.isEmpty()) {
                        JarvisStateRepository.setAgentState(
                            AgentRunState.LISTENING,
                            "Listening... बोलो भाई क्या करना है?"
                        )
                        return
                    }
                    effectiveText = wakeRemainder
                }
            }
        }
        // 1.4.18 (SCREEN/MEDIA GATING): turn-START gate only,
        // here in handleTranscript (main thread, where turn
        // state lives) — never in the pump thread. When no turn
        // is currently active (TurnDetector current text blank)
        // and media is playing (isMusicActive sampled at
        // transcript arrival), the transcript drops with a
        // content-free MEDIA_GATED diagnostic and is never fed
        // to TurnDetector — so idle-loop user speech while
        // media plays is suppressed at turn start until
        // playback pauses/stops or the user re-arms direction.
        // Mid-turn transcripts are NEVER gated (current text
        // non-blank skips this block). Bypasses (state flags,
        // never timers) are NEVER gated: (i) a pending voice
        // reply, (ii) a running engine task (both via
        // mediaGateBypass), (iii) a fresh user direction
        // (userDirectedTurnPending, consumed exactly once here
        // when the turn starts). The flush path is exempt: it
        // never passes through here and is already echo-filtered
        // stack-wide (see setSpeaking).
        if (turnDetector.currentText().isBlank()) {
            val directed = userDirectedTurnPending
            val bypassed = directed || mediaGateBypass?.invoke() == true
            if (!bypassed && isMusicActive()) {
                JarvisStateRepository.addDiagnostic(
                    "stt", "MEDIA_GATED",
                    "Transcript dropped at turn start while media is playing (turn not fed)."
                )
                return
            }
            if (directed) userDirectedTurnPending = false
        }
        // WAVE-2 (§B): LIVE-transcript echo gate, content-gated
        // NOT time-gated — SUPERSEDES the G8 MINOR-1
        // count-retire rule (retire after the first dropped
        // final). After a speak ends, the speaker drain tail
        // can still arrive as live transcripts; a live
        // transcript that is a subsequence of ANY stacked
        // spoken text is dropped — no grace timer, no silence
        // window, so genuine speech is never time-blocked and
        // tail echo is never time-let-in. Finals get one
        // diagnostic line each (bounded: one per dropped final);
        // interims drop silently like the flush.
        // RETIRE (finals only — interim drops NEVER retire, and
        // no branch below touches the stack for an interim):
        // a dropped final with speechFinal=true arms a segment
        // boundary (the tail segment is complete), but the
        // stack is NOT cleared yet — further finals of the
        // same tail segment must still drop. The next NON-echo
        // transcript (new segment onset, interim or final)
        // retires the stack, as does any non-echo FINAL even
        // without a boundary. No timer or grace window is added.
        val echoMatch = echoFingerprintStack.any { entry ->
            isEchoOfSpoken(effectiveText, entry)
        }
        if (echoMatch) {
            if (isFinal) {
                JarvisStateRepository.addDiagnostic(
                    "stt", "ECHO_DROPPED_LIVE",
                    "Live transcript dropped as echo of a stacked spoken text (content gate, no timer; stack kept for tail-segment finals)."
                )
                if (speechFinal) {
                    echoSegmentBoundaryPending = true
                }
            }
            return
        }
        // Guarded by non-empty: without it every genuine
        // final would log a spurious retire for an empty stack.
        if (echoFingerprintStack.isNotEmpty() &&
            (echoSegmentBoundaryPending || isFinal)
        ) {
            // Segment-boundary retire (pending armed by a
            // dropped speechFinal final, new segment onset
            // arrived) and/or non-echo-final retire (genuine
            // speech). Either way the stack's lifetime ends
            // here; a later tail final is no longer dropped,
            // which is the specified tradeoff (a tail arriving
            // after genuine speech has begun is acoustically
            // mixed with that speech anyway).
            val reason = if (echoSegmentBoundaryPending && isFinal) {
                "segment_boundary+non_echo_final"
            } else if (echoSegmentBoundaryPending) {
                "segment_boundary"
            } else {
                "non_echo_final"
            }
            echoFingerprintStack.clear()
            echoSegmentBoundaryPending = false
            JarvisStateRepository.addDiagnostic(
                "stt", "ECHO_STACK_RETIRED",
                "Echo stack retired ($reason; entries=0)."
            )
        }
        // 1.4.7 (A1): recognizer sessions are assembled into the
        // cumulative turn text (see feedAssembled) — each session
        // ends at a pause, so a later session's interim would
        // replace the buffer and the first session's words were
        // lost without assembly.
        // 1.4.19: feed the wake remainder (or the original text
        // when no wake matched) — effectiveText is the remainder
        // after a loop-wake match, else identical to text.
        feedAssembled(effectiveText, isFinal, System.currentTimeMillis())
    }

    /**
     * Echo test for the quarantine flush AND the live gate
     * (1.4.13, F-E2): delegates to BargeInDetector's shared test
     * — non-contiguous token subsequence + normalizeTokens
     * (punctuation strip + Devanagari map). The old
     * contiguous-only test here let mistranscribed echo
     * ("bus karo" for spoken "bas karo") through the flush
     * while the barge-in guard caught it — one test now, both
     * gates.
     */
    private fun isEchoOfSpoken(held: String, spoken: String): Boolean {
        return BargeInDetector.isEchoTranscript(held, spoken)
    }

    // ---- Wake-word session (system recognizer, loop OFF only) ----
    /**
     * Start continuous wake-word listening on the system
     * recognizer (1.4.23-PRO: no key, no websocket). A-H3 gate:
     * returns false when the always-on loop is enabled (two mic
     * owners must never fight), when RECORD_AUDIO is missing, or
     * when recognition is unavailable on the device. Finals are
     * delivered on the main thread to [callback]; the caller
     * (VoiceManager) applies matchWakeTrigger and the session
     * KEEPS running on non-matches. A session ends at every pause
     * and restarts immediately; hard errors restart with 5 s ->
     * 30 s capped backoff. No auth failure exists any more (no
     * key); a language error retries once in the other locale,
     * then stops the session.
     */
    fun startWakeListening(callback: (String) -> Unit): Boolean {
        if (enabled) return false
        if (!isRecordAudioGranted()) return false
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            JarvisStateRepository.addDiagnostic(
                "stt", "WAKE_NO_RECOGNIZER",
                "Wake-word session refused: system speech recognition is not available on this device."
            )
            return false
        }
        if (wakeActive) {
            wakeCallback = callback
            return true
        }
        wakeActive = true
        wakeCallback = callback
        wakeBackoffMs = WAKE_RESTART_BASE_MS
        wakeLanguage = LANGUAGE_PRIMARY_HI_IN
        wakeEmptySessionFallbackUsed = false
        startWakeSession()
        return true
    }

    private fun startWakeSession() {
        if (!wakeActive) return
        stopWakeRecognizer()
        wakeSessionHadTranscript = false
        val sessionId = wakeSessionId
        val recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }.getOrNull()
        if (recognizer == null) {
            JarvisStateRepository.addDiagnostic(
                "stt", "WAKE_NO_RECOGNIZER",
                "Wake session recognizer create failed — stopping wake listening."
            )
            stopWakeListening()
            return
        }
        wakeRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (sessionId != wakeSessionId) return
                JarvisStateRepository.setWakeListening(true)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (sessionId != wakeSessionId) return
                if (!wakeActive) return
                JarvisStateRepository.setWakeListening(false)
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        JarvisStateRepository.addDiagnostic(
                            "stt", "WAKE_NO_PERMISSION",
                            "Wake session stopped: RECORD_AUDIO not granted — no restart."
                        )
                        stopWakeListening()
                    }
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                        if (wakeLanguage != LANGUAGE_FALLBACK_EN_IN) {
                            wakeLanguage = LANGUAGE_FALLBACK_EN_IN
                            restartWakeSessionNow()
                        } else {
                            JarvisStateRepository.addDiagnostic(
                                "stt", "WAKE_NO_LANGUAGE",
                                "Wake session stopped: neither hi-IN nor en-IN supported — no restart."
                            )
                            stopWakeListening()
                        }
                    }
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Routine silence: flip locale once on an
                        // empty session, restart immediately.
                        if (!wakeSessionHadTranscript && !wakeEmptySessionFallbackUsed) {
                            wakeEmptySessionFallbackUsed = true
                            wakeLanguage = LANGUAGE_FALLBACK_EN_IN
                        }
                        restartWakeSessionNow()
                    }
                    else -> {
                        JarvisStateRepository.addDiagnostic(
                            "stt", "WAKE_ERROR",
                            "Wake session error $error — restarting in ${wakeBackoffMs / 1000}s."
                        )
                        scheduleWakeRestart()
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                if (sessionId != wakeSessionId) return
                if (!wakeActive) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isBlank()) {
                    restartWakeSessionNow()
                    return
                }
                wakeSessionHadTranscript = true
                wakeLanguage = LANGUAGE_PRIMARY_HI_IN
                wakeEmptySessionFallbackUsed = false
                val cb = wakeCallback
                if (cb != null) {
                    mainHandler.post { if (wakeActive) cb(text) }
                }
                restartWakeSessionNow()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val started = runCatching {
            recognizer.startListening(buildRecognizerIntent(wakeLanguage, false))
        }
        if (started.isFailure) {
            JarvisStateRepository.addDiagnostic(
                "stt", "WAKE_START_FAILED",
                "Wake session startListening threw (${started.exceptionOrNull()?.javaClass?.simpleName}) — restarting in ${wakeBackoffMs / 1000}s."
            )
            scheduleWakeRestart()
            return
        }
        // 1.4.8 (G8 F2): the wake session is LIVE only when the
        // recognizer actually signals readiness — onReadyForSpeech
        // asserts the flag; errors and the backoff clear it.
    }

    private fun restartWakeSessionNow() {
        if (!wakeActive || enabled) return
        startWakeSession()
    }

    private fun scheduleWakeRestart() {
        if (!wakeActive) return
        // 1.4.8 (G8 F2): backoff = NOT listening (the session
        // errored). The flag goes true again only if the
        // restarted session signals readiness.
        JarvisStateRepository.setWakeListening(false)
        wakeRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        val delayMs = wakeBackoffMs
        wakeBackoffMs = (wakeBackoffMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        val runnable = Runnable {
            wakeRestartRunnable = null
            if (!wakeActive || enabled) return@Runnable
            startWakeSession()
        }
        wakeRestartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun stopWakeRecognizer() {
        wakeSessionId++
        val recognizer = wakeRecognizer
        wakeRecognizer = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
    }

    fun stopWakeListening() {
        wakeActive = false
        // 1.4.8 (G8 F2): live wake state clears with the session.
        JarvisStateRepository.setWakeListening(false)
        wakeRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        wakeRestartRunnable = null
        stopWakeRecognizer()
        wakeCallback = null
    }

    // ---- One-shot session (system recognizer, loop OFF only) ----
    /**
     * Loop-OFF single reply on the system recognizer (1.4.23-PRO:
     * no key). A-H3 gate: when the loop is enabled the callback
     * gets null immediately (the service answers from the loop's
     * next turn instead). Blank/empty result or any error ->
     * null. The timeout bounds the whole session; the callback
     * fires exactly once (delivered guard).
     */
    fun listenOnceAsync(timeoutMs: Long, callback: (String?) -> Unit) {
        if (enabled) {
            callback(null)
            return
        }
        if (!isRecordAudioGranted()) {
            JarvisStateRepository.addDiagnostic(
                "stt", "ONE_SHOT_NO_PERMISSION",
                "One-shot refused: RECORD_AUDIO not granted."
            )
            callback(null)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            JarvisStateRepository.addDiagnostic(
                "stt", "ONE_SHOT_NO_RECOGNIZER",
                "One-shot refused: system speech recognition is not available on this device."
            )
            callback(null)
            return
        }
        cancelListenOnce()
        oneShotActive = true
        oneShotDelivered = false
        val recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }.getOrNull()
        if (recognizer == null) {
            oneShotActive = false
            callback(null)
            return
        }
        oneShotRecognizer = recognizer
        val timeout = Runnable {
            oneShotTimeoutRunnable = null
            if (oneShotDelivered) return@Runnable
            oneShotDelivered = true
            oneShotActive = false
            stopOneShotRecognizer()
            mainHandler.post { callback(null) }
        }
        oneShotTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, timeoutMs)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (recognizer !== oneShotRecognizer) return
                if (oneShotDelivered) return
                oneShotDelivered = true
                oneShotActive = false
                oneShotTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                oneShotTimeoutRunnable = null
                stopOneShotRecognizer()
                mainHandler.post { callback(null) }
            }
            override fun onResults(results: Bundle?) {
                if (recognizer !== oneShotRecognizer) return
                if (oneShotDelivered) return
                oneShotDelivered = true
                oneShotActive = false
                oneShotTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                oneShotTimeoutRunnable = null
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                stopOneShotRecognizer()
                mainHandler.post { callback(text.ifBlank { null }) }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val started = runCatching {
            recognizer.startListening(buildRecognizerIntent(LANGUAGE_PRIMARY_HI_IN, false))
        }
        if (started.isFailure) {
            oneShotDelivered = true
            oneShotActive = false
            oneShotTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            oneShotTimeoutRunnable = null
            stopOneShotRecognizer()
            callback(null)
        }
    }

    private fun stopOneShotRecognizer() {
        val recognizer = oneShotRecognizer
        oneShotRecognizer = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
    }

    fun cancelListenOnce() {
        oneShotTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        oneShotTimeoutRunnable = null
        oneShotDelivered = true
        oneShotActive = false
        stopOneShotRecognizer()
    }

    private fun resetLanguageToPrimary() {
        currentLanguage = LANGUAGE_PRIMARY_HI_IN
        emptySessionFallbackUsed = false
    }

    companion object {
        const val LANGUAGE_PRIMARY_HI_IN: String = "hi-IN"
        const val LANGUAGE_FALLBACK_EN_IN: String = "en-IN"

        /** 1.4.5: reconnect backoff base + cap (5s, doubling to
         *  30s) — auto-revive runs forever until the user turns
         *  the mic off; only permission / recognition /
         *  language failures stop it. */
        const val RECONNECT_BASE_MS: Long = 5_000L
        const val RECONNECT_MAX_MS: Long = 30_000L
        /** 1.4.5: wake-session restart backoff base (5s, doubling
         *  to 30s). No auth failure exists any more (no key). */
        const val WAKE_RESTART_BASE_MS: Long = 5_000L
        /** B-C3: cap on transcripts held while speaking. */
        const val MAX_QUARANTINED: Int = 20

    }
}

/** B-C3: one transcript held while Jarvis was speaking. */
private data class QuarantinedTranscript(val text: String, val isFinal: Boolean)

/** Callbacks from [AlwaysOnMicManager] to the coordinator. All arrive on the main thread. */
interface AlwaysOnMicListener {
    /** A complete turn (silence + completeness decided by TurnDetector). Act on this. */
    fun onTurnComplete(text: String)

    /**
     * The utterance looks incomplete. The coordinator continues
     * silently (1.4.18: no filler speak — listening indicator
     * only) and then calls
     * [AlwaysOnMicManager.continueAfterIncompletePrompt]. Exactly one
     * Incomplete per turn; after the ~5s window the text completes
     * via [onTurnComplete] anyway.
     */
    fun onTurnIncomplete(text: String, reason: String)

    /** The user said "mic band karo" (or a listed variant). The loop has already stopped. */
    fun onMicStoppedByUser()

    /** Rolling transcript for UI display; never act on this alone. */
    fun onPartialTranscript(text: String)

    /** Recognizer session live / not live, for the listening indicator. */
    fun onListeningStateChanged(listening: Boolean)

    /**
     * Fatal mic problem (permission, recognition unavailable,
     * language unsupported). The loop is not running.
     * [recognizerErrorCode] is the real SpeechRecognizer error
     * code when one exists (-1 when none applies, e.g. the
     * recognizer service itself is missing); the service carries
     * it into MicFatalState.
     */
    fun onMicError(reason: String, recognizerErrorCode: Int = -1)

    /**
     * 1.4.5: the user-enabled loop lost its recognizer session
     * and is auto-reconnecting (visible banner, NOT a stop).
     * Default no-op; the service overrides it to show the banner.
     */
    fun onMicReconnecting(reason: String) {}

    /**
     * 1.4.5: a reconnecting loop recovered (stream open again).
     * Default no-op; the service overrides it for a chat line.
     */
    fun onMicRecovered() {}

    /**
     * 1.4.3: STT path switch (system) + reason. Default
     * no-op so existing implementers keep compiling; the service
     * overrides it to post a visible chat line + Diagnostics.
     */
    fun onSttPathChanged(path: String, reason: String) {}
}

/** Stops whatever TTS engine is currently speaking. Wired by the coordinator to VoiceManager. */
fun interface StopSpeakingCallback {
    fun stopSpeaking()
}
