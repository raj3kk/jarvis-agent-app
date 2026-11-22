package com.jarvis.autonomous.data

import android.content.Context
import android.util.Base64
import com.google.firebase.messaging.FirebaseMessaging
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * FCM token registration (1.12.0, DESIGN expansion/fcm/DESIGN.md).
 *
 * Signed POST /api/fcm/register — same device-signing pattern as
 * CaptchaSolveClient (x-device-id / x-nonce / x-ts / x-signature over
 * device_id + "\n" + nonce + "\n" + ts + "\n" + sortedJson(body),
 * via PairingManager.sign). Sends ONLY {device_id, fcm_token}.
 * NEVER logs the token.
 *
 * syncToken(): fetch the current Firebase token and register it when
 * it differs from the last-registered one (stored in plain prefs —
 * the token is useless without the hub's server key, and Firebase
 * keeps its own copy in prefs anyway). Silent no-op when unpaired,
 * when there is no token, or when the network fails — the next app
 * start retries.
 */
object FcmTokenClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15_000, TimeUnit.MILLISECONDS)
        .readTimeout(15_000, TimeUnit.MILLISECONDS)
        .writeTimeout(15_000, TimeUnit.MILLISECONDS)
        .build()

    sealed interface RegisterResult {
        data object Registered : RegisterResult
        data object NotPaired : RegisterResult
        data class Failed(val error: String) : RegisterResult
    }

    private fun signedHeaders(context: Context, body: JSONObject): Map<String, String>? {
        val deviceId = runCatching { PairingManager.deviceId(context) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
        val ts = System.currentTimeMillis()
        val payloadRaw = DocConfirmClient.sortedJson(body)
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

    suspend fun register(context: Context, token: String): RegisterResult {
        if (token.isBlank()) return RegisterResult.Failed("empty_token")
        val deviceId = runCatching { PairingManager.deviceId(context) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return RegisterResult.NotPaired
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("fcm_token", token)
        val headers = signedHeaders(context, body) ?: return RegisterResult.NotPaired
        val json = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${PairingManager.HUB_URL}/api/fcm/register")
                    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    val text = runCatching { resp.body?.string() ?: "" }.getOrDefault("")
                    runCatching { JSONObject(text) }.getOrNull()
                }
            }.getOrNull()
        } ?: return RegisterResult.Failed("transport")
        return if (json.optBoolean("ok", false)) {
            RegisterResult.Registered
        } else {
            RegisterResult.Failed(json.optString("error", "hub_error"))
        }
    }

    /** Fetch + register when changed. True when the hub has the current token. */
    suspend fun syncToken(context: Context): Boolean {
        val prefs = JarvisPreferences(context)
        if (!prefs.pairingPaired) return false
        val token = runCatching {
            withContext(Dispatchers.IO) { FirebaseMessaging.getInstance().token.await() }
        }.getOrNull() ?: return false
        if (token.isBlank()) return false
        if (token == prefs.fcmToken) return true
        return when (register(context, token)) {
            RegisterResult.Registered -> {
                prefs.fcmToken = token
                prefs.fcmRegisteredAtMs = System.currentTimeMillis()
                true
            }
            else -> false
        }
    }
}
