# INTEGRATION NOTES — S1 Always-On Mic (JarvisAgentApp 1.4.0)

**Worker:** S1 (mic + turn detection). **Scope discipline:** this worker
created ONLY three new files and edited NO existing file. Everything
below is wiring the **coordinator must apply** to existing files. Do not
treat these notes as applied code — until the coordinator applies them,
the old VoiceManager single-shot behaviour is still live.

## 1. Files created (only these)

| File | Contents |
|---|---|
| `app/src/main/java/com/jarvis/autonomous/voice/AlwaysOnMicManager.kt` | `AlwaysOnMicManager`, `AlwaysOnMicListener`, `StopSpeakingCallback` |
| `app/src/main/java/com/jarvis/autonomous/voice/TurnDetector.kt` | `TurnThresholds`, `TurnOutcome`, `TurnDetector`, `BargeInDetector` |
| `INTEGRATION_NOTES_Mic.md` (this file) | Wiring spec |

Nothing was pushed, no version number was touched. **Sherpa VAD — removed 1.4.23; mic is system SpeechRecognizer only.** (The Sherpa AAR at `app/libs/sherpa-onnx.aar` no longer exists — no Sherpa dependency, no AAR download, nothing to commit.)

## 2. The bug being fixed (one paragraph)

`VoiceManager` uses the system `SpeechRecognizer`, which ends its own
session on a short silence. A user pause ("halt") therefore ended the
turn and Jarvis acted on a partial command. The fix inverts control:
the recognizer is now a dumb audio-to-text tap that is restarted on
every ending, and **our** `TurnDetector` (silence + sentence
completeness) is the only thing that ends a turn.

## 3. Turn-end thresholds (chosen, per brief)

| Constant | Value | Meaning |
|---|---|---|
| `TurnThresholds.SHORT_SILENCE_MS` | 1000 ms | Pause shorter than this = keep waiting, always. A "halt" never ends a turn. |
| `TurnThresholds.TURN_END_SILENCE_MS` | 1750 ms (in the 1.5–2 s band) | Silence this long after the last partial AND a complete-looking sentence = turn end. |
| `TurnThresholds.INCOMPLETE_EXTRA_WAIT_MS` | 5000 ms | After the "aur bolo" prompt, wait this long for continuation; then emit the buffered text as Complete anyway (never hang). |
| `TurnThresholds.TURN_CHECK_INTERVAL_MS` | 250 ms | How often the manager polls `TurnDetector.check()`. |
| `TurnDetector.INCOMPLETE_EMITTED_SAFETY_MS` | 15000 ms | Safety net: if the coordinator never calls `continueAfterIncompletePrompt()`, the buffered text completes 15 s after the Incomplete signal instead of hanging. |

**Incomplete signals** (`TurnDetector.incompleteReason()`): (a) the
utterance ends with a conjunction/preposition/subordinator (Hinglish:
aur, lekin, par, pe, me, se, ka, ki, ke, ko, tak, jab, agar, phir…;
English: and, or, but, with, for, of, to…); (b) 2+ words with no verb
token and no question word ("youtube par gaana" = fragment, while
"aaj ki date" is NOT flagged because "date" is a question token).
Deliberate carve-outs, because
false "incomplete" prompts are annoying: single words in
`COMPLETE_SINGLE_WORDS` (haan, yes, nahi, ruk, bas, hello, ok…) are
complete; any question word (kya, kitne, kab, kahan, kaun, time, date,
mausam…) completes the turn without a verb. The heuristic is
conservative by design: a false Incomplete costs one prompt, a false
Complete costs acting on half a command — and the 5 s fallback bounds
both mistakes.

## 4. Public API (exact signatures — coordinator, copy these types)

```kotlin
// AlwaysOnMicManager.kt
class AlwaysOnMicManager(context: Context, listener: AlwaysOnMicListener)
fun AlwaysOnMicManager.start(): Boolean
fun AlwaysOnMicManager.stop()
fun AlwaysOnMicManager.shutdown()
fun AlwaysOnMicManager.isEnabled(): Boolean
fun AlwaysOnMicManager.isListeningActive(): Boolean
fun AlwaysOnMicManager.isRecordAudioGranted(): Boolean
fun AlwaysOnMicManager.currentLanguage(): String            // "hi-IN" | "en-IN"
fun AlwaysOnMicManager.setStopSpeakingCallback(callback: StopSpeakingCallback?)
fun AlwaysOnMicManager.setSpeaking(speaking: Boolean, spokenText: String = "")
fun AlwaysOnMicManager.continueAfterIncompletePrompt()
fun AlwaysOnMicManager.buildBatteryExemptionIntent(): Intent?
@Volatile var AlwaysOnMicManager.isSpeaking: Boolean        // read-only outside (private set)
@Volatile var AlwaysOnMicManager.currentSpokenText: String  // read-only outside (private set)

interface AlwaysOnMicListener {
    fun onTurnComplete(text: String)
    fun onTurnIncomplete(text: String, reason: String)
    fun onMicStoppedByUser()
    fun onPartialTranscript(text: String)
    fun onListeningStateChanged(listening: Boolean)
    fun onMicError(reason: String)
}

fun interface StopSpeakingCallback {
    fun stopSpeaking()
}

// TurnDetector.kt
object TurnThresholds {
    const val SHORT_SILENCE_MS: Long            // 1000
    const val TURN_END_SILENCE_MS: Long         // 1750
    const val INCOMPLETE_EXTRA_WAIT_MS: Long    // 5000
    const val TURN_CHECK_INTERVAL_MS: Long      // 250
}
sealed interface TurnOutcome {
    data class Complete(val text: String) : TurnOutcome
    data class Incomplete(val text: String, val reason: String) : TurnOutcome
    data object Waiting : TurnOutcome
}
class TurnDetector {
    fun currentText(): String
    fun hasText(): Boolean
    fun onPartial(text: String, nowMs: Long, isNewSession: Boolean)
    fun onSpeechStarted(nowMs: Long)
    fun onFinal(text: String, nowMs: Long, isNewSession: Boolean)
    fun check(nowMs: Long): TurnOutcome
    fun markIncompletePromptSent(nowMs: Long)
    fun reset()
    fun incompleteReason(text: String): String?
    companion object {
        val MIC_STOP_PHRASES: List<String>
        fun isMicStopCommand(text: String): Boolean
    }
}
object BargeInDetector {
    const val LOUD_MARGIN_DB: Float             // 3.0
    fun isBargeIn(transcript: String, isSpeaking: Boolean, spokenText: String,
                  rmsDb: Float?, rmsBaselineDb: Float?): Boolean
    fun remainderAfterKeyword(transcript: String): String
}

// SherpaVadMonitor — REMOVED 1.4.23. Sherpa VAD is gone; mic is system
// SpeechRecognizer only. (Was: same file as the manager; optional, off by default.)
```

**Partial-buffer semantics (important for the coordinator's mental
model):** system partials are cumulative *within* one recognizer
session, so a same-session partial REPLACES the buffer; after a
restart, a new-session partial is APPENDED unless it already starts
with the buffer. That is how a pause ("halt") + continuation survives
the recognizer restarting underneath it.

## 5. Exact wiring the coordinator must apply

### 5.1 Construction — `service/JarvisForegroundService.kt`, `onCreate()`

Today the service builds `VoiceManager` first, then `JarvisAgentEngine`
with `onSpeakAndAwait = { voiceManager.speakAndWait(it) }` and
`onRequestUserVoiceReply = { voiceManager.listenForSingleReply() }`,
and `VoiceManager` with `onWakeCommandDetected = { engine.handleUserCommand(it) }`.
Keep that order problem in mind: the mic manager needs the engine for
turns and the engine needs the voice manager for speech. Wire it in
this order (new code only; existing lines otherwise untouched):

```kotlin
// 1. Build voiceManager and agentEngine exactly as today.
// 2. THEN build the mic manager (it needs both to exist):
val micManager = AlwaysOnMicManager(this, object : AlwaysOnMicListener {
    override fun onTurnComplete(text: String) {
        agentEngine.handleUserCommand(text)   // non-blocking: engine launches its own job
    }
    override fun onTurnIncomplete(text: String, reason: String) {
        // Speak the prompt WRAPPED in setSpeaking (see 5.3), then:
        // (suspend context required — launch on the service/engine scope)
        //   voiceManager.speakAndWait("Aur bolo, poora sun raha hoon.")
        //   micManager.continueAfterIncompletePrompt()
    }
    override fun onMicStoppedByUser() {
        // Mic loop already stopped itself. Update UI state only.
        // Do NOT stop the foreground service or the engine here.
    }
    override fun onPartialTranscript(text: String) { /* HUD "hearing…" text only */ }
    override fun onListeningStateChanged(listening: Boolean) { /* HUD mic dot */ }
    override fun onMicError(reason: String) { /* log + HUD; mic is not running */ }
})
// 3. Barge-in wiring (see 5.3):
micManager.setStopSpeakingCallback { voiceManager.stopSpeaking() }
```

**Where `handleUserCommand` hands off to the mic loop:** it does not
change. `JarvisAgentEngine.handleUserCommand` (agent/JarvisAgentEngine.kt:265)
stays the single entry point for every turn — mic turns, typed turns
(`ACTION_TEXT_COMMAND`), and wake-word turns all converge there.
The mic manager NEVER calls `tryNoAiRoutine` (:80) or
`runAutonomousLoop` (:301) directly; it only delivers a completed
string to `handleUserCommand`, which dispatches routines vs. the LLM
loop exactly as today. The mic loop also never blocks a task: turns
arrive on the main thread, `handleUserCommand` returns immediately
(it launches `activeTaskJob`), and the mic keeps listening while the
task runs. A turn that lands mid-task therefore cancels/replaces the
running task under today's engine rules — that is existing behaviour,
not something this worker changed.

### 5.2 Starting / stopping — session rule (read this whole section)

**Mic-once-on-stays-on:** once the user enables the mic, the session
persists **across tasks** (a task finishing must NOT call
`micManager.stop()`) **and across app backgrounding** while the
foreground service lives (the service, not any Activity, owns the
manager). The ONLY off switches are:

1. The user's explicit **"mic band karo"** (or `mic band`, `mic off`,
   `mic stop karo`, `microphone band karo`) — the manager detects
   this at turn end, stops itself, and fires `onMicStoppedByUser()`.
   It is never forwarded to the engine as a command.
2. The **Settings mic toggle** (coordinator-owned UI + prefs flag;
   this worker added no prefs key and no UI) — toggle OFF calls
   `micManager.stop()`; toggle ON calls `micManager.start()`.

**Android limit, stated honestly:** if the system kills the foreground
service, the mic is dead with it. On service restart (`START_STICKY`
in `JarvisForegroundService`), the coordinator must call
`micManager.start()` again (gated on the Settings toggle still being
ON and `RECORD_AUDIO` still granted) — the mic restarts *with the
service*, it is not magic and no code here can listen without a live
service process. Likewise, do NOT call `micManager.stop()` from
`onDestroy()` paths that are really service restarts; call
`micManager.shutdown()` only in the service's real `onDestroy()`.

**Do NOT confuse these three "stop" words** (they are different
switches and the coordinator must keep them apart):

| User says | When | Effect |
|---|---|---|
| "mic band karo" (+ variants) | Anytime, as a completed turn | Mic loop stops. Task keeps running or not — untouched. |
| "ruk" / "ruko" / "bas" / "stop" | While Jarvis is speaking (TTS) | **Barge-in:** speech stops immediately (see §6); mic keeps listening; the remainder after the keyword (if any) seeds the next turn. |
| "ruk" / "ruko" / "bas" / "stop" | While Jarvis is NOT speaking, as a completed turn | Goes to the engine as today (`handleUserCommand` → `cancelActiveTask`). Mic keeps listening. |

### 5.3 Speaking + the `isSpeaking` flag — REQUIRED, not optional

Every `speakAndWait` call site that the coordinator routes through the
mic era must be wrapped, or the echo gate (§6) and the barge-in path
cannot work. The cleanest place is one wrapper in the service (so
`VoiceManager.speakAndWait` itself is untouched):

```kotlin
// In JarvisForegroundService (new helper; engine's onSpeakAndAwait
// should point at THIS, not directly at voiceManager.speakAndWait):
private suspend fun speakWrapped(text: String) {
    micManager.setSpeaking(true, text)
    try {
        voiceManager.speakAndWait(text)
    } finally {
        micManager.setSpeaking(false)
    }
}
```

Notes: (a) `setSpeaking(false)` also resets the VAD so stale
own-voice audio is dropped — do not skip the `finally`. (b) The
"aur bolo" prompt and the post-task prompt (§5.5) go through this
same wrapper. (c) `VoiceManager.stopSpeaking()` already stops Swara
/ system TTS — that is why the barge-in callback
points at it; no TTS change is needed. (d) `VoiceManager`'s own
`startWakeWordListening()` / `listenForSingleReply()` paths are
superseded by the mic loop for the always-on session; leave them in
place for non-mic flows, but do NOT run them concurrently with
`micManager` — two recognizer owners will fight over the mic and the
restart loop will thrash.

### 5.4 Mid-task questions — replace `listenForSingleReply` for mic sessions

`runAutonomousLoop`'s `askUser` branch and the no-AI routines'
confirmation questions (`onRequestUserVoiceReply`, today
`voiceManager.listenForSingleReply()` — a 12 s single-shot) must NOT
use a second recognizer while the mic loop owns the mic. Coordinator
options, in order of preference:

1. **Preferred:** bridge the next `onTurnComplete` into the reply:
   a `CompletableDeferred<String?>` that `onTurnComplete` completes
   when the engine is in `WAITING_USER_REPLY` state, with the existing
   timeout behaviour preserved by the caller. The mic loop's turn
   (with its pause tolerance) is then the reply — a user who pauses
   mid-answer is not cut off at 12 s.
2. **Fallback:** keep `listenForSingleReply` only for flows where the
   mic loop is provably stopped (mic toggle OFF), and document that
   the pause bug still applies there.

Do not run both at once.

### 5.5 Prompts — where each one plugs in

* **"Aur bolo, poora sun raha hoon"** — `onTurnIncomplete` (§5.1).
  Sequence: manager emits Incomplete once → coordinator speaks the
  prompt via `speakWrapped` → coordinator calls
  `micManager.continueAfterIncompletePrompt()` → the detector's ~5 s
  continuation window starts. If the user continues with a complete
  sentence + 1.75 s silence, the turn completes immediately (no need
  to wait out the 5 s); if nothing arrives, the buffered text
  completes at the window end. If the coordinator forgets the
  `continueAfterIncompletePrompt()` call, the 15 s safety net (§3)
  still completes the turn — but do not rely on it.
* **"Aur kya karna hai?" post-task prompt** — `runAutonomousLoop`
  completion paths in `JarvisAgentEngine.kt`: the `isCompleted /
  action == "NONE"` return ("Task Completed — Ready") and the
  max-steps branch (which already speaks "Aage kya karna hai
  batao?"). No mic wiring is needed there beyond this rule: **the
  mic stays ON across task completion** — do not stop/restart the
  loop around tasks, and do not gate the prompt on mic state. The
  prompt is just a `speakWrapped` call; the already-running loop
  hears the answer as the next turn. The same applies to no-AI
  routine completions (they speak a confirmation and return; the
  loop keeps listening).

## 6. Echo gate — exactly how it works (S1 echo amendment)

Swara speaking while the mic listens would otherwise make the
recognizer hear Jarvis's own voice as user speech. The echo gate is
keyed off the single `isSpeaking` flag (§5.3):

1. **VAD gate (segments) — REMOVED 1.4.23.** Sherpa VAD is gone; mic is
   system SpeechRecognizer only. Echo gating now lives in the
   recognizer transcript path (barge-in scan + quarantine +
   echo-fingerprint stack — see `AlwaysOnMicManager.kt` header), not
   in a VAD segmenter. The remaining live gate is #2 below.
2. **Recognizer barge-in gate (keywords only):** recognizer
   transcripts arriving during TTS are still scanned, but the ONLY
   action they can trigger is a barge-in via `BargeInDetector`:
   the transcript must START with `ruk` / `ruko` / `bas` / `chup` /
   `stop` / `ruk ja`; if Jarvis's own current text starts with the
   same keyword, the barge-in is rejected (echo guard — Jarvis must
   not interrupt himself); and when RMS levels are known, the
   keyword must be louder than the non-speaking RMS baseline by
   3 dB (`LOUD_MARGIN_DB`) — the "loud" qualifier. When RMS is
   unknown, keyword + echo guard alone decide. On barge-in, the
   manager calls `stopSpeakingCallback` (→ `VoiceManager.stopSpeaking()`),
   clears `isSpeaking`, and seeds any remainder after the keyword
   ("ruk, youtube band karo" → next turn starts with "youtube band
   karo"); a bare "ruk" seeds nothing and the user restates.
   **Honest limit:** this is best-effort, not a guarantee. A quiet
   user over a loud speaker can be missed, and a loud echo that
   happens to start with "ruk" can false-trigger. Ordinary
   transcripts during TTS are buffered normally and only become a
   turn via the silence timer — this class never acts on them
   mid-speech.

## 7. Battery — honest statement (S1 battery amendment)

**Always-on mic + VAD costs battery. Do not claim zero drain.**
Continuous listening prevents deep sleep, keeps the recognizer
service active, and (if VAD is enabled) runs an audio loop + periodic
ONNX inference. Expected effect: materially higher drain than
wake-word-only or mic-off use; the exact percentage is device- and
usage-dependent and is NOT stated here because it was not measured
on a device. What this worker did to limit it:

* **Foreground service + persistent notification (Android
  requirement):** continuous mic listening from the background is
  only allowed through a foreground service with a microphone type
  and an ongoing notification. That already exists
  (`JarvisForegroundService`, `foregroundServiceType="microphone|specialUse"`,
  ongoing notification) — the mic loop must never run outside it.
* **Battery-optimisation exemption prompt on first enable:** the
  coordinator, on the FIRST mic enable only, launches
  `micManager.buildBatteryExemptionIntent()` (an
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent for this
  package; the manifest already declares
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) so Doze does not kill the
  service mid-session. If the user declines, the service may still
  be stopped by the system — that is the §5.2 limit, and the UI
  should say so rather than promising uninterrupted listening.
* **VAD idle low-CPU behaviour:** the VAD loop reads 16 kHz mono
  PCM, but frames below RMS 0.005 are never sent to the ONNX model
  (an RMS computation, not an inference), and speech frames are fed
  in 512-sample (32 ms) slices with 1 thread. This cuts VAD CPU when
  idle; it does not make listening free. The recognizer-only primary
  path adds no app-side audio CPU at all (the system service does
  the recognition) — another reason it is the primary path.
* **User control is the real battery feature:** the Settings mic
  toggle (§5.2) is the off switch. Recommend the UI copy state the
  trade-off plainly ("mic on = more battery use") instead of hiding
  it.

## 8. STT language — Hinglish code-switch (S1 language amendment)

**What ships:** recognizer locale `hi-IN` primary, `en-IN` fallback
(`LANGUAGE_PRIMARY_HI_IN` / `LANGUAGE_FALLBACK_EN_IN` in
`AlwaysOnMicManager`). This replaces `VoiceManager`'s hard-coded
`en-IN` for mic-loop sessions.

**How the fallback triggers:** if a whole recognizer session ends
with NO transcript at all — empty `onResults`, or `ERROR_NO_MATCH` /
`ERROR_SPEECH_TIMEOUT` with an empty turn buffer — the next session
retries in `en-IN`, once. The language resets to `hi-IN` after every
completed turn (`resetLanguageToPrimary()`), so one empty session
does not permanently flip the locale. A session that produced ANY
partial text never triggers the fallback (a partial means the locale
is hearing the user; the turn timer handles the rest).

**Honest limit:** locale support is device/recognizer-dependent.
`hi-IN` on the system recognizer handles Hinglish code-switch
reasonably in practice, but this worker verified no on-device
transcription — the fallback is a retry order, not a guarantee, and
the coordinator should keep the HUD transcript visible so the user
can see what was actually heard.

## 9. Sherpa VAD — removed 1.4.23

Sherpa VAD is fully removed (1.4.23-PRO mic policy: system
SpeechRecognizer only — see `AlwaysOnMicManager.kt` header). The S1
VAD path documented here was the verified API evidence for sherpa-onnx
1.13.4 (`com.k2fsa.sherpa.onnx.Vad`, Silero VAD); it no longer applies
— no Sherpa AAR, no `SherpaVadMonitor`, no VAD model file, no ONNX
inference in the mic path.

## 10. Permission audit — NO new permission needed (coordinator-verified by grep)

Manifest (`app/src/main/AndroidManifest.xml`) already declares:
`RECORD_AUDIO`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
`POST_NOTIFICATIONS`, `SYSTEM_ALERT_WINDOW`, `INTERNET`, `WAKE_LOCK`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `RECEIVE_BOOT_COMPLETED`
(plus `FOREGROUND_SERVICE` type `microphone|specialUse` on
`JarvisForegroundService`). `MainActivity` already requests
`RECORD_AUDIO` (+ `POST_NOTIFICATIONS` on Android 13+) at runtime
and already shows a battery-unrestricted status check
(`isBatteryUnrestricted` via `isIgnoringBatteryOptimizations`).

**Coordinator checklist (despite "no new permission", these checks
are mandatory):**

1. Before `micManager.start()`: `micManager.isRecordAudioGranted()`
   (the manager itself refuses to start without it and reports
   `onMicError`) AND the foreground service is running AND the
   Settings mic toggle is ON. All three, every start — including
   after a service restart (§5.2).
2. On first mic enable: launch `buildBatteryExemptionIntent()` (§7).
   Do not re-prompt on every enable.
3. If `RECORD_AUDIO` is revoked at runtime, the manager's next
   `startRecognizerSession` fails safe (error → `onMicError`, loop
   stops); the coordinator must surface that in the UI and must not
   silently re-request in a loop.

## 11. What this worker deliberately did NOT do

* No existing file was edited (strict file ownership). In
  particular: `VoiceManager.kt` (old single-shot paths left intact),
  `JarvisAgentEngine.kt` (`handleUserCommand` / `tryNoAiRoutine` /
  `runAutonomousLoop` untouched), `JarvisForegroundService.kt`,
  `AndroidManifest.xml`, and all version numbers are untouched.
* No wake word was added (user did not ask for one in this release).
* No prefs key, Settings UI, or HUD change was made — those are
  coordinator-owned (§5.2).
* No phone/device testing was done (no device in this environment);
  the turn logic is pure Kotlin and device-independent, but
  recognizer restart behaviour, mic sharing, and battery drain are
  phone-verified items for the coordinator, not claims made here.
* The VAD model file was NOT downloaded or bundled (no verified
  model URL was in scope; bundling an unverified binary would be
  worse than documenting its absence).

## 12. Compile verification + pre-push checks applied

* `kotlin_prepush_checks` applied: all state in both new files is
  declared above the callbacks/runnables that use it (verified by
  reading, not by comment); nullable values are guarded with early
  returns / `?:` before non-null use (e.g. `vad ?: break`,
  `record ?: break`, `rmsBaselineDb` smart-cast via a local copy
  pattern); no function shares a name with a property setter;
  no lambda assigned to a variable relies on an unlabeled early
  return; brace/paren balance re-read after the two post-write
  fixes (Incomplete single-emission guard, mic-stop fast-track).
* Local Gradle compile: attempted — see the worker's final report
  for the exact outcome (environment result, not claimed here).
* Known review notes for the coordinator's own compile pass: the
  two new files reference only AndroidX core (already a dependency)
  — the sherpa AAR reference is removed 1.4.23; `data object` requires
  Kotlin 1.9+ (project uses 2.4.0, fine); `SpeechRecognizer`
  callbacks run on the main thread by contract, which is why all
  recognizer work in the manager is posted to the main handler.
