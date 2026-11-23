package com.jarvis.autonomous.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1 (1.16.0-PRO) — N3 budget carve-out (REDESIGN_PLAN.md §9.3).
 * Pure Kotlin, plain JVM — no Android, no network.
 *
 * The numbers under test are the PLAN DEFAULTS (10 work units / 20
 * hard, 2 fix attempts per unit, 15-min watchdog, 20 LLM calls
 * default / 40 hard) — flagged NEEDS THE USER'S WORD in
 * TaskBudget.kt, JarvisPreferences, and APP_NOTES.md.
 */
class TaskBudgetTest {

    @Test
    fun freshBudgetAllowsWork() {
        val b = TaskBudget()
        assertFalse(b.watchdogExpired())
        assertTrue(b.noteLlmCall())
        assertTrue(b.noteWorkUnitDone())
        assertTrue(b.noteFixAttempt("unit-a"))
        assertEquals(1, b.llmCallsUsed)
        assertEquals(1, b.workUnitsDone)
        assertEquals(1, b.fixAttemptsUsed("unit-a"))
    }

    @Test
    fun llmHardBudgetStopsCalls() {
        val b = TaskBudget(llmCallsHard = 3)
        assertTrue(b.noteLlmCall())
        assertTrue(b.noteLlmCall())
        assertTrue(b.noteLlmCall())
        // 4th call refused — the caller must stop the task.
        assertFalse(b.noteLlmCall())
        assertEquals(3, b.llmCallsUsed)
    }

    @Test
    fun llmDefaultIsOnlyASoftWarning() {
        val b = TaskBudget(llmCallsDefault = 2, llmCallsHard = 5)
        b.noteLlmCall()
        assertFalse(b.llmDefaultReached())
        b.noteLlmCall()
        assertTrue(b.llmDefaultReached())
        // Soft: calls continue to the hard cap.
        assertTrue(b.noteLlmCall())
    }

    @Test
    fun workUnitHardBudgetStopsUnits() {
        val b = TaskBudget(workUnitsHard = 2)
        assertTrue(b.noteWorkUnitDone())
        assertTrue(b.noteWorkUnitDone())
        assertFalse(b.noteWorkUnitDone())
        assertEquals(2, b.workUnitsDone)
    }

    @Test
    fun fixAttemptsPerUnitAreCappedAtTwo() {
        val b = TaskBudget(fixAttemptsPerUnit = 2)
        assertTrue(b.noteFixAttempt("unit-a"))
        assertTrue(b.noteFixAttempt("unit-a"))
        // 3rd fix attempt refused — PARK + escalate, never retry.
        assertFalse(b.noteFixAttempt("unit-a"))
        // A different unit still has its own 2 attempts.
        assertTrue(b.noteFixAttempt("unit-b"))
    }

    @Test
    fun watchdogFiresAtFifteenMinutes() {
        val start = 1_000_000L
        val b = TaskBudget(watchdogMinutes = 15, startedAtMs = start)
        assertFalse(b.watchdogExpired(nowMs = start + 14 * 60_000L))
        assertTrue(b.watchdogExpired(nowMs = start + 15 * 60_000L))
        assertTrue(b.watchdogExpired(nowMs = start + 16 * 60_000L))
    }

    @Test
    fun snapshotRoundTripRestoresCountersWithoutResettingDeadline() {
        val start = 1_000_000L
        val b = TaskBudget(startedAtMs = start)
        b.noteLlmCall()
        b.noteLlmCall()
        b.noteWorkUnitDone()
        b.noteFixAttempt("unit-a")
        val restored = TaskBudget.restore(b.snapshot())
        assertEquals(2, restored.llmCallsUsed)
        assertEquals(1, restored.workUnitsDone)
        assertEquals(1, restored.fixAttemptsUsed("unit-a"))
        // Same start: the watchdog deadline does NOT reset on resume.
        assertFalse(restored.watchdogExpired(nowMs = start + 14 * 60_000L))
        assertTrue(restored.watchdogExpired(nowMs = start + 15 * 60_000L))
        // Counters continue from the restored values.
        restored.noteLlmCall()
        assertEquals(3, restored.llmCallsUsed)
    }

    @Test
    fun corruptSnapshotRestoresToNull() {
        assertEquals(null, TaskBudgetState.fromJson(null))
        assertEquals(null, TaskBudgetState.fromJson(JSONObject("{}")))
        assertEquals(null, TaskBudgetState.fromJson(JSONObject("{\"startedAtMs\":0}")))
    }

    @Test
    fun promptSummaryNeverMentionsSteps() {
        val b = TaskBudget()
        val line = b.summaryForPrompt(nowMs = b.startedAtMs)
        assertFalse(line.contains("step", ignoreCase = true))
        assertTrue(line.contains("LLM calls used 0 of 40"))
        assertTrue(line.contains("work units done 0 of 20"))
        assertTrue(line.contains("watchdog 0 of 15 min used"))
    }

    @Test
    fun defaultsAreTheN3PlanDefaults() {
        assertEquals(10, TaskBudgetDefaults.WORK_UNITS_DEFAULT)
        assertEquals(20, TaskBudgetDefaults.WORK_UNITS_HARD)
        assertEquals(2, TaskBudgetDefaults.FIX_ATTEMPTS_PER_UNIT)
        assertEquals(15, TaskBudgetDefaults.WATCHDOG_MINUTES)
        assertEquals(20, TaskBudgetDefaults.LLM_CALLS_DEFAULT)
        assertEquals(40, TaskBudgetDefaults.LLM_CALLS_HARD)
    }
}
