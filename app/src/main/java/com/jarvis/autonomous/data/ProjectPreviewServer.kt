package com.jarvis.autonomous.data

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *   (notably .js as application/javascript, images as image/png
 *   and friends, SVG, fonts) and NO charset suffix on binary types
 *   (the old server
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
    // B-L12: bounded pool — one thread per preview connection used to
    // let a burst of asset requests spawn unbounded threads.
    private var acceptExecutor: ExecutorService? = null
    private val maxPoolThreads = 8

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
            val counter = AtomicInteger(0)
            acceptExecutor = Executors.newFixedThreadPool(
                maxPoolThreads,
                ThreadFactory { runnable ->
                    Thread(runnable, "jarvis-preview-${counter.incrementAndGet()}").apply {
                        isDaemon = true
                    }
                }
            )
            acceptThread = Thread({
                while (isAlive) {
                    try {
                        val client = socket.accept()
                        try {
                            acceptExecutor?.execute { handle(client) }
                        } catch (e: RejectedExecutionException) {
                            runCatching { client.close() }
                        }
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
     * 1.4.8 (G8 F1): stops the server. The 1.4.6 debloat removed
     * the old stop() as uncalled; it is back WITH callers now
     * (ProjectsScreen stopPreview + delete-of-previewed). The
     * accept loop exits because closing the socket unblocks
     * accept(); pool threads are daemon, so shutdown() is
     * enough (no await that could stall the UI thread).
     * Benign race note: a stop racing an in-flight start can
     * be undone by that start completing — the next Stop tap
     * closes it, and the port is localhost-only in all cases.
     */
    @Synchronized
    fun stop() {
        if (!isAlive && serverSocket == null) return
        isAlive = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptExecutor?.shutdown() }
        acceptExecutor = null
        acceptThread = null
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
        // B-L1: a timeout is NOT liveness. The old code returned
        // isAlive here, so a bound-but-unanswering server reported
        // ready and the UI loaded a URL that could only fail blank.
        return false
    }

    /**
     * A response is either small in-memory bytes (error pages, the
     * redirect stub) or a file streamed in 8 KB chunks (B-L12: never
     * whole-file readBytes for assets — a large video/image used to be
     * fully loaded into RAM before a single byte was written).
     */
    private data class Response(
        val status: String,
        val mime: String,
        val bytes: ByteArray? = null,
        val file: File? = null,
        val contentLength: Long = bytes?.size?.toLong() ?: 0L,
        val contentRange: String? = null,
        val location: String? = null
    )

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                // B-L12: a stalled client must not hold a pool thread
                // forever.
                runCatching { socket.soTimeout = 15_000 }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return
                // Consume headers until the blank line (bounded, so a
                // header flood cannot spin here forever). Only Range
                // is used; everything else is ignored.
                var rangeHeader: String? = null
                var headerLines = 0
                while (headerLines < 100) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    headerLines++
                    val lower = line.lowercase()
                    if (lower.startsWith("range:")) {
                        rangeHeader = line.substringAfter(":", "").trim()
                    }
                }
                val parts = requestLine.split(" ")
                val rawPath = if (parts.size >= 2) parts[1] else "/"
                val path = URLDecoder.decode(rawPath.substringBefore("?"), "UTF-8")
                val response = route(path, rangeHeader)
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
                // B-S1: header values are never trusted raw — strip
                // CR/LF so a decoded %0D%0A in a project name cannot
                // inject a second header into the 301 Location.
                val safeLocation = response.location
                    ?.replace("\r", "")?.replace("\n", "")
                val header = buildString {
                    append("HTTP/1.1 ${response.status}\r\n")
                    append("Content-Type: $contentType\r\n")
                    if (safeLocation != null) append("Location: $safeLocation\r\n")
                    if (response.contentRange != null) {
                        append("Content-Range: ${response.contentRange}\r\n")
                    }
                    append("Content-Length: ${response.contentLength}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(header.toByteArray(Charsets.UTF_8))
                val file = response.file
                if (file != null) {
                    // Streamed (optionally Range-sliced) — no readBytes.
                    val range = parseRange(rangeHeader, file.length())
                    val start = range?.first ?: 0L
                    var remaining = range?.second ?: file.length()
                    FileInputStream(file).use { input ->
                        var skipped = 0L
                        while (skipped < start) {
                            val n = input.skip(start - skipped)
                            if (n <= 0L) break
                            skipped += n
                        }
                        val buffer = ByteArray(8192)
                        while (remaining > 0) {
                            val want = minOf(buffer.size.toLong(), remaining).toInt()
                            val read = input.read(buffer, 0, want)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                } else {
                    response.bytes?.let { out.write(it) }
                }
                out.flush()
            }
        } catch (e: Exception) {
            // A single bad preview request must never kill the server.
        }
    }

    /**
     * Parses a single "bytes=start-end" range. Returns null when the
     * header is absent/malformed (caller serves the whole file), or a
     * (start, length) pair when it parses. An unsatisfiable start is
     * reported by [route] as 416, not silently served whole.
     */
    private fun parseRange(rangeHeader: String?, fileLength: Long): Pair<Long, Long>? {
        if (rangeHeader == null) return null
        val value = rangeHeader.trim()
        if (!value.startsWith("bytes=")) return null
        val spec = value.removePrefix("bytes=").trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        val start = startText.toLongOrNull() ?: return null
        if (start < 0) return null
        val end = if (endText.isEmpty()) {
            fileLength - 1
        } else {
            endText.toLongOrNull() ?: return null
        }
        if (end < start) return null
        // Clamp the end to the file; the start stays unsatisfiable so
        // route() can answer 416 instead of a truncated 200.
        return start to (minOf(end, fileLength - 1) - start + 1).coerceAtLeast(0L)
    }

    private fun route(path: String, rangeHeader: String? = null): Response {
        val segments = path.trim('/').split("/").filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            val links = ProjectStore.listProjects(context).joinToString("") { p ->
                val safe = escapeHtml(p.name)
                """<li><a href="/$safe/">$safe</a></li>"""
            }
            val html = """
                <html><body style="font-family:sans-serif;background:#FFF8ED;color:#3B2F2A;padding:24px">
                <h1>Jarvis Projects</h1><ul>$links</ul></body></html>
            """.trimIndent()
            return Response("200 OK", "text/html", bytes = html.toByteArray(Charsets.UTF_8))
        }
        // B-S1: validate the project segment BEFORE any projectDir()
        // call. "." / ".." and any segment failing sanitizeFileName
        // are rejected here — the containment root below is never
        // derived from unvalidated input, and a GET can never mkdirs()
        // a phantom project (projectDirNoCreate, not projectDir).
        val project = segments[0]
        if (project == "." || project == ".." ||
            ProjectStore.sanitizeFileName(project) == null
        ) {
            return Response("403 Forbidden", "text/html", bytes = forbiddenPage().toByteArray(Charsets.UTF_8))
        }
        // Bare "/<project>" without trailing slash: redirect, so the
        // browser resolves relative style.css/script.js/images INSIDE
        // the project instead of at the server root (where they 404).
        if (segments.size == 1 && !path.endsWith("/")) {
            return Response(
                "301 Moved Permanently",
                "text/html",
                bytes = "<html><body>Redirecting…</body></html>".toByteArray(Charsets.UTF_8),
                location = "/$project/"
            )
        }
        val requested = if (segments.size > 1) segments.drop(1).joinToString("/") else "index.html"
        // No mkdirs on GET: a request for a missing project must 404,
        // not create an empty folder that then lists as a project.
        val dir = ProjectStore.projectDirNoCreate(context, project)
        val safeRequested = ProjectStore.sanitizeFileName(requested)
        if (safeRequested == null) {
            return errorResponse(project, requested, dir)
        }
        val file = File(dir, safeRequested)
        // Traversal defence: canonical containment, not a substring
        // check. This second check also covers encoded/odd spellings.
        val canonicalDir = runCatching { dir.canonicalFile }.getOrNull()
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull()
        if (canonicalDir == null || canonicalFile == null ||
            !(canonicalFile == canonicalDir || canonicalFile.path.startsWith(canonicalDir.path + File.separator))
        ) {
            return Response("403 Forbidden", "text/html", bytes = forbiddenPage().toByteArray(Charsets.UTF_8))
        }
        if (canonicalFile.isDirectory) {
            val index = File(canonicalFile, "index.html")
            if (index.isFile) {
                return Response("200 OK", "text/html", file = index, contentLength = index.length())
            }
            return errorResponse(project, "$requested/index.html", dir)
        }
        if (!canonicalFile.isFile) {
            return errorResponse(project, requested, dir)
        }
        val length = canonicalFile.length()
        val range = parseRange(rangeHeader, length)
        if (rangeHeader != null && range != null && range.first >= length) {
            return Response(
                "416 Range Not Satisfiable",
                "text/html",
                bytes = ByteArray(0),
                contentLength = 0L,
                contentRange = "bytes */$length"
            )
        }
        return if (range != null && rangeHeader != null) {
            Response(
                "206 Partial Content",
                mimeFor(canonicalFile.name),
                file = canonicalFile,
                contentLength = range.second,
                contentRange = "bytes ${range.first}-${range.first + range.second - 1}/$length"
            )
        } else {
            Response("200 OK", mimeFor(canonicalFile.name), file = canonicalFile, contentLength = length)
        }
    }

    /** Readable error page (creamy theme): never a blank screen, and
     *  it tells the user which files actually exist to fix the link.
     *  B-S1: every interpolated value is HTML-escaped — the project
     *  name and the missing path both come from the raw URL. */
    private fun errorResponse(project: String, missing: String, dir: File): Response {
        val safeProject = escapeHtml(project)
        val safeMissing = escapeHtml(missing)
        val safeDirName = escapeHtml(dir.name)
        val available = ProjectStore.listProjects(context)
            .firstOrNull { it.name == dir.name }?.files
            ?.joinToString("") { f ->
                val safe = escapeHtml(f)
                """<li><a href="/$safeDirName/$safe">$safe</a></li>"""
            }
            ?.ifBlank { "<li>(is project me koi file nahi hai)</li>" }
            ?: "<li>(project nahi mila)</li>"
        val html = """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>File nahi mili — $safeProject</title></head>
            <body style="font-family:system-ui,sans-serif;background:#FFF8ED;color:#3B2F2A;padding:24px">
            <h1 style="color:#B85C38">Preview me ye file nahi mili</h1>
            <p><b>Project:</b> $safeProject<br><b>Missing file:</b> $safeMissing</p>
            <p>Jarvis ne shayad ye file abhi banayi nahi, ya link me galat naam hai.
            Projects tab me file list check karo, ya Jarvis ko bolo ki is file ko dobara banaye.</p>
            <h2>Is project me maujood files:</h2>
            <ul>$available</ul>
            <p><a href="/$safeDirName/">Project home (index.html) kholo</a></p>
            </body></html>
        """.trimIndent()
        return Response("404 Not Found", "text/html", bytes = html.toByteArray(Charsets.UTF_8))
    }

    private fun forbiddenPage(): String = """
        <!DOCTYPE html><html><head><meta charset="UTF-8"><title>Forbidden</title></head>
        <body style="font-family:system-ui,sans-serif;background:#FFF8ED;color:#3B2F2A;padding:24px">
        <h1 style="color:#B85C38">Forbidden</h1>
        <p>Ye path project folder ke bahar jata hai, isliye block kar diya gaya.</p>
        </body></html>
    """.trimIndent()

    /** B-S1: escapes raw URL / stored names for HTML text and
     *  attribute positions. Ampersand first, so escaping never
     *  double-encodes its own entities. */
    private fun escapeHtml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun mimeFor(fileName: String): String {
        // B-L11: case-insensitive mapping — "Photo.PNG" used to fall
        // through to application/octet-stream.
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".js") || lower.endsWith(".mjs") -> "application/javascript"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".avif") -> "image/avif"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".ico") -> "image/x-icon"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".ogg") -> "audio/ogg"
            lower.endsWith(".m4a") -> "audio/mp4"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".woff") -> "font/woff"
            lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".ttf") -> "font/ttf"
            lower.endsWith(".txt") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    companion object {
        @Volatile
        private var instance: ProjectPreviewServer? = null
        // B-L2: serialises server creation. A Mutex (not @Synchronized)
        // is required because startAndAwaitReady suspends; holding a
        // JVM monitor across a suspension would be illegal. The second
        // caller waits, then finds the winner's live instance and
        // returns ready=true instead of overwriting the singleton or
        // reporting failure for a server that is actually serving.
        private val startMutex = Mutex()

        /**
         * The UI entry point: returns (server, ready). Only load a
         * preview URL when ready == true; otherwise show the error
         * state — never a WebView pointed at a dead server.
         */
        suspend fun ensureRunningReady(
            context: Context,
            timeoutMs: Long = 3000L
        ): Pair<ProjectPreviewServer, Boolean> = startMutex.withLock {
            val existing = instance
            if (existing != null && existing.isAlive) return@withLock existing to true
            val server = ProjectPreviewServer(context.applicationContext)
            instance = server
            val ready = server.startAndAwaitReady(timeoutMs)
            if (!ready && instance === server) {
                // A failed start must not leave a dead singleton behind
                // for the next caller to mistake for a live server.
                instance = null
            }
            server to ready
        }

        /**
         * 1.4.8 (G8 F1): stops the singleton server, if any, and
         * clears the singleton so the next Preview starts fresh.
         * Safe to call when nothing is running.
         */
        fun stopInstance() {
            val current = instance
            if (current != null) {
                current.stop()
                if (instance === current) instance = null
            }
        }

    }
}
