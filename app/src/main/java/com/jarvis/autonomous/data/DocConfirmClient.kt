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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Doc confirm client (3c) — signed calls to the 3b doc routes
 * (POST /api/docs/extract + /api/docs/confirm). Auth mirrors
 * docs.ts `authorizeDocsRequest`: headers x-device-id / x-nonce /
 * x-ts / x-signature, where the signature covers the canonical
 * string `device_id + "\n" + nonce + "\n" + ts + "\n" + payloadRaw`
 * and payloadRaw is the sorted-keys JSON of the request body
 * (docs.ts `sortedJson`). The key value is never logged.
 *
 * Entry point (3c): the Command tab's Documents section — the user
 * enters a doc_job_id + the field names to extract, [Extract
 * dikhao] loads the extract response into the ConfirmCard, and
 * [Confirm & Proceed] confirms it. The doc job itself comes from
 * the 3b pipeline (ingest needs the Render worker live, U-2).
 */
object DocConfirmClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    sealed interface ExtractResult {
        data class Ok(
            val docJobId: Long,
            val fields: Map<String, String>,
            val missing: List<String>,
            val confidence: Double
        ) : ExtractResult

        data class Err(val message: String) : ExtractResult
    }

    sealed interface ConfirmResult {
        data class Ok(val docJobId: Long, val fields: Map<String, String>) : ConfirmResult
        data class Missing(val missing: List<String>, val fields: Map<String, String>) : ConfirmResult
        data class Err(val message: String) : ConfirmResult
    }

    /** Sorted-keys JSON (stable payload for docs signatures). */
    fun sortedJson(value: Any?): String = when (value) {
        null -> "null"
        is JSONObject -> {
            val keys = value.keys().asSequence().toList().sorted()
            keys.joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + sortedJson(value.opt(k)) }
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { sortedJson(value.opt(it)) }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> {
            val keys = value.keys.map { it.toString() }.sorted()
            keys.joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + sortedJson(value[k]) }
        }
        else -> JSONObject.quote(value.toString())
    }

    private fun signedHeaders(context: Context, body: JSONObject): Map<String, String>? {
        val deviceId = runCatching { PairingManager.deviceId(context) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
        val ts = System.currentTimeMillis()
        val payloadRaw = sortedJson(body)
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

    private suspend fun postSigned(context: Context, path: String, body: JSONObject): JSONObject? {
        val headers = signedHeaders(context, body) ?: return null
        val request = Request.Builder()
            .url("${PairingManager.HUB_URL}$path")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            runCatching {
                httpClient.newCall(request).execute().use { resp ->
                    val text = runCatching { resp.body?.string() ?: "" }.getOrDefault("")
                    runCatching { JSONObject(text) }.getOrNull()
                }
            }.getOrNull()
        }
    }

    /** Load an extract response for a doc job (server reads the
     *  job's stored OCR text when `text` is empty — 3b contract).
     *  `fieldNames` builds the extraction schema. */
    suspend fun loadExtract(context: Context, docJobId: Long, fieldNames: List<String>): ExtractResult {
        val properties = JSONObject()
        for (name in fieldNames) properties.put(name, JSONObject())
        val body = JSONObject()
            .put("doc_job_id", docJobId)
            .put("schema", JSONObject().put("properties", properties))
            .put("turn_id", "doc-${System.currentTimeMillis()}")
        val json = postSigned(context, "/api/docs/extract", body)
            ?: return ExtractResult.Err("Hub se jawab nahi mila — pairing ya network check karo.")
        if (json.optBoolean("ok", false)) {
            val fieldsObj = json.optJSONObject("fields") ?: JSONObject()
            val fields = fieldsObj.keys().asSequence().toList()
                .associateWith { fieldsObj.opt(it)?.toString() ?: "" }
            val missing = json.optJSONArray("missing")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList()
            return ExtractResult.Ok(
                docJobId = json.optLong("doc_job_id", docJobId),
                fields = fields,
                missing = missing,
                confidence = json.optDouble("confidence", 0.0)
            )
        }
        return ExtractResult.Err("Extract fail: ${json.optString("error", "unknown")}")
    }

    /** Confirm the merged record (one-shot, 3b /confirm contract).
     *  `fields` = extracted + user-filled values; `required` = the
     *  full field list (server re-checks missing). */
    suspend fun confirmDoc(
        context: Context,
        docJobId: Long,
        fields: Map<String, String>,
        required: List<String>
    ): ConfirmResult {
        val body = JSONObject()
            .put("doc_job_id", docJobId)
            .put("fields", JSONObject(fields))
            .put("required", JSONArray(required))
        val json = postSigned(context, "/api/docs/confirm", body)
            ?: return ConfirmResult.Err("Hub se jawab nahi mila — pairing ya network check karo.")
        if (json.optBoolean("ok", false)) {
            val fieldsObj = json.optJSONObject("fields") ?: JSONObject()
            val out = fieldsObj.keys().asSequence().toList()
                .associateWith { fieldsObj.opt(it)?.toString() ?: "" }
            return ConfirmResult.Ok(docJobId = docJobId, fields = out)
        }
        if (json.optString("error", "") == "missing_fields") {
            val missing = json.optJSONArray("missing")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList()
            val fieldsObj = json.optJSONObject("fields") ?: JSONObject()
            val out = fieldsObj.keys().asSequence().toList()
                .associateWith { fieldsObj.opt(it)?.toString() ?: "" }
            return ConfirmResult.Missing(missing = missing, fields = out)
        }
        return ConfirmResult.Err("Confirm fail: ${json.optString("error", "unknown")}")
    }
}
