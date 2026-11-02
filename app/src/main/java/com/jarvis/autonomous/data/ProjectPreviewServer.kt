package com.jarvis.autonomous.data

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Localhost preview server — implemented with plain java.net
 * ServerSocket, NO external dependency.
 *
 * Why not NanoHTTPD: the often-quoted coordinate
 * `org.nanohttpd:nanohttpd:2.3.2` does not exist on Maven Central
 * (CI failed at dependency resolution on it), and the 2.3.x module
 * split makes the correct coordinate fragile. A ~100-line static
 * file server is all Jarvis Projects needs, and it cannot fail
 * at dependency resolution.
 *
 * Binds to 127.0.0.1 ONLY — preview is reachable from this phone
 * (WebView / browser at http://127.0.0.1:8080/<project>/) and never
 * from other devices on the network. No account, no cloud, no card.
 */
class ProjectPreviewServer(
    private val context: Context,
    private val port: Int = 8080
) {
    @Volatile
    var isAlive: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (isAlive) return
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
    }

    fun stop() {
        isAlive = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

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
                val (status, mime, bytes) = route(path)
                val out = socket.getOutputStream()
                val header = "HTTP/1.1 $status\r\nContent-Type: $mime; charset=UTF-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            // A single bad preview request must never kill the server.
        }
    }

    private fun route(path: String): Triple<String, String, ByteArray> {
        val segments = path.trim('/').split("/").filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            val links = ProjectStore.listProjects(context).joinToString("") { p ->
                """<li><a href="/${p.name}/">${p.name}</a></li>"""
            }
            val html = """
                <html><body style="font-family:sans-serif;background:#FFF8ED;color:#3B2F2A;padding:24px">
                <h1>Jarvis Projects</h1><ul>$links</ul></body></html>
            """.trimIndent()
            return Triple("200 OK", "text/html", html.toByteArray(Charsets.UTF_8))
        }
        val project = segments[0]
        val fileName = if (segments.size > 1) segments.drop(1).joinToString("/") else "index.html"
        if (fileName.contains("..")) {
            return Triple("403 Forbidden", "text/plain", "Forbidden".toByteArray())
        }
        val file = File(ProjectStore.projectDir(context, project), fileName)
        if (!file.exists() || !file.isFile) {
            return Triple("404 Not Found", "text/plain", "File not found: $fileName".toByteArray())
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
        return Triple("200 OK", mime, file.readBytes())
    }

    companion object {
        private var instance: ProjectPreviewServer? = null

        @Synchronized
        fun ensureRunning(context: Context): ProjectPreviewServer {
            val existing = instance
            if (existing != null && existing.isAlive) return existing
            val server = ProjectPreviewServer(context.applicationContext)
            server.start()
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
