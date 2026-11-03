package com.ca1nm.droidfileexplorer.presentation.install

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.ca1nm.droidfileexplorer.data.formatSize
import com.ca1nm.droidfileexplorer.data.install.ApkBundle
import com.ca1nm.droidfileexplorer.data.install.AppInstaller
import com.ca1nm.droidfileexplorer.data.install.Engine
import com.ca1nm.droidfileexplorer.data.install.InstallOptions
import com.ca1nm.droidfileexplorer.data.install.InstallResult
import com.ca1nm.droidfileexplorer.data.install.InstallSource
import com.ca1nm.droidfileexplorer.data.source.ShizukuFileAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class InstallerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installer: AppInstaller,
    private val shizuku: ShizukuFileAccess
) : ViewModel() {

    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var source by mutableStateOf<InstallSource?>(null); private set
    var selected by mutableStateOf(setOf<String>())
    var engine by mutableStateOf(Engine.AUTO)
    var options by mutableStateOf(InstallOptions())
    var running by mutableStateOf(false); private set
    var progressText by mutableStateOf(""); private set
    var progress by mutableFloatStateOf(-1f); private set
    var result by mutableStateOf<InstallResult?>(null); private set
    var pendingConfirm by mutableStateOf<Intent?>(null)
    var shizukuRunning by mutableStateOf(false); private set
    var shizukuAllowed by mutableStateOf(false); private set
    private var job: Job? = null
    private var loadedPath: String? = null

    fun refreshShizuku() { shizukuRunning = shizuku.isServiceRunning(); shizukuAllowed = shizuku.hasPermission() }
    fun requestShizuku() = shizuku.requestPermission()

    fun load(path: String) {
        if (path == loadedPath) return
        loadedPath = path
        loading = true; error = null; source = null; result = null; selected = emptySet()
        refreshShizuku()
        viewModelScope.launch {
            try {
                val s = withContext(Dispatchers.IO) { ApkBundle.read(context, File(path), File(context.cacheDir, "install")) }
                source = s
                selected = s.parts.filter { it.recommended }.map { it.entry }.toSet()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Couldn't read this file" }
            loading = false
        }
    }

    fun install() {
        val s = source ?: return
        if (running) return
        running = true; result = null; progress = -1f; progressText = "Starting…"
        job = viewModelScope.launch {
            result = installer.install(
                context, s, s.parts.filter { it.entry in selected }, engine, options,
                onProgress = { text, f -> progressText = text; progress = f },
                onConfirm = { pendingConfirm = it }
            )
            running = false
        }
    }

    fun cancel() { job?.cancel(); running = false; progressText = "" }
    fun resolved() = installer.resolve(engine)
    fun clearResult() { result = null }
}

/** Full-screen installer for one APK or split bundle at [path]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallerScreen(path: String, onClose: () -> Unit, viewModel: InstallerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    LaunchedEffect(path) { viewModel.load(path) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshShizuku() }
    // The Android installer's own "Install?" screen is started from here so it appears over this app.
    LaunchedEffect(viewModel.pendingConfirm) {
        viewModel.pendingConfirm?.let { runCatching { context.startActivity(it) }; viewModel.pendingConfirm = null }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Install") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") } }
        )
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val s = viewModel.source
            when {
                viewModel.loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Reading ${File(path).name}…")
                }
                viewModel.error != null -> Column(Modifier.padding(24.dp)) {
                    Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                    Text(viewModel.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                s != null -> InstallerBody(s, viewModel, onClose)
            }
        }
    }
}

@Composable
fun InstallerDialog(path: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { InstallerScreen(path, onClose) }
    }
}

private fun relation(s: InstallSource): Pair<String, String> {
    val have = s.installedVersionCode ?: return "Install" to "Not installed yet"
    return when {
        s.versionCode > have -> "Update" to "Updates ${s.installedVersionName} (${have}) to ${s.versionName}"
        s.versionCode == have -> "Reinstall" to "This version is already installed"
        else -> "Downgrade" to "Older than the installed ${s.installedVersionName} (${have})"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstallerBody(s: InstallSource, vm: InstallerViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val (action, relationText) = relation(s)
    val downgrade = (s.installedVersionCode ?: Long.MIN_VALUE) > s.versionCode
    var showPerms by remember { mutableStateOf(false) }
    val chosen = vm.resolved()
    val needsShizukuGrant = vm.engine == Engine.SHIZUKU && !vm.shizukuAllowed
    val canInstall = !vm.running && vm.selected.isNotEmpty() && !(downgrade && !vm.options.allowDowngrade) && !needsShizukuGrant

    // Extra bottom space keeps the install button and result clear of the gesture bar.
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                s.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))) }
                    ?: Icon(Icons.Default.Android, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(s.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text("Version ${s.versionName} (${s.versionCode})", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(relationText, fontWeight = FontWeight.SemiBold)
                    Text("Android ${s.minSdk}+ · targets ${s.targetSdk} · ${formatSize(s.parts.filter { it.entry in vm.selected }.sumOf { it.size })} to install",
                        style = MaterialTheme.typography.bodySmall)
                    if (s.isBundle) Text("Split bundle · ${s.parts.size} APKs" + if (s.obbs.isNotEmpty()) " · ${s.obbs.size} game data file(s)" else "",
                        style = MaterialTheme.typography.bodySmall)
                    if (downgrade && !vm.options.allowDowngrade)
                        Text("Turn on \"Allow downgrade\" below to replace the newer version.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (s.parts.size > 1) {
            item { Text("Parts to install", style = MaterialTheme.typography.titleSmall) }
            item {
                Text("Ticked parts suit this phone. Change them only if an install fails.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(s.parts.size) { i ->
                val p = s.parts[i]
                val toggle = { vm.selected = if (p.entry in vm.selected) vm.selected - p.entry else vm.selected + p.entry }
                Row(Modifier.fillMaxWidth().clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(p.entry in vm.selected, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.label); Text(p.entry.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(formatSize(p.size), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item { Text("Install with", style = MaterialTheme.typography.titleSmall) }
        items(Engine.entries.size) { i ->
            val e = Engine.entries[i]
            val note = when (e) {
                Engine.AUTO -> "Uses ${if (vm.shizukuAllowed) "Shizuku" else "the Android installer"} right now"
                Engine.SHIZUKU -> when { vm.shizukuAllowed -> "Ready: installs with no prompts"; vm.shizukuRunning -> "Running; tap Allow below"; else -> "Not running. Start Shizuku first" }
                Engine.SYSTEM -> "Android shows its own confirmation"
            }
            Row(Modifier.fillMaxWidth().clickable { vm.engine = e }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(vm.engine == e, onClick = null, modifier = Modifier.padding(12.dp))
                Column { Text(e.label); Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (needsShizukuGrant && vm.shizukuRunning) item { OutlinedButton(onClick = { vm.requestShizuku() }) { Text("Allow Shizuku") } }

        item { Text("Options", style = MaterialTheme.typography.titleSmall) }
        item { OptionSwitch("Replace existing app", "Keep its data when updating", vm.options.replaceExisting) { vm.options = vm.options.copy(replaceExisting = it) } }
        item { OptionSwitch("Allow downgrade", "Install an older version over a newer one", vm.options.allowDowngrade) { vm.options = vm.options.copy(allowDowngrade = it) } }
        item { OptionSwitch("Grant all permissions", "Approve every permission at install (Shizuku only)", vm.options.grantAllPermissions) { vm.options = vm.options.copy(grantAllPermissions = it) } }
        item { OptionSwitch("Allow old apps", "Android 14+ blocks apps built for very old Android versions", vm.options.allowOldApps) { vm.options = vm.options.copy(allowOldApps = it) } }

        item {
            TextButton(onClick = { showPerms = !showPerms }) { Text("Permissions (${s.permissions.size})  ${if (showPerms) "▲" else "▼"}") }
            if (showPerms) Text(s.permissions.joinToString("\n") { it.removePrefix("android.permission.") }.ifEmpty { "None" },
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }

        item {
            if (vm.running) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (vm.progress in 0f..1f) LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(vm.progressText, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { vm.cancel() }) { Text("Cancel") }
                }
            } else {
                Button(onClick = { vm.install() }, enabled = canInstall, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.InstallMobile, null); Spacer(Modifier.width(8.dp))
                    Text("$action with ${if (chosen == Engine.SHIZUKU) "Shizuku" else "Android installer"}")
                }
            }
        }

        item {
            when (val r = vm.result) {
                is InstallResult.Success -> ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp))
                            Text("${r.message}: ${s.title}", fontWeight = FontWeight.SemiBold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val launch = remember(s.packageName) { context.packageManager.getLaunchIntentForPackage(s.packageName) }
                            if (launch != null) Button(onClick = { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open") }
                            OutlinedButton(onClick = onClose) { Text("Done") }
                        }
                    }
                }
                is InstallResult.Failure -> ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Install failed", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(r.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        if (r.code == "NEEDS_PERMISSION") Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("Open Android settings") }
                        if (r.code?.contains("DEPRECATED_SDK") == true && !vm.options.allowOldApps) Button(onClick = {
                            vm.options = vm.options.copy(allowOldApps = true); vm.install()
                        }) { Text("Retry allowing old apps") }
                    }
                }
                null -> {}
            }
        }
    }
}

@Composable
private fun OptionSwitch(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onCheckedChange = null)
    }
}
