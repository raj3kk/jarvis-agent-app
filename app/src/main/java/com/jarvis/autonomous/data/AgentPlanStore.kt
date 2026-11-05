package com.jarvis.autonomous.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 1.4.21 Agent Mode — persisted plan model + store + encrypted
 * provider secrets. Pehle-Jaanch §3b/§3c is the design authority.
 *
 * Plan file: filesDir/agent_plan.json (app-private files dir, NOT
 * SharedPreferences — it survives a prefs clear and is never mixed
 * with settings). Writes are atomic: agent_plan.json.tmp then a
 * rename. A corrupt/unparseable file is treated as NO plan (a
 * diagnostic is logged, never a crash, never a resume of garbage).
 * Evidence strings are short facts (commit SHA, URLs, validation
 * result, HTTP status) — NEVER a token, never file contents, never
 * the user's utterance beyond the goal itself.
 *
 * Secrets file: EncryptedSharedPreferences "jarvis_agent_secrets"
 * (MasterKey AES256_GCM). Keys exactly: github_pat,
 * supabase_pat, supabase_project_ref, vercel_token. Tokens are
 * sent ONLY as Authorization: Bearer headers, never as URL query
 * params, and never appear in a log, chat line, plan JSON, or
 * returned status string.
 */
enum class PlanState {
    PENDING, RUNNING, DONE, FAILED, BLOCKED, SKIPPED
}

data class PlanStep(
    val id: String,
    val kind: String,
    val title: String,
    val detail: String = "",
    val state: PlanState = PlanState.PENDING,
    val evidence: String = "",
    val attempts: Int = 0,
    val updatedAtMs: Long = 0L,
    val needsGate: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("title", title)
        put("detail", detail)
        put("state", state.name)
        put("evidence", evidence)
        put("attempts", attempts)
        put("updatedAtMs", updatedAtMs)
        put("needsGate", needsGate)
    }

    companion object {
        fun fromJson(obj: JSONObject): PlanStep? {
            return try {
                val stateName = obj.optString("state", "PENDING")
                val state = runCatching { PlanState.valueOf(stateName) }.getOrDefault(PlanState.PENDING)
                PlanStep(
                    id = obj.optString("id", ""),
                    kind = obj.optString("kind", ""),
                    title = obj.optString("title", ""),
                    detail = obj.optString("detail", ""),
                    state = state,
                    evidence = obj.optString("evidence", ""),
                    attempts = obj.optInt("attempts", 0),
                    updatedAtMs = obj.optLong("updatedAtMs", 0L),
                    needsGate = obj.optBoolean("needsGate", false)
                ).takeIf { it.id.isNotBlank() && it.kind.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** UI-facing snapshot of one step (read by the plan card). */
data class PlanStepView(
    val id: String,
    val kind: String,
    val title: String,
    val detail: String,
    val state: PlanState,
    val evidence: String
)

/** UI-facing snapshot of a plan (read by the plan card + FGS notification). */
data class PlanView(
    val planId: String,
    val goal: String,
    val steps: List<PlanStepView>,
    val completed: Boolean
)

data class AgentPlan(
    val planId: String,
    val goal: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val currentStepId: String?,
    val answeredClarifications: List<String>,
    val steps: List<PlanStep>,
    /** Terminal record: every step DONE, or terminally BLOCKED/FAILED
     *  after asking, or user-declined. A completed plan is NEVER
     *  auto-resumed; a new plan overwrites the file. */
    val completed: Boolean = false
) {
    fun toView(): PlanView = PlanView(
        planId = planId,
        goal = goal,
        steps = steps.map {
            PlanStepView(it.id, it.kind, it.title, it.detail, it.state, it.evidence)
        },
        completed = completed
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("planId", planId)
        put("goal", goal)
        put("createdAtMs", createdAtMs)
        put("updatedAtMs", updatedAtMs)
        if (currentStepId != null) put("currentStepId", currentStepId) else put("currentStepId", JSONObject.NULL)
        put("answeredClarifications", JSONArray(answeredClarifications))
        put("steps", JSONArray(steps.map { it.toJson() }))
        put("completed", completed)
    }

    companion object {
        fun fromJson(raw: String): AgentPlan? {
            return try {
                val obj = JSONObject(raw)
                val planId = obj.optString("planId", "")
                val goal = obj.optString("goal", "")
                if (planId.isBlank() || goal.isBlank()) return null
                val clarifications = mutableListOf<String>()
                val clarArr = obj.optJSONArray("answeredClarifications")
                if (clarArr != null) {
                    for (i in 0 until clarArr.length()) {
                        clarArr.optString(i, "").takeIf { it.isNotBlank() }?.let { clarifications.add(it) }
                    }
                }
                val steps = mutableListOf<PlanStep>()
                val stepsArr = obj.optJSONArray("steps") ?: return null
                for (i in 0 until stepsArr.length()) {
                    val stepObj = stepsArr.optJSONObject(i) ?: return null
                    steps.add(PlanStep.fromJson(stepObj) ?: return null)
                }
                if (steps.isEmpty()) return null
                AgentPlan(
                    planId = planId,
                    goal = goal,
                    createdAtMs = obj.optLong("createdAtMs", 0L),
                    updatedAtMs = obj.optLong("updatedAtMs", 0L),
                    currentStepId = if (obj.isNull("currentStepId")) null else obj.optString("currentStepId", "").takeIf { it.isNotBlank() },
                    answeredClarifications = clarifications,
                    steps = steps,
                    completed = obj.optBoolean("completed", false)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

object AgentPlanStore {
    const val FILE_NAME = "agent_plan.json"
    private const val TMP_NAME = "agent_plan.json.tmp"

    // 1.4.22 (item 5d): load() runs on every FGS recreation,
    // demote, plan start and MainActivity resume — a corrupt file
    // must log PLAN_CORRUPT only once per file version, not on
    // every load. Signature = file length + last-modified.
    @Volatile
    private var lastCorruptSignature: String? = null

    /** Atomic write: tmp file + rename. Every write goes through here. */
    fun save(context: Context, plan: AgentPlan) {
        runCatching {
            val dir = context.filesDir
            val tmp = File(dir, TMP_NAME)
            val target = File(dir, FILE_NAME)
            tmp.writeText(plan.toJson().toString())
            // renameTo is atomic on the same filesystem; if it fails,
            // fall back to delete + rename so a stale file never
            // survives as the "current" plan.
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }.onFailure { e ->
            JarvisStateRepository.addDiagnostic(
                "plan", "PLAN_SAVE_FAIL",
                "Plan save fail hua: ${e.javaClass.simpleName}"
            )
        }
    }

    /** Null = no plan, completed plan, or corrupt file (corrupt is
     *  logged once as a diagnostic, never resumed, never a crash). */
    fun load(context: Context): AgentPlan? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        val plan = AgentPlan.fromJson(raw)
        if (plan == null) {
            val signature = "${file.length()}:${file.lastModified()}"
            if (signature != lastCorruptSignature) {
                lastCorruptSignature = signature
                JarvisStateRepository.addDiagnostic(
                    "plan", "PLAN_CORRUPT",
                    "agent_plan.json corrupt/unparseable hai — plan resume nahi hoga."
                )
            }
        } else {
            lastCorruptSignature = null
        }
        return plan
    }

    fun clear(context: Context) {
        runCatching {
            File(context.filesDir, FILE_NAME).delete()
            File(context.filesDir, TMP_NAME).delete()
        }
    }
}

/**
 * 1.4.21 Agent Mode — write-capable provider tokens. Encrypted
 * storage ONLY (EncryptedSharedPreferences, MasterKey AES256_GCM).
 * There is deliberately NO fallback to plain SharedPreferences:
 * if the encrypted store cannot be created, the exception
 * propagates and the failure is loud, never a silent plain-text
 * save. Test functions return a status string only — never the
 * token, never the request URL.
 */
object AgentSecrets {
    const val FILE_NAME = "jarvis_agent_secrets"
    const val KEY_GITHUB_PAT = "github_pat"
    const val KEY_SUPABASE_PAT = "supabase_pat"
    const val KEY_SUPABASE_PROJECT_REF = "supabase_project_ref"
    const val KEY_VERCEL_TOKEN = "vercel_token"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun get(context: Context, key: String): String {
        return runCatching { prefs(context).getString(key, "") ?: "" }.getOrDefault("")
    }

    fun has(context: Context, key: String): Boolean = get(context, key).isNotBlank()

    /** Persists ONLY when called (Save button tap). A blank value
     *  clears the stored token instead of saving an empty one. */
    fun put(context: Context, key: String, value: String) {
        val p = prefs(context)
        if (value.isBlank()) {
            p.edit().remove(key).apply()
        } else {
            p.edit().putString(key, value.trim()).apply()
        }
    }

    private fun getJson(url: String, token: String): Pair<Int, String> {
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return resp.code to body
        }
    }

    /** GitHub: one cheap GET /user — returns the login name on
     *  success, HTTP code + body message otherwise. */
    suspend fun testGithub(context: Context): String = withContext(Dispatchers.IO) {
        val token = get(context, KEY_GITHUB_PAT)
        if (token.isBlank()) return@withContext "GitHub PAT saved nahi hai — pehle token daalo aur SAVE dabao."
        try {
            val (code, body) = getJson("https://api.github.com/user", token)
            if (code == 200) {
                val login = runCatching { JSONObject(body).optString("login", "") }.getOrDefault("")
                "OK (HTTP 200) — GitHub login: ${login.ifBlank { "unknown" }}"
            } else {
                "HTTP $code: ${body.take(200)}"
            }
        } catch (e: Exception) {
            "Network error: ${(e.message ?: "unknown").take(200)}"
        }
    }

    /** Supabase: GET the named project via the Management API. */
    suspend fun testSupabase(context: Context): String = withContext(Dispatchers.IO) {
        val token = get(context, KEY_SUPABASE_PAT)
        val ref = get(context, KEY_SUPABASE_PROJECT_REF)
        if (token.isBlank()) return@withContext "Supabase PAT saved nahi hai — pehle token daalo aur SAVE dabao."
        if (ref.isBlank()) return@withContext "Supabase project ref khaali hai — Project Settings se ref daalo aur SAVE dabao."
        try {
            val (code, body) = getJson("https://api.supabase.com/v1/projects/$ref", token)
            if (code == 200) {
                val name = runCatching { JSONObject(body).optString("name", "") }.getOrDefault("")
                "OK (HTTP 200) — Supabase project: ${name.ifBlank { ref }}"
            } else {
                "HTTP $code: ${body.take(200)}"
            }
        } catch (e: Exception) {
            "Network error: ${(e.message ?: "unknown").take(200)}"
        }
    }

    /** Vercel: one cheap GET /v2/user. */
    suspend fun testVercel(context: Context): String = withContext(Dispatchers.IO) {
        val token = get(context, KEY_VERCEL_TOKEN)
        if (token.isBlank()) return@withContext "Vercel token saved nahi hai — pehle token daalo aur SAVE dabao."
        try {
            val (code, body) = getJson("https://api.vercel.com/v2/user", token)
            if (code == 200) {
                val username = runCatching {
                    JSONObject(body).optJSONObject("user")?.optString("username", "") ?: ""
                }.getOrDefault("")
                "OK (HTTP 200) — Vercel user: ${username.ifBlank { "connected" }}"
            } else {
                "HTTP $code: ${body.take(200)}"
            }
        } catch (e: Exception) {
            "Network error: ${(e.message ?: "unknown").take(200)}"
        }
    }
}
