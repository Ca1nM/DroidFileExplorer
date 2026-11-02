package com.sza.androidfolderexplorer.data.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Text-editor settings held as Compose state, so an open editor reacts at once to a change in Settings. */
object EditorOptions {
    /** Colour keywords, strings, comments and so on in code, scripts, config, web and log files. */
    var syntax by mutableStateOf(true)

    fun apply(s: Settings) {
        syntax = s.syntaxColours
    }
}
