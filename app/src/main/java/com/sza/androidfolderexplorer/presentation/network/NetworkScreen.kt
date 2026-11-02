package com.sza.androidfolderexplorer.presentation.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sza.androidfolderexplorer.data.FileKind
import com.sza.androidfolderexplorer.data.FileTypes
import com.sza.androidfolderexplorer.data.SecretBox
import com.sza.androidfolderexplorer.domain.model.FileItem
import com.sza.androidfolderexplorer.presentation.browser.SharedClipboard
import com.sza.androidfolderexplorer.data.network.CloudNames
import com.sza.androidfolderexplorer.data.network.NetworkRequests
import com.sza.androidfolderexplorer.data.formatSize
import com.sza.androidfolderexplorer.data.network.HostKeyStore
import com.sza.androidfolderexplorer.data.network.RemoteClient
import com.sza.androidfolderexplorer.data.network.RemoteClients
import com.sza.androidfolderexplorer.data.network.RemoteItem
import com.sza.androidfolderexplorer.data.settings.NetworkConnection
import com.sza.androidfolderexplorer.data.settings.UserData
import com.sza.androidfolderexplorer.data.source.FileOps
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class NetworkViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val userData: UserData
) : ViewModel() {
    private val hostKeys = HostKeyStore(context)
    private var client: RemoteClient? = null
    private var job: Job? = null

    /** One step of the path from the account root down to the folder on screen. */
    data class Crumb(val path: String, val name: String)

    var active by mutableStateOf<NetworkConnection?>(null); private set
    var crumbs by mutableStateOf<List<Crumb>>(emptyList()); private set
    /** The folder on screen: a path for servers, a document id for cloud accounts. */
    val path: String get() = crumbs.lastOrNull()?.path ?: "/"
    var items by mutableStateOf<List<RemoteItem>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    var lastDownload by mutableStateOf<File?>(null)
    /** The last failure, kept on screen until dismissed (the snackbar message disappears after a few seconds). */
    var error by mutableStateOf<String?>(null)

    init {
        // Older versions saved cloud accounts under the provider's raw id; give them readable names.
        viewModelScope.launch(Dispatchers.IO) {
            userData.state.value.connections.filter { it.protocol == "saf" && CloudNames.looksRaw(it.name) }.forEach { c ->
                val fixed = CloudNames.describe(context, Uri.parse(c.host))
                userData.update { s -> s.copy(connections = s.connections.map { if (it.id == c.id) it.copy(name = fixed) else it }) }
            }
        }
        // Open an account chosen from the hamburger menu.
        viewModelScope.launch {
            NetworkRequests.pending.collect { id ->
                if (id != null) {
                    val conn = userData.state.value.connections.firstOrNull { it.id == id }
                    com.sza.androidfolderexplorer.data.diag.Diagnostics.log("network", "open request $id found=${conn != null}")
                    conn?.let { connect(it) }
                    NetworkRequests.consume()
                }
            }
        }
    }

    /** Remembers a cloud account picked in the system file picker. */
    fun addProvider(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        viewModelScope.launch(Dispatchers.IO) {
            val name = CloudNames.describe(context, uri)
            userData.update { s ->
                // Picking the same account again shouldn't add a duplicate.
                if (s.connections.any { it.protocol == "saf" && it.host == uri.toString() }) s
                else s.copy(connections = s.connections + NetworkConnection(java.util.UUID.randomUUID().toString(), name, "saf", uri.toString(), 0))
            }
        }
    }

    private val downloadDir get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Network")
    /** Where saved downloads, and archives extracted from a server file, go. */
    val downloadFolder: String get() = downloadDir.absolutePath

    /** Runs blocking work (network, disk) off the main thread. */
    private suspend fun <T> io(f: () -> T): T = withContext(Dispatchers.IO) { f() }

    /**
     * Runs [block] on the main thread; it moves slow work into [io] itself. Screen state is only ever
     * written here, on the main thread. Writing it from a background thread while the screen was still
     * being built could be missed, which left the account list showing after a successful connection.
     */
    private fun launch(label: String, block: suspend () -> Unit) {
        job?.cancel()
        busy = true; status = label; error = null
        job = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) {
                message = "$label: ${e.message ?: e.javaClass.simpleName}"
                error = message
                com.sza.androidfolderexplorer.data.diag.Diagnostics.log("network", "$label failed: ${e.stackTraceToString().take(800)}")
            }
            busy = false
        }
    }

    fun save(conn: NetworkConnection, newPassword: String) {
        val existing = userData.state.value.connections.firstOrNull { it.id == conn.id }
        val stored = conn.copy(password = if (newPassword.isNotEmpty()) SecretBox.encrypt(newPassword) else existing?.password.orEmpty())
        userData.update { s -> s.copy(connections = s.connections.filter { it.id != conn.id } + stored) }
    }

    fun delete(conn: NetworkConnection) = userData.update { s -> s.copy(connections = s.connections - conn) }

    /** The account or server last tapped, so Retry can try it again. */
    var lastAttempt: NetworkConnection? = null

    /** For each cloud account, the name of the folder it was granted (Android only shares the folder you pick). */
    var shownFolders by mutableStateOf<Map<String, String>>(emptyMap()); private set

    init {
        viewModelScope.launch {
            val names = io {
                userData.state.value.connections.filter { it.protocol == "saf" }.mapNotNull { c ->
                    val tree = Uri.parse(c.host)
                    val name = runCatching {
                        context.contentResolver.query(
                            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
                        )?.use { if (it.moveToFirst()) it.getString(0) else null }
                    }.getOrNull()
                    name?.let { c.id to it }
                }.toMap()
            }
            shownFolders = names
        }
    }

    fun connect(conn: NetworkConnection) { lastAttempt = conn; connectNow(conn) }

    private fun connectNow(conn: NetworkConnection) = launch("Connecting to ${conn.name}") {
        val c = io { RemoteClients.open(context, conn, hostKeys) }
        client?.close(); client = c
        // Switch to the account's view straight away; Drive can take a moment to load its folders.
        active = conn
        crumbs = listOf(Crumb(c.rootPath, conn.name))
        items = emptyList()
        status = "Loading ${conn.name}…"
        items = io { sorted(c.list(c.rootPath)) }
    }

    private fun sorted(list: List<RemoteItem>) = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

    /** Opens [folder] and adds it to the path. */
    fun openFolder(folder: RemoteItem) = launch("Loading ${folder.name}") {
        val c = client ?: return@launch
        val list = io { sorted(c.list(folder.path)) }
        crumbs = crumbs + Crumb(folder.path, folder.name); items = list
    }

    /** Jumps back to an earlier folder in the path. */
    fun openCrumb(index: Int) = launch("Loading") {
        val c = client ?: return@launch
        val target = crumbs.getOrNull(index) ?: return@launch
        val list = io { sorted(c.list(target.path)) }
        crumbs = crumbs.take(index + 1); items = list
    }

    /** One folder up. False at the account root, where Back should leave the account. */
    fun up(): Boolean {
        if (crumbs.size <= 1) return false
        openCrumb(crumbs.size - 2); return true
    }

    fun disconnect() { job?.cancel(); client?.close(); client = null; active = null; crumbs = emptyList(); items = emptyList(); busy = false }

    fun download(item: RemoteItem) = launch("Downloading ${item.name}") {
        val c = client ?: return@launch
        val target = io {
            downloadDir.mkdirs()
            val t = FileOps.uniqueName(downloadDir, item.name)
            try { t.outputStream().buffered().use { c.download(item, it) } }
            catch (e: Throwable) { t.delete(); throw e }
            t
        }
        lastDownload = target
        message = "Saved to Download/Network/${target.name}"
    }

    /**
     * Fetches [item] into a temporary cache copy (not your Downloads) and hands it to [onReady] to be opened. Each open
     * replaces the previous copy, so the cache never fills up.
     */
    fun open(item: RemoteItem, onReady: (File) -> Unit) = launch("Opening ${item.name}") {
        val c = client ?: return@launch
        val file = io {
            val root = File(context.cacheDir, "remote_open")
            root.deleteRecursively()
            val dir = File(root, System.nanoTime().toString()).also { it.mkdirs() }
            val t = File(dir, item.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" })
            try { t.outputStream().buffered().use { c.download(item, it) } }
            catch (e: Throwable) { t.delete(); throw e }
            t
        }
        onReady(file)
    }

    fun upload(uris: List<Uri>) = launch("Uploading") {
        val c = client ?: return@launch
        var n = 0
        val dir = path
        for (u in uris) {
            val (name, size) = io {
                var nm = u.lastPathSegment?.substringAfterLast('/') ?: "file"
                var sz = -1L
                context.contentResolver.query(u, null, null, null, null)?.use { q ->
                    if (q.moveToFirst()) {
                        q.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { nm = q.getString(it) }
                        q.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { sz = q.getLong(it) }
                    }
                }
                nm to sz
            }
            status = "Uploading $name"
            io { (context.contentResolver.openInputStream(u) ?: error("Can't read $name")).use { c.upload(dir, name, it, size) } }
            n++
        }
        items = io { sorted(c.list(dir)) }
        message = "Uploaded $n file(s)"
    }

    /** Copies what was copied or cut in the file browser into the folder on screen; a cut removes the originals afterwards. */
    fun pasteClipboard() = launch("Copying here") {
        val c = client ?: return@launch
        val clip = SharedClipboard.state.value ?: return@launch
        val dir = path
        var n = 0
        for (p in clip.paths) {
            val f = File(p)
            status = "Uploading ${f.name}"
            n += io { uploadTree(c, dir, f) }
        }
        if (clip.isMove) {
            // Only reached when every upload worked; a failure above leaves the originals alone.
            io { clip.paths.forEach { File(it).deleteRecursively() } }
            SharedClipboard.set(null)
        }
        items = io { sorted(c.list(dir)) }
        message = if (clip.isMove) "Moved $n file(s) here" else "Copied $n file(s) here"
    }

    private fun uploadTree(c: RemoteClient, dir: String, f: File): Int {
        if (!f.isDirectory) {
            f.inputStream().buffered().use { c.upload(dir, f.name, it, f.length()) }
            return 1
        }
        c.mkdir(dir, f.name)
        val made = c.list(dir).firstOrNull { it.isDirectory && it.name == f.name } ?: error("Couldn't find the new folder ${f.name}")
        return f.listFiles().orEmpty().sumOf { uploadTree(c, made.path, it) }
    }

    fun mkdir(name: String) = launch("New folder") {
        val c = client ?: return@launch
        val dir = path
        items = io { c.mkdir(dir, name.trim()); sorted(c.list(dir)) }
    }

    fun rename(item: RemoteItem, name: String) = launch("Rename") {
        val c = client ?: return@launch
        val dir = path
        items = io { c.rename(item, name.trim()); sorted(c.list(dir)) }
    }

    fun remove(item: RemoteItem) = launch("Delete") {
        val c = client ?: return@launch
        val dir = path
        items = io { c.delete(item); sorted(c.list(dir)) }
    }

    fun forgetHostKey(conn: NetworkConnection) { hostKeys.forget("${conn.host}:${conn.port}"); message = "Forgot the saved server identity for ${conn.host}" }

    override fun onCleared() { client?.close() }
}

private val Protocols = listOf(
    "sftp" to ("SFTP (SSH)" to 22), "ftp" to ("FTP (not encrypted)" to 21), "smb" to ("SMB / Windows share" to 445),
    "webdav" to ("WebDAV (http)" to 80), "webdavs" to ("WebDAV (https)" to 443)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(onBack: () -> Unit, viewModel: NetworkViewModel = hiltViewModel()) {
    // Opens files you tap here in the app's own viewers, editor and archive view, on top of this screen.
    val files: com.sza.androidfolderexplorer.presentation.browser.FileBrowserViewModel = hiltViewModel()
    val fileState by files.uiState.collectAsStateWithLifecycle()
    com.sza.androidfolderexplorer.presentation.browser.FileOverlays(fileState, files)
    val user by viewModel.userData.state.collectAsStateWithLifecycle()
    val context = LocalContext()
    val snack = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<NetworkConnection?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<RemoteItem?>(null) }
    var renamingAccount by remember { mutableStateOf<NetworkConnection?>(null) }
    var showPickHelp by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<RemoteItem?>(null) }
    var menuFor by remember { mutableStateOf<RemoteItem?>(null) }
    // Messages from the file viewers opened on top of this screen (for example "Extracted 12 file(s) to …").
    LaunchedEffect(fileState.snackbar?.id) {
        fileState.snackbar?.let { snack.showSnackbar(it.text); files.dismissSnackbar() }
    }
    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            val file = viewModel.lastDownload
            val r = snack.showSnackbar(it, actionLabel = if (file != null && it.startsWith("Saved")) "Open" else null)
            if (r == SnackbarResult.ActionPerformed && file != null) openLocal(context, file)
            viewModel.message = null; viewModel.lastDownload = null
        }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addProvider(uri)
    }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) viewModel.upload(uris) }

    val clip by SharedClipboard.state.collectAsStateWithLifecycle()
    val connected = viewModel.active != null
    BackHandler(enabled = connected) { if (!viewModel.up()) viewModel.disconnect() }

    Scaffold(
        snackbarHost = { com.sza.androidfolderexplorer.ui.theme.AppSnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(if (connected) viewModel.active!!.name else "Network & cloud", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { if (connected && viewModel.up()) Unit else if (connected) viewModel.disconnect() else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (connected) {
                        IconButton(onClick = { newFolder = true }) { Icon(Icons.Default.CreateNewFolder, "New folder") }
                        IconButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Upload, "Upload") }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!connected) ExtendedFloatingActionButton(onClick = { editing = NetworkConnection(java.util.UUID.randomUUID().toString(), "", "sftp", "", 22) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add server") })
            else clip?.let { cb ->
                ExtendedFloatingActionButton(
                    onClick = { if (!viewModel.busy) viewModel.pasteClipboard() },
                    icon = { Icon(Icons.Default.ContentPaste, null) },
                    text = { Text("${if (cb.isMove) "Move" else "Paste"} ${cb.paths.size} item(s) here") }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (viewModel.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(viewModel.status, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
            viewModel.error?.let { err ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(err, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        viewModel.error = null
                        // Try the same thing again: reload this folder, or reconnect from the account list.
                        if (viewModel.active != null) viewModel.openCrumb(viewModel.crumbs.lastIndex)
                        else viewModel.lastAttempt?.let { viewModel.connect(it) }
                    }) { Text("Retry") }
                    TextButton(onClick = { viewModel.error = null }) { Text("Dismiss") }
                }
            }
            if (!connected) {
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        ListItem(
                            headlineContent = { Text("Add cloud storage") },
                            supportingContent = { Text("Google Drive, Dropbox, OneDrive and others that appear in the Android file picker") },
                            leadingContent = { Icon(Icons.Default.Cloud, null) },
                            modifier = Modifier.clickable { showPickHelp = true }
                        )
                        HorizontalDivider()
                    }
                    if (user.connections.isEmpty()) item { Text("No servers yet. Add an SFTP, FTP, SMB or WebDAV server with the button below.", Modifier.padding(16.dp)) }
                    items(user.connections, key = { it.id }) { c ->
                        ListItem(
                            headlineContent = { Text(c.name.ifBlank { c.host }) },
                            supportingContent = { Text(if (c.protocol == "saf") "Cloud · shows the folder \"${viewModel.shownFolders[c.id] ?: "…"}\"" else "${c.protocol.uppercase()} · ${c.user.ifBlank { "anonymous" }}@${c.host}:${c.port}") },
                            leadingContent = { Icon(if (c.protocol == "saf") Icons.Default.Cloud else Icons.Default.Storage, null) },
                            trailingContent = {
                                Row {
                                    if (c.protocol != "saf") IconButton(onClick = { editing = c }) { Icon(Icons.Default.Edit, "Edit") }
                                    else IconButton(onClick = { renamingAccount = c }) { Icon(Icons.Default.Edit, "Rename ${c.name}") }
                                    IconButton(onClick = { viewModel.delete(c) }) { Icon(Icons.Default.Delete, "Remove") }
                                }
                            },
                            modifier = Modifier.clickable { viewModel.connect(c) }
                        )
                    }
                }
            } else {
                val crumbScroll = rememberScrollState()
                LaunchedEffect(viewModel.crumbs.size) { crumbScroll.scrollTo(crumbScroll.maxValue) }
                Row(Modifier.fillMaxWidth().horizontalScroll(crumbScroll).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    viewModel.crumbs.forEachIndexed { i, crumb ->
                        if (i > 0) Text("›", style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { viewModel.openCrumb(i) }, enabled = i < viewModel.crumbs.lastIndex) {
                            Text(crumb.name, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    if (viewModel.items.isEmpty() && !viewModel.busy) item { Text("Empty folder", Modifier.padding(16.dp)) }
                    items(viewModel.items, key = { it.path }) { it ->
                        Box {
                            ListItem(
                                headlineContent = { Text(it.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Text(listOfNotNull(if (it.isDirectory) "Folder" else formatSize(it.size),
                                        it.modified.takeIf { m -> m > 0 }?.let { m -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m)) }).joinToString(" · "))
                                },
                                leadingContent = { Icon(remoteIcon(it), null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { IconButton(onClick = { menuFor = it }) { Icon(Icons.Default.MoreVert, "Actions for ${it.name}") } },
                                // A tap opens the file (in the app's own viewers); Download in the menu saves a copy.
                                modifier = Modifier.clickable {
                                    if (it.isDirectory) viewModel.openFolder(it)
                                    else viewModel.open(it) { f ->
                                        files.openTemporary(FileItem(f.name, f.absolutePath, false, f.length(), f.lastModified()), viewModel.downloadFolder)
                                    }
                                }
                            )
                            DropdownMenu(menuFor == it, { menuFor = null }) {
                                if (!it.isDirectory) DropdownMenuItem(text = { Text("Download") }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { menuFor = null; viewModel.download(it) })
                                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menuFor = null; renaming = it })
                                DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menuFor = null; deleting = it })
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { c -> ConnectionDialog(c, hasPassword = user.connections.any { it.id == c.id && it.password.isNotEmpty() },
        onDismiss = { editing = null }, onSave = { conn, pw -> viewModel.save(conn, pw); editing = null },
        onForgetKey = { viewModel.forgetHostKey(c) }) }
    if (showPickHelp) AlertDialog(
        onDismissRequest = { showPickHelp = false },
        title = { Text("Choose what to share") },
        text = {
            Text(
                "Android shows its own folder picker next. This app can only see the folder you pick there.\n\n" +
                    "To see everything in Google Drive, open the \"My Drive\" folder and tap \"Use this folder\". " +
                    "Picking a smaller folder, such as Classroom, shows only that folder."
            )
        },
        confirmButton = { TextButton(onClick = { showPickHelp = false; treePicker.launch(null) }) { Text("Open the picker") } },
        dismissButton = { TextButton(onClick = { showPickHelp = false }) { Text("Cancel") } }
    )
    renamingAccount?.let { acct ->
        com.sza.androidfolderexplorer.presentation.browser.PromptDialog(
            "Rename account", "Name", acct.name, "Rename", { renamingAccount = null },
            { viewModel.save(acct.copy(name = it.trim()), ""); renamingAccount = null }
        )
    }
    if (newFolder) com.sza.androidfolderexplorer.presentation.browser.PromptDialog("New folder", "Name", "", "Create", { newFolder = false }, { viewModel.mkdir(it); newFolder = false })
    renaming?.let { r -> com.sza.androidfolderexplorer.presentation.browser.PromptDialog("Rename", "Name", r.name, "Rename", { renaming = null }, { viewModel.rename(r, it); renaming = null }) }
    deleting?.let { d -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${d.name}?") },
        text = { Text("This deletes it on the server. It cannot be undone.") },
        confirmButton = { TextButton(onClick = { viewModel.remove(d); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

/** An icon for the kind of file, or for Google's own document types. */
private fun remoteIcon(item: RemoteItem): androidx.compose.ui.graphics.vector.ImageVector {
    if (item.isDirectory) return Icons.Default.Folder
    val mime = item.mime.orEmpty()
    return when {
        mime.startsWith("application/vnd.google-apps.spreadsheet") -> Icons.Default.TableChart
        mime.startsWith("application/vnd.google-apps") -> Icons.Default.Description
        // Same icon set as the main browser; the MIME type only decides when the name has no known extension.
        FileTypes.ext(item.name).isNotEmpty() -> com.sza.androidfolderexplorer.presentation.browser.fileIconFor(item.name)
        mime.startsWith("image/") -> Icons.Default.Image
        mime.startsWith("video/") -> Icons.Default.Movie
        mime.startsWith("audio/") -> Icons.Default.MusicNote
        else -> com.sza.androidfolderexplorer.presentation.browser.fileIconFor(item.name)
    }
}

@Composable
private fun LocalContext() = androidx.compose.ui.platform.LocalContext.current

private fun openLocal(context: Context, file: File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, com.sza.androidfolderexplorer.data.FileTypes.mimeOf(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun ConnectionDialog(
    initial: NetworkConnection, hasPassword: Boolean,
    onDismiss: () -> Unit, onSave: (NetworkConnection, String) -> Unit, onForgetKey: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var protocol by remember { mutableStateOf(initial.protocol) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.user) }
    var password by remember { mutableStateOf("") }
    var path by remember { mutableStateOf(initial.path) }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.host.isEmpty()) "Add server" else "Edit server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Box {
                    OutlinedButton(onClick = { menu = true }, Modifier.fillMaxWidth()) { Text(Protocols.first { it.first == protocol }.second.first) }
                    DropdownMenu(menu, { menu = false }) {
                        Protocols.forEach { (id, v) ->
                            DropdownMenuItem(text = { Text(v.first) }, onClick = { protocol = id; port = v.second.toString(); menu = false })
                        }
                    }
                }
                OutlinedTextField(host, { host = it.trim() }, label = { Text("Host or IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text("User name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text(if (hasPassword) "Password (leave empty to keep)" else "Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(path, { path = it }, label = { Text(if (protocol == "smb") "Share, e.g. /Movies" else "Start folder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (protocol == "ftp") Text("FTP sends your password unencrypted. Use it only on a network you trust.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (protocol == "webdav") Text("Plain http sends your password unencrypted. Prefer WebDAV (https).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (protocol == "sftp" && initial.host.isNotEmpty()) TextButton(onClick = onForgetKey) { Text("Forget saved server identity") }
                Text("Passwords are encrypted with a key kept in this phone's secure hardware.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = host.isNotBlank() && port.isNotBlank(), onClick = {
                onSave(initial.copy(name = name.ifBlank { host }, protocol = protocol, host = host, port = port.toIntOrNull() ?: 0, user = user, path = path.ifBlank { "/" }), password)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
