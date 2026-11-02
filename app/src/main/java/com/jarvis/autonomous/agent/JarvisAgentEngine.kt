package com.jarvis.autonomous.agent

import android.content.Context
import android.net.ConnectivityManager
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
import com.jarvis.autonomous.data.ProjectStore
import com.jarvis.autonomous.service.JarvisAccessibilityService
import com.jarvis.autonomous.service.JarvisNotificationService
import com.jarvis.autonomous.service.ScreenSnapshot
import com.jarvis.autonomous.ui.BrowserBrowseStatus
import com.jarvis.autonomous.ui.JarvisBackgroundBrowser
import com.jarvis.autonomous.ui.LICENCE_SERVER_URL
import com.jarvis.autonomous.ui.LicencePrefs
import com.jarvis.autonomous.ui.normalizeBrowserUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class JarvisAgentEngine(
    private val context: Context,
    private val prefs: JarvisPreferences,
    private val onSpeakAndAwait: suspend (String) -> Unit,
    private val onRequestUserVoiceReply: suspend () -> String?,
    private val onShowBrowserPage: ((String) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val llmClient = LlmClient(context, prefs)
    private val learnedRoutineStore = LearnedRoutineStore(context)
    private val memoryIndex = MemoryIndex(context)
    private var activeTaskJob: Job? = null
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

    // Last build validation, set by the CREATE/WRITE branches so
    // the loop can repair (§5c) or summarise (§6) without a second
    // validation implementation.
    private var lastBuildValidation: ProjectStore.ProjectValidation? = null
    private var lastBuildProject: String = ""

    private fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (e: Exception) {
            JarvisStateRepository.addLog("isOnline check failed (${e.message}) — assuming online.")
            true
        }
    }

    private fun isAffirmativeReply(lower: String): Boolean {
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
        if (cmd.contains(url.lowercase()) || cmd.contains(hostNoWww)) return "user-asked"
        if (firstLabel.length >= 3 && cmd.contains(firstLabel)) return "user-asked"
        return null
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
        JarvisStateRepository.setAgentState(AgentRunState.IDLE, reason)
        JarvisStateRepository.setCurrentGoal("")
        JarvisStateRepository.addLog(reason)
    }

    /**
     * Proactively announces incoming WhatsApp / SMS messages when Auto-Announce is enabled.
     */
    fun announceIncomingNotification(sender: String, message: String) {
        if (isTaskRunning()) return
        scope.launch {
            val announcement = "Bhai, $sender ka message aaya hai: ${message.take(90)}"
            JarvisStateRepository.addChatMessage("Jarvis", announcement)
            onSpeakAndAwait(announcement)
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
            val announcement = "Bhai, call aa raha hai, $displayNameOrNumber se."
            JarvisStateRepository.addChatMessage("Jarvis", announcement)
            onSpeakAndAwait(announcement)
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
    private fun tryNoAiRoutine(lower: String, trimmed: String): Boolean {
        val acc = JarvisAccessibilityService.instance
        // YouTube: "youtube par <query> chalao/play/bajao"
        val ytMatch = Regex(
            "youtube\\s+(?:par|pe|me)?\\s*(.+?)\\s*(?:chalao|play|bajao|sunao|dikhao)\\s*$"
        ).find(lower)
        if (ytMatch != null) {
            val query = ytMatch.groupValues[1]
                .replace(Regex("^(par|pe|me)\\s+"), "").trim()
            if (query.isNotBlank()) {
                if (acc == null) {
                    scope.launch { onSpeakAndAwait("Accessibility service on nahi hai, isliye YouTube nahi khol sakta. Permissions screen se on karo.") }
                    return true
                }
                activeTaskJob?.cancel()
                activeTaskJob = scope.launch {
                    try {
                        onSpeakAndAwait("YouTube par $query dhoondh raha hoon.")
                        if (!acc.searchYouTube(query)) {
                            onSpeakAndAwait("YouTube nahi khul paya.")
                            return@launch
                        }
                        kotlinx.coroutines.delay(3000L)
                        val video = acc.findFirstYouTubeVideo()
                        if (video == null) {
                            onSpeakAndAwait("Pehli video nahi mili. Search results khule hain, tum khud tap kar lo.")
                            return@launch
                        }
                        val title = video.text.ifBlank { video.contentDesc }
                        onSpeakAndAwait("Pehli video $title mili hai. Chalau?")
                        val reply = onRequestUserVoiceReply()?.lowercase() ?: ""
                        if (reply.contains("haan") || reply.contains("yes") ||
                            reply.contains("chalao") || reply.contains("play") ||
                            reply.contains("bajao")
                        ) {
                            val ok = acc.tapNode(video.id)
                            onSpeakAndAwait(if (ok) "Chala diya." else "Tap nahi ho paya.")
                        } else {
                            onSpeakAndAwait("Theek hai, nahi chalaya.")
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
                    scope.launch { onSpeakAndAwait("Accessibility service on nahi hai, isliye WhatsApp nahi bhej sakta.") }
                    return true
                }
                activeTaskJob?.cancel()
                activeTaskJob = scope.launch {
                    try {
                        onSpeakAndAwait("$contact ko ye message bhejna hai: $message. Bhej dun?")
                        val reply = onRequestUserVoiceReply()?.lowercase() ?: ""
                        if (reply.contains("haan") || reply.contains("yes") ||
                            reply.contains("bhej") || reply.contains("send")
                        ) {
                            val ok = acc.sendWhatsAppMessage(contact, message)
                            onSpeakAndAwait(if (ok) "Message bhej diya." else "Send button nahi mila — chat khula hai, message likha hua hai, tum send tap kar do.")
                        } else {
                            onSpeakAndAwait("Theek hai, message nahi bheja.")
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
            scope.launch { onSpeakAndAwait(answer) }
            return true
        }
        if (acc != null) {
            // Torch on/off
            if (lower.contains("torch on") || lower.contains("torch jalao") ||
                lower.contains("flashlight on")
            ) {
                val ok = acc.toggleFlashlight(true)
                scope.launch { onSpeakAndAwait(if (ok) "Torch on kar diya." else "Torch on nahi ho payi.") }
                return true
            }
            if (lower.contains("torch off") || lower.contains("torch bujhao") ||
                lower.contains("flashlight off")
            ) {
                val ok = acc.toggleFlashlight(false)
                scope.launch { onSpeakAndAwait(if (ok) "Torch off kar diya." else "Torch off nahi ho payi.") }
                return true
            }
            // Volume: "volume 70 kar do"
            val volMatch = Regex("volume\\s+(\\d{1,3})").find(lower)
            if (volMatch != null) {
                val pct = volMatch.groupValues[1].toInt().coerceIn(0, 100)
                val ok = acc.setMediaVolumePercent(pct)
                scope.launch { onSpeakAndAwait(if (ok) "Volume $pct percent kar diya." else "Volume set nahi ho paya.") }
                return true
            }
            // Media: play/pause/next/previous
            if (lower.contains("music pause") || lower.contains("gaana roko") ||
                lower.contains("song pause")
            ) {
                acc.controlMediaPlayback("PLAY_PAUSE")
                scope.launch { onSpeakAndAwait("Music pause kar diya.") }
                return true
            }
            if (lower.contains("music play") || lower.contains("gaana chalao") ||
                lower.contains("song play")
            ) {
                acc.controlMediaPlayback("PLAY_PAUSE")
                scope.launch { onSpeakAndAwait("Music play kar diya.") }
                return true
            }
            if (lower.contains("next song") || lower.contains("agla gaana") ||
                lower.contains("next gaana")
            ) {
                acc.controlMediaPlayback("NEXT")
                scope.launch { onSpeakAndAwait("Agla gaana chala diya.") }
                return true
            }
            if (lower.contains("previous song") || lower.contains("pichla gaana")) {
                acc.controlMediaPlayback("PREVIOUS")
                scope.launch { onSpeakAndAwait("Pichla gaana chala diya.") }
                return true
            }
            // Direct call: "<name> ko call karo/lagao"
            val callMatch = Regex("(.+?)\\s+ko\\s+call\\s+(?:karo|lagao)\\s*$").find(lower)
            if (callMatch != null) {
                val contact = callMatch.groupValues[1].trim()
                if (contact.isNotBlank()) {
                    val ok = acc.makePhoneCall(contact)
                    scope.launch { onSpeakAndAwait(if (ok) "$contact ko call laga raha hoon." else "$contact ko call nahi lag payi — contact phone book me nahi mila ho sakta hai.") }
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
                    scope.launch { onSpeakAndAwait(if (ok) "Alarm $hour baj kar $minute minute par laga diya." else "Alarm nahi lag paya.") }
                    return true
                }
            }
            // Open app: "<app> kholo" (after YouTube/WhatsApp routines above)
            val openMatch = Regex("(.+?)\\s+(?:kholo|khol\\s+do|open\\s+karo)\\s*$").find(lower)
            if (openMatch != null) {
                val app = openMatch.groupValues[1].trim()
                if (app.isNotBlank() && app.length < 30) {
                    val ok = acc.launchApp(app)
                    scope.launch { onSpeakAndAwait(if (ok) "$app khol diya." else "$app nahi khul paya.") }
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

        // Check for immediate stop command
        val lower = trimmed.lowercase()
        if (lower in listOf("stop", "ruk ja", "ruko", "cancel", "jarvis stop", "bas kar")) {
            cancelActiveTask("Stopped on voice command.")
            scope.launch { onSpeakAndAwait("Theek hai bhai, ruk gaya.") }
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
                scope.launch { onSpeakAndAwait("Browsing ruk gaya, dobara koshish karun?") }
            }
        }

        // Pending browser approval: the immediately following
        // command is the answer. Affirmative -> load it; anything
        // else clears the proposal and the new command runs.
        val pendingUrl = pendingBrowserApprovalUrl
        if (pendingUrl != null && !justSetInterrupted) {
            pendingBrowserApprovalUrl = null
            if (isAffirmativeReply(lower)) {
                approvedBrowserUrls.add(pendingUrl)
                val showPage = pendingBrowserShowPage
                pendingBrowserShowPage = false
                activeTaskJob?.cancel()
                activeTaskJob = scope.launch {
                    if (showPage && onShowBrowserPage != null) {
                        onShowBrowserPage?.invoke(pendingUrl)
                        JarvisStateRepository.addChatMessage("Jarvis", "Page dikha raha hoon: $pendingUrl")
                    } else {
                        val summary = executeBrowserLoad(pendingUrl, speakSummary = true)
                        JarvisStateRepository.addLog(summary)
                    }
                    JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Ready for next command")
                    onSpeakAndAwait("Aur kya karna hai?")
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
        activeTaskJob = scope.launch {
            val acc = JarvisAccessibilityService.instance
            for (step in routine.steps) {
                if (!isOnline() && learnedRoutineStore.isNetworkDependent(step.action)) {
                    onSpeakAndAwait("Internet nahi hai — is step ke liye network chahiye, isliye replay yahin rok raha hoon. Local kaam ho gaya hai, baaki net aane par dobara bolo.")
                    JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Replay stopped offline")
                    return@launch
                }
                if (prefs.confirmSensitiveActions &&
                    learnedRoutineStore.isConfirmationRequired(step.action, step.target)
                ) {
                    onSpeakAndAwait("Ye step sensitive hai (${step.action}). Kar dun?")
                    val reply = onRequestUserVoiceReply()?.lowercase() ?: ""
                    if (!isAffirmativeReply(reply)) {
                        JarvisStateRepository.addLog("Learned replay: sensitive step declined — falling back to LLM loop")
                        runAutonomousLoop(trimmed)
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
            onSpeakAndAwait("Ho gaya — seekha hua kaam dobara kar diya. Aur kya karna hai?")
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
                val stepsJson = JSONArray()
                for (step in routine.steps) {
                    val obj = JSONObject()
                    obj.put("action", step.action)
                    obj.put("target_kind", "generic")
                    stepsJson.put(obj)
                }
                val body = JSONObject()
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
                JarvisStateRepository.addLog("Global-learning upload: HTTP $code (generic template only)")
                conn.disconnect()
            } catch (e: Exception) {
                JarvisStateRepository.addLog("Global-learning upload failed (ignored): ${e.message}")
            }
        }
    }

    private suspend fun runAutonomousLoop(initialGoal: String, memoryContext: String = "") {
        var currentGoal = initialGoal
        loopUserCommand = initialGoal
        // Offline gate (1.4.0): cloud planning needs network.
        // Local (on-device) chat still works offline; no-AI local
        // routines already returned before this loop. Speak a
        // clear warning and do local work only — never fake a
        // cloud answer while offline.
        if (!isOnline() && prefs.provider != AiProvider.LOCAL) {
            JarvisStateRepository.addChatMessage("You", currentGoal)
            onSpeakAndAwait("Internet nahi hai — online kaam (AI planning, browsing, cloud actions) abhi nahi ho payega. Sirf phone ke andar wale kaam kar sakta hoon, jaise torch, volume, ya pehle se downloaded cheezein.")
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

        try {
            while (step <= maxSteps) {
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

                // Stuck-loop detection
                val currentHash = snapshot.formattedTree.hashCode()
                if (step > 1 && currentHash == lastScreenHash) {
                    consecutiveSameScreenCount++
                } else {
                    consecutiveSameScreenCount = 0
                }
                lastScreenHash = currentHash
                val isStuck = consecutiveSameScreenCount >= 1

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

                val decision = llmClient.decideNextStep(
                    userGoal = if (step == 1 && memoryContext.isNotBlank()) {
                        "$currentGoal\n(Pehli baat-cheet se yaad: $memoryContext)"
                    } else {
                        currentGoal
                    },
                    stepNumber = step,
                    previousActions = previousActions,
                    recentChat = recentChat,
                    recentNotifications = recentNotifs,
                    screenTree = snapshot.formattedTree,
                    hasSensitivePaymentScreen = snapshot.hasSensitivePaymentPrompt,
                    isStuckWarning = isStuck,
                    screenImageBase64 = visionBase64
                )

                JarvisStateRepository.addLog(
                    "Step $step Decision: action=${decision.action}, thought=${decision.thought}"
                )

                // Speak reply if non-empty — but in fast mode (default),
                // intermediate step updates are chat-only, NOT spoken aloud.
                // Speaking every step serialises seconds of TTS in front of
                // the next action and is the biggest perceived slowdown.
                // Final answers, questions and casual chat are always spoken.
                if (decision.reply.isNotBlank()) {
                    JarvisStateRepository.addChatMessage("Jarvis", decision.reply)
                    persistChat()
                    val shouldSpeak = decision.isCompleted || decision.askUser ||
                        decision.action == "NONE" || prefs.speakIntermediateSteps
                    if (shouldSpeak) {
                        JarvisStateRepository.setAgentState(
                            AgentRunState.SPEAKING,
                            "Speaking: ${decision.reply}"
                        )
                        onSpeakAndAwait(decision.reply)
                    }
                }

                // If Jarvis needs to ask the user a mid-task question, listen for reply and continue
                if (decision.askUser) {
                    JarvisStateRepository.setAgentState(
                        AgentRunState.WAITING_USER_REPLY,
                        "Waiting for your voice answer..."
                    )
                    val userAnswer = onRequestUserVoiceReply()
                    if (!userAnswer.isNullOrBlank()) {
                        JarvisStateRepository.addChatMessage("You", userAnswer)
                        persistChat()
                        currentGoal = "$currentGoal (User clarified: $userAnswer)"
                        previousActions.add("Asked user: '${decision.reply}' -> User answered: '$userAnswer'")
                        step++
                        continue
                    } else {
                        JarvisStateRepository.setAgentState(
                            AgentRunState.IDLE,
                            "Paused — No voice reply received."
                        )
                        return
                    }
                }

                // Execute the chosen action
                if (decision.action != "NONE") {
                    JarvisStateRepository.setAgentState(
                        AgentRunState.EXECUTING_STEP,
                        "Step $step: Executing ${decision.action}..."
                    )
                    val actionSummary = executeDecisionAction(decision, accService)
                    previousActions.add("Step $step: $actionSummary")
                    recordLoopStep(decision)
                }

                // Builder §5c: a failed build validation is NEVER a
                // success. One repair step (re-write the missing /
                // empty files on the next loop step), never a success
                // summary, never a silent return.
                if (decision.action in listOf("CREATE_PROJECT", "WRITE_PROJECT_FILE")) {
                    val v = lastBuildValidation
                    if (v != null && !v.ok) {
                        val detail = (v.missingFiles.map { "$it missing" } +
                            v.emptyFiles.map { "$it khaali" }).joinToString(", ")
                        val repairNote = "Build validation fail ho gaya — $detail. In files ko dobara likh raha hoon, success tabhi bolunga jab validation OK ho."
                        JarvisStateRepository.addChatMessage("Jarvis", repairNote)
                        onSpeakAndAwait(repairNote)
                        currentGoal = "$initialGoal (Repair: $detail dobara likhna hai)"
                        step++
                        continue
                    }
                }

                if (decision.isCompleted || decision.action == "NONE") {
                    // Builder §6: validated build summary, spoken
                    // AND in chat — unless the LLM's own reply
                    // already is that summary (same files + preview
                    // line), in which case speaking it twice would
                    // just be noise.
                    if (decision.action in listOf("CREATE_PROJECT", "WRITE_PROJECT_FILE") &&
                        lastBuildValidation?.ok == true
                    ) {
                        val projectName = ProjectStore.sanitizeProjectName(
                            decision.projectName.ifBlank { lastBuildProject.ifBlank { "jarvis-site" } }
                        )
                        val builtFiles = ProjectStore.listProjects(context)
                            .firstOrNull { it.name == projectName }?.files ?: emptyList()
                        if (!decision.reply.contains("Browser me kholo")) {
                            val summary = "Ho gaya bhai! Project '$projectName' ban gaya — " +
                                "files: ${builtFiles.joinToString(", ")}. " +
                                "Preview: ${ProjectStore.localhostUrl(projectName)} — " +
                                "Projects tab me Preview dabao, ya Browser me kholo (full-screen, wahi preview)."
                            JarvisStateRepository.addChatMessage("Jarvis", summary)
                            onSpeakAndAwait(summary)
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
                    onSpeakAndAwait("Aur kya karna hai?")
                    return
                }

                // Wait for UI to settle before next observation
                delay(prefs.stepDelayMs)
                step++
            }

            learnedRoutineStore.clearPending()
            JarvisStateRepository.setAgentState(
                AgentRunState.IDLE,
                "Reached max steps ($maxSteps). Ready."
            )
            onSpeakAndAwait("Bhai, maine $maxSteps steps complete kiye. Aur kya karna hai?")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            learnedRoutineStore.clearPending()
            JarvisStateRepository.addLog("Agent Loop Error: ${e.message}")
            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Error: ${e.localizedMessage}")
        }
    }

    /**
     * Browser mode (a): background load + extracted answer, no
     * tab switch, no visible load. Non-HTTPS gets the spoken
     * warning before any summary. Never fabricates content:
     * interrupted / failed loads speak exactly that.
     */
    private suspend fun executeBrowserLoad(url: String, speakSummary: Boolean): String {
        val result = JarvisBackgroundBrowser.browse(context, url)
        return when (result.status) {
            BrowserBrowseStatus.COMPLETED -> {
                if (result.isNonHttps) {
                    onSpeakAndAwait(
                        "Note: ye page secure (https) nahi hai, " +
                            "isliye is par koi password ya payment mat daalna."
                    )
                }
                val summary = listOf(result.title, result.mainText.take(500))
                    .filter { it.isNotBlank() }.joinToString(" — ")
                if (speakSummary && summary.isNotBlank()) {
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

    private suspend fun executeDecisionAction(
        d: AgentDecision,
        acc: JarvisAccessibilityService?
    ): String {
        return when (d.action) {
            "OPEN_APP" -> {
                val ok = acc?.launchApp(d.appName) ?: false
                delay(800L) // Cold start is observed on the next step anyway
                "OPEN_APP('${d.appName}') -> success=$ok"
            }

            "YOUTUBE_SEARCH" -> {
                val ok = acc?.searchYouTube(d.text.ifBlank { d.appName }) ?: false
                delay(900L)
                "YOUTUBE_SEARCH('${d.text}') -> success=$ok"
            }

            "YOUTUBE_PLAY" -> {
                // Search + play the first video (LLM path: direct, no
                // confirm — the voice routine path asks first instead).
                val query = d.text.ifBlank { d.appName }
                val opened = acc?.searchYouTube(query) ?: false
                delay(3000L)
                val video = acc?.findFirstYouTubeVideo()
                val ok = if (video != null) acc.tapNode(video.id) else false
                "YOUTUBE_PLAY('$query') -> opened=$opened, video='${video?.text?.ifBlank { video?.contentDesc }}', played=$ok"
            }

            "WHATSAPP_SEND" -> {
                // Caller (LLM prompt) must only use this after the user
                // explicitly said send; the voice routine path confirms
                // first. A sent message cannot be unsent reliably.
                val ok = acc?.sendWhatsAppMessage(d.contact, d.text) ?: false
                "WHATSAPP_SEND(to='${d.contact}') -> success=$ok"
            }

            "OPEN_URL" -> {
                val ok = acc?.openUrlOrDeepLink(d.text) ?: false
                delay(800L)
                "OPEN_URL('${d.text}') -> success=$ok"
            }

            "REPLY_NOTIFICATION" -> {
                val ok = JarvisNotificationService.sendDirectReply(d.contact, d.text)
                "REPLY_NOTIFICATION(to='${d.contact}', msg='${d.text}') -> success=$ok"
            }

            "TAP_NODE" -> {
                val ok = acc?.tapNode(d.nodeId) ?: false
                "TAP_NODE(${d.nodeId}) -> success=$ok"
            }

            "TAP_XY" -> {
                val ok = acc?.tapCoordinates(d.x, d.y) ?: false
                "TAP_XY(${d.x}, ${d.y}) -> success=$ok"
            }

            "LONG_PRESS_XY" -> {
                val ok = acc?.longPressCoordinates(d.x, d.y) ?: false
                "LONG_PRESS_XY(${d.x}, ${d.y}) -> success=$ok"
            }

            "TYPE_TEXT" -> {
                val nodeId = if (d.nodeId > 0) d.nodeId else null
                val ok = acc?.typeText(nodeId, d.text, d.pressEnter) ?: false
                "TYPE_TEXT(node=$nodeId, text='${d.text}', enter=${d.pressEnter}) -> success=$ok"
            }

            "SCROLL" -> {
                val ok = acc?.scrollScreen(d.direction) ?: false
                "SCROLL(${d.direction}) -> success=$ok"
            }

            "NAVIGATE" -> {
                val ok = acc?.performSystemNavigation(d.navTarget) ?: false
                "NAVIGATE(${d.navTarget}) -> success=$ok"
            }

            "MEDIA_CONTROL" -> {
                val ok = acc?.controlMediaPlayback(d.text) ?: false
                "MEDIA_CONTROL(${d.text}) -> success=$ok"
            }

            "COPY_CLIPBOARD" -> {
                val ok = acc?.copyToClipboard(d.text) ?: false
                "COPY_CLIPBOARD('${d.text.take(25)}') -> success=$ok"
            }

            "WAIT" -> {
                delay(1000L)
                "WAIT(1000ms)"
            }

            "FLASHLIGHT" -> {
                val ok = acc?.toggleFlashlight(d.enableFlag) ?: false
                "FLASHLIGHT(${d.enableFlag}) -> success=$ok"
            }

            "VOLUME" -> {
                val ok = acc?.setMediaVolumePercent(d.volumePercent) ?: false
                "VOLUME(${d.volumePercent}%) -> success=$ok"
            }

            "CALL" -> {
                val ok = acc?.makePhoneCall(d.contact) ?: false
                "CALL('${d.contact}') -> success=$ok"
            }

            "SET_ALARM" -> {
                val ok = acc?.setSystemAlarm(d.alarmHour, d.alarmMinute, d.text.ifBlank { "Jarvis Alarm" }) ?: false
                "SET_ALARM(${d.alarmHour}:${d.alarmMinute}) -> success=$ok"
            }

            "SAVE_MEMORY" -> {
                prefs.addMemory(d.memoryNote)
                "SAVE_MEMORY('${d.memoryNote}')"
            }

            "CREATE_PROJECT" -> {
                val name = d.projectName.ifBlank { "jarvis-site" }
                ProjectStore.createProject(context, name, d.fileContent.ifBlank { null })
                val v = ProjectStore.validateProject(context, name)
                lastBuildValidation = v
                lastBuildProject = name
                val files = ProjectStore.listProjects(context)
                    .firstOrNull { it.name == ProjectStore.sanitizeProjectName(name) }
                    ?.files ?: emptyList()
                "CREATE_PROJECT('$name') -> files=[${files.joinToString(", ")}], " +
                    "validation=${if (v.ok) "OK" else "FAILED missing=${v.missingFiles} empty=${v.emptyFiles}"}, " +
                    "preview at ${ProjectStore.localhostUrl(name)}"
            }

            "WRITE_PROJECT_FILE" -> {
                // Builder §5a: named -> verbatim; unnamed -> most
                // recently touched project; unnamed + none -> ASK
                // (never invent a name, never CREATE as a modify).
                var name = d.projectName.ifBlank { ProjectStore.lastTouchedProject(context) ?: "" }
                if (name.isBlank()) {
                    onSpeakAndAwait("Kaun se project me likhun? Project ka naam bolo.")
                    val answer = onRequestUserVoiceReply()?.trim() ?: ""
                    name = if (answer.isNotBlank()) {
                        ProjectStore.sanitizeProjectName(answer)
                    } else {
                        ""
                    }
                }
                if (name.isBlank()) {
                    lastBuildValidation = null
                    "WRITE_PROJECT_FILE blocked: project naam nahi mila (written=false)"
                } else {
                    val file = d.fileName.ifBlank { "index.html" }
                    val written = ProjectStore.writeFile(context, name, file, d.fileContent)
                    val v = ProjectStore.validateProject(context, name)
                    lastBuildValidation = v
                    lastBuildProject = name
                    "WRITE_PROJECT_FILE('$name/$file', written=$written, ${d.fileContent.length} chars) -> " +
                        "validation=${if (v.ok) "OK" else "FAILED missing=${v.missingFiles} empty=${v.emptyFiles}"}, " +
                        "preview at ${ProjectStore.localhostUrl(name)}"
                }
            }

            "OPEN_BROWSER" -> {
                // Browser §6: in-app only, never via the
                // accessibility OPEN_URL path (which may leave the
                // app). Background by default; foreground show only
                // on explicit user request, and only for
                // user-asked / user-approved URLs (§7 gate).
                val raw = d.browserUrl
                val url = normalizeBrowserUrl(raw)
                if (url.isEmpty()) {
                    "OPEN_BROWSER blocked: empty/blocked URL"
                } else {
                    val kind = browserApprovalKind(url)
                    if (kind == null) {
                        pendingBrowserApprovalUrl = url
                        pendingBrowserShowPage = d.browserShowPage
                        onSpeakAndAwait("Ye page kholun? $url")
                        "OPEN_BROWSER awaiting approval for '$url'"
                    } else if (d.browserShowPage) {
                        if (onShowBrowserPage != null) {
                            onShowBrowserPage?.invoke(url)
                            "OPEN_BROWSER foreground show '$url' ($kind)"
                        } else {
                            // No foreground host (app UI not alive):
                            // fall back to the background answer
                            // rather than dropping the request.
                            executeBrowserLoad(url, speakSummary = d.browserSpeakSummary) +
                                " (foreground host unavailable, background answer diya)"
                        }
                    } else {
                        executeBrowserLoad(url, speakSummary = d.browserSpeakSummary) + " ($kind)"
                    }
                }
            }

            else -> "UNKNOWN_ACTION(${d.action})"
        }
    }
}
