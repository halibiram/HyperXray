package com.hyperxray.an.feature.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxray.an.feature.dashboard.formatThroughput
import kotlinx.coroutines.delay

/**
 * A card component displaying real-time traffic throughput with a mini chart.
 * Shows upload and download throughput trends over time.
 * 
 * @param uplinkThroughput Current upload throughput
 * @param downlinkThroughput Current download throughput
 * @param maxThroughput Maximum throughput for normalization (default: 10 MB/s)
 * @param iconRes Resource ID for the icon
 */
@Composable
fun TrafficChartCard(
    uplinkThroughput: Double,
    downlinkThroughput: Double,
    iconRes: Int,
    maxThroughput: Double = 10_000_000.0, // 10 MB/s
    modifier: Modifier = Modifier
) {
    // Store recent throughput values for chart - initialize with small values to ensure chart visibility
    var uplinkHistory by remember { mutableStateOf(List(30) { 0.01f }) }
    var downlinkHistory by remember { mutableStateOf(List(30) { 0.01f }) }
    
    // Update history when throughput changes (throttled to ~300ms for smoother updates)
    val currentUplink = rememberUpdatedState(uplinkThroughput)
    val currentDownlink = rememberUpdatedState(downlinkThroughput)
    
    LaunchedEffect(Unit) {
        var lastUpdateTime = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if (now - lastUpdateTime >= 300) { // Update every 300ms for smoother chart
                // Normalize values: ensure minimum 0.01f so chart always has a baseline
                val newUplink = ((currentUplink.value / maxThroughput).toFloat().coerceIn(0f, 1f)).coerceAtLeast(0.01f)
                val newDownlink = ((currentDownlink.value / maxThroughput).toFloat().coerceIn(0f, 1f)).coerceAtLeast(0.01f)
                
                // Update state properly - create new list to trigger recomposition
                uplinkHistory = uplinkHistory.drop(1) + newUplink
                downlinkHistory = downlinkHistory.drop(1) + newDownlink
                lastUpdateTime = now
            }
            delay(100) // Check every 100ms
        }
    }
    
    ModernStatCard(
        title = "Traffic Chart",
        iconRes = iconRes,
        gradientColors = listOf(
            Color(0xFF00E5FF), // Neon Cyan
            Color(0xFFD500F9), // Neon Purple
            Color(0xFFFF00E5)  // Neon Magenta
        ),
        modifier = modifier,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Upload Chart - Modern Design
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF00E5FF).copy(alpha = 0.1f),
                                    Color(0xFF00B8D4).copy(alpha = 0.05f)
                                )
                            )
                        )
                        .padding(18.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "↑",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color(0xFF00E5FF) // Neon Cyan
                                )
                                Text(
                                    text = "Upload",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.2.sp
                                    ),
                                    color = Color(0xFFB0B0B0)
                                )
                            }
                            Text(
                                text = formatThroughput(uplinkThroughput),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.2).sp
                                ),
                                color = Color(0xFF00E5FF), // Neon Cyan
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        MiniChart(
                            dataPoints = uplinkHistory,
                            lineColor = Color(0xFF00E5FF),
                            fillGradient = listOf(
                                Color(0xFF00E5FF).copy(alpha = 0.3f),
                                Color(0xFF00E5FF).copy(alpha = 0.1f),
                                Color(0xFF00E5FF).copy(alpha = 0.0f)
                            ),
                            height = 70.dp
                        )
                    }
                }
                
                // Download Chart - Modern Design
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFFD500F9).copy(alpha = 0.1f),
                                    Color(0xFFAA00FF).copy(alpha = 0.05f)
                                )
                            )
                        )
                        .padding(18.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "↓",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color(0xFFD500F9) // Neon Purple
                                )
                                Text(
                                    text = "Download",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.2.sp
                                    ),
                                    color = Color(0xFFB0B0B0)
                                )
                            }
                            Text(
                                text = formatThroughput(downlinkThroughput),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.2).sp
                                ),
                                color = Color(0xFFD500F9), // Neon Purple
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        MiniChart(
                            dataPoints = downlinkHistory,
                            lineColor = Color(0xFFD500F9),
                            fillGradient = listOf(
                                Color(0xFFD500F9).copy(alpha = 0.3f),
                                Color(0xFFD500F9).copy(alpha = 0.1f),
                                Color(0xFFD500F9).copy(alpha = 0.0f)
                            ),
                            height = 70.dp
                        )
                    }
                }
            }
        }
    )
}

