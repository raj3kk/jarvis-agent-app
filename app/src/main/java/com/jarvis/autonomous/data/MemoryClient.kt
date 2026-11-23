package com.jarvis.autonomous.data

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * R2.4 (1.17.0-PRO) — MemoryClient: the phone's client for the hub's
 * shared-memory routes (hub v18, live).
 *
 * Same device-signed pattern as CronJobClient: x-device-id / x-nonce /
 * x-ts / x-signature over the canonical
 * `device_id\nnonce\nts\npayloadRaw`. The phone does NOT store its
 * tenant id anywhere — the server resolves it from the paired device
 * (same as /api/billing/cron-jobs). For /api/memory/list the signed
 * payload is `memory-list:<tenant>:<since>:<limit>` with the tenant
 * segment empty (the server computes the same string from the query
 * param, which we send empty); for /api/memory/store the payload is
 * sortedJson(body).
 *
 * POST /api/memory/store needs an embedding (tenant BYOK or founder
 * key) — without a key the hub answers 402 byok_required. A failed
 * push never blocks the on-device save; the caller reports
 * "phone par save ho gaya, hub sync baad me" honestly.
 */
class MemoryClient(private val context: Context) {

    data class HubMemory(
        val id: Long,
        val content: String,
        val kind: String,
        val createdAt: Long
    )

    data class HubPattern(
        val id: String,
        val template: String,
        val source: String,
        val useCount: Int
    )

    data class PullResult(
        val memories: List<HubMemory>,
        val patterns: List<HubPattern>,
        val since: Long
    )

    sealed class StoreResult {
        data class Ok(val memoryId: Long, val kind: String) : StoreResult()
        data class Err(val reason: String) : StoreResult()
    }

    sealed class PullOutcome {
        data class Ok(val result: PullResult) : PullOutcome()
        data class Err(val reason: String) : PullOutcome()
    }

    companion object {
        const val MAX_CONTENT_CHARS = 4000 // == hub MAX_MEMORY_CONTENT_CHARS

        /** Pure: parse a /api/memory/list body. Unit-tested. */
        fun parseListJson(json: JSONObject): PullResult {
            val memories = ArrayList<HubMemory>()
            val memArr = json.optJSONArray("memories")
            if (memArr != null) {
                for (i in 0 until memArr.length()) {
                    val o = memArr.optJSONObject(i) ?: continue
                    memories.add(
                        HubMemory(
                            id = o.optLong("id", 0L),
                            content = o.optString("content", ""),
                            kind = o.optString("kind", ""),
                            createdAt = o.optLong("created_at", 0L)
                        )
                    )
                }
            }
            val patterns = ArrayList<HubPattern>()
            val patArr = json.optJSONArray("patterns")
            if (patArr != null) {
                for (i in 0 until patArr.length()) {
                    val o = patArr.optJSONObject(i) ?: continue
                    patterns.add(
                        HubPattern(
                            id = o.optString("id", ""),
                            template = o.optString("template", ""),
                            source = o.optString("source", ""),
                            useCount = o.optInt("use_count", 0)
                        )
                    )
                }
            }
            return PullResult(memories, patterns, json.optLong("since", 0L))
        }

        /** Pure: build the signed payload for GET /api/memory/list. */
        fun listPayloadRaw(tenantId: String, since: Long, limit: Int): String =
            "memory-list:$tenantId:$since:$limit"
    }

    private val httpClient = OkHttpClient.Builder().build()

    private fun signedHeaders(payloadRaw: String): Map<String, String>? {
        val deviceId = runCatching { PairingManager.deviceId(context) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
        val ts = System.currentTimeMillis()
        val canonical = "$deviceId\n$nonce\n$ts\n$payloadRaw"
        val sig = runCatching {
            Base64.encodeToString(
                PairingManager.sign(context, canonical.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )
        }.getOrNull() ?: return null
        return mapOf(
            "x-device-id" to deviceId,
            "x-nonce" to nonce,
            "x-ts" to ts.toString(),
            "x-signature" to sig
        )
    }

    private suspend fun execute(request: Request): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.newCall(request).execute().use { resp ->
                val text = runCatching { resp.body?.string() ?: "" }.getOrDefault("")
                runCatching { JSONObject(text) }.getOrNull()
            }
        }.getOrNull()
    }

    /**
     * Store one memory on the hub (D1). [content] is capped at 4000
     * chars (hub limit) — callers chunk or summarise first. [kind]
     * is "document" for Trained docs, "fact" otherwise.
     */
    suspend fun storeMemory(content: String, kind: String): StoreResult {
        val deviceId = runCatching { PairingManager.deviceId(context) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return StoreResult.Err("device_not_paired")
        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("tenant_id", "")
            put("content", content.take(MAX_CONTENT_CHARS))
            put("kind", kind.ifBlank { "fact" })
        }
        val headers = signedHeaders(DocConfirmClient.sortedJson(body))
            ?: return StoreResult.Err("device_not_paired")
        val request = Request.Builder()
            .url("${PairingManager.HUB_URL}/api/memory/store")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val json = execute(request) ?: return StoreResult.Err("hub_no_reply")
        if (!json.optBoolean("ok", false)) {
            return StoreResult.Err(json.optString("error", "hub_error"))
        }
        return StoreResult.Ok(json.optLong("memory_id", 0L), json.optString("kind", kind))
    }

    /**
     * Pull memories + approved patterns from the hub. [since] is a
     * created_at cursor (0 = from the start). Newest first.
     */
    suspend fun pullMemories(since: Long = 0L, limit: Int = 50): PullOutcome {
        val payloadRaw = listPayloadRaw("", since, limit)
        val headers = signedHeaders(payloadRaw)
            ?: return PullOutcome.Err("device_not_paired")
        val url = "${PairingManager.HUB_URL}/api/memory/list" +
            "?tenant_id=&since=$since&limit=$limit"
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .get()
            .build()
        val json = execute(request) ?: return PullOutcome.Err("hub_no_reply")
        if (!json.optBoolean("ok", false)) {
            return PullOutcome.Err(json.optString("error", "hub_error"))
        }
        return PullOutcome.Ok(parseListJson(json))
    }

    /**
     * Cache a pull result into the on-device stores (R2.4 app leg).
     * - Hub memories of kind "document" (the "[Doc: title]" summaries
     *   this phone or the admin pushed) become on-device docs, source
     *   "hub", deduped by title — the full body stays on the device
     *   that ingested it; the summary is what travels.
     * - Hub patterns are instruction TEXT (admin train stores the
     *   instruction itself as the template, not a LearnedPattern
     *   JSON) — they become LearnedInstruction entries, deduped by
     *   text. Other memory kinds (facts) stay hub-side; the phone's
     *   doc recall does not store raw facts.
     * Returns (memoriesCached, instructionsCached).
     */
    suspend fun applyPullToDeviceStores(
        result: PullResult,
        documentMemory: DocumentMemoryStore,
        patternStore: LearnedPatternStore
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var docsCached = 0
        var instructionsCached = 0
        val existingTitles = runCatching { documentMemory.listDocuments().map { it.title }.toSet() }
            .getOrDefault(emptySet())
        for (mem in result.memories) {
            if (mem.kind != "document") continue
            val title = Regex("^\\[Doc: (.*)]\\n").find(mem.content)?.groupValues?.get(1) ?: continue
            if (title in existingTitles) continue
            val body = mem.content.substringAfter("\n").trim()
            if (body.isBlank()) continue
            val saved = runCatching { documentMemory.saveDocument(title, body, "hub") }.getOrNull()
            if (saved?.ok == true) docsCached++
        }
        val existingInstructions = runCatching {
            patternStore.listInstructions().map { it.text }.toSet()
        }.getOrDefault(emptySet())
        for (pattern in result.patterns) {
            val text = pattern.template.trim()
            if (text.isEmpty() || text in existingInstructions) continue
            val saved = runCatching {
                patternStore.saveInstruction(
                    text,
                    MemoryKeywords.extract(text),
                    listOf("PLAN", "LOOP")
                )
            }.getOrNull()
            if (saved != null) instructionsCached++
        }
        docsCached to instructionsCached
    }
}
