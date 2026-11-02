package com.sza.androidfolderexplorer.data.install

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import org.json.JSONObject

enum class PartKind { BASE, ABI, DENSITY, LANGUAGE, OTHER }

/** One APK to install: a base APK, or a split (CPU, screen density, language or feature module). */
data class ApkPart(
    /** Zip entry name for bundles, or the file name for a plain .apk. */
    val entry: String,
    val size: Long,
    val kind: PartKind,
    /** Short description shown to the user, e.g. "arm64-v8a" or "Base". */
    val label: String,
    /** Whether this part suits the phone, so it is ticked by default. */
    val recommended: Boolean
)

/** A file to copy next to the install (expansion data), e.g. Android/obb/com.game/main.1.com.game.obb. */
data class ObbPart(val entry: String, val targetPath: String, val size: Long)

data class InstallSource(
    val file: File,
    val isBundle: Boolean,
    val title: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val permissions: List<String>,
    val icon: Bitmap?,
    val parts: List<ApkPart>,
    val obbs: List<ObbPart>,
    val installedVersionName: String?,
    val installedVersionCode: Long?
)

/**
 * Reads .apk files and the split-APK bundle formats used by app stores and bundletool:
 * .apks (bundletool), .xapk (APKPure) and .apkm (APKMirror) are all zip files holding several APKs.
 */
object ApkBundle {
    val BUNDLE_EXTENSIONS = setOf("apks", "xapk", "apkm")
    fun isBundle(name: String) = name.substringAfterLast('.', "").lowercase() in BUNDLE_EXTENSIONS

    private val ABIS = setOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86", "x86_64", "mips", "mips64", "riscv64")
    private val DENSITIES = mapOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640)
    private val LANG = Regex("^[a-z]{2}([_-][a-z0-9]{2,4})?$")

    /** Opens [part] of [source] for reading. The caller closes the stream. */
    fun openPart(source: InstallSource, part: ApkPart): InputStream =
        if (!source.isBundle) source.file.inputStream()
        else {
            val zip = ZipFile(source.file)
            val entry = zip.getEntry(part.entry) ?: run { zip.close(); throw IOException("${part.entry} is missing from the bundle") }
            object : java.io.FilterInputStream(zip.getInputStream(entry)) {
                override fun close() { super.close(); zip.close() }
            }
        }

    fun openObb(source: InstallSource, obb: ObbPart): InputStream {
        val zip = ZipFile(source.file)
        val entry = zip.getEntry(obb.entry) ?: run { zip.close(); throw IOException("${obb.entry} is missing from the bundle") }
        return object : java.io.FilterInputStream(zip.getInputStream(entry)) {
            override fun close() { super.close(); zip.close() }
        }
    }

    /** Reads package details and works out which parts suit this phone. */
    fun read(context: Context, file: File, cacheDir: File): InstallSource {
        val pm = context.packageManager
        return if (!isBundle(file.name)) {
            val info = packageInfo(pm, file) ?: throw IOException("This file isn't a valid APK")
            build(context, file, false, info, listOf(ApkPart(file.name, file.length(), PartKind.BASE, "Base", true)), emptyList(), null)
        } else {
            ZipFile(file).use { zip ->
                val entries = zip.entries().toList().filter { !it.isDirectory }
                val apkEntries = entries.filter { it.name.endsWith(".apk", ignoreCase = true) && !it.name.startsWith("standalones/") }
                    .ifEmpty { entries.filter { it.name.endsWith(".apk", ignoreCase = true) } }
                if (apkEntries.isEmpty()) throw IOException("No APK files found inside this bundle")

                val classified = apkEntries.map { e -> classify(e.name).let { (kind, label) -> Triple(e, kind, label) } }
                // The base APK carries the manifest; with no recognisable base, use the largest APK.
                val baseEntry = classified.firstOrNull { it.second == PartKind.BASE }?.first ?: apkEntries.maxByOrNull { it.size }!!
                val baseFile = File(cacheDir, "base-${file.name.hashCode()}.apk").also { out ->
                    out.parentFile?.mkdirs()
                    zip.getInputStream(baseEntry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
                val info = packageInfo(pm, baseFile) ?: throw IOException("Couldn't read the base APK in this bundle")

                val parts = chooseParts(context, classified.map { (e, kind, label) ->
                    ApkPart(e.name, e.size.coerceAtLeast(0), if (e == baseEntry) PartKind.BASE else kind, if (e == baseEntry) "Base" else label, false)
                })

                // Expansion files: anything under Android/obb/ (or listed in an XAPK manifest).
                val obbs = entries.filter { it.name.startsWith("Android/obb/") || it.name.startsWith("Android/data/") }
                    .map { ObbPart(it.name, "/storage/emulated/0/${it.name}", it.size) }
                val manifest = zip.getEntry("manifest.json")?.let { runCatching { JSONObject(zip.getInputStream(it).bufferedReader().readText()) }.getOrNull() }
                val extra = manifest?.optJSONArray("expansions")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val name = o.optString("file"); val path = o.optString("install_path")
                        val e = zip.getEntry(name) ?: return@mapNotNull null
                        if (path.isBlank() || obbs.any { it.entry == name }) null else ObbPart(name, "/storage/emulated/0/$path", e.size)
                    }
                }.orEmpty()
                build(context, file, true, info, parts, obbs + extra, manifest)
            }
        }
    }

    private fun packageInfo(pm: PackageManager, apk: File) =
        pm.getPackageArchiveInfo(apk.path, PackageManager.GET_PERMISSIONS)?.also {
            it.applicationInfo?.apply { sourceDir = apk.path; publicSourceDir = apk.path }
        }

    private fun build(
        context: Context, file: File, bundle: Boolean, info: android.content.pm.PackageInfo,
        parts: List<ApkPart>, obbs: List<ObbPart>, manifest: JSONObject?
    ): InstallSource {
        val pm = context.packageManager
        val installed = runCatching { pm.getPackageInfo(info.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        return InstallSource(
            file = file, isBundle = bundle,
            title = info.applicationInfo?.loadLabel(pm)?.toString() ?: manifest?.optString("name").orEmpty().ifBlank { info.packageName },
            packageName = info.packageName,
            versionName = info.versionName ?: manifest?.optString("version_name").orEmpty(),
            versionCode = info.longVersionCode,
            minSdk = info.applicationInfo?.minSdkVersion ?: 0,
            targetSdk = info.applicationInfo?.targetSdkVersion ?: 0,
            permissions = info.requestedPermissions?.toList().orEmpty(),
            icon = runCatching { info.applicationInfo?.loadIcon(pm)?.toBitmap(128, 128) }.getOrNull(),
            parts = parts, obbs = obbs,
            installedVersionName = installed?.versionName,
            installedVersionCode = installed?.longVersionCode
        )
    }

    /** Sorts a split's file name into base / CPU / density / language / other. */
    internal fun classify(fileName: String): Pair<PartKind, String> {
        var n = fileName.substringAfterLast('/').lowercase().removeSuffix(".apk")
        for (p in listOf("split_config.", "split_config_", "config.", "base-", "split_")) n = n.removePrefix(p)
        return when {
            n == "base" || n == "master" || n.isEmpty() -> PartKind.BASE to "Base"
            n in ABIS -> PartKind.ABI to n.replace('_', '-')
            n in DENSITIES -> PartKind.DENSITY to n
            LANG.matches(n) -> PartKind.LANGUAGE to "Language: $n"
            else -> PartKind.OTHER to n.replace('_', ' ')
        }
    }

    /**
     * Marks the parts that suit this phone: all base and feature parts, the best matching CPU split,
     * the closest screen-density split, and the languages the phone is set to (plus English).
     */
    private fun chooseParts(context: Context, parts: List<ApkPart>): List<ApkPart> {
        val abiParts = parts.filter { it.kind == PartKind.ABI }
        val bestAbi = Build.SUPPORTED_ABIS.map { it.replace('-', '_').lowercase() }
            .firstOrNull { abi -> abiParts.any { it.label.replace('-', '_') == abi } }
        val dpi = context.resources.displayMetrics.densityDpi
        val densityParts = parts.filter { it.kind == PartKind.DENSITY }
        val bestDensity = densityParts.minByOrNull { Math.abs((DENSITIES[it.label] ?: 160) - dpi) }
        val locales = context.resources.configuration.locales
        val langs = (0 until locales.size()).map { locales[it].language.lowercase() }.toSet() + "en"
        return parts.map { p ->
            val ok = when (p.kind) {
                PartKind.BASE, PartKind.OTHER -> true
                PartKind.ABI -> p.label.replace('-', '_') == bestAbi
                PartKind.DENSITY -> p == bestDensity
                PartKind.LANGUAGE -> langs.contains(p.label.removePrefix("Language: ").substringBefore('_').substringBefore('-'))
            }
            p.copy(recommended = ok)
        }
    }
}
