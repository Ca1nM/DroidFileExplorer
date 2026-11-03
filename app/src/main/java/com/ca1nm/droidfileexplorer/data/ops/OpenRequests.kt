package com.ca1nm.droidfileexplorer.data.ops

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A file another app asked this one to open. [temporary] means it is a private copy, so edits would not reach the original. */
data class FileRequest(val path: String, val temporary: Boolean)

/**
 * A folder or file another part of the system asked the browser to open: a widget tap, the share
 * receiver, a launcher shortcut, or "Open with" from another app. The browser consumes it and clears it.
 */
object OpenRequests {
    private val _path = MutableStateFlow<String?>(null)
    val path: StateFlow<String?> = _path.asStateFlow()

    /**
     * Android also reaches internal storage as /sdcard, /storage/self/primary and /mnt/sdcard (all aliases, not an SD
     * card). Use the one canonical path so the breadcrumb says "Internal storage" and history/favourites match.
     */
    fun canonical(path: String): String {
        val root = "/storage/emulated/0"
        for (alias in listOf("/sdcard", "/mnt/sdcard", "/storage/self/primary", "/mnt/user/0/primary", "/storage/emulated/legacy")) {
            if (path == alias) return root
            if (path.startsWith("$alias/")) return root + path.removePrefix(alias)
        }
        return path
    }

    fun request(path: String) { _path.value = canonical(path) }
    fun consume() { _path.value = null }

    private val _file = MutableStateFlow<FileRequest?>(null)
    val file: StateFlow<FileRequest?> = _file.asStateFlow()

    fun requestFile(path: String, temporary: Boolean = false) { _file.value = FileRequest(canonical(path), temporary) }
    fun consumeFile() { _file.value = null }
}
