package com.sza.androidfolderexplorer.presentation.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sza.androidfolderexplorer.data.FileKind
import com.sza.androidfolderexplorer.data.FileTypes
import com.sza.androidfolderexplorer.data.diag.Diagnostics
import com.sza.androidfolderexplorer.data.formatSize
import com.sza.androidfolderexplorer.data.ops.OpenRequests
import com.sza.androidfolderexplorer.data.settings.AppSettings
import com.sza.androidfolderexplorer.data.settings.UserData
import com.sza.androidfolderexplorer.data.source.FileOps
import com.sza.androidfolderexplorer.data.source.TrashManager
import com.sza.androidfolderexplorer.data.source.ShizukuFileAccess
import com.sza.androidfolderexplorer.data.storage.AppUsage
import com.sza.androidfolderexplorer.data.storage.StorageBreakdown
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.sza.androidfolderexplorer.domain.model.FileItem
import com.sza.androidfolderexplorer.domain.usecase.DeleteFilesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

private const val ROOT = "/storage/emulated/0"

data class InstalledApp(val label: String, val pkg: String, val version: String, val apks: List<String>)
data class CleanupItem(val path: String, val label: String, val size: Long, val kind: String, val permanent: Boolean = false)
data class OverviewResult(
    val byCategory: Map<String, Long>, val largest: List<FileItem>,
    val files: Int, val totalBytes: Long, val freeBytes: Long, val usedBytes: Long,
    /** Bytes per file extension inside the "Other" category, so "Other" can be explained. */
    val otherExts: Map<String, Long> = emptyMap()
)

/** One folder's direct contents as last scanned. Reused while the folder's modified time is unchanged. */
@Serializable
data class DirScan(
    val mtime: Long, val subdirs: List<String>, val cats: Map<String, Long>, val files: Int, val top: List<TopFile>,
    /** Extension -> bytes for files in the "Other" category. Null in scans saved before this existed. */
    val exts: Map<String, Long>? = null
)

@Serializable
data class TopFile(val name: String, val size: Long, val modified: Long)

@Serializable
data class OverviewCache(val dirs: Map<String, DirScan> = emptyMap(), val scannedAt: Long = 0L)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val fileOps: FileOps,
    val trash: TrashManager,
    private val settings: AppSettings,
    private val userData: UserData,
    private val deleteFiles: DeleteFilesUseCase,
    private val shizuku: ShizukuFileAccess
) : ViewModel() {

    var busy by mutableStateOf(false); private set
    var progress by mutableStateOf(""); private set
    var message by mutableStateOf<String?>(null)
    var overview by mutableStateOf<OverviewResult?>(null); private set
    var overviewScannedAt by mutableLongStateOf(0L); private set
    var overviewReused by mutableIntStateOf(0); private set
    /** Per-app sizes from Android's storage statistics; empty until usage access is granted. */
    var appUsage by mutableStateOf<List<AppUsage>?>(null); private set
    var usageAccess by mutableStateOf(false); private set
    val shizukuReady: Boolean get() = shizuku.hasPermission()

    /** What the overview is showing inside (a file type or the app list); null shows the overview itself. */
    var detail by mutableStateOf<OverviewDetail?>(null); private set
    var detailFiles by mutableStateOf<List<FileItem>?>(null); private set
    /** Set when files were deleted, so the overview is refreshed when the detail page closes. */
    private var overviewStale = false

    fun openApps(sort: AppSort) { detail = OverviewDetail.Apps(sort) }

    fun closeDetail() {
        job?.cancel(); busy = false
        detail = null; detailFiles = null
        if (overviewStale) { overviewStale = false; scanOverview() }
    }

    /** Lists every file of one type, looking only in the folders the last scan found them in. */
    fun openCategory(name: String) {
        detail = OverviewDetail.Files(name); detailFiles = null
        run("Listing $name files") {
            val folders = loadOverviewCache().dirs.filter { (it.value.cats[name] ?: 0L) > 0L }.keys
            val found = ArrayList<FileItem>()
            for (dir in folders) {
                currentCoroutineContext().ensureActive()
                runCatching { fileOps.list(dir) }.getOrNull()?.forEach {
                    if (!it.isDirectory && FileTypes.categoryOf(it.name) == name) found += it
                }
                if (found.size >= 20_000) break
            }
            detailFiles = found.sortedByDescending { it.size }
        }
    }

    /** Moves files to the trash. The space comes back when the trash is emptied. */
    fun trashFiles(items: List<FileItem>) = run("Moving to trash") {
        val (added, failed) = trash.trash(items.map { it.path })
        val gone = added.map { it.originalPath }.toSet()
        detailFiles = detailFiles?.filter { it.path !in gone }
        overviewStale = overviewStale || gone.isNotEmpty()
        message = "Moved ${added.size} file(s) to the trash (${formatSize(items.filter { it.path in gone }.sumOf { it.size })}); " +
            "empty the trash to free the space" + if (failed.isNotEmpty()) ". ${failed.size} couldn't be moved." else "."
    }

    /** Deletes files immediately and for good. */
    fun deleteFilesForever(items: List<FileItem>) = run("Deleting") {
        deleteFiles(items.map { it.path })
        val gone = items.filter { !java.io.File(it.path).exists() }
        val goneSet = gone.map { it.path }.toSet()
        detailFiles = detailFiles?.filter { it.path !in goneSet }
        overviewStale = overviewStale || gone.isNotEmpty()
        message = "Deleted ${gone.size} file(s), freed ${formatSize(gone.sumOf { it.size })}" +
            if (gone.size < items.size) ". ${items.size - gone.size} couldn't be deleted." else "."
    }
    private val overviewFile get() = File(context.filesDir, "overview_cache.json")
    private val cacheJson = Json { ignoreUnknownKeys = true }
    var duplicates by mutableStateOf<List<List<FileItem>>?>(null); private set
    var cleanup by mutableStateOf<List<CleanupItem>?>(null); private set
    var apps by mutableStateOf<List<InstalledApp>?>(null); private set
    private var job: Job? = null

    fun cancel() { job?.cancel(); busy = false }

    private fun run(label: String, block: suspend () -> Unit) {
        job?.cancel()
        busy = true; progress = label
        job = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "$label failed: ${e.message ?: e.javaClass.simpleName}" }
            busy = false
        }
    }

    private fun loadOverviewCache(): OverviewCache =
        runCatching { cacheJson.decodeFromString<OverviewCache>(overviewFile.readText()) }.getOrDefault(OverviewCache())

    /** Builds the result from the walk of [dirs]; [scan] is called for a folder that needs (re)reading. */
    private suspend fun buildOverview(dirs: Map<String, DirScan>, scan: (suspend (String) -> DirScan?)?, out: MutableMap<String, DirScan>): OverviewResult {
        val cats = HashMap<String, Long>()
        val candidates = ArrayList<FileItem>()
        val otherExts = HashMap<String, Long>()
        var count = 0; var reused = 0
        suspend fun visit(path: String) {
            currentCoroutineContext().ensureActive()
            val cached = dirs[path]
            val live = if (scan != null) {
                val mtime = File(path).lastModified()
                if (cached != null && cached.exts != null && mtime > 0 && cached.mtime == mtime) { reused++; cached } else scan(path)
            } else cached
            val entry = live ?: return
            out[path] = entry
            entry.cats.forEach { (k, v) -> cats.merge(k, v, Long::plus) }
            entry.exts?.forEach { (k, v) -> otherExts.merge(k, v, Long::plus) }
            count += entry.files
            entry.top.forEach { candidates += FileItem(it.name, "$path/${it.name}", false, it.size, it.modified) }
            if (candidates.size > 400) { candidates.sortByDescending { it.size }; while (candidates.size > 40) candidates.removeAt(candidates.lastIndex) }
            if (scan != null && count % 500 < entry.files) progress = "Scanning\u2026 $count files"
            entry.subdirs.forEach { visit("$path/$it") }
        }
        visit(ROOT)
        overviewReused = reused
        candidates.sortByDescending { it.size }
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        return OverviewResult(cats, candidates.take(40), count, cats.values.sum(), stat.availableBytes, stat.totalBytes - stat.availableBytes, otherExts)
    }

    /** Shows the last saved scan without touching the file system. Returns false if there is none yet. */
    fun showCachedOverview(): Boolean {
        val cache = loadOverviewCache()
        if (cache.dirs.isEmpty()) return false
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { buildOverview(cache.dirs, null, HashMap()) }
            overview = result; overviewScannedAt = cache.scannedAt; overviewReused = 0
            withContext(Dispatchers.IO) { loadAppUsage() }
        }
        return true
    }

    /**
     * Rescans storage. Unless [full] is set, a folder whose modified time hasn't changed since the
     * last scan is not read again; only changed folders are listed. A folder's modified time changes
     * when files are added, removed or renamed in it, not when an existing file grows in place.
     */
    fun scanOverview(full: Boolean = false) = run(if (full) "Scanning storage" else "Updating overview") {
        val previous = if (full) emptyMap() else loadOverviewCache().dirs
        val fresh = HashMap<String, DirScan>()
        val result = buildOverview(previous, { path ->
            runCatching {
                val items = fileOps.list(path)
                val files = items.filter { !it.isDirectory }
                val cats = HashMap<String, Long>()
                val exts = HashMap<String, Long>()
                files.forEach {
                    val cat = FileTypes.categoryOf(it.name)
                    cats.merge(cat, it.size, Long::plus)
                    if (cat == "Other") exts.merge(FileTypes.ext(it.name), it.size, Long::plus)
                }
                DirScan(
                    mtime = File(path).lastModified(),
                    subdirs = items.filter { it.isDirectory && !fileOps.isRestricted(it.path) && it.path != TrashManager.TRASH_DIR }.map { it.name },
                    cats = cats, files = files.size,
                    top = files.sortedByDescending { it.size }.take(40).map { TopFile(it.name, it.size, it.lastModified) },
                    exts = exts
                )
            }.getOrNull()
        }, fresh)
        val now = System.currentTimeMillis()
        runCatching { overviewFile.writeText(cacheJson.encodeToString(OverviewCache(fresh, now))) }
        overview = result; overviewScannedAt = now
        loadAppUsage()
    }

    /** Reads per-app sizes if usage access is granted. */
    private fun loadAppUsage() {
        usageAccess = StorageBreakdown.hasUsageAccess(context)
        appUsage = if (usageAccess) StorageBreakdown.queryApps(context) else null
    }

    /** Re-checks usage access, for example after coming back from Android's settings. */
    fun refreshUsageAccess() {
        val before = usageAccess
        viewModelScope.launch(Dispatchers.IO) {
            loadAppUsage()
            if (!before && usageAccess) progress = ""
        }
    }

    fun grantUsageAccess() {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = StorageBreakdown.grantWithShizuku(context, shizuku)
            if (ok) loadAppUsage() else message = "Couldn't grant usage access. Allow it in Android's settings instead."
        }
    }

    fun scanDuplicates() = run("Looking for duplicates") {
        val bySize = HashMap<Long, MutableList<FileItem>>()
        var n = 0
        fileOps.walk(ROOT, skipRestricted = true) { f ->
            if (f.size >= 1024 && !f.path.startsWith(TrashManager.TRASH_DIR)) bySize.getOrPut(f.size) { mutableListOf() } += f
            if (++n % 500 == 0) progress = "Indexing… $n files"
        }
        val groups = mutableListOf<List<FileItem>>()
        val candidates = bySize.values.filter { it.size > 1 }
        var done = 0
        for (sameSize in candidates) {
            progress = "Comparing ${++done} of ${candidates.size} groups"
            // Full SHA-256 only for files that already share a size; that keeps this tractable.
            val byHash = sameSize.groupBy { runCatching { fileOps.checksum(it.path, "SHA-256") }.getOrNull() ?: it.path }
            byHash.values.filter { it.size > 1 }.forEach { groups += it.sortedBy { f -> f.lastModified } }
        }
        duplicates = groups.sortedByDescending { it[0].size * (it.size - 1) }
    }

    fun scanCleanup() = run("Looking for clutter") {
        val out = mutableListOf<CleanupItem>()
        val pm = context.packageManager
        val cacheDirs = mutableListOf<FileItem>()
        fileOps.walk(ROOT, skipRestricted = true, onDir = { d ->
            val n = d.name.lowercase()
            if ((n == "cache" || n == ".cache" || n == ".thumbnails" || n == "thumbnails") && !d.path.startsWith(TrashManager.TRASH_DIR)) cacheDirs += d
            if (!d.path.startsWith(TrashManager.TRASH_DIR) && fileOps.list(d.path).isEmpty() && !d.name.startsWith(".")) {
                out += CleanupItem(d.path, "Empty folder", 0L, "Empty folders")
            }
        }) { f ->
            if (f.name.endsWith(".apk", true) && !f.path.startsWith(TrashManager.TRASH_DIR)) {
                val info = runCatching { pm.getPackageArchiveInfo(f.path, 0) }.getOrNull()
                val installed = info?.packageName?.let { runCatching { pm.getPackageInfo(it, 0) }.getOrNull() }
                @Suppress("DEPRECATION")
                if (info != null && installed != null && installed.longVersionCode >= info.longVersionCode) {
                    out += CleanupItem(f.path, "Already installed (${info.packageName})", f.size, "Leftover APKs")
                }
            }
        }
        progress = "Measuring cache folders…"
        for (d in cacheDirs.take(80)) {
            val s = runCatching { fileOps.folderStats(d.path) }.getOrNull() ?: continue
            if (s.bytes >= 5L * 1024 * 1024) out += CleanupItem(d.path, "Cache folder, ${s.files} files", s.bytes, "Large cache folders", permanent = true)
        }
        cleanup = out.sortedWith(compareBy({ it.kind }, { -it.size }))
    }

    /** Sends [paths] to the trash, or deletes caches for good. */
    fun removeCleanup(items: List<CleanupItem>) = run("Cleaning up") {
        val (perm, soft) = items.partition { it.permanent }
        if (soft.isNotEmpty()) trash.trash(soft.map { it.path })
        if (perm.isNotEmpty()) deleteFiles(perm.map { it.path })
        val gone = items.map { it.path }.toSet()
        cleanup = cleanup?.filter { it.path !in gone }
        message = "Cleaned ${items.size} item(s), freed ${formatSize(items.sumOf { it.size })}"
    }

    fun trashDuplicates(files: List<FileItem>) = run("Moving to trash") {
        val (added, failed) = trash.trash(files.map { it.path })
        val gone = added.map { it.originalPath }.toSet()
        duplicates = duplicates?.map { g -> g.filter { it.path !in gone } }?.filter { it.size > 1 }
        message = "Moved ${added.size} duplicate(s) to trash" + if (failed.isNotEmpty()) "; ${failed.size} failed" else ""
    }

    fun loadApps() = run("Loading apps") {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        apps = pm.getInstalledApplications(PackageManager.GET_META_DATA).map { ai ->
            val v = runCatching { pm.getPackageInfo(ai.packageName, 0).versionName }.getOrNull() ?: "?"
            InstalledApp(ai.loadLabel(pm).toString(), ai.packageName, v, listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList()))
        }.sortedBy { it.label.lowercase() }
    }

    fun extractApk(app: InstalledApp) = run("Extracting ${app.label}") {
        val out = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "APKs/${app.label}-${app.version}".replace(Regex("[^\\w .()-/]"), "_"))
        out.mkdirs()
        app.apks.forEach { src -> File(src).copyTo(File(out, File(src).name), overwrite = true) }
        message = "Saved ${app.apks.size} file(s) to Download/APKs/${out.name}"
    }

    fun backup() = run("Backing up") {
        val obj = JSONObject().put("version", 1).put("settings", settings.exportJson()).put("userData", userData.exportJson())
        val out = FileOps.uniqueName(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AFE-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json")
        out.writeText(obj.toString(2))
        message = "Backup saved to Download/${out.name}. Saved server passwords are encrypted for this phone, so on another phone you'll re-enter them."
    }

    fun restore(uri: android.net.Uri) = run("Restoring") {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: error("Couldn't read the file")
        val obj = JSONObject(text)
        require(obj.has("settings") && obj.has("userData")) { "This isn't an AFE backup file" }
        settings.importJson(obj.getJSONObject("settings"))
        userData.importJson(obj.getString("userData"))
        message = "Restored settings, stars, tags, rules and connections"
    }

    fun exportBugReport(onDone: (File) -> Unit) = run("Creating bug report") {
        val zip = Diagnostics.exportBugReport(context, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        message = "Saved Download/${zip.name}"
        withContext(Dispatchers.Main) { onDone(zip) }
    }
}

private enum class Page { HOME, OVERVIEW, DUPES, CLEANUP, APPS, DIAG }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onBack: () -> Unit,
    onReveal: () -> Unit,
    onOpenNetwork: () -> Unit = {},
    onOpenServer: () -> Unit = {},
    onOpenVault: () -> Unit = {},
    onOpenSync: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    viewModel: ToolsViewModel = hiltViewModel()
) {
    var page by remember { mutableStateOf(Page.HOME) }
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(viewModel.message) { viewModel.message?.let { snack.showSnackbar(it); viewModel.message = null } }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.restore(uri) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (page == Page.OVERVIEW) viewModel.refreshUsageAccess() }
    androidx.activity.compose.BackHandler(enabled = page != Page.HOME) {
        // Inside a file type or the app list, Back returns to the overview first.
        if (viewModel.detail != null) viewModel.closeDetail() else { viewModel.cancel(); page = Page.HOME }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(when (page) {
                    Page.HOME -> "Tools"; Page.OVERVIEW -> viewModel.detail?.title ?: "Storage overview"; Page.DUPES -> "Duplicate finder"
                    Page.CLEANUP -> "Cleanup"; Page.APPS -> "Installed apps"; Page.DIAG -> "Diagnostics"
                }) },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            page == Page.HOME -> onBack()
                            viewModel.detail != null -> viewModel.closeDetail()
                            else -> { viewModel.cancel(); page = Page.HOME }
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (viewModel.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(viewModel.progress, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { viewModel.cancel() }) { Text("Stop") }
                }
            }
            when (page) {
                Page.HOME -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    ToolRow(Icons.Default.PieChart, "Storage overview", "Space by type and the largest files") { page = Page.OVERVIEW; if (!viewModel.showCachedOverview()) viewModel.scanOverview() }
                    ToolRow(Icons.Default.ContentCopy, "Duplicate finder", "Identical files by size and checksum, with preview") { page = Page.DUPES; viewModel.scanDuplicates() }
                    ToolRow(Icons.Default.CleaningServices, "Cleanup", "Empty folders, leftover APKs, large caches") { page = Page.CLEANUP; viewModel.scanCleanup() }
                    ToolRow(Icons.Default.Android, "Installed apps", "Extract the APK of any installed app") { page = Page.APPS; viewModel.loadApps() }
                    HorizontalDivider()
                    ToolRow(Icons.Default.Cloud, "Network storage", "SFTP, FTP, SMB and WebDAV; cloud via document providers", onOpenNetwork)
                    ToolRow(Icons.Default.Wifi, "Share over Wi-Fi", "Download and upload from a PC browser, with QR code", onOpenServer)
                    ToolRow(Icons.Default.Lock, "Private vault", "Encrypted folder unlocked with biometrics", onOpenVault)
                    ToolRow(Icons.Default.Sync, "Sync & auto-sort", "Scheduled folder backup, sorting rules, old-file cleanup", onOpenSync)
                    ToolRow(Icons.Default.Delete, "Trash", "Restore or permanently delete removed items", onOpenTrash)
                    HorizontalDivider()
                    ToolRow(Icons.Default.Backup, "Back up settings", "Pins, stars, tags, rules and preferences to a file") { viewModel.backup() }
                    ToolRow(Icons.Default.Restore, "Restore settings", "From a backup file") { restorePicker.launch(arrayOf("application/json", "*/*")) }
                    ToolRow(Icons.Default.BugReport, "Diagnostics", "Crashes, slow folder loads, export a bug report") { page = Page.DIAG }
                }
                Page.OVERVIEW -> when (val d = viewModel.detail) {
                    null -> StorageOverviewPage(viewModel, onReveal)
                    is OverviewDetail.Files -> FilesDetailPage(d.category, viewModel, onReveal)
                    is OverviewDetail.Apps -> AppsDetailPage(d.sort, viewModel, onReveal)
                }
                Page.DUPES -> DuplicatesPage(viewModel)
                Page.CLEANUP -> CleanupPage(viewModel)
                Page.APPS -> AppsPage(viewModel)
                Page.DIAG -> DiagPage(viewModel, context)
            }
        }
    }
}

@Composable
private fun ToolRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun DuplicatesPage(vm: ToolsViewModel) {
    val groups = vm.duplicates
    var marked by remember(groups) { mutableStateOf(groups?.flatMap { g -> g.drop(1).map { it.path } }?.toSet() ?: emptySet()) }
    var confirm by remember { mutableStateOf(false) }
    if (groups == null) { if (!vm.busy) Text("No data yet.", Modifier.padding(16.dp)); return }
    if (groups.isEmpty()) { Text("No duplicates found.", Modifier.padding(16.dp)); return }
    val all = groups.flatten().filter { it.path in marked }
    Column(Modifier.fillMaxSize()) {
        Text("${groups.size} groups. The oldest copy in each group is kept; ticked files go to the trash.", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp)) {
            groups.forEach { g ->
                item(key = "h" + g[0].path) { Text("${g[0].name} · ${formatSize(g[0].size)} × ${g.size}", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall) }
                items(g, key = { it.path }) { f ->
                    Row(Modifier.fillMaxWidth().clickable { marked = if (f.path in marked) marked - f.path else marked + f.path }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(f.path in marked, { marked = if (it) marked + f.path else marked - f.path })
                        com.sza.androidfolderexplorer.presentation.browser.FileThumb(f, 96, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), true) {
                            Icon(Icons.Default.InsertDriveFile, null, Modifier.size(44.dp).padding(8.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.path.removePrefix(ROOT), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Button(onClick = { confirm = true }, enabled = all.isNotEmpty() && !vm.busy, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Move ${all.size} file(s) to trash (${formatSize(all.sumOf { it.size })})")
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Trash ${all.size} duplicate(s)?") },
        text = { Text("They can be restored from the trash. Make sure at least one copy of each stays unticked.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.trashDuplicates(all) }) { Text("Move to trash") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )
}

@Composable
private fun CleanupPage(vm: ToolsViewModel) {
    val items = vm.cleanup
    var marked by remember(items) { mutableStateOf(emptySet<String>()) }
    if (items == null) { if (!vm.busy) Text("No data yet.", Modifier.padding(16.dp)); return }
    if (items.isEmpty()) { Text("Nothing to clean up.", Modifier.padding(16.dp)); return }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp)) {
            items.groupBy { it.kind }.forEach { (kind, list) ->
                item(key = "k$kind") { Text("$kind (${list.size})", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall) }
                items(list, key = { it.path }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { marked = if (c.path in marked) marked - c.path else marked + c.path }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(c.path in marked, { marked = if (it) marked + c.path else marked - c.path })
                        Column(Modifier.weight(1f)) {
                            Text(c.path.removePrefix(ROOT), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            Text(c.label + if (c.size > 0) " · ${formatSize(c.size)}" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        val picked = items.filter { it.path in marked }
        Text("Caches are deleted for good; everything else goes to the trash.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { vm.removeCleanup(picked) }, enabled = picked.isNotEmpty() && !vm.busy, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Clean ${picked.size} item(s) (${formatSize(picked.sumOf { it.size })})")
        }
    }
}

@Composable
private fun AppsPage(vm: ToolsViewModel) {
    val apps = vm.apps ?: return
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(q, { q = it }, placeholder = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp))
        LazyColumn {
            items(apps.filter { it.label.contains(q, true) || it.pkg.contains(q, true) }, key = { it.pkg }) { a ->
                Row(Modifier.fillMaxWidth().clickable { vm.extractApk(a) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${a.pkg} · ${a.version}" + if (a.apks.size > 1) " · ${a.apks.size} files" else "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.Download, "Extract APK")
                }
            }
        }
    }
}

@Composable
private fun DiagPage(vm: ToolsViewModel, context: Context) {
    var refresh by remember { mutableIntStateOf(0) }
    val perf = remember(refresh) { Diagnostics.read(Diagnostics.perfFile).ifBlank { "No slow folder loads recorded." } }
    val crash = remember(refresh) { Diagnostics.read(Diagnostics.crashFile).ifBlank { "No crashes recorded." } }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            vm.exportBugReport { zip ->
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
                context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Send bug report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) { Text("Export bug report") }
        OutlinedButton(onClick = { Diagnostics.clear(); refresh++ }) { Text("Clear logs") }
        Text("Slow folder loads", style = MaterialTheme.typography.titleSmall)
        Text(perf.takeLast(4000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text("Crashes", style = MaterialTheme.typography.titleSmall)
        Text(crash.takeLast(4000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}
