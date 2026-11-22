package com.jarvis.autonomous.browser.gecko

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoView
import com.jarvis.autonomous.ui.BrowserHostState
import com.jarvis.autonomous.ui.GOOGLE_HOME_URL
import com.jarvis.autonomous.ui.browserTargetForInput
import com.jarvis.autonomous.ui.normalizeBrowserUrl

/**
 * Gecko browser tab (1.13.0) — the REAL browser in Jarvis.
 *
 * Tabs, address bar, back/forward/reload, share/external (tap-only),
 * page extraction, captcha detection badge, cookie count, downloads,
 * screenshots — all on the GeckoView engine + the jarvis-bridge
 * extension. The WebView engine stays available via the engine toggle
 * at the top (fallback, never deleted).
 *
 * BRIDGE STATUS IS VISIBLE BY DESIGN: the diagnostics line under the
 * title always says whether the bridge is LIVE (bg-alive received,
 * port connected) or DOWN. If it is DOWN, pages still load, but
 * extraction / captcha / cookies do not — the screen says so plainly
 * instead of failing silently, and the WebView fallback is one tap
 * away. The user's "dekh lunga" check reads this line.
 *
 * The agent's background browse does NOT use this screen — it stays on
 * JarvisBackgroundBrowser (WebView) in 1.13.0 (D-2). This screen is
 * the visible browser only.
 */
@Composable
fun GeckoBrowserScreen(
    browserHost: BrowserHostState? = null,
    onSwitchEngine: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- ALL state declarations FIRST (kotlin_prepush_checks: every
    // callback below only captures variables declared above).
    val host = browserHost ?: remember { BrowserHostState() }
    val hostUrl = host.foregroundUrl
    val status by GeckoBrowserManager.statusFlow.collectAsState()
    val tabs by GeckoBrowserManager.tabsFlow.collectAsState()
    val selectedTab by GeckoBrowserManager.selectedTabFlow.collectAsState()
    val downloads by GeckoBrowserManager.downloadsFlow.collectAsState()
    var urlInput by remember { mutableStateOf(hostUrl ?: "") }
    var currentUrl by remember { mutableStateOf(hostUrl ?: "") }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var extractPreview by remember { mutableStateOf<GeckoExtract?>(null) }
    var cookieCount by remember { mutableIntStateOf(-1) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    // Plain holders, not state: the attached view/session are read by
    // buttons without needing recomposition.
    val geckoViewHolder = remember { arrayOfNulls<GeckoView>(1) }
    val attachedSessionHolder = remember { arrayOfNulls<org.mozilla.geckoview.GeckoSession>(1) }

    // --- Helpers (after the state they mutate).
    fun navigateTo(raw: String) {
        val normalized = browserTargetForInput(raw)
        if (normalized.isEmpty()) {
            statusMessage = "Ye URL nahi khol sakta — sirf http/https pages allowed hain."
            return
        }
        val tab = selectedTab ?: return
        urlInput = normalized
        currentUrl = normalized
        errorMessage = ""
        statusMessage = ""
        host.reportDisplayedUrl(normalized)
        scope.launch { GeckoBrowserManager.loadUrl(tab.id, normalized) }
    }

    fun openNewTab() {
        scope.launch {
            val tab = GeckoBrowserManager.newTab() ?: return@launch
            GeckoBrowserManager.loadUrl(tab.id, GOOGLE_HOME_URL)
        }
    }

    fun readPage() {
        val tab = selectedTab ?: return
        scope.launch {
            val extract = GeckoBrowserManager.requestExtract(tab.id)
            if (extract == null) {
                statusMessage = "Page padh nahi paya — bridge DOWN ho sakta hai (upar status dekho)."
            } else {
                statusMessage = ""
            }
        }
    }

    fun loadCookies() {
        val tab = selectedTab ?: return
        scope.launch {
            val cookies = GeckoBrowserManager.getCookies(tab.id)
            cookieCount = cookies.size
            statusMessage = if (cookies.isEmpty()) {
                "Is site par koi cookie nahi mili (ya bridge DOWN hai)."
            } else {
                "Is site par ${cookies.size} cookies mili."
            }
        }
    }

    fun takeScreenshot() {
        val view = geckoViewHolder[0] ?: return
        scope.launch {
            val file = GeckoBrowserManager.captureScreenshot(view)
            statusMessage = if (file != null) {
                "Screenshot save hua: ${file.name}"
            } else {
                "Screenshot nahi bana — dobara try karo."
            }
        }
    }

    // --- Engine start (lazy, once): runtime + extension install.
    LaunchedEffect(Unit) {
        val ready = GeckoBrowserManager.ensureReady(context.applicationContext)
        if (ready && GeckoBrowserManager.tabsFlow.value.isEmpty()) {
            val tab = GeckoBrowserManager.newTab()
            if (tab != null) {
                val target = hostUrl?.takeIf { it.isNotBlank() } ?: GOOGLE_HOME_URL
                GeckoBrowserManager.loadUrl(tab.id, target)
            }
        }
    }

    // --- Engine-driven URL changes (foreground mode b): the engine's
    // showLoadedPage / loadUrl lands here, like the WebView screen.
    LaunchedEffect(hostUrl) {
        val target = hostUrl ?: return@LaunchedEffect
        val tab = selectedTab ?: return@LaunchedEffect
        if (target != currentUrl) {
            urlInput = target
            currentUrl = target
            GeckoBrowserManager.loadUrl(tab.id, target)
        }
    }

    // --- Per-tab flows: the selected tab's state drives this screen.
    // Each LaunchedEffect restarts when the selected tab changes.
    LaunchedEffect(selectedTab) {
        val tab = selectedTab ?: return@LaunchedEffect
        launch { tab.url.collect { currentUrl = it; urlInput = it } }
        launch { tab.title.collect { if (it.isNotBlank()) pageTitle = it } }
        launch { tab.progress.collect { progress = it } }
        launch { tab.error.collect { if (it.isNotBlank()) errorMessage = it } }
        launch { tab.lastExtract.collect { extractPreview = it } }
        launch { tab.canGoBack.collect { canGoBack = it } }
        launch { tab.canGoForward.collect { canGoForward = it } }
        // Auto-extract when a load FINISHES (true -> false transition
        // only — the first false emission is the empty tab, not a page).
        // Runs in scope so a slow bridge never blocks this collector.
        launch {
            var wasLoading = false
            tab.loading.collect { loading ->
                if (wasLoading && !loading && tab.url.value.isNotBlank()) {
                    scope.launch { GeckoBrowserManager.requestExtract(tab.id) }
                }
                wasLoading = loading
            }
        }
    }

    // --- Browser-tab visibility (engine's background-browse guard).
    DisposableEffect(host) {
        host.isBrowserTabVisible = true
        onDispose {
            host.isBrowserTabVisible = false
        }
    }

    val bridgeLine = bridgeStatusLine(status)
    val captcha = extractPreview?.captcha ?: "none"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFFF8ED))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Jarvis Browser — Gecko",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB85C38)
        )
        Text(
            text = "GeckoView engine — pages isi app ke andar khulti hain, Chrome me nahi.",
            fontSize = 12.sp,
            color = Color(0xFF8A7A70)
        )

        // Engine toggle (Gecko primary, WebView fallback — both stay).
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(enabled = false, onClick = {}) {
                Text("Gecko ✓", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { onSwitchEngine("webview") }) {
                Text("WebView fallback", fontSize = 12.sp)
            }
            Text(
                text = "GV ${GeckoBrowserManager.GV_VERSION.split(".").take(2).joinToString(".")} • Ext ${GeckoBrowserManager.EXTENSION_VERSION}",
                fontSize = 11.sp,
                color = Color(0xFF8A7A70),
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        // Bridge diagnostics — ALWAYS visible (the user's check line).
        Text(
            text = bridgeLine.first,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = bridgeLine.second,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFF3E0))
                .padding(8.dp)
        )

        // Tab strip (in-memory tabs, v1 — no persistence).
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            tabs.forEach { tab ->
                val tabTitle = tab.title.value.ifBlank { tab.url.value.ifBlank { "Naya tab" } }
                OutlinedButton(
                    onClick = { GeckoBrowserManager.selectTab(tab.id) }
                ) {
                    Text(
                        text = (if (tab.id == selectedTab?.id) "● " else "") +
                            tabTitle.take(18),
                        fontSize = 12.sp
                    )
                }
                if (tabs.size > 1) {
                    OutlinedButton(
                        onClick = { GeckoBrowserManager.closeTab(tab.id) }
                    ) { Text("✕", fontSize = 12.sp) }
                }
            }
            OutlinedButton(onClick = { openNewTab() }) {
                Text("+ Tab", fontSize = 12.sp)
            }
        }

        // URL bar: editable, bare domains get https:// by default.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("Search ya URL — Google par search hoga") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { navigateTo(urlInput) })
            )
            Button(onClick = { navigateTo(urlInput) }) {
                Text("Go")
            }
        }

        // Back / forward / reload / share / external (tap-only).
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                enabled = canGoBack,
                onClick = { selectedTab?.let { GeckoBrowserManager.goBack(it.id) } }
            ) { Text("Back", fontSize = 12.sp) }
            OutlinedButton(
                enabled = canGoForward,
                onClick = { selectedTab?.let { GeckoBrowserManager.goForward(it.id) } }
            ) { Text("Forward", fontSize = 12.sp) }
            OutlinedButton(
                onClick = { selectedTab?.let { GeckoBrowserManager.reload(it.id) } }
            ) { Text("Reload", fontSize = 12.sp) }
            OutlinedButton(
                onClick = {
                    val target = normalizeBrowserUrl(currentUrl)
                    if (target.isEmpty()) {
                        statusMessage = "Share karne ke liye pehle koi page kholo."
                    } else {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, target)
                        }
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(send, "Page share karo")
                            )
                        }.onFailure {
                            statusMessage = "Share nahi khul paya."
                        }
                    }
                }
            ) { Text("Share", fontSize = 12.sp) }
            OutlinedButton(
                onClick = {
                    val target = normalizeBrowserUrl(currentUrl)
                    if (target.isEmpty()) {
                        statusMessage = "Bahari browser me kholne ke liye pehle koi page kholo."
                    } else {
                        val view = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(view) }
                            .onFailure {
                                statusMessage = "Bahari browser nahi khul paya."
                            }
                    }
                }
            ) { Text("Bahari browser", fontSize = 12.sp) }
        }

        // Bridge actions: extract / screenshot / cookies.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                enabled = status.bgAlive,
                onClick = { readPage() }
            ) { Text("Page padho", fontSize = 12.sp) }
            OutlinedButton(onClick = { takeScreenshot() }) {
                Text("Screenshot", fontSize = 12.sp)
            }
            OutlinedButton(
                enabled = status.bgAlive,
                onClick = { loadCookies() }
            ) { Text("Cookies", fontSize = 12.sp) }
            if (cookieCount >= 0) {
                Text(
                    text = "Cookies: $cookieCount",
                    fontSize = 12.sp,
                    color = Color(0xFF8A7A70),
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }

        // Captcha badge (detection only — solving stays WebView-only).
        if (captcha != "none") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Captcha mila: ${GeckoBridgeUtils.captchaLabel(captcha)} — Gecko par sirf detect hota hai, solve WebView me hoga.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF8D4A2B),
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFFFFE0B2))
                        .padding(8.dp)
                )
                OutlinedButton(
                    onClick = {
                        val target = normalizeBrowserUrl(currentUrl)
                        if (target.isNotEmpty()) host.loadUrl(target)
                        onSwitchEngine("webview")
                    }
                ) { Text("WebView me solve karo", fontSize = 12.sp) }
            }
        }

        if (pageTitle.isNotBlank()) {
            Text(
                text = pageTitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF3B2F2A)
            )
        }

        if (progress < 100 && currentUrl.isNotBlank()) {
            LinearProgressIndicator(
                progress = progress / 100f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (errorMessage.isNotBlank()) {
            Text(errorMessage, fontSize = 13.sp, color = Color(0xFFB3261E))
        }
        if (statusMessage.isNotBlank()) {
            Text(
                statusMessage,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2E7D5B)
            )
        }

        // Extraction preview (auto-extract on load + "Page padho").
        val preview = extractPreview
        if (preview != null && preview.text.isNotBlank()) {
            Text(
                text = "Page se padha gaya:\n" + preview.text.take(500),
                fontSize = 13.sp,
                color = Color(0xFF3B2F2A),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFF3E0))
                    .padding(8.dp)
            )
        }

        // Downloads saved by this engine (filesDir/gecko-downloads/).
        if (downloads.isNotEmpty()) {
            Text(
                text = "Downloads:",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF3B2F2A)
            )
            downloads.take(5).forEach { d ->
                Text(
                    text = "${d.fileName} — ${d.bytes} bytes",
                    fontSize = 12.sp,
                    color = Color(0xFF8A7A70)
                )
            }
        }

        if (currentUrl.isBlank() && selectedTab == null) {
            Text(
                text = "Gecko engine start ho raha hai…",
                fontSize = 13.sp,
                color = Color(0xFF3B2F2A)
            )
        }

        // The GeckoView: shows the selected tab's session. The view is
        // created once per composition; the update block re-attaches
        // the session when the selected tab changes (release first —
        // spike lesson: release views BEFORE closing sessions).
        val tab = selectedTab
        if (tab != null) {
            AndroidView(
                factory = { ctx ->
                    val view = GeckoView(ctx)
                    geckoViewHolder[0] = view
                    view
                },
                update = { view ->
                    val attached = attachedSessionHolder[0]
                    if (attached !== tab.session) {
                        runCatching { view.releaseSession() }
                        view.setSession(tab.session)
                        attachedSessionHolder[0] = tab.session
                    }
                },
                onRelease = { view ->
                    runCatching { view.releaseSession() }
                    attachedSessionHolder[0] = null
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.White)
            )
        }
    }
}

/** The diagnostics line: (text, color). Always honest, never silent. */
private fun bridgeStatusLine(status: GeckoBrowserManager.BridgeStatus): Pair<String, Color> {
    return when (status.engine) {
        GeckoBrowserManager.EngineState.FAILED ->
            "Gecko engine start nahi hua: ${status.lastError}" to Color(0xFFB3261E)
        GeckoBrowserManager.EngineState.STARTING,
        GeckoBrowserManager.EngineState.NOT_STARTED ->
            "Gecko engine start ho raha hai…" to Color(0xFF8A7A70)
        GeckoBrowserManager.EngineState.READY -> {
            if (status.bgAlive) {
                "Bridge LIVE ✓ — bg-alive mila, port: ${status.portKind}. Extraction, captcha check aur cookies kaam karenge." to Color(0xFF2E7D5B)
            } else {
                "Bridge DOWN — bg-alive nahi aaya. Pages load honge, par extraction/captcha/cookies nahi. WebView fallback upar se chuno." to Color(0xFF8D4A2B)
            }
        }
    }
}
