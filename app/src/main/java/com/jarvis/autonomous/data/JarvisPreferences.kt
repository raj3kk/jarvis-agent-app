package com.jarvis.autonomous.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class AiProvider(val displayName: String, val defaultModel: String, val baseUrl: String) {
    GEMINI("Google Gemini (Recommended)", "gemini-2.5-flash", "https://generativelanguage.googleapis.com/v1beta"),
    OPENAI("OpenAI (GPT-4o)", "gpt-4o-mini", "https://api.openai.com/v1/chat/completions"),
    GROQ("Groq (Ultra-Fast Llama 3.3)", "llama-3.3-70b-versatile", "https://api.groq.com/openai/v1/chat/completions"),
    OPENROUTER("OpenRouter / Custom", "google/gemini-2.5-flash", "https://openrouter.ai/api/v1/chat/completions")
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

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var modelName: String
        get() = prefs.getString("model_name", provider.defaultModel) ?: provider.defaultModel
        set(value) = prefs.edit().putString("model_name", value.trim()).apply()

    var languageMode: String
        get() = prefs.getString("language_mode", "Hinglish") ?: "Hinglish"
        set(value) = prefs.edit().putString("language_mode", value).apply()

    var personaPrompt: String
        get() = prefs.getString(
            "persona_prompt",
            "Tum Jarvis ho, user ke sabse close smart dost aur autonomous mobile assistant. Natural Hinglish mein friendly, witty aur helpful tarike se baat karo ('Haan bhai', 'Bilkul, abhi karta hoon')."
        ) ?: ""
        set(value) = prefs.edit().putString("persona_prompt", value).apply()

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
        get() = prefs.getLong("step_delay_ms", 1400L)
        set(value) = prefs.edit().putLong("step_delay_ms", value.coerceIn(500L, 5000L)).apply()

    var voiceSpeed: Float
        get() = prefs.getFloat("voice_speed", 1.05f)
        set(value) = prefs.edit().putFloat("voice_speed", value.coerceIn(0.6f, 1.8f)).apply()

    var voicePitch: Float
        get() = prefs.getFloat("voice_pitch", 0.95f)
        set(value) = prefs.edit().putFloat("voice_pitch", value.coerceIn(0.6f, 1.5f)).apply()

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
