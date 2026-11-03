package com.ca1nm.droidfileexplorer.presentation.receive

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ca1nm.droidfileexplorer.MainActivity
import com.ca1nm.droidfileexplorer.data.formatSize
import com.ca1nm.droidfileexplorer.data.settings.AppSettings
import com.ca1nm.droidfileexplorer.data.settings.ThemeMode
import com.ca1nm.droidfileexplorer.data.source.FileOps
import com.ca1nm.droidfileexplorer.ui.theme.DroidFileExplorerTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Target of "Share" from other apps, for example sharing files out of Google Drive. Copies the shared
 * files into Download/Received, showing progress, then opens that folder in the browser.
 */
@AndroidEntryPoint
class ReceiveActivity : ComponentActivity() {

    @Inject lateinit var appSettings: AppSettings

    private var total by mutableIntStateOf(0)
    private var position by mutableIntStateOf(0)
    private var currentName by mutableStateOf("")
    private var fraction by mutableFloatStateOf(0f)
    private var problem by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uris = sharedUris()
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nothing to receive", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        total = uris.size
        setContent {
            val settings by appSettings.state.collectAsState()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            DroidFileExplorerTheme(darkTheme = dark, accentId = settings.accent, blackDark = settings.blackDark) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val err = problem
                        if (err == null) {
                            Text("Saving to Download/Received", style = MaterialTheme.typography.titleMedium)
                            Text("File $position of $total", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                currentName, Modifier.padding(vertical = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall
                            )
                            if (fraction > 0f) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            Text("Couldn't save everything", style = MaterialTheme.typography.titleMedium)
                            Text(err, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                            Button(onClick = { finish() }) { Text("Close") }
                        }
                    }
                }
            }
        }
        lifecycleScope.launch { save(uris) }
    }

    private suspend fun save(uris: List<Uri>) {
        val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Received")
        withContext(Dispatchers.IO) { dest.mkdirs() }
        var saved = 0
        val failures = mutableListOf<String>()
        for ((i, uri) in uris.withIndex()) {
            position = i + 1
            fraction = 0f
            val (name, size) = withContext(Dispatchers.IO) { describe(uri) }
            currentName = name
            try {
                withContext(Dispatchers.IO) {
                    val target = FileOps.uniqueName(dest, name)
                    try {
                        val input = contentResolver.openInputStream(uri) ?: error("The sending app didn't provide the file")
                        input.use { src ->
                            target.outputStream().buffered().use { out ->
                                val buf = ByteArray(128 * 1024)
                                var copied = 0L
                                var lastShown = 0L
                                while (true) {
                                    val n = src.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    copied += n
                                    // Update the bar about every 512 KB, on the main thread.
                                    if (size > 0 && copied - lastShown >= 512 * 1024) {
                                        lastShown = copied
                                        val f = (copied.toFloat() / size).coerceIn(0f, 1f)
                                        runOnUiThread { fraction = f }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        target.delete()   // never leave a half-written file behind
                        throw e
                    }
                }
                saved++
            } catch (e: Exception) {
                failures += "$name: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (saved > 0) {
            val msg = "Saved $saved file(s) to Download/Received" + if (failures.isNotEmpty()) "; ${failures.size} failed" else ""
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_PATH, dest.absolutePath)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
        } else {
            problem = failures.joinToString("\n").ifBlank { "No files were saved" }
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE ->
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }

    /** The file's name (with an extension worked out from its type if the sender left it off) and size. */
    private fun describe(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
                }
            }
        }
        var clean = (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "shared-file")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimStart('.').ifBlank { "shared-file" }
        if (!clean.substringAfterLast('.', "").let { it.isNotEmpty() && it.length <= 5 }) {
            val ext = contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (ext != null) clean = "$clean.$ext"
        }
        return clean to size
    }
}
