package com.jarvis.autonomous.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.autonomous.data.CaptchaAssistState
import com.jarvis.autonomous.data.CaptchaStatus
import kotlinx.coroutines.delay

/**
 * Captcha assist canvas (3c, D3 §3.3 exact) — human-assist-first.
 *
 * Layout: site naam + task naam (top), captcha screenshot
 * (pinch-zoom), 60s countdown ring, [Main solve karta hun] /
 * [Task rok do]. Timeout = PAUSE, never fail, never auto-solve:
 * "Time khatam — task ruka hua hai" + [Abhi solve karo] / [Baad me].
 * Solved = green tick + "Ho gaya — task dobara shuru ho raha hai".
 *
 * The countdown is the canvas active-wait window only (60s). When
 * it expires the card calls [onTimeout] ONCE (the caller reports
 * CAPTCHA_RESOLVED timeout to the hub and pauses the task); the
 * user can still solve later via [Abhi solve karo].
 *
 * The screenshot is a Base64 PNG from the hub event or the local
 * WebView capture — never a video stream, never stored. If it is
 * missing the card degrades to site + task + countdown (the user
 * solves in the Browser tab, where the live page is).
 */
@Composable
fun CaptchaAssistCard(
    state: CaptchaAssistState,
    onSolveClick: () -> Unit,
    onRecheckClick: () -> Unit,
    onAbortClick: () -> Unit,
    onTimeout: () -> Unit,
    onReopenClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 60s countdown — keyed on task + status so a reopen restarts it.
    var remainingMs by remember(state.taskId, state.status) { mutableStateOf(60_000L) }
    var timeoutFired by remember(state.taskId, state.status) { mutableStateOf(false) }
    LaunchedEffect(state.taskId, state.status) {
        if (state.status != CaptchaStatus.ACTIVE) return@LaunchedEffect
        val deadline = state.receivedAtMs + 60_000L
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0L) {
                remainingMs = 0L
                if (!timeoutFired) {
                    timeoutFired = true
                    onTimeout()
                }
                break
            }
            remainingMs = left
            delay(250L)
        }
    }

    var confirmAbort by remember(state.taskId) { mutableStateOf(false) }
    val bitmap = remember(state.screenshotB64) {
        state.screenshotB64?.let { b64 ->
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3D6))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Jarvis ko madad chahiye — captcha aaya hai",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = Color(0xFF7A5A00)
            )
            Text(
                text = "Site: ${state.site.ifBlank { "unknown" }}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF3B2F2A)
            )
            Text(
                text = "Task: ${state.taskName}",
                fontSize = 13.sp,
                color = Color(0xFF3B2F2A)
            )

            when (state.status) {
                CaptchaStatus.SOLVED -> {
                    Text(
                        text = "✓ Ho gaya — task dobara shuru ho raha hai",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFF2E7D5B)
                    )
                    OutlinedButton(onClick = onDismiss) { Text("Band karo") }
                }
                CaptchaStatus.ABORTED -> {
                    Text(
                        text = "Task rok diya gaya.",
                        fontSize = 14.sp,
                        color = Color(0xFF3B2F2A)
                    )
                    OutlinedButton(onClick = onDismiss) { Text("Band karo") }
                }
                CaptchaStatus.TIMED_OUT -> {
                    Text(
                        text = "Time khatam — task ruka hua hai. Jab time ho, yahan se dobara kholo.",
                        fontSize = 14.sp,
                        color = Color(0xFF3B2F2A)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onReopenClick,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
                        ) { Text("Abhi solve karo", color = Color.White, fontWeight = FontWeight.Bold) }
                        OutlinedButton(onClick = onDismiss) { Text("Baad me") }
                    }
                }
                CaptchaStatus.SOLVING -> {
                    // Auto-solve phase (DESIGN §3.4): "Auto-solve kar
                    // raha hun… koshish <n>/3" with a spinner and NO
                    // buttons that can interfere. The user normally
                    // never sees this state — auto runs before the
                    // first human card; the card appears only after
                    // auto has failed. The 60 s countdown does NOT
                    // run here (the LaunchedEffect above fires only
                    // for ACTIVE), so the auto phase never consumes
                    // the human-assist window.
                    androidx.compose.material3.CircularProgressIndicator(
                        color = Color(0xFFB85C38)
                    )
                    Text(
                        text = "Auto-solve kar raha hun… koshish ${state.autoSolveAttempts.coerceAtLeast(1)}/3",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFF3B2F2A)
                    )
                    Text(
                        text = "Agar solve na hua, to aap khud solve karenge — wahi purana card khulega.",
                        fontSize = 13.sp,
                        color = Color(0xFF3B2F2A)
                    )
                }
                CaptchaStatus.ACTIVE -> {
                    // Screenshot (pinch-zoom) — or the honest fallback.
                    var scale by remember(state.taskId) { mutableStateOf(1f) }
                    var offset by remember(state.taskId) { mutableStateOf(Offset.Zero) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .pointerInput(state.taskId) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 4f)
                                    offset += pan
                                }
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Captcha screenshot",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().height(220.dp)
                            )
                        } else {
                            Text(
                                text = "Screenshot nahi mila — Browser tab me captcha khud dekho.",
                                fontSize = 13.sp,
                                color = Color(0xFF3B2F2A)
                            )
                        }
                    }

                    // 60s countdown ring + seconds.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val fraction = (remainingMs / 60_000f).coerceIn(0f, 1f)
                        Canvas(modifier = Modifier.size(44.dp)) {
                            drawCircle(color = Color(0xFFE8DCC8), style = Stroke(width = 6f))
                            drawArc(
                                color = Color(0xFFB85C38),
                                startAngle = -90f,
                                sweepAngle = 360f * fraction,
                                useCenter = false,
                                style = Stroke(width = 6f)
                            )
                        }
                        Text(
                            text = "${(remainingMs + 999L) / 1000L}s bache hain",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF3B2F2A)
                        )
                    }

                    if (confirmAbort) {
                        Text(
                            text = "Ye task ruk jayega — theek hai?",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF3B2F2A)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onAbortClick,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A2F2F))
                            ) { Text("Haan, rok do", color = Color.White, fontWeight = FontWeight.Bold) }
                            OutlinedButton(onClick = { confirmAbort = false }) { Text("Wapas") }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onSolveClick,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
                            ) { Text("Main solve karta hun", color = Color.White, fontWeight = FontWeight.Bold) }
                            OutlinedButton(onClick = { confirmAbort = true }) { Text("Task rok do") }
                        }
                        OutlinedButton(onClick = onRecheckClick) { Text("Dobara check karo") }
                    }
                }
            }
        }
    }
}
