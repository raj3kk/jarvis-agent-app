package com.jarvis.autonomous.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.autonomous.data.CallPower
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.service.JarvisCallCompanionService
import com.jarvis.autonomous.shizuku.ShizukuManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 1.26.0 (power features) — Calls tab (tab 12): dial pad, in-call
 * controls, the "Jarvis talks" toggle, the companion setup status,
 * WhatsApp calls, and Jarvis's OWN call log.
 *
 * Honest limits, shown in the UI:
 * - Incoming calls show only "Incoming call" — the caller number is
 *   never read (generic-only rule, §6 rule 1). The log stores the
 *   number only for calls the USER typed.
 * - "Jarvis talks" unlocks only after the M0 audio gate passes
 *   (P2+P3, Phone Health card, tab 3). Until then it is OFF and
 *   disabled — answering with Jarvis is speakerphone-only even
 *   after it unlocks (the clean tap waits for 1.27.0).
 * - WhatsApp calls are speakerphone-only (acoustic, degraded).
 * - No auto-answer, ever. No call recording without explicit
 *   per-call consent — default is listen-live, do not store.
 * - The companion override is lost on reboot / Telecom restart;
 *   it is re-applied on app start and checked every 60 s while
 *   this tab is open.
 */
@Composable
fun CallsScreen() {
    val context = LocalContext.current
    val prefs = remember { JarvisPreferences(context) }
    val scope = rememberCoroutineScope()

    var number by remember { mutableStateOf(prefs.callsLastNumber) }
    var talksEnabled by remember { mutableStateOf(prefs.jarvisTalksEnabled) }
    var audioGate by remember { mutableStateOf(prefs.m0AudioGatePassed) }
    var message by remember { mutableStateOf("") }
    var companionStatus by remember { mutableStateOf("") }
    var callLog by remember { mutableStateOf(CallPower.readCallLog(context)) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    val callState by JarvisStateRepository.callState.collectAsState()
    val shizukuState by ShizukuManager.state.collectAsState()
    val inCall = callState.status != CallPower.CallStatus.IDLE

    // One-second tick while a call is live (duration display).
    LaunchedEffect(inCall) {
        while (inCall) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }

    // Companion health check: once on open, then every 60 s while
    // this tab is open. Re-applies the override if it is gone.
    LaunchedEffect(Unit) {
        while (true) {
            if (ShizukuManager.state.value is ShizukuManager.ShizukuState.Ready) {
                val check = CallPower.checkCompanionOverride()
                companionStatus = if (check.ok) {
                    "Companion: OK — Jarvis calls ke liye ready hai."
                } else {
                    "Companion: override nahi mila (reboot par khatm ho jata hai) — dobara lagane ki koshish ho rahi hai…"
                }
                if (!check.ok) CallPower.applyCompanionOverride()
            } else {
                companionStatus = "Companion: Shizuku ready nahi hai — pehle Shizuku server start karo."
            }
            delay(60_000)
        }
    }

    // Reload the call log + the gate flag whenever a call ends.
    LaunchedEffect(callState.status) {
        if (callState.status == CallPower.CallStatus.IDLE) {
            callLog = CallPower.readCallLog(context)
            audioGate = prefs.m0AudioGatePassed
            talksEnabled = prefs.jarvisTalksEnabled
        }
    }

    fun dial() {
        val raw = number.trim()
        if (raw.isBlank()) {
            message = "Pehle number dalo."
            return
        }
        prefs.callsLastNumber = raw
        val r = CallPower.dial(context, raw)
        r.fold(
            onSuccess = { message = "Dial ho raha hai: ${CallPower.normalizeNumber(raw)}" },
            onFailure = { e -> message = e.message ?: "Dial nahi hua." }
        )
    }

    fun whatsappCall() {
        val raw = number.trim()
        if (raw.isBlank()) {
            message = "Pehle number dalo."
            return
        }
        prefs.callsLastNumber = raw
        scope.launch {
            message = "WhatsApp khol raha hoon…"
            val r = CallPower.whatsappCall(context, raw)
            r.fold(
                onSuccess = { message = it },
                onFailure = { e -> message = e.message ?: "WhatsApp call nahi hui." }
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Calls", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFFB85C38))
            Text(
                text = "Jarvis se call karo, uthao, band karo — bina default phone app bane. " +
                    "Incoming call par sirf \"Incoming call\" dikhega — caller ka number Jarvis kabhi nahi padhta.",
                fontSize = 12.sp,
                color = Color(0xFF8A7A70)
            )
        }

        // Companion status.
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Call companion", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFB85C38))
                    Text(companionStatus.ifBlank { "Status check ho raha hai…" }, fontSize = 12.sp, color = Color(0xFF3B2F2A))
                    Text(
                        text = "Setup Permissions tab (Shizuku card ke neeche, Call companion card) me hota hai. " +
                            "Override reboot par khatm ho jata hai — app start par dobara lagta hai.",
                        fontSize = 12.sp,
                        color = Color(0xFF8A7A70)
                    )
                }
            }
        }

        // "Jarvis talks" toggle.
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1DC))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Jarvis talks (caller se baat kare)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color(0xFF3B2F2A),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = talksEnabled,
                            onCheckedChange = { on ->
                                talksEnabled = on
                                prefs.jarvisTalksEnabled = on
                                message = if (on) {
                                    "Jarvis talks ON — ringing call par Jarvis uthayega (speaker se bolega)."
                                } else {
                                    "Jarvis talks OFF — Jarvis call nahi uthayega."
                                }
                            },
                            enabled = audioGate
                        )
                    }
                    if (!audioGate) {
                        Text(
                            text = "Locked — M0 probe (P2+P3) pass hone ke baad unlock hoga. " +
                                "Phone Health card (Memory & Logs, tab 3) me probe chalao. " +
                                "Unlock ke baad bhi awaaz speaker se jayegi — clean tap 1.27.0 me aayega.",
                            fontSize = 12.sp,
                            color = Color(0xFFB85C38)
                        )
                    } else {
                        Text(
                            text = "Unlocked (M0 gate pass). Awaaz abhi bhi speaker se jayegi — " +
                                "saaf nahi hogi. Clean tap ke liye 1.27.0 ka audio engine chahiye.",
                            fontSize = 12.sp,
                            color = Color(0xFF8A7A70)
                        )
                    }
                }
            }
        }

        // In-call card.
        if (inCall) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D5B))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = when (callState.status) {
                                CallPower.CallStatus.RINGING -> "Incoming call"
                                CallPower.CallStatus.DIALING -> "Dial ho raha hai…"
                                CallPower.CallStatus.ACTIVE -> "Call chal rahi hai"
                                else -> "Call"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                        Text(
                            text = callState.label,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        )
                        if (callState.startedAtMs > 0 && callState.status == CallPower.CallStatus.ACTIVE) {
                            val secs = (nowMs - callState.startedAtMs) / 1000
                            Text(
                                text = "Duration: ${secs / 60}:${"%02d".format(secs % 60)}" +
                                    if (callState.audioMode == CallPower.CallAudioMode.SPEAKERPHONE) " · speakerphone" else "",
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }
                        if (callState.status == CallPower.CallStatus.RINGING) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (JarvisCallCompanionService.answerCurrent()) {
                                            message = "Jarvis ne call uthayi (speaker se)."
                                        } else {
                                            message = "Answer nahi hua — \"Jarvis talks\" ON karo, phir try karo."
                                        }
                                    },
                                    enabled = talksEnabled,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                                ) { Text("Jarvis uthaye", color = Color(0xFF2E7D5B), fontSize = 12.sp) }
                                Button(
                                    onClick = { JarvisCallCompanionService.endCurrent() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
                                ) { Text("Reject", color = Color.White, fontSize = 12.sp) }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val next = !callState.muted
                                        JarvisCallCompanionService.setMutedCurrent(next)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                                ) {
                                    Text(
                                        if (callState.muted) "Unmute" else "Mute",
                                        color = Color(0xFF2E7D5B), fontSize = 12.sp
                                    )
                                }
                                Button(
                                    onClick = {
                                        val next = !callState.speaker
                                        JarvisCallCompanionService.setSpeakerCurrent(next)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                                ) {
                                    Text(
                                        if (callState.speaker) "Earpiece" else "Speaker",
                                        color = Color(0xFF2E7D5B), fontSize = 12.sp
                                    )
                                }
                                Button(
                                    onClick = { JarvisCallCompanionService.endCurrent() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
                                ) { Text("End", color = Color.White, fontSize = 12.sp) }
                            }
                            if (callState.status == CallPower.CallStatus.ACTIVE) {
                                Text(
                                    text = "DTMF (keypad):",
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf('1', '2', '3', '4', '5').forEach { d ->
                                        TextButton(onClick = { JarvisCallCompanionService.sendDtmfCurrent(d) }) {
                                            Text(d.toString(), color = Color.White, fontSize = 14.sp)
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf('6', '7', '8', '9', '0').forEach { d ->
                                        TextButton(onClick = { JarvisCallCompanionService.sendDtmfCurrent(d) }) {
                                            Text(d.toString(), color = Color.White, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Dial pad.
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = number,
                        onValueChange = { number = it },
                        label = { Text("Number", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val keys = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("*", "0", "#")
                    )
                    keys.forEach { rowKeys ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            rowKeys.forEach { k ->
                                Button(
                                    onClick = { number += k },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFF1DC))
                                ) { Text(k, fontSize = 16.sp, color = Color(0xFF3B2F2A)) }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { number = number.dropLast(1) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
                        ) { Text("⌫", color = Color.White, fontSize = 14.sp) }
                        Button(
                            onClick = { dial() },
                            modifier = Modifier.weight(1f),
                            enabled = !inCall,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
                        ) { Text("Call karo", color = Color.White, fontSize = 12.sp) }
                        Button(
                            onClick = { whatsappCall() },
                            modifier = Modifier.weight(1f),
                            enabled = !inCall,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
                        ) { Text("WhatsApp call", color = Color.White, fontSize = 12.sp) }
                    }
                    Text(
                        text = "WhatsApp call speaker se hogi — awaaz saaf nahi hogi (acoustic path). " +
                            "Emergency number Jarvis kabhi nahi dial karta.",
                        fontSize = 12.sp,
                        color = Color(0xFF8A7A70)
                    )
                    if (message.isNotBlank()) {
                        Text(message, fontSize = 12.sp, color = Color(0xFF3B2F2A))
                    }
                }
            }
        }

        // Jarvis's own call log (never the system call log).
        item {
            Text(
                "Jarvis call log",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Color(0xFFB85C38)
            )
            Text(
                text = "Sirf wo calls jo Jarvis ne ki ya uthayi — system call log kabhi nahi padha jata.",
                fontSize = 12.sp,
                color = Color(0xFF8A7A70)
            )
        }
        if (callLog.isEmpty()) {
            item { Text("Abhi koi call nahi hai.", fontSize = 12.sp, color = Color(0xFF8A7A70)) }
        } else {
            items(callLog, key = { it.timeMs }) { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "${if (entry.direction == CallPower.CallDirection.OUTGOING) "Outgoing" else "Incoming"} · ${entry.label}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF3B2F2A)
                        )
                        Text(
                            text = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(entry.timeMs)) +
                                " · ${entry.durationSec}s · ${entry.mode.name.lowercase()}",
                            fontSize = 11.sp,
                            color = Color(0xFF8A7A70)
                        )
                    }
                }
            }
        }
    }
}
