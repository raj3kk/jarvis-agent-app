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
                setColor(Color.parseColor("#E6071120"))
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
                combine(
                    JarvisStateRepository.agentState,
                    JarvisStateRepository.currentStepInfo
                ) { state, step -> state to step }.collect { (state, step) ->
                    updateHudAppearance(state, step)
                }
            }
        }
    }

    /** B-L13 failure channel: state log + notification, best effort
     *  (a missing notification permission must not throw here). */
    private fun reportShowFailure(reason: String) {
        JarvisStateRepository.addLog("Jarvis overlay nahi khul paya: $reason")
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

    private fun updateHudAppearance(state: AgentRunState, step: String) {
        val container = rootView ?: return
        val bg = container.background as? GradientDrawable

        val (icon, borderColor, showStop) = when (state) {
            AgentRunState.IDLE -> Triple("🎙️", "#00E5FF", false)
            AgentRunState.LISTENING -> Triple("🟢", "#00E676", false)
            AgentRunState.THINKING -> Triple("🧠", "#D500F9", true)
            AgentRunState.EXECUTING_STEP -> Triple("⚡", "#FFAB00", true)
            AgentRunState.WAITING_USER_REPLY -> Triple("❓", "#00E676", true)
            AgentRunState.SPEAKING -> Triple("🔊", "#00B0FF", true)
        }

        orbButton?.text = icon
        statusTextView?.text = step.ifBlank { "JARVIS Ready" }
        stopButton?.visibility = if (showStop) View.VISIBLE else View.GONE
        bg?.setStroke(3, Color.parseColor(borderColor))
    }

    fun hide() {
        overlayScope?.cancel()
        overlayScope = null
        rootView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootView = null
    }
}
