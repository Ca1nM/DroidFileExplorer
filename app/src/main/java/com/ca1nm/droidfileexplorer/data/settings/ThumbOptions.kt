package com.ca1nm.droidfileexplorer.data.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The animated-thumbnail settings, held as Compose state so every thumbnail on screen reacts the moment one is changed
 * in Settings. [AppSettings] keeps these in step with what is saved.
 */
object ThumbOptions {
    /** Play GIFs and animated WebP inside their thumbnails (otherwise they show a still first frame). */
    var animate by mutableStateOf(true)
    /** Repeat forever; off plays the animation once and stops on its last frame. */
    var loop by mutableStateOf(true)
    /** Files bigger than this are shown as a still, so a long list of large GIFs stays smooth. 0 means no limit. */
    var maxMb by mutableIntStateOf(40)

    fun apply(s: Settings) {
        animate = s.animateThumbnails
        loop = s.animationLoop
        maxMb = s.animationMaxMb
    }
}
