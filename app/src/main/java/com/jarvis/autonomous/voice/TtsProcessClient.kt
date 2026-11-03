package com.jarvis.autonomous.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.data.TestVoiceStageTracker
import com.jarvis.autonomous.service.KokoroTtsService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 1.4.5 — main-process client for the isolated Kokoro TTS process.
 *
 * Why this exists (user evidence, 2026-11-03 18:54:49): the UI
 * process died DURING the startup greeting speak — SPK_START
 * with no SPK_END, no destroy markers, no crash entry — and the
 * user confirms NO greeting audio was heard at all. Playback is
 * therefore eliminated (audible audio would have been heard);
 * the death window is the GENERATE stage (native OfflineTts
 * model load / first ONNX inference / native-allocation kill),
 * which Java try/catch cannot catch. Moving synthesis into the
 * :tts process means that death kills only TTS: this client
 * observes the binder death, records tts/NATIVE_TTS_PROCESS_DIED,
 * and returns false so the caller degrades to a VISIBLE failure
 * while the app stays open. The app must NEVER again die
 * silently during greeting speak.
 *
 * Binding policy: bind lazily on first speak and STAY bound
 * (the Kokoro engine stays cached in the :tts process, exactly
 * as it used to stay cached in the UI process — re-binding per
 * utterance would reload the 114 MB model every time). If the
 * :tts process dies, the next speak rebinds fresh (engine
 * reloads once in the new process). Audio focus stays in the
 * main process (same UID, so the grant covers :tts playback).
 *
 * Failure modes, all visible, never a hang, never a throw:
 *  - bind fails / times out -> TTS_BIND_FAILED, return false.
 *  - :tts process dies mid-speak -> onServiceDisconnected /
 *    binder death -> NATIVE_TTS_PROCESS_DIED, return false.
 *  - no reply in SPEAK_TIMEOUT_MS -> MSG_STOP + unbind,
 *    TTS_PROCESS_TIMEOUT, return false (the orphan :tts speak
 *    may linger briefly; the next speak rebinds fresh).
 * A second concurrent speak is answered false (TTS_BUSY) — the
 * main process serialises speaks via VoiceManager.speakMutex,
 * so this is defensive only, never a silent queue.
 *
 * Threading: ServiceConnection callbacks and the reply
 * Messenger run on the main looper; awaits suspend (never
 * block). Diagnostics are written synchronously to the shared
 * diagnostics.jsonl file, so they survive a :tts death.
 */
object TtsProcessClient {

    const val FAIL_NATIVE_DIED = "NATIVE_TTS_PROCESS_DIED"
    const val FAIL_BIND = "TTS_BIND_FAILED"
    const val FAIL_TIMEOUT = "TTS_PROCESS_TIMEOUT"
    const val FAIL_BUSY = "TTS_BUSY"
    /** 1.4.10: speak refused without rebinding — latch active. */
    const val FAIL_LATCHED = "KOKORO_LATCHED"

    private const val BIND_TIMEOUT_MS = 15_000L
    private const val SPEAK_TIMEOUT_MS = 300_000L

    /** Last failure code of the most recent speak (null = last speak ok or not run). Read by speakGreeting for its visible chat line. */
    @Volatile
    var lastFailure: String? = null
        private set

    private data class Outcome(val ok: Boolean, val died: Boolean)

    /** Request id echoed in every DONE/PROGRESS reply (item 5). */
    const val KEY_REQ = "req_id"

    private val lock = Any()
    private var serviceMessenger: Messenger? = null
    private var connection: ServiceConnection? = null
    private var bound: Boolean = false
    private var pending: CompletableDeferred<Outcome>? = null
    private var pendingProgress: ((String, Long, Long) -> Unit)? = null
    private var pendingReq: Long? = null
    /** 1.4.6: which op holds [pending] ("SPEAK" | "ENSURE" | "RESET").
     * Set in the same synchronized block as the claim, so whenever
     * [pending] is non-null the kind belongs to the live holder. */
    private var pendingKind: String? = null
    private var nextReqId: Long = 0L
    /** 1.4.8: application context of the live binding, kept so a
     * death can unbind the stale connection (see
     * onTtsProcessDied). Set on successful bind, cleared on
     * unbind. */
    private var boundContext: Context? = null
    /** 1.4.8: death-episode dedupe. One :tts death can invoke
     * onTtsProcessDied several times (DeathRecipient +
     * onServiceDisconnected, once per accumulated connection),
     * and the 22:07:33 phone report shows 10+ identical
     * NATIVE_TTS_PROCESS_DIED lines per second per speak —
     * the flood pushes the real markers out of the last-50
     * window. So: the first death of an episode writes the
     * full line (with the last :tts marker before it); later
     * deaths in the same episode only increment the counter;
     * the next successful bind closes the episode with one
     * summary line. An episode ends ONLY on a successful
     * bind. */
    private var deathEpisodeActive: Boolean = false
    private var deathEpisodeCount: Int = 0
    private var deathEpisodeFirstMarker: String = "none"

    /**
     * 1.4.10 (Step 1) — BOUNDED recovery + latch. The 1.4.8/1.4.9
     * behaviour was UNBOUNDED recovery: every new speak rebound
     * :tts, reloaded the 114 MB engine, and died again — a
     * crash-restart amplifier. Now consecutive failed SPEAK
     * attempts are counted (native-death episodes, bind
     * failures, speak timeouts); at the bound the latch trips
     * and speak() refuses WITHOUT rebinding. The latch opens
     * only on a user action (Test Voice tap, Settings reset,
     * Kokoro toggle-on) or a 10-minute cooldown expiry — never
     * silently. Engine-level failures (init fail, hash
     * mismatch, playback fail, busy, toggle OFF) do NOT count:
     * they already fail fast visibly without restarting the
     * process, and latching on them would suppress Kokoro for
     * problems the user can fix with reset. A successful speak
     * resets the count. Bound justification: 1 failure can be
     * a fluke (transient LMK), 2 is a pattern, 3 is a latch —
     * and 3 is also the smallest count that survives one
     * double-death episode (death + rebind death) without
     * latching on a single bad launch.
     */
    private const val LATCH_BOUND = 3
    private const val LATCH_COOLDOWN_MS = 10L * 60L * 1000L
    private var consecutiveSpeakFailures = 0
    private var kokoroLatched = false
    private var latchedAtMs = 0L

    /** True while the latch is active. A 10-minute cooldown
     * expiry clears it (marked, not silent). */
    fun isKokoroLatched(): Boolean {
        var expired = false
        val latched = synchronized(lock) {
            if (!kokoroLatched) {
                false
            } else if (System.currentTimeMillis() - latchedAtMs >= LATCH_COOLDOWN_MS) {
                kokoroLatched = false
                consecutiveSpeakFailures = 0
                expired = true
                false
            } else {
                true
            }
        }
        if (expired) {
            JarvisStateRepository.addDiagnostic(
                "tts", "KOKORO_LATCH_EXPIRED",
                "Kokoro latch expired after 10 min cooldown — Kokoro retries on the next speak."
            )
            JarvisStateRepository.setKokoroLatched(false)
        }
        return latched
    }

    /** User-action reopen (Test Voice tap, Settings reset,
     * Kokoro toggle-on). Silent no-op when not latched. */
    fun clearLatch(reason: String) {
        var wasLatched = false
        synchronized(lock) {
            wasLatched = kokoroLatched
            kokoroLatched = false
            consecutiveSpeakFailures = 0
        }
        if (wasLatched) {
            JarvisStateRepository.addDiagnostic(
                "tts", "KOKORO_LATCH_CLEARED",
                "Kokoro latch cleared by user action ($reason) — Kokoro retries on the next speak."
            )
            JarvisStateRepository.setKokoroLatched(false)
        }
    }

    /** Count one failed speak attempt. At the bound the latch
     * trips (marker + card flow); below it the running count
     * is logged so every futile attempt is visible — the
     * "har auto-rebind ka count" requirement. Callers must NOT
     * call this for a death already counted at episode-start
     * (see onTtsProcessDied) or the same death counts twice. */
    private fun recordSpeakFailure(kind: String) {
        var tripped = false
        var count = 0
        synchronized(lock) {
            consecutiveSpeakFailures++
            count = consecutiveSpeakFailures
            if (!kokoroLatched && count >= LATCH_BOUND) {
                kokoroLatched = true
                latchedAtMs = System.currentTimeMillis()
                tripped = true
            }
        }
        if (tripped) {
            JarvisStateRepository.addDiagnostic(
                "tts", "KOKORO_UNAVAILABLE_LATCHED",
                "Kokoro failed $count consecutive speaks — auto-rebind STOPPED, system-voice fallback active. " +
                    "Latch opens on Test Voice / Settings reset / Kokoro toggle-on / 10-min cooldown."
            )
            JarvisStateRepository.setKokoroLatched(true)
        } else {
            JarvisStateRepository.addDiagnostic(
                "tts", "KOKORO_FAIL_COUNT",
                "Consecutive failed speaks: $count/$LATCH_BOUND (kind=$kind) — latch trips at $LATCH_BOUND."
            )
        }
    }

    private fun recordSpeakSuccess() {
        synchronized(lock) { consecutiveSpeakFailures = 0 }
    }

    private val replyMessenger = Messenger(
        Handler(Looper.getMainLooper(), Handler.Callback { msg ->
            if (msg.what == KokoroTtsService.MSG_DONE) {
                // 1.4.5 (item 5): complete ONLY on request-id match.
                // A late DONE from a timed-out request (after an
                // unbind + rebind) must never complete a NEW pending
                // speak/reset/ensure — that was the second half of
                // the stuck-flag bug (stale replies landing on the
                // wrong request). Mismatches are ignored.
                val req = msg.data?.getLong(KEY_REQ, -1L) ?: -1L
                val ok = msg.data?.getBoolean(KokoroTtsService.KEY_OK) ?: false
                synchronized(lock) {
                    if (pendingReq != null && pendingReq == req) {
                        pending?.complete(Outcome(ok, false))
                        pending = null
                        pendingProgress = null
                        pendingReq = null
                        // 1.4.9 (Step 1e): pendingKind is cleared
                        // wherever pending is cleared — it used
                        // to survive every non-death clear (reply,
                        // send-failure, timeout), leaving a stale
                        // kind behind. Harmless in practice (the
                        // claim logic reads it only when pending
                        // is non-null), but stale state is how
                        // the next subtle bug starts.
                        pendingKind = null
                    }
                }
                true
            } else if (msg.what == KokoroTtsService.MSG_PROGRESS) {
                val req = msg.data?.getLong(KEY_REQ, -1L) ?: -1L
                val stage = msg.data?.getString(KokoroTtsService.KEY_STAGE) ?: ""
                val copied = msg.data?.getLong(KokoroTtsService.KEY_COPIED) ?: 0L
                val total = msg.data?.getLong(KokoroTtsService.KEY_TOTAL) ?: 0L
                val cb = synchronized(lock) {
                    if (pendingReq != null && pendingReq == req) pendingProgress else null
                }
                cb?.invoke(stage, copied, total)
                true
            } else {
                false
            }
        })
    )

    private val deathRecipient = IBinder.DeathRecipient { onTtsProcessDied() }

    /** Last :tts-area marker code in the in-memory diagnostics
     * mirror (the last 150 persisted lines). Names the stage
     * the dying process reached — the single most useful fact
     * in the next phone report's death line.
     * 1.4.9 (Step 1h): skip NATIVE_TTS_PROCESS_DIED lines
     * themselves — after a death, the mirror's last tts-area
     * code IS a previous death line, so the next episode used
     * to report "last marker: NATIVE_TTS_PROCESS_DIED"
     * (useless). Now it names the real last speak marker
     * (e.g. SPK_GENERATE_START). */
    private fun lastTtsMarker(): String {
        return runCatching {
            JarvisStateRepository.diagnostics.value
                .lastOrNull { it.area == "tts" && it.code != "NATIVE_TTS_PROCESS_DIED" }?.code ?: "none"
        }.getOrDefault("none")
    }

    private fun onTtsProcessDied() {
        // Capture the stale connection + context under lock,
        // then unbind OUTSIDE the lock (Binder call; never hold
        // the lock across it). Unbinding matters twice: (a)
        // with BIND_AUTO_CREATE the system would otherwise
        // resurrect :tts after every death with no speak pending
        // (the stray TTS_PROCESS_START lines in the 22:07:33
        // report); (b) a never-unbound connection keeps
        // receiving onServiceDisconnected on every later death,
        // so the death-line count grows with every speak —
        // the flood mechanism itself.
        val staleConn: ServiceConnection?
        val staleContext: Context?
        val episodeStart: Boolean
        val firstMarker: String
        synchronized(lock) {
            serviceMessenger = null
            bound = false
            staleConn = connection
            connection = null
            staleContext = boundContext
            boundContext = null
            pending?.complete(Outcome(false, true))
            pending = null
            pendingProgress = null
            pendingReq = null
            pendingKind = null
            if (!deathEpisodeActive) {
                deathEpisodeActive = true
                deathEpisodeCount = 1
                deathEpisodeFirstMarker = lastTtsMarker()
                episodeStart = true
            } else {
                deathEpisodeCount++
                episodeStart = false
            }
            firstMarker = deathEpisodeFirstMarker
        }
        lastFailure = FAIL_NATIVE_DIED
        if (staleConn != null && staleContext != null) {
            runCatching { staleContext.unbindService(staleConn) }
        }
        // Only the episode-start death writes a full line;
        // later deaths in the same episode only bump the
        // counter (reported when the episode closes on the
        // next successful bind).
        if (episodeStart) {
            JarvisStateRepository.addDiagnostic(
                "tts", "NATIVE_TTS_PROCESS_DIED",
                "Isolated TTS process died during speak (native crash or " +
                    "system kill in generate/playback) — UI process survived, " +
                    "speech degraded to visible failure. " +
                    "Last :tts marker before death: $firstMarker. " +
                    "Further deaths before the next successful bind are counted, not logged."
            )
            // 1.4.10: every death EPISODE counts toward the latch
            // (duplicates inside one episode do not — see above).
            // The speak() died-outcome path must NOT count again.
            recordSpeakFailure("death")
        }
    }

    private suspend fun ensureBound(appContext: Context): Boolean {
        synchronized(lock) {
            if (bound && serviceMessenger != null) return true
        }
        val connected = CompletableDeferred<Boolean>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    binder.linkToDeath(deathRecipient, 0)
                } catch (_: RemoteException) {
                    // Binder already dead: treat as a death, not a bind.
                    onTtsProcessDied()
                    connected.complete(false)
                    return
                }
                // 1.4.8: close a death episode, if one is open.
                // Captured under lock, written outside it (file
                // append must never run while holding the lock).
                var episodeSummary: Pair<Int, String>? = null
                synchronized(lock) {
                    serviceMessenger = Messenger(binder)
                    connection = this
                    bound = true
                    boundContext = appContext
                    if (deathEpisodeActive) {
                        episodeSummary = deathEpisodeCount to deathEpisodeFirstMarker
                        deathEpisodeActive = false
                        deathEpisodeCount = 0
                    }
                }
                episodeSummary?.let { (total, marker) ->
                    if (total > 1) {
                        JarvisStateRepository.addDiagnostic(
                            "tts", "NATIVE_TTS_PROCESS_DIED",
                            "TTS death episode closed on rebind: $total deaths " +
                                "(${total - 1} duplicates suppressed), " +
                                "last :tts marker before first death: $marker."
                        )
                    }
                }
                connected.complete(true)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // Fires when the :tts process crashes (not on our
                // own unbind). The system may re-deliver a fresh
                // onServiceConnected afterwards (BIND_AUTO_CREATE);
                // either way the next speak rebinds or reuses it.
                onTtsProcessDied()
            }
        }
        val intent = Intent(appContext, KokoroTtsService::class.java)
        val bindOk = runCatching {
            appContext.bindService(
                intent, conn,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        }.getOrDefault(false)
        if (!bindOk) return false
        val ok = withTimeoutOrNull(BIND_TIMEOUT_MS) { connected.await() } ?: false
        if (!ok) {
            // Do not leak a half-open binding: unbind so the next
            // speak starts clean.
            runCatching { appContext.unbindService(conn) }
            synchronized(lock) {
                if (connection == conn) {
                    connection = null
                    serviceMessenger = null
                    bound = false
                    boundContext = null
                }
            }
        }
        return ok
    }

    private fun unbind(appContext: Context) {
        val conn = synchronized(lock) {
            val c = connection
            connection = null
            serviceMessenger = null
            bound = false
            boundContext = null
            c
        }
        if (conn != null) runCatching { appContext.unbindService(conn) }
    }

    suspend fun speak(context: Context, text: String): Boolean {
        lastFailure = null
        if (text.isBlank()) return false
        // 1.4.10: latched = auto-rebind BAND — refuse WITHOUT
        // binding (VoiceManager checks first and falls back;
        // this is defense-in-depth for direct callers).
        if (isKokoroLatched()) {
            lastFailure = FAIL_LATCHED
            JarvisStateRepository.addDiagnostic(
                "tts", "KOKORO_LATCHED_REFUSED",
                "Kokoro speak refused: latch active — no rebind attempted (caller may use system-voice fallback)."
            )
            return false
        }
        val appContext = context.applicationContext
        if (!ensureBound(appContext)) {
            lastFailure = FAIL_BIND
            JarvisStateRepository.addDiagnostic(
                "tts", "TTS_BIND_FAILED",
                "Could not bind the isolated TTS process — speech " +
                    "unavailable, app stays open (visible failure)."
            )
            recordSpeakFailure("bind")
            return false
        }
        val deferred = CompletableDeferred<Outcome>()
        val reqId = synchronized(lock) { ++nextReqId }
        // 1.4.6 GREETING-FAIL ROOT CAUSE (20:49:45 phone report:
        // SPK_START -> TTS_BUSY -> SPK_END ok=false in 86 ms, no
        // ENGINE markers): the launch-time Kokoro asset ensure
        // (MainActivity, TtsProcessClient.ensure) holds `pending`
        // for the whole ~190 MB hash-verify/copy, and the startup
        // greeting speak arrived while it held the slot — the old
        // code refused the speak instantly (TTS_BUSY), so the
        // greeting could never be heard on a first/uncached
        // launch even though the engine itself was healthy. The
        // fix: a speak WAITS for a setup holder (ENSURE/RESET) —
        // awaiting the holder's deferred (never completing it) —
        // then re-claims; a speak-vs-speak collision still
        // refuses instantly (TTS_BUSY), preserving 1.4.5
        // serialisation. Bounded: 3 waits, 600 s each (the
        // ensure bound); on timeout the old busy failure returns.
        var claimed = false
        var setupWaits = 0
        while (!claimed) {
            val state = synchronized(lock) {
                when {
                    pending == null -> {
                        pending = deferred
                        pendingReq = reqId
                        pendingKind = "SPEAK"
                        // 1.4.11: live Test Voice stage relay —
                        // :tts sends MSG_PROGRESS with each stage
                        // code during this speak; forward it to
                        // the tracker (which gates on the open
                        // test run). Copy-progress callbacks for
                        // ensure/reset are unaffected (separate
                        // pending kinds, req-id matched).
                        pendingProgress = { stage, _, _ ->
                            TestVoiceStageTracker.onTtsStage(stage)
                        }
                        "CLAIMED"
                    }
                    pendingKind == "ENSURE" || pendingKind == "RESET" -> "WAIT_SETUP"
                    else -> "BUSY"
                }
            }
            when (state) {
                "CLAIMED" -> claimed = true
                "BUSY" -> {
                    lastFailure = FAIL_BUSY
                    JarvisStateRepository.addDiagnostic(
                        "tts", "TTS_BUSY",
                        "Second concurrent speak refused (main process " +
                            "serialises speaks) — caller degrades visibly."
                    )
                    return false
                }
                else -> {
                    if (setupWaits >= 3) {
                        lastFailure = FAIL_BUSY
                        JarvisStateRepository.addDiagnostic(
                            "tts", "TTS_BUSY",
                            "Speak gave up waiting for Kokoro setup " +
                                "(ensure/reset) — caller degrades visibly."
                        )
                        return false
                    }
                    setupWaits++
                    JarvisStateRepository.addDiagnostic(
                        "tts", "TTS_WAIT_SETUP",
                        "Speak waiting for Kokoro setup (ensure/reset) " +
                            "to finish before speaking (launch-copy race)."
                    )
                    val holder = synchronized(lock) { pending }
                    val timedOut = if (holder != null) {
                        withTimeoutOrNull(600_000L) { holder.await() } == null
                    } else {
                        false
                    }
                    if (timedOut) {
                        lastFailure = FAIL_BUSY
                        JarvisStateRepository.addDiagnostic(
                            "tts", "TTS_BUSY",
                            "Speak setup-wait timed out (600 s) — caller " +
                                "degrades visibly."
                        )
                        return false
                    }
                }
            }
        }
        val sent = runCatching {
            val msg = Message.obtain(null, KokoroTtsService.MSG_SPEAK)
            msg.data = Bundle().apply {
                putString(KokoroTtsService.KEY_TEXT, text)
                putLong(KEY_REQ, reqId)
            }
            msg.replyTo = replyMessenger
            val messenger = synchronized(lock) { serviceMessenger }
                ?: throw RemoteException("TTS service messenger gone")
            messenger.send(msg)
        }
        if (sent.isFailure) {
            synchronized(lock) {
                if (pending == deferred) {
                    pending = null
                    pendingProgress = null
                    pendingReq = null
                    pendingKind = null
                }
            }
            // A dead binder here is the same native-death case;
            // the death handler may already have recorded it.
            if (lastFailure == null) {
                lastFailure = FAIL_BIND
                JarvisStateRepository.addDiagnostic(
                    "tts", "TTS_BIND_FAILED",
                    "TTS service messenger gone while sending speak."
                )
            }
            // Death already counted at episode-start — do not count twice.
            if (lastFailure != FAIL_NATIVE_DIED) recordSpeakFailure("bind")
            return false
        }
        val outcome = withTimeoutOrNull(SPEAK_TIMEOUT_MS) { deferred.await() }
        if (outcome == null) {
            stop()
            unbind(appContext)
            // 1.4.5 (item 5): THE STUCK-FLAG BUG — the old timeout
            // branch never cleared `pending`, so every later speak
            // returned TTS_BUSY forever (a 5-minute timeout became
            // a permanent speech outage). Clear it here, and the
            // req-id check above makes any late DONE harmless.
            synchronized(lock) {
                if (pending == deferred) {
                    pending = null
                    pendingProgress = null
                    pendingReq = null
                    pendingKind = null
                }
            }
            lastFailure = FAIL_TIMEOUT
            JarvisStateRepository.addDiagnostic(
                "tts", "TTS_PROCESS_TIMEOUT",
                "No TTS reply in ${SPEAK_TIMEOUT_MS / 1000}s — stopped " +
                    "and unbound; next speak rebinds fresh."
            )
            recordSpeakFailure("timeout")
            return false
        }
        if (outcome.died) {
            // Already counted at episode-start — do not count twice.
            lastFailure = FAIL_NATIVE_DIED
            return false
        }
        if (outcome.ok) recordSpeakSuccess()
        return outcome.ok
    }

    /** Best-effort stop of :tts playback (barge-in / STOP_TASK). Never throws, never waits. */
    fun stop() {
        runCatching {
            val messenger = synchronized(lock) { serviceMessenger } ?: return
            val msg = Message.obtain(null, KokoroTtsService.MSG_STOP)
            messenger.send(msg)
        }
    }

    /**
     * 1.4.5 REDO: Settings "Kokoro reset" routed through the
     * :tts process (never the main process — the engine lives
     * in :tts, and a main-process delete of the shared model dir
     * under a loaded engine is a native use-after-delete).
     * Streams copy progress to [onProgress] (stage, copiedMB,
     * totalMB). Returns true only on a fresh verified copy.
     * Refused (false) while a speak is in flight — retry after
     * speech ends. Same death/timeout handling as speak().
     */
    suspend fun reset(
        context: Context,
        onProgress: (String, Long, Long) -> Unit = { _, _, _ -> }
    ): Boolean {
        lastFailure = null
        val appContext = context.applicationContext
        if (!ensureBound(appContext)) {
            lastFailure = FAIL_BIND
            JarvisStateRepository.addDiagnostic(
                "tts", "TTS_BIND_FAILED",
                "Could not bind the isolated TTS process for reset."
            )
            return false
        }
        val deferred = CompletableDeferred<Outcome>()
        val reqId = synchronized(lock) { ++nextReqId }
        synchronized(lock) {
            if (pending != null) {
                lastFailure = FAIL_BUSY
                JarvisStateRepository.addDiagnostic(
                    "tts", "TTS_BUSY",
                    "Reset refused: another TTS request (speak or " +
                        "ensure) in flight — retry after it ends."
                )
                return false
            }
            pending = deferred
            pendingProgress = onProgress
            pendingReq = reqId
            pendingKind = "RESET"
        }
        val sent = runCatching {
            val msg = Message.obtain(null, KokoroTtsService.MSG_RESET)
            msg.data = Bundle().apply { putLong(KEY_REQ, reqId) }
            msg.replyTo = replyMessenger
            val messenger = synchronized(lock) { serviceMessenger }
                ?: throw RemoteException("TTS service messenger gone")
            messenger.send(msg)
        }
        if (sent.isFailure) {
            synchronized(lock) {
                if (pending == deferred) {
                    pending = null
                    pendingProgress = null
                    pendingReq = null
                    pendingKind = null
                }
            }
            if (lastFailure == null) {
                lastFailure = FAIL_BIND
                JarvisStateRepository.addDiagnostic(
                    "tts", "TTS_BIND_FAILED",
                    "TTS service messenger gone while sending reset."
                )
            }
            return false
        }
        // A full 190 MB asset copy is slow (streaming
        // decompress); 10 minutes is the generous bound — the
        // death path, not the timeout, is the fast failure.
        val outcome = withTimeoutOrNull(600_000L) { deferred.await() }
        if (outcome == null) {
            unbind(appContext)
            // 1.4.5 (item 5): same stuck-flag fix as speak().
            synchronized(lock) {
                if (pending == deferred) {
                    pending = null
                    pendingProgress = null
                    pendingReq = null
                    pendingKind = null
                }
            }
            lastFailure = FAIL_TIMEOUT
            JarvisStateRepository.addDiagnostic(
                "tts", "TTS_PROCESS_TIMEOUT",
                "No TTS reset reply in 600s — unbound; next request rebinds fresh."
            )
            return false
        }
        if (outcome.died) {
            lastFailure = FAIL_NATIVE_DIED
            return false
        }
        // 1.4.10: a successful reset is a user-action retry —
        // it reopens the latch (no-op when not latched).
        if (outcome.ok) clearLatch("settings-reset")
        return outcome.ok
    }

    /**
     * 1.4.5 part 3 (item 1a): route ALL asset copies through :tts
     * (single writer). Main-process callers — MainActivity launch
     * copy and the AI Brain Kokoro setup button — call this
     * instead of KokoroTtsClient.ensureAssetsReady directly, or
     * the per-process copyMutex cannot protect the shared *.tmp
     * files against a concurrent :tts copy. Mirrors reset():
     * MSG_ENSURE, 600 s bound, same death/timeout handling
     * (including the stuck-flag fix). Read-only isDownloaded
     * checks stay main-process (manifest + size reads, safe
     * cross-process).
     */
    suspend fun ensure(
        context: Context,
        onProgress: (String, Long, Long) -> Unit = { _, _, _ -> }
    ): Boolean {
        lastFailure = null
        val appContext = context.applicationContext
        if (!ensureBound(appContext)) {
            lastFailure = FAIL_BIND
            JarvisStateRepository.addDiagnostic(
                "tts", "TTS_BIND_FAILED",
                "Could not bind the isolated TTS process for asset ensure."
            )
            return false
        }
        val deferred = CompletableDeferred<Outcome>()
        val reqId = synchronized(lock) { ++nextReqId }
        synchronized(lock) {
            if (pending != null) {
                lastFailure = FAIL_BUSY
                JarvisStateRepository.addDiagnostic(
                    "tts", "TTS_BUSY",
                    "Ensure refused: another TTS request (speak or " +
                        "reset) in flight — caller retries later."
                )
                return false
            }
            pending = deferred
            pendingProgress = onProgress
            pendingReq = reqId
            pendingKind = "ENSURE"
        }
        val sent = runCatching {
            val msg = Message.obtain(null, KokoroTtsService.MSG_ENSURE)
            msg.data = Bundle().apply { putLong(KEY_REQ, reqId) }
            msg.replyTo = replyMessenger
            val messenger = synchronized(lock) { serviceMessenger }
                ?: throw RemoteException("TTS service messenger gone")
            messenger.send(msg)
        }
        if (sent.isFailure) {
            synchronized(lock) {
                if (pending == deferred) {
                    pending = null
                    pendingProgress = null
                    pendingReq = null
                    pendingKind = null
                }
            }
            if (lastFailure == null) {
                lastFailure = FAIL_BIND
                JarvisStateRepository.addDiagnostic(
                    "tts", "TTS_BIND_FAILED",
                    "TTS service messenger gone while sending ensure."
                )
            }
            return false
        }
        val outcome = withTimeoutOrNull(600_000L) { deferred.await() }
        if (outcome == null) {
            unbind(appContext)
            synchronized(lock) {
                if (pending == deferred) {
                    pending = null
                    pendingProgress = null
                    pendingReq = null
                    pendingKind = null
                }
            }
            lastFailure = FAIL_TIMEOUT
            JarvisStateRepository.addDiagnostic(
                "tts", "TTS_PROCESS_TIMEOUT",
                "No TTS ensure reply in 600s — unbound; next request rebinds fresh."
            )
            return false
        }
        if (outcome.died) {
            lastFailure = FAIL_NATIVE_DIED
            return false
        }
        return outcome.ok
    }
}
