package com.hyperxray.an.ui.screens.utils.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

@Composable
fun PingTestTool() {
    var results by remember { mutableStateOf<Map<String, PingResult>>(emptyMap()) }
    var isRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val targets = listOf(
        "Google" to "8.8.8.8",
        "Cloudflare" to "1.1.1.1",
        "Quad9" to "9.9.9.9",
        "OpenDNS" to "208.67.222.222"
    )

    fun runPingTest() {
        scope.launch {
            isRunning = true
            results = emptyMap()
            
            targets.forEach { (name, ip) ->
                val result = withContext(Dispatchers.IO) {
                    try {
                        val start = System.currentTimeMillis()
                        val address = InetAddress.getByName(ip)
                        val reachable = address.isReachable(2000)
                        val end = System.currentTimeMillis()
                        if (reachable) {
                            PingResult.Success(end - start)
                        } else {
                            PingResult.Failure("Timeout")
                        }
                    } catch (e: Exception) {
                        PingResult.Failure(e.message ?: "Error")
                    }
                }
                results = results + (name to result)
            }
            isRunning = false
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
                    text = "Latency Test",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                IconButton(onClick = { runPingTest() }, enabled = !isRunning) {
                    if (isRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEach { (name, _) ->
                    val result = results[name]
                    PingRow(name, result)
                }
            }
        }
    }
}

@Composable
private fun PingRow(name: String, result: PingResult?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        when (result) {
            null -> Text("-", color = Color.Gray)
            is PingResult.Success -> Text(
                "${result.latencyMs} ms",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = if (result.latencyMs < 50) Color(0xFF10B981) else if (result.latencyMs < 150) Color(0xFFF59E0B) else Color(0xFFEF4444)
            )
            is PingResult.Failure -> Text(
                "Fail",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

sealed class PingResult {
    data class Success(val latencyMs: Long) : PingResult()
    data class Failure(val error: String) : PingResult()
}
