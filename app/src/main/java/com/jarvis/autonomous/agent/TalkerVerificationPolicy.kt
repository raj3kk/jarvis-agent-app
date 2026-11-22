package com.jarvis.autonomous.agent

/**
 * Talker verification verdicts + user-facing wording (v2 Gap 2).
 *
 * DESIGN.md: expansion/talker-executor-v2/DESIGN.md §4.4–§4.7.
 * Release 1.9.0-PRO (vc 45). Pure Kotlin — ZERO Android imports.
 *
 * The one rule this file exists to enforce: only a VERIFIED DONE may
 * claim completion ("Ho gaya bhai!"). Two lines deliberately contain
 * the words "ho gaya" without claiming it: the UNVERIFIED line hedges
 * them ("Ho gaya lagta hai, par main verify nahi kar paya") and the
 * timeout line says the TIME ran out ("Time khatm ho gaya"). Every
 * other wording in this file is checked by test to NOT contain the
 * words at all. A DONE whose refs could not be checked is
 * UNVERIFIED (honest, not a failure); only a positive failure —
 * preview GET 404, commit SHA not found, file missing, draft still
 * in the input field — is a MISMATCH, and only a MISMATCH triggers
 * a FIX_REQUEST.
 */
object TalkerVerificationPolicy {

    /** Outcome of re-reading one evidence ref. */
    enum class RefOutcome { PASSED, FAILED, UNCHECKABLE }

    /** One ref's re-read result. */
    data class RefCheckResult(
        val refId: String,
        val outcome: RefOutcome,
        val detail: String = ""
    )

    enum class VerificationVerdict { VERIFIED, UNVERIFIED, MISMATCH }

    /**
     * Verdict table (DESIGN §4.4):
     * - any ref FAILED → MISMATCH (the claim is wrong about
     *   something concrete);
     * - no FAILED, ≥ 1 UNCHECKABLE → UNVERIFIED;
     * - zero refs (no-artifact DONE) → UNVERIFIED;
     * - every ref PASSED → VERIFIED.
     */
    fun decide(refResults: List<RefCheckResult>): VerificationVerdict {
        if (refResults.any { it.outcome == RefOutcome.FAILED }) return VerificationVerdict.MISMATCH
        if (refResults.isEmpty()) return VerificationVerdict.UNVERIFIED
        if (refResults.any { it.outcome == RefOutcome.UNCHECKABLE }) return VerificationVerdict.UNVERIFIED
        return VerificationVerdict.VERIFIED
    }

    /** The kinds of the refs that positively failed, in ref order. */
    fun failedKinds(refResults: List<RefCheckResult>): List<EvidenceKind> {
        val kinds = ArrayList<EvidenceKind>()
        for (result in refResults) {
            if (result.outcome != RefOutcome.FAILED) continue
            val kindName = result.refId.substringBefore(":").uppercase()
            val kind = EvidenceKind.entries.firstOrNull {
                it.wire.equals(kindName, ignoreCase = true)
            } ?: continue
            if (kind !in kinds) kinds.add(kind)
        }
        return kinds
    }

    /**
     * One short noun phrase per failed ref kind, from a FIXED map —
     * never free text from the Executor (DESIGN §4.7). Multiple
     * missing refs join with " aur ", max 2 named + "aur kuch
     * cheezein" for the rest.
     */
    fun missingPhrase(kind: EvidenceKind): String = when (kind) {
        EvidenceKind.PREVIEW_HTTP -> "preview nahi khul raha"
        EvidenceKind.FILE_LIST -> "kuch files nahi mili"
        EvidenceKind.GITHUB_COMMIT -> "GitHub par commit nahi mila"
        EvidenceKind.SUPABASE_TABLES -> "Supabase tables nahi mile"
        EvidenceKind.VERCEL_DEPLOY -> "Vercel deploy nahi mila"
        EvidenceKind.SCREEN_STATE -> "screen par confirm nahi hua"
        EvidenceKind.MESSAGE_SENT -> "screen par confirm nahi hua"
    }

    fun missingPhraseFor(kinds: List<EvidenceKind>): String {
        if (kinds.isEmpty()) return "kuch cheezein"
        val named = kinds.take(2).map { missingPhrase(it) }
        val joined = named.joinToString(" aur ")
        return if (kinds.size > 2) "$joined aur kuch cheezein" else joined
    }

    /** The ONLY line in this file that claims completion ("Ho gaya bhai!", v1 line, unchanged). */
    fun verifiedLine(summary: String, isLate: Boolean): String {
        val prefix = if (isLate) "Der se hi sahi — " else ""
        val clean = summary.trim().take(TalkerExecutorProtocol.MAX_SUMMARY_CHARS)
        return prefix + if (clean.isBlank()) "Ho gaya bhai!" else "Ho gaya bhai! $clean"
    }

    /**
     * UNVERIFIED DONE (DESIGN §4.7, Q3 wording): honest, not a
     * failure. `artifact` is one short noun phrase naming what the
     * user should check (e.g. "preview", "project ki files").
     */
    fun unverifiedLine(artifact: String): String {
        val what = artifact.trim().take(80).ifBlank { "kaam" }
        return "Ho gaya lagta hai, par main verify nahi kar paya — $what check kar lo."
    }

    /** Chat-only detail for an UNVERIFIED verdict: which checks could not run. */
    fun unverifiedDetail(uncheckable: List<RefCheckResult>): String {
        if (uncheckable.isEmpty()) return "Verify nahi hua: koi checkable evidence nahi mila."
        val parts = uncheckable.map { result ->
            val why = result.detail.trim().take(80).ifBlank { "check nahi chal paya" }
            "${result.refId} ($why)"
        }
        return "Verify nahi hua: ${parts.joinToString("; ")}."
    }

    /** MISMATCH, fix attempt starting (short speak). */
    fun fixStartLine(missingKinds: List<EvidenceKind>): String =
        "Ek cheez abhi poori nahi hui — ${missingPhraseFor(missingKinds)} dobara theek kar raha hoon."

    /** MISMATCH, attempt 1 failed, attempt 2 starting (short speak). */
    fun fixRetryLine(missingKinds: List<EvidenceKind>): String =
        "Abhi bhi ${missingPhraseFor(missingKinds)} nahi hua — ek aur baar try kar raha hoon."

    /** MISMATCH, attempts exhausted (DESIGN §4.7). Never "ho gaya". */
    fun fixExhaustedLine(missingKinds: List<EvidenceKind>): String =
        "Bhai, kaam poora nahi ho paya — ${missingPhraseFor(missingKinds)} abhi bhi nahi hua. Dobara bolo to phir se karunga."

    /** Fix window exceeded (timeout) — attempts count against the 15-min budget. */
    fun fixTimeoutLine(missingKinds: List<EvidenceKind>): String =
        "Time khatm ho gaya — ${missingPhraseFor(missingKinds)} theek nahi ho paya. Dobara bolo to phir se karunga."

    /** Chat-only detail for a MISMATCH: ref ids + reasons. */
    fun mismatchDetail(failed: List<RefCheckResult>): String {
        if (failed.isEmpty()) return "Missing: kuch verify nahi hua."
        val parts = failed.map { result ->
            val why = result.detail.trim().take(100).ifBlank { "check fail hua" }
            "${result.refId} ($why)"
        }
        return "Missing: ${parts.joinToString("; ")}."
    }

    /** Short artifact phrase for the UNVERIFIED line, from the refs
     *  that could not be checked (first one wins). */
    fun artifactPhraseFor(refResults: List<RefCheckResult>): String {
        val first = refResults.firstOrNull { it.outcome == RefOutcome.UNCHECKABLE }
            ?: refResults.firstOrNull()
            ?: return "kaam"
        val kindName = first.refId.substringBefore(":").uppercase()
        val kind = EvidenceKind.entries.firstOrNull { it.wire.equals(kindName, ignoreCase = true) }
        return when (kind) {
            EvidenceKind.PREVIEW_HTTP -> "preview"
            EvidenceKind.FILE_LIST -> "project ki files"
            EvidenceKind.GITHUB_COMMIT -> "GitHub commit"
            EvidenceKind.SUPABASE_TABLES -> "Supabase tables"
            EvidenceKind.VERCEL_DEPLOY -> "Vercel deploy"
            EvidenceKind.SCREEN_STATE, EvidenceKind.MESSAGE_SENT -> "screen"
            null -> "kaam"
        }
    }
}
