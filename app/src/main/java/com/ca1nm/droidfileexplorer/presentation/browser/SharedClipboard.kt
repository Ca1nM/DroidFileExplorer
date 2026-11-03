package com.ca1nm.droidfileexplorer.presentation.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What was last copied or cut in the file browser. The browser shows its "Paste here" bar from this, and
 * the cloud / server screen can paste the same items into a remote folder.
 */
object SharedClipboard {
    private val _state = MutableStateFlow<ClipboardState?>(null)
    val state: StateFlow<ClipboardState?> = _state.asStateFlow()
    fun set(clip: ClipboardState?) { _state.value = clip }
}
