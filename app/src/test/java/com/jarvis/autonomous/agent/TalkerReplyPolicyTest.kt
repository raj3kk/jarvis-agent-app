package com.jarvis.autonomous.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.0 (I2) — Talker reply-policy tests.
 * Pure JVM tests: the policy is pure functions, no I/O of any kind.
 *
 * The hard rule under test: only a DONE result may say "ho gaya".
 * Acks, timeouts, failures and blocks must never claim completion.
 */
class TalkerReplyPolicyTest {

    private fun command(workKind: ExecutorWorkKind = ExecutorWorkKind.LOOP, goal: String = "website banao") =
        ExecutorCommand(workKind = workKind, goal = goal)

    private fun result(status: ExecutorResultStatus, summary: String = "kaam ka natija", isLate: Boolean = false) =
        ExecutorResult(commandId = "c1", status = status, summary = summary, isLate = isLate)

    @Test
    fun ackNeverClaimsCompletion() {
        for (kind in ExecutorWorkKind.entries) {
            val ack = TalkerReplyPolicy.ackFor(command(kind))
            assertTrue("ack must name the work", ack.contains("website banao"))
            assertTrue("ack must say it is happening", ack.contains("Ho raha hai") || ack.contains("Kar raha hoon"))
            assertFalse("ack must never say ho gaya: $ack", ack.contains("Ho gaya"))
        }
    }

    @Test
    fun ackDiffersByWorkKind() {
        assertTrue(TalkerReplyPolicy.ackFor(command(ExecutorWorkKind.PLAN)).contains("Plan"))
        assertTrue(TalkerReplyPolicy.ackFor(command(ExecutorWorkKind.LOOP)).contains("yahin hoon"))
    }

    @Test
    fun statusLineWithNoCommandSaysNothingRunning() {
        val line = TalkerReplyPolicy.statusLine(null, null, 0L)
        assertTrue(line.contains("koi kaam chal nahi raha"))
    }

    @Test
    fun statusLineWithCommandNamesGoalAndElapsed() {
        val cmd = command(goal = "dukaan ki website")
        val fresh = TalkerReplyPolicy.statusLine(cmd, null, 20_000L)
        assertTrue(fresh.contains("dukaan ki website"))
        assertTrue(fresh.contains("abhi-abhi shuru hua hai"))
        val older = TalkerReplyPolicy.statusLine(cmd, "GitHub push chal raha hai", 150_000L)
        assertTrue(older.contains("2 minute se chal raha hai"))
        assertTrue(older.contains("GitHub push chal raha hai"))
    }

    @Test
    fun statusLineNeverInventsAnActivity() {
        val cmd = command()
        val line = TalkerReplyPolicy.statusLine(cmd, "   ", 65_000L)
        assertFalse(line.contains("Step"))
        assertTrue(line.contains("1 minute se chal raha hai"))
    }

    @Test
    fun doneResultSaysHoGaya() {
        val line = TalkerReplyPolicy.resultLine(result(ExecutorResultStatus.DONE, "Project ban gaya."))
        assertTrue(line.startsWith("Ho gaya bhai!"))
        assertTrue(line.contains("Project ban gaya."))
    }

    @Test
    fun failedResultNeverSaysHoGaya() {
        val line = TalkerReplyPolicy.resultLine(result(ExecutorResultStatus.FAILED, "validation fail ho gaya."))
        assertFalse("failure must never claim completion: $line", line.contains("Ho gaya"))
        assertTrue(line.contains("kaam poora nahi ho paya"))
        assertTrue(line.contains("validation fail ho gaya."))
    }

    @Test
    fun blockedResultAsksForWhatIsNeeded() {
        val line = TalkerReplyPolicy.resultLine(result(ExecutorResultStatus.BLOCKED, "jawab nahi aaya."))
        assertTrue(line.contains("ruk gaya"))
        assertTrue(line.contains("Batao to aage badhaun."))
    }

    @Test
    fun declinedResultIsShortAndNeverReAsks() {
        val line = TalkerReplyPolicy.resultLine(result(ExecutorResultStatus.DECLINED))
        assertEquals("Theek hai, rok diya.", line)
    }

    @Test
    fun timeoutResultKeepsWorkRunning() {
        val line = TalkerReplyPolicy.resultLine(result(ExecutorResultStatus.TIMEOUT))
        assertFalse(line.contains("Ho gaya"))
        assertTrue(line.contains("kaam chalu hai"))
        assertTrue(line.contains("Ho jayega to bata dunga."))
    }

    @Test
    fun cancelledBlankSummaryUsesFixedLine() {
        val line = TalkerReplyPolicy.resultLine(result(ExecutorResultStatus.CANCELLED, ""))
        assertEquals("Theek hai bhai, ruk gaya.", line)
    }

    @Test
    fun cancelledSupersedeSummaryIsSpoken() {
        val supersede = TalkerReplyPolicy.supersededLine("nayi website")
        val line = TalkerReplyPolicy.resultLine(result(ExecutorResultStatus.CANCELLED, supersede))
        assertEquals(supersede, line)
        assertTrue(line.contains("Pehla kaam rok diya"))
    }

    @Test
    fun lateResultIsMarkedAsFollowUp() {
        val line = TalkerReplyPolicy.resultLine(result(ExecutorResultStatus.DONE, "Project ban gaya.", isLate = true))
        assertTrue(line.startsWith("Der se hi sahi — Ho gaya bhai!"))
    }

    @Test
    fun busyLineNamesRunningWork() {
        val line = TalkerReplyPolicy.busyLine("dukaan ki website")
        assertTrue(line.contains("dukaan ki website"))
        assertTrue(line.contains("chal raha hai"))
    }

    @Test
    fun invalidCommandLineNamesFirstError() {
        val line = TalkerReplyPolicy.invalidCommandLine(listOf("MISSING_GOAL", "TIMEOUT_OUT_OF_RANGE: 1"))
        assertTrue(line.contains("MISSING_GOAL"))
    }

    @Test
    fun latencyBudgetsAreOrdered() {
        // Acks/status are templated (fast); the local reply may take
        // longer but is hard-bounded; the bound exceeds the target.
        assertTrue(TalkerReplyPolicy.TALKER_ACK_TARGET_MS <= TalkerReplyPolicy.TALKER_REPLY_TARGET_MS)
        assertTrue(TalkerReplyPolicy.TALKER_REPLY_TARGET_MS < TalkerReplyPolicy.TALKER_LOCAL_REPLY_TIMEOUT_MS)
        assertEquals(300L, TalkerReplyPolicy.TALKER_ACK_TARGET_MS)
        assertEquals(5_000L, TalkerReplyPolicy.TALKER_LOCAL_REPLY_TIMEOUT_MS)
    }

    // ---- 1.9.0 (v2) — status from structured progress ----

    private fun planProgress(etaSeconds: Long = 90L, emittedAtMs: Long = 0L) = ExecutorProgress(
        commandId = "c1", stepIndex = 2, stepTotal = 5, stepLabel = "GitHub push",
        progressFraction = 0.4, etaSeconds = etaSeconds, emittedAtMs = emittedAtMs
    )

    private fun loopProgress(emittedAtMs: Long = 0L) = ExecutorProgress(
        commandId = "c1", stepIndex = 3, stepTotal = 0, stepLabel = "WhatsApp bhejna",
        progressFraction = -1.0, etaSeconds = -1L, emittedAtMs = emittedAtMs
    )

    @Test
    fun statusLineWithProgressNamesActivityAndEta() {
        val line = TalkerReplyPolicy.statusLine(
            command(), planProgress(), 120_000L, stalled = false, nowMs = 60_000L
        )
        // R1 (no-step): the label is named in plain words — never a
        // "Step N/M" count.
        assertTrue(line.contains("GitHub push"))
        assertFalse(line.contains("Step "))
        assertTrue(line.contains("lagbhag 1 minute aur"))
        assertTrue(line.contains("2 minute se chal raha hai"))
    }

    @Test
    fun statusLineWithLoopProgressNeverCarriesEtaOrNextStep() {
        val line = TalkerReplyPolicy.statusLine(
            command(), loopProgress(), 120_000L, stalled = false, nowMs = 60_000L
        )
        assertTrue(line.contains("WhatsApp bhejna"))
        assertFalse(line.contains("lagbhag"))
        assertFalse(line.contains("Step "))
        val progress = TalkerReplyPolicy.progressLine(loopProgress(), "Kuch aur")
        assertFalse("loops never invent a next activity", progress.contains("aage:"))
        assertEquals("⏳ $progress", TalkerReplyPolicy.progressChatLine(loopProgress(), "Kuch aur"))
    }

    @Test
    fun statusLineWithStaleProgressDegradesToElapsedOnly() {
        val stale = planProgress(emittedAtMs = 0L)
        val line = TalkerReplyPolicy.statusLine(
            command(), stale, 600_000L, stalled = true, nowMs = 600_000L
        )
        assertFalse("stale activity label must not be quoted", line.contains("GitHub push"))
        assertTrue(line.contains("10 minute se chal raha hai"))
        assertTrue(line.contains("Pichle 5 minute se koi nayi halchal nahi dikh rahi"))
    }

    @Test
    fun statusLineWithNoProgressDegradesToElapsedOnly() {
        val line = TalkerReplyPolicy.statusLine(command(), null, 60_000L, stalled = false, nowMs = 60_000L)
        assertTrue(line.contains("1 minute se chal raha hai"))
        assertFalse(line.contains("Step "))
    }

    @Test
    fun progressLineVerbsAndNextStep() {
        val start = planProgress().copy(phase = "START")
        assertTrue(TalkerReplyPolicy.progressLine(start, "Supabase tables").contains("shuru hua"))
        assertTrue(TalkerReplyPolicy.progressLine(start, "Supabase tables").contains("aage: Supabase tables"))
        val done = planProgress().copy(phase = "DONE", stepIndex = 5)
        val doneLine = TalkerReplyPolicy.progressLine(done, "")
        // An activity "ho gaya" is fine — only a terminal DONE may
        // claim the whole task; the last unit names no next activity.
        assertTrue(doneLine.contains("ho gaya"))
        assertTrue(doneLine.contains("bas, khatm hone wala hai"))
        val mid = planProgress().copy(phase = "")
        assertTrue(TalkerReplyPolicy.progressLine(mid, "Supabase tables").contains("chal raha hai"))
    }
}
