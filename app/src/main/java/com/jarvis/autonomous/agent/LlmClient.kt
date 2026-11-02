package com.jarvis.autonomous.agent

import android.content.Context
import com.jarvis.autonomous.data.AiProvider
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.ProjectStore
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
    val browserUrl: String = "",
    val browserSpeakSummary: Boolean = true,
    val browserShowPage: Boolean = false,
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
            // Builder bypass (audit S7): the local chat path returns
            // action=NONE for EVERYTHING, so a voice website build
            // ("ek website banao") used to get a friendly chat reply
            // and NO project at all — the user saw nothing built.
            // The small local model has no structured-action path, so
            // do not fake a build: say so plainly and point at the
            // working paths (cloud AI, or manual Create in Projects).
            if (isCodeBuilderRequest(userGoal)) {
                return@withContext AgentDecision(
                    reply = "Website banana local model se nahi hoga — iske liye AI Brain me Gemini ya Groq chuno, phir dobara bolo; ya Projects tab me Create Website Project dabao aur file khud edit karo.",
                    thought = "Local provider cannot emit project actions",
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

    /** True when the user is asking to build or edit a website/page
     *  (English + Hinglish triggers). Used only by the LOCAL-provider
     *  guard above — cloud prompts carry the full builder rules. */
    private fun isCodeBuilderRequest(text: String): Boolean {
        val lower = text.lowercase()
        if (lower.contains("website") || lower.contains("web site") ||
            lower.contains("webpage") || lower.contains("web page") ||
            lower.contains("portfolio") || lower.contains("landing page")
        ) return true
        if (lower.contains("rang badal") || lower.contains("color badal") ||
            lower.contains("page jodo") || lower.contains("section add") ||
            lower.contains("button ka") || lower.contains("naya page")
        ) return true
        val buildVerb = lower.contains("banao") || lower.contains("bana do") ||
            lower.contains("banado") || lower.contains("banaye") ||
            lower.contains("create") || lower.contains("build")
        val buildObject = lower.contains("site") || lower.contains("page") ||
            lower.contains("app") || lower.contains("calculator") ||
            lower.contains("todo")
        return buildVerb && buildObject
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
            5. CODE BUILDER — WEBSITE BUILD (multi-step, never one-and-done): If the user asks you to make a website/app/page — Hinglish or English, e.g. "ek website banao", "website bana do", "meri portfolio/dukaan/restaurant ki site banao", "ek calculator/todo page banao", "is ki website banao" — do NOT open a browser. Build it in EXACTLY these steps, one action per step, with the SAME projectName (short, no spaces, same spelling and case) on every step:
               Step 1: action="CREATE_PROJECT", projectName=<name>, fileContent=the COMPLETE index.html (full, working, mobile-friendly, creamy theme). index.html MUST contain exactly `<link rel="stylesheet" href="style.css">` and `<script src="script.js"></script>` and NO other local file reference unless you also write that file. Set isCompleted=false on this step — the build is NOT done yet.
               Step 2: action="WRITE_PROJECT_FILE", same projectName, fileName="style.css", fileContent=the COMPLETE custom CSS for this site (all classes the HTML uses). Set isCompleted=false.
               Step 3: action="WRITE_PROJECT_FILE", same projectName, fileName="script.js", fileContent=the COMPLETE custom JS for this site (every id the HTML references must exist in the JS, every JS getElementById target must exist in the HTML). Set isCompleted=true, and your reply MUST be the build summary: files created (index.html, style.css, script.js), the project name, and "Projects tab me Preview dabao, ya Browser me kholo".
               EXCEPTION — single-file build ONLY when the user explicitly asks for one file ("sirf ek file", "single file page"): then do ONE step, action="CREATE_PROJECT" with a fully self-contained index.html (inline <style> and <script> inside the HTML, NO href="style.css" and NO src="script.js"), isCompleted=true, and say it is a single-file build. Never mix the two modes: a 3-file HTML must never reference files you did not write, and a single-file HTML must never reference external files.
            6. CODE BUILDER — VOICE MODIFY (edit in place, never rebuild): If the user asks to CHANGE something in a site that already exists — e.g. "is button ka rang badal do", "is website me ek naya page/section add kar do", "is heading ko badal do", "is page me ye feature jodo" — do NOT use CREATE_PROJECT (that would rebuild or ignore the existing project). Use action="WRITE_PROJECT_FILE" to the EXISTING project: same projectName as the project being changed (if the user named it, use that name; if they did not name it, use the most recently built project from the conversation; if two or more projects could match and the user did not name one, set action="NONE", askUser=true, isCompleted=false and ask which project). Write ONLY the file that changes, with its FULL corrected content (the app overwrites that one file; other files stay untouched). A new page means: WRITE the new page file (e.g. fileName="about.html") AND rewrite index.html with a link to it, in two steps with isCompleted=false then true. Set isCompleted=true on the last edit step and reply with exactly what changed + "preview refresh ho gaya hai, Projects tab ya Browser tab me dekho".

            AVAILABLE ACTIONS (Pick strictly ONE per step):
            - "NONE": No screen action needed (casual chat, asking user a question, or task already complete).
            - "OPEN_APP": Launch an installed app by name. Fill `appName` (e.g. "WhatsApp", "YouTube", "Instagram", "Chrome", "Spotify", "Settings").
            - "YOUTUBE_SEARCH": Directly open YouTube search results for a song/video query! Fill `text` with search query.
            - "YOUTUBE_PLAY": Search YouTube AND play the first video result automatically. Fill `text` with search query. Use this when the user said play/chalao/bajao.
            - "WHATSAPP_SEND": Open a WhatsApp chat and send a NEW message (not a notification reply). Fill `contact` (name or number) and `text` (message). Use ONLY after the user explicitly said to send this message to this person — a sent message cannot be unsent.
            - "OPEN_URL": Open any website or deep link directly. Fill `text` with URL.
            - "OPEN_BROWSER": In-app browser, never leaves the app. For ANY web question/lookup use this, never OPEN_URL. Fill `browserUrl` with the page the user named (or the exact page Jarvis proposed and the user approved). `browserShowPage=true` ONLY when the user explicitly said they want to SEE the page ("dikhao", "khol ke dikhao", "page dikhao"); otherwise false (background answer, no tab switch). `browserSpeakSummary=true` for question-answering.
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
              "action": "NONE | OPEN_APP | YOUTUBE_SEARCH | YOUTUBE_PLAY | WHATSAPP_SEND | OPEN_URL | OPEN_BROWSER | REPLY_NOTIFICATION | TAP_NODE | TAP_XY | LONG_PRESS_XY | TYPE_TEXT | SCROLL | NAVIGATE | MEDIA_CONTROL | COPY_CLIPBOARD | WAIT | FLASHLIGHT | VOLUME | CALL | SET_ALARM | SAVE_MEMORY | CREATE_PROJECT | WRITE_PROJECT_FILE",
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
              "browserUrl": "",
              "browserSpeakSummary": true,
              "browserShowPage": false,
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
        // Robust extraction: some models wrap the JSON in prose even
        // with responseMimeType/json_object. Parsing the first '{'
        // .. last '}' substring recovers the decision instead of
        // failing the whole build step with an API error.
        val obj = runCatching { JSONObject(cleaned) }.getOrElse {
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start >= 0 && end > start) JSONObject(cleaned.substring(start, end + 1)) else throw it
        }
        // Normalise project/file names through the store's ONE
        // canonical form (audit S7): raw model spellings ("Meri
        // Portfolio" vs "meri-portfolio", "style.css " vs
        // "/style.css") used to land in different folders / miss the
        // href="style.css" reference, so the preview showed defaults
        // or 404s. An unsafe fileName falls back to index.html
        // instead of creating a traversal/odd file.
        val rawProject = obj.optString("projectName", "")
        val rawFile = obj.optString("fileName", "")
        val projectName = if (rawProject.isBlank()) "" else ProjectStore.sanitizeProjectName(rawProject)
        val fileName = if (rawFile.isBlank()) "" else (ProjectStore.sanitizeFileName(rawFile) ?: "index.html")
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
            projectName = projectName,
            fileName = fileName,
            fileContent = obj.optString("fileContent", ""),
            browserUrl = obj.optString("browserUrl", ""),
            browserSpeakSummary = obj.optBoolean("browserSpeakSummary", true),
            browserShowPage = obj.optBoolean("browserShowPage", false),
            askUser = obj.optBoolean("askUser", false),
            isCompleted = obj.optBoolean("isCompleted", true)
        )
    }
}
