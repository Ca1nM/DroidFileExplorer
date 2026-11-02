package com.sza.androidfolderexplorer

import android.content.Intent
import android.os.Bundle
import com.sza.androidfolderexplorer.data.ops.OpenRequests
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
        intent?.getStringExtra(EXTRA_OPEN_PATH)?.let { OpenRequests.request(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
