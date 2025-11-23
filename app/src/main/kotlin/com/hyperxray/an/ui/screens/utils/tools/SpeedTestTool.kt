package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URL
import kotlin.math.roundToInt

@Composable
fun SpeedTestTool() {
    var speedMbps by remember { mutableStateOf(0.0) }
    var progress by remember { mutableStateOf(0f) }
    var isRunning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val animatedProgress by animateFloatAsState(targetValue = progress, label = "progress")

    fun runSpeedTest() {
        scope.launch {
            isRunning = true
            speedMbps = 0.0
            progress = 0f
            error = null
            
            try {
                withContext(Dispatchers.IO) {
                    // Use a known fast file (e.g., 10MB test file from a CDN)
                    // Using Cloudflare speed test file as an example
                    val url = URL("https://speed.cloudflare.com/__down?bytes=10000000") // 10MB
                    val connection = url.openConnection()
                    connection.connect()
                    
                    val input: InputStream = connection.getInputStream()
                    val buffer = ByteArray(8192)
                    var bytesRead = 0
                    var totalBytesRead = 0L
                    val startTime = System.currentTimeMillis()
                    val totalSize = 10_000_000L // 10MB
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        totalBytesRead += bytesRead
                        val currentTime = System.currentTimeMillis()
                        val duration = (currentTime - startTime) / 1000.0 // seconds
                        
                        if (duration > 0) {
                            // Calculate speed in Mbps (bits per second / 1,000,000)
                            val currentSpeed = (totalBytesRead * 8) / (duration * 1_000_000)
                            speedMbps = currentSpeed
                        }
                        
                        progress = (totalBytesRead.toFloat() / totalSize).coerceIn(0f, 1f)
                    }
                    input.close()
                }
            } catch (e: Exception) {
                error = "Speed test failed: ${e.message}"
            } finally {
                isRunning = false
                progress = 1f
            }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Download Speed Test",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                IconButton(onClick = { runSpeedTest() }, enabled = !isRunning) {
                    if (isRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (error != null) {
                Text(
                    text = error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${String.format("%.2f", speedMbps)} Mbps",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }
}
