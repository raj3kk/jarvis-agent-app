package com.jarvis.autonomous.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.jarvis.autonomous.data.JarvisStateRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 1.4.12 — Android system TextToSpeech FALLBACK speaker (kept
 * unchanged from 1.4.10 except this header: behaviour is
 * identical). Swara (cloud Edge voice) stays PRIMARY; this
 * runs when VoiceManager decides Swara is latched (or this
 * speak tripped the latch) or the Swara toggle is OFF — never
 * as a silent replacement, never alongside Swara.
 *
 * Why system TTS is the right fallback (audit §5): its failure
 * mode is independent of Swara's (the engine runs in the
 * SYSTEM process — a system-engine death cannot kill this
 * app), it needs no permission, no FGS type, no bundle and
 * no key. What it CANNOT do: sound like the primary voice.
 * The fallback voice is whatever the system engine offers
 * (best-effort hi-IN female pick, guarantee nahi) — the
 * delivery message and the latch card say so plainly; this is
 * a stopgap, not a replacement.
 *
 * Package-visibility note (Step 7 justification for SKIPPING a
 * <queries> entry): this code never calls getEngines() and never
 * resolves the engine service via PackageManager — TextToSpeech
 * binds via the framework service, and getVoices()/setLanguage()
 * query the already-bound engine. So no
 * INTENT_ACTION_TTS_SERVICE queries entry is needed for this
 * usage; adding one would be cargo-cult. If a future change
 * calls getEngines(), the entry must be added then.
 */
object SystemTtsFallbackSpeaker {

    private const val INIT_TIMEOUT_MS = 10_000L

    suspend fun speak(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        JarvisStateRepository.addDiagnostic(
            "tts", "TTS_FALLBACK_INIT",
            "System-TTS fallback init started (Swara latched/unavailable)."
        )
        val appContext = context.applicationContext
        val initResult = CompletableDeferred<Int>()
        val tts = runCatching {
            TextToSpeech(appContext) { status -> initResult.complete(status) }
        }.getOrNull()
        if (tts == null) {
            JarvisStateRepository.addDiagnostic(
                "tts", "TTS_FALLBACK_FAILED",
                "System-TTS fallback unavailable: TextToSpeech construction threw."
            )
            return false
        }
        try {
            val status = withTimeoutOrNull(INIT_TIMEOUT_MS) { initResult.await() }
            if (status != TextToSpeech.SUCCESS) {
                JarvisStateRepository.addDiagnostic(
                    "tts", "TTS_FALLBACK_FAILED",
                    "System-TTS fallback init failed (status=$status, timedOut=${status == null}) — no system engine."
                )
                return false
            }
            // Language: hi-IN first (Jarvis speaks Hindi/Hinglish),
            // then en-IN, then default. Best-effort: if all are
            // unavailable, speak anyway in the engine default.
            val languageUsed = pickLanguage(tts)
            pickFemaleVoice(tts, languageUsed)
            val done = CompletableDeferred<Boolean>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    done.complete(true)
                }

                override fun onError(utteranceId: String?) {
                    done.complete(false)
                }
            })
            val speakResult = tts.speak(
                text, TextToSpeech.QUEUE_FLUSH, null,
                "jarvis-fallback-${System.currentTimeMillis()}"
            )
            if (speakResult != TextToSpeech.SUCCESS) {
                JarvisStateRepository.addDiagnostic(
                    "tts", "TTS_FALLBACK_FAILED",
                    "System-TTS fallback speak() refused (result=$speakResult)."
                )
                return false
            }
            // Bound: ~15 chars/sec + 30 s base, capped at 180 s.
            val timeoutMs = minOf(180_000L, 30_000L + text.length * 100L)
            val finished = withTimeoutOrNull(timeoutMs) { done.await() } ?: false
            if (!finished) {
                runCatching { tts.stop() }
                JarvisStateRepository.addDiagnostic(
                    "tts", "TTS_FALLBACK_FAILED",
                    "System-TTS fallback timed out after ${timeoutMs}ms — stopped."
                )
                return false
            }
            JarvisStateRepository.addDiagnostic(
                "tts", "TTS_FALLBACK_ENDED",
                "System-TTS fallback finished (language=$languageUsed)."
            )
            return true
        } finally {
            runCatching { tts.shutdown() }
        }
    }

    private fun pickLanguage(tts: TextToSpeech): String {
        val candidates = listOf(
            Locale("hi", "IN") to "hi-IN",
            Locale("en", "IN") to "en-IN",
            Locale.ENGLISH to "en"
        )
        for ((locale, label) in candidates) {
            val availability = runCatching { tts.setLanguage(locale) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (availability == TextToSpeech.LANG_AVAILABLE ||
                availability == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                availability == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            ) {
                return label
            }
        }
        return "default"
    }

    private fun pickFemaleVoice(tts: TextToSpeech, languageUsed: String) {
        runCatching {
            val locale = when (languageUsed) {
                "hi-IN" -> Locale("hi", "IN")
                "en-IN" -> Locale("en", "IN")
                "en" -> Locale.ENGLISH
                else -> return
            }
            val voices: Set<Voice> = tts.voices ?: return
            val female = voices.firstOrNull { voice ->
                voice.locale == locale && !voice.isNetworkConnectionRequired &&
                    voice.name.contains("female", ignoreCase = true)
            } ?: return
            tts.voice = female
        }
    }
}
