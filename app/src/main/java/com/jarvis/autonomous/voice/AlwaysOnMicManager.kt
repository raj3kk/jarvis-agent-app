package com.jarvis.autonomous.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
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
     * Starts the always-on loop. Preconditions (coordinator-owned,
     * NOT re-checked here beyond RECORD_AUDIO): the foreground
     * service must already be running, and the user must have
     * enabled the mic (Settings toggle). Returns false and calls
     * [AlwaysOnMicListener.onMicError] if RECORD_AUDIO is missing
     * or no recognizer exists on the device. Safe to call twice.
     */
    fun start(): Boolean {
        if (enabled) return true
        if (!isRecordAudioGranted()) {
            listener.onMicError("RECORD_AUDIO not granted; mic loop not started.")
            return false
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            listener.onMicError("SpeechRecognizer not available on this device.")
            return false
        }
        enabled = true
        consecutiveErrorCount = 0
        lastStartMs = 0L
        quarantinedWhileSpeaking.clear()
        turnDetector.reset()
        resetLanguageToPrimary()
        mainHandler.post {
            startRecognizerSession()
            mainHandler.postDelayed(turnCheckRunnable, TurnThresholds.TURN_CHECK_INTERVAL_MS)
        }
        return true
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
 * 1. MODEL NOT BUNDLED: Silero VAD needs a silero_vad.onnx model
 *    file. No such file ships in this repo (Kokoro ships its own
 *    TTS models, not a VAD model). If [modelFile] does not exist,
 *    [isAvailable] is false and this class does nothing.
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
