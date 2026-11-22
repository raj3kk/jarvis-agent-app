package com.jarvis.autonomous.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.0 (I2) — Talker/Executor bus tests.
 * Pure JVM tests: the bus has zero Android imports, no coroutines.
 */
class TalkerExecutorBusTest {

    private fun command(id: String, goal: String = "kaam $id") = ExecutorCommand(
        id = id,
        kind = ExecutorCommandKind.EXECUTE_TASK,
        workKind = ExecutorWorkKind.LOOP,
        goal = goal
    )

    private fun result(id: String, status: ExecutorResultStatus) = ExecutorResult(
        commandId = id,
        status = status,
        summary = "summary $status"
    )

    @Test
    fun submitAcceptsValidCommand() {
        val bus = TalkerExecutorBus()
        assertEquals(TalkerExecutorBus.SubmitResult.ACCEPTED, bus.submit(command("a")))
        assertEquals(1, bus.pendingCount())
        assertTrue(bus.isPending("a"))
    }

    @Test
    fun submitRejectsInvalidCommand() {
        val bus = TalkerExecutorBus()
        val bad = command("b").copy(goal = "")
        assertEquals(TalkerExecutorBus.SubmitResult.INVALID, bus.submit(bad))
        assertEquals(0, bus.pendingCount())
    }

    @Test
    fun submitRejectsDuplicatePendingId() {
        val bus = TalkerExecutorBus()
        assertEquals(TalkerExecutorBus.SubmitResult.ACCEPTED, bus.submit(command("c")))
        assertEquals(TalkerExecutorBus.SubmitResult.DUPLICATE_ID, bus.submit(command("c")))
        assertEquals(1, bus.pendingCount())
    }

    @Test
    fun completeDeliversResultAndClearsPending() {
        val bus = TalkerExecutorBus()
        bus.submit(command("d"))
        assertTrue(bus.complete(result("d", ExecutorResultStatus.DONE)))
        assertEquals(0, bus.pendingCount())
        assertFalse(bus.isPending("d"))
        assertEquals(ExecutorResultStatus.DONE, bus.lastCompleted()!!.status)
    }

    @Test
    fun completeDropsUnknownCommandId() {
        val bus = TalkerExecutorBus()
        assertFalse(bus.complete(result("ghost", ExecutorResultStatus.DONE)))
        assertNull(bus.lastCompleted())
    }

    @Test
    fun completeDropsInvalidResult() {
        val bus = TalkerExecutorBus()
        bus.submit(command("e"))
        val bad = ExecutorResult(commandId = "e", status = ExecutorResultStatus.DONE, summary = " ")
        assertFalse(bus.complete(bad))
        assertTrue(bus.isPending("e"))
    }

    @Test
    fun listenerIsNotifiedOnComplete() {
        val bus = TalkerExecutorBus()
        val seen = mutableListOf<ExecutorResult>()
        bus.addListener { seen.add(it) }
        bus.submit(command("f"))
        bus.complete(result("f", ExecutorResultStatus.FAILED))
        assertEquals(1, seen.size)
        assertEquals("f", seen[0].commandId)
        assertEquals(ExecutorResultStatus.FAILED, seen[0].status)
    }

    @Test
    fun secondTerminalResultArrivesMarkedLate() {
        val bus = TalkerExecutorBus()
        val seen = mutableListOf<ExecutorResult>()
        bus.addListener { seen.add(it) }
        bus.submit(command("g"))
        assertTrue(bus.complete(result("g", ExecutorResultStatus.TIMEOUT)))
        // Executor kept running; its late DONE must still be delivered.
        assertTrue(bus.complete(result("g", ExecutorResultStatus.DONE)))
        assertEquals(2, seen.size)
        assertFalse(seen[0].isLate)
        assertTrue(seen[1].isLate)
        assertEquals(ExecutorResultStatus.DONE, seen[1].status)
    }

    @Test
    fun cancelRemovesPendingAndReturnsCommand() {
        val bus = TalkerExecutorBus()
        bus.submit(command("h"))
        val removed = bus.cancel("h")
        assertEquals("h", removed!!.id)
        assertEquals(0, bus.pendingCount())
        assertNull(bus.cancel("h"))
    }

    @Test
    fun completedHistoryIsBoundedAt20() {
        val bus = TalkerExecutorBus()
        for (i in 0 until 25) {
            val id = "cmd-$i"
            bus.submit(command(id))
            bus.complete(result(id, ExecutorResultStatus.DONE))
        }
        val history = bus.completedHistory()
        assertEquals(TalkerExecutorBus.MAX_COMPLETED_HISTORY, history.size)
        assertEquals("cmd-24", history.last().commandId)
        assertEquals("cmd-5", history.first().commandId)
    }

    @Test
    fun pendingCommandsPreserveSubmissionOrder() {
        val bus = TalkerExecutorBus()
        bus.submit(command("1"))
        bus.submit(command("2"))
        bus.submit(command("3"))
        assertEquals(listOf("1", "2", "3"), bus.pendingCommands().map { it.id })
    }

    // ---- 1.9.0 (v2) — progress + fix pairing ----

    private fun progress(id: String, label: String, index: Int = 1) = ExecutorProgress(
        commandId = id, stepIndex = index, stepTotal = 3, stepLabel = label
    )

    @Test
    fun progressForUnknownIdIsDropped() {
        val bus = TalkerExecutorBus()
        assertFalse(bus.reportProgress(progress("ghost", "Step")))
        assertNull(bus.lastProgress("ghost"))
    }

    @Test
    fun progressKeepsFreshestOnlyAndDoesNotTouchPendingOrHistory() {
        val bus = TalkerExecutorBus()
        bus.submit(command("1"))
        assertTrue(bus.reportProgress(progress("1", "Step A")))
        assertTrue(bus.reportProgress(progress("1", "Step B", index = 2)))
        assertEquals("Step B", bus.lastProgress("1")!!.stepLabel)
        assertEquals(1, bus.pendingCount())
        assertTrue(bus.completedHistory().isEmpty())
        // Progress after terminal completion still lands (the id is
        // in completed history) but never re-opens the job.
        assertTrue(bus.complete(result("1", ExecutorResultStatus.DONE)))
        assertNull(bus.lastProgress("1"))
        assertTrue(bus.reportProgress(progress("1", "Step C", index = 3)))
        assertEquals("Step C", bus.lastProgress("1")!!.stepLabel)
        assertFalse(bus.isPending("1"))
    }

    @Test
    fun progressListenerIsNotified() {
        val bus = TalkerExecutorBus()
        bus.submit(command("1"))
        val seen = ArrayList<String>()
        bus.addProgressListener { seen.add(it.stepLabel) }
        bus.reportProgress(progress("1", "Step A"))
        bus.reportProgress(progress("ghost", "Step X"))
        assertEquals(listOf("Step A"), seen)
    }

    @Test
    fun lateProgressAfterCancelIsDropped() {
        val bus = TalkerExecutorBus()
        bus.submit(command("1"))
        assertTrue(bus.reportProgress(progress("1", "Step A")))
        bus.cancel("1")
        assertNull(bus.lastProgress("1"))
        assertFalse(bus.reportProgress(progress("1", "Step B", index = 2)))
    }

    @Test
    fun fixRequestRequiresKnownCommandAndValidAttempt() {
        val bus = TalkerExecutorBus()
        val req = ExecutorFixRequest(
            commandId = "ghost", attempt = 1, missingRefs = listOf("preview_http:preview:x")
        )
        assertFalse(bus.requestFix(req))
        bus.submit(command("1"))
        assertTrue(
            bus.requestFix(
                ExecutorFixRequest(
                    commandId = "1", attempt = 1, missingRefs = listOf("preview_http:preview:x")
                )
            )
        )
        assertTrue(bus.hasOutstandingFix("1"))
        // Attempt 3 of max 2 is invalid and never marks outstanding.
        assertFalse(
            bus.requestFix(
                ExecutorFixRequest(
                    commandId = "1", attempt = 3, maxAttempts = 2,
                    missingRefs = listOf("preview_http:preview:x")
                )
            )
        )
    }

    @Test
    fun fixResultWithoutOutstandingRequestIsDropped() {
        val bus = TalkerExecutorBus()
        bus.submit(command("1"))
        val fix = ExecutorFixResult(
            commandId = "1", attempt = 1, status = FixResultStatus.FIXED,
            freshEvidenceJson = "{}"
        )
        assertFalse(bus.completeFix(fix))
        // After a request, the result is delivered once and the
        // outstanding mark is consumed — a second result is dropped.
        assertTrue(
            bus.requestFix(
                ExecutorFixRequest(
                    commandId = "1", attempt = 1, missingRefs = listOf("preview_http:preview:x")
                )
            )
        )
        assertTrue(bus.completeFix(fix))
        assertFalse(bus.hasOutstandingFix("1"))
        assertFalse(bus.completeFix(fix))
    }

    @Test
    fun fixListenersAreNotifiedOnlyOnPairedResult() {
        val bus = TalkerExecutorBus()
        bus.submit(command("1"))
        val seen = ArrayList<FixResultStatus>()
        bus.addFixListener { seen.add(it.status) }
        bus.completeFix(
            ExecutorFixResult(commandId = "1", attempt = 1, status = FixResultStatus.FIXED)
        )
        assertTrue(seen.isEmpty())
        bus.requestFix(
            ExecutorFixRequest(
                commandId = "1", attempt = 1, missingRefs = listOf("preview_http:preview:x")
            )
        )
        bus.completeFix(
            ExecutorFixResult(commandId = "1", attempt = 1, status = FixResultStatus.STILL_MISSING)
        )
        assertEquals(listOf(FixResultStatus.STILL_MISSING), seen)
    }
}
