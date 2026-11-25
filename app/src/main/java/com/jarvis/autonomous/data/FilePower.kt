package com.jarvis.autonomous.data

/**
 * 1.26.0 (power features) — FilePower: file access through the
 * Shizuku shell (UID 2000).
 *
 * Pure shell-command builders + output parsers — no Android API, no
 * Shizuku call here, so every function is unit-testable without a
 * phone. The UI ([FilesScreen]) and the plan executor call
 * [ShizukuManager.exec] with the commands built here.
 *
 * What the shell CAN do (spec §1a, live research 2026-11-25):
 * /sdcard (all shared storage) + /sdcard/Android/data + /sdcard/Android/obb
 * (read + edit + copy + delete — the shell bypasses the FUSE
 * scoped-storage block that stops apps), /data/app (read — APK
 * backup), /data/local/tmp (staging), Jarvis's own /data/data.
 *
 * What it CANNOT do (and this module never tries): other apps'
 * private /data/data (DAC 0700 + SELinux — shell gets "Permission
 * denied"), /system writes (read-only partition). [isBlockedPath]
 * rejects those targets so a caller can never silently attempt them.
 *
 * Edit mechanism (corrected from the spec draft): the app CANNOT
 * write /data/local/tmp itself (0771 shell:shell — no write bit for
 * other UIDs), and the shell CANNOT read Jarvis's private filesDir
 * (0700). So an edit goes: app base64-encodes the new text in
 * memory → shell writes it to a staging file (`echo <b64> |
 * base64 -d`) → `cp` to the target → `chmod --reference` restores
 * the original mode → staging file removed → verify by `stat` +
 * `head`. If any step fails, the original is untouched.
 */
object FilePower {

    /** Browseable roots offered by the Files tab (in order). */
    val BROWSE_ROOTS = listOf(
        "/sdcard/" to "Home (/sdcard)",
        "/sdcard/Download" to "Download",
        "/sdcard/DCIM" to "DCIM (photos)",
        "/sdcard/Documents" to "Documents",
        "/sdcard/Android/data" to "Android/data",
        "/sdcard/Android/obb" to "Android/obb",
        "/sdcard/Jarvis/Backups" to "Jarvis Backups",
        "/data/local/tmp" to "Staging (/data/local/tmp)"
    )

    /** Max bytes the viewer reads/edits in one go (spec §5.1). */
    const val MAX_EDIT_BYTES = 1_048_576L

    /** Text extensions the viewer opens (anything else is binary). */
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "json", "xml", "log", "kt", "java", "py", "sh",
        "csv", "html", "htm", "css", "js", "ts", "gradle", "properties", "ini", "yml", "yaml"
    )

    fun isTextFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTENSIONS
    }

    /** Single-quote a shell argument (`'` becomes `'\''`). */
    fun quotePath(path: String): String =
        "'" + path.replace("'", "'\\''") + "'"

    /** Normalise a path for the safety checks (collapse //, drop trailing /). */
    fun normalizePath(path: String): String {
        var p = path.trim().replace(Regex("/+"), "/")
        if (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        return p
    }

    /**
     * True when the path may be WRITTEN through the shell: under
     * /sdcard or /data/local/tmp. Everything else (including
     * /system and /data/data) is never a write target.
     */
    fun isWritablePath(path: String): Boolean {
        val p = normalizePath(path)
        return p == "/sdcard" || p.startsWith("/sdcard/") ||
            p == "/data/local/tmp" || p.startsWith("/data/local/tmp/")
    }

    /**
     * True for paths Jarvis must never attempt: other apps' private
     * /data/data (OS blocks it — offering it would be a lie) and
     * /system (read-only). Jarvis's OWN /data/data is not blocked
     * here — it is not a shell path at all (normal File API).
     */
    fun isBlockedPath(path: String): Boolean {
        val p = normalizePath(path)
        return p == "/data/data" || p.startsWith("/data/data/") ||
            p == "/system" || p.startsWith("/system/")
    }

    /** True when a delete target is safe (never a root / parent dir). */
    fun isSafeDeleteTarget(path: String): Boolean {
        val p = normalizePath(path)
        if (p.isEmpty() || p == "/" || p == "/sdcard" || p == "/data/local/tmp") return false
        if (p == "/sdcard/Android" || p == "/sdcard/Android/data" || p == "/sdcard/Android/obb") return false
        if (p == "/sdcard/Jarvis" || p == "/sdcard/Jarvis/Backups") return false
        return isWritablePath(p)
    }

    // ---- Command builders (all through `sh -c`) ----

    fun listCommand(path: String): String =
        "ls -la --time-style=long-iso ${quotePath(path)}"

    fun statCommand(path: String): String =
        "stat -c '%s|%a|%y' ${quotePath(path)}"

    fun readTextCommand(path: String): String =
        "cat ${quotePath(path)}"

    /**
     * Write edited text to [targetPath]: base64 (safe inside single
     * quotes — alphabet is [A-Za-z0-9+/=]) → staging file → cp →
     * chmod --reference keeps the original mode → staging removed.
     * [originalMode] is the octal mode from [parseStat] (e.g. "644").
     */
    fun writeTextCommand(stagingPath: String, base64: String, targetPath: String, originalMode: String): String =
        "echo '$base64' | base64 -d > ${quotePath(stagingPath)} && " +
            "cp ${quotePath(stagingPath)} ${quotePath(targetPath)} && " +
            "chmod $originalMode ${quotePath(targetPath)} && " +
            "rm ${quotePath(stagingPath)}"

    fun copyCommand(src: String, dst: String): String =
        "cp -r ${quotePath(src)} ${quotePath(dst)}"

    fun moveCommand(src: String, dst: String): String =
        "mv ${quotePath(src)} ${quotePath(dst)}"

    fun deleteCommand(path: String): String =
        "rm -rf ${quotePath(path)}"

    fun mkdirCommand(path: String): String =
        "mkdir -p ${quotePath(path)}"

    fun apkPathCommand(pkg: String): String =
        "pm path $pkg"

    fun listPackagesCommand(thirdPartyOnly: Boolean): String =
        if (thirdPartyOnly) "pm list packages -3" else "pm list packages"

    fun packageVersionCommand(pkg: String): String =
        "dumpsys package $pkg | grep -i versionname | head -1"

    fun apkBackupCommand(apkPath: String, destPath: String): String =
        "cp ${quotePath(apkPath)} ${quotePath(destPath)} && ls -l ${quotePath(destPath)}"

    /**
     * Sanitise a search query. Truncates at the first shell
     * metacharacter — anything after it (e.g. "; rm -rf /") is
     * dropped ENTIRELY, so no command word can survive the
     * sanitizer. Filtering alone was not enough: it kept the
     * words ("rm", "rf") while only stripping the punctuation.
     * The kept prefix is letters/digits and "._- " only.
     */
    fun sanitizeSearchQuery(query: String): String {
        val prefix = query.takeWhile { it.isLetterOrDigit() || it in "._- " }
        return prefix.trim().split(Regex("\\s+")).joinToString(" ").take(64)
    }

    fun findCommand(dir: String, query: String): String {
        val q = sanitizeSearchQuery(query)
        return "find ${quotePath(dir)} -iname '*$q*' 2>/dev/null | head -100"
    }

    fun grepCommand(dir: String, query: String): String {
        val q = sanitizeSearchQuery(query)
        return "grep -r -l -- '$q' ${quotePath(dir)} 2>/dev/null | head -50"
    }

    // ---- Parsers (pure — unit-tested) ----

    data class FileEntry(
        val name: String,
        val isDir: Boolean,
        val isLink: Boolean,
        val sizeBytes: Long,
        val date: String,
        val linkTarget: String? = null
    )

    /**
     * Parses `ls -la --time-style=long-iso` output. Format per line:
     * `-rw-r--r-- 1 owner group 1234 2026-11-25 10:00 name` (or
     * `lrwxrwxrwx ... name -> target` for links). The `total N`
     * header and `.` / `..` are skipped. Names may contain spaces
     * (split with a limit of 8 fields).
     */
    fun parseLsOutput(out: String): List<FileEntry> {
        val entries = ArrayList<FileEntry>()
        for (rawLine in out.lines()) {
            val line = rawLine.trimEnd()
            if (line.isBlank() || line.startsWith("total ")) continue
            // 8 fields max: perms links owner group size date time name...
            val parts = line.split(Regex("\\s+"), limit = 8)
            if (parts.size < 8) continue
            val perms = parts[0]
            if (perms.length < 10) continue
            val size = parts[4].toLongOrNull() ?: 0L
            val date = parts[5] + " " + parts[6]
            var name = parts[7]
            var linkTarget: String? = null
            if (perms.startsWith("l") && name.contains(" -> ")) {
                val split = name.split(" -> ", limit = 2)
                name = split[0]
                linkTarget = split.getOrNull(1)
            }
            if (name == "." || name == "..") continue
            entries += FileEntry(
                name = name,
                isDir = perms.startsWith("d"),
                isLink = perms.startsWith("l"),
                sizeBytes = size,
                date = date,
                linkTarget = linkTarget
            )
        }
        return entries
    }

    data class StatInfo(val sizeBytes: Long, val mode: String, val mtime: String)

    /** Parses `stat -c '%s|%a|%y'` output (`size|mode|mtime`). */
    fun parseStat(out: String): StatInfo? {
        val parts = out.trim().split("|")
        if (parts.size < 3) return null
        val size = parts[0].trim().toLongOrNull() ?: return null
        val mode = parts[1].trim()
        if (mode.isBlank()) return null
        return StatInfo(size, mode, parts[2].trim())
    }

    /** Parses `pm path <pkg>` output (`package:/data/app/.../base.apk`). */
    fun parsePmPath(out: String): String? {
        val line = out.lines().firstOrNull { it.trim().startsWith("package:") } ?: return null
        return line.trim().removePrefix("package:").trim().takeIf { it.isNotBlank() }
    }

    /** Parses `pm list packages` output (`package:com.foo` lines). */
    fun parsePmList(out: String): List<String> =
        out.lines()
            .map { it.trim().removePrefix("package:").trim() }
            // No dot requirement: real packages such as "android"
            // (the system package) have no dot — filtering on "."
            // silently dropped them from the APK-backup list.
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

    /** Parses `dumpsys package` versionName line (`versionName=1.2.3`). */
    fun parsePackageVersion(out: String): String? {
        val line = out.lines().firstOrNull { it.contains("versionName", ignoreCase = true) } ?: return null
        return line.substringAfter("=", "").trim().takeIf { it.isNotBlank() }
    }

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    /** Parent directory of a path ("/sdcard" for "/sdcard/file.txt", "/" for "/sdcard"). */
    fun parentPath(path: String): String {
        val p = normalizePath(path)
        val idx = p.lastIndexOf('/')
        if (idx <= 0) return "/"
        return p.substring(0, idx)
    }
}
