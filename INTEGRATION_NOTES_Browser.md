# S3 — In-app Browser: integration spec for the coordinator

> Worker S3 scope only. S3 created exactly two files and edited nothing
> else, pushed nothing, and touched no version number:
>
> - `app/src/main/java/com/jarvis/autonomous/ui/BrowserScreen.kt`
>   (screen + shared browser engine + background-browse API + the
>   reusable `JarvisWebView` rendering composable, all in one file, so
>   the strict file-ownership rule holds)
> - this file (`INTEGRATION_NOTES_Browser.md`, repo root)
>
> Everything in §§6 and 12 is a spec for coordinator edits in
> `MainActivity.kt`, `JarvisAgentEngine.kt`, and `LlmClient.kt`. None of
> those edits are done here. The coordinator must apply them; S3's code
> compiles standalone but does nothing engine-side until wired.

## 1. What was built

A self-contained in-app browser that never leaves the app on its own:

- **URL bar** (editable; bare domains default to `https://`, see §4),
  **Back / Forward / Reload** buttons, **loading progress**
  (`LinearProgressIndicator` from the shared client's
  `onProgressChanged`), a **Share** button and an **open-externally**
  button.
- **External open is OFF by default.** There is no automatic
  `ACTION_VIEW` anywhere in `BrowserScreen.kt`. The only path out of
  the app is the "Bahari browser" button, fired on an explicit user tap
  (same for Share, which uses an `ACTION_SEND` chooser).
- **WebView lifecycle, no leaks:** the shared WebView gets `onResume` /
  `onPause` from the *activity* lifecycle observer only (see §3). It is
  **never destroyed on tab dispose** — `AndroidView.onRelease` is
  intentionally a no-op — and only `JarvisBackgroundBrowser.destroy()`
  (Main thread, app shutdown only) destroys it.
- **Manual browsing works standalone:** `BrowserScreen()` with no
  arguments creates its own `BrowserHostState`; the user types a URL,
  browses, and navigates with zero engine involvement.

## 2. UI consistency (miss-analysis item 10) — creamy theme, no grey

`BrowserScreen` uses the app's existing creamy palette explicitly, not
Material defaults:

| Role | Value |
|---|---|
| Screen background | `0xFFFFF8ED` |
| Terracotta primary / headings | `0xFFB85C38` |
| Body text | `0xFF3B2F2A` |
| Muted helper text | `0xFF8A7A70` |
| Non-HTTPS banner background / text | `0xFFFFE0B2` / `0xFF8D4A2B` |
| Page area | white (the WebView itself) |

This matches `MainActivity`'s `JarvisColorScheme` and `ProjectsScreen`
(the pattern this screen copies: `WebView` + `WebViewClient` inside
`AndroidView`, INTERNET permission already in the manifest).

## 3. Two modes, one WebView engine (user refinement, 2026-11-02 23:00 IST)

Browsing happens **inside the app, in the background, without the user
going anywhere**. There are exactly two modes sharing one engine:

### Mode (a) — BACKGROUND browsing (the DEFAULT for question-answering)

When the user asks something that needs browsing, Jarvis loads the page
in a hidden/off-screen WebView inside the app — no Chrome, no leaving
the app, no visible page load the user has to watch — extracts title +
main text, and the engine speaks/shows the **ANSWER** directly. The user
never watches a load.

- **Hosting:** a single `WebView` instance owned by the singleton
  `JarvisBackgroundBrowser` in `BrowserScreen.kt` — created lazily with
  the **application context**, **not** tied to any tab's Compose
  lifecycle, so it survives tab switches and tab disposal. It is not
  attached to any window while headless; the foreground tab borrows the
  same instance through `AndroidView` when shown.
- **It never touches tab state:** background browsing does NOT set
  `BrowserHostState.foregroundUrl` and does NOT call
  `onShowBrowserTab`. If it did, the user would be yanked to a loading
  tab — that would be mode (b), not mode (a).
- **Entry point (suspend, for the engine):**

  ```kotlin
  suspend fun JarvisBackgroundBrowser.browse(
      context: Context,
      rawUrl: String,
      timeoutMs: Long = 30_000L
  ): BrowserPageResult
  ```

  plus a callback variant for non-suspend callers:

  ```kotlin
  fun JarvisBackgroundBrowser.browseAsync(
      context: Context,
      rawUrl: String,
      timeoutMs: Long = 30_000L,
      onResult: (BrowserPageResult) -> Unit
  )
  ```

### Mode (b) — FOREGROUND show (only on explicit user want-to-see)

Only if the user explicitly wants to **SEE** the page (e.g. "dikhao",
"khol ke dikhao", "page dikhao") does the same WebView come to the
foreground in the Browser tab with that page loaded.

- **Coordinator entry point:**

  ```kotlin
  val browserHost = remember { BrowserHostState() }
  // in MainActivity's tab bar setup:
  browserHost.onShowBrowserTab = { selectedTab = <Browser tab index> }
  // engine foreground path (see §6):
  browserHost.showLoadedPage(url)   // NOT loadUrl() for mode (b)
  ```

- **No-reload handoff (exact mechanism):** `BrowserScreen`'s
  `AndroidView.update` block loads **only** when
  `webView.url != currentUrl`. After a background browse of the same
  URL, `webView.url` already equals the target, so showing the tab
  displays the already-loaded page instantly with **zero reload**.
  `loadUrl(url)` is the same setter but is the *foreground-load*
  entry point (use it when the page was NOT already background-loaded,
  or for a typed URL from another tab); `showLoadedPage(url)` is the
  *already-loaded* entry point. Both switch to the Browser tab; only
  the intent — and whether a load actually happens — differs, and the
  difference is decided entirely by that `webView.url` equality check.
- **Tab-return, same mechanism:** when the user leaves the Browser tab
  and comes back, `BrowserScreen` re-reads `host.foregroundUrl` and the
  shared WebView still holds the page, so the update check skips the
  load and the page reappears instantly.

## 4. URL normalisation (one rule, used by screen + engine + action)

`normalizeBrowserUrl(raw: String): String` in `BrowserScreen.kt`:

- blank → `""` (caller must refuse to load);
- `http://` / `https://` kept as-is;
- any other scheme (`javascript:`, `file:`, `intent:`, `data:`, …) →
  `""` — **blocked**, only web pages may load;
- `localhost` / `127.0.0.1` → `http://` prefix (loopback never leaves
  the device; this keeps the Projects localhost preview loadable);
- anything else (bare domain, e.g. `example.com`) → `https://` prefix.

## 5. Extraction API + background result + process-killed rule

### Exact extraction pipeline (`JarvisBackgroundBrowser.browse`)

1. Normalise the URL (§4); blank/blocked → `FAILED`, no load.
2. Write the pending marker (below).
3. If `webView.url == url`, skip the load and extract directly;
   otherwise `webView.loadUrl(url)`.
4. On `onPageFinished` (first win only — sub-frame finishes are
   ignored via an `AtomicBoolean`), wait a **short settle delay of
   800 ms** for late JS, then run `evaluateJavascript` twice:
   - `document.title`
   - `document.body ? document.body.innerText.slice(0, 2000) : ''`
5. Decode each JS result with `decodeJsString` (JS string results
   arrive JSON-quoted; parsing `{"v":<raw>}` unescapes them),
   collapse whitespace (`\s+` → single space), trim, and truncate the
   body text to **~2000 chars** (the JS `slice(0, 2000)` plus a Kotlin
   `.take(2000)` double-guard).
6. Overall **timeout 30 000 ms** (`timeoutMs` parameter). On timeout
   the WebView is `stopLoading()`-ed and the result is `FAILED` —
   never partial or fabricated text.
7. Load errors (`onReceivedError`, main frame only) → `FAILED` with
   the error description. The pending marker is cleared on every
   result that is actually delivered (COMPLETED, FAILED, or
   in-memory INTERRUPTED).

### Result type (implemented in `BrowserScreen.kt`)

```kotlin
enum class BrowserBrowseStatus {
    COMPLETED,
    INTERRUPTED_PROCESS_KILLED,
    FAILED
}

data class BrowserPageResult(
    val url: String,
    val title: String = "",
    val mainText: String = "",          // whitespace-collapsed, ≤ 2000 chars
    val isNonHttps: Boolean = false,    // true iff url starts with http://
    val status: BrowserBrowseStatus = BrowserBrowseStatus.COMPLETED,
    val errorMessage: String = ""
)
```

### FULL CHECK — background browsing lives only while the app lives

Background WebView browsing works **only while the app / foreground
service is alive**. If Android kills the process mid-browse, the browse
job stops with it — there is no result, no retry, and no answer.

Implementation (in `BrowserScreen.kt`, no other file needed):

- `browse()` writes a `SharedPreferences` marker
  (`jarvis_browser_pending` / `pending_url`) **before** loading and
  clears it on every delivered result.
- If the process dies mid-browse, the marker survives. On the **next
  user interaction after restart**, the engine must call:

  ```kotlin
  fun JarvisBackgroundBrowser.takeInterruptedBrowse(context: Context): String?
  ```

  A non-null return is a **single report** (the marker is cleared on
  read, so it never repeats on later launches) meaning: *that browse
  was interrupted by a process kill*.
- **Engine behaviour on INTERRUPTED (mandatory, both paths):**
  whether the status arrives as a `BrowserPageResult` with
  `status == INTERRUPTED_PROCESS_KILLED` (job cancelled while the app
  was alive — `browse()` deliberately converts cancellation into this
  status instead of rethrowing, so the engine can report it) or via
  `takeInterruptedBrowse()` on the next interaction, the engine must
  speak **"Browsing ruk gaya, dobara koshish karun?"** (or equivalent
  Hinglish) and re-browse **only on the user's yes**. It must **never
  invent, guess, or summarise page content it did not receive** — not
  for INTERRUPTED, and not for FAILED either (FAILED speaks its own
  failure line, e.g. "Page nahi khul paya", plus a retry offer).
- The same no-fabrication rule covers the pending-marker path: a
  marker with no result is evidence of interruption, not evidence of
  any page content.

## 6. OPEN_BROWSER engine action spec (coordinator edits)

### 6.1 Action definition

**Action name: `OPEN_BROWSER`** — new, and distinct from the existing
`OPEN_URL`. `OPEN_URL` goes through the accessibility service and may
leave the app; `OPEN_BROWSER` **never** leaves the app and must **not**
be routed through `acc.openUrlOrDeepLink`.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `browserUrl` | `String` | `""` | Raw URL or bare domain; engine normalises via `normalizeBrowserUrl`. Empty → no-op + speak that the URL is missing. (The original brief's `url` field is this field, renamed to avoid clashing with `AgentDecision.text`, which `OPEN_URL` / YouTube actions already use.) |
| `browserSpeakSummary` | `Boolean` | `true` | Background mode: after a COMPLETED extraction, speak a short summary (title + `mainText.take(500)`) and show the full `mainText` in chat. Foreground mode: speak the same short summary after showing the page, if true. |
| `browserShowPage` | `Boolean` | `false` | Refinement addition. `false` = mode (a) background (default). `true` = mode (b) foreground show — set **only** when the user explicitly asked to SEE the page. |

Add to `AgentDecision` in `LlmClient.kt` (next to `projectName` /
`fileName` / `fileContent`):

```kotlin
val browserUrl: String = "",
val browserSpeakSummary: Boolean = true,
val browserShowPage: Boolean = false,
```

and in the JSON parse (~line 400, next to the other `optString` /
`optBoolean` reads):

```kotlin
browserUrl = obj.optString("browserUrl", ""),
browserSpeakSummary = obj.optBoolean("browserSpeakSummary", true),
browserShowPage = obj.optBoolean("browserShowPage", false),
```

### 6.2 LlmClient prompt additions (coordinator edit)

Add `OPEN_BROWSER` to the action list in the system prompt (next to
`CREATE_PROJECT` / `WRITE_PROJECT_FILE`), with these instructions:

- For any web question/lookup, use `OPEN_BROWSER`, never `OPEN_URL`.
  Fill `browserUrl` with the page the user named (or the exact page
  Jarvis proposed and the user approved — see §7).
- `browserShowPage = true` ONLY when the user explicitly said they
  want to SEE the page ("dikhao", "khol ke dikhao", "page dikhao").
  Otherwise `false` (background answer, no tab switch, no visible load).
- `browserSpeakSummary = true` for question-answering; `false` only
  when the user wants a silent load-and-show.

### 6.3 `executeDecisionAction` routing (coordinator edit, sketch)

New `when` branch in `JarvisAgentEngine.executeDecisionAction`
(~line 460), placed next to the `OPEN_URL` branch. The engine needs
two things the coordinator wires in: the `Context` it already has
(for `browse()` / `takeInterruptedBrowse()`), and a foreground
callback — preferably a constructor lambda
`onShowBrowserPage: ((String) -> Unit)? = null` (coordinator passes
`{ url -> browserHost.showLoadedPage(url) }`), so the engine does not
depend on the `ui` package directly:

```kotlin
"OPEN_BROWSER" -> {
    val raw = d.browserUrl.ifBlank { "" }   // do NOT fall back to d.text:
                                            // text belongs to other actions
    val url = normalizeBrowserUrl(raw)      // ui.BrowserScreen.kt helper
    if (url.isEmpty()) {
        "OPEN_BROWSER blocked: empty/blocked URL"
    } else if (!isUserApprovedBrowserUrl(url)) {   // §7 approval gate
        // Do NOT load. Ask, then wait for the user's reply; only a
        // later, approved OPEN_BROWSER may load this URL.
        onSpeakAndAwait("Ye page kholun? $url")
        "OPEN_BROWSER awaiting approval for '$url'"
    } else if (d.browserShowPage) {
        // Mode (b): already-loaded pages show without reload (§3).
        onShowBrowserPage?.invoke(url)
        "OPEN_BROWSER foreground show '$url'"
    } else {
        // Mode (a): background answer, no tab switch, no visible load.
        val result = JarvisBackgroundBrowser.browse(context, url)
        when (result.status) {
            BrowserBrowseStatus.COMPLETED -> {
                if (result.isNonHttps) {
                    // Spoken warning — see §9 for the exact wording.
                    onSpeakAndAwait(
                        "Note: ye page secure (https) nahi hai, " +
                        "isliye is par koi password ya payment mat daalna."
                    )
                }
                val summary = listOf(result.title, result.mainText.take(500))
                    .filter { it.isNotBlank() }.joinToString(" — ")
                if (d.browserSpeakSummary && summary.isNotBlank()) {
                    onSpeakAndAwait(summary)
                }
                JarvisStateRepository.addChatMessage(
                    "Jarvis",
                    listOf(result.title, result.mainText)
                        .filter { it.isNotBlank() }.joinToString("\n")
                )
                "OPEN_BROWSER background '${result.url}' title='${result.title}' chars=${result.mainText.length}"
            }
            BrowserBrowseStatus.INTERRUPTED_PROCESS_KILLED -> {
                onSpeakAndAwait("Browsing ruk gaya, dobara koshish karun?")
                "OPEN_BROWSER interrupted '$url' (no content fabricated)"
            }
            BrowserBrowseStatus.FAILED -> {
                onSpeakAndAwait(
                    "Page nahi khul paya: ${result.errorMessage}. Dobara koshish karun?"
                )
                "OPEN_BROWSER failed '$url': ${result.errorMessage}"
            }
        }
    }
}
```

Also, at the start of `handleUserCommand` (or on engine start after a
restart), the coordinator adds the interrupted-check (§5):

```kotlin
val interruptedUrl = JarvisBackgroundBrowser.takeInterruptedBrowse(context)
if (interruptedUrl != null) {
    onSpeakAndAwait("Browsing ruk gaya, dobara koshish karun?")
    // Re-browse ONLY on the user's yes; never answer from thin air.
}
```

`isUserApprovedBrowserUrl(url)` is coordinator logic implementing §7
(it needs the conversation state the engine already tracks — the
user's own message and the immediately preceding Jarvis proposal).
S3 does not invent its implementation; §7 states the rule it must
enforce.

### 6.4 Callback / state-holder summary (what the coordinator wires)

| Piece | Owner | Wiring |
|---|---|---|
| `BrowserHostState.loadUrl(url)` | `BrowserScreen.kt` | Coordinator hoists one instance, sets `onShowBrowserTab`, passes it to `BrowserScreen`. Foreground load + tab switch. |
| `BrowserHostState.showLoadedPage(url)` | `BrowserScreen.kt` | Same instance. Foreground show of an already-loaded page; no reload via the `webView.url` equality check. |
| `JarvisBackgroundBrowser.browse(...)` | `BrowserScreen.kt` | Engine suspend call, mode (a). Returns `BrowserPageResult`. |
| `JarvisBackgroundBrowser.browseAsync(...)` | `BrowserScreen.kt` | Same, callback form. |
| `JarvisBackgroundBrowser.takeInterruptedBrowse(...)` | `BrowserScreen.kt` | Engine, next interaction after restart. |
| `JarvisBackgroundBrowser.setForegroundCallbacks(...)` | `BrowserScreen.kt` | `BrowserScreen` only (registered in `DisposableEffect`, cleared on dispose). The coordinator never touches this. |
| `onShowBrowserPage` lambda | engine constructor param (coordinator edit) | Coordinator passes `{ url -> browserHost.showLoadedPage(url) }`. |

## 7. Safety rule — the exact approval rule the engine must enforce

**(Miss-analysis item 11, binding on the ENGINE, not just the screen.)**

> **Jarvis opens only URLs the user asked for, or URLs Jarvis
> explicitly proposed AND the user approved.**

Enforcement, before ANY load in either mode (background hidden loads
are NOT exempt — hidden ≠ unapproved):

- **ALLOW** if and only if one of these holds:
  1. **User-asked:** the URL / site was named in the user's own
     current command (typed or spoken), or the user's question
     directly requires that specific page the user named; or
  2. **User-approved proposal:** Jarvis named that exact URL in its
     reply, and the user's **immediately following** reply affirmed
     it (`haan` / `yes` / `khol do` / `dikhao` or equivalent).
- **Otherwise: DO NOT LOAD.** Instead Jarvis asks
  `"Ye page kholun? <url>"` and waits. A later load of that URL is
  allowed only after an affirmative reply (case 2).
- **No speculative browsing:** no prefetching, no opening "related"
  pages, no following links found inside a loaded page automatically
  in background mode. In foreground mode, a link the **user taps
  manually** is itself a user action and is allowed.
- **Audit:** the `executeDecisionAction` summary string must record
  which case allowed the load (`user-asked` vs `user-approved
  proposal`) or that the load was blocked awaiting approval, so the
  decision is visible in logs.

Manual browsing in the tab needs no approval gate: typing a URL is
the user asking, by definition.

## 8. Safety notes (implemented in `BrowserScreen.kt`)

- **JavaScript enabled — narrow, stated choice:** `javaScriptEnabled
  = true` because modern pages need it for normal browsing. **No
  `addJavascriptInterface` is ever added**, so page JS cannot call
  into the app. JS dialogs are suppressed (`onJsAlert` / `onJsConfirm`
  / `onJsPrompt` cancel), and popups/multiple windows are off
  (`javaScriptCanOpenWindowsAutomatically = false`,
  `setSupportMultipleWindows(false)`).
- **No credential autofill:** the WebView sets
  `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`; no password or form
  data is ever saved or filled by this browser.
- **No file upload from the WebView:** `onShowFileChooser` always
  cancels (`onReceiveValue(null)`, returns `true`); `allowFileAccess`,
  `allowContentAccess`, `allowFileAccessFromFileURLs`, and
  `allowUniversalAccessFromFileURLs` are all `false`.
- **No downloads:** the `DownloadListener` only shows a note; no file
  is ever written by tapping a download.
- **No geolocation** (`setGeolocationEnabled(false)`), **no mixed
  content** (`MIXED_CONTENT_NEVER_ALLOW`), **Safe Browsing on**.
- **Never leaves the app on its own:** `shouldOverrideUrlLoading`
  returns `false` only for `http`/`https` (stays in the WebView) and
  `true` for everything else (blocked, with an on-screen error).
  External open / share happen only on explicit taps (§1).

## 9. Non-HTTPS warning behaviour, per mode

| Mode | Behaviour |
|---|---|
| **(b) Foreground** | Visible banner above the page for **every** `http://` load (literal rule, including loopback): *"⚠️ Ye page secure nahi hai (http, https nahi) — is par password, OTP ya payment mat daalo."* Driven by `currentUrl.startsWith("http://")`, so it also appears on redirect to `http://`. |
| **(a) Background** | No banner exists (nothing is shown), so the result carries `isNonHttps: Boolean`. On COMPLETED with `isNonHttps == true`, the engine **speaks** before the summary: *"Note: ye page secure (https) nahi hai, isliye is par koi password ya payment mat daalna."* The flag is also visible in the action summary log. |

## 10. Manual browsing (standalone, no engine)

Open the Browser tab, type a URL or bare domain in the URL bar, press
Go (or the keyboard Go action): the bar normalises per §4, the shared
WebView loads in-app, Back/Forward/Reload navigate history, the
progress bar and title update from the shared client, and the
non-HTTPS banner appears for `http://` pages. Nothing here involves
the engine, the approval gate (§7), or any tab switch.

## 11. Known limitations (honest, phone verification still required)

1. **Headless WebView is not attached to a window.** `onPageFinished`
   + `evaluateJavascript` generally work unattached, but some pages
   rely on rendering-gated JS (timers, `IntersectionObserver`,
   lazy-loaded content) that may not run until the WebView is
   attached. Background extraction is therefore best-effort for
   heavy JS pages; the foreground attach is the guaranteed path
   (showing the tab attaches the same instance, after which a
   re-extract will see the rendered content).
2. **Application-context WebView.** JS dialogs are suppressed and
   file upload is cancelled by design (§8); a site that requires an
   Activity context (custom handlers, login popups) may behave
   differently than in Chrome. This is accepted: the browser must
   never leave the app or pop an Activity dialog from a background
   job.
3. **One page at a time (single shared instance).** A background
   browse navigates the shared WebView away from whatever the
   foreground tab is showing. **Coordinator rule:** only
   background-browse when the Browser tab is not currently visible,
   or tell the user the visible page will change first. Do not
   "fix" this by creating a second WebView inside the tab — that
   would break the no-reload handoff in §3.
4. **Process-kill detection is marker-based (§5).** It reports the
   interruption on the next interaction; it cannot resurrect the
   killed job or deliver a late answer. The user must confirm a retry.
5. **No login persistence promise.** Nothing in this file manages
   cookies or sessions specially; sites that need a login will behave
   as a fresh WebView session behaves, and §8 forbids autofilling
   credentials into it.

## 12. Coordinator checklist (edits S3 did NOT make)

1. `MainActivity.kt`: hoist `val browserHost = remember {
   BrowserHostState() }`, add a Browser `NavigationBarItem` (next free
   index after Projects), set
   `browserHost.onShowBrowserTab = { selectedTab = <that index> }`,
   and render `BrowserScreen(browserHost)` for it.
2. `LlmClient.kt`: add the three `AgentDecision` fields (§6.1), parse
   them, and add the `OPEN_BROWSER` prompt block (§6.2).
3. `JarvisAgentEngine.kt`: add the `OPEN_BROWSER` branch (§6.3) with
   the §7 approval gate, the `onShowBrowserPage` constructor lambda,
   and the `takeInterruptedBrowse` check on the next interaction.
4. Do NOT route `OPEN_BROWSER` through `acc.openUrlOrDeepLink`
   (that is `OPEN_URL`'s path and may leave the app).
5. Do NOT call `JarvisBackgroundBrowser.destroy()` from any tab
   dispose — app shutdown only.
6. Phone-verify before claiming done: (i) manual browse + back/
   forward/reload; (ii) `http://` banner visible; (iii) background
   Q&A speaks the answer with no tab switch and no visible load;
   (iv) explicit "dikhao" shows the already-loaded page with no
   reload spinner; (v) external button is the only exit; (vi) kill
   the app mid-browse, reopen, and confirm the interrupted line is
   spoken once and no answer is invented; (vii) Projects preview
   renders full CSS/JS/images and multi-page links (§14), and
   "Open in Browser" shows the same preview with no reload.
7. `ProjectsScreen.kt` (coordinator edit, §14): accept an optional
   `browserHost: BrowserHostState? = null`, render the preview through
   `JarvisWebView`, and add the "Open in Browser" button calling
   `browserHost.openInBrowser(previewUrl)` (button shown only when
   `browserHost != null`). Pass the hoisted `browserHost` from
   `MainActivity` into both `ProjectsScreen` and `BrowserScreen`.
8. Do NOT create a second, preview-only WebView setup in
   `ProjectsScreen` — that would fork the engine and break the
   no-reload handoff. The preview's old `AndroidView` + local
   `WebViewClient` block is replaced by `JarvisWebView`, not kept
   alongside it.

## 14. Shared rendering engine + Projects preview (preview decision, 2026-11-02 23:09 IST)

The builder preview MUST render properly IN THE BROWSER itself. So the
WebView stack in `BrowserScreen.kt` is the shared rendering engine —
not a one-off tab-only WebView. The Projects preview does not get its
own browser; it renders through this engine.

### 14.1 The shared pieces (all in `BrowserScreen.kt`)

| Piece | What it is | Who calls it |
|---|---|---|
| `JarvisBackgroundBrowser.obtainWebView(context)` | Shared factory + configuration: the single `WebView` instance, created once with the application context, with the full rendering settings in §14.2 and the single shared client (links stay in-app, file upload cancelled, JS dialogs suppressed, downloads blocked). | `JarvisWebView` (both callers below). Never called directly by tabs, and never destroyed on tab dispose. |
| `JarvisWebView(targetUrl, modifier, onTitle, onProgress, onUrlChanged, onError, onWebViewReady)` | Reusable rendering composable: borrows the shared instance via `AndroidView`, registers the foreground callbacks, owns the activity-lifecycle onResume/onPause, and loads `targetUrl` ONLY when it differs from `webView.url` (the no-reload rule). | Browser tab AND Projects preview. |
| `BrowserHostState.openInBrowser(url)` | "Open in Browser" entry point. Same path as `showLoadedPage`: sets `foregroundUrl` + switches to the Browser tab. | Projects preview button (coordinator edit, §14.3). |
| `BrowserHostState.loadUrl(url)` / `showLoadedPage(url)` | Foreground load / already-loaded show (see §3, §6.4). | Coordinator / engine foreground path. |

Constraint: only ONE `JarvisWebView` may be composed at a time
(tab switching already guarantees this). Its callbacks are registered
once per lifecycle owner, so callers must pass lambdas capturing only
stable state (remembered holders and state setters) — which is what
`BrowserScreen` does, and what the preview wiring in §14.3 must do too.

### 14.2 Rendering settings (quality bar: "exactly like a real website")

Set in `obtainWebView`, so every caller gets them identically:

- **Full HTML + CSS + JS execution:** `javaScriptEnabled = true`
  (no JS bridge — §8), `domStorageEnabled = true` (modern pages'
  local/session storage), `cacheMode = LOAD_DEFAULT`.
- **Images:** `loadsImagesAutomatically = true`,
  `blockNetworkImage = false`.
- **Viewport / layout:** `loadWithOverviewMode = true`,
  `useWideViewPort = true` (pages honour their viewport meta, like a
  real mobile browser, instead of rendering desktop-wide and tiny).
- **Zoom:** `setSupportZoom(true)`, `builtInZoomControls = true`,
  `displayZoomControls = false` (pinch zoom works, no overlay buttons).
- **Multi-page navigation:** the shared client's
  `shouldOverrideUrlLoading` returns `false` for all `http`/`https`,
  so in-page links navigate inside the same WebView and build real
  back/forward history (`canGoBack` / `canGoForward`). A project with
  several pages navigates between them exactly like a website,
  because the preview server serves the whole project folder.
- **Safety trade-off, stated:** `MIXED_CONTENT_NEVER_ALLOW` is kept
  (§8). It is stricter than Chrome for `http` subresources on an
  `https` page (those images will not load). Nearly all modern sites
  serve images over `https`, so this rarely bites; the stricter
  setting is accepted rather than silently weakening the browser.

### 14.3 Projects preview wiring (coordinator edit in `ProjectsScreen.kt`)

The preview is served by `ProjectPreviewServer` at
`http://127.0.0.1:8080/<project>/` (existing code; unchanged by S3).
The coordinator rewires only the *rendering* of that URL:

```kotlin
// ProjectsScreen signature change (coordinator edit):
@Composable
fun ProjectsScreen(browserHost: BrowserHostState? = null) { ... }

// Preview section change: REPLACE the old local AndroidView +
// WebViewClient block with the shared engine composable:
val url = previewUrl
if (url != null) {
    Text("Live Preview (localhost, isi phone par):", ...)
    Text(url, ...)
    JarvisWebView(
        targetUrl = url,
        onTitle = { /* preview title state, if shown */ },
        onProgress = { /* preview progress state, if shown */ },
        onUrlChanged = { arrived -> previewUrl = arrived },
        onError = { status = it },
        modifier = Modifier.fillMaxWidth().height(420.dp)
    )
    // "Open in Browser" — only when the browser tab is wired:
    if (browserHost != null) {
        OutlinedButton(onClick = { browserHost.openInBrowser(url) }) {
            Text("Open in Browser")
        }
    }
}
```

And in `MainActivity`, the same hoisted instance goes to both tabs:

```kotlin
val browserHost = remember { BrowserHostState() }
browserHost.onShowBrowserTab = { selectedTab = <Browser tab index> }
// ...
4 -> ProjectsScreen(browserHost)
<Browser tab index> -> BrowserScreen(browserHost)
```

### 14.4 "Open in Browser" — why it does not reload

Sequence: user taps "Open in Browser" on a preview → coordinator calls
`browserHost.openInBrowser(previewUrl)` → `foregroundUrl` is set and
the tab switches to Browser → `BrowserScreen` renders `JarvisWebView`
with `targetUrl = foregroundUrl` → the shared WebView's `url` already
equals that localhost URL (the preview rendered it through the same
instance) → the no-reload equality check skips `loadUrl` → the page
appears full-screen instantly, same scroll/render state, no
re-fetch from the preview server. If the URL somehow differs (e.g.
the preview navigated after the button was drawn), the check falls
through to a normal load — still in-app, still foreground mode.

Note the §9 banner: a localhost preview opened in the Browser tab is
an `http://` load, so the non-HTTPS banner shows above it (literal
rule, §9). It does not affect rendering; loopback traffic never
leaves the device, and the user can ignore the banner for local
previews.

### 14.5 Quality-bar verification (coordinator, phone-side)

Before claiming the preview bar is met, verify on the phone with a
real project (CSS file, JS file, images, and at least two linked
pages): (i) styles apply exactly (colours, layout, fonts);
(ii) JS runs (interactive elements respond); (iii) images load;
(iv) tapping an internal link navigates to the second page inside
the preview AND inside the Browser tab after "Open in Browser";
(v) Back returns to the first page in both places; (vi) no reload
spinner appears on the "Open in Browser" switch. A localhost
server-side failure (preview server not running) is not a rendering
failure — `ProjectPreviewServer.ensureRunning` must still be called
before showing the preview, as the current code does.

## 15. Verification done by S3

- Read and applied `~/workspace/custom-skills/kotlin_prepush_checks/SKILL.md`:
  all state is declared before the callbacks / `LaunchedEffect` /
  `DisposableEffect` that capture it (verified by reading the file
  top-to-bottom after writing); nullable WebView values are guarded
  with `?: return` / null-checks before use (no copied-nullable
  reliance); no function shares a property-setter name; no
  `addJavascriptInterface`; imports cover exactly the APIs used
  (`android.webkit.*` incl. `WebViewClient` / `WebChromeClient`,
  `androidx.compose.ui.viewinterop.AndroidView`, `org.json.JSONObject`
  for JS-string decoding).
- Local compile: attempted (see the final report for the outcome —
  S3 does not claim compile success unless the Gradle run actually
  passed).
- Not done by S3 (out of scope / forbidden): no edits to existing
  files, no push, no version-number change, no live-browser use.
