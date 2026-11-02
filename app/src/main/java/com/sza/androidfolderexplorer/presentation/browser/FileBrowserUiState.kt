package com.sza.androidfolderexplorer.presentation.browser

import com.sza.androidfolderexplorer.domain.model.FileItem
import com.sza.androidfolderexplorer.domain.model.OperationProgress

data class FileBrowserUiState(
    val currentPath: String = DEFAULT_PATH,
    val items: List<FileItem> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val operation: OperationState? = null,
    val pathSegments: List<PathSegment> = emptyList(),
    val accessPrompt: AccessPromptState? = null,
    val pendingDelete: Boolean = false,
    val quickFolders: List<QuickFolder> = emptyList(),
    val showQuickAccess: Boolean = false,
    /** Settings: list dotfiles and dot-folders alongside the rest. */
    val showHidden: Boolean = false,
    /** Items staged by Copy or Move, waiting to be pasted into the folder on screen. */
    val clipboard: ClipboardState? = null,
    /** Path of the text file open in the editor, or null when the editor is closed. */
    val editorPath: String? = null,
    /** Images in the folder on screen, and which one the viewer opens on; null when closed. */
    val viewerPaths: List<String>? = null,
    val viewerIndex: Int = 0,
    val sortBy: SortBy = SortBy.NAME,
    val sortDescending: Boolean = false,
    val filter: TypeFilter = TypeFilter.ALL,
    /** Modal the browser is showing (rename, properties, tags ...), or null. */
    val dialog: BrowserDialog? = null,
    /** One-line message with an optional Undo button. */
    val snackbar: SnackbarMessage? = null,
    /** Name filter for the folder on screen; empty = off. */
    val query: String = "",
    val searchOpen: Boolean = false,
    val gridView: Boolean = false,
    val tagFilter: String? = null,
    val favouritesOnly: Boolean = false,
    val quick: QuickFilter? = null,
    /** Results of a recursive search, or null when none is shown. */
    val searchResults: List<FileItem>? = null,
    val searchRunning: Boolean = false,
    val showThumbnails: Boolean = true,
    val tapOpens: Boolean = true,
    val useTrash: Boolean = true,
    val haptics: Boolean = true,
    val sounds: Boolean = false,
    val dualPane: Boolean = true,
    val showOnboarding: Boolean = false
) {
    val hasSelection: Boolean get() = selectedPaths.isNotEmpty()
    val allSelected: Boolean get() = items.isNotEmpty() && selectedPaths.size == items.size

    /** True only for folders below the storage root; the root itself is the top level. */
    val canGoUp: Boolean get() = currentPath != "/" && currentPath != STORAGE_ROOT && currentPath.isNotEmpty()

    companion object {
        const val STORAGE_ROOT = "/storage/emulated/0"
        const val DEFAULT_PATH = "/storage/emulated/0/Android"
    }
}

/** One-tap filters shown as chips under the toolbar. */
enum class QuickFilter(val label: String) {
    TODAY("Today"), WEEK("This week"), OVER_1GB("Over 1 GB"), UNDER_1GB("Under 1 GB"), SCREENSHOTS("Screenshots");

    fun accepts(item: com.sza.androidfolderexplorer.domain.model.FileItem, now: Long = System.currentTimeMillis()): Boolean {
        if (item.isDirectory) return false
        val dayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return when (this) {
            TODAY -> item.lastModified >= dayStart
            WEEK -> item.lastModified >= now - 7L * 86_400_000L
            OVER_1GB -> item.size >= 1024L * 1024 * 1024
            UNDER_1GB -> item.size < 1024L * 1024 * 1024
            SCREENSHOTS -> com.sza.androidfolderexplorer.data.FileTypes.isImage(item.name) &&
                (item.path.contains("screenshot", ignoreCase = true) || item.name.startsWith("Screenshot", ignoreCase = true))
        }
    }
}

enum class SortBy(val label: String) { NAME("Name"), DATE("Date"), SIZE("Size"), TYPE("Type") }

enum class TypeFilter(val label: String) {
    ALL("All"), FOLDERS("Folders"), IMAGES("Images"), VIDEO("Video"), AUDIO("Audio"), ARCHIVES("Archives"), DOCS("Documents");

    /** Folders stay visible under every type filter (so you can keep navigating) except FOLDERS-only hides files. */
    fun accepts(item: com.sza.androidfolderexplorer.domain.model.FileItem): Boolean {
        if (item.isDirectory) return true
        val ext = item.name.substringAfterLast('.', "").lowercase()
        return when (this) {
            ALL -> true
            FOLDERS -> false
            IMAGES -> ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
            VIDEO -> ext in setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v")
            AUDIO -> ext in setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus")
            ARCHIVES -> ext in setOf("zip", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso", "rar")
            DOCS -> ext in setOf("pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "json", "xml")
        }
    }
}

/** Everything the browser can show as a modal. Each dialog keeps its own working state. */
sealed interface BrowserDialog {
    data class Rename(val path: String) : BrowserDialog
    data class NewItem(val folder: Boolean) : BrowserDialog
    data class Properties(val path: String) : BrowserDialog
    data class Compress(val paths: List<String>) : BrowserDialog
    data class Tags(val paths: List<String>) : BrowserDialog
    data class Checksum(val path: String) : BrowserDialog
    data class SetDate(val path: String) : BrowserDialog
    data class Split(val path: String) : BrowserDialog
    data class Compare(val a: String, val b: String) : BrowserDialog
    data class ArchiveBrowser(val path: String) : BrowserDialog
    data class BulkRename(val paths: List<String>) : BrowserDialog
    data class ImageBatch(val paths: List<String>) : BrowserDialog
    data class Actions(val paths: List<String>) : BrowserDialog
    data class OpenAs(val path: String) : BrowserDialog
    data class ApkInfo(val path: String) : BrowserDialog
    data class Symlink(val path: String) : BrowserDialog
    data class SecureDelete(val paths: List<String>) : BrowserDialog
    data class FolderSize(val path: String) : BrowserDialog
    data class Viewer(val path: String, val kind: FileKindHint) : BrowserDialog
    data object RecursiveSearch : BrowserDialog
    data object Recents : BrowserDialog
}

/** Which built-in viewer to force on a file ("Open as"). */
enum class FileKindHint { TEXT, MARKDOWN, JSON, XML, CSV, LOG, HEX, PDF, AUDIO_TAGS, EXIF }

data class SnackbarMessage(
    val text: String,
    val undo: (suspend () -> Unit)? = null,
    val id: Long = System.nanoTime()
)

data class PathSegment(val name: String, val fullPath: String)

data class QuickFolder(val name: String, val path: String, val isCustom: Boolean)

data class ClipboardState(val paths: List<String>, val isMove: Boolean)

sealed interface OperationState {
    data class InProgress(val progress: OperationProgress) : OperationState
    data class Completed(val message: String) : OperationState
    data class Failed(val error: String) : OperationState
}

/**
 * Represents what access permission the user needs to grant for a restricted path.
 * The path is stored so it can be retried after the user grants access.
 */
sealed interface AccessPromptState {
    val path: String
    /** Android 11-13: grant MANAGE_EXTERNAL_STORAGE ("All files access") in Settings */
    data class ManageStorageRequired(override val path: String) : AccessPromptState
    /** Legacy SAF fallback (not currently used as primary flow) */
    data class SafRequired(override val path: String) : AccessPromptState
    /** Android 14+: Shizuku service running, needs permission grant */
    data class ShizukuPermissionRequired(override val path: String) : AccessPromptState
    /** Android 14+: Shizuku APK installed but service is not running — user must start it */
    data class ShizukuNotRunning(override val path: String) : AccessPromptState
    /** Android 14+: Shizuku APK not installed — show install options */
    data class ShizukuNotInstalled(override val path: String) : AccessPromptState
}
