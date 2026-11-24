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
import org.json.JSONObject

/**
 * SkillsClient (1.21.0-PRO) — the phone's client for the hub's
 * skills-library catalog routes (hub v22, migration 0018, live).
 *
 * Auth mirrors CronJobClient / MemoryClient: device-signed headers
 * x-device-id / x-nonce / x-ts / x-signature over the canonical
 * `device_id\nnonce\nts\npayloadRaw`. The catalog routes use FROZEN
 * payload strings (hub.ts):
 * - GET /api/skills/catalog?tenant_id=<id> signs `catalog:<tenant_id>`
 * - POST /api/skills/catalog/install signs
 *   `catalog-install:<tenant_id>:<skill_id>`
 *
 * The phone does NOT store its tenant id at pairing time (before
 * 1.21.0 it was never persisted). The catalog GET sends an empty
 * tenant_id and the server resolves it from the paired device (same
 * as /api/memory/list and /api/billing/cron-jobs). The install route
 * REQUIRES a non-empty tenant_id in the body (hub 400s otherwise),
 * so install resolves it first via [ensureTenantId]: the stored
 * value (JarvisPreferences.pairingTenantId, saved at pairing from
 * 1.21.0 on), else one idempotent /api/pairing/auto call (the hub
 * comment says re-pairing an already-paired device upserts, no cap
 * hit) using the stored licence key + device id — then it is stored
 * so later opens need no network call for it.
 *
 * The install result is reported honestly: tier 403 and cap 403 are
 * surfaced verbatim (never a fake success), and an idempotent repeat
 * is "already installed" (deduped:true), not an error. No new
 * permission, no secret on the phone (device-signed only).
 */
object SkillsClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    data class CatalogSkill(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val tier: String,
        val installed: Boolean
    )

    data class CatalogResult(
        val skills: List<CatalogSkill>,
        val planTier: String,
        val count: Int
    )

    sealed interface CatalogOutcome {
        data class Ok(val result: CatalogResult) : CatalogOutcome
        data class Err(val message: String) : CatalogOutcome
    }

    sealed interface InstallOutcome {
        data class Ok(val deduped: Boolean, val skill: CatalogSkill) : InstallOutcome
        data class TierRequired(val requiredTier: String, val planTier: String) : InstallOutcome
        data class CapReached(val planTier: String, val installed: Int, val cap: Int) : InstallOutcome
        data class Err(val message: String) : InstallOutcome
    }

    // ---- Pure helpers (JVM unit test seam — no Android, no network) ----

    /** Frozen payload for GET /api/skills/catalog (hub.ts). */
    fun catalogPayloadRaw(tenantId: String): String = "catalog:$tenantId"

    /** Frozen payload for POST /api/skills/catalog/install (hub.ts). */
    fun installPayloadRaw(tenantId: String, skillId: String): String =
        "catalog-install:$tenantId:$skillId"

    /** Parse the GET /api/skills/catalog body. Tolerates missing fields. */
    fun parseCatalogJson(json: JSONObject): CatalogResult {
        val planTier = json.optString("plan_tier", "")
        val skills = ArrayList<CatalogSkill>()
        val arr = json.optJSONArray("skills")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isBlank()) continue
                skills.add(
                    CatalogSkill(
                        id = id,
                        name = o.optString("name", ""),
                        description = o.optString("description", ""),
                        category = o.optString("category", ""),
                        tier = o.optString("tier", "free"),
                        installed = o.optBoolean("installed", false)
                    )
                )
            }
        }
        return CatalogResult(skills, planTier, json.optInt("count", skills.size))
    }

    /** Parse the POST /api/skills/catalog/install body (any HTTP code — the
     * caller passes the parsed JSON; the error field decides the outcome). */
    fun parseInstallJson(json: JSONObject): InstallOutcome {
        if (json.optBoolean("ok", false)) {
            val skillObj = json.optJSONObject("skill")
            val skill = if (skillObj != null) {
                CatalogSkill(
                    id = skillObj.optString("id", json.optString("skill_id", "")),
                    name = skillObj.optString("name", ""),
                    description = skillObj.optString("description", ""),
                    category = skillObj.optString("category", ""),
                    tier = skillObj.optString("tier", "free"),
                    installed = true
                )
            } else {
                CatalogSkill(
                    id = json.optString("skill_id", ""),
                    name = "",
                    description = "",
                    category = "",
                    tier = "free",
                    installed = true
                )
            }
            return InstallOutcome.Ok(json.optBoolean("deduped", false), skill)
        }
        return when (json.optString("error", "")) {
            "skill_tier_required" -> InstallOutcome.TierRequired(
                requiredTier = json.optString("required_tier", ""),
                planTier = json.optString("plan_tier", "")
            )
            "skills_install_cap" -> InstallOutcome.CapReached(
                planTier = json.optString("plan_tier", ""),
                installed = json.optInt("installed", 0),
                cap = json.optInt("cap", 0)
            )
            else -> InstallOutcome.Err(mapError(json))
        }
    }

    // ---- Signing (same canonical string as CronJobClient/MemoryClient) ----

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

    private suspend fun getSigned(context: Context, path: String, payloadRaw: String): JSONObject? {
        val headers = signedHeaders(context, payloadRaw) ?: return null
        val request = Request.Builder()
            .url("${PairingManager.HUB_URL}$path")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .get()
            .build()
        return execute(request)
    }

    private suspend fun postSignedRaw(
        context: Context,
        path: String,
        body: JSONObject,
        payloadRaw: String
    ): JSONObject? {
        val headers = signedHeaders(context, payloadRaw) ?: return null
        val request = Request.Builder()
            .url("${PairingManager.HUB_URL}$path")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
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

    // ---- Tenant id (install needs it in the body; the hub 400s on empty) ----

    /**
     * The tenant id for the install body. Stored value first
     * (JarvisPreferences.pairingTenantId, saved at pairing from
     * 1.21.0 on). Otherwise one idempotent /api/pairing/auto call
     * (re-pairing an already-paired device upserts — hub comment)
     * with the stored licence key + device id; on success the
     * tenant id is stored so later opens need no call. Returns null
     * when the device is not paired or the licence key is missing.
     * Runs on Dispatchers.IO (autoPairing is a blocking call).
     */
    suspend fun ensureTenantId(context: Context): String? = withContext(Dispatchers.IO) {
        val prefs = JarvisPreferences(context)
        val stored = prefs.pairingTenantId
        if (stored.isNotBlank()) return@withContext stored
        val deviceId = runCatching { PairingManager.deviceId(context) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return@withContext null
        val licenceKey = runCatching { LicenceKeyStore.get(context) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return@withContext null
        val result = runCatching { PairingManager.autoPairing(context, deviceId, licenceKey) }
            .getOrNull() ?: return@withContext null
        if (result is PairingManager.AutoResult.Ok && result.tenantId.isNotBlank()) {
            prefs.pairingTenantId = result.tenantId
            return@withContext result.tenantId
        }
        null
    }

    // ---- Hub calls ----

    /** List the catalog (tenant resolved server-side from the device). */
    suspend fun listCatalog(context: Context): CatalogOutcome {
        val json = getSigned(context, "/api/skills/catalog?tenant_id=", catalogPayloadRaw(""))
            ?: return CatalogOutcome.Err("Hub se jawab nahi mila — pairing ya network check karo.")
        if (json.optBoolean("ok", false)) return CatalogOutcome.Ok(parseCatalogJson(json))
        return CatalogOutcome.Err(mapError(json))
    }

    /** Install one catalog skill. Tier/cap 403s are honest outcomes, not errors. */
    suspend fun installSkill(context: Context, skillId: String): InstallOutcome {
        val tenantId = ensureTenantId(context)
            ?: return InstallOutcome.Err("Tenant nahi mila — pairing check karo.")
        val body = JSONObject()
            .put("tenant_id", tenantId)
            .put("skill_id", skillId)
        val json = postSignedRaw(
            context,
            "/api/skills/catalog/install",
            body,
            installPayloadRaw(tenantId, skillId)
        ) ?: return InstallOutcome.Err("Hub se jawab nahi mila — pairing ya network check karo.")
        return parseInstallJson(json)
    }

    private fun mapError(json: JSONObject): String {
        val error = json.optString("error", "")
        val detail = json.optString("detail", "")
        val base = when (error) {
            "device_not_paired" -> "Device pair nahi hai — pehle Pairing se pair karo."
            "tenant_not_found" -> "Tenant nahi mila — pairing check karo."
            "skill_not_found" -> "Skill nahi mili — list refresh karo."
            "unauthorized" -> "Hub ne mana kar diya — pairing check karo."
            "" -> "Hub se jawab nahi mila — pairing ya network check karo."
            else -> "Fail: $error"
        }
        return if (detail.isNotBlank() && base.startsWith("Fail:")) "$base — ${detail.take(120)}" else base
    }
}
