package com.jarvis.autonomous.agent

/**
 * Talker/Executor v2 protocol additions — additive inside te/1, no
 * version bump (DESIGN.md: expansion/talker-executor-v2/DESIGN.md §2).
 *
 * Release 1.9.0-PRO (vc 45). Pure Kotlin — ZERO Android imports — so
 * this file unit-tests on a plain JVM, exactly like the v1 protocol.
 *
 * New message kinds (routed by the bus, never by version):
 * - `STEP_PROGRESS` (Executor → Talker, non-terminal): a step
 *   milestone while work runs. The Talker speaks only throttled
 *   milestones (see [TalkerProgressPolicy]) and never treats
 *   progress as a result — progress never satisfies the safety net
 *   and never resets the 15-min watchdog.
 * - `FIX_REQUEST` (Talker → Executor, non-terminal): re-run ONLY
 *   the steps that own the evidence refs the Talker re-read failed.
 *   Max [FIX_MAX_ATTEMPTS] attempts, each bounded by
 *   [FIX_TIMEOUT_MS] and by the command's remaining time.
 * - `FIX_RESULT` (Executor → Talker): answers a FIX_REQUEST with
 *   status FIXED | STILL_MISSING plus fresh evidence refs.
 *
 * Typed evidence ([EvidenceKind] / [EvidenceRef]): a DONE claim
 * carries machine-checkable refs in [ExecutorResult.evidenceJson]
 * (`{"refs":[{kind, ref, check, value}, …]}` as a JSON *string*, so
 * the existing parser handles it with zero changes). The Talker
 * re-reads each artifact itself; see [TalkerVerificationPolicy].
 */
object TalkerExecutorProgress {

    /** Fix attempts per command (DESIGN §4.6). */
    const val FIX_MAX_ATTEMPTS = 2

    /** One fix attempt is bounded by 5 minutes AND by the command's
     *  remaining time — the smaller wins (DESIGN §4.6, Q4: fix
     *  attempts count against the 15-min timeout). */
    const val FIX_TIMEOUT_MS = 300_000L

    /** Whole-verification bound (DESIGN §4.3): on timeout, unchecked
     *  refs are UNCHECKABLE and the verdict is UNVERIFIED. */
    const val VERIFICATION_TIMEOUT_MS = 60_000L

    /** A ref that comes back UNCHECKABLE is retried once after this delay. */
    const val VERIFICATION_RETRY_DELAY_MS = 5_000L

    /** Chat-only line while verification exceeds this long (DESIGN §4.3). */
    const val VERIFICATION_SLOW_CHAT_MS = 10_000L

    /** Max evidence refs per DONE (DESIGN §2.3). */
    const val MAX_EVIDENCE_REFS = 8
}

/** Closed set of re-checkable evidence kinds (DESIGN §2.3). */
enum class EvidenceKind(val wire: String) {
    FILE_LIST("FILE_LIST"),
    PREVIEW_HTTP("PREVIEW_HTTP"),
    GITHUB_COMMIT("GITHUB_COMMIT"),
    SUPABASE_TABLES("SUPABASE_TABLES"),
    VERCEL_DEPLOY("VERCEL_DEPLOY"),
    SCREEN_STATE("SCREEN_STATE"),
    MESSAGE_SENT("MESSAGE_SENT");

    companion object {
        fun fromWire(wire: String?): EvidenceKind? =
            entries.firstOrNull { it.wire == wire }
    }

    /** Ref id used in FIX_REQUEST missingRefs: "<kind>:<ref>", kind
     *  lowercased with `_` kept (e.g. `preview_http:preview:dukaan-site`).
     *  Matched by exact string equality, never parsed by splitting. */
    fun refId(ref: String): String = "${wire.lowercase()}:$ref"
}

/**
 * One typed evidence claim. `ref` is a pointer, not a claim;
 * `check` names what the Talker re-runs; `value` is the claimed
 * value the re-read must match (commit SHA, file list, URL, probe).
 */
data class EvidenceRef(
    val kind: EvidenceKind,
    val ref: String,
    val check: String,
    val value: String
) {
    /** The FIX_REQUEST id for this ref. */
    fun refId(): String = kind.refId(ref)

    fun toJsonMap(): String = with(TalkerExecutorProtocol) {
        "{\"kind\":\"${escapeJson(kind.wire)}\"," +
            "\"ref\":\"${escapeJson(ref)}\"," +
            "\"check\":\"${escapeJson(check)}\"," +
            "\"value\":\"${escapeJson(value)}\"}"
    }

    companion object {
        fun fromMap(map: Map<String, String>): EvidenceRef? {
            val kind = EvidenceKind.fromWire(map["kind"]) ?: return null
            val ref = map["ref"]?.takeIf { it.isNotBlank() } ?: return null
            val check = map["check"] ?: ""
            val value = map["value"] ?: ""
            return EvidenceRef(kind = kind, ref = ref, check = check, value = value)
        }
    }
}

/** Parse / serialise the `evidenceJson` string field (DESIGN §2.3). */
object EvidenceRefs {

    /** Priority when capping: artifact refs first (DESIGN §2.3). */
    private val PRIORITY = listOf(
        EvidenceKind.PREVIEW_HTTP,
        EvidenceKind.GITHUB_COMMIT,
        EvidenceKind.VERCEL_DEPLOY,
        EvidenceKind.FILE_LIST,
        EvidenceKind.SUPABASE_TABLES,
        EvidenceKind.SCREEN_STATE,
        EvidenceKind.MESSAGE_SENT
    )

    /** Serialise refs as `{"refs":[{...}, …]}`. */
    fun toJson(refs: List<EvidenceRef>): String {
        val capped = cap(refs)
        return "{\"refs\":[${capped.joinToString(",") { it.toJsonMap() }}]}"
    }

    /**
     * Parse an `evidenceJson` string. Fail-closed: malformed JSON,
     * a wrong shape, or a bad ref yields emptyList() — never a crash,
     * never a partial claim the Talker would half-trust. A DONE with
     * no parseable refs verifies as UNVERIFIED.
     */
    fun parse(evidenceJson: String): List<EvidenceRef> {
        if (evidenceJson.isBlank()) return emptyList()
        val map = TalkerExecutorProtocol.parseJsonObject(evidenceJson) ?: return emptyList()
        val raw = TalkerExecutorProtocol.asObjectList(map, "refs") ?: return emptyList()
        val out = ArrayList<EvidenceRef>(raw.size)
        for (entry in raw) {
            val ref = EvidenceRef.fromMap(entry) ?: return emptyList()
            out.add(ref)
        }
        return cap(out)
    }

    /**
     * Dedupe by kind+ref and cap at [TalkerExecutorProgress.MAX_EVIDENCE_REFS],
     * keeping the highest-priority refs (DESIGN §2.3).
     */
    fun cap(refs: List<EvidenceRef>): List<EvidenceRef> {
        val deduped = refs.distinctBy { it.kind to it.ref }
        if (deduped.size <= TalkerExecutorProgress.MAX_EVIDENCE_REFS) return deduped
        val sorted = deduped.sortedBy { PRIORITY.indexOf(it.kind).takeIf { idx -> idx >= 0 } ?: PRIORITY.size }
        return sorted.take(TalkerExecutorProgress.MAX_EVIDENCE_REFS)
    }
}

/**
 * One Executor step milestone (STEP_PROGRESS). `phase` is "START" or
 * "DONE" for plan steps (loops always send "DONE" for a verified
 * milestone step — a loop step has no meaningful start line). For
 * loops `stepTotal = 0`, `progressFraction = -1.0`, `etaSeconds = -1`
 * (an LLM step count cannot be predicted — inventing an ETA would be
 * a lie). `emittedAtMs` drives staleness: a line delivered more than
 * 120 s late is chat-only, never spoken.
 */
data class ExecutorProgress(
    val version: Int = TalkerExecutorProtocol.VERSION,
    val commandId: String,
    val stepIndex: Int,
    val stepTotal: Int,
    val stepLabel: String,
    val detail: String = "",
    val progressFraction: Double = -1.0,
    val etaSeconds: Long = -1L,
    val isMilestone: Boolean = true,
    val phase: String = "",
    /**
     * Next step's title (plans only; "" on the last step or for
     * loops). The Talker names it in the progress line ("aage:
     * Preview check") — loops never invent one.
     */
    val nextLabel: String = "",
    val emittedAtMs: Long = System.currentTimeMillis()
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (version != TalkerExecutorProtocol.VERSION) {
            errors.add("UNSUPPORTED_VERSION: $version (expected ${TalkerExecutorProtocol.VERSION})")
        }
        if (commandId.isBlank()) errors.add("MISSING_COMMAND_ID")
        if (stepIndex < 1) errors.add("BAD_STEP_INDEX: $stepIndex")
        if (stepTotal < 0) errors.add("BAD_STEP_TOTAL: $stepTotal")
        if (stepTotal > 0 && stepIndex > stepTotal) {
            errors.add("STEP_INDEX_EXCEEDS_TOTAL: $stepIndex > $stepTotal")
        }
        if (stepLabel.isBlank()) errors.add("MISSING_STEP_LABEL")
        if (progressFraction != -1.0 && (progressFraction < 0.0 || progressFraction > 1.0)) {
            errors.add("BAD_FRACTION: $progressFraction")
        }
        if (etaSeconds < -1L) errors.add("BAD_ETA: $etaSeconds")
        if (emittedAtMs <= 0) errors.add("BAD_EMITTED_AT")
        return errors
    }

    fun toJson(): String = with(TalkerExecutorProtocol) {
        buildString {
            append("{")
            append("\"version\":$version,")
            append("\"messageKind\":\"STEP_PROGRESS\",")
            append("\"commandId\":\"${escapeJson(commandId)}\",")
            append("\"stepIndex\":$stepIndex,")
            append("\"stepTotal\":$stepTotal,")
            append("\"stepLabel\":\"${escapeJson(stepLabel.take(80))}\",")
            append("\"detail\":\"${escapeJson(detail.take(200))}\",")
            // progressFraction is a Double, but the hand-rolled
            // parser reads Long numbers only — so it is carried as
            // a quoted string ("0.4" / "-1.0"); fromJson accepts
            // both the string form and a bare Long (0 / 1).
            append("\"progressFraction\":\"$progressFraction\",")
            append("\"etaSeconds\":$etaSeconds,")
            append("\"isMilestone\":$isMilestone,")
            append("\"phase\":\"${escapeJson(phase)}\",")
            append("\"nextLabel\":\"${escapeJson(nextLabel.take(80))}\",")
            append("\"emittedAtMs\":$emittedAtMs")
            append("}")
        }
    }

    companion object {
        fun fromJson(json: String): ExecutorProgress? {
            val map = TalkerExecutorProtocol.parseJsonObject(json) ?: return null
            if (TalkerExecutorProtocol.asString(map, "messageKind") != "STEP_PROGRESS") return null
            val version = TalkerExecutorProtocol.asLong(map, "version")?.toInt() ?: return null
            val commandId = TalkerExecutorProtocol.asString(map, "commandId") ?: return null
            val stepIndex = TalkerExecutorProtocol.asLong(map, "stepIndex")?.toInt() ?: return null
            val stepTotal = TalkerExecutorProtocol.asLong(map, "stepTotal")?.toInt() ?: return null
            val stepLabel = TalkerExecutorProtocol.asString(map, "stepLabel") ?: return null
            val detail = TalkerExecutorProtocol.asString(map, "detail") ?: ""
            // progressFraction is written as a JSON number with a
            // fractional part, which the hand-rolled parser (Long
            // only) rejects — so it is carried as a string field
            // when fractional. Accept both spellings: a string
            // ("0.4") or a Long (0 / 1). Unknown → -1 (unknown).
            val fractionRaw = map["progressFraction"]
            val progressFraction = when (fractionRaw) {
                is String -> fractionRaw.toDoubleOrNull() ?: -1.0
                is Long -> fractionRaw.toDouble()
                else -> -1.0
            }
            val etaSeconds = TalkerExecutorProtocol.asLong(map, "etaSeconds") ?: -1L
            val isMilestone = TalkerExecutorProtocol.asBoolean(map, "isMilestone") ?: true
            val phase = TalkerExecutorProtocol.asString(map, "phase") ?: ""
            val nextLabel = TalkerExecutorProtocol.asString(map, "nextLabel") ?: ""
            val emittedAtMs = TalkerExecutorProtocol.asLong(map, "emittedAtMs") ?: return null
            return ExecutorProgress(
                version = version, commandId = commandId, stepIndex = stepIndex,
                stepTotal = stepTotal, stepLabel = stepLabel, detail = detail,
                progressFraction = progressFraction, etaSeconds = etaSeconds,
                isMilestone = isMilestone, phase = phase, nextLabel = nextLabel,
                emittedAtMs = emittedAtMs
            )
        }
    }
}

/**
 * Talker → Executor fix request (FIX_REQUEST). The Executor fixes
 * ONLY [missingRefs] — it does not re-run the whole task.
 */
data class ExecutorFixRequest(
    val version: Int = TalkerExecutorProtocol.VERSION,
    val commandId: String,
    val attempt: Int,
    val maxAttempts: Int = TalkerExecutorProgress.FIX_MAX_ATTEMPTS,
    val missingRefs: List<String>,
    val reason: String = "",
    val issuedAtMs: Long = System.currentTimeMillis()
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (version != TalkerExecutorProtocol.VERSION) {
            errors.add("UNSUPPORTED_VERSION: $version (expected ${TalkerExecutorProtocol.VERSION})")
        }
        if (commandId.isBlank()) errors.add("MISSING_COMMAND_ID")
        if (attempt < 1) errors.add("BAD_ATTEMPT: $attempt")
        if (attempt > maxAttempts) errors.add("ATTEMPT_EXCEEDS_MAX: $attempt > $maxAttempts")
        if (missingRefs.isEmpty()) errors.add("MISSING_REFS_EMPTY")
        if (issuedAtMs <= 0) errors.add("BAD_ISSUED_AT")
        return errors
    }

    fun toJson(): String = with(TalkerExecutorProtocol) {
        buildString {
            append("{")
            append("\"version\":$version,")
            append("\"messageKind\":\"FIX_REQUEST\",")
            append("\"commandId\":\"${escapeJson(commandId)}\",")
            append("\"attempt\":$attempt,")
            append("\"maxAttempts\":$maxAttempts,")
            append("\"missingRefs\":${stringListToJson(missingRefs)},")
            append("\"reason\":\"${escapeJson(reason.take(200))}\",")
            append("\"issuedAtMs\":$issuedAtMs")
            append("}")
        }
    }

    companion object {
        fun fromJson(json: String): ExecutorFixRequest? {
            val map = TalkerExecutorProtocol.parseJsonObject(json) ?: return null
            if (TalkerExecutorProtocol.asString(map, "messageKind") != "FIX_REQUEST") return null
            val version = TalkerExecutorProtocol.asLong(map, "version")?.toInt() ?: return null
            val commandId = TalkerExecutorProtocol.asString(map, "commandId") ?: return null
            val attempt = TalkerExecutorProtocol.asLong(map, "attempt")?.toInt() ?: return null
            val maxAttempts = TalkerExecutorProtocol.asLong(map, "maxAttempts")?.toInt()
                ?: TalkerExecutorProgress.FIX_MAX_ATTEMPTS
            val missingRefs = TalkerExecutorProtocol.asStringList(map, "missingRefs") ?: return null
            val reason = TalkerExecutorProtocol.asString(map, "reason") ?: ""
            val issuedAtMs = TalkerExecutorProtocol.asLong(map, "issuedAtMs") ?: return null
            return ExecutorFixRequest(
                version = version, commandId = commandId, attempt = attempt,
                maxAttempts = maxAttempts, missingRefs = missingRefs,
                reason = reason, issuedAtMs = issuedAtMs
            )
        }
    }
}

/** FIX_RESULT status: only these two — a fix never claims a full DONE. */
enum class FixResultStatus(val wire: String) {
    FIXED("FIXED"),
    STILL_MISSING("STILL_MISSING");

    companion object {
        fun fromWire(wire: String?): FixResultStatus? =
            entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Executor → Talker answer to a FIX_REQUEST. A FIXED result carries
 * fresh refs in [freshEvidenceJson] (same format as
 * [ExecutorResult.evidenceJson]) and re-triggers verification; a
 * STILL_MISSING result lists the refs still missing.
 */
data class ExecutorFixResult(
    val version: Int = TalkerExecutorProtocol.VERSION,
    val commandId: String,
    val attempt: Int,
    val status: FixResultStatus,
    val missingRefs: List<String> = emptyList(),
    val freshEvidenceJson: String = "",
    val detail: String = "",
    val finishedAtMs: Long = System.currentTimeMillis()
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (version != TalkerExecutorProtocol.VERSION) {
            errors.add("UNSUPPORTED_VERSION: $version (expected ${TalkerExecutorProtocol.VERSION})")
        }
        if (commandId.isBlank()) errors.add("MISSING_COMMAND_ID")
        if (attempt < 1) errors.add("BAD_ATTEMPT: $attempt")
        if (finishedAtMs <= 0) errors.add("BAD_FINISHED_AT")
        return errors
    }

    fun toJson(): String = with(TalkerExecutorProtocol) {
        buildString {
            append("{")
            append("\"version\":$version,")
            append("\"messageKind\":\"FIX_RESULT\",")
            append("\"commandId\":\"${escapeJson(commandId)}\",")
            append("\"attempt\":$attempt,")
            append("\"status\":\"${status.wire}\",")
            append("\"missingRefs\":${stringListToJson(missingRefs)},")
            append("\"freshEvidenceJson\":\"${escapeJson(freshEvidenceJson)}\",")
            append("\"detail\":\"${escapeJson(detail.take(200))}\",")
            append("\"finishedAtMs\":$finishedAtMs")
            append("}")
        }
    }

    companion object {
        fun fromJson(json: String): ExecutorFixResult? {
            val map = TalkerExecutorProtocol.parseJsonObject(json) ?: return null
            if (TalkerExecutorProtocol.asString(map, "messageKind") != "FIX_RESULT") return null
            val version = TalkerExecutorProtocol.asLong(map, "version")?.toInt() ?: return null
            val commandId = TalkerExecutorProtocol.asString(map, "commandId") ?: return null
            val attempt = TalkerExecutorProtocol.asLong(map, "attempt")?.toInt() ?: return null
            val status = FixResultStatus.fromWire(
                TalkerExecutorProtocol.asString(map, "status")
            ) ?: return null
            val missingRefs = TalkerExecutorProtocol.asStringList(map, "missingRefs") ?: emptyList()
            val freshEvidenceJson = TalkerExecutorProtocol.asString(map, "freshEvidenceJson") ?: ""
            val detail = TalkerExecutorProtocol.asString(map, "detail") ?: ""
            val finishedAtMs = TalkerExecutorProtocol.asLong(map, "finishedAtMs") ?: return null
            return ExecutorFixResult(
                version = version, commandId = commandId, attempt = attempt,
                status = status, missingRefs = missingRefs,
                freshEvidenceJson = freshEvidenceJson, detail = detail,
                finishedAtMs = finishedAtMs
            )
        }
    }
}
