package com.sza.androidfolderexplorer.presentation.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import android.app.WallpaperManager
import android.widget.Toast
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.FileInputStream
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")

fun isImageFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/**
 * Full-screen viewer: swipe between [paths], pinch to zoom, double-tap to zoom in or reset.
 * GIFs animate. The toolbar adds slideshow, photo details, share and set-as-wallpaper.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerDialog(
    paths: List<String>,
    startIndex: Int,
    onClose: () -> Unit,
    onInfo: (String) -> Unit = {},
    onShare: (String) -> Unit = {}
) {
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, paths.lastIndex)) { paths.size }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var slideshow by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    // Slideshow: advance every 3 seconds, wrapping around.
    LaunchedEffect(slideshow, paths.size) {
        while (slideshow && paths.size > 1) {
            delay(3000)
            pager.animateScrollToPage((pager.currentPage + 1) % paths.size)
        }
    }

    fun setWallpaper(flag: Int) {
        val path = paths[pager.currentPage]
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    FileInputStream(path).use { WallpaperManager.getInstance(context).setStream(it, null, true, flag) }
                    "Wallpaper set"
                }.getOrElse { "Couldn't set wallpaper: ${it.message}" }
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                ImagePage(paths[page])
            }
            TopAppBar(
                title = { Text("${File(paths[pager.currentPage]).name}  (${pager.currentPage + 1}/${paths.size})", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                },
                actions = {
                    if (paths.size > 1) IconButton(onClick = { slideshow = !slideshow }) {
                        Icon(if (slideshow) Icons.Default.Pause else Icons.Default.PlayArrow, if (slideshow) "Stop slideshow" else "Start slideshow")
                    }
                    IconButton(onClick = { onShare(paths[pager.currentPage]) }) { Icon(Icons.Default.Share, "Share") }
                    IconButton(onClick = { onInfo(paths[pager.currentPage]) }) { Icon(Icons.Default.Info, "Photo details") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Set as home wallpaper") }, onClick = { menu = false; setWallpaper(WallpaperManager.FLAG_SYSTEM) })
                            DropdownMenuItem(text = { Text("Set as lock screen wallpaper") }, onClick = { menu = false; setWallpaper(WallpaperManager.FLAG_LOCK) })
                            DropdownMenuItem(text = { Text("Set as both") }, onClick = { menu = false; setWallpaper(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    }
}

private sealed interface Loaded {
    data class Still(val bitmap: Bitmap) : Loaded
    data class Animated(val drawable: AnimatedImageDrawable) : Loaded
    data object Failed : Loaded
}

@Composable
private fun ImagePage(path: String) {
    var loaded by remember(path) { mutableStateOf<Loaded?>(null) }
    LaunchedEffect(path) {
        loaded = withContext(Dispatchers.IO) { decode(path) }
    }
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offsetX by remember(path) { mutableFloatStateOf(0f) }
    var offsetY by remember(path) { mutableFloatStateOf(0f) }

    val zoomModifier = Modifier
        .fillMaxSize()
        .pointerInput(path) {
            detectTapGestures(onDoubleTap = {
                if (scale > 1f) { scale = 1f; offsetX = 0f; offsetY = 0f } else scale = 3f
            })
        }
        .pointerInput(path) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 8f)
                if (scale > 1f) {
                    offsetX += pan.x
                    offsetY += pan.y
                } else {
                    offsetX = 0f
                    offsetY = 0f
                }
            }
        }
        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val l = loaded) {
            null -> CircularProgressIndicator(color = Color.White)
            Loaded.Failed -> Text("Can't display this image", color = Color.White)
            is Loaded.Still -> Image(
                bitmap = l.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = zoomModifier
            )
            is Loaded.Animated -> AndroidView(
                modifier = zoomModifier,
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageDrawable(l.drawable)
                        l.drawable.start()
                    }
                }
            )
        }
    }
}

/** Decodes at most ~2048px on the long side so large photos don't exhaust memory. */
private fun decode(path: String): Loaded = runCatching {
    val file = File(path)
    if (file.extension.equals("gif", true) || file.extension.equals("webp", true)) {
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
        if (drawable is AnimatedImageDrawable) return@runCatching Loaded.Animated(drawable)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, _, _ -> d.setTargetSampleSize(sample) }
    Loaded.Still(bitmap)
}.getOrDefault(Loaded.Failed)
