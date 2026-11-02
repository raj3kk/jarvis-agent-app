package com.jarvis.autonomous.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import java.io.File

/**
 * Jarvis Projects — a tiny on-device website/app builder store.
 *
 * Rules the user asked for, enforced here:
 * - Every project is its OWN folder; nothing is mixed into one folder.
 * - Files are saved automatically on every write (no "save" step the
 *   user can forget).
 * - The working copy lives in the app's external files dir (always
 *   writable, used by the localhost preview server).
 * - Every write is ALSO exported to Documents/JarvisProjects/<project>/
 *   via MediaStore, so the user can open the real folder in the Files
 *   app without any special permission or card/server.
 * - Preview is served by the built-in ServerSocket server on 127.0.0.1 only (never exposed
 *   to the network) and rendered in the app's WebView, so a website
 *   Jarvis builds is viewable instantly at a localhost link.
 *
 * Stack (all free, open-source, serverless, no account):
 * - HTML + CSS + JavaScript, plain files (no build step, no bundler).
 * - Plain java.net ServerSocket for the localhost server (no dependency).
 * - WebView (built into Android) for preview.
 */
object ProjectStore {

    data class Project(val name: String, val files: List<String>)

    fun rootDir(context: Context): File =
        File(context.getExternalFilesDir(null), "JarvisProjects").apply { mkdirs() }

    fun projectDir(context: Context, name: String): File =
        File(rootDir(context), sanitize(name)).apply { mkdirs() }

    fun listProjects(context: Context): List<Project> {
        val root = rootDir(context)
        return (root.listFiles()?.filter { it.isDirectory } ?: emptyList())
            .sortedBy { it.name.lowercase() }
            .map { dir ->
                Project(
                    name = dir.name,
                    files = (dir.listFiles()?.filter { it.isFile } ?: emptyList())
                        .map { it.name }.sorted()
                )
            }
    }

    fun createProject(context: Context, rawName: String, indexHtml: String?): File {
        val dir = projectDir(context, rawName)
        if (dir.listFiles().isNullOrEmpty()) {
            writeFile(context, dir.name, "index.html", indexHtml ?: websiteTemplate(dir.name))
            writeFile(context, dir.name, "style.css", defaultCss())
            writeFile(context, dir.name, "script.js", defaultJs(dir.name))
        }
        return dir
    }

    fun readFile(context: Context, project: String, fileName: String): String {
        val file = File(projectDir(context, project), fileName)
        return if (file.exists()) file.readText() else ""
    }

    /** Saves to the working copy AND exports to Documents (best effort). */
    fun writeFile(context: Context, project: String, fileName: String, content: String) {
        val dir = projectDir(context, project)
        val file = File(dir, fileName)
        file.parentFile?.mkdirs()
        file.writeText(content)
        exportToDocuments(context, project, fileName, content)
    }

    fun deleteProject(context: Context, project: String): Boolean =
        projectDir(context, project).deleteRecursively()

    fun localhostUrl(project: String, port: Int = 8080): String =
        "http://127.0.0.1:$port/${sanitize(project)}/"

    // ---- Documents export via MediaStore (user-visible folder) ----

    private fun exportToDocuments(
        context: Context,
        project: String,
        fileName: String,
        content: String
    ) {
        runCatching {
            val resolver = context.contentResolver
            val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/JarvisProjects/${sanitize(project)}/"
            val collection =
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            // Find an existing file with this name in this folder, if any.
            var existing: Uri? = null
            resolver.query(
                collection,
                arrayOf(BaseColumns._ID),
                "${MediaColumns.DISPLAY_NAME}=? AND ${MediaColumns.RELATIVE_PATH}=?",
                arrayOf(fileName, relativePath),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    existing = Uri.withAppendedPath(collection, id.toString())
                }
            }
            val uri = existing ?: run {
                val values = ContentValues().apply {
                    put(MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaColumns.MIME_TYPE, mimeFor(fileName))
                    put(MediaColumns.RELATIVE_PATH, relativePath)
                }
                resolver.insert(collection, values)
            } ?: return
            resolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun mimeFor(fileName: String): String = when {
        fileName.endsWith(".html") -> "text/html"
        fileName.endsWith(".css") -> "text/css"
        fileName.endsWith(".js") -> "text/javascript"
        fileName.endsWith(".json") -> "application/json"
        else -> "text/plain"
    }

    private fun sanitize(name: String): String {
        val clean = name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-')
        return clean.ifBlank { "jarvis-project" }.take(60)
    }

    // ---- Default website template (creamy, mobile-friendly, no build) ----

    fun websiteTemplate(title: String): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <title>$title</title>
          <link rel="stylesheet" href="style.css">
        </head>
        <body>
          <header class="hero">
            <h1>$title</h1>
            <p>Jarvis ne banaya — phone par, bina server ke.</p>
            <button id="helloBtn">Hello Jarvis 👋</button>
            <p id="helloMsg"></p>
          </header>
          <main>
            <section class="card">
              <h2>About</h2>
              <p>Is section ko Jarvis se badalwa sakte ho — bas bolo "is website me ye section add kar do".</p>
            </section>
            <section class="card">
              <h2>Features</h2>
              <ul>
                <li>Fast, mobile-friendly, no build step</li>
                <li>HTML + CSS + JS — sirf 3 files</li>
                <li>Localhost preview + Documents folder me save</li>
              </ul>
            </section>
          </main>
          <footer>Made with Jarvis on Android</footer>
          <script src="script.js"></script>
        </body>
        </html>
    """.trimIndent()

    private fun defaultCss(): String = """
        * { box-sizing: border-box; }
        body { margin: 0; font-family: system-ui, sans-serif; background: #FFF8ED; color: #3B2F2A; }
        .hero { padding: 48px 20px; text-align: center; background: #FFF1DC; }
        .hero h1 { margin: 0 0 8px; font-size: 32px; }
        .hero button { padding: 12px 22px; border: 0; border-radius: 24px; background: #B85C38; color: #fff; font-size: 16px; }
        main { max-width: 720px; margin: 0 auto; padding: 20px; }
        .card { background: #fff; border-radius: 16px; padding: 20px; margin-bottom: 16px; box-shadow: 0 2px 10px rgba(0,0,0,0.06); }
        footer { text-align: center; padding: 20px; color: #8A7A70; }
    """.trimIndent()

    private fun defaultJs(title: String): String = """
        document.getElementById('helloBtn').addEventListener('click', function () {
          document.getElementById('helloMsg').textContent = 'Hello! Ye "$title" Jarvis ke phone se chal rahi hai.';
        });
    """.trimIndent()
}
