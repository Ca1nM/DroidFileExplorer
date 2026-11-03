package com.ca1nm.droidfileexplorer.presentation.browser

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ca1nm.droidfileexplorer.data.formatSize
import com.ca1nm.droidfileexplorer.data.source.ArchiveTools
import com.ca1nm.droidfileexplorer.domain.model.FileItem
import java.io.File

/**
 * An archive opened like a folder: tap a folder to go into it, Back to come out, long-press to tick rows.
 * The extract options sit in a bar at the top. Rows are the same ones the file list uses, so it looks the same.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchiveFolderView(v: ArchiveViewState, vm: FileBrowserViewModel) {
    val c = vm.archive
    val archive = File(v.archivePath)
    val all = v.entries

    Dialog(
        onDismissRequest = { c.back() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                MiddleEllipsisText(archive.name, MaterialTheme.typography.titleMedium)
                                // Inside a nested archive, show the way in: outer.zip/folder › inner.7z/folder
                                val trail = generateSequence(v.parent) { it.parent }.toList().asReversed()
                                    .joinToString("") { File(it.archivePath).name + "/" + it.folder + " › " }
                                Text(
                                    trail + "/" + v.folder,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { c.back() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = when {
                                        v.folder.isNotEmpty() -> "Up"
                                        v.parent != null -> "Back to ${File(v.parent.archivePath).name}"
                                        else -> "Close archive"
                                    }
                                )
                            }
                        },
                        actions = {
                            if (all != null) IconButton(onClick = { c.selectAllHere() }) {
                                Icon(Icons.Default.DoneAll, contentDescription = "Select all in this folder")
                            }
                            if (v.selected.isNotEmpty()) IconButton(onClick = { c.clearSelection() }) {
                                Icon(Icons.Default.RemoveDone, contentDescription = "Clear selection")
                            }
                        }
                    )
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    when {
                        v.needsPassword && all == null -> PasswordPrompt(v.error) { c.unlock(it) }
                        v.error != null && all == null -> Text(
                            v.error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp)
                        )
                        all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else -> ArchiveContents(v, all, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordPrompt(error: String?, onUnlock: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("This archive is password-protected.")
        OutlinedTextField(
            pw, { pw = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { onUnlock(pw) }, enabled = pw.isNotEmpty()) { Text("Unlock") }
    }
}

@Composable
private fun ArchiveContents(v: ArchiveViewState, all: List<com.ca1nm.droidfileexplorer.data.source.ArchiveEntryInfo>, vm: FileBrowserViewModel) {
    val c = vm.archive
    val files = remember(all) { all.filter { !it.isDirectory } }
    val totalSize = remember(files) { files.sumOf { it.size } }
    val children = remember(all, v.folder) { ArchiveTools.childrenOf(all, v.folder) }
    // Files under each visible subfolder, counted in one pass, for the "N items" label.
    val counts = remember(all, v.folder) {
        val prefix = if (v.folder.isEmpty()) "" else v.folder + "/"
        val map = HashMap<String, Int>()
        for (e in files) {
            if (!e.path.startsWith(prefix)) continue
            val rest = e.path.removePrefix(prefix)
            if ('/' in rest) map.merge(prefix + rest.substringBefore('/'), 1, Int::plus)
        }
        map
    }
    val selectedFiles = remember(files, v.selected) {
        if (v.selected.isEmpty()) 0 else files.count { f -> v.selected.any { s -> f.path == s || f.path.startsWith("$s/") } }
    }

    // ---- Extract options ----
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (v.busy) {
            OutlinedButton(onClick = { c.cancel() }) {
                Icon(Icons.Default.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cancel")
            }
        } else {
            Button(onClick = { c.extract(all = true) }, enabled = files.isNotEmpty()) {
                Icon(Icons.Default.Unarchive, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Extract all")
            }
            FilledTonalButton(onClick = { c.extract(all = false) }, enabled = selectedFiles > 0) {
                Icon(Icons.Default.Unarchive, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(if (selectedFiles > 0) "Extract selected ($selectedFiles)" else "Extract selected")
            }
            OutlinedButton(onClick = { c.test() }) {
                Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Test")
            }
        }
    }
    if (v.busy) {
        val p = v.progress
        if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
    val line = when {
        v.status != null -> v.status + (if (v.busy && v.progress != null) " ${(v.progress * 100).toInt()}%" else "")
        v.selected.isNotEmpty() -> "$selectedFiles file(s) selected"
        else -> "${files.size} files · ${formatSize(totalSize)}"
    }
    Text(
        line,
        Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (v.status != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )

    // ---- Contents ----
    if (children.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Nothing in here", textAlign = TextAlign.Center)
            }
        }
        return
    }
    val selecting = v.selected.isNotEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(children, key = { it.path }) { e ->
            val item = FileItem(
                name = e.path.substringAfterLast('/'),
                path = e.path,
                isDirectory = e.isDirectory,
                size = e.size,
                lastModified = 0L,
                childCount = if (e.isDirectory) counts[e.path] else null
            )
            FileItemRow(
                item = item,
                isSelected = e.path in v.selected,
                selectionMode = selecting,
                starred = false,
                tags = emptyList(),
                showThumbnails = false,      // there is no file on disk to thumbnail yet
                onRowTap = {
                    when {
                        selecting -> c.toggle(e.path)
                        e.isDirectory -> c.enter(e.path)
                        !v.busy -> c.openEntry(e.path, e.size)
                    }
                },
                onSelectionToggle = { c.toggle(e.path) }
            )
        }
    }
}
