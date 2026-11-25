package com.jarvis.autonomous.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.shizuku.ShizukuManager

class BootCompletedReceiver : BroadcastReceiver() {
    /**
     * 1.4.10 (Step 5) BOOT confirmation: the receiver starts the
     * service ONLY when the user enabled auto-start-on-boot, via
     * startForegroundService (the correct API 26+ call). The
     * service create path can never crash on this start: it
     * goes through startForegroundSafe (qualified types +
     * specialUse-only retry + record-and-continue). If the
     * system denies the background start on this build/OEM,
     * FGS_START_FAILED is recorded and the service continues
     * without foreground state (it may then be killed; the mic
     * restore retries on the next app open via the onCreate
     * restore path — no silent permanent loss). No behaviour
     * change here, only the documented degradation path.
     */
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = JarvisPreferences(context)
            if (prefs.autoStartOnBoot) {
                val serviceIntent = Intent(context, JarvisForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            // 1.15.0 (R0 backstop): re-arm the periodic worker after
            // every boot, unconditionally — the worker itself is a
            // no-op unless a plan is unfinished, so this does not
            // start anything the user did not ask for.
            JarvisBackstop.enqueue(context)
            // 1.25.0 (Shizuku): silent re-apply of the shell-level
            // protections (accessibility + Doze whitelist + standby
            // bucket + appops). ONLY when the Shizuku server is
            // running AND Jarvis is authorized; otherwise do nothing
            // (the settings persist across reboots anyway). No UI, no
            // notification, never blocks boot — goAsync() is finished
            // by the manager within 25 s at most.
            ShizukuManager.reapplyOnBootAsync(context, goAsync())
        }
    }
}
