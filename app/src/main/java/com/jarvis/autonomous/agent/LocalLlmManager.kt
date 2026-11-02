package com.jarvis.autonomous.agent

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Free on-device LLM for Jarvis, no API key, no card, no server.
 *
 * Model (live-verified 2026-11-02, litert-community on Hugging Face):
 * Qwen3-0.6B, mixed INT4 `.litertlm`, ~475 MB, Apache 2.0, UNGATED.
 * Gemma was deliberately NOT chosen as default: its repo returns 401
 * without a Hugging Face account + accepted Gemma licence, so an
 * in-app download would fail for most users. Qwen downloads freely.
 *
 * Honest limits (do not oversell to the user):
 * - 0.6B is a chat model, NOT a reliable JSON agent. It answers casual
 *   conversation fast and offline, but multi-step phone actions and
 *   strict JSON decisions stay on the cloud Lite model (Gemini/Groq
 *   free tier), where the JSON schema actually holds.
 * - First load takes seconds (weights load), then replies are local
 *   with no network round-trip, so casual chat feels instant after
 *   the first answer.
 * - Only arm64-v8a / x86_64 phones are supported by the LiteRT-LM
 *   AAR; other ABIs get a clear "not supported" message, never a crash.
 */
object LocalLlmManager {

    const val MODEL_FILE_NAME = "qwen3_0_6b_mixed_int4.litertlm"
    const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0_6b_mixed_int4.litertlm?download=true"
    const val MODEL_SIZE_MB = 475L
    const val MODEL_DISPLAY = "Qwen3-0.6B (on-device, free, Apache 2.0)"

    private val engineLock = Mutex()
    private var engine: Engine? = null
    private var engineModelPath: String? = null

    fun isDeviceSupported(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), MODEL_FILE_NAME)

    fun isDownloaded(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() > 100_000_000L
    }

    fun downloadedBytes(context: Context): Long =
        modelFile(context).takeIf { it.exists() }?.length() ?: 0L

    suspend fun download(
        context: Context,
        onProgress: (downloadedMB: Long, totalMB: Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isDeviceSupported()) {
                throw IllegalStateException(
                    "Is phone ka processor (ABI) LiteRT-LM ko support nahi karta — sirf arm64 / x86_64 chalta hai."
                )
            }
            val target = modelFile(context)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "$MODEL_FILE_NAME.tmp")
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(MODEL_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Model download failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Empty download body")
                val total = body.contentLength()
                var downloaded = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            onProgress(
                                downloaded / 1_000_000L,
                                if (total > 0) total / 1_000_000L else MODEL_SIZE_MB
                            )
                        }
                    }
                }
                if (tmp.length() < 100_000_000L) {
                    tmp.delete()
                    throw IllegalStateException("Download incomplete (${tmp.length()} bytes) — dobara try karo.")
                }
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            }
        }
    }

    /**
     * One casual chat reply from the local model. History is trimmed to
     * the last 6 turns so the small context window never overflows.
     * Thinking output is stripped: Qwen3's thinking channel must never
     * reach the user as a reply.
     */
    suspend fun chat(
        context: Context,
        systemPrompt: String,
        userText: String,
        recentChat: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val file = modelFile(context)
        if (!isDownloaded(context)) {
            throw IllegalStateException(
                "Local model abhi download nahi hua — AI Brain me 'Download Free Local Model' dabao (~475 MB, ek baar)."
            )
        }
        engineLock.withLock {
            val eng = getEngine(context, file)
            val history = recentChat.takeLast(6).map { (sender, text) ->
                if (sender == "You") Message.user(text) else Message.model(text)
            }
            val config = ConversationConfig(
                systemInstruction = Contents.of(
                    "$systemPrompt Short answers only. No thinking, no <think> tags, no reasoning preamble — answer directly."
                ),
                initialMessages = history,
                samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.7)
            )
            eng.createConversation(config).use { conversation ->
                val message = conversation.sendMessage(userText)
                stripThinking(message.toString().trim())
            }
        }
    }

    private fun getEngine(context: Context, file: File): Engine {
        val path = file.absolutePath
        if (engine != null && engineModelPath == path) return engine!!
        // Release a stale engine before loading a new one; a second
        // close() throws, so guard it and never store a failed engine.
        runCatching { engine?.close() }
        engine = null
        val created = try {
            val gpuConfig = EngineConfig(
                modelPath = path,
                backend = Backend.GPU(),
                cacheDir = context.cacheDir.path
            )
            Engine(gpuConfig).also { it.initialize() }
        } catch (t: Throwable) {
            // GPU does not work on every phone/model — CPU is the safety net.
            val cpuConfig = EngineConfig(
                modelPath = path,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.path
            )
            Engine(cpuConfig).also { it.initialize() }
        }
        engine = created
        engineModelPath = path
        return created
    }

    fun release() {
        runCatching { engine?.close() }
        engine = null
        engineModelPath = null
    }

    private fun stripThinking(raw: String): String {
        var out = raw
        // Remove <think>...</think> blocks (dot matches newlines via (?s)).
        out = out.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        // Some builds prefix the reply with a role label.
        out = out.removePrefix("model:").removePrefix("Model:").trim()
        return out.ifBlank { "Bhai, local model se jawab nahi aaya — dobara bolo." }
    }
}
