package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

@Composable
fun LanScannerTool() {
    var devices by remember { mutableStateOf<List<LanDevice>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun startScan() {
        scope.launch {
            isScanning = true
            devices = emptyList()
            progress = 0f
            statusMessage = "Starting scan..."

            withContext(Dispatchers.IO) {
                try {
                    val myIp = getLocalIpAddress()
                    if (myIp == null) {
                        statusMessage = "Could not determine local IP."
                        return@withContext
                    }

                    val subnet = myIp.substringBeforeLast(".")
                    val foundDevices = Collections.synchronizedList(mutableListOf<LanDevice>())
                    
                    // Scan 1 to 254
                    val jobs = (1..254).map { i ->
                        async {
                            val host = "$subnet.$i"
                            try {
                                val inetAddress = InetAddress.getByName(host)
                                var isReachable = false
                                
                                // Try standard reachability (ICMP/Echo) - often blocked on Android
                                if (inetAddress.isReachable(200)) {
                                    isReachable = true
                                } else {
                                    // Fallback: TCP Connect to common ports
                                    val ports = listOf(80, 443, 8080, 22, 53, 445)
                                    for (port in ports) {
                                        try {
                                            val socket = Socket()
                                            socket.connect(InetSocketAddress(host, port), 100)
                                            socket.close()
                                            isReachable = true
                                            break
                                        } catch (e: Exception) {
                                            // Ignore
                                        }
                                    }
                                }

                                if (isReachable) {
                                    val hostname = inetAddress.hostName
                                    val display = if (hostname == host) "Unknown Device" else hostname
                                    foundDevices.add(LanDevice(host, display, true))
                                }
                            } catch (e: Exception) {
                                // Ignore
                            }
                            // Update progress roughly
                            progress += (1f / 254f)
                        }
                    }
                    jobs.awaitAll()
                    
                    // Sort by IP
                    withContext(Dispatchers.Main) {
                        devices = foundDevices.sortedBy { 
                            it.ip.substringAfterLast(".").toIntOrNull() ?: 0 
                        }
                        statusMessage = "Scan complete. Found ${devices.size} devices."
                    }
                } catch (e: Exception) {
                    statusMessage = "Error: ${e.message}"
                } finally {
                    isScanning = false
                    progress = 1f
                }
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LAN Scanner",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                IconButton(onClick = { startScan() }, enabled = !isScanning) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Scan", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (isScanning) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            
            if (statusMessage.isNotEmpty()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (devices.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    devices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = device.ip,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Text(
                                    text = device.hostname,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(0xFF10B981), RoundedCornerShape(4.dp))
                            )
                        }
                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.1f))
                    }
                }
            } else if (!isScanning && statusMessage.startsWith("Scan complete")) {
                Text("No devices found (or blocked by Android restrictions).", color = Color.Gray)
            }
        }
    }
}

data class LanDevice(val ip: String, val hostname: String, val isOnline: Boolean)

private fun getLocalIpAddress(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val intf = interfaces.nextElement()
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (!addr.isLoopbackAddress && addr.hostAddress.indexOf(':') < 0) {
                    return addr.hostAddress
                }
            }
        }
    } catch (ex: Exception) { }
    return null
}
