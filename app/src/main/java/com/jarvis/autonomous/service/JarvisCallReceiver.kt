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
 * What it DOES: when a call rings, Jarvis speaks "Call aa raha hai,
 * <name ya number> se" through the foreground service TTS — even when
 * the app is in the background — provided Auto-Announce is ON in
 * AI Brain settings. It uses the same toggle as notification announce.
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
        // pretended). Blank number -> generic marker; the engine
        // turns it into "call aa rahi hai, number nahi dikh raha".
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
