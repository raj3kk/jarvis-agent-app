package com.jarvis.autonomous

import android.app.Application
import com.jarvis.autonomous.data.JarvisDiagnosticsFile

/**
 * 1.4.2 bug catcher — app entry point.
 *
 * There was no Application class before; every process start now
 * installs a CHAINED uncaught-exception handler: the crash is first
 * recorded to the persisted diagnostics file (which survives the
 * process death), then the previous handler runs exactly as before,
 * so crash behaviour (system dialog / restart) is unchanged. The
 * handler never swallows a crash and never uploads anything.
 */
class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        JarvisDiagnosticsFile.init(filesDir)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                JarvisDiagnosticsFile.appendCrash(thread.name, throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
