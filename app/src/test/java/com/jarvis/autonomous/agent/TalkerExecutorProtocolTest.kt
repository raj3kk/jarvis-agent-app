package com.jarvis.autonomous.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.0 (I2) — Talker/Executor protocol tests (te/1).
 * Pure JVM tests: the protocol module has zero Android imports.
 */
class TalkerExecutorProtocolTest {

    private fun sampleCommand() = ExecutorCommand(
        id = "cmd-1",
        kind = ExecutorCommandKind.EXECUTE_TASK,
        workKind = ExecutorWorkKind.PLAN,
        goal = "ek website banao",
        params = mapOf("project" to "dukaan-site"),
        issuedAtMs = 1_793_123_456_789L,
        timeoutMs = 900_000L
    )

    @Test
    fun commandRoundTrip() {
        val cmd = sampleCommand()
        val parsed = ExecutorCommand.fromJson(cmd.toJson())
        assertNotNull(parsed)
        assertEquals(cmd, parsed)
    }

    @Test
    fun resultRoundTrip() {
        val result = ExecutorResult(
            commandId = "cmd-1",
            status = ExecutorResultStatus.DONE,
            summary = "Ho gaya bhai! Project ban gaya.",
            evidence = "validation OK; preview 200",
            finishedAtMs = 1_793_123_457_789L,
            durationMs = 1000L,
            isLate = true
        )
        val parsed = ExecutorResult.fromJson(result.toJson())
        assertNotNull(parsed)
        assertEquals(result, parsed)
    }

    @Test
    fun commandValidationPassesForValid() {
        assertTrue(sampleCommand().validate().isEmpty())
    }

    @Test
    fun commandValidationRejectsBadVersion() {
        val errors = sampleCommand().copy(version = 99).validate()
        assertTrue(errors.any { it.startsWith("UNSUPPORTED_VERSION") })
    }

    @Test
    fun commandValidationRejectsMissingGoalForExecute() {
        val errors = sampleCommand().copy(goal = "   ").validate()
        assertTrue(errors.contains("MISSING_GOAL"))
    }

    @Test
    fun commandValidationAllowsBlankGoalForCancel() {
        val cmd = ExecutorCommand(
            kind = ExecutorCommandKind.CANCEL_TASK,
            workKind = ExecutorWorkKind.LOOP,
            goal = ""
        )
        assertTrue(cmd.validate().isEmpty())
    }

    @Test
    fun commandValidationRejectsTimeoutOutOfRange() {
        assertTrue(sampleCommand().copy(timeoutMs = 1_000L).validate().any { it.startsWith("TIMEOUT_OUT_OF_RANGE") })
        assertTrue(sampleCommand().copy(timeoutMs = 9_999_999L).validate().any { it.startsWith("TIMEOUT_OUT_OF_RANGE") })
        assertTrue(sampleCommand().copy(timeoutMs = TalkerExecutorProtocol.MIN_TIMEOUT_MS).validate().isEmpty())
        assertTrue(sampleCommand().copy(timeoutMs = TalkerExecutorProtocol.MAX_TIMEOUT_MS).validate().isEmpty())
    }

    @Test
    fun commandValidationRejectsBlankIdAndBadIssuedAt() {
        assertTrue(sampleCommand().copy(id = "").validate().contains("MISSING_ID"))
        assertTrue(sampleCommand().copy(issuedAtMs = 0L).validate().contains("BAD_ISSUED_AT"))
    }

    @Test
    fun fromJsonReturnsNullForMalformed() {
        assertNull(ExecutorCommand.fromJson(""))
        assertNull(ExecutorCommand.fromJson("{not json"))
        assertNull(ExecutorCommand.fromJson("""{"version":1,"id":"x"}"""))
        assertNull(ExecutorCommand.fromJson("""{"version":1,"id":"x","kind":"NOPE","workKind":"LOOP","goal":"g","issuedAtMs":1,"timeoutMs":900000}"""))
        assertNull(ExecutorCommand.fromJson("""{"version":"1","id":"x","kind":"EXECUTE_TASK","workKind":"LOOP","goal":"g","issuedAtMs":1,"timeoutMs":900000}"""))
        assertNull(ExecutorResult.fromJson("""{"version":1,"commandId":"x","status":"NOPE","summary":"s","finishedAtMs":1}"""))
    }

    @Test
    fun fromJsonIgnoresUnknownFieldsForwardCompat() {
        val json = """{"version":1,"id":"cmd-9","kind":"EXECUTE_TASK","workKind":"LOOP","goal":"g","params":{},"issuedAtMs":5,"timeoutMs":900000,"requiresConfirmation":false,"origin":"talker","futureField":"hello","futureNum":42}"""
        val parsed = ExecutorCommand.fromJson(json)
        assertNotNull(parsed)
        assertEquals("cmd-9", parsed!!.id)
        assertEquals("g", parsed.goal)
    }

    @Test
    fun jsonEscapesQuotesNewlinesAndUnicode() {
        val tricky = "boli \"quoted\" text\nnayi line\tTab \\ backslash"
        val cmd = sampleCommand().copy(goal = tricky)
        val parsed = ExecutorCommand.fromJson(cmd.toJson())
        assertNotNull(parsed)
        assertEquals(tricky, parsed!!.goal)
    }

    @Test
    fun resultValidationRejectsMissingSummary() {
        val result = ExecutorResult(commandId = "c", status = ExecutorResultStatus.FAILED, summary = " ")
        assertTrue(result.validate().contains("MISSING_SUMMARY"))
    }

    @Test
    fun defaultTimeoutIs15Minutes() {
        assertEquals(900_000L, TalkerExecutorProtocol.DEFAULT_TIMEOUT_MS)
        assertEquals(1, TalkerExecutorProtocol.VERSION)
    }
}
