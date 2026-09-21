package me.unknkriod.kapclient.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.unknkriod.kapclient.R
import me.unknkriod.kapclient.data.ProxyApp
import me.unknkriod.kapclient.ui.viewmodel.VpnViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyAppsScreen(
    viewModel: VpnViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val dbProxyApps by viewModel.proxyApps.collectAsStateWithLifecycle()
    val proxyMode by viewModel.proxyMode.collectAsStateWithLifecycle()
    val showSystemApps by viewModel.showSystemApps.collectAsStateWithLifecycle()
    
    var installedApps by remember { mutableStateOf<List<AppListItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(showSystemApps) {
        isLoading = true
        // Move heavy app listing to a background thread
        installedApps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { 
                    val isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM != 0) || 
                                 (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                    showSystemApps || !isSystem || it.packageName == context.packageName
                }
                .map { appInfo ->
                    AppListItem(
                        packageName = appInfo.packageName,
                        name = appInfo.loadLabel(pm).toString(),
                        icon = appInfo.loadIcon(pm)
                    )
                }.sortedBy { it.name }
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_proxying)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Header with controls
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Mode Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.proxy_mode), style = MaterialTheme.typography.labelLarge)
                        Row {
                            FilterChip(
                                selected = proxyMode == "allowed",
                                onClick = { viewModel.setProxyMode("allowed") },
                                label = { Text(stringResource(R.string.only_selected)) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            FilterChip(
                                selected = proxyMode == "disallowed",
                                onClick = { viewModel.setProxyMode("disallowed") },
                                label = { Text(stringResource(R.string.all_except)) }
                            )
                        }
                    }

                    // System Apps Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.show_system_apps), style = MaterialTheme.typography.bodyMedium)
                        Checkbox(
                            checked = showSystemApps,
                            onCheckedChange = { viewModel.setShowSystemApps(it) }
                        )
                    }
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val sortedApps = remember(installedApps, dbProxyApps) {
                    installedApps.sortedWith(
                        compareByDescending<AppListItem> { app ->
                            dbProxyApps.find { it.packageName == app.packageName }?.isProxied ?: false
                        }.thenBy { it.name }
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sortedApps) { app ->
                        val isProxied = dbProxyApps.find { it.packageName == app.packageName }?.isProxied ?: false
                        ProxyAppItem(
                            app = app,
                            isProxied = isProxied,
                            onToggle = { proxied ->
                                viewModel.updateProxyApp(ProxyApp(app.packageName, app.name, proxied))
                            }
                        )
                    }
                }
            }
        }
    }
}

data class AppListItem(
    val packageName: String,
    val name: String,
    val icon: Drawable
)

@Composable
fun ProxyAppItem(
    app: AppListItem,
    isProxied: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                bitmap = app.icon.toBitmap().asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = app.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge
            )
            Switch(
                checked = isProxied,
                onCheckedChange = onToggle
            )
        }
    }
}
