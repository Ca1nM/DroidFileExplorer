package com.ca1nm.droidfileexplorer.presentation.server

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.ca1nm.droidfileexplorer.data.ops.JobService
import com.ca1nm.droidfileexplorer.data.server.LanServer
import java.io.File

private fun qr(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
        for (x in 0 until size) for (y in 0 until size) b.setPixel(x, y, if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by LanServer.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val storage = Environment.getExternalStorageDirectory()
    val choices = listOf("Download/Shared" to File(storage, "Download/Shared"), "Download" to File(storage, "Download"),
        "Pictures" to File(storage, "Pictures"), "DCIM" to File(storage, "DCIM"), "Documents" to File(storage, "Documents"))
    var choice by remember { mutableStateOf(0) }
    var allowUpload by remember { mutableStateOf(true) }
    val bitmap = remember(state.url) { if (state.running) qr(state.url) else null }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Share over Wi-Fi") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!state.running) {
                Text("Serve a folder to any browser on your Wi-Fi. Open the link or scan the QR code on your PC; only people with the link can get in.", style = MaterialTheme.typography.bodyMedium)
                Text("Folder to share", style = MaterialTheme.typography.titleSmall)
                choices.forEachIndexed { i, (label, _) ->
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(choice == i, { choice = i }); Text(label) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(allowUpload, { allowUpload = it }); Spacer(Modifier.width(8.dp)); Text("Allow uploads from the browser")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    LanServer.start(context, choices[choice].second, allowUpload)
                    val s = LanServer.state.value
                    if (s.running) {
                        JobService.onCancel = { LanServer.stop(); JobService.stop(context) }
                        JobService.update(context, "Sharing over Wi-Fi", s.url.substringBefore("?"), -1)
                    }
                }, Modifier.fillMaxWidth()) { Text("Start sharing") }
            } else {
                Text("Sharing ${File(state.root).name}", style = MaterialTheme.typography.titleMedium)
                bitmap?.let {
                    Image(it.asImageBitmap(), "QR code for the link", Modifier.size(260.dp).align(Alignment.CenterHorizontally).background(Color.White).padding(8.dp))
                }
                SelectionContainer { Text(state.url, style = MaterialTheme.typography.bodyMedium) }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(state.url)) }) {
                    Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy link")
                }
                Text("${state.requests} request(s) so far · uploads ${if (state.allowUpload) "on" else "off"}", style = MaterialTheme.typography.bodySmall)
                Text("Anyone on this Wi-Fi who has the link can read this folder${if (state.allowUpload) " and add files to it" else ""}. Stop sharing when you're done.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Button(onClick = { LanServer.stop(); JobService.stop(context) }, Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Stop sharing") }
            }
        }
    }
}
