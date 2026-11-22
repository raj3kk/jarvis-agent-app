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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Captcha auto-solve permission screen (DESIGN §1.2–§1.3).
 *
 * Shown ONLY from Settings → AI Brain, when the user taps the
 * "Captcha auto-solve" row ON — never in-context mid-task (§1.2:
 * in-context prompts train the user to tap through during a task).
 * Tapping OFF in Settings revokes instantly, no confirm needed.
 *
 * Wording below is the EXACT §1.3 Hinglish text, including the
 * one-line risk line — copied verbatim, not paraphrased.
 */
@Composable
fun CaptchaAutoSolvePermissionCard(
    onAllow: () -> Unit,
    onKeepHuman: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3D6))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Captcha auto-solve chalu karein?",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFF7A5A00)
            )
            Text(
                text = "Jab Jarvis ke browser task me captcha aayega, Jarvis pehle khud solve " +
                    "karne ki koshish karega — aapki Gemini/Groq key se (jo key aapne AI Brain " +
                    "me jodi hai). Sirf usi session ka captcha image bheja jayega, aur kuch " +
                    "nahi. Agar Jarvis solve na kar paye, to wahi purana human-assist card " +
                    "khulega — aap khud solve karenge.",
                fontSize = 13.sp,
                color = Color(0xFF3B2F2A)
            )
            Text(
                text = "⚠️ hCaptcha aur Google reCAPTCHA ki terms automated solving mana karti " +
                    "hain — agar block ya ban laga, to wo aapke apne account par lagega; ye " +
                    "risk aap le rahe hain.",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8A2F2F)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAllow,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
                ) { Text("Samajh gaya — hamesha allow karo", color = Color.White, fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onKeepHuman) { Text("Nahi — human-assist hi rakho") }
            }
            Text(
                text = "Kabhi bhi band kar sakte hain: Settings → AI Brain → Captcha auto-solve OFF. " +
                    "Audio captcha kabhi auto-solve nahi hoga.",
                fontSize = 12.sp,
                color = Color(0xFF8A7A70)
            )
        }
    }
}
