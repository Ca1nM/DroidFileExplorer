package com.sza.androidfolderexplorer.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Decides whether a file can be opened in the built-in text editor. */
object TextFiles {
    /**
     * Largest file the editor will open. The editor is one scrolling text field, which gets very slow with a lot of
     * text; bigger files open in the read-only viewer instead.
     */
    const val MAX_EDIT_BYTES = 8L * 1024 * 1024

    /**
     * A line this long can't be shown on one row: the text widget redraws the whole line every frame, so scrolling
     * crawls. Such files (minified JSON, one-line logs) are shown wrapped instead.
     */
    const val LONG_LINE_CHARS = 20_000

    /** Number of characters in the longest line of [s]. */
    fun longestLine(s: CharSequence): Int {
        var longest = 0
        var run = 0
        for (i in 0 until s.length) {
            if (s[i] == '\n') { if (run > longest) longest = run; run = 0 } else run++
        }
        return maxOf(longest, run)
    }

    /** True for a small file whose start is valid UTF-8 with no NUL bytes (config files, dotfiles, scripts, notes ...). */
    fun looksLikeText(file: File): Boolean = runCatching {
        if (!file.isFile) return false
        val size = file.length()
        if (size == 0L) return true
        if (size > MAX_EDIT_BYTES) return false
        val head = ByteArray(minOf(size, 8192L).toInt())
        file.inputStream().use { it.read(head) }
        if (head.any { it == 0.toByte() }) return false
        // A multi-byte character may be cut at the end of the sample, so ignore up to 3 trailing bytes.
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val sample = if (size > head.size) head.copyOf(head.size - 3) else head
        decoder.decode(ByteBuffer.wrap(sample))
        true
    }.getOrDefault(false)
}
