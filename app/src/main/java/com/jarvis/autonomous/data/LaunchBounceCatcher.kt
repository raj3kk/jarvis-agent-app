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
 * Session model: a "session" starts at a MainActivity LAUNCH_START
 * line, and the guard window RESETS on every activity launch
 * (onActivityLaunch): HOME/BACK stay blocked until THIS activity
 * instance composes UI AND a command arrives after THIS launch.
 * The old process-lifetime reading was wrong for a long-lived
 * service — a command from an hour ago must not leave the guard
 * permanently open.
 *
 * DECISIVE USER EVIDENCE 2026-11-03 (rewrites the 1.4.3 reading):
 * app runs in BACKGROUND + notification ALIVE after every bounce
 * + overlay permission NOT granted. Manifest check
 * (AndroidManifest.xml: MainActivity singleTop ~line 61,
 * JarvisForegroundService ~line 90, NO android:process on ANY
 * component — grep-verified zero matches): SINGLE PROCESS. A
 * foreground-service notification vanishes when its process dies,
 * so notification-alive = process-alive = the Activity did NOT
 * crash the process. Process death / Java crash / Vivo
 * battery-kill are therefore ELIMINATED as the bounce mechanism
 * (a crash would also have left a crash entry + killed the
 * notification). What remains, ranked: (1) agent HOME/BACK press
 * (from a task or wake-word trigger in the still-alive service)
 * — the per-launch guard + NAV_BLOCKED_STARTUP marker decide
 * this; (2) a task/back-stack/intent effect pushing the activity
 * behind (no launch-time settings/permission intent exists —
 * MainActivity only requests runtime permissions at first
 * composition, and overlay/battery intents are user-initiated
 * buttons only); (3) activity finish — ELIMINATED, zero
 * finish()/finishAndRemoveTask/moveTaskToBack calls anywhere.
 *
 * OVERLAY LOOP (separate pending item, NOT the bounce cause):
 * launch-time overlay handling is safe — service onCreate calls
 * FloatingJarvisOverlay.show() only when the HUD toggle is ON
 * (default true), and show() early-returns on missing permission
 * (FloatingJarvisOverlay.kt:44-53: reportShowFailure + return, no
 * throw, no activity effect). Missing overlay therefore cannot
 * bounce the launch. But the user cannot reach the in-app
 * Permissions tab while every launch bounces, so the overlay
 * grant is UNREACHABLE until the bounce is fixed — 1.4.4 does
 * NOT depend on the in-app grant. The Command-tab banner is
 * kept for AFTER the bounce is fixed; the bounce fix path is
 * service/notification/file only.
 *
 * PRIMARY REPORT PATH (user: NO launch reaches UI at all):
 * "show the report first on next UI" alone is INSUFFICIENT —
 * that screen may never open. So the report is written from
 * SERVICE/Application paths that do not need UI: the
 * MainActivity.onCreate background thread AND the foreground
 * service's onStartCommand thread both run previous-bounce
 * detection and write Documents/JarvisDiagnostics/
 * last-bounce-report.txt (+ external-files copy). The persistent
 * notification gains a "Share bounce report" action (system
 * share sheet via FileProvider, ACTION_SEND) — it works without
 * Jarvis UI staying open, because the chooser is system UI.
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

    /**
     * 1.4.4 per-launch guard window (user evidence 2026-11-03:
     * service + notification ALIVE after a bounce, so the process
     * did NOT die — the ACTIVITY left). The old process-lifetime
     * flags were wrong for a long-lived service: a command from
     * an hour ago would have left the guard permanently open.
     * So every MainActivity launch RESETS the window: HOME/BACK
     * stay blocked until THIS activity instance composes UI AND
     * a command arrives after THIS launch. No activity launch =
     * no reset, so a pure background voice task (app never
     * opened) is NOT blocked by a stale window.
     */
    fun onActivityLaunch() {
        userCommandIssued = false
        uiStable = false
    }

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
    if (activityStarts.size < 2) {
        // 1.4.4 LEGACY fallback (deep-verification audit): 1.4.3
        // never wrote LAUNCH_START, so the FIRST 1.4.4 launch has
        // only one Activity LAUNCH_START and the slice logic above
        // would report nothing — yet the OLD 1.4.3 diagnostics
        // file already holds LIFE/NAV/SPK markers, and THOSE are
        // what decide candidate (a) agent-HOME vs (b) OEM
        // backgrounding. So: when no LAUNCH_START pair exists yet
        // but 1.4.3-era markers are present, surface the last 30
        // lines as a clearly-labeled LEGACY trail (not a fresh
        // bounce verdict). Gated on marker presence, so a fresh
        // install (no markers) still reports nothing. After the
        // second 1.4.4 launch, the normal slice path takes over
        // and this fallback stops firing.
        val hasLegacyMarkers = lines.any { line ->
            val c = codeOf(line)
            c.startsWith("LIFE_") || c.startsWith("NAV_") || c.startsWith("SPK_")
        }
        if (!hasLegacyMarkers) return null
        val tail = lines.takeLast(30)
        val crash = tail.lastOrNull { it.contains("\"area\":\"crash\"") }
        val lastMarkers = tail.filter { line ->
            val c = codeOf(line)
            c.startsWith("LIFE_") || c.startsWith("NAV_") || c.startsWith("SPK_")
        }.takeLast(6).joinToString("; ") { "${codeOf(it)} @ ${tsOf(it)}" }
        return PreviousBounce(
            classification = "LEGACY TRAIL (1.4.3 markers, pre-LAUNCH_START — " +
                "first 1.4.4 launch; this decides agent-HOME vs OEM backgrounding)",
            detail = "Last markers: ${if (lastMarkers.isBlank()) "none" else lastMarkers}. " +
                "NAV_EXEC HOME present = agent pressed HOME (1.4.3 wrote no " +
                "USER_CMD markers, so task context comes from SPK/step lines, " +
                "not from a USER_CMD order check). No NAV_EXEC + " +
                "LIFE_PAUSE/DESTROY = OEM backgrounding (b). " +
                "Crash in trail: ${if (crash != null) "YES" else "no"}.",
            sessionLines = tail,
            lastCrash = crash
        )
    }
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
 * Writes BOTH copies every time: (1) Documents/JarvisDiagnostics/
 * last-bounce-report.txt via MediaStore (same proven pattern
 * ProjectStore uses for Documents/JarvisProjects) — visible in any
 * file manager; (2) the app's external files dir copy, which is
 * the one the notification Share action serves via FileProvider
 * (a MediaStore URI cannot be shared the same way). Returns a
 * human-readable location string for the card.
 */
fun writeBounceReportFile(context: Context, text: String): String {
    var externalOk = false
    runCatching {
        val dir = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "JarvisDiagnostics"
        ).apply { mkdirs() }
        File(dir, "last-bounce-report.txt").writeText(text)
        externalOk = true
    }
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
    return if (externalOk) {
        "App files: Android/data/com.jarvis.autonomous/files/JarvisDiagnostics/last-bounce-report.txt"
    } else {
        "File save nahi ho paya — app ke andar Copy button se copy karo."
    }
}

/** External-files copy for the notification Share action, if present. */
fun bounceShareFile(context: Context): File? {
    val dir = context.getExternalFilesDir(null) ?: return null
    val f = File(dir, "JarvisDiagnostics/last-bounce-report.txt")
    return if (f.exists()) f else null
}
