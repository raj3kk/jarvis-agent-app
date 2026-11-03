package com.jarvis.autonomous.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jarvis.autonomous.data.JarvisPreferences

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
        }
    }
}
