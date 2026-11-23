package com.jarvis.autonomous.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import wendu.dsbridge.DWebView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * 1.15.0-PRO (redesign R0) — WhopClip-style browser job engine on the
 * stock Android System WebView. Replicates WhopClip `JobEngine.kt`
 * (441 lines) + the AutoClip evolutions, adapted to Jarvis:
 *
 * - Engine: one [WebView] per job (headless, application context) or a
 *   caller-supplied Activity-hosted WebView (file-picker steps only).
 * - Channel: **DSBridge (MIT, wendux/DSBridge-Android)** — `DWebView` +
 *   `addJavascriptObject` (ONE method, `onResult`) + `evaluateJavascript`.
 *   The page-side half (`dsbridge.js` v3.1.3, MIT) is vendored in
 *   `app/src/main/assets/` and injected into each loaded page, so page
 *   JS calls `dsBridge.call("JarvisBridge.onResult", ...)` — no CDN load
 *   at runtime. There is no native-messaging port to die silently (the
 *   Gecko failure mode): JS throws come back as `{__err}`, a missing
 *   callback is a 20 s `JobFailed("js timeout")`. Do NOT add a second
 *   bridge implementation (lzyzsd/JsBridge is the documented backup,
 *   not a parallel bridge).
 * - Step language (JSON `steps[]`, from the Executor's BROWSER_JOB
 *   step): goto / wait / wait_text / assert_text / click_text
 *   (text-matched, never selectors) / type (execCommand insertText +
 *   bubbling input event) / js / extract / screenshot / upload.
 * - Desktop mode by default ([JarvisBrowserSetup]); renderer death is
 *   a `JobFailed("renderer_gone")`, never an app crash; camera/mic/
 *   geolocation are silently denied.
 * - Login truth is cookie-based ([JarvisCookieLogin]): a job may name
 *   `login_url` + `login_cookies`; a missing session fails fast as
 *   `session_expired` (first-class outcome — the Executor parks the
 *   plan and the user re-logs in). Cookies themselves never leave the
 *   device.
 * - Captcha is a first-class FAILURE (`captcha:<kind>` — human assist
 *   needed). D3 lock stands: this engine never auto-solves a captcha.
 * - Screenshot-as-evidence: `WebView.draw(canvas)` frames to
 *   `cacheDir/jarvis-frames/`, 480 px max width (the AutoClip OOM
 *   lesson — 1920x1080 bitmaps every few seconds OOM the process).
 *   `screenshot` with `upload:true` fails honestly in 1.15.0 — there
 *   is no hub `/api/frames` endpoint yet (R1); the local path is the
 *   evidence.
 * - Upload needs the app open: headless runs throw
 *   `needs_foreground` immediately (fail-fast, never a silent 90 s
 *   wait); foreground runs arm [JarvisUploadBridge], click the file
 *   input, and await the host's picker signal (R3 wires the host).
 *
 * Improvements over WhopClip (plan §3.2): `evalJs` is coroutine +
 * timeout (no raw-Thread 100 ms busy-poll — the bridge resumes the
 * continuation by id, a late result from a timed-out eval is dropped);
 * `run()` is serialised by a [Mutex] (one job per engine instance);
 * steps are validated BEFORE any WebView is created (fail fast).
 *
 * DON'Ts (kept): no Whop/IG-specific code (service names, login URLs,
 * cookie-name lists, campaign templates, `__usProxy` relay, coins);
 * no server-side session-cookie store; no captcha auto-solve.
 */
class JarvisJobEngine(private val ctx: Context) {

    private val TAG = "JarvisJobEngine"

    class JobFailed(msg: String) : Exception(msg)

    companion object {
        /** In-memory tracker of the currently running job, for the Live tab (R3 reads it). */
        @Volatile
        private var currentJobDesc: String? = null

        fun setCurrentJob(desc: String?) {
            currentJobDesc = desc
        }

        fun currentJobInfo(): String? = currentJobDesc

        /** Actions the step language accepts. Unknown actions fail the job, never skip. */
        val ACTIONS = setOf(
            "goto", "wait", "wait_text", "assert_text", "click_text",
            "type", "js", "extract", "screenshot", "upload"
        )

        /**
         * Validates a steps array BEFORE any WebView is created — a bad
         * job fails fast with a named reason instead of dying mid-run.
         * Pure (no Android calls) — covered by unit tests.
         */
        fun validateSteps(steps: JSONArray) {
            for (i in 0 until steps.length()) {
                val s = steps.optJSONObject(i)
                    ?: throw JobFailed("step $i: not a JSON object")
                when (val action = s.optString("action")) {
                    "goto" -> if (s.optString("url").isBlank())
                        throw JobFailed("step $i (goto): url missing")
                    "wait" -> Unit
                    "wait_text", "assert_text", "click_text" ->
                        if (s.optString("text").isBlank())
                            throw JobFailed("step $i ($action): text missing")
                    "type" -> {
                        if (s.optString("selector").isBlank())
                            throw JobFailed("step $i (type): selector missing")
                        if (!s.has("text"))
                            throw JobFailed("step $i (type): text missing")
                    }
                    "js" -> if (s.optString("code").isBlank())
                        throw JobFailed("step $i (js): code missing")
                    "extract" -> {
                        if (s.optString("key").isBlank())
                            throw JobFailed("step $i (extract): key missing")
                        if (s.optString("code").isBlank())
                            throw JobFailed("step $i (extract): code missing")
                    }
                    "screenshot", "upload" -> Unit
                    else -> throw JobFailed("step $i: unknown action: $action")
                }
            }
        }

        /**
         * Substitutes `{{key}}` placeholders in a goto URL from values
         * an earlier `extract` step produced. Fail closed: an
         * unresolved placeholder never navigates to a literal `{{...}}`.
         * Pure — covered by unit tests.
         */
        fun resolveGotoUrl(url: String, extracted: JSONObject): String {
            var out = url
            val re = Regex("\\{\\{([A-Za-z0-9_.-]+)\\}\\}")
            for (m in re.findAll(url)) {
                val key = m.groupValues[1]
                val value = extracted.optString(key, "")
                if (value.isBlank())
                    throw JobFailed("goto: placeholder {{$key}} unresolved — extract '$key' first")
                out = out.replace("{{$key}}", value)
            }
            return out
        }

        /** Safe file name for a screenshot key. Pure — covered by unit tests. */
        fun sanitizeFrameKey(key: String): String =
            key.replace(Regex("[^A-Za-z0-9._-]"), "_")

        /**
         * Decodes one evaluateJavascript result. JS string results arrive
         * as a JSON-quoted string literal, so parsing `{"v":<raw>}`
         * unescapes them; numbers/booleans/objects pass through as
         * text; `null` decodes to "". Pure — covered by unit tests.
         */
        fun decodeJsResult(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            val t = raw.trim()
            return runCatching {
                val v = JSONObject("{\"v\":$t}").opt("v")
                if (v == null || v === JSONObject.NULL) "" else v.toString()
            }.getOrDefault(t.trim('"'))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val runMutex = Mutex()

    // -- DSBridge eval channel (id-matched, no polling thread) ----------
    // Each eval gets a fresh id; the JS wrapper echoes it back via
    // dsBridge.call("JarvisBridge.onResult", "<id>:<json>"). A late
    // result from a timed-out eval carries a stale id and is dropped,
    // so it can never resume the NEXT eval's continuation with old data.
    // DSBridge invokes Java API methods on the main thread.
    private val jsCounter = AtomicLong(0)
    @Volatile
    private var pendingJsId: Long = -1
    @Volatile
    private var pendingJsCont: kotlinx.coroutines.CancellableContinuation<String>? = null

    /**
     * The ONE Java API object DSBridge exposes to the page, under the
     * namespace "JarvisBridge". DSBridge requires the argument type to
     * be Object and the method to carry @JavascriptInterface. The JS
     * side calls it ONLY through dsBridge.call (the vendored dsbridge.js
     * wrapper) — never as a bare global, so the page cannot reach any
     * other native method: this object has exactly one method.
     */
    private inner class JsApi {
        @JavascriptInterface
        fun onResult(msg: Any?): String {
            val value = msg?.toString() ?: ""
            val sep = value.indexOf(':')
            if (sep < 0) return "ok"
            val id = value.substring(0, sep).toLongOrNull() ?: return "ok"
            if (id != pendingJsId) return "ok"
            val cont = pendingJsCont
            pendingJsCont = null
            if (cont != null && cont.isActive) cont.resume(value.substring(sep + 1))
            return "ok"
        }
    }

    // -- dsbridge.js injection (page-side half of the bridge) -----------
    // The vendored dsbridge.js (assets/dsbridge.js, MIT) defines the
    // global `dsBridge` in the page. A page navigation wipes the JS
    // context, so the flag resets on every goto and the library is
    // re-injected. The trailing `module.exports=bridge` (UMD tail) is
    // wrapped in a local `module` object so it cannot throw in a plain
    // page (no module loader).
    @Volatile
    private var dsBridgeInjected = false
    @Volatile
    private var dsBridgeJsSource: String? = null

    private fun loadDsBridgeJs(): String {
        dsBridgeJsSource?.let { return it }
        val source = runCatching {
            ctx.assets.open("dsbridge.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        dsBridgeJsSource = source
        return source
    }

    /** Raw platform evaluateJavascript (ValueCallback path) — used ONLY to check the injection, never for steps. */
    private suspend fun evalJsRaw(wv: WebView, code: String, timeoutMs: Long = 8000): String? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String?> { cont ->
                mainHandler.post {
                    wv.evaluateJavascript(code) { result -> if (cont.isActive) cont.resume(result) }
                }
            }
        }

    private suspend fun ensureDsBridge(wv: WebView) {
        if (dsBridgeInjected) return
        val source = loadDsBridgeJs()
        if (source.isBlank()) throw JobFailed("dsbridge.js asset missing — bridge cannot start")
        val wrapped = "(function(){var module={exports:{}};\n$source\n})();"
        mainHandler.post { wv.evaluateJavascript(wrapped) {} }
        delay(300)
        val check = evalJsRaw(wv, "typeof dsBridge!=='undefined'")
        if (decodeJsResult(check) != "true")
            throw JobFailed("dsbridge.js injection failed — page JS bridge unavailable")
        dsBridgeInjected = true
    }

    /** Captcha DOM markers (same markers as the Browser tab's check — D3: detect only, never solve). */
    private val CAPTCHA_CHECK_JS = """
        (function(){var d=document;function q(s){try{return !!d.querySelector(s)}catch(e){return false}}
        if(q('iframe[src*="challenges.cloudflare.com"],[class*="cf-turnstile"]'))return 'turnstile';
        if(q('iframe[src*="recaptcha"],.g-recaptcha'))return 'recaptcha';
        if(q('iframe[src*="hcaptcha"],.h-captcha'))return 'hcaptcha';
        if(q('img[src*="captcha"],input[name*="captcha"],input[id*="captcha"]'))return 'text';
        if(q('[data-sitekey]'))return 'unknown';return 'none';})()
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWebView(): DWebView {
        val wv = DWebView(ctx.applicationContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        JarvisBrowserSetup.applyDesktopMode(wv)
        // DSBridge: the ONE Java API object, namespace "JarvisBridge".
        wv.addJavascriptObject(JsApi(), "JarvisBridge")
        wv.webViewClient = JarvisBrowserSetup.renderGoneClient()
        wv.webChromeClient = JarvisBrowserSetup.silentDenyChromeClient()
        return wv
    }

    private suspend fun evalJs(wv: WebView, code: String, timeoutMs: Long = 20000): String {
        // Self-healing: a navigation wipes the injected dsbridge.js, so
        // the first eval after any goto re-injects it (no-op otherwise).
        if (!dsBridgeInjected) ensureDsBridge(wv)
        val id = jsCounter.incrementAndGet()
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String> { cont ->
                pendingJsId = id
                pendingJsCont = cont
                cont.invokeOnCancellation {
                    if (pendingJsId == id) pendingJsCont = null
                }
                mainHandler.post {
                    wv.evaluateJavascript(
                        "(function(){try{var r=($code);dsBridge.call(\"JarvisBridge.onResult\",$id+':'+JSON.stringify(r));}" +
                            "catch(e){dsBridge.call(\"JarvisBridge.onResult\",$id+':'+JSON.stringify({__err:String(e)}));}})()"
                    ) {}
                }
            }
        } ?: throw JobFailed("js timeout: ${code.take(80)}")
        return result
    }

    private suspend fun waitFor(wv: WebView, jsCondition: String, timeoutMs: Long, label: String) {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            val r = evalJs(wv, jsCondition, 8000)
            if (r == "true") return
            delay(700)
        }
        throw JobFailed("wait timeout: $label")
    }

    private suspend fun checkCaptcha(wv: WebView): String {
        val raw = runCatching { evalJs(wv, CAPTCHA_CHECK_JS, 8000) }.getOrDefault("")
        val hit = decodeJsResult(raw).trim().lowercase()
        return if (hit in setOf("turnstile", "recaptcha", "hcaptcha", "unknown", "text")) hit else "none"
    }

    private suspend fun currentUrl(wv: WebView): String? =
        suspendCancellableCoroutine { cont ->
            mainHandler.post { cont.resume(wv.url) }
        }

    /**
     * Captures the WebView's current visible content as a PNG in the app
     * cache (jarvis-frames/). Max 480 px wide — full-size 1920x1080
     * bitmaps OOM the process when taken repeatedly (AutoClip lesson).
     * Returns the absolute file path. The bitmap draw itself hops to the
     * UI thread; a failed draw returns a 1x1 file path honestly logged,
     * never a crash.
     */
    private suspend fun captureScreenshot(wv: WebView, key: String): String =
        withContext(Dispatchers.IO) {
            val safeName = sanitizeFrameKey(key)
            val dir = File(ctx.cacheDir, "jarvis-frames").apply { mkdirs() }
            val out = File(dir, safeName)
            val bmp = suspendCancellableCoroutine<Bitmap> { cont ->
                mainHandler.post {
                    try {
                        val w = wv.width.coerceAtLeast(1)
                        val h = wv.height.coerceAtLeast(1)
                        val scale = (480f / w).coerceAtMost(1f)
                        val bw = (w * scale).toInt().coerceAtLeast(1)
                        val bh = (h * scale).toInt().coerceAtLeast(1)
                        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(b)
                        canvas.scale(scale, scale)
                        wv.draw(canvas)
                        cont.resume(b)
                    } catch (e: Exception) {
                        Log.w(TAG, "screenshot capture failed: ${e.message}")
                        cont.resume(
                            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                        )
                    }
                }
            }
            try {
                FileOutputStream(out).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 90, fos)
                }
            } finally {
                bmp.recycle()
            }
            Log.i(TAG, "screenshot saved: ${out.absolutePath} (${out.length()} bytes)")
            out.absolutePath
        }

    /**
     * Runs all steps; returns extracted values. Must be called off the main thread.
     * @param job `{"id": "...", "steps": [...], "login_url": "...", "login_cookies": ["..."]}`
     * @param externalWebView when a foreground host supplies its DWebView (file-chooser
     *   steps only) — the engine uses it and does NOT destroy it afterwards.
     *   It MUST be a DWebView (plain WebView has no DSBridge object to register).
     * @param onStep optional live callback (step index, action) — the R3 live loop hooks here.
     * @param onFrame optional live callback (screenshot path) — the R3 live loop hooks here.
     */
    suspend fun run(
        job: JSONObject,
        externalWebView: DWebView? = null,
        onStep: ((Int, String) -> Unit)? = null,
        onFrame: ((String) -> Unit)? = null
    ): JSONObject = runMutex.withLock {
        withContext(Dispatchers.IO) {
            val extracted = JSONObject()
            val steps: JSONArray = job.optJSONArray("steps") ?: JSONArray()
            // Validate BEFORE creating any WebView — a bad job fails fast.
            validateSteps(steps)
            val ownsWebView = externalWebView == null
            val jobDesc = "job ${job.optString("id", "").take(8)}"
            setCurrentJob("Chal raha: $jobDesc (${steps.length()} steps)")
            Log.i(TAG, "job start: $jobDesc")

            // Session pre-check (cookie login truth): a job that names a
            // login_url must have the session BEFORE any step runs —
            // otherwise the first goto lands on a login wall and every
            // later step fails confusingly. First-class outcome.
            val loginUrl = job.optString("login_url", "").trim()
            if (loginUrl.isNotBlank()) {
                JarvisCookieLogin.flush()
                val names = job.optJSONArray("login_cookies")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                } ?: emptyList()
                if (!JarvisCookieLogin.hasSessionCookies(loginUrl, *names.toTypedArray())) {
                    setCurrentJob(null)
                    throw JobFailed(
                        "session_expired: no session cookies for $loginUrl — " +
                            "user ko pehle login karna hoga (Browser tab me)."
                    )
                }
            }

            val wv: WebView = if (externalWebView != null) {
                suspendCancellableCoroutine { cont ->
                    mainHandler.post {
                        externalWebView.addJavascriptObject(JsApi(), "JarvisBridge")
                        cont.resume(Unit)
                    }
                }
                externalWebView
            } else {
                var created: WebView? = null
                suspendCancellableCoroutine<Unit> { cont ->
                    mainHandler.post {
                        created = makeWebView()
                        cont.resume(Unit)
                    }
                }
                created ?: throw JobFailed("webview init failed")
            }

            try {
                for (i in 0 until steps.length()) {
                    val s = steps.getJSONObject(i)
                    val action = s.optString("action")
                    onStep?.invoke(i, action)
                    when (action) {
                        "goto" -> {
                            val url = resolveGotoUrl(s.getString("url"), extracted)
                            if (ownsWebView) {
                                val latch = CountDownLatch(1)
                                @Volatile var loadError: String? = null
                                // Navigation wipes the page JS context — the
                                // injected dsbridge.js is gone with it; the
                                // next evalJs re-injects (self-healing flag).
                                dsBridgeInjected = false
                                mainHandler.post {
                                    wv.webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, u: String?) {
                                            latch.countDown()
                                        }

                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?
                                        ) {
                                            if (request?.isForMainFrame == true) {
                                                loadError = error?.description?.toString()
                                                    ?: "Page load error"
                                                latch.countDown()
                                            }
                                        }

                                        override fun onRenderProcessGone(
                                            view: WebView?,
                                            detail: android.webkit.RenderProcessGoneDetail
                                        ): Boolean {
                                            JarvisBrowserSetup.markRenderGone()
                                            latch.countDown()
                                            return true
                                        }
                                    }
                                    wv.loadUrl(url)
                                }
                                if (!latch.await(45, TimeUnit.SECONDS))
                                    throw JobFailed("goto timeout: $url")
                                loadError?.let { throw JobFailed("goto failed: $it ($url)") }
                            } else {
                                // External WebView: the host owns its client —
                                // never replace it. Load and poll the URL until
                                // it leaves about:blank for the target host
                                // (redirects change the exact URL, so the host
                                // match — not string equality — is the signal).
                                // Navigation wipes the injected dsbridge.js —
                                // the next evalJs re-injects (self-healing).
                                dsBridgeInjected = false
                                mainHandler.post { wv.loadUrl(url) }
                                val targetHost = runCatching {
                                    android.net.Uri.parse(url).host?.lowercase()
                                }.getOrNull()
                                val t0 = System.currentTimeMillis()
                                var arrived = false
                                while (System.currentTimeMillis() - t0 < 45000) {
                                    val now = currentUrl(wv)
                                    val nowHost = now?.let {
                                        runCatching {
                                            android.net.Uri.parse(it).host?.lowercase()
                                        }.getOrNull()
                                    }
                                    if (!now.isNullOrBlank() && now != "about:blank" &&
                                        (now == url || (targetHost != null && nowHost == targetHost))
                                    ) { arrived = true; break }
                                    delay(500)
                                }
                                if (!arrived) throw JobFailed("goto timeout: $url")
                            }
                            delay(1500)
                            // Captcha is a first-class failure (D3: never auto-solve).
                            val captcha = checkCaptcha(wv)
                            if (captcha != "none") {
                                throw JobFailed(
                                    "captcha:$captcha — page par captcha hai; " +
                                        "user ko khud solve karna hoga (auto-solve nahi hota)."
                                )
                            }
                            if (JarvisBrowserSetup.consumeRenderGone())
                                throw JobFailed("renderer_gone: browser renderer crash ho gaya ($url)")
                        }
                        "wait" -> delay(s.optLong("ms", 2000))
                        "wait_text" -> {
                            val t = s.getString("text").replace("'", "\\'")
                            waitFor(
                                wv,
                                "document.body && document.body.innerText.includes('$t')",
                                s.optLong("timeout_ms", 30000), "text:$t"
                            )
                        }
                        "assert_text" -> {
                            val t = s.getString("text").replace("'", "\\'")
                            val r = evalJs(
                                wv,
                                "!!(document.body && document.body.innerText.includes('$t'))"
                            )
                            if (r != "true") throw JobFailed("assert_text missing: $t")
                        }
                        "click_text" -> {
                            val t = s.getString("text").replace("'", "\\'")
                            val timeout = s.optLong("timeout_ms", 15000)
                            val clicked = withTimeoutOrNull(timeout) {
                                while (true) {
                                    val r = evalJs(
                                        wv, """
                                        (function(){
                                          var els=[...document.querySelectorAll('button,a,[role=button]')];
                                          for(var e of els){ if((e.innerText||'').trim().toLowerCase()==='${t.lowercase()}'){e.click();return true;} }
                                          return false;
                                        })()
                                        """.trimIndent(), 8000
                                    )
                                    if (r == "true") return@withTimeoutOrNull true
                                    delay(800)
                                }
                                @Suppress("UNREACHABLE_CODE") false
                            } ?: throw JobFailed("click_text not found: $t")
                            if (!clicked) throw JobFailed("click_text failed: $t")
                            delay(1500)
                        }
                        "type" -> {
                            val sel = s.getString("selector").replace("'", "\\'")
                            val text = s.getString("text")
                                .replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
                            evalJs(
                                wv, """
                                (function(){var el=document.querySelector('$sel');if(!el)return 'no-el';
                                el.focus();document.execCommand('selectAll',false,null);
                                document.execCommand('insertText',false,'$text');
                                el.dispatchEvent(new Event('input',{bubbles:true}));return 'ok';})()
                                """.trimIndent()
                            )
                            delay(1000)
                        }
                        "js" -> evalJs(wv, s.getString("code"))
                        "extract" -> {
                            val key = s.getString("key")
                            val r = evalJs(wv, s.getString("code"))
                            extracted.put(key, decodeJsResult(r))
                        }
                        "screenshot" -> {
                            val key = s.optString("key", "frame_$i.png")
                            val path = captureScreenshot(wv, key)
                            extracted.put(key, path)
                            onFrame?.invoke(path)
                            if (s.optBoolean("upload", false)) {
                                // No hub /api/frames endpoint in 1.15.0 (R1) —
                                // fail honestly instead of pretending to upload.
                                throw JobFailed(
                                    "screenshot upload not supported in 1.15.0 " +
                                        "(no hub frames endpoint yet) — local path saved: $path"
                                )
                            }
                        }
                        "upload" -> {
                            // Headless: no activity, no picker — fail fast so
                            // the caller reports needs_foreground honestly
                            // (no pointless 90 s wait here).
                            if (ownsWebView)
                                throw JobFailed("needs_foreground: upload needs the app open (file picker)")
                            // Arm BEFORE the JS click: the host's
                            // onShowFileChooser signals the latch that
                            // awaitFile() is waiting on.
                            JarvisUploadBridge.arm()
                            val sel = s.optString("selector", "input[type=file]").replace("'", "\\'")
                            val r = evalJs(
                                wv,
                                "(function(){var el=document.querySelector('$sel');if(!el)return 'no-el';el.click();return 'clicked';})()"
                            )
                            if (decodeJsResult(r) != "clicked")
                                throw JobFailed("upload: file input not found ($sel)")
                            if (!JarvisUploadBridge.awaitFile(90000))
                                throw JobFailed("needs_foreground: upload needs the app open (file picker)")
                            delay(2000)
                        }
                        else -> throw JobFailed("unknown action: $action")
                    }
                    if (JarvisBrowserSetup.consumeRenderGone())
                        throw JobFailed("renderer_gone: browser renderer crash ho gaya (step $i: $action)")
                    Log.i(TAG, "step $i ok: $action")
                }
            } finally {
                if (ownsWebView) mainHandler.post { wv.destroy() }
                setCurrentJob(null)
                Log.i(TAG, "job done: $jobDesc")
            }
            extracted
        }
    }
}
