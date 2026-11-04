# INTEGRATION_NOTES_Swara.md — JarvisAgentApp 1.4.12-PRO

Swara (Microsoft Edge Read-Aloud voice `hi-IN-SwaraNeural`) is the PRIMARY
voice for ALL speak paths in 1.4.12. History in one line: 1.4.12 replaces
the 1.4.11 bundled on-device voice with this cloud voice; the old engine,
its isolated `:tts` process, its workflow bundle step, and its ~190 MB of
bundled assets are all deleted in the same change.

## Design (frozen 2026-11-04, implemented as specified)

- **Transport:** OkHttp 4.12.0 WebSocket (same stack Deepgram STT uses —
  no new dependency). One socket per utterance (`speak()` call).
- **Endpoint (pinned at build time):**
  `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1`
  + `TrustedClientToken` + `ConnectionId` (uuid hex, no dashes) +
  `Sec-MS-GEC` + `Sec-MS-GEC-Version=2-143.0.3650.75` (Chromium full
  version from the installed edge-tts package). Auth is the time-derived
  GEC token (`SwaraDrm`, a mechanical port of edge-tts
  `DRM.generate_sec_ms_gec_v2`) — no key, no account, no user credential.
  The URL is NEVER logged (token/GEC ride as query params).
- **Headers:** Edge UA, Origin
  `chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold`, Pragma /
  Cache-Control no-cache, Accept-Encoding, Accept-Language.
  `Sec-WebSocket-Version` is NOT set manually (OkHttp sets its own).
  Cookie: `muid=<32 hex upper, random per connection>`.
- **Handshake:** `Path:speech.config` FIRST (output format
  `audio-24khz-48kbitrate-mono-mp3`, sentenceBoundary on, wordBoundary
  off), then one `Path:ssml` per chunk.
- **SSML (edge-tts shape, replicated exactly):**
  `<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis'
  xml:lang='en-US'><voice name='hi-IN-SwaraNeural'><prosody
  pitch='+0Hz' rate='+0%' volume='+0%'>ESCAPED_TEXT</prosody></voice></speak>`
  — `xml:lang='en-US'` is hardcoded by edge-tts regardless of voice
  (not a mistake), and the SSML `X-Timestamp` trailing `Z` is a
  Microsoft Edge bug replicated by edge-tts (speech.config uses the
  date string WITHOUT the trailing `Z`). Single `<voice>` containing a
  single `<prosody>`; nothing else.
- **Text handling:** chars 0–8, 11–12, 14–31 become spaces, then
  XML-escape (`& < > " '`) PER CHUNK, so no entity can split across a
  chunk boundary.
- **Chunking:** char-based, cap ~3500 chars/chunk, preferring sentence
  boundaries (`. ! ?` Devanagari danda, newline). Sequential SSMLs go on
  the SAME socket within one `speak()` call; each SSML produces its own
  `turn.end`, and only the LAST request's `turn.end` ends the speak.
- **Streaming frames:** binary frames, first 2 bytes = big-endian header
  length; headers from `data[:header_length]` split by `\r\n`, each split
  by `:` (first colon); audio = `data[header_length + 2:]`. Text messages:
  headers via the `"\r\n\r\n"` index; `audio.metadata` / `response` /
  `turn.start` ignored, unknown text paths ignored (never fail the
  speak), `turn.end` counts. Binary with Content-Type other than
  `audio/mpeg` or None fails; no Content-Type + empty data is skipped
  (expected at stream termination); no Content-Type + data fails; empty
  data fails. No audio at all by `turn.end` fails (no-audio).
- **Playback (the fast-reply mechanism):** play on the FIRST `Path:audio`
  frame — never wait for `turn.end`. MP3 is decoded incrementally
  (MediaCodec `"audio/mpeg"` → 24 kHz mono s16le PCM) into a streaming
  AudioTrack, first-PCM-ASAP. The old PCM-only playback path is NOT
  reusable for MP3 and was deleted with the old client.
- **Timeouts (ALL exist; a Swara speak MUST return so `SPK_END` is
  always written):** socket connect 10 s (OkHttp, same as Deepgram);
  first audio 20 s from speak start; overall speak
  `min(180 s, 30 s + len*100 ms)` (same shape as the system-voice
  fallback bound). `stopSpeaking` / barge-in closes the socket, stops
  the track, flushes the decoder, and bumps the generation — the
  in-flight speak returns cancelled WITHOUT counting toward the latch.
  Airplane-mode/offline fails fast via the connect timeout, never hangs.
- **403 handling:** ONE token-regen retry + reconnect, and only when no
  audio has played yet. NO full clock-skew tracking: if the phone clock
  is off by >5 min, Edge fails, the latch trips, and the system voice
  speaks (never silent).
- **DRM porting rules (do NOT "simplify" `SwaraDrm.kt`):** every u32 word
  is a Long masked with `0xFFFFFFFFL`; logical shifts use `ushr`, never
  `>>`; Python floor-mod uses `Math.floorMod` (Kotlin `%` is truncated
  and differs for negatives); ticks double math
  (`ticks = unix + 11644473600; ticks -= ticks % 300; ticks *= 1e7`)
  matches edge-tts exactly because both sides use IEEE doubles.
  `hexUpper` masks each byte with `0xFF` (Java's Formatter sign-extends
  a Byte for `%X` — unmasked, every digest/MUID byte ≥ 0x80 would print
  as 8 chars and corrupt the token).

## Toggle + latch (1.4.12 semantics)

- **Pref key:** `swara_enabled` (default true). Migration: if
  `swara_enabled` is absent, the old key's value (default true) is copied
  over once — a 1.4.11 user who had voice OFF stays OFF.
- **BEHAVIOUR CHANGE (document this to the user):** Swara OFF no longer
  refuses to speak. OFF now means "system voice speaks" (visible:
  `TTS_FALLBACK_USED`, reason=Swara OFF) — Jarvis is never silent
  because of a toggle. The old OFF-refusal diagnostic is gone.
- **Latch:** bound 3, cooldown 10 min, clears ONLY on Test Voice tap /
  Settings-reset-equivalent (`SwaraTtsClient.resetConnection()` +
  `clearLatch("settings-reset")`) / Swara toggle-on / cooldown expiry.
  COUNTED: connect failure (incl. 403 after the one retry), first-audio
  timeout, turn timeout / socket failure mid-stream, speak timeout.
  NOT counted: blank text (rejected before speak), explicit
  stop/cancellation (barge-in), toggle-OFF (not a failure).
- **Markers (Swara-wide):** `SWARA_LATCHED`, `SWARA_LATCH_CLEARED`,
  `SWARA_LATCH_EXPIRED`, `SWARA_FAIL_COUNT`, `SWARA_UNAVAILABLE_LATCHED`,
  `SWARA_LATCHED_REFUSED`; stages `SWARA_CONNECT`, `SWARA_FIRST_AUDIO`
  (with ms latency in the detail — the fast-reply proof),
  `SWARA_STREAMING` (optional), `SWARA_END`, `SWARA_FAILED` (with cause:
  connect/synthesize/no-audio/timeout/cancelled). Fallback markers stay
  `TTS_FALLBACK_*`. Fresh 1.4.12 speaks NEVER emit the old markers
  (`KOKORO_*`, `NATIVE_TTS_PROCESS_DIED`, `TTS_FG_*`, `HASH_MISMATCH`,
  `SPK_ENGINE_*`, `SPK_GENERATE_*`, `SPK_PLAY_*`, `TTS_WAIT_SETUP`,
  `TTS_BIND_FAILED`, `TTS_PROCESS_TIMEOUT`, `TTS_ENSURE_FAILED`,
  `ENGINE_INIT_FAIL`, `EMPTY_AUDIO`, `PLAYBACK_ERROR`,
  `TTS_MINBUF_ERROR`, `TTS_TRACK_UNINITIALIZED`, `TTS_PLAY_NOT_STARTED`,
  `WRITE_FAIL`, `TTS_DRAIN_TIMEOUT`).
- **Old diagnostics history:** tolerated, no code change needed —
  `loadPersistedDiagnostics` seeds the mirror directly and never routes
  through `TestVoiceStageTracker`, so stale markers in history can never
  be misread as a Swara stage.
- **Test Voice stage display:** same arming invariant as 1.4.11 (arming
  ONLY via post-mutex `onSpeakStarted()`, never on pre-mutex `SPK_START`)
  — a test queued behind a running speak is not terminalized by that
  speak's `SPK_END`. Terminal success texts: Swara success
  "Bol diya — poori awaaz chali" + trail; fallback success
  "System voice se bol diya…" + reason (Swara latch / Swara OFF);
  failure "<stuck point> par atak gaya" + cause + trail. Never raw
  codes/text/keys; unknown terminal → generic-cause fallback.

## Removed in the same change (keep this list for the audit)

- `voice/KokoroTtsClient.kt`, `voice/TtsProcessClient.kt`,
  `service/KokoroTtsService.kt` (whole files), the `:tts` service
  declaration + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission
  (15 → 14 permissions — no new permission added, none needed:
  WebSocket needs no permission and AudioTrack from the main
  foreground service needs no mediaPlayback type).
- The first-run voice overlay + OFF-warning card + setup/reset buttons
  (MainActivity); the AI Brain card is now a Swara card (title
  "Swara Voice — hi-IN-SwaraNeural (Primary)", "Use Swara Voice"
  toggle-ON, "Swara reconnect" = drop socket + clear latch, Test Voice
  enabled ALWAYS except while working).
- Workflow "Bundle Kokoro model assets" step + in-APK voice-asset gate,
  replaced by a NEGATIVE gate (fail if `assets/kokoro/` present) + the
  kept VAD gate (`assets/silero_vad.onnx`, 643,854 bytes, SHA pinned in
  the workflow and mirrored in code as `AlwaysOnMicManager.PIN_VAD_*`).
  Workflow floor bumped: `PREV_VC=19` (previous delivered 1.4.11-PRO);
  the stale floor comment (17 = 1.4.9-PRO vs constant 18) is fixed.
- Sherpa AAR STAYS (Silero VAD only — `AlwaysOnMicManager` imports
  `SileroVadModelConfig`/`Vad`/`getVadModelConfig`; the VAD load chain
  is unchanged: workflow bundles `silero_vad.onnx`, the service copies
  it to filesDir, `SherpaVadMonitor` inits it).
- **Old model dir cleanup:** first 1.4.12 run deletes `files/Kokoro/`
  (~190 MB) on a background thread, best-effort, never blocking speech
  (`SwaraTtsClient.cleanupLegacyModelDir`, called from MainActivity
  launch). Rollback to 1.4.11 would re-copy from that APK's bundled
  assets, so no rollback path is lost.
- **VAD deferral, kept with reworded rationale:** VAD init is deferred
  until the greeting speak completes, to avoid VAD-init vs
  greeting-speak contention at launch; greeting speak is fast under
  Swara streaming, so the window shrinks naturally.
- **Greeting circuit breaker, kept (generic):** the
  GREET_START-without-SPK_END detector is engine-agnostic; Swara's
  bounded timeouts guarantee `SPK_END` is always written, so the breaker
  should almost never trip on Swara.
- **Chat-before-speak ordering:** no reorder was needed — greeting posts
  chat before speaking, announcements post chat before speaking, engine
  replies post `decision.reply` BEFORE `speak()` in the same block.
- **System-voice fallback 5 improvements** (Google TTS engine lock,
  quality-based hi-IN pick, network voice option, rate ~0.9/pitch 1.0,
  text cleanup) are NOT part of 1.4.12: `SystemTtsFallbackSpeaker.kt`
  is reused unchanged except header/comments.
- **Gemma chat download UNCHANGED:** `LocalLlmManager.kt` (Gemma 3 270M
  Q8, 304,005,120 bytes, `litert-community/gemma-3-270m-it`, token via
  `BuildConfig.HF_TOKEN`) shares no code with the old voice except
  `app/build.gradle.kts` itself; the Gemma `buildConfigField` was not
  touched. One voice for ALL text (incl. English replies —
  Indian-accented English is acceptable, spec-ordered); prosody defaults
  rate +0%, pitch +0Hz, volume +0% (edge-tts defaults); no speed slider.

## Phone-test proof note (for the parent / tester — NOT done by Phase 1)

Phase 1 is implementation only (no CI, no push, no phone test). Before
delivery, CI must prove: versionCode 20 / `1.4.12-PRO`, workflow floor
19, negative voice-asset gate + VAD gate, release signing on the same
keystore (install-over-1.4.11 licence preservation). Phone tests must
prove, per speak origin (greeting, reply, incomplete prompt, mic-error,
Test Voice, notification announce, generic-only call announce): Swara
voice heard, `SWARA_FIRST_AUDIO` latency recorded, chat rendered before
or while speech plays, airplane-mode fallback (never silent, latch
after 3, Test Voice reopens), toggle-OFF → system voice (not refusal),
queued-Test-Voice arming invariant, barge-in stops Swara + closes the
socket, VAD echo gate still suppresses Jarvis's own Swara voice, and
background announce playback from the main foreground service (the
`:tts` + mediaPlayback deletion makes this a phone test, not a code
claim). DRM proof: the token constant was patched BY SCRIPT from the
installed edge-tts package source (never typed by hand, never printed);
the Kotlin DRM draft's `hexUpper` sign-extension bug was caught and
fixed during Phase 1 (see above) — a live Swara speak on the phone is
still the only end-to-end DRM proof, since no local Gradle/phone run
exists in this environment.

## Safety gates — untouched in 1.4.12 (verified, not modified)

Sensitive-action code gate, negation veto, decline-abort, licence
bind/release/revoked, in-memory rate limits + `RATE_LIMITED`, preview
traversal closure, generic-only call announce (no `READ_PHONE_STATE` /
`READ_CALL_LOG` restoration), Deepgram-only mic policy (no system
recognizer return). None of those files were edited by Phase 1.
