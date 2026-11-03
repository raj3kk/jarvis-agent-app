package com.jarvis.autonomous.data

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1.4.2 bug catcher — persisted diagnostics file.
 *
 * Crashes kill the process, so an in-memory log alone dies with the
 * crash. Every diagnostic entry AND every uncaught crash is appended
 * here (filesDir/diagnostics.jsonl) BEFORE anything else happens, so
 * the trail survives. Capped at ~200 KB by dropping the oldest lines.
 *
 * NEVER written here: API keys, HF tokens, full licence keys,
 * passwords, contact names/numbers, message/notification text, URLs
 * containing keys, screen-tree content, image data. Callers pass
 * clean values; [append] additionally strips ?key= params.
 * NEVER read from here automatically: no auto-upload, no network,
 * no logcat. The user copies the report manually — that is the point.
 *
 * 1.4.5 part 3 — CROSS-PROCESS SAFETY (research regression):
 * since 1.4.5 the file is written from TWO processes (main + :tts).
 * @Synchronized is per-process only, so it never protected
 * cross-process appends, and rotation's readLines + writeText
 * could clobber the other process's lines mid-rewrite. Now every
 * write (append, crash, rotation, clear) holds a java.nio
 * FileLock on diagnostics.lock in the same dir, and rotation
 * writes a temp file + atomic renameTo — a concurrent reader
 * sees the old OR the new file, never a torn half-rewrite.
 * Speak-path stage markers use [appendSync] (write + fsync) so
 * they survive even a device crash, not just a process death.
 */
object JarvisDiagnosticsFile {
    private const val FILE_NAME = "diagnostics.jsonl"
    private const val LOCK_NAME = "diagnostics.lock"
    private const val TMP_SUFFIX = ".rotatetmp"
    private const val MAX_BYTES = 200L * 1024L

    @Volatile
    private var dir: File? = null

    fun init(filesDir: File) {
        dir = filesDir
    }

    private fun file(): File? = dir?.let { File(it, FILE_NAME) }

    private fun escape(raw: String): String {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", " ").replace("\r", " ")
    }

    private fun sanitize(raw: String): String {
        // 1.4.10 (Step 6): SINGLE implementation — this file used
        // to carry a second copy of the sanitizer that drifted
        // (it missed Bearer values, licence-shaped keys,
        // field/value pairs and path tokens). The one rule lives
        // in JarvisStateRepository (same package); both the
        // in-memory mirror and this file call it.
        return JarvisStateRepository.sanitizeDiagnosticMessage(raw)
    }

    /**
     * Cross-process mutual exclusion. Same-process threads are
     * already serialised by @Synchronized on the callers, so the
     * FileLock is never contended within one process (no
     * OverlappingFileLockException); across processes it blocks
     * until the other process's write + rotation finish. The lock
     * is released on process death by the OS — no stale locks.
     */
    private inline fun <T> withFileLock(block: () -> T): T {
        val d = dir ?: return block()
        val lockFile = File(d, LOCK_NAME)
        runCatching { lockFile.parentFile?.mkdirs() }
        val channel = FileOutputStream(lockFile, true).channel
        val lock = channel.lock()
        try {
            return block()
        } finally {
            runCatching { lock.release() }
            runCatching { channel.close() }
        }
    }

    private fun writeLineLocked(line: String, sync: Boolean) {
        val f = file() ?: return
        runCatching { f.parentFile?.mkdirs() }
        FileOutputStream(f, true).use { out ->
            out.write(line.toByteArray(Charsets.UTF_8))
            out.flush()
            if (sync) out.fd.sync()
        }
        rotateLocked(f)
    }

    /**
     * Rotation runs INSIDE the same lock acquisition as the
     * append that triggered it — a second process can never
     * append between our read and our rewrite. The rewrite goes
     * to a temp file + atomic renameTo in the same dir.
     */
    private fun rotateLocked(f: File) {
        runCatching {
            if (!f.exists() || f.length() <= MAX_BYTES) return
            val lines = f.readLines()
            var kept = lines
            while (kept.joinToString("\n").length > MAX_BYTES && kept.isNotEmpty()) {
                kept = kept.drop(kept.size / 4 + 1)
            }
            val tmp = File(f.parentFile, f.name + TMP_SUFFIX)
            tmp.writeText(kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n")
            if (!tmp.renameTo(f)) {
                // Rename failed (should not happen same-dir):
                // fall back to a direct rewrite rather than
                // losing the cap entirely. Still under lock.
                f.writeText(kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n")
                runCatching { tmp.delete() }
            }
        }
    }

    @Synchronized
    fun append(entry: DiagnosticEntry) {
        runCatching {
            val f = file() ?: return
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val line = "{\"ts\":\"$ts\",\"area\":\"${escape(entry.area)}\"," +
                "\"code\":\"${escape(entry.code)}\"," +
                "\"msg\":\"${escape(sanitize(entry.message))}\"}\n"
            withFileLock { writeLineLocked(line, sync = false) }
        }
    }

    /**
     * Sync append: write + flush + fsync under the cross-process
     * lock. Used by speak-path stage markers (SPK_*, GREET_START,
     * FIRST_*, TTS_PROCESS_START, HASH_MISMATCH) so the marker
     * naming a killing stage survives even a device crash, not
     * just a process death. Markers are infrequent (per speak /
     * per chunk), so the fsync cost is negligible.
     */
    @Synchronized
    fun appendSync(entry: DiagnosticEntry) {
        runCatching {
            val f = file() ?: return
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val line = "{\"ts\":\"$ts\",\"area\":\"${escape(entry.area)}\"," +
                "\"code\":\"${escape(entry.code)}\"," +
                "\"msg\":\"${escape(sanitize(entry.message))}\"}\n"
            withFileLock { writeLineLocked(line, sync = true) }
        }
    }

    /** Record an uncaught crash: class + message + top frames only
     *  (class/method names, never arguments or user text). Called
     *  from the chained uncaught-exception handler BEFORE the
     *  previous handler runs, so the record lands even if the
     *  process dies a millisecond later. Sync write: a crash
     *  record must never be lost to a page-cache race. */
    @Synchronized
    fun appendCrash(threadName: String, throwable: Throwable, processTag: String = "") {
        runCatching {
            val f = file() ?: return
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val frames = throwable.stackTrace.take(5).joinToString("; ") {
                "${it.className}.${it.methodName}:${it.lineNumber}"
            }
            val line = "{\"ts\":\"$ts\",\"area\":\"crash\"," +
                "\"code\":\"${escape(throwable.javaClass.simpleName)}\"," +
                "\"msg\":\"${escape(sanitize(throwable.message ?: ""))} | thread=$threadName" +
                (if (processTag.isNotEmpty()) " [process=$processTag]" else "") +
                " | $frames\"}\n"
            withFileLock { writeLineLocked(line, sync = true) }
        }
    }

    /** Last [n] lines, newest last. Empty list when nothing recorded.
     *  No lock needed: rotation replaces the file by atomic rename,
     *  so a concurrent reader sees the old OR the new complete
     *  file, never a torn half-rewrite. */
    fun readLast(n: Int): List<String> {
        return runCatching {
            val f = file() ?: return emptyList()
            if (!f.exists()) return emptyList()
            f.readLines().takeLast(n)
        }.getOrDefault(emptyList())
    }

    /** Newest crash line, if any. */
    fun readLastCrash(): String? {
        return readLast(200).lastOrNull { it.contains("\"area\":\"crash\"") }
    }

    fun clear() {
        runCatching {
            withFileLock {
                file()?.delete()
                dir?.let { File(it, FILE_NAME + TMP_SUFFIX).delete() }
            }
        }
    }
}
