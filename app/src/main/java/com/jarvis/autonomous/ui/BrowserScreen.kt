package com.jarvis.autonomous.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * S3 — In-app browser for JarvisAgentApp 1.4.0.
 *
 * Self-contained screen + shared browser engine. This file is the ONLY
 * place browser code lives (coordinator wires it in; it does not edit
 * MainActivity / JarvisAgentEngine / LlmClient itself — see
 * INTEGRATION_NOTES_Browser.md at the repo root for the wiring spec).
 *
 * TWO MODES, ONE WebView ENGINE (user refinement 2026-11-02 23:00 IST):
 *  (a) BACKGROUND (default for question-answering): Jarvis loads the page
 *      in the shared, headless WebView owned by [JarvisBackgroundBrowser]
 *      — no Chrome, no leaving the app, no visible page load the user
 *      has to watch — extracts title + main text, and the engine speaks
 *      / shows the ANSWER directly.
 *  (b) FOREGROUND show: only when the user explicitly wants to SEE the
 *      page, the SAME already-loaded WebView is shown in the Browser tab
 *      via [BrowserHostState.showLoadedPage] — no reload, because the
 *      screen only calls loadUrl when webView.url differs from the target.
 *
 * SAFETY RULE (miss-analysis item 11, enforced by the ENGINE, not by
 * this screen alone): Jarvis opens only URLs the user asked for, or
 * URLs Jarvis explicitly proposed AND the user approved. This screen
 * never auto-loads an unapproved URL — manual typing is the user
 * asking, by definition. See the integration notes for the exact rule.
 *
 * THEME (miss-analysis item 10): creamy theme, same as the rest of the
 * app — background 0xFFFFF8ED, terracotta primary 0xFFB85C38, text
 * 0xFF3B2F2A, muted 0xFF8A7A70. No default grey screens.
 *
 * SHARED RENDERING ENGINE (preview decision 2026-11-02 23:09 IST):
 * the WebView setup here is NOT tab-only. [JarvisWebView] is the
 * reusable composable the Projects localhost preview renders through
 * too, and [JarvisBackgroundBrowser.obtainWebView] is the shared
 * factory/configuration (full HTML + CSS + JS execution, DOM storage,
 * image loading, multi-page navigation). "Open in Browser" is
 * [BrowserHostState.openInBrowser]: a preview URL opens full-screen in
 * the Browser tab through the same instance, without reloading where
 * possible. See INTEGRATION_NOTES_Browser.md §14.
 *
 * The browser NEVER leaves the app on its own: links stay in the
 * WebView (shouldOverrideUrlLoading returns false only for http/https),
 * and the external-browser / share buttons fire ONLY on an explicit
 * user tap. External open is OFF by default — there is no automatic
 * ACTION_VIEW anywhere in this file.
 */

// ---------------------------------------------------------------------------
// Background-browse result (shared by mode (a) and the engine)
// ---------------------------------------------------------------------------

/** Outcome of one background browse. See the integration notes §5. */
enum class BrowserBrowseStatus {
    /** Page loaded and text extracted normally. */
    COMPLETED,

    /**
     * The browse job stopped before producing an answer — Android killed
     * the process mid-browse (detected on the next interaction via the
     * pending marker), or the job was cancelled while the app was alive.
     * The engine MUST speak the interrupted line and ask to retry; it
     * must NEVER invent page content for this result.
     */
    INTERRUPTED_PROCESS_KILLED,

    /** Load error or timeout (page never finished, JS stuck, etc.). */
    FAILED
}

data class BrowserPageResult(
    val url: String,
    val title: String = "",
    val mainText: String = "",
    val isNonHttps: Boolean = false,
    val status: BrowserBrowseStatus = BrowserBrowseStatus.COMPLETED,
    val errorMessage: String = ""
)

// ---------------------------------------------------------------------------
// URL helpers (used by the screen, the background engine, and the engine
// action spec — one normalisation rule everywhere)
// ---------------------------------------------------------------------------

/**
 * Normalises raw user / LLM input to a loadable URL.
 * - Blank -> "" (caller must refuse to load).
 * - http:// / https:// kept as-is.
 * - Any other scheme (javascript:, file:, intent:, data:, ...) -> ""
 *   (blocked: only web pages may load, never local files or intents).
 * - localhost / 127.0.0.1 -> http:// (loopback never leaves the device,
 *   and the Projects localhost preview is http).
 * - Anything else (bare domain, e.g. "example.com") -> https:// prefix.
 */
fun normalizeBrowserUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val lower = trimmed.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://")) return trimmed
    if (lower.contains("://")) return ""
    if (lower.startsWith("localhost") || lower.startsWith("127.0.0.1")) return "http://$trimmed"
    return "https://$trimmed"
}

/** True for any http:// URL (the non-HTTPS banner / warning condition). */
fun isNonHttpsUrl(url: String): Boolean = url.startsWith("http://")

/**
 * Decodes one evaluateJavascript result. JS string results arrive as a
 * JSON-quoted string literal, so parsing {"v":<raw>} unescapes it.
 * Falls back to the raw text (trimmed of quotes) on parse failure.
 */
fun decodeJsString(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    if (raw == "null") return ""
    return runCatching { JSONObject("{\"v\":$raw}").optString("v") }
        .getOrDefault(raw.trim('"'))
}

// ---------------------------------------------------------------------------
// Foreground tab state — the coordinator-owned entry point
// ---------------------------------------------------------------------------

/**
 * State holder the coordinator hoists: `val browserHost = remember {
 * BrowserHostState() }` in MainActivity, passed to BrowserScreen, with
 * `browserHost.onShowBrowserTab = { selectedTab = <Browser tab index> }`.
 *
 * Entry points the coordinator / engine action may call:
 * - [loadUrl]: foreground load + switch to the Browser tab. Use when the
 *   user explicitly wants to SEE a page (or typed it in another tab).
 * - [showLoadedPage]: same tab switch, but for a page the background
 *   engine ALREADY loaded — the screen will NOT reload it, because its
 *   update block only loads when webView.url differs from the target.
 *
 * Neither method is ever called by background mode (a): background
 * browsing deliberately does NOT touch this holder or the tab state,
 * so the user never watches a load.
 */
class BrowserHostState {
    var foregroundUrl by mutableStateOf<String?>(null)
        private set

    /** Set by the coordinator to switch to the Browser tab. No-op until set. */
    var onShowBrowserTab: () -> Unit = {}

    fun loadUrl(url: String) {
        val normalized = normalizeBrowserUrl(url)
        if (normalized.isEmpty()) return
        foregroundUrl = normalized
        onShowBrowserTab()
    }

    fun showLoadedPage(url: String) {
        val normalized = normalizeBrowserUrl(url)
        if (normalized.isEmpty()) return
        foregroundUrl = normalized
        onShowBrowserTab()
    }

    /**
     * "Open in Browser" action (preview decision 2026-11-02 23:09 IST):
     * from a Projects preview, open the same URL full-screen in the
     * Browser tab. Same path as [showLoadedPage]: because the preview
     * already rendered through the shared WebView, the Browser tab
     * shows it without reloading where possible (webView.url already
     * equals the target — see the equality check in [JarvisWebView]).
     */
    fun openInBrowser(url: String) {
        showLoadedPage(url)
    }
}

/**
 * 1.4.0 service bridge: the Activity-owned [BrowserHostState]
 * registers itself here so the foreground service's engine can
 * show a loaded page in the Browser tab without depending on
 * the Activity. Null when the UI is not alive — foreground
 * show then falls back to a background answer in the engine.
 */
object JarvisBrowserHost {
    @Volatile
    var current: BrowserHostState? = null
}

// ---------------------------------------------------------------------------
// Shared WebView engine (background mode + foreground screen share it)
// ---------------------------------------------------------------------------

/** Callbacks the visible Browser tab registers; cleared on tab dispose. */
data class ForegroundCallbacks(
    val onProgress: (Int) -> Unit,
    val onTitle: (String) -> Unit,
    val onUrlChanged: (String) -> Unit,
    val onError: (String) -> Unit
)

/**
 * Retained, headless-capable browser engine.
 *
 * HOSTING (refinement spec): a single WebView instance owned by this
 * singleton — NOT by any Compose tab. It is created lazily with the
 * application context, survives tab switches and tab disposal, and is
 * destroyed only by an explicit [destroy] call at app shutdown. The
 * foreground tab borrows the same instance through AndroidView (and
 * returns it without destroying it in onRelease), which is exactly how
 * mode (b) shows an already-loaded page without reloading.
 *
 * Single-instance trade-off (honest limitation, see notes §11): there
 * is only ONE page at a time. A background browse navigates the shared
 * WebView away from whatever the foreground tab is showing. The engine
 * must therefore only background-browse when the Browser tab is not
 * currently visible, or tell the user the visible page will change.
 */
object JarvisBackgroundBrowser {

    private const val PREFS_NAME = "jarvis_browser_pending"
    private const val KEY_PENDING_URL = "pending_url"

    private var sharedWebView: WebView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var foregroundCallbacks: ForegroundCallbacks? = null
    private var pendingExtraction: PendingExtraction? = null

    private class PendingExtraction(
        val url: String,
        val deferred: CompletableDeferred<BrowserPageResult>
    ) {
        // onPageFinished can fire for sub-frames too; only the first win
        // starts extraction.
        val extractionStarted = AtomicBoolean(false)
    }

    /** Foreground tab registers its callbacks; tab dispose clears them. */
    fun setForegroundCallbacks(callbacks: ForegroundCallbacks?) {
        foregroundCallbacks = callbacks
    }

    /**
     * Returns the shared WebView, creating it (with the safe settings
     * below) on first use. Callers must NEVER destroy this instance on
     * tab dispose — use [destroy] only at app shutdown, on Main.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun obtainWebView(context: Context): WebView {
        sharedWebView?.let { return it }
        val appContext = context.applicationContext
        val created = WebView(appContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // No credential autofill into the WebView, ever.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            settings.javaScriptEnabled = true
            // JavaScript ON is a deliberate, narrow choice: normal
            // browsing only. NO addJavascriptInterface is ever added,
            // so page JS cannot call into the app.
            settings.domStorageEnabled = true
            // Full-website rendering (preview quality bar, §14):
            // images, viewport handling, zoom, and default caching, so
            // a page renders exactly like a real website. Multi-page
            // navigation comes from the shared client: http/https links
            // stay in this WebView, building real back/forward history.
            settings.loadsImagesAutomatically = true
            settings.blockNetworkImage = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.setGeolocationEnabled(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            webViewClient = SharedWebViewClient()
            webChromeClient = SharedWebChromeClient()
            // Downloads are disabled: tapping a download does nothing
            // except this note — no file ever lands on the phone.
            setDownloadListener { _, _, _, _, _ ->
                Toast.makeText(
                    appContext,
                    "Download is browser me band hai.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        sharedWebView = created
        return created
    }

    /** App-shutdown only. NEVER call from tab dispose / onRelease. */
    fun destroy() {
        runCatching { sharedWebView?.stopLoading() }
        runCatching { sharedWebView?.destroy() }
        sharedWebView = null
        foregroundCallbacks = null
        pendingExtraction = null
    }

    // -- Process-kill marker (FULL CHECK spec, notes §5) -------------------

    private fun markPending(context: Context, url: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING_URL, url).apply()
    }

    private fun clearPending(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_PENDING_URL).apply()
    }

    /**
     * Returns the URL of a browse that was still pending when the
     * process died, and clears the marker (single report — it must not
     * repeat on every later launch). Returns null when nothing was
     * interrupted. The engine calls this on the next user interaction
     * after a restart; a non-null result means: speak
     * "Browsing ruk gaya, dobara koshish karun?" and re-browse ONLY on
     * the user's yes — never fabricate the missing answer.
     */
    fun takeInterruptedBrowse(context: Context): String? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_PENDING_URL, null)
        if (url != null) {
            prefs.edit().remove(KEY_PENDING_URL).apply()
        }
        return url
    }

    // -- Background mode (a) ----------------------------------------------

    /**
     * Background browse (mode a). Suspends until the result is ready.
     *
     * Exact pipeline: normalise -> mark pending -> load -> onPageFinished
     * -> 800 ms settle delay (late JS) -> evaluateJavascript
     * `document.title` + `document.body.innerText.slice(0, 2000)` ->
     * whitespace-collapsed, truncated to 2000 chars -> COMPLETED.
     * Overall [timeoutMs] default 30_000 ms; on timeout the result is
     * FAILED (never partial-fabricated text). Cancellation while the
     * process is alive is converted to INTERRUPTED_PROCESS_KILLED on
     * purpose, so the engine can speak the interrupted line instead of
     * silently dropping the job.
     */
    suspend fun browse(
        context: Context,
        rawUrl: String,
        timeoutMs: Long = 30_000L
    ): BrowserPageResult {
        val url = normalizeBrowserUrl(rawUrl)
        if (url.isEmpty()) {
            return BrowserPageResult(
                url = rawUrl,
                status = BrowserBrowseStatus.FAILED,
                errorMessage = "Khaali ya blocked URL — sirf http/https pages allowed hain."
            )
        }
        markPending(context, url)
        val result: BrowserPageResult = try {
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(timeoutMs) { loadAndExtract(url, context) }
                    ?: BrowserPageResult(
                        url = url,
                        status = BrowserBrowseStatus.FAILED,
                        errorMessage = "Timeout: page $timeoutMs ms me load nahi hui."
                    )
            }
        } catch (ce: CancellationException) {
            BrowserPageResult(
                url = url,
                status = BrowserBrowseStatus.INTERRUPTED_PROCESS_KILLED,
                errorMessage = "Browse job beech me ruk gaya."
            )
        } catch (e: Exception) {
            BrowserPageResult(
                url = url,
                status = BrowserBrowseStatus.FAILED,
                errorMessage = e.message ?: "Unknown error"
            )
        }
        clearPending(context)
        if (result.status != BrowserBrowseStatus.COMPLETED) {
            runCatching { obtainWebView(context).stopLoading() }
        }
        pendingExtraction = null
        return result
    }

    /** Callback variant of [browse] for non-suspend callers. */
    fun browseAsync(
        context: Context,
        rawUrl: String,
        timeoutMs: Long = 30_000L,
        onResult: (BrowserPageResult) -> Unit
    ) {
        scope.launch { onResult(browse(context, rawUrl, timeoutMs)) }
    }

    private suspend fun loadAndExtract(url: String, context: Context): BrowserPageResult {
        val webView = obtainWebView(context)
        val deferred = CompletableDeferred<BrowserPageResult>()
        val pending = PendingExtraction(url, deferred)
        pendingExtraction = pending
        if (webView.url == url) {
            // Already loaded (e.g. foreground tab showed it): extract
            // without reloading.
            startExtraction(webView, pending)
        } else {
            webView.loadUrl(url)
        }
        return deferred.await()
    }

    private fun startExtraction(webView: WebView, pending: PendingExtraction) {
        if (!pending.extractionStarted.compareAndSet(false, true)) return
        scope.launch {
            delay(800L)
            if (pending.deferred.isCompleted) return@launch
            try {
                val title = evaluateJs(webView, "document.title")
                val rawText = evaluateJs(
                    webView,
                    "document.body ? document.body.innerText.slice(0, 2000) : ''"
                )
                val cleaned = rawText.replace(Regex("\\s+"), " ").trim().take(2000)
                pending.deferred.complete(
                    BrowserPageResult(
                        url = pending.url,
                        title = title,
                        mainText = cleaned,
                        isNonHttps = isNonHttpsUrl(pending.url),
                        status = BrowserBrowseStatus.COMPLETED
                    )
                )
            } catch (e: Exception) {
                pending.deferred.complete(
                    BrowserPageResult(
                        url = pending.url,
                        status = BrowserBrowseStatus.FAILED,
                        errorMessage = e.message ?: "Extraction fail ho gaya."
                    )
                )
            }
        }
    }

    private suspend fun evaluateJs(webView: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { raw ->
                if (continuation.isActive) {
                    continuation.resume(decodeJsString(raw))
                }
            }
        }

    // -- The single shared client (foreground + background dispatch) -----

    private class SharedWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val target = request?.url?.toString() ?: return false
            val scheme = request.url?.scheme?.lowercase() ?: ""
            // http/https stay INSIDE the app (return false). Everything
            // else (intent:, tel: handled by the app itself, file:,
            // javascript:, ...) is blocked — the browser never hands
            // the user off to Chrome or another app on its own.
            if (scheme == "http" || scheme == "https") return false
            foregroundCallbacks?.onError("Blocked link (sirf http/https khulte hain): $target")
            return true
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val webView = view ?: return
            val finishedUrl = url ?: webView.url ?: return
            foregroundCallbacks?.onUrlChanged(finishedUrl)
            val pending = pendingExtraction ?: return
            startExtraction(webView, pending)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame != true) return
            val message = error?.description?.toString() ?: "Page load error"
            foregroundCallbacks?.onError(message)
            val pending = pendingExtraction ?: return
            pending.deferred.complete(
                BrowserPageResult(
                    url = pending.url,
                    status = BrowserBrowseStatus.FAILED,
                    errorMessage = message
                )
            )
        }
    }

    private class SharedWebChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            foregroundCallbacks?.onProgress(newProgress)
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            if (!title.isNullOrBlank()) {
                foregroundCallbacks?.onTitle(title)
            }
        }

        // File upload from the WebView is disabled: the chooser is
        // cancelled outright, never shown.
        override fun onShowFileChooser(
            view: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            return true
        }

        // JS dialogs are suppressed: the shared WebView runs on the
        // application context, so no Activity dialog is ever attempted.
        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean {
            result?.cancel()
            return true
        }

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean {
            result?.cancel()
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?
        ): Boolean {
            result?.cancel()
            return true
        }
    }
}

// ---------------------------------------------------------------------------
// Shared rendering-engine composable (preview decision 2026-11-02)
// ---------------------------------------------------------------------------

/**
 * THE shared rendering component: both the Browser tab ([BrowserScreen])
 * and the Projects localhost preview render through this composable,
 * which borrows the single shared WebView from
 * [JarvisBackgroundBrowser.obtainWebView]. That is what makes the
 * preview and the browser the same engine — same settings (full HTML +
 * CSS + JS, DOM storage, images, viewport, zoom), same client (links
 * stay in-app, multi-page history), same instance.
 *
 * Constraint the coordinator must respect: only ONE JarvisWebView may
 * be composed at a time. Tab switching (`when (selectedTab)`) already
 * guarantees this — exactly one tab is composed. If two ever coexisted,
 * they would fight over the single View instance and the last
 * registered foreground callbacks would win. The callbacks registered
 * here are keyed on the lifecycle owner only, so they must capture
 * nothing but stable state (remembered holders and state setters) —
 * which is exactly what [BrowserScreen] passes.
 *
 * Lifecycle: onResume/onPause follow the ACTIVITY lifecycle only, so
 * background extraction keeps running across tab switches. Disposal
 * clears callbacks but NEVER destroys the shared WebView (it must
 * survive for the next tab visit, for background mode, and for the
 * "Open in Browser" handoff).
 *
 * No-reload rule: [targetUrl] is loaded ONLY when it differs from
 * `webView.url`. A page already rendered (background browse, Projects
 * preview, or a tab return) is therefore shown instantly, never
 * re-fetched.
 */
@Composable
fun JarvisWebView(
    targetUrl: String,
    modifier: Modifier = Modifier,
    onTitle: (String) -> Unit = {},
    onProgress: (Int) -> Unit = {},
    onUrlChanged: (String) -> Unit = {},
    onError: (String) -> Unit = {},
    onWebViewReady: (WebView) -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val holder = remember { arrayOfNulls<WebView>(1) }

    DisposableEffect(lifecycleOwner) {
        JarvisBackgroundBrowser.setForegroundCallbacks(
            ForegroundCallbacks(
                onProgress = onProgress,
                onTitle = onTitle,
                onUrlChanged = onUrlChanged,
                onError = onError
            )
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> holder[0]?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            JarvisBackgroundBrowser.setForegroundCallbacks(null)
        }
    }

    AndroidView(
        factory = { ctx ->
            val shared = JarvisBackgroundBrowser.obtainWebView(ctx)
            (shared.parent as? ViewGroup)?.removeView(shared)
            holder[0] = shared
            onWebViewReady(shared)
            shared
        },
        update = { webView ->
            holder[0] = webView
            onWebViewReady(webView)
            if (targetUrl.isNotBlank() && webView.url != targetUrl) {
                webView.loadUrl(targetUrl)
            }
        },
        onRelease = {
            // Shared instance survives tab switches: no destroy,
            // no pause here (pause follows the activity lifecycle).
        },
        modifier = modifier.background(Color.White)
    )
}

// ---------------------------------------------------------------------------
// Foreground tab screen (mode b + standalone manual browsing)
// ---------------------------------------------------------------------------

/**
 * Browser tab. Works fully standalone — the user types a URL, browses
 * and navigates with no engine involvement at all.
 *
 * @param browserHost shared holder from the coordinator, or null for a
 * standalone instance (manual browsing only, no engine tab-switch).
 *
 * Coordinator entry point: [BrowserHostState.loadUrl] (foreground load
 * + tab switch) and [BrowserHostState.showLoadedPage] (already-loaded
 * page, no reload). See the integration notes for the wiring.
 */
@Composable
fun BrowserScreen(browserHost: BrowserHostState? = null) {
    val context = LocalContext.current

    // --- ALL state declarations FIRST (kotlin_prepush_checks: every
    // callback below only captures variables declared above). The tab
    // itself is built on JarvisWebView (shared engine); lifecycle and
    // foreground callbacks live inside that composable.
    val host = browserHost ?: remember { BrowserHostState() }
    val hostUrl = host.foregroundUrl
    var urlInput by remember { mutableStateOf(hostUrl ?: "") }
    var currentUrl by remember { mutableStateOf(hostUrl ?: "") }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    // Plain holder, not state: the shared WebView instance is read by
    // buttons without needing recomposition.
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }

    // --- Helpers (after the state they mutate).
    fun navigateTo(raw: String) {
        val normalized = normalizeBrowserUrl(raw)
        if (normalized.isEmpty()) {
            statusMessage = "Ye URL nahi khol sakta — sirf http/https pages allowed hain."
            return
        }
        urlInput = normalized
        currentUrl = normalized
        errorMessage = ""
        statusMessage = ""
        val existing = webViewHolder[0]
        if (existing != null && existing.url != normalized) {
            existing.loadUrl(normalized)
        }
    }

    // --- Engine-driven URL changes (foreground mode b).
    LaunchedEffect(hostUrl) {
        if (hostUrl != null && hostUrl != currentUrl) {
            urlInput = hostUrl
            currentUrl = hostUrl
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFFF8ED))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Jarvis Browser — App ke andar",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB85C38)
        )
        Text(
            text = "Pages isi app ke andar khulti hain, Chrome me nahi. Bahari browser sirf neeche wale button ko dabane par khulega.",
            fontSize = 12.sp,
            color = Color(0xFF8A7A70)
        )

        // URL bar: editable, bare domains get https:// by default.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("URL ya domain (e.g. example.com)") },
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
                onClick = {
                    val webView = webViewHolder[0]
                    if (webView != null && webView.canGoBack()) {
                        webView.goBack()
                    }
                }
            ) { Text("Back", fontSize = 12.sp) }
            OutlinedButton(
                enabled = canGoForward,
                onClick = {
                    val webView = webViewHolder[0]
                    if (webView != null && webView.canGoForward()) {
                        webView.goForward()
                    }
                }
            ) { Text("Forward", fontSize = 12.sp) }
            OutlinedButton(
                onClick = { webViewHolder[0]?.reload() }
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
                // OFF by default: this is the ONLY path out of the app,
                // and it fires only on this explicit tap.
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

        // Non-HTTPS warning banner (item 11): shown for EVERY http://
        // load, including loopback — the rule is literal on purpose.
        if (currentUrl.startsWith("http://")) {
            Text(
                text = "⚠️ Ye page secure nahi hai (http, https nahi) — is par password, OTP ya payment mat daalo.",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8D4A2B),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFE0B2))
                    .padding(8.dp)
            )
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
        if (currentUrl.isBlank()) {
            Text(
                text = "Upar URL likh kar Go dabao — ya Jarvis se kaho ki koi page khole.",
                fontSize = 13.sp,
                color = Color(0xFF3B2F2A)
            )
        }

        // Shared rendering engine (the same JarvisWebView the Projects
        // preview uses). Its update loads ONLY when the target differs
        // from webView.url — that equality check is the no-reload
        // mechanism for mode (b), for "Open in Browser", and for tab
        // returns. Back/forward enablement refreshes from the URL
        // callback and after each navigation button press below.
        JarvisWebView(
            targetUrl = currentUrl,
            onTitle = { pageTitle = it },
            onProgress = { progress = it },
            onUrlChanged = { arrived ->
                currentUrl = arrived
                urlInput = arrived
                val current = webViewHolder[0]
                if (current != null) {
                    canGoBack = current.canGoBack()
                    canGoForward = current.canGoForward()
                }
            },
            onError = { errorMessage = it },
            onWebViewReady = { webViewHolder[0] = it },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
    }
}
