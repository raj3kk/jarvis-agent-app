package com.jarvis.autonomous.data

import android.content.Context
import android.content.SharedPreferences
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
    // FREE on-device model: Gemma 3 270M Q8 via LiteRT-LM.
    // No card, no server — model downloads once (~304 MB) from
    // Hugging Face into the app's own files. GATED: needs the user's
    // own HF token (account must have accepted the Gemma licence).
    // CHAT ONLY: a 270M model cannot reliably emit the strict JSON
    // agent actions, so phone actions stay on a cloud Lite provider.
    LOCAL(
        "Local Gemma3-270M (Free, Offline Chat)",
        "gemma3-270m-it-q8",
        "",
        emptyList()
    )
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

    // Hugging Face token for the gated Gemma download only. Stored
    // on the phone, never in the APK or repo.
    var hfToken: String
        get() = prefs.getString("hf_token", "") ?: ""
        set(value) = prefs.edit().putString("hf_token", value.trim()).apply()

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
    // (Deepgram listening + Gemini text + Swara speaking) stays
    // default. The service gates Live start on provider==GEMINI
    // + a saved Gemini key + a live mic loop, so a stale true
    // here can never start Live on its own.
    var liveModeEnabled: Boolean
        get() = prefs.getBoolean("live_mode_enabled", false)
        set(value) = prefs.edit().putBoolean("live_mode_enabled", value).apply()

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

    // Fast mode: intermediate multi-step voice updates are OFF by default.
    // Speaking every step aloud serialises TTS (seconds per step) in front
    // of the next action; the chat feed still shows every step live, and
    // the final answer / question is always spoken.
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

    var maxAutonomousSteps: Int
        get() = prefs.getInt("max_steps", 25)
        set(value) = prefs.edit().putInt("max_steps", value.coerceIn(5, 100)).apply()

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

    // Persistent chat memory: the last 30 turns survive app restarts, so
    // Jarvis does not forget the conversation (or the user) on relaunch.
    // ChatEntry lives in this same package, so no import is needed.
    fun getSavedChatHistory(): List<ChatEntry> {
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
        entries.takeLast(30).forEach { e ->
            arr.put(JSONObject().apply {
                put("sender", e.sender)
                put("message", e.message)
            })
        }
        prefs.edit().putString("chat_history_json", arr.toString()).apply()
    }

    // Long-term Friend Memory Notes
    fun getMemories(): List<String> {
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
        prefs.edit().putString("friend_memories", arr.toString()).apply()
    }

    fun removeMemory(note: String) {
        val updated = getMemories().filterNot { it == note }
        val arr = JSONArray()
        updated.forEach { arr.put(it) }
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
        val raw = prefs.getString("voice_routines", defaultJson) ?: defaultJson
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
        prefs.edit().putString("voice_routines", arr.toString()).apply()
    }
}
