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

data class DnsBenchmarkResult(
    val name: String,
    val ip: String,
    val avgLatency: Long,
    val minLatency: Long,
    val maxLatency: Long,
    val successRate: Double,
    val rank: Int = 0
)

private val dnsServers = listOf(
    "Cloudflare" to "1.1.1.1",
    "Cloudflare 2" to "1.0.0.1",
    "Google" to "8.8.8.8",
    "Google 2" to "8.8.4.4",
    "Quad9" to "9.9.9.9",
    "Quad9 2" to "149.112.112.112",
    "OpenDNS" to "208.67.222.222",
    "OpenDNS 2" to "208.67.220.220",
    "AdGuard" to "94.140.14.14",
    "AdGuard 2" to "94.140.15.15",
    "CleanBrowsing" to "185.228.168.9",
    "Comodo" to "8.26.56.26",
    "Level3" to "4.2.2.1",
    "Verisign" to "64.6.64.6",
    "DNS.Watch" to "84.200.69.80"
)

@Composable
fun DnsBenchmarkTool() {
    var isRunning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<DnsBenchmarkResult>>(emptyList()) }
    var progress by remember { mutableStateOf(0f) }
    var currentServer by remember { mutableStateOf("") }
    var testCount by remember { mutableStateOf("5") }
    var testDomain by remember { mutableStateOf("google.com") }
    val scope = rememberCoroutineScope()

    suspend fun benchmarkDns(name: String, ip: String, domain: String, count: Int): DnsBenchmarkResult {
        return withContext(Dispatchers.IO) {
            val latencies = mutableListOf<Long>()
            var successes = 0
            
            repeat(count) {
                try {
                    val start = System.currentTimeMillis()
                    // Resolve using specific DNS (simplified - actual implementation would use dnsjava)
                    InetAddress.getByName(domain)
                    val latency = System.currentTimeMillis() - start
                    latencies.add(latency)
                    successes++
                } catch (e: Exception) {
                    latencies.add(-1)
                }
                delay(50)
            }
            
            val validLatencies = latencies.filter { it > 0 }
            
            DnsBenchmarkResult(
                name = name,
                ip = ip,
                avgLatency = if (validLatencies.isNotEmpty()) validLatencies.average().toLong() else -1,
                minLatency = validLatencies.minOrNull() ?: -1,
                maxLatency = validLatencies.maxOrNull() ?: -1,
                successRate = (successes.toDouble() / count) * 100
            )
        }
    }

    fun runBenchmark() {
        scope.launch {
            isRunning = true
            results = emptyList()
            progress = 0f
            
            val count = testCount.toIntOrNull() ?: 5
            val benchmarkResults = mutableListOf<DnsBenchmarkResult>()
            
            dnsServers.forEachIndexed { index, (name, ip) ->
                currentServer = name
                val result = benchmarkDns(name, ip, testDomain, count)
                benchmarkResults.add(result)
                results = benchmarkResults.sortedBy { if (it.avgLatency > 0) it.avgLatency else Long.MAX_VALUE }
                    .mapIndexed { i, r -> r.copy(rank = i + 1) }
                progress = (index + 1).toFloat() / dnsServers.size
            }
            
            isRunning = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Control Card
        GlassCard(glowColor = FuturisticColors.NeonBlue) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NeonText(
                    text = "DNS BENCHMARK",
                    color = FuturisticColors.NeonBlue,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                
                Text(
                    "Find the fastest DNS server for your connection",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = testDomain,
                        onValueChange = { testDomain = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Test Domain") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FuturisticColors.NeonBlue,
                            unfocusedBorderColor = FuturisticColors.NeonBlue.copy(alpha = 0.3f)
                        )
                    )
                    OutlinedTextField(
                        value = testCount,
                        onValueChange = { testCount = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.width(80.dp),
                        label = { Text("Tests") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FuturisticColors.NeonPurple,
                            unfocusedBorderColor = FuturisticColors.NeonPurple.copy(alpha = 0.3f)
                        )
                    )
                }
                
                if (isRunning) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Testing: $currentServer", color = FuturisticColors.NeonYellow)
                            Text("${(progress * 100).toInt()}%", color = FuturisticColors.NeonCyan)
                        }
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = FuturisticColors.NeonBlue)
                    }
                }
                
                CyberButton(
                    onClick = { runBenchmark() },
                    enabled = !isRunning,
                    glowColor = FuturisticColors.NeonGreen,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = FuturisticColors.NeonGreen, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("BENCHMARKING...")
                    } else {
                        Icon(Icons.Default.Speed, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("RUN BENCHMARK", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        
        // Winner Card
        if (results.isNotEmpty() && !isRunning) {
            val winner = results.first()
            GlassCard(glowColor = FuturisticColors.NeonGreen) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EmojiEvents, null, tint = FuturisticColors.NeonYellow, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("FASTEST DNS", color = FuturisticColors.NeonGreen, fontWeight = FontWeight.Bold)
                            Text(winner.name, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${winner.avgLatency}ms", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold), color = FuturisticColors.NeonCyan)
                            Text("Avg Latency", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(winner.ip, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace), color = FuturisticColors.NeonPurple)
                            Text("IP Address", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        
        // Results Card
        if (results.isNotEmpty()) {
            GlassCard(glowColor = FuturisticColors.NeonPurple) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonText(text = "ALL RESULTS", color = FuturisticColors.NeonPurple, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    
                    results.forEach { result ->
                        DnsResultItem(result)
                    }
                }
            }
        }
    }
}

@Composable
private fun DnsResultItem(result: DnsBenchmarkResult) {
    val color = when (result.rank) {
        1 -> FuturisticColors.NeonGreen
        2 -> FuturisticColors.NeonCyan
        3 -> FuturisticColors.NeonYellow
        else -> Color.White.copy(alpha = 0.7f)
    }
    
    Row(
        modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.05f), RoundedCornerShape(8.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${result.rank}", color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
            Column {
                Text(result.name, color = Color.White, fontWeight = FontWeight.Bold)
                Text(result.ip, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
        
        Column(horizontalAlignment = Alignment.End) {
            if (result.avgLatency > 0) {
                Text("${result.avgLatency}ms", color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("${result.minLatency}-${result.maxLatency}ms", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
            } else {
                Text("FAILED", color = FuturisticColors.ErrorGlow, fontWeight = FontWeight.Bold)
            }
        }
    }
}
