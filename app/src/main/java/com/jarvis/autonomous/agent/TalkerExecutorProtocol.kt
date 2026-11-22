package com.jarvis.autonomous.agent

import java.util.UUID

/**
 * Talker/Executor structured command protocol, version 1 (`te/1`).
 *
 * DESIGN: ~/workspace/omnijarvis-implementation/expansion/talker-executor/DESIGN.md
 * Release 1.8.0-PRO (vc 44). Pure Kotlin — ZERO Android imports — so this
 * file unit-tests on a plain JVM without Robolectric.
 *
 * The Talker (fast-reply layer) issues an [ExecutorCommand]; the Executor
 * (the current OmniJarvis agent: autonomous loop + agent-mode plans) does
 * the work and reports back with an [ExecutorResult]. The Talker never
 * blocks on the Executor: acks and status answers are templated, and
 * results arrive via [TalkerExecutorBus] listeners.
 *
 * Serialization is hand-rolled (minimal JSON escaper + small object
 * parser), deliberately NOT org.json: org.json is an Android-framework
 * class whose methods throw "not mocked" in JVM unit tests. Only flat
 * string/number/boolean fields plus one level of string map are
 * supported; anything else fails closed ([fromJson] returns null).
 */
object TalkerExecutorProtocol {

    /** Current protocol version. A mismatch is a hard reject, never best-effort. */
    const val VERSION = 1

    /** Default Executor budget: 15 minutes. */
    const val DEFAULT_TIMEOUT_MS = 900_000L

    /** Executor timeout bounds: 1 minute .. 60 minutes. */
    const val MIN_TIMEOUT_MS = 60_000L
    const val MAX_TIMEOUT_MS = 3_600_000L

    /** Max user-facing summary length (spoken lines stay short). */
    const val MAX_SUMMARY_CHARS = 300

    // ---- JSON helpers (hand-rolled, see file header) ----

    internal fun escapeJson(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                else -> {
                    if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code))
                    else sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    internal fun stringMapToJson(map: Map<String, String>): String {
        if (map.isEmpty()) return "{}"
        return map.entries.joinToString(",", "{", "}") {
            "\"${escapeJson(it.key)}\":\"${escapeJson(it.value)}\""
        }
    }

    /**
     * Minimal JSON object parser. Returns a map of key -> raw value where
     * a value is String, Long, Boolean, or Map<String, String> (nested
     * object, string values only). Returns null on ANY malformed input.
     * Unknown keys are preserved (forward-compat: v2 may add fields).
     */
    internal fun parseJsonObject(json: String): Map<String, Any?>? {
        return try {
            val parser = JsonParser(json.trim())
            val result = parser.parseObject()
            parser.skipWs()
            if (!parser.atEnd()) null else result
        } catch (e: Exception) {
            null
        }
    }

    private class JsonParser(private val text: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= text.length
        fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseObject(): Map<String, Any?> {
            skipWs()
            require(pos < text.length && text[pos] == '{') { "expected {" }
            pos++
            val out = linkedMapOf<String, Any?>()
            skipWs()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                require(pos < text.length && text[pos] == ':') { "expected :" }
                pos++
                out[key] = parseValue()
                skipWs()
                require(pos < text.length) { "unterminated object" }
                when (text[pos]) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; return out }
                    else -> throw IllegalArgumentException("expected , or }")
                }
            }
        }

        private fun parseValue(): Any? {
            skipWs()
            require(pos < text.length) { "unterminated value" }
            return when (val ch = text[pos]) {
                '"' -> parseString()
                '{' -> parseObject()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("unexpected char $ch")
            }
        }

        private fun expect(word: String) {
            require(text.startsWith(word, pos)) { "expected $word" }
            pos += word.length
        }

        private fun parseNumber(): Long {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            require(pos > start) { "bad number" }
            return text.substring(start, pos).toLong()
        }

        private fun parseString(): String {
            require(pos < text.length && text[pos] == '"') { "expected string" }
            pos++
            val sb = StringBuilder()
            while (pos < text.length) {
                val ch = text[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < text.length) { "bad escape" }
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                require(pos + 4 <= text.length) { "bad unicode escape" }
                                sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$esc")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }
    }

    internal fun asString(map: Map<String, Any?>, key: String): String? =
        (map[key] as? String)

    internal fun asLong(map: Map<String, Any?>, key: String): Long? =
        (map[key] as? Long) ?: (map[key] as? Int)?.toLong()

    internal fun asBoolean(map: Map<String, Any?>, key: String): Boolean? =
        (map[key] as? Boolean)

    @Suppress("UNCHECKED_CAST")
    internal fun asStringMap(map: Map<String, Any?>, key: String): Map<String, String>? {
        val nested = map[key] as? Map<String, Any?> ?: return null
        val out = linkedMapOf<String, String>()
        for ((k, v) in nested) {
            if (v !is String) return null
            out[k] = v
        }
        return out
    }
}

/** What kind of work the Executor should do. */
enum class ExecutorCommandKind(val wire: String) {
    EXECUTE_TASK("EXECUTE_TASK"),
    CANCEL_TASK("CANCEL_TASK"),
    STATUS_QUERY("STATUS_QUERY");

    companion object {
        fun fromWire(wire: String?): ExecutorCommandKind? =
            entries.firstOrNull { it.wire == wire }
    }
}

/** Which Executor path runs the work. */
enum class ExecutorWorkKind(val wire: String) {
    /** Agent-mode plan via AgentPlanExecutor. */
    PLAN("PLAN"),
    /** Autonomous loop via JarvisAgentEngine.runAutonomousLoop. */
    LOOP("LOOP"),
    /** Reserved; v1 never issues it — single-step routines stay on the Talker fast path. */
    ROUTINE("ROUTINE");

    companion object {
        fun fromWire(wire: String?): ExecutorWorkKind? =
            entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One structured command from the Talker to the Executor.
 * `id` is the idempotency key: a duplicate id is rejected, never executed twice.
 */
data class ExecutorCommand(
    val version: Int = TalkerExecutorProtocol.VERSION,
    val id: String = UUID.randomUUID().toString(),
    val kind: ExecutorCommandKind = ExecutorCommandKind.EXECUTE_TASK,
    val workKind: ExecutorWorkKind = ExecutorWorkKind.LOOP,
    /** The user's own words (trimmed). Non-empty for EXECUTE_TASK. */
    val goal: String = "",
    /** Optional hints, never orders (e.g. project, repo). */
    val params: Map<String, String> = emptyMap(),
    val issuedAtMs: Long = System.currentTimeMillis(),
    val timeoutMs: Long = TalkerExecutorProtocol.DEFAULT_TIMEOUT_MS,
    /** Reserved in v1 (always false) — confirmations happen inside the Executor gates. */
    val requiresConfirmation: Boolean = false,
    val origin: String = "talker"
) {
    /** Empty = valid. Invalid commands are never submitted to the bus. */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (version != TalkerExecutorProtocol.VERSION) {
            errors.add("UNSUPPORTED_VERSION: $version (expected ${TalkerExecutorProtocol.VERSION})")
        }
        if (id.isBlank()) errors.add("MISSING_ID")
        if (kind == ExecutorCommandKind.EXECUTE_TASK && goal.isBlank()) {
            errors.add("MISSING_GOAL")
        }
        if (issuedAtMs <= 0) errors.add("BAD_ISSUED_AT")
        if (timeoutMs < TalkerExecutorProtocol.MIN_TIMEOUT_MS ||
            timeoutMs > TalkerExecutorProtocol.MAX_TIMEOUT_MS
        ) {
            errors.add(
                "TIMEOUT_OUT_OF_RANGE: $timeoutMs " +
                    "(allowed ${TalkerExecutorProtocol.MIN_TIMEOUT_MS}..${TalkerExecutorProtocol.MAX_TIMEOUT_MS})"
            )
        }
        return errors
    }

    fun toJson(): String = with(TalkerExecutorProtocol) {
        buildString {
            append("{")
            append("\"version\":$version,")
            append("\"id\":\"${escapeJson(id)}\",")
            append("\"kind\":\"${kind.wire}\",")
            append("\"workKind\":\"${workKind.wire}\",")
            append("\"goal\":\"${escapeJson(goal)}\",")
            append("\"params\":${stringMapToJson(params)},")
            append("\"issuedAtMs\":$issuedAtMs,")
            append("\"timeoutMs\":$timeoutMs,")
            append("\"requiresConfirmation\":$requiresConfirmation,")
            append("\"origin\":\"${escapeJson(origin)}\"")
            append("}")
        }
    }

    companion object {
        fun fromJson(json: String): ExecutorCommand? {
            val map = TalkerExecutorProtocol.parseJsonObject(json) ?: return null
            val version = TalkerExecutorProtocol.asLong(map, "version")?.toInt()
                ?: return null
            val id = TalkerExecutorProtocol.asString(map, "id") ?: return null
            val kind = ExecutorCommandKind.fromWire(
                TalkerExecutorProtocol.asString(map, "kind")
            ) ?: return null
            val workKind = ExecutorWorkKind.fromWire(
                TalkerExecutorProtocol.asString(map, "workKind")
            ) ?: return null
            val goal = TalkerExecutorProtocol.asString(map, "goal") ?: return null
            val params = TalkerExecutorProtocol.asStringMap(map, "params") ?: emptyMap()
            val issuedAtMs = TalkerExecutorProtocol.asLong(map, "issuedAtMs") ?: return null
            val timeoutMs = TalkerExecutorProtocol.asLong(map, "timeoutMs") ?: return null
            val requiresConfirmation =
                TalkerExecutorProtocol.asBoolean(map, "requiresConfirmation") ?: false
            val origin = TalkerExecutorProtocol.asString(map, "origin") ?: "talker"
            return ExecutorCommand(
                version = version, id = id, kind = kind, workKind = workKind,
                goal = goal, params = params, issuedAtMs = issuedAtMs,
                timeoutMs = timeoutMs, requiresConfirmation = requiresConfirmation,
                origin = origin
            )
        }
    }
}

/** Terminal (or advisory-timeout) outcome of one Executor command. */
enum class ExecutorResultStatus(val wire: String) {
    DONE("DONE"),
    FAILED("FAILED"),
    BLOCKED("BLOCKED"),
    DECLINED("DECLINED"),
    TIMEOUT("TIMEOUT"),
    CANCELLED("CANCELLED");

    companion object {
        fun fromWire(wire: String?): ExecutorResultStatus? =
            entries.firstOrNull { it.wire == wire }
    }
}

data class ExecutorResult(
    val version: Int = TalkerExecutorProtocol.VERSION,
    val commandId: String,
    val status: ExecutorResultStatus,
    /** One human line, already user-facing wording, ≤ 300 chars. */
    val summary: String,
    /** Machine detail for chat/log (step evidence, HTTP code, file list). */
    val evidence: String = "",
    val finishedAtMs: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    /** True when this result arrives AFTER a TIMEOUT was already delivered. */
    val isLate: Boolean = false
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (version != TalkerExecutorProtocol.VERSION) {
            errors.add("UNSUPPORTED_VERSION: $version (expected ${TalkerExecutorProtocol.VERSION})")
        }
        if (commandId.isBlank()) errors.add("MISSING_COMMAND_ID")
        if (summary.isBlank()) errors.add("MISSING_SUMMARY")
        if (finishedAtMs <= 0) errors.add("BAD_FINISHED_AT")
        return errors
    }

    fun toJson(): String = with(TalkerExecutorProtocol) {
        buildString {
            append("{")
            append("\"version\":$version,")
            append("\"commandId\":\"${escapeJson(commandId)}\",")
            append("\"status\":\"${status.wire}\",")
            append("\"summary\":\"${escapeJson(summary)}\",")
            append("\"evidence\":\"${escapeJson(evidence)}\",")
            append("\"finishedAtMs\":$finishedAtMs,")
            append("\"durationMs\":$durationMs,")
            append("\"isLate\":$isLate")
            append("}")
        }
    }

    companion object {
        fun fromJson(json: String): ExecutorResult? {
            val map = TalkerExecutorProtocol.parseJsonObject(json) ?: return null
            val version = TalkerExecutorProtocol.asLong(map, "version")?.toInt()
                ?: return null
            val commandId = TalkerExecutorProtocol.asString(map, "commandId") ?: return null
            val status = ExecutorResultStatus.fromWire(
                TalkerExecutorProtocol.asString(map, "status")
            ) ?: return null
            val summary = TalkerExecutorProtocol.asString(map, "summary") ?: return null
            val evidence = TalkerExecutorProtocol.asString(map, "evidence") ?: ""
            val finishedAtMs = TalkerExecutorProtocol.asLong(map, "finishedAtMs") ?: return null
            val durationMs = TalkerExecutorProtocol.asLong(map, "durationMs") ?: 0L
            val isLate = TalkerExecutorProtocol.asBoolean(map, "isLate") ?: false
            return ExecutorResult(
                version = version, commandId = commandId, status = status,
                summary = summary, evidence = evidence,
                finishedAtMs = finishedAtMs, durationMs = durationMs, isLate = isLate
            )
        }
    }
}
