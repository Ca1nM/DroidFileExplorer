package com.sza.androidfolderexplorer.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

data class ArchiveEntryInfo(val path: String, val isDirectory: Boolean, val size: Long)

/**
 * Archive features beyond plain extraction: creating zips (optionally AES-encrypted),
 * browsing an archive's contents as folders, extracting a single entry, password-protected
 * zips, and an integrity test. Zip handling uses zip4j; tar and 7z use commons-compress.
 */
object ArchiveTools {

    private fun zip(file: File, password: String?) =
        if (password.isNullOrEmpty()) ZipFile(file) else ZipFile(file, password.toCharArray())

    /** Creates [dest] from [sources] (files and folders). Returns the number of top-level items added. */
    suspend fun createZip(
        sources: List<File>,
        dest: File,
        password: String?,
        onItem: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        if (dest.exists()) dest.delete()
        val z = zip(dest, password)
        val params = ZipParameters().apply {
            if (!password.isNullOrEmpty()) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        try {
            for (src in sources) {
                currentCoroutineContext().ensureActive()
                onItem(src.name)
                if (src.isDirectory) z.addFolder(src, params) else z.addFile(src, params)
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
        sources.size
    }

    fun isEncryptedZip(archive: File): Boolean =
        archive.name.endsWith(".zip", true) && (
            // A direct read of the zip directory is far faster than any library; the libraries stay as fallbacks.
            ZipDirectory.read(archive)?.any { it.encrypted } ?: runCatching {
                org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(archive).get().use { z ->
                    val entries = z.entries
                    var found = false
                    while (!found && entries.hasMoreElements()) found = entries.nextElement().generalPurposeBit.usesEncryption()
                    found
                }
            }.getOrElse { runCatching { ZipFile(archive).isEncrypted }.getOrDefault(false) }
        )

    /** The entries of a zip via commons-compress (fast, no password needed to list), or null if it can't read it. */
    private fun fastZipList(archive: File): List<ArchiveEntryInfo>? =
        ZipDirectory.read(archive)?.map { ArchiveEntryInfo(it.name.trimEnd('/'), it.isDirectory, it.size) } ?: slowZipList(archive)

    private fun slowZipList(archive: File): List<ArchiveEntryInfo>? = runCatching {
        org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(archive).get().use { z ->
            java.util.Collections.list(z.entries).map { ArchiveEntryInfo(it.name.trimEnd('/'), it.isDirectory, maxOf(it.size, 0L)) }
        }
    }.getOrNull()

    /** True for a password-protected zip or RAR. */
    fun isEncrypted(archive: File): Boolean =
        if (RarSupport.handles(archive.name)) RarSupport.isEncrypted(archive) else isEncryptedZip(archive)

    /** Extracts the whole archive with a password. Zip and RAR. */
    suspend fun extractWithPassword(
        archive: File,
        destDir: File,
        password: String,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): Int =
        if (RarSupport.handles(archive.name)) withContext(Dispatchers.IO) {
            RarSupport.extract(archive, destDir, password, null, {}, onProgress)
        }
        else extractZipWithPassword(archive, destDir, password)

    /** Lists every entry. Works for zip, 7z, RAR, tar variants and ISO 9660 images. */
    suspend fun list(archive: File, password: String? = null): List<ArchiveEntryInfo> = withContext(Dispatchers.IO) {
        val lower = archive.name.lowercase()
        when {
            RarSupport.handles(archive.name) -> RarSupport.list(archive, password)
            lower.endsWith(".zip") -> fastZipList(archive) ?: zip(archive, password).fileHeaders.map {
                ArchiveEntryInfo(it.fileName.trimEnd('/'), it.isDirectory, it.uncompressedSize)
            }
            lower.endsWith(".7z") -> SevenZFile.builder().setFile(archive).get().use { sz ->
                sz.entries.map { ArchiveEntryInfo(it.name.trimEnd('/'), it.isDirectory, it.size) }
            }
            ArchiveExtractor.isDiscImageName(lower) -> ArchiveExtractor.listIso(archive)
            else -> {
                val out = mutableListOf<ArchiveEntryInfo>()
                tarStream(archive)?.use { s ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val e = s.nextEntry ?: break
                        out += ArchiveEntryInfo(e.name.trimEnd('/'), e.isDirectory, e.size)
                    }
                } ?: throw IOException("Can't browse ${archive.name}; extract it instead")
                out
            }
        }
    }

    /** The immediate children of [folder] ("" = archive root), folders first. */
    fun childrenOf(all: List<ArchiveEntryInfo>, folder: String): List<ArchiveEntryInfo> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val seen = LinkedHashMap<String, ArchiveEntryInfo>()
        for (e in all) {
            if (!e.path.startsWith(prefix) || e.path == folder) continue
            val rest = e.path.removePrefix(prefix)
            val first = rest.substringBefore('/')
            if (first.isEmpty() || first == ".." || first == ".") continue    // never shown: extraction skips these paths anyway
            val isDir = rest.contains('/') || e.isDirectory
            val key = prefix + first
            val prev = seen[key]
            seen[key] = ArchiveEntryInfo(key, isDir, if (isDir) 0L else e.size).takeIf { prev == null || isDir } ?: prev!!
        }
        return seen.values.sortedWith(compareBy({ !it.isDirectory }, { it.path.lowercase() }))
    }

    /** Extracts exactly the given file paths (as listed by [list]) into [destDir]. */
    suspend fun extractPaths(
        archive: File,
        paths: Set<String>,
        destDir: File,
        password: String? = null,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): Int {
        if (RarSupport.handles(archive.name)) return withContext(Dispatchers.IO) {
            val fast = if (archive.name.endsWith(".7z", true) && password == null) {
                ArchiveExtractor.tryParallelSevenZ(archive, destDir, paths, {}, onProgress)
            } else null
            fast ?: RarSupport.extract(archive, destDir, password, null, {}, onProgress, onlyPaths = paths)
        }
        if (archive.name.endsWith(".zip", true) && password == null && !isEncryptedZip(archive)) {
            // One multi-core pass over the zip, however many files were picked.
            val n = withContext(Dispatchers.IO) { ParallelZip.extract(archive, destDir, paths, onProgress, {}, always = true) }
            if (n != null) return n
        }
        // Tar variants: a single pass over the archive instead of one pass per file.
        ArchiveExtractor.extractOnly(archive, destDir, paths, onProgress)?.let { return it }
        var n = 0
        for (p in paths) n += extractEntry(archive, p, destDir, password)
        return n
    }

    /** Extracts one entry (a file, or a folder with everything under it) into [destDir]. */
    suspend fun extractEntry(archive: File, entryPath: String, destDir: File, password: String? = null): Int =
        withContext(Dispatchers.IO) {
            destDir.mkdirs()
            val lower = archive.name.lowercase()
            var count = 0
            fun matches(name: String) = name.trimEnd('/') == entryPath || name.startsWith("$entryPath/")
            fun target(name: String): File {
                val t = File(destDir, name).canonicalFile
                if (!t.path.startsWith(destDir.canonicalPath + File.separator)) throw IOException("Blocked unsafe path: $name")
                return t
            }
            when {
                RarSupport.handles(archive.name) -> count = RarSupport.extract(archive, destDir, password, entryPath)
                ArchiveExtractor.isDiscImageName(lower) ->
                    count = ArchiveExtractor.extractIsoWhere(archive, destDir, { it == entryPath || it.startsWith("$entryPath/") }, {})
                lower.endsWith(".zip") -> {
                    val z = zip(archive, password)
                    for (h in z.fileHeaders.filter { matches(it.fileName) }) {
                        currentCoroutineContext().ensureActive()
                        target(h.fileName)
                        z.extractFile(h, destDir.absolutePath); if (!h.isDirectory) count++
                    }
                }
                lower.endsWith(".7z") -> SevenZFile.builder().setFile(archive).get().use { sz ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val e = sz.nextEntry ?: break
                        if (!matches(e.name)) continue
                        val t = target(e.name)
                        if (e.isDirectory) { t.mkdirs(); continue }
                        t.parentFile?.mkdirs()
                        t.outputStream().buffered().use { o ->
                            val buf = ByteArray(32 * 1024)
                            while (true) { val n = sz.read(buf); if (n < 0) break; o.write(buf, 0, n) }
                        }
                        count++
                    }
                }
                else -> tarStream(archive)?.use { s ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val e = s.nextEntry ?: break
                        if (!matches(e.name)) continue
                        val t = target(e.name)
                        if (e.isDirectory) { t.mkdirs(); continue }
                        t.parentFile?.mkdirs()
                        t.outputStream().buffered().use { o -> s.copyTo(o) }
                        count++
                    }
                } ?: throw IOException("Can't extract from ${archive.name}")
            }
            if (count == 0) throw IOException("Nothing named \"$entryPath\" found in the archive")
            count
        }

    /** Extracts a whole zip with a password into [destDir]. */
    suspend fun extractZipWithPassword(archive: File, destDir: File, password: String): Int = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val z = zip(archive, password)
        z.extractAll(destDir.absolutePath)
        z.fileHeaders.count { !it.isDirectory }
    }

    data class TestResult(val ok: Boolean, val message: String)

    /** Reads every byte of every entry so CRC and format errors surface before extraction. */
    suspend fun test(
        archive: File,
        password: String? = null,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): TestResult = withContext(Dispatchers.IO) {
        val lower = archive.name.lowercase()
        var entries = 0
        try {
            when {
                RarSupport.handles(archive.name) -> entries = RarSupport.test(archive, password, onProgress)
                ArchiveExtractor.isDiscImageName(lower) -> {
                    val n = ArchiveExtractor.listIso(archive).count { !it.isDirectory }
                    return@withContext TestResult(true, "OK: the image's folder structure is readable ($n file(s)). ISO files carry no checksums to verify.")
                }
                lower.endsWith(".zip") -> {
                    val z = zip(archive, password)
                    if (!z.isValidZipFile) return@withContext TestResult(false, "Not a valid zip file")
                    for (h in z.fileHeaders) {
                        currentCoroutineContext().ensureActive()
                        if (h.isDirectory) continue
                        z.getInputStream(h).use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
                        entries++
                    }
                }
                lower.endsWith(".7z") -> SevenZFile.builder().setFile(archive).get().use { sz ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val e = sz.nextEntry ?: break
                        if (e.isDirectory) continue
                        while (sz.read(buf) >= 0) { /* drain */ }
                        entries++
                    }
                }
                else -> {
                    val s = tarStream(archive) ?: return@withContext TestResult(false, "This archive type can't be tested")
                    s.use {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val e = it.nextEntry ?: break
                            if (e.isDirectory) continue
                            it.copyTo(java.io.OutputStream.nullOutputStream())
                            entries++
                        }
                    }
                }
            }
            TestResult(true, "OK: $entries file(s) read without errors")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            TestResult(false, "Problem found: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun tarStream(archive: File): ArchiveInputStream<*>? {
        val n = archive.name.lowercase()
        fun raw() = BufferedInputStream(FileInputStream(archive))
        return when {
            n.endsWith(".tar") -> TarArchiveInputStream(raw())
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> TarArchiveInputStream(GzipCompressorInputStream(raw()))
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> TarArchiveInputStream(BZip2CompressorInputStream(raw()))
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> TarArchiveInputStream(XZCompressorInputStream(raw()))
            else -> null
        }
    }
}
