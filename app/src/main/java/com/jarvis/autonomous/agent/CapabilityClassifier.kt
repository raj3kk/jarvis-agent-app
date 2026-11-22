package com.jarvis.autonomous.agent

/**
 * Self-extension stage 3 gate — CAPABILITY CLASSIFIER (DESIGN §8.2).
 *
 * Pure Kotlin — ZERO Android imports — JVM-testable. This file is
 * THE enforcement point (E1): [CapabilityClassifier.classify] is the
 * only producer of [Classification] values, and the installer
 * accepts only a classifier-produced AUTO (see [InstallGate]).
 *
 * MIRROR NOTE (parent decision §12-8 / C9): this table copies the
 * SERVER mirror (`omnijarvis-server/src/skills.ts classifySkillClass`)
 * as implemented, not the DESIGN §8.2 text where they differ:
 * - C9 fires when lastUpdated is null OR the candidate is stale
 *   (> 24 months) — the design text's "stale AND unknown" can never
 *   fire (a null date has no age); the server reads it as OR, and
 *   the OR reading is fail-closed, so the app copies it.
 * - C2 (closed-source) is checked before C1 (unknown licence) so a
 *   proprietary licence reports `closed_source`, not `unknown_licence`.
 * - KNOWN_ACTIONS is the server's frozen 20-action set
 *   (SELF_EXT_KNOWN_ACTIONS), NOT the wider loop-action set: a STEP
 *   skill using e.g. WHATSAPP_SEND / TAP_XY / CALL is a CODE
 *   candidate here, exactly as the server classifies it on submit.
 *   (The task brief's anchor named the wider set; decision 8 —
 *   "app classifier must match the server mirror exactly" — wins.)
 * - Incomplete research signals (any of needsCard / needsAccount /
 *   needsNetwork / costUsd missing) fail closed to APPROVAL, never AUTO.
 *
 * Decision table, first match wins (a REJECT is never downgraded):
 * C1 unknown licence -> REJECT | C2 closed-source -> REJECT |
 * C3 paid/card -> REJECT | C4 unknown action / CODE kind -> CODE path
 * (continues as APPROVAL, never AUTO) | C5 account/credential ->
 * APPROVAL | C6 network/exfil -> APPROVAL | C7 sensitive action ->
 * APPROVAL | C8 copyleft -> APPROVAL | C9 stale/unknown maintenance
 * -> APPROVAL | C10 oversize -> REJECT (STEP) / APPROVAL (CODE) |
 * C11 pre-scan hit -> REJECT | C12 clean local STEP -> AUTO |
 * C13 CODE, no REJECT hit -> APPROVAL | C14 anything else -> APPROVAL.
 */
enum class SkillKind { STEP, CODE }

enum class ClassificationValue { AUTO, APPROVAL, REJECT }

/** One step of a candidate STEP skill (action + optional target/input). */
data class SkillStep(
    val action: String,
    val target: String = "",
    val input: String? = null
)

/**
 * A researched candidate (DESIGN §3.2). `kind` is classifier-assigned
 * in the output, never LLM-assigned: the input kind is a hint only.
 * `suggestedSteps` is the app-side procedure the LLM proposed for a
 * STEP skill (absent from the server candidate shape; never submitted
 * as-is — the submitter sends action names only, §5.4).
 */
data class ResearchCandidate(
    val name: String,
    val sourceUrl: String,
    val licence: String,
    val licenceUrl: String? = null,
    val costUsd: String,
    val needsCard: Boolean,
    val needsAccount: Boolean,
    val needsNetwork: Boolean,
    val lastUpdated: String? = null,
    val sizeBytes: Long? = null,
    val hostRequirements: List<String> = emptyList(),
    val kind: SkillKind = SkillKind.STEP,
    val evidence: List<String> = emptyList(),
    val stale: Boolean = false,
    val suggestedSteps: List<SkillStep> = emptyList(),
    /** True when any research signal is missing/unknown (cost, card,
     *  account, network). Fail-closed to APPROVAL, never AUTO. */
    val signalsComplete: Boolean = true
)

/** A classifier verdict: the value + the row that fired + the kind. */
data class Classification(
    val value: ClassificationValue,
    val reason: String,
    val kind: SkillKind
)

object CapabilityClassifier {

    // ---- Licence sets (§3.4, = server SELF_EXT_LICENSE_*) ----
    val LICENSE_ALLOWLIST = setOf(
        "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC",
        "MPL-2.0", "CC0-1.0", "Unlicense", "Zlib"
    )
    val LICENSE_COPYLEFT = setOf(
        "GPL-2.0", "GPL-3.0", "LGPL-2.1", "LGPL-3.0", "AGPL-3.0", "EPL-2.0"
    )
    val LICENSE_CLOSED = setOf(
        "proprietary", "commercial", "closed-source", "closed_source",
        "free for personal use", "free-for-personal-use"
    )

    // ---- Frozen action sets (= server SELF_EXT_KNOWN_ACTIONS etc.) ----
    val KNOWN_ACTIONS = setOf(
        "OPEN_APP", "TAP_NODE", "TYPE_TEXT", "SCROLL", "NAVIGATE",
        "YOUTUBE_SEARCH", "YOUTUBE_PLAY", "MEDIA_CONTROL", "FLASHLIGHT",
        "VOLUME", "SET_ALARM", "WAIT", "SAVE_MEMORY", "CREATE_PROJECT",
        "WRITE_PROJECT_FILE", "BUILD", "GITHUB_PUSH", "SUPABASE_TABLES",
        "VERCEL_DEPLOY", "HTTP_CHECK"
    )
    val NETWORK_ACTIONS = setOf("OPEN_URL", "YOUTUBE_SEARCH", "YOUTUBE_PLAY", "HTTP_CHECK")
    val CONFIRMATION_ACTIONS = setOf(
        "WHATSAPP_SEND", "REPLY_NOTIFICATION", "SEND", "SEND_MESSAGE",
        "PAYMENT", "PAY", "PURCHASE", "BUY", "DELETE", "DELETE_FILE", "REMOVE"
    )
    private val CONFIRM_TARGET_KEYWORDS = listOf(
        "send", "pay", "buy", "purchase", "checkout", "delete", "remove",
        "confirm order", "place order"
    )
    private val CREDENTIAL_REGEX = Regex(
        "api[_-]?key|password|\\botp\\b|\\btoken\\b|secret|" +
            "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}",
        RegexOption.IGNORE_CASE
    )

    const val MAX_STEPS = 20
    const val MAX_ARTIFACT_BYTES = 10L * 1024L * 1024L
    const val MAX_MANIFEST_BYTES = 64L * 1024L
    const val STALE_MS = 24L * 30L * 24L * 60L * 60L * 1000L

    /**
     * The decision table (§8.2, server-mirror reading). [scanHit] is
     * the on-device pre-scan result for the steps/fetched text
     * (null = clean, otherwise the pattern name, e.g. "eval(");
     * pass the scanner's finding, never a hand-made string.
     */
    fun classify(
        candidate: ResearchCandidate,
        steps: List<SkillStep>,
        scanHit: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Classification {
        val licence = candidate.licence.trim()
        // C2 then C1 (server order): closed-source first.
        if (LICENSE_CLOSED.any { it.equals(licence, ignoreCase = true) }) {
            return Classification(ClassificationValue.REJECT, "closed_source", SkillKind.STEP)
        }
        if (licence.isBlank() || licence == "UNKNOWN" ||
            (licence !in LICENSE_ALLOWLIST && licence !in LICENSE_COPYLEFT)
        ) {
            return Classification(ClassificationValue.REJECT, "unknown_licence", SkillKind.STEP)
        }
        val isCopyleft = licence in LICENSE_COPYLEFT
        val isAllowlisted = licence in LICENSE_ALLOWLIST
        // C3: paid / card-required -> REJECT.
        if ((candidate.costUsd.isNotBlank() && candidate.costUsd != "\$0") || candidate.needsCard) {
            return Classification(ClassificationValue.REJECT, "paid_or_card_required", SkillKind.STEP)
        }
        // C4: unknown action, explicit CODE kind -> CODE path.
        val hasUnknownAction = steps.any { it.action.uppercase() !in KNOWN_ACTIONS }
        val kind: SkillKind =
            if (candidate.kind == SkillKind.CODE || hasUnknownAction) SkillKind.CODE else SkillKind.STEP
        // Incomplete research signals -> APPROVAL (fail-closed).
        if (!candidate.signalsComplete) {
            return Classification(ClassificationValue.APPROVAL, "incomplete_research_signals", kind)
        }
        // C5: account / credential signals -> APPROVAL.
        val credentialStep = steps.any { step ->
            CREDENTIAL_REGEX.containsMatchIn(step.target) ||
                (step.input != null && CREDENTIAL_REGEX.containsMatchIn(step.input))
        }
        if (candidate.needsAccount || credentialStep) {
            return Classification(ClassificationValue.APPROVAL, "needs_account_or_credential", kind)
        }
        // C6: network / exfil risk -> APPROVAL.
        val networkStep = steps.any { it.action.uppercase() in NETWORK_ACTIONS }
        if (candidate.needsNetwork || networkStep) {
            return Classification(ClassificationValue.APPROVAL, "network_or_exfil_risk", kind)
        }
        // C7: sensitive (send/pay/buy/delete) action -> APPROVAL.
        val sensitiveStep = steps.any { step ->
            val action = step.action.uppercase()
            if (action.contains("SEND") || action.contains("PAY") || action.contains("PURCHASE") ||
                action.contains("BUY") || action.contains("DELETE")
            ) return@any true
            if (action == "TAP_NODE") {
                val lower = step.target.lowercase()
                CONFIRM_TARGET_KEYWORDS.any { lower.contains(it) }
            } else false
        }
        if (sensitiveStep) {
            return Classification(ClassificationValue.APPROVAL, "sensitive_action", kind)
        }
        // C8: copyleft -> APPROVAL.
        if (isCopyleft) {
            return Classification(ClassificationValue.APPROVAL, "copyleft_needs_review", kind)
        }
        // C9 (server reading): stale OR maintenance unknowable -> APPROVAL.
        if (candidate.lastUpdated == null || isStale(candidate, nowMs)) {
            return Classification(ClassificationValue.APPROVAL, "unmaintained_unknown", kind)
        }
        // C10: size over budget -> REJECT (STEP) / APPROVAL (CODE).
        if (kind == SkillKind.STEP && steps.size > MAX_STEPS) {
            return Classification(ClassificationValue.REJECT, "oversize", kind)
        }
        if (kind == SkillKind.CODE && candidate.sizeBytes != null &&
            candidate.sizeBytes > MAX_ARTIFACT_BYTES
        ) {
            return Classification(ClassificationValue.APPROVAL, "oversize", kind)
        }
        // C11: pre-scan hit -> REJECT.
        if (scanHit != null) {
            return Classification(ClassificationValue.REJECT, "scan_hit:$scanHit", kind)
        }
        // C12: clean local STEP -> AUTO.
        if (kind == SkillKind.STEP && isAllowlisted && candidate.costUsd == "\$0") {
            return Classification(ClassificationValue.AUTO, "clean_local_step", kind)
        }
        // C13: CODE with no REJECT hit -> APPROVAL.
        if (kind == SkillKind.CODE) {
            return Classification(ClassificationValue.APPROVAL, "code_needs_review", kind)
        }
        // C14: anything not matched -> APPROVAL (fail-closed).
        return Classification(ClassificationValue.APPROVAL, "unclassified", kind)
    }

    /** True when the candidate is stale: explicit flag, or lastUpdated > 24 months old. */
    fun isStale(candidate: ResearchCandidate, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (candidate.stale) return true
        val raw = candidate.lastUpdated ?: return false
        val ms = parseDateMs(raw) ?: return false
        return nowMs - ms > STALE_MS
    }

    /** Parses "yyyy-MM-dd" (and ISO datetime prefixes) to epoch ms, or null. */
    fun parseDateMs(raw: String): Long? {
        val trimmed = raw.trim()
        val datePart = trimmed.take(10)
        val parts = datePart.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (year !in 1970..2100 || month !in 1..12 || day !in 1..31) return null
        // Days-from-civil (Howard Hinnant's algorithm) — no timezone,
        // no java.time, pure arithmetic so the JVM harness can run it.
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = if (month > 2) month - 3 else month + 9
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097 + doe - 719468
        return days * 86_400_000L
    }

    /**
     * Ranking score (§8.2): 100 base; -30 stale; -20 lastUpdated null;
     * -10 per non-critical warning (size unknown, docs thin); +10
     * licence in allowlist with a fetched LICENSE file (evidence-backed).
     * The score ranks; it never overrides the decision table.
     */
    fun score(candidate: ResearchCandidate, nowMs: Long = System.currentTimeMillis()): Int {
        var score = 100
        if (isStale(candidate, nowMs)) score -= 30
        if (candidate.lastUpdated == null) score -= 20
        if (candidate.sizeBytes == null && candidate.kind == SkillKind.CODE) score -= 10
        if (candidate.evidence.isEmpty()) score -= 10
        if (candidate.licence in LICENSE_ALLOWLIST && candidate.licenceUrl != null) score += 10
        return score
    }
}

/**
 * Installer gate (E2, defence in depth): the installer re-checks
 * budgets and KNOWN_ACTIONS membership itself instead of trusting
 * the classifier's step check. Pure, so the harness can test the
 * refusal paths without Android.
 */
object InstallGate {

    sealed interface GateResult {
        data object Allowed : GateResult
        data class Refused(val reason: String) : GateResult
    }

    /**
     * Returns [GateResult.Allowed] ONLY for a classifier-produced
     * AUTO + kind STEP + clean scan + in-budget steps/manifest/store.
     * Any other input is refused BEFORE any write.
     */
    fun check(
        classification: Classification,
        steps: List<SkillStep>,
        scanHit: String? = null,
        manifestBytes: Long = 0L,
        storeBytes: Long = 0L,
        storeCapBytes: Long = SelfExtensionBudgets.STORE_CAP_BYTES
    ): GateResult {
        if (classification.value != ClassificationValue.AUTO) {
            return GateResult.Refused("not_auto:${classification.reason}")
        }
        if (classification.kind != SkillKind.STEP) {
            return GateResult.Refused("not_step_kind")
        }
        if (scanHit != null) {
            return GateResult.Refused("scan_hit:$scanHit")
        }
        if (steps.isEmpty()) {
            return GateResult.Refused("no_steps")
        }
        if (steps.size > CapabilityClassifier.MAX_STEPS) {
            return GateResult.Refused("oversize")
        }
        if (steps.any { it.action.uppercase() !in CapabilityClassifier.KNOWN_ACTIONS }) {
            return GateResult.Refused("unknown_action")
        }
        if (manifestBytes > CapabilityClassifier.MAX_MANIFEST_BYTES) {
            return GateResult.Refused("oversize")
        }
        if (storeBytes + manifestBytes > storeCapBytes) {
            return GateResult.Refused("store_full")
        }
        return GateResult.Allowed
    }
}

/**
 * Replay gate (DESIGN §8 E3, fail-closed — pure so the JVM harness
 * can test it): a self-added AUTO skill with an empty verification
 * NEVER replays, even if a bug flips `enabled`. Learned skills and
 * user-approved imports are unaffected. `LearnedRoutineStore.
 * isSkillEnabled` delegates to this function.
 */
object SelfExtensionReplayGate {
    fun isEnabled(
        origin: String,
        autoAdded: Boolean,
        verification: String,
        enabledFlag: Boolean
    ): Boolean {
        if (origin == "self_extended" && autoAdded && verification.isBlank()) return false
        return enabledFlag
    }
}

/** Hard budgets (§2.5, §3.5, §8 S7) shared by loop, store and installer. */
object SelfExtensionBudgets {
    const val MAX_LLM_CALLS = 2
    const val MAX_FETCHES = 3
    const val MAX_FETCH_BYTES = 200L * 1024L
    const val RESEARCH_WALL_MS = 90_000L
    const val VERIFY_WALL_MS = 120_000L
    const val MAX_CANDIDATES = 5
    const val REPORT_CACHE_MS = 7L * 24L * 60L * 60L * 1000L
    const val RESEARCH_PER_DAY = 3
    const val COOLDOWN_MS = 24L * 60L * 60L * 1000L
    const val STORE_CAP_BYTES = 5L * 1024L * 1024L
    const val MAX_STEPS_PER_SKILL = 20
}
