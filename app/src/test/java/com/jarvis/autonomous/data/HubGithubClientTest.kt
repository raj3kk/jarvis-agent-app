package com.jarvis.autonomous.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1 (1.16.0-PRO) — HubGithubClient pure parsing. Plain JVM —
 * no Android, no network. The network calls (pushProject / fetch /
 * escalate) need the hub + the admin token and are covered by the
 * phone tests (E-T6) instead.
 */
class HubGithubClientTest {

    @Test
    fun pushOkParsesShaOwnerRepoBranch() {
        val r = HubGithubClient.parsePushJson(
            JSONObject(
                """{"ok":true,"sha":"abc123def456","owner":"raj3kk","repo":"demo","branch":"main"}"""
            ),
            200
        )
        assertTrue(r is HubGithubClient.PushResult.Ok)
        val ok = r as HubGithubClient.PushResult.Ok
        assertEquals("abc123def456", ok.sha)
        assertEquals("raj3kk", ok.owner)
        assertEquals("demo", ok.repo)
        assertEquals("main", ok.branch)
    }

    @Test
    fun pushOkDefaultsBranchToMain() {
        val r = HubGithubClient.parsePushJson(
            JSONObject("""{"ok":true,"sha":"abc","owner":"o","repo":"r"}"""),
            200
        )
        assertEquals("main", (r as HubGithubClient.PushResult.Ok).branch)
    }

    @Test
    fun pushMissingShaIsAnError() {
        val r = HubGithubClient.parsePushJson(
            JSONObject("""{"ok":true,"owner":"o","repo":"r"}"""),
            200
        )
        assertTrue(r is HubGithubClient.PushResult.Err)
    }

    @Test
    fun pushErrorBodyIsAnErrorWithDetail() {
        val r = HubGithubClient.parsePushJson(
            JSONObject("""{"ok":false,"error":"push_failed","detail":"repo locked"}"""),
            500
        )
        val err = r as HubGithubClient.PushResult.Err
        assertTrue(err.message.contains("repo locked"))
    }

    @Test
    fun pushGithubNotConfiguredMapsToNotConfigured() {
        val byError = HubGithubClient.parsePushJson(
            JSONObject("""{"ok":false,"error":"github_not_configured"}"""),
            200
        )
        assertTrue(byError is HubGithubClient.PushResult.NotConfigured)
        val by503 = HubGithubClient.parsePushJson(
            JSONObject("""{"ok":false,"error":"anything"}"""),
            503
        )
        assertTrue(by503 is HubGithubClient.PushResult.NotConfigured)
    }

    @Test
    fun fetchOkParsesStatusAndBody() {
        val r = HubGithubClient.parseFetchJson(
            JSONObject("""{"ok":true,"status":200,"body":{"id":12345,"name":"demo"}}"""),
            200
        )
        val ok = r as HubGithubClient.FetchResult.Ok
        assertEquals(200, ok.status)
        assertEquals(12345L, ok.body.optLong("id"))
        assertEquals("demo", ok.body.optString("name"))
    }

    @Test
    fun fetchMissingBodyIsAnError() {
        val r = HubGithubClient.parseFetchJson(
            JSONObject("""{"ok":true,"status":200}"""),
            200
        )
        assertTrue(r is HubGithubClient.FetchResult.Err)
    }

    @Test
    fun fetchGithubNotConfiguredMapsToNotConfigured() {
        val r = HubGithubClient.parseFetchJson(
            JSONObject("""{"ok":false,"error":"github_not_configured"}"""),
            200
        )
        assertTrue(r is HubGithubClient.FetchResult.NotConfigured)
    }

    @Test
    fun isNotConfiguredRecognisesBothSpellingsAnd503() {
        assertTrue(HubGithubClient.isNotConfigured(JSONObject("""{"error":"github_not_configured"}"""), 200))
        assertTrue(HubGithubClient.isNotConfigured(JSONObject("""{"error":"github_not_setup"}"""), 200))
        assertTrue(HubGithubClient.isNotConfigured(null, 503))
        assertTrue(HubGithubClient.isNotConfigured(JSONObject("""{"error":"other"}"""), 503))
        assertTrue(!HubGithubClient.isNotConfigured(JSONObject("""{"error":"other"}"""), 500))
        assertTrue(!HubGithubClient.isNotConfigured(null, 200))
    }
}
