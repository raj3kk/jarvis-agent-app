package com.jarvis.autonomous.agent

import com.jarvis.autonomous.browser.JarvisJobEngine
import com.jarvis.autonomous.ui.JarvisBackgroundBrowser
import com.jarvis.autonomous.data.AgentPlan
import com.jarvis.autonomous.data.AgentPlanStore
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.data.AgentSecrets
import com.jarvis.autonomous.data.FilePower
import com.jarvis.autonomous.data.HubGithubClient
import com.jarvis.autonomous.data.ChatSummary
import com.jarvis.autonomous.data.DocContextPrompt
import com.jarvis.autonomous.data.GoogleClient
import com.jarvis.autonomous.data.JarvisEventLog
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.data.LearnedStep
import com.jarvis.autonomous.data.PlanState
import com.jarvis.autonomous.data.PlanStep
import com.jarvis.autonomous.data.PlanTrajectoryEntry
import com.jarvis.autonomous.data.ProjectPreviewServer
import com.jarvis.autonomous.data.ProjectStore
import com.jarvis.autonomous.data.ShellAutomation
import com.jarvis.autonomous.service.JarvisAccessibilityService
import com.jarvis.autonomous.shizuku.ShizukuManager
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
 * Rules enforced here (not in prompts): a unit is DONE only when
 * its §3d evidence check passes — the LLM's word alone is never
 * evidence; isCompleted from a decision NEVER ends a plan; a plan
 * ends only when every unit is DONE, or terminally BLOCKED/FAILED
 * after asking. Silent progress (§3e): NO speaks for progress —
 * speaks ONLY for the final completion summary, ask-user
 * questions, and plan-ending failures. Chat lines only for
 * completions, failures, asks. AgentRunState is NOT extended.
 *
 * R1 (1.16.0-PRO) — NO-STEP REWIRE (REDESIGN_PLAN.md §9.1, user
 * re-confirmed 2026-11-23): the user NEVER sees a step — no lists,
 * no counts, no per-unit confirmations, no "step" word in any chat
 * line, spoken line, state label, or card. Internal work units
 * stay internal (the PlanStep model keeps its name — it never
 * surfaces). The ONLY mid-task stops are the 5 hard gates
 * (otp / login / payment / biometric / destructive_confirm) + the
 * 15-min watchdog. Every other mid-task clarification becomes a
 * PARK: the task stops, the Talker tells the user plainly what is
 * needed, the Executor waits — never a mid-task ask-stop. The
 * Executor NEVER speaks to the user directly; results go only via
 * the bus to the Talker.
 *
 * R1 — N3 BUDGET (REDESIGN_PLAN.md §9.3): per-task budget replaces
 * step-count stopping. TaskBudget: 10 work units default / 20 hard,
 * max 2 fix attempts per unit, 15-min watchdog, 20 LLM calls
 * default / 40 hard — PLAN DEFAULTS, NEEDS THE USER'S WORD (see
 * TaskBudget.kt). The loop runs until DONE (verified) or budget
 * exhausted or watchdog — never after N steps. The hub's
 * 5-calls/turn cap does NOT apply to the phone path (LlmClient
 * calls Gemini/Groq directly, BYOK).
 *
 * R1 — GITHUB VIA HUB (REDESIGN_PLAN.md §4.1): pushToGithub and
 * every direct api.github.com call are GONE. GitHub goes through
 * HubGithubClient (device-signed hub routes, admin token on the
 * hub). github_not_configured / 503 parks with a plain-words
 * message, never a crash. Supabase/Vercel direct calls STAY
 * (user's own projects).
 */
class AgentPlanExecutor(private val engine: JarvisAgentEngine) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    // 1.4.22 (item 5a): detail of the last Supabase column
    // mismatch, set by verifySupabaseTablesNow alongside the
    // SUPABASE_VERIFY_COLUMNS diagnostic so the caller can put
    // the table + missing columns in the Failed message. Reset
    // at the start of every verify call (never stale).
    private var lastSupabaseVerifyMismatch: String? = null

    // 1.9.0 (v2): the plan that last reached finishPlan — the
    // Talker verification gate reads it for fixMissingRefs
    // (re-run only the failed steps) and for pattern extraction
    // (learn only from VERIFIED DONE). Set in finishPlan only.
    internal var lastFinishedPlan: AgentPlan? = null
        private set

    // R1 (N3): the running task's budget — created in startNewPlan
    // (or restored from the persisted plan on resume), snapshotted
    // into agent_plan.json on every saveAndPublish.
    private var taskBudget: TaskBudget? = null

    // ---- Outcomes ----

    private sealed interface StepOutcome {
        /** `refs` are the typed evidence refs (DESIGN §4.2) this
         *  step proved — the Talker re-reads them before saying
         *  "ho gaya". Empty when the step has no re-checkable
         *  artifact. */
        data class Done(val evidence: String, val refs: List<EvidenceRef> = emptyList()) : StepOutcome
        data class Failed(val reason: String) : StepOutcome
        /** Question already asked (missing info) or gate paused — park as BLOCKED. */
        data class Blocked(val reason: String) : StepOutcome
        /** Decline (incl. gate ABORT_TASK) — abortPlan already ran
         *  inside the step; runPlan must just stop, never park/re-ask. */
        data object Aborted : StepOutcome
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

    // R1: direct GitHub push is GONE (hub proxy now) — the result
    // type lived here only for pushToGithub. Deleted with it.

    // ---- Entry points ----

    /** Fresh agent-mode task from a user goal (called from the
     *  engine's handleUserCommand plan branch, already inside
     *  activeTaskJob).
     *
     *  1.9.0 (v2, DESIGN §5.4): [learnedHint] / [instructionHint]
     *  are guidance, never orders — they prepend ONE hint line to
     *  the planner prompt. The planner may still ask the user when
     *  a value is genuinely missing.
     *  M-I-2 (1.14.0): [docContext] is the I1 recall output
     *  ([DocumentText.buildDocContext] — max 3 docs, snippets
     *  ≤400 chars, labelled). It rides the planner prompt as
     *  context only, via [DocContextPrompt.planGoalWithDocs];
     *  blank means no recall and the prompt is byte-identical to
     *  the pre-I2 version. */
    suspend fun startNewPlan(
        goal: String,
        learnedHint: String = "",
        instructionHint: String = "",
        docContext: String = ""
    ) {
        val context = engine.planContext()
        // R1 (N3): fresh per-task budget — the planNext call below
        // is this task's first LLM call.
        taskBudget = TaskBudget()
        // Q10: a new agent-mode task overwrites agent_plan.json —
        // one chat line warning if replacing an unfinished plan,
        // no speak.
        val existing = AgentPlanStore.load(context)
        if (existing != null && !existing.completed &&
            existing.steps.any { it.state == PlanState.PENDING || it.state == PlanState.RUNNING || it.state == PlanState.BLOCKED || it.state == PlanState.AWAITING_USER }
        ) {
            JarvisStateRepository.addChatMessage(
                "Jarvis",
                "Puraana adhoora plan '${existing.goal.take(60)}' replace ho raha hai — naya plan shuru ho raha hai."
            )
            engine.planPersistChat()
        }
        // 1.8.0: the Talker already posted this turn's "You" line
        // with the ack — postYouLineOnce dedupes it (no-op when the
        // plan was NOT started by the Talker).
        engine.planPostYouLineOnce(goal)
        engine.planPersistChat()
        JarvisStateRepository.setCurrentGoal(goal)
        JarvisStateRepository.setAgentState(AgentRunState.THINKING, "Plan bana raha hoon…")
        if (!engine.planIsOnline()) {
            engine.planSpeak("Internet nahi hai — plan banane ke liye online hona zaroori hai. Net aane par dobara bolo.")
            // 1.8.0: BLOCKED, chat-only — the line above was spoken.
            engine.planReportExecutorResult(
                ExecutorResultStatus.BLOCKED,
                "Internet nahi hai — plan banane ke liye online hona zaroori hai.",
                "plan start offline", speakResult = false
            )
            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Offline — plan nahi bana")
            JarvisStateRepository.setCurrentGoal("")
            return
        }
        val recentChat = JarvisStateRepository.chatHistory.value.takeLast(6)
            .map { "${it.sender}: ${it.message}" }
        // 1.9.0 (v2, DESIGN §5.4): learned guidance reaches the
        // planner as ONE hint line in the prompt (hint — zaroori
        // nahi). The stored plan goal stays the user's own words.
        // M-I-2 (1.14.0): recalled doc context rides the same way,
        // via DocContextPrompt (blank = no recall, prompt unchanged).
        val planGoal = DocContextPrompt.planGoalWithDocs(goal, learnedHint, instructionHint, docContext)
        when (val result = engine.planLlm().planNext(
            planGoal,
            engine.planAnsweredClarifications().toList(),
            recentChat
        )) {
            is LlmClient.PlanResult.Failed -> {
                JarvisStateRepository.addChatMessage("Jarvis", result.reason)
                engine.planPersistChat()
                engine.planSpeak(result.reason)
                // 1.8.0: FAILED, unannounced — the reason is already
                // fully in chat AND spoken above.
                engine.planReportExecutorResult(
                    ExecutorResultStatus.FAILED,
                    result.reason.take(300),
                    "planNext failed", speakResult = false, chatResult = false
                )
                JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Plan nahi bana")
                JarvisStateRepository.setCurrentGoal("")
            }
            is LlmClient.PlanResult.Planned -> {
                // R1 (N3): the planner call counts against the task
                // LLM budget (1 of 20 default / 40 hard).
                taskBudget?.noteLlmCall()
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
                    steps = steps,
                    budget = taskBudget?.snapshot()
                )
                AgentPlanStore.save(context, plan)
                publish(plan)
                // R1 (no-step): NO unit list, NO count in chat —
                // the user hears plain words only.
                JarvisStateRepository.addChatMessage(
                    "Jarvis",
                    "Plan ban gaya — ab kaam shuru kar raha hoon."
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
                it.state == PlanState.PENDING || it.state == PlanState.RUNNING || it.state == PlanState.BLOCKED ||
                    it.state == PlanState.AWAITING_USER
            }
        ) return
        // answeredClarifications are restored into the engine list
        // (capped 12, as today) so the resumed plan never re-asks.
        engine.planRestoreClarifications(loaded.answeredClarifications)
        // R1 (N3): restore the task budget from the persisted plan
        // — counters continue, the deadline does NOT reset.
        taskBudget = loaded.budget?.let { TaskBudget.restore(it) } ?: TaskBudget(
            startedAtMs = loaded.createdAtMs
        )
        // A RUNNING (or captcha-waiting AWAITING_USER) step is
        // demoted to PENDING with attempts preserved and evidence
        // appended — never blindly re-run.
        var plan = loaded
        val running = plan.steps.firstOrNull { it.state == PlanState.RUNNING || it.state == PlanState.AWAITING_USER }
        if (running != null) {
            val note = if (running.state == PlanState.AWAITING_USER) {
                "captcha wait ke dauraan ruk gaya — verifying before re-running"
            } else {
                "interrupted by kill — verifying before re-running"
            }
            plan = updateStep(plan, running.id) {
                it.copy(
                    state = PlanState.PENDING,
                    evidence = (it.evidence + " | $note")
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
        // R2.7: a fresh run starts with a clean trajectory — entries
        // from an earlier run (e.g. a step whose outcome led straight
        // to finishPlan without an updateStep) never leak into this plan.
        runTrajectory.clear()
        // R1 (N3): budget for this run — restored on resume, fresh
        // on a new plan. The loop runs until DONE (verified) or
        // budget exhausted or watchdog — never after N steps.
        if (taskBudget == null) {
            taskBudget = plan.budget?.let { TaskBudget.restore(it) } ?: TaskBudget()
        }
        val budget = taskBudget!!
        // 1.11.0-PRO (self-extension): units already retried once
        // via the self-extension loop — the loop never retries the
        // original unit more than once (DESIGN §2.5). R1: the
        // retry itself is now counted in the budget (max 2 fix
        // attempts per unit), the set only guards the extension.
        val extendedAfterExtension = mutableSetOf<String>()
        // 1.9.0 (v2, DESIGN §4.2): typed evidence refs collected
        // from DONE steps — finishPlan attaches them to the DONE
        // claim so the Talker can re-read them.
        val collectedRefs = ArrayList<EvidenceRef>()
        // 1.9.0 (v2, DESIGN §3.2): per-step durations for the ETA
        // (elapsedPerStep × remainingSteps, plans only).
        val stepDurationsMs = ArrayList<Long>()
        try {
            JarvisStateRepository.setCurrentGoal(current.goal)
            publish(current)
            var idx = 0
            while (idx < current.steps.size) {
                // R1 (N3): watchdog — the 15-min stop. The task ends
                // (FAILED, plain words), never silently, never late.
                if (budget.watchdogExpired()) {
                    current = updateStep(current, current.steps[idx].id) {
                        it.copy(
                            state = PlanState.FAILED,
                            evidence = "15 minute ho gaye — kaam samay par poora nahi ho paya."
                        )
                    }
                    saveAndPublish(current)
                    finishPlan(current, allDone = false)
                    return
                }
                // R1 (N3): hard work-unit budget — a plan longer
                // than 20 units cannot exist (planner caps at 8),
                // so this fires only on pathological re-runs.
                if (budget.workUnitsDone >= TaskBudgetDefaults.WORK_UNITS_HARD) {
                    finishPlan(current, allDone = false)
                    return
                }
                val step = current.steps[idx]
                when (step.state) {
                    PlanState.DONE, PlanState.SKIPPED -> {
                        idx++
                        continue
                    }
                    PlanState.FAILED -> {
                        // 1.4.22 (item 5c): a FAILED step in a
                        // non-completed plan means the process was
                        // killed between the FAILED save and
                        // finishPlan — never silently re-run it.
                        // finishPlan surfaces the stored failure
                        // evidence in its summary + planSpeak.
                        finishPlan(current, allDone = false)
                        return
                    }
                    PlanState.BLOCKED -> {
                        // Resume path: the PARKED question is asked
                        // ONCE — only because the user re-engaged
                        // (new command / service recreation), never
                        // mid-task. A mid-task clarification PARKS;
                        // it is never asked while the task runs.
                        val question = blockedQuestion(step)
                        when (val ans = askUserOnce(question)) {
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
                // (Both demote notes count: kill-interrupt and the
                // 3c captcha-wait demote.)
                if (step.evidence.contains("interrupted by kill") ||
                    step.evidence.contains("captcha wait ke dauraan ruk gaya")
                ) {
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
                // R1 (no-step): plain-words status only — no count,
                // no "step" word. The Talker speaks this at most
                // once per 30 s (progress policy).
                JarvisStateRepository.setAgentState(
                    AgentRunState.EXECUTING_STEP,
                    "Abhi ${step.title} kar raha hoon…"
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
                    "Abhi ${step.title} kar raha hoon…"
                )
                // 1.9.0 (v2, DESIGN §3.2): plan step START milestone
                // (best-effort — a dropped line is invisible, a
                // crashed plan is not). ETA from the average finished
                // step duration; -1 while no step has finished yet.
                val stepStartMs = System.currentTimeMillis()
                emitPlanProgress(
                    current, idx, phase = "START",
                    etaSeconds = planEtaSeconds(stepDurationsMs, current.steps.size - idx)
                )
                when (val outcome = executeStep(current.steps[idx], current)) {
                    is StepOutcome.Done -> {
                        stepDurationsMs.add(System.currentTimeMillis() - stepStartMs)
                        // R1 (N3): one finished work unit against
                        // the task budget.
                        budget.noteWorkUnitDone()
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
                            "Plan unit ${idx + 1} ${step.kind} DONE ${outcome.evidence.take(120)}"
                        )
                        collectedRefs.addAll(outcome.refs)
                        // 1.9.0 (v2): step DONE milestone.
                        emitPlanProgress(
                            current, idx, phase = "DONE",
                            etaSeconds = planEtaSeconds(stepDurationsMs, current.steps.size - idx - 1)
                        )
                        idx++
                    }
                    is StepOutcome.Failed -> {
                        // 1.11.0-PRO (self-extension, DESIGN §1 trigger
                        // point): after a unit Failed — BEFORE
                        // parkBlocked/finishPlan decides — try the
                        // loop once for a missing capability. The
                        // loop runs inside this same job, under the
                        // same timeout; it never launches its own
                        // scope. Goal text is NEVER passed (S8): the
                        // detector works from step kind/action/reason
                        // only, so goalText stays "".
                        // R1 (N3): the extension attempt IS fix
                        // attempt 1 for this unit (max 2 per unit).
                        if (step.id !in extendedAfterExtension && budget.noteFixAttempt(step.id)) {
                            var retryOutcome: StepOutcome? = null
                            val extOutcome = engine.planMaybeExtend(
                                failure = ExecutorFailure(
                                    stepKind = step.kind,
                                    action = step.kind,
                                    reason = outcome.reason,
                                    goalText = "",
                                    planId = current.planId
                                ),
                                runId = current.planId,
                                hasRealInput = true,
                                retryOriginal = {
                                    retryOutcome = executeStep(current.steps[idx], current)
                                    retryOutcome is StepOutcome.Done
                                },
                                stepRunner = { skillStep ->
                                    engine.planExecuteLearnedStep(
                                        LearnedStep(
                                            action = skillStep.action,
                                            target = skillStep.target,
                                            input = skillStep.input
                                        )
                                    )
                                }
                            )
                            when (extOutcome) {
                                is SelfExtensionLoop.LoopOutcome.ExtendedAndVerified -> {
                                    extendedAfterExtension.add(step.id)
                                    val done = retryOutcome as? StepOutcome.Done
                                    if (done != null) {
                                        // V3 passed: the retried step
                                        // is DONE — same bookkeeping
                                        // as the normal Done branch.
                                        stepDurationsMs.add(
                                            System.currentTimeMillis() - stepStartMs
                                        )
                                        current = updateStep(current, step.id) {
                                            it.copy(state = PlanState.DONE, evidence = done.evidence)
                                        }
                                        saveAndPublish(current)
                                        JarvisStateRepository.addChatMessage(
                                            "Jarvis",
                                            "✓ ${step.title} — ${done.evidence.take(200)}"
                                        )
                                        engine.planPersistChat()
                                        collectedRefs.addAll(done.refs)
                                        emitPlanProgress(
                                            current, idx, phase = "DONE",
                                            etaSeconds = planEtaSeconds(stepDurationsMs, current.steps.size - idx - 1)
                                        )
                                        idx++
                                        continue
                                    }
                                    // Retry reported success but left
                                    // no Done outcome — fall through
                                    // to the existing ask flow below.
                                }
                                is SelfExtensionLoop.LoopOutcome.ProposalFiled -> {
                                    // CODE skill: original step stays
                                    // BLOCKED, user told honestly
                                    // (the loop already posted its
                                    // line; parkBlocked reports it).
                                    parkBlocked(current, step, extOutcome.userLine)
                                    return
                                }
                                is SelfExtensionLoop.LoopOutcome.UserItem,
                                is SelfExtensionLoop.LoopOutcome.GiveUp -> Unit
                            }
                        }
                        // R1 (no-step): NO "dobara koshish karun?"
                        // ask — a mid-task ask is a stop, and
                        // mid-task stops are gone. CFD: one more fix
                        // attempt (max 2 per unit), else PARK +
                        // escalate to Main (§4.3).
                        if (budget.noteFixAttempt(step.id)) {
                            current = updateStep(current, step.id) { it.copy(state = PlanState.PENDING) }
                            saveAndPublish(current)
                            // Same idx — one re-run.
                        } else {
                            escalateToMain(
                                plan = current,
                                triedKinds = listOf(step.kind),
                                error = outcome.reason,
                                need = "'${step.title}' 2 baar fix nahi ho paya — is kaam ke liye code/capability chahiye."
                            )
                            parkBlocked(
                                current, step,
                                "'${step.title}' nahi ho paya (${outcome.reason.take(150)}). Main ko bhej diya hai — fix banega to bataunga."
                            )
                            return
                        }
                    }
                    is StepOutcome.Blocked -> {
                        // The question was already asked inside the
                        // step (missing info / gate pause) — park.
                        parkBlocked(current, step, outcome.reason)
                        return
                    }
                    is StepOutcome.Aborted -> {
                        // Decline: abortPlan already ran inside the
                        // step (plan terminal, user told). Just stop.
                        return
                    }
                }
            }
            finishPlan(current, allDone = true, refs = collectedRefs)
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

    /** 1.9.0 (v2, DESIGN §3.2): plan step milestone emission.
     *  Best-effort — never throws into the plan. */
    private fun emitPlanProgress(
        plan: AgentPlan,
        idx: Int,
        phase: String,
        etaSeconds: Long
    ) {
        val commandId = engine.planActiveCommandId() ?: return
        val step = plan.steps.getOrNull(idx) ?: return
        val total = plan.steps.size
        val fraction = when (phase) {
            "DONE" -> (idx + 1).toDouble() / total.toDouble()
            else -> idx.toDouble() / total.toDouble()
        }
        val nextLabel = if (idx + 1 < total) plan.steps[idx + 1].title else ""
        runCatching {
            engine.planReportProgress(
                ExecutorProgress(
                    commandId = commandId,
                    stepIndex = idx + 1,
                    stepTotal = total,
                    stepLabel = step.title,
                    detail = "${step.kind} ${plan.goal.take(60)}",
                    progressFraction = fraction,
                    etaSeconds = etaSeconds,
                    isMilestone = true,
                    phase = phase,
                    nextLabel = nextLabel
                )
            )
        }
    }

    /** ETA hint: average finished-step duration × remaining steps, in seconds (-1 when unknown). */
    private fun planEtaSeconds(finishedDurationsMs: List<Long>, remainingSteps: Int): Long {
        if (finishedDurationsMs.isEmpty() || remainingSteps <= 0) return -1L
        val avgMs = finishedDurationsMs.sum() / finishedDurationsMs.size
        return (avgMs * remainingSteps) / 1000L
    }

    /** ONE summary speak (replaces "Aur kya karna hai?") + chat
     *  lines + memory index. A plan ends only here (or via
     *  abort/park): every step DONE, or terminally BLOCKED/FAILED
     *  after asking.
     *
     *  1.9.0 (v2, DESIGN §4.5): the speak MOVES behind the
     *  verification gate for DONE. finishPlan posts the summary to
     *  chat (the plan's own log), persists the plan, then reports
     *  DONE with chatResult=true, speakResult=false and the typed
     *  refs — and lets the engine verify and speak. FAILED keeps
     *  speaking its own summary (it is not verified). */
    private suspend fun finishPlan(
        plan: AgentPlan,
        allDone: Boolean,
        refs: List<EvidenceRef> = emptyList()
    ) {
        lastFinishedPlan = plan
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
                append("Jo kaam ruka hai uske liye upar wali detail dekho, phir dobara bolo.")
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
            updatedAtMs = System.currentTimeMillis(),
            budget = taskBudget?.snapshot()
        )
        AgentPlanStore.save(context, completedPlan)
        publish(completedPlan)
        JarvisStateRepository.setAgentState(
            AgentRunState.IDLE,
            if (allDone) "Plan complete" else "Plan stopped"
        )
        JarvisStateRepository.setCurrentGoal("")
        engine.planClearTaskState()
        if (allDone) {
            // 1.9.0 (v2): DONE is chat-posted, NOT spoken here —
            // the verification gate speaks the verdict. Typed refs
            // travel on the result for the Talker's re-read.
            engine.planReportExecutorResult(
                ExecutorResultStatus.DONE,
                summary.take(300),
                "plan complete",
                speakResult = false, chatResult = true,
                evidenceJson = EvidenceRefs.toJson(refs)
            )
        } else {
            // FAILED is not verified — the summary is spoken here,
            // exactly as v1 did.
            engine.planReportExecutorResult(
                ExecutorResultStatus.FAILED,
                summary.take(300),
                "plan stopped: ${failed.joinToString(", ") { it.title }}",
                speakResult = false, chatResult = false
            )
            engine.planSpeak(summary)
        }
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
            updatedAtMs = now,
            budget = taskBudget?.snapshot()
        )
        AgentPlanStore.save(context, p)
        publish(p)
        JarvisStateRepository.addChatMessage("Jarvis", "Theek hai, plan rok diya — '${step.title}': $reason.")
        engine.planPersistChat()
        JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Plan stopped by user")
        JarvisStateRepository.setCurrentGoal("")
        engine.planClearTaskState()
        // 1.8.0: DECLINED, unannounced — already fully in chat AND
        // spoken below.
        engine.planReportExecutorResult(
            ExecutorResultStatus.DECLINED,
            "User ne plan rok diya — '${step.title}': $reason",
            "plan aborted", speakResult = false, chatResult = false
        )
        engine.planSpeak("Theek hai, plan rok diya.")
    }

    /** No reply to a question: the plan stays BLOCKED and
     *  persisted (resumable — the next service recreation re-asks
     *  once). No speak here: the question was already spoken. */
    private suspend fun parkBlocked(plan: AgentPlan, step: PlanStep, reason: String) {
        val context = engine.planContext()
        val p = updateStep(plan, step.id) {
            it.copy(state = PlanState.BLOCKED, evidence = "Blocked: ${reason.take(300)}")
        }.copy(
            currentStepId = null,
            updatedAtMs = System.currentTimeMillis(),
            budget = taskBudget?.snapshot()
        )
        AgentPlanStore.save(context, p)
        publish(p)
        // 1.8.0: BLOCKED, fully announced — nothing was spoken for
        // this park (the question was already spoken), so the
        // Talker must tell the user the plan is waiting.
        engine.planReportExecutorResult(
            ExecutorResultStatus.BLOCKED,
            reason.take(300),
            "plan parked BLOCKED"
        )
        JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Plan blocked — ${reason.take(80)}")
        JarvisStateRepository.setCurrentGoal("")
        engine.planClearTaskState()
    }

    /** R1 (§4.3): best-effort escalation to the Main Executor.
     *  Never throws, never blocks the park — a failed escalate is
     *  just a diagnostic; the PARK itself is the user-visible
     *  outcome. Packet carries goal + tried kinds + error only —
     *  never the user's personal content. */
    private suspend fun escalateToMain(
        plan: AgentPlan,
        triedKinds: List<String>,
        error: String,
        need: String
    ) {
        runCatching {
            val result = HubGithubClient.escalate(
                engine.planContext(),
                goal = plan.goal,
                tried = triedKinds,
                error = error,
                need = need
            )
            if (result is HubGithubClient.EscalateResult.Err) {
                JarvisStateRepository.addDiagnostic(
                    "plan", "ESCALATE_FAIL",
                    "Main ko escalate nahi ho paya: ${result.message.take(120)}"
                )
            }
        }
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

    /** R1 (no-step): the ONLY voice-ask primitive left in the
     *  executor. Called ONLY from (a) a hard gate (gateStep /
     *  destructive overwrite confirm) and (b) the PARK-resume path
     *  after the user re-engages. NEVER called mid-task for a
     *  clarification — those PARK (Blocked + Talker tells the
     *  user) instead of asking. One spoken question via askUserVoice
     *  (B-M8: never wait on an unspoken question). Sets
     *  WAITING_USER_REPLY while asking. */
    private suspend fun askUserOnce(question: String): AskResult {
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
            thought = "plan gate",
            action = if (payLike) "TYPE_TEXT" else "NONE",
            text = step.detail.ifBlank { step.title }
        )
        val (outcome, reason) = engine.planCheckGate(synthetic)
        when (outcome) {
            JarvisAgentEngine.GateOutcome.ABORT_TASK -> return GateResult.Abort
            JarvisAgentEngine.GateOutcome.PAUSE_TASK -> return GateResult.Pause("gate question nahi boli ja saki: $reason")
            JarvisAgentEngine.GateOutcome.BLOCK_STEP -> return GateResult.Block("Gate ne kaam block kiya: $reason")
            JarvisAgentEngine.GateOutcome.ALLOW -> Unit
        }
        if (step.needsGate) {
            return when (val ans = askUserOnce(
                "'${step.title}' sensitive hai — ${step.detail.take(120)}. Kar dun?"
            )) {
                is AskResult.Answered -> if (engine.planIsDecline(ans.answer)) GateResult.Abort else GateResult.Allow
                is AskResult.Declined -> GateResult.Abort
                is AskResult.NoReply -> GateResult.Pause("sensitive kaam ka sawal poocha, jawab nahi aaya")
            }
        }
        return GateResult.Allow
    }

    // ---- Step dispatch (R2.7: ToolRegistry resolve + trajectory) ----

    // R2.7: per-run trajectory, appended by executeStep and merged
    // into the persisted plan by updateStep (the post-execution
    // choke point). Cleared at every runPlan start so entries never
    // leak across plans. Tool name + outcome + verdict only — no
    // args, no content (mini-swe shape).
    private val runTrajectory = ArrayList<PlanTrajectoryEntry>()

    private suspend fun executeStep(step: PlanStep, plan: AgentPlan): StepOutcome {
        // R2.7: resolve through the registry first. An unknown kind
        // fails with the T-NO-HANDLER marker (the self-extension
        // detector keys on it) instead of silently doing nothing.
        val tool = ToolRegistry.resolve(step.kind)
        if (tool == null) {
            val outcome = StepOutcome.Failed("Unknown kaam kind: ${step.kind} (T-NO-HANDLER)")
            recordStepOutcome(step, outcome)
            return outcome
        }
        val outcome = when (step.kind) {
            "BUILD" -> executeBuild(step, plan)
            "GITHUB_PUSH" -> executeGithubPush(step, plan)
            "SUPABASE_TABLES" -> executeSupabaseTables(step, plan)
            "VERCEL_DEPLOY" -> executeVercelDeploy(step, plan)
            "HTTP_CHECK" -> executeHttpCheck(step)
            // Track E #27: Gmail/Drive via the hub's direct Google
            // OAuth (no tokens on the phone — hub calls Google).
            "GMAIL_SEND" -> executeGmailSend(step)
            "GMAIL_READ" -> executeGmailRead(step)
            "DRIVE_READ" -> executeDriveRead(step)
            // 1.15.0 (R0 Whop browser): headless WebView job — the
            // JobEngine drives a page by steps (goto/click/type/
            // extract/screenshot). Session/captcha/foreground failures
            // park as BLOCKED (user help needed), everything else Failed.
            "BROWSER_JOB" -> executeBrowserJob(step)
            // 1.26.0 (power features — spec §5.3): shell-first phone
            // automation (AccessibilityService is the fallback).
            "SHELL_TAP" -> executeShellTap(step)
            "SHELL_TYPE" -> executeShellType(step)
            "SHELL_SCREENSHOT" -> executeShellScreenshot(step)
            "SHELL_APP" -> executeShellApp(step)
            "SHELL_SETTING" -> executeShellSetting(step)
            // Unreachable after the registry resolve above (kept as a
            // safety net — see the T-NO-HANDLER comment there).
            else -> StepOutcome.Failed("Unknown kaam kind: ${step.kind} (T-NO-HANDLER)")
        }
        recordStepOutcome(step, outcome)
        return outcome
    }

    /**
     * R3.6: run ONE registry tool from Live mode (Gemini Live
     * function call). The kind is gate-checked through ToolRegistry:
     * only GateLevel.SAFE executes here; CONFIRM / SENSITIVE kinds
     * are REFUSED with an error string (PARK — same as the R1
     * no-step rule; the 5 hard gates never run from live mode).
     * The step is synthetic (no plan, no persistence — the result
     * goes back to the model as the function response, and the
     * engine logs one line). Returns evidence on success, or an
     * "ERROR: ..." string the model can speak.
     */
    suspend fun executeLiveTool(kind: String, detail: String): String {
        val tool = ToolRegistry.resolve(kind)
            ?: return "ERROR: unknown tool '$kind'"
        if (tool.gate != ToolRegistry.GateLevel.SAFE) {
            return "ERROR: '$kind' live mode se nahi chal sakta (gate: ${tool.gate}) — iske liye normal command do."
        }
        val step = PlanStep(
            id = "live-${System.currentTimeMillis()}",
            kind = kind, title = "live:$kind", detail = detail
        )
        return when (val outcome = when (kind) {
            "HTTP_CHECK" -> executeHttpCheck(step)
            "GMAIL_READ" -> executeGmailRead(step)
            "DRIVE_READ" -> executeDriveRead(step)
            // 1.26.0: the SAFE shell kinds run from live mode too
            // (SHELL_APP/SHELL_SETTING are CONFIRM — refused above).
            "SHELL_TAP" -> executeShellTap(step)
            "SHELL_TYPE" -> executeShellType(step)
            "SHELL_SCREENSHOT" -> executeShellScreenshot(step)
            else -> StepOutcome.Failed("Unknown kaam kind: $kind (T-NO-HANDLER)")
        }) {
            is StepOutcome.Done -> outcome.evidence
            is StepOutcome.Failed -> "ERROR: ${outcome.reason}"
            is StepOutcome.Blocked -> "ERROR (blocked): ${outcome.reason}"
            is StepOutcome.Aborted -> "ERROR: aborted"
        }
    }

    /**
     * R2.2 + R2.7: one executed step -> one trajectory line (tool +
     * outcome + verdict, no args/content) and one event-log line
     * (kind + result + verdict + short screen meaning, sanitised).
     * The trajectory is merged into the persisted plan by updateStep;
     * the event log is on-device only.
     */
    private fun recordStepOutcome(step: PlanStep, outcome: StepOutcome) {
        val (result, detail) = when (outcome) {
            is StepOutcome.Done -> "DONE" to outcome.evidence
            is StepOutcome.Failed -> "FAILED" to outcome.reason
            is StepOutcome.Blocked -> "BLOCKED" to outcome.reason
            // Decline / gate abort — abortPlan already ran inside the
            // step; there is no reason string to carry.
            is StepOutcome.Aborted -> "ABORTED" to "declined"
        }
        runTrajectory.add(
            PlanTrajectoryEntry(
                tool = step.kind,
                outcome = result,
                verdict = detail.take(120),
                tsMs = System.currentTimeMillis()
            )
        )
        val screenMeaning = when (outcome) {
            is StepOutcome.Done -> "${step.kind} ho gaya — ${detail.take(200)}"
            is StepOutcome.Failed -> "${step.kind} fail hua — ${detail.take(200)}"
            is StepOutcome.Blocked -> "${step.kind} ruka — ${detail.take(200)}"
            is StepOutcome.Aborted -> "${step.kind} ruka — user ne mana kiya"
        }
        JarvisEventLog.log(
            engine.planCurrentTurnId().ifBlank { "resume" },
            step.kind, result, detail.take(120), screenMeaning
        )
    }

    // ---- BUILD (existing builder loop, reused) ----

    private suspend fun executeBuild(step: PlanStep, plan: AgentPlan): StepOutcome {
        val context = engine.planContext()
        // MINOR-2: "?" is the planner's unknown-name sentinel, not a
        // name — treat it as missing. R1 (no-step): a missing name
        // is a PARK, never a mid-task ask — the Talker tells the
        // user, the Executor waits for the name.
        var projectName = parseDetailValue(step.detail, "project")?.takeUnless { it == "?" }
        if (projectName.isNullOrBlank()) projectName = engine.planLastBuildProject().takeIf { it.isNotBlank() }
        if (projectName.isNullOrBlank()) {
            return StepOutcome.Blocked("Website ka naam chahiye — naam bolo to aage badhaunga.")
        }
        projectName = ProjectStore.sanitizeProjectName(projectName!!)
        // Garbar rule (§3g.2): building into an existing non-empty
        // project overwrites index.html by design, so the executor
        // asks first even if the planner forgot needsGate.
        // R1: this ask STAYS — overwriting is a destructive_confirm
        // hard gate, one of the 5 allowed stops.
        val existingProject = ProjectStore.listProjects(context).firstOrNull { it.name == projectName }
        if (existingProject != null && existingProject.files.isNotEmpty()) {
            when (val ans = askUserOnce(
                "Project '$projectName' pehle se hai (${existingProject.files.size} files). Upar se dobara banau?"
            )) {
                is AskResult.Answered -> {
                    if (!engine.planIsYes(ans.answer)) {
                        // MINOR-1: a "no" to the overwrite question is
                        // a decline — abort the plan, do not park it.
                        abortPlan(plan, step, "User ne existing project overwrite karne se mana kar diya.")
                        return StepOutcome.Aborted
                    }
                }
                is AskResult.Declined -> {
                    abortPlan(plan, step, "User ne existing project overwrite karne se mana kar diya.")
                    return StepOutcome.Aborted
                }
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
        // R1 (N3): the build loop is budget-gated, not step-counted.
        // Every decideNextStep call is one LLM call against the task
        // budget (20 default / 40 hard); the watchdog is the time
        // bound. The loop runs until DONE (verified) or budget
        // exhausted — never "N steps me complete nahi hua".
        val budget = taskBudget ?: TaskBudget()
        var stepNo = 1
        var goal = "${plan.goal} (Project: $projectName — ${step.detail})"
        while (true) {
            if (!engine.planIsOnline()) return StepOutcome.Failed("Internet chala gaya — build ruk gaya.")
            if (budget.watchdogExpired()) {
                return StepOutcome.Failed("15 minute ho gaye — build samay par poora nahi ho paya.")
            }
            if (!budget.noteLlmCall()) {
                return StepOutcome.Failed("Kaam ka budget khatam ho gaya — build poora nahi ho paya. Dobara bolo to phir se koshish karunga.")
            }
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
            // R1 (no-step): a mid-task question from the builder is
            // a PARK, never a voice-wait — the Talker tells the user
            // the question, the Executor waits for the answer.
            if (decision.askUser) {
                return StepOutcome.Blocked(
                    "Build ke liye ek sawal hai: ${decision.reply.ifBlank { "batao?" }.take(200)} — batao to aage badhaunga."
                )
            }
            if (decision.action != "NONE") {
                val (gateOutcome, gateReason) = engine.planCheckGate(decision)
                if (gateOutcome != JarvisAgentEngine.GateOutcome.ALLOW) {
                    if (gateOutcome == JarvisAgentEngine.GateOutcome.ABORT_TASK) {
                        // MINOR-1: a gate ABORT_TASK is the user
                        // declining the gate confirmation — abort the
                        // plan, never Failed (which would re-ask
                        // "dobara koshish karun?").
                        abortPlan(plan, step, "Build gate ne roka: $gateReason")
                        return StepOutcome.Aborted
                    }
                    return StepOutcome.Failed("Build gate ne roka: $gateReason")
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
        // Unreachable in practice (the loop exits only via the
        // returns above) — kept so the function always returns.
        return StepOutcome.Failed("Build poora nahi ho paya.")
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
        // 1.9.0 (v2, DESIGN §4.2): typed refs — the Talker re-reads
        // the file list + preview instead of trusting this string.
        val refs = listOf(
            EvidenceRef(
                EvidenceKind.FILE_LIST, "project:$projectName", "files_present",
                files.joinToString(",")
            ),
            EvidenceRef(
                EvidenceKind.PREVIEW_HTTP, "preview:$projectName", "http_200",
                ProjectStore.localhostUrl(projectName)
            )
        )
        return StepOutcome.Done(
            "validation OK; files: ${files.joinToString(", ").take(200)}; preview ${ProjectStore.localhostUrl(projectName)}",
            refs = refs
        )
    }

    // ---- GITHUB_PUSH (via hub proxy — NO direct GitHub call) ----
    //
    // R1 (1.16.0-PRO, REDESIGN_PLAN.md §4.1): the phone holds no
    // GitHub token and calls api.github.com NEVER. The push goes
    // through HubGithubClient (POST /api/github/pr-request,
    // device-signed); the hub pushes with the ADMIN token. The hub
    // creates the repo when missing (private, user-given name only
    // — never invented). github_not_configured / 503 PARKS with a
    // plain-words message — never a crash, never a silent fail.

    private suspend fun executeGithubPush(step: PlanStep, plan: AgentPlan): StepOutcome {
        val context = engine.planContext()
        // MINOR-2: "repo=?" is the planner's unknown-name sentinel,
        // not a name. R1 (no-step): a missing name is a PARK, never
        // a mid-task ask — the Talker tells the user, the Executor
        // waits for the name.
        var repo = parseDetailValue(step.detail, "repo")?.takeUnless { it == "?" }
        if (repo.isNullOrBlank()) repo = resolveRepoFromClarifications(plan)
        if (repo.isNullOrBlank()) {
            return StepOutcome.Blocked("GitHub repo ka naam chahiye — naam bolo to aage badhaunga. (Naya repo banega to wahi naam use hoga.)")
        }
        repo = sanitizeRepoName(repo!!)
        // MINOR-2: blank-after-sanitize is still "missing" — park so
        // the resume path re-asks, never Failed("Repo naam khaali hai.").
        if (repo.isBlank()) return StepOutcome.Blocked("Repo naam khaali hai — naam batao to phir se koshish karunga.")
        val projectName = resolveProjectName(step, plan)
            ?: return StepOutcome.Blocked("Project naam nahi mila — pehle BUILD kaam complete hona chahiye.")
        val project = ProjectStore.listProjects(context).firstOrNull { it.name == projectName }
            ?: return StepOutcome.Failed("Project '$projectName' phone par nahi mila.")
        val files = project.files.filter { ProjectStore.readFile(context, projectName, it).isNotBlank() }
        if (files.isEmpty()) return StepOutcome.Failed("Project '$projectName' me koi file nahi mili.")
        return withContext(Dispatchers.IO) {
            val contents = files.associateWith { ProjectStore.readFile(context, projectName, it) }
            when (val result = HubGithubClient.pushProject(context, repo, projectName, contents)) {
                is HubGithubClient.PushResult.Ok -> {
                    // Full SHA in evidence (verify-before-re-run needs
                    // it for the tree fetch); display sites truncate.
                    // 1.9.0 (v2): typed GITHUB_COMMIT ref for the Talker.
                    StepOutcome.Done(
                        "commit ${result.sha}; repo https://github.com/${result.owner}/${result.repo}; branch ${result.branch}",
                        refs = listOf(
                            EvidenceRef(
                                EvidenceKind.GITHUB_COMMIT,
                                "github:${result.owner}/${result.repo}",
                                "commit_in_tree",
                                result.sha
                            )
                        )
                    )
                }
                is HubGithubClient.PushResult.NotConfigured -> StepOutcome.Blocked(
                    "GitHub hub par set nahi hai — admin ko hub me GitHub token lagana hoga. Tab tak GitHub push nahi ho payega."
                )
                is HubGithubClient.PushResult.Err -> StepOutcome.Failed(
                    "GitHub push fail: ${result.message.take(200)}"
                )
            }
        }
    }

    // ---- SUPABASE_TABLES (Management API, SQL from built schema.sql) ----

    private suspend fun executeSupabaseTables(step: PlanStep, plan: AgentPlan): StepOutcome {
        val context = engine.planContext()
        val projectName = resolveProjectName(step, plan)
            ?: return StepOutcome.Blocked("Project naam nahi mila — pehle BUILD kaam complete hona chahiye.")
        val schema = ProjectStore.readFile(context, projectName, "backend/schema.sql")
        if (schema.isBlank()) {
            return StepOutcome.Failed("backend/schema.sql nahi mila — BUILD kaam me backend files bane hone chahiye.")
        }
        // 1.4.22 (item 5a): expected schema is table -> columns,
        // not just table names — verification compares column sets.
        val expected = parseSchemaColumns(schema)
        if (expected.isEmpty()) {
            return StepOutcome.Failed("schema.sql me koi CREATE TABLE nahi mila — schema invent nahi kar sakta.")
        }
        return withContext(Dispatchers.IO) {
            // 1.4.22 (item 5b): token reads are blocking keystore /
            // disk I/O — they happen on IO, never on Main.
            val token = AgentSecrets.get(context, AgentSecrets.KEY_SUPABASE_PAT)
            if (token.isBlank()) {
                return@withContext StepOutcome.Blocked(
                    "Supabase PAT saved nahi hai — AI Brain > Agent Mode me Supabase PAT daalo."
                )
            }
            val ref = AgentSecrets.get(context, AgentSecrets.KEY_SUPABASE_PROJECT_REF)
            if (ref.isBlank()) {
                return@withContext StepOutcome.Blocked(
                    "Supabase project ref khaali hai — AI Brain > Agent Mode me project ref daalo (Project Settings se)."
                )
            }
            try {
                // One retry per statement, network/5xx only — a
                // 4xx is the true answer (bad SQL / bad scope).
                for (stmt in splitSqlStatements(schema)) {
                    withOneRetryOnNetworkOr5xx {
                        val (code, body) = supabaseQuery(token, ref, stmt)
                        requireOk(code, body, "Supabase SQL")
                    }
                }
                val verified = verifySupabaseTablesNow(token, ref, expected)
                if (verified == null) {
                    val detail = lastSupabaseVerifyMismatch
                        ?: "tables missing/empty: ${expected.keys.joinToString(", ")}"
                    return@withContext StepOutcome.Failed(
                        "Supabase verify fail: $detail. BUILD dobara chalao ya schema.sql check karo."
                    )
                }
                // 1.9.0 (v2): typed SUPABASE_TABLES ref for the Talker.
                val tablesValue = expected.entries.joinToString(";") { (table, cols) ->
                    "$table:${cols.joinToString(",")}"
                }
                StepOutcome.Done(
                    "tables: ${expected.keys.joinToString(", ")} (project $ref)",
                    refs = listOf(
                        EvidenceRef(
                            EvidenceKind.SUPABASE_TABLES, "supabase:$ref",
                            "columns_present", tablesValue
                        )
                    )
                )
            } catch (e: IllegalStateException) {
                StepOutcome.Failed("Supabase ${e.message?.take(200)}")
            } catch (e: IOException) {
                StepOutcome.Failed("Supabase network error: ${(e.message ?: "").take(150)}")
            } catch (e: Exception) {
                StepOutcome.Failed("Supabase error: ${(e.message ?: "").take(150)}")
            }
        }
    }

    /** Runs the verification query; returns the per-table live
     *  column sets, or null when it cannot be confirmed.
     *  1.4.22 (item 5a): exact set equality per table against the
     *  expected schema — a missing/extra column is a mismatch,
     *  logged as SUPABASE_VERIFY_COLUMNS (table + column names
     *  only, no data) and returned as null ("cannot confirm"). */
    private fun verifySupabaseTablesNow(
        token: String,
        ref: String,
        expected: Map<String, Set<String>>
    ): Map<String, Set<String>>? {
        lastSupabaseVerifyMismatch = null
        return try {
            withOneRetryOnNetworkOr5xx {
                val inList = expected.keys.joinToString(",") { "'$it'" }
                val sql = "SELECT table_name, column_name FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name IN ($inList)"
                val (code, body) = supabaseQuery(token, ref, sql)
                requireOk(code, body, "Supabase verify")
                val live = parseInformationSchemaColumns(body)
                for ((table, expectedCols) in expected) {
                    val liveCols = live[table] ?: emptySet()
                    if (liveCols != expectedCols) {
                        val missing = (expectedCols - liveCols).sorted()
                        val extra = (liveCols - expectedCols).sorted()
                        val detail = buildString {
                            append("$table ke columns schema se match nahi karte (missing: ${missing.joinToString(", ").ifBlank { "—" }}")
                            if (extra.isNotEmpty()) append("; extra: ${extra.joinToString(", ")}")
                            append(")")
                        }
                        lastSupabaseVerifyMismatch = detail
                        JarvisStateRepository.addDiagnostic(
                            "supabase", "SUPABASE_VERIFY_COLUMNS", detail
                        )
                        return@withOneRetryOnNetworkOr5xx null
                    }
                }
                live
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

    /** Live column sets per table (table + column names
     *  lowercased — Postgres folds unquoted identifiers; the
     *  builder emits lowercase).
     *  1.4.22 (item 5a): was per-table row counts only. */
    private fun parseInformationSchemaColumns(body: String): Map<String, Set<String>> {
        val columns = mutableMapOf<String, MutableSet<String>>()
        fun absorb(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val t = obj.optString("table_name", "").lowercase()
                val c = obj.optString("column_name", "").lowercase()
                if (t.isNotBlank() && c.isNotBlank()) {
                    columns.getOrPut(t) { mutableSetOf() }.add(c)
                }
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
        return columns
    }

    // ---- VERCEL_DEPLOY (REST API, project linked to the GitHub repo) ----

    private suspend fun executeVercelDeploy(step: PlanStep, plan: AgentPlan): StepOutcome {
        val context = engine.planContext()
        val pushStep = plan.steps.firstOrNull { it.kind == "GITHUB_PUSH" }
        if (pushStep == null || pushStep.state != PlanState.DONE) {
            return StepOutcome.Failed("GitHub push pehle complete hona chahiye — Vercel deploy uske bina nahi ho sakta.")
        }
        val (owner, repo) = parseGithubRepo(pushStep)
            ?: return StepOutcome.Failed("GitHub push ka repo nahi mila — push ki evidence check karo.")
        val branch = parseEvidenceValue(pushStep.evidence, "branch") ?: "main"
        val projectName = resolveProjectName(step, plan) ?: repo
        val vercelName = sanitizeVercelName(projectName)
        return withContext(Dispatchers.IO) {
            // 1.4.22 (item 5b): token read is blocking keystore /
            // disk I/O — it happens on IO, never on Main.
            val token = AgentSecrets.get(context, AgentSecrets.KEY_VERCEL_TOKEN)
            if (token.isBlank()) {
                return@withContext StepOutcome.Blocked(
                    "Vercel token saved nahi hai — AI Brain > Agent Mode me Vercel token daalo (Settings → Tokens)."
                )
            }
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
                // 2. GitHub repo id (needed for gitSource) — R1:
                // via the hub proxy, never a direct GitHub call and
                // never a phone-side token.
                val repoId = when (val fetch = HubGithubClient.fetch(
                    context, owner, repo, "repos/$owner/$repo"
                )) {
                    is HubGithubClient.FetchResult.Ok -> fetch.body.optLong("id", 0)
                    is HubGithubClient.FetchResult.NotConfigured -> {
                        return@withContext StepOutcome.Failed(
                            "GitHub hub par set nahi hai — admin ko hub me GitHub token lagana hoga. Tab tak Vercel deploy nahi ho payega."
                        )
                    }
                    is HubGithubClient.FetchResult.Err -> {
                        return@withContext StepOutcome.Failed(
                            "GitHub repo id nahi mila (hub: ${fetch.message.take(150)})."
                        )
                    }
                }
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
                            // 1.9.0 (v2): typed VERCEL_DEPLOY ref for the Talker.
                            return@withContext StepOutcome.Done(
                                "READY; url=https://$deploymentUrl; id=$deploymentId",
                                refs = listOf(
                                    EvidenceRef(
                                        EvidenceKind.VERCEL_DEPLOY,
                                        "vercel:$deploymentId",
                                        "deployment_ready",
                                        "https://$deploymentUrl"
                                    )
                                )
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

    // ---- Google steps (Track E #27) ----
    //
    // Detail format (parseDetailValue = no spaces; parseDetailRest =
    // rest-of-string up to ';'):
    //   GMAIL_SEND: "to=a@b.com; subject=Hi there; body=hello world"
    //     (body LAST — it may contain spaces; ';' ends it)
    //   GMAIL_READ: "q=from:x subject:y; max=5"
    //   DRIVE_READ: "file_id=<drive file id>"
    // GMAIL_SEND is a mutating send: the existing gateStep runs
    // before executeStep for every step, and PAY_WORDS already
    // contains "send"/"bhej" — a step whose title/detail says send
    // gets the gate's sensitive confirmation. No Google token lives
    // on the phone; every call is hub-signed (GoogleClient).
    // No Google account -> Blocked with the exact AI Brain path
    // (same wording discipline as the GitHub PAT Blocked message).

    private suspend fun executeGmailSend(step: PlanStep): StepOutcome {
        val to = parseDetailValue(step.detail, "to")
            ?: return StepOutcome.Failed("GMAIL_SEND me to= (email) nahi diya gaya.")
        val subject = parseDetailRest(step.detail, "subject").orEmpty()
        val body = parseDetailRest(step.detail, "body").orEmpty()
        val context = engine.planContext()
        return when (val r = GoogleClient.gmailSend(context, to, subject, body)) {
            is GoogleClient.SendResult.Ok -> StepOutcome.Done("Gmail sent (id ${r.messageId}) to $to")
            is GoogleClient.SendResult.Err -> {
                if (r.message.contains("connect nahi hai")) StepOutcome.Blocked(r.message)
                else StepOutcome.Failed(r.message.take(200))
            }
        }
    }

    private suspend fun executeGmailRead(step: PlanStep): StepOutcome {
        val q = parseDetailRest(step.detail, "q")
        val max = parseDetailValue(step.detail, "max")?.toIntOrNull()?.coerceIn(1, 10) ?: 5
        val context = engine.planContext()
        return when (val r = GoogleClient.gmailRead(context, q, max)) {
            is GoogleClient.ReadResult.Ok -> {
                if (r.messages.isEmpty()) {
                    StepOutcome.Done("Gmail: koi message nahi mila" + if (!q.isNullOrBlank()) " (q=$q)" else "")
                } else {
                    val evidence = r.messages.joinToString("; ") { "'${it.subject}' (${it.from})" }.take(500)
                    StepOutcome.Done("Gmail: ${r.messages.size} message — $evidence")
                }
            }
            is GoogleClient.ReadResult.Err -> {
                if (r.message.contains("connect nahi hai")) StepOutcome.Blocked(r.message)
                else StepOutcome.Failed(r.message.take(200))
            }
        }
    }

    private suspend fun executeDriveRead(step: PlanStep): StepOutcome {
        val fileId = parseDetailValue(step.detail, "file_id")
            ?: return StepOutcome.Failed("DRIVE_READ me file_id nahi diya gaya.")
        val context = engine.planContext()
        return when (val r = GoogleClient.driveRead(context, fileId)) {
            is GoogleClient.DriveResult.Ok -> {
                val note = r.file.contentNote
                if (note != null) {
                    StepOutcome.Done("Drive: '${r.file.name}' (${r.file.mimeType}) — content nahi mila: $note")
                } else {
                    val preview = (r.file.text.orEmpty()).take(500)
                    StepOutcome.Done("Drive: '${r.file.name}' (${r.file.mimeType}) — $preview")
                }
            }
            is GoogleClient.DriveResult.Err -> {
                if (r.message.contains("connect nahi hai")) StepOutcome.Blocked(r.message)
                else StepOutcome.Failed(r.message.take(200))
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

    // ---- SHELL_* (1.26.0 power features — spec §5.3) ----
    //
    // Shell first, AccessibilityService as fallback — shell is
    // faster and works even when Accessibility is off, and the
    // fallback keeps a tap/type working when Shizuku dies mid-run.
    // Shizuku not Ready is BLOCKED (the plan parks so the user can
    // start the server), never a terminal Failed — except when the
    // Accessibility fallback already did the work.

    private suspend fun executeShellTap(step: PlanStep): StepOutcome {
        val x = parseDetailValue(step.detail, "x")?.toIntOrNull()
        val y = parseDetailValue(step.detail, "y")?.toIntOrNull()
        if (x == null || y == null) {
            return StepOutcome.Failed("SHELL_TAP me x aur y chahiye (x=123 y=456).")
        }
        if (ShizukuManager.state.value is ShizukuManager.ShizukuState.Ready) {
            val r = ShizukuManager.exec(ShellAutomation.tapCommand(x, y))
            if (r.isSuccess) return StepOutcome.Done("tap ($x,$y)")
        }
        val acc = JarvisAccessibilityService.instance
        if (acc != null && runCatching { acc.tapCoordinates(x, y) }.getOrDefault(false)) {
            return StepOutcome.Done("tap ($x,$y) (Accessibility se)")
        }
        return StepOutcome.Blocked(
            "Tap nahi hua — Shizuku ready nahi hai aur Accessibility bhi nahi chal raha. " +
                "Shizuku server start karo ya Accessibility on karo, phir dobara try karo."
        )
    }

    private suspend fun executeShellType(step: PlanStep): StepOutcome {
        val text = parseDetailRest(step.detail, "text")
            ?: return StepOutcome.Failed("SHELL_TYPE me text chahiye (text=...).")
        if (ShizukuManager.state.value is ShizukuManager.ShizukuState.Ready) {
            val r = ShizukuManager.exec(ShellAutomation.typeCommand(text))
            if (r.isSuccess) return StepOutcome.Done("typed ${text.length} chars")
        }
        val acc = JarvisAccessibilityService.instance
        if (acc != null && runCatching { acc.typeText(null, text, pressEnter = false) }.getOrDefault(false)) {
            return StepOutcome.Done("typed ${text.length} chars (Accessibility se)")
        }
        return StepOutcome.Blocked(
            "Type nahi hua — Shizuku ready nahi hai aur Accessibility bhi nahi chal raha. " +
                "Shizuku server start karo ya Accessibility on karo, phir dobara try karo."
        )
    }

    private suspend fun executeShellScreenshot(step: PlanStep): StepOutcome {
        val path = parseDetailValue(step.detail, "path")
            ?: "/sdcard/Jarvis/screenshots/jarvis-${System.currentTimeMillis()}.png"
        if (!FilePower.isWritablePath(path)) {
            return StepOutcome.Failed("SHELL_SCREENSHOT path likhne layak nahi hai: $path")
        }
        if (ShizukuManager.state.value !is ShizukuManager.ShizukuState.Ready) {
            return StepOutcome.Blocked(
                "Screenshot ke liye Shizuku chahiye — Shizuku server start karo, phir dobara try karo."
            )
        }
        val r = ShizukuManager.exec(ShellAutomation.screenshotCommand(path), 30_000)
        return r.fold(
            onSuccess = { StepOutcome.Done("screenshot: $path") },
            onFailure = { e -> StepOutcome.Failed("Screenshot nahi hua: ${e.message?.take(150)}") }
        )
    }

    private suspend fun executeShellApp(step: PlanStep): StepOutcome {
        val action = parseDetailValue(step.detail, "action")?.lowercase()
            ?: return StepOutcome.Failed("SHELL_APP me action chahiye (launch/install/enable/disable/uninstall/clear/force-stop).")
        val pkg = parseDetailValue(step.detail, "package")
            ?: return StepOutcome.Failed("SHELL_APP me package chahiye (package=com.example.app).")
        if (!ShellAutomation.isValidPackage(pkg)) {
            return StepOutcome.Failed("SHELL_APP package galat hai: $pkg")
        }
        // §6 rule 6: destructive app actions are NEVER silent — if
        // the step did not go through the gate (needsGate=false),
        // park as Blocked instead of running them.
        val destructive = action in setOf("uninstall", "clear", "disable")
        if (destructive && !step.needsGate) {
            return StepOutcome.Blocked(
                "'$action $pkg' destructive hai — bina confirmation ke nahi karunga. " +
                    "Confirm karo to phir se chalao."
            )
        }
        if (ShizukuManager.state.value !is ShizukuManager.ShizukuState.Ready) {
            // Launch has an Accessibility fallback (it just opens the
            // app — no shell needed). Every other app action needs
            // the shell, so those park as Blocked.
            if (action == "launch") {
                val acc = JarvisAccessibilityService.instance
                if (acc != null && runCatching { acc.launchApp(pkg) }.getOrDefault(false)) {
                    return StepOutcome.Done("launch $pkg (Accessibility se)")
                }
            }
            return StepOutcome.Blocked(
                "App action ke liye Shizuku chahiye — Shizuku server start karo, phir dobara try karo."
            )
        }
        val cmd = when (action) {
            "launch" -> ShellAutomation.launchAppCommand(pkg)
            "install" -> {
                val apk = parseDetailValue(step.detail, "apk_path")
                    ?: return StepOutcome.Failed("SHELL_APP install me apk_path chahiye.")
                // An APK can only come from shared storage or the
                // staging dir — never from a blocked/private path.
                if (FilePower.isBlockedPath(apk) ||
                    !(apk.startsWith("/sdcard") || apk.startsWith("/data/local/tmp"))
                ) {
                    return StepOutcome.Failed("SHELL_APP apk_path allowed nahi hai: $apk")
                }
                ShellAutomation.installAppCommand(apk)
            }
            "enable" -> ShellAutomation.enableAppCommand(pkg)
            "disable" -> ShellAutomation.disableAppCommand(pkg)
            "uninstall" -> ShellAutomation.uninstallAppCommand(pkg)
            "clear" -> ShellAutomation.clearAppCommand(pkg)
            "force-stop" -> ShellAutomation.forceStopAppCommand(pkg)
            else -> return StepOutcome.Failed("SHELL_APP action galat hai: $action")
        }
        val r = ShizukuManager.exec(cmd, 60_000)
        return r.fold(
            onSuccess = { StepOutcome.Done("$action $pkg") },
            onFailure = { e -> StepOutcome.Failed("$action $pkg fail hua: ${e.message?.take(200)}") }
        )
    }

    private suspend fun executeShellSetting(step: PlanStep): StepOutcome {
        val setting = parseDetailValue(step.detail, "setting")?.lowercase()
            ?: return StepOutcome.Failed("SHELL_SETTING me setting chahiye (brightness/volume/night/power_saver/dnd/wifi/data/bluetooth/airplane).")
        val value = parseDetailValue(step.detail, "value")
            ?: return StepOutcome.Failed("SHELL_SETTING me value chahiye.")
        val cmd = when (setting) {
            "brightness" -> {
                val pct = value.toIntOrNull()
                    ?: return StepOutcome.Failed("brightness value 0-100 hona chahiye: $value")
                ShellAutomation.brightnessCommand(pct)
            }
            "volume" -> {
                val level = value.toIntOrNull()
                    ?: return StepOutcome.Failed("volume value 0-15 hona chahiye: $value")
                ShellAutomation.volumeCommand(3, level)
            }
            "night" -> ShellAutomation.nightModeCommand(value.equals("on", ignoreCase = true))
            "power_saver" -> ShellAutomation.powerSaverCommand(value.equals("on", ignoreCase = true))
            "dnd" -> ShellAutomation.dndCommand(value.equals("on", ignoreCase = true))
            "wifi" -> ShellAutomation.wifiCommand(value.equals("on", ignoreCase = true))
            "data" -> ShellAutomation.mobileDataCommand(value.equals("on", ignoreCase = true))
            "bluetooth" -> ShellAutomation.bluetoothCommand(value.equals("on", ignoreCase = true))
            "airplane" -> ShellAutomation.airplaneModeCommand(value.equals("on", ignoreCase = true))
            else -> return StepOutcome.Failed("SHELL_SETTING setting galat hai: $setting")
        }
        if (ShizukuManager.state.value !is ShizukuManager.ShizukuState.Ready) {
            return StepOutcome.Blocked(
                "Setting badalne ke liye Shizuku chahiye — Shizuku server start karo, phir dobara try karo."
            )
        }
        val r = ShizukuManager.exec(cmd)
        return r.fold(
            onSuccess = { StepOutcome.Done("$setting=$value") },
            onFailure = { e -> StepOutcome.Failed("$setting=$value fail hua: ${e.message?.take(200)}") }
        )
    }

    // ---- BROWSER_JOB (1.15.0 R0 Whop browser) ----

    /**
     * Headless WebView job, driven by the step array in the detail:
     * `steps=<JSON array>` (each step: {"action":"goto"|"click_text"|
     * "type"|"extract"|"screenshot"|"wait"|"wait_text"|"assert_text"|
     * "js"|"upload", ...}). Optional `login_url=<url>` (+
     * `login_cookies=<a,b>` names) makes the engine fail fast with
     * session_expired when the user is not logged in — the user then
     * logs in once in the Browser tab and the plan resumes.
     *
     * Outcome mapping (honest): session/captcha/foreground failures
     * are BLOCKED — the executor cannot fix them alone, the user (or
     * a foreground host in R3) must. A job that ran but proved nothing
     * is Failed, never Done. Done evidence carries the final URL and
     * the first extract, truncated.
     */
    private suspend fun executeBrowserJob(step: PlanStep): StepOutcome {
        val stepsJson = parseDetailRest(step.detail, "steps")
            ?: return StepOutcome.Failed("BROWSER_JOB me steps nahi diye gaye (steps=<JSON array>).")
        val steps = runCatching { JSONArray(stepsJson) }.getOrNull()
            ?: return StepOutcome.Failed("BROWSER_JOB steps JSON parse nahi hua.")
        val job = JSONObject().apply {
            put("id", step.id)
            put("steps", steps)
            parseDetailValue(step.detail, "login_url")?.let { put("login_url", it) }
            parseDetailRest(step.detail, "login_cookies")?.let { raw ->
                val names = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (names.isNotEmpty()) put("login_cookies", JSONArray(names))
            }
        }
        return try {
            val jobEngine = JarvisJobEngine(engine.planContext())
            // R3.4: when the Browser tab is VISIBLE, the JobEngine
            // drives the ACTIVE tab's DWebView — the user watches
            // Jarvis work live in the Browser tab. When the tab is
            // NOT visible, the JobEngine stays headless (its own
            // DWebView) so a background job never hijacks the page
            // the user is reading (R0 no-hijack guarantee).
            val external = if (engine.isBrowserTabVisibleNow()) {
                JarvisBackgroundBrowser.activeTabDWebView(engine.planContext())
            } else {
                null
            }
            val result = jobEngine.run(job, externalWebView = external)
            val finalUrl = result.optString("finalUrl", "")
            val extract = result.optJSONObject("extract")?.toString()?.take(300) ?: ""
            StepOutcome.Done(
                "Browser job OK — ${result.optInt("steps", steps.length())} steps" +
                    if (finalUrl.isNotBlank()) "; final URL: $finalUrl" else "" +
                    if (extract.isNotBlank()) "; extract: $extract" else ""
            )
        } catch (e: JarvisJobEngine.JobFailed) {
            val msg = e.message ?: "browser job fail hua"
            val lower = msg.lowercase()
            if (lower.contains("session_expired") || lower.contains("captcha") ||
                lower.contains("needs_foreground") || lower.contains("login")
            ) {
                StepOutcome.Blocked(msg.take(300))
            } else {
                StepOutcome.Failed(msg.take(300))
            }
        } catch (e: Exception) {
            StepOutcome.Failed("Browser job fail: ${(e.message ?: e.javaClass.simpleName).take(200)}")
        }
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
            // Track E #27: GMAIL_READ re-checks live (query returns
            // >=1 message). GMAIL_SEND / DRIVE_READ are not
            // re-verifiable without side effects — null, like the
            // other mutating steps.
            "GMAIL_READ" -> {
                val context = engine.planContext()
                val q = parseDetailRest(step.detail, "q")
                val max = parseDetailValue(step.detail, "max")?.toIntOrNull()?.coerceIn(1, 10) ?: 5
                when (val r = GoogleClient.gmailRead(context, q, max)) {
                    is GoogleClient.ReadResult.Ok ->
                        if (r.messages.isNotEmpty()) StepOutcome.Done("Gmail: ${r.messages.size} message mil gaye") else null
                    is GoogleClient.ReadResult.Err -> null
                }
            }
            else -> null
        }
    }

    private suspend fun verifyGithubPush(step: PlanStep, plan: AgentPlan): StepOutcome? {
        return withContext(Dispatchers.IO) {
            try {
                // R1: verify via the hub proxy — no phone-side token,
                // no direct GitHub call. NotConfigured / Err = cannot
                // verify (null) — the caller then executes the push,
                // which parks with the plain-words message.
                val sha = parseEvidenceValue(step.evidence, "commit") ?: return@withContext null
                val (owner, repo) = parseGithubRepo(step) ?: return@withContext null
                val projectName = resolveProjectName(step, plan) ?: return@withContext null
                val expected = ProjectStore.listProjects(engine.planContext())
                    .firstOrNull { it.name == projectName }?.files ?: return@withContext null
                val fetch = HubGithubClient.fetch(
                    engine.planContext(), owner, repo,
                    "repos/$owner/$repo/git/trees/$sha?recursive=1"
                )
                val body = when (fetch) {
                    is HubGithubClient.FetchResult.Ok -> fetch.body
                    else -> return@withContext null
                }
                val present = mutableSetOf<String>()
                val treeArr = body.optJSONArray("tree")
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
                val expected = parseSchemaColumns(schema)
                if (expected.isEmpty()) return@withContext null
                val counts = verifySupabaseTablesNow(token, ref, expected) ?: return@withContext null
                if (counts.isEmpty()) return@withContext null
                StepOutcome.Done("verified after kill: tables: ${expected.keys.joinToString(", ")} (project $ref)")
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

    // ---- 1.9.0 (v2) Talker verification: fix + typed re-reads ----

    /**
     * FIX_REQUEST handler (DESIGN §4.6): re-run ONLY the step(s)
     * that own the failed refs, using the existing `executeStep`
     * for that step kind, then re-run that step's `verify*` via the
     * step's own Done outcome (which carries fresh refs). It does
     * not re-run unrelated DONE steps and never re-asks an answered
     * question (the `answeredClarifications` list still applies —
     * executeStep reads it through the engine). A step whose fix
     * outcome is Failed / Blocked / Aborted leaves its ref(s) in
     * STILL_MISSING. Runs on the engine's fix job (bounded by
     * min(5 min, remaining time) by the caller).
     */
    suspend fun fixMissingRefs(
        missingRefs: List<String>,
        commandId: String,
        attempt: Int
    ): ExecutorFixResult {
        val plan = lastFinishedPlan
            ?: return ExecutorFixResult(
                commandId = commandId,
                attempt = attempt,
                status = FixResultStatus.STILL_MISSING,
                missingRefs = missingRefs,
                detail = "no finished plan to fix"
            )
        var current = plan
        val stillMissing = ArrayList<String>()
        val freshRefs = ArrayList<EvidenceRef>()
        val fixedLabels = ArrayList<String>()
        for (refId in missingRefs) {
            val stepKind = when {
                refId.startsWith("preview_http:") || refId.startsWith("file_list:") -> "BUILD"
                refId.startsWith("github_commit:") -> "GITHUB_PUSH"
                refId.startsWith("supabase_tables:") -> "SUPABASE_TABLES"
                refId.startsWith("vercel_deploy:") -> "VERCEL_DEPLOY"
                else -> null
            }
            if (stepKind == null) {
                stillMissing.add(refId)
                continue
            }
            val step = current.steps.firstOrNull { it.kind == stepKind }
            if (step == null) {
                stillMissing.add(refId)
                continue
            }
            val outcome = runCatching { executeStep(step, current) }.getOrElse { e ->
                StepOutcome.Failed(
                    "fix error: ${(e.message ?: e.javaClass.simpleName).take(100)}"
                )
            }
            when (outcome) {
                is StepOutcome.Done -> {
                    current = updateStep(current, step.id) {
                        it.copy(state = PlanState.DONE, evidence = outcome.evidence)
                    }
                    saveAndPublish(current)
                    freshRefs.addAll(outcome.refs)
                    fixedLabels.add(step.title)
                }
                else -> stillMissing.add(refId)
            }
        }
        lastFinishedPlan = current
        return if (stillMissing.isEmpty()) {
            ExecutorFixResult(
                commandId = commandId,
                attempt = attempt,
                status = FixResultStatus.FIXED,
                freshEvidenceJson = EvidenceRefs.toJson(
                    freshRefs.distinctBy { it.kind to it.ref }
                ),
                detail = "fixed: ${fixedLabels.joinToString(", ")}".take(200)
            )
        } else {
            ExecutorFixResult(
                commandId = commandId,
                attempt = attempt,
                status = FixResultStatus.STILL_MISSING,
                missingRefs = stillMissing,
                detail = "still missing: ${stillMissing.size} ref(s)"
            )
        }
    }

    /** Files of the last finished plan's BUILD project (the expected
     *  set for the GitHub tree re-read). Empty when unknown — the
     *  typed check then proves only that the commit exists. */
    internal fun planBuildProjectFiles(): List<String> {
        val plan = lastFinishedPlan ?: return emptyList()
        val project = resolveProjectName(
            plan.steps.firstOrNull { it.kind == "BUILD" } ?: return emptyList(), plan
        ) ?: return emptyList()
        return ProjectStore.listProjects(engine.planContext())
            .firstOrNull { it.name == project }?.files ?: emptyList()
    }

    /**
     * Typed GitHub re-read (DESIGN §4.3): the same live tree fetch
     * `verifyGithubPush` performs, driven by typed ref fields
     * instead of regex-parsed free text. R1: via the hub proxy —
     * no phone-side token, no direct GitHub call. The regex path
     * stays for the kill-resume flow.
     */
    internal suspend fun verifyGithubCommitTyped(
        owner: String,
        repo: String,
        sha: String,
        expectedFiles: List<String>
    ): TalkerVerificationPolicy.RefOutcome {
        return withContext(Dispatchers.IO) {
            try {
                val fetch = HubGithubClient.fetch(
                    engine.planContext(), owner, repo,
                    "repos/$owner/$repo/git/trees/$sha?recursive=1"
                )
                if (fetch is HubGithubClient.FetchResult.NotConfigured) {
                    return@withContext TalkerVerificationPolicy.RefOutcome.UNCHECKABLE
                }
                if (fetch is HubGithubClient.FetchResult.Err) {
                    return@withContext TalkerVerificationPolicy.RefOutcome.UNCHECKABLE
                }
                fetch as HubGithubClient.FetchResult.Ok
                if (fetch.status == 404) {
                    return@withContext TalkerVerificationPolicy.RefOutcome.FAILED
                }
                if (expectedFiles.isEmpty()) return@withContext TalkerVerificationPolicy.RefOutcome.PASSED
                val body = fetch.body
                val present = mutableSetOf<String>()
                val treeArr = body.optJSONArray("tree")
                if (treeArr != null) {
                    for (i in 0 until treeArr.length()) {
                        treeArr.optJSONObject(i)?.optString("path", "")
                            ?.takeIf { it.isNotBlank() }?.let { present.add(it) }
                    }
                }
                if (expectedFiles.all { it in present }) {
                    TalkerVerificationPolicy.RefOutcome.PASSED
                } else {
                    TalkerVerificationPolicy.RefOutcome.FAILED
                }
            } catch (e: Exception) {
                TalkerVerificationPolicy.RefOutcome.UNCHECKABLE
            }
        }
    }

    /**
     * Typed Supabase re-read (DESIGN §4.3): `verifySupabaseTablesNow`
     * against the typed expected columns. A null result WITH a
     * recorded column mismatch is a positive failure; a null result
     * WITHOUT one (network / auth) is UNCHECKABLE.
     */
    internal suspend fun verifySupabaseTablesTyped(
        projectRef: String,
        expected: Map<String, Set<String>>
    ): TalkerVerificationPolicy.RefOutcome {
        return withContext(Dispatchers.IO) {
            try {
                val token = AgentSecrets.get(engine.planContext(), AgentSecrets.KEY_SUPABASE_PAT)
                if (token.isBlank()) return@withContext TalkerVerificationPolicy.RefOutcome.UNCHECKABLE
                val verified = verifySupabaseTablesNow(token, projectRef, expected)
                if (verified != null) return@withContext TalkerVerificationPolicy.RefOutcome.PASSED
                if (lastSupabaseVerifyMismatch != null) {
                    TalkerVerificationPolicy.RefOutcome.FAILED
                } else {
                    TalkerVerificationPolicy.RefOutcome.UNCHECKABLE
                }
            } catch (e: Exception) {
                TalkerVerificationPolicy.RefOutcome.UNCHECKABLE
            }
        }
    }

    /** Typed Vercel re-read (DESIGN §4.3): deployment READY + live URL 200. */
    internal suspend fun verifyVercelDeployTyped(
        deploymentId: String,
        url: String
    ): TalkerVerificationPolicy.RefOutcome {
        return withContext(Dispatchers.IO) {
            try {
                val token = AgentSecrets.get(engine.planContext(), AgentSecrets.KEY_VERCEL_TOKEN)
                if (token.isBlank()) return@withContext TalkerVerificationPolicy.RefOutcome.UNCHECKABLE
                val (code, body) = httpGet(
                    "https://api.vercel.com/v13/deployments/$deploymentId",
                    mapOf("Authorization" to "Bearer $token")
                )
                if (code == 404) return@withContext TalkerVerificationPolicy.RefOutcome.FAILED
                if (code !in 200..299) return@withContext TalkerVerificationPolicy.RefOutcome.UNCHECKABLE
                val state = JSONObject(body).optString("state", "")
                if (state != "READY") {
                    return@withContext if (state == "ERROR" || state == "CANCELED") {
                        TalkerVerificationPolicy.RefOutcome.FAILED
                    } else {
                        TalkerVerificationPolicy.RefOutcome.UNCHECKABLE
                    }
                }
                when (httpGetStatus(url)) {
                    200 -> TalkerVerificationPolicy.RefOutcome.PASSED
                    -1 -> TalkerVerificationPolicy.RefOutcome.UNCHECKABLE
                    else -> TalkerVerificationPolicy.RefOutcome.FAILED
                }
            } catch (e: Exception) {
                TalkerVerificationPolicy.RefOutcome.UNCHECKABLE
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
                .takeIf { it.isNotBlank() } ?: "'${step.title}' ruka hua hai — aage badhau?"
        }
        return "'${step.title}' ruka hua hai — aage badhau?"
    }

    /** Project name: this step's detail, else the plan's BUILD
     *  step detail, else the last built project. MINOR-2: a "?"
     *  sentinel is missing, never the invented "jarvis-project"
     *  default that sanitizeProjectName falls back to. */
    private fun resolveProjectName(step: PlanStep, plan: AgentPlan): String? {
        parseDetailValue(step.detail, "project")?.takeUnless { it == "?" }?.let { return ProjectStore.sanitizeProjectName(it) }
        plan.steps.firstOrNull { it.kind == "BUILD" }?.let { buildStep ->
            parseDetailValue(buildStep.detail, "project")?.takeUnless { it == "?" }?.let {
                return ProjectStore.sanitizeProjectName(it)
            }
        }
        return engine.planLastBuildProject().takeIf { it.isNotBlank() }
    }

    /** Repo name from answered clarifications — ONLY when the
     *  answer was given to the repo-name question, or the entry
     *  carries an explicit "repo=<name>". MAJOR-1: a bare
     *  single-token answer to ANY other question (a "haan" to a
     *  retry/overwrite ask, a project name given for BUILD) is
     *  NOT a repo name — accepting it would create a private repo
     *  with an invented name, without asking. Such entries fall
     *  through (null) so the caller asks for the repo name. */
    private fun resolveRepoFromClarifications(plan: AgentPlan): String? {
        for (entry in plan.answeredClarifications.asReversed()) {
            parseDetailValue(entry, "repo")?.let { raw ->
                val name = sanitizeRepoName(raw)
                if (name.isNotBlank()) return name
            }
            val question = entry.substringBefore("-> A:", "").trim()
            if (!question.contains("repo", ignoreCase = true)) continue
            val answer = entry.substringAfter("-> A: ", "").trim()
            if (answer.isBlank() || answer == "?") continue
            if (!answer.matches(Regex("[A-Za-z0-9._-]+")) || answer.length !in 2..100) continue
            val name = sanitizeRepoName(answer)
            if (name.isNotBlank()) return name
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

    /** 1.4.22 (item 5a): expected schema as table -> column set,
     *  parsed from the CREATE TABLE blocks. Per block, the
     *  parenthesised body is split on top-level commas; the first
     *  identifier of each part is a column, except constraint
     *  lines (PRIMARY KEY / FOREIGN KEY / UNIQUE / CHECK /
     *  CONSTRAINT), which are skipped. Quotes/backticks are
     *  stripped and both sides lowercased (Postgres folds
     *  unquoted identifiers; builder-generated schemas are
     *  lowercase). 1.4.25 (step 2d): the head regex also accepts
     *  an optional schema qualifier (`public.users` — the bare
     *  name is still captured, matching the
     *  `table_schema = 'public'` verify query); `--` comments
     *  outside string literals are stripped before the depth
     *  scan. NOTED LIMITATION (false-match, not false-mismatch):
     *  a quoted mixed-case identifier folds to lowercase on both
     *  sides, so verification passes while the real identifier
     *  differs in case — a SUPABASE_VERIFY_CASEFOLD diagnostic
     *  (table + column names only, no values) is logged whenever
     *  a quoted identifier was folded. */

    /** 1.4.25 (step 2d): strip `--` line comments that sit OUTSIDE
     *  string literals, before the depth scan in
     *  [parseSchemaColumns]. A `)`/`(` or an apostrophe inside a
     *  comment must not move the paren depth or the quote state
     *  (the old scan had no comment handling, so a comment
     *  paren could truncate or skip a table). Length-preserving
     *  (comment chars become spaces, newlines kept) so head-match
     *  indices still line up with the input. `splitSqlStatements`
     *  is NOT touched — it already strips whole `--` lines for
     *  execution. */
    private fun stripSqlLineComments(schema: String): String {
        val out = StringBuilder(schema.length)
        var i = 0
        var inSingleQuote = false
        while (i < schema.length) {
            val ch = schema[i]
            if (inSingleQuote) {
                out.append(ch)
                if (ch == '\'') inSingleQuote = false
                i++
            } else if (ch == '\'') {
                inSingleQuote = true
                out.append(ch)
                i++
            } else if (ch == '-' && i + 1 < schema.length && schema[i + 1] == '-') {
                // Line comment to end of line; the newline itself is
                // kept (appended by the next loop pass) so line
                // structure — and every index — is preserved.
                while (i < schema.length && schema[i] != '\n') {
                    out.append(' ')
                    i++
                }
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    private fun parseSchemaColumns(schema: String): Map<String, Set<String>> {
        // 1.4.25 (step 2d): scan the comment-stripped copy — a `)`/`(`
        // or an apostrophe inside a `--` comment must not move the
        // paren depth or the quote state. Same length as the input,
        // so every head-match index still lines up.
        val code = stripSqlLineComments(schema)
        val result = mutableMapOf<String, Set<String>>()
        val headRegex = Regex("""(?i)CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:["`]?[A-Za-z_][A-Za-z0-9_]*["`]?\.)?["`]?([A-Za-z_][A-Za-z0-9_]*)["`]?\s*\(""")
        for (match in headRegex.findAll(code)) {
            val rawTable = match.groupValues[1]
            val table = rawTable.lowercase()
            // 1.4.25 (step 2d): a quoted mixed-case table name was
            // folded to lowercase for the case-folded comparison
            // (see the KDoc limitation) — log it, names only.
            // Quoted-but-already-lowercase folds to itself, so it
            // logs nothing.
            val headText = match.value.trimEnd().removeSuffix("(").trimEnd()
            if ((headText.endsWith("\"$rawTable\"") || headText.endsWith("`$rawTable`")) &&
                rawTable != table
            ) {
                JarvisStateRepository.addDiagnostic(
                    "supabase", "SUPABASE_VERIFY_CASEFOLD",
                    "table=$rawTable folded to lowercase for verification"
                )
            }
            var depth = 1
            var i = match.range.last + 1
            val bodyStart = i
            var bodyEnd = -1
            var inSingleQuote = false
            while (i < code.length) {
                val ch = code[i]
                if (ch == '\'') inSingleQuote = !inSingleQuote
                if (!inSingleQuote) {
                    if (ch == '(') depth++
                    if (ch == ')') {
                        depth--
                        if (depth == 0) { bodyEnd = i; break }
                    }
                }
                i++
            }
            if (bodyEnd < 0) continue
            val body = code.substring(bodyStart, bodyEnd)
            val columns = mutableSetOf<String>()
            var partStart = 0
            var d = 0
            var q = false
            fun flushPart(end: Int) {
                val part = body.substring(partStart, end).trim()
                if (part.isNotEmpty()) {
                    val first = Regex("""^["`]?([A-Za-z_][A-Za-z0-9_]*)""")
                        .find(part)?.groupValues?.get(1)
                    if (first != null &&
                        first.uppercase() !in setOf("PRIMARY", "FOREIGN", "UNIQUE", "CHECK", "CONSTRAINT")
                    ) {
                        // 1.4.25 (step 2d): same fold diagnostic for
                        // a quoted mixed-case column (names only).
                        if ((part.startsWith("\"") || part.startsWith("`")) &&
                            first != first.lowercase()
                        ) {
                            JarvisStateRepository.addDiagnostic(
                                "supabase", "SUPABASE_VERIFY_CASEFOLD",
                                "table=$table column=$first folded to lowercase for verification"
                            )
                        }
                        columns.add(first.lowercase())
                    }
                }
                partStart = end + 1
            }
            var j = 0
            while (j < body.length) {
                val ch = body[j]
                if (ch == '\'') q = !q
                if (!q) {
                    if (ch == '(') d++
                    if (ch == ')') d--
                    if (ch == ',' && d == 0) flushPart(j)
                }
                j++
            }
            flushPart(body.length)
            if (columns.isNotEmpty()) result[table] = columns
        }
        return result
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
        // R1 (N3): every save carries the current budget snapshot
        // so agent_plan.json always holds the live budget state.
        val withBudget = taskBudget?.let { plan.copy(budget = it.snapshot()) } ?: plan
        AgentPlanStore.save(engine.planContext(), withBudget)
        publish(withBudget)
    }

    private fun updateStep(plan: AgentPlan, stepId: String, transform: (PlanStep) -> PlanStep): AgentPlan {
        val now = System.currentTimeMillis()
        // R2.7: merge any pending trajectory lines into the plan copy
        // (deduped) so the persisted plan carries the mini-swe
        // trajectory. This is the post-execution choke point — every
        // executed step flows through here before its save.
        val mergedTrajectory = if (runTrajectory.isEmpty()) {
            plan.trajectory
        } else {
            (plan.trajectory + runTrajectory).distinct()
        }
        runTrajectory.clear()
        return plan.copy(
            steps = plan.steps.map { if (it.id == stepId) transform(it).copy(updatedAtMs = now) else it },
            updatedAtMs = now,
            trajectory = mergedTrajectory
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
