package com.sza.androidfolderexplorer.presentation.browser

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.sza.androidfolderexplorer.data.FileKind
import com.sza.androidfolderexplorer.data.FileTypes
import com.sza.androidfolderexplorer.data.formatSize
import com.sza.androidfolderexplorer.data.ops.JobService
import com.sza.androidfolderexplorer.data.settings.CustomAction
import com.sza.androidfolderexplorer.data.source.ArchiveEntryInfo
import com.sza.androidfolderexplorer.data.source.ArchiveExtractor
import com.sza.androidfolderexplorer.data.source.ArchiveTools
import com.sza.androidfolderexplorer.data.source.FileOps
import com.sza.androidfolderexplorer.domain.model.FileItem
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows whichever modal [dialog] describes. Closing goes through the view model. */
@Composable
fun BrowserDialogHost(dialog: BrowserDialog, state: FileBrowserUiState, vm: FileBrowserViewModel) {
    val close = { vm.closeDialog() }
    when (dialog) {
        is BrowserDialog.Rename -> PromptDialog(
            title = "Rename", label = "Name", initial = File(dialog.path).name, confirm = "Rename",
            onDismiss = close, onConfirm = { vm.rename(dialog.path, it); close() }
        )
        is BrowserDialog.NewItem -> PromptDialog(
            title = if (dialog.folder) "New folder" else "New file", label = "Name", initial = "", confirm = "Create",
            onDismiss = close, onConfirm = { vm.createItem(it, dialog.folder); close() }
        )
        is BrowserDialog.Properties -> PropertiesDialog(dialog.path, vm, close)
        is BrowserDialog.Compress -> CompressDialog(dialog.paths, state.currentPath, vm, close)
        is BrowserDialog.Tags -> TagsDialog(dialog.paths, vm, close)
        is BrowserDialog.Checksum -> ChecksumDialog(dialog.path, vm, close)
        is BrowserDialog.SetDate -> SetDateDialog(dialog.path, vm, close)
        is BrowserDialog.Split -> SplitDialog(dialog.path, vm, close)
        is BrowserDialog.Compare -> CompareDialog(dialog.a, dialog.b, vm, close)
        is BrowserDialog.ArchiveBrowser -> ArchiveBrowserDialog(dialog.path, vm, close)
        is BrowserDialog.BulkRename -> BulkRenameDialog(dialog.paths, vm, close)
        is BrowserDialog.ImageBatch -> ImageBatchDialog(dialog.paths, vm, close)
        is BrowserDialog.Actions -> ActionsDialog(dialog.paths, state.currentPath, vm, close)
        is BrowserDialog.OpenAs -> OpenAsDialog(dialog.path, vm, close)
        is BrowserDialog.ApkInfo -> ApkInfoDialog(dialog.path, vm, close)
        is BrowserDialog.Symlink -> PromptDialog(
            title = "Create symlink", label = "Link name (in this folder)", initial = File(dialog.path).name + "-link",
            confirm = "Create", onDismiss = close,
            onConfirm = { name ->
                vm.launchOp("Symlink") {
                    vm.fileOps.symlink(dialog.path, File(state.currentPath, name.trim()).absolutePath)
                    vm.showSnackbar("Created link $name")
                }
                close()
            }
        )
        is BrowserDialog.SecureDelete -> AlertDialog(
            onDismissRequest = close,
            icon = { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Securely delete ${dialog.paths.size} item(s)?") },
            text = {
                Text("The contents are overwritten with random data and then removed. This cannot be undone and " +
                    "skips the trash.\n\nOn flash storage the phone may keep older copies of the data, so this " +
                    "reduces recovery chances but cannot guarantee them.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.launchOp("Secure delete") {
                            dialog.paths.forEach { vm.fileOps.secureDelete(it) }
                            vm.showSnackbar("Securely deleted ${dialog.paths.size} item(s)")
                        }
                        close()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Overwrite & delete") }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } }
        )
        is BrowserDialog.FolderSize -> FolderSizeDialog(dialog.path, vm, close)
        is BrowserDialog.Viewer -> ContentViewerDialog(dialog.path, dialog.kind, vm, close)
        BrowserDialog.RecursiveSearch -> SearchDialog(state, vm, close)
        BrowserDialog.Recents -> RecentsDialog(vm, close)
    }
}

// ---- shared pieces ----

@Composable
fun PromptDialog(
    title: String, label: String, initial: String, confirm: String,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit,
    extra: (@Composable ColumnScope.() -> Unit)? = null
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                extra?.invoke(this)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun InfoDialog(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
private fun KeyValue(key: String, value: String, mono: Boolean = false) {
    Column {
        Text(key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
        }
    }
}

private fun fmtDate(ms: Long): String =
    if (ms <= 0) "Unknown" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(ms))

// ---- Properties ----

@Composable
private fun PropertiesDialog(path: String, vm: FileBrowserViewModel, onClose: () -> Unit) {
    val f = File(path)
    val user by vm.userData.state.collectAsState()
    var stats by remember { mutableStateOf<FileOps.FolderStats?>(null) }
    var calculating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isDir = f.isDirectory
    InfoDialog(f.name.ifEmpty { path }, onClose) {
        KeyValue("Full path", path, mono = true)
        KeyValue("Type", if (isDir) "Folder" else FileTypes.mimeOf(f.name))
        if (!isDir) KeyValue("Size", "${formatSize(f.length())}  (${f.length()} bytes)")
        KeyValue("Modified", fmtDate(f.lastModified()))
        KeyValue("Permissions", buildString {
            append(if (f.canRead()) "read " else ""); append(if (f.canWrite()) "write " else ""); append(if (f.canExecute()) "execute" else "")
        }.trim().ifEmpty { "none visible to this app" })
        if (f.isHidden || f.name.startsWith(".")) KeyValue("Hidden", "Yes")
        KeyValue("Starred", if (path in user.favourites) "Yes" else "No")
        user.tags[path]?.let { KeyValue("Tags", it.joinToString(", ")) }
        if (isDir) {
            val s = stats
            if (s != null) KeyValue("Contents", "${formatSize(s.bytes)} in ${s.files} files, ${s.folders} folders")
            else OutlinedButton(
                onClick = {
                    calculating = true
                    scope.launch { stats = withContext(Dispatchers.IO) { runCatching { vm.fileOps.folderStats(path) }.getOrNull() }; calculating = false }
                },
                enabled = !calculating
            ) {
                if (calculating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Calculate folder size")
            }
        }
    }
}

@Composable
private fun FolderSizeDialog(path: String, vm: FileBrowserViewModel, onClose: () -> Unit) {
    var stats by remember { mutableStateOf<FileOps.FolderStats?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        stats = withContext(Dispatchers.IO) { runCatching { vm.fileOps.folderStats(path) }.getOrNull() }
        failed = stats == null
    }
    InfoDialog(File(path).name, onClose) {
        val s = stats
        when {
            s != null -> { KeyValue("Total size", formatSize(s.bytes)); KeyValue("Files", s.files.toString()); KeyValue("Folders", s.folders.toString()) }
            failed -> Text("Couldn't read this folder")
            else -> { CircularProgressIndicator(); Text("Counting…") }
        }
    }
}

// ---- Compress ----

@Composable
private fun CompressDialog(paths: List<String>, folder: String, vm: FileBrowserViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    PromptDialog(
        title = "Create zip", label = "Archive name",
        initial = if (paths.size == 1) File(paths[0]).nameWithoutExtension else "Archive",
        confirm = "Compress", onDismiss = onClose,
        onConfirm = { raw ->
            val base = raw.trim().removeSuffix(".zip")
            val pw = password
            vm.launchOp("Compress") {
                val dest = FileOps.uniqueName(File(folder), "$base.zip")
                JobService.update(context, "Compressing", dest.name, -1)
                try {
                    ArchiveTools.createZip(paths.map { File(it) }, dest, pw.ifEmpty { null }) { name ->
                        JobService.update(context, "Compressing", name, -1)
                    }
                } finally { JobService.stop(context) }
                vm.showSnackbar("Created ${dest.name}" + if (pw.isNotEmpty()) " (AES-256 encrypted)" else "")
            }
            onClose()
        },
        extra = {
            OutlinedTextField(
                password, { password = it }, label = { Text("Password (optional)") }, singleLine = true,
                visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { show = !show }) { Icon(Icons.Default.Visibility, "Show password") } },
                modifier = Modifier.fillMaxWidth()
            )
            Text("${paths.size} item(s) will be added.", style = MaterialTheme.typography.bodySmall)
        }
    )
}

// ---- Tags ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsDialog(paths: List<String>, vm: FileBrowserViewModel, onClose: () -> Unit) {
    val user by vm.userData.state.collectAsState()
    var newTag by remember { mutableStateOf("") }
    val all = remember(user.tags) { vm.userData.allTags() }
    AlertDialog(
        onDismissRequest = { vm.refreshArrangement(); onClose() },
        title = { Text("Tags for ${paths.size} item(s)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    all.forEach { tag ->
                        val on = paths.all { tag in (user.tags[it] ?: emptyList()) }
                        FilterChip(
                            selected = on,
                            onClick = { if (on) vm.userData.removeTag(paths, tag) else vm.userData.addTag(paths, tag) },
                            label = { Text(tag) }
                        )
                    }
                }
                if (all.isEmpty()) Text("No tags yet. Add one below.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newTag, { newTag = it }, label = { Text("New tag") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.userData.addTag(paths, newTag); newTag = "" }, enabled = newTag.isNotBlank()) {
                        Icon(Icons.Default.Add, "Add tag")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.refreshArrangement(); onClose() }) { Text("Done") } }
    )
}

// ---- Checksum ----

@Composable
private fun ChecksumDialog(path: String, vm: FileBrowserViewModel, onClose: () -> Unit) {
    val algos = listOf("MD5", "SHA-1", "SHA-256")
    val results = remember(path) { mutableStateMapOf<String, String>() }
    var error by remember { mutableStateOf<String?>(null) }
    var expected by remember { mutableStateOf("") }
    LaunchedEffect(path) {
        try {
            for (a in algos) results[a] = withContext(Dispatchers.IO) { vm.fileOps.checksum(path, a) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Couldn't read the file" }
    }
    InfoDialog(File(path).name, onClose) {
        algos.forEach { a -> KeyValue(a, results[a] ?: "calculating…", mono = true) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(expected, { expected = it.trim() }, label = { Text("Paste a checksum to verify") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (expected.isNotEmpty()) {
            val hit = results.entries.firstOrNull { it.value.equals(expected, ignoreCase = true) }
            if (hit != null) Text("Matches ${hit.key}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            else if (results.size == algos.size) Text("Does not match", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---- Set date ----

private val DateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }

@Composable
private fun SetDateDialog(path: String, vm: FileBrowserViewModel, onClose: () -> Unit) {
    val f = File(path)
    val exifDate = remember(path) { exifTakenMillis(path) }
    var text by remember { mutableStateOf(DateFmt.format(Date(f.lastModified()))) }
    var error by remember { mutableStateOf<String?>(null) }
    PromptDialog(
        title = "Change modified date", label = "yyyy-MM-dd HH:mm", initial = text, confirm = "Apply", onDismiss = onClose,
        onConfirm = { input ->
            val ms = runCatching { DateFmt.parse(input.trim())!!.time }.getOrNull()
            if (ms == null) error = "Use the format 2026-11-02 14:30"
            else {
                val old = f.lastModified()
                vm.launchOp("Set date") {
                    vm.fileOps.setModified(path, ms)
                    vm.showSnackbar("Date changed", { vm.fileOps.setModified(path, old); vm.navigateTo(vm.uiState.value.currentPath) })
                }
                onClose()
            }
        },
        extra = {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (exifDate != null) {
                OutlinedButton(onClick = {
                    vm.launchOp("Set date") { vm.fileOps.setModified(path, exifDate); vm.showSnackbar("Set to the photo's date") }
                    onClose()
                }) { Text("Use photo's taken date (${DateFmt.format(Date(exifDate))})") }
            }
            OutlinedButton(onClick = {
                vm.launchOp("Set date") { vm.fileOps.setModified(path, System.currentTimeMillis()); vm.showSnackbar("Set to now") }
                onClose()
            }) { Text("Set to now") }
        }
    )
}

/** The EXIF "date taken" of an image, in epoch millis, or null. */
internal fun exifTakenMillis(path: String): Long? = runCatching {
    if (!FileTypes.isImage(path)) return null
    val raw = ExifInterface(path).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        ?: ExifInterface(path).getAttribute(ExifInterface.TAG_DATETIME) ?: return null
    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
}.getOrNull()

// ---- Split / merge ----

@Composable
private fun SplitDialog(path: String, vm: FileBrowserViewModel, onClose: () -> Unit) {
    val isPart = FileTypes.kindOf(File(path).name) == FileKind.SPLIT_PART
    if (isPart) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Join parts") },
            text = { Text("${File(path).name} looks like one part of a split file (name.001, name.002 …). Join all parts in this folder into one file?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.launchOp("Join") { val out = vm.fileOps.merge(path); vm.showSnackbar("Created ${File(out).name}") }
                    onClose()
                }) { Text("Join") }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
        )
    } else {
        PromptDialog(
            title = "Split file", label = "Part size in MB", initial = "100", confirm = "Split", onDismiss = onClose,
            onConfirm = { mb ->
                val bytes = (mb.toDoubleOrNull() ?: 0.0) * 1024 * 1024
                if (bytes >= 1024) {
                    vm.launchOp("Split") { val parts = vm.fileOps.split(path, bytes.toLong()); vm.showSnackbar("Split into ${parts.size} part(s)") }
                    onClose()
                }
            }
        )
    }
}

// ---- Compare ----

@Composable
private fun CompareDialog(a: String, b: String, vm: FileBrowserViewModel, onClose: () -> Unit) {
    var result by remember { mutableStateOf<FileOps.CompareResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(a, b) {
        try { result = withContext(Dispatchers.IO) { vm.fileOps.compare(a, b) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
    }
    InfoDialog("Compare", onClose) {
        KeyValue("A", File(a).name); KeyValue("B", File(b).name)
        val r = result
        when {
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            r == null -> { CircularProgressIndicator(); Text("Comparing…") }
            else -> {
                Text(r.summary, fontWeight = FontWeight.SemiBold,
                    color = if (r.identical) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if (r.onlyInA.isNotEmpty()) { Text("Only in A", style = MaterialTheme.typography.labelMedium); r.onlyInA.take(50).forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } }
                if (r.onlyInB.isNotEmpty()) { Text("Only in B", style = MaterialTheme.typography.labelMedium); r.onlyInB.take(50).forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } }
                if (r.different.isNotEmpty()) { Text("Different", style = MaterialTheme.typography.labelMedium); r.different.take(50).forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } }
                r.textDiff.filter { it.kind != ' ' }.take(200).forEach { line ->
                    val c = if (line.kind == '+') Color(0xFF2E7D32) else Color(0xFFC62828)
                    Text("${line.kind} ${line.text}", color = c, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

// ---- Archive browser ----

@Composable
private fun ArchiveBrowserDialog(path: String, vm: FileBrowserViewModel, onClose: () -> Unit) {
    val archive = File(path)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var needPassword by remember { mutableStateOf(ArchiveTools.isEncryptedZip(archive)) }
    var entries by remember { mutableStateOf<List<ArchiveEntryInfo>?>(null) }
    var folder by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(path, loadKey, needPassword) {
        if (needPassword && password.isEmpty()) return@LaunchedEffect
        error = null
        try { entries = ArchiveTools.list(archive, password.ifEmpty { null }) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Can't read this archive"; entries = null }
    }

    fun extractTarget() = FileOps.uniqueName(archive.parentFile!!, ArchiveExtractor.folderNameFor(archive.name))
    fun run(label: String, block: suspend () -> String) {
        busy = true; status = "$label…"
        scope.launch {
            status = try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                     catch (e: Exception) { "$label failed: ${e.message}" }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(archive.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (needPassword && entries == null) {
                    Text("This zip is password-protected.")
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button(onClick = { loadKey++ }, enabled = password.isNotEmpty()) { Text("Unlock") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val all = entries
                if (all != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (folder.isNotEmpty()) IconButton(onClick = { folder = folder.substringBeforeLast('/', "") }) {
                            Icon(Icons.Default.ArrowUpward, "Up")
                        }
                        Text("/$folder", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val children = remember(all, folder) { ArchiveTools.childrenOf(all, folder) }
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(children, key = { it.path }) { e ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = e.isDirectory) { folder = e.path }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (e.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null,
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!e.isDirectory) Text(formatSize(e.size), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(enabled = !busy, onClick = {
                                    run("Extract") {
                                        val dest = extractTarget()
                                        val n = ArchiveTools.extractEntry(archive, e.path, dest, password.ifEmpty { null })
                                        vm.navigateTo(vm.uiState.value.currentPath)
                                        "Extracted $n file(s) to ${dest.name}"
                                    }
                                }) { Icon(Icons.Default.Unarchive, "Extract ${e.path}") }
                            }
                        }
                    }
                    Text("${all.count { !it.isDirectory }} files in archive", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = !busy, onClick = {
                    run("Test") { ArchiveTools.test(archive, password.ifEmpty { null }).let { (if (it.ok) "✓ " else "✗ ") + it.message } }
                }) { Text("Test") }
                TextButton(enabled = !busy, onClick = {
                    run("Extract all") {
                        val dest = extractTarget()
                        val n = if (needPassword) ArchiveTools.extractZipWithPassword(archive, dest, password)
                                else ArchiveExtractor.extract(archive, dest) { }
                        vm.navigateTo(vm.uiState.value.currentPath)
                        "Extracted $n file(s) to ${dest.name}"
                    }
                }) { Text("Extract all") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    )
}

// ---- Bulk rename ----

@Composable
private fun BulkRenameDialog(paths: List<String>, vm: FileBrowserViewModel, onClose: () -> Unit) {
    var regexMode by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf("Photo_{n}") }
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("1") }
    val sorted = remember(paths) { paths.sortedBy { File(it).name.lowercase() } }

    fun newNames(): List<String>? {
        val startN = start.toIntOrNull() ?: 1
        val width = (startN + sorted.size - 1).toString().length
        return try {
            sorted.mapIndexed { i, p ->
                val f = File(p)
                val ext = f.extension
                if (regexMode) {
                    if (find.isEmpty()) f.name else f.name.replace(Regex(find), replace)
                } else {
                    val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(f.lastModified()))
                    val taken = exifTakenMillis(p)?.let { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(it)) } ?: date
                    pattern.replace("{n}", (startN + i).toString().padStart(width, '0'))
                        .replace("{name}", f.nameWithoutExtension).replace("{date}", date).replace("{taken}", taken)
                        .replace("{ext}", ext) + if (ext.isNotEmpty() && !pattern.contains("{ext}")) ".$ext" else ""
                }
            }
        } catch (e: Exception) { null }
    }
    val names = newNames()
    val problem = when {
        names == null -> "Invalid pattern"
        names.any { it.isBlank() || '/' in it } -> "A name is empty or contains /"
        names.toSet().size != names.size -> "Two files would get the same name"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Rename ${paths.size} files") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(!regexMode, { regexMode = false }, label = { Text("Pattern") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(regexMode, { regexMode = true }, label = { Text("Regex") })
                }
                if (regexMode) {
                    OutlinedTextField(find, { find = it }, label = { Text("Find (regex)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(replace, { replace = it }, label = { Text("Replace with ($1 for groups)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(pattern, { pattern = it }, label = { Text("Pattern") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("{n} number  {name} old name  {ext}  {date} modified  {taken} photo date",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(start, { start = it.filter(Char::isDigit) }, label = { Text("Start number") }, singleLine = true)
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                names?.take(6)?.forEachIndexed { i, n ->
                    Text("${File(sorted[i]).name}  →  $n", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                if ((names?.size ?: 0) > 6) Text("…", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null && names != null, onClick = {
                val targets = names!!
                vm.launchOp("Rename") {
                    val pairs = sorted.zip(targets).filter { (p, n) -> File(p).name != n }
                    renameAll(vm, pairs.map { (p, n) -> p to File(File(p).parentFile, n).absolutePath })
                    val reverse = pairs.map { (p, n) -> File(File(p).parentFile, n).absolutePath to p }
                    vm.showSnackbar("Renamed ${pairs.size} file(s)", { renameAll(vm, reverse); vm.navigateTo(vm.uiState.value.currentPath) })
                }
                onClose()
            }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

/** Renames via temporary names first so swapping or shifting names can't collide. */
private fun renameAll(vm: FileBrowserViewModel, pairs: List<Pair<String, String>>) {
    val temps = pairs.mapIndexed { i, (from, _) -> from to File(File(from).parentFile, ".afe-tmp-$i-${System.nanoTime()}").absolutePath }
    temps.forEach { (from, tmp) -> vm.fileOps.rename(from, tmp) }
    temps.zip(pairs).forEach { (t, p) ->
        val (_, tmp) = t
        val (orig, final) = p
        if (vm.fileOps.exists(final)) {
            vm.fileOps.rename(tmp, orig)
            throw java.io.IOException("${File(final).name} already exists")
        }
        vm.fileOps.rename(tmp, final)
        vm.userData.onPathChanged(orig, final)
    }
}

// ---- Resize / convert images ----

private enum class OutFormat(val label: String, val ext: String) { JPEG("JPEG", "jpg"), PNG("PNG", "png"), WEBP("WebP", "webp") }

@Composable
private fun ImageBatchDialog(paths: List<String>, vm: FileBrowserViewModel, onClose: () -> Unit) {
    val images = remember(paths) { paths.filter { FileTypes.isImage(it) } }
    val edges = listOf(0 to "Original size", 1080 to "1080 px", 1920 to "1920 px", 2560 to "2560 px", 3840 to "4K (3840 px)")
    var edge by remember { mutableIntStateOf(1920) }
    var format by remember { mutableStateOf(OutFormat.JPEG) }
    var quality by remember { mutableFloatStateOf(85f) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Resize / convert ${images.size} image(s)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Longest side", style = MaterialTheme.typography.titleSmall)
                edges.forEach { (v, label) -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(edge == v, { edge = v }); Text(label) } }
                Text("Format", style = MaterialTheme.typography.titleSmall)
                Row { OutFormat.entries.forEach { f -> FilterChip(format == f, { format = f }, label = { Text(f.label) }, modifier = Modifier.padding(end = 6.dp)) } }
                if (format != OutFormat.PNG) {
                    Text("Quality ${quality.toInt()}%", style = MaterialTheme.typography.titleSmall)
                    Slider(quality, { quality = it }, valueRange = 40f..100f)
                }
                Text("New files are saved next to the originals; the originals are not changed.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = images.isNotEmpty(), onClick = {
                val maxEdge = edge; val fmt = format; val q = quality.toInt()
                vm.launchOp("Convert images") {
                    var done = 0
                    for (path in images) {
                        val src = File(path)
                        val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(src)) { dec, info, _ ->
                            dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                            val longest = maxOf(info.size.width, info.size.height)
                            if (maxEdge > 0 && longest > maxEdge) {
                                val f = maxEdge.toFloat() / longest
                                dec.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
                            }
                        }
                        val suffix = if (maxEdge > 0) "-$maxEdge" else "-converted"
                        val out = FileOps.uniqueName(src.parentFile!!, src.nameWithoutExtension + suffix + "." + fmt.ext)
                        out.outputStream().buffered().use { o ->
                            val cf = when (fmt) {
                                OutFormat.JPEG -> android.graphics.Bitmap.CompressFormat.JPEG
                                OutFormat.PNG -> android.graphics.Bitmap.CompressFormat.PNG
                                OutFormat.WEBP -> if (android.os.Build.VERSION.SDK_INT >= 30) android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
                                                  else @Suppress("DEPRECATION") android.graphics.Bitmap.CompressFormat.WEBP
                            }
                            // JPEG has no alpha: flatten onto white so transparent PNGs don't turn black.
                            val toWrite = if (fmt == OutFormat.JPEG && bmp.hasAlpha()) {
                                android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                                    android.graphics.Canvas(it).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                                }
                            } else bmp
                            toWrite.compress(cf, q, o)
                        }
                        out.setLastModified(src.lastModified())
                        done++
                    }
                    vm.showSnackbar("Created $done image(s)")
                }
                onClose()
            }) { Text("Convert") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

// ---- Custom actions ----

@Composable
private fun ActionsDialog(paths: List<String>, folder: String, vm: FileBrowserViewModel, onClose: () -> Unit) {
    val user by vm.userData.state.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var viaShizuku by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    fun run(action: CustomAction) {
        running = true; output = null
        scope.launch {
            output = withContext(Dispatchers.IO) {
                buildString {
                    for (p in paths) {
                        val f = File(p)
                        val cmd = action.command.replace("{path}", q(p)).replace("{name}", q(f.name))
                            .replace("{dir}", q(f.parent ?: "")).replace("{base}", q(f.nameWithoutExtension)).replace("{ext}", q(f.extension))
                        appendLine("$ ${f.name}")
                        if (action.useShizuku) {
                            val (code, out) = vm.shizukuFileAccess.exec(cmd, folder)
                            append(out); if (code != 0) appendLine("(exit $code)")
                        } else {
                            val pr = ProcessBuilder("sh", "-c", cmd).directory(File(folder)).redirectErrorStream(true).start()
                            append(pr.inputStream.bufferedReader().readText())
                            val code = pr.waitFor(); if (code != 0) appendLine("(exit $code)")
                        }
                    }
                }.take(20000)
            }
            running = false
            vm.navigateTo(folder)
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Actions on ${paths.size} item(s)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                user.customActions.forEach { a ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { run(a) }, enabled = !running, modifier = Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(a.name + if (a.useShizuku) "  (Shizuku)" else "")
                                Text(a.command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        IconButton(onClick = { vm.userData.update { it.copy(customActions = it.customActions - a) } }) {
                            Icon(Icons.Default.Delete, "Delete action")
                        }
                    }
                }
                if (adding) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(command, { command = it }, label = { Text("Command, e.g. gzip -k {path}") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(viaShizuku, { viaShizuku = it }); Text("Run through Shizuku (shell access)")
                    }
                    Text("{path} {name} {dir} {base} {ext} are replaced for each file, already quoted.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = name.isNotBlank() && command.isNotBlank(), onClick = {
                        vm.userData.update { it.copy(customActions = it.customActions + CustomAction(name.trim(), command.trim(), viaShizuku)) }
                        adding = false; name = ""; command = ""
                    }) { Text("Save action") }
                } else OutlinedButton(onClick = { adding = true }) { Icon(Icons.Default.Add, null); Text("New action") }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                output?.let {
                    HorizontalDivider()
                    SelectionContainer { Text(it.ifBlank { "(no output)" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

// ---- Open as ----

@Composable
private fun OpenAsDialog(path: String, vm: FileBrowserViewModel, onClose: () -> Unit) {
    val name = File(path).name
    fun entry(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) {
        // inline row builder is defined below via Row()
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Open $name as…") },
        text = {
            Column {
                @Composable fun Option(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { action() }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Text(label) }
                }
                Option("Text", Icons.Default.Description) { onClose(); vm.openEditor(path) }
                Option("Hex dump", Icons.Default.Code) { vm.showDialog(BrowserDialog.Viewer(path, FileKindHint.HEX)) }
                Option("Image", Icons.Default.Image) { vm.viewImage(path) }
                Option("Video / audio", Icons.Default.Movie) { onClose(); vm.playFile(path) }
                Option("PDF", Icons.Default.PictureAsPdf) { vm.showDialog(BrowserDialog.Viewer(path, FileKindHint.PDF)) }
                Option("Photo details (EXIF)", Icons.Default.Info) { vm.showDialog(BrowserDialog.Viewer(path, FileKindHint.EXIF)) }
                Option("Audio tags", Icons.Default.MusicNote) { vm.showDialog(BrowserDialog.Viewer(path, FileKindHint.AUDIO_TAGS)) }
                Option("Another app…", Icons.Default.OpenInNew) { onClose(); vm.openExternally(path, chooser = true) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

// ---- APK installer ----

@Composable
private fun ApkInfoDialog(path: String, vm: FileBrowserViewModel, onClose: () -> Unit) {
    com.sza.androidfolderexplorer.presentation.install.InstallerDialog(path, onClose)
}

// ---- Recursive search ----

@Composable
private fun SearchDialog(state: FileBrowserUiState, vm: FileBrowserViewModel, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { vm.clearSearchResults(); onClose() },
        title = { Text("Search in ${File(state.currentPath).name.ifEmpty { "storage" }} and below") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Name contains") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(regex, { regex = it }); Text("Regex", Modifier.padding(end = 12.dp))
                    Checkbox(content, { content = it }); Text("Search text inside files")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.runSearch(query, regex, content) }, enabled = query.isNotBlank() && !state.searchRunning) { Text("Search") }
                    if (state.searchRunning) {
                        OutlinedButton(onClick = { vm.cancelSearch() }) { Text("Stop") }
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                val results = state.searchResults
                if (results != null) {
                    Text("${results.size} result(s)", style = MaterialTheme.typography.labelMedium)
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(results, key = { it.path }) { r ->
                            Column(Modifier.fillMaxWidth().clickable { vm.revealItem(r) }.padding(vertical = 6.dp)) {
                                Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (r.isDirectory) FontWeight.SemiBold else FontWeight.Normal)
                                Text(r.path.removePrefix(FileBrowserUiState.STORAGE_ROOT), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.clearSearchResults(); onClose() }) { Text("Close") } }
    )
}

// ---- Recent & starred ----

@Composable
private fun RecentsDialog(vm: FileBrowserViewModel, onClose: () -> Unit) {
    val user by vm.userData.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    fun item(path: String, dir: Boolean) = FileItem(File(path).name, path, dir, 0L, 0L)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Recent & starred") },
        text = {
            Column {
                TabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Recent") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Starred") })
                }
                val rows: List<Triple<String, Boolean, Long>> = if (tab == 0)
                    user.recents.filter { File(it.path).exists() }.map { Triple(it.path, it.isDirectory, it.time) }
                else user.favourites.filter { File(it).exists() }.map { Triple(it, File(it).isDirectory, 0L) }
                if (rows.isEmpty()) Text(if (tab == 0) "Nothing opened yet." else "Star items from the selection menu.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(rows, key = { it.first }) { (path, dir, time) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val it = item(path, dir)
                                if (dir) vm.revealItem(it) else { vm.closeDialog(); vm.openItem(it.copy(size = File(path).length())) }
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (dir) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(File(path).name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(path.removePrefix(FileBrowserUiState.STORAGE_ROOT).substringBeforeLast('/').ifEmpty { "/" },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (time > 0) Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(time)),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = { if (tab == 0) TextButton(onClick = { vm.userData.clearRecents() }) { Text("Clear recent") } }
    )
}
