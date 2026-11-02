package com.jarvis.autonomous.agent

import android.content.Context
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
    val projectName: String = "",
    val fileName: String = "",
    val fileContent: String = "",
    val askUser: Boolean = false,
    val isCompleted: Boolean = true
)

class LlmClient(private val context: Context, private val prefs: JarvisPreferences) {

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
        // LOCAL fast path: casual chat answered by the on-device model
        // with NO network call at all. This is the delay fix for chat:
        // cloud calls wait on network + a big model; local answers are
        // generated on the phone itself after the one-time load.
        if (prefs.provider == AiProvider.LOCAL) {
            if (!LocalLlmManager.isDownloaded(context)) {
                return@withContext AgentDecision(
                    reply = "Bhai, local model abhi download nahi hua — AI Brain me 'Download Free Local Model' dabao (ek baar, ~475 MB), phir main bina internet ke baat karunga. Tab tak Gemini/Groq chuno.",
                    thought = "Local model not downloaded",
                    action = "NONE",
                    isCompleted = true
                )
            }
            return@withContext try {
                val history = recentChat.takeLast(6).mapNotNull { line ->
                    val idx = line.indexOf(": ")
                    if (idx > 0) line.substring(0, idx) to line.substring(idx + 2) else null
                }
                val reply = LocalLlmManager.chat(
                    context = context,
                    systemPrompt = prefs.personaPrompt,
                    userText = userGoal,
                    recentChat = history
                )
                AgentDecision(reply = reply, thought = "Local Gemma chat", action = "NONE", isCompleted = true)
            } catch (e: Exception) {
                AgentDecision(
                    reply = "Local model se jawab nahi aaya (${(e.message ?: "").take(120)}). AI Brain me dobara Gemini chuno ya model reload karo.",
                    thought = "Local LLM error: ${e.message}",
                    action = "NONE",
                    isCompleted = true
                )
            }
        }

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
        // Speed: a full 120-element screen tree is ~10k chars of tokens on
        // EVERY step. Cap it — the agent only acts on the elements it can
        // actually see in the prompt, so unseen tail nodes are useless cost.
        val trimmedTree = if (screenTree.length > 8000) {
            screenTree.take(8000) + "\n[Screen tree truncated for speed]"
        } else {
            screenTree
        }
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
            appendLine(trimmedTree)
        }

        return@withContext try {
            // 404 fallback chain: if the chosen model is dead/overloaded
            // (404/503), try the provider's verified fallbacks before giving
            // up, so one retired model ID can never brick the whole agent.
            val candidates = (listOf(prefs.modelName) + prefs.provider.fallbackModels)
                .map { it.trim().removePrefix("models/") }
                .filter { it.isNotBlank() }.distinct()
            var lastError: Exception? = null
            var rawJson: String? = null
            for (candidate in candidates) {
                try {
                    rawJson = when (prefs.provider) {
                        AiProvider.GEMINI -> callGeminiApi(
                            apiKey = apiKey,
                            model = candidate,
                            systemPrompt = systemInstruction,
                            userPrompt = userPayload,
                            imageBase64 = screenImageBase64
                        )
                        else -> callOpenAiCompatibleApi(
                            provider = prefs.provider,
                            baseUrl = prefs.provider.baseUrl,
                            apiKey = apiKey,
                            model = candidate,
                            systemPrompt = systemInstruction,
                            userPrompt = userPayload
                        )
                    }
                    break
                } catch (e: IllegalStateException) {
                    val isModelGone = e.message?.contains("HTTP 404") == true ||
                        e.message?.contains("HTTP 503") == true
                    lastError = e
                    if (!isModelGone || candidate == candidates.last()) throw e
                    // else: fall through to the next candidate model
                }
            }
            parseDecisionJson(rawJson ?: throw (lastError ?: IllegalStateException("No model responded")))
        } catch (e: Exception) {
            // Do NOT truncate the error to 60 chars any more: that hid the
            // "404 model not found" detail that would have named the fix.
            val detail = (e.message ?: "Unknown error").take(300)
            val hint = if (detail.contains("404")) {
                " Model nahi mila — AI Brain me provider ka default model check karo."
            } else ""
            AgentDecision(
                reply = "API error: $detail.$hint",
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
            5. CODE BUILDER: If the user asks you to make a website/app/page (e.g. "is ki website banao", "ek portfolio site banao", "ek calculator page banao"), do NOT open a browser. Use action="CREATE_PROJECT" first with `projectName` (short, no spaces) and `fileContent` = the complete index.html for that site (full, working, mobile-friendly, creamy theme, with style.css/script.js links — the app already provides default style.css/script.js if you only write index.html). Then, if needed, use action="WRITE_PROJECT_FILE" with `projectName`, `fileName` ("style.css" or "script.js") and `fileContent` for custom CSS/JS. Each project is saved in its OWN folder, and the user previews it on localhost inside the app (Projects tab). Never mix two projects into one folder.

            AVAILABLE ACTIONS (Pick strictly ONE per step):
            - "NONE": No screen action needed (casual chat, asking user a question, or task already complete).
            - "OPEN_APP": Launch an installed app by name. Fill `appName` (e.g. "WhatsApp", "YouTube", "Instagram", "Chrome", "Spotify", "Settings").
            - "YOUTUBE_SEARCH": Directly open YouTube search results for a song/video query! Fill `text` with search query.
            - "YOUTUBE_PLAY": Search YouTube AND play the first video result automatically. Fill `text` with search query. Use this when the user said play/chalao/bajao.
            - "WHATSAPP_SEND": Open a WhatsApp chat and send a NEW message (not a notification reply). Fill `contact` (name or number) and `text` (message). Use ONLY after the user explicitly said to send this message to this person — a sent message cannot be unsent.
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
            - "CREATE_PROJECT": Create a new website/app project in its own folder. Fill `projectName` and `fileContent` (complete index.html).
            - "WRITE_PROJECT_FILE": Write/overwrite one file inside a project. Fill `projectName`, `fileName`, `fileContent`.

            OUTPUT FORMAT:
            Respond ONLY with a valid JSON object matching this exact schema (no markdown fences):
            {
              "reply": "Short natural spoken response in ${prefs.languageMode} — talk exactly like a close playful friend, 1-2 short sentences for step updates, never a lecture, never 'As an AI'",
              "thought": "Your internal reasoning for this step",
              "action": "NONE | OPEN_APP | YOUTUBE_SEARCH | YOUTUBE_PLAY | WHATSAPP_SEND | OPEN_URL | REPLY_NOTIFICATION | TAP_NODE | TAP_XY | LONG_PRESS_XY | TYPE_TEXT | SCROLL | NAVIGATE | MEDIA_CONTROL | COPY_CLIPBOARD | WAIT | FLASHLIGHT | VOLUME | CALL | SET_ALARM | SAVE_MEMORY | CREATE_PROJECT | WRITE_PROJECT_FILE",
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
              "projectName": "",
              "fileName": "",
              "fileContent": "",
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
                throw IllegalStateException("HTTP ${resp.code}: ${respBody.take(400)}")
            }
            val root = JSONObject(respBody)
            val candidates = root.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                throw IllegalStateException(
                    "HTTP 200 but no candidates: ${respBody.take(400)}"
                )
            }
            return candidates
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        }
    }

    private fun callOpenAiCompatibleApi(
        provider: AiProvider,
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
            // Reasoning models (OpenAI GPT-6 family) reject temperature on
            // Chat Completions unless reasoning is off; keep them at
            // reasoning_effort=none so the old JSON contract still works.
            if (provider == AiProvider.OPENAI && model.startsWith("gpt-6")) {
                put("reasoning_effort", "none")
            } else {
                put("temperature", 0.25)
            }
            put("response_format", JSONObject().put("type", "json_object"))
        }

        val reqBuilder = Request.Builder()
            .url(baseUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
        if (provider == AiProvider.OPENROUTER) {
            // OpenRouter identifies the calling app from these headers.
            reqBuilder.addHeader("HTTP-Referer", "https://github.com/raj3kk/jarvis-agent-app")
            reqBuilder.addHeader("X-Title", "JarvisAgentApp")
        }
        val req = reqBuilder
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: ${respBody.take(400)}")
            }
            val root = JSONObject(respBody)
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                throw IllegalStateException(
                    "HTTP 200 but no choices: ${respBody.take(400)}"
                )
            }
            return choices
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
            projectName = obj.optString("projectName", ""),
            fileName = obj.optString("fileName", ""),
            fileContent = obj.optString("fileContent", ""),
            askUser = obj.optBoolean("askUser", false),
            isCompleted = obj.optBoolean("isCompleted", true)
        )
    }
}
