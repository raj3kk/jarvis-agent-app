package com.jarvis.autonomous.agent

import com.jarvis.autonomous.data.SelfExtendedSkillInput
import com.jarvis.autonomous.data.SelfExtensionStore
import com.jarvis.autonomous.data.SkillStorePort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * DESIGN §8.4 — installer refusal / budget / rollback / replay-gate
 * tests. The REAL installer runs against a harness-only stub
 * LearnedRoutineStore (in /tmp/selfext-harness/stubs) that mirrors
 * the real store's self-extension behaviour.
 */
class SelfExtensionInstallerTest {

    // The REAL installer + a stub LearnedRoutineStore (harness-only,
    // in /tmp/selfext-harness/stubs): install/rollback/markVerified
    // run through production code, not a fake.
    private fun realInstaller(): Pair<SelfExtensionInstaller, com.jarvis.autonomous.data.LearnedRoutineStore> {
        val store = com.jarvis.autonomous.data.LearnedRoutineStore(android.content.Context())
        val installer = SelfExtensionInstaller(
            context = android.content.Context(),
            routineStore = store,
            store = tempStore()
        )
        return installer to store
    }

    private fun tempStore(): SelfExtensionStore {
        val dir = Files.createTempDirectory("selfext-installer-test-").toFile()
        return SelfExtensionStore(dir)
    }

    private fun autoClassification() = Classification(
        ClassificationValue.AUTO, "clean_local_step", SkillKind.STEP
    )

    private fun cleanCandidate() = ResearchCandidate(
        name = "test-tool",
        sourceUrl = "https://github.com/example/test-tool",
        licence = "MIT",
        costUsd = "\$0",
        needsCard = false,
        needsAccount = false,
        needsNetwork = false,
        lastUpdated = "2026-11-01"
    )

    private val localSteps = listOf(
        SkillStep("OPEN_APP", "com.example.app"),
        SkillStep("WAIT", "")
    )

    private val signature = SelfExtensionDetector.sha256Hex("convert-pdf-to-text|BUILD|BUILD")

    @Test
    fun installerRefusesApprovalClassification() {
        val (installer, routineStore) = realInstaller()
        val approval = Classification(ClassificationValue.APPROVAL, "needs_account_or_credential", SkillKind.STEP)
        val result = installer.install(approval, cleanCandidate(), localSteps, "convert-pdf-to-text", signature)
        assertTrue(result is SelfExtensionInstaller.InstallResult.Refused)
        assertEquals(0, routineStore.loadSkillManifests().size)
    }

    @Test
    fun installerRefusesRejectClassification() {
        val (installer, routineStore) = realInstaller()
        val reject = Classification(ClassificationValue.REJECT, "paid_or_card_required", SkillKind.STEP)
        val result = installer.install(reject, cleanCandidate(), localSteps, "convert-pdf-to-text", signature)
        assertTrue(result is SelfExtensionInstaller.InstallResult.Refused)
        assertEquals(0, routineStore.loadSkillManifests().size)
    }

    @Test
    fun installerRefusesCodeKindEvenWhenAuto() {
        val (installer, routineStore) = realInstaller()
        val autoCode = Classification(ClassificationValue.AUTO, "clean_local_step", SkillKind.CODE)
        val result = installer.install(autoCode, cleanCandidate(), localSteps, "convert-pdf-to-text", signature)
        assertTrue(result is SelfExtensionInstaller.InstallResult.Refused)
        assertEquals(0, routineStore.loadSkillManifests().size)
    }

    @Test
    fun installerBudget21StepsRefused() {
        val steps = (1..21).map { SkillStep("WAIT", "") }
        val gate = InstallGate.check(autoClassification(), steps)
        assertTrue(gate is InstallGate.GateResult.Refused)
        assertEquals("oversize", (gate as InstallGate.GateResult.Refused).reason)
    }

    @Test
    fun installerBudgetManifestOver64KbRefused() {
        val gate = InstallGate.check(autoClassification(), localSteps, manifestBytes = 100L * 1024L)
        assertTrue(gate is InstallGate.GateResult.Refused)
        assertEquals("oversize", (gate as InstallGate.GateResult.Refused).reason)
    }

    @Test
    fun installerBudgetStoreFullRefused() {
        val gate = InstallGate.check(
            autoClassification(), localSteps,
            manifestBytes = 1024L,
            storeBytes = SelfExtensionBudgets.STORE_CAP_BYTES
        )
        assertTrue(gate is InstallGate.GateResult.Refused)
        assertEquals("store_full", (gate as InstallGate.GateResult.Refused).reason)
    }

    @Test
    fun installerBudgetUnknownActionRefused() {
        val gate = InstallGate.check(
            autoClassification(), listOf(SkillStep("WHATSAPP_SEND", "x", "hi"))
        )
        assertTrue(gate is InstallGate.GateResult.Refused)
        assertEquals("unknown_action", (gate as InstallGate.GateResult.Refused).reason)
    }

    @Test
    fun installerAutoHappyInstallsDisabledThenVerifierEnables() {
        val (installer, routineStore) = realInstaller()
        val result = installer.install(
            autoClassification(), cleanCandidate(), localSteps, "convert-pdf-to-text", signature
        )
        assertTrue(result is SelfExtensionInstaller.InstallResult.Installed)
        val skillId = (result as SelfExtensionInstaller.InstallResult.Installed).skillId
        // Installed-but-unverified: disabled, never replays.
        assertFalse(routineStore.isSkillEnabled(skillId))
        assertTrue(installer.markVerified(skillId, "selfext-test1234"))
        assertTrue(routineStore.isSkillEnabled(skillId))
    }

    @Test
    fun installerScanHitRefusedBeforeAnyWrite() {
        val (installer, routineStore) = realInstaller()
        val evilSteps = listOf(SkillStep("TYPE_TEXT", "code", "eval(x)"))
        val result = installer.install(
            autoClassification(), cleanCandidate(), evilSteps, "convert-pdf-to-text", signature
        )
        assertTrue(result is SelfExtensionInstaller.InstallResult.Refused)
        assertEquals(0, routineStore.loadSkillManifests().size)
    }

    @Test
    fun rollbackDeletesManifestAndRoutineBothAbsent() {
        val (installer, routineStore) = realInstaller()
        val result = installer.install(
            autoClassification(), cleanCandidate(), localSteps, "convert-pdf-to-text", signature
        )
        val skillId = (result as SelfExtensionInstaller.InstallResult.Installed).skillId
        assertEquals(1, routineStore.loadSkillManifests().size)
        assertTrue(installer.rollback(skillId))
        assertEquals(0, routineStore.loadSkillManifests().size)
        // A second rollback is a no-op that still reports absence.
        assertTrue(installer.rollback(skillId))
    }

    @Test
    fun unverifiedNeverReplays() {
        // §8 E3, fail-closed: origin=self_extended + autoAdded +
        // empty verification => disabled even with enabled=true.
        assertFalse(
            SelfExtensionReplayGate.isEnabled("self_extended", true, "", true)
        )
        // Once verified, the flag rules again.
        assertTrue(
            SelfExtensionReplayGate.isEnabled("self_extended", true, "verified:selfext-12345678", true)
        )
        assertFalse(
            SelfExtensionReplayGate.isEnabled("self_extended", true, "verified:selfext-12345678", false)
        )
        // Learned skills are untouched by the rule.
        assertTrue(SelfExtensionReplayGate.isEnabled("learned", false, "", true))
    }

    @Test
    fun noDynamicLoadInNewFiles() {
        // DESIGN §10 G0, in-test form: the new self-extension files
        // must contain zero dynamic-loading tokens. The working-copy
        // root comes from -Dselfext.workdir (harness passes it).
        val root = File(
            System.getProperty(
                "selfext.workdir",
                "/home/hatch/workspace/selfext-work/app/app/src/main/java/com/jarvis/autonomous"
            )
        )
        val forbidden = listOf(
            "DexClassLoader", "PathClassLoader", "ScriptEngine",
            "Runtime.exec", "System.load"
        )
        val files = listOf(
            "agent/SelfExtensionDetector.kt",
            "agent/CapabilityClassifier.kt",
            "agent/SelfExtensionScanner.kt",
            "agent/SelfExtensionResearcher.kt",
            "agent/SelfExtensionInstaller.kt",
            "agent/SelfExtensionVerifier.kt",
            "agent/SelfExtensionLoop.kt",
            "data/SelfExtensionStore.kt"
        )
        for (rel in files) {
            val file = File(root, rel)
            assertTrue("missing file: $rel", file.isFile)
            val text = file.readText()
            for (token in forbidden) {
                if (rel.endsWith("SelfExtensionScanner.kt")) {
                    // The scanner legitimately NAMES the patterns it
                    // detects (string/regex literals). Only a real
                    // USE counts: a construction/call (token + "(")
                    // or an import — both must be absent.
                    assertFalse("$rel uses forbidden token $token", text.contains("$token("))
                    assertFalse("$rel imports forbidden token $token", text.contains("import") && text.contains(token) && Regex("import\\s+.*$token").containsMatchIn(text))
                } else {
                    assertFalse("$rel contains forbidden token $token", text.contains(token))
                }
            }
        }
    }
}
