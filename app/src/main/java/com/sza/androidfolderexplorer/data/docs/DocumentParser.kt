package com.sza.androidfolderexplorer.data.docs

import android.database.sqlite.SQLiteDatabase
import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** One piece of a document, in reading order. A [Row] is one row of a table or spreadsheet. */
sealed interface DocBlock {
    data class Heading(val level: Int, val text: String) : DocBlock
    data class Para(val text: String) : DocBlock
    data class Row(val cells: List<String>, val header: Boolean = false) : DocBlock
}

/** A sheet, a slide, a chapter or a database table; a plain document is a single unnamed section. */
data class DocSection(val name: String?, val blocks: List<DocBlock>)

data class ParsedDocument(val sections: List<DocSection>, val note: String? = null, val fontPath: String? = null)

/**
 * Reads the text and tables out of office documents so they can be shown without another app: Word (.docx), Excel
 * (.xlsx), PowerPoint (.pptx), OpenDocument (.odt .ods .odp), e-books (.epub), Rich Text (.rtf) and SQLite databases.
 * This is a reader, not a renderer: it shows the content (headings, paragraphs, tables, cells, slide text), not the page
 * layout, fonts, pictures or charts. The old binary formats (.doc .xls .ppt) aren't supported.
 */
object DocumentParser {
    const val MAX_BYTES = 120L * 1024 * 1024
    private const val MAX_BLOCKS = 40_000
    private const val MAX_SHEET_ROWS = 1_500
    private const val MAX_COLUMNS = 60

    fun parse(file: File): ParsedDocument {
        require(file.isFile) { "File not found" }
        require(file.length() <= MAX_BYTES) { "This file is too large to preview (limit ${MAX_BYTES / 1024 / 1024} MB)." }
        return when (file.extension.lowercase()) {
            "docx", "docm", "dotx", "dotm" -> zip(file) { parseDocx(it) }
            "xlsx", "xlsm", "xltx", "xltm" -> zip(file) { parseXlsx(it) }
            "pptx", "pptm", "potx", "ppsx" -> zip(file) { parsePptx(it) }
            "odt", "ott" -> zip(file) { parseOdf(it, "odt") }
            "ods", "ots" -> zip(file) { parseOdf(it, "ods") }
            "odp", "otp" -> zip(file) { parseOdf(it, "odp") }
            "epub" -> zip(file) { parseEpub(it) }
            "rtf" -> ParsedDocument(listOf(DocSection(null, paragraphs(rtfToText(file.readText(Charsets.ISO_8859_1))))))
            "db", "sqlite", "sqlite3", "db3" -> parseSqlite(file)
            "ttf", "otf", "ttc" -> fontSpecimen(file)
            "doc", "xls", "ppt", "dot", "xlt", "pps" ->
                throw UnsupportedOperationException("This is an old Office format (.${file.extension}). It can't be previewed here; open it in another app.")
            else -> throw UnsupportedOperationException("No preview for this type of file.")
        }
    }

    private inline fun <T> zip(file: File, block: (ZipFile) -> T): T = ZipFile(file).use(block)

    private fun parser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(input, null)
    }

    private fun XmlPullParser.local(): String = name.substringAfter(':')

    private fun XmlPullParser.attr(local: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i).substringAfter(':') == local) return getAttributeValue(i)
        return null
    }

    private fun paragraphs(text: String): List<DocBlock> =
        text.split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }.map { DocBlock.Para(it) }

    // ---- Word ----

    private fun parseDocx(zip: ZipFile): ParsedDocument {
        val entry = zip.getEntry("word/document.xml") ?: throw IllegalArgumentException("Not a Word document")
        val blocks = ArrayList<DocBlock>()
        var truncated = false
        zip.getInputStream(entry).use { input ->
            val p = parser(input)
            val sb = StringBuilder()
            var inPara = false
            var style = ""
            var tableDepth = 0
            var row: MutableList<String>? = null
            var cell: StringBuilder? = null
            var firstRow = true
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (blocks.size >= MAX_BLOCKS) { truncated = true; break }
                if (event == XmlPullParser.START_TAG) {
                    when (p.local()) {
                        "tbl" -> { tableDepth++; if (tableDepth == 1) firstRow = true }
                        "tr" -> if (tableDepth == 1) row = ArrayList()
                        "tc" -> if (tableDepth == 1) cell = StringBuilder()
                        "p" -> { sb.setLength(0); style = ""; inPara = true }
                        "pStyle" -> style = p.attr("val").orEmpty()
                        "t" -> { val t = p.nextText(); if (inPara) sb.append(t) }
                        "tab" -> if (inPara) sb.append('\t')
                        "br", "cr" -> if (inPara) sb.append('\n')
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    when (p.local()) {
                        "p" -> {
                            inPara = false
                            val text = sb.toString()
                            val c = cell
                            if (c != null) {
                                if (c.isNotEmpty() && text.isNotEmpty()) c.append('\n')
                                c.append(text)
                            } else if (text.isNotBlank()) {
                                val s = style.lowercase()
                                blocks += when {
                                    s.startsWith("heading") -> DocBlock.Heading(s.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 6) ?: 1, text)
                                    s == "title" -> DocBlock.Heading(1, text)
                                    s == "subtitle" -> DocBlock.Heading(2, text)
                                    else -> DocBlock.Para(text)
                                }
                            }
                        }
                        "tc" -> if (tableDepth == 1) { row?.add(cell.toString().trim()); cell = null }
                        "tr" -> if (tableDepth == 1) { row?.let { blocks += DocBlock.Row(it, header = firstRow) }; row = null; firstRow = false }
                        "tbl" -> tableDepth--
                    }
                }
                event = p.next()
            }
        }
        return ParsedDocument(listOf(DocSection(null, blocks)), if (truncated) "Showing the first part of a very long document." else null)
    }

    // ---- Excel ----

    private fun parseXlsx(zip: ZipFile): ParsedDocument {
        val shared = ArrayList<String>()
        zip.getEntry("xl/sharedStrings.xml")?.let { e ->
            zip.getInputStream(e).use { input ->
                val p = parser(input)
                val sb = StringBuilder()
                var event = p.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (p.local()) { "si" -> sb.setLength(0); "t" -> sb.append(p.nextText()) }
                    } else if (event == XmlPullParser.END_TAG && p.local() == "si") shared += sb.toString()
                    event = p.next()
                }
            }
        }
        // sheet name -> file, in workbook order
        val rels = HashMap<String, String>()
        zip.getEntry("xl/_rels/workbook.xml.rels")?.let { e ->
            zip.getInputStream(e).use { input ->
                val p = parser(input)
                var event = p.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && p.local() == "Relationship") {
                        val id = p.attr("Id"); val target = p.attr("Target")
                        if (id != null && target != null) rels[id] = target
                    }
                    event = p.next()
                }
            }
        }
        val sheets = ArrayList<Pair<String, String>>()
        val wb = zip.getEntry("xl/workbook.xml") ?: throw IllegalArgumentException("Not an Excel workbook")
        zip.getInputStream(wb).use { input ->
            val p = parser(input)
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && p.local() == "sheet") {
                    val name = p.attr("name").orEmpty(); val rid = p.attr("id")
                    val target = rid?.let { rels[it] }
                    if (target != null) sheets += name to (if (target.startsWith("/")) target.removePrefix("/") else "xl/$target")
                }
                event = p.next()
            }
        }
        val sections = sheets.mapNotNull { (name, path) ->
            val e = zip.getEntry(path) ?: return@mapNotNull null
            DocSection(name, zip.getInputStream(e).use { readSheet(it, shared) })
        }
        return ParsedDocument(sections.ifEmpty { throw IllegalArgumentException("The workbook has no sheets") })
    }

    private fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1) else break }
        return n - 1
    }

    private fun readSheet(input: InputStream, shared: List<String>): List<DocBlock> {
        val rows = ArrayList<DocBlock>()
        val p = parser(input)
        var cells = ArrayList<String>()
        var col = 0
        var type = ""
        var value = ""
        var inInline = false
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT && rows.size < MAX_SHEET_ROWS) {
            if (event == XmlPullParser.START_TAG) {
                when (p.local()) {
                    "row" -> cells = ArrayList()
                    "c" -> { type = p.attr("t").orEmpty(); value = ""; col = p.attr("r")?.let { columnIndex(it) } ?: cells.size }
                    "v" -> value = p.nextText()
                    "is" -> { inInline = true; value = "" }
                    "t" -> if (inInline) value += p.nextText()
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (p.local()) {
                    "is" -> inInline = false
                    "c" -> if (col in 0 until MAX_COLUMNS) {
                        while (cells.size < col) cells += ""
                        cells += when (type) {
                            "s" -> value.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "b" -> if (value == "1") "TRUE" else "FALSE"
                            else -> value
                        }
                    }
                    "row" -> { while (cells.isNotEmpty() && cells.last().isEmpty()) cells.removeAt(cells.size - 1); if (cells.isNotEmpty()) rows += DocBlock.Row(cells, header = rows.isEmpty()) }
                }
            }
            event = p.next()
        }
        return rows
    }

    // ---- PowerPoint ----

    private fun parsePptx(zip: ZipFile): ParsedDocument {
        val slides = zip.entries().asSequence()
            .mapNotNull { e -> Regex("""ppt/slides/slide(\d+)\.xml""").matchEntire(e.name)?.let { it.groupValues[1].toInt() to e } }
            .sortedBy { it.first }.toList()
        require(slides.isNotEmpty()) { "Not a PowerPoint file" }
        val sections = slides.map { (n, e) ->
            val blocks = ArrayList<DocBlock>()
            zip.getInputStream(e).use { input ->
                val p = parser(input)
                val sb = StringBuilder()
                var event = p.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (p.local()) { "p" -> sb.setLength(0); "t" -> sb.append(p.nextText()); "br" -> sb.append('\n') }
                    } else if (event == XmlPullParser.END_TAG && p.local() == "p" && sb.isNotBlank()) {
                        blocks += if (blocks.isEmpty()) DocBlock.Heading(2, sb.toString()) else DocBlock.Para(sb.toString())
                    }
                    event = p.next()
                }
            }
            DocSection("Slide $n", blocks.ifEmpty { listOf(DocBlock.Para("(no text on this slide)")) })
        }
        return ParsedDocument(sections)
    }

    // ---- OpenDocument ----

    private fun parseOdf(zip: ZipFile, kind: String): ParsedDocument {
        val entry = zip.getEntry("content.xml") ?: throw IllegalArgumentException("Not an OpenDocument file")
        val sections = ArrayList<DocSection>()
        var blocks = ArrayList<DocBlock>()
        var sectionName: String? = null
        fun closeSection() { if (blocks.isNotEmpty() || sectionName != null) sections += DocSection(sectionName, blocks); blocks = ArrayList() }
        zip.getInputStream(entry).use { input ->
            val p = parser(input)
            val text = StringBuilder()
            var textDepth = 0
            var headingLevel = 0
            var inTable = 0
            var row: MutableList<String>? = null
            var cell: StringBuilder? = null
            var repeat = 1
            var firstRow = true
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT && blocks.size < MAX_BLOCKS) {
                when (event) {
                    XmlPullParser.START_TAG -> when (p.local()) {
                        "table" -> {
                            inTable++
                            if (inTable == 1) { firstRow = true; if (kind == "ods") { closeSection(); sectionName = p.attr("name") } }
                        }
                        "page" -> if (kind == "odp") { closeSection(); sectionName = p.attr("name") ?: "Slide ${sections.size + 1}" }
                        "table-row" -> if (inTable == 1) row = ArrayList()
                        "table-cell" -> if (inTable == 1) { cell = StringBuilder(); repeat = p.attr("number-columns-repeated")?.toIntOrNull() ?: 1 }
                        "h" -> { textDepth++; text.setLength(0); headingLevel = p.attr("outline-level")?.toIntOrNull() ?: 1 }
                        "p" -> { textDepth++; if (textDepth == 1) { text.setLength(0); headingLevel = 0 } }
                        "s" -> if (textDepth > 0) repeat(p.attr("c")?.toIntOrNull()?.coerceIn(1, 20) ?: 1) { text.append(' ') }
                        "tab" -> if (textDepth > 0) text.append('\t')
                        "line-break" -> if (textDepth > 0) text.append('\n')
                    }
                    XmlPullParser.TEXT -> if (textDepth > 0) text.append(p.text)
                    XmlPullParser.END_TAG -> when (p.local()) {
                        "h", "p" -> {
                            textDepth = (textDepth - 1).coerceAtLeast(0)
                            if (textDepth == 0) {
                                val t = text.toString()
                                val c = cell
                                if (c != null) { if (c.isNotEmpty() && t.isNotEmpty()) c.append('\n'); c.append(t) }
                                else if (t.isNotBlank()) blocks += if (headingLevel > 0) DocBlock.Heading(headingLevel.coerceIn(1, 6), t) else DocBlock.Para(t)
                                text.setLength(0)
                            }
                        }
                        "table-cell" -> if (inTable == 1) {
                            val value = cell.toString().trim()
                            val times = if (value.isEmpty()) minOf(repeat, 1) else minOf(repeat, MAX_COLUMNS)
                            repeat(times) { if ((row?.size ?: 0) < MAX_COLUMNS) row?.add(value) }
                            cell = null
                        }
                        "table-row" -> if (inTable == 1) {
                            row?.let { r ->
                                while (r.isNotEmpty() && r.last().isEmpty()) r.removeAt(r.size - 1)
                                if (r.isNotEmpty() && blocks.size < MAX_SHEET_ROWS * 4) { blocks += DocBlock.Row(r, header = firstRow && kind == "ods"); firstRow = false }
                            }
                            row = null
                        }
                        "table" -> inTable = (inTable - 1).coerceAtLeast(0)
                    }
                }
                event = p.next()
            }
        }
        closeSection()
        return ParsedDocument(sections.ifEmpty { listOf(DocSection(null, emptyList())) })
    }

    // ---- E-books ----

    private fun parseEpub(zip: ZipFile): ParsedDocument {
        val container = zip.getEntry("META-INF/container.xml") ?: throw IllegalArgumentException("Not an e-book")
        var opfPath: String? = null
        zip.getInputStream(container).use { input ->
            val p = parser(input)
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT && opfPath == null) {
                if (event == XmlPullParser.START_TAG && p.local() == "rootfile") opfPath = p.attr("full-path")
                event = p.next()
            }
        }
        val opf = opfPath?.let { zip.getEntry(it) } ?: throw IllegalArgumentException("The e-book has no package file")
        val base = opfPath!!.substringBeforeLast('/', "")
        val manifest = HashMap<String, String>()
        val spine = ArrayList<String>()
        zip.getInputStream(opf).use { input ->
            val p = parser(input)
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) when (p.local()) {
                    "item" -> { val id = p.attr("id"); val href = p.attr("href"); if (id != null && href != null) manifest[id] = href }
                    "itemref" -> p.attr("idref")?.let { spine += it }
                }
                event = p.next()
            }
        }
        val sections = spine.take(300).mapIndexedNotNull { i, id ->
            val href = manifest[id] ?: return@mapIndexedNotNull null
            val path = if (base.isEmpty()) href else "$base/$href"
            val e = zip.getEntry(path.substringBefore('#')) ?: return@mapIndexedNotNull null
            val html = zip.getInputStream(e).use { String(it.readBytes().copyOf(minOf(it.available() + 1, 400_000)), Charsets.UTF_8) }
            val plain = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString()
            DocSection("${i + 1}", paragraphs(plain)).takeIf { it.blocks.isNotEmpty() }
        }
        return ParsedDocument(sections.ifEmpty { throw IllegalArgumentException("The e-book has no readable text") })
    }

    // ---- Rich Text ----

    /** Strips RTF control words and groups that aren't text (fonts, colours, pictures ...), keeping the words. */
    fun rtfToText(s: String): String {
        val skipWords = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "footnote", "object", "themedata", "datastore", "latentstyles", "listtable", "listoverridetable", "rsidtbl", "generator")
        val out = StringBuilder()
        var depth = 0
        var skipDepth = -1
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '{' -> { depth++; i++ }
                '}' -> { if (skipDepth == depth) skipDepth = -1; depth--; i++ }
                '\\' -> {
                    i++
                    if (i >= s.length) break
                    val n = s[i]
                    when {
                        n == '\\' || n == '{' || n == '}' -> { if (skipDepth == -1) out.append(n); i++ }
                        n == '\'' -> {
                            val hex = s.substring(i + 1, minOf(i + 3, s.length))
                            if (skipDepth == -1) hex.toIntOrNull(16)?.let { out.append(String(byteArrayOf(it.toByte()), charset("windows-1252"))) }
                            i += 3
                        }
                        n == '*' -> { if (skipDepth == -1) skipDepth = depth; i++ }
                        n.isLetter() -> {
                            var j = i
                            while (j < s.length && s[j].isLetter()) j++
                            val word = s.substring(i, j)
                            var k = j
                            if (k < s.length && (s[k] == '-' || s[k].isDigit())) { k++; while (k < s.length && s[k].isDigit()) k++ }
                            val param = s.substring(j, k).toIntOrNull()
                            if (k < s.length && s[k] == ' ') k++
                            if (skipDepth == -1) {
                                when {
                                    word in skipWords -> skipDepth = depth
                                    word == "par" || word == "line" || word == "sect" || word == "page" -> out.append('\n')
                                    word == "tab" -> out.append('\t')
                                    word == "emdash" -> out.append('—')
                                    word == "endash" -> out.append('–')
                                    word == "bullet" -> out.append('•')
                                    word == "u" && param != null -> { out.append((if (param < 0) param + 65536 else param).toChar()); if (k < s.length && s[k] != '\\') k++ }
                                }
                            }
                            i = k
                        }
                        else -> i++
                    }
                }
                '\r', '\n' -> i++
                else -> { if (skipDepth == -1) out.append(c); i++ }
            }
        }
        return out.toString()
    }

    // ---- Fonts ----

    /** Sample text to show a font in; the viewer draws it with the font itself ([ParsedDocument.fontPath]). */
    private fun fontSpecimen(file: File): ParsedDocument = ParsedDocument(
        listOf(
            DocSection(
                null,
                listOf(
                    DocBlock.Heading(1, file.nameWithoutExtension),
                    DocBlock.Heading(2, "The quick brown fox jumps over the lazy dog"),
                    DocBlock.Para("ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
                    DocBlock.Para("abcdefghijklmnopqrstuvwxyz"),
                    DocBlock.Para("0123456789  !?@#&%()[]{}<>+-=/\\*"),
                    DocBlock.Para("Pack my box with five dozen liquor jugs. Sphinx of black quartz, judge my vow."),
                    DocBlock.Para("ÀÉÎÕÜ àéîõü ß Ææ Øø Çç Ññ — “quotes” ‘single’ … € £ ¥")
                )
            )
        ),
        fontPath = file.path
    )

    // ---- SQLite ----

    private fun parseSqlite(file: File): ParsedDocument {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val names = ArrayList<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { c ->
                while (c.moveToNext()) names += c.getString(0)
            }
            val sections = names.map { name ->
                val quoted = "\"" + name.replace("\"", "\"\"") + "\""
                val blocks = ArrayList<DocBlock>()
                val total = db.rawQuery("SELECT COUNT(*) FROM $quoted", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                db.rawQuery("SELECT * FROM $quoted LIMIT $MAX_SHEET_ROWS", null).use { c ->
                    blocks += DocBlock.Row(c.columnNames.toList(), header = true)
                    while (c.moveToNext()) {
                        blocks += DocBlock.Row((0 until minOf(c.columnCount, MAX_COLUMNS)).map { i ->
                            when (c.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                                android.database.Cursor.FIELD_TYPE_BLOB -> "[${c.getBlob(i).size} bytes]"
                                else -> c.getString(i)
                            }
                        })
                    }
                }
                DocSection("$name ($total)", blocks)
            }
            return ParsedDocument(sections.ifEmpty { throw IllegalArgumentException("The database has no tables") })
        } finally {
            db.close()
        }
    }
}
