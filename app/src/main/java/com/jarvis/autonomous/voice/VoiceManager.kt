package com.jarvis.autonomous.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import kotlinx.coroutines.suspendCancellableCoroutine
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
    private val onWakeCommandDetected: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningActive = false
    private var pendingSingleReplyCallback: ((String?) -> Unit)? = null

    /**
     * Speaks [text] with Kokoro only. Returns true if the full text
     * was spoken, false if nothing (or only part) was spoken — e.g.
     * Kokoro disabled in prefs, bundled model missing, free space
     * too low for the first-run copy, or playback interrupted by
     * [stopSpeaking]. On false, the reason is in the app log.
     */
    suspend fun speakAndWait(text: String): Boolean {
        if (text.isBlank()) return false
        stopListeningInternal()

        if (!prefs.kokoroEnabled) {
            JarvisStateRepository.addLog(
                "Kokoro voice OFF in settings — nothing spoken " +
                    "(Kokoro-only build: no other voice is used)."
            )
            return false
        }

        val ok = KokoroTtsClient.speak(context, text)
        if (!ok) {
            JarvisStateRepository.addLog(
                "Kokoro unavailable — nothing spoken " +
                    "(Kokoro-only build: no Deepgram / Gemini / " +
                    "system-TTS fallback exists any more)."
            )
            return false
        }

        // Resume background wake-word listening if enabled and idle
        if (prefs.wakeWordEnabled && JarvisStateRepository.agentState.value == AgentRunState.IDLE) {
            startWakeWordListening()
        }
        return true
    }

    fun stopSpeaking() {
        KokoroTtsClient.stopPlayback()
    }

    /**
     * Listens for a single user voice response (e.g., when mic button is tapped or Jarvis asks a mid-task question).
     */
    suspend fun listenForSingleReply(): String? = withTimeoutOrNull(12_000L) {
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                stopSpeaking()
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

    fun triggerManualVoiceCommand() {
        mainHandler.post {
            stopSpeaking()
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

    fun startWakeWordListening() {
        if (!prefs.wakeWordEnabled) return
        mainHandler.post {
            if (isListeningActive || pendingSingleReplyCallback != null) return@post
            startRecognizerInternal(isSingleShot = false)
        }
    }

    private fun startRecognizerInternal(isSingleShot: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            JarvisStateRepository.addLog("SpeechRecognizer not available on this device.")
            return
        }

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
                } else if (prefs.wakeWordEnabled && JarvisStateRepository.agentState.value == AgentRunState.IDLE) {
                    mainHandler.postDelayed({ startWakeWordListening() }, 600L)
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

                // Continuous wake-word mode check
                if (bestText.isNotEmpty()) {
                    val lower = bestText.lowercase()
                    val wakeTriggers = listOf("jarvis", "hey jarvis", "ok jarvis", "sun jarvis")
                    val matchedWake = wakeTriggers.firstOrNull { lower.contains(it) }
                    if (matchedWake != null) {
                        val stripped = lower.substringAfter(matchedWake).trim()
                        if (stripped.isNotEmpty()) {
                            onWakeCommandDetected(stripped)
                        } else {
                            // User just said "Hey Jarvis" -> Open active command listener
                            triggerManualVoiceCommand()
                        }
                        return
                    }
                }

                if (prefs.wakeWordEnabled && JarvisStateRepository.agentState.value == AgentRunState.IDLE) {
                    mainHandler.postDelayed({ startWakeWordListening() }, 400L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, !isSingleShot)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 2)
        }

        runCatching {
            speechRecognizer?.startListening(recognizerIntent)
        }
    }

    fun stopListeningInternal() {
        mainHandler.post {
            isListeningActive = false
            runCatching { speechRecognizer?.cancel() }
        }
    }

    fun shutdown() {
        mainHandler.post {
            runCatching {
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
        }
    }
}
