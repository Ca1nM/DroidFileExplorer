package com.sza.androidfolderexplorer.presentation.viewer

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.sza.androidfolderexplorer.data.FileTypes
import com.sza.androidfolderexplorer.data.media.PlaybackService
import com.sza.androidfolderexplorer.ui.theme.FolderExplorerTheme
import java.io.File

/**
 * Video and audio player. Opens with a list of files from the folder (use the next/previous
 * buttons to move between them), or a single file handed over by another app.
 */
class PlayerActivity : ComponentActivity() {

    private var future: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var isVideo by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FolderExplorerTheme(darkTheme = true, accentId = "blue", blackDark = true) {
                PlayerScreen(
                    controller = controller,
                    onBack = { finish() },
                    onPip = { enterPip() },
                    canPip = isVideo
                )
            }
        }
        connect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller?.let { load(it) }
    }

    private fun connect() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    isVideo = item?.mediaMetadata?.extras?.getBoolean("video") ?: false
                }
            })
            load(c)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun load(c: MediaController) {
        val paths = intent.getStringArrayExtra(EXTRA_PATHS)
        val items: List<MediaItem>
        var index = intent.getIntExtra(EXTRA_INDEX, 0)
        if (paths != null) {
            items = paths.map { itemFor(File(it)) }
        } else {
            val uri = intent.data ?: return
            val name = uri.lastPathSegment ?: "media"
            items = listOf(
                MediaItem.Builder().setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(name)
                            .setExtras(Bundle().apply { putBoolean("video", intent.type?.startsWith("video") == true || FileTypes.isVideo(name)) })
                            .build()
                    ).build()
            )
            index = 0
        }
        c.setMediaItems(items, index.coerceIn(0, items.lastIndex), 0L)
        c.prepare()
        c.play()
        isVideo = items.getOrNull(index)?.mediaMetadata?.extras?.getBoolean("video") ?: false
    }

    /** Adds a same-named .srt / .vtt from the same folder as a subtitle track, if there is one. */
    private fun itemFor(file: File): MediaItem {
        val subs = FileTypes.SUBTITLES.mapNotNull { ext ->
            File(file.parentFile, file.nameWithoutExtension + "." + ext).takeIf { it.isFile }
        }.map { sub ->
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sub))
                .setMimeType(if (sub.extension.equals("vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("und")
                .setLabel(sub.name)
                .build()
        }
        return MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setSubtitleConfigurations(subs)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(file.name)
                    .setExtras(Bundle().apply { putBoolean("video", FileTypes.isVideo(file.name)) })
                    .build()
            ).build()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isVideo) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (controller?.isPlaying == true) enterPip()
    }

    /** Video stops when the screen goes away; audio keeps playing through the service. */
    override fun onStop() {
        super.onStop()
        if (isVideo && !isInPictureInPictureMode) controller?.pause()
    }

    override fun onDestroy() {
        future?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATHS = "paths"
        const val EXTRA_INDEX = "index"

        fun intent(context: Context, paths: List<String>, index: Int) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_PATHS, paths.toTypedArray())
                .putExtra(EXTRA_INDEX, index)
    }
}

private val Speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
private fun PlayerScreen(controller: MediaController?, onBack: () -> Unit, onPip: () -> Unit, canPip: Boolean) {
    var speedIndex by remember { mutableStateOf(2) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (controller == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        player = controller
                        controllerShowTimeoutMs = 3000
                        setShowSubtitleButton(true)
                        setShowNextButton(true)
                        setShowPreviousButton(true)
                    }
                },
                update = { it.player = controller }
            )
            DisposableEffect(controller) { onDispose { } }
        }
        Row(
            Modifier.statusBarsPadding().padding(4.dp).align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White) }
        }
        Row(
            Modifier.statusBarsPadding().padding(4.dp).align(Alignment.TopEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (controller != null) {
                IconButton(onClick = {
                    speedIndex = (speedIndex + 1) % Speeds.size
                    controller.setPlaybackSpeed(Speeds[speedIndex])
                }) { Icon(Icons.Default.Speed, "Playback speed", tint = Color.White) }
                Text("${Speeds[speedIndex]}x", color = Color.White)
            }
            if (canPip) IconButton(onClick = onPip) {
                Icon(Icons.Default.PictureInPictureAlt, "Picture in picture", tint = Color.White)
            }
        }
    }
}
