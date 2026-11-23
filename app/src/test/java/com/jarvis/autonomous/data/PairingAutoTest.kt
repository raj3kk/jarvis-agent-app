package com.jarvis.autonomous.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4.1 — pairing auto-flow response mapping tests. Pure Kotlin
 * (parseAutoResponse takes only the HTTP code + body text), so no
 * network is needed. The live route is POST /api/pairing/auto
 * (hub v20).
 */
class PairingAutoTest {

    @Test
    fun `200 paired returns tenant id`() {
        val r = PairingManager.parseAutoResponse(
            200,
            """{"ok":true,"paired":true,"device_id":"d1","tenant_id":"lic-abc"}"""
        )
        assertTrue(r is PairingManager.AutoResult.Ok)
        assertEquals("lic-abc", (r as PairingManager.AutoResult.Ok).tenantId)
    }

    @Test
    fun `200 without paired is a server error`() {
        val r = PairingManager.parseAutoResponse(200, """{"ok":true,"paired":false}""")
        assertEquals(
            PairingManager.AutoResult.Err(PairingManager.PairingError.SERVER),
            r
        )
    }

    @Test
    fun `200 malformed json is a server error`() {
        val r = PairingManager.parseAutoResponse(200, "not json")
        assertEquals(
            PairingManager.AutoResult.Err(PairingManager.PairingError.SERVER),
            r
        )
    }

    @Test
    fun `403 device_limit maps to DEVICE_LIMIT (not licence invalid)`() {
        val r = PairingManager.parseAutoResponse(
            403,
            """{"error":"device_limit","detail":"Device limit khatam"}"""
        )
        assertEquals(
            PairingManager.AutoResult.Err(PairingManager.PairingError.DEVICE_LIMIT),
            r
        )
    }

    @Test
    fun `403 licence_invalid maps to LICENCE_INVALID`() {
        val r = PairingManager.parseAutoResponse(403, """{"error":"licence_invalid"}""")
        assertEquals(
            PairingManager.AutoResult.Err(PairingManager.PairingError.LICENCE_INVALID),
            r
        )
    }

    @Test
    fun `429 maps to RATE_LIMITED, 400 to BAD_REQUEST, 500 to SERVER`() {
        assertEquals(
            PairingManager.AutoResult.Err(PairingManager.PairingError.RATE_LIMITED),
            PairingManager.parseAutoResponse(429, "")
        )
        assertEquals(
            PairingManager.AutoResult.Err(PairingManager.PairingError.BAD_REQUEST),
            PairingManager.parseAutoResponse(400, "")
        )
        assertEquals(
            PairingManager.AutoResult.Err(PairingManager.PairingError.SERVER),
            PairingManager.parseAutoResponse(500, "")
        )
    }
}
