package com.ca1nm.droidfileexplorer.data.source

import android.system.Os
import com.ca1nm.droidfileexplorer.domain.model.FileItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small file operations used across the app. Each goes through the File API when it can,
 * and through the Shizuku shell for Android/data, Android/obb and system paths.
 */
@Singleton
class FileOps @Inject constructor(
    private val saf: SafFileAccess,
    private val shizuku: ShizukuFileAccess
) {
    private fun viaShell(path: String): Boolean =
        (saf.isRestrictedPath(path) || !path.startsWith("/storage/")) && shizuku.hasPermission() &&
            !File(path).let { it.canRead() || it.parentFile?.canWrite() == true }

    fun isRestricted(path: String): Boolean = saf.isRestrictedPath(path)

    fun exists(path: String): Boolean = File(path).exists() || (shizuku.hasPermission() && shizuku.exists(path))

    fun isDirectory(path: String): Boolean = File(path).isDirectory || (viaShell(path) && shizuku.isDirectory(path))

    fun rename(from: String, to: String) {
        if (exists(to)) throw IOException("${File(to).name} already exists")
        val ok = if (viaShell(from)) shizuku.rename(from, to) else File(from).renameTo(File(to))
        if (!ok) throw IOException("Couldn't rename ${File(from).name}")
    }

    fun mkdir(path: String) {
        if (exists(path)) throw IOException("${File(path).name} already exists")
        val ok = if (viaShell(path)) shizuku.mkdirs(path) else File(path).mkdirs()
        if (!ok) throw IOException("Couldn't create folder ${File(path).name}")
    }

    fun createFile(path: String) {
        if (exists(path)) throw IOException("${File(path).name} already exists")
        val ok = if (viaShell(path)) shizuku.createFile(path) else File(path).createNewFile()
        if (!ok) throw IOException("Couldn't create ${File(path).name}")
    }

    fun openInput(path: String): InputStream =
        if (File(path).canRead()) FileInputStream(path)
        else if (saf.isRestrictedPath(path) && !shizuku.hasPermission() && saf.hasUriPermission(path)) saf.openInputStream(path)
        else if (shizuku.hasPermission()) shizuku.openInputStream(path)
        else throw IOException("No read access to ${File(path).name}")

    fun length(path: String): Long = File(path).length()

    fun setModified(path: String, millis: Long) {
        val ok = if (viaShell(path)) shizuku.setModified(path, millis) else File(path).setLastModified(millis)
        if (!ok) throw IOException("This storage doesn't allow changing the date of ${File(path).name}")
    }

    /** Symlinks are refused by the shared-storage filesystem on most phones; the error says so. */
    fun symlink(target: String, link: String) {
        try {
            Os.symlink(target, link)
        } catch (e: Exception) {
            if (shizuku.hasPermission() && shizuku.symlink(target, link)) return
            throw IOException("Symlinks aren't supported here (${e.message}). Internal storage uses a filesystem that doesn't allow them.")
        }
    }

    /** Lists a folder through whichever backend works, for recursive tools. */
    fun list(path: String): List<FileItem> {
        val f = File(path)
        val children = f.listFiles()
        if (children != null) return children.map {
            FileItem(it.name, it.absolutePath, it.isDirectory, if (it.isFile) it.length() else 0L, it.lastModified())
        }
        if (shizuku.hasPermission()) return shizuku.listDirectory(path)
        if (saf.hasUriPermission(path)) return saf.listDirectory(path)
        return emptyList()
    }

    /** Walks [root] depth-first. [onFile] gets every file; folders are passed to [onDir] first. */
    suspend fun walk(
        root: String,
        includeHidden: Boolean = true,
        /** Skip Android/data and Android/obb; reading them goes through Shizuku and is slow. */
        skipRestricted: Boolean = false,
        onDir: (FileItem) -> Unit = {},
        onFile: (FileItem) -> Unit
    ) {
        val stack = ArrayDeque<String>().apply { add(root) }
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            for (item in list(dir)) {
                if (!includeHidden && item.name.startsWith(".")) continue
                if (skipRestricted && saf.isRestrictedPath(item.path)) continue
                if (item.isDirectory) {
                    onDir(item)
                    stack.add(item.path)
                } else onFile(item)
            }
        }
    }

    data class FolderStats(val bytes: Long, val files: Int, val folders: Int)

    suspend fun folderStats(path: String): FolderStats {
        var bytes = 0L; var files = 0; var folders = 0
        walk(path, skipRestricted = !saf.isRestrictedPath(path), onDir = { folders++ }) { bytes += it.size; files++ }
        return FolderStats(bytes, files, folders)
    }

    suspend fun checksum(path: String, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        openInput(path).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Overwrites the file's bytes with random data, then deletes it. */
    suspend fun secureDelete(path: String) {
        val f = File(path)
        if (f.isDirectory) {
            f.listFiles()?.forEach { secureDelete(it.absolutePath) }
            if (!f.delete()) throw IOException("Couldn't remove folder ${f.name}")
            return
        }
        val rnd = SecureRandom()
        RandomAccessFile(f, "rws").use { raf ->
            val len = raf.length()
            val buf = ByteArray(64 * 1024)
            var written = 0L
            raf.seek(0)
            while (written < len) {
                currentCoroutineContext().ensureActive()
                rnd.nextBytes(buf)
                val n = minOf(buf.size.toLong(), len - written).toInt()
                raf.write(buf, 0, n)
                written += n
            }
            raf.fd.sync()
        }
        if (!f.delete()) throw IOException("Overwrote ${f.name} but couldn't delete it")
    }

    /** Splits into name.001, name.002 ... beside the original. Returns the part paths. */
    suspend fun split(path: String, partBytes: Long, onPart: (String) -> Unit = {}): List<String> {
        require(partBytes > 0)
        val src = File(path)
        val parts = mutableListOf<String>()
        openInput(path).use { input ->
            val buf = ByteArray(64 * 1024)
            var index = 1
            var pending: Int = input.read(buf)
            while (pending > 0) {
                val part = File(src.parentFile, "${src.name}.%03d".format(index++))
                part.outputStream().buffered().use { out ->
                    var inPart = 0L
                    while (pending > 0 && inPart < partBytes) {
                        currentCoroutineContext().ensureActive()
                        val take = minOf(pending.toLong(), partBytes - inPart).toInt()
                        out.write(buf, 0, take)
                        inPart += take
                        if (take < pending) {
                            System.arraycopy(buf, take, buf, 0, pending - take)
                            pending -= take
                        } else pending = input.read(buf)
                    }
                }
                parts += part.absolutePath
                onPart(part.name)
            }
        }
        return parts
    }

    /** Joins name.001, name.002 ... (pass any part) into the original name. */
    suspend fun merge(anyPart: String): String {
        val first = File(anyPart)
        val base = first.name.substringBeforeLast('.')
        val dir = first.parentFile ?: throw IOException("No folder")
        val parts = (dir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("$base.") && it.name.substringAfterLast('.').matches(Regex("\\d{3}")) }
            .sortedBy { it.name }
        if (parts.isEmpty()) throw IOException("No parts found")
        var out = File(dir, base)
        var n = 2
        while (out.exists()) out = File(dir, "${base.substringBeforeLast('.', base)} ($n)" +
            (if (base.contains('.')) "." + base.substringAfterLast('.') else "")).also { n++ }
        out.outputStream().buffered().use { o ->
            for (p in parts) {
                currentCoroutineContext().ensureActive()
                p.inputStream().use { it.copyTo(o) }
            }
        }
        return out.absolutePath
    }

    data class CompareResult(
        val identical: Boolean,
        val summary: String,
        val onlyInA: List<String> = emptyList(),
        val onlyInB: List<String> = emptyList(),
        val different: List<String> = emptyList(),
        val textDiff: List<DiffLine> = emptyList()
    )

    data class DiffLine(val kind: Char, val text: String)

    suspend fun compare(a: String, b: String): CompareResult {
        val aDir = isDirectory(a); val bDir = isDirectory(b)
        if (aDir != bDir) return CompareResult(false, "One is a folder and the other is a file")
        if (!aDir) {
            val sa = File(a).length(); val sb = File(b).length()
            val same = sa == sb && checksum(a, "SHA-256") == checksum(b, "SHA-256")
            val diff = if (!same && sa < 512 * 1024 && sb < 512 * 1024 && looksText(a) && looksText(b))
                lineDiff(File(a).readLines(), File(b).readLines()) else emptyList()
            return CompareResult(
                same,
                if (same) "Files are identical (${sa} bytes, same SHA-256)"
                else "Files differ: ${sa} vs ${sb} bytes",
                textDiff = diff
            )
        }
        fun index(root: String): Map<String, Long> {
            val m = HashMap<String, Long>()
            File(root).walkTopDown().filter { it.isFile }.forEach { m[it.absolutePath.removePrefix(root)] = it.length() }
            return m
        }
        val ia = index(a); val ib = index(b)
        val onlyA = (ia.keys - ib.keys).sorted()
        val onlyB = (ib.keys - ia.keys).sorted()
        val changed = ia.keys.intersect(ib.keys).filter { k ->
            ia[k] != ib[k] || checksum(a + k, "MD5") != checksum(b + k, "MD5")
        }.sorted()
        val same = onlyA.isEmpty() && onlyB.isEmpty() && changed.isEmpty()
        return CompareResult(
            same,
            if (same) "Folders match (${ia.size} files)"
            else "${onlyA.size} only in first, ${onlyB.size} only in second, ${changed.size} different",
            onlyA, onlyB, changed
        )
    }

    private fun looksText(path: String): Boolean = runCatching {
        File(path).inputStream().use { s -> val b = ByteArray(4096); val n = s.read(b); (0 until maxOf(n, 0)).none { b[it] == 0.toByte() } }
    }.getOrDefault(false)

    /** Longest-common-subsequence line diff; inputs are capped so it stays fast. */
    private fun lineDiff(a: List<String>, b: List<String>): List<DiffLine> {
        val x = a.take(2000); val y = b.take(2000)
        val dp = Array(x.size + 1) { IntArray(y.size + 1) }
        for (i in x.indices.reversed()) for (j in y.indices.reversed())
            dp[i][j] = if (x[i] == y[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val out = mutableListOf<DiffLine>()
        var i = 0; var j = 0
        while (i < x.size && j < y.size) {
            when {
                x[i] == y[j] -> { out += DiffLine(' ', x[i]); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out += DiffLine('-', x[i]); i++ }
                else -> { out += DiffLine('+', y[j]); j++ }
            }
        }
        while (i < x.size) out += DiffLine('-', x[i++])
        while (j < y.size) out += DiffLine('+', y[j++])
        return out
    }

    companion object {
        /** "Photo.jpg" -> "Photo (2).jpg" until the name is free. */
        fun uniqueName(dir: File, name: String): File {
            var f = File(dir, name)
            if (!f.exists()) return f
            val base = name.substringBeforeLast('.', name)
            val ext = if (name.contains('.') && !name.startsWith(".")) "." + name.substringAfterLast('.') else ""
            var n = 2
            while (f.exists()) f = File(dir, "$base ($n)$ext").also { n++ }
            return f
        }
    }
}
