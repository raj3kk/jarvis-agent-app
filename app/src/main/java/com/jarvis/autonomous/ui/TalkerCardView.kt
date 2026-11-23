package com.jarvis.autonomous.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.autonomous.data.TalkerCard
import com.jarvis.autonomous.data.TalkerCardKind

/**
 * R3.10: renders a [TalkerCard] on the Command tab.
 *
 * PROGRESS — display only (the stop is the existing stop button).
 * APPROVAL — Approve / Reject tap the engine's browser-approval
 * answer (the U8 phone-side record: the tap IS the recorded
 * approval, no server route exists). DOC — display only.
 */
@Composable
fun TalkerCardView(
    card: TalkerCard,
    onApprove: () -> Unit = {},
    onReject: () -> Unit = {}
) {
    val accent = when (card.kind) {
        TalkerCardKind.PROGRESS -> Color(0xFFB85C38)
        TalkerCardKind.APPROVAL -> Color(0xFF2E7D32)
        TalkerCardKind.DOC -> Color(0xFF5B4A8A)
    }
    val label = when (card.kind) {
        TalkerCardKind.PROGRESS -> "Kaam chal raha hai"
        TalkerCardKind.APPROVAL -> "Aapki marzi chahiye"
        TalkerCardKind.DOC -> "Yaad rakhe docs se"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1DC)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "$label — ${card.title}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            if (card.body.isNotBlank()) {
                Text(text = card.body, fontSize = 13.sp, color = Color(0xFF5C4A3A))
            }
            if (card.sourceLabel.isNotBlank()) {
                Text(text = card.sourceLabel, fontSize = 12.sp, color = Color(0xFF8A7A70))
            }
            if (card.kind == TalkerCardKind.APPROVAL) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onApprove) { Text("Khol do") }
                    OutlinedButton(onClick = onReject) { Text("Nahi") }
                }
            }
        }
    }
}
