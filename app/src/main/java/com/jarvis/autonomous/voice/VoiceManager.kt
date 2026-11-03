package com.jarvis.autonomous.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * VoiceManager — Kokoro ONLY (1.4.0).
 *
 * SPEECH (output) is Kokoro hf_alpha, on-device via Sherpa-ONNX, and
 * nothing else. The 1.3.0 chain is gone, deliberately:
 *  - Deepgram TTS (cloud, user key) — REMOVED.
 *  - Gemini TTS Sulafat (cloud, user key, tiny free quota) — REMOVED.
 *  - Android system TextToSpeech (incl. its init, language setup and
 *    female-voice selection) — REMOVED as a voice. System STT below
 *    (SpeechRecognizer) is a different API and is UNTOUCHED.
 *
 * If Kokoro is unavailable, [speakAndWait] returns false and logs a
 * clear message. It NEVER silently falls back to another voice, and
 * it never leaves the caller guessing: false means "nothing was
 * spoken". Callers that only need fire-and-forget speech can ignore
 * the return value — the service lambda
 * `onSpeakAndAwait = { text -> voiceManager.speakAndWait(text) }`
 * still compiles unchanged, because a Boolean result is coerced to
 * Unit for a `suspend (String) -> Unit` function type.
 *
 * Signature change (documented, see INTEGRATION_NOTES_Kokoro.md):
 *  - 1.3.0: `suspend fun speakAndWait(text: String)` (Unit)
 *  - 1.4.0: `suspend fun speakAndWait(text: String): Boolean`
 * [stopSpeaking] is unchanged in signature.
 *
 * First-run behaviour: the first call triggers
 * KokoroTtsClient's bundled-asset copy (assets/kokoro/ -> files
 * dir Kokoro/) with progress logging. That copy must ALSO be
 * driven from the UI at app launch with a VISIBLE progress screen
 * (see INTEGRATION_NOTES_Kokoro.md §5) — this class only logs, it
 * cannot show a screen, so a speak-triggered copy is the safety
 * net, not the intended first-run UX.
 *
 * prefs.voiceSpeed / prefs.voicePitch are no longer applied here:
 * they only ever tuned system TTS. Kokoro speaks at its fixed
 * speed (1.0f in KokoroTtsClient). The prefs fields themselves
 * live in JarvisPreferences (outside this worker's ownership) and
 * are listed as dead in the integration notes until the
 * coordinator removes or rewires the slider.
 */
class VoiceManager(
    private val context: Context,
    private val prefs: JarvisPreferences,
    private val onWakeCommandDetected: (String) -> Unit,
    /**
     * A-H3 single mic-owner gate, wired by the service to
     * AlwaysOnMicManager.isEnabled(). EVERY recognizer start in
     * this class checks it first: the wake-word recognizer NEVER
     * runs alongside the mic-loop recognizer.
     */
    private val isMicLoopEnabled: () -> Boolean = { false }
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningActive = false
    private var pendingSingleReplyCallback: ((String?) -> Unit)? = null
    /** B-M13: ALL speaks serialise through this mutex. */
    private val speakMutex = Mutex()
    /**
     * 1.4.3: true while a Kokoro utterance is playing. Mic-tap
     * paths read this to QUEUE listening until TTS ends instead
     * of cutting speech mid-word (the old stopSpeaking() on tap
     * was a top "bolti hi nahi / band ho jati hai" cause).
     */
    @Volatile
    private var speakInProgress: Boolean = false
    /** A-H6: the single cancellable wake-word restart handle. */
    private var restartRunnable: Runnable? = null
    /** A-H6: last recognizer start, for the 150ms double-start guard. */
    private var lastStartMs: Long = 0L

    /**
     * Speaks [text] with Kokoro only. Returns true if the full text
     * was spoken, false if nothing (or only part) was spoken — e.g.
     * Kokoro disabled in prefs, bundled model missing, free space
     * too low for the first-run copy, or playback interrupted by
     * [stopSpeaking]. On false, the reason is in the app log.
     */
    suspend fun speakAndWait(text: String): Boolean {
        if (text.isBlank()) return false
        // B-M13: serialised — concurrent speaks queue here instead
        // of racing Kokoro's shared track/stop flag. Signature
        // unchanged (suspend fun speakAndWait(String): Boolean).
        return speakMutex.withLock {
            stopListeningInternal()

            if (!prefs.kokoroEnabled) {
                JarvisStateRepository.addLog(
                    "Kokoro voice OFF in settings — nothing spoken " +
                        "(Kokoro-only build: no other voice is used)."
                )
                return@withLock false
            }

            // 1.4.5: synthesis runs in the ISOLATED :tts process
            // (TtsProcessClient) — a native Sherpa/ONNX death
            // during model load / generate kills only TTS, never
            // this UI process. On :tts death this returns false
            // (with tts/NATIVE_TTS_PROCESS_DIED recorded) instead
            // of the app vanishing; see KokoroTtsService.
            val focus = requestTransientFocus()
            speakInProgress = true
            val ok = try {
                TtsProcessClient.speak(context, text)
            } finally {
                speakInProgress = false
                abandonFocus(focus)
            }
            if (!ok) {
                JarvisStateRepository.addLog(
                    "Kokoro unavailable — nothing spoken " +
                        "(Kokoro-only build: no Deepgram / Gemini / " +
                        "system-TTS fallback exists any more)."
                )
                return@withLock false
            }

            // A-H3 speak-resume leak fix: a speak that ends while
            // the mic loop is enabled must NOT restart wake-word
            // listening — the loop already owns the mic. A-H9:
            // when wake-word mode is on and the loop is off, the
            // restart no longer depends on the agent being IDLE.
            if (prefs.wakeWordEnabled && !isMicLoopEnabled()) {
                startWakeWordListening()
            }
            true
        }
    }

    fun stopSpeaking() {
        stopSpeaking("unspecified (legacy call)")
    }

    /**
     * 1.4.3: EVERY speak abort is logged with its cause — the old
     * silent stop made "bolti hi nahi / band ho jati hai"
     * undebuggable. Only explicit stops (barge-in keyword,
     * STOP_TASK) cut speech; mic taps queue instead (see
     * triggerManualVoiceCommand / listenForSingleReply).
     */
    fun stopSpeaking(reason: String) {
        // B-M13: stops only the utterance currently playing.
        // Queued speaks (waiting on speakMutex) are untouched —
        // Stop never cancels the whole queue.
        JarvisStateRepository.addDiagnostic(
            "tts", "SPEAK_ABORT", "Speech stopped: $reason"
        )
        // 1.4.5: playback lives in the :tts process — stop it
        // there. (The old main-process stopPlayback() call is
        // gone: no AudioTrack exists in this process any more.)
        TtsProcessClient.stop()
    }

    /** B-M13: transient focus for one utterance; null if unavailable. */
    private fun requestTransientFocus(): Any? {
        return runCatching {
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioManager.requestAudioFocus(request)
                request
            } else {
                @Suppress("DEPRECATION")
                val listener = AudioManager.OnAudioFocusChangeListener { }
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                listener
            }
        }.getOrNull()
    }

    private fun abandonFocus(focus: Any?) {
        if (focus == null) return
        runCatching {
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Branch on SDK FIRST: the AudioFocusRequest type must
            // never even be resolved on pre-O devices, where the
            // class does not exist.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(focus as AudioFocusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(
                    focus as AudioManager.OnAudioFocusChangeListener
                )
            }
        }
    }

    /**
     * Listens for a single user voice response (e.g., when mic button is tapped or Jarvis asks a mid-task question).
     */
    suspend fun listenForSingleReply(): String? {
        // A-H3: while the mic loop owns the mic, no second
        // recognizer may start here — the service answers single
        // replies from the loop's next turn instead.
        if (isMicLoopEnabled()) {
            JarvisStateRepository.addLog(
                "Single reply not started: mic loop owns the mic."
            )
            return null
        }
        return withTimeoutOrNull(12_000L) {
            // 1.4.3: queue BEHIND any playing TTS instead of
            // cutting it — the old stopSpeaking() here was why
            // Jarvis "bolti hi nahi / band ho jati hai" when the
            // user tapped mic mid-speech. Wait up to 8s for the
            // utterance to finish, then listen without stopping.
            val waitStart = System.currentTimeMillis()
            while (speakInProgress &&
                System.currentTimeMillis() - waitStart < 8_000L
            ) {
                delay(200L)
            }
            suspendCancellableCoroutine { cont ->
            mainHandler.post {
                pendingSingleReplyCallback = { recognizedText ->
                    if (cont.isActive) cont.resume(recognizedText)
                }
                startRecognizerInternal(isSingleShot = true)
            }
            cont.invokeOnCancellation {
                mainHandler.post {
                    pendingSingleReplyCallback = null
                    stopListeningInternal()
                }
            }
            }
        }
    }

    fun triggerManualVoiceCommand() {
        // A-H3: overlay mic tap while the mic loop runs must not
        // start a second recognizer — the loop is already
        // listening, so the tap is a no-op (not a double start).
        if (isMicLoopEnabled()) {
            JarvisStateRepository.addLog(
                "Mic tap ignored: mic loop already listening."
            )
            return
        }
        // 1.4.3: a mic tap during TTS QUEUES listening until the
        // utterance ends — it never cuts speech. Only an explicit
        // stop (voice "ruk"/"bas", STOP button) stops TTS.
        val startTapListening = object : Runnable {
            var tries = 0
            override fun run() {
                if (speakInProgress && tries < 32) {
                    tries++
                    mainHandler.postDelayed(this, 250L)
                    return
                }
                JarvisStateRepository.setAgentState(
                    AgentRunState.LISTENING,
                    "Listening... बोलो भाई क्या करना है?"
                )
                pendingSingleReplyCallback = { text ->
                    if (!text.isNullOrBlank()) {
                        onWakeCommandDetected(text)
                    } else {
                        JarvisStateRepository.setAgentState(
                            AgentRunState.IDLE,
                            "Didn't catch that. Tap Core to speak."
                        )
                        if (prefs.wakeWordEnabled) startWakeWordListening()
                    }
                }
                startRecognizerInternal(isSingleShot = true)
            }
        }
        mainHandler.post(startTapListening)
    }

    fun startWakeWordListening() {
        if (!prefs.wakeWordEnabled) return
        // C-B13: without RECORD_AUDIO, starting would only error
        // and (before this fix) hot-restart — refuse up front.
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            JarvisStateRepository.addLog(
                "Wake-word not started: RECORD_AUDIO not granted."
            )
            return
        }
        mainHandler.post {
            // A-H3 single-owner gate + A-H9: wake-word restarts
            // whenever wake-word mode is on and the loop is off,
            // regardless of agent state — but never next to the loop.
            if (isMicLoopEnabled()) return@post
            if (isListeningActive || pendingSingleReplyCallback != null) return@post
            startRecognizerInternal(isSingleShot = false)
        }
    }

    /** A-H6: schedule a cancellable wake-word restart. */
    private fun scheduleWakeWordRestart(delayMs: Long) {
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            restartRunnable = null
            startWakeWordListening()
        }
        restartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun startRecognizerInternal(isSingleShot: Boolean) {
        // A-H3: never start next to the mic loop. (Single-shot
        // callers already gate before posting; this is the last
        // line of defence for wake-word restarts.)
        if (!isSingleShot && isMicLoopEnabled()) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            JarvisStateRepository.addLog("SpeechRecognizer not available on this device.")
            return
        }
        // A-H6: no double start within ~150ms of a live start.
        val nowMs = System.currentTimeMillis()
        if (isListeningActive && nowMs - lastStartMs < 150L) return

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, !isSingleShot)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 2)
        }

        // A-H7: creation + listener + startListening in ONE
        // guarded block — a failure logs and ends this attempt,
        // never a main-thread crash.
        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            }
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListeningActive = true
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListeningActive = false
                }

                override fun onError(error: Int) {
                    isListeningActive = false
                    val cb = pendingSingleReplyCallback
                    if (cb != null) {
                        pendingSingleReplyCallback = null
                        cb.invoke(null)
                    } else if (prefs.wakeWordEnabled && !isMicLoopEnabled()) {
                        // C-B13: a permission error BREAKS the
                        // restart loop — no hot restart on
                        // ERROR_INSUFFICIENT_PERMISSIONS. A-H9:
                        // other errors restart regardless of
                        // agent state (no IDLE gate any more).
                        if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            scheduleWakeWordRestart(600L)
                        } else {
                            JarvisStateRepository.addLog(
                                "Wake-word stopped: RECORD_AUDIO permission error — no restart."
                            )
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListeningActive = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val bestText = matches?.firstOrNull()?.trim() ?: ""

                    val cb = pendingSingleReplyCallback
                    if (cb != null) {
                        pendingSingleReplyCallback = null
                        cb.invoke(bestText.ifEmpty { null })
                        return
                    }

                    // A-M4 wake-word check (see [matchWakeTrigger]):
                    // trigger at the START or as a standalone
                    // address only — never a substring anywhere.
                    if (bestText.isNotEmpty()) {
                        val matched = matchWakeTrigger(bestText)
                        if (matched != null) {
                            val (trigger, remainder) = matched
                            if (remainder.isNotEmpty()) {
                                onWakeCommandDetected(remainder)
                            } else {
                                // Bare "Hey Jarvis" -> open the
                                // active command listener.
                                triggerManualVoiceCommand()
                            }
                            return
                        }
                    }

                    // A-H9: restart regardless of agent state
                    // (subject to the A-H3 loop gate inside
                    // startWakeWordListening).
                    if (prefs.wakeWordEnabled) {
                        scheduleWakeWordRestart(400L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer?.startListening(recognizerIntent)
            lastStartMs = System.currentTimeMillis()
        } catch (e: Exception) {
            isListeningActive = false
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            JarvisStateRepository.addLog(
                "Wake-word recognizer start failed (${e.message}) — no restart loop."
            )
        }
    }

    /**
     * A-M4: returns (trigger, remainder) when [text] is addressed
     * to Jarvis, else null. Normalised first (punctuation stripped,
     * case folded). Valid forms, longest trigger first:
     * "hey jarvis" / "ok jarvis" / "sun jarvis" / "jarvis", either
     * bare or at the START followed by a command. A trigger in the
     * middle ("aaj jarvis ko mat bulao") never matches, and a
     * postposition right after the trigger ("jarvis KO mat bulao"
     * — Jarvis as grammatical object, not addressee) never
     * matches either.
     */
    private fun matchWakeTrigger(text: String): Pair<String, String>? {
        val tokens = TurnDetector.normalizeTokens(text)
        if (tokens.isEmpty()) return null
        val joined = tokens.joinToString(" ")
        val triggers = listOf("hey jarvis", "ok jarvis", "sun jarvis", "jarvis")
        for (trigger in triggers) {
            if (joined == trigger) return trigger to ""
            if (joined.startsWith("$trigger ")) {
                val remainderTokens = tokens.drop(trigger.split(" ").size)
                // Talking ABOUT Jarvis, not TO Jarvis.
                if (remainderTokens.firstOrNull() in WAKE_OBJECT_MARKERS) return null
                return trigger to remainderTokens.joinToString(" ")
            }
        }
        return null
    }

    fun stopListeningInternal() {
        mainHandler.post {
            // A-H6/A-H8: cancel any held restart and complete any
            // pending single reply as cancelled — a stale reply
            // must never swallow the next command.
            restartRunnable?.let { mainHandler.removeCallbacks(it) }
            restartRunnable = null
            val cb = pendingSingleReplyCallback
            pendingSingleReplyCallback = null
            cb?.invoke(null)
            isListeningActive = false
            runCatching { speechRecognizer?.cancel() }
        }
    }

    fun shutdown() {
        mainHandler.post {
            restartRunnable?.let { mainHandler.removeCallbacks(it) }
            restartRunnable = null
            val cb = pendingSingleReplyCallback
            pendingSingleReplyCallback = null
            cb?.invoke(null)
            isListeningActive = false
            runCatching {
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
        }
    }

    companion object {
        /** Postpositions marking Jarvis as object, not addressee. */
        private val WAKE_OBJECT_MARKERS: Set<String> = setOf(
            "ko", "ka", "ki", "ke", "se", "mein", "me", "par", "tak"
        )
    }
}
