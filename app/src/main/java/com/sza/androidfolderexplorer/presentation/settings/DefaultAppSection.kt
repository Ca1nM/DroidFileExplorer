package com.sza.androidfolderexplorer.presentation.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.io.File

/** A kind of file other apps ask to open, with a sample name and mime type that stand in for it. */
private data class FileTypeEntry(val label: String, val ext: String, val mime: String)

private val GROUPS: List<Pair<String, List<FileTypeEntry>>> = listOf(
    "Archives and disc images" to listOf(
        FileTypeEntry("ZIP", "zip", "application/zip"),
        FileTypeEntry("7-Zip", "7z", "application/x-7z-compressed"),
        FileTypeEntry("RAR", "rar", "application/vnd.rar"),
        FileTypeEntry("TAR", "tar", "application/x-tar"),
        FileTypeEntry("GZ", "gz", "application/gzip"),
        FileTypeEntry("ISO disc image", "iso", "application/x-iso9660-image")
    ),
    "Text and config files" to listOf(
        FileTypeEntry("Plain text", "txt", "text/plain"),
        FileTypeEntry("JSON", "json", "application/json"),
        FileTypeEntry("XML", "xml", "text/xml"),
        FileTypeEntry("Markdown", "md", "text/markdown"),
        FileTypeEntry("CSV", "csv", "text/csv"),
        FileTypeEntry("Unknown type (.bin, .img, configs)", "bin", "application/octet-stream")
    ),
    "Documents and media" to listOf(
        FileTypeEntry("PDF", "pdf", "application/pdf"),
        FileTypeEntry("APK", "apk", "application/vnd.android.package-archive"),
        FileTypeEntry("Images", "png", "image/png"),
        FileTypeEntry("Video", "mp4", "video/mp4"),
        FileTypeEntry("Audio", "mp3", "audio/mpeg")
    )
)

/** Who answers an "open this file" request for one type right now. */
private sealed interface Handler {
    data object Us : Handler
    data object Nobody : Handler                         // no default chosen: Android asks every time
    data class Other(val pkg: String, val name: String) : Handler
}

private fun handlerFor(context: Context, type: FileTypeEntry): Handler {
    val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://sample.provider/files/sample.${type.ext}"), type.mime)
    val found = context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        ?: return Handler.Nobody
    return when (found) {
        context.packageName -> Handler.Us
        "android" -> Handler.Nobody                      // the system's own "Open with" chooser
        else -> Handler.Other(found, runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(found, 0)).toString()
        }.getOrDefault(found))
    }
}

/**
 * Settings section for making this app the app that opens files handed over by other apps (a browser's downloads and so
 * on). Android doesn't let an app set itself as the default; the person has to pick it in the system "Open with" window
 * and choose Always. This shows where each type stands and opens that window for the types that are still free.
 */
@Composable
fun DefaultAppSection() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    // "Set all": the types still to do, and the one whose chooser is open right now.
    var queue by remember { mutableStateOf<List<FileTypeEntry>>(emptyList()) }
    var current by remember { mutableStateOf<FileTypeEntry?>(null) }
    fun openNext() {
        val next = queue.firstOrNull()
        current = next
        if (next != null) askSystemToChoose(context, next)
    }
    // Come back from the system chooser or Settings: refresh the status, and carry on down the "Set all" list.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refresh++
        val waiting = current
        if (waiting != null) {
            if (handlerFor(context, waiting) == Handler.Us) { queue = queue.drop(1); openNext() }   // chosen: next type
            else { queue = emptyList(); current = null }                                            // cancelled or "Just once": stop
        }
    }
    val statuses = remember(refresh) { GROUPS.flatMap { it.second }.associateWith { handlerFor(context, it) } }
    val ours = statuses.values.count { it == Handler.Us }
    val todo = statuses.filterValues { it == Handler.Nobody }.keys.toList()

    Text(
        "Android only lets you choose this yourself. For each type below, tap Set as default, pick Folder Explorer in " +
            "the window that appears, then choose Always.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Default for $ours of ${statuses.size} file types",
        style = MaterialTheme.typography.titleSmall
    )
    Spacer(Modifier.height(8.dp))
    if (current != null) {
        // A guided run is in progress.
        Text(
            "Setting up ${current?.label}: choose Folder Explorer, then Always (${queue.size} left). Press Back in the chooser to stop.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { queue = emptyList(); current = null }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
    } else if (todo.isNotEmpty()) {
        // Android needs one confirmation per type, so this opens each chooser in turn instead of making you hunt for them.
        Button(onClick = { queue = todo; openNext() }, modifier = Modifier.fillMaxWidth()) {
            Text("Set all ${todo.size} as default, one after another")
        }
        Spacer(Modifier.height(8.dp))
    }
    OutlinedButton(onClick = { openSystemDefaults(context) }, modifier = Modifier.fillMaxWidth()) {
        Text("Open Android's default-app settings")
    }

    for ((groupName, entries) in GROUPS) {
        Spacer(Modifier.height(12.dp))
        Text(groupName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        for (type in entries) {
            val handler = statuses.getValue(type)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(type.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when (handler) {
                            Handler.Us -> "Folder Explorer opens these"
                            Handler.Nobody -> "No default: Android asks each time"
                            is Handler.Other -> "Opens in ${handler.name}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when (handler) {
                    Handler.Us -> Icon(Icons.Default.Check, contentDescription = "Default", tint = MaterialTheme.colorScheme.primary)
                    Handler.Nobody -> Button(onClick = { askSystemToChoose(context, type) }) { Text("Set as default") }
                    is Handler.Other -> TextButton(onClick = { openAppDetails(context, handler.pkg) }) { Text("Change") }
                }
            }
        }
    }
    Spacer(Modifier.width(1.dp))
}

private fun openSystemDefaults(context: Context) {
    val tries = listOf(
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    )
    for (i in tries) {
        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

/** For a type another app owns: its App info page, where "Open by default" has Clear defaults. */
private fun openAppDetails(context: Context, pkg: String) {
    Toast.makeText(context, "Tap Open by default, then Clear defaults", Toast.LENGTH_LONG).show()
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Asks Android to open a small sample file of this type. With no default set, the system shows its "Open with" window
 * (Just once / Always); choosing this app and Always makes it the default for the type.
 */
private fun askSystemToChoose(context: Context, type: FileTypeEntry) {
    val sample = File(File(context.cacheDir, "default_samples").apply { mkdirs() }, "sample.${type.ext}")
    if (!sample.exists()) sample.writeText("Sample file used to set the default app.\n")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", sample)
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, type.mime)
        .putExtra(EXTRA_DEFAULT_SETUP, true)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // From an Activity no new task is needed; a new task is what made the app reload.
    if (context !is android.app.Activity && context !is android.content.ContextWrapper) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(view) }
        .onFailure { Toast.makeText(context, "Couldn't open the chooser for ${type.label}", Toast.LENGTH_LONG).show() }
}

/** Marks the sample intent above, so the app opens normally instead of opening the sample file. */
const val EXTRA_DEFAULT_SETUP = "afe_default_setup"
