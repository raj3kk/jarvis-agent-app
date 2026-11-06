package com.jarvis.autonomous.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.util.Base64
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.autonomous.data.JarvisStateRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

data class ScreenElement(
    val id: Int,
    val text: String,
    val contentDesc: String,
    val viewId: String,
    val className: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isPassword: Boolean,
    val centerX: Int,
    val centerY: Int,
    val bounds: Rect
)

data class ScreenSnapshot(
    val packageName: String,
    val elements: List<ScreenElement>,
    val hasSensitivePaymentPrompt: Boolean,
    val formattedTree: String
)

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set
    }

    private val latestNodesMap = mutableMapOf<Int, AccessibilityNodeInfo>()
    private val latestElementsMap = mutableMapOf<Int, ScreenElement>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        JarvisStateRepository.setAccessibilityConnected(true)
        JarvisStateRepository.addLog("Accessibility Service Connected & Ready.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Real-time window changes are captured on demand during agent loop
    }

    override fun onInterrupt() {
        JarvisStateRepository.addLog("Accessibility Service Interrupted.")
    }

    override fun onDestroy() {
        instance = null
        JarvisStateRepository.setAccessibilityConnected(false)
        super.onDestroy()
    }

    /**
     * Captures the complete current screen hierarchy with indexed actionable nodes.
     */
    fun captureScreenSnapshot(): ScreenSnapshot {
        val root = rootInActiveWindow
        if (root == null) {
            return ScreenSnapshot(
                packageName = "unknown",
                elements = emptyList(),
                hasSensitivePaymentPrompt = false,
                formattedTree = "Screen is currently empty or locked."
            )
        }

        val pkg = root.packageName?.toString() ?: "unknown"
        latestNodesMap.clear()
        latestElementsMap.clear()

        val rawElements = mutableListOf<ScreenElement>()
        var nextId = 1

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return

            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) return

            val rawText = if (node.isPassword) {
                "[REDACTED_PASSWORD]"
            } else {
                node.text?.toString()?.trim()?.replace("\n", " ") ?: ""
            }
            val desc = node.contentDescription?.toString()?.trim()?.replace("\n", " ") ?: ""
            val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
            val cls = node.className?.toString()?.substringAfterLast(".") ?: "View"

            val clickable = node.isClickable || node.isLongClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable

            val isMeaningful = rawText.isNotEmpty() || desc.isNotEmpty() || clickable || editable || scrollable

            if (isMeaningful && nextId <= 120) {
                val id = nextId++
                val element = ScreenElement(
                    id = id,
                    text = rawText.take(80),
                    contentDesc = desc.take(80),
                    viewId = viewId,
                    className = cls,
                    isClickable = clickable,
                    isEditable = editable,
                    isScrollable = scrollable,
                    isPassword = node.isPassword,
                    centerX = rect.centerX(),
                    centerY = rect.centerY(),
                    bounds = rect
                )
                rawElements.add(element)
                latestNodesMap[id] = node
                latestElementsMap[id] = element
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)

        val sensitiveKeywords = listOf(
            "upi pin", "enter pin", "pay ₹", "send money", "confirm payment",
            "proceed to pay", "delete account", "uninstall"
        )
        val combinedScreenText = rawElements.joinToString(" ") { "${it.text} ${it.contentDesc}" }.lowercase()
        val hasSensitive = sensitiveKeywords.any { combinedScreenText.contains(it) }

        val sb = StringBuilder()
        sb.appendLine("Active App Package: $pkg")
        sb.appendLine("Screen Elements ([ID] Type | Text | Desc | Flags | Center(x,y)):")
        for (el in rawElements) {
            val flags = buildList {
                if (el.isClickable) add("CLICKABLE")
                if (el.isEditable) add("EDITABLE_INPUT")
                if (el.isScrollable) add("SCROLLABLE")
                if (el.isPassword) add("PASSWORD")
            }.joinToString(",")
            val labelPart = buildString {
                if (el.text.isNotEmpty()) append("text=\"${el.text}\" ")
                if (el.contentDesc.isNotEmpty()) append("desc=\"${el.contentDesc}\" ")
                if (el.viewId.isNotEmpty()) append("id=${el.viewId}")
            }.trim()
            sb.appendLine("[${el.id}] ${el.className} | $labelPart | [$flags] | (${el.centerX}, ${el.centerY})")
        }

        return ScreenSnapshot(
            packageName = pkg,
            elements = rawElements,
            hasSensitivePaymentPrompt = hasSensitive,
            formattedTree = sb.toString()
        )
    }

    /**
     * Taps a node by its [ID]. Falls back to coordinate gesture if node click returns false.
     */
    suspend fun tapNode(nodeId: Int): Boolean {
        val node = latestNodesMap[nodeId]
        val el = latestElementsMap[nodeId]
        if (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                JarvisStateRepository.addLog("Clicked node [$nodeId] (${el?.text ?: el?.contentDesc})")
                return true
            }
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 4) {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    JarvisStateRepository.addLog("Clicked parent of node [$nodeId]")
                    return true
                }
                parent = parent.parent
                depth++
            }
        }
        if (el != null) {
            return tapCoordinates(el.centerX, el.centerY)
        }
        return false
    }

    /**
     * Performs a physical tap gesture at (x, y).
     * A-H1: out-of-range coordinates are REJECTED, never coerced —
     * coercing a bad tap onto the screen edge taps the wrong thing.
     */
    suspend fun tapCoordinates(x: Int, y: Int): Boolean {
        val metrics = resources.displayMetrics
        if (x < 0 || y < 0 || x > metrics.widthPixels || y > metrics.heightPixels) {
            JarvisStateRepository.addLog("Tap rejected: ($x, $y) out of screen range")
            return false
        }
        return tapCoordinatesInRange(x, y)
    }

    private suspend fun tapCoordinatesInRange(x: Int, y: Int): Boolean = suspendCancellableCoroutine { cont ->
        val safeX = x.toFloat()
        val safeY = y.toFloat()
        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 90L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    JarvisStateRepository.addLog("Tapped coordinates ($x, $y)")
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null
        )
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    /**
     * Performs a long press at (x, y). Same A-H1 rule as taps:
     * out-of-range is rejected, never coerced.
     */
    suspend fun longPressCoordinates(x: Int, y: Int): Boolean {
        val metrics = resources.displayMetrics
        if (x < 0 || y < 0 || x > metrics.widthPixels || y > metrics.heightPixels) {
            JarvisStateRepository.addLog("Long-press rejected: ($x, $y) out of screen range")
            return false
        }
        return longPressCoordinatesInRange(x, y)
    }

    private suspend fun longPressCoordinatesInRange(x: Int, y: Int): Boolean = suspendCancellableCoroutine { cont ->
        val safeX = x.toFloat()
        val safeY = y.toFloat()
        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 850L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    JarvisStateRepository.addLog("Long-pressed coordinates ($x, $y)")
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null
        )
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    /**
     * Types text into a specific node [ID] or the first available editable field on screen.
     */
    suspend fun typeText(nodeId: Int?, text: String, pressEnter: Boolean = true): Boolean {
        if (text.isBlank()) {
            JarvisStateRepository.addLog("Type failed: blank text rejected.")
            return false
        }
        var targetNode: AccessibilityNodeInfo? = if (nodeId != null && nodeId > 0) {
            latestNodesMap[nodeId]
        } else {
            null
        }

        if (targetNode == null) {
            // Find focused input or first editable node
            targetNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: latestNodesMap.values.firstOrNull { it.isEditable }
        }

        if (targetNode == null) {
            JarvisStateRepository.addLog("Type failed: No editable input field found.")
            return false
        }

        // Tap/focus first
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (pressEnter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }

        JarvisStateRepository.addLog("Typed \"$text\" into input field (success=$success)")
        return success
    }

    /**
     * Scrolls the screen in the requested direction ("DOWN", "UP", "LEFT", "RIGHT").
     */
    suspend fun scrollScreen(direction: String): Boolean {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val cx = w / 2
        val cy = h / 2

        return when (direction.uppercase()) {
            "DOWN", "NEXT" -> swipeCoordinates(cx, (h * 0.78).toInt(), cx, (h * 0.22).toInt(), 360L)
            "UP", "PREVIOUS" -> swipeCoordinates(cx, (h * 0.25).toInt(), cx, (h * 0.78).toInt(), 360L)
            "LEFT" -> swipeCoordinates((w * 0.85).toInt(), cy, (w * 0.15).toInt(), cy, 320L)
            "RIGHT" -> swipeCoordinates((w * 0.15).toInt(), cy, (w * 0.85).toInt(), cy, 320L)
            else -> swipeCoordinates(cx, (h * 0.75).toInt(), cx, (h * 0.25).toInt(), 360L)
        }
    }

    suspend fun swipeCoordinates(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = 350L
    ): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    JarvisStateRepository.addLog("Swiped ($startX,$startY) -> ($endX,$endY)")
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null
        )
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    /**
     *  Global system navigation actions.
     * 1.4.3 home-redirect hardening: (a) the action is recorded
     * to PERSISTED Diagnostics BEFORE it fires, so a "speaking
     * dikhte hi home" repro is decidable (NAV_EXEC present =
     * the agent pressed HOME on purpose, NOT a crash); (b) an
     * unknown target FAILS CLOSED (returns false + diagnostic)
     * instead of pressing BACK — the old else->BACK turned any
     * garbage target into a backgrounding press; (c) pre-P
     * LOCK_SCREEN/SCREENSHOT fallbacks to HOME are removed for
     * the same reason (a substitute HOME press is never what
     * the caller asked for).
     * 1.4.4 launch-bounce guard: HOME/BACK are BLOCKED until a
     * user command arrived this session AND first UI composed
     * (NAV_BLOCKED_STARTUP, never NAV_EXEC) — see
     * LaunchBounceCatcher.kt for the full launch reading guide
     * (LAUNCH_START without LAUNCH_UI = died during startup;
     * LAUNCH_UI then nothing = process killed; NAV_EXEC HOME
     * before any USER_CMD = startup agent action).
     */
    fun performSystemNavigation(navAction: String): Boolean {
        val target = navAction.uppercase()
        // 1.4.4 "back na ho" startup guard: until THIS activity
        // instance has composed UI AND a user command arrived
        // after THIS launch, NO agent path may press HOME or BACK
        // — a launch bounce must never be caused (or worsened) by
        // the agent itself. The window resets on every activity
        // launch (LaunchSession.onActivityLaunch); a pure
        // background voice task with the app never opened is not
        // blocked, because no reset happens without a launch.
        // Blocked attempts write NAV_BLOCKED_STARTUP and return
        // false; they NEVER silently succeed and NEVER write
        // NAV_EXEC (NAV_EXEC means "actually pressed"). The
        // hardware Back button is NOT affected — this guard
        // covers only the agent / accessibility path. After the
        // window lifts, normal in-task navigation works as before.
        if ((target == "HOME" || target == "BACK") &&
            !(com.jarvis.autonomous.data.LaunchSession.userCommandIssued &&
                com.jarvis.autonomous.data.LaunchSession.uiStable)
        ) {
            JarvisStateRepository.addDiagnostic(
                "nav", "NAV_BLOCKED_STARTUP",
                "Blocked $target at startup: userCommandIssued=" +
                    "${com.jarvis.autonomous.data.LaunchSession.userCommandIssued} " +
                    "uiStable=${com.jarvis.autonomous.data.LaunchSession.uiStable} " +
                    "— no user command yet this session, nothing pressed."
            )
            return false
        }
        JarvisStateRepository.addDiagnostic(
            "nav", "NAV_EXEC", "System navigation requested: $target"
        )
        val code = when (target) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            "LOCK_SCREEN" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else -1
            "SCREENSHOT" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else -1
            else -> -1
        }
        if (code == -1) {
            JarvisStateRepository.addDiagnostic(
                "nav", "NAV_REJECTED",
                "Unknown or unsupported navigation target: $target — nothing pressed."
            )
            return false
        }
        val ok = performGlobalAction(code)
        JarvisStateRepository.addLog("Executed Global Action: $navAction (ok=$ok)")
        return ok
    }

    /**
     * Finds and launches any installed Android app by fuzzy name or package name.
     */
    fun launchApp(appNameOrPackage: String): Boolean {
        val query = appNameOrPackage.trim().lowercase()
        if (query.isEmpty()) return false

        val pm = packageManager

        // Common aliases for popular Indian / global apps
        val aliasMap = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "instagram" to "com.instagram.android",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "zomato" to "com.application.zomato",
            "swiggy" to "in.swiggy.android",
            "uber" to "com.ubercab",
            "ola" to "com.olacabs.customer",
            "phonepe" to "com.phonepe.app",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "paytm" to "net.one97.paytm",
            "settings" to "com.android.settings"
        )

        aliasMap[query]?.let { mappedPkg ->
            val directIntent = pm.getLaunchIntentForPackage(mappedPkg)
            if (directIntent != null) {
                directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(directIntent)
                JarvisStateRepository.addLog("Launched app via alias: $appNameOrPackage ($mappedPkg)")
                return true
            }
        }

        // Search all launchable apps
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)

        val matched = resolveInfos.firstOrNull { info ->
            val label = info.loadLabel(pm).toString().lowercase()
            val pkg = info.activityInfo.packageName.lowercase()
            label == query || label.contains(query) || query.contains(label) || pkg.contains(query)
        }

        if (matched != null) {
            val targetPkg = matched.activityInfo.packageName
            val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                JarvisStateRepository.addLog("Launched app: ${matched.loadLabel(pm)} ($targetPkg)")
                return true
            }
        }

        JarvisStateRepository.addLog("Could not find installed app matching: $appNameOrPackage")
        return false
    }

    /**
     * Hardware & Utility Controls (Flashlight, Volume, Phone Call, Alarm).
     */
    fun toggleFlashlight(enable: Boolean): Boolean {
        return runCatching {
            val camManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = camManager.cameraIdList.firstOrNull() ?: return false
            camManager.setTorchMode(cameraId, enable)
            JarvisStateRepository.addLog("Flashlight set to: $enable")
            true
        }.getOrDefault(false)
    }

    fun setMediaVolumePercent(percent: Int): Boolean {
        // A-H1: out-of-range volume is rejected, never coerced.
        if (percent !in 0..100) {
            JarvisStateRepository.addLog("Volume rejected: $percent% out of range")
            return false
        }
        return runCatching {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = ((percent / 100.0) * max).toInt()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            JarvisStateRepository.addLog("Volume set to $percent%")
            true
        }.getOrDefault(false)
    }

    /** Contact-lookup outcome (C-B13): the reason is distinguished,
     *  never collapsed into a bare null. error is null on success. */
    data class ContactLookupResult(
        val number: String?,
        val error: String?
    )

    /** Returns (success, reason): ok | contact_not_found |
     *  contact_ambiguous | permission_denied | dial_failed. A name
     *  that does not resolve is NEVER dialled as a tel: string. */
    fun makePhoneCallWithReason(nameOrNumber: String): Pair<Boolean, String> {
        var number = nameOrNumber.trim()
        if (number.isBlank()) return false to "contact_not_found"
        if (number.any { it.isLetter() }) {
            val lookup = lookupContactNumberDetailed(number)
            if (lookup.number == null) {
                return false to (lookup.error ?: "contact_not_found")
            }
            number = lookup.number
        }
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            JarvisStateRepository.addLog("Initiated phone call to $number")
            true to "ok"
        } catch (se: SecurityException) {
            false to "permission_denied"
        } catch (e: Exception) {
            false to "dial_failed"
        }
    }

    /**
     * A-C3 contact matching: EXACT name match wins; otherwise a
     * SUBSTRING match counts only when it is UNIQUE. Zero matches
     * -> contact_not_found; two or more -> contact_ambiguous (the
     * caller must ask for disambiguation, never pick the first).
     * A SecurityException (contacts permission denied) is reported
     * distinctly, never as not-found.
     */
    fun lookupContactNumberDetailed(contactName: String): ContactLookupResult {
        val query = contactName.trim()
        if (query.isBlank()) return ContactLookupResult(null, "contact_not_found")
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val exact = mutableListOf<String>()
            val partial = mutableListOf<String>()
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numIdx) ?: continue
                    if (name.equals(query, ignoreCase = true)) {
                        exact.add(number)
                    } else if (name.contains(query, ignoreCase = true)) {
                        partial.add(number)
                    }
                }
            }
            val distinctExact = exact.distinct()
            if (distinctExact.size == 1) return ContactLookupResult(distinctExact[0], null)
            if (distinctExact.size > 1) return ContactLookupResult(null, "contact_ambiguous")
            val distinctPartial = partial.distinct()
            if (distinctPartial.size == 1) return ContactLookupResult(distinctPartial[0], null)
            if (distinctPartial.size > 1) return ContactLookupResult(null, "contact_ambiguous")
            ContactLookupResult(null, "contact_not_found")
        } catch (se: SecurityException) {
            ContactLookupResult(null, "permission_denied")
        } catch (e: Exception) {
            ContactLookupResult(null, "contact_not_found")
        }
    }

    fun setSystemAlarm(hour: Int, minute: Int, label: String): Boolean {
        return runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            JarvisStateRepository.addLog("Alarm set for $hour:$minute ($label)")
            true
        }.getOrDefault(false)
    }

    /**
     * Captures a live compressed JPEG screenshot (Base64) on Android 11+ (API 30+)
     * so Multimodal AI Vision (Gemini) can see photos, memes, custom game/Flutter UIs, or WebViews.
     */
    suspend fun captureScreenshotBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            runCatching {
                                val hwBitmap = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace
                                )
                                screenshot.hardwareBuffer.close()
                                if (hwBitmap == null) {
                                    if (cont.isActive) cont.resume(null)
                                    return
                                }
                                val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                hwBitmap.recycle()

                                val maxDim = 720
                                val scale = (maxDim.toFloat() / maxOf(softBitmap.width, softBitmap.height)).coerceAtMost(1f)
                                val scaled = Bitmap.createScaledBitmap(
                                    softBitmap,
                                    (softBitmap.width * scale).toInt().coerceAtLeast(1),
                                    (softBitmap.height * scale).toInt().coerceAtLeast(1),
                                    true
                                )
                                if (scaled != softBitmap) softBitmap.recycle()

                                val baos = ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 62, baos)
                                scaled.recycle()

                                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                                JarvisStateRepository.addLog("Captured live screen vision frame (${b64.length / 1024} KB)")
                                if (cont.isActive) cont.resume(b64)
                            }.onFailure {
                                if (cont.isActive) cont.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * Opens an http(s) URL directly. A-C4 hardening: ANY other
     * scheme (upi:/tel:/sms:/intent:/javascript:/file:/...) is
     * blocked here, even if a caller bypassed the engine gate —
     * this function no longer opens deep links at all.
     */
    fun openUrlOrDeepLink(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.contains("://")) {
            val scheme = trimmed.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") {
                JarvisStateRepository.addLog("URL blocked: non-http(s) scheme '$scheme'")
                return false
            }
        }
        return runCatching {
            val formatted = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formatted)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            JarvisStateRepository.addLog("Opened URL/DeepLink: $formatted")
            true
        }.getOrDefault(false)
    }

    /**
     * Launches YouTube directly into search results for the given query so Jarvis can play a song/video in 1 tap.
     */
    fun searchYouTube(query: String): Boolean {
        return runCatching {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(webIntent)
            }
            JarvisStateRepository.addLog("Opened YouTube search for: $query")
            true
        }.getOrDefault(false)
    }

    /**
     * Finds the first video result on a YouTube search-results screen.
     * Heuristic (YouTube UI changes, so this is best-effort, never a
     * blind tap): first clickable element below the search bar whose
     * row is tall/wide like a video thumbnail row, with a real title,
     * and not a chip/button like Search/Filter/Shorts/Home. Returns
     * null when no such row is found, so the caller can fall back to
     * asking the user instead of tapping the wrong thing.
     */
    suspend fun findFirstYouTubeVideo(): ScreenElement? {
        repeat(3) { attempt ->
            val snapshot = captureScreenSnapshot()
            val candidates = snapshot.elements.filter { el ->
                el.isClickable &&
                    el.centerY > 260 &&
                    el.bounds.height() > 110 &&
                    el.bounds.width() > 200 &&
                    (el.text.isNotBlank() || el.contentDesc.isNotBlank())
            }
            val skipWords = listOf(
                "search", "filter", "shorts", "home", "subscriptions",
                "library", "create", "you", "sign in", "voice search"
            )
            val picked = candidates.firstOrNull { el ->
                val label = "${el.text} ${el.contentDesc}".lowercase()
                skipWords.none { label.contains(it) }
            }
            if (picked != null) {
                JarvisStateRepository.addLog(
                    "First YouTube video found: '${picked.text.ifBlank { picked.contentDesc }}'"
                )
                return picked
            }
            if (attempt < 2) kotlinx.coroutines.delay(1800L)
        }
        return null
    }

    /**
     * Opens a WhatsApp chat for a contact/number and taps Send for the
     * given message. Contact names resolve through the phone book;
     * numbers go straight to wa.me. Returns false when the Send button
     * is not found — the chat stays open with the message prefilled,
     * so the user can tap Send themselves instead of losing the draft.
     * Callers must confirm with the user BEFORE invoking this: a sent
     * WhatsApp message cannot be unsent reliably.
     */
    suspend fun sendWhatsAppMessage(contactOrNumber: String, message: String): Boolean {
        if (message.isBlank()) return false
        // A-C3: an ambiguous name fails here — never send to the
        // first substring match, never fall back to another contact.
        var target = contactOrNumber.trim()
        if (target.any { it.isLetter() }) {
            val lookup = lookupContactNumberDetailed(target)
            if (lookup.number == null) {
                JarvisStateRepository.addLog(
                    "WhatsApp send failed: contact '$contactOrNumber' lookup=${lookup.error}"
                )
                return false
            }
            target = lookup.number
        }
        val digits = target.filter { it.isDigit() }.let {
            if (it.length > 10 && it.startsWith("91")) it.drop(2) else it
        }
        if (digits.length < 7) {
            JarvisStateRepository.addLog("WhatsApp send failed: contact '$contactOrNumber' not found in phone book")
            return false
        }
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://wa.me/91$digits?text=${Uri.encode(message)}")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { startActivity(intent) }.onFailure { return false }
        // 1.4.7 (B): no blind 3000ms wait — the send-button poll
        // below re-snapshots itself (3 x 1800ms).
        repeat(3) { attempt ->
            val snapshot = captureScreenSnapshot()
            val sendButton = snapshot.elements.firstOrNull { el ->
                el.isClickable &&
                    (el.contentDesc.lowercase().contains("send") ||
                        el.viewId.lowercase().contains("send"))
            }
            if (sendButton != null) {
                val ok = tapNode(sendButton.id)
                JarvisStateRepository.addLog("WhatsApp message to $digits: send tapped=$ok")
                return ok
            }
            if (attempt < 2) kotlinx.coroutines.delay(1800L)
        }
        JarvisStateRepository.addLog("WhatsApp chat opened for $digits, Send button not found — message prefilled only")
        return false
    }

    /**
     * Controls background media playback (Spotify, YouTube Music, etc.) without switching apps.
     */
    fun controlMediaPlayback(command: String): Boolean {
        return runCatching {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val keyCode = when (command.uppercase()) {
                "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "PREVIOUS", "PREV" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "PAUSE" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "PLAY" -> KeyEvent.KEYCODE_MEDIA_PLAY
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            JarvisStateRepository.addLog("Dispatched Media Control: $command")
            true
        }.getOrDefault(false)
    }

    /**
     * 1.4.25-PRO Step 1B (1f): live music-active state, same
     * AudioManager access path as controlMediaPlayback — no new
     * permission. Observation only: it never starts, stops, or
     * changes playback. False on any error (unknown state reads
     * as "not playing", which callers treat as unverified, never
     * as a success).
     */
    fun isMusicActive(): Boolean {
        return runCatching {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.isMusicActive
        }.getOrDefault(false)
    }

    /**
     * Copies text to the Android system clipboard.
     */
    fun copyToClipboard(text: String): Boolean {
        return runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("JarvisClip", text))
            JarvisStateRepository.addLog("Copied text to clipboard (${text.take(30)})")
            true
        }.getOrDefault(false)
    }
}
