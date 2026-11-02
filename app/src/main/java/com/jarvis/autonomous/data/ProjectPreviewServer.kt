package com.jarvis.autonomous.data

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * Localhost preview server (NanoHTTPD, BSD licence).
 * Binds to 127.0.0.1 ONLY — the preview is reachable from this phone
 * (WebView / browser at http://127.0.0.1:8080/<project>/) and never
 * from other devices on the network. No account, no cloud, no card.
 */
class ProjectPreviewServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimStart('/')
        val parts = uri.split("/").filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            val projects = ProjectStore.listProjects(context)
            val links = projects.joinToString("") { p ->
                """<li><a href="/${p.name}/">${p.name}</a></li>"""
            }
            val html = """
                <html><body style="font-family:sans-serif;background:#FFF8ED;color:#3B2F2A;padding:24px">
                <h1>Jarvis Projects</h1><ul>$links</ul></body></html>
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
        val project = parts[0]
        val fileName = if (parts.size > 1) parts.drop(1).joinToString("/") else "index.html"
        // Never serve outside the project folder (no ../ escapes).
        if (fileName.contains("..")) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
        }
        val file = File(ProjectStore.projectDir(context, project), fileName)
        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found: $fileName")
        }
        val mime = when {
            fileName.endsWith(".html") -> "text/html"
            fileName.endsWith(".css") -> "text/css"
            fileName.endsWith(".js") -> "text/javascript"
            fileName.endsWith(".json") -> "application/json"
            fileName.endsWith(".png") -> "image/png"
            fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
            else -> "text/plain"
        }
        return newChunkedResponse(Response.Status.OK, mime, FileInputStream(file))
    }

    companion object {
        private var instance: ProjectPreviewServer? = null

        @Synchronized
        fun ensureRunning(context: Context): ProjectPreviewServer {
            val existing = instance
            if (existing != null && existing.isAlive) return existing
            val server = ProjectPreviewServer(context.applicationContext)
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            instance = server
            return server
        }

        @Synchronized
        fun stop() {
            runCatching { instance?.stop() }
            instance = null
        }
    }
}
