package com.jarvis.autonomous.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Licence tab (Jarvis 1.4.0, S6). Built ONLY as a screen — the
 * coordinator wires it into MainActivity's tab bar; this file does
 * not touch MainActivity, version numbers, or any other app file.
 *
 * GRACE RULE (concrete, 2026-11-02 amendment — replaces any vague
 * grace wording, and NO day-count deadline is invented here):
 *   This screen is WARN-ONLY until BOTH conditions hold:
 *     (1) the licence-server signup bug is verified fixed, AND
 *     (2) the user (owner) orders enforcement.
 *   Until both hold, a missing/inactive licence shows only the
 *   banner below and the app keeps working fully. NOTHING on this
 *   screen locks, gates, or disables any existing feature.
 *
 * Server (CORRECTED 2026-11-02 — see note below):
 * https://jarvis-server-webbuilder1.vercel.app
 *   1. Open the server link -> Signup / Login
 *   2. Dashboard -> Generate licence -> Copy the key
 *   3. Paste the key here -> Activate
 *
 * Device binding: device_id is ANDROID_ID
 * (Settings.Secure.ANDROID_ID), the same id sent on every verify.
 *
 * Release — HONEST DEVIATION, documented (no session store in app):
 *   The app holds no Supabase session, so it cannot normally send
 *   the Bearer token the original /api/licence/release required.
 *   This screen therefore offers BOTH paths, in order:
 *     a. If the optional session-token field is filled, Release sends
 *        it as `Authorization: Bearer <token>` (original server path).
 *     b. Otherwise Release sends { key, device_id } — the server
 *        (release route, updated 2026-11-02) clears the binding ONLY
 *        when the key is active AND bound to exactly this device.
 *   The dashboard's own Release Device button (server website, where
 *   the user IS logged in) also still works. No path releases a
 *   licence bound to a different device.
 *
 * Global-learning opt-out:
 *   SharedPreferences file "jarvis_agent_prefs" (the app's main prefs
 *   file), key "global_learning_opt_out", default false.
 *   false (default) = uploads allowed ONLY as anonymized templates
 *     (enum action + target-kind steps, no user text — see server
 *     lib/patterns.ts). true = this device uploads nothing at all.
 *   The coordinator / upload code MUST read this key before any
 *   pattern upload; this screen only stores the choice.
 */
object LicencePrefs {
    const val PREFS_NAME = "jarvis_agent_prefs"
    const val KEY_OPT_OUT = "global_learning_opt_out"
    const val KEY_LICENCE = "licence_key"
    const val KEY_ACTIVATED = "licence_activated"
}

const val LICENCE_SERVER_URL = "https://jarvis-server-webbuilder1.vercel.app"

private val licenceHttp = OkHttpClient()

private fun deviceId(context: Context): String {
    return Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    ) ?: ""
}

private suspend fun postJson(
    url: String,
    json: JSONObject,
    bearerToken: String? = null
): Pair<Int, String> = withContext(Dispatchers.IO) {
    val body = json.toString().toRequestBody("application/json".toMediaType())
    val builder = Request.Builder().url(url).post(body)
    if (!bearerToken.isNullOrBlank()) {
        builder.header("Authorization", "Bearer ${bearerToken.trim()}")
    }
    runCatching {
        licenceHttp.newCall(builder.build()).execute().use { resp ->
            resp.code to (resp.body?.string() ?: "")
        }
    }.getOrElse { -1 to (it.message ?: "network error") }
}

@Composable
fun LicenceScreen() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(
            LicencePrefs.PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }
    val scope = rememberCoroutineScope()

    var licenceKey by remember {
        mutableStateOf(prefs.getString(LicencePrefs.KEY_LICENCE, "") ?: "")
    }
    var sessionToken by remember { mutableStateOf("") }
    var activated by remember {
        mutableStateOf(prefs.getBoolean(LicencePrefs.KEY_ACTIVATED, false))
    }
    var optOut by remember {
        mutableStateOf(prefs.getBoolean(LicencePrefs.KEY_OPT_OUT, false))
    }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Licence",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB85C38)
        )

        // GRACE BANNER — warn-only, never a gate. Exact rule text:
        // warn-only until BOTH (signup bug verified fixed) AND
        // (owner orders enforcement). No deadline is stated because
        // none exists.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (activated) Color(0xFFD9EAD3) else Color(0xFFFFF1DC)
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = if (activated) {
                        "Licence activated ✅ — this device is bound."
                    } else {
                        "Licence not yet activated — app continues in grace mode"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3B2F2A)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Grace mode is warn-only until BOTH conditions hold: " +
                        "(1) the licence-server signup bug is verified fixed, AND " +
                        "(2) you (the owner) order enforcement. " +
                        "No deadline, no lockout before then — everything keeps working.",
                    fontSize = 12.sp,
                    color = Color(0xFF3B2F2A)
                )
            }
        }

        // Server link + steps
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Get your free licence:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = LICENCE_SERVER_URL, fontSize = 13.sp, color = Color(0xFFB85C38))
                Text(
                    text = "1. Open the server link above (in your browser).\n" +
                        "2. Signup / Login on the server.\n" +
                        "3. Dashboard -> Generate licence -> Copy the key.\n" +
                        "4. Paste the key below and tap Activate.",
                    fontSize = 13.sp
                )
                Text(
                    text = "One licence = one device at a time. " +
                        "Moving to a new phone? Tap Release below (or Release Device " +
                        "on the server dashboard), then Activate on the new phone.",
                    fontSize = 12.sp,
                    color = Color(0xFF8A7A70)
                )
            }
        }

        // Activate
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = licenceKey,
                    onValueChange = { licenceKey = it },
                    label = { Text("Licence key (JARVIS-XXXX-XXXX-XXXX-XXXX)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    enabled = !busy,
                    onClick = {
                        val key = licenceKey.trim()
                        if (key.isBlank()) {
                            status = "Pehle licence key paste karo."
                            return@Button
                        }
                        val id = deviceId(context)
                        if (id.isBlank()) {
                            status = "Device id (ANDROID_ID) nahi mila — dobara try karo."
                            return@Button
                        }
                        busy = true
                        status = "Activating…"
                        scope.launch {
                            val json = JSONObject().apply {
                                put("key", key)
                                put("device_id", id)
                            }
                            val (code, bodyText) = postJson(
                                "$LICENCE_SERVER_URL/api/licence/verify",
                                json
                            )
                            busy = false
                            if (code == 200) {
                                prefs.edit()
                                    .putString(LicencePrefs.KEY_LICENCE, key)
                                    .putBoolean(LicencePrefs.KEY_ACTIVATED, true)
                                    .apply()
                                activated = true
                                status = "Activated ✅ — licence is bound to this device."
                            } else if (code == 409) {
                                status = "Ye licence kisi aur device se bound hai. " +
                                    "Pehle us device se Release karo, ya dashboard se Release Device karo."
                            } else if (code == 404) {
                                status = "Invalid licence — key dobara check karo."
                            } else if (code == -1) {
                                status = "Network error: $bodyText (grace mode me app chalta rahega)."
                            } else {
                                status = "Activate fail (HTTP $code): $bodyText"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Please wait…" else "Activate")
                }
            }
        }

        // Release (session token optional; key+device fallback)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Release this device",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = sessionToken,
                    onValueChange = { sessionToken = it },
                    label = { Text("Server session token (optional — khali chhodo to key se release hoga)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    text = "Khali chhodne par release licence key + is device se hota hai " +
                        "(server sirf tab clear karta hai jab key isi device se bound ho). " +
                        "Token bharne par server ke original Bearer-token path se release hota hai.",
                    fontSize = 12.sp,
                    color = Color(0xFF8A7A70)
                )
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        val key = licenceKey.trim()
                        if (key.isBlank()) {
                            status = "Release ke liye licence key chahiye."
                            return@OutlinedButton
                        }
                        val id = deviceId(context)
                        if (id.isBlank()) {
                            status = "Device id (ANDROID_ID) nahi mila — dobara try karo."
                            return@OutlinedButton
                        }
                        busy = true
                        status = "Releasing…"
                        scope.launch {
                            val json = JSONObject().apply {
                                put("key", key)
                                put("device_id", id)
                            }
                            val token = sessionToken.trim().ifBlank { null }
                            val (code, bodyText) = postJson(
                                "$LICENCE_SERVER_URL/api/licence/release",
                                json,
                                bearerToken = token
                            )
                            busy = false
                            if (code == 200) {
                                prefs.edit()
                                    .putBoolean(LicencePrefs.KEY_ACTIVATED, false)
                                    .apply()
                                activated = false
                                status = "Released ✅ — ye licence ab naye device par activate ho sakti hai."
                            } else if (code == 401) {
                                status = "Release fail: session token ya key/device match nahi hua."
                            } else if (code == 409) {
                                status = "Ye licence is device se bound nahi hai — release ki zaroorat nahi."
                            } else if (code == 404) {
                                status = "Invalid licence — key dobara check karo."
                            } else if (code == -1) {
                                status = "Network error: $bodyText"
                            } else {
                                status = "Release fail (HTTP $code): $bodyText"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Release")
                }
            }
        }

        // Global-learning opt-out toggle
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Opt out of global learning uploads",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = optOut,
                        onCheckedChange = { value ->
                            optOut = value
                            prefs.edit()
                                .putBoolean(LicencePrefs.KEY_OPT_OUT, value)
                                .apply()
                            status = if (value) {
                                "Global-learning uploads OFF — is device se kuch upload nahi hoga."
                            } else {
                                "Global-learning uploads ON — sirf anonymized templates upload honge, koi user text nahi."
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "OFF (default) = uploads allowed ONLY as anonymized templates " +
                        "(sirf action + target-kind steps — koi baat-cheet, message, contact, " +
                        "URL ya file content kabhi upload nahi hota). " +
                        "ON = is device se koi pattern upload nahi hota.",
                    fontSize = 12.sp,
                    color = Color(0xFF8A7A70)
                )
            }
        }

        if (status.isNotBlank()) {
            Text(status, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
