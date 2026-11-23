package com.jarvis.spike.mi1

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.NativeLibraryLoader
import com.google.ai.edge.litertlm.SamplerConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * M-I-1 LiteRT on-demand .so spike.
 *
 * Question: can liblitertlm_jni.so (22,155,680 B — 89% of the 1.5.4
 * APK, still 22,155,680 B in 1.19.0) be REMOVED from the APK and
 * loaded on demand instead?
 *
 * Steps (each logs its own timing; any failure stops the run with
 * the failing step named — never a silent pass):
 *  0. Precondition: the spike APK itself must NOT bundle the .so
 *     (checked at build time by CI and at runtime here — if the
 *     bundled copy exists in nativeLibraryDir the load proof is
 *     contaminated and the verdict is INVALID).
 *  1. Download the public Google Maven AAR
 *     (litertlm-android 0.18.0, 20,905,807 B, no auth, no token).
 *  2. Extract jni/arm64-v8a/liblitertlm_jni.so, SHA-256-verify it
 *     against the hash measured from the 1.19.0 APK's bundled .so
 *     (byte-identical — proven on the VM before this spike was
 *     written).
 *  3. chmod + System.load(absolute path), then call the AAR's own
 *     NativeLibraryLoader.load() — it must succeed via the
 *     already-loaded short-circuit (System.loadLibrary on an
 *     already-loaded soname is a no-op success). If it throws,
 *     the on-demand path does NOT work on this phone.
 *  4. Gemma model (304 MB, gated HF — the user's OWN token, typed
 *     in the spike UI, never stored, never logged; same policy as
 *     Jarvis B1). Already-downloaded model is reused.
 *  5. Engine (CPU backend — by design: the spike's verdict is
 *     about the .so load, not backend speed; the app tries GPU
 *     first) + one chat turn ("Hello, who are you?").
 *
 * Verdict: PASS only if every step succeeds and the turn returns a
 * non-blank reply. Anything else is FAIL (with the step + error).
 * The verdict is decided ON THE PHONE, never in CI — CI only
 * proves the spike builds and the APK carries no bundled .so.
 */
object SpikeRunner {

    const val AAR_URL =
        "https://maven.google.com/com/google/ai/edge/litertlm/litertlm-android/0.18.0/litertlm-android-0.18.0.aar"
    const val AAR_ENTRY = "jni/arm64-v8a/liblitertlm_jni.so"
    const val EXPECTED_SO_SHA256 =
        "4a94a3f955ad7c58cbcdfc5cb4ff566187bb9e8c5bc2c4056b4e226646c7e8fd"
    const val EXPECTED_SO_BYTES = 22_155_680L
    const val EXPECTED_AAR_BYTES = 20_905_807L

    const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.litertlm?download=true"
    const val MODEL_FILE_NAME = "gemma3-270m-it-q8.litertlm"
    const val MODEL_MIN_BYTES = 100_000_000L

    const val PROMPT = "Hello, who are you?"

    class SpikeFailure(step: String, cause: Throwable? = null) :
        Exception("FAIL at $step" + (cause?.let { ": ${it.message}" } ?: ""), cause)

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun soFile(context: Context): File =
        File(File(context.filesDir, "litertlm"), "liblitertlm_jni.so")

    fun aarFile(context: Context): File =
        File(File(context.filesDir, "litertlm"), "litertlm-android-0.18.0.aar")

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), MODEL_FILE_NAME)

    fun isModelDownloaded(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() > MODEL_MIN_BYTES
    }

    /**
     * Runs the whole spike on a worker thread. [log] is called from
     * the worker thread — the caller must post to the UI thread.
     * Returns the verdict line ("SPIKE PASS ..." or "SPIKE FAIL ...").
     * With [withTurn] = false only steps 0–4 run (model download) —
     * used by the "Download model only" button.
     */
    fun run(
        context: Context,
        hfToken: String?,
        log: (String) -> Unit,
        withTurn: Boolean = true
    ): String {
        val t0 = System.currentTimeMillis()
        return try {
            log("M-I-1 spike — arm64 .so on-demand load")
            log("Phone ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
                throw SpikeFailure("step 0 (ABI check)",
                    IllegalStateException("arm64-v8a nahi hai — spike sirf arm64 par chalta hai"))
            }

            // Step 0 — the APK must not bundle the .so (else the
            // load proof is contaminated).
            val bundled = File(context.applicationInfo.nativeLibraryDir, "liblitertlm_jni.so")
            if (bundled.exists()) {
                throw SpikeFailure("step 0 (precondition)",
                    IllegalStateException("bundled liblitertlm_jni.so mil gaya (${bundled.absolutePath}) — verdict INVALID, spike APK galat build hua"))
            }
            log("step 0 OK — no bundled .so in ${context.applicationInfo.nativeLibraryDir}")

            step1DownloadAar(context, log)
            step2ExtractAndVerify(context, log)
            step3Load(context, log)
            step4Model(context, hfToken, log)
            val total = System.currentTimeMillis() - t0
            if (!withTurn) {
                return "MODEL OK — model downloaded in ${total}ms (${modelFile(context).length()} bytes)"
            }
            val reply = step5OneTurn(context, log)

            "SPIKE PASS — one local turn OK in ${total}ms. Reply: $reply"
        } catch (e: SpikeFailure) {
            "SPIKE FAIL — ${e.message}"
        } catch (t: Throwable) {
            "SPIKE FAIL — unexpected: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** Steps 0–4 only (model download, no turn). */
    fun downloadModel(context: Context, hfToken: String?, log: (String) -> Unit): String =
        run(context, hfToken, log, withTurn = false)

    private fun step1DownloadAar(context: Context, log: (String) -> Unit) {
        val t0 = System.currentTimeMillis()
        log("step 1 — downloading AAR (20.9 MB, Google Maven, no token)...")
        val target = aarFile(context)
        target.parentFile?.mkdirs()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(AAR_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SpikeFailure("step 1 (AAR download)",
                    IllegalStateException("HTTP ${response.code}"))
            }
            val body = response.body
                ?: throw SpikeFailure("step 1 (AAR download)", IllegalStateException("empty body"))
            val tmp = File(target.parentFile, "litertlm-android-0.18.0.aar.tmp")
            var downloaded = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                    }
                }
            }
            if (downloaded < 20_000_000L) {
                tmp.delete()
                throw SpikeFailure("step 1 (AAR download)",
                    IllegalStateException("incomplete: $downloaded bytes"))
            }
            if (target.exists()) target.delete()
            check(tmp.renameTo(target)) { "AAR save nahi hua" }
            log("step 1 OK — ${downloaded} bytes in ${System.currentTimeMillis() - t0}ms (expected $EXPECTED_AAR_BYTES)")
        }
    }

    private fun step2ExtractAndVerify(context: Context, log: (String) -> Unit) {
        val t0 = System.currentTimeMillis()
        log("step 2 — extracting $AAR_ENTRY + SHA-256 verify...")
        val target = soFile(context)
        target.parentFile?.mkdirs()
        ZipFile(aarFile(context)).use { zip ->
            val entry = zip.getEntry(AAR_ENTRY)
                ?: throw SpikeFailure("step 2 (extract)", IllegalStateException("entry $AAR_ENTRY not in AAR"))
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (target.length() != EXPECTED_SO_BYTES) {
            throw SpikeFailure("step 2 (verify)",
                IllegalStateException("size ${target.length()} != expected $EXPECTED_SO_BYTES"))
        }
        val hash = sha256Hex(target)
        log("step 2 — sha256: $hash")
        if (hash != EXPECTED_SO_SHA256) {
            throw SpikeFailure("step 2 (verify)",
                IllegalStateException("hash mismatch — expected $EXPECTED_SO_SHA256"))
        }
        log("step 2 OK — ${target.length()} bytes, hash MATCH in ${System.currentTimeMillis() - t0}ms")
    }

    private fun step3Load(context: Context, log: (String) -> Unit) {
        val t0 = System.currentTimeMillis()
        log("step 3 — chmod + System.load(downloaded .so)...")
        val file = soFile(context)
        file.setReadable(true, false)
        file.setExecutable(true, false)
        try {
            System.load(file.absolutePath)
        } catch (t: Throwable) {
            throw SpikeFailure("step 3 (System.load)", t)
        }
        log("step 3a OK — System.load(${file.absolutePath}) in ${System.currentTimeMillis() - t0}ms")
        // The AAR's own loader must now succeed via the
        // already-loaded short-circuit. If THIS throws, the
        // on-demand path does not work on this phone.
        try {
            NativeLibraryLoader.load()
        } catch (t: Throwable) {
            throw SpikeFailure("step 3 (NativeLibraryLoader.load)", t)
        }
        log("step 3b OK — NativeLibraryLoader.load() succeeded (already-loaded short-circuit works)")
    }

    private fun step4Model(context: Context, hfToken: String?, log: (String) -> Unit) {
        if (isModelDownloaded(context)) {
            log("step 4 OK — model already downloaded (${modelFile(context).length()} bytes)")
            return
        }
        val t0 = System.currentTimeMillis()
        log("step 4 — downloading Gemma model (304 MB, HF token needed)...")
        val token = hfToken?.takeIf { it.isNotBlank() }
            ?: throw SpikeFailure("step 4 (model)",
                IllegalStateException("HF token nahi diya — apna Hugging Face token upar daalo (jis account ne Gemma licence accept ki hai)"))
        val target = modelFile(context)
        target.parentFile?.mkdirs()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(MODEL_URL)
            .header("Authorization", "Bearer ${token.trim()}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 401) {
                    throw SpikeFailure("step 4 (model)",
                        IllegalStateException("Gemma gated hai (HTTP 401) — token galat hai ya us account ne Gemma licence accept nahi ki"))
                }
                throw SpikeFailure("step 4 (model)",
                    IllegalStateException("HTTP ${response.code}"))
            }
            val body = response.body
                ?: throw SpikeFailure("step 4 (model)", IllegalStateException("empty body"))
            val tmp = File(target.parentFile, "$MODEL_FILE_NAME.tmp")
            var downloaded = 0L
            var lastLogged = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastLogged >= 50_000_000L) {
                            lastLogged = downloaded
                            log("step 4 — model ${downloaded / 1_000_000L} MB / 304 MB...")
                        }
                    }
                }
            }
            if (tmp.length() < MODEL_MIN_BYTES) {
                tmp.delete()
                throw SpikeFailure("step 4 (model)",
                    IllegalStateException("incomplete: ${tmp.length()} bytes"))
            }
            if (target.exists()) target.delete()
            check(tmp.renameTo(target)) { "Model save nahi hua" }
            log("step 4 OK — ${target.length()} bytes in ${System.currentTimeMillis() - t0}ms")
        }
    }

    private fun step5OneTurn(context: Context, log: (String) -> Unit): String {
        val t0 = System.currentTimeMillis()
        log("step 5 — Engine(CPU) init + one local turn...")
        val file = modelFile(context)
        // CPU only, by design: the spike's verdict is about the .so
        // load, not backend speed. (The app tries GPU first.)
        val config = EngineConfig(
            modelPath = file.absolutePath,
            backend = Backend.CPU(),
            cacheDir = context.cacheDir.path
        )
        val engine = Engine(config)
        try {
            engine.initialize()
            log("step 5a OK — Engine.initialize() in ${System.currentTimeMillis() - t0}ms")
            val convConfig = ConversationConfig(
                systemInstruction = Contents.of("You are a helpful assistant. Short answers only."),
                samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.7)
            )
            engine.createConversation(convConfig).use { conversation ->
                val t1 = System.currentTimeMillis()
                val message = conversation.sendMessage(PROMPT)
                val reply = message.toString().trim()
                log("step 5b OK — one turn in ${System.currentTimeMillis() - t1}ms")
                if (reply.isBlank()) {
                    throw SpikeFailure("step 5 (turn)", IllegalStateException("blank reply"))
                }
                return reply
            }
        } finally {
            runCatching { engine.close() }
        }
    }
}
