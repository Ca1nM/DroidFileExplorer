package com.sza.androidfolderexplorer.presentation.browser

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.sza.androidfolderexplorer.domain.model.FileItem
import java.io.File

private const val DRAG_PREFIX = "afe-paths\n"

/** The text carried by a drag: only paths that start with / and exist are accepted on drop. */
internal fun dragPayloadFor(paths: Collection<String>) = DRAG_PREFIX + paths.joinToString("\n")

private fun parseDrop(event: DragAndDropEvent): List<String> {
    val clip = event.toAndroidDragEvent().clipData ?: return emptyList()
    if (clip.itemCount == 0) return emptyList()
    val text = clip.getItemAt(0).text?.toString() ?: return emptyList()
    if (!text.startsWith(DRAG_PREFIX)) return emptyList()
    return text.removePrefix(DRAG_PREFIX).lines().filter { it.startsWith("/") && File(it).exists() }
}

/** A drop target that copies dropped items into [dest()]. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.dropInto(dest: () -> String, onDrop: (List<String>, String) -> Unit): Modifier = dragAndDropTarget(
    shouldStartDragAndDrop = { it.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN) },
    target = object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val paths = parseDrop(event)
            if (paths.isEmpty()) return false
            onDrop(paths, dest())
            return true
        }
    }
)

// ---- Fold posture ----

data class FoldPosture(val tabletop: Boolean, val lowerHalfHeight: Dp)

private fun Context.activity(): Activity? {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Tabletop = a foldable half-open with a horizontal hinge. Then the lower half is for controls. */
@Composable
fun rememberFoldPosture(): FoldPosture {
    val activity = LocalContext.current.activity() ?: return FoldPosture(false, 0.dp)
    val flow = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
    val info by flow.collectAsState(initial = null)
    val density = LocalDensity.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
    val tabletop = fold != null && fold.state == FoldingFeature.State.HALF_OPENED && fold.orientation == FoldingFeature.Orientation.HORIZONTAL
    val lower = if (fold != null) screenH - with(density) { fold.bounds.bottom.toDp() } else 0.dp
    return FoldPosture(tabletop, lower.coerceAtLeast(0.dp))
}

/** Big buttons for the lower half of a half-folded phone, where the thumbs are. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TabletopControls(
    state: FileBrowserUiState, vm: FileBrowserViewModel, onTrash: () -> Unit, onTools: () -> Unit, modifier: Modifier = Modifier
) {
    Surface(modifier, tonalElevation = 2.dp) {
        FlowRow(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
        ) {
            @Composable fun Big(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
                FilledTonalButton(onClick, enabled = enabled, modifier = Modifier.defaultMinSize(minWidth = 120.dp, minHeight = 64.dp)) {
                    Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(label)
                }
            }
            Big(Icons.AutoMirrored.Filled.ArrowBack, "Up", state.canGoUp) { vm.navigateUp() }
            Big(Icons.Default.DoneAll, "Select all") { vm.toggleSelectAll() }
            Big(Icons.Default.Search, "Search") { vm.toggleSearch() }
            Big(Icons.Default.CreateNewFolder, "New folder") { vm.showDialog(BrowserDialog.NewItem(true)) }
            Big(if (state.gridView) Icons.Default.ViewList else Icons.Default.GridView, if (state.gridView) "List" else "Grid") { vm.setGrid(!state.gridView) }
            Big(Icons.Default.ContentPaste, "Paste", state.clipboard != null) { vm.pasteHere() }
            Big(Icons.Default.Delete, "Trash") { onTrash() }
            Big(Icons.Default.Build, "Tools") { onTools() }
        }
    }
}

// ---- Second pane ----

/**
 * A second, independent folder view for wide windows. Select items here and send them to the
 * main pane's folder with the buttons, or drag any item's icon from one pane to the other to copy it.
 */
@Composable
fun SecondPane(
    state: FileBrowserUiState,
    vm: FileBrowserViewModel,
    starred: Set<String>,
    tags: Map<String, List<String>>,
    modifier: Modifier = Modifier
) {
    var path by rememberSaveable { mutableStateOf("/storage/emulated/0/Download") }
    var items by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var reload by remember { mutableIntStateOf(0) }

    // Reload when the folder changes, and after any copy/move finishes in either pane.
    val opDone = state.operation == null
    LaunchedEffect(path, reload, opDone, state.currentPath) {
        loading = true; error = null
        vm.listFolder(path)
            .onSuccess { list ->
                items = list.filter { state.showHidden || !it.name.startsWith(".") }
                    .sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
                selected = selected.filter { s -> items.any { it.path == s } }.toSet()
            }
            .onFailure { error = it.message ?: "Can't open this folder"; items = emptyList() }
        loading = false
    }

    Column(modifier.dropInto({ path }) { paths, dest -> vm.transfer(false, paths, dest) }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { path = path.substringBeforeLast('/').ifEmpty { "/" }; selected = emptySet() }, enabled = path != "/" && path != "/storage/emulated/0") {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up in second pane")
            }
            Text(path.removePrefix("/storage/emulated/0").ifEmpty { "/" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { path = state.currentPath; selected = emptySet() }) { Icon(Icons.Default.ContentCopy, "Show the main pane's folder here") }
            IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Refresh second pane") }
        }
        // Main selection -> this pane
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.transfer(false, state.selectedPaths.toList(), path) }, enabled = state.hasSelection, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Copy here ▸", maxLines = 1) }
            OutlinedButton(onClick = { vm.transfer(true, state.selectedPaths.toList(), path) }, enabled = state.hasSelection, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Move here ▸", maxLines = 1) }
        }
        // This pane's selection -> main pane
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.transfer(false, selected.toList(), state.currentPath); selected = emptySet() }, enabled = selected.isNotEmpty(), modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp)) { Text("◂ Copy", maxLines = 1) }
            Button(onClick = { vm.transfer(true, selected.toList(), state.currentPath); selected = emptySet() }, enabled = selected.isNotEmpty(), modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp)) { Text("◂ Move", maxLines = 1) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(items, key = { it.path }) { item ->
                FileItemRow(
                    item = item,
                    isSelected = item.path in selected,
                    selectionMode = selected.isNotEmpty(),
                    starred = item.path in starred,
                    tags = tags[item.path].orEmpty(),
                    showThumbnails = state.showThumbnails,
                    onRowTap = {
                        if (item.isDirectory && selected.isEmpty()) { path = item.path }
                        else selected = if (item.path in selected) selected - item.path else selected + item.path
                    },
                    onSelectionToggle = { selected = if (item.path in selected) selected - item.path else selected + item.path },
                    dragPayload = { dragPayloadFor(if (item.path in selected) selected else setOf(item.path)) }
                )
            }
        }
    }
}
