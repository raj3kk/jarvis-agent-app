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
 * Google client (Track E #27) — signed calls to the hub's direct
 * Google OAuth routes (oauth/google/start, api/google/...).
 *
 * Auth mirrors DocConfirmClient / docs.ts `authorizeDocsRequest`:
 * headers x-device-id / x-nonce / x-ts / x-signature, signature
 * covers `device_id + "\n" + nonce + "\n" + ts + "\n" + payloadRaw`.
 * GET routes sign the path + query string; POST routes sign the
 * sorted-keys JSON of the body (DocConfirmClient.sortedJson).
 *
 * The app NEVER holds Google tokens: the hub stores them encrypted
 * in D1 (google_tokens) and calls Google. No token value is ever
 * logged or returned to the UI — status carries email + scopes only.
 * No new Android permission: INTERNET already declared; the consent
 * URL opens in the browser via ACTION_VIEW / openUrlOrDeepLink
 * (existing pattern, no WebView).
 */
object GoogleClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    sealed interface StatusResult {
        data class Ok(val connected: Boolean, val configured: Boolean, val email: String?, val scopes: String?) : StatusResult
        data class Err(val message: String) : StatusResult
    }

    sealed interface SendResult {
        data class Ok(val messageId: String) : SendResult
        data class Err(val message: String) : SendResult
    }

    data class GmailMessage(val id: String, val subject: String, val from: String, val date: String, val snippet: String, val body: String)

    sealed interface ReadResult {
        data class Ok(val messages: List<GmailMessage>) : ReadResult
        data class Err(val message: String) : ReadResult
    }

    data class DriveFile(val id: String, val name: String, val mimeType: String, val text: String?, val contentNote: String?)

    sealed interface DriveResult {
        data class Ok(val file: DriveFile) : DriveResult
        data class Err(val message: String) : DriveResult
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

    private fun deviceIdOrNull(context: Context): String? =
        runCatching { PairingManager.deviceId(context) }.getOrNull()?.takeIf { it.isNotBlank() }

    private suspend fun getSigned(context: Context, pathAndQuery: String): JSONObject? {
        val headers = signedHeaders(context, pathAndQuery) ?: return null
        val request = Request.Builder()
            .url("${PairingManager.HUB_URL}$pathAndQuery")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .get()
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

    private fun hubError(json: JSONObject?): String {
        if (json == null) return "Hub se jawab nahi mila — pairing ya network check karo."
        return when (val err = json.optString("error", "")) {
            "google_not_connected" -> "Google connect nahi hai — AI Brain > Google — Gmail & Drive me Connect dabao."
            "google_oauth_not_configured" -> "Hub par Google OAuth configure nahi hai — abhi setup baaki hai."
            "google_token_decrypt_failed" -> "Google tokens dobara connect karne honge — AI Brain > Google me Connect dabao."
            "" -> json.optString("detail", "Google call fail hua.")
            else -> json.optString("detail", err).take(200)
        }
    }

    /** GET /api/google/status — connected state for the consent card. */
    suspend fun getStatus(context: Context): StatusResult {
        val deviceId = deviceIdOrNull(context) ?: return StatusResult.Err("Device paired nahi hai — pehle pairing karo.")
        val json = getSigned(context, "/api/google/status?device_id=$deviceId")
            ?: return StatusResult.Err("Hub se jawab nahi mila — pairing ya network check karo.")
        if (!json.optBoolean("ok", false)) return StatusResult.Err(hubError(json))
        return StatusResult.Ok(
            connected = json.optBoolean("connected", false),
            configured = json.optBoolean("configured", false),
            email = json.optString("email", "").takeIf { it.isNotBlank() },
            scopes = json.optString("scopes", "").takeIf { it.isNotBlank() }
        )
    }

    /** GET /oauth/google/start — returns the Google consent URL to open in the browser. */
    suspend fun getStartUrl(context: Context): String? {
        val deviceId = deviceIdOrNull(context) ?: return null
        val json = getSigned(context, "/oauth/google/start?device_id=$deviceId") ?: return null
        if (!json.optBoolean("ok", false)) return null
        return json.optString("auth_url", "").takeIf { it.isNotBlank() }
    }

    /** POST /api/google/revoke — disconnects (hub revokes at Google + deletes the stored row). */
    suspend fun revoke(context: Context): Boolean {
        val deviceId = deviceIdOrNull(context) ?: return false
        val body = JSONObject().put("device_id", deviceId)
        val json = postSigned(context, "/api/google/revoke", body) ?: return false
        return json.optBoolean("ok", false)
    }

    /** POST /api/google/gmail/send (agent step GMAIL_SEND). */
    suspend fun gmailSend(context: Context, to: String, subject: String, body: String): SendResult {
        val deviceId = deviceIdOrNull(context)
            ?: return SendResult.Err("Device paired nahi hai — pehle pairing karo.")
        val jsonBody = JSONObject()
            .put("device_id", deviceId)
            .put("to", to)
            .put("subject", subject)
            .put("body", body)
        val json = postSigned(context, "/api/google/gmail/send", jsonBody)
            ?: return SendResult.Err("Hub se jawab nahi mila — pairing ya network check karo.")
        if (!json.optBoolean("ok", false)) return SendResult.Err(hubError(json))
        return SendResult.Ok(json.optString("id", ""))
    }

    /** GET /api/google/gmail/messages (agent step GMAIL_READ). */
    suspend fun gmailRead(context: Context, q: String?, maxResults: Int): ReadResult {
        val deviceId = deviceIdOrNull(context)
            ?: return ReadResult.Err("Device paired nahi hai — pehle pairing karo.")
        val query = buildString {
            append("/api/google/gmail/messages?device_id=$deviceId")
            if (!q.isNullOrBlank()) append("&q=${java.net.URLEncoder.encode(q, "UTF-8")}")
            append("&max_results=$maxResults")
        }
        val json = getSigned(context, query)
            ?: return ReadResult.Err("Hub se jawab nahi mila — pairing ya network check karo.")
        if (!json.optBoolean("ok", false)) return ReadResult.Err(hubError(json))
        val messages = mutableListOf<GmailMessage>()
        val arr = json.optJSONArray("messages") ?: return ReadResult.Ok(messages)
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            messages.add(
                GmailMessage(
                    id = m.optString("id", ""),
                    subject = m.optString("subject", ""),
                    from = m.optString("from", ""),
                    date = m.optString("date", ""),
                    snippet = m.optString("snippet", ""),
                    body = m.optString("body", "")
                )
            )
        }
        return ReadResult.Ok(messages)
    }

    /** GET /api/google/drive/files/:id (agent step DRIVE_READ). */
    suspend fun driveRead(context: Context, fileId: String): DriveResult {
        val deviceId = deviceIdOrNull(context)
            ?: return DriveResult.Err("Device paired nahi hai — pehle pairing karo.")
        val encoded = java.net.URLEncoder.encode(fileId, "UTF-8")
        val json = getSigned(context, "/api/google/drive/files/$encoded?device_id=$deviceId")
            ?: return DriveResult.Err("Hub se jawab nahi mila — pairing ya network check karo.")
        if (!json.optBoolean("ok", false)) return DriveResult.Err(hubError(json))
        val f = json.optJSONObject("file") ?: return DriveResult.Err("Drive file ka jawab adhoora aaya.")
        return DriveResult.Ok(
            DriveFile(
                id = f.optString("id", fileId),
                name = f.optString("name", fileId),
                mimeType = f.optString("mimeType", ""),
                text = f.optString("text", "").takeIf { f.has("text") && !f.isNull("text") },
                contentNote = f.optString("content_note", "").takeIf { it.isNotBlank() }
            )
        )
    }
}
