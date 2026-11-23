package com.jarvis.autonomous.browser

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat

/**
 * 1.15.0-PRO (redesign R0) — shared WebView setup for the Whop/AutoClip-style
 * browser. Adapted from AutoClip `LoginWebView.setup` (desktop mode,
 * renderer-crash flag, silent permission denies) — NO Whop/ClipFlow
 * service code, no login URLs, no cookie-name lists, no `; wv` UA-strip
 * hack (AutoClip Client Hints supersede it).
 *
 * Two uses:
 * - [applyDesktopMode] — the shared Browser-tab WebView
 *   ([com.jarvis.autonomous.ui.JarvisBackgroundBrowser.obtainWebView])
 *   and the JobEngine's own WebView both run desktop pages.
 * - [renderGoneClient] / [consumeRenderGone] — renderer death is a
 *   signal the engine can fail honestly on, never a silent blank page
 *   and never an app crash (returning true keeps the process alive).
 */
object JarvisBrowserSetup {

    /** Desktop mode is the default for every Jarvis WebView (AutoClip p65). */
    const val DESKTOP_MODE = true

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    @Volatile
    private var renderGone = false

    /** Read the renderer-death flag once (consumed — a later check is false). */
    fun consumeRenderGone(): Boolean {
        val v = renderGone
        renderGone = false
        return v
    }

    internal fun markRenderGone() {
        renderGone = true
    }

    /**
     * Desktop page mode: desktop UA string + desktop Client Hints
     * (Sec-CH-UA-Mobile: ?0, platform Windows). The UA string alone is
     * NOT enough — WebView keeps sending `Sec-CH-UA-Mobile: ?1`, so the
     * server serves the mobile bundle anyway. Also accepts cookies
     * (login persists across restarts in the WebView cookie store).
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun applyDesktopMode(webView: WebView) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.acceptThirdPartyCookies(webView)
        if (DESKTOP_MODE) {
            webView.settings.userAgentString = DESKTOP_UA
            try {
                WebSettingsCompat.setUserAgentMetadata(
                    webView.settings,
                    UserAgentMetadata.Builder()
                        .setMobile(false)
                        .setPlatform("Windows")
                        .setModel("")
                        .build()
                )
            } catch (_: Exception) {
                // Old WebView without Client Hints support: the UA
                // string still applies; desktop metadata is best-effort.
            }
        }
    }

    /**
     * Silent-deny chrome client for engine WebViews: camera/mic/
     * geolocation prompts are denied without showing any system UI.
     * A mid-automation permission prompt is a failure mode — browsing
     * and file upload never need camera/mic access. JS dialogs are
     * suppressed the same way (DSBridge's default dialog handling is
     * modal and would block the engine; the shared WebView suppresses
     * them too — no Activity dialog is ever attempted from an engine
     * WebView).
     */
    fun silentDenyChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            callback.invoke(origin, false, false)
        }

        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: android.webkit.JsResult?
        ): Boolean {
            result?.cancel()
            return true
        }

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: android.webkit.JsResult?
        ): Boolean {
            result?.cancel()
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: android.webkit.JsPromptResult?
        ): Boolean {
            result?.cancel()
            return true
        }
    }

    /**
     * A WebViewClient that only records renderer death. Returning true
     * tells WebView the app handled the crash — the default (false)
     * kills the app process. The engine checks [consumeRenderGone]
     * after every step and fails the job honestly (renderer_gone).
     */
    fun renderGoneClient(): WebViewClient = object : WebViewClient() {
        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail
        ): Boolean {
            markRenderGone()
            return true
        }
    }
}
