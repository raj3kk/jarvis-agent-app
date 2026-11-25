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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.autonomous.data.CallPower
import com.jarvis.autonomous.shizuku.ShizukuManager
import kotlinx.coroutines.launch

/**
 * 1.26.0 (power features) — Call companion card (Permissions tab 1,
 * right after the Shizuku card): applies the Telecom companion
 * setup so Jarvis can dial / answer / end / mute / DTMF / speaker
 * WITHOUT becoming the default phone app.
 *
 * Setup: `appops MANAGE_ONGOING_CALLS allow` + the test-only
 * `cmd telecom add-or-remove-call-companion-app` override. The
 * override is lost on reboot / Telecom restart — it is re-applied
 * on app start and checked every 60 s while the Calls tab is open.
 * Each step is verified by read-back (dumpsys telecom), never
 * assumed.
 *
 * The card NEVER reads caller identity — incoming calls show only
 * "Incoming call" (generic-only rule, §6 rule 1).
 */
@Composable
fun CallControlCard() {
    val scope = rememberCoroutineScope()
    val shizukuState by ShizukuManager.state.collectAsState()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Call companion (Jarvis calls)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFB85C38))
            Text(
                text = "Jarvis ko call dial / answer / end karne do — bina default phone app bane. " +
                    "Setup Shizuku se hota hai: appops + Telecom override. " +
                    "Override reboot par khatm ho jata hai, app start par dobara lagta hai.",
                fontSize = 12.sp,
                color = Color(0xFF8A7A70)
            )
            Text(
                text = "Caller ka naam/number Jarvis kabhi nahi padhta — incoming call par sirf \"Incoming call\" dikhega.",
                fontSize = 12.sp,
                color = Color(0xFF8A7A70)
            )
            if (status.isNotBlank()) {
                Text(status, fontSize = 12.sp, color = Color(0xFF3B2F2A))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Setup chal raha hai…"
                            val steps = CallPower.applyCompanionOverride()
                            status = steps.joinToString("\n") {
                                "${if (it.ok) "✓" else "✗"} ${it.title}: ${it.detail}"
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && shizukuState is ShizukuManager.ShizukuState.Ready,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
                ) { Text("Companion setup karo", color = Color.White, fontSize = 12.sp) }
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val check = CallPower.checkCompanionOverride()
                            status = "${if (check.ok) "✓" else "✗"} ${check.title}: ${check.detail}"
                            busy = false
                        }
                    },
                    enabled = !busy && shizukuState is ShizukuManager.ShizukuState.Ready,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
                ) { Text("Status check karo", color = Color.White, fontSize = 12.sp) }
            }
            if (shizukuState !is ShizukuManager.ShizukuState.Ready) {
                Text(
                    text = "Shizuku ready nahi hai — pehle Shizuku server start karo (upar Shizuku card).",
                    fontSize = 12.sp,
                    color = Color(0xFFB85C38)
                )
            }
        }
    }
}
