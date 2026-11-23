package com.jarvis.autonomous.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * Privacy redaction shared by the event log and the Trained ingestion
 * path: OTP / banking / password-shaped content is NEVER written to
 * any on-device log or sent to the hub. The event itself is kept
 * (the turn happened); only the sensitive field is replaced.
 */
object SensitiveRedactor {
    private val SENSITIVE_WORDS = listOf(
        "otp", "password", "passwd", "pwd", "pin", "cvv", "card number",
        "bank", "upi", "aadh", "aadhaar", "pan card", "account number",
        "ifsc", "secret", "token"
    )

    fun containsSensitive(text: String): Boolean {
        val lower = text.lowercase()
        return SENSITIVE_WORDS.any { lower.contains(it) }
    }

    fun redact(text: String): String =
        if (containsSensitive(text)) "[redacted — sensitive content]" else text

    /**
     * Full log sanitising: redact sensitive words AND strip URL query
     * strings (a URL can carry a token/id in "?..." — the path alone
     * is enough for a screen-meaning line). Only touches text that
     * actually contains a URL.
     */
    fun sanitizeForLog(text: String): String {
        var out = redact(text)
        if (out.contains("http")) {
            out = out.replace(Regex("\\?[A-Za-z0-9_=&%+.-]*")) { "" }
        }
        return out
    }
}

/**
 * R2.2 (1.17.0-PRO) — JarvisEventLog: the on-device event log.
 *
 * Every Executor action + result + verification verdict + what the
 * screen meant, in one place. Talker and Executor BOTH write here
 * (the engine logs turns, the plan executor logs steps) and BOTH can
 * read it back. The log is on-device only — it never leaves the
 * phone in R2 (a future /api/sync sender can ship it to D1
 * jarvis_events; the server side already accepts 'events' rows).
 *
 * Storage: filesDir/jarvis-events.jsonl, one JSON object per line,
 * capped at 500 events, FIFO (oldest dropped on overflow). Appends
 * are atomic (append + flush + fsync). The Store holds only the
 * index (count / first+last ts / last id — R2.6).
 *
 * Privacy: NO command text, NO step args, NO doc content, NO raw
 * URLs with query strings — only the action kind, the result code,
 * the verdict code, and a short human-readable screen meaning.
 * Anything matching the sensitive list is redacted, never dropped.
 */
object JarvisEventLog {
    const val FILE_NAME = "jarvis-events.jsonl"
    const val MAX_EVENTS = 500

    data class JarvisEvent(
        val id: String,
        val tsMs: Long,
        val turnId: String,
        val action: String,
        val result: String,
        val verdict: String,
        val screenMeaning: String
    )

    private val lock = Any()
    private var dir: File? = null

    fun init(context: Context) {
        synchronized(lock) { dir = context.filesDir }
    }

    /**
     * Append one event. [screenMeaning] is a short human line, e.g.
     * "example.com khola, login page mila" — never command text,
     * never step args, never a URL with a query string.
     */
    fun log(
        turnId: String,
        action: String,
        result: String,
        verdict: String,
        screenMeaning: String
    ): JarvisEvent? {
        val d = synchronized(lock) { dir } ?: return null
        val event = JarvisEvent(
            id = "ev-${System.currentTimeMillis()}-${(0..9999).random()}",
            tsMs = System.currentTimeMillis(),
            turnId = turnId.take(64),
            action = action.take(64),
            result = result.take(32),
            verdict = verdict.take(120),
            screenMeaning = SensitiveRedactor.sanitizeForLog(screenMeaning.take(300))
        )
        synchronized(lock) {
            runCatching {
                val file = File(d, FILE_NAME)
                val line = JSONObject().apply {
                    put("id", event.id)
                    put("tsMs", event.tsMs)
                    put("turnId", event.turnId)
                    put("action", event.action)
                    put("result", event.result)
                    put("verdict", event.verdict)
                    put("screenMeaning", event.screenMeaning)
                }.toString() + "\n"
                FileOutputStream(file, true).use { out ->
                    out.write(line.toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
                trimLocked(file)
                updateIndexLocked()
            }.onFailure {
                // The log must never break the task that writes it.
                android.util.Log.w("JarvisEventLog", "append failed: ${it.javaClass.simpleName}")
            }
        }
        return event
    }

    /** Newest-first, up to [limit] events. */
    fun recent(limit: Int = 50): List<JarvisEvent> {
        val d = synchronized(lock) { dir } ?: return emptyList()
        return runCatching {
            val file = File(d, FILE_NAME)
            if (!file.exists()) return emptyList()
            val lines = file.readLines().filter { it.isNotBlank() }
            lines.takeLast(limit.coerceIn(1, MAX_EVENTS)).reversed().mapNotNull { line ->
                runCatching {
                    val obj = JSONObject(line)
                    JarvisEvent(
                        id = obj.optString("id", ""),
                        tsMs = obj.optLong("tsMs", 0L),
                        turnId = obj.optString("turnId", ""),
                        action = obj.optString("action", ""),
                        result = obj.optString("result", ""),
                        verdict = obj.optString("verdict", ""),
                        screenMeaning = obj.optString("screenMeaning", "")
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun count(): Int {
        val d = synchronized(lock) { dir } ?: return 0
        return runCatching {
            val file = File(d, FILE_NAME)
            if (!file.exists()) 0 else file.readLines().count { it.isNotBlank() }
        }.getOrDefault(0)
    }

    /** FIFO trim: keep the newest MAX_EVENTS lines (rewrite, atomic). */
    private fun trimLocked(file: File) {
        val lines = runCatching { file.readLines().filter { it.isNotBlank() } }.getOrNull() ?: return
        if (lines.size <= MAX_EVENTS) return
        val kept = lines.takeLast(MAX_EVENTS)
        runCatching {
            val tmp = File(file.parentFile, FILE_NAME + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write((kept.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            tmp.renameTo(file)
        }
    }

    /** Mirror the index into the Jarvis Store (best-effort). */
    private fun updateIndexLocked() {
        if (!JarvisStore.isMigrated()) return
        val d = dir ?: return
        runCatching {
            val file = File(d, FILE_NAME)
            if (!file.exists()) return
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return
            val first = runCatching { JSONObject(lines.first()).optLong("tsMs", 0L) }.getOrDefault(0L)
            val lastObj = runCatching { JSONObject(lines.last()) }.getOrNull()
            val lastTs = lastObj?.optLong("tsMs", 0L) ?: 0L
            val lastId = lastObj?.optString("id", "") ?: ""
            JarvisStore.setSection(JarvisStore.SEC_EVENT_LOG_INDEX, JSONObject().apply {
                put("count", lines.size)
                put("firstTsMs", first)
                put("lastTsMs", lastTs)
                put("lastEventId", lastId)
            })
        }
    }
}
