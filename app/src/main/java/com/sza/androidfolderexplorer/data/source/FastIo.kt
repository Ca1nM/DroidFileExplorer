package com.sza.androidfolderexplorer.data.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Counts the bytes read through it, so a tar's progress can follow how much of the archive file was consumed. */
internal class CountingInputStream(source: InputStream) : FilterInputStream(source) {
    @Volatile var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count += it }
}

/**
 * Writes extracted data to disk on background threads, so decompressing the next block never waits for the
 * disk and, with many small files, file creation (slow on phone storage) happens on several threads at once.
 *
 * Use from ONE producer thread: for each file call [copy] (or [begin] / [acquire] / [submit] / [end]), and
 * call [finish] at the end, or [abort] when giving up. Data for one file always goes through the same writer,
 * so bytes stay in order. A fixed pool of 1 MiB buffers keeps memory bounded and slows the producer down
 * when the disk is the bottleneck.
 */
internal class WriteBehind(writers: Int = 3) {

    class Buf(val bytes: ByteArray)

    private sealed interface Op
    private class Open(val file: File) : Op
    private class Data(val buf: Buf, val len: Int) : Op
    private class Close(val mtime: Long) : Op
    private data object Stop : Op

    private val pool = ArrayBlockingQueue<Buf>(POOL).also { q -> repeat(POOL) { q.put(Buf(ByteArray(CHUNK))) } }
    private val queues: List<BlockingQueue<Op>> = List(writers) { ArrayBlockingQueue(POOL * 2) }
    private val error = AtomicReference<Throwable?>(null)
    private val threads = queues.mapIndexed { i, q -> Thread({ writerLoop(q) }, "extract-writer-$i").apply { isDaemon = true; start() } }
    private var current = 0
    private var next = 0

    private fun writerLoop(q: BlockingQueue<Op>) {
        var out: FileOutputStream? = null
        var file: File? = null
        fun fail(t: Throwable) { error.compareAndSet(null, t) }
        while (true) {
            when (val op = q.take()) {
                is Open -> {
                    try { op.file.parentFile?.mkdirs(); out = FileOutputStream(op.file); file = op.file }
                    catch (t: Throwable) { fail(t); out = null }
                }
                is Data -> {
                    try { out?.write(op.buf.bytes, 0, op.len) } catch (t: Throwable) { fail(t) }
                    pool.offer(op.buf)
                }
                is Close -> {
                    try { out?.close(); if (op.mtime > 0) file?.setLastModified(op.mtime) } catch (t: Throwable) { fail(t) }
                    out = null
                }
                Stop -> { runCatching { out?.close() }; return }
            }
        }
    }

    private fun check() { error.get()?.let { throw if (it is IOException) it else IOException(it.message ?: it.toString(), it) } }

    fun begin(file: File) { check(); current = next; next = (next + 1) % queues.size; queues[current].put(Open(file)) }
    fun acquire(): Buf { check(); return pool.take() }
    fun release(buf: Buf) { pool.offer(buf) }
    fun submit(buf: Buf, len: Int) { queues[current].put(Data(buf, len)) }
    fun end(mtime: Long = 0L) { queues[current].put(Close(mtime)) }

    /**
     * Copies everything [read] returns into [target]. [cancelled] is polled once per chunk so a cancel takes
     * effect inside one huge file too; [onBytes] gets each chunk's size.
     */
    fun copy(
        target: File,
        mtime: Long = 0L,
        cancelled: () -> Boolean = { false },
        onBytes: (Int) -> Unit = {},
        read: (ByteArray, Int, Int) -> Int
    ) {
        begin(target)
        try {
            while (true) {
                if (cancelled()) throw CancellationException("Extraction cancelled")
                val b = acquire()
                var n = 0
                try {
                    while (n < b.bytes.size) {
                        val r = read(b.bytes, n, b.bytes.size - n)
                        if (r < 0) break
                        n += r
                    }
                } catch (t: Throwable) { release(b); throw t }
                if (n == 0) { release(b); break }
                submit(b, n)
                onBytes(n)
                if (n < b.bytes.size) break
            }
        } finally {
            end(mtime)
        }
    }

    fun copy(input: InputStream, target: File, mtime: Long = 0L, cancelled: () -> Boolean = { false }, onBytes: (Int) -> Unit = {}) =
        copy(target, mtime, cancelled, onBytes) { b, o, l -> input.read(b, o, l) }

    /** Waits for every queued byte to reach the disk. Throws the first write error, if any. */
    fun finish() {
        stopWriters()
        check()
    }

    /** Stops the writers without reporting write errors (the caller is already failing or was cancelled). */
    fun abort() = stopWriters()

    private fun stopWriters() {
        queues.forEach { it.put(Stop) }
        threads.forEach { it.join() }
    }

    companion object {
        const val CHUNK = 1 shl 20
        private const val POOL = 8
    }
}

/**
 * Extracts a zip on several cores. Every worker opens its own handle on the file, reads entries through the central
 * directory and inflates them independently, so a zip of many files (or a few big ones) scales with the core count.
 */
internal object ParallelZip {

    /**
     * Returns the number of files written, or null when the zip is too small for threads to pay off (and
     * [always] is false); nothing has been written in that case. [only] limits extraction to those file paths.
     */
    suspend fun extract(
        archive: File,
        destDir: File,
        only: Set<String>?,
        onProgress: (done: Long, total: Long) -> Unit,
        onEntry: (String) -> Unit,
        always: Boolean = false
    ): Int? {
        class Job(val name: String, val size: Long, val time: Long, val target: File)

        val jobs = ArrayList<Job>()
        var total = 0L
        org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(archive).get().use { zf ->
            for (entry in java.util.Collections.list(zf.entries)) {
                val name = entry.name
                if (only != null && !entry.isDirectory && name.trimEnd('/') !in only) continue
                if (only != null && entry.isDirectory) continue
                val target = ArchiveExtractor.safeTarget(destDir, name) ?: continue
                if (entry.isDirectory) { target.mkdirs(); continue }
                if (!zf.canReadEntryData(entry)) {
                    throw IOException("\"$name\" uses a compression method this app can't read")
                }
                val size = maxOf(entry.size, 0L)
                jobs += Job(name, size, entry.time, target)
                total += size
            }
        }
        if (jobs.isEmpty()) return if (only != null) throw IOException("Nothing to extract from the archive") else 0
        if (!always && (jobs.size < 2 || total < MIN_BYTES)) return null

        jobs.sortByDescending { it.size }     // biggest first, so the last files are small and the workers finish together
        val threads = minOf(Runtime.getRuntime().availableProcessors(), MAX_THREADS, jobs.size).coerceAtLeast(1)
        val nextJob = AtomicInteger(0)
        val doneBytes = AtomicLong(0)
        val written = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val lock = Any()
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "zip-extract").apply { isDaemon = true } }
        val futures = (0 until threads).map {
            pool.submit {
                try {
                    org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(archive).get().use { zf ->
                        val buf = ByteArray(WriteBehind.CHUNK)
                        while (!stopFlag.get()) {
                            val i = nextJob.getAndIncrement()
                            if (i >= jobs.size) break
                            val j = jobs[i]
                            val entry = zf.getEntry(j.name) ?: throw IOException("\"${j.name}\" is missing from the archive")
                            j.target.parentFile?.mkdirs()
                            openFast(zf, entry).use { input ->
                                FileOutputStream(j.target).use { out ->
                                    while (true) {
                                        if (stopFlag.get()) throw CancellationException("Extraction cancelled")
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        doneBytes.addAndGet(n.toLong())
                                    }
                                }
                            }
                            if (j.time > 0) j.target.setLastModified(j.time)
                            written.incrementAndGet()
                            synchronized(lock) { onEntry(j.name) }
                        }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                    stopFlag.set(true)
                }
            }
        }
        try {
            while (futures.any { !it.isDone }) {
                delay(100)   // throws if the user cancelled
                onProgress(doneBytes.get(), total)
            }
            onProgress(total, total)
        } finally {
            stopFlag.set(true)
            pool.shutdown()
            runCatching { pool.awaitTermination(3, TimeUnit.SECONDS) }
        }
        failure.get()?.let { throw if (it is IOException || it is CancellationException) it else IOException(it.message ?: it.toString(), it) }
        return written.get()
    }

    /**
     * commons-compress feeds its inflater 512 bytes at a time, which makes big zips slow. For the usual Deflate and
     * Stored entries, read the raw compressed bytes and inflate them here with a 64 KiB buffer; anything else
     * (Deflate64, bzip2 ...) still goes through commons-compress.
     */
    private fun openFast(
        zf: org.apache.commons.compress.archivers.zip.ZipFile,
        entry: org.apache.commons.compress.archivers.zip.ZipArchiveEntry
    ): InputStream {
        val raw = zf.getRawInputStream(entry) ?: return zf.getInputStream(entry)
        return when (entry.method) {
            java.util.zip.ZipEntry.STORED -> raw
            java.util.zip.ZipEntry.DEFLATED -> {
                val inflater = java.util.zip.Inflater(true)
                // A raw deflate stream wants one extra byte after the data, or the last block may not finish.
                val padded = java.io.SequenceInputStream(raw, java.io.ByteArrayInputStream(byteArrayOf(0)))
                object : java.util.zip.InflaterInputStream(padded, inflater, 64 * 1024) {
                    override fun close() { try { super.close() } finally { inflater.end() } }
                }
            }
            else -> { raw.close(); zf.getInputStream(entry) }
        }
    }

    private const val MIN_BYTES = 16L * 1024 * 1024
    private const val MAX_THREADS = 4
}
