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
     *  build-summary step — see INTEGRATION_NOTES_Builder.md §6).
     *  B-M7: [invalidRefs] are references that are ERRORS by form, not
     *  by absence — leading-slash (absolute) references, which the old
     *  code silently stripped to a relative name and then "found". */
    data class ProjectValidation(
        val ok: Boolean,
        val missingFiles: List<String>,
        val emptyFiles: List<String>,
        val referencedFiles: List<String>,
        val invalidRefs: List<String> = emptyList()
    )

    fun rootDir(context: Context): File =
        // C-B14: getExternalFilesDir() can return null (unmounted /
        // adopted storage) — File(null, …) would NPE. Fall back to
        // the app-private files dir instead of crashing.
        File(context.getExternalFilesDir(null) ?: context.filesDir, "JarvisProjects").apply { mkdirs() }

    /** Canonical project-name form. Every caller (store, editor,
     *  LLM parsing, engine) MUST go through this one function — the
     *  old editor-side regex copy differed (no trailing-dash trim,
     *  no 60-char cap, no blank fallback), so the editor could select
     *  a name that matched no listed project and show only the
     *  index.html chip.
     *  B-M5: "." and ".." map to the fallback, never to themselves —
     *  a project dir literally named "." would BE the projects root
     *  and ".." would escape it. */
    fun sanitizeProjectName(name: String): String {
        val clean = name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-')
        if (clean == "." || clean == "..") return "jarvis-project"
        return clean.ifBlank { "jarvis-project" }.take(60)
    }

    /** B-M5: raw names the UI must refuse outright, before any
     *  sanitising fallback could quietly rename them. */
    fun isRejectedProjectName(rawName: String): Boolean {
        val trimmed = rawName.trim()
        return trimmed == "." || trimmed == ".."
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

    /** B-S1: the same path WITHOUT mkdirs — for read paths (the
     *  preview server) that must never create a phantom project just
     *  because somebody requested its URL. */
    fun projectDirNoCreate(context: Context, name: String): File =
        File(rootDir(context), sanitizeProjectName(name))

    /** True when a folder with this sanitised name already exists.
     *  Uses the no-create path, so the check itself creates nothing. */
    fun projectExists(context: Context, sanitizedName: String): Boolean =
        projectDirNoCreate(context, sanitizedName).isDirectory

    /** B-M5: when a raw name normalises onto an existing folder, the
     *  caller must not overwrite that folder — this returns the first
     *  free "<name>-2", "<name>-3", … form instead. */
    fun uniqueProjectName(context: Context, rawName: String): String {
        val base = sanitizeProjectName(rawName)
        if (!projectExists(context, base)) return base
        var suffix = 2
        while (true) {
            // Truncate the STEM, never the suffix — a 60-char base
            // plus "-2" truncated to 60 chars would equal the base
            // itself and loop forever.
            val stemLength = (60 - suffix.toString().length - 1).coerceAtLeast(1)
            val candidate = "${base.take(stemLength)}-$suffix"
            if (!projectExists(context, candidate)) return candidate
            suffix++
        }
    }

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

    /** Result of a write that keeps the working copy and the
     *  Documents export distinct (B-M6): a working-copy success with a
     *  failed export must NEVER be reported as "export ho gaya". */
    data class WriteResult(
        val workingOk: Boolean,
        val exportOk: Boolean
    )

    /**
     * Saves to the working copy (source of truth) AND exports to
     * Documents (write-only copy, best effort). Returns false when
     * the file name is unsafe or the working-copy write failed —
     * callers must surface that instead of claiming "Saved".
     * B-L6: a blank write is refused for EVERY file, not just the
     * three template files — a blank LLM/editor output must never
     * silently wipe a good file (validation is the second guard;
     * this is the first). Callers that intentionally create a new
     * empty file must write a placeholder comment instead.
     */
    fun writeFile(context: Context, project: String, fileName: String, content: String): Boolean =
        writeFileDetailed(context, project, fileName, content).workingOk

    fun writeFileDetailed(context: Context, project: String, fileName: String, content: String): WriteResult {
        val safe = sanitizeFileName(fileName) ?: return WriteResult(false, false)
        if (content.isBlank()) {
            return WriteResult(false, false)
        }
        val dir = projectDir(context, project)
        val file = File(dir, safe)
        file.parentFile?.mkdirs()
        val ok = runCatching { file.writeText(content) }.isSuccess
        if (!ok) return WriteResult(false, false)
        val exported = exportToDocuments(context, dir.name, safe, content)
        recordLastTouched(context, dir.name)
        return WriteResult(true, exported)
    }

    fun deleteProject(context: Context, project: String): Boolean {
        val name = sanitizeProjectName(project)
        val deleted = projectDir(context, name).deleteRecursively()
        // B-M6: a failed working-copy delete must NOT claim success
        // and must NOT delete the Documents copy — the UI checks this
        // Boolean, and the Documents copy is the user's visible backup
        // of a project that (per this return) still exists.
        if (!deleted) return false
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
     *  and pure anchors. Kept for callers that need the raw list;
     *  VALIDATION ([validateProject]) does NOT use this — it resolves
     *  every reference relative to its own file's directory and
     *  treats leading-slash references as errors (see B-M7). */
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

    /** CSS url(...) references, same skipping rules as HTML refs. */
    private fun referencedCssUrls(css: String): List<String> {
        val regex = Regex("""url\(\s*["']?([^"')]+)["']?\s*\)""")
        return regex.findAll(css)
            .map { it.groupValues[1].substringBefore("?").substringBefore("#").trim() }
            .filter {
                it.isNotBlank() && !it.startsWith("http://") && !it.startsWith("https://") &&
                    !it.startsWith("//") && !it.startsWith("data:")
            }
            .distinct()
            .toList()
    }

    /**
     * Resolves one raw reference against the directory of the file
     * that contains it. Returns the project-relative path, or null
     * when the reference is invalid by form. B-M7 rules:
     * - a leading "/" reference is an ERROR (absolute path — never
     *   silently stripped to a relative name, which used to make a
     *   broken link validate as "found");
     * - ".." segments are resolved lexically and may climb to the
     *   project root, but never above it (escaping the root is an
     *   error, not a silent clamp);
     * - backslashes are path separators, never escapes.
     */
    private fun resolveReference(fileDir: String, rawRef: String): String? {
        if (rawRef.startsWith("/")) return null
        val base = if (fileDir.isEmpty()) emptyList() else fileDir.split("/")
        val out = base.toMutableList()
        for (segment in rawRef.replace('\\', '/').split("/")) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (out.isEmpty()) return null
                    out.removeAt(out.lastIndex)
                }
                else -> {
                    if (!segment.matches(Regex("[A-Za-z0-9._-]+"))) return null
                    out.add(segment)
                }
            }
        }
        if (out.isEmpty()) return null
        return out.joinToString("/")
    }

    /** Validates a built project against the source of truth:
     *  index.html exists and is non-empty, and EVERY HTML file's
     *  references (plus every CSS file's url() references) resolve
     *  relative to THAT file's directory and point at an existing,
     *  non-empty file. The old code checked only index.html's refs
     *  and resolved them all against the project root, so a nested
     *  page's "style.css" (really pages/style.css) validated against
     *  the wrong file, and a missing nested asset passed silently. */
    fun validateProject(context: Context, project: String): ProjectValidation {
        val name = sanitizeProjectName(project)
        val missing = mutableListOf<String>()
        val empty = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        val referenced = mutableListOf<String>()
        val index = readFileStatus(context, name, "index.html")
        if (index is FileRead.Missing) missing.add("index.html")
        if (index is FileRead.Empty) empty.add("index.html")
        // Every HTML file in the project, including nested pages.
        val htmlFiles = listProjects(context)
            .firstOrNull { it.name == name }?.files
            ?.filter { it.lowercase().endsWith(".html") || it.lowercase().endsWith(".htm") }
            ?: emptyList()
        val htmlRegex = Regex("""(?:href|src)\s*=\s*["']([^"']+)["']""")
        for (htmlFile in htmlFiles) {
            val content = when (val read = readFileStatus(context, name, htmlFile)) {
                is FileRead.Ok -> read.content
                else -> continue
            }
            val fileDir = htmlFile.substringBeforeLast("/", "")
                .takeIf { htmlFile.contains("/") } ?: ""
            val refs = htmlRegex.findAll(content)
                .map { it.groupValues[1].substringBefore("?").substringBefore("#").trim() }
                .filter {
                    it.isNotBlank() && !it.startsWith("http://") && !it.startsWith("https://") &&
                        !it.startsWith("//") && !it.startsWith("data:")
                }
            for (raw in refs) {
                val resolved = resolveReference(fileDir, raw)
                if (resolved == null) {
                    invalid.add("$htmlFile -> $raw (absolute ya project se bahar ka path)")
                    continue
                }
                referenced.add(resolved)
                when (readFileStatus(context, name, resolved)) {
                    is FileRead.Missing -> missing.add("$htmlFile -> $resolved")
                    is FileRead.Empty -> empty.add("$htmlFile -> $resolved")
                    is FileRead.Ok -> Unit
                }
            }
        }
        // Every CSS file's url(...) references, same directory rule.
        val cssFiles = listProjects(context)
            .firstOrNull { it.name == name }?.files
            ?.filter { it.lowercase().endsWith(".css") }
            ?: emptyList()
        for (cssFile in cssFiles) {
            val content = when (val read = readFileStatus(context, name, cssFile)) {
                is FileRead.Ok -> read.content
                else -> continue
            }
            val fileDir = cssFile.substringBeforeLast("/", "")
                .takeIf { cssFile.contains("/") } ?: ""
            for (raw in referencedCssUrls(content)) {
                val resolved = resolveReference(fileDir, raw)
                if (resolved == null) {
                    invalid.add("$cssFile -> $raw (absolute ya project se bahar ka path)")
                    continue
                }
                referenced.add(resolved)
                when (readFileStatus(context, name, resolved)) {
                    is FileRead.Missing -> missing.add("$cssFile -> $resolved")
                    is FileRead.Empty -> empty.add("$cssFile -> $resolved")
                    is FileRead.Ok -> Unit
                }
            }
        }
        return ProjectValidation(
            ok = missing.isEmpty() && empty.isEmpty() && invalid.isEmpty(),
            missingFiles = missing.distinct(),
            emptyFiles = empty.distinct(),
            referencedFiles = referenced.distinct(),
            invalidRefs = invalid.distinct()
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

    /**
     * B-M6: mirrors subdirectories — a working-copy file at
     * "pages/about.html" exports to Documents/JarvisProjects/<project>/
     * pages/ with DISPLAY_NAME "about.html" (leaf only). The old code
     * put the whole "pages/about.html" string in DISPLAY_NAME under a
     * flat folder, so nested files landed with a broken name and no
     * subfolder. Returns true only when the export actually landed;
     * callers surface a false here instead of claiming "export ho gaya".
     */
    private fun exportToDocuments(
        context: Context,
        project: String,
        fileName: String,
        content: String
    ): Boolean {
        return runCatching {
            val leaf = fileName.substringAfterLast("/")
            val subDir = fileName.substringBeforeLast("/", "")
                .takeIf { fileName.contains("/") } ?: ""
            val relativePath =
                "${Environment.DIRECTORY_DOCUMENTS}/JarvisProjects/${sanitizeProjectName(project)}/" +
                    if (subDir.isNotEmpty()) "$subDir/" else ""
            val resolver = context.contentResolver
            val collection =
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            // Find an existing file with this leaf name in this folder, if any.
            var existing: Uri? = null
            resolver.query(
                collection,
                arrayOf(BaseColumns._ID),
                "${MediaColumns.DISPLAY_NAME}=? AND ${MediaColumns.RELATIVE_PATH}=?",
                arrayOf(leaf, relativePath),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    existing = Uri.withAppendedPath(collection, id.toString())
                }
            }
            val uri = existing ?: run {
                val values = ContentValues().apply {
                    put(MediaColumns.DISPLAY_NAME, leaf)
                    put(MediaColumns.MIME_TYPE, mimeFor(fileName))
                    put(MediaColumns.RELATIVE_PATH, relativePath)
                }
                resolver.insert(collection, values)
            } ?: return false
            var written = false
            resolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                written = true
            }
            written
        }.getOrDefault(false)
    }

    private fun mimeFor(fileName: String): String {
        // B-L11: case-insensitive, plus the types the old map missed.
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
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".pdf") -> "application/pdf"
            else -> "text/plain"
        }
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
