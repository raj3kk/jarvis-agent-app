package com.jarvis.autonomous.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.data.NotificationItem
import java.util.concurrent.ConcurrentHashMap

data class DirectReplyHandle(
    val packageName: String,
    val senderTitle: String,
    val pendingIntent: PendingIntent,
    val remoteInputs: Array<RemoteInput>,
    val timestamp: Long = System.currentTimeMillis()
)

class JarvisNotificationService : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: JarvisNotificationService? = null
            private set

        private val replyHandles = ConcurrentHashMap<String, DirectReplyHandle>()

        /**
         * Replies directly to a WhatsApp / Instagram / Telegram / SMS notification in the background
         * using Android's RemoteInput API without even opening the target app.
         */
        /**
         * A-C3 retargeting: FAIL (do not send) on no exact/unique
         * match. The old code fell back to the most-recent sender
         * when nothing matched — a reply could go to the wrong
         * person. Rules: exact sender/package match wins; otherwise
         * a substring match counts only when UNIQUE; zero or two+
         * matches both fail. An empty filter fails too.
         */
        fun sendDirectReply(senderOrAppFilter: String, message: String): Boolean {
            val svc = instance ?: return false
            if (message.isBlank() || replyHandles.isEmpty()) return false

            val filter = senderOrAppFilter.trim().lowercase()
            if (filter.isEmpty()) {
                JarvisStateRepository.addLog("Direct reply refused: empty sender filter (no most-recent fallback)")
                return false
            }
            val exact = replyHandles.values.filter {
                it.senderTitle.lowercase() == filter ||
                    it.packageName.lowercase() == filter
            }
            val matchedHandle = when {
                exact.size == 1 -> exact[0]
                exact.size > 1 -> {
                    JarvisStateRepository.addLog("Direct reply refused: ambiguous sender '$senderOrAppFilter' (${exact.size} matches)")
                    return false
                }
                else -> {
                    val partial = replyHandles.values.filter {
                        it.senderTitle.lowercase().contains(filter) ||
                            it.packageName.lowercase().contains(filter)
                    }
                    if (partial.size != 1) {
                        JarvisStateRepository.addLog(
                            "Direct reply refused: no unique match for '$senderOrAppFilter' (${partial.size} matches)"
                        )
                        return false
                    }
                    partial[0]
                }
            }

            return runCatching {
                val fillInIntent = Intent()
                val resultsBundle = Bundle()
                for (remoteInput in matchedHandle.remoteInputs) {
                    resultsBundle.putCharSequence(remoteInput.resultKey, message)
                }
                RemoteInput.addResultsToIntent(matchedHandle.remoteInputs, fillInIntent, resultsBundle)
                matchedHandle.pendingIntent.send(svc.applicationContext, 0, fillInIntent)
                JarvisStateRepository.addLog(
                    "Sent background direct reply to '${matchedHandle.senderTitle}': \"$message\""
                )
                true
            }.getOrDefault(false)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        JarvisStateRepository.addLog("Notification Listener Connected (Direct Reply Ready).")
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.isOngoing) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        if (title.isEmpty() && text.isEmpty()) return

        // Extract RemoteInput quick-reply action if present (WhatsApp, Instagram, Telegram, Messages)
        notification.actions?.forEach { action ->
            val remoteInputs = action.remoteInputs
            val actionIntent = action.actionIntent
            if (!remoteInputs.isNullOrEmpty() && actionIntent != null) {
                val key = "${pkg}:${title.lowercase()}"
                replyHandles[key] = DirectReplyHandle(
                    packageName = pkg,
                    senderTitle = title.ifEmpty { pkg },
                    pendingIntent = actionIntent,
                    remoteInputs = remoteInputs
                )
            }
        }

        val item = NotificationItem(
            packageName = pkg,
            title = title,
            text = text
        )
        JarvisStateRepository.addNotification(item)

        // If Auto-Announce is enabled in preferences, notify ForegroundService
        val prefs = JarvisPreferences(applicationContext)
        if (prefs.autoAnnounceNotifications && title.isNotEmpty() && text.isNotEmpty()) {
            val announceIntent = Intent(applicationContext, JarvisForegroundService::class.java).apply {
                action = JarvisForegroundService.ACTION_ANNOUNCE_NOTIFICATION
                putExtra(JarvisForegroundService.EXTRA_NOTIF_SENDER, title)
                putExtra(JarvisForegroundService.EXTRA_NOTIF_TEXT, text)
            }
            runCatching {
                startService(announceIntent)
            }
        }
    }
}
