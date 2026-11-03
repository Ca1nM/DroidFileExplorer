package com.ca1nm.droidfileexplorer.data.source

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Reads a zip's central directory (the list of entries at the end of the file) directly. The general-purpose zip
 * libraries take about 5 seconds to build 50,000 entry objects on a phone; this takes a fraction of a second because it
 * only pulls out what the browser needs: the name, the size, whether the entry is a folder, and whether it is encrypted.
 * Returns null when the file isn't something it understands (the caller then falls back to a library).
 */
internal object ZipDirectory {

    class Entry(val name: String, val isDirectory: Boolean, val size: Long, val encrypted: Boolean)

    private const val EOCD_SIG = 0x06054b50
    private const val ZIP64_LOCATOR_SIG = 0x07064b50
    private const val ZIP64_EOCD_SIG = 0x06064b50
    private const val CENTRAL_SIG = 0x02014b50
    private const val MAX_DIRECTORY_BYTES = 256L * 1024 * 1024

    private val cp437: Charset = runCatching { Charset.forName("IBM437") }.getOrDefault(Charsets.ISO_8859_1)

    fun read(file: File): List<Entry>? = runCatching { readOrThrow(file) }.getOrNull()

    private fun readOrThrow(file: File): List<Entry> = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        require(length >= 22)
        // The end-of-central-directory record is in the last 64 KB + 22 bytes (it can be followed by a comment).
        val tailSize = minOf(length, 65_557L).toInt()
        val tail = ByteArray(tailSize)
        raf.seek(length - tailSize)
        raf.readFully(tail)
        var eocd = -1
        for (i in tailSize - 22 downTo 0) {
            if (le32(tail, i) == EOCD_SIG) { eocd = i; break }
        }
        require(eocd >= 0)
        var total = le16(tail, eocd + 10).toLong()
        var cdSize = le32(tail, eocd + 12).toLong() and 0xFFFFFFFFL
        var cdOffset = le32(tail, eocd + 16).toLong() and 0xFFFFFFFFL

        // Zip64: the real numbers live in a second record, found through the locator just before the end record.
        if (total == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            val locAt = length - tailSize + eocd - 20
            require(locAt >= 0)
            val loc = ByteArray(20); raf.seek(locAt); raf.readFully(loc)
            require(le32(loc, 0) == ZIP64_LOCATOR_SIG)
            val z64At = le64(loc, 8)
            val z64 = ByteArray(56); raf.seek(z64At); raf.readFully(z64)
            require(le32(z64, 0) == ZIP64_EOCD_SIG)
            total = le64(z64, 32); cdSize = le64(z64, 40); cdOffset = le64(z64, 48)
        }
        require(cdSize in 1..MAX_DIRECTORY_BYTES && cdOffset + cdSize <= length)

        val dir = ByteArray(cdSize.toInt())
        raf.seek(cdOffset)
        raf.readFully(dir)
        val buf = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Entry>(minOf(total, 2_000_000L).toInt())
        var pos = 0
        while (pos + 46 <= dir.size && buf.getInt(pos) == CENTRAL_SIG) {
            val flags = buf.getShort(pos + 8).toInt() and 0xFFFF
            var size = buf.getInt(pos + 24).toLong() and 0xFFFFFFFFL
            val nameLen = buf.getShort(pos + 28).toInt() and 0xFFFF
            val extraLen = buf.getShort(pos + 30).toInt() and 0xFFFF
            val commentLen = buf.getShort(pos + 32).toInt() and 0xFFFF
            val nameAt = pos + 46
            require(nameAt + nameLen + extraLen + commentLen <= dir.size)
            if (size == 0xFFFFFFFFL) size = zip64Size(dir, nameAt + nameLen, extraLen) ?: size
            val utf8 = (flags and 0x800) != 0
            var name = String(dir, nameAt, nameLen, Charsets.UTF_8)
            if (!utf8 && name.indexOf('�') >= 0) name = String(dir, nameAt, nameLen, cp437)
            out += Entry(name.replace('\\', '/'), name.endsWith("/") || name.endsWith("\\"), size, (flags and 1) != 0)
            pos = nameAt + nameLen + extraLen + commentLen
        }
        out
    }

    /** The uncompressed size from a Zip64 extra field (header id 0x0001), where it is the first value. */
    private fun zip64Size(dir: ByteArray, extraStart: Int, extraLen: Int): Long? {
        var p = extraStart
        val end = extraStart + extraLen
        while (p + 4 <= end) {
            val id = le16(dir, p)
            val len = le16(dir, p + 2)
            if (id == 0x0001 && len >= 8 && p + 4 + 8 <= end) return le64(dir, p + 4)
            p += 4 + len
        }
        return null
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
    private fun le64(b: ByteArray, o: Int) = (le32(b, o).toLong() and 0xFFFFFFFFL) or ((le32(b, o + 4).toLong() and 0xFFFFFFFFL) shl 32)
}
