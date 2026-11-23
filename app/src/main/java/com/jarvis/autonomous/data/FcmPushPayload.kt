package com.jarvis.autonomous.data

/**
 * FCM push payload (1.12.0, DESIGN expansion/fcm/DESIGN.md).
 *
 * Pure Kotlin — no Android/Firebase types, so it is unit-testable on
 * the JVM. The hub sends data-only messages {title, body, kind}; an
 * empty body means "no notification" (logged only, never shown).
 *
 * R4.2: the hub also sends `data: {job_id}` on due-job pushes and
 * `data: {skill_id}` on skill_approved pushes (fcm.ts sendFcm
 * merges opts.data) — the app carries them through to the tap
 * intent so MainActivity can deep-link. Kind-wise notification
 * IDs + channels live here as pure functions (same file, same
 * tests): every kind gets its own notification ID, so a due-job
 * push never clobbers a skill_approved one (the 1.12.0 single ID
 * 9001 did exactly that).
 */
data class FcmPushPayload(
    val title: String,
    val body: String,
    val kind: String,
    val jobId: String = "",
    val skillId: String = ""
) {
    companion object {
        /** R4.2 — one notification ID per kind (9001 stays test/default). */
        fun notificationIdFor(kind: String): Int = when (kind) {
            "skill_approved" -> 9002
            "due-job" -> 9003
            "memory_push" -> 9004
            "cron" -> 9005
            else -> 9001
        }

        /** R4.2 — one NotificationChannel per kind (default = the 1.12.0 channel). */
        fun channelIdFor(kind: String): String = when (kind) {
            "skill_approved" -> "jarvis_fcm_skill_approved"
            "due-job" -> "jarvis_fcm_due_job"
            "memory_push" -> "jarvis_fcm_memory"
            "cron" -> "jarvis_fcm_cron"
            else -> "jarvis_autonomous_channel"
        }

        /** R4.2 — human channel name shown in Android Settings. */
        fun channelNameFor(kind: String): String = when (kind) {
            "skill_approved" -> "Jarvis — Skill approved"
            "due-job" -> "Jarvis — Scheduled job due"
            "memory_push" -> "Jarvis — Memory"
            "cron" -> "Jarvis — Scheduled"
            else -> "Jarvis"
        }

        fun fromData(data: Map<String, String>): FcmPushPayload? {
            val body = data["body"]?.trim().orEmpty()
            if (body.isEmpty()) return null
            return FcmPushPayload(
                title = data["title"]?.trim()?.takeIf { it.isNotEmpty() } ?: "Jarvis",
                body = body,
                kind = data["kind"]?.trim()?.takeIf { it.isNotEmpty() } ?: "test",
                jobId = data["job_id"]?.trim().orEmpty(),
                skillId = data["skill_id"]?.trim().orEmpty()
            )
        }
    }
}
