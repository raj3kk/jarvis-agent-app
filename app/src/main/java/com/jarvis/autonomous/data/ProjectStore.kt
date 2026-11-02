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
 * SINGLE SOURCE OF TRUTH (fixed 2026-11-02, builder audit S7):
 * The app's external files dir (`.../JarvisProjects/<project>/`) is the
 * ONLY place project files are ever READ from — by the editor, by the
 * preview server, and by validation. The MediaStore export to
 * Documents/JarvisProjects/<project>/ is a WRITE-ONLY copy for user
 * visibility in the Files app; it is NEVER read back. Before this fix
 * the two copies could silently disagree (a failed/stale Documents
 * export, a delete that left the Documents copy behind) and there was
 * no code path that could tell which copy was current. Now there is
 * exactly one read path: [projectFile] + [readFileStatus] / [readFile],
 * and both the editor and the preview server go through it.
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

    /** Result of a project-file read that distinguishes the three cases
     *  the old `readFile` collapsed into one blank string: a real file
     *  with content, a missing file, and an existing-but-empty file.
     *  The editor must NEVER show a blank editor for Missing/Empty —
     *  it shows an explicit error state instead. */
    sealed interface FileRead {
        data class Ok(val content: String) : FileRead
        data object Missing : FileRead
        data object Empty : FileRead
    }

    /** Validation result for a built project (used by the engine's
     *  build-summary step — see INTEGRATION_NOTES_Builder.md §6). */
    data class ProjectValidation(
        val ok: Boolean,
        val missingFiles: List<String>,
        val emptyFiles: List<String>,
        val referencedFiles: List<String>
    )

    fun rootDir(context: Context): File =
        File(context.getExternalFilesDir(null), "JarvisProjects").apply { mkdirs() }

    /** Canonical project-name form. Every caller (store, editor,
     *  LLM parsing, engine) MUST go through this one function — the
     *  old editor-side regex copy differed (no trailing-dash trim,
     *  no 60-char cap, no blank fallback), so the editor could select
     *  a name that matched no listed project and show only the
     *  index.html chip. */
    fun sanitizeProjectName(name: String): String {
        val clean = name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-')
        return clean.ifBlank { "jarvis-project" }.take(60)
    }

    /** Canonical file-name form, or null when the name is unsafe.
     *  Keeps simple sub-paths ("pages/about.html") so multi-page
     *  builds work, but rejects absolute paths, backslashes-as-escape
     *  tricks and any ".." segment (directory traversal on write). */
    fun sanitizeFileName(raw: String): String? {
        var name = raw.trim().replace('\\', '/')
        while (name.startsWith("/")) name = name.drop(1)
        while (name.startsWith("./")) name = name.drop(2)
        if (name.isBlank() || name.endsWith("/")) return null
        val segments = name.split("/").filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." || it == "." }) return null
        if (segments.any { !it.matches(Regex("[A-Za-z0-9._-]+")) }) return null
        return segments.joinToString("/")
    }

    fun projectDir(context: Context, name: String): File =
        File(rootDir(context), sanitizeProjectName(name)).apply { mkdirs() }

    /**
     * THE single read path. Returns the canonical working-copy file
     * for [project]/[fileName], or null when the file name is unsafe.
     * The preview server and the editor both resolve files through
     * this function (the server additionally checks canonical
     * containment before serving). Never resolve a project file by
     * hand-building a File anywhere else.
     */
    fun projectFile(context: Context, project: String, fileName: String): File? {
        val safe = sanitizeFileName(fileName) ?: return null
        return File(projectDir(context, project), safe)
    }

    fun listProjects(context: Context): List<Project> {
        val root = rootDir(context)
        return (root.listFiles()?.filter { it.isDirectory } ?: emptyList())
            .sortedBy { it.name.lowercase() }
            .map { dir ->
                // All files, including nested pages, as paths relative
                // to the project dir — the old top-level-only listing
                // hid every nested file from the editor chips.
                val files = dir.walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(dir).path.replace('\\', '/') }
                    .sorted()
                    .toList()
                Project(name = dir.name, files = files)
            }
    }

    /**
     * Create (or re-build) a project. Old bug: when the project dir
     * already existed, this wrote NOTHING — a re-build, or a
     * CREATE_PROJECT for an existing name, silently dropped the new
     * index.html and the user kept seeing the old site.
     * New semantics: a caller-supplied index.html ALWAYS overwrites
     * index.html; default style.css/script.js are written ONLY when
     * missing, so a re-build never clobbers custom CSS/JS. A bare
     * create (null index) on an existing project leaves it untouched.
     */
    fun createProject(context: Context, rawName: String, indexHtml: String?): File {
        val dir = projectDir(context, rawName)
        val name = dir.name
        if (!indexHtml.isNullOrBlank()) {
            writeFile(context, name, "index.html", indexHtml)
        } else if (dir.listFiles().isNullOrEmpty()) {
            writeFile(context, name, "index.html", websiteTemplate(name))
        }
        if (!File(dir, "style.css").exists()) {
            writeFile(context, name, "style.css", defaultCss())
        }
        if (!File(dir, "script.js").exists()) {
            writeFile(context, name, "script.js", defaultJs(name))
        }
        recordLastTouched(context, name)
        return dir
    }

    /** Single read path for content. Missing and empty are NOT the
     *  same as a blank file — callers that show content to the user
     *  must use [readFileStatus]; this compat wrapper keeps the old
     *  "" contract for callers that only need a best-effort string. */
    fun readFile(context: Context, project: String, fileName: String): String =
        when (val r = readFileStatus(context, project, fileName)) {
            is FileRead.Ok -> r.content
            else -> ""
        }

    fun readFileStatus(context: Context, project: String, fileName: String): FileRead {
        val file = projectFile(context, project, fileName) ?: return FileRead.Missing
        if (!file.exists() || !file.isFile) return FileRead.Missing
        val content = runCatching { file.readText() }.getOrNull() ?: return FileRead.Missing
        return if (content.isBlank()) FileRead.Empty else FileRead.Ok(content)
    }

    fun fileExists(context: Context, project: String, fileName: String): Boolean =
        readFileStatus(context, project, fileName) is FileRead.Ok

    /**
     * Saves to the working copy (source of truth) AND exports to
     * Documents (write-only copy, best effort). Returns false when
     * the file name is unsafe or the working-copy write failed —
     * callers must surface that instead of claiming "Saved".
     * An empty write is refused for the three template files: a blank
     * LLM output must never silently wipe a good file (the engine's
     * validation step is the second guard; this is the first).
     */
    fun writeFile(context: Context, project: String, fileName: String, content: String): Boolean {
        val safe = sanitizeFileName(fileName) ?: return false
        if (content.isBlank() && safe in setOf("index.html", "style.css", "script.js")) {
            return false
        }
        val dir = projectDir(context, project)
        val file = File(dir, safe)
        file.parentFile?.mkdirs()
        val ok = runCatching { file.writeText(content) }.isSuccess
        if (!ok) return false
        exportToDocuments(context, dir.name, safe, content)
        recordLastTouched(context, dir.name)
        return true
    }

    fun deleteProject(context: Context, project: String): Boolean {
        val name = sanitizeProjectName(project)
        val deleted = projectDir(context, name).deleteRecursively()
        // Best effort: remove the Documents copy too, otherwise the
        // user keeps seeing a deleted project in the Files app.
        runCatching {
            val resolver = context.contentResolver
            val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/JarvisProjects/$name/"
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            resolver.query(
                collection,
                arrayOf(BaseColumns._ID),
                "${MediaColumns.RELATIVE_PATH}=?",
                arrayOf(relativePath),
                null
            )?.use { cursor ->
                val ids = mutableListOf<Long>()
                while (cursor.moveToNext()) ids.add(cursor.getLong(0))
                ids.forEach { id ->
                    runCatching {
                        resolver.delete(Uri.withAppendedPath(collection, id.toString()), null, null)
                    }
                }
            }
        }
        return deleted
    }

    fun localhostUrl(project: String, port: Int = 8080): String =
        "http://127.0.0.1:$port/${sanitizeProjectName(project)}/"

    // ---- Most-recently-touched project (voice-modify resolution) ----

    private fun lastTouchedFile(context: Context): File =
        File(rootDir(context), ".last-project")

    private fun recordLastTouched(context: Context, sanitizedName: String) {
        runCatching { lastTouchedFile(context).writeText(sanitizedName) }
    }

    /** The project Jarvis touched most recently, or null. The engine
     *  uses this to resolve a bare voice edit ("is button ka rang
     *  badal do") with no project name — see
     *  INTEGRATION_NOTES_Builder.md §5. Never a user-visible file:
     *  it lives in the app-private root, not inside any project dir,
     *  so it never appears as a project file chip. */
    fun lastTouchedProject(context: Context): String? {
        val name = runCatching { lastTouchedFile(context).readText().trim() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        return if (File(rootDir(context), sanitizeProjectName(name)).isDirectory) {
            sanitizeProjectName(name)
        } else {
            null
        }
    }

    // ---- Build validation (engine build-summary step) ----

    /** Extracts same-folder asset references (href/src) from HTML,
     *  skipping absolute URLs, protocol-relative URLs, data: URIs
     *  and pure anchors. */
    fun referencedAssets(html: String): List<String> {
        val regex = Regex("""(?:href|src)\s*=\s*["']([^"']+)["']""")
        return regex.findAll(html)
            .map { it.groupValues[1].substringBefore("?").substringBefore("#").trim() }
            .filter {
                it.isNotBlank() && !it.startsWith("http://") && !it.startsWith("https://") &&
                    !it.startsWith("//") && !it.startsWith("data:")
            }
            .mapNotNull { sanitizeFileName(it) }
            .distinct()
            .toList()
    }

    /** Validates a built project against the source of truth:
     *  index.html exists and is non-empty, and every file it
     *  references exists and is non-empty. */
    fun validateProject(context: Context, project: String): ProjectValidation {
        val name = sanitizeProjectName(project)
        val missing = mutableListOf<String>()
        val empty = mutableListOf<String>()
        val index = readFileStatus(context, name, "index.html")
        val referenced = if (index is FileRead.Ok) referencedAssets(index.content) else emptyList()
        if (index is FileRead.Missing) missing.add("index.html")
        if (index is FileRead.Empty) empty.add("index.html")
        for (ref in referenced) {
            when (readFileStatus(context, name, ref)) {
                is FileRead.Missing -> missing.add(ref)
                is FileRead.Empty -> empty.add(ref)
                is FileRead.Ok -> Unit
            }
        }
        return ProjectValidation(
            ok = missing.isEmpty() && empty.isEmpty(),
            missingFiles = missing,
            emptyFiles = empty,
            referencedFiles = referenced
        )
    }

    /** Default content for a file the user adds from the editor, so a
     *  new file never opens as a blank editor with no clue. */
    fun defaultFileContent(fileName: String, project: String): String = when (sanitizeFileName(fileName)) {
        "index.html" -> websiteTemplate(sanitizeProjectName(project))
        "style.css" -> defaultCss()
        "script.js" -> defaultJs(sanitizeProjectName(project))
        else -> ""
    }

    // ---- Documents export via MediaStore (user-visible folder, WRITE-ONLY) ----

    private fun exportToDocuments(
        context: Context,
        project: String,
        fileName: String,
        content: String
    ) {
        runCatching {
            val resolver = context.contentResolver
            val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/JarvisProjects/${sanitizeProjectName(project)}/"
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
        fileName.endsWith(".js") -> "application/javascript"
        fileName.endsWith(".json") -> "application/json"
        else -> "text/plain"
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
