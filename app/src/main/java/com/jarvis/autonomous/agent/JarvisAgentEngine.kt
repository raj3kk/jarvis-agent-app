package com.jarvis.autonomous.agent

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.provider.Settings
import android.net.NetworkCapabilities
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.data.AiProvider
import com.jarvis.autonomous.data.ChatSummary
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.data.LearnedRoutine
import com.jarvis.autonomous.data.LearnedRoutineStore
import com.jarvis.autonomous.data.LearnedStep
import com.jarvis.autonomous.data.MemoryIndex
import com.jarvis.autonomous.data.ProjectPreviewServer
import com.jarvis.autonomous.data.ProjectStore
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // 1.4.21 Agent Mode: planner + executor, owned by the engine
    // (same ownership shape as llmClient above). Lazy so the
    // executor receives a fully-constructed engine.
    private val planExecutor: AgentPlanExecutor by lazy { AgentPlanExecutor(this) }
    private var activeTaskJob: Job? = null
    // 1.4.14 (D1): announce dedupe — in-memory only, dies with
    // process death, never persisted. Key = trimmed sender +
    // trimmed text of the notification; value = elapsedRealtime
    // ms when that announce actually happened. Pruned on every
    // check (no timer, no handler); expired keys are dropped,
    // never consulted.
    private val recentAnnounces = mutableMapOf<String, Long>()
    private var chatRestored = false

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
        runCatching {
            prefs.saveChatHistory(JarvisStateRepository.chatHistory.value)
        }
    }

    fun isTaskRunning(): Boolean = activeTaskJob?.isActive == true

    fun cancelActiveTask(reason: String = "Task stopped by user.") {
        activeTaskJob?.cancel()
        activeTaskJob = null
        clearTaskScopedState()
        JarvisStateRepository.setAgentState(AgentRunState.IDLE, reason)
        JarvisStateRepository.setCurrentGoal("")
        JarvisStateRepository.addLog(reason)
    }

    // ---- 1.4.21 Agent Mode (planner trigger, resume, executor access) ----

    /** Frozen trigger (§3h): goal names GitHub / Supabase / Vercel /
     *  deploy, or the user says "agent mode" (Latin or
     *  transliterated Devanagari — the same transliteration the
     *  stop-command check uses). */
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
                            speak("YouTube nahi khul paya.")
                            return@launch
                        }
                        // 1.4.7 (B): no blind 3000ms wait here —
                        // findFirstYouTubeVideo polls fresh
                        // snapshots itself (3 x 1800ms), so the
                        // blind wait only added 3s on every path.
                        val video = acc.findFirstYouTubeVideo()
                        if (video == null) {
                            speak("Pehli video nahi mili. Search results khule hain, tum khud tap kar lo.")
                            return@launch
                        }
                        val title = video.text.ifBlank { video.contentDesc }
                        // A-C1: negation-aware confirmation — "mat
                        // chalao" must NEVER play. askUserVoice also
                        // never waits when the question was unspoken.
                        val reply = askUserVoice("Pehli video $title mili hai. Chalau?")
                        if (reply != null && isYesReply(reply)) {
                            val ok = acc.tapNode(video.id)
                            speak(if (ok) "Chala diya." else "Tap nahi ho paya.")
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
                            val ok = acc.sendWhatsAppMessage(contact, message)
                            speak(if (ok) "Message bhej diya." else "Send button nahi mila — chat khula hai, message likha hua hai, tum send tap kar do.")
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
                    speak("Live search nahi mila — dobara koshish karo ya Browser me kholo.")
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
                scope.launch { speak(if (ok) "Torch on kar diya." else "Torch on nahi ho payi.") }
                return true
            }
            if (lower.contains("torch off") || lower.contains("torch bujhao") ||
                lower.contains("flashlight off")
            ) {
                val ok = acc.toggleFlashlight(false)
                scope.launch { speak(if (ok) "Torch off kar diya." else "Torch off nahi ho payi.") }
                return true
            }
            // Volume: "volume 70 kar do"
            val volMatch = Regex("volume\\s+(\\d{1,3})").find(lower)
            if (volMatch != null) {
                val pct = volMatch.groupValues[1].toInt().coerceIn(0, 100)
                val ok = acc.setMediaVolumePercent(pct)
                scope.launch { speak(if (ok) "Volume $pct percent kar diya." else "Volume set nahi ho paya.") }
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
                        speak("Music pause nahi ho paya.")
                    } else {
                        delay(700)
                        val postActive = acc.isMusicActive()
                        speak(
                            when {
                                preActive && !postActive -> "Music pause kar diya."
                                !preActive -> "Music pehle se nahi baj raha tha, isliye pause karne ko kuch nahi tha."
                                else -> "Music state badla nahi dikh raha — player khula hai ya nahi check karo."
                            }
                        )
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
                        speak("Music play nahi ho paya.")
                    } else {
                        delay(700)
                        val postActive = acc.isMusicActive()
                        speak(
                            when {
                                !preActive && postActive -> "Music play kar diya."
                                preActive && postActive -> "Music pehle se baj raha tha."
                                else -> "Music state badla nahi dikh raha — player khula hai ya nahi check karo."
                            }
                        )
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
                        speak("Agla gaana nahi chal paya.")
                    } else {
                        delay(700)
                        speak(
                            if (acc.isMusicActive()) "Agla gaana chala diya."
                            else "Music state badla nahi dikh raha — player khula hai ya nahi check karo."
                        )
                    }
                }
                return true
            }
            if (lower.contains("previous song") || lower.contains("pichla gaana")) {
                val ok = acc.controlMediaPlayback("PREVIOUS")
                scope.launch {
                    if (!ok) {
                        speak("Pichla gaana nahi chal paya.")
                    } else {
                        delay(700)
                        speak(
                            if (acc.isMusicActive()) "Pichla gaana chala diya."
                            else "Music state badla nahi dikh raha — player khula hai ya nahi check karo."
                        )
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
                    scope.launch { speak(msg) }
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
                    scope.launch { speak(if (ok) "Alarm $hour baj kar $minute minute par laga diya." else "Alarm nahi lag paya.") }
                    return true
                }
            }
            // Open app: "<app> kholo" (after YouTube/WhatsApp routines above)
            val openMatch = Regex("(.+?)\\s+(?:kholo|khol\\s+do|open\\s+karo)\\s*$").find(lower)
            if (openMatch != null) {
                val app = openMatch.groupValues[1].trim()
                if (app.isNotBlank() && app.length < 30) {
                    val ok = acc.launchApp(app)
                    scope.launch { speak(if (ok) "$app khol diya." else "$app nahi khul paya.") }
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
            cancelActiveTask("Stopped on voice command.")
            scope.launch { speak("Theek hai bhai, ruk gaya.") }
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

        // 1.4.21 Agent Mode: the planner runs when the goal names
        // an external destination (GitHub / Supabase / Vercel /
        // deploy) or the user says "agent mode". Everything else
        // keeps the existing runAutonomousLoop path untouched — a
        // plain "website banao" stays on the 1.4.20 builder loop.
        // Custom routines (above) and no-AI routines win first, so
        // "github kholo" still opens the app, not a plan.
        if (isAgentModeCommand(effectiveGoal)) {
            clearTaskScopedState()
            activeTaskJob?.cancel()
            activeTaskJob = scope.launch {
                planExecutor.startNewPlan(effectiveGoal)
            }
            return
        }

        // Memory-first consult before the LLM loop: past turns
        // (local MemoryIndex only) are offered to the first LLM
        // step as context, never as a fabricated answer.
        val memorySummaries = memoryIndex.search(effectiveGoal)
            .mapNotNull { memoryIndex.getSummary(it) }
        val memoryContext = memorySummaries.joinToString(" | ")
        if (memoryContext.isNotBlank()) {
            JarvisStateRepository.addLog("Memory-first: ${memorySummaries.size} pehle ki baat-cheet mili.")
        }

        // Cancel any previous running task and start the new multi-step loop
        activeTaskJob?.cancel()
        activeTaskJob = scope.launch {
            runAutonomousLoop(effectiveGoal, memoryContext)
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
                    speak("Internet nahi hai — is step ke liye network chahiye, isliye replay yahin rok raha hoon. Local kaam ho gaya hai, baaki net aane par dobara bolo.")
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
                val ok = executeLearnedStep(step, acc)
                if (!ok) {
                    JarvisStateRepository.addLog("Learned replay mismatch at ${step.action} — falling back to LLM loop")
                    runAutonomousLoop(trimmed)
                    return@launch
                }
                if (step.waitMs > 0) delay(step.waitMs)
            }
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
     * Records ONLY the safe, repeatable subset of a loop step
     * into the current routine buffer. Send / pay / delete /
     * type / tap / call / project steps are never recorded:
     * their targets (contacts, message text, node ids, file
     * content) must never become a replayable template, and
     * recordStep() itself rejects sensitive-looking values as
     * a backstop. finishRoutine() persists the buffer only for
     * a verified, completed task; failures clear it.
     */
    private fun recordLoopStep(d: AgentDecision) {
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
        // Offline gate (1.4.0): cloud planning needs network.
        // Local (on-device) chat still works offline; no-AI local
        // routines already returned before this loop. Speak a
        // clear warning and do local work only — never fake a
        // cloud answer while offline.
        if (!isOnline() && prefs.provider != AiProvider.LOCAL) {
            JarvisStateRepository.addChatMessage("You", currentGoal)
            speak("Internet nahi hai — online kaam (AI planning, browsing, cloud actions) abhi nahi ho payega. Sirf phone ke andar wale kaam kar sakta hoon, jaise torch, volume, ya pehle se downloaded cheezein.")
            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Offline — local work only")
            return
        }
        if (!chatRestored) {
            chatRestored = true
            runCatching {
                JarvisStateRepository.restoreChatHistory(prefs.getSavedChatHistory())
            }
        }
        JarvisStateRepository.addChatMessage("You", currentGoal)
        persistChat()
        JarvisStateRepository.setCurrentGoal(currentGoal)

        val previousActions = mutableListOf<String>()
        val maxSteps = prefs.maxAutonomousSteps
        var step = 1
        var lastScreenHash = 0
        var consecutiveSameScreenCount = 0
        var parseErrorRetries = 0
        var rateLimitRetries = 0

        try {
            while (step <= maxSteps) {
                // A-M7: per-step connectivity re-check — offline
                // mid-task = pause (warn, do not complete, do not
                // persist a routine), not a faked continuation.
                if (!isOnline() && prefs.provider != AiProvider.LOCAL) {
                    speak("Internet chala gaya hai — kaam yahin rok raha hoon, complete nahi bol raha. Net aane par dobara bolo.")
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
                            clearTaskScopedState()
                            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Paused — $gateReason")
                            JarvisStateRepository.setCurrentGoal("")
                            return
                        }
                        GateOutcome.ABORT_TASK -> {
                            speak("Theek hai, kaam rok diya.")
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
                    recordLoopStep(decision)
                    lastExecutedAction = decision.action
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
                            // The held completion reply (C2) may be
                            // spoken now — the evidence backs it.
                            // Builder §6: validated build summary,
                            // spoken AND in chat — unless the LLM's own
                            // reply already is that summary (same files
                            // + preview line), in which case speaking
                            // it twice would just be noise.
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
                                    val summary = "Ho gaya bhai! Project '$projectName' ban gaya — " +
                                        "files: ${builtFiles.joinToString(", ")}. " +
                                        "Preview: ${ProjectStore.localhostUrl(projectName)} — " +
                                        "Projects tab me Preview dabao, ya Browser me kholo (full-screen, wahi preview)."
                                    JarvisStateRepository.addChatMessage("Jarvis", summary)
                                    speak(summary, alreadyPostedToChat = true)
                                } else {
                                    deferredCompletionReply?.let {
                                        JarvisStateRepository.addChatMessage("Jarvis", it)
                                        persistChat()
                                        speak(it, alreadyPostedToChat = true)
                                    }
                                }
                            } else {
                                deferredCompletionReply?.let {
                                    JarvisStateRepository.addChatMessage("Jarvis", it)
                                    persistChat()
                                    speak(it, alreadyPostedToChat = true)
                                }
                            }
                            // Verified completion: persist the learned
                            // routine + index this turn for memory-first
                            // lookup, then the post-task prompt. The mic
                            // loop (service-owned) is NEVER stopped here —
                            // it keeps listening for the answer.
                            val finishedRoutine = learnedRoutineStore.finishRoutine(initialGoal, verified = true)
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
                            speak("Aur kya karna hai?")
                            return
                        }
                        StepEvidence.FAILED -> {
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
            // 1.4.23-PRO Item 2 (C5): the old line claimed the steps
            // were "complete" for a task that did NOT complete. The
            // replacement states the non-completion, no success claim.
            speak("Bhai, kaam $maxSteps steps me poora nahi ho paya — dobara batao to phir se koshish karunga.")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
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
     * Per-kind expected-text checks (TYPE_TEXT / OPEN_APP /
     * WHATSAPP_SEND / YOUTUBE_PLAY / OPEN_BROWSER-foreground)
     * come in a later step — this step is the interface + default
     * screen-change rule only.
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
        val screenChanged = verifySnapshot.formattedTree.hashCode() != preSnapshot.formattedTree.hashCode() ||
            verifySnapshot.packageName != preSnapshot.packageName ||
            verifySnapshot.elements != preSnapshot.elements
        return if (screenChanged) StepEvidence.VERIFIED else StepEvidence.AMBIGUOUS
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
