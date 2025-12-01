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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

data class ProtocolTestResult(
    val protocol: String,
    val port: Int,
    val description: String,
    val status: TestStatus,
    val responseTime: Long = 0,
    val details: String = ""
)

enum class TestStatus { PENDING, TESTING, OPEN, BLOCKED, ERROR }

// VPN-related protocols and ports
private val vpnProtocols = listOf(
    ProtocolTest("OpenVPN UDP", 1194, "UDP", "Standard OpenVPN"),
    ProtocolTest("OpenVPN TCP", 443, "TCP", "OpenVPN over HTTPS"),
    ProtocolTest("WireGuard", 51820, "UDP", "WireGuard VPN"),
    ProtocolTest("IKEv2/IPSec", 500, "UDP", "IKEv2 VPN"),
    ProtocolTest("IPSec NAT-T", 4500, "UDP", "IPSec NAT Traversal"),
    ProtocolTest("L2TP", 1701, "UDP", "L2TP VPN"),
    ProtocolTest("SSTP", 443, "TCP", "SSTP VPN (Microsoft)"),
    ProtocolTest("SSH", 22, "TCP", "SSH Tunnel"),
    ProtocolTest("Shadowsocks", 8388, "TCP", "Shadowsocks Proxy"),
    ProtocolTest("SOCKS5", 1080, "TCP", "SOCKS5 Proxy"),
    ProtocolTest("HTTP Proxy", 8080, "TCP", "HTTP Proxy"),
    ProtocolTest("HTTPS", 443, "TCP", "Secure Web"),
    ProtocolTest("DNS", 53, "UDP", "Standard DNS"),
    ProtocolTest("DoT", 853, "TCP", "DNS over TLS"),
    ProtocolTest("QUIC", 443, "UDP", "HTTP/3 QUIC")
)

data class ProtocolTest(
    val name: String,
    val port: Int,
    val type: String,
    val description: String
)

@Composable
fun FirewallTesterTool() {
    var isRunning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ProtocolTestResult>>(emptyList()) }
    var progress by remember { mutableStateOf(0f) }
    var currentTest by remember { mutableStateOf("") }
    var testTarget by remember { mutableStateOf("8.8.8.8") }
    var selectedCategory by remember { mutableStateOf("all") }
    val scope = rememberCoroutineScope()

    val categories = mapOf(
        "all" to "All Protocols",
        "vpn" to "VPN Protocols",
        "proxy" to "Proxy Ports",
        "dns" to "DNS Ports"
    )

    fun getFilteredProtocols(): List<ProtocolTest> {
        return when (selectedCategory) {
            "vpn" -> vpnProtocols.filter { it.name.contains("VPN") || it.name in listOf("OpenVPN UDP", "OpenVPN TCP", "WireGuard", "IKEv2/IPSec", "IPSec NAT-T", "L2TP", "SSTP") }
            "proxy" -> vpnProtocols.filter { it.name.contains("Proxy") || it.name in listOf("SSH", "Shadowsocks", "SOCKS5") }
            "dns" -> vpnProtocols.filter { it.name.contains("DNS") || it.name == "DoT" }
            else -> vpnProtocols
        }
    }

    suspend fun testTcpPort(host: String, port: Int, timeout: Int = 3000): Pair<Boolean, Long> {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), timeout)
                socket.close()
                Pair(true, System.currentTimeMillis() - startTime)
            } catch (e: Exception) {
                Pair(false, -1L)
            }
        }
    }

    suspend fun testUdpPort(host: String, port: Int, timeout: Int = 3000): Pair<Boolean, Long> {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val socket = DatagramSocket()
                socket.soTimeout = timeout
                
                val address = InetAddress.getByName(host)
                val sendData = ByteArray(32) { 0 }
                val sendPacket = DatagramPacket(sendData, sendData.size, address, port)
                socket.send(sendPacket)
                
                // For UDP, we can't really know if it's blocked without a response
                // We'll consider it "open" if we can send without error
                socket.close()
                Pair(true, System.currentTimeMillis() - startTime)
            } catch (e: Exception) {
                Pair(false, -1L)
            }
        }
    }

    suspend fun testSslPort(host: String, port: Int, timeout: Int = 5000): Pair<Boolean, Long> {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val socket = factory.createSocket()
                socket.connect(InetSocketAddress(host, port), timeout)
                socket.close()
                Pair(true, System.currentTimeMillis() - startTime)
            } catch (e: Exception) {
                Pair(false, -1L)
            }
        }
    }

    fun runTests() {
        scope.launch {
            isRunning = true
            results = emptyList()
            progress = 0f
            
            val protocols = getFilteredProtocols()
            val testResults = mutableListOf<ProtocolTestResult>()
            
            protocols.forEachIndexed { index, protocol ->
                currentTest = protocol.name
                
                val (isOpen, responseTime) = when (protocol.type) {
                    "TCP" -> if (protocol.port == 443 && protocol.name.contains("SSL")) {
                        testSslPort(testTarget, protocol.port)
                    } else {
                        testTcpPort(testTarget, protocol.port)
                    }
                    "UDP" -> testUdpPort(testTarget, protocol.port)
                    else -> testTcpPort(testTarget, protocol.port)
                }
                
                testResults.add(
                    ProtocolTestResult(
                        protocol = protocol.name,
                        port = protocol.port,
                        description = protocol.description,
                        status = if (isOpen) TestStatus.OPEN else TestStatus.BLOCKED,
                        responseTime = responseTime,
                        details = "${protocol.type}/${protocol.port}"
                    )
                )
                
                results = testResults.toList()
                progress = (index + 1).toFloat() / protocols.size
                delay(100)
            }
            
            isRunning = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Control Card
        GlassCard(glowColor = FuturisticColors.NeonMagenta) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NeonText(
                    text = "FIREWALL TESTER",
                    color = FuturisticColors.NeonMagenta,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                
                Text(
                    "Test which VPN protocols and ports are accessible from your network. Useful for detecting firewall restrictions.",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
                
                // Target Input
                OutlinedTextField(
                    value = testTarget,
                    onValueChange = { testTarget = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Test Target") },
                    placeholder = { Text("8.8.8.8") },
                    leadingIcon = { Icon(Icons.Default.Dns, null, tint = FuturisticColors.NeonMagenta) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FuturisticColors.NeonMagenta,
                        unfocusedBorderColor = FuturisticColors.NeonMagenta.copy(alpha = 0.3f)
                    )
                )
                
                // Category Filter
                Text("Category", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { (key, label) ->
                        FilterChip(
                            selected = selectedCategory == key,
                            onClick = { selectedCategory = key },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = FuturisticColors.NeonMagenta.copy(alpha = 0.2f),
                                selectedLabelColor = FuturisticColors.NeonMagenta
                            )
                        )
                    }
                }
                
                // Progress
                if (isRunning) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Testing: $currentTest", color = FuturisticColors.NeonYellow, style = MaterialTheme.typography.bodySmall)
                            Text("${(progress * 100).toInt()}%", color = FuturisticColors.NeonCyan)
                        }
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = FuturisticColors.NeonMagenta)
                    }
                }
                
                // Start Button
                CyberButton(
                    onClick = { runTests() },
                    enabled = !isRunning,
                    glowColor = FuturisticColors.NeonGreen,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = FuturisticColors.NeonGreen, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("TESTING...")
                    } else {
                        Icon(Icons.Default.Shield, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("RUN FIREWALL TEST", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        
        // Summary Card
        if (results.isNotEmpty()) {
            val openCount = results.count { it.status == TestStatus.OPEN }
            val blockedCount = results.count { it.status == TestStatus.BLOCKED }
            
            GlassCard(glowColor = if (blockedCount > openCount) FuturisticColors.NeonOrange else FuturisticColors.NeonGreen) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NeonText(text = "SUMMARY", color = FuturisticColors.NeonCyan, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$openCount", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = FuturisticColors.NeonGreen)
                            Text("Open", color = Color.White.copy(alpha = 0.6f))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$blockedCount", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = FuturisticColors.ErrorGlow)
                            Text("Blocked", color = Color.White.copy(alpha = 0.6f))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${results.size}", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = FuturisticColors.NeonCyan)
                            Text("Total", color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                    
                    // Recommendation
                    val recommendation = when {
                        blockedCount == 0 -> "✅ No restrictions detected"
                        openCount == 0 -> "🚫 Heavy firewall - try obfuscation"
                        results.any { it.protocol == "OpenVPN TCP" && it.status == TestStatus.OPEN } -> "💡 Use OpenVPN over TCP/443"
                        results.any { it.protocol == "WireGuard" && it.status == TestStatus.OPEN } -> "💡 WireGuard is available"
                        else -> "⚠️ Limited protocols available"
                    }
                    
                    Surface(shape = RoundedCornerShape(8.dp), color = FuturisticColors.NeonYellow.copy(alpha = 0.1f), modifier = Modifier.fillMaxWidth()) {
                        Text(recommendation, modifier = Modifier.padding(12.dp), color = FuturisticColors.NeonYellow)
                    }
                }
            }
        }
        
        // Results Card
        if (results.isNotEmpty()) {
            GlassCard(glowColor = FuturisticColors.NeonPurple) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonText(text = "RESULTS", color = FuturisticColors.NeonPurple, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    
                    results.forEach { result ->
                        ProtocolResultItem(result)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtocolResultItem(result: ProtocolTestResult) {
    val color = when (result.status) {
        TestStatus.OPEN -> FuturisticColors.NeonGreen
        TestStatus.BLOCKED -> FuturisticColors.ErrorGlow
        else -> FuturisticColors.NeonYellow
    }
    
    Row(
        modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.05f), RoundedCornerShape(8.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (result.status == TestStatus.OPEN) Icons.Default.CheckCircle else Icons.Default.Block,
                null, tint = color, modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(result.protocol, color = Color.White, fontWeight = FontWeight.Bold)
                Text(result.details, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
            }
        }
        
        Column(horizontalAlignment = Alignment.End) {
            Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.2f)) {
                Text(
                    if (result.status == TestStatus.OPEN) "OPEN" else "BLOCKED",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold
                )
            }
            if (result.responseTime > 0) {
                Text("${result.responseTime}ms", color = FuturisticColors.NeonCyan, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
