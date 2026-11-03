package com.ca1nm.droidfileexplorer.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** [ACCENT_WALLPAPER] or the id of a preset in ui/theme/Accent.kt. */
    val accent: String = AppSettings.ACCENT_WALLPAPER,
    /** Use pure black backgrounds in dark theme. */
    val blackDark: Boolean = false,
    /** List dotfiles and dot-folders such as .config. */
    val showHidden: Boolean = false,
    /** Show image, video and APK previews instead of type icons. */
    val showThumbnails: Boolean = true,
    /** Play GIFs and animated WebP in their thumbnails; [animationLoop] repeats forever, [animationMaxMb] caps the file size. */
    val animateThumbnails: Boolean = true,
    val animationLoop: Boolean = true,
    val animationMaxMb: Int = 40,   // 0 = no limit
    /** Colour code, scripts, config, web and log files in the text editor. */
    val syntaxColours: Boolean = true,
    /** What happens when another app (a browser's "Open") hands over a file: [AppSettings.FROM_APP_LOCATE] or [AppSettings.FROM_APP_OPEN]. */
    val openFromApp: String = AppSettings.FROM_APP_LOCATE,
    /** Tapping a file opens it; when off, tapping selects it (the original behaviour). */
    val tapOpens: Boolean = true,
    /** Delete moves to the in-app trash; items older than [trashDays] are purged. */
    val useTrash: Boolean = true,
    val trashDays: Int = 30,
    val haptics: Boolean = true,
    val sounds: Boolean = false,
    /** Two folders side by side when the window is wide (unfolded screen, DeX). */
    val dualPane: Boolean = true,
    val onboardingDone: Boolean = false,
    /** Log directory loads slower than this to the diagnostics log. */
    val slowLoadMs: Int = 700
)

/** Persists user settings in SharedPreferences and exposes them as a StateFlow. */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()

    init { ThumbOptions.apply(_state.value); EditorOptions.apply(_state.value) }

    fun setThemeMode(mode: ThemeMode) = update(_state.value.copy(themeMode = mode)) { putString(KEY_THEME_MODE, mode.name) }
    fun setAccent(accent: String) = update(_state.value.copy(accent = accent)) { putString(KEY_ACCENT, accent) }
    fun setBlackDark(enabled: Boolean) = update(_state.value.copy(blackDark = enabled)) { putBoolean(KEY_BLACK_DARK, enabled) }
    fun setShowHidden(show: Boolean) = update(_state.value.copy(showHidden = show)) { putBoolean(KEY_SHOW_HIDDEN, show) }
    fun setShowThumbnails(v: Boolean) = update(_state.value.copy(showThumbnails = v)) { putBoolean("thumbnails", v) }
    fun setAnimateThumbnails(v: Boolean) = update(_state.value.copy(animateThumbnails = v)) { putBoolean("animate_thumbs", v) }
    fun setAnimationLoop(v: Boolean) = update(_state.value.copy(animationLoop = v)) { putBoolean("animation_loop", v) }
    fun setAnimationMaxMb(v: Int) = update(_state.value.copy(animationMaxMb = v)) { putInt("animation_max_mb", v) }
    fun setSyntaxColours(v: Boolean) = update(_state.value.copy(syntaxColours = v)) { putBoolean("syntax_colours", v) }
    fun setOpenFromApp(v: String) = update(_state.value.copy(openFromApp = v)) { putString("open_from_app", v) }
    fun setTapOpens(v: Boolean) = update(_state.value.copy(tapOpens = v)) { putBoolean("tap_opens", v) }
    fun setUseTrash(v: Boolean) = update(_state.value.copy(useTrash = v)) { putBoolean("use_trash", v) }
    fun setTrashDays(v: Int) = update(_state.value.copy(trashDays = v)) { putInt("trash_days", v) }
    fun setHaptics(v: Boolean) = update(_state.value.copy(haptics = v)) { putBoolean("haptics", v) }
    fun setSounds(v: Boolean) = update(_state.value.copy(sounds = v)) { putBoolean("sounds", v) }
    fun setDualPane(v: Boolean) = update(_state.value.copy(dualPane = v)) { putBoolean("dual_pane", v) }
    fun setOnboardingDone(v: Boolean) = update(_state.value.copy(onboardingDone = v)) { putBoolean("onboarding_done", v) }

    private inline fun update(new: Settings, write: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(write).apply()
        _state.value = new
        ThumbOptions.apply(new)
        EditorOptions.apply(new)
    }

    private fun load(): Settings = Settings(
        themeMode = prefs.getString(KEY_THEME_MODE, null)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM,
        accent = prefs.getString(KEY_ACCENT, ACCENT_WALLPAPER) ?: ACCENT_WALLPAPER,
        blackDark = prefs.getBoolean(KEY_BLACK_DARK, false),
        showHidden = prefs.getBoolean(KEY_SHOW_HIDDEN, false),
        showThumbnails = prefs.getBoolean("thumbnails", true),
        animateThumbnails = prefs.getBoolean("animate_thumbs", true),
        animationLoop = prefs.getBoolean("animation_loop", true),
        animationMaxMb = prefs.getInt("animation_max_mb", 40),
        syntaxColours = prefs.getBoolean("syntax_colours", true),
        openFromApp = prefs.getString("open_from_app", FROM_APP_LOCATE)?.takeIf { it == FROM_APP_OPEN } ?: FROM_APP_LOCATE,
        tapOpens = prefs.getBoolean("tap_opens", true),
        useTrash = prefs.getBoolean("use_trash", true),
        trashDays = prefs.getInt("trash_days", 30),
        haptics = prefs.getBoolean("haptics", true),
        sounds = prefs.getBoolean("sounds", false),
        dualPane = prefs.getBoolean("dual_pane", true),
        onboardingDone = prefs.getBoolean("onboarding_done", false)
    )

    /** Every key in the settings file, for backup. */
    fun exportJson(): JSONObject = JSONObject().apply {
        prefs.all.forEach { (k, v) -> put(k, v) }
    }

    fun importJson(obj: JSONObject) {
        val e = prefs.edit()
        obj.keys().forEach { k ->
            when (val v = obj.get(k)) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Double -> e.putFloat(k, v.toFloat())
                is String -> e.putString(k, v)
            }
        }
        e.commit()
        _state.value = load()
        ThumbOptions.apply(_state.value)
        EditorOptions.apply(_state.value)
    }

    companion object {
        const val ACCENT_WALLPAPER = "wallpaper"
        /** A file another app opens: show its folder with the file scrolled to and highlighted (default)... */
        const val FROM_APP_LOCATE = "locate"
        /** ...or open the file straight away (its folder is shown behind it). */
        const val FROM_APP_OPEN = "open"
        /** Accent ids that start with this prefix hold a custom colour as "custom:AARRGGBB". */
        const val ACCENT_CUSTOM_PREFIX = "custom:"
        // "dfe_prefs" and "show_hidden" match the keys the browser already used.
        const val PREFS = "dfe_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ACCENT = "accent"
        private const val KEY_BLACK_DARK = "black_dark"
        private const val KEY_SHOW_HIDDEN = "show_hidden"
    }
}
