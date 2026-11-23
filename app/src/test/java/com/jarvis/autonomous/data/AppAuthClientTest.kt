package com.jarvis.autonomous.data

import android.net.Uri
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.GrantTypeValues
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R4.4 (M-I-18) — AppAuth plumbing tests. Robolectric (android.net.Uri
 * is a stub on the plain JVM harness). No network, no provider: the
 * tests prove the plumbing is real — config validation, authorization
 * request + token-exchange request construction (PKCE), and the
 * AuthState vault round-trip — and that an unconfigured provider
 * reports "not configured" instead of faking a login.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppAuthClientTest {

    private fun validConfig() = OAuthProviderConfig(
        providerKey = "provider2",
        issuer = "https://accounts.example.test",
        clientId = "test-client-id",
        redirectUri = "com.jarvis.autonomous.oauth:/oauth2redirect",
        authorizationEndpoint = "https://accounts.example.test/o/oauth2/v2/auth",
        tokenEndpoint = "https://accounts.example.test/token",
        scopes = listOf("openid", "email")
    )

    @Test
    fun `valid config validates clean`() {
        assertNull(validConfig().validate())
    }

    @Test
    fun `each blank field fails validation with a reason`() {
        assertNotNull(validConfig().copy(providerKey = "").validate())
        assertNotNull(validConfig().copy(issuer = "").validate())
        assertNotNull(validConfig().copy(clientId = "").validate())
        assertNotNull(validConfig().copy(redirectUri = "").validate())
        assertNotNull(validConfig().copy(authorizationEndpoint = "").validate())
        assertNotNull(validConfig().copy(tokenEndpoint = "").validate())
        assertTrue(validConfig().copy(redirectUri = "not-a-uri").validate()!!.contains("redirectUri"))
    }

    @Test
    fun `no provider configured reports empty`() {
        val client = AppAuthClient(InMemoryTokenStore())
        assertTrue(client.configuredProviders().isEmpty())
        assertFalse(client.isConfigured("provider2"))
        assertNull(client.loadAuthState("provider2"))
    }

    @Test
    fun `authorization request builds with PKCE verifier`() {
        val client = AppAuthClient(InMemoryTokenStore())
        val req = client.buildAuthorizationRequest(validConfig())
        assertNotNull(req)
        assertEquals("test-client-id", req!!.clientId)
        assertEquals(
            Uri.parse("com.jarvis.autonomous.oauth:/oauth2redirect"),
            req.redirectUri
        )
        assertEquals("code", req.responseType)
        assertTrue(req.scope!!.contains("openid"))
        // AppAuth generates the S256 PKCE challenge automatically.
        assertNotNull(req.codeVerifier)
        assertTrue(req.codeVerifier!!.isNotBlank())
        assertEquals(
            "https://accounts.example.test/o/oauth2/v2/auth",
            req.configuration.authorizationEndpoint.toString()
        )
    }

    @Test
    fun `authorization request is null for an invalid config`() {
        val client = AppAuthClient(InMemoryTokenStore())
        assertNull(client.buildAuthorizationRequest(validConfig().copy(clientId = "")))
        assertNull(client.buildAuthorizationRequest(validConfig().copy(issuer = "")))
    }

    @Test
    fun `token exchange request carries code, verifier and grant type`() {
        val client = AppAuthClient(InMemoryTokenStore())
        val config = validConfig()
        val authReq = client.buildAuthorizationRequest(config)!!
        val authResp = AuthorizationResponse.Builder(authReq)
            .setAuthorizationCode("auth-code-123")
            .build()
        val tokenReq = client.buildTokenExchangeRequest(config, authResp)
        assertNotNull(tokenReq)
        assertEquals(GrantTypeValues.AUTHORIZATION_CODE, tokenReq!!.grantType)
        assertEquals("auth-code-123", tokenReq.authorizationCode)
        assertEquals(authReq.codeVerifier, tokenReq.codeVerifier)
        assertEquals(
            Uri.parse("com.jarvis.autonomous.oauth:/oauth2redirect"),
            tokenReq.redirectUri
        )
    }

    @Test
    fun `token exchange request is null without a code`() {
        val client = AppAuthClient(InMemoryTokenStore())
        val config = validConfig()
        val authReq = client.buildAuthorizationRequest(config)!!
        // No authorization code set on the response.
        val authResp = AuthorizationResponse.Builder(authReq).build()
        assertNull(client.buildTokenExchangeRequest(config, authResp))
    }

    @Test
    fun `vault round-trips an AuthState`() {
        val store = InMemoryTokenStore()
        val client = AppAuthClient(store)
        val config = validConfig()
        val state = AuthState(config.serviceConfiguration())
        store.save("provider2", state.jsonSerializeString())
        assertTrue(client.isConfigured("provider2"))
        assertEquals(setOf("provider2"), client.configuredProviders())
        val loaded = client.loadAuthState("provider2")
        assertNotNull(loaded)
        assertEquals(
            "https://accounts.example.test/o/oauth2/v2/auth",
            loaded!!.authorizationServiceConfiguration!!.authorizationEndpoint.toString()
        )
        assertEquals(
            "https://accounts.example.test/token",
            loaded.authorizationServiceConfiguration!!.tokenEndpoint.toString()
        )
    }

    @Test
    fun `clear removes the provider`() {
        val store = InMemoryTokenStore()
        val client = AppAuthClient(store)
        store.save("provider2", AuthState(validConfig().serviceConfiguration()).jsonSerializeString())
        assertTrue(client.isConfigured("provider2"))
        client.clearAuthState("provider2")
        assertFalse(client.isConfigured("provider2"))
        assertNull(client.loadAuthState("provider2"))
    }

    @Test
    fun `corrupt vault entry loads as null and is dropped`() {
        val store = InMemoryTokenStore()
        val client = AppAuthClient(store)
        store.save("provider2", "{not json")
        assertNull(client.loadAuthState("provider2"))
        assertFalse(client.isConfigured("provider2"))
    }

    @Test
    fun `in-memory store save load clear keys`() {
        val store = InMemoryTokenStore()
        store.save("a", "x")
        store.save("b", "y")
        assertEquals("x", store.load("a"))
        assertEquals(setOf("a", "b"), store.keys())
        store.clear("a")
        assertNull(store.load("a"))
        assertEquals(setOf("b"), store.keys())
    }
}
