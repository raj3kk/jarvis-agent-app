package com.jarvis.autonomous.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvis.autonomous.data.WhiteboardStore
import com.jarvis.autonomous.service.JarvisWhiteboardService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// In-app whiteboard screen. This is the FALLBACK when overlay permission
// is denied: the same WhiteboardView and the same WhiteboardStore as the
// floating window, so nothing drawn or posted here is lost when the user
// later grants the permission and opens the floating board.
//
// UI CONSISTENCY: creamy theme throughout — background 0xFFFFF8ED,
// terracotta primary 0xFFB85C38, text 0xFF3B2F2A. No default grey screens.
//
// HONEST LIMIT (also in WhiteboardStore and the integration notes):
// On-device Gemma cannot see images and there is no vision model in the
// app. "Jarvis sees the drawing/image" means the engine receives the
// text context from WhiteboardStore.describeForEngine() — stroke count
// and bounds, image attachment, and YOUR description below — plus cloud
// vision only if you have your own Gemini key. It does NOT literally
// interpret pixels, and the image is NEVER uploaded to any learning
// server: it stays as a file in the app's filesDir.
@Composable
fun WhiteboardScreen() {
    // Every value used by the launcher / effects below is declared
    // BEFORE it, per the kotlin_prepush_checks skill (declaration order).
    val context = LocalContext.current
    val storeState by WhiteboardStore.state.collectAsState()
    val loadError by WhiteboardStore.loadError.collectAsState()
    var selectedColor by remember { mutableStateOf(WhiteboardView.COLORS.first()) }
    var captionText by remember {
        mutableStateOf(WhiteboardStore.state.value.userCaption ?: "")
    }
    var pickedStatus by remember { mutableStateOf("") }
    var canDrawOverlays by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(context)
        )
    }
    // B-L10: bounds pass + inSampleSize downsample to the displayed
    // size (screen width x 240dp). The old code decoded the picked
    // image at full resolution into this preview — a 50 MP photo
    // could OOM the app from the whiteboard tab alone. Oversized or
    // non-image files decode to null and show the fallback text.
    val imageBitmap = remember(storeState.imagePath) {
        val path = storeState.imagePath ?: return@remember null
        runCatching {
            val file = java.io.File(path)
            if (!file.isFile || file.length() <= 0L ||
                file.length() > 50L * 1024L * 1024L
            ) {
                return@runCatching null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val mime = bounds.outMimeType
            if (mime == null || !mime.startsWith("image/")) return@runCatching null
            val density = context.resources.displayMetrics.density
            val targetWidth = context.resources.displayMetrics.widthPixels
                .coerceAtLeast(1)
            val targetHeight = (240f * density).toInt().coerceAtLeast(1)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth &&
                bounds.outHeight / (sample * 2) >= targetHeight
            ) {
                sample *= 2
            }
            val decode = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, decode)
        }.getOrNull()
    }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val savedPath = WhiteboardStore.setImageFromUri(context, uri)
        pickedStatus = if (savedPath != null) {
            "Image saved on this phone (not uploaded anywhere)."
        } else {
            "Image could not be saved — please try again."
        }
    }

    LaunchedEffect(Unit) {
        WhiteboardStore.init(context)
        canDrawOverlays = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)
    }
    // B-M11: when this screen goes away, the latest strokes/messages
    // are flushed synchronously — no reliance on an async save that
    // teardown could leave in flight.
    DisposableEffect(Unit) {
        onDispose {
            runCatching { WhiteboardStore.flushSync() }
        }
    }
    LaunchedEffect(storeState.userCaption) {
        val saved = storeState.userCaption
        if (saved != null && captionText.isBlank()) {
            captionText = saved
        }
    }

    val creamBackground = Color(0xFFFFF8ED)
    val terracotta = Color(0xFFB85C38)
    val darkText = Color(0xFF3B2F2A)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(creamBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Jarvis Whiteboard",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = terracotta
        )

        // B-C5: a corrupt saved board is surfaced here, never silently
        // shown as an empty board.
        val corruptError = loadError
        if (corruptError != null) {
            Text(
                text = corruptError,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFB3261E)
            )
        }

        // Plain-language mode explanation — never silently pretend the
        // floating board is available when it is not.
        if (!canDrawOverlays) {
            Text(
                text = "Floating whiteboard over other apps is NOT available " +
                    "right now, because overlay permission has not been granted. " +
                    "This in-app whiteboard is the fallback: you can draw here, " +
                    "upload an image, and read Jarvis's messages, and everything " +
                    "is shared with the floating board once permission is granted.",
                fontSize = 13.sp,
                color = darkText
            )
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = terracotta)
            ) {
                Text("Open overlay permission settings", color = Color.White)
            }
        } else {
            Text(
                text = "Overlay permission is granted — the whiteboard can also " +
                    "float over other apps. This screen shows the same board.",
                fontSize = 13.sp,
                color = darkText
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { JarvisWhiteboardService.show(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = terracotta)
                ) {
                    Text("Open floating whiteboard", color = Color.White)
                }
                Button(
                    onClick = { JarvisWhiteboardService.hide(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = terracotta)
                ) {
                    Text("Close floating", color = Color.White)
                }
            }
        }

        Text(
            text = "Jarvis does NOT see pixels. What Jarvis understands is your " +
                "description below (spoken or typed) plus stroke counts — or cloud " +
                "vision only if you have your own Gemini key. Without a description, " +
                "a drawing or image is not understood.",
            fontSize = 13.sp,
            color = darkText
        )

        // Drawing surface: the same classic View as the floating window.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(Color.White)
                .border(2.dp, terracotta)
        ) {
            AndroidView(
                factory = { viewContext ->
                    WhiteboardView(viewContext).apply {
                        setCurrentColor(selectedColor)
                        onStrokeFinished = { stroke ->
                            WhiteboardStore.addStroke(stroke)
                        }
                        setStrokes(WhiteboardStore.state.value.strokes)
                    }
                },
                update = { view ->
                    view.setStrokes(storeState.strokes)
                    view.setCurrentColor(selectedColor)
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (colorValue in WhiteboardView.COLORS) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(colorValue), shape = CircleShape)
                        .border(
                            width = if (selectedColor == colorValue) 3.dp else 1.dp,
                            color = if (selectedColor == colorValue) {
                                terracotta
                            } else {
                                darkText
                            },
                            shape = CircleShape
                        )
                        .clickable { selectedColor = colorValue }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { WhiteboardStore.clearStrokes() },
                colors = ButtonDefaults.buttonColors(containerColor = terracotta)
            ) {
                Text("Clear drawing", color = Color.White)
            }
            Button(
                onClick = {
                    WhiteboardStore.clearAll()
                    captionText = ""
                    pickedStatus = ""
                },
                colors = ButtonDefaults.buttonColors(containerColor = terracotta)
            ) {
                Text("Clear all", color = Color.White)
            }
        }

        // Image upload: picked image is copied into filesDir only.
        Button(
            onClick = { imagePicker.launch("image/*") },
            colors = ButtonDefaults.buttonColors(containerColor = terracotta)
        ) {
            Text("Pick an image", color = Color.White)
        }
        if (pickedStatus.isNotBlank()) {
            Text(text = pickedStatus, fontSize = 13.sp, color = darkText)
        }
        val bitmap = imageBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Uploaded whiteboard image",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            )
        } else if (storeState.imagePath != null) {
            Text(
                text = "Image saved, but it could not be previewed on this screen.",
                fontSize = 13.sp,
                color = darkText
            )
        }

        // User-spoken/written description — the description path that
        // makes the board Jarvis-understood on-device.
        Text(
            text = "Describe this board for Jarvis (speak it, then type it here):",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = darkText
        )
        OutlinedTextField(
            value = captionText,
            onValueChange = { captionText = it },
            label = { Text("What is on the whiteboard?") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { WhiteboardStore.setUserCaption(captionText) },
            colors = ButtonDefaults.buttonColors(containerColor = terracotta)
        ) {
            Text("Save description for Jarvis", color = Color.White)
        }

        // What the engine actually receives (text context, not pixels).
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "What Jarvis receives (text only):",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = darkText
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = WhiteboardStore.describeForEngine(),
                    fontSize = 12.sp,
                    color = darkText
                )
            }
        }

        // Jarvis-posted messages, newest last.
        Text(
            text = "Jarvis messages",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = terracotta
        )
        if (storeState.messages.isEmpty()) {
            Text(
                text = "(No Jarvis messages yet.)",
                fontSize = 13.sp,
                color = darkText
            )
        } else {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            for (message in storeState.messages) {
                Text(
                    text = "${timeFormat.format(Date(message.timestampMillis))} — ${message.text}",
                    fontSize = 13.sp,
                    color = darkText
                )
            }
        }
    }
}
