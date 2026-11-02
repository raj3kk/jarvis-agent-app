package com.jarvis.autonomous.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.jarvis.autonomous.data.JarvisStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deepgram TTS voice for Jarvis (user order 2026-11-02).
 *
 * API: POST https://api.deepgram.com/v1/speak?model=<voice>
 *   &encoding=linear16&sample_rate=24000, header
 *   "Authorization: Token <key>", JSON body {"text": chunk}.
 * Returns raw linear16 PCM 24kHz mono, played via AudioTrack.
 *
 * REST limit is 2000 chars/request, so text is split into
 * <=1900-char chunks on sentence boundaries and played in order.
 * Any failure (no key, 401/429, network, empty audio) returns false
 * so VoiceManager falls back to the next voice — never silent.
 *
 * API key: the user's own Deepgram key from AI Brain (prefs).
 * No key is baked into the APK (shared app = extractable key).
 * Cost honesty: Aura-2 is $0.030/1000 chars pay-as-you-go after a
 * new account's one-time $200 credit — this is NOT free forever.
 * Language honesty: Deepgram Aura-2 is English-focused; Hindi text
 * is spoken with an English accent, which is why Kokoro (hf_alpha)
 * is tried first for Hindi in VoiceManager.
 */
class DeepgramTtsClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentTrack: AudioTrack? = null
    private val stopped = AtomicBoolean(false)

    fun stopPlayback() {
        stopped.set(true)
        runCatching {
            currentTrack?.stop()
            currentTrack?.release()
            currentTrack = null
        }
    }

    suspend fun speak(text: String, apiKey: String, voiceName: String): Boolean {
        if (apiKey.isBlank() || text.isBlank()) return false
        stopped.set(false)
        return try {
            for (chunk in splitChunks(text, 1900)) {
                if (stopped.get()) return false
                val pcm = synthesize(chunk, apiKey, voiceName) ?: return false
                if (stopped.get()) return false
                if (!playPcm(pcm)) return false
            }
            true
        } catch (e: Exception) {
            JarvisStateRepository.addLog("Deepgram TTS failed (${e.message}), falling back")
            false
        }
    }

    /** Split on sentence boundaries, never exceeding [maxChars] per chunk. */
    internal fun splitChunks(text: String, maxChars: Int): List<String> {
        val sentences = text.split(Regex("(?<=[.!?\\u0964\\n])\\s+"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current = StringBuilder()
            }
        }
        for (sentence in sentences) {
            if (sentence.length > maxChars) {
                // One over-long sentence: hard-split it.
                flush()
                var rest = sentence
                while (rest.length > maxChars) {
                    chunks.add(rest.substring(0, maxChars))
                    rest = rest.substring(maxChars)
                }
                if (rest.isNotEmpty()) current.append(rest).append(" ")
            } else if (current.length + sentence.length + 1 > maxChars) {
                flush()
                current.append(sentence).append(" ")
            } else {
                current.append(sentence).append(" ")
            }
        }
        flush()
        return chunks.ifEmpty { listOf(text.take(maxChars)) }
    }

    private suspend fun synthesize(text: String, apiKey: String, voiceName: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply { put("text", text) }
            val request = Request.Builder()
                .url(
                    "https://api.deepgram.com/v1/speak?model=$voiceName" +
                        "&encoding=linear16&sample_rate=24000"
                )
                .header("Authorization", "Token $apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    JarvisStateRepository.addLog(
                        "Deepgram TTS HTTP ${resp.code} (key/quota/network) — fallback"
                    )
                    return@withContext null
                }
                val bytes = resp.body?.bytes() ?: return@withContext null
                if (bytes.isEmpty()) null else bytes
            }
        }

    private suspend fun playPcm(pcm: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            currentTrack = track
            track.play()
            var offset = 0
            while (offset < pcm.size && !stopped.get()) {
                val written = track.write(pcm, offset, pcm.size - offset)
                if (written <= 0) break
                offset += written
            }
            val frames = pcm.size / 2
            withTimeoutOrNull(30_000L) {
                while (!stopped.get() &&
                    track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    track.playbackHeadPosition < frames
                ) {
                    delay(50L)
                }
            }
            runCatching { track.stop(); track.release() }
            if (currentTrack == track) currentTrack = null
            !stopped.get()
        } catch (e: Exception) {
            JarvisStateRepository.addLog("Deepgram TTS playback failed: ${e.message}")
            false
        }
    }
}
