package com.jarvis.autonomous.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN §8.4 / §7 — verifier tests (fake step runner, fake clock). */
class SelfExtensionVerifierTest {

    private val signature = SelfExtensionDetector.sha256Hex("convert-pdf-to-text|BUILD|BUILD")
    private val steps = listOf(
        SkillStep("OPEN_APP", "com.example.app"),
        SkillStep("WAIT", "")
    )

    @Test
    fun verifierPassesOnRealInvocation() = runBlocking {
        var ran = 0
        val result = SelfExtensionVerifier.runTest(
            steps = steps,
            signature = signature,
            hasRealInput = true,
            stepRunner = { ran++; true }
        )
        assertEquals(VerificationVerdict.VERIFIED, result.verdict)
        assertEquals(2, ran)
        assertEquals(2, result.refs.size)
        assertTrue(result.refs.all { it.kind == EvidenceKind.SKILL_TEST })
        assertTrue(result.refs.all { it.check.startsWith("step_ok:") })
        assertEquals("selfext-${signature.take(8)}", result.testId)
    }

    @Test
    fun verifierFailsWhenAStepFails() = runBlocking {
        var ran = 0
        val result = SelfExtensionVerifier.runTest(
            steps = steps,
            signature = signature,
            hasRealInput = true,
            stepRunner = { step -> ran++; step.action != "WAIT" }
        )
        assertEquals(VerificationVerdict.FAILED, result.verdict)
        assertEquals("step_failed:WAIT", result.reason)
        assertEquals(2, ran)
    }

    @Test
    fun verifierUnverifiedWithoutRealInputAndRunnerNeverCalled() = runBlocking {
        var ran = 0
        val result = SelfExtensionVerifier.runTest(
            steps = steps,
            signature = signature,
            hasRealInput = false,
            stepRunner = { ran++; true }
        )
        assertEquals(VerificationVerdict.UNVERIFIED, result.verdict)
        assertEquals("no_real_input", result.reason)
        assertEquals(0, ran)
        assertTrue(result.refs.isEmpty())
    }

    @Test
    fun verifierUnverifiedWithNoSteps() = runBlocking {
        val result = SelfExtensionVerifier.runTest(
            steps = emptyList(),
            signature = signature,
            hasRealInput = true,
            stepRunner = { true }
        )
        assertEquals(VerificationVerdict.UNVERIFIED, result.verdict)
    }

    @Test
    fun verifierTimeoutAt120sWall() = runBlocking {
        var fakeNow = 1_000_000L
        val result = SelfExtensionVerifier.runTest(
            steps = steps,
            signature = signature,
            hasRealInput = true,
            stepRunner = {
                fakeNow += 130_000L // the first step overruns the wall
                true
            },
            nowMs = { fakeNow }
        )
        // Deadline is checked before each step: the second step is
        // past the 120 s wall, so the run fails as a timeout.
        assertEquals(VerificationVerdict.FAILED, result.verdict)
        assertEquals("verification_timeout", result.reason)
    }

    @Test
    fun verifierVerdictForZeroRefsIsUnverified() {
        assertEquals(
            VerificationVerdict.UNVERIFIED,
            SelfExtensionVerifier.verdictFor(emptyList(), emptyList())
        )
    }

    @Test
    fun verifierVerdictForFailedStepIsFailed() {
        val ref = EvidenceRef(EvidenceKind.SKILL_TEST, "t:step0", "step_ok:WAIT", "ok")
        assertEquals(
            VerificationVerdict.FAILED,
            SelfExtensionVerifier.verdictFor(listOf(true, false), listOf(ref, ref))
        )
    }

    @Test
    fun verifierStepRunnerExceptionIsFailureNotCrash() = runBlocking {
        val result = SelfExtensionVerifier.runTest(
            steps = steps,
            signature = signature,
            hasRealInput = true,
            stepRunner = { throw IllegalStateException("boom") }
        )
        assertEquals(VerificationVerdict.FAILED, result.verdict)
        assertFalse(result.refs.isEmpty())
    }
}
