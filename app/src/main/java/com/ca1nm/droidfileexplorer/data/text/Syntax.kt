package com.ca1nm.droidfileexplorer.data.text

/**
 * Lightweight syntax colouring for the text editor. This is not a parser: each language family gets a small scanner
 * that picks out comments, strings, numbers, keywords, function calls, variables, tags and so on, which is enough to
 * make code, scripts, config, web and log files readable. [tokenize] returns flat (start, end, kind) triples; the
 * editor turns them into colour spans. Unknown file types return null from [langFor] and stay plain.
 */
object Syntax {
    const val NONE = 0
    const val KEYWORD = 1
    const val STRING = 2
    const val COMMENT = 3
    const val NUMBER = 4
    const val FUNCTION = 5
    const val TYPE = 6
    const val VARIABLE = 7
    const val TAG = 8
    const val ATTR = 9
    const val HEADING = 10
    const val ADDED = 11
    const val REMOVED = 12
    const val ERROR = 13
    const val WARN = 14
    const val KEY = 15
    const val BUILTIN = 16
    const val KIND_COUNT = 17

    /** Bigger files stay plain: colouring them would slow scrolling and typing. */
    const val MAX_CHARS = 200_000
    private const val MAX_TOKENS = 25_000

    enum class Style { CODE, MARKUP, MARKDOWN, DIFF, LOG }
    enum class LineKeys { NONE, YAML, INI }

    class Lang(
        val style: Style = Style.CODE,
        val lineComments: List<String> = emptyList(),
        val block: List<String>? = null,
        val block2: List<String>? = null,
        val keywords: Set<String> = emptySet(),
        val builtins: Set<String> = emptySet(),
        val types: Set<String> = emptySet(),
        val ignoreCase: Boolean = false,
        /** A single quote opens a string (scripting languages) rather than a one-character literal (C family). */
        val apostropheString: Boolean = false,
        val backtickString: Boolean = false,
        val tripleQuotes: Boolean = false,
        val dollarVars: Boolean = false,
        val percentVars: Boolean = false,
        val atAnnotations: Boolean = false,
        val capitalTypes: Boolean = false,
        val dashIdent: Boolean = false,
        val cmdlets: Boolean = false,
        val jsonKeys: Boolean = false,
        val keyColon: Boolean = false,
        val hexColors: Boolean = false,
        val backslashCommands: Boolean = false,
        val remComments: Boolean = false,
        val lineKeys: LineKeys = LineKeys.NONE
    )

    private fun kw(vararg s: String): Set<String> = s.joinToString(" ").split(' ').filter { it.isNotEmpty() }.toSet()

    // ---- keyword sets ----

    private val CLIKE = kw(
        "abstract as assert async await base bool break by case catch char checked class companion const constexpr constructor continue",
        "crossinline data debugger decimal default delegate delete do double dynamic else enum event explicit export extends extern",
        "external final finally fixed float for foreach fun func function get goto if implements implicit import in init inline inner",
        "instanceof int interface internal is lambda lateinit let lock long managed module mut mutable namespace native new noinline",
        "object of open operator out override package params partial private protected pub public readonly ref register reified return",
        "sealed select set short signed sizeof static string struct super suspend switch synchronized template this throw throws trait",
        "transient try typealias typedef typeof union unchecked unsigned unsafe use using val value var virtual void volatile when where",
        "while with yield"
    )
    private val C_BUILTINS = kw("true false null nil undefined NaN Infinity NULL nullptr None")
    private val RUST_EXTRA = kw("fn impl crate loop match dyn move Self")
    private val GO_EXTRA = kw("chan defer range fallthrough go map")
    private val JS_EXTRA = kw("delete from keyof readonly declare type enum export default")
    private val PHP_EXTRA = kw("echo print array isset unset empty foreach endforeach endif endwhile elseif require require_once include include_once")

    private val PYTHON = kw(
        "and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal",
        "not or pass raise return try while with yield match case"
    )
    private val RUBY = kw(
        "alias and begin break case class def defined do else elsif end ensure for if in module next not or redo rescue retry return",
        "self super then undef unless until when while yield require require_relative include extend attr_accessor attr_reader attr_writer"
    )
    private val SHELL = kw(
        "if then else elif fi for while until do done case esac in function select return exit break continue local export readonly unset",
        "set shift source alias echo printf cd pwd test read eval exec trap declare typeset let"
    )
    private val POWERSHELL = kw(
        "if elseif else switch while for foreach do until break continue return function filter param begin process end try catch finally",
        "throw trap in class using exit data dynamicparam workflow parallel sequence enum hidden static"
    )
    private val BATCH = kw(
        "echo off on set setlocal endlocal enabledelayedexpansion enableextensions if else not exist defined errorlevel goto call for in do",
        "exit rem start cd chdir cls copy del erase dir md mkdir move pause popd pushd rd rmdir ren rename shift title type ver xcopy",
        "robocopy taskkill timeout choice find findstr more sort tasklist reg net sc schtasks attrib assoc path prompt color mode"
    )
    private val SQL = kw(
        "select insert update delete from where join left right inner outer full cross on group by order having limit offset create table",
        "alter drop index view into values set and or not null is in like between as distinct union all case when then else end primary key",
        "foreign references default unique check constraint begin commit rollback pragma if exists asc desc add column trigger virtual using",
        "with recursive returning replace transaction savepoint release vacuum explain analyze"
    )
    private val SQL_TYPES = kw("int integer bigint smallint tinyint text varchar char real float double blob numeric decimal boolean date datetime timestamp time")
    private val LUA = kw("and break do else elseif end for function goto if in local not or repeat return then until while")
    private val R_LANG = kw("function if else for while repeat break next return in")
    private val DOCKER = kw("from run cmd label expose env add copy entrypoint volume user workdir arg onbuild stopsignal healthcheck shell as")
    private val MAKE = kw("ifeq ifneq ifdef ifndef else endif define endef include export override")
    private val CSS_KW = kw("important")
    private val YAML_BUILTINS = kw("true false null yes no on off True False Null")

    // ---- language table ----

    private val byName = HashMap<String, Lang>()

    private fun reg(lang: Lang, names: String) {
        for (n in names.split(' ')) if (n.isNotEmpty()) byName[n] = lang
    }

    init {
        val cStyle = Lang(
            lineComments = listOf("//"), block = listOf("/*", "*/"), keywords = CLIKE, builtins = C_BUILTINS,
            capitalTypes = true, atAnnotations = true, tripleQuotes = true
        )
        reg(cStyle, "kt kts java c h cpp cc cxx hpp hh cs scala groovy gradle swift glsl hlsl wgsl frag vert shader cu cl proto m mm")
        reg(Lang(lineComments = listOf("//"), block = listOf("/*", "*/"), keywords = CLIKE + RUST_EXTRA, builtins = C_BUILTINS, capitalTypes = true), "rs")
        reg(Lang(lineComments = listOf("//"), block = listOf("/*", "*/"), keywords = CLIKE + GO_EXTRA, builtins = C_BUILTINS, backtickString = true, capitalTypes = true), "go")
        reg(
            Lang(
                lineComments = listOf("//"), block = listOf("/*", "*/"), keywords = CLIKE + JS_EXTRA, builtins = C_BUILTINS,
                apostropheString = true, backtickString = true, capitalTypes = true, atAnnotations = true
            ),
            "js jsx mjs cjs tsx dart"
        )
        reg(
            Lang(
                lineComments = listOf("//", "#"), block = listOf("/*", "*/"), keywords = CLIKE + PHP_EXTRA, builtins = C_BUILTINS,
                apostropheString = true, dollarVars = true, capitalTypes = true
            ),
            "php"
        )
        reg(
            Lang(lineComments = listOf("#"), keywords = PYTHON, builtins = kw("True False None self cls"), apostropheString = true, tripleQuotes = true, atAnnotations = true, capitalTypes = true),
            "py pyw pyi"
        )
        reg(Lang(lineComments = listOf("#"), block = listOf("=begin", "=end"), keywords = RUBY, builtins = kw("true false nil"), apostropheString = true, capitalTypes = true), "rb rake gemspec")
        val shell = Lang(lineComments = listOf("#"), keywords = SHELL, builtins = kw("true false"), apostropheString = true, dollarVars = true)
        reg(shell, "sh bash zsh fish ksh csh awk sed pl pm bashrc zshrc profile gitignore gitattributes dockerignore npmrc cmake")
        reg(
            Lang(lineComments = listOf("#"), block = listOf("<#", "#>"), keywords = POWERSHELL, ignoreCase = true, apostropheString = true, dollarVars = true, cmdlets = true),
            "ps1 psm1 psd1"
        )
        reg(
            Lang(lineComments = listOf("::"), keywords = BATCH, ignoreCase = true, percentVars = true, remComments = true),
            "bat cmd"
        )
        reg(
            Lang(lineComments = listOf("--"), block = listOf("/*", "*/"), keywords = SQL, types = SQL_TYPES, ignoreCase = true, apostropheString = true),
            "sql"
        )
        reg(Lang(lineComments = listOf("--"), block = listOf("--[[", "]]"), keywords = LUA, builtins = kw("nil true false"), apostropheString = true), "lua")
        reg(Lang(lineComments = listOf("#"), keywords = R_LANG, builtins = kw("TRUE FALSE NULL NA"), apostropheString = true), "r")
        reg(Lang(lineComments = listOf("#"), keywords = DOCKER, ignoreCase = true, apostropheString = true, dollarVars = true), "dockerfile")
        reg(Lang(lineComments = listOf("#"), keywords = MAKE, apostropheString = true, dollarVars = true), "makefile mk mak")
        reg(
            Lang(block = listOf("/*", "*/"), keywords = CSS_KW, hexColors = true, atAnnotations = true, keyColon = true, dashIdent = true),
            "css"
        )
        reg(
            Lang(lineComments = listOf("//"), block = listOf("/*", "*/"), keywords = CSS_KW, hexColors = true, atAnnotations = true, keyColon = true, dashIdent = true, dollarVars = true),
            "scss less sass"
        )
        reg(Lang(builtins = kw("true false null"), jsonKeys = true), "json geojson webmanifest map")
        reg(Lang(lineComments = listOf("//"), block = listOf("/*", "*/"), builtins = kw("true false null"), jsonKeys = true, apostropheString = true), "json5 jsonc")
        reg(Lang(lineComments = listOf("#"), builtins = YAML_BUILTINS, apostropheString = true, lineKeys = LineKeys.YAML), "yml yaml")
        reg(
            Lang(lineComments = listOf("#", ";"), builtins = kw("true false"), apostropheString = true, lineKeys = LineKeys.INI),
            "ini cfg conf config properties env toml editorconfig gitconfig service timer socket desktop prefs rc cnf reg"
        )
        reg(Lang(lineComments = listOf("%"), backslashCommands = true), "tex sty cls bib")
        reg(Lang(style = Style.MARKUP), "html htm xhtml xml svg xsd xsl xslt plist rss atom xaml csproj vcxproj props targets resx manifest wsdl kml gpx fxml vue svelte cshtml aspx asp jsp ejs ui qrc")
        reg(Lang(style = Style.MARKDOWN), "md markdown mdx")
        reg(Lang(style = Style.DIFF), "diff patch")
        reg(Lang(style = Style.LOG), "log")
    }

    /** The colouring scheme for a file name, or null for a type that stays plain. */
    fun langFor(fileName: String): Lang? {
        val lower = fileName.lowercase()
        val key = if ('.' in lower) lower.substringAfterLast('.').trimEnd('_') else lower
        return byName[key] ?: byName[lower]
    }

    // ---- scanning ----

    private class Sink {
        var a = IntArray(3 * 2048)
        var n = 0
        fun full() = n / 3 >= MAX_TOKENS
        fun add(start: Int, end: Int, kind: Int) {
            if (end <= start || kind == NONE || full()) return
            if (n + 3 > a.size) a = a.copyOf(a.size * 2)
            a[n] = start; a[n + 1] = end; a[n + 2] = kind; n += 3
        }
        fun toArray(): IntArray = a.copyOf(n)
    }

    /** Flat (start, end, kind) triples, in increasing start order, for [text] under [lang]. */
    fun tokenize(text: String, lang: Lang): IntArray {
        val out = Sink()
        when (lang.style) {
            Style.CODE -> scanCode(text, lang, out)
            Style.MARKUP -> scanMarkup(text, out)
            Style.MARKDOWN -> scanMarkdown(text, out)
            Style.DIFF -> scanDiff(text, out)
            Style.LOG -> scanLog(text, out)
        }
        return out.toArray()
    }

    private fun eol(t: String, i: Int): Int { val e = t.indexOf('\n', i); return if (e < 0) t.length else e }

    private fun nextNonSpace(t: String, from: Int): Char {
        var j = from
        while (j < t.length && (t[j] == ' ' || t[j] == '\t')) j++
        return if (j < t.length) t[j] else '\n'
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun stringEnd(t: String, i: Int, L: Lang): Int {
        val n = t.length
        val q = t[i]
        if (q == '\'' && !L.apostropheString) {
            // a one-character literal such as 'a' or '\n'; anything longer is just an apostrophe
            var j = i + 1
            if (j < n && t[j] == '\\') j += 2 else j += 1
            var k = 0
            while (j < n && k < 6 && t[j] != '\'' && t[j] != '\n') { j++; k++ }
            return if (j < n && t[j] == '\'') j + 1 else 0
        }
        if (L.tripleQuotes && (q == '"' || q == '\'') && t.startsWith("$q$q$q", i)) {
            val idx = t.indexOf("$q$q$q", i + 3)
            return if (idx < 0) n else idx + 3
        }
        if (q == '`') {
            var j = i + 1
            while (j < n) { if (t[j] == '\\') { j += 2; continue }; if (t[j] == '`') return j + 1; j++ }
            return n
        }
        var j = i + 1
        while (j < n) {
            val ch = t[j]
            if (ch == '\\') { j += 2; continue }
            if (ch == q) return j + 1
            if (ch == '\n') return j
            j++
        }
        return n
    }

    private fun startsWithWord(t: String, i: Int, word: String): Boolean {
        if (!t.regionMatches(i, word, 0, word.length, ignoreCase = true)) return false
        val e = i + word.length
        return e >= t.length || !(t[e].isLetterOrDigit() || t[e] == '_')
    }

    private fun scanCode(t: String, L: Lang, out: Sink) {
        val n = t.length
        var i = 0
        var lineStart = true
        val blocks = listOfNotNull(L.block, L.block2)
        while (i < n && !out.full()) {
            val c = t[i]
            if (c == '\n') { i++; lineStart = true; continue }
            if (c == ' ' || c == '\t' || c == '\r') { i++; continue }
            val atLineStart = lineStart
            lineStart = false

            if (atLineStart && L.lineKeys != LineKeys.NONE) {
                val next = lineKey(t, i, L, out)
                if (next > i) { i = next; continue }
            }
            if (L.remComments && atLineStart && startsWithWord(t, i, "rem")) {
                val e = eol(t, i); out.add(i, e, COMMENT); i = e; continue
            }
            var done = false
            for (b in blocks) {
                if (t.startsWith(b[0], i)) {
                    val idx = t.indexOf(b[1], i + b[0].length)
                    val e = if (idx < 0) n else idx + b[1].length
                    out.add(i, e, COMMENT); i = e; done = true; break
                }
            }
            if (done) continue
            for (lc in L.lineComments) {
                if (t.startsWith(lc, i)) { val e = eol(t, i); out.add(i, e, COMMENT); i = e; done = true; break }
            }
            if (done) continue

            if (c == '"' || c == '\'' || (c == '`' && L.backtickString)) {
                val e = stringEnd(t, i, L)
                if (e > i) {
                    var kind = STRING
                    if (L.jsonKeys && c == '"' && nextNonSpace(t, e) == ':') kind = KEY
                    out.add(i, e, kind); i = e; continue
                }
                i++; continue
            }
            if (L.hexColors && c == '#' && i + 1 < n && isHex(t[i + 1])) {
                var j = i + 1
                while (j < n && isHex(t[j])) j++
                if (j - i - 1 in 3..8) { out.add(i, j, NUMBER); i = j; continue }
            }
            if (c in '0'..'9' || (c == '.' && i + 1 < n && t[i + 1] in '0'..'9')) {
                var j = i + 1
                while (j < n && (t[j].isLetterOrDigit() || t[j] == '_' || t[j] == '%' || (t[j] == '.' && j + 1 < n && t[j + 1] in '0'..'9'))) j++
                out.add(i, j, NUMBER); i = j; continue
            }
            if (L.dollarVars && c == '$' && i + 1 < n) {
                val d = t[i + 1]
                var j = i + 1
                when {
                    d == '{' || d == '(' -> {
                        val close = t.indexOf(if (d == '{') '}' else ')', j)
                        j = if (close in 0..(i + 80) && t.indexOf('\n', i).let { it < 0 || close < it }) close + 1 else i + 1
                    }
                    d.isLetter() || d == '_' -> { j++; while (j < n && (t[j].isLetterOrDigit() || t[j] == '_' || t[j] == ':')) j++ }
                    d.isDigit() || d in "?#@*!$-" -> j++
                }
                if (j > i + 1) { out.add(i, j, VARIABLE); i = j; continue }
            }
            if (L.percentVars && c == '%') {
                var j = i + 1
                if (j < n && (t[j].isDigit() || t[j] == '~' || t[j] == '*')) {
                    j++
                    while (j < n && (t[j].isLetterOrDigit() || t[j] == '~')) j++
                    out.add(i, j, VARIABLE); i = j; continue
                }
                val close = t.indexOf('%', i + 1)
                if (close > i + 1 && close - i < 60 && t.indexOf('\n', i).let { it < 0 || close < it }) {
                    out.add(i, close + 1, VARIABLE); i = close + 1; continue
                }
            }
            if (L.atAnnotations && c == '@' && i + 1 < n && t[i + 1].isLetter()) {
                var j = i + 1
                while (j < n && (t[j].isLetterOrDigit() || t[j] == '_' || t[j] == '.' || (L.dashIdent && t[j] == '-'))) j++
                out.add(i, j, ATTR); i = j; continue
            }
            if (L.backslashCommands && c == '\\') {
                var j = i + 1
                if (j < n && t[j].isLetter()) { while (j < n && t[j].isLetter()) j++ } else if (j < n) j++
                out.add(i, j, KEYWORD); i = j; continue
            }
            if (L.cmdlets && c == '[') {
                val close = t.indexOf(']', i + 1)
                if (close > i + 1 && close - i < 40) {
                    val inner = t.substring(i + 1, close)
                    if (inner.all { it.isLetterOrDigit() || it == '.' || it == ',' || it == '_' || it == '[' || it == ']' || it == ' ' }) {
                        out.add(i, close + 1, TYPE); i = close + 1; continue
                    }
                }
            }
            if (c.isLetter() || c == '_') {
                var j = i + 1
                while (j < n && (t[j].isLetterOrDigit() || t[j] == '_' || (L.dashIdent && t[j] == '-' && j + 1 < n && t[j + 1].isLetter()))) j++
                if (L.cmdlets && j < n && t[j] == '-' && j + 1 < n && t[j + 1].isLetter()) {
                    var k = j + 1
                    while (k < n && t[k].isLetterOrDigit()) k++
                    out.add(i, k, FUNCTION); i = k; continue
                }
                val word = t.substring(i, j)
                val key = if (L.ignoreCase) word.lowercase() else word
                val kind = when {
                    key in L.keywords -> KEYWORD
                    key in L.builtins -> BUILTIN
                    key in L.types -> TYPE
                    L.capitalTypes && word.length > 1 && word[0].isUpperCase() && word.any { it.isLowerCase() } -> TYPE
                    L.keyColon && t.startsWith(":", j) && !t.startsWith("::", j) -> KEY
                    nextNonSpace(t, j) == '(' -> FUNCTION
                    else -> NONE
                }
                out.add(i, j, kind); i = j; continue
            }
            i++
        }
    }

    /** "key:" in YAML, "key =" and "[section]" in INI-style files, at the start of a line. Returns the new position, or [i]. */
    private fun lineKey(t: String, i: Int, L: Lang, out: Sink): Int {
        val n = t.length
        val e = eol(t, i)
        if (L.lineKeys == LineKeys.INI && t[i] == '[') {
            val close = t.indexOf(']', i)
            if (close in i..e) { out.add(i, close + 1, HEADING); return close + 1 }
            return i
        }
        var p = i
        if (L.lineKeys == LineKeys.YAML && t[p] == '-' && p + 1 < n && t[p + 1] == ' ') {
            p += 2
            while (p < e && t[p] == ' ') p++
        }
        var j = p
        while (j < e && (t[j].isLetterOrDigit() || t[j] == '_' || t[j] == '-' || t[j] == '.' || t[j] == '$' || t[j] == '/' || (t[j] == ' ' && L.lineKeys == LineKeys.INI))) j++
        var keyEnd = j
        while (keyEnd > p && t[keyEnd - 1] == ' ') keyEnd--
        if (j >= e || keyEnd <= p) return i
        val sep = t[j]
        val ok = when (L.lineKeys) {
            LineKeys.YAML -> sep == ':' && (j + 1 >= e || t[j + 1] == ' ')
            LineKeys.INI -> sep == '=' || sep == ':'
            LineKeys.NONE -> false
        }
        if (!ok) return i
        out.add(p, keyEnd, KEY)
        return j
    }

    private fun scanMarkup(t: String, out: Sink) {
        val n = t.length
        var i = 0
        while (i < n && !out.full()) {
            val lt = t.indexOf('<', i)
            if (lt < 0) break
            if (t.startsWith("<!--", lt)) {
                val idx = t.indexOf("-->", lt + 4)
                val e = if (idx < 0) n else idx + 3
                out.add(lt, e, COMMENT); i = e; continue
            }
            if (t.startsWith("<![CDATA[", lt)) {
                val idx = t.indexOf("]]>", lt)
                val e = if (idx < 0) n else idx + 3
                out.add(lt, e, STRING); i = e; continue
            }
            if (lt + 1 < n && (t[lt + 1].isLetter() || t[lt + 1] == '/' || t[lt + 1] == '!' || t[lt + 1] == '?')) {
                var j = lt + 1
                if (t[j] == '/' || t[j] == '!' || t[j] == '?') j++
                while (j < n && (t[j].isLetterOrDigit() || t[j] == ':' || t[j] == '-' || t[j] == '_' || t[j] == '.')) j++
                out.add(lt, j, TAG)
                while (j < n && !out.full()) {
                    val ch = t[j]
                    if (ch == '>') { out.add(j, j + 1, TAG); j++; break }
                    if ((ch == '/' || ch == '?') && j + 1 < n && t[j + 1] == '>') { out.add(j, j + 2, TAG); j += 2; break }
                    if (ch == '"' || ch == '\'') {
                        val close = t.indexOf(ch, j + 1)
                        val e = if (close < 0) n else close + 1
                        out.add(j, e, STRING); j = e; continue
                    }
                    if (ch.isLetter() || ch == '_' || ch == ':' || ch == '@') {
                        var k = j + 1
                        while (k < n && (t[k].isLetterOrDigit() || t[k] == ':' || t[k] == '-' || t[k] == '_' || t[k] == '.' || t[k] == '@')) k++
                        out.add(j, k, ATTR); j = k; continue
                    }
                    if (ch == '<') break
                    j++
                }
                i = j
            } else {
                i = lt + 1
            }
        }
    }

    private fun scanMarkdown(t: String, out: Sink) {
        val n = t.length
        var i = 0
        var fenced = false
        while (i < n && !out.full()) {
            val e = eol(t, i)
            if (t.startsWith("```", i) || t.startsWith("~~~", i)) {
                fenced = !fenced
                out.add(i, e, STRING)
            } else if (fenced) {
                out.add(i, e, STRING)
            } else {
                var p = i
                while (p < e && p - i < 3 && t[p] == ' ') p++
                var handled = false
                if (p < e && t[p] == '#') {
                    var c = 0
                    while (p + c < e && t[p + c] == '#') c++
                    if (c in 1..6 && (p + c >= e || t[p + c] == ' ')) { out.add(i, e, HEADING); handled = true }
                } else if (p < e && t[p] == '>') {
                    out.add(p, e, COMMENT); handled = true
                }
                if (!handled) {
                    if (p + 1 < e && (t[p] == '-' || t[p] == '*' || t[p] == '+') && t[p + 1] == ' ') out.add(p, p + 1, KEYWORD)
                    var j = p
                    while (j < e) {
                        if (t[j] == '`') {
                            val close = t.indexOf('`', j + 1)
                            if (close in (j + 1) until e) { out.add(j, close + 1, STRING); j = close + 1; continue }
                        }
                        if (t[j] == '[') {
                            val close = t.indexOf("](", j + 1)
                            val end = if (close > 0 && close < e) t.indexOf(')', close) else -1
                            if (end in 1 until e) { out.add(j, close + 1, FUNCTION); out.add(close + 1, end + 1, COMMENT); j = end + 1; continue }
                        }
                        j++
                    }
                }
            }
            i = e + 1
        }
    }

    private fun scanDiff(t: String, out: Sink) {
        val n = t.length
        var i = 0
        while (i < n && !out.full()) {
            val e = eol(t, i)
            when {
                t.startsWith("+++", i) || t.startsWith("---", i) || t.startsWith("diff ", i) || t.startsWith("index ", i) -> out.add(i, e, HEADING)   // file headers: not the red of a removed line
                t.startsWith("@@", i) -> out.add(i, e, FUNCTION)
                t[i] == '+' -> out.add(i, e, ADDED)
                t[i] == '-' -> out.add(i, e, REMOVED)
            }
            i = e + 1
        }
    }

    private fun scanLog(t: String, out: Sink) {
        val n = t.length
        var i = 0
        while (i < n && !out.full()) {
            val e = eol(t, i)
            val line = t.substring(i, e)
            val kind = when {
                line.contains("ERROR", true) || line.contains("FATAL", true) || line.contains("Exception") || line.contains(" E/") || line.contains("SEVERE", true) -> ERROR
                line.contains("WARN", true) || line.contains(" W/") -> WARN
                else -> NONE
            }
            if (kind != NONE) {
                out.add(i, e, kind)
            } else {
                // a leading timestamp, e.g. "2026-11-02 10:22:03.123" or "11-02 22:00:02.978"
                var j = i
                while (j < e && j - i < 30 && (t[j].isDigit() || t[j] in ":-./ T,[]")) j++
                if (j - i >= 8 && t[i].isDigit()) out.add(i, j, NUMBER)
            }
            i = e + 1
        }
    }
}
