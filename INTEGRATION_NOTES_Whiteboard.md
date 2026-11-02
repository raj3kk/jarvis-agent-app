# JarvisAgentApp 1.4.0 — Whiteboard integration notes (worker S4)

Scope of this delivery: 4 new files only. **No existing file was edited,
no version number was touched, nothing was pushed.** The coordinator owns
all wiring below.

## Files created (S4)

| File | Role |
|---|---|
| `app/src/main/java/com/jarvis/autonomous/data/WhiteboardStore.kt` | Shared state + JSON persistence + text-only engine context |
| `app/src/main/java/com/jarvis/autonomous/ui/WhiteboardView.kt` | Classic Android `View` for finger drawing, both hosts |
| `app/src/main/java/com/jarvis/autonomous/ui/WhiteboardScreen.kt` | In-app Compose screen (fallback when overlay is denied) |
| `app/src/main/java/com/jarvis/autonomous/service/JarvisWhiteboardService.kt` | Foreground service, floating window over other apps |

Pattern reused, not reinvented: the floating window copies
`ui/overlay/FloatingJarvisOverlay.kt` — `TYPE_APPLICATION_OVERLAY` on
API 26+, `TYPE_PHONE` below it, `FLAG_NOT_FOCUSABLE`, draggable header/pill
with tap-vs-drag detection. There is no second overlay mechanism.

## 1. Exact manifest service entry (coordinator: paste into `<application>`)

`SYSTEM_ALERT_WINDOW` is already declared — no new permission is needed.
The whiteboard service is a foreground service, so on targetSdk 34 it
needs a declared type; `specialUse` matches the existing
`JarvisForegroundService` pattern:

```xml
<!-- Floating Whiteboard over other apps (S4, 1.4.0) -->
<service
    android:name=".service.JarvisWhiteboardService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Floating whiteboard over other apps" />
</service>
```

Notification ID `1002` (channel `jarvis_whiteboard_channel`) is distinct
from the foreground service's `1001`, so the two notifications never clash.

## 2. Settings / permission-toggle wiring (coordinator: no new permission flow)

Two pieces already exist — reuse them, do not duplicate them:

1. **Permissions tab row (already in `MainActivity.kt`,
   `PermissionsChecklistScreen`)** — "2. Floating HUD Overlay (Draw Over
   Apps)" already opens the system screen with:
   ```kotlin
   Intent(
       Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
       Uri.parse("package:${context.packageName}")
   ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
   ```
   and its granted state is already computed with
   `Settings.canDrawOverlays(context)`. This one row now gates BOTH the
   HUD pill and the whiteboard. The coordinator may extend that row's
   description to mention the whiteboard, but must not add a second
   overlay-permission row that opens the same system screen.
2. **AI Brain toggle (already in `MainActivity.kt`)** —
   `prefs.floatingOverlayEnabled` ("Floating HUD Overlay Pill") stays the
   master on/off for floating UI. The whiteboard service itself does not
   read that pref (it is started explicitly), so the coordinator decides:
   recommended — when the user opens the whiteboard from the entry point
   below, start the service regardless of that toggle, because the user
   just asked for the board; the toggle keeps governing the always-on HUD
   pill only.

**Never bypass the permission screen.** `JarvisWhiteboardService`
checks `Settings.canDrawOverlays()` before adding any window and shows
nothing when it is false. In that case the message is already safe in
`WhiteboardStore`, and `WhiteboardScreen` explains the fallback in plain
text with its own "Open overlay permission settings" button (same intent
as above). The service deliberately does not open Settings itself.

## 3. How the engine posts messages (coordinator: wire into the engine)

From anywhere with a `Context` (the engine already holds one):

```kotlin
// Jarvis feeling / message onto the board. Writes the shared store
// FIRST (so the in-app screen shows it even without overlay permission),
// then asks the floating service to show/refresh.
JarvisWhiteboardService.postMessage(context, "Main tumhara sketch dekh raha hoon — batao isme kya hai?")
```

For a direct intent (e.g. from a receiver or a notification action):

```kotlin
Intent(context, JarvisWhiteboardService::class.java).apply {
    action = JarvisWhiteboardService.ACTION_POST_MESSAGE
    putExtra(JarvisWhiteboardService.EXTRA_MESSAGE, text)
}
```

Other actions: `ACTION_SHOW`, `ACTION_HIDE`, `ACTION_TOGGLE`, or the
companions `JarvisWhiteboardService.show(context)` /
`.hide(context)` / `.toggle(context)`.

When the user is talking about the board, the engine should include
this text context in its prompt — and nothing else from the board:

```kotlin
WhiteboardStore.init(context)
val boardContext = WhiteboardStore.describeForEngine()
// e.g. "Whiteboard context (text only — Jarvis does NOT see pixels):
//  3 strokes, 42 points, bounds x=... image attached: yes (...).
//  User description: ... Latest Jarvis whiteboard message: ..."
```

## 4. Coordinator entry point — RECOMMENDATION: CommandCenter button

**Recommended: a button on the CommandCenter tab, not a new tab.**
Why: the bottom bar already has 5 tabs (Command, Permissions, AI Brain,
Memory & Logs, Projects). A 6th tab crowds the bar and pulls the user
out of the conversation to a standalone destination, while the
whiteboard is used *during* a conversation — the user draws or uploads
something and immediately talks to Jarvis about it. A CommandCenter
button keeps the user one tap from the chat that interprets the board.

Suggested wiring in `MainActivity.kt` (coordinator-owned edit):

```kotlin
// In the JarvisApp composable, next to selectedTab:
var showWhiteboard by remember { mutableStateOf(false) }
// In Scaffold content: if (showWhiteboard) WhiteboardScreen() with a
// Back/Close affordance that sets showWhiteboard = false, else the
// existing when (selectedTab) { ... }.
// In CommandCenterScreen, add a button:
//   Button(onClick = { onOpenWhiteboard() }) { Text("✏️ Whiteboard") }
// where onOpenWhiteboard = { showWhiteboard = true }.
```

If the coordinator instead prefers a tab, it is a mechanical change
(6th `NavigationBarItem` → `WhiteboardScreen()`), but the button above
is the recommended route for the reason stated.

## 5. The two modes (what the user sees)

- **(a) Floating over other apps** — overlay permission granted:
  `JarvisWhiteboardService.show(context)` shows a small draggable
  "✏️ Whiteboard" pill; tapping it expands to the full board (drawing
  view, 5 colour swatches, Clear, Jarvis messages, collapse "—", close
  "✕"). Close stops the service. The board survives collapse/expand
  because both hosts read the same `WhiteboardStore`.
- **(b) Inside the app** — overlay permission denied: `WhiteboardScreen`
  says so in plain text on screen ("Floating whiteboard over other
  apps is NOT available right now, because overlay permission has not
  been granted. This in-app whiteboard is the fallback…"), offers the
  system permission button, and provides the identical drawing view,
  image picker, description field and message list. Nothing is lost
  between modes: strokes persist to `filesDir/whiteboard.json`, the
  image to `filesDir/whiteboard_image`, messages alongside the strokes.

## 6. WHITEBOARD VISION — corrected honest limit (amendment 7, hard rule)

Earlier wording ("Jarvis sees the drawing/image") was imprecise and is
**corrected here and in code comments**:

- **On-device Gemma cannot see images. There is no vision model in the
  app.** A user drawing or upload becomes Jarvis-understood on-device
  only via the **user's spoken description** — typed into the
  "Describe this board for Jarvis" field (or spoken and then typed by
  the user) and stored as `WhiteboardStore.state.userCaption`, which
  `describeForEngine()` includes as text context for the reply.
- **Cloud vision is the only pixel path, and only with the user's own
  Gemini key.** If the user has a Gemini key, a future engine step (not
  built by S4) may send the image to Gemini vision. It must never be
  described as on-device understanding, and it must never happen
  silently: the user must have supplied the key and the send must be
  visible in the chat/log.
- **Without a user description (and without cloud vision via the user's
  key), a drawing/upload is NOT understood** — the engine only knows
  stroke count, stroke bounds, and that an image is attached. Every
  user-facing string in S4's files says this; never claim pixel
  understanding in UI copy, release notes, or replies.
- **HARD RULE — whiteboard images are NEVER uploaded to the global
  learning server.** The image lives only in the app's `filesDir`
  (`whiteboard_image`). `WhiteboardStore` has no upload code, and
  `describeForEngine()` returns text only (stroke counts/bounds, image
  filename, user caption) precisely so that no caller needs the image
  bytes. Any future "learning" feature must exclude whiteboard images
  by name; do not add an upload call to this store.

## 7. UI consistency (amendment 10)

All whiteboard UI follows the existing creamy theme — **no default
grey screens**:

- Background `0xFFFFF8ED` (creamy white), primary/terracotta
  `0xFFB85C38`, text `0xFF3B2F2A`.
- The drawing surface itself is white (`Color.WHITE`) for contrast,
  framed by a terracotta border in-app and a terracotta-stroked creamy
  panel in the floating window; the floating pill is creamy with a
  terracotta stroke.
- Colour swatches start with terracotta, then dark brown, green, blue,
  pink (`WhiteboardView.COLORS`).

## 8. What S4 did NOT do (coordinator checklist)

- [ ] Paste the manifest entry from §1 (S4 was forbidden to edit it).
- [ ] Add the CommandCenter whiteboard button from §4 (S4 was forbidden
      to edit `MainActivity.kt`).
- [ ] Optionally extend the Permissions-tab overlay row description to
      mention the whiteboard (§2).
- [ ] Wire `describeForEngine()` into the engine prompt when the user
      mentions the board, and `postMessage()` for Jarvis feelings (§3).
- [ ] Verify on a phone: floating pill over another app, drawing syncs
      between floating and in-app views, fallback text shows with
      permission denied, image stays local (no network call from the
      store — grep for upload code in `WhiteboardStore.kt`: there is none).
- [ ] Do not bump the version for this worker alone; versioning is the
      coordinator's 1.4.0 step.
