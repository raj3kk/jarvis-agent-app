package com.jarvis.autonomous.agent

import android.content.Context
import com.jarvis.autonomous.data.AiProvider
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.data.ProjectStore
import com.jarvis.autonomous.data.WebsiteBuildSpec
import kotlinx.coroutines.CancellationException
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
    val isCompleted: Boolean = true,
    // A-C6: explicit decision kind. "OK" = normal; "ERROR" = API /
    // config failure (never complete, never persist, never index);
    // "PARSE_ERROR" = truncated / multi-object JSON (retry once);
    // "RATE_LIMITED" = HTTP 429 (distinct, retry with backoff).
    val decisionKind: String = "OK"
)

/** Thrown ONLY by strict JSON parsing (A-M2): truncation or a
 *  second merged object. Never converted into a partial action. */
class DecisionParseException(message: String) : IllegalArgumentException(message)

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
        answeredClarifications: List<String> = emptyList(),
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
                // A-C6: a missing-model error is NOT a completed turn.
                return@withContext AgentDecision(
                    reply = "Bhai, local model abhi download nahi hua — AI Brain me 'Download Free Local Model' dabao (ek baar, ~304 MB), phir main bina internet ke baat karunga. Tab tak Gemini/Groq chuno.",
                    thought = "Local model not downloaded",
                    action = "NONE",
                    isCompleted = false,
                    decisionKind = "ERROR"
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
                    isCompleted = false,
                    decisionKind = "ERROR"
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
                    isCompleted = false,
                    decisionKind = "ERROR"
                )
            }
        }

        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            // 1.4.2: a missing key is a diagnosable state, not just
            // a chat reply — the bug report names it.
            JarvisStateRepository.addDiagnostic("llm", "NO_KEY", "API key missing in preferences")
            return@withContext AgentDecision(
                reply = "Bhai, pehle AI Brain tab mein jaake apni Gemini ya OpenAI/Groq API Key daal de taaki mera dimaag full power pe chal sake!",
                thought = "API key is missing in preferences",
                action = "NONE",
                askUser = false,
                isCompleted = false,
                decisionKind = "ERROR"
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
            if (answeredClarifications.isNotEmpty()) {
                appendLine("=== ALREADY ANSWERED (never re-ask) ===")
                answeredClarifications.takeLast(12).forEach { appendLine("- $it") }
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
            // A-M1 fallback chain: ANY attempt failure (429 / 401 /
            // network / JSON-shape / empty body) is fallback-eligible
            // — the old code only fell back on 404/503, so one rate
            // limit or dead key bricked the whole agent.
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
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    lastError = e
                    if (candidate == candidates.last()) throw e
                    // 1.4.13 (F-L2): fail FAST on non-retryable
                    // errors — do NOT burn the remaining
                    // candidates (each up to 25 s connect / 45 s
                    // read) on a dead key or a dead endpoint.
                    // 401 = the key is dead for EVERY candidate
                    // (same key); 404 = the endpoint/model path
                    // is dead. Retryable errors (429 / 5xx /
                    // network / timeouts) still fall through to
                    // the next candidate model, as before. Model
                    // choice and timeout values are untouched.
                    if (isNonRetryableLlmError(e)) throw e
                    // else: fall through to the next candidate model
                }
            }
            parseDecisionJson(rawJson ?: throw (lastError ?: IllegalStateException("No model responded")))
        } catch (e: Exception) {
            // Do NOT truncate the error to 60 chars any more: that hid the
            // "404 model not found" detail that would have named the fix.
            // A-C6/A-M1: the kind is explicit and isCompleted is FALSE —
            // an error turn must never persist as a verified routine
            // or index as a success, and a rate limit is surfaced
            // distinctly (retry), never as completion.
            val detail = (e.message ?: "Unknown error").take(300)
            val kind = when {
                e is DecisionParseException -> "PARSE_ERROR"
                detail.contains("429") -> "RATE_LIMITED"
                else -> "ERROR"
            }
            val reply = when (kind) {
                "PARSE_ERROR" -> "Model ka jawab adhoora/gadbad aaya, dobara koshish kar raha hoon."
                "RATE_LIMITED" -> "Rate limit lag gaya hai, thodi der me dobara koshish karunga."
                else -> {
                    val hint = if (detail.contains("404")) {
                        " Model nahi mila — AI Brain me provider ka default model check karo."
                    } else ""
                    "API error: $detail.$hint"
                }
            }
            // 1.4.2: record the failure for the bug report (HTTP
            // code + body message only — never the key or URL).
            JarvisStateRepository.addDiagnostic("llm", kind, detail)
            AgentDecision(
                reply = reply,
                thought = "Exception: ${e.message}",
                action = "NONE",
                askUser = false,
                isCompleted = false,
                decisionKind = kind
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

    /**
     * 1.4.13 (F-L2): non-retryable LLM errors. Both API callers
     * throw IllegalStateException("HTTP <code>: ..."), so a
     * prefix match is exact — a "401"/"404" buried in a body
     * string never misclassifies. Everything else (429, 5xx,
     * SocketTimeout, unknown-host, parse errors) is retryable
     * and still tries the next candidate model.
     */
    private fun isNonRetryableLlmError(e: Exception): Boolean {
        val msg = (e.message ?: "").trim()
        return msg.startsWith("HTTP 401") || msg.startsWith("HTTP 404")
    }

    private fun buildSystemPrompt(): String {
        val memories = prefs.getMemories().joinToString("; ")
        return """
            ${prefs.personaPrompt}
            Preferred Language: ${prefs.languageMode}.
            User's Long-Term Memory Notes: [$memories]

            You are JARVIS, an autonomous Android OS agent with full Accessibility Service control, Multimodal Vision Sight, AND a warm, friendly human-like companion.
            You operate in a multi-step ReAct loop:
            1. If the user is just chatting casually (e.g. "Aur bhai kya haal hai?", "Joke suna", "Mujhe neend aa rahi hai"), respond naturally and concisely like a close friend with action="NONE" and isCompleted=true.
            2. If the user gives a phone command or multi-step task (e.g. "WhatsApp khol ke Rahul ko bol main 10 min mein aa raha hoon", "YouTube pe Arijit Singh ka gaana chala", "Instagram reels scroll kar", "Flashlight jala"), execute it step-by-step until the goal is achieved!
            3. If you see ambiguity on the screen (e.g. multiple contacts with the same name, missing info, or a payment confirmation when Safe Mode is active), set action="NONE", askUser=true, isCompleted=false, and ask the user clearly in `reply`.
            4. If the user tells you a personal fact to remember, use action="SAVE_MEMORY" with `memoryNote`.
            5. ${WebsiteBuildSpec.BUILDER_PROMPT_FRAGMENT}
            6. CODE BUILDER — VOICE MODIFY (edit in place, never rebuild): If the user asks to CHANGE something in a site that already exists — e.g. "is button ka rang badal do", "is website me ek naya page/section add kar do", "is heading ko badal do", "is page me ye feature jodo" — do NOT use CREATE_PROJECT (that would rebuild or ignore the existing project). Use action="WRITE_PROJECT_FILE" to the EXISTING project: same projectName as the project being changed (if the user named it, use that name; if they did not name it, use the most recently built project from the conversation; if two or more projects could match and the user did not name one, set action="NONE", askUser=true, isCompleted=false and ask which project). Write ONLY the file that changes, with its FULL corrected content (the app overwrites that one file; other files stay untouched). A new page means: WRITE the new page file (e.g. fileName="about.html") AND rewrite index.html with a link to it, in two steps with isCompleted=false then true. The new page MUST carry the SAME shared nav snippet as the other pages, and index.html's nav MUST link to it (validation fails on a page missing nav or linking a missing page). Set isCompleted=true on the last edit step and reply with exactly what changed + "preview refresh ho gaya hai, Projects tab ya Browser tab me dekho".
            7. NEVER ask for info already present in CURRENT USER GOAL, PREVIOUS STEPS, RECENT CONVERSATION, or ALREADY ANSWERED — reuse it and act; ask only for genuinely missing info.

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
            - "NAVIGATE": System navigation. Fill `navTarget` ("BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS", "SCREENSHOT"). HOME ONLY when the user explicitly asked to go home or the plan genuinely needs the launcher — NEVER as a generic first step before opening an app (use OPEN_APP directly to launch apps). A HOME press backgrounds Jarvis, so it must never appear in a plan unless the user asked for it.
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
              "reply": "Short natural spoken response in ${prefs.languageMode} — talk exactly like a close playful friend, concisely: 1-2 short sentences for EVERY reply (not just step updates), never a lecture, never 'As an AI'; concise but complete — never drop file names, preview lines, or details the user asked for",
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

    /**
     * 1.4.2 "Test key" button (AI Brain): one cheap generateContent
     * call against the SAVED provider/model/key, returning the HTTP
     * result VERBATIM (code + body message). The key and the request
     * URL are NEVER included in the returned string or any log —
     * only the status and the body's error text, so a 400/403 names
     * a restricted/disabled key instead of a vague "kaam nahi karta".
     * Gemini only: other providers get a plain note (their test path
     * is the normal decide() error surfacing, already recorded).
     */
    suspend fun testApiKey(): String {
        return withContext(Dispatchers.IO) {
            try {
                if (prefs.provider != AiProvider.GEMINI) {
                    return@withContext "Test abhi sirf Gemini provider ke liye hai " +
                        "(current provider: ${prefs.provider.displayName}). " +
                        "Gemini chuno, key daal kar Save karo, phir Test dabao."
                }
                val key = prefs.apiKey
                if (key.isBlank()) {
                    return@withContext "Key khaali hai — pehle AI Brain me key daal kar Save karo."
                }
                val model = prefs.modelName
                val body = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", "hi"))
                            })
                        })
                    })
                }.toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .addHeader("x-goog-api-key", key)
                    .post(body)
                    .build()
                val resp = httpClient.newCall(request).execute()
                val respBody = resp.body?.string().orEmpty()
                resp.close()
                if (resp.isSuccessful) {
                    "OK (HTTP 200) — key kaam kar rahi hai, model: $model"
                } else {
                    "HTTP ${resp.code}: ${respBody.take(300)}"
                }
            } catch (e: Exception) {
                "Network error: ${(e.message ?: "unknown").take(200)}"
            }
        }
    }

    private fun callGeminiApi(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imageBase64: String? = null
    ): String {
        // 1.4.5 (item 8): key via HEADER, never the URL query.
        // The step-call path put the key in ?key= (logged URLs,
        // proxies, and the diagnostics sanitizer's ?key= strip all
        // treat query keys as leakable); testApiKey already used
        // the x-goog-api-key header — now the real call does too.
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
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
            .addHeader("x-goog-api-key", apiKey)
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

    // ---- 1.4.20 (item 5): FREE live-browsing AI, separate path ----
    // answerWithLiveSearch is deliberately NOT part of decideNextStep
    // and NOT part of callGeminiApi: the decision parser needs strict
    // JSON and would reject grounded prose, and the decision call's
    // extractor drops groundingMetadata. This call is its own shape:
    // model HARDCODED to gemini-3.1-flash-lite (never prefs.modelName,
    // never the fallback chain — the primary 3.5-flash-lite budget is
    // not burned by search turns), same saved key, same
    // x-goog-api-key header, same OkHttpClient, same
    // .../v1beta/models/<model>:generateContent URL shape.
    // Grounding tool field (confirmed 2026-11-05 against Google's
    // Grounding with Google Search reference): the REST request body
    // carries "tools": [{"googleSearch": {}}] — the current field
    // spelling (the older googleSearchRetrieval form is superseded).
    // Google's supported-models list for Search grounding includes
    // gemini-3.1-flash-lite. NO responseMimeType application/json on
    // this call: grounded prose is the answer, not a decision object.
    // Response parsing is new code, not the decision parser: all
    // parts[].text joined as the answer, plus
    // groundingMetadata.groundingChunks[].web{uri, title} as sources
    // (+ webSearchQueries when present). A call with NO grounding
    // metadata is a FAILURE, never a training-data answer presented
    // as current. No new key, no new SDK, no paid service, no card.
    // Gemini-provider only: the engine guards this, and this
    // function double-guards (other providers get Failed, and the
    // engine falls through to today's WebView-browse behaviour).

    data class LiveSource(val title: String, val uri: String)

    sealed interface LiveSearchResult {
        data class Grounded(
            val answer: String,
            val sources: List<LiveSource>,
            val queries: List<String>
        ) : LiveSearchResult

        data class Failed(val reason: String) : LiveSearchResult
    }

    suspend fun answerWithLiveSearch(question: String): LiveSearchResult =
        withContext(Dispatchers.IO) {
            if (prefs.provider != AiProvider.GEMINI) {
                return@withContext LiveSearchResult.Failed(
                    "Live search sirf Gemini provider par hai."
                )
            }
            val apiKey = prefs.apiKey
            if (apiKey.isBlank()) {
                return@withContext LiveSearchResult.Failed("API key nahi hai.")
            }
            try {
                val bodyJson = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().apply {
                            put(
                                "text",
                                "Answer concisely in 2-3 short sentences, based on the live search results: $question"
                            )
                        }))
                    }))
                    put("tools", JSONArray().put(JSONObject().apply {
                        put("googleSearch", JSONObject())
                    }))
                }
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent")
                    .addHeader("x-goog-api-key", apiKey)
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        return@withContext LiveSearchResult.Failed(
                            "HTTP ${resp.code}: ${respBody.take(200)}"
                        )
                    }
                    val root = JSONObject(respBody)
                    val candidates = root.optJSONArray("candidates")
                    if (candidates == null || candidates.length() == 0) {
                        return@withContext LiveSearchResult.Failed(
                            "Live search se koi jawab nahi mila."
                        )
                    }
                    val candidate = candidates.getJSONObject(0)
                    val parts = candidate.optJSONObject("content")
                        ?.optJSONArray("parts")
                    val answer = buildString {
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val text = parts.optJSONObject(i)?.optString("text", "")
                                if (!text.isNullOrBlank()) {
                                    if (isNotEmpty()) append("\n")
                                    append(text)
                                }
                            }
                        }
                    }.trim()
                    if (answer.isBlank()) {
                        return@withContext LiveSearchResult.Failed(
                            "Live search se khaali jawab mila."
                        )
                    }
                    // No grounding metadata = NOT a grounded answer:
                    // fail honestly instead of dressing up a
                    // training-data reply as current information.
                    val grounding = candidate.optJSONObject("groundingMetadata")
                        ?: return@withContext LiveSearchResult.Failed(
                            "Live search se grounded sources nahi mile."
                        )
                    val sources = mutableListOf<LiveSource>()
                    val chunks = grounding.optJSONArray("groundingChunks")
                    if (chunks != null) {
                        for (i in 0 until chunks.length()) {
                            val web = chunks.optJSONObject(i)?.optJSONObject("web")
                            val uri = web?.optString("uri", "") ?: ""
                            if (uri.isNotBlank()) {
                                sources.add(
                                    LiveSource(
                                        title = web?.optString("title", uri) ?: uri,
                                        uri = uri
                                    )
                                )
                            }
                        }
                    }
                    if (sources.isEmpty()) {
                        return@withContext LiveSearchResult.Failed(
                            "Live search se grounded sources nahi mile."
                        )
                    }
                    val queries = mutableListOf<String>()
                    val searchQueries = grounding.optJSONArray("webSearchQueries")
                    if (searchQueries != null) {
                        for (i in 0 until searchQueries.length()) {
                            val q = searchQueries.optString(i, "")
                            if (q.isNotBlank()) queries.add(q)
                        }
                    }
                    LiveSearchResult.Grounded(answer, sources, queries)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Key and URL are never included in the reason.
                LiveSearchResult.Failed(
                    (e.message ?: "Network error").take(200)
                )
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
        // A-M2 STRICT parsing: some models wrap the JSON in prose,
        // so the first '{' is still the start — but the object must
        // end at its MATCHING close brace (string-aware scan). A
        // truncated reply (no matching close) or trailing content
        // after the close (a second, merged object) is a parse
        // error — never a partial action, never a merged decision.
        val start = cleaned.indexOf('{')
        if (start < 0) throw DecisionParseException("Model reply me JSON object nahi mila")
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end < 0) throw DecisionParseException("Model JSON adhoora/truncated hai")
        val trailing = cleaned.substring(end + 1).trim().removePrefix("```").trim()
        if (trailing.isNotEmpty()) {
            throw DecisionParseException("Model reply me ek se zyada JSON object mile (merge) — partial action nahi liya")
        }
        val obj = try {
            JSONObject(cleaned.substring(start, end + 1))
        } catch (e: Exception) {
            throw DecisionParseException("Model JSON parse nahi hua: ${e.message}")
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
        // A-H1 per-action validation: known-action allowlist,
        // required non-blank fields, in-range values. A bad decision
        // throws (=> error decision, never executed, never partial).
        // Coordinates are range-checked against the screen again in
        // the engine gate; here negatives are rejected outright.
        val action = obj.optString("action", "NONE").uppercase()
        val knownActions = setOf(
            "NONE", "OPEN_APP", "YOUTUBE_SEARCH", "YOUTUBE_PLAY",
            "WHATSAPP_SEND", "OPEN_URL", "OPEN_BROWSER", "REPLY_NOTIFICATION",
            "TAP_NODE", "TAP_XY", "LONG_PRESS_XY", "TYPE_TEXT", "SCROLL",
            "NAVIGATE", "MEDIA_CONTROL", "COPY_CLIPBOARD", "WAIT",
            "FLASHLIGHT", "VOLUME", "CALL", "SET_ALARM", "SAVE_MEMORY",
            "CREATE_PROJECT", "WRITE_PROJECT_FILE"
        )
        if (action !in knownActions) throw DecisionParseException("Unknown action: $action")
        val appName = obj.optString("appName", "")
        val nodeId = obj.optInt("nodeId", -1)
        val x = obj.optInt("x", -1)
        val y = obj.optInt("y", -1)
        val text = obj.optString("text", "")
        val contact = obj.optString("contact", "")
        val volumePercent = obj.optInt("volumePercent", 50)
        val alarmHour = obj.optInt("alarmHour", 7)
        val alarmMinute = obj.optInt("alarmMinute", 0)
        val direction = obj.optString("direction", "DOWN")
        val navTarget = obj.optString("navTarget", "BACK")
        val memoryNote = obj.optString("memoryNote", "")
        val fileContent = obj.optString("fileContent", "")
        val browserUrl = obj.optString("browserUrl", "")
        fun need(cond: Boolean, msg: String) {
            if (!cond) throw DecisionParseException(msg)
        }
        when (action) {
            "OPEN_APP" -> need(appName.isNotBlank(), "OPEN_APP appName blank")
            "YOUTUBE_SEARCH", "YOUTUBE_PLAY" -> need(
                text.isNotBlank() || appName.isNotBlank(), "$action query blank"
            )
            "WHATSAPP_SEND", "REPLY_NOTIFICATION" -> need(
                contact.isNotBlank() && text.isNotBlank(), "$action contact/text blank"
            )
            "OPEN_URL" -> need(text.isNotBlank(), "OPEN_URL text blank")
            "OPEN_BROWSER" -> need(browserUrl.isNotBlank(), "OPEN_BROWSER browserUrl blank")
            "TAP_NODE" -> need(nodeId > 0, "TAP_NODE nodeId missing")
            "TAP_XY", "LONG_PRESS_XY" -> need(
                x >= 0 && y >= 0, "$action coordinates negative — rejected, never coerced"
            )
            "TYPE_TEXT" -> need(text.isNotBlank(), "TYPE_TEXT text blank")
            "SCROLL" -> need(
                direction.uppercase() in setOf("DOWN", "UP", "LEFT", "RIGHT", "NEXT", "PREVIOUS"),
                "SCROLL direction invalid"
            )
            "NAVIGATE" -> need(
                navTarget.uppercase() in setOf(
                    "BACK", "HOME", "RECENTS", "NOTIFICATIONS",
                    "QUICK_SETTINGS", "LOCK_SCREEN", "SCREENSHOT"
                ), "NAVIGATE target invalid"
            )
            "MEDIA_CONTROL" -> need(
                text.uppercase() in setOf("PLAY_PAUSE", "NEXT", "PREVIOUS", "PLAY", "PAUSE"),
                "MEDIA_CONTROL text invalid"
            )
            "COPY_CLIPBOARD" -> need(text.isNotBlank(), "COPY_CLIPBOARD text blank")
            "VOLUME" -> need(volumePercent in 0..100, "VOLUME out of range — rejected, never coerced")
            "CALL" -> need(contact.isNotBlank(), "CALL contact blank")
            "SET_ALARM" -> need(
                alarmHour in 0..23 && alarmMinute in 0..59, "SET_ALARM time out of range"
            )
            "SAVE_MEMORY" -> need(memoryNote.isNotBlank(), "SAVE_MEMORY memoryNote blank")
            "WRITE_PROJECT_FILE" -> need(fileContent.isNotBlank(), "WRITE_PROJECT_FILE fileContent blank")
        }
        return AgentDecision(
            reply = obj.optString("reply", ""),
            thought = obj.optString("thought", ""),
            action = action,
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
            // A-H1: isCompleted defaults FALSE for non-NONE actions —
            // a step without an explicit completion flag must NOT
            // end the task (engine exits on isCompleted only, A-H2).
            isCompleted = obj.optBoolean("isCompleted", action == "NONE")
        )
    }

    // ---- 1.4.21 Agent Mode: planner (SEPARATE from decideNextStep) ----
    // planNext / buildPlanPrompt / parsePlanJson are NEW functions
    // only; decideNextStep, buildSystemPrompt, answerWithLiveSearch
    // and callGeminiApi are untouched. The planner runs on the
    // GEMINI chain only (same key, same fallback chain) — NEVER
    // LOCAL Gemma (the on-device model has no structured-plan path,
    // same reason as the builder guard above). Other providers get
    // a plain failure asking for Gemini; the planner does not
    // branch on provider in 1.4.21. Strict parsing mirrors
    // parseDecisionJson (A-M2): string-aware brace scan, trailing
    // content = error, unknown step kind = error, missing
    // kind/title = error, needsGate defaults false, max 8 steps.

    data class PlanStepDraft(
        val kind: String,
        val title: String,
        val detail: String,
        val needsGate: Boolean
    )

    sealed interface PlanResult {
        data class Planned(val planTitle: String, val steps: List<PlanStepDraft>) : PlanResult
        data class Failed(val reason: String) : PlanResult
    }

    class PlanParseException(message: String) : IllegalArgumentException(message)

    suspend fun planNext(
        goal: String,
        answeredClarifications: List<String>,
        recentChat: List<String>
    ): PlanResult = withContext(Dispatchers.IO) {
        if (prefs.provider != AiProvider.GEMINI) {
            return@withContext PlanResult.Failed(
                "Planner sirf Gemini provider par chalta hai — AI Brain me Gemini chuno, key save karo, phir dobara bolo."
            )
        }
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            JarvisStateRepository.addDiagnostic("llm", "NO_KEY", "API key missing in preferences (planner)")
            return@withContext PlanResult.Failed(
                "API key nahi hai — AI Brain me Gemini key daal kar Save karo, phir dobara bolo."
            )
        }
        val prompt = buildPlanPrompt(goal, answeredClarifications, recentChat)
        val systemPrompt = "You are JARVIS, an Android agent planner. " +
            "Respond ONLY with the plan JSON object described in the user message — " +
            "no prose, no markdown fences, no explanation."
        val candidates = (listOf(prefs.modelName) + prefs.provider.fallbackModels)
            .map { it.trim().removePrefix("models/") }
            .filter { it.isNotBlank() }.distinct()
        // PLAN retry budget (§3d): 1 re-plan on a parse error, then ask.
        var attempt = 0
        while (attempt < 2) {
            attempt++
            try {
                var rawJson: String? = null
                var lastError: Exception? = null
                for (candidate in candidates) {
                    try {
                        rawJson = callGeminiApi(
                            apiKey = apiKey,
                            model = candidate,
                            systemPrompt = systemPrompt,
                            userPrompt = prompt
                        )
                        break
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        lastError = e
                        if (candidate == candidates.last()) throw e
                        if (isNonRetryableLlmError(e)) throw e
                    }
                }
                return@withContext parsePlanJson(
                    rawJson ?: throw (lastError ?: IllegalStateException("No model responded"))
                )
            } catch (e: PlanParseException) {
                if (attempt >= 2) {
                    JarvisStateRepository.addDiagnostic("llm", "PLAN_PARSE", (e.message ?: "").take(200))
                    return@withContext PlanResult.Failed(
                        "Plan JSON dobara bhi gadbad aaya (${(e.message ?: "").take(150)}). Goal dobara simple shabdon me bolo."
                    )
                }
                // else: one re-plan retry.
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val detail = (e.message ?: "Unknown error").take(200)
                JarvisStateRepository.addDiagnostic("llm", "PLAN_ERROR", detail)
                return@withContext PlanResult.Failed("Planner error: $detail")
            }
        }
        PlanResult.Failed("Planner se plan nahi bana — dobara koshish karo.")
    }

    private fun buildPlanPrompt(
        goal: String,
        answeredClarifications: List<String>,
        recentChat: List<String>
    ): String {
        return buildString {
            appendLine("USER GOAL (Agent Mode — multi-step plan banao):")
            appendLine(goal)
            appendLine()
            if (answeredClarifications.isNotEmpty()) {
                appendLine("ALREADY ANSWERED (never re-ask — inhe plan me use karo):")
                answeredClarifications.takeLast(12).forEach { appendLine("- $it") }
                appendLine()
            }
            if (recentChat.isNotEmpty()) {
                appendLine("RECENT CONVERSATION (project/repo naam yahin se mil sakta hai):")
                recentChat.takeLast(6).forEach { appendLine("- $it") }
                appendLine()
            }
            appendLine("WEBSITE BUILD CHECKLIST (BUILD step ke liye — har website build par ye checklist lagti hai, user dobara details nahi dega):")
            appendLine(WebsiteBuildSpec.CHECKLIST_TEXT)
            appendLine()
            appendLine("PLAN RULES (strict — inhe todna plan reject kar dega):")
            appendLine("1. Output SIRF ek JSON object ho, is schema me (no prose, no fences):")
            appendLine("{ \"planTitle\": \"short title\", \"steps\": [ { \"kind\": \"BUILD|GITHUB_PUSH|SUPABASE_TABLES|VERCEL_DEPLOY|HTTP_CHECK\", \"title\": \"short\", \"detail\": \"...\", \"needsGate\": false } ] }")
            appendLine("2. Steps 1 se 8 ke beech — zyada steps ho to plan ko chhota rakho, silently truncate mat karo.")
            appendLine("3. Step kinds sirf ye 5 allowed hain: BUILD (website banao — existing builder checklist ke saath), GITHUB_PUSH (code GitHub repo me push karo), SUPABASE_TABLES (built backend/schema.sql se tables banao), VERCEL_DEPLOY (GitHub repo se Vercel deploy karo), HTTP_CHECK (ek URL check karo). Koi aur kind mat likho.")
            appendLine("4. Order dependency ka dhyaan rakho: BUILD pehle, phir GITHUB_PUSH, phir SUPABASE_TABLES, phir VERCEL_DEPLOY. Vercel deploy ke liye GitHub push DONE hona zaroori hai — uske bina Vercel step mat rakho.")
            appendLine("5. detail me har step ke liye zaroori naam likho, is format me: BUILD -> \"project=<project-name>; <site kaisa ho, ek line>\"; GITHUB_PUSH -> \"repo=<repo-name>\" (user ne naam diya ho to wahi, nahi diya to \"repo=?\"); SUPABASE_TABLES -> \"project=<project-name>\"; VERCEL_DEPLOY -> \"project=<project-name>; repo=<owner>/<repo>\" (repo pata ho to); HTTP_CHECK -> \"url=<full-url> marker=<expected text>\".")
            appendLine("6. Naam KABHI invent mat karo: repo naam, project naam, table naam user ne diya ho ya conversation me ho to wahi use karo — nahi ho to \"repo=?\" / project poochne ke liye detail me likho, khud ka naam mat gadh lo.")
            appendLine("7. needsGate=true set karo jab step paise kharch kare, kuch delete kare, login/2FA maange, ya kisi existing non-empty project ko overwrite kare — us step se pehle user se poocha jayega.")
            appendLine("8. Phone par koi backend server nahi chalta — backend files (backend/server.js, backend/schema.sql) deploy ke liye generate hoti hain, phone par run hone ka daava mat karo.")
            appendLine("9. Goal me sirf website banani ho (koi GitHub/Supabase/Vercel/deploy shabd na ho) to BUILD step hi rakho — deploy steps mat jodo.")
        }
    }

    private fun parsePlanJson(raw: String): PlanResult {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        if (start < 0) throw PlanParseException("Model reply me JSON object nahi mila")
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end < 0) throw PlanParseException("Model plan JSON adhoora/truncated hai")
        val trailing = cleaned.substring(end + 1).trim().removePrefix("```").trim()
        if (trailing.isNotEmpty()) {
            throw PlanParseException("Model reply me ek se zyada JSON object mile (merge) — partial plan nahi liya")
        }
        val obj = try {
            JSONObject(cleaned.substring(start, end + 1))
        } catch (e: Exception) {
            throw PlanParseException("Model plan JSON parse nahi hua: ${e.message}")
        }
        val planTitle = obj.optString("planTitle", "").trim()
        val stepsArr = obj.optJSONArray("steps")
            ?: throw PlanParseException("Plan me 'steps' array nahi mila")
        if (stepsArr.length() == 0) throw PlanParseException("Plan me koi step nahi hai")
        if (stepsArr.length() > 8) throw PlanParseException("Plan me 8 se zyada steps hain (${stepsArr.length()}) — chhota plan banao")
        val allowedKinds = setOf(
            "PLAN", "BUILD", "GITHUB_PUSH", "SUPABASE_TABLES", "VERCEL_DEPLOY", "HTTP_CHECK"
        )
        val steps = mutableListOf<PlanStepDraft>()
        for (i in 0 until stepsArr.length()) {
            val stepObj = stepsArr.optJSONObject(i)
                ?: throw PlanParseException("Step ${i + 1} JSON object nahi hai")
            val kind = stepObj.optString("kind", "").trim().uppercase()
            if (kind.isBlank()) throw PlanParseException("Step ${i + 1} me 'kind' missing hai")
            if (kind !in allowedKinds) throw PlanParseException("Step ${i + 1} ka kind unknown hai: $kind")
            val title = stepObj.optString("title", "").trim()
            if (title.isBlank()) throw PlanParseException("Step ${i + 1} me 'title' missing hai")
            steps.add(
                PlanStepDraft(
                    kind = kind,
                    title = title,
                    detail = stepObj.optString("detail", "").trim(),
                    needsGate = stepObj.optBoolean("needsGate", false)
                )
            )
        }
        return PlanResult.Planned(planTitle = planTitle, steps = steps)
    }
}
