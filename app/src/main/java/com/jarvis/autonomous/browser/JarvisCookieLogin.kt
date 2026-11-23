package com.jarvis.autonomous.browser

import android.webkit.CookieManager

/**
 * 1.15.0-PRO (redesign R0) — cookie-based login truth, adapted from
 * WhopClip `LoginActivity` + `SessionManager` (login = known session
 * cookies present) and AutoClip's privacy boundary.
 *
 * Privacy boundary (AutoClip rule, kept): this object answers ONLY in
 * booleans — "is the session alive for this URL". Full cookie values
 * NEVER leave the WebView store and are never sent to the hub/server;
 * the server only ever learns a login-state boolean. Cookie names come
 * from the job (per-site, caller-supplied) — NO service-specific
 * cookie-name lists are hardcoded here (Whop/IG lists were NOT copied).
 */
object JarvisCookieLogin {

    /** Persist the WebView cookie store to disk now (not on next flush). */
    fun flush() {
        runCatching { CookieManager.getInstance().flush() }
    }

    private fun rawCookies(url: String): String =
        runCatching { CookieManager.getInstance().getCookie(url) ?: "" }.getOrDefault("")

    /**
     * True when the URL has session cookies. With no [names], any
     * non-empty cookie string counts; with names, ALL of them must be
     * present (matched as `name=` so `session` does not match
     * `session_id`'s prefix — the `=` pins the name boundary).
     */
    fun hasSessionCookies(url: String, vararg names: String): Boolean {
        val raw = rawCookies(url)
        if (raw.isBlank()) return false
        if (names.isEmpty()) return true
        val lower = raw.lowercase()
        return names.all { lower.contains("${it.lowercase()}=") }
    }

    /** Login-state boolean for a URL (same contract as [hasSessionCookies]). */
    fun loginState(url: String, vararg names: String): Boolean =
        hasSessionCookies(url, *names)
}
