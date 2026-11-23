package com.jarvis.autonomous.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * R4.6 (D-R18) — content idempotency key tests. Pure Kotlin (SHA-256),
 * no Android types. The vector was computed with sha256sum on the VM
 * (printf 'a@b.com\nHello\nWorld'), and the live probe key
 * b213ce25… (flipify.com@gmail.com / R4 probe subject / test body)
 * was accepted by hub v20 — same canonical form as the hub's
 * contentIdempotencyKey (src/idempotency.ts: "to\nsubject\nbody").
 */
class GoogleClientIdempotencyTest {

    @Test
    fun `known vector matches sha256sum`() {
        assertEquals(
            "af4aad8a57f0755b406582329f0738aeb3bfc6d5ff79471d9f65120f7d3b6c32",
            GoogleClient.idempotencyKey("a@b.com", "Hello", "World")
        )
    }

    @Test
    fun `live probe vector matches the key the hub accepted`() {
        assertEquals(
            "b213ce2580ed06726538c77817bc83625fb9a80fb5c8e8939db2a9bab73ec1be",
            GoogleClient.idempotencyKey(
                "flipify.com@gmail.com",
                "R4 idempotency probe — do not reply",
                "test body 2026-11-23"
            )
        )
    }

    @Test
    fun `same content gives the same key (double-tap dedupes)`() {
        val a = GoogleClient.idempotencyKey("a@b.com", "Hi", "Body")
        val b = GoogleClient.idempotencyKey("a@b.com", "Hi", "Body")
        assertEquals(a, b)
    }

    @Test
    fun `any field change gives a different key`() {
        val base = GoogleClient.idempotencyKey("a@b.com", "Hi", "Body")
        assertNotEquals(base, GoogleClient.idempotencyKey("c@d.com", "Hi", "Body"))
        assertNotEquals(base, GoogleClient.idempotencyKey("a@b.com", "Hi!", "Body"))
        assertNotEquals(base, GoogleClient.idempotencyKey("a@b.com", "Hi", "Body!"))
    }

    @Test
    fun `key is 64 lowercase hex chars`() {
        val key = GoogleClient.idempotencyKey("a@b.com", "Hi", "Body")
        assertEquals(64, key.length)
        assertEquals(key, key.lowercase())
        assertEquals(key.filter { it in '0'..'9' || it in 'a'..'f' }, key)
    }
}
