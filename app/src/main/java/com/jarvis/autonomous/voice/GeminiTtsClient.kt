package com.jarvis.autonomous.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
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

/**
 * Gemini TTS voice for Jarvis (user order 2026-11-02).
 *
 * Voice: Sulafat (prebuilt, warm, female) + a soft, slow, romantic,
 * deep style instruction in the prompt. Google gives prebuilt voices
 * one-word characters (Sulafat = "Warm"); the style prompt is what
 * steers it romantic/deep — the voice itself is not a custom clone
 * (Gemini TTS cannot clone a voice, and this does not claim to).
 *
 * Model: gemini-2.5-flash-preview-tts (free-tier eligible, NOT
 * unlimited: free quota is a few requests/minute and roughly
 * 10-15 requests/day in published notes). Every failure — no key,
 * 429 quota, network, bad audio — returns false so VoiceManager
 * falls back to the phone's system TTS instead of going silent.
 *
 * API key: the user's own Gemini key from AI Brain (prefs.apiKey).
 * No key is baked into the APK (shared app = extractable key).
 * Output is raw PCM 24kHz 16-bit mono, played via AudioTrack.
 */
class GeminiTtsClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentTrack: AudioTrack? = null

    fun stopPlayback() {
        runCatching {
            currentTrack?.stop()
            currentTrack?.release()
            currentTrack = null
        }
    }

    suspend fun speak(text: String, apiKey: String, voiceName: String): Boolean {
        if (apiKey.isBlank() || text.isBlank()) return false
        // Long texts burn free quota fast and risk TTS timeouts;
        // system TTS handles those instead.
        if (text.length > 1500) return false
        return try {
            val pcm = synthesize(text, apiKey, voiceName) ?: return false
            playPcm(pcm)
        } catch (e: Exception) {
            JarvisStateRepository.addLog("Gemini TTS failed (${e.message}), falling back to system TTS")
            false
        }
    }

    private suspend fun synthesize(text: String, apiKey: String, voiceName: String): ByteArray? =
        withContext(Dispatchers.IO) {
            // Style instruction FIRST, then the text to speak. This is
            // the documented way to steer tone for Gemini TTS.
            val styledText =
                "Speak in a warm, soft, slow, romantic and deep female voice, " +
                    "gently and lovingly, like talking to someone very close: $text"
            val body = JSONObject().apply {
                put("contents", org.json.JSONArray().put(JSONObject().apply {
                    put("parts", org.json.JSONArray().put(JSONObject().apply {
                        put("text", styledText)
                    }))
                }))
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", org.json.JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", voiceName.ifBlank { "Sulafat" })
                            })
                        })
                    })
                })
            }
            val request = Request.Builder()
                .url(
                    "https://generativelanguage.googleapis.com/v1beta/models/" +
                        "gemini-2.5-flash-preview-tts:generateContent?key=$apiKey"
                )
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    JarvisStateRepository.addLog("Gemini TTS HTTP ${resp.code} (quota/network) — fallback")
                    return@withContext null
                }
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                val data = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optJSONObject("inlineData")
                    ?.optString("data")
                if (data.isNullOrBlank()) return@withContext null
                Base64.decode(data, Base64.DEFAULT)
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
            while (offset < pcm.size) {
                val written = track.write(pcm, offset, pcm.size - offset)
                if (written <= 0) break
                offset += written
            }
            // Wait for playback to finish (with a hard cap).
            val frames = pcm.size / 2
            withTimeoutOrNull(25_000L) {
                while (track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    track.playbackHeadPosition < frames
                ) {
                    delay(50L)
                }
            }
            runCatching { track.stop(); track.release() }
            if (currentTrack == track) currentTrack = null
            true
        } catch (e: Exception) {
            JarvisStateRepository.addLog("Gemini TTS playback failed: ${e.message}")
            false
        }
    }
}
