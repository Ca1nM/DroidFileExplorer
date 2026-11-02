package com.sza.androidfolderexplorer.data.diag

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Crash and performance log. Crashes and slow folder loads are appended to files in the
 * app's private storage; [exportBugReport] bundles them with logcat and device details.
 */
object Diagnostics {
    private lateinit var dir: File
    private val stamp get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    val crashFile get() = File(dir, "crashes.log")
    val perfFile get() = File(dir, "performance.log")

    fun init(context: Context) {
        dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                append(crashFile, "=== $stamp crash on ${thread.name}\n${e.stackTraceToString()}\n")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun logSlowLoad(path: String, ms: Long, items: Int) {
        if (!::dir.isInitialized) return
        append(perfFile, "$stamp  ${ms}ms  $items items  $path\n")
    }

    fun log(tag: String, message: String) {
        if (!::dir.isInitialized) return
        append(File(dir, "events.log"), "$stamp  [$tag] $message\n")
    }

    fun read(file: File): String = if (file.exists()) file.readText() else ""

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private fun append(file: File, text: String) {
        file.appendText(text)
        if (file.length() > 512 * 1024) {
            val tail = file.readText().takeLast(256 * 1024)
            file.writeText(tail)
        }
    }

    /** Writes a zip with device info, the app's logcat and the diagnostics files. Returns the zip. */
    fun exportBugReport(context: Context, outDir: File): File {
        outDir.mkdirs()
        val name = "AFE-bugreport-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
        val out = File(outDir, name)
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val device = buildString {
            appendLine("App: ${context.packageName} ${pkg.versionName}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Build: ${Build.DISPLAY}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Time: $stamp")
        }
        val logcat = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}"))
            p.inputStream.bufferedReader().readText().takeLast(2_000_000)
        }.getOrElse { "logcat unavailable: ${it.message}" }
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun put(n: String, text: String) {
                zip.putNextEntry(ZipEntry(n)); zip.write(text.toByteArray()); zip.closeEntry()
            }
            put("device.txt", device)
            put("logcat.txt", logcat)
            dir.listFiles()?.forEach { put(it.name, it.readText()) }
        }
        return out
    }
}
