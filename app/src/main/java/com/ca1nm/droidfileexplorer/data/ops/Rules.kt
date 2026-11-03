package com.ca1nm.droidfileexplorer.data.ops

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ca1nm.droidfileexplorer.data.FileTypes
import com.ca1nm.droidfileexplorer.data.diag.Diagnostics
import com.ca1nm.droidfileexplorer.data.settings.AppSettings
import com.ca1nm.droidfileexplorer.data.settings.AutoSortRule
import com.ca1nm.droidfileexplorer.data.settings.SyncRule
import com.ca1nm.droidfileexplorer.data.settings.UserData
import com.ca1nm.droidfileexplorer.data.source.FileOps
import com.ca1nm.droidfileexplorer.data.source.TrashManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RulesEntryPoint {
    fun runner(): RulesRunner
}

/** Runs sync and auto-sort rules. Shared by the hourly worker and the "Run now" buttons. */
@Singleton
class RulesRunner @Inject constructor(
    private val userData: UserData,
    private val trash: TrashManager
) {
    private val protectedSuffixes = listOf(".crdownload", ".part", ".tmp", ".download", ".partial")

    /** Rules whose interval has elapsed run now; everything else waits. */
    suspend fun runDue() {
        val now = System.currentTimeMillis()
        for (r in userData.state.value.syncRules.filter { it.enabled && now - it.lastRun >= it.intervalHours * 3_600_000L }) runSync(r)
        for (r in userData.state.value.autoSortRules.filter { it.enabled }) runAutoSort(r)
    }

    private fun saveSync(id: String, result: String) = userData.update { s ->
        s.copy(syncRules = s.syncRules.map { if (it.id == id) it.copy(lastRun = System.currentTimeMillis(), lastResult = result) else it })
    }

    private fun saveSort(id: String, result: String) = userData.update { s ->
        s.copy(autoSortRules = s.autoSortRules.map { if (it.id == id) it.copy(lastResult = result) else it })
    }

    /** One-way copy of new and changed files from source to destination. Returns a summary. */
    suspend fun runSync(rule: SyncRule): String {
        val result = try {
            val src = File(rule.source).canonicalFile
            val dst = File(rule.destination).canonicalFile
            if (!src.isDirectory) throw IOException("Source folder not found")
            if (dst.path == src.path || dst.path.startsWith(src.path + "/") || src.path.startsWith(dst.path + "/"))
                throw IOException("Source and destination can't be inside each other")
            dst.mkdirs()
            var copied = 0; var skipped = 0
            val seen = HashSet<String>()
            src.walkTopDown().onEnter { !it.name.startsWith(".") || it == src }.forEach { f ->
                currentCoroutineContext().ensureActive()
                if (f == src) return@forEach
                val rel = f.relativeTo(src).path
                seen += rel
                val target = File(dst, rel)
                if (f.isDirectory) { target.mkdirs(); return@forEach }
                if (f.name.startsWith(".") || protectedSuffixes.any { f.name.endsWith(it) }) return@forEach
                if (target.exists() && target.length() == f.length() && f.lastModified() <= target.lastModified() + 2000) { skipped++; return@forEach }
                target.parentFile?.mkdirs()
                f.copyTo(target, overwrite = true)
                target.setLastModified(f.lastModified())
                copied++
            }
            var removed = 0
            if (rule.mirrorDeletes) {
                // Extra files in the destination go to the trash, not straight to oblivion.
                val extra = dst.walkTopDown().filter { it != dst && it.isFile && it.relativeTo(dst).path !in seen }.map { it.absolutePath }.toList()
                if (extra.isNotEmpty()) removed = trash.trash(extra).first.size
            }
            "Copied $copied, unchanged $skipped" + if (rule.mirrorDeletes) ", removed $removed" else ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.log("sync", "${rule.source}: ${e.message}")
            "Failed: ${e.message ?: e.javaClass.simpleName}"
        }
        saveSync(rule.id, result)
        return result
    }

    suspend fun runAutoSort(rule: AutoSortRule): String {
        val result = try {
            val dir = File(rule.folder)
            if (!dir.isDirectory) throw IOException("Folder not found")
            var moved = 0; var trashed = 0
            val now = System.currentTimeMillis()
            if (rule.sortByType) {
                for (f in dir.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }) {
                    currentCoroutineContext().ensureActive()
                    if (protectedSuffixes.any { f.name.endsWith(it) } || now - f.lastModified() < 60_000) continue
                    val category = FileTypes.categoryOf(f.name).takeIf { it != "Other" } ?: continue
                    val target = FileOps.uniqueName(File(dir, category).apply { mkdirs() }, f.name)
                    if (f.renameTo(target)) moved++ else { f.copyTo(target); f.delete(); moved++ }
                }
            }
            if (rule.deleteOlderThanDays > 0) {
                val cutoff = now - rule.deleteOlderThanDays * 86_400_000L
                val old = dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && it.lastModified() < cutoff }.map { it.absolutePath }.toList()
                if (old.isNotEmpty()) trashed = trash.trash(old).first.size
            }
            "Sorted $moved file(s)" + if (rule.deleteOlderThanDays > 0) ", trashed $trashed old file(s)" else ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.log("autosort", "${rule.folder}: ${e.message}")
            "Failed: ${e.message ?: e.javaClass.simpleName}"
        }
        saveSort(rule.id, result)
        return result
    }
}

class RulesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runner = EntryPointAccessors.fromApplication(applicationContext, RulesEntryPoint::class.java).runner()
        return try { runner.runDue(); Result.success() } catch (e: Exception) { Result.retry() }
    }
}

object RulesScheduler {
    private const val JOB_RENAMED = "rules_job_renamed"

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<RulesWorker>(1, TimeUnit.HOURS).build()
        val work = WorkManager.getInstance(context)
        // Earlier versions scheduled this hourly job under a different name. It is the app's only background job, so
        // the first time this version runs, cancel everything once and schedule it again under the current name
        // (otherwise the old job would keep running next to the new one).
        val prefs = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(JOB_RENAMED, false)) {
            work.cancelAllWork()
            prefs.edit().putBoolean(JOB_RENAMED, true).apply()
        }
        work.enqueueUniquePeriodicWork("dfe_rules", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
