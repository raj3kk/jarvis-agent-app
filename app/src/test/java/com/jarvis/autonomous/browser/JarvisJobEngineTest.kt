package com.jarvis.autonomous.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.15.0-PRO (redesign R0) — unit tests for the Whop-style browser engine's
 * PURE parts (no WebView, no Android runtime — plain JUnit4 on the JVM,
 * same as DocContextPromptTest). The WebView-driving paths (goto/click/
 * type/extract/screenshot/upload) cannot run on the JVM and are covered
 * by the phone tests B-T1–B-T12, not here. A test that needs a WebView
 * would fail in CI with a stub — so there is deliberately none.
 */
class JarvisJobEngineTest {

    // ---- validateSteps ----

    @Test
    fun `valid steps pass validation`() {
        val steps = JSONArray(
            """[
            {"action":"goto","url":"https://example.com"},
            {"action":"wait","ms":500},
            {"action":"wait_text","text":"Hello"},
            {"action":"assert_text","text":"Hello"},
            {"action":"click_text","text":"Next"},
            {"action":"type","selector":"input","text":"hi"},
            {"action":"js","code":"document.title"},
            {"action":"extract","key":"title","code":"document.title"},
            {"action":"screenshot","key":"frame.png"},
            {"action":"upload","selector":"input[type=file]"}
            ]"""
        )
        // Must not throw.
        JarvisJobEngine.validateSteps(steps)
    }

    @Test
    fun `empty steps pass validation`() {
        JarvisJobEngine.validateSteps(JSONArray())
    }

    @Test
    fun `unknown action fails validation`() {
        val steps = JSONArray("""[{"action":"teleport","url":"https://example.com"}]""")
        assertThrows(JarvisJobEngine.JobFailed::class.java) {
            JarvisJobEngine.validateSteps(steps)
        }
    }

    @Test
    fun `goto without url fails validation`() {
        val steps = JSONArray("""[{"action":"goto"}]""")
        val err = assertThrows(JarvisJobEngine.JobFailed::class.java) {
            JarvisJobEngine.validateSteps(steps)
        }
        assertTrue(err.message!!.contains("url missing"))
    }

    @Test
    fun `click_text without text fails validation`() {
        val steps = JSONArray("""[{"action":"click_text"}]""")
        assertThrows(JarvisJobEngine.JobFailed::class.java) {
            JarvisJobEngine.validateSteps(steps)
        }
    }

    @Test
    fun `type without selector fails validation`() {
        val steps = JSONArray("""[{"action":"type","text":"hi"}]""")
        assertThrows(JarvisJobEngine.JobFailed::class.java) {
            JarvisJobEngine.validateSteps(steps)
        }
    }

    @Test
    fun `extract without key fails validation`() {
        val steps = JSONArray("""[{"action":"extract","code":"document.title"}]""")
        assertThrows(JarvisJobEngine.JobFailed::class.java) {
            JarvisJobEngine.validateSteps(steps)
        }
    }

    @Test
    fun `non-object step fails validation`() {
        val steps = JSONArray("""["goto"]""")
        assertThrows(JarvisJobEngine.JobFailed::class.java) {
            JarvisJobEngine.validateSteps(steps)
        }
    }

    // ---- resolveGotoUrl ----

    @Test
    fun `placeholder resolves from extracted values`() {
        val extracted = JSONObject().put("post_url", "https://example.com/p/123")
        val out = JarvisJobEngine.resolveGotoUrl(
            "https://example.com/view?u={{post_url}}", extracted
        )
        assertEquals("https://example.com/view?u=https://example.com/p/123", out)
    }

    @Test
    fun `url without placeholder is unchanged`() {
        val out = JarvisJobEngine.resolveGotoUrl(
            "https://example.com/", JSONObject()
        )
        assertEquals("https://example.com/", out)
    }

    @Test
    fun `unresolved placeholder fails closed`() {
        val err = assertThrows(JarvisJobEngine.JobFailed::class.java) {
            JarvisJobEngine.resolveGotoUrl("https://example.com/{{missing}}", JSONObject())
        }
        assertTrue(err.message!!.contains("unresolved"))
    }

    // ---- sanitizeFrameKey ----

    @Test
    fun `frame key strips unsafe chars`() {
        assertEquals("frame_1.png", JarvisJobEngine.sanitizeFrameKey("frame 1.png"))
        assertEquals("a_b_c", JarvisJobEngine.sanitizeFrameKey("a/b\\c"))
        assertEquals("frame-1_2.png", JarvisJobEngine.sanitizeFrameKey("frame-1_2.png"))
    }

    // ---- decodeJsResult ----

    @Test
    fun `decodeJsResult unquotes JSON strings`() {
        assertEquals("hello", JarvisJobEngine.decodeJsResult("\"hello\""))
        assertEquals("a b", JarvisJobEngine.decodeJsResult("\"a b\""))
    }

    @Test
    fun `decodeJsResult passes numbers and booleans through`() {
        assertEquals("123", JarvisJobEngine.decodeJsResult("123"))
        assertEquals("true", JarvisJobEngine.decodeJsResult("true"))
    }

    @Test
    fun `decodeJsResult maps null and blank to empty`() {
        assertEquals("", JarvisJobEngine.decodeJsResult("null"))
        assertEquals("", JarvisJobEngine.decodeJsResult(""))
        assertEquals("", JarvisJobEngine.decodeJsResult(null))
    }

    @Test
    fun `decodeJsResult keeps error objects as text`() {
        val out = JarvisJobEngine.decodeJsResult("{\"__err\":\"boom\"}")
        assertTrue(out.contains("boom"))
    }

    // ---- JarvisUploadBridge (pure JVM — CountDownLatch only) ----

    @Test
    fun `upload bridge unarmed await returns false immediately`() {
        JarvisUploadBridge.disarm()
        assertEquals(false, JarvisUploadBridge.awaitFile(100))
    }

    @Test
    fun `upload bridge signal unblocks await with true`() {
        JarvisUploadBridge.arm()
        Thread { Thread.sleep(50); JarvisUploadBridge.signal(true) }.start()
        assertEquals(true, JarvisUploadBridge.awaitFile(5000))
        JarvisUploadBridge.disarm()
    }

    @Test
    fun `upload bridge signal false unblocks await with false`() {
        JarvisUploadBridge.arm()
        Thread { Thread.sleep(50); JarvisUploadBridge.signal(false) }.start()
        assertEquals(false, JarvisUploadBridge.awaitFile(5000))
        JarvisUploadBridge.disarm()
    }

    @Test
    fun `upload bridge disarm clears armed state`() {
        JarvisUploadBridge.arm()
        assertTrue(JarvisUploadBridge.isArmed())
        JarvisUploadBridge.disarm()
        assertEquals(false, JarvisUploadBridge.isArmed())
    }
}
