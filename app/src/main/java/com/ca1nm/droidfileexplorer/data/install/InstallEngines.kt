package com.ca1nm.droidfileexplorer.data.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.ca1nm.droidfileexplorer.data.source.ShizukuFileAccess
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class Engine(val label: String) {
    AUTO("Automatic"), SHIZUKU("Shizuku (silent)"), SYSTEM("Android installer")
}

data class InstallOptions(
    val replaceExisting: Boolean = true,
    val allowDowngrade: Boolean = false,
    val grantAllPermissions: Boolean = false,
    /** Android 14+ refuses apps built for very old Android versions unless told to allow them. */
    val allowOldApps: Boolean = false
)

sealed interface InstallResult {
    data class Success(val message: String) : InstallResult
    data class Failure(val message: String, val code: String? = null) : InstallResult
}

/** Messages from the system installer, delivered by [InstallResultReceiver]. */
data class InstallEvent(val sessionId: Int, val status: Int, val message: String?, val confirm: Intent?)

object InstallEvents {
    private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<InstallEvent> = _events.asSharedFlow()
    fun post(e: InstallEvent) { _events.tryEmit(e) }
}

/** Receives the result of a session started with the Android installer. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        @Suppress("DEPRECATION")
        val confirm = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) else null
        InstallEvents.post(InstallEvent(session, status, message, confirm))
    }

    companion object { const val ACTION = "com.ca1nm.droidfileexplorer.INSTALL_RESULT" }
}

/** Counts bytes read so progress can be reported while a file streams to the installer. */
private class CountingStream(input: InputStream, private val onBytes: (Long) -> Unit) : FilterInputStream(input) {
    override fun read(): Int = super.read().also { if (it >= 0) onBytes(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onBytes(it.toLong()) }
}

@Singleton
class AppInstaller @Inject constructor(private val shizuku: ShizukuFileAccess) {

    fun shizukuReady(): Boolean = shizuku.hasPermission()

    /** Automatic picks Shizuku when it is running and allowed, otherwise the Android installer. */
    fun resolve(engine: Engine): Engine = when (engine) {
        Engine.AUTO -> if (shizukuReady()) Engine.SHIZUKU else Engine.SYSTEM
        else -> engine
    }

    /**
     * Installs [parts] of [source]. [onProgress] gets a message and a 0..1 fraction (or -1 when unknown).
     * [onConfirm] is called with the system's confirmation screen when the Android installer needs the user.
     */
    suspend fun install(
        context: Context,
        source: InstallSource,
        parts: List<ApkPart>,
        engine: Engine,
        options: InstallOptions,
        onProgress: (String, Float) -> Unit,
        onConfirm: (Intent) -> Unit
    ): InstallResult = withContext(Dispatchers.IO) {
        if (parts.isEmpty()) return@withContext InstallResult.Failure("Select at least one APK to install")
        try {
            val result = when (resolve(engine)) {
                Engine.SHIZUKU -> viaShizuku(source, parts, options, onProgress)
                else -> viaSystem(context, source, parts, options, onProgress, onConfirm)
            }
            if (result is InstallResult.Success && source.obbs.isNotEmpty()) copyObbs(source, onProgress)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            InstallResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    // ---- Shizuku: pm install-create / install-write / install-commit ----

    private suspend fun viaShizuku(source: InstallSource, parts: List<ApkPart>, o: InstallOptions, onProgress: (String, Float) -> Unit): InstallResult {
        val total = parts.sumOf { it.size }.coerceAtLeast(1)
        val flags = buildString {
            if (o.replaceExisting) append(" -r")
            if (o.allowDowngrade) append(" -d")
            if (o.grantAllPermissions) append(" -g")
            if (o.allowOldApps && Build.VERSION.SDK_INT >= 34) append(" --bypass-low-target-sdk-block")
        }
        onProgress("Preparing the install…", -1f)
        val (_, created) = shizuku.exec("pm install-create$flags -S $total")
        val id = Regex("\\[(\\d+)]").find(created)?.groupValues?.get(1)
            ?: return InstallResult.Failure("Couldn't start the install: ${created.trim()}", failureCode(created))
        try {
            var done = 0L
            parts.forEachIndexed { i, part ->
                currentCoroutineContext().ensureActive()
                onProgress("Writing ${part.label} (${i + 1} of ${parts.size})", done.toFloat() / total)
                val name = if (part.kind == PartKind.BASE && i == 0) "base.apk" else "split$i.apk"
                var lastReport = done
                val (code, out) = ApkBundle.openPart(source, part).use { raw ->
                    shizuku.execWithInput("pm install-write -S ${part.size} $id $name -", CountingStream(raw) {
                        done += it
                        if (done - lastReport >= 512 * 1024) {
                            lastReport = done
                            onProgress("Writing ${part.label} (${i + 1} of ${parts.size})", done.toFloat() / total)
                        }
                    })
                }
                if (code != 0 || !out.contains("Success")) {
                    shizuku.exec("pm install-abandon $id")
                    return InstallResult.Failure("Writing ${part.label} failed: ${out.trim()}", failureCode(out))
                }
            }
            onProgress("Installing…", -1f)
            val (_, out) = shizuku.exec("pm install-commit $id")
            return if (out.contains("Success")) InstallResult.Success("Installed with Shizuku")
            else InstallResult.Failure(friendly(out), failureCode(out))
        } catch (e: Exception) {
            shizuku.exec("pm install-abandon $id")
            throw e
        }
    }

    // ---- Android installer: PackageInstaller session, system asks the user to confirm ----

    private suspend fun viaSystem(
        context: Context, source: InstallSource, parts: List<ApkPart>, o: InstallOptions,
        onProgress: (String, Float) -> Unit, onConfirm: (Intent) -> Unit
    ): InstallResult {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return InstallResult.Failure("Allow this app to install apps in Android's settings first (Install unknown apps), then try again.", "NEEDS_PERMISSION")
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(source.packageName)
            setSize(parts.sumOf { it.size })
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            val total = parts.sumOf { it.size }.coerceAtLeast(1)
            var done = 0L
            parts.forEachIndexed { i, part ->
                currentCoroutineContext().ensureActive()
                onProgress("Writing ${part.label} (${i + 1} of ${parts.size})", done.toFloat() / total)
                val name = if (part.kind == PartKind.BASE && i == 0) "base.apk" else "split$i.apk"
                ApkBundle.openPart(source, part).use { input ->
                    session.openWrite(name, 0, part.size).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                        }
                        session.fsync(out)
                    }
                }
            }
            onProgress("Waiting for Android…", -1f)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context, sessionId,
                Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION).setPackage(context.packageName),
                flags
            )
            // Start listening before committing so the first message can't be missed.
            val result = coroutineScope {
                val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                    InstallEvents.events.first { ev ->
                        if (ev.sessionId != sessionId) false
                        else if (ev.status == PackageInstaller.STATUS_PENDING_USER_ACTION) { ev.confirm?.let(onConfirm); false }
                        else true
                    }
                }
                session.commit(callback.intentSender)
                waiter.await()
            }
            return if (result.status == PackageInstaller.STATUS_SUCCESS) InstallResult.Success("Installed")
            else InstallResult.Failure(friendly(result.message.orEmpty()), failureCode(result.message.orEmpty()))
        } catch (e: Exception) {
            runCatching { session.abandon() }
            throw e
        } finally {
            runCatching { session.close() }
        }
    }

    // ---- Expansion (OBB) files ----

    private suspend fun copyObbs(source: InstallSource, onProgress: (String, Float) -> Unit) {
        source.obbs.forEachIndexed { i, obb ->
            currentCoroutineContext().ensureActive()
            onProgress("Copying game data (${i + 1} of ${source.obbs.size})", i.toFloat() / source.obbs.size)
            val target = File(obb.targetPath)
            val viaFile = runCatching {
                target.parentFile?.mkdirs()
                ApkBundle.openObb(source, obb).use { input -> target.outputStream().use { input.copyTo(it) } }
            }.isSuccess
            if (!viaFile && shizuku.hasPermission()) {
                shizuku.exec("mkdir -p '${target.parent}'")
                ApkBundle.openObb(source, obb).use { shizuku.execWithInput("cat > '${target.path}'", it) }
            }
        }
    }

    // ---- Error messages ----

    private fun failureCode(text: String): String? =
        Regex("INSTALL_[A-Z_]+|STATUS_[A-Z_]+").find(text)?.value

    /** Turns the installer's terse codes into something a person can act on. */
    private fun friendly(text: String): String {
        val t = text.trim()
        val hint = when {
            "INSTALL_FAILED_VERSION_DOWNGRADE" in t -> "A newer version is already installed. Turn on \"Allow downgrade\" to replace it."
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in t || "signatures do not match" in t ->
                "The installed copy was signed with a different key. Uninstall it first (this deletes its data), then install."
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" in t -> "Not enough free storage."
            "INSTALL_FAILED_OLDER_SDK" in t -> "This app needs a newer version of Android."
            "INSTALL_FAILED_DEPRECATED_SDK_VERSION" in t || "target SDK" in t ->
                "Android blocks apps built for very old Android versions. Turn on \"Allow old apps\" and try again."
            "INSTALL_FAILED_MISSING_SPLIT" in t -> "A required split is missing. Tick the base APK and the matching CPU, screen and language splits."
            "INSTALL_FAILED_NO_MATCHING_ABIS" in t -> "This app has no build for this phone's CPU."
            "INSTALL_PARSE_FAILED" in t -> "Android couldn't read this APK; it may be damaged or incomplete."
            "STATUS_FAILURE_ABORTED" in t || "User rejected" in t || "aborted" in t.lowercase() -> "The install was cancelled."
            else -> null
        }
        return if (hint != null) "$hint\n\n$t" else t.ifBlank { "The install failed" }
    }
}
