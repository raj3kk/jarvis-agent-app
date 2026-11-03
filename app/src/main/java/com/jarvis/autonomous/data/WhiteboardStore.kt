package com.jarvis.autonomous.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// Whiteboard state: strokes drawn by finger, one optional uploaded image,
// Jarvis-posted messages, and the user's own description of the board.
//
// HONEST LIMIT (see also INTEGRATION_NOTES_Whiteboard.md):
// On-device Gemma cannot see images and there is NO vision model in the
// app, so "Jarvis sees the drawing/image" NEVER means pixel understanding.
// The engine only ever receives describeForEngine() as TEXT context:
// stroke count/bounds, whether an image is attached (file path), and the
// user's spoken/written description (userCaption) when given. Without a
// user description, or cloud vision via the user's own Gemini key, a
// drawing/upload is NOT understood — it is only described in numbers.
//
// HARD PRIVACY RULE: a whiteboard image is NEVER uploaded to the global
// learning server, or to any server, by this store. It lives only as a
// file in the app's filesDir. describeForEngine() returns text only and
// must never be used as a reason to upload the image bytes.

data class WhiteboardPoint(
    val x: Float,
    val y: Float
)

data class WhiteboardStroke(
    val points: List<WhiteboardPoint>,
    val color: Int,
    val width: Float
)

data class WhiteboardMessage(
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class WhiteboardState(
    val strokes: List<WhiteboardStroke> = emptyList(),
    val imagePath: String? = null,
    // User-spoken (or user-typed) description of the drawing/image.
    // This is the ONLY way an on-device Jarvis "understands" the board:
    // the engine includes this text as context for its reply.
    val userCaption: String? = null,
    val messages: List<WhiteboardMessage> = emptyList()
)

object WhiteboardStore {
    private const val STATE_FILE_NAME = "whiteboard.json"
    private const val STATE_CORRUPT_NAME = "whiteboard.json.corrupt"
    private const val IMAGE_FILE_NAME = "whiteboard_image"
    // B-L10: an image larger than this is refused, not copied and then
    // decoded at full resolution.
    private const val MAX_IMAGE_BYTES = 50L * 1024L * 1024L

    private val _state = MutableStateFlow(WhiteboardState())
    val state: StateFlow<WhiteboardState> = _state.asStateFlow()

    // B-C5: a corrupt whiteboard.json is NEVER silently reset to
    // empty. The error is surfaced here (the screen shows it) and the
    // unreadable bytes are preserved as whiteboard.json.corrupt, so
    // a later save cannot destroy the only copy of the old board.
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    // Single-threaded writer so rapid stroke/message saves cannot
    // overwrite each other out of order and lose the latest state.
    private val ioScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )
    private var filesDir: File? = null
    private var loaded = false
    // Monotonic save version: an async write whose snapshot is no
    // longer the latest is skipped, so a stale write can never
    // overwrite a newer state (or a synchronous flush) after it.
    private val saveVersion = AtomicLong(0L)

    fun init(context: Context) {
        if (filesDir == null) {
            filesDir = context.applicationContext.filesDir
        }
        if (!loaded) {
            loaded = true
            load()
        }
    }

    private fun stateFile(): File? {
        val dir = filesDir ?: return null
        return File(dir, STATE_FILE_NAME)
    }

    private fun load() {
        val file = stateFile() ?: return
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            val strokes = mutableListOf<WhiteboardStroke>()
            val strokeArray = root.optJSONArray("strokes") ?: JSONArray()
            for (i in 0 until strokeArray.length()) {
                val strokeObj = strokeArray.optJSONObject(i) ?: continue
                val pointArray = strokeObj.optJSONArray("points") ?: JSONArray()
                val points = mutableListOf<WhiteboardPoint>()
                for (j in 0 until pointArray.length()) {
                    val pointObj = pointArray.optJSONObject(j) ?: continue
                    points.add(
                        WhiteboardPoint(
                            x = pointObj.optDouble("x", 0.0).toFloat(),
                            y = pointObj.optDouble("y", 0.0).toFloat()
                        )
                    )
                }
                if (points.isNotEmpty()) {
                    strokes.add(
                        WhiteboardStroke(
                            points = points,
                            color = strokeObj.optInt("color", -0x1000000),
                            width = strokeObj.optDouble("width", 8.0).toFloat()
                        )
                    )
                }
            }
            val messages = mutableListOf<WhiteboardMessage>()
            val messageArray = root.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until messageArray.length()) {
                val messageObj = messageArray.optJSONObject(i) ?: continue
                val text = messageObj.optString("text", "")
                if (text.isNotBlank()) {
                    messages.add(
                        WhiteboardMessage(
                            text = text,
                            timestampMillis = messageObj.optLong(
                                "timestampMillis",
                                System.currentTimeMillis()
                            )
                        )
                    )
                }
            }
            val imagePath = root.optString("imagePath", "").ifBlank { null }
            val userCaption = root.optString("userCaption", "").ifBlank { null }
            _state.value = WhiteboardState(
                strokes = strokes,
                imagePath = imagePath?.takeIf { File(it).exists() },
                userCaption = userCaption,
                messages = messages
            )
            _loadError.value = null
        } catch (e: Exception) {
            // B-C5: corrupt JSON surfaces an error instead of a silent
            // empty board. The unreadable bytes are kept aside first,
            // so no later save can destroy them unnoticed.
            val dir = filesDir
            if (dir != null) {
                runCatching { file.copyTo(File(dir, STATE_CORRUPT_NAME), overwrite = true) }
            }
            _loadError.value =
                "Whiteboard ka purana data kharab (corrupt) mila, isliye khaali board dikh raha hai. " +
                    "Purana data whiteboard.json.corrupt me surakshit hai."
        }
    }

    /** Serialises one snapshot. Built on the calling thread, so the
     *  async writer below can never serialise a newer state. */
    private fun stateToJson(current: WhiteboardState): String {
        val root = JSONObject()
        val strokeArray = JSONArray()
        current.strokes.forEach { stroke ->
            val pointArray = JSONArray()
            stroke.points.forEach { point ->
                pointArray.put(
                    JSONObject().apply {
                        put("x", point.x.toDouble())
                        put("y", point.y.toDouble())
                    }
                )
            }
            strokeArray.put(
                JSONObject().apply {
                    put("points", pointArray)
                    put("color", stroke.color)
                    put("width", stroke.width.toDouble())
                }
            )
        }
        root.put("strokes", strokeArray)
        root.put("imagePath", current.imagePath ?: "")
        root.put("userCaption", current.userCaption ?: "")
        val messageArray = JSONArray()
        current.messages.forEach { message ->
            messageArray.put(
                JSONObject().apply {
                    put("text", message.text)
                    put("timestampMillis", message.timestampMillis)
                }
            )
        }
        root.put("messages", messageArray)
        return root.toString()
    }

    /**
     * B-C5 atomic write: tmp file + rename, keeping exactly one
     * backup (previous good file). A crash mid-write leaves either
     * the old file or the new file intact — never a half-written
     * whiteboard.json. Returns false when the write did not land.
     */
    private fun atomicWriteText(file: File, text: String): Boolean {
        val dir = file.parentFile ?: return false
        val tmp = File(dir, "${file.name}.tmp")
        val backup = File(dir, "${file.name}.bak")
        return runCatching {
            tmp.writeText(text)
            if (file.exists()) {
                runCatching { backup.delete() }
                // If the old->backup rename fails, the tmp file is
                // still complete and the rename below is attempted;
                // only a failed final rename reports failure.
                runCatching { file.renameTo(backup) }
            }
            if (tmp.renameTo(file)) {
                true
            } else {
                // renameTo can fail across filesystems — same dir here,
                // so fall back to a direct copy rather than losing it.
                runCatching {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                    true
                }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }

    private fun save() {
        val file = stateFile() ?: return
        val json = stateToJson(_state.value)
        val version = saveVersion.incrementAndGet()
        ioScope.launch {
            // Skip when a newer snapshot (or a synchronous flush)
            // already superseded this one.
            if (version != saveVersion.get()) return@launch
            runCatching { atomicWriteText(file, json) }
        }
    }

    /**
     * B-M11 synchronous flush for teardown paths (service onDestroy,
     * screen dispose): the latest state is written before the caller
     * returns, instead of trusting an async launch that teardown may
     * cancel. Returns false when the write did not land.
     */
    fun flushSync(): Boolean {
        val file = stateFile() ?: return false
        val json = stateToJson(_state.value)
        saveVersion.incrementAndGet()
        return atomicWriteText(file, json)
    }

    fun addStroke(stroke: WhiteboardStroke) {
        if (stroke.points.isEmpty()) return
        _state.value = _state.value.copy(strokes = _state.value.strokes + stroke)
        save()
    }

    fun clearStrokes() {
        _state.value = _state.value.copy(strokes = emptyList())
        save()
    }

    fun postMessage(text: String) {
        if (text.isBlank()) return
        val updated = (_state.value.messages + WhiteboardMessage(text = text.trim()))
            .takeLast(50)
        _state.value = _state.value.copy(messages = updated)
        save()
    }

    fun setUserCaption(caption: String) {
        _state.value = _state.value.copy(
            userCaption = caption.trim().ifBlank { null }
        )
        save()
    }

    fun setImage(path: String?, caption: String? = null) {
        val current = _state.value
        _state.value = current.copy(
            imagePath = path,
            userCaption = caption?.trim()?.ifBlank { null } ?: current.userCaption
        )
        save()
    }

    // Copies a user-picked image into filesDir (local only, never uploaded)
    // and returns the local file path, or null when the copy fails.
    // B-C5 + B-L10: the copy lands via tmp + rename with one backup
    // (never a half-copied image at the live path), the size is capped,
    // and the bytes must bounds-decode as a real image before the live
    // path is replaced — a corrupt/oversized pick reports null, not a
    // broken board image.
    fun setImageFromUri(context: Context, uri: Uri): String? {
        init(context)
        val dir = filesDir ?: return null
        return runCatching {
            val declaredType = runCatching {
                context.contentResolver.getType(uri)
            }.getOrNull()
            if (declaredType != null && !declaredType.startsWith("image/")) return null
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val target = File(dir, IMAGE_FILE_NAME)
            val tmp = File(dir, "$IMAGE_FILE_NAME.tmp")
            var total = 0L
            var overLimit = false
            input.use { stream ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_IMAGE_BYTES) {
                            overLimit = true
                            break
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (overLimit) {
                runCatching { tmp.delete() }
                return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.absolutePath, bounds)
            val mime = bounds.outMimeType
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                mime == null || !mime.startsWith("image/")
            ) {
                runCatching { tmp.delete() }
                return null
            }
            if (target.exists()) {
                val backup = File(dir, "$IMAGE_FILE_NAME.bak")
                runCatching { backup.delete() }
                runCatching { target.renameTo(backup) }
            }
            if (!tmp.renameTo(target)) {
                runCatching {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            if (!target.exists()) return null
            setImage(target.absolutePath)
            target.absolutePath
        }.getOrNull()
    }

    fun clearAll() {
        _state.value.imagePath?.let { path ->
            runCatching { File(path).delete() }
        }
        _state.value = WhiteboardState()
        save()
    }

    // Text-only context for the engine. This is NOT pixel understanding:
    // it reports counts, bounds, attachment presence and the user's own
    // description. The image file itself is never sent anywhere by this
    // function, and must never be uploaded to a learning server.
    fun describeForEngine(): String {
        val current = _state.value
        val strokeCount = current.strokes.size
        val pointCount = current.strokes.sumOf { it.points.size }
        val boundsText = if (current.strokes.isEmpty()) {
            "no strokes yet"
        } else {
            val allPoints = current.strokes.flatMap { it.points }
            val minX = allPoints.minOf { it.x }
            val maxX = allPoints.maxOf { it.x }
            val minY = allPoints.minOf { it.y }
            val maxY = allPoints.maxOf { it.y }
            "bounds x=$minX..$maxX, y=$minY..$maxY (view pixels)"
        }
        val imageText = if (current.imagePath != null) {
            "image attached: yes (local file: ${File(current.imagePath).name})"
        } else {
            "image attached: no"
        }
        val captionText = current.userCaption?.takeIf { it.isNotBlank() }
            ?: "(no user description given — drawing/image is NOT understood)"
        val latestMessage = current.messages.lastOrNull()?.text
            ?: "(no Jarvis message yet)"
        return "Whiteboard context (text only — Jarvis does NOT see pixels): " +
            "$strokeCount strokes, $pointCount points, $boundsText. " +
            "$imageText. User description: $captionText. " +
            "Latest Jarvis whiteboard message: $latestMessage."
    }
}
