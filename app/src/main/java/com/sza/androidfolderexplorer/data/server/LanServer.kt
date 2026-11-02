package com.sza.androidfolderexplorer.data.server

import android.content.Context
import android.net.wifi.WifiManager
import com.sza.androidfolderexplorer.data.formatSize
import com.sza.androidfolderexplorer.data.source.FileOps
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerState(val running: Boolean = false, val url: String = "", val root: String = "", val allowUpload: Boolean = false, val requests: Int = 0, val error: String? = null)

/**
 * A small web server for the local network: browse, download and (optionally) upload from a
 * PC browser. Every request needs the random access key in the link (shown as a QR code);
 * it is then kept in a cookie. Nothing is reachable outside the chosen folder.
 */
object LanServer {
    private var server: Server? = null
    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    fun localIp(context: Context): String? {
        // Prefer the Wi-Fi interface; fall back to any private IPv4 address.
        runCatching {
            @Suppress("DEPRECATION")
            val ip = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.ipAddress
            if (ip != 0) return "%d.%d.%d.%d".format(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
        }
        return NetworkInterface.getNetworkInterfaces().asSequence().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }.filterIsInstance<Inet4Address>()
            .map { it.hostAddress }.firstOrNull { it != null && (it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.")) }
    }

    @Synchronized
    fun start(context: Context, root: File, allowUpload: Boolean, port: Int = 8080) {
        stop()
        val ip = localIp(context)
        if (ip == null) { _state.value = ServerState(error = "Connect to Wi-Fi first"); return }
        val key = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        try {
            root.mkdirs()
            server = Server(port, root.canonicalFile, allowUpload, key).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            _state.value = ServerState(true, "http://$ip:$port/?k=$key", root.absolutePath, allowUpload)
        } catch (e: Exception) {
            _state.value = ServerState(error = "Couldn't start: ${e.message}")
        }
    }

    @Synchronized
    fun stop() { server?.stop(); server = null; _state.value = ServerState() }

    private class Server(port: Int, private val root: File, private val upload: Boolean, private val key: String) : NanoHTTPD(port) {
        private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private fun authorised(s: IHTTPSession): Boolean {
            s.parameters["k"]?.firstOrNull()?.let { if (same(it, key)) return true }
            val cookie = s.headers["cookie"].orEmpty()
            return cookie.split(';').map { it.trim() }.any { it.startsWith("afe=") && same(it.removePrefix("afe="), key) }
        }

        private fun resolve(rel: String): File? {
            val f = File(root, rel.trimStart('/')).canonicalFile
            return if (f == root || f.path.startsWith(root.path + File.separator)) f else null
        }

        override fun serve(session: IHTTPSession): Response {
            if (!authorised(session)) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Open the link or QR code shown in the app.")
            _state.value = _state.value.copy(requests = _state.value.requests + 1)
            val rel = java.net.URLDecoder.decode(session.uri, "UTF-8")
            val target = resolve(rel) ?: return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Not allowed")
            val resp: Response = when {
                session.method == Method.POST && upload && target.isDirectory -> handleUpload(session, target, rel)
                session.method == Method.POST -> newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Uploads are turned off")
                session.method != Method.GET -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "")
                target.isDirectory -> listing(target, rel)
                target.isFile -> download(target)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
            // Keep the key in a cookie so links inside the page work without ?k=.
            resp.addHeader("Set-Cookie", "afe=$key; Path=/; HttpOnly; SameSite=Strict")
            resp.addHeader("Cache-Control", "no-store")
            return resp
        }

        private fun download(f: File): Response {
            val mime = com.sza.androidfolderexplorer.data.FileTypes.mimeOf(f.name)
            val r = newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(f), f.length())
            r.addHeader("Content-Disposition", "attachment; filename=\"${f.name.replace("\"", "")}\"")
            return r
        }

        private fun handleUpload(session: IHTTPSession, dir: File, rel: String): Response {
            val files = HashMap<String, String>()
            return try {
                session.parseBody(files)
                var n = 0
                for ((field, tmp) in files) {
                    val original = session.parameters[field]?.firstOrNull()?.substringAfterLast('/')?.substringAfterLast('\\') ?: continue
                    val clean = original.replace(Regex("[\\\\/:*?\"<>|]"), "_").trimStart('.').ifBlank { "upload" }
                    File(tmp).copyTo(FileOps.uniqueName(dir, clean))
                    n++
                }
                newFixedLengthResponse(Response.Status.OK, "text/html", page("Uploaded", "<p>Uploaded $n file(s).</p><p><a href=\"${esc(rel)}\">Back</a></p>"))
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Upload failed: ${e.message}")
            } finally { files.values.forEach { File(it).delete() } }
        }

        private fun listing(dir: File, rel: String): Response {
            val base = "/" + rel.trim('/').let { if (it.isEmpty()) "" else "$it/" }
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val rows = (dir.listFiles() ?: emptyArray())
                .filter { !it.name.startsWith(".") }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .joinToString("") { f ->
                    val href = base + java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
                    "<tr><td><a href=\"$href\">${if (f.isDirectory) "📁 " else ""}${esc(f.name)}${if (f.isDirectory) "/" else ""}</a></td>" +
                        "<td>${if (f.isDirectory) "" else formatSize(f.length())}</td><td>${fmt.format(Date(f.lastModified()))}</td></tr>"
                }
            val up = if (upload) "<form method=\"post\" enctype=\"multipart/form-data\"><input type=\"file\" name=\"file\" multiple> <button>Upload here</button></form>" else ""
            val parent = if (dir != root) "<p><a href=\"${base.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }}\">⬆ Up</a></p>" else ""
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                page(esc(dir.name.ifEmpty { "Files" }), "$parent$up<table><tr><th>Name</th><th>Size</th><th>Modified</th></tr>$rows</table>"))
        }

        private fun page(title: String, body: String) = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title><style>body{font-family:system-ui,sans-serif;margin:24px;max-width:900px}table{border-collapse:collapse;width:100%}td,th{padding:8px;border-bottom:1px solid #ddd;text-align:left}a{text-decoration:none}</style></head>
<body><h2>$title</h2>$body</body></html>"""
    }
}
