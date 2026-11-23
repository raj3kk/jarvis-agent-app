package com.jarvis.autonomous.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R4.2 — kind-wise FCM routing tests. Pure Kotlin, no Android types.
 * The hub sends job_id (due-job) / skill_id (skill_approved) in the
 * FCM data map (fcm.ts sendFcm merges opts.data); the app carries
 * them to the tap intent for MainActivity's deep-link.
 */
class FcmKindRoutingTest {

    @Test
    fun `each kind gets its own notification id`() {
        assertEquals(9001, FcmPushPayload.notificationIdFor("test"))
        assertEquals(9002, FcmPushPayload.notificationIdFor("skill_approved"))
        assertEquals(9003, FcmPushPayload.notificationIdFor("due-job"))
        assertEquals(9004, FcmPushPayload.notificationIdFor("memory_push"))
        assertEquals(9005, FcmPushPayload.notificationIdFor("cron"))
        assertEquals(9001, FcmPushPayload.notificationIdFor("something-new"))
    }

    @Test
    fun `each kind gets its own channel, default is the 1_12_0 channel`() {
        assertEquals("jarvis_autonomous_channel", FcmPushPayload.channelIdFor("test"))
        assertEquals("jarvis_fcm_skill_approved", FcmPushPayload.channelIdFor("skill_approved"))
        assertEquals("jarvis_fcm_due_job", FcmPushPayload.channelIdFor("due-job"))
        assertEquals("jarvis_fcm_memory", FcmPushPayload.channelIdFor("memory_push"))
        assertEquals("jarvis_fcm_cron", FcmPushPayload.channelIdFor("cron"))
        assertEquals("jarvis_autonomous_channel", FcmPushPayload.channelIdFor("unknown-kind"))
    }

    @Test
    fun `channel names are human readable`() {
        assertEquals("Jarvis — Skill approved", FcmPushPayload.channelNameFor("skill_approved"))
        assertEquals("Jarvis — Scheduled job due", FcmPushPayload.channelNameFor("due-job"))
        assertEquals("Jarvis", FcmPushPayload.channelNameFor("test"))
    }

    @Test
    fun `due-job data carries job_id`() {
        val p = FcmPushPayload.fromData(
            mapOf(
                "title" to "Jarvis — scheduled job due",
                "body" to "har subah 8 baje",
                "kind" to "due-job",
                "job_id" to "job-123"
            )
        )
        assertEquals("due-job", p?.kind)
        assertEquals("job-123", p?.jobId)
        assertEquals("", p?.skillId)
    }

    @Test
    fun `skill_approved data carries skill_id`() {
        val p = FcmPushPayload.fromData(
            mapOf(
                "title" to "Skill approved",
                "body" to "Your skill was approved and is ready to use.",
                "kind" to "skill_approved",
                "skill_id" to "sk-abc"
            )
        )
        assertEquals("skill_approved", p?.kind)
        assertEquals("sk-abc", p?.skillId)
        assertEquals("", p?.jobId)
    }

    @Test
    fun `ids default to empty when the hub sends none`() {
        val p = FcmPushPayload.fromData(mapOf("body" to "Hi", "kind" to "test"))
        assertEquals("", p?.jobId)
        assertEquals("", p?.skillId)
    }
}
