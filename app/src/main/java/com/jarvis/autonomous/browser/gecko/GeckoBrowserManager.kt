package com.jarvis.autonomous.browser.gecko

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.jarvis.autonomous.data.JarvisStateRepository
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse

/**
 * Gecko browser engine for Jarvis 1.13.0 — the REAL browser in the app.
 *
 * Ports the Phase 0 spike's bridge (fix #2, commit 6b426a4c) into the main
 * app, productionized: no test harness, no results JSON, no RSS snapshots.
 * The visible Browser tab is powered by this manager; the agent's
 * background browse STAYS on JarvisBackgroundBrowser (WebView) in this
 * release — the Gecko bridge is unproven on the phone, and moving the
 * agent to it risks a regression in the production-critical path (D-2).
 *
 * Bridge design (do NOT change without re-reading
 * expansion/browser/BRIDGE_ROOT_CAUSE_AUDIT.md): the BACKGROUND script
 * owns the single native port (Mozilla's documented pattern); content.js
 * talks only via runtime.sendMessage; cookies live in background.js;
 * the Kotlin side stores the session==null background port and prefers
 * it. The content-script connectNative design died silently twice and
 * must never come back (CI gate enforces: no "connectNative(" in
 * content.js).
 *
 * C1 mitigation: the extension manifest version EQUALS the app
 * versionName on every release (CI gate enforces it) and the extension
 * ID is fresh for this app, so the first 1.13.0 run installs with
 * ADDON_INSTALL and every later release upgrades with ADDON_UPGRADE +
 * updateReason — both paths build the background directly instead of
 * relying on the APP_STARTUP fallback that silently never started it
 * in the spike's persisted runs.
 *
 * Sessions are in-memory only in v1 (no tab persistence). The runtime
 * is created LAZILY on first Browser-tab visit — never at app start
 * (RAM + startup cost). All Gecko calls run on Main; file writes on IO.
 */
object GeckoBrowserManager {

    private const val TAG = "GeckoBrowser"

    // Pins (VERSION_PINS.md): GeckoView 160.0.20261116130455 arrives
    // transitively as geckoview-omni via browser-engine-gecko:160.0.1.
    const val GV_VERSION = "160.0.20261116130455"
    const val AC_VERSION = "160.0.1"

    // C1: must match app/src/main/assets/jarvis-bridge/manifest.json
    // "version" AND the app versionName. CI gate enforces all three.
    const val EXTENSION_VERSION = "1.13.0"

    private const val EXT_URI = "resource://android/assets/jarvis-bridge/"
    private const val EXT_ID = "jarvis-bridge@jarvis.local"
    private const val NATIVE_APP = "jarvis-bridge"

    private const val PAGE_TIMEOUT_MS = 30_000L
    private const val EXT_TIMEOUT_MS = 15_000L
    private const val PORT_WAIT_MS = 10_000L
    private const val BG_ALIVE_WAIT_MS = 15_000L

    enum class EngineState { NOT_STARTED, STARTING, READY, FAILED }

    /**
     * What the user sees in the browser screen's diagnostics line.
     * engine READY + bgAlive false = pages load, but the bridge is
     * down (no extraction / captcha / cookies) — the screen says so
     * plainly and offers the WebView fallback.
     */
    data class BridgeStatus(
        val engine: EngineState = EngineState.NOT_STARTED,
        val extensionInstalled: Boolean = false,
        val bgAlive: Boolean = false,
        val portKind: String = "none",
        val lastError: String = ""
    )

    data class GeckoDownload(
        val fileName: String,
        val path: String,
        val bytes: Long,
        val uri: String,
        val savedAtMs: Long
    )

    /**
     * One browser tab. The session is created on Main and lives until
     * closeTab. State is exposed as flows so the Compose screen can
     * observe it; the session itself is a stable reference.
     */
    class GeckoTab(val id: String, val session: GeckoSession) {
        val url = MutableStateFlow("")
        val title = MutableStateFlow("")
        val progress = MutableStateFlow(0)
        val loading = MutableStateFlow(false)
        val canGoBack = MutableStateFlow(false)
        val canGoForward = MutableStateFlow(false)
        val error = MutableStateFlow("")
        val lastExtract = MutableStateFlow<GeckoExtract?>(null)
        var port: WebExtension.Port? = null
        var pageStop = CompletableDeferred<Boolean>()
        val loadMutex = Mutex()

        fun resetLoadState() {
            pageStop = CompletableDeferred()
            lastExtract.value = null
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val initMutex = Mutex()

    private var appContext: Context? = null
    private var runtime: GeckoRuntime? = null
    private var installedExtension: WebExtension? = null
    private var nativePort: WebExtension.Port? = null
    private var bgAliveReceived = false
    private var tabCounter = 0

    private val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private var reqCounter = 0

    private val _status = MutableStateFlow(BridgeStatus())
    val statusFlow: StateFlow<BridgeStatus> = _status

    private val _tabs = MutableStateFlow<List<GeckoTab>>(emptyList())
    val tabsFlow: StateFlow<List<GeckoTab>> = _tabs

    private val _selectedTabId = MutableStateFlow<String?>(null)
    val selectedTabIdFlow: StateFlow<String?> = _selectedTabId

    val selectedTabFlow: StateFlow<GeckoTab?> = combine(_tabs, _selectedTabId) { tabs, id ->
        tabs.firstOrNull { it.id == id } ?: tabs.firstOrNull()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _downloads = MutableStateFlow<List<GeckoDownload>>(emptyList())
    val downloadsFlow: StateFlow<List<GeckoDownload>> = _downloads

    private fun updateStatus(
        engine: EngineState? = null,
        extensionInstalled: Boolean? = null,
        bgAlive: Boolean? = null,
        lastError: String? = null
    ) {
        val cur = _status.value
        val portKind = if (nativePort != null) "background"
            else if (_tabs.value.any { it.port != null }) "session" else "none"
        _status.value = cur.copy(
            engine = engine ?: cur.engine,
            extensionInstalled = extensionInstalled ?: cur.extensionInstalled,
            bgAlive = bgAlive ?: cur.bgAlive,
            portKind = portKind,
            lastError = lastError ?: cur.lastError
        )
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    // ------------------------------------------------------------------
    // GeckoResult -> coroutine bridge (spike pattern: GeckoResult.accept
    // takes Consumer<T> / Consumer<Throwable>, not listeners)
    // ------------------------------------------------------------------

    private suspend fun <T> GeckoResult<T>.awaitValue(): T? {
        val deferred = CompletableDeferred<T?>()
        accept(
            object : GeckoResult.Consumer<T> {
                override fun accept(t: T?) {
                    deferred.complete(t)
                }
            },
            object : GeckoResult.Consumer<Throwable> {
                override fun accept(t: Throwable?) {
                    deferred.completeExceptionally(
                        t ?: Exception("unknown GeckoResult error")
                    )
                }
            }
        )
        return deferred.await()
    }

    // ------------------------------------------------------------------
    // Bridge delegate (160.x signatures — MessageSender carries the
    // session, @Nullable; null when the sender is a background script)
    // ------------------------------------------------------------------

    private val bridgeDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any>? {
            (message as? JSONObject)?.let { handleBridgeMessage(it, sender.session) }
            return null
        }

        override fun onConnect(port: WebExtension.Port) {
            val session = port.sender.session
            if (session == null) {
                // Background-script port (background.js owns the native
                // port). There is no GeckoTab for it; keep it as the
                // shared native port for all requests.
                nativePort = port
                log("Bridge port connected (session=false) — background native port stored")
                updateStatus()
            } else {
                log("Bridge port connected (session=true)")
                val owner = _tabs.value.firstOrNull { it.session == session }
                if (owner != null) {
                    owner.port = port
                    log("[${owner.id}] native port attached")
                    updateStatus()
                } else {
                    log("Bridge port for unknown session — keeping no reference")
                }
            }
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    (message as? JSONObject)?.let {
                        handleBridgeMessage(it, port.sender.session)
                    }
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    log("Bridge port disconnected (session=${port.sender.session != null})")
                    if (port.sender.session == null && nativePort == port) nativePort = null
                    val owner = _tabs.value.firstOrNull { it.session == port.sender.session }
                    if (owner != null && owner.port == port) owner.port = null
                    updateStatus()
                }
            })
        }
    }

    private fun JSONArray.toPlainList(): List<Any?> {
        return (0 until length()).map { i ->
            when (val item = opt(i)) {
                is JSONObject -> item.toFieldMap()
                is JSONArray -> item.toPlainList()
                JSONObject.NULL -> null
                else -> item
            }
        }
    }

    private fun JSONObject.toFieldMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = when (val v = opt(key)) {
                is JSONObject -> v.toFieldMap()
                is JSONArray -> v.toPlainList()
                JSONObject.NULL -> null
                else -> v
            }
        }
        return map
    }

    private fun handleBridgeMessage(msg: JSONObject, session: GeckoSession?) {
        val id = msg.optString("id", "")
        val type = msg.optString("type", "?")
        if (type == "bg-alive") {
            bgAliveReceived = true
            log("Bridge bg-alive received (one-shot path works — background.js runs)")
            updateStatus(bgAlive = true)
            JarvisStateRepository.addDiagnostic(
                "browser", "GECKO_BG_ALIVE",
                "Gecko bridge LIVE — background.js chal raha hai (bg-alive mila)."
            )
            return
        }
        if (id.isNotEmpty()) {
            pending.remove(id)?.let { deferred ->
                if (!deferred.isCompleted) deferred.complete(msg)
                return
            }
        }
        if (type == "extract") {
            val owner = _tabs.value.firstOrNull { it.session == session }
                ?: _tabs.value.lastOrNull()
            if (owner != null) {
                val extract = GeckoBridgeUtils.parseExtract(msg.toFieldMap())
                if (extract != null) {
                    // B-C4: an extract counts only for its own page.
                    val currentUrl = owner.url.value
                    if (currentUrl.isBlank() ||
                        GeckoBridgeUtils.extractMatchesPage(extract.url, currentUrl)
                    ) {
                        owner.lastExtract.value = extract
                        log("[${owner.id}] auto-extract url=${extract.url} " +
                            "title=${extract.title} captcha=${extract.captcha} " +
                            "textLen=${extract.textLen}")
                    } else {
                        log("[${owner.id}] auto-extract DISCARDED (wrong page: " +
                            "${extract.url} != $currentUrl)")
                    }
                }
            }
        } else {
            log("Bridge msg type=$type id=$id (no waiter)")
        }
    }

    // ------------------------------------------------------------------
    // Runtime + extension setup (lazy — first Browser-tab visit only)
    // ------------------------------------------------------------------

    /**
     * Creates the GeckoRuntime (once per process) and installs the
     * built-in extension (once per process). Idempotent; concurrent
     * callers serialize on initMutex and the second one re-checks.
     * Returns true when the engine is ready to open tabs.
     */
    suspend fun ensureReady(context: Context): Boolean {
        if (_status.value.engine == EngineState.READY) return true
        if (_status.value.engine == EngineState.FAILED &&
            _status.value.lastError.isNotBlank() &&
            runtime != null && installedExtension != null
        ) {
            // A previous failure after runtime creation (e.g. a
            // transient install error) — retry once.
        }
        return initMutex.withLock {
            if (_status.value.engine == EngineState.READY) return@withLock true
            updateStatus(engine = EngineState.STARTING)
            val ctx = context.applicationContext
            appContext = ctx
            val t0 = System.currentTimeMillis()
            if (runtime == null) {
                log("Creating GeckoRuntime…")
                runtime = runCatching { GeckoRuntime.create(ctx) }
                    .onFailure {
                        log("GeckoRuntime.create FAILED: $it")
                        updateStatus(engine = EngineState.FAILED, lastError = "GeckoRuntime nahi bana: $it")
                    }
                    .getOrNull() ?: return@withLock false
                log("GeckoRuntime created in ${System.currentTimeMillis() - t0} ms")
            }
            val rt = runtime ?: return@withLock false
            if (installedExtension == null) {
                log("Installing built-in extension $EXT_URI …")
                val ext = runCatching {
                    withTimeout(EXT_TIMEOUT_MS) {
                        rt.webExtensionController
                            .ensureBuiltIn(EXT_URI, EXT_ID).awaitValue()
                    }
                }.onFailure {
                    log("Extension install FAILED: $it")
                    updateStatus(engine = EngineState.FAILED, lastError = "Extension install fail: $it")
                }.getOrNull()
                if (ext == null) return@withLock false
                ext.setMessageDelegate(bridgeDelegate, NATIVE_APP)
                installedExtension = ext
                updateStatus(extensionInstalled = true)
                log("Extension installed + MessageDelegate set (nativeApp=$NATIVE_APP)")
            }
            updateStatus(engine = EngineState.READY)
            JarvisStateRepository.addDiagnostic(
                "browser", "GECKO_READY",
                "Gecko browser ready — engine $GV_VERSION, extension $EXTENSION_VERSION."
            )
            // bg-alive watchdog: the background page is expected to
            // ping within 15 s of install. If it never arrives the
            // engine still works (pages load), but the bridge is DOWN
            // — the screen says so and offers the WebView fallback.
            scope.launch {
                delay(BG_ALIVE_WAIT_MS)
                if (!bgAliveReceived && _status.value.engine == EngineState.READY) {
                    updateStatus(
                        lastError = "Bridge DOWN — bg-alive 15s me nahi aaya. Pages phir bhi load honge; extraction/captcha/cookies ke liye WebView fallback use karo."
                    )
                    JarvisStateRepository.addDiagnostic(
                        "browser", "GECKO_BRIDGE_DOWN",
                        "Gecko bridge DOWN — bg-alive 15s me nahi aaya (C1/C2 — see PHONE_TESTS_1.13.0)."
                    )
                }
            }
            true
        }
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    private fun nextTabId(): String {
        tabCounter += 1
        return "tab-$tabCounter"
    }

    /**
     * Opens a new tab (session + delegates) and selects it. Returns
     * null when the engine is not ready — call ensureReady first.
     * Must be called from Main (or any thread — it switches to Main).
     */
    suspend fun newTab(): GeckoTab? {
        val rt = runtime ?: return null
        return withContext(Dispatchers.Main) {
            val tab = GeckoTab(nextTabId(), GeckoSession())
            tab.session.setProgressDelegate(object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    tab.url.value = url
                    tab.loading.value = true
                    tab.error.value = ""
                    log("[${tab.id}] onPageStart $url")
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    tab.loading.value = false
                    log("[${tab.id}] onPageStop success=$success")
                    if (!tab.pageStop.isCompleted) tab.pageStop.complete(success)
                }

                override fun onProgressChange(session: GeckoSession, progress: Int) {
                    tab.progress.value = progress
                }
            })
            tab.session.setContentDelegate(object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    if (!title.isNullOrBlank()) tab.title.value = title
                    log("[${tab.id}] onTitleChange: $title")
                }

                override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                    handleDownload(tab, response)
                }

                override fun onCrash(session: GeckoSession) {
                    tab.loading.value = false
                    tab.error.value = "Browser ka renderer crash ho gaya — page dobara load karo."
                    log("[${tab.id}] CONTENT CRASH")
                    if (!tab.pageStop.isCompleted) tab.pageStop.complete(false)
                }
            })
            tab.session.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest
                ): GeckoResult<AllowOrDeny>? {
                    val uri = request.uri
                    // Same rule as the WebView engine: only http/https
                    // stay in-app; every other scheme is blocked.
                    return if (GeckoBridgeUtils.isLoadableScheme(uri)) {
                        GeckoResult.fromValue(AllowOrDeny.ALLOW)
                    } else {
                        tab.error.value = "Blocked link (sirf http/https khulte hain): $uri"
                        GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                }

                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    tab.canGoBack.value = canGoBack
                }

                override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                    tab.canGoForward.value = canGoForward
                }
            })
            tab.session.open(rt)
            installedExtension?.let { ext ->
                tab.session.webExtensionController
                    .setMessageDelegate(ext, bridgeDelegate, NATIVE_APP)
                log("[${tab.id}] session message delegate set (nativeApp=$NATIVE_APP)")
            } ?: log("[${tab.id}] WARNING: no installed extension — session delegate NOT set")
            tab.session.setActive(true)
            _tabs.value = _tabs.value + tab
            _selectedTabId.value = tab.id
            tab
        }
    }

    fun selectTab(id: String) {
        if (_tabs.value.any { it.id == id }) {
            _selectedTabId.value = id
        }
    }

    /**
     * Closes a tab. The last tab can never be closed (the strip hides
     * its X). When the selected tab closes, another tab is selected
     * FIRST; the session itself closes 300 ms later so the Compose
     * GeckoView has time to releaseSession() first (spike lesson:
     * release views BEFORE closing sessions).
     */
    fun closeTab(id: String) {
        val tabs = _tabs.value
        if (tabs.size <= 1) return
        val closing = tabs.firstOrNull { it.id == id } ?: return
        val remaining = tabs.filter { it.id != id }
        if (_selectedTabId.value == id) {
            _selectedTabId.value = remaining.first().id
        }
        _tabs.value = remaining
        scope.launch {
            delay(300L)
            runCatching { closing.session.close() }
            log("[${closing.id}] session closed")
        }
    }

    fun goBack(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return
        runCatching { tab.session.goBack() }
    }

    fun goForward(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return
        runCatching { tab.session.goForward() }
    }

    fun reload(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return
        runCatching { tab.session.reload() }
    }

    fun stop(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return
        runCatching { tab.session.stop() }
    }

    // ------------------------------------------------------------------
    // Page loading
    // ------------------------------------------------------------------

    /**
     * Loads a URL in a tab and waits for pageStop (30 s). Returns false
     * on a blocked scheme, a timeout, or a failed stop. The bridge
     * auto-extract lands separately in tab.lastExtract.
     */
    suspend fun loadUrl(tabId: String, url: String): Boolean {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return false
        if (!GeckoBridgeUtils.isLoadableScheme(url)) {
            tab.error.value = "Ye URL nahi khol sakta — sirf http/https pages allowed hain."
            return false
        }
        return tab.loadMutex.withLock {
            withContext(Dispatchers.Main) {
                tab.resetLoadState()
                tab.url.value = url
                tab.session.loadUri(url)
                try {
                    withTimeout(PAGE_TIMEOUT_MS) { tab.pageStop.await() }
                } catch (e: TimeoutCancellationException) {
                    tab.loading.value = false
                    tab.error.value = "Timeout: page 30s me load nahi hui."
                    log("[${tab.id}] load $url TIMEOUT after ${PAGE_TIMEOUT_MS}ms")
                    false
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Bridge request/response (background-owned port first, per-session
    // port as fallback — spike pattern)
    // ------------------------------------------------------------------

    private fun nextId(prefix: String): String {
        reqCounter += 1
        return "$prefix-$reqCounter"
    }

    private suspend fun requestViaPort(
        tab: GeckoTab,
        msg: JSONObject,
        timeoutMs: Long = EXT_TIMEOUT_MS
    ): JSONObject? {
        val id = msg.optString("id", "")
        val port = nativePort ?: tab.port
        if (port == null) {
            log("[${tab.id}] request $id FAILED: no native port yet")
            return null
        }
        val deferred = CompletableDeferred<JSONObject>()
        if (id.isNotEmpty()) pending[id] = deferred
        runCatching { port.postMessage(msg) }
            .onFailure {
                log("[${tab.id}] postMessage FAILED: $it")
                pending.remove(id)
                return null
            }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            log("[${tab.id}] request $id TIMEOUT after ${timeoutMs}ms")
            null
        } finally {
            pending.remove(id)
        }
    }

    /**
     * The native port attaches when the background script connects
     * (at extension start) — poll briefly before any request so a
     * just-started engine is not failed for a port that lands 200 ms
     * later.
     */
    private suspend fun awaitPort(tab: GeckoTab, timeoutMs: Long = PORT_WAIT_MS): Boolean {
        val t0 = System.currentTimeMillis()
        while (nativePort == null && tab.port == null &&
            System.currentTimeMillis() - t0 < timeoutMs
        ) {
            delay(200)
        }
        if (nativePort == null && tab.port == null) {
            log("[${tab.id}] no native port after ${timeoutMs}ms")
        }
        return nativePort != null || tab.port != null
    }

    /**
     * Asks the content script for the current page (title + text +
     * captcha marker). Returns null when the bridge is down or the
     * reply belongs to a different page (B-C4 — never wrong-page text).
     */
    suspend fun requestExtract(tabId: String): GeckoExtract? {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return null
        if (!awaitPort(tab)) return null
        val requestedUrl = tab.url.value
        val msg = JSONObject().apply {
            put("type", "extract")
            put("id", nextId("extract"))
            put("url", requestedUrl)
        }
        val reply = requestViaPort(tab, msg) ?: return null
        val extract = GeckoBridgeUtils.parseExtract(reply.toFieldMap()) ?: return null
        if (requestedUrl.isNotBlank() &&
            !GeckoBridgeUtils.extractMatchesPage(extract.url, requestedUrl)
        ) {
            log("[${tab.id}] extract DISCARDED (wrong page: ${extract.url} != $requestedUrl)")
            return null
        }
        tab.lastExtract.value = extract
        return extract
    }

    /** Asks the content script for the captcha marker on this page. */
    suspend fun requestCaptcha(tabId: String): String? {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return null
        if (!awaitPort(tab)) return null
        val msg = JSONObject().apply {
            put("type", "captcha")
            put("id", nextId("captcha"))
            put("url", tab.url.value)
        }
        val reply = requestViaPort(tab, msg) ?: return null
        return (reply.optString("captcha", "none")).takeIf { it.isNotBlank() }
    }

    /** Cookies for a tab's current URL, via background.js (cookies API). */
    suspend fun getCookies(tabId: String): List<GeckoCookie> {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return emptyList()
        if (!awaitPort(tab)) return emptyList()
        val msg = JSONObject().apply {
            put("type", "cookie-get")
            put("id", nextId("cookie-get"))
            put("url", tab.url.value)
        }
        val reply = requestViaPort(tab, msg) ?: return emptyList()
        if (!reply.optBoolean("ok", false)) return emptyList()
        return GeckoBridgeUtils.parseCookies(reply.toFieldMap())
    }

    /** Sets one cookie for a tab's current URL, via background.js. */
    suspend fun setCookie(tabId: String, name: String, value: String): Boolean {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return false
        if (!awaitPort(tab)) return false
        val msg = JSONObject().apply {
            put("type", "cookie-set")
            put("id", nextId("cookie-set"))
            put("url", tab.url.value)
            put("name", name)
            put("value", value)
        }
        val reply = requestViaPort(tab, msg) ?: return false
        return reply.optBoolean("ok", false)
    }

    // ------------------------------------------------------------------
    // Downloads (onExternalResponse — spike pattern)
    // ------------------------------------------------------------------

    private fun handleDownload(tab: GeckoTab, response: WebResponse) {
        val uri = response.uri
        val contentType = response.headers["Content-Type"]
        val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
        val filename = GeckoBridgeUtils.downloadFileName(
            uri,
            response.headers["Content-Disposition"]
        )
        log("[${tab.id}] onExternalResponse uri=$uri type=$contentType " +
            "len=$contentLength file=$filename status=${response.statusCode}")
        val ctx = appContext ?: return
        scope.launch(Dispatchers.IO) {
            val saved = runCatching {
                val dir = File(ctx.filesDir, "gecko-downloads").apply { mkdirs() }
                val out = File(dir, filename)
                val body = response.body ?: return@runCatching null
                FileOutputStream(out).use { fos -> body.copyTo(fos) }
                body.close()
                out
            }.onFailure { log("[${tab.id}] download save FAILED: $it") }.getOrNull()
            if (saved != null) {
                val entry = GeckoDownload(
                    fileName = filename,
                    path = saved.absolutePath,
                    bytes = saved.length(),
                    uri = uri,
                    savedAtMs = System.currentTimeMillis()
                )
                _downloads.value = listOf(entry) + _downloads.value
                log("[${tab.id}] download saved: ${saved.absolutePath} (${saved.length()} bytes)")
            }
        }
    }

    // ------------------------------------------------------------------
    // Screenshots (capturePixels proven on the phone in the spike;
    // captureFullPage best-effort — it failed twice there)
    // ------------------------------------------------------------------

    /**
     * Captures the attached GeckoView and saves a PNG to
     * filesDir/gecko-shots/. Returns the file, or null on failure.
     * The view must be the one currently attached to a tab.
     */
    suspend fun captureScreenshot(view: GeckoView): File? {
        val ctx = appContext ?: return null
        // No explicit nullable type here: the Elvis narrows the try
        // result to a non-null Bitmap, and an explicit `Bitmap?`
        // annotation would defeat that smart-cast (CI 45044198448).
        val bitmap = try {
            withTimeout(EXT_TIMEOUT_MS) { view.capturePixels().awaitValue() }
        } catch (e: Exception) {
            log("capturePixels FAILED: $e")
            null
        } ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.filesDir, "gecko-shots").apply { mkdirs() }
                val out = File(dir, "gecko-shot-${System.currentTimeMillis()}.png")
                FileOutputStream(out).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                bitmap.recycle()
                log("Screenshot saved: ${out.absolutePath} (${out.length()} bytes)")
                out
            }.onFailure { log("Screenshot save FAILED: $it") }.getOrNull()
        }
    }

    /**
     * Best-effort full-page capture. Returns the file, or null (the
     * spike's phone runs failed this twice — the screen degrades to
     * capturePixels, never blocks on it).
     */
    suspend fun captureFullPage(view: GeckoView): File? {
        val ctx = appContext ?: return null
        // Same Elvis narrowing as captureScreenshot (no `: Bitmap?`).
        val bitmap = try {
            withTimeout(EXT_TIMEOUT_MS) { view.captureFullPage().awaitValue() }
        } catch (e: Exception) {
            log("captureFullPage FAILED (best-effort): $e")
            null
        } ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.filesDir, "gecko-shots").apply { mkdirs() }
                val out = File(dir, "gecko-full-${System.currentTimeMillis()}.png")
                FileOutputStream(out).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                bitmap.recycle()
                out
            }.getOrNull()
        }
    }
}
