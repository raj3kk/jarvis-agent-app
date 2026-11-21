package com.jarvis.autonomous.data

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.json.JSONObject

/**
 * Device-side verify for hub-signed EXECUTE_STEP (M-4, hub 3b.1+;
 * spec: phase-3/3c PEHLE_JAANCH_3c.md §6 / PEHLE_JAANCH_M4.md §3).
 *
 * Pure logic — no Android APIs (java.security + java.util.Base64 +
 * org.json only) and every side effect (stored key, nonce store)
 * arrives as a parameter, so the 7 checks are unit-testable without
 * a device. The caller (HubWsClient) supplies the stored hub key
 * from PairingManager and the persisted nonce store.
 *
 * Checks, in frozen order (§6.2) — ANY failure means REJECT, never
 * execute:
 *  1. key_id known (stored hub key's key_id matches; no key = reject)
 *  2. device_id == own deviceId
 *  3. ts fresh: |now - ts| <= 5 min (clock skew -> fail-closed)
 *  4. nonce never seen before (replay -> reject)
 *  5. step_ts fresh: now - step_ts <= 24h and step_ts <= now + 5 min
 *  6. sig verifies against the stored hub key over the canonical
 *     string `device_id + "\n" + nonce + "\n" + ts + "\n" + payload_raw`
 *  7. payload_raw parses and has kind + job_id
 *
 * On success the nonce is marked seen (only AFTER the signature
 * verified — a failed verify must not burn the nonce) and the step
 * parsed from payload_raw is returned. The `step` envelope field is
 * NEVER executed from: if it is present and disagrees with
 * payload_raw, that is a reject (payload_raw is the source of truth).
 */
object HubStepVerifier {

    /** ts freshness window (M-4 §3.4) — matches the device->hub window. */
    const val MAX_TS_SKEW_MS = 5 * 60 * 1000L

    /** step_ts freshness window (M-4 §3.4) — 24h offline queue. */
    const val MAX_STEP_AGE_MS = 24 * 60 * 60 * 1000L

    /** SPKI prefix for Ed25519 (12 bytes) — the inverse of
     *  PairingManager.rawPublicKeyBytes, which takes the last 32
     *  bytes of a 44-byte SPKI. */
    private const val SPKI_PREFIX_HEX = "302a300506032b6570032100"

    sealed interface Result {
        /** Verified — execute the step parsed from payload_raw. */
        data class Ok(val step: JSONObject) : Result

        /** Reject — never execute. `reason` is a stable code (no
         *  secret, no signature/nonce value — safe for diagnostics). */
        data class Reject(val reason: String) : Result
    }

    fun verify(
        envelope: JSONObject,
        deviceId: String,
        storedKeyB64: String?,
        storedKeyId: String?,
        nowMs: Long,
        isNonceSeen: (String) -> Boolean,
        markNonceSeen: (String) -> Unit,
    ): Result {
        // 1. key_id known.
        if (storedKeyB64.isNullOrBlank() || storedKeyId.isNullOrBlank()) {
            return Result.Reject("no_hub_key")
        }
        val keyId = envelope.optString("key_id", "")
        if (keyId != storedKeyId) return Result.Reject("unknown_key_id")
        // type guard (the caller dispatches on type already; defence in depth).
        if (envelope.optString("type", "") != "EXECUTE_STEP") return Result.Reject("bad_type")
        // 2. device_id match.
        val envelopeDeviceId = envelope.optString("device_id", "")
        if (envelopeDeviceId != deviceId) return Result.Reject("device_mismatch")
        // 3. ts fresh.
        val ts = envelope.optLong("ts", 0L)
        if (ts <= 0L || kotlin.math.abs(nowMs - ts) > MAX_TS_SKEW_MS) {
            return Result.Reject("ts_stale")
        }
        // 4. nonce never seen.
        val nonce = envelope.optString("nonce", "")
        if (nonce.isBlank()) return Result.Reject("bad_nonce")
        if (isNonceSeen(nonce)) return Result.Reject("nonce_replay")
        // 5. step_ts fresh.
        val stepTs = envelope.optLong("step_ts", 0L)
        if (stepTs <= 0L || nowMs - stepTs > MAX_STEP_AGE_MS || stepTs > nowMs + MAX_TS_SKEW_MS) {
            return Result.Reject("step_ts_stale")
        }
        // 6. signature verifies over the canonical string.
        val payloadRaw = envelope.optString("payload_raw", "")
        if (payloadRaw.isBlank()) return Result.Reject("bad_payload")
        val sigB64 = envelope.optString("sig", "")
        if (sigB64.isBlank()) return Result.Reject("bad_sig")
        val canonical = "$envelopeDeviceId\n$nonce\n$ts\n$payloadRaw"
        val verified = runCatching {
            val rawKey = Base64.getDecoder().decode(storedKeyB64)
            if (rawKey.size != 32) return Result.Reject("bad_key")
            val spki = hexToBytes(SPKI_PREFIX_HEX) + rawKey
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(spki))
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(publicKey)
            sig.update(canonical.toByteArray(Charsets.UTF_8))
            sig.verify(Base64.getDecoder().decode(sigB64))
        }.getOrDefault(false)
        if (!verified) return Result.Reject("bad_sig")
        // Signature is good — the nonce is spent now (a replayed copy
        // of THIS frame is rejected from here on).
        runCatching { markNonceSeen(nonce) }
        // 7. payload_raw parses and has kind + job_id.
        val step = runCatching { JSONObject(payloadRaw) }.getOrNull()
            ?: return Result.Reject("bad_payload")
        val kind = step.optString("kind", "")
        val jobId = step.optString("job_id", "")
        if (kind.isBlank() || jobId.isBlank()) return Result.Reject("bad_payload")
        // payload_raw is the source of truth: if the envelope ALSO
        // carries a `step` object and it disagrees, reject.
        val envelopeStep = envelope.optJSONObject("step")
        if (envelopeStep != null && envelopeStep.toString() != step.toString()) {
            return Result.Reject("step_mismatch")
        }
        return Result.Ok(step)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
