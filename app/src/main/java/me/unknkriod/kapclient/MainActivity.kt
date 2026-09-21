package me.unknkriod.kapclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.unknkriod.kapclient.ui.screens.DashboardScreen
import me.unknkriod.kapclient.ui.screens.EditConfigScreen
import me.unknkriod.kapclient.ui.screens.LogsScreen
import me.unknkriod.kapclient.ui.screens.ProxyAppsScreen
import me.unknkriod.kapclient.ui.screens.RoutingScreen
import me.unknkriod.kapclient.ui.screens.SettingsScreen
import me.unknkriod.kapclient.ui.theme.KAPClientTheme
import me.unknkriod.kapclient.ui.viewmodel.VpnViewModel
import me.unknkriod.kapclient.util.RuleSetManager

class MainActivity : ComponentActivity() {
    private val viewModel: VpnViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        RuleSetManager.init(this)

        setContent {
            KAPClientTheme {
                MainContent(viewModel)
            }
        }
    }
}

@Serializable
sealed interface NavKey {
    @Serializable
    data object Dashboard : NavKey
    
    @Serializable
    data class EditConfig(val id: Long? = null) : NavKey

    @Serializable
    data object ProxyApps : NavKey

    @Serializable
    data object Routing : NavKey

    @Serializable
    data object Settings : NavKey

    @Serializable
    data object Logs : NavKey
}

@Composable
fun MainContent(viewModel: VpnViewModel) {
    val backstack: SnapshotStateList<NavKey> = rememberSaveable(
        saver = listSaver<SnapshotStateList<NavKey>, String>(
            save = { it.map { key -> Json.encodeToString(key) } },
            restore = { 
                val list = mutableStateListOf<NavKey>()
                list.addAll(it.map { json -> Json.decodeFromString<NavKey>(json) })
                list
            }
        )
    ) { mutableStateListOf<NavKey>(NavKey.Dashboard) }

    val popBackstack = {
        if (backstack.size > 1) {
            backstack.removeAt(backstack.lastIndex)
        }
    }

    NavDisplay(
        backStack = backstack.toList(),
        onBack = popBackstack,
        entryDecorators = listOf(rememberViewModelStoreNavEntryDecorator()),
        entryProvider = { key ->
            when (key) {
                is NavKey.Dashboard -> NavEntry(
                    key = key,
                    content = {
                        DashboardScreen(
                            viewModel = viewModel,
                            onNavigateToAddConfig = { backstack.add(NavKey.EditConfig(null)) },
                            onNavigateToEditConfig = { id -> backstack.add(NavKey.EditConfig(id)) },
                            onNavigateToProxyApps = { backstack.add(NavKey.ProxyApps) },
                            onNavigateToRouting = { backstack.add(NavKey.Routing) },
                            onNavigateToSettings = { backstack.add(NavKey.Settings) }
                        )
                    }
                )
                is NavKey.EditConfig -> NavEntry(
                    key = key,
                    content = {
                        EditConfigScreen(
                            viewModel = viewModel,
                            configId = key.id,
                            onNavigateBack = popBackstack
                        )
                    }
                )
                is NavKey.ProxyApps -> NavEntry(
                    key = key,
                    content = {
                        ProxyAppsScreen(
                            viewModel = viewModel,
                            onNavigateBack = popBackstack
                        )
                    }
                )
                is NavKey.Routing -> NavEntry(
                    key = key,
                    content = {
                        RoutingScreen(
                            viewModel = viewModel,
                            onNavigateBack = popBackstack
                        )
                    }
                )
                is NavKey.Settings -> NavEntry(
                    key = key,
                    content = {
                        SettingsScreen(
                            viewModel = viewModel,
                            onNavigateToLogs = { backstack.add(NavKey.Logs) },
                            onNavigateBack = popBackstack
                        )
                    }
                )
                is NavKey.Logs -> NavEntry(
                    key = key,
                    content = {
                        LogsScreen(
                            onNavigateBack = popBackstack
                        )
                    }
                )
            }
        }
    )
}
