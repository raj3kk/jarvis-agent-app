package com.jarvis.autonomous.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U7 (R1 audit follow-up, A7): JarvisDiagnosticsFile RAM sample —
 * the pure formatting seam only (JVM unit test, JUnit 4, runs in CI
 * `./gradlew testDebugUnitTest`). The ActivityManager read itself
 * is exercised on the phone (Diagnostics card -> "RAM check"),
 * never faked here. The sample carries numbers only — this test
 * also pins that no key/token-shaped value can ever appear in it.
 */
class MemorySampleTest {

    private val mb = 1024L * 1024L

    @Test
    fun formatMemorySample_rendersTotalAvailHeapAndLowFlag() {
        val msg = JarvisDiagnosticsFile.formatMemorySample(
            totalMemBytes = 8L * 1024L * mb,
            availMemBytes = 3L * 1024L * mb + 512L * mb,
            heapUsedBytes = 120L * mb,
            heapMaxBytes = 512L * mb,
            lowMemory = false
        )
        assertEquals(
            "total=8192MB avail=3584MB heap_used=120MB heap_max=512MB low_memory=false",
            msg
        )
    }

    @Test
    fun formatMemorySample_lowMemoryTrue_rendersTrue() {
        val msg = JarvisDiagnosticsFile.formatMemorySample(
            totalMemBytes = 4L * 1024L * mb,
            availMemBytes = 200L * mb,
            heapUsedBytes = 400L * mb,
            heapMaxBytes = 512L * mb,
            lowMemory = true
        )
        assertTrue(msg.contains("low_memory=true"))
        assertTrue(msg.contains("total=4096MB"))
    }

    @Test
    fun formatMemorySample_zeroValues_neverBlank() {
        val msg = JarvisDiagnosticsFile.formatMemorySample(0L, 0L, 0L, 0L, false)
        assertEquals(
            "total=0MB avail=0MB heap_used=0MB heap_max=0MB low_memory=false",
            msg
        )
    }
}
