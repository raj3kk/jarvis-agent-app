package com.jarvis.autonomous.voice

import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.jarvis.autonomous.data.JarvisStateRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1.4.17: Gemini Live API session — conversation + R3.6 live tools.
 *
 * R3.6 (1.18.0-PRO): the structural no-action boundary is LIFTED
 * for exactly 6 tools (3 registry SAFE tools + 3 local device
 * tools). The setup message now carries a function-calling field
 * with those declarations; a toolCall message is parsed and
 * delivered to [Listener.onToolCall] (call id + name + args JSON),
 * and the service answers with [sendFunctionResponse]. Everything
 * else stays: the class receives NO engine, LLM client,
 * accessibility service, or memory/routine reference — the
 * service (FGS) owns the dispatch through the engine's
 * executeLiveTool, which gate-checks through ToolRegistry.
 * CONFIRM / SENSITIVE registry tools are never declared here, so
 * the model cannot call them; a call for any undeclared name is
 * refused by the engine with an error functionResponse (PARK —
 * the 5 hard gates never run from live mode). An unknown toolCall
 * shape is logged LIVE_TOOLCALL_DROPPED and the session continues.
 *
 * Pinned model: gemini-2.5-flash-native-audio-dialog. Endpoint:
 * the Developer-API WebSocket BidiGenerateContent, auth via the
 * x-goog-api-key header ONLY — the key is NEVER in the URL,
 * NEVER logged, and NEVER put in a Diagnostics message (the
 * sanitizer redacts key-bearing values as a second line of
 * defence). Transcription is OFF in v1 (no input/output
 * transcription field), voice selection is omitted (server
 * default), and no session-resumption handle is sent or stored
 * (zero automatic reconnects in v1). Usage metadata is never
 * read, computed, or displayed here — no cost figure exists.
 *
 * Ownership: this class owns ONLY the WebSocket + protocol.
 * The service owns the Live AudioRecord (mic capture, fed via
 * [sendPcm]) and the Live AudioTrack (model PCM delivered via
 * [Listener.onAudioFrame]). Callbacks arrive on OkHttp threads
 * unless noted; the service hops to main where it needs to.
 */
class GeminiLiveSession {

    /** How a session ended — the service maps this to the exact
     * fallback wording + diagnostic code. MANUAL is service-side
     * only (toggle OFF, overlay tap, mic-off, destroy) and is
     * never delivered by the listener. */
    enum class EndKind {
        MANUAL, CAP, GOAWAY, TIMEOUT, NETWORK, LIMIT, ERROR
    }

    interface Listener {
        fun onSetupComplete()
        fun onAudioFrame(pcm: ByteArray)
        fun onInterrupted()
        /**
         * R3.6: the model called a declared function. [callId] is
         * the server's function-call id (echoed back in
         * [sendFunctionResponse]); [argsJson] is the call's args
         * object as JSON. The listener MUST answer every call with
         * sendFunctionResponse (even a refusal — an unanswered call
         * stalls the model's turn).
         */
        fun onToolCall(callId: String, name: String, argsJson: String)
        fun onEnded(kind: EndKind)
    }

    companion object {
        /** Pinned Live model (see class doc). */
        const val LIVE_MODEL = "gemini-2.5-flash-native-audio-dialog"

        /** Developer-API BidiGenerateContent WebSocket. */
        const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /** Setup handshake timeout, mirroring the Deepgram
         * CONNECT_TIMEOUT_MS (15 s). */
        const val SETUP_TIMEOUT_MS: Long = 15_000L

        /** Frozen setup-time system instruction (R3.6: live tools
         *  allowed — 3 read-only checks + 3 local device tools.
         *  Money / delete / login / OTP / biometric / sending
         *  messages / app operations stay forbidden in live mode;
         *  if asked, reply conversationally that Live mode cannot
         *  do that and offer the normal command path. */
        const val SYSTEM_INSTRUCTION =
            "You are Jarvis in Live conversation mode. You can use " +
                "these tools when the user asks: http_check (check a URL), " +
                "gmail_read (read the inbox), drive_read (read a Drive file), " +
                "set_torch, set_volume, media_control (play/pause). " +
                "Never ask for or perform payments, messages, deletions, " +
                "logins, OTP, biometric, or other app operations; if asked, " +
                "reply conversationally that Live mode cannot do that."

        /**
         * R3.6: the 6 live tools, as Gemini function declarations.
         * Only SAFE (read-only / harmless local) tools are declared
         * — CONFIRM / SENSITIVE registry tools are never offered to
         * the model. The service dispatches through the engine,
         * which re-checks the gate per call.
         */
        val LIVE_TOOLS_JSON = """
            [{
                "functionDeclarations": [
                    {"name": "http_check", "description": "Kisi URL ko kholo aur status/content check karo (read-only).",
                     "parameters": {"type": "OBJECT", "properties": {
                         "url": {"type": "STRING", "description": "Poora http(s) URL"},
                         "expect": {"type": "STRING", "description": "Page me dhoondhne wala text (optional)"}},
                         "required": ["url"]}},
                    {"name": "gmail_read", "description": "Gmail inbox padho (read-only, hub OAuth).",
                     "parameters": {"type": "OBJECT", "properties": {
                         "query": {"type": "STRING", "description": "Search query (optional)"},
                         "limit": {"type": "INTEGER", "description": "Max messages, 1-10 (optional)"}},
                         "required": []}},
                    {"name": "drive_read", "description": "Google Drive se file padho (read-only, hub OAuth).",
                     "parameters": {"type": "OBJECT", "properties": {
                         "file_id": {"type": "STRING", "description": "Drive file id"}},
                         "required": ["file_id"]}},
                    {"name": "set_torch", "description": "Phone ki torch on/off karo.",
                     "parameters": {"type": "OBJECT", "properties": {
                         "enable": {"type": "BOOLEAN", "description": "true = on, false = off"}},
                         "required": ["enable"]}},
                    {"name": "set_volume", "description": "Media volume set karo (percent).",
                     "parameters": {"type": "OBJECT", "properties": {
                         "percent": {"type": "INTEGER", "description": "0-100"}},
                         "required": ["percent"]}},
                    {"name": "media_control", "description": "Media play/pause/next/previous karo.",
                     "parameters": {"type": "OBJECT", "properties": {
                         "action": {"type": "STRING", "description": "play, pause, next, ya previous"}},
                         "required": ["action"]}}
                ]
            }]
        """.trimIndent()
    }

    private var httpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private val closed = AtomicBoolean(false)
    @Volatile
    private var setupComplete = false
    private var listener: Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var watchdogRunnable: Runnable? = null
    private var endedDelivered = false

    /**
     * Opens the session: sends the setup message FIRST on open
     * and waits for setupComplete (15 s watchdog — on timeout
     * the session ends TIMEOUT). Returns false only when the
     * socket could not even be created; handshake failures
     * arrive via [Listener.onEnded].
     */
    fun start(apiKey: String, listener: Listener): Boolean {
        closed.set(false)
        endedDelivered = false
        setupComplete = false
        this.listener = listener
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        httpClient = client
        val request = Request.Builder()
            .url(WS_URL)
            .header("x-goog-api-key", apiKey)
            .build()
        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // Model audio arrives as base64 inside JSON text
                // frames; a binary frame carries nothing usable.
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed.get()) {
                    deliverEnded(EndKind.NETWORK)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed.get()) {
                    deliverEnded(EndKind.NETWORK)
                }
            }
        }
        val ws = runCatching { client.newWebSocket(request, wsListener) }.getOrNull()
            ?: return false
        webSocket = ws
        val watchdog = Runnable {
            watchdogRunnable = null
            if (!closed.get() && !setupComplete) {
                deliverEnded(EndKind.TIMEOUT)
                closeSocket()
            }
        }
        watchdogRunnable = watchdog
        mainHandler.postDelayed(watchdog, SETUP_TIMEOUT_MS)
        return true
    }

    /** Explicit stop: closes the socket and suppresses the end
     * event (a manual end is not a failure). Idempotent. */
    fun stop() {
        closed.set(true)
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
        closeSocket()
        listener = null
    }

    /**
     * Sends one mic PCM chunk (16 kHz mono PCM_16BIT, the same
     * capture format as the Deepgram pump). Dropped silently
     * before setupComplete or after close — capture starts only
     * after setupComplete, so a drop here is a lifecycle bug
     * being contained, never a crash.
     */
    fun sendPcm(bytes: ByteArray, length: Int): Boolean {
        if (closed.get() || !setupComplete) return false
        val ws = webSocket ?: return false
        val chunk = JSONObject().apply {
            put("mimeType", "audio/pcm;rate=16000")
            put("data", Base64.encodeToString(bytes.copyOf(length), Base64.NO_WRAP))
        }
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", org.json.JSONArray().put(chunk))
            })
        }
        return runCatching { ws.send(msg.toString()) }.getOrDefault(false)
    }

    private fun sendSetup(ws: WebSocket) {
        // R3.6: the function-calling field is now present with the
        // 6 live tools (LIVE_TOOLS_JSON) — the model may call them.
        // No transcription fields, no voice selection — see class doc.
        // responseModalities AUDIO = spoken replies.
        val setup = JSONObject().apply {
            put("model", "models/$LIVE_MODEL")
            put("systemInstruction", JSONObject().apply {
                put("parts", org.json.JSONArray().put(
                    JSONObject().put("text", SYSTEM_INSTRUCTION)
                ))
            })
            put("generationConfig", JSONObject().apply {
                put("responseModalities", org.json.JSONArray().put("AUDIO"))
            })
            put("tools", org.json.JSONArray(LIVE_TOOLS_JSON))
        }
        val msg = JSONObject().apply { put("setup", setup) }
        runCatching { ws.send(msg.toString()) }
    }

    /**
     * R3.6: answer one function call. [resultJson] is a JSON string
     * (the engine's result — evidence or an "ERROR: ..." refusal);
     * it is wrapped as the response object so the model can speak
     * it. Dropped silently before setupComplete or after close.
     */
    fun sendFunctionResponse(callId: String, name: String, resultJson: String): Boolean {
        if (closed.get() || !setupComplete) return false
        val ws = webSocket ?: return false
        val msg = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", org.json.JSONArray().put(
                    JSONObject().apply {
                        put("id", callId)
                        put("name", name)
                        put("response", JSONObject().apply {
                            put("result", resultJson)
                        })
                    }
                ))
            })
        }
        return runCatching { ws.send(msg.toString()) }.getOrDefault(false)
    }

    private fun handleServerText(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        // Server error object: rate exhaustion maps to LIMIT so
        // the service speaks the limit fallback, not generic.
        if (json.has("error")) {
            val err = json.optJSONObject("error")
            val code = err?.optInt("code", 0) ?: 0
            val msg = err?.optString("message", "") ?: ""
            val status = err?.optString("status", "") ?: ""
            val kind = if (code == 429 || status == "RESOURCE_EXHAUSTED" ||
                msg.contains("rate", ignoreCase = true) ||
                msg.contains("quota", ignoreCase = true)
            ) EndKind.LIMIT else EndKind.ERROR
            deliverEnded(kind)
            closeSocket()
            return
        }
        if (json.has("setupComplete")) {
            setupComplete = true
            watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
            watchdogRunnable = null
            listener?.onSetupComplete()
            return
        }
        if (json.has("goAway")) {
            deliverEnded(EndKind.GOAWAY)
            closeSocket()
            return
        }
        // R3.6: a toolCall message — parse the function calls and
        // deliver them to the listener (the service dispatches
        // through the engine and answers with sendFunctionResponse).
        // An unparseable call is dropped + logged, never answered,
        // and the session continues as conversation.
        if (json.has("toolCall")) {
            val toolCall = json.optJSONObject("toolCall")
            val calls = toolCall?.optJSONArray("functionCalls")
            if (calls == null || calls.length() == 0) {
                JarvisStateRepository.addDiagnostic(
                    "live", "LIVE_TOOLCALL_DROPPED",
                    "Server sent a toolCall with no functionCalls — dropped, no response sent, session continues."
                )
                return
            }
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val name = call.optString("name", "")
                val id = call.optString("id", "")
                val args = call.optJSONObject("args")?.toString() ?: "{}"
                if (name.isBlank() || id.isBlank()) {
                    JarvisStateRepository.addDiagnostic(
                        "live", "LIVE_TOOLCALL_DROPPED",
                        "Server sent a function call without name/id — dropped, session continues."
                    )
                    continue
                }
                listener?.onToolCall(id, name, args)
            }
            return
        }
        val content = json.optJSONObject("serverContent") ?: return
        if (content.optBoolean("interrupted", false)) {
            listener?.onInterrupted()
            return
        }
        val turn = content.optJSONObject("modelTurn") ?: return
        val parts = turn.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val data = inline.optString("data", "")
            if (data.isEmpty()) continue
            val pcm = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
                ?: continue
            if (pcm.isNotEmpty()) listener?.onAudioFrame(pcm)
        }
    }

    private fun deliverEnded(kind: EndKind) {
        synchronized(this) {
            if (endedDelivered) return
            endedDelivered = true
        }
        listener?.onEnded(kind)
    }

    private fun closeSocket() {
        runCatching { webSocket?.close(1000, "done") }
        webSocket = null
        runCatching { httpClient?.dispatcher?.executorService?.shutdown() }
        httpClient = null
    }
}
