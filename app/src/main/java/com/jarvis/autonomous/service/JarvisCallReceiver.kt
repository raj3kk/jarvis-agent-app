package com.jarvis.autonomous.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository

/**
 * Incoming-call announcer (user order 2026-11-02).
 *
 * What it DOES: when a call rings, Jarvis speaks the CONSTANT
 * generic line "Bhai, call aa rahi hai." through the foreground
 * service TTS — even when the app is in the background — provided
 * Auto-Announce is ON in AI Brain settings. 1.4.8 (G8 F3)
 * GENERIC-ONLY by user decision (2026-11-03 09:18 IST): the
 * display value is INTENTIONALLY IGNORED by the engine — no
 * name/number is ever spoken from this path, even if the OS/OEM
 * delivers one. (An older header here claimed the announce spoke
 * "<name ya number>" — that was stale and contradicted the
 * generic-only enforcement; behaviour unchanged, comment fixed.)
 *
 * What it does NOT do (Android limit, told to the user honestly):
 * it does NOT answer the call and does NOT talk to the caller. A
 * normal app cannot inject its voice into a phone call's audio.
 * Answering is still done by the user tapping Accept (or by the
 * existing CALL action for outgoing calls).
 */
class JarvisCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        val prefs = JarvisPreferences(context.applicationContext)
        if (!prefs.autoAnnounceNotifications) return

        // C-B13 GENERIC-ONLY: no contact-name lookup (phone-state
        // permissions are NOT restored, so the caller is never
        // pretended). Blank number -> generic marker. 1.4.8: the
        // engine IGNORES the display value entirely and speaks
        // the constant generic line — the number never reaches
        // chat or speech from this path (an older comment here
        // claimed otherwise; behaviour unchanged, comment fixed).
        // Non-blank on purpose: the foreground service only
        // announces non-blank display values.
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
        val display = number.ifBlank { "number nahi dikh raha" }
        JarvisStateRepository.addLog(
            "Incoming call ringing (number ${if (number.isBlank()) "hidden" else "present"})"
        )

        val announceIntent = Intent(context.applicationContext, JarvisForegroundService::class.java).apply {
            action = JarvisForegroundService.ACTION_ANNOUNCE_CALL
            putExtra(JarvisForegroundService.EXTRA_CALL_DISPLAY, display)
        }
        runCatching {
            context.applicationContext.startService(announceIntent)
        }
    }

}
