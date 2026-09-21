package me.unknkriod.kapclient.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.unknkriod.kapclient.R
import me.unknkriod.kapclient.util.RuleSetManager
import kotlinx.coroutines.launch
import me.unknkriod.kapclient.ui.viewmodel.VpnViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: VpnViewModel,
    onNavigateToLogs: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE) }
    
    val configs by viewModel.configs.collectAsStateWithLifecycle()
    val preferredDirectIds by viewModel.preferredDirectConfigIds.collectAsStateWithLifecycle()
    val preferredCdnIds by viewModel.preferredCdnConfigIds.collectAsStateWithLifecycle()
    
    var ipv6Enabled by remember { mutableStateOf(prefs.getBoolean("ipv6_enabled", false)) }
    var startOnBoot by remember { mutableStateOf(prefs.getBoolean("start_on_boot", false)) }
    var wifiAutoSwitch by remember { mutableStateOf(prefs.getBoolean("wifi_auto_switch", true)) }
    
    var logLevel by remember { mutableStateOf(prefs.getString("log_level", "warn") ?: "warn") }
    var lastUpdate by remember { mutableStateOf(prefs.getString("last_database_update", "Never") ?: "Never") }
    
    val scope = rememberCoroutineScope()
    val updating by RuleSetManager.isUpdating.collectAsStateWithLifecycle()
    val updateProgress by RuleSetManager.updateProgress.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.ipv6_support)) },
                supportingContent = { Text("Experimental support for IPv6 traffic") },
                trailingContent = {
                    Switch(
                        checked = ipv6Enabled,
                        onCheckedChange = {
                            ipv6Enabled = it
                            prefs.edit().putBoolean("ipv6_enabled", it).apply()
                        }
                    )
                }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.start_on_boot)) },
                supportingContent = { Text("Automatically reconnect when device starts") },
                trailingContent = {
                    Switch(
                        checked = startOnBoot,
                        onCheckedChange = {
                            startOnBoot = it
                            prefs.edit().putBoolean("start_on_boot", it).apply()
                        }
                    )
                }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.wifi_auto_switch)) },
                supportingContent = { Text(stringResource(R.string.wifi_auto_switch_desc)) },
                trailingContent = {
                    Switch(
                        checked = wifiAutoSwitch,
                        onCheckedChange = {
                            wifiAutoSwitch = it
                            prefs.edit().putBoolean("wifi_auto_switch", it).apply()
                        }
                    )
                }
            )
            HorizontalDivider()

            var showDirectSelector by remember { mutableStateOf(false) }
            var showCdnSelector by remember { mutableStateOf(false) }

            val directCount = preferredDirectIds.size
            val cdnCount = preferredCdnIds.size

            ListItem(
                headlineContent = { Text(stringResource(R.string.preferred_direct_config)) },
                supportingContent = { 
                    Text(if (directCount > 0) "Selected: $directCount" else stringResource(R.string.config_not_selected)) 
                },
                trailingContent = {
                    TextButton(onClick = { showDirectSelector = true }) {
                        Text(stringResource(R.string.change))
                    }
                }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.preferred_cdn_config)) },
                supportingContent = { 
                    Text(if (cdnCount > 0) "Selected: $cdnCount" else stringResource(R.string.config_not_selected)) 
                },
                trailingContent = {
                    TextButton(onClick = { showCdnSelector = true }) {
                        Text(stringResource(R.string.change))
                    }
                }
            )

            if (showDirectSelector) {
                MultiConfigSelectorDialog(
                    title = stringResource(R.string.preferred_direct_config),
                    configs = configs,
                    selectedIds = preferredDirectIds,
                    onSave = { 
                        viewModel.setPreferredDirectConfigs(it)
                        showDirectSelector = false
                    },
                    onDismiss = { showDirectSelector = false }
                )
            }

            if (showCdnSelector) {
                MultiConfigSelectorDialog(
                    title = stringResource(R.string.preferred_cdn_config),
                    configs = configs,
                    selectedIds = preferredCdnIds,
                    onSave = { 
                        viewModel.setPreferredCdnConfigs(it)
                        showCdnSelector = false
                    },
                    onDismiss = { showCdnSelector = false }
                )
            }

            HorizontalDivider()
            Text(stringResource(R.string.database_update), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            Surface(
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.update_databases), style = MaterialTheme.typography.bodyLarge)
                        if (updating) {
                            Spacer(Modifier.height(4.dp))
                            Text(updateProgress.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                LinearProgressIndicator(
                                    progress = { updateProgress.progress },
                                    modifier = Modifier.weight(1f).height(4.dp),
                                )
                                if (updateProgress.speed.isNotEmpty()) {
                                    Text(
                                        updateProgress.speed,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(start = 8.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        } else {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.last_update, lastUpdate),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    if (updating) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Button(
                            onClick = { 
                                scope.launch {
                                    val success = RuleSetManager.updateAll(context)
                                    if (success) {
                                        lastUpdate = prefs.getString("last_database_update", "Never") ?: "Never"
                                    }
                                }
                            }
                        ) {
                            Text(stringResource(R.string.update_databases))
                        }
                    }
                }
            }
            HorizontalDivider()
            Text("Diagnostics", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            
            var showLogDialog by remember { mutableStateOf(false) }
            
            ListItem(
                headlineContent = { Text(stringResource(R.string.log_level)) },
                supportingContent = { Text("Current: ${logLevel.uppercase()}") },
                trailingContent = {
                    TextButton(onClick = { showLogDialog = true }) {
                        Text(stringResource(R.string.change))
                    }
                }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.app_logs)) },
                supportingContent = { Text(stringResource(R.string.view_logs)) },
                trailingContent = {
                    TextButton(onClick = onNavigateToLogs) {
                        Text(stringResource(R.string.view))
                    }
                }
            )

            if (showLogDialog) {
                AlertDialog(
                    onDismissRequest = { showLogDialog = false },
                    title = { Text("Select Log Level") },
                    text = {
                        Column {
                            listOf("debug", "info", "warn", "error").forEach { level ->
                                TextButton(
                                    onClick = {
                                        logLevel = level
                                        prefs.edit().putString("log_level", level).apply()
                                        showLogDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(level.uppercase(), color = if (logLevel == level) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    },
                    confirmButton = {}
                )
            }
        }
    }
}

@Composable
fun MultiConfigSelectorDialog(
    title: String,
    configs: List<me.unknkriod.kapclient.data.VpnConfig>,
    selectedIds: Set<Long>,
    onSave: (Set<Long>) -> Unit,
    onDismiss: () -> Unit
) {
    var currentSelection by remember { mutableStateOf(selectedIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                configs.forEach { config ->
                    val isSelected = currentSelection.contains(config.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                currentSelection = if (isSelected) {
                                    currentSelection - config.id
                                } else {
                                    currentSelection + config.id
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = null // Handled by Row clickable
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(config.name)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(currentSelection) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
