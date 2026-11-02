package com.sza.androidfolderexplorer.data.network

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.sza.androidfolderexplorer.data.SecretBox
import com.sza.androidfolderexplorer.data.settings.NetworkConnection
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import org.xmlpull.v1.XmlPullParser

data class RemoteItem(
    val name: String,
    /** Path (SFTP, FTP, SMB, WebDAV) or document id (document providers). */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val mime: String? = null
)

/** One remote storage. All calls block, so run them off the main thread. */
interface RemoteClient : Closeable {
    val rootPath: String
    fun list(path: String): List<RemoteItem>
    fun download(item: RemoteItem, out: OutputStream)
    fun upload(dirPath: String, name: String, input: InputStream, length: Long)
    fun mkdir(dirPath: String, name: String)
    fun delete(item: RemoteItem)
    fun rename(item: RemoteItem, newName: String)
}

internal fun join(dir: String, name: String) = dir.trimEnd('/') + "/" + name

/** Connects to [c]. Passwords are decrypted here and never logged. */
object RemoteClients {
    fun open(context: Context, c: NetworkConnection, hostKeys: HostKeyStore): RemoteClient {
        val password = SecretBox.decrypt(c.password)
        return when (c.protocol) {
            "sftp" -> SftpClient(c, password, hostKeys)
            "ftp" -> FtpClient(c, password)
            "smb" -> SmbClient(c, password)
            "webdav", "webdavs" -> WebDavClient(c, password)
            "saf" -> SafClient(context, Uri.parse(c.host))
            else -> throw IOException("Unknown protocol ${c.protocol}")
        }
    }
}

/** Remembers each SFTP server's key the first time it is seen and refuses a different key later. */
class HostKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("afe_hostkeys", Context.MODE_PRIVATE)
    fun get(hostPort: String): String? = prefs.getString(hostPort, null)
    fun put(hostPort: String, fp: String) = prefs.edit().putString(hostPort, fp).apply()
    fun forget(hostPort: String) = prefs.edit().remove(hostPort).apply()
}

// ---- SFTP ----

private class SftpClient(c: NetworkConnection, password: String, hostKeys: HostKeyStore) : RemoteClient {
    private val session: Session
    private val sftp: ChannelSftp
    override val rootPath = c.path.ifBlank { "/" }

    init {
        val jsch = JSch()
        val hostPort = "${c.host}:${c.port}"
        // Trust on first use: the first key is stored, a changed key is rejected before any password is sent.
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String?, key: ByteArray?): Int {
                val fp = HostKey(host, key).getFingerPrint(jsch)
                val known = hostKeys.get(hostPort)
                return when {
                    known == null -> { hostKeys.put(hostPort, fp); HostKeyRepository.OK }
                    known == fp -> HostKeyRepository.OK
                    else -> HostKeyRepository.CHANGED
                }
            }
            override fun add(hostkey: HostKey?, ui: UserInfo?) {}
            override fun remove(host: String?, type: String?) {}
            override fun remove(host: String?, type: String?, key: ByteArray?) {}
            override fun getKnownHostsRepositoryID() = "afe"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        session = jsch.getSession(c.user, c.host, c.port)
        session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.timeout = 15000
        try {
            session.connect(15000)
        } catch (e: Exception) {
            if (e.message?.contains("HostKey", true) == true)
                throw IOException("The server's identity changed since you last connected. If you expected this, remove and re-add the connection.")
            throw e
        }
        sftp = session.openChannel("sftp") as ChannelSftp
        sftp.connect(15000)
    }

    @Suppress("UNCHECKED_CAST")
    override fun list(path: String): List<RemoteItem> =
        (sftp.ls(path) as java.util.Vector<ChannelSftp.LsEntry>)
            .filter { it.filename != "." && it.filename != ".." }
            .map { RemoteItem(it.filename, join(path, it.filename), it.attrs.isDir, it.attrs.size, it.attrs.mTime * 1000L) }

    override fun download(item: RemoteItem, out: OutputStream) = sftp.get(item.path, out)
    override fun upload(dirPath: String, name: String, input: InputStream, length: Long) = sftp.put(input, join(dirPath, name))
    override fun mkdir(dirPath: String, name: String) = sftp.mkdir(join(dirPath, name))
    override fun delete(item: RemoteItem) { if (item.isDirectory) sftp.rmdir(item.path) else sftp.rm(item.path) }
    override fun rename(item: RemoteItem, newName: String) =
        sftp.rename(item.path, join(item.path.substringBeforeLast('/'), newName))

    override fun close() { runCatching { sftp.disconnect() }; runCatching { session.disconnect() } }
}

// ---- FTP ----

private class FtpClient(c: NetworkConnection, password: String) : RemoteClient {
    private val ftp = FTPClient()
    override val rootPath = c.path.ifBlank { "/" }

    init {
        ftp.connectTimeout = 15000
        ftp.connect(c.host, c.port)
        if (!ftp.login(c.user.ifBlank { "anonymous" }, password.ifBlank { "anonymous@" })) throw IOException("FTP login failed")
        ftp.enterLocalPassiveMode()
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        ftp.controlEncoding = "UTF-8"
    }

    override fun list(path: String): List<RemoteItem> =
        (ftp.listFiles(path) ?: emptyArray()).filter { it.name != "." && it.name != ".." }
            .map { RemoteItem(it.name, join(path, it.name), it.isDirectory, it.size, it.timestamp?.timeInMillis ?: 0L) }

    override fun download(item: RemoteItem, out: OutputStream) { if (!ftp.retrieveFile(item.path, out)) throw IOException("Download failed: ${ftp.replyString}") }
    override fun upload(dirPath: String, name: String, input: InputStream, length: Long) {
        if (!ftp.storeFile(join(dirPath, name), input)) throw IOException("Upload failed: ${ftp.replyString}")
    }
    override fun mkdir(dirPath: String, name: String) { if (!ftp.makeDirectory(join(dirPath, name))) throw IOException(ftp.replyString) }
    override fun delete(item: RemoteItem) {
        val ok = if (item.isDirectory) ftp.removeDirectory(item.path) else ftp.deleteFile(item.path)
        if (!ok) throw IOException(ftp.replyString.trim())
    }
    override fun rename(item: RemoteItem, newName: String) {
        if (!ftp.rename(item.path, join(item.path.substringBeforeLast('/'), newName))) throw IOException(ftp.replyString.trim())
    }
    override fun close() { runCatching { ftp.logout() }; runCatching { ftp.disconnect() } }
}

// ---- SMB ----

private class SmbClient(private val c: NetworkConnection, password: String) : RemoteClient {
    private val client = SMBClient()
    private val connection = client.connect(c.host, c.port)
    private val session = connection.authenticate(AuthenticationContext(c.user, password.toCharArray(), ""))
    private val shares = HashMap<String, DiskShare>()
    override val rootPath = c.path.ifBlank { "/" }

    /** "/Share/dir/file" -> share "Share" and path "dir\file". */
    private fun split(path: String): Pair<String, String> {
        val parts = path.trim('/').split('/', limit = 2)
        val share = parts.firstOrNull().orEmpty()
        if (share.isEmpty()) throw IOException("Enter the share name as the start folder, for example /Movies")
        return share to (parts.getOrNull(1)?.replace('/', '\\') ?: "")
    }

    private fun share(name: String): DiskShare =
        shares.getOrPut(name) { session.connectShare(name) as DiskShare }

    override fun list(path: String): List<RemoteItem> {
        val (s, p) = split(path)
        return share(s).list(p).filter { it.fileName != "." && it.fileName != ".." }.map {
            val dir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            RemoteItem(it.fileName, join(path, it.fileName), dir, it.endOfFile, it.lastWriteTime.toEpochMillis())
        }
    }

    override fun download(item: RemoteItem, out: OutputStream) {
        val (s, p) = split(item.path)
        share(s).openFile(p, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { f ->
            f.inputStream.use { it.copyTo(out) }
        }
    }

    override fun upload(dirPath: String, name: String, input: InputStream, length: Long) {
        val (s, p) = split(join(dirPath, name))
        share(s).openFile(p, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null).use { f ->
            f.outputStream.use { input.copyTo(it) }
        }
    }

    override fun mkdir(dirPath: String, name: String) { val (s, p) = split(join(dirPath, name)); share(s).mkdir(p) }
    override fun delete(item: RemoteItem) {
        val (s, p) = split(item.path)
        if (item.isDirectory) share(s).rmdir(p, true) else share(s).rm(p)
    }
    override fun rename(item: RemoteItem, newName: String) {
        val (s, p) = split(item.path)
        val access = EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE)
        val target = p.substringBeforeLast('\\', "").let { if (it.isEmpty()) newName else "$it\\$newName" }
        if (item.isDirectory) share(s).openDirectory(p, access, null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { it.rename(target) }
        else share(s).openFile(p, access, null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { it.rename(target) }
    }

    override fun close() {
        shares.values.forEach { runCatching { it.close() } }
        runCatching { session.close() }; runCatching { connection.close() }; runCatching { client.close() }
    }
}

// ---- WebDAV ----

private class WebDavClient(private val c: NetworkConnection, private val password: String) : RemoteClient {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val scheme = if (c.protocol == "webdavs") "https" else "http"
    private val base = "$scheme://${c.host}:${c.port}"
    override val rootPath = c.path.ifBlank { "/" }

    private fun req(path: String): Request.Builder {
        val url = base + path.split('/').joinToString("/") { Uri.encode(it) }
        return Request.Builder().url(url).header("Authorization", okhttp3.Credentials.basic(c.user, password))
    }

    private fun check(r: okhttp3.Response, what: String) {
        r.use { if (!it.isSuccessful) throw IOException("$what failed: HTTP ${it.code} ${it.message}") }
    }

    override fun list(path: String): List<RemoteItem> {
        val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"
            .toRequestBody("application/xml".toMediaType())
        val xml = http.newCall(req(path).method("PROPFIND", body).header("Depth", "1").build()).execute().use { r ->
            if (r.code != 207) throw IOException("Listing failed: HTTP ${r.code} ${r.message}")
            r.body?.string().orEmpty()
        }
        val p = android.util.Xml.newPullParser().apply { setInput(java.io.StringReader(xml)) }
        val items = mutableListOf<RemoteItem>()
        var href = ""; var size = 0L; var dir = false; var mod = 0L; var tag = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = p.name.substringAfter(':').lowercase()
                    if (tag == "response") { href = ""; size = 0; dir = false; mod = 0 }
                    if (tag == "collection") dir = true
                }
                XmlPullParser.TEXT -> when (tag) {
                    "href" -> href += p.text.trim()
                    "getcontentlength" -> size = p.text.trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> mod = runCatching {
                        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(p.text.trim())!!.time
                    }.getOrDefault(0L)
                }
                XmlPullParser.END_TAG -> {
                    if (p.name.substringAfter(':').lowercase() == "response") {
                        val decoded = Uri.decode(href).substringAfter("://").let { if (it.contains('/')) "/" + it.substringAfter('/') else it }
                        val clean = decoded.trimEnd('/')
                        if (clean.isNotEmpty() && clean != path.trimEnd('/')) items += RemoteItem(clean.substringAfterLast('/'), clean, dir, size, mod)
                    }
                    tag = ""
                }
            }
            ev = p.next()
        }
        return items
    }

    override fun download(item: RemoteItem, out: OutputStream) {
        http.newCall(req(item.path).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed: HTTP ${r.code}")
            r.body?.byteStream()?.copyTo(out)
        }
    }

    override fun upload(dirPath: String, name: String, input: InputStream, length: Long) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) { sink.writeAll(input.source()) }
        }
        check(http.newCall(req(join(dirPath, name)).put(body).build()).execute(), "Upload")
    }

    override fun mkdir(dirPath: String, name: String) =
        check(http.newCall(req(join(dirPath, name)).method("MKCOL", null).build()).execute(), "New folder")

    override fun delete(item: RemoteItem) = check(http.newCall(req(item.path).delete().build()).execute(), "Delete")

    override fun rename(item: RemoteItem, newName: String) {
        val dest = base + join(item.path.substringBeforeLast('/'), newName).split('/').joinToString("/") { Uri.encode(it) }
        check(http.newCall(req(item.path).method("MOVE", null).header("Destination", dest).header("Overwrite", "F").build()).execute(), "Rename")
    }

    override fun close() { http.dispatcher.executorService.shutdown() }
}

// ---- Document providers (Drive, Dropbox, OneDrive … through the system picker) ----

private class SafClient(private val context: Context, private val tree: Uri) : RemoteClient {
    private val cr = context.contentResolver
    override val rootPath: String = DocumentsContract.getTreeDocumentId(tree)

    /**
     * Cloud providers such as Google Drive often answer a folder request straight away with an empty
     * "still loading" result and fill it in a moment later. Asking again until the provider says it is
     * done is what makes folders show their contents instead of looking empty.
     */
    override fun list(path: String): List<RemoteItem> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, path)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val deadline = System.currentTimeMillis() + 60_000
        var emptyRetries = 0
        while (true) {
            val out = mutableListOf<RemoteItem>()
            var loading = false
            var error: String? = null
            cr.query(children, projection, null, null, null)?.use { c ->
                val extras = c.extras
                loading = extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
                error = extras?.getString(DocumentsContract.EXTRA_ERROR)
                while (c.moveToNext()) {
                    out += RemoteItem(c.getString(1) ?: "?", c.getString(0), c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4), c.getString(2))
                }
            }
            if (!loading) error?.let { throw IOException(it) }
            val timedOut = System.currentTimeMillis() > deadline
            // A provider that doesn't flag "loading" can still hand back an empty first answer; look once or twice more.
            val settled = !loading && (out.isNotEmpty() || emptyRetries >= 2)
            if (settled || timedOut) return out.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            if (!loading) emptyRetries++
            Thread.sleep(700)
        }
    }

    private fun docUri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    override fun download(item: RemoteItem, out: OutputStream) {
        (cr.openInputStream(docUri(item.path)) ?: throw IOException("Can't open ${item.name}")).use { it.copyTo(out) }
    }

    override fun upload(dirPath: String, name: String, input: InputStream, length: Long) {
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val doc = DocumentsContract.createDocument(cr, docUri(dirPath), mime, name) ?: throw IOException("The provider refused to create $name")
        (cr.openOutputStream(doc) ?: throw IOException("Can't write $name")).use { input.copyTo(it) }
    }

    override fun mkdir(dirPath: String, name: String) {
        DocumentsContract.createDocument(cr, docUri(dirPath), DocumentsContract.Document.MIME_TYPE_DIR, name) ?: throw IOException("Can't create folder")
    }

    override fun delete(item: RemoteItem) { if (!DocumentsContract.deleteDocument(cr, docUri(item.path))) throw IOException("The provider refused to delete") }
    override fun rename(item: RemoteItem, newName: String) { DocumentsContract.renameDocument(cr, docUri(item.path), newName) ?: throw IOException("The provider refused to rename") }
    override fun close() {}
}
