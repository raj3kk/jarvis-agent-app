package com.jarvis.autonomous.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.jarvis.autonomous.data.JarvisStateRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 1.4.12 — Android system TextToSpeech FALLBACK speaker.
 * Swara (cloud Edge voice) stays PRIMARY; this runs when
 * VoiceManager decides Swara is latched (or this speak
 * tripped the latch) or the Swara toggle is OFF — never as
 * a silent replacement, never alongside Swara.
 *
 * Why system TTS is the right fallback (audit §5): its failure
 * mode is independent of Swara's (the engine runs in the
 * SYSTEM process — a system-engine death cannot kill this
 * app), it needs no permission, no FGS type, no bundle and
 * no key. What it CANNOT do: sound like the primary voice.
 * The fallback voice is whatever the system engine offers
 * (best-effort quality pick, guarantee nahi) — the delivery
 * message and the latch card say so plainly; this is a
 * stopgap, not a replacement.
 *
 * Package-visibility note (Step 7 justification for SKIPPING a
 * <queries> entry): this code never calls getEngines() and never
 * resolves the engine service via PackageManager — TextToSpeech
 * binds via the framework service, and getVoices()/setLanguage()
 * query the already-bound engine. So no
 * INTENT_ACTION_TTS_SERVICE queries entry is needed for this
 * usage; adding one would be cargo-cult. If a future change
 * calls getEngines(), the entry must be added then.
 *
 * WAVE-2 (ADD 1) — SystemTtsFallbackSpeaker ONLY. Swara is
 * untouched: SwaraTtsClient SSML/queue/latch-trip logic and
 * VoiceManager.speakLocked/speakViaFallback routing are not
 * changed here, and this file never reads prefs.voicePitch /
 * prefs.voiceSpeed (dead for Swara per the VoiceManager
 * header) — the 0.9 rate / 1.0 pitch below are literals for
 * the FALLBACK voice only and must never be applied to Swara
 * text. (a) Google engine lock with default-engine fallback;
 * (b) quality-ranked voice pick; (c) per-speak network-voice
 * filter; (d) rate 0.9 / pitch 1.0; (e) fallback-only text
 * cleanup + sentence queueing.
 */
object SystemTtsFallbackSpeaker {

    private const val INIT_TIMEOUT_MS = 10_000L
    private const val GOOGLE_ENGINE_PACKAGE = "com.google.android.tts"

    /**
     * 1.4.13 (F-E4): the CURRENTLY speaking engine, if any.
     * speak() used to hold its TextToSpeech locally with no
     * static handle, so its tts.stop() fired only on internal
     * timeout — VoiceManager.stopSpeaking could NOT cut a
     * fallback utterance, and the old claim that the barge-in
     * callback "stops system TTS" was false. Now stop() below
     * cuts it from anywhere; the field is cleared in speak()'s
     * finally so a stale engine is never stopped twice.
     */
    @Volatile
    private var currentTts: TextToSpeech? = null

    /** Cuts the fallback utterance in flight, if any (no-op otherwise). */
    fun stop() {
        runCatching { currentTts?.stop() }
    }

    private fun buildTts(
        appContext: Context
    ): Pair<TextToSpeech, CompletableDeferred<Int>>? {
        val initResult = CompletableDeferred<Int>()
        val tts = runCatching {
            TextToSpeech(appContext) { status -> initResult.complete(status) }
        }.getOrNull() ?: return null
        return tts to initResult
    }

    private suspend fun awaitInit(initResult: CompletableDeferred<Int>): Int? {
        return withTimeoutOrNull(INIT_TIMEOUT_MS) { initResult.await() }
    }

    suspend fun speak(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        JarvisStateRepository.addDiagnostic(
            "tts", "TTS_FALLBACK_INIT",
            "System-TTS fallback init started (Swara latched/unavailable)."
        )
        val appContext = context.applicationContext
        // WAVE-2 (ADD 1e): cleanup FIRST, and the timeout below
        // uses the CLEANED length (never the raw length) — the
        // engine speaks the cleaned text, so its length is the
        // honest bound. Swara's cleanText/xmlEscape path is
        // untouched; this cleanup lives only in this file.
        val cleaned = cleanForFallback(text, "")
        val sentences = splitSentences(cleaned)
        if (sentences.isEmpty()) return false
        // WAVE-2 (ADD 1c): online computed ONCE per speak()
        // from appContext (never cached across speaks —
        // connectivity can change between speaks).
        val online = computeOnline(appContext)

        // WAVE-2 (ADD 1a): engine lock, sequenced BEFORE any
        // language/voice selection (an engine switch can
        // invalidate the voice list). Attempt 1: default
        // construction + init, then setEngineByPackageName
        // (Google). If init fails/times out OR the lock
        // returns ERROR, that engine is shut down and attempt
        // 2 rebuilds with the DEFAULT engine (no engine
        // argument) and re-runs init. If both fail, today's
        // TTS_FALLBACK_FAILED path stands (visible failure,
        // never silent, never crash).
        var tts: TextToSpeech? = null
        var engineLabel: String? = null
        val attempt1 = buildTts(appContext)
        if (attempt1 != null) {
            val (candidate, initResult) = attempt1
            currentTts = candidate
            val status = awaitInit(initResult)
            if (status == TextToSpeech.SUCCESS) {
                val lockResult = runCatching {
                    candidate.setEngineByPackageName(GOOGLE_ENGINE_PACKAGE)
                }.getOrDefault(TextToSpeech.ERROR)
                if (lockResult == TextToSpeech.SUCCESS) {
                    tts = candidate
                    engineLabel = "google"
                    JarvisStateRepository.addDiagnostic(
                        "tts", "TTS_ENGINE_GOOGLE",
                        "System-TTS fallback engine locked to Google TTS."
                    )
                } else {
                    JarvisStateRepository.addDiagnostic(
                        "tts", "TTS_ENGINE_GOOGLE_FAILED",
                        "Google TTS engine lock refused (result=$lockResult) — falling back to default engine."
                    )
                    runCatching { candidate.shutdown() }
                    if (currentTts === candidate) currentTts = null
                }
            } else {
                JarvisStateRepository.addDiagnostic(
                    "tts", "TTS_ENGINE_GOOGLE_FAILED",
                    "Google-attempt init failed (status=$status, timedOut=${status == null}) — falling back to default engine."
                )
                runCatching { candidate.shutdown() }
                if (currentTts === candidate) currentTts = null
            }
        }
        if (tts == null) {
            val attempt2 = buildTts(appContext)
            if (attempt2 == null) {
                JarvisStateRepository.addDiagnostic(
                    "tts", "TTS_FALLBACK_FAILED",
                    "System-TTS fallback unavailable: TextToSpeech construction threw (both engine attempts)."
                )
                return false
            }
            val (candidate, initResult) = attempt2
            currentTts = candidate
            val status = awaitInit(initResult)
            if (status != TextToSpeech.SUCCESS) {
                JarvisStateRepository.addDiagnostic(
                    "tts", "TTS_FALLBACK_FAILED",
                    "System-TTS fallback init failed (status=$status, timedOut=${status == null}) — no system engine."
                )
                runCatching { candidate.shutdown() }
                if (currentTts === candidate) currentTts = null
                return false
            }
            tts = candidate
            engineLabel = "default"
            JarvisStateRepository.addDiagnostic(
                "tts", "TTS_ENGINE_DEFAULT_FALLBACK",
                "System-TTS fallback using default engine (Google lock unavailable)."
            )
        }
        val engine = tts ?: return false
        // Language + voice + rate/pitch ALL run AFTER the lock
        // succeeds (or after the default-engine fallback) —
        // never before, so the voice list belongs to the engine
        // that will actually speak.
        try {
            val languageUsed = pickLanguage(engine)
            // The cleanup's Roman→Devanagari step depends on
            // the picked language, so it runs AFTER pickLanguage
            // here: if the language is hi-IN, re-clean with the
            // Devanagari step enabled and re-split. The timeout
            // below uses this final cleaned length.
            val finalCleaned = cleanForFallback(text, languageUsed)
            val finalSentences = splitSentences(finalCleaned)
            if (finalSentences.isEmpty()) return false
            pickBestVoice(engine, languageUsed, online)
            // WAVE-2 (ADD 1d): fallback-only tuning, literals —
            // NEVER prefs.voicePitch/prefs.voiceSpeed, and never
            // applied to Swara (different object/path; Swara's
            // deep voice is Edge SSML prosody pitch='-12%'
            // rate='+0%' in SwaraTtsClient).
            engine.setSpeechRate(0.9f)
            engine.setPitch(1.0f)
            val baseId = "jarvis-fallback-${System.currentTimeMillis()}"
            val sentenceIds = finalSentences.indices.map { "$baseId-$it" }
            val lastId = sentenceIds.last()
            val done = CompletableDeferred<Boolean>()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // WAVE-2 (§D-8): fallback first audio ≈
                    // utterance start — upgrade PREPARING
                    // (set by speakWrapped) to SPEAKING here.
                    JarvisStateRepository.setPlaybackState(
                        com.jarvis.autonomous.data.PlaybackState.SPEAKING
                    )
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == lastId) done.complete(true)
                }

                override fun onError(utteranceId: String?) {
                    if (utteranceId == lastId) done.complete(false)
                }

                // 1.4.13 (F-E4): stop() must free the speak
                // coroutine promptly, not hang it to the timeout
                // — a barge-in cut during a fallback utterance
                // completes here as "not fully spoken" (false),
                // so the VM slot + FGS mutex release at once.
                // (minSdk 26, so onStop exists on every device
                // this build runs on; the timeout below stays
                // as the backstop if a platform skips it.)
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == lastId) done.complete(false)
                }
            })
            // WAVE-2 (ADD 1e): sequential sentence queueing —
            // first sentence QUEUE_FLUSH, rest QUEUE_ADD, each
            // with its own utterance id; done completes on the
            // LAST sentence's onDone/onError/onStop above.
            var firstResult = TextToSpeech.SUCCESS
            for ((index, sentence) in finalSentences.withIndex()) {
                val mode = if (index == 0) {
                    TextToSpeech.QUEUE_FLUSH
                } else {
                    TextToSpeech.QUEUE_ADD
                }
                val result = engine.speak(sentence, mode, null, sentenceIds[index])
                if (index == 0) firstResult = result
                if (result != TextToSpeech.SUCCESS) {
                    JarvisStateRepository.addDiagnostic(
                        "tts", "TTS_FALLBACK_FAILED",
                        "System-TTS fallback sentence speak() refused (index=$index, result=$result)."
                    )
                }
            }
            if (firstResult != TextToSpeech.SUCCESS) {
                return false
            }
            // Bound: ~15 chars/sec + 30 s base, capped at 180 s,
            // on the CLEANED length (see above).
            val timeoutMs = minOf(180_000L, 30_000L + finalCleaned.length * 100L)
            val finished = withTimeoutOrNull(timeoutMs) { done.await() } ?: false
            if (!finished) {
                runCatching { engine.stop() }
                JarvisStateRepository.addDiagnostic(
                    "tts", "TTS_FALLBACK_FAILED",
                    "System-TTS fallback timed out after ${timeoutMs}ms — stopped."
                )
                return false
            }
            JarvisStateRepository.addDiagnostic(
                "tts", "TTS_FALLBACK_ENDED",
                "System-TTS fallback finished (language=$languageUsed, engine=$engineLabel, sentences=${finalSentences.size})."
            )
            return true
        } finally {
            if (currentTts === engine) currentTts = null
            runCatching { engine.shutdown() }
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

    /**
     * WAVE-2 (ADD 1b): quality-ranked voice pick, REPLACING
     * pickFemaleVoice. (1) Allowed set = voices filtered to
     * the chosen language AND by the network rule (ADD 1c):
     * offline → network-required voices excluded entirely
     * (they cannot play offline — including them risks
     * silence); online → they enter and compete purely on
     * quality. The filter runs BEFORE the rank, never after.
     * (2) Rank by Voice.quality descending: VERY_HIGH (500) >
     * HIGH (400) > NORMAL (300) > LOW (200) > VERY_LOW (100);
     * missing/0 quality sorts lowest, never first (raw int
     * descending does exactly this). (3) Tiebreak ONLY among
     * equal-quality voices: prefer a name containing "female"
     * (case-insensitive). Female is NEVER a gate: with no
     * female voice at any quality, the best-quality voice wins
     * regardless of name. (4) Empty allowed set → NO voice is
     * set (engine default) rather than a wrong-language or
     * unplayable voice; any pick failure → engine default
     * (today's behaviour when pickFemaleVoice found nothing).
     */
    private fun pickBestVoice(tts: TextToSpeech, languageUsed: String, online: Boolean) {
        runCatching {
            val locale = when (languageUsed) {
                "hi-IN" -> Locale("hi", "IN")
                "en-IN" -> Locale("en", "IN")
                "en" -> Locale.ENGLISH
                else -> return
            }
            val voices: Set<Voice> = tts.voices ?: return
            val allowed = voices.filter { voice ->
                voice.locale == locale &&
                    (online || !voice.isNetworkConnectionRequired)
            }
            if (allowed.isEmpty()) return
            val best = allowed.sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenByDescending { it.name.contains("female", ignoreCase = true) }
            ).first()
            tts.voice = best
        }
    }

    /**
     * WAVE-2 (ADD 1c): online computed ONCE per speak() from
     * appContext — the codebase's exact pattern:
     * ConnectivityManager.activeNetwork + NetworkCapabilities
     * (JarvisAgentEngine.isOnline(): Wi-Fi/cellular/Ethernet
     * transport; AlwaysOnMicManager.hasNetwork():
     * NET_CAPABILITY_INTERNET). Union of both readings: either
     * transport or the internet capability counts as online.
     * A throw or no network → OFFLINE (safe reading: a failed
     * check must never assume online).
     */
    private fun computeOnline(appContext: Context): Boolean {
        return runCatching {
            val cm = appContext.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as? ConnectivityManager ?: return@runCatching false
            val net = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(net) ?: return@runCatching false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
    }

    private val ONES = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven",
        "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"
    )
    private val TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty",
        "sixty", "seventy", "eighty", "ninety"
    )

    /** 0–100 → words; anything else → digits as-is (engine reads them). */
    private fun intToWord(value: Int): String {
        return when {
            value < 0 -> value.toString()
            value < 20 -> ONES[value]
            value < 100 -> {
                val ten = TENS[value / 10]
                val one = value % 10
                if (one == 0) ten else "$ten ${ONES[one]}"
            }
            value == 100 -> "one hundred"
            else -> value.toString()
        }
    }

    /**
     * WAVE-2 (ADD 1e): fallback-ONLY text cleanup. Swara's
     * cleanText/xmlEscape and the Swara text path are untouched.
     * (1) Sentence splitting happens in [splitSentences] on
     * [.!?।\n] boundaries. (2) Bounded expansions, word-boundary
     * ONLY (never substring replacements inside words):
     * symbols % → " percent ", ₹ → " rupees ", & → " aur ";
     * integers 0–100 → words; decimals (incl. multi-dot
     * numbers) → segments joined by " point "; larger integers
     * stay digits; abbreviations Dr./Mr./Mrs./No./vs./etc. and
     * units km/kg/min/sec → words (case-insensitive).
     * (3) Roman→Devanagari BOUNDED: the inverted
     * TurnDetector map (Latin → Devanagari, single tokens
     * only, fillers excluded), word-boundary, ONLY when
     * languageUsed == "hi-IN". Everything outside that set
     * stays Roman (the hi-IN engine reads Roman Hinglish
     * adequately; guessing conversions would worsen
     * pronunciation). Full phonetic transliteration is OUT of
     * scope (needs a new engine/dependency) — a reasoned
     * limitation, not a silent drop: unknown Roman words are
     * spoken as-is by the engine.
     */
    private fun cleanForFallback(text: String, languageUsed: String): String {
        var out = text
        // Symbols (not word characters, so a plain replace is
        // safe — it cannot eat a substring inside a word).
        out = out.replace("%", " percent ")
            .replace("₹", " rupees ")
            .replace("&", " aur ")
        // Abbreviations (with dot; case-insensitive, word-boundary).
        val abbreviations = listOf(
            Regex("""\bMrs\.""", RegexOption.IGNORE_CASE) to "Missus",
            Regex("""\bMr\.""", RegexOption.IGNORE_CASE) to "Mister",
            Regex("""\bDr\.""", RegexOption.IGNORE_CASE) to "Doctor",
            Regex("""\bNo\.""", RegexOption.IGNORE_CASE) to "Number",
            Regex("""\bvs\.""", RegexOption.IGNORE_CASE) to "versus",
            Regex("""\betc\.""", RegexOption.IGNORE_CASE) to "etcetera"
        )
        for ((regex, replacement) in abbreviations) {
            out = regex.replace(out, replacement)
        }
        // Decimals / multi-dot numbers first (so the integer
        // pass below cannot split them): "3.14" → "three point
        // one four"; a >100 segment stays digits ("1.250" →
        // "one point 250" is avoided — segments use intToWord,
        // which leaves >100 as digits).
        out = Regex("""\b\d+(?:\.\d+)+\b""").replace(out) { match ->
            match.value.split(".").joinToString(" point ") { part ->
                part.toIntOrNull()?.let { intToWord(it) } ?: part
            }
        }
        // Standalone integers 0–100 → words; larger stay digits.
        out = Regex("""\b\d+\b""").replace(out) { match ->
            match.value.toIntOrNull()?.let { intToWord(it) } ?: match.value
        }
        // Units (word-boundary, case-insensitive).
        val units = listOf(
            Regex("""\bkm\b""", RegexOption.IGNORE_CASE) to "kilometre",
            Regex("""\bkg\b""", RegexOption.IGNORE_CASE) to "kilogram",
            Regex("""\bmin\b""", RegexOption.IGNORE_CASE) to "minute",
            Regex("""\bsec\b""", RegexOption.IGNORE_CASE) to "second"
        )
        for ((regex, replacement) in units) {
            out = regex.replace(out, replacement)
        }
        // Bounded Roman→Devanagari, hi-IN only.
        if (languageUsed == "hi-IN") {
            for ((latin, devanagari) in TurnDetector.latinToDevanagariKeywords()) {
                val regex = Regex(
                    """\b${Regex.escape(latin)}\b""",
                    RegexOption.IGNORE_CASE
                )
                out = regex.replace(out, devanagari)
            }
        }
        return out.replace(Regex("""\s+"""), " ").trim()
    }

    /** Split on [.!?।\n] boundaries; empty pieces dropped. */
    private fun splitSentences(cleaned: String): List<String> {
        if (cleaned.isBlank()) return emptyList()
        val parts = cleaned.split(Regex("""[.!?।\n]+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.isEmpty()) listOf(cleaned) else parts
    }
}
