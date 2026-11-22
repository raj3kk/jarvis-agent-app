package com.jarvis.autonomous.data

/**
 * Pure part of the on-device auto-solve audit log (DESIGN §4.2).
 * No Android imports — the local kotlinc + JUnitCore harness and
 * CI testDebugUnitTest exercise this directly.
 *
 * ANSWER-FREE by construction: an entry carries solve_id, site,
 * challenge_type, model, outcome, confidence, latency and the
 * image SHA-256 ONLY. There is no answer field anywhere in this
 * file — the answer lives only in memory for the apply step (§2.5).
 */
object CaptchaSolveLogFormat {

    const val MAX_ENTRIES = 100

    /** One audit line. No answer / token / image field exists. */
    data class Entry(
        val timestampMs: Long = System.currentTimeMillis(),
        val solveId: String,
        val taskId: String,
        val site: String,
        val challengeType: String,
        val model: String,
        val outcome: String,
        val confidence: Double?,
        val latencyMs: Long,
        val imageSha256: String
    )

    /** Pipe-separated line — the unit tests assert the cap + the
     *  answer-free property against this exact shape. */
    fun formatLine(entry: Entry): String = listOf(
        entry.timestampMs.toString(),
        entry.solveId,
        entry.taskId,
        entry.site,
        entry.challengeType,
        entry.model,
        entry.outcome,
        entry.confidence?.toString() ?: "",
        entry.latencyMs.toString(),
        entry.imageSha256
    ).joinToString("|")

    /** Pure cap: keep only the last [MAX_ENTRIES] lines. */
    fun capLines(lines: List<String>): List<String> =
        if (lines.size <= MAX_ENTRIES) lines else lines.takeLast(MAX_ENTRIES)
}
