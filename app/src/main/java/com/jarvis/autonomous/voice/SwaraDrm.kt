package com.jarvis.autonomous.voice

import java.util.UUID

/**
 * 1.4.12 — Sec-MS-GEC v2 token for Microsoft Edge Read-Aloud TTS
 * (Swara, hi-IN-SwaraNeural). Mechanical Kotlin mirror of the
 * VERIFIED Java port (~/workspace/jarvis-1.4.12-scratch/EdgeDrm.java),
 * which matched the edge-tts Python implementation byte-for-byte
 * on 6 fixed timestamps (1700000000.0, 1793770000.0, 1767225600.5,
 * 1234567890.0, 0.0, 9999999999.0 — MATCH on all).
 *
 * Faithfulness rules (do NOT "simplify"):
 * - Every u32 word is a Long masked with MASK after each Python
 *   masked step; unmasked intermediates stay Long (low 32 bits
 *   are preserved through wrap, exactly like Python big ints).
 * - Logical shifts use `ushr`, never `>>` (arithmetic shift would
 *   sign-extend a wrapped negative Long).
 * - Python floor-mod (%) uses Math.floorMod (Java/Kotlin % is
 *   truncated and differs for negatives — padding counts!).
 * - SHA-256 compression uses the SIMPLE round form, provably
 *   equal to edge-tts's unrolled _sha256_rounds (same recurrence).
 * - The token constant below is patched at implementation time
 *   from the installed edge-tts package (never typed by hand —
 *   a hand-typed copy once dropped chars and broke verification).
 * - Clock skew is 0 (no Date fetch): the 300 s quantization gives
 *   ample tolerance for phone clock drift.
 * - No key, no account, no secret: this token is derived from
 *   the current time + the service's public client token, exactly
 *   as edge-tts does. Nothing here is a user credential.
 */
object SwaraDrm {

    private const val MASK = 0xFFFFFFFFL
    private const val WIN_EPOCH = 11644473600L

    /** Patched at implementation time from edge-tts constants. */
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    private val H0 = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
        0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L
    )
    private val STATE0 = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
        0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L
    )
    private val SCHED = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
        0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
        0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
        0xD258B060L, 0x72766564L
    )
    private val K = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L,
        0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
        0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L,
        0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
        0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL,
        0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
        0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L,
        0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
        0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L,
        0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
        0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L,
        0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
        0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L,
        0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
        0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
        0x90BEFFFA, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L
    )
    private const val ADJ = 0xC9DC06A7L
    private val BLOCK: LongArray = K.copyOf().also { it[14] = ADJ }
    private const val SIGMA0_ADJ = 0x059C0B80L
    private const val SIGMA1_ADJ = 0x000B92C0L

    private val CS64_COEF = arrayOf(
        longArrayOf(0xE82E91B1L, 0xB32700D0L),
        longArrayOf(0x44DE24C6L, 0x88DFF51DL),
        longArrayOf(0x3BEB65BCL, 0x29514038L),
        longArrayOf(0xC89C5933L, 0xB328CBDFL),
        longArrayOf(0x8D1EC211L, 0xD405B4D5L),
        longArrayOf(0x314EF5D5L, 0x26E55F3CL),
        longArrayOf(0xE92491B1L, 0x607801C6L),
        longArrayOf(0xF0A5B9D5L, 0x82B2C1FBL),
        longArrayOf(0x06064CF3L, 0xFA51A3DAL),
        longArrayOf(0xDA79FA60L, 0x207CC464L),
        longArrayOf(0xBED20560L, 0x3C3C74F3L),
        longArrayOf(0x89F9C9F8L, 0xD2D14B61L),
        longArrayOf(0x35FA38C6L, 0xBB04A2C1L),
        longArrayOf(0xCB49E3C6L, 0xC5626167L)
    )
    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJ0 = 0xF2F90B1AL
    private const val CS64_ADJ1 = 0x0B1A0000L

    private fun rotr(x: Long, n: Int): Long = ((x ushr n) or (x shl (32 - n))) and MASK
    private fun sigma0(x: Long): Long = (rotr(x, 7) xor rotr(x, 18) xor ((x ushr 3) and MASK)) and MASK
    private fun sigma1(x: Long): Long = (rotr(x, 17) xor rotr(x, 19) xor ((x ushr 10) and MASK)) and MASK
    private fun bigSigma0(x: Long): Long = (rotr(x, 2) xor rotr(x, 13) xor rotr(x, 22)) and MASK
    private fun bigSigma1(x: Long): Long = (rotr(x, 6) xor rotr(x, 11) xor rotr(x, 25)) and MASK
    private fun ch(x: Long, y: Long, z: Long): Long = (z xor (x and (y xor z))) and MASK
    private fun maj(x: Long, y: Long, z: Long): Long = ((x and y) or (z and (x or y))) and MASK

    private fun rounds(state: LongArray, w: LongArray, firstRound: Int): LongArray {
        val kc = if (firstRound == 14) BLOCK else K
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in firstRound until 64) {
            val t1 = (h + bigSigma1(e) + ch(e, f, g) + kc[i] + w[i]) and MASK
            val t2 = (bigSigma0(a) + maj(a, b, c)) and MASK
            h = g; g = f; f = e; e = (d + t1) and MASK
            d = c; c = b; b = a; a = (t1 + t2) and MASK
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expand(w: LongArray, from: Int) {
        for (i in from until 64) {
            w[i] = (sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16]) and MASK
        }
    }

    private fun sha256Init(data7: ByteArray): LongArray {
        val w = LongArray(64)
        w[14] = ((data7[0].toLong() and 0xFF) shl 16) or ((data7[1].toLong() and 0xFF) shl 8) or (data7[2].toLong() and 0xFF)
        w[15] = ((data7[3].toLong() and 0xFF) shl 24) or ((data7[4].toLong() and 0xFF) shl 16) or
            ((data7[5].toLong() and 0xFF) shl 8) or (data7[6].toLong() and 0xFF)
        for (index in 16 until 30) {
            var value = SCHED[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) (sigma1(w[index - 2]) xor SIGMA1_ADJ) else sigma1(w[index - 2])
            }
            if (index - 7 >= 14) value += w[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) (sigma0(w[index - 15]) xor SIGMA0_ADJ) else sigma0(w[index - 15])
            }
            w[index] = value and MASK
        }
        w[30] = (sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + ADJ - K[14]) and MASK
        expand(w, 31)
        val working = rounds(STATE0, w, 14)
        return LongArray(8) { (H0[it] + working[it]) and MASK }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Token salt must contain at least seven bytes" }
        val length = 57L + data.size
        val pad = Math.floorMod(55L - length, 64L).toInt()
        val total = (data.size - 7) + 1 + pad + 8
        val padded = ByteArray(total)
        data.copyInto(padded, 0, 7, data.size)
        padded[data.size - 7] = 0x80.toByte()
        val bits = length * 8
        for (i in 0 until 8) padded[total - 8 + i] = ((bits ushr (56 - 8 * i)) and 0xFF).toByte()
        var state = sha256Init(data.copyOfRange(0, 7))
        var off = 0
        while (off < total) {
            val w = LongArray(64)
            for (i in 0 until 16) {
                w[i] = ((padded[off + 4 * i].toLong() and 0xFF) shl 24) or
                    ((padded[off + 4 * i + 1].toLong() and 0xFF) shl 16) or
                    ((padded[off + 4 * i + 2].toLong() and 0xFF) shl 8) or
                    (padded[off + 4 * i + 3].toLong() and 0xFF)
            }
            expand(w, 16)
            val working = rounds(state, w, 0)
            state = LongArray(8) { (state[it] + working[it]) and MASK }
            off += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[4 * i] = ((state[i] ushr 24) and 0xFF).toByte()
            out[4 * i + 1] = ((state[i] ushr 16) and 0xFF).toByte()
            out[4 * i + 2] = ((state[i] ushr 8) and 0xFF).toByte()
            out[4 * i + 3] = (state[i] and 0xFF).toByte()
        }
        return out
    }

    private fun rol16(v: Long): Long {
        val x = v and MASK
        return ((x shl 16) or (x ushr 16)) and MASK
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): LongArray {
        var keyA = (digest[0].toLong() and 0xFF) or ((digest[1].toLong() and 0xFF) shl 8) or
            ((digest[2].toLong() and 0xFF) shl 16) or ((digest[3].toLong() and 0xFF) shl 24)
        var keyB = (digest[4].toLong() and 0xFF) or ((digest[5].toLong() and 0xFF) shl 8) or
            ((digest[6].toLong() and 0xFF) shl 16) or ((digest[7].toLong() and 0xFF) shl 24)
        keyA = keyA or 1
        keyB = keyB or 1
        val f0 = (keyA * CS64_FACTOR) and MASK
        val f1 = (keyB * CS64_FACTOR) and MASK
        val terms = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        for (i in CS64_COEF.indices) {
            terms.add((CS64_COEF[i][0] * (if (i % 2 == 0) f0 else f1)) and MASK)
            offsets.add(CS64_COEF[i][1])
        }
        val inp = ByteArray(data.size + 1)
        inp[0] = 0
        data.copyInto(inp, 1)
        val pad = Math.floorMod(-inp.size, 4)
        val padded = ByteArray(inp.size + pad)
        inp.copyInto(padded)
        var nwords = padded.size / 4
        if (nwords % 2 == 1) nwords += 1
        for (j in 0 until nwords) {
            var word = 0L
            if (j * 4 < padded.size) {
                word = (padded[j * 4].toLong() and 0xFF) or
                    ((padded[j * 4 + 1].toLong() and 0xFF) shl 8) or
                    ((padded[j * 4 + 2].toLong() and 0xFF) shl 16) or
                    ((padded[j * 4 + 3].toLong() and 0xFF) shl 24)
            }
            var term = word * (if (j % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (j % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
            if (j == 0) {
                term += CS64_ADJ0 * f0
                offset += CS64_ADJ1
            }
            terms.add(term and MASK)
            offsets.add(offset and MASK)
        }
        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        var i = 0
        while (i < terms.size) {
            val x = terms[i]
            val y = terms[i + 1]
            var t = rol16((offsets[i] + x) and MASK)
            var q = (t * 0x76F50F34L) and MASK
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) and MASK
            t = rol16((offsets[i + 1] + y) and MASK)
            q = (t * 0x1881CE3CL) and MASK
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and MASK
            swapAcc = (swapAcc + swapLast) and MASK
            state = (state * keyA + x) and MASK
            state = (rol16(state) * 0xCF98B111L) and MASK
            state = (rol16(state) * 0x87085B9FL) and MASK
            state = (rol16(state) * 0x12CEB96DL) and MASK
            state = (rol16(state) * 0x257E1D83L) and MASK
            acc += state
            t = (state * keyB + y) and MASK
            t = (rol16(t) * 0xA27416F5L) and MASK
            t = (rol16(t) * 0xD38396FFL) and MASK
            state = (rol16((rol16(t) * 0x7C932B89L) and MASK) * 0xBFA49F69L) and MASK
            acc = (acc + state) and MASK
            i += 2
        }
        return longArrayOf(swapLast xor state, swapAcc xor acc)
    }

    private fun hexUpper(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        // Mask with 0xFF: Java's Formatter sign-extends a Byte
        // for %X (byte 0xAB would print as FFFFFFAB without the
        // mask), which would corrupt every digest/MUID byte >=
        // 0x80. Python bytes.hex() emits exactly two chars/byte.
        for (b in bytes) sb.append(String.format("%02X", b.toInt() and 0xFF))
        return sb.toString()
    }

    /** Sec-MS-GEC v2 token for the current time (clock skew 0). */
    fun generateSecMsGec(): String = generateSecMsGec(System.currentTimeMillis() / 1000.0)

    fun generateSecMsGec(unixSeconds: Double): String {
        var ticks = unixSeconds + WIN_EPOCH
        ticks -= ticks % 300
        ticks *= 10000000.0
        val str = ticks.toLong().toString() + TRUSTED_CLIENT_TOKEN
        val data = str.toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        val cs = cs64Digest(data, digest)
        val csBytes = ByteArray(8)
        csBytes[0] = (cs[0] and 0xFF).toByte()
        csBytes[1] = ((cs[0] ushr 8) and 0xFF).toByte()
        csBytes[2] = ((cs[0] ushr 16) and 0xFF).toByte()
        csBytes[3] = ((cs[0] ushr 24) and 0xFF).toByte()
        csBytes[4] = (cs[1] and 0xFF).toByte()
        csBytes[5] = ((cs[1] ushr 8) and 0xFF).toByte()
        csBytes[6] = ((cs[1] ushr 16) and 0xFF).toByte()
        csBytes[7] = ((cs[1] ushr 24) and 0xFF).toByte()
        return hexUpper(csBytes) + "-" + hexUpper(digest)
    }

    /** Random MUID for the Cookie header (fresh per connection, like edge-tts). */
    fun generateMuid(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return hexUpper(bytes)
    }

    /** UUID without dashes, for ConnectionId / X-RequestId (like edge-tts connect_id()). */
    fun connectId(): String = UUID.randomUUID().toString().replace("-", "")

    /**
     * Full Edge Read-Aloud WebSocket URL for one connection.
     * Kept here (not in the client) so the client-token constant
     * stays in this one file. The URL is NEVER logged — it
     * carries the token and the GEC value as query params.
     */
    fun buildSpeechUrl(connectionId: String, gec: String): String {
        return "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud" +
            "/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=$gec" +
            "&Sec-MS-GEC-Version=2-143.0.3650.75"
    }
}
