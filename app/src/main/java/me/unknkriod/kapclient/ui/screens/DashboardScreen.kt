package me.unknkriod.kapclient.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import me.unknkriod.kapclient.R
import me.unknkriod.kapclient.data.Subscription
import me.unknkriod.kapclient.data.VpnConfig
import me.unknkriod.kapclient.ui.viewmodel.VpnViewModel
import me.unknkriod.kapclient.util.VpnStatus
import me.unknkriod.kapclient.util.RuleSetManager
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: VpnViewModel,
    onNavigateToAddConfig: () -> Unit,
    onNavigateToEditConfig: (Long) -> Unit,
    onNavigateToProxyApps: () -> Unit,
    onNavigateToRouting: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val vpnStatus by viewModel.vpnStatus.collectAsStateWithLifecycle()
    val configs by viewModel.configs.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val latency by viewModel.latency.collectAsStateWithLifecycle()
    val configLatencies by viewModel.configLatencies.collectAsStateWithLifecycle()
    val connectionStartTime by viewModel.connectionStartTime.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val importError by viewModel.importError.collectAsStateWithLifecycle()
    val pingResults by viewModel.pingResults.collectAsStateWithLifecycle()
    val isRulesReady by viewModel.isRulesReady.collectAsStateWithLifecycle()
    val isIfconfigReachable by viewModel.isIfconfigReachable.collectAsStateWithLifecycle()
    val isUpdatingRules by RuleSetManager.isUpdating.collectAsStateWithLifecycle()
    val isMeasuringLatency = latency == "..." || configLatencies.values.any { it == "..." }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(importError) {
        importError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearImportError()
        }
    }

    LaunchedEffect(pingResults) {
        pingResults?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = SnackbarDuration.Long
            )
            viewModel.clearPingResults()
        }
    }

    LaunchedEffect(isRulesReady) {
        if (!isRulesReady) {
            viewModel.checkWhitelists()
        }
    }

    var cooldownSeconds by remember { mutableIntStateOf(0) }
    val isCoolingDown = cooldownSeconds > 0
    var statusCooldownSeconds by remember { mutableIntStateOf(0) }
    val isStatusCoolingDown = statusCooldownSeconds > 0

    LaunchedEffect(cooldownSeconds) {
        if (cooldownSeconds > 0) {
            kotlinx.coroutines.delay(1000)
            cooldownSeconds -= 1
        }
    }

    LaunchedEffect(statusCooldownSeconds) {
        if (statusCooldownSeconds > 0) {
            kotlinx.coroutines.delay(1000)
            statusCooldownSeconds -= 1
        }
    }

    var connectionTime by remember { mutableStateOf("00:00:00") }

    LaunchedEffect(vpnStatus, connectionStartTime) {
        if (vpnStatus == VpnStatus.CONNECTED && connectionStartTime > 0) {
            while (true) {
                val duration = System.currentTimeMillis() - connectionStartTime
                val hours = (duration / (1000 * 60 * 60)) % 24
                val minutes = (duration / (1000 * 60)) % 60
                val seconds = (duration / 1000) % 60
                connectionTime = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                kotlinx.coroutines.delay(1000)
            }
        } else {
            connectionTime = "00:00:00"
        }
    }

    val prepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.toggleVpn(context, null)
        }
    }

    var showImportDialog by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var editingSubscription by remember { mutableStateOf<Subscription?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add")
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.scan_qr)) },
                                onClick = {
                                    showAddMenu = false
                                    val scanner = GmsBarcodeScanning.getClient(context)
                                    scanner.startScan()
                                        .addOnSuccessListener { barcode ->
                                            barcode.rawValue?.let { viewModel.handleQrCode(it) }
                                        }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_configuration)) },
                                onClick = {
                                    showAddMenu = false
                                    showImportDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_subscription)) },
                                onClick = {
                                    showAddMenu = false
                                    val clipData = clipboardManager.getText()
                                    android.util.Log.i("KAP_DEBUG", "Clipboard button clicked. Text present: ${!clipData?.text.isNullOrBlank()}")
                                    
                                    val url = clipData?.text?.trim()?.toString()
                                    if (!url.isNullOrBlank()) {
                                        viewModel.addSubscription("", url)
                                    } else {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Clipboard is empty or does not contain text")
                                        }
                                    }
                                }
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.routing_title)) },
                                onClick = {
                                    showMoreMenu = false
                                    onNavigateToRouting()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.app_proxying)) },
                                onClick = {
                                    showMoreMenu = false
                                    onNavigateToProxyApps()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                onClick = {
                                    showMoreMenu = false
                                    onNavigateToSettings()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(26.dp))
            
            // Connection Timer
            if (isRulesReady) {
                Spacer(modifier = Modifier.height(11.dp))

                Text(
                    text = stringResource(R.string.connection_time),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = connectionTime,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = stringResource(R.string.rules_not_ready),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 2.dp),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.rules_not_ready_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 0.dp),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Normal
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    if (isIfconfigReachable) {
                        OutlinedButton(
                            onClick = { viewModel.disableAllRouting() },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = stringResource(R.string.disable_routing),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        Button(
                            onClick = { viewModel.updateRules(context) },
                            modifier = Modifier.weight(1f),
                            enabled = !isUpdatingRules
                        ) {
                            if (isUpdatingRules) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.update_databases),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = { viewModel.disableAllRouting() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.disable_routing),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.updateRules(context) },
                            modifier = Modifier.weight(1f),
                            enabled = !isUpdatingRules,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        ) {
                            if (isUpdatingRules) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.update_databases),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            val poolConfigs by remember { derivedStateOf { configs.filter { it.inPool } } }

            // Connect Button
            ConnectButton(
                status = vpnStatus,
                isLocked = !isRulesReady || isCoolingDown || poolConfigs.isEmpty() || isMeasuringLatency,
                onClick = {
                    cooldownSeconds = 5
                    viewModel.toggleVpn(context, prepareLauncher)
                }
            )

            if (isCoolingDown) {
                Text(
                    text = "Перезарядка ${cooldownSeconds}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else Spacer(modifier = Modifier.height(22.dp))

            Spacer(modifier = Modifier.height(6.dp))

            // Status Label
            val statusText = when (vpnStatus) {
                VpnStatus.DISCONNECTED -> stringResource(R.string.status_disconnected)
                VpnStatus.CONNECTING -> stringResource(R.string.status_connecting)
                VpnStatus.CONNECTED -> stringResource(R.string.status_connected)
                VpnStatus.STOPPING -> stringResource(R.string.status_stopping)
                VpnStatus.PAUSED -> stringResource(R.string.status_paused)
                VpnStatus.OPTIMIZING -> stringResource(R.string.status_optimizing)
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.clickable(enabled = vpnStatus == VpnStatus.CONNECTED && !isMeasuringLatency && !isStatusCoolingDown) {
                    statusCooldownSeconds = 5
                    viewModel.measureLatency()
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isStatusCoolingDown) "Ждите ${statusCooldownSeconds}s" else statusText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (latency != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = latency!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Manual Configs Island
            val manualConfigs = configs.filter { it.subscriptionId == null }
            if (manualConfigs.isNotEmpty()) {
                IslandCard(
                    title = stringResource(R.string.configurations),
                    items = manualConfigs,
                    configLatencies = configLatencies,
                    onEdit = { onNavigateToEditConfig(it) },
                    onInPoolChange = { config, inPool -> viewModel.updateConfigInPool(config, inPool) },
                    isVpnActive = vpnStatus == VpnStatus.CONNECTED || vpnStatus == VpnStatus.CONNECTING || vpnStatus == VpnStatus.OPTIMIZING,
                    isMeasuringLatency = isMeasuringLatency
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Subscription Islands
            subscriptions.forEach { sub ->
                val subConfigs = configs.filter { it.subscriptionId == sub.id }
                IslandCard(
                    title = sub.name,
                    items = subConfigs,
                    configLatencies = configLatencies,
                    isSubscription = true,
                    subscription = sub,
                    onEdit = { onNavigateToEditConfig(it) },
                    onRefresh = { viewModel.updateSubscription(sub.id) },
                    onDelete = { viewModel.deleteSubscription(sub) },
                    onEditSubscription = { editingSubscription = sub },
                    onMoveToTop = { viewModel.moveSubscriptionToTop(sub) },
                    onMeasureLatency = { viewModel.measureSubscriptionLatencies(sub.id) },
                    isVpnConnected = vpnStatus == VpnStatus.CONNECTED,
                    onInPoolChange = { config, inPool -> viewModel.updateConfigInPool(config, inPool) },
                    isVpnActive = vpnStatus == VpnStatus.CONNECTED || vpnStatus == VpnStatus.CONNECTING || vpnStatus == VpnStatus.OPTIMIZING,
                    isMeasuringLatency = isMeasuringLatency
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            if (configs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.no_configs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showImportDialog) {
        ImportDialog(
            onDismiss = { showImportDialog = false },
            onImport = { link ->
                viewModel.importConfig(link)
                showImportDialog = false
            },
            onManualAdd = {
                onNavigateToAddConfig()
                showImportDialog = false
            }
        )
    }

    if (isImporting) {
        LoadingOverlay()
    }

    editingSubscription?.let { sub ->
        EditSubscriptionDialog(
            subscription = sub,
            onDismiss = { editingSubscription = null },
            onSave = { name, url ->
                viewModel.saveSubscription(sub.copy(name = name, url = url))
                editingSubscription = null
            }
        )
    }
}

@Composable
fun EditSubscriptionDialog(
    subscription: Subscription,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(subscription.name) }
    var url by remember { mutableStateOf(subscription.url) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_subscription)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.url)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, url) },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
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

@Composable
fun LoadingOverlay() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(Color(0xFF2B2B2B), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun IslandCard(
    title: String,
    items: List<VpnConfig>,
    configLatencies: Map<Long, String> = emptyMap(),
    isSubscription: Boolean = false,
    subscription: Subscription? = null,
    onEdit: (Long) -> Unit,
    onRefresh: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onEditSubscription: (() -> Unit)? = null,
    onMoveToTop: (() -> Unit)? = null,
    onMeasureLatency: (() -> Unit)? = null,
    isVpnConnected: Boolean = false,
    onInPoolChange: (VpnConfig, Boolean) -> Unit,
    isVpnActive: Boolean = false,
    isMeasuringLatency: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                if (isSubscription) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onRefresh != null) {
                            IconButton(onClick = onRefresh, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
                            }

                            Spacer(Modifier.width(15.dp))
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.response_time)) },
                                    onClick = {
                                        showMenu = false
                                        onMeasureLatency?.invoke()
                                    },
                                    enabled = !isMeasuringLatency
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit)) },
                                    onClick = {
                                        showMenu = false
                                        onEditSubscription?.invoke()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.move_to_top)) },
                                    onClick = {
                                        showMenu = false
                                        onMoveToTop?.invoke()
                                    }
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showMenu = false
                                        onDelete?.invoke()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (isSubscription && subscription != null) {
                Spacer(modifier = Modifier.height(12.dp))
                val progress = if (subscription.trafficLimit > 0) {
                    (subscription.trafficUsed.toFloat() / subscription.trafficLimit).coerceIn(0f, 1f)
                } else {
                    0f
                }
                
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val usedStr = formatTraffic(subscription.trafficUsed)
                    val limitStr = if (subscription.trafficLimit > 0) formatTraffic(subscription.trafficLimit) else "∞"
                    Text("$usedStr / $limitStr", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    subscription.expiresAt?.let {
                        Text(it.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            items.forEach { config ->
                ConfigItem(
                    config = config,
                    latency = configLatencies[config.id],
                    onEdit = { onEdit(config.id) },
                    onInPoolChange = { onInPoolChange(config, it) },
                    isEnabled = !isVpnActive
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun ConfigItem(
    config: VpnConfig,
    latency: String? = null,
    onEdit: () -> Unit,
    onInPoolChange: (Boolean) -> Unit,
    isEnabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = isEnabled) { onInPoolChange(!config.inPool) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Spacer(Modifier.height(2.dp))

            Text(
                text = config.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "KAP / ${if (config.server.startsWith("https")) "HTTPS" else "HTTP"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (latency != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = latency,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (latency == "Timeout") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            if (config.trafficLimit > 0 || config.trafficUsed > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                val usedStr = formatTraffic(config.trafficUsed)
                if (config.trafficLimit > 0) {
                    val limitStr = formatTraffic(config.trafficLimit)
                    val progress = (config.trafficUsed.toFloat() / config.trafficLimit).coerceIn(0f, 1f)
                    val isExhausted = config.trafficRemaining == 0L
                    
                    Column(modifier = Modifier.fillMaxWidth(0.8f)) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(2.dp).clip(CircleShape),
                            color = if (isExhausted) MaterialTheme.colorScheme.error else if (progress > 0.9f) Color(0xFFFFA500) else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Text(
                            text = if (isExhausted) "Exhausted" else "$usedStr / $limitStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isExhausted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp
                        )
                    }
                } else {
                    Text(
                        text = usedStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp
                    )
                }
            }
        }
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = config.inPool,
                onCheckedChange = onInPoolChange,
                enabled = isEnabled,
                modifier = Modifier.scale(0.67f),
                colors = SwitchDefaults.colors(
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onEdit, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
    onManualAdd: () -> Unit
) {
    var link by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_config)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.paste_link))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("kap-proxy://...") }
                )
                TextButton(onClick = onManualAdd) {
                    Text(stringResource(R.string.or_manual))
                }
            }
        },
        confirmButton = {
            Button(onClick = { onImport(link) }) {
                Text(stringResource(R.string.import_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun ConnectButton(
    status: VpnStatus,
    enabled: Boolean = true,
    isLocked: Boolean = false,
    onClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val shakeOffset = remember { Animatable(0f) }
    val borderAlpha = remember { Animatable(0f) }

    val isActive = status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING || status == VpnStatus.OPTIMIZING
    val isOptimizing = status == VpnStatus.OPTIMIZING
    
    val infiniteTransition = rememberInfiniteTransition(label = "optimizing")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val color by animateColorAsState(
        targetValue = when {
            isLocked -> Color(0xFF2B2B2B)
            !enabled -> Color.Gray
            isOptimizing -> Color(0xFF2B2B2B)
            isActive -> MaterialTheme.colorScheme.primary
            else -> Color(0xFF2B2B2B)
        },
        label = "color"
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            isLocked -> if (borderAlpha.value > 0.1f) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
            isOptimizing -> Color(0xFFFF6F00)
            isActive -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.primary
        },
        label = "iconColor"
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = shakeOffset.value
            }
            .size(120.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(color)
            .then(
                if (isOptimizing) {
                    Modifier.drawWithContent {
                        drawContent()
                        val strokeWidth = 10.dp.toPx()
                        val borderRadius = 32.dp.toPx()
                        val halfStroke = strokeWidth / 2
                        
                        val orange = Color(0xFFFF6F00)
                        val shader = android.graphics.SweepGradient(
                            size.width / 2f,
                            size.height / 2f,
                            intArrayOf(
                                orange.copy(alpha = 0.4f).toArgb(),
                                orange.toArgb(),
                                orange.copy(alpha = 0.4f).toArgb()
                            ),
                            floatArrayOf(0f, 0.5f, 1f)
                        )
                        val matrix = android.graphics.Matrix()
                        matrix.postRotate(rotation, size.width / 2f, size.height / 2f)
                        shader.setLocalMatrix(matrix)

                        drawRoundRect(
                            brush = ShaderBrush(shader),
                            topLeft = Offset(halfStroke, halfStroke),
                            size = Size(size.width - strokeWidth, size.height - strokeWidth),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                            cornerRadius = CornerRadius(borderRadius - halfStroke)
                        )
                    }
                } else {
                    Modifier.border(
                        width = 3.dp,
                        color = Color.Red.copy(alpha = borderAlpha.value),
                        shape = RoundedCornerShape(32.dp)
                    )
                }
            )
            .clickable(enabled = enabled || isLocked) {
                if (isLocked) {
                    scope.launch {
                        launch {
                            borderAlpha.animateTo(1f, tween(100))
                            borderAlpha.animateTo(0f, tween(400))
                        }
                        shakeOffset.animateTo(
                            targetValue = 0f,
                            animationSpec = keyframes {
                                durationMillis = 400
                                -15f at 100
                                15f at 200
                                -15f at 300
                                0f at 400
                            }
                        )
                    }
                } else {
                    onClick()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when {
                isLocked -> Icons.Rounded.Lock
                isOptimizing -> Icons.Default.Refresh
                else -> Icons.Rounded.PowerSettingsNew
            },
            contentDescription = when {
                isLocked -> "Locked"
                isOptimizing -> "Optimizing"
                else -> "Connect"
            },
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer {
                    if (isOptimizing) rotationZ = rotation
                },
            tint = iconColor
        )
    }
}

fun formatTraffic(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
        else -> String.format(Locale.US, "%.1f KB", kb)
    }
}
