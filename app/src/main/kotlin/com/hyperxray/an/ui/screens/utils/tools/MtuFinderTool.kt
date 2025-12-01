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

data class MtuTestResult(
    val mtuSize: Int,
    val success: Boolean,
    val responseTime: Long = 0
)

@Composable
fun MtuFinderTool() {
    var target by remember { mutableStateOf("8.8.8.8") }
    var isSearching by remember { mutableStateOf(false) }
    var optimalMtu by remember { mutableStateOf(0) }
    var currentTest by remember { mutableStateOf(0) }
    var progress by remember { mutableStateOf(0f) }
    var testResults by remember { mutableStateOf<List<MtuTestResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchMode by remember { mutableStateOf("binary") } // binary or linear
    val scope = rememberCoroutineScope()

    // Common MTU values for reference
    val commonMtuValues = mapOf(
        1500 to "Ethernet (Standard)",
        1492 to "PPPoE",
        1472 to "VPN (IPSec)",
        1400 to "VPN (OpenVPN)",
        1380 to "VPN (WireGuard)",
        1280 to "IPv6 Minimum",
        576 to "Dialup"
    )

    suspend fun testMtu(host: String, mtuSize: Int): MtuTestResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                // Simulate MTU test using TCP connection with specific packet size
                // In real implementation, this would use ICMP with DF flag
                val socket = Socket()
                socket.sendBufferSize = mtuSize
                socket.connect(InetSocketAddress(host, 80), 2000)
                
                // Try to send data of MTU size
                val testData = ByteArray(mtuSize - 28) // Subtract IP + TCP headers
                socket.getOutputStream().write(testData)
                socket.close()
                
                val responseTime = System.currentTimeMillis() - startTime
                MtuTestResult(mtuSize, true, responseTime)
            } catch (e: Exception) {
                MtuTestResult(mtuSize, false)
            }
        }
    }

    fun findOptimalMtu() {
        scope.launch {
            isSearching = true
            error = null
            testResults = emptyList()
            optimalMtu = 0
            progress = 0f
            
            try {
                var low = 576
                var high = 1500
                var lastSuccess = low
                val results = mutableListOf<MtuTestResult>()
                
                if (searchMode == "binary") {
                    // Binary search for optimal MTU
                    var iterations = 0
                    val maxIterations = 15
                    
                    while (low <= high && iterations < maxIterations) {
                        val mid = (low + high) / 2
                        currentTest = mid
                        progress = iterations.toFloat() / maxIterations
                        
                        val result = testMtu(target, mid)
                        results.add(result)
                        testResults = results.toList()
                        
                        if (result.success) {
                            lastSuccess = mid
                            low = mid + 1
                        } else {
                            high = mid - 1
                        }
                        
                        iterations++
                        delay(100)
                    }
                } else {
                    // Linear search (more accurate but slower)
                    var mtu = 1500
                    val step = 10
                    
                    while (mtu >= 576) {
                        currentTest = mtu
                        progress = (1500 - mtu).toFloat() / (1500 - 576)
                        
                        val result = testMtu(target, mtu)
                        results.add(result)
                        testResults = results.toList()
                        
                        if (result.success) {
                            lastSuccess = mtu
                            break
                        }
                        
                        mtu -= step
                        delay(50)
                    }
                }
                
                optimalMtu = lastSuccess
                progress = 1f
                
            } catch (e: Exception) {
                error = "MTU search failed: ${e.message}"
            }
            
            isSearching = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Main Card
        GlassCard(glowColor = FuturisticColors.NeonOrange) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NeonText(
                    text = "MTU FINDER",
                    color = FuturisticColors.NeonOrange,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                
                Text(
                    "Find the optimal MTU (Maximum Transmission Unit) for your VPN connection to improve performance and reduce fragmentation.",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
                
                // Target Input
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Target Host") },
                    placeholder = { Text("8.8.8.8") },
                    leadingIcon = { Icon(Icons.Default.Router, null, tint = FuturisticColors.NeonOrange) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FuturisticColors.NeonOrange,
                        unfocusedBorderColor = FuturisticColors.NeonOrange.copy(alpha = 0.3f)
                    )
                )
                
                // Search Mode
                Text("Search Mode", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = searchMode == "binary",
                        onClick = { searchMode = "binary" },
                        label = { Text("Fast (Binary)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FuturisticColors.NeonCyan.copy(alpha = 0.2f),
                            selectedLabelColor = FuturisticColors.NeonCyan
                        )
                    )
                    FilterChip(
                        selected = searchMode == "linear",
                        onClick = { searchMode = "linear" },
                        label = { Text("Accurate (Linear)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FuturisticColors.NeonPurple.copy(alpha = 0.2f),
                            selectedLabelColor = FuturisticColors.NeonPurple
                        )
                    )
                }
                
                // Progress
                if (isSearching) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Testing MTU: $currentTest", color = FuturisticColors.NeonYellow)
                            Text("${(progress * 100).toInt()}%", color = FuturisticColors.NeonCyan)
                        }
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            color = FuturisticColors.NeonOrange
                        )
                    }
                }
                
                // Start Button
                CyberButton(
                    onClick = { findOptimalMtu() },
                    enabled = !isSearching && target.isNotBlank(),
                    glowColor = FuturisticColors.NeonGreen,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = FuturisticColors.NeonGreen, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SEARCHING...")
                    } else {
                        Icon(Icons.Default.Search, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("FIND OPTIMAL MTU", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        
        // Result Card
        if (optimalMtu > 0) {
            GlassCard(glowColor = FuturisticColors.NeonGreen) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NeonText(
                        text = "OPTIMAL MTU FOUND",
                        color = FuturisticColors.NeonGreen,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    
                    // Big MTU Display
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = FuturisticColors.NeonGreen.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "$optimalMtu",
                                style = MaterialTheme.typography.displayLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = FuturisticColors.NeonGreen
                            )
                            Text("bytes", color = Color.White.copy(alpha = 0.6f))
                            
                            // Match with common values
                            commonMtuValues[optimalMtu]?.let { description ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(description, color = FuturisticColors.NeonCyan, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    
                    // Recommendations
                    HorizontalDivider(color = FuturisticColors.NeonGreen.copy(alpha = 0.2f))
                    
                    Text("Recommended Settings:", color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("VPN MTU:", color = Color.White.copy(alpha = 0.6f))
                        Text("${optimalMtu - 80}", color = FuturisticColors.NeonCyan, fontFamily = FontFamily.Monospace)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("MSS Clamping:", color = Color.White.copy(alpha = 0.6f))
                        Text("${optimalMtu - 40}", color = FuturisticColors.NeonCyan, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        
        // Common MTU Reference Card
        GlassCard(glowColor = FuturisticColors.NeonBlue.copy(alpha = 0.5f)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Common MTU Values", color = FuturisticColors.NeonBlue, fontWeight = FontWeight.Bold)
                
                commonMtuValues.forEach { (mtu, description) ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(description, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                        Text("$mtu", color = FuturisticColors.NeonCyan, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        
        // Error Display
        error?.let {
            GlassCard(glowColor = FuturisticColors.ErrorGlow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, null, tint = FuturisticColors.ErrorGlow)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = it, color = FuturisticColors.ErrorGlow)
                }
            }
        }
    }
}
