package com.jarvis.autonomous

import android.app.Application
import com.jarvis.autonomous.data.JarvisDiagnosticsFile
import com.jarvis.autonomous.data.JarvisStateRepository

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
        // 1.4.4 launch-bounce catcher: LAUNCH_START is the FIRST
        // persisted marker of a process start. A previous session
        // with LAUNCH_START but no LAUNCH_UI = died during startup.
        JarvisStateRepository.addDiagnostic(
            "launch", "LAUNCH_START", "App process started (Application.onCreate)."
        )
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                JarvisDiagnosticsFile.appendCrash(thread.name, throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
