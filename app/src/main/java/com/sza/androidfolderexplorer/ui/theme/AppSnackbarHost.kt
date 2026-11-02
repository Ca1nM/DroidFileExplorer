package com.sza.androidfolderexplorer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance

/**
 * The snackbar ("Saved to…", "Open", "Undo"). Material's default colour is the "inverse surface", which in dark mode is
 * a light grey slab. In dark mode this uses a dark raised surface instead, so it matches the rest of the app; in light mode
 * it keeps the default.
 */
@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    SnackbarHost(hostState, modifier) { data ->
        if (dark) {
            Snackbar(
                snackbarData = data,
                containerColor = colors.surfaceContainerHighest,
                contentColor = colors.onSurface,
                actionColor = colors.primary,
                dismissActionContentColor = colors.onSurfaceVariant
            )
        } else {
            Snackbar(snackbarData = data)
        }
    }
}
