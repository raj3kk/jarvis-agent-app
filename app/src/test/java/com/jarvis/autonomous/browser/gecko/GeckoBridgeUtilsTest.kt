package com.jarvis.autonomous.browser.gecko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gecko bridge pure-helper tests (1.13.0). Pure Kotlin — no Android /
 * GeckoView types, so these run on the plain JVM harness and in CI.
 */
class GeckoBridgeUtilsTest {

    @Test
    fun `disposition filename star form wins and decodes`() {
        assertEquals(
            "my file.pdf",
            GeckoBridgeUtils.parseDispositionFilename(
                "attachment; filename*=UTF-8''my%20file.pdf; filename=\"ignored.pdf\""
            )
        )
    }

    @Test
    fun `disposition filename quoted form`() {
        assertEquals(
            "report.pdf",
            GeckoBridgeUtils.parseDispositionFilename("attachment; filename=\"report.pdf\"")
        )
    }

    @Test
    fun `disposition without filename returns null`() {
        assertNull(GeckoBridgeUtils.parseDispositionFilename("attachment"))
        assertNull(GeckoBridgeUtils.parseDispositionFilename(""))
    }

    @Test
    fun `sanitize strips unsafe chars and never returns blank`() {
        assertEquals("a_b_c.pdf", GeckoBridgeUtils.sanitizeDownloadName("a/b:c.pdf"))
        assertEquals("download.bin", GeckoBridgeUtils.sanitizeDownloadName("///"))
        assertEquals("download.bin", GeckoBridgeUtils.sanitizeDownloadName(""))
    }

    @Test
    fun `download filename prefers disposition over uri`() {
        assertEquals(
            "real.pdf",
            GeckoBridgeUtils.downloadFileName(
                "https://example.com/other.pdf",
                "attachment; filename=\"real.pdf\""
            )
        )
        assertEquals(
            "other.pdf",
            GeckoBridgeUtils.downloadFileName("https://example.com/other.pdf", null)
        )
        assertEquals(
            "download.bin",
            GeckoBridgeUtils.downloadFileName("https://example.com/", null)
        )
    }

    @Test
    fun `only http and https are loadable`() {
        assertTrue(GeckoBridgeUtils.isLoadableScheme("https://example.com/"))
        assertTrue(GeckoBridgeUtils.isLoadableScheme("http://localhost:8080/x"))
        assertFalse(GeckoBridgeUtils.isLoadableScheme("javascript:alert(1)"))
        assertFalse(GeckoBridgeUtils.isLoadableScheme("file:///etc/passwd"))
        assertFalse(GeckoBridgeUtils.isLoadableScheme("intent://x#Intent;scheme=https;end"))
        assertFalse(GeckoBridgeUtils.isLoadableScheme("data:text/html,<h1>x</h1>"))
        assertFalse(GeckoBridgeUtils.isLoadableScheme("tel:+911234567890"))
        assertFalse(GeckoBridgeUtils.isLoadableScheme("example.com"))
    }

    @Test
    fun `extract parses and defaults`() {
        val e = GeckoBridgeUtils.parseExtract(
            mapOf(
                "url" to "https://example.com/",
                "title" to "Example Domain",
                "text" to "Hello world",
                "textLen" to 11,
                "captcha" to "none"
            )
        )
        assertEquals("https://example.com/", e?.url)
        assertEquals("Example Domain", e?.title)
        assertEquals("Hello world", e?.text)
        assertEquals(11, e?.textLen)
        assertEquals("none", e?.captcha)
    }

    @Test
    fun `extract without url returns null`() {
        assertNull(GeckoBridgeUtils.parseExtract(mapOf("title" to "No url")))
        assertNull(GeckoBridgeUtils.parseExtract(emptyMap()))
    }

    @Test
    fun `extract without textLen falls back to text length`() {
        val e = GeckoBridgeUtils.parseExtract(
            mapOf("url" to "https://example.com/", "text" to "abc")
        )
        assertEquals(3, e?.textLen)
        assertEquals("none", e?.captcha)
    }

    @Test
    fun `cookies parse name and value pairs`() {
        val cookies = GeckoBridgeUtils.parseCookies(
            mapOf(
                "cookies" to listOf(
                    mapOf("name" to "session", "value" to "abc123"),
                    mapOf("name" to "theme", "value" to "dark"),
                    mapOf("value" to "no-name"),
                    "not-a-map"
                )
            )
        )
        assertEquals(2, cookies.size)
        assertEquals("session", cookies[0].name)
        assertEquals("abc123", cookies[0].value)
        assertEquals("dark", cookies[1].value)
    }

    @Test
    fun `cookies without list returns empty`() {
        assertTrue(GeckoBridgeUtils.parseCookies(emptyMap()).isEmpty())
        assertTrue(GeckoBridgeUtils.parseCookies(mapOf("cookies" to "nope")).isEmpty())
    }

    @Test
    fun `B-C4 extract matches only its own page`() {
        assertTrue(
            GeckoBridgeUtils.extractMatchesPage(
                "https://example.com/page",
                "https://example.com/page"
            )
        )
        // Preview ?v= nonce is ignored — same page.
        assertTrue(
            GeckoBridgeUtils.extractMatchesPage(
                "https://example.com/page?v=3",
                "https://example.com/page"
            )
        )
        // A redirect or an iframe's extract never counts.
        assertFalse(
            GeckoBridgeUtils.extractMatchesPage(
                "https://example.com/other",
                "https://example.com/page"
            )
        )
        assertFalse(
            GeckoBridgeUtils.extractMatchesPage(
                "https://consent.google.com/",
                "https://www.google.com/search?q=jarvis"
            )
        )
    }

    @Test
    fun `captcha labels`() {
        assertEquals("Turnstile", GeckoBridgeUtils.captchaLabel("turnstile"))
        assertEquals("reCAPTCHA", GeckoBridgeUtils.captchaLabel("recaptcha"))
        assertEquals("hCaptcha", GeckoBridgeUtils.captchaLabel("hcaptcha"))
        assertEquals("Captcha (type pata nahi)", GeckoBridgeUtils.captchaLabel("unknown"))
        assertEquals("Captcha", GeckoBridgeUtils.captchaLabel("none"))
    }
}
