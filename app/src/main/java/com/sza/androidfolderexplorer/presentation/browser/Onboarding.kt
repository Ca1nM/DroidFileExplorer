package com.sza.androidfolderexplorer.presentation.browser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch

private data class Tip(val icon: ImageVector, val title: String, val body: String)

private val Tips = listOf(
    Tip(Icons.Default.FolderOpen, "Browse everything", "Tap a folder to open it and a file to view it. Photos, videos, PDFs, text, JSON and archives open right here. Long-press anything to start selecting."),
    Tip(Icons.Default.Checklist, "Act on many files", "With files selected, Copy, Move and Delete work in bulk. \"More\" adds rename, zip, share, tags, checksums, compare and more. Deleted items go to the trash and can be restored."),
    Tip(Icons.Default.Search, "Find things fast", "Use the ⋮ menu to search this folder or everything below it. The chips under the toolbar filter by date, size, stars and tags. Drag the scroll bar for a quick jump."),
    Tip(Icons.Default.Build, "Tools and storage", "Tools has a storage overview, duplicate finder, cleanup, network and cloud storage, a Wi-Fi share link, an encrypted vault and scheduled sync."),
    Tip(Icons.Default.Keyboard, "On a big screen", "Unfolded or on a desktop display the browser can show two folders side by side. Keyboard shortcuts and right-click menus work too.")
)

/** Short first-launch tour. Can be shown again from Settings. */
@Composable
fun OnboardingDialog(onDone: () -> Unit) {
    val pager = rememberPagerState { Tips.size }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                HorizontalPager(pager, Modifier.weight(1f)) { page ->
                    val t = Tips[page]
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(t.icon, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(24.dp))
                        Text(t.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(t.body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    }
                }
                Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(Tips.size) { i ->
                        Box(Modifier.size(8.dp).background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDone) { Text("Skip") }
                    Button(onClick = {
                        if (pager.currentPage == Tips.lastIndex) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }) { Text(if (pager.currentPage == Tips.lastIndex) "Get started" else "Next") }
                }
            }
        }
    }
}
