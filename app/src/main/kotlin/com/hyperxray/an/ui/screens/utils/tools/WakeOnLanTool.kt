package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

@Composable
fun WakeOnLanTool() {
    var macAddress by remember { mutableStateOf("") }
    var broadcastIp by remember { mutableStateOf("255.255.255.255") }
    var port by remember { mutableStateOf("9") }
    var status by remember { mutableStateOf<String?>(null) }
    var isSending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun sendMagicPacket() {
        if (macAddress.isBlank()) return
        scope.launch {
            isSending = true
            status = null
            try {
                withContext(Dispatchers.IO) {
                    val macBytes = getMacBytes(macAddress)
                    val bytes = ByteArray(6 + 16 * macBytes.size)
                    for (i in 0..5) {
                        bytes[i] = 0xff.toByte()
                    }
                    for (i in 6 until bytes.size) {
                        bytes[i] = macBytes[i % macBytes.size]
                    }
                    
                    val address = InetAddress.getByName(broadcastIp)
                    val packet = DatagramPacket(bytes, bytes.size, address, port.toInt())
                    val socket = DatagramSocket()
                    socket.send(packet)
                    socket.close()
                }
                status = "Magic Packet sent successfully!"
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            } finally {
                isSending = false
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
            Text(
                text = "Wake on LAN (WoL)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            OutlinedTextField(
                value = macAddress,
                onValueChange = { macAddress = it },
                label = { Text("MAC Address") },
                placeholder = { Text("00:11:22:33:44:55") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = broadcastIp,
                    onValueChange = { broadcastIp = it },
                    label = { Text("Broadcast IP") },
                    modifier = Modifier.weight(0.7f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    modifier = Modifier.weight(0.3f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            Button(
                onClick = { sendMagicPacket() },
                enabled = !isSending && macAddress.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send Magic Packet")
                }
            }

            status?.let {
                Text(
                    text = it,
                    color = if (it.startsWith("Error")) MaterialTheme.colorScheme.error else Color(0xFF10B981),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Throws(IllegalArgumentException::class)
private fun getMacBytes(macStr: String): ByteArray {
    val bytes = ByteArray(6)
    val hex = macStr.split(":", "-")
    if (hex.size != 6) {
        throw IllegalArgumentException("Invalid MAC address.")
    }
    try {
        for (i in 0..5) {
            bytes[i] = Integer.parseInt(hex[i], 16).toByte()
        }
    } catch (e: NumberFormatException) {
        throw IllegalArgumentException("Invalid hex digit in MAC address.")
    }
    return bytes
}
