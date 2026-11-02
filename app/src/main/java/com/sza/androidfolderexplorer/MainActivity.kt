package com.sza.androidfolderexplorer

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.sza.androidfolderexplorer.data.ops.IncomingFiles
import com.sza.androidfolderexplorer.data.ops.OpenRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.sza.androidfolderexplorer.data.settings.AppSettings
import com.sza.androidfolderexplorer.data.settings.ThemeMode
import com.sza.androidfolderexplorer.navigation.AppNavigation
import com.sza.androidfolderexplorer.ui.theme.FolderExplorerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var appSettings: AppSettings

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenRequest(intent)
    }

    private fun handleOpenRequest(intent: Intent?) {
        intent?.getStringExtra(EXTRA_OPEN_PATH)?.let { OpenRequests.request(it); return }
        // "Open with" from another app (a browser's download, DebForge ...): a file or folder Uri.
        if (intent?.action == Intent.ACTION_VIEW) {
            // Sent by Settings > Default app, only to make Android show its chooser: just open the app.
            if (intent.getBooleanExtra(com.sza.androidfolderexplorer.presentation.settings.EXTRA_DEFAULT_SETUP, false)) return
            val uri = intent.data ?: return
            val appContext = applicationContext
            lifecycleScope.launch {
                val found = withContext(Dispatchers.IO) { IncomingFiles.resolve(appContext, uri) }
                when {
                    found == null -> Toast.makeText(appContext, "Couldn't open that file", Toast.LENGTH_LONG).show()
                    found.isDirectory -> OpenRequests.request(found.path)
                    else -> OpenRequests.requestFile(found.path, found.temporary)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Settings > Default app sends a sample file only so Android shows its "Open with" window. If the person picks
        // this app there, close this extra copy at once so they land back on Settings instead of a reloaded browser.
        if (intent?.getBooleanExtra(com.sza.androidfolderexplorer.presentation.settings.EXTRA_DEFAULT_SETUP, false) == true) {
            finish()
            return
        }
        handleOpenRequest(intent)
        enableEdgeToEdge()
        setContent {
            val settings by appSettings.state.collectAsState()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            FolderExplorerTheme(
                darkTheme = darkTheme,
                accentId = settings.accent,
                blackDark = settings.blackDark
            ) {
                AppNavigation()
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_PATH = "open_path"
    }
}
