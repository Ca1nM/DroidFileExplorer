package com.sza.androidfolderexplorer.presentation.trash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sza.androidfolderexplorer.data.formatSize
import com.sza.androidfolderexplorer.data.settings.AppSettings
import com.sza.androidfolderexplorer.data.source.TrashEntry
import com.sza.androidfolderexplorer.data.source.TrashManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trash: TrashManager,
    private val appSettings: AppSettings
) : ViewModel() {
    val entries: StateFlow<List<TrashEntry>> = trash.entries
    val retentionDays get() = appSettings.state.value.trashDays
    var message by mutableStateOf<String?>(null)

    init {
        // Entries whose data has vanished from the trash folder can never be restored; say so up front.
        viewModelScope.launch {
            val gone = trash.prune()
            if (gone.isNotEmpty()) {
                message = "${gone.size} item(s) were no longer in the trash folder, so they were removed from this list: " +
                    gone.joinToString { it.name }
            }
        }
    }

    fun restore(list: List<TrashEntry>) = viewModelScope.launch {
        val r = trash.restore(list)
        message = buildString {
            append("Restored ${r.restored.size} of ${list.size}")
            if (r.missing.isNotEmpty()) {
                append(". Not restorable, their data is no longer in the trash folder: ")
                append(r.missing.joinToString { it.name })
            }
            if (r.failed.isNotEmpty()) append(". Failed (is the original folder writable?): ${r.failed.joinToString { it.name }}")
        }
    }
    fun deleteForever(list: List<TrashEntry>) = viewModelScope.launch {
        trash.deleteForever(list); message = "Deleted ${list.size} item(s) permanently"
    }
    fun empty() = viewModelScope.launch { trash.empty(); message = "Trash emptied" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit, viewModel: TrashViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picked = entries.filter { it.id in selected }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(viewModel.message) { viewModel.message?.let { snack.showSnackbar(it); viewModel.message = null } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) "Trash" else "${selected.size} selected") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = { viewModel.restore(picked); selected = emptySet() }) {
                            Icon(Icons.Default.Restore, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Restore")
                        }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.DeleteForever, "Delete permanently") }
                    } else if (entries.isNotEmpty()) {
                        TextButton(onClick = { confirmEmpty = true }) { Text("Empty") }
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Delete, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Trash is empty", style = MaterialTheme.typography.titleMedium)
                    Text("Deleted items stay here for ${viewModel.retentionDays} days.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else LazyColumn(Modifier.padding(padding)) {
            items(entries, key = { it.id }) { e ->
                val daysLeft = (viewModel.retentionDays - (System.currentTimeMillis() - e.deletedAt) / 86_400_000L).coerceAtLeast(0)
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { selected = if (e.id in selected) selected - e.id else selected + e.id }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(e.id in selected, { selected = if (it) selected + e.id else selected - e.id })
                    Spacer(Modifier.width(8.dp))
                    Icon(if (e.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${e.originalPath.removePrefix("/storage/emulated/0").substringBeforeLast('/').ifEmpty { "/" }} · " +
                                (if (e.isDirectory) "folder" else formatSize(e.size)) + " · $daysLeft day(s) left",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { viewModel.restore(listOf(e)) }) {
                        Icon(Icons.Default.Restore, contentDescription = "Restore ${e.name}", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false },
        title = { Text("Empty trash?") },
        text = { Text("${entries.size} item(s) will be deleted permanently.") },
        confirmButton = { TextButton(onClick = { confirmEmpty = false; viewModel.empty() }) { Text("Empty") } },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } }
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${picked.size} item(s) permanently?") },
        text = { Text("This cannot be undone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deleteForever(picked); selected = emptySet() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
}
