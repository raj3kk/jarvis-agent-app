package com.jarvis.autonomous.service

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jarvis.autonomous.data.TrainedIngestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * R3.1 — Live screen v1 (U22): the phone's own eyes for tap-to-operate.
 *
 * One still per 1–2 s (AccessibilityService.takeScreenshot, max 720 px)
 * + on-device ML Kit OCR (Latin + Devanagari, already bundled — no new
 * dependency) + the node-tree snapshot the engine already uses. The
 * OCR text + element boxes give the planner buttons the node tree
 * misses (WebView / canvas / Flutter text) and give the CFD loop its
 * "did the tap change the screen?" check.
 *
 * PRIVACY (O-T4): a sensitive screen is NEVER captured, NEVER OCR'd,
 * NEVER sent anywhere. The check runs on the node tree FIRST (package
 * name, hasSensitivePaymentPrompt, password fields, OTP/payment
 * keywords) — the screenshot is only taken when the tree is clean —
 * and again on the OCR text (a keyword hit discards the frame AFTER
 * OCR but BEFORE use/send). An excluded frame carries
 * excluded=true + screenKind + NO text + NO boxes; diagnostics carry
 * only `screen_excluded: <kind>`. The hub drops the same kinds as
 * defense in depth (LIVE_SCREEN_SENSITIVE_KINDS). Pixels NEVER leave
 * the phone on this path — only OCR text + boxes go to the hub, and
 * only when the socket is connected (a down socket loses the frame;
 * the local tap flow never waits on the hub).
 *
 * BOUNDS: the watch is time-boxed — it runs while tap activity keeps
 * coming (each tap refreshes the deadline by 60 s) and stops itself
 * at most 5 min after it started. There is no always-on screen
 * capture: no tap task = no watch = no screenshots. One still is
 * ~1 OCR pass (~100–300 ms on-device); the 1.5 s cadence keeps the
 * Vivo responsive.
 */
data class OcrBox(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val label: String
)

data class LiveScreenFrame(
    val text: String,
    val boxes: List<OcrBox>,
    val packageName: String,
    /** "normal" | "otp" | "banking" | "password" | "payment" | "empty" */
    val screenKind: String,
    /** true = sensitive screen — text/boxes are EMPTY, never use/send. */
    val excluded: Boolean,
    val atMs: Long
)

class LiveScreenWatcher(
    val service: JarvisAccessibilityService,
    private val scope: CoroutineScope
) {
    companion object {
        /** One still per 1.5 s while watching (brief: 1–2 s cadence). */
        const val STILL_INTERVAL_MS = 1_500L

        /** A tap refreshes the watch deadline by this long. */
        const val WATCH_TTL_AFTER_TAP_MS = 60_000L

        /** Hard cap on one watch session (self-stop, no always-on). */
        const val WATCH_MAX_MS = 5 * 60_000L

        /** OCR text kept per frame (prompt + hub cap). */
        const val MAX_TEXT_CHARS = 2_000

        /** Boxes kept per frame (hub payload bound). */
        const val MAX_BOXES = 60

        private val BANKING_PACKAGE_HINTS = listOf(
            "bank", "paytm", "phonepe", "gpay", "bhim", "upi",
            "wallet", "finance", "payzapp", "amazonpay"
        )

        private val OTP_KEYWORDS = listOf(
            "otp", "one-time password", "verification code", "verify code"
        )

        private val PASSWORD_KEYWORDS = listOf(
            "password", "passcode", "pin", "cvv", "card number", "card no"
        )

        private val PAYMENT_KEYWORDS = listOf(
            "confirm payment", "proceed to pay", "pay ₹", "send money",
            "upi pin", "enter pin", "payment"
        )
    }

    private var watchJob: Job? = null
    private var watchStartedMs: Long = 0L
    private var lastTapActivityMs: Long = 0L
    private var lastEmittedHash: Int = 0

    @Volatile
    var latestFrame: LiveScreenFrame? = null
        private set

    val isWatching: Boolean
        get() = watchJob?.isActive == true

    /**
     * One still, right now: node tree (sensitive check) → screenshot →
     * ML Kit OCR (Latin + Devanagari) → frame. Returns null only when
     * the service cannot take a screenshot at all (API < 30, service
     * not active). A sensitive screen returns an EXCLUDED frame, never
     * null — callers must check [LiveScreenFrame.excluded] before
     * using text/boxes.
     */
    suspend fun readScreen(): LiveScreenFrame? {
        val atMs = System.currentTimeMillis()
        val snapshot = runCatching { service.captureScreenSnapshot() }.getOrNull()
        val pkg = snapshot?.packageName ?: "unknown"

        // --- Sensitive check 1: node tree (BEFORE any screenshot) ---
        val kindFromTree = classifyFromTree(snapshot, pkg)
        if (kindFromTree != null) {
            return LiveScreenFrame(
                text = "", boxes = emptyList(), packageName = pkg,
                screenKind = kindFromTree, excluded = true, atMs = atMs
            )
        }
        if (snapshot != null && snapshot.elements.isEmpty()) {
            return LiveScreenFrame(
                text = "", boxes = emptyList(), packageName = pkg,
                screenKind = "empty", excluded = false, atMs = atMs
            )
        }

        val bitmap = service.captureScreenshotBitmap() ?: return null
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val latin = runCatching {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(image).await()
            }.getOrNull()
            val devanagari = runCatching {
                TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                    .process(image).await()
            }.getOrNull()
            val merged = TrainedIngestion.mergeOcrTexts(
                latin?.text ?: "", devanagari?.text ?: ""
            )
            // --- Sensitive check 2: OCR text (AFTER OCR, BEFORE use) ---
            val kindFromOcr = classifyOcrText(merged)
            if (kindFromOcr != null) {
                return LiveScreenFrame(
                    text = "", boxes = emptyList(), packageName = pkg,
                    screenKind = kindFromOcr, excluded = true, atMs = atMs
                )
            }
            val boxes = buildList {
                for (block in (latin?.textBlocks.orEmpty() + devanagari?.textBlocks.orEmpty())) {
                    for (line in block.lines) {
                        val label = line.text.trim()
                        val rect = line.boundingBox ?: continue
                        if (label.isEmpty()) continue
                        add(OcrBox(rect.left, rect.top, rect.width(), rect.height(), label))
                        if (size >= MAX_BOXES) break
                    }
                    if (size >= MAX_BOXES) break
                }
            }
            LiveScreenFrame(
                text = merged.take(MAX_TEXT_CHARS),
                boxes = boxes, packageName = pkg,
                screenKind = "normal", excluded = false, atMs = atMs
            )
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    /**
     * Start the watch: one still per [STILL_INTERVAL_MS], emitting a
     * frame only when the OCR text CHANGED since the last emitted
     * frame (on-change + cadence). Each call refreshes the tap
     * deadline. The loop stops itself when the deadline passes with
     * no tap activity or [WATCH_MAX_MS] elapses — call
     * [noteTapActivity] before every tap-type action to keep it alive.
     */
    fun startWatch(onFrame: (LiveScreenFrame) -> Unit) {
        noteTapActivity()
        if (watchJob?.isActive == true) return
        watchStartedMs = System.currentTimeMillis()
        lastEmittedHash = 0
        watchJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                if (now - lastTapActivityMs > WATCH_TTL_AFTER_TAP_MS ||
                    now - watchStartedMs > WATCH_MAX_MS
                ) {
                    break
                }
                val frame = runCatching { readScreen() }.getOrNull()
                if (frame != null) {
                    latestFrame = frame
                    if (!frame.excluded) {
                        val hash = frame.text.hashCode()
                        if (hash != lastEmittedHash) {
                            lastEmittedHash = hash
                            onFrame(frame)
                        }
                    }
                }
                delay(STILL_INTERVAL_MS)
            }
            watchJob = null
        }
    }

    /** A tap-type action is about to run — keep the watch alive. */
    fun noteTapActivity() {
        lastTapActivityMs = System.currentTimeMillis()
    }

    fun stopWatch() {
        watchJob?.cancel()
        watchJob = null
    }

    private fun classifyFromTree(snapshot: ScreenSnapshot?, pkg: String): String? {
        if (snapshot == null) return null
        if (snapshot.hasSensitivePaymentPrompt) return "payment"
        val pkgLower = pkg.lowercase()
        if (BANKING_PACKAGE_HINTS.any { pkgLower.contains(it) }) return "banking"
        if (snapshot.elements.any { it.isPassword }) return "password"
        val combined = snapshot.elements
            .joinToString(" ") { "${it.text} ${it.contentDesc}" }.lowercase()
        if (OTP_KEYWORDS.any { combined.contains(it) }) return "otp"
        if (PASSWORD_KEYWORDS.any { combined.contains(it) }) return "password"
        if (PAYMENT_KEYWORDS.any { combined.contains(it) }) return "payment"
        return null
    }

    private fun classifyOcrText(text: String): String? {
        val lower = text.lowercase()
        if (OTP_KEYWORDS.any { lower.contains(it) }) return "otp"
        if (PASSWORD_KEYWORDS.any { lower.contains(it) }) return "password"
        if (PAYMENT_KEYWORDS.any { lower.contains(it) }) return "payment"
        return null
    }
}
