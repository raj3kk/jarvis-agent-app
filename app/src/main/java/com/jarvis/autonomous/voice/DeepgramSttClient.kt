package com.jarvis.autonomous.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import com.jarvis.autonomous.data.JarvisStateRepository
import android.media.MediaRecorder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Deepgram STT (1.4.3, listening/transcription ONLY).
 *
 * Speaking stays bundled Kokoro — Deepgram TTS/Aura is NEVER used
 * here and no TTS code path references this client.
 *
 * Wire protocol (Deepgram streaming API):
 * - wss://api.deepgram.com/v1/listen, auth header
 *   "Authorization: Token <key>" — the key is NEVER in the URL,
 *   NEVER logged, and NEVER put in a Diagnostics message (the
 *   sanitizer redacts Token headers as a second line of defence).
 * - Params (brief-locked): model=nova-3, language=multi,
 *   language_hint=hi, language_hint=en (user speaks Hinglish;
 *   monolingual hi would mangle English words),
 *   interim_results=true. Binary audio is linear16 PCM 16kHz mono,
 *   so encoding/sample_rate/channels are declared explicitly.
 * - Turn end is decided by the APP (TurnDetector 1000/1750/5000ms),
 *   NOT by Deepgram endpointing — no endpointing param is set.
 * - Fallback model (only on a model error): nova-2, language=hi.
 *
 * Ownership: the MANAGER owns the AudioRecord. This client only
 * owns the WebSocket: [connect], [sendPcm], [finalizeStream],
 * [close]. One-shot listening ([listenOnce]) is the exception —
 * the loop is OFF there, so the client briefly owns its own
 * AudioRecord for that single utterance.
 */
class DeepgramSttClient(private val apiKey: String) {

    interface Listener {
        fun onOpen()
        fun onInterim(text: String)
        fun onFinal(text: String, speechFinal: Boolean)
        fun onError(code: String, message: String)
        fun onClosed()
    }

    private var httpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private val closed = AtomicBoolean(false)
    @Volatile
    private var lastSendMs: Long = 0L

    private fun buildUrl(useFallback: Boolean): String {
        return if (useFallback) {
            "wss://api.deepgram.com/v1/listen?model=nova-2&language=hi" +
                "&interim_results=true&encoding=linear16&sample_rate=16000&channels=1"
        } else {
            "wss://api.deepgram.com/v1/listen?model=nova-3&language=multi" +
                "&language_hint=hi&language_hint=en&interim_results=true" +
                "&encoding=linear16&sample_rate=16000&channels=1"
        }
    }

    /**
     * Opens the stream. Results arrive on [listener] (OkHttp
     * dispatcher thread — the manager hops to main as needed).
     * Call [close] to end the stream; a failed handshake reports
     * through [Listener.onError] (HTTP code when OkHttp has one).
     */
    fun connect(listener: Listener, useFallback: Boolean = false) {
        closed.set(false)
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        httpClient = client
        val request = Request.Builder()
            .url(buildUrl(useFallback))
            .header("Authorization", "Token $apiKey")
            .build()
        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") != "Results") {
                        if (json.optString("type") == "Error") {
                            listener.onError(
                                "DEEPGRAM_ERROR",
                                "Deepgram error ${json.optString("err_code")}: " +
                                    json.optString("err_msg")
                            )
                        }
                        return
                    }
                    val channel = json.optJSONObject("channel") ?: return
                    val alt = channel.optJSONArray("alternatives")?.optJSONObject(0)
                        ?: return
                    val transcript = alt.optString("transcript", "").trim()
                    if (transcript.isEmpty()) return
                    val isFinal = channel.optBoolean("is_final", false)
                    val speechFinal = channel.optBoolean("speech_final", false)
                    if (isFinal) {
                        listener.onFinal(transcript, speechFinal)
                    } else {
                        listener.onInterim(transcript)
                    }
                } catch (e: Exception) {
                    // A malformed frame must never kill the stream.
                    listener.onError("PARSE_ERROR", "Deepgram frame parse failed: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Deepgram sends text frames for results; ignore binary.
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed.get()) listener.onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code
                val msg = if (httpCode != null) {
                    "Deepgram connect failed: HTTP $httpCode ${response.message}"
                } else {
                    "Deepgram connect failed: ${t.message ?: t.javaClass.simpleName}"
                }
                listener.onError("WS_FAILURE", msg)
            }
        }
        webSocket = client.newWebSocket(request, wsListener)
    }

    /** Sends one PCM chunk (linear16, 16kHz, mono). Thread-safe. */
    fun sendPcm(bytes: ByteArray, length: Int): Boolean {
        if (closed.get()) return false
        val ws = webSocket ?: return false
        // Buffer.readByteString() is the version-proof binary
        // path: ByteString.of() is ERROR-level deprecated under
        // Okio 3.x, and the toByteString extension import does
        // not resolve on this toolchain (CI-verified twice).
        val ok = ws.send(Buffer().write(bytes, 0, length).readByteString())
        if (ok) lastSendMs = System.currentTimeMillis()
        return ok
    }

    /** App-level keep-alive (Deepgram closes idle streams). */
    fun sendKeepAliveIfIdle(idleMs: Long = 5_000L) {
        if (closed.get()) return
        if (System.currentTimeMillis() - lastSendMs < idleMs) return
        webSocket?.send("{\"type\":\"KeepAlive\"}")
        lastSendMs = System.currentTimeMillis()
    }

    fun finalizeStream() {
        webSocket?.send("{\"type\":\"Finalize\"}")
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { webSocket?.send("{\"type\":\"CloseStream\"}") }
        runCatching { webSocket?.close(1000, null) }
        webSocket = null
        runCatching { httpClient?.dispatcher?.executorService?.shutdown() }
        httpClient = null
    }

    /**
     * Cheap auth check for the AI Brain "Test Deepgram key"
     * button: opens the stream and closes it immediately. Returns
     * a VERBATIM result (HTTP code when available). Never includes
     * the key.
     */
    suspend fun testAuth(): String {
        return withTimeoutOrNull(12_000L) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Listener {
                    override fun onOpen() {
                        if (cont.isActive) cont.resume("Deepgram OK — connected (nova-3).")
                        close()
                    }

                    override fun onInterim(text: String) {}
                    override fun onFinal(text: String, speechFinal: Boolean) {}
                    override fun onError(code: String, message: String) {
                        if (cont.isActive) cont.resume("Deepgram error [$code]: $message")
                        close()
                    }

                    override fun onClosed() {
                        if (cont.isActive) {
                            cont.resume("Deepgram closed before auth completed.")
                        }
                    }
                }
                connect(listener)
                cont.invokeOnCancellation { close() }
            }
        } ?: run {
            close()
            "Deepgram test timed out after 12s — network ya key check karo."
        }
    }

    /**
     * One-shot listening for the loop-OFF single-reply path.
     * Owns a short-lived AudioRecord (the loop is off, so no mic
     * conflict). 12s budget, kept from the old single-shot path.
     *
     * 1.4.5 (item 4): ENDPOINTING PROOF. The old code resumed
     * ONLY on speech_final=true — if Deepgram never sends
     * speech_final, the attempt burned the full 12 s and returned
     * null even though a complete final transcript had arrived.
     * Now it resumes on EITHER speech_final (immediate, reason
     * speech_final) OR a non-blank final followed by a 1,500 ms
     * quiet window (reason final_quiet). EVERY exit logs
     * ONE_SHOT_END with reason (speech_final / final_quiet /
     * timeout / error / closed) + duration, so the endpointing
     * behaviour is provable from Diagnostics, not guessed.
     */
    suspend fun listenOnce(timeoutMs: Long = 12_000L): String? {
        val startMs = System.currentTimeMillis()
        var endReason = "timeout"
        var endText: String? = null
        var recordRef: AudioRecord? = null
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val minBuf = AudioRecord.getMinBufferSize(
                    16_000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) {
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                val record = runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        16_000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                    )
                }.getOrNull()
                if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                    runCatching { record?.release() }
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                recordRef = record
                val pump = Thread {
                    val buf = ByteArray(3200)
                    try {
                        record.startRecording()
                        while (cont.isActive && !closed.get()) {
                            val read = record.read(buf, 0, buf.size)
                            if (read > 0) sendPcm(buf, read)
                        }
                    } catch (e: Exception) {
                        // Pump death ends the attempt via timeout/close.
                    }
                }
                val quietHandler = Handler(Looper.getMainLooper())
                var lastFinal: String? = null
                var finished = false
                fun finish(text: String?, reason: String) {
                    if (finished) return
                    finished = true
                    endReason = reason
                    endText = text
                    quietHandler.removeCallbacksAndMessages(null)
                    if (cont.isActive) cont.resume(text)
                }
                val quietRunnable = Runnable {
                    val t = lastFinal
                    if (t != null && t.isNotBlank()) finish(t, "final_quiet")
                }
                val listener = object : Listener {
                    override fun onOpen() {
                        pump.start()
                    }

                    override fun onInterim(text: String) {}
                    override fun onFinal(text: String, speechFinal: Boolean) {
                        if (text.isBlank()) return
                        lastFinal = text
                        if (speechFinal) {
                            finish(text, "speech_final")
                        } else {
                            // Non-final transcript + quiet window
                            // = utterance done (endpointing proof).
                            quietHandler.removeCallbacks(quietRunnable)
                            quietHandler.postDelayed(quietRunnable, 1500L)
                        }
                    }

                    override fun onError(code: String, message: String) {
                        finish(null, "error")
                    }

                    override fun onClosed() {
                        finish(null, "closed")
                    }
                }
                connect(listener)
                cont.invokeOnCancellation {
                    runCatching { record.stop() }
                    runCatching { record.release() }
                    close()
                }
            }
        }
        // Every exit path frees the one-shot mic and the stream
        // — no mic leak. Endpointing proof on EVERY exit.
        runCatching { recordRef?.stop() }
        runCatching { recordRef?.release() }
        close()
        if (result != null) endReason = endReason.takeIf { it != "timeout" } ?: "speech_final"
        JarvisStateRepository.addDiagnostic(
            "stt", "ONE_SHOT_END",
            "One-shot ended reason=$endReason durationMs=${System.currentTimeMillis() - startMs} " +
                "textPresent=${result != null}."
        )
        return result
    }
}
