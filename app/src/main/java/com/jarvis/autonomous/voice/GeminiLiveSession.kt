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
 * 1.4.17: Gemini Live API session — conversation ONLY.
 *
 * Structural no-action boundary (not a prompt hope): this class
 * receives NO engine, LLM client, accessibility service, or
 * memory/routine reference, and its setup message carries NO
 * function-calling field — with that field omitted the model
 * cannot legitimately emit a function call. Defense in depth:
 * a call message arriving anyway is dropped, never answered,
 * logged LIVE_TOOLCALL_DROPPED, and the session continues as
 * conversation.
 *
 * Pinned model: gemini-3.8-live (GA since 2026-09-15, Google's
 * named replacement for the retiring Live previews). Endpoint:
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
        fun onToolCallDropped()
        fun onEnded(kind: EndKind)
    }

    companion object {
        /** Pinned Live model (see class doc). */
        const val LIVE_MODEL = "gemini-3.8-live"

        /** Developer-API BidiGenerateContent WebSocket. */
        const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /** Setup handshake timeout, mirroring the Deepgram
         * CONNECT_TIMEOUT_MS (15 s). */
        const val SETUP_TIMEOUT_MS: Long = 15_000L

        /** Frozen setup-time system instruction (conversation
         * only, no phone actions). */
        const val SYSTEM_INSTRUCTION =
            "You are Jarvis in Live conversation mode. Conversation only. " +
                "Never ask for or perform phone actions, payments, messages, " +
                "deletions, or app operations; if asked, reply conversationally " +
                "that Live mode cannot do actions."
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
        // NOTE: deliberately NO function-calling field, NO
        // transcription fields, NO voice selection — see class
        // doc. responseModalities AUDIO = spoken replies.
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
        }
        val msg = JSONObject().apply { put("setup", setup) }
        runCatching { ws.send(msg.toString()) }
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
        // A call message despite no function-calling field in
        // setup: dropped, never answered, session continues.
        if (json.has("toolCall")) {
            JarvisStateRepository.addDiagnostic(
                "live", "LIVE_TOOLCALL_DROPPED",
                "Server sent a call message despite no function-calling field in setup — dropped, no response sent, session continues as conversation."
            )
            listener?.onToolCallDropped()
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
