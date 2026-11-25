package com.jarvis.autonomous.ui

import android.content.Intent
import android.util.Base64
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.jarvis.autonomous.data.FilePower
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.ShellAutomation
import com.jarvis.autonomous.shizuku.ShizukuManager
import java.io.File
import kotlinx.coroutines.launch

/**
 * 1.26.0 (power features) — Files tab (tab 11): browse /sdcard,
 * /sdcard/Android/data and /sdcard/Android/obb through the Shizuku
 * shell, with text edit, copy/move/delete (delete asks first),
 * share, and APK backup.
 *
 * Honest limits, shown in the UI:
 * - /data/data is BLOCKED (Permission denied — Android's rule, not
 *   Jarvis's). The tab says so instead of pretending to open it.
 * - Text edit only works for files under 1 MB (MAX_EDIT_BYTES).
 * - Edits go through /data/local/tmp staging (base64), because the
 *   app cannot write that staging dir itself and the shell cannot
 *   read Jarvis's private filesDir. The original mode is restored
 *   with chmod --reference and the write is verified by stat.
 */
@Composable
fun FilesScreen() {
    val context = LocalContext.current
    val prefs = remember { JarvisPreferences(context) }
    val scope = rememberCoroutineScope()
    val shizukuState by ShizukuManager.state.collectAsState()

    var path by remember { mutableStateOf(prefs.filesLastPath.ifBlank { "/sdcard" }) }
    var entries by remember { mutableStateOf<List<FilePower.FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<EditingFile?>(null) }
    var confirmDelete by remember { mutableStateOf<DeleteTarget?>(null) }
    var copyMove by remember { mutableStateOf<CopyMoveTarget?>(null) }
    var apkPkg by remember { mutableStateOf("") }
    var apkResult by remember { mutableStateOf("") }

    fun fullPath(entry: FilePower.FileEntry): String =
        if (path == "/") "/${entry.name}" else "$path/${entry.name}"

    fun load(p: String) {
        scope.launch {
            loading = true
            error = ""
            if (FilePower.isBlockedPath(p)) {
                entries = emptyList()
                error = "BLOCKED: /data/data — dusre apps ka private data shell se bhi nahi khulta " +
                    "(Permission denied). Ye Android ka rule hai, Jarvis ka nahi — is folder ko kholne " +
                    "ka koi tareeka nahi hai."
                loading = false
                return@launch
            }
            val r = ShizukuManager.exec(FilePower.listCommand(p))
            r.fold(
                onSuccess = { out ->
                    entries = FilePower.parseLsOutput(out)
                    path = p
                    prefs.filesLastPath = p
                },
                onFailure = { e ->
                    entries = emptyList()
                    error = "List nahi hua: ${e.message?.take(200)}"
                }
            )
            loading = false
        }
    }

    fun openText(entry: FilePower.FileEntry) {
        val fp = fullPath(entry)
        if (!FilePower.isTextFile(entry.name)) {
            error = "Ye file text nahi hai — edit sirf text files ka hota hai."
            return
        }
        if (entry.sizeBytes > FilePower.MAX_EDIT_BYTES) {
            error = "File bahut badi hai (${FilePower.humanSize(entry.sizeBytes)}) — edit sirf 1 MB tak ki files ka hota hai."
            return
        }
        if (!FilePower.isWritablePath(fp)) {
            error = "Ye file yahan se edit nahi ho sakti (sirf /sdcard aur /data/local/tmp likhe ja sakte hain)."
            return
        }
        scope.launch {
            loading = true
            val r = ShizukuManager.exec(FilePower.readTextCommand(fp), 30_000)
            r.fold(
                onSuccess = { content -> editing = EditingFile(entry, fp, content) },
                onFailure = { e -> error = "File khul nahi payi: ${e.message?.take(200)}" }
            )
            loading = false
        }
    }

    fun saveText() {
        val e = editing ?: return
        scope.launch {
            loading = true
            val bytes = e.content.toByteArray(Charsets.UTF_8)
            // Original mode (stat) so chmod --reference restores it.
            val mode = ShizukuManager.exec(FilePower.statCommand(e.fullPath)).fold(
                onSuccess = { FilePower.parseStat(it)?.mode },
                onFailure = { null }
            ) ?: "644"
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val staging = "/data/local/tmp/jarvis-write-${System.currentTimeMillis()}"
            val w = ShizukuManager.exec(
                FilePower.writeTextCommand(staging, b64, e.fullPath, mode), 30_000
            )
            w.fold(
                onSuccess = {
                    // Verify by stat: size must match the new content.
                    val ok = ShizukuManager.exec(FilePower.statCommand(e.fullPath)).fold(
                        onSuccess = { FilePower.parseStat(it)?.sizeBytes == bytes.size.toLong() },
                        onFailure = { false }
                    )
                    if (ok) {
                        editing = null
                        error = ""
                        load(path)
                    } else {
                        error = "Save hua lekin verify nahi hua — file dobara check karo."
                    }
                },
                onFailure = { ex -> error = "Save nahi hua: ${ex.message?.take(200)}" }
            )
            loading = false
        }
    }

    fun delete(target: DeleteTarget) {
        scope.launch {
            loading = true
            if (!FilePower.isSafeDeleteTarget(target.fullPath)) {
                error = "Ye delete nahi kar sakte — protected path hai: ${target.fullPath}"
                loading = false
                return@launch
            }
            val r = ShizukuManager.exec(FilePower.deleteCommand(target.fullPath))
            r.fold(
                onSuccess = { load(path) },
                onFailure = { ex -> error = "Delete nahi hua: ${ex.message?.take(200)}" }
            )
            loading = false
        }
    }

    fun runCopyMove(target: CopyMoveTarget, destDir: String) {
        scope.launch {
            loading = true
            val dest = if (destDir == "/") "/${target.entry.name}" else "$destDir/${target.entry.name}"
            val cmd = if (target.isMove) {
                FilePower.moveCommand(target.fullPath, dest)
            } else {
                FilePower.copyCommand(target.fullPath, dest)
            }
            val r = ShizukuManager.exec(cmd, 60_000)
            r.fold(
                onSuccess = { copyMove = null; load(path) },
                onFailure = { ex -> error = "${if (target.isMove) "Move" else "Copy"} nahi hua: ${ex.message?.take(200)}" }
            )
            loading = false
        }
    }

    fun share(entry: FilePower.FileEntry) {
        runCatching {
            val file = File(fullPath(entry))
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Share ${entry.name}").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { e -> error = "Share nahi hua: ${e.message?.take(150)}" }
    }

    fun backupApk() {
        val pkg = apkPkg.trim()
        if (!ShellAutomation.isValidPackage(pkg)) {
            apkResult = "Package naam galat hai — jaise com.whatsapp"
            return
        }
        scope.launch {
            apkResult = "Backup chal raha hai…"
            val destDir = "/sdcard/Jarvis/apks"
            val mk = ShizukuManager.exec(FilePower.mkdirCommand(destDir))
            if (mk.isFailure) {
                apkResult = "Folder nahi bana: ${mk.exceptionOrNull()?.message?.take(150)}"
                return@launch
            }
            val apkPath = ShizukuManager.exec(FilePower.apkPathCommand(pkg)).fold(
                onSuccess = { FilePower.parsePmPath(it) },
                onFailure = { null }
            )
            if (apkPath.isNullOrBlank()) {
                apkResult = "APK path nahi mila — app installed hai? ($pkg)"
                return@launch
            }
            val dest = "$destDir/$pkg.apk"
            val cp = ShizukuManager.exec(FilePower.apkBackupCommand(apkPath, dest), 60_000)
            cp.fold(
                onSuccess = { apkResult = "Backup ho gaya: $dest" },
                onFailure = { e -> apkResult = "Backup fail: ${e.message?.take(200)}" }
            )
        }
    }

    LaunchedEffect(Unit) { load(path) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Files", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFFB85C38))
        Text(
            text = "Shizuku se /sdcard, Android/data aur obb — read, edit, copy, delete. " +
                "/data/data band hai (neeche dekho). Delete hamesha pooch kar hota hai.",
            fontSize = 12.sp,
            color = Color(0xFF8A7A70)
        )

        // Path bar + up.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { load(FilePower.parentPath(path)) },
                enabled = !loading && path != "/sdcard" && path != "/",
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A7A70))
            ) { Text("Up", color = Color.White, fontSize = 12.sp) }
            Text(
                text = path,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF3B2F2A),
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        // Quick locations (scrollable row of the browse roots).
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            FilePower.BROWSE_ROOTS.forEach { (root, label) ->
                TextButton(onClick = { load(root) }) { Text(label, fontSize = 12.sp) }
            }
            TextButton(onClick = { load("/data/data") }) { Text("/data/data ✗", fontSize = 12.sp) }
        }

        if (shizukuState !is ShizukuManager.ShizukuState.Ready) {
            Text(
                text = "Shizuku ready nahi hai — Files tab ke liye Shizuku server start karo " +
                    "(Permissions tab, Shizuku card).",
                fontSize = 12.sp,
                color = Color(0xFFB85C38)
            )
        }
        if (loading) CircularProgressIndicator()
        if (error.isNotBlank()) {
            Text(error, fontSize = 12.sp, color = Color(0xFFB85C38))
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(entries, key = { "$path/${it.name}" }) { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row {
                            Text(
                                text = (if (entry.isDir) "📁 " else "📄 ") + entry.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF3B2F2A),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (entry.isDir) "" else FilePower.humanSize(entry.sizeBytes),
                                fontSize = 11.sp,
                                color = Color(0xFF8A7A70)
                            )
                        }
                        if (entry.isLink && entry.linkTarget != null) {
                            Text(
                                text = "→ ${entry.linkTarget}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF8A7A70)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (entry.isDir) {
                                TextButton(onClick = { load(fullPath(entry)) }) { Text("Open", fontSize = 12.sp) }
                            } else if (FilePower.isTextFile(entry.name)) {
                                TextButton(onClick = { openText(entry) }) { Text("Edit", fontSize = 12.sp) }
                            }
                            TextButton(onClick = { share(entry) }) { Text("Share", fontSize = 12.sp) }
                            TextButton(onClick = { copyMove = CopyMoveTarget(entry, fullPath(entry), isMove = false) }) { Text("Copy", fontSize = 12.sp) }
                            TextButton(onClick = { copyMove = CopyMoveTarget(entry, fullPath(entry), isMove = true) }) { Text("Move", fontSize = 12.sp) }
                            TextButton(onClick = { confirmDelete = DeleteTarget(entry, fullPath(entry)) }) { Text("Delete", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }

        // APK backup.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1DC))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("APK backup", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFB85C38))
                Text(
                    text = "Kisi installed app ka APK /sdcard/Jarvis/apks/ me backup karo.",
                    fontSize = 12.sp,
                    color = Color(0xFF8A7A70)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = apkPkg,
                        onValueChange = { apkPkg = it },
                        label = { Text("Package — jaise com.whatsapp", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { backupApk() },
                        enabled = !loading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D5B))
                    ) { Text("Backup", color = Color.White, fontSize = 12.sp) }
                }
                if (apkResult.isNotBlank()) {
                    Text(apkResult, fontSize = 12.sp, color = Color(0xFF3B2F2A))
                }
            }
        }
    }

    // Text editor dialog.
    val editTarget = editing
    if (editTarget != null) {
        var draft by remember(editTarget) { mutableStateOf(editTarget.content) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit: ${editTarget.entry.name}", fontSize = 14.sp) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    singleLine = false
                )
            },
            confirmButton = {
                Button(onClick = { editing = editTarget.copy(content = draft); saveText() }) {
                    Text("Save karo", fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel", fontSize = 12.sp) }
            }
        )
    }

    // Delete confirmation.
    val deleteTarget = confirmDelete
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete karo?", fontSize = 14.sp) },
            text = {
                Text(
                    text = "${deleteTarget.entry.name} delete ho jayega — wapas nahi aayega." +
                        if (deleteTarget.entry.isDir) " (poora folder, andar ki files samet)" else "",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirmDelete = null; delete(deleteTarget) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB85C38))
                ) { Text("Delete", color = Color.White, fontSize = 12.sp) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel", fontSize = 12.sp) }
            }
        )
    }

    // Copy / move destination dialog.
    val cmTarget = copyMove
    if (cmTarget != null) {
        var dest by remember(cmTarget) {
            mutableStateOf(FilePower.parentPath(cmTarget.fullPath))
        }
        AlertDialog(
            onDismissRequest = { copyMove = null },
            title = { Text(if (cmTarget.isMove) "Move: ${cmTarget.entry.name}" else "Copy: ${cmTarget.entry.name}", fontSize = 14.sp) },
            text = {
                OutlinedTextField(
                    value = dest,
                    onValueChange = { dest = it },
                    label = { Text("Destination folder", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = { runCopyMove(cmTarget, dest) }) {
                    Text(if (cmTarget.isMove) "Move karo" else "Copy karo", fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { copyMove = null }) { Text("Cancel", fontSize = 12.sp) }
            }
        )
    }
}

private data class EditingFile(val entry: FilePower.FileEntry, val fullPath: String, val content: String)

private data class DeleteTarget(val entry: FilePower.FileEntry, val fullPath: String)

private data class CopyMoveTarget(val entry: FilePower.FileEntry, val fullPath: String, val isMove: Boolean)
