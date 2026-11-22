package com.jarvis.autonomous.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN §8.4 — classifier boundary tests (server mirror, parent
 * decision §12-8: copy `classifySkillClass` as implemented).
 *
 * Note on ClassifierUnknownTest: DESIGN §8.4 expects APPROVAL for an
 * all-UNKNOWN candidate, but the server mirror rejects an UNKNOWN
 * licence at C1 (REJECT `unknown_licence`) — decision 8 governs, so
 * the test asserts REJECT here, and asserts APPROVAL for the
 * fail-closed case the mirror actually uses (incomplete signals).
 */
class CapabilityClassifierTest {

    private fun cleanCandidate(
        licence: String = "MIT",
        costUsd: String = "\$0",
        needsCard: Boolean = false,
        needsAccount: Boolean = false,
        needsNetwork: Boolean = false,
        lastUpdated: String? = "2026-11-01",
        kind: SkillKind = SkillKind.STEP,
        signalsComplete: Boolean = true
    ) = ResearchCandidate(
        name = "test-tool",
        sourceUrl = "https://github.com/example/test-tool",
        licence = licence,
        costUsd = costUsd,
        needsCard = needsCard,
        needsAccount = needsAccount,
        needsNetwork = needsNetwork,
        lastUpdated = lastUpdated,
        kind = kind,
        signalsComplete = signalsComplete
    )

    private val localSteps = listOf(
        SkillStep("OPEN_APP", "com.example.app"),
        SkillStep("WAIT", "")
    )

    @Test
    fun classifierPaidMonthlyPrice() {
        val c = CapabilityClassifier.classify(cleanCandidate(costUsd = "\$9/mo"), localSteps)
        assertEquals(ClassificationValue.REJECT, c.value)
        assertEquals("paid_or_card_required", c.reason)
    }

    @Test
    fun classifierPaidPremiumSignal() {
        val c = CapabilityClassifier.classify(cleanCandidate(costUsd = "premium"), localSteps)
        assertEquals(ClassificationValue.REJECT, c.value)
        assertEquals("paid_or_card_required", c.reason)
    }

    @Test
    fun classifierPaidCardRequired() {
        val c = CapabilityClassifier.classify(cleanCandidate(needsCard = true), localSteps)
        assertEquals(ClassificationValue.REJECT, c.value)
        assertEquals("paid_or_card_required", c.reason)
    }

    @Test
    fun classifierClosedSourceProprietary() {
        val c = CapabilityClassifier.classify(cleanCandidate(licence = "proprietary"), localSteps)
        assertEquals(ClassificationValue.REJECT, c.value)
        assertEquals("closed_source", c.reason)
    }

    @Test
    fun classifierClosedSourceFreeForPersonalUse() {
        val c = CapabilityClassifier.classify(cleanCandidate(licence = "free for personal use"), localSteps)
        assertEquals(ClassificationValue.REJECT, c.value)
        assertEquals("closed_source", c.reason)
    }

    @Test
    fun classifierUnknownLicence() {
        val c = CapabilityClassifier.classify(cleanCandidate(licence = "UNKNOWN"), localSteps)
        assertEquals(ClassificationValue.REJECT, c.value)
        assertEquals("unknown_licence", c.reason)
    }

    @Test
    fun classifierCopyleftNeverAuto() {
        for (licence in listOf("GPL-3.0", "AGPL-3.0", "LGPL-2.1")) {
            val c = CapabilityClassifier.classify(cleanCandidate(licence = licence), localSteps)
            assertEquals(ClassificationValue.APPROVAL, c.value)
            assertEquals("copyleft_needs_review", c.reason)
        }
    }

    @Test
    fun classifierAccountNeedsAccount() {
        val c = CapabilityClassifier.classify(cleanCandidate(needsAccount = true), localSteps)
        assertEquals(ClassificationValue.APPROVAL, c.value)
        assertEquals("needs_account_or_credential", c.reason)
    }

    @Test
    fun classifierAccountStepInputEmail() {
        val steps = listOf(SkillStep("TYPE_TEXT", "email", "user@example.com"))
        val c = CapabilityClassifier.classify(cleanCandidate(), steps)
        assertEquals(ClassificationValue.APPROVAL, c.value)
        assertEquals("needs_account_or_credential", c.reason)
    }

    @Test
    fun classifierAccountStepInputApiKey() {
        val steps = listOf(SkillStep("TYPE_TEXT", "key", "api_key=abc123"))
        val c = CapabilityClassifier.classify(cleanCandidate(), steps)
        assertEquals(ClassificationValue.APPROVAL, c.value)
        assertEquals("needs_account_or_credential", c.reason)
    }

    @Test
    fun classifierNetworkNeedsNetwork() {
        val c = CapabilityClassifier.classify(cleanCandidate(needsNetwork = true), localSteps)
        assertEquals(ClassificationValue.APPROVAL, c.value)
        assertEquals("network_or_exfil_risk", c.reason)
    }

    @Test
    fun classifierNetworkStepToThirdParty() {
        val steps = listOf(SkillStep("YOUTUBE_SEARCH", "", "some query"))
        val c = CapabilityClassifier.classify(cleanCandidate(), steps)
        assertEquals(ClassificationValue.APPROVAL, c.value)
        assertEquals("network_or_exfil_risk", c.reason)
    }

    @Test
    fun classifierSensitivePaymentAction() {
        val steps = listOf(SkillStep("PAYMENT", "pay"))
        val c = CapabilityClassifier.classify(cleanCandidate(), steps)
        assertEquals(ClassificationValue.APPROVAL, c.value)
        assertEquals("sensitive_action", c.reason)
    }

    @Test
    fun classifierSensitiveTapOnPayTarget() {
        val steps = listOf(SkillStep("TAP_NODE", "pay now button"))
        val c = CapabilityClassifier.classify(cleanCandidate(), steps)
        assertEquals(ClassificationValue.APPROVAL, c.value)
        assertEquals("sensitive_action", c.reason)
    }

    @Test
    fun classifierCodeNeedsNewAction() {
        // A procedure needing an action outside the frozen
        // KNOWN_ACTIONS forces the kind to CODE and is never AUTO.
        // (WHATSAPP_SEND is the real-world case: a genuine loop
        // action, but outside the server's 20-action frozen set —
        // it also trips C7 sensitive_action, asserted separately.)
        val steps = listOf(SkillStep("FOO_NEW_ACTION", "thing"))
        val c = CapabilityClassifier.classify(cleanCandidate(), steps)
        assertEquals(SkillKind.CODE, c.kind)
        assertEquals(ClassificationValue.APPROVAL, c.value)
        assertEquals("code_needs_review", c.reason)
    }

    @Test
    fun classifierWhatsAppSendIsCodeKindAndSensitive() {
        val steps = listOf(SkillStep("WHATSAPP_SEND", "contact", "hi"))
        val c = CapabilityClassifier.classify(cleanCandidate(), steps)
        assertEquals(SkillKind.CODE, c.kind)
        assertEquals(ClassificationValue.APPROVAL, c.value)
        assertEquals("sensitive_action", c.reason)
    }

    @Test
    fun classifierCodeKindExplicit() {
        val c = CapabilityClassifier.classify(cleanCandidate(kind = SkillKind.CODE), localSteps)
        assertEquals(SkillKind.CODE, c.kind)
        assertEquals(ClassificationValue.APPROVAL, c.value)
        assertEquals("code_needs_review", c.reason)
    }

    @Test
    fun classifierAutoHappy() {
        // The ONLY AUTO test: asserts every row that must NOT fire.
        val c = CapabilityClassifier.classify(cleanCandidate(), localSteps)
        assertEquals(ClassificationValue.AUTO, c.value)
        assertEquals("clean_local_step", c.reason)
        assertEquals(SkillKind.STEP, c.kind)
    }

    @Test
    fun classifierStaleOrUnknownMaintenanceIsApproval() {
        // C9 server reading: stale OR lastUpdated null -> APPROVAL.
        val stale = CapabilityClassifier.classify(
            cleanCandidate(lastUpdated = "2020-01-01"), localSteps
        )
        assertEquals(ClassificationValue.APPROVAL, stale.value)
        assertEquals("unmaintained_unknown", stale.reason)
        val unknown = CapabilityClassifier.classify(
            cleanCandidate(lastUpdated = null), localSteps
        )
        assertEquals(ClassificationValue.APPROVAL, unknown.value)
        assertEquals("unmaintained_unknown", unknown.reason)
    }

    @Test
    fun classifierIncompleteSignalsFailClosedToApproval() {
        val c = CapabilityClassifier.classify(
            cleanCandidate(signalsComplete = false), localSteps
        )
        assertEquals(ClassificationValue.APPROVAL, c.value)
        assertEquals("incomplete_research_signals", c.reason)
    }

    @Test
    fun classifierOversizeStepSkillRejected() {
        val steps = (1..21).map { SkillStep("WAIT", "") }
        val c = CapabilityClassifier.classify(cleanCandidate(), steps)
        assertEquals(ClassificationValue.REJECT, c.value)
        assertEquals("oversize", c.reason)
    }

    @Test
    fun classifierScanHitRejected() {
        val c = CapabilityClassifier.classify(cleanCandidate(), localSteps, scanHit = "eval(")
        assertEquals(ClassificationValue.REJECT, c.value)
        assertEquals("scan_hit:eval(", c.reason)
    }

    @Test
    fun classifierScoreRanksStaleBelowMaintained() {
        val fresh = cleanCandidate(lastUpdated = "2026-11-01")
        val stale = cleanCandidate(lastUpdated = "2020-01-01")
        assertTrue(CapabilityClassifier.score(fresh) > CapabilityClassifier.score(stale))
    }

    @Test
    fun classifierRejectNeverDowngradedByLaterRow() {
        // Paid + copyleft: the REJECT (C3) wins over the APPROVAL (C8).
        val c = CapabilityClassifier.classify(
            cleanCandidate(licence = "GPL-3.0", costUsd = "\$5"), localSteps
        )
        assertEquals(ClassificationValue.REJECT, c.value)
        assertEquals("paid_or_card_required", c.reason)
    }
}
