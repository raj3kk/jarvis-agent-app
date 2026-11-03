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

    private const val BIND_TIMEOUT_MS = 15_000L
    private const val SPEAK_TIMEOUT_MS = 300_000L

    /** Last failure code of the most recent speak (null = last speak ok or not run). Read by speakGreeting for its visible chat line. */
    @Volatile
    var lastFailure: String? = null
        private set

    private data class Outcome(val ok: Boolean, val died: Boolean)

    private val lock = Any()
    private var serviceMessenger: Messenger? = null
    private var connection: ServiceConnection? = null
    private var bound: Boolean = false
    private var pending: CompletableDeferred<Outcome>? = null
    private var pendingProgress: ((String, Long, Long) -> Unit)? = null

    private val replyMessenger = Messenger(
        Handler(Looper.getMainLooper(), Handler.Callback { msg ->
            if (msg.what == KokoroTtsService.MSG_DONE) {
                val ok = msg.data?.getBoolean(KokoroTtsService.KEY_OK) ?: false
                synchronized(lock) {
                    pending?.complete(Outcome(ok, false))
                    pending = null
                    pendingProgress = null
                }
                true
            } else if (msg.what == KokoroTtsService.MSG_PROGRESS) {
                val stage = msg.data?.getString(KokoroTtsService.KEY_STAGE) ?: ""
                val copied = msg.data?.getLong(KokoroTtsService.KEY_COPIED) ?: 0L
                val total = msg.data?.getLong(KokoroTtsService.KEY_TOTAL) ?: 0L
                synchronized(lock) { pendingProgress }?.invoke(stage, copied, total)
                true
            } else {
                false
            }
        })
    )

    private val deathRecipient = IBinder.DeathRecipient { onTtsProcessDied() }

    private fun onTtsProcessDied() {
        synchronized(lock) {
            serviceMessenger = null
            bound = false
            pending?.complete(Outcome(false, true))
            pending = null
            pendingProgress = null
        }
        lastFailure = FAIL_NATIVE_DIED
        JarvisStateRepository.addDiagnostic(
            "tts", "NATIVE_TTS_PROCESS_DIED",
            "Isolated TTS process died during speak (native crash or " +
                "system kill in generate/playback) — UI process survived, " +
                "speech degraded to visible failure."
        )
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
                synchronized(lock) {
                    serviceMessenger = Messenger(binder)
                    connection = this
                    bound = true
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
            c
        }
        if (conn != null) runCatching { appContext.unbindService(conn) }
    }

    suspend fun speak(context: Context, text: String): Boolean {
        lastFailure = null
        if (text.isBlank()) return false
        val appContext = context.applicationContext
        if (!ensureBound(appContext)) {
            lastFailure = FAIL_BIND
            JarvisStateRepository.addDiagnostic(
                "tts", "TTS_BIND_FAILED",
                "Could not bind the isolated TTS process — speech " +
                    "unavailable, app stays open (visible failure)."
            )
            return false
        }
        val deferred = CompletableDeferred<Outcome>()
        synchronized(lock) {
            if (pending != null) {
                lastFailure = FAIL_BUSY
                JarvisStateRepository.addDiagnostic(
                    "tts", "TTS_BUSY",
                    "Second concurrent speak refused (main process " +
                        "serialises speaks) — caller degrades visibly."
                )
                return false
            }
            pending = deferred
        }
        val sent = runCatching {
            val msg = Message.obtain(null, KokoroTtsService.MSG_SPEAK)
            msg.data = Bundle().apply { putString(KokoroTtsService.KEY_TEXT, text) }
            msg.replyTo = replyMessenger
            val messenger = synchronized(lock) { serviceMessenger }
                ?: throw RemoteException("TTS service messenger gone")
            messenger.send(msg)
        }
        if (sent.isFailure) {
            synchronized(lock) {
                if (pending == deferred) pending = null
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
            return false
        }
        val outcome = withTimeoutOrNull(SPEAK_TIMEOUT_MS) { deferred.await() }
        if (outcome == null) {
            stop()
            unbind(appContext)
            lastFailure = FAIL_TIMEOUT
            JarvisStateRepository.addDiagnostic(
                "tts", "TTS_PROCESS_TIMEOUT",
                "No TTS reply in ${SPEAK_TIMEOUT_MS / 1000}s — stopped " +
                    "and unbound; next speak rebinds fresh."
            )
            return false
        }
        if (outcome.died) {
            lastFailure = FAIL_NATIVE_DIED
            return false
        }
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
        synchronized(lock) {
            if (pending != null) {
                lastFailure = FAIL_BUSY
                return false
            }
            pending = deferred
            pendingProgress = onProgress
        }
        val sent = runCatching {
            val msg = Message.obtain(null, KokoroTtsService.MSG_RESET)
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
        return outcome.ok
    }
}
