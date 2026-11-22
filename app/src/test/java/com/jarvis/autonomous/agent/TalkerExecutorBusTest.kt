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
}
