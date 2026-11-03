package com.ca1nm.droidfileexplorer.presentation.tools

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ca1nm.droidfileexplorer.data.FileTypes
import com.ca1nm.droidfileexplorer.data.formatSize
import com.ca1nm.droidfileexplorer.data.ops.OpenRequests
import com.ca1nm.droidfileexplorer.data.storage.AppUsage
import com.ca1nm.droidfileexplorer.domain.model.FileItem
import com.ca1nm.droidfileexplorer.presentation.browser.FileThumb
import java.text.DateFormat
import java.util.Date

/** What the overview is showing inside: all files of one type, or the list of apps. */
sealed interface OverviewDetail {
    val title: String
    data class Files(val category: String) : OverviewDetail {
        override val title get() = if (category == "Other") "Other file types" else category
    }
    data class Apps(val sort: AppSort) : OverviewDetail { override val title get() = "Apps" }
}

enum class AppSort(val label: String) { TOTAL("Total"), CODE("App"), DATA("Data"), CACHE("Cache") }

private const val STORAGE_ROOT = "/storage/emulated/0"

/** A list of the files in one category, with trash and delete. */
@Composable
internal fun FilesDetailPage(category: String, vm: ToolsViewModel, onReveal: () -> Unit) {
    val all = vm.detailFiles
    val ext = vm.overview?.otherExts.orEmpty().entries.sortedByDescending { it.value }.take(6).map { it.key }
    var extFilter by remember(category) { mutableStateOf<String?>(null) }
    var selected by remember(all) { mutableStateOf(setOf<String>()) }
    var confirmForever by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }

    if (all == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (vm.busy) CircularProgressIndicator() else Text("Nothing to show.")
        }
        return
    }
    val shown = remember(all, extFilter) { if (extFilter == null) all else all.filter { FileTypes.ext(it.name) == extFilter } }
    val picked = shown.filter { it.path in selected }
    val pickedBytes = picked.sumOf { it.size }

    Column(Modifier.fillMaxSize()) {
        Text("${shown.size} files · ${formatSize(shown.sumOf { it.size })}", Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp),
            style = MaterialTheme.typography.titleSmall)
        if (category == "Other" && ext.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(extFilter == null, { extFilter = null }, label = { Text("All") })
                ext.forEach { e -> FilterChip(extFilter == e, { extFilter = if (extFilter == e) null else e }, label = { Text(if (e.isEmpty()) "no extension" else ".$e") }) }
            }
        }
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selected = if (picked.size == shown.size) emptySet() else shown.map { it.path }.toSet() }) {
                Text(if (picked.size == shown.size && shown.isNotEmpty()) "Clear selection" else "Select all")
            }
            Text("Biggest first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.path }) { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { selected = if (f.path in selected) selected - f.path else selected + f.path }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(f.path in selected, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                    com.ca1nm.droidfileexplorer.presentation.browser.FileTile(f)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            (com.ca1nm.droidfileexplorer.presentation.browser.typeLabelOf(f.name)?.plus(" · ") ?: "") +
                                f.path.removePrefix(STORAGE_ROOT).substringBeforeLast('/').ifEmpty { "/" } + " · " +
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.lastModified)),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(formatSize(f.size), style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { OpenRequests.request(f.path.substringBeforeLast('/')); onReveal() }) {
                        Icon(Icons.Default.FolderOpen, "Show ${f.name} in its folder")
                    }
                }
            }
        }
        if (picked.isNotEmpty()) Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${picked.size} selected · ${formatSize(pickedBytes)}", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { confirmTrash = true }, enabled = !vm.busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("To trash")
                    }
                    Button(
                        onClick = { confirmForever = true }, enabled = !vm.busy, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Icon(Icons.Default.DeleteForever, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Delete") }
                }
            }
        }
    }

    if (confirmTrash) AlertDialog(
        onDismissRequest = { confirmTrash = false },
        title = { Text("Move ${picked.size} file(s) to the trash?") },
        text = { Text("You can restore them from Tools > Trash. The space (${formatSize(pickedBytes)}) is freed when the trash is emptied.") },
        confirmButton = { TextButton(onClick = { confirmTrash = false; vm.trashFiles(picked); selected = emptySet() }) { Text("Move to trash") } },
        dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("Cancel") } }
    )
    if (confirmForever) AlertDialog(
        onDismissRequest = { confirmForever = false },
        icon = { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Delete ${picked.size} file(s) for good?") },
        text = { Text("${formatSize(pickedBytes)} will be erased immediately and cannot be recovered.") },
        confirmButton = {
            TextButton(onClick = { confirmForever = false; vm.deleteFilesForever(picked); selected = emptySet() }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmForever = false }) { Text("Cancel") } }
    )
}

/** Installed apps by size, with the actions that can reclaim their space. */
@Composable
internal fun AppsDetailPage(initialSort: AppSort, vm: ToolsViewModel, onReveal: () -> Unit) {
    val context = LocalContext.current
    val apps = vm.appUsage.orEmpty()
    var sort by remember(initialSort) { mutableStateOf(initialSort) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val sorted = remember(apps, sort) {
        when (sort) {
            AppSort.TOTAL -> apps.sortedByDescending { it.totalBytes }
            AppSort.CODE -> apps.sortedByDescending { it.codeBytes }
            AppSort.DATA -> apps.sortedByDescending { it.dataBytes }
            AppSort.CACHE -> apps.sortedByDescending { it.cacheBytes }
        }
    }
    fun value(a: AppUsage) = when (sort) { AppSort.TOTAL -> a.totalBytes; AppSort.CODE -> a.codeBytes; AppSort.DATA -> a.dataBytes; AppSort.CACHE -> a.cacheBytes }

    Column(Modifier.fillMaxSize()) {
        Text("Sorted by ${sort.label.lowercase()} · ${apps.size} apps", Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppSort.entries.forEach { s -> FilterChip(sort == s, { sort = s }, label = { Text(s.label) }) }
        }
        Text("Cache and data of an app are cleared from its Android settings page; the menu on each row opens it.",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f)) {
            items(sorted, key = { it.pkg }) { a ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                if (a.codeBytes > 0) append("App ${formatSize(a.codeBytes)}  ·  ")
                                append("Data ${formatSize(a.dataBytes)}")
                                if (a.cacheBytes > 0) append(" (cache ${formatSize(a.cacheBytes)})")
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(formatSize(value(a)), style = MaterialTheme.typography.labelLarge)
                    Box {
                        IconButton(onClick = { menuFor = a.pkg }) { Icon(Icons.Default.MoreVert, "Actions for ${a.label}") }
                        DropdownMenu(expanded = menuFor == a.pkg, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(
                                text = { Text("Clear cache or data…") },
                                leadingIcon = { Icon(Icons.Default.CleaningServices, null) },
                                onClick = {
                                    menuFor = null
                                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Browse its data folder") },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                onClick = { menuFor = null; OpenRequests.request("$STORAGE_ROOT/Android/data/${a.pkg}"); onReveal() }
                            )
                            DropdownMenuItem(
                                text = { Text("Uninstall") },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = {
                                    menuFor = null
                                    context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
