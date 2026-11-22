package com.jarvis.autonomous.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FCM push payload tests (1.12.0). Pure Kotlin — no Android/Firebase
 * types, so these run on the plain JVM harness and in CI.
 */
class FcmPushPayloadTest {

    @Test
    fun `full data parses`() {
        val p = FcmPushPayload.fromData(
            mapOf("title" to "Jarvis test", "body" to "Hello from the hub", "kind" to "test")
        )
        assertEquals("Jarvis test", p?.title)
        assertEquals("Hello from the hub", p?.body)
        assertEquals("test", p?.kind)
    }

    @Test
    fun `missing title defaults to Jarvis`() {
        val p = FcmPushPayload.fromData(mapOf("body" to "Hi", "kind" to "job_done"))
        assertEquals("Jarvis", p?.title)
        assertEquals("job_done", p?.kind)
    }

    @Test
    fun `missing kind defaults to test`() {
        val p = FcmPushPayload.fromData(mapOf("title" to "T", "body" to "B"))
        assertEquals("test", p?.kind)
    }

    @Test
    fun `empty body returns null (no notification)`() {
        assertNull(FcmPushPayload.fromData(mapOf("title" to "T", "body" to "")))
        assertNull(FcmPushPayload.fromData(mapOf("title" to "T", "body" to "   ")))
        assertNull(FcmPushPayload.fromData(mapOf("title" to "T")))
        assertNull(FcmPushPayload.fromData(emptyMap()))
    }

    @Test
    fun `blank title defaults to Jarvis and whitespace is trimmed`() {
        val p = FcmPushPayload.fromData(
            mapOf("title" to "   ", "body" to "  Hello  ", "kind" to "  test  ")
        )
        assertEquals("Jarvis", p?.title)
        assertEquals("Hello", p?.body)
        assertEquals("test", p?.kind)
    }
}
