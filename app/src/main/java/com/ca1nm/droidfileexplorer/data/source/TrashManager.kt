package com.ca1nm.droidfileexplorer.data.source

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class TrashEntry(
    val id: String,
    val originalPath: String,
    val name: String,
    val deletedAt: Long,
    val size: Long,
    val isDirectory: Boolean
) {
    val trashedPath: String get() = "${TrashManager.TRASH_DIR}/$id/$name"
}

/**
 * Deleted items are renamed into /storage/emulated/0/.DFE-Trash/<id>/<name>. A rename on the
 * same volume is instant, so trashing large folders costs nothing. Items older than the
 * configured number of days are removed for good by [purgeExpired].
 */
@Singleton
class TrashManager @Inject constructor(
    private val shizuku: ShizukuFileAccess,
    private val saf: SafFileAccess
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val indexFile get() = File(TRASH_DIR, "index.json")
    private val _entries = MutableStateFlow<List<TrashEntry>>(emptyList())
    val entries: StateFlow<List<TrashEntry>> = _entries.asStateFlow()

    init { runCatching { _entries.value = load() } }

    private fun load(): List<TrashEntry> =
        if (indexFile.exists()) json.decodeFromString(indexFile.readText()) else emptyList()

    private fun save(list: List<TrashEntry>) {
        File(TRASH_DIR).mkdirs()
        File(TRASH_DIR, ".nomedia").takeIf { !it.exists() }?.createNewFile()
        indexFile.writeText(json.encodeToString(list))
        _entries.value = list
    }

    /** Moves each path to the trash. Returns the paths that could not be trashed. */
    suspend fun trash(paths: List<String>): Pair<List<TrashEntry>, List<String>> = withContext(Dispatchers.IO) {
        val added = mutableListOf<TrashEntry>()
        val failed = mutableListOf<String>()
        for (path in paths) {
            if (path.startsWith(TRASH_DIR)) { failed += path; continue }
            val src = File(path)
            val id = System.currentTimeMillis().toString(36) + (0..9999).random().toString(36)
            val dir = File(TRASH_DIR, id)
            val restricted = saf.isRestrictedPath(path) || !path.startsWith("/storage/emulated/0")
            val size = if (src.isFile) src.length() else 0L
            val isDir = src.isDirectory || (restricted && shizuku.hasPermission() && shizuku.isDirectory(path))
            dir.mkdirs()
            val target = File(dir, src.name)
            val ok = when {
                !restricted -> src.renameTo(target)
                shizuku.hasPermission() -> shizuku.rename(path, target.absolutePath)
                else -> false
            }
            if (ok) {
                added += TrashEntry(id, path, src.name, System.currentTimeMillis(), size, isDir)
            } else {
                dir.delete()
                failed += path
            }
        }
        if (added.isNotEmpty()) save(added + _entries.value)
        added to failed
    }

    /** What happened to each item in a restore: put back, no longer in the trash folder, or failed. */
    data class RestoreResult(val restored: List<String>, val missing: List<TrashEntry>, val failed: List<TrashEntry>)

    /** True if the item's data is still in the trash folder. */
    private fun present(e: TrashEntry): Boolean =
        File(e.trashedPath).exists() || java.nio.file.Files.isSymbolicLink(File(e.trashedPath).toPath())

    /**
     * Drops list entries whose data is no longer in the trash folder, for example because the
     * folder was deleted outside the trash. Returns the entries removed.
     */
    suspend fun prune(): List<TrashEntry> = withContext(Dispatchers.IO) {
        runCatching { _entries.value = load() }
        val gone = _entries.value.filter { !present(it) }
        if (gone.isNotEmpty()) save(_entries.value.filter { present(it) })
        gone
    }

    suspend fun restore(entries: List<TrashEntry>): RestoreResult = withContext(Dispatchers.IO) {
        val restored = mutableListOf<String>()
        val missing = mutableListOf<TrashEntry>()
        val failed = mutableListOf<TrashEntry>()
        val remaining = _entries.value.toMutableList()
        for (e in entries) {
            if (!present(e)) {
                // Nothing to put back. Say so, and stop listing an item that can never be restored.
                missing += e
                remaining.removeAll { it.id == e.id }
                continue
            }
            val original = File(e.originalPath)
            original.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs() && shizuku.hasPermission()) shizuku.mkdirs(parent.absolutePath)
            }
            val dest = if (original.exists()) FileOps.uniqueName(original.parentFile!!, original.name) else original
            val restricted = saf.isRestrictedPath(dest.absolutePath)
            val ok = if (!restricted) File(e.trashedPath).renameTo(dest)
                     else shizuku.hasPermission() && shizuku.rename(e.trashedPath, dest.absolutePath)
            if (ok) {
                File(TRASH_DIR, e.id).delete()
                remaining.removeAll { it.id == e.id }
                restored += dest.absolutePath
            } else {
                failed += e
                Log.w("DFE_TRASH", "restore failed for ${e.originalPath}")
            }
        }
        save(remaining)
        RestoreResult(restored, missing, failed)
    }

    suspend fun deleteForever(entries: List<TrashEntry>) = withContext(Dispatchers.IO) {
        val ids = entries.map { it.id }.toSet()
        entries.forEach { File(TRASH_DIR, it.id).deleteRecursively() }
        save(_entries.value.filter { it.id !in ids })
    }

    suspend fun empty() = deleteForever(_entries.value)

    suspend fun purgeExpired(days: Int) = withContext(Dispatchers.IO) {
        runCatching {
            _entries.value = load()
            val cutoff = System.currentTimeMillis() - days * 86_400_000L
            val expired = _entries.value.filter { it.deletedAt < cutoff }
            if (expired.isNotEmpty()) deleteForever(expired)
        }
    }

    fun totalSize(): Long = _entries.value.sumOf { e ->
        if (e.isDirectory) File(e.trashedPath).walkTopDown().filter { it.isFile }.sumOf { it.length() } else e.size
    }

    companion object {
        const val TRASH_DIR = "/storage/emulated/0/.DFE-Trash"
        fun requireNotTrash(path: String) { if (path.startsWith(TRASH_DIR)) throw IOException("Already in trash") }
    }
}
