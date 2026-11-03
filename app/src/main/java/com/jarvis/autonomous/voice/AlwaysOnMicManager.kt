package com.jarvis.autonomous.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
 * THREADING: SpeechRecognizer must run on the main thread, so all
 * recognizer work and turn checks use [mainHandler]. Task work
 * never runs here. The VAD loop runs on [vadScope] (IO).
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
    private val vadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var speechRecognizer: SpeechRecognizer? = null
    private var vadMonitor: SherpaVadMonitor? = null
    private var vadJob: Job? = null

    private var enabled: Boolean = false
    private var listeningActive: Boolean = false

    /** 1.4.2: raw SpeechRecognizer error code of the most recent
     *  onError (-1 when none). Terminal deaths previously surfaced
     *  only a reason string, so the code was lost before any UI
     *  could name it — the bug catcher and the Retry banner read
     *  this, and the diagnostics store records it. */
    var lastRecognizerErrorCode: Int = -1
        private set
    private var currentSessionId: Int = 0
    private var lastPartialSessionId: Int = -1
    private var restartPending: Boolean = false
    /** A-H6: the single cancellable restart handle (cancelled in [stop]). */
    private var restartRunnable: Runnable? = null
    /** A-H6: last successful recognizer start, for the 150ms double-start guard. */
    private var lastStartMs: Long = 0L
    /** A-H4/A-H5: consecutive counted (non-transient) errors; reset
     * on any recognizer progress, never reset by wall-clock time. */
    private var consecutiveErrorCount: Int = 0
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

    private var stopSpeakingCallback: StopSpeakingCallback? = null

    // RMS baseline for the barge-in "loud" gate; tracked only while
    // NOT speaking, so Jarvis's own playback never raises it.
    private var rmsBaselineDb: Float? = null
    private var lastRmsDb: Float? = null

    // ---- 1.4.3 Deepgram-primary STT state (system loop stays as
    // fallback — nothing below deletes a system path). ----
    /** Active STT path: "deepgram" or "system" (Diagnostics + UI). */
    var sttPath: String = "system"
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
    @Volatile
    private var lastSpeechMs: Long = 0L
    private var vadSegmentCallback: ((SpeechSegment) -> Unit)? = null

    /** Deepgram stream listener — one instance per loop start,
     *  reused across idle-suspend reconnects. */
    private val deepgramListener: DeepgramSttClient.Listener = object : DeepgramSttClient.Listener {
        override fun onOpen() {
            if (!enabled) return
            deepgramConnecting = false
            deepgramActive = true
            deepgramSuspended = false
            lastSpeechMs = System.currentTimeMillis()
            mainHandler.post {
                listeningActive = true
                listener.onListeningStateChanged(true)
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
            mainHandler.post { handleTranscript(text, isFinal = true) }
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
                    return
                }
            }
            mainHandler.post {
                fallbackToSystem("Deepgram $code: ${message.take(120)}")
            }
        }

        override fun onClosed() {
            if (!enabled || (!deepgramActive && !deepgramConnecting) || deepgramSuspended) return
            mainHandler.post {
                fallbackToSystem("Deepgram stream closed unexpectedly.")
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

    // RecognitionListener: every ending restarts the recognizer.
    // Declared after all state above; uses only that state.
    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listeningActive = true
            consecutiveErrorCount = 0
            listener.onListeningStateChanged(true)
        }

        override fun onBeginningOfSpeech() {
            turnDetector.onSpeechStarted(System.currentTimeMillis())
        }

        override fun onRmsChanged(rmsdB: Float) {
            // A-M3: FREEZE RMS while speaking — recognizer RMS
            // during TTS is playback-dominated and must never feed
            // the barge-in loud gate. Baseline is tracked only
            // while NOT speaking, as before.
            if (isSpeaking) return
            lastRmsDb = rmsdB
            rmsBaselineDb = if (rmsBaselineDb == null) {
                rmsdB
            } else {
                rmsBaselineDb!! * 0.9f + rmsdB * 0.1f
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Raw audio is not needed; partials carry the transcript.
        }

        override fun onEndOfSpeech() {
            // NOT a turn signal. Just marks the session ended; the
            // restart below (via onResults/onError, or here as a
            // safety net) keeps the loop alive.
            listeningActive = false
            if (enabled) scheduleRestart(RESTART_DELAY_MS)
        }

        override fun onError(error: Int) {
            listeningActive = false
            // 1.4.2: keep the raw code — the old code passed only a
            // string onward, so the banner/diagnostics could never
            // name ERROR_NETWORK vs ERROR_SERVER vs permission.
            lastRecognizerErrorCode = error
            listener.onListeningStateChanged(false)
            if (!enabled) return
            // A-H4: terminal errors stop the loop and surface
            // onMicError — they are never silently retried.
            if (error in TERMINAL_RECOGNIZER_ERRORS) {
                stopWithError("Recognizer error $error (terminal) — mic loop stopped.")
                return
            }
            // A-H4: RECORD_AUDIO may have been revoked mid-loop;
            // every restart path re-checks it (see
            // [startRecognizerSession]), and a revoked grant stops
            // here too instead of spinning.
            if (!isRecordAudioGranted()) {
                stopWithError("RECORD_AUDIO revoked — mic loop stopped.")
                return
            }
            // A-M6: locale flip ONLY after a session that DID
            // transcribe something then failed — an empty session
            // (heard nothing) is not a locale failure and must
            // not flip. Any successful transcript resets the
            // locale (see [handleTranscript]).
            if (turnDetector.currentText().isNotBlank() &&
                (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                !emptySessionFallbackUsed
            ) {
                currentLanguage = LANGUAGE_FALLBACK_EN_IN
                emptySessionFallbackUsed = true
            }
            // Transient silence endings (NO_MATCH / SPEECH_TIMEOUT)
            // restart immediately and never count toward escalation;
            // every other error (incl. RECOGNIZER_BUSY, A-H5) goes
            // through the same counted, escalating backoff.
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                scheduleRestart(RESTART_DELAY_MS)
            } else {
                scheduleRestart(countedBackoffDelay())
            }
        }

        override fun onResults(results: Bundle?) {
            listeningActive = false
            consecutiveErrorCount = 0
            listener.onListeningStateChanged(false)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val finalText = matches?.firstOrNull()?.trim() ?: ""
            handleTranscript(finalText, isFinal = true)
            if (enabled) scheduleRestart(RESTART_DELAY_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull()?.trim() ?: ""
            if (partial.isNotEmpty()) {
                handleTranscript(partial, isFinal = false)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // No-op.
        }
    }

    // ---- Public API ----

    /** True while the loop is enabled (between [start] and [stop]). */
    fun isEnabled(): Boolean = enabled

    /** True while a recognizer session is currently live. */
    fun isListeningActive(): Boolean = listeningActive

    /** Current recognizer locale ("hi-IN" or "en-IN"). */
    fun currentLanguage(): String = currentLanguage

    /** True when the optional VAD monitor is running. */
    fun isVadActive(): Boolean = vadMonitor?.isRunning() == true

    fun isRecordAudioGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts the always-on loop. 1.4.3 path decision (in order):
     * Deepgram PRIMARY when key present + network available +
     * WS connects; otherwise the SYSTEM loop (unchanged) with a
     * VISIBLE switch notice (chat line via onSttPathChanged +
     * Diagnostics) naming the path and the reason. Safe to call
     * twice. The system availability check now lives in
     * [startSystem] so a missing system recognizer never blocks
     * a Deepgram start.
     */
    fun start(): Boolean {
        if (enabled) return true
        lastRecognizerErrorCode = -1
        if (!isRecordAudioGranted()) {
            listener.onMicError("RECORD_AUDIO not granted; mic loop not started.")
            return false
        }
        enabled = true
        consecutiveErrorCount = 0
        lastStartMs = 0L
        quarantinedWhileSpeaking.clear()
        turnDetector.reset()
        resetLanguageToPrimary()
        deepgramFallbackTried = false
        val key = deepgramKey()
        if (key.isNotBlank() && hasNetwork()) {
            sttPath = "deepgram"
            sttPathReason = "Deepgram key present + network OK"
            JarvisStateRepository.setSttPath("deepgram", sttPathReason)
            startDeepgram(key)
        } else {
            sttPath = "system"
            sttPathReason = if (key.isBlank()) {
                "No Deepgram key — listening via system mic"
            } else {
                "No network — listening via system mic"
            }
            JarvisStateRepository.setSttPath("system", sttPathReason)
            JarvisStateRepository.addDiagnostic(
                "stt", "PATH_SYSTEM", sttPathReason
            )
            listener.onSttPathChanged("system", sttPathReason)
            startSystem()
        }
        return true
    }

    /** The pre-1.4.3 system loop, untouched except for the
     *  availability check moving here from [start]. */
    private fun startSystem() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            enabled = false
            listener.onMicError("SpeechRecognizer not available on this device.")
            return
        }
        mainHandler.post {
            startRecognizerSession()
            mainHandler.postDelayed(turnCheckRunnable, TurnThresholds.TURN_CHECK_INTERVAL_MS)
        }
    }

    /** Deepgram-primary start: stops any VAD runLoop first (its
     *  AudioRecord would steal the mic from the pump), then
     *  connects async — onOpen starts the pump, onError/onClosed
     *  fall back to [startSystem] via [fallbackToSystem]. */
    private fun startDeepgram(key: String) {
        stopVadLoopOnly()
        deepgramConnecting = true
        lastSpeechMs = System.currentTimeMillis()
        val client = DeepgramSttClient(key)
        deepgramClient = client
        client.connect(deepgramListener)
        mainHandler.postDelayed(turnCheckRunnable, TurnThresholds.TURN_CHECK_INTERVAL_MS)
    }

    /** Stops the VAD runLoop job WITHOUT releasing the Vad — the
     *  Deepgram pump reuses the same monitor in tap mode. */
    private fun stopVadLoopOnly() {
        vadJob?.cancel()
        vadJob = null
    }

    /**
     * Deepgram -> system fallback (main thread). Tears Deepgram
     * down, records path + reason, notifies the coordinator
     * (visible chat line), then starts the system loop. If the
     * system loop is itself unavailable, [startSystem] surfaces
     * onMicError (banner/Retry) instead of silent death.
     */
    private fun fallbackToSystem(reason: String) {
        if (!enabled) return
        stopDeepgramStream()
        sttPath = "system"
        sttPathReason = "Deepgram unavailable ($reason) — listening via system mic"
        JarvisStateRepository.setSttPath("system", sttPathReason)
        JarvisStateRepository.addDiagnostic("stt", "FALLBACK", sttPathReason)
        listener.onSttPathChanged("system", sttPathReason)
        startSystem()
    }

    /** Closes the Deepgram stream + pump mic (idempotent). */
    private fun stopDeepgramStream() {
        deepgramConnecting = false
        deepgramActive = false
        deepgramSuspended = false
        runCatching { deepgramClient?.close() }
        deepgramClient = null
        val record = deepgramRecord
        deepgramRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        deepgramPumpThread = null
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
                fallbackToSystem("Mic buffer unavailable for Deepgram pump.")
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
                fallbackToSystem("Mic unavailable for Deepgram pump.")
            }
            return
        }
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { record.stop() }
            runCatching { record.release() }
            mainHandler.post {
                fallbackToSystem("Mic busy — Deepgram pump could not start.")
            }
            return
        }
        deepgramRecord = record
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
                    // VAD tap on the shared buffer (tap mode).
                    val monitor = vadMonitor
                    if (monitor != null) {
                        val segments = monitor.feedSamples(shorts, read)
                        if (segments.isNotEmpty()) {
                            if (deepgramSuspended) {
                                // Resume: speech returned — reconnect.
                                deepgramSuspended = false
                                val key = deepgramKey()
                                if (key.isNotBlank()) {
                                    runCatching { deepgramClient?.close() }
                                    deepgramClient = null
                                    deepgramConnecting = true
                                    val client = DeepgramSttClient(key)
                                    deepgramClient = client
                                    client.connect(deepgramListener)
                                }
                            }
                            val cb = vadSegmentCallback
                            if (cb != null) {
                                mainHandler.post {
                                    for (segment in segments) cb(segment)
                                }
                            }
                        }
                    }
                    if (deepgramActive && !deepgramSuspended && !isSpeaking) {
                        for (i in 0 until read) {
                            val v = shorts[i].toInt()
                            bytes[i * 2] = (v and 0xFF).toByte()
                            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                        }
                        deepgramClient?.sendPcm(bytes, read * 2)
                    } else if (deepgramActive && !deepgramSuspended) {
                        deepgramClient?.sendKeepAliveIfIdle()
                    }
                    // Idle-suspend (cost): 2 min without any
                    // transcript — WS closes, pump + VAD stay.
                    if (deepgramActive && !deepgramSuspended &&
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
                        fallbackToSystem("Deepgram pump failed: ${e.message}")
                    }
                }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                if (deepgramRecord == record) deepgramRecord = null
            }
        }
        deepgramPumpThread = thread
        thread.start()
    }

    /** PCM-energy RMS for the barge-in loud gate (the recognizer
     *  no longer supplies RMS in Deepgram mode). Tracked only
     *  while NOT speaking — same freeze rule as onRmsChanged. */
    private fun updateRmsFromPcm(samples: ShortArray, count: Int) {
        if (isSpeaking || count <= 0) return
        var sumSquares = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble()
            sumSquares += v * v
        }
        val rms = kotlin.math.sqrt(sumSquares / count) / 32768.0
        val rmsDb = (20 * kotlin.math.log10(rms + 1e-9)).toFloat()
        lastRmsDb = rmsDb
        rmsBaselineDb = if (rmsBaselineDb == null) {
            rmsDb
        } else {
            rmsBaselineDb!! * 0.9f + rmsDb * 0.1f
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
        if (!enabled) return
        enabled = false
        // A-H6: cancel the held restart handle — a posted restart
        // must never fire after stop().
        restartPending = false
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
        consecutiveErrorCount = 0
        quarantinedWhileSpeaking.clear()
        speakingDepth = 0
        isSpeaking = false
        currentSpokenText = ""
        mainHandler.removeCallbacks(turnCheckRunnable)
        // 1.4.3: Deepgram stream + pump mic die with the loop.
        stopDeepgramStream()
        sttPathReason = "stopped"
        mainHandler.post {
            runCatching { speechRecognizer?.cancel() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            listeningActive = false
            listener.onListeningStateChanged(false)
        }
        stopVadMonitor()
        turnDetector.reset()
    }

    /**
     * A-H4: terminal stop — tears the loop down and surfaces
     * [AlwaysOnMicListener.onMicError] instead of silently
     * restarting. Called from the main thread (onError path).
     */
    private fun stopWithError(reason: String) {
        enabled = false
        restartPending = false
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
        mainHandler.removeCallbacks(turnCheckRunnable)
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        listeningActive = false
        listener.onListeningStateChanged(false)
        stopVadMonitor()
        turnDetector.reset()
        listener.onMicError(reason)
    }

    /** Releases everything; call from the owning service's onDestroy. */
    fun shutdown() {
        stop()
        vadScope.cancel()
    }

    /**
     * Coordinator wiring for barge-in: called with VoiceManager's
     * stop path (it stops Kokoro / Deepgram / Gemini / system TTS).
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
            vadMonitor?.pauseForSpeech()
        } else {
            if (speakingDepth > 0) speakingDepth--
            if (speakingDepth > 0) return
            isSpeaking = false
            currentSpokenText = ""
            vadMonitor?.resumeAfterSpeech()
            // B-C3: flush quarantined transcripts now, stamped
            // with post-TTS time, so they form a turn only after
            // post-TTS silence — never mid-speech.
            if (enabled && quarantinedWhileSpeaking.isNotEmpty()) {
                val held = quarantinedWhileSpeaking.toList()
                quarantinedWhileSpeaking.clear()
                val nowMs = System.currentTimeMillis()
                for (item in held) {
                    if (item.isFinal) {
                        turnDetector.onFinal(item.text, nowMs, true)
                    } else {
                        turnDetector.onPartial(item.text, nowMs, true)
                    }
                }
                listener.onPartialTranscript(turnDetector.currentText())
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
     * Returns true only if the monitor actually started.
     */
    fun enableVadMonitor(modelFile: File, onSegment: (SpeechSegment) -> Unit): Boolean {
        if (!enabled) return false
        val monitor = SherpaVadMonitor(appContext, modelFile, onSegment) { isSpeaking }
        if (!monitor.isAvailable()) return false
        stopVadMonitor()
        vadMonitor = monitor
        vadSegmentCallback = onSegment
        // 1.4.3: in Deepgram mode the pump feeds this monitor in
        // tap mode — starting runLoop here would open a SECOND
        // AudioRecord and steal the mic from the pump.
        if (sttPath == "deepgram") return true
        vadJob = vadScope.launch { monitor.runLoop() }
        return true
    }

    fun disableVadMonitor() {
        stopVadMonitor()
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

    private fun handleTranscript(text: String, isFinal: Boolean) {
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
        if (isFinal && BargeInDetector.isBargeIn(
                transcript = text,
                isSpeaking = isSpeaking,
                spokenText = currentSpokenText,
                rmsDb = lastRmsDb,
                rmsBaselineDb = rmsBaselineDb
            )
        ) {
            // 1.4.3: the abort cause is recorded HERE (the
            // callback itself carries no reason) so Diagnostics
            // can tell a barge-in cut from a tap cut or a crash.
            JarvisStateRepository.addDiagnostic(
                "tts", "SPEAK_ABORT",
                "Barge-in stop keyword at TTS: transcript starts with a stop word."
            )
            stopSpeakingCallback?.stopSpeaking()
            setSpeaking(false)
            val remainder = BargeInDetector.remainderAfterKeyword(text)
            if (remainder.isNotEmpty()) {
                // "ruk, youtube band karo" -> the follow-up command
                // seeds the next turn; a bare "ruk" seeds nothing.
                turnDetector.reset()
                turnDetector.onPartial(remainder, System.currentTimeMillis(), isNewSession = true)
                listener.onPartialTranscript(remainder)
            } else {
                turnDetector.reset()
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
        val nowMs = System.currentTimeMillis()
        val isNewSession = lastPartialSessionId != currentSessionId
        if (isFinal) {
            turnDetector.onFinal(text, nowMs, isNewSession)
        } else {
            turnDetector.onPartial(text, nowMs, isNewSession)
        }
        lastPartialSessionId = currentSessionId
        listener.onPartialTranscript(turnDetector.currentText())
    }

    private fun startRecognizerSession() {
        if (!enabled) return
        // A-H4: RECORD_AUDIO is re-checked on EVERY restart — a
        // revoked grant stops the loop instead of spinning.
        if (!isRecordAudioGranted()) {
            stopWithError("RECORD_AUDIO revoked — mic loop stopped.")
            return
        }
        // A-H6: never double-start — a live session, or a start
        // less than ~150ms ago, means a restart already fired.
        val nowMs = System.currentTimeMillis()
        if (listeningActive) return
        if (nowMs - lastStartMs < RESTART_GUARD_MS) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            stopWithError("SpeechRecognizer became unavailable; mic loop stopping.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 2)
        }
        // A-H7: creation + listener + startListening in ONE
        // guarded block — a creation/start failure degrades to
        // onMicError, never a main-thread crash.
        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            }
            speechRecognizer?.setRecognitionListener(recognitionListener)
            currentSessionId++
            speechRecognizer?.startListening(intent)
            lastStartMs = System.currentTimeMillis()
        } catch (e: Exception) {
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            listeningActive = false
            stopWithError("Recognizer start failed (${e.message}) — mic loop stopped.")
        }
    }

    // A-H6: ONE held restart runnable — stop() removes it, so a
    // restart can never fire after the loop stopped.
    private fun scheduleRestart(delayMs: Long) {
        if (!enabled || restartPending) return
        restartPending = true
        val runnable = Runnable {
            restartPending = false
            restartRunnable = null
            if (enabled) startRecognizerSession()
        }
        restartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    /**
     * A-H4/A-H5: escalating backoff for counted (non-transient)
     * errors, RECOGNIZER_BUSY included — no fixed-delay bypass.
     * Delay doubles per consecutive error (150ms, 300ms, 600ms…
     * capped at ~4.8s) and, past [MAX_CONSECUTIVE_ERRORS], the
     * loop STOPS with onMicError instead of looping silently
     * forever. The count resets ONLY on recognizer progress
     * (onReady / onResults) — never on a wall-clock window, so a
     * flapping recognizer cannot defeat escalation by waiting
     * out a window.
     */
    private fun countedBackoffDelay(): Long {
        consecutiveErrorCount++
        if (consecutiveErrorCount > MAX_CONSECUTIVE_ERRORS) {
            stopWithError(
                "Recognizer failed $consecutiveErrorCount times in a row — mic loop stopped."
            )
            return RESTART_DELAY_MS
        }
        val shift = (consecutiveErrorCount - 1).coerceAtMost(5)
        return RESTART_DELAY_MS * (1L shl shift)
    }

    private fun resetLanguageToPrimary() {
        currentLanguage = LANGUAGE_PRIMARY_HI_IN
        emptySessionFallbackUsed = false
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

        /** Normal restart gap: effectively immediate, not a spin. */
        const val RESTART_DELAY_MS: Long = 150L
        /** A-H6 double-start guard window. */
        const val RESTART_GUARD_MS: Long = 150L
        /** A-H4/A-H5: counted errors before the loop stops itself. */
        const val MAX_CONSECUTIVE_ERRORS: Int = 10
        /** B-C3: cap on transcripts held while speaking. */
        const val MAX_QUARANTINED: Int = 20
        /** 1.4.3: silence after which the Deepgram WS suspends
         *  (cost) while the pump + VAD tap keep running. */
        const val IDLE_SUSPEND_MS: Long = 120_000L

        /** A-H4: terminal recognizer errors — stop, never retry. */
        val TERMINAL_RECOGNIZER_ERRORS: Set<Int> = setOf(
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_AUDIO
        )
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

    /** Fatal mic problem (permission, no recognizer). The loop is not running. */
    fun onMicError(reason: String)

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
 * acceptWaveform(FloatArray), isSpeechDetected(), empty(),
 * front(): SpeechSegment(start: Int, samples: FloatArray), pop(),
 * flush(), reset(), release(); VadModelConfig(SileroVadModelConfig,
 * TenVadModelConfig, sampleRate, numThreads, provider, debug);
 * SileroVadModelConfig(model, threshold, minSilenceDuration,
 * minSpeechDuration, windowSize, maxSpeechDuration);
 * getVadModelConfig(sampleRate) (top-level function in Vad.kt).
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
 *    dependency for Kokoro, so these imports add no new dep.)
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

    init {
        available = runCatching {
            if (!modelFile.exists()) return@runCatching false
            // getVadModelConfig's Kotlin metadata declares a
            // nullable return (VadModelConfig?), so a null result
            // means VAD is unavailable — same as a missing model.
            val baseConfig = getVadModelConfig(SAMPLE_RATE_HZ) ?: return@runCatching false
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
            vad = Vad(appContext.assets, config)
            true
        }.getOrDefault(false)
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
