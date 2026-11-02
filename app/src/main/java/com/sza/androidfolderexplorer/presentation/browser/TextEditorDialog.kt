package com.sza.androidfolderexplorer.presentation.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_EDIT_BYTES = 5L * 1024 * 1024

/** Reads a file as UTF-8 text. Refuses files that are too large or look binary (NUL bytes). */
private fun readTextFile(file: File): Result<String> = runCatching {
    require(file.isFile) { "File not found or not accessible: ${file.path}" }
    val size = file.length()
    require(size <= MAX_EDIT_BYTES) { "File is ${size / 1024} KB; the editor limit is 5 MB." }
    val bytes = file.readBytes()
    require(bytes.take(8192).none { it == 0.toByte() }) {
        "This looks like a binary file, so it can't be edited as text."
    }
    String(bytes, Charsets.UTF_8)
}

/** Full-screen text viewer/editor. Closes via the top-bar X or Back; asks before discarding edits. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorDialog(path: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var original by remember(path) { mutableStateOf<String?>(null) }
    var text by remember(path) { mutableStateOf("") }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var status by remember(path) { mutableStateOf<String?>(null) }
    var saving by remember(path) { mutableStateOf(false) }
    var confirmDiscard by remember(path) { mutableStateOf(false) }

    val dirty = original != null && text != original

    fun requestClose() {
        if (dirty) confirmDiscard = true else onClose()
    }

    LaunchedEffect(path) {
        withContext(Dispatchers.IO) { readTextFile(File(path)) }
            .onSuccess { content ->
                original = content
                text = content
            }
            .onFailure { loadError = it.message ?: "Could not open file" }
    }

    Dialog(
        onDismissRequest = { requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(File(path).name, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                                status?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { requestClose() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                            }
                        },
                        actions = {
                            IconButton(
                                enabled = dirty && !saving,
                                onClick = {
                                    saving = true
                                    scope.launch {
                                        val content = text
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { File(path).writeText(content, Charsets.UTF_8) }
                                        }
                                        saving = false
                                        result.onSuccess {
                                            original = content
                                            status = "Saved"
                                        }.onFailure {
                                            status = "Save failed: ${it.message ?: "unknown error"}"
                                        }
                                    }
                                }
                            ) {
                                if (saving) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Save, contentDescription = "Save")
                                }
                            }
                        }
                    )
                }
            ) { padding ->
                val error = loadError
                when {
                    error != null -> Text(
                        error,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(padding).padding(16.dp)
                    )
                    original == null -> CircularProgressIndicator(
                        modifier = Modifier.padding(padding).padding(16.dp)
                    )
                    else -> OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            status = null
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(8.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        maxLines = Int.MAX_VALUE
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to ${File(path).name} have not been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            }
        )
    }
}
