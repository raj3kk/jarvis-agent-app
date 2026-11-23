package com.jarvis.autonomous.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4.11 (U8-SERVER) — skill-approve response mapping tests. Pure
 * Kotlin (parseApproveResponse takes only the parsed JSON), so no
 * network is needed. The live route is POST /api/skills/approve
 * (hub v20) — it records a skill_reviews row and the skill stays
 * quarantined until admin review.
 */
class SkillApprovalClientTest {

    @Test
    fun `ok maps to Ok with deduped flag`() {
        val r = SkillApprovalClient.parseApproveResponse(
            JSONObject("""{"ok":true,"skill_id":"sk-1","status":"quarantined","user_approved":true,"deduped":false}""")
        )
        assertEquals(SkillApprovalClient.ApproveResult.Ok(deduped = false), r)
        val r2 = SkillApprovalClient.parseApproveResponse(
            JSONObject("""{"ok":true,"skill_id":"sk-1","deduped":true}""")
        )
        assertEquals(SkillApprovalClient.ApproveResult.Ok(deduped = true), r2)
    }

    @Test
    fun `skill_not_found names the missing skill`() {
        val r = SkillApprovalClient.parseApproveResponse(
            JSONObject("""{"error":"skill_not_found"}""")
        )
        assertTrue(r is SkillApprovalClient.ApproveResult.Err)
        assertTrue((r as SkillApprovalClient.ApproveResult.Err).message.contains("server par nahi mili"))
    }

    @Test
    fun `device_not_paired asks for pairing`() {
        val r = SkillApprovalClient.parseApproveResponse(
            JSONObject("""{"error":"device_not_paired"}""")
        )
        assertTrue((r as SkillApprovalClient.ApproveResult.Err).message.contains("pairing"))
    }

    @Test
    fun `rate_limited asks to wait`() {
        val r = SkillApprovalClient.parseApproveResponse(
            JSONObject("""{"error":"rate_limited"}""")
        )
        assertTrue((r as SkillApprovalClient.ApproveResult.Err).message.contains("ruk"))
    }

    @Test
    fun `null json is an error, never a silent ok`() {
        val r = SkillApprovalClient.parseApproveResponse(null)
        assertTrue(r is SkillApprovalClient.ApproveResult.Err)
    }

    @Test
    fun `unknown error surfaces the hub detail`() {
        val r = SkillApprovalClient.parseApproveResponse(
            JSONObject("""{"error":"weird","detail":"something broke"}""")
        )
        assertEquals(
            SkillApprovalClient.ApproveResult.Err("something broke"),
            r
        )
    }
}
