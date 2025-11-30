package com.hyperxray.an.feature.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxray.an.feature.dashboard.DashboardColors
import com.hyperxray.an.feature.dashboard.formatBytes
import com.hyperxray.an.feature.dashboard.formatThroughput
import com.hyperxray.an.core.network.vpn.HyperVpnStateManager

/**
 * Control card for HyperVpnService (WireGuard over Xray)
 * Shows connection status, statistics, and control buttons
 */
@Composable
fun HyperVpnControlCard(
    state: HyperVpnStateManager.VpnState,
    stats: HyperVpnStateManager.TunnelStats,
    error: String?,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradientColors = DashboardColors.performanceGradient()
    val successColor = DashboardColors.successColor()
    val errorColor = DashboardColors.errorColor()
    val warningColor = DashboardColors.warningColor()
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF000000).copy(alpha = 0.6f),
                        Color(0xFF0A0A0A).copy(alpha = 0.4f)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(gradientColors),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "HyperVpn (WireGuard + Xray)",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        ),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when (state) {
                            is HyperVpnStateManager.VpnState.Connected -> {
                                if (state.serverName.isNotEmpty()) {
                                    "Connected to ${state.serverName}"
                                } else {
                                    "Connected"
                                }
                            }
                            is HyperVpnStateManager.VpnState.Connecting -> state.getMessage()
                            is HyperVpnStateManager.VpnState.Disconnecting -> state.getMessage()
                            is HyperVpnStateManager.VpnState.Error -> state.getMessage()
                            is HyperVpnStateManager.VpnState.Disconnected -> "Disconnected"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (state) {
                            is HyperVpnStateManager.VpnState.Connected -> successColor
                            is HyperVpnStateManager.VpnState.Error -> errorColor
                            else -> Color(0xFF808080)
                        }
                    )
                }
                
                // Status indicator
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when (state) {
                                is HyperVpnStateManager.VpnState.Connected -> successColor
                                is HyperVpnStateManager.VpnState.Connecting -> warningColor
                                is HyperVpnStateManager.VpnState.Error -> errorColor
                                else -> Color(0xFF404040)
                            }
                        )
                )
            }
            
            // Error message
            AnimatedVisibility(
                visible = error != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(errorColor.copy(alpha = 0.15f))
                        .border(1.dp, errorColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = errorColor
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap to dismiss",
                            style = MaterialTheme.typography.labelSmall,
                            color = errorColor.copy(alpha = 0.7f),
                            modifier = Modifier.clickable { onClearError() }
                        )
                    }
                }
            }
            
            // Statistics (only show when connected)
            AnimatedVisibility(
                visible = state is HyperVpnStateManager.VpnState.Connected && stats.totalBytes > 0,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem(
                            label = "TX",
                            value = formatBytes(stats.txBytes),
                            color = gradientColors[0]
                        )
                        StatItem(
                            label = "RX",
                            value = formatBytes(stats.rxBytes),
                            color = gradientColors[1]
                        )
                        StatItem(
                            label = "Total",
                            value = formatBytes(stats.totalBytes),
                            color = gradientColors[2]
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem(
                            label = "Throughput",
                            value = formatThroughput(stats.throughput),
                            color = gradientColors[0]
                        )
                        StatItem(
                            label = "Uptime",
                            value = formatUptime(stats.uptime.toInt()),
                            color = gradientColors[1]
                        )
                        StatItem(
                            label = "Latency",
                            value = "${stats.latency}ms",
                            color = if (stats.latency < 100) successColor else warningColor
                        )
                    }
                    
                    if (stats.packetLoss > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatItem(
                                label = "Packet Loss",
                                value = String.format("%.2f%%", stats.packetLoss),
                                color = if (stats.packetLoss < 1.0) successColor else errorColor
                            )
                            StatItem(
                                label = "Packets",
                                value = "${stats.totalPackets}",
                                color = gradientColors[2]
                            )
                        }
                    }
                    
                    // Last Handshake
                    if (stats.lastHandshake > 0) {
                        StatItem(
                            label = "Last Handshake",
                            value = stats.lastHandshakeFormatted,
                            color = successColor,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            
            // Control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (state) {
                    is HyperVpnStateManager.VpnState.Connected -> {
                        ControlButton(
                            text = "Stop",
                            onClick = onStopClick,
                            enabled = true,
                            color = errorColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    is HyperVpnStateManager.VpnState.Connecting -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(warningColor.copy(alpha = 0.2f))
                                .border(1.dp, warningColor, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = warningColor,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = when (state) {
                                        is HyperVpnStateManager.VpnState.Connecting -> state.getMessage()
                                        else -> "Connecting..."
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = warningColor
                                )
                            }
                        }
                    }
                    is HyperVpnStateManager.VpnState.Disconnecting -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(warningColor.copy(alpha = 0.2f))
                                .border(1.dp, warningColor, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = warningColor,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Disconnecting...",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = warningColor
                                )
                            }
                        }
                    }
                    else -> {
                        ControlButton(
                            text = "Start",
                            onClick = onStartClick,
                            enabled = true,
                            color = successColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF808080)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = color
        )
    }
}

@Composable
private fun ControlButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (enabled) {
                    Brush.verticalGradient(
                        colors = listOf(
                            color.copy(alpha = 0.3f),
                            color.copy(alpha = 0.2f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1A1A1A),
                            Color(0xFF0F0F0F)
                        )
                    )
                }
            )
            .border(
                1.dp,
                if (enabled) color.copy(alpha = 0.6f) else Color(0xFF2A2A2A),
                RoundedCornerShape(16.dp)
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = if (enabled) color else Color(0xFF606060)
        )
    }
}

private fun formatUptime(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

