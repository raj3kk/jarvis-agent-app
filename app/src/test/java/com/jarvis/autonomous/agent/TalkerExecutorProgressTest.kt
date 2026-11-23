package com.jarvis.autonomous.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.9.0 (v2) — STEP_PROGRESS / FIX_REQUEST / FIX_RESULT round-trips
 * and the typed evidence refs (DESIGN §2.1, §4.2).
 *
 * Pure JVM tests: TalkerExecutorProgress has zero Android imports.
 */
class TalkerExecutorProgressTest {

    private fun progress() = ExecutorProgress(
        commandId = "cmd-1",
        stepIndex = 2,
        stepTotal = 5,
        stepLabel = "GitHub push",
        detail = "pushing 3 files",
        progressFraction = 0.4,
        etaSeconds = 90L,
        isMilestone = true,
        phase = "START",
        nextLabel = "Supabase tables"
    )

    @Test
    fun stepProgressRoundTrip() {
        val p = progress()
        val parsed = ExecutorProgress.fromJson(p.toJson())
        assertNotNull(parsed)
        assertEquals(p, parsed)
        assertTrue(parsed!!.validate().isEmpty())
        assertEquals(0.4, parsed.progressFraction, 0.0001)
    }

    @Test
    fun stepProgressRoundTripWithoutOptionalFields() {
        val p = ExecutorProgress(commandId = "c", stepIndex = 1, stepTotal = 0, stepLabel = "Kaam")
        val parsed = ExecutorProgress.fromJson(p.toJson())
        assertEquals(p, parsed)
        assertEquals(-1.0, parsed!!.progressFraction, 0.0001)
        assertEquals(-1L, parsed.etaSeconds)
        assertEquals("", parsed.phase)
        assertEquals("", parsed.nextLabel)
    }

    @Test
    fun progressFractionSurvivesAsQuotedString() {
        // The hand-rolled parser reads Long numbers only, so the
        // fraction is written quoted ("0.4") — pin the wire form.
        val json = progress().toJson()
        assertTrue(json.contains("\"progressFraction\":\"0.4\""))
        assertTrue(json.contains("\"etaSeconds\":90"))
    }

    @Test
    fun loopProgressUnknownTotalsStayNegativeOne() {
        val p = ExecutorProgress(commandId = "c", stepIndex = 3, stepTotal = 0, stepLabel = "WhatsApp bhejna")
        assertEquals(-1.0, p.progressFraction, 0.0001)
        assertEquals(-1L, p.etaSeconds)
        assertTrue(p.validate().isEmpty())
    }

    @Test
    fun invalidProgressRejected() {
        assertTrue(ExecutorProgress(commandId = "", stepIndex = 1, stepTotal = 1, stepLabel = "S").validate().isNotEmpty())
        assertTrue(ExecutorProgress(commandId = "c", stepIndex = 0, stepTotal = 1, stepLabel = "S").validate().isNotEmpty())
        assertTrue(ExecutorProgress(commandId = "c", stepIndex = 2, stepTotal = 1, stepLabel = "S").validate().isNotEmpty())
        assertTrue(ExecutorProgress(commandId = "c", stepIndex = 1, stepTotal = 1, stepLabel = "S", progressFraction = 1.5).validate().isNotEmpty())
        assertTrue(ExecutorProgress(commandId = "c", stepIndex = 1, stepTotal = 1, stepLabel = "S", progressFraction = -0.5).validate().isNotEmpty())
        assertNull(ExecutorProgress.fromJson("{}"))
        assertNull(ExecutorProgress.fromJson("garbage"))
    }

    @Test
    fun fixRequestRoundTrip() {
        val req = ExecutorFixRequest(
            commandId = "cmd-1",
            attempt = 1,
            maxAttempts = 2,
            missingRefs = listOf("preview_http:preview:dukaan-site"),
            reason = "preview nahi khul raha"
        )
        val parsed = ExecutorFixRequest.fromJson(req.toJson())
        assertEquals(req, parsed)
        assertTrue(parsed!!.validate().isEmpty())
    }

    @Test
    fun fixRequestRejectsOverBudgetAttemptAndEmptyRefs() {
        assertTrue(
            ExecutorFixRequest(commandId = "c", attempt = 3, maxAttempts = 2, missingRefs = listOf("x")).validate().isNotEmpty()
        )
        assertTrue(
            ExecutorFixRequest(commandId = "c", attempt = 1, maxAttempts = 2, missingRefs = emptyList()).validate().isNotEmpty()
        )
        assertTrue(ExecutorFixRequest(commandId = "c", attempt = 0, maxAttempts = 2, missingRefs = listOf("x")).validate().isNotEmpty())
        assertNull(ExecutorFixRequest.fromJson("{}"))
    }

    @Test
    fun fixResultRoundTrip() {
        val fixed = ExecutorFixResult(
            commandId = "cmd-1",
            attempt = 1,
            status = FixResultStatus.FIXED,
            freshEvidenceJson = EvidenceRefs.toJson(
                listOf(
                    EvidenceRef(EvidenceKind.PREVIEW_HTTP, "preview:dukaan-site", "http_200", "http://localhost:8080")
                )
            ),
            detail = "fixed: preview server restart"
        )
        val parsedFixed = ExecutorFixResult.fromJson(fixed.toJson())
        assertEquals(fixed, parsedFixed)
        assertTrue(parsedFixed!!.validate().isEmpty())

        val missing = ExecutorFixResult(
            commandId = "cmd-1",
            attempt = 2,
            status = FixResultStatus.STILL_MISSING,
            missingRefs = listOf("preview_http:preview:dukaan-site"),
            detail = "still missing: 1 ref(s)"
        )
        assertEquals(missing, ExecutorFixResult.fromJson(missing.toJson()))
        assertTrue(missing.validate().isEmpty())
    }

    @Test
    fun evidenceRefJsonRoundTripAndRefIds() {
        val ref = EvidenceRef(EvidenceKind.PREVIEW_HTTP, "preview:dukaan-site", "http_200", "http://localhost:8080")
        assertEquals("preview_http:preview:dukaan-site", ref.refId())
        assertEquals(
            EvidenceKind.GITHUB_COMMIT,
            EvidenceRef(EvidenceKind.GITHUB_COMMIT, "github:o/r", "commit_in_tree", "abc").kind
        )
        val list = listOf(ref)
        assertEquals(list, EvidenceRefs.parse(EvidenceRefs.toJson(list)))
    }

    @Test
    fun evidenceRefsCapAtEightWithPriorityOrder() {
        val kinds = listOf(
            EvidenceKind.MESSAGE_SENT, EvidenceKind.SCREEN_STATE, EvidenceKind.SUPABASE_TABLES,
            EvidenceKind.FILE_LIST, EvidenceKind.VERCEL_DEPLOY, EvidenceKind.GITHUB_COMMIT,
            EvidenceKind.PREVIEW_HTTP, EvidenceKind.MESSAGE_SENT, EvidenceKind.SCREEN_STATE,
            EvidenceKind.SUPABASE_TABLES
        )
        val refs = kinds.mapIndexed { i, k ->
            EvidenceRef(k, "ref-$i", "check", "v")
        }
        val capped = EvidenceRefs.cap(refs)
        assertEquals(8, capped.size)
        // PREVIEW_HTTP first, then GITHUB_COMMIT, then VERCEL_DEPLOY…
        assertEquals(EvidenceKind.PREVIEW_HTTP, capped[0].kind)
        assertEquals(EvidenceKind.GITHUB_COMMIT, capped[1].kind)
        assertEquals(EvidenceKind.VERCEL_DEPLOY, capped[2].kind)
        assertEquals(EvidenceKind.FILE_LIST, capped[3].kind)
    }

    @Test
    fun evidenceRefsDedupeAndMalformedJsonFailsClosed() {
        val ref = EvidenceRef(EvidenceKind.FILE_LIST, "project:x", "files_present", "a.html")
        val dupes = EvidenceRefs.cap(listOf(ref, ref.copy(value = "b.html")))
        assertEquals(1, dupes.size)
        assertTrue(EvidenceRefs.parse("not json").isEmpty())
        assertTrue(EvidenceRefs.parse("").isEmpty())
        assertTrue(EvidenceRefs.parse("""{"refs":[{"kind":"NOT_A_KIND","ref":"r","check":"c","value":"v"}]}""").isEmpty())
        // A bare array is the wrong shape — fail-closed, not half-trusted.
        assertTrue(EvidenceRefs.parse("""[{"kind":"PREVIEW_HTTP","ref":"r","check":"c","value":"v"}]""").isEmpty())
    }

    @Test
    fun executorResultCarriesEvidenceJson() {
        val refs = listOf(
            EvidenceRef(EvidenceKind.PREVIEW_HTTP, "preview:x", "http_200", "http://localhost:8080")
        )
        val result = ExecutorResult(
            commandId = "cmd-1",
            status = ExecutorResultStatus.DONE,
            summary = "website ban gayi",
            evidence = "plan complete",
            evidenceJson = EvidenceRefs.toJson(refs)
        )
        val parsed = ExecutorResult.fromJson(result.toJson())
        assertEquals(result, parsed)
        assertEquals(1, EvidenceRefs.parse(parsed!!.evidenceJson).size)
        // Existing v1 results without the field still parse.
        val legacy = ExecutorResult.fromJson(
            """{"version":1,"commandId":"c","status":"DONE","summary":"s","evidence":"d","finishedAtMs":1000}"""
        )
        assertNotNull(legacy)
        assertEquals("", legacy!!.evidenceJson)
    }
}
