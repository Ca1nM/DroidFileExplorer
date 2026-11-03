package com.ca1nm.droidfileexplorer.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ca1nm.droidfileexplorer.data.settings.AppSettings

private val DefaultDarkColorScheme = darkColorScheme(
    primary = Blue80,
    secondary = BlueGrey80,
    tertiary = Teal80
)

private val DefaultLightColorScheme = lightColorScheme(
    primary = Blue40,
    secondary = BlueGrey40,
    tertiary = Teal40
)

/**
 * Applies the app colour scheme.
 *
 * [accentId] is [AppSettings.ACCENT_WALLPAPER] to use the device wallpaper palette (Android 12+),
 * or a preset id from [AccentPresets]. [blackDark] replaces the dark background and surfaces with black.
 */
@Composable
fun DroidFileExplorerTheme(
    darkTheme: Boolean,
    accentId: String = AppSettings.ACCENT_WALLPAPER,
    blackDark: Boolean = false,
    content: @Composable () -> Unit
) {
    val preset = accentPresetFor(accentId) ?: customAccentPreset(accentId)
    val useWallpaper = preset == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val base: ColorScheme = when {
        useWallpaper -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        preset != null && darkTheme -> darkColorScheme(
            primary = preset.darkPrimary,
            onPrimary = Color(0xFF101418),
            primaryContainer = preset.darkContainer,
            onPrimaryContainer = Color(0xFFEFEFF4),
            secondary = BlueGrey80,
            tertiary = Teal80
        )
        preset != null -> lightColorScheme(
            primary = preset.lightPrimary,
            onPrimary = Color.White,
            primaryContainer = preset.lightContainer,
            onPrimaryContainer = Color(0xFF1C1B1F),
            secondary = BlueGrey40,
            tertiary = Teal40
        )
        darkTheme -> DefaultDarkColorScheme
        else -> DefaultLightColorScheme
    }

    // Dark theme text is pure white. Material's default dark text colours are lavender-tinted,
    // which read as pink against the accent.
    val withWhiteText = if (darkTheme) {
        base.copy(
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Color.White
        )
    } else {
        base
    }

    val colorScheme = if (darkTheme && blackDark) {
        withWhiteText.copy(background = Color.Black, surface = Color.Black)
    } else {
        withWhiteText
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
