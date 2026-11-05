package com.jarvis.autonomous.agent

import com.jarvis.autonomous.data.AgentPlan
import com.jarvis.autonomous.data.AgentPlanStore
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.data.AgentSecrets
import com.jarvis.autonomous.data.ChatSummary
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.data.PlanState
import com.jarvis.autonomous.data.PlanStep
import com.jarvis.autonomous.data.ProjectPreviewServer
import com.jarvis.autonomous.data.ProjectStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 1.4.21 Agent Mode — planner + executor. Owned by
 * [JarvisAgentEngine] (same ownership shape as llmClient /
 * learnedRoutineStore / memoryIndex) and run as the engine's
 * activeTaskJob, so the Live task-gate, announce suppression and
 * voice-stop gates key off isTaskRunning() unchanged. No
 * WorkManager, no second service, no new background component.
 * Pehle-Jaanch §3 is the design authority.
 *
 * Rules enforced here (not in prompts): a step is DONE only when
 * its §3d evidence check passes — the LLM's word alone is never
 * evidence; isCompleted from a decision NEVER ends a plan; a plan
 * ends only when every step is DONE, or terminally BLOCKED/FAILED
 * after asking. Silent progress (§3e): NO speaks for progress —
 * speaks ONLY for the final completion summary, ask-user
 * questions, and plan-ending failures. Chat lines only for
 * completions, failures, asks. AgentRunState is NOT extended.
 */
class AgentPlanExecutor(private val engine: JarvisAgentEngine) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    // ---- Outcomes ----

    private sealed interface StepOutcome {
        data class Done(val evidence: String) : StepOutcome
        data class Failed(val reason: String) : StepOutcome
        /** Question already asked (missing info) or gate paused — park as BLOCKED. */
        data class Blocked(val reason: String) : StepOutcome
    }

    private sealed interface AskResult {
        data class Answered(val answer: String) : AskResult
        data object Declined : AskResult
        data object NoReply : AskResult
    }

    private sealed interface GateResult {
        data object Allow : GateResult
        data object Abort : GateResult
        data class Pause(val reason: String) : GateResult
        data class Block(val reason: String) : GateResult
    }

    private data class GithubPushResult(
        val sha: String,
        val owner: String,
        val repo: String,
        val repoId: Long,
        val defaultBranch: String
    )

    // ---- Entry points ----

    /** Fresh agent-mode task from a user goal (called from the
     *  engine's handleUserCommand plan branch, already inside
     *  activeTaskJob). */
    suspend fun startNewPlan(goal: String) {
        val context = engine.planContext()
        // Q10: a new agent-mode task overwrites agent_plan.json —
        // one chat line warning if replacing an unfinished plan,
        // no speak.
        val existing = AgentPlanStore.load(context)
        if (existing != null && !existing.completed &&
            existing.steps.any { it.state == PlanState.PENDING || it.state == PlanState.RUNNING || it.state == PlanState.BLOCKED }
        ) {
            JarvisStateRepository.addChatMessage(
                "Jarvis",
                "Puraana adhoora plan '${existing.goal.take(60)}' replace ho raha hai — naya plan shuru ho raha hai."
            )
            engine.planPersistChat()
        }
        JarvisStateRepository.addChatMessage("You", goal)
        engine.planPersistChat()
        JarvisStateRepository.setCurrentGoal(goal)
        JarvisStateRepository.setAgentState(AgentRunState.THINKING, "Plan bana raha hoon…")
        if (!engine.planIsOnline()) {
            engine.planSpeak("Internet nahi hai — plan banane ke liye online hona zaroori hai. Net aane par dobara bolo.")
            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Offline — plan nahi bana")
            JarvisStateRepository.setCurrentGoal("")
            return
        }
        val recentChat = JarvisStateRepository.chatHistory.value.takeLast(6)
            .map { "${it.sender}: ${it.message}" }
        when (val result = engine.planLlm().planNext(
            goal,
            engine.planAnsweredClarifications().toList(),
            recentChat
        )) {
            is LlmClient.PlanResult.Failed -> {
                JarvisStateRepository.addChatMessage("Jarvis", result.reason)
                engine.planPersistChat()
                engine.planSpeak(result.reason)
                JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Plan nahi bana")
                JarvisStateRepository.setCurrentGoal("")
            }
            is LlmClient.PlanResult.Planned -> {
                val now = System.currentTimeMillis()
                val steps = result.steps.mapIndexed { i, draft ->
                    PlanStep(
                        id = "s${i + 1}",
                        kind = draft.kind,
                        title = draft.title,
                        detail = draft.detail,
                        needsGate = draft.needsGate
                    )
                }
                val plan = AgentPlan(
                    planId = UUID.randomUUID().toString(),
                    goal = goal,
                    createdAtMs = now,
                    updatedAtMs = now,
                    currentStepId = null,
                    answeredClarifications = engine.planAnsweredClarifications().toList(),
                    steps = steps
                )
                AgentPlanStore.save(context, plan)
                publish(plan)
                JarvisStateRepository.addChatMessage(
                    "Jarvis",
                    "Plan ban gaya — ${steps.size} steps: ${steps.joinToString(", ") { it.title }}."
                )
                engine.planPersistChat()
                runPlan(plan)
            }
        }
    }

    /** Resume after service recreation / app kill (called from
     *  FGS onCreate via engine.resumePersistedPlanIfAny). Silent
     *  when there is nothing resumable. */
    suspend fun resumePersistedPlan() {
        if (engine.isTaskRunning()) return
        val context = engine.planContext()
        val loaded = AgentPlanStore.load(context) ?: return
        if (loaded.completed) return
        if (loaded.steps.none {
                it.state == PlanState.PENDING || it.state == PlanState.RUNNING || it.state == PlanState.BLOCKED
            }
        ) return
        // answeredClarifications are restored into the engine list
        // (capped 12, as today) so the resumed plan never re-asks.
        engine.planRestoreClarifications(loaded.answeredClarifications)
        // A RUNNING step is demoted to PENDING with attempts
        // preserved and evidence appended — never blindly re-run.
        var plan = loaded
        val running = plan.steps.firstOrNull { it.state == PlanState.RUNNING }
        if (running != null) {
            plan = updateStep(plan, running.id) {
                it.copy(
                    state = PlanState.PENDING,
                    evidence = (it.evidence + " | interrupted by kill — verifying before re-running")
                        .trim().trimStart('|').trim()
                )
            }.copy(currentStepId = null)
            AgentPlanStore.save(context, plan)
        }
        publish(plan)
        // Launch as the engine's activeTaskJob (same pattern as a
        // new task) so isTaskRunning() is true while it runs.
        engine.planLaunch { runPlan(plan) }
    }

    // ---- Main loop ----

    private suspend fun runPlan(plan: AgentPlan) {
        var current = plan
        // Steps that already had one ask-driven re-run in this run.
        val retriedAfterAsk = mutableSetOf<String>()
        try {
            JarvisStateRepository.setCurrentGoal(current.goal)
            publish(current)
            var idx = 0
            while (idx < current.steps.size) {
                val step = current.steps[idx]
                when (step.state) {
                    PlanState.DONE, PlanState.SKIPPED -> {
                        idx++
                        continue
                    }
                    PlanState.FAILED -> {
                        // Interrupted before the terminal finish —
                        // give it one more run instead of dropping it.
                        current = updateStep(current, step.id) { it.copy(state = PlanState.PENDING) }
                        saveAndPublish(current)
                        continue
                    }
                    PlanState.BLOCKED -> {
                        // Resume path: re-ask the stored question ONCE.
                        val question = blockedQuestion(step)
                        when (val ans = askStepQuestion(question)) {
                            is AskResult.Answered -> {
                                if (engine.planIsDecline(ans.answer)) {
                                    abortPlan(current, step, "user declined")
                                    return
                                }
                                engine.planRecordClarification("Q: $question -> A: ${ans.answer}")
                                current = updateStep(current, step.id) { it.copy(state = PlanState.PENDING) }
                                saveAndPublish(current)
                                continue
                            }
                            is AskResult.Declined -> {
                                abortPlan(current, step, "user declined")
                                return
                            }
                            is AskResult.NoReply -> {
                                parkBlocked(current, step, question)
                                return
                            }
                        }
                    }
                    else -> Unit
                }
                // Verify-before-re-run for an interrupted step: if
                // the check proves it actually completed, mark it
                // DONE and advance — never re-execute a DONE step.
                if (step.evidence.contains("interrupted by kill")) {
                    when (val verified = verifyStep(step, current)) {
                        is StepOutcome.Done -> {
                            current = updateStep(current, step.id) {
                                it.copy(state = PlanState.DONE, evidence = verified.evidence)
                            }
                            saveAndPublish(current)
                            JarvisStateRepository.addChatMessage(
                                "Jarvis",
                                "✓ ${step.title} — ${verified.evidence.take(150)} (kill se pehle ho gaya tha, dobara nahi chalaya)"
                            )
                            engine.planPersistChat()
                            idx++
                            continue
                        }
                        else -> Unit // not complete — execute it (attempts + 1 below)
                    }
                }
                // Mark RUNNING before executing (write point 2).
                current = updateStep(current, step.id) {
                    it.copy(state = PlanState.RUNNING, attempts = it.attempts + 1)
                }.copy(currentStepId = step.id)
                saveAndPublish(current)
                JarvisStateRepository.setAgentState(
                    AgentRunState.EXECUTING_STEP,
                    "Plan ${idx + 1}/${current.steps.size}: ${step.title}…"
                )
                // Gate: needsGate steps through the existing
                // checkExecutionGate (see gateStep).
                when (val gate = gateStep(current.steps[idx])) {
                    is GateResult.Abort -> {
                        abortPlan(current, step, "gate decline")
                        return
                    }
                    is GateResult.Pause -> {
                        parkBlocked(current, step, gate.reason)
                        return
                    }
                    is GateResult.Block -> {
                        current = updateStep(current, step.id) {
                            it.copy(state = PlanState.FAILED, evidence = gate.reason)
                        }
                        saveAndPublish(current)
                        finishPlan(current, allDone = false)
                        return
                    }
                    is GateResult.Allow -> Unit
                }
                JarvisStateRepository.setAgentState(
                    AgentRunState.EXECUTING_STEP,
                    "Plan ${idx + 1}/${current.steps.size}: ${step.title}…"
                )
                when (val outcome = executeStep(current.steps[idx], current)) {
                    is StepOutcome.Done -> {
                        current = updateStep(current, step.id) {
                            it.copy(state = PlanState.DONE, evidence = outcome.evidence)
                        }
                        saveAndPublish(current)
                        JarvisStateRepository.addChatMessage(
                            "Jarvis",
                            "✓ ${step.title} — ${outcome.evidence.take(200)}"
                        )
                        engine.planPersistChat()
                        JarvisStateRepository.addLog(
                            "Plan step ${idx + 1} ${step.kind} DONE ${outcome.evidence.take(120)}"
                        )
                        idx++
                    }
                    is StepOutcome.Failed -> {
                        if (step.id in retriedAfterAsk) {
                            // Same step failed twice — terminal.
                            current = updateStep(current, step.id) {
                                it.copy(state = PlanState.FAILED, evidence = outcome.reason)
                            }
                            saveAndPublish(current)
                            finishPlan(current, allDone = false)
                            return
                        }
                        when (val ans = askStepQuestion(
                            "Step '${step.title}' fail ho gaya: ${outcome.reason.take(150)}. Dobara koshish karun ya chhod dun?"
                        )) {
                            is AskResult.Answered -> {
                                if (engine.planIsDecline(ans.answer)) {
                                    abortPlan(current, step, "user declined after failure")
                                    return
                                }
                                engine.planRecordClarification(
                                    "Q: Step '${step.title}' fail — dobara? -> A: ${ans.answer}"
                                )
                                retriedAfterAsk.add(step.id)
                                current = updateStep(current, step.id) { it.copy(state = PlanState.PENDING) }
                                saveAndPublish(current)
                                // Same idx — one re-run.
                            }
                            is AskResult.Declined -> {
                                abortPlan(current, step, "user declined after failure")
                                return
                            }
                            is AskResult.NoReply -> {
                                parkBlocked(
                                    current, step,
                                    "Step fail hua, user se poocha — jawab nahi aaya: ${outcome.reason.take(150)}"
                                )
                                return
                            }
                        }
                    }
                    is StepOutcome.Blocked -> {
                        // The question was already asked inside the
                        // step (missing info / gate pause) — park.
                        parkBlocked(current, step, outcome.reason)
                        return
                    }
                }
            }
            finishPlan(current, allDone = true)
        } catch (ce: CancellationException) {
            // User stop / new task / service destroy: demote the
            // RUNNING step to PENDING so the plan stays resumable.
            demoteRunningStep(current)
            throw ce
        } catch (e: Exception) {
            JarvisStateRepository.addLog("Plan executor error: ${e.message}")
            JarvisStateRepository.addDiagnostic(
                "plan", "EXECUTOR_ERROR",
                "Plan executor error: ${e.javaClass.simpleName}: ${(e.message ?: "").take(150)}"
            )
            demoteRunningStep(current)
            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Plan error — stopped")
            JarvisStateRepository.setCurrentGoal("")
            runCatching {
                engine.planSpeak("Plan me ek error aa gaya, isliye ruk gaya — dobara bolo to phir se koshish karunga.")
            }
        }
    }

    // ---- Terminal paths ----

    /** ONE summary speak (replaces "Aur kya karna hai?") + chat
     *  lines + memory index. A plan ends only here (or via
     *  abort/park): every step DONE, or terminally BLOCKED/FAILED
     *  after asking. */
    private suspend fun finishPlan(plan: AgentPlan, allDone: Boolean) {
        val context = engine.planContext()
        val done = plan.steps.filter { it.state == PlanState.DONE }
        val failed = plan.steps.filter { it.state == PlanState.FAILED || it.state == PlanState.BLOCKED }
        val summary = buildString {
            if (allDone) {
                append("Plan poora ho gaya — ")
                append(done.joinToString("; ") { "${it.title}: ${it.evidence.take(120)}" })
            } else {
                append("Plan ruk gaya — ")
                failed.forEach { append("${it.title} nahi hua (${it.evidence.take(120)}). ") }
                if (done.isNotEmpty()) append("Ho gaya: ${done.joinToString(", ") { it.title }}. ")
                append("Jo step ruka hai uske liye upar wali detail dekho, phir dobara bolo.")
            }
        }
        JarvisStateRepository.addChatMessage("Jarvis", summary)
        engine.planPersistChat()
        runCatching {
            engine.planMemoryIndex().indexTurn(
                System.currentTimeMillis().toString(),
                ChatSummary.summarize(plan.goal, summary)
            )
        }
        val completedPlan = plan.copy(
            completed = true,
            currentStepId = null,
            updatedAtMs = System.currentTimeMillis()
        )
        AgentPlanStore.save(context, completedPlan)
        publish(completedPlan)
        JarvisStateRepository.setAgentState(
            AgentRunState.IDLE,
            if (allDone) "Plan complete" else "Plan stopped"
        )
        JarvisStateRepository.setCurrentGoal("")
        engine.planClearTaskState()
        engine.planSpeak(summary)
    }

    /** Decline aborts the WHOLE plan (not just the step): the
     *  current step is BLOCKED, the rest SKIPPED, the plan is
     *  terminal (never auto-resumed — only a fresh user command
     *  starts a new one). */
    private suspend fun abortPlan(plan: AgentPlan, step: PlanStep, reason: String) {
        val context = engine.planContext()
        var p = updateStep(plan, step.id) {
            it.copy(state = PlanState.BLOCKED, evidence = "Blocked: $reason")
        }
        val now = System.currentTimeMillis()
        p = p.copy(
            steps = p.steps.map {
                if (it.state == PlanState.PENDING || it.state == PlanState.RUNNING) {
                    it.copy(state = PlanState.SKIPPED, updatedAtMs = now)
                } else it
            },
            completed = true,
            currentStepId = null,
            updatedAtMs = now
        )
        AgentPlanStore.save(context, p)
        publish(p)
        JarvisStateRepository.addChatMessage("Jarvis", "Theek hai, plan rok diya — '${step.title}': $reason.")
        engine.planPersistChat()
        JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Plan stopped by user")
        JarvisStateRepository.setCurrentGoal("")
        engine.planClearTaskState()
        engine.planSpeak("Theek hai, plan rok diya.")
    }

    /** No reply to a question: the plan stays BLOCKED and
     *  persisted (resumable — the next service recreation re-asks
     *  once). No speak here: the question was already spoken. */
    private suspend fun parkBlocked(plan: AgentPlan, step: PlanStep, reason: String) {
        val context = engine.planContext()
        val p = updateStep(plan, step.id) {
            it.copy(state = PlanState.BLOCKED, evidence = "Blocked: ${reason.take(300)}")
        }.copy(currentStepId = null, updatedAtMs = System.currentTimeMillis())
        AgentPlanStore.save(context, p)
        publish(p)
        JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Plan blocked — ${reason.take(80)}")
        JarvisStateRepository.setCurrentGoal("")
        engine.planClearTaskState()
    }

    /** Cancellation demotion: RUNNING -> PENDING (attempts kept).
     *  Saves ONLY when the file still holds this plan — a newer
     *  plan may already have overwritten it. */
    private fun demoteRunningStep(plan: AgentPlan) {
        val context = engine.planContext()
        val running = plan.steps.firstOrNull { it.state == PlanState.RUNNING } ?: return
        val onDisk = AgentPlanStore.load(context)
        if (onDisk?.planId != plan.planId) return
        val demoted = updateStep(plan, running.id) {
            it.copy(
                state = PlanState.PENDING,
                evidence = (it.evidence + " | interrupted by kill — verifying before re-running")
                    .trim().trimStart('|').trim()
            )
        }.copy(currentStepId = null, updatedAtMs = System.currentTimeMillis())
        AgentPlanStore.save(context, demoted)
        publish(demoted)
    }

    // ---- Ask / gate ----

    /** One spoken question via askUserVoice (B-M8: never wait on
     *  an unspoken question). Sets WAITING_USER_REPLY while
     *  asking. */
    private suspend fun askStepQuestion(question: String): AskResult {
        JarvisStateRepository.setAgentState(
            AgentRunState.WAITING_USER_REPLY,
            "Waiting for your voice answer..."
        )
        JarvisStateRepository.addChatMessage("Jarvis", question)
        engine.planPersistChat()
        val reply = engine.planAsk(question)
        if (reply.isNullOrBlank()) return AskResult.NoReply
        JarvisStateRepository.addChatMessage("You", reply)
        engine.planPersistChat()
        if (engine.planIsDecline(reply)) return AskResult.Declined
        return AskResult.Answered(reply)
    }

    /**
     * needsGate steps go through the EXISTING checkExecutionGate —
     * no second gate implementation. The gate's confirmation
     * questions are written for phone actions (send/call/pay), so
     * the synthetic decision carries the step's own text: for a
     * pay-like step the gate's own sensitive-confirmation question
     * fires (TYPE_TEXT + pay keyword); other steps pass its
     * validation as ALLOW. A flagged needsGate step is then asked
     * with the step's own wording through the SAME askUserVoice
     * primitive the gate itself uses internally — that ask is the
     * confirmation, not a second gate.
     */
    private suspend fun gateStep(step: PlanStep): GateResult {
        val payLike = PAY_WORDS.any {
            step.detail.lowercase().contains(it) || step.title.lowercase().contains(it)
        }
        val synthetic = AgentDecision(
            reply = "",
            thought = "plan step gate",
            action = if (payLike) "TYPE_TEXT" else "NONE",
            text = step.detail.ifBlank { step.title }
        )
        val (outcome, reason) = engine.planCheckGate(synthetic)
        when (outcome) {
            JarvisAgentEngine.GateOutcome.ABORT_TASK -> return GateResult.Abort
            JarvisAgentEngine.GateOutcome.PAUSE_TASK -> return GateResult.Pause("gate question nahi boli ja saki: $reason")
            JarvisAgentEngine.GateOutcome.BLOCK_STEP -> return GateResult.Block("Gate ne step block kiya: $reason")
            JarvisAgentEngine.GateOutcome.ALLOW -> Unit
        }
        if (step.needsGate) {
            return when (val ans = askStepQuestion(
                "Plan step '${step.title}' sensitive hai — ${step.detail.take(120)}. Kar dun?"
            )) {
                is AskResult.Answered -> if (engine.planIsDecline(ans.answer)) GateResult.Abort else GateResult.Allow
                is AskResult.Declined -> GateResult.Abort
                is AskResult.NoReply -> GateResult.Pause("sensitive step ka sawal poocha, jawab nahi aaya")
            }
        }
        return GateResult.Allow
    }

    // ---- Step dispatch ----

    private suspend fun executeStep(step: PlanStep, plan: AgentPlan): StepOutcome {
        return when (step.kind) {
            "PLAN" -> StepOutcome.Done("plan parsed: ${plan.steps.size} steps")
            "BUILD" -> executeBuild(step, plan)
            "GITHUB_PUSH" -> executeGithubPush(step, plan)
            "SUPABASE_TABLES" -> executeSupabaseTables(step, plan)
            "VERCEL_DEPLOY" -> executeVercelDeploy(step, plan)
            "HTTP_CHECK" -> executeHttpCheck(step)
            else -> StepOutcome.Failed("Unknown step kind: ${step.kind}")
        }
    }

    // ---- BUILD (existing builder loop, reused) ----

    private suspend fun executeBuild(step: PlanStep, plan: AgentPlan): StepOutcome {
        val context = engine.planContext()
        var projectName = parseDetailValue(step.detail, "project")
        if (projectName.isNullOrBlank()) projectName = engine.planLastBuildProject().takeIf { it.isNotBlank() }
        if (projectName.isNullOrBlank()) {
            when (val ans = askStepQuestion("Website kis naam se banau? Project ka naam bolo.")) {
                is AskResult.Answered -> {
                    projectName = ProjectStore.sanitizeProjectName(ans.answer)
                    engine.planRecordClarification("Q: project naam? -> A: $projectName")
                }
                is AskResult.Declined -> return StepOutcome.Blocked("User ne project naam dene se mana kar diya.")
                is AskResult.NoReply -> return StepOutcome.Blocked("Project naam nahi mila — user se poocha, jawab nahi aaya.")
            }
        }
        projectName = ProjectStore.sanitizeProjectName(projectName!!)
        // Garbar rule (§3g.2): building into an existing non-empty
        // project overwrites index.html by design, so the executor
        // asks first even if the planner forgot needsGate.
        val existingProject = ProjectStore.listProjects(context).firstOrNull { it.name == projectName }
        if (existingProject != null && existingProject.files.isNotEmpty()) {
            when (val ans = askStepQuestion(
                "Project '$projectName' pehle se hai (${existingProject.files.size} files). Upar se dobara banau?"
            )) {
                is AskResult.Answered -> {
                    if (!engine.planIsYes(ans.answer)) {
                        return StepOutcome.Blocked("User ne existing project overwrite karne se mana kar diya.")
                    }
                }
                is AskResult.Declined -> return StepOutcome.Blocked("User ne existing project overwrite karne se mana kar diya.")
                is AskResult.NoReply -> return StepOutcome.Blocked("Existing project overwrite ka sawal poocha, jawab nahi aaya.")
            }
        }
        // Drive the EXISTING builder loop: decideNextStep +
        // executeDecisionAction, same validation + one-repair
        // discipline as runAutonomousLoop (§5c / A-M10). The
        // WebsiteBuildSpec checklist is already in the system
        // prompt (step 5) — referenced, never copied.
        val previousActions = mutableListOf<String>()
        var repairUsed = false
        var parseRetries = 0
        var rateLimitRetries = 0
        val maxSteps = engine.planPrefs().maxAutonomousSteps
        var stepNo = 1
        var goal = "${plan.goal} (Project: $projectName — ${step.detail})"
        while (stepNo <= maxSteps) {
            if (!engine.planIsOnline()) return StepOutcome.Failed("Internet chala gaya — build ruk gaya.")
            val decision = engine.planLlm().decideNextStep(
                userGoal = goal,
                stepNumber = stepNo,
                previousActions = previousActions,
                answeredClarifications = engine.planAnsweredClarifications(),
                recentChat = emptyList(),
                recentNotifications = emptyList(),
                screenTree = "Agent Mode build — no live screen snapshot; build from the goal only.",
                hasSensitivePaymentScreen = false
            )
            when (decision.decisionKind) {
                "ERROR" -> return StepOutcome.Failed("Builder error: ${decision.reply.take(200)}")
                "PARSE_ERROR" -> {
                    if (parseRetries < 1) {
                        parseRetries++
                        delay(2000L)
                        continue
                    }
                    return StepOutcome.Failed("Builder ka jawab baar-baar adhoora aaya.")
                }
                "RATE_LIMITED" -> {
                    if (rateLimitRetries < 2) {
                        rateLimitRetries++
                        delay(5000L)
                        continue
                    }
                    return StepOutcome.Failed("Rate limit baar-baar lag gaya.")
                }
            }
            if (decision.askUser) {
                when (val ans = askStepQuestion(
                    decision.reply.ifBlank { "Build ke liye ek sawal hai — batao?" }
                )) {
                    is AskResult.Answered -> {
                        if (engine.planIsDecline(ans.answer)) {
                            return StepOutcome.Blocked("User ne build ke sawal par mana kar diya.")
                        }
                        engine.planRecordClarification("Q: ${decision.reply} -> A: ${ans.answer}")
                        goal = "$goal (User clarified: ${ans.answer})"
                        previousActions.add("Asked user: '${decision.reply}' -> User answered: '${ans.answer}'")
                        stepNo++
                        continue
                    }
                    is AskResult.Declined -> return StepOutcome.Blocked("User ne build ke sawal par mana kar diya.")
                    is AskResult.NoReply -> return StepOutcome.Blocked(
                        "Build ke sawal ka jawab nahi aaya: ${decision.reply.take(120)}"
                    )
                }
            }
            if (decision.action != "NONE") {
                val (gateOutcome, gateReason) = engine.planCheckGate(decision)
                if (gateOutcome != JarvisAgentEngine.GateOutcome.ALLOW) {
                    return StepOutcome.Failed("Build step gate ne roka: $gateReason")
                }
                engine.planExecuteDecision(decision)
                previousActions.add("Step $stepNo: ${decision.action}")
            }
            if (decision.action in listOf("CREATE_PROJECT", "WRITE_PROJECT_FILE")) {
                val v = engine.planLastBuildValidation()
                if (v != null && !v.ok) {
                    if (!repairUsed) {
                        repairUsed = true
                        val detail = (v.missingFiles.map { "$it missing" } +
                            v.emptyFiles.map { "$it khaali" } +
                            v.invalidRefs).joinToString(", ")
                        goal = "${plan.goal} (Repair: $detail dobara likhna hai)"
                        stepNo++
                        continue
                    }
                    return StepOutcome.Failed(
                        "Build validation fail: ${(v.missingFiles + v.emptyFiles + v.invalidRefs).joinToString(", ").take(200)}"
                    )
                }
            }
            if (decision.action == "WRITE_PROJECT_FILE" && !engine.planLastBuildWritten()) {
                return StepOutcome.Failed("File likh nahi payi — project ya file ka naam galat ho sakta hai.")
            }
            if (decision.isCompleted) {
                val v = engine.planLastBuildValidation()
                if (v?.ok == true && engine.planLastBuildWritten()) {
                    val builtProject = engine.planLastBuildProject().ifBlank { projectName }
                    return verifyBuildPreview(builtProject)
                }
                return StepOutcome.Failed("Build complete bola gaya par validation/write OK nahi hai.")
            }
            delay(engine.planPrefs().stepDelayMs)
            stepNo++
        }
        return StepOutcome.Failed("Build $maxSteps steps me complete nahi hua.")
    }

    /** BUILD evidence: validation OK + written + preview GET 200. */
    private suspend fun verifyBuildPreview(projectName: String): StepOutcome {
        val context = engine.planContext()
        val v = ProjectStore.validateProject(context, projectName)
        if (!v.ok) {
            return StepOutcome.Failed(
                "Build validation fail: ${(v.missingFiles + v.emptyFiles + v.invalidRefs).joinToString(", ").take(200)}"
            )
        }
        val (_, ready) = ProjectPreviewServer.ensureRunningReady(context)
        if (!ready) return StepOutcome.Failed("Preview server start nahi hua.")
        val code = httpGetStatus(ProjectStore.localhostUrl(projectName))
        if (code != 200) return StepOutcome.Failed("Preview GET HTTP $code — preview nahi khula.")
        val files = ProjectStore.listProjects(context)
            .firstOrNull { it.name == projectName }?.files ?: emptyList()
        return StepOutcome.Done(
            "validation OK; files: ${files.joinToString(", ").take(200)}; preview ${ProjectStore.localhostUrl(projectName)}"
        )
    }

    // ---- GITHUB_PUSH (trees API + GraphQL updateRef) ----

    private suspend fun executeGithubPush(step: PlanStep, plan: AgentPlan): StepOutcome {
        val context = engine.planContext()
        val token = AgentSecrets.get(context, AgentSecrets.KEY_GITHUB_PAT)
        if (token.isBlank()) {
            return StepOutcome.Blocked(
                "GitHub PAT saved nahi hai — AI Brain > Agent Mode — Connected accounts me GitHub PAT daalo (fine-grained, Contents read+write)."
            )
        }
        var repo = parseDetailValue(step.detail, "repo")
        if (repo.isNullOrBlank()) repo = resolveRepoFromClarifications(plan)
        if (repo.isNullOrBlank()) {
            when (val ans = askStepQuestion("GitHub repo ka naam kya rakhu? (naya repo banega to wahi naam use hoga)")) {
                is AskResult.Answered -> {
                    repo = sanitizeRepoName(ans.answer)
                    engine.planRecordClarification("Q: GitHub repo naam? -> A: $repo")
                }
                is AskResult.Declined -> return StepOutcome.Blocked("User ne repo naam dene se mana kar diya.")
                is AskResult.NoReply -> return StepOutcome.Blocked("Repo naam nahi mila — user se poocha, jawab nahi aaya.")
            }
        }
        repo = sanitizeRepoName(repo!!)
        if (repo.isBlank()) return StepOutcome.Failed("Repo naam khaali hai.")
        val projectName = resolveProjectName(step, plan)
            ?: return StepOutcome.Blocked("Project naam nahi mila — pehle BUILD step complete hona chahiye.")
        val project = ProjectStore.listProjects(context).firstOrNull { it.name == projectName }
            ?: return StepOutcome.Failed("Project '$projectName' phone par nahi mila.")
        val files = project.files.filter { ProjectStore.readFile(context, projectName, it).isNotBlank() }
        if (files.isEmpty()) return StepOutcome.Failed("Project '$projectName' me koi file nahi mili.")
        return withContext(Dispatchers.IO) {
            try {
                val result = pushToGithub(token, repo, projectName, files, context)
                // Full SHA in evidence (verify-before-re-run needs
                // it for the tree GET); display sites truncate.
                StepOutcome.Done(
                    "commit ${result.sha}; repo https://github.com/${result.owner}/${result.repo}; branch ${result.defaultBranch}"
                )
            } catch (e: IllegalStateException) {
                StepOutcome.Failed("GitHub ${e.message?.take(200)}")
            } catch (e: IOException) {
                StepOutcome.Failed("GitHub network error: ${(e.message ?: "").take(150)}")
            } catch (e: Exception) {
                StepOutcome.Failed("GitHub push fail: ${(e.message ?: "").take(150)}")
            }
        }
    }

    /** Trees API + GraphQL updateRef (same pattern as the
     *  jarvis-push helper; no expectedOldOid). One retry on
     *  network failure / 5xx only — a 4xx is the true answer and
     *  is never retried. */
    private fun pushToGithub(
        token: String,
        repo: String,
        projectName: String,
        files: List<String>,
        context: android.content.Context
    ): GithubPushResult {
        return withOneRetryOnNetworkOr5xx {
            val ghHeaders = mapOf(
                "Authorization" to "Bearer $token",
                "Accept" to "application/vnd.github+json"
            )
            val (userCode, userBody) = httpGet("https://api.github.com/user", ghHeaders)
            val owner = JSONObject(requireOk(userCode, userBody, "GitHub /user"))
                .optString("login", "")
            if (owner.isBlank()) throw IllegalStateException("GitHub /user se login nahi mila")
            val (repoCode, repoBody) = httpGet("https://api.github.com/repos/$owner/$repo", ghHeaders)
            var defaultBranch = "main"
            var repoId = 0L
            if (repoCode == 200) {
                val repoJson = JSONObject(repoBody)
                defaultBranch = repoJson.optString("default_branch", "main").ifBlank { "main" }
                repoId = repoJson.optLong("id", 0)
            } else if (repoCode == 404) {
                // Q4: create ONLY after a user-given name (the repo
                // name came from the user or an ask, never invented).
                val createPayload = JSONObject().apply {
                    put("name", repo)
                    put("private", true)
                    put("description", "Built with Jarvis Agent Mode")
                }.toString()
                val (createCode, createBody) = httpPostJson(
                    "https://api.github.com/user/repos", token, createPayload,
                    "application/vnd.github+json"
                )
                val created = JSONObject(requireOk(createCode, createBody, "GitHub create repo"))
                defaultBranch = created.optString("default_branch", "main").ifBlank { "main" }
                repoId = created.optLong("id", 0)
            } else {
                requireOk(repoCode, repoBody, "GitHub repo check")
            }
            var tipSha: String? = null
            var baseTreeSha: String? = null
            val (refCode, refBody) = httpGet(
                "https://api.github.com/repos/$owner/$repo/git/ref/heads/$defaultBranch", ghHeaders
            )
            if (refCode == 200) {
                tipSha = JSONObject(refBody).optJSONObject("object")
                    ?.optString("sha", "")?.takeIf { it.isNotBlank() }
                if (tipSha != null) {
                    val (commitCode, commitBody) = httpGet(
                        "https://api.github.com/repos/$owner/$repo/git/commits/$tipSha", ghHeaders
                    )
                    baseTreeSha = JSONObject(requireOk(commitCode, commitBody, "GitHub tip commit"))
                        .optJSONObject("tree")?.optString("sha", "")
                }
            } else if (refCode != 404) {
                requireOk(refCode, refBody, "GitHub ref check")
            }
            val treeEntries = JSONArray()
            for (file in files) {
                val content = ProjectStore.readFile(context, projectName, file)
                val blobPayload = JSONObject().apply {
                    put("content", content)
                    put("encoding", "utf-8")
                }.toString()
                val (blobCode, blobBody) = httpPostJson(
                    "https://api.github.com/repos/$owner/$repo/git/blobs", token, blobPayload,
                    "application/vnd.github+json"
                )
                val blobSha = JSONObject(requireOk(blobCode, blobBody, "GitHub blob $file"))
                    .optString("sha", "")
                treeEntries.put(JSONObject().apply {
                    put("path", file)
                    put("mode", "100644")
                    put("type", "blob")
                    put("sha", blobSha)
                })
            }
            val treePayload = JSONObject().apply {
                if (baseTreeSha != null) put("base_tree", baseTreeSha)
                put("tree", treeEntries)
            }.toString()
            val (treeCode, treeBody) = httpPostJson(
                "https://api.github.com/repos/$owner/$repo/git/trees", token, treePayload,
                "application/vnd.github+json"
            )
            val treeSha = JSONObject(requireOk(treeCode, treeBody, "GitHub tree")).optString("sha", "")
            val commitPayload = JSONObject().apply {
                put("message", "Jarvis Agent Mode: push $projectName website")
                put("tree", treeSha)
                put("parents", JSONArray().apply { if (tipSha != null) put(tipSha) })
            }.toString()
            val (commitCode, commitBody) = httpPostJson(
                "https://api.github.com/repos/$owner/$repo/git/commits", token, commitPayload,
                "application/vnd.github+json"
            )
            val commitSha = JSONObject(requireOk(commitCode, commitBody, "GitHub commit"))
                .optString("sha", "")
            if (tipSha != null) {
                val refQuery = JSONObject().apply {
                    put(
                        "query",
                        "{ repository(owner:\"$owner\", name:\"$repo\") { ref(qualifiedName:\"refs/heads/$defaultBranch\") { id } } }"
                    )
                }.toString()
                val (gqlCode, gqlBody) = httpPostJson(
                    "https://api.github.com/graphql", token, refQuery, "application/json"
                )
                val refId = JSONObject(requireOk(gqlCode, gqlBody, "GitHub GraphQL ref"))
                    .optJSONObject("data")?.optJSONObject("repository")
                    ?.optJSONObject("ref")?.optString("id", "")
                if (refId.isNullOrBlank()) throw IllegalStateException("GitHub GraphQL se ref id nahi mila")
                val mutation = JSONObject().apply {
                    put(
                        "query",
                        "mutation(\$refId:ID!,\$oid:GitObjectID!){updateRef(input:{refId:\$refId,oid:\$oid}){ref{target{oid}}}}"
                    )
                    put("variables", JSONObject().apply {
                        put("refId", refId)
                        put("oid", commitSha)
                    })
                }.toString()
                val (mutCode, mutBody) = httpPostJson(
                    "https://api.github.com/graphql", token, mutation, "application/json"
                )
                requireOk(mutCode, mutBody, "GitHub GraphQL updateRef")
            } else {
                val refPayload = JSONObject().apply {
                    put("ref", "refs/heads/$defaultBranch")
                    put("sha", commitSha)
                }.toString()
                val (refCreateCode, refCreateBody) = httpPostJson(
                    "https://api.github.com/repos/$owner/$repo/git/refs", token, refPayload,
                    "application/vnd.github+json"
                )
                requireOk(refCreateCode, refCreateBody, "GitHub create ref")
            }
            // Verify: follow-up GET confirms the expected files
            // exist at that SHA — a 200 without this check is not
            // evidence.
            val (verifyCode, verifyBody) = httpGet(
                "https://api.github.com/repos/$owner/$repo/git/trees/$commitSha?recursive=1", ghHeaders
            )
            val treeJson = JSONObject(requireOk(verifyCode, verifyBody, "GitHub verify tree"))
            val present = mutableSetOf<String>()
            val treeArr = treeJson.optJSONArray("tree")
            if (treeArr != null) {
                for (i in 0 until treeArr.length()) {
                    treeArr.optJSONObject(i)?.optString("path", "")
                        ?.takeIf { it.isNotBlank() }?.let { present.add(it) }
                }
            }
            val missing = files.filter { it !in present }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "Push verify fail: files missing at commit: ${missing.joinToString(", ")}"
                )
            }
            GithubPushResult(
                sha = commitSha, owner = owner, repo = repo,
                repoId = repoId, defaultBranch = defaultBranch
            )
        }
    }

    // ---- SUPABASE_TABLES (Management API, SQL from built schema.sql) ----

    private suspend fun executeSupabaseTables(step: PlanStep, plan: AgentPlan): StepOutcome {
        val context = engine.planContext()
        val token = AgentSecrets.get(context, AgentSecrets.KEY_SUPABASE_PAT)
        val ref = AgentSecrets.get(context, AgentSecrets.KEY_SUPABASE_PROJECT_REF)
        if (token.isBlank()) {
            return StepOutcome.Blocked(
                "Supabase PAT saved nahi hai — AI Brain > Agent Mode me Supabase PAT daalo."
            )
        }
        if (ref.isBlank()) {
            return StepOutcome.Blocked(
                "Supabase project ref khaali hai — AI Brain > Agent Mode me project ref daalo (Project Settings se)."
            )
        }
        val projectName = resolveProjectName(step, plan)
            ?: return StepOutcome.Blocked("Project naam nahi mila — pehle BUILD step complete hona chahiye.")
        val schema = ProjectStore.readFile(context, projectName, "backend/schema.sql")
        if (schema.isBlank()) {
            return StepOutcome.Failed("backend/schema.sql nahi mila — BUILD step me backend files bane hone chahiye.")
        }
        val tables = parseCreateTables(schema)
        if (tables.isEmpty()) {
            return StepOutcome.Failed("schema.sql me koi CREATE TABLE nahi mila — schema invent nahi kar sakta.")
        }
        return withContext(Dispatchers.IO) {
            try {
                // One retry per statement, network/5xx only — a
                // 4xx is the true answer (bad SQL / bad scope).
                for (stmt in splitSqlStatements(schema)) {
                    withOneRetryOnNetworkOr5xx {
                        val (code, body) = supabaseQuery(token, ref, stmt)
                        requireOk(code, body, "Supabase SQL")
                    }
                }
                val verified = verifySupabaseTablesNow(token, ref, tables)
                if (verified == null) {
                    return@withContext StepOutcome.Failed(
                        "Supabase verify fail: tables missing/empty: ${tables.joinToString(", ")}"
                    )
                }
                StepOutcome.Done("tables: ${tables.joinToString(", ")} (project $ref)")
            } catch (e: IllegalStateException) {
                StepOutcome.Failed("Supabase ${e.message?.take(200)}")
            } catch (e: IOException) {
                StepOutcome.Failed("Supabase network error: ${(e.message ?: "").take(150)}")
            } catch (e: Exception) {
                StepOutcome.Failed("Supabase error: ${(e.message ?: "").take(150)}")
            }
        }
    }

    /** Runs the verification query; returns the per-table column
     *  counts, or null when it cannot be confirmed. */
    private fun verifySupabaseTablesNow(
        token: String,
        ref: String,
        tables: List<String>
    ): Map<String, Int>? {
        return try {
            withOneRetryOnNetworkOr5xx {
                val inList = tables.joinToString(",") { "'$it'" }
                val sql = "SELECT table_name, column_name FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name IN ($inList)"
                val (code, body) = supabaseQuery(token, ref, sql)
                requireOk(code, body, "Supabase verify")
                val counts = parseInformationSchemaColumns(body)
                if (tables.all { (counts[it] ?: 0) >= 1 }) counts else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun supabaseQuery(token: String, ref: String, sql: String): Pair<Int, String> {
        return httpPostJson(
            "https://api.supabase.com/v1/projects/$ref/database/query",
            token,
            JSONObject().apply { put("query", sql) }.toString(),
            "application/json"
        )
    }

    private fun parseInformationSchemaColumns(body: String): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        fun absorb(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i)?.optString("table_name", "") ?: ""
                if (t.isNotBlank()) counts[t] = (counts[t] ?: 0) + 1
            }
        }
        runCatching {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                absorb(JSONArray(trimmed))
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                absorb(obj.optJSONArray("data"))
                absorb(obj.optJSONArray("rows"))
                absorb(obj.optJSONArray("result"))
            }
        }
        return counts
    }

    // ---- VERCEL_DEPLOY (REST API, project linked to the GitHub repo) ----

    private suspend fun executeVercelDeploy(step: PlanStep, plan: AgentPlan): StepOutcome {
        val context = engine.planContext()
        val token = AgentSecrets.get(context, AgentSecrets.KEY_VERCEL_TOKEN)
        if (token.isBlank()) {
            return StepOutcome.Blocked(
                "Vercel token saved nahi hai — AI Brain > Agent Mode me Vercel token daalo (Settings → Tokens)."
            )
        }
        val pushStep = plan.steps.firstOrNull { it.kind == "GITHUB_PUSH" }
        if (pushStep == null || pushStep.state != PlanState.DONE) {
            return StepOutcome.Failed("GitHub push pehle complete hona chahiye — Vercel deploy uske bina nahi ho sakta.")
        }
        val (owner, repo) = parseGithubRepo(pushStep)
            ?: return StepOutcome.Failed("GitHub push ka repo nahi mila — push step ki evidence check karo.")
        val branch = parseEvidenceValue(pushStep.evidence, "branch") ?: "main"
        val projectName = resolveProjectName(step, plan) ?: repo
        val vercelName = sanitizeVercelName(projectName)
        return withContext(Dispatchers.IO) {
            try {
                // 1. Create (or confirm) the Vercel project linked
                // to the GitHub repo.
                val createPayload = JSONObject().apply {
                    put("name", vercelName)
                    put("gitRepository", JSONObject().apply {
                        put("type", "github")
                        put("repo", "$owner/$repo")
                    })
                }.toString()
                val (createCode, createBody) = httpPostJson(
                    "https://api.vercel.com/v9/projects", token, createPayload, "application/json"
                )
                if (createCode == 402) {
                    return@withContext StepOutcome.Failed(
                        "Vercel free-tier limit (HTTP 402) — deploy ruk gaya. Vercel dashboard me usage/limits check karo; upgrade bina tumhari permission ke nahi hoga."
                    )
                }
                if (createCode == 409) {
                    val (getCode, getBody) = httpGet(
                        "https://api.vercel.com/v9/projects/$vercelName",
                        mapOf("Authorization" to "Bearer $token")
                    )
                    requireOk(getCode, getBody, "Vercel project check")
                } else {
                    requireOk(createCode, createBody, "Vercel create project")
                }
                // 2. GitHub repo id (needed for gitSource).
                val githubToken = AgentSecrets.get(context, AgentSecrets.KEY_GITHUB_PAT)
                if (githubToken.isBlank()) {
                    return@withContext StepOutcome.Failed(
                        "Vercel deploy ke liye GitHub repo id chahiye — GitHub PAT saved nahi hai."
                    )
                }
                val (repoCode, repoBody) = httpGet(
                    "https://api.github.com/repos/$owner/$repo",
                    mapOf(
                        "Authorization" to "Bearer $githubToken",
                        "Accept" to "application/vnd.github+json"
                    )
                )
                val repoId = JSONObject(requireOk(repoCode, repoBody, "GitHub repo")).optLong("id", 0)
                if (repoId == 0L) {
                    return@withContext StepOutcome.Failed("GitHub repo id nahi mila.")
                }
                // 3. Create the deployment from the repo.
                val depPayload = JSONObject().apply {
                    put("name", vercelName)
                    put("gitSource", JSONObject().apply {
                        put("type", "github")
                        put("repoId", repoId)
                        put("ref", branch)
                    })
                }.toString()
                val (depCode, depBody) = httpPostJson(
                    "https://api.vercel.com/v13/deployments", token, depPayload, "application/json"
                )
                if (depCode == 402) {
                    return@withContext StepOutcome.Failed(
                        "Vercel free-tier limit (HTTP 402) — deployment create nahi hua."
                    )
                }
                val depJson = JSONObject(requireOk(depCode, depBody, "Vercel create deployment"))
                val deploymentId = depJson.optString("id", "")
                var deploymentUrl = depJson.optString("url", "")
                if (deploymentId.isBlank() || deploymentUrl.isBlank()) {
                    return@withContext StepOutcome.Failed("Vercel deployment response me id/url nahi mila.")
                }
                // 4. Bounded poll: every 15 s, max 10 min (40 polls),
                // then BLOCKED-ask. Never an infinite poll.
                repeat(40) {
                    delay(15_000L)
                    val (pollCode, pollBody) = try {
                        httpGet(
                            "https://api.vercel.com/v13/deployments/$deploymentId",
                            mapOf("Authorization" to "Bearer $token")
                        )
                    } catch (e: IOException) {
                        return@repeat // transient network blip — next tick retries
                    }
                    if (pollCode == 402) {
                        return@withContext StepOutcome.Failed(
                            "Vercel free-tier limit (HTTP 402) — deploy ruk gaya."
                        )
                    }
                    if (pollCode !in 200..299) {
                        return@withContext StepOutcome.Failed(
                            "Vercel deploy status check fail: HTTP $pollCode."
                        )
                    }
                    val pollJson = JSONObject(pollBody)
                    val state = pollJson.optString("state", "")
                    deploymentUrl = pollJson.optString("url", deploymentUrl)
                    when (state) {
                        "READY" -> {
                            val liveCode = httpGetStatus("https://$deploymentUrl")
                            if (liveCode != 200) {
                                return@withContext StepOutcome.Failed(
                                    "Vercel deploy READY hai par live URL HTTP $liveCode de raha hai — deploy verify nahi hua."
                                )
                            }
                            return@withContext StepOutcome.Done(
                                "READY; url=https://$deploymentUrl; id=$deploymentId"
                            )
                        }
                        "ERROR", "CANCELED" -> {
                            return@withContext StepOutcome.Failed(
                                "Vercel deploy state: $state — Vercel dashboard me build logs dekho."
                            )
                        }
                        else -> Unit // BUILDING / QUEUED — keep polling
                    }
                }
                StepOutcome.Failed("Vercel deploy 10 min me READY nahi hua — Vercel dashboard me status check karo.")
            } catch (e: IllegalStateException) {
                StepOutcome.Failed("Vercel ${e.message?.take(200)}")
            } catch (e: IOException) {
                StepOutcome.Failed("Vercel network error: ${(e.message ?: "").take(150)}")
            } catch (e: Exception) {
                StepOutcome.Failed("Vercel error: ${(e.message ?: "").take(150)}")
            }
        }
    }

    // ---- HTTP_CHECK ----

    private suspend fun executeHttpCheck(step: PlanStep): StepOutcome {
        val url = parseDetailValue(step.detail, "url")
            ?: return StepOutcome.Failed("HTTP_CHECK me url nahi diya gaya.")
        val marker = parseDetailRest(step.detail, "marker")
        return httpCheck(url, marker)
    }

    private suspend fun httpCheck(url: String, marker: String?): StepOutcome {
        return try {
            val (code, body) = withContext(Dispatchers.IO) { httpGet(url) }
            if (code != 200) return StepOutcome.Failed("HTTP $code — $url")
            if (!marker.isNullOrBlank() && !body.contains(marker)) {
                return StepOutcome.Failed("Marker '$marker' nahi mila (HTTP 200).")
            }
            StepOutcome.Done("HTTP 200" + if (!marker.isNullOrBlank()) "; marker found: $marker" else "")
        } catch (e: Exception) {
            StepOutcome.Failed("HTTP check fail: ${(e.message ?: "").take(150)}")
        }
    }

    // ---- Verify-before-re-run (§3c.4) ----

    /** Returns Done when live state proves the step completed,
     *  null otherwise (caller then executes it). */
    private suspend fun verifyStep(step: PlanStep, plan: AgentPlan): StepOutcome? {
        return when (step.kind) {
            "PLAN" -> StepOutcome.Done("plan parsed: ${plan.steps.size} steps")
            "BUILD" -> {
                val project = resolveProjectName(step, plan) ?: return null
                when (val v = verifyBuildPreview(project)) {
                    is StepOutcome.Done -> v
                    else -> null
                }
            }
            "GITHUB_PUSH" -> verifyGithubPush(step, plan)
            "SUPABASE_TABLES" -> verifySupabaseTables(step, plan)
            "VERCEL_DEPLOY" -> verifyVercelDeploy(step, plan)
            "HTTP_CHECK" -> {
                val url = parseDetailValue(step.detail, "url") ?: return null
                val marker = parseDetailRest(step.detail, "marker")
                when (val r = httpCheck(url, marker)) {
                    is StepOutcome.Done -> r
                    else -> null
                }
            }
            else -> null
        }
    }

    private suspend fun verifyGithubPush(step: PlanStep, plan: AgentPlan): StepOutcome? {
        return withContext(Dispatchers.IO) {
            try {
                val token = AgentSecrets.get(engine.planContext(), AgentSecrets.KEY_GITHUB_PAT)
                if (token.isBlank()) return@withContext null
                val sha = parseEvidenceValue(step.evidence, "commit") ?: return@withContext null
                val (owner, repo) = parseGithubRepo(step) ?: return@withContext null
                val projectName = resolveProjectName(step, plan) ?: return@withContext null
                val expected = ProjectStore.listProjects(engine.planContext())
                    .firstOrNull { it.name == projectName }?.files ?: return@withContext null
                val (code, body) = httpGet(
                    "https://api.github.com/repos/$owner/$repo/git/trees/$sha?recursive=1",
                    mapOf("Authorization" to "Bearer $token", "Accept" to "application/vnd.github+json")
                )
                if (code !in 200..299) return@withContext null
                val present = mutableSetOf<String>()
                val treeArr = JSONObject(body).optJSONArray("tree")
                if (treeArr != null) {
                    for (i in 0 until treeArr.length()) {
                        treeArr.optJSONObject(i)?.optString("path", "")
                            ?.takeIf { it.isNotBlank() }?.let { present.add(it) }
                    }
                }
                if (expected.all { it in present }) {
                    StepOutcome.Done("verified after kill: commit ${sha.take(12)}; repo https://github.com/$owner/$repo")
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun verifySupabaseTables(step: PlanStep, plan: AgentPlan): StepOutcome? {
        return withContext(Dispatchers.IO) {
            try {
                val context = engine.planContext()
                val token = AgentSecrets.get(context, AgentSecrets.KEY_SUPABASE_PAT)
                val ref = AgentSecrets.get(context, AgentSecrets.KEY_SUPABASE_PROJECT_REF)
                if (token.isBlank() || ref.isBlank()) return@withContext null
                val projectName = resolveProjectName(step, plan) ?: return@withContext null
                val schema = ProjectStore.readFile(context, projectName, "backend/schema.sql")
                if (schema.isBlank()) return@withContext null
                val tables = parseCreateTables(schema)
                if (tables.isEmpty()) return@withContext null
                val counts = verifySupabaseTablesNow(token, ref, tables) ?: return@withContext null
                if (counts.isEmpty()) return@withContext null
                StepOutcome.Done("verified after kill: tables: ${tables.joinToString(", ")} (project $ref)")
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun verifyVercelDeploy(step: PlanStep, plan: AgentPlan): StepOutcome? {
        return withContext(Dispatchers.IO) {
            try {
                val token = AgentSecrets.get(engine.planContext(), AgentSecrets.KEY_VERCEL_TOKEN)
                if (token.isBlank()) return@withContext null
                val deploymentId = parseEvidenceValue(step.evidence, "id") ?: return@withContext null
                val url = parseEvidenceValue(step.evidence, "url") ?: return@withContext null
                val (code, body) = httpGet(
                    "https://api.vercel.com/v13/deployments/$deploymentId",
                    mapOf("Authorization" to "Bearer $token")
                )
                if (code !in 200..299) return@withContext null
                if (JSONObject(body).optString("state", "") != "READY") return@withContext null
                val liveCode = httpGetStatus(url)
                if (liveCode != 200) return@withContext null
                StepOutcome.Done("verified after kill: READY; url=$url; id=$deploymentId")
            } catch (e: Exception) {
                null
            }
        }
    }

    // ---- Name resolution helpers ----

    /** "key=value" (single token) from a step detail string. */
    private fun parseDetailValue(detail: String, key: String): String? {
        val regex = Regex("""(?i)(?:^|[;\s])$key=([^\s;]+)""")
        return regex.find(detail)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** "key=<rest of string>" (may contain spaces) from a detail string. */
    private fun parseDetailRest(detail: String, key: String): String? {
        val idx = detail.indexOf("$key=", ignoreCase = true)
        if (idx < 0) return null
        val rest = detail.substring(idx + key.length + 1)
        val end = rest.indexOf(';')
        return (if (end >= 0) rest.substring(0, end) else rest).trim().takeIf { it.isNotBlank() }
    }

    /** "key value" or "key=value" from a step evidence string. */
    private fun parseEvidenceValue(evidence: String, key: String): String? {
        val regex = Regex("""(?i)(?:^|;\s*)$key[= ]([^;]+)""")
        return regex.find(evidence)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun blockedQuestion(step: PlanStep): String {
        val ev = step.evidence.trim()
        if (ev.startsWith("Blocked:")) {
            return ev.removePrefix("Blocked:").trim()
                .takeIf { it.isNotBlank() } ?: "Plan step '${step.title}' ruka hua hai — aage badhau?"
        }
        return "Plan step '${step.title}' ruka hua hai — aage badhau?"
    }

    /** Project name: this step's detail, else the plan's BUILD
     *  step detail, else the last built project. */
    private fun resolveProjectName(step: PlanStep, plan: AgentPlan): String? {
        parseDetailValue(step.detail, "project")?.let { return ProjectStore.sanitizeProjectName(it) }
        plan.steps.firstOrNull { it.kind == "BUILD" }?.let { buildStep ->
            parseDetailValue(buildStep.detail, "project")?.let {
                return ProjectStore.sanitizeProjectName(it)
            }
        }
        return engine.planLastBuildProject().takeIf { it.isNotBlank() }
    }

    /** Repo name from answered clarifications ("repo=<name>" or a
     *  bare single-token answer to the repo question). */
    private fun resolveRepoFromClarifications(plan: AgentPlan): String? {
        for (entry in plan.answeredClarifications.asReversed()) {
            parseDetailValue(entry, "repo")?.let { return sanitizeRepoName(it) }
            val answer = entry.substringAfter("-> A: ", "").trim()
            if (answer.matches(Regex("[A-Za-z0-9._-]+")) && answer.length in 2..100) {
                return sanitizeRepoName(answer)
            }
        }
        return null
    }

    private fun parseGithubRepo(step: PlanStep): Pair<String, String>? {
        val regex = Regex("""github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)""")
        val match = regex.find(step.evidence) ?: return null
        return match.groupValues[1] to match.groupValues[2].trimEnd('/')
    }

    private fun sanitizeRepoName(raw: String): String {
        return raw.trim().lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.')
            .take(100)
    }

    private fun sanitizeVercelName(raw: String): String {
        val clean = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .take(60)
        return clean.ifBlank { "jarvis-site" }
    }

    private fun parseCreateTables(schema: String): List<String> {
        val regex = Regex("""(?i)CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?["`]?([A-Za-z_][A-Za-z0-9_]*)""")
        return regex.findAll(schema).map { it.groupValues[1] }.distinct().toList()
    }

    private fun splitSqlStatements(schema: String): List<String> {
        return schema.split(";").map { stmt ->
            stmt.lines().filter { !it.trim().startsWith("--") }.joinToString("\n").trim()
        }.filter { it.isNotBlank() }
    }

    // ---- Plan bookkeeping ----

    private fun publish(plan: AgentPlan) {
        JarvisStateRepository.setPlanState(plan.toView())
    }

    private fun saveAndPublish(plan: AgentPlan) {
        AgentPlanStore.save(engine.planContext(), plan)
        publish(plan)
    }

    private fun updateStep(plan: AgentPlan, stepId: String, transform: (PlanStep) -> PlanStep): AgentPlan {
        val now = System.currentTimeMillis()
        return plan.copy(
            steps = plan.steps.map { if (it.id == stepId) transform(it).copy(updatedAtMs = now) else it },
            updatedAtMs = now
        )
    }

    // ---- HTTP primitives (blocking — callers run them on IO) ----

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        httpClient.newCall(builder.build()).execute().use { resp ->
            return resp.code to (resp.body?.string().orEmpty())
        }
    }

    private fun httpPostJson(
        url: String,
        token: String?,
        json: String,
        accept: String
    ): Pair<Int, String> {
        val builder = Request.Builder()
            .url(url)
            .addHeader("Accept", accept)
            .post(json.toRequestBody("application/json".toMediaType()))
        if (token != null) builder.addHeader("Authorization", "Bearer $token")
        httpClient.newCall(builder.build()).execute().use { resp ->
            return resp.code to (resp.body?.string().orEmpty())
        }
    }

    private suspend fun httpGetStatus(url: String): Int {
        return withContext(Dispatchers.IO) {
            try {
                httpGet(url).first
            } catch (e: Exception) {
                -1
            }
        }
    }

    /** 2xx = body; anything else throws IllegalStateException
     *  ("HTTP <code>: ...") — the same fail-fast shape LlmClient
     *  uses, so the retry helper can classify it. */
    private fun requireOk(code: Int, body: String, what: String): String {
        if (code in 200..299) return body
        throw IllegalStateException("HTTP $code: ${body.take(300)} ($what)")
    }

    private fun isRetryableHttpError(e: IllegalStateException): Boolean {
        return (e.message ?: "").trim().startsWith("HTTP 5")
    }

    /** One retry on network failure / HTTP 5xx only. A 4xx is the
     *  true answer — never retried (blind replays of mutating
     *  calls after an unknown outcome are how duplicates happen). */
    private fun <T> withOneRetryOnNetworkOr5xx(block: () -> T): T {
        try {
            return block()
        } catch (e: IOException) {
            // network failure — one retry below
        } catch (e: IllegalStateException) {
            if (!isRetryableHttpError(e)) throw e
            // 5xx — one retry below
        }
        return block()
    }

    companion object {
        /** Same pay/send vocabulary as the engine gate
         *  (PAY_SEND_KEYWORDS) — a plan step whose wording matches
         *  is routed through the gate's sensitive path. */
        private val PAY_WORDS = listOf(
            "pay", "payment", "upi", "checkout", "place order",
            "confirm order", "buy now", "send", "bhej"
        )
    }
}
