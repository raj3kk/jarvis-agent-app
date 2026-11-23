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
 * (MasterKey AES256_GCM). Keys exactly: supabase_pat,
 * supabase_project_ref, vercel_token. Tokens are
 * sent ONLY as Authorization: Bearer headers, never as URL query
 * params, and never appear in a log, chat line, plan JSON, or
 * returned status string.
 *
 * R1 (1.16.0-PRO, REDESIGN_PLAN.md §4.1): the GitHub PAT is GONE —
 * no github_pat key, no GitHub field in AI Brain, no direct GitHub
 * call from the phone. GitHub goes through the hub
 * (HubGithubClient: POST /api/github/fetch, /api/github/pr-request,
 * /api/escalate, device-signed; admin token lives in the hub Worker
 * secret). Supabase/Vercel tokens STAY — they are the user's own
 * projects; only GitHub moved server-side.
 */
enum class PlanState {
    PENDING, RUNNING, DONE, FAILED, BLOCKED, SKIPPED,
    /** 3c: a browser step is waiting on the user (captcha assist).
     *  Persisted like RUNNING; resume demotes it to PENDING with
     *  verify-before-re-run (never blindly re-run). */
    AWAITING_USER
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

/** Internal work-unit snapshot (never rendered — the plan card shows
 *  only plain-words status, never a unit list). */
data class PlanStepView(
    val id: String,
    val kind: String,
    val title: String,
    val detail: String,
    val state: PlanState,
    val evidence: String
)

/** Snapshot of a plan (read by the plan card + FGS notification).
 *  R1: the card renders only the goal + current activity in plain
 *  words — the unit list stays internal, never shown. */
data class PlanView(
    val planId: String,
    val goal: String,
    val steps: List<PlanStepView>,
    val completed: Boolean
)

/** R2.7 (ToolRegistry): one trajectory line — tool name + outcome +
 *  verdict, NO args, NO content (mini-swe shape). Arg values can be
 *  PII (an email body, a URL with a token) and never go in a
 *  trajectory, a log, or a plan file. */
data class PlanTrajectoryEntry(
    val tool: String,
    val outcome: String,
    val verdict: String,
    val tsMs: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("tool", tool)
        put("outcome", outcome)
        put("verdict", verdict)
        put("tsMs", tsMs)
    }

    companion object {
        fun fromJson(obj: JSONObject?): PlanTrajectoryEntry? {
            if (obj == null) return null
            return runCatching {
                PlanTrajectoryEntry(
                    tool = obj.optString("tool", ""),
                    outcome = obj.optString("outcome", ""),
                    verdict = obj.optString("verdict", ""),
                    tsMs = obj.optLong("tsMs", 0L)
                ).takeIf { it.tool.isNotBlank() }
            }.getOrNull()
        }
    }
}

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
    val completed: Boolean = false,
    /** R1 (N3, §9.3): persisted task-budget state (LLM calls used,
     *  work units done, fix attempts, started-at). Absent on old
     *  plans — fromJson treats that as a fresh budget. */
    val budget: com.jarvis.autonomous.agent.TaskBudgetState? = null,
    /** R2.7 (ToolRegistry): persisted per-step trajectory — tool
     *  name + outcome + verdict, NO args, NO content (mini-swe
     *  shape). Absent on old plans — fromJson defaults to empty. */
    val trajectory: List<PlanTrajectoryEntry> = emptyList()
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
        if (budget != null) put("budget", budget.toJson())
        if (trajectory.isNotEmpty()) {
            put("trajectory", JSONArray(trajectory.map { it.toJson() }))
        }
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
                val trajectory = mutableListOf<PlanTrajectoryEntry>()
                val trajArr = obj.optJSONArray("trajectory")
                if (trajArr != null) {
                    for (i in 0 until trajArr.length()) {
                        PlanTrajectoryEntry.fromJson(trajArr.optJSONObject(i) ?: continue)
                            ?.let { trajectory.add(it) }
                    }
                }
                AgentPlan(
                    planId = planId,
                    goal = goal,
                    createdAtMs = obj.optLong("createdAtMs", 0L),
                    updatedAtMs = obj.optLong("updatedAtMs", 0L),
                    currentStepId = if (obj.isNull("currentStepId")) null else obj.optString("currentStepId", "").takeIf { it.isNotBlank() },
                    answeredClarifications = clarifications,
                    steps = steps,
                    completed = obj.optBoolean("completed", false),
                    budget = com.jarvis.autonomous.agent.TaskBudgetState.fromJson(obj.optJSONObject("budget")),
                    trajectory = trajectory
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

    /** Atomic write: tmp file + rename. Every write goes through here.
     *  R2.6: once migrated, the plan lives in the Jarvis Store
     *  (SEC_AGENT_PLAN) instead of agent_plan.json. */
    fun save(context: Context, plan: AgentPlan) {
        if (JarvisStore.isMigrated()) {
            JarvisStore.setSection(JarvisStore.SEC_AGENT_PLAN, plan.toJson())
            return
        }
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
        if (JarvisStore.isMigrated()) {
            val obj = JarvisStore.getSection(JarvisStore.SEC_AGENT_PLAN) as? JSONObject
                ?: return null
            return AgentPlan.fromJson(obj.toString())
        }
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
        if (JarvisStore.isMigrated()) {
            JarvisStore.setSection(JarvisStore.SEC_AGENT_PLAN, null)
        }
        runCatching {
            File(context.filesDir, FILE_NAME).delete()
            File(context.filesDir, TMP_NAME).delete()
        }
    }

    /**
     * 3c: mark the currently RUNNING step AWAITING_USER (a browser
     * captcha is waiting on the user). Called from the browser layer
     * (JarvisBackgroundBrowser), NOT the executor — the executor's
     * in-memory copy still says RUNNING and overwrites this mark
     * when the step ends (DONE/FAILED). The mark is therefore a
     * persisted display + kill-resume state only: if the process
     * dies while waiting, resume demotes AWAITING_USER to PENDING
     * with verify-before-re-run. No-op when no step is RUNNING (a
     * direct browse with no plan, or the step already ended).
     */
    fun markRunningStepAwaitingUser(context: Context, site: String, taskName: String): Boolean {
        val plan = load(context) ?: return false
        if (plan.completed) return false
        val running = plan.steps.firstOrNull { it.state == PlanState.RUNNING } ?: return false
        val now = System.currentTimeMillis()
        val updated = plan.copy(
            steps = plan.steps.map {
                if (it.id == running.id) {
                    it.copy(
                        state = PlanState.AWAITING_USER,
                        evidence = (it.evidence + " | Captcha — $site par madad chahiye ($taskName)").take(300),
                        updatedAtMs = now
                    )
                } else it
            },
            updatedAtMs = now
        )
        save(context, updated)
        JarvisStateRepository.setPlanState(updated.toView())
        return true
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
    // R1: KEY_GITHUB_PAT REMOVED (REDESIGN_PLAN.md §4.1) — GitHub
    // goes through the hub (HubGithubClient); the phone holds no
    // GitHub token any more. A stale github_pat value left in the
    // encrypted store from 1.14.0 is simply never read.
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
