package com.jarvis.autonomous.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.autonomous.data.ProjectPreviewServer
import com.jarvis.autonomous.data.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Projects tab: Jarvis's on-device code builder UI.
 * - Har project apne folder me (koi mix nahi).
 * - File edit + Save = turant working copy + Documents/JarvisProjects
 *   me export (Files app me dikhega).
 * - Preview = localhost server (127.0.0.1:8080) + the SAME shared
 *   WebView engine as the Browser tab
 *   ([JarvisBackgroundBrowser.obtainWebView]) — NOT a second,
 *   separately-configured preview WebView. The old screen built its
 *   own WebView with only javaScriptEnabled/domStorageEnabled, no
 *   error handling, and loaded the URL even when the server had
 *   failed to start: that combination is what produced blank
 *   previews. See INTEGRATION_NOTES_Builder.md §3 for the contract.
 * - Code is ALWAYS visible: every project file is a chip, the editor
 *   never shows a silent blank (missing/empty files get an explicit
 *   error banner + a Restore button), and the preview never loads
 *   before the server provably answers.
 * Jarvis se banao: Command tab me bolo "meri portfolio website banao"
 * — wo CREATE_PROJECT action se yahin project bana dega.
 *
 * @param browserHost shared holder from the coordinator (hoisted in
 * MainActivity, also passed to BrowserScreen). "Open in Browser"
 * needs it to switch to the Browser tab; when it is null (wiring
 * not done yet) the button falls back to the inline shared-engine
 * preview and says so — it never shows a blank screen.
 */
@Composable
fun ProjectsScreen(browserHost: BrowserHostState? = null) {
    val context = LocalContext.current
    // --- ALL state declarations FIRST (kotlin_prepush_checks: every
    // callback / helper below only captures variables declared above).
    var projects by remember { mutableStateOf(ProjectStore.listProjects(context)) }
    var newName by remember { mutableStateOf("") }
    var newFileName by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var selectedFile by remember { mutableStateOf("index.html") }
    var fileContent by remember { mutableStateOf("") }
    var fileError by remember { mutableStateOf<String?>(null) }
    var previewBaseUrl by remember { mutableStateOf<String?>(null) }
    var previewNonce by remember { mutableStateOf(0) }
    // B-L5: which project the inline preview is actually showing.
    // The nonce bump on Save and the clear-on-delete below are scoped
    // to THIS project only — editing project B must never reload (or
    // clear) project A's preview.
    var previewProject by remember { mutableStateOf<String?>(null) }
    var previewStarting by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    // 1.4.20 (item 2): Editor / Preview are mutually exclusive UI
    // modes — UI state only, no mic/wake involvement. Editor mode
    // (default) is the scrollable column; Preview mode is a
    // non-scrolling container with the WebView at weight(1f).
    var previewMode by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    // B-L6: true once the editor text differs from the loaded file.
    // A file/project switch with unsaved edits asks first (banner
    // below) instead of silently discarding them.
    var editorDirty by remember { mutableStateOf(false) }
    // B-L6: a switch the user has not confirmed yet (project, file).
    var pendingSwitch by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()

    // Full preview URL: the ?v= nonce busts the WebView cache on
    // every Save, and the AndroidView update block only loads when
    // this string actually changes — so typing in the editor can
    // never reload (and reset) the preview mid-edit.
    val previewUrl = previewBaseUrl?.let { "$it?v=$previewNonce" }

    // --- Helpers (after the state they mutate).
    fun refresh() {
        projects = ProjectStore.listProjects(context)
    }

    fun loadFile(project: String, file: String) {
        selected = project
        selectedFile = file
        when (val read = ProjectStore.readFileStatus(context, project, file)) {
            is ProjectStore.FileRead.Ok -> {
                fileContent = read.content
                fileError = null
            }
            is ProjectStore.FileRead.Missing -> {
                fileContent = ""
                fileError = "File nahi mili: $project/$file — shayad Jarvis ne ise banaya nahi, ya delete ho gayi."
            }
            is ProjectStore.FileRead.Empty -> {
                fileContent = ""
                fileError = "File khaali hai: $project/$file — isme koi code nahi hai. Neeche Restore dabao ya dobara likho."
            }
        }
        editorDirty = false
    }

    /**
     * B-L6 gate: when the editor has unsaved edits and the target is
     * a DIFFERENT file/project, the switch is parked in pendingSwitch
     * and the banner below asks — loadFile() is NOT called, so the
     * edits survive until the user chooses Discard or Cancel. A
     * switch to the already-open file, or with a clean editor, loads
     * immediately. Callers that just saved/created the file use
     * loadFile() directly (no gate — there is nothing to lose).
     */
    fun requestLoad(project: String, file: String) {
        if (editorDirty && (project != selected || file != selectedFile)) {
            pendingSwitch = project to file
            return
        }
        loadFile(project, file)
    }

    fun openProject(name: String) {
        val clean = ProjectStore.sanitizeProjectName(name)
        refresh()
        // B-L5 + B-L6 ordering: the dirty gate runs BEFORE the preview
        // is cleared — a parked switch (user has not confirmed yet)
        // must not destroy the old project's preview, and the banner's
        // Discard path clears it when the switch actually happens.
        if (editorDirty && clean != selected) {
            pendingSwitch = clean to "index.html"
            return
        }
        previewBaseUrl = null
        previewProject = null
        previewError = null
        previewMode = false
        loadFile(clean, "index.html")
    }

    /** B-L7: one detail string for every validation failure mode —
     *  missing, empty AND invalid (absolute/escaping) references —
     *  so a failure never falls through to a generic message. */
    fun validationDetail(validation: ProjectStore.ProjectValidation): String {
        val parts = validation.missingFiles.map { "$it missing" } +
            validation.emptyFiles.map { "$it khaali" } +
            validation.invalidRefs.map { "$it galat reference" }
        return parts.joinToString(", ")
    }

    /**
     * Starts the server, awaits a real TCP answer, validates the
     * project, and only then shows the preview. On any failure the
     * error banner is shown and NO WebView is loaded — never a
     * silent blank preview.
     */
    /**
     * 1.4.8 (G8 F1): the preview server finally HAS a stop path
     * (removed as "dead code" in the 1.4.6 debloat because
     * nothing called it — now the UI calls it). Stops the
     * singleton server + clears preview UI state. Safe to call
     * when nothing is running (no-op).
     */
    fun stopPreview() {
        ProjectPreviewServer.stopInstance()
        previewBaseUrl = null
        previewProject = null
        previewError = null
        previewMode = false
        status = "Preview band ho gaya."
    }

    fun startPreview(project: String) {
        val clean = ProjectStore.sanitizeProjectName(project)
        selected = clean
        previewError = null
        previewStarting = true
        scope.launch {
            val validation = withContext(Dispatchers.IO) {
                ProjectStore.validateProject(context, clean)
            }
            if (!validation.ok) {
                previewStarting = false
                previewError = "Preview nahi chal sakta — ${validationDetail(validation)}. Pehle Projects editor me file theek karo ya Jarvis ko dobara banane bolo."
                return@launch
            }
            val (_, ready) = withContext(Dispatchers.IO) {
                ProjectPreviewServer.ensureRunningReady(context)
            }
            previewStarting = false
            if (!ready) {
                previewError = "Preview server start nahi hua (port 8080 busy ya blocked). Retry dabao; phir bhi na chale to app restart karo."
                return@launch
            }
            previewBaseUrl = ProjectStore.localhostUrl(clean)
            previewProject = clean
            previewNonce += 1
            previewMode = true
            status = "Preview chal raha hai: ${ProjectStore.localhostUrl(clean)}"
        }
    }

    /**
     * "Open in Browser": the SAME localhost preview, full-screen, in
     * the Browser tab, on the SAME shared WebView instance (the
     * Browser tab borrows it without reloading — see BrowserScreen).
     * Requires the coordinator wiring (browserHost); without it, the
     * inline preview is shown instead and the status says so.
     * B-M7: validated with the SAME gate as Preview — an unvalidated
     * project must not open in the Browser tab either.
     */
    fun openInBrowser(project: String) {
        val clean = ProjectStore.sanitizeProjectName(project)
        scope.launch {
            val validation = withContext(Dispatchers.IO) {
                ProjectStore.validateProject(context, clean)
            }
            if (!validation.ok) {
                previewError = "Browser me nahi khol sakta — ${validationDetail(validation)}. Pehle Projects editor me file theek karo ya Jarvis ko dobara banane bolo."
                return@launch
            }
            val (_, ready) = withContext(Dispatchers.IO) {
                ProjectPreviewServer.ensureRunningReady(context)
            }
            if (!ready) {
                previewError = "Preview server start nahi hua, isliye Browser me bhi nahi kholega. Retry dabao."
                return@launch
            }
            val url = ProjectStore.localhostUrl(clean)
            val host = browserHost
            if (host == null) {
                previewBaseUrl = url
                previewProject = clean
                previewNonce += 1
                previewMode = true
                status = "Browser tab wiring abhi coordinator se pending hai — preview yahin (same engine) khul gaya: $url"
            } else {
                host.loadUrl(url)
                status = "Browser tab me preview khul gaya: $url"
            }
        }
    }

    // 1.4.20 (item 2): the root container does NOT scroll. Editor
    // mode (default) is the scrollable column below; Preview mode
    // is a non-scrolling container with the WebView at weight(1f),
    // structurally identical to the Browser tab. The WebView is
    // NEVER composed inside a verticalScroll column any more: pages
    // taller than the old 420.dp box were truncated and gestures
    // were contested between the outer Compose scroll and the
    // WebView's own scroll, with no nested-scroll wiring anywhere.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Jarvis Projects — Code Builder",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB85C38)
        )
        Text(
            text = "Har project alag folder me. File save hote hi Documents/JarvisProjects me bhi export hoti hai, aur localhost preview isi phone par chalta hai — koi server, account ya card nahi. Preview Browser tab wale shared engine se hi chalta hai.",
            fontSize = 12.sp,
            color = Color(0xFF8A7A70)
        )

        if (previewMode) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = previewProject ?: selected ?: "Preview",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { previewNonce += 1 }) { Text("Reload", fontSize = 12.sp) }
                OutlinedButton(onClick = { stopPreview() }) { Text("Stop", fontSize = 12.sp) }
                OutlinedButton(onClick = { previewMode = false }) { Text("Editor wapas", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = {
                        val p = previewProject ?: selected
                        if (p != null) openInBrowser(p)
                    }
                ) { Text("Browser me kholo", fontSize = 12.sp) }
            }
            if (previewStarting) {
                Text("Preview server start ho raha hai…", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            val modeErr = previewError
            val modeUrl = previewUrl
            if (modeErr != null) {
                Text(modeErr, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB3261E))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val retryProject = selected
                    if (retryProject != null) {
                        OutlinedButton(onClick = { startPreview(retryProject) }) { Text("Retry Preview") }
                    }
                    OutlinedButton(onClick = { previewMode = false }) { Text("Editor wapas", fontSize = 12.sp) }
                }
            } else if (modeUrl != null) {
                Text("Live Preview (localhost, isi phone par — Browser tab wala engine):", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(previewBaseUrl ?: modeUrl, fontSize = 12.sp, color = Color(0xFFB85C38))
                JarvisWebView(
                    targetUrl = modeUrl,
                    onError = { previewError = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                Text(
                    "Note: preview aur Browser tab ek hi page share karte hain — ek jagah navigate karoge to doosri jagah bhi wahi page dikhega.",
                    fontSize = 11.sp,
                    color = Color(0xFF8A7A70)
                )
            } else {
                Text("Preview band hai — Editor se kisi project ka Preview dabao.", fontSize = 13.sp)
            }
        } else {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

        // New project
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Naya project ka naam (e.g. meri-portfolio)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    onClick = {
                        if (newName.isBlank()) {
                            status = "Pehle project ka naam likho."
                        } else if (ProjectStore.isRejectedProjectName(newName)) {
                            // B-M5: "." / ".." are refused outright —
                            // they must never become a project folder.
                            status = "'.' ya '..' project ka naam nahi ho sakta."
                        } else {
                            // Single canonical name form (store-side
                            // sanitize) — the old screen-side regex
                            // produced a different selected name than
                            // the listed project, hiding file chips.
                            val normalized = ProjectStore.sanitizeProjectName(newName)
                            // B-M5: a DIFFERENT raw name that normalises
                            // onto an existing folder gets a suffix
                            // (-2, -3, …), never a silent overwrite of
                            // that folder's index.html. An exact-name
                            // re-create keeps the old re-build meaning.
                            val clean = if (ProjectStore.projectExists(context, normalized) &&
                                newName.trim() != normalized
                            ) {
                                ProjectStore.uniqueProjectName(context, newName)
                            } else {
                                normalized
                            }
                            ProjectStore.createProject(context, clean, null)
                            newName = ""
                            openProject(clean)
                            status = if (clean != normalized) {
                                "Project ban gaya — '$normalized' pehle se tha, isliye '$clean' banaya gaya. index.html, style.css, script.js ready hain."
                            } else {
                                "Project ban gaya — index.html, style.css, script.js ready hain."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create Website Project")
                }
            }
        }

        // Project list
        Text("Tumhare Projects:", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (projects.isEmpty()) {
            Text("Abhi koi project nahi — upar se banao, ya Command me Jarvis ko bolo.", fontSize = 13.sp)
        }
        projects.forEach { project ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected == project.name) Color(0xFFFFF1DC) else Color.White
                )
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(project.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        text = project.files.joinToString(", ").ifBlank { "koi file nahi" },
                        fontSize = 11.sp,
                        color = Color(0xFF8A7A70)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { openProject(project.name) }) { Text("Open", fontSize = 12.sp) }
                        OutlinedButton(onClick = { startPreview(project.name) }) { Text("Preview", fontSize = 12.sp) }
                        OutlinedButton(onClick = { openInBrowser(project.name) }) { Text("Browser me kholo", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = {
                                // B-M6: deleteProject's Boolean is
                                // respected — a failed delete must not
                                // claim success, and the Documents copy
                                // is left alone by the store in that
                                // case. Preview state clears only for
                                // the project actually deleted (B-L5).
                                val deleted = ProjectStore.deleteProject(context, project.name)
                                if (!deleted) {
                                    status = "Delete NAHI hua: ${project.name} — dobara try karo."
                                } else {
                                    if (selected == project.name) {
                                        selected = null
                                    }
                                    if (previewProject == project.name) {
                                        // 1.4.8 (G8 F1): deleting the
                                        // previewed project stops its
                                        // server too — serving deleted
                                        // files from a held port is
                                        // worse than useless.
                                        ProjectPreviewServer.stopInstance()
                                        previewBaseUrl = null
                                        previewProject = null
                                        previewError = null
                                        previewMode = false
                                    }
                                    refresh()
                                    status = "Project delete ho gaya: ${project.name}"
                                }
                            }
                        ) { Text("Delete", fontSize = 12.sp) }
                    }
                }
            }
        }

        // Selected project editor — code is ALWAYS visible.
        val current = selected
        if (current != null) {
            val files = projects.firstOrNull { it.name == current }?.files ?: emptyList()
            Text("Editing: $current", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            // B-L6: unsaved-edit confirmation banner. Shown only when
            // a switch was parked; Discard loads the parked file and
            // drops the edits, Cancel keeps editing the current file.
            val parked = pendingSwitch
            if (parked != null) {
                Text(
                    "Unsaved edits hain — switch karne par ye edits khatam ho jayengi.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFB3261E)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            pendingSwitch = null
                            editorDirty = false
                            // B-L5: a confirmed PROJECT switch clears
                            // the old preview; a same-project file
                            // switch leaves it alone.
                            if (parked.first != selected) {
                                previewBaseUrl = null
                                previewProject = null
                                previewError = null
                                previewMode = false
                            }
                            loadFile(parked.first, parked.second)
                        }
                    ) { Text("Discard karke switch karo", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { pendingSwitch = null }
                    ) { Text("Cancel (edit karte raho)", fontSize = 12.sp) }
                }
            }
            if (files.isEmpty()) {
                Text(
                    "Is project me koi file nahi hai. Template files dobara banao, ya neeche se nayi file jodo.",
                    fontSize = 13.sp,
                    color = Color(0xFFB85C38)
                )
                Button(
                    onClick = {
                        ProjectStore.createProject(context, current, null)
                        openProject(current)
                        status = "Template files dobara ban gayi: $current"
                    }
                ) { Text("Template files dobara banao") }
            } else {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    files.forEach { file ->
                        FilterChip(
                            selected = selectedFile == file,
                            onClick = { requestLoad(current, file) },
                            label = { Text(file, fontSize = 12.sp) }
                        )
                    }
                }
            }
            // Explicit error state — never a silent blank editor.
            val error = fileError
            if (error != null && files.isNotEmpty()) {
                Text(error, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB3261E))
                val restoreContent = ProjectStore.defaultFileContent(selectedFile, current)
                if (restoreContent.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            // B-M6: the restore reports its export
                            // status too, not just the working copy.
                            val result = ProjectStore.writeFileDetailed(context, current, selectedFile, restoreContent)
                            if (result.workingOk) {
                                loadFile(current, selectedFile)
                                refresh()
                                status = if (result.exportOk) {
                                    "Restore ho gaya: $current/$selectedFile"
                                } else {
                                    "Restore ho gaya (working copy), lekin Documents export fail hua: $current/$selectedFile"
                                }
                            } else {
                                status = "Restore nahi ho paya — dobara try karo."
                            }
                        }
                    ) { Text("Template se Restore karo") }
                }
            }
            if (files.isNotEmpty()) {
                OutlinedTextField(
                    value = fileContent,
                    onValueChange = {
                        fileContent = it
                        // B-L6: any edit marks the editor dirty until
                        // a successful Save clears it.
                        editorDirty = true
                    },
                    label = { Text("$current / $selectedFile") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    minLines = 10
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            // B-M6: the save reports working-copy and
                            // Documents-export status separately — an
                            // export failure is never announced as
                            // "export ho gaya".
                            val result = ProjectStore.writeFileDetailed(context, current, selectedFile, fileContent)
                            if (!result.workingOk) {
                                status = "Save NAHI hua: $current/$selectedFile — khaali file ya galat naam ho sakta hai."
                                return@Button
                            }
                            editorDirty = false
                            refresh()
                            // B-L5: reload the preview only when the
                            // file just saved belongs to the project
                            // actually being previewed.
                            if (previewBaseUrl != null && previewProject == current) {
                                previewNonce += 1
                            }
                            status = if (result.exportOk) {
                                "Saved: $current/$selectedFile — Documents/JarvisProjects me bhi export ho gaya."
                            } else {
                                "Saved (working copy): $current/$selectedFile — lekin Documents export FAIL hua, Files app me naya version nahi dikhega."
                            }
                            fileError = null
                        }
                    ) { Text("Save File") }
                    OutlinedButton(onClick = { startPreview(current) }) { Text("Preview") }
                    // 1.4.8 (G8 F1): the server finally has a Stop
                    // button — shown only while this project is
                    // actually previewed, so it can never stop a
                    // preview the user is not looking at.
                    if (previewBaseUrl != null && previewProject == current) {
                        OutlinedButton(onClick = { stopPreview() }) { Text("Stop Preview") }
                    }
                    OutlinedButton(onClick = { openInBrowser(current) }) { Text("Browser me kholo") }
                }
            }
            // Add a file to this project (never a blank mystery file:
            // template files get template content, others start empty
            // and the editor then shows the explicit empty-file state).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text("Nayi file (e.g. about.html)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedButton(
                    onClick = {
                        val safe = ProjectStore.sanitizeFileName(newFileName)
                        if (safe == null) {
                            status = "Galat file naam — sirf letters, digits, ., -, _ aur / use karo, .. mat likho."
                            return@OutlinedButton
                        }
                        // B-L6: blank writes are refused for ALL files
                        // now, so a new non-template file gets a small
                        // placeholder comment instead of a refused
                        // empty write (the editor then shows real
                        // content to replace, not a mystery failure).
                        val template = ProjectStore.defaultFileContent(safe, current)
                        val content = if (template.isNotBlank()) {
                            template
                        } else if (safe.lowercase().endsWith(".css")) {
                            "/* new file: $safe */\n"
                        } else if (safe.lowercase().endsWith(".js")) {
                            "// new file: $safe\n"
                        } else {
                            "<!-- new file: $safe -->\n"
                        }
                        val result = ProjectStore.writeFileDetailed(context, current, safe, content)
                        if (result.workingOk) {
                            newFileName = ""
                            refresh()
                            loadFile(current, safe)
                            status = if (result.exportOk) {
                                "File jod di: $current/$safe"
                            } else {
                                "File jod di (working copy): $current/$safe — lekin Documents export fail hua."
                            }
                        } else {
                            status = "File nahi bani: $current/$safe — save fail hua."
                        }
                    }
                ) { Text("Add file") }
            }
        }

        if (status.isNotBlank()) {
            Text(status, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D5B))
        }

        // 1.4.20 (item 2): NO inline preview WebView in this scroll
        // column any more — preview lives in Preview mode above
        // (non-scrolling, weight(1f)). Validation / server errors
        // still surface here in Editor mode, so a failed Preview
        // tap is never silent; the error's Retry re-enters Preview
        // mode on success via startPreview.
        if (previewStarting) {
            Text("Preview server start ho raha hai…", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        val previewErr = previewError
        if (previewErr != null) {
            Text(previewErr, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB3261E))
            val retryProject = selected
            if (retryProject != null) {
                OutlinedButton(onClick = { startPreview(retryProject) }) { Text("Retry Preview") }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        } // close editor scroll column
        } // close else block (Editor mode)
    }
}
