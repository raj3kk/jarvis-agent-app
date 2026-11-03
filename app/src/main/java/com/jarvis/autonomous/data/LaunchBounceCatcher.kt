package com.jarvis.autonomous.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import com.jarvis.autonomous.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1.4.4 LAUNCH-BOUNCE CATCHER — user symptom: "app par tap karte hi
 * khulte hi automatic home page par redirect" (launch bounce, EVERY
 * launch). This is DIFFERENT from the 1.4.3 step-speaking home case
 * (which was the agent pressing HOME mid-task). If the app bounces
 * on every launch, the user can NEVER open Memory & Logs to copy
 * anything — so this catcher is built around two rules:
 *
 *  (1) Markers are PERSISTED (diagnostics.jsonl), never in-memory.
 *  (2) The previous-session bounce report is readable WITHOUT the
 *      app staying open: a first-shown card on the Command tab AND
 *      a plain-text file the user can copy from a file manager.
 *
 * Session model (single process — no android:process anywhere, so
 * these statics are shared by Activity, Service and Accessibility
 * service): a "session" starts at a MainActivity LAUNCH_START line.
 * The previous session is the slice between the last two
 * MainActivity LAUNCH_START lines in the persisted file.
 *
 * Reading guide (extends the 1.4.3 guide):
 *  - LAUNCH_START, no LAUNCH_UI after it, crash line present
 *      = Java crash during startup (class + frames in crash line).
 *  - LAUNCH_START, no LAUNCH_UI, NO crash line
 *      = died during startup: native crash (Sherpa/ONNX, first
 *      greeting speak loads the model) or system kill.
 *  - LAUNCH_UI, then NO LIFE_PAUSE/LIFE_DESTROY at all
 *      = process killed after UI (battery-kill style).
 *  - LAUNCH_UI + LIFE_PAUSE/LIFE_DESTROY (or a clean close)
 *      = NOT a bounce — normal backgrounding/rotation/close, so
 *      NO card is shown (a post-UI bounce that goes through the
 *      normal pause lifecycle is indistinguishable from the user
 *      pressing Home themselves; the guard + markers still record
 *      it, and full diagnostics stay in Memory & Logs).
 *  - NAV_BLOCKED_STARTUP in the slice = the 1.4.4 startup guard
 *      fired and stopped an agent HOME/BACK before any command.
 *  - NAV_EXEC HOME before any USER_CMD in the slice = a startup
 *      agent action (should be impossible after the guard).
 *
 * Privacy: session lines come from diagnostics.jsonl, which is
 * already sanitized at append time (no keys/tokens/licence keys/
 * contacts/message text/URLs with ?key=). The report re-sanitizes
 * defensively. Never a full licence key, never a token value.
 */
object LaunchSession {
    @Volatile
    var userCommandIssued: Boolean = false

    @Volatile
    var uiStable: Boolean = false

    @Volatile
    var bounceChecked: Boolean = false

    @Volatile
    var bounce: PreviousBounce? = null

    @Volatile
    var bounceFilePath: String = ""

    @Volatile
    var bounceDismissed: Boolean = false

    fun markUserCommand() {
        userCommandIssued = true
    }

    fun markUiStable() {
        uiStable = true
    }
}

data class PreviousBounce(
    val classification: String,
    val detail: String,
    val sessionLines: List<String>,
    val lastCrash: String?
)

private fun codeOf(line: String): String {
    return Regex("\"code\":\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: "?"
}

private fun tsOf(line: String): String {
    return Regex("\"ts\":\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: "?"
}

/**
 * Previous-session bounce detection. Returns null when there is no
 * previous launch to judge, or when the previous session ended
 * normally (backgrounding / rotation / clean close) — no card then.
 */
fun detectPreviousBounce(): PreviousBounce? {
    val lines = JarvisDiagnosticsFile.readLast(200)
    if (lines.isEmpty()) return null
    // Boundaries: MainActivity LAUNCH_START lines (one per launcher
    // tap / activity creation). Application LAUNCH_START lines are
    // NOT used as boundaries: they only fire on cold process start,
    // and a warm tap (service already alive) writes no new one.
    val activityStarts = lines.indices.filter { i ->
        lines[i].contains("\"code\":\"LAUNCH_START\"") &&
            lines[i].contains("MainActivity created")
    }
    if (activityStarts.size < 2) return null
    val prevStart = activityStarts[activityStarts.size - 2]
    val curStart = activityStarts[activityStarts.size - 1]
    val slice = lines.subList(prevStart, curStart)
    if (slice.isEmpty()) return null

    val hasUi = slice.any { it.contains("\"code\":\"LAUNCH_UI\"") }
    val crash = slice.lastOrNull { it.contains("\"area\":\"crash\"") }
    val hasPause = slice.any { it.contains("\"code\":\"LIFE_PAUSE\"") }
    val hasDestroy = slice.any { it.contains("\"code\":\"LIFE_DESTROY\"") }
    val navHome = slice.any {
        it.contains("\"code\":\"NAV_EXEC\"") && it.contains("HOME")
    }
    val navBlocked = slice.any { it.contains("\"code\":\"NAV_BLOCKED_STARTUP\"") }
    val lastMarkers = slice.filter { line ->
        val c = codeOf(line)
        c.startsWith("LIFE_") || c.startsWith("NAV_") || c.startsWith("SPK_") ||
            c == "USER_CMD" || c == "LAUNCH_UI" || c == "LAUNCH_START"
    }.takeLast(6).joinToString("; ") { "${codeOf(it)} @ ${tsOf(it)}" }

    val detail = buildString {
        append("Last markers: ${if (lastMarkers.isBlank()) "none" else lastMarkers}. ")
        if (navBlocked) append("Startup guard fired (NAV_BLOCKED_STARTUP). ")
        if (navHome) append("Agent HOME was pressed (NAV_EXEC HOME). ")
        append("Crash in session: ${if (crash != null) "YES" else "no"}.")
    }

    val classification = when {
        crash != null && !hasUi ->
            "STARTUP CRASH — Java crash before first UI (class + frames below)"
        crash != null ->
            "CRASH after UI — Java crash in previous session (class + frames below)"
        !hasUi ->
            "DIED DURING STARTUP — no first UI, no Java crash entry " +
                "(native Sherpa/ONNX crash on first greeting speak, or system kill)"
        !hasPause && !hasDestroy ->
            "PROCESS KILLED after UI — no pause/destroy at all (battery-kill style)"
        else -> return null // Normal backgrounding / rotation / clean close.
    }
    return PreviousBounce(
        classification = classification,
        detail = detail,
        sessionLines = slice.toList(),
        lastCrash = crash
    )
}

/** Full copy-paste text for the card + the file. Sanitized twice. */
fun buildBounceReportText(bounce: PreviousBounce): String {
    val sb = StringBuilder()
    sb.appendLine("=== Jarvis launch-bounce report ===")
    sb.appendLine("App: ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})")
    sb.appendLine(
        "Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    )
    sb.appendLine("Previous session: ${bounce.classification}")
    sb.appendLine(bounce.detail)
    sb.appendLine("--- previous session lines (persisted, sanitized) ---")
    bounce.sessionLines.forEach { line ->
        sb.appendLine(JarvisStateRepository.sanitizeDiagnosticMessage(line))
    }
    sb.appendLine("--- last crash in session ---")
    sb.appendLine(
        bounce.lastCrash?.let { JarvisStateRepository.sanitizeDiagnosticMessage(it) } ?: "none"
    )
    sb.appendLine("=== end report (no keys, no contacts, no messages) ===")
    return sb.toString()
}

/**
 * Plain-text copy the user can reach WITHOUT the app staying open.
 * First tries Documents/JarvisDiagnostics/last-bounce-report.txt via
 * MediaStore (same proven pattern ProjectStore uses for
 * Documents/JarvisProjects) — visible in any file manager. Falls
 * back to the app's external files dir with a Share-ready path.
 * Returns a human-readable location string for the card.
 */
fun writeBounceReportFile(context: Context, text: String): String {
    runCatching {
        val resolver = context.contentResolver
        val collection =
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/JarvisDiagnostics/"
        var existing: Uri? = null
        resolver.query(
            collection,
            arrayOf(BaseColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf("last-bounce-report.txt", relativePath),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                existing = Uri.withAppendedPath(
                    collection, cursor.getLong(0).toString()
                )
            }
        }
        val uri = existing ?: run {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "last-bounce-report.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
            resolver.insert(collection, values)
        } ?: return@runCatching
        var written = false
        resolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            written = true
        }
        if (written) return "Documents/JarvisDiagnostics/last-bounce-report.txt (Files app se kholo)"
    }
    return runCatching {
        val dir = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "JarvisDiagnostics"
        ).apply { mkdirs() }
        File(dir, "last-bounce-report.txt").writeText(text)
        "App files: Android/data/com.jarvis.autonomous/files/JarvisDiagnostics/last-bounce-report.txt"
    }.getOrDefault(
        "File save nahi ho paya — app ke andar Copy button se copy karo."
    )
}
