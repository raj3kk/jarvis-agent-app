# INTEGRATION NOTES — JarvisAgentApp 1.4.0, Kokoro-only bundled voice (worker S2)

> Scope of this worker: voice stack only. Files owned and changed are
> listed in §1. `MainActivity.kt` was NOT edited (outside this
> worker's ownership) — §2 gives the coordinator the exact UI
> removals to make there. Nothing was pushed, and no version number
> was touched (`versionCode 7` / `versionName 1.3.0-PRO` in
> `app/build.gradle.kts` are unchanged; the coordinator bumps them
> for 1.4.0).

## 1. Files changed by S2

| File | Change |
|---|---|
| `app/src/main/java/com/jarvis/autonomous/voice/VoiceManager.kt` | **Rewrite, Kokoro only.** System `TextToSpeech` init / language / female-voice selection, Deepgram chain, and Gemini chain all removed. `speakAndWait` now returns `Boolean` (§3). System STT (`SpeechRecognizer` wake-word + single-reply) untouched. |
| `app/src/main/java/com/jarvis/autonomous/voice/KokoroTtsClient.kt` | **Asset-first loading + first-run copy + free-space check** (§5). `download()` kept but `@Deprecated(WARNING)` so existing UI still compiles (§6). Engine loading (`OfflineTts`, hf_alpha sid 31, speed 1.0f) unchanged. |
| `app/build.gradle.kts` | `ndk { abiFilters += "arm64-v8a" }` in `defaultConfig` (§4); comment explaining why no aapt `noCompress` rule was added. No `buildConfigField` added or moved (the existing `HF_TOKEN` field stays exactly where it was, inside `defaultConfig`). |
| `.github/workflows/build-apk.yml` | New step **before** the Gradle build: downloads the sherpa-onnx Kokoro tarball and extracts it into `app/src/main/assets/kokoro/`, with size checks (§7). Verify step now gates on the bundled model being inside the release APK. |
| `INTEGRATION_NOTES_Kokoro.md` (this file) | Coordinator handoff. |

**NOT changed, deliberately:**
- `DeepgramTtsClient.kt` / `GeminiTtsClient.kt` — **not deleted**.
  They are now unreferenced by `VoiceManager`. Deletion is left out
  because the push helper may not carry deletions; unreferenced code
  is acceptable. The coordinator may delete both files in a later
  cleanup commit — nothing in S2's code imports them any more.
- `MainActivity.kt`, `JarvisPreferences.kt`, `LocalLlmManager.kt`,
  `JarvisForegroundService.kt`, `AndroidManifest.xml`, version
  numbers — all untouched (outside S2 ownership or out of scope).
- **Gemma chat is untouched.** See the scope boundary in §8.

## 2. EXACT MainActivity UI removals (coordinator — file: `app/src/main/java/com/jarvis/autonomous/ui/MainActivity.kt`, line numbers from the 1.3.0 tree)

Remove all of the following. Do not just hide them — dead voice
controls that still write prefs will confuse users when the voice
they select can never play.

1. **State vars (lines ~881–884):**
   - `var geminiTtsOn by remember { mutableStateOf(prefs.geminiTtsEnabled) }` (line 881) — DELETE.
   - `var deepgramKey by remember { mutableStateOf(prefs.deepgramApiKey) }` (line 882) — DELETE.
   - `var deepgramOn by remember { mutableStateOf(prefs.deepgramTtsEnabled) }` (line 883) — DELETE.
   - `var kokoroOn by remember { mutableStateOf(prefs.kokoroEnabled) }` (line 884) — KEEP **only if** the Kokoro toggle itself is kept (see item 4). If the toggle goes, delete this too; `prefs.kokoroEnabled` defaults to `true`, so `VoiceManager` stays enabled.
2. **Kokoro download card (lines ~1008–1074):** the whole
   `// Kokoro offline voice …` block, including the status text
   (`"Status: Downloaded ✅ …"` line 1036 /
   `"Status: Not downloaded …"` line 1038), the progress text, and
   the `Download Kokoro Voice (~185 MB)` / `Use Kokoro Voice`
   button (line 1071) that calls the now-deprecated
   `KokoroTtsClient.download()`. Replace it with the first-run
   progress screen in §5 — do NOT keep a download button that can
   never be needed again (the model is in the APK).
3. **Deepgram API key field (lines ~1107–1117):** the
   `// Deepgram API Key …` `OutlinedTextField` (value `deepgramKey`,
   label `"Deepgram API Key (cloud voice ke liye)"`) — DELETE whole
   field.
4. **Voice toggles (lines ~1183–1198):**
   - `SettingToggleRow(title = "Gemini Voice — Sulafat …")`
     (lines 1183–1186) — DELETE.
   - `SettingToggleRow(title = "Deepgram Voice — Theia …")`
     (lines 1189–1192) — DELETE.
   - `SettingToggleRow(title = "Kokoro Voice — hf_alpha …")`
     (lines 1195–1198) — OPTIONAL: keep as a mute switch (OFF =
     silence, no fallback — `VoiceManager` logs this clearly), or
     delete it since Kokoro is now the only voice and always on.
     If kept, its subtitle must stop saying "(download hone ke
     baad…)" — the model is bundled, there is no download step.
5. **Voice speed slider (lines ~889, ~1231–1236):** `voiceSpeed`
   state (line 889) and the `"Voice Speech Speed: …"` slider —
   DELETE **or** rewire (see §3). It only ever tuned system TTS,
   which no longer exists; leaving it would imply a control that
   does nothing to Kokoro's voice.
6. **Save assignments (lines ~1254–1260):** delete
   `prefs.geminiTtsEnabled = geminiTtsOn`,
   `prefs.deepgramApiKey = deepgramKey`,
   `prefs.deepgramTtsEnabled = deepgramOn`,
   `prefs.voiceSpeed = voiceSpeed`, and (if the Kokoro toggle goes)
   `prefs.kokoroEnabled = kokoroOn`.
7. **Stale copy:** any remaining text saying a voice "falls back
   to system voice", "Gemini free quota khatam hone par system
   voice par wapas", or Kokoro needs a "~185 MB download" must be
   rewritten — in 1.4.0 there is no fallback and no download.

### Prefs fields that become dead (in `JarvisPreferences.kt` — NOT edited by S2)

- `deepgramApiKey`, `deepgramTtsEnabled`, `deepgramVoice` — dead.
- `geminiTtsEnabled`, `geminiTtsVoice` — dead **as voice prefs**.
  NOTE: `apiKey` itself is NOT dead — it is still the chat/AI
  provider key (Gemini chat), only its TTS use is gone. Do not
  remove the API-key field from settings.
- `voiceSpeed`, `voicePitch` — dead as voice controls (they only
  fed system TTS). Kokoro speaks at fixed speed 1.0f.
- `kokoroEnabled` — NOT dead: `VoiceManager` still reads it as
  the on/off gate. It only becomes dead if the coordinator also
  removes the toggle AND hard-codes the gate away later.
- Old values already saved on users' phones are harmless
  (unread keys in `SharedPreferences`); the coordinator may clean
  them in a later prefs pass, but must not silently delete user
  data in the same commit as the voice rewrite.

## 3. API / behaviour changes callers must know

- **`speakAndWait` signature change (the only one):**
  - 1.3.0: `suspend fun speakAndWait(text: String)` (returns `Unit`)
  - 1.4.0: `suspend fun speakAndWait(text: String): Boolean`
  - `true` = fully spoken; `false` = nothing (or only part)
    spoken, with the reason in the app log. It NEVER falls back.
- **No caller edit is needed.** The only caller,
  `JarvisForegroundService` line 57
  (`onSpeakAndAwait = { text -> voiceManager.speakAndWait(text) }`),
  targets `suspend (String) -> Unit`, and Kotlin coerces the
  `Boolean` result to `Unit` there. `stopSpeaking()` is unchanged
  in signature and now only stops Kokoro playback.
- **Voice-speed slider:** if the coordinator wants to keep a
  speed control, it must be rewired to Kokoro (e.g. pass
  `prefs.voiceSpeed` into `KokoroTtsClient.speak` as the
  `generate(..., speed)` parameter, currently hard-coded 1.0f).
  That is a coordinator decision, not done here, because it would
  change audible behaviour beyond the Kokoro-only brief.
- **Removed with reason:**
  - Deepgram chain — cloud voice needing the user's key and
    paid usage; English-focused, wrong accent for Hindi.
  - Gemini Sulafat chain — cloud voice needing the user's key
    with a tiny free quota; quota exhaustion used to silently
    change the voice.
  - System TTS chain (init, `setLanguage`, female-voice picking,
    pitch/speed) — a different voice from Kokoro; keeping it as a
    "never silent" fallback meant the user could never trust which
    voice would speak. Silence-with-a-log is now the honest
    failure mode, and Kokoro being bundled makes that failure
    rare (it needs a missing/corrupt APK or critically low disk).

## 4. ABI / bloat

- `abiFilters += "arm64-v8a"` (§1): both native stacks ship
  arm64-v8a (Sherpa-ONNX static AAR, LiteRT-LM Android), and the
  target phone is arm64, so this does not break either lib on
  device. It drops the x86_64 slice — **emulator (x86_64) builds
  will no longer run native code**; that is the accepted
  trade-off, not a phone regression. It saves only a few MB, but
  every MB counts against §7's growth.
- **CI GATE (coordinator must watch the first 1.4.0 run):** if
  the build fails with a missing-native-library error, revert the
  `abiFilters` block FIRST (restore both ABIs) before debugging
  anything else. Local compile could not prove this either way
  (§9) — CI is the proof.
- **No aapt `noCompress` for `onnx`/`bin`, deliberately:**
  Sherpa never reads APK assets directly. `KokoroTtsClient`
  copies assets via `AssetManager` streams (which decompress on
  the fly) into the files dir, and Sherpa loads those copies.
  `noCompress` would only inflate the APK further for zero
  benefit, so it was left out and the reason is commented in
  `app/build.gradle.kts`.

## 5. FIRST-RUN behaviour — bundled copy, visible progress, free-space check (NEW in this amendment)

### Honest behaviour (say exactly this to the user)

- **No network download, but NOT zero-time.** On first launch
  (or first speak, whichever comes first), ~190 MB of model files
  copy from APK assets to the app's files dir (`Kokoro/`).
  Duration depends on phone storage speed — expect tens of
  seconds on a budget phone, a few seconds on a fast one. The
  user must see progress during this window (below), never a
  blank hang, and must never be told the voice is "instant".
- **Once only.** After the copy, `isDownloaded()` is true and
  every later launch skips the copy entirely. Phones that already
  downloaded Kokoro under 1.3.0 also skip it (existing dir
  accepted as-is).
- **Resumable.** If the app is killed mid-copy, partial files are
  KEPT; relaunch skips files already present at full size and
  copies only the rest.
- **Failure is loud, not silent.** If the copy cannot complete,
  Jarvis logs the exact reason and `speakAndWait` returns
  `false` — no other voice speaks instead.

### The API the coordinator must drive (already implemented in `KokoroTtsClient`)

```kotlin
// Call ONCE at app launch, before the first speak, from a coroutine:
val result = KokoroTtsClient.ensureAssetsReady(context) { stage, copiedMB, totalMB ->
    // stage: "checking_space" | "copying" | "ready"
    // copiedMB/totalMB: progress in MB (totalMB = 0 during checking_space)
    // -> update the progress screen state (§5 UI spec below)
}
if (result.isFailure) {
    // Show result.exceptionOrNull()?.message VERBATIM — it is
    // already written as a user-facing message, e.g. the
    // free-space message below. Do not paraphrase it away.
}
```

Supporting helpers (all in `KokoroTtsClient`, all already implemented):

- `hasBundledAssets(context): Boolean` — true if this APK
  carries `assets/kokoro/model.int8.onnx` (false on a
  non-bundled / sideloaded build; then `ensureAssetsReady`
  fails with "reinstall the bundled 1.4.0 APK").
- `availableFreeBytes(context): Long` — usable bytes on the
  model-dir volume.
- `REQUIRED_FREE_BYTES_FRESH_COPY = 209,715,200` (200 MiB).
- `isDownloaded(context)` / `downloadedBytes(context)` —
  unchanged, still the "already ready?" check.

### Free-space rule (exact)

- **Fresh copy** (no model files present yet): requires
  **~200 MB free** (`REQUIRED_FREE_BYTES_FRESH_COPY`). Below
  that, the copy does NOT start, and the user sees verbatim:
  > "Kokoro setup needs ~200 MB free space — only XXX MB
  > available. Please free space and relaunch Jarvis."
  (XXX = actual free MB at check time.)
- **Resumed partial copy**: requires remaining-bytes + 10 MB,
  so a nearly-finished copy is not blocked by the full 200 MB
  rule.
- The check runs BEFORE any copying, so a low-space phone is
  never left with a half-copied, unusable model dir and no
  explanation.

### EXACT UI state the coordinator must show (MainActivity — not written by S2)

The coordinator must add a first-run overlay driven by the
`ensureAssetsReady` callback above. Minimum spec — all three
states are required, none may be a blank screen:

1. **Checking space** (`stage == "checking_space"`):
   full-screen overlay, title "Jarvis ki awaaz taiyaar ho rahi
   hai…", subtitle "Storage check ho raha hai…", indeterminate
   progress indicator. This state is normally sub-second; it
   still must render (no flash of the chat screen behind it).
2. **Copying** (`stage == "copying"`):
   same overlay, determinate progress: `"Copy ho raha hai:
   $copiedMB / $totalMB MB — pehli baar hi hoga, dobara nahi."`
   plus a percentage bar. The overlay must BLOCK interaction
   with the chat behind it (a tap during copy must not queue a
   speak that then silently fails).
3. **Low space / failure** (`result.isFailure`):
   same overlay, error text = the exception message verbatim,
   plus a "Dobara try karo" button that re-runs
   `ensureAssetsReady`, and a "Baad me" dismissal that leaves
   Jarvis usable WITHOUT voice (chat text still works; every
   speak attempt will log the same failure, never fall back).
4. **Ready** (`stage == "ready"`): overlay dismisses, normal UI
   appears. If `isDownloaded()` was already true at launch, the
   overlay must never appear at all (no flicker).

**Fallback if the coordinator does NOT add the launch screen:**
`speakAndWait` still triggers the copy via `KokoroTtsClient.speak`
and logs progress every ~25 MB to the app log — but that is the
safety net only. Shipping 1.4.0 with speak-triggered copying and
no launch screen would reproduce the blank-hang this amendment
exists to prevent, so the launch screen is a release gate, not a
nice-to-have.

### UI CONSISTENCY (§10) — the overlay MUST follow the existing creamy theme

No default grey / Material-default screen is acceptable for the
first-run overlay. Use exactly the theme already defined in
`MainActivity` (lines ~122–129):

- Background: `Color(0xFFFFF8ED)` (creamy whitish)
- Primary / progress / button: `Color(0xFFB85C38)` (terracotta)
- Text: `Color(0xFF3B2F2A)` (dark brown)
- Card (if used): `Color(0xFFFFF1DC)` (the existing card tint,
  e.g. the Kokoro card at line ~1019)

Paste-ready shape for the coordinator (colours only — layout is
the coordinator's, but the colours are not negotiable):

```kotlin
// First-run Kokoro overlay — creamy theme, no default greys.
Box(
    modifier = Modifier.fillMaxSize()
        .background(Color(0xFFFFF8ED)),
    contentAlignment = Alignment.Center
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Jarvis ki awaaz taiyaar ho rahi hai…",
            color = Color(0xFF3B2F2A), fontWeight = FontWeight.Bold)
        Text("Copy ho raha hai: $copiedMB / $totalMB MB — pehli baar hi hoga, dobara nahi.",
            color = Color(0xFF3B2F2A))
        LinearProgressIndicator(
            progress = { if (totalMB > 0) copiedMB / totalMB.toFloat() else 0f },
            color = Color(0xFFB85C38),
            trackColor = Color(0xFFFFF1DC)
        )
        // Low-space text, if any: same Color(0xFF3B2F2A) on 0xFFFFF8ED,
        // retry button in Color(0xFFB85C38) with white text.
    }
}
```

## 6. `download()` — kept, deprecated, unused

`KokoroTtsClient.download()` (HF tree-API network download) is
kept ONLY so `MainActivity`'s existing download button still
compiles until §2 item 2 removes it. It is annotated
`@Deprecated(WARNING)` with a message pointing at
`ensureAssetsReady`, and `VoiceManager` never calls it. The
coordinator should delete the function together with the button
in the same UI commit — leaving a deprecated network-download
function in a "bundled, no download" build longer than one
release would be misleading. Its `OkHttpClient` stays only for
that deprecated path.

## 7. CI bundling — URL / size evidence (verified 2026-11-02, NOT assumed)

- **URL used in the workflow:**
  `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2`
- **Evidence:** `HEAD` follows to HTTP 200 (via the release-asset
  redirect); the GitHub release API
  (`/repos/k2-fsa/sherpa-onnx/releases/tags/tts-models`) lists
  this asset at **132,353,410 bytes**, and a full
  `curl -L -o /dev/null` download reported the same
  `size=132353410`, `http=200`. The workflow enforces
  tarball ≥ 100,000,000 bytes (not an exact pin, so a
  re-uploaded asset does not brick CI on a byte) AND, after
  extraction, `model.int8.onnx` > 50,000,000 bytes, plus
  presence of `voices.bin`, `tokens.txt`, and `espeak-ng-data/`.
  The extraction handles both layouts (tarball top dir or flat
  files) by locating `model.int8.onnx` with `find` and copying
  its directory.
- **No 190 MB commit:** the step runs after checkout and the
  files exist only in the runner; the workflow never commits
  them. The coordinator must ALSO add `app/src/main/assets/kokoro/`
  to `.gitignore` (not done here — `.gitignore` is outside S2
  ownership, and it currently only ignores `app/libs/`), or a
  developer who runs the bundling step locally could
  accidentally commit the model on a later push.
- **HF fallback (only if the sherpa URL 404s in future):**
  unpacked files from Hugging Face repo
  `csukuangfj/kokoro-int8-multi-lang-v1_0` via the HF tree API,
  exactly as 1.3.0 downloaded on-device. Do NOT silently switch
  to a different model version (e.g. `v1_1`) — that changes the
  voice (`hf_alpha` sid mapping) and needs its own verification.
- **APK gate:** the Verify step now fails the build if the
  release APK lacks `assets/kokoro/model.int8.onnx` or
  `assets/kokoro/voices.bin` (`unzip -l` check).

## 8. SCOPE BOUNDARY — "APK me hi rahe" means KOKORO bundled only (clarification amendment)

- **Bundled in 1.4.0: Kokoro only.** Gemma is NOT bundled by
  this worker and must not be added to `assets/` under this
  scope.
- **Gemma stays exactly as it is:** a one-time ~304 MB in-app
  download, gated by the owner's embedded `BuildConfig.HF_TOKEN`
  (read-only Gemma-download token). `LocalLlmManager.kt` and the
  `HF_TOKEN` build field are untouched by S2.
- **Why:** bundling Gemma too would push the APK from ~340 MB
  (§7 growth) toward **~650 MB** (340 + ~304 MB Gemma), which is
  a different product decision (install friction, Play/store
  limits, update sizes). If the user later orders Gemma bundled,
  that is a SEPARATE change with its own size/licence review —
  do not bundle it silently as part of the Kokoro work.
- **Resulting on-phone footprint (honest):** APK ~340 MB +
  first-run Kokoro copy ~190 MB in app files + optional Gemma
  download ~304 MB if the user enables Local chat. Total worst
  case is therefore ~830 MB on disk, and the user should be told
  that before enabling Local — the APK size alone understates it.

## 9. APK size estimate + verification status

- **Estimate:** 1.3.0-PRO was **158 MB**. Adding ~190 MB
  unpacked Kokoro assets (deflate on `onnx`/`bin` may shave a
  little, since assets are compressed in the APK unless
  `noCompress` — deliberately not used, §4) and subtracting a
  few MB of dropped x86_64 native libs gives **~320–350 MB,
  central ~340 MB** — matching the brief's ~340 MB figure. The
  exact number is only known from the first CI-built APK; do
  not quote 340 MB as measured until that artifact exists.
- **Permission audit (amendment result): NO new manifest
  permission is needed for S2's part.** The copy reads APK
  assets (`AssetManager`, no permission) and writes the app's
  own files/external-files dir (`getExternalFilesDir`, no
  runtime storage permission on modern Android). `INTERNET` is
  already in the manifest (line 6) but the bundled path does
  not use the network at all; the deprecated `download()` is
  the only code path that would, and it is being removed with
  its button. Manifest untouched.
- **Qwen leftovers (grep):** none in S2's files. The only Qwen
  mention found repo-wide is a comment-only line in
  `agent/LocalLlmManager.kt` line ~38 ("Qwen has been fully
  removed…") — outside S2 ownership, left alone; the
  coordinator may delete that comment, but it is documentation
  of a removal, not a leftover reference to remove code for.
- **Local compile: NOT proven — environment failure, not a code
  verdict.** Attempted with JDK 17
  (`~/workspace/.jdk17-full/jdk-17.0.20.1+1`), SDK
  `~/workspace/build-tools/android-sdk` (platform 34,
  build-tools 34.0.0), and the CI-identical Sherpa AAR
  (37,631,864 bytes, size-checked OK) in `app/libs/` (gitignored,
  removed again afterwards). Gradle 8.13 failed before any
  compilation both times with "The first result from the daemon
  was empty. The daemon process may have died" (`--no-daemon`
  also failed the same way; no daemon log was written; VM free
  memory was only ~354 MB at the time). No Kotlin error was
  produced, so **no compile success is claimed**. Static
  pre-push checks per `kotlin_prepush_checks` DID pass:
  brace/paren/bracket balance 0 in both edited Kotlin files;
  no code references to `TextToSpeech`/`Deepgram`/`GeminiTts`
  remain (only doc/log strings); every variable used in
  callbacks is declared before its use (constructor params /
  top-of-object constants; no composable capture issues — S2
  touched no composables); nullable asset values are guarded
  (`?:` / `try` fallbacks, never a bare null check relied on for
  a non-null API arg). **CI is the compile proof for 1.4.0 —
  do not merge on this note alone.**

## 10. Coordinator checklist (in order)

1. Watch the first CI run: bundling step sizes, `abiFilters`
   gate (§4), APK asset gate (§7), and record the real APK size
   (replace the §9 estimate with the measured number).
2. Add `app/src/main/assets/kokoro/` to `.gitignore`.
3. Make the §2 `MainActivity` removals + the §5 launch-time
   `ensureAssetsReady` progress screen (creamy theme, §5) —
   this is a release gate for 1.4.0, not polish.
4. Delete `DeepgramTtsClient.kt` / `GeminiTtsClient.kt` and the
   deprecated `KokoroTtsClient.download()` in the same UI
   commit (once the button is gone, nothing references them).
5. Phone-test on the user's arm64 phone: fresh install →
   progress screen shows → copy completes → Jarvis speaks
   Kokoro on first command; low-space phone shows the §5
   message; 1.3.0 upgrade keeps its downloaded model with no
   re-copy. Only then call 1.4.0 voice "done".
