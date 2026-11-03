package com.ca1nm.droidfileexplorer.presentation.browser

import android.graphics.Typeface
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ca1nm.droidfileexplorer.data.docs.DocBlock
import com.ca1nm.droidfileexplorer.data.docs.DocSection
import com.ca1nm.droidfileexplorer.data.docs.DocumentParser
import com.ca1nm.droidfileexplorer.data.docs.OfficeHtml
import com.ca1nm.droidfileexplorer.data.docs.ParsedDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads office documents, spreadsheets, slides, e-books, rich text, databases and fonts without another app: the text,
 * tables and sheets are shown (see [DocumentParser]); pictures, charts and page layout aren't. "Open in…" hands the file
 * to another app for the full-fidelity version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocumentViewerDialog(path: String, vm: FileBrowserViewModel, onClose: () -> Unit) {
    val file = File(path)
    var doc by remember(path) { mutableStateOf<ParsedDocument?>(null) }
    var pages by remember(path) { mutableStateOf<OfficeHtml.Result?>(null) }
    var pageIndex by remember(path) { mutableIntStateOf(0) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    val cache = androidx.compose.ui.platform.LocalContext.current.cacheDir
    LaunchedEffect(path) {
        try {
            val ext = file.extension.lowercase()
            val rich = ext in setOf("docx", "docm", "dotx", "dotm", "pptx", "pptm", "potx", "ppsx", "epub")
            if (rich) {
                pages = withContext(Dispatchers.IO) {
                    val dir = File(cache, "docview").also { it.deleteRecursively() }
                    when (ext) {
                        "epub" -> OfficeHtml.epub(file, dir)
                        "docx", "docm", "dotx", "dotm" -> OfficeHtml.docx(file, dir)
                        else -> OfficeHtml.pptx(file, dir)
                    }
                }
            } else
            doc = withContext(Dispatchers.IO) { DocumentParser.parse(file) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: "Can't read this file"
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") } },
                        actions = {
                            IconButton(onClick = { vm.openExternally(path, chooser = true) }) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in another app")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    val d = doc
                    val pg = pages
                    when {
                        pg != null && error == null -> Column(Modifier.fillMaxSize()) {
                            if (pg.pages.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.TextButton(onClick = { if (pageIndex > 0) pageIndex-- }, enabled = pageIndex > 0) { Text("Previous") }
                                Text("${pageIndex + 1} / ${pg.pages.size}", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                androidx.compose.material3.TextButton(onClick = { if (pageIndex < pg.pages.lastIndex) pageIndex++ }, enabled = pageIndex < pg.pages.lastIndex) { Text("Next") }
                            }
                            androidx.compose.ui.viewinterop.AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    android.webkit.WebView(ctx).apply {
                                        settings.javaScriptEnabled = false
                                        settings.allowFileAccess = true
                                        settings.allowContentAccess = false
                                        @Suppress("DEPRECATION")
                                        settings.allowFileAccessFromFileURLs = true   // lets the page load the bundled fonts; scripts and network stay off
                                        settings.builtInZoomControls = true
                                        settings.displayZoomControls = false
                                        // Word pages and slides declare their own width (fit to screen); an e-book's pages don't,
                                        // so lay them out at the phone's width or their text comes out tiny.
                                        val isBook = file.extension.equals("epub", ignoreCase = true)
                                        settings.useWideViewPort = !isBook
                                        settings.loadWithOverviewMode = !isBook
                                        if (isBook) settings.textZoom = 115
                                        settings.blockNetworkLoads = true
                                        webViewClient = object : android.webkit.WebViewClient() {
                                            override fun shouldOverrideUrlLoading(v: android.webkit.WebView?, r: android.webkit.WebResourceRequest?) = r?.url?.scheme != "file"
                                        }
                                    }
                                },
                                update = { w ->
                                    val u = "file://" + pg.pages[pageIndex.coerceIn(0, pg.pages.lastIndex)].absolutePath
                                    if (w.tag != u) { w.tag = u; w.loadUrl(u) }
                                },
                                onRelease = { it.destroy() }
                            )
                        }
                        error != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(error!!, color = MaterialTheme.colorScheme.error)
                            Button(onClick = { vm.openExternally(path, chooser = true) }) { Text("Open in another app") }
                        }
                        d == null && error == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        d != null -> DocumentBody(d)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentBody(doc: ParsedDocument) {
    var selected by remember(doc) { mutableIntStateOf(0) }
    val family = remember(doc.fontPath) { doc.fontPath?.let { runCatching { FontFamily(Typeface.createFromFile(it)) }.getOrNull() } }
    Column(Modifier.fillMaxSize()) {
        doc.note?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (doc.sections.size > 1) {
            ScrollableTabRow(selectedTabIndex = selected.coerceIn(0, doc.sections.lastIndex), edgePadding = 8.dp) {
                doc.sections.forEachIndexed { i, s ->
                    Tab(selected = i == selected, onClick = { selected = i }, text = { Text(s.name ?: "Page ${i + 1}", maxLines = 1) })
                }
            }
        }
        SectionView(doc.sections[selected.coerceIn(0, doc.sections.lastIndex)], family)
    }
}

private val CellWidth = 132.dp

@Composable
private fun SectionView(section: DocSection, family: FontFamily?) {
    if (section.blocks.isEmpty()) {
        Text("Nothing to show here.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val columns = remember(section) { section.blocks.maxOf { if (it is DocBlock.Row) it.cells.size else 0 } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = maxWidth
        val tableWidth = CellWidth * columns
        val content = if (tableWidth > screen) tableWidth else screen
        Box(Modifier.fillMaxSize().then(if (tableWidth > screen) Modifier.horizontalScroll(rememberScrollState()) else Modifier)) {
            SelectionContainer {
                LazyColumn(Modifier.width(content), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                    items(section.blocks) { block ->
                        when (block) {
                            is DocBlock.Heading -> Text(
                                block.text,
                                Modifier.widthIn(max = screen).padding(horizontal = 16.dp, vertical = 8.dp),
                                style = when (block.level) {
                                    1 -> MaterialTheme.typography.headlineMedium
                                    2 -> MaterialTheme.typography.titleLarge
                                    else -> MaterialTheme.typography.titleMedium
                                },
                                fontFamily = family,
                                fontWeight = FontWeight.SemiBold
                            )
                            is DocBlock.Para -> Text(
                                block.text,
                                Modifier.widthIn(max = screen).padding(horizontal = 16.dp, vertical = 4.dp),
                                style = if (family != null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium,
                                fontFamily = family
                            )
                            is DocBlock.Row -> Row(Modifier.padding(horizontal = 8.dp)) {
                                block.cells.forEach { cell ->
                                    Text(
                                        cell,
                                        Modifier.width(CellWidth).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).padding(6.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (block.header) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 6,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
