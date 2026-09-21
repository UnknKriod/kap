package me.unknkriod.kapclient.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.unknkriod.kapclient.R
import me.unknkriod.kapclient.ui.viewmodel.VpnViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(
    viewModel: VpnViewModel,
    onNavigateBack: () -> Unit
) {
    val blockRules by viewModel.blockRules.collectAsStateWithLifecycle()
    val directRules by viewModel.directRules.collectAsStateWithLifecycle()
    val proxyRules by viewModel.proxyRules.collectAsStateWithLifecycle()
    val defaultOutbound by viewModel.defaultOutbound.collectAsStateWithLifecycle()
    val directRussianSites by viewModel.directRussianSites.collectAsStateWithLifecycle()
    val directWhitelist by viewModel.directWhitelist.collectAsStateWithLifecycle()

    val baseDirect = remember(directRules, directRussianSites, directWhitelist) {
        val currentDirect = directRules.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (directRussianSites) {
            val target = listOf("geosite:category-ru", "geoip:ru")
            target.forEach { if (!currentDirect.contains(it)) currentDirect.add(it) }
        }
        if (directWhitelist) {
            val target = listOf("geoip:ru-whitelist", "geosite:yandex", "geosite:vk", "geosite:avito")
            target.forEach { if (!currentDirect.contains(it)) currentDirect.add(it) }
        }
        currentDirect.joinToString("\n")
    }

    var tempBlock by remember(blockRules) { mutableStateOf(blockRules) }
    var tempDirect by remember(baseDirect) { mutableStateOf(baseDirect) }
    var tempProxy by remember(proxyRules) { mutableStateOf(proxyRules) }
    var tempDefault by remember(defaultOutbound) { mutableStateOf(defaultOutbound) }

    val hasChanges = tempBlock != blockRules || tempDirect != baseDirect || tempProxy != proxyRules || tempDefault != defaultOutbound

    var showBottomSheet by remember { mutableStateOf(false) }

    // Hide bottom sheet if keyboard appears
    val isKeyboardVisible = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp
    LaunchedEffect(isKeyboardVisible) {
        if (isKeyboardVisible) showBottomSheet = false
    }

    // Hide bottom sheet if changes occur after it was shown
    LaunchedEffect(tempBlock, tempDirect, tempProxy, tempDefault) {
        showBottomSheet = false
    }

    val scope = rememberCoroutineScope()
    val shakeOffset = remember { Animatable(0f) }
    val borderAlpha = remember { Animatable(0f) }

    val isDrsActive = remember(tempDirect) {
        tempDirect.split("\n").map { it.trim() }.containsAll(listOf("geosite:category-ru", "geoip:ru"))
    }
    val isDwActive = remember(tempDirect) {
        tempDirect.split("\n").map { it.trim() }.containsAll(listOf("geoip:ru-whitelist", "geosite:yandex", "geosite:vk", "geosite:avito"))
    }
    val isPdActive = remember(tempDirect) {
        tempDirect.split("\n").map { it.trim() }.containsAll(listOf("geosite:private", "geoip:private"))
    }
    val isAbActive = remember(tempBlock) {
        tempBlock.split("\n").map { it.trim() }.contains("geosite:category-ads-all")
    }

    val handleBackClick: () -> Unit = {
        if (hasChanges) {
            showBottomSheet = true
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
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routing_title)) },
                navigationIcon = {
                    IconButton(onClick = handleBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.setRoutingRules(tempBlock, tempDirect, tempProxy, tempDefault)
                        viewModel.setDirectRussianSites(isDrsActive)
                        viewModel.setDirectWhitelist(isDwActive)
                        onNavigateBack()
                    }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.direct_russian_sites)) },
                supportingContent = { Text(stringResource(R.string.direct_russian_sites_desc)) },
                trailingContent = {
                    Switch(
                        checked = isDrsActive,
                        onCheckedChange = { checked ->
                            val currentLines = tempDirect.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                            val targetRules = listOf("geosite:category-ru", "geoip:ru")
                            if (checked) {
                                targetRules.forEach { if (!currentLines.contains(it)) currentLines.add(it) }
                            } else {
                                currentLines.removeAll(targetRules)
                            }
                            tempDirect = currentLines.joinToString("\n")
                        }
                    )
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.direct_whitelist)) },
                supportingContent = { Text(stringResource(R.string.direct_whitelist_desc)) },
                trailingContent = {
                    Switch(
                        checked = isDwActive,
                        onCheckedChange = { checked ->
                            val currentLines = tempDirect.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                            val targetRules = listOf("geoip:ru-whitelist", "geosite:yandex", "geosite:vk", "geosite:avito")
                            if (checked) {
                                targetRules.forEach { if (!currentLines.contains(it)) currentLines.add(it) }
                            } else {
                                currentLines.removeAll(targetRules)
                            }
                            tempDirect = currentLines.joinToString("\n")
                        }
                    )
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.private_direct)) },
                supportingContent = { Text(stringResource(R.string.private_direct_desc)) },
                trailingContent = {
                    Switch(
                        checked = isPdActive,
                        onCheckedChange = { checked ->
                            val currentLines = tempDirect.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                            val targetRules = listOf("geosite:private", "geoip:private")
                            if (checked) {
                                targetRules.forEach { if (!currentLines.contains(it)) currentLines.add(it) }
                            } else {
                                currentLines.removeAll(targetRules)
                            }
                            tempDirect = currentLines.joinToString("\n")
                        }
                    )
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.ad_blocking)) },
                supportingContent = { Text(stringResource(R.string.ad_blocking_desc)) },
                trailingContent = {
                    Switch(
                        checked = isAbActive,
                        onCheckedChange = { checked ->
                            val currentLines = tempBlock.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                            val target = "geosite:category-ads-all"
                            if (checked) {
                                if (!currentLines.contains(target)) currentLines.add(target)
                            } else {
                                currentLines.remove(target)
                            }
                            tempBlock = currentLines.joinToString("\n")
                        }
                    )
                }
            )

            HorizontalDivider()

            Text(stringResource(R.string.routing_desc), style = MaterialTheme.typography.bodySmall)

            OutlinedTextField(
                value = tempDirect,
                onValueChange = { tempDirect = it },
                label = { Text(stringResource(R.string.direct_rules)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("domain:example.com\ncidr:1.2.3.4/24") },
                minLines = 3
            )

            OutlinedTextField(
                value = tempProxy,
                onValueChange = { tempProxy = it },
                label = { Text(stringResource(R.string.proxy_rules)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("ruleset:geoip-google") },
                minLines = 3
            )

            OutlinedTextField(
                value = tempBlock,
                onValueChange = { tempBlock = it },
                label = { Text(stringResource(R.string.block_rules)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("geosite:category-ads-all\ngeoip:private") },
                minLines = 3
            )

            HorizontalDivider()

            Text(stringResource(R.string.default_outbound), fontWeight = FontWeight.Bold)
            
            Column {
                listOf("proxy", "direct", "block").forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { tempDefault = option },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = tempDefault == option,
                            onClick = { tempDefault = option }
                        )
                        Text(option.replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showBottomSheet && WindowInsets.ime.asPaddingValues().calculateBottomPadding() == 0.dp,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .graphicsLayer {
                        translationX = shakeOffset.value
                    }
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                        RoundedCornerShape(24.dp)
                    )
                    .border(
                        width = 3.dp,
                        color = Color(0xFF40E0D0).copy(alpha = 0.8f + (0.2f * borderAlpha.value)),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.unsaved_changes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFB2C2C)
                    )
                    
                    Spacer(Modifier.height(5.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        TextButton(
                            onClick = {
                                tempBlock = blockRules
                                tempDirect = baseDirect
                                tempProxy = proxyRules
                                tempDefault = defaultOutbound
                                showBottomSheet = false
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                stringResource(R.string.revert),
                                maxLines = 1,
                                color = Color(0xFFFB2C2C),
                                modifier = Modifier.offset(y = 0.4.dp)
                            )
                        }

                        TextButton(
                            onClick = {
                                viewModel.setRoutingRules(tempBlock, tempDirect, tempProxy, tempDefault)
                                viewModel.setDirectRussianSites(isDrsActive)
                                viewModel.setDirectWhitelist(isDwActive)
                                showBottomSheet = false
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(stringResource(R.string.save), maxLines = 1)
                        }

                        Button(
                            onClick = { showBottomSheet = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(stringResource(R.string.discard_cancel), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
}
