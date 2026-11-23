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
import java.util.concurrent.TimeUnit

/**
 * R4.11 (U8-SERVER) — device-signed "user approved" signal.
 *
 * The phone-side Approve tap (MainActivity proposals card) records
 * the user's yes locally first (SelfExtensionStore, status
 * "user_approved"); this client then tells the hub via POST
 * /api/skills/approve {device_id, skill_id} (device-signed
 * Ed25519, same authorizeDocsRequest pattern as GoogleClient /
 * SelfExtensionInstaller). The hub records a skill_reviews row
 * (decision "user_approved", idempotent — a repeat approve is the
 * same result) and the skill STAYS quarantined until a clean
 * re-scan + admin review approves it (frozen rule, untouched).
 *
 * Server fail => the phone record stays and the user is told
 * (the caller shows it) — never a silent drop. A proposal whose
 * serverSkillId is blank was never submitted to the hub, so there
 * is no server skill to approve: the caller skips the POST and
 * says so honestly instead of sending a made-up id.
 */
object SkillApprovalClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    sealed interface ApproveResult {
        /** Recorded server-side. deduped = this approve was a repeat. */
        data class Ok(val deduped: Boolean) : ApproveResult
        data class Err(val message: String) : ApproveResult
    }

    /** POST /api/skills/approve for a submitted skill. */
    suspend fun approve(context: Context, skillId: String): ApproveResult {
        if (skillId.isBlank()) {
            return ApproveResult.Err("Skill hub par submit hi nahi hui thi — server ko batane ke liye koi skill id nahi hai.")
        }
        val deviceId = runCatching { PairingManager.deviceId(context) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return ApproveResult.Err("Device paired nahi hai — pehle pairing karo.")
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("skill_id", skillId)
        val json = postSigned(context, "/api/skills/approve", body)
            ?: return ApproveResult.Err("Hub se jawab nahi mila — pairing ya network check karo.")
        return parseApproveResponse(json)
    }

    /** Pure mapping of the /api/skills/approve response (no I/O). */
    fun parseApproveResponse(json: JSONObject?): ApproveResult {
        if (json == null) return ApproveResult.Err("Hub se jawab nahi mila.")
        if (!json.optBoolean("ok", false)) {
            return when (val err = json.optString("error", "")) {
                "skill_not_found" -> ApproveResult.Err("Skill server par nahi mili — shayad delete ho gayi; phone par record rehta hai.")
                "device_not_paired" -> ApproveResult.Err("Device paired nahi hai — pehle pairing karo.")
                "rate_limited" -> ApproveResult.Err("Bahut jaldi try kar rahe ho — thodi der ruk kar dobara try karo.")
                "" -> ApproveResult.Err(json.optString("detail", "Server ne approve record nahi kiya.").take(200))
                else -> ApproveResult.Err(json.optString("detail", err).take(200))
            }
        }
        return ApproveResult.Ok(deduped = json.optBoolean("deduped", false))
    }

    private suspend fun postSigned(context: Context, path: String, body: JSONObject): JSONObject? {
        val headers = signedHeaders(context, DocConfirmClient.sortedJson(body)) ?: return null
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
}
