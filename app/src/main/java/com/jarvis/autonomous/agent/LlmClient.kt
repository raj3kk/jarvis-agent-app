package com.jarvis.autonomous.agent

import com.jarvis.autonomous.data.AiProvider
import com.jarvis.autonomous.data.JarvisPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AgentDecision(
    val reply: String,
    val thought: String,
    val action: String,
    val appName: String = "",
    val nodeId: Int = -1,
    val x: Int = -1,
    val y: Int = -1,
    val text: String = "",
    val pressEnter: Boolean = true,
    val direction: String = "DOWN",
    val navTarget: String = "BACK",
    val enableFlag: Boolean = true,
    val volumePercent: Int = 50,
    val contact: String = "",
    val alarmHour: Int = 7,
    val alarmMinute: Int = 0,
    val memoryNote: String = "",
    val askUser: Boolean = false,
    val isCompleted: Boolean = true
)

class LlmClient(private val prefs: JarvisPreferences) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun decideNextStep(
        userGoal: String,
        stepNumber: Int,
        previousActions: List<String>,
        recentChat: List<String>,
        recentNotifications: List<String>,
        screenTree: String,
        hasSensitivePaymentScreen: Boolean,
        isStuckWarning: Boolean = false,
        screenImageBase64: String? = null
    ): AgentDecision = withContext(Dispatchers.IO) {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            return@withContext AgentDecision(
                reply = "Bhai, pehle AI Brain tab mein jaake apni Gemini ya OpenAI/Groq API Key daal de taaki mera dimaag full power pe chal sake!",
                thought = "API key is missing in preferences",
                action = "NONE",
                askUser = false,
                isCompleted = true
            )
        }

        val systemInstruction = buildSystemPrompt()
        val userPayload = buildString {
            appendLine("=== CURRENT USER GOAL / MESSAGE ===")
            appendLine(userGoal)
            appendLine()
            appendLine("=== STEP NUMBER: $stepNumber (Max ${prefs.maxAutonomousSteps}) ===")
            if (previousActions.isNotEmpty()) {
                appendLine("=== PREVIOUS STEPS TAKEN IN THIS TASK ===")
                previousActions.takeLast(8).forEach { appendLine("- $it") }
                appendLine()
            }
            if (isStuckWarning) {
                appendLine("⚠️ STUCK RECOVERY ALERT: Your last action did NOT change the screen! Do NOT repeat the exact same TAP_NODE. Try TAP_XY with exact coordinates, SCROLL DOWN, or NAVIGATE BACK!")
                appendLine()
            }
            if (recentChat.isNotEmpty()) {
                appendLine("=== RECENT CONVERSATION CONTEXT ===")
                recentChat.takeLast(6).forEach { appendLine(it) }
                appendLine()
            }
            if (recentNotifications.isNotEmpty()) {
                appendLine("=== RECENT PHONE NOTIFICATIONS (Can use REPLY_NOTIFICATION to reply directly!) ===")
                recentNotifications.takeLast(5).forEach { appendLine(it) }
                appendLine()
            }
            if (hasSensitivePaymentScreen && prefs.confirmSensitiveActions) {
                appendLine("⚠️ WARNING: Current screen contains a PAYMENT / PIN / SENSITIVE action! You MUST set askUser=true and ask the user for confirmation before clicking Pay/Confirm!")
                appendLine()
            }
            appendLine("=== LIVE ANDROID SCREEN UI HIERARCHY ===")
            appendLine(screenTree)
        }

        return@withContext try {
            val rawJson = when (prefs.provider) {
                AiProvider.GEMINI -> callGeminiApi(
                    apiKey = apiKey,
                    model = prefs.modelName,
                    systemPrompt = systemInstruction,
                    userPrompt = userPayload,
                    imageBase64 = screenImageBase64
                )
                else -> callOpenAiCompatibleApi(
                    baseUrl = prefs.provider.baseUrl,
                    apiKey = apiKey,
                    model = prefs.modelName,
                    systemPrompt = systemInstruction,
                    userPrompt = userPayload
                )
            }
            parseDecisionJson(rawJson)
        } catch (e: Exception) {
            AgentDecision(
                reply = "Arre bhai, network ya API mein thoda glitch aaya: ${e.localizedMessage?.take(60)}",
                thought = "Exception: ${e.message}",
                action = "NONE",
                askUser = false,
                isCompleted = true
            )
        }
    }

    private fun buildSystemPrompt(): String {
        val memories = prefs.getMemories().joinToString("; ")
        return """
            ${prefs.personaPrompt}
            Preferred Language: ${prefs.languageMode}.
            User's Long-Term Memory Notes: [$memories]

            You are JARVIS, an autonomous Android OS agent with full Accessibility Service control, Multimodal Vision Sight, AND a warm, friendly human-like companion.
            You operate in a multi-step ReAct loop:
            1. If the user is just chatting casually (e.g. "Aur bhai kya haal hai?", "Joke suna", "Mujhe neend aa rahi hai"), respond naturally like a close friend with action="NONE" and isCompleted=true.
            2. If the user gives a phone command or multi-step task (e.g. "WhatsApp khol ke Rahul ko bol main 10 min mein aa raha hoon", "YouTube pe Arijit Singh ka gaana chala", "Instagram reels scroll kar", "Flashlight jala"), execute it step-by-step until the goal is achieved!
            3. If you see ambiguity on the screen (e.g. multiple contacts with the same name, missing info, or a payment confirmation when Safe Mode is active), set action="NONE", askUser=true, isCompleted=false, and ask the user clearly in `reply`.
            4. If the user tells you a personal fact to remember, use action="SAVE_MEMORY" with `memoryNote`.

            AVAILABLE ACTIONS (Pick strictly ONE per step):
            - "NONE": No screen action needed (casual chat, asking user a question, or task already complete).
            - "OPEN_APP": Launch an installed app by name. Fill `appName` (e.g. "WhatsApp", "YouTube", "Instagram", "Chrome", "Spotify", "Settings").
            - "YOUTUBE_SEARCH": Directly open YouTube search results for a song/video query! Fill `text` with search query.
            - "OPEN_URL": Open any website or deep link directly. Fill `text` with URL.
            - "REPLY_NOTIFICATION": Reply directly in background to a recent WhatsApp/Instagram/SMS notification without opening the app! Fill `contact` (sender name filter) and `text` (reply message).
            - "TAP_NODE": Click a UI element from the live screen list by its integer `[ID]`. Fill `nodeId`.
            - "TAP_XY": Tap exact screen coordinates `(x, y)`. Fill `x` and `y`.
            - "LONG_PRESS_XY": Long-press screen coordinates `(x, y)`. Fill `x` and `y`.
            - "TYPE_TEXT": Type text into an input box. Fill `nodeId` (or -1 for focused field), `text`, and `pressEnter` (true/false).
            - "SCROLL": Scroll the screen. Fill `direction` ("DOWN", "UP", "LEFT", "RIGHT").
            - "NAVIGATE": System navigation. Fill `navTarget` ("BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS", "SCREENSHOT").
            - "MEDIA_CONTROL": Control music/video playback in background. Fill `text` ("PLAY_PAUSE", "NEXT", "PREVIOUS").
            - "COPY_CLIPBOARD": Copy text to system clipboard. Fill `text`.
            - "WAIT": Wait 2 seconds for screen/app loading.
            - "FLASHLIGHT": Turn torch on/off. Fill `enableFlag` (true/false).
            - "VOLUME": Set media volume percentage. Fill `volumePercent` (0 to 100).
            - "CALL": Make a direct phone call. Fill `contact` (name or phone number).
            - "SET_ALARM": Set system alarm. Fill `alarmHour` (0-23), `alarmMinute` (0-59), `text` (label).
            - "SAVE_MEMORY": Save a fact to long-term friend memory. Fill `memoryNote`.

            OUTPUT FORMAT:
            Respond ONLY with a valid JSON object matching this exact schema (no markdown fences):
            {
              "reply": "Short natural spoken response in ${prefs.languageMode} (keep step updates brief, friendly & natural)",
              "thought": "Your internal reasoning for this step",
              "action": "NONE | OPEN_APP | YOUTUBE_SEARCH | OPEN_URL | REPLY_NOTIFICATION | TAP_NODE | TAP_XY | LONG_PRESS_XY | TYPE_TEXT | SCROLL | NAVIGATE | MEDIA_CONTROL | COPY_CLIPBOARD | WAIT | FLASHLIGHT | VOLUME | CALL | SET_ALARM | SAVE_MEMORY",
              "appName": "",
              "nodeId": -1,
              "x": -1,
              "y": -1,
              "text": "",
              "pressEnter": true,
              "direction": "DOWN",
              "navTarget": "BACK",
              "enableFlag": true,
              "volumePercent": 50,
              "contact": "",
              "alarmHour": 7,
              "alarmMinute": 0,
              "memoryNote": "",
              "askUser": false,
              "isCompleted": false
            }
        """.trimIndent()
    }

    private fun callGeminiApi(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imageBase64: String? = null
    ): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val userParts = JSONArray().apply {
            put(JSONObject().put("text", userPrompt))
            if (!imageBase64.isNullOrBlank()) {
                put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", "image/jpeg")
                            .put("data", imageBase64)
                    )
                )
            }
        }
        val bodyJson = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", userParts)
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.25)
                put("responseMimeType", "application/json")
            })
        }

        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: ${respBody.take(160)}")
            }
            val root = JSONObject(respBody)
            return root.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        }
    }

    private fun callOpenAiCompatibleApi(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String
    ): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userPrompt))

        val bodyJson = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.25)
            put("response_format", JSONObject().put("type", "json_object"))
        }

        val req = Request.Builder()
            .url(baseUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: ${respBody.take(160)}")
            }
            val root = JSONObject(respBody)
            return root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    private fun parseDecisionJson(raw: String): AgentDecision {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val obj = JSONObject(cleaned)
        return AgentDecision(
            reply = obj.optString("reply", ""),
            thought = obj.optString("thought", ""),
            action = obj.optString("action", "NONE").uppercase(),
            appName = obj.optString("appName", ""),
            nodeId = obj.optInt("nodeId", -1),
            x = obj.optInt("x", -1),
            y = obj.optInt("y", -1),
            text = obj.optString("text", ""),
            pressEnter = obj.optBoolean("pressEnter", true),
            direction = obj.optString("direction", "DOWN"),
            navTarget = obj.optString("navTarget", "BACK"),
            enableFlag = obj.optBoolean("enableFlag", true),
            volumePercent = obj.optInt("volumePercent", 50),
            contact = obj.optString("contact", ""),
            alarmHour = obj.optInt("alarmHour", 7),
            alarmMinute = obj.optInt("alarmMinute", 0),
            memoryNote = obj.optString("memoryNote", ""),
            askUser = obj.optBoolean("askUser", false),
            isCompleted = obj.optBoolean("isCompleted", true)
        )
    }
}
