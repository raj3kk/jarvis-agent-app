package com.jarvis.autonomous.voice

/**
 * S1 — Mic + turn detection (JarvisAgentApp 1.4.0).
 *
 * Turn end is decided HERE, by our own silence + completeness logic —
 * NEVER by the recognizer's onEndOfSpeech / onResults signal. The old
 * bug was exactly that: the system recognizer self-stops on a short
 * pause ("halt"), so Jarvis acted on a partial command. This class
 * only ever sees timestamps and text; it knows nothing about Android
 * recognizers, so it is unit-testable without a device.
 *
 * Thresholds (chosen per the S1 brief, all in milliseconds):
 * - SHORT_SILENCE_MS (1000): a pause shorter than this is just a
 *   breath / "halt" — always keep waiting, never end the turn.
 * - TURN_END_SILENCE_MS (1750, inside the 1.5–2s band): silence this
 *   long after the last partial AND a sentence that looks complete
 *   ends the turn.
 * - INCOMPLETE_EXTRA_WAIT_MS (5000): after an "incomplete" signal,
 *   the caller speaks "aur bolo, poora sun raha hoon" and waits this
 *   long for continuation. If nothing more arrives, the buffered
 *   text is emitted as a Complete turn anyway — the mic loop must
 *   never hang forever on a fragment.
 *
 * Completeness is a heuristic, not a parser, and it is deliberately
 * conservative: a false "incomplete" costs one prompt; a false
 * "complete" costs acting on half a command. The 5s fallback bounds
 * the cost of either mistake.
 */
object TurnThresholds {
    const val SHORT_SILENCE_MS: Long = 1_000L
    const val TURN_END_SILENCE_MS: Long = 1_750L
    const val INCOMPLETE_EXTRA_WAIT_MS: Long = 5_000L
    const val TURN_CHECK_INTERVAL_MS: Long = 250L
    /** A-M5: a turn never runs longer than this, whatever its state. */
    const val MAX_TURN_DURATION_MS: Long = 60_000L
    /** A-M5: buffered transcript cap (head kept, tail dropped). */
    const val MAX_BUFFER_CHARS: Int = 2_000
    /** A-M5: long silence completes a turn even while an Incomplete
     * prompt is pending (replaces the old 15s wall-clock stall). */
    const val INCOMPLETE_LONG_SILENCE_MS: Long = 5_000L
}

/** Result of [TurnDetector.check]. Only one value per check call. */
sealed interface TurnOutcome {
    /** The turn is over; [text] is the full utterance to act on. */
    data class Complete(val text: String) : TurnOutcome

    /**
     * The utterance looks unfinished. The caller should speak the
     * "aur bolo" prompt, then call [TurnDetector.markIncompletePromptSent].
     * This outcome is emitted at most once per turn.
     */
    data class Incomplete(val text: String, val reason: String) : TurnOutcome

    /** Keep listening; no decision yet. */
    data object Waiting : TurnOutcome
}

class TurnDetector {

    // ---- State: every field is declared above every function that
    // uses it (kotlin_prepush_checks: declaration order). ----
    private var buffer: String = ""
    private var lastPartialAtMs: Long = 0L
    private var turnActive: Boolean = false
    private var incompletePromptSent: Boolean = false
    private var continuationDeadlineMs: Long = 0L
    private var incompleteEmitted: Boolean = false
    private var incompleteEmittedAtMs: Long = 0L
    private var turnStartedAtMs: Long = 0L

    /** Current buffered transcript (trimmed), even mid-turn. */
    fun currentText(): String = buffer.trim()

    /** True once any speech text has arrived for the current turn. */
    fun hasText(): Boolean = buffer.isNotBlank()

    /**
     * A new partial result arrived. System partials are cumulative
     * WITHIN one recognizer session, so a same-session partial
     * REPLACES the buffer; a new-session partial (the recognizer
     * restarted after a pause) is a continuation and is APPENDED
     * unless it already starts with the buffer. [isNewSession]
     * tells the two cases apart — see AlwaysOnMicManager.
     */
    fun onPartial(text: String, nowMs: Long, isNewSession: Boolean) {
        val incoming = text.trim()
        if (incoming.isEmpty()) return
        if (!turnActive) {
            turnStartedAtMs = nowMs
        }
        buffer = if (!turnActive || buffer.isBlank()) {
            incoming
        } else if (isNewSession && !incoming.startsWith(buffer)) {
            "$buffer $incoming"
        } else {
            incoming
        }
        // A-M5 buffer cap: keep the head of the utterance, drop the
        // tail past the cap — never grow unbounded on a long ramble.
        if (buffer.length > TurnThresholds.MAX_BUFFER_CHARS) {
            buffer = buffer.take(TurnThresholds.MAX_BUFFER_CHARS)
        }
        lastPartialAtMs = nowMs
        turnActive = true
    }

    /** Speech started (recognizer onBeginningOfSpeech). */
    fun onSpeechStarted(nowMs: Long) {
        if (!turnActive) {
            lastPartialAtMs = nowMs
            turnStartedAtMs = nowMs
        }
        turnActive = true
    }

    /**
     * A final result arrived. This does NOT end the turn — the
     * recognizer ending is not our turn signal. It only refreshes
     * the buffer and the silence clock; [check] still decides.
     */
    fun onFinal(text: String, nowMs: Long, isNewSession: Boolean) {
        onPartial(text, nowMs, isNewSession)
    }

    /**
     * Decide the turn state at [nowMs]. Callers poll this about
     * every [TurnThresholds.TURN_CHECK_INTERVAL_MS] while listening.
     */
    fun check(nowMs: Long): TurnOutcome {
        if (!turnActive || buffer.isBlank()) return TurnOutcome.Waiting
        val text = buffer.trim()
        val silenceMs = nowMs - lastPartialAtMs
        if (silenceMs < TurnThresholds.SHORT_SILENCE_MS) {
            return TurnOutcome.Waiting
        }
        // Mic-stop phrases complete the turn even when they look
        // like fragments ("mic off" has no verb): the user is not
        // mid-phrase, they are switching the mic off.
        if (isMicStopCommand(text)) {
            return TurnOutcome.Complete(text)
        }
        // A-M5 max turn duration: complete the buffered text
        // whatever the turn state — a turn never stalls forever.
        if (turnStartedAtMs > 0L &&
            nowMs - turnStartedAtMs >= TurnThresholds.MAX_TURN_DURATION_MS
        ) {
            return TurnOutcome.Complete(text)
        }
        // A-M5: if Incomplete was emitted but the caller never
        // called markIncompletePromptSent, complete on LONG
        // SILENCE (not a 15s wall-clock stall) — the user has
        // stopped talking, so the buffered text is the turn.
        if (incompleteEmitted && !incompletePromptSent) {
            if (silenceMs >= TurnThresholds.INCOMPLETE_LONG_SILENCE_MS) {
                return TurnOutcome.Complete(text)
            }
            return TurnOutcome.Waiting
        }
        if (incompletePromptSent) {
            // A-M5: long silence completes even while the
            // Incomplete prompt is pending and the text still
            // looks like a fragment — the user is not continuing.
            if (silenceMs >= TurnThresholds.INCOMPLETE_LONG_SILENCE_MS) {
                return TurnOutcome.Complete(text)
            }
            // After the "aur bolo" prompt: if the continuation now
            // looks complete and has had its own TURN_END silence,
            // end immediately — do not make the user wait out the
            // whole 5s window. The deadline is only the hard stop
            // when nothing (or only more fragments) arrives.
            if (incompleteReason(text) == null && silenceMs >= TurnThresholds.TURN_END_SILENCE_MS) {
                return TurnOutcome.Complete(text)
            }
            if (nowMs >= continuationDeadlineMs) {
                return TurnOutcome.Complete(text)
            }
            return TurnOutcome.Waiting
        }
        if (silenceMs < TurnThresholds.TURN_END_SILENCE_MS) {
            return TurnOutcome.Waiting
        }
        val reason = incompleteReason(text)
        if (reason != null) {
            incompleteEmitted = true
            incompleteEmittedAtMs = nowMs
            return TurnOutcome.Incomplete(text, reason)
        }
        return TurnOutcome.Complete(text)
    }

    /**
     * Call this AFTER the caller has spoken the "aur bolo, poora
     * sun raha hoon" prompt. Starts the ~5s continuation window;
     * [check] returns Complete (never a second Incomplete) once
     * the window expires, so a silent user still gets an answer.
     */
    fun markIncompletePromptSent(nowMs: Long) {
        incompletePromptSent = true
        continuationDeadlineMs = nowMs + TurnThresholds.INCOMPLETE_EXTRA_WAIT_MS
    }

    /** Clears all turn state for the next utterance. */
    fun reset() {
        buffer = ""
        lastPartialAtMs = 0L
        turnActive = false
        incompletePromptSent = false
        continuationDeadlineMs = 0L
        incompleteEmitted = false
        incompleteEmittedAtMs = 0L
        turnStartedAtMs = 0L
    }

    /**
     * Why [text] looks incomplete, or null if it looks complete.
     * Rules, in order:
     * 1. Ends with a conjunction / preposition / subordinating word
     *    (Hinglish + English) — the sentence is mid-phrase.
     * 2. Two or more words, no verb token, and no question word —
     *    a fragment ("youtube par gaana") rather than a command or
     *    a question. Single words are NOT flagged here: "haan",
     *    "ruk", "bas" and greetings are complete replies (see
     *    COMPLETE_SINGLE_WORDS). Known question words ("kya",
     *    "kitne"...) also complete the turn without a verb.
     */
    fun incompleteReason(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val tokens = trimmed.lowercase()
            .replace("?", " ")
            .replace("।", " ")
            .split(" ", ",", ";", ":")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        if (tokens.size == 1 && tokens[0] in COMPLETE_SINGLE_WORDS) return null
        val last = tokens.last()
        if (last in TRAILING_TOKENS) {
            return "ends with '$last' (mid-phrase)"
        }
        if (tokens.size >= 2 &&
            tokens.none { it in VERB_TOKENS } &&
            tokens.none { it in QUESTION_TOKENS }
        ) {
            return "no verb/object (fragment)"
        }
        return null
    }

    companion object {
        /**
         * The ONLY phrases that switch the mic loop itself off.
         * Checked against the final transcript BEFORE it is emitted
         * as a turn, so "mic band karo" never reaches the engine.
         * Note the deliberate difference from the engine's task-stop
         * words ("ruk", "bas", "stop"): those stop a TASK, not the
         * mic. During TTS, "ruk"/"bas" is a barge-in (see
         * BargeInDetector), not a mic stop.
         */
        val MIC_STOP_PHRASES: List<String> = listOf(
            "mic band karo",
            "mic band",
            "mic off",
            "microphone band karo",
            "mic stop karo"
        )

        /**
         * A-M4: mic-stop phrases, normalised — punctuation stripped,
         * filler words dropped, trailing "kar do" / "karo" allowed,
         * so "mic band kar do please", "please mic band karo" and
         * "mic band, yaar" all stop the mic. A phrase only counts
         * when the whole (normalised) utterance is the phrase plus
         * at most trailing action words — never a substring of a
         * longer command.
         */
        fun isMicStopCommand(text: String): Boolean {
            val tokens = normalizeTokens(text)
            if (tokens.isEmpty()) return false
            val joined = tokens.joinToString(" ")
            if (joined in MIC_STOP_PHRASES) return true
            for (phrase in MIC_STOP_PHRASES) {
                if (joined.startsWith("$phrase ")) {
                    val rest = tokens.drop(phrase.split(" ").size)
                    if (rest.all { it in ALLOWED_STOP_TRAILING }) return true
                }
            }
            return false
        }

        /** Lowercase, punctuation -> space, fillers dropped. */
        fun normalizeTokens(text: String): List<String> {
            return text.lowercase()
                .replace(Regex("[.,!?;:'\"()\\[\\]।]+"), " ")
                .split(" ")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it !in STOP_FILLERS }
        }

        private val STOP_FILLERS: Set<String> = setOf(
            "please", "pls", "yaar", "bhai", "zara", "kripya", "thoda"
        )

        private val ALLOWED_STOP_TRAILING: Set<String> = setOf(
            "kar", "do", "karo", "karna"
        )

        private val TRAILING_TOKENS: Set<String> = setOf(
            // Hindi conjunctions / postpositions / subordinators
            "aur", "lekin", "par", "pe", "me", "mein", "se", "ka",
            "ki", "ke", "ko", "tak", "jab", "agar", "phir", "fir",
            "isliye", "ya", "toh", "to",
            // English conjunctions / prepositions
            "and", "or", "but", "with", "for", "of", "to", "on",
            "in", "at", "then", "when", "if"
        )

        private val VERB_TOKENS: Set<String> = setOf(
            // Hindi verbs (imperative / common forms)
            "karo", "kar", "karna", "chalao", "chala", "bajao",
            "sunao", "dikhao", "dikh", "bhejo", "bhej", "kholo",
            "khol", "batao", "bata", "lagao", "laga", "band",
            "roko", "ruk", "dekho", "dekh", "likho", "banao",
            "dhoondho", "khelo", "uthao", "bhejdo", "chalu",
            // English verbs
            "play", "send", "open", "call", "set", "turn",
            "show", "tell", "start", "stop", "search", "find",
            "do", "make", "write", "create", "give", "bring"
        )

        private val QUESTION_TOKENS: Set<String> = setOf(
            "kya", "kitne", "kitna", "kab", "kahan", "kaha",
            "kaun", "kis", "kyun", "kaise", "konsa", "kaunsi",
            "time", "date", "mausam", "weather", "what",
            "when", "where", "who", "how", "which"
        )

        private val COMPLETE_SINGLE_WORDS: Set<String> = setOf(
            "haan", "yes", "nahi", "no", "ruk", "ruko", "bas",
            "stop", "hello", "hi", "ok", "okay", "theek",
            "acha", "accha", "done", "thanks", "shukriya"
        )
    }
}

/**
 * Barge-in during TTS playback (S1 echo amendment).
 *
 * While Jarvis is speaking, the mic still hears — including Jarvis's
 * own voice through the speaker. A barge-in is therefore NOT "any
 * speech during TTS". It is a loud, user-initiated stop keyword at
 * the START of the transcript, with an echo guard:
 * - the transcript must START with a stop keyword ("ruk", "ruko",
 *   "bas", "chup", "stop", "ruk ja") — an interrupting user starts
 *   with the stop word; Jarvis's own speech rarely does;
 * - if the text Jarvis is currently speaking itself contains that
 *   keyword (e.g. Jarvis saying "ruk gaya"), the transcript must
 *   start with the keyword while the spoken text does NOT — otherwise
 *   Jarvis would interrupt himself on his own echo;
 * - the keyword must also be louder than the listening baseline by
 *   [LOUD_MARGIN_DB] on KNOWN RMS values; unknown RMS never passes
 *   (B-C3) — the manager freezes RMS while speaking, so playback
 *   can never inflate the compared level. This is a best-effort
 *   heuristic, not a guarantee:
 *   a quiet user on a loud speaker can still be missed, and a loud
 *   echo that starts with "ruk" can still false-trigger. The
 *   remainder after the keyword is kept, so "ruk, youtube band karo"
 *   still yields the follow-up command.
 */
object BargeInDetector {

    const val LOUD_MARGIN_DB: Float = 3.0f

    private val LEADING_KEYWORDS: Set<String> = setOf(
        "ruk", "ruko", "bas", "chup", "stop"
    )

    /**
     * A-M3 + B-C3: a barge-in is ONLY a FINAL transcript (the
     * caller must never pass partials here) that starts with a
     * stop keyword, passes the echo guard against the FULL spoken
     * text, and passes a KNOWN loud gate. Unknown RMS never
     * passes: the old "keyword alone decides when RMS is
     * unknown" path let Jarvis's own playback barge-in on itself.
     * The manager freezes RMS while speaking (see
     * AlwaysOnMicManager.onRmsChanged), so a playback-dominated
     * RMS can never satisfy this gate — only the pre-TTS user
     * level is ever compared.
     */
    fun isBargeIn(
        transcript: String,
        isSpeaking: Boolean,
        spokenText: String,
        rmsDb: Float?,
        rmsBaselineDb: Float?
    ): Boolean {
        if (!isSpeaking) return false
        val tokens = TurnDetector.normalizeTokens(transcript)
        if (tokens.isEmpty()) return false
        val first = tokens[0]
        val keywordLen = when {
            first in LEADING_KEYWORDS -> 1
            first == "ruk" && tokens.size > 1 && tokens[1] == "ja" -> 2
            else -> return false
        }
        val keyword = tokens.take(keywordLen).joinToString(" ")
        val remainderTokens = tokens.drop(keywordLen)
        // Echo guard against the FULL spoken text (not just its
        // start): if Jarvis's own words contain this transcript,
        // it is echo, not the user. A bare keyword is also echo
        // when Jarvis's text contains that keyword anywhere as a
        // token ("ruk gaya" echo must not barge-in). A keyword +
        // follow-up command is only echo when the whole
        // transcript sits inside Jarvis's spoken text.
        val spokenTokens = TurnDetector.normalizeTokens(spokenText)
        val spokenJoined = spokenTokens.joinToString(" ")
        val transcriptJoined = tokens.joinToString(" ")
        if (spokenJoined.isNotEmpty() && spokenJoined.contains(transcriptJoined)) return false
        if (remainderTokens.isEmpty() && keyword in spokenTokens) return false
        // Loud gate: KNOWN values only, never skipped on unknown.
        if (rmsDb == null || rmsBaselineDb == null) return false
        if (rmsDb < rmsBaselineDb + LOUD_MARGIN_DB) return false
        return true
    }

    /** Text after the leading stop keyword(s), or "" if none. */
    fun remainderAfterKeyword(transcript: String): String {
        val tokens = TurnDetector.normalizeTokens(transcript)
        if (tokens.isEmpty()) return ""
        val first = tokens[0]
        if (first in LEADING_KEYWORDS) {
            return tokens.drop(1).joinToString(" ").trim()
        }
        if (first == "ruk" && tokens.size > 1 && tokens[1] == "ja") {
            return tokens.drop(2).joinToString(" ").trim()
        }
        return tokens.joinToString(" ").trim()
    }
}
