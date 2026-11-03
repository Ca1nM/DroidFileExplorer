package com.ca1nm.droidfileexplorer

import android.app.Application
import com.ca1nm.droidfileexplorer.data.diag.Diagnostics
import com.ca1nm.droidfileexplorer.data.settings.AppSettings
import com.ca1nm.droidfileexplorer.data.source.TrashManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class DroidFileExplorerApp : Application() {

    @Inject lateinit var trash: TrashManager
    @Inject lateinit var settings: AppSettings

    override fun onCreate() {
        super.onCreate()
        Diagnostics.init(this)
        com.ca1nm.droidfileexplorer.data.ops.RulesScheduler.schedule(this)
        // Remove trash older than the configured number of days, off the main thread.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val s = settings.state.value
            if (s.useTrash) trash.purgeExpired(s.trashDays)
        }
    }
}
