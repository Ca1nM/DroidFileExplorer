package com.sza.androidfolderexplorer.data.docs

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

internal fun XmlPullParser.local(): String = name.substringAfter(':')

internal fun XmlPullParser.attr(local: String): String? {
    for (i in 0 until attributeCount) if (getAttributeName(i).substringAfter(':') == local) return getAttributeValue(i)
    return null
}

/**
 * Turns Word, PowerPoint and e-book files into HTML pages (with their pictures saved next to them) so they can be shown
 * with their formatting: bold, italic, underline, colours, sizes, alignment, lists, tables, links and images. It is a
 * converter for viewing, not a full layout engine: page margins, headers, footers, charts and drawings aren't reproduced.
 * Everything taken from the document is escaped, so a hostile file can't inject script or markup.
 */
object OfficeHtml {

    class Result(val pages: List<File>, val titles: List<String>)

    fun docx(file: File, outDir: File): Result = ZipFile(file).use { zip ->
        outDir.mkdirs()
        Result(listOf(DocxConverter(zip, outDir).convert()), listOf(file.nameWithoutExtension))
    }

    fun pptx(file: File, outDir: File): Result = ZipFile(file).use { zip ->
        outDir.mkdirs()
        PptxConverter(zip, outDir).convert()
    }

    /** An e-book is already HTML: unpack it and show its chapters in reading order. */
    fun epub(file: File, outDir: File): Result = ZipFile(file).use { zip ->
        outDir.mkdirs()
        val base = outDir.canonicalPath + File.separator
        zip.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
            val target = File(outDir, e.name).canonicalFile
            if (target.path.startsWith(base)) {
                target.parentFile?.mkdirs()
                zip.getInputStream(e).use { input -> target.outputStream().use { out -> input.copyTo(out) } }
            }
        }
        val container = File(outDir, "META-INF/container.xml")
        require(container.isFile) { "Not an e-book" }
        var opfPath: String? = null
        container.inputStream().use { input ->
            val p = parser(input)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT && opfPath == null) {
                if (ev == XmlPullParser.START_TAG && p.local() == "rootfile") opfPath = p.attr("full-path")
                ev = p.next()
            }
        }
        val opf = File(outDir, opfPath ?: throw IllegalArgumentException("The e-book has no package file"))
        val manifest = HashMap<String, String>()
        val spine = ArrayList<String>()
        opf.inputStream().use { input ->
            val p = parser(input)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (p.local()) {
                    "item" -> { val id = p.attr("id"); val href = p.attr("href"); if (id != null && href != null) manifest[id] = href }
                    "itemref" -> p.attr("idref")?.let { spine += it }
                }
                ev = p.next()
            }
        }
        val dir = opf.parentFile ?: outDir
        val pages = spine.mapNotNull { id -> manifest[id]?.let { File(dir, it.substringBefore('#')).takeIf { f -> f.isFile && f.canonicalPath.startsWith(base) } } }
        require(pages.isNotEmpty()) { "The e-book has no readable pages" }
        Result(pages, pages.indices.map { "${it + 1}" })
    }

    // ---- shared helpers ----

    internal fun parser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(input, null)
    }

    /**
     * The phone doesn't have Word's fonts, so each one maps to the closest style it does have: serif (Times, Cambria,
     * Georgia ...), monospace (Courier, Consolas ...), handwriting/cursive, condensed, or sans-serif.
     */
    internal fun fontStack(name: String): String {
        val n = name.lowercase()
        val generic = when {
            listOf("courier", "consolas", "mono", "menlo", "lucida console", "code", "typewriter").any { n.contains(it) } -> "monospace"
            listOf("times", "cambria", "georgia", "garamond", "palatino", "book", "serif", "minion", "baskerville", "caslon", "didot", "constantia", "century").any { n.contains(it) } && !n.contains("sans") -> "serif"
            listOf("comic", "brush", "script", "hand", "chalk", "segoe script", "lucida hand", "mistral", "vivaldi", "kunstler").any { n.contains(it) } -> "cursive"
            listOf("impact", "condensed", "narrow", "bebas", "oswald").any { n.contains(it) } -> "sans-serif-condensed"
            listOf("black", "heavy").any { n.contains(it) } -> "sans-serif-black"
            listOf("light", "thin").any { n.contains(it) } -> "sans-serif-light"
            else -> "sans-serif"
        }
        // Calibri and Cambria are bundled (see FONT_FACES), so they resolve to the real look-alike rather than the generic one.
        return "'${name}',$generic"
    }

    internal fun esc(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\u0000' -> {}
            else -> append(c)
        }
    }

    internal fun hexColor(s: String?): String? =
        s?.takeIf { it.length == 6 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }?.let { "#$it" }

    internal fun safeFont(s: String?): String? = s?.takeIf { it.length <= 60 && it.all { c -> c.isLetterOrDigit() || c == ' ' || c == '-' } }

    internal val HIGHLIGHT = mapOf(
        "yellow" to "#ffff00", "green" to "#00ff00", "cyan" to "#00ffff", "magenta" to "#ff00ff", "blue" to "#0000ff", "red" to "#ff0000",
        "darkBlue" to "#000080", "darkCyan" to "#008080", "darkGreen" to "#008000", "darkMagenta" to "#800080", "darkRed" to "#800000",
        "darkYellow" to "#808000", "darkGray" to "#808080", "lightGray" to "#c0c0c0", "black" to "#000000", "white" to "#ffffff"
    )

    internal const val IMAGE_EXTENSIONS = "png jpg jpeg gif bmp webp svg tif tiff"

    private fun face(family: String, file: String, weight: Int, style: String) =
        "@font-face{font-family:'$family';src:url('file:///android_asset/docfonts/$file.ttf');font-weight:$weight;font-style:$style}"

    /** Word's Calibri and Cambria aren't on the phone; Carlito and Caladea (bundled, open licence) match their metrics. */
    internal val FONT_FACES: String = listOf(
        "Calibri" to "Carlito", "Cambria" to "Caladea"
    ).joinToString("") { (family, file) ->
        face(family, "$file-Regular", 400, "normal") + face(family, "$file-Bold", 700, "normal") +
            face(family, "$file-Italic", 400, "italic") + face(family, "$file-BoldItalic", 700, "italic")
    }

    internal val STYLE_SHEET: String = FONT_FACES + """
        html{background:#e9e9ec}
        body{margin:0;color:#000;font-family:Calibri,'Segoe UI',Roboto,Arial,sans-serif;line-height:1.35;word-wrap:break-word}
        .doc{max-width:880px;margin:12px auto;padding:20px 24px;background:#fff;box-shadow:0 1px 4px rgba(0,0,0,.25);box-sizing:border-box;overflow-x:auto}
        p,.li{margin:0 0 6pt;white-space:pre-wrap;tab-size:4;min-height:1em}
        h1,h2,h3,h4,h5,h6{font-size:inherit;font-weight:inherit;margin:8pt 0 4pt;white-space:pre-wrap}
        table{border-collapse:collapse;margin:6px 0;max-width:100%}
        td{vertical-align:top;padding:3px 6px}
        img{max-width:100%;height:auto}
        .lbl{display:inline-block;min-width:1.6em}
        .tb{border:1px dashed #bbb;padding:4px;margin:4px 0}
        a{color:#0563c1}
    """
}

// =====================================================================================================================
// Word
// =====================================================================================================================

private class RunProps {
    var bold: Boolean? = null; var italic: Boolean? = null; var underline: Boolean? = null; var strike: Boolean? = null
    var caps: Boolean? = null; var color: String? = null; var sizeHalf: Int? = null; var font: String? = null
    var highlight: String? = null; var vert: String? = null; var style: String? = null

    fun mergeFrom(o: RunProps) {
        o.bold?.let { bold = it }; o.italic?.let { italic = it }; o.underline?.let { underline = it }; o.strike?.let { strike = it }
        o.caps?.let { caps = it }; o.color?.let { color = it }; o.sizeHalf?.let { sizeHalf = it }; o.font?.let { font = it }
        o.highlight?.let { highlight = it }; o.vert?.let { vert = it }
    }
}

private class ParaProps {
    var style: String? = null; var align: String? = null; var left: Int? = null; var firstLine: Int? = null; var hanging: Int? = null
    var before: Int? = null; var after: Int? = null; var shade: String? = null; var numId: Int? = null; var ilvl: Int = 0

    fun mergeFrom(o: ParaProps) {
        o.align?.let { align = it }; o.left?.let { left = it }; o.firstLine?.let { firstLine = it }; o.hanging?.let { hanging = it }
        o.before?.let { before = it }; o.after?.let { after = it }; o.shade?.let { shade = it }
        if (o.numId != null) { numId = o.numId; ilvl = o.ilvl }
    }
}

private class Sty { var name = ""; var basedOn: String? = null; val run = RunProps(); val para = ParaProps(); var tableBorders = false }

private class Lvl(val fmt: String, val text: String, val start: Int, val left: Int, val hanging: Int)

private class Cell { val html = StringBuilder(); var span = 1; var rowspan = 1; var fill: String? = null; var skip = false; var start = 0 }

private class TableB { val rows = ArrayList<ArrayList<Cell>>(); var cell: Cell? = null; var row: ArrayList<Cell>? = null; var col = 0; var bordered = false }

private class ParaCtx { val inner = StringBuilder(); var pp = ParaProps(); var run = RunProps(); var linkOpen = false }

private class DocxConverter(private val zip: ZipFile, private val out: File) {
    private val rels = HashMap<String, Pair<String, Boolean>>()
    private val styles = HashMap<String, Sty>()
    private var defaultSizeHalf = 22
    private val numbering = HashMap<Int, HashMap<Int, Lvl>>()
    private val counters = HashMap<Int, IntArray>()
    private val body = StringBuilder()
    private val media = HashMap<String, String>()
    private val paras = ArrayList<ParaCtx>()
    private val tables = ArrayList<TableB>()
    private var extentW = 0
    private var blocks = 0

    fun convert(): File {
        loadRels(); loadTheme(); loadStyles(); loadNumbering(); walk()
        val bodyFont = OfficeHtml.fontStack(defaultFont ?: themeFonts["minor"] ?: "Calibri")
        val html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>${OfficeHtml.STYLE_SHEET} .doc{font-size:${defaultSizeHalf / 2.0}pt;font-family:$bodyFont}</style></head><body><div class=\"doc\">$body</div></body></html>"
        return File(out, "index.html").also { it.writeText(html) }
    }

    // --- loading ---

    private fun entry(name: String) = zip.getEntry(name)

    private fun loadRels() {
        val e = entry("word/_rels/document.xml.rels") ?: return
        zip.getInputStream(e).use { input ->
            val p = OfficeHtml.parser(input)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.local() == "Relationship") {
                    val id = p.attr("Id"); val target = p.attr("Target")
                    if (id != null && target != null) rels[id] = target to (p.attr("TargetMode") == "External")
                }
                ev = p.next()
            }
        }
    }

    private fun on(p: XmlPullParser): Boolean { val v = p.attr("val"); return v == null || (v != "0" && v != "false" && v != "none") }

    private fun readRPr(p: XmlPullParser, into: RunProps) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (p.local()) {
                        "b" -> into.bold = on(p); "i" -> into.italic = on(p); "u" -> into.underline = on(p); "strike", "dstrike" -> into.strike = on(p)
                        "caps", "smallCaps" -> into.caps = on(p)
                        "color" -> OfficeHtml.hexColor(p.attr("val"))?.let { into.color = it }
                        "sz" -> p.attr("val")?.toIntOrNull()?.let { into.sizeHalf = it }
                        "rFonts" -> OfficeHtml.safeFont(
                            p.attr("ascii") ?: p.attr("hAnsi") ?: (p.attr("asciiTheme") ?: p.attr("hAnsiTheme"))?.let { themeFont(it) }
                        )?.let { into.font = it }
                        "highlight" -> OfficeHtml.HIGHLIGHT[p.attr("val")]?.let { into.highlight = it }
                        "shd" -> OfficeHtml.hexColor(p.attr("fill"))?.let { if (into.highlight == null) into.highlight = it }
                        "vertAlign" -> into.vert = p.attr("val")
                        "rStyle" -> into.style = p.attr("val")
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun readPPr(p: XmlPullParser, into: ParaProps) {
        var depth = 1
        var inMarkRPr = false
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    val n = p.local()
                    if (n == "rPr") { inMarkRPr = true }
                    if (!inMarkRPr) when (n) {
                        "pStyle" -> into.style = p.attr("val")
                        "jc" -> into.align = p.attr("val")
                        "ind" -> {
                            (p.attr("left") ?: p.attr("start"))?.toIntOrNull()?.let { into.left = it }
                            p.attr("firstLine")?.toIntOrNull()?.let { into.firstLine = it }
                            p.attr("hanging")?.toIntOrNull()?.let { into.hanging = it }
                        }
                        "spacing" -> { p.attr("before")?.toIntOrNull()?.let { into.before = it }; p.attr("after")?.toIntOrNull()?.let { into.after = it } }
                        "shd" -> OfficeHtml.hexColor(p.attr("fill"))?.let { into.shade = it }
                        "ilvl" -> into.ilvl = p.attr("val")?.toIntOrNull() ?: 0
                        "numId" -> into.numId = p.attr("val")?.toIntOrNull()
                    }
                }
                XmlPullParser.END_TAG -> { if (p.local() == "rPr") inMarkRPr = false; depth-- }
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private val themeFonts = HashMap<String, String>()
    private var defaultFont: String? = null

    /** "minorHAnsi" / "majorAscii" ... -> the theme's body or heading font. */
    private fun themeFont(key: String): String? = themeFonts[if (key.startsWith("major")) "major" else "minor"]

    private fun loadTheme() {
        val e = entry("word/theme/theme1.xml") ?: return
        zip.getInputStream(e).use { input ->
            val p = OfficeHtml.parser(input)
            var which: String? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (p.local()) {
                    "majorFont" -> which = "major"
                    "minorFont" -> which = "minor"
                    "latin" -> which?.let { w -> p.attr("typeface")?.takeIf { it.isNotBlank() }?.let { themeFonts[w] = it }; which = null }
                }
                ev = p.next()
            }
        }
    }

    private fun loadStyles() {
        val e = entry("word/styles.xml") ?: return
        zip.getInputStream(e).use { input ->
            val p = OfficeHtml.parser(input)
            var cur: Sty? = null
            var curId: String? = null
            var inDefaults = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.local()) {
                        "docDefaults" -> inDefaults = true
                        "style" -> { cur = Sty(); curId = p.attr("styleId") }
                        "name" -> cur?.name = p.attr("val").orEmpty()
                        "basedOn" -> cur?.basedOn = p.attr("val")
                        "rPr" -> if (inDefaults) { val d = RunProps(); readRPr(p, d); d.sizeHalf?.let { defaultSizeHalf = it }; d.font?.let { defaultFont = it } } else cur?.let { readRPr(p, it.run) }
                        "pPr" -> if (!inDefaults) cur?.let { readPPr(p, it.para) }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.local()) {
                        "docDefaults" -> inDefaults = false
                        "style" -> { val s = cur; val id = curId; if (s != null && id != null) styles[id] = s; cur = null; curId = null }
                    }
                }
                ev = p.next()
            }
        }
    }

    private fun loadNumbering() {
        val e = entry("word/numbering.xml") ?: return
        val abstracts = HashMap<Int, HashMap<Int, Lvl>>()
        val nums = HashMap<Int, Int>()
        zip.getInputStream(e).use { input ->
            val p = OfficeHtml.parser(input)
            var absId = -1; var numId = -1; var lvl = -1
            var fmt = "decimal"; var text = ""; var start = 1; var left = 0; var hanging = 0
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.local()) {
                        "abstractNum" -> absId = p.attr("abstractNumId")?.toIntOrNull() ?: -1
                        "lvl" -> { lvl = p.attr("ilvl")?.toIntOrNull() ?: 0; fmt = "decimal"; text = ""; start = 1; left = 0; hanging = 0 }
                        "numFmt" -> if (lvl >= 0) fmt = p.attr("val").orEmpty()
                        "lvlText" -> if (lvl >= 0) text = p.attr("val").orEmpty()
                        "start" -> if (lvl >= 0) start = p.attr("val")?.toIntOrNull() ?: 1
                        "ind" -> if (lvl >= 0) { left = (p.attr("left") ?: p.attr("start"))?.toIntOrNull() ?: 0; hanging = p.attr("hanging")?.toIntOrNull() ?: 0 }
                        "num" -> numId = p.attr("numId")?.toIntOrNull() ?: -1
                        "abstractNumId" -> if (numId >= 0) p.attr("val")?.toIntOrNull()?.let { nums[numId] = it }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.local()) {
                        "lvl" -> { if (absId >= 0 && lvl >= 0) abstracts.getOrPut(absId) { HashMap() }[lvl] = Lvl(fmt, text, start, left, hanging); lvl = -1 }
                        "abstractNum" -> absId = -1
                        "num" -> numId = -1
                    }
                }
                ev = p.next()
            }
        }
        for ((id, abs) in nums) abstracts[abs]?.let { numbering[id] = it }
    }

    // --- style resolution ---

    private fun chain(id: String?): List<Sty> {
        val list = ArrayList<Sty>()
        var cur = id
        var guard = 0
        while (cur != null && guard++ < 12) { val s = styles[cur] ?: break; list += s; cur = s.basedOn }
        return list.asReversed()   // base style first
    }

    private fun effectiveRun(para: ParaProps, direct: RunProps): RunProps {
        val r = RunProps()
        for (s in chain(para.style)) r.mergeFrom(s.run)
        for (s in chain(direct.style)) r.mergeFrom(s.run)
        r.mergeFrom(direct)
        return r
    }

    private fun effectivePara(direct: ParaProps): ParaProps {
        val r = ParaProps()
        for (s in chain(direct.style)) r.mergeFrom(s.para)
        r.mergeFrom(direct)
        return r
    }

    // --- walking document.xml ---

    private fun emit(html: String) { val c = tables.lastOrNull()?.cell; if (c != null) c.html.append(html) else body.append(html) }

    private fun walk() {
        val e = entry("word/document.xml") ?: throw IllegalArgumentException("Not a Word document")
        zip.getInputStream(e).use { input ->
            val p = OfficeHtml.parser(input)
            var skipFallback = 0
            var inTblPr = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (blocks > 60_000) { emit("<p><i>(The rest of this long document isn't shown.)</i></p>"); break }
                if (ev == XmlPullParser.START_TAG) {
                    val n = p.local()
                    if (n == "Fallback") skipFallback++
                    if (skipFallback > 0) { ev = p.next(); continue }
                    val ctx = paras.lastOrNull()
                    when (n) {
                        "p" -> { paras += ParaCtx(); blocks++ }
                        "pPr" -> ctx?.let { readPPr(p, it.pp) }
                        "r" -> ctx?.run = RunProps()
                        "rPr" -> if (ctx != null) readRPr(p, ctx.run)
                        "t", "instrText" -> { val t = p.nextText(); if (n == "t" && ctx != null) text(ctx, t) }
                        "tab" -> ctx?.let { text(it, "\t") }
                        "br", "cr" -> ctx?.let { if (p.attr("type") == "page") it.inner.append("<hr style=\"border:0;border-top:1px dashed #bbb;margin:10px 0\">") else it.inner.append("<br>") }
                        "hyperlink" -> if (ctx != null) {
                            val r = p.attr("id")?.let { rels[it] }
                            val href = r?.takeIf { it.second }?.first?.takeIf { it.startsWith("http", true) || it.startsWith("mailto:", true) }
                            if (href != null) { ctx.inner.append("<a href=\"${OfficeHtml.esc(href)}\">"); ctx.linkOpen = true }
                        }
                        "extent" -> extentW = p.attr("cx")?.toIntOrNull()?.div(9525) ?: 0
                        "blip" -> if (ctx != null) p.attr("embed")?.let { image(ctx, it) }
                        "imagedata" -> if (ctx != null) p.attr("id")?.let { image(ctx, it) }
                        "tbl" -> tables += TableB()
                        "tblPr" -> inTblPr = true
                        "tblStyle" -> if (inTblPr) tables.lastOrNull()?.let { t -> if (styles[p.attr("val")]?.name?.contains("grid", true) == true || p.attr("val")?.contains("grid", true) == true) t.bordered = true }
                        "top", "left", "bottom", "right", "insideH", "insideV", "start", "end" -> if (inTblPr && p.attr("val").let { it != null && it != "nil" && it != "none" }) tables.lastOrNull()?.bordered = true
                        "tr" -> tables.lastOrNull()?.let { it.row = ArrayList(); it.col = 0 }
                        "tc" -> tables.lastOrNull()?.let { t -> t.cell = Cell().also { c -> c.start = t.col } }
                        "gridSpan" -> tables.lastOrNull()?.cell?.span = p.attr("val")?.toIntOrNull() ?: 1
                        "vMerge" -> tables.lastOrNull()?.cell?.let { c -> if (p.attr("val") != "restart") c.skip = true }
                        "shd" -> if (ctx == null) tables.lastOrNull()?.cell?.fill = OfficeHtml.hexColor(p.attr("fill"))
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    val n = p.local()
                    if (n == "Fallback") { skipFallback--; ev = p.next(); continue }
                    if (skipFallback > 0) { ev = p.next(); continue }
                    when (n) {
                        "hyperlink" -> paras.lastOrNull()?.let { if (it.linkOpen) { it.inner.append("</a>"); it.linkOpen = false } }
                        "p" -> {
                            val ctx = paras.removeAt(paras.size - 1)
                            val html = paragraph(ctx)
                            val parent = paras.lastOrNull()
                            if (parent != null) parent.inner.append("<div class=\"tb\">$html</div>") else emit(html)
                        }
                        "tblPr" -> inTblPr = false
                        "tc" -> tables.lastOrNull()?.let { t ->
                            val c = t.cell
                            if (c != null) {
                                t.row?.add(c)
                                t.col += c.span
                                if (c.skip) {
                                    // A cell continuing the one above: stretch that one down instead.
                                    for (ri in t.rows.indices.reversed()) {
                                        val above = t.rows[ri].firstOrNull { !it.skip && it.start == c.start }
                                        if (above != null) { above.rowspan++; break }
                                    }
                                }
                            }
                            t.cell = null
                        }
                        "tr" -> tables.lastOrNull()?.let { t -> t.row?.let { t.rows += it }; t.row = null }
                        "tbl" -> if (tables.isNotEmpty()) { val t = tables.removeAt(tables.size - 1); emit(table(t)) }
                    }
                }
                ev = p.next()
            }
        }
    }

    // --- output ---

    private fun text(ctx: ParaCtx, s: String) {
        if (s.isEmpty()) return
        val r = effectiveRun(ctx.pp, ctx.run)
        val css = StringBuilder()
        if (r.bold == true) css.append("font-weight:700;")
        if (r.italic == true) css.append("font-style:italic;")
        val deco = listOfNotNull(if (r.underline == true) "underline" else null, if (r.strike == true) "line-through" else null)
        if (deco.isNotEmpty()) css.append("text-decoration:${deco.joinToString(" ")};")
        r.color?.takeIf { it != "#auto" }?.let { css.append("color:$it;") }
        r.sizeHalf?.let { css.append("font-size:${it / 2.0}pt;") }
        r.font?.let { css.append("font-family:${OfficeHtml.fontStack(it)};") }
        r.highlight?.let { css.append("background:$it;") }
        if (r.caps == true) css.append("text-transform:uppercase;")
        var html = OfficeHtml.esc(s)
        if (css.isNotEmpty()) html = "<span style=\"$css\">$html</span>"
        when (r.vert) { "superscript" -> html = "<sup>$html</sup>"; "subscript" -> html = "<sub>$html</sub>" }
        ctx.inner.append(html)
    }

    private fun image(ctx: ParaCtx, relId: String) {
        val (target, external) = rels[relId] ?: return
        if (external) return
        val path = "word/" + target.removePrefix("/word/").removePrefix("/")
        val e = entry(path) ?: entry(target.removePrefix("/")) ?: return
        val ext = e.name.substringAfterLast('.', "").lowercase()
        if (ext !in OfficeHtml.IMAGE_EXTENSIONS.split(' ')) { ctx.inner.append("<i>[image: .$ext not shown]</i>"); return }
        val name = media.getOrPut(e.name) {
            val n = "img${media.size + 1}.$ext"
            File(out, n).outputStream().use { o -> zip.getInputStream(e).use { it.copyTo(o) } }
            n
        }
        val w = if (extentW in 8..4000) " style=\"width:${extentW}px\"" else ""
        ctx.inner.append("<img src=\"$name\"$w>")
    }

    private fun fmtNumber(n: Int, fmt: String): String = when (fmt) {
        "lowerLetter" -> letters(n).lowercase(); "upperLetter" -> letters(n)
        "lowerRoman" -> roman(n).lowercase(); "upperRoman" -> roman(n)
        else -> n.toString()
    }

    private fun letters(n: Int): String { var x = n; val sb = StringBuilder(); while (x > 0) { x--; sb.insert(0, ('A' + x % 26)); x /= 26 }; return sb.toString() }

    private fun roman(n: Int): String {
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n; val sb = StringBuilder()
        for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
        return sb.toString()
    }

    private fun label(pp: ParaProps): Pair<String, Lvl>? {
        val numId = pp.numId ?: return null
        if (numId == 0) return null
        val lvl = numbering[numId]?.get(pp.ilvl) ?: return null
        val c = counters.getOrPut(numId) { IntArray(9) }
        val i = pp.ilvl.coerceIn(0, 8)
        c[i] = if (c[i] == 0) lvl.start else c[i] + 1
        for (j in i + 1..8) c[j] = 0
        if (lvl.fmt == "bullet") return (if (i == 0) "•" else if (i == 1) "◦" else "▪") to lvl
        var t = lvl.text.ifEmpty { "%${i + 1}." }
        for (j in 0..8) if (t.contains("%${j + 1}")) t = t.replace("%${j + 1}", fmtNumber(maxOf(c[j], 1), numbering[numId]?.get(j)?.fmt ?: "decimal"))
        return t to lvl
    }

    private fun paragraph(ctx: ParaCtx): String {
        val pp = effectivePara(ctx.pp)
        if (ctx.linkOpen) ctx.inner.append("</a>")
        val name = styles[ctx.pp.style]?.name?.lowercase().orEmpty()
        val tag = when {
            name.startsWith("heading ") -> "h" + (name.removePrefix("heading ").trim().toIntOrNull() ?: 1).coerceIn(1, 6)
            name == "title" -> "h1"
            else -> "p"
        }
        val css = StringBuilder()
        when (pp.align) { "center" -> css.append("text-align:center;"); "right", "end" -> css.append("text-align:right;"); "both", "distribute" -> css.append("text-align:justify;") }
        val lab = label(pp)
        val left = (lab?.second?.left ?: pp.left ?: 0)
        val hanging = lab?.second?.hanging ?: pp.hanging ?: 0
        if (left > 0) css.append("margin-left:${left / 15}px;")
        val indent = (pp.firstLine ?: 0) - hanging
        if (indent != 0) css.append("text-indent:${indent / 15}px;")
        pp.before?.let { css.append("margin-top:${it / 20.0}pt;") }
        pp.after?.let { css.append("margin-bottom:${it / 20.0}pt;") }
        pp.shade?.let { OfficeHtml.hexColor(it.removePrefix("#"))?.let { c -> css.append("background:$c;") } }
        val inner = if (ctx.inner.isEmpty()) "<br>" else ctx.inner.toString()
        val style = if (css.isNotEmpty()) " style=\"$css\"" else ""
        return if (lab != null) "<div class=\"li\"$style><span class=\"lbl\">${OfficeHtml.esc(lab.first)}</span>$inner</div>" else "<$tag$style>$inner</$tag>"
    }

    private fun table(t: TableB): String {
        val sb = StringBuilder("<table")
        if (t.bordered) sb.append(" border=\"1\" style=\"border:1px solid #888\"")
        sb.append(">")
        for (row in t.rows) {
            sb.append("<tr>")
            for (c in row) {
                if (c.skip) continue
                sb.append("<td")
                if (c.span > 1) sb.append(" colspan=\"${c.span}\"")
                if (c.rowspan > 1) sb.append(" rowspan=\"${c.rowspan}\"")
                val css = StringBuilder()
                if (t.bordered) css.append("border:1px solid #888;")
                OfficeHtml.hexColor(c.fill?.removePrefix("#"))?.let { css.append("background:$it;") }
                if (css.isNotEmpty()) sb.append(" style=\"$css\"")
                sb.append(">").append(c.html).append("</td>")
            }
            sb.append("</tr>")
        }
        return sb.append("</table>").toString()
    }
}

// =====================================================================================================================
// PowerPoint
// =====================================================================================================================

private class PptxConverter(private val zip: ZipFile, private val out: File) {
    private val slideWidthEmu: Long
    private val slideHeightEmu: Long
    private val media = HashMap<String, String>()

    init {
        var w = 9_144_000L; var h = 6_858_000L
        zip.getEntry("ppt/presentation.xml")?.let { e ->
            zip.getInputStream(e).use { input ->
                val p = OfficeHtml.parser(input)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && p.local() == "sldSz") { p.attr("cx")?.toLongOrNull()?.let { w = it }; p.attr("cy")?.toLongOrNull()?.let { h = it } }
                    ev = p.next()
                }
            }
        }
        slideWidthEmu = w; slideHeightEmu = h
    }

    fun convert(): OfficeHtml.Result {
        val slides = zip.entries().asSequence()
            .mapNotNull { e -> Regex("""ppt/slides/slide(\d+)\.xml""").matchEntire(e.name)?.let { it.groupValues[1].toInt() to e } }
            .sortedBy { it.first }.toList()
        require(slides.isNotEmpty()) { "Not a PowerPoint file" }
        val width = 960.0
        val scale = width / slideWidthEmu           // pixels per EMU
        val ptToPx = width / (slideWidthEmu / 12700.0)
        val cards = StringBuilder()
        for ((n, e) in slides) cards.append(slide(n, e, scale, ptToPx, width))
        val html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=980\">" +   // slides are 960 px wide: fit the whole slide to the screen
            "<style>${OfficeHtml.STYLE_SHEET} .slide{position:relative;width:${width}px;height:${slideHeightEmu * scale}px;margin:14px auto;background:#fff;" +
            "box-shadow:0 1px 4px rgba(0,0,0,.3);overflow:hidden;font-family:Calibri,Arial,sans-serif} .shape{position:absolute;overflow:hidden} .shape p{margin:0;white-space:pre-wrap} " +
            ".num{position:absolute;right:6px;bottom:2px;font:11px Arial;color:#888} body{overflow-x:auto;min-width:${width + 20}px}</style></head><body>$cards</body></html>"
        return OfficeHtml.Result(listOf(File(out, "index.html").also { it.writeText(html) }), listOf("Slides"))
    }

    private fun relMap(slideName: String): Map<String, String> {
        val map = HashMap<String, String>()
        val e = zip.getEntry("ppt/slides/_rels/$slideName.rels") ?: return map
        zip.getInputStream(e).use { input ->
            val p = OfficeHtml.parser(input)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.local() == "Relationship") { val id = p.attr("Id"); val t = p.attr("Target"); if (id != null && t != null) map[id] = t }
                ev = p.next()
            }
        }
        return map
    }

    private fun image(target: String): String? {
        val path = java.io.File("ppt/slides/$target").toPath().normalize().toString().replace('\\', '/')
        val e = zip.getEntry(path) ?: return null
        val ext = e.name.substringAfterLast('.', "").lowercase()
        if (ext !in OfficeHtml.IMAGE_EXTENSIONS.split(' ')) return null
        return media.getOrPut(e.name) {
            val n = "img${media.size + 1}.$ext"
            File(out, n).outputStream().use { o -> zip.getInputStream(e).use { it.copyTo(o) } }
            n
        }
    }

    private fun slide(n: Int, e: java.util.zip.ZipEntry, scale: Double, ptToPx: Double, width: Double): String {
        val rels = relMap(e.name.substringAfterLast('/'))
        val sb = StringBuilder("<div class=\"slide\">")
        zip.getInputStream(e).use { input ->
            val p = OfficeHtml.parser(input)
            var x = 0L; var y = 0L; var cx = 0L; var cy = 0L
            var shape = StringBuilder(); var inShape = false; var isPic = false; var picEmbed: String? = null
            var para = StringBuilder(); var inPara = false; var align = ""; var bullet = false; var pLevel = 0
            var bold = false; var italic = false; var under = false; var color: String? = null; var size = 0
            fun flush() {
                if (!inShape) return
                val box = "left:${x * scale}px;top:${y * scale}px;width:${maxOf(cx, 1L) * scale}px;height:${maxOf(cy, 1L) * scale}px;"
                if (isPic) {
                    val src = picEmbed?.let { rels[it] }?.let { image(it) }
                    if (src != null) sb.append("<div class=\"shape\" style=\"$box\"><img src=\"$src\" style=\"width:100%;height:100%;object-fit:contain\"></div>")
                } else if (shape.isNotEmpty()) sb.append("<div class=\"shape\" style=\"$box\">$shape</div>")
                shape = StringBuilder(); inShape = false; isPic = false; picEmbed = null
            }
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.local()) {
                        "sp", "pic", "graphicFrame" -> { flush(); inShape = true; isPic = p.local() == "pic"; x = 0; y = 0; cx = 0; cy = 0 }
                        "off" -> if (inShape) { x = p.attr("x")?.toLongOrNull() ?: 0; y = p.attr("y")?.toLongOrNull() ?: 0 }
                        "ext" -> if (inShape && p.attr("cx") != null) { cx = p.attr("cx")?.toLongOrNull() ?: 0; cy = p.attr("cy")?.toLongOrNull() ?: 0 }
                        "blip" -> if (inShape && isPic) picEmbed = p.attr("embed")
                        "p" -> if (inShape) { inPara = true; para = StringBuilder(); align = ""; bullet = false; pLevel = 0 }
                        "pPr" -> if (inPara) { align = p.attr("algn").orEmpty(); pLevel = p.attr("lvl")?.toIntOrNull() ?: 0 }
                        "buChar", "buAutoNum" -> if (inPara) bullet = true
                        "rPr" -> if (inPara) {
                            bold = p.attr("b") == "1"; italic = p.attr("i") == "1"; under = p.attr("u")?.let { it != "none" } ?: false
                            size = p.attr("sz")?.toIntOrNull() ?: 0; color = null
                        }
                        "srgbClr" -> if (inPara) color = OfficeHtml.hexColor(p.attr("val"))
                        "t" -> if (inPara) {
                            val css = StringBuilder()
                            if (bold) css.append("font-weight:700;"); if (italic) css.append("font-style:italic;"); if (under) css.append("text-decoration:underline;")
                            color?.let { css.append("color:$it;") }
                            css.append("font-size:${(if (size > 0) size / 100.0 else 18.0) * ptToPx}px;")
                            para.append("<span style=\"$css\">${OfficeHtml.esc(p.nextText())}</span>")
                        }
                        "br" -> if (inPara) para.append("<br>")
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.local()) {
                        "p" -> if (inPara) {
                            inPara = false
                            if (para.isNotEmpty()) {
                                val a = when (align) { "ctr" -> "center"; "r" -> "right"; "just" -> "justify"; else -> "left" }
                                val mark = if (bullet) "• " else ""
                                shape.append("<p style=\"text-align:$a;margin-left:${pLevel * 24}px\">$mark$para</p>")
                            }
                        }
                        "sp", "pic", "graphicFrame" -> flush()
                    }
                }
                ev = p.next()
            }
            flush()
        }
        return sb.append("<div class=\"num\">$n</div></div>").toString()
    }
}
