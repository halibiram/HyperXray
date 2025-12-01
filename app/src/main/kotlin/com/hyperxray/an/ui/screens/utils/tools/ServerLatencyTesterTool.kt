package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hyperxray.an.ui.theme.*
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class ServerTestResult(
    val name: String,
    val host: String,
    val port: Int,
    val latency: Long,
    val isReachable: Boolean,
    val rank: Int = 0
)

@Composable
fun ServerLatencyTesterTool() {
    var servers by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ServerTestResult>>(emptyList()) }
    var progress by remember { mutableStateOf(0f) }
    var currentServer by remember { mutableStateOf("") }
    var testCount by remember { mutableStateOf("3") }
    var timeout by remember { mutableStateOf("3000") }
    val scope = rememberCoroutineScope()

    val presetServers = listOf(
        "Google" to "google.com:443",
        "Cloudflare" to "1.1.1.1:443",
        "Amazon" to "amazon.com:443",
        "Microsoft" to "microsoft.com:443"
    )

    fun parseServers(): List<Triple<String, String, Int>> {
        return servers.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    val parts = line.trim().split(":")
                    val host = parts[0]
                    val port = parts.getOrNull(1)?.toIntOrNull() ?: 443
                    Triple(host, host, port)
                } catch (e: Exception) { null }
            }
    }

    suspend fun testServer(name: String, host: String, port: Int, count: Int, timeoutMs: Int): ServerTestResult {
        return withContext(Dispatchers.IO) {
            val latencies = mutableListOf<Long>()
            
            repeat(count) {
                try {
                    val start = System.currentTimeMillis()
                    val socket = Socket()
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    socket.close()
                    latencies.add(System.currentTimeMillis() - start)
                } catch (e: Exception) {
                    latencies.add(-1)
                }
                delay(100)
            }
            
            val validLatencies = latencies.filter { it > 0 }
            val avgLatency = if (validLatencies.isNotEmpty()) validLatencies.average().toLong() else -1
            
            ServerTestResult(
                name = name,
                host = host,
                port = port,
                latency = avgLatency,
                isReachable = validLatencies.isNotEmpty()
            )
        }
    }

    fun runTest() {
        scope.launch {
            isRunning = true
            results = emptyList()
            progress = 0f
            
            val serverList = parseServers()
            if (serverList.isEmpty()) {
                isRunning = false
                return@launch
            }
            
            val count = testCount.toIntOrNull() ?: 3
            val timeoutMs = timeout.toIntOrNull() ?: 3000
            val testResults = mutableListOf<ServerTestResult>()
            
            serverList.forEachIndexed { index, (name, host, port) ->
                currentServer = host
                val result = testServer(name, host, port, count, timeoutMs)
                testResults.add(result)
                
                results = testResults
                    .sortedBy { if (it.latency > 0) it.latency else Long.MAX_VALUE }
                    .mapIndexed { i, r -> r.copy(rank = i + 1) }
                
                progress = (index + 1).toFloat() / serverList.size
            }
            
            isRunning = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Control Card
        GlassCard(glowColor = FuturisticColors.NeonOrange) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NeonText(
                    text = "SERVER LATENCY TESTER",
                    color = FuturisticColors.NeonOrange,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                
                Text(
                    "Test latency to multiple VPN servers and find the fastest one",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
                
                // Quick add presets
                Text("Quick Add", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presetServers.forEach { (name, addr) ->
                        FilterChip(
                            selected = false,
                            onClick = { 
                                servers = if (servers.isBlank()) addr else "$servers\n$addr"
                            },
                            label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = FuturisticColors.NeonCyan.copy(alpha = 0.1f),
                                labelColor = FuturisticColors.NeonCyan
                            )
                        )
                    }
                }
                
                // Server list input
                OutlinedTextField(
                    value = servers,
                    onValueChange = { servers = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = { Text("Servers (one per line)") },
                    placeholder = { Text("server1.com:443\nserver2.com:8080\n192.168.1.1:1080") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FuturisticColors.NeonOrange,
                        unfocusedBorderColor = FuturisticColors.NeonOrange.copy(alpha = 0.3f)
                    )
                )
                
                // Options
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = testCount,
                        onValueChange = { testCount = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.weight(1f),
                        label = { Text("Tests per server") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FuturisticColors.NeonPurple,
                            unfocusedBorderColor = FuturisticColors.NeonPurple.copy(alpha = 0.3f)
                        )
                    )
                    OutlinedTextField(
                        value = timeout,
                        onValueChange = { timeout = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.weight(1f),
                        label = { Text("Timeout (ms)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FuturisticColors.NeonPurple,
                            unfocusedBorderColor = FuturisticColors.NeonPurple.copy(alpha = 0.3f)
                        )
                    )
                }
                
                // Progress
                if (isRunning) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Testing: $currentServer", color = FuturisticColors.NeonYellow)
                            Text("${(progress * 100).toInt()}%", color = FuturisticColors.NeonCyan)
                        }
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = FuturisticColors.NeonOrange)
                    }
                }
                
                // Start Button
                CyberButton(
                    onClick = { runTest() },
                    enabled = !isRunning && servers.isNotBlank(),
                    glowColor = FuturisticColors.NeonGreen,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = FuturisticColors.NeonGreen, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("TESTING...")
                    } else {
                        Icon(Icons.Default.NetworkCheck, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("TEST SERVERS", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        
        // Best Server Card
        if (results.isNotEmpty() && !isRunning) {
            val best = results.firstOrNull { it.isReachable }
            best?.let {
                GlassCard(glowColor = FuturisticColors.NeonGreen) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, null, tint = FuturisticColors.NeonYellow, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("FASTEST SERVER", color = FuturisticColors.NeonGreen, fontWeight = FontWeight.Bold)
                                Text(it.host, color = Color.White, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                            }
                        }
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${it.latency}ms", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold), color = FuturisticColors.NeonCyan)
                                Text("Latency", color = Color.White.copy(alpha = 0.6f))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${it.port}", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold), color = FuturisticColors.NeonPurple)
                                Text("Port", color = Color.White.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
        }
        
        // Results Card
        if (results.isNotEmpty()) {
            GlassCard(glowColor = FuturisticColors.NeonPurple) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        NeonText(text = "ALL RESULTS", color = FuturisticColors.NeonPurple, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text("${results.count { it.isReachable }}/${results.size} online", color = FuturisticColors.NeonGreen, style = MaterialTheme.typography.labelSmall)
                    }
                    
                    results.forEach { result ->
                        ServerResultItem(result)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerResultItem(result: ServerTestResult) {
    val color = when {
        !result.isReachable -> FuturisticColors.ErrorGlow
        result.rank == 1 -> FuturisticColors.NeonGreen
        result.rank <= 3 -> FuturisticColors.NeonCyan
        result.latency < 100 -> FuturisticColors.NeonYellow
        else -> FuturisticColors.NeonOrange
    }
    
    Row(
        modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.05f), RoundedCornerShape(8.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (result.isReachable) {
                Text("#${result.rank}", color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
            } else {
                Icon(Icons.Default.Close, null, tint = FuturisticColors.ErrorGlow, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column {
                Text(result.host, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("Port ${result.port}", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
            }
        }
        
        if (result.isReachable) {
            Text("${result.latency}ms", color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        } else {
            Text("OFFLINE", color = FuturisticColors.ErrorGlow, fontWeight = FontWeight.Bold)
        }
    }
}
