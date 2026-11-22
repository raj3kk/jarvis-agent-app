package com.jarvis.autonomous.agent

/**
 * Learned-pattern matching, scoring, confidence and extraction
 * filter (v2 Gap 3).
 *
 * DESIGN.md: expansion/talker-executor-v2/DESIGN.md §5.2–§5.6.
 * Release 1.9.0-PRO (vc 45). Pure Kotlin — ZERO Android imports.
 *
 * Scoring deliberately mirrors `DocumentSearch.scoreHit` (one
 * scoring idiom in the codebase): +1 per distinct query keyword in
 * the pattern's trigger keywords, +2 when the query's work kind
 * equals the pattern's, +1 when the query phrase contains the
 * trigger signature as a substring.
 *
 * Confidence: starts 0.5 on first verified DONE, +0.1 per verified
 * reuse (cap 0.9), −0.2 per failure-after-apply (floor 0.2).
 * A pattern with 2 consecutive failures-after-apply is
 * auto-disabled (kept on disk so the user can re-enable it by
 * voice — "wo tareeka phir se use karo").
 */
object LearnedPatternPolicy {

    /** Apply a pattern only when score ≥ 3 AND confidence ≥ 0.5 AND enabled. */
    const val MATCH_MIN_SCORE = 3

    /** Instructions are user-taught, so their bar is lower than a learned pattern's. */
    const val INSTRUCTION_MIN_SCORE = 2

    /** Minimum confidence for a pattern/instruction to be applied. */
    const val APPLY_MIN_CONFIDENCE = 0.5

    /** A matching enabled pattern with score ≥ 5 is updated, not re-minted. */
    const val DUPLICATE_MIN_SCORE = 5

    /** At most one pattern and two instructions per command. */
    const val MAX_PATTERNS_PER_COMMAND = 1
    const val MAX_INSTRUCTIONS_PER_COMMAND = 2

    /** Confidence bounds / steps (DESIGN §5.2). */
    const val START_CONFIDENCE = 0.5
    const val CONFIDENCE_STEP_UP = 0.1
    const val CONFIDENCE_STEP_DOWN = 0.2
    const val CONFIDENCE_MAX = 0.9
    const val CONFIDENCE_MIN = 0.2

    /** Consecutive failures-after-apply that auto-disable a pattern. */
    const val AUTO_DISABLE_FAILURES = 2

    /** Max step labels per pattern; max keywords per pattern/instruction. */
    const val MAX_PATTERN_STEPS = 8
    const val MAX_KEYWORDS = 20

    /** Guidance strings injected into Executor params, capped (DESIGN §5.3). */
    const val MAX_GUIDANCE_CHARS = 500

    /**
     * Match score for one pattern against one query.
     *
     * @param queryKeywords distinct significant keywords of the goal.
     * @param patternKeywords the pattern's trigger keywords.
     * @param workKindMatches true when the query's work kind equals the pattern's.
     * @param phraseContainsSignature true when the lowercased goal
     *   contains the pattern's trigger signature as a substring.
     */
    fun matchScore(
        queryKeywords: List<String>,
        patternKeywords: List<String>,
        workKindMatches: Boolean,
        phraseContainsSignature: Boolean
    ): Int {
        var score = 0
        val patternSet = patternKeywords.toSet()
        for (kw in queryKeywords.distinct()) {
            if (kw in patternSet) score += 1
        }
        if (workKindMatches) score += 2
        if (phraseContainsSignature) score += 1
        return score
    }

    /** Confidence after one verified reuse (cap 0.9). */
    fun confidenceAfterSuccess(confidence: Double): Double =
        (confidence + CONFIDENCE_STEP_UP).coerceAtMost(CONFIDENCE_MAX)

    /**
     * Confidence after one failure-after-apply (floor 0.2). Returns
     * the new confidence and whether the pattern must auto-disable
     * (2 consecutive failures).
     */
    fun confidenceAfterFailure(
        confidence: Double,
        consecutiveFailuresBefore: Int
    ): Pair<Double, Boolean> {
        val next = (confidence - CONFIDENCE_STEP_DOWN).coerceAtLeast(CONFIDENCE_MIN)
        val consecutive = consecutiveFailuresBefore + 1
        return next to (consecutive >= AUTO_DISABLE_FAILURES)
    }

    // ---- Extraction filter (DESIGN §5.6) ----

    private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val PHONE_REGEX = Regex("(\\+?91[\\s\\-]?)?[6-9]\\d{9}")
    private val LONG_DIGITS_REGEX = Regex("\\d{6,}")

    /**
     * True when a value must never be learned: email, phone, OTP /
     * password-shaped value, a URL carrying a query string / id /
     * token, a message body, file contents, a contact name, a
     * per-task project/repo name, coordinates, or a payment string.
     * Mirrors `LearnedRoutineStore.looksSensitive` plus a
     * URL-with-`?`/`#` check — the two filters must stay in sync
     * (a value rejected by one must be rejected by the other).
     */
    fun isSensitiveValue(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length > 200) return true
        if (EMAIL_REGEX.containsMatchIn(trimmed)) return true
        if (PHONE_REGEX.containsMatchIn(trimmed)) return true
        if (trimmed.contains("http")) {
            if (trimmed.contains("?") || trimmed.contains("#") || trimmed.contains("=")) return true
            if (LONG_DIGITS_REGEX.containsMatchIn(trimmed)) return true
        }
        // OTP / password-shaped: a bare 4–8 digit code, or a
        // "password/otp/pin" label carrying a value.
        if (Regex("^\\d{4,8}$").matches(trimmed)) return true
        val lower = trimmed.lowercase()
        if (lower.contains("password") || lower.contains("otp") || lower.contains("pincode")) return true
        return false
    }

    /**
     * A step label is learnable only when it is a plan step title or
     * a loop action name from the safe subset — never a value. The
     * allowlist is structural: labels come from the plan's own
     * titles (already user-visible, no PII by construction) or the
     * loop's recorded action names.
     */
    fun isLearnableStepLabel(label: String, kind: String): Boolean {
        val cleanLabel = label.trim()
        if (cleanLabel.isBlank() || cleanLabel.length > 80) return false
        if (isSensitiveValue(cleanLabel)) return false
        if (isSensitiveValue(kind)) return false
        // A label that IS a project/repo name for this task (a bare
        // slug with no verb) is a value, not knowledge — the planner
        // already asks for names when they are missing. Generic
        // single-word step words ("Build", "Deploy") are knowledge,
        // not names, so they pass.
        val genericSingleWords = setOf(
            "build", "deploy", "push", "check", "verify", "test",
            "create", "website", "preview", "upload", "download",
            "send", "open", "setup", "publish"
        )
        if (Regex("^[a-z0-9][a-z0-9\\-]{2,59}$").matches(cleanLabel.lowercase()) &&
            !cleanLabel.contains(" ") &&
            cleanLabel.lowercase() !in genericSingleWords
        ) {
            return false
        }
        return true
    }

    /** Failure/fix strings are built from the fixed reason map + step
     *  labels only — never from free-text Executor output. */
    fun isLearnableNote(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed.length > 200) return false
        return !isSensitiveValue(trimmed)
    }

    /** An instruction is learnable when it is short user-taught text
     *  with no sensitive value in it. */
    fun isLearnableInstruction(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed.length > 280) return false
        return !isSensitiveValue(trimmed)
    }
}
