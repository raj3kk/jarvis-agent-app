package com.jarvis.autonomous.data

import com.jarvis.autonomous.agent.CapabilityClassifier
import com.jarvis.autonomous.agent.ResearchCandidate
import com.jarvis.autonomous.agent.ResearchOutcome
import com.jarvis.autonomous.agent.ResearchReport
import com.jarvis.autonomous.agent.SelfExtensionBudgets
import com.jarvis.autonomous.agent.SelfExtensionJson
import com.jarvis.autonomous.agent.SkillKind
import com.jarvis.autonomous.agent.SkillStep
import java.io.File

/**
 * Input to [SkillStorePort.upsertSelfExtendedSkill] — plain data
 * (no LearnedRoutine dependency) so the installer port unit-tests
 * on a plain JVM with an in-memory fake.
 */
data class SelfExtendedSkillInput(
    val id: String,
    val trigger: String,
    val steps: List<SkillStep>,
    val sources: List<String>,
    val licence: String,
    val costUsd: String,
    val addedAtMs: Long,
    val researchSignature: String
)

/**
 * Minimal store seam the installer needs (production passes the
 * adapter in SelfExtensionInstaller.kt, which delegates 1:1 to
 * LearnedRoutineStore).
 */
interface SkillStorePort {
    fun upsertSelfExtendedSkill(input: SelfExtendedSkillInput): String
    fun markSkillVerified(id: String, verification: String): Boolean
    fun setSkillEnabled(id: String, enabled: Boolean)
    fun isSkillEnabled(id: String): Boolean
    fun deleteSkill(id: String)
    fun loadSkillManifestIds(): List<String>
}

/**
 * Self-extension on-device store (DESIGN §5.4).
 *
 * File-backed under `filesDir/device-memory/self-extension/`:
 * `blocks.json`, `reports/<signature>.json`, `proposals.json`,
 * `counters.json`. Atomic tmp+rename writes following the
 * `DocumentMemory.kt` pattern; 5 MB total cap with oldest-first
 * eviction of REPORTS only — blocks are the anti-loop memory and
 * are NEVER evicted.
 *
 * This file has NO Android imports (the root dir is injected as a
 * [File], exactly like `FileDocumentStorage` takes a Context and
 * derives its dir) so the anti-loop / cap logic unit-tests on a
 * plain JVM. S8: records carry capability strings + candidate
 * metadata only — never keys, tokens, goal text, step targets/
 * inputs, or user content.
 */
data class BlockRecord(
    val signature: String,
    val capability: String,
    val outcome: String,
    val reason: String,
    val atMs: Long
)

data class ProposalRecord(
    val id: String,
    val capability: String,
    val signature: String,
    val licence: String,
    val costUsd: String,
    val sourceUrl: String,
    val kind: String,
    val status: String,
    val atMs: Long,
    val serverSkillId: String = "",
    val submitError: String = ""
)

sealed interface AttemptGate {
    data object Allowed : AttemptGate
    data class Denied(val reason: String, val userLine: String) : AttemptGate
}

class SelfExtensionStore(private val rootDir: File) {

    private val blocksFile: File get() = File(rootDir, "blocks.json")
    private val proposalsFile: File get() = File(rootDir, "proposals.json")
    private val countersFile: File get() = File(rootDir, "counters.json")
    private val reportsDir: File get() = File(rootDir, "reports")

    init {
        rootDir.mkdirs()
        reportsDir.mkdirs()
    }

    // ---- Anti-loop gates (§2.5, E8) ----

    /**
     * Read-then-check in ONE function; no caller-side override.
     * Denies when: the same signature is inside its 24 h cooldown
     * (after a GIVE_UP / failed verification / rejection), or the
     * rolling-24 h research count already hit 3/day. [alreadyAttempted]
     * is the in-memory per-run guard (second trigger in the same
     * task → denied without any network call).
     */
    fun canAttempt(signature: String, nowMs: Long, alreadyAttempted: Boolean = false): AttemptGate {
        if (alreadyAttempted) {
            return AttemptGate.Denied(
                "already_attempted_this_run",
                "Is kaam ke liye ek baar koshish kar chuka hoon — dobara research nahi karunga."
            )
        }
        val blocks = loadBlocks()
        val last = blocks.filter { it.signature == signature }.maxByOrNull { it.atMs }
        if (last != null && nowMs - last.atMs < SelfExtensionBudgets.COOLDOWN_MS) {
            return AttemptGate.Denied(
                "cooldown",
                "Is kaam ko abhi dobara try nahi kar sakta — kal phir koshish karunga."
            )
        }
        val runs24h = loadResearchRunMs().count { nowMs - it < SelfExtensionBudgets.COOLDOWN_MS }
        if (runs24h >= SelfExtensionBudgets.RESEARCH_PER_DAY) {
            return AttemptGate.Denied(
                "daily_cap",
                "Aaj ka research limit khatam ho gaya (3 din me) — kal phir koshish karunga."
            )
        }
        return AttemptGate.Allowed
    }

    /** A cached report (≤ 7 days) reuses the report: no new LLM calls (§3.5). */
    fun cachedReport(signature: String, nowMs: Long): String? {
        val file = File(reportsDir, "$signature.json")
        if (!file.isFile) return null
        if (nowMs - file.lastModified() > SelfExtensionBudgets.REPORT_CACHE_MS) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun saveReport(signature: String, reportJson: String): Boolean {
        if (!isSafeSignature(signature)) return false
        enforceCapBeforeWrite(reportJson.length.toLong())
        return writeAtomic(File(reportsDir, "$signature.json"), reportJson)
    }

    fun recordBlock(record: BlockRecord): Boolean {
        val blocks = loadBlocks().toMutableList()
        blocks.add(record)
        return writeAtomic(blocksFile, blocksToJson(blocks))
    }

    fun loadBlocks(): List<BlockRecord> {
        val raw = runCatching { blocksFile.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val parsed = SelfExtensionJson.parse(raw) ?: return emptyList()
        val list = SelfExtensionJson.asList(parsed) ?: return emptyList()
        val out = ArrayList<BlockRecord>()
        for (entry in list) {
            val obj = SelfExtensionJson.asObject(entry) ?: continue
            val signature = SelfExtensionJson.asString(obj["signature"]) ?: continue
            val capability = SelfExtensionJson.asString(obj["capability"]) ?: continue
            val outcome = SelfExtensionJson.asString(obj["outcome"]) ?: continue
            val reason = SelfExtensionJson.asString(obj["reason"]) ?: ""
            val atMs = SelfExtensionJson.asLong(obj["atMs"]) ?: continue
            out.add(BlockRecord(signature, capability, outcome, reason, atMs))
        }
        return out
    }

    fun recordResearchRun(nowMs: Long): Boolean {
        val runs = loadResearchRunMs().toMutableList()
        runs.add(nowMs)
        // Keep only the last 30 timestamps; the 24 h window is what matters.
        val kept = runs.takeLast(30)
        return writeAtomic(countersFile, countersToJson(kept))
    }

    fun loadResearchRunMs(): List<Long> {
        val raw = runCatching { countersFile.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val parsed = SelfExtensionJson.parse(raw) ?: return emptyList()
        val obj = SelfExtensionJson.asObject(parsed) ?: return emptyList()
        val list = SelfExtensionJson.asList(obj["researchRuns"]) ?: return emptyList()
        return list.mapNotNull { SelfExtensionJson.asLong(it) }
    }

    fun saveProposal(record: ProposalRecord): Boolean {
        val all = loadProposals().filterNot { it.id == record.id }.toMutableList()
        all.add(record)
        return writeAtomic(proposalsFile, proposalsToJson(all))
    }

    fun updateProposalStatus(id: String, status: String, serverSkillId: String = "", submitError: String = ""): Boolean {
        val all = loadProposals().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val old = all[idx]
        all[idx] = old.copy(
            status = status,
            serverSkillId = serverSkillId.ifBlank { old.serverSkillId },
            submitError = submitError
        )
        return writeAtomic(proposalsFile, proposalsToJson(all))
    }

    fun loadProposals(): List<ProposalRecord> {
        val raw = runCatching { proposalsFile.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val parsed = SelfExtensionJson.parse(raw) ?: return emptyList()
        val list = SelfExtensionJson.asList(parsed) ?: return emptyList()
        val out = ArrayList<ProposalRecord>()
        for (entry in list) {
            val obj = SelfExtensionJson.asObject(entry) ?: continue
            val id = SelfExtensionJson.asString(obj["id"]) ?: continue
            val capability = SelfExtensionJson.asString(obj["capability"]) ?: continue
            val signature = SelfExtensionJson.asString(obj["signature"]) ?: continue
            val licence = SelfExtensionJson.asString(obj["licence"]) ?: "UNKNOWN"
            val costUsd = SelfExtensionJson.asString(obj["costUsd"]) ?: "UNKNOWN"
            val sourceUrl = SelfExtensionJson.asString(obj["sourceUrl"]) ?: ""
            val kind = SelfExtensionJson.asString(obj["kind"]) ?: "CODE"
            val status = SelfExtensionJson.asString(obj["status"]) ?: "quarantined"
            val atMs = SelfExtensionJson.asLong(obj["atMs"]) ?: continue
            out.add(
                ProposalRecord(
                    id = id, capability = capability, signature = signature,
                    licence = licence, costUsd = costUsd, sourceUrl = sourceUrl,
                    kind = kind, status = status, atMs = atMs,
                    serverSkillId = SelfExtensionJson.asString(obj["serverSkillId"]) ?: "",
                    submitError = SelfExtensionJson.asString(obj["submitError"]) ?: ""
                )
            )
        }
        return out
    }

    /** Total bytes of the whole self-extension dir (cap: 5 MB, S7). */
    fun totalBytes(): Long {
        var total = 0L
        fun walk(file: File) {
            if (file.isFile) total += file.length()
            else file.listFiles()?.forEach { walk(it) }
        }
        if (rootDir.isDirectory) walk(rootDir)
        return total
    }

    /**
     * Oldest-first eviction of REPORTS (never blocks) until the new
     * write fits under the cap. Returns false when even an empty
     * reports dir cannot fit the write (caller must refuse).
     */
    fun enforceCapBeforeWrite(incomingBytes: Long): Boolean {
        var guard = 0
        while (totalBytes() + incomingBytes > SelfExtensionBudgets.STORE_CAP_BYTES && guard++ < 100) {
            val oldest = reportsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") }
                ?.minByOrNull { it.lastModified() } ?: return false
            if (!oldest.delete()) return false
        }
        return totalBytes() + incomingBytes <= SelfExtensionBudgets.STORE_CAP_BYTES
    }

    // ---- JSON (hand-built via SelfExtensionJson; no org.json here) ----

    fun blocksToJson(blocks: List<BlockRecord>): String = blocks.joinToString(",", "[", "]") { b ->
        "{\"signature\":${SelfExtensionJson.quote(b.signature)}," +
            "\"capability\":${SelfExtensionJson.quote(b.capability)}," +
            "\"outcome\":${SelfExtensionJson.quote(b.outcome)}," +
            "\"reason\":${SelfExtensionJson.quote(b.reason)}," +
            "\"atMs\":${b.atMs}}"
    }

    private fun countersToJson(runs: List<Long>): String =
        "{\"researchRuns\":[${runs.joinToString(",")}]}"

    fun proposalsToJson(proposals: List<ProposalRecord>): String =
        proposals.joinToString(",", "[", "]") { p ->
            "{\"id\":${SelfExtensionJson.quote(p.id)}," +
                "\"capability\":${SelfExtensionJson.quote(p.capability)}," +
                "\"signature\":${SelfExtensionJson.quote(p.signature)}," +
                "\"licence\":${SelfExtensionJson.quote(p.licence)}," +
                "\"costUsd\":${SelfExtensionJson.quote(p.costUsd)}," +
                "\"sourceUrl\":${SelfExtensionJson.quote(p.sourceUrl)}," +
                "\"kind\":${SelfExtensionJson.quote(p.kind)}," +
                "\"status\":${SelfExtensionJson.quote(p.status)}," +
                "\"atMs\":${p.atMs}," +
                "\"serverSkillId\":${SelfExtensionJson.quote(p.serverSkillId)}," +
                "\"submitError\":${SelfExtensionJson.quote(p.submitError)}}"
        }

    /** Report JSON for a research run (capability + candidate metadata only, S8). */
    fun reportToJson(
        capability: String,
        signature: String,
        researchedAtMs: Long,
        llmCalls: Int,
        fetches: Int,
        durationMs: Long,
        outcome: String,
        failureReason: String,
        candidates: List<com.jarvis.autonomous.agent.ResearchCandidate>
    ): String {
        val candidatesJson = candidates.joinToString(",", "[", "]") { c ->
            "{\"name\":${SelfExtensionJson.quote(c.name)}," +
                "\"sourceUrl\":${SelfExtensionJson.quote(c.sourceUrl)}," +
                "\"licence\":${SelfExtensionJson.quote(c.licence)}," +
                "\"licenceUrl\":${c.licenceUrl?.let { SelfExtensionJson.quote(it) } ?: "null"}," +
                "\"costUsd\":${SelfExtensionJson.quote(c.costUsd)}," +
                "\"needsCard\":${c.needsCard},\"needsAccount\":${c.needsAccount}," +
                "\"needsNetwork\":${c.needsNetwork}," +
                "\"lastUpdated\":${c.lastUpdated?.let { SelfExtensionJson.quote(it) } ?: "null"}," +
                "\"sizeBytes\":${c.sizeBytes?.toString() ?: "null"}," +
                "\"kind\":\"${c.kind}\"," +
                "\"evidence\":${SelfExtensionJson.stringListJson(c.evidence.take(5))}," +
                "\"stale\":${c.stale},\"signalsComplete\":${c.signalsComplete}," +
                "\"hostRequirements\":${SelfExtensionJson.stringListJson(c.hostRequirements.take(10))}," +
                // 1.11.0-PRO cache fix (coordinator pre-push review):
                // a cached report MUST carry the candidate's suggested
                // steps — without them a cache hit yields empty step
                // lists, InstallGate refuses `no_steps`, and the loop
                // gives up instead of installing for 7 days. Steps
                // are the LLM-proposed procedure only (no goal text
                // ever reaches research, S8 holds by construction);
                // the server submitter still strips them (§5.4).
                "\"suggestedSteps\":${stepsJson(c.suggestedSteps)}}"
        }
        return "{\"capability\":${SelfExtensionJson.quote(capability)}," +
            "\"signature\":${SelfExtensionJson.quote(signature)}," +
            "\"researchedAtMs\":$researchedAtMs,\"llmCalls\":$llmCalls," +
            "\"fetches\":$fetches,\"durationMs\":$durationMs," +
            "\"outcome\":${SelfExtensionJson.quote(outcome)}," +
            "\"failureReason\":${SelfExtensionJson.quote(failureReason)}," +
            "\"candidates\":$candidatesJson}"
    }

    /** Serialise candidate steps (S7 cap: ≤ 20 per skill). */
    private fun stepsJson(steps: List<SkillStep>): String {
        val capped = steps.take(SelfExtensionBudgets.MAX_STEPS_PER_SKILL)
        return capped.joinToString(",", "[", "]") { s ->
            "{\"action\":${SelfExtensionJson.quote(s.action)}," +
                "\"target\":${SelfExtensionJson.quote(s.target)}," +
                "\"input\":${s.input?.let { SelfExtensionJson.quote(it) } ?: "null"}}"
        }
    }

    /**
     * Parse a cached report JSON back into a [ResearchReport].
     * Inverse of [reportToJson]: every field it writes is restored
     * (incl. `suggestedSteps` — a cache hit must be installable,
     * see the note on [reportToJson]). Unknown/missing optional
     * fields fall back to the data-class defaults. Returns null on
     * malformed JSON or a missing `candidates` array.
     */
    fun parseReport(json: String, capability: String, signature: String): ResearchReport? {
        val parsed = SelfExtensionJson.parse(json) ?: return null
        val obj = SelfExtensionJson.asObject(parsed) ?: return null
        val candidatesRaw = SelfExtensionJson.asList(obj["candidates"]) ?: return null
        val candidates = ArrayList<ResearchCandidate>()
        for (entry in candidatesRaw) {
            val c = SelfExtensionJson.asObject(entry) ?: continue
            val name = SelfExtensionJson.asString(c["name"]) ?: continue
            val sourceUrl = SelfExtensionJson.asString(c["sourceUrl"]) ?: continue
            val steps = SelfExtensionJson.asList(c["suggestedSteps"])?.mapNotNull { s ->
                val so = SelfExtensionJson.asObject(s) ?: return@mapNotNull null
                val action = SelfExtensionJson.asString(so["action"]) ?: return@mapNotNull null
                SkillStep(
                    action = action,
                    target = SelfExtensionJson.asString(so["target"]) ?: "",
                    input = SelfExtensionJson.asString(so["input"])
                )
            } ?: emptyList()
            candidates.add(
                ResearchCandidate(
                    name = name,
                    sourceUrl = sourceUrl,
                    licence = SelfExtensionJson.asString(c["licence"]) ?: "UNKNOWN",
                    licenceUrl = SelfExtensionJson.asString(c["licenceUrl"]),
                    costUsd = SelfExtensionJson.asString(c["costUsd"]) ?: "UNKNOWN",
                    needsCard = SelfExtensionJson.asBool(c["needsCard"]) ?: false,
                    needsAccount = SelfExtensionJson.asBool(c["needsAccount"]) ?: false,
                    needsNetwork = SelfExtensionJson.asBool(c["needsNetwork"]) ?: false,
                    lastUpdated = SelfExtensionJson.asString(c["lastUpdated"]),
                    sizeBytes = SelfExtensionJson.asLong(c["sizeBytes"]),
                    hostRequirements = SelfExtensionJson.asList(c["hostRequirements"])
                        ?.mapNotNull { SelfExtensionJson.asString(it) } ?: emptyList(),
                    kind = if (SelfExtensionJson.asString(c["kind"]) == "CODE") SkillKind.CODE else SkillKind.STEP,
                    evidence = SelfExtensionJson.asList(c["evidence"])
                        ?.mapNotNull { SelfExtensionJson.asString(it) } ?: emptyList(),
                    stale = SelfExtensionJson.asBool(c["stale"]) ?: false,
                    suggestedSteps = steps,
                    signalsComplete = SelfExtensionJson.asBool(c["signalsComplete"]) ?: true
                )
            )
        }
        val outcome = try {
            ResearchOutcome.valueOf(SelfExtensionJson.asString(obj["outcome"]) ?: "NONE_FOUND")
        } catch (e: IllegalArgumentException) {
            ResearchOutcome.NONE_FOUND
        }
        return ResearchReport(
            capability = capability,
            signature = signature,
            candidates = candidates,
            researchedAtMs = SelfExtensionJson.asLong(obj["researchedAtMs"]) ?: 0L,
            llmCalls = (SelfExtensionJson.asLong(obj["llmCalls"]) ?: 0L).toInt(),
            fetches = (SelfExtensionJson.asLong(obj["fetches"]) ?: 0L).toInt(),
            durationMs = SelfExtensionJson.asLong(obj["durationMs"]) ?: 0L,
            outcome = outcome,
            failureReason = SelfExtensionJson.asString(obj["failureReason"]) ?: ""
        )
    }

    private fun isSafeSignature(signature: String): Boolean =
        signature.matches(Regex("[0-9a-f]{64}"))

    /** Atomic tmp+rename (DocumentMemory pattern). */
    private fun writeAtomic(file: File, content: String): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    companion object {
        /** Licence strings a stored self-added manifest may carry (§5.3). */
        val STORABLE_LICENCES: Set<String> =
            CapabilityClassifier.LICENSE_ALLOWLIST + CapabilityClassifier.LICENSE_COPYLEFT + "UNKNOWN"
    }
}
