package com.sza.androidfolderexplorer.data.ops

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

/**
 * Turns the Uri from an "Open with" request into a path this app can browse. Other apps hand over content:// Uris
 * (a browser's download, a document provider); where the real file can be worked out it is used in place, so edits
 * reach the original. Otherwise the file is copied to the app's cache and marked [Result.temporary].
 */
object IncomingFiles {

    data class Result(val path: String, val isDirectory: Boolean, val temporary: Boolean)

    private const val PRIMARY_ROOT = "/storage/emulated/0"

    fun resolve(context: Context, uri: Uri): Result? {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return null)
            return if (f.exists()) Result(f.path, f.isDirectory, false) else null
        }
        if (uri.scheme != "content") return null
        realPath(context, uri)?.let { return Result(it.path, it.isDirectory, false) }
        return copyToCache(context, uri)
    }

    private fun realPath(context: Context, uri: Uri): File? {
        val auth = uri.authority.orEmpty()
        val docId = runCatching {
            when {
                DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
                DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                else -> null
            }
        }.getOrNull()

        // The phone's own storage and SD cards: "primary:Download/x.zip" -> /storage/emulated/0/Download/x.zip
        if (auth == "com.android.externalstorage.documents" && docId != null) {
            val volume = docId.substringBefore(':')
            val rest = docId.substringAfter(':', "")
            val root = if (volume.equals("primary", true)) PRIMARY_ROOT else "/storage/$volume"
            return File(if (rest.isEmpty()) root else "$root/$rest").takeIf { it.exists() }
        }
        // The Downloads provider: "raw:/storage/..." is already a path; numeric ids are looked up in MediaStore.
        if (auth == "com.android.providers.downloads.documents" && docId != null) {
            if (docId.startsWith("raw:")) return File(docId.removePrefix("raw:")).takeIf { it.exists() }
            val id = docId.substringAfter(':').toLongOrNull()
            if (id != null) dataColumn(context, ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id))?.let { return it }
        }
        if (auth == "com.android.providers.media.documents" && docId != null) {
            val id = docId.substringAfter(':').toLongOrNull()
            val base = when (docId.substringBefore(':')) {
                "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Files.getContentUri("external")
            }
            if (id != null) dataColumn(context, ContentUris.withAppendedId(base, id))?.let { return it }
        }
        // MediaStore Uris (content://media/...) carry the path in _data.
        dataColumn(context, uri)?.let { return it }

        // A file provider (a browser, say) hides the real path. If the same name and size sit in Downloads, or the
        // Uri's own path maps onto storage, it's the same file: use it rather than copy it.
        val (name, size) = nameAndSize(context, uri)
        val segments = uri.pathSegments.orEmpty()
        val guesses = buildList {
            if (segments.size > 1) {
                val rest = segments.drop(1).joinToString("/")
                add(File("/$rest")); add(File("$PRIMARY_ROOT/$rest"))
            }
            if (name != null) add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name))
        }
        return guesses.firstOrNull { it.isFile && (size == null || it.length() == size) }
    }

    private fun dataColumn(context: Context, uri: Uri): File? = runCatching {
        context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.startsWith("/") }?.let { File(it) }?.takeIf { it.exists() } else null
        }
    }.getOrNull()

    private fun nameAndSize(context: Context, uri: Uri): Pair<String?, Long?> = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null to null
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
            val s = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
            n to s
        } ?: (null to null)
    }.getOrDefault(null to null)

    /** Copies the content to the cache (replacing the previous copy, so this never piles up). */
    private fun copyToCache(context: Context, uri: Uri): Result? = runCatching {
        val (name, _) = nameAndSize(context, uri)
        val safeName = (name ?: uri.lastPathSegment ?: "file").replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        val root = File(context.cacheDir, "incoming")
        root.deleteRecursively()
        val dest = File(root, safeName).also { it.parentFile?.mkdirs() }
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
        } ?: return null
        Result(dest.path, false, true)
    }.getOrNull()
}
