package com.hyperxray.an.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hyperxray.an.common.TunnelMode

/**
 * MASQUE Configuration Screen
 * Allows users to configure MASQUE proxy settings when MASQUE tunnel mode is selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasqueConfigScreen(
    proxyEndpoint: String,
    onProxyEndpointChange: (String) -> Unit,
    masqueMode: String,
    onMasqueModeChange: (String) -> Unit,
    maxReconnects: Int,
    onMaxReconnectsChange: (Int) -> Unit,
    queueSize: Int,
    onQueueSizeChange: (Int) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var modeExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF000011))
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "MASQUE Configuration",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Proxy Endpoint
        OutlinedTextField(
            value = proxyEndpoint,
            onValueChange = onProxyEndpointChange,
            label = { Text("Proxy Endpoint") },
            placeholder = { Text("masque.example.com:443") },
            leadingIcon = {
                Icon(Icons.Default.Cloud, contentDescription = null, tint = Color(0xFF00F5FF))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF00F5FF),
                unfocusedBorderColor = Color.Gray,
                focusedLabelColor = Color(0xFF00F5FF),
                unfocusedLabelColor = Color.Gray
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        // MASQUE Mode Selector
        ExposedDropdownMenuBox(
            expanded = modeExpanded,
            onExpandedChange = { modeExpanded = it }
        ) {
            OutlinedTextField(
                value = when (masqueMode) {
                    "connect-ip" -> "CONNECT-IP (Full IP Tunneling)"
                    "connect-udp" -> "CONNECT-UDP (UDP Only)"
                    else -> "CONNECT-IP (Full IP Tunneling)"
                },
                onValueChange = {},
                readOnly = true,
                label = { Text("Encapsulation Mode") },
                leadingIcon = {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = Color(0xFF00F5FF))
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00F5FF),
                    unfocusedBorderColor = Color.Gray,
                    focusedLabelColor = Color(0xFF00F5FF),
                    unfocusedLabelColor = Color.Gray
                )
            )

            ExposedDropdownMenu(
                expanded = modeExpanded,
                onDismissRequest = { modeExpanded = false },
                modifier = Modifier.background(Color(0xFF1A1A2E))
            ) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("CONNECT-IP", color = Color.White)
                            Text(
                                "Full IP tunneling (Layer 3)",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    onClick = {
                        onMasqueModeChange("connect-ip")
                        modeExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("CONNECT-UDP", color = Color.White)
                            Text(
                                "UDP only (Layer 4)",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    onClick = {
                        onMasqueModeChange("connect-udp")
                        modeExpanded = false
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Advanced Settings Section
        Text(
            text = "Advanced Settings",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF8B5CF6),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Max Reconnects
        OutlinedTextField(
            value = maxReconnects.toString(),
            onValueChange = { it.toIntOrNull()?.let(onMaxReconnectsChange) },
            label = { Text("Max Reconnection Attempts") },
            leadingIcon = {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF8B5CF6))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF8B5CF6),
                unfocusedBorderColor = Color.Gray,
                focusedLabelColor = Color(0xFF8B5CF6),
                unfocusedLabelColor = Color.Gray
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Queue Size
        OutlinedTextField(
            value = queueSize.toString(),
            onValueChange = { it.toIntOrNull()?.let(onQueueSizeChange) },
            label = { Text("Packet Queue Size") },
            leadingIcon = {
                Icon(Icons.Default.Storage, contentDescription = null, tint = Color(0xFF8B5CF6))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF8B5CF6),
                unfocusedBorderColor = Color.Gray,
                focusedLabelColor = Color(0xFF8B5CF6),
                unfocusedLabelColor = Color.Gray
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = Color.Gray)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00F5FF)
                )
            ) {
                Text("Save", color = Color.Black)
            }
        }
    }
}
