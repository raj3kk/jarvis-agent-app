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
 * Captcha solve client (DESIGN §2.2–§2.4, app side only).
 *
 * Signed POST /api/captcha/solve — same device-signing pattern as
 * DocConfirmClient (x-device-id / x-nonce / x-ts / x-signature over
 * device_id + "\n" + nonce + "\n" + ts + "\n" + sortedJson(body),
 * via PairingManager.sign). Sends ONLY task_id / site (host only) /
 * challenge_type / image_b64 / auto_solve_allowed=true. The prompt
 * lives hub-side; the app sends only the image (§2.6).
 *
 * 15 s per-call timeout client-side (§3.1 CAPTCHA_SOLVE_TIMEOUT_MS).
 * The hub does the 3-leg chain internally; the app makes ONE call
 * per challenge phase.
 *
 * NEVER logs the answer or the image — not here, not in callers.
 * The parsed answer is returned in memory only, for the apply step.
 */
object CaptchaSolveClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CaptchaSolvePolicy.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(CaptchaSolvePolicy.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(CaptchaSolvePolicy.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    sealed interface SolveResult {
        /** Hub returned a ≥0.80, schema-valid answer. */
        data class Ok(
            val solveId: String,
            val answer: CaptchaSolvePolicy.SolveAnswer,
            val confidence: Double,
            val model: String,
            val attempts: Int,
            val latencyMs: Long
        ) : SolveResult

        /** Any failure — caller falls to human-assist (§3.2). */
        data class Failed(
            val error: String,
            val solveId: String? = null,
            val attempts: Int = 0,
            val latencyMs: Long = 0L
        ) : SolveResult
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

    /**
     * One solve call. `imageB64` is the ≤200 KB PNG from the live
     * WebView (never re-fetched). Returns Failed on any transport,
     * auth, parse or confidence problem — the caller never has to
     * distinguish; every failure means human-assist.
     */
    suspend fun solve(
        context: Context,
        taskId: String,
        site: String,
        challengeType: String,
        imageB64: String
    ): SolveResult {
        val startedMs = System.currentTimeMillis()
        if (!CaptchaSolvePolicy.isAutoSolvableType(challengeType)) {
            return SolveResult.Failed(error = "not_allowed", latencyMs = 0L)
        }
        val body = JSONObject()
            .put("task_id", taskId)
            .put("site", site)
            .put("challenge_type", challengeType)
            .put("image_b64", imageB64)
            .put("auto_solve_allowed", true)
        val headers = signedHeaders(context, body)
            ?: return SolveResult.Failed(
                error = "not_paired",
                latencyMs = System.currentTimeMillis() - startedMs
            )
        val json = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${PairingManager.HUB_URL}/api/captcha/solve")
                    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    val text = runCatching { resp.body?.string() ?: "" }.getOrDefault("")
                    runCatching { JSONObject(text) }.getOrNull()
                }
            }.getOrNull()
        } ?: return SolveResult.Failed(
            error = "model_error",
            latencyMs = System.currentTimeMillis() - startedMs
        )
        val latencyMs = System.currentTimeMillis() - startedMs
        val solveId = json.optString("solve_id", "").takeIf { it.isNotBlank() }
        val attempts = json.optInt("attempts", 1).coerceIn(0, CaptchaSolvePolicy.MAX_AUTO_SOLVE_ATTEMPTS)
        if (!json.optBoolean("ok", false)) {
            // Failure contract (§2.4): error + fallback human_assist.
            // The error string is metadata only — never the answer.
            return SolveResult.Failed(
                error = json.optString("error", "model_error").takeIf { it.isNotBlank() } ?: "model_error",
                solveId = solveId,
                attempts = attempts,
                latencyMs = latencyMs
            )
        }
        val confidence = json.optDouble("confidence", -1.0)
        if (!CaptchaSolvePolicy.isConfident(confidence)) {
            return SolveResult.Failed(
                error = "low_confidence",
                solveId = solveId,
                attempts = attempts,
                latencyMs = latencyMs
            )
        }
        val answerObj = json.optJSONObject("answer")
        val answer = CaptchaSolvePolicy.parseAnswer(answerObj?.toString())
            ?: return SolveResult.Failed(
                error = "no_answer",
                solveId = solveId,
                attempts = attempts,
                latencyMs = latencyMs
            )
        return SolveResult.Ok(
            solveId = solveId ?: "",
            answer = answer,
            confidence = confidence,
            model = json.optString("model", ""),
            attempts = attempts,
            latencyMs = latencyMs
        )
    }
}
