package com.sza.androidfolderexplorer.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FolderView(val grid: Boolean = false, val sortBy: String = "NAME", val descending: Boolean = false)

@Serializable
data class CustomAction(
    val name: String,
    /** Shell command. {path} {name} {dir} {base} {ext} are replaced per selected file, shell-quoted. */
    val command: String,
    val useShizuku: Boolean = false
)

@Serializable
data class NetworkConnection(
    val id: String,
    val name: String,
    /** sftp, ftp, smb, webdav */
    val protocol: String,
    val host: String,
    val port: Int,
    val user: String = "",
    val password: String = "",
    /** Start folder; for SMB this starts with the share name. */
    val path: String = "/"
)

@Serializable
data class SyncRule(
    val id: String,
    val source: String,
    val destination: String,
    /** Delete files in destination that no longer exist in source. */
    val mirrorDeletes: Boolean = false,
    val intervalHours: Int = 24,
    val enabled: Boolean = true,
    val lastRun: Long = 0L,
    val lastResult: String = ""
)

@Serializable
data class AutoSortRule(
    val id: String,
    val folder: String,
    /** Move new files into Images/, Video/, Audio/, Documents/, Archives/, APKs/ subfolders. */
    val sortByType: Boolean = true,
    /** Delete (to trash) files older than this many days; 0 = off. */
    val deleteOlderThanDays: Int = 0,
    val enabled: Boolean = true,
    val lastResult: String = ""
)

@Serializable
data class RecentEntry(val path: String, val isDirectory: Boolean, val time: Long)

@Serializable
data class UserDataState(
    val favourites: List<String> = emptyList(),
    /** path -> tags */
    val tags: Map<String, List<String>> = emptyMap(),
    val recents: List<RecentEntry> = emptyList(),
    val folderViews: Map<String, FolderView> = emptyMap(),
    val customActions: List<CustomAction> = listOf(
        CustomAction("Show file info (stat)", "stat {path}"),
        CustomAction("Line count", "wc -l {path}")
    ),
    val connections: List<NetworkConnection> = emptyList(),
    val syncRules: List<SyncRule> = emptyList(),
    val autoSortRules: List<AutoSortRule> = emptyList(),
    /** Document-provider trees (SAF URIs) the user has added, e.g. Dropbox, OneDrive. */
    val providerTrees: List<String> = emptyList()
)

/** Everything the user creates in the app besides settings: stars, tags, recents, rules. */
@Singleton
class UserData @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("afex_user", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<UserDataState> = _state.asStateFlow()

    private fun load(): UserDataState = prefs.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString<UserDataState>(it) }.getOrNull() }
        ?: UserDataState()

    @Synchronized
    fun update(transform: (UserDataState) -> UserDataState) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit().putString(KEY, json.encodeToString(next)).apply()
    }

    fun exportJson(): String = json.encodeToString(_state.value)
    fun importJson(text: String) {
        val parsed = json.decodeFromString<UserDataState>(text)
        update { parsed }
    }

    fun isFavourite(path: String) = path in _state.value.favourites
    fun toggleFavourite(paths: Collection<String>) = update { s ->
        val allFav = paths.all { it in s.favourites }
        s.copy(favourites = if (allFav) s.favourites - paths.toSet() else (s.favourites + paths).distinct())
    }

    fun setTags(path: String, tags: List<String>) = update { s ->
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        s.copy(tags = if (clean.isEmpty()) s.tags - path else s.tags + (path to clean))
    }

    fun addTag(paths: Collection<String>, tag: String) = update { s ->
        val t = tag.trim()
        if (t.isEmpty()) s else s.copy(tags = s.tags + paths.associateWith { ((s.tags[it] ?: emptyList()) + t).distinct() })
    }

    fun removeTag(paths: Collection<String>, tag: String) = update { s ->
        val next = s.tags.toMutableMap()
        for (p in paths) {
            val left = (next[p] ?: continue) - tag
            if (left.isEmpty()) next.remove(p) else next[p] = left
        }
        s.copy(tags = next)
    }

    fun allTags(): List<String> = _state.value.tags.values.flatten().distinct().sorted()

    fun addRecent(path: String, isDirectory: Boolean) = update { s ->
        val list = listOf(RecentEntry(path, isDirectory, System.currentTimeMillis())) +
            s.recents.filter { it.path != path }
        s.copy(recents = list.take(MAX_RECENTS))
    }

    fun clearRecents() = update { it.copy(recents = emptyList()) }

    fun folderView(path: String): FolderView? = _state.value.folderViews[path]
    fun setFolderView(path: String, view: FolderView) = update { it.copy(folderViews = it.folderViews + (path to view)) }

    /** Keeps stars, tags and recents attached to a file after the app moves or renames it. */
    fun onPathChanged(old: String, new: String) = update { s ->
        fun remap(p: String) = when {
            p == old -> new
            p.startsWith("$old/") -> new + p.removePrefix(old)
            else -> p
        }
        s.copy(
            favourites = s.favourites.map(::remap),
            tags = s.tags.mapKeys { remap(it.key) },
            recents = s.recents.map { it.copy(path = remap(it.path)) },
            folderViews = s.folderViews.mapKeys { remap(it.key) }
        )
    }

    companion object {
        private const val KEY = "user_data"
        private const val MAX_RECENTS = 60
    }
}
