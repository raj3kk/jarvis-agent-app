package com.jarvis.autonomous.agent

import android.content.Context
import com.jarvis.autonomous.data.BlockRecord
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.data.LearnedRoutineStore
import com.jarvis.autonomous.data.LearnedStep
import com.jarvis.autonomous.data.SelfExtensionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Self-extension loop orchestrator (DESIGN §1).
 *
 * Lives INSIDE the Executor's single job — it NEVER launches its own
 * coroutine scope, NEVER survives its job, NEVER runs while another
 * Executor job is active (it is only ever called from inside one:
 * `AgentPlanExecutor.runPlan` after a step `Failed`, and the engine
 * loop after a loop action fails with a capability error). If the
 * job is cancelled, the loop dies with it — a half-researched
 * candidate is discarded, nothing is half-installed.
 *
 * Stage sequence: DETECT → RESEARCH → CLASSIFY (+ pre-scan) →
 * INTEGRATE → VERIFY → RECORD. On any failure: rollback, re-record
 * the block, one honest user line (S12, ≤ 300 chars).
 *
 * The loop NEVER retries the original step more than once via this
 * path (§2.5): [maybeExtend] takes the executor's [retryOriginal]
 * callback and invokes it at most once, only after a VERIFIED
 * install. V3 (§7.2): if that retry still fails, the skill is
 * rolled back and the block is recorded `verification_failed:
 * original_task_still_fails` — there is no partial credit.
 *
 * [hasRealInput] is a boolean only (S8): the verifier needs the
 * original failing task's real input; when false the loop does not
 * install at all (UNVERIFIED, never passed).
 */
class SelfExtensionLoop(
    private val context: Context,
    private val llmClient: LlmClient,
    private val routineStore: LearnedRoutineStore,
    private val store: SelfExtensionStore = SelfExtensionStore(
        File(context.applicationContext.filesDir, "device-memory/self-extension")
    ),
    /** Injected for tests / the engine's step path; default is the
     *  researcher's own allowlisted HttpURLConnection fetcher. */
    private val fetcher: ResearchFetcher = SelfExtensionResearcher.defaultFetcher()
) {

    sealed interface LoopOutcome {
        val userLine: String
        data class ExtendedAndVerified(val skillId: String, val capability: String, override val userLine: String) : LoopOutcome
        data class ProposalFiled(val proposalId: String, val capability: String, override val userLine: String) : LoopOutcome
        data class UserItem(val reason: String, override val userLine: String) : LoopOutcome
        data class GiveUp(val reason: String, override val userLine: String) : LoopOutcome
    }

    // Per-run guard (§2.5): one loop attempt per original task.
    // Keyed by run id (plan id / executor command id); a new run id
    // resets the guard. In-memory only — never persisted.
    private var lastRunId: String? = null
    private var attemptedSignatures: MutableSet<String> = mutableSetOf()

    private val installer = SelfExtensionInstaller(context, routineStore, store)

    suspend fun maybeExtend(
        failure: ExecutorFailure,
        runId: String,
        hasRealInput: Boolean,
        retryOriginal: (suspend () -> Boolean)? = null,
        stepRunner: suspend (SkillStep) -> Boolean
    ): LoopOutcome {
        val nowMs = System.currentTimeMillis()
        if (runId != lastRunId) {
            lastRunId = runId
            attemptedSignatures = mutableSetOf()
        }
        val alreadyAttempted = attemptedSignatures.contains(
            SelfExtensionDetector.signature(
                SelfExtensionDetector.capabilityFor(failure.stepKind, failure.action), failure
            )
        )

        // 1. DETECT (exclusions first — E6; the loop entry point calls
        // the detector; there is no direct call into the researcher).
        val detection = SelfExtensionDetector.detect(failure)
        if (detection !is Detection.MissingCapability) {
            val reason = (detection as? Detection.NotACapabilityBlock)?.exclusion ?: "NO_TRIGGER"
            return LoopOutcome.UserItem(reason, "Ye kaam is tareeke se nahi ho paya — $reason. Dobara bolo to phir se koshish karunga.")
        }
        val capability = detection.capability
        val signature = detection.signature

        // 2. Anti-loop gates (E8) — before ANY network call.
        when (val gate = store.canAttempt(signature, nowMs, alreadyAttempted)) {
            is com.jarvis.autonomous.data.AttemptGate.Denied -> {
                recordBlock(signature, capability, "give_up", gate.reason, nowMs)
                postLine(gate.userLine)
                return LoopOutcome.GiveUp(gate.reason, gate.userLine)
            }
            is com.jarvis.autonomous.data.AttemptGate.Allowed -> Unit
        }
        attemptedSignatures.add(signature)

        // 3. RESEARCH — 7-day report cache first (§3.5); a fresh run
        // records its research-run timestamp for the 3/day cap.
        val cached = store.cachedReport(signature, nowMs)
        val report: ResearchReport
        if (cached != null) {
            report = store.parseReport(cached, capability, signature)
                ?: run {
                    val fresh = runResearch(capability, signature)
                    store.recordResearchRun(nowMs)
                    fresh
                }
        } else {
            report = runResearch(capability, signature)
            store.recordResearchRun(nowMs)
        }
        if (report.outcome != ResearchOutcome.CANDIDATES_FOUND || report.candidates.isEmpty()) {
            val reason = report.failureReason.ifBlank { "no_candidates" }
            recordBlock(signature, capability, "give_up", reason, System.currentTimeMillis())
            val line = "Is kaam ke liye koi free/open-source tareeka nahi mila — isliye abhi nahi jod paya."
            postLine(line)
            return LoopOutcome.GiveUp(reason, line)
        }

        // 4. CLASSIFY (+ pre-scan) — ranked candidates, first
        // non-REJECT decides the path; all REJECT -> give up.
        val ranked = report.candidates
            .sortedByDescending { CapabilityClassifier.score(it) }
            .take(SelfExtensionBudgets.MAX_CANDIDATES)
        var firstRejectReason: String? = null
        for (candidate in ranked) {
            val steps = candidate.suggestedSteps
            val scanHit = SelfExtensionScanner.firstHit(steps, candidate.evidence.joinToString("\n"))
            val classification = CapabilityClassifier.classify(candidate, steps, scanHit)
            when (classification.value) {
                ClassificationValue.REJECT -> {
                    if (firstRejectReason == null) firstRejectReason = classification.reason
                    continue
                }
                ClassificationValue.APPROVAL -> {
                    return fileProposal(candidate, classification, capability, signature)
                }
                ClassificationValue.AUTO -> {
                    return installAndVerify(candidate, classification, steps, capability, signature, hasRealInput, retryOriginal, stepRunner)
                }
            }
        }
        val reason = firstRejectReason ?: "all_rejected"
        recordBlock(signature, capability, "give_up", reason, System.currentTimeMillis())
        val line = "Is kaam ka tareeka free nahi mila ya safe nahi laga — isliye nahi joda."
        postLine(line)
        return LoopOutcome.GiveUp(reason, line)
    }

    private suspend fun runResearch(capability: String, signature: String): ResearchReport {
        val llm = ResearchLlm { prompt ->
            runCatching { llmClient.researchCandidates(prompt) }.getOrNull()
        }
        // Blocking fetch leg runs on IO, never on the caller's thread.
        val report = withContext(Dispatchers.IO) {
            SelfExtensionResearcher.research(capability, signature, llm, fetcher)
        }
        runCatching {
            store.saveReport(
                signature,
                store.reportToJson(
                    capability = report.capability,
                    signature = report.signature,
                    researchedAtMs = report.researchedAtMs,
                    llmCalls = report.llmCalls,
                    fetches = report.fetches,
                    durationMs = report.durationMs,
                    outcome = report.outcome.name,
                    failureReason = report.failureReason,
                    candidates = report.candidates
                )
            )
        }
        return report
    }

    private suspend fun installAndVerify(
        candidate: ResearchCandidate,
        classification: Classification,
        steps: List<SkillStep>,
        capability: String,
        signature: String,
        hasRealInput: Boolean,
        retryOriginal: (suspend () -> Boolean)?,
        stepRunner: suspend (SkillStep) -> Boolean
    ): LoopOutcome {
        // No real input -> UNVERIFIED, never passed, never installed.
        if (!hasRealInput) {
            recordBlock(signature, capability, "give_up", "no_real_input", System.currentTimeMillis())
            val line = "Naya tareeka test karne ke liye asal input nahi mila — isliye skill nahi jodi."
            postLine(line)
            return LoopOutcome.GiveUp("no_real_input", line)
        }
        when (val installed = installer.install(classification, candidate, steps, capability, signature)) {
            is SelfExtensionInstaller.InstallResult.Refused -> {
                recordBlock(signature, capability, "give_up", "install_refused:${installed.reason}", System.currentTimeMillis())
                val line = "Naya tareeka safe check me pass nahi hua — isliye nahi joda."
                postLine(line)
                return LoopOutcome.GiveUp("install_refused:${installed.reason}", line)
            }
            is SelfExtensionInstaller.InstallResult.Failed -> {
                recordBlock(signature, capability, "give_up", "install_failed:${installed.reason}", System.currentTimeMillis())
                val line = "Naya tareeka jodte waqt error aa gaya — kuch install nahi hua."
                postLine(line)
                return LoopOutcome.GiveUp("install_failed:${installed.reason}", line)
            }
            is SelfExtensionInstaller.InstallResult.Installed -> {
                val skillId = installed.skillId
                // 5. VERIFY — real invocation through the production
                // step path, 120 s wall, SKILL_TEST refs.
                val verification = withContext(Dispatchers.IO) {
                    SelfExtensionVerifier.runTest(
                        steps = steps,
                        signature = signature,
                        hasRealInput = true,
                        stepRunner = stepRunner
                    )
                }
                if (verification.verdict != VerificationVerdict.VERIFIED) {
                    installer.rollback(skillId)
                    recordBlock(signature, capability, "verification_failed", verification.reason, System.currentTimeMillis())
                    val line = "Naya tareeka jod kar test kiya — test fail ho gaya, isliye skill hata di. Capability abhi nahi judi."
                    postLine(line)
                    return LoopOutcome.GiveUp("verification_failed:${verification.reason}", line)
                }
                if (!installer.markVerified(skillId, verification.testId)) {
                    installer.rollback(skillId)
                    recordBlock(signature, capability, "verification_failed", "mark_verified_failed", System.currentTimeMillis())
                    val line = "Naya tareeka test pass hua par enable nahi ho paya — skill hata di."
                    postLine(line)
                    return LoopOutcome.GiveUp("verification_failed:mark_verified_failed", line)
                }
                // V3: retry the original step once with the new skill.
                if (retryOriginal != null) {
                    val retried = runCatching { retryOriginal() }.getOrDefault(false)
                    if (!retried) {
                        installer.rollback(skillId)
                        recordBlock(
                            signature, capability, "verification_failed",
                            "original_task_still_fails", System.currentTimeMillis()
                        )
                        val line = "Naya tareeka test pass hua par asal kaam phir bhi nahi hua — skill hata di."
                        postLine(line)
                        return LoopOutcome.GiveUp("verification_failed:original_task_still_fails", line)
                    }
                }
                recordBlock(signature, capability, "extended_and_verified", "clean_local_step", System.currentTimeMillis())
                val line = "Naya tareeka khud research karke jod diya aur test karke verify bhi kar liya — ab ye kaam kar sakta hoon."
                postLine(line)
                return LoopOutcome.ExtendedAndVerified(skillId, capability, line)
            }
        }
    }

    private suspend fun fileProposal(
        candidate: ResearchCandidate,
        classification: Classification,
        capability: String,
        signature: String
    ): LoopOutcome {
        return when (val filed = installer.fileCodeProposal(candidate, classification, capability, signature)) {
            is SelfExtensionInstaller.ProposalResult.Failed -> {
                recordBlock(signature, capability, "give_up", "proposal_failed:${filed.reason}", System.currentTimeMillis())
                val line = "Is kaam ke liye naya code chahiye — proposal file nahi ho paya, isliye abhi ruk gaya."
                postLine(line)
                LoopOutcome.GiveUp("proposal_failed:${filed.reason}", line)
            }
            is SelfExtensionInstaller.ProposalResult.Filed -> {
                recordBlock(signature, capability, "proposal_filed", classification.reason, System.currentTimeMillis())
                val line = if (filed.submitOk) {
                    "Is kaam ke liye naya code chahiye — proposal approval ke liye bhej diya hai, abhi install nahi hua."
                } else {
                    "Is kaam ke liye naya code chahiye — proposal phone par save ho gaya hai (approval ke liye bhejna baaki hai)."
                }
                postLine(line)
                LoopOutcome.ProposalFiled(filed.proposalId, capability, line)
            }
        }
    }

    private fun recordBlock(signature: String, capability: String, outcome: String, reason: String, nowMs: Long) {
        runCatching {
            store.recordBlock(BlockRecord(signature, capability, outcome, reason, nowMs))
        }
    }

    /** One honest chat line per terminal outcome (S12). Best-effort; never throws. */
    private fun postLine(line: String) {
        runCatching {
            JarvisStateRepository.addChatMessage("Jarvis", line.take(300))
        }
    }
}
