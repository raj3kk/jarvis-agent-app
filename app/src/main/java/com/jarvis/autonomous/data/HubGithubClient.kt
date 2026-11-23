package com.jarvis.autonomous.data

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * R1 (1.16.0-PRO) — hub-proxy GitHub client (REDESIGN_PLAN.md §4.1).
 *
 * The phone NEVER talks to api.github.com directly any more and
 * NEVER holds a GitHub token: the admin token lives in the hub
 * Worker secret, and the phone calls only these device-signed hub
 * routes (same signing as CronJobClient / DocConfirmClient):
 *
 * - POST /api/github/fetch       — thin proxy: body {owner, repo,
 *   path} where path is a GitHub API path (e.g.
 *   "repos/{o}/{r}/git/trees/{sha}?recursive=1"); the hub returns
 *   {ok, status, body} with the GitHub JSON passed through.
 * - POST /api/github/pr-request  — body {repo, project, files:
 *   [{path, content}]}; the hub creates/updates the repo with the
 *   admin token and returns {ok, sha, owner, repo, branch}.
 * - POST /api/escalate           — body {goal, tried, error, need};
 *   the Main Executor picks it up async (REDESIGN_PLAN.md §4.3).
 *
 * SERVER CONTRACT (server track must honor it — these routes land
 * with the server deploy; until then every call returns
 * NotConfigured/Err and the Executor parks with a plain-words
 * message, never a crash, never a silent fail):
 * - github_not_configured: the hub has no admin GitHub token yet
 *   (error "github_not_configured", or HTTP 503). The Talker tells
 *   the user plainly ("GitHub hub par set nahi hai — admin ko
 *   token lagana hoga") and the task parks.
 * - No token, no repo id, no GitHub JSON ever comes back to the
 *   phone except the public tree/commit data the fetch asked for.
 *
 * Supabase/Vercel tokens STAY on the phone (user's own projects —
 * only GitHub moved server-side).
 */
object HubGithubClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    sealed interface PushResult {
        data class Ok(val sha: String, val owner: String, val repo: String, val branch: String) : PushResult
        /** Hub has no admin GitHub token (github_not_configured / 503). */
        data object NotConfigured : PushResult
        data class Err(val message: String) : PushResult
    }

    sealed interface FetchResult {
        data class Ok(val status: Int, val body: JSONObject) : FetchResult
        /** Hub has no admin GitHub token (github_not_configured / 503). */
        data object NotConfigured : FetchResult
        data class Err(val message: String) : FetchResult
    }

    sealed interface EscalateResult {
        data object Ok : EscalateResult
        data object NotConfigured : EscalateResult
        data class Err(val message: String) : EscalateResult
    }

    // ---- Pure helpers (JVM unit test seam — no Android, no network) ----

    /** True when a hub error body means "admin GitHub token not set". */
    fun isNotConfigured(json: JSONObject?, httpCode: Int): Boolean {
        if (httpCode == 503) return true
        if (json == null) return false
        val error = json.optString("error", "")
        return error == "github_not_configured" || error == "github_not_setup"
    }

    /** Parse a POST /api/github/pr-request response. */
    fun parsePushJson(json: JSONObject, httpCode: Int): PushResult {
        if (isNotConfigured(json, httpCode)) return PushResult.NotConfigured
        if (!json.optBoolean("ok", false)) {
            return PushResult.Err(
                json.optString("detail", "").ifBlank {
                    json.optString("error", "Hub se GitHub push nahi ho paya.").take(200)
                }
            )
        }
        val sha = json.optString("sha", "")
        val owner = json.optString("owner", "")
        val repo = json.optString("repo", "")
        if (sha.isBlank() || owner.isBlank() || repo.isBlank()) {
            return PushResult.Err("Hub ne push ka jawab adhoora bheja (sha/owner/repo nahi mila).")
        }
        return PushResult.Ok(
            sha = sha, owner = owner, repo = repo,
            branch = json.optString("branch", "main").ifBlank { "main" }
        )
    }

    /** Parse a POST /api/github/fetch response ({ok, status, body}). */
    fun parseFetchJson(json: JSONObject, httpCode: Int): FetchResult {
        if (isNotConfigured(json, httpCode)) return FetchResult.NotConfigured
        if (!json.optBoolean("ok", false)) {
            return FetchResult.Err(
                json.optString("detail", "").ifBlank {
                    json.optString("error", "Hub se GitHub fetch nahi ho paya.").take(200)
                }
            )
        }
        val body = json.optJSONObject("body") ?: return FetchResult.Err(
            "Hub ne fetch ka jawab adhoora bheja (body nahi mila)."
        )
        return FetchResult.Ok(status = json.optInt("status", 200), body = body)
    }

    // ---- Signing (same canonical string as CronJobClient) ----

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

    private suspend fun postSigned(context: Context, path: String, body: JSONObject): Pair<Int, JSONObject?> {
        val headers = signedHeaders(context, body) ?: return 0 to null
        val request = Request.Builder()
            .url("${PairingManager.HUB_URL}$path")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): Pair<Int, JSONObject?> = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.newCall(request).execute().use { resp ->
                val text = runCatching { resp.body?.string() ?: "" }.getOrDefault("")
                resp.code to runCatching { JSONObject(text) }.getOrNull()
            }
        }.getOrDefault(0 to null)
    }

    // ---- Hub calls ----

    /**
     * Push a built project to GitHub via the hub (admin token).
     * [files] is path -> content. The hub creates the repo when
     * missing (private, user-given name only — never invented).
     */
    suspend fun pushProject(
        context: Context,
        repo: String,
        projectName: String,
        files: Map<String, String>
    ): PushResult {
        val body = JSONObject()
            .put("repo", repo)
            .put("project", projectName)
            .put("files", JSONArray().apply {
                files.forEach { (path, content) ->
                    put(JSONObject().put("path", path).put("content", content))
                }
            })
        val (code, json) = postSigned(context, "/api/github/pr-request", body)
        if (json == null) {
            return if (code == 0) PushResult.Err("Hub se jawab nahi mila — pairing ya network check karo.")
            else PushResult.Err("Hub ne HTTP $code bheja, jawab samajh nahi aaya.")
        }
        return parsePushJson(json, code)
    }

    /**
     * Fetch one GitHub API path via the hub. [path] is the GitHub
     * API path WITHOUT a leading slash, e.g.
     * "repos/{owner}/{repo}/git/trees/{sha}?recursive=1".
     */
    suspend fun fetch(
        context: Context,
        owner: String,
        repo: String,
        path: String
    ): FetchResult {
        val body = JSONObject()
            .put("owner", owner)
            .put("repo", repo)
            .put("path", path)
        val (code, json) = postSigned(context, "/api/github/fetch", body)
        if (json == null) {
            return if (code == 0) FetchResult.Err("Hub se jawab nahi mila — pairing ya network check karo.")
            else FetchResult.Err("Hub ne HTTP $code bheja, jawab samajh nahi aaya.")
        }
        return parseFetchJson(json, code)
    }

    /**
     * Escalate to the Main Executor (§4.3): goal in plain words,
     * [tried] = kinds tried (enums only, never content), [error] =
     * last error text (≤5,000 chars, no personal content),
     * [need] = what is needed, one line. Best-effort — a failed
     * escalate never fails the park itself.
     */
    suspend fun escalate(
        context: Context,
        goal: String,
        tried: List<String>,
        error: String,
        need: String
    ): EscalateResult {
        val body = JSONObject()
            .put("goal", goal.take(500))
            .put("tried", JSONArray(tried))
            .put("error", error.take(5000))
            .put("need", need.take(200))
        val (code, json) = postSigned(context, "/api/escalate", body)
        if (json == null) {
            return if (code == 0) EscalateResult.Err("Hub se jawab nahi mila.")
            else EscalateResult.Err("Hub ne HTTP $code bheja.")
        }
        if (isNotConfigured(json, code)) return EscalateResult.NotConfigured
        return if (json.optBoolean("ok", false)) EscalateResult.Ok
        else EscalateResult.Err(json.optString("detail", json.optString("error", "Escalate nahi ho paya.")).take(200))
    }
}
