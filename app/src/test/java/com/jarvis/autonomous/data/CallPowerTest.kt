package com.jarvis.autonomous.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.26.0 (power features) — unit tests for the PURE parts of
 * CallPower: number normalisation, the emergency guard, the M0
 * audio gate, and the probe-results JSON round-trip. Everything
 * that needs Shizuku, Telecom or a Context (dial, WhatsApp call,
 * the probe run itself, the call log) is NOT unit-tested — it needs
 * the live phone, and the phone proof is in APP_NOTES (M0 P1–P10).
 */
class CallPowerTest {

    @Test
    fun normalizeNumberKeepsDigitsAndPlusOnly() {
        assertEquals("+919876543210", CallPower.normalizeNumber("+91 98765 43210"))
        assertEquals("9876543210", CallPower.normalizeNumber("98765-43210"))
        assertEquals("", CallPower.normalizeNumber("abc"))
        assertEquals("+91112", CallPower.normalizeNumber("+91 (112)"))
    }

    @Test
    fun emergencyNumbersAreRefused() {
        assertTrue(CallPower.isEmergencyNumber("112"))
        assertTrue(CallPower.isEmergencyNumber("100"))
        assertTrue(CallPower.isEmergencyNumber("101"))
        assertTrue(CallPower.isEmergencyNumber("102"))
        assertTrue(CallPower.isEmergencyNumber("108"))
        assertTrue(CallPower.isEmergencyNumber("911"))
        // Empty / too-short numbers are refused too (never dialled).
        assertTrue(CallPower.isEmergencyNumber(""))
        assertTrue(CallPower.isEmergencyNumber("12"))
        // A normal 10-digit number is fine.
        assertFalse(CallPower.isEmergencyNumber("9876543210"))
        assertFalse(CallPower.isEmergencyNumber("+919876543210"))
    }

    @Test
    fun audioGateNeedsP2AndP3Pass() {
        fun results(p2: CallPower.ProbeStatus, p3: CallPower.ProbeStatus) = listOf(
            CallPower.ProbeResult("P1", "Shell identity", CallPower.ProbeStatus.PASS, "ok", ""),
            CallPower.ProbeResult("P2", "Audio-policy telephony routes", p2, "ok", ""),
            CallPower.ProbeResult("P3", "Call-audio interceptable?", p3, "ok", ""),
            CallPower.ProbeResult("P10", "Echo test", CallPower.ProbeStatus.PENDING, "waits for the engine", "")
        )
        assertTrue(CallPower.audioGatePassed(results(CallPower.ProbeStatus.PASS, CallPower.ProbeStatus.PASS)))
        assertFalse(CallPower.audioGatePassed(results(CallPower.ProbeStatus.FAIL, CallPower.ProbeStatus.PASS)))
        assertFalse(CallPower.audioGatePassed(results(CallPower.ProbeStatus.PASS, CallPower.ProbeStatus.FAIL)))
        assertFalse(CallPower.audioGatePassed(results(CallPower.ProbeStatus.PASS, CallPower.ProbeStatus.UNKNOWN)))
        assertFalse(CallPower.audioGatePassed(emptyList()))
    }

    @Test
    fun probeResultsJsonRoundTrip() {
        val original = listOf(
            CallPower.ProbeResult("P2", "Audio-policy telephony routes", CallPower.ProbeStatus.PASS, "routes mile", "raw-output-here"),
            CallPower.ProbeResult("P3", "Call-audio interceptable?", CallPower.ProbeStatus.UNKNOWN, "hidden API nahi chali", "error-text"),
            CallPower.ProbeResult("P10", "Echo test", CallPower.ProbeStatus.PENDING, "engine ke baad", "")
        )
        val json = CallPower.probeResultsToJson(original)
        val parsed = CallPower.probeResultsFromJson(json)
        assertEquals(3, parsed.size)
        assertEquals("P2", parsed[0].id)
        assertEquals(CallPower.ProbeStatus.PASS, parsed[0].status)
        assertEquals("routes mile", parsed[0].detail)
        assertEquals("raw-output-here", parsed[0].raw)
        assertEquals(CallPower.ProbeStatus.UNKNOWN, parsed[1].status)
        assertEquals(CallPower.ProbeStatus.PENDING, parsed[2].status)
        // Empty / garbage input parses to an empty list, never a crash.
        assertTrue(CallPower.probeResultsFromJson("").isEmpty())
        assertTrue(CallPower.probeResultsFromJson("not json").isEmpty())
    }

    @Test
    fun callStateDefaultsToIdle() {
        val state = CallPower.JarvisCallState()
        assertEquals(CallPower.CallStatus.IDLE, state.status)
        assertEquals(CallPower.CallAudioMode.NONE, state.audioMode)
        assertFalse(state.muted)
        assertFalse(state.speaker)
    }
}
