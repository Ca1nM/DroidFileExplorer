package com.ca1nm.droidfileexplorer.presentation.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ca1nm.droidfileexplorer.data.source.FileOps
import com.ca1nm.droidfileexplorer.domain.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ROOT = "/storage/emulated/0"

/** Lists subfolders so the user can pick one without typing a path. */
@Composable
fun FolderPickerDialog(initial: String, fileOps: FileOps, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf(if (initial.startsWith(ROOT)) initial else ROOT) }
    var folders by remember { mutableStateOf<List<FileItem>?>(null) }
    LaunchedEffect(path) {
        folders = null
        folders = withContext(Dispatchers.IO) { runCatching { fileOps.list(path).filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() } }.getOrDefault(emptyList()) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a folder") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (path != ROOT) IconButton(onClick = { path = path.substringBeforeLast('/').ifEmpty { ROOT } }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
                    Text(path.removePrefix(ROOT).ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
                if (folders == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    val list = folders.orEmpty()
                    if (list.isEmpty() && folders != null) item { Text("No subfolders", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                    items(list, key = { it.path }) { f ->
                        Row(Modifier.fillMaxWidth().clickable { path = f.path }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp)); Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(path) }) { Text("Use this folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
