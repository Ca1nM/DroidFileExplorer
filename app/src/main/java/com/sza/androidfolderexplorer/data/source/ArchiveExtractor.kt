package com.sza.androidfolderexplorer.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Extracts zip, 7z, tar (plain, gz, bz2, xz) and ISO 9660 images through the plain File API.
 * Every entry is checked to stay inside the destination folder (no zip-slip).
 */
object ArchiveExtractor {

    private val compoundSuffixes = listOf(".tar.gz", ".tar.bz2", ".tar.xz")
    private val singleSuffixes = listOf(".zip", ".7z", ".iso", ".tar", ".tgz", ".tbz2", ".tbz", ".txz")

    fun isSupported(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return (compoundSuffixes + singleSuffixes).any { lower.endsWith(it) }
    }

    /** "photos.tar.gz" -> "photos", "backup.zip" -> "backup". */
    fun folderNameFor(fileName: String): String {
        val lower = fileName.lowercase()
        val suffix = (compoundSuffixes + singleSuffixes)
            .sortedByDescending { it.length }
            .firstOrNull { lower.endsWith(it) }
        return if (suffix != null) fileName.dropLast(suffix.length) else fileName.substringBeforeLast('.')
    }

    /** Extracts [archive] into [destDir] (created if missing). Returns the number of files written. */
    suspend fun extract(archive: File, destDir: File, onEntry: (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            destDir.mkdirs()
            val lower = archive.name.lowercase()
            when {
                lower.endsWith(".zip") -> extractZip(archive, destDir, onEntry)
                lower.endsWith(".7z") -> extractSevenZip(archive, destDir, onEntry)
                lower.endsWith(".iso") -> extractIso(archive, destDir, onEntry)
                lower.endsWith(".tar") ->
                    extractStream(TarArchiveInputStream(buffered(archive)), destDir, onEntry)
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ->
                    extractStream(TarArchiveInputStream(GzipCompressorInputStream(buffered(archive))), destDir, onEntry)
                lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz") ->
                    extractStream(TarArchiveInputStream(BZip2CompressorInputStream(buffered(archive))), destDir, onEntry)
                lower.endsWith(".tar.xz") || lower.endsWith(".txz") ->
                    extractStream(TarArchiveInputStream(XZCompressorInputStream(buffered(archive))), destDir, onEntry)
                else -> throw IOException("Unsupported archive type: ${archive.name}")
            }
        }

    private fun buffered(file: File) = BufferedInputStream(FileInputStream(file))

    /**
     * Zips are read through the central directory (random access) so entries stored with data
     * descriptors, zip64 archives and Deflate64 all work. A damaged zip with no usable central
     * directory falls back to reading it as a stream.
     */
    private suspend fun extractZip(archive: File, destDir: File, onEntry: (String) -> Unit): Int {
        if (ArchiveTools.isEncryptedZip(archive)) {
            throw IOException("This zip is password-protected. Tap it to open the archive browser and enter the password there.")
        }
        val zf = try {
            org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(archive).get()
        } catch (e: IOException) {
            // No readable central directory: try the streaming reader, which tolerates truncated files.
            return extractStream(
                ZipArchiveInputStream(buffered(archive), "UTF-8", true, true),
                destDir, onEntry
            )
        }
        var written = 0
        zf.use { zip ->
            for (entry in java.util.Collections.list(zip.entries)) {
                currentCoroutineContext().ensureActive()
                val target = safeTarget(destDir, entry.name) ?: continue
                if (entry.isDirectory) { target.mkdirs(); continue }
                if (!zip.canReadEntryData(entry)) {
                    throw IOException("\"${entry.name}\" uses a compression method this app can't read")
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> FileOutputStream(target).use { out -> input.copyTo(out) } }
                if (entry.time > 0) target.setLastModified(entry.time)
                written++
                onEntry(entry.name)
            }
        }
        return written
    }

    private suspend fun <E : ArchiveEntry> extractStream(
        input: ArchiveInputStream<E>,
        destDir: File,
        onEntry: (String) -> Unit
    ): Int {
        var written = 0
        input.use { stream ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = stream.nextEntry ?: break
                val target = safeTarget(destDir, entry.name) ?: continue
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out -> stream.copyTo(out) }
                written++
                onEntry(entry.name)
            }
        }
        return written
    }

    private suspend fun extractSevenZip(archive: File, destDir: File, onEntry: (String) -> Unit): Int {
        var written = 0
        SevenZFile(archive).use { sevenZ ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = sevenZ.nextEntry ?: break
                val target = safeTarget(destDir, entry.name) ?: continue
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    while (true) {
                        val n = sevenZ.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
                written++
                onEntry(entry.name)
            }
        }
        return written
    }

    /** Returns the target file under [destDir], or null if the entry would escape it. */
    private fun safeTarget(destDir: File, entryName: String): File? {
        val relative = entryName.replace('\\', '/').trimStart('/')
        if (relative.isEmpty()) return null
        val target = File(destDir, relative)
        return if (target.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) target else null
    }

    // ---- ISO 9660 (with Joliet long names when present) ----

    private data class IsoRoot(val lba: Long, val size: Long, val unicode: Boolean)

    private suspend fun extractIso(iso: File, destDir: File, onEntry: (String) -> Unit): Int =
        RandomAccessFile(iso, "r").use { raf ->
            val sector = ByteArray(SECTOR)
            var primary: IsoRoot? = null
            var joliet: IsoRoot? = null
            var lba = 16L // volume descriptors start at sector 16
            while (true) {
                try {
                    raf.seek(lba * SECTOR)
                    raf.readFully(sector)
                } catch (e: EOFException) {
                    break
                }
                if (String(sector, 1, 5, Charsets.US_ASCII) != "CD001") break
                val type = sector[0].toInt() and 0xFF
                if (type == 255) break // terminator
                if (type == 1 && primary == null) primary = rootOf(sector, unicode = false)
                if (type == 2 && isJolietEscape(sector)) joliet = rootOf(sector, unicode = true)
                lba++
            }
            val root = joliet ?: primary ?: throw IOException("Not a valid ISO 9660 image")
            val walker = IsoWalker(raf, destDir, onEntry)
            walker.walk(root.lba, root.size, root.unicode, destDir)
            walker.written
        }

    private fun rootOf(sector: ByteArray, unicode: Boolean) = IsoRoot(
        lba = le32(sector, ROOT_RECORD_OFFSET + 2),
        size = le32(sector, ROOT_RECORD_OFFSET + 10),
        unicode = unicode
    )

    private fun isJolietEscape(sector: ByteArray): Boolean {
        val third = sector[90].toInt() and 0xFF
        return sector[88].toInt() == 0x25 && sector[89].toInt() == 0x2F && third in setOf(0x40, 0x43, 0x45)
    }

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFFL) or
            ((b[o + 1].toLong() and 0xFFL) shl 8) or
            ((b[o + 2].toLong() and 0xFFL) shl 16) or
            ((b[o + 3].toLong() and 0xFFL) shl 24)

    private class IsoWalker(
        private val raf: RandomAccessFile,
        private val base: File,
        private val onEntry: (String) -> Unit
    ) {
        var written = 0
            private set

        suspend fun walk(lba: Long, size: Long, unicode: Boolean, dir: File) {
            val data = ByteArray(size.toInt())
            raf.seek(lba * SECTOR)
            raf.readFully(data)
            var pos = 0
            while (pos < data.size) {
                currentCoroutineContext().ensureActive()
                val recordLen = data[pos].toInt() and 0xFF
                if (recordLen == 0) {
                    // Records never cross a sector boundary; zero length means padding up to the next sector.
                    pos = (pos / SECTOR + 1) * SECTOR
                    continue
                }
                val extent = le32(data, pos + 2)
                val dataLen = le32(data, pos + 10)
                val isDir = (data[pos + 25].toInt() and 0x02) != 0
                val nameLen = data[pos + 32].toInt() and 0xFF
                val nameStart = pos + 33
                pos += recordLen

                // "." and ".." records
                if (nameLen == 1 && (data[nameStart].toInt() == 0 || data[nameStart].toInt() == 1)) continue

                val raw = String(data, nameStart, nameLen, if (unicode) Charsets.UTF_16BE else Charsets.US_ASCII)
                val name = raw.substringBeforeLast(';').removeSuffix(".")
                if (name.isEmpty() || name.contains('/')) continue

                val target = File(dir, name)
                if (!target.canonicalPath.startsWith(base.canonicalPath + File.separator)) continue
                if (isDir) {
                    target.mkdirs()
                    walk(extent, dataLen, unicode, target)
                } else {
                    target.parentFile?.mkdirs()
                    copyExtent(extent, dataLen, target)
                    written++
                    onEntry(name)
                }
            }
        }

        private fun copyExtent(lba: Long, length: Long, target: File) {
            raf.seek(lba * SECTOR)
            val buffer = ByteArray(COPY_BUFFER)
            var remaining = length
            FileOutputStream(target).use { out ->
                while (remaining > 0) {
                    val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("Unexpected end of ISO image")
                    out.write(buffer, 0, n)
                    remaining -= n
                }
            }
        }
    }

    private const val SECTOR = 2048
    private const val COPY_BUFFER = 64 * 1024
    private const val ROOT_RECORD_OFFSET = 156
}
