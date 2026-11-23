package com.jarvis.autonomous.browser

import android.net.Uri
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 1.15.0-PRO (redesign R0) — bridge between [JarvisJobEngine]'s "upload"
 * step and the foreground host's `onShowFileChooser`. Adapted from WhopClip
 * `UploadBridge` (same latch contract): the engine clicks the
 * `<input type=file>`; the host shows the system picker (or auto-supplies
 * a pre-downloaded file); the result is signalled here.
 *
 * Headless runs have no host — [awaitFile] returns false and the engine
 * throws `needs_foreground`, so the job is reported honestly instead of
 * waiting on a picker that can never appear. The foreground host itself
 * is R3 work (tap-to-operate); until then, [armWithUri] lets a caller
 * pre-supply a file the host can hand to the chooser without a picker.
 */
object JarvisUploadBridge {

    @Volatile
    private var latch: CountDownLatch? = null

    @Volatile
    var fileChosen: Boolean = false
        private set

    @Volatile
    private var pendingUri: Uri? = null

    /** True while a job is waiting on a file-chooser result. */
    fun isArmed(): Boolean = latch != null

    fun arm() {
        fileChosen = false
        latch = CountDownLatch(1)
    }

    /** Arm with a pre-supplied file (FileProvider URI) the host may auto-supply. */
    fun armWithUri(uri: Uri) {
        pendingUri = uri
        arm()
    }

    /** Take the pre-supplied URI once (one-shot — the host consumes it). */
    fun takePendingUri(): Uri? {
        val u = pendingUri
        pendingUri = null
        return u
    }

    fun signal(chosen: Boolean) {
        fileChosen = chosen
        latch?.countDown()
    }

    fun disarm() {
        latch = null
        pendingUri = null
    }

    /** True if a file was picked within the timeout. */
    fun awaitFile(timeoutMs: Long): Boolean {
        val l = latch ?: return false
        return try {
            l.await(timeoutMs, TimeUnit.MILLISECONDS) && fileChosen
        } catch (e: InterruptedException) {
            false
        }
    }
}
