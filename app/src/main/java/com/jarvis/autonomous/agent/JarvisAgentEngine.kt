package com.jarvis.autonomous.agent

import android.content.Context
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.ProjectStore
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.service.JarvisAccessibilityService
import com.jarvis.autonomous.service.JarvisNotificationService
import com.jarvis.autonomous.service.ScreenSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class JarvisAgentEngine(
    private val context: Context,
    private val prefs: JarvisPreferences,
    private val onSpeakAndAwait: suspend (String) -> Unit,
    private val onRequestUserVoiceReply: suspend () -> String?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val llmClient = LlmClient(context, prefs)
    private var activeTaskJob: Job? = null
    private var chatRestored = false

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

        // Check if command matches any custom Voice Routine macro
        // (user's own macros win over built-in no-AI routines).
        val matchedRoutine = prefs.getRoutines().firstOrNull { routine ->
            lower.contains(routine.triggerPhrase.lowercase())
        }
        if (matchedRoutine == null) {
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

        // Cancel any previous running task and start the new multi-step loop
        activeTaskJob?.cancel()
        activeTaskJob = scope.launch {
            runAutonomousLoop(effectiveGoal)
        }
    }

    private suspend fun runAutonomousLoop(initialGoal: String) {
        var currentGoal = initialGoal
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
                    userGoal = currentGoal,
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
                }

                if (decision.isCompleted || decision.action == "NONE") {
                    JarvisStateRepository.setAgentState(
                        AgentRunState.IDLE,
                        "Task Completed — Ready for next command"
                    )
                    JarvisStateRepository.setCurrentGoal("")
                    return
                }

                // Wait for UI to settle before next observation
                delay(prefs.stepDelayMs)
                step++
            }

            JarvisStateRepository.setAgentState(
                AgentRunState.IDLE,
                "Reached max steps ($maxSteps). Ready."
            )
            onSpeakAndAwait("Bhai, maine $maxSteps steps complete kiye. Aage kya karna hai batao?")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            JarvisStateRepository.addLog("Agent Loop Error: ${e.message}")
            JarvisStateRepository.setAgentState(AgentRunState.IDLE, "Error: ${e.localizedMessage}")
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
                "CREATE_PROJECT('$name') -> saved in its own folder, preview at ${ProjectStore.localhostUrl(name)}"
            }

            "WRITE_PROJECT_FILE" -> {
                val name = d.projectName.ifBlank { "jarvis-site" }
                val file = d.fileName.ifBlank { "index.html" }
                ProjectStore.writeFile(context, name, file, d.fileContent)
                "WRITE_PROJECT_FILE('$name/$file', ${d.fileContent.length} chars)"
            }

            else -> "UNKNOWN_ACTION(${d.action})"
        }
    }
}
