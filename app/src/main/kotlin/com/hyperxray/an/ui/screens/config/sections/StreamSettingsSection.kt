package com.hyperxray.an.ui.screens.config.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api

/**
 * Stream settings section for TLS configuration.
 * Displays SNI field and advanced TLS settings (Fingerprint, ALPN, AllowInsecure).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamSettingsSection(
    streamSecurity: String?,
    sni: String,
    fingerprint: String,
    alpn: String,
    allowInsecure: Boolean,
    onSniChange: (String) -> Unit,
    onFingerprintChange: (String) -> Unit,
    onAlpnChange: (String) -> Unit,
    onAllowInsecureChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // Only show when security is "tls"
    AnimatedVisibility(
        visible = streamSecurity == "tls",
        modifier = modifier
    ) {
        Column {
            // SNI input field
            OutlinedTextField(
                value = sni,
                onValueChange = onSniChange,
                label = { 
                    Text(
                        "TLS SNI (Server Name Indication)",
                        color = Color(0xFFB0B0B0)
                    ) 
                },
                placeholder = { 
                    Text(
                        "Leave empty to use server address",
                        color = Color(0xFF808080)
                    ) 
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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

            // Advanced TLS Settings Card
            Card(
                modifier = Modifier
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
                        text = "Advanced TLS Settings",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        ),
                        color = Color(0xFFE0E0E0)
                    )

                    // Fingerprint Dropdown
                    var fingerprintExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = fingerprintExpanded,
                        onExpandedChange = { fingerprintExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = fingerprint,
                            onValueChange = {},
                            readOnly = true,
                            label = { 
                                Text(
                                    "uTLS Fingerprint",
                                    color = Color(0xFFB0B0B0)
                                ) 
                            },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = fingerprintExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryEditable, true),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFFE0E0E0),
                                unfocusedTextColor = Color(0xFFE0E0E0),
                                focusedBorderColor = Color(0xFF4A9EFF),
                                unfocusedBorderColor = Color(0xFF404040),
                                focusedLabelColor = Color(0xFFB0B0B0),
                                unfocusedLabelColor = Color(0xFF808080)
                            )
                        )
                        DropdownMenu(
                            expanded = fingerprintExpanded,
                            onDismissRequest = { fingerprintExpanded = false }
                        ) {
                            listOf("chrome", "firefox", "safari", "ios", "android", "randomized", "360", "qq").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option, color = Color(0xFFE0E0E0)) },
                                    onClick = {
                                        onFingerprintChange(option)
                                        fingerprintExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // ALPN Dropdown
                    var alpnExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = alpnExpanded,
                        onExpandedChange = { alpnExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = alpn,
                            onValueChange = {},
                            readOnly = true,
                            label = { 
                                Text(
                                    "ALPN (Application-Layer Protocol Negotiation)",
                                    color = Color(0xFFB0B0B0)
                                ) 
                            },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = alpnExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryEditable, true),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFFE0E0E0),
                                unfocusedTextColor = Color(0xFFE0E0E0),
                                focusedBorderColor = Color(0xFF4A9EFF),
                                unfocusedBorderColor = Color(0xFF404040),
                                focusedLabelColor = Color(0xFFB0B0B0),
                                unfocusedLabelColor = Color(0xFF808080)
                            )
                        )
                        DropdownMenu(
                            expanded = alpnExpanded,
                            onDismissRequest = { alpnExpanded = false }
                        ) {
                            listOf("default", "h2", "http/1.1", "h2,http/1.1").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option, color = Color(0xFFE0E0E0)) },
                                    onClick = {
                                        onAlpnChange(option)
                                        alpnExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Allow Insecure Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Allow Insecure",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color(0xFFE0E0E0)
                            )
                            Text(
                                text = "Skip certificate verification",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF808080)
                            )
                        }
                        Switch(
                            checked = allowInsecure,
                            onCheckedChange = onAllowInsecureChange
                        )
                    }
                }
            }
        }
    }
}

