package com.ca1nm.droidfileexplorer.data.source

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IArchiveOpenCallback
import net.sf.sevenzipjbinding.IArchiveOpenVolumeCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * RAR and RAR5 archives, including ones split into several parts ("name.part1.rar", "name.part2.rar", …
 * or the older "name.rar", "name.r00", "name.r01", …). Uses 7-Zip's engine.
 *
 * Names such as "game.part1 (1).rar", which browsers produce when a file is downloaded twice, are handled:
 * the part number is read from the name and the other parts are found next to it, with or without the "(1)".
 */
object RarSupport {

    fun isRar(name: String): Boolean = name.lowercase().let { it.endsWith(".rar") || OLD_PART.matches(it) }

    /** "movie.part2.rar" -> "movie", "movie.rar" -> "movie", "movie.part1 (1).rar" -> "movie". */
    fun folderNameFor(name: String): String {
        var n = name.replace(Regex("(?i)\\.rar$"), "").replace(Regex("(?i)\\.r\\d{2,3}$"), "")
        n = n.replace(Regex("\\s*\\(\\d+\\)$"), "")
        n = n.replace(Regex("(?i)[._ -]?part\\d+$"), "")
        return n.ifBlank { name.substringBeforeLast('.') }
    }

    private val OLD_PART = Regex(".*\\.r\\d{2,3}")
    private val NEW_PART = Regex("(?i)^(.*?)(\\.?part)(\\d+)(.*)(\\.rar)$")

    /** The first part of the set that [file] belongs to; [file] itself when it is already the first or not split. */
    fun firstPart(file: File): File {
        val dir = file.parentFile ?: return file
        val name = file.name
        NEW_PART.matchEntire(name)?.let { m ->
            val width = m.groupValues[3].length
            val first = m.groupValues[1] + m.groupValues[2] + "1".padStart(width, '0') + m.groupValues[4] + m.groupValues[5]
            File(dir, first).takeIf { it.exists() }?.let { return it }
            // Same set, differently spelled (for example the "(1)" is on only some parts): look for any part 1.
            dir.listFiles()?.firstOrNull { f ->
                NEW_PART.matchEntire(f.name)?.let { o ->
                    o.groupValues[3].toIntOrNull() == 1 && baseOf(o.groupValues[1]) == baseOf(m.groupValues[1])
                } == true
            }?.let { return it }
            return file
        }
        if (OLD_PART.matches(name.lowercase())) {
            val base = name.substringBeforeLast('.')
            File(dir, "$base.rar").takeIf { it.exists() }?.let { return it }
            File(dir, "$base.RAR").takeIf { it.exists() }?.let { return it }
        }
        return file
    }

    private fun baseOf(s: String) = s.replace(Regex("\\s*\\(\\d+\\)$"), "").lowercase()

    /** Opens the archive. [first] must be the first part. The returned handle must be closed. */
    private class Opened(val archive: IInArchive, private val streams: MutableList<RandomAccessFile>) : AutoCloseable {
        override fun close() {
            runCatching { archive.close() }
            streams.forEach { runCatching { it.close() } }
        }
    }

    /**
     * 7-Zip asks for the following parts by name, built from the first part's name. We hand it a plain name
     * ("x.part1.rar") and translate whatever it asks for ("x.part2.rar") back to the real file on disk.
     */
    private class Volumes(private val first: File, private val password: String?, val streams: MutableList<RandomAccessFile>) :
        IArchiveOpenCallback, IArchiveOpenVolumeCallback, ICryptoGetTextPassword {

        private var current: File = first
        private val dir = first.parentFile
        private val wanted = HashMap<String, File>()

        private fun plain(n: String) = n.replace(Regex("\\s*\\(\\d+\\)(?=\\.[^.]+$)"), "")

        override fun getProperty(propID: PropID): Any? = when (propID) {
            PropID.NAME -> plain(current.name)
            else -> null
        }

        override fun getStream(filename: String): IInStream? {
            val asked = File(filename).name
            val real = wanted[asked] ?: run {
                val direct = File(dir, asked).takeIf { it.exists() }
                direct ?: dir?.listFiles()?.firstOrNull { plain(it.name).equals(asked, ignoreCase = true) }
            } ?: return null
            wanted[asked] = real
            current = real
            val raf = RandomAccessFile(real, "r")
            streams += raf
            return RandomAccessFileInStream(raf)
        }

        override fun setTotal(files: Long?, bytes: Long?) {}
        override fun setCompleted(files: Long?, bytes: Long?) {}
        override fun cryptoGetTextPassword(): String = password.orEmpty()
    }

    @Volatile private var nativeReady = false

    /** Loads 7-Zip's native library once. */
    private fun initNative() {
        if (nativeReady) return
        synchronized(this) {
            if (nativeReady) return
            try {
                System.loadLibrary("7-Zip-JBinding")
                SevenZip.initLoadedLibraries()
            } catch (e: Throwable) {
                throw IOException("RAR support couldn't start on this device: ${e.message}", e)
            }
            nativeReady = true
        }
    }

    private fun open(first: File, password: String?): Opened {
        initNative()
        val streams = mutableListOf<RandomAccessFile>()
        val callback = Volumes(first, password, streams)
        val raf = RandomAccessFile(first, "r")
        streams += raf
        val archive = try {
            // Let 7-Zip pick RAR or RAR5 from the file itself.
            SevenZip.openInArchive(null, RandomAccessFileInStream(raf), callback)
        } catch (e: SevenZipException) {
            streams.forEach { runCatching { it.close() } }
            throw IOException(friendly(e, first), e)
        }
        return Opened(archive, callback.streams)
    }

    private fun friendly(e: Exception, file: File): String {
        val m = e.message.orEmpty()
        val kind = if (file.name.endsWith(".7z", true)) "7z" else "RAR"
        return when {
            m.contains("password", true) -> "This $kind is password-protected."
            else -> "Can't open ${file.name} as a $kind archive" + if (m.isNotBlank()) ": $m" else ""
        }
    }

    /** True for .7z files when 7-Zip's native engine loads; it is several times faster than the pure-Java reader. */
    fun handlesSevenZ(name: String): Boolean =
        name.endsWith(".7z", true) && runCatching { initNative() }.isSuccess

    /** Archives this object reads: RAR always, 7z when the native engine is available. */
    fun handles(name: String): Boolean = isRar(name) || handlesSevenZ(name)

    fun isEncrypted(archive: File): Boolean = runCatching {
        open(firstPart(archive), null).use { o ->
            (0 until o.archive.numberOfItems).any { o.archive.getProperty(it, PropID.ENCRYPTED) == true }
        }
    }.getOrDefault(false)

    /** Every entry. For a split set this reads all the parts' headers. */
    fun list(archive: File, password: String? = null): List<ArchiveEntryInfo> {
        val first = firstPart(archive)
        open(first, password).use { o ->
            val a = o.archive
            return (0 until a.numberOfItems).map { i ->
                val path = (a.getProperty(i, PropID.PATH) as? String).orEmpty().replace('\\', '/').trimEnd('/')
                val isDir = a.getProperty(i, PropID.IS_FOLDER) == true
                val size = (a.getProperty(i, PropID.SIZE) as? Number)?.toLong() ?: 0L
                ArchiveEntryInfo(path, isDir, size)
            }
        }
    }

    /**
     * Extracts into [destDir]. [only] limits it to one entry or folder (a path inside the archive).
     * Returns the number of files written. Paths that would leave [destDir] are skipped.
     */
    suspend fun extract(
        archive: File,
        destDir: File,
        password: String? = null,
        only: String? = null,
        onEntry: (String) -> Unit = {},
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        onlyPaths: Set<String>? = null,
        rejectEncrypted: Boolean = false
    ): Int {
        val startedAt = System.currentTimeMillis()
        val first = firstPart(archive)
        destDir.mkdirs()
        val base = destDir.canonicalPath
        var written = 0
        var failure: String? = null
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        open(first, password).use { o ->
            val a = o.archive
            fun pathOf(i: Int) = (a.getProperty(i, PropID.PATH) as? String).orEmpty().replace('\\', '/').trimStart('/')
            // Checked on the archive we already opened, so a big archive's headers are read once, not twice.
            if (rejectEncrypted && password == null &&
                (0 until a.numberOfItems).any { a.getProperty(it, PropID.ENCRYPTED) == true }
            ) {
                throw IOException("This archive is password-protected. Tap it to open the archive browser and enter the password there.")
            }
            val wanted = (0 until a.numberOfItems).filter { i ->
                val p = pathOf(i).trimEnd('/')
                p.isNotEmpty() &&
                    (only == null || p == only || p.startsWith("$only/")) &&
                    (onlyPaths == null || p in onlyPaths)
            }
            if (wanted.isEmpty()) throw IOException(if (only != null) "Nothing named \"$only\" found in the archive" else "The archive is empty")

            val callback = object : IArchiveExtractCallback, ICryptoGetTextPassword {
                private var out: java.io.OutputStream? = null
                private var target: File? = null
                private var cancelled = false

                override fun getStream(index: Int, mode: ExtractAskMode): ISequentialOutStream? {
                    closeCurrent()
                    if (mode != ExtractAskMode.EXTRACT) return null
                    val rel = pathOf(index)
                    val t = File(destDir, rel)
                    val ok = runCatching { t.canonicalPath.startsWith(base + File.separator) }.getOrDefault(false)
                    if (!ok) return null            // would escape the destination: skip it
                    if (a.getProperty(index, PropID.IS_FOLDER) == true) { t.mkdirs(); return null }
                    t.parentFile?.mkdirs()
                    target = t
                    val stream = t.outputStream().buffered(1024 * 1024)
                    out = stream
                    onEntry(rel)
                    return ISequentialOutStream { data ->
                        // Stops a long extraction when the user cancels it.
                        if (job != null && !job.isActive) { cancelled = true; throw SevenZipException("Cancelled") }
                        stream.write(data)
                        data.size
                    }
                }

                private fun closeCurrent() { runCatching { out?.close() }; out = null }

                override fun prepareOperation(mode: ExtractAskMode) {}

                override fun setOperationResult(result: ExtractOperationResult) {
                    closeCurrent()
                    val t = target; target = null
                    if (t == null) return
                    if (result == ExtractOperationResult.OK) { written++ }
                    else {
                        t.delete()      // never leave a damaged file behind
                        if (failure == null) failure = when (result) {
                            ExtractOperationResult.WRONG_PASSWORD -> "Wrong password"
                            ExtractOperationResult.CRCERROR -> "${t.name} is damaged (checksum mismatch)"
                            ExtractOperationResult.UNAVAILABLE -> "A part of the set is missing, so ${t.name} can't be completed"
                            ExtractOperationResult.UNEXPECTED_END -> "${t.name} ends early; a part may be incomplete"
                            ExtractOperationResult.UNSUPPORTEDMETHOD -> "${t.name} uses a RAR feature that isn't supported"
                            else -> "${t.name}: $result"
                        }
                    }
                }

                private var total = 0L
                private var lastReport = 0L
                override fun setTotal(total: Long) { this.total = total }
                override fun setCompleted(complete: Long) {
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 100 || complete >= total) { lastReport = now; onProgress(complete, total) }
                }
                override fun cryptoGetTextPassword(): String = password.orEmpty()
            }

            // The 7-Zip call blocks; check for cancellation by failing the write when the job is cancelled.
            currentCoroutineContext().ensureActive()
            try {
                a.extract(wanted.toIntArray(), false, callback)
            } catch (e: SevenZipException) {
                if (job != null && !job.isActive) throw kotlinx.coroutines.CancellationException("Extraction cancelled")
                throw IOException(failure ?: friendly(e, first), e)
            }
        }
        failure?.let { throw IOException(it) }
        android.util.Log.i("DFE_EXTRACT", "native extract of ${archive.name}: $written files in ${System.currentTimeMillis() - startedAt} ms")
        return written
    }

    /** Reads every byte of every file so damage shows up before extraction. Returns the number of files checked. */
    fun test(archive: File, password: String? = null, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): Int {
        val first = firstPart(archive)
        var checked = 0
        var failure: String? = null
        open(first, password).use { o ->
            val a = o.archive
            val all = (0 until a.numberOfItems).filter { a.getProperty(it, PropID.IS_FOLDER) != true }.toIntArray()
            val cb = object : IArchiveExtractCallback, ICryptoGetTextPassword {
                private var name = ""
                override fun getStream(index: Int, mode: ExtractAskMode): ISequentialOutStream? {
                    name = (a.getProperty(index, PropID.PATH) as? String).orEmpty()
                    return ISequentialOutStream { it.size }
                }
                override fun prepareOperation(mode: ExtractAskMode) {}
                override fun setOperationResult(result: ExtractOperationResult) {
                    if (result == ExtractOperationResult.OK) checked++ else if (failure == null) failure = "$name: $result"
                }
                private var total = 0L
                private var lastReport = 0L
                override fun setTotal(total: Long) { this.total = total }
                override fun setCompleted(complete: Long) {
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 100 || complete >= total) { lastReport = now; onProgress(complete, total) }
                }
                override fun cryptoGetTextPassword(): String = password.orEmpty()
            }
            try { a.extract(all, true, cb) } catch (e: SevenZipException) { throw IOException(failure ?: friendly(e, first), e) }
        }
        failure?.let { throw IOException(it) }
        return checked
    }
}
