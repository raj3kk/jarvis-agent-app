package com.jarvis.autonomous.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvis.autonomous.agent.TerminalSession
import org.json.JSONObject

/**
 * R1 (1.16.0-PRO) — Terminal tab (parity §1.2 A / §5 #2).
 *
 * One screen: a WebView running xterm.js 6.0.0 (MIT, bundled asset
 * — never fetched) + a Kotlin bridge to TerminalSession
 * (ProcessBuilder("sh"), app storage only, no root, no git).
 * Session = stdin/stdout + in-memory history + scrollback (xterm).
 * Graceful when `sh` is missing: the start error is shown in plain
 * words, nothing crashes.
 *
 * The full shell is NOT here — it is the Main Executor shell
 * (GitHub Actions as computer, parity §1.2 B). This screen is the
 * on-phone limited shell: file ops inside app storage.
 */
@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    val session = remember { TerminalSession(context.filesDir) }
    var startError by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(session) {
        startError = session.start()
        onDispose { session.stop() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            text = "Terminal — phone shell",
            fontSize = 16.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = Color(0xFFB85C38)
        )
        Text(
            text = "App storage ke andar hi kaam hota hai (ls, cat, grep) — no root, no git, no compilers. Poora shell Main Executor (server) par hai.",
            fontSize = 12.sp,
            color = Color(0xFF8A7A70)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { webViewRef?.evaluateJavascript("window.clearTerminal()", null) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
            ) { Text("Clear", color = Color.White, fontSize = 12.sp) }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    session.stop()
                    startError = session.start()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
            ) { Text("Restart shell", color = Color.White, fontSize = 12.sp) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (startError != null) {
            Text(
                text = startError!!,
                fontSize = 13.sp,
                color = Color(0xFFB3261E)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    @SuppressLint("SetJavaScriptEnabled")
                    fun setup() {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = true
                        settings.domStorageEnabled = false
                    }
                    setup()
                    addJavascriptInterface(TerminalBridge(session), "JarvisTerminal")
                    session.onOutput = { chunk ->
                        post {
                            evaluateJavascript(
                                "window.writeOutput(${JSONObject.quote(chunk)})",
                                null
                            )
                        }
                    }
                    loadUrl("file:///android_asset/terminal/terminal.html")
                    webViewRef = this
                }
            },
            update = { webViewRef = it }
        )
    }
}

/** JS -> Kotlin bridge for the terminal page. */
class TerminalBridge(
    private val session: TerminalSession
) {
    @JavascriptInterface
    fun onReady() {
        // Page loaded; nothing to send — the shell prompt is
        // already printed by the page itself.
    }

    @JavascriptInterface
    fun onInput(line: String) {
        val error = session.send(line)
        if (error != null) {
            session.onOutput?.invoke("\r\n$error\r\n")
        }
    }

    @JavascriptInterface
    fun onInterrupt() {
        session.interrupt()
    }
}
