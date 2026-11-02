# INTEGRATION_NOTES_Builder — JarvisAgentApp Code Builder (S7)

Date: 2026-11-02 (audit + fix). Amended same night for the user's
preview decision (23:09 IST): preview renders IN THE BROWSER, on the
Browser tab's shared WebView engine — see §3. Earlier brief fully
kept; nothing below drops it.

Scope guard: S7 edited ONLY `data/ProjectStore.kt`,
`data/ProjectPreviewServer.kt`, `ui/ProjectsScreen.kt`,
`agent/LlmClient.kt`, and this file. `agent/JarvisAgentEngine.kt`
was NOT edited — its required edits are specified exactly in §5–§6
for the coordinator. No version number was touched. Nothing was
pushed. No credential, key, or token appears anywhere in this work.

Working-copy note: the brief's paths (`~/workspace/jarvis-agent/data/…`)
do not exist. The real paths are
`~/workspace/jarvis-agent/app/src/main/java/com/jarvis/autonomous/…`
(`data/`, `ui/`, `agent/` under that package dir). All paths below
use the real layout.

---

## 1. AUDIT FIRST — spec vs code (findings, before fixing)

### 1. ProjectStore — where files are written vs where the editor reads

**Partial divergence found — but NOT a second read path.**
`readFile()` already read only the external-files working copy, and
the MediaStore Documents export was already write-only (never read
back). So the classic "editor shows the Documents copy" bug was NOT
present. The real divergences were subtler, and each one produced
exactly the user's complaint ("cannot see ANY code" / "does not
build properly"):

1. **Blank-collapse (root cause of the invisible-code complaint).**
   `readFile()` returned `""` for a missing file, an empty file, AND
   a read failure alike. The editor displayed that `""` with no
   error state, so a missing/failed file looked identical to an
   empty editor. The user could not tell "no code" from "code failed
   to load".
2. **Silent drop on re-build.** `createProject()` wrote files ONLY
   when the project dir was empty. For an existing project it wrote
   NOTHING — a re-build, or a `CREATE_PROJECT` for an existing name,
   silently discarded the new index.html and the user kept seeing
   the old site.
3. **Two different name sanitisers.** `ProjectStore` sanitised
   (`trim('-')`, 60-char cap, blank fallback) but `ProjectsScreen`
   had its own inline regex (no dash-trim, no cap, no fallback).
   Example: typing `meri portfolio ` selected `meri-portfolio-`
   while the listed project was `meri-portfolio`, so the editor's
   file lookup (`firstOrNull { it.name == current }`) missed and
   fell back to showing ONLY the `index.html` chip — style.css and
   script.js were invisible even though they existed.
4. **Top-level-only listing.** `listProjects()` listed only
   top-level files, so any nested page (`pages/about.html`) Jarvis
   wrote was invisible in the editor chips, even though the server
   could serve it.
5. **Blank overwrite.** `writeFile()` happily overwrote a good file
   with an empty string (e.g. a blank LLM `fileContent`), producing
   a genuinely empty file the editor then showed as blank.
6. **Delete left the Documents copy behind.** The user could delete
   a project and still see it in the Files app.
7. **No last-touched record.** Nothing recorded which project Jarvis
   touched most recently, so a bare voice edit ("is button ka rang
   badal do", no project named) had no deterministic target.

**Fix (in `ProjectStore.kt`):** external-files dir is now the
declared single source of truth, enforced in code — one read path
(`projectFile()` + `readFileStatus()`), used by the editor AND the
preview server (the server resolves through `projectFile()` plus a
canonical-containment check). `readFileStatus()` returns
`Ok(content) | Missing | Empty` so the UI can never show a silent
blank. `sanitizeProjectName()` / `sanitizeFileName()` are public
and are the ONLY canonical forms (editor and LLM parsing both call
them). `createProject()`: a supplied index.html ALWAYS overwrites
index.html; default style.css/script.js are written ONLY when
missing, so a re-build never clobbers custom CSS/JS and never drops
new HTML. `writeFile()` returns Boolean, refuses blank writes to
the three template files, rejects unsafe file names (absolute
paths, `..` segments), and records the last-touched project
(`lastTouchedProject()`, stored app-privately in the root, never
inside a project dir, so it never appears as a file chip).
`deleteProject()` best-effort deletes the Documents copy too.
`listProjects()` now lists nested files as relative paths.
New helpers for the engine: `validateProject()` and
`referencedAssets()` (see §6), `defaultFileContent()` (template
content for editor-added files).

### 2. ProjectPreviewServer — REPLACED, not patched around

Old server bugs found (each verified by reading the code):

1. **Start race / swallowed failure (the #1 blank-preview path).**
   `start()` threw on bind failure; the UI called
   `ensureRunning()` inside `runCatching` (swallowing the throw)
   and set the preview URL anyway. The WebView then loaded a dead
   server and showed a blank/connection-error page with no message
   from the app.
2. **Wrong MIME for JS + charset on binary.** `.js` served as
   `text/javascript` (browsers tolerate it; the brief requires
   `application/javascript`) and EVERY response — including
   `image/png` and `image/jpeg` — carried `; charset=UTF-8`,
   which is invalid for binary types. `.svg`, `.webp`, `.gif`,
   `.ico`, fonts and video had no mapping at all (fell through to
   `text/plain`), so those assets could not render correctly.
3. **Broken relative links on a slash-less URL.** Loading
   `http://127.0.0.1:8080/<project>` (no trailing slash) served
   index.html, but the browser then resolved `style.css` at the
   server root (`/style.css` → 404) — an unstyled page that looks
   like a broken preview.
4. **Bare 404.** A missing file returned plain-text
   `File not found: <name>` with no project context and no list of
   files that DO exist — useless to the user, and blank-ish.
5. **Traversal check was substring-only** (`contains("..")`).
   Adequate for the common case, but not a containment guarantee.
6. **Phantom projects.** `route()` called `projectDir()`, which
   `mkdirs()` — requesting a non-existent project CREATED an empty
   dir for it before 404ing.

**Fix (server rewritten in place, same file, same no-dependency
design):** correct MIME map (`.html` text/html, `.css` text/css,
`.js`/`.mjs` application/javascript, `.json` application/json,
`.png/.jpg/.jpeg/.gif/.webp/.svg/.ico` image/*, `.mp4` video/mp4,
`.woff/.woff2/.ttf` font/*, `.txt` text/plain, else
application/octet-stream); charset ONLY on text types; 301
redirect from `/<project>` to `/<project>/`; directory requests
serve their `index.html`; canonical-path containment for
traversal; missing files return a readable creamy HTML error page
listing the project's actual files with links (never blank, never
bare text). `start()` returns Boolean instead of throwing.
**No-race API:** `startAndAwaitReady(timeoutMs)` suspends until a
real TCP connect to 127.0.0.1:8080 succeeds, and
`ensureRunningReady(context)` returns `(server, ready)` — the UI
awaits it and loads a URL ONLY when `ready == true`. `isRunning`
alias added (`isAlive` kept for compatibility). The old
`ensureRunning()` is kept as a best-effort caller but now never
throws; callers must check `isRunning`.

### 3. ProjectsScreen — editor + preview (incl. 23:09 preview decision)

Old screen bugs found:

1. **Own half-broken renderer.** It built a fresh `WebView` with
   only `javaScriptEnabled` + `domStorageEnabled`, a default
   `WebViewClient` (no `onReceivedError`), and loaded the URL
   unconditionally — including when the server had failed to start.
   Any failure surfaced as a silent blank box.
2. **Preview reset on every keystroke.** The `AndroidView` update
   block called `loadUrl(url)` on EVERY recomposition, so typing
   in the editor reloaded (and reset scroll/JS state of) the
   preview continuously.
3. **Preview cleared on Open / chip switch** (`previewUrl = null`),
   forcing the user to re-press Preview to see anything.
4. **No loading, error, or empty states anywhere** — no
   server-starting indicator, no WebView error, no missing/empty
   file state (see §1.1).
5. **Save did not refresh the preview** (no cache-bust, no reload).

**Fix — and the 23:09 preview decision (this supersedes the old
inline renderer entirely):**

* **One shared engine.** The Projects preview now borrows the SAME
  WebView instance as the Browser tab:
  `JarvisBackgroundBrowser.obtainWebView(context)` (defined in
  `ui/BrowserScreen.kt`, S3's shared engine). The factory detaches
  it from any previous parent (`(shared.parent as? ViewGroup)
  ?.removeView(shared)`); `onRelease` deliberately does NOT destroy
  it. ProjectsScreen creates NO WebView of its own any more.
  Shared-engine settings (from `obtainWebView`, read 2026-11-02 —
  do not duplicate them in ProjectsScreen): JavaScript ON,
  DOM storage ON, `javaScriptCanOpenWindowsAutomatically = false`,
  multiple windows off, file/content access OFF, file-from-file
  and universal-file access OFF, geolocation OFF, mixed content
  NEVER_ALLOW, Safe Browsing ON, NO `addJavascriptInterface`,
  downloads disabled (toast only), file chooser cancelled, JS
  dialogs (alert/confirm/prompt) suppressed. `SharedWebViewClient`
  keeps http/https in-app and blocks every other scheme.
* **What this means for preview content (read before testing):**
  full HTML + CSS + JS execution works, because it is the real
  browser engine; images and multi-page projects work because the
  server (§2) serves them with correct MIME types over the same
  localhost origin. Two honest engine limits carry over: (a) an
  `http://` preview page loading an `https://` asset (or vice
  versa) is mixed content and is BLOCKED — preview pages must use
  relative or same-origin loopback assets, or https assets served
  over https; (b) page JS `alert()/confirm()/prompt()` is silently
  cancelled by the shared Chrome client — preview JS must not
  depend on dialogs. (c) Single-page trade-off (S3, §11): there is
  ONE shared WebView, so previewing in Projects navigates away
  from whatever the Browser tab was showing, and vice versa. That
  is the price of "no reload, same page" — see the note under the
  preview in the UI.
* **No callback fight.** ProjectsScreen deliberately does NOT
  replace the shared WebView's client/chrome client and does NOT
  set `ForegroundCallbacks` (those belong to the Browser tab when
  visible — see wiring §4). Inline-preview feedback therefore
  comes from the server side, which is now reliable: ready-check
  before load + validation before load + the server's readable
  error page for any missing file. The Browser tab itself provides
  progress/title/error UI when the user opens the preview there.
* **No keystroke reloads.** The `AndroidView` update block loads
  ONLY when the target URL string changes. Save bumps a `?v=<nonce>`
  cache-buster, so Save refreshes the preview immediately and
  typing never does.
* **Explicit states everywhere.** Server-starting indicator,
  preview error banner + Retry, file Missing/Empty error banner +
  "Template se Restore karo" (for template files), empty-project
  state + "Template files dobara banao", save-failure status (no
  more fake "Saved"). File chips list ALL project files (nested
  included). "Add file" row creates a file with template content
  for the three template names, and selects it in the editor.
* **"Open in Browser" (requirement 3).** Every project card, the
  editor row, AND the preview section have a "Browser me kholo"
  button. It awaits server readiness, then calls
  `browserHost.loadUrl(localhostUrl)` — the Browser tab borrows
  the same already-loaded instance WITHOUT reloading (its update
  block only loads when `webView.url` differs). If `browserHost`
  is null (coordinator wiring not done yet), it falls back to the
  inline shared-engine preview and says so in the status line —
  never a blank screen, never a dead button.

### 4. LlmClient — why Jarvis built incompletely

Bugs found (verified by reading prompt + parsing + engine loop):

1. **One-step completion killed multi-file builds (root cause).**
   The old rule 5 said "the app already provides default
   style.css/script.js if you only write index.html" and never told
   the model to set `isCompleted=false` on the CREATE step. The
   engine loop returns as soon as `isCompleted` is true (and the
   parser defaults `isCompleted` to true when the model omits it),
   so the loop exited after step 1 and the WRITE steps for custom
   CSS/JS never ran. Any site whose HTML used custom classes/ids
   therefore rendered with generic defaults or broken JS — "Jarvis
   does not build properly".
2. **No modify rule at all.** Nothing told the model to use
   `WRITE_PROJECT_FILE` on an EXISTING project for edits, so voice
   edits ("is button ka rang badal do") had no defined action.
3. **Unnormalised names.** `parseDecisionJson` copied
   `projectName`/`fileName` verbatim. "Meri Portfolio" vs
   "meri-portfolio" are different dirs on a case-sensitive FS, and
   "style.css " / "/style.css" do not match `href="style.css"` —
   so step 2/3 writes could land in a second project folder while
   the preview of the first showed defaults.
4. **Fragile JSON.** Any prose wrapped around the JSON object made
   `JSONObject(cleaned)` throw, failing the whole step as an API
   error with no build.
5. **LOCAL-provider bypass.** With provider LOCAL, `decideNextStep`
   returned local-chat `action=NONE` for everything, so a voice
   website build got a friendly chat reply and NO project at all.
6. **Hinglish triggers were implicit.** The old examples were
   Roman-Hindi-ish but no explicit trigger list; "ek website banao"
   was not guaranteed to match the builder rule.

**Fix (in `LlmClient.kt`):** rule 5 rewritten as an explicit
3-step contract (CREATE index.html `isCompleted=false` →
WRITE style.css `isCompleted=false` → WRITE script.js
`isCompleted=true` + spoken build summary), same projectName
spelling/case on every step, index.html must reference EXACTLY
`style.css`/`script.js` and no other local file unless also
written; single-file mode ONLY on an explicit user ask ("sirf ek
file") with inline `<style>`/`<script>` and NO external references.
New rule 6 (voice modify): never CREATE for an edit — WRITE the
existing project in place, only the file(s) that change, full
corrected content; a new page = write the page + rewrite index.html
with a link (two steps); ambiguous project → `askUser=true`,
`isCompleted=false`, ask which project. `parseDecisionJson` now
normalises names through `ProjectStore.sanitizeProjectName` /
`sanitizeFileName` (unsafe fileName → `index.html` fallback) and
recovers JSON from prose (first `{` … last `}`). LOCAL provider:
a builder/modify request now returns an honest guidance reply
(needs Gemini/Groq, or manual Create in Projects) instead of a
chat reply that builds nothing — the small local model has no
structured-action path, so a local build is NOT faked. (If the
coordinator later wants local builds, that is a separate design
decision, not a silent fallback.)

**No bug found (for the record):** Gemini `responseMimeType`
application/json and OpenAI `response_format json_object` were
already set; the 404/503 model-fallback chain is intact; the
`fileContent` JSON escaping itself was sound — the failures were
the prompt/loop/parsing issues above, not the transport.

---

## 2. FILES CHANGED (S7 only, no push)

- `app/src/main/java/com/jarvis/autonomous/data/ProjectStore.kt`
  — single source of truth, canonical names, read statuses,
  create/write semantics, last-touched, validation, delete export.
- `app/src/main/java/com/jarvis/autonomous/data/ProjectPreviewServer.kt`
  — replaced server: MIME map, redirect, canonical containment,
  readable error page, no-race ready API.
- `app/src/main/java/com/jarvis/autonomous/ui/ProjectsScreen.kt`
  — shared-engine preview, Open in Browser, explicit states,
  all-file chips, add-file, save-refresh.
- `app/src/main/java/com/jarvis/autonomous/agent/LlmClient.kt`
  — builder rules 5–6, name normalisation, robust JSON, LOCAL guard.
- `INTEGRATION_NOTES_Builder.md` (this file, repo root).

**Unified source of truth:** the app external-files dir
`.../JarvisProjects/<project>/` — the ONLY read path (editor,
preview server, validation). `Documents/JarvisProjects/<project>/`
(MediaStore) is a write-only user-visible copy, never read back.

---

## 3. SHARED-ENGINE CONTRACT (preview decision — what S7 calls)

S7's preview calls exactly this, and nothing else:

```kotlin
// ProjectsScreen.kt — inline preview factory (same as BrowserScreen.kt)
val shared: WebView = JarvisBackgroundBrowser.obtainWebView(ctx)
(shared.parent as? ViewGroup)?.removeView(shared)
// update: load ONLY when webView.url != targetUrl
// onRelease: NOTHING (never destroy the shared instance)
```

Settings, clients, and lifecycle all come from
`JarvisBackgroundBrowser` in `ui/BrowserScreen.kt` (S3). S7 does
not re-implement, wrap, or replace any of it. The localhost server
(§2) is the only S7-owned preview component; rendering is 100% S3's
engine. Consequence the coordinator must accept: preview and
Browser tab share ONE page (single-instance trade-off, §3 above).

---

## 4. COORDINATOR WIRING (exact — MainActivity, NOT edited by S7)

Verified 2026-11-02: `MainActivity.kt` currently has tabs 0–4 only
(0 Command, 1 Permissions, 2 AI Brain, 3 Memory & Logs,
4 Projects) and does NOT reference `BrowserScreen` at all — the
Browser tab itself is still unwired. Until this wiring lands, S7's
"Browser me kholo" falls back to the inline shared-engine preview
(by design, stated in the status line). To complete requirement 3:

```kotlin
// In JarvisMainApp, AFTER `var selectedTab by remember { ... }`:
// (declaration order: selectedTab must be declared before this
//  lambda captures it — kotlin_prepush_checks.)
val browserHost = remember { BrowserHostState() }
browserHost.onShowBrowserTab = { selectedTab = 5 }  // Browser tab index
// NavigationBar: add item index 5, label "Browser"
// (icon: coordinator's choice — existing imports cover Home,
//  Security, Settings, List, Build; add the chosen icon import).
// Content switch:
//   4 -> ProjectsScreen(browserHost)   // was ProjectsScreen()
//   5 -> BrowserScreen(browserHost)
// No other file needs a signature change: ProjectsScreen's
// browserHost parameter defaults to null, so the old call still
// compiles until this wiring lands.
```

Notes for the wiring owner:

- `BrowserHostState.loadUrl()` already normalises
  `http://127.0.0.1:8080/<project>/` correctly (loopback keeps
  `http://` — see `normalizeBrowserUrl` in `BrowserScreen.kt`).
- The Browser tab WILL show S3's literal non-HTTPS warning banner
  for the `http://` localhost preview ("Ye page secure nahi hai…").
  That banner is S3's rule applied literally to every `http://`
  URL. If the coordinator wants loopback exempted, that is an S3
  (`BrowserScreen.kt`) change — NOT an S7 file — and must be
  decided explicitly; S7 did not touch it. Default: accept the
  banner on preview loads (harmless, and true that it is http).
- `ForegroundCallbacks` ownership: BrowserScreen sets them in its
  `DisposableEffect` when visible and clears them on dispose.
  ProjectsScreen never sets them. Do not "fix" this by giving
  Projects its own callbacks — the two tabs would fight over the
  single shared client. Inline-preview feedback stays server-side
  (§3).
- Voice "browser me dikhao" (user asks Jarvis to SHOW a built site
  in the Browser tab) is NOT wired by S7: the engine has no access
  to `browserHost`. If the coordinator wants it, the engine action
  spec (S3/engine owner) must add a `SHOW_PREVIEW_IN_BROWSER`
  path that calls `browserHost.loadUrl(...)` from MainActivity
  scope — do not reach the holder from the engine via a singleton
  hack. Until then, the build summary (§6) tells the user to tap
  "Browser me kholo" in Projects, which is one tap.

---

## 5. ENGINE EDITS REQUIRED (JarvisAgentEngine.kt — coordinator only)

S7 did not edit this file. Apply exactly the following in
`executeDecisionAction` / `runAutonomousLoop`. All helpers named
here already exist in `ProjectStore` (no new store API needed).

### 5a. Resolve the target project BEFORE executing a project action

Insert in `runAutonomousLoop`, immediately before the
`if (decision.action != "NONE")` execution block (so the resolved
name is what gets executed AND logged):

```kotlin
// Voice-modify resolution: a WRITE with no project name targets
// the most-recently-touched project; with no such project, ask.
var effectiveDecision = decision
if (decision.action == "WRITE_PROJECT_FILE" && decision.projectName.isBlank()) {
    val last = ProjectStore.lastTouchedProject(context)
    if (last != null) {
        effectiveDecision = decision.copy(projectName = last)
        JarvisStateRepository.addLog("Voice edit: no project named, using last-touched '$last'")
    } else {
        // No deterministic target — ask, do not guess or rebuild.
        JarvisStateRepository.addChatMessage(
            "Jarvis",
            "Kaunsi website badalun? Project ka naam batao — ya Projects tab me project kholo."
        )
        // Skip execution this step; treat like an askUser round:
        // set state WAITING_USER_REPLY and continue the loop via the
        // existing askUser path (coordinator: wire this branch into
        // the askUser handling above it, not as a silent return).
        // DO NOT fall back to CREATE_PROJECT here.
        // (Implementation detail for the loop owner: reuse the
        //  existing onRequestUserVoiceReply() round and append the
        //  answer to currentGoal, as the askUser branch already does.)
    }
}
// Execute effectiveDecision (not decision) below, and use
// effectiveDecision in the isCompleted check below it.
```

Rules this enforces (prompt rule 6 is the LLM side; this is the
deterministic side):

- Named project in the decision → use it verbatim (already
  store-sanitised by `LlmClient` parsing).
- Unnamed WRITE → most-recently-touched project
  (`lastTouchedProject()` — updated on every create/write).
- Unnamed WRITE + no last-touched project → ASK which project.
  Never invent a name, never `CREATE_PROJECT` as a modify.
- Unnamed CREATE → existing engine default (`jarvis-site`) is
  unchanged; the store's new create semantics (§1) make a repeat
  CREATE safe (index overwritten, custom CSS/JS preserved).

### 5b. Validate inside the CREATE / WRITE branches (do not trust the LLM)

Replace the two project branches in `executeDecisionAction` with:

```kotlin
"CREATE_PROJECT" -> {
    val name = d.projectName.ifBlank { "jarvis-site" }
    ProjectStore.createProject(context, name, d.fileContent.ifBlank { null })
    val v = ProjectStore.validateProject(context, name)
    val files = ProjectStore.listProjects(context)
        .firstOrNull { it.name == ProjectStore.sanitizeProjectName(name) }
        ?.files ?: emptyList()
    "CREATE_PROJECT('$name') -> files=[${files.joinToString(", ")}], " +
        "validation=${if (v.ok) "OK" else "FAILED missing=${v.missingFiles} empty=${v.emptyFiles}"}, " +
        "preview at ${ProjectStore.localhostUrl(name)}"
}

"WRITE_PROJECT_FILE" -> {
    val name = d.projectName.ifBlank { "jarvis-site" }
    val file = d.fileName.ifBlank { "index.html" }
    val written = ProjectStore.writeFile(context, name, file, d.fileContent)
    val v = ProjectStore.validateProject(context, name)
    "WRITE_PROJECT_FILE('$name/$file', written=$written, ${d.fileContent.length} chars) -> " +
        "validation=${if (v.ok) "OK" else "FAILED missing=${v.missingFiles} empty=${v.emptyFiles}"}, " +
        "preview at ${ProjectStore.localhostUrl(name)}"
}
```

Notes:

- `writeFile()` now returns Boolean: `written=false` means the
  write was refused (blank template content or unsafe name) — the
  summary MUST say so, never "saved".
- Validation is post-write and reads the source of truth (§2), so
  it catches exactly the failures users reported (missing
  style.css/script.js, empty index.html, dangling references).
- Do NOT add a second validation implementation in the engine —
  `ProjectStore.validateProject()` is the one implementation.

### 5c. What the loop does with a failed validation

In `runAutonomousLoop`, after `executeDecisionAction` returns, if
the action was CREATE_PROJECT or WRITE_PROJECT_FILE AND the
summary contains `FAILED` or `written=false`:

- Do NOT return on `isCompleted`, and do NOT speak a success
  summary. Add a chat message + speak: which files are
  missing/empty and what Jarvis will do next (one repair step:
  re-emit the missing file via WRITE_PROJECT_FILE on the next
  loop step — set `currentGoal` to the original goal plus
  "(Repair: <missing/empty files> dobara likhna hai)" and
  continue the loop). Only when validation is OK does §6 run.

---

## 6. BUILD VALIDATION + SUMMARY (engine, after every Jarvis build)

After the LAST project step of a build (i.e. when the action was
CREATE_PROJECT or WRITE_PROJECT_FILE, validation is OK, and the
decision marks the build complete), the loop MUST — before
returning to IDLE — do all three, in this order:

1. **Validate** (already done in §5b — reuse that result, do not
   re-validate from a different path): every referenced file
   exists and is non-empty; index.html references its css/js
   correctly (that IS what `validateProject()` checks).
2. **Show the preview.** The engine cannot open a tab itself (see
   §4). So the summary message (step 3) MUST name the exact tap:
   "Projects tab me Preview dabao, ya Browser me kholo" — and the
   user can then open the SAME localhost preview full-screen in
   the Browser tab with one tap (§3). The preview URL in the
   message is `ProjectStore.localhostUrl(name)`
   (`http://127.0.0.1:8080/<project>/`).
3. **Speak + post the build summary.** Insert before the
   `if (decision.isCompleted || decision.action == "NONE") return`
   block, for project actions only (coordinator: guard on
   `effectiveDecision.action` in {CREATE_PROJECT,
   WRITE_PROJECT_FILE} and validation OK):

```kotlin
// Build summary — spoken AND in chat, every build, no exceptions.
val projectName = ProjectStore.sanitizeProjectName(effectiveDecision.projectName.ifBlank { "jarvis-site" })
val builtFiles = ProjectStore.listProjects(context)
    .firstOrNull { it.name == projectName }?.files ?: emptyList()
val summary = "Ho gaya bhai! Project '$projectName' ban gaya — " +
    "files: ${builtFiles.joinToString(", ")}. " +
    "Preview: ${ProjectStore.localhostUrl(projectName)} — " +
    "Projects tab me Preview dabao, ya Browser me kholo (full-screen, wahi preview)."
JarvisStateRepository.addChatMessage("Jarvis", summary)
onSpeakAndAwait(summary)
```

Every Jarvis build therefore ends with: validated files +
preview link in chat + summary spoken + preview one tap away
(Projects inline, or Browser tab full-screen). A build that fails
validation never gets a success summary (§5c).

---

## 7. VERIFY (kotlin_prepush_checks applied)

- Declaration order: in `ProjectsScreen`, ALL state is declared
  before every helper/callback that captures it; the new
  `browserHost` parameter is a composable parameter (not a
  different composable's state). In `LlmClient`, the new
  `isCodeBuilderRequest` is a member function (no callback-capture
  ordering issue); the LOCAL guard calls it before any local chat.
- Scope/nullability: `readFileStatus` results are handled with
  `when` (no nullable copy passed to a non-null API); the
  `return@Button` / `return@OutlinedButton` labels match the
  established pattern already used in `LicenceScreen.kt`.
- Balance: braces/parens balance to 0 in all four edited files
  (checked by script); `grep` confirms no old preview-WebView code
  remains in `ProjectsScreen` (no own `WebViewClient`, no
  unconditional `loadUrl`).
- Cross-file compatibility: engine call sites still compile
  against the kept signatures (`createProject` returns `File`,
  `localhostUrl` unchanged; `writeFile`'s new Boolean return may
  be ignored by the old engine code — the coordinator's §5b
  replacement uses it). `ProjectsScreen()` with no args still
  compiles (default `browserHost = null`).
- **Local compile: NOT claimed.** Attempted
  `:app:compileDebugKotlin --offline` with JDK 17
  (`~/workspace/.jdk17-full/jdk-17.0.20.1+1`) and
  `ANDROID_HOME=~/workspace/build-tools/android-sdk`. Gradle
  daemon IPC fails in this sandbox on both attempts ("A new
  daemon was started but could not be connected to" /
  "The first result from the daemon was empty") — an environment
  failure, not a code error, and the first real compile error (if
  any) is therefore still unknown. The coordinator MUST run the
  CI/local build and quote its first error before claiming this
  done. Do not present this work as compile-verified.

---

## 8. G-BUILDER PHONE-TEST CHECKLIST (gate — all must pass on the phone)

Precondition: coordinator wiring (§4) landed + CI green. Tester
uses voice for build/edit steps, hand for editor steps.

1. **Voice 3-file build.** Say: "ek portfolio website banao".
   Expect: Jarvis builds in 3 steps; chat ends with the §6 summary
   (files + preview link spoken). Projects tab shows ONE project
   with index.html, style.css, script.js chips — all three with
   real code visible in the editor (never a blank editor).
2. **Inline preview renders.** In Projects, tap Preview. Expect:
   styled page (creamy background, terracotta button), button JS
   works (tap → message appears), NO blank screen at any point;
   a "server start ho raha hai" line may flash briefly, then the
   page. Kill-test: none — but if the server cannot start, an
   error banner + Retry must appear INSTEAD of a blank box.
3. **Browser-tab preview (NEW GATE — preview decision).** Tap
   "Browser me kholo" on the same project. Expect: Browser tab
   opens, SAME page, full-screen, WITHOUT a reload flash;
   styling identical, scripts work, and tapping a link to a second
   page (multi-page project: add `about.html` + link, §3/§5)
   navigates INSIDE the Browser tab and renders. Back/Forward in
   the Browser tab work across the project's pages.
4. **Multi-file + images.** Build/edit a site with an image asset
   (image served by the localhost server) and a second page.
   Expect: image renders (correct MIME), second page renders,
   no 404 error page for any referenced file.
5. **Hand edit.** In the Projects editor, change one CSS value
   (e.g. button colour) → Save. Expect: status says Saved, inline
   preview updates WITHOUT retyping/reloading manually, and the
   Browser-tab preview shows the same change on return (same
   shared page) or after one Reload tap.
6. **Voice edit.** Say: "is button ka rang badal do" (no project
   named). Expect: Jarvis edits the EXISTING project in place
   (no new project folder appears), speaks what changed, preview
   refreshes; the changed file's editor content matches the spoken
   change. Then say: "ek naya page jodo" → new page file appears
   as a chip + index.html gains a link to it, preview navigates
   to it.
7. **Voice edit, ambiguous.** (Only if 2+ projects exist.) Say a
   bare edit with no project name while the last-touched project
   is unclear to the tester — expect Jarvis to ASK which project
   (never silently edit the wrong one or rebuild).
8. **Missing-file behaviour.** Delete `style.css` from a project
   (editor Delete is per-project; use Add-file flow in reverse:
   tester removes the file via Files app or a fresh voice rebuild
   that drops it — coordinator to define the exact removal step),
   then tap Preview. Expect: readable error page naming the
   missing file + list of existing files — NEVER a blank screen.
9. **Single-file build.** Say: "sirf ek file me ek calculator page
   banao". Expect: ONE step, self-contained index.html, no
   style.css/script.js references, preview works standalone.
10. **No blank screens, anywhere in this flow.** Any failure at
    any step must show a readable message (banner, error page, or
    spoken line) — a blank editor, blank preview box, or silent
    no-op is a FAIL of this gate even if the build "succeeded".

If any item fails, report the exact step + what was shown (or not
shown) — do not mark G-Builder passed on a partial pass.
