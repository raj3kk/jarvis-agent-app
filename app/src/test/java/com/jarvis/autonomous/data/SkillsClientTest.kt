package com.jarvis.autonomous.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.21.0-PRO — unit tests for the SkillsClient's pure parts: the
 * /api/skills/catalog response parser, the install response parser
 * (success / deduped / tier 403 / cap 403 / errors), and the frozen
 * signed-payload builders. No network, no pairing — the device-signed
 * request itself is covered by the live hub probes, not by this test.
 */
class SkillsClientTest {

    private val catalogJson = JSONObject(
        """
        {
          "ok": true,
          "plan_tier": "pro",
          "count": 3,
          "skills": [
            {"id": "email-management", "name": "Email Management", "description": "Inbox triage aur draft", "category": "productivity", "tier": "free", "installed": true},
            {"id": "documents", "name": "Documents", "description": "Docs padho aur likho", "category": "productivity", "tier": "pro", "installed": false},
            {"id": "shopping", "name": "Shopping", "description": "Products dhoondo aur compare karo", "category": "commerce", "tier": "pro", "installed": false}
          ]
        }
        """.trimIndent()
    )

    @Test
    fun parseCatalogJsonReadsSkillsTiersAndInstalled() {
        val result = SkillsClient.parseCatalogJson(catalogJson)
        assertEquals("pro", result.planTier)
        assertEquals(3, result.count)
        assertEquals(3, result.skills.size)
        assertEquals("email-management", result.skills[0].id)
        assertEquals("Email Management", result.skills[0].name)
        assertEquals("free", result.skills[0].tier)
        assertTrue(result.skills[0].installed)
        assertEquals("documents", result.skills[1].id)
        assertEquals("pro", result.skills[1].tier)
        assertFalse(result.skills[1].installed)
        assertEquals("commerce", result.skills[2].category)
    }

    @Test
    fun parseCatalogJsonToleratesMissingSkillsArray() {
        val result = SkillsClient.parseCatalogJson(JSONObject("""{"ok":true,"plan_tier":"free"}"""))
        assertTrue(result.skills.isEmpty())
        assertEquals("free", result.planTier)
        assertEquals(0, result.count)
    }

    @Test
    fun parseCatalogJsonSkipsRowsWithoutId() {
        val json = JSONObject(
            """{"ok":true,"plan_tier":"pro","skills":[{"name":"No id here"},{"id":"scheduling","name":"Scheduling","tier":"free","installed":false}]}"""
        )
        val result = SkillsClient.parseCatalogJson(json)
        assertEquals(1, result.skills.size)
        assertEquals("scheduling", result.skills[0].id)
    }

    @Test
    fun parseInstallJsonSuccess() {
        val json = JSONObject(
            """
            {
              "ok": true, "skill_id": "documents", "deduped": false,
              "skill": {"id": "documents", "name": "Documents", "description": "Docs padho aur likho", "category": "productivity", "tier": "pro", "installed": true}
            }
            """.trimIndent()
        )
        val out = SkillsClient.parseInstallJson(json)
        assertTrue(out is SkillsClient.InstallOutcome.Ok)
        val ok = out as SkillsClient.InstallOutcome.Ok
        assertFalse(ok.deduped)
        assertEquals("documents", ok.skill.id)
        assertTrue(ok.skill.installed)
    }

    @Test
    fun parseInstallJsonDedupedRepeatIsOkNotError() {
        val json = JSONObject(
            """
            {
              "ok": true, "skill_id": "email-management", "deduped": true,
              "skill": {"id": "email-management", "name": "Email Management", "tier": "free", "installed": true}
            }
            """.trimIndent()
        )
        val out = SkillsClient.parseInstallJson(json)
        assertTrue(out is SkillsClient.InstallOutcome.Ok)
        assertTrue((out as SkillsClient.InstallOutcome.Ok).deduped)
    }

    @Test
    fun parseInstallJsonTier403() {
        val json = JSONObject(
            """{"ok":false,"error":"skill_tier_required","required_tier":"pro","plan_tier":"free"}"""
        )
        val out = SkillsClient.parseInstallJson(json)
        assertTrue(out is SkillsClient.InstallOutcome.TierRequired)
        val tier = out as SkillsClient.InstallOutcome.TierRequired
        assertEquals("pro", tier.requiredTier)
        assertEquals("free", tier.planTier)
    }

    @Test
    fun parseInstallJsonCap403() {
        val json = JSONObject(
            """{"ok":false,"error":"skills_install_cap","plan_tier":"free","installed":1,"cap":1}"""
        )
        val out = SkillsClient.parseInstallJson(json)
        assertTrue(out is SkillsClient.InstallOutcome.CapReached)
        val cap = out as SkillsClient.InstallOutcome.CapReached
        assertEquals("free", cap.planTier)
        assertEquals(1, cap.installed)
        assertEquals(1, cap.cap)
    }

    @Test
    fun parseInstallJsonNotFoundIsErr() {
        val json = JSONObject("""{"ok":false,"error":"skill_not_found"}""")
        val out = SkillsClient.parseInstallJson(json)
        assertTrue(out is SkillsClient.InstallOutcome.Err)
        assertTrue((out as SkillsClient.InstallOutcome.Err).message.contains("nahi mili"))
    }

    @Test
    fun parseInstallJsonUnknownErrorIsErr() {
        val json = JSONObject("""{"ok":false,"error":"bad_request"}""")
        val out = SkillsClient.parseInstallJson(json)
        assertTrue(out is SkillsClient.InstallOutcome.Err)
    }

    @Test
    fun payloadRawMatchesTheServerShape() {
        // Must be byte-identical to hub.ts:
        // `catalog:${tenantId}` and
        // `catalog-install:${tenantId}:${skillId}`.
        assertEquals("catalog:", SkillsClient.catalogPayloadRaw(""))
        assertEquals("catalog:lic-abc", SkillsClient.catalogPayloadRaw("lic-abc"))
        assertEquals(
            "catalog-install:lic-abc:documents",
            SkillsClient.installPayloadRaw("lic-abc", "documents")
        )
    }
}
