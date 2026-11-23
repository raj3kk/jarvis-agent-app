package com.jarvis.autonomous.data

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.autonomous.agent.TaskBudgetDefaults
import org.json.JSONArray
import org.json.JSONObject

enum class AiProvider(
    val displayName: String,
    val defaultModel: String,
    val baseUrl: String,
    val fallbackModels: List<String> = emptyList()
) {
    // Live-verified 2026-11-02: gemini-2.5-flash returns 404 "no longer
    // available to new users" (Google limits 2.5 to past users since Sep 2026;
    // its own 404 message names gemini-3.8-flash as replacement).
    // Speed update 2026-11-02: 3.5-flash-lite is the fastest Flash-Lite and
    // is now primary; 3.8-flash (Google's UI/agent recommendation) is the
    // fallback if Lite fails, so speed does not cost accuracy.
    GEMINI(
        "Google Gemini (Recommended)", "gemini-3.5-flash-lite",
        "https://generativelanguage.googleapis.com/v1beta",
        listOf("gemini-3.1-flash-lite", "gemini-3.8-flash", "gemini-3-flash-preview")
    ),
    // gpt-4o-mini still answers in Nov 2026; OpenAI shuts the GPT-4 family
    // down on 2027-01-14 (replacement gpt-6-luna, a reasoning model).
    OPENAI(
        "OpenAI (GPT-4o-mini)", "gpt-4o-mini",
        "https://api.openai.com/v1/chat/completions",
        listOf("gpt-4.1-mini")
    ),
    // Live-verified 2026-11-02: llama-3.3-70b-versatile was decommissioned
    // by Groq on 2026-08-16 and now returns 404 model_not_found.
    // Groq's recommended replacement is openai/gpt-oss-120b (HTTP 200).
    // Speed update 2026-11-02: the smaller 20b model is primary (much
    // faster to answer); 120b is the fallback if 20b fails.
    GROQ(
        "Groq (Ultra-Fast)", "openai/gpt-oss-20b",
        "https://api.groq.com/openai/v1/chat/completions",
        listOf("openai/gpt-oss-120b")
    ),
    // Same 2.5 problem through OpenRouter's Google route.
    // Speed: Lite first, 3.8-flash as fallback (same order as Gemini).
    OPENROUTER(
        "OpenRouter / Custom", "google/gemini-3.5-flash-lite",
        "https://openrouter.ai/api/v1/chat/completions",
        listOf("google/gemini-3.8-flash", "google/gemini-3-flash-preview")
    ),
    // 1.20.0: the on-device LOCAL provider (Gemma 3 270M via
    // LiteRT-LM) was REMOVED by user order 2026-11-23 — the whole
    // entry is deleted, not deprecated. An install that saved
    // ai_provider=LOCAL falls back to GEMINI via the getter's
    // getOrDefault below, and a saved model_name of
    // gemma3-270m-it-q8 is migrated to the provider default in
    // modelName (see the guard there). No offline-chat fallback
    // remains: no network = a clear message, never a crash.
}

data class VoiceRoutine(
    val id: String,
    val triggerPhrase: String,
    val actionPrompt: String
)

class JarvisPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("jarvis_agent_prefs", Context.MODE_PRIVATE)

    var provider: AiProvider
        get() {
            val name = prefs.getString("ai_provider", AiProvider.GEMINI.name) ?: AiProvider.GEMINI.name
            return runCatching { AiProvider.valueOf(name) }.getOrDefault(AiProvider.GEMINI)
        }
        set(value) = prefs.edit().putString("ai_provider", value.name).apply()

    // 1.20.0: the Hugging Face token pref (hf_token) is deleted
    // with Gemma — no HF token is stored or used anywhere any more.
    // Old installs keep a stale "hf_token" value in prefs; nothing
    // reads it any more (harmless, never migrated).

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    // 1.4.12: Swara cloud voice (user order 2026-11-04: Swara
    // primary, bundled voice fully removed). The old cloud-voice
    // prefs (Gemini TTS toggle/voice, Deepgram key/toggle/voice)
    // were deleted with their AI Brain UI — VoiceManager never
    // reads them. Swara (Edge hi-IN-SwaraNeural, Hindi female)
    // needs internet but no key and no quota; OFF means the
    // system voice speaks (never silence — 1.4.12 behaviour
    // change from the old OFF-refusal).
    // MIGRATION: new key "swara_enabled" (default true). If it
    // is absent, the old "kokoro_enabled" value (default true)
    // is copied over once, so a 1.4.11 user who had voice OFF
    // stays OFF (now meaning system voice, not silence).
    var swaraEnabled: Boolean
        get() {
            if (prefs.contains("swara_enabled")) {
                return prefs.getBoolean("swara_enabled", true)
            }
            val migrated = prefs.getBoolean("kokoro_enabled", true)
            prefs.edit().putBoolean("swara_enabled", migrated).apply()
            return migrated
        }
        set(value) = prefs.edit().putBoolean("swara_enabled", value).apply()

    // 1.4.17: Live mode (Gemini real-time conversation) toggle.
    // Persisted ONLY by the AI Brain Save button, like every
    // other setting here — flipping the toggle alone changes
    // nothing until Save. Default false: the normal pipeline
    // (system SpeechRecognizer listening + Gemini text + Swara speaking) stays
    // default. The service gates Live start on provider==GEMINI
    // + a saved Gemini key + a live mic loop, so a stale true
    // here can never start Live on its own.
    var liveModeEnabled: Boolean
        get() = prefs.getBoolean("live_mode_enabled", false)
        set(value) = prefs.edit().putBoolean("live_mode_enabled", value).apply()

    // Captcha auto-solve (DESIGN §1.1, D3 LOCKED 2026-11-22): one-time
    // "always allow", default FALSE. The solver NEVER runs unless
    // this is true at the moment the challenge is detected — the
    // flag is re-read on every challenge, never cached across tasks,
    // so revoking in Settings → AI Brain takes effect immediately,
    // including mid-task. Grant happens only deliberately in
    // Settings (no in-context mid-task grant prompt, §1.2).
    var captchaAutoSolveAllowed: Boolean
        get() = prefs.getBoolean("captcha_auto_solve_allowed", false)
        set(value) = prefs.edit().putBoolean("captcha_auto_solve_allowed", value).apply()

    // 1.6.0 (Phase 2, 2b-1): control-plane pairing status — flag +
    // metadata ONLY. No secrets in plain prefs, ever: the Ed25519
    // private key lives in the Android Keystore (or encrypted prefs
    // on API < 31) and the pending pairing token in
    // EncryptedSharedPreferences (PairingManager). Set ONLY by the
    // pairing card's own buttons, never by the AI Brain Save button.
    var pairingPaired: Boolean
        get() = prefs.getBoolean("pairing_paired", false)
        set(value) = prefs.edit().putBoolean("pairing_paired", value).apply()

    var pairingDeviceId: String
        get() = prefs.getString("pairing_device_id", "") ?: ""
        set(value) = prefs.edit().putString("pairing_device_id", value).apply()

    var pairingPairedAtMs: Long
        get() = prefs.getLong("pairing_paired_at_ms", 0L)
        set(value) = prefs.edit().putLong("pairing_paired_at_ms", value).apply()

    // 1.12.0 FCM: last FCM token registered with the hub (plain prefs —
    // the token is useless without the hub's server key, and Firebase
    // keeps its own copy in prefs anyway). Used only to skip
    // re-registering an unchanged token.
    var fcmToken: String
        get() = prefs.getString("fcm_token", "") ?: ""
        set(value) = prefs.edit().putString("fcm_token", value).apply()

    var fcmRegisteredAtMs: Long
        get() = prefs.getLong("fcm_registered_at_ms", 0L)
        set(value) = prefs.edit().putLong("fcm_registered_at_ms", value).apply()

    // 1.15.0 Gecko REMOVED (redesign R0): the browser_engine pref is
    // gone — the browser is System WebView only, no engine toggle.
    // Old installs keep a stale "browser_engine" value in prefs;
    // nothing reads it any more (harmless, never migrated).

    companion object {
        // Dead model IDs that return 404 today, mapped to their live
        // replacement. This is the bug that broke existing installs: the
        // old APK saved gemini-2.5-flash / llama-3.3-70b-versatile into
        // SharedPreferences, so merely changing the default above would
        // NEVER fix a phone that already saved the dead ID.
        private val DEAD_MODEL_MAP = mapOf(
            "gemini-2.5-flash" to "gemini-3.8-flash",
            "gemini-2.5-flash-lite" to "gemini-3.5-flash-lite",
            "gemini-2.0-flash" to "gemini-3.8-flash",
            "gemini-2.0-flash-lite" to "gemini-3.5-flash-lite",
            "gemini-1.5-flash" to "gemini-3.8-flash",
            "gemini-1.5-pro" to "gemini-3.8-flash",
            "llama-3.3-70b-versatile" to "openai/gpt-oss-120b",
            "llama-3.1-8b-instant" to "openai/gpt-oss-20b",
            "google/gemini-2.5-flash" to "google/gemini-3.8-flash",
            "google/gemini-2.5-flash-lite" to "google/gemini-3.5-flash-lite",
            "google/gemini-2.0-flash" to "google/gemini-3.8-flash"
        )
    }

    var modelName: String
        get() {
            val saved = prefs.getString("model_name", provider.defaultModel)
                ?.trim().orEmpty().removePrefix("models/")
            val migrated = DEAD_MODEL_MAP[saved] ?: saved
            val resolved = when {
                migrated.isBlank() -> provider.defaultModel
                // 1.20.0: the removed on-device model's ID. A phone
                // that had Local selected saved this as model_name;
                // without this guard Gemini would be called with a
                // model that does not exist on its API (404).
                migrated == "gemma3-270m-it-q8" -> provider.defaultModel
                // Cross-provider stale ID (e.g. a Gemini ID saved while on
                // Groq): that provider will 404 it, so reset to its default.
                provider == AiProvider.GEMINI &&
                    (migrated.startsWith("llama-") || migrated.startsWith("openai/") ||
                        migrated.startsWith("gpt-")) -> provider.defaultModel
                provider == AiProvider.GROQ &&
                    (migrated.startsWith("gemini-") || migrated.startsWith("google/")) ->
                    provider.defaultModel
                provider == AiProvider.OPENAI &&
                    (migrated.startsWith("gemini-") || migrated.startsWith("llama-") ||
                        migrated.startsWith("google/")) -> provider.defaultModel
                provider == AiProvider.OPENROUTER && !migrated.contains("/") ->
                    provider.defaultModel
                else -> migrated
            }
            if (resolved != saved) {
                prefs.edit().putString("model_name", resolved).apply()
            }
            return resolved
        }
        set(value) {
            val cleaned = value.trim().removePrefix("models/")
            prefs.edit().putString(
                "model_name",
                if (cleaned.isBlank()) provider.defaultModel else cleaned
            ).apply()
        }

    var languageMode: String
        get() = prefs.getString("language_mode", "Hinglish") ?: "Hinglish"
        set(value) = prefs.edit().putString("language_mode", value).apply()

    var personaPrompt: String
        get() {
            val newDefault = "Tum Jarvis ho — user ki sabse close dost, playful, smart aur bilkul normal insaan jaisi baat karne wali. Hinglish me short, natural jawab do, jaise dost chat me baat karta hai. Lambi lecture, formal robot-style aur 'As an AI' wali line kabhi mat do. Mazak halka rakho, kaam ki baat seedhi rakho, aur jab user kuch personal bataye to use yaad rakhne ki koshish karo. Jawab hamesha chhota aur seedha rakho — 1-2 short sentences, no rambling, no filler; zaroori details (file names, preview lines) kabhi mat chhodo."
            val oldDefault = "Tum Jarvis ho, user ke sabse close smart dost aur autonomous mobile assistant. Natural Hinglish mein friendly, witty aur helpful tarike se baat karo ('Haan bhai', 'Bilkul, abhi karta hoon')."
            // 1.4.18: the 1.4.17 friend persona is a SECOND old
            // default — users who saved it auto-migrate to the
            // concise default above; a custom prompt is untouched.
            val prevDefault = "Tum Jarvis ho — user ki sabse close dost, playful, smart aur bilkul normal insaan jaisi baat karne wali. Hinglish me short, natural jawab do, jaise dost chat me baat karta hai. Lambi lecture, formal robot-style aur 'As an AI' wali line kabhi mat do. Mazak halka rakho, kaam ki baat seedhi rakho, aur jab user kuch personal bataye to use yaad rakhne ki koshish karo."
            val saved = prefs.getString("persona_prompt", newDefault) ?: newDefault
            // One-time migration: users who saved an old default get the
            // new concise friend persona automatically; a custom prompt is untouched.
            if (saved == oldDefault || saved == prevDefault) {
                prefs.edit().putString("persona_prompt", newDefault).apply()
                return newDefault
            }
            return saved
        }
        set(value) = prefs.edit().putString("persona_prompt", value).apply()

    // Fast mode: intermediate voice updates are OFF by default.
    // Speaking every activity aloud serialises TTS (seconds per
    // turn) in front of the next action; the chat feed still shows
    // every activity live, and the final answer / question is
    // always spoken.
    var speakIntermediateSteps: Boolean
        get() = prefs.getBoolean("speak_intermediate_steps", false)
        set(value) = prefs.edit().putBoolean("speak_intermediate_steps", value).apply()

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_word_enabled", true)
        set(value) = prefs.edit().putBoolean("wake_word_enabled", value).apply()

    var floatingOverlayEnabled: Boolean
        get() = prefs.getBoolean("floating_overlay_enabled", true)
        set(value) = prefs.edit().putBoolean("floating_overlay_enabled", value).apply()

    var confirmSensitiveActions: Boolean
        get() = prefs.getBoolean("confirm_sensitive_actions", true)
        set(value) = prefs.edit().putBoolean("confirm_sensitive_actions", value).apply()

    var visionModeEnabled: Boolean
        get() = prefs.getBoolean("vision_mode_enabled", true)
        set(value) = prefs.edit().putBoolean("vision_mode_enabled", value).apply()

    var autoAnnounceNotifications: Boolean
        get() = prefs.getBoolean("auto_announce_notifications", false)
        set(value) = prefs.edit().putBoolean("auto_announce_notifications", value).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean("auto_start_on_boot", true)
        set(value) = prefs.edit().putBoolean("auto_start_on_boot", value).apply()

    // R1 (1.16.0-PRO, N3 budgets — REDESIGN_PLAN.md §9.3):
    // the loop runs until DONE or budget/watchdog, NEVER after N
    // steps. ⚠️ NEEDS THE USER'S WORD — the numbers below are PLAN
    // DEFAULTS, not user-confirmed values (flagged here, in
    // TaskBudget.kt, and in APP_NOTES.md).
    /** Work units per task (default 10 / hard 20). */
    var workUnitsPerTask: Int
        get() = prefs.getInt("work_units_per_task", TaskBudgetDefaults.WORK_UNITS_DEFAULT)
        set(value) = prefs.edit().putInt(
            "work_units_per_task",
            value.coerceIn(1, TaskBudgetDefaults.WORK_UNITS_HARD)
        ).apply()
    /** LLM calls per task (default 20 / hard 40). */
    var llmCallsPerTask: Int
        get() = prefs.getInt("llm_calls_per_task", TaskBudgetDefaults.LLM_CALLS_DEFAULT)
        set(value) = prefs.edit().putInt(
            "llm_calls_per_task",
            value.coerceIn(1, TaskBudgetDefaults.LLM_CALLS_HARD)
        ).apply()
    /** Watchdog minutes per task (default 15). */
    var taskWatchdogMinutes: Int
        get() = prefs.getInt("task_watchdog_minutes", TaskBudgetDefaults.WATCHDOG_MINUTES)
        set(value) = prefs.edit().putInt(
            "task_watchdog_minutes", value.coerceIn(1, 60)
        ).apply()
    /** Fix attempts per work unit (default 2). */
    var fixAttemptsPerUnit: Int
        get() = prefs.getInt("fix_attempts_per_unit", TaskBudgetDefaults.FIX_ATTEMPTS_PER_UNIT)
        set(value) = prefs.edit().putInt(
            "fix_attempts_per_unit", value.coerceIn(0, 5)
        ).apply()

    /**
     * DEPRECATED in R1 — kept only so an old stored value never
     * breaks a read. Nothing in 1.16.0 reads this: the loop is
     * budget-gated (TaskBudget), never step-counted. The "max_steps"
     * key stays in storage unread; the Settings slider that wrote
     * it is removed in MainActivity.
     */
    @Deprecated("R1: step-count stopping is gone — use TaskBudget via workUnitsPerTask / llmCallsPerTask")
    var maxAutonomousSteps: Int
        get() = prefs.getInt("max_steps", 25)
        set(value) = prefs.edit().putInt("max_steps", value.coerceIn(5, 100)).apply()

    // Internal pacing between turns — NOT a step count. The key
    // stays "step_delay_ms" for storage compat; the Settings label
    // is reworded ("Kaam ke beech rukawat").
    var stepDelayMs: Long
        get() {
            val v = prefs.getLong("step_delay_ms", 650L)
            if (v == 1400L) {
                prefs.edit().putLong("step_delay_ms", 650L).apply()
                return 650L
            }
            return v
        }
        set(value) = prefs.edit().putLong("step_delay_ms", value.coerceIn(300L, 5000L)).apply()

    // Persistent chat memory: the last 80 turns survive app restarts, so
    // Jarvis does not forget the conversation (or the user) on relaunch.
    // 1.15.0 memory-loss fix (F4): persisted cap 30 -> 80, aligned with
    // the in-memory cap (JarvisStateRepository.addChatMessage) — turns
    // 31-80 used to vanish on every restart by design. F3: commit(),
    // not apply() — the chat key is the one the user sees, so the
    // write must be on disk before the next line is spoken, even on
    // force-stop right after a reply. Other prefs keep apply().
    // ChatEntry lives in this same package, so no import is needed.
    // R2.6 (1.17.0-PRO): chat / friend memories / voice routines
    // live in the Jarvis Store once migrated (single on-device
    // file). Same public API, same caps, same F3 commit() guarantee
    // (the Store write is atomic tmp + rename + fsync). Pre-migration
    // launches keep the prefs path untouched.
    fun getSavedChatHistory(): List<ChatEntry> {
        if (JarvisStore.isMigrated()) {
            val arr = JarvisStore.getSection(JarvisStore.SEC_CHAT) as? JSONArray
                ?: return emptyList()
            return runCatching {
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    ChatEntry(
                        sender = obj.optString("sender", "Jarvis"),
                        message = obj.optString("message", "")
                    )
                }.filter { it.message.isNotBlank() }
            }.getOrDefault(emptyList())
        }
        val raw = prefs.getString("chat_history_json", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ChatEntry(
                    sender = obj.optString("sender", "Jarvis"),
                    message = obj.optString("message", "")
                )
            }.filter { it.message.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun saveChatHistory(entries: List<ChatEntry>) {
        val arr = JSONArray()
        entries.takeLast(80).forEach { e ->
            arr.put(JSONObject().apply {
                put("sender", e.sender)
                put("message", e.message)
            })
        }
        if (JarvisStore.isMigrated()) {
            JarvisStore.setSection(JarvisStore.SEC_CHAT, arr)
            return
        }
        prefs.edit().putString("chat_history_json", arr.toString()).commit()
    }

    // Long-term Friend Memory Notes
    fun getMemories(): List<String> {
        if (JarvisStore.isMigrated()) {
            val arr = JarvisStore.getSection(JarvisStore.SEC_FRIEND_MEMORIES) as? JSONArray
            if (arr == null) {
                return listOf(
                    "User prefers friendly Hinglish conversation",
                    "Ask before clicking Pay Now or sending money"
                )
            }
            return runCatching {
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrDefault(emptyList())
        }
        val raw = prefs.getString(
            "friend_memories",
            """["User prefers friendly Hinglish conversation", "Ask before clicking Pay Now or sending money"]"""
        ) ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun addMemory(note: String) {
        if (note.isBlank()) return
        val updated = (getMemories() + note.trim()).distinct()
        val arr = JSONArray()
        updated.forEach { arr.put(it) }
        if (JarvisStore.isMigrated()) {
            JarvisStore.setSection(JarvisStore.SEC_FRIEND_MEMORIES, arr)
            return
        }
        prefs.edit().putString("friend_memories", arr.toString()).apply()
    }

    fun removeMemory(note: String) {
        val updated = getMemories().filterNot { it == note }
        val arr = JSONArray()
        updated.forEach { arr.put(it) }
        if (JarvisStore.isMigrated()) {
            JarvisStore.setSection(JarvisStore.SEC_FRIEND_MEMORIES, arr)
            return
        }
        prefs.edit().putString("friend_memories", arr.toString()).apply()
    }

    // Custom Voice Routines (Macros)
    fun getRoutines(): List<VoiceRoutine> {
        val defaultJson = """
            [
              {"id":"1","trigger":"good morning","prompt":"Mujhe aaj ka motivational quote sunao aur phir WhatsApp khol kar mere unread messages check karo"},
              {"id":"2","trigger":"reels mode","prompt":"Instagram kholo, Reels tab par tap karo aur har 8 second mein niche scroll karte raho"},
              {"id":"3","trigger":"focus mode","prompt":"Phone ka volume kam karo aur YouTube khol kar Lo-Fi study music search karke play karo"}
            ]
        """.trimIndent()
        val raw = if (JarvisStore.isMigrated()) {
            (JarvisStore.getSection(JarvisStore.SEC_VOICE_ROUTINES) as? JSONArray)?.toString()
                ?: defaultJson
        } else {
            prefs.getString("voice_routines", defaultJson) ?: defaultJson
        }
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                VoiceRoutine(
                    id = obj.optString("id", i.toString()),
                    triggerPhrase = obj.getString("trigger"),
                    actionPrompt = obj.getString("prompt")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addRoutine(trigger: String, prompt: String) {
        if (trigger.isBlank() || prompt.isBlank()) return
        val current = getRoutines().toMutableList()
        current.add(
            VoiceRoutine(
                id = System.currentTimeMillis().toString(),
                triggerPhrase = trigger.trim().lowercase(),
                actionPrompt = prompt.trim()
            )
        )
        saveRoutines(current)
    }

    fun removeRoutine(id: String) {
        saveRoutines(getRoutines().filterNot { it.id == id })
    }

    private fun saveRoutines(list: List<VoiceRoutine>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("trigger", r.triggerPhrase)
                    put("prompt", r.actionPrompt)
                }
            )
        }
        if (JarvisStore.isMigrated()) {
            JarvisStore.setSection(JarvisStore.SEC_VOICE_ROUTINES, arr)
            return
        }
        prefs.edit().putString("voice_routines", arr.toString()).apply()
    }
}
