package com.jarvis.autonomous.data

/**
 * Captcha auto-solve policy (DESIGN §3, §5, §12 Q5 — frozen numbers).
 *
 * Pure JVM, no Android imports, so the local kotlinc + JUnitCore
 * harness and CI testDebugUnitTest can exercise it directly.
 *
 * Frozen constants (do NOT re-derive):
 * - MIN_SOLVE_CONFIDENCE = 0.80 (below = attempt fails, never submitted)
 * - MAX_AUTO_SOLVE_ATTEMPTS = 3 (one per hub chain leg)
 * - CALL_TIMEOUT_MS = 15_000 (per vision call, client-side)
 * - PHASE_BUDGET_MS = 60_000 (whole auto phase per challenge)
 * - MAX_SUBMISSIONS_PER_CHALLENGE = 1 (a wrong submission ends auto
 *   for that challenge — no second auto round)
 * - MAX_AUTO_SOLVED_PER_TASK = 2 (3rd challenge in the same browser
 *   task = human-only)
 * - MAX_AUTO_SOLVES_PER_DAY = 10 (UTC day; enforced hub-side from
 *   the D1 audit table — the app cannot count other devices' rows,
 *   so the client constant is the documented bound, not the enforcer)
 *
 * Scope (§5): ONLY recaptcha_grid, hcaptcha_grid and simple distorted
 * text. Audio / Turnstile / reCAPTCHA v3 / sliders / GeeTest /
 * animated / multi-round grids are NEVER auto-solved.
 *
 * The answer (tile indices / text) is NEVER stored or logged — this
 * file only validates it in memory and computes tap geometry.
 */
object CaptchaSolvePolicy {

    const val MIN_SOLVE_CONFIDENCE = 0.80
    const val MAX_AUTO_SOLVE_ATTEMPTS = 3
    const val CALL_TIMEOUT_MS = 15_000L
    const val PHASE_BUDGET_MS = 60_000L
    const val MAX_SUBMISSIONS_PER_CHALLENGE = 1
    const val MAX_AUTO_SOLVED_PER_TASK = 2
    const val MAX_AUTO_SOLVES_PER_DAY = 10

    /** Challenge types the hub contract knows (§2.4). */
    const val TYPE_RECAPTCHA_GRID = "recaptcha_grid"
    const val TYPE_HCAPTCHA_GRID = "hcaptcha_grid"
    const val TYPE_TEXT = "text"

    /**
     * R3.7 — DETECT-ONLY hub types (D3 lock): slider + GeeTest are
     * KNOWN challenge types (the hub detects + audits them) but are
     * NEVER auto-solved — they stay in NEVER_AUTO_TYPES below and
     * isAutoSolvableType still returns false for them. The mapping
     * exists so the type flows to the human-assist card + hub audit
     * instead of being dropped to null/"unknown".
     */
    const val TYPE_SLIDER = "slider"
    const val TYPE_GEETEST = "geetest"

    /** Types that are NEVER auto-solved, whatever the flag says (§5). */
    val NEVER_AUTO_TYPES = setOf("audio", "turnstile", "recaptcha_v3", "slider", "geetest")

    /** Auto-solvable challenge types (§5 scope list). */
    fun isAutoSolvableType(challengeType: String?): Boolean =
        challengeType == TYPE_RECAPTCHA_GRID ||
            challengeType == TYPE_HCAPTCHA_GRID ||
            challengeType == TYPE_TEXT

    /**
     * Maps the engine's DOM-detection string (BrowserScreen
     * CAPTCHA_CHECK_JS) to a hub challenge_type, or null when the
     * detected challenge is outside the auto scope (turnstile /
     * unknown / anything else → human-only, unchanged).
     * R3.7: "slider" and "geetest" map to their hub KNOWN types for
     * detection + human-assist routing only — isAutoSolvableType
     * still returns false for them (D3 lock: never auto-solved).
     */
    fun challengeTypeForDetected(detected: String): String? = when (detected) {
        "recaptcha" -> TYPE_RECAPTCHA_GRID
        "hcaptcha" -> TYPE_HCAPTCHA_GRID
        "text" -> TYPE_TEXT
        "slider" -> TYPE_SLIDER
        "geetest" -> TYPE_GEETEST
        else -> null
    }

    /**
     * The single gate for starting an auto-solve phase. The flag is
     * re-read by the caller on every challenge (never cached across
     * tasks); revoking mid-task takes effect on the next challenge.
     * `autoSolvedInTask` counts challenges already auto-solved in
     * this browser task; `alreadySubmitted` is true once this
     * challenge has had its one submission.
     */
    fun shouldAutoSolve(
        flagAllowed: Boolean,
        challengeType: String?,
        autoSolvedInTask: Int,
        alreadySubmitted: Boolean
    ): Boolean {
        if (!flagAllowed) return false
        if (!isAutoSolvableType(challengeType)) return false
        if (alreadySubmitted) return false
        if (autoSolvedInTask >= MAX_AUTO_SOLVED_PER_TASK) return false
        return true
    }

    /** A model answer is submittable only at or above 0.80 (§3.1). */
    fun isConfident(confidence: Double?): Boolean =
        confidence != null && confidence in MIN_SOLVE_CONFIDENCE..1.0

    // ---- Answer parsing / validation (§2.2 schema) -------------------
    // Hand-rolled minimal JSON extraction (no org.json here — this
    // file must stay pure-JVM for the local harness). The client
    // re-validates with the same rules before applying anything.

    sealed interface SolveAnswer {
        data class Tiles(val rows: Int, val cols: Int, val tiles: List<Int>) : SolveAnswer
        data class Text(val text: String) : SolveAnswer
    }

    private fun extractString(json: String, key: String): String? {
        val needle = "\"$key\""
        val i = json.indexOf(needle)
        if (i < 0) return null
        val colon = json.indexOf(':', i + needle.length)
        if (colon < 0) return null
        val start = json.indexOf('"', colon + 1)
        if (start < 0) return null
        val end = json.indexOf('"', start + 1)
        if (end < 0) return null
        return json.substring(start + 1, end)
    }

    private fun extractDouble(json: String, key: String): Double? {
        val needle = "\"$key\""
        val i = json.indexOf(needle)
        if (i < 0) return null
        val colon = json.indexOf(':', i + needle.length)
        if (colon < 0) return null
        val raw = json.substring(colon + 1).trimStart()
            .takeWhile { it.isDigit() || it == '.' || it == '-' }
        return raw.toDoubleOrNull()
    }

    private fun extractInt(json: String, key: String): Int? =
        extractDouble(json, key)?.takeIf { it == kotlin.math.floor(it) }?.toInt()

    private fun extractIntArray(json: String, key: String): List<Int>? {
        val needle = "\"$key\""
        val i = json.indexOf(needle)
        if (i < 0) return null
        val open = json.indexOf('[', i + needle.length)
        if (open < 0) return null
        val close = json.indexOf(']', open)
        if (close < 0) return null
        val inner = json.substring(open + 1, close).trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(",").map { it.trim().toIntOrNull() ?: return null }
    }

    /**
     * Parses + validates a hub answer object. Returns null on ANY
     * defect (bad JSON shape, missing fields, out-of-range tile,
     * confidence absent/outside 0–1, empty text) — a null is a parse
     * failure, never a submission. Confidence is validated for
     * range here; the ≥0.80 submit gate is [isConfident] and is
     * checked separately by the caller.
     */
    fun parseAnswer(answerJson: String?): SolveAnswer? {
        if (answerJson.isNullOrBlank()) return null
        val kind = extractString(answerJson, "kind") ?: return null
        val confidence = extractDouble(answerJson, "confidence") ?: return null
        if (confidence !in 0.0..1.0) return null
        return when (kind) {
            "tiles" -> {
                val rows = extractInt(answerJson, "grid_rows") ?: return null
                val cols = extractInt(answerJson, "grid_cols") ?: return null
                if (rows !in 1..6 || cols !in 1..6) return null
                val tiles = extractIntArray(answerJson, "tiles") ?: return null
                if (tiles.isEmpty()) return null
                if (tiles.any { it < 0 || it >= rows * cols }) return null
                if (tiles.distinct().size != tiles.size) return null
                SolveAnswer.Tiles(rows, cols, tiles)
            }
            "text" -> {
                val text = extractString(answerJson, "text") ?: return null
                if (text.isBlank() || text.length > 64) return null
                SolveAnswer.Text(text)
            }
            else -> null
        }
    }

    /** Confidence of a parsed answer object (range already checked). */
    fun answerConfidence(answerJson: String?): Double? {
        if (answerJson.isNullOrBlank()) return null
        return extractDouble(answerJson, "confidence")?.takeIf { it in 0.0..1.0 }
    }

    // ---- Grid tap geometry (§2.5, §12 Q5) ---------------------------
    // Model-reported grid_rows/grid_cols are trusted for geometry,
    // with app-side sanity checks: out-of-range indices / dims are
    // REJECTED (null), never coerced; every tap point is clamped
    // inside the widget rect so a rounding error can never tap
    // outside the challenge widget.

    /** Widget rect in CSS px, as measured by getBoundingClientRect. */
    data class WidgetRect(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
    }

    fun parseWidgetRect(raw: String?): WidgetRect? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(",").map { it.trim().toFloatOrNull() ?: return null }
        if (parts.size != 4) return null
        val (x, y, w, h) = parts
        if (w <= 0f || h <= 0f) return null
        return WidgetRect(x, y, w, h)
    }

    /**
     * Screen-coordinate centres for the given tile indices.
     * `viewLeft/viewTop` = WebView location on screen (px),
     * `viewScale` = WebView.scale (CSS px → screen px).
     * Returns null if rows/cols are out of range, any index is
     * out of range, or the rect is degenerate — the caller must
     * fall to human-assist, never tap and hope.
     */
    fun tileCenters(
        rect: WidgetRect,
        rows: Int,
        cols: Int,
        tiles: List<Int>,
        viewLeft: Int,
        viewTop: Int,
        viewScale: Float
    ): List<Pair<Int, Int>>? {
        if (rows !in 1..6 || cols !in 1..6) return null
        if (tiles.isEmpty()) return null
        if (tiles.any { it < 0 || it >= rows * cols }) return null
        if (rect.width <= 0f || rect.height <= 0f) return null
        val cellW = rect.width / cols
        val cellH = rect.height / rows
        return tiles.map { idx ->
            val row = idx / cols
            val col = idx % cols
            val cssX = rect.left + (col + 0.5f) * cellW
            val cssY = rect.top + (row + 0.5f) * cellH
            // Clamp inside the widget rect (1px margin) — a tap
            // must never land outside the challenge widget.
            val clampedX = cssX.coerceIn(rect.left + 1f, rect.right - 1f)
            val clampedY = cssY.coerceIn(rect.top + 1f, rect.bottom - 1f)
            val screenX = (viewLeft + clampedX * viewScale).toInt()
            val screenY = (viewTop + clampedY * viewScale).toInt()
            screenX to screenY
        }
    }

    /**
     * Verify-button tap: bottom-centre of the widget rect (the
     * VERIFY button sits below the grid inside the challenge
     * iframe, unreachable by DOM). Best-effort geometry — S-T4 is
     * the ship gate for the whole grid path.
     */
    fun verifyTapPoint(
        rect: WidgetRect,
        viewLeft: Int,
        viewTop: Int,
        viewScale: Float
    ): Pair<Int, Int>? {
        if (rect.width <= 0f || rect.height <= 0f) return null
        val cssX = rect.left + rect.width / 2f
        val cssY = (rect.bottom - 24f).coerceIn(rect.top + 1f, rect.bottom - 1f)
        return ((viewLeft + cssX * viewScale).toInt() to (viewTop + cssY * viewScale).toInt())
    }
}
