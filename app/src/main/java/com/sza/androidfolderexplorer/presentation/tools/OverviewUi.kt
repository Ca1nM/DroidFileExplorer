package com.sza.androidfolderexplorer.presentation.tools

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sza.androidfolderexplorer.data.formatSize
import com.sza.androidfolderexplorer.data.ops.OpenRequests

private const val STORAGE_ROOT = "/storage/emulated/0"

private data class Seg(val label: String, val bytes: Long, val color: Color, val note: String, val onClick: (() -> Unit)? = null)

private val FilesColor = Color(0xFF1E88E5)
private val CodeColor = Color(0xFF43A047)
private val DataColor = Color(0xFFFB8C00)
private val SystemColor = Color(0xFF8E24AA)
private val HiddenColor = Color(0xFF8E24AA)
private val CategoryColors = listOf(0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA, 0xFFE53935, 0xFF00ACC1, 0xFF6D4C41).map { Color(it) }

/**
 * Storage overview whose figures add up: the used space is split into your files, installed apps
 * (code), app data and cache, and what is left over (the system and anything else). Without
 * usage access, apps and the system can't be told apart, so they are shown as one honest block.
 */
@Composable
internal fun StorageOverviewPage(vm: ToolsViewModel, onReveal: () -> Unit) {
    val o = vm.overview
    if (o == null) { if (!vm.busy) Text("No data yet.", Modifier.padding(16.dp)); return }
    val context = LocalContext.current
    val apps = vm.appUsage.orEmpty()
    val haveApps = vm.usageAccess && apps.isNotEmpty()

    val used = o.usedBytes.coerceAtLeast(1)
    val files = o.totalBytes
    val code = apps.sumOf { it.codeBytes }
    val data = apps.sumOf { it.dataBytes }
    val cache = apps.sumOf { it.cacheBytes }

    var showSystemInfo by remember { mutableStateOf(false) }
    val segments: List<Seg> = if (haveApps) {
        val remainder = used - files - code - data
        listOf(
            Seg("Your files", files, FilesColor, "Photos, videos, downloads and everything else you can browse. Tap a type below."),
            Seg("Apps (installed code)", code, CodeColor, "${apps.count { it.codeBytes > 0 }} apps with code on this phone. Tap to see them.",
                onClick = { vm.openApps(AppSort.CODE) }),
            Seg("App data", data, DataColor, "Includes ${formatSize(cache)} of cache. Their Android/data folders too. Tap to see which apps.",
                onClick = { vm.openApps(AppSort.DATA) }),
            Seg("System & other", remainder.coerceAtLeast(0), SystemColor,
                "Android itself, system caches, other users and anything Android doesn't attribute to an app. Tap for details.",
                onClick = { showSystemInfo = true })
        )
    } else {
        listOf(
            Seg("Your files", files, FilesColor, "What this app can browse: ${o.files} files"),
            Seg("Apps, app data & system", (used - files).coerceAtLeast(0), HiddenColor,
                "Installed apps and their data are not visible to a file manager. Allow usage access below to break this down.")
        )
    }

    if (showSystemInfo) SystemInfoDialog(segments.last().bytes) { showSystemInfo = false }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val ago = if (vm.overviewScannedAt > 0)
                android.text.format.DateUtils.getRelativeTimeSpanString(vm.overviewScannedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
            else "never"
            Text("Files last scanned $ago" + if (vm.overviewReused > 0) " · ${vm.overviewReused} unchanged folders reused" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.scanOverview() }, enabled = !vm.busy) { Text("Refresh") }
                OutlinedButton(onClick = { vm.scanOverview(full = true) }, enabled = !vm.busy) { Text("Full rescan") }
            }
        }

        // Headline and bar: the bar is the USED space, so it fills up and the parts can be compared.
        item {
            Text("${formatSize(o.usedBytes)} used", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("${formatSize(o.freeBytes)} free of ${formatSize(o.usedBytes + o.freeBytes)} total", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                segments.filter { it.bytes > 0 }.forEach { s ->
                    Box(Modifier.weight((s.bytes.toFloat() / used).coerceAtLeast(0.004f)).fillMaxHeight().background(s.color))
                }
            }
        }

        items(segments.size) { i ->
            val s = segments[i]
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .then(if (s.onClick != null) Modifier.clickable { s.onClick.invoke() } else Modifier)
                    .padding(vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(s.color))
                    Spacer(Modifier.width(10.dp))
                    Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(formatSize(s.bytes), fontWeight = FontWeight.SemiBold)
                    Text("  ${percent(s.bytes, used)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(s.note, Modifier.padding(start = 22.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            HorizontalDivider()
            Row {
                Text("Total used", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(formatSize(segments.sumOf { it.bytes }), fontWeight = FontWeight.SemiBold)
            }
            if (haveApps && files + code + data > used) {
                Text("Apps and files overlap a little in Android's own accounting, so the parts can exceed the total slightly.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Ask for usage access when it is missing.
        if (!haveApps) item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Where does the rest go?", fontWeight = FontWeight.SemiBold)
                    Text("Most of the space is usually apps and their data. Android only shares per-app sizes with apps that have usage access.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.shizukuReady) Button(onClick = { vm.grantUsageAccess() }) { Text("Allow with Shizuku") }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("Open settings") }
                    }
                    Text("After allowing it in Settings, come back and tap Refresh.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Your files by type.
        item { Text("Your files", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        val cats = o.byCategory.entries.sortedByDescending { it.value }
        items(cats.size) { i ->
            val (name, bytes) = cats[i].let { it.key to it.value }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.openCategory(name) }.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(CategoryColors[i % CategoryColors.size]))
                    Spacer(Modifier.width(10.dp))
                    Text(if (name == "Other") "Other file types" else name, Modifier.weight(1f))
                    Text(formatSize(bytes))
                    Text("  ›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (name == "Other" && o.otherExts.isNotEmpty()) {
                    val top = o.otherExts.entries.sortedByDescending { it.value }.take(5)
                        .joinToString("  ·  ") { (ext, b) -> (if (ext.isEmpty()) "no extension" else ".$ext") + " " + formatSize(b) }
                    Text(top, Modifier.padding(start = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Biggest apps.
        if (haveApps) {
            item {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Biggest apps", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { vm.openApps(AppSort.TOTAL) }) { Text("See all ${apps.size}") }
                }
            }
            item {
                Text("Tap an app to open its Android settings, where you can clear its cache or data, or uninstall it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(apps.take(30), key = { it.pkg }) { a ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                if (a.codeBytes > 0) append("App ${formatSize(a.codeBytes)}  ·  ")
                                append("Data ${formatSize(a.dataBytes)}")
                                if (a.cacheBytes > 0) append(" (cache ${formatSize(a.cacheBytes)})")
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(formatSize(a.totalBytes), style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        item { Text("Largest files", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        items(o.largest.take(20), key = { it.path }) { f ->
            Row(Modifier.fillMaxWidth().clickable { OpenRequests.request(f.path.substringBeforeLast('/')); onReveal() }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(f.path.removePrefix(STORAGE_ROOT).substringBeforeLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(formatSize(f.size), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SystemInfoDialog(bytes: Long, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("System & other · ${formatSize(bytes)}") },
        text = {
            Text(
                "This is what is left after your files and the apps Android can account for. It is mostly Android itself " +
                    "and its caches, accounts, system databases and anything belonging to other users (such as a Secure Folder).\n\n" +
                    "A file manager can't safely delete it. To shrink it, restart the phone, clear unused apps' caches, " +
                    "or reset an app's data from its Android settings page."
            )
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

private fun percent(part: Long, whole: Long): String {
    val p = part * 100.0 / whole.coerceAtLeast(1)
    return if (p < 0.1 && part > 0) "<0.1%" else "%.1f%%".format(p)
}
