package com.jarvis.autonomous.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.jarvis.autonomous.R
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.data.WhiteboardStore
import com.jarvis.autonomous.ui.MainActivity
import com.jarvis.autonomous.ui.WhiteboardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

// Floating whiteboard over other apps. WindowManager/type pattern is
// reused from ui/overlay/FloatingJarvisOverlay.kt — no second overlay
// mechanism is invented here: TYPE_APPLICATION_OVERLAY on API 26+,
// TYPE_PHONE below it, FLAG_NOT_FOCUSABLE so touches still reach the
// drawing view without stealing the keyboard from the app underneath.
//
// PERMISSION GATE (never bypassed): showWindow() does nothing unless
// Settings.canDrawOverlays() is true. When denied, the engine's messages
// still land in WhiteboardStore (companion postMessage writes the store
// first), and the user sees them in the in-app WhiteboardScreen fallback.
//
// HONEST LIMIT: this window shows Jarvis messages and the user's drawing
// to the USER. It does not give Jarvis pixel vision — see WhiteboardStore
// (text context only) and INTEGRATION_NOTES_Whiteboard.md. Whiteboard
// images are NEVER uploaded to the global learning server.
class JarvisWhiteboardService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_whiteboard_channel"
        const val NOTIFICATION_ID = 1002

        const val ACTION_SHOW = "com.jarvis.autonomous.ACTION_WHITEBOARD_SHOW"
        const val ACTION_HIDE = "com.jarvis.autonomous.ACTION_WHITEBOARD_HIDE"
        const val ACTION_TOGGLE = "com.jarvis.autonomous.ACTION_WHITEBOARD_TOGGLE"
        const val ACTION_POST_MESSAGE = "com.jarvis.autonomous.ACTION_WHITEBOARD_POST_MESSAGE"
        const val EXTRA_MESSAGE = "extra_whiteboard_message"

        fun show(context: Context) {
            startWithAction(context, ACTION_SHOW)
        }

        fun hide(context: Context) {
            startWithAction(context, ACTION_HIDE)
        }

        fun toggle(context: Context) {
            startWithAction(context, ACTION_TOGGLE)
        }

        // Callable from anywhere (engine, notification handler, UI).
        // Writes the message to the shared store FIRST, so it is visible
        // in the in-app screen even when the overlay cannot be shown,
        // then asks the service to show/refresh the floating window.
        fun postMessage(context: Context, text: String) {
            if (text.isBlank()) return
            WhiteboardStore.init(context.applicationContext)
            WhiteboardStore.postMessage(text)
            val intent = Intent(context, JarvisWhiteboardService::class.java).apply {
                action = ACTION_POST_MESSAGE
                putExtra(EXTRA_MESSAGE, text)
            }
            startIntent(context, intent)
        }

        private fun startWithAction(context: Context, action: String) {
            val intent = Intent(context, JarvisWhiteboardService::class.java).apply {
                this.action = action
            }
            startIntent(context, intent)
        }

        private fun startIntent(context: Context, intent: Intent) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var collapsedView: LinearLayout? = null
    private var expandedView: LinearLayout? = null
    private var drawingView: WhiteboardView? = null
    private var messagesTextView: TextView? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var serviceScope: CoroutineScope? = null
    // B-C2: guards the denied-permission notice so the store message
    // below cannot re-trigger itself in a loop (postMessage restarts
    // this service, which would otherwise re-post on every start).
    private var deniedNoticePosted: Boolean = false
    private val deniedNotificationId = 1003

    override fun onCreate() {
        super.onCreate()
        WhiteboardStore.init(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main).apply {
            launch {
                WhiteboardStore.state.collect { state ->
                    drawingView?.setStrokes(state.strokes)
                    updateMessagesText(state.messages)
                }
            }
        }
        JarvisStateRepository.addLog("Jarvis Whiteboard service started.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showWindow()
            ACTION_HIDE -> {
                hideWindow()
                stopSelf()
            }
            ACTION_TOGGLE -> {
                if (collapsedView != null || expandedView != null) {
                    hideWindow()
                    stopSelf()
                } else {
                    showWindow()
                }
            }
            ACTION_POST_MESSAGE -> {
                // Companion already wrote the store; a direct intent still
                // posts its extra so no caller can lose a message.
                val text = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
                if (text.isNotBlank() &&
                    WhiteboardStore.state.value.messages.lastOrNull()?.text != text
                ) {
                    WhiteboardStore.postMessage(text)
                }
                showWindow()
            }
        }
        return START_STICKY
    }

    private fun canDrawOverlays(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)
    }

    private fun layoutType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun showWindow() {
        if (collapsedView != null || expandedView != null) return
        if (!canDrawOverlays()) {
            // B-C2: NEVER log-only. The denied path must reach the
            // user visibly: (1) a message in the shared store, so it
            // shows in the in-app Whiteboard tab's message list, and
            // (2) a notification, so it is seen even when the app is
            // not open. The never-upload rule is untouched — both
            // channels are on-device only. The guard above prevents
            // the store post from looping back through postMessage.
            JarvisStateRepository.addLog(
                "Whiteboard overlay not shown: overlay permission denied."
            )
            if (!deniedNoticePosted) {
                deniedNoticePosted = true
                WhiteboardStore.postMessage(
                    "Floating whiteboard nahi khul paya — overlay permission nahi mili. " +
                        "In-app Whiteboard tab me draw karo, ya overlay permission do."
                )
                notifyDenied()
            }
            return
        }
        deniedNoticePosted = false
        showCollapsed()
    }

    /** Visible denied notice via the service's own notification
     *  channel, opening the app on tap. Best effort — a missing
     *  notification permission must not crash the service. */
    private fun notifyDenied() {
        runCatching {
            createNotificationChannel()
            val openAppIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_jarvis_core)
                .setContentTitle("JARVIS Whiteboard nahi khula")
                .setContentText(
                    "Overlay permission nahi mili — in-app Whiteboard tab me draw karo."
                )
                .setContentIntent(openAppIntent)
                .setAutoCancel(true)
                .build()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(deniedNotificationId, notification)
        }
    }

    private fun showCollapsed(): Boolean {
        val manager = windowManager ?: return false
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(200)
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 64f
                setColor(Color.parseColor("#E6FFF8ED"))
                setStroke(3, Color.parseColor("#B85C38"))
            }
        }
        val label = TextView(this).apply {
            text = "✏️ Whiteboard"
            setTextColor(Color.parseColor("#3B2F2A"))
            textSize = 14f
            setPadding(dp(4), 0, dp(4), 0)
        }
        pill.addView(label)

        // Drag vars are declared BEFORE the touch listener that uses them.
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        pill.setOnTouchListener { _, event ->
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
                    runCatching { manager.updateViewLayout(pill, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    if (dx < 15 && dy < 15) {
                        showExpanded()
                    }
                    true
                }
                else -> false
            }
        }
        return runCatching {
            manager.addView(pill, params)
            collapsedView = pill
            currentParams = params
        }.isSuccess
    }

    private fun showExpanded() {
        val manager = windowManager ?: return
        val oldParams = currentParams
        // NOTE: the collapsed pill is NOT removed yet (see below).

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = oldParams?.y ?: dp(160)
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(Color.parseColor("#FFFFF8ED"))
                setStroke(3, Color.parseColor("#B85C38"))
            }
        }

        // Header: title is the drag handle; buttons are separate views
        // with their own click listeners, so dragging never eats a tap.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Jarvis Whiteboard"
            setTextColor(Color.parseColor("#3B2F2A"))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val collapseButton = Button(this).apply {
            text = "—"
            setOnClickListener { showCollapsedFromExpanded() }
        }
        val closeButton = Button(this).apply {
            text = "✕"
            setOnClickListener {
                hideWindow()
                stopSelf()
            }
        }
        header.addView(title)
        header.addView(collapseButton)
        header.addView(closeButton)

        // Header drag vars declared BEFORE the listener that uses them.
        var headerInitialX = 0
        var headerInitialY = 0
        var headerInitialTouchX = 0f
        var headerInitialTouchY = 0f
        title.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    headerInitialX = params.x
                    headerInitialY = params.y
                    headerInitialTouchX = event.rawX
                    headerInitialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = headerInitialX + (event.rawX - headerInitialTouchX).toInt()
                    params.y = headerInitialY + (event.rawY - headerInitialTouchY).toInt()
                    runCatching { manager.updateViewLayout(panel, params) }
                    true
                }
                else -> false
            }
        }

        val drawing = WhiteboardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(320)
            )
            onStrokeFinished = { stroke -> WhiteboardStore.addStroke(stroke) }
            setStrokes(WhiteboardStore.state.value.strokes)
        }

        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        for (color in WhiteboardView.COLORS) {
            val swatch = Button(this).apply {
                text = " "
                setBackgroundColor(color)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                setOnClickListener { drawing.setCurrentColor(color) }
            }
            colorRow.addView(swatch)
        }
        val clearButton = Button(this).apply {
            text = "Clear"
            setOnClickListener { WhiteboardStore.clearStrokes() }
        }
        colorRow.addView(clearButton)

        val messagesLabel = TextView(this).apply {
            text = "Jarvis messages:"
            setTextColor(Color.parseColor("#3B2F2A"))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(2))
        }
        val messagesView = TextView(this).apply {
            setTextColor(Color.parseColor("#3B2F2A"))
            textSize = 13f
        }
        val messagesScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(140)
            )
            addView(messagesView)
        }

        panel.addView(header)
        panel.addView(drawing)
        panel.addView(colorRow)
        panel.addView(messagesLabel)
        panel.addView(messagesScroll)

        // B-L8: add the panel FIRST, remove the pill only on success.
        // The old order removed the pill first, so a failed addView
        // left the user with neither pill nor panel.
        val added = runCatching {
            manager.addView(panel, params)
        }.isSuccess
        if (!added) {
            JarvisStateRepository.addLog(
                "Whiteboard expanded panel could not be added — pill kept."
            )
            return
        }
        collapsedView?.let { view ->
            runCatching { manager.removeView(view) }
        }
        collapsedView = null
        expandedView = panel
        drawingView = drawing
        messagesTextView = messagesView
        currentParams = params
        // messagesTextView was null at the earlier updateMessagesText call
        // above, so refresh now that it is assigned.
        updateMessagesText(WhiteboardStore.state.value.messages)
    }

    private fun showCollapsedFromExpanded() {
        val manager = windowManager ?: return
        // Same add-first rule as showExpanded: the new pill must land
        // before the expanded panel is removed, or a failed add leaves
        // the user with no whiteboard at all.
        val expanded = expandedView
        if (showCollapsed()) {
            expanded?.let { view ->
                runCatching { manager.removeView(view) }
            }
            expandedView = null
            drawingView = null
            messagesTextView = null
        }
    }

    private fun hideWindow() {
        val manager = windowManager ?: return
        collapsedView?.let { view ->
            runCatching { manager.removeView(view) }
        }
        expandedView?.let { view ->
            runCatching { manager.removeView(view) }
        }
        collapsedView = null
        expandedView = null
        drawingView = null
        messagesTextView = null
        currentParams = null
    }

    private fun updateMessagesText(
        messages: List<com.jarvis.autonomous.data.WhiteboardMessage>
    ) {
        val view = messagesTextView ?: return
        if (messages.isEmpty()) {
            view.text = "(No Jarvis messages yet.)"
            return
        }
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        view.text = messages.takeLast(10).joinToString("\n") { message ->
            "${format.format(Date(message.timestampMillis))} — ${message.text}"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Whiteboard",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the floating whiteboard visible over other apps"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hideIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, JarvisWhiteboardService::class.java).apply {
                action = ACTION_HIDE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis_core)
            .setContentTitle("JARVIS Whiteboard")
            .setContentText("Floating whiteboard is open — tap to return to Jarvis.")
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Close whiteboard", hideIntent)
            .build()
    }

    override fun onDestroy() {
        hideWindow()
        // B-M11: flush synchronously BEFORE cancelling the writer
        // scope, or the latest strokes/messages could die with the
        // service's async save still in flight.
        runCatching { WhiteboardStore.flushSync() }
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
