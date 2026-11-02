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
        buffer = if (!turnActive || buffer.isBlank()) {
            incoming
        } else if (isNewSession && !incoming.startsWith(buffer)) {
            "$buffer $incoming"
        } else {
            incoming
        }
        lastPartialAtMs = nowMs
        turnActive = true
    }

    /** Speech started (recognizer onBeginningOfSpeech). */
    fun onSpeechStarted(nowMs: Long) {
        if (!turnActive) {
            lastPartialAtMs = nowMs
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
        // Safety net: if Incomplete was emitted but the caller
        // never called markIncompletePromptSent (e.g. it spoke the
        // prompt through another path), do not wait forever — the
        // buffered text completes 15s after the Incomplete signal.
        if (incompleteEmitted && !incompletePromptSent &&
            nowMs - incompleteEmittedAtMs >= INCOMPLETE_EMITTED_SAFETY_MS
        ) {
            return TurnOutcome.Complete(text)
        }
        if (incompleteEmitted && !incompletePromptSent) {
            // Incomplete already delivered once; wait for the
            // caller to speak the prompt and mark it sent.
            return TurnOutcome.Waiting
        }
        if (incompletePromptSent) {
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
        /** Safety net when the caller never marks the prompt sent. */
        const val INCOMPLETE_EMITTED_SAFETY_MS: Long = 15_000L

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

        fun isMicStopCommand(text: String): Boolean {
            val normalized = text.trim().lowercase()
                .replace("?", "")
                .replace("।", "")
                .trim()
            return normalized in MIC_STOP_PHRASES
        }

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
 * - when RMS levels are available, the keyword must also be louder
 *   than the listening baseline by [LOUD_MARGIN_DB]; when RMS is
 *   unavailable (some recognizer paths), the keyword + echo guard
 *   alone decides. This is a best-effort heuristic, not a guarantee:
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

    fun isBargeIn(
        transcript: String,
        isSpeaking: Boolean,
        spokenText: String,
        rmsDb: Float?,
        rmsBaselineDb: Float?
    ): Boolean {
        if (!isSpeaking) return false
        val trimmed = transcript.trim().lowercase()
        if (trimmed.isEmpty()) return false
        val startsWithKeyword = LEADING_KEYWORDS.any { trimmed == it || trimmed.startsWith("$it ") } ||
            trimmed.startsWith("ruk ja ") || trimmed == "ruk ja"
        if (!startsWithKeyword) return false
        // Echo guard: if Jarvis's own current text contains a stop
        // keyword, only accept when Jarvis did NOT start with one.
        val spoken = spokenText.trim().lowercase()
        val spokenStartsWithKeyword = LEADING_KEYWORDS.any { spoken == it || spoken.startsWith("$it ") } ||
            spoken.startsWith("ruk ja ")
        if (spokenStartsWithKeyword) return false
        // Loud gate, only when both levels are known.
        if (rmsDb != null && rmsBaselineDb != null) {
            if (rmsDb < rmsBaselineDb + LOUD_MARGIN_DB) return false
        }
        return true
    }

    /** Text after the leading stop keyword(s), or "" if none. */
    fun remainderAfterKeyword(transcript: String): String {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) return ""
        val tokens = trimmed.split(" ").filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        val first = tokens[0].lowercase()
        if (first in LEADING_KEYWORDS) {
            // "ruk ja ..." is a two-token keyword.
            if (first == "ruk" && tokens.size > 1 && tokens[1].lowercase() == "ja") {
                return tokens.drop(2).joinToString(" ").trim()
            }
            return tokens.drop(1).joinToString(" ").trim()
        }
        return trimmed
    }
}
