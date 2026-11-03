package com.jarvis.autonomous.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * VoiceManager — Kokoro ONLY (1.4.0).
 *
 * SPEECH (output) is Kokoro hf_alpha, on-device via Sherpa-ONNX, and
 * nothing else. The 1.3.0 chain is gone, deliberately:
 *  - Deepgram TTS (cloud, user key) — REMOVED.
 *  - Gemini TTS Sulafat (cloud, user key, tiny free quota) — REMOVED.
 *  - Android system TextToSpeech (incl. its init, language setup and
 *    female-voice selection) — REMOVED as a voice.
 * 1.4.5 FINAL MIC POLICY (user order 2026-11-03 ~19:36 IST):
 * system SpeechRecognizer is REMOVED ENTIRELY — loop, one-shot,
 * wake-word, everywhere. Listening is Deepgram ONLY, via the
 * AlwaysOnMicManager session lambdas wired by the service
 * ([startWakeSession]/[stopWakeSession]/[listenOnceSession]/
 * [cancelListenOnceSession]). The old "System STT below is
 * UNTOUCHED" note is DEAD: there is no system STT left here.
 *
 * If Kokoro is unavailable, [speakAndWait] returns false and logs a
 * clear message. It NEVER silently falls back to another voice, and
 * it never leaves the caller guessing: false means "nothing was
 * spoken". Callers that only need fire-and-forget speech can ignore
 * the return value — the service lambda
 * `onSpeakAndAwait = { text -> voiceManager.speakAndWait(text) }`
 * still compiles unchanged, because a Boolean result is coerced to
 * Unit for a `suspend (String) -> Unit` function type.
 *
 * Signature change (documented, see INTEGRATION_NOTES_Kokoro.md):
 *  - 1.3.0: `suspend fun speakAndWait(text: String)` (Unit)
 *  - 1.4.0: `suspend fun speakAndWait(text: String): Boolean`
 * [stopSpeaking] is unchanged in signature.
 *
 * First-run behaviour: the first call triggers
 * KokoroTtsClient's bundled-asset copy (assets/kokoro/ -> files
 * dir Kokoro/) with progress logging. That copy must ALSO be
 * driven from the UI at app launch with a VISIBLE progress screen
 * (see INTEGRATION_NOTES_Kokoro.md §5) — this class only logs, it
 * cannot show a screen, so a speak-triggered copy is the safety
 * net, not the intended first-run UX.
 *
 * prefs.voiceSpeed / prefs.voicePitch are no longer applied here:
 * they only ever tuned system TTS. Kokoro speaks at its fixed
 * speed (1.0f in KokoroTtsClient). The prefs fields themselves
 * live in JarvisPreferences (outside this worker's ownership) and
 * are listed as dead in the integration notes until the
 * coordinator removes or rewires the slider.
 */
class VoiceManager(
    private val context: Context,
    private val prefs: JarvisPreferences,
    private val onWakeCommandDetected: (String) -> Unit,
    /**
     * A-H3 single mic-owner gate, wired by the service to
     * AlwaysOnMicManager.isEnabled(). EVERY recognizer start in
     * this class checks it first: the wake-word recognizer NEVER
     * runs alongside the mic-loop recognizer.
     */
    private val isMicLoopEnabled: () -> Boolean = { false },
    /**
     * 1.4.5 FINAL policy wiring (service -> AlwaysOnMicManager):
     * Deepgram-only wake/one-shot sessions. Defaults are no-ops
     * so unit callers without a service keep compiling; the
     * service MUST wire all four, or listening never starts.
     */
    private val startWakeSession: (((String) -> Unit) -> Unit) = { },
    private val stopWakeSession: (() -> Unit) = { },
    private val listenOnceSession: (((String?) -> Unit) -> Unit) = { },
    private val cancelListenOnceSession: (() -> Unit) = { }
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSingleReplyCallback: ((String?) -> Unit)? = null
    /** B-M13: ALL speaks serialise through this mutex. */
    private val speakMutex = Mutex()
    /**
     * 1.4.3: true while a Kokoro utterance is playing. Mic-tap
     * paths read this to QUEUE listening until TTS ends instead
     * of cutting speech mid-word (the old stopSpeaking() on tap
     * was a top "bolti hi nahi / band ho jati hai" cause).
     */
    @Volatile
    private var speakInProgress: Boolean = false
    // 1.4.5 (item 5): same-reason SPEAK_ABORT dedupe window — the
    // STOP itself always fires (idempotent); only the duplicate
    // log line is suppressed within 3 s of the same reason.
    private var lastAbortReason: String? = null
    private var lastAbortMs: Long = 0L

    /**
     * Speaks [text] with Kokoro only. Returns true if the full text
     * was spoken, false if nothing (or only part) was spoken — e.g.
     * Kokoro disabled in prefs, bundled model missing, free space
     * too low for the first-run copy, or playback interrupted by
     * [stopSpeaking]. On false, the reason is in the app log.
     */
    suspend fun speakAndWait(text: String): Boolean {
        if (text.isBlank()) return false
        // B-M13: serialised — concurrent speaks queue here instead
        // of racing Kokoro's shared track/stop flag. Signature
        // unchanged (suspend fun speakAndWait(String): Boolean).
        // 1.4.9 (Step 1e): the queue was unbounded AND unmarked —
        // a speak stuck behind a long utterance looked exactly
        // like a dead speak (SPK_START written upstream, then
        // silence). Now a wait over 5 s is marked with its
        // duration; the queue itself is unchanged (bounding it
        // would refuse queued speaks, a behaviour change).
        val queuedAtMs = System.currentTimeMillis()
        return speakMutex.withLock {
            val waitedMs = System.currentTimeMillis() - queuedAtMs
            if (waitedMs > 5_000L) {
                JarvisStateRepository.addDiagnostic(
                    "tts", "TTS_SPEAK_QUEUED",
                    "Speak waited ${waitedMs}ms on speakMutex before starting (concurrent speaks queue)."
                )
            }
            stopListeningInternal()

            if (!prefs.kokoroEnabled) {
                JarvisStateRepository.addLog(
                    "Kokoro voice OFF in settings — nothing spoken " +
                        "(Kokoro-only build: no other voice is used)."
                )
                // 1.4.6: toggle-OFF used to leave NO diagnostic —
                // only a log line — so a phone report with
                // SPK_END ok=false and no ENGINE markers could
                // not distinguish "toggle OFF" from a TTS failure.
                // Now it has its own code (KOKORO_OFF), distinct
                // from TTS_BIND_FAILED / ENGINE_INIT_FAIL /
                // HASH_MISMATCH / NATIVE_TTS_PROCESS_DIED.
                JarvisStateRepository.addDiagnostic(
                    "tts", "KOKORO_OFF",
                    "Speak refused: Kokoro voice OFF in settings — " +
                        "nothing spoken (toggle ON in AI Brain to speak)."
                )
                return@withLock false
            }

            // 1.4.5: synthesis runs in the ISOLATED :tts process
            // (TtsProcessClient) — a native Sherpa/ONNX death
            // during model load / generate kills only TTS, never
            // this UI process. On :tts death this returns false
            // (with tts/NATIVE_TTS_PROCESS_DIED recorded) instead
            // of the app vanishing; see KokoroTtsService.
            val focus = requestTransientFocus()
            speakInProgress = true
            val ok = try {
                TtsProcessClient.speak(context, text)
            } finally {
                speakInProgress = false
                abandonFocus(focus)
            }
            if (!ok) {
                JarvisStateRepository.addLog(
                    "Kokoro unavailable — nothing spoken " +
                        "(Kokoro-only build: no Deepgram / Gemini / " +
                        "system-TTS fallback exists any more)."
                )
                return@withLock false
            }

            // A-H3 speak-resume leak fix: a speak that ends while
            // the mic loop is enabled must NOT restart wake-word
            // listening — the loop already owns the mic. A-H9:
            // when wake-word mode is on and the loop is off, the
            // restart no longer depends on the agent being IDLE.
            if (prefs.wakeWordEnabled && !isMicLoopEnabled()) {
                startWakeWordListening()
            }
            true
        }
    }

    fun stopSpeaking() {
        stopSpeaking("unspecified (legacy call)")
    }

    /**
     * 1.4.3: EVERY speak abort is logged with its cause — the old
     * silent stop made "bolti hi nahi / band ho jati hai"
     * undebuggable. Only explicit stops (barge-in keyword,
     * STOP_TASK) cut speech; mic taps queue instead (see
     * triggerManualVoiceCommand / listenForSingleReply).
     */
    fun stopSpeaking(reason: String) {
        // B-M13: stops only the utterance currently playing.
        // Queued speaks (waiting on speakMutex) are untouched —
        // Stop never cancels the whole queue.
        // 1.4.5 (item 5): dedupe — one barge-in used to log TWO
        // SPEAK_ABORT entries (handleTranscript + this call).
        // The STOP still fires every time; only the repeat log
        // within 3 s of the same reason is skipped.
        val nowMs = System.currentTimeMillis()
        if (!(reason == lastAbortReason && nowMs - lastAbortMs < 3_000L)) {
            lastAbortReason = reason
            lastAbortMs = nowMs
            JarvisStateRepository.addDiagnostic(
                "tts", "SPEAK_ABORT", "Speech stopped: $reason"
            )
        }
        // 1.4.5: playback lives in the :tts process — stop it
        // there. (The old main-process stopPlayback() call is
        // gone: no AudioTrack exists in this process any more.)
        TtsProcessClient.stop()
    }

    /** B-M13: transient focus for one utterance; null if unavailable. */
    private fun requestTransientFocus(): Any? {
        return runCatching {
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioManager.requestAudioFocus(request)
                request
            } else {
                @Suppress("DEPRECATION")
                val listener = AudioManager.OnAudioFocusChangeListener { }
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                listener
            }
        }.getOrNull()
    }

    private fun abandonFocus(focus: Any?) {
        if (focus == null) return
        runCatching {
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Branch on SDK FIRST: the AudioFocusRequest type must
            // never even be resolved on pre-O devices, where the
            // class does not exist.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(focus as AudioFocusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(
                    focus as AudioManager.OnAudioFocusChangeListener
                )
            }
        }
    }

    /**
     * Listens for a single user voice response (e.g., when mic button is tapped or Jarvis asks a mid-task question).
     */
    suspend fun listenForSingleReply(): String? {
        // A-H3: while the mic loop owns the mic, no second
        // recognizer may start here — the service answers single
        // replies from the loop's next turn instead.
        if (isMicLoopEnabled()) {
            JarvisStateRepository.addLog(
                "Single reply not started: mic loop owns the mic."
            )
            return null
        }
        return withTimeoutOrNull(12_000L) {
            // 1.4.3: queue BEHIND any playing TTS instead of
            // cutting it — the old stopSpeaking() here was why
            // Jarvis "bolti hi nahi / band ho jati hai" when the
            // user tapped mic mid-speech. Wait up to 8s for the
            // utterance to finish, then listen without stopping.
            val waitStart = System.currentTimeMillis()
            while (speakInProgress &&
                System.currentTimeMillis() - waitStart < 8_000L
            ) {
                delay(200L)
            }
            suspendCancellableCoroutine { cont ->
            mainHandler.post {
                // Deepgram-only single shot (1.4.5): stop any wake
                // session first (one mic owner), then listen once.
                stopWakeSession()
                pendingSingleReplyCallback = { recognizedText ->
                    if (cont.isActive) cont.resume(recognizedText)
                }
                listenOnceSession { text ->
                    val cb = pendingSingleReplyCallback
                    pendingSingleReplyCallback = null
                    cb?.invoke(text)
                }
            }
            cont.invokeOnCancellation {
                mainHandler.post {
                    pendingSingleReplyCallback = null
                    cancelListenOnceSession()
                }
            }
            }
        }
    }

    fun triggerManualVoiceCommand() {
        // A-H3: overlay mic tap while the mic loop runs must not
        // start a second recognizer — the loop is already
        // listening, so the tap is a no-op (not a double start).
        if (isMicLoopEnabled()) {
            JarvisStateRepository.addLog(
                "Mic tap ignored: mic loop already listening."
            )
            return
        }
        // 1.4.3: a mic tap during TTS QUEUES listening until the
        // utterance ends — it never cuts speech. Only an explicit
        // stop (voice "ruk"/"bas", STOP button) stops TTS.
        val startTapListening = object : Runnable {
            var tries = 0
            override fun run() {
                if (speakInProgress && tries < 32) {
                    tries++
                    mainHandler.postDelayed(this, 250L)
                    return
                }
                JarvisStateRepository.setAgentState(
                    AgentRunState.LISTENING,
                    "Listening... बोलो भाई क्या करना है?"
                )
                // Deepgram-only single shot (1.4.5): wake session
                // stops first — one mic owner at a time.
                stopWakeSession()
                pendingSingleReplyCallback = { text ->
                    if (!text.isNullOrBlank()) {
                        onWakeCommandDetected(text)
                    } else {
                        JarvisStateRepository.setAgentState(
                            AgentRunState.IDLE,
                            "Didn't catch that. Tap Core to speak."
                        )
                        if (prefs.wakeWordEnabled) startWakeWordListening()
                    }
                }
                listenOnceSession { text ->
                    val cb = pendingSingleReplyCallback
                    pendingSingleReplyCallback = null
                    cb?.invoke(text)
                }
            }
        }
        mainHandler.post(startTapListening)
    }

    /**
     * Deepgram-only wake-word listening (1.4.5 FINAL policy).
     * Pref + RECORD_AUDIO + loop-off gate, then the manager's
     * continuous Deepgram session. Finals arrive via the session
     * callback; [matchWakeTrigger] decides (start/bare only);
     * non-matches are ignored and the session KEEPS running.
     * On a match the session stops first (one mic owner), then
     * the remainder goes to the engine or a bare trigger opens
     * the manual command listener.
     */
    fun startWakeWordListening() {
        if (!prefs.wakeWordEnabled) return
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            JarvisStateRepository.addLog(
                "Wake-word not started: RECORD_AUDIO not granted."
            )
            return
        }
        mainHandler.post {
            // A-H3 single-owner gate: never next to the loop, and
            // never while a single reply is pending.
            if (isMicLoopEnabled()) return@post
            if (pendingSingleReplyCallback != null) return@post
            startWakeSession { text ->
                if (!prefs.wakeWordEnabled || isMicLoopEnabled()) return@startWakeSession
                val matched = matchWakeTrigger(text) ?: return@startWakeSession
                val (trigger, remainder) = matched
                stopWakeSession()
                if (remainder.isNotEmpty()) {
                    onWakeCommandDetected(remainder)
                } else {
                    // Bare "Hey Jarvis" -> open the active command
                    // listener (which itself stops the wake session
                    // first — already stopped here, idempotent).
                    triggerManualVoiceCommand()
                }
            }
        }
    }

    /**
     * A-M4: returns (trigger, remainder) when [text] is addressed
     * to Jarvis, else null. Normalised first (punctuation stripped,
     * case folded). Valid forms, longest trigger first:
     * "hey jarvis" / "ok jarvis" / "sun jarvis" / "jarvis", either
     * bare or at the START followed by a command. A trigger in the
     * middle ("aaj jarvis ko mat bulao") never matches, and a
     * postposition right after the trigger ("jarvis KO mat bulao"
     * — Jarvis as grammatical object, not addressee) never
     * matches either.
     */
    private fun matchWakeTrigger(text: String): Pair<String, String>? {
        val tokens = TurnDetector.normalizeTokens(text)
        if (tokens.isEmpty()) return null
        val joined = tokens.joinToString(" ")
        val triggers = listOf("hey jarvis", "ok jarvis", "sun jarvis", "jarvis")
        for (trigger in triggers) {
            if (joined == trigger) return trigger to ""
            if (joined.startsWith("$trigger ")) {
                val remainderTokens = tokens.drop(trigger.split(" ").size)
                // Talking ABOUT Jarvis, not TO Jarvis.
                if (remainderTokens.firstOrNull() in WAKE_OBJECT_MARKERS) return null
                return trigger to remainderTokens.joinToString(" ")
            }
        }
        return null
    }

    fun stopListeningInternal() {
        mainHandler.post {
            // Deepgram-only teardown (1.4.5): stop the wake session
            // and cancel any one-shot; complete any pending single
            // reply as cancelled so a stale reply never swallows
            // the next command.
            stopWakeSession()
            cancelListenOnceSession()
            val cb = pendingSingleReplyCallback
            pendingSingleReplyCallback = null
            cb?.invoke(null)
        }
    }

    fun shutdown() {
        mainHandler.post {
            stopWakeSession()
            cancelListenOnceSession()
            val cb = pendingSingleReplyCallback
            pendingSingleReplyCallback = null
            cb?.invoke(null)
        }
    }

    companion object {
        /** Postpositions marking Jarvis as object, not addressee. */
        private val WAKE_OBJECT_MARKERS: Set<String> = setOf(
            "ko", "ka", "ki", "ke", "se", "mein", "me", "par", "tak"
        )
    }
}
