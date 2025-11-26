package com.hyperxray.an.feature.warp.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyperxray.an.feature.warp.domain.entity.WarpConfigType
import com.hyperxray.an.feature.warp.presentation.viewmodel.WarpViewModel

/**
 * Main WARP screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarpScreen(
    viewModel: WarpViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WARP Account") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Error message
            uiState.error?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Account registration section
            if (uiState.account == null) {
                RegisterAccountSection(
                    onRegister = { licenseKey ->
                        viewModel.registerAccount(licenseKey.ifEmpty { null })
                    },
                    isLoading = uiState.isLoading
                )
            } else {
            // Account info section
            uiState.account?.let { account ->
                AccountInfoSection(
                    account = account,
                    onUpdateLicense = { licenseKey ->
                        viewModel.updateLicense(licenseKey)
                    },
                    isLoading = uiState.isLoading
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Config generation section
                ConfigGenerationSection(
                    onGenerateConfig = { configType, endpoint ->
                        viewModel.generateConfig(configType, endpoint.ifEmpty { null })
                    },
                    generatedConfig = uiState.generatedConfig,
                    configType = uiState.configType,
                    isLoading = uiState.isLoading,
                    onClearConfig = { viewModel.clearGeneratedConfig() }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Devices section
                DevicesSection(
                    devices = uiState.devices,
                    onLoadDevices = { viewModel.loadDevices() },
                    onRemoveDevice = { deviceId ->
                        viewModel.removeDevice(deviceId)
                    },
                    isLoading = uiState.isLoading
                )
            }
            }
        }
    }
}

@Composable
private fun RegisterAccountSection(
    onRegister: (String) -> Unit,
    isLoading: Boolean
) {
    var licenseKey by remember { mutableStateOf("") }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Register WARP Account",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Create a new WARP account. Optionally provide a license key for WARP+.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = licenseKey,
                onValueChange = { licenseKey = it },
                label = { Text("License Key (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = { onRegister(licenseKey) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Register Account")
            }
        }
    }
}

@Composable
private fun AccountInfoSection(
    account: com.hyperxray.an.feature.warp.domain.entity.WarpAccount,
    onUpdateLicense: (String) -> Unit,
    isLoading: Boolean
) {
    var licenseKey by remember { mutableStateOf("") }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Account Information",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            InfoRow("Account ID", account.accountId)
            InfoRow("Type", account.account.accountType ?: "free")
            InfoRow("WARP+", if (account.account.warpPlus) "Yes" else "No")
            InfoRow("IPv4", account.config.interfaceData?.addresses?.v4 ?: "N/A")
            InfoRow("IPv6", account.config.interfaceData?.addresses?.v6 ?: "N/A")
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Divider()
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Update License Key",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = licenseKey,
                onValueChange = { licenseKey = it },
                label = { Text("License Key") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = { onUpdateLicense(licenseKey) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && licenseKey.isNotEmpty()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Update License")
            }
        }
    }
}

@Composable
private fun ConfigGenerationSection(
    onGenerateConfig: (WarpConfigType, String) -> Unit,
    generatedConfig: String?,
    configType: WarpConfigType?,
    isLoading: Boolean,
    onClearConfig: () -> Unit
) {
    var endpoint by remember { mutableStateOf("") }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Generate Configuration",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text("Endpoint (Optional)") },
                placeholder = { Text("engage.cloudflareclient.com:2408") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onGenerateConfig(WarpConfigType.WIREGUARD, endpoint) },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text("WireGuard")
                }
                Button(
                    onClick = { onGenerateConfig(WarpConfigType.XRAY, endpoint) },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text("Xray")
                }
                Button(
                    onClick = { onGenerateConfig(WarpConfigType.SINGBOX, endpoint) },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text("sing-box")
                }
            }
            
            generatedConfig?.let { config ->
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "${configType?.name} Configuration",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = config,
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = onClearConfig,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear")
                }
            }
        }
    }
}

@Composable
private fun DevicesSection(
    devices: List<com.hyperxray.an.feature.warp.domain.entity.WarpDevice>,
    onLoadDevices: () -> Unit,
    onRemoveDevice: (String) -> Unit,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Devices (${devices.size})",
                    style = MaterialTheme.typography.titleLarge
                )
                Button(
                    onClick = onLoadDevices,
                    enabled = !isLoading
                ) {
                    Text("Refresh")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (devices.isEmpty()) {
                Text(
                    text = "No devices found. Click Refresh to load devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                devices.forEach { device ->
                    DeviceItem(
                        device = device,
                        onRemove = { onRemoveDevice(device.id) },
                        isLoading = isLoading
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun DeviceItem(
    device: com.hyperxray.an.feature.warp.domain.entity.WarpDevice,
    onRemove: () -> Unit,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name ?: device.id,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${device.type ?: "Unknown"} - ${device.model ?: "N/A"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Role: ${device.role ?: "N/A"} | Active: ${device.active}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (device.role == "child") {
                    TextButton(
                        onClick = onRemove,
                        enabled = !isLoading
                    ) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

