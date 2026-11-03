package com.jarvis.autonomous.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.content.ComponentCallbacks2
import com.jarvis.autonomous.voice.KokoroTtsClient
import com.jarvis.autonomous.voice.TtsProcessClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1.4.5 — Kokoro TTS in an ISOLATED process (android:process=":tts"
 * in the manifest). This is the fix for the 1.4.4 launch-bounce
 * report (user phone, 2026-11-03 18:54:49): the UI process died
 * DURING the startup greeting speak — SPK_START with no SPK_END,
 * no LIFE_PAUSE/DESTROY, no crash entry, new process 2 s later —
 * and the user confirms NO greeting audio was heard at all.
 *
 * That signature eliminates playback (audible audio would have
 * been heard) and Java crashes (the process-wide handler would
 * have written a crash entry). What remains is the GENERATE
 * stage: native OfflineTts model load (114 MB int8 ONNX) or the
 * first ONNX inference, or a native-allocation / LMK kill during
 * that load — all of which a Java try/catch CANNOT catch, which
 * is exactly why there was no crash entry and no destroy marker.
 *
 * Isolation is the strongest fix available for a native death:
 * the native Sherpa/ONNX code runs ONLY in the :tts process.
 * If it crashes or is LMK-killed mid-generate, the :tts process
 * dies but the UI process SURVIVES: the main-process client
 * (TtsProcessClient) sees the binder death, records
 * tts/NATIVE_TTS_PROCESS_DIED, and the greeting degrades to a
 * visible failure + chat text instead of the app vanishing.
 * The app must NEVER again die silently during greeting speak:
 * either it speaks, or it stays open with a visible failure.
 *
 * IPC protocol (Messenger, no AIDL):
 *   main -> :tts  MSG_SPEAK (Bundle "text"): speak one utterance.
 *   main -> :tts  MSG_STOP: stop current playback (best-effort).
 *   :tts  -> main MSG_DONE (Bundle "ok"): speak finished.
 * The main process serialises speaks (VoiceManager.speakMutex),
 * so at most one MSG_SPEAK is outstanding; a second one while
 * busy is answered ok=false, stage="busy" (never queued
 * silently). Speech audio itself is played inside this process
 * (AudioTrack lives here); audio focus is held by the main
 * process (same UID, so the focus grant covers this process).
 *
 * Diagnostics: this process runs JarvisApplication.onCreate too,
 * so JarvisDiagnosticsFile is initialised here and every marker
 * written from this process lands in the SAME diagnostics.jsonl
 * file (synchronous append). That is what makes the 1.4.5 stage
 * markers (SPK_ENGINE_*, SPK_GENERATE_*, SPK_PLAY_*) decidable
 * even when THIS process dies mid-generate: the last marker
 * written before death names the stage that killed it.
 *
 * Deliberately NOT touched here: LaunchSession flags (main
 * process only), the bounce report, the per-launch HOME/BACK
 * guard, Deepgram/system STT, licence, patterns. This service
 * speaks and nothing else.
 */
class KokoroTtsService : Service() {

    companion object {
        const val MSG_SPEAK = 1
        const val MSG_STOP = 2
        const val MSG_RESET = 3
        const val MSG_ENSURE = 4
        const val MSG_DONE = 11
        const val MSG_PROGRESS = 12
        const val KEY_TEXT = "text"
        const val KEY_OK = "ok"
        const val KEY_STAGE = "stage"
        const val KEY_COPIED = "copied"
        const val KEY_TOTAL = "total"
        const val STAGE_DONE = "done"
        const val STAGE_BUSY = "busy"
        const val STAGE_STOPPED = "stopped"
        const val STAGE_RESET = "reset"
    }

    private lateinit var workerThread: HandlerThread
    private lateinit var incoming: Messenger
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val speakBusy = AtomicBoolean(false)
    // 1.4.5 (item 2c): engine release/trim handling. A release
    // requested while a speak is in flight is DEFERRED (never
    // release under a running generate — native use-after-release)
    // and executed when the speak completes. Idle release runs
    // 15 minutes after the last speak; the timer resets on every
    // new speak. Trim (memory pressure) releases immediately when
    // idle, else defers the same way.
    @Volatile
    private var pendingRelease: String? = null
    private var idleReleaseJob: Job? = null

    private fun requestRelease(reason: String) {
        if (speakBusy.get()) {
            pendingRelease = reason
            return
        }
        if (KokoroTtsClient.tryReleaseEngine(reason)) pendingRelease = null
    }

    private fun scheduleIdleRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = scope.launch {
            delay(15L * 60L * 1000L)
            requestRelease("idle-15min")
        }
    }

    private fun afterSpeakDone() {
        pendingRelease?.let { reason ->
            if (KokoroTtsClient.tryReleaseEngine(reason)) pendingRelease = null
        }
        scheduleIdleRelease()
    }

    private val incomingCallback = Handler.Callback { msg ->
        when (msg.what) {
            MSG_SPEAK -> {
                val text = msg.data?.getString(KEY_TEXT).orEmpty()
                val replyTo = msg.replyTo
                val reqId = msg.data?.getLong(TtsProcessClient.KEY_REQ, -1L) ?: -1L
                idleReleaseJob?.cancel()
                if (text.isBlank()) {
                    replyTo?.let { sendDone(it, false, STAGE_DONE, reqId) }
                } else if (!speakBusy.compareAndSet(false, true)) {
                    // Main process serialises speaks; a busy reply
                    // is honest (never a silent queue) and the
                    // caller degrades to a visible failure.
                    replyTo?.let { sendDone(it, false, STAGE_BUSY, reqId) }
                } else {
                    scope.launch {
                        try {
                            val ok = KokoroTtsClient.speak(applicationContext, text)
                            replyTo?.let {
                                sendDone(it, ok, if (ok) STAGE_DONE else STAGE_STOPPED, reqId)
                            }
                        } catch (t: Throwable) {
                            // Java throws from the speak path are
                            // recorded by KokoroTtsClient itself;
                            // this catch only guarantees a reply
                            // so the main process never hangs.
                            replyTo?.let { sendDone(it, false, STAGE_DONE, reqId) }
                        } finally {
                            speakBusy.set(false)
                            afterSpeakDone()
                        }
                    }
                }
                true
            }
            MSG_STOP -> {
                // Runs on the handler thread, which is never
                // blocked by a speak (speaks run on scope IO),
                // so STOP pre-empts playback between chunks and
                // during AudioTrack writes via the stopped flag.
                KokoroTtsClient.stopPlayback()
                true
            }
            MSG_ENSURE -> {
                // 1.4.5 (item 1a): single-writer asset ensure for
                // main-process callers (launch copy, AI Brain setup
                // button). No busy refusal: copyMutex inside :tts
                // serialises this with speak-triggered copies and
                // with reset (resetAndCopy holds the same lock for
                // delete + copy). Progress streams with the req id.
                val replyTo = msg.replyTo
                val reqId = msg.data?.getLong(TtsProcessClient.KEY_REQ, -1L) ?: -1L
                scope.launch {
                    try {
                        val result = KokoroTtsClient.ensureAssetsReady(applicationContext) { stage, copied, total ->
                            sendProgress(replyTo, stage, copied, total, reqId)
                        }
                        replyTo?.let { sendDone(it, result.isSuccess, STAGE_DONE, reqId) }
                    } catch (t: Throwable) {
                        replyTo?.let { sendDone(it, false, STAGE_DONE, reqId) }
                    }
                }
                true
            }
            MSG_RESET -> {
                // 1.4.5 REDO: the Settings reset MUST run here,
                // in the :tts process — the old main-process call
                // deleted the shared model dir while this process
                // could hold a loaded engine (mmap'd files), a
                // native use-after-delete. Refused while a speak
                // is in flight (busy = retry after speech ends).
                val replyTo = msg.replyTo
                val reqId = msg.data?.getLong(TtsProcessClient.KEY_REQ, -1L) ?: -1L
                if (speakBusy.get()) {
                    replyTo?.let { sendDone(it, false, STAGE_BUSY, reqId) }
                } else {
                    scope.launch {
                        try {
                            val result = KokoroTtsClient.resetAndCopy(applicationContext) { stage, copied, total ->
                                sendProgress(replyTo, stage, copied, total, reqId)
                            }
                            replyTo?.let { sendDone(it, result.isSuccess, STAGE_RESET, reqId) }
                        } catch (t: Throwable) {
                            replyTo?.let { sendDone(it, false, STAGE_RESET, reqId) }
                        }
                    }
                }
                true
            }
            else -> false
        }
    }

    private fun sendDone(replyTo: Messenger, ok: Boolean, stage: String, reqId: Long = -1L) {
        runCatching {
            val msg = Message.obtain(null, MSG_DONE)
            msg.data = android.os.Bundle().apply {
                putBoolean(KEY_OK, ok)
                putString(KEY_STAGE, stage)
                putLong(TtsProcessClient.KEY_REQ, reqId)
            }
            replyTo.send(msg)
        }
    }

    private fun sendProgress(replyTo: Messenger?, stage: String, copied: Long, total: Long, reqId: Long) {
        runCatching {
            val progress = Message.obtain(null, MSG_PROGRESS)
            progress.data = android.os.Bundle().apply {
                putString(KEY_STAGE, stage)
                putLong(KEY_COPIED, copied)
                putLong(KEY_TOTAL, total)
                putLong(TtsProcessClient.KEY_REQ, reqId)
            }
            replyTo?.send(progress)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // HandlerThread only routes messages; speaks run on the
        // IO scope so MSG_STOP stays responsive mid-utterance.
        workerThread = HandlerThread("kokoro-tts-ipc").apply { start() }
        incoming = Messenger(Handler(workerThread.looper, incomingCallback))
    }

    override fun onBind(intent: Intent): IBinder {
        return incoming.binder
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 1.4.5 (item 2c): the engine holds ~114 MB native. On
        // memory pressure, release it (or defer if speaking) so
        // the system need not LMK-kill this process mid-generate.
        // The old code never released the engine at all.
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
        ) {
            requestRelease("trim-$level")
        }
    }

    override fun onDestroy() {
        idleReleaseJob?.cancel()
        runCatching { KokoroTtsClient.tryReleaseEngine("service-destroy") }
        runCatching { KokoroTtsClient.stopPlayback() }
        scope.cancel()
        runCatching { workerThread.quitSafely() }
        super.onDestroy()
    }
}
