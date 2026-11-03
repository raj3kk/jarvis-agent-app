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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
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

    /**
     * 1.4.5 part 3 (item 2a): PINNED asset sizes + SHA-256 hashes.
     * Computed from the verified 1.4.5-part-2 APK's shipped assets
     * (public Sherpa-ONNX model files, safe to commit). The old
     * CI gate was only ">50 MB" for the model while the model is
     * ~109 MB expected, and the runtime copy manifest was built
     * from bundled bytes — self-consistent, so a truncated voice
     * file passed CI and died natively at Sherpa load. Now a
     * mismatch is a HARD FAIL (diagnostic HASH_MISMATCH, no
     * engine load) at copy time, at manifest-compare time, and
     * at engine-load time. espeak-ng-data files are NOT pinned
     * (many files); existence + non-empty + engine-load success
     * remain their check, and init failure fails fast with
     * ENGINE_INIT_FAIL. NOTE: pins are taken from the part-2
     * APK's bundled assets (known-good as shipped), not
     * independently hash-verified against upstream — stated
     * honestly in the 1.4.5 report.
     */
    const val PIN_MODEL_SIZE = 114_202_993L
    const val PIN_MODEL_SHA = "75635ea3b85a32d00094dcdd61a258aba87c160f7c264c304a7b3ae9d734195e"
    const val PIN_VOICES_SIZE = 28_200_960L
    const val PIN_VOICES_SHA = "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29"
    const val PIN_TOKENS_SIZE = 687L
    const val PIN_TOKENS_SHA = "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425"
    const val PIN_VAD_SIZE = 643_854L
    const val PIN_VAD_SHA = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"

    /** Streaming SHA-256 of a file (never loads the 114 MB model into memory). */
    fun sha256Of(file: File): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private val engineLock = Mutex()
    /** B-M1: ONE mutex guarding ensureAssetsReady across launch
     * overlay, Settings retry and speak-triggered copy — concurrent
     * copies used to collide on the same *.tmp files. */
    private val copyMutex = Mutex()
    private var engine: OfflineTts? = null
    private var engineDir: File? = null
    private var speakerCheckLogged: Boolean = false
    private var firstGenerateLogged: Boolean = false

    @Volatile
    private var currentTrack: AudioTrack? = null
    private val stopped = AtomicBoolean(false)

    fun modelDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "Kokoro")
    }

    /**
     * 1.4.5 part 3 (item 2a): download-complete now means SIZES
     * match pins AND HASHES match pins. The hash manifest
     * (kokoro-hashes.txt) is compared by cheap string equality —
     * no rehash per speak. A dir whose files are the right size
     * but whose hash manifest is missing/stale returns FALSE, so
     * [ensureAssetsReady] runs its one-time hash verification
     * (hash the file: match -> record + skip, mismatch -> recopy
     * that file only). A legacy 1.3.0 dir with neither manifest
     * therefore gets verified once, not trusted forever.
     * The old ">50MB" legacy fallback is DEAD under this rule:
     * without a hash manifest, size alone never proves the file.
     */
    fun isDownloaded(context: Context): Boolean {
        val dir = modelDir(context)
        val model = File(dir, MODEL_FILE)
        val voices = File(dir, VOICES_FILE)
        val tokens = File(dir, TOKENS_FILE)
        val espeak = File(dir, "espeak-ng-data")
        if (!model.exists() || model.length() != PIN_MODEL_SIZE) return false
        if (!voices.exists() || voices.length() != PIN_VOICES_SIZE) return false
        if (!tokens.exists() || tokens.length() != PIN_TOKENS_SIZE) return false
        if (!espeak.isDirectory) return false
        if (!espeak.walkTopDown().any { it.isFile }) return false
        val hashes = readHashesManifest(dir) ?: return false
        if (hashes[MODEL_FILE] != PIN_MODEL_SHA) return false
        if (hashes[VOICES_FILE] != PIN_VOICES_SHA) return false
        if (hashes[TOKENS_FILE] != PIN_TOKENS_SHA) return false
        return true
    }

    /** Hashes manifest written by [ensureAssetsReady] (rel=sha256 lines). */
    private const val HASHES_MANIFEST_FILE = "kokoro-hashes.txt"

    private fun readHashesManifest(dir: File): Map<String, String>? {
        val manifest = File(dir, HASHES_MANIFEST_FILE)
        if (!manifest.exists()) return null
        return runCatching {
            manifest.readLines()
                .mapNotNull { line ->
                    val idx = line.lastIndexOf('=')
                    if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
                }.toMap().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun writeHashesManifest(dir: File, hashes: Map<String, String>) {
        runCatching {
            File(dir, HASHES_MANIFEST_FILE).writeText(
                hashes.entries.joinToString("\n") { "${it.key}=${it.value}" }
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
    /**
     * 1.4.5 part 3 (item 1a): ALL copies go through :tts (single
     * writer). Main-process callers use TtsProcessClient.ensure()
     * (MSG_ENSURE) — never call this directly from main, or the
     * per-process copyMutex cannot protect the shared *.tmp files
     * against a concurrent :tts copy. [ensureAssetsReady] takes
     * the lock; [ensureAssetsReadyInner] assumes it (used by
     * [resetAndCopy] so delete + re-copy are atomic in :tts).
     */
    suspend fun ensureAssetsReady(
        context: Context,
        onProgress: (stage: String, copiedMB: Long, totalMB: Long) -> Unit = { _, _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        copyMutex.withLock { ensureAssetsReadyInner(context, onProgress) }
    }

    private suspend fun ensureAssetsReadyInner(
        context: Context,
        onProgress: (stage: String, copiedMB: Long, totalMB: Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
            runCatching {
                if (isDownloaded(context)) {
                    val totalMB = bundledModelBytes(context) / 1_000_000L
                    onProgress("ready", totalMB, totalMB)
                    return@runCatching
                }
                // 1.4.5 (item 2a): one-time HASH verification for a
                // dir whose files are the right SIZE but whose hash
                // manifest is missing/stale (legacy dirs, or a copy
                // made before hashes existed). Hash each pinned
                // file: all match -> record manifests + ready (no
                // copy); any mismatch -> delete ONLY that file so
                // the copy path below recopies it (the copy loop
                // skips size-matching files, so a bad file must
                // not survive to the loop). Size alone never
                // proves a file after this point.
                run {
                    val vdir = modelDir(context)
                    val pinned = mapOf(
                        MODEL_FILE to PIN_MODEL_SHA,
                        VOICES_FILE to PIN_VOICES_SHA,
                        TOKENS_FILE to PIN_TOKENS_SHA
                    )
                    val allSized = pinned.keys.all { rel ->
                        val f = File(vdir, rel)
                        f.exists() && f.length() == when (rel) {
                            MODEL_FILE -> PIN_MODEL_SIZE
                            VOICES_FILE -> PIN_VOICES_SIZE
                            else -> PIN_TOKENS_SIZE
                        }
                    }
                    if (allSized) {
                        val hashes = mutableMapOf<String, String>()
                        var bad: String? = null
                        for ((rel, pin) in pinned) {
                            val actual = sha256Of(File(vdir, rel))
                            if (actual == pin) {
                                hashes[rel] = pin
                            } else {
                                bad = rel
                                JarvisStateRepository.addDiagnosticSync(
                                    "tts", "HASH_MISMATCH",
                                    "Pinned asset $rel failed hash verification — deleting for recopy (no engine load)."
                                )
                                runCatching { File(vdir, rel).delete() }
                            }
                        }
                        if (bad == null) {
                            val espeakOk = File(vdir, "espeak-ng-data").walkTopDown().any { it.isFile }
                            if (espeakOk) {
                                writeHashesManifest(vdir, hashes)
                                val totalMB = bundledModelBytes(context) / 1_000_000L
                                onProgress("ready", totalMB, totalMB)
                                return@runCatching
                            }
                        }
                    }
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
                // 1.4.5 (item 2a): HARD FAIL on hash mismatch of a
                // FRESH copy — a truncated/corrupt bundled file must
                // never reach a native engine load. Bad file is
                // deleted; the failure names the file, not a vague
                // "copy failed". No engine load happens on this path.
                run {
                    val freshPins = mapOf(
                        MODEL_FILE to PIN_MODEL_SHA,
                        VOICES_FILE to PIN_VOICES_SHA,
                        TOKENS_FILE to PIN_TOKENS_SHA
                    )
                    val freshHashes = mutableMapOf<String, String>()
                    for ((rel, pin) in freshPins) {
                        val f = File(dir, rel)
                        val actual = sha256Of(f)
                        if (actual != pin) {
                            JarvisStateRepository.addDiagnosticSync(
                                "tts", "HASH_MISMATCH",
                                "Fresh copy of $rel failed hash verification (expected pin, got ${actual ?: "unreadable"}) — deleted, engine NOT loaded."
                            )
                            runCatching { f.delete() }
                            throw IllegalStateException(
                                "Kokoro asset $rel failed hash verification — " +
                                    "relaunch Jarvis to recopy it."
                            )
                        }
                        freshHashes[rel] = pin
                    }
                    writeHashesManifest(dir, freshHashes)
                }
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

    /**
     * B-C1 reset path, callable from the Settings retry button
     * VIA TtsProcessClient.reset (1.4.5): releases the engine,
     * deletes the whole model dir, then re-copies bundled assets
     * fresh. A corrupt copy is never "repaired" by re-verifying
     * it in place — it is replaced.
     *
     * 1.4.5: this MUST run in the :tts process (KokoroTtsService
     * MSG_RESET) — never call it directly from the main process.
     * The engine lives in :tts; a main-process delete of the
     * shared model dir under a loaded engine is a native
     * use-after-delete. Main-process callers use
     * TtsProcessClient.reset() instead.
     */
    suspend fun resetAndCopy(
        context: Context,
        onProgress: (stage: String, copiedMB: Long, totalMB: Long) -> Unit = { _, _, _ -> }
    ): Result<Unit> {
        engineLock.withLock {
            runCatching { engine?.release() }
            engine = null
            engineDir = null
            firstGenerateLogged = false
        }
        stopPlayback()
        // 1.4.5 (item 1a): the old code deleted the dir OUTSIDE
        // copyMutex while ensureAssetsReady took it — a concurrent
        // ensure could copy into a dir being deleted (or read a
        // half-deleted file). Now delete + inner ensure hold the
        // same lock, so every copy/delete path in :tts is serial.
        return withContext(Dispatchers.IO) {
            copyMutex.withLock {
                runCatching { modelDir(context).deleteRecursively() }
                JarvisStateRepository.addLog("Kokoro model reset — fresh bundled copy starting.")
                ensureAssetsReadyInner(context, onProgress)
            }
        }
    }

    /**
     * 1.4.5 (item 2c): release the native engine WITHOUT suspending.
     * Returns false (engine kept) when engine creation is in flight
     * — releasing under a running generate/load would be a native
     * use-after-release. Callers that get false set a pending flag
     * and retry after the speak completes (see KokoroTtsService).
     * The next speak reloads (FIRST_ENGINE_LOAD appears again).
     */
    fun tryReleaseEngine(reason: String): Boolean {
        if (!engineLock.tryLock()) return false
        try {
            runCatching { engine?.release() }
            engine = null
            engineDir = null
            firstGenerateLogged = false
            JarvisStateRepository.addDiagnostic(
                "tts", "ENGINE_RELEASED",
                "Kokoro engine released (reason=$reason) — next speak reloads."
            )
            return true
        } finally {
            engineLock.unlock()
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
        // 1.4.5: GENERATE-stage markers are PRIMARY now (user
        // evidence 2026-11-03: NO greeting audio heard at all, so
        // death was BEFORE the first audible sample — playback is
        // eliminated as the death stage). SPK_ENGINE_START without
        // SPK_ENGINE_END = death during native model load (114 MB
        // ONNX load / native allocation / LMK kill); GENERATE_START
        // without GENERATE_END = death during ONNX inference.
        // These run in the :tts process and land in the SHARED
        // diagnostics.jsonl file, so they survive a :tts death
        // and name the stage that killed it. Never the text.
        JarvisStateRepository.addDiagnosticSync(
            "tts", "SPK_ENGINE_START",
            "Kokoro engine load started (native model load)."
        )
        // 1.4.5 REDO — init-failure handling split by EVIDENCE.
        // The old code deleted + recopied the whole ~190 MB dir
        // on EVERY init failure, so a deterministically-broken
        // init (ABI/ONNX fault, OOM, or a bundle file the copy
        // itself cannot supply) recopied 190 MB on every single
        // utterance and re-entered the native death window each
        // time. Now: size verification FAILED (isDownloaded
        // false) = real corruption evidence -> ensureAssetsReady
        // (recopies ONLY mismatched files, never a blind
        // whole-dir delete) + one retry; sizes verify OK but
        // init still throws = NOT corruption -> fail fast with
        // the exact init error, dir untouched. The explicit
        // Settings reset (routed via :tts) remains the recovery
        // for a genuinely bad bundle.
        val tts = try {
            getEngine(context)
        } catch (e: Exception) {
            JarvisStateRepository.addDiagnosticSync(
                "tts", "ENGINE_INIT_FAIL",
                "Kokoro engine init failed: ${e.javaClass.simpleName}: ${e.message ?: ""}."
            )
            if (!isDownloaded(context)) {
                JarvisStateRepository.addLog(
                    "Kokoro init failed with size mismatch — recopying only mismatched files."
                )
                val ready = ensureAssetsReady(context)
                val recoveredTts = if (ready.isSuccess) {
                    runCatching { getEngine(context) }.getOrNull()
                } else {
                    null
                }
                if (recoveredTts == null) {
                    JarvisStateRepository.addLog(
                        "Kokoro still unavailable after recopy — no " +
                            "fallback voice in the Kokoro-only build."
                    )
                    JarvisStateRepository.addDiagnosticSync(
                        "tts", "SPK_ENGINE_END",
                        "Kokoro engine unavailable after recopy."
                    )
                    return false
                }
                recoveredTts
            } else {
                JarvisStateRepository.addLog(
                    "Kokoro init failed with verified sizes — NOT " +
                        "deleting the model dir (cause is not corruption; " +
                        "use Settings reset if the bundle itself is bad)."
                )
                JarvisStateRepository.addDiagnosticSync(
                    "tts", "SPK_ENGINE_END",
                    "Kokoro engine init failed with verified sizes — failing fast, dir untouched."
                )
                return false
            }
        }
        JarvisStateRepository.addDiagnosticSync(
            "tts", "SPK_ENGINE_END",
            "Kokoro engine ready (cached or freshly loaded)."
        )
        return try {
            withContext(Dispatchers.IO) {
                val chunks = splitChunks(text, 600)
                for ((index, chunk) in chunks.withIndex()) {
                    if (stopped.get()) return@withContext false
                    JarvisStateRepository.addDiagnosticSync(
                        "tts", "SPK_GENERATE_START",
                        "Generate chunk ${index + 1}/${chunks.size} (chars=${chunk.length})."
                    )
                    if (!firstGenerateLogged) {
                        firstGenerateLogged = true
                        JarvisStateRepository.addDiagnosticSync(
                            "tts", "FIRST_GENERATE",
                            "First generate in this process (chunk ${index + 1}/${chunks.size})."
                        )
                    }
                    val audio = tts.generate(chunk, HF_ALPHA_SID, 1.0f)
                    JarvisStateRepository.addDiagnosticSync(
                        "tts", "SPK_GENERATE_END",
                        "Generate chunk ${index + 1} done (samples=${audio.samples.size})."
                    )
                    if (stopped.get()) return@withContext false
                    JarvisStateRepository.addDiagnosticSync(
                        "tts", "SPK_PLAY_START",
                        "Playback chunk ${index + 1}/${chunks.size}."
                    )
                    val played = playAudio(audio)
                    JarvisStateRepository.addDiagnosticSync(
                        "tts", "SPK_PLAY_END",
                        "Playback chunk ${index + 1} ok=$played."
                    )
                    if (!played) return@withContext false
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
            // 1.4.8: threads 2 -> 1. The generate death (phone
            // report 2026-11-03 22:07:33) happens inside native
            // inference in a secondary process; a second intra-op
            // thread buys parallel tensor workspace for ~nothing
            // on short utterances and raises the RSS peak that
            // Vivo LMK kills on. Single-threaded generate is
            // marginally slower per chunk but measurably
            // smaller at peak — the right trade for survival.
            numThreads = 1,
            debug = false,
            provider = "cpu"
        )
        // 1.4.5 (item 2a/2b): pre-load HASH check — a bad file
        // must NEVER reach a native OfflineTts load (the load is
        // the first native failure point). Mismatch fails fast
        // with HASH_MISMATCH, dir untouched, no native load.
        JarvisStateRepository.addDiagnosticSync(
            "tts", "FIRST_ENGINE_LOAD",
            "First engine creation in this process — hashing pinned files before native load."
        )
        run {
            val prePins = mapOf(
                MODEL_FILE to PIN_MODEL_SHA,
                VOICES_FILE to PIN_VOICES_SHA,
                TOKENS_FILE to PIN_TOKENS_SHA
            )
            for ((rel, pin) in prePins) {
                val actual = sha256Of(File(dir, rel))
                if (actual != pin) {
                    JarvisStateRepository.addDiagnosticSync(
                        "tts", "HASH_MISMATCH",
                        "Engine load refused: $rel failed hash verification — run Settings Kokoro reset (recopy via :tts)."
                    )
                    throw IllegalStateException(
                        "Kokoro asset $rel failed hash verification before engine load."
                    )
                }
            }
        }
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
                // 1.4.3: empty audio is a FAILURE, never a silent
                // "success" — the old `return true` made Jarvis
                // look like it spoke while nothing was heard, and
                // the chat-only fallback never fired.
                if (samples.isEmpty()) {
                    JarvisStateRepository.addLog(
                        "Kokoro playback failed: empty audio — nothing to play."
                    )
                    JarvisStateRepository.addDiagnostic(
                        "tts", "EMPTY_AUDIO",
                        "Kokoro generated zero samples — treated as failure, not success."
                    )
                    return@withContext false
                }
                val pcm = ByteArray(samples.size * 2)
                for (i in samples.indices) {
                    val v = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
                    pcm[i * 2] = (v and 0xFF).toByte()
                    pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                val rate = audio.sampleRate.takeIf { it > 0 } ?: 24000
                // 1.4.3: AudioTrack init/write failure gets ONE
                // retry with a doubled buffer before giving up,
                // and the exact exception is recorded — a dead
                // track must never look like a finished utterance.
                val played = playTrackOnce(pcm, rate, bufferScale = 1)
                if (played || stopped.get()) return@withContext played
                JarvisStateRepository.addLog(
                    "Kokoro playback retrying once with a doubled AudioTrack buffer."
                )
                playTrackOnce(pcm, rate, bufferScale = 2)
            } catch (e: Exception) {
                JarvisStateRepository.addLog("Kokoro playback failed: ${e.message}")
                JarvisStateRepository.addDiagnostic(
                    "tts", "PLAYBACK_ERROR",
                    "Kokoro playback failed: ${e.javaClass.simpleName}: ${e.message ?: ""}"
                )
                false
            }
        }

    /**
     * One AudioTrack play attempt. Returns true only when the full
     * buffer was written and played (or playback was explicitly
     * stopped, which the caller treats as "not failed, not
     * played" via the stopped flag). Throws on init failure so
     * the caller can log + retry; a short write is a failure.
     */
    private fun playTrackOnce(pcm: ByteArray, rate: Int, bufferScale: Int): Boolean {
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
            .setBufferSizeInBytes(maxOf(minBuf * bufferScale, pcm.size))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            currentTrack = track
            track.play()
            var offset = 0
            var writeFailed = false
            while (offset < pcm.size && !stopped.get()) {
                val written = track.write(pcm, offset, pcm.size - offset)
                if (written < 0) {
                    writeFailed = true
                    break
                }
                if (written == 0) {
                    // Avoid a hot spin on a track that accepts
                    // nothing; treat as a failed write.
                    Thread.sleep(10L)
                    writeFailed = true
                    break
                }
                offset += written
            }
            if (stopped.get()) return false
            if (writeFailed || offset < pcm.size) {
                JarvisStateRepository.addDiagnostic(
                    "tts", "WRITE_FAIL",
                    "AudioTrack wrote $offset/${pcm.size} bytes (scale=$bufferScale) — playback incomplete."
                )
                return false
            }
            val frames = pcm.size / 2
            runBlocking {
                withTimeoutOrNull(60_000L) {
                    while (!stopped.get() &&
                        track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                        track.playbackHeadPosition < frames
                    ) {
                        delay(50L)
                    }
                }
            }
            return !stopped.get()
        } finally {
            runCatching { track.stop(); track.release() }
            if (currentTrack == track) currentTrack = null
        }
    }
}
