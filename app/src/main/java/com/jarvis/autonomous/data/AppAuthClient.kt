package com.jarvis.autonomous.data

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.json.JSONObject

/**
 * R4.4 (M-I-18) — AppAuth-Android plumbing (OAuth token vault).
 *
 * What this is: the reference OAuth 2.0 / OIDC client for native
 * apps (RFC 8252, net.openid:appauth 0.11.1, pinned in
 * app/build.gradle.kts) + a secure token vault. Authorization
 * request building, authorization-code exchange (PKCE, public
 * client — no client secret on the phone, ever), AuthState
 * persistence in EncryptedSharedPreferences (the same MasterKey
 * AES256_GCM pattern as PairingManager / AgentSecrets).
 *
 * What this is NOT (honest scope): no provider is configured.
 * Provider #2 stays DEFER — the user gives a provider name first;
 * no issuer, client_id, redirect URI or scope is invented here.
 * The Google flow stays hub-mediated (GoogleClient → hub D1
 * tokens); nothing in this file is wired to Google. With no
 * provider configured, `configuredProviders()` is empty and any
 * UI that asks gets "koi provider configure nahi hai" — never a
 * fake login screen. When the user names a provider, the only
 * missing piece is one OAuthProviderConfig + the consent call;
 * the request / exchange / vault below are already real.
 *
 * The vault is behind the AppAuthTokenStore interface so the pure
 * logic (config validation, AuthState round-trip, store/load/
 * clear) is unit-tested on the JVM with InMemoryTokenStore —
 * EncryptedSharedPreferences needs a real device Keystore and
 * cannot run under Robolectric reliably.
 */
data class OAuthProviderConfig(
    /** Stable key for the vault, e.g. "provider2". Never a secret. */
    val providerKey: String,
    val issuer: String,
    val clientId: String,
    val redirectUri: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val scopes: List<String> = emptyList()
) {
    /** Null = valid; otherwise a one-line reason (never throws). */
    fun validate(): String? {
        if (providerKey.isBlank()) return "providerKey khaali hai."
        if (issuer.isBlank()) return "issuer khaali hai."
        if (clientId.isBlank()) return "clientId khaali hai."
        if (redirectUri.isBlank()) return "redirectUri khaali hai."
        if (!redirectUri.contains("://") && !redirectUri.contains(":")) {
            return "redirectUri ek URI honi chahiye (scheme:/path)."
        }
        if (authorizationEndpoint.isBlank()) return "authorizationEndpoint khaali hai."
        if (tokenEndpoint.isBlank()) return "tokenEndpoint khaali hai."
        return null
    }

    fun serviceConfiguration(): AuthorizationServiceConfiguration {
        return AuthorizationServiceConfiguration(
            Uri.parse(authorizationEndpoint),
            Uri.parse(tokenEndpoint)
        )
    }
}

/** Token vault backend — EncryptedSharedPreferences on device, in-memory in tests. */
interface AppAuthTokenStore {
    fun save(providerKey: String, authStateJson: String)
    fun load(providerKey: String): String?
    fun clear(providerKey: String)
    fun keys(): Set<String>
}

/** Production vault: EncryptedSharedPreferences (MasterKey AES256_GCM). */
class EncryptedPrefsTokenStore(context: Context) : AppAuthTokenStore {
    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "jarvis_appauth_vault",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun save(providerKey: String, authStateJson: String) {
        prefs.edit().putString(providerKey, authStateJson).apply()
    }

    override fun load(providerKey: String): String? =
        prefs.getString(providerKey, null)?.takeIf { it.isNotBlank() }

    override fun clear(providerKey: String) {
        prefs.edit().remove(providerKey).apply()
    }

    override fun keys(): Set<String> = prefs.all.keys.toSet()
}

/** Test double: plain in-memory map. Never used in production. */
class InMemoryTokenStore : AppAuthTokenStore {
    private val map = mutableMapOf<String, String>()
    override fun save(providerKey: String, authStateJson: String) { map[providerKey] = authStateJson }
    override fun load(providerKey: String): String? = map[providerKey]
    override fun clear(providerKey: String) { map.remove(providerKey) }
    override fun keys(): Set<String> = map.keys.toSet()
}

class AppAuthClient(private val store: AppAuthTokenStore) {

    /** Provider keys with a stored AuthState (empty = none configured). */
    fun configuredProviders(): Set<String> = store.keys()

    fun isConfigured(providerKey: String): Boolean = store.load(providerKey) != null

    /** Stored AuthState for a provider, null = none / corrupt (corrupt = cleared). */
    fun loadAuthState(providerKey: String): AuthState? {
        val json = store.load(providerKey) ?: return null
        return runCatching { AuthState.jsonDeserialize(JSONObject(json)) }.getOrNull()
            ?: run {
                // A corrupt vault entry must not wedge the provider
                // forever — drop it and report "not configured".
                runCatching { store.clear(providerKey) }
                null
            }
    }

    fun clearAuthState(providerKey: String) = store.clear(providerKey)

    /**
     * Build the authorization request (PKCE is automatic — AppAuth
     * generates the S256 challenge). Null when the config is
     * invalid (validate() tells why). No network, no browser — the
     * caller starts it with AuthorizationService.getAuthorizationRequestIntent.
     */
    fun buildAuthorizationRequest(config: OAuthProviderConfig): AuthorizationRequest? {
        if (config.validate() != null) return null
        return AuthorizationRequest.Builder(
            config.serviceConfiguration(),
            config.clientId,
            ResponseTypeValues.CODE,
            Uri.parse(config.redirectUri)
        )
            .setScope(config.scopes.joinToString(" "))
            // PKCE S256 challenge (AppAuth generates one by default;
            // setting it explicitly keeps the guarantee visible and
            // testable regardless of the library default).
            .setCodeVerifier(CodeVerifierUtil.generateRandomCodeVerifier())
            .build()
    }

    /**
     * Build the code-exchange token request (pure construction —
     * unit-tested). Null when the config is invalid or the
     * authorization response carries no code / verifier.
     */
    fun buildTokenExchangeRequest(
        config: OAuthProviderConfig,
        authResponse: AuthorizationResponse
    ): TokenRequest? {
        if (config.validate() != null) return null
        val code = authResponse.authorizationCode ?: return null
        val verifier = authResponse.request.codeVerifier ?: return null
        return TokenRequest.Builder(config.serviceConfiguration(), config.clientId)
            .setGrantType(GrantTypeValues.AUTHORIZATION_CODE)
            .setAuthorizationCode(code)
            .setRedirectUri(Uri.parse(config.redirectUri))
            .setCodeVerifier(verifier)
            .build()
    }

    /** Standard AuthorizationService (default AppAuth configuration). */
    fun createService(context: Context): AuthorizationService {
        return AuthorizationService(context, AppAuthConfiguration.Builder().build())
    }

    /**
     * Perform the code exchange (public client — no client secret;
     * PKCE is the authentication). On success the AuthState is
     * stored in the vault; on failure nothing is stored and the
     * callback gets (false, reason). The caller owns the
     * AuthorizationService lifecycle (create + dispose).
     */
    fun performCodeExchange(
        service: AuthorizationService,
        config: OAuthProviderConfig,
        authResponse: AuthorizationResponse,
        onResult: (Boolean, String?) -> Unit
    ) {
        val tokenRequest = buildTokenExchangeRequest(config, authResponse)
        if (tokenRequest == null) {
            onResult(false, config.validate() ?: "authorization response me code nahi mila.")
            return
        }
        service.performTokenRequest(tokenRequest, null) { resp: TokenResponse?, ex: AuthorizationException? ->
            if (resp == null) {
                onResult(false, (ex?.message ?: "token exchange fail hua.").take(200))
                return@performTokenRequest
            }
            val state = AuthState(authResponse, resp, ex)
            runCatching { store.save(config.providerKey, state.jsonSerializeString()) }
                .onSuccess { onResult(true, null) }
                .onFailure { onResult(false, "Token mila par vault me save nahi hua — dobara try karo.") }
        }
    }
}
