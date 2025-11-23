package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

@Composable
fun PortScannerTool() {
    var target by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }
    var isScanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val commonPorts = listOf(
        80 to "HTTP",
        443 to "HTTPS",
        22 to "SSH",
        21 to "FTP",
        53 to "DNS",
        8080 to "HTTP-Alt"
    )

    fun scanPorts() {
        if (target.isBlank()) return
        
        scope.launch {
            isScanning = true
            results = emptyMap()
            
            commonPorts.forEach { (port, _) ->
                val isOpen = withContext(Dispatchers.IO) {
                    try {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(target, port), 1000) // 1s timeout
                        socket.close()
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                results = results + (port to isOpen)
            }
            isScanning = false
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Port Scanner",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Target IP or Domain") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )

                Button(
                    onClick = { scanPorts() },
                    enabled = !isScanning && target.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Scan")
                    }
                }
            }

            if (results.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    commonPorts.forEach { (port, name) ->
                        val isOpen = results[port]
                        if (isOpen != null) {
                            PortRow(port, name, isOpen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PortRow(port: Int, name: String, isOpen: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$port ($name)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (isOpen) "OPEN" else "CLOSED",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = if (isOpen) Color(0xFF10B981) else Color(0xFFEF4444)
        )
    }
}
