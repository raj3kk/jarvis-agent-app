package com.jarvis.autonomous.data

import android.content.Context
import java.io.File

/**
 * On-device auto-solve audit log (DESIGN §4.2).
 *
 * Append-only, capped at the last 100 entries (oldest dropped),
 * stored in the app's private files dir (NOT SharedPreferences,
 * NOT chat history). Viewable in-app from Settings → AI Brain
 * ("Auto-solve log" — last 20 lines).
 *
 * ANSWER-FREE by construction: an entry carries solve_id, site,
 * challenge_type, model, outcome, confidence, latency and the
 * image SHA-256 ONLY. There is no answer field anywhere in this
 * file — the answer lives only in memory for the apply step (§2.5)
 * and is never passed here. Same for solution tokens and image
 * bytes: only the SHA-256 of the PNG is retained (§4.3).
 */
object CaptchaSolveLog {

    const val MAX_ENTRIES = CaptchaSolveLogFormat.MAX_ENTRIES
    const val FILE_NAME = "captcha_solve_log.txt"

    /** One audit line — see CaptchaSolveLogFormat.Entry (no answer /
     *  token / image field exists, §4.2–§4.3). */
    typealias Entry = CaptchaSolveLogFormat.Entry

    /** Pure formatter — see CaptchaSolveLogFormat. */
    fun formatLine(entry: Entry): String = CaptchaSolveLogFormat.formatLine(entry)

    /** Pure cap — see CaptchaSolveLogFormat. */
    fun capLines(lines: List<String>): List<String> = CaptchaSolveLogFormat.capLines(lines)

    private fun logFile(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun append(context: Context, entry: Entry) {
        runCatching {
            val file = logFile(context)
            val existing = if (file.exists()) file.readLines() else emptyList()
            val updated = capLines(existing + formatLine(entry))
            file.writeText(updated.joinToString("\n") + "\n")
        }
    }

    /** Last [count] lines, newest last. Empty list when no log yet. */
    fun readLast(context: Context, count: Int = 20): List<String> = runCatching {
        val file = logFile(context)
        if (!file.exists()) return emptyList()
        file.readLines().filter { it.isNotBlank() }.takeLast(count.coerceAtLeast(1))
    }.getOrDefault(emptyList())

    /** A revocation is recorded so the user can see when they
     *  turned auto-solve off (§4.2). */
    fun recordRevocation(context: Context) {
        append(
            context,
            Entry(
                solveId = "-",
                taskId = "-",
                site = "-",
                challengeType = "-",
                model = "-",
                outcome = "flag_revoked_by_user",
                confidence = null,
                latencyMs = 0L,
                imageSha256 = "-"
            )
        )
    }

    /** A grant is recorded the same way (audit symmetry). */
    fun recordGrant(context: Context) {
        append(
            context,
            Entry(
                solveId = "-",
                taskId = "-",
                site = "-",
                challengeType = "-",
                model = "-",
                outcome = "flag_granted_by_user",
                confidence = null,
                latencyMs = 0L,
                imageSha256 = "-"
            )
        )
    }
}
