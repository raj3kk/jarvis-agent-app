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

/** 1.4.2 bug-catcher: one structured diagnostics line. `area` is one
 *  of mic|overlay|llm|nav|crash. `message` must NEVER contain secrets
 *  (API keys, tokens, full licence keys, passwords, contact/message
 *  text, URLs carrying ?key=) — [addDiagnostic] strips key query
 *  params defensively, but callers must still pass clean values. */
data class DiagnosticEntry(
    val time: String,
    val area: String,
    val code: String,
    val message: String
)

/** 1.4.2: a terminal mic death that the user must SEE (banner +
 *  spoken reason + Retry), not a log line. `code` is the raw
 *  SpeechRecognizer error code (-1 when unknown). */
data class MicFatalState(
    val reason: String,
    val code: Int,
    val timeMs: Long = System.currentTimeMillis()
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

    // 1.4.2 diagnostics store (bug catcher). In-memory mirror of the
    // persisted diagnostics.jsonl file (see JarvisDiagnosticsFile);
    // the file survives a crash, this list does not — both are
    // written on every event so a crash never loses the trail.
    private val _diagnostics = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val diagnostics: StateFlow<List<DiagnosticEntry>> = _diagnostics.asStateFlow()

    // 1.4.2: last LLM failure, surfaced verbatim (HTTP code + body
    // message) in AI Brain — never the key, never the request URL.
    private val _lastLlmError = MutableStateFlow<String?>(null)
    val lastLlmError: StateFlow<String?> = _lastLlmError.asStateFlow()

    // 1.4.2: terminal mic death + live mic state, written by the
    // foreground service, read by the command-tab banner.
    private val _micFatal = MutableStateFlow<MicFatalState?>(null)
    val micFatal: StateFlow<MicFatalState?> = _micFatal.asStateFlow()

    private val _micLoopEnabled = MutableStateFlow(false)
    val micLoopEnabled: StateFlow<Boolean> = _micLoopEnabled.asStateFlow()

    private val _micListening = MutableStateFlow(false)
    val micListening: StateFlow<Boolean> = _micListening.asStateFlow()

    // 1.4.3: live STT path (deepgram|system) + switch reason +
    // last Deepgram transcript script, written by the mic manager
    // (and mirrored by the service's onSttPathChanged), read by
    // the Diagnostics report. Values only — never a key.
    private val _sttPath = MutableStateFlow("system")
    val sttPath: StateFlow<String> = _sttPath.asStateFlow()

    private val _sttPathReason = MutableStateFlow("not started")
    val sttPathReason: StateFlow<String> = _sttPathReason.asStateFlow()

    private val _lastTranscriptScript = MutableStateFlow("latin")
    val lastTranscriptScript: StateFlow<String> = _lastTranscriptScript.asStateFlow()

    // 1.4.5 circuit breaker: true on the launch where greeting
    // speak was skipped because the previous process died during
    // greeting speak. In-memory only (main process); the skip
    // itself writes no GREET_START, so the breaker self-clears
    // on the next launch. Read by the Command-tab breaker card.
    private val _greetSkippedAfterSpeakDeath = MutableStateFlow(false)
    val greetSkippedAfterSpeakDeath: StateFlow<Boolean> = _greetSkippedAfterSpeakDeath.asStateFlow()

    fun setGreetSkippedAfterSpeakDeath(value: Boolean) {
        _greetSkippedAfterSpeakDeath.value = value
    }

    fun setSttPath(path: String, reason: String) {
        _sttPath.value = path
        _sttPathReason.value = reason
    }

    fun setLastTranscriptScript(script: String) {
        _lastTranscriptScript.value = script
    }

    /** Strip anything that could carry a secret: ?key= / &key= query
     *  params and full URLs with query strings are reduced to the
     *  path, so a request URL can never leak a key into a report.
     *  1.4.3: also strips "Authorization: Token ..." header values
     *  (Deepgram) and any token-like value after the word Token. */
    fun sanitizeDiagnosticMessage(raw: String): String {
        var out = raw
        out = out.replace(Regex("[?&]key=[^&\\s]*"), "")
        out = out.replace(Regex("https?://[^\\s]*\\?[^\\s]*"), "[url-with-query-stripped]")
        out = out.replace(Regex("(?i)Authorization:\\s*Token\\s+[^\\s]+"), "Authorization: Token [redacted]")
        out = out.replace(Regex("(?i)Token\\s+[A-Za-z0-9._\\-]{16,}"), "Token [redacted]")
        return out.take(500)
    }

    fun addDiagnostic(area: String, code: String, message: String) {
        val clean = sanitizeDiagnosticMessage(message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = DiagnosticEntry(time, area, code, clean)
        _diagnostics.value = (_diagnostics.value + entry).takeLast(150)
        runCatching { JarvisDiagnosticsFile.append(entry) }
        if (area == "llm") _lastLlmError.value = "$code: $clean"
    }

    fun clearDiagnostics() {
        _diagnostics.value = emptyList()
        _lastLlmError.value = null
        runCatching { JarvisDiagnosticsFile.clear() }
    }

    fun setMicFatal(state: MicFatalState?) {
        _micFatal.value = state
    }

    fun setMicLoopEnabled(enabled: Boolean) {
        _micLoopEnabled.value = enabled
    }

    fun setMicListening(listening: Boolean) {
        _micListening.value = listening
    }

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

    // Restore a persisted conversation on app relaunch, but only when the
    // in-memory history is still just the greeting — never overwrite a
    // live conversation with a stale one.
    fun restoreChatHistory(entries: List<ChatEntry>) {
        if (entries.isEmpty()) return
        if (_chatHistory.value.size <= 1) {
            _chatHistory.value = entries.takeLast(30)
        }
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
