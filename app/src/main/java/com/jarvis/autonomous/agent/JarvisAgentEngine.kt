package com.jarvis.autonomous.agent

import android.content.Context
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.data.JarvisPreferences
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
    private val llmClient = LlmClient(prefs)
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
        val matchedRoutine = prefs.getRoutines().firstOrNull { routine ->
            lower.contains(routine.triggerPhrase.lowercase())
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

            else -> "UNKNOWN_ACTION(${d.action})"
        }
    }
}
