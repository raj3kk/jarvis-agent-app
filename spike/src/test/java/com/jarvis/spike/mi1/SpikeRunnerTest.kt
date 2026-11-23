package com.jarvis.spike.mi1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the spike's hash + constants (no Android APIs).
 * The on-phone verdict (download → load → turn) cannot run here —
 * it is decided on the Vivo only.
 */
class SpikeRunnerTest {

    @Test
    fun sha256HexOfAbcMatchesKnownValue() {
        val tmp = File.createTempFile("mi1-hash-test", ".bin")
        try {
            tmp.writeBytes("abc".toByteArray(Charsets.UTF_8))
            // SHA-256("abc") — FIPS 180-4 test vector.
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                SpikeRunner.sha256Hex(tmp)
            )
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun expectedSoShaIs64LowercaseHex() {
        assertTrue(SpikeRunner.EXPECTED_SO_SHA256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun aarEntryPathIsTheArm64So() {
        assertEquals("jni/arm64-v8a/liblitertlm_jni.so", SpikeRunner.AAR_ENTRY)
    }

    @Test
    fun expectedSoSizeMatchesMeasured() {
        // Measured from the 1.19.0 APK's bundled .so AND from the
        // public litertlm-android 0.18.0 AAR (both 22,155,680 B).
        assertEquals(22_155_680L, SpikeRunner.EXPECTED_SO_BYTES)
    }

    @Test
    fun aarUrlIsGoogleMavenHttps() {
        assertTrue(SpikeRunner.AAR_URL.startsWith("https://maven.google.com/"))
        assertTrue(SpikeRunner.AAR_URL.endsWith("litertlm-android-0.18.0.aar"))
    }
}
