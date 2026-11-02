package com.sza.androidfolderexplorer.presentation.vault

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.sza.androidfolderexplorer.data.FileTypes
import com.sza.androidfolderexplorer.data.formatSize
import com.sza.androidfolderexplorer.data.source.FileOps
import com.sza.androidfolderexplorer.data.vault.VaultEntry
import com.sza.androidfolderexplorer.data.vault.VaultManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class VaultViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: VaultManager
) : ViewModel() {
    var unlocked by mutableStateOf(false); private set
    var entries by mutableStateOf<List<VaultEntry>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var opened by mutableStateOf<File?>(null)

    private fun launch(block: suspend () -> Unit) {
        busy = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { message = e.message ?: e.javaClass.simpleName; if (e is com.sza.androidfolderexplorer.data.vault.VaultLockedException) unlocked = false }
            busy = false
        }
    }

    /** Called after the user passes the biometric / screen-lock check. */
    fun onAuthenticated() { unlocked = true; refresh() }
    fun refresh() = launch { entries = vault.list() }

    fun lock() { unlocked = false; entries = emptyList(); vault.clearCache() }

    fun addFromUris(uris: List<android.net.Uri>) = launch {
        var n = 0
        for (u in uris) {
            var name = "file"
            context.contentResolver.query(u, null, null, null, null)?.use { q ->
                if (q.moveToFirst()) q.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = q.getString(it) }
            }
            (context.contentResolver.openInputStream(u) ?: error("Can't read $name")).use { vault.add(name, it) }
            n++
        }
        entries = vault.list()
        message = "Encrypted $n file(s). The originals are untouched; delete them yourself once you've checked."
    }

    fun export(e: VaultEntry) = launch {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Vault export").apply { mkdirs() }
        val out = FileOps.uniqueName(dir, e.name)
        vault.extract(e, out)
        message = "Decrypted to Download/Vault export/${out.name}"
    }

    fun open(e: VaultEntry) = launch { opened = vault.openTemp(e) }

    fun delete(e: VaultEntry) = launch { vault.delete(e); entries = vault.list(); message = "Deleted ${e.name}" }
}

private fun Context.findActivity(): FragmentActivity? {
    var c = this
    while (c is ContextWrapper) { if (c is FragmentActivity) return c; c = c.baseContext }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(onBack: () -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    var deleting by remember { mutableStateOf<VaultEntry?>(null) }
    var authError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) viewModel.addFromUris(it) }

    LaunchedEffect(viewModel.message) { viewModel.message?.let { snack.showSnackbar(it); viewModel.message = null } }
    // Lock as soon as the screen leaves the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.lock() }
    LaunchedEffect(viewModel.opened) {
        viewModel.opened?.let { f ->
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, FileTypes.mimeOf(f.name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"))
            }.onFailure { snack.showSnackbar("No app can open this file") }
            viewModel.opened = null
        }
    }

    fun authenticate() {
        val activity = context.findActivity() ?: run { authError = "Can't show the unlock prompt here"; return }
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(context).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            authError = "Set up a screen lock or fingerprint in Android settings to use the vault."; return
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { authError = null; viewModel.onAuthenticated() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { authError = errString.toString() }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock vault").setSubtitle("Your private files stay encrypted until you do")
            .setAllowedAuthenticators(authenticators).build())
    }

    Scaffold(
        snackbarHost = { com.sza.androidfolderexplorer.ui.theme.AppSnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text("Private vault") },
                navigationIcon = { IconButton(onClick = { viewModel.lock(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (viewModel.unlocked) IconButton(onClick = { viewModel.lock() }) { Icon(Icons.Default.Lock, "Lock now") } }
            )
        },
        floatingActionButton = {
            if (viewModel.unlocked) ExtendedFloatingActionButton(onClick = { picker.launch(arrayOf("*/*")) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add files") })
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (viewModel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!viewModel.unlocked) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Lock, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("The vault is locked", style = MaterialTheme.typography.titleMedium)
                    Text("Files are encrypted with a key kept in this phone's secure hardware and can only be opened after you unlock.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                    authError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { authenticate() }) { Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Unlock") }
                }
            } else if (viewModel.entries.isEmpty() && !viewModel.busy) {
                Text("The vault is empty. Add files with the button below, or choose \"Move to vault\" in the file browser's More menu.", Modifier.padding(24.dp))
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(viewModel.entries, key = { it.id }) { e ->
                        ListItem(
                            headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(formatSize(e.size)) },
                            leadingContent = { Icon(com.sza.androidfolderexplorer.presentation.browser.fileIconFor(e.name), null) },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { viewModel.export(e) }) { Icon(Icons.Default.Download, "Decrypt to Downloads") }
                                    IconButton(onClick = { deleting = e }) { Icon(Icons.Default.Delete, "Delete from vault") }
                                }
                            }
                        )
                        TextButton(onClick = { viewModel.open(e) }, Modifier.padding(start = 56.dp)) { Text("Open") }
                    }
                }
            }
        }
    }
    deleting?.let { d -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${d.name}?") },
        text = { Text("It is removed from the vault for good.") },
        confirmButton = { TextButton(onClick = { viewModel.delete(d); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}
