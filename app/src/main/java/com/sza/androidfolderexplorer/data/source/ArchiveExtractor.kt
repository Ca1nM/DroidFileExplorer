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
        return RarSupport.isRar(fileName) || (compoundSuffixes + singleSuffixes).any { lower.endsWith(it) }
    }

    /** "photos.tar.gz" -> "photos", "backup.zip" -> "backup", "game.part2.rar" -> "game". */
    fun folderNameFor(fileName: String): String {
        if (RarSupport.isRar(fileName)) return RarSupport.folderNameFor(fileName)
        val lower = fileName.lowercase()
        val suffix = (compoundSuffixes + singleSuffixes)
            .sortedByDescending { it.length }
            .firstOrNull { lower.endsWith(it) }
        return if (suffix != null) fileName.dropLast(suffix.length) else fileName.substringBeforeLast('.')
    }

    /** Extracts [archive] into [destDir] (created if missing). Returns the number of files written. */
    suspend fun extract(
        archive: File,
        destDir: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        onEntry: (String) -> Unit
    ): Int {
        val started = System.nanoTime()
        val n = extractCore(archive, destDir, onProgress, onEntry)
        android.util.Log.i("AFEX_EXTRACT", "TIMING ${archive.name}: $n files in ${(System.nanoTime() - started) / 1_000_000} ms")
        return n
    }

    private suspend fun extractCore(
        archive: File,
        destDir: File,
        onProgress: (done: Long, total: Long) -> Unit,
        onEntry: (String) -> Unit
    ): Int =
        withContext(Dispatchers.IO) {
            destDir.mkdirs()
            val lower = archive.name.lowercase()
            // Multi-core path for 7z files made of independent LZMA2 blocks; null means "not suitable".
            if (lower.endsWith(".7z")) {
                tryParallelSevenZ(archive, destDir, null, onEntry, onProgress)?.let { return@withContext it }
            }
            when {
                RarSupport.handles(archive.name) ->
                    RarSupport.extract(archive, destDir, null, null, onEntry, onProgress, rejectEncrypted = true)
                lower.endsWith(".zip") -> extractZip(archive, destDir, onEntry, onProgress)
                lower.endsWith(".7z") -> extractSevenZip(archive, destDir, onEntry)
                isDiscImageName(lower) -> extractIso(archive, destDir, onEntry)
                isTar(lower) -> extractTar(archive, destDir, null, onEntry, onProgress)
                else -> throw IOException("Unsupported archive type: ${archive.name}")
            }
        }

    private fun buffered(file: File) = BufferedInputStream(FileInputStream(file), WriteBehind.CHUNK)

    private fun isTar(lower: String) = lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ||
        lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz") ||
        lower.endsWith(".tar.xz") || lower.endsWith(".txz")

    /**
     * Extracts a tar (plain, gz, bz2 or xz). [only] limits it to those file paths, in one pass over the archive.
     * Progress follows how much of the archive file has been read.
     */
    private suspend fun extractTar(
        archive: File,
        destDir: File,
        only: Set<String>?,
        onEntry: (String) -> Unit,
        onProgress: (done: Long, total: Long) -> Unit
    ): Int {
        val lower = archive.name.lowercase()
        val counter = CountingInputStream(FileInputStream(archive))
        val raw = BufferedInputStream(counter, WriteBehind.CHUNK)
        val stream = TarArchiveInputStream(
            when {
                lower.endsWith(".tar") -> raw
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> GzipCompressorInputStream(raw)
                lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz") -> BZip2CompressorInputStream(raw)
                else -> XZCompressorInputStream(raw)
            }
        )
        val length = archive.length()
        return extractStream(stream, destDir, onEntry, only) { onProgress(counter.count, length) }
    }

    /**
     * Extracts just the file paths in [only] in a single pass over the archive (tar variants, and 7z when the native
     * engine is missing). Returns null for formats that have no single-pass reader here.
     */
    suspend fun extractOnly(
        archive: File,
        destDir: File,
        only: Set<String>,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): Int? = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val lower = archive.name.lowercase()
        when {
            isTar(lower) -> extractTar(archive, destDir, only, {}, onProgress)
            lower.endsWith(".7z") -> extractSevenZip(archive, destDir, {}, only)
            isDiscImageName(lower) -> extractIsoWhere(archive, destDir, { it in only }, {})
            else -> null
        }
    }

    /** Removes [dir] if nothing was written into it, so a cancelled or failed extraction doesn't leave an empty folder. */
    fun removeIfEmpty(dir: File) {
        if (!dir.isDirectory) return
        // Nothing but folders and empty files (what is left when extraction fails or is cancelled before any data arrives).
        val hasData = dir.walkTopDown().any { it.isFile && it.length() > 0 }
        if (!hasData) dir.deleteRecursively()
    }

    /**
     * Extracts a .7z on several cores when its LZMA2 data is split into independent blocks (7-Zip does that for
     * big archives). Returns the number of files written, or null when the archive isn't suitable (filters,
     * encryption, a single block, no memory); nothing has been written in that case and the caller falls back.
     * [only] limits extraction to those file paths inside the archive.
     */
    suspend fun tryParallelSevenZ(
        archive: File,
        destDir: File,
        only: Set<String>?,
        onEntry: (String) -> Unit,
        onProgress: (done: Long, total: Long) -> Unit
    ): Int? {
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        return try {
            ParallelSevenZip.extract(
                archive, destDir, only, 0,
                { done, total -> onProgress(done, total) },
                { path -> onEntry(path) },
                { job != null && !job.isActive },
                { msg -> android.util.Log.i("AFEX_EXTRACT", msg) }
            )
        } catch (e: ParallelSevenZip.Unsupported) {
            android.util.Log.i("AFEX_EXTRACT", "parallel 7z not used: ${e.message}")
            null
        } catch (e: ParallelSevenZip.Cancelled) {
            throw kotlinx.coroutines.CancellationException("Extraction cancelled")
        }
    }

    /**
     * Zips are read through the central directory (random access) so entries stored with data
     * descriptors, zip64 archives and Deflate64 all work. A damaged zip with no usable central
     * directory falls back to reading it as a stream.
     */
    private suspend fun extractZip(
        archive: File,
        destDir: File,
        onEntry: (String) -> Unit,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): Int {
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
        // Big zips: inflate on several cores at once. Null means the zip is too small to bother.
        val parallel = try {
            ParallelZip.extract(archive, destDir, null, onProgress, onEntry)
        } catch (t: Throwable) {
            zf.close(); throw t
        }
        if (parallel != null) { zf.close(); return parallel }
        var written = 0
        var done = 0L
        zf.use { zip ->
            val all = java.util.Collections.list(zip.entries)
            val total = all.sumOf { if (it.isDirectory) 0L else maxOf(it.size, 0L) }
            for (entry in all) {
                currentCoroutineContext().ensureActive()
                val target = safeTarget(destDir, entry.name) ?: continue
                if (entry.isDirectory) { target.mkdirs(); continue }
                if (!zip.canReadEntryData(entry)) {
                    throw IOException("\"${entry.name}\" uses a compression method this app can't read")
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> FileOutputStream(target).use { out -> input.copyTo(out, WriteBehind.CHUNK) } }
                if (entry.time > 0) target.setLastModified(entry.time)
                written++
                done += maxOf(entry.size, 0L)
                onProgress(done, total)
                onEntry(entry.name)
            }
        }
        return written
    }

    /**
     * Streams every entry out of [input]. File data goes through [WriteBehind], so the next block is decoded while the
     * last one is still being written. [only] limits it to those file paths; [afterEntry] runs after each file.
     */
    private suspend fun <E : ArchiveEntry> extractStream(
        input: ArchiveInputStream<E>,
        destDir: File,
        onEntry: (String) -> Unit,
        only: Set<String>? = null,
        afterEntry: () -> Unit = {}
    ): Int {
        var written = 0
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val cancelled = { job != null && !job.isActive }
        val wb = WriteBehind()
        try {
            input.use { stream ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = stream.nextEntry ?: break
                    val target = safeTarget(destDir, entry.name) ?: continue
                    if (entry.isDirectory) {
                        if (only == null) target.mkdirs()
                        continue
                    }
                    if (only != null && entry.name.trimEnd('/') !in only) continue
                    wb.copy(stream, target, cancelled = cancelled)
                    written++
                    onEntry(entry.name)
                    afterEntry()
                }
            }
            wb.finish()
        } catch (t: Throwable) {
            wb.abort()
            throw t
        }
        return written
    }

    private suspend fun extractSevenZip(archive: File, destDir: File, onEntry: (String) -> Unit, only: Set<String>? = null): Int {
        var written = 0
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val cancelled = { job != null && !job.isActive }
        val wb = WriteBehind()
        try {
            SevenZFile(archive).use { sevenZ ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = sevenZ.nextEntry ?: break
                    val target = safeTarget(destDir, entry.name) ?: continue
                    if (entry.isDirectory) {
                        if (only == null) target.mkdirs()
                        continue
                    }
                    if (only != null && entry.name.trimEnd('/') !in only) continue
                    wb.copy(target, cancelled = cancelled) { b, o, l -> sevenZ.read(b, o, l) }
                    written++
                    onEntry(entry.name)
                }
            }
            wb.finish()
        } catch (t: Throwable) {
            wb.abort()
            throw t
        }
        return written
    }

    /** Returns the target file under [destDir], or null if the entry would escape it. */
    internal fun safeTarget(destDir: File, entryName: String): File? {
        val relative = entryName.replace('\\', '/').trimStart('/')
        if (relative.isEmpty()) return null
        val target = File(destDir, relative)
        return if (target.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) target else null
    }

    // ---- ISO 9660 (with Joliet long names when present) ----

    private data class IsoRoot(val lba: Long, val size: Long, val unicode: Boolean)

    /**
     * How an image stores its 2048-byte data sectors: back to back (a normal .iso), or inside raw 2352-byte CD sectors
     * after a 16 or 24 byte header (typical of .bin CD images).
     */
    private class IsoGeometry(val physical: Int, val dataOffset: Int) {
        fun position(lba: Long) = lba * physical + dataOffset
    }

    private val geometries = listOf(IsoGeometry(2048, 0), IsoGeometry(2352, 16), IsoGeometry(2352, 24))

    /** Finds which layout holds a primary volume descriptor ("CD001") at sector 16, or null if it isn't an ISO 9660 image. */
    private fun detectGeometry(raf: RandomAccessFile): IsoGeometry? {
        val head = ByteArray(6)
        for (g in geometries) {
            try { raf.seek(g.position(16)); raf.readFully(head) } catch (e: IOException) { continue }
            if (String(head, 1, 5, Charsets.US_ASCII) == "CD001" && head[0].toInt() == 1) return g
        }
        return null
    }

    /** True when [file] (an .iso, or a .bin/.img image) holds an ISO 9660 or Xbox (XDVDFS) file system this app can browse. */
    fun looksLikeDiscImage(file: File): Boolean =
        runCatching { RandomAccessFile(file, "r").use { detectGeometry(it) != null || detectXdvdfs(it) != null } }.getOrDefault(false)

    /** Names this app treats as disc images when they hold an ISO 9660 file system. */
    fun isDiscImageName(lowerName: String) = lowerName.endsWith(".iso") || lowerName.endsWith(".bin") || lowerName.endsWith(".img")

    private suspend fun extractIso(iso: File, destDir: File, onEntry: (String) -> Unit): Int =
        extractIsoWhere(iso, destDir, null, onEntry)

    /** Every file and folder in an ISO 9660 image (Joliet long names when present), for browsing it like a folder. */
    suspend fun listIso(iso: File): List<ArchiveEntryInfo> = withContext(Dispatchers.IO) {
        val out = ArrayList<ArchiveEntryInfo>()
        val xbox = xdvdfsBaseOf(iso)
        if (xbox != null) {
            RandomAccessFile(iso, "r").use { raf -> XdvdfsWalker(raf, xbox, null, {}, null, { false }, out, null).run() }
            return@withContext out
        }
        withIsoRoot(iso) { raf, root, geom ->
            IsoWalker(raf, geom, null, {}, null, { false }, out, null).walk(root.lba, root.size, root.unicode, "")
        }
        out
    }

    /** Extracts only the files for which [wanted] (given the path inside the image) is true; null extracts everything. */
    suspend fun extractIsoWhere(iso: File, destDir: File, wanted: ((String) -> Boolean)?, onEntry: (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            destDir.mkdirs()
            val xbox = xdvdfsBaseOf(iso)
            if (xbox != null) {
                return@withContext RandomAccessFile(iso, "r").use { raf ->
                    val job = currentCoroutineContext()[kotlinx.coroutines.Job]
                    val wb = WriteBehind()
                    val walker = XdvdfsWalker(raf, xbox, destDir, onEntry, wb, { job != null && !job.isActive }, null, wanted)
                    try {
                        walker.run()
                        wb.finish()
                    } catch (t: Throwable) {
                        wb.abort()
                        throw t
                    }
                    walker.written
                }
            }
            withIsoRoot(iso) { raf, root, geom ->
                val job = currentCoroutineContext()[kotlinx.coroutines.Job]
                val wb = WriteBehind()
                val walker = IsoWalker(raf, geom, destDir, onEntry, wb, { job != null && !job.isActive }, null, wanted)
                try {
                    walker.walk(root.lba, root.size, root.unicode, "")
                    wb.finish()
                } catch (t: Throwable) {
                    wb.abort()
                    throw t
                }
                walker.written
            }
        }

    private suspend fun <T> withIsoRoot(iso: File, block: suspend (RandomAccessFile, IsoRoot, IsoGeometry) -> T): T =
        RandomAccessFile(iso, "r").use { raf ->
            val geom = detectGeometry(raf) ?: throw IOException(unsupportedReason(raf))
            val sector = ByteArray(SECTOR)
            var primary: IsoRoot? = null
            var joliet: IsoRoot? = null
            var lba = 16L // volume descriptors start at sector 16
            while (true) {
                try {
                    raf.seek(geom.position(lba))
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
            val root = joliet ?: primary ?: throw IOException(unsupportedReason(raf))
            block(raf, root, geom)
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

    /**
     * Walks the directory tree. With [list] it records every entry; with [wb] and [base] it also writes files out
     * (all of them, or those [wanted] accepts). Paths are relative to the image root, "/"-separated.
     */
    private class IsoWalker(
        private val raf: RandomAccessFile,
        private val geom: IsoGeometry,
        private val base: File?,
        private val onEntry: (String) -> Unit,
        private val wb: WriteBehind?,
        private val cancelled: () -> Boolean,
        private val list: MutableList<ArchiveEntryInfo>?,
        private val wanted: ((String) -> Boolean)?
    ) {
        var written = 0
            private set
        private val seen = HashSet<String>()
        // A file over 4 GB is stored as several back-to-back records; they are gathered here until the last one arrives.
        private var pending: ArrayList<Pair<Long, Long>>? = null
        private var pendingPath = ""
        private var pendingName = ""

        suspend fun walk(lba: Long, size: Long, unicode: Boolean, rel: String) {
            val data = ByteArray(size.toInt())
            // One 2048-byte sector at a time, since in a raw .bin image sectors aren't back to back.
            var done = 0
            var sectorNo = lba
            while (done < data.size) {
                val n = minOf(SECTOR, data.size - done)
                raf.seek(geom.position(sectorNo))
                raf.readFully(data, done, n)
                done += n
                sectorNo++
            }
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
                val moreExtents = (data[pos + 25].toInt() and 0x80) != 0   // "the next record continues this file"
                val nameLen = data[pos + 32].toInt() and 0xFF
                val nameStart = pos + 33
                pos += recordLen

                // "." and ".." records
                if (nameLen == 1 && (data[nameStart].toInt() == 0 || data[nameStart].toInt() == 1)) continue

                val raw = String(data, nameStart, nameLen, if (unicode) Charsets.UTF_16BE else Charsets.US_ASCII)
                val name = raw.substringBeforeLast(';').removeSuffix(".")
                if (name.isEmpty() || name.contains('/')) continue

                val path = if (rel.isEmpty()) name else "$rel/$name"
                if (isDir) {
                    list?.add(ArchiveEntryInfo(path, true, 0L))
                    if (base != null && wanted == null) safeTarget(base, path)?.mkdirs()
                    walk(extent, dataLen, unicode, path)
                    continue
                }
                // Collect every record of a split file (all but the last are flagged "more extents follow"), then handle
                // it as one file.
                val parts = pending ?: ArrayList<Pair<Long, Long>>().also { pending = it; pendingPath = path; pendingName = name }
                parts.add(extent to dataLen)
                if (moreExtents) continue
                pending = null
                finishFile(pendingPath, pendingName, parts)
            }
        }

        private fun finishFile(path: String, name: String, parts: List<Pair<Long, Long>>) {
            if (!seen.add(path)) return                          // the same path listed twice
            list?.add(ArchiveEntryInfo(path, false, parts.sumOf { it.second }))
            if (wb != null && base != null && (wanted == null || wanted(path))) {
                val target = safeTarget(base, path) ?: return
                target.parentFile?.mkdirs()
                copyExtents(parts, target, wb)
                written++
                onEntry(name)
            }
        }

        /** Writes [parts] (start sector, length) one after another into [target], as a single file. */
        private fun copyExtents(parts: List<Pair<Long, Long>>, target: File, wb: WriteBehind) {
            var idx = 0
            var remaining = parts[0].second
            var sectorNo = parts[0].first
            var inSector = 0
            // Reading the image and writing the file overlap, each in 1 MiB steps. A plain .iso reads straight through;
            // a raw .bin image can only be read up to the end of the current sector before skipping the next header.
            wb.copy(target, cancelled = cancelled) { b, o, l ->
                while (remaining <= 0 && idx < parts.size - 1) {
                    idx++
                    remaining = parts[idx].second; sectorNo = parts[idx].first; inSector = 0
                }
                if (remaining <= 0) -1
                else {
                    raf.seek(geom.position(sectorNo) + inSector)
                    val reachable = if (geom.physical == SECTOR) Long.MAX_VALUE else (SECTOR - inSector).toLong()
                    val n = raf.read(b, o, minOf(l.toLong(), remaining, reachable).toInt())
                    if (n < 0) throw IOException("Unexpected end of ISO image")
                    remaining -= n
                    inSector += n
                    sectorNo += inSector / SECTOR
                    inSector %= SECTOR
                    n
                }
            }
        }
    }

    // ---- Xbox and Xbox 360 discs (XDVDFS, usually saved as .iso) ----

    private val XDVDFS_MAGIC = "MICROSOFT*XBOX*MEDIA".toByteArray(Charsets.US_ASCII)

    /** Where the game partition starts: the original Xbox at 0; Xbox 360 discs put it after a video partition. */
    private val xdvdfsOffsets = longArrayOf(0L, 0xFD90000L, 0x2090000L, 0x2080000L, 0x18300000L)

    /** Start of the XDVDFS partition in [raf] (its volume descriptor sits 0x10000 bytes in), or null. */
    private fun detectXdvdfs(raf: RandomAccessFile): Long? {
        val head = ByteArray(XDVDFS_MAGIC.size)
        for (base in xdvdfsOffsets) {
            try { raf.seek(base + 0x10000L); raf.readFully(head) } catch (e: IOException) { continue }
            if (head.contentEquals(XDVDFS_MAGIC)) return base
        }
        return null
    }

    private fun xdvdfsBaseOf(file: File): Long? =
        runCatching { RandomAccessFile(file, "r").use { detectXdvdfs(it) } }.getOrNull()

    /** Why an image can't be browsed, in words, for the formats this app knows it can't open. */
    private fun unsupportedReason(raf: RandomAccessFile): String {
        val head = ByteArray(0x20)
        runCatching { raf.seek(0); raf.readFully(head) }
        val wbfs = String(head, 0, 4, Charsets.US_ASCII) == "WBFS"
        val wii = (head[0x18].toInt() and 0xFF) == 0x5D && (head[0x19].toInt() and 0xFF) == 0x1C &&
            (head[0x1A].toInt() and 0xFF) == 0x9E && (head[0x1B].toInt() and 0xFF) == 0xA3
        return when {
            wbfs || wii -> "This is a Wii disc image. Wii discs are encrypted with a key Nintendo keeps secret, so their files can't be listed or extracted here."
            else -> "Not an image this app can browse: it isn't ISO 9660 (CD/DVD) or an Xbox / Xbox 360 disc."
        }
    }

    /**
     * Walks an XDVDFS file system. Each directory is a table of entries stored as a binary tree; an entry is
     * left(u16) right(u16) sector(u32) size(u32) attributes(u8) nameLength(u8) name, offsets counted in 4-byte units.
     */
    private class XdvdfsWalker(
        private val raf: RandomAccessFile,
        private val base: Long,
        private val destDir: File?,
        private val onEntry: (String) -> Unit,
        private val wb: WriteBehind?,
        private val cancelled: () -> Boolean,
        private val list: MutableList<ArchiveEntryInfo>?,
        private val wanted: ((String) -> Boolean)?
    ) {
        var written = 0
            private set

        suspend fun run() {
            val volume = ByteArray(0x20)
            raf.seek(base + 0x10000L)
            raf.readFully(volume)
            walk(le32(volume, 0x14), le32(volume, 0x18), "")
        }

        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        private suspend fun walk(sector: Long, size: Long, rel: String) {
            if (size < 14 || size > 64L * 1024 * 1024) return          // empty or implausible directory table
            val table = ByteArray(size.toInt())
            raf.seek(base + sector * SECTOR)
            raf.readFully(table)
            if (u16(table, 0) == 0xFFFF && u16(table, 2) == 0xFFFF) return   // an empty directory is all 0xFF

            // In-order walk of the tree without recursion (a badly balanced directory could be deep).
            val order = ArrayList<Int>()
            val stack = ArrayDeque<Int>()
            var cur = 0
            val limit = table.size / 14 + 1
            while (order.size <= limit) {
                while (cur >= 0 && cur + 14 <= table.size && stack.size <= limit) {
                    stack.addLast(cur)
                    val left = u16(table, cur)
                    cur = if (left == 0 || left == 0xFFFF) -1 else left * 4
                }
                val node = stack.removeLastOrNull() ?: break
                order += node
                val right = u16(table, node + 2)
                cur = if (right == 0 || right == 0xFFFF) -1 else right * 4
            }

            for (off in order) {
                currentCoroutineContext().ensureActive()
                val start = le32(table, off + 4)
                val length = le32(table, off + 8)
                val isDir = (table[off + 12].toInt() and 0x10) != 0
                val nameLen = table[off + 13].toInt() and 0xFF
                if (off + 14 + nameLen > table.size || nameLen == 0) continue
                val name = String(table, off + 14, nameLen, Charsets.ISO_8859_1)
                if (name.contains('/') || name == "." || name == "..") continue
                val path = if (rel.isEmpty()) name else "$rel/$name"
                if (isDir) {
                    list?.add(ArchiveEntryInfo(path, true, 0L))
                    if (destDir != null && wanted == null) safeTarget(destDir, path)?.mkdirs()
                    walk(start, length, path)
                } else {
                    list?.add(ArchiveEntryInfo(path, false, length))
                    if (wb != null && destDir != null && (wanted == null || wanted(path))) {
                        val target = safeTarget(destDir, path) ?: continue
                        target.parentFile?.mkdirs()
                        copyFile(start, length, target, wb)
                        written++
                        onEntry(name)
                    }
                }
            }
        }

        private fun copyFile(sector: Long, length: Long, target: File, wb: WriteBehind) {
            var remaining = length
            raf.seek(base + sector * SECTOR)
            wb.copy(target, cancelled = cancelled) { b, o, l ->
                if (remaining <= 0) -1
                else {
                    val n = raf.read(b, o, minOf(l.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("Unexpected end of the disc image")
                    remaining -= n
                    n
                }
            }
        }
    }

    /**
     * A warning to show above an image's contents, or null. A PS3 disc dump that is still encrypted lists fine but its
     * files are unreadable; that is checked by looking at whether PS3_GAME/PARAM.SFO starts with its "\0PSF" marker.
     */
    suspend fun discNote(image: File, entries: List<ArchiveEntryInfo>, tempDir: File): String? = withContext(Dispatchers.IO) {
        val sfo = entries.firstOrNull { !it.isDirectory && it.path.equals("PS3_GAME/PARAM.SFO", ignoreCase = true) }
            ?: return@withContext null
        val tmp = File(tempDir, "ps3check_${System.nanoTime()}")
        try {
            extractIsoWhere(image, tmp, { it.equals(sfo.path, ignoreCase = true) }, {})
            val head = ByteArray(4)
            File(tmp, sfo.path).inputStream().use { it.read(head) }
            if (head.contentEquals(byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte()))) null
            else "This PS3 image is encrypted: the files are listed, but their contents can't be read without decrypting the image first."
        } catch (e: Exception) {
            null
        } finally {
            tmp.deleteRecursively()
        }
    }

    private const val SECTOR = 2048
    private const val ROOT_RECORD_OFFSET = 156
}
