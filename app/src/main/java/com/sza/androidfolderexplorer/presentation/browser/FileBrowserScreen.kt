package com.sza.androidfolderexplorer.presentation.browser

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Storage
import com.sza.androidfolderexplorer.data.settings.NetworkConnection
import android.content.ClipData
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import android.view.SoundEffectConstants
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.OutlinedTextField as SearchField
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.sza.androidfolderexplorer.data.FileTypes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RingVolume
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.filled.Visibility
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.text.DateFormat
import java.util.Date
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Unarchive
import com.sza.androidfolderexplorer.data.source.ArchiveExtractor
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import com.sza.androidfolderexplorer.data.formatSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sza.androidfolderexplorer.data.source.ShizukuFileAccess
import com.sza.androidfolderexplorer.domain.model.FileItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onOpenSettings: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenTools: () -> Unit = {},
    onOpenConnection: (String) -> Unit = {},
    viewModel: FileBrowserViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val user by viewModel.userData.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val allTags = remember(user.tags) { user.tags.values.flatten().distinct().sorted() }
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    // Selecting by long-press gives a small buzz and, if enabled in Settings, a click.
    val selectFeedback = {
        if (state.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (state.sounds) view.playSoundEffect(SoundEffectConstants.CLICK)
    }

    // Show each new message; "Undo" runs the message's undo action.
    LaunchedEffect(state.snackbar?.id) {
        val msg = state.snackbar ?: return@LaunchedEffect
        val result = snackbarHost.showSnackbar(
            msg.text,
            actionLabel = if (msg.undo != null) "Undo" else null,
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.runUndo() else viewModel.dismissSnackbar()
    }

    // SAF tree picker — launched when AccessPromptState.SafRequired is shown
    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.onSafPermissionGranted(uri)
    }

    // When user returns from Shizuku/Settings, auto-retry the pending restricted path
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val prompt = state.accessPrompt
        if (prompt is AccessPromptState.ShizukuNotRunning ||
            prompt is AccessPromptState.ShizukuPermissionRequired ||
            prompt is AccessPromptState.ManageStorageRequired) {
            viewModel.retryPendingPath()
        }
    }

    // Back clears the selection first, then goes up one folder. At the top level it opens the menu
    // instead of leaving the app, so an over-long back swipe can't quit by accident. With the menu
    // open, Back closes it (the sheet handles that itself).
    BackHandler(
        enabled = state.editorPath == null && !state.showQuickAccess && state.dialog == null &&
            state.viewerPaths == null && !state.showOnboarding && !state.pendingDelete
    ) {
        when {
            state.hasSelection -> viewModel.clearSelection()
            state.searchOpen -> viewModel.toggleSearch()
            state.canGoUp -> viewModel.navigateUp()
            else -> viewModel.setQuickAccessVisible(true)
        }
    }

    var contextFor by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    // Hardware keyboard (Samsung DeX, Bluetooth keyboards, desktop mode).
    val onKey: (KeyEvent) -> Boolean = handler@{ e ->
        if (e.type != KeyEventType.KeyDown) return@handler false
        if (state.dialog != null || state.editorPath != null || state.viewerPaths != null || state.showOnboarding || state.showQuickAccess) return@handler false
        if (state.searchOpen) {
            return@handler if (e.key == Key.Escape) { viewModel.toggleSearch(); true } else false
        }
        val ctrl = e.isCtrlPressed
        when {
            ctrl && e.key == Key.A -> { if (!state.allSelected) viewModel.toggleSelectAll(); true }
            ctrl && e.key == Key.C -> { viewModel.stageSelection(isMove = false); true }
            ctrl && e.key == Key.X -> { viewModel.stageSelection(isMove = true); true }
            ctrl && e.key == Key.V -> { viewModel.pasteHere(); true }
            ctrl && e.key == Key.F -> { viewModel.toggleSearch(); true }
            ctrl && e.key == Key.N -> { viewModel.showDialog(BrowserDialog.NewItem(folder = true)); true }
            (ctrl && e.key == Key.R) || e.key == Key.F5 -> { viewModel.navigateTo(state.currentPath); true }
            e.key == Key.Delete -> { viewModel.requestDelete(); true }
            e.key == Key.F2 -> { state.selectedPaths.singleOrNull()?.let { viewModel.showDialog(BrowserDialog.Rename(it)) }; true }
            e.key == Key.Backspace || (e.isAltPressed && e.key == Key.DirectionLeft) -> { viewModel.navigateUp(); true }
            e.key == Key.Enter -> {
                val sel = state.items.firstOrNull { it.path in state.selectedPaths && state.selectedPaths.size == 1 }
                if (sel != null) { if (sel.isDirectory) viewModel.navigateTo(sel.path) else viewModel.openItem(sel); true } else false
            }
            e.key == Key.Escape -> { viewModel.clearSelection(); true }
            else -> false
        }
    }

    Scaffold(
        modifier = Modifier.focusRequester(focusRequester).focusable().onPreviewKeyEvent(onKey),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    BreadcrumbBar(
                        segments = state.pathSegments,
                        onSegmentClick = { viewModel.navigateTo(it.fullPath) },
                        onSegmentLongClick = {
                            clipboard.setText(AnnotatedString(it.fullPath))
                            viewModel.showSnackbar("Copied ${it.fullPath}")
                        }
                    )
                },
                navigationIcon = {
                    if (state.canGoUp) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    SortFilterMenu(
                        sortBy = state.sortBy,
                        descending = state.sortDescending,
                        filter = state.filter,
                        onSort = { by, desc -> viewModel.setSort(by, desc) },
                        onFilter = { viewModel.setFilter(it) }
                    )
                    IconButton(onClick = { viewModel.toggleSelectAll() }) {
                        Icon(
                            if (state.allSelected) Icons.Default.RemoveDone
                            else Icons.Default.DoneAll,
                            contentDescription = "Select all"
                        )
                    }
                    BrowserOverflowMenu(state, viewModel, onTrash = onOpenTrash, onTools = onOpenTools)
                }
            )
        },
        bottomBar = {
            Column {
                AnimatedVisibility(visible = state.clipboard != null) {
                    state.clipboard?.let { clip ->
                        PasteBar(
                            clip = clip,
                            onPaste = { viewModel.pasteHere() },
                            onCancel = { viewModel.cancelClipboard() }
                        )
                    }
                }
                AnimatedVisibility(visible = state.hasSelection) {
                    val selectedItems = state.items.filter { it.path in state.selectedPaths }
                    BottomActionBar(
                        moreMenu = { m -> SelectionMoreMenu(selectedItems, viewModel, m) },
                        selectedCount = state.selectedPaths.size,
                        singleFile = state.items.firstOrNull { it.path in state.selectedPaths }
                            ?.takeIf { state.selectedPaths.size == 1 && !it.isDirectory },
                        onCopy = { viewModel.stageSelection(isMove = false) },
                        onMove = { viewModel.stageSelection(isMove = true) },
                        onDelete = { viewModel.requestDelete() },
                        onEdit = { state.selectedPaths.singleOrNull()?.let { viewModel.openEditor(it) } },
                        onExtract = { viewModel.extractSelected() },
                        onView = { state.selectedPaths.singleOrNull()?.let { viewModel.openViewer(it) } }
                    )
                }
            }
        }
    ) { padding ->
        val posture = rememberFoldPosture()
        val dual = state.dualPane && !posture.tabletop && LocalConfiguration.current.screenWidthDp >= 600
        Box(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.fillMaxSize().padding(bottom = if (posture.tabletop) posture.lowerHalfHeight else 0.dp)) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight().dropInto({ state.currentPath }) { paths, dest -> viewModel.transfer(false, paths, dest) }) {
            if (state.searchOpen) {
                SearchField(
                    value = state.query,
                    onValueChange = { viewModel.setQuery(it) },
                    placeholder = { Text("Filter this folder by name") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = { viewModel.toggleSearch() }) { Icon(Icons.Default.Close, "Close search") }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            FilterChipsRow(state, allTags, viewModel)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.error != null -> {
                    ErrorContent(
                        message = state.error!!,
                        onRetry = { viewModel.navigateTo(state.currentPath) },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                state.items.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "This folder is empty",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Nothing to show here yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    val onTap: (FileItem) -> Unit = { item ->
                        when {
                            state.hasSelection -> viewModel.toggleSelection(item.path)
                            item.isDirectory -> viewModel.navigateTo(item.path)
                            state.tapOpens -> viewModel.openItem(item)
                            else -> viewModel.toggleSelection(item.path)
                        }
                    }
                    // Scroll to the folder we just backed out of, hold its highlight for a moment, then let it fade.
                    val highlight = state.highlightPath
                    LaunchedEffect(highlight, state.items) {
                        if (highlight == null) return@LaunchedEffect
                        val idx = state.items.indexOfFirst { it.path == highlight }
                        if (idx >= 0) {
                            if (state.gridView) gridState.animateScrollToItem((idx - 4).coerceAtLeast(0))
                            else listState.animateScrollToItem((idx - 3).coerceAtLeast(0))
                            kotlinx.coroutines.delay(2500)
                        }
                        viewModel.clearHighlight()
                    }
                    var refreshing by remember { mutableStateOf(false) }
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = {
                            scope.launch {
                                refreshing = true
                                try {
                                    val reload = viewModel.refresh()
                                    // A reload can finish within one frame; if `refreshing` flipped true->false that fast,
                                    // the pull indicator would never be told to hide. Hold the spinner briefly.
                                    kotlinx.coroutines.delay(700)
                                    reload.join()
                                } finally { refreshing = false }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (state.gridView) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(104.dp),
                                state = gridState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 8.dp, end = 36.dp, top = 4.dp, bottom = 72.dp)
                            ) {
                                gridItems(state.items, key = { it.path }) { item ->
                                    val hl by androidx.compose.animation.animateColorAsState(
                                        if (item.path == state.highlightPath) MaterialTheme.colorScheme.secondaryContainer
                                        else androidx.compose.ui.graphics.Color.Transparent,
                                        animationSpec = androidx.compose.animation.core.tween(600), label = "highlight"
                                    )
                                    Box(Modifier.background(hl, RoundedCornerShape(12.dp))) {
                                        GridTile(
                                            item = item,
                                            isSelected = item.path in state.selectedPaths,
                                            starred = item.path in user.favourites,
                                            showThumbnails = state.showThumbnails,
                                            onTap = { onTap(item) },
                                            onLongPress = { selectFeedback(); viewModel.toggleSelection(item.path) }
                                        )
                                    }
                                }
                            }
                            FastScrollbar(
                                totalItems = state.items.size,
                                visibleCount = gridState.layoutInfo.visibleItemsInfo.size,
                                firstVisible = gridState.firstVisibleItemIndex,
                                labelFor = { bubbleLabel(state.items.getOrNull(it), state.sortBy) },
                                onScrollTo = { i -> scope.launch { gridState.scrollToItem(i) } },
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp)
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 72.dp) // keep last row clear of the menu button
                            ) {
                                items(state.items, key = { it.path }) { item ->
                                    val hl by androidx.compose.animation.animateColorAsState(
                                        if (item.path == state.highlightPath) MaterialTheme.colorScheme.secondaryContainer
                                        else androidx.compose.ui.graphics.Color.Transparent,
                                        animationSpec = androidx.compose.animation.core.tween(600), label = "highlight"
                                    )
                                    Box(Modifier.background(hl)) {
                                        FileItemRow(
                                            item = item,
                                            isSelected = item.path in state.selectedPaths,
                                            selectionMode = state.hasSelection,
                                            starred = item.path in user.favourites,
                                            tags = user.tags[item.path].orEmpty(),
                                            showThumbnails = state.showThumbnails,
                                            onRowTap = { onTap(item) },
                                            onSelectionToggle = { selectFeedback(); viewModel.toggleSelection(item.path) },
                                            dragPayload = {
                                                dragPayloadFor(if (item.path in state.selectedPaths) state.selectedPaths else setOf(item.path))
                                            },
                                            onSecondaryClick = {
                                                // Right-click (mouse or DeX): select just this item and show its menu.
                                                if (item.path !in state.selectedPaths) { viewModel.clearSelection(); viewModel.toggleSelection(item.path) }
                                                contextFor = item.path
                                            }
                                        )
                                        DropdownMenu(expanded = contextFor == item.path, onDismissRequest = { contextFor = null }) {
                                            val close = { contextFor = null }
                                            if (!item.isDirectory) DropdownMenuItem(text = { Text("Open") }, onClick = { close(); viewModel.openItem(item) })
                                            else DropdownMenuItem(text = { Text("Open folder") }, onClick = { close(); viewModel.navigateTo(item.path) })
                                            DropdownMenuItem(text = { Text("Rename") }, onClick = { close(); viewModel.showDialog(BrowserDialog.Rename(item.path)) })
                                            DropdownMenuItem(text = { Text("Copy") }, onClick = { close(); viewModel.stageSelection(isMove = false) })
                                            DropdownMenuItem(text = { Text("Move") }, onClick = { close(); viewModel.stageSelection(isMove = true) })
                                            DropdownMenuItem(text = { Text(if (item.path in user.favourites) "Remove star" else "Add star") }, onClick = { close(); viewModel.toggleFavouriteSelected() })
                                            DropdownMenuItem(text = { Text("Properties") }, onClick = { close(); viewModel.showDialog(BrowserDialog.Properties(item.path)) })
                                            HorizontalDivider()
                                            DropdownMenuItem(text = { Text("Delete") }, onClick = { close(); viewModel.requestDelete() })
                                        }
                                    }
                                }
                            }
                            FastScrollbar(
                                totalItems = state.items.size,
                                visibleCount = listState.layoutInfo.visibleItemsInfo.size,
                                firstVisible = listState.firstVisibleItemIndex,
                                labelFor = { bubbleLabel(state.items.getOrNull(it), state.sortBy) },
                                onScrollTo = { i -> scope.launch { listState.scrollToItem(i) } },
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
            QuickAccessButton(onClick = { viewModel.setQuickAccessVisible(true) })
        }
        }
        if (dual) {
            VerticalDivider()
            SecondPane(state, viewModel, user.favourites.toSet(), user.tags, Modifier.weight(1f).fillMaxHeight())
        }
        }
        if (posture.tabletop) {
            TabletopControls(state, viewModel, onOpenTrash, onOpenTools, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(posture.lowerHalfHeight))
        }
        }
    }

    if (state.showQuickAccess) {
        QuickAccessSheet(
            connections = user.connections,
            onOpenConnection = onOpenConnection,
            folders = state.quickFolders,
            currentPath = state.currentPath,
            showHidden = state.showHidden,
            onShowHiddenChange = { viewModel.setShowHidden(it) },
            onOpen = { path -> viewModel.navigateTo(path) },
            onRemove = { viewModel.removeQuickFolder(it) },
            onMove = { path, delta -> viewModel.moveQuickFolder(path, delta) },
            onEdit = { old, name, path -> viewModel.updateQuickFolder(old, name, path) },
            onReset = { viewModel.resetQuickFolders() },
            onAdd = { viewModel.addQuickFolder(it) },
            loadSubfolders = { viewModel.listSubfolders(it) },
            onOpenSettings = onOpenSettings,
            onDismiss = { viewModel.setQuickAccessVisible(false) }
        )
    }

    state.editorPath?.let { path ->
        TextEditorDialog(path = path, onClose = { viewModel.closeEditor() })
    }

    state.viewerPaths?.let { paths ->
        ImageViewerDialog(
            paths = paths,
            startIndex = state.viewerIndex,
            onClose = { viewModel.closeViewer() },
            onInfo = { viewModel.showDialog(BrowserDialog.Viewer(it, FileKindHint.EXIF)) },
            onShare = { viewModel.sharePath(it) }
        )
    }

    state.dialog?.let { BrowserDialogHost(it, state, viewModel) }

    if (state.showOnboarding) OnboardingDialog(onDone = { viewModel.finishOnboarding() })

    // Delete confirmation dialog
    if (state.pendingDelete) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete ${state.selectedPaths.size} item(s)?") },
            text = {
                Text(
                    if (state.useTrash && state.selectedPaths.none { it.startsWith(com.sza.androidfolderexplorer.data.source.TrashManager.TRASH_DIR) })
                        "Items move to the trash, where you can restore them. They are removed for good after the trash retention period."
                    else "This action is permanent and cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDelete() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) { Text("Cancel") }
            }
        )
    }

    // Operation dialog
    state.operation?.let { operationState ->
        OperationDialog(
            state = operationState,
            onCancel = { viewModel.cancelOperation() },
            onDismiss = { viewModel.dismissOperation() }
        )
    }

    // Access permission dialog (SAF / Shizuku / Manage Storage)
    state.accessPrompt?.let { prompt ->
        AccessPromptDialog(
            state = prompt,
            onDismiss = { viewModel.dismissAccessPrompt() },
            onGrantSaf = { safLauncher.launch(viewModel.buildSafInitialUri(prompt.path)) },
            onGrantManageStorage = {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                } else {
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                }
                context.startActivity(intent)
            },
            onGrantShizuku = { viewModel.requestShizukuPermission() },
            onOpenShizukuApp = {
                val intent = context.packageManager
                    .getLaunchIntentForPackage(ShizukuFileAccess.SHIZUKU_PACKAGE)
                if (intent != null) context.startActivity(intent)
            },
            onOpenPlayStore = {
                val market = Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=${ShizukuFileAccess.SHIZUKU_PACKAGE}"))
                val web = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=${ShizukuFileAccess.SHIZUKU_PACKAGE}"))
                runCatching { context.startActivity(market) }
                    .onFailure { context.startActivity(web) }
            },
            onOpenFDroid = {
                context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://f-droid.org/packages/${ShizukuFileAccess.SHIZUKU_PACKAGE}/")))
            },
            onOpenWebsite = {
                context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://shizuku.rikka.app/")))
            },
            onRetry = { viewModel.retryPendingPath() }
        )
    }
}

@Composable
private fun SortFilterMenu(
    sortBy: SortBy,
    descending: Boolean,
    filter: TypeFilter,
    onSort: (SortBy, Boolean) -> Unit,
    onFilter: (TypeFilter) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort and filter")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        Text("Sort by", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        SortBy.values().forEach { by ->
            DropdownMenuItem(
                text = { Text(by.label + if (by == sortBy) (if (descending) "  ↓" else "  ↑") else "") },
                // Tapping the active key flips direction; tapping another starts ascending.
                onClick = { onSort(by, if (by == sortBy) !descending else false); open = false }
            )
        }
        HorizontalDivider()
        Text("Show", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        TypeFilter.values().forEach { f ->
            DropdownMenuItem(
                text = { Text(f.label + if (f == filter) "  ✓" else "") },
                onClick = { onFilter(f); open = false }
            )
        }
    }
}

@Composable
private fun ScrollbarIndicator(state: LazyListState, modifier: Modifier = Modifier) {
    val totalItems = state.layoutInfo.totalItemsCount
    val visibleCount = state.layoutInfo.visibleItemsInfo.size
    if (totalItems == 0 || visibleCount == 0 || visibleCount >= totalItems) return
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val firstVisibleIndex = state.firstVisibleItemIndex
    Canvas(modifier = modifier) {
        val thumbHeight = (visibleCount.toFloat() / totalItems * size.height)
            .coerceAtLeast(48.dp.toPx())
        val availableTravel = size.height - thumbHeight
        val maxFirst = totalItems - visibleCount
        val fraction = if (maxFirst > 0) firstVisibleIndex.toFloat() / maxFirst else 0f
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, fraction * availableTravel),
            size = Size(size.width, thumbHeight),
            cornerRadius = CornerRadius(size.width / 2)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BreadcrumbBar(
    segments: List<PathSegment>,
    onSegmentClick: (PathSegment) -> Unit,
    onSegmentLongClick: (PathSegment) -> Unit
) {
    val scroll = rememberScrollState()
    // Keep the current (last) folder in view when the path gets long.
    LaunchedEffect(segments) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier.horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically
    ) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                Text(" / ", style = MaterialTheme.typography.bodySmall)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        onClick = { onSegmentClick(segment) },
                        onLongClick = { onSegmentLongClick(segment) }
                    )
                    .padding(horizontal = 6.dp, vertical = 10.dp)
            ) {
                Text(
                    text = segment.name,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileItemRow(
    item: FileItem,
    isSelected: Boolean,
    selectionMode: Boolean,
    starred: Boolean,
    tags: List<String>,
    showThumbnails: Boolean,
    onRowTap: () -> Unit,
    onSelectionToggle: () -> Unit,
    dragPayload: (() -> String)? = null,
    onSecondaryClick: () -> Unit = {}
) {
    val dateText = remember(item.lastModified) {
        if (item.lastModified > 0) {
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.lastModified))
        } else null
    }
    val supporting = if (item.isDirectory) {
        listOfNotNull(
            "Folder",
            item.childCount?.let { "$it items" },
            dateText
        ).joinToString(" · ")
    } else {
        listOfNotNull(formatFileSize(item.size), dateText).joinToString(" · ")
    } + if (tags.isNotEmpty()) "  " + tags.joinToString(" ") { "#$it" } else ""

    val visual = remember(item.name, item.isDirectory) { visualFor(item) }
    // Tiles follow the chosen accent colour for every file type; the icon shape shows the type.
    val tileColor = MaterialTheme.colorScheme.primaryContainer
    val tileContent = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) {
                stateDescription = buildString {
                    append(if (isSelected) "Selected" else "Not selected")
                    if (starred) append(", starred")
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) onSecondaryClick()
                    }
                }
            }
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .combinedClickable(
                onClickLabel = if (item.isDirectory) "Open folder" else "Open",
                onClick = onRowTap,
                onLongClickLabel = "Select",
                onLongClick = { onSelectionToggle() }
            )
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tileColor)
                .then(
                    if (dragPayload == null) Modifier else Modifier.dragAndDropSource {
                        // Long-press and drag the icon to copy this item (or the whole selection) to the other pane.
                        detectDragGesturesAfterLongPress(
                            onDragStart = { startTransfer(DragAndDropTransferData(ClipData.newPlainText("afe", dragPayload()))) },
                            onDrag = { _, _ -> }
                        )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            FileThumb(item, px = 96, modifier = Modifier.fillMaxSize(), enabled = showThumbnails) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = tileContent
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                supporting,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (starred) {
            Icon(Icons.Default.Star, contentDescription = "Starred", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
        }
        // Fixed-width slot: the checkbox appears in select mode without shifting the text.
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelectionToggle() }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridTile(
    item: FileItem,
    isSelected: Boolean,
    starred: Boolean,
    showThumbnails: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val visual = remember(item.name, item.isDirectory) { visualFor(item) }
    Column(
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            FileThumb(item, px = 256, modifier = Modifier.fillMaxSize(), enabled = showThumbnails) {
                Icon(visual.icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (starred) Icon(
                Icons.Default.Star, "Starred", Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.tertiary
            )
            if (isSelected) Icon(
                Icons.Default.CheckCircle, null, Modifier.align(Alignment.BottomEnd).padding(4.dp).size(22.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private enum class Tone { Folder, Media, Audio, Document, Other }

private data class FileVisual(val icon: ImageVector, val tone: Tone)

private val ImageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")
private val VideoExtensions = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v")
private val AudioExtensions = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "opus")
private val SheetExtensions = setOf("xls", "xlsx", "ods", "csv")
private val DocExtensions = setOf("doc", "docx", "odt", "rtf", "txt", "md", "ppt", "pptx")
private val ArchiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
private val CodeExtensions = setOf("kt", "java", "py", "js", "ts", "html", "css", "json", "xml", "sh", "gradle")

private fun visualFor(item: FileItem): FileVisual {
    if (item.isDirectory) return FileVisual(folderIconFor(item.name), Tone.Folder)
    val ext = item.name.substringAfterLast('.', "").lowercase()
    return when {
        ext in ImageExtensions -> FileVisual(Icons.Default.Image, Tone.Media)
        ext in VideoExtensions -> FileVisual(Icons.Default.Movie, Tone.Media)
        ext in AudioExtensions -> FileVisual(Icons.Default.MusicNote, Tone.Audio)
        ext == "pdf" -> FileVisual(Icons.Default.PictureAsPdf, Tone.Document)
        ext in SheetExtensions -> FileVisual(Icons.Default.TableChart, Tone.Document)
        ext in DocExtensions -> FileVisual(Icons.Default.Description, Tone.Document)
        ext in ArchiveExtensions -> FileVisual(Icons.Default.Archive, Tone.Other)
        ext == "apk" -> FileVisual(Icons.Default.Android, Tone.Other)
        ext in CodeExtensions -> FileVisual(Icons.Default.Code, Tone.Other)
        else -> FileVisual(Icons.AutoMirrored.Filled.InsertDriveFile, Tone.Other)
    }
}

/** Well-known Android storage folders get their own icon; anything else is a plain folder. */
private fun folderIconFor(name: String): ImageVector = when (name.lowercase()) {
    "download", "downloads" -> Icons.Default.Download
    "dcim" -> Icons.Default.CameraAlt
    "pictures", "photos" -> Icons.Default.PhotoLibrary
    "screenshots" -> Icons.Default.PhotoCamera
    "music" -> Icons.Default.LibraryMusic
    "movies", "videos" -> Icons.Default.Movie
    "documents" -> Icons.Default.Description
    "android" -> Icons.Default.Android
    "alarms" -> Icons.Default.Alarm
    "notifications" -> Icons.Default.Notifications
    "ringtones" -> Icons.Default.RingVolume
    "bluetooth" -> Icons.Default.Bluetooth
    "whatsapp" -> Icons.Default.Chat
    else -> Icons.Default.Folder
}

@Composable
private fun BottomActionBar(
    moreMenu: @Composable (Modifier) -> Unit,
    selectedCount: Int,
    singleFile: FileItem?,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onExtract: () -> Unit,
    onView: () -> Unit
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Copy", maxLines = 1)
                }
                Button(
                    onClick = onMove,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(
                        Icons.Default.MoveToInbox,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Move", maxLines = 1)
                }
                Button(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", maxLines = 1)
                }
                moreMenu(Modifier.weight(1f))
            }
            // Single-file actions: edit as text, and extract if it's an archive
            if (singleFile != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Edit", maxLines = 1)
                    }
                    if (isImageFile(singleFile.name)) {
                        FilledTonalButton(
                            onClick = onView,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("View", maxLines = 1)
                        }
                    }
                    if (ArchiveExtractor.isSupported(singleFile.name)) {
                        FilledTonalButton(
                            onClick = onExtract,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(Icons.Default.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Extract", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasteBar(
    clip: ClipboardState,
    onPaste: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${clip.paths.size} item(s) ready to ${if (clip.isMove) "move" else "copy"}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel paste")
            }
            Button(onClick = onPaste, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Paste here", maxLines = 1)
            }
        }
    }
}

@Composable
private fun BoxScope.QuickAccessButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(12.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp
    ) {
        IconButton(onClick = onClick) {
            Icon(
                Icons.Default.Menu,
                contentDescription = "Quick access",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** Bottom sheet with quick-access folders, an in-app folder chooser, and a path field for pinning more. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAccessSheet(
    connections: List<NetworkConnection>,
    onOpenConnection: (String) -> Unit,
    folders: List<QuickFolder>,
    currentPath: String,
    showHidden: Boolean,
    onShowHiddenChange: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onEdit: (String, String, String) -> String?,
    onReset: () -> Unit,
    onAdd: (String) -> String?,
    loadSubfolders: suspend (String) -> Result<List<FileItem>>,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    var addText by remember(currentPath) { mutableStateOf(currentPath) }
    var addError by remember(currentPath) { mutableStateOf<String?>(null) }
    var chooserPath by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<QuickFolder?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    // The sheet is only as tall as its content: a few pins make a small sheet, many pins a tall one.
    // Pinning, hidden files and settings sit behind "More options" so they don't add empty space.
    val density = LocalDensity.current
    // Estimated until the list has been measured, then exact.
    var listHeight by remember { mutableStateOf((100 + (connections.size + folders.size) * 60).dp) }
    var listPx by remember { mutableIntStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetScroll = rememberScrollState()
    // The sheet resizes once the list is measured; make sure it opens showing the title, not scrolled down.
    var userScrolled by remember { mutableStateOf(false) }
    var dragFrom by remember { mutableIntStateOf(0) }
    LaunchedEffect(sheetScroll) {
        sheetScroll.interactionSource.interactions.collect {
            if (it is androidx.compose.foundation.interaction.DragInteraction.Start) {
                userScrolled = true
                dragFrom = sheetScroll.value
            }
        }
    }
    LaunchedEffect(listPx) { if (!userScrolled) sheetScroll.scrollTo(0) }
    // Until the user touches the sheet it stays on the pins page, whatever the layout does while it opens.
    LaunchedEffect(sheetScroll) {
        androidx.compose.runtime.snapshotFlow { sheetScroll.value }.collect { v ->
            if (!userScrolled && v != 0) sheetScroll.scrollTo(0)
        }
    }
    // Two pages: the pins, and the extras below them. Letting go between the two settles on the nearer one.
    // Once inside the extras, scrolling is free.
    LaunchedEffect(sheetScroll) {
        androidx.compose.runtime.snapshotFlow { sheetScroll.isScrollInProgress }.collect { moving ->
            if (!moving && userScrolled && chooserPath == null && listPx > 0) {
                val page = minOf(listPx, sheetScroll.maxValue)
                val v = sheetScroll.value
                if (page > 0 && v in 1 until page) {
                    // Started on the pins page: past 30% of the way, go to the extras. Started on the extras: past 30% back, go to the pins.
                    val fromExtras = dragFrom >= page / 2
                    val target = if (fromExtras) { if (v < page * 0.7f) 0 else page } else { if (v > page * 0.3f) page else 0 }
                    sheetScroll.animateScrollTo(target)
                }
            }
        }
    }
    val scope = rememberCoroutineScope()

    // When the chooser opens, scroll the sheet so the "Pin a folder" section moves to the top.
    // Two frames are waited so the new layout has been measured before reading maxValue.
    LaunchedEffect(chooserPath != null) {
        if (chooserPath != null) {
            // The chooser grows the content, so open the sheet fully first; a half-height sheet would re-anchor.
            sheetState.expand()
            withFrameNanos { }
            withFrameNanos { }
            sheetScroll.animateScrollTo(sheetScroll.maxValue)
        }
    }

    // Slide the sheet away first, then run the action. Removing the sheet from composition
    // straight away cut its exit animation short and made the navigation jolt.
    val closeThen: (() -> Unit) -> Unit = { action ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    editTarget?.let { target ->
        var nameText by remember(target.path) { mutableStateOf(target.name) }
        var pathText by remember(target.path) { mutableStateOf(target.path) }
        var err by remember(target.path) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Edit quick access") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(nameText, { nameText = it; err = null }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pathText, { pathText = it; err = null }, label = { Text("Folder path") }, singleLine = true, isError = err != null, modifier = Modifier.fillMaxWidth())
                    err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    err = onEdit(target.path, nameText, pathText)
                    if (err == null) editTarget = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Cancel") } }
        )
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset quick access?") },
        text = { Text("This puts back the standard list and removes your own entries, names and order.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // The sheet is only as tall as the quick-access list; the extras sit below it, so pulling up scrolls them in.
                // While the folder chooser is open the sheet may use whatever height it needs.
                .then(if (chooserPath == null) Modifier.heightIn(max = listHeight + 24.dp) else Modifier)
                .verticalScroll(sheetScroll)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().onSizeChanged { size ->
                    listPx = size.height
                    listHeight = with(density) { size.height.toDp() }
                },
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Quick access", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (editing) TextButton(onClick = { confirmReset = true }) { Text("Reset") }
                TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done" else "Edit") }
            }
            // Cloud accounts and servers saved in Tools > Network & cloud.
            connections.forEach { conn ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { closeThen { onOpenConnection(conn.id) } }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (conn.protocol == "saf") Icons.Default.Cloud else Icons.Default.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(conn.name.ifBlank { conn.host }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (conn.protocol == "saf") "Cloud account" else "${conn.protocol.uppercase()} · ${conn.host}",
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            folders.forEachIndexed { index, folder ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { if (editing) editTarget = folder else closeThen { onOpen(folder.path) } }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        folderIconFor(folder.name),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge)
                        Text(
                            folder.path.removePrefix(FileBrowserUiState.STORAGE_ROOT).ifEmpty { "/" },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (editing) {
                        IconButton(onClick = { onMove(folder.path, -1) }, enabled = index > 0, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move ${folder.name} up")
                        }
                        IconButton(onClick = { onMove(folder.path, 1) }, enabled = index < folders.lastIndex, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move ${folder.name} down")
                        }
                        IconButton(onClick = { editTarget = folder }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit ${folder.name}")
                        }
                        IconButton(onClick = { onRemove(folder.path) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remove ${folder.name}")
                        }
                    }
                }
            }
            Text(
                "Pull up for more", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
                    .clickable { userScrolled = true; scope.launch { sheetScroll.animateScrollTo(listPx) } }.padding(8.dp)
            )
            }

            // At least one sheet-height tall, so the second page can scroll all the way up to start where the pins end.
            Column(Modifier.heightIn(min = listHeight), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Pin a folder", style = MaterialTheme.typography.titleMedium)
            val chosen = chooserPath
            if (chosen != null) {
                FolderChooser(
                    path = chosen,
                    loadSubfolders = loadSubfolders,
                    onOpen = { chooserPath = it },
                    onUp = { chooserPath = it },
                    onCancel = { chooserPath = null },
                    onUse = { path ->
                        addError = onAdd(path)
                        chooserPath = null
                    }
                )
            } else {
                OutlinedButton(
                    onClick = { chooserPath = chooserStartFor(currentPath) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Choose a folder")
                }
                Text(
                    "Or type a path inside internal storage, e.g. /storage/emulated/0/Projects",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = addText,
                    onValueChange = {
                        addText = it
                        addError = null
                    },
                    label = { Text("Folder path") },
                    singleLine = true,
                    isError = addError != null,
                    modifier = Modifier.fillMaxWidth()
                )
                addError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = {
                        val error = onAdd(addText)
                        addError = error
                        if (error == null) addText = ""
                    },
                    enabled = addText.isNotBlank(),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pin path")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onShowHiddenChange(!showHidden) }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Show hidden files",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(checked = showHidden, onCheckedChange = null)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { closeThen(onOpenSettings) }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Settings", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Theme and accent colour",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }
        }
    }
}

/** Starts the chooser at the current folder when it is inside internal storage, else at the root. */
private fun chooserStartFor(currentPath: String): String {
    val root = FileBrowserUiState.STORAGE_ROOT
    return if (currentPath == root || currentPath.startsWith("$root/")) currentPath else root
}

/** In-sheet folder chooser: lists subfolders of [path] and pins whichever folder the user confirms. */
@Composable
private fun FolderChooser(
    path: String,
    loadSubfolders: suspend (String) -> Result<List<FileItem>>,
    onOpen: (String) -> Unit,
    onUp: (String) -> Unit,
    onCancel: () -> Unit,
    onUse: (String) -> Unit
) {
    val root = FileBrowserUiState.STORAGE_ROOT
    // Keep the previous listing on screen while the next folder loads. Blanking it out shrank
    // the sheet, which reset its scroll position back to the top.
    var loaded by remember { mutableStateOf<Pair<String, Result<List<FileItem>>>?>(null) }
    LaunchedEffect(path) {
        loaded = path to loadSubfolders(path)
    }
    val shown = loaded
    val fresh = shown?.first == path
    val result: Result<List<FileItem>>? = shown?.second
    // The list has its own fixed-height scroll area, so changing folders never changes the
    // sheet's height. Each new folder starts at its top.
    val listScroll = rememberScrollState()
    LaunchedEffect(path) { listScroll.scrollTo(0) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (path != root) {
                IconButton(onClick = { onUp(path.substringBeforeLast('/')) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up one folder")
                }
            }
            Text(
                path.removePrefix(root).ifEmpty { "/" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }

        if (!fresh) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .verticalScroll(listScroll),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
        when (val r = result) {
            null -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp)
            )
            else -> r.fold(
                onSuccess = { items ->
                    val subfolders = items.sortedBy { it.name.lowercase() }
                    if (subfolders.isEmpty()) {
                        Text(
                            "No subfolders here",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    subfolders.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpen(folder.path) }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                folderIconFor(folder.name),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                        }
                    }
                },
                onFailure = { e ->
                    Text(
                        e.message ?: "Couldn't open this folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onUse(path) }, enabled = fresh && result?.isSuccess == true) {
                Text("Use this folder")
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
        OutlinedButton(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun OperationDialog(
    state: OperationState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (state !is OperationState.InProgress) onDismiss()
        },
        title = {
            Text(
                when (state) {
                    is OperationState.InProgress -> "Processing..."
                    is OperationState.Completed -> "Done"
                    is OperationState.Failed -> "Error"
                }
            )
        },
        text = {
            when (state) {
                is OperationState.InProgress -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            progress = { state.progress.fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            if (state.progress.totalFiles > 0) {
                                "${state.progress.processedFiles} / ${state.progress.totalFiles} files"
                            } else if (state.progress.totalBytes > 0) {
                                "${(state.progress.fraction * 100).toInt()}% · ${formatSize(state.progress.processedBytes)} of ${formatSize(state.progress.totalBytes)} · ${state.progress.processedFiles} files"
                            } else {
                                "${state.progress.processedFiles} files so far"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (state.progress.currentFileName.isNotEmpty()) {
                            Text(
                                state.progress.currentFileName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                is OperationState.Completed -> Text(state.message)
                is OperationState.Failed -> Text(state.error)
            }
        },
        confirmButton = {
            when (state) {
                is OperationState.InProgress -> {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                else -> {
                    TextButton(onClick = onDismiss) { Text("OK") }
                }
            }
        }
    )
}

@Composable
private fun AccessPromptDialog(
    state: AccessPromptState,
    onDismiss: () -> Unit,
    onGrantSaf: () -> Unit,
    onGrantManageStorage: () -> Unit,
    onGrantShizuku: () -> Unit,
    onOpenShizukuApp: () -> Unit,
    onOpenPlayStore: () -> Unit,
    onOpenFDroid: () -> Unit,
    onOpenWebsite: () -> Unit,
    onRetry: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                when (state) {
                    is AccessPromptState.ManageStorageRequired -> "Grant All Files Access"
                    is AccessPromptState.SafRequired -> "Grant Folder Access"
                    is AccessPromptState.ShizukuPermissionRequired -> "Allow Shizuku Access"
                    is AccessPromptState.ShizukuNotRunning -> "Start Shizuku Service"
                    is AccessPromptState.ShizukuNotInstalled -> "Install Shizuku"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is AccessPromptState.ManageStorageRequired -> {
                        Text(
                            "Android 11–13 requires \"All files access\" to browse Android/data and Android/obb.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Tap \"Open Settings\", enable All files access for this app, then return here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is AccessPromptState.SafRequired -> {
                        Text(
                            "This folder requires special access.\n\n" +
                            "Tap \"Grant Access\", then select the folder:\n" +
                            state.path.substringAfter("/storage/emulated/0/"),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Confirm \"Allow\" in the system picker.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is AccessPromptState.ShizukuPermissionRequired -> {
                        Text(
                            "Shizuku is running. Tap \"Allow\" to grant shell-level " +
                            "file access to Android/data and Android/obb.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "Shizuku service: running",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    is AccessPromptState.ShizukuNotRunning -> {
                        Text(
                            "Shizuku is installed but the service is not running.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Option 1 — Open Shizuku app and tap Start:",
                            style = MaterialTheme.typography.labelMedium
                        )
                        FilledTonalButton(
                            onClick = onOpenShizukuApp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Open Shizuku")
                        }
                        HorizontalDivider()
                        Text(
                            "Option 2 — Start via ADB (one-time):",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Text(
                            "After starting Shizuku, tap Retry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is AccessPromptState.ShizukuNotInstalled -> {
                        Text(
                            "Android 14+ blocks all standard access to Android/data and Android/obb.\n\n" +
                            "Shizuku provides privileged shell access. Install it, start the service " +
                            "once via ADB, then grant permission — no root required.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Install from:", style = MaterialTheme.typography.labelMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(
                                onClick = onOpenPlayStore,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Google Play Store")
                            }
                            OutlinedButton(
                                onClick = onOpenFDroid,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("F-Droid (open source)")
                            }
                            TextButton(
                                onClick = onOpenWebsite,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("shizuku.rikka.app — APK & instructions")
                            }
                        }
                        HorizontalDivider()
                        Text(
                            "After install, start the service once:",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Text(
                            "Wireless ADB: Developer Options → Wireless debugging\n" +
                            "(no PC needed after pairing once)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is AccessPromptState.ManageStorageRequired ->
                    Button(onClick = onGrantManageStorage) { Text("Open Settings") }
                is AccessPromptState.SafRequired ->
                    Button(onClick = onGrantSaf) { Text("Grant Access") }
                is AccessPromptState.ShizukuPermissionRequired ->
                    Button(onClick = onGrantShizuku) { Text("Allow") }
                is AccessPromptState.ShizukuNotRunning ->
                    Button(onClick = onRetry) { Text("Retry") }
                is AccessPromptState.ShizukuNotInstalled ->
                    TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        dismissButton = {
            when (state) {
                is AccessPromptState.ManageStorageRequired ->
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                is AccessPromptState.ShizukuNotInstalled ->
                    // Fallback: SAF may partially work on some ROMs even on Android 14
                    TextButton(onClick = onGrantSaf) { Text("Try SAF (limited)") }
                is AccessPromptState.ShizukuNotRunning ->
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                else ->
                    TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
