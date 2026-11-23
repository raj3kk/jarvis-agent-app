package com.jarvis.autonomous.agent

/**
 * Talker router: classifies ONE user turn before any LLM call, any
 * network, or any phone action.
 *
 * DESIGN: ~/workspace/omnijarvis-implementation/expansion/talker-executor/DESIGN.md §1–§2, §7
 * Release 1.8.0-PRO (vc 44). Pure Kotlin — ZERO Android imports — so
 * this file unit-tests on a plain JVM.
 *
 * Deterministic by design: a classifier that needs the cloud to decide
 * whether to call the cloud has already lost the latency budget. An
 * ambiguous turn classifies TALK and falls through to today's cloud
 * loop, which is the safe direction (a chat reply instead of a wrong
 * action — never the reverse).
 *
 * Ordering matters: CANCEL first (a stop must win over everything),
 * then STATUS_QUERY (only meaningful while work runs), then EXECUTE,
 * else TALK. Single-step deterministic routines (YouTube / WhatsApp /
 * torch / volume / media / time-date) are NOT routed here — the engine
 * runs them on the Talker fast path before classification, because
 * they complete in <1 s and need no background worker (DESIGN.md §1.1).
 */
object TalkerDispatcher {

    enum class TalkerDecision {
        /** Pure chat — Talker answers via the cloud loop. */
        TALK,
        /** Multi-step work via the agent-mode plan executor. */
        EXECUTE_PLAN,
        /** Multi-step work via the autonomous loop. */
        EXECUTE_LOOP,
        /** "Ho gaya?" — instant templated answer from bus state. */
        STATUS_QUERY,
        /** Stop / cancel the running Executor command. */
        CANCEL_TASK
    }

    /** Exact-match stop turns (same list the engine's stop branch uses). */
    private val CANCEL_EXACT = setOf(
        "stop", "ruk ja", "ruko", "cancel", "jarvis stop", "bas kar"
    )

    /** A turn that cancels even as a prefix ("ruk ja, bas" / "cancel karo"). */
    private val CANCEL_PREFIXES = listOf(
        "ruk ja", "ruko", "bas kar", "cancel kar", "stop kar", "kaam rok"
    )

    /** Status questions — only a STATUS_QUERY while a command is pending. */
    private val STATUS_PATTERNS = listOf(
        "ho gaya", "ho gaya?", "hogaya", "ho gayi", "kitna hua", "kitna bacha",
        "status kya", "kya status", "kab hoga", "kab tak hoga",
        "progress", "kahan tak pahuncha", "kahan tak pahunche",
        "kaam ho gaya", "ban gaya", "ban gayi", "complete hua", "done hua"
    )

    /** Agent-mode triggers — the SAME frozen trigger as
     * JarvisAgentEngine.isAgentModeCommand (GitHub / Supabase / Vercel /
     * deploy / "agent mode"). Kept in sync by test, not by import: the
     * engine's trigger is private, so both lists must match. */
    private val AGENT_MODE_KEYWORDS = listOf(
        "github", "supabase", "vercel", "deploy", "agent mode"
    )

    /** Strong work verbs: a turn containing one is work, not chat.
     * Deliberately verb-first (not noun-first): "website banao" is work,
     * "website kya hai" is chat. */
    private val WORK_VERBS = listOf(
        "banao", "banado", "bana do", "bhej", "bhejo", "bhej do",
        "kholo", "khol do", "chalao", "chala do", "karo", "kar do",
        "dhoondho", "dhoondh", "search karo", "likho", "likh do",
        "save karo", "set karo", "lagao", "laga do", "band karo",
        "call karo", "message karo", "book karo", "order karo",
        "download karo", "upload karo", "share karo", "edit karo",
        "delete karo", "hatao", "hata do", "change karo", "badlo",
        "play karo", "pause karo", "bajao", "sunao", "dikhao",
        "reminder", "alarm lagao"
    )

    /** Question words that mark a turn as chat even next to a work verb
     * ("youtube kaise chalate hain" is a question, not a command). */
    private val QUESTION_MARKERS = listOf(
        "kaise", "kya hai", "kya hota", "kyun", "kyon", "kab hota",
        "kaun sa", "kaunsi", "matlab", "samjhao", "explain"
    )

    /**
     * Classify one trimmed user turn.
     *
     * @param text the user's turn, trimmed (original casing kept by caller).
     * @param hasActiveCommand true when the bus holds a pending Executor
     *   command — only then can a status turn be answered from state.
     * @param matchText optional pre-transliterated lowercase form of
     *   [text] (Devanagari → Latin keywords, as the engine's stop
     *   check does) — keyword matching runs against this when given,
     *   so "गीथब" still routes to EXECUTE_PLAN. Null = match on [text].
     */
    fun classify(text: String, hasActiveCommand: Boolean, matchText: String? = null): TalkerDecision {
        val lower = (matchText ?: text.lowercase()).trim()
        if (lower.isEmpty()) return TalkerDecision.TALK

        // 1. CANCEL wins over everything — including a status question
        // that happens to contain "ruk" or a work turn that starts "stop".
        if (lower in CANCEL_EXACT) return TalkerDecision.CANCEL_TASK
        if (CANCEL_PREFIXES.any { lower.startsWith(it) }) return TalkerDecision.CANCEL_TASK

        // 2. STATUS_QUERY — only while work runs. Without an active
        // command "ho gaya?" is a chat turn ("kya ho gaya?" → TALK).
        if (hasActiveCommand && STATUS_PATTERNS.any { lower.contains(it) }) {
            return TalkerDecision.STATUS_QUERY
        }

        // 3. EXECUTE_PLAN — agent-mode trigger (frozen list, §3 above).
        if (AGENT_MODE_KEYWORDS.any { lower.contains(it) }) {
            return TalkerDecision.EXECUTE_PLAN
        }

        // 4. EXECUTE_LOOP — a strong work verb, unless the turn is
        // shaped as a question (question markers win: asking HOW is
        // chat, even with a verb in it).
        if (QUESTION_MARKERS.any { lower.contains(it) }) return TalkerDecision.TALK
        if (WORK_VERBS.any { lower.contains(it) }) return TalkerDecision.EXECUTE_LOOP

        // 5. Everything else is talk.
        return TalkerDecision.TALK
    }

    /** True when the turn is a status question about running work. */
    fun isStatusQuestion(text: String): Boolean {
        val lower = text.lowercase().trim()
        return STATUS_PATTERNS.any { lower.contains(it) }
    }

    // ---- 1.9.0 (v2) explain-once / forget / list turns ----
    // Pure phrase helpers so the engine call site stays one line and
    // the phrases are unit-tested (DESIGN §5.8). Detection runs in
    // the engine BEFORE Talker classification, after macros — a
    // meta-turn about memory is not work, and must never dispatch.

    /** Trigger phrases that teach a standing instruction. The turn
     *  must also carry instruction text AFTER the trigger; a bare
     *  "yaad rakhna" is not an instruction (see [extractInstruction]). */
    private val EXPLAIN_TRIGGERS = listOf(
        "yaad rakhna", "yaad rakh", "note kar lo", "aage se", "hamesha"
    )

    /** True when the turn looks like an explain-once teaching turn. */
    fun isExplainOnceTurn(text: String): Boolean =
        extractInstruction(text) != null

    /**
     * The instruction text after the trigger phrase, or null when
     * there is none (bare trigger) or it is over-long (> 280 chars —
     * the Talker offers the document path instead of truncating).
     * Transliterated matching is the caller's job: pass the same
     * lowercased transliterated form the stop check uses.
     */
    fun extractInstruction(text: String): String? {
        val rest = triggerRemainder(text) ?: return null
        if (rest.isEmpty()) return null
        if (rest.length > 280) return null
        // "hamesha" alone is not a trigger ("hamesha aisa hota
        // hai" is chat) — DESIGN §5.8 wants "hamesha … karna"
        // shape. The V8 case ("hamesha pehle … poochhna") is
        // covered by the poochh- verb as well.
        if (triggerOf(text) == "hamesha") {
            val restLower = rest.lowercase()
            if (!restLower.contains("karna") && !restLower.contains("poochh") &&
                !restLower.contains("poochna")
            ) {
                return null
            }
        }
        return rest
    }

    /** True when a trigger phrase is present but nothing follows it
     *  ("yaad rakhna" alone) — the Talker asks what to remember. */
    fun isBareExplainOnceTurn(text: String): Boolean {
        val rest = triggerRemainder(text) ?: return false
        return rest.isEmpty()
    }

    /** True when a trigger phrase is present but the instruction is
     *  over-long (> 280 chars) — the Talker offers the document path. */
    fun isLongExplainOnceTurn(text: String): Boolean {
        val rest = triggerRemainder(text) ?: return false
        return rest.length > 280
    }

    /** Which trigger phrase matched first (longest-first order), or null. */
    private fun triggerOf(text: String): String? {
        val lower = text.lowercase().trim()
        for (trigger in EXPLAIN_TRIGGERS) {
            if (lower.contains(trigger)) return trigger
        }
        return null
    }

    /** Raw text after the first matching trigger phrase (trimmed of
     *  leading punctuation), or null when no trigger is present. */
    private fun triggerRemainder(text: String): String? {
        val lower = text.lowercase().trim()
        for (trigger in EXPLAIN_TRIGGERS) {
            val idx = lower.indexOf(trigger)
            if (idx < 0) continue
            return text.substring(idx + trigger.length)
                .trim()
                .trimStart(',', ':', '—', '-')
                .trim()
        }
        return null
    }

    /** "kya yaad rakha hai?" / "kya kya yaad hai?" — list remembered items. */
    fun isListMemoryTurn(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.contains("kya yaad rakha") || lower.contains("kya kya yaad hai") ||
            lower.contains("kya yaad hai")
    }

    /** "bhool jao …" / "ye mat yaad rakhna" — delete a stored instruction or pattern. */
    fun isForgetTurn(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.contains("bhool jao") || lower.contains("bhool ja") ||
            lower.contains("mat yaad rakhna")
    }

    /** True when the forget turn targets a learned PATTERN, not an instruction. */
    fun isForgetPatternTurn(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.contains("tareeka") || lower.contains("seekha hua")
    }

    /** "ye tareeka mat use karo" — disable the applied pattern for this command. */
    fun isDisablePatternTurn(text: String): Boolean {
        val lower = text.lowercase().trim()
        return (lower.contains("tareeka") && lower.contains("mat use karo")) ||
            lower.contains("dobara mat use karna")
    }

    /** True when the disable is permanent ("dobara mat use karna"). */
    fun isPermanentDisableTurn(text: String): Boolean =
        text.lowercase().contains("dobara mat use karna")

    /** "seekha hua phir se use karo" — re-enable a disabled pattern. */
    fun isReenablePatternTurn(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.contains("phir se use karo")
    }
}
