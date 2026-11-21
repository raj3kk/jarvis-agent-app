package com.jarvis.autonomous.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.jarvis.autonomous.agent.JarvisAgentEngine
import com.jarvis.autonomous.ui.MainActivity
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Control-plane WebSocket client (Phase 2, stage 2b-2) — phone -> hub.
 *
 * Protocol (frozen, server `src/do/JarvisHub.ts` + `src/crypto.ts`):
 * - Connect `wss://<HUB_URL>/ws/<device_id>` (paired device only; the
 *   hub answers 403 `device_not_paired` otherwise).
 * - On open, send a SIGNED HELLO at once (the DO closes unauthenticated
 *   sockets after 30s). Canonical signed string (exact):
 *   `device_id + "\n" + nonce + "\n" + ts + "\n" + payload_raw`.
 *   nonce = 32-hex SecureRandom (128-bit), ts = unix ms. Signature =
 *   Ed25519 via PairingManager.sign (Keystore private key, never
 *   leaves the phone). `payload_raw` is the EXACT string signed —
 *   never re-serialized (key-order fragility).
 * - HELLO_ACK -> CONNECTED; the hub flushes queued pending_steps.
 * - EXECUTE_STEP (hub -> device, currently only CRON_COMMAND from the
 *   cron dispatcher) is SIGNED by the hub (M-4, hub 3b.1+) and VERIFIED
 *   here before any execution (HubStepVerifier — 7 checks: key_id,
 *   device_id, ts ±5 min, nonce one-time, step_ts ≤24h, Ed25519 sig,
 *   payload_raw parses with kind + job_id). ANY failure -> step
 *   REJECTED, never executed; a signed STEP_RESULT (ok=false,
 *   "Hub signature verify fail — step rejected") goes back. The
 *   `step` envelope field is never executed from — only payload_raw.
 *   (The old note here said the hub does NOT sign — that rationale
 *   is SUPERSEDED by M-4; the hub signs with its OWN key, the device
 *   key is never involved.)
 * - HELLO_ACK bootstrap (M-4 §4.4): the hub sends its public key +
 *   key_id. No stored key -> store it (first-seen). Stored key with
 *   the SAME key_id -> ignore (confirm). A NEW key_id from HELLO_ACK
 *   is NEVER accepted (rotation is re-pair-only) — on mismatch the
 *   client stops with UNAUTH "Hub key badli — dobara pair karo".
 * - CAPTCHA_ASSIST (3c, D3 human-assist-first): a display-only event
 *   from the hub (server-side executor path) — the phone shows
 *   CaptchaAssistCard (live canvas + 60s countdown) and notifies.
 *   The phone ALSO reports its own WebView captchas to the hub via
 *   signed CAPTCHA_DETECTED / CAPTCHA_RESOLVED messages. A captcha
 *   solution/token value is NEVER sent, logged, or stored.
 * - Presence = the WS connection itself (C1). Reconnect backoff
 *   5s -> 10s -> 20s -> 30s cap (1.5.0 L1 pattern), reset on HELLO_ACK.
 *
 * Honesty rules (1.4.25 false-completion lesson):
 * - STEP_RESULT ok=true ONLY after the engine's task is observed
 *   finished (isTaskRunning false, 180s timeout). Calling
 *   handleUserCommand alone is NOT evidence — that would be the H1
 *   hole in a new shape.
 * - A remote command NEVER interrupts the user's live task: if the
 *   engine is already running when EXECUTE_STEP arrives, the step is
 *   refused (ok=false, "Engine busy").
 * - Command TEXT is never logged, never in diagnostics, never in a
 *   STEP_RESULT summary. Diagnostics carry codes + step kind only.
 *
 * Lifecycle: single-use instance, owned by JarvisForegroundService
 * (created in onCreate after the engine, stopped in onDestroy).
 * `instance` is the process-wide handle the UI (PairingCard) reads
 * for the WS status line. No new permission, no new dependency
 * (OkHttp was already there).
 */
class HubWsClient(
    private val context: Context,
    private val engine: JarvisAgentEngine,
    private val prefs: JarvisPreferences
) {
    enum class WsState { OFFLINE, CONNECTING, CONNECTED, UNAUTH, NOT_PAIRED }

    data class WsStatus(
        val state: WsState = WsState.OFFLINE,
        val lastHelloAckAtMs: Long = 0L,
        val lastStepResultAtMs: Long = 0L,
        val reconnectAttempt: Int = 0,
        val detail: String = ""
    )

    // Instance view of the process-wide status (same flow as the
    // companion's statusFlow — one connection per process).
    val status: StateFlow<WsStatus> = statusFlow

    companion object {
        @Volatile
        var instance: HubWsClient? = null
            private set

        fun setInstance(client: HubWsClient?) {
            instance = client
        }

        // Process-wide WS status (single source of truth). The UI
        // (PairingCard) collects this flow directly — the flow
        // identity never changes, so the card always sees updates
        // even if the client instance is created after the card.
        // When no client exists the status is OFFLINE ("Band hai").
        private val _status = MutableStateFlow(WsStatus())
        val statusFlow: StateFlow<WsStatus> = _status.asStateFlow()

        private const val PING_INTERVAL_MS = 60_000L
        private const val HELLO_TIMEOUT_MS = 30_000L
        private const val STEP_TIMEOUT_MS = 180_000L
        private val BACKOFF_MS = longArrayOf(5_000L, 10_000L, 20_000L, 30_000L)
        private const val APP_VERSION = "1.7.0"
        private const val CAPTCHA_NOTIFICATION_ID = 9101
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // WS stays open; app-level JSON PING keeps it alive
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var helloWatchdogJob: Job? = null
    private var stepJob: Job? = null
    private var attempt = 0

    @Volatile
    private var started = false

    @Volatile
    private var stopRequested = false

    @Volatile
    private var terminalStop = false

    private var deviceId: String = ""

    fun start() {
        if (started) return
        started = true
        stopRequested = false
        terminalStop = false
        if (!prefs.pairingPaired) {
            setStatus(WsState.NOT_PAIRED, detail = "Paired nahi hai")
            return
        }
        deviceId = runCatching { PairingManager.deviceId(context) }.getOrDefault("")
        if (deviceId.isBlank()) {
            setStatus(WsState.NOT_PAIRED, detail = "Device id nahi mila")
            return
        }
        reconnectJob = scope.launch { connectLoop() }
    }

    fun stop() {
        stopRequested = true
        reconnectJob?.cancel()
        pingJob?.cancel()
        helloWatchdogJob?.cancel()
        stepJob?.cancel()
        runCatching { webSocket?.close(1000, "stopping") }
        webSocket = null
        scope.cancel()
        setStatus(WsState.OFFLINE, detail = "Band hai")
    }

    // ---- Connection loop ----

    private suspend fun connectLoop() {
        while (!stopRequested && !terminalStop) {
            if (!prefs.pairingPaired) {
                setStatus(WsState.NOT_PAIRED, detail = "Paired nahi hai")
                return
            }
            setStatus(WsState.CONNECTING, reconnectAttempt = attempt)
            diag("WS_CONNECT", "Hub WS connect try #${attempt + 1}")
            val terminal = connectOnce()
            if (stopRequested || terminalStop || terminal) return
            val delayMs = BACKOFF_MS[minOf(attempt, BACKOFF_MS.lastIndex)]
            attempt++
            diag("WS_RECONNECT", "Dobara ${delayMs}ms me (try #${attempt + 1})")
            delay(delayMs)
        }
    }

    /**
     * One socket lifetime. Suspends until it closes/fails.
     * Returns true when the failure is TERMINAL (do not reconnect).
     */
    private suspend fun connectOnce(): Boolean {
        val closed = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val wsUrl = "wss://${PairingManager.HUB_URL.removePrefix("https://")}/ws/$deviceId"
        val request = Request.Builder().url(wsUrl).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                webSocket = ws
                startPingLoop()
                startHelloWatchdog()
                sendHello()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                scope.launch { onTextMessage(text) }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                onSocketEnded(code, "closed")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: -1
                if (code == 403) {
                    // Hub: device_not_paired — pairing dobara karo, retry loop nahi.
                    terminalStop = true
                    setStatus(WsState.NOT_PAIRED, detail = "Paired nahi hai (hub ne mana kiya)")
                    diag("WS_NOT_PAIRED", "Hub upgrade 403 device_not_paired")
                }
                onSocketEnded(code, "failed")
            }

            private fun onSocketEnded(code: Int, how: String) {
                pingJob?.cancel()
                helloWatchdogJob?.cancel()
                if (webSocket != null) {
                    webSocket = null
                }
                if (code == 4401) {
                    // DO: unauthorized (bad signature / replay / wrong device / hello timeout).
                    terminalStop = true
                    setStatus(WsState.UNAUTH, detail = "Pairing dobara karo")
                    diag("WS_UNAUTH", "Socket 4401 unauthorized ($how)")
                } else if (!terminalStop) {
                    setStatus(WsState.OFFLINE, detail = "Connection toota ($how, code $code)")
                    diag("WS_CLOSE", "Socket $how, code $code")
                }
                if (!closed.isCompleted) closed.complete(terminalStop)
            }
        }
        httpClient.newWebSocket(request, listener)
        return closed.await()
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (!stopRequested) {
                delay(PING_INTERVAL_MS)
                val sent = runCatching {
                    webSocket?.send("{\"type\":\"PING\"}") ?: false
                }.getOrDefault(false)
                if (!sent) return@launch
            }
        }
    }

    private fun startHelloWatchdog() {
        helloWatchdogJob?.cancel()
        helloWatchdogJob = scope.launch {
            delay(HELLO_TIMEOUT_MS)
            // No HELLO_ACK in 30s — the DO kills unauthenticated sockets
            // at 30s anyway; close first so reconnect is clean.
            if (_status.value.state == WsState.CONNECTING) {
                diag("WS_HELLO_TIMEOUT", "HELLO_ACK 30s me nahi aaya")
                runCatching { webSocket?.close(1000, "hello_timeout") }
            }
        }
    }

    // ---- Messages ----

    private fun onTextMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: run {
            diag("WS_BAD_JSON", "Hub se kharab message aaya")
            return
        }
        when (json.optString("type", "")) {
            "HELLO_ACK" -> {
                helloWatchdogJob?.cancel()
                attempt = 0
                // M-4 §4.4 HELLO_ACK bootstrap: the hub sends its public
                // key + key_id. No stored key -> store (first-seen).
                // Same key_id -> confirm, ignore. A NEW key_id is NEVER
                // accepted from HELLO_ACK (rotation is re-pair-only).
                val ackKey = json.optString("hub_public_key", "").ifBlank { null }
                val ackKeyId = json.optString("key_id", "").ifBlank { null }
                if (ackKey != null && ackKeyId != null) {
                    val storedId = runCatching { PairingManager.hubKeyId(context) }.getOrNull()
                    val storedKey = runCatching { PairingManager.hubPublicKeyB64(context) }.getOrNull()
                    when {
                        storedKey == null -> {
                            runCatching { PairingManager.storeHubPublicKey(context, ackKey, ackKeyId) }
                            diag("WS_HUB_KEY_BOOTSTRAP", "Hub key HELLO_ACK se mili (first-seen)")
                        }
                        storedId == ackKeyId -> {
                            // Same key again — confirm, nothing to do.
                        }
                        else -> {
                            terminalStop = true
                            setStatus(WsState.UNAUTH, detail = "Hub key badli — dobara pair karo")
                            diag("WS_HUB_KEY_CHANGED", "Hub key_id badla — re-pair chahiye")
                            runCatching { webSocket?.close(1000, "hub_key_changed") }
                            return
                        }
                    }
                }
                setStatus(
                    WsState.CONNECTED,
                    lastHelloAckAtMs = System.currentTimeMillis(),
                    reconnectAttempt = 0,
                    detail = "Juda hua"
                )
                diag("WS_HELLO_ACK", "Hub se HELLO_ACK mila")
            }
            "EXECUTE_STEP" -> {
                // M-4 §6.2: verify FIRST, execute only on full pass.
                handleSignedExecuteStep(json)
            }
            "CAPTCHA_ASSIST" -> {
                // 3c (D3): display-only event from the hub — show the
                // canvas + notify. Nothing here executes anything.
                val taskId = json.optString("task_id", "")
                val site = json.optString("site", "")
                val taskName = json.optString("task_name", "")
                val screenshotB64 = json.optString("screenshot_b64", "").ifBlank { null }
                if (taskId.isBlank()) {
                    diag("WS_CAPTCHA_BAD", "CAPTCHA_ASSIST bina task_id ke aaya")
                } else {
                    JarvisStateRepository.setCaptchaAssist(
                        CaptchaAssistState(
                            taskId = taskId,
                            site = site,
                            taskName = taskName,
                            screenshotB64 = screenshotB64,
                            receivedAtMs = System.currentTimeMillis(),
                            status = CaptchaStatus.ACTIVE,
                            source = CaptchaSource.HUB_EVENT,
                            assistCount = 1
                        )
                    )
                    diag("WS_CAPTCHA_ASSIST", "Captcha assist aaya ($site)")
                    postCaptchaNotification(site, taskName)
                }
            }
            "PING" -> {
                runCatching { webSocket?.send("{\"type\":\"PONG\"}") }
            }
            "ACK" -> {
                diag("WS_ACK", "Hub ne STEP_RESULT ack kiya")
            }
            else -> {
                diag("WS_UNKNOWN", "Hub se anjaan message type")
            }
        }
    }

    private fun handleExecuteStep(step: JSONObject) {
        val kind = step.optString("kind", "")
        val jobId = step.optString("job_id", "").ifBlank { "unknown" }
        if (kind != "CRON_COMMAND") {
            sendStepResult(jobId, false, "Unknown step kind: $kind")
            return
        }
        val command = step.optString("command", "")
        if (command.isBlank()) {
            sendStepResult(jobId, false, "Empty command")
            return
        }
        if (engine.isTaskRunning() || stepJob?.isActive == true) {
            sendStepResult(jobId, false, "Engine busy — command baad me dobara bhejo")
            return
        }
        stepJob = scope.launch {
            // Command TEXT is never logged (privacy) — diagnostics
            // carry the step kind only.
            diag("WS_STEP_START", "CRON_COMMAND execution shuru hua")
            engine.handleUserCommand(command)
            // Completion observation (GAP-2): a task path assigns
            // activeTaskJob synchronously, but a command can also
            // resolve without starting a task (synchronous handling)
            // — so wait up to 5s for a task to appear; if none
            // appears, the command resolved synchronously and
            // "finished" is honest.
            val graceDeadline = System.currentTimeMillis() + 5_000L
            var sawRunning = engine.isTaskRunning()
            while (!sawRunning && System.currentTimeMillis() < graceDeadline) {
                delay(1_000)
                sawRunning = engine.isTaskRunning()
            }
            if (sawRunning) {
                val deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS
                while (engine.isTaskRunning() && System.currentTimeMillis() < deadline) {
                    delay(2_000)
                }
            }
            if (engine.isTaskRunning()) {
                sendStepResult(jobId, false, "Task timeout (180s) — result unknown")
            } else {
                sendStepResult(jobId, true, "Task finished")
            }
        }
    }

    /**
     * M-4 §6.2: verify a hub-signed EXECUTE_STEP BEFORE executing.
     * Any check failing -> REJECT (never execute) + a signed
     * STEP_RESULT (ok=false) with a reason-code summary — the sig /
     * nonce VALUES never appear in the summary or diagnostics.
     */
    private fun handleSignedExecuteStep(envelope: JSONObject) {
        val storedKey = runCatching { PairingManager.hubPublicKeyB64(context) }.getOrNull()
        val storedKeyId = runCatching { PairingManager.hubKeyId(context) }.getOrNull()
        val now = System.currentTimeMillis()
        val result = HubStepVerifier.verify(
            envelope = envelope,
            deviceId = deviceId,
            storedKeyB64 = storedKey,
            storedKeyId = storedKeyId,
            nowMs = now,
            isNonceSeen = { nonce ->
                runCatching { PairingManager.isHubNonceSeen(context, nonce, now) }.getOrDefault(false)
            },
            markNonceSeen = { nonce ->
                runCatching { PairingManager.markHubNonceSeen(context, nonce, now) }
            },
        )
        when (result) {
            is HubStepVerifier.Result.Ok -> handleExecuteStep(result.step)
            is HubStepVerifier.Result.Reject -> {
                val jobId = runCatching {
                    JSONObject(envelope.optString("payload_raw", ""))
                        .optString("job_id", "")
                }.getOrDefault("").ifBlank { "unknown" }
                val summary = if (result.reason == "no_hub_key") {
                    "Hub key nahi mili — step rejected"
                } else {
                    "Hub signature verify fail — step rejected"
                }
                diag("WS_STEP_SIG_FAIL", "EXECUTE_STEP reject (${result.reason})")
                if (result.reason == "ts_stale" || result.reason == "step_ts_stale") {
                    // Fail-closed is correct, but the user needs the
                    // fix: their clock. PairingCard shows this detail.
                    setStatus(_status.value.state, detail = "Hub WS: time galat lagta hai — clock check karo")
                    diag("WS_STEP_TS_SKEW", "Step ts stale — device clock check karo")
                }
                sendStepResult(jobId, false, summary)
            }
        }
    }

    /**
     * 3c: report a WebView captcha to the hub (signed, like
     * STEP_RESULT). The screenshot is the captcha IMAGE, not a
     * solution — it is never stored server-side (DO traces only
     * task_id/site). If the socket is down the report is lost
     * (device->hub has no queue); the local card flow still works.
     */
    fun sendCaptchaDetected(taskId: String, site: String, taskName: String, screenshotB64: String?) {
        val payloadRaw = JSONObject()
            .put("task_id", taskId)
            .put("site", site)
            .put("task_name", taskName)
            .apply { if (screenshotB64 != null) put("screenshot_b64", screenshotB64) }
            .toString()
        val msg = runCatching { signedMessage("CAPTCHA_DETECTED", payloadRaw) }.getOrNull() ?: run {
            diag("WS_KEY_ERROR", "CAPTCHA_DETECTED sign nahi hua")
            return
        }
        val sent = webSocket?.send(msg) ?: false
        diag("WS_CAPTCHA_DETECTED", "CAPTCHA_DETECTED bheja (sent=$sent)")
    }

    /**
     * 3c: report the assist outcome (solved / timeout / aborted).
     * A solution/token value is NEVER sent — only the outcome.
     */
    fun sendCaptchaResolved(taskId: String, site: String, solved: Boolean, aborted: Boolean, durationS: Long) {
        val payloadRaw = JSONObject()
            .put("task_id", taskId)
            .put("site", site)
            .put("solved", solved)
            .put("aborted", aborted)
            .put("duration_s", durationS)
            .toString()
        val msg = runCatching { signedMessage("CAPTCHA_RESOLVED", payloadRaw) }.getOrNull() ?: run {
            diag("WS_KEY_ERROR", "CAPTCHA_RESOLVED sign nahi hua")
            return
        }
        val sent = webSocket?.send(msg) ?: false
        diag("WS_CAPTCHA_RESOLVED", "CAPTCHA_RESOLVED bheja (solved=$solved, sent=$sent)")
    }

    /** 3c: 1-tap handoff — the notification opens the app to the canvas. */
    private fun postCaptchaNotification(site: String, taskName: String) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "jarvis_autonomous_channel",
                    "Jarvis",
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                CAPTCHA_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, "jarvis_autonomous_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Jarvis ko madad chahiye")
                .setContentText("$site par captcha aaya hai ($taskName). Tap karke solve karo.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(CAPTCHA_NOTIFICATION_ID, notification)
        }.onFailure {
            diag("WS_CAPTCHA_NOTIFY_FAIL", "Captcha notification nahi bheji ja saki")
        }
    }

    // ---- Signing (frozen canonical form) ----

    /** Build + sign one device->hub message. payload_raw is the EXACT
     *  string signed — built once, never re-serialized. */
    private fun signedMessage(type: String, payloadRaw: String): String {
        val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
        val ts = System.currentTimeMillis()
        val canonical = "$deviceId\n$nonce\n$ts\n$payloadRaw"
        val sig = Base64.encodeToString(
            PairingManager.sign(context, canonical.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        return JSONObject()
            .put("type", type)
            .put("device_id", deviceId)
            .put("nonce", nonce)
            .put("ts", ts)
            .put("sig", sig)
            .put("payload_raw", payloadRaw)
            .toString()
    }

    private fun sendHello() {
        val payloadRaw = JSONObject()
            .put("hello", true)
            .put("app_version", APP_VERSION)
            .toString()
        val msg = runCatching { signedMessage("HELLO", payloadRaw) }.getOrElse {
            setStatus(WsState.UNAUTH, detail = "Key error — pairing dobara karo")
            diag("WS_KEY_ERROR", "HELLO sign nahi hua")
            runCatching { webSocket?.close(1000, "key_error") }
            return
        }
        webSocket?.send(msg)
    }

    private fun sendStepResult(stepId: String, ok: Boolean, summary: String) {
        val payloadRaw = JSONObject()
            .put("step_id", stepId)
            .put("ok", ok)
            .put("summary", summary.take(200))
            .toString()
        val msg = runCatching { signedMessage("STEP_RESULT", payloadRaw) }.getOrNull() ?: run {
            diag("WS_KEY_ERROR", "STEP_RESULT sign nahi hua")
            return
        }
        val sent = webSocket?.send(msg) ?: false
        if (sent) {
            setStatus(
                _status.value.state,
                lastStepResultAtMs = System.currentTimeMillis()
            )
            diag("WS_STEP_RESULT", "STEP_RESULT bheja (ok=$ok)")
        } else {
            diag("WS_STEP_RESULT_FAIL", "STEP_RESULT bheja nahi ja saka (socket band)")
        }
    }

    // ---- Helpers ----

    private fun setStatus(
        state: WsState,
        lastHelloAckAtMs: Long = _status.value.lastHelloAckAtMs,
        lastStepResultAtMs: Long = _status.value.lastStepResultAtMs,
        reconnectAttempt: Int = _status.value.reconnectAttempt,
        detail: String = ""
    ) {
        _status.value = WsStatus(state, lastHelloAckAtMs, lastStepResultAtMs, reconnectAttempt, detail)
    }

    private fun diag(code: String, message: String) {
        runCatching {
            JarvisStateRepository.addDiagnostic("ws", code, message)
        }
    }
}
