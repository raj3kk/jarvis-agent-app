package com.jarvis.autonomous.data

import android.content.Context
import android.util.Base64
import com.jarvis.autonomous.agent.JarvisAgentEngine
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
 *   cron dispatcher) -> execute via JarvisAgentEngine.handleUserCommand
 *   (the SAME choke point as a spoken command, so ALL standing gates
 *   apply) -> signed STEP_RESULT after completion observation.
 * - The hub does NOT sign EXECUTE_STEP (it cannot — it never has the
 *   device key, and does not need to: the channel is authenticated at
 *   establishment over TLS to the known hub URL). The device signs its
 *   OUTBOUND messages (HELLO, STEP_RESULT); the hub verifies.
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
        private const val APP_VERSION = "1.6.1"
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
                setStatus(
                    WsState.CONNECTED,
                    lastHelloAckAtMs = System.currentTimeMillis(),
                    reconnectAttempt = 0,
                    detail = "Juda hua"
                )
                diag("WS_HELLO_ACK", "Hub se HELLO_ACK mila")
            }
            "EXECUTE_STEP" -> {
                val step = json.optJSONObject("step")
                if (step == null) {
                    diag("WS_STEP_BAD", "EXECUTE_STEP bina step ke aaya")
                } else {
                    handleExecuteStep(step)
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
