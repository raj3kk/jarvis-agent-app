package com.jarvis.autonomous.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN §8.4 — detector boundary tests (exclusions first, triggers second). */
class SelfExtensionDetectorTest {

    private fun failure(reason: String, stepKind: String = "BUILD", action: String = "BUILD", httpCode: Int? = null) =
        ExecutorFailure(stepKind = stepKind, action = action, reason = reason, httpCode = httpCode)

    private fun assertExclusion(reason: String, code: String, httpCode: Int? = null, stepKind: String = "BUILD", action: String = "BUILD") {
        val detection = SelfExtensionDetector.detect(failure(reason, stepKind, action, httpCode))
        val exclusion = (detection as? Detection.NotACapabilityBlock)?.exclusion
        assertEquals("reason=[$reason] should be $code", code, exclusion)
    }

    @Test
    fun detectorExclusionLogin() {
        assertExclusion("Login required — sign in again to continue", "X-LOGIN")
    }

    @Test
    fun detectorExclusionOtp() {
        assertExclusion("OTP verification failed — session expired", "X-LOGIN")
    }

    @Test
    fun detectorExclusionPayment() {
        assertExclusion("Payment failed: insufficient funds", "X-PAYMENT")
    }

    @Test
    fun detectorExclusionCaptcha() {
        assertExclusion("Captcha check appeared — turnstile challenge", "X-CAPTCHA")
    }

    @Test
    fun detectorExclusionPermission() {
        assertExclusion("Permission denied: overlay permission not granted", "X-PERMISSION")
    }

    @Test
    fun detectorExclusionTransient5xx() {
        assertExclusion("HTTP 500: server error", "X-TRANSIENT", httpCode = 500)
    }

    @Test
    fun detectorExclusionTransientRateLimit() {
        assertExclusion("Rate limit lag gaya hai, dobara koshish karunga", "X-TRANSIENT")
    }

    @Test
    fun detectorExclusionDecline() {
        assertExclusion("user declined the question", "X-DECLINE")
    }

    @Test
    fun detectorExclusionNoReply() {
        assertExclusion("Step fail hua, user se poocha — jawab nahi aaya", "X-NO-REPLY")
    }

    @Test
    fun detectorExclusionGate() {
        assertExclusion("Gate ne step block kiya: confirmation needed", "X-GATE")
    }

    @Test
    fun detectorExclusionMissingInfo() {
        assertExclusion("Project naam nahi mila — '?' naam nahi ho sakta.", "X-MISSING-INFO")
    }

    @Test
    fun detectorExclusionParse() {
        assertExclusion("Model JSON parse nahi hua: malformed json", "X-PARSE")
    }

    @Test
    fun detectorExclusionUserContent() {
        assertExclusion("Unsupported file: corrupt user file, cannot open", "X-USER-CONTENT")
    }

    @Test
    fun detectorTriggerUnknownStep() {
        val detection = SelfExtensionDetector.detect(
            failure("Unknown step kind: FAKE_KIND_X (T-NO-HANDLER)", stepKind = "FAKE_KIND_X", action = "FAKE_KIND_X")
        )
        val missing = detection as? Detection.MissingCapability
        assertTrue(missing != null)
        assertEquals("T-UNKNOWN-STEP", missing!!.trigger)
        // Capability comes from the step kind via the fixed map /
        // normaliser — never from goal text.
        assertEquals("fake-kind-x", missing.capability)
        assertTrue(missing.capability.matches(Regex("[a-z0-9-]+")))
    }

    @Test
    fun detectorTriggerNoHandler() {
        val detection = SelfExtensionDetector.detect(
            failure("T-NO-HANDLER: no handler for step kind FAKE_KIND_X", stepKind = "FAKE_KIND_X", action = "FAKE_KIND_X")
        )
        val missing = detection as? Detection.MissingCapability
        assertTrue(missing != null)
        assertEquals("T-NO-HANDLER", missing!!.trigger)
    }

    @Test
    fun detectorTriggerUnknownAction() {
        val detection = SelfExtensionDetector.detect(
            failure("UNKNOWN_ACTION(FOO_BAR)", stepKind = "LOOP_ACTION", action = "UNKNOWN_ACTION")
        )
        val missing = detection as? Detection.MissingCapability
        assertTrue(missing != null)
        assertEquals("T-UNKNOWN-ACTION", missing!!.trigger)
    }

    @Test
    fun detectorTriggerFeatureAbsent() {
        val detection = SelfExtensionDetector.detect(
            failure("hub error no_route for kind search_grounding", stepKind = "HTTP_CHECK", action = "HTTP_CHECK")
        )
        val missing = detection as? Detection.MissingCapability
        assertTrue(missing != null)
        assertEquals("T-FEATURE-ABSENT", missing!!.trigger)
    }

    @Test
    fun detectorTriggerToolMissing() {
        val detection = SelfExtensionDetector.detect(
            failure("build failed: no such tool 'ffmpeg' on device", stepKind = "BUILD", action = "BUILD")
        )
        val missing = detection as? Detection.MissingCapability
        assertTrue(missing != null)
        assertEquals("T-TOOL-MISSING", missing!!.trigger)
    }

    @Test
    fun detectorExclusionBeatsTrigger() {
        // A login-flavoured unknown step is still an exclusion:
        // exclusions are checked FIRST, in §2.2 order.
        assertExclusion("Unknown step kind: LOGIN_STEP — login required", "X-LOGIN", stepKind = "LOGIN_STEP", action = "LOGIN_STEP")
    }

    @Test
    fun detectorNoUserText() {
        // Goal text carrying an email + phone + password-like string
        // must NEVER reach the capability string or the signature:
        // the detector reads step kind/action/reason only.
        val secretEmail = "someone@example.com"
        val secretPhone = "9876543210"
        val secretPassword = "hunter2-secret"
        val detection = SelfExtensionDetector.detect(
            ExecutorFailure(
                stepKind = "BUILD",
                action = "BUILD",
                reason = "Unknown step kind: FAKE_KIND_X",
                goalText = "email $secretEmail phone $secretPhone password $secretPassword"
            )
        )
        val missing = detection as? Detection.MissingCapability
        assertTrue(missing != null)
        assertFalse(missing!!.capability.contains(secretEmail))
        assertFalse(missing.capability.contains(secretPhone))
        assertFalse(missing.capability.contains(secretPassword))
        assertFalse(missing.signature.contains(secretEmail))
        assertFalse(missing.signature.contains(secretPhone))
        assertFalse(missing.signature.contains(secretPassword))
        assertTrue(missing.signature.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun detectorCapabilityNormalised() {
        assertEquals("build-project", SelfExtensionDetector.capabilityFor("BUILD", "BUILD"))
        assertEquals("unknown-capability", SelfExtensionDetector.capabilityFor("", ""))
        val long = SelfExtensionDetector.capabilityFor("A".repeat(100), "B".repeat(100))
        assertTrue(long.length <= 64)
    }

    @Test
    fun detectorSignatureStable() {
        val f = failure("Unknown step kind: FAKE_KIND_X", stepKind = "FAKE_KIND_X", action = "FAKE_KIND_X")
        assertEquals(
            SelfExtensionDetector.sha256Hex("fake-kind-x|FAKE_KIND_X|FAKE_KIND_X"),
            SelfExtensionDetector.signature(SelfExtensionDetector.capabilityFor("FAKE_KIND_X", "FAKE_KIND_X"), f)
        )
    }
}
