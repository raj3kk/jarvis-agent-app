package com.jarvis.autonomous.agent

import org.json.JSONObject

/**
 * R1 (1.16.0-PRO) — N3 budget carve-out (REDESIGN_PLAN.md §9.3).
 *
 * The loop runs until DONE (verified) or the task budget is exhausted
 * or the watchdog fires — it NEVER stops after N steps. The old
 * step-count stop (`maxAutonomousSteps`, "Step N/M") is gone; budgets
 * below replace it.
 *
 * ⚠️ NEEDS THE USER'S WORD — the numbers below are PLAN DEFAULTS from
 * REDESIGN_PLAN.md §9.3, NOT user-confirmed values. They are flagged
 * here (code comment), in JarvisPreferences, and in APP_NOTES.md.
 * Do NOT silently keep any 5-call stop: the hub's 5-calls/turn cap
 * does NOT apply to the phone path — the phone calls Gemini/Groq
 * DIRECTLY via LlmClient (BYOK; LlmClient.kt documents there is NO
 * app→hub LLM call path, the hub /api/llm/route needs
 * x-internal-secret which the phone never has). The phone budget is
 * its own, per task, counted here.
 *
 * Pure Kotlin — ZERO Android imports — so this file unit-tests on a
 * plain JVM, exactly like the te/1 protocol files.
 */
object TaskBudgetDefaults {
    /** Work units per task (default). A plan has at most 8 items
     *  (planner cap, LlmClient.parsePlanJson), so 10 covers any
     *  single plan plus build-loop retries. */
    const val WORK_UNITS_DEFAULT = 10
    /** Work units per task (hard — never exceeded). */
    const val WORK_UNITS_HARD = 20
    /** Fix attempts per work unit (CFD loop, §9.2) — then PARK +
     *  escalate, never a third blind retry. Matches the old
     *  FIX_MAX_ATTEMPTS = 2. */
    const val FIX_ATTEMPTS_PER_UNIT = 2
    /** Watchdog: a task that runs longer than this stops (the
     *  15-min te/1 DEFAULT_TIMEOUT_MS, kept). */
    const val WATCHDOG_MINUTES = 15
    /** LLM calls per task (default). A 10-unit task needs ~10–20
     *  calls; the old hub 5-calls/turn cap would kill it. */
    const val LLM_CALLS_DEFAULT = 20
    /** LLM calls per task (hard — never exceeded). */
    const val LLM_CALLS_HARD = 40
}

/** Persisted budget snapshot — lives in agent_plan.json (§9.3:
 *  "Record budget state in the persisted plan"). */
data class TaskBudgetState(
    val startedAtMs: Long,
    val llmCallsUsed: Int,
    val workUnitsDone: Int,
    val fixAttempts: Map<String, Int>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("startedAtMs", startedAtMs)
        put("llmCallsUsed", llmCallsUsed)
        put("workUnitsDone", workUnitsDone)
        put("fixAttempts", JSONObject(fixAttempts))
    }

    companion object {
        fun fromJson(obj: JSONObject?): TaskBudgetState? {
            if (obj == null) return null
            return try {
                val fixes = mutableMapOf<String, Int>()
                val fixObj = obj.optJSONObject("fixAttempts")
                if (fixObj != null) {
                    val keys = fixObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        fixes[k] = fixObj.optInt(k, 0)
                    }
                }
                TaskBudgetState(
                    startedAtMs = obj.optLong("startedAtMs", 0L),
                    llmCallsUsed = obj.optInt("llmCallsUsed", 0),
                    workUnitsDone = obj.optInt("workUnitsDone", 0),
                    fixAttempts = fixes
                ).takeIf { it.startedAtMs > 0 }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Per-task budget for one Executor run (plan or loop). */
class TaskBudget(
    val workUnitsDefault: Int = TaskBudgetDefaults.WORK_UNITS_DEFAULT,
    val workUnitsHard: Int = TaskBudgetDefaults.WORK_UNITS_HARD,
    val fixAttemptsPerUnit: Int = TaskBudgetDefaults.FIX_ATTEMPTS_PER_UNIT,
    val watchdogMinutes: Int = TaskBudgetDefaults.WATCHDOG_MINUTES,
    val llmCallsDefault: Int = TaskBudgetDefaults.LLM_CALLS_DEFAULT,
    val llmCallsHard: Int = TaskBudgetDefaults.LLM_CALLS_HARD,
    val startedAtMs: Long = System.currentTimeMillis()
) {
    var llmCallsUsed: Int = 0
        private set
    var workUnitsDone: Int = 0
        private set
    private val fixAttempts = mutableMapOf<String, Int>()

    /** True when the 15-min watchdog has fired. */
    fun watchdogExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - startedAtMs >= watchdogMinutes * 60_000L

    /** Record one LLM call. Returns false when the HARD LLM budget
     *  is exhausted — the caller must stop the task, never make
     *  another call. */
    fun noteLlmCall(): Boolean {
        if (llmCallsUsed >= llmCallsHard) return false
        llmCallsUsed++
        return true
    }

    /** True when the DEFAULT LLM budget is reached (soft warning —
     *  the task may continue to the hard cap). */
    fun llmDefaultReached(): Boolean = llmCallsUsed >= llmCallsDefault

    /** Record one finished work unit. Returns false when the HARD
     *  work-unit budget is exhausted. */
    fun noteWorkUnitDone(): Boolean {
        if (workUnitsDone >= workUnitsHard) return false
        workUnitsDone++
        return true
    }

    /** Record one fix attempt for a unit. Returns false when that
     *  unit already used its fix attempts — the caller must PARK +
     *  escalate, never retry again. */
    fun noteFixAttempt(unitId: String): Boolean {
        val used = (fixAttempts[unitId] ?: 0) + 1
        fixAttempts[unitId] = used
        return used <= fixAttemptsPerUnit
    }

    fun fixAttemptsUsed(unitId: String): Int = fixAttempts[unitId] ?: 0

    /** One line for the LLM prompt so the model knows the remaining
     *  budget — it must never be told a step count exists. */
    fun summaryForPrompt(nowMs: Long = System.currentTimeMillis()): String =
        "LLM calls used $llmCallsUsed of $llmCallsHard; " +
            "work units done $workUnitsDone of $workUnitsHard; " +
            "watchdog ${(nowMs - startedAtMs) / 60_000L} of $watchdogMinutes min used"

    fun snapshot(): TaskBudgetState = TaskBudgetState(
        startedAtMs = startedAtMs,
        llmCallsUsed = llmCallsUsed,
        workUnitsDone = workUnitsDone,
        fixAttempts = fixAttempts.toMap()
    )

    companion object {
        /** Restore from a persisted plan (resume path) — counters
         *  continue, the deadline does NOT reset. */
        fun restore(state: TaskBudgetState): TaskBudget {
            val budget = TaskBudget(startedAtMs = state.startedAtMs)
            budget.llmCallsUsed = state.llmCallsUsed
            budget.workUnitsDone = state.workUnitsDone
            budget.fixAttempts.putAll(state.fixAttempts)
            return budget
        }
    }
}
