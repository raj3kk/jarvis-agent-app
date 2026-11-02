package com.jarvis.autonomous.ui

import android.view.ViewGroup
import android.webkit.WebView
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
import androidx.compose.ui.viewinterop.AndroidView
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
    var previewStarting by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
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
    }

    fun openProject(name: String) {
        val clean = ProjectStore.sanitizeProjectName(name)
        refresh()
        selected = clean
        previewError = null
        loadFile(clean, "index.html")
    }

    /**
     * Starts the server, awaits a real TCP answer, validates the
     * project, and only then shows the preview. On any failure the
     * error banner is shown and NO WebView is loaded — never a
     * silent blank preview.
     */
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
                val detail = (validation.missingFiles.map { "$it missing" } +
                    validation.emptyFiles.map { "$it khaali" }).joinToString(", ")
                previewStarting = false
                previewError = "Preview nahi chal sakta — $detail. Pehle Projects editor me file theek karo ya Jarvis ko dobara banane bolo."
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
            previewNonce += 1
            status = "Preview chal raha hai: ${ProjectStore.localhostUrl(clean)}"
        }
    }

    /**
     * "Open in Browser": the SAME localhost preview, full-screen, in
     * the Browser tab, on the SAME shared WebView instance (the
     * Browser tab borrows it without reloading — see BrowserScreen).
     * Requires the coordinator wiring (browserHost); without it, the
     * inline preview is shown instead and the status says so.
     */
    fun openInBrowser(project: String) {
        val clean = ProjectStore.sanitizeProjectName(project)
        scope.launch {
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
                previewNonce += 1
                status = "Browser tab wiring abhi coordinator se pending hai — preview yahin (same engine) khul gaya: $url"
            } else {
                host.loadUrl(url)
                status = "Browser tab me preview khul gaya: $url"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                        } else {
                            // Single canonical name form (store-side
                            // sanitize) — the old screen-side regex
                            // produced a different selected name than
                            // the listed project, hiding file chips.
                            val clean = ProjectStore.sanitizeProjectName(newName)
                            ProjectStore.createProject(context, clean, null)
                            newName = ""
                            openProject(clean)
                            status = "Project ban gaya — index.html, style.css, script.js ready hain."
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
                                ProjectStore.deleteProject(context, project.name)
                                if (selected == project.name) {
                                    selected = null
                                    previewBaseUrl = null
                                    previewError = null
                                }
                                refresh()
                                status = "Project delete ho gaya: ${project.name}"
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
                            onClick = { loadFile(current, file) },
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
                            if (ProjectStore.writeFile(context, current, selectedFile, restoreContent)) {
                                loadFile(current, selectedFile)
                                refresh()
                                status = "Restore ho gaya: $current/$selectedFile"
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
                    onValueChange = { fileContent = it },
                    label = { Text("$current / $selectedFile") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    minLines = 10
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val ok = ProjectStore.writeFile(context, current, selectedFile, fileContent)
                            if (!ok) {
                                status = "Save NAHI hua: $current/$selectedFile — khaali template file ya galat naam ho sakta hai."
                                return@Button
                            }
                            refresh()
                            // Preview open hai to turant refresh (cache-bust nonce).
                            if (previewBaseUrl != null) previewNonce += 1
                            status = "Saved: $current/$selectedFile — Documents/JarvisProjects me bhi export ho gaya."
                            fileError = null
                        }
                    ) { Text("Save File") }
                    OutlinedButton(onClick = { startPreview(current) }) { Text("Preview") }
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
                        val content = ProjectStore.defaultFileContent(safe, current)
                        if (ProjectStore.writeFile(context, current, safe, content)) {
                            newFileName = ""
                            refresh()
                            loadFile(current, safe)
                            status = "File jod di: $current/$safe"
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

        // Preview — shared Browser-tab engine, never a silent blank.
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
        val url = previewUrl
        if (url != null && previewErr == null) {
            Text("Live Preview (localhost, isi phone par — Browser tab wala engine):", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(previewBaseUrl ?: url, fontSize = 12.sp, color = Color(0xFFB85C38))
            // The shared WebView instance. Factory detaches it from
            // any previous parent (e.g. the Browser tab); update
            // loads ONLY when the target URL string changes, so the
            // Browser tab can later borrow it back without a reload,
            // and typing in the editor never resets the preview.
            // onRelease deliberately does NOT destroy it.
            AndroidView(
                factory = { ctx ->
                    val shared: WebView = JarvisBackgroundBrowser.obtainWebView(ctx)
                    (shared.parent as? ViewGroup)?.removeView(shared)
                    shared
                },
                update = { webView ->
                    if (webView.url != url) {
                        webView.loadUrl(url)
                    }
                },
                onRelease = {
                    // Shared instance survives tab switches: no destroy.
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        previewNonce += 1
                        status = "Preview reload ho raha hai."
                    }
                ) { Text("Reload Preview") }
                val previewProject = selected
                if (previewProject != null) {
                    OutlinedButton(onClick = { openInBrowser(previewProject) }) { Text("Browser me kholo (full-screen)") }
                }
            }
            Text(
                "Note: preview aur Browser tab ek hi page share karte hain — ek jagah navigate karoge to doosri jagah bhi wahi page dikhega.",
                fontSize = 11.sp,
                color = Color(0xFF8A7A70)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
