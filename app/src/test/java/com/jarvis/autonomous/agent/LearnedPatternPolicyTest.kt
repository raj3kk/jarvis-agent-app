package com.jarvis.autonomous.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.9.0 (v2) — learned-pattern policy: match scoring, confidence
 * ladder, extraction filter (DESIGN §5). Pure JVM tests.
 */
class LearnedPatternPolicyTest {

    @Test
    fun matchScoreCountsKeywordsWorkKindAndPhrase() {
        // 3 keyword hits + work-kind match + phrase contains signature.
        val score = LearnedPatternPolicy.matchScore(
            queryKeywords = listOf("website", "dukaan", "banao"),
            patternKeywords = listOf("website", "dukaan", "banao"),
            workKindMatches = true,
            phraseContainsSignature = true
        )
        assertEquals(3 + 2 + 1, score)
    }

    @Test
    fun matchScoreThresholdBoundary() {
        // 1 keyword hit + work-kind match = 3, exactly the threshold.
        val atThreshold = LearnedPatternPolicy.matchScore(
            queryKeywords = listOf("website", "kholo"),
            patternKeywords = listOf("website", "dukaan"),
            workKindMatches = true,
            phraseContainsSignature = false
        )
        assertEquals(LearnedPatternPolicy.MATCH_MIN_SCORE, atThreshold)
        // No keyword hit and no work-kind match → 0, below threshold.
        val none = LearnedPatternPolicy.matchScore(
            queryKeywords = listOf("zebra"),
            patternKeywords = listOf("website", "dukaan"),
            workKindMatches = false,
            phraseContainsSignature = false
        )
        assertEquals(0, none)
        assertTrue(none < LearnedPatternPolicy.MATCH_MIN_SCORE)
    }

    @Test
    fun confidenceLadderCapsAndFloors() {
        assertEquals(0.6, LearnedPatternPolicy.confidenceAfterSuccess(0.5), 0.001)
        assertEquals(0.9, LearnedPatternPolicy.confidenceAfterSuccess(0.85), 0.001)
        assertEquals(0.9, LearnedPatternPolicy.confidenceAfterSuccess(0.9), 0.001)
        // First failure: confidence drops, no auto-disable yet.
        val (afterOne, disabledOne) = LearnedPatternPolicy.confidenceAfterFailure(0.6, 0)
        assertEquals(0.4, afterOne, 0.001)
        assertFalse(disabledOne)
        // Second consecutive failure: floored at 0.2 and auto-disabled.
        val (afterTwo, disabledTwo) = LearnedPatternPolicy.confidenceAfterFailure(0.4, 1)
        assertEquals(0.2, afterTwo, 0.001)
        assertTrue(disabledTwo)
    }

    @Test
    fun extractionFilterRejectsSensitiveAndProjectNames() {
        assertFalse(LearnedPatternPolicy.isLearnableStepLabel("email priyadarshirajindia@gmail.com bhejo", "LOOP"))
        assertFalse(LearnedPatternPolicy.isLearnableStepLabel("OTP 482910 daalo", "LOOP"))
        assertFalse(LearnedPatternPolicy.isLearnableStepLabel("call 6201858525 karo", "LOOP"))
        assertFalse(LearnedPatternPolicy.isLearnableStepLabel("site https://example.com/a?x=1 kholo", "LOOP"))
        assertFalse(LearnedPatternPolicy.isLearnableStepLabel("password daalo", "LOOP"))
        // Bare project slugs are not learnable steps.
        assertFalse(LearnedPatternPolicy.isLearnableStepLabel("dukaan-site", "BUILD"))
        assertFalse(LearnedPatternPolicy.isLearnableStepLabel("my-website-2026", "BUILD"))
        // Clean labels pass.
        assertTrue(LearnedPatternPolicy.isLearnableStepLabel("Website banao", "BUILD"))
        assertTrue(LearnedPatternPolicy.isLearnableStepLabel("GitHub par push karo", "GITHUB_PUSH"))
        assertTrue(LearnedPatternPolicy.isLearnableStepLabel("Preview check karo", "BUILD"))
        assertTrue(LearnedPatternPolicy.isLearnableStepLabel("build", "BUILD"))
    }

    @Test
    fun sensitiveValueDetection() {
        assertTrue(LearnedPatternPolicy.isSensitiveValue("priya@gmail.com"))
        assertTrue(LearnedPatternPolicy.isSensitiveValue("6201858525"))
        assertTrue(LearnedPatternPolicy.isSensitiveValue("https://x.com/a?token=abc"))
        assertTrue(LearnedPatternPolicy.isSensitiveValue("x".repeat(201)))
        assertFalse(LearnedPatternPolicy.isSensitiveValue("website banao"))
        assertFalse(LearnedPatternPolicy.isSensitiveValue("dukaan-site"))
    }

    @Test
    fun notesAndInstructionsHaveLengthLimits() {
        assertTrue(LearnedPatternPolicy.isLearnableNote("fix: preview server restart kiya"))
        assertFalse(LearnedPatternPolicy.isLearnableNote("x".repeat(201)))
        assertFalse(LearnedPatternPolicy.isLearnableNote(""))
        assertTrue(LearnedPatternPolicy.isLearnableInstruction("aage se pehle preview check karo"))
        assertFalse(LearnedPatternPolicy.isLearnableInstruction("x".repeat(281)))
        assertFalse(LearnedPatternPolicy.isLearnableInstruction(""))
    }
}
