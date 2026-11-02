package com.jarvis.autonomous.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
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

        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
        val name = lookupContactName(context, number)
        val display = name.ifBlank { number.ifBlank { "unknown number" } }
        JarvisStateRepository.addLog("Incoming call ringing from $display")

        val announceIntent = Intent(context.applicationContext, JarvisForegroundService::class.java).apply {
            action = JarvisForegroundService.ACTION_ANNOUNCE_CALL
            putExtra(JarvisForegroundService.EXTRA_CALL_DISPLAY, display)
        }
        runCatching {
            context.applicationContext.startService(announceIntent)
        }
    }

    private fun lookupContactName(context: Context, number: String): String {
        if (number.isBlank()) return ""
        val digits = number.filter { it.isDigit() }.takeLast(10)
        if (digits.length < 7) return ""
        return runCatching {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val num = cursor.getString(numIdx) ?: continue
                    if (num.filter { it.isDigit() }.takeLast(10) == digits) return name
                }
            }
            ""
        }.getOrDefault("")
    }
}
