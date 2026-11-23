package com.jarvis.autonomous.ui

import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.core.content.FileProvider
import com.jarvis.autonomous.data.DocumentMemoryStore
import com.jarvis.autonomous.data.DocumentMeta
import com.jarvis.autonomous.data.FileDocumentStorage
import com.jarvis.autonomous.data.FilePatternStorage
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.data.JarvisStore
import com.jarvis.autonomous.data.JarvisStorePatternStorage
import com.jarvis.autonomous.data.LearnedPatternStore
import com.jarvis.autonomous.data.MemoryClient
import com.jarvis.autonomous.data.TrainedIngestion
import java.io.File
import kotlinx.coroutines.launch

/**
 * R2.1 (1.17.0-PRO) — Trained Jarvis tab (tab 9).
 *
 * "Jo dikha de user, Jarvis pura padh ke yaad rakhega" — the user
 * hands Jarvis a document / PDF / photo / link / voice note, Jarvis
 * reads it, remembers it on-device (I1 DocumentMemoryStore), and can
 * work from it (doc recall in handleUserCommand). Every ingested doc
 * also pushes a summary to the hub's shared memory (kind
 * "document") so Main and the admin side can recall it too.
 *
 * Per-doc labels (what the store guarantees): "Jarvis ne padha ✓"
 * (the text was read and stored), "yaad hai ✓" (it is indexed and
 * searchable), "isse kaam kar sakta hai" (doc recall feeds it to the
 * planner/Talker as labelled context).
 *
 * The screen owns its own DocumentMemoryStore / LearnedPatternStore
 * instances (same pattern as the Memory & Logs tab's doc UI in
 * MainActivity) — MainActivity talks to the engine only via service
 * intents, so the engine's instances are not reachable here. The
 * pattern store picks the Jarvis Store backing once migrated, same
 * conditional as the engine.
 */
@Composable
fun TrainedJarvisScreen() {
    val context = LocalContext.current
    val documentMemory = remember {
        DocumentMemoryStore(FileDocumentStorage(context.applicationContext))
    }
    val patternStore = remember {
        LearnedPatternStore(
            if (JarvisStore.isMigrated()) JarvisStorePatternStorage()
            else FilePatternStorage(context.applicationContext)
        )
    }
    val memoryClient = remember { MemoryClient(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val ingestion = remember { TrainedIngestion(context, documentMemory, memoryClient) }

    var docs by remember { mutableStateOf<List<DocumentMeta>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pasteTitle by remember { mutableStateOf("") }
    var pasteText by remember { mutableStateOf("") }
    var linkUrl by remember { mutableStateOf("") }
    var deleteArmedId by remember { mutableStateOf<String?>(null) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val memoryPushVersion by JarvisStateRepository.memoryPushVersion.collectAsState()

    fun refresh() {
        docs = runCatching { documentMemory.listDocuments() }.getOrDefault(emptyList())
    }

    LaunchedEffect(Unit) { refresh() }

    // MEMORY_PUSH (R2.4): the hub has new shared memories — pull them
    // and cache the document summaries + admin instructions on-device.
    LaunchedEffect(memoryPushVersion) {
        if (memoryPushVersion == 0) return@LaunchedEffect
        when (val pulled = memoryClient.pullMemories()) {
            is MemoryClient.PullOutcome.Ok -> {
                val cached = memoryClient.applyPullToDeviceStores(
                    pulled.result, documentMemory, patternStore
                )
                status = "Hub se ${pulled.result.memories.size} yaadein aayi — " +
                    "${cached.first} doc aur ${cached.second} instruction phone par save hue."
                refresh()
            }
            is MemoryClient.PullOutcome.Err -> {
                status = "Hub se yaadein nahi aayi (${pulled.reason}) — phone wale docs yahin hain."
            }
        }
    }

    fun runIngest(block: suspend () -> TrainedIngestion.IngestResult) {
        scope.launch {
            busy = true
            status = "Padh raha hoon…"
            val result = runCatching { block() }
                .getOrElse { TrainedIngestion.IngestResult.Err(it.javaClass.simpleName) }
            busy = false
            when (result) {
                is TrainedIngestion.IngestResult.Ok -> {
                    status = "✓ ${result.title} — ${result.chars} akshar padhe. ${result.d1Note}"
                    pasteTitle = ""
                    pasteText = ""
                    linkUrl = ""
                    refresh()
                }
                is TrainedIngestion.IngestResult.Err -> {
                    status = "✗ ${result.reason}"
                }
            }
        }
    }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: ""
        when {
            mime == "application/pdf" || uri.toString().endsWith(".pdf", ignoreCase = true) -> {
                val name = runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) c.getString(idx) else null
                        } else null
                    }
                }.getOrNull() ?: "document.pdf"
                runIngest { ingestion.ingestPdf(uri, name) }
            }
            mime.startsWith("image/") -> {
                runIngest { ingestion.ingestImage(uri, "photo") }
            }
            else -> {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull() ?: ""
                runIngest { ingestion.ingestText("file", text, "pasted") }
            }
        }
    }

    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = photoUri
        if (ok && uri != null) runIngest { ingestion.ingestImage(uri, "photo") }
    }

    val speakLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull().orEmpty()
        if (spoken.isNotBlank()) {
            pasteText = spoken
            status = "Bola hua text aa gaya — 'Yaad rakho' dabao."
        }
    }

    fun startPhoto() {
        val file = File(context.cacheDir, "trained-photo-${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return
        photoUri = uri
        takePhoto.launch(uri)
    }

    fun startSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Bolo — Jarvis yaad rakhega")
        }
        runCatching { speakLauncher.launch(intent) }
    }

    val stats = remember(docs) {
        runCatching { documentMemory.stats() }.getOrNull()
    }
    val shownDocs = if (search.isBlank()) docs else docs.filter {
        it.title.contains(search, ignoreCase = true) ||
            it.keywords.any { kw -> kw.contains(search, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Trained Jarvis",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB85C38)
        )
        Text(
            text = "Document, PDF, photo, link ya bolo — Jarvis padh ke yaad rakhega, aur us se kaam kar payega.",
            fontSize = 13.sp,
            color = Color(0xFF8A7A70)
        )
        Text(
            text = "Yaad rakhe documents (${docs.size}/50)" +
                (stats?.let { " · ${(it.totalBytes / 1024)} KB" } ?: ""),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )

        if (busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text(text = "Padh raha hoon…", fontSize = 13.sp)
            }
        }
        if (status.isNotBlank()) {
            Text(text = status, fontSize = 13.sp, color = Color(0xFF5C4A3A))
        }

        // Upload row.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickFile.launch("*/*") }, enabled = !busy) {
                Text("File chuno")
            }
            OutlinedButton(onClick = { startPhoto() }, enabled = !busy) {
                Text("Photo lo")
            }
            OutlinedButton(onClick = { startSpeech() }, enabled = !busy) {
                Text("Bolo")
            }
        }

        // Link.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = linkUrl,
                onValueChange = { linkUrl = it },
                label = { Text("Link daalo (https://…)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { runIngest { ingestion.ingestLink(linkUrl) } },
                enabled = !busy && linkUrl.isNotBlank()
            ) { Text("Padho") }
        }

        // Paste.
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1DC))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pasteTitle,
                    onValueChange = { pasteTitle = it },
                    label = { Text("Title (khali ho to auto)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    label = { Text("Text likho ya bolo — Jarvis yaad rakhega") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        runIngest {
                            ingestion.ingestText(pasteTitle.ifBlank { "note" }, pasteText, "pasted")
                        }
                    },
                    enabled = !busy && pasteText.isNotBlank()
                ) { Text("Yaad rakho") }
            }
        }

        // Hub pull.
        OutlinedButton(
            onClick = {
                scope.launch {
                    busy = true
                    when (val pulled = memoryClient.pullMemories()) {
                        is MemoryClient.PullOutcome.Ok -> {
                            val cached = memoryClient.applyPullToDeviceStores(
                                pulled.result, documentMemory, patternStore
                            )
                            status = "Hub se ${pulled.result.memories.size} yaadein aayi — " +
                                "${cached.first} doc aur ${cached.second} instruction phone par save hue."
                            refresh()
                        }
                        is MemoryClient.PullOutcome.Err -> {
                            status = "Hub se yaadein nahi aayi (${pulled.reason})."
                        }
                    }
                    busy = false
                }
            },
            enabled = !busy
        ) { Text("Hub se nayi yaadein lao") }

        // Search.
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Documents me dhoondo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Doc list.
        if (shownDocs.isEmpty()) {
            Text(
                text = if (docs.isEmpty()) "Abhi koi document nahi — upar se kuch do, Jarvis padh lega."
                else "Is khoj ke liye kuch nahi mila.",
                fontSize = 13.sp,
                color = Color(0xFF8A7A70)
            )
        }
        for (doc in shownDocs) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8F0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = doc.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        text = "Jarvis ne padha ✓ · yaad hai ✓ · isse kaam kar sakta hai",
                        fontSize = 12.sp,
                        color = Color(0xFF5C7A4A)
                    )
                    Text(
                        text = "${doc.source} · ${doc.charCount} akshar",
                        fontSize = 12.sp,
                        color = Color(0xFF8A7A70)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (deleteArmedId == doc.id) {
                                    val ok = runCatching {
                                        documentMemory.deleteDocument(doc.id)
                                    }.getOrDefault(false)
                                    status = if (ok) "✓ '${doc.title}' hata diya."
                                    else "Delete nahi ho paya."
                                    deleteArmedId = null
                                    refresh()
                                } else {
                                    deleteArmedId = doc.id
                                }
                            }
                        ) {
                            Text(if (deleteArmedId == doc.id) "Pakka hatao?" else "Hatao")
                        }
                        if (deleteArmedId == doc.id) {
                            OutlinedButton(onClick = { deleteArmedId = null }) {
                                Text("Ruko")
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
