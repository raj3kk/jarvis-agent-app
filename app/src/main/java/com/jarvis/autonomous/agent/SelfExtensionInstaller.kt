package com.jarvis.autonomous.agent

import android.content.Context
import android.util.Base64
import com.jarvis.autonomous.data.DocConfirmClient
import com.jarvis.autonomous.data.LearnedRoutine
import com.jarvis.autonomous.data.LearnedRoutineStore
import com.jarvis.autonomous.data.LearnedStep
import com.jarvis.autonomous.data.PairingManager
import com.jarvis.autonomous.data.ProposalRecord
import com.jarvis.autonomous.data.SelfExtendedSkillInput
import com.jarvis.autonomous.data.SelfExtensionStore
import com.jarvis.autonomous.data.SkillStorePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Production adapter: [LearnedRoutineStore] behind [SkillStorePort]. */
class LearnedRoutineStorePort(private val store: LearnedRoutineStore) : SkillStorePort {
    override fun upsertSelfExtendedSkill(input: SelfExtendedSkillInput): String {
        val routine = LearnedRoutine(
            id = input.id,
            trigger = input.trigger,
            steps = input.steps.map { LearnedStep(action = it.action, target = it.target, input = it.input) },
            createdAtMs = input.addedAtMs
        )
        val provenance = com.jarvis.autonomous.data.SkillProvenance(
            researchedBy = "self-extension",
            sources = input.sources,
            licence = input.licence,
            costUsd = input.costUsd,
            addedAtMs = input.addedAtMs,
            autoAdded = true,
            researchSignature = input.researchSignature,
            verification = ""
        )
        return store.upsertSelfExtendedSkill(routine, provenance).id
    }

    override fun markSkillVerified(id: String, verification: String): Boolean =
        store.markSkillVerified(id, verification)

    override fun setSkillEnabled(id: String, enabled: Boolean) = store.setSkillEnabled(id, enabled)
    override fun isSkillEnabled(id: String): Boolean = store.isSkillEnabled(id)
    override fun deleteSkill(id: String) = store.deleteSkill(id)
    override fun loadSkillManifestIds(): List<String> =
        store.loadSkillManifests().map { it.id }
}

/**
 * Self-extension stage 3a/3b — INTEGRATE (DESIGN §5, §6, E2).
 *
 * The installer accepts ONLY a classifier-produced AUTO for STEP
 * installs: [install] takes a [Classification] value (never a
 * boolean, never an LLM verdict) and re-checks budgets and
 * KNOWN_ACTIONS membership itself via [InstallGate] (defence in
 * depth — it does not trust the classifier's step check). Any other
 * input is refused BEFORE any write.
 *
 * STEP install: the skill is created with `enabled=false` and is
 * flipped to `true` ONLY by the verifier (§7) after a passed real
 * invocation — the installer never enables. Rollback (§5.5) deletes
 * manifest + routine in the same call and re-reads to confirm absence.
 *
 * CODE proposals (§6): nothing is installed, nothing is enabled, no
 * code is downloaded or stored on-device (metadata + URLs only).
 * The proposal is filed locally as `quarantined` and submitted
 * through the existing POST /api/skills/submit (3d, always
 * quarantined server-side). The submission is TEMPLATE-ONLY: step
 * action names only, a generic trigger, no targets/inputs, no goal
 * text, no user content — the server strip-check rejects user
 * content with 400 `contains_user_content`. The device-signed POST
 * follows the `DocConfirmClient.postSigned` / `signedHeaders`
 * pattern (device-signed via PairingManager); no app code called
 * /api/skills/submit before this file.
 *
 * Ed25519 on-device verify is DEFERRED (parent decision §12-4):
 * proposals stay `approved_awaiting_release` — CODE skills enter
 * the app only through the next APK build, never downloaded-and-run.
 */
class SelfExtensionInstaller(
    private val context: Context?,
    private val skillStore: SkillStorePort,
    private val store: SelfExtensionStore
) {

    /** Production constructor: wraps the real routine store. */
    constructor(
        context: Context,
        routineStore: LearnedRoutineStore,
        store: SelfExtensionStore
    ) : this(context, LearnedRoutineStorePort(routineStore), store)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    sealed interface InstallResult {
        data class Installed(val skillId: String) : InstallResult
        data class Refused(val reason: String) : InstallResult
        data class Failed(val reason: String) : InstallResult
    }

    sealed interface ProposalResult {
        data class Filed(
            val proposalId: String,
            val serverSkillId: String,
            val submitOk: Boolean,
            val submitError: String = ""
        ) : ProposalResult
        data class Failed(val reason: String) : ProposalResult
    }

    /**
     * Installs a STEP skill from a classifier verdict. [trigger] is
     * the GENERIC capability string (never user goal text). The
     * skill starts disabled + unverified; the verifier enables it.
     */
    fun install(
        classification: Classification,
        candidate: ResearchCandidate,
        steps: List<SkillStep>,
        capability: String,
        signature: String
    ): InstallResult {
        val manifestBytes = estimateManifestBytes(steps, candidate)
        when (val gate = InstallGate.check(classification, steps, null, manifestBytes, store.totalBytes())) {
            is InstallGate.GateResult.Refused -> return InstallResult.Refused(gate.reason)
            is InstallGate.GateResult.Allowed -> Unit
        }
        // Defence in depth: the scanner runs unconditionally in the
        // installer path too — there is no flag to skip it (E4).
        val scanHit = SelfExtensionScanner.firstHit(steps, candidate.evidence.joinToString("\n"))
        if (scanHit != null) return InstallResult.Refused("scan_hit:$scanHit")
        val skillId = "selfext-${signature.take(12)}-${System.currentTimeMillis()}"
        val input = SelfExtendedSkillInput(
            id = skillId,
            trigger = capability,
            steps = steps,
            sources = candidate.sourcesForProvenance(),
            licence = candidate.licence,
            costUsd = candidate.costUsd,
            addedAtMs = System.currentTimeMillis(),
            researchSignature = signature
        )
        return try {
            val installedId = skillStore.upsertSelfExtendedSkill(input)
            // The 50-skill prune runs inside the upsert: a fresh
            // disabled skill can itself be the prune victim at the
            // cap (S7). Confirm it survived before claiming success.
            if (skillStore.loadSkillManifestIds().none { it == installedId }) {
                InstallResult.Failed("install_error:pruned_at_cap")
            } else {
                InstallResult.Installed(installedId)
            }
        } catch (e: Exception) {
            // Install failed midway: same delete runs as cleanup,
            // then the store is re-read to confirm absence (§5.5).
            runCatching { skillStore.deleteSkill(skillId) }
            InstallResult.Failed("install_error:${e.javaClass.simpleName}")
        }
    }

    /** Marks a verified skill enabled + records its verification (§7). */
    fun markVerified(skillId: String, testId: String): Boolean {
        val marked = skillStore.markSkillVerified(skillId, "verified:$testId")
        if (!marked) return false
        skillStore.setSkillEnabled(skillId, true)
        return skillStore.isSkillEnabled(skillId)
    }

    /** Rollback (§5.5 / §7.3): delete manifest + routine, confirm absence. */
    fun rollback(skillId: String): Boolean {
        skillStore.deleteSkill(skillId)
        val gone = skillStore.loadSkillManifestIds().none { it == skillId }
        if (!gone) {
            runCatching {
                com.jarvis.autonomous.data.JarvisStateRepository.addDiagnostic(
                    "self-extension", "ROLLBACK_INCOMPLETE", "skillId=$skillId"
                )
            }
        }
        return gone
    }

    /**
     * Files a CODE proposal (§6 steps 1–2): local quarantined record
     * + template-only submit to /api/skills/submit. The local record
     * is written FIRST so a network failure never loses the proposal;
     * the submit is best-effort and its outcome is recorded on it.
     * Proposals stay `approved_awaiting_release` (Ed25519 verify
     * deferred) — never auto-installed.
     */
    suspend fun fileCodeProposal(
        candidate: ResearchCandidate,
        classification: Classification,
        capability: String,
        signature: String
    ): ProposalResult {
        val proposalId = "proposal-${signature.take(12)}-${System.currentTimeMillis()}"
        val record = ProposalRecord(
            id = proposalId,
            capability = capability,
            signature = signature,
            licence = candidate.licence,
            costUsd = candidate.costUsd,
            sourceUrl = candidate.sourceUrl,
            kind = classification.kind.name,
            status = "quarantined",
            atMs = System.currentTimeMillis()
        )
        if (!store.saveProposal(record)) return ProposalResult.Failed("proposal_store_failed")
        val (submitOk, serverSkillId, submitError) = submitTemplate(candidate, capability)
        store.updateProposalStatus(
            proposalId, "quarantined",
            serverSkillId = serverSkillId, submitError = submitError
        )
        return ProposalResult.Filed(proposalId, serverSkillId, submitOk, submitError)
    }

    /**
     * Template-only submit (§5.4/§6.2): manifest name/version/
     * trigger/steps[{action}] only. Trigger is the fixed generic
     * literal "self-extended capability" — the server cannot
     * strip-check trigger text (frozen schema needs a non-empty
     * trigger), so the app submitter MUST send a generic trigger;
     * this is that rule, in code. Steps carry action names only.
     */
    private suspend fun submitTemplate(
        candidate: ResearchCandidate,
        capability: String
    ): Triple<Boolean, String, String> {
        val stepsArr = JSONArray()
        val actions = candidate.suggestedSteps.map { it.action.uppercase() }
            .filter { it in CapabilityClassifier.KNOWN_ACTIONS }
            .distinct()
            .ifEmpty { listOf("WAIT") }
        for (action in actions) {
            stepsArr.put(JSONObject().put("action", action))
        }
        val researchCandidate = JSONObject()
            .put("name", candidate.name.take(120))
            .put("sourceUrl", candidate.sourceUrl)
            .put("licence", candidate.licence)
            .put("costUsd", candidate.costUsd)
            .put("needsCard", candidate.needsCard)
            .put("needsAccount", candidate.needsAccount)
            .put("needsNetwork", candidate.needsNetwork)
            .put("kind", "CODE")
        candidate.licenceUrl?.let { researchCandidate.put("licenceUrl", it) }
        candidate.lastUpdated?.let { researchCandidate.put("lastUpdated", it) }
        candidate.sizeBytes?.let { researchCandidate.put("sizeBytes", it) }
        val manifest = JSONObject()
            .put("name", "self-extended-$capability".take(120))
            .put("version", "1")
            .put("trigger", "self-extended capability")
            .put("steps", stepsArr)
            .put("origin", "self_extended")
            .put("code", "")
            .put("researchCandidate", researchCandidate)
        val body = JSONObject().put("manifest", manifest)
        val json = postSigned("/api/skills/submit", body)
            ?: return Triple(false, "", "hub_no_response")
        if (json.optBoolean("ok", false)) {
            return Triple(true, json.optString("skill_id", ""), "")
        }
        val error = json.optString("error", "submit_failed").take(80)
        return Triple(false, "", error)
    }

    /** Device-signed POST, following DocConfirmClient.postSigned. */
    private suspend fun postSigned(path: String, body: JSONObject): JSONObject? {
        val headers = signedHeaders(body) ?: return null
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

    private fun signedHeaders(body: JSONObject): Map<String, String>? {
        val ctx = context ?: return null
        val deviceId = runCatching { PairingManager.deviceId(ctx) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
        val ts = System.currentTimeMillis()
        val payloadRaw = DocConfirmClient.sortedJson(body)
        val canonical = "$deviceId\n$nonce\n$ts\n$payloadRaw"
        val sig = runCatching {
            Base64.encodeToString(
                PairingManager.sign(ctx, canonical.toByteArray(Charsets.UTF_8)),
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

    private fun estimateManifestBytes(steps: List<SkillStep>, candidate: ResearchCandidate): Long {
        var bytes = 512L
        for (step in steps) {
            bytes += step.action.length + step.target.length + (step.input?.length ?: 0) + 32
        }
        bytes += candidate.name.length + candidate.sourceUrl.length + candidate.licence.length
        return bytes
    }

    /** Provenance sources: ≤5 allowlisted URLs (§3.3) — evidence only. */
    private fun ResearchCandidate.sourcesForProvenance(): List<String> {
        val urls = ArrayList<String>()
        if (SelfExtensionResearcher.isAllowlistedUrl(sourceUrl)) urls.add(sourceUrl)
        licenceUrl?.takeIf { SelfExtensionResearcher.isAllowlistedUrl(it) }?.let { urls.add(it) }
        return urls.distinct().take(5)
    }
}
