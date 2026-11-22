package com.jarvis.autonomous.agent

import com.jarvis.autonomous.data.AttemptGate
import com.jarvis.autonomous.data.BlockRecord
import com.jarvis.autonomous.data.SelfExtensionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * DESIGN §8.4 — anti-loop + budget + no-secret tests, against the
 * real file-backed [SelfExtensionStore] in a temp dir.
 */
class SelfExtensionAntiLoopTest {

    private fun tempStore(): SelfExtensionStore {
        val dir = Files.createTempDirectory("selfext-test-").toFile()
        return SelfExtensionStore(dir)
    }

    private fun signature(capability: String = "convert-pdf-to-text"): String =
        SelfExtensionDetector.sha256Hex("$capability|BUILD|BUILD")

    @Test
    fun antiLoopSecondAttemptSameRunDroppedBeforeNetwork() {
        val store = tempStore()
        val sig = signature()
        val now = System.currentTimeMillis()
        // First attempt in the run is allowed …
        assertTrue(store.canAttempt(sig, now, alreadyAttempted = false) is AttemptGate.Allowed)
        // … the second trigger in the SAME run is dropped with no
        // network call (the loop checks this before researching).
        val denied = store.canAttempt(sig, now, alreadyAttempted = true)
        assertTrue(denied is AttemptGate.Denied)
        assertEquals("already_attempted_this_run", (denied as AttemptGate.Denied).reason)
    }

    @Test
    fun antiLoopCooldownBlocksSameSignatureFor24h() {
        val store = tempStore()
        val sig = signature()
        val now = System.currentTimeMillis()
        store.recordBlock(BlockRecord(sig, "convert-pdf-to-text", "give_up", "no_candidates", now))
        val denied = store.canAttempt(sig, now + 60_000L)
        assertTrue(denied is AttemptGate.Denied)
        assertEquals("cooldown", (denied as AttemptGate.Denied).reason)
        // After 24 h the same signature may be tried again.
        assertTrue(store.canAttempt(sig, now + 25 * 60 * 60 * 1000L) is AttemptGate.Allowed)
    }

    @Test
    fun antiLoopFourthResearchRunIn24hRefused() {
        val store = tempStore()
        val now = System.currentTimeMillis()
        repeat(3) { store.recordResearchRun(now - it * 60_000L) }
        val denied = store.canAttempt(signature("other-capability"), now)
        assertTrue(denied is AttemptGate.Denied)
        assertEquals("daily_cap", (denied as AttemptGate.Denied).reason)
    }

    @Test
    fun antiLoopThreeRunsAllowed() {
        val store = tempStore()
        val now = System.currentTimeMillis()
        repeat(2) { store.recordResearchRun(now - it * 60_000L) }
        assertTrue(store.canAttempt(signature(), now) is AttemptGate.Allowed)
    }

    @Test
    fun budgetReportCacheReusedWithin7Days() {
        val store = tempStore()
        val sig = signature()
        val now = System.currentTimeMillis()
        val json = "{\"capability\":\"convert-pdf-to-text\",\"candidates\":[]}"
        assertTrue(store.saveReport(sig, json))
        assertEquals(json, store.cachedReport(sig, now + 6 * 24 * 60 * 60 * 1000L))
        // After 7 days the cache is stale: a fresh run is allowed.
        assertNull(store.cachedReport(sig, now + 8 * 24 * 60 * 60 * 1000L))
    }

    @Test
    fun budgetStoreCapEvictsOldestReportsNeverBlocks() {
        val store = tempStore()
        val now = System.currentTimeMillis()
        // Fill the dir with old reports (~5 MB total).
        val big = "x".repeat(1024 * 1024)
        repeat(5) { i ->
            val sig = SelfExtensionDetector.sha256Hex("cap-$i|BUILD|BUILD")
            store.saveReport(sig, "{\"capability\":\"cap-$i\",\"pad\":\"$big\"}")
        }
        // A block record is the anti-loop memory: it must survive.
        val blockSig = signature()
        store.recordBlock(BlockRecord(blockSig, "convert-pdf-to-text", "give_up", "no_candidates", now))
        assertEquals(1, store.loadBlocks().size)
        // One more report write evicts the OLDEST report first …
        val newSig = SelfExtensionDetector.sha256Hex("cap-new|BUILD|BUILD")
        store.saveReport(newSig, "{\"capability\":\"cap-new\",\"pad\":\"$big\"}")
        assertTrue(store.totalBytes() <= SelfExtensionBudgets.STORE_CAP_BYTES)
        // … and the block is still there.
        assertEquals(1, store.loadBlocks().size)
        assertEquals(blockSig, store.loadBlocks().first().signature)
    }

    @Test
    fun noSecretInLog() {
        // A failure whose text carries a secret must never land in
        // any persisted record: the detector never reads goal text,
        // and block records carry capability strings only.
        val store = tempStore()
        val secret = "sk-live-SECRET-987654321"
        val failure = ExecutorFailure(
            stepKind = "BUILD",
            action = "BUILD",
            reason = "Unknown step kind: FAKE_KIND_X",
            goalText = "my key is $secret, email someone@example.com"
        )
        val detection = SelfExtensionDetector.detect(failure)
        val missing = detection as? Detection.MissingCapability
        assertNotNull(missing)
        store.recordBlock(
            BlockRecord(missing!!.signature, missing.capability, "give_up", "no_candidates", System.currentTimeMillis())
        )
        val root = File(storeRootOf(store))
        val allText = root.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(allText.contains(secret))
        assertFalse(allText.contains("someone@example.com"))
    }

    @Test
    fun cachedReportRoundTripsSuggestedSteps() {
        // 1.11.0-PRO cache fix (coordinator pre-push review): a
        // cached report MUST carry the candidate's suggested steps —
        // without them a cache hit yields empty step lists and
        // InstallGate refuses `no_steps`, so the loop gives up
        // instead of installing for 7 days.
        val store = tempStore()
        val sig = signature()
        val candidate = ResearchCandidate(
            name = "pdfbox",
            sourceUrl = "https://github.com/apache/pdfbox",
            licence = "Apache-2.0",
            costUsd = "\$0",
            needsCard = false,
            needsAccount = false,
            needsNetwork = false,
            lastUpdated = "2026-01-15",
            hostRequirements = listOf("android-api-26+"),
            kind = SkillKind.STEP,
            evidence = listOf("Apache License 2.0"),
            stale = false,
            suggestedSteps = listOf(
                SkillStep(action = "OPEN_APP", target = "Files"),
                SkillStep(action = "TAP_NODE", target = "Convert", input = null),
                SkillStep(action = "TYPE_TEXT", target = "query", input = "hello")
            ),
            signalsComplete = true
        )
        val json = store.reportToJson(
            capability = "convert-pdf-to-text",
            signature = sig,
            researchedAtMs = 1_700_000_000_000L,
            llmCalls = 1,
            fetches = 1,
            durationMs = 1234L,
            outcome = "CANDIDATES_FOUND",
            failureReason = "",
            candidates = listOf(candidate)
        )
        val parsed = store.parseReport(json, "convert-pdf-to-text", sig)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.candidates.size)
        val back = parsed.candidates.first()
        assertEquals("pdfbox", back.name)
        assertEquals("Apache-2.0", back.licence)
        assertEquals(listOf("android-api-26+"), back.hostRequirements)
        assertFalse(back.stale)
        assertTrue(back.signalsComplete)
        assertEquals(3, back.suggestedSteps.size)
        assertEquals("OPEN_APP", back.suggestedSteps[0].action)
        assertEquals("Files", back.suggestedSteps[0].target)
        assertEquals("TAP_NODE", back.suggestedSteps[1].action)
        assertEquals("TYPE_TEXT", back.suggestedSteps[2].action)
        assertEquals("hello", back.suggestedSteps[2].input)
        // And through the file cache: save + re-read + parse.
        assertTrue(store.saveReport(sig, json))
        val cached = store.cachedReport(sig, System.currentTimeMillis())
        assertNotNull(cached)
        val reparsed = store.parseReport(cached!!, "convert-pdf-to-text", sig)
        assertEquals(3, reparsed!!.candidates.first().suggestedSteps.size)
    }

    @Test
    fun proposalsRoundTrip() {
        val store = tempStore()
        val record = com.jarvis.autonomous.data.ProposalRecord(
            id = "proposal-1",
            capability = "convert-pdf-to-text",
            signature = signature(),
            licence = "MIT",
            costUsd = "\$0",
            sourceUrl = "https://github.com/example/tool",
            kind = "CODE",
            status = "quarantined",
            atMs = System.currentTimeMillis()
        )
        assertTrue(store.saveProposal(record))
        assertEquals(1, store.loadProposals().size)
        assertTrue(store.updateProposalStatus("proposal-1", "approved_awaiting_release"))
        assertEquals("approved_awaiting_release", store.loadProposals().first().status)
    }

    private fun storeRootOf(store: SelfExtensionStore): String {
        // The temp dir is the store root; recover it via a block file.
        val field = SelfExtensionStore::class.java.getDeclaredField("rootDir")
        field.isAccessible = true
        return (field.get(store) as File).absolutePath
    }
}
