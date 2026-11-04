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

/**
 * 1.4.13 WAVE-2 (§D-8): TRUE playback state, distinct from
 * [AgentRunState.SPEAKING] (which the engine sets at speak
 * REQUEST time, i.e. before any audio exists). PREPARING =
 * speak requested/acquired but no audio yet (the
 * SPK_START→first-audio gap); SPEAKING = audio is really
 * playing (first-audio/streaming until marker/done/cancel);
 * IDLE = no speak in flight. The indicator must show
 * "speaking" ONLY in SPEAKING; PREPARING is its own visible
 * state, never hidden, never merged into SPEAKING. Set by
 * speakWrapped (PREPARING at SPK_START/SPK_ACQUIRED, IDLE in
 * its finally), by Swara at SWARA_FIRST_AUDIO/SWARA_STREAMING,
 * and by the system-TTS fallback at utterance onStart. A
 * speak that fails before first audio goes PREPARING→IDLE
 * (never SPEAKING) via speakWrapped's finally.
 */
enum class PlaybackState {
    IDLE,
    PREPARING,
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

/** 1.4.2: a mic failure the user must SEE (banner + spoken
 *  reason + Retry), not a log line. `code` is -1 under the
 *  Deepgram-only policy (no system recognizer codes any more);
 *  the banner hides the code line when code < 0. */
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

    // 1.4.3: live STT path + switch reason + last Deepgram
    // transcript script, written by the mic manager (and
    // mirrored by the service's onSttPathChanged), read by the
    // Diagnostics report. Values only — never a key.
    // 1.4.5 part 3 (FINAL mic policy 2026-11-03): Deepgram ONLY
    // — the path is always "deepgram" once started; "not
    // started" before the first user-enabled start.
    private val _sttPath = MutableStateFlow("not started")
    val sttPath: StateFlow<String> = _sttPath.asStateFlow()

    private val _sttPathReason = MutableStateFlow("not started")
    val sttPathReason: StateFlow<String> = _sttPathReason.asStateFlow()

    // 1.4.5 part 3: mic auto-reconnect state (FINAL policy:
    // transient Deepgram failures NEVER silently kill the loop).
    // Non-null reason = the loop is user-enabled but currently
    // reconnecting (amber banner, Retry + Mic off offered);
    // null = connected, or loop off. Set by the service's
    // onMicReconnecting / onMicRecovered.
    private val _micReconnecting = MutableStateFlow<String?>(null)
    val micReconnecting: StateFlow<String?> = _micReconnecting.asStateFlow()

    fun setMicReconnecting(reason: String?) {
        _micReconnecting.value = reason
    }

    // 1.4.5 part 3: Swara ON/OFF as Compose state. prefs
    // .swaraEnabled is a plain SharedPreferences read — it does
    // NOT trigger recomposition, so the persistent Swara-OFF
    // header card stayed stale after an AI Brain Save (the Save
    // only recomposed the settings screen, never the parent).
    // This flow is the single source the card reads; it is set
    // from prefs at MainActivity launch and on every Save that
    // changes the toggle.
    private val _swaraEnabled = MutableStateFlow(true)
    val swaraEnabled: StateFlow<Boolean> = _swaraEnabled.asStateFlow()

    /**
     * 1.4.12: Swara latch state for the AI Brain card. True
     * while the bounded-recovery latch is tripped (3
     * consecutive failed speaks) — the card shows the fallback
     * banner until Test Voice / reset / toggle-on / cooldown
     * reopens it. Set ONLY by SwaraTtsClient.
     */
    private val _swaraLatched = MutableStateFlow(false)
    val swaraLatched: StateFlow<Boolean> = _swaraLatched.asStateFlow()

    /**
     * 1.4.11: Test Voice live stage display (AI Brain Swara
     * card, Test Voice button ke neeche). StateFlow from
     * TestVoiceStageTracker — null until the first test tap,
     * then the live stage ("…") during a test and the frozen
     * terminal result (stage trail + stuck point + kaaran)
     * until the next test. Derived from existing tts markers
     * only; Diagnostics tab content is untouched.
     */
    val testVoiceStage: StateFlow<TestVoiceStageDisplay?> =
        TestVoiceStageTracker.stage

    /**
     * 1.4.12: NO :tts stage relay any more. Swara runs in the
     * main process, so its markers flow through addDiagnostic
     * directly (TestVoiceStageTracker.onDiagnostic) — the old
     * ttsStageListener hook (set only by the deleted :tts
     * service during a speak) is deleted with it. Old
     * diagnostics history is tolerated: loadPersistedDiagnostics
     * seeds the mirror directly and never routes through
     * TestVoiceStageTracker, so stale markers in history can
     * never be misread as a Swara stage.
     */


    fun setSwaraLatched(value: Boolean) {
        _swaraLatched.value = value
    }

    fun setSwaraEnabled(value: Boolean) {
        _swaraEnabled.value = value
    }

    // 1.4.5 part 3: bounce detection as observable state
    // (research: atomic detection + durable evidence). The old
    // design kept the result in LaunchSession @Volatile vars
    // that Compose never observed, so a card composed before
    // the background detection finished stayed empty forever.
    // The single claiming detector thread sets these; all cards
    // collect them and recompose when detection lands.
    private val _bounceReport = MutableStateFlow<PreviousBounce?>(null)
    val bounceReport: StateFlow<PreviousBounce?> = _bounceReport.asStateFlow()

    private val _bounceChecked = MutableStateFlow(false)
    val bounceChecked: StateFlow<Boolean> = _bounceChecked.asStateFlow()

    private val _bounceFilePath = MutableStateFlow("")
    val bounceFilePath: StateFlow<String> = _bounceFilePath.asStateFlow()

    fun setBounceReport(report: PreviousBounce?, filePath: String) {
        _bounceReport.value = report
        _bounceFilePath.value = filePath
        _bounceChecked.value = true
    }

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
     *  (Deepgram) and any token-like value after the word Token.
     *  1.4.10 (Step 6): SINGLE implementation — JarvisDiagnosticsFile
     *  delegates here (its old second copy drifted and missed these).
     *  Added coverage: Bearer values, licence-shaped JARVIS-XXXX-…
     *  keys, field-name/value pairs (apiKey=/password:/secret=…),
     *  and /token/… / /key/… / /auth/… path segments. Honest limit:
     *  opaque path segments that are not recognisably tokens are
     *  NOT redacted (indistinguishable from IDs). */
    fun sanitizeDiagnosticMessage(raw: String): String {
        var out = raw
        out = out.replace(Regex("[?&]key=[^&\\s]*"), "")
        out = out.replace(Regex("https?://[^\\s]*\\?[^\\s]*"), "[url-with-query-stripped]")
        out = out.replace(Regex("(?i)Authorization:\\s*Token\\s+[^\\s]+"), "Authorization: Token [redacted]")
        out = out.replace(Regex("(?i)Token\\s+[A-Za-z0-9._\\-]{16,}"), "Token [redacted]")
        out = out.replace(Regex("(?i)Authorization:\\s*Bearer\\s+[^\\s]+"), "Authorization: Bearer [redacted]")
        out = out.replace(Regex("JARVIS-[A-Z0-9]{4}(?:-[A-Z0-9]{4}){3}"), "[licence-redacted]")
        out = out.replace(Regex("(?i)(api[_-]?key|deepgram[_-]?key|password|passwd|secret|auth[_-]?token)\\s*[:=]\\s*[^\\s,;]+"), "$1=[redacted]")
        out = out.replace(Regex("(?i)(/(?:token|key|auth)/)[^/\\s]+"), "$1[redacted]")
        return out.take(500)
    }

    fun addDiagnostic(area: String, code: String, message: String) {
        val clean = sanitizeDiagnosticMessage(message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = DiagnosticEntry(time, area, code, clean)
        _diagnostics.value = (_diagnostics.value + entry).takeLast(150)
        // 1.4.11: Test Voice stage display derives from the
        // existing tts marker stream (no new speak path, no
        // polling). runCatching: display tracking must never
        // break diagnostics. 1.4.12: Swara markers arrive here
        // directly (main process — no relay any more).
        if (area == "tts") {
            runCatching { TestVoiceStageTracker.onDiagnostic(area, code, clean) }
        }
        runCatching { JarvisDiagnosticsFile.append(entry) }
        if (area == "llm") _lastLlmError.value = "$code: $clean"
    }

    /**
     * Sync variant: same mirror update, but the file write is
     * fsynced (see JarvisDiagnosticsFile.appendSync). Used by
     * speak-path stage markers so the marker naming a killing
     * stage survives even a device crash.
     */
    fun addDiagnosticSync(area: String, code: String, message: String) {
        val clean = sanitizeDiagnosticMessage(message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = DiagnosticEntry(time, area, code, clean)
        _diagnostics.value = (_diagnostics.value + entry).takeLast(150)
        // 1.4.11: same Test Voice stage hook as addDiagnostic
        // (1.4.12: Swara markers arrive directly — no relay).
        if (area == "tts") {
            runCatching { TestVoiceStageTracker.onDiagnostic(area, code, clean) }
        }
        runCatching { JarvisDiagnosticsFile.appendSync(entry) }
        if (area == "llm") _lastLlmError.value = "$code: $clean"
    }

    /**
     * 1.4.5 part 3 — durable evidence for a fresh launch
     * (research: in-memory mirror khaali dikhta hai). On a fresh
     * process the mirror starts empty, so the Diagnostics
     * section showed nothing until NEW entries arrived — a
     * post-bounce launch looked evidence-free. This seeds the
     * mirror from the persisted file (last 150 lines, parsed
     * back into entries). Called once from MainActivity launch
     * on a background thread; never re-appends to the file.
     */
    fun loadPersistedDiagnostics() {
        runCatching {
            val lines = JarvisDiagnosticsFile.readLast(150)
            if (lines.isEmpty()) return
            val parsed = lines.mapNotNull { parseDiagnosticLine(it) }
            if (parsed.isNotEmpty()) {
                _diagnostics.value = parsed.takeLast(150)
            }
        }
    }

    private fun parseDiagnosticLine(line: String): DiagnosticEntry? {
        fun field(name: String): String? {
            val key = "\"$name\":\""
            val start = line.indexOf(key)
            if (start < 0) return null
            var i = start + key.length
            val sb = StringBuilder()
            while (i < line.length) {
                val c = line[i]
                if (c == '\\' && i + 1 < line.length) {
                    val n = line[i + 1]
                    when (n) {
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        'n' -> sb.append(' ')
                        'r' -> sb.append(' ')
                        else -> sb.append(n)
                    }
                    i += 2
                } else if (c == '"') {
                    return sb.toString()
                } else {
                    sb.append(c)
                    i++
                }
            }
            return null
        }
        val ts = field("ts") ?: return null
        val area = field("area") ?: return null
        val code = field("code") ?: return null
        val msg = field("msg") ?: return null
        // File ts is yyyy-MM-dd HH:mm:ss; mirror shows HH:mm:ss.
        val time = ts.takeLast(8).takeIf { it.length == 8 } ?: ts
        return DiagnosticEntry(time, area, code, msg)
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

    // 1.4.8 (G8 F2): LIVE wake-session state (not the preference).
    // The bug report used to print mode=wake whenever the
    // wake-word preference was ON, even if the session never
    // started or died (no key, no permission, backoff) — a
    // report could claim "wake" while nothing listened. This
    // flow is true only while the wake pump is actually
    // running (set at pump start, cleared on stop/restart
    // backoff), so the report's mode reflects reality.
    private val _wakeListening = MutableStateFlow(false)
    val wakeListening: StateFlow<Boolean> = _wakeListening.asStateFlow()

    fun setWakeListening(listening: Boolean) {
        _wakeListening.value = listening
    }

    private val _recentNotifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val recentNotifications: StateFlow<List<NotificationItem>> = _recentNotifications.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    /** WAVE-2 (§D-8): publishes the true playback state (see [PlaybackState]). */
    fun setPlaybackState(state: PlaybackState) {
        _playbackState.value = state
    }

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
