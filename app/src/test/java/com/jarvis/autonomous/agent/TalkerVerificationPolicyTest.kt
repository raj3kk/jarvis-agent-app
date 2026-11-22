package com.jarvis.autonomous.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.9.0 (v2) — verification verdicts + the wording map
 * (DESIGN §4.4, §10 Q3). Pure JVM tests.
 *
 * The hard gate: ONLY the VERIFIED line may contain "ho gaya".
 * Every other spoken line must be a hedged / negative statement.
 */
class TalkerVerificationPolicyTest {

    private fun check(
        refId: String,
        outcome: TalkerVerificationPolicy.RefOutcome,
        detail: String = ""
    ) = TalkerVerificationPolicy.RefCheckResult(refId, outcome, detail)

    @Test
    fun allPassedIsVerified() {
        val results = listOf(
            check("file_list:project:x", TalkerVerificationPolicy.RefOutcome.PASSED),
            check("preview_http:preview:x", TalkerVerificationPolicy.RefOutcome.PASSED)
        )
        assertEquals(
            TalkerVerificationPolicy.VerificationVerdict.VERIFIED,
            TalkerVerificationPolicy.decide(results)
        )
    }

    @Test
    fun zeroRefsIsUnverifiedNeverVerified() {
        assertEquals(
            TalkerVerificationPolicy.VerificationVerdict.UNVERIFIED,
            TalkerVerificationPolicy.decide(emptyList())
        )
    }

    @Test
    fun anyUncheckableIsUnverified() {
        val results = listOf(
            check("file_list:project:x", TalkerVerificationPolicy.RefOutcome.PASSED),
            check("vercel_deploy:vercel:d1", TalkerVerificationPolicy.RefOutcome.UNCHECKABLE)
        )
        assertEquals(
            TalkerVerificationPolicy.VerificationVerdict.UNVERIFIED,
            TalkerVerificationPolicy.decide(results)
        )
    }

    @Test
    fun anyFailedIsMismatchEvenWhenOthersPassed() {
        val results = listOf(
            check("file_list:project:x", TalkerVerificationPolicy.RefOutcome.PASSED),
            check("preview_http:preview:x", TalkerVerificationPolicy.RefOutcome.FAILED)
        )
        assertEquals(
            TalkerVerificationPolicy.VerificationVerdict.MISMATCH,
            TalkerVerificationPolicy.decide(results)
        )
    }

    @Test
    fun failedBeatsUncheckable() {
        val results = listOf(
            check("github_commit:github:o/r", TalkerVerificationPolicy.RefOutcome.FAILED),
            check("vercel_deploy:vercel:d1", TalkerVerificationPolicy.RefOutcome.UNCHECKABLE)
        )
        assertEquals(
            TalkerVerificationPolicy.VerificationVerdict.MISMATCH,
            TalkerVerificationPolicy.decide(results)
        )
    }

    @Test
    fun failedKindsParsesLowercaseRefIds() {
        // Ref ids carry the kind lowercased ("preview_http:…") — the
        // parser must still resolve them (regression: the old
        // wire.lowercase() == uppercased-prefix comparison matched
        // nothing and every MISMATCH degraded to "kuch cheezein").
        val results = listOf(
            check("github_commit:github:o/r", TalkerVerificationPolicy.RefOutcome.FAILED),
            check("preview_http:preview:x", TalkerVerificationPolicy.RefOutcome.PASSED)
        )
        assertEquals(
            listOf(EvidenceKind.GITHUB_COMMIT),
            TalkerVerificationPolicy.failedKinds(results)
        )
    }

    @Test
    fun everyEvidenceKindHasAMissingPhrase() {
        for (kind in EvidenceKind.values()) {
            val phrase = TalkerVerificationPolicy.missingPhrase(kind)
            assertTrue("no phrase for $kind", phrase.isNotBlank())
            assertFalse("phrase for $kind says ho gaya", phrase.lowercase().contains("ho gaya"))
        }
    }

    @Test
    fun missingPhraseForCapsAtTwoPlusEtc() {
        val kinds = listOf(EvidenceKind.PREVIEW_HTTP, EvidenceKind.FILE_LIST, EvidenceKind.GITHUB_COMMIT)
        val text = TalkerVerificationPolicy.missingPhraseFor(kinds)
        assertTrue(text.contains("preview nahi khul raha"))
        assertTrue(text.contains("aur kuch cheezein"))
    }

    @Test
    fun completionClaimOnlyInVerifiedLine() {
        // The completion formula "Ho gaya bhai!" appears only in the
        // VERIFIED line. Two lines deliberately contain the WORDS
        // "ho gaya" without claiming completion: the Q3 UNVERIFIED
        // hedge ("Ho gaya lagta hai, par main verify nahi kar paya")
        // and the timeout line (the TIME ran out, not the task).
        // Every other non-verified line must not contain the words
        // at all.
        val verified = TalkerVerificationPolicy.verifiedLine("website ban gayi", false)
        assertTrue(verified.contains("Ho gaya bhai!"))
        val late = TalkerVerificationPolicy.verifiedLine("website ban gayi", true)
        assertTrue(late.contains("Ho gaya bhai!"))

        val unverified = TalkerVerificationPolicy.unverifiedLine("preview")
        assertTrue(unverified.contains("Ho gaya lagta hai, par main verify nahi kar paya"))
        assertFalse(unverified.contains("Ho gaya bhai"))

        val failed = listOf(check("preview_http:preview:x", TalkerVerificationPolicy.RefOutcome.FAILED))
        val uncheckable = listOf(check("preview_http:preview:x", TalkerVerificationPolicy.RefOutcome.UNCHECKABLE))
        val kinds = listOf(EvidenceKind.PREVIEW_HTTP)
        val timeout = TalkerVerificationPolicy.fixTimeoutLine(kinds)
        assertTrue(timeout.contains("Time khatm ho gaya"))
        assertTrue(timeout.contains("theek nahi ho paya"))
        assertFalse(timeout.contains("Ho gaya bhai"))

        val plainLines = listOf(
            TalkerVerificationPolicy.unverifiedDetail(uncheckable),
            TalkerVerificationPolicy.fixStartLine(kinds),
            TalkerVerificationPolicy.fixRetryLine(kinds),
            TalkerVerificationPolicy.fixExhaustedLine(kinds),
            TalkerVerificationPolicy.mismatchDetail(failed),
            TalkerVerificationPolicy.artifactPhraseFor(uncheckable)
        )
        for (line in plainLines) {
            assertFalse("non-verified line says ho gaya: $line", line.lowercase().contains("ho gaya"))
        }
    }

    @Test
    fun unverifiedLineUsesTheAgreedQ3Wording() {
        val line = TalkerVerificationPolicy.unverifiedLine("preview")
        assertTrue(line.contains("Ho gaya lagta hai, par main verify nahi kar paya"))
        assertTrue(line.contains("preview"))
    }

    @Test
    fun fixLinesNameTheMissingArtifact() {
        val detail = TalkerVerificationPolicy.mismatchDetail(
            listOf(check("github_commit:github:o/r", TalkerVerificationPolicy.RefOutcome.FAILED, "tree 404"))
        )
        assertTrue(detail.contains("github_commit:github:o/r"))
        assertTrue(detail.contains("tree 404"))
    }

    @Test
    fun artifactPhraseNamesTheUncheckableKind() {
        val phrase = TalkerVerificationPolicy.artifactPhraseFor(
            listOf(check("preview_http:preview:x", TalkerVerificationPolicy.RefOutcome.UNCHECKABLE))
        )
        assertEquals("preview", phrase)
    }
}
