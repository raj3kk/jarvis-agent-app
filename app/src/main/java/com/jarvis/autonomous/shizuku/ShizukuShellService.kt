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

    /** Required constructor. */
    constructor() : super()

    /**
     * Constructor with Context (Shizuku API v13 tries this first).
     * The Context is a bare Application; it is deliberately ignored.
     */
    @Keep
    @Suppress("unused")
    constructor(context: Context) : super()

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
     * Called by the Shizuku server with transaction 16777115
     * (oneway) when the service is removed. Must terminate the
     * process — otherwise a shell-UID process lingers after unbind.
     */
    override fun destroy() {
        exitProcess(0)
    }
}
