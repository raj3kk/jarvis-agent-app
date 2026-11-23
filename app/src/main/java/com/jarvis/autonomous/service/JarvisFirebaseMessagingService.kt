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
        // 1.12.0 single channel + ID — kept for reference. R4.2
        // routes per kind (FcmPushPayload.channelIdFor /
        // notificationIdFor): the default kind still maps to these
        // exact values ("jarvis_autonomous_channel" / 9001).
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
            // R4.2 — kind-wise channel + notification ID (FcmPushPayload
            // companion): a due-job push no longer clobbers a
            // skill_approved one. The channel is created on demand —
            // creating an existing channel is a no-op.
            val channelId = FcmPushPayload.channelIdFor(payload.kind)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        FcmPushPayload.channelNameFor(payload.kind),
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val notificationId = FcmPushPayload.notificationIdFor(payload.kind)
            // R3.9: the tap intent carries the push payload
            // (kind + text) so MainActivity can hand it to the FGS
            // and the Talker speaks what arrived. The kind defaults
            // to "Push" when the payload has none.
            // R4.2 — deep-link: the tap also carries job_id (due-job)
            // / skill_id (skill_approved) so MainActivity opens the
            // right tab. The PendingIntent request code IS the
            // notification ID — per-kind codes, so two kinds never
            // collapse into one intent (FLAG_UPDATE_CURRENT).
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_FCM_KIND, payload.kind.ifBlank { "Push" })
                putExtra(MainActivity.EXTRA_FCM_TEXT, payload.body)
                if (payload.jobId.isNotBlank()) putExtra(MainActivity.EXTRA_FCM_JOB_ID, payload.jobId)
                if (payload.skillId.isNotBlank()) putExtra(MainActivity.EXTRA_FCM_SKILL_ID, payload.skillId)
            }
            val pi = PendingIntent.getActivity(
                this,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(payload.title)
                .setContentText(payload.body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(notificationId, notification)
        }.onFailure {
            JarvisStateRepository.addLog("FCM notification nahi dikhayi ja saki.")
        }
    }
}
