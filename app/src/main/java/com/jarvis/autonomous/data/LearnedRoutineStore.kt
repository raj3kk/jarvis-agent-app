package com.jarvis.autonomous.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * S5 — Learned routines (JarvisAgentApp 1.4.0, memory-first).
 *
 * A learned routine is the VERIFIED action sequence of one multi-step
 * task the user asked for before. Next time the same task is asked,
 * the engine replays these steps instead of re-planning with the LLM.
 * On ANY replay mismatch the engine must abort replay and fall into
 * runAutonomousLoop — never silently stop, never claim success
 * without verification (see INTEGRATION_NOTES_Memory.md).
 *
 * Storage: a dedicated SharedPreferences file, JSON array. This is
 * LOCAL state only — no network is needed to record, list, find, or
 * replay a routine (see isNetworkDependent() for per-step behaviour
 * when offline).
 *
 * PRIVACY BOUNDARY (enforced here as a backstop, owned by the caller):
 * - target is a view id, content description, or app package ONLY.
 *   Never raw user message text, never a contact name/number, never
 *   a URL carrying identifiers (query string, id, token).
 * - input is ONLY a non-sensitive input the user explicitly asked to
 *   repeat (e.g. a YouTube/search query). Never a message body,
 *   password, OTP, file content, or URL with identifiers.
 * - recordStep() REJECTS (returns false, appends nothing) values that
 *   look like an email, a phone number, or an identifier-bearing URL,
 *   and over-long values that smell like message/file text. These
 *   pattern checks are a backstop, not a guarantee: the engine must
 *   still only pass safe values in (see INTEGRATION_NOTES_Memory.md
 *   for the full NEVER-record list).
 *
 * RECORDING IS OPT-IN PER TASK COMPLETION: steps are buffered only
 * while a task runs, and finishRoutine() persists them ONLY when
 * verified == true. Failed / unverified runs are discarded, never
 * recorded. Nothing is recorded on every tap.
 */

/** One verified step of a learned routine. */
data class LearnedStep(
    val action: String,
    val target: String,
    val input: String? = null,
    val waitMs: Long = 0L
)

/** One learned routine: a normalized trigger plus its verified steps. */
data class LearnedRoutine(
    val id: String,
    val trigger: String,
    val steps: List<LearnedStep>,
    val createdAtMs: Long = 0L
)

/**
 * Provenance for a self-extended skill (DESIGN §5.3, additive).
 * `researchedBy` is the fixed literal "self-extension" — never a
 * model name + version free text. `verification` is
 * "verified:<test-id>" once §7 passes, "" until then.
 */
data class SkillProvenance(
    val researchedBy: String,
    val sources: List<String>,
    val licence: String,
    val costUsd: String,
    val addedAtMs: Long,
    val autoAdded: Boolean,
    val researchSignature: String,
    val verification: String
)

/**
 * One on-device skill manifest (Phase 1, 1.5.3-PRO).
 *
 * A skill is a learned routine PLUS its lifecycle metadata:
 * how often it replayed, when it last ran, whether the user
 * left it enabled, and which content version it is on.
 * The manifest is the source of truth for the Skills UI and
 * for replay gating; the routine JSON stays as the legacy
 * step store (unchanged format, still the replay input).
 *
 * PRIVACY: manifests NEVER leave the phone. No network call
 * touches them; the learned-pattern upload path
 * (maybeUploadLearnedPattern) stays templates-only and does
 * not read manifests. No server, no global publish.
 *
 * Schema v1 (frozen in Pehle-Jaanch §3.1): schemaVersion is
 * the envelope version, checked on load — an unknown version
 * fails closed (skill skipped, routine untouched).
 *
 * 1.11.0-PRO (self-extension, DESIGN §5.3): additive `origin` /
 * `provenance` fields, both optional with safe defaults, so v1
 * readers keep working. `origin="learned"` is the default.
 */
data class LocalSkillManifest(
    val id: String,
    val trigger: String,
    val displayName: String,
    val steps: List<LearnedStep>,
    val source: String = "learned",
    val createdAtMs: Long = 0L,
    val lastUsedAtMs: Long = 0L,
    val useCount: Int = 0,
    val enabled: Boolean = true,
    val version: Int = 1,
    val origin: String = "learned",
    val provenance: SkillProvenance? = null
)

/** Trigger normalization, shared by recording and lookup (idempotent). */
object RoutineNormalization {
    // Filler words dropped ONLY for the close-match comparison, never
    // from the stored trigger itself.
    private val FILLER_WORDS = setOf("please", "pls", "bhai", "jarvis", "zara")

    /** Lowercase + trim + collapse every whitespace run to one space. */
    fun normalize(raw: String): String =
        raw.lowercase().trim().replace(Regex("\\s+"), " ")

    fun tokens(normalized: String): List<String> =
        normalized.split(" ").filter { it.isNotBlank() }

    /** Tokens with fillers removed, sorted — order-insensitive form. */
    fun sortedSignificantTokens(normalized: String): List<String> =
        tokens(normalized).filter { it !in FILLER_WORDS }.sorted()
}

class LearnedRoutineStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Buffer for the task currently being recorded. Bound to its
    // trigger only when finishRoutine(trigger, verified) is called.
    private val pendingSteps = mutableListOf<LearnedStep>()

    // ------------------------------------------------------------------
    // Recording (opt-in per completed task only)
    // ------------------------------------------------------------------

    /**
     * Buffers one executed step. Returns false (and buffers nothing)
     * when the action is blank or the target/input trips the safety
     * checks below. Callers must still only pass safe values.
     */
    fun recordStep(
        action: String,
        target: String,
        input: String? = null,
        waitMs: Long = 0L
    ): Boolean {
        if (action.isBlank()) return false
        if (looksSensitive(target)) return false
        if (input != null && looksSensitive(input)) return false
        pendingSteps.add(
            LearnedStep(
                action = action.trim(),
                target = target.trim(),
                input = input?.trim()?.ifBlank { null },
                waitMs = waitMs.coerceIn(0L, MAX_WAIT_MS)
            )
        )
        return true
    }

    fun clearPending() {
        pendingSteps.clear()
    }

    /**
     * Persists the buffered steps under [trigger] ONLY if [verified]
     * is true and at least one step was buffered. A failed or
     * unverified run returns null and the buffer is always discarded,
     * so a failed run can never become a routine.
     */
    fun finishRoutine(trigger: String, verified: Boolean): LearnedRoutine? {
        val steps = pendingSteps.toList()
        pendingSteps.clear()
        if (!verified) return null
        val normalized = RoutineNormalization.normalize(trigger)
        if (normalized.isBlank() || steps.isEmpty()) return null
        val routine = LearnedRoutine(
            id = System.currentTimeMillis().toString(),
            trigger = normalized,
            steps = steps,
            createdAtMs = System.currentTimeMillis()
        )
        val all = loadRoutines().toMutableList()
        // One trigger, one routine: re-learning the same task replaces
        // the stale sequence instead of stacking duplicates.
        val replaced = all.filterNot { it.trigger == normalized }.toMutableList()
        replaced.add(routine)
        saveRoutines(replaced)
        // 1.5.3-PRO: the routine just learned (or re-learned) gets
        // its skill manifest upserted (version+1 on re-learn,
        // useCount / lastUsedAtMs / enabled preserved).
        upsertSkillManifest(routine)
        return routine
    }

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    /**
     * MATCHING RULE (exact, then close — nothing fuzzier):
     * 1. EXACT: normalized phrase == stored normalized trigger.
     * 2. CLOSE: same significant words in ANY order — the sorted
     *    significant-token lists are equal (filler words such as
     *    "please"/"bhai"/"jarvis" ignored). This is word-order
     *    insensitive equality, NOT a subset or similarity match.
     * Deliberately NO partial / substring / fuzzy matching: a phrase
     * that only overlaps a routine ("play lofi" vs "play lofi and
     * message mom") must NOT replay that routine — it falls through
     * to the LLM loop, which is the safe path for a different task.
     * The input phrase is normalized inside, so callers may pass raw
     * user text; normalization is idempotent.
     */
    /**
     * 1.5.3-PRO: disabled skills never replay. A matched routine
     * whose manifest is disabled returns null, so the caller
     * falls through to the LLM loop. No manifest (legacy
     * routine, learned before 1.5.3) counts as enabled.
     */
    fun findRoutine(phrase: String): LearnedRoutine? =
        findMatchedRoutine(phrase)?.takeIf { isSkillEnabled(it.id) }

    private fun findMatchedRoutine(phrase: String): LearnedRoutine? {
        val normalized = RoutineNormalization.normalize(phrase)
        if (normalized.isBlank()) return null
        val all = loadRoutines()
        for (routine in all) {
            if (routine.trigger == normalized) return routine
        }
        val wanted = RoutineNormalization.sortedSignificantTokens(normalized)
        if (wanted.isEmpty()) return null
        for (routine in all) {
            if (RoutineNormalization.sortedSignificantTokens(routine.trigger) == wanted) {
                return routine
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // REPLAY SAFETY (hard rule): confirmation-required steps
    // ------------------------------------------------------------------

    /**
     * Returns true when replaying this step MUST ask the user to
     * confirm first, even inside a learned routine. The replay loop
     * MUST call this before every step and, for a true result, ask
     * (voice reply) and execute only on an explicit yes; on no / no
     * reply it aborts replay into runAutonomousLoop — it never skips
     * the step silently and never proceeds without confirmation.
     *
     * COVERED STEP TYPES — exact list:
     * - Send steps: WHATSAPP_SEND, REPLY_NOTIFICATION, plus the
     *   generic SEND / SEND_MESSAGE action names (future-proofing).
     * - Payment / purchase steps: PAYMENT, PAY, PURCHASE, BUY.
     * - Delete steps: DELETE, DELETE_FILE, REMOVE.
     * - TAP_NODE backstop: a tap whose target (view id / content
     *   description) names a send / pay / buy / delete / checkout
     *   control is also confirmation-required, because the engine has
     *   no dedicated PAY / DELETE action — those go through TAP_NODE.
     *   The keyword list below is the backstop; the engine must ALSO
     *   treat any live screen showing a send/payment/delete
     *   confirmation as confirmation-required regardless of keywords.
     * NOT covered (safe to replay without asking): OPEN_APP,
     * YOUTUBE_SEARCH, YOUTUBE_PLAY (play-first ask lives in the
     * no-AI routine path, not here), TAP_NODE on ordinary controls,
     * TYPE_TEXT of a repeatable search query, SCROLL, NAVIGATE,
     * MEDIA_CONTROL, FLASHLIGHT, VOLUME, SET_ALARM, WAIT,
     * SAVE_MEMORY, CREATE_PROJECT, WRITE_PROJECT_FILE.
     */
    fun isConfirmationRequired(action: String, target: String = ""): Boolean {
        val upper = action.trim().uppercase()
        if (upper in CONFIRMATION_ACTIONS) return true
        if (upper == "TAP_NODE") {
            val lowerTarget = target.lowercase()
            for (keyword in CONFIRM_TARGET_KEYWORDS) {
                if (lowerTarget.contains(keyword)) return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------
    // OFFLINE MODE: which steps need the network
    // ------------------------------------------------------------------

    /**
     * Returns true when the step needs network to do anything useful.
     * Local steps (OPEN_APP, TAP_NODE, SCROLL, NAVIGATE, MEDIA_CONTROL,
     * FLASHLIGHT, VOLUME, SET_ALARM, WAIT, SAVE_MEMORY,
     * CREATE_PROJECT, WRITE_PROJECT_FILE) return false and proceed
     * offline. Network steps return true; see
     * INTEGRATION_NOTES_Memory.md for the replay rule: local steps
     * run, the first network step warns and hands over to the LLM
     * loop instead of silently failing, and send/payment/delete
     * steps are NEVER queued for later.
     */
    fun isNetworkDependent(action: String): Boolean =
        action.trim().uppercase() in NETWORK_ACTIONS

    // ------------------------------------------------------------------
    // SKILL MANIFESTS v1 (1.5.3-PRO) — on-device only, never uploaded
    // ------------------------------------------------------------------

    /**
     * Loads all skill manifests. Unknown schemaVersion entries
     * are skipped (fail-closed); the routine JSON is untouched.
     * Public — the Skills UI reads through this.
     */
    fun loadSkillManifests(): List<LocalSkillManifest> {
        val raw = prefs.getString(KEY_SKILLS_JSON, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<LocalSkillManifest>()
            for (i in 0 until arr.length()) {
                val manifest = manifestFromJson(arr.getJSONObject(i))
                if (manifest != null) {
                    out.add(manifest)
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun saveSkillManifests(manifests: List<LocalSkillManifest>) {
        val arr = JSONArray()
        for (manifest in manifests) {
            arr.put(manifestToJson(manifest))
        }
        prefs.edit().putString(KEY_SKILLS_JSON, arr.toString()).apply()
    }

    /**
     * Creates or refreshes the manifest for a just-learned (or
     * re-learned) routine. Match is by TRIGGER: re-learning the
     * same task bumps version by 1 and keeps useCount /
     * lastUsedAtMs / enabled (never reset), while the id follows
     * the current routine (finishRoutine replaces by trigger, so
     * the old id is stale). Prune runs after every upsert.
     */
    private fun upsertSkillManifest(routine: LearnedRoutine) {
        val all = loadSkillManifests().toMutableList()
        val existing = all.firstOrNull { it.trigger == routine.trigger }
        val manifest = if (existing != null) {
            existing.copy(
                id = routine.id,
                steps = routine.steps,
                version = existing.version + 1
            )
        } else {
            LocalSkillManifest(
                id = routine.id,
                trigger = routine.trigger,
                displayName = routine.trigger,
                steps = routine.steps,
                createdAtMs = routine.createdAtMs
            )
        }
        val replaced = all.filterNot { it.trigger == routine.trigger }.toMutableList()
        replaced.add(manifest)
        saveSkillManifests(replaced)
        pruneSkillsIfNeeded()
    }

    /**
     * Counts one successful replay (full replay passed — the
     * engine calls this only after every step passed). A
     * half-finished or mismatched replay never counts.
     * No manifest (legacy routine) = no-op.
     */
    fun recordSkillUse(id: String) {
        val all = loadSkillManifests().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return
        val m = all[idx]
        all[idx] = m.copy(
            useCount = m.useCount + 1,
            lastUsedAtMs = System.currentTimeMillis()
        )
        saveSkillManifests(all)
    }

    /** User toggle from the Skills UI. No manifest = no-op. */
    fun setSkillEnabled(id: String, enabled: Boolean) {
        val all = loadSkillManifests().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return
        all[idx] = all[idx].copy(enabled = enabled)
        saveSkillManifests(all)
    }

    /**
     * Deletes a skill: its manifest AND its routine (both stay
     * in sync — a deleted skill must never replay from a stale
     * routine entry).
     */
    fun deleteSkill(id: String) {
        saveSkillManifests(loadSkillManifests().filterNot { it.id == id })
        saveRoutines(loadRoutines().filterNot { it.id == id })
    }

    /** No manifest (legacy routine) counts as enabled. */
    fun isSkillEnabled(id: String): Boolean {
        val manifest = loadSkillManifests().firstOrNull { it.id == id } ?: return true
        // 1.11.0-PRO (self-extension, §8 E3, fail-closed): delegates
        // to SelfExtensionReplayGate — an unverified self-added skill
        // never replays even if a bug flips `enabled`.
        return com.jarvis.autonomous.agent.SelfExtensionReplayGate.isEnabled(
            origin = manifest.origin,
            autoAdded = manifest.provenance?.autoAdded == true,
            verification = manifest.provenance?.verification ?: "",
            enabledFlag = manifest.enabled
        )
    }

    /**
     * 1.11.0-PRO (self-extension, DESIGN §5.2/§5.3): installs a
     * STEP skill found by the loop. The manifest starts
     * `enabled=false` + empty `verification`; ONLY the verifier
     * flips it on (via [markSkillVerified] + [setSkillEnabled]).
     * The routine is saved alongside (same sync rule as
     * [deleteSkill]) so `findRoutine` can replay it once enabled.
     * Callers MUST have passed the classifier + installer gates —
     * this function does not re-classify, it only stores.
     */
    fun upsertSelfExtendedSkill(routine: LearnedRoutine, provenance: SkillProvenance): LocalSkillManifest {
        val allRoutines = loadRoutines().filterNot { it.id == routine.id }.toMutableList()
        allRoutines.add(routine)
        saveRoutines(allRoutines)
        val manifest = LocalSkillManifest(
            id = routine.id,
            trigger = routine.trigger,
            displayName = routine.trigger,
            steps = routine.steps,
            source = "self_extended",
            createdAtMs = routine.createdAtMs,
            enabled = false,
            version = 1,
            origin = "self_extended",
            provenance = provenance
        )
        val all = loadSkillManifests().filterNot { it.id == routine.id }.toMutableList()
        all.add(manifest)
        saveSkillManifests(all)
        pruneSkillsIfNeeded()
        return manifest
    }

    /**
     * 1.11.0-PRO (self-extension, §7): records a passed §7 test on
     * a self-added skill. Returns false when the skill is absent or
     * is not a self-added skill (learned skills never carry one).
     */
    fun markSkillVerified(id: String, verification: String): Boolean {
        val all = loadSkillManifests().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val m = all[idx]
        if (m.origin != "self_extended" || m.provenance == null) return false
        all[idx] = m.copy(provenance = m.provenance.copy(verification = verification))
        saveSkillManifests(all)
        return true
    }

    /**
     * Cap 50 + prune. Over the cap, one victim at a time: (1)
     * disabled + lowest useCount first; (2) otherwise the
     * oldest lastUsedAtMs (0 = never used) + lowest useCount.
     * The victim's routine goes with its manifest (sync).
     * Diagnostic carries id + useCount ONLY — never trigger
     * text (a trigger is the user's own spoken phrase).
     */
    private fun pruneSkillsIfNeeded() {
        var all = loadSkillManifests()
        while (all.size > MAX_SKILLS) {
            val disabled = all.filter { !it.enabled }
            val victim = if (disabled.isNotEmpty()) {
                disabled.minWithOrNull(compareBy({ it.useCount }, { it.lastUsedAtMs }))
            } else {
                all.minWithOrNull(compareBy({ it.lastUsedAtMs }, { it.useCount }))
            } ?: break
            runCatching {
                JarvisStateRepository.addDiagnostic(
                    "skill", "SKILL_PRUNED",
                    "id=${victim.id} useCount=${victim.useCount}"
                )
            }
            saveSkillManifests(all.filterNot { it.id == victim.id })
            saveRoutines(loadRoutines().filterNot { it.id == victim.id })
            all = loadSkillManifests()
        }
    }

    private fun manifestToJson(manifest: LocalSkillManifest): JSONObject {
        val obj = JSONObject()
        obj.put("schemaVersion", SKILL_SCHEMA_VERSION)
        obj.put("id", manifest.id)
        obj.put("trigger", manifest.trigger)
        obj.put("displayName", manifest.displayName)
        val stepsArr = JSONArray()
        for (step in manifest.steps) {
            stepsArr.put(stepToJson(step))
        }
        obj.put("steps", stepsArr)
        obj.put("source", manifest.source)
        obj.put("createdAtMs", manifest.createdAtMs)
        obj.put("lastUsedAtMs", manifest.lastUsedAtMs)
        obj.put("useCount", manifest.useCount)
        obj.put("enabled", manifest.enabled)
        obj.put("version", manifest.version)
        // 1.11.0-PRO (self-extension, §5.3, additive): origin +
        // provenance serialise only when present, so v1 JSON for
        // learned skills is byte-identical to before.
        obj.put("origin", manifest.origin)
        manifest.provenance?.let { prov ->
            val provObj = JSONObject()
            provObj.put("researchedBy", prov.researchedBy)
            val sourcesArr = JSONArray()
            for (source in prov.sources.take(5)) sourcesArr.put(source)
            provObj.put("sources", sourcesArr)
            provObj.put("licence", prov.licence)
            provObj.put("costUsd", prov.costUsd)
            provObj.put("addedAtMs", prov.addedAtMs)
            provObj.put("autoAdded", prov.autoAdded)
            provObj.put("researchSignature", prov.researchSignature)
            provObj.put("verification", prov.verification)
            obj.put("provenance", provObj)
        }
        return obj
    }

    private fun manifestFromJson(obj: JSONObject): LocalSkillManifest? {
        // Unknown schema version = fail-closed (skip the entry).
        if (obj.optInt("schemaVersion", -1) != SKILL_SCHEMA_VERSION) return null
        val id = obj.optString("id", "")
        val trigger = obj.optString("trigger", "")
        if (id.isBlank() || trigger.isBlank()) return null
        val stepsArr = obj.optJSONArray("steps") ?: return null
        val steps = ArrayList<LearnedStep>()
        for (i in 0 until stepsArr.length()) {
            val step = stepFromJson(stepsArr.optJSONObject(i))
            if (step != null) {
                steps.add(step)
            }
        }
        if (steps.isEmpty()) return null
        // 1.11.0-PRO (self-extension, §5.3, additive): origin
        // defaults to "learned"; provenance absent -> null (old
        // manifests and learned skills are unaffected). A manifest
        // whose provenance licence is not in the §3.4 allowlist /
        // copyleft sets (or "UNKNOWN") is skipped (fail-closed).
        val origin = obj.optString("origin", "learned").ifBlank { "learned" }
        var provenance: SkillProvenance? = null
        val provObj = obj.optJSONObject("provenance")
        if (provObj != null) {
            val licence = provObj.optString("licence", "UNKNOWN").ifBlank { "UNKNOWN" }
            if (licence !in STORABLE_LICENCES) return null
            val sources = ArrayList<String>()
            val sourcesArr = provObj.optJSONArray("sources")
            if (sourcesArr != null) {
                for (i in 0 until sourcesArr.length()) {
                    val source = sourcesArr.optString(i, "")
                    if (source.isNotBlank()) sources.add(source)
                }
            }
            provenance = SkillProvenance(
                researchedBy = provObj.optString("researchedBy", "self-extension"),
                sources = sources.take(5),
                licence = licence,
                costUsd = provObj.optString("costUsd", "UNKNOWN"),
                addedAtMs = provObj.optLong("addedAtMs", 0L),
                autoAdded = provObj.optBoolean("autoAdded", false),
                researchSignature = provObj.optString("researchSignature", ""),
                verification = provObj.optString("verification", "")
            )
        }
        return LocalSkillManifest(
            id = id,
            trigger = trigger,
            displayName = obj.optString("displayName", trigger).ifBlank { trigger },
            steps = steps,
            source = obj.optString("source", "learned"),
            createdAtMs = obj.optLong("createdAtMs", 0L),
            lastUsedAtMs = obj.optLong("lastUsedAtMs", 0L),
            useCount = obj.optInt("useCount", 0),
            enabled = obj.optBoolean("enabled", true),
            version = obj.optInt("version", 1).coerceAtLeast(1),
            origin = origin,
            provenance = provenance
        )
    }

    // ------------------------------------------------------------------
    // Safety-pattern backstop for recordStep()
    // ------------------------------------------------------------------

    private fun looksSensitive(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length > MAX_VALUE_LENGTH) return true
        if (EMAIL_REGEX.containsMatchIn(trimmed)) return true
        if (PHONE_REGEX.containsMatchIn(trimmed)) return true
        // URL carrying identifiers: a query string, an id/token
        // parameter, or a long digit run inside a link.
        if (trimmed.contains("http")) {
            if (trimmed.contains("?") || trimmed.contains("=")) return true
            if (Regex("\\d{6,}").containsMatchIn(trimmed)) return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // JSON persistence (dedicated file, local only)
    // ------------------------------------------------------------------

    private fun loadRoutines(): List<LearnedRoutine> {
        val raw = prefs.getString(KEY_JSON, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<LearnedRoutine>()
            for (i in 0 until arr.length()) {
                val routine = routineFromJson(arr.getJSONObject(i))
                if (routine != null && routine.steps.isNotEmpty()) {
                    out.add(routine)
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun saveRoutines(routines: List<LearnedRoutine>) {
        val arr = JSONArray()
        for (routine in routines) {
            arr.put(routineToJson(routine))
        }
        prefs.edit().putString(KEY_JSON, arr.toString()).apply()
    }

    private fun routineToJson(routine: LearnedRoutine): JSONObject {
        val obj = JSONObject()
        obj.put("id", routine.id)
        obj.put("trigger", routine.trigger)
        obj.put("createdAtMs", routine.createdAtMs)
        val stepsArr = JSONArray()
        for (step in routine.steps) {
            stepsArr.put(stepToJson(step))
        }
        obj.put("steps", stepsArr)
        return obj
    }

    private fun routineFromJson(obj: JSONObject): LearnedRoutine? {
        val id = obj.optString("id", "")
        val trigger = obj.optString("trigger", "")
        if (id.isBlank() || trigger.isBlank()) return null
        val stepsArr = obj.optJSONArray("steps") ?: return null
        val steps = ArrayList<LearnedStep>()
        for (i in 0 until stepsArr.length()) {
            val step = stepFromJson(stepsArr.optJSONObject(i))
            if (step != null) {
                steps.add(step)
            }
        }
        if (steps.isEmpty()) return null
        return LearnedRoutine(
            id = id,
            trigger = trigger,
            steps = steps,
            createdAtMs = obj.optLong("createdAtMs", 0L)
        )
    }

    private fun stepToJson(step: LearnedStep): JSONObject {
        val obj = JSONObject()
        obj.put("action", step.action)
        obj.put("target", step.target)
        if (step.input != null) {
            obj.put("input", step.input)
        }
        obj.put("waitMs", step.waitMs)
        return obj
    }

    private fun stepFromJson(obj: JSONObject?): LearnedStep? {
        if (obj == null) return null
        val action = obj.optString("action", "")
        if (action.isBlank()) return null
        val input = if (obj.has("input")) obj.optString("input", "") else ""
        return LearnedStep(
            action = action,
            target = obj.optString("target", ""),
            input = input.ifBlank { null },
            waitMs = obj.optLong("waitMs", 0L).coerceIn(0L, MAX_WAIT_MS)
        )
    }

    companion object {
        private const val PREFS_NAME = "jarvis_learned_routines"
        private const val KEY_JSON = "learned_routines_json"
        // 1.5.3-PRO: skill manifests v1 — a SEPARATE key, so the
        // routine JSON format never changes (legacy routines keep
        // working with no migration).
        private const val KEY_SKILLS_JSON = "local_skills_json_v1"
        private const val SKILL_SCHEMA_VERSION = 1
        private const val MAX_SKILLS = 50
        private const val MAX_WAIT_MS = 10_000L
        private const val MAX_VALUE_LENGTH = 200

        // 1.11.0-PRO (self-extension, §5.3): licence strings a
        // stored self-added manifest may carry — the §3.4 allowlist
        // ∪ copyleft sets ∪ "UNKNOWN" (mirrors the server's
        // SELF_EXT_LICENSE_ALLOWLIST / COPYLEFT; kept literal here
        // so data/ never imports agent/).
        private val STORABLE_LICENCES = setOf(
            "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC",
            "MPL-2.0", "CC0-1.0", "Unlicense", "Zlib",
            "GPL-2.0", "GPL-3.0", "LGPL-2.1", "LGPL-3.0", "AGPL-3.0", "EPL-2.0",
            "UNKNOWN"
        )

        // Confirmation-required actions (see isConfirmationRequired()).
        private val CONFIRMATION_ACTIONS = setOf(
            "WHATSAPP_SEND", "REPLY_NOTIFICATION",
            "SEND", "SEND_MESSAGE",
            "PAYMENT", "PAY", "PURCHASE", "BUY",
            "DELETE", "DELETE_FILE", "REMOVE"
        )

        // TAP_NODE targets naming one of these controls are also
        // confirmation-required (backstop; see isConfirmationRequired()).
        private val CONFIRM_TARGET_KEYWORDS = listOf(
            "send", "pay", "buy", "purchase", "checkout",
            "delete", "remove", "confirm order", "place order"
        )

        // Steps that need network to be useful.
        private val NETWORK_ACTIONS = setOf(
            "YOUTUBE_SEARCH", "YOUTUBE_PLAY", "OPEN_URL",
            "WHATSAPP_SEND", "REPLY_NOTIFICATION"
        )

        private val EMAIL_REGEX =
            Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
        private val PHONE_REGEX =
            Regex("(\\+?\\d[\\d\\s\\-]{6,}\\d)")
    }
}
