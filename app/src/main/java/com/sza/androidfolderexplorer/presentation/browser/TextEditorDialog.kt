package com.sza.androidfolderexplorer.presentation.browser

import android.text.Selection
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.widget.EditText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sza.androidfolderexplorer.data.TextFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Reads a file as UTF-8 text. Refuses files that are too large or look binary (NUL bytes). */
private fun readTextFile(file: File): Result<String> = runCatching {
    require(file.isFile) { "File not found or not accessible: ${file.path}" }
    val size = file.length()
    require(size <= TextFiles.MAX_EDIT_BYTES) {
        "File is ${size / 1024} KB; the editor limit is ${TextFiles.MAX_EDIT_BYTES / 1024} KB."
    }
    val bytes = file.readBytes()
    require(bytes.take(8192).none { it == 0.toByte() }) {
        "This looks like a binary file, so it can't be edited as text."
    }
    String(bytes, Charsets.UTF_8)
}

/** Wrapping long lines makes the layout of a big file many times slower, so it's only offered up to this size. */
private const val MAX_WRAP_CHARS = 600_000

/** Longest file with a very long line that the editor will take (wrapping a single huge paragraph is slow). */
private const val MAX_LONG_LINE_FILE_CHARS = 2_500_000

/**
 * Full-screen text editor for plain text, configs, JSON, XML and so on. The text lives in Android's native text
 * widget (see [NativeTextArea]), so files of several megabytes stay responsive. Lines don't wrap by default
 * (they scroll sideways); there's a Find bar; Back or the arrow closes it and asks before discarding edits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorDialog(path: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by remember(path) { mutableStateOf<String?>(null) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var status by remember(path) { mutableStateOf<String?>(null) }
    var saving by remember(path) { mutableStateOf(false) }
    var confirmDiscard by remember(path) { mutableStateOf(false) }
    var dirty by remember(path) { mutableStateOf(false) }
    var widget by remember(path) { mutableStateOf<EditText?>(null) }
    var wrap by remember(path) { mutableStateOf(false) }
    var longLines by remember(path) { mutableStateOf(false) }

    // Find
    var findOpen by remember(path) { mutableStateOf(false) }
    var query by remember(path) { mutableStateOf("") }
    var matchCase by remember(path) { mutableStateOf(false) }
    var findInfo by remember(path) { mutableStateOf<String?>(null) }
    val matchSpan = remember(path) { BackgroundColorSpan(0x99FFC107.toInt()) }
    val findFocus = remember { FocusRequester() }

    fun requestClose() {
        if (dirty) confirmDiscard = true else onClose()
    }

    /**
     * Selects the next (or previous) match of [query] and scrolls to it. [fromCaretStart] searches from the start of the
     * current selection, so a match you are already on stays selected while you type the rest of the query.
     */
    fun find(forward: Boolean, fromCaretStart: Boolean = false) {
        val edit = widget ?: return
        val q = query
        val editable = edit.text
        editable.removeSpan(matchSpan)
        if (q.isEmpty()) { findInfo = null; return }
        val hay = editable.toString()
        val ignoreCase = !matchCase
        val from = when {
            fromCaretStart -> edit.selectionStart.coerceAtLeast(0)
            forward -> edit.selectionEnd.coerceAtLeast(0)
            else -> (edit.selectionStart - 1).coerceAtLeast(0)
        }
        var idx = if (forward) hay.indexOf(q, from, ignoreCase) else hay.lastIndexOf(q, from, ignoreCase)
        if (idx < 0) idx = if (forward) hay.indexOf(q, 0, ignoreCase) else hay.lastIndexOf(q, hay.length, ignoreCase)   // wrap around
        if (idx < 0) { findInfo = "No matches"; return }
        editable.setSpan(matchSpan, idx, idx + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        Selection.setSelection(editable, idx, idx + q.length)
        edit.bringPointIntoView(idx)
        // "3 of 120", counted off the main thread
        scope.launch(Dispatchers.Default) {
            var total = 0
            var ordinal = 0
            var i = hay.indexOf(q, 0, ignoreCase)
            while (i >= 0 && total < 999_999) {
                total++
                if (i == idx) ordinal = total
                i = hay.indexOf(q, i + q.length, ignoreCase)
            }
            findInfo = if (ordinal > 0) "$ordinal of $total" else "$total matches"
        }
    }

    val opened = remember(path) { System.nanoTime() }
    var readMs by remember(path) { mutableStateOf(0L) }
    var scanMs by remember(path) { mutableStateOf(0L) }
    LaunchedEffect(path) {
        val t0 = System.nanoTime()
        val read = withContext(Dispatchers.IO) { readTextFile(File(path)) }
        readMs = (System.nanoTime() - t0) / 1_000_000
        read
            .onSuccess { text ->
                // A file with a huge line can only be shown wrapped (see TextFiles.LONG_LINE_CHARS).
                val t1 = System.nanoTime()
                val longest = withContext(Dispatchers.Default) { TextFiles.longestLine(text) }
                scanMs = (System.nanoTime() - t1) / 1_000_000
                if (longest > TextFiles.LONG_LINE_CHARS) {
                    // Laying out one giant paragraph takes about 3.5 s per MB, so cap it to keep the app responsive.
                    if (text.length > MAX_LONG_LINE_FILE_CHARS) {
                        loadError = "This file has lines far too long to show smoothly, and at ${text.length / 1024} KB it is over the " +
                            "${MAX_LONG_LINE_FILE_CHARS / 1024} KB limit for such files. Reformat it (for example pretty-print the JSON) first."
                        return@onSuccess
                    }
                    wrap = true
                    longLines = true
                    status = "Very long lines: wrapped"
                }
                loaded = text
            }
            .onFailure { loadError = it.message ?: "Could not open file" }
    }
    // Search as you type.
    LaunchedEffect(query, matchCase, findOpen) { if (findOpen) find(forward = true, fromCaretStart = true) }
    LaunchedEffect(findOpen) { if (findOpen) runCatching { findFocus.requestFocus() } else widget?.text?.removeSpan(matchSpan) }

    Dialog(
        onDismissRequest = { if (findOpen) findOpen = false else requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(File(path).name, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { requestClose() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                            }
                        },
                        actions = {
                            IconButton(enabled = loaded != null, onClick = { findOpen = !findOpen }) {
                                Icon(Icons.Default.Search, contentDescription = "Find")
                            }
                            IconButton(
                                // Files with huge lines must stay wrapped; big files are too slow to wrap by choice.
                                enabled = loaded != null && !longLines && (wrap || (loaded?.length ?: 0) <= MAX_WRAP_CHARS),
                                onClick = { wrap = !wrap }
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.WrapText,
                                    contentDescription = if (wrap) "Stop wrapping long lines" else "Wrap long lines",
                                    tint = if (wrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                enabled = dirty && !saving,
                                onClick = {
                                    val edit = widget ?: return@IconButton
                                    saving = true
                                    scope.launch {
                                        val content = edit.text.toString()   // one copy of the text, taken when saving
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { File(path).writeText(content, Charsets.UTF_8) }
                                        }
                                        saving = false
                                        result.onSuccess {
                                            dirty = false
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
                val text = loaded
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (findOpen) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                placeholder = { Text("Find") },
                                supportingText = findInfo?.let { info -> { Text(info) } },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { find(forward = true) }),
                                modifier = Modifier.weight(1f).focusRequester(findFocus)
                            )
                            TextButton(onClick = { matchCase = !matchCase }) {
                                Text(
                                    "Aa",
                                    color = if (matchCase) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            IconButton(onClick = { find(forward = false) }) { Icon(Icons.Default.KeyboardArrowUp, "Previous match") }
                            IconButton(onClick = { find(forward = true) }) { Icon(Icons.Default.KeyboardArrowDown, "Next match") }
                            IconButton(onClick = { findOpen = false }) { Icon(Icons.Default.Close, "Close find") }
                        }
                    }
                    when {
                        error != null -> Text(
                            error,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp)
                        )
                        text == null -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                        else -> NativeTextArea(
                            text = text,
                            editable = true,
                            wrap = wrap,
                            modifier = Modifier.fillMaxSize(),
                            fileName = File(path).name,
                            onReady = { widget = it },
                            onEdited = { dirty = true; status = null },
                            onFirstDraw = {
                                android.util.Log.i(
                                    "AFEX_EDIT",
                                    "${File(path).name}: ${text.length} chars on screen ${(System.nanoTime() - opened) / 1_000_000} ms after the editor opened " +
                                        "(read ${readMs} ms, line scan ${scanMs} ms, wrap=$wrap)"
                                )
                            }
                        )
                    }
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
