package com.jarvis.autonomous.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class VoiceManager(
    private val context: Context,
    private val prefs: JarvisPreferences,
    private val onWakeCommandDetected: (String) -> Unit
) : TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val geminiTts = GeminiTtsClient()
    private val deepgramTts = DeepgramTtsClient()
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningActive = false
    private var pendingSingleReplyCallback: ((String?) -> Unit)? = null

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val locale = Locale("en", "IN")
            val res = tts?.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.US
            }
            selectFemaleVoice()
            isTtsReady = true
            JarvisStateRepository.addLog("TTS Voice Engine Ready (en-IN / Hinglish).")
        }
    }

    // Best-effort female voice: Android TTS has no gender flag, so pick
    // a voice whose name marks it female (Google TTS names do), preferring
    // en-IN, then hi-IN, then en-US. If the phone has no female voice
    // installed, the higher pitch default (1.15) still makes the default
    // voice sound more female — a true human-like cloud voice (e.g.
    // Edge-TTS Swara) would need a server/key and is NOT claimed here.
    private fun selectFemaleVoice() {
        val voices = try {
            tts?.voices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (voices.isEmpty()) return
        val isFemaleName = { name: String ->
            val n = name.lowercase()
            n.contains("female") || n.contains("-f-") || n.contains("_f_") ||
                n.contains("f1") || n.contains("woman")
        }
        val localeOrder = listOf("en_IN", "hi_IN", "en_US", "en_GB")
        val picked = localeOrder.firstNotNullOfOrNull { want ->
            voices.firstOrNull { v ->
                v.locale.toString() == want && isFemaleName(v.name)
            }
        } ?: voices.firstOrNull { isFemaleName(it.name) }
        if (picked != null) {
            runCatching { tts?.voice = picked }
            JarvisStateRepository.addLog("Female TTS voice selected: ${picked.name}")
        }
    }

    suspend fun speakAndWait(text: String) {
        if (text.isBlank()) return
        stopListeningInternal()

        // Jarvis's voice order (1.3.0):
        // 1. Kokoro hf_alpha (offline, Flora's voice) — Hindi ke liye
        //    sabse sahi, bina key/quota ke, bas ek baar download.
        // 2. Deepgram Theia (cloud, user key) — English-focused.
        // 3. Gemini Sulafat (cloud, user key, chhota free quota).
        // 4. System TTS — kabhi silent nahi.
        // Har engine fail hone par agla try hota hai.
        if (prefs.kokoroEnabled && KokoroTtsClient.isDownloaded(context)) {
            val ok = KokoroTtsClient.speak(context, text)
            if (ok) {
                if (prefs.wakeWordEnabled && JarvisStateRepository.agentState.value == AgentRunState.IDLE) {
                    startWakeWordListening()
                }
                return
            }
        }

        if (prefs.deepgramTtsEnabled && prefs.deepgramApiKey.isNotBlank()) {
            val ok = deepgramTts.speak(text, prefs.deepgramApiKey, prefs.deepgramVoice)
            if (ok) {
                if (prefs.wakeWordEnabled && JarvisStateRepository.agentState.value == AgentRunState.IDLE) {
                    startWakeWordListening()
                }
                return
            }
        }

        // Jarvis's voice = Gemini TTS (Sulafat, warm female, romantic
        // deep style) when the user has a Gemini key and quota left.
        // Any failure falls back to system TTS below — never silent.
        if (prefs.geminiTtsEnabled && prefs.apiKey.isNotBlank()) {
            val ok = geminiTts.speak(text, prefs.apiKey, prefs.geminiTtsVoice)
            if (ok) {
                if (prefs.wakeWordEnabled && JarvisStateRepository.agentState.value == AgentRunState.IDLE) {
                    startWakeWordListening()
                }
                return
            }
        }

        if (!isTtsReady) return

        withTimeoutOrNull(25_000L) {
            suspendCancellableCoroutine { cont ->
                val utteranceId = UUID.randomUUID().toString()
                tts?.setSpeechRate(prefs.voiceSpeed)
                tts?.setPitch(prefs.voicePitch)

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        if (id == utteranceId && cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        if (id == utteranceId && cont.isActive) cont.resume(Unit)
                    }
                })

                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

                cont.invokeOnCancellation {
                    tts?.stop()
                }
            }
        }

        // Resume background wake-word listening if enabled and idle
        if (prefs.wakeWordEnabled && JarvisStateRepository.agentState.value == AgentRunState.IDLE) {
            startWakeWordListening()
        }
    }

    fun stopSpeaking() {
        KokoroTtsClient.stopPlayback()
        deepgramTts.stopPlayback()
        geminiTts.stopPlayback()
        tts?.stop()
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
                    val wakeTriggers = listOf("jarvis", "hey jarvis", "ok jarvis", "sun jarvis", "sun bhai")
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
                tts?.stop()
                tts?.shutdown()
            }
        }
    }
}
