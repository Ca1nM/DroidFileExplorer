package com.ca1nm.droidfileexplorer.presentation.browser

import android.content.Context
import com.ca1nm.droidfileexplorer.data.FileKind
import com.ca1nm.droidfileexplorer.data.FileTypes
import com.ca1nm.droidfileexplorer.data.source.ArchiveExtractor
import com.ca1nm.droidfileexplorer.data.source.ArchiveTools
import com.ca1nm.droidfileexplorer.data.source.FileOps
import com.ca1nm.droidfileexplorer.domain.model.FileItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drives the "archive as a folder" screen: lists an archive's contents, moves between its subfolders, and
 * extracts everything, a selection, or a single file to look at. All state lives in the browser's UI state
 * ([get] / [set]); this class only holds the running job.
 */
internal class ArchiveViewController(
    private val scope: CoroutineScope,
    private val context: Context,
    private val get: () -> ArchiveViewState?,
    private val set: (ArchiveViewState?) -> Unit,
    private val message: (String) -> Unit,
    private val goTo: (String) -> Unit,
    private val open: (FileItem) -> Unit
) {
    private var job: Job? = null

    private fun edit(block: (ArchiveViewState) -> ArchiveViewState) { get()?.let { set(block(it)) } }

    /** [home] is where "Extract" puts the result; by default, the folder the archive is in. */
    fun open(path: String, home: String? = null) {
        android.util.Log.i("DFE_ARCH", "open requested for ${File(path).name}")
        job?.cancel()
        set(ArchiveViewState(archivePath = path, homeDir = home ?: File(path).parent.orEmpty()))
        job = scope.launch {
            val encrypted = withContext(Dispatchers.IO) { ArchiveTools.isEncrypted(File(path)) }
            if (encrypted) edit { it.copy(needsPassword = true) } else load(null)
        }
    }

    fun unlock(password: String) {
        job?.cancel()
        job = scope.launch { load(password) }
    }

    private suspend fun load(password: String?) {
        try {
            val file = File(get()?.archivePath ?: return)
            val t0 = System.nanoTime()
            val list = ArchiveTools.list(file, password?.ifEmpty { null })
            android.util.Log.i("DFE_ARCH", "listed ${list.size} entries of ${file.name} in ${(System.nanoTime() - t0) / 1_000_000} ms")
            // An encrypted PS3 disc dump lists fine but its files can't be read: say so up front.
            val note = if (ArchiveExtractor.isDiscImageName(file.name.lowercase())) {
                ArchiveExtractor.discNote(file, list, File(context.cacheDir, "disc_check"))
            } else null
            edit { it.copy(entries = list, needsPassword = false, password = password.orEmpty(), error = null, status = note) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "Can't read this archive"
            // An archive with encrypted file names can't even be opened without its password, and says so vaguely.
            val needsOne = password.isNullOrEmpty() && (msg.contains("codec", ignoreCase = true) || msg.contains("password", ignoreCase = true))
            edit {
                it.copy(
                    error = if (needsOne) "This archive is password-protected. Enter the password." else if (!password.isNullOrEmpty()) "Wrong password, or the archive is damaged." else msg,
                    needsPassword = it.needsPassword || needsOne
                )
            }
        }
    }

    fun enter(folder: String) = edit { it.copy(folder = folder) }

    fun up() = edit { it.copy(folder = it.folder.substringBeforeLast('/', "")) }

    /** Leaves every archive and throws away the temporary copies made for opening files and nested archives. */
    fun close() {
        job?.cancel(); job = null
        set(null)
        removeTemporaryCopies()
    }

    private fun removeTemporaryCopies() {
        scope.launch(Dispatchers.IO) { File(context.cacheDir, PREVIEW_DIR).deleteRecursively() }
    }

    /**
     * Back: stop a running job, then clear the selection, then go up a folder, then (inside a nested archive) return to
     * the archive it was opened from, and finally leave the archive.
     */
    fun back() {
        val v = get() ?: return
        when {
            v.busy -> cancel()
            v.selected.isNotEmpty() -> edit { it.copy(selected = emptySet()) }
            v.folder.isNotEmpty() -> up()
            v.parent != null -> { job?.cancel(); job = null; set(v.parent) }
            else -> close()
        }
    }

    fun cancel() { job?.cancel() }

    fun toggle(path: String) = edit { it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path) }

    fun clearSelection() = edit { it.copy(selected = emptySet()) }

    fun selectAllHere() = edit { v ->
        val here = ArchiveTools.childrenOf(v.entries.orEmpty(), v.folder).map { it.path }.toSet()
        v.copy(selected = if (v.selected.containsAll(here)) v.selected - here else v.selected + here)
    }

    private fun report(): (Long, Long) -> Unit {
        var last = 0L
        return { done, total ->
            val now = System.currentTimeMillis()
            if (total > 0 && (now - last >= 120 || done >= total)) {
                last = now
                edit { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
            }
        }
    }

    /** Runs [block] with the busy flag and progress bar on, then reports how it ended. */
    private fun run(label: String, block: suspend () -> String?) {
        if (get()?.busy == true) return
        edit { it.copy(busy = true, progress = null, status = "$label…") }
        job = scope.launch {
            try {
                val result = block()
                edit { it.copy(busy = false, progress = null, status = result) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { edit { it.copy(busy = false, progress = null, status = "Cancelled") } }
                throw e
            } catch (e: Exception) {
                if (e.message?.contains("password", ignoreCase = true) == true && get()?.password?.isNotEmpty() == true) {
                    // The password was wrong (zip lists fine without it, so this only shows when extracting): ask again.
                    edit { it.copy(busy = false, progress = null, status = null, entries = null, needsPassword = true, password = "", error = "Wrong password. Try again.") }
                } else {
                    edit { it.copy(busy = false, progress = null, status = "$label failed: ${e.message ?: e.javaClass.simpleName}") }
                }
            }
        }
    }

    /** Extracts the whole archive ([all]) or the ticked rows into a new folder beside it, then opens that folder. */
    fun extract(all: Boolean) {
        val v = get() ?: return
        val entries = v.entries ?: return
        val archive = File(v.archivePath)
        val files = entries.filter { !it.isDirectory }
        val chosen = if (all) files else files.filter { f -> v.selected.any { s -> f.path == s || f.path.startsWith("$s/") } }
        if (chosen.isEmpty()) return
        val everything = all || chosen.size == files.size
        val pw = v.password.ifEmpty { null }
        run(if (everything) "Extracting" else "Extracting ${chosen.size} file(s)") {
            // Always beside the outermost archive, even when this one is nested and lives in the cache.
            val home = v.homeDir.ifEmpty { archive.parent.orEmpty() }
            val dest = FileOps.uniqueName(File(home), ArchiveExtractor.folderNameFor(archive.name))
            val progress = report()
            val n = try {
                when {
                    !everything -> ArchiveTools.extractPaths(archive, chosen.map { it.path }.toSet(), dest, pw, progress)
                    pw != null -> ArchiveTools.extractWithPassword(archive, dest, pw, progress)
                    else -> ArchiveExtractor.extract(archive, dest, onProgress = progress) { }
                }
            } catch (t: Throwable) {
                ArchiveExtractor.removeIfEmpty(dest)
                throw t
            }
            message("Extracted $n file(s) to ${dest.name}")
            set(null)                  // leave the archive (not close(): that would cancel this very job)...
            removeTemporaryCopies()
            goTo(dest.absolutePath)    // ...and open the new folder
            null
        }
    }

    fun test() {
        val v = get() ?: return
        run("Testing") {
            val r = ArchiveTools.test(File(v.archivePath), v.password.ifEmpty { null }, report())
            (if (r.ok) "✓ " else "✗ ") + r.message
        }
    }

    /**
     * Tap on a file inside the archive: extract just that file to the cache and open it. An archive (or ISO) opens as
     * another folder view on top of this one, so Back returns here.
     */
    fun openEntry(entryPath: String, size: Long) {
        val v = get() ?: return
        val archive = File(v.archivePath)
        // Each nesting level keeps its own temporary files, so opening a file never deletes the archive being browsed.
        val level = generateSequence(v.parent) { it.parent }.count()
        run("Opening ${entryPath.substringAfterLast('/')}") {
            val root = File(context.cacheDir, "$PREVIEW_DIR/L$level")
            withContext(Dispatchers.IO) { root.deleteRecursively() }   // this level only ever holds its last opened file
            val dest = File(root, System.nanoTime().toString())
            ArchiveTools.extractEntry(archive, entryPath, dest, v.password.ifEmpty { null })
            val file = File(dest, entryPath)
            val isImage = FileTypes.ext(file.name) in setOf("bin", "img") && ArchiveExtractor.looksLikeDiscImage(file)
            if (FileTypes.kindOf(file.name) == FileKind.ARCHIVE || isImage) {
                val outer = get() ?: return@run null
                set(ArchiveViewState(archivePath = file.absolutePath, homeDir = outer.homeDir, parent = outer.copy(busy = false, progress = null, status = null)))
                val encrypted = withContext(Dispatchers.IO) { ArchiveTools.isEncrypted(file) }
                if (encrypted) edit { it.copy(needsPassword = true) } else load(null)
            } else {
                open(FileItem(file.name, file.absolutePath, false, file.length().takeIf { it > 0 } ?: size, file.lastModified()))
            }
            null
        }
    }

    private companion object {
        const val PREVIEW_DIR = "archive_preview"
    }
}
