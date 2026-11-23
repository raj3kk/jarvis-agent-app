package com.jarvis.autonomous.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import com.jarvis.autonomous.data.JarvisStateRepository

/**
 * R3.5 — downloads + file-upload host for the in-app browser.
 *
 * DEVIATION (AgentWeb NOT embedded — see work/1.18.0/BUILD_NOTES.md
 * §6): AgentWeb (v5.1.7-androidx, CVE-2026-95180 fixed) was
 * evaluated and rejected as the browser host — it owns its WebView
 * lifecycle (breaks the shared-WebView + DWebView architecture)
 * and its default client had an access-control flaw. The two
 * behaviours it would provide are implemented directly here,
 * behind the same interface shape, keeping the adoption path open.
 *
 * Downloads: DownloadManager → Downloads/Jarvis/<name> (public
 * Downloads dir — the manager's own completion notification
 * carries the open action, so no custom receiver is needed).
 * Upload: the foreground host (MainActivity) owns the system
 * picker — a WebView on the application context cannot launch
 * one itself. When no foreground host exists (background /
 * headless), the chooser falls back to the bridge's pre-supplied
 * file (JobEngine upload step), then cancels honestly.
 */
object JarvisBrowserFileHost {

    /** The foreground Activity's file-chooser launcher. Set by
     *  MainActivity on create, cleared on destroy. */
    fun interface FileChooserHost {
        fun openChooser(
            filePathCallback: ValueCallback<Array<Uri>>?,
            params: WebChromeClient.FileChooserParams?
        ): Boolean
    }

    @Volatile
    var fileChooserHost: FileChooserHost? = null

    /**
     * Enqueue a download via DownloadManager. Returns the download
     * id, or null when the request could not be enqueued (bad URL /
     * no DownloadManager). The file lands in
     * Downloads/Jarvis/<guessed name>.
     */
    fun download(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        contentLength: Long
    ): Long? {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        return runCatching {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                if (userAgent.isNotBlank()) addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription("Jarvis browser download")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Jarvis/$fileName")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            Toast.makeText(
                context.applicationContext,
                "Download ho raha hai: $fileName → Downloads/Jarvis/",
                Toast.LENGTH_LONG
            ).show()
            JarvisStateRepository.addLog("Browser download shuru: $fileName (id=$id)")
            id
        }.onFailure {
            Toast.makeText(
                context.applicationContext,
                "Download nahi ho paya: $fileName",
                Toast.LENGTH_LONG
            ).show()
            JarvisStateRepository.addLog("Browser download fail: $fileName (${it.message})")
        }.getOrNull()
    }
}
