package com.jarvis.autonomous.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.9.0 (v2) — Talker progress throttling/dedupe/stale/stalled
 * (DESIGN §3.3). Pure JVM tests.
 */
class TalkerProgressPolicyTest {

    private fun progress(label: String = "Kaam A", emittedAtMs: Long = 0L) =
        ExecutorProgress(
            commandId = "c", stepIndex = 1, stepTotal = 3, stepLabel = label,
            isMilestone = true, emittedAtMs = emittedAtMs
        )

    private fun speak(
        p: ExecutorProgress = progress(),
        nowMs: Long = 60_000L,
        lastSpokenAtMs: Long = 0L,
        lastSpokenLabel: String? = null,
        lastSpokenLabelAtMs: Long = Long.MIN_VALUE,
        gateQuestionOutstanding: Boolean = false,
        isExecutingStep: Boolean = true,
        announceLevelFull: Boolean = true
    ) = TalkerProgressPolicy.shouldSpeak(
        p, nowMs, lastSpokenAtMs, lastSpokenLabel, lastSpokenLabelAtMs,
        gateQuestionOutstanding, isExecutingStep, announceLevelFull
    )

    @Test
    fun speakAllowedAfterThirtySecondsForNewLabel() {
        assertTrue(speak(nowMs = 30_000L, lastSpokenAtMs = 0L))
        assertTrue(speak(nowMs = 30_001L, lastSpokenAtMs = 0L))
    }

    @Test
    fun speakBlockedInsideThirtySecondWindow() {
        assertFalse(speak(nowMs = 29_999L, lastSpokenAtMs = 0L))
        assertFalse(speak(nowMs = 40_000L, lastSpokenAtMs = 20_000L))
    }

    @Test
    fun speakBlockedForSameLabelWithinSixtySeconds() {
        assertFalse(speak(nowMs = 59_999L, lastSpokenLabel = "Kaam A", lastSpokenLabelAtMs = 0L))
        // Exactly at the 60 s boundary the dedupe window has elapsed.
        assertTrue(speak(nowMs = 60_000L, lastSpokenLabel = "Kaam A", lastSpokenLabelAtMs = 0L))
        assertTrue(speak(nowMs = 60_001L, lastSpokenLabel = "Kaam A", lastSpokenLabelAtMs = 0L))
        // A different label is not a dedupe repeat.
        assertTrue(speak(progress("Kaam B"), nowMs = 60_000L, lastSpokenLabel = "Kaam A", lastSpokenLabelAtMs = 0L))
    }

    @Test
    fun speakBlockedForStaleProgress() {
        // Progress emitted >120 s ago is stale — never spoken, even
        // if the 30 s window has passed.
        assertFalse(speak(progress(emittedAtMs = 0L), nowMs = 200_000L, lastSpokenAtMs = 0L))
        assertFalse(speak(progress(emittedAtMs = 79_999L), nowMs = 200_000L, lastSpokenAtMs = 0L))
        assertTrue(speak(progress(emittedAtMs = 80_000L), nowMs = 200_000L, lastSpokenAtMs = 0L))
    }

    @Test
    fun speakBlockedWhileQuestionOutstandingOrNotExecutingOrNotFull() {
        assertFalse(speak(gateQuestionOutstanding = true))
        assertFalse(speak(isExecutingStep = false))
        assertFalse(speak(announceLevelFull = false))
    }

    @Test
    fun nonMilestoneNeverSpoken() {
        val p = progress().copy(isMilestone = false)
        assertFalse(speak(p))
    }

    @Test
    fun chatAllowedAfterFiveSecondsWhenLabelOrFractionChanged() {
        val p = progress()
        assertTrue(TalkerProgressPolicy.shouldPostToChat(p, 5_000L, 0L, null, -1.0))
        assertFalse(TalkerProgressPolicy.shouldPostToChat(p, 4_999L, 0L, null, -1.0))
        // Same label AND same fraction → no re-post.
        assertFalse(TalkerProgressPolicy.shouldPostToChat(p, 60_000L, 0L, "Kaam A", -1.0))
        // Same label but a new fraction → post.
        assertTrue(
            TalkerProgressPolicy.shouldPostToChat(
                p.copy(progressFraction = 0.5), 60_000L, 0L, "Kaam A", -1.0
            )
        )
    }

    @Test
    fun staleAndStalledBoundaries() {
        assertFalse(TalkerProgressPolicy.isStale(progress(emittedAtMs = 0L), 120_000L))
        assertTrue(TalkerProgressPolicy.isStale(progress(emittedAtMs = 0L), 120_001L))
        assertFalse(TalkerProgressPolicy.isStalled(1L, 300_000L))
        assertTrue(TalkerProgressPolicy.isStalled(0L, 300_000L))
        assertTrue(TalkerProgressPolicy.isStalled(0L, 600_000L))
    }

    @Test
    fun stalledLineIsHonestAndHasNoHoGaya() {
        val line = TalkerProgressPolicy.stalledLine()
        assertTrue(line.contains("5 minute"))
        assertFalse(line.lowercase().contains("ho gaya"))
    }

    @Test
    fun etaClauseOnlyWhenEtaPositive() {
        assertEquals("", TalkerProgressPolicy.etaClause(-1L))
        assertEquals("", TalkerProgressPolicy.etaClause(0L))
        assertTrue(TalkerProgressPolicy.etaClause(45L).contains("45 second"))
        assertTrue(TalkerProgressPolicy.etaClause(90L).contains("1 minute"))
        assertTrue(TalkerProgressPolicy.etaClause(150L).contains("2 minute"))
    }
}
