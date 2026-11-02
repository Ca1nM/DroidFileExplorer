package com.sza.androidfolderexplorer.data

import android.webkit.MimeTypeMap

/** One place that decides what kind of file a name refers to, so every screen agrees. */
enum class FileKind { IMAGE, VIDEO, AUDIO, PDF, MARKDOWN, JSON, XML, CSV, LOG, TEXT, ARCHIVE, APK, SPLIT_PART, DOCUMENT, DATABASE, OTHER }

object FileTypes {
    val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "ico", "dng", "wbmp")
    val VIDEO = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts")
    val AUDIO = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma", "amr")
    val ARCHIVE = setOf("zip", "7z", "tar", "gz", "tgz", "bz2", "tbz", "tbz2", "xz", "txz", "iso", "rar")
    val TEXT = setOf(
        "txt", "kt", "kts", "java", "py", "js", "ts", "html", "htm", "css", "sh", "gradle", "ini", "cfg", "conf",
        "properties", "yml", "yaml", "toml", "c", "h", "cpp", "rs", "go", "sql", "bat", "ps1", "srt", "vtt", "nfo",
        // More configs, scripts and plain-text formats
        "env", "rc", "cnf", "config", "prefs", "plist", "reg", "service", "desktop", "list", "lst", "lock", "mk", "cmake",
        "gitignore", "gitattributes", "editorconfig", "dockerignore", "npmrc", "bashrc", "zshrc", "profile", "hosts",
        "rb", "php", "pl", "lua", "swift", "cs", "cc", "hpp", "hh", "m", "scala", "dart", "r", "jsx", "tsx", "vue",
        "scss", "sass", "less", "ejs", "tpl", "tmpl", "j2", "jinja", "diff", "patch", "tex", "rst", "adoc", "org",
        "json5", "jsonc", "ndjson", "jsonl", "geojson", "gpx", "kml", "xsd", "xsl", "xslt", "svg", "rss", "atom",
        "pem", "crt", "csr", "pub", "ovpn", "m3u", "m3u8", "pls", "ass", "ssa", "vcf", "ics", "sub", "smali", "aidl", "pro",
        // Windows / PC scripts, project and web files
        "cmd", "vbs", "wsf", "psm1", "psd1", "hta", "asp", "aspx", "jsp", "cshtml", "razor", "xaml", "csproj", "vcxproj", "sln",
        "props", "targets", "resx", "manifest", "inf", "nsi", "ahk", "au3", "bash", "zsh", "fish", "ksh", "csh", "awk", "sed",
        "mak", "mjs", "cjs", "tf", "hcl", "proto", "graphql", "gql", "zig", "nim", "hs", "ex", "exs", "erl", "clj", "fsx", "vb",
        "pas", "asm", "ino", "pde", "cu", "hlsl", "wgsl", "frag", "vert", "shader", "gitmodules", "gitconfig", "htaccess",
        "timer", "socket", "mdx", "svelte", "pyw", "pyi", "gemspec", "rake", "bib", "sty", "mm", "groovy", "webmanifest"
    )
    /** Opened in the built-in document reader: Office, OpenDocument, e-books, rich text, and fonts (shown as a specimen). */
    val DOCUMENT = setOf(
        "docx", "docm", "dotx", "dotm", "xlsx", "xlsm", "xltx", "xltm", "pptx", "pptm", "potx", "ppsx",
        "odt", "ott", "ods", "ots", "odp", "otp", "rtf", "epub", "doc", "xls", "ppt", "dot", "xlt", "pps",
        "ttf", "otf", "ttc"
    )
    val DATABASE = setOf("db", "sqlite", "sqlite3", "db3")
    val DOCS = setOf("pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "json", "xml", "odt", "rtf")
    val SUBTITLES = setOf("srt", "vtt", "ass", "ssa")

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun kindOf(name: String): FileKind {
        val e = ext(name).trimEnd('_')   // "photo.png_" (a renamed/disabled copy) is still a PNG
        return when {
            e in IMAGE -> FileKind.IMAGE
            e in VIDEO -> FileKind.VIDEO
            e in AUDIO -> FileKind.AUDIO
            e == "pdf" -> FileKind.PDF
            e == "md" || e == "markdown" -> FileKind.MARKDOWN
            e == "json" -> FileKind.JSON
            e == "xml" -> FileKind.XML
            e == "csv" || e == "tsv" -> FileKind.CSV
            e == "log" -> FileKind.LOG
            e in DOCUMENT -> FileKind.DOCUMENT
            e in DATABASE -> FileKind.DATABASE
            e in TEXT -> FileKind.TEXT
            e in ARCHIVE -> FileKind.ARCHIVE
            e == "apk" || e in setOf("apks", "xapk", "apkm") -> FileKind.APK
            e.matches(Regex("\\d{3}")) -> FileKind.SPLIT_PART
            else -> FileKind.OTHER
        }
    }

    fun isImage(name: String) = ext(name) in IMAGE
    fun isVideo(name: String) = ext(name) in VIDEO
    fun isAudio(name: String) = ext(name) in AUDIO
    fun isMedia(name: String) = isVideo(name) || isAudio(name)

    fun mimeOf(name: String): String {
        val e = ext(name)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: when (e) {
            "md" -> "text/markdown"
            "kt", "kts", "log", "ini", "toml", "yml", "yaml" -> "text/plain"
            "mkv" -> "video/x-matroska"
            "opus" -> "audio/opus"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    /** Category used by the storage overview. */
    fun categoryOf(name: String): String = when (kindOf(name)) {
        FileKind.IMAGE -> "Images"
        FileKind.VIDEO -> "Video"
        FileKind.AUDIO -> "Audio"
        FileKind.ARCHIVE -> "Archives"
        FileKind.APK -> "APKs"
        FileKind.PDF, FileKind.MARKDOWN, FileKind.JSON, FileKind.XML, FileKind.CSV, FileKind.TEXT, FileKind.LOG, FileKind.DOCUMENT -> "Documents"
        else -> if (ext(name) in DOCS) "Documents" else "Other"
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
