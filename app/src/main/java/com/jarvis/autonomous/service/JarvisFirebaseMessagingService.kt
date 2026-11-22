package com.jarvis.autonomous.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jarvis.autonomous.data.FcmPushPayload
import com.jarvis.autonomous.data.FcmTokenClient
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * FCM push receiver (1.12.0, DESIGN expansion/fcm/DESIGN.md).
 *
 * Data-only messages from the hub. onNewToken -> re-register (only
 * when paired). onMessageReceived -> show a notification on the
 * existing jarvis_autonomous_channel (same channel + icon pattern as
 * the captcha-assist notification in HubWsClient).
 *
 * Display only: no TTS, no voice, no mic — the voice paths are
 * untouched. Empty body = log only, never shown.
 */
class JarvisFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "jarvis_autonomous_channel"
        const val FCM_NOTIFICATION_ID = 9001
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val appContext = applicationContext
        if (!JarvisPreferences(appContext).pairingPaired) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { FcmTokenClient.register(appContext, token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = FcmPushPayload.fromData(message.data)
        if (payload == null) {
            JarvisStateRepository.addLog("FCM push aaya par body khaali thi — notification nahi dikhayi.")
            return
        }
        showNotification(payload)
    }

    private fun showNotification(payload: FcmPushPayload) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Jarvis", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this,
                FCM_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(payload.title)
                .setContentText(payload.body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(FCM_NOTIFICATION_ID, notification)
        }.onFailure {
            JarvisStateRepository.addLog("FCM notification nahi dikhayi ja saki.")
        }
    }
}
