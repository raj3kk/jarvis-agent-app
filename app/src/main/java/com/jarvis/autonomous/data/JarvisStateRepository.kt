package com.jarvis.autonomous.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AgentRunState {
    IDLE,
    LISTENING,
    THINKING,
    EXECUTING_STEP,
    WAITING_USER_REPLY,
    SPEAKING
}

data class ChatEntry(
    val id: Long = System.currentTimeMillis(),
    val sender: String, // "You", "Jarvis", "System"
    val message: String,
    val timestamp: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
)

data class NotificationItem(
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

object JarvisStateRepository {
    private val _agentState = MutableStateFlow(AgentRunState.IDLE)
    val agentState: StateFlow<AgentRunState> = _agentState.asStateFlow()

    private val _currentGoal = MutableStateFlow("")
    val currentGoal: StateFlow<String> = _currentGoal.asStateFlow()

    private val _currentStepInfo = MutableStateFlow("Ready — Say 'Hey Jarvis' or tap the Core")
    val currentStepInfo: StateFlow<String> = _currentStepInfo.asStateFlow()

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _isForegroundRunning = MutableStateFlow(false)
    val isForegroundRunning: StateFlow<Boolean> = _isForegroundRunning.asStateFlow()

    private val _chatHistory = MutableStateFlow<List<ChatEntry>>(
        listOf(
            ChatEntry(
                sender = "Jarvis",
                message = "Aur bhai! Main Jarvis ready hoon. Koi bhi app kholna ho, WhatsApp pe baat karni ho, ya normal gup-shup karni ho—bas bol de!"
            )
        )
    )
    val chatHistory: StateFlow<List<ChatEntry>> = _chatHistory.asStateFlow()

    private val _executionLogs = MutableStateFlow<List<String>>(emptyList())
    val executionLogs: StateFlow<List<String>> = _executionLogs.asStateFlow()

    private val _recentNotifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val recentNotifications: StateFlow<List<NotificationItem>> = _recentNotifications.asStateFlow()

    fun setAgentState(state: AgentRunState, stepDescription: String? = null) {
        _agentState.value = state
        if (stepDescription != null) {
            _currentStepInfo.value = stepDescription
        }
    }

    fun setCurrentGoal(goal: String) {
        _currentGoal.value = goal
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _isAccessibilityConnected.value = connected
    }

    fun setForegroundRunning(running: Boolean) {
        _isForegroundRunning.value = running
    }

    fun addChatMessage(sender: String, message: String) {
        if (message.isBlank()) return
        val updated = (_chatHistory.value + ChatEntry(sender = sender, message = message)).takeLast(80)
        _chatHistory.value = updated
    }

    fun addLog(log: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $log"
        _executionLogs.value = (_executionLogs.value + entry).takeLast(150)
    }

    fun clearLogs() {
        _executionLogs.value = emptyList()
    }

    fun addNotification(item: NotificationItem) {
        _recentNotifications.value = (_recentNotifications.value + item).takeLast(25)
    }
}
