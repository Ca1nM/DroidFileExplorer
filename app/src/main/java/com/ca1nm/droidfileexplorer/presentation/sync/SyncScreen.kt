package com.ca1nm.droidfileexplorer.presentation.sync

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
import com.ca1nm.droidfileexplorer.data.ops.RulesRunner
import com.ca1nm.droidfileexplorer.data.settings.AutoSortRule
import com.ca1nm.droidfileexplorer.data.settings.SyncRule
import com.ca1nm.droidfileexplorer.data.settings.UserData
import com.ca1nm.droidfileexplorer.data.source.FileOps
import com.ca1nm.droidfileexplorer.presentation.common.FolderPickerDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SyncViewModel @Inject constructor(
    val userData: UserData,
    val fileOps: FileOps,
    private val runner: RulesRunner
) : ViewModel() {
    var running by mutableStateOf<String?>(null); private set

    fun runSync(r: SyncRule) { running = r.id; viewModelScope.launch { withContext(Dispatchers.IO) { runner.runSync(r) }; running = null } }
    fun runSort(r: AutoSortRule) { running = r.id; viewModelScope.launch { withContext(Dispatchers.IO) { runner.runAutoSort(r) }; running = null } }
}

private const val ROOT = "/storage/emulated/0"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onBack: () -> Unit, viewModel: SyncViewModel = hiltViewModel()) {
    val user by viewModel.userData.state.collectAsStateWithLifecycle()
    var syncDialog by remember { mutableStateOf<SyncRule?>(null) }
    var sortDialog by remember { mutableStateOf<AutoSortRule?>(null) }
    fun short(p: String) = p.removePrefix(ROOT).ifEmpty { "/" }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Sync & auto-sort") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text("Folder sync", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            item { Text("Copies new and changed files from one folder to another, on a schedule while the phone is idle. Checked hourly.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            items(user.syncRules, key = { it.id }) { r ->
                ListItem(
                    headlineContent = { Text("${short(r.source)} → ${short(r.destination)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text("Every ${r.intervalHours} h" + (if (r.mirrorDeletes) " · mirrors deletions (to trash)" else "") +
                            (if (r.lastRun > 0) "\nLast: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.lastRun))} — ${r.lastResult}" else ""))
                    },
                    leadingContent = { Switch(r.enabled, { on -> viewModel.userData.update { s -> s.copy(syncRules = s.syncRules.map { if (it.id == r.id) it.copy(enabled = on) else it }) } }) },
                    trailingContent = {
                        Row {
                            if (viewModel.running == r.id) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else IconButton(onClick = { viewModel.runSync(r) }) { Icon(Icons.Default.PlayArrow, "Run now") }
                            IconButton(onClick = { viewModel.userData.update { s -> s.copy(syncRules = s.syncRules - r) } }) { Icon(Icons.Default.Delete, "Delete rule") }
                        }
                    }
                )
            }
            item { TextButton(onClick = { syncDialog = SyncRule(UUID.randomUUID().toString(), "$ROOT/DCIM/Camera", "$ROOT/Backup/Camera") }, Modifier.padding(horizontal = 8.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add sync rule") } }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            item { Text("Auto-sort", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            item { Text("Moves loose files in a folder into Images, Video, Audio, Documents, Archives and APKs subfolders, and can trash files older than a set age.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            items(user.autoSortRules, key = { it.id }) { r ->
                ListItem(
                    headlineContent = { Text(short(r.folder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(listOfNotNull(if (r.sortByType) "Sort by type" else null, if (r.deleteOlderThanDays > 0) "Trash after ${r.deleteOlderThanDays} days" else null).joinToString(" · ") +
                            if (r.lastResult.isNotEmpty()) "\nLast: ${r.lastResult}" else "")
                    },
                    leadingContent = { Switch(r.enabled, { on -> viewModel.userData.update { s -> s.copy(autoSortRules = s.autoSortRules.map { if (it.id == r.id) it.copy(enabled = on) else it }) } }) },
                    trailingContent = {
                        Row {
                            if (viewModel.running == r.id) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else IconButton(onClick = { viewModel.runSort(r) }) { Icon(Icons.Default.PlayArrow, "Run now") }
                            IconButton(onClick = { viewModel.userData.update { s -> s.copy(autoSortRules = s.autoSortRules - r) } }) { Icon(Icons.Default.Delete, "Delete rule") }
                        }
                    }
                )
            }
            item { TextButton(onClick = { sortDialog = AutoSortRule(UUID.randomUUID().toString(), "$ROOT/Download") }, Modifier.padding(horizontal = 8.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add auto-sort rule") } }
        }
    }

    syncDialog?.let { r -> SyncRuleDialog(r, viewModel.fileOps, { syncDialog = null }) { saved -> viewModel.userData.update { s -> s.copy(syncRules = s.syncRules + saved) }; syncDialog = null } }
    sortDialog?.let { r -> SortRuleDialog(r, viewModel.fileOps, { sortDialog = null }) { saved -> viewModel.userData.update { s -> s.copy(autoSortRules = s.autoSortRules + saved) }; sortDialog = null } }
}

@Composable
private fun PathRow(label: String, value: String, onPick: () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onPick, Modifier.fillMaxWidth()) { Text(value.removePrefix(ROOT).ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun SyncRuleDialog(initial: SyncRule, fileOps: FileOps, onDismiss: () -> Unit, onSave: (SyncRule) -> Unit) {
    var rule by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf<String?>(null) }
    val nested = rule.source == rule.destination || rule.destination.startsWith(rule.source + "/") || rule.source.startsWith(rule.destination + "/")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PathRow("From", rule.source) { picking = "src" }
                PathRow("To", rule.destination) { picking = "dst" }
                if (nested) Text("The two folders can't be the same or inside each other.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                var hours by remember { mutableStateOf(rule.intervalHours.toString()) }
                OutlinedTextField(hours, { hours = it.filter(Char::isDigit); rule = rule.copy(intervalHours = hours.toIntOrNull()?.coerceAtLeast(1) ?: 24) }, label = { Text("Every … hours") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rule.mirrorDeletes, { rule = rule.copy(mirrorDeletes = it) })
                    Text("Also remove files from the destination that no longer exist in the source (they go to the trash)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = !nested, onClick = { onSave(rule) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    picking?.let { which ->
        FolderPickerDialog(if (which == "src") rule.source else rule.destination, fileOps, onDismiss = { picking = null }, onPick = { p ->
            rule = if (which == "src") rule.copy(source = p) else rule.copy(destination = p); picking = null
        })
    }
}

@Composable
private fun SortRuleDialog(initial: AutoSortRule, fileOps: FileOps, onDismiss: () -> Unit, onSave: (AutoSortRule) -> Unit) {
    var rule by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf(if (rule.deleteOlderThanDays > 0) rule.deleteOlderThanDays.toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-sort rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PathRow("Folder", rule.folder) { picking = true }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(rule.sortByType, { rule = rule.copy(sortByType = it) }); Text("Move files into subfolders by type") }
                OutlinedTextField(days, { days = it.filter(Char::isDigit); rule = rule.copy(deleteOlderThanDays = days.toIntOrNull() ?: 0) }, label = { Text("Trash files older than … days (empty = never)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = rule.sortByType || rule.deleteOlderThanDays > 0, onClick = { onSave(rule) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (picking) FolderPickerDialog(rule.folder, fileOps, onDismiss = { picking = false }, onPick = { rule = rule.copy(folder = it); picking = false })
}
