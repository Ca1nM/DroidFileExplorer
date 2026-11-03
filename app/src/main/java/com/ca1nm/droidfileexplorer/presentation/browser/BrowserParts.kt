package com.ca1nm.droidfileexplorer.presentation.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.ca1nm.droidfileexplorer.data.FileKind
import com.ca1nm.droidfileexplorer.data.FileTypes
import com.ca1nm.droidfileexplorer.data.formatSize
import com.ca1nm.droidfileexplorer.domain.model.FileItem
import androidx.compose.foundation.border
import com.ca1nm.droidfileexplorer.data.settings.ThumbOptions
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ---- Thumbnails ----

/** Decodes small previews for images, videos and APK icons, with a memory cache and at most 3 decodes at once. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4096)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val gate = Semaphore(3)

    fun supports(name: String) = FileTypes.kindOf(name).let { it == FileKind.IMAGE || it == FileKind.VIDEO || it == FileKind.APK }

    /** Formats that can hold an animation; everything else is shown as a still. */
    fun mayAnimate(name: String) = FileTypes.ext(name).trimEnd('_') in setOf("gif", "webp", "avif")

    /**
     * The file as a playing [android.graphics.drawable.AnimatedImageDrawable] scaled down to about [px], or null when it
     * isn't animated (or is too big to animate in a list), in which case the still thumbnail is used.
     */
    suspend fun loadAnimated(item: FileItem, px: Int, maxMb: Int): android.graphics.drawable.Drawable? = gate.withPermit {
        withContext(Dispatchers.IO) {
            runCatching {
                // maxMb 0 = "No limit" in Settings.
                if (maxMb > 0 && File(item.path).length() > maxMb.toLong() * 1024 * 1024) return@runCatching null
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(item.path))) { dec, info, _ ->
                    val big = maxOf(info.size.width, info.size.height)
                    if (big > px) {
                        val f = px.toFloat() / big
                        dec.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
                    }
                }.takeIf { it is android.graphics.drawable.AnimatedImageDrawable }
            }.getOrNull()
        }
    }

    suspend fun load(context: Context, item: FileItem, px: Int): Bitmap? {
        val key = "${item.path}|${item.lastModified}|$px"
        cache.get(key)?.let { return it }
        val bmp = gate.withPermit { withContext(Dispatchers.IO) { runCatching { decode(context, item, px) }.getOrNull() } }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    private fun decode(context: Context, item: FileItem, px: Int): Bitmap? = when (FileTypes.kindOf(item.name)) {
        FileKind.IMAGE -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(item.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
            BitmapFactory.decodeFile(item.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(item.path))) { d, _, _ -> d.setTargetSampleSize(sample) }
        }
        FileKind.VIDEO -> MediaMetadataRetriever().let { r ->
            try { r.setDataSource(item.path); r.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px, px) }
            finally { r.release() }
        }
        FileKind.APK -> {
            val pm = context.packageManager
            pm.getPackageArchiveInfo(item.path, 0)?.applicationInfo?.let {
                it.sourceDir = item.path; it.publicSourceDir = item.path
                it.loadIcon(pm).toBitmap(px, px)
            }
        }
        else -> null
    }
}

/** Shows the preview for [item] when there is one, otherwise [fallback]. */
@Composable
fun FileThumb(item: FileItem, px: Int, modifier: Modifier, enabled: Boolean, fallback: @Composable () -> Unit) {
    if (!enabled || item.isDirectory || !Thumbs.supports(item.name)) { fallback(); return }
    val context = LocalContext.current
    // Settings > Browser > "Animate GIF thumbnails": on/off, loop or play once, and the biggest file that will animate.
    val animate = ThumbOptions.animate
    val loop = ThumbOptions.loop
    val maxMb = ThumbOptions.maxMb
    if (animate && Thumbs.mayAnimate(item.name)) {
        // GIFs and animated WebP play inside the tile while it is on screen; the still shows until the animation is ready.
        val anim by produceState<android.graphics.drawable.Drawable?>(null, item.path, item.lastModified, maxMb) { value = Thumbs.loadAnimated(item, px, maxMb) }
        val d = anim
        if (d != null) {
            // Drawn straight onto the tile's canvas: the drawable tells us when its next frame is ready (invalidateDrawable)
            // and we redraw then, so it can't stall the way a wrapped ImageView did, and it loops forever.
            var frame by androidx.compose.runtime.remember(d) { androidx.compose.runtime.mutableIntStateOf(0) }
            androidx.compose.runtime.DisposableEffect(d, loop) {
                val a = d as android.graphics.drawable.AnimatedImageDrawable
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                a.callback = object : android.graphics.drawable.Drawable.Callback {
                    override fun invalidateDrawable(who: android.graphics.drawable.Drawable) { frame++ }
                    override fun scheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable, `when`: Long) { handler.postAtTime(what, who, `when`) }
                    override fun unscheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable) { handler.removeCallbacks(what, who) }
                }
                // Loop on: repeat forever. Loop off: one play, then it rests on the last frame.
                a.repeatCount = if (loop) android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE else 0
                a.registerAnimationCallback(object : android.graphics.drawable.Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(drawable: android.graphics.drawable.Drawable?) { if (loop) a.start() }   // some files report one play
                })
                a.start()
                onDispose {
                    a.stop(); a.clearAnimationCallbacks(); a.callback = null
                    handler.removeCallbacksAndMessages(a)
                }
            }
            // Watchdog: if the drawable ever stops for any reason (end of a run, a paused window ...), start it again.
            androidx.compose.runtime.LaunchedEffect(d, loop) {
                val a = d as android.graphics.drawable.AnimatedImageDrawable
                while (true) {
                    kotlinx.coroutines.delay(250)
                    if (loop && !a.isRunning) a.start()
                    // The drawable only moves to its next frame when it is drawn; if its own redraw request is ever missed the
                    // tile would freeze, so also ask for a redraw on every tick (drawing early is harmless).
                    if (loop || a.isRunning) frame++
                }
            }
            androidx.compose.foundation.Canvas(modifier) {
                frame   // read so each new animation frame redraws this tile
                val dw = d.intrinsicWidth.coerceAtLeast(1); val dh = d.intrinsicHeight.coerceAtLeast(1)
                val scale = maxOf(size.width / dw, size.height / dh)          // fill the tile, cropping the overflow
                drawIntoCanvas { c ->
                    val n = c.nativeCanvas
                    n.save()
                    n.clipRect(0f, 0f, size.width, size.height)
                    n.translate((size.width - dw * scale) / 2f, (size.height - dh * scale) / 2f)
                    n.scale(scale, scale)
                    d.setBounds(0, 0, dw, dh)
                    d.draw(n)
                    n.restore()
                }
            }
            return
        }
    }
    val bmp by produceState<Bitmap?>(null, item.path, item.lastModified) { value = Thumbs.load(context, item, px) }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop) else fallback()
}

/**
 * The square tile at the start of a file row in the secondary lists (Trash, Storage overview, Duplicate finder):
 * always [size] wide with the same rounded corners and tint, showing the preview when there is one and the file's
 * icon centred otherwise, so every row lines up no matter what the file is. Previews decode at 256 px.
 */
@Composable
fun FileTile(item: FileItem, size: androidx.compose.ui.unit.Dp = 36.dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        FileThumb(item, 256, Modifier.fillMaxSize(), true) {
            androidx.compose.material3.Icon(
                if (item.isDirectory) androidx.compose.material.icons.Icons.Default.Folder else fileIconFor(item.name), null,
                Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ---- Fast scrollbar with bubble ----

/** A draggable scroll thumb; while dragging it shows a bubble with [labelFor] of the item under it. */
@Composable
fun FastScrollbar(
    totalItems: Int, visibleCount: Int, firstVisible: Int,
    labelFor: (Int) -> String, onScrollTo: (Int) -> Unit, modifier: Modifier = Modifier
) {
    if (totalItems == 0 || visibleCount <= 0 || visibleCount >= totalItems) return
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val maxFirst = (totalItems - visibleCount).coerceAtLeast(1)
    val fraction = if (dragging) dragFraction else (firstVisible.toFloat() / maxFirst).coerceIn(0f, 1f)
    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dragging) 0.6f else 0.35f)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.width(36.dp)) {
        val h = with(density) { maxHeight.toPx() }
        val thumbH = (visibleCount.toFloat() / totalItems * h).coerceAtLeast(with(density) { 48.dp.toPx() })
        val travel = h - thumbH
        Box(
            Modifier.fillMaxSize().pointerInput(totalItems, h) {
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        dragFraction = ((o.y - thumbH / 2) / travel).coerceIn(0f, 1f)
                        onScrollTo((dragFraction * (totalItems - 1)).roundToInt())
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false }
                ) { change, _ ->
                    dragFraction = ((change.position.y - thumbH / 2) / travel).coerceIn(0f, 1f)
                    onScrollTo((dragFraction * (totalItems - 1)).roundToInt())
                }
            }
        )
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(size.width - 8.dp.toPx(), fraction * travel),
                size = Size(6.dp.toPx(), thumbH),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
        }
        if (dragging) {
            val idx = (fraction * (totalItems - 1)).roundToInt().coerceIn(0, totalItems - 1)
            Surface(
                Modifier.offset { IntOffset(-with(density) { 72.dp.roundToPx() }, (fraction * travel + thumbH / 2 - with(density) { 20.dp.toPx() }).roundToInt()) }
                    .widthIn(min = 56.dp),
                shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 6.dp
            ) {
                Text(labelFor(idx), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

/** The text on the scroll bubble: first letter, month, size or extension, depending on the sort order. */
fun bubbleLabel(item: FileItem?, sortBy: SortBy): String {
    if (item == null) return ""
    return when (sortBy) {
        SortBy.NAME -> item.name.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
        SortBy.DATE -> if (item.lastModified > 0) SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(item.lastModified)) else "—"
        SortBy.SIZE -> if (item.isDirectory) "Folders" else formatSize(item.size)
        SortBy.TYPE -> if (item.isDirectory) "Folders" else item.name.substringAfterLast('.', "").lowercase().ifEmpty { "—" }
    }
}

// ---- Chips under the toolbar ----

@Composable
fun FilterChipsRow(state: FileBrowserUiState, tags: List<String>, vm: FileBrowserViewModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QuickFilter.entries.forEach { q ->
            FilterChip(state.quick == q, { vm.setQuick(if (state.quick == q) null else q) }, label = { Text(q.label) })
        }
        FilterChip(state.favouritesOnly, { vm.setFavouritesOnly(!state.favouritesOnly) }, label = { Text("Starred") },
            leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(16.dp)) })
        tags.forEach { t ->
            FilterChip(state.tagFilter == t, { vm.setTagFilter(if (state.tagFilter == t) null else t) }, label = { Text("#$t") })
        }
    }
}

// ---- Menus ----

@Composable
private fun MenuRow(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, enabled = enabled, onClick = onClick)
}

/** Toolbar "⋮" menu: search, view mode, create, history, trash and tools. */
@Composable
fun BrowserOverflowMenu(state: FileBrowserUiState, vm: FileBrowserViewModel, onTrash: () -> Unit, onTools: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More options") }
        DropdownMenu(open, { open = false }) {
            fun go(a: () -> Unit) = { open = false; a() }
            MenuRow(if (state.searchOpen) "Close folder search" else "Search this folder", Icons.Default.Search, onClick = go { vm.toggleSearch() })
            MenuRow("Search everywhere below", Icons.Default.ManageSearch, onClick = go { vm.showDialog(BrowserDialog.RecursiveSearch) })
            MenuRow(if (state.gridView) "Show as list" else "Show as grid", if (state.gridView) Icons.Default.ViewList else Icons.Default.GridView,
                onClick = go { vm.setGrid(!state.gridView) })
            HorizontalDivider()
            MenuRow("New folder", Icons.Default.CreateNewFolder, onClick = go { vm.showDialog(BrowserDialog.NewItem(folder = true)) })
            MenuRow("New file", Icons.Default.NoteAdd, onClick = go { vm.showDialog(BrowserDialog.NewItem(folder = false)) })
            HorizontalDivider()
            MenuRow("Recent & starred", Icons.Default.History, onClick = go { vm.showDialog(BrowserDialog.Recents) })
            MenuRow(if (state.showThumbnails) "Hide thumbnails" else "Show thumbnails", Icons.Default.Image,
                onClick = go { vm.setThumbnails(!state.showThumbnails) })
            MenuRow(if (state.showHidden) "Hide hidden files" else "Show hidden files", Icons.Default.Visibility,
                onClick = go { vm.setShowHidden(!state.showHidden) })
            HorizontalDivider()
            MenuRow("Trash", Icons.Default.Delete, onClick = go(onTrash))
            MenuRow("Tools", Icons.Default.Build, onClick = go(onTools))
        }
    }
}

/** Everything that can be done with the current selection beyond copy, move and delete. */
@Composable
fun SelectionMoreMenu(selected: List<FileItem>, vm: FileBrowserViewModel, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val paths = selected.map { it.path }
    val single = selected.singleOrNull()
    val singleFile = single?.takeIf { !it.isDirectory }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.MoreHoriz, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("More", maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            fun go(a: () -> Unit) = { open = false; a() }
            if (single != null) MenuRow("Rename", Icons.Default.Edit, onClick = go { vm.showDialog(BrowserDialog.Rename(single.path)) })
            if (selected.size > 1) MenuRow("Rename many…", Icons.Default.DriveFileRenameOutline, onClick = go { vm.showDialog(BrowserDialog.BulkRename(paths)) })
            MenuRow("Resize / convert images", Icons.Default.PhotoSizeSelectLarge, enabled = selected.any { FileTypes.isImage(it.name) }, onClick = go { vm.showDialog(BrowserDialog.ImageBatch(paths)) })
            MenuRow("Compress to zip", Icons.Default.Archive, onClick = go { vm.showDialog(BrowserDialog.Compress(paths)) })
            val allStarred = paths.all { vm.userData.isFavourite(it) }
            MenuRow(if (allStarred) "Remove star" else "Add star", Icons.Default.Star, onClick = go { vm.toggleFavouriteSelected() })
            MenuRow("Tags…", Icons.Default.Label, onClick = go { vm.showDialog(BrowserDialog.Tags(paths)) })
            MenuRow("Share", Icons.Default.Share, enabled = selected.any { !it.isDirectory }, onClick = go { vm.shareSelected() })
            HorizontalDivider()
            if (single != null) MenuRow("Properties", Icons.Default.Info, onClick = go { vm.showDialog(BrowserDialog.Properties(single.path)) })
            if (single?.isDirectory == true) MenuRow("Pin to home screen", Icons.Default.PushPin, onClick = go { vm.pinShortcut(single.path) })
            if (single?.isDirectory == true) MenuRow("Folder size", Icons.Default.Calculate, onClick = go { vm.showDialog(BrowserDialog.FolderSize(single.path)) })
            if (singleFile != null) {
                MenuRow("Checksums (MD5 / SHA)", Icons.Default.Fingerprint, onClick = go { vm.showDialog(BrowserDialog.Checksum(singleFile.path)) })
                MenuRow("Open as…", Icons.Default.OpenInNew, onClick = go { vm.showDialog(BrowserDialog.OpenAs(singleFile.path)) })
                MenuRow(if (FileTypes.kindOf(singleFile.name) == FileKind.SPLIT_PART) "Join parts" else "Split into parts", Icons.Default.CallSplit,
                    onClick = go { vm.showDialog(BrowserDialog.Split(singleFile.path)) })
            }
            if (single != null) {
                MenuRow("Change date", Icons.Default.Event, onClick = go { vm.showDialog(BrowserDialog.SetDate(single.path)) })
                MenuRow("Create symlink", Icons.Default.Link, onClick = go { vm.showDialog(BrowserDialog.Symlink(single.path)) })
            }
            if (selected.size == 2 && selected[0].isDirectory == selected[1].isDirectory)
                MenuRow("Compare", Icons.Default.Compare, onClick = go { vm.showDialog(BrowserDialog.Compare(paths[0], paths[1])) })
            HorizontalDivider()
            MenuRow("Move to vault", Icons.Default.Lock, enabled = selected.any { !it.isDirectory }, onClick = go { vm.moveToVault(paths) })
            MenuRow("Run action…", Icons.Default.Terminal, onClick = go { vm.showDialog(BrowserDialog.Actions(paths)) })
            MenuRow("Secure delete", Icons.Default.DeleteForever, onClick = go { vm.showDialog(BrowserDialog.SecureDelete(paths)) })
        }
    }
}
