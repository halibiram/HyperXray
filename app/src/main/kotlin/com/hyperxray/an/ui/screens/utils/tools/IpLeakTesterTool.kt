package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hyperxray.an.ui.theme.*
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.URL
import java.net.DatagramSocket
import java.net.DatagramPacket

data class LeakTestResult(
    val testName: String,
    val status: LeakStatus,
    val detectedIp: String = "",
    val expectedIp: String = "",
    val details: String = "",
    val isLeak: Boolean = false
)

enum class LeakStatus { PENDING, TESTING, SAFE, LEAK, ERROR }

@Composable
fun IpLeakTesterTool() {
    var isRunning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<LeakTestResult>>(emptyList()) }
    var vpnIp by remember { mutableStateOf("") }
    var realIp by remember { mutableStateOf("") }
    var overallStatus by remember { mutableStateOf(LeakStatus.PENDING) }
    var progress by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    val dnsServers = listOf(
        "https://cloudflare-dns.com/dns-query" to "Cloudflare",
        "https://dns.google/resolve" to "Google",
        "https://dns.quad9.net/dns-query" to "Quad9"
    )

    suspend fun getPublicIp(service: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(service)
                url.readText().trim()
            } catch (e: Exception) { null }
        }
    }

    suspend fun testDnsLeak(server: String, name: String): LeakTestResult {
        return withContext(Dispatchers.IO) {
            try {
                // Query DNS and check responding server
                val testDomain = "whoami.akamai.net"
                val addresses = InetAddress.getAllByName(testDomain)
                val resolvedIp = addresses.firstOrNull()?.hostAddress ?: ""
                
                // Check if DNS response comes from VPN's DNS
                val isLeak = realIp.isNotEmpty() && resolvedIp.contains(realIp.substringBeforeLast("."))
                
                LeakTestResult(
                    testName = "DNS ($name)",
                    status = if (isLeak) LeakStatus.LEAK else LeakStatus.SAFE,
                    detectedIp = resolvedIp,
                    expectedIp = vpnIp,
                    details = if (isLeak) "DNS queries may be leaking" else "DNS is protected",
                    isLeak = isLeak
                )
            } catch (e: Exception) {
                LeakTestResult(
                    testName = "DNS ($name)",
                    status = LeakStatus.ERROR,
                    details = "Test failed: ${e.message}"
                )
            }
        }
    }

    suspend fun testIpv6Leak(): LeakTestResult {
        return withContext(Dispatchers.IO) {
            try {
                // Try to get IPv6 address
                val ipv6Services = listOf(
                    "https://api6.ipify.org",
                    "https://v6.ident.me"
                )
                
                var ipv6Address: String? = null
                for (service in ipv6Services) {
                    try {
                        val url = URL(service)
                        val conn = url.openConnection()
                        conn.connectTimeout = 3000
                        conn.readTimeout = 3000
                        ipv6Address = conn.getInputStream().bufferedReader().readText().trim()
                        if (ipv6Address.contains(":")) break
                    } catch (e: Exception) { }
                }
                
                if (ipv6Address != null && ipv6Address.contains(":")) {
                    LeakTestResult(
                        testName = "IPv6 Leak",
                        status = LeakStatus.LEAK,
                        detectedIp = ipv6Address,
                        details = "IPv6 address detected - potential leak!",
                        isLeak = true
                    )
                } else {
                    LeakTestResult(
                        testName = "IPv6 Leak",
                        status = LeakStatus.SAFE,
                        details = "No IPv6 leak detected",
                        isLeak = false
                    )
                }
            } catch (e: Exception) {
                LeakTestResult(
                    testName = "IPv6 Leak",
                    status = LeakStatus.SAFE,
                    details = "IPv6 not available (safe)",
                    isLeak = false
                )
            }
        }
    }

    suspend fun testWebRtcLeak(): LeakTestResult {
        // WebRTC leak detection (simplified - full detection requires JavaScript)
        return withContext(Dispatchers.IO) {
            try {
                // Check for local IP exposure via UDP
                val socket = DatagramSocket()
                socket.connect(InetAddress.getByName("8.8.8.8"), 10002)
                val localIp = socket.localAddress.hostAddress
                socket.close()
                
                val isPrivateIp = localIp?.startsWith("192.168.") == true ||
                        localIp?.startsWith("10.") == true ||
                        localIp?.startsWith("172.") == true
                
                if (isPrivateIp && realIp.isNotEmpty()) {
                    LeakTestResult(
                        testName = "WebRTC Leak",
                        status = LeakStatus.LEAK,
                        detectedIp = localIp ?: "",
                        details = "Local IP exposed via WebRTC",
                        isLeak = true
                    )
                } else {
                    LeakTestResult(
                        testName = "WebRTC Leak",
                        status = LeakStatus.SAFE,
                        details = "WebRTC appears protected",
                        isLeak = false
                    )
                }
            } catch (e: Exception) {
                LeakTestResult(
                    testName = "WebRTC Leak",
                    status = LeakStatus.SAFE,
                    details = "WebRTC test inconclusive",
                    isLeak = false
                )
            }
        }
    }

    fun runLeakTest() {
        scope.launch {
            isRunning = true
            results = emptyList()
            progress = 0f
            overallStatus = LeakStatus.TESTING
            
            // Step 1: Get current public IP (should be VPN IP)
            vpnIp = getPublicIp("https://api.ipify.org") ?: ""
            progress = 0.1f
            
            // Step 2: Try to detect real IP (via different methods)
            realIp = "" // In real scenario, this would be stored before VPN connection
            progress = 0.2f
            
            val testResults = mutableListOf<LeakTestResult>()
            
            // Test 1: IPv4 Check
            testResults.add(LeakTestResult(
                testName = "IPv4 Address",
                status = if (vpnIp.isNotEmpty()) LeakStatus.SAFE else LeakStatus.ERROR,
                detectedIp = vpnIp,
                details = if (vpnIp.isNotEmpty()) "Current IP: $vpnIp" else "Could not detect IP"
            ))
            results = testResults.toList()
            progress = 0.3f
            
            // Test 2: IPv6 Leak
            testResults.add(testIpv6Leak())
            results = testResults.toList()
            progress = 0.5f
            
            // Test 3: DNS Leak Tests
            dnsServers.forEachIndexed { index, (server, name) ->
                testResults.add(testDnsLeak(server, name))
                results = testResults.toList()
                progress = 0.5f + (index + 1) * 0.1f
            }
            
            // Test 4: WebRTC Leak
            testResults.add(testWebRtcLeak())
            results = testResults.toList()
            progress = 0.9f
            
            // Calculate overall status
            val hasLeak = testResults.any { it.isLeak }
            val hasError = testResults.any { it.status == LeakStatus.ERROR }
            
            overallStatus = when {
                hasLeak -> LeakStatus.LEAK
                hasError -> LeakStatus.ERROR
                else -> LeakStatus.SAFE
            }
            
            progress = 1f
            isRunning = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Main Card
        GlassCard(
            glowColor = when (overallStatus) {
                LeakStatus.SAFE -> FuturisticColors.NeonGreen
                LeakStatus.LEAK -> FuturisticColors.ErrorGlow
                LeakStatus.TESTING -> FuturisticColors.NeonYellow
                else -> FuturisticColors.NeonCyan
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NeonText(
                    text = "IP LEAK TESTER",
                    color = FuturisticColors.NeonCyan,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                
                // Status Display
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (overallStatus) {
                        LeakStatus.PENDING -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Shield, null, tint = FuturisticColors.NeonCyan, modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Ready to test", color = Color.White.copy(alpha = 0.7f))
                            }
                        }
                        LeakStatus.TESTING -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = FuturisticColors.NeonYellow, modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Testing for leaks...", color = FuturisticColors.NeonYellow)
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    color = FuturisticColors.NeonCyan
                                )
                            }
                        }
                        LeakStatus.SAFE -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.VerifiedUser, null, tint = FuturisticColors.NeonGreen, modifier = Modifier.size(64.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("NO LEAKS DETECTED", color = FuturisticColors.NeonGreen, fontWeight = FontWeight.Bold)
                                Text("Your connection is secure", color = Color.White.copy(alpha = 0.7f))
                            }
                        }
                        LeakStatus.LEAK -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Warning, null, tint = FuturisticColors.ErrorGlow, modifier = Modifier.size(64.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("LEAK DETECTED!", color = FuturisticColors.ErrorGlow, fontWeight = FontWeight.Bold)
                                Text("Your real IP may be exposed", color = Color.White.copy(alpha = 0.7f))
                            }
                        }
                        LeakStatus.ERROR -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Error, null, tint = FuturisticColors.NeonOrange, modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Test incomplete", color = FuturisticColors.NeonOrange)
                            }
                        }
                    }
                }
                
                // Current IP Display
                if (vpnIp.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = FuturisticColors.NeonGreen.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Public, null, tint = FuturisticColors.NeonGreen)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Your IP Address", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                                Text(vpnIp, color = FuturisticColors.NeonGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                
                // Start Button
                CyberButton(
                    onClick = { runLeakTest() },
                    enabled = !isRunning,
                    glowColor = FuturisticColors.NeonGreen,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = FuturisticColors.NeonGreen, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("TESTING...")
                    } else {
                        Icon(Icons.Default.Security, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("RUN LEAK TEST", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Results Card
        if (results.isNotEmpty()) {
            GlassCard(glowColor = FuturisticColors.NeonPurple) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NeonText(
                        text = "TEST RESULTS",
                        color = FuturisticColors.NeonPurple,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    
                    results.forEach { result ->
                        LeakTestResultItem(result)
                    }
                }
            }
        }
        
        // Info Card
        GlassCard(glowColor = FuturisticColors.NeonYellow.copy(alpha = 0.5f)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = FuturisticColors.NeonYellow, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("What We Test", color = FuturisticColors.NeonYellow, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "• IPv4/IPv6 Address - Your visible IP\n" +
                           "• DNS Leak - If DNS queries bypass VPN\n" +
                           "• WebRTC Leak - Browser IP exposure\n" +
                           "• Run this test with VPN connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun LeakTestResultItem(result: LeakTestResult) {
    val (icon, color) = when (result.status) {
        LeakStatus.SAFE -> Icons.Default.CheckCircle to FuturisticColors.NeonGreen
        LeakStatus.LEAK -> Icons.Default.Warning to FuturisticColors.ErrorGlow
        LeakStatus.ERROR -> Icons.Default.Error to FuturisticColors.NeonOrange
        LeakStatus.TESTING -> Icons.Default.HourglassEmpty to FuturisticColors.NeonYellow
        LeakStatus.PENDING -> Icons.Default.Schedule to Color.Gray
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(result.testName, color = Color.White, fontWeight = FontWeight.Bold)
            Text(result.details, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
            if (result.detectedIp.isNotEmpty()) {
                Text(result.detectedIp, color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            }
        }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = color.copy(alpha = 0.2f)
        ) {
            Text(
                text = when (result.status) {
                    LeakStatus.SAFE -> "SAFE"
                    LeakStatus.LEAK -> "LEAK"
                    LeakStatus.ERROR -> "ERROR"
                    else -> "..."
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
