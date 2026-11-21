package com.jarvis.autonomous.ui

import android.content.Context
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
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.PairingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Control-plane pairing card (Phase 2, stage 2b-1) — Ed25519 keypair +
 * hub register. Shown inside AiBrainSettingsScreen (one call site).
 *
 * Flow: "Pair karo" -> hub /api/pairing/init (licence-verified) ->
 * 6-digit code (5-min) -> "Confirm pairing" -> hub
 * /api/pairing/register (one-time token + public key) -> Paired.
 * No QR scan (camera = new permission — NOT in Phase 2b), no token
 * on screen, no private key anywhere in the UI. Until B-A the init
 * call answers 503 and the card shows the honest setup-pending line.
 *
 * Compose rule (1.5.3 lesson): LocalContext.current is captured ONCE
 * at the composable level (cardContext) and never inside remember { }
 * or a click lambda. All blocking work runs on Dispatchers.IO; state
 * is assigned back on the Main thread.
 */
@Composable
fun PairingCard(prefs: JarvisPreferences) {
    val cardContext = LocalContext.current
    val scope = rememberCoroutineScope()

    var paired by remember { mutableStateOf(prefs.pairingPaired) }
    var keyStoreLabel by remember { mutableStateOf("") }
    var pubPrefix by remember { mutableStateOf("") }
    var selfTest by remember { mutableStateOf("") }
    var hasLicence by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var expiresAt by remember { mutableStateOf(0L) }
    var remainingSec by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Key info + licence presence + any surviving pending token, once.
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val kind = runCatching { PairingManager.keyStoreKind(cardContext) }.getOrNull()
            val pub = runCatching { PairingManager.publicKeyB64(cardContext) }.getOrNull()
            val test = runCatching { PairingManager.selfTestSign(cardContext) }.getOrDefault(false)
            val lic = cardContext.getSharedPreferences(
                LicencePrefs.PREFS_NAME, Context.MODE_PRIVATE
            ).getString(LicencePrefs.KEY_LICENCE, "") ?: ""
            val pend = runCatching { PairingManager.pendingToken(cardContext) }.getOrNull()
            LoadedPairingInfo(kind, pub, test, lic.isNotBlank(), pend)
        }
        keyStoreLabel = when (loaded.kind) {
            PairingManager.KeyStoreKind.ANDROID_KEYSTORE -> "Android Keystore"
            PairingManager.KeyStoreKind.ENCRYPTED_PREFS -> "Encrypted prefs (API < 31)"
            null -> "Key load nahi hui"
        }
        pubPrefix = loaded.pub?.take(8) ?: ""
        selfTest = if (loaded.test) "Sign self-test: PASS" else "Sign self-test: FAIL"
        hasLicence = loaded.hasLicence
        // Pending token survived an app kill — offer Confirm again
        // while its 5-min window is still open.
        val pend = loaded.pending
        if (pend != null && pend.second > System.currentTimeMillis()) {
            // Code is derived server-side from the token; the app
            // cannot recompute it without the token's hash — but the
            // token is stored, so re-derive the code locally: it is
            // SHA-256(token) first-4-bytes uint32 mod 1,000,000.
            // (Same formula as the hub — see pairing.ts.)
            code = deriveSixDigitLocal(pend.first)
            expiresAt = pend.second
            message = "Pehli pairing adhoori reh gayi thi — code abhi bhi valid hai."
        }
    }

    // 1-second countdown while a code is shown.
    LaunchedEffect(code, expiresAt) {
        if (code.isEmpty() || expiresAt <= 0L) {
            remainingSec = 0L
            return@LaunchedEffect
        }
        while (true) {
            val left = (expiresAt - System.currentTimeMillis()) / 1000
            remainingSec = left.coerceAtLeast(0L)
            if (left <= 0L) {
                code = ""
                message = "Pairing code expire ho gaya — dobara Pair karo."
                break
            }
            delay(1000)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1DC))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Control-plane pairing", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                text = "Jarvis ko apne control plane (omnijarvis-hub) se jodne ke liye. " +
                    "Device ki pehchan Ed25519 key se hoti hai — private key phone se bahar nahi jati.",
                fontSize = 12.sp
            )
            Text(
                text = "Paired: " + if (paired) "YES" +
                    (if (prefs.pairingDeviceId.isNotBlank()) " (…${prefs.pairingDeviceId.takeLast(6)})" else "") else "NO",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = "Key store: $keyStoreLabel", fontSize = 12.sp)
            if (pubPrefix.isNotEmpty()) {
                Text(text = "Public key: $pubPrefix…", fontSize = 12.sp)
            }
            if (selfTest.isNotEmpty()) {
                Text(text = selfTest, fontSize = 12.sp)
            }
            if (code.isNotEmpty()) {
                Text(
                    text = code,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp
                )
                Text(
                    text = "$remainingSec second me expire hoga — Confirm dabao.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && !paired && code.isEmpty(),
                    onClick = {
                        scope.launch {
                            busy = true
                            message = ""
                            val deviceId = PairingManager.deviceId(cardContext)
                            val lic = cardContext.getSharedPreferences(
                                LicencePrefs.PREFS_NAME, Context.MODE_PRIVATE
                            ).getString(LicencePrefs.KEY_LICENCE, "") ?: ""
                            val result = withContext(Dispatchers.IO) {
                                PairingManager.initPairing(cardContext, deviceId, lic)
                            }
                            busy = false
                            when (result) {
                                is PairingManager.InitResult.Ok -> {
                                    code = result.code
                                    expiresAt = result.expiresAtMs
                                    message = "Code mila — 5 minute me Confirm karo."
                                }
                                is PairingManager.InitResult.Err ->
                                    message = pairingErrorText(result.kind)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
                ) {
                    Text("Pair karo", color = Color.White, fontWeight = FontWeight.Bold)
                }
                if (code.isNotEmpty()) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                message = ""
                                val deviceId = PairingManager.deviceId(cardContext)
                                val result = withContext(Dispatchers.IO) {
                                    PairingManager.registerPairing(cardContext, deviceId)
                                }
                                busy = false
                                when (result) {
                                    is PairingManager.RegisterResult.Ok -> {
                                        prefs.pairingPaired = true
                                        prefs.pairingDeviceId = deviceId
                                        prefs.pairingPairedAtMs = System.currentTimeMillis()
                                        paired = true
                                        code = ""
                                        message = "Paired ho gaya ✅ — control plane se jud gaya."
                                    }
                                    is PairingManager.RegisterResult.Err ->
                                        message = pairingErrorText(result.kind)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
                    ) {
                        Text("Confirm pairing", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                if (paired) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            prefs.pairingPaired = false
                            prefs.pairingDeviceId = ""
                            prefs.pairingPairedAtMs = 0L
                            paired = false
                            message = "Unpaired (local). Server par device abhi hataya nahi ja sakta — Phase 2 backlog."
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
                    ) {
                        Text("Unpair karo", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (!hasLicence) {
                Text(
                    text = "Pehle Licence tab me key activate karo — bina active licence ke pairing nahi hogi.",
                    fontSize = 11.sp,
                    color = Color(0xFF8A7A70)
                )
            }
            if (message.isNotEmpty()) {
                Text(message, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private data class LoadedPairingInfo(
    val kind: PairingManager.KeyStoreKind?,
    val pub: String?,
    val test: Boolean,
    val hasLicence: Boolean,
    val pending: Pair<String, Long>?
)

private fun pairingErrorText(kind: PairingManager.PairingError): String {
    return when (kind) {
        PairingManager.PairingError.CONFIG_MISSING ->
            "Control plane ka licence check abhi setup me hai (B-A) — baad me try karo."
        PairingManager.PairingError.LICENCE_INVALID ->
            "Licence active nahi hai — Licence tab check karo."
        PairingManager.PairingError.RATE_LIMITED ->
            "Bahut jaldi try kar rahe ho — 1 minute ruk kar dobara try karo."
        PairingManager.PairingError.TOKEN_INVALID ->
            "Pairing code expire ho gaya — dobara Pair karo."
        PairingManager.PairingError.NO_PENDING ->
            "Koi pending pairing nahi hai — pehle Pair karo."
        PairingManager.PairingError.NO_LICENCE ->
            "Pehle Licence tab me key activate karo."
        PairingManager.PairingError.NETWORK ->
            "Network fail — connection check karke dobara try karo."
        PairingManager.PairingError.KEY_ERROR ->
            "Device key load nahi hui — app dobara kholo."
        PairingManager.PairingError.BAD_REQUEST,
        PairingManager.PairingError.SERVER ->
            "Server error — dobara try karo."
    }
}

/** Local 6-digit derivation (hub pairing.ts ke barabar): first 4 bytes
 *  of SHA-256(token) as big-endian uint32, mod 1,000,000, zero-padded.
 *  Sirf pending-token restore ke liye — token kabhi screen par nahi
 *  aata, sirf iska code dikhta hai. */
private fun deriveSixDigitLocal(token: String): String {
    return runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        val num = ((digest[0].toInt() and 0xFF) shl 24) or
            ((digest[1].toInt() and 0xFF) shl 16) or
            ((digest[2].toInt() and 0xFF) shl 8) or
            (digest[3].toInt() and 0xFF)
        String.format("%06d", (num.toLong() and 0xFFFFFFFFL) % 1_000_000L)
    }.getOrDefault("------")
}
