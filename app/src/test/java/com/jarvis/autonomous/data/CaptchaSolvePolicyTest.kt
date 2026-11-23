package com.jarvis.autonomous.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Captcha auto-solve policy tests (DESIGN §3, §4.2, §12 Q5).
 * Pure JVM — runs in the local kotlinc + JUnitCore harness and in
 * CI testDebugUnitTest. No Android, no network, no keys.
 *
 * Gates covered: the 0.79/0.80 confidence boundary, the per-task
 * cap (2) and one-submission rule, grid-geometry sanity (reject,
 * never coerce), and the on-device log cap (100) + answer-free
 * assertion.
 */
class CaptchaSolvePolicyTest {

    // ---- Confidence boundary (§3.1: MIN = 0.80) ----

    @Test
    fun confidence_0_80_isSubmittable() {
        assertTrue(CaptchaSolvePolicy.isConfident(0.80))
    }

    @Test
    fun confidence_0_79_isNotSubmittable() {
        assertFalse(CaptchaSolvePolicy.isConfident(0.79))
    }

    @Test
    fun confidence_missingOrOutOfRange_isNotSubmittable() {
        assertFalse(CaptchaSolvePolicy.isConfident(null))
        assertFalse(CaptchaSolvePolicy.isConfident(1.01))
        assertFalse(CaptchaSolvePolicy.isConfident(-0.1))
    }

    // ---- Scope (§5) ----

    @Test
    fun gridsAndTextAreSolvable_audioTurnstileV3Never() {
        assertTrue(CaptchaSolvePolicy.isAutoSolvableType("recaptcha_grid"))
        assertTrue(CaptchaSolvePolicy.isAutoSolvableType("hcaptcha_grid"))
        assertTrue(CaptchaSolvePolicy.isAutoSolvableType("text"))
        assertFalse(CaptchaSolvePolicy.isAutoSolvableType("audio"))
        assertFalse(CaptchaSolvePolicy.isAutoSolvableType("turnstile"))
        assertFalse(CaptchaSolvePolicy.isAutoSolvableType("recaptcha_v3"))
        assertFalse(CaptchaSolvePolicy.isAutoSolvableType("slider"))
        assertFalse(CaptchaSolvePolicy.isAutoSolvableType(null))
    }

    @Test
    fun detectedChallengeMapsToHubType() {
        assertEquals("recaptcha_grid", CaptchaSolvePolicy.challengeTypeForDetected("recaptcha"))
        assertEquals("hcaptcha_grid", CaptchaSolvePolicy.challengeTypeForDetected("hcaptcha"))
        assertEquals("text", CaptchaSolvePolicy.challengeTypeForDetected("text"))
        assertNull(CaptchaSolvePolicy.challengeTypeForDetected("turnstile"))
        assertNull(CaptchaSolvePolicy.challengeTypeForDetected("unknown"))
    }

    // ---- Gate: flag re-read + caps (§1.1, §3.1) ----

    @Test
    fun flagOff_neverAutoSolves() {
        assertFalse(CaptchaSolvePolicy.shouldAutoSolve(false, "recaptcha_grid", 0, false))
    }

    @Test
    fun flagOn_grid_firstChallenge_autoSolves() {
        assertTrue(CaptchaSolvePolicy.shouldAutoSolve(true, "recaptcha_grid", 0, false))
        assertTrue(CaptchaSolvePolicy.shouldAutoSolve(true, "text", 1, false))
    }

    @Test
    fun thirdChallengeInTask_isHumanOnly() {
        assertFalse(CaptchaSolvePolicy.shouldAutoSolve(true, "recaptcha_grid", 2, false))
        assertFalse(CaptchaSolvePolicy.shouldAutoSolve(true, "recaptcha_grid", 3, false))
    }

    @Test
    fun alreadySubmittedChallenge_neverRetriesAuto() {
        assertFalse(CaptchaSolvePolicy.shouldAutoSolve(true, "recaptcha_grid", 0, true))
    }

    // ---- Answer schema (§2.2) ----

    @Test
    fun validTilesAnswerParses() {
        val ans = CaptchaSolvePolicy.parseAnswer(
            """{"kind":"tiles","grid_rows":3,"grid_cols":3,"tiles":[0,4,7],"confidence":0.87}"""
        )
        assertEquals(CaptchaSolvePolicy.SolveAnswer.Tiles(3, 3, listOf(0, 4, 7)), ans)
    }

    @Test
    fun validTextAnswerParses() {
        val ans = CaptchaSolvePolicy.parseAnswer(
            """{"kind":"text","text":"ab12cd","confidence":0.91}"""
        )
        assertEquals(CaptchaSolvePolicy.SolveAnswer.Text("ab12cd"), ans)
    }

    @Test
    fun outOfRangeTile_isParseFailure() {
        assertNull(CaptchaSolvePolicy.parseAnswer(
            """{"kind":"tiles","grid_rows":3,"grid_cols":3,"tiles":[0,9],"confidence":0.9}"""
        ))
    }

    @Test
    fun missingConfidence_isParseFailure() {
        assertNull(CaptchaSolvePolicy.parseAnswer(
            """{"kind":"tiles","grid_rows":3,"grid_cols":3,"tiles":[0,4]}"""
        ))
        assertNull(CaptchaSolvePolicy.parseAnswer(
            """{"kind":"text","text":"ab12cd"}"""
        ))
    }

    @Test
    fun confidenceOutsideRange_isParseFailure() {
        assertNull(CaptchaSolvePolicy.parseAnswer(
            """{"kind":"text","text":"ab12cd","confidence":1.5}"""
        ))
    }

    @Test
    fun badKindOrEmptyTiles_isParseFailure() {
        assertNull(CaptchaSolvePolicy.parseAnswer(
            """{"kind":"prose","text":"hello","confidence":0.9}"""
        ))
        assertNull(CaptchaSolvePolicy.parseAnswer(
            """{"kind":"tiles","grid_rows":3,"grid_cols":3,"tiles":[],"confidence":0.9}"""
        ))
        assertNull(CaptchaSolvePolicy.parseAnswer("not json at all"))
        assertNull(CaptchaSolvePolicy.parseAnswer(null))
    }

    // ---- Grid geometry (§12 Q5: reject, never coerce; clamp inside) ----

    private val rect = CaptchaSolvePolicy.WidgetRect(100f, 200f, 300f, 300f)

    @Test
    fun tileCenters_landInsideWidget() {
        val points = CaptchaSolvePolicy.tileCenters(rect, 3, 3, listOf(0, 4, 8), 0, 0, 1f)
        assertNotNull(points)
        // Tile 0 = top-left centre (150, 250); tile 4 = centre (250, 350); tile 8 = bottom-right (350, 450).
        assertEquals(listOf(150 to 250, 250 to 350, 350 to 450), points)
    }

    @Test
    fun tileCenters_outOfRangeIndex_rejected() {
        assertNull(CaptchaSolvePolicy.tileCenters(rect, 3, 3, listOf(9), 0, 0, 1f))
        assertNull(CaptchaSolvePolicy.tileCenters(rect, 3, 3, listOf(-1), 0, 0, 1f))
    }

    @Test
    fun tileCenters_badDims_rejected() {
        assertNull(CaptchaSolvePolicy.tileCenters(rect, 0, 3, listOf(0), 0, 0, 1f))
        assertNull(CaptchaSolvePolicy.tileCenters(rect, 3, 7, listOf(0), 0, 0, 1f))
        assertNull(CaptchaSolvePolicy.tileCenters(rect, 3, 3, emptyList(), 0, 0, 1f))
    }

    @Test
    fun tileCenters_respectViewOffsetAndScale() {
        val points = CaptchaSolvePolicy.tileCenters(rect, 3, 3, listOf(4), 10, 20, 2f)
        // Centre CSS (250, 350) * 2 + offset (10, 20) = (510, 720).
        assertEquals(listOf(510 to 720), points)
    }

    @Test
    fun widgetRect_badInput_rejected() {
        assertNull(CaptchaSolvePolicy.parseWidgetRect(null))
        assertNull(CaptchaSolvePolicy.parseWidgetRect(""))
        assertNull(CaptchaSolvePolicy.parseWidgetRect("10,20,0,300"))
        assertNull(CaptchaSolvePolicy.parseWidgetRect("10,20,300"))
    }

    // ---- On-device log (§4.2): cap 100 + answer-free ----

    @Test
    fun logCap_keepsLast100() {
        val lines = (1..150).map { "line-$it" }
        val capped = CaptchaSolveLogFormat.capLines(lines)
        assertEquals(100, capped.size)
        assertEquals("line-51", capped.first())
        assertEquals("line-150", capped.last())
    }

    @Test
    fun logLine_carriesNoAnswerField() {
        val entry = CaptchaSolveLogFormat.Entry(
            solveId = "cs-0123456789abcdef",
            taskId = "bt-0123456789abcdef",
            site = "example.com",
            challengeType = "recaptcha_grid",
            model = "gemini-3.5-flash-lite",
            outcome = "submitted_ok",
            confidence = 0.87,
            latencyMs = 8420L,
            imageSha256 = "deadbeef"
        )
        val line = CaptchaSolveLogFormat.formatLine(entry)
        // The answer itself ([0,4,7] / tile indices) must never
        // appear in the line; only metadata + the image hash.
        assertFalse(line.contains("[0, 4, 7]"))
        assertFalse(line.contains("tiles"))
        assertFalse(line.contains("answer"))
        assertTrue(line.contains("cs-0123456789abcdef"))
        assertTrue(line.contains("submitted_ok"))
        assertTrue(line.contains("deadbeef"))
        assertEquals(CaptchaSolveLogFormat.MAX_ENTRIES, 100)
    }

    // ---- R3.7: slider / GeeTest are DETECTED but NEVER auto-solved (D3 lock) ----

    @Test
    fun sliderAndGeetest_detectedButNeverAutoSolvable() {
        assertEquals("slider", CaptchaSolvePolicy.challengeTypeForDetected("slider"))
        assertEquals("geetest", CaptchaSolvePolicy.challengeTypeForDetected("geetest"))
        assertFalse(CaptchaSolvePolicy.isAutoSolvableType("slider"))
        assertFalse(CaptchaSolvePolicy.isAutoSolvableType("geetest"))
        assertFalse(CaptchaSolvePolicy.isAutoSolvableType(CaptchaSolvePolicy.TYPE_SLIDER))
        assertFalse(CaptchaSolvePolicy.isAutoSolvableType(CaptchaSolvePolicy.TYPE_GEETEST))
    }

    @Test
    fun sliderAndGeetest_shouldAutoSolve_isAlwaysFalse() {
        assertFalse(CaptchaSolvePolicy.shouldAutoSolve(true, "slider", 0, false))
        assertFalse(CaptchaSolvePolicy.shouldAutoSolve(true, "geetest", 0, false))
        assertFalse(CaptchaSolvePolicy.shouldAutoSolve(true, CaptchaSolvePolicy.TYPE_SLIDER, 0, false))
        assertFalse(CaptchaSolvePolicy.shouldAutoSolve(true, CaptchaSolvePolicy.TYPE_GEETEST, 0, false))
    }

    @Test
    fun turnstile_stillUnmapped_humanOnly() {
        assertNull(CaptchaSolvePolicy.challengeTypeForDetected("turnstile"))
        assertFalse(CaptchaSolvePolicy.isAutoSolvableType("turnstile"))
    }

}
