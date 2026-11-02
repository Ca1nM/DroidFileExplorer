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
        archive.name.endsWith(".zip", true) && runCatching { ZipFile(archive).isEncrypted }.getOrDefault(false)

    /** Lists every entry. Works for zip, 7z and tar variants; ISO is not supported here. */
    suspend fun list(archive: File, password: String? = null): List<ArchiveEntryInfo> = withContext(Dispatchers.IO) {
        val lower = archive.name.lowercase()
        when {
            lower.endsWith(".zip") -> zip(archive, password).fileHeaders.map {
                ArchiveEntryInfo(it.fileName.trimEnd('/'), it.isDirectory, it.uncompressedSize)
            }
            lower.endsWith(".7z") -> SevenZFile.builder().setFile(archive).get().use { sz ->
                sz.entries.map { ArchiveEntryInfo(it.name.trimEnd('/'), it.isDirectory, it.size) }
            }
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
            val isDir = rest.contains('/') || e.isDirectory
            val key = prefix + first
            val prev = seen[key]
            seen[key] = ArchiveEntryInfo(key, isDir, if (isDir) 0L else e.size).takeIf { prev == null || isDir } ?: prev!!
        }
        return seen.values.sortedWith(compareBy({ !it.isDirectory }, { it.path.lowercase() }))
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
    suspend fun test(archive: File, password: String? = null): TestResult = withContext(Dispatchers.IO) {
        val lower = archive.name.lowercase()
        var entries = 0
        try {
            when {
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
