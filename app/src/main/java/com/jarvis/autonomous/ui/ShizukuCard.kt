package com.jarvis.autonomous.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.autonomous.shizuku.ShizukuManager
import kotlinx.coroutines.launch

/**
 * 1.25.0 (Shizuku): the one-tap card on the Permissions screen.
 *
 * Honest framing, right in the UI: Shizuku is a SEPARATE app (already
 * on the user's phone) — it is NOT inside Jarvis and cannot be. This
 * card only binds to it so Jarvis can enable its own Accessibility
 * service + battery whitelist itself, once, instead of the user
 * tapping through Settings. If Shizuku is not running or not
 * authorized, the card says so and the manual cards above/below keep
 * working — nothing here replaces them.
 *
 * @param onApplied called after a protection run finishes, so the
 * parent screen can re-read the permission states (the Accessibility
 * card above flips to GRANTED when the shell run enabled it).
 */
@Composable
fun ShizukuCard(onApplied: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ShizukuManager.state.collectAsState()
    var running by remember { mutableStateOf(false) }
    var lastSteps by remember { mutableStateOf<List<ShizukuManager.ProtectionStep>?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1423)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Shizuku — Accessibility khud ON karo (ek baar)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Shizuku ek alag app hai (aapke phone me pehle se hai) — Jarvis usse judkar apni Accessibility + battery whitelist khud ON kar leta hai. Shizuku Jarvis ke andar nahi hota; uska server har reboot ke baad dobara start karna padta hai (wireless debugging se).",
                fontSize = 12.sp,
                color = Color(0xFFCFD8DC)
            )
            Spacer(modifier = Modifier.height(6.dp))
            when (val s = state) {
                is ShizukuManager.ShizukuState.NotInstalled -> {
                    Text(
                        text = "Shizuku installed nahi hai.",
                        fontSize = 12.sp,
                        color = Color(0xFFFFAB00)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { openPlayStore(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
                    ) {
                        Text(
                            text = "Shizuku install karo",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                is ShizukuManager.ShizukuState.ServerNotRunning -> {
                    Text(
                        text = "Shizuku server band hai. Shizuku app kholo → Start karo (wireless debugging). Har reboot ke baad dobara start karna padta hai.",
                        fontSize = 12.sp,
                        color = Color(0xFFFFAB00)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { openShizukuApp(context) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFB85C38)
                            )
                        ) {
                            Text(
                                text = "Shizuku kholo",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { ShizukuManager.refreshState() }) {
                            Text(
                                text = "Dobara check karo",
                                color = Color(0xFFB85C38),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                is ShizukuManager.ShizukuState.Unsupported -> {
                    Text(
                        text = "Shizuku bahut purana hai (v${s.version}) — v11+ chahiye. Shizuku update karo.",
                        fontSize = 12.sp,
                        color = Color(0xFFFFAB00)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { openShizukuApp(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
                    ) {
                        Text(
                            text = "Shizuku kholo",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                is ShizukuManager.ShizukuState.NeedsPermission -> {
                    if (s.permanentlyDenied) {
                        Text(
                            text = "Permission 'don't ask again' ke saath deny hui hai. Shizuku app kholo → Authorized apps → Jarvis ko allow karo.",
                            fontSize = 12.sp,
                            color = Color(0xFFFFAB00)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { openShizukuApp(context) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFB85C38)
                            )
                        ) {
                            Text(
                                text = "Shizuku kholo",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text(
                            text = "Shizuku se judne ki permission do — ek baar allow karo, phir Jarvis khud kaam karega.",
                            fontSize = 12.sp,
                            color = Color(0xFFCFD8DC)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                scope.launch { ShizukuManager.requestPermission() }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFB85C38)
                            )
                        ) {
                            Text(
                                text = "Shizuku permission do",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                is ShizukuManager.ShizukuState.Ready -> {
                    Text(
                        text = "Shizuku ready ✓ — " +
                            (if (s.uid == 0) "root (UID 0)" else "shell (UID 2000)") +
                            ", server v${s.version}.",
                        fontSize = 12.sp,
                        color = Color(0xFF2E7D5B)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                scope.launch {
                                    running = true
                                    lastError = null
                                    lastSteps = null
                                    val steps = runCatching {
                                        ShizukuManager.applyProtections(context)
                                    }.getOrElse {
                                        lastError = it.message ?: it.javaClass.simpleName
                                        null
                                    }
                                    lastSteps = steps
                                    running = false
                                    if (steps != null) onApplied()
                                }
                            },
                            enabled = !running,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFB85C38)
                            )
                        ) {
                            Text(
                                text = if (running) {
                                    "Shizuku se ON kar raha hoon…"
                                } else {
                                    "Shizuku se Accessibility ON karo"
                                },
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (running) {
                            Spacer(modifier = Modifier.width(10.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(0xFFB85C38),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }
            lastError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "✗ $error",
                    fontSize = 12.sp,
                    color = Color(0xFFFFAB00)
                )
            }
            lastSteps?.let { steps ->
                Spacer(modifier = Modifier.height(8.dp))
                for (step in steps) {
                    Text(
                        text = (if (step.ok) "✓ " else "✗ ") + step.title +
                            " — " + step.detail,
                        fontSize = 12.sp,
                        color = if (step.ok) Color(0xFF2E7D5B) else Color(0xFFFFAB00)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}

private fun openShizukuApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(
        ShizukuManager.SHIZUKU_PACKAGE
    )
    if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(launch) }.isSuccess) return
    }
    openPlayStore(context)
}

private fun openPlayStore(context: Context) {
    val market = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=${ShizukuManager.SHIZUKU_PACKAGE}")
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    if (runCatching { context.startActivity(market) }.isSuccess) return
    val web = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(ShizukuManager.SHIZUKU_PLAY_STORE_URL)
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(web) }
}
