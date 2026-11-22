package com.jarvis.autonomous.browser.gecko

import com.jarvis.autonomous.ui.stripPreviewNonce
import java.net.URLDecoder

/**
 * Pure helpers for the Gecko browser (1.13.0). No Android or GeckoView
 * types here on purpose — everything in this file runs on the plain JVM
 * harness and in CI unit tests (see GeckoBridgeUtilsTest).
 *
 * The manager converts each incoming bridge JSONObject into a plain
 * Map<String, Any?> before calling these (see GeckoBrowserManager),
 * so the parsing logic stays testable without Robolectric.
 */

data class GeckoExtract(
    val url: String,
    val title: String,
    val text: String,
    val textLen: Int,
    val captcha: String
)

data class GeckoCookie(val name: String, val value: String)

object GeckoBridgeUtils {

    /**
     * filename*=UTF-8''... preferred, else filename="...".
     * (Ported from the Phase 0 spike — v160 validates Content-Disposition
     * with desktop's save rules, bug 1822968.)
     */
    fun parseDispositionFilename(disposition: String): String? {
        val star = Regex(
            "filename\\*\\s*=\\s*UTF-8''([^;\\s]+)",
            RegexOption.IGNORE_CASE
        ).find(disposition)
        if (star != null) {
            return runCatching {
                URLDecoder.decode(star.groupValues[1], "UTF-8")
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        return Regex(
            "filename\\s*=\\s*\"?([^\";]+)\"?",
            RegexOption.IGNORE_CASE
        ).find(disposition)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * A download filename is safe for filesDir: only [A-Za-z0-9._-],
     * and it must carry at least one letter or digit — otherwise an
     * all-punctuation input ("/", "..", "") would become a useless or
     * even directory-traversing name (".."). Falls back to download.bin.
     */
    fun sanitizeDownloadName(rawName: String): String {
        val cleaned = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.any { it.isLetterOrDigit() }) cleaned else "download.bin"
    }

    /**
     * Resolve the filename for a download: Content-Disposition first,
     * else the last URI path segment, else "download.bin".
     */
    fun downloadFileName(uri: String, disposition: String?): String {
        val fromDisposition = disposition?.let { parseDispositionFilename(it) }
        val fromUri = uri.substringAfterLast('/').substringBefore('?')
            .takeIf { it.isNotBlank() }
        return sanitizeDownloadName(fromDisposition ?: fromUri ?: "download.bin")
    }

    /**
     * Only http/https pages may load in the Gecko browser — same rule as
     * the WebView engine (normalizeBrowserUrl): javascript:, file:,
     * intent:, data:, tel: and every other scheme are blocked.
     */
    fun isLoadableScheme(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    /**
     * Parse a bridge "extract" message (fields as a plain map).
     * Returns null when the message carries no usable URL — such a
     * message can never be attributed to a page.
     */
    fun parseExtract(fields: Map<String, Any?>): GeckoExtract? {
        val url = (fields["url"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return GeckoExtract(
            url = url,
            title = (fields["title"] as? String) ?: "",
            text = (fields["text"] as? String) ?: "",
            textLen = (fields["textLen"] as? Number)?.toInt()
                ?: (fields["text"] as? String)?.length ?: 0,
            captcha = (fields["captcha"] as? String) ?: "none"
        )
    }

    /** Parse a bridge "cookie-get-result" message into name/value pairs. */
    fun parseCookies(fields: Map<String, Any?>): List<GeckoCookie> {
        val raw = fields["cookies"] as? List<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = map["name"] as? String ?: return@mapNotNull null
            val value = map["value"] as? String ?: ""
            GeckoCookie(name, value)
        }
    }

    /**
     * B-C4 guard: an extract belongs to the requested page only when
     * its URL equals the requested URL, ignoring only the preview
     * ?v= nonce — exactly the WebView engine's rule. A redirect whose
     * final URL differs, or an iframe's extract, never counts.
     */
    fun extractMatchesPage(extractUrl: String, requestedUrl: String): Boolean {
        return stripPreviewNonce(extractUrl) == stripPreviewNonce(requestedUrl)
    }

    /** Human label for a captcha marker from the content script. */
    fun captchaLabel(captcha: String): String = when (captcha.lowercase()) {
        "turnstile" -> "Turnstile"
        "recaptcha" -> "reCAPTCHA"
        "hcaptcha" -> "hCaptcha"
        "unknown" -> "Captcha (type pata nahi)"
        else -> "Captcha"
    }
}
