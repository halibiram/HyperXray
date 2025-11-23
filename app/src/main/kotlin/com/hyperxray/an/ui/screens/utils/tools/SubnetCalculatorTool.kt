package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.net.InetAddress
import kotlin.math.pow

@Composable
fun SubnetCalculatorTool() {
    var ipAddress by remember { mutableStateOf("") }
    var cidr by remember { mutableStateOf("24") }
    var result by remember { mutableStateOf<SubnetResult?>(null) }

    fun calculate() {
        try {
            val ipParts = ipAddress.split(".").map { it.toInt() }
            if (ipParts.size != 4) return
            
            val cidrVal = cidr.toIntOrNull() ?: return
            if (cidrVal !in 0..32) return

            val ipLong = (ipParts[0].toLong() shl 24) + (ipParts[1] shl 16) + (ipParts[2] shl 8) + ipParts[3]
            val maskLong = (0xFFFFFFFF shl (32 - cidrVal)) and 0xFFFFFFFF
            
            val networkLong = ipLong and maskLong
            val broadcastLong = networkLong or (maskLong.inv() and 0xFFFFFFFF)
            
            val firstIpLong = networkLong + 1
            val lastIpLong = broadcastLong - 1
            
            val totalHosts = if (cidrVal == 32) 1L else if (cidrVal == 31) 2L else (2.0.pow(32 - cidrVal).toLong() - 2)

            result = SubnetResult(
                network = longToIp(networkLong),
                broadcast = longToIp(broadcastLong),
                firstHost = longToIp(firstIpLong),
                lastHost = longToIp(lastIpLong),
                totalHosts = totalHosts,
                mask = longToIp(maskLong)
            )
        } catch (e: Exception) {
            result = null
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
            Text(
                text = "Subnet Calculator",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { 
                        ipAddress = it
                        calculate()
                    },
                    label = { Text("IP Address") },
                    modifier = Modifier.weight(0.7f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = cidr,
                    onValueChange = { 
                        cidr = it
                        calculate()
                    },
                    label = { Text("CIDR") },
                    modifier = Modifier.weight(0.3f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            result?.let {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow("Network", it.network)
                    InfoRow("Netmask", it.mask)
                    InfoRow("First Host", it.firstHost)
                    InfoRow("Last Host", it.lastHost)
                    InfoRow("Broadcast", it.broadcast)
                    InfoRow("Hosts", it.totalHosts.toString())
                }
            }
        }
    }
}

private fun longToIp(long: Long): String {
    return "${(long shr 24) and 0xFF}.${(long shr 16) and 0xFF}.${(long shr 8) and 0xFF}.${long and 0xFF}"
}

data class SubnetResult(
    val network: String,
    val broadcast: String,
    val firstHost: String,
    val lastHost: String,
    val totalHosts: Long,
    val mask: String
)

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
    }
}
