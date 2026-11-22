package com.jarvis.autonomous.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.autonomous.data.GoogleClient
import com.jarvis.autonomous.service.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Google — Gmail & Drive consent card (Track E #27, item #27).
 *
 * Lives in AiBrainSettingsScreen, directly under the "Agent Mode —
 * Connected accounts" card (one call-site line, like PairingCard).
 * Shows connected / not-connected state from GET /api/google/status,
 * opens the Google consent URL in the browser on Connect, and
 * disconnects via POST /api/google/revoke.
 *
 * No tokens on the phone (the hub holds them encrypted in D1), no
 * client secret anywhere in the app, no WebView, no new Android
 * permission — the browser opens via the existing
 * openUrlOrDeepLink (ACTION_VIEW fallback when the accessibility
 * service is not running). The hub must have GOOGLE_OAUTH_CLIENT_ID
 * + GOOGLE_OAUTH_CLIENT_SECRET set, otherwise status reports
 * "configure nahi hai" honestly instead of a fake Connect.
 */
@Composable
fun GoogleConnectCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf("Status load ho raha hai…") }
    var connected by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            when (val s = GoogleClient.getStatus(context)) {
                is GoogleClient.StatusResult.Ok -> {
                    connected = s.connected
                    configured = s.configured
                    email = s.email
                    statusText = when {
                        !s.configured -> "Hub par Google OAuth configure nahi hai — setup baaki hai, Connect abhi kaam nahi karega."
                        s.connected -> "Connected: ${s.email ?: "Google account"} — Gmail bhej/padh + Drive read chalu hai."
                        else -> "Not connected — Connect dabakar Google account jodo (Gmail read/send + Drive read, sirf wahi scopes)."
                    }
                }
                is GoogleClient.StatusResult.Err -> {
                    statusText = s.message
                }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3EC))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Google — Gmail & Drive", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                text = "Agent Mode ke liye Google connect karo — Gmail bhej/padh sakte ho, Drive files padh sakte ho. " +
                    "Tokens phone par nahi, hub par encrypted save hote hain. Nango nahi — seedha Google OAuth.",
                fontSize = 12.sp
            )
            Text(statusText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && configured && !connected,
                    onClick = {
                        busy = true
                        statusText = "Google consent khol raha hai…"
                        scope.launch(Dispatchers.IO) {
                            val url = GoogleClient.getStartUrl(context)
                            busy = false
                            if (url.isNullOrBlank()) {
                                statusText = "Consent URL nahi mila — pairing ya hub status check karo."
                            } else {
                                openGoogleConsentUrl(context, url)
                                statusText = "Browser me Google consent khula — approve karke wapas aao, phir Refresh dabao."
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
                ) {
                    Text("Connect Google", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Button(
                    enabled = !busy && connected,
                    onClick = {
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            val ok = GoogleClient.revoke(context)
                            busy = false
                            if (ok) {
                                connected = false
                                email = null
                                statusText = "Google disconnect ho gaya."
                            } else {
                                statusText = "Disconnect fail hua — dobara koshish karo."
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
                ) {
                    Text("Disconnect", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Button(
                enabled = !busy,
                onClick = { refresh() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
            ) {
                Text("Refresh status", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Browser open: accessibility service first (existing pattern), ACTION_VIEW fallback. */
private fun openGoogleConsentUrl(context: Context, url: String) {
    val opened = runCatching {
        JarvisAccessibilityService.instance?.openUrlOrDeepLink(url) ?: false
    }.getOrDefault(false)
    if (!opened) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}
