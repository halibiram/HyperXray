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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxray.an.feature.dashboard.DnsCacheStats
import com.hyperxray.an.feature.dashboard.formatBytes
import com.hyperxray.an.feature.dashboard.formatNumber

/**
 * DNS Cache statistics card component.
 * Displays cache entry count, memory usage, hit/miss rates, and latency metrics.
 * 
 * @param stats DNS cache statistics data
 * @param gradientColors List of colors for the gradient effect
 */
@Composable
fun DnsCacheCard(
    stats: DnsCacheStats,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier
) {
    AnimatedStatCard(
        title = "DNS Cache",
        iconRes = null,
        gradientColors = gradientColors,
        animationDelay = 500,
        modifier = modifier,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Entry Count and Memory Usage Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Entry Count
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        gradientColors.first().copy(alpha = 0.15f),
                                        gradientColors.first().copy(alpha = 0.1f)
                                    )
                                )
                            )
                            .padding(16.dp)
                    ) {
                        Column {
                            Text(
                                text = "Entries",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFB0B0B0)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = formatNumber(stats.entryCount.toLong()),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = gradientColors.first(),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    
                    // Memory Usage
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        gradientColors[1].copy(alpha = 0.15f),
                                        gradientColors[1].copy(alpha = 0.1f)
                                    )
                                )
                            )
                            .padding(16.dp)
                    ) {
                        Column {
                            Text(
                                text = "Memory",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFB0B0B0)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${stats.memoryUsageMB}MB / ${stats.memoryLimitMB}MB",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = gradientColors[1],
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "(${stats.memoryUsagePercent}%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF808080)
                            )
                        }
                    }
                }
                
                // Hit/Miss Statistics Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Hits
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFF00E676).copy(alpha = 0.15f),
                                        Color(0xFF00C853).copy(alpha = 0.1f)
                                    )
                                )
                            )
                            .padding(16.dp)
                    ) {
                        Column {
                            Text(
                                text = "Hits",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFB0B0B0)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = formatNumber(stats.hits),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color(0xFF00E676),
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "${String.format("%.2f", stats.avgHitLatencyMs)}ms avg",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF808080)
                            )
                        }
                    }
                    
                    // Misses
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFFFF3D00).copy(alpha = 0.15f),
                                        Color(0xFFFF6D00).copy(alpha = 0.1f)
                                    )
                                )
                            )
                            .padding(16.dp)
                    ) {
                        Column {
                            Text(
                                text = "Misses",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFB0B0B0)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = formatNumber(stats.misses),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color(0xFFFF3D00),
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "${String.format("%.2f", stats.avgMissLatencyMs)}ms avg",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF808080)
                            )
                        }
                    }
                }
                
                // Hit Rate Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    gradientColors[2].copy(alpha = 0.15f),
                                    gradientColors[2].copy(alpha = 0.1f)
                                )
                            )
                        )
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Hit Rate",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFB0B0B0)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${stats.hitRate}%",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = gradientColors[2],
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    // Quality indicator dot
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                when {
                                    stats.hitRate >= 80 -> Color(0xFF00E676)
                                    stats.hitRate >= 60 -> Color(0xFFFFD600)
                                    else -> Color(0xFFFF3D00)
                                }
                            )
                    )
                }
                
                // Domain Hit Rate
                if (stats.avgDomainHitRate > 0) {
                    StatRow(
                        label = "Avg Domain Hit Rate",
                        value = "${stats.avgDomainHitRate}%"
                    )
                }
            }
        }
    )
}




