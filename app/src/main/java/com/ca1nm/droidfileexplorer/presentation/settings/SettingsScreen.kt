package com.ca1nm.droidfileexplorer.presentation.settings

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ca1nm.droidfileexplorer.data.settings.AppSettings
import com.ca1nm.droidfileexplorer.data.settings.Settings
import com.ca1nm.droidfileexplorer.data.settings.ThemeMode
import com.ca1nm.droidfileexplorer.ui.theme.AccentPresets
import com.ca1nm.droidfileexplorer.ui.theme.customAccentArgb
import com.ca1nm.droidfileexplorer.ui.theme.customAccentId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettings: AppSettings
) : ViewModel() {
    val settings: StateFlow<Settings> = appSettings.state

    fun setThemeMode(mode: ThemeMode) = appSettings.setThemeMode(mode)
    fun setAccent(id: String) = appSettings.setAccent(id)
    fun setBlackDark(enabled: Boolean) = appSettings.setBlackDark(enabled)
    fun setShowHidden(show: Boolean) = appSettings.setShowHidden(show)
    fun setThumbnails(v: Boolean) = appSettings.setShowThumbnails(v)
    fun setAnimateThumbnails(v: Boolean) = appSettings.setAnimateThumbnails(v)
    fun setAnimationLoop(v: Boolean) = appSettings.setAnimationLoop(v)
    fun setAnimationMaxMb(v: Int) = appSettings.setAnimationMaxMb(v)
    fun setSyntaxColours(v: Boolean) = appSettings.setSyntaxColours(v)
    fun setOpenFromApp(v: String) = appSettings.setOpenFromApp(v)
    fun setTapOpens(v: Boolean) = appSettings.setTapOpens(v)
    fun setUseTrash(v: Boolean) = appSettings.setUseTrash(v)
    fun setTrashDays(v: Int) = appSettings.setTrashDays(v)
    fun setHaptics(v: Boolean) = appSettings.setHaptics(v)
    fun setSounds(v: Boolean) = appSettings.setSounds(v)
    fun setDualPane(v: Boolean) = appSettings.setDualPane(v)
    fun showIntroAgain() = appSettings.setOnboardingDone(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SectionHeader("Appearance")

            Text("Theme", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ThemeMode.entries.forEach { mode ->
                OptionRow(
                    title = when (mode) {
                        ThemeMode.SYSTEM -> "Follow system"
                        ThemeMode.LIGHT -> "Light"
                        ThemeMode.DARK -> "Dark"
                    },
                    selected = settings.themeMode == mode,
                    onClick = { viewModel.setThemeMode(mode) }
                ) {
                    RadioButton(selected = settings.themeMode == mode, onClick = null)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Accent colour", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            AccentPicker(
                selected = settings.accent,
                onSelect = { viewModel.setAccent(it) }
            )

            Spacer(Modifier.height(12.dp))
            SwitchRow(
                title = "Black background",
                description = "Pure black instead of dark grey in dark theme. Helps OLED screens.",
                checked = settings.blackDark,
                onCheckedChange = { viewModel.setBlackDark(it) }
            )

            SectionHeader("Browser")
            SwitchRow(
                title = "Show hidden files",
                description = "Dotfiles and dot-folders such as .config",
                checked = settings.showHidden,
                onCheckedChange = { viewModel.setShowHidden(it) }
            )
            SwitchRow(
                title = "Thumbnails",
                description = "Show previews for photos, videos and APK icons",
                checked = settings.showThumbnails,
                onCheckedChange = { viewModel.setThumbnails(it) }
            )
            if (settings.showThumbnails) {
                SwitchRow(
                    title = "Animate GIF thumbnails",
                    description = "Play GIFs and animated WebP inside their thumbnails. Off: show a still picture.",
                    checked = settings.animateThumbnails,
                    onCheckedChange = { viewModel.setAnimateThumbnails(it) }
                )
                if (settings.animateThumbnails) {
                    SwitchRow(
                        title = "Loop animations",
                        description = "Off: play once, then stop on the last frame",
                        checked = settings.animationLoop,
                        onCheckedChange = { viewModel.setAnimationLoop(it) }
                    )
                    Text("Only animate files smaller than", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(5, 20, 40, 100, 0).forEach { mb ->
                        OptionRow(
                            title = if (mb == 0) "No limit" else "$mb MB",
                            selected = settings.animationMaxMb == mb,
                            onClick = { viewModel.setAnimationMaxMb(mb) }
                        ) { RadioButton(selected = settings.animationMaxMb == mb, onClick = null) }
                    }
                }
            }
            SwitchRow(
                title = "Tap opens files",
                description = "Tap a file to open it. Off: tapping selects it, like long-press.",
                checked = settings.tapOpens,
                onCheckedChange = { viewModel.setTapOpens(it) }
            )
            SwitchRow(
                title = "Two panes on wide screens",
                description = "Show two folders side by side when the window is wide, such as unfolded",
                checked = settings.dualPane,
                onCheckedChange = { viewModel.setDualPane(it) }
            )

            SwitchRow(
                title = "Syntax colouring",
                description = "Colour keywords, strings and comments in code, scripts, web, config and log files. Files over 200 KB stay plain.",
                checked = settings.syntaxColours,
                onCheckedChange = { viewModel.setSyntaxColours(it) }
            )
            Spacer(Modifier.height(8.dp))
            Text("When another app opens a file", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("For example tapping Open on a finished download in Firefox", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(
                AppSettings.FROM_APP_LOCATE to "Show it in its folder (scrolled to and highlighted)",
                AppSettings.FROM_APP_OPEN to "Open it straight away"
            ).forEach { (mode, label) ->
                OptionRow(
                    title = label,
                    selected = settings.openFromApp == mode,
                    onClick = { viewModel.setOpenFromApp(mode) }
                ) { RadioButton(selected = settings.openFromApp == mode, onClick = null) }
            }

            SectionHeader("Default app for files")
            DefaultAppSection()

            SectionHeader("Deleting")
            SwitchRow(
                title = "Use trash",
                description = "Deleted items move to the trash instead of being erased",
                checked = settings.useTrash,
                onCheckedChange = { viewModel.setUseTrash(it) }
            )
            if (settings.useTrash) {
                Text("Empty the trash automatically after", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(7, 14, 30, 90).forEach { d ->
                    OptionRow(
                        title = "$d days",
                        selected = settings.trashDays == d,
                        onClick = { viewModel.setTrashDays(d) }
                    ) { RadioButton(selected = settings.trashDays == d, onClick = null) }
                }
            }

            SectionHeader("Feedback")
            SwitchRow(
                title = "Haptics",
                description = "A small vibration when you select items",
                checked = settings.haptics,
                onCheckedChange = { viewModel.setHaptics(it) }
            )
            SwitchRow(
                title = "Click sound",
                description = "Play a click when you select items",
                checked = settings.sounds,
                onCheckedChange = { viewModel.setSounds(it) }
            )

            SectionHeader("Help")
            OptionRow(title = "Show the intro tour again", selected = false, onClick = { viewModel.showIntroAgain() }) {}

            SectionHeader("About")
            Text(
                "DroidFileExplorer",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column {
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun OptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
        trailing()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Hue used when the Custom swatch is first picked. */
private val DefaultCustomArgb = hsvColor(210f, 0.8f, 0.8f).toArgb()

/**
 * Accent swatches, then a Custom swatch that opens the colour wheel. The wallpaper option
 * only appears on Android 12 and newer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(selected: String, onSelect: (String) -> Unit) {
    val customArgb = customAccentArgb(selected)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Swatch(
                brush = SolidColor(MaterialTheme.colorScheme.surfaceVariant),
                label = "Wallpaper",
                selected = selected == AppSettings.ACCENT_WALLPAPER,
                onClick = { onSelect(AppSettings.ACCENT_WALLPAPER) }
            ) {
                Text("A", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AccentPresets.forEach { preset ->
            Swatch(
                brush = SolidColor(preset.seed),
                label = preset.label,
                selected = selected == preset.id,
                onClick = { onSelect(preset.id) }
            )
        }
        Swatch(
            brush = Brush.sweepGradient(WheelHues),
            label = "Custom colour",
            selected = customArgb != null,
            onClick = { if (customArgb == null) onSelect(customAccentId(DefaultCustomArgb)) }
        )
    }
    Spacer(Modifier.height(4.dp))
    val label = when {
        selected == AppSettings.ACCENT_WALLPAPER -> "Using your wallpaper's colours"
        customArgb != null -> "Custom: #" + "%06X".format(customArgb and 0xFFFFFF)
        else -> "Accent: ${AccentPresets.firstOrNull { it.id == selected }?.label ?: "Default"}"
    }
    Text(label, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (customArgb != null) {
        CustomColorPicker(argb = customArgb, onPick = { onSelect(customAccentId(it)) })
    }
}

/** Colour wheel for hue and saturation, plus a brightness slider. Brightness is committed on release. */
@Composable
private fun CustomColorPicker(argb: Int, onPick: (Int) -> Unit) {
    val hsv = remember(argb) { FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) } }
    var brightness by remember(argb) { mutableFloatStateOf(hsv[2].coerceIn(0.2f, 1f)) }

    Spacer(Modifier.height(12.dp))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ColorWheel(
            hue = hsv[0],
            saturation = hsv[1],
            value = brightness,
            onChange = { h, s -> onPick(hsvColor(h, s, brightness).toArgb()) },
            modifier = Modifier.fillMaxWidth(0.8f)
        )
    }
    Text(
        "Drag the dot to change the colour, or press and hold on the wheel to jump there. Swiping elsewhere just scrolls.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
    Spacer(Modifier.height(12.dp))
    Text("Brightness", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Slider(
        value = brightness,
        onValueChange = { brightness = it },
        onValueChangeFinished = { onPick(hsvColor(hsv[0], hsv[1], brightness).toArgb()) },
        valueRange = 0.2f..1f
    )
}

@Composable
private fun Swatch(
    brush: Brush,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(brush)
            .border(
                BorderStroke(if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent),
                CircleShape
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
        } else {
            content()
        }
    }
}
