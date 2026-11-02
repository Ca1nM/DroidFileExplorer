package com.sza.androidfolderexplorer.data.storage

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import com.sza.androidfolderexplorer.data.source.ShizukuFileAccess

/** How much space one installed app uses, as Android's own storage statistics report it. */
data class AppUsage(
    val label: String,
    val pkg: String,
    /** The app's installed code (APK, splits, OBB). Zero for built-in apps, whose code is on the system partition. */
    val codeBytes: Long,
    /** The app's data, including its Android/data folder and its cache. */
    val dataBytes: Long,
    /** The part of [dataBytes] that is cache and can be cleared. */
    val cacheBytes: Long
) {
    val totalBytes: Long get() = codeBytes + dataBytes
}

/**
 * Per-app sizes come from StorageStatsManager, the same source as Android's Settings > Storage.
 * It needs "usage access", which can be granted in Settings or silently through Shizuku.
 */
object StorageBreakdown {

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Grants usage access with a shell command. Returns true if it is now allowed. */
    fun grantWithShizuku(context: Context, shizuku: ShizukuFileAccess): Boolean {
        if (!shizuku.hasPermission()) return false
        shizuku.exec("appops set ${context.packageName} GET_USAGE_STATS allow")
        return hasUsageAccess(context)
    }

    /** Sizes of every installed app, largest first. Empty if usage access isn't granted. */
    fun queryApps(context: Context): List<AppUsage> {
        if (!hasUsageAccess(context)) return emptyList()
        val stats = context.getSystemService(StorageStatsManager::class.java) ?: return emptyList()
        val pm = context.packageManager
        val user = Process.myUserHandle()
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(0).mapNotNull { ai ->
            val s = runCatching { stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, user) }.getOrNull()
                ?: return@mapNotNull null
            // Built-in apps keep their code on the read-only system partition, which isn't part of the
            // data partition's "used" figure; an updated built-in app's new code is on the data partition.
            val builtIn = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            AppUsage(
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName),
                pkg = ai.packageName,
                codeBytes = if (builtIn) 0L else s.appBytes,
                dataBytes = s.dataBytes,
                cacheBytes = s.cacheBytes
            )
        }.filter { it.totalBytes > 0 }.sortedByDescending { it.totalBytes }
    }
}
