package com.hyperxray.an.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hyperxray.an.common.formatThroughput

/**
 * A card component displaying real-time traffic throughput with a mini chart.
 * Shows upload and download throughput trends over time.
 * 
 * @param uplinkThroughput Current upload throughput
 * @param downlinkThroughput Current download throughput
 * @param maxThroughput Maximum throughput for normalization (default: 10 MB/s)
 */
@Composable
fun TrafficChartCard(
    uplinkThroughput: Double,
    downlinkThroughput: Double,
    maxThroughput: Double = 10_000_000.0, // 10 MB/s
    modifier: Modifier = Modifier
) {
    // Store recent throughput values for chart - use by delegate for better state management
    var uplinkHistory by remember { mutableStateOf(List(20) { 0f }) }
    var downlinkHistory by remember { mutableStateOf(List(20) { 0f }) }
    
    // Update history when throughput changes (throttled to ~500ms)
    // Use Unit key to keep LaunchedEffect running, track throughput with rememberUpdatedState
    val currentUplink = rememberUpdatedState(uplinkThroughput)
    val currentDownlink = rememberUpdatedState(downlinkThroughput)
    
    LaunchedEffect(Unit) {
        var lastUpdateTime = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if (now - lastUpdateTime >= 500) { // Update every 500ms max
                val newUplink = (currentUplink.value / maxThroughput).toFloat().coerceIn(0f, 1f)
                val newDownlink = (currentDownlink.value / maxThroughput).toFloat().coerceIn(0f, 1f)
                
                // Update state properly - create new list to trigger recomposition
                uplinkHistory = uplinkHistory.drop(1) + newUplink
                downlinkHistory = downlinkHistory.drop(1) + newDownlink
                lastUpdateTime = now
            }
            delay(100) // Check every 100ms
        }
    }
    
    AnimatedStatCard(
        title = "Traffic Chart",
        iconRes = null,
        gradientColors = listOf(
            Color(0xFF06B6D4),
            Color(0xFF3B82F6),
            Color(0xFF6366F1)
        ),
        animationDelay = 500,
        modifier = modifier,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Upload Chart
                Column {
                    Text(
                        text = "↑ Upload",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatThroughput(uplinkThroughput),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color(0xFF06B6D4)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    MiniChart(
                        dataPoints = uplinkHistory,
                        lineColor = Color(0xFF06B6D4),
                        fillGradient = listOf(
                            Color(0xFF06B6D4).copy(alpha = 0.3f),
                            Color(0xFF06B6D4).copy(alpha = 0.0f)
                        )
                    )
                }
                
                // Download Chart
                Column {
                    Text(
                        text = "↓ Download",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatThroughput(downlinkThroughput),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color(0xFF8B5CF6)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    MiniChart(
                        dataPoints = downlinkHistory,
                        lineColor = Color(0xFF8B5CF6),
                        fillGradient = listOf(
                            Color(0xFF8B5CF6).copy(alpha = 0.3f),
                            Color(0xFF8B5CF6).copy(alpha = 0.0f)
                        )
                    )
                }
            }
        }
    )
}

