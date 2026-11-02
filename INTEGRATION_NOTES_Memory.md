# INTEGRATION NOTES — Memory-first + learned routines (S5, JarvisAgentApp 1.4.0)

**Scope of this worker:** two new files only —
`app/src/main/java/com/jarvis/autonomous/data/LearnedRoutineStore.kt`
and `.../data/MemoryIndex.kt` (includes the `ChatSummary` helper).
No existing file was edited by S5. Everything below is wiring the
**coordinator must apply** to `JarvisAgentEngine.kt` (and, where
noted, a small connectivity check). Do not wire by guesswork: the
order, the gates, and the never-record list are the safety contract.

**Files created by S5 (do not move):**
- `app/src/main/java/com/jarvis/autonomous/data/LearnedRoutineStore.kt`
- `app/src/main/java/com/jarvis/autonomous/data/MemoryIndex.kt`
- `INTEGRATION_NOTES_Memory.md` (this file)

---

## 1. Wiring order in `handleUserCommand` — decided, with justification

Apply in exactly this order. Each stage that handles the command
**returns immediately**; exactly one execution path runs per command
(no double-execution). Replay lives ONLY in `handleUserCommand` —
never inside `runAutonomousLoop` — so a replay fallback into the LLM
loop cannot loop back into replay.

```
fun handleUserCommand(rawCommand: String) {
    val trimmed = rawCommand.trim()
    if (trimmed.isEmpty()) return
    val lower = trimmed.lowercase()

    // 0. Stop commands — unchanged, first, always.
    if (lower in listOf("stop", "ruk ja", "ruko", "cancel",
            "jarvis stop", "bas kar")) { cancelActiveTask(...); return }

    // 1. MEMORY RECALL (read-only, never executes anything).
    //    This is the "search memory first" order: BEFORE any task
    //    runs, recall — but recall alone cannot double-execute,
    //    because it only reads. Keep it first so every later path
    //    (built-in, learned, LLM) can use the same context.
    memoryIndex.rebuildIfNeeded()          // cheap no-op when healthy
    val memoryHits = memoryIndex.search(trimmed)          // top 5 ids
    val memoryContext = memoryHits.mapNotNull { memoryIndex.getSummary(it) }

    // 2. USER CUSTOM VoiceRoutine (prefs.getRoutines()) — WINS.
    //    Unchanged existing behaviour: an explicit user macro is a
    //    deliberate instruction, so it outranks every learned guess.
    val matchedRoutine = prefs.getRoutines().firstOrNull {
        lower.contains(it.triggerPhrase.lowercase())
    }
    if (matchedRoutine != null) {
        // Existing path: effectiveGoal = matchedRoutine.actionPrompt,
        // then runAutonomousLoop(effectiveGoal). Pass memoryContext
        // into the loop (see §2) and return.
    }

    // 3. BUILT-IN tryNoAiRoutine — BEFORE learned replay.
    //    Justification: built-ins are deterministic AND carry the
    //    ask-first safety gates (YouTube: ask before play; WhatsApp:
    //    ask before send). A learned replay running first could
    //    bypass those gates with a stale sequence. Built-ins are
    //    also faster than replay. If tryNoAiRoutine returns true,
    //    return — no learned replay, no LLM loop.
    if (tryNoAiRoutine(lower, trimmed)) return

    // 4. LEARNED ROUTINE replay — only for phrases no built-in covers.
    val learned = learnedRoutineStore.findRoutine(trimmed)
    if (learned != null) {
        activeTaskJob?.cancel()
        activeTaskJob = scope.launch { replayLearnedRoutine(learned, trimmed) }
        return
    }

    // 5. LLM loop — the fallback for everything else, and the
    //    fallback when replay aborts (see §3). Pass memoryContext
    //    in so the LLM plans with recalled context, not from zero.
    activeTaskJob?.cancel()
    activeTaskJob = scope.launch { runAutonomousLoop(trimmed, memoryContext) }
}
```

**Order summary:** stop → memory recall (read-only) → user custom
routine → built-in no-AI routine → learned routine replay → LLM
loop. Explicit user instruction beats deterministic built-in, which
beats learned guess, which beats fresh LLM planning.

### §2. Passing memory context into the loop + indexing finished turns

- `runAutonomousLoop` gains one parameter, e.g.
  `memoryContext: List<String> = emptyList()`, and prepends it to
  the `recentChat` list already passed to `llmClient.decideNextStep`
  (e.g. as `"Memory: <one-line summary>"` lines, max 5). No
  `LlmClient` signature change is needed if the coordinator folds
  them into `recentChat`; a dedicated parameter is cleaner but
  optional — either way, summaries are one line each, never full
  turn text.
- On loop completion (`decision.isCompleted`, the existing return
  point in `runAutonomousLoop`): call
  `memoryIndex.indexTurn(id, ChatSummary.summarize(currentGoal, outcome))`
  where `id` is a stable per-turn id (e.g. the completion timestamp
  string) and `outcome` is the final reply / action summary. Chat-only
  turns (`action == "NONE"`, no app action executed) SHOULD still be
  indexed — they are conversation memory — but they are NEVER
  recorded as routines (see §4).
- `MemoryIndex.search` is O(query keywords + matched postings +
  matched log matched) — index lookup + top-k sort only, never a
  scan of all turns. `rebuildIfNeeded()` is a cheap integrity check
  that rebuilds only on drift, from stored per-turn keywords.

---

## 2. Recording: where `executeDecisionAction` records each verified step

Recording is **opt-in per task completion, not on every tap**:

- Default OFF. The coordinator enables recording only for a
  multi-step LLM-loop task: set a flag (e.g. `recordingLearned =
  true` + `learnedRoutineStore.clearPending()`) when
  `runAutonomousLoop` starts AND at least one real action
  (`action != "NONE"`) is about to execute. Chat-only turns,
  custom-routine paths, and built-in no-AI paths NEVER enable it —
  those paths already have their own deterministic behaviour, and
  recording them would duplicate paths and risk double-execution.
- Inside `executeDecisionAction`, record **only after the branch
  reports success** (`ok == true`). The cleanest coordinator change
  is to record at the end of each branch, next to its existing
  success value, e.g. (schematic, not a paste-in patch):

```
"OPEN_APP" -> {
    val ok = acc?.launchApp(d.appName) ?: false
    if (ok && recordingLearned)
        learnedRoutineStore.recordStep("OPEN_APP", d.appName)
    ...
}
```

- At the loop's completion point, call exactly once:
  `learnedRoutineStore.finishRoutine(originalGoal, verified = decision.isCompleted)`.
  `finishRoutine` persists ONLY when `verified == true` AND at least
  one step was buffered; a failed / max-steps / cancelled run returns
  null and the buffer is discarded. Never call `finishRoutine` with
  `verified = true` on the max-steps path, the error path, or the
  user-no-reply path — those are unverified by definition.
- On cancellation (`cancelActiveTask`) or a new command, call
  `learnedRoutineStore.clearPending()` so a killed task's partial
  steps can never leak into the next task's routine.

### Step → record mapping (target / input rules)

| LLM action | `recordStep(action, target, input)` | Notes |
|---|---|---|
| `OPEN_APP` | target = `appName` (app name/package) | Never user text. |
| `YOUTUBE_SEARCH` | target = `"youtube"`, input = search query | Query is the one input class allowed (see §5). |
| `YOUTUBE_PLAY` | target = `"youtube"`, input = query | Same as above. |
| `TAP_NODE` | target = element's **contentDesc, else view id** | Resolve from the fresh snapshot element matching `nodeId` at execution time. If neither is resolvable, **skip the step** — do not record a bare session-local Int id (it will not match next run) and never record element text that could be message content. |
| `TAP_XY` / `LONG_PRESS_XY` | **Do not record** | Raw coordinates do not survive layout/screen change; replay verification would (correctly) abort. Re-plan via LLM instead. |
| `TYPE_TEXT` | target = node contentDesc (or `"input"`), input = text **only if this step is a search query** | If the text is a message, password, or anything else, **skip the step**. When in doubt, skip. |
| `SCROLL` | target = `direction` | |
| `NAVIGATE` | target = `navTarget` | |
| `MEDIA_CONTROL` | target = control text (`PLAY_PAUSE`/`NEXT`/…) | |
| `FLASHLIGHT` | target = `enableFlag.toString()` | |
| `VOLUME` | target = `volumePercent.toString()` | |
| `SET_ALARM` | target = `"HH:MM"` | Never a label containing personal text. |
| `WAIT` | target = `""`, waitMs = step delay | |
| `SAVE_MEMORY` | target = `""` | Local only; replay re-adds, and `addMemory` dedupes identical notes, so replay is safe. |
| `CREATE_PROJECT` / `WRITE_PROJECT_FILE` | target = project name / `"project/file"` | File CONTENT is never recorded (see §5). |
| `OPEN_URL` | **Do not record** | URLs carry identifiers by nature. |
| `WHATSAPP_SEND` / `REPLY_NOTIFICATION` | target = `""`, input = null (**skeleton step**) | Contact + message are NEVER stored. Replay reaches this step, asks the user, and collects fresh recipient/message live (see §3). |
| `CALL` | target = `""`, input = null (**skeleton step**) | Contact/number NEVER stored. Replay treats an empty-target `CALL` as a verification mismatch and aborts to the LLM loop (see §3 table). |
| `COPY_CLIPBOARD` | **Do not record** | Clipboard content is private by definition. |

`recordStep()` itself rejects email-like, phone-like, and
identifier-bearing-URL values and over-long values (backstop only —
the table above is the real rule; the pattern check cannot catch
every message body, so the engine must still only pass safe values).

---

## 3. Replay loop: execute, verify each step, abort to LLM on ANY mismatch

Schematic the coordinator implements (suspend, on the engine scope):

```
suspend fun replayLearnedRoutine(routine: LearnedRoutine, originalCommand: String) {
    val acc = JarvisAccessibilityService.instance
    if (acc == null) {
        // Never claim success, never silently stop.
        runAutonomousLoop("$originalCommand (Learned replay unavailable: accessibility off)")
        return
    }
    for (step in routine.steps) {
        // (a) REPLAY SAFETY — confirmation first (see §4).
        if (learnedRoutineStore.isConfirmationRequired(step.action, step.target)) {
            onSpeakAndAwait(confirmationPromptFor(step))   // fresh ask, every time
            val reply = onRequestUserVoiceReply()?.lowercase() ?: ""
            if (!reply.contains("haan") && !reply.contains("yes")) {
                runAutonomousLoop("$originalCommand (Learned replay stopped: user did not confirm ${step.action})")
                return
            }
            // For WHATSAPP_SEND / REPLY_NOTIFICATION skeletons, collect
            // FRESH recipient + message here by voice before executing.
            // Stored values are always empty — there is nothing to reuse.
        }
        // (b) OFFLINE gate (see §6) — before the network step runs.
        if (learnedRoutineStore.isNetworkDependent(step.action) && !isOnline()) {
            onSpeakAndAwait("Internet nahi hai, isliye ye step abhi nahi ho sakta.")
            runAutonomousLoop("$originalCommand (Learned replay paused: offline at ${step.action})")
            return
        }
        // (c) Execute via the EXISTING accessibility actions only
        //     (same acc.* calls executeDecisionAction uses — no new
        //     execution path, no LLM call inside replay).
        val verified = executeReplayStep(step, acc)
        if (!verified) {
            // ANY mismatch aborts replay. No partial-success claim.
            JarvisStateRepository.addLog("Learned replay aborted at ${step.action} — falling back to LLM loop")
            runAutonomousLoop("$originalCommand (Learned replay failed at ${step.action} — continuing fresh)")
            return
        }
        delay(step.waitMs.coerceIn(0L, 10_000L))
    }
    // ALL steps verified: only now may Jarvis say the task is done.
    // Replay does NOT re-record (no finishRoutine call here).
    JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Task Completed — Ready for next command")
    JarvisStateRepository.setCurrentGoal("")
}
```

**Fallback discipline:** the LLM loop re-observes the live screen
and plans from the CURRENT state; it does not blindly re-execute
already-verified replay steps. Steps verified before the abort stay
done; the note in the goal string tells the LLM what failed, so it
does not repeat the failing step verbatim without re-observing.

### Verification per action type (what counts as "verified")

Verification is checked against a FRESH snapshot
(`acc.captureScreenSnapshot()`) taken after the step, not against
the pre-step screen. A service call returning `false` is always a
mismatch. `acc == null` at any point is a mismatch.

| Step action | Verified iff … |
|---|---|
| `OPEN_APP` | `launchApp` returned true AND fresh snapshot `packageName` is the target app (package/name match). |
| `YOUTUBE_SEARCH` | `searchYouTube` returned true AND foreground package is YouTube. |
| `YOUTUBE_PLAY` | Search opened AND `findFirstYouTubeVideo()` returned a node AND `tapNode` returned true. |
| `TAP_NODE` | Target view id / contentDesc is present in the fresh snapshot BEFORE the tap AND `tapNode` returned true. Absent target = mismatch, abort. |
| `TYPE_TEXT` | Target node present AND `typeText` returned true. (Search-query steps only; see §2.) |
| `SCROLL` | `scrollScreen` returned true. (Same-screen after a scroll at list end is NOT a mismatch — the return value is the check.) |
| `NAVIGATE` | `performSystemNavigation` returned true. |
| `MEDIA_CONTROL` / `FLASHLIGHT` / `VOLUME` / `SET_ALARM` | The service call returned true. |
| `WAIT` | The delay elapsed. Always passes. |
| `SAVE_MEMORY` | `addMemory` completed (local, deduped). |
| `CREATE_PROJECT` / `WRITE_PROJECT_FILE` | `ProjectStore` call completed and the project/file is present. |
| `WHATSAPP_SEND` / `REPLY_NOTIFICATION` | User confirmed fresh (see §4) AND fresh recipient/message were collected live AND the service call returned true. A stored value is never used — skeletons carry none. |
| `CALL` | **Never verified from a skeleton:** empty target = mismatch → abort to LLM loop, which resolves the contact fresh. |
| Any step with `acc == null`, a `false` service return, or a missing target view | Mismatch → abort replay → LLM loop. No exception to this rule. |

---

## 4. REPLAY SAFETY (hard rule) — send / payment / delete / purchase

**A learned-routine replay must NEVER auto-execute a send, payment,
delete, or purchase step. Those steps always ask the user to confirm
first, even inside a learned routine.** This is implemented in
`LearnedRoutineStore.isConfirmationRequired(action, target)`, which
the replay loop MUST call before EVERY step (§3a). A step the loop
fails to check is a bug, not a shortcut.

**Exact step types covered** (action-name match, case-insensitive):
- **Send:** `WHATSAPP_SEND`, `REPLY_NOTIFICATION`, `SEND`, `SEND_MESSAGE`.
- **Payment / purchase:** `PAYMENT`, `PAY`, `PURCHASE`, `BUY`.
- **Delete:** `DELETE`, `DELETE_FILE`, `REMOVE`.
- **Tap backstop:** `TAP_NODE` whose target (view id / contentDesc)
  contains `send`, `pay`, `buy`, `purchase`, `checkout`, `delete`,
  `remove`, `confirm order`, or `place order` — because the engine
  has no dedicated PAY / DELETE action, those go through `TAP_NODE`.
  The keyword list is a backstop, not a guarantee: the coordinator
  must ALSO treat any live screen showing a send / payment / delete
  confirmation as confirmation-required, regardless of keywords.

**Confirmation semantics:**
- Ask every time, in plain words naming the action ("Ye message
  bhejun?", "Ye delete kar dun?"). A previous confirmation — in
  this routine, this session, or the original task — never carries
  over.
- Execute ONLY on an explicit yes (`haan` / `yes` family, same
  vocabulary as the existing YouTube/WhatsApp ask-first paths).
- On no, on silence, or on an unclear reply: abort replay into
  `runAutonomousLoop` with a note that confirmation was not given.
  Never skip the step and continue, never proceed without an answer,
  never claim the send/payment/delete happened.
- For send skeletons, confirmation alone is not enough: the user
  must also supply the recipient and message fresh (voice), because
  nothing was stored. A confirmed send with no fresh recipient still
  aborts to the LLM loop.
- **Not covered** (safe to replay without asking): `OPEN_APP`,
  `YOUTUBE_SEARCH`, `YOUTUBE_PLAY`, `TAP_NODE` on ordinary controls,
  `TYPE_TEXT` of a repeatable search query, `SCROLL`, `NAVIGATE`,
  `MEDIA_CONTROL`, `FLASHLIGHT`, `VOLUME`, `SET_ALARM`, `WAIT`,
  `SAVE_MEMORY`, `CREATE_PROJECT`, `WRITE_PROJECT_FILE`. (`CALL`
  is not in the confirmation set; its skeleton has an empty target,
  so replay aborts on it by the verification rule instead — see §3.)

---

## 5. Privacy boundary — what is NEVER recorded

This list is absolute. It binds the engine (caller), not just the
store's pattern backstop. If a value falls in this list, the step is
skipped (or stored as an empty-target skeleton for sends, per §2) —
it is never written to `LearnedRoutineStore`, `MemoryIndex`, or any
routine JSON.

NEVER record:
- Contact names, contact numbers, or any phone number.
- Message bodies — WhatsApp/SMS/chat text, in `target` OR `input`.
- Passwords, OTPs, PINs, tokens, API keys, or any credential.
- URLs containing identifiers: query strings, ids, tokens, session
  parameters, deep links with user ids. (`OPEN_URL` is never recorded.)
- File contents (project file bodies, documents, pasted text).
- Clipboard contents.
- Notification titles/bodies or notification sender text.
- Payment details: amounts, card/bank/UPI details, order ids.
- Addresses, emails, or any other personal identifier.
- Photos, screenshots, or screen text beyond the target view's id /
  content description needed to find the control again.
- The raw user message as a step `target`. (The routine's own
  `trigger` is the normalized command phrase — that is the lookup
  key, not a step target — and it must still never contain a phone
  number, OTP, or password; if it does, do not record the routine.)

ALLOWED, narrowly:
- `target`: view id, content description, app package/name, scroll
  direction, navigation target, control name, alarm time.
- `input`: ONLY a non-sensitive input the user explicitly asked to
  repeat — in practice, a search query (YouTube/search). Nothing
  else goes in `input`.

---

## 6. OFFLINE MODE (routines part)

Learned routines and local state work offline by construction:
`LearnedRoutineStore` and `MemoryIndex` are `SharedPreferences` +
JSON on the phone — no server, no network call. `findRoutine`,
`allRoutines`, `deleteRoutine`, `indexTurn`, and `search` all work
with no connectivity, and routine recording (buffering +
`finishRoutine`) works offline too, since verification is local
(accessibility + foreground-package checks).

**Replay behaviour with no network** (coordinator adds one
connectivity check, e.g. `ConnectivityManager.activeNetwork != null`,
as `isOnline()` — S5 did not add it, to stay inside file ownership):
1. **Local steps proceed offline, unchanged:** `OPEN_APP`,
   `TAP_NODE`, `SCROLL`, `NAVIGATE`, `MEDIA_CONTROL`, `FLASHLIGHT`,
   `VOLUME`, `SET_ALARM`, `WAIT`, `SAVE_MEMORY`, `CREATE_PROJECT`,
   `WRITE_PROJECT_FILE`. These are executed and verified exactly as
   in §3.
2. **Network steps** (`LearnedRoutineStore.isNetworkDependent`:
   `YOUTUBE_SEARCH`, `YOUTUBE_PLAY`, `OPEN_URL`, `WHATSAPP_SEND`,
   `REPLY_NOTIFICATION`) **do not silently fail and are not
   silently skipped.** On reaching the first network step while
   offline, the replay loop speaks + logs a warning
   ("Internet nahi hai, isliye ye step abhi nahi ho sakta"),
   stops replay, and hands over to `runAutonomousLoop` with an
   explicit offline note (§3b). It never marks the routine
   complete, never claims the step happened, and never records a
   partial replay as a new routine.
3. **Warn / queue, with one hard exception:** when connectivity
   returns, the LLM loop may re-plan and retry the pending
   idempotent read/search steps (`YOUTUBE_SEARCH`, `YOUTUBE_PLAY`,
   `OPEN_URL`) — that retry IS the queue. **Send / payment /
   delete steps are NEVER queued for later:** a queued send that
   fires after reconnect, without the user present, would violate
   §4. After reconnect, sends require a fresh live confirmation
   and fresh recipient/message, via the normal ask-first path.
4. **Memory recall stays available offline:** `MemoryIndex.search`
   results are still passed to the LLM loop as context, so even an
   offline fallback plans with memory instead of from zero. Only
   the LLM call itself needs network — if that too is unavailable,
   the engine says so plainly (no fake completion) rather than
   pretending the task ran.

---

## 7. Coordinator checklist before claiming this done

- [ ] Order in §1 applied verbatim; single execution path per command.
- [ ] Replay exists only in `handleUserCommand`, never in the loop.
- [ ] `isConfirmationRequired` called before EVERY replay step (§4).
- [ ] Every replay step verified per §3 table; any mismatch aborts
      to `runAutonomousLoop` with a note; no silent stop, no
      unverified success claim.
- [ ] Recording only for verified multi-step LLM completions (§2);
      `finishRoutine(verified = false)` on every failure path;
      `clearPending()` on cancel / new command.
- [ ] Never-record list (§5) audited against every `recordStep` call.
- [ ] Offline behaviour (§6): local steps run, network steps warn
      and hand over, sends never queued.
- [ ] No version-number change, no push from this worker (coordinator
      owns integration + build + push).
