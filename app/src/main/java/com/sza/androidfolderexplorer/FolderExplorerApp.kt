package com.sza.androidfolderexplorer

import android.app.Application
import com.sza.androidfolderexplorer.data.diag.Diagnostics
import com.sza.androidfolderexplorer.data.settings.AppSettings
import com.sza.androidfolderexplorer.data.source.TrashManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class FolderExplorerApp : Application() {

    @Inject lateinit var trash: TrashManager
    @Inject lateinit var settings: AppSettings

    override fun onCreate() {
        super.onCreate()
        Diagnostics.init(this)
        com.sza.androidfolderexplorer.data.ops.RulesScheduler.schedule(this)
        // Remove trash older than the configured number of days, off the main thread.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val s = settings.state.value
            if (s.useTrash) trash.purgeExpired(s.trashDays)
        }
    }
}
