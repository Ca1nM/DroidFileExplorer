package com.ca1nm.droidfileexplorer.presentation.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import com.ca1nm.droidfileexplorer.data.FileTypes
import com.ca1nm.droidfileexplorer.data.formatSize
import com.ca1nm.droidfileexplorer.data.source.FileOps
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.xmlpull.v1.XmlPullParser

private const val MAX_VIEW_BYTES = 4 * 1024 * 1024

private fun readLimited(vm: FileBrowserViewModel, path: String, max: Int = MAX_VIEW_BYTES): ByteArray =
    vm.fileOps.openInput(path).use { s ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (out.size() < max) {
            val n = s.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

/** Full-screen read-only viewer for one file; the layout depends on [kind]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentViewerDialog(path: String, kind: FileKindHint, vm: FileBrowserViewModel, onClose: () -> Unit) {
    var rawToggle by remember { mutableStateOf(false) }
    // A temporary copy (a file taken out of an archive) can't be edited: the changes would be lost with the copy.
    val temporary = path.startsWith(androidx.compose.ui.platform.LocalContext.current.cacheDir.path)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(
                    title = { Text(File(path).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") } },
                    actions = {
                        if (kind == FileKindHint.MARKDOWN) {
                            TextButton(onClick = { rawToggle = !rawToggle }) { Text(if (rawToggle) "Rendered" else "Raw") }
                        }
                        if (!temporary && kind in listOf(FileKindHint.MARKDOWN, FileKindHint.JSON, FileKindHint.XML, FileKindHint.CSV, FileKindHint.LOG)) {
                            IconButton(onClick = { onClose(); vm.openEditor(path) }) { Icon(Icons.Default.Edit, "Edit as text") }
                        }
                    }
                )
            }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (kind) {
                        FileKindHint.MARKDOWN -> TextLoader(vm, path) { if (rawToggle) RawText(it, java.io.File(path).name) else MarkdownView(it) }
                        FileKindHint.JSON -> TextLoader(vm, path) { JsonView(it) }
                        FileKindHint.XML -> TextLoader(vm, path) { XmlView(it) }
                        FileKindHint.CSV -> TextLoader(vm, path) { CsvView(it) }
                        FileKindHint.LOG -> LogView(path, vm)
                        FileKindHint.TEXT -> TextLoader(vm, path) { RawText(it, java.io.File(path).name) }
                        FileKindHint.HEX -> HexView(path, vm)
                        FileKindHint.PDF -> PdfView(path)
                        FileKindHint.EXIF -> ExifView(path, vm)
                        FileKindHint.AUDIO_TAGS -> AudioTagsView(path)
                    }
                }
            }
        }
    }
}

@Composable
private fun TextLoader(vm: FileBrowserViewModel, path: String, content: @Composable (String) -> Unit) {
    var text by remember(path) { mutableStateOf<String?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        try {
            text = withContext(Dispatchers.IO) {
                val bytes = readLimited(vm, path)
                require(bytes.take(8192).none { it == 0.toByte() }) { "This looks like a binary file. Try Hex dump." }
                String(bytes, Charsets.UTF_8) + if (bytes.size >= MAX_VIEW_BYTES) "\n\n… (truncated at 4 MB)" else ""
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Can't read this file" }
    }
    when {
        error != null -> Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        text == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> content(text!!)
    }
}

@Composable
private fun RawText(text: String, fileName: String? = null) {
    // Native text widget: a single Compose Text of a few megabytes freezes the app, this stays responsive.
    // A very long line can't scroll smoothly on one row, so those files are shown wrapped.
    val wrap = remember(text) { com.ca1nm.droidfileexplorer.data.TextFiles.longestLine(text) > com.ca1nm.droidfileexplorer.data.TextFiles.LONG_LINE_CHARS }
    NativeTextArea(text = text, editable = false, wrap = wrap, modifier = Modifier.fillMaxSize(), fileName = fileName)
}

// ---- Markdown ----

private val InlineRe = Regex("""(\*\*[^*]+\*\*)|(__[^_]+__)|(\*[^*\n]+\*)|(`[^`\n]+`)|(\[[^\]]+]\([^)]+\))""")

private fun inline(line: String, code: Color, link: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in InlineRe.findAll(line)) {
        append(line.substring(last, m.range.first))
        val t = m.value
        when {
            t.startsWith("**") || t.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.drop(2).dropLast(2)) }
            t.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = code)) { append(t.drop(1).dropLast(1)) }
            t.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.drop(1).dropLast(1)) }
            else -> withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) { append(t.substringAfter('[').substringBefore(']')) }
        }
        last = m.range.last + 1
    }
    append(line.substring(last))
}

@Composable
private fun MarkdownView(text: String) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val link = MaterialTheme.colorScheme.primary
    val lines = remember(text) { text.lines() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var inCode = false
        val rendered = lines.map { line ->
            if (line.trimStart().startsWith("```")) { inCode = !inCode; null to "fence" }
            else if (inCode) line to "code" else line to "text"
        }
        itemsIndexed(rendered) { _, (line, mode) ->
            if (line == null) return@itemsIndexed
            if (mode == "code") {
                Text(line.ifEmpty { " " }, Modifier.fillMaxWidth().background(codeBg).padding(horizontal = 8.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                return@itemsIndexed
            }
            val t = line.trimEnd()
            when {
                t.isBlank() -> Spacer(Modifier.height(6.dp))
                t.startsWith("#") -> {
                    val level = t.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    Text(inline(t.drop(level).trim(), codeBg, link), fontWeight = FontWeight.Bold,
                        fontSize = (26 - level * 3).sp, modifier = Modifier.padding(top = 8.dp))
                }
                t.startsWith("> ") -> Text(inline(t.drop(2), codeBg, link), fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                t.trimStart().startsWith("- ") || t.trimStart().startsWith("* ") ->
                    Text(buildAnnotatedString { append("•  "); append(inline(t.trimStart().drop(2), codeBg, link)) },
                        modifier = Modifier.padding(start = (12 + (t.length - t.trimStart().length) * 4).dp))
                t == "---" || t == "***" -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                else -> Text(inline(t, codeBg, link))
            }
        }
    }
}

// ---- Tree (JSON / XML) ----

private class TreeNode(val label: String, val value: String? = null, val children: List<TreeNode> = emptyList())

private fun jsonNode(key: String?, v: Any?): TreeNode {
    val prefix = if (key != null) "$key: " else ""
    return when (v) {
        is JSONObject -> TreeNode("$prefix{${v.length()}}", children = v.keys().asSequence().take(500).map { jsonNode(it, v.opt(it)) }.toList())
        is JSONArray -> TreeNode("$prefix[${v.length()}]", children = (0 until minOf(v.length(), 500)).map { jsonNode("$it", v.opt(it)) })
        JSONObject.NULL, null -> TreeNode("${prefix}null")
        is String -> TreeNode("$prefix\"$v\"")
        else -> TreeNode("$prefix$v")
    }
}

@Composable
private fun TreeRow(node: TreeNode, depth: Int, expandedDepth: Int) {
    var open by remember { mutableStateOf(depth < expandedDepth) }
    val hasKids = node.children.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().clickable(enabled = hasKids) { open = !open }.padding(start = (depth * 14).dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(if (hasKids) (if (open) "▼ " else "▶ ") else "   ", fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary)
        Text(node.label, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
    if (open) node.children.forEach { TreeRow(it, depth + 1, expandedDepth) }
}

@Composable
private fun JsonView(text: String) {
    val root = remember(text) {
        runCatching { jsonNode(null, JSONTokener(text).nextValue()) }
    }
    root.fold(
        onSuccess = { SelectionContainer { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) { TreeRow(it, 0, 2) } } },
        onFailure = { Column(Modifier.padding(16.dp)) { Text("Not valid JSON: ${it.message}", color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(8.dp)); RawText(text) } }
    )
}

private fun parseXml(text: String): TreeNode {
    val p = android.util.Xml.newPullParser()
    p.setInput(java.io.StringReader(text))
    class Open(val name: String, val attrs: String, val kids: MutableList<TreeNode> = mutableListOf(), val sb: StringBuilder = StringBuilder())
    val stack = ArrayDeque<Open>()
    var root: TreeNode? = null
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        when (ev) {
            XmlPullParser.START_TAG -> {
                val attrs = (0 until p.attributeCount).joinToString(" ") { """${p.getAttributeName(it)}="${p.getAttributeValue(it)}"""" }
                stack.addLast(Open(p.name, attrs))
            }
            XmlPullParser.TEXT -> stack.lastOrNull()?.sb?.append(p.text)
            XmlPullParser.END_TAG -> {
                val o = stack.removeLast()
                val txt = o.sb.toString().trim()
                val label = "<${o.name}${if (o.attrs.isNotEmpty()) " " + o.attrs else ""}>" + if (txt.isNotEmpty() && o.kids.isEmpty()) "  $txt" else ""
                val node = TreeNode(label, children = o.kids.take(500))
                if (stack.isEmpty()) root = node else stack.last().kids += node
            }
        }
        ev = p.next()
    }
    return root ?: throw IllegalArgumentException("No XML elements found")
}

@Composable
private fun XmlView(text: String) {
    val root = remember(text) { runCatching { parseXml(text) } }
    root.fold(
        onSuccess = { SelectionContainer { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) { TreeRow(it, 0, 3) } } },
        onFailure = { Column(Modifier.padding(16.dp)) { Text("Not valid XML: ${it.message}", color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(8.dp)); RawText(text) } }
    )
}

// ---- CSV ----

private fun parseCsv(text: String, delim: Char): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
    while (i < text.length && rows.size < 2000) {
        val c = text[i]
        when {
            quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
            c == '"' -> quoted = !quoted
            !quoted && c == delim -> { row.add(cell.toString()); cell.clear() }
            !quoted && (c == '\n' || c == '\r') -> {
                if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                row.add(cell.toString()); cell.clear(); rows.add(row); row = mutableListOf()
            }
            else -> cell.append(c)
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
    return rows
}

@Composable
private fun CsvView(text: String) {
    val rows = remember(text) {
        val first = text.lineSequence().firstOrNull().orEmpty()
        val delim = listOf(',', '\t', ';', '|').maxByOrNull { d -> first.count { it == d } } ?: ','
        parseCsv(text, delim)
    }
    val cols = rows.maxOfOrNull { it.size } ?: 0
    Column(Modifier.fillMaxSize()) {
        Text("${rows.size} rows × $cols columns" + if (rows.size >= 2000) " (first 2000 shown)" else "",
            Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            LazyColumn {
                itemsIndexed(rows) { i, r ->
                    Row(Modifier.background(if (i == 0) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) {
                        for (c in 0 until cols) {
                            Text(r.getOrElse(c) { "" }, Modifier.width(140.dp).padding(horizontal = 6.dp, vertical = 4.dp),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                                fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}

// ---- Live log ----

@Composable
private fun LogView(path: String, vm: FileBrowserViewModel) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var follow by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(path) {
        var offset = 0L
        while (true) {
            val f = File(path)
            val len = f.length()
            if (len < offset) { offset = 0; lines = emptyList() } // rotated or truncated
            if (len > offset) {
                val chunk = withContext(Dispatchers.IO) {
                    runCatching {
                        java.io.RandomAccessFile(f, "r").use { r ->
                            // First load shows the last 256 KB only.
                            if (offset == 0L && len > 256 * 1024) offset = len - 256 * 1024
                            r.seek(offset); val b = ByteArray((len - offset).toInt().coerceAtMost(1 shl 20)); r.readFully(b)
                            offset += b.size; String(b)
                        }
                    }.getOrDefault("")
                }
                lines = (lines + chunk.lines().filter { it.isNotEmpty() }).takeLast(5000)
            }
            delay(1000)
        }
    }
    val shown = remember(lines, filter) { if (filter.isBlank()) lines else lines.filter { it.contains(filter, ignoreCase = true) } }
    LaunchedEffect(shown.size, follow) { if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(filter, { filter = it }, label = { Text("Filter / highlight") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            FilterChip(follow, { follow = !follow }, label = { Text("Follow") })
        }
        val hi = MaterialTheme.colorScheme.tertiaryContainer
        LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), state = listState) {
            items(shown.size) { i ->
                val line = shown[i]
                val text = if (filter.isBlank()) AnnotatedString(line) else buildAnnotatedString {
                    var last = 0
                    while (true) {
                        val at = line.indexOf(filter, last, ignoreCase = true)
                        if (at < 0) break
                        append(line.substring(last, at))
                        withStyle(SpanStyle(background = hi, fontWeight = FontWeight.Bold)) { append(line.substring(at, at + filter.length)) }
                        last = at + filter.length
                    }
                    append(line.substring(last))
                }
                val tone = when {
                    line.contains("error", true) || line.contains(" E/", false) || line.contains("fatal", true) -> MaterialTheme.colorScheme.error
                    line.contains("warn", true) || line.contains(" W/", false) -> Color(0xFFB26A00)
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(text, Modifier.padding(horizontal = 8.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = tone, softWrap = false)
            }
        }
    }
}

// ---- Hex ----

@Composable
private fun HexView(path: String, vm: FileBrowserViewModel) {
    var bytes by remember(path) { mutableStateOf<ByteArray?>(null) }
    var limit by remember(path) { mutableIntStateOf(64 * 1024) }
    LaunchedEffect(path, limit) { bytes = withContext(Dispatchers.IO) { runCatching { readLimited(vm, path, limit) }.getOrDefault(ByteArray(0)) } }
    val b = bytes
    if (b == null) { CircularProgressIndicator(Modifier.padding(24.dp)); return }
    val total = File(path).length()
    LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), contentPadding = PaddingValues(8.dp)) {
        items((b.size + 15) / 16) { row ->
            val off = row * 16
            val slice = b.copyOfRange(off, minOf(off + 16, b.size))
            val hex = slice.joinToString(" ") { "%02x".format(it) }.padEnd(47)
            val ascii = slice.map { if (it in 32..126) it.toInt().toChar() else '.' }.joinToString("")
            Text("%08x  %s  %s".format(off, hex, ascii), fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
        }
        if (b.size < total) item {
            TextButton(onClick = { limit = minOf(limit * 4L, total).toInt().coerceAtMost(16 * 1024 * 1024) }) {
                Text("Showing ${formatSize(b.size.toLong())} of ${formatSize(total)} — load more")
            }
        }
    }
}

// ---- PDF ----

private val PdfLock = Mutex()

@Composable
private fun PdfView(path: String) {
    var renderer by remember(path) { mutableStateOf<PdfRenderer?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    DisposableEffect(path) {
        val pfd = runCatching { ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
        val r = pfd?.let { runCatching { PdfRenderer(it) }.getOrElse { e -> error = e.message ?: "Can't open this PDF"; null } }
        if (pfd == null) error = "Can't open this file"
        renderer = r
        onDispose { r?.close(); pfd?.close() }
    }
    val r = renderer
    when {
        error != null -> Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        r == null -> CircularProgressIndicator(Modifier.padding(24.dp))
        else -> LazyColumn(Modifier.fillMaxSize().background(Color(0xFF444444)), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(8.dp)) {
            items(r.pageCount) { index ->
                val bmp by produceState<Bitmap?>(null, index) {
                    value = PdfLock.withLock {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                r.openPage(index).use { page ->
                                    val w = 1400; val h = (w.toFloat() * page.height / page.width).toInt()
                                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                                        it.eraseColor(AColor.WHITE)
                                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }.getOrNull()
                        }
                    }
                }
                val shown = bmp
                if (shown != null) Image(shown.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                else Box(Modifier.fillMaxWidth().aspectRatio(0.7f).background(Color.White), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

// ---- EXIF ----

private val ExifTags = listOf(
    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_IMAGE_WIDTH,
    ExifInterface.TAG_IMAGE_LENGTH, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH,
    ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_WHITE_BALANCE
)
private val GpsTags = listOf(
    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD
)

@Composable
private fun ExifView(path: String, vm: FileBrowserViewModel) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val data = remember(path) {
        runCatching {
            val e = ExifInterface(path)
            val tags = ExifTags.mapNotNull { t -> e.getAttribute(t)?.let { t to it } }
            val latLong = e.latLong
            Triple(tags, latLong, GpsTags.any { e.getAttribute(it) != null })
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        data.fold(onSuccess = { (tags, loc, hasGps) ->
            if (tags.isEmpty() && !hasGps) Text("No EXIF data in this file.")
            tags.forEach { (k, v) ->
                Column { Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v) }
            }
            if (hasGps) {
                Column {
                    Text("GPS location", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Text(loc?.let { "%.5f, %.5f".format(it[0], it[1]) } ?: "present")
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            fun stripped(all: Boolean) {
                scope.launch {
                    status = try {
                        withContext(Dispatchers.IO) {
                            val src = File(path)
                            val out = FileOps.uniqueName(src.parentFile!!, src.nameWithoutExtension + (if (all) "-clean." else "-nogps.") + src.extension)
                            src.copyTo(out)
                            val e = ExifInterface(out.absolutePath)
                            (if (all) ExifTags + GpsTags else GpsTags).forEach { e.setAttribute(it, null) }
                            e.saveAttributes()
                            "Saved a copy without ${if (all) "metadata" else "location"}: ${out.name}"
                        }.also { vm.navigateTo(vm.uiState.value.currentPath) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { "Couldn't strip: ${e.message}" }
                }
            }
            Button(onClick = { stripped(false) }, enabled = hasGps) { Text("Save copy without location") }
            OutlinedButton(onClick = { stripped(true) }, enabled = tags.isNotEmpty() || hasGps) { Text("Save copy without any metadata") }
            Text("The original is kept. Share the copy.", style = MaterialTheme.typography.bodySmall)
        }, onFailure = { Text("Can't read EXIF: ${it.message}", color = MaterialTheme.colorScheme.error) })
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}

// ---- Audio tags ----

@Composable
private fun AudioTagsView(path: String) {
    val info = remember(path) {
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(path)
                val art = r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                val rows = listOf(
                    "Title" to MediaMetadataRetriever.METADATA_KEY_TITLE, "Artist" to MediaMetadataRetriever.METADATA_KEY_ARTIST,
                    "Album" to MediaMetadataRetriever.METADATA_KEY_ALBUM, "Album artist" to MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
                    "Year" to MediaMetadataRetriever.METADATA_KEY_YEAR, "Genre" to MediaMetadataRetriever.METADATA_KEY_GENRE,
                    "Track" to MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER, "Bitrate" to MediaMetadataRetriever.METADATA_KEY_BITRATE,
                    "Duration" to MediaMetadataRetriever.METADATA_KEY_DURATION
                ).mapNotNull { (label, key) ->
                    r.extractMetadata(key)?.let { v ->
                        label to when (label) {
                            "Duration" -> v.toLongOrNull()?.let { "%d:%02d".format(it / 60000, it / 1000 % 60) } ?: v
                            "Bitrate" -> v.toLongOrNull()?.let { "${it / 1000} kbps" } ?: v
                            else -> v
                        }
                    }
                }
                art to rows
            } finally { r.release() }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        info.fold(onSuccess = { (art, rows) ->
            art?.let { Image(it.asImageBitmap(), "Cover art", Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit) }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var ringStatus by remember { mutableStateOf<String?>(null) }
            OutlinedButton(onClick = {
                if (!android.provider.Settings.System.canWrite(ctx)) {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    ringStatus = "Allow \"Modify system settings\" for this app, then try again."
                } else {
                    runCatching {
                        val dir = File(android.os.Environment.getExternalStorageDirectory(), "Ringtones").apply { mkdirs() }
                        val copy = FileOps.uniqueName(dir, File(path).name)
                        File(path).copyTo(copy)
                        android.media.MediaScannerConnection.scanFile(ctx, arrayOf(copy.absolutePath), null) { _, uri ->
                            if (uri != null) android.media.RingtoneManager.setActualDefaultRingtoneUri(ctx, android.media.RingtoneManager.TYPE_RINGTONE, uri)
                        }
                        ringStatus = "Ringtone set (copied to Ringtones/${copy.name})"
                    }.onFailure { ringStatus = "Couldn't set ringtone: ${it.message}" }
                }
            }) { Text("Set as ringtone") }
            ringStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (rows.isEmpty()) Text("No tags found in this file.")
            rows.forEach { (k, v) -> Column { Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v) } }
        }, onFailure = { Text("Can't read tags: ${it.message}", color = MaterialTheme.colorScheme.error) })
    }
}
