package com.jarvis.autonomous.data

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Cron-job client (M-I-30, 1.14.0) — signed calls to the hub cron
 * routes (POST /api/cron/parse + POST/GET/DELETE
 * /api/billing/cron-jobs). Auth mirrors DocConfirmClient: headers
 * x-device-id / x-nonce / x-ts / x-signature, where the signature
 * covers `device_id + "\n" + nonce + "\n" + ts + "\n" + payloadRaw`
 * and payloadRaw is the sorted-keys JSON of the request body
 * (DocConfirmClient.sortedJson) — except the GET list, whose
 * payloadRaw is the literal `billing-cron-jobs:<tenant_id>` string
 * (empty tenant when the device resolves it server-side), and the
 * DELETE, whose payloadRaw is the literal `{}`.
 *
 * Server contract (hub v13, live-verified): parse is deterministic
 * first ("har subah 8 baje" -> `0 8 * * *`), LLM fallback second,
 * anything unvalidated is 422 unparseable — never a fake cron
 * expression. Times are the user's local time; the client sends
 * utc_offset_minutes (IST = 330) and the server converts to UTC.
 * A job fires as a CRON_COMMAND step to the device when due (tick
 * every 5 min — exact minute ka vaada kabhi nahi; the card says so).
 * No tokens on the phone (device-signed only), no new Android
 * permission. Plan caps are enforced server-side (cron_cap 403) and
 * surfaced verbatim — the card never invents a limit.
 */
object CronJobClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    data class CronJob(
        val id: String,
        val cronExpr: String,
        val command: String,
        val enabled: Boolean,
        val lastRunAt: Long?,
        val nextRunAt: Long?,
        val createdAt: Long
    )

    sealed interface ListResult {
        data class Ok(val jobs: List<CronJob>) : ListResult
        data class Err(val message: String) : ListResult
    }

    sealed interface ParseResult {
        data class Ok(val cronExpr: String, val source: String, val nextRunAt: Long?) : ParseResult
        data class Unparseable(val detail: String) : ParseResult
        data class Err(val message: String) : ParseResult
    }

    sealed interface CreateResult {
        data class Ok(val jobId: String, val nextRunAt: Long?) : CreateResult
        data class Cap(val detail: String) : CreateResult
        data class Err(val message: String) : CreateResult
    }

    sealed interface OpResult {
        data object Ok : OpResult
        data class Err(val message: String) : OpResult
    }

    // ---- Pure helpers (JVM unit test seam — no Android, no network) ----

    /** Parse the GET list payload. Tolerates missing/null run times. */
    fun parseJobsJson(json: JSONObject): List<CronJob> {
        val arr = json.optJSONArray("jobs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "")
            if (id.isBlank()) return@mapNotNull null
            CronJob(
                id = id,
                cronExpr = o.optString("cron_expr", ""),
                command = o.optString("command", ""),
                enabled = o.optInt("enabled", 0) == 1,
                lastRunAt = optEpoch(o, "last_run_at"),
                nextRunAt = optEpoch(o, "next_run_at"),
                createdAt = o.optLong("created_at", 0)
            )
        }
    }

    /** Parse the POST /api/cron/parse payload. */
    fun parseParseJson(json: JSONObject): ParseResult {
        if (json.optBoolean("ok", false)) {
            return ParseResult.Ok(
                cronExpr = json.optString("cron_expr", ""),
                source = json.optString("source", ""),
                nextRunAt = optEpoch(json, "next_run_at")
            )
        }
        if (json.optString("error", "") == "unparseable") {
            return ParseResult.Unparseable(
                json.optString("detail", "").ifBlank {
                    "Time samajh nahi aaya — dobara saaf shabdon me likho (jaise: har subah 8 baje)"
                }
            )
        }
        return ParseResult.Err(mapError(json))
    }

    /** IST (device timezone by default) rendering of a UTC-epoch run time. */
    fun formatNextRun(epochMs: Long?, tz: TimeZone = TimeZone.getDefault()): String {
        if (epochMs == null || epochMs <= 0) return "—"
        val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)
        fmt.timeZone = tz
        return fmt.format(Date(epochMs))
    }

    /** Device standard UTC offset in minutes (IST = 330). */
    fun utcOffsetMinutes(tz: TimeZone = TimeZone.getDefault()): Int = tz.rawOffset / 60000

    private fun optEpoch(o: JSONObject, key: String): Long? =
        if (o.isNull(key)) null else o.optLong(key, -1).takeIf { it >= 0 }

    // ---- Signing (same canonical string as DocConfirmClient) ----

    private fun signedHeaders(context: Context, payloadRaw: String): Map<String, String>? {
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

    private fun signedHeaders(context: Context, body: JSONObject): Map<String, String>? =
        signedHeaders(context, DocConfirmClient.sortedJson(body))

    private suspend fun postSigned(context: Context, path: String, body: JSONObject): JSONObject? {
        val headers = signedHeaders(context, body) ?: return null
        val request = Request.Builder()
            .url("${PairingManager.HUB_URL}$path")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return execute(request)
    }

    private suspend fun getSigned(context: Context, path: String, payloadRaw: String): JSONObject? {
        val headers = signedHeaders(context, payloadRaw) ?: return null
        val request = Request.Builder()
            .url("${PairingManager.HUB_URL}$path")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .get()
            .build()
        return execute(request)
    }

    private suspend fun deleteSigned(context: Context, path: String): JSONObject? {
        // DELETE signs the literal "{}" (server calls billingAuth({})).
        val headers = signedHeaders(context, "{}") ?: return null
        val request = Request.Builder()
            .url("${PairingManager.HUB_URL}$path")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .delete()
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.newCall(request).execute().use { resp ->
                val text = runCatching { resp.body?.string() ?: "" }.getOrDefault("")
                runCatching { JSONObject(text) }.getOrNull()
            }
        }.getOrNull()
    }

    // ---- Hub calls ----

    /** List this device's jobs (tenant resolved server-side from the device). */
    suspend fun listJobs(context: Context): ListResult {
        val json = getSigned(context, "/api/billing/cron-jobs", "billing-cron-jobs:")
            ?: return ListResult.Err("Hub se jawab nahi mila — pairing ya network check karo.")
        if (json.optBoolean("ok", false)) return ListResult.Ok(parseJobsJson(json))
        return ListResult.Err(mapError(json))
    }

    /** NL time -> cron expression (deterministic first, LLM fallback second). */
    suspend fun parseNatural(context: Context, text: String): ParseResult {
        val body = JSONObject()
            .put("text", text)
            .put("utc_offset_minutes", utcOffsetMinutes())
        val json = postSigned(context, "/api/cron/parse", body)
            ?: return ParseResult.Err("Hub se jawab nahi mila — pairing ya network check karo.")
        return parseParseJson(json)
    }

    /** Create a job from a parsed expression + the command to run. */
    suspend fun createJob(context: Context, cronExpr: String, command: String): CreateResult {
        val deviceId = runCatching { PairingManager.deviceId(context) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return CreateResult.Err("Device pair nahi hai — pehle PairingCard se pair karo.")
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("cron_expr", cronExpr)
            .put("command", command)
        val json = postSigned(context, "/api/billing/cron-jobs", body)
            ?: return CreateResult.Err("Hub se jawab nahi mila — pairing ya network check karo.")
        if (json.optBoolean("ok", false)) {
            return CreateResult.Ok(
                jobId = json.optString("job_id", ""),
                nextRunAt = optEpoch(json, "next_run_at")
            )
        }
        if (json.optString("error", "") == "cron_cap") {
            return CreateResult.Cap(
                json.optString("detail", "").ifBlank {
                    "Cron limit khatam — purana band karo ya upgrade karo."
                }
            )
        }
        return CreateResult.Err(mapError(json))
    }

    /** Enable / disable a job (enable recomputes next_run_at server-side). */
    suspend fun setEnabled(context: Context, jobId: String, enabled: Boolean): OpResult {
        val path = "/api/billing/cron-jobs/$jobId/${if (enabled) "enable" else "disable"}"
        val json = postSigned(context, path, JSONObject())
            ?: return OpResult.Err("Hub se jawab nahi mila — pairing ya network check karo.")
        if (json.optBoolean("ok", false)) return OpResult.Ok
        return OpResult.Err(mapError(json))
    }

    /** Delete a job outright (no un-delete — the card confirms first). */
    suspend fun deleteJob(context: Context, jobId: String): OpResult {
        val json = deleteSigned(context, "/api/billing/cron-jobs/$jobId")
            ?: return OpResult.Err("Hub se jawab nahi mila — pairing ya network check karo.")
        if (json.optBoolean("ok", false)) return OpResult.Ok
        return OpResult.Err(mapError(json))
    }

    private fun mapError(json: JSONObject): String {
        val error = json.optString("error", "")
        val detail = json.optString("detail", "")
        val base = when (error) {
            "device_not_paired" -> "Device pair nahi hai — pehle PairingCard se pair karo."
            "tenant_not_found" -> "Tenant nahi mila — pairing check karo."
            "job_not_found" -> "Job nahi mila — list refresh karo."
            "bad_cron" -> "Cron expression galat hai — time dobara samjhao."
            "cron_cap" -> "Cron limit khatam — purana band karo ya upgrade karo."
            "unparseable" -> "Time samajh nahi aaya — dobara saaf shabdon me likho (jaise: har subah 8 baje)"
            "" -> "Hub se jawab nahi mila — pairing ya network check karo."
            else -> "Fail: $error"
        }
        return if (detail.isNotBlank() && base.startsWith("Fail:")) "$base — ${detail.take(120)}" else base
    }
}
