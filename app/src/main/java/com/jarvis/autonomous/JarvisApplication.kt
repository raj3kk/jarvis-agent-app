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
    companion object {
        /**
         * 1.4.5 (item 1b): process short name — "" for the main
         * process, ":tts" for the isolated TTS process. The old
         * code wrote an identical LAUNCH_START in EVERY process,
         * so a :tts start corrupted the breaker session slicing
         * in wasLastDeathDuringGreetingSpeak (it slices between
         * LAUNCH_START lines). Now only the main process writes
         * LAUNCH_START (tagged process=main); :tts writes
         * TTS_PROCESS_START and never LAUNCH_START. Crash entries
         * carry the same tag so a native :tts death is
         * distinguishable from a main-process crash.
         */
        fun processShortName(app: Application): String {
            // getProcessName() needs API 28+ (minSdk is 26): guard
            // by SDK first — on older devices a direct call would
            // throw NoSuchMethodError (an Error, not an Exception,
            // so a catch-Exception would NOT save it).
            val full = if (android.os.Build.VERSION.SDK_INT >= 28) {
                runCatching { Application.getProcessName() }.getOrNull()
            } else {
                null
            } ?: runCatching {
                val am = app.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
                am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }
                    ?.processName
            }.getOrNull()
            val pkg = app.packageName
            return when {
                full == null -> ""
                full == pkg -> ""
                full.startsWith(pkg) -> full.substring(pkg.length)
                else -> full
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        JarvisDiagnosticsFile.init(filesDir)
        val proc = processShortName(this)
        if (proc == ":tts") {
            // :tts must NEVER write LAUNCH_START (breaker slicing).
            JarvisStateRepository.addDiagnosticSync(
                "tts", "TTS_PROCESS_START",
                "TTS process started (Application.onCreate, process=:tts)."
            )
        } else {
            // 1.4.4 launch-bounce catcher: LAUNCH_START is the FIRST
            // persisted marker of a process start. A previous session
            // with LAUNCH_START but no LAUNCH_UI = died during startup.
            // 1.4.5: tagged process=main so :tts starts can never
            // be mistaken for main-process boundaries.
            JarvisStateRepository.addDiagnosticSync(
                "launch", "LAUNCH_START",
                "App process started (Application.onCreate, process=main)."
            )
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                JarvisDiagnosticsFile.appendCrash(
                    thread.name, throwable,
                    processTag = if (proc.isEmpty()) "main" else proc
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
