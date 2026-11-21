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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.autonomous.data.DocConfirmState
import com.jarvis.autonomous.data.DocConfirmStatus

/**
 * Doc confirm card (3c, 3b §2b contract) — one-shot confirmation:
 * extracted fields + missing-field inputs, ek click "Confirm &
 * Proceed". Kabhi tukde-tukde sawal nahi — ek card, ek click.
 *
 * `fields` are shown read-only; every `missing` field gets an
 * editable input. [Confirm & Proceed] sends the merged record
 * (extracted fields + user-filled missing fields) to
 * POST /api/docs/confirm via [onConfirm]. If the server still
 * reports missing fields, the caller refreshes this card with the
 * new missing list (same card, no new screen).
 */
@Composable
fun ConfirmCard(
    state: DocConfirmState,
    onConfirm: (fields: Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Editable values for the missing fields only — keyed on the
    // job so a new job never reuses stale inputs. Merged record =
    // extracted fields + user-filled missing fields.
    var edited by remember(state.docJobId) { mutableStateOf(mapOf<String, String>()) }
    val merged: Map<String, String> = state.fields + edited

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3EC))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Document confirm karo",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = Color(0xFF2E7D5B)
            )
            Text(
                text = "Doc job #${state.docJobId} — confidence ${(state.confidence * 100).toInt()}%",
                fontSize = 13.sp,
                color = Color(0xFF3B2F2A)
            )

            if (state.fields.isEmpty() && state.missing.isEmpty()) {
                Text(
                    text = "Koi field nahi mili — extract dobara chalao.",
                    fontSize = 13.sp,
                    color = Color(0xFF3B2F2A)
                )
            }
            // Extracted fields (read-only).
            for ((key, value) in state.fields) {
                Column {
                    Text(text = key, fontSize = 12.sp, color = Color(0xFF8A7A70))
                    Text(text = value, fontSize = 14.sp, color = Color(0xFF3B2F2A))
                }
            }
            // Missing fields (editable inputs).
            for (key in state.missing) {
                OutlinedTextField(
                    value = edited[key] ?: "",
                    onValueChange = { edited = edited + (key to it) },
                    label = { Text("$key (bharna zaroori hai)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            when (state.status) {
                DocConfirmStatus.CONFIRMED -> {
                    Text(
                        text = "✓ Confirmed — ${state.message}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFF2E7D5B)
                    )
                    OutlinedButton(onClick = onDismiss) { Text("Band karo") }
                }
                DocConfirmStatus.ERROR -> {
                    Text(
                        text = state.message,
                        fontSize = 14.sp,
                        color = Color(0xFF8A2F2F)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onConfirm(merged) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
                        ) { Text("Dobara confirm karo", color = Color.White, fontWeight = FontWeight.Bold) }
                        OutlinedButton(onClick = onDismiss) { Text("Band karo") }
                    }
                }
                DocConfirmStatus.PENDING -> {
                    val stillMissing = state.missing.filter { (edited[it] ?: "").isBlank() }
                    Button(
                        onClick = { onConfirm(merged) },
                        enabled = stillMissing.isEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
                    ) { Text("Confirm & Proceed", color = Color.White, fontWeight = FontWeight.Bold) }
                    if (stillMissing.isNotEmpty()) {
                        Text(
                            text = "Pehle ye bharo: ${stillMissing.joinToString(", ")}",
                            fontSize = 12.sp,
                            color = Color(0xFF8A7A70)
                        )
                    }
                    OutlinedButton(onClick = onDismiss) { Text("Baad me") }
                }
            }
        }
    }
}
