package com.jarvis.autonomous.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.22.0 — unit tests for the launch-bounce loop-breaker.
 *
 * `isForegroundServiceTypeError` is the decision point in
 * JarvisForegroundService.onCreate's catch: a foregroundServiceType
 * IllegalArgumentException must NOT rethrow (the service survives
 * without foreground state), while every other exception keeps the
 * 1.4.2 crash behaviour (record + rethrow). It is a pure function
 * (JVM unit test seam — no Android runtime), so these tests run on
 * the plain JVM in CI (testDebugUnitTest).
 *
 * What this does NOT prove (honest limit): the actual
 * startForeground calls themselves (JarvisForegroundService's
 * startForegroundSafe, JarvisWhiteboardService's startForegroundSafe)
 * need a Service + the Android framework, so they cannot be
 * unit-tested without Robolectric / instrumentation. The try/catch
 * around those calls is correct by construction (any Exception is
 * caught and logged as FGS_START_FAILED); only the classification
 * decision is covered here. Phone proof (no crash loop on the Vivo
 * after installing 1.22.0 OVER 1.21.0) is still owed by the user.
 */
class ForegroundServiceTypeErrorTest {

    // The exact message from the 2026-11-25 launch-bounce report.
    private val realCrashMessage =
        "foregroundServiceType 0x40000000 is not a subset of " +
            "foregroundServiceType attribute 0x00000000 in service " +
            "element of manifest file"

    @Test
    fun `real crash message is a foreground-service type error`() {
        assertTrue(
            isForegroundServiceTypeError(
                IllegalArgumentException(realCrashMessage)
            )
        )
    }

    @Test
    fun `IllegalArgumentException with another message is not`() {
        assertFalse(
            isForegroundServiceTypeError(
                IllegalArgumentException("parameter must not be null")
            )
        )
    }

    @Test
    fun `IllegalArgumentException with null message is not`() {
        assertFalse(
            isForegroundServiceTypeError(IllegalArgumentException(null as String?))
        )
    }

    @Test
    fun `non-IllegalArgumentException with the same text is not`() {
        assertFalse(
            isForegroundServiceTypeError(RuntimeException(realCrashMessage))
        )
    }

    @Test
    fun `SecurityException is not a foreground-service type error`() {
        assertFalse(
            isForegroundServiceTypeError(
                SecurityException("foregroundServiceType permission missing")
            )
        )
    }

    @Test
    fun `shorter not-a-subset phrasing is still a foreground-service type error`() {
        assertTrue(
            isForegroundServiceTypeError(
                IllegalArgumentException(
                    "foregroundServiceType 0x40000000 is not a subset"
                )
            )
        )
    }
}
