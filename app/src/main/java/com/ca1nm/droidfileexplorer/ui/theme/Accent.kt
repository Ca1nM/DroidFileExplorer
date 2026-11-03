package com.ca1nm.droidfileexplorer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.ca1nm.droidfileexplorer.data.settings.AppSettings

/** A selectable accent colour. Light and dark variants are tuned so text on them stays readable. */
data class AccentPreset(
    val id: String,
    val label: String,
    val seed: Color,
    val lightPrimary: Color,
    val lightContainer: Color,
    val darkPrimary: Color,
    val darkContainer: Color
)

val AccentPresets = listOf(
    AccentPreset("blue", "Blue", Color(0xFF1565C0),
        Color(0xFF1565C0), Color(0xFFD6E3FF), Color(0xFFA8C8FF), Color(0xFF00468C)),
    AccentPreset("teal", "Teal", Color(0xFF00897B),
        Color(0xFF00897B), Color(0xFFA7F2E7), Color(0xFF82D5C7), Color(0xFF005047)),
    AccentPreset("green", "Green", Color(0xFF2E7D32),
        Color(0xFF2E7D32), Color(0xFFB8F3B0), Color(0xFF9CD69A), Color(0xFF0A5312)),
    AccentPreset("purple", "Purple", Color(0xFF6750A4),
        Color(0xFF6750A4), Color(0xFFEADDFF), Color(0xFFD0BCFF), Color(0xFF4F378B)),
    AccentPreset("orange", "Orange", Color(0xFFBF5B00),
        Color(0xFFBF5B00), Color(0xFFFFDBC6), Color(0xFFFFB68A), Color(0xFF7A3500)),
    AccentPreset("rose", "Rose", Color(0xFFB3266B),
        Color(0xFFB3266B), Color(0xFFFFD9E2), Color(0xFFFFB0C8), Color(0xFF8C1152))
)

fun accentPresetFor(id: String): AccentPreset? = AccentPresets.firstOrNull { it.id == id }

/** Accent id for a custom colour, e.g. "custom:FF1565C0". */
fun customAccentId(argb: Int): String = AppSettings.ACCENT_CUSTOM_PREFIX + "%08X".format(argb)

/** The ARGB value stored in a custom accent id, or null if [id] is not a custom accent. */
fun customAccentArgb(id: String): Int? {
    if (!id.startsWith(AppSettings.ACCENT_CUSTOM_PREFIX)) return null
    return id.removePrefix(AppSettings.ACCENT_CUSTOM_PREFIX).toLongOrNull(16)?.toInt()
}

/** Builds light and dark variants around a user-picked colour. */
fun customAccentPreset(id: String): AccentPreset? {
    val argb = customAccentArgb(id) ?: return null
    val seed = Color(argb)
    return AccentPreset(
        id = id,
        label = "Custom",
        seed = seed,
        // Very light picks would be unreadable under white text, so darken them for light theme.
        lightPrimary = if (seed.luminance() > 0.5f) lerp(seed, Color.Black, 0.35f) else seed,
        lightContainer = lerp(seed, Color.White, 0.8f),
        darkPrimary = lerp(seed, Color.White, 0.35f),
        darkContainer = lerp(seed, Color.Black, 0.6f)
    )
}
