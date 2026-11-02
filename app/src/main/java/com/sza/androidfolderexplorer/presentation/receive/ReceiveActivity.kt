package com.sza.androidfolderexplorer.presentation.receive

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.sza.androidfolderexplorer.MainActivity
import com.sza.androidfolderexplorer.data.source.FileOps
import java.io.File
import kotlin.concurrent.thread

/**
 * Target of "Share" from other apps. Copies the shared files into Download/Received and
 * opens that folder in the browser.
 */
class ReceiveActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris()
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nothing to receive", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Received")
        Toast.makeText(this, "Saving ${uris.size} file(s)…", Toast.LENGTH_SHORT).show()
        thread {
            var saved = 0
            var error: String? = null
            dest.mkdirs()
            for (uri in uris) {
                try {
                    val target = FileOps.uniqueName(dest, displayName(uri))
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().buffered().use { input.copyTo(it) }
                    } ?: error("Couldn't read the shared file")
                    saved++
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
            }
            runOnUiThread {
                val msg = if (error == null) "Saved $saved file(s) to Download/Received" else "Saved $saved; error: $error"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (saved > 0) {
                    startActivity(Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_PATH, dest.absolutePath)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }
                finish()
            }
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

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it.replace('/', '_') }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "shared-file"
    }
}
