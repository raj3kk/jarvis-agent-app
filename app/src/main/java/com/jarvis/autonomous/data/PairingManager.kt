package com.jarvis.autonomous.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Control-plane pairing (Phase 2, stage 2b-1) — Ed25519 keypair + hub
 * register. Hub: https://omnijarvis-hub.rajraj3kk.workers.dev
 * (routes /api/pairing/init + /api/pairing/register, server repo
 * raj3kk/omnijarvis-server).
 *
 * Key storage:
 * - API 31+: Android Keystore (non-exportable private key).
 * - API 26-30 (or Keystore failure): platform Ed25519 keypair, private
 *   key PKCS8 Base64 in EncryptedSharedPreferences
 *   ("jarvis_pairing_secrets", same MasterKey pattern as AgentSecrets).
 *   Weaker than Keystore, but functional — the UI names which store is
 *   in use, never pretends otherwise.
 * - The pending pairing TOKEN lives in the same encrypted prefs until
 *   register consumes it (one-time, 5-min). Token NEVER in plain prefs,
 *   NEVER logged, NEVER shown in UI.
 * - The HUB PUBLIC key + key_id live in the same encrypted prefs
 *   (M-4 §4, hub 3b.1+): delivered by /api/pairing/register (200) and
 *   by HELLO_ACK bootstrap (first-seen only — a new key_id from
 *   HELLO_ACK is NEVER accepted; rotation is re-pair-only). The hub
 *   key is PUBLIC (base64 raw 32-byte) — storing it is safe; the
 *   private key never leaves the hub. Unpair clears it.
 * - Seen hub nonces (M-4 §3.5) live in the same prefs as a bounded
 *   set (max 200, 10-min TTL, pruned on read/write) — a persisted
 *   store, not memory-only, so a replayed EXECUTE_STEP cannot slip
 *   through an FGS restart inside the 5-min window. Nonce VALUES are
 *   never logged or shown in diagnostics (count only).
 * - The PUBLIC key is sent to the hub as STANDARD base64 of the RAW
 *   32-byte Ed25519 key (server importKey("raw") — NOT the 44-byte
 *   SPKI). Android's PublicKey.encoded is the SPKI, so the last 32
 *   bytes are taken (SPKI prefix is a fixed 12 bytes for Ed25519).
 *
 * Errors are typed (PairingError) — the UI maps each to one honest
 * Hinglish line; no invented wording, no fake "paired" state.
 * D1 migration (2026-11-21): licence check is D1-backed; the hub never
 * answers 503 config_missing anymore (B-A closed by Supabase removal).
 * The 503 mapping below is a defensive branch only.
 */
object PairingManager {

    const val HUB_URL = "https://omnijarvis-hub.rajraj3kk.workers.dev"

    private const val KEYSTORE_ALIAS = "jarvis_pairing_ed25519"
    private const val SECRETS_FILE = "jarvis_pairing_secrets"
    private const val KEY_PENDING_TOKEN = "pairing_pending_token"
    private const val KEY_PENDING_EXPIRES_AT = "pairing_pending_expires_at"
    private const val KEY_FALLBACK_PRIVATE_B64 = "pairing_private_pkcs8_b64"
    private const val KEY_FALLBACK_PUBLIC_SPKI_B64 = "pairing_public_spki_b64"
    // M-4 (hub 3b.1+): hub public key + key_id (register response /
    // HELLO_ACK bootstrap) + seen-nonce store (replay protection).
    private const val KEY_HUB_PUBLIC_B64 = "hub_public_key_b64"
    private const val KEY_HUB_KEY_ID = "hub_key_id"
    private const val KEY_HUB_SEEN_NONCES = "hub_seen_nonces"
    private const val MAX_HUB_NONCES = 200
    private const val HUB_NONCE_TTL_MS = 10 * 60 * 1000L

    enum class KeyStoreKind { ANDROID_KEYSTORE, ENCRYPTED_PREFS }

    enum class PairingError {
        CONFIG_MISSING, LICENCE_INVALID, RATE_LIMITED, TOKEN_INVALID,
        BAD_REQUEST, NETWORK, SERVER, NO_PENDING, NO_LICENCE, KEY_ERROR
    }

    sealed interface InitResult {
        data class Ok(val code: String, val expiresAtMs: Long) : InitResult
        data class Err(val kind: PairingError) : InitResult
    }

    sealed interface RegisterResult {
        data object Ok : RegisterResult
        data class Err(val kind: PairingError) : RegisterResult
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun secretsPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SECRETS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ---- Keypair ----

    /** Get-or-create the device Ed25519 keypair. Never throws for a
     *  missing key — throws only when NO store can produce one. */
    fun ensureKeyPair(context: Context): Pair<KeyPair, KeyStoreKind> {
        // 1. Keystore (API 31+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { keystoreKeyPair() }.onSuccess { return it to KeyStoreKind.ANDROID_KEYSTORE }
        }
        // 2. Fallback: encrypted-prefs PKCS8 (API 26-30, or Keystore failed).
        runCatching { fallbackKeyPair(context) }.onSuccess { return it to KeyStoreKind.ENCRYPTED_PREFS }
        throw IllegalStateException("Ed25519 keypair banaya nahi ja saka (dono stores fail)")
    }

    fun keyStoreKind(context: Context): KeyStoreKind = ensureKeyPair(context).second

    private fun keystoreKeyPair(): KeyPair {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (entry != null) return KeyPair(entry.certificate.publicKey, entry.privateKey)
        // Generate. Ed25519 is a pure signature (no digest) — try
        // DIGEST_SHA512 first (accepted on API 31+), DIGEST_NONE on
        // failure. Each retry re-reads the entry above, so a key
        // created by an earlier attempt is returned, not regenerated.
        val firstError = runCatching {
            generateKeystoreKey(KeyProperties.DIGEST_SHA512)
        }.exceptionOrNull()
        if (firstError == null) return keystoreKeyPair()
        val secondError = runCatching {
            generateKeystoreKey(KeyProperties.DIGEST_NONE)
        }.exceptionOrNull()
        if (secondError == null) return keystoreKeyPair()
        throw firstError
    }

    private fun generateKeystoreKey(digest: String) {
        // NOTE: KeyProperties has NO KEY_ALGORITHM_ED25519 constant
        // (compileSdk 34) — the algorithm is the plain string
        // "Ed25519" (Keystore supports it from API 31).
        val kpg = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).setDigests(digest).build()
        )
        kpg.generateKeyPair()
    }

    private fun fallbackKeyPair(context: Context): KeyPair {
        val prefs = secretsPrefs(context)
        val privB64 = prefs.getString(KEY_FALLBACK_PRIVATE_B64, null)
        val pubB64 = prefs.getString(KEY_FALLBACK_PUBLIC_SPKI_B64, null)
        if (!privB64.isNullOrBlank() && !pubB64.isNullOrBlank()) {
            val kf = KeyFactory.getInstance("Ed25519")
            val priv = kf.generatePrivate(
                PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP))
            )
            val pub = kf.generatePublic(
                X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP))
            )
            return KeyPair(pub, priv)
        }
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        prefs.edit()
            .putString(KEY_FALLBACK_PRIVATE_B64, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString(KEY_FALLBACK_PUBLIC_SPKI_B64, Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .apply()
        return kp
    }

    /** Raw 32-byte Ed25519 public key (server importKey("raw")). */
    fun rawPublicKeyBytes(publicKey: PublicKey): ByteArray {
        val spki = publicKey.encoded
        return when {
            spki.size == 32 -> spki
            spki.size == 44 -> spki.copyOfRange(spki.size - 32, spki.size)
            else -> throw IllegalStateException("Public key format pehchana nahi gaya (${spki.size} bytes)")
        }
    }

    fun publicKeyB64(context: Context): String {
        val (kp, _) = ensureKeyPair(context)
        return Base64.encodeToString(rawPublicKeyBytes(kp.public), Base64.NO_WRAP)
    }

    /** Sign for 2b-2 (WS HELLO / STEP_RESULT). Proved in 2b-1 by the
     *  UI self-test (sign -> verify with the same public key). */
    fun sign(context: Context, data: ByteArray): ByteArray {
        val (kp, _) = ensureKeyPair(context)
        return signWith(kp.private, data)
    }

    fun signWith(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    /** Self-test: sign a fixed probe, verify with the public key. */
    fun selfTestSign(context: Context): Boolean {
        return runCatching {
            val (kp, _) = ensureKeyPair(context)
            val probe = "jarvis-pairing-selftest".toByteArray(Charsets.UTF_8)
            val sigBytes = signWith(kp.private, probe)
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(kp.public)
            verifier.update(probe)
            verifier.verify(sigBytes)
        }.getOrDefault(false)
    }

    /** Device id — SAME source as the licence binding
     *  (LicenceScreen.deviceId): Settings.Secure.ANDROID_ID. */
    fun deviceId(context: Context): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: ""
    }

    // ---- Pending token (encrypted prefs only) ----

    fun pendingToken(context: Context): Pair<String, Long>? {
        val prefs = secretsPrefs(context)
        val token = prefs.getString(KEY_PENDING_TOKEN, null) ?: return null
        val expiresAt = prefs.getLong(KEY_PENDING_EXPIRES_AT, 0L)
        if (token.isBlank() || expiresAt <= 0L) return null
        return token to expiresAt
    }

    private fun storePendingToken(context: Context, token: String, expiresAtMs: Long) {
        secretsPrefs(context).edit()
            .putString(KEY_PENDING_TOKEN, token)
            .putLong(KEY_PENDING_EXPIRES_AT, expiresAtMs)
            .apply()
    }

    fun clearPendingToken(context: Context) {
        secretsPrefs(context).edit()
            .remove(KEY_PENDING_TOKEN)
            .remove(KEY_PENDING_EXPIRES_AT)
            .apply()
    }

    // ---- Hub public key (M-4 §4 — register response / HELLO_ACK) ----

    /** Stored hub public key (base64 raw 32-byte), null = never received. */
    fun hubPublicKeyB64(context: Context): String? {
        return secretsPrefs(context).getString(KEY_HUB_PUBLIC_B64, null)?.takeIf { it.isNotBlank() }
    }

    /** Stored hub key id (e.g. "hub-1"), null = never received. */
    fun hubKeyId(context: Context): String? {
        return secretsPrefs(context).getString(KEY_HUB_KEY_ID, null)?.takeIf { it.isNotBlank() }
    }

    /** Store the hub key (register response is authoritative — it
     *  overwrites a HELLO_ACK bootstrap key on re-pair). */
    fun storeHubPublicKey(context: Context, publicKeyB64: String, keyId: String) {
        secretsPrefs(context).edit()
            .putString(KEY_HUB_PUBLIC_B64, publicKeyB64)
            .putString(KEY_HUB_KEY_ID, keyId)
            .apply()
    }

    /** Clear the hub key (unpair — re-pair brings a new one). */
    fun clearHubPublicKey(context: Context) {
        secretsPrefs(context).edit()
            .remove(KEY_HUB_PUBLIC_B64)
            .remove(KEY_HUB_KEY_ID)
            .apply()
    }

    // ---- Seen hub nonces (M-4 §3.5 — replay protection, persisted) ----

    /** True when this hub nonce was already seen (replay). Entries are
     *  "nonce:seen_at_ms" strings; >10-min entries are pruned here. */
    fun isHubNonceSeen(context: Context, nonce: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        return prunedHubNonces(context, nowMs).any { it.substringBefore(":") == nonce }
    }

    /** Record a hub nonce as seen (call only after the signature
     *  verified — a failed verify must not burn the nonce). Bounded
     *  to 200 entries (oldest dropped first). */
    fun markHubNonceSeen(context: Context, nonce: String, nowMs: Long = System.currentTimeMillis()) {
        val fresh = prunedHubNonces(context, nowMs).toMutableList()
        fresh.add("$nonce:$nowMs")
        while (fresh.size > MAX_HUB_NONCES) fresh.removeAt(0)
        secretsPrefs(context).edit().putStringSet(KEY_HUB_SEEN_NONCES, fresh.toSet()).apply()
    }

    /** Fresh (<=10-min) nonce entries; prunes stale ones (writes back
     *  only when something was pruned). Values never leave this file. */
    private fun prunedHubNonces(context: Context, nowMs: Long): List<String> {
        val prefs = secretsPrefs(context)
        val stored = prefs.getStringSet(KEY_HUB_SEEN_NONCES, emptySet()) ?: emptySet()
        val fresh = stored.mapNotNull { entry ->
            val at = entry.substringAfter(":", "").toLongOrNull() ?: return@mapNotNull null
            if (nowMs - at <= HUB_NONCE_TTL_MS) entry else null
        }
        if (fresh.size != stored.size) {
            prefs.edit().putStringSet(KEY_HUB_SEEN_NONCES, fresh.toSet()).apply()
        }
        return fresh
    }

    // ---- Hub calls (blocking — call from Dispatchers.IO) ----

    /** POST /api/pairing/init {device_id, licence_key}. */
    fun initPairing(context: Context, deviceId: String, licenceKey: String): InitResult {
        if (licenceKey.isBlank()) return InitResult.Err(PairingError.NO_LICENCE)
        val bodyJson = JSONObject()
            .put("device_id", deviceId)
            .put("licence_key", licenceKey)
            .toString()
        val request = Request.Builder()
            .url("$HUB_URL/api/pairing/init")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        val response = runCatching { httpClient.newCall(request).execute() }
            .getOrElse { return InitResult.Err(PairingError.NETWORK) }
        response.use { resp ->
            val text = runCatching { resp.body?.string() ?: "" }.getOrDefault("")
            return when (resp.code) {
                200 -> {
                    val json = runCatching { JSONObject(text) }.getOrNull()
                        ?: return InitResult.Err(PairingError.SERVER)
                    val token = json.optString("token", "")
                    val code = json.optString("code", "")
                    val expiresAt = json.optLong("expires_at", 0L)
                    if (token.isBlank() || code.isBlank() || expiresAt <= 0L) {
                        return InitResult.Err(PairingError.SERVER)
                    }
                    storePendingToken(context, token, expiresAt)
                    InitResult.Ok(code, expiresAt)
                }
                403 -> InitResult.Err(PairingError.LICENCE_INVALID)
                429 -> InitResult.Err(PairingError.RATE_LIMITED)
                503 -> InitResult.Err(PairingError.CONFIG_MISSING)
                400 -> InitResult.Err(PairingError.BAD_REQUEST)
                else -> InitResult.Err(PairingError.SERVER)
            }
        }
    }

    /** POST /api/pairing/register {token, device_id, public_key}. */
    fun registerPairing(context: Context, deviceId: String): RegisterResult {
        val (token, _) = pendingToken(context)
            ?: return RegisterResult.Err(PairingError.NO_PENDING)
        val publicKey = runCatching { publicKeyB64(context) }
            .getOrElse { return RegisterResult.Err(PairingError.KEY_ERROR) }
        val bodyJson = JSONObject()
            .put("token", token)
            .put("device_id", deviceId)
            .put("public_key", publicKey)
            .toString()
        val request = Request.Builder()
            .url("$HUB_URL/api/pairing/register")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        val response = runCatching { httpClient.newCall(request).execute() }
            .getOrElse { return RegisterResult.Err(PairingError.NETWORK) }
        response.use { resp ->
            val text = runCatching { resp.body?.string() ?: "" }.getOrDefault("")
            return when (resp.code) {
                200 -> {
                    clearPendingToken(context)
                    // M-4 §4.5: the register response carries the hub
                    // public key + key_id (authoritative — overwrites
                    // any HELLO_ACK bootstrap key). Old apps ignore
                    // the extra fields; the key is PUBLIC (base64 raw
                    // 32-byte), so storing it is safe.
                    runCatching {
                        val json = JSONObject(text)
                        val hubKey = json.optString("hub_public_key", "")
                        val hubKeyId = json.optString("key_id", "")
                        if (hubKey.isNotBlank() && hubKeyId.isNotBlank()) {
                            storeHubPublicKey(context, hubKey, hubKeyId)
                        }
                    }
                    RegisterResult.Ok
                }
                403 -> {
                    // One-time token is spent even on failure.
                    clearPendingToken(context)
                    RegisterResult.Err(PairingError.TOKEN_INVALID)
                }
                429 -> RegisterResult.Err(PairingError.RATE_LIMITED)
                400 -> RegisterResult.Err(PairingError.BAD_REQUEST)
                else -> RegisterResult.Err(PairingError.SERVER)
            }
        }
    }
}
