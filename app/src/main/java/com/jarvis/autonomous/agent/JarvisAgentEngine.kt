package com.jarvis.autonomous.agent

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.provider.Settings
import android.net.NetworkCapabilities
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.data.AiProvider
import com.jarvis.autonomous.data.ChatSummary
import com.jarvis.autonomous.data.FilePatternStorage
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.data.DocContextPrompt
import com.jarvis.autonomous.data.DocumentMemoryStore
import com.jarvis.autonomous.data.DocumentSearch
import com.jarvis.autonomous.data.DocumentText
import com.jarvis.autonomous.data.FileDocumentStorage
import com.jarvis.autonomous.data.LearnedInstructionMeta
import com.jarvis.autonomous.data.LearnedPattern
import com.jarvis.autonomous.data.LearnedPatternMeta
import com.jarvis.autonomous.data.LearnedPatternStep
import com.jarvis.autonomous.data.LearnedPatternStore
import com.jarvis.autonomous.data.LearnedRoutine
import com.jarvis.autonomous.data.LearnedRoutineStore
import com.jarvis.autonomous.data.LearnedStep
import com.jarvis.autonomous.data.MemoryIndex
import com.jarvis.autonomous.data.MemoryKeywords
import com.jarvis.autonomous.data.ProjectPreviewServer
import com.jarvis.autonomous.data.ProjectStore
import com.jarvis.autonomous.data.RoutineNormalization
import com.jarvis.autonomous.data.SaveResult
import com.jarvis.autonomous.data.SelfExtensionStore
import com.jarvis.autonomous.service.JarvisAccessibilityService
import com.jarvis.autonomous.service.JarvisNotificationService
import com.jarvis.autonomous.service.ScreenSnapshot
import com.jarvis.autonomous.ui.BrowserBrowseStatus
import com.jarvis.autonomous.ui.JarvisBackgroundBrowser
import com.jarvis.autonomous.voice.TurnDetector
import com.jarvis.autonomous.ui.LICENCE_SERVER_URL
import com.jarvis.autonomous.ui.LicencePrefs
import com.jarvis.autonomous.ui.browserTargetForInput
import com.jarvis.autonomous.ui.normalizeBrowserUrl
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class JarvisAgentEngine(
    private val context: Context,
    private val prefs: JarvisPreferences,
    // B-M8 (1.4.1): Boolean = spoken aloud (true) vs TTS failed /
    // unavailable (false). Callers inside this file go through
    // speak() below, which falls back to a visible chat prompt on
    // false instead of waiting for an answer to an unspoken question.
    // NOTE (coordinator-owned): JarvisForegroundService's lambda
    // must return Boolean after this change — it is NOT edited here.
    private val onSpeakAndAwait: suspend (String, String) -> Boolean,
    private val onRequestUserVoiceReply: suspend () -> String?,
    private val onShowBrowserPage: ((String) -> Unit)? = null,
    // B-M3 (1.4.1, coordinator-wired): true when the Browser tab is
    // currently visible. Null = host gives no visibility signal, in
    // which case background browse proceeds as before (see
    // executeBrowserLoad) — the coordinator must wire this for the
    // full no-hijack guarantee.
    private val isBrowserTabVisible: (() -> Boolean)? = null,
    /**
     * 1.4.5 (item 5): voice "stop" commands actually stop speech.
     * Optional (default null) so existing callers keep compiling;
     * the service wires it to VoiceManager.stopSpeaking.
     */
    private val onStopSpeaking: (() -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val llmClient = LlmClient(context, prefs)
    private val learnedRoutineStore = LearnedRoutineStore(context)
    private val memoryIndex = MemoryIndex(context)
    // I1 (1.7.1): on-device document memory — file-backed, offline,
    // never uploaded (see data/DocumentMemory.kt). Owned here like
    // memoryIndex above; the UI holds its own instance over the same
    // files (the store keeps no in-memory cache, so both see the
    // same data).
    private val documentMemory = DocumentMemoryStore(FileDocumentStorage(context))
    // 1.4.21 Agent Mode: planner + executor, owned by the engine
    // (same ownership shape as llmClient above). Lazy so the
    // executor receives a fully-constructed engine.
    private val planExecutor: AgentPlanExecutor by lazy { AgentPlanExecutor(this) }
    private var activeTaskJob: Job? = null
    // 1.8.0 Talker/Executor (DESIGN.md: expansion/talker-executor):
    // the bus is the ONLY handoff between the Talker (fast replies)
    // and the Executor (loop + plans). activeExecutorCommand is the
    // command currently bound to activeTaskJob, or null when no
    // Executor work runs — every reporting call no-ops on null, so
    // non-Executor paths (learned replay, quick-path fallback) can
    // never produce a phantom result. executorTimedOut remembers a
    // TIMEOUT delivery so the job-completion safety net does not
    // overwrite it with a FAILED net when the job later finishes.
    // speechSuppressedResultIds / unannouncedResultIds hold command
    // ids whose outcome the Executor ALREADY spoke (chat-only) or
    // already fully announced in chat AND speech (bus record only) —
    // the listener then never says the same outcome twice.
    // executorTurnAlreadyPosted dedupes the "You" chat line: the Talker posts it with the ack, so the
    // Executor path must skip its own post (consumed once).
    private val talkerBus = TalkerExecutorBus()
    private var activeExecutorCommand: ExecutorCommand? = null
    private var executorWatchdogJob: Job? = null
    private var executorTimedOut: Boolean = false
    private val speechSuppressedResultIds = mutableSetOf<String>()
    private val unannouncedResultIds = mutableSetOf<String>()
    private var executorTurnAlreadyPosted: Boolean = false

    // 1.9.0 (v2) Talker/Executor — progress, verification, learning.
    // learnedPatternStore is the NEW on-device pattern/instruction
    // store (DESIGN §5.1): same I1 filesDir/device-memory/ root, new
    // record kind. It is NEVER read by maybeUploadLearnedPattern
    // (CI grep enforces the boundary — see §5.7).
    private val learnedPatternStore = LearnedPatternStore(FilePatternStorage(context))
    // Progress speak/chat throttle state (single job at a time, so
    // one set of counters suffices — DESIGN §3.3).
    private var lastSpokenProgressAtMs: Long = 0L
    private var lastSpokenProgressLabel: String? = null
    private var lastSpokenProgressLabelAtMs: Long = 0L
    private var lastPostedProgressAtMs: Long = 0L
    private var lastPostedProgressLabel: String? = null
    private var lastPostedProgressFraction: Double = -1.0
    // Verification gate state (DESIGN §4.5): a second DONE for a
    // command already being verified is dropped — the first verdict
    // owns the speak. verificationStates carries the command +
    // original result across async fix rounds.
    private val verificationInFlight = mutableSetOf<String>()
    private val verificationStates = mutableMapOf<String, VerificationState>()
    private val fixAttempts = mutableMapOf<String, Int>()
    private var lastDeliveredCommand: ExecutorCommand? = null
    private var lastExecutorWorkKind: ExecutorWorkKind? = null
    private var lastVerifiedLoopRoutine: LearnedRoutine? = null
    // Applied-learning bookkeeping (DESIGN §5.4–§5.5): which pattern
    // rode on which command, so a VERIFIED DONE can reinforce it and
    // a mid-flight "ye tareeka mat use karo" can penalise it instead.
    private val appliedPatternByCommand = mutableMapOf<String, String>()
    private var lastAppliedPatternId: String? = null
    private var lastAppliedInstructionIds: List<String> = emptyList()
    private val penalisedPatternIds = mutableSetOf<String>()
    private val stalledDiagnosticLogged = mutableSetOf<String>()
    // Loop milestone state (DESIGN §3.2): action-kind changes, every
    // 3rd verified step, or a > 60 s step. Reset per loop run.
    private var loopLastActionKind: String = ""
    private var loopVerifiedSteps: Int = 0
    // Pending "kaun sa bhool jaun?" number selection (DESIGN §5.8).
    private var pendingForgetSelection: List<Pair<String, String>>? = null

    /** Verification context held across async fix rounds. */
    private data class VerificationState(
        val command: ExecutorCommand,
        val result: ExecutorResult,
        val fixFailure: String = "",
        val fixStepLabel: String = ""
    )

    /** Guidance found for one EXECUTE turn (DESIGN §5.3–§5.4). */
    private data class Guidance(
        val patternId: String?,
        val patternTitle: String?,
        val patternHint: String,
        val instructionHint: String,
        val instructionIds: List<String>,
        val noteLines: List<String>,
        val params: Map<String, String>
    )

    init {
        // The bus listener is the Talker's result voice: every
        // delivered ExecutorResult becomes one chat line + (unless
        // the Executor already spoke this outcome) one spoken line.
        // The listener itself never blocks — it only launches.
        talkerBus.addListener { result ->
            scope.launch {
                if (result.status == ExecutorResultStatus.DONE) {
                    // v2: DONE claims go through the verification
                    // gate — the Talker re-reads the artifact before
                    // anyone says "ho gaya" (DESIGN §4.5).
                    deliverVerifiedExecutorResult(result)
                } else {
                    deliverExecutorResult(result)
                }
            }
        }
        // v2: progress milestones (throttled speak/chat) and the
        // fix pair. Listeners never block — they only launch.
        talkerBus.addProgressListener { progress ->
            scope.launch { deliverProgress(progress) }
        }
        talkerBus.addFixRequestListener { request ->
            scope.launch { handleFixRequest(request) }
        }
        talkerBus.addFixListener { fixResult ->
            scope.launch { handleFixResult(fixResult) }
        }
    }
    // 1.4.14 (D1): announce dedupe — in-memory only, dies with
    // process death, never persisted. Key = trimmed sender +
    // trimmed text of the notification; value = elapsedRealtime
    // ms when that announce actually happened. Pruned on every
    // check (no timer, no handler); expired keys are dropped,
    // never consulted.
    private val recentAnnounces = mutableMapOf<String, Long>()
    // 1.15.0 memory-loss fix (F2): the engine keeps NO chatRestored
    // flag any more — the exactly-once guard lives in
    // JarvisStateRepository (AtomicBoolean); see ensureChatRestored().

    // 1.4.0 browser approval state (see Browser §7): a URL Jarvis
    // proposed is only loaded after the user's immediately following
    // affirmative reply. Pending approval is consumed by the very
    // next command — anything else clears it, never reuses it.
    private var pendingBrowserApprovalUrl: String? = null
    private var pendingBrowserShowPage: Boolean = false
    private val approvedBrowserUrls = mutableSetOf<String>()
    private var interruptedBrowseChecked = false
    private var loopUserCommand: String = ""
    // A-C2/A-C5/B-M9 task-scoped state: confirmed sensitive actions
    // live only for the current attempt and expire at task end.
    private val confirmedSensitiveKeys = mutableSetOf<String>()
    // B-M3: background loads deferred because the Browser tab was visible.
    private val deferredBrowseUrls = mutableListOf<String>()
    // A-C4: an OPEN_URL awaiting approval must reopen externally on
    // yes, not via the background-browser path.
    private var pendingBrowserOpenExternal: Boolean = false
    private var lastBuildWritten: Boolean = false
    private var lastExecutedAction: String = ""

    /**
     * 1.4.18: answered clarifications, ENGINE-LIFETIME (capped
     * last 12, "Q -> A" strings) — deliberately NOT cleared by
     * clearTaskScopedState: surviving across tasks is the point,
     * so the model never re-asks an answered question in a later
     * task. In-task currentGoal accumulation stays as-is.
     */
    private val answeredClarifications = mutableListOf<String>()

    /** URLs deferred by the B-M3 visibility check (never auto-loaded). */

    /** Clears ALL per-attempt state (A-C7): routine buffer, browser
     *  approvals and sensitive confirmations. Called at every task
     *  start and every termination path, so nothing leaks across
     *  attempts. */
    private fun clearTaskScopedState() {
        learnedRoutineStore.clearPending()
        approvedBrowserUrls.clear()
        confirmedSensitiveKeys.clear()
    }

    // Last build validation, set by the CREATE/WRITE branches so
    // the loop can repair (§5c) or summarise (§6) without a second
    // validation implementation.
    private var lastBuildValidation: ProjectStore.ProjectValidation? = null
    private var lastBuildProject: String = ""

    // 1.4.23-PRO Item 2 (C1): outcome of the most recent
    // executeBrowserLoad call — the OPEN_BROWSER branch reads it
    // AFTER the load returns, so the ActionResult carries the
    // load's own success instead of a stale value. Same side-effect
    // pattern as lastBuildWritten above.
    private var lastBrowserLoadOk: Boolean = false

    private fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (e: Exception) {
            // A-M7: a connectivity-check throw must NEVER assume
            // online — a failed check means "unknown", and the safe
            // reading is offline (pause, warn, do not complete).
            JarvisStateRepository.addLog("isOnline check failed (${e.message}) — treating as offline.")
            false
        }
    }

    // ---- 1.4.1 safety helpers (A-C1 negation, B-M8 speak fallback) ----

    private val NEGATION_TOKENS = setOf(
        "nahi", "nahin", "nhi", "no", "never", "not", "mat", "mt",
        "dont", "don't", "na", "kabhi"
    )

    /** Lowercase + punctuation-stripped tokens ("don't" -> "dont"). */
    private fun replyTokens(reply: String): List<String> =
        reply.lowercase().replace(Regex("[^a-z ]"), " ")
            .split(" ").filter { it.isNotBlank() }

    /** True when the reply contains ANY negation token. A negated
     *  reply must NEVER confirm (A-C1) — checked before every
     *  affirmative test in this file. */
    private fun containsNegation(reply: String): Boolean =
        replyTokens(reply).any { it in NEGATION_TOKENS }

    private fun isDeclineReply(reply: String): Boolean {
        val lower = reply.lowercase().trim()
        if (containsNegation(reply)) return true
        return lower in setOf(
            "rehne do", "rehne de", "rehene do", "chhodo", "chhod do",
            "cancel", "band karo", "ruk ja", "ruko", "bas kar"
        )
    }

    /** Negation-aware yes: affirmative words AND no negation token.
     *  "mat bhejo" contains "bhej" but is a NO, never a yes. */
    private fun isYesReply(reply: String): Boolean {
        if (reply.isBlank() || containsNegation(reply)) return false
        val lower = reply.lowercase()
        return lower.contains("haan") || lower.contains("yes") ||
            lower.contains("chalao") || lower.contains("play") ||
            lower.contains("bajao") || lower.contains("bhej") ||
            lower.contains("send") || lower.contains("khol do") ||
            lower.contains("dikhao") || lower.trim() == "ok"
    }

    /** B-M8: speak, falling back to a VISIBLE chat prompt when TTS
     *  reports failure. Returns the spoken flag. Origin tags the
     *  SPK_START line (1.4.10 Step 3): engine replies default to
     *  "reply"; announces pass their own origin so the next
     *  report can tell a reply from an announcement. */
    private suspend fun speak(
        text: String,
        origin: String = "reply",
        alreadyPostedToChat: Boolean = false
    ): Boolean {
        val spoken = onSpeakAndAwait(text, origin)
        // G8 F2: callers that already posted this exact line to chat
        // before calling speak() must not get a duplicate line on
        // TTS failure — the failure re-post exists only for callers
        // that did NOT pre-post (greeting / askUserVoice paths).
        if (!spoken && !alreadyPostedToChat) {
            JarvisStateRepository.addChatMessage("Jarvis", text)
        }
        return spoken
    }

    /** Ask a question and await a voice reply — but NEVER wait for an
     *  answer to a question that was not spoken (B-M8): on TTS
     *  failure the chat prompt above is the fallback and null is
     *  returned immediately. */
    private suspend fun askUserVoice(prompt: String): String? {
        if (!speak(prompt)) return null
        return onRequestUserVoiceReply()
    }

    private fun isAffirmativeReply(lower: String): Boolean {
        // A-C1: any negation token vetoes, even next to a yes-word
        // ("haan nahi", "yes mat karo" are NOT approvals).
        if (containsNegation(lower)) return false
        // Deliberately narrow: a bare "khol" must NOT count, or a
        // fresh command like "youtube kholo" would be misread as
        // approval for a pending page. Only standalone yes-words
        // and explicit "khol do / dikhao" answers approve.
        val t = lower.trim()
        return t == "haan" || t == "yes" || t == "ok" ||
            t.startsWith("haan ") || t.startsWith("yes ") ||
            t.contains("khol do") || t.contains("dikhao")
    }

    /**
     * Browser §7 approval gate: ALLOW only when the URL was named
     * in the user's own current command (user-asked), or was
     * previously approved after Jarvis proposed it (user-approved
     * proposal). Everything else must ask first, never load.
     */
    private fun browserApprovalKind(url: String): String? {
        if (approvedBrowserUrls.contains(url)) return "user-approved proposal"
        val cmd = loopUserCommand.lowercase()
        if (cmd.isBlank()) return null
        val host = url.substringAfter("://", url).substringBefore("/")
        val hostNoWww = host.removePrefix("www.")
        val firstLabel = hostNoWww.substringBefore(".")
        // B-M9: full URL or explicitly user-named host only. A bare
        // host label counts only as a WHOLE word in the command
        // ("news" must not match "news18.com" via substring).
        if (cmd.contains(url.lowercase()) || cmd.contains(hostNoWww)) return "user-asked"
        val cmdTokens = cmd.replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ").filter { it.isNotBlank() }
        if (firstLabel.length >= 4 && cmdTokens.contains(firstLabel)) return "user-asked"
        return null
    }

    // ---- A-C2 hard pre-execution gate (code, never prompt text) ----

    // 1.4.21: internal (was private) so the Agent Mode executor can
    // route needsGate plan steps through this SAME gate — no second
    // gate implementation exists.
    internal enum class GateOutcome { ALLOW, BLOCK_STEP, PAUSE_TASK, ABORT_TASK }

    // 1.4.23-PRO Item 2 (C1): the step return type. `ok` is the
    // action's own success signal (the accessibility call's
    // Boolean, the build validation, the write result) — the loop
    // may only exit DONE when the evidence check on this result
    // passes. `summary` is the human/log line (what the old
    // String return carried). planExecuteDecision() still exposes
    // the summary String so AgentPlanExecutor is untouched.
    data class ActionResult(val ok: Boolean, val summary: String)

    // 1.4.23-PRO Item 2 — evidence verdicts. VERIFIED = the
    // action's evidence passed, the loop may exit DONE. FAILED =
    // the action definitely did not achieve its outcome: stop the
    // task with the fixed failure line (no routine, no memory
    // index, no "Task Completed"). AMBIGUOUS = evidence could
    // neither pass nor fail (adopted default, veto open): continue
    // the loop / ask, never fail outright, never report success.
    private enum class StepEvidence { VERIFIED, FAILED, AMBIGUOUS }

    private val PAY_SEND_KEYWORDS = listOf(
        "pay", "payment", "upi", "checkout", "place order", "confirm order",
        "buy now", "send", "bhej"
    )

    /**
     * Runs BEFORE any decision action executes. Validation blocks
     * (A-H1) reject bad steps without executing them; sensitive
     * actions (send / reply / call / pay-like tap / type) require an
     * explicit, negation-aware confirmation in code (A-C2) unless
     * already confirmed for this task attempt, or the user turned
     * confirmations off in settings. A decline ABORTS the task
     * (A-C5); an unspoken question pauses it (B-M8).
     */
    private suspend fun checkExecutionGate(
        d: AgentDecision,
        snapshotHasPayment: Boolean
    ): Pair<GateOutcome, String> {
        // A-H1 validation blocks (engine side; LlmClient parses too).
        when (d.action) {
            "TAP_NODE" -> if (d.nodeId <= 0) return GateOutcome.BLOCK_STEP to "TAP_NODE nodeId missing"
            "TAP_XY", "LONG_PRESS_XY" -> {
                val metrics = context.resources.displayMetrics
                if (d.x < 0 || d.y < 0 || d.x > metrics.widthPixels || d.y > metrics.heightPixels) {
                    return GateOutcome.BLOCK_STEP to
                        "Coordinates (${d.x}, ${d.y}) out of screen range — rejected, never coerced"
                }
            }
            "VOLUME" -> if (d.volumePercent !in 0..100) return GateOutcome.BLOCK_STEP to "VOLUME out of range"
            "SET_ALARM" -> if (d.alarmHour !in 0..23 || d.alarmMinute !in 0..59) {
                return GateOutcome.BLOCK_STEP to "SET_ALARM time out of range"
            }
            "WHATSAPP_SEND", "REPLY_NOTIFICATION" -> {
                if (d.contact.isBlank() || d.text.isBlank()) {
                    return GateOutcome.BLOCK_STEP to "${d.action} contact/text blank"
                }
            }
            "CALL" -> if (d.contact.isBlank()) return GateOutcome.BLOCK_STEP to "CALL contact blank"
            "TYPE_TEXT" -> if (d.text.isBlank()) return GateOutcome.BLOCK_STEP to "TYPE_TEXT blank"
            "OPEN_URL" -> Unit // scheme + approval handled in its branch (A-C4)
        }
        // Sensitive-action confirmation (OPEN_URL excluded here: its
        // branch runs the same browser-approval gate, A-C4).
        val payLike = snapshotHasPayment ||
            PAY_SEND_KEYWORDS.any { (d.text + " " + d.contact).lowercase().contains(it) }
        val isSensitive = d.action in setOf("WHATSAPP_SEND", "REPLY_NOTIFICATION", "CALL") ||
            (payLike && d.action in setOf("TAP_NODE", "TAP_XY", "TYPE_TEXT"))
        if (!isSensitive) return GateOutcome.ALLOW to ""
        val key = "${d.action}:${d.contact.ifBlank { d.text.take(40) }}"
        if (confirmedSensitiveKeys.contains(key)) return GateOutcome.ALLOW to ""
        if (!prefs.confirmSensitiveActions) {
            confirmedSensitiveKeys.add(key)
            return GateOutcome.ALLOW to ""
        }
        val question = when (d.action) {
            "WHATSAPP_SEND" -> "${d.contact} ko ye message bhejna hai: ${d.text}. Bhej dun?"
            "REPLY_NOTIFICATION" -> "${d.contact} ko ye jawab bhejna hai: ${d.text}. Bhej dun?"
            "CALL" -> "${d.contact} ko call lagau?"
            else -> "Ye payment/send wala step hai, kar dun?"
        }
        val reply = askUserVoice(question) ?: return GateOutcome.PAUSE_TASK to "question not spoken"
        if (isAffirmativeReply(reply.lowercase()) || isYesReply(reply)) {
            confirmedSensitiveKeys.add(key)
            return GateOutcome.ALLOW to ""
        }
        return GateOutcome.ABORT_TASK to "user declined"
    }

    private fun persistChat() {
        // 1.15.0 memory-loss fix (F2): restore-first — the Talker fast
        // paths persist BEFORE any loop turn runs, so a persist with no
        // restore yet would overwrite the saved history on disk with
        // just [greeting + new turn]. ensureChatRestored() is a no-op
        // after the first call (repository AtomicBoolean), so calling
        // it here is free and covers all 45 persist sites.
        ensureChatRestored()
        runCatching {
            prefs.saveChatHistory(JarvisStateRepository.chatHistory.value)
        }
    }

    // 1.15.0 memory-loss fix (F2): the once-per-process restore, in one
    // place. Called from persistChat(), handleUserCommand(), and the
    // autonomous loop (harmless fallback). The repository owns the
    // exactly-once flag — the engine keeps no flag of its own any more.
    private fun ensureChatRestored() {
        runCatching {
            JarvisStateRepository.restoreChatHistory(prefs.getSavedChatHistory())
        }
    }

    fun isTaskRunning(): Boolean = activeTaskJob?.isActive == true

    /**
     * 1.8.0: returns true when an Executor command was running and has
     * now been reported CANCELLED (the Talker speaks it via the bus
     * listener) — callers must NOT speak a second stop line then.
     * Returns false when nothing Executor-side was running.
     */
    fun cancelActiveTask(reason: String = "Task stopped by user."): Boolean {
        val hadExecutorCommand = activeExecutorCommand != null
        if (hadExecutorCommand) {
            // Report BEFORE cancelling the job: the job's
            // invokeOnCompletion guard (see launchExecutorCommand)
            // skips a second CANCELLED once the active command is
            // cleared here.
            reportExecutorOutcome(ExecutorResultStatus.CANCELLED, "", "")
        }
        // 1.9.0 (v2): a cancelled job must never be verified or
        // fixed later — drop any verification state for it, so a
        // late DONE cannot speak after the stop line.
        lastDeliveredCommand?.id?.let { clearVerificationState(it) }
        activeTaskJob?.cancel()
        activeTaskJob = null
        clearTaskScopedState()
        // 1.8.0: a job cancelled before its first "You" post must not
        // leave executorTurnAlreadyPosted set — the next turn's "You"
        // line would be wrongly skipped.
        executorTurnAlreadyPosted = false
        JarvisStateRepository.setAgentState(AgentRunState.IDLE, reason)
        JarvisStateRepository.setCurrentGoal("")
        JarvisStateRepository.addLog(reason)
        return hadExecutorCommand
    }

    // ---- 1.8.0 Talker/Executor (DESIGN.md: expansion/talker-executor) ----

    /** The Talker's result voice: one chat line + one spoken line per
     *  delivered result — unless the Executor already announced this
     *  outcome itself. CHAT_ONLY results post to chat but are not
     *  spoken twice; NONE results are recorded on the bus only (the
     *  outcome is already fully in chat AND spoken by the Executor). */
    private suspend fun deliverExecutorResult(result: ExecutorResult) {
        if (unannouncedResultIds.remove(result.commandId)) {
            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Ready for next command")
            return
        }
        val line = TalkerReplyPolicy.resultLine(result)
        JarvisStateRepository.addChatMessage("Jarvis", line)
        persistChat()
        val suppressSpeech = speechSuppressedResultIds.remove(result.commandId)
        if (!suppressSpeech) {
            JarvisStateRepository.setAgentState(AgentRunState.SPEAKING, "Speaking: $line")
            speak(line, alreadyPostedToChat = true)
        }
        // TIMEOUT is advisory — the Executor is STILL running, so the
        // state must not drop to IDLE (the UI would claim "Ready"
        // while work runs). Terminal results end the work: IDLE.
        if (result.status == ExecutorResultStatus.TIMEOUT) {
            JarvisStateRepository.setAgentState(
                AgentRunState.EXECUTING_STEP, "Executor still running — Talker waiting for result"
            )
        } else {
            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Ready for next command")
        }
    }

    // ---- 1.9.0 (v2) Talker/Executor: progress feed (Gap 1) ----

    /**
     * Progress milestone listener: one throttled spoken line and/or
     * one throttled chat line per milestone (DESIGN §3.3–§3.4).
     * Progress never touches the watchdog or the safety net, and a
     * spoken progress line is never also posted as a second chat
     * line with different wording — the chat line is the same text
     * plus the `⏳` marker. After speaking, the state returns to
     * EXECUTING_STEP only while the command is still running, so a
     * terminal result that landed mid-speak is never revived.
     */
    private suspend fun deliverProgress(progress: ExecutorProgress) {
        val cmd = talkerBus.pendingCommand(progress.commandId)
            ?: lastDeliveredCommand?.takeIf { it.id == progress.commandId }
            ?: return
        val now = System.currentTimeMillis()
        val state = JarvisStateRepository.agentState.value
        val gateQuestionOutstanding = state == AgentRunState.WAITING_USER_REPLY
        val isExecutingStep = state == AgentRunState.EXECUTING_STEP
        val nextLabel = progress.nextLabel.takeIf { it.isNotBlank() }
        val line = TalkerReplyPolicy.progressLine(progress, nextLabel)
        val chatAllowed = TalkerProgressPolicy.shouldPostToChat(
            progress, now, lastPostedProgressAtMs, lastPostedProgressLabel, lastPostedProgressFraction
        )
        if (chatAllowed) {
            lastPostedProgressAtMs = now
            lastPostedProgressLabel = progress.stepLabel
            lastPostedProgressFraction = progress.progressFraction
            JarvisStateRepository.addChatMessage(
                "Jarvis", TalkerReplyPolicy.progressChatLine(progress, nextLabel)
            )
            persistChat()
        }
        val speakAllowed = TalkerProgressPolicy.shouldSpeak(
            progress, now, lastSpokenProgressAtMs, lastSpokenProgressLabel,
            lastSpokenProgressLabelAtMs, gateQuestionOutstanding, isExecutingStep, true
        )
        if (speakAllowed) {
            lastSpokenProgressAtMs = now
            lastSpokenProgressLabel = progress.stepLabel
            lastSpokenProgressLabelAtMs = now
            JarvisStateRepository.setAgentState(AgentRunState.SPEAKING, "Speaking: $line")
            speak(line, alreadyPostedToChat = chatAllowed)
            if (talkerBus.isPending(cmd.id) || activeExecutorCommand?.id == cmd.id) {
                JarvisStateRepository.setAgentState(
                    AgentRunState.EXECUTING_STEP, "Executor still running — Talker waiting for result"
                )
            }
        }
    }

    // ---- 1.9.0 (v2) Talker/Executor: verification loop (Gap 2) ----

    /** One ref's re-read, plus the verdict over all refs. */
    private data class VerificationReport(
        val verdict: TalkerVerificationPolicy.VerificationVerdict,
        val results: List<TalkerVerificationPolicy.RefCheckResult>
    )

    /**
     * The verification gate (DESIGN §4.5): every DONE result is held
     * here — never spoken directly — while the Talker re-reads each
     * claimed artifact. Non-DONE results never enter this function
     * (the bus listener routes only DONE here). A DONE the Executor
     * already fully announced (unannounced ids — the NONE chat-end
     * DONE) passes through untouched. A second DONE for a command
     * already being verified is dropped; the first verdict owns the
     * speak. Verification time counts against the command's 15-min
     * budget (Q4): fix attempts are bounded by the remaining time.
     */
    private suspend fun deliverVerifiedExecutorResult(result: ExecutorResult) {
        if (unannouncedResultIds.remove(result.commandId)) {
            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Ready for next command")
            return
        }
        if (result.commandId in verificationInFlight) {
            JarvisStateRepository.addLog(
                "Verification already in flight for ${result.commandId} — second DONE dropped."
            )
            return
        }
        val cmd = lastDeliveredCommand?.takeIf { it.id == result.commandId }
            ?: talkerBus.pendingCommand(result.commandId)
            ?: run {
                // No command context (e.g. a replayed / legacy DONE):
                // fall back to the v1 delivery path rather than
                // dropping the outcome silently.
                deliverExecutorResult(result)
                return
            }
        verificationInFlight.add(result.commandId)
        verificationStates[result.commandId] = VerificationState(cmd, result)
        // Slow-verification chat line (chat only, never spoken —
        // speaking it would be a premature completion claim).
        val slowJob = scope.launch {
            delay(TalkerExecutorProgress.VERIFICATION_SLOW_CHAT_MS)
            if (result.commandId in verificationInFlight) {
                JarvisStateRepository.addChatMessage(
                    "Jarvis", "Ho gaya lagta hai — check kar raha hoon, ek second…"
                )
                persistChat()
            }
        }
        val report = verifyRefs(cmd, EvidenceRefs.parse(result.evidenceJson))
        slowJob.cancel()
        deliverVerdict(result.commandId, report, fixed = false)
    }

    /**
     * Re-read every ref (fresh reads, never the Executor's text,
     * never the `lastBuildValidation` cache). One bounded re-check
     * for UNCHECKABLE refs after 5 s; the whole verification is
     * bounded by 60 s — on timeout, unchecked refs are UNCHECKABLE.
     */
    private suspend fun verifyRefs(
        cmd: ExecutorCommand,
        refs: List<EvidenceRef>
    ): VerificationReport {
        if (refs.isEmpty()) {
            return VerificationReport(
                TalkerVerificationPolicy.VerificationVerdict.UNVERIFIED, emptyList()
            )
        }
        val results = ArrayList<TalkerVerificationPolicy.RefCheckResult>(refs.size)
        val checked = withTimeoutOrNull(TalkerExecutorProgress.VERIFICATION_TIMEOUT_MS) {
            for (ref in refs) {
                var check = checkEvidenceRef(cmd, ref)
                if (check.outcome == TalkerVerificationPolicy.RefOutcome.UNCHECKABLE) {
                    delay(TalkerExecutorProgress.VERIFICATION_RETRY_DELAY_MS)
                    check = checkEvidenceRef(cmd, ref)
                }
                results.add(check)
            }
            true
        }
        if (checked == null) {
            // Timed out mid-verification: the refs never reached are
            // UNCHECKABLE, never a pass and never a fail.
            val done = results.map { it.refId }.toSet()
            for (ref in refs) {
                if (ref.refId() !in done) {
                    results.add(
                        TalkerVerificationPolicy.RefCheckResult(
                            ref.refId(),
                            TalkerVerificationPolicy.RefOutcome.UNCHECKABLE,
                            "verification timeout"
                        )
                    )
                }
            }
        }
        return VerificationReport(TalkerVerificationPolicy.decide(results), results)
    }

    /** Re-read one evidence ref with the existing re-read function for its kind. */
    private suspend fun checkEvidenceRef(
        cmd: ExecutorCommand,
        ref: EvidenceRef
    ): TalkerVerificationPolicy.RefCheckResult {
        val refId = ref.refId()
        return when (ref.kind) {
            EvidenceKind.FILE_LIST -> checkFileListRef(ref)
            EvidenceKind.PREVIEW_HTTP -> checkPreviewHttpRef(ref)
            EvidenceKind.GITHUB_COMMIT -> checkGithubCommitRef(ref)
            EvidenceKind.SUPABASE_TABLES -> checkSupabaseTablesRef(ref)
            EvidenceKind.VERCEL_DEPLOY -> checkVercelDeployRef(ref)
            EvidenceKind.SCREEN_STATE, EvidenceKind.MESSAGE_SENT -> checkScreenRef(ref)
            // 1.11.0-PRO (self-extension, DESIGN §7): SKILL_TEST refs
            // are re-read against persisted state (below), never
            // trusted from the verifier's in-memory claim alone.
            EvidenceKind.SKILL_TEST -> checkSkillTestRef(ref)
        }
    }

    /**
     * 1.11.0-PRO (self-extension, DESIGN §7): re-read a SKILL_TEST
     * ref against persisted state. The ref is "<testId>:step<N>"
     * (testId "selfext-<sig8>") and the verifier marks the skill
     * "verified:<testId>" + enabled only on a passed test. PASSED
     * iff a manifest carries that verification AND is enabled AND
     * the ref itself recorded "ok"; anything else is FAILED (a
     * rolled-back skill leaves no manifest behind).
     */
    private fun checkSkillTestRef(
        ref: EvidenceRef
    ): TalkerVerificationPolicy.RefCheckResult {
        val refId = ref.refId()
        val testId = ref.ref.substringBefore(":")
        val verified = learnedRoutineStore.loadSkillManifests().any { manifest ->
            manifest.origin == "self_extended" &&
                manifest.provenance?.verification == "verified:$testId" &&
                learnedRoutineStore.isSkillEnabled(manifest.id)
        }
        return when {
            ref.value != "ok" -> TalkerVerificationPolicy.RefCheckResult(
                refId, TalkerVerificationPolicy.RefOutcome.FAILED, "skill test step fail hua"
            )
            !verified -> TalkerVerificationPolicy.RefCheckResult(
                refId, TalkerVerificationPolicy.RefOutcome.FAILED, "skill verified nahi mila"
            )
            else -> TalkerVerificationPolicy.RefCheckResult(
                refId, TalkerVerificationPolicy.RefOutcome.PASSED
            )
        }
    }

    private suspend fun checkFileListRef(
        ref: EvidenceRef
    ): TalkerVerificationPolicy.RefCheckResult {
        val refId = ref.refId()
        val project = ref.ref.removePrefix("project:")
        val files = ref.value.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return withContext(Dispatchers.IO) {
            val projects = runCatching { ProjectStore.listProjects(context) }.getOrDefault(emptyList())
            val found = projects.firstOrNull { it.name == project }
                ?: return@withContext TalkerVerificationPolicy.RefCheckResult(
                    refId, TalkerVerificationPolicy.RefOutcome.FAILED, "project nahi mila"
                )
            val validation = runCatching { ProjectStore.validateProject(context, project) }.getOrNull()
            if (validation != null && !validation.ok) {
                val missing = (validation.missingFiles + validation.emptyFiles + validation.invalidRefs)
                    .joinToString(", ").take(100)
                return@withContext TalkerVerificationPolicy.RefCheckResult(
                    refId, TalkerVerificationPolicy.RefOutcome.FAILED,
                    "validation fail: $missing"
                )
            }
            for (file in files) {
                val content = runCatching { ProjectStore.readFile(context, project, file) }.getOrDefault("")
                if (content.isBlank()) {
                    return@withContext TalkerVerificationPolicy.RefCheckResult(
                        refId, TalkerVerificationPolicy.RefOutcome.FAILED, "file nahi mili: $file"
                    )
                }
            }
            // A project with no files at all is not a built project.
            if (found.files.isEmpty()) {
                return@withContext TalkerVerificationPolicy.RefCheckResult(
                    refId, TalkerVerificationPolicy.RefOutcome.FAILED, "project me koi file nahi"
                )
            }
            TalkerVerificationPolicy.RefCheckResult(refId, TalkerVerificationPolicy.RefOutcome.PASSED)
        }
    }

    private suspend fun checkPreviewHttpRef(
        ref: EvidenceRef
    ): TalkerVerificationPolicy.RefCheckResult {
        val refId = ref.refId()
        val url = ref.value.trim()
        if (url.isBlank()) {
            return TalkerVerificationPolicy.RefCheckResult(
                refId, TalkerVerificationPolicy.RefOutcome.UNCHECKABLE, "preview URL nahi diya"
            )
        }
        val (_, ready) = ProjectPreviewServer.ensureRunningReady(context)
        if (!ready) {
            return TalkerVerificationPolicy.RefCheckResult(
                refId, TalkerVerificationPolicy.RefOutcome.UNCHECKABLE, "preview server start nahi hua"
            )
        }
        return when (val code = previewGetStatus(url)) {
            200 -> TalkerVerificationPolicy.RefCheckResult(
                refId, TalkerVerificationPolicy.RefOutcome.PASSED
            )
            -1 -> TalkerVerificationPolicy.RefCheckResult(
                refId, TalkerVerificationPolicy.RefOutcome.UNCHECKABLE, "preview GET fail hua"
            )
            else -> TalkerVerificationPolicy.RefCheckResult(
                refId, TalkerVerificationPolicy.RefOutcome.FAILED, "preview HTTP $code"
            )
        }
    }

    private suspend fun checkGithubCommitRef(
        ref: EvidenceRef
    ): TalkerVerificationPolicy.RefCheckResult {
        val refId = ref.refId()
        val ownerRepo = ref.ref.removePrefix("github:")
        val owner = ownerRepo.substringBefore("/")
        val repo = ownerRepo.substringAfter("/", "")
        val sha = ref.value.trim()
        if (owner.isBlank() || repo.isBlank() || sha.isBlank()) {
            return TalkerVerificationPolicy.RefCheckResult(
                refId, TalkerVerificationPolicy.RefOutcome.UNCHECKABLE, "commit ref adhoora hai"
            )
        }
        val expectedFiles = planExecutor.planBuildProjectFiles()
        return planExecutor.verifyGithubCommitTyped(owner, repo, sha, expectedFiles)
            .let { outcome ->
                TalkerVerificationPolicy.RefCheckResult(
                    refId, outcome,
                    when (outcome) {
                        TalkerVerificationPolicy.RefOutcome.FAILED -> "GitHub par commit nahi mila"
                        TalkerVerificationPolicy.RefOutcome.UNCHECKABLE -> "GitHub re-read nahi ho paya"
                        else -> ""
                    }
                )
            }
    }

    private suspend fun checkSupabaseTablesRef(
        ref: EvidenceRef
    ): TalkerVerificationPolicy.RefCheckResult {
        val refId = ref.refId()
        val projectRef = ref.ref.removePrefix("supabase:")
        val expected = parseTableColumns(ref.value)
        if (projectRef.isBlank() || expected.isEmpty()) {
            return TalkerVerificationPolicy.RefCheckResult(
                refId, TalkerVerificationPolicy.RefOutcome.UNCHECKABLE, "table ref adhoora hai"
            )
        }
        return planExecutor.verifySupabaseTablesTyped(projectRef, expected)
            .let { outcome ->
                TalkerVerificationPolicy.RefCheckResult(
                    refId, outcome,
                    when (outcome) {
                        TalkerVerificationPolicy.RefOutcome.FAILED -> "Supabase tables nahi mile"
                        TalkerVerificationPolicy.RefOutcome.UNCHECKABLE -> "Supabase re-read nahi ho paya"
                        else -> ""
                    }
                )
            }
    }

    /** "table:col1,col2;table2:col3" → table → columns. */
    private fun parseTableColumns(value: String): Map<String, Set<String>> {
        val out = linkedMapOf<String, Set<String>>()
        for (part in value.split(";")) {
            val table = part.substringBefore(":").trim()
            val cols = part.substringAfter(":", "").split(",")
                .map { it.trim() }.filter { it.isNotBlank() }.toSet()
            if (table.isNotBlank() && cols.isNotEmpty()) out[table] = cols
        }
        return out
    }

    private suspend fun checkVercelDeployRef(
        ref: EvidenceRef
    ): TalkerVerificationPolicy.RefCheckResult {
        val refId = ref.refId()
        val deploymentId = ref.ref.removePrefix("vercel:")
        val url = ref.value.trim()
        if (deploymentId.isBlank() || url.isBlank()) {
            return TalkerVerificationPolicy.RefCheckResult(
                refId, TalkerVerificationPolicy.RefOutcome.UNCHECKABLE, "deploy ref adhoora hai"
            )
        }
        return planExecutor.verifyVercelDeployTyped(deploymentId, url)
            .let { outcome ->
                TalkerVerificationPolicy.RefCheckResult(
                    refId, outcome,
                    when (outcome) {
                        TalkerVerificationPolicy.RefOutcome.FAILED -> "Vercel deploy nahi mila"
                        TalkerVerificationPolicy.RefOutcome.UNCHECKABLE -> "Vercel re-read nahi ho paya"
                        else -> ""
                    }
                )
            }
    }

    /**
     * SCREEN_STATE / MESSAGE_SENT: a FRESH accessibility snapshot,
     * probed with the same rule as verifyPerKindEvidence. A snapshot
     * that cannot be captured makes the ref UNCHECKABLE, never a
     * pass and never a fail.
     */
    private fun checkScreenRef(
        ref: EvidenceRef
    ): TalkerVerificationPolicy.RefCheckResult {
        val refId = ref.refId()
        val probe = ref.value.take(40)
        val snapshot = JarvisAccessibilityService.instance?.captureScreenSnapshot()
        if (snapshot == null || snapshot.elements.isEmpty()) {
            return TalkerVerificationPolicy.RefCheckResult(
                refId, TalkerVerificationPolicy.RefOutcome.UNCHECKABLE, "snapshot nahi mila"
            )
        }
        if (probe.isBlank()) {
            return TalkerVerificationPolicy.RefCheckResult(
                refId, TalkerVerificationPolicy.RefOutcome.UNCHECKABLE, "probe khaali hai"
            )
        }
        if (ref.kind == EvidenceKind.MESSAGE_SENT) {
            val draftStillThere = snapshot.elements.any { el ->
                el.isEditable && (el.text.contains(probe) || el.contentDesc.contains(probe))
            }
            if (draftStillThere) {
                return TalkerVerificationPolicy.RefCheckResult(
                    refId, TalkerVerificationPolicy.RefOutcome.FAILED, "draft abhi bhi input me hai"
                )
            }
            val sentVisible = snapshot.elements.any { el ->
                !el.isEditable && (el.text.contains(probe) || el.contentDesc.contains(probe))
            }
            return if (sentVisible) {
                TalkerVerificationPolicy.RefCheckResult(refId, TalkerVerificationPolicy.RefOutcome.PASSED)
            } else {
                TalkerVerificationPolicy.RefCheckResult(
                    refId, TalkerVerificationPolicy.RefOutcome.UNCHECKABLE, "sent bubble nahi dikha"
                )
            }
        }
        val probeVisible = snapshot.elements.any { el ->
            el.text.contains(probe) || el.contentDesc.contains(probe)
        }
        return if (probeVisible) {
            TalkerVerificationPolicy.RefCheckResult(refId, TalkerVerificationPolicy.RefOutcome.PASSED)
        } else {
            TalkerVerificationPolicy.RefCheckResult(
                refId, TalkerVerificationPolicy.RefOutcome.FAILED, "screen par probe nahi mila"
            )
        }
    }

    /** Speak + chat the verdict, or start the bounded fix flow on MISMATCH. */
    private suspend fun deliverVerdict(
        commandId: String,
        report: VerificationReport,
        fixed: Boolean
    ) {
        val state = verificationStates[commandId] ?: return
        when (report.verdict) {
            TalkerVerificationPolicy.VerificationVerdict.VERIFIED -> {
                speechSuppressedResultIds.remove(commandId)
                val line = TalkerVerificationPolicy.verifiedLine(state.result.summary, state.result.isLate)
                JarvisStateRepository.addChatMessage("Jarvis", line)
                persistChat()
                JarvisStateRepository.setAgentState(AgentRunState.SPEAKING, "Speaking: $line")
                speak(line, alreadyPostedToChat = true)
                JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Ready for next command")
                // Learn only from VERIFIED outcomes (DESIGN §5.5) —
                // never from unverified claims or failures.
                learnFromVerified(state, fixed)
                speak("Aur kya karna hai?")
                clearVerificationState(commandId)
            }
            TalkerVerificationPolicy.VerificationVerdict.UNVERIFIED -> {
                speechSuppressedResultIds.remove(commandId)
                val artifact = TalkerVerificationPolicy.artifactPhraseFor(report.results)
                val line = TalkerVerificationPolicy.unverifiedLine(artifact)
                JarvisStateRepository.addChatMessage("Jarvis", line)
                JarvisStateRepository.addChatMessage(
                    "Jarvis", TalkerVerificationPolicy.unverifiedDetail(
                        report.results.filter { it.outcome == TalkerVerificationPolicy.RefOutcome.UNCHECKABLE }
                    )
                )
                persistChat()
                JarvisStateRepository.setAgentState(AgentRunState.SPEAKING, "Speaking: $line")
                speak(line, alreadyPostedToChat = true)
                JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Ready for next command")
                speak("Aur kya karna hai?")
                clearVerificationState(commandId)
            }
            TalkerVerificationPolicy.VerificationVerdict.MISMATCH -> {
                onMismatch(
                    state,
                    report.results.filter { it.outcome == TalkerVerificationPolicy.RefOutcome.FAILED }
                )
            }
        }
    }

    /**
     * MISMATCH → bounded fix (DESIGN §4.6): max 2 attempts, each
     * bounded by 5 min AND by the command's remaining time. UNVERIFIED
     * never triggers a fix (there is nothing concrete to fix).
     * Fix attempts are not task retries — a FAILED *task* gets no
     * FIX_REQUEST; only this MISMATCH branch constructs one.
     */
    private suspend fun onMismatch(
        state: VerificationState,
        failed: List<TalkerVerificationPolicy.RefCheckResult>
    ) {
        val kinds = TalkerVerificationPolicy.failedKinds(failed)
        val attempt = (fixAttempts[state.command.id] ?: 0) + 1
        if (attempt > TalkerExecutorProgress.FIX_MAX_ATTEMPTS) {
            speakFixExhausted(state, kinds, failed)
            return
        }
        val remainingMs = state.command.timeoutMs -
            (System.currentTimeMillis() - state.command.issuedAtMs)
        if (remainingMs <= 0) {
            val line = TalkerVerificationPolicy.fixTimeoutLine(kinds)
            JarvisStateRepository.addChatMessage("Jarvis", line)
            JarvisStateRepository.addChatMessage(
                "Jarvis", TalkerVerificationPolicy.mismatchDetail(failed)
            )
            persistChat()
            JarvisStateRepository.setAgentState(AgentRunState.SPEAKING, "Speaking: $line")
            speak(line, alreadyPostedToChat = true)
            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Ready for next command")
            JarvisStateRepository.addDiagnostic(
                "talker", "VERIFY_TIMEOUT",
                "Fix window exceeded for ${state.command.id}: ${kinds.joinToString(",")}"
            )
            clearVerificationState(state.command.id)
            return
        }
        fixAttempts[state.command.id] = attempt
        // Remember the failure reason — if the fix works, it becomes
        // the pattern's `failure` (DESIGN §5.5: a fixed failure is
        // the most valuable pattern there is).
        verificationStates[state.command.id] = state.copy(
            fixFailure = TalkerVerificationPolicy.mismatchDetail(failed).take(200)
        )
        val line = if (attempt == 1) {
            TalkerVerificationPolicy.fixStartLine(kinds)
        } else {
            TalkerVerificationPolicy.fixRetryLine(kinds)
        }
        JarvisStateRepository.addChatMessage("Jarvis", line)
        JarvisStateRepository.addChatMessage(
            "Jarvis", TalkerVerificationPolicy.mismatchDetail(failed)
        )
        persistChat()
        JarvisStateRepository.setAgentState(AgentRunState.SPEAKING, "Speaking: $line")
        speak(line, alreadyPostedToChat = true)
        JarvisStateRepository.setAgentState(
            AgentRunState.EXECUTING_STEP, "Executor fixing — Talker waiting for fix result"
        )
        val request = ExecutorFixRequest(
            commandId = state.command.id,
            attempt = attempt,
            missingRefs = failed.map { it.refId },
            reason = TalkerVerificationPolicy.mismatchDetail(failed).take(200)
        )
        if (!talkerBus.requestFix(request)) {
            // Executor is gone (cancelled / superseded since the
            // verdict) — end honestly instead of waiting forever.
            JarvisStateRepository.addLog("FIX_REQUEST dropped for ${state.command.id} — executor gone.")
            speakFixExhausted(state, kinds, failed)
        }
    }

    /** Attempts exhausted: honest "still missing X", never "ho gaya". */
    private suspend fun speakFixExhausted(
        state: VerificationState,
        kinds: List<EvidenceKind>,
        failed: List<TalkerVerificationPolicy.RefCheckResult>
    ) {
        val line = TalkerVerificationPolicy.fixExhaustedLine(kinds)
        JarvisStateRepository.addChatMessage("Jarvis", line)
        JarvisStateRepository.addChatMessage(
            "Jarvis", TalkerVerificationPolicy.mismatchDetail(failed)
        )
        persistChat()
        JarvisStateRepository.setAgentState(AgentRunState.SPEAKING, "Speaking: $line")
        speak(line, alreadyPostedToChat = true)
        JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Ready for next command")
        JarvisStateRepository.addDiagnostic(
            "talker", "VERIFY_FAILED",
            "Fix exhausted for ${state.command.id}: ${kinds.joinToString(",")}"
        )
        clearVerificationState(state.command.id)
    }

    // ---- Fix execution (Talker asks, Executor re-runs only the failed refs) ----

    /**
     * FIX_REQUEST handler: runs the minimal fix for the command's
     * work kind, bounded by min(5 min, remaining time). The fix runs
     * as a fresh scope job — the original Executor job already
     * ended when it reported DONE. A fix that cannot run (or times
     * out) answers STILL_MISSING at once; the Executor never
     * improvises a new plan here.
     */
    private suspend fun handleFixRequest(request: ExecutorFixRequest) {
        val state = verificationStates[request.commandId] ?: run {
            JarvisStateRepository.addLog("FIX_REQUEST dropped for ${request.commandId} — no verification in flight.")
            return
        }
        if (request.commandId !in verificationInFlight) return
        val remainingMs = state.command.timeoutMs -
            (System.currentTimeMillis() - state.command.issuedAtMs)
        if (remainingMs <= 0) {
            talkerBus.completeFix(
                ExecutorFixResult(
                    commandId = request.commandId,
                    attempt = request.attempt,
                    status = FixResultStatus.STILL_MISSING,
                    missingRefs = request.missingRefs,
                    detail = "fix window exceeded"
                )
            )
            return
        }
        val budgetMs = minOf(TalkerExecutorProgress.FIX_TIMEOUT_MS, remainingMs)
        val result = withTimeoutOrNull(budgetMs) {
            when (lastExecutorWorkKind) {
                ExecutorWorkKind.PLAN -> planExecutor.fixMissingRefs(
                    request.missingRefs, request.commandId, request.attempt
                )
                ExecutorWorkKind.LOOP -> fixLoopResult(request, state)
                else -> ExecutorFixResult(
                    commandId = request.commandId,
                    attempt = request.attempt,
                    status = FixResultStatus.STILL_MISSING,
                    missingRefs = request.missingRefs,
                    detail = "unknown work kind"
                )
            }
        } ?: ExecutorFixResult(
            commandId = request.commandId,
            attempt = request.attempt,
            status = FixResultStatus.STILL_MISSING,
            missingRefs = request.missingRefs,
            detail = "fix timed out"
        )
        talkerBus.completeFix(result)
    }

    /** FIX_RESULT handler: FIXED re-triggers verification; STILL_MISSING tries the next attempt. */
    private suspend fun handleFixResult(fix: ExecutorFixResult) {
        val state = verificationStates[fix.commandId] ?: return
        if (fix.commandId !in verificationInFlight) return
        when (fix.status) {
            FixResultStatus.FIXED -> {
                // Remember the step that fixed it — the pattern's
                // `fix` (DESIGN §5.5). The `failure` was stored when
                // the FIX_REQUEST went out (see onMismatch).
                val prior = verificationStates[fix.commandId]
                if (prior != null) {
                    verificationStates[fix.commandId] = prior.copy(
                        fixStepLabel = fix.detail.take(200)
                    )
                }
                val freshRefs = EvidenceRefs.parse(fix.freshEvidenceJson)
                val report = verifyRefs(state.command, freshRefs)
                deliverVerdict(fix.commandId, report, fixed = true)
            }
            FixResultStatus.STILL_MISSING -> {
                val failed = fix.missingRefs.map { refId ->
                    TalkerVerificationPolicy.RefCheckResult(
                        refId, TalkerVerificationPolicy.RefOutcome.FAILED,
                        "fix ke baad bhi nahi mila"
                    )
                }
                onMismatch(state, failed)
            }
        }
    }

    /**
     * Loop fix (DESIGN §4.6): minimal repair only. For a preview /
     * file ref, re-run build evidence and, if it fails, one preview-
     * server restart + fresh GET (the fix attempt IS the repair, not
     * an extra one). For a message/screen ref, re-observe once. If
     * the minimal repair cannot run, STILL_MISSING at once — do not
     * improvise a new plan.
     */
    private suspend fun fixLoopResult(
        request: ExecutorFixRequest,
        state: VerificationState
    ): ExecutorFixResult {
        val failedRefs = EvidenceRefs.parse(state.result.evidenceJson)
            .filter { it.refId() in request.missingRefs }
        val stillMissing = ArrayList<String>()
        val fixedRefs = ArrayList<EvidenceRef>()
        for (ref in failedRefs) {
            when (ref.kind) {
                EvidenceKind.FILE_LIST, EvidenceKind.PREVIEW_HTTP -> {
                    val evidence = verifyBuildEvidence()
                    if (evidence == StepEvidence.VERIFIED) {
                        fixedRefs.addAll(loopBuildRefs())
                    } else {
                        val (_, ready) = ProjectPreviewServer.ensureRunningReady(context)
                        val project = lastBuildProject
                        val code = if (ready && project.isNotBlank()) {
                            previewGetStatus(ProjectStore.localhostUrl(project))
                        } else {
                            -1
                        }
                        if (code == 200 && verifyBuildEvidence() == StepEvidence.VERIFIED) {
                            fixedRefs.addAll(loopBuildRefs())
                        } else {
                            stillMissing.add(ref.refId())
                        }
                    }
                }
                EvidenceKind.MESSAGE_SENT, EvidenceKind.SCREEN_STATE -> {
                    val check = checkScreenRef(ref)
                    if (check.outcome == TalkerVerificationPolicy.RefOutcome.PASSED) {
                        fixedRefs.add(ref)
                    } else {
                        stillMissing.add(ref.refId())
                    }
                }
                else -> stillMissing.add(ref.refId())
            }
        }
        // Refs the Executor never sent cannot be fixed by re-checking.
        for (refId in request.missingRefs) {
            if (failedRefs.none { it.refId() == refId } && refId !in stillMissing) {
                stillMissing.add(refId)
            }
        }
        return if (stillMissing.isEmpty()) {
            ExecutorFixResult(
                commandId = request.commandId,
                attempt = request.attempt,
                status = FixResultStatus.FIXED,
                freshEvidenceJson = EvidenceRefs.toJson(fixedRefs.distinctBy { it.refId() }),
                detail = "loop fix: ${fixedRefs.joinToString(", ") { it.kind.wire }}".take(200)
            )
        } else {
            ExecutorFixResult(
                commandId = request.commandId,
                attempt = request.attempt,
                status = FixResultStatus.STILL_MISSING,
                missingRefs = stillMissing,
                detail = "loop fix: ${stillMissing.size} ref(s) still missing"
            )
        }
    }

    /** FILE_LIST + PREVIEW_HTTP refs for the last built project (loop path). */
    private fun loopBuildRefs(): List<EvidenceRef> {
        val project = lastBuildProject.ifBlank { return emptyList() }
        val files = ProjectStore.listProjects(context)
            .firstOrNull { it.name == project }?.files ?: emptyList()
        return listOf(
            EvidenceRef(
                EvidenceKind.FILE_LIST, "project:$project", "files_present",
                files.joinToString(",")
            ),
            EvidenceRef(
                EvidenceKind.PREVIEW_HTTP, "preview:$project", "http_200",
                ProjectStore.localhostUrl(project)
            )
        )
    }

    /** Typed refs for a loop DONE, by the final action (DESIGN §4.2). */
    private fun loopDoneRefs(decision: AgentDecision): List<EvidenceRef> {
        return when (decision.action) {
            "CREATE_PROJECT", "WRITE_PROJECT_FILE" -> loopBuildRefs()
            "WHATSAPP_SEND" -> listOf(
                EvidenceRef(
                    EvidenceKind.MESSAGE_SENT, "message:whatsapp", "sent_visible",
                    decision.text.trim().take(40)
                )
            )
            "OPEN_APP" -> listOf(
                EvidenceRef(
                    EvidenceKind.SCREEN_STATE, "screen:open_app", "probe_present",
                    decision.appName.trim().take(40)
                )
            )
            "TYPE_TEXT" -> listOf(
                EvidenceRef(
                    EvidenceKind.SCREEN_STATE, "screen:type_text", "probe_present",
                    decision.text.trim().take(40)
                )
            )
            else -> emptyList()
        }
    }

    // ---- 1.9.0 (v2) Talker/Executor: self-mastery (Gap 3) ----

    /**
     * Index-only pattern/instruction lookup for one EXECUTE turn
     * (DESIGN §5.3). Runs synchronously on the Talker path — the
     * same shape as the I1 document search that already runs here —
     * and never parses pattern files (indexes only). The ack-budget
     * guard is measured by the caller (diagnostic
     * `talker/PATTERN_LOOKUP_MS` when > 100 ms).
     */
    private fun lookupGuidance(goal: String, workKind: ExecutorWorkKind): Guidance {
        val keywords = MemoryKeywords.extract(goal)
        val patternMeta = runCatching {
            learnedPatternStore.findBestPattern(keywords, workKind.wire, goal)
        }.getOrNull()
        val pattern = patternMeta?.let { meta ->
            runCatching { learnedPatternStore.getPattern(meta.id) }.getOrNull()
        }
        val instructions = runCatching {
            learnedPatternStore.findInstructions(keywords, workKind.wire, goal)
        }.getOrDefault(emptyList())
        val patternHint = pattern?.let { p ->
            "${p.title}. Steps: ${p.steps.joinToString(", ") { it.label }}"
        } ?: ""
        val instructionHint = instructions.joinToString("; ") { it.text }
        val noteLines = ArrayList<String>()
        if (pattern != null) {
            noteLines.add("Seekha hua tareeka use kar raha hoon — ${pattern.title}.")
        }
        if (instructions.isNotEmpty()) {
            noteLines.add(
                "Yaad rakha hua: ${instructions.joinToString("; ") { it.text.take(80) }}."
            )
        }
        val params = mutableMapOf<String, String>()
        if (patternHint.isNotBlank()) {
            params["learned_pattern"] = patternHint.take(LearnedPatternPolicy.MAX_GUIDANCE_CHARS)
        }
        if (instructionHint.isNotBlank()) {
            params["user_instruction"] = instructionHint.take(LearnedPatternPolicy.MAX_GUIDANCE_CHARS)
        }
        return Guidance(
            patternId = pattern?.id,
            patternTitle = pattern?.title,
            patternHint = patternHint,
            instructionHint = instructionHint,
            instructionIds = instructions.map { it.id },
            noteLines = noteLines,
            params = params
        )
    }

    /**
     * Learn from a VERIFIED DONE (DESIGN §5.5): plan steps or the
     * loop's recorded safe steps become a pattern; a fixed failure
     * carries its failure + fix. Never called for UNVERIFIED,
     * FAILED, BLOCKED, DECLINED or CANCELLED. Also reinforces an
     * applied pattern (+0.1 confidence) — unless the user penalised
     * it mid-flight, in which case it is neither reinforced nor
     * re-minted from this task.
     */
    private suspend fun learnFromVerified(state: VerificationState, fixed: Boolean) {
        val cmd = state.command
        val appliedId = appliedPatternByCommand[cmd.id]
        if (appliedId != null && appliedId !in penalisedPatternIds) {
            withContext(Dispatchers.IO) {
                runCatching { learnedPatternStore.recordReuse(appliedId, success = true) }
            }
        }
        if (appliedId != null && appliedId in penalisedPatternIds) return
        val (steps, signature, title) = when (cmd.workKind) {
            ExecutorWorkKind.PLAN -> {
                val plan = planExecutor.lastFinishedPlan ?: return
                val doneSteps = plan.steps.filter { it.state == com.jarvis.autonomous.data.PlanState.DONE }
                if (doneSteps.isEmpty()) return
                Triple(
                    doneSteps.map { LearnedPatternStep(it.title.take(80), it.kind) },
                    RoutineNormalization.normalize(plan.goal).take(120),
                    plan.goal.take(80)
                )
            }
            ExecutorWorkKind.LOOP -> {
                val routine = lastVerifiedLoopRoutine ?: return
                if (routine.steps.isEmpty()) return
                Triple(
                    routine.steps.map { LearnedPatternStep(it.action, it.action) },
                    RoutineNormalization.normalize(cmd.goal).take(120),
                    cmd.goal.take(80)
                )
            }
            ExecutorWorkKind.ROUTINE -> return
        }
        val keywords = MemoryKeywords.extract(signature)
            .take(LearnedPatternPolicy.MAX_KEYWORDS)
        val pattern = LearnedPattern(
            id = "",
            triggerSignature = signature,
            triggerKeywords = keywords,
            workKind = cmd.workKind.wire,
            title = title.ifBlank { "Seekha hua kaam" },
            steps = steps,
            failure = if (fixed) state.fixFailure.take(200) else "",
            fix = if (fixed) state.fixStepLabel.take(200) else "",
            sourceCommandId = cmd.id,
            verified = true,
            confidence = LearnedPatternPolicy.START_CONFIDENCE
        )
        withContext(Dispatchers.IO) {
            runCatching {
                val savedId = learnedPatternStore.saveOrUpdatePattern(pattern)
                if (savedId == null) {
                    JarvisStateRepository.addLog("Pattern learn skip hua — extraction filter ne roka.")
                }
            }
        }
    }

    // ---- 1.9.0 (v2) Talker/Executor: loop progress milestones (Gap 1) ----

    /** User-meaningful noun label for a loop action (never an LLM thought). */
    private fun loopActionLabel(d: AgentDecision): String = when (d.action) {
        "OPEN_APP" -> "${d.appName.ifBlank { "App" }} kholna"
        "WHATSAPP_SEND" -> "WhatsApp bhejna"
        "CREATE_PROJECT", "WRITE_PROJECT_FILE" -> "Website banana"
        "TYPE_TEXT" -> "Text likhna"
        "YOUTUBE_PLAY" -> "YouTube chalana"
        "YOUTUBE_SEARCH" -> "YouTube par dhoondhna"
        "SCROLL" -> "Scroll karna"
        "NAVIGATE" -> "Navigate karna"
        "MEDIA_CONTROL" -> "Media control karna"
        "FLASHLIGHT" -> "Torch ${if (d.enableFlag) "on" else "off"} karna"
        "VOLUME" -> "Volume set karna"
        "WAIT" -> "Intezaar karna"
        "OPEN_BROWSER" -> "Browser kholna"
        "SAVE_MEMORY" -> "Yaad rakhna"
        else -> d.action.lowercase().replace("_", " ")
    }.take(80)

    /**
     * Loop milestone emission (DESIGN §3.2): only when (a) the action
     * kind changes vs the previous step, or (b) every 3rd verified
     * step, or (c) a step takes > 60 s — and only when the step's
     * own evidence check returns VERIFIED. No emit for
     * AMBIGUOUS/FAILED steps. `stepTotal = 0`, fraction/ETA unknown.
     */
    private suspend fun maybeEmitLoopProgress(
        decision: AgentDecision,
        step: Int,
        stepResult: ActionResult?,
        preSnapshot: ScreenSnapshot?,
        preMusicActive: Boolean?,
        stepStartMs: Long
    ) {
        val cmdId = activeExecutorCommand?.id ?: return
        val kindChanged = decision.action != loopLastActionKind
        val longStep = System.currentTimeMillis() - stepStartMs > 60_000L
        val thirdStep = (loopVerifiedSteps + 1) % 3 == 0
        loopLastActionKind = decision.action
        if (!kindChanged && !longStep && !thirdStep) return
        val evidence = verifyStepEvidence(
            decision, stepResult, JarvisAccessibilityService.instance,
            preSnapshot = preSnapshot, preMusicActive = preMusicActive
        )
        if (evidence != StepEvidence.VERIFIED) return
        loopVerifiedSteps++
        runCatching {
            talkerBus.reportProgress(
                ExecutorProgress(
                    commandId = cmdId,
                    stepIndex = step,
                    stepTotal = 0,
                    stepLabel = loopActionLabel(decision),
                    detail = decision.action,
                    progressFraction = -1.0,
                    etaSeconds = -1L,
                    isMilestone = true,
                    phase = "DONE"
                )
            )
        }
    }

    /**
     * Report a terminal (or TIMEOUT) outcome for the active Executor
     * command. No-op when no command is active, so non-Executor paths
     * can call it freely. TIMEOUT is advisory: the command leaves the
     * bus (a late DONE/FAILED still arrives as isLate), but the active
     * command stays bound so the late result can still be reported.
     * `speakResult=false` marks the outcome as already spoken by the
     * Executor itself (error lines, offline warnings, …) — the bus
     * listener then posts chat only, never a second speak.
     * `chatResult=false` marks the outcome as already fully in chat
     * AND spoken (verified exit, plan summary, …) — the listener
     * records it on the bus only, with no second chat line either.
     */
    private fun reportExecutorOutcome(
        status: ExecutorResultStatus,
        summary: String,
        evidence: String = "",
        speakResult: Boolean = true,
        chatResult: Boolean = true,
        // 1.9.0 (v2): typed evidence refs for DONE claims — the
        // Talker verification gate re-reads them (DESIGN §4.2).
        // Only DONE carries it; other statuses pass "".
        evidenceJson: String = ""
    ) {
        val cmd = activeExecutorCommand ?: return
        if (status == ExecutorResultStatus.TIMEOUT) {
            executorTimedOut = true
        } else {
            executorWatchdogJob?.cancel()
            executorWatchdogJob = null
            activeExecutorCommand = null
        }
        if (!chatResult) unannouncedResultIds.add(cmd.id)
        else if (!speakResult) speechSuppressedResultIds.add(cmd.id)
        val now = System.currentTimeMillis()
        talkerBus.complete(
            ExecutorResult(
                commandId = cmd.id,
                status = status,
                summary = summary.take(TalkerExecutorProtocol.MAX_SUMMARY_CHARS),
                evidence = evidence.take(500),
                evidenceJson = evidenceJson,
                finishedAtMs = now,
                durationMs = now - cmd.issuedAtMs
            )
        )
    }

    /** Engine accessor for AgentPlanExecutor terminal sites. */
    internal fun planReportExecutorResult(
        status: ExecutorResultStatus,
        summary: String,
        evidence: String = "",
        speakResult: Boolean = true,
        chatResult: Boolean = true,
        evidenceJson: String = ""
    ) = reportExecutorOutcome(status, summary, evidence, speakResult, chatResult, evidenceJson)

    /** Engine accessors for AgentPlanExecutor progress emission (v2 §3.2). */
    internal fun planActiveCommandId(): String? = activeExecutorCommand?.id

    internal fun planReportProgress(progress: ExecutorProgress) {
        val cmdId = activeExecutorCommand?.id ?: return
        if (progress.commandId != cmdId) return
        runCatching { talkerBus.reportProgress(progress) }
    }

    /** Post the user's turn once: the Talker posts it with the ack, so
     *  the Executor path must skip its own post (consumed exactly once). */
    private fun postYouLineOnce(goal: String) {
        if (executorTurnAlreadyPosted) {
            executorTurnAlreadyPosted = false
        } else {
            JarvisStateRepository.addChatMessage("You", goal)
        }
    }

    /** Engine accessor for AgentPlanExecutor.startNewPlan. */
    internal fun planPostYouLineOnce(goal: String) = postYouLineOnce(goal)

    /**
     * Cancel the running Executor command because a NEW command
     * supersedes it (single-job rule). The old job is cancelled and
     * its CANCELLED result names the new work, so the Talker tells
     * the user in one line. No-op when nothing runs.
     */
    private fun supersedeRunningCommand(newGoal: String) {
        if (activeExecutorCommand == null) return
        // 1.9.0 (v2): the superseded job's verification state dies
        // with it — a late DONE for the old work must never speak.
        activeExecutorCommand?.id?.let { clearVerificationState(it) }
        executorWatchdogJob?.cancel()
        executorWatchdogJob = null
        activeTaskJob?.cancel()
        activeTaskJob = null
        reportExecutorOutcome(
            ExecutorResultStatus.CANCELLED,
            TalkerReplyPolicy.supersededLine(newGoal),
            "superseded by new command"
        )
    }

    /** Drop verification + fix state for one command id (cancel / supersede / final verdict). */
    private fun clearVerificationState(commandId: String) {
        verificationInFlight.remove(commandId)
        verificationStates.remove(commandId)
        fixAttempts.remove(commandId)
        appliedPatternByCommand.remove(commandId)
    }

    /**
     * Run an already-submitted Executor command as the engine's
     * activeTaskJob: arm the timeout watchdog, launch [block], and
     * attach the job-completion safety net. The net reports FAILED
     * when a job ends with no terminal result delivered and no
     * TIMEOUT fired — a missed terminal site surfaces instead of
     * vanishing. A late result may still arrive from the Executor's
     * own terminal sites after a TIMEOUT (delivered as isLate).
     */
    private fun launchExecutorCommand(cmd: ExecutorCommand, block: suspend () -> Unit) {
        // A plain (non-Executor) loop turn may still be running — the
        // Talker never lets two jobs share one screen. (An Executor
        // job was already cancelled by supersedeRunningCommand above,
        // which nulled activeTaskJob, so this is a no-op then.)
        activeTaskJob?.cancel()
        activeTaskJob = null
        clearTaskScopedState()
        activeExecutorCommand = cmd
        executorTimedOut = false
        executorWatchdogJob = scope.launch {
            delay(cmd.timeoutMs)
            if (talkerBus.isPending(cmd.id)) {
                reportExecutorOutcome(
                    ExecutorResultStatus.TIMEOUT,
                    "Time zyada lag raha hai — ${cmd.goal.take(80)}",
                    "timeoutMs=${cmd.timeoutMs}"
                )
            }
        }
        val job = scope.launch { block() }
        activeTaskJob = job
        job.invokeOnCompletion { cause ->
            if (activeExecutorCommand?.id != cmd.id) return@invokeOnCompletion
            when {
                cause is CancellationException ->
                    reportExecutorOutcome(ExecutorResultStatus.CANCELLED, "", "")
                cause == null && !executorTimedOut ->
                    reportExecutorOutcome(
                        ExecutorResultStatus.FAILED,
                        "Kaam khatm hua par result nahi mila — dobara bolo to phir se koshish karunga.",
                        // 1.9.0 (v2, DESIGN §3.7): progress is attached
                        // to the FAILED evidence so the Talker can say
                        // what was last seen — but progress NEVER
                        // satisfies the safety net by itself.
                        "no terminal result delivered" +
                            (talkerBus.lastProgress(cmd.id)?.let { progress ->
                                "; last progress: Step ${progress.stepIndex}" +
                                    (if (progress.stepTotal > 0) "/${progress.stepTotal}" else "") +
                                    " ${progress.stepLabel}"
                            } ?: "")
                    )
                cause != null ->
                    reportExecutorOutcome(
                        ExecutorResultStatus.FAILED,
                        "Kaam me error aa gaya — dobara bolo to phir se koshish karunga.",
                        (cause.message ?: cause.javaClass.simpleName).take(200)
                    )
                // cause == null && executorTimedOut: a late result may
                // still arrive from the Executor's own terminal sites.
                else -> Unit
            }
        }
    }

    /** Memory-first context for a goal (local MemoryIndex turns +
     *  I1 saved-document snippets, labelled, snippet-only) — the
     *  same context the pre-1.8.0 tail built inline. */
    private fun buildMemoryContext(goal: String, docContext: String, recallHitCount: Int): String {
        val memorySummaries = memoryIndex.search(goal)
            .mapNotNull { memoryIndex.getSummary(it) }
        val turnContext = memorySummaries.joinToString(" | ")
        val memoryContext = listOf(docContext, turnContext)
            .filter { it.isNotBlank() }.joinToString("\n")
        if (memoryContext.isNotBlank()) {
            JarvisStateRepository.addLog("Memory-first: ${memorySummaries.size} pehle ki baat-cheet, $recallHitCount document mile.")
        }
        return memoryContext
    }

    /** Launch the autonomous loop as a plain (non-Executor) turn —
     *  the pre-1.8.0 path for chat turns that miss the local reply. */
    private fun launchLoopTurn(goal: String, docContext: String, recallHitCount: Int) {
        val memoryContext = buildMemoryContext(goal, docContext, recallHitCount)
        activeTaskJob?.cancel()
        activeTaskJob = scope.launch {
            runAutonomousLoop(goal, memoryContext)
        }
    }

    /**
     * Talker fast reply for a pure-chat turn: on-device Gemma first,
     * bounded by [TalkerReplyPolicy.TALKER_LOCAL_REPLY_TIMEOUT_MS].
     * Returns true when a reply was posted + spoken. False when the
     * model is not downloaded, the attempt timed out / errored, or the
     * reply came back blank — the caller falls through to today's
     * cloud loop (or the busy line while an Executor runs).
     * M-I-2 (1.14.0): [docContext] is the I1 recall output
     * ([DocumentText.buildDocContext] — max 3 docs, snippets ≤400
     * chars, labelled). It joins the system prompt as context only,
     * via [DocContextPrompt.talkerSystemPrompt]; blank means no
     * recall and the prompt is the persona prompt untouched.
     */
    private suspend fun tryTalkerLocalReply(text: String, docContext: String = ""): Boolean {
        if (!LocalLlmManager.isDownloaded(context)) return false
        val recent = JarvisStateRepository.chatHistory.value.takeLast(6).mapNotNull { entry ->
            val sender = entry.sender
            val message = entry.message
            if (message.isBlank()) null
            else if (sender == "You") "You" to message else "Jarvis" to message
        }
        val reply = withTimeoutOrNull(TalkerReplyPolicy.TALKER_LOCAL_REPLY_TIMEOUT_MS) {
            runCatching {
                LocalLlmManager.chat(
                    context = context,
                    systemPrompt = DocContextPrompt.talkerSystemPrompt(prefs.personaPrompt, docContext),
                    userText = text,
                    recentChat = recent
                )
            }.getOrNull()
        }?.trim().orEmpty()
        if (reply.isBlank()) return false
        JarvisStateRepository.addChatMessage("You", text)
        JarvisStateRepository.addChatMessage("Jarvis", reply)
        persistChat()
        speak(reply, alreadyPostedToChat = true)
        return true
    }

    // ---- 1.4.21 Agent Mode (planner trigger, resume, executor access) ----

    /** Frozen trigger (§3h): goal names GitHub / Supabase / Vercel /
     *  deploy, or the user says "agent mode" (Latin or
     *  transliterated Devanagari — the same transliteration the
     *  stop-command check uses).
     *  1.8.0: no longer called from handleUserCommand — the Talker
     *  dispatcher ([TalkerDispatcher]) owns routing now and mirrors
     *  this list (pinned by TalkerDispatcherTest). Kept as the
     *  documented frozen trigger; do not change one without the other. */
    private fun isAgentModeCommand(goal: String): Boolean {
        val lower = TurnDetector.transliterateDevanagariKeywords(goal.lowercase())
        return lower.contains("github") || lower.contains("supabase") ||
            lower.contains("vercel") || lower.contains("deploy") ||
            lower.contains("agent mode")
    }

    /** Called once from JarvisForegroundService.onCreate, after the
     *  mic-restore block: resumes a persisted plan after service
     *  recreation / app kill. Never throws — the executor's resume
     *  is defensive and a failure is a diagnostic, not a crash. */
    fun resumePersistedPlanIfAny() {
        scope.launch {
            runCatching { planExecutor.resumePersistedPlan() }.onFailure { e ->
                JarvisStateRepository.addDiagnostic(
                    "plan", "RESUME_ERROR",
                    "Plan resume fail hua: ${e.javaClass.simpleName}: ${(e.message ?: "").take(120)}"
                )
            }
        }
    }

    // Internal accessors for AgentPlanExecutor only. The executor
    // never reaches into engine privates directly; every crossing
    // is one of these named functions.
    internal fun planLlm(): LlmClient = llmClient
    internal fun planPrefs(): JarvisPreferences = prefs
    internal fun planContext(): Context = context

    // 1.11.0-PRO (self-extension, DESIGN §1): the loop lives inside
    // the Executor's single job — owned here like llmClient above,
    // lazy so it receives a fully-constructed engine. No new service,
    // no WorkManager, no second Executor job.
    private val selfExtensionLoop: SelfExtensionLoop by lazy {
        SelfExtensionLoop(context, llmClient, learnedRoutineStore)
    }

    /**
     * 1.11.0-PRO (self-extension): the Executor's entry into the
     * loop (used by AgentPlanExecutor after a step Failed and by the
     * engine loop after a loop action fails with a capability
     * error). Runs inside the current job; never launches its own
     * scope. The loop's terminal outcome reaches the Talker through
     * the EXISTING result path — the loop posts its one honest line
     * to chat, and the task's terminal DONE/FAILED/BLOCKED result
     * still flows through reportExecutorOutcome -> the bus ->
     * deliverVerifiedExecutorResult (DONE) / deliverExecutorResult
     * (other statuses). No second result is emitted mid-loop, so
     * the single-result invariant stays intact.
     */
    internal suspend fun planMaybeExtend(
        failure: ExecutorFailure,
        runId: String,
        hasRealInput: Boolean,
        retryOriginal: (suspend () -> Boolean)? = null,
        stepRunner: (suspend (SkillStep) -> Boolean)? = null
    ): SelfExtensionLoop.LoopOutcome = selfExtensionLoop.maybeExtend(
        failure = failure,
        runId = runId,
        hasRealInput = hasRealInput,
        retryOriginal = retryOriginal,
        stepRunner = stepRunner ?: { skillStep ->
            planExecuteLearnedStep(
                LearnedStep(
                    action = skillStep.action,
                    target = skillStep.target,
                    input = skillStep.input
                )
            )
        }
    )

    /**
     * 1.11.0-PRO (self-extension, §7): executes one candidate skill
     * step through the SAME path `findRoutine` replay uses
     * ([executeLearnedStep]) — the verifier's real-invocation path.
     * Steps outside the replay vocabulary return false (a mismatch),
     * never a faked success.
     */
    internal suspend fun planExecuteLearnedStep(step: LearnedStep): Boolean =
        executeLearnedStep(step, JarvisAccessibilityService.instance)
    internal suspend fun planSpeak(text: String): Boolean = speak(text)
    internal suspend fun planAsk(prompt: String): String? = askUserVoice(prompt)
    internal fun planIsOnline(): Boolean = isOnline()
    internal suspend fun planExecuteDecision(d: AgentDecision): String =
        executeDecisionAction(d, JarvisAccessibilityService.instance).summary
    internal fun planLastBuildWritten(): Boolean = lastBuildWritten
    internal fun planLastBuildValidation(): ProjectStore.ProjectValidation? = lastBuildValidation
    internal fun planLastBuildProject(): String = lastBuildProject
    internal suspend fun planCheckGate(d: AgentDecision): Pair<GateOutcome, String> =
        checkExecutionGate(d, false)
    internal fun planAnsweredClarifications(): MutableList<String> = answeredClarifications
    internal fun planRecordClarification(entry: String) {
        answeredClarifications.add(entry)
        while (answeredClarifications.size > 12) answeredClarifications.removeAt(0)
    }
    internal fun planRestoreClarifications(entries: List<String>) {
        answeredClarifications.clear()
        answeredClarifications.addAll(entries.takeLast(12))
    }
    internal fun planIsDecline(reply: String): Boolean = isDeclineReply(reply)
    internal fun planIsYes(reply: String): Boolean =
        isYesReply(reply) || isAffirmativeReply(reply.lowercase())
    internal fun planMemoryIndex(): MemoryIndex = memoryIndex
    internal fun planDocumentMemory(): DocumentMemoryStore = documentMemory

    /**
     * I1 (1.7.1): save a shown document into on-device memory.
     * Explicit saves only — the engine never auto-saves a turn as a
     * document. Returns the store result (ok/id/evicted ids). The
     * text stays on the phone; this function performs no network call.
     */
    fun rememberDocument(title: String, text: String, source: String): SaveResult {
        val result = runCatching {
            documentMemory.saveDocument(title, text, source)
        }.getOrElse {
            SaveResult(ok = false, reason = "save_error")
        }
        if (result.ok) {
            // Metadata only in the log — never doc text.
            JarvisStateRepository.addLog(
                "Document yaad rakha: '${result.title}' (${result.charCount} chars)"
            )
        }
        return result
    }

    /**
     * I1 (1.7.1): offline direct recall. When there is no network and
     * a saved document strongly matches the goal, answer straight from
     * the on-device snippet — no LLM call, no network, nothing leaves
     * the phone. Returns true only when it answered (the caller must
     * return). A weak/no match returns false and the normal flow runs.
     */
    private fun tryDocumentRecall(
        goal: String,
        hits: List<com.jarvis.autonomous.data.DocumentHit>
    ): Boolean {
        if (isOnline()) return false
        val strong = hits.firstOrNull {
            DocumentSearch.isStrongHit(it.score, it.titleMatched)
        } ?: return false
        activeTaskJob?.cancel()
        clearTaskScopedState()
        activeTaskJob = scope.launch {
            JarvisStateRepository.addChatMessage("You", goal)
            persistChat()
            val answer = "Ye '${strong.title}' se mila — ${strong.snippet} " +
                "(sirf phone se yaad, offline — internet nahi hai, isliye bahar se kuch nahi manga.)"
            JarvisStateRepository.addChatMessage("Jarvis", answer)
            persistChat()
            JarvisStateRepository.setAgentState(
                AgentRunState.IDLE, "Document se yaad — offline"
            )
            speak(answer, alreadyPostedToChat = true)
        }
        return true
    }
    internal fun planPersistChat() = persistChat()
    internal fun planClearTaskState() = clearTaskScopedState()
    internal fun planLaunch(job: suspend () -> Unit) {
        activeTaskJob?.cancel()
        activeTaskJob = scope.launch { job() }
    }

    /**
     * Proactively announces incoming WhatsApp / SMS messages when Auto-Announce is enabled.
     */
    fun announceIncomingNotification(sender: String, message: String) {
        if (isTaskRunning()) return
        scope.launch {
            // 1.4.14 (D1): dedupe lives ONLY here, inside the
            // announce path — never in JarvisNotificationService
            // (its tab listing + reply-handle storage stay ungated)
            // and never via VoiceManager coalescing (downstream,
            // keyed on spoken text, cannot tell call announces
            // from notification announces). Drops below NEVER
            // arm the record: the record updates only when an
            // announce actually happens.
            if (isTaskRunning()) return@launch
            if (!prefs.autoAnnounceNotifications) return@launch
            val trimmedSender = sender.trim()
            val trimmedMessage = message.trim()
            if (trimmedSender.isBlank() || trimmedMessage.isBlank()) return@launch
            val now = SystemClock.elapsedRealtime()
            val dedupeKey = trimmedSender + "\u0001" + trimmedMessage
            val pruneIt = recentAnnounces.entries.iterator()
            while (pruneIt.hasNext()) {
                if (now - pruneIt.next().value >= 60_000L) pruneIt.remove()
            }
            val lastAnnounced = recentAnnounces[dedupeKey]
            if (lastAnnounced != null && now - lastAnnounced < 60_000L) {
                // Content-free log only: sender/text values must
                // never reach diagnostics (repository header rule).
                JarvisStateRepository.addLog("Announce deduped: identical sender+text within 60s")
                return@launch
            }
            recentAnnounces[dedupeKey] = now
            val announcement = "Bhai, $sender ka message aaya hai: ${message.take(90)}"
            JarvisStateRepository.addChatMessage("Jarvis", announcement)
            speak(announcement, "announce-notification", alreadyPostedToChat = true)
        }
    }

    /**
     * Incoming-call announcement (from JarvisCallReceiver). Same
     * Auto-Announce toggle as notifications, and never while a task
     * is running, so it never talks over an ongoing job.
     */
    fun announceIncomingCall(displayNameOrNumber: String) {
        if (isTaskRunning()) return
        scope.launch {
            // 1.4.8 (G8 F3): GENERIC-ONLY by user decision — the
            // display value is INTENTIONALLY IGNORED here and
            // never spoken, even if the OS/OEM ever delivers a
            // real number. The old code spoke it whenever
            // non-blank, so permission absence was the ONLY
            // thing keeping the announce generic; now the speak
            // site itself enforces it. The parameter stays for
            // call-site compatibility only (the receiver still
            // passes whatever the OS delivered; nothing here
            // reads it). No name/number ever reaches chat or
            // speech from this path.
            val announcement = "Bhai, call aa rahi hai."
            JarvisStateRepository.addChatMessage("Jarvis", announcement)
            speak(announcement, "announce-call", alreadyPostedToChat = true)
        }
    }

    /**
     * No-AI routines (user order 2026-11-02): fixed everyday commands
     * run as deterministic steps, without an LLM call for every step.
     * YouTube: search → find first video → ASK the user → play only
     * on "haan". WhatsApp: ASK first (a sent message cannot be
     * unsent), then send. Anything that does not match a routine
     * falls through to the normal LLM multi-step loop below.
     */
    /**
     * 1.4.20 (item 5): current-information question matcher, kept
     * deliberately narrow so it never hijacks commands. A turn
     * matches only when it has BOTH a current-info topic token
     * (aaj/latest/abhi/current/live/news/score/price/mausam/weather
     * and kin) AND a question shape ("?" or kya/kaun/kitna/kaisa/
     * batao / English wh-words). Show-page commands ("weather
     * dikhao", "news kholo"), build requests ("website banao")
     * and plain statements do NOT match — they keep today's path.
     * Time/date turns never reach here (the time/date block above
     * returns first). Tokens are whole words, so "aaj" inside a
     * longer word cannot false-match.
     */
    private fun isCurrentInfoQuestion(lower: String): Boolean {
        // 1.4.22 (item 2): task/memory turns ("aaj mere kaam kya
        // hain", "kal ki baat yaad hai?") and app-self turns
        // ("live preview kaisa hai", "jarvis ka voice kaisa hai")
        // are NOT current-info questions — they keep the memory /
        // app path. "app" is a TOKEN match only: substring would
        // match "whatsapp" and break WhatsApp routines.
        if (lower.contains("website") || lower.contains("banao") ||
            lower.contains("banado") || lower.contains("kholo") ||
            lower.contains("chalao") || lower.contains("dikhao") ||
            lower.contains("kaam") || lower.contains("task") ||
            lower.contains("yaad") || lower.contains("bhool") ||
            lower.contains("routine") || lower.contains("plan") ||
            lower.contains("schedule") || lower.contains("timetable") ||
            lower.contains("preview") || lower.contains("project") ||
            lower.contains("jarvis") || lower.contains("mic") ||
            lower.contains("screen") || lower.contains("notification") ||
            lower.contains("battery") || lower.contains("live mode") ||
            Regex("\\bapp\\b").containsMatchIn(lower)
        ) {
            return false
        }
        val tokens = lower.replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ").filter { it.isNotBlank() }.toSet()
        val topics = setOf(
            "aaj", "latest", "abhi", "current", "live", "news",
            "score", "price", "mausam", "weather", "bhav", "rate",
            "samachar", "khabar", "taaza", "taza", "aajkal"
        )
        if (tokens.intersect(topics).isEmpty()) return false
        if (lower.contains("?")) return true
        val questionWords = setOf(
            "kya", "kaun", "kitna", "kitne", "kaisa", "kahan",
            "kab", "batao", "bata", "what", "when", "where",
            "who", "why", "how", "which"
        )
        return tokens.intersect(questionWords).isNotEmpty()
    }

    /**
     * 1.5.1-PRO (L2): quick-path failure -> LLM re-plan.
     * Speaks the honest failure line (the quick path's own
     * contract), posts it to chat with a "dobara koshish"
     * note, then runs the autonomous loop on the same
     * command — the LLM re-observes the screen and re-plans.
     * Exactly once: runAutonomousLoop never calls
     * tryNoAiRoutine, so there is no ping-pong. Declines
     * never come here (decline-abort stays a stop).
     */
    private suspend fun quickPathFallback(trimmed: String, failureLine: String) {
        JarvisStateRepository.addChatMessage("Jarvis", "$failureLine — dobara koshish kar raha hoon.")
        persistChat()
        speak(failureLine, alreadyPostedToChat = true)
        JarvisStateRepository.addDiagnostic("l2", "QUICK_FALLBACK", "Quick-path fail — LLM loop re-plan.")
        runAutonomousLoop(trimmed)
    }

    /**
     * 1.5.1-PRO (L2): sync-branch entry to quickPathFallback.
     * Cancels any previous task and runs the fallback as the
     * new activeTaskJob (same pattern as the YouTube /
     * WhatsApp flows, which own their job already).
     */
    private fun launchQuickFallback(trimmed: String, failureLine: String) {
        activeTaskJob?.cancel()
        activeTaskJob = scope.launch { quickPathFallback(trimmed, failureLine) }
    }

    /**
     * 1.5.2-PRO (second-claim guard): completion-claim verbs
     * (Hinglish + English, lowercase match). A reply with
     * claim verbs in 2+ segments is spoken only up to the
     * first claim — see guardSecondClaim.
     */
    private val claimVerbs = listOf(
        "bhej diya", "bhej diya hai", "laga diya", "khol diya",
        "chala diya", "kar diya", "ho gaya", "ban gaya",
        "save kar diya", "sent", "done", "completed", "opened", "played"
    )

    private fun containsClaimVerb(text: String): Boolean {
        val lower = text.lowercase()
        return claimVerbs.any { lower.contains(it) }
    }

    /**
     * 1.5.2-PRO (second-claim guard, closes 1.4.25 Step 1g):
     * a multi-claim reply ("X bhej diya aur Y bhi kar diya")
     * is spoken only up to the first claim. Split points:
     * " aur ", " and ", ", ", "; ". Fires ONLY when the part
     * before AND the part after a split both contain a claim
     * verb. Returns (speakText, secondClaim?) — the second
     * claim is truncated to 80 chars for the chat note.
     * Deterministic, no LLM, no NLP library. The guard only
     * shortens what is SPOKEN — the full reply stays in chat
     * with a note; evidence, routine, index and state are
     * untouched. Worst case of a false positive: the second
     * half of a long reply is not spoken (still visible in
     * chat) — the safe direction.
     */
    private fun guardSecondClaim(reply: String): Pair<String, String?> {
        val lower = reply.lowercase()
        var bestSplit: Int? = null
        var bestSepLen = 0
        for (sep in listOf(" aur ", " and ", ", ", "; ")) {
            var idx = lower.indexOf(sep)
            while (idx >= 0) {
                val first = lower.substring(0, idx)
                val rest = lower.substring(idx + sep.length)
                if (containsClaimVerb(first) && containsClaimVerb(rest)) {
                    if (bestSplit == null || idx < bestSplit) {
                        bestSplit = idx
                        bestSepLen = sep.length
                    }
                    break
                }
                idx = lower.indexOf(sep, idx + 1)
            }
        }
        if (bestSplit == null) return reply to null
        val speakText = reply.substring(0, bestSplit).trim()
        val secondClaim = reply.substring(bestSplit + bestSepLen).trim().take(80)
        if (speakText.isBlank()) return reply to null
        return speakText to secondClaim
    }

    private fun tryNoAiRoutine(lower: String, trimmed: String): Boolean {
        val acc = JarvisAccessibilityService.instance
        // Patterns suggestions (1.4.1 audit gap): "patterns dikhao" /
        // "suggestions dikhao" lists approved patterns as chat
        // suggestions only — never stored, never auto-executed.
        if (lower.contains("pattern") && (lower.contains("dikhao") || lower.contains("suggestion"))) {
            fetchPatternSuggestions()
            return true
        }
        // YouTube: "youtube par <query> chalao/play/bajao"
        val ytMatch = Regex(
            "youtube\\s+(?:par|pe|me)?\\s*(.+?)\\s*(?:chalao|play|bajao|sunao|dikhao)\\s*$"
        ).find(lower)
        if (ytMatch != null) {
            val query = ytMatch.groupValues[1]
                .replace(Regex("^(par|pe|me)\\s+"), "").trim()
            if (query.isNotBlank()) {
                if (acc == null) {
                    scope.launch { speak("Accessibility service on nahi hai, isliye YouTube nahi khol sakta. Permissions screen se on karo.") }
                    return true
                }
                activeTaskJob?.cancel()
                activeTaskJob = scope.launch {
                    try {
                        speak("YouTube par $query dhoondh raha hoon.")
                        if (!acc.searchYouTube(query)) {
                            // 1.5.1-PRO (L2): search fail -> LLM
                            // re-plan (re-observe + retry), not a stop.
                            quickPathFallback(trimmed, "YouTube nahi khul paya.")
                            return@launch
                        }
                        // 1.4.7 (B): no blind 3000ms wait here —
                        // findFirstYouTubeVideo polls fresh
                        // snapshots itself (3 x 1800ms), so the
                        // blind wait only added 3s on every path.
                        val video = acc.findFirstYouTubeVideo()
                        if (video == null) {
                            quickPathFallback(trimmed, "Pehli video nahi mili. Search results khule hain, tum khud tap kar lo.")
                            return@launch
                        }
                        val title = video.text.ifBlank { video.contentDesc }
                        // A-C1: negation-aware confirmation — "mat
                        // chalao" must NEVER play. askUserVoice also
                        // never waits when the question was unspoken.
                        val reply = askUserVoice("Pehli video $title mili hai. Chalau?")
                        if (reply != null && isYesReply(reply)) {
                            val ok = acc.tapNode(video.id)
                            if (!ok) {
                                quickPathFallback(trimmed, "Tap nahi ho paya.")
                            } else {
                                // 1.4.25-PRO Step 1C (1e): tap dispatch
                                // alone proves nothing — verify the
                                // post-tap screen is YouTube with a
                                // player signal (audit §10(b)
                                // YOUTUBE_PLAY rule): a pause/stop
                                // control, or the tapped video's title
                                // in the player area. No signal = an
                                // honest line, never "Chala diya."
                                delay(1000)
                                val post = acc.captureScreenSnapshot()
                                val titleProbe = title.take(40)
                                val playing = post.packageName == "com.google.android.youtube" &&
                                    post.elements.any { el ->
                                        val label = "${el.text} ${el.contentDesc}".lowercase()
                                        label.contains("pause") || label.contains("stop") ||
                                            (titleProbe.isNotBlank() &&
                                                (el.text.contains(titleProbe) || el.contentDesc.contains(titleProbe)))
                                    }
                                if (playing) {
                                    speak("Chala diya.")
                                } else {
                                    // 1.5.1-PRO (L2): tap dispatched
                                    // but no play signal — LLM
                                    // re-observes instead of stopping.
                                    quickPathFallback(trimmed, "Video tap ho gaya, par play hota dikh nahi raha — screen par check karo.")
                                }
                            }
                        } else {
                            speak("Theek hai, nahi chalaya.")
                        }
                    } catch (e: Exception) {
                        JarvisStateRepository.addLog("YouTube routine error: ${e.message}")
                    }
                }
                return true
            }
        }
        // WhatsApp: "whatsapp par <name> ko <message> bhejo"
        val waMatch = Regex(
            "whatsapp\\s+(?:par|pe|me)?\\s*(.+?)\\s+ko\\s+(.+?)\\s*(?:bhejo|bhej\\s+do|send\\s+karo)\\s*$"
        ).find(lower)
        if (waMatch != null) {
            val contact = waMatch.groupValues[1].trim()
            val message = waMatch.groupValues[2].trim()
            if (contact.isNotBlank() && message.isNotBlank()) {
                if (acc == null) {
                    scope.launch { speak("Accessibility service on nahi hai, isliye WhatsApp nahi bhej sakta.") }
                    return true
                }
                activeTaskJob?.cancel()
                activeTaskJob = scope.launch {
                    try {
                        // A-C1: "mat bhejo" contains "bhej" but is a
                        // NO — isYesReply vetoes on any negation token.
                        val reply = askUserVoice("$contact ko ye message bhejna hai: $message. Bhej dun?")
                        if (reply != null && isYesReply(reply)) {
                            // 1.4.25-PRO Step 1C (1e): "Message bhej
                            // diya." stays — sendWhatsAppMessage only
                            // returns true when the Send button was
                            // found AND tapped, the strongest
                            // quick-path check. Residual: delivery /
                            // read is still unconfirmed (the tap is
                            // dispatch, not delivery) — no stronger
                            // signal is observable here.
                            val ok = acc.sendWhatsAppMessage(contact, message)
                            if (ok) {
                                speak("Message bhej diya.")
                            } else {
                                // 1.5.1-PRO (L2): send fail -> LLM
                                // re-plan (Send button dobara dhoondho).
                                quickPathFallback(trimmed, "Send button nahi mila — chat khula hai, message likha hua hai, tum send tap kar do.")
                            }
                        } else {
                            speak("Theek hai, message nahi bheja.")
                        }
                    } catch (e: Exception) {
                        JarvisStateRepository.addLog("WhatsApp routine error: ${e.message}")
                    }
                }
                return true
            }
        }
        // ---- More no-AI routines (user order 2026-11-02: "jo kaam
        // bina AI ke ho sakta hai, set trained karke") ----
        // Time / date — no app, no network, instant.
        if (lower.contains("time kya") || lower.contains("samay kya") ||
            lower.contains("kitne baje") || lower.contains("date kya") ||
            lower.contains("aaj kaun si date") || lower == "time" || lower == "date"
        ) {
            val now = java.time.LocalDateTime.now()
            val answer = if (lower.contains("date")) {
                "Aaj ki date hai ${now.dayOfMonth} ${now.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${now.year}."
            } else {
                "Abhi time hai ${"%02d".format(now.hour)} baj kar ${"%02d".format(now.minute)} minute."
            }
            scope.launch { speak(answer) }
            return true
        }
        // 1.4.20 (item 5): FREE live-browsing AI, engine-side
        // pre-check in the no-AI routine pattern (same shape as the
        // time/date block above). Matching turns route to
        // answerWithLiveSearch BEFORE decideNextStep runs;
        // non-matching turns are byte-for-byte unchanged.
        // Gemini-provider only: other providers fall through to
        // today's behaviour (LLM loop / OPEN_BROWSER browse).
        if (prefs.provider == AiProvider.GEMINI && isCurrentInfoQuestion(lower)) {
            scope.launch {
                try {
                    when (val result = llmClient.answerWithLiveSearch(trimmed)) {
                        is LlmClient.LiveSearchResult.Grounded -> {
                            // Spoken: at most 2 short sentences +
                            // one source name — never full grounded
                            // text, never raw URLs spoken.
                            val sentences = result.answer
                                .split(Regex("""(?<=[.!?])\s+"""))
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                            val spokenBase = if (sentences.isEmpty()) {
                                result.answer.take(280)
                            } else {
                                sentences.take(2).joinToString(" ")
                            }
                            val sourceName = result.sources.firstOrNull()
                                ?.title?.ifBlank { null }
                                ?: result.sources.firstOrNull()?.uri
                                    ?.substringAfter("://", "")
                                    ?.substringBefore("/")
                            val spoken = if (sourceName.isNullOrBlank()) {
                                spokenBase
                            } else {
                                "$spokenBase (source: $sourceName)"
                            }
                            speak(spoken)
                            // Chat: full grounded answer + Sources
                            // list of titles with URLs (tappable
                            // text, not spoken).
                            val chat = buildString {
                                append(result.answer)
                                append("\n\nSources:")
                                result.sources.forEach { src ->
                                    append("\n- ${src.title}: ${src.uri}")
                                }
                            }
                            JarvisStateRepository.addChatMessage("Jarvis", chat)
                        }
                        is LlmClient.LiveSearchResult.Failed -> {
                            // FAILURE HONESTY: never fall back
                            // silently to a training-data answer
                            // presented as current. The fallback is
                            // the existing OPEN_BROWSER background
                            // browse over a Google search URL (the
                            // item-3 search rule), LABELED as
                            // scraped snippets, not a grounded
                            // answer — weaker, and said so.
                            // 1.4.22 (item 4): the HTTP code/body
                            // snippet in result.reason is NEVER
                            // spoken. Spoken = fixed sentence;
                            // chat = the same label minus the
                            // (${reason}) snippet; the reason is
                            // logged as a diagnostic, URLs stripped.
                            val sanitizedReason = result.reason
                                .replace(Regex("https?://\\S+"), "")
                                .take(200)
                            JarvisStateRepository.addDiagnostic(
                                "llm", "LIVE_SEARCH_FAILED", sanitizedReason
                            )
                            val spoken = "Live search nahi mila — Google search page se dekh raha hoon."
                            val chat = "Live search nahi mila. Google search page se dekh raha hoon — ye grounded answer nahi hai, scraped snippets hain."
                            JarvisStateRepository.addChatMessage("Jarvis", chat)
                            speak(spoken)
                            val searchUrl = browserTargetForInput(trimmed)
                            if (searchUrl.isNotBlank()) {
                                executeBrowserLoad(searchUrl, speakSummary = true)
                            }
                        }
                    }
                } catch (e: Exception) {
                    JarvisStateRepository.addLog("Live search error: ${e.message}")
                    // 1.5.1-PRO (L2): even the browser fallback
                    // threw — LLM loop re-plans from here.
                    quickPathFallback(trimmed, "Live search nahi mila — dobara koshish karo ya Browser me kholo.")
                }
            }
            return true
        }
        if (acc != null) {
            // Torch on/off
            if (lower.contains("torch on") || lower.contains("torch jalao") ||
                lower.contains("flashlight on")
            ) {
                val ok = acc.toggleFlashlight(true)
                if (!ok) {
                    // 1.5.1-PRO (L2): torch fail -> LLM re-plan.
                    launchQuickFallback(trimmed, "Torch on nahi ho payi.")
                } else {
                    scope.launch { speak("Torch on kar diya.") }
                }
                return true
            }
            if (lower.contains("torch off") || lower.contains("torch bujhao") ||
                lower.contains("flashlight off")
            ) {
                val ok = acc.toggleFlashlight(false)
                if (!ok) {
                    // 1.5.1-PRO (L2): torch fail -> LLM re-plan.
                    launchQuickFallback(trimmed, "Torch off nahi ho payi.")
                } else {
                    scope.launch { speak("Torch off kar diya.") }
                }
                return true
            }
            // Volume: "volume 70 kar do"
            val volMatch = Regex("volume\\s+(\\d{1,3})").find(lower)
            if (volMatch != null) {
                val pct = volMatch.groupValues[1].toInt().coerceIn(0, 100)
                val ok = acc.setMediaVolumePercent(pct)
                if (!ok) {
                    // 1.5.1-PRO (L2): volume fail -> LLM re-plan.
                    launchQuickFallback(trimmed, "Volume set nahi ho paya.")
                } else {
                    scope.launch { speak("Volume $pct percent kar diya.") }
                }
                return true
            }
            // Media: play/pause/next/previous
            // 1.4.23-PRO Item 2 (C7): controlMediaPlayback's
            // Boolean IS the result — a false return is a failed
            // action, never a success claim. Speak the failure,
            // not "kar diya", when the call reports false.
            // 1.4.25-PRO Step 1B (1f): the Boolean is dispatch-only
            // — a key swallowed with no player session still
            // returns true. Before speaking "kar diya", check
            // AudioManager.isMusicActive() before vs after (same
            // access path as controlMediaPlayback, no new
            // permission). No state change in the commanded
            // direction = an honest line, never "kar diya". The
            // post-check waits briefly so the player can react to
            // the key before its state is read. NEXT / PREVIOUS
            // have no track-change signal observable via
            // AudioManager — the checkable proxy there is that
            // music is still playing afterwards.
            if (lower.contains("music pause") || lower.contains("gaana roko") ||
                lower.contains("song pause")
            ) {
                val preActive = acc.isMusicActive()
                val ok = acc.controlMediaPlayback("PLAY_PAUSE")
                scope.launch {
                    if (!ok) {
                        quickPathFallback(trimmed, "Music pause nahi ho paya.")
                    } else {
                        delay(700)
                        val postActive = acc.isMusicActive()
                        when {
                            preActive && !postActive -> speak("Music pause kar diya.")
                            !preActive -> speak("Music pehle se nahi baj raha tha, isliye pause karne ko kuch nahi tha.")
                            else -> quickPathFallback(trimmed, "Music state badla nahi dikh raha — player khula hai ya nahi check karo.")
                        }
                    }
                }
                return true
            }
            if (lower.contains("music play") || lower.contains("gaana chalao") ||
                lower.contains("song play")
            ) {
                val preActive = acc.isMusicActive()
                val ok = acc.controlMediaPlayback("PLAY_PAUSE")
                scope.launch {
                    if (!ok) {
                        quickPathFallback(trimmed, "Music play nahi ho paya.")
                    } else {
                        delay(700)
                        val postActive = acc.isMusicActive()
                        when {
                            !preActive && postActive -> speak("Music play kar diya.")
                            preActive && postActive -> speak("Music pehle se baj raha tha.")
                            else -> quickPathFallback(trimmed, "Music state badla nahi dikh raha — player khula hai ya nahi check karo.")
                        }
                    }
                }
                return true
            }
            if (lower.contains("next song") || lower.contains("agla gaana") ||
                lower.contains("next gaana")
            ) {
                val ok = acc.controlMediaPlayback("NEXT")
                scope.launch {
                    if (!ok) {
                        quickPathFallback(trimmed, "Agla gaana nahi chal paya.")
                    } else {
                        delay(700)
                        if (acc.isMusicActive()) {
                            speak("Agla gaana chala diya.")
                        } else {
                            quickPathFallback(trimmed, "Music state badla nahi dikh raha — player khula hai ya nahi check karo.")
                        }
                    }
                }
                return true
            }
            if (lower.contains("previous song") || lower.contains("pichla gaana")) {
                val ok = acc.controlMediaPlayback("PREVIOUS")
                scope.launch {
                    if (!ok) {
                        quickPathFallback(trimmed, "Pichla gaana nahi chal paya.")
                    } else {
                        delay(700)
                        if (acc.isMusicActive()) {
                            speak("Pichla gaana chala diya.")
                        } else {
                            quickPathFallback(trimmed, "Music state badla nahi dikh raha — player khula hai ya nahi check karo.")
                        }
                    }
                }
                return true
            }
            // Direct call: "<name> ko call karo/lagao"
            val callMatch = Regex("(.+?)\\s+ko\\s+call\\s+(?:karo|lagao)\\s*$").find(lower)
            if (callMatch != null) {
                val contact = callMatch.groupValues[1].trim()
                if (contact.isNotBlank()) {
                    // C-B13: failure reason is distinguished, never
                    // a blanket "phone book me nahi mila".
                    val (ok, reason) = acc.makePhoneCallWithReason(contact)
                    val msg = when {
                        ok -> "$contact ko call laga raha hoon."
                        reason == "contact_not_found" -> "$contact phone book me nahi mila."
                        reason == "contact_ambiguous" -> "$contact naam ke kai contacts mile, kaun sa wala batao."
                        reason == "permission_denied" -> "Contact dekhne ki permission nahi hai, isliye call nahi lag payi."
                        else -> "$contact ko call nahi lag payi."
                    }
                    if (!ok) {
                        // 1.5.1-PRO (L2): call fail -> LLM re-plan
                        // (contact dhoondhna / poochhna).
                        launchQuickFallback(trimmed, msg)
                    } else {
                        scope.launch { speak(msg) }
                    }
                    return true
                }
            }
            // Alarm: "alarm 7 baje lagao" / "alarm 6:30 lagao"
            val alarmMatch = Regex("alarm\\s+(\\d{1,2})(?::(\\d{2}))?").find(lower)
            if (alarmMatch != null && lower.contains("lagao")) {
                var hour = alarmMatch.groupValues[1].toInt()
                val minute = alarmMatch.groupValues[2].ifBlank { "0" }.toInt()
                if (lower.contains("pm") && hour < 12) hour += 12
                if (hour in 0..23 && minute in 0..59) {
                    val ok = acc.setSystemAlarm(hour, minute, "Jarvis Alarm")
                    // 1.4.25-PRO Step 1C (1e): setSystemAlarm true
                    // only means the ACTION_SET_ALARM intent fired —
                    // never claim the alarm is stored. Intent-fired
                    // wording; the user checks Clock.
                    if (!ok) {
                        // 1.5.1-PRO (L2): alarm fail -> LLM re-plan.
                        launchQuickFallback(trimmed, "Alarm nahi lag paya.")
                    } else {
                        scope.launch { speak("Alarm $hour baj kar $minute minute par lagane ke liye bhej diya — Clock app me check kar lo ki alarm set hua.") }
                    }
                    return true
                }
            }
            // Open app: "<app> kholo" (after YouTube/WhatsApp routines above)
            val openMatch = Regex("(.+?)\\s+(?:kholo|khol\\s+do|open\\s+karo)\\s*$").find(lower)
            if (openMatch != null) {
                val app = openMatch.groupValues[1].trim()
                if (app.isNotBlank() && app.length < 30) {
                    val ok = acc.launchApp(app)
                    // 1.4.25-PRO Step 1C (1e): a fired launch intent
                    // proves nothing — "khol diya" only when the
                    // post-launch snapshot's package is the launched
                    // app's package (resolveAppPackage mirrors the
                    // launchApp match). Anything else = honest
                    // "bhej diya" line, never a done claim.
                    scope.launch {
                        if (!ok) {
                            quickPathFallback(trimmed, "$app nahi khul paya.")
                        } else {
                            delay(1200)
                            val post = acc.captureScreenSnapshot()
                            val expectedPkg = acc.resolveAppPackage(app)
                            if (expectedPkg != null && post.packageName == expectedPkg) {
                                speak("$app khol diya.")
                            } else {
                                // 1.5.1-PRO (L2): launch fired but
                                // the app never came forward — LLM
                                // re-observes instead of stopping.
                                quickPathFallback(trimmed, "$app kholne ke liye bhej diya — screen par check karo.")
                            }
                        }
                    }
                    return true
                }
            }
        }
        return false
    }

    /**
     * Entry point for any voice or text command from the user.
     */
    fun handleUserCommand(rawCommand: String) {
        val trimmed = rawCommand.trim()
        if (trimmed.isEmpty()) return
        // 1.15.0 memory-loss fix (F2): restore before ANY chat line is
        // added or persisted — this is the single choke point for all
        // user commands, so no first-turn path can overwrite history.
        ensureChatRestored()

        // 1.4.4 startup guard: this is the single choke point for
        // ALL user commands (mic turn, typed text, wake word,
        // routine button) — once one arrives, agent HOME/BACK
        // navigation is allowed for the rest of this session.
        // The command TEXT is never logged (privacy).
        com.jarvis.autonomous.data.LaunchSession.markUserCommand()
        JarvisStateRepository.addDiagnostic(
            "cmd", "USER_CMD", "User command received (text never logged)."
        )

        // Check for immediate stop command
        // 1.4.3: transliterate ONLY the matching variable so a
        // Devanagari transcript ("रुक", "दिखाओ") matches the
        // Latin keyword lists. `trimmed` (original) still feeds
        // display + LLM paths below — never the transliteration.
        val lower = TurnDetector.transliterateDevanagariKeywords(trimmed.lowercase())
        if (lower in listOf("stop", "ruk ja", "ruko", "cancel", "jarvis stop", "bas kar")) {
            // 1.4.5 (item 5): stop SPEECH first, then cancel the
            // task, then confirm. The old branch cancelled the
            // task but never called stopSpeaking, so speech kept
            // playing after a voice "stop". Barge-in keywords are
            // handled separately (mid-speech path); this branch
            // covers the command-turn case and the barge-in-missed
            // case. No double-fire: a barge-in with a bare keyword
            // seeds nothing, and with a remainder it seeds the
            // remainder, never "stop".
            onStopSpeaking?.invoke()
            val hadExecutorCommand = cancelActiveTask("Stopped on voice command.")
            // 1.8.0: when an Executor command was running, its
            // CANCELLED result speaks this same line via the bus
            // listener — speaking it here too would double it.
            if (!hadExecutorCommand) {
                scope.launch { speak("Theek hai bhai, ruk gaya.") }
            }
            return
        }

        // Interrupted background browse (checked once per process):
        // ask to retry, never auto-load and never invent content.
        // The prompt is for the NEXT command, so this same call
        // must not consume the pending approval as its own answer.
        var justSetInterrupted = false
        if (!interruptedBrowseChecked) {
            interruptedBrowseChecked = true
            val interruptedUrl = JarvisBackgroundBrowser.takeInterruptedBrowse(context)
            if (interruptedUrl != null) {
                pendingBrowserApprovalUrl = interruptedUrl
                pendingBrowserShowPage = false
                justSetInterrupted = true
                scope.launch { speak("Browsing ruk gaya, dobara koshish karun?") }
            }
        }

        // Pending browser approval: the immediately following
        // command is the answer. Affirmative -> load it; a bare
        // negation ("nahi") declines and stops (A-C5) instead of
        // being misread as a new command; anything else clears the
        // proposal and the new command runs.
        val pendingUrl = pendingBrowserApprovalUrl
        if (pendingUrl != null && !justSetInterrupted) {
            pendingBrowserApprovalUrl = null
            val openExternal = pendingBrowserOpenExternal
            pendingBrowserOpenExternal = false
            val negationOnly = replyTokens(lower).size <= 2 && containsNegation(lower)
            if (negationOnly) {
                pendingBrowserShowPage = false
                scope.launch { speak("Theek hai, page nahi khola.") }
                return
            }
            if (isAffirmativeReply(lower)) {
                approvedBrowserUrls.add(pendingUrl)
                val showPage = pendingBrowserShowPage
                pendingBrowserShowPage = false
                activeTaskJob?.cancel()
                activeTaskJob = scope.launch {
                    if (openExternal) {
                        val ok = JarvisAccessibilityService.instance?.openUrlOrDeepLink(pendingUrl) ?: false
                        JarvisStateRepository.addChatMessage("Jarvis", "Link khola: $pendingUrl (success=$ok)")
                    } else if (showPage && onShowBrowserPage != null) {
                        onShowBrowserPage?.invoke(pendingUrl)
                        JarvisStateRepository.addChatMessage("Jarvis", "Page dikha raha hoon: $pendingUrl")
                    } else {
                        val summary = executeBrowserLoad(pendingUrl, speakSummary = true)
                        JarvisStateRepository.addLog(summary)
                    }
                    JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Ready for next command")
                    speak("Aur kya karna hai?")
                }
                return
            } else {
                pendingBrowserShowPage = false
            }
        }

        // Check if command matches any custom Voice Routine macro
        // (user's own macros win over built-in no-AI routines).
        val matchedRoutine = prefs.getRoutines().firstOrNull { routine ->
            lower.contains(routine.triggerPhrase.lowercase())
        }
        if (matchedRoutine == null) {
            // 1.9.0 (v2, DESIGN §5.9): explain-once / forget / list
            // turns are meta-turns about memory, not work — they
            // return before any dispatch (and before learned replay,
            // so a memory turn can never double-execute as work).
            // Macros above still win over everything.
            if (handleMemoryTurn(trimmed, lower)) return
            // Memory-first (1.4.0): a learned routine replays
            // before any no-AI routine or LLM planning. On ANY
            // mismatch the replay aborts into the LLM loop inside
            // tryLearnedReplay — it never silently stops.
            if (tryLearnedReplay(trimmed)) return
            // No-AI routines (YouTube play-first, WhatsApp send, ...):
            // deterministic steps, no LLM call, with ask-before-play/send.
            if (tryNoAiRoutine(lower, trimmed)) return
        }
        val effectiveGoal = if (matchedRoutine != null) {
            JarvisStateRepository.addLog("Matched Custom Routine '${matchedRoutine.triggerPhrase}'")
            matchedRoutine.actionPrompt
        } else {
            trimmed
        }

        // I1 (1.7.1): on-device document recall — local search, no
        // network. Runs before the agent-mode check: an offline
        // strong match answers straight from the phone, with no LLM
        // call and no planner. Online, recalled snippets join the
        // memory-first context below (labelled, context only).
        val docHits = runCatching { documentMemory.search(effectiveGoal) }
            .getOrDefault(emptyList())
        val recallHits = docHits.filter {
            DocumentSearch.isRecallHit(it.score, it.titleMatched)
        }
        val docContext = DocumentText.buildDocContext(recallHits)
        if (!isOnline() && tryDocumentRecall(effectiveGoal, docHits)) return

        // 1.8.0 Talker/Executor: the Talker classifies every turn
        // BEFORE any LLM call, network, or phone action. The ack is
        // templated (no LLM) and is posted + spoken before the
        // Executor starts, so the user never waits in silence.
        // Routines / learned replays already returned above — this
        // block only sees turns that need the loop, a plan, or chat.
        val turnReceivedMs = System.currentTimeMillis()
        val talkerDecision = TalkerDispatcher.classify(
            effectiveGoal, talkerBus.pendingCount() > 0,
            // Devanagari turns transliterate to Latin keywords first
            // (same transliteration the stop check uses) so "गीथब"
            // still routes to EXECUTE_PLAN.
            TurnDetector.transliterateDevanagariKeywords(effectiveGoal.lowercase())
        )
        when (talkerDecision) {
            TalkerDispatcher.TalkerDecision.STATUS_QUERY -> {
                val cmd = talkerBus.pendingCommands().firstOrNull()
                // 1.9.0 (v2, DESIGN §3.5): status answers read the
                // freshest structured progress first — never the
                // stale currentStepInfo string. Only when no
                // progress exists yet (job just started) does the
                // answer fall back to the step text.
                val progress = cmd?.let { talkerBus.lastProgress(it.id) }
                val nowMs = turnReceivedMs
                val stalled = cmd != null && progress != null &&
                    TalkerProgressPolicy.isStalled(progress.emittedAtMs, nowMs)
                if (stalled && cmd != null && stalledDiagnosticLogged.add(cmd.id)) {
                    JarvisStateRepository.addDiagnostic(
                        "talker", "STALLED",
                        "No progress for ${cmd.id} in 5 min — job still running."
                    )
                }
                val line = if (progress != null && cmd != null) {
                    TalkerReplyPolicy.statusLine(
                        cmd, progress, nowMs - cmd.issuedAtMs, stalled, nowMs
                    )
                } else {
                    val step = JarvisStateRepository.currentStepInfo.value
                        .takeIf { it.isNotBlank() }
                    TalkerReplyPolicy.statusLine(
                        cmd, step,
                        if (cmd != null) nowMs - cmd.issuedAtMs else 0L
                    )
                }
                JarvisStateRepository.addChatMessage("Jarvis", line)
                persistChat()
                scope.launch { speak(line, alreadyPostedToChat = true) }
                return
            }
            TalkerDispatcher.TalkerDecision.CANCEL_TASK -> {
                // "cancel karo" / "ruk ja" phrasings the exact-match
                // stop branch above does not catch.
                onStopSpeaking?.invoke()
                val hadExecutorCommand = cancelActiveTask("Stopped on voice command.")
                if (!hadExecutorCommand) {
                    scope.launch { speak("Theek hai bhai, ruk gaya.") }
                }
                return
            }
            TalkerDispatcher.TalkerDecision.EXECUTE_PLAN,
            TalkerDispatcher.TalkerDecision.EXECUTE_LOOP -> {
                val workKind = if (talkerDecision == TalkerDispatcher.TalkerDecision.EXECUTE_PLAN) {
                    ExecutorWorkKind.PLAN
                } else {
                    ExecutorWorkKind.LOOP
                }
                // 1.9.0 (v2, DESIGN §5.3): index-only pattern /
                // instruction lookup BEFORE submit — guidance rides
                // in params (a hint, never an order). The lookup is
                // measured: past 100 ms the ack is never delayed for
                // it (the diagnostic records the breach instead).
                val lookupStartMs = System.currentTimeMillis()
                val guidance = runCatching { lookupGuidance(effectiveGoal, workKind) }.getOrNull()
                val lookupMs = System.currentTimeMillis() - lookupStartMs
                if (lookupMs > 100) {
                    JarvisStateRepository.addDiagnostic(
                        "talker", "PATTERN_LOOKUP_MS",
                        "Pattern lookup took ${lookupMs}ms (> 100ms budget) — ack was not delayed."
                    )
                }
                val cmd = ExecutorCommand(
                    kind = ExecutorCommandKind.EXECUTE_TASK,
                    workKind = workKind,
                    goal = effectiveGoal,
                    params = guidance?.params ?: emptyMap()
                )
                // Validate + submit BEFORE posting the ack: a
                // rejected command must never be acknowledged.
                when (talkerBus.submit(cmd)) {
                    TalkerExecutorBus.SubmitResult.INVALID -> {
                        scope.launch {
                            speak(TalkerReplyPolicy.invalidCommandLine(cmd.validate()))
                        }
                        return
                    }
                    TalkerExecutorBus.SubmitResult.DUPLICATE_ID -> {
                        scope.launch { speak("Ye kaam pehle se chal raha hai — dobara bhejne ki zaroorat nahi.") }
                        return
                    }
                    TalkerExecutorBus.SubmitResult.ACCEPTED -> Unit
                }
                supersedeRunningCommand(cmd.goal)
                lastDeliveredCommand = cmd
                lastExecutorWorkKind = workKind
                guidance?.patternId?.let { patternId ->
                    appliedPatternByCommand[cmd.id] = patternId
                    lastAppliedPatternId = patternId
                }
                lastAppliedInstructionIds = guidance?.instructionIds ?: emptyList()
                for (instructionId in lastAppliedInstructionIds) {
                    runCatching { learnedPatternStore.recordInstructionUse(instructionId) }
                }
                val ack = TalkerReplyPolicy.ackFor(cmd)
                JarvisStateRepository.addChatMessage("You", effectiveGoal)
                JarvisStateRepository.addChatMessage("Jarvis", ack)
                persistChat()
                executorTurnAlreadyPosted = true
                scope.launch { speak(ack, alreadyPostedToChat = true) }
                // 1.9.0 (v2, DESIGN §5.4): no silent application —
                // a chat-only note right after the ack (never spoken:
                // the ack is already the spoken line for this turn).
                guidance?.noteLines?.forEach { note ->
                    JarvisStateRepository.addChatMessage("Jarvis", note)
                }
                if (!guidance?.noteLines.isNullOrEmpty()) persistChat()
                JarvisStateRepository.addDiagnostic(
                    "talker", "ACK_MS",
                    "Talker ack posted ${System.currentTimeMillis() - turnReceivedMs}ms after turn received."
                )
                if (workKind == ExecutorWorkKind.PLAN) {
                    launchExecutorCommand(cmd) {
                        // M-I-2 (1.14.0): recalled doc context rides
                        // the planner prompt (context only) — the
                        // EXECUTE_LOOP branch already had it via
                        // buildMemoryContext; EXECUTE_PLAN did not.
                        planExecutor.startNewPlan(
                            effectiveGoal,
                            guidance?.patternHint ?: "",
                            guidance?.instructionHint ?: "",
                            docContext
                        )
                    }
                } else {
                    val memoryContext = buildMemoryContext(
                        effectiveGoal, docContext, recallHits.size
                    )
                    // 1.9.0 (v2, DESIGN §5.4): loop guidance rides in
                    // memoryContext as labelled context (same shape
                    // as I1 doc snippets), never as a command.
                    val loopContextParts = ArrayList<String>()
                    if (memoryContext.isNotBlank()) loopContextParts.add(memoryContext)
                    if (!guidance?.patternHint.isNullOrBlank()) {
                        loopContextParts.add("(Seekha hua tareeka: ${guidance.patternHint})")
                    }
                    if (!guidance?.instructionHint.isNullOrBlank()) {
                        loopContextParts.add("(Yaad rakha: ${guidance.instructionHint})")
                    }
                    launchExecutorCommand(cmd) {
                        runAutonomousLoop(effectiveGoal, loopContextParts.joinToString("\n"))
                    }
                }
                return
            }
            TalkerDispatcher.TalkerDecision.TALK -> {
                scope.launch {
                    // M-I-2 (1.14.0): the Talker's on-device local
                    // reply now sees recalled doc context too (the
                    // cloud-loop fallback already had it via
                    // launchLoopTurn). Blank = no recall, prompt
                    // unchanged.
                    val answered = tryTalkerLocalReply(effectiveGoal, docContext)
                    if (!answered) {
                        val running = talkerBus.pendingCommands().firstOrNull()
                        if (running != null) {
                            // Executor busy: NEVER launch the cloud
                            // loop here — it would cancel the work.
                            // The Talker says so plainly instead.
                            val busy = TalkerReplyPolicy.busyLine(running.goal)
                            JarvisStateRepository.addChatMessage("Jarvis", busy)
                            persistChat()
                            speak(busy, alreadyPostedToChat = true)
                        } else {
                            launchLoopTurn(effectiveGoal, docContext, recallHits.size)
                        }
                    }
                }
                return
            }
        }
    }

    /**
     * 1.9.0 (v2, DESIGN §5.8): explain-once / forget / list turns.
     * Returns true when the turn was a memory meta-turn and is fully
     * handled (the caller must return). Voice + chat only — no new
     * screen. A stored instruction is never a VoiceRoutine and never
     * rewrites the user's goal (macro-collision guard); it is a
     * standing preference applied as a hint.
     */
    private fun handleMemoryTurn(trimmed: String, lower: String): Boolean {
        // A pending "kaun sa bhool jaun?" number answer.
        val pending = pendingForgetSelection
        if (pending != null) {
            pendingForgetSelection = null
            val number = trimmed.trim().toIntOrNull()
            if (number != null && number in 1..pending.size) {
                val (kind, id) = pending[number - 1]
                val deleted = if (kind == "pattern") {
                    learnedPatternStore.deletePattern(id)
                } else {
                    learnedPatternStore.deleteInstruction(id)
                }
                scope.launch {
                    val line = if (deleted) {
                        "Bhool gaya — ab yaad nahi rakhunga."
                    } else {
                        "Wo abhi list me nahi mila — dobara bolo."
                    }
                    JarvisStateRepository.addChatMessage("Jarvis", line)
                    persistChat()
                    speak(line, alreadyPostedToChat = true)
                }
                return true
            }
            // Not a number: the pending selection is dropped and the
            // turn falls through to the normal checks below.
        }

        if (TalkerDispatcher.isListMemoryTurn(lower)) {
            scope.launch {
                val patterns = runCatching { learnedPatternStore.listPatterns() }.getOrDefault(emptyList())
                val instructions = runCatching { learnedPatternStore.listInstructions() }.getOrDefault(emptyList())
                val lines = StringBuilder("Yaad rakha hua:")
                var n = 0
                for (instruction in instructions.filter { it.enabled }.take(10)) {
                    n++
                    lines.append("\n$n. ${instruction.text.take(120)}")
                }
                for (pattern in patterns.filter { it.enabled }.take(10 - n.coerceAtMost(10))) {
                    n++
                    lines.append("\n$n. Tareeka: ${pattern.title.take(120)}")
                }
                if (n == 0) lines.append(" abhi kuch nahi — kuch yaad rakhne ko bolo to yaad rakh lunga.")
                JarvisStateRepository.addChatMessage("Jarvis", lines.toString())
                persistChat()
                // Listing 10 items by voice is noise — spoken line
                // is only the pointer to chat.
                speak("Chat me list bhej di hai.", alreadyPostedToChat = true)
            }
            return true
        }

        if (TalkerDispatcher.isReenablePatternTurn(lower)) {
            scope.launch {
                val target = findPatternByTurn(lower, enabledOnly = false)
                    ?.takeIf { !it.enabled }
                if (target == null) {
                    val line = "Koi band kiya hua tareeka nahi mila — kaun sa phir se use karun?"
                    JarvisStateRepository.addChatMessage("Jarvis", line)
                    persistChat()
                    speak(line, alreadyPostedToChat = true)
                } else {
                    learnedPatternStore.setPatternEnabled(target.id, true)
                    val line = "Theek hai — '${target.title.take(60)}' phir se use karunga."
                    JarvisStateRepository.addChatMessage("Jarvis", line)
                    persistChat()
                    speak(line, alreadyPostedToChat = true)
                }
            }
            return true
        }

        if (TalkerDispatcher.isDisablePatternTurn(lower)) {
            scope.launch {
                val targetId = lastAppliedPatternId
                    ?: findPatternByTurn(lower, enabledOnly = true)?.id
                if (targetId == null) {
                    val line = "Abhi koi tareeka apply nahi hua — kaun sa band karun?"
                    JarvisStateRepository.addChatMessage("Jarvis", line)
                    persistChat()
                    speak(line, alreadyPostedToChat = true)
                } else {
                    // Failure-after-apply: confidence −0.2 (and
                    // auto-disable after 2 consecutive failures).
                    learnedPatternStore.recordFailureAfterApply(targetId)
                    penalisedPatternIds.add(targetId)
                    if (TalkerDispatcher.isPermanentDisableTurn(lower)) {
                        learnedPatternStore.setPatternEnabled(targetId, false)
                    }
                    val line = if (TalkerDispatcher.isPermanentDisableTurn(lower)) {
                        "Theek hai, ye tareeka dobara use nahi karunga."
                    } else {
                        "Theek hai, ye tareeka abhi ke liye band kar diya."
                    }
                    JarvisStateRepository.addChatMessage("Jarvis", line)
                    persistChat()
                    speak(line, alreadyPostedToChat = true)
                }
            }
            return true
        }

        if (TalkerDispatcher.isForgetTurn(lower)) {
            scope.launch {
                if (TalkerDispatcher.isForgetPatternTurn(lower)) {
                    val target = findPatternByTurn(lower, enabledOnly = true)
                    if (target == null) {
                        val candidates = runCatching { learnedPatternStore.listPatterns() }
                            .getOrDefault(emptyList()).filter { it.enabled }.take(5)
                        if (candidates.isEmpty()) {
                            val line = "Koi seekha hua tareeka yaad nahi hai — bhoolne ko kuch nahi."
                            JarvisStateRepository.addChatMessage("Jarvis", line)
                            persistChat()
                            speak(line, alreadyPostedToChat = true)
                        } else {
                            val lines = StringBuilder("Kaun sa tareeka bhool jaun? Number bolo:")
                            candidates.forEachIndexed { i, meta ->
                                lines.append("\n${i + 1}. ${meta.title.take(100)}")
                            }
                            pendingForgetSelection = candidates.map { "pattern" to it.id }
                            JarvisStateRepository.addChatMessage("Jarvis", lines.toString())
                            persistChat()
                            speak("Kaun sa bhool jaun? Number bolo.", alreadyPostedToChat = true)
                        }
                    } else {
                        learnedPatternStore.deletePattern(target.id)
                        val line = "Bhool gaya — '${target.title.take(60)}' ab yaad nahi rakhunga."
                        JarvisStateRepository.addChatMessage("Jarvis", line)
                        persistChat()
                        speak(line, alreadyPostedToChat = true)
                    }
                } else {
                    val target = findInstructionByTurn(lower)
                    if (target == null) {
                        val candidates = runCatching { learnedPatternStore.listInstructions() }
                            .getOrDefault(emptyList()).filter { it.enabled }.take(5)
                        if (candidates.isEmpty()) {
                            val line = "Koi yaad rakhi hui baat nahi hai — bhoolne ko kuch nahi."
                            JarvisStateRepository.addChatMessage("Jarvis", line)
                            persistChat()
                            speak(line, alreadyPostedToChat = true)
                        } else {
                            val lines = StringBuilder("Kaun sa bhool jaun? Number bolo:")
                            candidates.forEachIndexed { i, meta ->
                                lines.append("\n${i + 1}. ${meta.text.take(100)}")
                            }
                            pendingForgetSelection = candidates.map { "instruction" to it.id }
                            JarvisStateRepository.addChatMessage("Jarvis", lines.toString())
                            persistChat()
                            speak("Kaun sa bhool jaun? Number bolo.", alreadyPostedToChat = true)
                        }
                    } else {
                        learnedPatternStore.deleteInstruction(target.id)
                        val line = "Bhool gaya — '${target.text.take(60)}' ab yaad nahi rakhunga."
                        JarvisStateRepository.addChatMessage("Jarvis", line)
                        persistChat()
                        speak(line, alreadyPostedToChat = true)
                    }
                }
            }
            return true
        }

        if (TalkerDispatcher.isBareExplainOnceTurn(lower)) {
            scope.launch {
                val line = "Kya yaad rakhun? Ek line me bolo."
                JarvisStateRepository.addChatMessage("Jarvis", line)
                persistChat()
                speak(line, alreadyPostedToChat = true)
            }
            return true
        }

        if (TalkerDispatcher.isLongExplainOnceTurn(lower)) {
            scope.launch {
                val line = "Ye lambi baat hai — document ki tarah save kar dun?"
                JarvisStateRepository.addChatMessage("Jarvis", line)
                persistChat()
                speak(line, alreadyPostedToChat = true)
            }
            return true
        }

        val instructionText = TalkerDispatcher.extractInstruction(lower)
        if (instructionText != null) {
            scope.launch {
                // Macro-collision guard (DESIGN §5.8): an instruction
                // is never stored as a VoiceRoutine and never rewrites
                // the goal. If the text already IS a macro trigger,
                // the macro wins.
                val collidesWithMacro = prefs.getRoutines().any { routine ->
                    routine.triggerPhrase.lowercase() == instructionText.lowercase()
                }
                if (collidesWithMacro) {
                    val line = "Ye pehle se ek routine hai — usi se hoga."
                    JarvisStateRepository.addChatMessage("Jarvis", line)
                    persistChat()
                    speak(line, alreadyPostedToChat = true)
                    return@launch
                }
                val scopeKeywords = MemoryKeywords.extract("$instructionText $trimmed")
                    .take(LearnedPatternPolicy.MAX_KEYWORDS)
                val appliesTo = instructionAppliesTo(lower)
                val saved = runCatching {
                    learnedPatternStore.saveInstruction(instructionText, scopeKeywords, appliesTo)
                }.getOrNull()
                if (saved == null) {
                    val line = "Yaad nahi rakh paya — dobara bolo."
                    JarvisStateRepository.addChatMessage("Jarvis", line)
                    persistChat()
                    speak(line, alreadyPostedToChat = true)
                } else {
                    val spoken = instructionText.take(80)
                    val line = "Yaad rakha — aage se $spoken."
                    JarvisStateRepository.addChatMessage(
                        "Jarvis",
                        "$line\nScope: ${scopeKeywords.take(5).joinToString(", ")}."
                    )
                    persistChat()
                    speak(line, alreadyPostedToChat = true)
                }
            }
            return true
        }

        return false
    }

    /** Best-matching enabled (or any, when [enabledOnly] is false)
     *  pattern for a forget/disable turn, by keyword overlap (score ≥ 3). */
    private fun findPatternByTurn(lower: String, enabledOnly: Boolean): LearnedPatternMeta? {
        val keywords = MemoryKeywords.extract(lower)
        val patterns = runCatching { learnedPatternStore.listPatterns() }.getOrDefault(emptyList())
        var best: LearnedPatternMeta? = null
        var bestScore = -1
        for (meta in patterns) {
            if (enabledOnly && !meta.enabled) continue
            val score = LearnedPatternPolicy.matchScore(
                queryKeywords = keywords,
                patternKeywords = meta.triggerKeywords,
                workKindMatches = false,
                phraseContainsSignature = lower.contains(meta.triggerSignature.lowercase())
            )
            if (score >= LearnedPatternPolicy.MATCH_MIN_SCORE && score > bestScore) {
                bestScore = score
                best = meta
            }
        }
        return best
    }

    /** Best-matching enabled instruction for a forget turn (score ≥ 3). */
    private fun findInstructionByTurn(lower: String): LearnedInstructionMeta? {
        val keywords = MemoryKeywords.extract(lower)
        val instructions = runCatching { learnedPatternStore.listInstructions() }
            .getOrDefault(emptyList())
        var best: LearnedInstructionMeta? = null
        var bestScore = -1
        for (meta in instructions) {
            if (!meta.enabled) continue
            val score = LearnedPatternPolicy.matchScore(
                queryKeywords = keywords,
                patternKeywords = meta.scopeKeywords,
                workKindMatches = false,
                phraseContainsSignature = lower.contains(meta.text.lowercase())
            )
            if (score >= LearnedPatternPolicy.MATCH_MIN_SCORE && score > bestScore) {
                bestScore = score
                best = meta
            }
        }
        return best
    }

    /** Work kind(s) an instruction applies to, from the turn's own
     *  words (DESIGN §5.8). Default: both kinds. */
    private fun instructionAppliesTo(lower: String): List<String> {
        val planWords = listOf("website", "github", "supabase", "vercel", "deploy", "agent mode", "project")
        val loopWords = listOf("whatsapp", "bhej", "message", "youtube", "torch", "volume")
        val isPlan = planWords.any { lower.contains(it) }
        val isLoop = loopWords.any { lower.contains(it) }
        return when {
            isPlan && !isLoop -> listOf("PLAN")
            isLoop && !isPlan -> listOf("LOOP")
            else -> listOf("PLAN", "LOOP")
        }
    }

    /**
     * Memory-first replay (1.4.0). Returns true only when a
     * learned routine matched and its replay job started. Steps
     * are the verified, safe subset the engine records (see
     * recordLoopStep): anything else is a mismatch and falls
     * back to the LLM loop. Sensitive steps ask first when
     * prefs.confirmSensitiveActions is on; a no / no-reply
     * aborts replay into the LLM loop, never skips silently.
     * Offline: local steps run, the first network step speaks
     * the offline warning and stops (local work only — send /
     * pay / delete steps are never queued for later).
     */
    private fun tryLearnedReplay(trimmed: String): Boolean {
        val routine = learnedRoutineStore.findRoutine(trimmed) ?: return false
        if (routine.steps.isEmpty()) return false
        JarvisStateRepository.addLog("Learned routine matched: '${routine.trigger}' (${routine.steps.size} steps)")
        activeTaskJob?.cancel()
        clearTaskScopedState()
        activeTaskJob = scope.launch {
            val acc = JarvisAccessibilityService.instance
            for (step in routine.steps) {
                if (!isOnline() && learnedRoutineStore.isNetworkDependent(step.action)) {
                    speak("Internet nahi hai — is step ke liye network chahiye, isliye replay yahin rok raha hoon. Net nahi hai, isliye yahin ruk raha hoon — jo steps ho gaye hain wo upar dikh rahe hain, baaki net aane par dobara bolo.")
                    clearTaskScopedState()
                    JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Replay stopped offline")
                    return@launch
                }
                if (prefs.confirmSensitiveActions &&
                    learnedRoutineStore.isConfirmationRequired(step.action, step.target)
                ) {
                    // A-C5: an explicit decline ABORTS the task
                    // entirely — it must NOT fall back to the LLM
                    // loop (only a step-mismatch may re-plan).
                    val reply = askUserVoice("Ye step sensitive hai (${step.action}). Kar dun?")
                    if (reply == null || !isAffirmativeReply(reply.lowercase())) {
                        JarvisStateRepository.addLog("Learned replay: sensitive step declined — aborting task")
                        speak("Theek hai, kaam rok diya.")
                        clearTaskScopedState()
                        JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Declined — task stopped")
                        return@launch
                    }
                }
                // 1.4.25-PRO Step 1C (1d): dispatch-only ok is not
                // evidence. For screen-changing UI steps, the
                // post-step screen must have CHANGED vs the pre-step
                // snapshot (same formatted-tree hash / package /
                // element-set comparison as verifyStepEvidence,
                // 1B/1c) — otherwise this is a mismatch and the LLM
                // loop re-plans. Screen-invariant steps (WAIT,
                // SAVE_MEMORY, FLASHLIGHT, VOLUME, MEDIA_CONTROL)
                // keep their existing ok semantics: their ok is a
                // real system call or trivially true by construction,
                // and no screen change is expected from them.
                val preStepSnapshot = if (replayStepNeedsScreenChange(step.action)) acc?.captureScreenSnapshot() else null
                val ok = executeLearnedStep(step, acc)
                if (!ok) {
                    JarvisStateRepository.addLog("Learned replay mismatch at ${step.action} — falling back to LLM loop")
                    runAutonomousLoop(trimmed)
                    return@launch
                }
                if (preStepSnapshot != null) {
                    // Brief settle so a cold-starting app can come
                    // to the foreground before the post snapshot.
                    // A still-unchanged screen is a mismatch, never
                    // a success — the LLM loop re-observes.
                    delay(800)
                    val postStepSnapshot = acc?.captureScreenSnapshot()
                    if (postStepSnapshot == null || !snapshotsDiffer(preStepSnapshot, postStepSnapshot)) {
                        JarvisStateRepository.addLog("Learned replay no screen change at ${step.action} — falling back to LLM loop")
                        runAutonomousLoop(trimmed)
                        return@launch
                    }
                }
                if (step.waitMs > 0) delay(step.waitMs)
            }
            // 1.5.3-PRO: full replay passed — count the use.
            learnedRoutineStore.recordSkillUse(routine.id)
            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Learned replay done")
            speak("Ho gaya — seekha hua kaam dobara kar diya. Aur kya karna hai?")
        }
        return true
    }

    private suspend fun executeLearnedStep(
        step: LearnedStep,
        acc: JarvisAccessibilityService?
    ): Boolean {
        return when (step.action.uppercase()) {
            "OPEN_APP" -> acc?.launchApp(step.target) ?: false
            "SCROLL" -> acc?.scrollScreen(step.target.ifBlank { "DOWN" }) ?: false
            "NAVIGATE" -> acc?.performSystemNavigation(step.target) ?: false
            "MEDIA_CONTROL" -> acc?.controlMediaPlayback(step.target) ?: false
            "FLASHLIGHT" -> acc?.toggleFlashlight(step.target == "true") ?: false
            "VOLUME" -> acc?.setMediaVolumePercent(step.target.toIntOrNull() ?: 50) ?: false
            "WAIT" -> true
            "YOUTUBE_SEARCH" -> acc?.searchYouTube(step.input ?: step.target) ?: false
            "SAVE_MEMORY" -> {
                prefs.addMemory(step.input ?: step.target)
                true
            }
            else -> false
        }
    }

    /**
     * 1.4.25-PRO Step 1C (1d): replay steps whose nature is
     * screen-changing — the replay must observe a screen change
     * after them. WAIT / SAVE_MEMORY / FLASHLIGHT / VOLUME /
     * MEDIA_CONTROL are screen-invariant by nature and keep their
     * dispatch / system-call ok.
     */
    private fun replayStepNeedsScreenChange(action: String): Boolean =
        action.uppercase() in setOf("OPEN_APP", "SCROLL", "NAVIGATE", "YOUTUBE_SEARCH")

    /**
     * 1.4.25-PRO Step 1C (1d): the Step 1B screen-change
     * comparison, shared by verifyStepEvidence and the replay
     * check — formatted-tree hash, owning package, or element set.
     */
    private fun snapshotsDiffer(a: ScreenSnapshot, b: ScreenSnapshot): Boolean =
        a.formattedTree.hashCode() != b.formattedTree.hashCode() ||
            a.packageName != b.packageName ||
            a.elements != b.elements

    /**
     * Records ONLY the safe, repeatable subset of a loop step
     * into the current routine buffer — and ONLY when the step
     * actually succeeded (1.4.25-PRO Step 2A, H4 ok-filter:
     * `ok == false` returns immediately, so a failed
     * intermediate step can never ride along into a routine
     * that a later VERIFIED exit persists). Send / pay / delete /
     * type / tap / call / project steps are never recorded:
     * their targets (contacts, message text, node ids, file
     * content) must never become a replayable template, and
     * recordStep() itself rejects sensitive-looking values as
     * a backstop. finishRoutine() persists the buffer only for
     * a verified, completed task; failures clear it.
     * NOTE: an AMBIGUOUS-continue step whose action returned
     * ok stays buffered (acceptable — the later VERIFIED exit
     * re-checks evidence before anything persists); do NOT add
     * a second filter here.
     */
    private fun recordLoopStep(d: AgentDecision, ok: Boolean) {
        if (!ok) return
        when (d.action) {
            "OPEN_APP" -> learnedRoutineStore.recordStep("OPEN_APP", d.appName)
            "SCROLL" -> learnedRoutineStore.recordStep("SCROLL", d.direction)
            "NAVIGATE" -> learnedRoutineStore.recordStep("NAVIGATE", d.navTarget)
            "MEDIA_CONTROL" -> learnedRoutineStore.recordStep("MEDIA_CONTROL", d.text)
            "FLASHLIGHT" -> learnedRoutineStore.recordStep("FLASHLIGHT", d.enableFlag.toString())
            "VOLUME" -> learnedRoutineStore.recordStep("VOLUME", d.volumePercent.toString())
            "WAIT" -> learnedRoutineStore.recordStep("WAIT", "", waitMs = 1000L)
            "YOUTUBE_SEARCH" -> learnedRoutineStore.recordStep("YOUTUBE_SEARCH", "", input = d.text)
            "SAVE_MEMORY" -> learnedRoutineStore.recordStep("SAVE_MEMORY", "", input = d.memoryNote)
            else -> Unit
        }
    }

    /**
     * Global-learning upload (1.4.0, gated, best-effort, never
     * blocking): only when a licence is activated AND the
     * opt-out is off. Payload is ONLY the generic action
     * sequence (action names, no targets, no inputs, no
     * utterances, no chat, no whiteboard images) PLUS the
     * licence auth the server requires (licence_key +
     * device_id, path (b) of /api/patterns — without them the
     * server returns 401). A blank key skips the upload
     * entirely (never send an empty-key request). A failed
     * upload is logged, never retried silently and never
     * shown as success.
     */
    private fun maybeUploadLearnedPattern(routine: LearnedRoutine) {
        scope.launch(Dispatchers.IO) {
            try {
                val lp = context.getSharedPreferences(LicencePrefs.PREFS_NAME, Context.MODE_PRIVATE)
                val activated = lp.getBoolean(LicencePrefs.KEY_ACTIVATED, false)
                val optOut = lp.getBoolean(LicencePrefs.KEY_OPT_OUT, false)
                if (!activated || optOut) return@launch
                val licenceKey = lp.getString(LicencePrefs.KEY_LICENCE, "") ?: ""
                if (licenceKey.isBlank()) {
                    JarvisStateRepository.addLog("Global-learning upload skipped: licence key blank.")
                    return@launch
                }
                val deviceId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                ) ?: ""
                if (deviceId.isBlank()) {
                    JarvisStateRepository.addLog("Global-learning upload skipped: device id unavailable.")
                    return@launch
                }
                // C-B1: map engine actions to the SERVER enums
                // (lib/patterns.ts ALLOWED_ACTIONS / TARGET_KINDS).
                // The old code sent target_kind "generic", which the
                // server rejects, and no template_hash, which the
                // server requires — both are fixed here. Steps with
                // no server-enum mapping are dropped, never invented.
                val mapped = mutableListOf<Pair<String, String>>()
                for (step in routine.steps) {
                    mapped.addAll(mapLearnedStepToServer(step.action))
                }
                if (mapped.isEmpty()) {
                    JarvisStateRepository.addLog("Global-learning upload skipped: no server-mappable steps.")
                    return@launch
                }
                val stepsJson = JSONArray()
                for ((action, targetKind) in mapped) {
                    val obj = JSONObject()
                    obj.put("action", action)
                    obj.put("target_kind", targetKind)
                    stepsJson.put(obj)
                }
                // C-B1: template_hash exactly as the server computes
                // it — sha256 hex of canonical JSON
                // {"steps":[{"action":...,"target_kind":...},...]}
                // (key order action, target_kind; no spaces).
                val canonical = buildString {
                    append("{\"steps\":[")
                    mapped.forEachIndexed { i, (action, targetKind) ->
                        if (i > 0) append(",")
                        append("{\"action\":\"$action\",\"target_kind\":\"$targetKind\"}")
                    }
                    append("]}")
                }
                val hashBytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toByteArray(Charsets.UTF_8))
                val templateHash = hashBytes.joinToString("") { "%02x".format(it) }
                val body = JSONObject()
                body.put("template_hash", templateHash)
                body.put("steps", stepsJson)
                body.put("licence_key", licenceKey)
                body.put("device_id", deviceId)
                val conn = URL("$LICENCE_SERVER_URL/api/patterns").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                // C-B1: log the server's error DETAIL, not just the
                // HTTP code (the detail names the validation failure).
                // The request body (licence key) is never logged.
                val detail = if (code in 200..299) {
                    "ok"
                } else {
                    runCatching {
                        conn.errorStream?.bufferedReader()?.readText()?.take(300) ?: ""
                    }.getOrDefault("")
                }
                // 1.4.5 (item 10): upload failures are no longer
                // silent-or-identical. 400 = server validation
                // rejected the template; 409 = same template_hash
                // with different steps already exists (never
                // overwritten — that is the server's guard, not a
                // retryable error); other codes = transport/server.
                // A background upload must not spam chat on every
                // failure, so the distinction lands in Diagnostics
                // (user-visible via the bug-report section) + log.
                if (code !in 200..299) {
                    val upCode = when (code) {
                        400 -> "UPLOAD_400"
                        409 -> "UPLOAD_409"
                        else -> "UPLOAD_FAIL"
                    }
                    val upMsg = when (code) {
                        400 -> "Upload rejected (400 validation): $detail — template not saved."
                        409 -> "Upload conflict (409 same-hash-different-steps): template already exists with different steps — not overwritten."
                        else -> "Upload failed (HTTP $code): $detail"
                    }
                    JarvisStateRepository.addDiagnostic("patterns", upCode, upMsg)
                }
                JarvisStateRepository.addLog("Global-learning upload: HTTP $code $detail (generic template only)")
                conn.disconnect()
            } catch (e: Exception) {
                JarvisStateRepository.addLog("Global-learning upload failed (ignored): ${e.message}")
            }
        }
    }

    /** C-B1 mapping: engine action -> server (action, target_kind)
     *  pairs from lib/patterns.ts. Empty = unmappable, dropped. */
    private fun mapLearnedStepToServer(engineAction: String): List<Pair<String, String>> =
        when (engineAction.uppercase()) {
            "OPEN_APP" -> listOf("OPEN_APP" to "APP")
            "YOUTUBE_SEARCH" -> listOf("SEARCH" to "SEARCH_BOX")
            "YOUTUBE_PLAY" -> listOf(
                "SEARCH" to "SEARCH_BOX",
                "TAP_FIRST_RESULT" to "FIRST_RESULT"
            )
            "SCROLL" -> listOf("SCROLL" to "GENERIC_BUTTON")
            "NAVIGATE" -> listOf("NAVIGATE" to "GENERIC_BUTTON")
            "WAIT" -> listOf("WAIT" to "GENERIC_BUTTON")
            else -> emptyList()
        }

    /**
     * PATTERNS FETCH (audit gap): fetches owner-APPROVED patterns
     * (GET /api/patterns — the server returns approved=true rows
     * only) and surfaces them as CHAT SUGGESTIONS. They are NEVER
     * auto-applied and NEVER auto-executed: no routine is stored,
     * no step runs, the user must ask for a pattern by name for
     * anything to happen. Best-effort; failures are logged only.
     */
    /**
     * PATTERNS FETCH: fetches owner-APPROVED patterns (GET
     * /api/patterns — the server returns approved=true rows only)
     * and surfaces them as CHAT SUGGESTIONS. They are NEVER
     * auto-applied and NEVER auto-executed: no routine is stored,
     * no step runs, the user must ask for a pattern by name for
     * anything to happen. Best-effort; failures are logged only.
     *
     * 1.4.5 (item 10) fixes, both low-risk and user-visible:
     * (a) PAGINATION: the old code fetched ?limit=20 once and
     * ignored next_cursor, so only the first page ever showed.
     * Now it follows next_cursor (cap 5 pages / same-cursor
     * break, so a misbehaving server cannot loop forever).
     * (b) APPROVED-ONLY CLIENT VERIFY: the old code trusted the
     * server's approved-only claim without checking — now every
     * template with an explicit approved=false is dropped
     * client-side too (absent field = trusted, server default).
     */
    fun fetchPatternSuggestions() {
        scope.launch(Dispatchers.IO) {
            try {
                val allTemplates = mutableListOf<JSONObject>()
                var cursor: String? = null
                var pages = 0
                var fetchError: String? = null
                while (pages < 5) {
                    pages++
                    val url = buildString {
                        append("$LICENCE_SERVER_URL/api/patterns?limit=20")
                        if (!cursor.isNullOrBlank()) append("&cursor=$cursor")
                    }
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    val code = conn.responseCode
                    val text = if (code in 200..299) {
                        conn.inputStream.bufferedReader().readText()
                    } else {
                        runCatching {
                            conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""
                        }.getOrDefault("")
                    }
                    conn.disconnect()
                    if (code !in 200..299) {
                        fetchError = "HTTP $code $text"
                        break
                    }
                    val root = JSONObject(text)
                    val templates = root.optJSONArray("templates")
                    if (templates != null) {
                        for (i in 0 until templates.length()) {
                            val t = templates.optJSONObject(i) ?: continue
                            // Approved-only client verify: drop any
                            // template that explicitly says approved=false.
                            if (t.has("approved") && !t.optBoolean("approved", true)) continue
                            allTemplates.add(t)
                        }
                    }
                    val next = root.optString("next_cursor", "")
                    if (next.isBlank() || next == cursor) break
                    cursor = next
                }
                if (fetchError != null) {
                    JarvisStateRepository.addLog("Pattern suggestions fetch failed: $fetchError")
                    return@launch
                }
                if (allTemplates.isEmpty()) {
                    JarvisStateRepository.addChatMessage(
                        "Jarvis",
                        "Approved patterns: abhi koi approved pattern nahi mila (sirf sujhav dikhata hoon, auto-apply kabhi nahi)."
                    )
                    return@launch
                }
                val lines = StringBuilder(
                    "Approved patterns — sirf sujhav, auto-apply NAHI (kisi ko chalana ho to naam se bolo):"
                )
                for (t in allTemplates) {
                    val steps = t.optJSONObject("template")?.optJSONArray("steps")
                    val seq = StringBuilder()
                    if (steps != null) {
                        for (j in 0 until steps.length()) {
                            if (j > 0) seq.append(" → ")
                            seq.append(steps.optJSONObject(j)?.optString("action", "?") ?: "?")
                        }
                    }
                    lines.append("\n• ${t.optString("template_hash", "").take(8)}: $seq")
                }
                JarvisStateRepository.addChatMessage("Jarvis", lines.toString())
            } catch (e: Exception) {
                JarvisStateRepository.addLog("Pattern suggestions fetch failed (ignored): ${e.message}")
            }
        }
    }

    private suspend fun runAutonomousLoop(initialGoal: String, memoryContext: String = "") {
        var currentGoal = initialGoal
        loopUserCommand = initialGoal
        // A-C7: a new attempt starts with a clean buffer — a previous
        // attempt's steps must never leak into this task's routine.
        clearTaskScopedState()
        lastExecutedAction = ""
        lastBuildWritten = false
        // 1.9.0 (v2): loop milestone state is per-run, never carried
        // across tasks (DESIGN §3.2).
        loopLastActionKind = ""
        loopVerifiedSteps = 0
        // Offline gate (1.4.0): cloud planning needs network.
        // Local (on-device) chat still works offline; no-AI local
        // routines already returned before this loop. Speak a
        // clear warning and do local work only — never fake a
        // cloud answer while offline.
        if (!isOnline() && prefs.provider != AiProvider.LOCAL) {
            postYouLineOnce(currentGoal)
            speak("Internet nahi hai — online kaam (AI planning, browsing, cloud actions) abhi nahi ho payega. Sirf phone ke andar wale kaam kar sakta hoon, jaise torch, volume, ya pehle se downloaded cheezein.")
            // 1.8.0: BLOCKED, chat-only — the warning above was spoken.
            reportExecutorOutcome(
                ExecutorResultStatus.BLOCKED,
                "Internet nahi hai — online kaam abhi nahi ho payega.",
                "offline gate", speakResult = false
            )
            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Offline — local work only")
            return
        }
        // 1.15.0 memory-loss fix (F2): the in-loop restore is now just
        // the shared ensureChatRestored() (no-op after the first call).
        ensureChatRestored()
        postYouLineOnce(currentGoal)
        persistChat()
        JarvisStateRepository.setCurrentGoal(currentGoal)

        val previousActions = mutableListOf<String>()
        val maxSteps = prefs.maxAutonomousSteps
        var step = 1
        var lastScreenHash = 0
        var consecutiveSameScreenCount = 0
        var parseErrorRetries = 0
        var rateLimitRetries = 0
        // 1.5.1-PRO (L2): FAILED-evidence retries — one re-plan
        // when the action itself failed (ok=false). See the
        // FAILED branch below.
        var failedEvidenceRetries = 0

        try {
            while (step <= maxSteps) {
                // A-M7: per-step connectivity re-check — offline
                // mid-task = pause (warn, do not complete, do not
                // persist a routine), not a faked continuation.
                if (!isOnline() && prefs.provider != AiProvider.LOCAL) {
                    speak("Internet chala gaya hai — kaam yahin rok raha hoon, complete nahi bol raha. Net aane par dobara bolo.")
                    // 1.8.0: BLOCKED, chat-only — the warning above was spoken.
                    reportExecutorOutcome(
                        ExecutorResultStatus.BLOCKED,
                        "Internet chala gaya hai — kaam yahin rok raha hoon.",
                        "offline mid-task", speakResult = false
                    )
                    clearTaskScopedState()
                    JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Paused offline mid-task")
                    JarvisStateRepository.setCurrentGoal("")
                    return
                }
                JarvisStateRepository.setAgentState(
                    AgentRunState.THINKING,
                    "Step $step/$maxSteps: Observing screen & thinking..."
                )

                val accService = JarvisAccessibilityService.instance
                val snapshot = accService?.captureScreenSnapshot() ?: ScreenSnapshot(
                    packageName = "AccessibilityService_Not_Connected",
                    elements = emptyList(),
                    hasSensitivePaymentPrompt = false,
                    formattedTree = "Accessibility Service is NOT enabled yet. Only conversational chat, OPEN_APP, or hardware actions are available."
                )

                // 1.4.25-PRO Step 1B (1f): pre-action music state for
                // the MEDIA_CONTROL evidence check — captured next to
                // the pre-action screen snapshot, before the LLM
                // decides and the action runs. Null when the service
                // is absent (unknown state, never treated as a
                // success by the evidence check).
                val preMusicActive = accService?.isMusicActive()

                // Stuck-loop detection (A-M11): stuck needs 2+
                // CONSECUTIVE unchanged screens, and screen-invariant
                // actions (volume / flashlight / media / WAIT) never
                // count — an unchanged screen is EXPECTED after them.
                val currentHash = snapshot.formattedTree.hashCode()
                val lastWasInvariant = lastExecutedAction in
                    setOf("FLASHLIGHT", "VOLUME", "MEDIA_CONTROL", "WAIT")
                if (step > 1 && currentHash == lastScreenHash && !lastWasInvariant) {
                    consecutiveSameScreenCount++
                } else {
                    consecutiveSameScreenCount = 0
                }
                lastScreenHash = currentHash
                val isStuck = consecutiveSameScreenCount >= 2

                // Capture live Multimodal Vision screenshot if enabled and needed
                val needsVisualSight = prefs.visionModeEnabled && accService != null && (
                    isStuck ||
                        snapshot.elements.size < 4 ||
                        currentGoal.lowercase().let { g ->
                            listOf("screen", "photo", "image", "dekho", "dekh", "kya dikh", "meme", "story").any { g.contains(it) }
                        }
                    )
                val visionBase64 = if (needsVisualSight) {
                    accService?.captureScreenshotBase64()
                } else {
                    null
                }

                val recentChat = JarvisStateRepository.chatHistory.value.takeLast(6).map {
                    "${it.sender}: ${it.message}"
                }
                val recentNotifs = JarvisStateRepository.recentNotifications.value.takeLast(5).map {
                    "[${it.packageName}] ${it.title}: ${it.text}"
                }

                // 1.4.7 (B): per-step LLM timing, so "task step
                // slow hai" is measurable from the log instead of
                // guessed (network + model latency dominates a
                // step; snapshot + execution are local).
                val llmStartMs = System.currentTimeMillis()
                val decision = llmClient.decideNextStep(
                    userGoal = if (step == 1 && memoryContext.isNotBlank()) {
                        "$currentGoal\n(Pehli baat-cheet se yaad: $memoryContext)"
                    } else {
                        currentGoal
                    },
                    stepNumber = step,
                    previousActions = previousActions,
                    answeredClarifications = answeredClarifications,
                    recentChat = recentChat,
                    recentNotifications = recentNotifs,
                    screenTree = snapshot.formattedTree,
                    hasSensitivePaymentScreen = snapshot.hasSensitivePaymentPrompt,
                    isStuckWarning = isStuck,
                    screenImageBase64 = visionBase64
                )
                val llmMs = System.currentTimeMillis() - llmStartMs

                JarvisStateRepository.addLog(
                    "Step $step Decision: action=${decision.action}, thought=${decision.thought}, kind=${decision.decisionKind}, llmMs=$llmMs"
                )

                // A-C6 / A-M1 / A-M2: error kinds NEVER complete,
                // NEVER persist a routine, NEVER index as success.
                when (decision.decisionKind) {
                    "ERROR" -> {
                        speak(decision.reply)
                        // 1.8.0: FAILED, chat-only — the reply above was spoken.
                        reportExecutorOutcome(
                            ExecutorResultStatus.FAILED, decision.reply.take(300),
                            "decision ERROR", speakResult = false
                        )
                        clearTaskScopedState()
                        JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Error — task stopped")
                        JarvisStateRepository.setCurrentGoal("")
                        return
                    }
                    "PARSE_ERROR" -> {
                        if (parseErrorRetries < 1) {
                            parseErrorRetries++
                            speak("Jawab adhoora aaya, ek baar dobara koshish kar raha hoon.")
                            delay(2000L)
                            continue
                        }
                        speak("Jawab baar-baar adhoora aa raha hai, isliye kaam rok raha hoon.")
                        // 1.8.0: FAILED, chat-only — the line above was spoken.
                        reportExecutorOutcome(
                            ExecutorResultStatus.FAILED,
                            "Jawab baar-baar adhoora aa raha hai, isliye kaam rok raha hoon.",
                            "decision PARSE_ERROR", speakResult = false
                        )
                        clearTaskScopedState()
                        JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Parse error — task stopped")
                        JarvisStateRepository.setCurrentGoal("")
                        return
                    }
                    "RATE_LIMITED" -> {
                        if (rateLimitRetries < 2) {
                            rateLimitRetries++
                            speak("Rate limit lag gaya hai, thodi der ruk kar dobara koshish kar raha hoon.")
                            delay(5000L)
                            continue
                        }
                        speak("Rate limit baar-baar lag raha hai, isliye kaam rok raha hoon.")
                        // 1.8.0: FAILED, chat-only — the line above was spoken.
                        reportExecutorOutcome(
                            ExecutorResultStatus.FAILED,
                            "Rate limit baar-baar lag raha hai, isliye kaam rok raha hoon.",
                            "decision RATE_LIMITED", speakResult = false
                        )
                        clearTaskScopedState()
                        JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Rate limited — task stopped")
                        JarvisStateRepository.setCurrentGoal("")
                        return
                    }
                }

                // Speak reply if non-empty — but in fast mode (default),
                // intermediate step updates are chat-only, NOT spoken aloud.
                // Speaking every step serialises seconds of TTS in front of
                // the next action and is the biggest perceived slowdown.
                // Final answers, questions and casual chat are always spoken.
                // 1.4.23-PRO Item 2 (C2): a completion claim is HELD,
                // not spoken — when the model says the task is done on
                // a step that takes a real action, its reply is neither
                // posted to chat nor spoken until the action's evidence
                // has been checked at the exit below. Speaking it now
                // would present the LLM's word as Jarvis's claim before
                // any check. A question (askUser) and a pure chat turn
                // (NONE) are not completion claims and speak as before.
                var replyWasSpoken = false
                var deferredCompletionReply: String? = null
                val isCompletionClaim = decision.isCompleted &&
                    decision.action != "NONE" && !decision.askUser
                if (decision.reply.isNotBlank()) {
                    if (isCompletionClaim) {
                        deferredCompletionReply = decision.reply
                    } else {
                        JarvisStateRepository.addChatMessage("Jarvis", decision.reply)
                        persistChat()
                        val shouldSpeak = decision.isCompleted || decision.askUser ||
                            decision.action == "NONE" || prefs.speakIntermediateSteps
                        if (shouldSpeak) {
                            JarvisStateRepository.setAgentState(
                                AgentRunState.SPEAKING,
                                "Speaking: ${decision.reply}"
                            )
                            replyWasSpoken = speak(decision.reply, alreadyPostedToChat = true)
                        }
                    }
                } else if ((decision.isCompleted || decision.askUser) && !isCompletionClaim) {
                    // 1.4.3: a FINAL answer / question that arrives
                    // BLANK must not pass in silence (the old
                    // guard-only path). Speak a fixed fallback line
                    // as an audible marker. replyWasSpoken stays
                    // false on purpose: the fallback is NOT the
                    // (blank) question, so no answer is waited for
                    // — the existing pause path below still runs.
                    JarvisStateRepository.addLog(
                        "Blank final reply — speaking fallback line instead of silence."
                    )
                    JarvisStateRepository.setAgentState(
                        AgentRunState.SPEAKING,
                        "Speaking: fallback (blank reply)"
                    )
                    speak("Samajh gaya, aage badh raha hoon.")
                }

                // If Jarvis needs to ask the user a mid-task question, listen for reply and continue
                if (decision.askUser) {
                    JarvisStateRepository.setAgentState(
                        AgentRunState.WAITING_USER_REPLY,
                        "Waiting for your voice answer..."
                    )
                    // B-M8: never wait for an answer to an unspoken
                    // question — the chat prompt above is the fallback.
                    val userAnswer = if (replyWasSpoken) onRequestUserVoiceReply() else null
                    if (!userAnswer.isNullOrBlank()) {
                        // A-C5: an explicit decline ABORTS the task
                        // entirely — it is not a clarification.
                        if (isDeclineReply(userAnswer)) {
                            speak("Theek hai, kaam rok diya.")
                            // 1.8.0: DECLINED, chat-only — the line above was spoken.
                            reportExecutorOutcome(
                                ExecutorResultStatus.DECLINED,
                                "User ne kaam rok diya.",
                                "askUser decline", speakResult = false
                            )
                            clearTaskScopedState()
                            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Declined — task stopped")
                            JarvisStateRepository.setCurrentGoal("")
                            return
                        }
                        JarvisStateRepository.addChatMessage("You", userAnswer)
                        persistChat()
                        currentGoal = "$currentGoal (User clarified: $userAnswer)"
                        previousActions.add("Asked user: '${decision.reply}' -> User answered: '$userAnswer'")
                        // 1.4.18: record the answered clarification
                        // for the ALREADY ANSWERED section (capped
                        // last 12, engine-lifetime).
                        answeredClarifications.add("Q: ${decision.reply} -> A: $userAnswer")
                        while (answeredClarifications.size > 12) {
                            answeredClarifications.removeAt(0)
                        }
                        step++
                        continue
                    } else {
                        // A-C7: ask-user timeout clears the buffer.
                        // 1.8.0: BLOCKED, fully announced — nothing
                        // was spoken for this pause, so the Talker
                        // must tell the user what is needed.
                        reportExecutorOutcome(
                            ExecutorResultStatus.BLOCKED,
                            "Sawal ka jawab nahi aaya — jawab do to aage badhaun.",
                            "askUser no reply"
                        )
                        clearTaskScopedState()
                        JarvisStateRepository.setAgentState(
                            AgentRunState.IDLE,
                            "Paused — No voice reply received."
                        )
                        return
                    }
                }

                // A-C2 hard gate, before ANY execution.
                if (decision.action != "NONE") {
                    val (gateOutcome, gateReason) = checkExecutionGate(
                        decision, snapshot.hasSensitivePaymentPrompt
                    )
                    when (gateOutcome) {
                        GateOutcome.BLOCK_STEP -> {
                            previousActions.add("Step $step blocked by gate: $gateReason")
                            step++
                            continue
                        }
                        GateOutcome.PAUSE_TASK -> {
                            // 1.8.0: BLOCKED, fully announced —
                            // nothing was spoken for this pause.
                            reportExecutorOutcome(
                                ExecutorResultStatus.BLOCKED,
                                "Ruk gaya — $gateReason",
                                "gate PAUSE_TASK"
                            )
                            clearTaskScopedState()
                            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Paused — $gateReason")
                            JarvisStateRepository.setCurrentGoal("")
                            return
                        }
                        GateOutcome.ABORT_TASK -> {
                            speak("Theek hai, kaam rok diya.")
                            // 1.8.0: DECLINED, chat-only — the line above was spoken.
                            reportExecutorOutcome(
                                ExecutorResultStatus.DECLINED,
                                "User ne kaam rok diya.",
                                "gate ABORT_TASK", speakResult = false
                            )
                            clearTaskScopedState()
                            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Declined — task stopped")
                            JarvisStateRepository.setCurrentGoal("")
                            return
                        }
                        GateOutcome.ALLOW -> Unit
                    }
                }

                // Execute the chosen action
                // 1.4.23-PRO Item 2 (C1): the step result is kept for
                // the evidence check at the exit below — a failed
                // action (ok=false) must never be exited as DONE.
                var stepResult: ActionResult? = null
                if (decision.action != "NONE") {
                    JarvisStateRepository.setAgentState(
                        AgentRunState.EXECUTING_STEP,
                        "Step $step: Executing ${decision.action}..."
                    )
                    val actionResult = executeDecisionAction(decision, accService)
                    stepResult = actionResult
                    previousActions.add("Step $step: ${actionResult.summary}")
                    // 1.11.0-PRO (self-extension, DESIGN §1): same
                    // detector call in the loop-action failure path.
                    // Only an UNKNOWN_ACTION failure can trigger it
                    // (parseDecisionJson already rejects actions
                    // outside the known set, so this is the narrow
                    // handler-missing case). The loop runs inside
                    // this same job; on EXTENDED_AND_VERIFIED the
                    // next LLM step can use the new skill via the
                    // normal findRoutine path, and the terminal
                    // outcome still flows through the existing
                    // reportExecutorOutcome -> bus -> verification
                    // gate (no second result emitted here).
                    if (!actionResult.ok && actionResult.summary.contains("UNKNOWN_ACTION")) {
                        val extOutcome = planMaybeExtend(
                            failure = ExecutorFailure(
                                stepKind = "LOOP_ACTION",
                                action = decision.action,
                                reason = actionResult.summary,
                                goalText = "",
                                planId = activeExecutorCommand?.id ?: ""
                            ),
                            runId = activeExecutorCommand?.id ?: "loop",
                            hasRealInput = true,
                            retryOriginal = null
                        )
                        previousActions.add(
                            "Step $step self-extension: ${extOutcome::class.simpleName}"
                        )
                    }
                    // 1.4.25-PRO Step 2A (H4): record only ok steps.
                    // stepResult is non-null here — action != NONE,
                    // so executeDecisionAction just ran above.
                    recordLoopStep(decision, stepResult.ok)
                    lastExecutedAction = decision.action
                    // 1.9.0 (v2, DESIGN §3.2): loop milestone — only
                    // on a kind change, every 3rd verified step, or
                    // a > 60 s step, and only when this step's own
                    // evidence verifies. llmStartMs is the step's
                    // start (the LLM call opens the step).
                    maybeEmitLoopProgress(
                        decision, step, stepResult, snapshot, preMusicActive, llmStartMs
                    )
                }

                // Builder §5c: a failed build validation is NEVER a
                // success. One repair step (re-write the missing /
                // empty files on the next loop step), never a success
                // summary, never a silent return.
                if (decision.action in listOf("CREATE_PROJECT", "WRITE_PROJECT_FILE")) {
                    val v = lastBuildValidation
                    if (v != null && !v.ok) {
                        // 1.4.20 (item 4): the repair note names
                        // invalid refs too (nav/viewport/fetch
                        // failures), not just missing/empty — the
                        // loop machinery itself is unchanged.
                        val detail = (v.missingFiles.map { "$it missing" } +
                            v.emptyFiles.map { "$it khaali" } +
                            v.invalidRefs).joinToString(", ")
                        val repairNote = "Build validation fail ho gaya — $detail. In files ko dobara likh raha hoon, success tabhi bolunga jab validation OK ho."
                        JarvisStateRepository.addChatMessage("Jarvis", repairNote)
                        speak(repairNote, alreadyPostedToChat = true)
                        currentGoal = "$initialGoal (Repair: $detail dobara likhna hai)"
                        step++
                        continue
                    }
                }

                // A-M10: WRITE_PROJECT_FILE with written==false is
                // a FAILURE — speak/show it, never "Ho gaya", never
                // complete, never persist a routine for it.
                if (decision.action == "WRITE_PROJECT_FILE" && !lastBuildWritten) {
                    speak("File likh nahi paya — project ya file ka naam galat ho sakta hai. Dobara naam batao.")
                    JarvisStateRepository.addChatMessage(
                        "Jarvis",
                        "WRITE_PROJECT_FILE fail ho gaya (written=false) — file disk par nahi likhi gayi."
                    )
                    // 1.8.0: FAILED, unannounced — the failure is
                    // already fully in chat AND spoken above.
                    reportExecutorOutcome(
                        ExecutorResultStatus.FAILED,
                        "File likh nahi paya — project ya file ka naam galat ho sakta hai.",
                        "WRITE_PROJECT_FILE written=false",
                        speakResult = false, chatResult = false
                    )
                    clearTaskScopedState()
                    JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Write failed — task stopped")
                    JarvisStateRepository.setCurrentGoal("")
                    return
                }

                // 1.4.23-PRO Item 2 — VERIFIED EXIT (replaces the
                // old A-H2 "exit on isCompleted ONLY"). The model's
                // isCompleted word alone NEVER ends the task now:
                // the loop exits DONE only when the action's
                // evidence check passes (C1). An unverified exit
                // persists nothing and indexes nothing (C4).
                if (decision.isCompleted) {
                    // C6 approval guard: a step awaiting approval
                    // (the model's own askUser question, or a browser
                    // approval the action just asked — "Ye link
                    // kholun?") goes to WAITING_USER_REPLY, never
                    // DONE. Task state is deliberately NOT cleared
                    // here: the pending browser approval stays set
                    // for the next user command to answer, and the
                    // next loop start clears task state anyway.
                    if (decision.askUser || pendingBrowserApprovalUrl != null) {
                        // 1.8.0: BLOCKED, fully announced — nothing
                        // was spoken for this wait, so the Talker
                        // must tell the user approval is needed.
                        reportExecutorOutcome(
                            ExecutorResultStatus.BLOCKED,
                            "Approval ka intezaar hai — haan ya naa bolo to aage badhaun.",
                            "waiting for approval"
                        )
                        JarvisStateRepository.setAgentState(
                            AgentRunState.WAITING_USER_REPLY,
                            "Waiting for your approval..."
                        )
                        JarvisStateRepository.setCurrentGoal("")
                        return
                    }
                    // 1.4.25-PRO Step 1A (H1/H2): a NONE turn that
                    // claims completion is a CHAT end, never a task
                    // DONE. The model's reply was already posted /
                    // spoken above — do NOT post or speak it again,
                    // and do NOT finish a routine, index a turn, or
                    // say "Task Completed": no action ran, so there
                    // is nothing to verify. The C6 guard above stays
                    // first: a NONE+askUser turn still waits for
                    // approval instead of ending here.
                    if (decision.action == "NONE" && decision.isCompleted) {
                        // 1.8.0: DONE, unannounced — the reply was
                        // already posted to chat AND spoken above.
                        reportExecutorOutcome(
                            ExecutorResultStatus.DONE,
                            decision.reply.take(300),
                            "NONE chat turn complete",
                            speakResult = false, chatResult = false
                        )
                        JarvisStateRepository.setAgentState(
                            AgentRunState.IDLE,
                            "Ready for next command"
                        )
                        JarvisStateRepository.setCurrentGoal("")
                        clearTaskScopedState()
                        return
                    }
                    when (verifyStepEvidence(decision, stepResult, accService, preSnapshot = snapshot, preMusicActive = preMusicActive)) {
                        StepEvidence.VERIFIED -> {
                            // 1.9.0 (v2, DESIGN §4.5): the speak MOVES
                            // behind the verification gate. The loop
                            // no longer speaks its own DONE summary —
                            // the Talker re-reads the artifact first
                            // (deliverVerifiedExecutorResult) and only
                            // then says "ho gaya" (or the honest
                            // unverified / still-missing line). The
                            // model's deferred reply is still posted
                            // to chat (it is the step's own words for
                            // the record), but never spoken here; the
                            // second-claim guard still notes the
                            // unverified second claim in chat.
                            // The built summary drops the "Ho gaya
                            // bhai!" prefix — the gate's verified line
                            // adds it, and a double prefix would read
                            // as two completion claims.
                            var doneSummary = ""
                            // Plain (non-Executor) loop turns — TALK
                            // fallback, replay fallback — have no
                            // Talker command, so there is no gate to
                            // route through: they keep the v1 behaviour
                            // (speak the loop's own verified summary).
                            // Only Executor turns go through the gate.
                            var plainSpeakLine: String? = null
                            var plainChatLine: String? = null
                            if (decision.action in listOf("CREATE_PROJECT", "WRITE_PROJECT_FILE") &&
                                lastBuildValidation?.ok == true && lastBuildWritten
                            ) {
                                val projectName = ProjectStore.sanitizeProjectName(
                                    decision.projectName.ifBlank { lastBuildProject.ifBlank { "jarvis-site" } }
                                )
                                val builtFiles = ProjectStore.listProjects(context)
                                    .firstOrNull { it.name == projectName }?.files ?: emptyList()
                                if (!decision.reply.contains("Browser me kholo")) {
                                    deferredCompletionReply?.let {
                                        JarvisStateRepository.addChatMessage("Jarvis", it)
                                        persistChat()
                                    }
                                    doneSummary = "Project '$projectName' ban gaya — " +
                                        "files: ${builtFiles.joinToString(", ")}. " +
                                        "Preview: ${ProjectStore.localhostUrl(projectName)} — " +
                                        "Projects tab me Preview dabao, ya Browser me kholo (full-screen, wahi preview)."
                                    plainSpeakLine = "Ho gaya bhai! $doneSummary"
                                    plainChatLine = plainSpeakLine
                                } else {
                                    deferredCompletionReply?.let { raw ->
                                        JarvisStateRepository.addChatMessage("Jarvis", raw)
                                        persistChat()
                                        // 1.5.2-PRO (second-claim
                                        // guard): the second claim goes
                                        // to chat with a note that it
                                        // was not verified in this step.
                                        val (guarded, secondClaim) = guardSecondClaim(raw)
                                        secondClaim?.let { claim ->
                                            JarvisStateRepository.addChatMessage(
                                                "Jarvis",
                                                "Note: dusra daava ($claim) is step me verify nahi hua — dobara check karke bataunga."
                                            )
                                            persistChat()
                                        }
                                        doneSummary = raw
                                        plainSpeakLine = guarded
                                    }
                                }
                            } else {
                                // 1.5.2-PRO (second-claim guard):
                                // chat keeps the FULL reply; the
                                // second claim is noted, never spoken.
                                deferredCompletionReply?.let { raw ->
                                    JarvisStateRepository.addChatMessage("Jarvis", raw)
                                    persistChat()
                                    val (guarded, secondClaim) = guardSecondClaim(raw)
                                    secondClaim?.let { claim ->
                                        JarvisStateRepository.addChatMessage(
                                            "Jarvis",
                                            "Note: dusra daava ($claim) is step me verify nahi hua — dobara check karke bataunga."
                                        )
                                        persistChat()
                                    }
                                    doneSummary = raw
                                    plainSpeakLine = guarded
                                }
                            }
                            if (doneSummary.isBlank()) doneSummary = decision.reply
                            // Verified completion: persist the learned
                            // routine + index this turn for memory-first
                            // lookup. The mic loop (service-owned) is
                            // NEVER stopped here — it keeps listening
                            // for the answer. The routine is kept for
                            // the gate's pattern extraction
                            // (lastVerifiedLoopRoutine); the v2 pattern
                            // itself is only learned after the Talker
                            // verdict (learnFromVerified).
                            val finishedRoutine = learnedRoutineStore.finishRoutine(initialGoal, verified = true)
                            lastVerifiedLoopRoutine = finishedRoutine
                            if (finishedRoutine != null) {
                                maybeUploadLearnedPattern(finishedRoutine)
                            }
                            val lastReply = JarvisStateRepository.chatHistory.value
                                .lastOrNull { it.sender == "Jarvis" }?.message ?: ""
                            memoryIndex.indexTurn(
                                System.currentTimeMillis().toString(),
                                ChatSummary.summarize(initialGoal, lastReply)
                            )
                            JarvisStateRepository.setAgentState(
                                AgentRunState.IDLE,
                                "Task Completed — Ready for next command"
                            )
                            JarvisStateRepository.setCurrentGoal("")
                            clearTaskScopedState()
                            // 1.9.0 (v2): DONE, chat-posted but NOT
                            // spoken — the verification gate speaks
                            // the verdict (or the fix / honest line).
                            // Typed refs let the Talker re-read the
                            // artifact instead of trusting the claim.
                            // Plain loop turns (no active Executor
                            // command) keep the v1 speak instead.
                            if (activeExecutorCommand != null) {
                                reportExecutorOutcome(
                                    ExecutorResultStatus.DONE,
                                    doneSummary.take(300),
                                    "verified exit",
                                    speakResult = false, chatResult = true,
                                    evidenceJson = EvidenceRefs.toJson(loopDoneRefs(decision))
                                )
                            } else {
                                plainChatLine?.let {
                                    JarvisStateRepository.addChatMessage("Jarvis", it)
                                    persistChat()
                                }
                                plainSpeakLine?.let {
                                    speak(it, alreadyPostedToChat = plainChatLine != null)
                                }
                                speak("Aur kya karna hai?")
                            }
                            return
                        }
                        StepEvidence.FAILED -> {
                            // 1.5.1-PRO (L2): ONE re-plan retry when
                            // the ACTION itself failed (ok=false) —
                            // a tap miss / cold start is transient
                            // and the LLM re-decides with the failure
                            // in previousActions. No retry when
                            // ok=true: the action dispatched but its
                            // evidence failed, and re-running it
                            // could double-act (second tap/send).
                            if (stepResult?.ok == false && failedEvidenceRetries < 1) {
                                failedEvidenceRetries++
                                previousActions.add("Step $step: action fail hua (${stepResult?.summary ?: decision.action}) — ek baar dobara koshish kar raha hoon.")
                                JarvisStateRepository.addLog("L2: FAILED-evidence retry (1/1) — ${stepResult?.summary ?: decision.action}")
                                step++
                                continue
                            }
                            // C4: definite failure — fixed line, no
                            // routine persisted, no memory indexed, no
                            // "Task Completed" wording anywhere. The
                            // detail goes to chat only, never spoken.
                            val detail = "Step $step: ${stepResult?.summary ?: decision.action} — evidence fail"
                            JarvisStateRepository.addChatMessage(
                                "Jarvis",
                                "Bhai, kaam poora nahi ho paya — dobara batao to phir se koshish karunga. ($detail)"
                            )
                            persistChat()
                            JarvisStateRepository.addLog("Item 2: unverified exit blocked — $detail")
                            JarvisStateRepository.setAgentState(
                                AgentRunState.IDLE,
                                "Task not verified — stopped"
                            )
                            JarvisStateRepository.setCurrentGoal("")
                            clearTaskScopedState()
                            // 1.8.0: FAILED, unannounced — the
                            // failure line is already fully in chat
                            // AND spoken below.
                            reportExecutorOutcome(
                                ExecutorResultStatus.FAILED,
                                "Kaam poora nahi ho paya — $detail",
                                "evidence FAILED", speakResult = false, chatResult = false
                            )
                            speak(
                                "Bhai, kaam poora nahi ho paya — dobara batao to phir se koshish karunga.",
                                alreadyPostedToChat = true
                            )
                            return
                        }
                        StepEvidence.AMBIGUOUS -> {
                            // Adopted default: ambiguous evidence —
                            // continue the loop / ask, never fail
                            // outright, never report success. The next
                            // step re-observes, and the model sees the
                            // note instead of a success claim.
                            previousActions.add(
                                "Step $step: verification unclear (${stepResult?.summary ?: decision.action}) — success mat bolo, dobara check karo."
                            )
                            step++
                            continue
                        }
                    }
                }

                // Wait for UI to settle before next observation
                delay(prefs.stepDelayMs)
                step++
            }

            clearTaskScopedState()
            JarvisStateRepository.setAgentState(
                AgentRunState.IDLE,
                "Reached max steps ($maxSteps). Ready."
            )
            // 1.8.0: FAILED, chat-only — the line below was spoken.
            reportExecutorOutcome(
                ExecutorResultStatus.FAILED,
                "Kaam $maxSteps steps me poora nahi ho paya.",
                "max steps reached", speakResult = false
            )
            // 1.4.23-PRO Item 2 (C5): the old line claimed the steps
            // were "complete" for a task that did NOT complete. The
            // replacement states the non-completion, no success claim.
            speak("Bhai, kaam $maxSteps steps me poora nahi ho paya — dobara batao to phir se koshish karunga.")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // 1.8.0: FAILED, fully announced — nothing was spoken
            // for this error, so the Talker must tell the user.
            reportExecutorOutcome(
                ExecutorResultStatus.FAILED,
                "Kaam me error aa gaya — dobara bolo to phir se koshish karunga.",
                "loop exception: ${(e.message ?: e.javaClass.simpleName).take(200)}"
            )
            clearTaskScopedState()
            JarvisStateRepository.addLog("Agent Loop Error: ${e.message}")
            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Error: ${e.localizedMessage}")
        }
    }

    // ---- 1.4.23-PRO Item 2: verified-exit evidence table ----

    /**
     * Evidence table for the verified exit (C1/C4/C7). Decides
     * whether a step the model marked isCompleted may end the
     * task. Per-action evidence:
     * - NONE: no action taken — never evidence (1A, AMBIGUOUS).
     * - CREATE_PROJECT / WRITE_PROJECT_FILE: validation OK +
     *   written + preview GET 200 (verifyBuildEvidence).
     * - MEDIA_CONTROL: AudioManager.isMusicActive() before vs
     *   after (1B/1f) — PLAY / PLAY_PAUSE-towards-play needs
     *   inactive→active, PAUSE / PLAY_PAUSE-towards-pause needs
     *   active→inactive; NEXT / PREVIOUS have no track-change
     *   signal observable here, so they are never VERIFIED on
     *   dispatch alone (AMBIGUOUS).
     * - UI actions: action ok=true PLUS a post-action snapshot
     *   that is non-empty AND changed vs the pre-action snapshot
     *   (1B/1c, H5) — a non-empty screen alone proves a screen
     *   exists, not that the action achieved its outcome.
     * - FLASHLIGHT / VOLUME / WAIT / SAVE_MEMORY: screen-invariant
     *   — the action's own Boolean is the whole evidence, no
     *   snapshot (real system calls / trivially true by
     *   construction). MEDIA_CONTROL was removed from this set
     *   in 1B: its Boolean is dispatch-only.
     * ok=false is a definite failure; a missing/empty snapshot, an
     * unchanged screen, or an unobserved music-state transition is
     * ambiguous. Ambiguous evidence continues the loop (adopted
     * default), never fails outright, never reports success.
     * Per-kind expected-text checks (1.4.25-PRO Step 2B, H5 rule
     * (iii) — the change must be in the action's direction where
     * checkable; a (iii) failure where checkable is FAILED):
     * - TYPE_TEXT: the typed text (or its prefix) appears in some
     *   element's text afterwards. Checkable only while an editable
     *   field is still on screen — a submitted/navigated-away field
     *   (or a password field, whose text is redacted) cannot be
     *   checked, so it falls back to rules (i)+(ii).
     * - OPEN_APP: post-snapshot packageName is the launched app's
     *   package (resolveAppPackage) or the app's label appears.
     * - WHATSAPP_SEND: the chat screen no longer shows an unsent
     *   draft (message text still in an editable field = FAILED);
     *   the message visible as a sent bubble = VERIFIED; no
     *   observable signal either way = AMBIGUOUS, never
     *   VERIFIED-on-empty-change.
     * - YOUTUBE_PLAY: post-snapshot package is YouTube AND a
     *   playing signal is present (pause/stop control or the video
     *   title in the player area). Tap-dispatched alone stays
     *   AMBIGUOUS.
     * - OPEN_BROWSER foreground-show: VERIFIED only if the shown
     *   page's snapshot is non-empty AND the URL's host appears in
     *   the snapshot (no loaded-title signal is wired into the
     *   engine — only the visibility supplier — so the host check
     *   is the checkable half); otherwise AMBIGUOUS. The
     *   background path already carries lastBrowserLoadOk and
     *   keeps the default rules, unchanged.
     * - All other UI actions keep the default rules (i)+(ii) only.
     */
    private suspend fun verifyStepEvidence(
        decision: AgentDecision,
        result: ActionResult?,
        accService: JarvisAccessibilityService?,
        preSnapshot: ScreenSnapshot? = null,
        preMusicActive: Boolean? = null
    ): StepEvidence {
        // 1.4.25-PRO Step 1A (H1, defence-in-depth): NONE is never
        // evidence. The chat-end branch in the loop ends a
        // NONE+isCompleted turn before this is reached, so this
        // line is unreachable for a completed NONE; it stays
        // AMBIGUOUS so a NONE can never again be verified as a
        // task step.
        if (decision.action == "NONE") return StepEvidence.AMBIGUOUS
        if (result == null) return StepEvidence.AMBIGUOUS
        if (decision.action in listOf("CREATE_PROJECT", "WRITE_PROJECT_FILE")) {
            return verifyBuildEvidence()
        }
        // A definite failure is never a success, whatever the
        // model claimed (C1). The media Boolean arrives here as
        // ok (C7 loop side).
        if (!result.ok) return StepEvidence.FAILED
        // 1.4.25-PRO Step 1B (1f): MEDIA_CONTROL is no longer
        // Boolean-only — a dispatched media key proves nothing when
        // no player session exists. Require the music-active state
        // to move in the commanded direction.
        if (decision.action == "MEDIA_CONTROL") {
            return verifyMediaControlEvidence(decision, preMusicActive, accService)
        }
        if (decision.action in setOf("FLASHLIGHT", "VOLUME", "WAIT", "SAVE_MEMORY")) {
            return StepEvidence.VERIFIED
        }
        val verifySnapshot = accService?.captureScreenSnapshot()
        if (verifySnapshot == null || verifySnapshot.elements.isEmpty()) {
            return StepEvidence.AMBIGUOUS
        }
        // 1.4.25-PRO Step 1B (1c, H5): the post-action screen must
        // have CHANGED vs the pre-action snapshot — formatted-tree
        // hash, owning package, or element set. An identical screen
        // means the action had no observable effect (stale node,
        // wrong field, wrong app), so it is ambiguous, never
        // verified. A missing pre-snapshot cannot prove a change,
        // so it is ambiguous too.
        if (preSnapshot == null) return StepEvidence.AMBIGUOUS
        val screenChanged = snapshotsDiffer(preSnapshot, verifySnapshot)
        if (!screenChanged) return StepEvidence.AMBIGUOUS
        // 1.4.25-PRO Step 2B (H5 rule (iii)): the change must be
        // in the action's direction where checkable. Default rules
        // (i)+(ii) have passed; per-kind checks decide the rest.
        return verifyPerKindEvidence(decision, verifySnapshot, accService)
    }

    /**
     * 1.4.25-PRO Step 2B (H5 rule (iii)): per-kind expected-text
     * checks on the post-action snapshot. The caller already
     * established (i) non-empty and (ii) changed vs pre-action.
     * A (iii) failure WHERE CHECKABLE is FAILED; where the app
     * gives no observable signal for that kind, the result is
     * AMBIGUOUS (never VERIFIED-on-empty-change, never a false
     * failure). All other UI actions keep the default rules
     * (i)+(ii) only and return VERIFIED here.
     */
    private fun verifyPerKindEvidence(
        decision: AgentDecision,
        postSnapshot: ScreenSnapshot,
        accService: JarvisAccessibilityService?
    ): StepEvidence {
        return when (decision.action) {
            "TYPE_TEXT" -> {
                val typed = decision.text.trim()
                if (typed.isBlank()) return StepEvidence.VERIFIED
                // Probe: the full text, or its first 20 chars when
                // the field truncates long input.
                val probe = typed.take(20)
                // Checkable only while a non-password editable field
                // is still on screen. A submitted field (navigation
                // / sent message cleared the input) or a password
                // field (text redacted as [REDACTED_PASSWORD]) cannot
                // be compared — fall back to (i)+(ii).
                val editableFields = postSnapshot.elements.filter { it.isEditable && !it.isPassword }
                if (editableFields.isEmpty()) return StepEvidence.VERIFIED
                val typedVisible = editableFields.any { el ->
                    el.text.contains(probe) || el.contentDesc.contains(probe)
                }
                if (typedVisible) StepEvidence.VERIFIED else StepEvidence.FAILED
            }
            "OPEN_APP" -> {
                val expectedPkg = accService?.resolveAppPackage(decision.appName)
                val pkgOk = expectedPkg != null && postSnapshot.packageName == expectedPkg
                val labelOk = decision.appName.isNotBlank() && postSnapshot.elements.any { el ->
                    val label = "${el.text} ${el.contentDesc}".lowercase()
                    label.contains(decision.appName.lowercase())
                }
                when {
                    pkgOk || labelOk -> StepEvidence.VERIFIED
                    // Package known but neither it nor the label is
                    // on screen: the wrong app (or no app) opened.
                    expectedPkg != null -> StepEvidence.FAILED
                    // Package unresolvable and label absent: nothing
                    // checkable — do not fail, do not verify.
                    else -> StepEvidence.AMBIGUOUS
                }
            }
            "WHATSAPP_SEND" -> {
                val sent = decision.text.trim()
                if (sent.isBlank()) return StepEvidence.AMBIGUOUS
                val probe = sent.take(40)
                // An unsent draft is still sitting in an editable
                // input field: the Send tap did not consume it.
                val draftStillThere = postSnapshot.elements.any { el ->
                    el.isEditable && (el.text.contains(probe) || el.contentDesc.contains(probe))
                }
                if (draftStillThere) return StepEvidence.FAILED
                // The message now shows as a sent bubble / chat
                // history (non-editable element): consumed + shown.
                val sentVisible = postSnapshot.elements.any { el ->
                    !el.isEditable && (el.text.contains(probe) || el.contentDesc.contains(probe))
                }
                if (sentVisible) StepEvidence.VERIFIED else StepEvidence.AMBIGUOUS
            }
            "YOUTUBE_PLAY" -> {
                // Same playing-signal rule as the 1C quick path:
                // YouTube in the foreground AND a pause/stop control
                // or the video title in the player area. Tap
                // dispatch alone stays AMBIGUOUS — a still-loading
                // player is re-observed on the next loop step, never
                // failed outright and never verified on dispatch.
                val youtubePkg = accService?.resolveAppPackage("YouTube")
                    ?: "com.google.android.youtube"
                if (postSnapshot.packageName != youtubePkg) return StepEvidence.AMBIGUOUS
                val query = decision.text.ifBlank { decision.appName }.trim().take(40)
                val playing = postSnapshot.elements.any { el ->
                    val label = "${el.text} ${el.contentDesc}".lowercase()
                    label.contains("pause") || label.contains("stop") ||
                        (query.isNotBlank() &&
                            (el.text.contains(query) || el.contentDesc.contains(query)))
                }
                if (playing) StepEvidence.VERIFIED else StepEvidence.AMBIGUOUS
            }
            "OPEN_BROWSER" -> {
                // Foreground-show only: the background path already
                // carries lastBrowserLoadOk in result.ok and keeps
                // the default rules. A foreground show happened only
                // when the decision asked for it AND a host was
                // wired to show the page.
                if (!decision.browserShowPage || onShowBrowserPage == null) {
                    return StepEvidence.VERIFIED
                }
                val url = browserTargetForInput(decision.browserUrl)
                val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
                    ?.removePrefix("www.")
                if (url.isBlank() || host.isNullOrBlank()) return StepEvidence.AMBIGUOUS
                val hostVisible = postSnapshot.elements.any { el ->
                    "${el.text} ${el.contentDesc}".lowercase().contains(host)
                }
                if (hostVisible) StepEvidence.VERIFIED else StepEvidence.AMBIGUOUS
            }
            else -> StepEvidence.VERIFIED
        }
    }

    /**
     * 1.4.25-PRO Step 1B (1f): MEDIA_CONTROL evidence from
     * AudioManager.isMusicActive() before vs after the dispatch.
     * PLAY / PLAY_PAUSE-towards-play requires inactive→active;
     * PAUSE / PLAY_PAUSE-towards-pause requires active→inactive;
     * NEXT / PREVIOUS have no track-change signal observable here,
     * so they are AMBIGUOUS, never VERIFIED on dispatch alone. An
     * unknown pre/post state is AMBIGUOUS too — a missing signal
     * never becomes a success. The short settle delay lets the
     * player react to the key before the post-state is read; the
     * AMBIGUOUS→continue default still covers a slow player.
     */
    private suspend fun verifyMediaControlEvidence(
        decision: AgentDecision,
        preMusicActive: Boolean?,
        accService: JarvisAccessibilityService?
    ): StepEvidence {
        if (preMusicActive == null || accService == null) return StepEvidence.AMBIGUOUS
        delay(500)
        val postMusicActive = accService.isMusicActive()
        return when (decision.text.uppercase()) {
            "PLAY" -> if (!preMusicActive && postMusicActive) StepEvidence.VERIFIED else StepEvidence.AMBIGUOUS
            "PAUSE" -> if (preMusicActive && !postMusicActive) StepEvidence.VERIFIED else StepEvidence.AMBIGUOUS
            // PLAY_PAUSE toggles: whichever direction the pre-state
            // implies, the state must flip.
            "PLAY_PAUSE" -> if (preMusicActive != postMusicActive) StepEvidence.VERIFIED else StepEvidence.AMBIGUOUS
            // NEXT / PREVIOUS / anything else: no track-change
            // signal is observable via AudioManager, so dispatch
            // alone never verifies.
            else -> StepEvidence.AMBIGUOUS
        }
    }

    /**
     * Build evidence (mirrors AgentPlanExecutor.verifyBuildPreview):
     * validation OK + written + preview GET 200. A validation /
     * write failure is definite (FAILED); a preview server that
     * will not start, or a GET that throws / times out, is
     * AMBIGUOUS (the build may be fine — the check itself could
     * not run); a running server answering non-200 does NOT serve
     * the preview, so that is FAILED.
     */
    private suspend fun verifyBuildEvidence(): StepEvidence {
        val v = lastBuildValidation
        if (v == null || !v.ok || !lastBuildWritten) return StepEvidence.FAILED
        val project = lastBuildProject.ifBlank { return StepEvidence.FAILED }
        val (_, ready) = ProjectPreviewServer.ensureRunningReady(context)
        if (!ready) return StepEvidence.AMBIGUOUS
        return when (previewGetStatus(ProjectStore.localhostUrl(project))) {
            200 -> StepEvidence.VERIFIED
            -1 -> StepEvidence.AMBIGUOUS
            else -> StepEvidence.FAILED
        }
    }

    /** Plain GET status on IO (the loop runs on Main). -1 = the
     *  request itself failed (no server / timeout) — callers treat
     *  that as ambiguous, never as a served status. */
    private suspend fun previewGetStatus(url: String): Int = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            code
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Browser mode (a): background load + extracted answer, no
     * tab switch, no visible load. Non-HTTPS gets the spoken
     * warning before any summary. Never fabricates content:
     * interrupted / failed loads speak exactly that.
     */
    private suspend fun executeBrowserLoad(url: String, speakSummary: Boolean): String {
        // 1.4.23-PRO Item 2 (C1): every return path sets
        // lastBrowserLoadOk, so the OPEN_BROWSER branch can carry
        // the load's own outcome in its ActionResult. Defaults to
        // false — a deferred / interrupted / failed load is never
        // a success.
        lastBrowserLoadOk = false
        // B-M3: never hijack the visible page. When the host says
        // the Browser tab is visible, queue/defer — never navigate
        // the shared WebView away silently. (Null host signal =
        // coordinator has not wired visibility yet; the load
        // proceeds as before and the gap is logged.)
        if (isBrowserTabVisible?.invoke() == true) {
            deferredBrowseUrls.add(url)
            speak("Browser tab me abhi ek page khula hai, isliye is page ko background me load nahi kar raha — tab se hatne par dobara bolo.")
            JarvisStateRepository.addChatMessage(
                "Jarvis",
                "Deferred (Browser tab visible, page hijack nahi kiya): $url"
            )
            return "OPEN_BROWSER deferred (browser tab visible): '$url'"
        }
        if (isBrowserTabVisible == null) {
            JarvisStateRepository.addLog("B-M3 note: browser visibility signal not wired (coordinator) — background load proceeding.")
        }
        val result = JarvisBackgroundBrowser.browse(context, url)
        return when (result.status) {
            BrowserBrowseStatus.COMPLETED -> {
                lastBrowserLoadOk = true
                if (result.isNonHttps) {
                    speak(
                        "Note: ye page secure (https) nahi hai, " +
                            "isliye is par koi password ya payment mat daalna."
                    )
                }
                val summary = listOf(result.title, result.mainText.take(500))
                    .filter { it.isNotBlank() }.joinToString(" — ")
                if (speakSummary && summary.isNotBlank()) {
                    speak(summary)
                }
                JarvisStateRepository.addChatMessage(
                    "Jarvis",
                    listOf(result.title, result.mainText)
                        .filter { it.isNotBlank() }.joinToString("\n")
                )
                "OPEN_BROWSER background '${result.url}' title='${result.title}' chars=${result.mainText.length}"
            }
            BrowserBrowseStatus.INTERRUPTED_PROCESS_KILLED -> {
                speak("Browsing ruk gaya, dobara koshish karun?")
                "OPEN_BROWSER interrupted '$url' (no content fabricated)"
            }
            BrowserBrowseStatus.CAPTCHA -> {
                // 3c (D3 human-assist-first): a challenge page is
                // never auto-solved and never content-extracted. The
                // assist canvas is already showing (browser layer
                // published it); tell the user once, return the
                // prompt string, and let the loop pause on the
                // AWAITING_USER step — no retry loop while waiting.
                speak("Captcha aaya hai — Browser tab me solve karo, phir main aage badhunga.")
                JarvisStateRepository.addChatMessage(
                    "Jarvis",
                    "Captcha aaya hai — ${result.errorMessage}"
                )
                "OPEN_BROWSER captcha '$url' (human assist — no auto-solve)"
            }
            BrowserBrowseStatus.FAILED -> {
                speak(
                    "Page nahi khul paya: ${result.errorMessage}. Dobara koshish karun?"
                )
                "OPEN_BROWSER failed '$url': ${result.errorMessage}"
            }
        }
    }

    // 1.4.23-PRO Item 2 (C1): returns ActionResult, not a bare
    // String — the loop's evidence check needs the action's own
    // success signal, not a log line it would have to re-parse.
    private suspend fun executeDecisionAction(
        d: AgentDecision,
        acc: JarvisAccessibilityService?
    ): ActionResult {
        return when (d.action) {
            "OPEN_APP" -> {
                val ok = acc?.launchApp(d.appName) ?: false
                // 1.4.7 (B): no 800ms blind wait — the next loop
                // step re-snapshots and observes the cold start
                // (the old comment said exactly this).
                ActionResult(ok, "OPEN_APP('${d.appName}') -> success=$ok")
            }

            "YOUTUBE_SEARCH" -> {
                val ok = acc?.searchYouTube(d.text.ifBlank { d.appName }) ?: false
                // 1.4.7 (B): no 900ms blind wait — the next loop
                // step re-snapshots and observes the results.
                ActionResult(ok, "YOUTUBE_SEARCH('${d.text}') -> success=$ok")
            }

            "YOUTUBE_PLAY" -> {
                // Search + play the first video (LLM path: direct, no
                // confirm — the voice routine path asks first instead).
                val query = d.text.ifBlank { d.appName }
                val opened = acc?.searchYouTube(query) ?: false
                // 1.4.7 (B): no blind 3000ms wait — the poll
                // inside findFirstYouTubeVideo covers the load.
                val video = acc?.findFirstYouTubeVideo()
                val ok = if (video != null) acc.tapNode(video.id) else false
                ActionResult(opened && ok, "YOUTUBE_PLAY('$query') -> opened=$opened, video='${video?.text?.ifBlank { video?.contentDesc }}', played=$ok")
            }

            "WHATSAPP_SEND" -> {
                // Caller (LLM prompt) must only use this after the user
                // explicitly said send; the voice routine path confirms
                // first. A sent message cannot be unsent reliably.
                val ok = acc?.sendWhatsAppMessage(d.contact, d.text) ?: false
                ActionResult(ok, "WHATSAPP_SEND(to='${d.contact}') -> success=$ok")
            }

            "OPEN_URL" -> {
                // A-C4: same browser-approval gate as OPEN_BROWSER,
                // http(s)-only allowlist. normalizeBrowserUrl returns
                // "" for upi:/tel:/sms:/intent: and other schemes, so
                // those are blocked at the gate, never opened. The
                // accessibility openUrlOrDeepLink hardens this again.
                val url = normalizeBrowserUrl(d.text)
                if (url.isEmpty()) {
                    ActionResult(false, "OPEN_URL blocked: sirf http/https allowed hai (upi:/tel:/sms:/intent: blocked)")
                } else {
                    val kind = browserApprovalKind(url)
                    if (kind == null) {
                        pendingBrowserApprovalUrl = url
                        pendingBrowserOpenExternal = true
                        pendingBrowserShowPage = false
                        speak("Ye link kholun? $url")
                        ActionResult(false, "OPEN_URL awaiting approval for '$url'")
                    } else {
                        val ok = acc?.openUrlOrDeepLink(url) ?: false
                        // 1.4.7 (B): no 800ms blind wait — the next
                        // loop step re-snapshots the opened page.
                        ActionResult(ok, "OPEN_URL('$url') -> success=$ok ($kind)")
                    }
                }
            }

            "REPLY_NOTIFICATION" -> {
                val ok = JarvisNotificationService.sendDirectReply(d.contact, d.text)
                ActionResult(ok, "REPLY_NOTIFICATION(to='${d.contact}', msg='${d.text}') -> success=$ok")
            }

            "TAP_NODE" -> {
                val ok = acc?.tapNode(d.nodeId) ?: false
                ActionResult(ok, "TAP_NODE(${d.nodeId}) -> success=$ok")
            }

            "TAP_XY" -> {
                val ok = acc?.tapCoordinates(d.x, d.y) ?: false
                ActionResult(ok, "TAP_XY(${d.x}, ${d.y}) -> success=$ok")
            }

            "LONG_PRESS_XY" -> {
                val ok = acc?.longPressCoordinates(d.x, d.y) ?: false
                ActionResult(ok, "LONG_PRESS_XY(${d.x}, ${d.y}) -> success=$ok")
            }

            "TYPE_TEXT" -> {
                val nodeId = if (d.nodeId > 0) d.nodeId else null
                val ok = acc?.typeText(nodeId, d.text, d.pressEnter) ?: false
                ActionResult(ok, "TYPE_TEXT(node=$nodeId, text='${d.text}', enter=${d.pressEnter}) -> success=$ok")
            }

            "SCROLL" -> {
                val ok = acc?.scrollScreen(d.direction) ?: false
                ActionResult(ok, "SCROLL(${d.direction}) -> success=$ok")
            }

            "NAVIGATE" -> {
                val ok = acc?.performSystemNavigation(d.navTarget) ?: false
                ActionResult(ok, "NAVIGATE(${d.navTarget}) -> success=$ok")
            }

            "MEDIA_CONTROL" -> {
                val ok = acc?.controlMediaPlayback(d.text) ?: false
                ActionResult(ok, "MEDIA_CONTROL(${d.text}) -> success=$ok")
            }

            "COPY_CLIPBOARD" -> {
                val ok = acc?.copyToClipboard(d.text) ?: false
                ActionResult(ok, "COPY_CLIPBOARD('${d.text.take(25)}') -> success=$ok")
            }

            "WAIT" -> {
                delay(1000L)
                ActionResult(true, "WAIT(1000ms)")
            }

            "FLASHLIGHT" -> {
                val ok = acc?.toggleFlashlight(d.enableFlag) ?: false
                ActionResult(ok, "FLASHLIGHT(${d.enableFlag}) -> success=$ok")
            }

            "VOLUME" -> {
                val ok = acc?.setMediaVolumePercent(d.volumePercent) ?: false
                ActionResult(ok, "VOLUME(${d.volumePercent}%) -> success=$ok")
            }

            "CALL" -> {
                // C-B13: the summary carries the distinct reason —
                // permission-denied is never reported as not-found.
                val (ok, reason) = acc?.makePhoneCallWithReason(d.contact) ?: (false to "dial_failed")
                ActionResult(ok, "CALL('${d.contact}') -> success=$ok reason=$reason")
            }

            "SET_ALARM" -> {
                val ok = acc?.setSystemAlarm(d.alarmHour, d.alarmMinute, d.text.ifBlank { "Jarvis Alarm" }) ?: false
                ActionResult(ok, "SET_ALARM(${d.alarmHour}:${d.alarmMinute}) -> success=$ok")
            }

            "SAVE_MEMORY" -> {
                prefs.addMemory(d.memoryNote)
                ActionResult(true, "SAVE_MEMORY('${d.memoryNote}')")
            }

            "CREATE_PROJECT" -> {
                val name = d.projectName.ifBlank { "jarvis-site" }
                ProjectStore.createProject(context, name, d.fileContent.ifBlank { null })
                val v = ProjectStore.validateProject(context, name)
                lastBuildValidation = v
                lastBuildWritten = v.ok
                lastBuildProject = name
                val files = ProjectStore.listProjects(context)
                    .firstOrNull { it.name == ProjectStore.sanitizeProjectName(name) }
                    ?.files ?: emptyList()
                ActionResult(
                    v.ok,
                    "CREATE_PROJECT('$name') -> files=[${files.joinToString(", ")}], " +
                        "validation=${if (v.ok) "OK" else "FAILED missing=${v.missingFiles} empty=${v.emptyFiles}"}, " +
                        "preview at ${ProjectStore.localhostUrl(name)}"
                )
            }

            "WRITE_PROJECT_FILE" -> {
                // Builder §5a: named -> verbatim; unnamed -> most
                // recently touched project; unnamed + none -> ASK
                // (never invent a name, never CREATE as a modify).
                var name = d.projectName.ifBlank { ProjectStore.lastTouchedProject(context) ?: "" }
                if (name.isBlank()) {
                    // B-M8: never wait for an answer to an unspoken question.
                    val answer = askUserVoice("Kaun se project me likhun? Project ka naam bolo.")?.trim() ?: ""
                    name = if (answer.isNotBlank()) {
                        ProjectStore.sanitizeProjectName(answer)
                    } else {
                        ""
                    }
                }
                if (name.isBlank()) {
                    lastBuildValidation = null
                    lastBuildWritten = false
                    ActionResult(false, "WRITE_PROJECT_FILE blocked: project naam nahi mila (written=false)")
                } else {
                    val file = d.fileName.ifBlank { "index.html" }
                    val written = ProjectStore.writeFile(context, name, file, d.fileContent)
                    val v = ProjectStore.validateProject(context, name)
                    lastBuildValidation = v
                    lastBuildWritten = written
                    lastBuildProject = name
                    ActionResult(
                        written && v.ok,
                        "WRITE_PROJECT_FILE('$name/$file', written=$written, ${d.fileContent.length} chars) -> " +
                            "validation=${if (v.ok) "OK" else "FAILED missing=${v.missingFiles} empty=${v.emptyFiles}"}, " +
                            "preview at ${ProjectStore.localhostUrl(name)}"
                    )
                }
            }

            "OPEN_BROWSER" -> {
                // Browser §6: in-app only, never via the
                // accessibility OPEN_URL path (which may leave the
                // app). Background by default; foreground show only
                // on explicit user request, and only for
                // user-asked / user-approved URLs (§7 gate).
                // 1.4.20 (item 3): ONE search rule — spoken query
                // text maps to a Google search via
                // browserTargetForInput, not the invalid
                // "https://" prefix normalizeBrowserUrl produced.
                // Real URLs pass through unchanged; the approval
                // gate below is unchanged.
                val raw = d.browserUrl
                val url = browserTargetForInput(raw)
                if (url.isEmpty()) {
                    ActionResult(false, "OPEN_BROWSER blocked: empty/blocked URL")
                } else {
                    val kind = browserApprovalKind(url)
                    if (kind == null) {
                        pendingBrowserApprovalUrl = url
                        pendingBrowserShowPage = d.browserShowPage
                        speak("Ye page kholun? $url")
                        ActionResult(false, "OPEN_BROWSER awaiting approval for '$url'")
                    } else if (d.browserShowPage) {
                        if (onShowBrowserPage != null) {
                            onShowBrowserPage?.invoke(url)
                            ActionResult(true, "OPEN_BROWSER foreground show '$url' ($kind)")
                        } else {
                            // No foreground host (app UI not alive):
                            // fall back to the background answer
                            // rather than dropping the request.
                            // NOTE: the load runs FIRST — lastBrowserLoadOk
                            // is only meaningful after it returns.
                            val loadSummary = executeBrowserLoad(url, speakSummary = d.browserSpeakSummary)
                            ActionResult(
                                lastBrowserLoadOk,
                                loadSummary + " (foreground host unavailable, background answer diya)"
                            )
                        }
                    } else {
                        val loadSummary = executeBrowserLoad(url, speakSummary = d.browserSpeakSummary)
                        ActionResult(
                            lastBrowserLoadOk,
                            loadSummary + " ($kind)"
                        )
                    }
                }
            }

            else -> ActionResult(false, "UNKNOWN_ACTION(${d.action})")
        }
    }
}
