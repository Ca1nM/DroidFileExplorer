package com.sza.androidfolderexplorer.presentation.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.sza.androidfolderexplorer.data.FileKind
import com.sza.androidfolderexplorer.data.FileTypes
import com.sza.androidfolderexplorer.data.diag.Diagnostics
import com.sza.androidfolderexplorer.data.ops.OpenRequests
import com.sza.androidfolderexplorer.data.settings.FolderView
import com.sza.androidfolderexplorer.data.settings.UserData
import com.sza.androidfolderexplorer.data.source.FileOps
import com.sza.androidfolderexplorer.data.source.TrashManager
import com.sza.androidfolderexplorer.presentation.viewer.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sza.androidfolderexplorer.data.repository.RestrictedAccessException
import com.sza.androidfolderexplorer.data.settings.AppSettings
import com.sza.androidfolderexplorer.data.source.ArchiveExtractor
import com.sza.androidfolderexplorer.data.source.SafFileAccess
import com.sza.androidfolderexplorer.data.source.ShizukuFileAccess
import com.sza.androidfolderexplorer.domain.model.FileItem
import com.sza.androidfolderexplorer.domain.model.OperationProgress
import com.sza.androidfolderexplorer.domain.usecase.BrowseDirectoryUseCase
import com.sza.androidfolderexplorer.domain.usecase.CopyFilesUseCase
import com.sza.androidfolderexplorer.domain.usecase.DeleteFilesUseCase
import com.sza.androidfolderexplorer.domain.usecase.MoveFilesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import javax.inject.Inject

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val browseDirectory: BrowseDirectoryUseCase,
    private val copyFiles: CopyFilesUseCase,
    private val moveFiles: MoveFilesUseCase,
    private val deleteFiles: DeleteFilesUseCase,
    val safFileAccess: SafFileAccess,
    val shizukuFileAccess: ShizukuFileAccess,
    private val appSettings: AppSettings,
    val fileOps: FileOps,
    val trash: TrashManager,
    val userData: UserData,
    private val vault: com.sza.androidfolderexplorer.data.vault.VaultManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    private val prefs = context.getSharedPreferences("afex_prefs", Context.MODE_PRIVATE)

    private var operationJob: Job? = null

    // Stored to retry navigation after permission grant
    private var pendingPath: String? = null

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) {
            _uiState.value = _uiState.value.copy(accessPrompt = null)
            pendingPath?.let { navigateTo(it) }
        }
    }

    init {
        shizukuFileAccess.addPermissionListener(shizukuPermissionListener)
        // Ignore a saved path outside the storage root (older builds could save /storage etc.)
        val lastPath = prefs.getString("last_path", FileBrowserUiState.DEFAULT_PATH)
            ?.takeIf { it.startsWith("${FileBrowserUiState.STORAGE_ROOT}/") }
            ?: FileBrowserUiState.DEFAULT_PATH
        _uiState.value = _uiState.value.copy(
            quickFolders = loadQuickFolders(),
            showHidden = appSettings.state.value.showHidden,
            showThumbnails = appSettings.state.value.showThumbnails,
            tapOpens = appSettings.state.value.tapOpens,
            useTrash = appSettings.state.value.useTrash,
            haptics = appSettings.state.value.haptics,
            sounds = appSettings.state.value.sounds,
            dualPane = appSettings.state.value.dualPane,
            showOnboarding = !appSettings.state.value.onboardingDone
        )
        navigateTo(OpenRequests.path.value ?: lastPath)
        OpenRequests.consume()
        // Open folders requested from a widget, the share receiver or a shortcut.
        viewModelScope.launch {
            OpenRequests.path.collect { requested ->
                if (requested != null) {
                    navigateTo(requested)
                    OpenRequests.consume()
                }
            }
        }
        // Keep display settings that don't need a reload in sync.
        viewModelScope.launch {
            appSettings.state.collect { st ->
                _uiState.value = _uiState.value.copy(
                    showThumbnails = st.showThumbnails,
                    tapOpens = st.tapOpens,
                    useTrash = st.useTrash,
                    haptics = st.haptics,
                    sounds = st.sounds,
                    dualPane = st.dualPane,
                    showOnboarding = !st.onboardingDone
                )
            }
        }
        // Reload the current folder when the hidden-files setting changes in Settings.
        viewModelScope.launch {
            appSettings.state
                .map { it.showHidden }
                .distinctUntilChanged()
                .drop(1)
                .collect { show ->
                    _uiState.value = _uiState.value.copy(showHidden = show)
                    navigateTo(_uiState.value.currentPath)
                }
        }
    }

    // ---- Quick access ----

    fun setQuickAccessVisible(visible: Boolean) {
        _uiState.value = _uiState.value.copy(showQuickAccess = visible)
    }

    /** Writes the shared setting; the state collector above reloads the folder. */
    fun setShowHidden(show: Boolean) = appSettings.setShowHidden(show)
    fun setThumbnails(show: Boolean) = appSettings.setShowThumbnails(show)
    fun finishOnboarding() = appSettings.setOnboardingDone(true)

    /** Full folder listing before filter/sort, so changing them doesn't need a reload. */
    private var rawItems: List<FileItem> = emptyList()

    private fun visibleItems(items: List<FileItem>): List<FileItem> {
        rawItems = items
        return arrangedItems()
    }

    private fun arrangedItems(): List<FileItem> {
        val s = _uiState.value
        // The trash folder is managed from the Trash screen; listing it invites deleting it by hand.
        val shown = (if (s.showHidden) rawItems else rawItems.filter { !it.name.startsWith(".") })
            .filter { it.path != TrashManager.TRASH_DIR }
        val ud = userData.state.value
        var filtered = shown.filter { s.filter.accepts(it) }
        if (s.query.isNotBlank()) filtered = filtered.filter { it.name.contains(s.query.trim(), ignoreCase = true) }
        s.tagFilter?.let { t -> filtered = filtered.filter { t in (ud.tags[it.path] ?: emptyList()) } }
        if (s.favouritesOnly) filtered = filtered.filter { it.path in ud.favourites }
        s.quick?.let { q -> filtered = filtered.filter { q.accepts(it) } }
        val key: Comparator<FileItem> = when (s.sortBy) {
            SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.DATE -> compareBy { it.lastModified }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.TYPE -> compareBy<FileItem> { it.name.substringAfterLast('.', "").lowercase() }
                .thenComparing(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
        // Folders always stay on top; direction only applies within each group.
        val ordered = if (s.sortDescending) key.reversed() else key
        return filtered.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenComparing(ordered))
    }

    fun setSort(by: SortBy, descending: Boolean) {
        _uiState.value = _uiState.value.copy(sortBy = by, sortDescending = descending)
        _uiState.value = _uiState.value.copy(items = arrangedItems(), selectedPaths = emptySet())
        persistView()
    }

    fun setGrid(grid: Boolean) {
        _uiState.value = _uiState.value.copy(gridView = grid)
        persistView()
    }

    /** Remembers grid/list and sort order for the folder on screen. */
    private fun persistView() {
        val s = _uiState.value
        userData.setFolderView(s.currentPath, FolderView(s.gridView, s.sortBy.name, s.sortDescending))
    }

    private fun rearrange() {
        _uiState.value = _uiState.value.copy(items = arrangedItems(), selectedPaths = emptySet())
    }

    fun setQuery(q: String) { _uiState.value = _uiState.value.copy(query = q); rearrange() }
    fun toggleSearch() {
        val open = !_uiState.value.searchOpen
        _uiState.value = _uiState.value.copy(searchOpen = open, query = if (open) _uiState.value.query else "")
        if (!open) rearrange()
    }
    fun setTagFilter(tag: String?) { _uiState.value = _uiState.value.copy(tagFilter = tag); rearrange() }
    fun setQuick(q: QuickFilter?) { _uiState.value = _uiState.value.copy(quick = q); rearrange() }
    fun setFavouritesOnly(on: Boolean) { _uiState.value = _uiState.value.copy(favouritesOnly = on); rearrange() }
    /** Re-sorts after stars or tags change. */
    fun refreshArrangement() = rearrange()

    fun setFilter(filter: TypeFilter) {
        _uiState.value = _uiState.value.copy(filter = filter)
        _uiState.value = _uiState.value.copy(items = arrangedItems(), selectedPaths = emptySet())
    }

    // ---- Image viewer ----

    fun openViewer(path: String) {
        val images = _uiState.value.items.filter { !it.isDirectory && isImageFile(it.name) }.map { it.path }
        val index = images.indexOf(path)
        if (index < 0) return
        _uiState.value = _uiState.value.copy(viewerPaths = images, viewerIndex = index, showQuickAccess = false)
    }

    fun closeViewer() {
        _uiState.value = _uiState.value.copy(viewerPaths = null, selectedPaths = emptySet())
    }

    /** Adds a custom quick-access folder. Returns an error message, or null on success. */
    fun addQuickFolder(rawPath: String): String? {
        val path = rawPath.trim().trimEnd('/')
        val root = FileBrowserUiState.STORAGE_ROOT
        if (path != root && !path.startsWith("$root/")) return "Folder must be inside $root"
        if (_uiState.value.quickFolders.any { it.path == path }) return "Already in quick access"
        if (!File(path).isDirectory) return "Folder not found or not accessible"
        saveCustomQuickFolders(customQuickFolderPaths() + path)
        _uiState.value = _uiState.value.copy(quickFolders = loadQuickFolders())
        return null
    }

    /** Lists the subfolders of [path] for the in-app folder chooser. Files are left out. */
    suspend fun listSubfolders(path: String): Result<List<FileItem>> =
        browseDirectory(path, countChildren = false).map { items -> items.filter { it.isDirectory } }

    fun removeQuickFolder(path: String) {
        saveCustomQuickFolders(customQuickFolderPaths() - path)
        _uiState.value = _uiState.value.copy(quickFolders = loadQuickFolders())
    }

    private fun customQuickFolderPaths(): List<String> =
        prefs.getString(PREF_QUICK_CUSTOM, "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun saveCustomQuickFolders(paths: List<String>) {
        prefs.edit().putString(PREF_QUICK_CUSTOM, paths.distinct().joinToString("\n")).apply()
    }

    private fun loadQuickFolders(): List<QuickFolder> {
        val root = FileBrowserUiState.STORAGE_ROOT
        val defaults = listOf(
            QuickFolder("Internal storage", root, isCustom = false),
            QuickFolder("Device root (needs Shizuku)", "/", isCustom = false)
        ) + DEFAULT_QUICK_FOLDERS.map { name ->
            QuickFolder(name, "$root/$name", isCustom = false)
        }
        val custom = customQuickFolderPaths().map { QuickFolder(File(it).name, it, isCustom = true) }
        return defaults + custom
    }

    // ---- Text editor ----

    fun openEditor(path: String) {
        _uiState.value = _uiState.value.copy(editorPath = path, showQuickAccess = false)
    }

    fun closeEditor() {
        _uiState.value = _uiState.value.copy(editorPath = null, selectedPaths = emptySet())
    }

    // ---- Archive extraction ----

    /** Extracts the single selected archive into a new folder beside it. */
    fun extractSelected() {
        val archive = _uiState.value.selectedPaths.singleOrNull()?.let { File(it) } ?: return
        if (!ArchiveExtractor.isSupported(archive.name)) return
        if (operationJob?.isActive == true) return

        val parent = archive.parentFile ?: return
        val baseName = ArchiveExtractor.folderNameFor(archive.name)
        var destDir = File(parent, baseName)
        var n = 2
        while (destDir.exists()) destDir = File(parent, "$baseName ($n)").also { n++ }

        operationJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                operation = OperationState.InProgress(OperationProgress(0, 0, archive.name, 0L, 0L))
            )
            try {
                var extracted = 0
                val count = ArchiveExtractor.extract(archive, destDir) { entryName ->
                    extracted++
                    _uiState.value = _uiState.value.copy(
                        operation = OperationState.InProgress(
                            OperationProgress(0, extracted, entryName, 0L, 0L)
                        )
                    )
                }
                _uiState.value = _uiState.value.copy(
                    operation = OperationState.Completed("Extracted $count file(s) to ${destDir.name}"),
                    selectedPaths = emptySet()
                )
                navigateTo(_uiState.value.currentPath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AFEX_EXTRACT", "extract failed", e)
                _uiState.value = _uiState.value.copy(
                    operation = OperationState.Failed(e.message ?: "Extraction failed")
                )
            }
        }
    }

    // ---- Messages ----

    fun showSnackbar(text: String, undo: (suspend () -> Unit)? = null) {
        _uiState.value = _uiState.value.copy(snackbar = SnackbarMessage(text, undo))
    }

    fun dismissSnackbar() { _uiState.value = _uiState.value.copy(snackbar = null) }

    fun runUndo() {
        val undo = _uiState.value.snackbar?.undo ?: return
        dismissSnackbar()
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { undo() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { showSnackbar("Undo failed: ${e.message}") }
        }
    }

    fun showDialog(d: BrowserDialog?) { _uiState.value = _uiState.value.copy(dialog = d) }
    fun closeDialog() = showDialog(null)

    /** Runs [block] off the main thread, reports failures in the snackbar and reloads the folder. */
    fun launchOp(label: String, reload: Boolean = true, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                if (reload) navigateTo(_uiState.value.currentPath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.log("op", "$label failed: ${e.stackTraceToString()}")
                showSnackbar("$label failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ---- File actions ----

    fun rename(path: String, newName: String) = launchOp("Rename") {
        val name = newName.trim()
        require(name.isNotEmpty() && '/' !in name) { "Enter a valid name" }
        val dst = File(File(path).parentFile, name).absolutePath
        fileOps.rename(path, dst)
        userData.onPathChanged(path, dst)
        showSnackbar("Renamed to $name", {
            fileOps.rename(dst, path)
            userData.onPathChanged(dst, path)
            navigateTo(_uiState.value.currentPath)
        })
    }

    fun createItem(name: String, folder: Boolean) = launchOp(if (folder) "New folder" else "New file") {
        val clean = name.trim()
        require(clean.isNotEmpty() && '/' !in clean) { "Enter a valid name" }
        val path = File(_uiState.value.currentPath, clean).absolutePath
        if (folder) fileOps.mkdir(path) else fileOps.createFile(path)
        showSnackbar("Created $clean")
    }

    fun toggleFavouriteSelected() {
        val sel = _uiState.value.selectedPaths
        if (sel.isEmpty()) return
        userData.toggleFavourite(sel)
        _uiState.value = _uiState.value.copy(selectedPaths = emptySet())
        rearrange()
    }

    /** Encrypts the selected files into the vault and removes the originals. */
    fun moveToVault(paths: List<String>) = launchOp("Move to vault") {
        val files = paths.map { File(it) }.filter { it.isFile }
        require(files.isNotEmpty()) { "Select files (folders can't go in the vault)" }
        var n = 0
        for (f in files) {
            f.inputStream().use { vault.add(f.name, it) }
            // The encrypted copy exists; only now is the plain original removed.
            f.delete(); n++
        }
        showSnackbar("Moved $n file(s) to the vault")
    }

    // ---- Opening files ----

    /** Tap on a file: open it in the right built-in viewer, or hand it to another app. */
    fun openItem(item: FileItem) {
        userData.addRecent(item.path, false)
        when (FileTypes.kindOf(item.name)) {
            FileKind.IMAGE -> openViewer(item.path)
            FileKind.VIDEO, FileKind.AUDIO -> playMedia(item)
            FileKind.ARCHIVE ->
                if (item.name.endsWith(".rar", true)) showSnackbar("RAR archives can't be opened here")
                else showDialog(BrowserDialog.ArchiveBrowser(item.path))
            FileKind.APK -> showDialog(BrowserDialog.ApkInfo(item.path))
            FileKind.PDF -> showDialog(BrowserDialog.Viewer(item.path, FileKindHint.PDF))
            FileKind.MARKDOWN -> showDialog(BrowserDialog.Viewer(item.path, FileKindHint.MARKDOWN))
            FileKind.JSON -> showDialog(BrowserDialog.Viewer(item.path, FileKindHint.JSON))
            FileKind.XML -> showDialog(BrowserDialog.Viewer(item.path, FileKindHint.XML))
            FileKind.CSV -> showDialog(BrowserDialog.Viewer(item.path, FileKindHint.CSV))
            FileKind.LOG -> showDialog(BrowserDialog.Viewer(item.path, FileKindHint.LOG))
            FileKind.TEXT -> openEditor(item.path)
            FileKind.SPLIT_PART -> showDialog(BrowserDialog.Split(item.path))
            FileKind.OTHER -> showDialog(BrowserDialog.OpenAs(item.path))
        }
    }

    private fun playMedia(item: FileItem) {
        val playlist = _uiState.value.items.filter { !it.isDirectory && FileTypes.isMedia(it.name) }.map { it.path }
        val index = playlist.indexOf(item.path).coerceAtLeast(0)
        context.startActivity(PlayerActivity.intent(context, playlist, index).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Asks the launcher to pin a shortcut that opens [path] in this app. */
    fun pinShortcut(path: String) {
        if (!androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            showSnackbar("This launcher can't pin shortcuts"); return
        }
        val name = File(path).name.ifEmpty { "Storage" }
        val intent = Intent(context, com.sza.androidfolderexplorer.MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(com.sza.androidfolderexplorer.MainActivity.EXTRA_OPEN_PATH, path)
        val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(context, "folder:$path")
            .setShortLabel(name).setLongLabel(path.removePrefix(FileBrowserUiState.STORAGE_ROOT).ifEmpty { "/" })
            .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(context, com.sza.androidfolderexplorer.R.mipmap.ic_launcher))
            .setIntent(intent).build()
        androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /** Plays a single file in the built-in player. */
    fun playFile(path: String) {
        context.startActivity(PlayerActivity.intent(context, listOf(path), 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens an image that may not be in the folder listing. */
    fun viewImage(path: String) {
        _uiState.value = _uiState.value.copy(viewerPaths = listOf(path), viewerIndex = 0, showQuickAccess = false, dialog = null)
    }

    private fun uriFor(path: String) = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))

    fun openExternally(path: String, mime: String? = null) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uriFor(path), mime ?: FileTypes.mimeOf(File(path).name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            showSnackbar("No app can open this file")
        } catch (e: Exception) {
            showSnackbar("Can't open: ${e.message}")
        }
    }

    fun sharePath(path: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uriFor(path))
                .setType(FileTypes.mimeOf(File(path).name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { showSnackbar("Can't share: ${e.message}") }
    }

    fun shareSelected() {
        val files = _uiState.value.selectedPaths.filter { File(it).isFile }
        if (files.isEmpty()) { showSnackbar("Select files to share (folders can't be shared)"); return }
        try {
            val uris = ArrayList(files.map { uriFor(it) })
            val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                         else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            intent.type = if (files.size == 1) FileTypes.mimeOf(File(files[0]).name) else "*/*"
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            showSnackbar("Can't share: ${e.message}")
        }
    }

    // ---- Recursive search ----

    private var searchJob: Job? = null

    fun runSearch(query: String, regex: Boolean, inContent: Boolean) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) return
        val matcher: (String) -> Boolean = try {
            if (regex) Regex(q, RegexOption.IGNORE_CASE).let { r -> { s: String -> r.containsMatchIn(s) } }
            else { s: String -> s.contains(q, ignoreCase = true) }
        } catch (e: Exception) {
            showSnackbar("Invalid pattern: ${e.message}"); return
        }
        val root = _uiState.value.currentPath
        val hidden = _uiState.value.showHidden
        _uiState.value = _uiState.value.copy(searchRunning = true, searchResults = emptyList())
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val found = mutableListOf<FileItem>()
            fun hit(item: FileItem) {
                if (found.size >= MAX_SEARCH_RESULTS) return
                found += item
                if (found.size % 25 == 0) _uiState.value = _uiState.value.copy(searchResults = found.toList())
            }
            try {
                fileOps.walk(root, includeHidden = hidden, onDir = { if (matcher(it.name)) hit(it) }) { f ->
                    var matched = matcher(f.name)
                    if (!matched && inContent && f.size in 1..1_000_000 &&
                        FileTypes.kindOf(f.name) in TEXT_KINDS) {
                        matched = runCatching { matcher(File(f.path).readText()) }.getOrDefault(false)
                    }
                    if (matched) hit(f)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showSnackbar("Search stopped: ${e.message}")
            }
            _uiState.value = _uiState.value.copy(searchResults = found.toList(), searchRunning = false)
        }
    }

    fun cancelSearch() { searchJob?.cancel(); _uiState.value = _uiState.value.copy(searchRunning = false) }
    fun clearSearchResults() { cancelSearch(); _uiState.value = _uiState.value.copy(searchResults = null) }

    /** Jumps to the folder containing [item], used from search and recent lists. */
    fun revealItem(item: FileItem) {
        clearSearchResults()
        closeDialog()
        if (item.isDirectory) navigateTo(item.path) else navigateTo(item.path.substringBeforeLast('/'))
    }

    override fun onCleared() {
        super.onCleared()
        shizukuFileAccess.removePermissionListener(shizukuPermissionListener)
    }

    fun navigateTo(path: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                selectedPaths = emptySet(),
                accessPrompt = null
            )
            val started = SystemClock.elapsedRealtime()
            val saved = userData.folderView(path)
            _uiState.value = _uiState.value.copy(
                sortBy = saved?.let { runCatching { SortBy.valueOf(it.sortBy) }.getOrNull() } ?: SortBy.NAME,
                sortDescending = saved?.descending ?: false,
                gridView = saved?.grid ?: false,
                query = "", tagFilter = null, favouritesOnly = false, quick = null
            )
            browseDirectory(path)
                .onSuccess { items ->
                    pendingPath = null
                    prefs.edit().putString("last_path", path).apply()
                    val took = SystemClock.elapsedRealtime() - started
                    if (took > appSettings.state.value.slowLoadMs) Diagnostics.logSlowLoad(path, took, items.size)
                    if (path != FileBrowserUiState.STORAGE_ROOT && !path.startsWith(TrashManager.TRASH_DIR)) {
                        userData.addRecent(path, true)
                    }
                    _uiState.value = _uiState.value.copy(
                        currentPath = path,
                        items = visibleItems(items),
                        isLoading = false,
                        pathSegments = buildPathSegments(path)
                    )
                }
                .onFailure { error ->
                    if (error is RestrictedAccessException) {
                        pendingPath = error.path
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            accessPrompt = resolveAccessPrompt(error.path)
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = error.message ?: "Unknown error"
                        )
                    }
                }
        }
    }

    fun navigateUp(): Boolean {
        val state = _uiState.value
        if (!state.canGoUp) return false
        val current = state.currentPath
        val parent = current.substringBeforeLast('/').ifEmpty { "/" }
        if (parent == current) return false
        navigateTo(parent)
        return true
    }

    fun toggleSelection(path: String) {
        val current = _uiState.value.selectedPaths
        _uiState.value = _uiState.value.copy(
            selectedPaths = if (path in current) current - path else current + path
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedPaths = emptySet())
    }

    fun toggleSelectAll() {
        val state = _uiState.value
        _uiState.value = if (state.allSelected) {
            state.copy(selectedPaths = emptySet())
        } else {
            state.copy(selectedPaths = state.items.map { it.path }.toSet())
        }
    }

    /** Stages the selection for Copy or Move. Nothing is written until the user pastes in a folder. */
    fun stageSelection(isMove: Boolean) {
        val selected = _uiState.value.selectedPaths.toList()
        if (selected.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            clipboard = ClipboardState(selected, isMove),
            selectedPaths = emptySet()
        )
    }

    /** Copy or move [sources] into [dest]; used by the second pane and drag-and-drop. */
    fun transfer(isMove: Boolean, sources: List<String>, dest: String) {
        val usable = sources.filter { src -> File(src).parent != dest && src != dest && !dest.startsWith("$src/") }
        if (usable.isEmpty()) { showSnackbar("Those items are already in that folder"); return }
        executeOperation(isMove, usable, dest) { if (isMove) offerMoveUndo(usable, dest) }
    }

    /** Lists any folder without touching the main pane's state. */
    suspend fun listFolder(path: String): Result<List<FileItem>> = browseDirectory(path, countChildren = false)

    /** After a move, "Undo" renames each item back to where it came from. */
    private fun offerMoveUndo(sources: List<String>, dest: String) {
        showSnackbar("Moved ${sources.size} item(s)", {
            sources.forEach { orig ->
                val now = File(dest, File(orig).name).path
                fileOps.rename(now, orig)
                userData.onPathChanged(now, orig)
            }
            navigateTo(_uiState.value.currentPath)
        })
    }

    fun cancelClipboard() {
        _uiState.value = _uiState.value.copy(clipboard = null)
    }

    /** Pastes the staged items into the folder currently on screen. */
    fun pasteHere() {
        val clip = _uiState.value.clipboard ?: return
        val dest = _uiState.value.currentPath
        // Skip items already in this folder, and a folder being pasted into itself.
        val sources = clip.paths.filter { src ->
            File(src).parent != dest && src != dest && !dest.startsWith("$src/")
        }
        if (sources.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                operation = OperationState.Failed("Those items are already in this folder")
            )
            return
        }
        executeOperation(clip.isMove, sources, dest) {
            if (clip.isMove) {
                _uiState.value = _uiState.value.copy(clipboard = null)
                offerMoveUndo(sources, dest)
            }
        }
    }

    fun requestDelete() {
        if (_uiState.value.selectedPaths.isEmpty()) return
        if (_uiState.value.selectedPaths.any { it == TrashManager.TRASH_DIR }) {
            showSnackbar("That's the trash folder. Use Tools > Trash to restore or empty it.")
            return
        }
        _uiState.value = _uiState.value.copy(pendingDelete = true)
    }

    fun cancelDelete() {
        _uiState.value = _uiState.value.copy(pendingDelete = false)
    }

    fun confirmDelete() {
        val selected = _uiState.value.selectedPaths.toList()
        _uiState.value = _uiState.value.copy(pendingDelete = false)
        viewModelScope.launch {
            if (_uiState.value.useTrash && selected.none { it.startsWith(TrashManager.TRASH_DIR) }) {
                val (added, failed) = trash.trash(selected)
                _uiState.value = _uiState.value.copy(selectedPaths = emptySet())
                val text = buildString {
                    append("Moved ${added.size} item(s) to trash")
                    if (failed.isNotEmpty()) append("; ${failed.size} couldn't be trashed")
                }
                showSnackbar(text, if (added.isEmpty()) null else ({
                    trash.restore(added)
                    navigateTo(_uiState.value.currentPath)
                }))
                navigateTo(_uiState.value.currentPath)
                return@launch
            }
            deleteFiles(selected)
                .onSuccess { count ->
                    _uiState.value = _uiState.value.copy(
                        operation = OperationState.Completed("Deleted $count item(s)"),
                        selectedPaths = emptySet()
                    )
                    navigateTo(_uiState.value.currentPath)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        operation = OperationState.Failed(e.message ?: "Delete failed")
                    )
                }
        }
    }

    fun cancelOperation() {
        operationJob?.cancel()
        operationJob = null
        _uiState.value = _uiState.value.copy(operation = null)
    }

    fun dismissOperation() {
        _uiState.value = _uiState.value.copy(operation = null)
        navigateTo(_uiState.value.currentPath)
    }

    fun dismissAccessPrompt() {
        _uiState.value = _uiState.value.copy(accessPrompt = null)
    }

    /** Re-checks access and retries the last pending path (called on Activity resume). */
    fun retryPendingPath() {
        pendingPath?.let { navigateTo(it) }
    }

    /** Called by Screen after user picks a folder in SAF document tree picker. */
    fun onSafPermissionGranted(uri: Uri) {
        safFileAccess.persistTreeUri(uri)
        _uiState.value = _uiState.value.copy(accessPrompt = null)
        pendingPath?.let { navigateTo(it) }
    }

    /** Called by Screen to trigger Shizuku permission request dialog. */
    fun requestShizukuPermission() = shizukuFileAccess.requestPermission()

    /** Returns the initial URI hint for the SAF document tree picker. */
    fun buildSafInitialUri(path: String) = safFileAccess.buildSafInitialUri(path)

    private fun resolveAccessPrompt(path: String): AccessPromptState =
        when {
            // Android 14+ — FUSE blocks even MANAGE_EXTERNAL_STORAGE; Shizuku is the reliable path
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> when {
                shizukuFileAccess.isServiceRunning() -> AccessPromptState.ShizukuPermissionRequired(path)
                shizukuFileAccess.isApkInstalled()   -> AccessPromptState.ShizukuNotRunning(path)
                else                                  -> AccessPromptState.ShizukuNotInstalled(path)
            }
            // Android 11-13 with MANAGE_EXTERNAL_STORAGE already granted but File API still denied:
            // device (e.g. Samsung Knox) blocks it despite the permission — fall back to Shizuku
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager() -> when {
                shizukuFileAccess.isServiceRunning() -> AccessPromptState.ShizukuPermissionRequired(path)
                shizukuFileAccess.isApkInstalled()   -> AccessPromptState.ShizukuNotRunning(path)
                else                                  -> AccessPromptState.ShizukuNotInstalled(path)
            }
            // Android 11-13 without MANAGE_EXTERNAL_STORAGE — ask the user to grant it
            else -> AccessPromptState.ManageStorageRequired(path)
        }

    private fun executeOperation(
        isMove: Boolean,
        selected: List<String>,
        destination: String,
        onDone: () -> Unit = {}
    ) {
        if (operationJob?.isActive == true) {
            Log.w("AFEX_MOVE", "executeOperation: operation already running, ignoring duplicate call")
            return
        }
        Log.d("AFEX_MOVE", "executeOperation isMove=$isMove dest=$destination selected=$selected")
        if (selected.isEmpty()) {
            Log.w("AFEX_MOVE", "executeOperation: selection is empty, aborting")
            return
        }

        operationJob = viewModelScope.launch {
            Log.d("AFEX_MOVE", "coroutine started, collecting flow...")
            val flow = if (isMove) moveFiles(selected, destination)
                       else copyFiles(selected, destination)

            flow.catch { e ->
                Log.e("AFEX_MOVE", "flow.catch error", e)
                _uiState.value = _uiState.value.copy(
                    operation = OperationState.Failed(e.message ?: "Operation failed")
                )
            }
            .onCompletion { error ->
                Log.d("AFEX_MOVE", "onCompletion error=$error")
                if (error == null) {
                    val action = if (isMove) "Moved" else "Copied"
                    _uiState.value = _uiState.value.copy(
                        operation = OperationState.Completed("$action ${selected.size} item(s) to ${File(destination).name}"),
                        selectedPaths = emptySet()
                    )
                    onDone()
                }
            }
            .collect { progress ->
                Log.d("AFEX_MOVE", "progress: ${progress.processedFiles}/${progress.totalFiles} ${progress.currentFileName}")
                _uiState.value = _uiState.value.copy(operation = OperationState.InProgress(progress))
            }

            // After Move: refresh directory so moved items disappear from the list
            if (isMove && _uiState.value.operation is OperationState.Completed) {
                delay(1200)
                val currentPath = _uiState.value.currentPath
                _uiState.value = _uiState.value.copy(isLoading = true, operation = null)
                browseDirectory(currentPath)
                    .onSuccess { items ->
                        _uiState.value = _uiState.value.copy(items = visibleItems(items), isLoading = false)
                    }
                    .onFailure {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                    }
            }
        }
    }

    /**
     * Breadcrumbs start at the storage root. Paths above it (/storage, /storage/emulated)
     * cannot be listed through SAF, so they are never offered as crumbs.
     */
    private fun buildPathSegments(path: String): List<PathSegment> {
        val root = FileBrowserUiState.STORAGE_ROOT
        val segments = mutableListOf<PathSegment>()
        if (path != root && !path.startsWith("$root/")) {
            // Outside the storage root (e.g. last-used path from an older build): show it plainly.
            var accumulated = ""
            for (part in path.removePrefix("/").split("/")) {
                accumulated = "$accumulated/$part"
                segments.add(PathSegment(part, accumulated))
            }
            return segments
        }
        segments.add(PathSegment(ROOT_LABEL, root))
        val relative = path.removePrefix(root).trim('/')
        var accumulated = root
        if (relative.isNotEmpty()) {
            for (part in relative.split("/")) {
                accumulated = "$accumulated/$part"
                segments.add(PathSegment(part, accumulated))
            }
        }
        return segments
    }

    private companion object {
        const val ROOT_LABEL = "Internal storage"
        const val PREF_QUICK_CUSTOM = "quick_access_custom"
        const val MAX_SEARCH_RESULTS = 2000
        val TEXT_KINDS = setOf(FileKind.TEXT, FileKind.MARKDOWN, FileKind.JSON, FileKind.XML, FileKind.CSV, FileKind.LOG)
        val DEFAULT_QUICK_FOLDERS = listOf(
            "Download", "DCIM", "Pictures", "Music", "Movies", "Documents", "Android"
        )
    }
}

