package com.hyperxray.an.ui.screens.config.sections

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog

/**
 * WireGuard/WARP configuration section.
 * Handles WARP chain settings including license binding, endpoint, and private key.
 */
@Composable
fun WireGuardSection(
    enableWarp: Boolean,
    warpPrivateKey: String,
    warpPeerPublicKey: String,
    warpEndpoint: String,
    warpLocalAddress: String,
    warpLicenseKey: String,
    warpAccountType: String?,
    warpQuota: String,
    isBindingLicense: Boolean,
    onWarpSettingsChange: (enabled: Boolean, privateKey: String, endpoint: String, localAddress: String) -> Unit,
    onLicenseKeyInputChange: (String) -> Unit,
    onBindLicenseKey: () -> Unit,
    onGenerateWarpIdentity: () -> Unit,
    onCreateFreeIdentity: () -> Unit,
    onSetWarpPrivateKey: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "🌐 WireGuard over Xray",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = Color(0xFFE0E0E0)
            )

            Text(
                text = "Use WireGuard as standalone outbound with routing rules",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF808080)
            )

            // Enable WireGuard Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable WireGuard over Xray",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFFE0E0E0)
                    )
                    Text(
                        text = "Use WireGuard as standalone outbound with routing rules",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF808080)
                    )
                }
                Switch(
                    checked = enableWarp,
                    onCheckedChange = { enabled ->
                        onWarpSettingsChange(
                            enabled,
                            if (enabled && warpPrivateKey.isEmpty()) "" else warpPrivateKey,
                            warpEndpoint,
                            warpLocalAddress
                        )
                    }
                )
            }

            // WARP Settings (visible when enabled)
            if (enableWarp) {
                // Cloudflare WARP Identity Card
                WarpAccountCard(
                    accountType = warpAccountType,
                    quota = warpQuota,
                    licenseKey = warpLicenseKey,
                    isBindingLicense = isBindingLicense,
                    onLicenseKeyInputChange = onLicenseKeyInputChange,
                    onBindLicenseKey = onBindLicenseKey,
                    onGenerateWarpIdentity = onGenerateWarpIdentity,
                    onCreateFreeIdentity = onCreateFreeIdentity
                )
                
                // Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0xFF404040))
                        .padding(vertical = 8.dp)
                )

                // Endpoint Field
                OutlinedTextField(
                    value = warpEndpoint,
                    onValueChange = { newEndpoint ->
                        onWarpSettingsChange(
                            true,
                            warpPrivateKey,
                            newEndpoint,
                            warpLocalAddress
                        )
                    },
                    label = { 
                        Text(
                            "WARP Endpoint",
                            color = Color(0xFFB0B0B0)
                        ) 
                    },
                    placeholder = { 
                        Text(
                            "engage.cloudflareclient.com:2408",
                            color = Color(0xFF808080)
                        ) 
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE0E0E0),
                        unfocusedTextColor = Color(0xFFE0E0E0),
                        focusedBorderColor = Color(0xFF4A9EFF),
                        unfocusedBorderColor = Color(0xFF404040),
                        focusedLabelColor = Color(0xFFB0B0B0),
                        unfocusedLabelColor = Color(0xFF808080)
                    ),
                    keyboardOptions = KeyboardOptions.Default.copy(
                        keyboardType = KeyboardType.Text
                    )
                )

                // Local Address Field
                OutlinedTextField(
                    value = warpLocalAddress,
                    onValueChange = { newAddress ->
                        onWarpSettingsChange(
                            true,
                            warpPrivateKey,
                            warpEndpoint,
                            newAddress
                        )
                    },
                    label = { 
                        Text(
                            "Local Address (CIDR)",
                            color = Color(0xFFB0B0B0)
                        ) 
                    },
                    placeholder = { 
                        Text(
                            "172.16.0.2/32, 2606:4700:110:8f5a:7c46:852e:f45c:6d35/128",
                            color = Color(0xFF808080)
                        ) 
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE0E0E0),
                        unfocusedTextColor = Color(0xFFE0E0E0),
                        focusedBorderColor = Color(0xFF4A9EFF),
                        unfocusedBorderColor = Color(0xFF404040),
                        focusedLabelColor = Color(0xFFB0B0B0),
                        unfocusedLabelColor = Color(0xFF808080)
                    ),
                    keyboardOptions = KeyboardOptions.Default.copy(
                        keyboardType = KeyboardType.Text
                    )
                )

                // Private Key Field (Hidden by default, can be shown for advanced users)
                var showPrivateKey by remember { mutableStateOf(false) }
                if (showPrivateKey) {
                    OutlinedTextField(
                        value = warpPrivateKey,
                        onValueChange = { newKey ->
                            // Update StateFlow immediately for UI responsiveness
                            onSetWarpPrivateKey(newKey)
                            // Update config when key is complete (44 chars), empty, or when user stops typing
                            // This allows users to type without triggering config updates on every keystroke
                            // But ensures user's manual key is saved when complete
                            if (newKey.isEmpty() || newKey.length == 44) {
                                try {
                                    onWarpSettingsChange(
                                        true,
                                        newKey,
                                        warpEndpoint,
                                        warpLocalAddress
                                    )
                                } catch (e: Exception) {
                                    // Key validation error - show to user but don't crash
                                    Log.w("WireGuardSection", "WARP key validation error: ${e.message}")
                                }
                            }
                        },
                        label = { 
                            Text(
                                "Private Key (Advanced)",
                                color = Color(0xFFB0B0B0)
                            ) 
                        },
                        placeholder = { 
                            Text(
                                "Base64-encoded private key",
                                color = Color(0xFF808080)
                            ) 
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE0E0E0),
                            unfocusedTextColor = Color(0xFFE0E0E0),
                            focusedBorderColor = Color(0xFF4A9EFF),
                            unfocusedBorderColor = Color(0xFF404040),
                            focusedLabelColor = Color(0xFFB0B0B0),
                            unfocusedLabelColor = Color(0xFF808080)
                        ),
                        keyboardOptions = KeyboardOptions.Default.copy(
                            keyboardType = KeyboardType.Text
                        )
                    )
                }

                // Show/Hide Private Key Button
                TextButton(
                    onClick = { showPrivateKey = !showPrivateKey },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (showPrivateKey) "Hide Private Key" else "Show Private Key (Advanced)",
                        color = Color(0xFF4A9EFF)
                    )
                }

                // Info Text
                Text(
                    text = "Public Key: ${warpPeerPublicKey.take(20)}... (Cloudflare WARP)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF808080)
                )
            }
        }
    }
}

/**
 * Cloudflare WARP Account Management Card.
 * Displays account status, quota, and license key management.
 */
@Composable
private fun WarpAccountCard(
    accountType: String?,
    quota: String,
    licenseKey: String,
    isBindingLicense: Boolean,
    onLicenseKeyInputChange: (String) -> Unit,
    onBindLicenseKey: () -> Unit,
    onGenerateWarpIdentity: () -> Unit,
    onCreateFreeIdentity: () -> Unit
) {
    var showLicenseKey by remember { mutableStateOf(false) }
    var showRegenerateDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF252525)
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "☁️ Cloudflare WARP Identity",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color(0xFFE0E0E0)
                )
            }
            
            // Account Type Status Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Account Type:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB0B0B0)
                )
                
                val accountTypeDisplay = when {
                    accountType?.equals("plus", ignoreCase = true) == true -> "WARP+"
                    accountType?.equals("unlimited", ignoreCase = true) == true -> "WARP Unlimited"
                    accountType?.equals("premium", ignoreCase = true) == true -> "WARP Premium"
                    accountType?.equals("free", ignoreCase = true) == true -> "FREE"
                    accountType != null -> accountType.uppercase()
                    else -> "UNREGISTERED"
                }
                
                val badgeColor = when {
                    accountTypeDisplay.contains("PLUS", ignoreCase = true) || 
                    accountTypeDisplay.contains("UNLIMITED", ignoreCase = true) ||
                    accountTypeDisplay.contains("PREMIUM", ignoreCase = true) -> Color(0xFF4A9EFF)
                    accountTypeDisplay == "FREE" -> Color(0xFF808080)
                    else -> Color(0xFF404040)
                }
                
                Box(
                    modifier = Modifier
                        .background(badgeColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = accountTypeDisplay,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White
                    )
                }
            }
            
            // Data Quota Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Data Quota:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB0B0B0)
                )
                Text(
                    text = quota,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = when {
                        quota == "Unlimited" -> Color(0xFF4A9EFF)
                        quota.contains("GB", ignoreCase = true) -> Color(0xFF4A9EFF) // WARP+ quota in GB
                        quota == "Limited" -> Color(0xFFFFA500)
                        else -> Color(0xFF808080)
                    }
                )
            }
            
            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF404040))
            )
            
            // License Key Input Section
            Text(
                text = "License Key (Optional)",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = Color(0xFFE0E0E0)
            )
            
            OutlinedTextField(
                value = licenseKey,
                onValueChange = onLicenseKeyInputChange,
                label = { 
                    Text(
                        "WARP+ License Key",
                        color = Color(0xFFB0B0B0)
                    ) 
                },
                placeholder = { 
                    Text(
                        "xxxx-xxxx-xxxx",
                        color = Color(0xFF808080)
                    ) 
                },
                singleLine = true,
                enabled = !isBindingLicense,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showLicenseKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showLicenseKey = !showLicenseKey }) {
                        Icon(
                            imageVector = if (showLicenseKey) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (showLicenseKey) "Hide license key" else "Show license key",
                            tint = Color(0xFFB0B0B0)
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFE0E0E0),
                    unfocusedTextColor = Color(0xFFE0E0E0),
                    focusedBorderColor = Color(0xFF4A9EFF),
                    unfocusedBorderColor = Color(0xFF404040),
                    focusedLabelColor = Color(0xFFB0B0B0),
                    unfocusedLabelColor = Color(0xFF808080),
                    disabledTextColor = Color(0xFF808080),
                    disabledBorderColor = Color(0xFF303030)
                ),
                keyboardOptions = KeyboardOptions.Default.copy(
                    keyboardType = KeyboardType.Text
                ),
                supportingText = {
                    Text(
                        text = "Format: xxxx-xxxx-xxxx (alphanumeric)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF808080)
                    )
                }
            )
            
            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Generate Free Account / Re-Generate Identity Button
                // Always visible - shows "Generate Free Account" if no account, "Re-Generate Identity" if account exists
                Button(
                    onClick = {
                        if (accountType != null) {
                            // Show confirmation dialog if account exists
                            showRegenerateDialog = true
                        } else {
                            // Directly create free account if no account exists
                            onCreateFreeIdentity()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (accountType != null) Color(0xFFFFA500) else Color(0xFF4A9EFF)
                    )
                ) {
                    Text(
                        text = if (accountType != null) "Re-Generate Identity" else "Generate Free Account",
                        color = Color.White
                    )
                }
                
                // Apply License Button (if key is entered)
                Button(
                    onClick = onBindLicenseKey,
                    enabled = !isBindingLicense && licenseKey.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4A9EFF),
                        disabledContainerColor = Color(0xFF303030)
                    )
                ) {
                    if (isBindingLicense) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Binding...",
                                color = Color.White
                            )
                        }
                    } else {
                        Text(
                            text = "Apply License",
                            color = Color.White
                        )
                    }
                }
            }
            
            // Re-Generate Identity Confirmation Dialog
            if (showRegenerateDialog) {
                AlertDialog(
                    onDismissRequest = { showRegenerateDialog = false },
                    title = {
                        Text(
                            text = "Re-Generate WARP Identity?",
                            color = Color(0xFFE0E0E0)
                        )
                    },
                    text = {
                        Text(
                            text = "This will create a new free WARP account and replace your current identity. Your current account settings will be lost. Continue?",
                            color = Color(0xFFB0B0B0)
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showRegenerateDialog = false
                                onCreateFreeIdentity()
                            }
                        ) {
                            Text(
                                text = "Re-Generate",
                                color = Color(0xFF4A9EFF)
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showRegenerateDialog = false }
                        ) {
                            Text(
                                text = "Cancel",
                                color = Color(0xFF808080)
                            )
                        }
                    },
                    containerColor = Color(0xFF1A1A1A)
                )
            }
        }
    }
}

