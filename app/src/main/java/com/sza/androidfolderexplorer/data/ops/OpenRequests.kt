package com.sza.androidfolderexplorer.data.ops

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A folder another part of the system asked the browser to open: a widget tap, the share
 * receiver, or a launcher shortcut. The browser consumes it and clears it.
 */
object OpenRequests {
    private val _path = MutableStateFlow<String?>(null)
    val path: StateFlow<String?> = _path.asStateFlow()

    fun request(path: String) { _path.value = path }
    fun consume() { _path.value = null }
}
