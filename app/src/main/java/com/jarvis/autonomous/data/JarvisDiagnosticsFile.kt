package com.jarvis.autonomous.data

import java.io.File
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
 */
object JarvisDiagnosticsFile {
    private const val FILE_NAME = "diagnostics.jsonl"
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
        var out = raw
        out = out.replace(Regex("[?&]key=[^&\\s]*"), "")
        out = out.replace(Regex("https?://[^\\s]*\\?[^\\s]*"), "[url-with-query-stripped]")
        return out.take(500)
    }

    @Synchronized
    fun append(entry: DiagnosticEntry) {
        runCatching {
            val f = file() ?: return
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val line = "{\"ts\":\"$ts\",\"area\":\"${escape(entry.area)}\"," +
                "\"code\":\"${escape(entry.code)}\"," +
                "\"msg\":\"${escape(sanitize(entry.message))}\"}\n"
            f.appendText(line)
            rotateIfNeeded(f)
        }
    }

    /** Record an uncaught crash: class + message + top frames only
     *  (class/method names, never arguments or user text). Called
     *  from the chained uncaught-exception handler BEFORE the
     *  previous handler runs, so the record lands even if the
     *  process dies a millisecond later. */
    @Synchronized
    fun appendCrash(threadName: String, throwable: Throwable) {
        runCatching {
            val f = file() ?: return
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val frames = throwable.stackTrace.take(5).joinToString("; ") {
                "${it.className}.${it.methodName}:${it.lineNumber}"
            }
            val line = "{\"ts\":\"$ts\",\"area\":\"crash\"," +
                "\"code\":\"${escape(throwable.javaClass.simpleName)}\"," +
                "\"msg\":\"${escape(sanitize(throwable.message ?: ""))} | thread=$threadName | $frames\"}\n"
            f.appendText(line)
            rotateIfNeeded(f)
        }
    }

    private fun rotateIfNeeded(f: File) {
        runCatching {
            if (!f.exists() || f.length() <= MAX_BYTES) return
            val lines = f.readLines()
            var kept = lines
            while (kept.joinToString("\n").length > MAX_BYTES && kept.isNotEmpty()) {
                kept = kept.drop(kept.size / 4 + 1)
            }
            f.writeText(kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n")
        }
    }

    /** Last [n] lines, newest last. Empty list when nothing recorded. */
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
        runCatching { file()?.delete() }
    }
}
