package com.sza.androidfolderexplorer.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sza.androidfolderexplorer.presentation.browser.FileBrowserScreen
import com.sza.androidfolderexplorer.presentation.permissions.PermissionScreen
import com.sza.androidfolderexplorer.presentation.settings.SettingsScreen
import com.sza.androidfolderexplorer.presentation.network.NetworkScreen
import com.sza.androidfolderexplorer.presentation.server.ServerScreen
import com.sza.androidfolderexplorer.presentation.sync.SyncScreen
import com.sza.androidfolderexplorer.presentation.vault.VaultScreen
import com.sza.androidfolderexplorer.presentation.tools.ToolsScreen
import com.sza.androidfolderexplorer.presentation.trash.TrashScreen
import kotlinx.serialization.Serializable

@Serializable
object PermissionRoute

@Serializable
object BrowserRoute

@Serializable
object SettingsRoute

@Serializable
object TrashRoute

@Serializable
object ToolsRoute

@Serializable
object NetworkRoute

@Serializable
object ServerRoute

@Serializable
object VaultRoute

@Serializable
object SyncRoute

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = PermissionRoute) {
        composable<PermissionRoute> {
            PermissionScreen(
                onPermissionGranted = {
                    navController.navigate(BrowserRoute) {
                        popUpTo<PermissionRoute> { inclusive = true }
                    }
                }
            )
        }
        composable<BrowserRoute> {
            FileBrowserScreen(
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenTrash = { navController.navigate(TrashRoute) },
                onOpenTools = { navController.navigate(ToolsRoute) },
                onOpenConnection = { id ->
                    // The Network screen picks this up and connects straight away.
                    com.sza.androidfolderexplorer.data.network.NetworkRequests.connect(id)
                    navController.navigate(NetworkRoute)
                }
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<TrashRoute> {
            TrashScreen(onBack = { navController.popBackStack() })
        }
        composable<ToolsRoute> {
            ToolsScreen(
                onBack = { navController.popBackStack() },
                // "Reveal" requests are picked up by the browser once it is back on screen.
                onReveal = { navController.popBackStack(BrowserRoute, inclusive = false) },
                onOpenTrash = { navController.navigate(TrashRoute) },
                onOpenNetwork = { navController.navigate(NetworkRoute) },
                onOpenServer = { navController.navigate(ServerRoute) },
                onOpenVault = { navController.navigate(VaultRoute) },
                onOpenSync = { navController.navigate(SyncRoute) }
            )
        }
        composable<NetworkRoute> { NetworkScreen(onBack = { navController.popBackStack() }) }
        composable<ServerRoute> { ServerScreen(onBack = { navController.popBackStack() }) }
        composable<VaultRoute> { VaultScreen(onBack = { navController.popBackStack() }) }
        composable<SyncRoute> { SyncScreen(onBack = { navController.popBackStack() }) }
    }
}
