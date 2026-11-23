package com.jarvis.autonomous.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jarvis.autonomous.ui.LicencePrefs

/**
 * U10 (R1 audit follow-up, B8): the licence key now lives in
 * EncryptedSharedPreferences ("jarvis_licence_secrets", same
 * MasterKey AES256_GCM pattern as AgentSecrets / PairingManager),
 * never in plain SharedPreferences. 1.14.0 kept it in plain prefs
 * ("jarvis_agent_prefs" / "licence_key") while the pairing key was
 * encrypted — that gap is what this closes.
 *
 * One-time migration: the first [get] after upgrade reads a plain
 * value left by 1.14.0 (if any), writes it encrypted, deletes the
 * plain copy, and returns it — an already-activated user never has
 * to re-activate. [put] also deletes any plain copy left behind.
 * Only the KEY migrates: licence_activated and
 * global_learning_opt_out stay in plain prefs (flags, not secrets).
 *
 * Degraded read: if the encrypted store itself cannot be created
 * (Keystore failure), [get] falls back to the legacy plain value
 * rather than pretending there is no licence — the user is never
 * locked out by a store failure, and the plain copy is still
 * deleted on the next successful encrypted write.
 *
 * No key value is ever logged or put in diagnostics — callers mask
 * it (see buildDiagnosticsReport in MainActivity).
 */
object LicenceKeyStore {
    private const val SECRETS_FILE = "jarvis_licence_secrets"

    private fun encryptedPrefs(context: Context): SharedPreferences {
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

    private fun readPlainLegacy(context: Context): String = runCatching {
        context.getSharedPreferences(LicencePrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LicencePrefs.KEY_LICENCE, "") ?: ""
    }.getOrDefault("")

    private fun deletePlainLegacy(context: Context) {
        runCatching {
            context.getSharedPreferences(LicencePrefs.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(LicencePrefs.KEY_LICENCE).commit()
        }
    }

    /**
     * Encrypted value; migrates a plain-prefs value once, then
     * deletes it. Returns "" when no key was ever saved.
     */
    fun get(context: Context): String {
        val encrypted = runCatching { encryptedPrefs(context) }.getOrNull()
            ?: return readPlainLegacy(context)
        val stored = runCatching {
            encrypted.getString(LicencePrefs.KEY_LICENCE, "") ?: ""
        }.getOrDefault("")
        if (stored.isNotBlank()) return stored
        // One-time migration from the 1.14.0 plain location.
        val legacy = readPlainLegacy(context)
        if (legacy.isBlank()) return ""
        val ok = runCatching {
            encrypted.edit().putString(LicencePrefs.KEY_LICENCE, legacy).commit()
        }.getOrDefault(false)
        if (ok) {
            deletePlainLegacy(context)
            return legacy
        }
        // Encrypted write failed: return the legacy value so the
        // user is not locked out, and leave the plain copy in place
        // for the next attempt (never silently lose the key).
        return legacy
    }

    fun has(context: Context): Boolean = get(context).isNotBlank()

    /**
     * Saves encrypted (commit + read-back, C-B15 rule). Returns
     * false when the save did not stick — callers must surface
     * that, never show "Activated" on a failed save.
     */
    fun put(context: Context, key: String): Boolean {
        return runCatching {
            val encrypted = encryptedPrefs(context)
            val ok = encrypted.edit().putString(LicencePrefs.KEY_LICENCE, key).commit()
            if (ok) deletePlainLegacy(context)
            ok && encrypted.getString(LicencePrefs.KEY_LICENCE, "") == key
        }.getOrDefault(false)
    }

    /** Clears both stores (reset path). The plain copy should already be gone. */
    fun clear(context: Context) {
        runCatching {
            encryptedPrefs(context).edit().remove(LicencePrefs.KEY_LICENCE).commit()
        }
        deletePlainLegacy(context)
    }
}
