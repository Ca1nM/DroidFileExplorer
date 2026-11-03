package com.ca1nm.droidfileexplorer.presentation.install

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ca1nm.droidfileexplorer.data.install.ApkBundle
import com.ca1nm.droidfileexplorer.data.settings.AppSettings
import com.ca1nm.droidfileexplorer.data.settings.ThemeMode
import com.ca1nm.droidfileexplorer.data.source.FileOps
import com.ca1nm.droidfileexplorer.ui.theme.DroidFileExplorerTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens when another app (a browser, a chat, Files) hands over an APK or a split bundle. Content
 * links are copied into this app's cache first so the installer can read them.
 */
@AndroidEntryPoint
class InstallActivity : ComponentActivity() {

    @Inject lateinit var appSettings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent?.data
        setContent {
            val settings by appSettings.state.collectAsState()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            DroidFileExplorerTheme(darkTheme = dark, accentId = settings.accent, blackDark = settings.blackDark) {
                Surface(Modifier.fillMaxSize()) {
                    var path by remember { mutableStateOf<String?>(null) }
                    var failed by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(uri) {
                        if (uri == null) { failed = "Nothing to install"; return@LaunchedEffect }
                        try { path = withContext(Dispatchers.IO) { resolve(uri) } } catch (e: Exception) { failed = e.message ?: "Couldn't read the file" }
                    }
                    when {
                        failed != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(failed!!) }
                        path == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else -> InstallerScreen(path!!, onClose = { finish() })
                    }
                }
            }
        }
    }

    /** A readable file path for [uri]: the file itself, or a copy in the cache for content links. */
    private fun resolve(uri: Uri): String {
        if (uri.scheme == "file") return uri.path ?: throw IllegalArgumentException("Bad file link")
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "app.apk"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        // Links from other apps often have no extension; decide from the content type.
        if (!name.endsWith(".apk", true) && !ApkBundle.isBundle(name)) name += ".apk"
        val dir = File(cacheDir, "inbox").apply { mkdirs() }
        val target = FileOps.uniqueName(dir, name)
        (contentResolver.openInputStream(uri) ?: throw IllegalStateException("Can't open the file")).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target.absolutePath
    }
}
