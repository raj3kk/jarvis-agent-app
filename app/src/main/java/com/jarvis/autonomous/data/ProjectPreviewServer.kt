package com.jarvis.autonomous.data

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Localhost preview server — implemented with plain java.net
 * ServerSocket, NO external dependency.
 *
 * Why not NanoHTTPD: the often-quoted coordinate
 * `org.nanohttpd:nanohttpd:2.3.2` does not exist on Maven Central
 * (CI failed at dependency resolution on it), and the 2.3.x module
 * split makes the correct coordinate fragile. A static file server is
 * all Jarvis Projects needs, and it cannot fail at dependency
 * resolution.
 *
 * Binds to 127.0.0.1 ONLY — preview is reachable from this phone
 * (WebView / browser at http://127.0.0.1:8080/<project>/) and never
 * from other devices on the network. No account, no cloud, no card.
 *
 * REPLACED 2026-11-02 (builder audit S7 + preview decision):
 * - Serves EVERY project file through the single source of truth
 *   ([ProjectStore.projectFile]): index.html, style.css, script.js,
 *   nested pages, images and other assets — with correct MIME types
 *   (notably .js as application/javascript, images as image/*, SVG,
 *   fonts) and NO charset suffix on binary types (the old server
 *   appended "; charset=UTF-8" to image/png and friends).
 * - Path mapping is exact for http://127.0.0.1:8080/<project>/ :
 *   a bare "/<project>" (no trailing slash) 301-redirects to
 *   "/<project>/" so relative href/src links resolve inside the
 *   project instead of at the server root; a directory request
 *   serves its index.html; traversal is blocked by canonical-path
 *   containment, not just a ".." substring check.
 * - A missing file returns a READABLE creamy HTML error page that
 *   lists the files that DO exist in the project — never a blank
 *   screen, never a bare "File not found" line.
 * - No start race: [start] reports success/failure instead of
 *   throwing, and [ensureRunningReady] suspends until a real TCP
 *   connect to 127.0.0.1:port succeeds (or times out). The UI awaits
 *   that before it loads any URL, so a "server not up yet" blank
 *   WebView cannot happen. The old UI swallowed start() exceptions
 *   and loaded the URL anyway — the #1 blank-preview path.
 *
 * RENDERING (preview decision 2026-11-02 23:09 IST): this server
 * only SERVES. Rendering is done by the SAME shared WebView engine
 * as the Browser tab — [com.jarvis.autonomous.ui.JarvisBackgroundBrowser.obtainWebView]
 * — borrowed by ProjectsScreen and by BrowserScreen. There is no
 * second preview renderer. See INTEGRATION_NOTES_Builder.md §3 for
 * the exact shared-engine contract and the coordinator wiring.
 */
class ProjectPreviewServer(
    private val context: Context,
    private val port: Int = 8080
) {
    @Volatile
    var isAlive: Boolean = false
        private set

    /** Alias the UI awaits on: true only once the accept loop is up. */
    val isRunning: Boolean get() = isAlive

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** Starts the server. Returns false (never throws) when the port
     *  cannot be bound — the caller must show an error, not load a
     *  URL into a WebView that can only fail blank. */
    @Synchronized
    fun start(): Boolean {
        if (isAlive) return true
        return try {
            val socket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            isAlive = true
            acceptThread = Thread({
                while (isAlive) {
                    try {
                        val client = socket.accept()
                        Thread({ handle(client) }, "jarvis-preview").start()
                    } catch (e: Exception) {
                        if (isAlive) Thread.sleep(50)
                    }
                }
            }, "jarvis-preview-accept").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            isAlive = false
            serverSocket = null
            false
        }
    }

    /**
     * Starts (if needed) and suspends until a TCP connect to
     * 127.0.0.1:[port] actually succeeds, or [timeoutMs] elapses.
     * Returns true only when the server provably answers. The UI
     * MUST await this before loading a preview URL.
     */
    suspend fun startAndAwaitReady(timeoutMs: Long = 3000L): Boolean {
        if (!isAlive) start()
        if (!isAlive) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { s ->
                        s.connect(InetSocketAddress("127.0.0.1", port), 200)
                    }
                    true
                }.getOrDefault(false)
            }
            if (ok) return true
            kotlinx.coroutines.delay(100L)
        }
        return isAlive
    }

    @Synchronized
    fun stop() {
        isAlive = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private data class Response(
        val status: String,
        val mime: String,
        val bytes: ByteArray,
        val location: String? = null
    )

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return
                // Consume headers until the blank line.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                }
                val parts = requestLine.split(" ")
                val rawPath = if (parts.size >= 2) parts[1] else "/"
                val path = URLDecoder.decode(rawPath.substringBefore("?"), "UTF-8")
                val response = route(path)
                val out = socket.getOutputStream()
                // Charset ONLY on text types. Binary (image/font/video)
                // must not carry "; charset=UTF-8" — it is invalid for
                // those types and some renderers mishandle it.
                val isText = response.mime.startsWith("text/") ||
                    response.mime == "application/json" ||
                    response.mime == "application/javascript"
                val contentType = if (isText) {
                    "${response.mime}; charset=UTF-8"
                } else {
                    response.mime
                }
                val header = buildString {
                    append("HTTP/1.1 ${response.status}\r\n")
                    append("Content-Type: $contentType\r\n")
                    if (response.location != null) append("Location: ${response.location}\r\n")
                    append("Content-Length: ${response.bytes.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(response.bytes)
                out.flush()
            }
        } catch (e: Exception) {
            // A single bad preview request must never kill the server.
        }
    }

    private fun route(path: String): Response {
        val segments = path.trim('/').split("/").filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            val links = ProjectStore.listProjects(context).joinToString("") { p ->
                """<li><a href="/${p.name}/">${p.name}</a></li>"""
            }
            val html = """
                <html><body style="font-family:sans-serif;background:#FFF8ED;color:#3B2F2A;padding:24px">
                <h1>Jarvis Projects</h1><ul>$links</ul></body></html>
            """.trimIndent()
            return Response("200 OK", "text/html", html.toByteArray(Charsets.UTF_8))
        }
        val project = segments[0]
        // Bare "/<project>" without trailing slash: redirect, so the
        // browser resolves relative style.css/script.js/images INSIDE
        // the project instead of at the server root (where they 404).
        if (segments.size == 1 && !path.endsWith("/")) {
            return Response(
                "301 Moved Permanently",
                "text/html",
                "<html><body>Redirecting…</body></html>".toByteArray(Charsets.UTF_8),
                location = "/$project/"
            )
        }
        val requested = if (segments.size > 1) segments.drop(1).joinToString("/") else "index.html"
        val dir = ProjectStore.projectDir(context, project)
        // Traversal defence: canonical containment, not a substring
        // check. projectFile() already rejects ".." segments; this
        // second check also covers encoded/odd spellings.
        val file = ProjectStore.projectFile(context, project, requested)
        if (file == null) {
            return errorResponse(project, requested, dir)
        }
        val canonicalDir = runCatching { dir.canonicalFile }.getOrNull()
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull()
        if (canonicalDir == null || canonicalFile == null ||
            !(canonicalFile == canonicalDir || canonicalFile.path.startsWith(canonicalDir.path + File.separator))
        ) {
            return Response("403 Forbidden", "text/html", forbiddenPage().toByteArray(Charsets.UTF_8))
        }
        if (canonicalFile.isDirectory) {
            val index = File(canonicalFile, "index.html")
            if (index.isFile) {
                return Response("200 OK", "text/html", index.readBytes())
            }
            return errorResponse(project, "$requested/index.html", dir)
        }
        if (!canonicalFile.isFile) {
            return errorResponse(project, requested, dir)
        }
        return Response("200 OK", mimeFor(canonicalFile.name), canonicalFile.readBytes())
    }

    /** Readable error page (creamy theme): never a blank screen, and
     *  it tells the user which files actually exist to fix the link. */
    private fun errorResponse(project: String, missing: String, dir: File): Response {
        val available = ProjectStore.listProjects(context)
            .firstOrNull { it.name == dir.name }?.files
            ?.joinToString("") { f -> """<li><a href="/${dir.name}/$f">$f</a></li>""" }
            ?.ifBlank { "<li>(is project me koi file nahi hai)</li>" }
            ?: "<li>(project nahi mila)</li>"
        val html = """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>File nahi mili — $project</title></head>
            <body style="font-family:system-ui,sans-serif;background:#FFF8ED;color:#3B2F2A;padding:24px">
            <h1 style="color:#B85C38">Preview me ye file nahi mili</h1>
            <p><b>Project:</b> $project<br><b>Missing file:</b> $missing</p>
            <p>Jarvis ne shayad ye file abhi banayi nahi, ya link me galat naam hai.
            Projects tab me file list check karo, ya Jarvis ko bolo ki is file ko dobara banaye.</p>
            <h2>Is project me maujood files:</h2>
            <ul>$available</ul>
            <p><a href="/${dir.name}/">Project home (index.html) kholo</a></p>
            </body></html>
        """.trimIndent()
        return Response("404 Not Found", "text/html", html.toByteArray(Charsets.UTF_8))
    }

    private fun forbiddenPage(): String = """
        <!DOCTYPE html><html><head><meta charset="UTF-8"><title>Forbidden</title></head>
        <body style="font-family:system-ui,sans-serif;background:#FFF8ED;color:#3B2F2A;padding:24px">
        <h1 style="color:#B85C38">Forbidden</h1>
        <p>Ye path project folder ke bahar jata hai, isliye block kar diya gaya.</p>
        </body></html>
    """.trimIndent()

    private fun mimeFor(fileName: String): String = when {
        fileName.endsWith(".html") || fileName.endsWith(".htm") -> "text/html"
        fileName.endsWith(".css") -> "text/css"
        fileName.endsWith(".js") || fileName.endsWith(".mjs") -> "application/javascript"
        fileName.endsWith(".json") -> "application/json"
        fileName.endsWith(".png") -> "image/png"
        fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
        fileName.endsWith(".gif") -> "image/gif"
        fileName.endsWith(".webp") -> "image/webp"
        fileName.endsWith(".svg") -> "image/svg+xml"
        fileName.endsWith(".ico") -> "image/x-icon"
        fileName.endsWith(".mp4") -> "video/mp4"
        fileName.endsWith(".woff") -> "font/woff"
        fileName.endsWith(".woff2") -> "font/woff2"
        fileName.endsWith(".ttf") -> "font/ttf"
        fileName.endsWith(".txt") -> "text/plain"
        else -> "application/octet-stream"
    }

    companion object {
        private var instance: ProjectPreviewServer? = null

        /**
         * Best-effort start (old callers). Prefer [ensureRunningReady]:
         * this one can return a server whose [isRunning] is false when
         * the port could not be bound — check [isRunning] before
         * loading any URL.
         */
        @Synchronized
        fun ensureRunning(context: Context): ProjectPreviewServer {
            val existing = instance
            if (existing != null && existing.isAlive) return existing
            val server = ProjectPreviewServer(context.applicationContext)
            server.start()
            instance = server
            return server
        }

        /**
         * The UI entry point: returns (server, ready). Only load a
         * preview URL when ready == true; otherwise show the error
         * state — never a WebView pointed at a dead server.
         */
        suspend fun ensureRunningReady(
            context: Context,
            timeoutMs: Long = 3000L
        ): Pair<ProjectPreviewServer, Boolean> {
            val existing = instance
            if (existing != null && existing.isAlive) return existing to true
            val server = ProjectPreviewServer(context.applicationContext)
            instance = server
            val ready = server.startAndAwaitReady(timeoutMs)
            return server to ready
        }

        @Synchronized
        fun stop() {
            runCatching { instance?.stop() }
            instance = null
        }
    }
}
