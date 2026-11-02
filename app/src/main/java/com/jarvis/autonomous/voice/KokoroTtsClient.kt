package com.jarvis.autonomous.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.jarvis.autonomous.data.JarvisStateRepository
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kokoro offline voice for Jarvis (user order 2026-11-02) — the
 * voice Flora chose: Kokoro hf_alpha (Hindi female), run fully
 * on-device via Sherpa-ONNX, no key, no quota, no network after
 * the one-time model download.
 *
 * Model: Kokoro multi-lang v1.0 INT8, Hugging Face repo
 * csukuangfj/kokoro-int8-multi-lang-v1_0, downloaded UNPACKED
 * via the HF tree API (recursive) + resolve URLs — no tar/bzip2
 * extraction needed on the phone. Total ~190 MB (model.int8.onnx
 * ~109 MB + voices.bin ~28 MB + espeak-ng-data ~18 MB + lexicons
 * + dict). Files land in app external files dir Kokoro/.
 *
 * Sherpa API (verified against the bundled AAR 1.13.4 classes,
 * not guessed): OfflineTts(null, config) for file-based models,
 * OfflineTtsConfig(model), OfflineTtsModelConfig(kokoro),
 * OfflineTtsKokoroModelConfig(model, voices, tokens, dataDir,
 * lexicon, lang, dictDir, lengthScale), generate(text, sid, speed)
 * returning GeneratedAudio(samples: FloatArray, sampleRate: Int).
 * Speaker id 31 = hf_alpha in kokoro-multi-lang-v1_0's Sherpa
 * voice ordering (Hindi female) — the same voice as the
 * kokoro-hf_alpha-hindi.wav sample the user picked.
 *
 * Honest limits: the ~109 MB model is loaded once and cached
 * behind a Mutex (never reloaded per utterance); first load takes
 * seconds. Synthesis is CPU-bound, so on budget phones a reply
 * takes longer than cloud TTS — that is the offline tradeoff.
 * stopPlayback() takes effect between chunks and during playback;
 * a generation already running in native code cannot be cut
 * mid-sentence, it finishes that chunk first.
 */
object KokoroTtsClient {

    private const val REPO = "csukuangfj/kokoro-int8-multi-lang-v1_0"
    private const val MODEL_FILE = "model.int8.onnx"
    private const val VOICES_FILE = "voices.bin"
    private const val TOKENS_FILE = "tokens.txt"
    private const val HF_ALPHA_SID = 31

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val engineLock = Mutex()
    private var engine: OfflineTts? = null
    private var engineDir: File? = null

    @Volatile
    private var currentTrack: AudioTrack? = null
    private val stopped = AtomicBoolean(false)

    fun modelDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "Kokoro")
    }

    fun isDownloaded(context: Context): Boolean {
        val dir = modelDir(context)
        val model = File(dir, MODEL_FILE)
        val voices = File(dir, VOICES_FILE)
        val tokens = File(dir, TOKENS_FILE)
        val espeak = File(dir, "espeak-ng-data")
        if (!model.exists() || model.length() <= 50_000_000L) return false
        if (!voices.exists() || voices.length() == 0L) return false
        if (!tokens.exists() || tokens.length() == 0L) return false
        if (!espeak.isDirectory) return false
        return espeak.walkTopDown().any { it.isFile }
    }

    fun downloadedBytes(context: Context): Long {
        val dir = modelDir(context)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Download the full model bundle. Returns Result; on failure the
     * partial .tmp files are deleted, never half-installed.
     */
    suspend fun download(
        context: Context,
        onProgress: (downloadedMB: Long, totalMB: Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = modelDir(context)
            dir.mkdirs()
            val treeUrl =
                "https://huggingface.co/api/models/$REPO/tree/main?recursive=true"
            val treeJson = http.newCall(Request.Builder().url(treeUrl).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Kokoro file list nahi mili: HTTP ${resp.code}")
                    }
                    resp.body?.string() ?: throw IllegalStateException("Empty file list")
                }
            val arr = JSONArray(treeJson)
            val entries = mutableListOf<Pair<String, Long>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("type") != "file") continue
                val path = obj.getString("path")
                if (path == ".gitattributes" || path == "LICENSE" || path == "README.md") continue
                entries.add(path to obj.optLong("size", 0L))
            }
            if (entries.none { it.first == MODEL_FILE }) {
                throw IllegalStateException("Kokoro model file list me $MODEL_FILE nahi mila")
            }
            val total = entries.sumOf { it.second }.let { if (it > 0) it else 190_000_000L }
            var downloaded = 0L
            for ((path, size) in entries) {
                val target = File(dir, path)
                if (target.exists() && size > 0 && target.length() == size) {
                    downloaded += size
                    onProgress(downloaded / 1_000_000L, total / 1_000_000L)
                    continue
                }
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "${target.name}.tmp")
                val encodedPath = path.split("/").joinToString("/") {
                    it.replace(" ", "%20")
                }
                val fileUrl =
                    "https://huggingface.co/$REPO/resolve/main/$encodedPath?download=true"
                http.newCall(Request.Builder().url(fileUrl).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        tmp.delete()
                        throw IllegalStateException("Kokoro download fail ($path): HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IllegalStateException("Empty body: $path")
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                downloaded += n
                                onProgress(downloaded / 1_000_000L, total / 1_000_000L)
                            }
                        }
                    }
                }
                if (path == MODEL_FILE && tmp.length() <= 50_000_000L) {
                    tmp.delete()
                    throw IllegalStateException("Kokoro model incomplete (${tmp.length()} bytes)")
                }
                if (target.exists()) target.delete()
                check(tmp.renameTo(target)) { "Save nahi hua: $path" }
            }
            if (!isDownloaded(context)) {
                throw IllegalStateException("Kokoro download ke baad bhi incomplete hai")
            }
        }
    }

    fun stopPlayback() {
        stopped.set(true)
        runCatching {
            currentTrack?.stop()
            currentTrack?.release()
            currentTrack = null
        }
    }

    suspend fun speak(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        if (!isDownloaded(context)) return false
        stopped.set(false)
        return try {
            withContext(Dispatchers.IO) {
                val tts = getEngine(context)
                for (chunk in splitChunks(text, 600)) {
                    if (stopped.get()) return@withContext false
                    val audio = tts.generate(chunk, HF_ALPHA_SID, 1.0f)
                    if (stopped.get()) return@withContext false
                    if (!playAudio(audio)) return@withContext false
                }
                true
            }
        } catch (e: Exception) {
            JarvisStateRepository.addLog("Kokoro TTS failed (${e.message}), falling back")
            false
        }
    }

    private suspend fun getEngine(context: Context): OfflineTts = engineLock.withLock {
        val dir = modelDir(context)
        val cached = engine
        if (cached != null && engineDir == dir) return@withLock cached
        runCatching { cached?.release() }
        val lexicon = listOf("lexicon-us-en.txt", "lexicon-zh.txt")
            .filter { File(dir, it).exists() }
            .joinToString(",")
        val kokoroConfig = OfflineTtsKokoroModelConfig(
            model = File(dir, MODEL_FILE).absolutePath,
            voices = File(dir, VOICES_FILE).absolutePath,
            tokens = File(dir, TOKENS_FILE).absolutePath,
            dataDir = File(dir, "espeak-ng-data").absolutePath,
            lexicon = lexicon,
            lang = "",
            dictDir = File(dir, "dict").takeIf { it.isDirectory }?.absolutePath ?: "",
            lengthScale = 1.0f
        )
        val modelConfig = OfflineTtsModelConfig(
            kokoro = kokoroConfig,
            numThreads = 2,
            debug = false,
            provider = "cpu"
        )
        val config = OfflineTtsConfig(model = modelConfig)
        // null AssetManager = file-based model (assets are NOT used).
        val created = OfflineTts(null, config)
        engine = created
        engineDir = dir
        created
    }

    private fun splitChunks(text: String, maxChars: Int): List<String> {
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

    private suspend fun playAudio(audio: GeneratedAudio): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val samples = audio.samples
                if (samples.isEmpty()) return@withContext true
                val pcm = ByteArray(samples.size * 2)
                for (i in samples.indices) {
                    val v = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
                    pcm[i * 2] = (v and 0xFF).toByte()
                    pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                val rate = audio.sampleRate.takeIf { it > 0 } ?: 24000
                val minBuf = AudioTrack.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
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
                            .setSampleRate(rate)
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
                withTimeoutOrNull(60_000L) {
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
                JarvisStateRepository.addLog("Kokoro playback failed: ${e.message}")
                false
            }
        }
}
