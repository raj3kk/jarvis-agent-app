package com.jarvis.autonomous.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.autonomous.data.CallPower
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.shizuku.ShizukuManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 1.26.0 (power features) — Phone Health card (Memory & Logs, tab 3):
 * the M0 probe (§5.5). It runs P1–P10 on THIS phone through the
 * 1.25.0 Shizuku bridge and shows every result (pass/fail + raw
 * output), so the user can run it and report back.
 *
 * The gate: the 1.27.0 call-audio engine is built ONLY if P2 AND P3
 * pass. P10 (the echo test) is PENDING in 1.26.0 — it IS the audio
 * engine test, so it cannot run before the engine exists; the card
 * says so honestly instead of faking a result.
 */
@Composable
fun PhoneHealthCard(prefs: JarvisPreferences) {
    val scope = rememberCoroutineScope()
    val results by JarvisStateRepository.probeResults.collectAsState()
    val shizukuState by ShizukuManager.state.collectAsState()
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    // Restore the last saved probe run (so the Calls tab can read
    // the gate without re-running the probe).
    LaunchedEffect(Unit) {
        if (JarvisStateRepository.probeResults.value.isEmpty()) {
            val saved = prefs.m0ProbeResultsJson
            if (saved.isNotBlank()) {
                JarvisStateRepository.setProbeResults(CallPower.probeResultsFromJson(saved))
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Phone Health — M0 probe", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFB85C38))
            Text(
                text = "Ye probe batata hai ki is phone par call-audio tap possible hai ya nahi. " +
                    "P2 + P3 dono PASS hon to 1.27.0 me audio engine banega. " +
                    "Probe chalane ke liye Shizuku server chahiye (Permissions tab).",
                fontSize = 12.sp,
                color = Color(0xFF8A7A70)
            )
            if (prefs.m0ProbeRanAtMs > 0) {
                Text(
                    text = "Last run: " + SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
                        .format(Date(prefs.m0ProbeRanAtMs)),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF8A7A70)
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        running = true
                        message = ""
                        val r = CallPower.runProbes()
                        prefs.m0ProbeResultsJson = CallPower.probeResultsToJson(r)
                        prefs.m0ProbeRanAtMs = System.currentTimeMillis()
                        val gate = CallPower.audioGatePassed(r)
                        prefs.m0AudioGatePassed = gate
                        message = if (gate) {
                            "Gate PASS — P2+P3 dono pass hain. 1.27.0 me audio engine banega. " +
                                "Result mujhe bhej do (screenshot ya Copy)."
                        } else {
                            "Gate pass nahi hua — neeche dekho kaunsi probe fail hui. " +
                                "Result mujhe bhej do (screenshot ya Copy)."
                        }
                        running = false
                    }
                },
                enabled = !running && shizukuState is ShizukuManager.ShizukuState.Ready,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
            ) {
                Text("Probe chalao", color = Color.White, fontSize = 12.sp)
            }
            if (shizukuState !is ShizukuManager.ShizukuState.Ready) {
                Text(
                    text = "Shizuku ready nahi hai — pehle Shizuku server start karo (Permissions tab, Shizuku card).",
                    fontSize = 12.sp,
                    color = Color(0xFFB85C38)
                )
            }
            if (running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text("Probe chal raha hai… (30–60s lag sakta hai)", fontSize = 12.sp, color = Color(0xFF3B2F2A))
                }
            }
            if (message.isNotBlank()) {
                Text(message, fontSize = 12.sp, color = Color(0xFF2E7D5B))
            }
            results.forEach { result ->
                val (badge, badgeColor) = when (result.status) {
                    CallPower.ProbeStatus.PASS -> "PASS" to Color(0xFF2E7D5B)
                    CallPower.ProbeStatus.FAIL -> "FAIL" to Color(0xFFB85C38)
                    CallPower.ProbeStatus.UNKNOWN -> "UNKNOWN" to Color(0xFF8A7A70)
                    CallPower.ProbeStatus.PENDING -> "PENDING" to Color(0xFF8A7A70)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "[$badge]",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor
                        )
                        Text(
                            text = "${result.id} — ${result.title}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF3B2F2A)
                        )
                    }
                    Text(result.detail, fontSize = 12.sp, color = Color(0xFF3B2F2A))
                    if (result.raw.isNotBlank()) {
                        Text(
                            text = result.raw.take(500),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF8A7A70)
                        )
                    }
                }
            }
        }
    }
}
