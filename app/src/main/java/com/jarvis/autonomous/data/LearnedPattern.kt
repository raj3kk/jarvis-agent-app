package com.jarvis.autonomous.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * v2 Gap 3 — learned patterns + user-taught instructions (release
 * 1.9.0-PRO, vc 45). Pure record types + JSON (`org.json`, as I1
 * does in `DocumentMemory.kt`) — zero Android imports in this file.
 * Storage lives in [LearnedPatternStore]; the policy (matching,
 * scoring, confidence, extraction filter) lives in
 * `LearnedPatternPolicy` (pure Kotlin, JVM-tested).
 *
 * PRIVACY (hard boundary, reviewable in one grep): this file imports
 * NO networking. Pattern/instruction content is never read by
 * `maybeUploadLearnedPattern` and never leaves the phone — see
 * DESIGN.md §5.7 and the CI grep step. Patterns carry step labels +
 * kinds only — never per-task values (project/repo names, URLs,
 * contacts, message bodies); the store refuses any record whose
 * fields trip the extraction filter instead of saving a redacted
 * pattern.
 */
data class LearnedPatternStep(
    val label: String,
    val kind: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("label", label)
        put("kind", kind)
    }

    companion object {
        fun fromJson(obj: JSONObject): LearnedPatternStep? {
            return try {
                val label = obj.optString("label", "")
                val kind = obj.optString("kind", "")
                if (label.isBlank() || kind.isBlank()) return null
                LearnedPatternStep(label = label, kind = kind)
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class LearnedPattern(
    val id: String,
    val triggerSignature: String,
    val triggerKeywords: List<String>,
    val workKind: String, // "PLAN" | "LOOP"
    val title: String,
    val steps: List<LearnedPatternStep>,
    val failure: String = "",
    val fix: String = "",
    val sourceCommandId: String = "",
    val verified: Boolean = true,
    val confidence: Double = 0.5,
    val useCount: Int = 0,
    val successCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastUsedAtMs: Long = 0L,
    val createdAtMs: Long = 0L,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("id", id)
        put("kind", "PATTERN")
        put("triggerSignature", triggerSignature)
        val kwArr = JSONArray()
        for (kw in triggerKeywords) kwArr.put(kw)
        put("triggerKeywords", kwArr)
        put("workKind", workKind)
        put("title", title)
        val stepsArr = JSONArray()
        for (step in steps) stepsArr.put(step.toJson())
        put("steps", stepsArr)
        put("failure", failure)
        put("fix", fix)
        put("sourceCommandId", sourceCommandId)
        put("verified", verified)
        put("confidence", confidence)
        put("useCount", useCount)
        put("successCount", successCount)
        put("consecutiveFailures", consecutiveFailures)
        put("lastUsedAtMs", lastUsedAtMs)
        put("createdAtMs", createdAtMs)
        put("enabled", enabled)
    }

    companion object {
        /** Fail-closed: unknown schema version or any parse problem returns null. */
        fun fromJson(obj: JSONObject): LearnedPattern? {
            return try {
                if (obj.optInt("schemaVersion", -1) != 1) return null
                val id = obj.optString("id", "")
                val signature = obj.optString("triggerSignature", "")
                val title = obj.optString("title", "")
                if (id.isBlank() || signature.isBlank() || title.isBlank()) return null
                val keywords = ArrayList<String>()
                val kwArr = obj.optJSONArray("triggerKeywords")
                if (kwArr != null) {
                    for (i in 0 until kwArr.length()) {
                        val kw = kwArr.optString(i, "")
                        if (kw.isNotBlank()) keywords.add(kw)
                    }
                }
                val steps = ArrayList<LearnedPatternStep>()
                val stepsArr = obj.optJSONArray("steps")
                if (stepsArr != null) {
                    for (i in 0 until stepsArr.length()) {
                        val stepObj = stepsArr.optJSONObject(i) ?: continue
                        val step = LearnedPatternStep.fromJson(stepObj) ?: continue
                        steps.add(step)
                    }
                }
                if (steps.isEmpty()) return null
                LearnedPattern(
                    id = id,
                    triggerSignature = signature,
                    triggerKeywords = keywords,
                    workKind = obj.optString("workKind", "LOOP").ifBlank { "LOOP" },
                    title = title,
                    steps = steps,
                    failure = obj.optString("failure", ""),
                    fix = obj.optString("fix", ""),
                    sourceCommandId = obj.optString("sourceCommandId", ""),
                    verified = obj.optBoolean("verified", true),
                    confidence = obj.optDouble("confidence", 0.5),
                    useCount = obj.optInt("useCount", 0),
                    successCount = obj.optInt("successCount", 0),
                    consecutiveFailures = obj.optInt("consecutiveFailures", 0),
                    lastUsedAtMs = obj.optLong("lastUsedAtMs", 0L),
                    createdAtMs = obj.optLong("createdAtMs", 0L),
                    enabled = obj.optBoolean("enabled", true)
                )
            } catch (e: Exception) {
                null
            }
        }

        fun fromJsonString(raw: String): LearnedPattern? {
            return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
        }
    }
}

data class LearnedInstruction(
    val id: String,
    val text: String,
    val scopeKeywords: List<String>,
    val appliesTo: List<String>, // subset of ["PLAN", "LOOP"]
    val createdAtMs: Long = 0L,
    val useCount: Int = 0,
    val lastUsedAtMs: Long = 0L,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("id", id)
        put("kind", "INSTRUCTION")
        put("text", text)
        val kwArr = JSONArray()
        for (kw in scopeKeywords) kwArr.put(kw)
        put("scopeKeywords", kwArr)
        val appliesArr = JSONArray()
        for (kind in appliesTo) appliesArr.put(kind)
        put("appliesTo", appliesArr)
        put("createdAtMs", createdAtMs)
        put("useCount", useCount)
        put("lastUsedAtMs", lastUsedAtMs)
        put("enabled", enabled)
    }

    companion object {
        /** Fail-closed: unknown schema version or any parse problem returns null. */
        fun fromJson(obj: JSONObject): LearnedInstruction? {
            return try {
                if (obj.optInt("schemaVersion", -1) != 1) return null
                val id = obj.optString("id", "")
                val text = obj.optString("text", "")
                if (id.isBlank() || text.isBlank()) return null
                val keywords = ArrayList<String>()
                val kwArr = obj.optJSONArray("scopeKeywords")
                if (kwArr != null) {
                    for (i in 0 until kwArr.length()) {
                        val kw = kwArr.optString(i, "")
                        if (kw.isNotBlank()) keywords.add(kw)
                    }
                }
                val applies = ArrayList<String>()
                val appliesArr = obj.optJSONArray("appliesTo")
                if (appliesArr != null) {
                    for (i in 0 until appliesArr.length()) {
                        val kind = appliesArr.optString(i, "")
                        if (kind.isNotBlank()) applies.add(kind)
                    }
                }
                LearnedInstruction(
                    id = id,
                    text = text,
                    scopeKeywords = keywords,
                    appliesTo = applies.ifEmpty { listOf("PLAN", "LOOP") },
                    createdAtMs = obj.optLong("createdAtMs", 0L),
                    useCount = obj.optInt("useCount", 0),
                    lastUsedAtMs = obj.optLong("lastUsedAtMs", 0L),
                    enabled = obj.optBoolean("enabled", true)
                )
            } catch (e: Exception) {
                null
            }
        }

        fun fromJsonString(raw: String): LearnedInstruction? {
            return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
        }
    }
}

/** Index metadata for one pattern (everything lookup needs without opening the file). */
data class LearnedPatternMeta(
    val id: String,
    val title: String,
    val triggerSignature: String,
    val triggerKeywords: List<String>,
    val workKind: String,
    val confidence: Double,
    val useCount: Int,
    val successCount: Int,
    val consecutiveFailures: Int,
    val lastUsedAtMs: Long,
    val createdAtMs: Long,
    val enabled: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("triggerSignature", triggerSignature)
        val kwArr = JSONArray()
        for (kw in triggerKeywords) kwArr.put(kw)
        put("triggerKeywords", kwArr)
        put("workKind", workKind)
        put("confidence", confidence)
        put("useCount", useCount)
        put("successCount", successCount)
        put("consecutiveFailures", consecutiveFailures)
        put("lastUsedAtMs", lastUsedAtMs)
        put("createdAtMs", createdAtMs)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(id: String, obj: JSONObject): LearnedPatternMeta? {
            return try {
                val title = obj.optString("title", "")
                val signature = obj.optString("triggerSignature", "")
                if (title.isBlank() || signature.isBlank()) return null
                val keywords = ArrayList<String>()
                val kwArr = obj.optJSONArray("triggerKeywords")
                if (kwArr != null) {
                    for (i in 0 until kwArr.length()) {
                        val kw = kwArr.optString(i, "")
                        if (kw.isNotBlank()) keywords.add(kw)
                    }
                }
                LearnedPatternMeta(
                    id = id,
                    title = title,
                    triggerSignature = signature,
                    triggerKeywords = keywords,
                    workKind = obj.optString("workKind", "LOOP").ifBlank { "LOOP" },
                    confidence = obj.optDouble("confidence", 0.5),
                    useCount = obj.optInt("useCount", 0),
                    successCount = obj.optInt("successCount", 0),
                    consecutiveFailures = obj.optInt("consecutiveFailures", 0),
                    lastUsedAtMs = obj.optLong("lastUsedAtMs", 0L),
                    createdAtMs = obj.optLong("createdAtMs", 0L),
                    enabled = obj.optBoolean("enabled", true)
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toPatternStepsHint(): String = title
}

/** Index metadata for one instruction. */
data class LearnedInstructionMeta(
    val id: String,
    val text: String,
    val scopeKeywords: List<String>,
    val appliesTo: List<String>,
    val createdAtMs: Long,
    val useCount: Int,
    val lastUsedAtMs: Long,
    val enabled: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text)
        val kwArr = JSONArray()
        for (kw in scopeKeywords) kwArr.put(kw)
        put("scopeKeywords", kwArr)
        val appliesArr = JSONArray()
        for (kind in appliesTo) appliesArr.put(kind)
        put("appliesTo", appliesArr)
        put("createdAtMs", createdAtMs)
        put("useCount", useCount)
        put("lastUsedAtMs", lastUsedAtMs)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(id: String, obj: JSONObject): LearnedInstructionMeta? {
            return try {
                val text = obj.optString("text", "")
                if (text.isBlank()) return null
                val keywords = ArrayList<String>()
                val kwArr = obj.optJSONArray("scopeKeywords")
                if (kwArr != null) {
                    for (i in 0 until kwArr.length()) {
                        val kw = kwArr.optString(i, "")
                        if (kw.isNotBlank()) keywords.add(kw)
                    }
                }
                val applies = ArrayList<String>()
                val appliesArr = obj.optJSONArray("appliesTo")
                if (appliesArr != null) {
                    for (i in 0 until appliesArr.length()) {
                        val kind = appliesArr.optString(i, "")
                        if (kind.isNotBlank()) applies.add(kind)
                    }
                }
                LearnedInstructionMeta(
                    id = id,
                    text = text,
                    scopeKeywords = keywords,
                    appliesTo = applies.ifEmpty { listOf("PLAN", "LOOP") },
                    createdAtMs = obj.optLong("createdAtMs", 0L),
                    useCount = obj.optInt("useCount", 0),
                    lastUsedAtMs = obj.optLong("lastUsedAtMs", 0L),
                    enabled = obj.optBoolean("enabled", true)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
