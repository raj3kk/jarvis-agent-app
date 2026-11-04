package com.jarvis.autonomous.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.autonomous.data.JarvisStateRepository
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.getVadModelConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * session-gated by [start]/[stop]; the optional VAD monitor
 * likewise only segments while a session is active, because
 * the coordinator only starts it after [start].
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
 * OPTIONAL PATH — Sherpa VAD (see [SherpaVadMonitor]). It is OFF
 * by default, needs a silero_vad.onnx model file the coordinator
 * supplies, and NEVER breaks the primary path: any VAD failure
 * (missing model, native error, mic-busy) silently disables the
 * monitor and the recognizer timer carries on.
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
 * - VAD: [SherpaVadMonitor] DROPS audio frames while speaking and
 *   calls Vad.reset() the moment speaking stops, so Jarvis's own
 *   voice never becomes a "user segment". Segmenting resumes only
 *   on fresh post-TTS audio.
 * - Recognizer transcripts during TTS are still scanned, but ONLY
 *   for a barge-in ([BargeInDetector]): a leading "ruk"/"ruko"/
 *   "bas"/"chup"/"stop" (loud when RMS is known, echo-guarded
 *   against Jarvis's own current text) calls [stopSpeakingCallback]
 *   and stops speech. Ordinary transcripts during TTS are buffered
 *   as usual and only become a turn via the silence timer — they
 *   are never acted on mid-TTS by this class; the coordinator
 *   decides whether to queue or drop a turn that lands mid-speech.
 *
 * 1.4.5 FINAL MIC POLICY (user order 2026-11-03 ~19:36 IST,
 * SUPERSEDES the 16:18 fallback decision): system SpeechRecognizer
 * is REMOVED ENTIRELY from this app — loop, one-shot, wake-word,
 * everywhere. Listening is Deepgram ONLY. There is no fallback:
 * every Deepgram gap (idle-resume incl. null VAD, one-shot
 * endpointing, key-change restart, chat/diagnostic lines, barge-in,
 * hung-connect timeout, dead-loop re-tap) is therefore a MUST-FIX
 * in this file, not an optional improvement. Mic does NOT
 * auto-start on app open (tap-to-start); once the user enables it,
 * it stays ON with visible reconnect + auto-revive — the 1.4.1
 * terminal-stop permanent-silence design is dead. Mic off happens
 * only on the user's explicit OFF action. Trade-off (user-decided):
 * no internet = no listening; speaking still works offline via
 * the system voice (Swara itself needs internet).
 *
 * THREADING: turn checks use [mainHandler]; Deepgram callbacks
 * arrive on OkHttp threads and are marshalled to main where
 * noted. Task work never runs here. The VAD loop runs on
 * [vadScope] (IO).
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
/**
 * 1.4.14 (D2): WHY a VAD init failed — diagnostics tokens only,
 * never paths, exception text, or message content. The reason is
 * set atomically where the failure is detected (Sherpa init /
 * enable), never recomputed later by the coordinator, because
 * mic state can change between start() success and the enable
 * call. COPY_FAILED is a coordinator-side bucket (asset copy
 * threw) — the mic manager never returns it; the foreground
 * service records it from its own copy try/catch.
 */
enum class VadFailureReason {
    MODEL_MISSING,
    VAD_CONSTRUCTION_FAILED,
    LOOP_OFF,
    COPY_FAILED
}

/**
 * 1.4.14 (D2): enableVadMonitor result. Started carries no
 * reason (success lines stay reason-free); Failed carries the
 * token from the failure site.
 */
sealed class VadMonitorResult {
    data object Started : VadMonitorResult()
    data class Failed(val reason: VadFailureReason) : VadMonitorResult()
}

class AlwaysOnMicManager(
    context: Context,
    private val listener: AlwaysOnMicListener
) {

    // ---- All state is declared BEFORE the listener/runnable that
    // use it (kotlin_prepush_checks: declaration order). ----
    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val turnDetector = TurnDetector()
    private val vadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var vadMonitor: SherpaVadMonitor? = null
    private var vadJob: Job? = null

    private var enabled: Boolean = false
    private var listeningActive: Boolean = false

    private var currentSessionId: Int = 0
    private var lastPartialSessionId: Int = -1
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
    private val ECHO_STACK_MAX = 5
    /** Armed by a dropped speechFinal final; cleared on retire. */
    private var echoSegmentBoundaryPending: Boolean = false
    /** Optional resume callback (WAVE-2 §A): fired by
     * [resumeDeepgram] on a real resume so the owning service
     * can re-qualify its FGS types (pump resumes have no
     * service call site of their own). */
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

    // ---- Deepgram-ONLY STT state (1.4.5 FINAL policy: no system
    // path exists any more — sttPath is "deepgram" once started,
    // "not started" before the first user-enabled start). ----
    /** Active STT path: "deepgram" once started (Diagnostics + UI). */
    var sttPath: String = "not started"
        private set
    /** Why the current path is active (Diagnostics + UI line). */
    var sttPathReason: String = "not started"
        private set
    /** Last Deepgram transcript script: devanagari|latin|mixed. */
    var lastTranscriptScript: String = "latin"
        private set
    private var deepgramClient: DeepgramSttClient? = null
    private var deepgramRecord: AudioRecord? = null
    private var deepgramPumpThread: Thread? = null
    @Volatile
    private var deepgramActive: Boolean = false
    @Volatile
    private var deepgramConnecting: Boolean = false
    @Volatile
    private var deepgramSuspended: Boolean = false
    private var deepgramFallbackTried: Boolean = false
    // 1.4.7 (A1): Deepgram SEGMENT assembly. Deepgram restarts its
    // cumulative transcript at every utterance segment, but the
    // turn is the WHOLE multi-segment utterance: without assembly,
    // a later segment's interim REPLACED the buffer and the first
    // segment's words were lost ("adha baat"). The manager now
    // assembles completed segments' finals + the current segment's
    // cumulative text, and feeds TurnDetector that cumulative turn
    // text (replace is then correct).
    private var completedSegmentsText: String = ""
    private var lastSegmentFinalText: String? = null
    private var segmentFinalSeen: Boolean = false
    // 1.4.7 (A2): audio captured while the stream was NOT ready
    // (idle-suspended, connecting, or reconnect-backing-off) is
    // buffered, not discarded — the utterance that triggers a
    // resume used to be lost ("kabhi nahi catch"). Capped at
    // MAX_SUSPEND_CHUNKS (1.4.13: 45 s, oldest dropped);
    // flushed in order by the pump before live audio once the
    // stream opens.
    private val suspendAudioLock = Any()
    private val suspendAudioChunks: ArrayDeque<ByteArray> = ArrayDeque()
    // 1.4.5 (FINAL policy): NO terminal-stop design any more.
    // A Deepgram failure while the user-enabled loop runs enters
    // RECONNECTING (visible banner + capped exponential backoff
    // 5s -> 30s, forever until the user turns the mic off) instead
    // of permanent silence. Only two failures are PERMANENT (the
    // loop cannot fix them by retrying): RECORD_AUDIO revoked and
    // Deepgram key missing/blank — those stop with onMicError
    // (banner) via stopPermanently(). Everything else revives.
    @Volatile
    private var reconnecting: Boolean = false
    private var reconnectAttempt: Int = 0
    private var reconnectRunnable: Runnable? = null
    private var connectTimeoutRunnable: Runnable? = null
    // Wake-word session (Deepgram-only, loop OFF only — see A-H3
    // gate in startWakeListening): own client/record/pump.
    private var wakeClient: DeepgramSttClient? = null
    private var wakeRecord: AudioRecord? = null
    private var wakePumpThread: Thread? = null
    private var wakeCallback: ((String) -> Unit)? = null
    private var wakeActive: Boolean = false
    private var wakeRestartRunnable: Runnable? = null
    private var wakeBackoffMs: Long = WAKE_RESTART_BASE_MS
    // 1.4.6: last time the no-key chat line was posted (the wake
    // start is retried after every speak, so an ungated line
    // would spam the chat — 5 min gate, same wording as the
    // loop-start no-key banner).
    private var lastWakeNoKeyChatMs: Long = 0L
    // One-shot session (loop OFF only).
    private var oneShotJob: Job? = null
    @Volatile
    private var lastSpeechMs: Long = 0L
    /** 1.4.8: last IDLE_RESUME time (pump thread + main thread).
     * The flap guard: a resume must never be followed by an
     * immediate re-suspend (see the pump suspend check). */
    @Volatile
    private var lastResumeMs: Long = 0L
    private var vadSegmentCallback: ((SpeechSegment) -> Unit)? = null

    /** Deepgram stream listener — one instance per loop start,
     *  reused across idle-suspend reconnects. */
    private val deepgramListener: DeepgramSttClient.Listener = object : DeepgramSttClient.Listener {
        override fun onOpen() {
            if (!enabled) return
            cancelConnectTimeout()
            val wasReconnecting = reconnecting
            deepgramConnecting = false
            deepgramActive = true
            deepgramSuspended = false
            reconnecting = false
            reconnectAttempt = 0
            lastSpeechMs = System.currentTimeMillis()
            JarvisStateRepository.addDiagnostic(
                "stt", "DEEPGRAM_OPEN",
                "Deepgram stream opened (recovered=$wasReconnecting)."
            )
            mainHandler.post {
                // 1.4.8: this post runs LATER — a stop between
                // onOpen and this post must not resurrect a stale
                // listening=YES (the loop-OFF + listening-YES
                // confusion in the 22:07:33 report: stop() sets
                // deepgramActive=false synchronously, while this
                // true was queued before the stop). Re-check live
                // state at post time; a queued recovery line is
                // dropped by the same guard.
                if (!enabled || !deepgramActive) return@post
                listeningActive = true
                listener.onListeningStateChanged(true)
                if (wasReconnecting) listener.onMicRecovered()
            }
            // 1.4.3: an idle-resume reconnect must NOT start a
            // second pump — the original pump thread is still
            // alive and owns the AudioRecord. Only start the
            // pump when none is running (first connect).
            if (deepgramPumpThread?.isAlive != true) {
                startDeepgramPump()
            }
        }

        override fun onInterim(text: String) {
            if (!enabled || !deepgramActive) return
            lastSpeechMs = System.currentTimeMillis()
            mainHandler.post { handleTranscript(text, isFinal = false) }
        }

        override fun onFinal(text: String, speechFinal: Boolean) {
            if (!enabled || !deepgramActive) return
            lastSpeechMs = System.currentTimeMillis()
            lastTranscriptScript = TurnDetector.scriptOf(text)
            JarvisStateRepository.setLastTranscriptScript(lastTranscriptScript)
            // WAVE-2 (§B): speechFinal is plumbed through to
            // the live gate (segment-boundary retire) — it was
            // previously dropped at this post boundary.
            mainHandler.post {
                handleTranscript(text, isFinal = true, speechFinal = speechFinal)
            }
        }

        override fun onError(code: String, message: String) {
            if (!enabled || (!deepgramActive && !deepgramConnecting)) return
            deepgramConnecting = false
            // One model retry (nova-3 -> nova-2/hi) before giving
            // up on Deepgram entirely — a model error is not a
            // reason to fall back to the system recognizer yet.
            if (!deepgramFallbackTried &&
                message.contains("model", ignoreCase = true)
            ) {
                deepgramFallbackTried = true
                deepgramActive = false
                runCatching { deepgramClient?.close() }
                deepgramClient = null
                val key = deepgramKey()
                if (key.isNotBlank()) {
                    deepgramConnecting = true
                    val client = DeepgramSttClient(key)
                    deepgramClient = client
                    client.connect(deepgramListener, useFallback = true)
                    scheduleConnectTimeout()
                    return
                }
            }
            cancelConnectTimeout()
            JarvisStateRepository.addDiagnostic(
                "stt", "DEEPGRAM_ERROR",
                "Deepgram error $code: ${message.take(120)} — entering reconnect (no fallback exists)."
            )
            mainHandler.post {
                enterReconnecting("Deepgram $code: ${message.take(120)}")
            }
        }

        override fun onClosed() {
            if (!enabled || (!deepgramActive && !deepgramConnecting) || deepgramSuspended) return
            cancelConnectTimeout()
            JarvisStateRepository.addDiagnostic(
                "stt", "DEEPGRAM_CLOSED",
                "Deepgram stream closed unexpectedly — entering reconnect (no fallback exists)."
            )
            mainHandler.post {
                enterReconnecting("Deepgram stream closed unexpectedly.")
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
     * only while its job is active. */
    fun isWakeActive(): Boolean = wakeActive
    fun isOneShotActive(): Boolean = oneShotJob?.isActive == true

    /** Current recognizer locale ("hi-IN" or "en-IN"). */
    fun currentLanguage(): String = currentLanguage

    fun isRecordAudioGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts the always-on loop — Deepgram ONLY (1.4.5 FINAL
     * policy; no system path exists any more). Safe to call
     * twice (returns true when already enabled — the service's
     * health-restart logic decides when a re-tap forces a real
     * restart via stop() + start()). 1.4.13 (F-H1): this no-op
     * is exactly why a tap on an enabled-but-SUSPENDED loop
     * must NOT come here — the service checks suspension FIRST
     * and calls resumeDeepgram() (as the overlay pill already
     * did); routing a suspended tap through start() would leave
     * the stream suspended forever. A blank Deepgram key is a
     * PERMANENT failure (banner via onMicError, never a silent
     * loop): without a key there is nothing to listen with. No
     * network at start enters RECONNECTING (visible banner +
     * backoff) instead of dying — the network may return.
     */
    fun start(): Boolean {
        if (enabled) return true
        if (!isRecordAudioGranted()) {
            listener.onMicError("RECORD_AUDIO not granted; mic loop not started.")
            return false
        }
        val key = deepgramKey()
        if (key.isBlank()) {
            JarvisStateRepository.addDiagnostic(
                "stt", "NO_DEEPGRAM_KEY",
                "Mic start refused: no Deepgram key saved — AI Brain me key daal kar Save dabao, phir mic on karo."
            )
            listener.onMicError(
                "Deepgram key nahi hai — AI Brain me key daal kar Save dabao, phir mic on karo."
            )
            return false
        }
        enabled = true
        reconnecting = false
        reconnectAttempt = 0
        quarantinedWhileSpeaking.clear()
        turnDetector.reset()
        resetTurnAssembly()
        resetLanguageToPrimary()
        deepgramFallbackTried = false
        sttPath = "deepgram"
        sttPathReason = if (hasNetwork()) {
            "Deepgram key present + network OK"
        } else {
            "No network at start — reconnecting when network returns"
        }
        JarvisStateRepository.setSttPath("deepgram", sttPathReason)
        JarvisStateRepository.addDiagnostic("stt", "PATH_DEEPGRAM", sttPathReason)
        listener.onSttPathChanged("deepgram", sttPathReason)
        if (!hasNetwork()) {
            enterReconnecting("No network — mic reconnects when network returns.")
        } else {
            startDeepgram(key)
        }
        return true
    }

    /**
     * Deepgram start: stops any VAD runLoop first (its AudioRecord
     * would steal the mic from the pump), then connects async —
     * onOpen starts the pump, onError/onClosed enter reconnecting.
     * Plus the 15 s HUNG-CONNECT timeout: a hung TCP connect used
     * to leave deepgramConnecting=true forever (no timeout
     * existed). Now a posted runnable closes the client and
     * enters reconnecting with CONNECT_TIMEOUT if still
     * connecting; it is cancelled on open/error/closed.
     */
    private fun startDeepgram(key: String) {
        stopVadLoopOnly()
        deepgramConnecting = true
        lastSpeechMs = System.currentTimeMillis()
        val client = DeepgramSttClient(key)
        deepgramClient = client
        client.connect(deepgramListener)
        scheduleConnectTimeout()
        mainHandler.postDelayed(turnCheckRunnable, TurnThresholds.TURN_CHECK_INTERVAL_MS)
    }

    private fun scheduleConnectTimeout() {
        cancelConnectTimeout()
        val runnable = Runnable {
            connectTimeoutRunnable = null
            if (!enabled) return@Runnable
            if (deepgramConnecting && !deepgramActive) {
                JarvisStateRepository.addDiagnostic(
                    "stt", "CONNECT_TIMEOUT",
                    "Deepgram connect hung >15s — closing client, entering reconnect."
                )
                runCatching { deepgramClient?.close() }
                deepgramClient = null
                deepgramConnecting = false
                enterReconnecting("Deepgram connect timeout (15s) — reconnecting.")
            }
        }
        connectTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout() {
        connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        connectTimeoutRunnable = null
    }

    /**
     * Enter RECONNECTING (main thread by convention; pump and WS
     * callbacks post here). Visible (banner via onMicReconnecting
     * + Diagnostics), auto-reviving (capped exponential backoff
     * 5s -> 30s, forever until the user turns the mic off), and
     * NEVER a silent permanent stop. The loop stays enabled, so
     * a later start() tap is a no-op — revival comes from the
     * backoff, from the UI Retry (service stops then starts),
     * or from the overlay mic tap (resumeDeepgram when suspended).
     */
    private fun enterReconnecting(reason: String) {
        if (!enabled || reconnecting) return
        reconnecting = true
        listeningActive = false
        listener.onListeningStateChanged(false)
        listener.onMicReconnecting(reason)
        JarvisStateRepository.addDiagnostic(
            "stt", "RECONNECT_SCHEDULED",
            "Mic reconnecting (attempt ${reconnectAttempt + 1}): $reason"
        )
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        val delayMs = (RECONNECT_BASE_MS * (1L shl reconnectAttempt.coerceAtMost(3)))
            .coerceAtMost(RECONNECT_MAX_MS)
        val runnable = Runnable {
            reconnectRunnable = null
            if (!enabled) return@Runnable
            reconnectAttempt++
            if (!isRecordAudioGranted()) {
                stopPermanently("RECORD_AUDIO revoked — mic loop stopped.")
                return@Runnable
            }
            val key = deepgramKey()
            if (key.isBlank()) {
                stopPermanently(
                    "Deepgram key nahi hai — AI Brain me key daal kar Save dabao, phir mic on karo."
                )
                return@Runnable
            }
            if (!hasNetwork()) {
                // Still offline: stay reconnecting, back off again.
                reconnecting = true
                scheduleReconnect()
                return@Runnable
            }
            startDeepgram(key)
        }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    /**
     * PERMANENT stop (only for failures retrying cannot fix:
     * permission revoked, key missing). Everything else goes
     * through enterReconnecting. Tears the loop down and surfaces
     * onMicError (banner) — the user, not the loop, restarts it.
     */
    private fun stopPermanently(reason: String) {
        enabled = false
        reconnecting = false
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        cancelConnectTimeout()
        mainHandler.removeCallbacks(turnCheckRunnable)
        stopDeepgramStream()
        listeningActive = false
        listener.onListeningStateChanged(false)
        stopVadMonitor()
        turnDetector.reset()
        resetTurnAssembly()
        listener.onMicError(reason)
    }

    /**
     * Health for the service's re-tap restart: enabled AND any
     * sign of a live loop (active stream, connecting, backing
     * off, or a live pump). Suspended-with-live-pump counts as
     * HEALTHY (resume paths exist); suspended-with-dead-pump is
     * UNHEALTHY (only a stop + start revives it).
     */
    fun isLoopHealthy(): Boolean {
        if (!enabled) return false
        return deepgramActive || deepgramConnecting || reconnecting ||
            (deepgramPumpThread?.isAlive == true)
    }

    /** True when the stream is idle-suspended (pump alive, WS closed). */
    fun isDeepgramSuspended(): Boolean = deepgramSuspended

    /**
     * VAD-independent resume (item 4): called from the pump's
     * RMS-spike path and from the overlay mic tap. No-op unless
     * enabled + suspended. Reconnects with the saved key and
     * logs IDLE_RESUME with the reason.
     */
    fun resumeDeepgram(reason: String = "manual") {
        if (!enabled || !deepgramSuspended) return
        deepgramSuspended = false
        // 1.4.8 — flap fix, part 1: the resume trigger IS speech
        // (an RMS spike / VAD segment), so the silence clock
        // restarts here. Without this, the pump's suspend check
        // (now - lastSpeechMs > 120s, still true from the
        // pre-suspend silence) fires on the very next pump
        // iteration — RESUME -> SUSPEND -> RESUME in 2 s, the
        // exact 22:04:49-51 pattern in the 22:07:33 report.
        // Part 2 (grace + connecting guard) is in the pump
        // suspend check below.
        val nowMs = System.currentTimeMillis()
        lastSpeechMs = nowMs
        lastResumeMs = nowMs
        val key = deepgramKey()
        if (key.isBlank()) {
            stopPermanently(
                "Deepgram key nahi hai — AI Brain me key daal kar Save dabao, phir mic on karo."
            )
            return
        }
        JarvisStateRepository.addDiagnostic(
            "stt", "IDLE_RESUME",
            "Deepgram stream resuming (reason=$reason)."
        )
        runCatching { deepgramClient?.close() }
        deepgramClient = null
        deepgramConnecting = true
        val client = DeepgramSttClient(key)
        deepgramClient = client
        client.connect(deepgramListener)
        scheduleConnectTimeout()
        // WAVE-2 (§A): pump resumes (rms_spike / vad_segment)
        // reach here with no service call site — notify the
        // service so it re-qualifies its FGS types. Service
        // call sites that already refresh will refresh twice;
        // refresh is change-only, so the duplicate is a no-op.
        onMicResumed?.invoke()
    }

    /** Closes the Deepgram stream + pump mic (idempotent). */
    private fun stopDeepgramStream() {
        cancelConnectTimeout()
        deepgramConnecting = false
        deepgramActive = false
        deepgramSuspended = false
        synchronized(suspendAudioLock) { suspendAudioChunks.clear() }
        runCatching { deepgramClient?.close() }
        deepgramClient = null
        val record = deepgramRecord
        deepgramRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        deepgramPumpThread = null
    }

    /**
     * 1.4.7 (A2): sends buffered not-ready audio (oldest first)
     * to the live stream, in order, before live audio. Called
     * ONLY from the pump thread when the stream is ready, so
     * ordering against live chunks is exact. One diagnostic
     * line proves the flush on the phone.
     */
    private fun drainSuspendBufferToStream() {
        val chunks: List<ByteArray>
        synchronized(suspendAudioLock) {
            if (suspendAudioChunks.isEmpty()) return
            chunks = suspendAudioChunks.toList()
            suspendAudioChunks.clear()
        }
        val client = deepgramClient ?: return
        var sent = 0
        for (chunk in chunks) {
            if (client.sendPcm(chunk, chunk.size)) sent++
        }
        if (sent > 0) {
            JarvisStateRepository.addDiagnostic(
                "stt", "SUSPEND_AUDIO_FLUSHED",
                "Flushed $sent buffered chunks (~${sent * 100}ms) captured while the stream was not ready."
            )
        }
    }

    /** Clears the 1.4.7 segment-assembly state with the turn. */
    private fun resetTurnAssembly() {
        completedSegmentsText = ""
        lastSegmentFinalText = null
        segmentFinalSeen = false
    }

    /**
     * 1.4.7 (A1): feeds one Deepgram transcript through the
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

    /** Single shared AudioRecord 16kHz mono: WS sender + VAD tap
     *  on the SAME buffers (never a second AudioRecord). While
     *  Jarvis speaks, mic audio is read-and-discarded (never
     *  streamed — no echo transcripts, no wasted credit). After
     *  [IDLE_SUSPEND_MS] of silence the WS suspends (cost) but
     *  the pump + VAD tap keep running; a VAD speech segment
     *  reconnects the stream (resume). */
    private fun startDeepgramPump() {
        val minBuf = AudioRecord.getMinBufferSize(
            16_000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            mainHandler.post {
                if (enabled) {
                    JarvisStateRepository.addDiagnostic(
                        "stt", "PUMP_ERROR",
                        "Mic buffer unavailable for Deepgram pump — entering reconnect."
                    )
                    enterReconnecting("Mic buffer unavailable — reconnecting.")
                }
            }
            return
        }
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16_000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            mainHandler.post {
                if (enabled) {
                    JarvisStateRepository.addDiagnostic(
                        "stt", "PUMP_ERROR",
                        "Mic unavailable for Deepgram pump — entering reconnect."
                    )
                    enterReconnecting("Mic unavailable — reconnecting.")
                }
            }
            return
        }
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { record.stop() }
            runCatching { record.release() }
            mainHandler.post {
                if (enabled) {
                    JarvisStateRepository.addDiagnostic(
                        "stt", "PUMP_ERROR",
                        "Mic busy — Deepgram pump could not start — entering reconnect."
                    )
                    enterReconnecting("Mic busy — reconnecting.")
                }
            }
            return
        }
        deepgramRecord = record
        // 1.4.13 (F-E3): platform AEC on the pump's own
        // AudioRecord session (VOICE_RECOGNITION capture).
        // Created here, released in the pump finally. If the
        // device has no AEC (create returns null) or enabling
        // throws, the pump runs on WITHOUT it and the software
        // echo gate stays primary — AEC absence NEVER silences
        // the mic and NEVER drops audio. Playback stays
        // USAGE_MEDIA (VoiceManager focus + Swara AudioTrack):
        // switching playback to VOICE_COMMUNICATION would risk
        // earpiece routing and hurt audibility, so it is
        // deliberately NOT done — AEC is an enhancement on top
        // of the software gate, not a replacement for it.
        var pumpAec: AcousticEchoCanceler? = null
        runCatching {
            AcousticEchoCanceler.create(record.audioSessionId)
        }.getOrNull().let { created ->
            pumpAec = created
            if (created != null) {
                runCatching { created.setEnabled(true) }.onFailure {
                    runCatching { created.release() }
                    pumpAec = null
                }
            }
        }
        if (pumpAec == null) {
            JarvisStateRepository.addDiagnostic(
                "stt", "AEC_UNAVAILABLE",
                "Platform AEC unavailable on this device — software echo gate stays primary (mic unaffected)."
            )
        }
        val thread = Thread {
            val shorts = ShortArray(1600)
            val bytes = ByteArray(3200)
            try {
                while (enabled && deepgramRecord == record) {
                    val read = runCatching {
                        record.read(shorts, 0, shorts.size)
                    }.getOrDefault(-1)
                    if (read <= 0) {
                        if (read < 0) break
                        deepgramClient?.sendKeepAliveIfIdle()
                        continue
                    }
                    updateRmsFromPcm(shorts, read)
                    // 1.4.5 (item 4): VAD-INDEPENDENT resume. The old
                    // code resumed ONLY inside the `if (monitor !=
                    // null)` VAD-tap block — with vadMonitor == null
                    // a suspended stream NEVER resumed (silent death;
                    // first post-idle utterance lost). Now an RMS
                    // spike (>10 dB over the frozen baseline) also
                    // resumes, via the same shared helper (reason
                    // rms_spike). Baseline stays frozen while
                    // speaking, so this also fires for interruptions
                    // during speech.
                    if (deepgramSuspended) {
                        val base = rmsBaselineDb
                        val cur = lastRmsDb
                        if (base != null && cur != null && cur >= base + 10f) {
                            mainHandler.post { resumeDeepgram("rms_spike") }
                        }
                    }
                    // VAD tap on the shared buffer (tap mode).
                    val monitor = vadMonitor
                    if (monitor != null) {
                        val segments = monitor.feedSamples(shorts, read)
                        if (segments.isNotEmpty()) {
                            if (deepgramSuspended) {
                                // Resume via the shared helper (logs
                                // IDLE_RESUME vad_segment). Pump thread
                                // -> main, because resume touches
                                // client state the WS callbacks use.
                                mainHandler.post { resumeDeepgram("vad_segment") }
                            }
                            val cb = vadSegmentCallback
                            if (cb != null) {
                                mainHandler.post {
                                    for (segment in segments) cb(segment)
                                }
                            }
                        }
                    }
                    // 1.4.5 (item 4): BARGE-IN ALIVE. The old branch
                    // streamed ONLY when !isSpeaking, so mic audio
                    // read during TTS speech was discarded and a
                    // user interrupting during speech could never
                    // be transcribed. Now audio streams during
                    // speech too; echo is handled by (a) RMS still
                    // tracked during speech (baseline frozen, so
                    // the loud gate can see interruptions) and (b)
                    // the quarantine echo filter on flush (see
                    // setSpeaking) + BargeInDetector echo guards.
                    // 1.4.7 (A2): stream-ready gating + suspend
                    // buffering. Audio streams ONLY when the
                    // stream is live AND ready (active, not
                    // suspended, not connecting, not backing
                    // off); otherwise it is BUFFERED (cap 45s,
                    // oldest dropped) while the stream is merely
                    // not ready yet, and discarded only when the
                    // loop is not running at all. The pump flushes
                    // the buffer in order before live audio once
                    // ready, so the utterance that triggered a
                    // resume is transcribed instead of lost.
                    for (i in 0 until read) {
                        val v = shorts[i].toInt()
                        bytes[i * 2] = (v and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    val streamReady = deepgramActive && !deepgramSuspended &&
                        !deepgramConnecting && !reconnecting && deepgramClient != null
                    if (streamReady) {
                        drainSuspendBufferToStream()
                        deepgramClient?.sendPcm(bytes, read * 2)
                    } else if (deepgramSuspended || deepgramConnecting || reconnecting) {
                        synchronized(suspendAudioLock) {
                            if (suspendAudioChunks.size >= MAX_SUSPEND_CHUNKS) {
                                suspendAudioChunks.removeFirst()
                            }
                            suspendAudioChunks.addLast(bytes.copyOf(read * 2))
                        }
                    }
                    // Idle-suspend (cost): 2 min without any
                    // transcript — WS closes, pump + VAD stay.
                    // 1.4.8 — flap fix, part 2: never suspend
                    // while a resume is still connecting
                    // (deepgramActive stays true across the
                    // close + reconnect, so the old check fired
                    // mid-connect), and never within
                    // IDLE_RESUME_GRACE_MS of a resume. The
                    // 120 s silence band itself is untouched.
                    if (deepgramActive && !deepgramSuspended &&
                        !deepgramConnecting &&
                        System.currentTimeMillis() - lastResumeMs > IDLE_RESUME_GRACE_MS &&
                        System.currentTimeMillis() - lastSpeechMs > IDLE_SUSPEND_MS
                    ) {
                        deepgramSuspended = true
                        runCatching { deepgramClient?.close() }
                        deepgramClient = null
                        JarvisStateRepository.addDiagnostic(
                            "stt", "IDLE_SUSPEND",
                            "Deepgram stream suspended after 2 min silence — VAD tap resumes on speech."
                        )
                    }
                }
            } catch (e: Exception) {
                // Pump death must not kill the loop silently.
                mainHandler.post {
                    if (enabled) {
                        JarvisStateRepository.addDiagnostic(
                            "stt", "PUMP_ERROR",
                            "Deepgram pump failed: ${e.message} — entering reconnect."
                        )
                        enterReconnecting("Deepgram pump failed — reconnecting.")
                    }
                }
            } finally {
                // 1.4.13 (F-E3): release AEC before the record
                // it was attached to (both runCatching — a dead
                // pump must never throw out of its finally).
                runCatching { pumpAec?.release() }
                pumpAec = null
                runCatching { record.stop() }
                runCatching { record.release() }
                if (deepgramRecord == record) deepgramRecord = null
            }
        }
        deepgramPumpThread = thread
        thread.start()
    }

    /**
     * PCM-energy RMS for the barge-in loud gate. 1.4.5 (item 4):
     * lastRmsDb is ALWAYS updated (even while speaking) so the
     * loud gate can see an interruption during speech — the old
     * code froze RMS during speech, so the gate compared STALE
     * pre-speech RMS and barge-in could never fire on level. The
     * BASELINE stays frozen while speaking (Jarvis's own playback
     * must never raise it) — that freeze is what makes the gate
     * meaningful during speech.
     */
    private fun updateRmsFromPcm(samples: ShortArray, count: Int) {
        if (count <= 0) return
        var sumSquares = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble()
            sumSquares += v * v
        }
        val rms = kotlin.math.sqrt(sumSquares / count) / 32768.0
        val rmsDb = (20 * kotlin.math.log10(rms + 1e-9)).toFloat()
        lastRmsDb = rmsDb
        if (!isSpeaking) {
            rmsBaselineDb = if (rmsBaselineDb == null) {
                rmsDb
            } else {
                rmsBaselineDb!! * 0.9f + rmsDb * 0.1f
            }
        }
    }

    private fun deepgramKey(): String {
        return runCatching {
            appContext.getSharedPreferences(
                "jarvis_agent_prefs", Context.MODE_PRIVATE
            ).getString("deepgram_api_key", "")?.trim() ?: ""
        }.getOrDefault("")
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
     * Stops the recognizer and the VAD monitor; does NOT stop the
     * foreground service itself (coordinator-owned).
     */
    fun stop() {
        if (!enabled && !reconnecting) return
        enabled = false
        reconnecting = false
        // A cancelled reconnect must never revive a stopped loop.
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        cancelConnectTimeout()
        quarantinedWhileSpeaking.clear()
        speakingDepth = 0
        isSpeaking = false
        currentSpokenText = ""
        // 1.4.13 (F-E1/F-E5): a stopped loop holds no fingerprint
        // and no stopping state — a later start speaks fresh.
        // WAVE-2 (§B): the stack clears on stop (rule 4).
        echoFingerprintStack.clear()
        echoSegmentBoundaryPending = false
        speakStopping = false
        mainHandler.removeCallbacks(turnCheckRunnable)
        stopDeepgramStream()
        sttPathReason = "stopped"
        listeningActive = false
        listener.onListeningStateChanged(false)
        stopVadMonitor()
        turnDetector.reset()
        resetTurnAssembly()
    }

    /** Releases everything; call from the owning service's onDestroy. */
    fun shutdown() {
        stop()
        stopWakeListening()
        cancelListenOnce()
        vadScope.cancel()
    }

    /**
     * Coordinator wiring for barge-in: called with VoiceManager's
     * stop path. CORRECTION (1.4.13, F-E4): the old docstring
     * claimed this stops "Swara / Deepgram / system TTS" — that
     * was false twice over. VoiceManager.stopSpeaking stops
     * Swara playback AND, since 1.4.13, the system-TTS fallback
     * via its static stop handle — but it NEVER stops Deepgram
     * listening. A barge-in must cut speech, never kill the mic.
     */
    fun setStopSpeakingCallback(callback: StopSpeakingCallback?) {
        stopSpeakingCallback = callback
    }

    /**
     * Coordinator MUST call this around every speak: true before
     * VoiceManager.speakAndWait, false right after it returns.
     * False also resets the VAD so stale own-voice audio is dropped
     * and segmenting resumes on fresh user audio only.
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
            // announce pair). One exception, the "retire on
            // next speak" rule: if a dropped speechFinal final
            // already armed a segment boundary, the prior tail
            // segment is DONE, so the stack retires BEFORE this
            // push (the new speak starts fresh); without a
            // boundary, nothing is cleared here.
            if (echoSegmentBoundaryPending) {
                echoFingerprintStack.clear()
                echoSegmentBoundaryPending = false
                JarvisStateRepository.addDiagnostic(
                    "stt", "ECHO_STACK_RETIRED",
                    "Echo stack retired on next speak after a segment boundary (entries=0)."
                )
            }
            if (spokenText.isNotBlank()) {
                echoFingerprintStack.addLast(spokenText)
                while (echoFingerprintStack.size > ECHO_STACK_MAX) {
                    echoFingerprintStack.removeFirst()
                }
            }
            // 1.4.13 (F-E1/F-E5): a new speak supersedes any
            // stopping state left by the previous speak's barge-in.
            speakStopping = false
            vadMonitor?.pauseForSpeech()
        } else {
            if (speakingDepth > 0) speakingDepth--
            if (speakingDepth > 0) return
            val currentSpokenTextBeforeClear = currentSpokenText
            isSpeaking = false
            currentSpokenText = ""
            // 1.4.13 (F-E5): this is the REAL open event (the
            // speak coroutine's finally) — the stopping window
            // ends here, and only here, does the gate open.
            speakStopping = false
            vadMonitor?.resumeAfterSpeech()
            // B-C3: flush quarantined transcripts now, stamped
            // with post-TTS time, so they form a turn only after
            // post-TTS silence — never mid-speech.
            // 1.4.5 (item 4): ECHO FILTER on flush. Because the pump
            // now streams during speech (barge-in alive), Jarvis's
            // own voice can arrive as a quarantined transcript;
            // flushing it verbatim would make Jarvis answer its own
            // echo. Drop held items whose normalised text is
            // contained in the spoken text or is a token-subsequence
            // of it — the same echo logic as BargeInDetector.
            // Genuine user speech during TTS (non-keyword) still
            // flushes as a turn; keyword barge-in already fired
            // promptly via handleTranscript.
            if (enabled && quarantinedWhileSpeaking.isNotEmpty()) {
                val spoken = currentSpokenTextBeforeClear
                val held = quarantinedWhileSpeaking.toList()
                quarantinedWhileSpeaking.clear()
                val nowMs = System.currentTimeMillis()
                for (item in held) {
                    if (spoken.isNotBlank() && isEchoOfSpoken(item.text, spoken)) continue
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
     * Coordinator MUST call this after it has spoken the
     * "aur bolo, poora sun raha hoon" prompt for an Incomplete
     * turn. Starts the ~5s continuation window in [TurnDetector].
     */
    fun continueAfterIncompletePrompt() {
        turnDetector.markIncompletePromptSent(System.currentTimeMillis())
    }

    /**
     * Opt-in Sherpa VAD monitor. [modelFile] must be a silero_vad.onnx
     * file (see [SherpaVadMonitor]); if it is missing or VAD setup
     * fails, this is a no-op and the recognizer timer stays primary.
     * Call only after [start]; the monitor stops with [stop].
     * Returns [VadMonitorResult.Started] only if the monitor
     * actually started; on failure the reason comes from the
     * failure site (loop-off here, model/construction from
     * the monitor's own init). The Deepgram early-return path
     * (monitor available, no runLoop in deepgram mode) stays
     * Started with NO reason token.
     */
    fun enableVadMonitor(modelFile: File, onSegment: (SpeechSegment) -> Unit): VadMonitorResult {
        if (!enabled) return VadMonitorResult.Failed(VadFailureReason.LOOP_OFF)
        val monitor = SherpaVadMonitor(appContext, modelFile, onSegment) { isSpeaking }
        if (!monitor.isAvailable()) {
            return VadMonitorResult.Failed(
                monitor.failureReason ?: VadFailureReason.VAD_CONSTRUCTION_FAILED
            )
        }
        stopVadMonitor()
        vadMonitor = monitor
        vadSegmentCallback = onSegment
        // 1.4.3: in Deepgram mode the pump feeds this monitor in
        // tap mode — starting runLoop here would open a SECOND
        // AudioRecord and steal the mic from the pump.
        if (sttPath == "deepgram") return VadMonitorResult.Started
        vadJob = vadScope.launch { monitor.runLoop() }
        return VadMonitorResult.Started
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
            isEchoOfSpoken(text, entry)
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
        // 1.4.7 (A1): Deepgram segments are assembled into the
        // cumulative turn text (see feedAssembled) — the old
        // isNewSession flag never changed in the Deepgram loop
        // (currentSessionId is never incremented), so a later
        // segment's interim replaced the buffer and the first
        // segment's words were lost.
        feedAssembled(text, isFinal, System.currentTimeMillis())
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

    // ---- Wake-word session (Deepgram-only, loop OFF only) ----
    /**
     * Start continuous Deepgram wake-word listening (1.4.5 FINAL
     * policy: the system recognizer wake path is deleted with the
     * system loop). A-H3 gate: returns false when the always-on
     * loop is enabled (two mic owners must never fight), when
     * RECORD_AUDIO is missing, or when no Deepgram key is saved
     * (+ diagnostic). Finals are delivered on the main thread to
     * [callback]; the caller (VoiceManager) applies matchWakeTrigger
     * and the session KEEPS running on non-matches. Pump death
     * restarts in 5 s; WS error/close restarts with 5 s -> 30 s
     * capped backoff — EXCEPT auth failure (401/Unauthorized),
     * which stops the session with WAKE_AUTH_FAILED (retrying a
     * bad key forever helps nobody; the key must be fixed).
     */
    fun startWakeListening(callback: (String) -> Unit): Boolean {
        if (enabled) return false
        if (!isRecordAudioGranted()) return false
        val key = deepgramKey()
        if (key.isBlank()) {
            JarvisStateRepository.addDiagnostic(
                "stt", "WAKE_NO_KEY",
                "Wake-word session refused: no Deepgram key saved."
            )
            // 1.4.6: WAKE_NO_KEY used to be diagnostics-only —
            // invisible in the app — so "catch nahi karta" looked
            // like a listening bug when listening could never
            // start (Deepgram-only policy: no key = no listening
            // at all). Now it also posts a visible chat line
            // (5-min gated: wake start is retried after every
            // speak, and an ungated line would spam the chat).
            // The mic-loop start path already banners this via
            // onMicError; this covers the wake-word path, which
            // runs at every launch (wakeWordEnabled default ON).
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastWakeNoKeyChatMs >= 300_000L) {
                lastWakeNoKeyChatMs = nowMs
                JarvisStateRepository.addChatMessage(
                    "System",
                    "Sunna shuru nahi ho sakta — Deepgram key nahi " +
                        "hai. AI Brain me Deepgram key daal kar Save " +
                        "dabao, phir mic on karo."
                )
            }
            return false
        }
        if (wakeActive) {
            wakeCallback = callback
            return true
        }
        wakeActive = true
        wakeCallback = callback
        wakeBackoffMs = WAKE_RESTART_BASE_MS
        startWakeSessionInternal(key)
        return true
    }

    private fun startWakeSessionInternal(key: String) {
        if (!wakeActive) return
        val minBuf = AudioRecord.getMinBufferSize(
            16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            scheduleWakeRestart()
            return
        }
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            scheduleWakeRestart()
            return
        }
        wakeRecord = record
        val client = DeepgramSttClient(key)
        wakeClient = client
        val listener = object : DeepgramSttClient.Listener {
            override fun onOpen() {}
            override fun onInterim(text: String) {}
            override fun onFinal(text: String, speechFinal: Boolean) {
                if (!wakeActive) return
                if (text.isBlank()) return
                val cb = wakeCallback ?: return
                mainHandler.post { if (wakeActive) cb(text) }
            }
            override fun onError(code: String, message: String) {
                if (!wakeActive) return
                if (message.contains("401", ignoreCase = true) ||
                    message.contains("Unauthorized", ignoreCase = true)
                ) {
                    JarvisStateRepository.addDiagnostic(
                        "stt", "WAKE_AUTH_FAILED",
                        "Wake session stopped: Deepgram auth failed (key check needed) — no restart."
                    )
                    stopWakeListening()
                    return
                }
                JarvisStateRepository.addDiagnostic(
                    "stt", "WAKE_ERROR",
                    "Wake session error $code — restarting in ${wakeBackoffMs / 1000}s."
                )
                scheduleWakeRestart()
            }
            override fun onClosed() {
                if (!wakeActive) return
                JarvisStateRepository.addDiagnostic(
                    "stt", "WAKE_CLOSED",
                    "Wake session closed — restarting in ${wakeBackoffMs / 1000}s."
                )
                scheduleWakeRestart()
            }
        }
        client.connect(listener)
        val thread = Thread {
            val buf = ByteArray(3200)
            try {
                record.startRecording()
                while (wakeActive && wakeRecord == record) {
                    val read = runCatching { record.read(buf, 0, buf.size) }.getOrDefault(-1)
                    if (read < 0) break
                    if (read > 0) client.sendPcm(buf, read)
                }
            } catch (e: Exception) {
                // Pump death: restart path below.
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                if (wakeRecord == record) wakeRecord = null
            }
            if (wakeActive) {
                JarvisStateRepository.addDiagnostic(
                    "stt", "WAKE_PUMP_DIED",
                    "Wake pump died — restarting in 5s."
                )
                mainHandler.post { scheduleWakeRestart() }
            }
        }
        wakePumpThread = thread
        thread.start()
        // 1.4.8 (G8 F2): the wake session is LIVE only now (the
        // pump thread actually started) — not at wakeActive=true
        // (which is set before record/client init can fail).
        JarvisStateRepository.setWakeListening(true)
    }

    private fun scheduleWakeRestart() {
        if (!wakeActive) return
        // 1.4.8 (G8 F2): backoff = NOT listening (the pump died
        // or the stream errored/closed). The flag goes true
        // again only if the restarted pump actually starts.
        JarvisStateRepository.setWakeListening(false)
        wakeRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        val delayMs = wakeBackoffMs
        wakeBackoffMs = (wakeBackoffMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        val runnable = Runnable {
            wakeRestartRunnable = null
            if (!wakeActive || enabled) return@Runnable
            val key = deepgramKey()
            if (key.isBlank()) {
                stopWakeListening()
                return@Runnable
            }
            runCatching { wakeClient?.close() }
            wakeClient = null
            startWakeSessionInternal(key)
        }
        wakeRestartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    fun stopWakeListening() {
        wakeActive = false
        // 1.4.8 (G8 F2): live wake state clears with the session.
        JarvisStateRepository.setWakeListening(false)
        wakeRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        wakeRestartRunnable = null
        runCatching { wakeClient?.close() }
        wakeClient = null
        val record = wakeRecord
        wakeRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        wakePumpThread = null
        wakeCallback = null
    }

    // ---- One-shot session (Deepgram-only, loop OFF only) ----
    /**
     * Loop-OFF single reply via Deepgram listenOnce (1.4.5 FINAL
     * policy: the system single-shot path is deleted). A-H3 gate:
     * when the loop is enabled the callback gets null immediately
     * (the service answers from the loop's next turn instead).
     * Blank key -> null + ONE_SHOT_NO_KEY diagnostic (no mic
     * opened). The running job is kept so cancelListenOnce()
     * frees the mic.
     */
    fun listenOnceAsync(timeoutMs: Long, callback: (String?) -> Unit) {
        if (enabled) {
            callback(null)
            return
        }
        // 1.4.10 (Step 6): the old code checked only the key —
        // a missing RECORD_AUDIO grant fell through to a silent
        // null (AudioRecord init failure). Now it has its own
        // diagnostic (graceful still, never a crash).
        if (!isRecordAudioGranted()) {
            JarvisStateRepository.addDiagnostic(
                "stt", "ONE_SHOT_NO_PERMISSION",
                "One-shot refused: RECORD_AUDIO not granted."
            )
            callback(null)
            return
        }
        val key = deepgramKey()
        if (key.isBlank()) {
            JarvisStateRepository.addDiagnostic(
                "stt", "ONE_SHOT_NO_KEY",
                "One-shot refused: no Deepgram key saved."
            )
            callback(null)
            return
        }
        oneShotJob?.cancel()
        oneShotJob = vadScope.launch {
            val text = DeepgramSttClient(key).listenOnce(timeoutMs)
            mainHandler.post { callback(text) }
        }
    }

    fun cancelListenOnce() {
        oneShotJob?.cancel()
        oneShotJob = null
    }

    private fun resetLanguageToPrimary() {
        currentLanguage = LANGUAGE_PRIMARY_HI_IN
        emptySessionFallbackUsed = false
    }

    // Cancels the standalone VAD loop job only, keeping the
    // vadMonitor itself alive: the Deepgram pump taps the same
    // monitor for segments, so a full stopVadMonitor() here would
    // kill segment detection while streaming continues.
    private fun stopVadLoopOnly() {
        vadJob?.cancel()
        vadJob = null
    }

    private fun stopVadMonitor() {
        vadJob?.cancel()
        vadJob = null
        runCatching { vadMonitor?.release() }
        vadMonitor = null
    }

    companion object {
        const val LANGUAGE_PRIMARY_HI_IN: String = "hi-IN"
        const val LANGUAGE_FALLBACK_EN_IN: String = "en-IN"

        /** 1.4.5: hung Deepgram connect timeout (no overall
         *  timeout existed before — a hung TCP connect left the
         *  loop "connecting" forever). */
        const val CONNECT_TIMEOUT_MS: Long = 15_000L
        /** 1.4.12: Silero VAD model pin — CI mirror of the
         *  workflow's VAD bundle gate (643,854 bytes). Moved
         *  here so the pin has a code home now that the old
         *  voice client's pins are deleted with it. The workflow
         *  still enforces the same pin independently. */
        const val PIN_VAD_SIZE: Long = 643_854L
        const val PIN_VAD_SHA: String = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
        /** 1.4.5: reconnect backoff base + cap (5s, doubling to
         *  30s) — auto-revive runs forever until the user turns
         *  the mic off; only permission/key failures stop it. */
        const val RECONNECT_BASE_MS: Long = 5_000L
        const val RECONNECT_MAX_MS: Long = 30_000L
        /** 1.4.5: wake-session restart backoff base (5s, doubling
         *  to 30s); auth failures never restart. */
        const val WAKE_RESTART_BASE_MS: Long = 5_000L
        /** B-C3: cap on transcripts held while speaking. */
        const val MAX_QUARANTINED: Int = 20
        /** 1.4.3: silence after which the Deepgram WS suspends
         *  (cost) while the pump + VAD tap keep running. */
        const val IDLE_SUSPEND_MS: Long = 120_000L
        /** 1.4.8: hysteresis — never suspend within this long
         *  of a resume (see the flap mechanism below). */
        const val IDLE_RESUME_GRACE_MS: Long = 30_000L
        /** 1.4.13 (F-H2): not-ready audio buffer cap — sized
         *  from the reconnect budget it must cover, not a
         *  guess: max backoff 30 s (RECONNECT_MAX_MS) + connect
         *  timeout 15 s (CONNECT_TIMEOUT_MS) = 45 s. 450 x
         *  100 ms chunks = 45 s of 16 kHz mono 16-bit PCM
         *  (~1.44 MB: 450 x 3200 bytes), oldest dropped, flushed
         *  in order. The old 8 s cap dropped the very utterance
         *  that triggered a slow resume. */
        const val MAX_SUSPEND_CHUNKS: Int = 450

    }
}

/** B-C3: one transcript held while Jarvis was speaking. */
private data class QuarantinedTranscript(val text: String, val isFinal: Boolean)

/** Callbacks from [AlwaysOnMicManager] to the coordinator. All arrive on the main thread. */
interface AlwaysOnMicListener {
    /** A complete turn (silence + completeness decided by TurnDetector). Act on this. */
    fun onTurnComplete(text: String)

    /**
     * The utterance looks incomplete. The coordinator should speak
     * "aur bolo, poora sun raha hoon" (via VoiceManager, wrapped in
     * setSpeaking) and then call
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

    /** Fatal mic problem (permission, no Deepgram key). The loop is not running. */
    fun onMicError(reason: String)

    /**
     * 1.4.5: the user-enabled loop lost its Deepgram stream and is
     * auto-reconnecting (visible banner, NOT a stop). Default
     * no-op; the service overrides it to show the banner.
     */
    fun onMicReconnecting(reason: String) {}

    /**
     * 1.4.5: a reconnecting loop recovered (stream open again).
     * Default no-op; the service overrides it for a chat line.
     */
    fun onMicRecovered() {}

    /**
     * 1.4.3: STT path switch (deepgram|system) + reason. Default
     * no-op so existing implementers keep compiling; the service
     * overrides it to post a visible chat line + Diagnostics.
     */
    fun onSttPathChanged(path: String, reason: String) {}
}

/** Stops whatever TTS engine is currently speaking. Wired by the coordinator to VoiceManager. */
fun interface StopSpeakingCallback {
    fun stopSpeaking()
}

/**
 * OPTIONAL Sherpa-ONNX Silero VAD monitor (S1 VAD path).
 *
 * Verified API (sherpa-onnx 1.13.4 AAR, classes.jar inspected with
 * javap): com.k2fsa.sherpa.onnx.Vad(AssetManager, VadModelConfig),
 * Vad(VadModelConfig) (file constructor, for a filesDir path),
 * acceptWaveform(FloatArray), isSpeechDetected(), empty(),
 * front(): SpeechSegment(start: Int, samples: FloatArray), pop(),
 * flush(), reset(), release(); VadModelConfig(SileroVadModelConfig,
 * TenVadModelConfig, sampleRate, numThreads, provider, debug);
 * SileroVadModelConfig(model, threshold, minSilenceDuration,
 * minSpeechDuration, windowSize, maxSpeechDuration);
 * getVadModelConfig(type) (top-level function in Vad.kt;
 * 1.4.15 correction: the Int is a MODEL-TYPE SELECTOR, not a
 * sample rate — 0 = Silero. The sample rate stays 16000 from
 * the returned base config. The old "sampleRate" reading was a
 * javap misread: javap shows only the Int type, never the name.
 *
 * HONEST LIMITS — read before enabling:
 * 1. MODEL BUNDLED (1.4.2 correction of a stale comment): CI bundles
 *    assets/silero_vad.onnx into the APK; when [modelFile] exists,
 *    [isAvailable] is true and the VAD monitor runs. The old text
 *    claiming no such file ships was false from 1.4.1 on.
 * 2. MIC SHARING: Android gives the mic to one client at a time in
 *    practice. Running AudioRecord WHILE SpeechRecognizer is
 *    listening can fail (mic busy) or disturb the recognizer.
 *    [runLoop] catches that, marks itself unavailable, and the
 *    recognizer-only timer (the PRIMARY path) is unaffected. Do
 *    not treat VAD as required; treat it as an enhancement that
 *    may be unavailable on a given device.
 * 3. NEVER FAILS THE BUILD OR THE MIC: all native/model work is
 *    inside runCatching; every failure degrades to "VAD off".
 *    (Compile-time: the sherpa AAR is already a declared
 *    dependency for VAD, so these imports add no new dep.)
 * 4. ECHO GATE: while [isSpeaking] is true, frames are DROPPED
 *    (never fed to Vad) and [resumeAfterSpeech] resets Vad, so
 *    Jarvis's own TTS can never be emitted as a user segment.
 *    Segmenting resumes on fresh post-TTS audio only. The loud
 *    "ruk"/"bas" barge-in during TTS is handled by the recognizer
 *    transcript path ([BargeInDetector]), NOT by VAD — VAD alone
 *    cannot recognise keywords.
 * 5. LOW-CPU IDLE BEHAVIOUR: frames below the energy gate
 *    (RMS < 0.005) are never sent to the ONNX model — near-silence
 *    costs an RMS computation, not an inference. Speech frames are
 *    fed in 512-sample (32ms @ 16kHz) slices. This reduces VAD CPU
 *    when idle; it does NOT make always-on listening free (see the
 *    battery note in INTEGRATION_NOTES_Mic.md).
 *
 * VAD segments go to STT only while a session is active: the
 * coordinator starts this monitor only after AlwaysOnMicManager
 * [start], and [stop]/[release] ends it. Wake-word listening is
 * a separate VoiceManager path (word: "Jarvis") that runs only
 * while this loop is off; there is no always-segmenting mode
 * outside an active session.
 */
class SherpaVadMonitor(
    context: Context,
    private val modelFile: File,
    private val onSegment: (SpeechSegment) -> Unit,
    private val isSpeaking: () -> Boolean
) {

    private val appContext: Context = context.applicationContext
    // A-H10: EVERY Vad touch (feed, front/pop, reset, release)
    // goes through this lock, so release() can never race the
    // inference loop's acceptWaveform/front/pop.
    private val vadLock = Any()
    private var vad: Vad? = null
    // AudioRecord lifecycle is owned ONLY by runLoop's IO thread
    // (create/start/stop/release there, incl. the finally block);
    // release() below never touches it from the main thread.
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var running: Boolean = false
    private var pausedForSpeech: Boolean = false

    @Volatile
    private var available: Boolean = false

    // 1.4.14 (D2): reason for init failure, set atomically in
    // init — null on success. Model-file-missing is kept
    // distinct from null-config / Vad(...) throw, which the
    // old single available flag collapsed together.
    var failureReason: VadFailureReason? = null
        private set

    init {
        failureReason = if (!modelFile.exists()) {
            VadFailureReason.MODEL_MISSING
        } else {
            try {
                // 1.4.15: getVadModelConfig's Int is a model-type
                // selector (0 = Silero), NOT a sample rate — the
                // old SAMPLE_RATE_HZ (16000) argument matched no
                // branch, returned null on every device, and VAD
                // never constructed. The sample rate stays 16000
                // from the returned base config. A null result
                // means the type selector was unknown — a
                // construction failure, NOT a missing model
                // (the file exists; the config could not be built).
                val baseConfig = getVadModelConfig(0)
                if (baseConfig == null) {
                    VadFailureReason.VAD_CONSTRUCTION_FAILED
                } else {
                    val config = baseConfig.copy(
                        sileroVadModelConfig = SileroVadModelConfig(
                            modelFile.absolutePath,
                            VAD_THRESHOLD,
                            VAD_MIN_SILENCE_SEC,
                            VAD_MIN_SPEECH_SEC,
                            VAD_WINDOW_SIZE,
                            VAD_MAX_SPEECH_SEC
                        )
                    )
                    // 1.4.15: file constructor, NOT the
                    // AssetManager one: the model path here is a
                    // filesDir ABSOLUTE path, and the AssetManager
                    // constructor routes to native newFromAsset,
                    // which expects an asset-relative name. The
                    // file constructor routes to newFromFile and
                    // runs config.Validate().
                    vad = Vad(config)
                    null
                }
            } catch (e: Exception) {
                VadFailureReason.VAD_CONSTRUCTION_FAILED
            }
        }
        available = failureReason == null && vad != null
        if (!available) {
            synchronized(vadLock) {
                runCatching { vad?.release() }
                vad = null
            }
        }
    }

    fun isAvailable(): Boolean = available && vad != null

    fun isRunning(): Boolean = running

    /** Called by the manager when TTS starts: drop frames until speech ends. */
    fun pauseForSpeech() {
        pausedForSpeech = true
    }

    /** Called by the manager when TTS stops: fresh state, fresh audio only. */
    fun resumeAfterSpeech() {
        pausedForSpeech = false
        synchronized(vadLock) {
            runCatching { vad?.reset() }
        }
    }

    /**
     * AudioRecord + VAD loop. Runs on the manager's IO scope.
     * Returns normally on stop/failure; never throws to the caller.
     * Must be called from a coroutine (it suspends via withContext
     * only for cleanup ordering; the read loop itself blocks on
     * AudioRecord.read, which is why it lives on Dispatchers.IO).
     */
    suspend fun runLoop() {
        if (!isAvailable()) return
        val started = runCatching {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) return@runCatching false
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                return@runCatching false
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                // Mic busy (recognizer holds it) or otherwise denied.
                runCatching { record.stop() }
                runCatching { record.release() }
                return@runCatching false
            }
            audioRecord = record
            true
        }.getOrDefault(false)
        if (!started) {
            // Mic-sharing failure: degrade silently to VAD-off.
            available = false
            return
        }
        running = true
        try {
            val shortBuffer = ShortArray(VAD_WINDOW_SIZE * 2)
            val floatBuffer = FloatArray(VAD_WINDOW_SIZE)
            while (running) {
                val record = audioRecord ?: break
                val read = runCatching {
                    record.read(shortBuffer, 0, shortBuffer.size)
                }.getOrDefault(-1)
                if (read <= 0) {
                    if (read < 0) break
                    continue
                }
                // Echo gate + pause: drop everything while speaking.
                if (pausedForSpeech || isSpeaking()) continue
                // Feed in 512-sample slices, with the energy gate:
                // near-silent slices skip ONNX inference entirely.
                var offset = 0
                while (offset + VAD_WINDOW_SIZE <= read) {
                    var sumSquares = 0.0
                    for (i in 0 until VAD_WINDOW_SIZE) {
                        val sample = shortBuffer[offset + i]
                        sumSquares += (sample * sample).toDouble()
                    }
                    val rms = kotlin.math.sqrt(sumSquares / VAD_WINDOW_SIZE) / 32768.0
                    if (rms >= ENERGY_GATE_RMS) {
                        for (i in 0 until VAD_WINDOW_SIZE) {
                            floatBuffer[i] = shortBuffer[offset + i] / 32768.0f
                        }
                        // A-H10: feed + drain under the lock; the
                        // main-thread dispatch happens OUTSIDE it
                        // (never hold the lock across a suspend).
                        val segments = mutableListOf<SpeechSegment>()
                        synchronized(vadLock) {
                            val currentVad = vad ?: return@synchronized
                            runCatching { currentVad.acceptWaveform(floatBuffer) }
                            runCatching {
                                while (!currentVad.empty()) {
                                    segments.add(currentVad.front())
                                    currentVad.pop()
                                }
                            }
                        }
                        for (segment in segments) {
                            withContext(Dispatchers.Main) {
                                onSegment(segment)
                            }
                        }
                    }
                    offset += VAD_WINDOW_SIZE
                }
            }
        } finally {
            running = false
            // NonCancellable: a cancelled job would otherwise skip
            // this cleanup (withContext on a cancelled job throws
            // before running its block).
            withContext(NonCancellable) {
                runCatching { audioRecord?.stop() }
                runCatching { audioRecord?.release() }
                audioRecord = null
            }
        }
    }

    /**
     * 1.4.3 TAP MODE for Deepgram mode: feeds caller-owned PCM
     * (the manager's single shared AudioRecord) into the SAME Vad
     * instance, energy gate and 512-sample slicing as runLoop —
     * but WITHOUT opening a second AudioRecord (Android gives the
     * mic to one client). Returns completed segments; the caller
     * dispatches them on the main thread, exactly as runLoop does.
     * Echo gate identical to runLoop: nothing while speaking.
     */
    fun feedSamples(samples: ShortArray, count: Int): List<SpeechSegment> {
        if (!isAvailable()) return emptyList()
        if (pausedForSpeech || isSpeaking()) return emptyList()
        val segments = mutableListOf<SpeechSegment>()
        val floatBuffer = FloatArray(VAD_WINDOW_SIZE)
        var offset = 0
        while (offset + VAD_WINDOW_SIZE <= count) {
            var sumSquares = 0.0
            for (i in 0 until VAD_WINDOW_SIZE) {
                val sample = samples[offset + i]
                sumSquares += (sample * sample).toDouble()
            }
            val rms = kotlin.math.sqrt(sumSquares / VAD_WINDOW_SIZE) / 32768.0
            if (rms >= ENERGY_GATE_RMS) {
                for (i in 0 until VAD_WINDOW_SIZE) {
                    floatBuffer[i] = samples[offset + i] / 32768.0f
                }
                synchronized(vadLock) {
                    val currentVad = vad ?: return@synchronized
                    runCatching { currentVad.acceptWaveform(floatBuffer) }
                    runCatching {
                        while (!currentVad.empty()) {
                            segments.add(currentVad.front())
                            currentVad.pop()
                        }
                    }
                }
            }
            offset += VAD_WINDOW_SIZE
        }
        return segments
    }

    /**
     * Stops the loop (idempotent) and releases native state.
     * A-H10: Vad release happens under [vadLock]; AudioRecord is
     * deliberately NOT touched here — the runLoop thread owns it
     * and releases it in its finally block (or the loop never
     * started, so there is nothing to release). Releasing an
     * AudioRecord from the main thread while the IO loop still
     * reads it was the race this removes.
     */
    fun release() {
        running = false
        synchronized(vadLock) {
            runCatching { vad?.release() }
            vad = null
        }
        available = false
    }

    companion object {
        const val SAMPLE_RATE_HZ: Int = 16_000
        const val VAD_WINDOW_SIZE: Int = 512
        const val VAD_THRESHOLD: Float = 0.5f
        const val VAD_MIN_SILENCE_SEC: Float = 0.5f
        const val VAD_MIN_SPEECH_SEC: Float = 0.25f
        const val VAD_MAX_SPEECH_SEC: Float = 15.0f

        /** Frames quieter than this skip ONNX inference (idle CPU saving). */
        const val ENERGY_GATE_RMS: Double = 0.005
    }
}
