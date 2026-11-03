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
    fun findRoutine(phrase: String): LearnedRoutine? {
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
        private const val MAX_WAIT_MS = 10_000L
        private const val MAX_VALUE_LENGTH = 200

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
