package com.jarvis.autonomous.shizuku

import android.content.Context
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 1.25.0 (Shizuku): the privileged shell runner.
 *
 * Instantiated by the Shizuku server via reflection (it tries the
 * (Context) constructor first, then the no-arg one — both must be
 * public). The Context it receives is a bare android.app.Application
 * created by the server: JarvisApplication is NOT instantiated and
 * onCreate() is NOT called in this process, so this class must NEVER
 * touch any Jarvis singleton, repository, or preference — it is pure
 * ProcessBuilder + Bundle, nothing else.
 *
 * Runs in a separate process (`com.jarvis.autonomous:shizuku-shell`)
 * as the shell identity (UID 2000 on a non-root phone, UID 0 on a
 * rooted phone). The app process talks to it over Binder through
 * [IShizukuShellService].
 *
 * Single-flight: the bridge ([ShizukuManager]) serialises calls with
 * a Mutex, so one exec at a time.
 */
@Keep
class ShizukuShellService : IShizukuShellService.Stub {

    /**
     * The Context the Shizuku server passes (a bare Application).
     * Stored ONLY so [isCallAudioInterceptable] can reach the
     * system AudioManager — nothing else in this class touches it,
     * and no Jarvis singleton, repository, or preference is ever
     * touched here (this process has no JarvisApplication).
     */
    private var serviceContext: Context? = null

    /** Required constructor. */
    constructor() : super()

    /**
     * Constructor with Context (Shizuku API v13 tries this first).
     */
    @Keep
    @Suppress("unused")
    constructor(context: Context) : super() {
        serviceContext = context
    }

    override fun exec(command: String, timeoutMs: Int): android.os.Bundle {
        val timeout = timeoutMs.coerceAtLeast(1).toLong()
        var outText = ""
        var errText = ""
        return try {
            val proc = ProcessBuilder("sh", "-c", command)
                .directory(File("/"))
                .redirectErrorStream(false)
                .start()
            // Drain both streams concurrently to avoid pipe-buffer
            // deadlock (a full stdout/stderr buffer blocks the child).
            val outThread = Thread {
                runCatching { outText = proc.inputStream.bufferedReader().readText() }
            }
            val errThread = Thread {
                runCatching { errText = proc.errorStream.bufferedReader().readText() }
            }
            outThread.start()
            errThread.start()
            val finished = proc.waitFor(timeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
            }
            // Join with a cap: if a grandchild keeps a pipe open the
            // reader could block forever — never hang the binder
            // thread on it.
            outThread.join(2000)
            errThread.join(2000)
            android.os.Bundle().apply {
                putInt("code", if (finished) proc.exitValue() else 124)
                putString("out", outText)
                putString(
                    "err",
                    if (finished) errText
                    else (errText + "\n[timeout after ${timeoutMs}ms: $command]")
                )
            }
        } catch (e: Exception) {
            android.os.Bundle().apply {
                putInt("code", 127)
                putString("out", "")
                putString("err", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    override fun exit() {
        destroy()
    }

    /**
     * 1.26.0 (M0 probe P3): calls the hidden
     * AudioManager.isPstnCallAudioInterceptable() in this process.
     * The shell UID holds CALL_AUDIO_INTERCEPTION, but the hidden
     * API itself may still refuse reflection on this build — that
     * is a real probe outcome (supported=false), not a crash: the
     * caller reports UNKNOWN and the audio engine is not built.
     * Catches Throwable on purpose: hidden-API enforcement throws
     * Errors (not Exceptions) on some builds.
     */
    override fun isCallAudioInterceptable(): android.os.Bundle {
        val ctx = serviceContext
        if (ctx == null) {
            return android.os.Bundle().apply {
                putBoolean("supported", false)
                putBoolean("interceptable", false)
                putString("error", "no Context in this process")
            }
        }
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val method = android.media.AudioManager::class.java.getMethod(
                "isPstnCallAudioInterceptable"
            )
            val result = method.invoke(am) as? Boolean
            android.os.Bundle().apply {
                putBoolean("supported", result != null)
                putBoolean("interceptable", result == true)
                putString("error", if (result == null) "method returned null" else "")
            }
        } catch (t: Throwable) {
            android.os.Bundle().apply {
                putBoolean("supported", false)
                putBoolean("interceptable", false)
                putString(
                    "error",
                    (t.javaClass.simpleName + ": " + (t.message ?: "")).take(300)
                )
            }
        }
    }

    /**
     * Called by the Shizuku server with transaction 16777115
     * (oneway) when the service is removed. Must terminate the
     * process — otherwise a shell-UID process lingers after unbind.
     */
    override fun destroy() {
        exitProcess(0)
    }
}
