package com.jarvis.autonomous.agent

import java.security.MessageDigest

/**
 * Self-extension stage 1 — DETECT a blocked capability (DESIGN §2).
 *
 * Pure Kotlin — ZERO Android imports — so this file unit-tests on a
 * plain JVM, exactly like TalkerExecutorProtocol.kt. The Executor
 * calls it; the Talker never does. Detection is a pure function over
 * the failure, so it is unit-testable without a phone, a network, or
 * an LLM.
 *
 * Matching rule (§2.2): exclusions are checked FIRST, in order, and
 * match on structured fields first (step kind, action name, HTTP
 * code, hub error code) and on a frozen keyword list second (exact
 * substring, lowercase). Free-text LLM output is NEVER the sole
 * trigger: a trigger needs a structured code or a structured code +
 * keyword. This is what stops "the LLM hallucinated a missing tool"
 * from starting research.
 *
 * S8: the capability string (§2.3) is derived from the failing
 * step/action + a fixed vocabulary map only — never from goal text,
 * step inputs, or user data. The block signature (§2.4) therefore
 * contains no user content by construction.
 */
data class ExecutorFailure(
    val stepKind: String,
    val action: String,
    val reason: String,
    val httpCode: Int? = null,
    /** Accepted for API compatibility with DESIGN §2.1 only. It is
     *  NEVER read: no user text may reach detection, research, or
     *  any persisted record (S8). Callers must pass "". */
    val goalText: String = "",
    val planId: String = ""
)

sealed interface Detection {
    data class MissingCapability(val capability: String, val signature: String, val trigger: String) : Detection
    data class NotACapabilityBlock(val exclusion: String) : Detection
}

object SelfExtensionDetector {

    // ---- Exclusion keywords (frozen, §2.2 — exact substring, lowercase) ----
    private val LOGIN_KEYWORDS = listOf(
        "login", "log-in", "sign-in", "signin", "sign in", "otp", "2fa",
        "two-factor", "session expired", "session", "account auth", "authenticate",
        "password", "verify your identity"
    )
    private val PAYMENT_KEYWORDS = listOf(
        "payment", "pay", "price", "pricing", "subscription", "checkout",
        "card", "billing", "purchase", "buy now", "upi"
    )
    private val CAPTCHA_KEYWORDS = listOf(
        "captcha", "turnstile", "recaptcha", "hcaptcha", "bot-check", "bot check",
        "verify you are human", "i'm not a robot"
    )
    private val PERMISSION_KEYWORDS = listOf(
        "permission denied", "permission not granted", "permission",
        "overlay permission", "accessibility service", "not granted"
    )
    private val TRANSIENT_KEYWORDS = listOf(
        "timeout", "timed out", "ioexception", "socket", "rate limit",
        "rate-limit", "rate_limited", "quota", "byok_required",
        "render_not_configured", "service unavailable", "bad gateway",
        "network error", "no internet", "internet nahi"
    )
    private val MISSING_INFO_KEYWORDS = listOf(
        "missing info", "missing information", "unknown name", "naam nahi mila",
        "project naam nahi mila", "fixmissingrefs", "asked a question"
    )
    private val PARSE_KEYWORDS = listOf(
        "unparseable", "malformed json", "parse error", "json parse",
        "empty decision", "adhoora", "truncated"
    )

    /** Fixed vocabulary map: failing step/action -> capability phrase. */
    private val CAPABILITY_VOCABULARY = mapOf(
        "BUILD" to "build-project",
        "GITHUB_PUSH" to "github-push",
        "SUPABASE_TABLES" to "supabase-tables",
        "VERCEL_DEPLOY" to "vercel-deploy",
        "HTTP_CHECK" to "http-check",
        "UNKNOWN_STEP" to "unknown-step",
        "UNKNOWN_ACTION" to "unknown-action"
    )

    /**
     * Detect whether [failure] is a missing-capability block.
     * Exclusions first; then triggers. Returns [Detection.NotACapabilityBlock]
     * with the exclusion code for everything that must never research.
     */
    fun detect(failure: ExecutorFailure): Detection {
        val reasonLower = failure.reason.lowercase()
        val actionUpper = failure.action.uppercase()
        val stepKindUpper = failure.stepKind.uppercase()

        // ---- Exclusions, checked FIRST, in §2.2 order ----
        // X-DECLINE: user declined / gate ABORT_TASK.
        if (reasonLower.contains("declin") || reasonLower.contains("user ne") &&
            reasonLower.contains("mana")
        ) return Detection.NotACapabilityBlock("X-DECLINE")
        // X-NO-REPLY: no reply to an asked question.
        if (reasonLower.contains("jawab nahi aaya") || reasonLower.contains("no reply") ||
            reasonLower.contains("no answer")
        ) return Detection.NotACapabilityBlock("X-NO-REPLY")
        // X-GATE: gate BLOCK_STEP / PAUSE_TASK.
        if (reasonLower.contains("gate") && (reasonLower.contains("block") || reasonLower.contains("pause"))) {
            return Detection.NotACapabilityBlock("X-GATE")
        }
        // X-LOGIN
        if (LOGIN_KEYWORDS.any { reasonLower.contains(it) }) {
            return Detection.NotACapabilityBlock("X-LOGIN")
        }
        // X-PAYMENT — "pay" is checked as a whole word so that e.g.
        // "display" never trips it; the other keywords are substrings.
        if (PAYMENT_KEYWORDS.any { kw ->
                if (kw == "pay") Regex("\\bpay\\b").containsMatchIn(reasonLower) ||
                    reasonLower.contains("pay ") || reasonLower.endsWith("pay")
                else reasonLower.contains(kw)
            }
        ) return Detection.NotACapabilityBlock("X-PAYMENT")
        // X-CAPTCHA (S11 — captcha is always out of scope).
        if (CAPTCHA_KEYWORDS.any { reasonLower.contains(it) }) {
            return Detection.NotACapabilityBlock("X-CAPTCHA")
        }
        // X-PERMISSION
        if (PERMISSION_KEYWORDS.any { reasonLower.contains(it) }) {
            return Detection.NotACapabilityBlock("X-PERMISSION")
        }
        // X-TRANSIENT: HTTP 5xx / timeout / rate-limit / quota.
        val httpCode = failure.httpCode
        if (httpCode != null && httpCode in 500..599) return Detection.NotACapabilityBlock("X-TRANSIENT")
        if (TRANSIENT_KEYWORDS.any { reasonLower.contains(it) }) {
            return Detection.NotACapabilityBlock("X-TRANSIENT")
        }
        // X-MISSING-INFO: planner asked a question / "?" sentinel.
        if (MISSING_INFO_KEYWORDS.any { reasonLower.contains(it) } || reasonLower.contains("\"?\"")) {
            return Detection.NotACapabilityBlock("X-MISSING-INFO")
        }
        // X-PARSE: LLM output unparseable.
        if (PARSE_KEYWORDS.any { reasonLower.contains(it) }) {
            return Detection.NotACapabilityBlock("X-PARSE")
        }
        // X-USER-CONTENT: the user's own data is corrupt/unsupported
        // (a data problem, not a missing tool). The classifier decides
        // the narrow standard-format exception; detection stays out.
        if (reasonLower.contains("corrupt") || reasonLower.contains("unsupported file") ||
            reasonLower.contains("unsupported user file")
        ) return Detection.NotACapabilityBlock("X-USER-CONTENT")

        // ---- Triggers (all must hold: trigger AND no exclusion) ----
        // T-UNKNOWN-STEP: AgentPlanExecutor else-branch. R1:
        // the wording is "Unknown kaam kind:" — keep in sync with
        // AgentPlanExecutor.executeStep else-branch.
        if (reasonLower.contains("unknown kaam kind")) {
            val kind = stepKindUpper.ifBlank { actionUpper }
            val capability = capabilityFor(kind, actionUpper)
            return Detection.MissingCapability(capability, signature(capability, failure), "T-UNKNOWN-STEP")
        }
        // T-NO-HANDLER: a handler returns "not implemented / unsupported".
        if (reasonLower.contains("t-no-handler") || reasonLower.contains("not implemented") ||
            reasonLower.contains("unsupported") || reasonLower.contains("no handler")
        ) {
            val capability = capabilityFor(stepKindUpper.ifBlank { actionUpper }, actionUpper)
            return Detection.MissingCapability(capability, signature(capability, failure), "T-NO-HANDLER")
        }
        // T-UNKNOWN-ACTION: action not in the engine's known set.
        if (actionUpper == "UNKNOWN_ACTION" || reasonLower.contains("unknown_action") ||
            reasonLower.contains("unknown action")
        ) {
            val capability = capabilityFor(actionUpper, actionUpper)
            return Detection.MissingCapability(capability, signature(capability, failure), "T-UNKNOWN-ACTION")
        }
        // T-FEATURE-ABSENT: hub error naming a missing capability
        // (matched on the error CODE, never on free text).
        if (reasonLower.contains("no_route") || reasonLower.contains("live_not_routed") ||
            (httpCode == 404 && reasonLower.contains("unimplemented"))
        ) {
            val capability = capabilityFor(stepKindUpper.ifBlank { actionUpper }, actionUpper)
            return Detection.MissingCapability(capability, signature(capability, failure), "T-FEATURE-ABSENT")
        }
        // T-TOOL-MISSING: a named external tool/CLI/library is absent.
        if (reasonLower.contains("no such tool") || reasonLower.contains("binary-not-found") ||
            reasonLower.contains("binary not found") || reasonLower.contains("tool not found") ||
            reasonLower.contains("command not found")
        ) {
            val capability = capabilityFor(stepKindUpper.ifBlank { actionUpper }, actionUpper)
            return Detection.MissingCapability(capability, signature(capability, failure), "T-TOOL-MISSING")
        }
        return Detection.NotACapabilityBlock("NO_TRIGGER")
    }

    /**
     * Capability naming (§2.3): lowercase, spaces -> `-`, max 64
     * chars, `[a-z0-9-]` only, from the fixed vocabulary map only.
     * Unknown step/action -> `unknown-capability`. Never goal text.
     */
    fun capabilityFor(stepKind: String, action: String): String {
        val raw = CAPABILITY_VOCABULARY[stepKind.uppercase()]
            ?: CAPABILITY_VOCABULARY[action.uppercase()]
            ?: stepKind.ifBlank { action }.lowercase()
        val normalised = raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(64)
            .trimEnd('-')
        return normalised.ifBlank { "unknown-capability" }
    }

    /** Block signature (§2.4): sha256Hex(capability + "|" + stepKind + "|" + action). */
    fun signature(capability: String, failure: ExecutorFailure): String =
        sha256Hex("$capability|${failure.stepKind}|${failure.action}")

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Minimal JSON helpers for the self-extension loop (shared by
 * [SelfExtensionResearcher] and the file-backed store). The loop
 * deliberately uses NO org.json here: these files must stay
 * JVM-testable on a plain kotlinc harness with only the stdlib.
 * Strict by design — malformed input returns null, never a
 * best-effort partial value (research needs strict JSON, §3.1).
 */
object SelfExtensionJson {

    fun escape(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    fun quote(value: String): String = "\"${escape(value)}\""

    fun stringListJson(values: List<String>): String =
        values.joinToString(",", "[", "]") { quote(it) }

    /** Strict parse of one JSON value. Returns null on any malformed input. */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue() ?: return null
        parser.skipWs()
        if (!parser.atEnd()) return null
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun asObject(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun asList(value: Any?): List<Any?>? = value as? List<Any?>

    fun asString(value: Any?): String? = value as? String

    fun asBool(value: Any?): Boolean? = value as? Boolean

    fun asLong(value: Any?): Long? = when (value) {
        is Long -> value
        is Double -> if (value == value.toLong().toDouble()) value.toLong() else null
        else -> null
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd(): Boolean {
            skipWs()
            return pos >= text.length
        }

        fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWs()
            if (pos >= text.length) return null
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> null
            }
        }

        private fun parseLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) return null
            pos += word.length
            return value
        }

        private fun parseNumber(): Any? {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            var sawDigit = false
            var sawDotOrExp = false
            while (pos < text.length) {
                val c = text[pos]
                when {
                    c in '0'..'9' -> { sawDigit = true; pos++ }
                    c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-' -> { sawDotOrExp = true; pos++ }
                    else -> break
                }
            }
            if (!sawDigit) return null
            val raw = text.substring(start, pos)
            return try {
                if (sawDotOrExp) raw.toDouble() else raw.toLong()
            } catch (e: NumberFormatException) {
                null
            }
        }

        private fun parseString(): String? {
            if (pos >= text.length || text[pos] != '"') return null
            pos++
            val out = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= text.length) return null
                        when (text[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) return null
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: return null
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> return null
                        }
                    }
                    else -> {
                        if (c.code < 0x20) return null
                        out.append(c)
                    }
                }
            }
            return null
        }

        private fun parseObject(): Map<String, Any?>? {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString() ?: return null
                skipWs()
                if (pos >= text.length || text[pos] != ':') return null
                pos++
                skipWs()
                val isNullLiteral = text.startsWith("null", pos)
                // parseValue returns null for a genuine null literal
                // AND for a failure; the literal check separates them.
                // (parseValue already advances past a null literal.)
                val value = parseValue()
                if (value == null && !isNullLiteral) return null
                map[key] = value
                skipWs()
                if (pos >= text.length) return null
                when (text[pos]) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; return map }
                    else -> return null
                }
            }
        }

        private fun parseArray(): List<Any?>? {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                // parseValue returns null for both a null literal
                // and a failure; accept a literal null only when the
                // next non-ws chars are "null".
                skipWs()
                val isNullLiteral = text.startsWith("null", pos)
                val value = parseValue()
                if (value == null && !isNullLiteral) return null
                list.add(value)
                skipWs()
                if (pos >= text.length) return null
                when (text[pos]) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; return list }
                    else -> return null
                }
            }
        }
    }
}
