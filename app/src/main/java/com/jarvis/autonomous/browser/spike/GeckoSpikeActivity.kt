package com.jarvis.autonomous.browser.spike

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Looper
import android.os.Process
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * GeckoView Phase 0 spike — THROWAWAY. Branch `geckoview-spike` only.
 *
 * Runs the PLAN.md Phase 0 tests S-2–S-8 on the real phone and shows /
 * logs the results on-screen + logcat, and writes a results JSON to
 * filesDir AND getExternalFilesDir (so it can be pulled without adb
 * run-as) for export. NOT wired into MainActivity, the foreground
 * service, or any engine — launched by explicit intent only:
 *
 *   adb shell am start -n com.jarvis.autonomous/.browser.spike.GeckoSpikeActivity
 *
 * S-1 (APK size) and S-9 (compileSdk 36 build) are CI-side gates, not
 * phone tests — they are recorded in expansion/browser/SPIKE.md from
 * the CI run, not from here. S-2c (app-backgrounded) needs a manual
 * step: tap "S-2c background test", press Home / lock the screen,
 * wait 60 s, come back — the result is evaluated on return.
 *
 * Native messaging only: fixed, reviewed content-script operations
 * (extract / captcha / cookie-get / cookie-set). No generic
 * run-any-JS bridge exists anywhere in this spike.
 */
class GeckoSpikeActivity : ComponentActivity() {

    companion object {
        private const val TAG = "GeckoSpike"

        // Pins (VERSION_PINS.md): GeckoView 160.0.20261116130455 arrives
        // transitively as geckoview-omni via browser-engine-gecko:160.0.1.
        private const val GV_VERSION = "160.0.20261116130455"
        private const val AC_VERSION = "160.0.1"
        private const val EXT_URI = "resource://android/assets/jarvis-bridge/"
        private const val EXT_ID = "jarvis-bridge-spike@jarvis.local"
        private const val NATIVE_APP = "jarvis-bridge"

        private const val STATIC_URL = "https://example.com/"
        private const val SPA_URL = "https://react.dev/"
        private const val GOOGLE_URL = "https://www.google.com/search?q=jarvis+autonomous+agent"
        private const val TURNSTILE_URL = "https://demo.turnstile.workers.dev/"
        private const val COOKIE_URL = "https://example.com/"
        // orimi.com/pdf-test.pdf verified live 2026-11-22 (200,
        // application/pdf, 20,597 B). The old W3C dummy.pdf URL is dead
        // (404) — see BRIDGE_ROOT_CAUSE_AUDIT.md §6.
        private const val DOWNLOAD_URL = "https://www.orimi.com/pdf-test.pdf"

        private const val PAGE_TIMEOUT_MS = 30_000L
        private const val EXT_TIMEOUT_MS = 15_000L
        private const val DOWNLOAD_TIMEOUT_MS = 20_000L

        private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var tinyView: GeckoView
    private lateinit var bigView: GeckoView

    // ------------------------------------------------------------------
    // Gecko state
    // ------------------------------------------------------------------

    private var runtime: GeckoRuntime? = null
    private var extensionInstalled = false
    private var installedExtension: WebExtension? = null
    // Background-owned native port (background.js connectNative). Stored
    // when onConnect fires with session == null; preferred by
    // requestViaPort. See BRIDGE_ROOT_CAUSE_AUDIT.md §5.
    private var nativePort: WebExtension.Port? = null
    private var bgAliveReceived = false
    private var running = false

    private val allSessions = mutableListOf<SpikeSession>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private var reqCounter = 0

    // S-2c state (survives backgrounding; the JSON on disk already says
    // STARTED if the process is killed while backgrounded).
    private var s2cSession: SpikeSession? = null
    private var s2cStarted = false
    private var s2cDone = false
    private var wentBackgroundAt = 0L

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    private val resultsLock = Any()
    private val results = JSONObject()
    private val testsObj = JSONObject()
    private val rssObj = JSONObject()

    // ------------------------------------------------------------------
    // Session holder
    // ------------------------------------------------------------------

    private inner class SpikeSession(val label: String) {
        val session = GeckoSession()
        var title: String? = null
        var lastUrl: String? = null
        var pageStartAt = 0L
        var pageStopAt = 0L
        var pageStopSuccess: Boolean? = null
        var pageStop = CompletableDeferred<Boolean>()
        var autoExtract = CompletableDeferred<JSONObject>()
        var lastExtract: JSONObject? = null
        var port: WebExtension.Port? = null
        var downloadResult = CompletableDeferred<JSONObject>()

        fun open() {
            val rt = runtime ?: error("GeckoRuntime not created")
            session.setProgressDelegate(object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    pageStartAt = System.currentTimeMillis()
                    lastUrl = url
                    log("[$label] onPageStart $url")
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    pageStopAt = System.currentTimeMillis()
                    pageStopSuccess = success
                    log("[$label] onPageStop success=$success (${pageStopAt - pageStartAt} ms)")
                    if (!pageStop.isCompleted) pageStop.complete(success)
                }

                override fun onProgressChange(session: GeckoSession, progress: Int) {
                    // Noisy; not logged.
                }
            })
            session.setContentDelegate(object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    this@SpikeSession.title = title
                    log("[$label] onTitleChange: $title")
                }

                override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                    // WebResponse has NO uri/contentType/contentLength/
                    // filename fields (verified against mozilla-central
                    // WebResponse.java + WebMessage.java): uri + headers
                    // live on the WebMessage parent; body is @Nullable.
                    // v160: content-disposition carries a filename
                    // validated with desktop's save rules (bug 1822968).
                    val uri = response.uri
                    val contentType = response.headers["Content-Type"]
                    val contentLength =
                        response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                    val filename = parseDispositionFilename(
                        response.headers["Content-Disposition"] ?: "",
                    ) ?: uri.substringAfterLast('/').substringBefore('?')
                        .takeIf { it.isNotBlank() }
                    log("[$label] onExternalResponse uri=$uri " +
                        "type=$contentType len=$contentLength " +
                        "file=$filename status=${response.statusCode}")
                    lifecycleScope.launch(Dispatchers.IO) {
                        val saved = saveDownload(response.body, filename, uri)
                        val obj = JSONObject().apply {
                            put("uri", uri)
                            put("contentType", contentType ?: JSONObject.NULL)
                            put("contentLength", contentLength)
                            put("filename", filename ?: JSONObject.NULL)
                            put("savedPath", saved?.absolutePath ?: JSONObject.NULL)
                            put("savedBytes", saved?.length() ?: 0)
                        }
                        if (!downloadResult.isCompleted) downloadResult.complete(obj)
                    }
                }

                override fun onCrash(session: GeckoSession) {
                    log("[$label] CONTENT CRASH")
                    if (!pageStop.isCompleted) pageStop.complete(false)
                }
            })
            session.open(rt)
            // Content-script messages (connectNative / sendNativeMessage
            // from content.js) are delivered ONLY to the per-session
            // delegate — WebExtension.setMessageDelegate receives
            // background-script messages only (WebExtension.java
            // setMessageDelegate javadoc + WebExtensionController.
            // getDelegate(): session != null -> session delegate;
            // session == null + ENV_TYPE_EXTENSION -> extension
            // delegate). Without this call the bridge is deaf: onConnect
            // never fires and auto-extract never arrives. Messages sent
            // before this call are queued by Gecko and released after it
            // (per Mozilla docs), so registering here, before loadUri,
            // has no race. Mozilla's own MessagingExample does exactly
            // this after ensureBuiltIn.
            installedExtension?.let { ext ->
                session.webExtensionController
                    .setMessageDelegate(ext, bridgeDelegate, NATIVE_APP)
                log("[$label] session message delegate set (nativeApp=$NATIVE_APP)")
            } ?: log("[$label] WARNING: no installed extension — session delegate NOT set")
            session.setActive(true)
        }

        fun resetLoadState() {
            pageStartAt = 0L
            pageStopAt = 0L
            pageStopSuccess = null
            pageStop = CompletableDeferred()
            autoExtract = CompletableDeferred()
            lastExtract = null
        }

        fun close() {
            runCatching { session.close() }
        }

        /** Save an onExternalResponse body to filesDir. Runs on IO. */
        private fun saveDownload(body: java.io.InputStream?, filename: String?, uri: String): File? {
            if (body == null) {
                log("[$label] download save FAILED: null body")
                return null
            }
            return runCatching {
                val rawName = filename?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    ?: uri.substringAfterLast('/').substringBefore('?')
                        .takeIf { it.isNotBlank() } ?: "download.bin"
                val safeName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val dir = File(filesDir, "gecko-spike-download").apply { mkdirs() }
                val out = File(dir, safeName)
                FileOutputStream(out).use { fos ->
                    body.copyTo(fos)
                }
                body.close()
                out
            }.onFailure { log("[$label] download save FAILED: $it") }.getOrNull()
        }

        /** filename*=UTF-8''... preferred, else filename="...". */
        private fun parseDispositionFilename(disposition: String): String? {
            val star = Regex(
                "filename\\*\\s*=\\s*UTF-8''([^;\\s]+)",
                RegexOption.IGNORE_CASE,
            ).find(disposition)
            if (star != null) {
                return runCatching {
                    java.net.URLDecoder.decode(star.groupValues[1], "UTF-8")
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            return Regex(
                "filename\\s*=\\s*\"?([^\";]+)\"?",
                RegexOption.IGNORE_CASE,
            ).find(disposition)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle / UI construction
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        results.put("spike", "geckoview-phase0")
        results.put("geckoview", GV_VERSION)
        results.put("androidComponents", AC_VERSION)
        results.put("compileSdk", 36)
        results.put("appVersion", "1.8.0-PRO (44)")
        results.put(
            "device",
            "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})",
        )
        results.put("startedAt", Date().toString())
        results.put("tests", testsObj)
        results.put("rss", rssObj)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val title = TextView(this).apply {
            text = "GeckoView Spike — Phase 0 (S-2–S-8)"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val sub = TextView(this).apply {
            text = "GeckoView $GV_VERSION via AC $AC_VERSION. " +
                "Tap RUN ALL (needs network). Results also land in " +
                "filesDir + external filesDir as gecko-spike-results.json. " +
                "S-2c: tap its button, then Home/lock 60 s, then return."
            textSize = 13f
        }
        val runAllBtn = Button(this).apply {
            text = "RUN ALL SPIKE TESTS"
            setOnClickListener { runAll() }
        }
        val s2cBtn = Button(this).apply {
            text = "S-2c background test (manual step)"
            setOnClickListener { startS2c() }
        }
        val exportBtn = Button(this).apply {
            text = "EXPORT results JSON"
            setOnClickListener {
                writeResults()
                log("Results written (see paths in log).")
                val extPath = getExternalFilesDir(null)?.let {
                    File(it, "gecko-spike-results.json").absolutePath
                } ?: "filesDir/gecko-spike-results.json"
                Toast.makeText(
                    this@GeckoSpikeActivity,
                    "Results JSON saved: $extPath",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        tinyView = GeckoView(this).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
        }
        bigView = GeckoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150),
            )
        }
        logView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        // Log ABOVE the browser view, taking all remaining space: the
        // 400dp browser box used to push the log completely off-screen.
        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(logView)
        }
        root.addView(title)
        root.addView(sub)
        root.addView(runAllBtn)
        root.addView(s2cBtn)
        root.addView(exportBtn)
        root.addView(tinyView)
        root.addView(logScroll)
        root.addView(bigView)
        setContentView(root)

        recordRss("T0-baseline")
        log("GeckoSpike ready. GV $GV_VERSION / AC $AC_VERSION / compileSdk 36.")
        log("Files: filesDir=${filesDir.absolutePath}")
        log("       extDir=${getExternalFilesDir(null)?.absolutePath}")
    }

    override fun onStop() {
        super.onStop()
        if (s2cStarted && !s2cDone) {
            wentBackgroundAt = System.currentTimeMillis()
            log("S-2c: app backgrounded at ${TIME_FMT.format(Date(wentBackgroundAt))}")
            writeResults()
        }
    }

    override fun onStart() {
        super.onStart()
        if (s2cStarted && !s2cDone && wentBackgroundAt > 0L) {
            val returnedAt = System.currentTimeMillis()
            log("S-2c: app returned at ${TIME_FMT.format(Date(returnedAt))} " +
                "(${returnedAt - wentBackgroundAt} ms backgrounded)")
            evaluateS2c(returnedAt)
        }
    }

    override fun onDestroy() {
        allSessions.forEach { it.close() }
        allSessions.clear()
        super.onDestroy()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
        ).toInt()

    // ------------------------------------------------------------------
    // Logging + results
    // ------------------------------------------------------------------

    private fun log(line: String) {
        val stamped = "[${TIME_FMT.format(Date())}] $line"
        Log.i(TAG, line)
        val appendAndScroll = {
            logView.append(stamped + "\n")
            // Keep the newest lines visible as they appear.
            if (::logScroll.isInitialized) {
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendAndScroll()
        } else {
            logView.post { appendAndScroll() }
        }
    }

    private fun record(testId: String, status: String, details: String, ms: Long? = null) {
        val obj = JSONObject().apply {
            put("status", status)
            put("details", details)
            if (ms != null) put("ms", ms)
        }
        synchronized(resultsLock) { testsObj.put(testId, obj) }
        log("RESULT $testId: $status — $details")
        writeResults()
    }

    /** Write gecko-spike-results.json to filesDir + external filesDir. */
    private fun writeResults() {
        // writtenAt + testsCount + diagnostics make every copy
        // self-identifying (the 2026-11-22 18:23 run's uploaded copy was
        // stale — it matched the post-S-5 state; see
        // BRIDGE_ROOT_CAUSE_AUDIT.md §6).
        synchronized(resultsLock) {
            results.put("writtenAt", Date().toString())
            results.put("testsCount", testsObj.length())
            results.put("diagnostics", JSONObject().apply {
                put("bgAlive", bgAliveReceived)
                put(
                    "portKind",
                    if (nativePort != null) "background"
                    else if (allSessions.any { it.port != null }) "session" else "none",
                )
            })
        }
        val snapshot: String = synchronized(resultsLock) { results.toString(2) }
        runCatching {
            File(filesDir, "gecko-spike-results.json").writeText(snapshot)
            log("Wrote filesDir/gecko-spike-results.json")
        }.onFailure { log("Write filesDir results FAILED: $it") }
        runCatching {
            val ext = getExternalFilesDir(null)
            if (ext != null) {
                File(ext, "gecko-spike-results.json").writeText(snapshot)
                log("Wrote external filesDir/gecko-spike-results.json")
            }
        }.onFailure { log("Write external results FAILED: $it") }
    }

    private fun recordRss(stage: String) {
        val pssKb = runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getProcessMemoryInfo(intArrayOf(Process.myPid()))
                ?.firstOrNull()?.totalPss?.toLong() ?: -1L
        }.getOrDefault(-1L)
        val vmRssKb = runCatching {
            File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLong() ?: -1L
        }.getOrDefault(-1L)
        synchronized(resultsLock) {
            rssObj.put(
                stage,
                JSONObject().apply {
                    put("totalPssKb", pssKb)
                    put("vmRssKb", vmRssKb)
                },
            )
        }
        log("RSS $stage: totalPss=${pssKb}KB vmRss=${vmRssKb}KB")
    }

    // ------------------------------------------------------------------
    // GeckoResult -> coroutine bridge
    // ------------------------------------------------------------------

    /**
     * Await a GeckoResult via a CompletableDeferred bridge (no
     * suspendCancellableCoroutine — its overload shape varies across
     * kotlinx-coroutines versions, and the resume/resumeWithException
     * top-level fns moved packages in Kotlin 2.4).
     * NOTE: GeckoResult.accept takes Consumer<T> /
     * Consumer<Throwable> (single method `accept`), NOT
     * OnValueListener/OnExceptionListener (those are for then()).
     * Verified against mozilla-central GeckoResult.java.
     */
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
                        t ?: Exception("unknown GeckoResult error"),
                    )
                }
            },
        )
        return deferred.await()
    }

    // ------------------------------------------------------------------
    // Runtime + extension setup
    // ------------------------------------------------------------------

    // MessageDelegate signatures (160.x, verified against
    // mozilla-central WebExtension.java — the pins doc's older
    // (source, message, session) form is OUTDATED):
    //   onMessage(String nativeApp, Object message, MessageSender sender)
    //   onConnect(Port port)   — session comes from port.sender.session
    //   (null when the sender is a background script).
    // MessageSender carries session (@Nullable), url, environmentType
    // (ENV_TYPE_CONTENT_SCRIPT vs ENV_TYPE_EXTENSION), isTopLevel().
    private val bridgeDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? {
            (message as? JSONObject)?.let { handleBridgeMessage(it, sender.session) }
            return null
        }

        override fun onConnect(port: WebExtension.Port) {
            val session = port.sender.session
            if (session == null) {
                // Background-script port (background.js owns the native
                // port — see BRIDGE_ROOT_CAUSE_AUDIT.md §5). There is no
                // SpikeSession for it; keep it as the shared native port
                // for all requests. The old code dropped session==null
                // ports ("keeping no reference") — that was a bug for the
                // background-port design.
                nativePort = port
                log("Bridge port connected (session=false) — background native port stored")
            } else {
                log("Bridge port connected (session=true)")
                val owner = allSessions.firstOrNull { it.session == session }
                if (owner != null) {
                    owner.port = port
                    log("[${owner.label}] native port attached")
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
                    val owner = allSessions.firstOrNull { it.session == port.sender.session }
                    if (owner != null && owner.port == port) owner.port = null
                }
            })
        }
    }

    private fun handleBridgeMessage(msg: JSONObject, session: GeckoSession?) {
        val id = msg.optString("id", "")
        val type = msg.optString("type", "?")
        if (type == "bg-alive") {
            bgAliveReceived = true
            log("Bridge bg-alive received (one-shot path works — background.js runs)")
            return
        }
        if (id.isNotEmpty()) {
            pending.remove(id)?.let { deferred ->
                if (!deferred.isCompleted) deferred.complete(msg)
                return
            }
        }
        if (type == "extract") {
            val owner = allSessions.firstOrNull { it.session == session }
                ?: allSessions.lastOrNull()
            if (owner != null) {
                owner.lastExtract = msg
                if (!owner.autoExtract.isCompleted) owner.autoExtract.complete(msg)
                log("[${owner.label}] auto-extract url=${msg.optString("url")} " +
                    "title=${msg.optString("title")} captcha=${msg.optString("captcha")} " +
                    "textLen=${msg.optInt("textLen", -1)}")
            }
        } else {
            log("Bridge msg type=$type id=$id (no waiter)")
        }
    }

    private suspend fun ensureRuntime(): Boolean {
        if (runtime != null && extensionInstalled) return true
        val t0 = System.currentTimeMillis()
        if (runtime == null) {
            log("Creating GeckoRuntime…")
            runtime = runCatching { GeckoRuntime.create(applicationContext) }
                .onFailure { log("GeckoRuntime.create FAILED: $it") }
                .getOrNull() ?: return false
            log("GeckoRuntime created in ${System.currentTimeMillis() - t0} ms")
        }
        if (!extensionInstalled) {
            log("Installing built-in extension $EXT_URI …")
            val ext = runCatching {
                withTimeout(EXT_TIMEOUT_MS) {
                    runtime!!.webExtensionController
                        .ensureBuiltIn(EXT_URI, EXT_ID).awaitValue()
                }
            }.onFailure { log("Extension install FAILED: $it") }.getOrNull()
            if (ext == null) return false
            ext.setMessageDelegate(bridgeDelegate, NATIVE_APP)
            installedExtension = ext
            extensionInstalled = true
            log("Extension installed + MessageDelegate set (nativeApp=$NATIVE_APP)")
        }
        return true
    }

    // ------------------------------------------------------------------
    // Bridge request/response
    // ------------------------------------------------------------------

    private fun nextId(prefix: String): String {
        reqCounter += 1
        return "$prefix-$reqCounter"
    }

    private suspend fun requestViaPort(
        spike: SpikeSession,
        msg: JSONObject,
        timeoutMs: Long = EXT_TIMEOUT_MS,
    ): JSONObject? {
        val id = msg.optString("id", "")
        // Background-owned port first (background.js owns the native port);
        // fall back to the per-session port (content-script port, legacy path).
        val port = nativePort ?: spike.port
        if (port == null) {
            log("[${spike.label}] request $id FAILED: no native port yet")
            return null
        }
        val deferred = CompletableDeferred<JSONObject>()
        if (id.isNotEmpty()) pending[id] = deferred
        runCatching { port.postMessage(msg) }
            .onFailure {
                log("[${spike.label}] postMessage FAILED: $it")
                pending.remove(id)
                return null
            }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            log("[${spike.label}] request $id TIMEOUT after ${timeoutMs}ms")
            null
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun requestExtract(spike: SpikeSession): JSONObject? {
        val msg = JSONObject().apply {
            put("type", "extract")
            put("id", nextId("extract"))
        }
        return requestViaPort(spike, msg)
    }

    /**
     * The native port attaches when the page's content script connects
     * (document_idle — usually around pageStop, but not guaranteed
     * before it). Poll briefly before any requestViaPort so a
     * just-loaded page is not failed for a port that lands 200 ms later.
     */
    private suspend fun awaitPort(spike: SpikeSession, timeoutMs: Long = 10_000L): Boolean {
        val t0 = System.currentTimeMillis()
        while (nativePort == null && spike.port == null && System.currentTimeMillis() - t0 < timeoutMs) {
            kotlinx.coroutines.delay(200)
        }
        if (nativePort == null && spike.port == null) log("[${spike.label}] no native port after ${timeoutMs}ms")
        return nativePort != null || spike.port != null
    }

    private suspend fun requestCaptcha(spike: SpikeSession): JSONObject? {
        val msg = JSONObject().apply {
            put("type", "captcha")
            put("id", nextId("captcha"))
        }
        return requestViaPort(spike, msg)
    }

    // ------------------------------------------------------------------
    // Page loading
    // ------------------------------------------------------------------

    private fun newSpikeSession(label: String): SpikeSession {
        val spike = SpikeSession(label)
        allSessions.add(spike)
        spike.open()
        return spike
    }

    private suspend fun loadAndWait(
        spike: SpikeSession,
        url: String,
        timeoutMs: Long = PAGE_TIMEOUT_MS,
    ): Boolean {
        spike.resetLoadState()
        val t0 = System.currentTimeMillis()
        spike.session.loadUri(url)
        return try {
            val ok = withTimeout(timeoutMs) { spike.pageStop.await() }
            log("[${spike.label}] load $url -> pageStop=$ok in ${System.currentTimeMillis() - t0} ms")
            ok
        } catch (e: TimeoutCancellationException) {
            log("[${spike.label}] load $url TIMEOUT after ${timeoutMs}ms (no onPageStop)")
            false
        }
    }

    /** Wait for the content script's auto-extract (proves JS ran). */
    private suspend fun awaitAutoExtract(
        spike: SpikeSession,
        timeoutMs: Long = EXT_TIMEOUT_MS,
    ): JSONObject? {
        return try {
            withTimeout(timeoutMs) { spike.autoExtract.await() }
        } catch (e: TimeoutCancellationException) {
            log("[${spike.label}] auto-extract TIMEOUT after ${timeoutMs}ms")
            null
        }
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    private fun runAll() {
        if (running) {
            log("Already running — ignored.")
            return
        }
        running = true
        log("RUN ALL started — starting Gecko engine (this can take a while)...")
        lifecycleScope.launch {
            try {
                closeAllSessions()
                recordRss("T0-baseline")
                if (!ensureRuntime()) {
                    record("S-2a", "FAIL", "GeckoRuntime/extension setup failed — cannot run phone tests")
                    return@launch
                }
                log("Starting S-2a (headless session)...")
                testS2a()
                log("Starting S-2b (1x1 view session)...")
                testS2b()
                log("Starting S-3 (content extraction)...")
                testS3()
                log("Starting S-4 (captcha detection)...")
                testS4()
                log("Starting S-5 (cookies)...")
                testS5()
                log("Starting S-6 (downloads)...")
                testS6()
                log("Starting S-7 (screenshots)...")
                testS7()
                log("Starting S-8 (RAM)...")
                testS8()
                log("RUN ALL finished. See results above + gecko-spike-results.json.")
            } catch (e: Exception) {
                log("RUN ALL aborted with exception: $e")
            } finally {
                running = false
                writeResults()
            }
        }
    }

    private fun closeAllSessions() {
        // Release views BEFORE closing sessions (setSession refuses /
        // misbehaves when a previous open session is still attached).
        runCatching { tinyView.releaseSession() }
        runCatching { bigView.releaseSession() }
        allSessions.forEach { it.close() }
        allSessions.clear()
    }

    /** S-2 variant (a): unattached session + setActive, no GeckoView. */
    private suspend fun testS2a() {
        val t0 = System.currentTimeMillis()
        log("=== S-2a: unattached GeckoSession + setActive ===")
        val spike = newSpikeSession("S-2a")
        val stopped = loadAndWait(spike, STATIC_URL)
        val extract = awaitAutoExtract(spike)
        val title = spike.title
        val ok = stopped && title?.isNotBlank() == true && extract != null &&
            extract.optString("text", "").isNotBlank()
        record(
            "S-2a",
            if (ok) "PASS" else "FAIL",
            "pageStop=$stopped title=${title ?: "null"} " +
                "extract=${extract != null} extractTitle=${extract?.optString("title") ?: "null"} " +
                "captcha=${extract?.optString("captcha") ?: "null"}",
            System.currentTimeMillis() - t0,
        )
        recordRss("S-2a-after-1-page")
        spike.close()
    }

    /** S-2 variant (b): 1x1 invisible GeckoView inside this activity. */
    private suspend fun testS2b() {
        val t0 = System.currentTimeMillis()
        log("=== S-2b: 1x1 GeckoView attached ===")
        val spike = newSpikeSession("S-2b")
        tinyView.setSession(spike.session)
        val stopped = loadAndWait(spike, STATIC_URL)
        val extract = awaitAutoExtract(spike)
        val title = spike.title
        val ok = stopped && title?.isNotBlank() == true && extract != null &&
            extract.optString("text", "").isNotBlank()
        record(
            "S-2b",
            if (ok) "PASS" else "FAIL",
            "pageStop=$stopped title=${title ?: "null"} " +
                "extract=${extract != null} extractTitle=${extract?.optString("title") ?: "null"}",
            System.currentTimeMillis() - t0,
        )
        runCatching { tinyView.releaseSession() }
        spike.close()
    }

    /** S-3: extraction round-trip on 3 pages (static, SPA, Google search). */
    private suspend fun testS3() {
        val t0 = System.currentTimeMillis()
        log("=== S-3: extraction round-trip (3 pages) ===")
        val spike = newSpikeSession("S-3")
        tinyView.setSession(spike.session)
        val pages = listOf(
            "static" to STATIC_URL,
            "spa" to SPA_URL,
            "google" to GOOGLE_URL,
        )
        val lines = mutableListOf<String>()
        var allOk = true
        for ((kind, url) in pages) {
            val stopped = loadAndWait(spike, url)
            awaitPort(spike)
            val extract = if (stopped) {
                requestExtract(spike) ?: awaitAutoExtract(spike)
            } else {
                awaitAutoExtract(spike)
            }
            val gotUrl = extract?.optString("url", "") ?: ""
            val gotTitle = extract?.optString("title", "") ?: ""
            val gotText = extract?.optString("text", "") ?: ""
            // B-C4-equivalent guard: the response must carry the URL that
            // was requested — a wrong-page response is a FAIL, never data.
            val urlOk = gotUrl == url
            val ok = stopped && urlOk && gotTitle.isNotBlank() && gotText.isNotBlank()
            if (!ok) allOk = false
            lines.add(
                "$kind: pageStop=$stopped urlOk=$urlOk " +
                    "title='$gotTitle' textChars=${gotText.length} gotUrl='$gotUrl'",
            )
        }
        record(
            "S-3",
            if (allOk) "PASS" else "FAIL",
            lines.joinToString(" | "),
            System.currentTimeMillis() - t0,
        )
        recordRss("S-3-after-3-pages-1-tab")
        runCatching { tinyView.releaseSession() }
        spike.close()
    }

    /** S-4: captcha detection parity on the Turnstile demo page. */
    private suspend fun testS4() {
        val t0 = System.currentTimeMillis()
        log("=== S-4: captcha detection (Turnstile demo) ===")
        val spike = newSpikeSession("S-4")
        tinyView.setSession(spike.session)
        val stopped = loadAndWait(spike, TURNSTILE_URL)
        awaitPort(spike)
        val extract = if (stopped) {
            requestExtract(spike) ?: awaitAutoExtract(spike)
        } else {
            null
        }
        val captchaMsg = requestCaptcha(spike)
        val viaExtract = extract?.optString("captcha", "null")
        val viaRequest = captchaMsg?.optString("captcha", "null")
        // Today's WebView check on this page must say "turnstile"
        // (same selectors). Parity = the Gecko content script says so too.
        val ok = stopped && viaExtract == "turnstile" && viaRequest == "turnstile"
        record(
            "S-4",
            if (ok) "PASS" else "FAIL",
            "pageStop=$stopped captchaViaExtract=$viaExtract " +
                "captchaViaRequest=$viaRequest expected=turnstile",
            System.currentTimeMillis() - t0,
        )
        runCatching { tinyView.releaseSession() }
        spike.close()
    }

    /** S-5: cookies read/write/read-back via the extension cookies API. */
    private suspend fun testS5() {
        val t0 = System.currentTimeMillis()
        log("=== S-5: cookies via extension ===")
        val spike = newSpikeSession("S-5")
        tinyView.setSession(spike.session)
        val stopped = loadAndWait(spike, COOKIE_URL)
        var detail = "pageStop=$stopped"
        var ok = false
        if (stopped) {
            awaitPort(spike)
            val value = "s5-${System.currentTimeMillis()}"
            val setMsg = JSONObject().apply {
                put("type", "cookie-set")
                put("id", nextId("cookie-set"))
                put("url", COOKIE_URL)
                put("name", "jarvis-spike")
                put("value", value)
            }
            val setRes = requestViaPort(spike, setMsg)
            val setOk = setRes?.optBoolean("ok", false) == true
            val getMsg = JSONObject().apply {
                put("type", "cookie-get")
                put("id", nextId("cookie-get"))
                put("url", COOKIE_URL)
            }
            val getRes = requestViaPort(spike, getMsg)
            val cookies = getRes?.optJSONArray("cookies")
            var readBack: String? = null
            if (cookies != null) {
                for (i in 0 until cookies.length()) {
                    val c = cookies.optJSONObject(i) ?: continue
                    if (c.optString("name") == "jarvis-spike") readBack = c.optString("value")
                }
            }
            ok = setOk && getRes?.optBoolean("ok", false) == true && readBack == value
            detail = "pageStop=$stopped setOk=$setOk " +
                "getOk=${getRes?.optBoolean("ok", false)} " +
                "readBack=${readBack ?: "null"} expected=$value"
        }
        record("S-5", if (ok) "PASS" else "FAIL", detail, System.currentTimeMillis() - t0)
        runCatching { tinyView.releaseSession() }
        spike.close()
    }

    /** S-6: downloads via onExternalResponse. */
    private suspend fun testS6() {
        val t0 = System.currentTimeMillis()
        log("=== S-6: download via onExternalResponse ===")
        val spike = newSpikeSession("S-6")
        spike.session.loadUri(DOWNLOAD_URL)
        val res = try {
            withTimeout(DOWNLOAD_TIMEOUT_MS) { spike.downloadResult.await() }
        } catch (e: TimeoutCancellationException) {
            null
        }
        val savedBytes = res?.optLong("savedBytes", 0) ?: 0
        val contentLen = res?.optLong("contentLength", -1) ?: -1
        val ok = res != null && savedBytes > 0 &&
            (contentLen <= 0 || savedBytes == contentLen)
        record(
            "S-6",
            if (ok) "PASS" else "FAIL",
            if (res == null) {
                "onExternalResponse did NOT fire within ${DOWNLOAD_TIMEOUT_MS}ms for $DOWNLOAD_URL"
            } else {
                "uri=${res.optString("uri")} filename=${res.optString("filename")} " +
                    "contentLength=$contentLen savedBytes=$savedBytes " +
                    "path=${res.optString("savedPath")}"
            },
            System.currentTimeMillis() - t0,
        )
        spike.close()
    }

    /** S-7: screenshot via capturePixels / captureFullPage. */
    private suspend fun testS7() {
        val t0 = System.currentTimeMillis()
        log("=== S-7: screenshot (capturePixels / captureFullPage) ===")
        val spike = newSpikeSession("S-7")
        bigView.setSession(spike.session)
        val stopped = loadAndWait(spike, STATIC_URL)
        var detail = "pageStop=$stopped"
        var ok = false
        if (stopped) {
            val dir = File(filesDir, "gecko-spike-shots").apply { mkdirs() }
            val px = runCatching {
                withTimeout(EXT_TIMEOUT_MS) { bigView.capturePixels().awaitValue() }
            }.onFailure { log("[S-7] capturePixels FAILED: $it") }.getOrNull()
            var pxLine = "capturePixels=null"
            if (px != null) {
                val f = File(dir, "capturePixels.png")
                runCatching {
                    FileOutputStream(f).use { px.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                pxLine = "capturePixels=${px.width}x${px.height} -> ${f.absolutePath} (${f.length()} bytes)"
            }
            val full = runCatching {
                withTimeout(EXT_TIMEOUT_MS) { bigView.captureFullPage().awaitValue() }
            }.onFailure { log("[S-7] captureFullPage FAILED: $it") }.getOrNull()
            var fullLine = "captureFullPage=null"
            if (full != null) {
                val f = File(dir, "captureFullPage.png")
                runCatching {
                    FileOutputStream(f).use { full.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                fullLine = "captureFullPage=${full.width}x${full.height} -> ${f.absolutePath} (${f.length()} bytes)"
            }
            ok = (px != null && px.width > 0 && px.height > 0) ||
                (full != null && full.width > 0 && full.height > 0)
            detail = "pageStop=$stopped | $pxLine | $fullLine"
        }
        record("S-7", if (ok) "PASS" else "FAIL", detail, System.currentTimeMillis() - t0)
        runCatching { bigView.releaseSession() }
        spike.close()
    }

    /** S-8: RAM — process RSS at each stage (informational, no hard cap). */
    private suspend fun testS8() {
        val t0 = System.currentTimeMillis()
        log("=== S-8: RAM (RSS snapshots) ===")
        // Three tabs open at once, each with a page loaded.
        val tabs = listOf(
            newSpikeSession("S-8-tab1"),
            newSpikeSession("S-8-tab2"),
            newSpikeSession("S-8-tab3"),
        )
        tinyView.setSession(tabs[0].session)
        loadAndWait(tabs[0], STATIC_URL)
        loadAndWait(tabs[1], SPA_URL)
        loadAndWait(tabs[2], GOOGLE_URL)
        recordRss("S-8-3-tabs-loaded")
        // Background extraction running on one tab while 3 are open.
        awaitPort(tabs[0])
        val extract = requestExtract(tabs[0])
        recordRss("S-8-3-tabs-plus-extraction")
        val extractOk = extract != null && extract.optString("text", "").isNotBlank()
        record(
            "S-8",
            "RECORDED",
            "3 tabs loaded; extraction during 3-tab run ok=$extractOk; " +
                "see rss{} object for totalPssKb/vmRssKb at T0/S-2a/S-3/S-8 stages. " +
                "No hard cap frozen in PLAN — number goes to parent.",
            System.currentTimeMillis() - t0,
        )
        runCatching { tinyView.releaseSession() }
        tabs.forEach { it.close() }
    }

    // ------------------------------------------------------------------
    // S-2 variant (c): app-backgrounded run (manual step, no overlay)
    // ------------------------------------------------------------------

    private fun startS2c() {
        if (s2cStarted && !s2cDone) {
            log("S-2c already running — background the app, wait 60 s, return.")
            return
        }
        if (running) {
            log("A run is in progress — wait for it, then tap S-2c.")
            return
        }
        s2cStarted = true
        s2cDone = false
        wentBackgroundAt = 0L
        log("S-2c starting — loading page, then background the app when told...")
        lifecycleScope.launch {
            if (!ensureRuntime()) {
                record("S-2c", "FAIL", "GeckoRuntime/extension setup failed")
                s2cDone = true
                return@launch
            }
            val spike = newSpikeSession("S-2c")
            s2cSession = spike
            // Load a slow, JS-heavy page so it is still in flight when
            // the user backgrounds the app.
            spike.resetLoadState()
            spike.session.loadUri(SPA_URL)
            record(
                "S-2c",
                "STARTED",
                "Unattached session loading $SPA_URL. NOW press Home or lock " +
                    "the screen, wait 60 s, then return to this app. " +
                    "Result is evaluated on return (onStart).",
            )
            log("S-2c: loading started. Background the app NOW (Home / lock), wait 60 s, return.")
        }
    }

    private fun evaluateS2c(returnedAt: Long) {
        val spike = s2cSession ?: return
        s2cDone = true
        val bgMs = returnedAt - wentBackgroundAt
        val finishedBeforeBg = spike.pageStopAt > 0 && spike.pageStopAt < wentBackgroundAt
        val finishedWhileBg =
            spike.pageStopAt in wentBackgroundAt..returnedAt
        val extract = spike.lastExtract
        val detail = "backgroundedMs=$bgMs pageStop=${spike.pageStopSuccess} " +
            "pageStopAt=${if (spike.pageStopAt > 0) TIME_FMT.format(Date(spike.pageStopAt)) else "never"} " +
            "finishedBeforeBg=$finishedBeforeBg finishedWhileBg=$finishedWhileBg " +
            "title=${spike.title ?: "null"} extract=${extract != null}"
        val status = when {
            finishedBeforeBg -> "INCONCLUSIVE"
            spike.pageStopSuccess == true && extract != null -> "PASS"
            else -> "FAIL"
        }
        val note = if (status == "INCONCLUSIVE") {
            " (page finished BEFORE backgrounding — re-run and background faster)"
        } else ""
        record("S-2c", status, detail + note)
        spike.close()
        s2cSession = null
    }
}
