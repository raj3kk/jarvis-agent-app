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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kokoro offline voice for Jarvis (user order 2026-11-02) — the
 * voice Flora chose: Kokoro hf_alpha (Hindi female), run fully
 * on-device via Sherpa-ONNX, no key, no quota, no network.
 *
 * 1.4.0 — BUNDLED, asset-first (no network download on the phone):
 * CI downloads the sherpa-onnx kokoro-int8-multi-lang-v1_0 tarball
 * and extracts it into app/src/main/assets/kokoro/ (never
 * committed to git). On first use, [ensureAssetsReady] copies
 * assets/kokoro/ (model.int8.onnx ~109 MB + voices.bin + tokens.txt
 * + espeak-ng-data/ + lexicons/dict if present) into the existing
 * model dir (app external files dir Kokoro/) with progress
 * callbacks and a free-space check, and the engine then loads
 * from that dir exactly as before. A previously downloaded model
 * dir is still accepted as-is ([isDownloaded] short-circuits the
 * copy), so phones that already downloaded under 1.3.0 keep
 * working without re-copying.
 *
 * Total unpacked ~190 MB. The copy is NOT zero-time on first run
 * (see INTEGRATION_NOTES_Kokoro.md §5): the coordinator MUST
 * drive [ensureAssetsReady] from the UI at app launch behind a
 * visible creamy-theme progress screen, so the user never stares
 * at a blank hang while ~190 MB copies.
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

    private const val MODEL_FILE = "model.int8.onnx"
    private const val VOICES_FILE = "voices.bin"
    private const val TOKENS_FILE = "tokens.txt"
    private const val HF_ALPHA_SID = 31

    /** Asset folder CI fills from the sherpa-onnx tarball (see build-apk.yml). */
    private const val ASSET_DIR = "kokoro"

    /**
     * Free-space floor for a FRESH first-run copy: ~200 MB
     * (200 MiB = 209,715,200 bytes), i.e. the ~190 MB unpacked
     * bundle plus margin. On a partial copy (app killed mid-copy)
     * the floor is instead remaining-bytes + 10 MB, so a resumed
     * copy is not blocked by the full 200 MB rule — see
     * [ensureAssetsReady].
     */
    const val REQUIRED_FREE_BYTES_FRESH_COPY = 200L * 1024L * 1024L
    private const val COPY_RESUME_MARGIN_BYTES = 10L * 1024L * 1024L
    private const val FALLBACK_BUNDLED_BYTES = 190_000_000L

    private val engineLock = Mutex()
    /** B-M1: ONE mutex guarding ensureAssetsReady across launch
     * overlay, Settings retry and speak-triggered copy — concurrent
     * copies used to collide on the same *.tmp files. */
    private val copyMutex = Mutex()
    private var engine: OfflineTts? = null
    private var engineDir: File? = null
    private var speakerCheckLogged: Boolean = false

    @Volatile
    private var currentTrack: AudioTrack? = null
    private val stopped = AtomicBoolean(false)

    fun modelDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "Kokoro")
    }

    /**
     * B-C1: the bundled model is verified by EXACT size, never by
     * ">50MB" (a truncated 60MB copy used to pass). Sources, in
     * order: (1) the sizes manifest written by [ensureAssetsReady]
     * after a verified copy; (2) the bundled asset size via
     * openFd when the asset is stored uncompressed. A legacy
     * 1.3.0 download dir with neither source keeps the old
     * >50MB check as a fallback — but a Sherpa init failure on
     * such a dir deletes it and forces a fresh copy (see [speak]),
     * so a corrupt legacy dir can never return false forever.
     */
    fun isDownloaded(context: Context): Boolean {
        val dir = modelDir(context)
        val model = File(dir, MODEL_FILE)
        val voices = File(dir, VOICES_FILE)
        val tokens = File(dir, TOKENS_FILE)
        val espeak = File(dir, "espeak-ng-data")
        if (!model.exists()) return false
        val manifestSizes = readSizesManifest(dir)
        if (manifestSizes != null) {
            val expected = manifestSizes[MODEL_FILE] ?: return false
            if (model.length() != expected) return false
        } else {
            val bundledSize = bundledFileBytes(context, MODEL_FILE)
            if (bundledSize > 0) {
                if (model.length() != bundledSize) return false
            } else if (model.length() <= 50_000_000L) {
                return false
            }
        }
        if (!voices.exists() || voices.length() == 0L) return false
        if (!tokens.exists() || tokens.length() == 0L) return false
        if (!espeak.isDirectory) return false
        return espeak.walkTopDown().any { it.isFile }
    }

    /** Sizes manifest written by [ensureAssetsReady] (rel=size lines). */
    private const val SIZES_MANIFEST_FILE = "kokoro-sizes.txt"

    private fun readSizesManifest(dir: File): Map<String, Long>? {
        val manifest = File(dir, SIZES_MANIFEST_FILE)
        if (!manifest.exists()) return null
        return runCatching {
            manifest.readLines()
                .mapNotNull { line ->
                    val idx = line.lastIndexOf('=')
                    if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1).toLong()
                }.toMap().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun writeSizesManifest(dir: File, sizes: Map<String, Long>) {
        runCatching {
            File(dir, SIZES_MANIFEST_FILE).writeText(
                sizes.entries.joinToString("\n") { "${it.key}=${it.value}" }
            )
        }
    }

    fun bundledModelBytes(context: Context): Long {
        val dir = modelDir(context)
        if (!dir.exists()) return 0L
        // B-M2: orphan *.tmp files never count toward the total.
        return dir.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .sumOf { it.length() }
    }

    // ---------- Bundled-asset first run (1.4.0) ----------

    /** True if this APK carries assets/kokoro/model.int8.onnx (CI-bundled build). */
    fun hasBundledAssets(context: Context): Boolean {
        return try {
            context.assets.list(ASSET_DIR)?.contains(MODEL_FILE) == true
        } catch (e: Exception) {
            false
        }
    }

    /** Free bytes usable on the volume holding the model dir. */
    fun availableFreeBytes(context: Context): Long {
        val dir = modelDir(context)
        runCatching { dir.mkdirs() }
        return try {
            dir.usableSpace
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Recursively lists asset files under [ASSET_DIR] as relative
     * paths (e.g. "espeak-ng-data/phontab"). AssetManager.list()
     * returns null/empty for a file, non-empty for a directory; an
     * empty directory is indistinguishable from a file, which is
     * fine here because the Kokoro bundle has no empty dirs that
     * matter (espeak-ng-data files are what Sherpa reads).
     */
    private fun listBundledFiles(context: Context, relDir: String = ""): List<String> {
        val assetPath = if (relDir.isEmpty()) ASSET_DIR else "$ASSET_DIR/$relDir"
        val children = try {
            context.assets.list(assetPath)
        } catch (e: Exception) {
            null
        } ?: return listOf(relDir)
        if (children.isEmpty()) return listOf(relDir)
        val out = mutableListOf<String>()
        for (child in children) {
            val childRel = if (relDir.isEmpty()) child else "$relDir/$child"
            out += listBundledFiles(context, childRel)
        }
        return out
    }

    private fun bundledFileBytes(context: Context, relPath: String): Long {
        return try {
            context.assets.openFd("$ASSET_DIR/$relPath").use { it.length }
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * B-M2: exact asset size by STREAMING (openFd returns -1 for
     * compressed assets, so it is never relied on alone). Used
     * for every file whose openFd size is unknown, before any
     * copy/skip decision is made.
     */
    private fun countedAssetBytes(context: Context, relPath: String): Long {
        return try {
            var total = 0L
            context.assets.open("$ASSET_DIR/$relPath").use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
            total
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Ensures the model dir is ready, copying bundled assets on
     * first use. Idempotent: if [isDownloaded] is already true
     * (bundled copy done, or a 1.3.0 download), this returns
     * success immediately without copying anything.
     *
     * Progress stages reported to [onProgress] (stage, copiedMB,
     * totalMB) — the coordinator MUST surface these on a visible
     * screen (see INTEGRATION_NOTES_Kokoro.md §5), never a blank
     * hang:
     *  - "checking_space": free-space check running (copied=0).
     *  - "copying": files copying (copied grows to total).
     *  - "ready": model dir verified complete.
     * On failure the returned Result carries a user-showable
     * message (free-space failure names the MB available vs
     * needed). Partial copies are KEPT (not deleted) so a relaunch
     * resumes instead of restarting from zero; already-copied
     * files with matching size are skipped.
     *
     * Free-space rule: fresh copy (no model files present yet)
     * requires [REQUIRED_FREE_BYTES_FRESH_COPY] (~200 MB) free;
     * a resumed partial copy requires remaining-bytes + 10 MB.
     * Failure message format (show verbatim):
     * "Kokoro setup needs ~XXX MB free space — only YYY MB
     * available. Please free space and relaunch Jarvis."
     */
    suspend fun ensureAssetsReady(
        context: Context,
        onProgress: (stage: String, copiedMB: Long, totalMB: Long) -> Unit = { _, _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // B-M1: serialised — launch overlay, Settings retry and
        // speak-triggered copies queue here, never interleave.
        copyMutex.withLock {
            runCatching {
                if (isDownloaded(context)) {
                    val totalMB = bundledModelBytes(context) / 1_000_000L
                    onProgress("ready", totalMB, totalMB)
                    return@runCatching
                }
                if (!hasBundledAssets(context)) {
                    throw IllegalStateException(
                        "Kokoro voice missing: this APK has no bundled Kokoro " +
                            "model and no downloaded model exists. Reinstall the " +
                            "bundled 1.4.0 APK — no other voice will be used."
                    )
                }
                onProgress("checking_space", 0L, 0L)
                val dir = modelDir(context)
                dir.mkdirs()
                // B-M2: clean orphan *.tmp files BEFORE measuring
                // or resuming — a stale tmp must never count as
                // already-copied bytes or as "present" content.
                dir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".tmp") }
                    .forEach { runCatching { it.delete() } }
                val files = listBundledFiles(context)
                // B-M2: exact sizes via stream/manifest — openFd
                // alone is never trusted (compressed assets -> -1).
                val sizes = files.associateWith { rel ->
                    val fdSize = bundledFileBytes(context, rel)
                    if (fdSize > 0) fdSize else countedAssetBytes(context, rel)
                }
                val totalBytes = sizes.values.filter { it > 0 }.sum()
                    .takeIf { it > 0 } ?: FALLBACK_BUNDLED_BYTES
                val totalMB = totalBytes / 1_000_000L
                // Bytes still to copy (skip targets already present at full size).
                var remainingBytes = 0L
                for ((rel, size) in sizes) {
                    val target = File(dir, rel)
                    if (size > 0 && target.exists() && target.length() == size) continue
                    remainingBytes += if (size > 0) size else 0L
                }
                val alreadyPresent = dir.walkTopDown()
                    .any { it.isFile && !it.name.endsWith(".tmp") }
                val requiredBytes = if (!alreadyPresent) {
                    REQUIRED_FREE_BYTES_FRESH_COPY
                } else {
                    remainingBytes + COPY_RESUME_MARGIN_BYTES
                }
                val freeBytes = availableFreeBytes(context)
                if (freeBytes < requiredBytes) {
                    val msg = "Kokoro setup needs ~${requiredBytes / 1_000_000L} MB free " +
                        "space — only ${freeBytes / 1_000_000L} MB available. " +
                        "Please free space and relaunch Jarvis."
                    JarvisStateRepository.addLog(msg)
                    throw IllegalStateException(msg)
                }
                var copiedBytes = totalBytes - remainingBytes
                onProgress("copying", copiedBytes / 1_000_000L, totalMB)
                for (rel in files) {
                    val target = File(dir, rel)
                    val size = sizes[rel] ?: -1L
                    if (size > 0 && target.exists() && target.length() == size) continue
                    // B-M2: per-file free-space re-check — disk
                    // may fill mid-copy (downloads, other apps).
                    val freeNow = availableFreeBytes(context)
                    val needNow = if (size > 0) size + COPY_RESUME_MARGIN_BYTES
                        else COPY_RESUME_MARGIN_BYTES
                    if (freeNow < needNow) {
                        val msg = "Kokoro setup needs ~${needNow / 1_000_000L} MB free " +
                            "space for $rel — only ${freeNow / 1_000_000L} MB available. " +
                            "Please free space and relaunch Jarvis."
                        JarvisStateRepository.addLog(msg)
                        throw IllegalStateException(msg)
                    }
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, "${target.name}.tmp")
                    try {
                        context.assets.open("$ASSET_DIR/$rel").use { input ->
                            tmp.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    copiedBytes += n
                                }
                            }
                        }
                        if (target.exists()) target.delete()
                        check(tmp.renameTo(target)) { "Save nahi hua: $rel" }
                    } catch (e: Exception) {
                        tmp.delete()
                        throw e
                    }
                    onProgress("copying", copiedBytes / 1_000_000L, totalMB)
                }
                // B-C1: record exact copied sizes so later
                // isDownloaded() checks verify exact sizes.
                writeSizesManifest(dir, sizes.filterValues { it > 0 })
                if (!isDownloaded(context)) {
                    throw IllegalStateException(
                        "Kokoro copy finished but the model is still incomplete — " +
                            "relaunch Jarvis to resume the copy."
                    )
                }
                onProgress("ready", totalMB, totalMB)
                JarvisStateRepository.addLog(
                    "Kokoro bundled model ready (${bundledModelBytes(context) / 1_000_000L} MB, " +
                        "copied from APK assets — no download needed)."
                )
            }
        }
    }

    /**
     * B-C1 reset path, callable from the Settings retry button:
     * releases the engine, deletes the whole model dir, then
     * re-copies bundled assets fresh. A corrupt copy is never
     * "repaired" by re-verifying it in place — it is replaced.
     */
    suspend fun resetAndCopy(
        context: Context,
        onProgress: (stage: String, copiedMB: Long, totalMB: Long) -> Unit = { _, _, _ -> }
    ): Result<Unit> {
        engineLock.withLock {
            runCatching { engine?.release() }
            engine = null
            engineDir = null
        }
        stopPlayback()
        withContext(Dispatchers.IO) {
            runCatching { modelDir(context).deleteRecursively() }
        }
        JarvisStateRepository.addLog("Kokoro model reset — fresh bundled copy starting.")
        return ensureAssetsReady(context, onProgress)
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
        if (!isDownloaded(context)) {
            // First run: copy bundled assets (visible progress is
            // the coordinator's launch screen; here we log progress
            // so a speak-triggered copy is never a silent hang).
            var lastLoggedMB = -1L
            val ready = ensureAssetsReady(context) { stage, copiedMB, totalMB ->
                if (stage == "copying" && totalMB > 0 && copiedMB - lastLoggedMB >= 25) {
                    lastLoggedMB = copiedMB
                    JarvisStateRepository.addLog(
                        "Kokoro first-run copy: $copiedMB / $totalMB MB " +
                            "(bundled assets, no download)."
                    )
                }
            }
            if (ready.isFailure) {
                JarvisStateRepository.addLog(
                    "Kokoro unavailable: ${ready.exceptionOrNull()?.message} " +
                        "— no fallback voice in the Kokoro-only build."
                )
                return false
            }
        }
        stopped.set(false)
        // B-C1: engine-init failure is NOT terminal. A corrupt
        // model dir is deleted and re-copied fresh, then the
        // engine is retried once — speak() must never return
        // false forever on a dir that a fresh copy would fix.
        // Playback/generation failures below do NOT delete the
        // model (they are not model-corruption evidence).
        val tts = try {
            getEngine(context)
        } catch (e: Exception) {
            JarvisStateRepository.addLog(
                "Kokoro engine init failed (${e.message}) — deleting " +
                    "model dir and forcing a fresh bundled copy."
            )
            val recoveredTts = if (resetAndCopy(context).isSuccess) {
                runCatching { getEngine(context) }.getOrNull()
            } else {
                null
            }
            if (recoveredTts == null) {
                JarvisStateRepository.addLog(
                    "Kokoro still unavailable after fresh copy — no " +
                        "fallback voice in the Kokoro-only build."
                )
                return false
            }
            recoveredTts
        }
        return try {
            withContext(Dispatchers.IO) {
                for (chunk in splitChunks(text, 600)) {
                    if (stopped.get()) return@withContext false
                    val audio = tts.generate(chunk, HF_ALPHA_SID, 1.0f)
                    if (stopped.get()) return@withContext false
                    if (!playAudio(audio)) return@withContext false
                }
                true
            }
        } catch (e: Exception) {
            JarvisStateRepository.addLog(
                "Kokoro TTS failed (${e.message}) — no fallback voice " +
                    "in the Kokoro-only build."
            )
            false
        }
    }

    private suspend fun getEngine(context: Context): OfflineTts = engineLock.withLock {
        val dir = modelDir(context)
        val cached = engine
        if (cached != null && engineDir == dir) return@withLock cached
        runCatching { cached?.release() }
        // B-L12: verify HF_ALPHA_SID against a bundled voices
        // manifest when one exists; a mismatch is LOGGED (never
        // silently used). No manifest ships in the current bundle,
        // so that case logs once that SID 31 rests on Sherpa's
        // documented voice ordering, unverified by a manifest.
        verifySpeakerId(dir)
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
        // null AssetManager = file-based model: Sherpa reads the
        // files-dir copies, never the APK assets directly. The
        // assets are only the SOURCE that ensureAssetsReady copies
        // from on first run — which is also why no aapt noCompress
        // rule is needed (AssetManager streams decompress on copy).
        val created = OfflineTts(null, config)
        engine = created
        engineDir = dir
        created
    }

    /**
     * B-L12: looks for a voices manifest in the model dir
     * (voices.json / voices.txt / speakers.json / manifest.json).
     * If one names hf_alpha with an id other than
     * [HF_ALPHA_SID], that mismatch is logged as a warning —
     * the voice is never silently wrong. Without a manifest the
     * SID is unverified and that is logged once, honestly.
     */
    private fun verifySpeakerId(dir: File) {
        if (speakerCheckLogged) return
        speakerCheckLogged = true
        val manifest = listOf("voices.json", "voices.txt", "speakers.json", "manifest.json")
            .map { File(dir, it) }
            .firstOrNull { it.exists() }
        if (manifest == null) {
            JarvisStateRepository.addLog(
                "Kokoro voice check: no voices manifest in the model " +
                    "dir — HF_ALPHA_SID=$HF_ALPHA_SID is Sherpa's " +
                    "documented ordering, not manifest-verified."
            )
            return
        }
        val content = runCatching { manifest.readText() }.getOrDefault("")
        val idx = content.indexOf("hf_alpha")
        if (idx < 0) {
            JarvisStateRepository.addLog(
                "Kokoro voice WARNING: voices manifest does not name " +
                    "hf_alpha — SID $HF_ALPHA_SID is unverified."
            )
            return
        }
        val window = content.substring(
            maxOf(0, idx - 120),
            minOf(content.length, idx + 120)
        )
        if (!window.contains(HF_ALPHA_SID.toString())) {
            JarvisStateRepository.addLog(
                "Kokoro voice WARNING: voices manifest names hf_alpha " +
                    "but not with SID $HF_ALPHA_SID near it — check " +
                    "the bundled voices before trusting the voice."
            )
        }
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
        // B-L12 word packing: a chunk boundary falls on a word
        // boundary. A single unbreakable token longer than
        // maxChars goes out WHOLE as its own chunk — it is never
        // hard-cut mid-word.
        fun packWords(sentence: String) {
            for (word in sentence.split(" ").filter { it.isNotEmpty() }) {
                if (current.isEmpty()) {
                    current.append(word)
                } else if (current.length + 1 + word.length <= maxChars) {
                    current.append(" ").append(word)
                } else {
                    flush()
                    current.append(word)
                }
            }
        }
        for (sentence in sentences) {
            if (sentence.length > maxChars) {
                flush()
                packWords(sentence)
                flush()
            } else if (current.length + sentence.length + 1 > maxChars) {
                flush()
                current.append(sentence).append(" ")
            } else {
                current.append(sentence).append(" ")
            }
        }
        flush()
        if (chunks.isEmpty()) {
            // No sentence boundaries at all: word-pack the whole
            // text (a lone over-long token still goes out whole).
            val packed = mutableListOf<String>()
            var cur = StringBuilder()
            for (word in text.split(" ").filter { it.isNotEmpty() }) {
                if (cur.isEmpty()) {
                    cur.append(word)
                } else if (cur.length + 1 + word.length <= maxChars) {
                    cur.append(" ").append(word)
                } else {
                    packed.add(cur.toString())
                    cur = StringBuilder(word)
                }
            }
            if (cur.isNotEmpty()) packed.add(cur.toString())
            return packed.ifEmpty { listOf(text) }
        }
        return chunks
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
