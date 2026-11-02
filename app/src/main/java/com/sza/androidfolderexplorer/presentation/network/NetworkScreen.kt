package com.sza.androidfolderexplorer.presentation.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import com.sza.androidfolderexplorer.data.SecretBox
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

    var active by mutableStateOf<NetworkConnection?>(null); private set
    var path by mutableStateOf("/"); private set
    var items by mutableStateOf<List<RemoteItem>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    var lastDownload by mutableStateOf<File?>(null)

    private val downloadDir get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Network")

    private fun launch(label: String, block: suspend () -> Unit) {
        job?.cancel()
        busy = true; status = label
        job = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { message = "$label: ${e.message ?: e.javaClass.simpleName}" }
            busy = false
        }
    }

    fun save(conn: NetworkConnection, newPassword: String) {
        val existing = userData.state.value.connections.firstOrNull { it.id == conn.id }
        val stored = conn.copy(password = if (newPassword.isNotEmpty()) SecretBox.encrypt(newPassword) else existing?.password.orEmpty())
        userData.update { s -> s.copy(connections = s.connections.filter { it.id != conn.id } + stored) }
    }

    fun delete(conn: NetworkConnection) = userData.update { s -> s.copy(connections = s.connections - conn) }

    fun connect(conn: NetworkConnection) = launch("Connecting to ${conn.name}") {
        val c = RemoteClients.open(context, conn, hostKeys)
        client?.close(); client = c
        active = conn
        path = c.rootPath
        items = c.list(path).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun open(p: String) = launch("Loading") {
        val c = client ?: return@launch
        val list = c.list(p).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        path = p; items = list
    }

    fun up(): Boolean {
        val conn = active ?: return false
        if (conn.protocol == "saf") return false // document ids are opaque; leave through the back button
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        if (path.trimEnd('/').isEmpty() || path == client?.rootPath) return false
        open(parent.ifEmpty { "/" }); return true
    }

    fun disconnect() { job?.cancel(); client?.close(); client = null; active = null; items = emptyList(); busy = false }

    fun download(item: RemoteItem) = launch("Downloading ${item.name}") {
        val c = client ?: return@launch
        downloadDir.mkdirs()
        val target = FileOps.uniqueName(downloadDir, item.name)
        try { target.outputStream().buffered().use { c.download(item, it) } }
        catch (e: Throwable) { target.delete(); throw e }
        lastDownload = target
        message = "Saved to Download/Network/${target.name}"
    }

    fun upload(uris: List<Uri>) = launch("Uploading") {
        val c = client ?: return@launch
        var n = 0
        for (u in uris) {
            var name = u.lastPathSegment?.substringAfterLast('/') ?: "file"
            var size = -1L
            context.contentResolver.query(u, null, null, null, null)?.use { q ->
                if (q.moveToFirst()) {
                    q.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = q.getString(it) }
                    q.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = q.getLong(it) }
                }
            }
            status = "Uploading $name"
            (context.contentResolver.openInputStream(u) ?: error("Can't read $name")).use { c.upload(path, name, it, size) }
            n++
        }
        items = c.list(path).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        message = "Uploaded $n file(s)"
    }

    fun mkdir(name: String) = launch("New folder") {
        val c = client ?: return@launch; c.mkdir(path, name.trim())
        items = c.list(path).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun rename(item: RemoteItem, name: String) = launch("Rename") {
        val c = client ?: return@launch; c.rename(item, name.trim())
        items = c.list(path).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun remove(item: RemoteItem) = launch("Delete") {
        val c = client ?: return@launch; c.delete(item)
        items = c.list(path).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
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
    val user by viewModel.userData.state.collectAsStateWithLifecycle()
    val context = LocalContext()
    val snack = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<NetworkConnection?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<RemoteItem?>(null) }
    var deleting by remember { mutableStateOf<RemoteItem?>(null) }
    var menuFor by remember { mutableStateOf<RemoteItem?>(null) }
    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            val file = viewModel.lastDownload
            val r = snack.showSnackbar(it, actionLabel = if (file != null && it.startsWith("Saved")) "Open" else null)
            if (r == SnackbarResult.ActionPerformed && file != null) openLocal(context, file)
            viewModel.message = null; viewModel.lastDownload = null
        }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val label = uri.authority?.substringAfterLast('.')?.replaceFirstChar { it.uppercase() } ?: "Provider"
            viewModel.save(NetworkConnection(java.util.UUID.randomUUID().toString(), "$label (${uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { "root" }})", "saf", uri.toString(), 0), "")
        }
    }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) viewModel.upload(uris) }

    val connected = viewModel.active != null
    BackHandler(enabled = connected) { if (!viewModel.up()) viewModel.disconnect() }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
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
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (viewModel.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(viewModel.status, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
            if (!connected) {
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        ListItem(
                            headlineContent = { Text("Add cloud storage") },
                            supportingContent = { Text("Google Drive, Dropbox, OneDrive and others that appear in the Android file picker") },
                            leadingContent = { Icon(Icons.Default.Cloud, null) },
                            modifier = Modifier.clickable { treePicker.launch(null) }
                        )
                        HorizontalDivider()
                    }
                    if (user.connections.isEmpty()) item { Text("No servers yet. Add an SFTP, FTP, SMB or WebDAV server with the button below.", Modifier.padding(16.dp)) }
                    items(user.connections, key = { it.id }) { c ->
                        ListItem(
                            headlineContent = { Text(c.name.ifBlank { c.host }) },
                            supportingContent = { Text(if (c.protocol == "saf") "Cloud / document provider" else "${c.protocol.uppercase()} · ${c.user.ifBlank { "anonymous" }}@${c.host}:${c.port}") },
                            leadingContent = { Icon(if (c.protocol == "saf") Icons.Default.Cloud else Icons.Default.Storage, null) },
                            trailingContent = {
                                Row {
                                    if (c.protocol != "saf") IconButton(onClick = { editing = c }) { Icon(Icons.Default.Edit, "Edit") }
                                    IconButton(onClick = { viewModel.delete(c) }) { Icon(Icons.Default.Delete, "Remove") }
                                }
                            },
                            modifier = Modifier.clickable { viewModel.connect(c) }
                        )
                    }
                }
            } else {
                Text(viewModel.path, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LazyColumn(Modifier.weight(1f)) {
                    if (viewModel.items.isEmpty() && !viewModel.busy) item { Text("Empty folder", Modifier.padding(16.dp)) }
                    items(viewModel.items, key = { it.path }) { it ->
                        Box {
                            ListItem(
                                headlineContent = { Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Text(listOfNotNull(if (it.isDirectory) "Folder" else formatSize(it.size),
                                        it.modified.takeIf { m -> m > 0 }?.let { m -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m)) }).joinToString(" · "))
                                },
                                leadingContent = { Icon(if (it.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null) },
                                trailingContent = { IconButton(onClick = { menuFor = it }) { Icon(Icons.Default.MoreVert, "Actions for ${it.name}") } },
                                modifier = Modifier.clickable { if (it.isDirectory) viewModel.open(it.path) else viewModel.download(it) }
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
    if (newFolder) com.sza.androidfolderexplorer.presentation.browser.PromptDialog("New folder", "Name", "", "Create", { newFolder = false }, { viewModel.mkdir(it); newFolder = false })
    renaming?.let { r -> com.sza.androidfolderexplorer.presentation.browser.PromptDialog("Rename", "Name", r.name, "Rename", { renaming = null }, { viewModel.rename(r, it); renaming = null }) }
    deleting?.let { d -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${d.name}?") },
        text = { Text("This deletes it on the server. It cannot be undone.") },
        confirmButton = { TextButton(onClick = { viewModel.remove(d); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
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
