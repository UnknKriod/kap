package me.unknkriod.kapclient.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.unknkriod.kapclient.R
import me.unknkriod.kapclient.data.VpnConfig
import me.unknkriod.kapclient.ui.viewmodel.VpnViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditConfigScreen(
    viewModel: VpnViewModel,
    configId: Long?,
    onNavigateBack: () -> Unit
) {
    val configs by viewModel.configs.collectAsStateWithLifecycle()
    val existingConfig = remember(configId, configs) {
        configs.find { it.id == configId }
    }

    var name by remember { mutableStateOf(existingConfig?.name ?: "") }
    var server by remember { mutableStateOf(existingConfig?.server ?: "") }
    var uid by remember { mutableStateOf(existingConfig?.uid ?: "") }
    var psk by remember { mutableStateOf("") }
    
    LaunchedEffect(existingConfig) {
        existingConfig?.let {
            psk = viewModel.getPsk(it.id) ?: ""
        }
    }
    
    var sni by remember { mutableStateOf(existingConfig?.sni ?: "") }
    
    var behindCdn by remember { mutableStateOf(existingConfig?.behindCdn ?: false) }
    var cookieUplink by remember { mutableStateOf(existingConfig?.cookieUplink ?: false) }
    var debug by remember { mutableStateOf(existingConfig?.debug ?: false) }
    var insecure by remember { mutableStateOf(existingConfig?.insecure ?: false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (configId == null) stringResource(R.string.add_configuration) else "Edit Config") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val config = VpnConfig(
                            id = configId ?: 0,
                            name = name,
                            server = server,
                            uid = uid.ifBlank { null },
                            behindCdn = behindCdn,
                            debug = debug,
                            insecure = insecure,
                            cookieUplink = cookieUplink,
                            downlinkWorkers = existingConfig?.downlinkWorkers ?: 2,
                            uplinkPipeline = existingConfig?.uplinkPipeline ?: 8,
                            subscriptionId = existingConfig?.subscriptionId,
                            sni = sni.ifBlank { null }
                        )
                        viewModel.saveConfig(config, psk)
                        onNavigateBack()
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Server URL") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = uid,
                onValueChange = { uid = it },
                label = { Text("UID") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = psk,
                onValueChange = { psk = it },
                label = { Text("PSK") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            OutlinedTextField(
                value = sni,
                onValueChange = { sni = it },
                label = { Text("SNI (Optional)") },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()
            Text("Advanced Settings", fontWeight = FontWeight.Bold)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = behindCdn, onCheckedChange = { behindCdn = it })
                Text("Behind CDN")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = cookieUplink, onCheckedChange = { cookieUplink = it })
                Text("Cookie Uplink")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = insecure, onCheckedChange = { insecure = it })
                Column {
                    Text("Insecure (Skip TLS Verify)")
                    if (insecure) {
                        Text(
                            "Warning: Traffic may be visible to others",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = debug, onCheckedChange = { debug = it })
                Text("Debug Logging")
            }
            
            if (configId != null) {
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = {
                        existingConfig?.let { viewModel.deleteConfig(it) }
                        onNavigateBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete_config))
                }
            }
        }
    }
}
