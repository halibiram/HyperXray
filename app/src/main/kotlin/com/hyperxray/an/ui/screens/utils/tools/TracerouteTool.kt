package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

@Composable
fun TracerouteTool() {
    var target by remember { mutableStateOf("") }
    var hops by remember { mutableStateOf<List<Hop>>(emptyList()) }
    var isRunning by remember { mutableStateOf(false) }
    var currentTtl by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun startTraceroute() {
        if (target.isBlank()) return
        scope.launch {
            isRunning = true
            hops = emptyList()
            currentTtl = 1
            
            withContext(Dispatchers.IO) {
                val maxHops = 30
                for (ttl in 1..maxHops) {
                    if (!isRunning) break
                    currentTtl = ttl
                    
                    val hop = pingHop(target, ttl)
                    withContext(Dispatchers.Main) {
                        hops = hops + hop
                    }
                    
                    if (hop.ip == target || hop.ip.contains(target)) {
                        break
                    }
                }
            }
            isRunning = false
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
                text = "Traceroute",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Target Host") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = { startTraceroute() },
                    enabled = !isRunning && target.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    if (isRunning) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    else Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                }
            }

            if (hops.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    hops.forEach { hop ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${hop.ttl}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(30.dp)
                            )
                            Text(
                                text = hop.ip,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = hop.rtt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.1f))
                    }
                }
            }
        }
    }
}

data class Hop(val ttl: Int, val ip: String, val rtt: String)

private fun pingHop(target: String, ttl: Int): Hop {
    return try {
        // ping -c 1 -t <ttl> -W 1 <target>
        // -c 1: count 1
        // -t: TTL
        // -W 1: timeout 1 second
        val process = ProcessBuilder("ping", "-c", "1", "-t", "$ttl", "-W", "1", target).start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()
        process.waitFor()
        
        // Parse output
        // Example success: "64 bytes from 1.1.1.1: icmp_seq=1 ttl=58 time=14.2 ms"
        // Example hop: "From 192.168.1.1: icmp_seq=1 Time to live exceeded"
        
        if (output.contains("Time to live exceeded")) {
            val ip = output.substringAfter("From ").substringBefore(":")
            Hop(ttl, ip, "*")
        } else if (output.contains("time=")) {
            val ip = output.substringAfter("from ").substringBefore(":")
            val time = output.substringAfter("time=").substringBefore(" ms")
            Hop(ttl, ip, "${time}ms")
        } else {
            Hop(ttl, "*", "*")
        }
    } catch (e: Exception) {
        Hop(ttl, "*", "Error")
    }
}
