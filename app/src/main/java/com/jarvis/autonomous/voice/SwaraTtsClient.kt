package com.jarvis.autonomous.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import com.jarvis.autonomous.data.JarvisStateRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * 1.4.12 — Swara PRIMARY voice (Microsoft Edge Read-Aloud,
 * hi-IN-SwaraNeural, cloud, free, no key, no account).
 *
 * Replaces the 1.4.11 bundled on-device voice entirely: no bundled
 * model, no native engine, no isolated :tts process. Speech
 * runs in the MAIN process through this client, and markers
 * flow through JarvisStateRepository.addDiagnostic directly
 * (there is no :tts stage relay any more).
 *
 * Protocol (mirrors the installed edge-tts package, whose
 * source was read at implementation time — constants.py,
 * communicate.py, drm.py):
 * - wss://speech.platform.bing.com/consumer/speech/synthesize/
 *   readaloud/edge/v1 + TrustedClientToken + ConnectionId +
 *   Sec-MS-GEC + Sec-MS-GEC-Version=2-143.0.3650.75. Auth is
 *   the time-derived GEC token (SwaraDrm, a mechanical port of
 *   edge-tts DRM.generate_sec_ms_gec_v2) — no user credential,
 *   no key to enter, nothing to log. The URL is NEVER logged
 *   (it carries token/GEC as query params).
 * - Browser-like headers: Edge UA, Origin
 *   chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold,
 *   Pragma/Cache-Control no-cache, Accept-Encoding,
 *   Accept-Language. Sec-WebSocket-Version is NOT set manually
 *   (OkHttp sets its own). Cookie: muid=<32 hex upper, random
 *   per connection>.
 * - Handshake: Path:speech.config FIRST (output format
 *   audio-24khz-48kbitrate-mono-mp3, sentenceBoundary on,
 *   wordBoundary off), then one Path:ssml per chunk.
 * - SSML shape (edge-tts hardcodes xml:lang='en-US'
 *   regardless of voice — replicated, not a mistake):
 *   <speak version='1.0' ... xml:lang='en-US'><voice
 *   name='hi-IN-SwaraNeural'><prosody pitch='+0Hz'
 *   rate='+0%' volume='+0%'>ESCAPED_TEXT</prosody></voice>
 *   </speak>. SSML X-Timestamp carries the trailing Z (a
 *   Microsoft Edge bug replicated by edge-tts); speech.config
 *   uses the date string WITHOUT the trailing Z.
 * - Text handling: chars 0-8, 11-12, 14-31 become spaces, then
 *   XML-escape (& < > " ') PER CHUNK, so no entity can split
 *   across a chunk boundary.
 * - Chunking: char-based, cap ~3500 chars/chunk, preferring
 *   sentence boundaries (., !, ?, Devanagari danda, newline).
 *   Sequential SSMLs go on the SAME socket within one speak()
 *   call; each SSML produces its own turn.end, and only the
 *   LAST request's turn.end ends the speak.
 * - Streaming frames: binary WebSocket frames, first 2 bytes =
 *   big-endian header length; headers parsed from
 *   data[:header_length] split by \r\n, each split by ':'
 *   (first colon); audio = data[header_length + 2:]. Text
 *   messages: headers found via the "\r\n\r\n" index; Path
 *   audio.metadata / response / turn.start are ignored, unknown
 *   text paths are ignored (never fail the speak), turn.end
 *   counts. Binary with Content-Type other than audio/mpeg or
 *   None fails; binary with no Content-Type and empty data is
 *   skipped (expected at stream termination); binary with no
 *   Content-Type and data fails; binary with empty data fails.
 *   No audio at all by turn.end fails (no-audio).
 * - FAST REPLY: playback starts on the FIRST Path:audio frame
 *   — never waits for turn.end. MP3 is decoded incrementally
 *   (MediaCodec "audio/mpeg" -> 24 kHz mono s16le PCM) into a
 *   streaming AudioTrack, first-PCM-ASAP.
 *
 * Bounded everywhere (a Swara speak MUST return, so SPK_END
 * is always written and the greeting circuit breaker never
 * trips on a hung socket):
 * - Socket connect: OkHttp 10 s (same as Deepgram).
 * - First audio: 20 s from speak start (no Path:audio in
 *   bound -> fail -> latch count -> fallback).
 * - Overall speak: min(180 s, 30 s + len*100 ms) — the same
 *   shape as the system-voice fallback bound.
 * - 403 handling: ONE token-regen retry + reconnect, and only
 *   when no audio has played yet. No full clock-skew tracking:
 *   if the phone clock is off by >5 min, Edge fails, the
 *   latch trips, and the system voice speaks (never silent).
 * - stopSpeaking / barge-in: closes the socket, stops the
 *   track, flushes the decoder, bumps the generation — the
 *   in-flight speak returns cancelled WITHOUT counting toward
 *   the latch (explicit stop is not a failure).
 *
 * Latch (same shape as 1.4.10, Swara failure kinds):
 * bound 3, cooldown 10 min, clears ONLY on Test Voice tap /
 * Settings-reset-equivalent / Swara toggle-on / cooldown
 * expiry. COUNTED: connect failure (incl. 403 after the one
 * retry), first-audio timeout, turn timeout / socket failure
 * mid-stream, speak timeout. NOT counted: blank text (rejected
 * before speak), explicit stop/cancellation (barge-in),
 * toggle-OFF (not a failure — VoiceManager speaks via the
 * system voice instead, never silence).
 *
 * Privacy: NEVER logs spoken text, SSML text, URLs with query
 * strings, or tokens — stage + char count only. The
 * diagnostics sanitizer is backstop, not the plan.
 *
 * Threading: speak() runs on Dispatchers.IO (MediaCodec
 * dequeue uses blocking timeouts, never sleep-polling, and
 * never a coroutine delay() in the speak path). OkHttp
 * callbacks only enqueue events; all diagnostics, decoding
 * and playback happen on the IO thread. stop() is safe from
 * any thread. Speaks are serialised by VoiceManager's mutex;
 * a second concurrent speak here is refused defensively
 * (speaking flag) rather than queued.
 */
object SwaraTtsClient {

    const val VOICE_NAME = "hi-IN-SwaraNeural"

    /** Last failure code of the most recent speak (null = last speak ok or not run). Read by speakGreeting for its visible GREET_FAIL stage. */
    @Volatile
    var lastFailure: String? = null
        private set

    const val FAIL_CONNECT = "SWARA_CONNECT_FAILED"
    const val FAIL_SYNTHESIZE = "SWARA_SYNTHESIZE_FAILED"
    const val FAIL_NO_AUDIO = "SWARA_NO_AUDIO"
    const val FAIL_TIMEOUT = "SWARA_TIMEOUT"
    const val FAIL_CANCELLED = "SWARA_CANCELLED"

    private const val CONNECT_TIMEOUT_S = 10L
    private const val FIRST_AUDIO_TIMEOUT_MS = 20_000L
    private const val MAX_CHUNK_CHARS = 3500
    private const val SAMPLE_RATE = 24000

    private const val EDGE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
            " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    private const val EDGE_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    // ---- Latch (bound 3 / cooldown 10 min) ----
    private val latchLock = Any()
    private const val LATCH_BOUND = 3
    private const val LATCH_COOLDOWN_MS = 10L * 60L * 1000L
    private var consecutiveFailures = 0
    private var swaraLatched = false
    private var latchedAtMs = 0L

    /** True while the latch is active. A 10-minute cooldown expiry clears it (marked, not silent). */
    fun isSwaraLatched(): Boolean {
        var expired = false
        val latched = synchronized(latchLock) {
            if (!swaraLatched) {
                false
            } else if (System.currentTimeMillis() - latchedAtMs >= LATCH_COOLDOWN_MS) {
                swaraLatched = false
                consecutiveFailures = 0
                expired = true
                false
            } else {
                true
            }
        }
        if (expired) {
            JarvisStateRepository.addDiagnostic(
                "tts", "SWARA_LATCH_EXPIRED",
                "Swara latch expired after 10 min cooldown — Swara retries on the next speak."
            )
            JarvisStateRepository.setSwaraLatched(false)
        }
        return latched
    }

    /** User-action reopen (Test Voice tap, Settings reset, Swara toggle-on). Silent no-op when not latched. */
    fun clearLatch(reason: String) {
        var wasLatched = false
        synchronized(latchLock) {
            wasLatched = swaraLatched
            swaraLatched = false
            consecutiveFailures = 0
        }
        if (wasLatched) {
            JarvisStateRepository.addDiagnostic(
                "tts", "SWARA_LATCH_CLEARED",
                "Swara latch cleared by user action ($reason) — Swara retries on the next speak."
            )
            JarvisStateRepository.setSwaraLatched(false)
        }
    }

    private fun recordFailure(kind: String) {
        var tripped = false
        var count = 0
        synchronized(latchLock) {
            consecutiveFailures++
            count = consecutiveFailures
            if (!swaraLatched && count >= LATCH_BOUND) {
                swaraLatched = true
                latchedAtMs = System.currentTimeMillis()
                tripped = true
            }
        }
        if (tripped) {
            JarvisStateRepository.addDiagnostic(
                "tts", "SWARA_UNAVAILABLE_LATCHED",
                "Swara failed $count consecutive speaks — auto-retry STOPPED, system-voice fallback active. " +
                    "Latch opens on Test Voice / Settings reset / Swara toggle-on / 10-min cooldown."
            )
            JarvisStateRepository.setSwaraLatched(true)
        } else {
            JarvisStateRepository.addDiagnostic(
                "tts", "SWARA_FAIL_COUNT",
                "Consecutive failed speaks: $count/$LATCH_BOUND (kind=$kind) — latch trips at $LATCH_BOUND."
            )
        }
    }

    private fun recordSuccess() {
        synchronized(latchLock) { consecutiveFailures = 0 }
    }

    // ---- Runtime state (stop/cancel from any thread) ----
    private val generation = AtomicInteger(0)
    private val cancelled = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)

    @Volatile
    private var currentSocket: WebSocket? = null

    @Volatile
    private var currentTrack: AudioTrack? = null

    @Volatile
    private var currentDecoder: MediaCodec? = null

    @Volatile
    private var currentPlayback: Playback? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Cancels the in-flight speak, if any: socket closed, track
     * stopped/flushed, decoder flushed, generation bumped so the
     * IO loop exits as cancelled (NOT counted as a failure).
     * Safe to call when nothing is speaking (no-op).
     */
    fun stop() {
        cancelled.set(true)
        generation.incrementAndGet()
        runCatching { currentSocket?.close(1000, "stop") }
        runCatching { currentTrack?.stop() }
        runCatching { currentTrack?.flush() }
        runCatching { currentDecoder?.flush() }
        runCatching { currentPlayback?.cancelTailWait() }
    }

    /**
     * Settings-reconnect path: drops the live socket (if any)
     * and clears the latch (the "Settings reset" latch-clear).
     * Callers may also call clearLatch("settings-reset") — the
     * second clear is a silent no-op when not latched.
     */
    fun resetConnection() {
        runCatching { currentSocket?.close(1000, "reset") }
        clearLatch("settings-reset")
    }

    /**
     * 1.4.12 first-run cleanup: deletes the legacy 1.4.11
     * legacy bundled-voice model dir (~190 MB) on first 1.4.12 run.
     * Best-effort, never blocking speech, never throwing: call
     * from a background thread (MainActivity launch does so).
     * Rollback to 1.4.11 would re-copy from that APK's bundled
     * assets, so no rollback path is lost.
     */
    fun cleanupLegacyModelDir(context: Context) {
        runCatching {
            val dir = File(context.filesDir, "Kokoro")
            if (dir.exists()) {
                dir.deleteRecursively()
                JarvisStateRepository.addLog(
                    "Legacy bundled-voice model dir removed on 1.4.12 first run (Swara needs no local model)."
                )
            }
        }
    }

    // ---- Public speak ----

    /**
     * Speaks [text] via Swara streaming. Returns true only if
     * the full text played. False on any failure (latch counted
     * inside, except cancellation/blank which never count).
     * Never throws for a speak failure — failures return false.
     * Never logs spoken text (stage + char count only).
     */
    suspend fun speak(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        if (!speaking.compareAndSet(false, true)) {
            JarvisStateRepository.addLog(
                "Swara speak refused: another Swara speak is already running (VoiceManager serialises — defensive only)."
            )
            return false
        }
        try {
            return withContext(Dispatchers.IO) { runSpeak(text) }
        } finally {
            speaking.set(false)
        }
    }

    // ---- Chunking / SSML ----

    private fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            val code = c.code
            if ((code in 0..8) || (code in 11..12) || (code in 14..31)) {
                sb.append(' ')
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun xmlEscape(text: String): String {
        // Order matters: & first, so the entities below are not re-escaped.
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * Char-based chunks, cap ~3500 chars, preferring sentence
     * boundaries (., !, ?, Devanagari danda, newline). Escaping
     * happens PER CHUNK (in [chunkSsmlMessages]), never on the
     * whole text first, so no entity can split across chunks.
     */
    internal fun splitChunks(text: String): List<String> {
        val cleaned = cleanText(text)
        if (cleaned.isBlank()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        val len = cleaned.length
        while (start < len) {
            var end = minOf(start + MAX_CHUNK_CHARS, len)
            if (end < len) {
                var cut = -1
                var i = end - 1
                val floor = start + 1000
                while (i >= floor) {
                    val c = cleaned[i]
                    if ((c == '.' || c == '!' || c == '?' || c == '।' || c == '\n') &&
                        (i + 1 >= len || cleaned[i + 1] == ' ' || cleaned[i + 1] == '\n')
                    ) {
                        cut = i + 1
                        break
                    }
                    i--
                }
                if (cut > start) {
                    end = cut
                } else {
                    var j = end - 1
                    while (j >= floor) {
                        if (cleaned[j] == ' ') {
                            cut = j + 1
                            break
                        }
                        j--
                    }
                    if (cut > start) end = cut
                }
            }
            val chunk = cleaned.substring(start, end)
            if (chunk.isNotBlank()) out.add(chunk)
            // Progress is guaranteed: end > start whenever end < len
            // falls through without a cut (end == start + cap), and
            // end == len terminates the loop.
            start = end
        }
        return out
    }

    /** "%a %b %d %Y %H:%M:%S GMT+0000 (Coordinated Universal Time)" in UTC, as edge-tts date_to_string(). */
    private fun edgeDateString(): String {
        val fmt = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US
        )
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun speechConfigMessage(): String {
        val ts = edgeDateString()
        return "X-Timestamp:$ts\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
            "{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
    }

    private fun ssmlMessage(escapedChunk: String): String {
        val reqId = SwaraDrm.connectId()
        // Trailing Z on X-Timestamp is a Microsoft Edge bug
        // replicated by edge-tts — NOT a mistake. xml:lang is
        // hardcoded 'en-US' by edge-tts regardless of voice.
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$VOICE_NAME'>" +
            "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>$escapedChunk</prosody>" +
            "</voice></speak>"
        return "X-RequestId:$reqId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${edgeDateString()}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml
    }

    // ---- WebSocket events (listener only enqueues) ----

    private sealed class WsEvent {
        object Connected : WsEvent()
        class Audio(val bytes: ByteArray) : WsEvent()
        object TurnEnd : WsEvent()
        class Failure(val cause: String, val httpCode: Int?) : WsEvent()
        object Closed : WsEvent()
    }

    private sealed class AttemptResult {
        object Success : AttemptResult()
        object Cancelled : AttemptResult()
        class Failure(
            val cause: String,
            val code: String,
            val retryable403: Boolean,
            val anyAudio: Boolean
        ) : AttemptResult()
    }

    private suspend fun runSpeak(text: String): Boolean {
        val myGen = generation.get()
        cancelled.set(false)
        val chunks = splitChunks(text)
        if (chunks.isEmpty()) return false
        val startMs = System.currentTimeMillis()
        val overallTimeoutMs = minOf(180_000L, 30_000L + text.length * 100L)
        var attempt = 0
        while (true) {
            if (cancelled.get() || generation.get() != myGen) {
                lastFailure = FAIL_CANCELLED
                JarvisStateRepository.addDiagnostic(
                    "tts", "SWARA_FAILED",
                    "Swara failed (cause=cancelled, chars=${text.length})."
                )
                return false
            }
            when (val result = runAttempt(chunks, text.length, myGen, startMs, overallTimeoutMs)) {
                is AttemptResult.Success -> {
                    recordSuccess()
                    lastFailure = null
                    JarvisStateRepository.addDiagnostic(
                        "tts", "SWARA_END",
                        "Swara speak finished (chars=${text.length})."
                    )
                    return true
                }
                is AttemptResult.Cancelled -> {
                    lastFailure = FAIL_CANCELLED
                    JarvisStateRepository.addDiagnostic(
                        "tts", "SWARA_FAILED",
                        "Swara failed (cause=cancelled, chars=${text.length})."
                    )
                    return false
                }
                is AttemptResult.Failure -> {
                    if (result.retryable403 && attempt == 0 && !result.anyAudio) {
                        // ONE token-regen retry + reconnect: the
                        // retry builds a fresh GEC inside
                        // runAttempt. No SWARA_FAILED marker here —
                        // the retry may still succeed, and a
                        // failed-then-retried marker would leave a
                        // stale stuck cause on the tracker.
                        JarvisStateRepository.addLog(
                            "Swara 403 — one token-regen retry + reconnect (no audio played yet)."
                        )
                        attempt++
                        continue
                    }
                    lastFailure = result.code
                    JarvisStateRepository.addDiagnostic(
                        "tts", "SWARA_FAILED",
                        "Swara failed (cause=${result.cause}, chars=${text.length})."
                    )
                    recordFailure(result.cause)
                    return false
                }
            }
        }
    }

    /**
     * One socket attempt: connect, speech.config, all SSMLs,
     * stream audio to first-PCM-ASAP playback, count turn.ends.
     * Returns Success only when every chunk's turn.end arrived
     * AND audio played (no-audio at turn.end is a failure).
     */
    private suspend fun runAttempt(
        chunks: List<String>,
        charCount: Int,
        myGen: Int,
        speakStartMs: Long,
        overallTimeoutMs: Long
    ): AttemptResult {
        val events = LinkedBlockingQueue<WsEvent>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                events.offer(WsEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                val idx = indexOfDoubleCrlf(bytes)
                if (idx < 0) return // Unknown text frame: ignore, never fail.
                val path = headerValue(bytes, 0, idx, "Path")
                when (path) {
                    "turn.end" -> events.offer(WsEvent.TurnEnd)
                    // audio.metadata / response / turn.start:
                    // ignored (no boundary metadata is consumed).
                    // Unknown text paths: ignored per spec.
                    else -> Unit
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) {
                    events.offer(WsEvent.Failure("synthesize", null))
                    return
                }
                // edge-tts slicing, exactly: header length is the
                // first 2 bytes big-endian; headers are
                // data[:header_length]; audio is
                // data[header_length + 2:].
                val headerLength = ((data[0].toInt() and 0xFF) shl 8) or
                    (data[1].toInt() and 0xFF)
                if (headerLength > data.size) {
                    events.offer(WsEvent.Failure("synthesize", null))
                    return
                }
                val headers = parseHeaders(data, 0, headerLength)
                val path = headers["Path"]
                if (path != "audio") {
                    events.offer(WsEvent.Failure("synthesize", null))
                    return
                }
                val contentType = headers["Content-Type"]
                val audio = if (headerLength + 2 <= data.size) {
                    data.copyOfRange(headerLength + 2, data.size)
                } else {
                    ByteArray(0)
                }
                when {
                    contentType != null && contentType != "audio/mpeg" -> {
                        events.offer(WsEvent.Failure("synthesize", null))
                    }
                    contentType == null && audio.isEmpty() -> {
                        // Expected at stream termination: skip.
                        Unit
                    }
                    contentType == null -> {
                        events.offer(WsEvent.Failure("synthesize", null))
                    }
                    audio.isEmpty() -> {
                        events.offer(WsEvent.Failure("synthesize", null))
                    }
                    else -> events.offer(WsEvent.Audio(audio))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.offer(WsEvent.Closed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                val is403 = code == 403 || (t.message?.contains("403") == true)
                // Phase (pre-connect vs mid-stream) is decided by
                // the caller from `connected`; only the 403 flag
                // matters here (one regen retry, audio-free only).
                events.offer(WsEvent.Failure("connect", if (is403) 403 else code))
            }
        }

        var socket: WebSocket? = null
        val playback = Playback(
            myGen, cancelled, generation,
            { currentDecoder = it }, { currentTrack = it }
        )
        currentPlayback = playback
        try {
            val url = SwaraDrm.buildSpeechUrl(
                SwaraDrm.connectId(),
                SwaraDrm.generateSecMsGec()
            )
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", EDGE_UA)
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", EDGE_ORIGIN)
                .header("Cookie", "muid=${SwaraDrm.generateMuid()};")
                .build()
            socket = httpClient.newWebSocket(request, listener)
            currentSocket = socket

            val overallDeadlineMs = speakStartMs + overallTimeoutMs
            val firstAudioDeadlineMs = speakStartMs + FIRST_AUDIO_TIMEOUT_MS
            var connected = false
            var turnEnds = 0
            var anyAudio = false
            var firstAudioMs = -1L
            var failure: AttemptResult.Failure? = null
            var tailCancelled = false
            var done = false

            while (!done) {
                if (cancelled.get() || generation.get() != myGen) {
                    return AttemptResult.Cancelled
                }
                val now = System.currentTimeMillis()
                if (!anyAudio && now > firstAudioDeadlineMs) {
                    failure = AttemptResult.Failure("timeout", FAIL_TIMEOUT, false, false)
                    break
                }
                if (now > overallDeadlineMs) {
                    failure = AttemptResult.Failure("timeout", FAIL_TIMEOUT, false, anyAudio)
                    break
                }
                val ev = events.poll(20, TimeUnit.MILLISECONDS)
                if (ev == null) {
                    playback.feedPending()
                    playback.drainOutput(0)
                    continue
                }
                when (ev) {
                    is WsEvent.Connected -> {
                        connected = true
                        JarvisStateRepository.addDiagnostic(
                            "tts", "SWARA_CONNECT",
                            "Swara connected (chars=$charCount)."
                        )
                        val ok = runCatching {
                            var allSent = socket?.send(speechConfigMessage()) == true
                            if (allSent) {
                                for (chunk in chunks) {
                                    val sentOne = socket?.send(
                                        ssmlMessage(xmlEscape(chunk))
                                    ) == true
                                    if (!sentOne) {
                                        allSent = false
                                        break
                                    }
                                }
                            }
                            allSent
                        }.getOrDefault(false)
                        if (!ok) {
                            failure = AttemptResult.Failure(
                                "synthesize", FAIL_SYNTHESIZE, false, anyAudio
                            )
                            done = true
                        }
                    }
                    is WsEvent.Audio -> {
                        if (!anyAudio) {
                            anyAudio = true
                            firstAudioMs = System.currentTimeMillis()
                            JarvisStateRepository.addDiagnostic(
                                "tts", "SWARA_FIRST_AUDIO",
                                "First Swara audio after ${firstAudioMs - speakStartMs}ms (chars=$charCount)."
                            )
                            if (!playback.ensureDecoder()) {
                                failure = AttemptResult.Failure(
                                    "synthesize", FAIL_SYNTHESIZE, false, true
                                )
                                done = true
                                continue
                            }
                        }
                        playback.queueAudio(ev.bytes)
                        playback.feedPending()
                        playback.drainOutput(10_000)
                    }
                    is WsEvent.TurnEnd -> {
                        turnEnds++
                        if (turnEnds >= chunks.size) {
                            if (!anyAudio) {
                                failure = AttemptResult.Failure(
                                    "no-audio", FAIL_NO_AUDIO, false, false
                                )
                                done = true
                            } else {
                                // Drain everything still queued,
                                // then decoder EOS, then let the
                                // track play out to its marker.
                                // G8 F1: a barge-in during this tail
                                // window must report Cancelled, not
                                // Success — waitPlaybackComplete
                                // returns false when cut short.
                                playback.feedPending()
                                playback.finishAndDrain(overallDeadlineMs)
                                if (!playback.waitPlaybackComplete()) {
                                    tailCancelled = true
                                }
                                done = true
                            }
                        }
                    }
                    is WsEvent.Failure -> {
                        val retryable = ev.httpCode == 403 && !anyAudio && !connected
                        val cause = if (ev.httpCode == 403) {
                            "connect"
                        } else if (!connected) {
                            "connect"
                        } else {
                            "synthesize"
                        }
                        val code = when {
                            ev.httpCode == 403 -> FAIL_CONNECT
                            !connected -> FAIL_CONNECT
                            else -> FAIL_SYNTHESIZE
                        }
                        failure = AttemptResult.Failure(cause, code, retryable, anyAudio)
                        done = true
                    }
                    is WsEvent.Closed -> {
                        if (turnEnds < chunks.size) {
                            val cause = if (!connected) "connect" else "synthesize"
                            val code = if (!connected) FAIL_CONNECT else FAIL_SYNTHESIZE
                            failure = AttemptResult.Failure(cause, code, false, anyAudio)
                            done = true
                        }
                    }
                }
                playback.feedPending()
                playback.drainOutput(0)
            }
            if (tailCancelled) return AttemptResult.Cancelled
            if (failure != null) return failure
            // Reached here only via the TurnEnd branch with audio:
            // success (finishAndDrain already ran). If the loop
            // exited without failure and without completing turns
            // (should not happen), treat as a timeout.
            return if (turnEnds >= chunks.size && anyAudio) {
                AttemptResult.Success
            } else {
                AttemptResult.Failure("timeout", FAIL_TIMEOUT, false, anyAudio)
            }
        } catch (e: Exception) {
            // Never throw out of a speak: degrade to a visible
            // failure (type name only — never text/URLs/tokens).
            return AttemptResult.Failure(
                "synthesize", FAIL_SYNTHESIZE, false, false
            )
        } finally {
            playback.release()
            if (currentPlayback === playback) currentPlayback = null
            runCatching { socket?.close(1000, "done") }
            if (currentSocket === socket) currentSocket = null
        }
    }

    // ---- Header parsing (edge-tts slicing, exactly) ----

    private fun indexOfDoubleCrlf(bytes: ByteArray): Int {
        var i = 0
        while (i + 3 < bytes.size) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
            i++
        }
        return -1
    }

    private fun parseHeaders(data: ByteArray, from: Int, to: Int): Map<String, String> {
        val out = HashMap<String, String>()
        if (to <= from) return out
        val text = String(data, from, to - from, Charsets.UTF_8)
        for (line in text.split("\r\n")) {
            val colon = line.indexOf(':')
            if (colon > 0) {
                out[line.substring(0, colon)] = line.substring(colon + 1)
            }
        }
        return out
    }

    private fun headerValue(bytes: ByteArray, from: Int, to: Int, name: String): String? {
        return parseHeaders(bytes, from, to)[name]
    }

    // ---- Incremental MP3 decode -> AudioTrack playback ----

    /**
     * Owns one MediaCodec MP3 decoder + one streaming AudioTrack
     * for a single attempt. Plain nested class (not inner — an
     * inner class inside an object is a needless compile risk);
     * outer state arrives explicitly. All calls happen on the
     * attempt IO thread except stop(), which only touches the
     * volatile track/decoder refs via runCatching (any
     * IllegalState there is swallowed; the IO loop sees the
     * cancelled flag and exits as Cancelled).
     */
    private class Playback(
        private val myGen: Int,
        private val cancelled: AtomicBoolean,
        private val generation: AtomicInteger,
        private val onDecoder: (MediaCodec?) -> Unit,
        private val onTrack: (AudioTrack?) -> Unit
    ) {
        private var decoder: MediaCodec? = null
        private var track: AudioTrack? = null
        private var trackRate = SAMPLE_RATE
        private var trackChannels = 1
        private var pending: ByteArray = ByteArray(0)
        private var pendingOff = 0
        private var inputPtsUs = 0L
        private var totalFramesWritten = 0L
        private var streamingMarked = false
        private var eosOutputSeen = false

        fun ensureDecoder(): Boolean {
            if (decoder != null) return true
            return try {
                val dc = MediaCodec.createDecoderByType("audio/mpeg")
                val fmt = MediaFormat.createAudioFormat("audio/mpeg", SAMPLE_RATE, 1)
                dc.configure(fmt, null, null, 0)
                dc.start()
                decoder = dc
                onDecoder(dc)
                true
            } catch (e: Exception) {
                false
            }
        }

        private fun ensureTrack(): Boolean {
            // Refresh rate/channels from the decoder's output
            // format when available (24 kHz mono expected).
            runCatching {
                val fmt = decoder?.outputFormat
                if (fmt != null) {
                    runCatching { trackRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                    runCatching { trackChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                }
            }
            if (track != null) return true
            return try {
                val minBuf = AudioTrack.getMinBufferSize(
                    trackRate,
                    if (trackChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) return false
                val tr = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(trackRate)
                            .setChannelMask(
                                if (trackChannels == 1) AudioFormat.CHANNEL_OUT_MONO
                                else AudioFormat.CHANNEL_OUT_STEREO
                            )
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBuf * 4)
                    .build()
                tr.play()
                track = tr
                onTrack(tr)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun queueAudio(bytes: ByteArray) {
            if (pendingOff >= pending.size) {
                pending = bytes
                pendingOff = 0
            } else {
                val rest = pending.copyOfRange(pendingOff, pending.size)
                val merged = ByteArray(rest.size + bytes.size)
                rest.copyInto(merged, 0)
                bytes.copyInto(merged, rest.size)
                pending = merged
                pendingOff = 0
            }
        }

        /** Pushes pending MP3 bytes into decoder input buffers (non-blocking dequeue). */
        fun feedPending() {
            val dc = decoder ?: return
            while (pendingOff < pending.size) {
                val idx = try {
                    dc.dequeueInputBuffer(0)
                } catch (e: Exception) {
                    -1
                }
                if (idx < 0) return
                try {
                    val buf = dc.getInputBuffer(idx) ?: run {
                        dc.queueInputBuffer(idx, 0, 0, inputPtsUs, 0)
                        return
                    }
                    val n = minOf(buf.remaining(), pending.size - pendingOff)
                    if (n <= 0) return
                    buf.put(pending, pendingOff, n)
                    pendingOff += n
                    // 48 kbps CBR: 6000 bytes/sec — monotonic PTS
                    // only; AudioTrack pacing comes from PCM flow.
                    inputPtsUs += n * 1_000_000L / 6000L
                    dc.queueInputBuffer(idx, 0, n, inputPtsUs, 0)
                } catch (e: Exception) {
                    return
                }
            }
        }

        /** Drains one decoder output buffer (blocking dequeue with timeout, never sleep-poll). */
        fun drainOutput(timeoutUs: Long) {
            val dc = decoder ?: return
            val info = MediaCodec.BufferInfo()
            val idx = try {
                dc.dequeueOutputBuffer(info, timeoutUs)
            } catch (e: Exception) {
                return
            }
            when {
                idx >= 0 -> {
                    try {
                        if (info.size > 0) {
                            val outBuf = dc.getOutputBuffer(idx)
                            if (outBuf != null && ensureTrack()) {
                                val pcm = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.get(pcm)
                                writePcm(pcm)
                                if (!streamingMarked) {
                                    streamingMarked = true
                                    JarvisStateRepository.addDiagnostic(
                                        "tts", "SWARA_STREAMING",
                                        "Swara streaming playback started."
                                    )
                                }
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            eosOutputSeen = true
                        }
                    } catch (e: Exception) {
                        // Dropped output buffer: playback
                        // continues with the next buffer.
                    } finally {
                        runCatching { dc.releaseOutputBuffer(idx, false) }
                    }
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    runCatching {
                        val fmt = dc.outputFormat
                        runCatching { trackRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                        runCatching { trackChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                    }
                }
                else -> Unit // INFO_TRY_AGAIN_LATER: nothing yet.
            }
        }

        private fun writePcm(pcm: ByteArray) {
            val tr = track ?: return
            var off = 0
            while (off < pcm.size) {
                val written = try {
                    tr.write(pcm, off, pcm.size - off)
                } catch (e: Exception) {
                    -1
                }
                if (written <= 0) return
                off += written
                totalFramesWritten += written / (2 * trackChannels)
            }
        }

        /** Queues decoder EOS and drains until EOS output (bounded by the overall deadline). */
        fun finishAndDrain(overallDeadlineMs: Long) {
            val dc = decoder ?: return
            feedPending()
            try {
                val idx = dc.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    dc.queueInputBuffer(idx, 0, 0, inputPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            } catch (e: Exception) {
                return
            }
            while (!eosOutputSeen && System.currentTimeMillis() <= overallDeadlineMs) {
                if (cancelled.get() || generation.get() != myGen) return
                drainOutput(10_000)
            }
        }

        /** Tail-wait deferred, completed by cancelTailWait() on stop. */
        @Volatile
        private var tailDone: CompletableDeferred<Unit>? = null

        /** Called from stop(): wakes a tail wait immediately. */
        fun cancelTailWait() {
            tailDone?.complete(Unit)
        }

        /**
         * Waits for the track to actually play out (marker at
         * the last written frame, event-driven — no polling,
         * no delay()). Bounded by remaining audio + margin.
         * Returns false when a stop cut the tail short (G8 F1);
         * a plain timeout still counts as played (audio was
         * delivered to the track), unless a stop also landed.
         */
        suspend fun waitPlaybackComplete(): Boolean {
            val tr = track ?: return true
            if (totalFramesWritten <= 0) return true
            if (cancelled.get() || generation.get() != myGen) return false
            val head = runCatching { tr.playbackHeadPosition }.getOrDefault(0)
            if (head >= totalFramesWritten) return true
            val remainingMs = ((totalFramesWritten - head) * 1000L / trackRate) + 5_000L
            val done = CompletableDeferred<Unit>()
            tailDone = done
            try {
                tr.setPlaybackPositionUpdateListener(
                    object : AudioTrack.OnPlaybackPositionUpdateListener {
                        override fun onMarkerReached(track: AudioTrack?) {
                            done.complete(Unit)
                        }

                        override fun onPeriodicNotification(track: AudioTrack?) {}
                    }
                )
                tr.notificationMarkerPosition = totalFramesWritten.toInt()
            } catch (e: Exception) {
                return true
            }
            try {
                withTimeoutOrNull(remainingMs.coerceIn(1_000L, 60_000L)) {
                    done.await()
                }
            } catch (e: Exception) {
                // Timeout: playback tail is cut, but the speak
                // still counts as played (audio was delivered
                // to the track) — unless a stop also landed.
            } finally {
                if (tailDone === done) tailDone = null
                runCatching { tr.setPlaybackPositionUpdateListener(null) }
            }
            return !(cancelled.get() || generation.get() != myGen)
        }

        fun release() {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            onTrack(null)
            track = null
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            onDecoder(null)
            decoder = null
        }
    }
}
