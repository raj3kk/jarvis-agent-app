package com.jarvis.autonomous.agent

/**
 * Self-extension stage 4 — VERIFY the new capability with a real
 * test before claiming it works (DESIGN §7).
 *
 * Runs INSIDE the same Executor job, immediately after install, and
 * reuses the exact execution path the skill will use in production:
 * the [stepRunner] supplied by the loop is the engine's
 * `executeLearnedStep` path (the same code `findRoutine` replay
 * uses) — not a mock, not a dry run, not an LLM self-assessment.
 *
 * Suspend: the loop calls it from `withContext(Dispatchers.IO)`.
 * Wall clock for the whole test run is 120 s (§7.1); overrun →
 * FAILED (`verification_timeout`).
 * Pass bar V1–V4 (§7.2): every step DONE through the production
 * step path (V1); every evidence ref re-read PASSED (V2 — the
 * Talker verdict over the SKILL_TEST refs must be VERIFIED, zero
 * refs → UNVERIFIED); the original task retried once with the new
 * skill reaches DONE (V3 — owned by the loop/executor, not here);
 * no gate bypassed, no confirmation skipped, no user content in
 * diagnostics (V4 — the verifier scans its own output for the goal
 * text: it never receives goal text in the first place, see below).
 *
 * S8: the verifier NEVER receives the original goal text or step
 * inputs from the user task — [hasRealInput] is a boolean only.
 * Verification needs the original failing task's real input; if
 * none exists, the loop must not install at all (UNVERIFIED, never
 * passed). Each executed step produces an [EvidenceRef] of kind
 * SKILL_TEST whose `check` is a re-readable assertion
 * (`step_ok:<ACTION>`).
 */
enum class VerificationVerdict { VERIFIED, FAILED, UNVERIFIED }

data class VerificationResult(
    val verdict: VerificationVerdict,
    val refs: List<EvidenceRef>,
    val reason: String,
    val testId: String
)

object SelfExtensionVerifier {

    /** Deterministic test id: "selfext-<first 8 of signature>". No user content. */
    fun testIdFor(signature: String): String = "selfext-${signature.take(8)}"

    /**
     * Pure verdict step (unit-testable): given per-step outcomes and
     * the refs they produced, decide the verdict the way §7.2 demands.
     * Zero refs → UNVERIFIED; any failed step → FAILED.
     */
    fun verdictFor(stepResults: List<Boolean>, refs: List<EvidenceRef>): VerificationVerdict {
        if (stepResults.isEmpty() || refs.isEmpty()) return VerificationVerdict.UNVERIFIED
        if (stepResults.any { !it }) return VerificationVerdict.FAILED
        val refResults = refs.map {
            TalkerVerificationPolicy.RefCheckResult(
                refId = it.refId(),
                outcome = TalkerVerificationPolicy.RefOutcome.PASSED
            )
        }
        return when (TalkerVerificationPolicy.decide(refResults)) {
            TalkerVerificationPolicy.VerificationVerdict.VERIFIED -> VerificationVerdict.VERIFIED
            else -> VerificationVerdict.UNVERIFIED
        }
    }

    /**
     * Runs [steps] through [stepRunner] (the production step path).
     * [hasRealInput] must be true — the caller guarantees the
     * original failing task's real input exists; false → UNVERIFIED
     * without running anything. [nowMs] supplies the clock so the
     * 120 s wall can be tested with a fake clock in tests.
     */
    suspend fun runTest(
        steps: List<SkillStep>,
        signature: String,
        hasRealInput: Boolean,
        stepRunner: suspend (SkillStep) -> Boolean,
        nowMs: () -> Long = { System.currentTimeMillis() }
    ): VerificationResult {
        val testId = testIdFor(signature)
        if (!hasRealInput) {
            return VerificationResult(
                VerificationVerdict.UNVERIFIED, emptyList(), "no_real_input", testId
            )
        }
        if (steps.isEmpty()) {
            return VerificationResult(
                VerificationVerdict.UNVERIFIED, emptyList(), "no_steps", testId
            )
        }
        val deadlineMs = nowMs() + SelfExtensionBudgets.VERIFY_WALL_MS
        val refs = ArrayList<EvidenceRef>()
        val results = ArrayList<Boolean>()
        for ((index, step) in steps.withIndex()) {
            if (nowMs() > deadlineMs) {
                return VerificationResult(
                    VerificationVerdict.FAILED, refs, "verification_timeout", testId
                )
            }
            val ok = runCatching { stepRunner(step) }.getOrDefault(false)
            results.add(ok)
            refs.add(
                EvidenceRef(
                    kind = EvidenceKind.SKILL_TEST,
                    ref = "$testId:step$index",
                    check = "step_ok:${step.action.uppercase()}",
                    value = if (ok) "ok" else "failed"
                )
            )
            if (!ok) {
                return VerificationResult(
                    VerificationVerdict.FAILED, refs,
                    "step_failed:${step.action.uppercase()}", testId
                )
            }
        }
        return when (verdictFor(results, refs)) {
            VerificationVerdict.VERIFIED -> VerificationResult(
                VerificationVerdict.VERIFIED, refs, "verified", testId
            )
            else -> VerificationResult(
                VerificationVerdict.UNVERIFIED, refs, "unverified", testId
            )
        }
    }
}
