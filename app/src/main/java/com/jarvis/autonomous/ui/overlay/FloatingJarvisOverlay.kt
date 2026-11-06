package com.jarvis.autonomous.ui.overlay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.jarvis.autonomous.R
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.data.JarvisStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingJarvisOverlay(
    private val context: Context,
    private val onMicTap: () -> Unit,
    private val onStopTap: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: LinearLayout? = null
    private var statusTextView: TextView? = null
    private var orbButton: TextView? = null
    private var stopButton: TextView? = null
    private var overlayScope: CoroutineScope? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (rootView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            // B-L13: a denied overlay used to fail silently — the user
            // just saw no pill and no reason. Surface it on both
            // channels the app already has: the state log (visible in
            // Memory & Logs) and a notification.
            reportShowFailure("Overlay permission nahi mili.")
            return
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 180
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 14, 24, 14)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 64f
                // 1.4.8: slightly transparent (was 0xE6 = 90%
                // opaque, now 0xB3 = 70%) — the pill sits lighter
                // on screen, but text stays near-opaque so it
                // remains readable and the pill stays tappable
                // (never faded to invisible).
                setColor(Color.parseColor("#B3071120"))
                setStroke(3, Color.parseColor("#00E5FF"))
            }
        }

        val orb = TextView(context).apply {
            text = "🎙️"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(16, 6, 16, 6)
        }

        val statusLabel = TextView(context).apply {
            text = "JARVIS Ready"
            setTextColor(Color.parseColor("#E0F7FA"))
            textSize = 12f
            maxWidth = 440
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(8, 0, 12, 0)
        }

        val stopBtn = TextView(context).apply {
            text = "⏹"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 16f
            setPadding(12, 4, 8, 4)
            visibility = View.GONE
            setOnClickListener { onStopTap() }
        }

        container.addView(orb)
        container.addView(statusLabel)
        container.addView(stopBtn)

        // Support dragging and tapping the floating pill
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    runCatching { windowManager.updateViewLayout(container, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    if (dx < 15 && dy < 15) {
                        onMicTap()
                    }
                    true
                }

                else -> false
            }
        }

        runCatching {
            windowManager.addView(container, params)
            rootView = container
            orbButton = orb
            statusTextView = statusLabel
            stopButton = stopBtn
        }.onFailure {
            // B-L13: a failed addView must not leave a live status
            // scope collecting into a null view forever, and must not
            // fail silently either. Report, then return before the
            // scope below is launched.
            reportShowFailure("Overlay window add nahi ho paya: ${it.message ?: "unknown error"}")
            return
        }

        overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main).apply {
            launch {
                // 1.4.8: the pill now also follows the LIVE mic
                // loop state (toggle + status need it) — agent
                // state alone cannot tell "mic OFF" from "idle".
                // WAVE-2 (§D-8) FIX for the 6-flow combine:
                // combine(agentState, …, playbackState) with six
                // typed flows does NOT resolve on this toolchain
                // (CI compileDebugKotlin: the call collapsed to
                // the vararg Array overload and inference failed
                // for every lambda parameter). So the five
                // original flows combine as before, and the
                // playback flow joins via the 2-flow
                // Flow.combine extension, which resolves
                // cleanly. Same collected values, same HUD call.
                combine(
                    JarvisStateRepository.agentState,
                    JarvisStateRepository.currentStepInfo,
                    JarvisStateRepository.micLoopEnabled,
                    JarvisStateRepository.micListening,
                    JarvisStateRepository.micReconnecting
                ) { state, step, loopOn, listening, reconnecting ->
                    Quint(state, step, loopOn, listening, reconnecting)
                }.combine(JarvisStateRepository.playbackState) { quint, playback ->
                    quint.copy(playback = playback)
                }.collect { (state, step, loopOn, listening, reconnecting, playback) ->
                    updateHudAppearance(state, step, loopOn, listening, reconnecting, playback)
                }
            }
        }
    }

    private data class Quint(
        val state: AgentRunState,
        val step: String,
        val loopOn: Boolean,
        val listening: Boolean,
        val reconnecting: String?,
        val playback: com.jarvis.autonomous.data.PlaybackState =
            com.jarvis.autonomous.data.PlaybackState.IDLE
    )

    /** B-L13 failure channel: state log + notification, best effort
     *  (a missing notification permission must not throw here). */
    private fun reportShowFailure(reason: String) {
        JarvisStateRepository.addLog("Jarvis overlay nahi khul paya: $reason")
        // 1.4.2: the failure also lands in the diagnostics store
        // (area overlay) so the bug report can name it.
        JarvisStateRepository.addDiagnostic("overlay", "SHOW_FAILED", reason)
        runCatching {
            val channelId = "jarvis_overlay_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "JARVIS Overlay",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Reports when the floating Jarvis overlay cannot be shown"
                }
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
                val notification = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_jarvis_core)
                    .setContentTitle("JARVIS overlay nahi khula")
                    .setContentText(reason)
                    .setAutoCancel(true)
                    .build()
                nm.notify(1004, notification)
            }
        }
    }

    /**
     * 1.4.8: the pill shows ONLY true states (user order
     * 22:15 IST: mic toggle + transparency + real status).
     * Non-IDLE agent states keep their existing mapping.
     * IDLE used to always read "JARVIS Ready", hiding whether
     * the mic was even on — now IDLE resolves through the
     * live mic flows: loop ON + listening = "Sun raha hoon",
     * loop ON + reconnecting = "Dobara jud raha hoon…",
     * loop ON + neither = "Mic ON — jud raha hai…"
     * (connecting), loop OFF = "Mic OFF — tap karo" (this
     * label IS the tap-to-start discoverability: the pill
     * itself tells the user the mic is off and tappable).
     * No state is invented: every branch reads a live flow.
     */
    private fun updateHudAppearance(
        state: AgentRunState,
        step: String,
        loopOn: Boolean,
        listening: Boolean,
        reconnecting: String?,
        playback: com.jarvis.autonomous.data.PlaybackState =
            com.jarvis.autonomous.data.PlaybackState.IDLE
    ) {
        val container = rootView ?: return
        val bg = container.background as? GradientDrawable

        // WAVE-2 (§D-8): true playback wins over agentState
        // for speaking — agentState SPEAKING is set at speak
        // REQUEST time, so without this the pill shows
        // "Bol raha hoon" through the whole connect gap.
        // PREPARING is its own label, never hidden.
        val (icon, label, borderColor, showStop) = when {
            playback == com.jarvis.autonomous.data.PlaybackState.SPEAKING ->
                Quad("🔊", step.ifBlank { "Bol raha hoon" }, "#00B0FF", true)
            playback == com.jarvis.autonomous.data.PlaybackState.PREPARING ->
                Quad("⏳", "Bolne ki tayyari…", "#78909C", true)
            // 1.4.24-PRO step 3 (RC-D): task state stays truthful
            // during a task — THINKING / EXECUTING / SPEAKING
            // outrank the reconnecting pill. But a stale LISTENING
            // must NOT outrank it, so the reconnecting branch sits
            // above LISTENING (and above the live-listening green
            // branch): during a reconnect the pill shows yellow
            // "Dobara jud raha hoon…" even if agent state is
            // stale-LISTENING; when listening is truly live it
            // still shows green via `loopOn && listening`.
            state == AgentRunState.THINKING -> Quad("🧠", step.ifBlank { "Soch raha hoon" }, "#D500F9", true)
            state == AgentRunState.EXECUTING_STEP -> Quad("⚡", step.ifBlank { "Kaam kar raha hoon" }, "#FFAB00", true)
            state == AgentRunState.SPEAKING -> Quad("🔊", step.ifBlank { "Bol raha hoon" }, "#00B0FF", true)
            loopOn && reconnecting != null -> Quad("🟡", "Dobara jud raha hoon…", "#FFAB00", false)
            state == AgentRunState.LISTENING -> Quad("🟢", step.ifBlank { "Sun raha hoon" }, "#00E676", false)
            state == AgentRunState.WAITING_USER_REPLY -> Quad("❓", step.ifBlank { "Jawab ka intezar" }, "#00E676", true)
            loopOn && listening -> Quad("🟢", "Sun raha hoon", "#00E676", false)
            loopOn -> Quad("🎙️", "Mic ON — jud raha hai…", "#00E5FF", false)
            else -> Quad("🎙️", "Mic OFF — tap karo", "#00E5FF", false)
        }

        orbButton?.text = icon
        statusTextView?.text = label
        stopButton?.visibility = if (showStop) View.VISIBLE else View.GONE
        bg?.setStroke(3, Color.parseColor(borderColor))
    }

    private data class Quad(
        val icon: String,
        val label: String,
        val borderColor: String,
        val showStop: Boolean
    )

    fun hide() {
        overlayScope?.cancel()
        overlayScope = null
        rootView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootView = null
    }
}
