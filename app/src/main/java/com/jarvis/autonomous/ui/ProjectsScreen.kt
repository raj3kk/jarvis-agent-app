package com.jarvis.autonomous.ui

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

/**
 * Projects tab: Jarvis's on-device code builder UI.
 * - Har project apne folder me (koi mix nahi).
 * - File edit + Save = turant working copy + Documents/JarvisProjects
 *   me export (Files app me dikhega).
 * - Preview = NanoHTTPD localhost (127.0.0.1:8080) + WebView, bina
 *   server/account/card ke.
 * Jarvis se banao: Command tab me bolo "meri portfolio website banao"
 * — wo CREATE_PROJECT action se yahin project bana dega.
 */
@Composable
fun ProjectsScreen() {
    val context = LocalContext.current
    var projects by remember { mutableStateOf(ProjectStore.listProjects(context)) }
    var newName by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var selectedFile by remember { mutableStateOf("index.html") }
    var fileContent by remember { mutableStateOf("") }
    var previewUrl by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }

    fun refresh() {
        projects = ProjectStore.listProjects(context)
    }

    fun openProject(name: String) {
        selected = name
        selectedFile = "index.html"
        fileContent = ProjectStore.readFile(context, name, "index.html")
        previewUrl = null
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
            text = "Har project alag folder me. File save hote hi Documents/JarvisProjects me bhi export hoti hai, aur localhost preview isi phone par chalta hai — koi server, account ya card nahi.",
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
                            ProjectStore.createProject(context, newName, null)
                            openProject(newName.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-"))
                            newName = ""
                            refresh()
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
                        OutlinedButton(
                            onClick = {
                                runCatching { ProjectPreviewServer.ensureRunning(context) }
                                previewUrl = ProjectStore.localhostUrl(project.name)
                                selected = project.name
                                status = "Preview chal raha hai: ${ProjectStore.localhostUrl(project.name)}"
                            }
                        ) { Text("Preview", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = {
                                ProjectStore.deleteProject(context, project.name)
                                if (selected == project.name) {
                                    selected = null
                                    previewUrl = null
                                }
                                refresh()
                                status = "Project delete ho gaya: ${project.name}"
                            }
                        ) { Text("Delete", fontSize = 12.sp) }
                    }
                }
            }
        }

        // Selected project editor
        val current = selected
        if (current != null) {
            val files = projects.firstOrNull { it.name == current }?.files ?: listOf("index.html")
            Text("Editing: $current", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                files.forEach { file ->
                    FilterChip(
                        selected = selectedFile == file,
                        onClick = {
                            selectedFile = file
                            fileContent = ProjectStore.readFile(context, current, file)
                            previewUrl = null
                        },
                        label = { Text(file, fontSize = 12.sp) }
                    )
                }
            }
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
                        ProjectStore.writeFile(context, current, selectedFile, fileContent)
                        refresh()
                        status = "Saved: $current/$selectedFile — Documents/JarvisProjects me bhi export ho gaya."
                    }
                ) { Text("Save File") }
                OutlinedButton(
                    onClick = {
                        runCatching { ProjectPreviewServer.ensureRunning(context) }
                        previewUrl = ProjectStore.localhostUrl(current)
                        status = "Preview: ${ProjectStore.localhostUrl(current)}"
                    }
                ) { Text("Preview") }
            }
        }

        if (status.isNotBlank()) {
            Text(status, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D5B))
        }

        // Preview
        val url = previewUrl
        if (url != null) {
            Text("Live Preview (localhost, isi phone par):", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(url, fontSize = 12.sp, color = Color(0xFFB85C38))
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                    }
                },
                update = { webView -> webView.loadUrl(url) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
