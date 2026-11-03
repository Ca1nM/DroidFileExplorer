package com.ca1nm.droidfileexplorer.data.source

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.ca1nm.droidfileexplorer.domain.model.FileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

private const val STAG = "DFE_SHIZUKU"

/**
 * File access via Shizuku privileged shell.
 *
 * Shizuku runs as the `shell` user (uid 2000) via ADB or root — it has access to
 * Android/data and Android/obb bypassing the FUSE overlay that blocks
 * MANAGE_EXTERNAL_STORAGE and SAF on Android 14+.
 *
 * User flow:
 *   1. Install Shizuku from Play Store, F-Droid, or shizuku.rikka.app
 *   2. Start Shizuku service (one-time ADB or Wireless ADB in Developer Options)
 *   3. Grant permission when prompted by this app
 */
@Singleton
class ShizukuFileAccess @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Shizuku APK is installed on the device (service may or may not be running). */
    fun isApkInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Shizuku service binder is alive (APK installed AND service started). */
    fun isServiceRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) { false }

    fun hasPermission(): Boolean = try {
        isServiceRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun requestPermission() {
        Shizuku.requestPermission(REQUEST_CODE)
    }

    fun addPermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.addRequestPermissionResultListener(listener)
    }

    fun removePermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    fun listDirectory(path: String): List<FileItem> {
        // `-exec ... {} +` hands every entry to one stat process. The old `{} \;` started a separate
        // process per entry, which cost about 0.9 s for a folder with 100 entries.
        val started = System.nanoTime()
        val output = runShell(
            "find ${q(path)} -maxdepth 1 -mindepth 1 -exec stat -c '%F|%s|%Y|%n' {} + 2>/dev/null"
        )
        val items = output.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { parseStat(it) }
            .sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
        Log.d(STAG, "listDirectory ${items.size} entries in ${(System.nanoTime() - started) / 1_000_000} ms: $path")
        return items
    }

    fun calculateTotalSize(paths: List<String>): Long =
        paths.sumOf { path ->
            runShell("du -sb ${q(path)} 2>/dev/null | cut -f1")
                .trim().toLongOrNull() ?: 0L
        }

    fun countFiles(path: String): Int =
        runShell("find ${q(path)} 2>/dev/null | wc -l")
            .trim().toIntOrNull()?.coerceAtLeast(1) ?: 1

    // Copies source into destDir (same semantics as `cp -r src destDir/`)
    fun copyInto(sourcePath: String, destDirPath: String) {
        val exit = runShellWithExit("cp -r ${q(sourcePath)} ${q(destDirPath)}")
        if (exit != 0) throw Exception("Shizuku cp failed for $sourcePath (exit $exit)")
    }

    fun moveInto(sourcePath: String, destDirPath: String) {
        val exit = runShellWithExit("mv ${q(sourcePath)} ${q(destDirPath)}")
        if (exit != 0) throw Exception("Shizuku mv failed for $sourcePath (exit $exit)")
    }

    fun deleteRecursively(path: String): Boolean =
        runShellWithExit("rm -rf ${q(path)}") == 0

    fun rename(from: String, to: String): Boolean = runShellWithExit("mv ${q(from)} ${q(to)}") == 0
    fun mkdirs(path: String): Boolean = runShellWithExit("mkdir -p ${q(path)}") == 0
    fun createFile(path: String): Boolean = runShellWithExit("touch ${q(path)}") == 0
    fun exists(path: String): Boolean = runShellWithExit("test -e ${q(path)}") == 0
    fun isDirectory(path: String): Boolean = runShellWithExit("test -d ${q(path)}") == 0

    /** Sets the modification time; [millis] since epoch. */
    fun setModified(path: String, millis: Long): Boolean {
        val stamp = java.text.SimpleDateFormat("yyyyMMddHHmm.ss", java.util.Locale.US).format(java.util.Date(millis))
        return runShellWithExit("touch -m -t $stamp ${q(path)}") == 0
    }

    fun symlink(target: String, link: String): Boolean = runShellWithExit("ln -s ${q(target)} ${q(link)}") == 0

    /** Streams a file's bytes through `cat`. The caller closes the stream. */
    @Suppress("DEPRECATION")
    fun openInputStream(path: String): java.io.InputStream =
        Shizuku.newProcess(arrayOf("sh", "-c", "cat ${q(path)}"), null, null).inputStream

    /** Writes [data] into [path] through `cat >`. */
    @Suppress("DEPRECATION")
    fun write(path: String, data: java.io.InputStream): Boolean = try {
        val p = Shizuku.newProcess(arrayOf("sh", "-c", "cat > ${q(path)}"), null, null)
        p.outputStream.use { out -> data.copyTo(out) }
        p.waitFor() == 0
    } catch (e: Exception) {
        Log.e(STAG, "write failed", e); false
    }

    /** Runs an arbitrary command in [cwd] and returns exit code and combined output. */
    @Suppress("DEPRECATION")
    fun exec(command: String, cwd: String? = null): Pair<Int, String> = try {
        val full = if (cwd != null) "cd ${q(cwd)} && $command" else command
        val process = Shizuku.newProcess(arrayOf("sh", "-c", "$full 2>&1"), null, null)
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor() to out
    } catch (e: Exception) {
        -1 to (e.message ?: "Shizuku error")
    }

    /**
     * Runs [command] and feeds [input] to its stdin, so large files can be streamed to the shell
     * (for example `pm install-write ... -`). Returns the exit code and combined output.
     */
    @Suppress("DEPRECATION")
    fun execWithInput(command: String, input: java.io.InputStream): Pair<Int, String> = try {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", "$command 2>&1"), null, null)
        // Read output on a separate thread so a full pipe can't stall the write below.
        var output = ""
        val reader = Thread { output = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("") }
        reader.start()
        process.outputStream.use { out -> input.copyTo(out, 64 * 1024) }
        reader.join()
        process.waitFor() to output
    } catch (e: Exception) {
        Log.e(STAG, "execWithInput failed", e)
        -1 to (e.message ?: "Shizuku error")
    }

    private fun parseStat(line: String): FileItem? {
        // Format: type|size|modified_unix_seconds|fullpath. The path is last so a '|' in a name can't split it.
        val parts = line.split("|", limit = 4)
        if (parts.size < 4) return null
        val type = parts[0].trim()
        val size = parts[1].trim().toLongOrNull() ?: 0L
        val modifiedSec = parts[2].trim().toLongOrNull() ?: 0L
        val fullPath = parts[3]
        val name = fullPath.substringAfterLast('/')
        if (name.isEmpty()) return null
        val isDir = type == "directory"
        return FileItem(
            name = name,
            path = fullPath,
            isDirectory = isDir,
            size = if (!isDir) size else 0L,
            lastModified = modifiedSec * 1000L,
            childCount = null
        )
    }

    @Suppress("DEPRECATION")
    private fun runShell(command: String): String = try {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output
    } catch (_: Exception) { "" }

    @Suppress("DEPRECATION")
    private fun runShellWithExit(command: String): Int = try {
        Log.d(STAG, "runShellWithExit: $command")
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        val stderr = process.errorStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (stderr.isNotBlank()) Log.w(STAG, "stderr: $stderr")
        Log.d(STAG, "exit=$exit")
        exit
    } catch (e: Exception) {
        Log.e(STAG, "runShellWithExit exception", e)
        -1
    }

    // Single-quote a shell argument, escaping internal single quotes
    private fun q(path: String): String = "'${path.replace("'", "'\\''")}'"

    companion object {
        const val REQUEST_CODE = 1001
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
