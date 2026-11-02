package com.sza.androidfolderexplorer.data

import android.webkit.MimeTypeMap

/** One place that decides what kind of file a name refers to, so every screen agrees. */
enum class FileKind { IMAGE, VIDEO, AUDIO, PDF, MARKDOWN, JSON, XML, CSV, LOG, TEXT, ARCHIVE, APK, SPLIT_PART, OTHER }

object FileTypes {
    val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
    val VIDEO = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts")
    val AUDIO = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma", "amr")
    val ARCHIVE = setOf("zip", "7z", "tar", "gz", "tgz", "bz2", "tbz", "tbz2", "xz", "txz", "iso", "rar")
    val TEXT = setOf(
        "txt", "kt", "kts", "java", "py", "js", "ts", "html", "htm", "css", "sh", "gradle", "ini", "cfg", "conf",
        "properties", "yml", "yaml", "toml", "c", "h", "cpp", "rs", "go", "sql", "bat", "ps1", "srt", "vtt", "nfo"
    )
    val DOCS = setOf("pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "json", "xml", "odt", "rtf")
    val SUBTITLES = setOf("srt", "vtt", "ass", "ssa")

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun kindOf(name: String): FileKind {
        val e = ext(name)
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
        FileKind.PDF, FileKind.MARKDOWN, FileKind.JSON, FileKind.XML, FileKind.CSV, FileKind.TEXT, FileKind.LOG -> "Documents"
        else -> if (ext(name) in DOCS) "Documents" else "Other"
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
