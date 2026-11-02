package com.jarvis.autonomous.data

import android.content.Context
import android.net.Uri
import java.io.File
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
    private const val IMAGE_FILE_NAME = "whiteboard_image"

    private val _state = MutableStateFlow(WhiteboardState())
    val state: StateFlow<WhiteboardState> = _state.asStateFlow()

    // Single-threaded writer so rapid stroke/message saves cannot
    // overwrite each other out of order and lose the latest state.
    private val ioScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )
    private var filesDir: File? = null
    private var loaded = false

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
        runCatching {
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
        }
    }

    private fun save() {
        val file = stateFile() ?: return
        val current = _state.value
        ioScope.launch {
            runCatching {
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
                file.writeText(root.toString())
            }
        }
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
    fun setImageFromUri(context: Context, uri: Uri): String? {
        init(context)
        val dir = filesDir ?: return null
        return runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val target = File(dir, IMAGE_FILE_NAME)
            input.use { stream ->
                target.outputStream().use { out -> stream.copyTo(out) }
            }
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
