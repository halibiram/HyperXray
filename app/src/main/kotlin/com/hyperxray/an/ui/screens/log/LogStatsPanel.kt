package com.hyperxray.an.ui.screens.log

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LogStatsPanel(
    stats: LogStats,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "statsGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // Compact Stats Bar (always visible)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF0A0A0A),
                            Color(0xFF101015),
                            Color(0xFF0A0A0A)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            LogColorPalette.NeonCyan.copy(alpha = glowAlpha * 0.3f),
                            LogColorPalette.NeonPurple.copy(alpha = glowAlpha * 0.5f),
                            LogColorPalette.NeonCyan.copy(alpha = glowAlpha * 0.3f)
                        )
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                .clickable { onToggleExpand() }
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Quick Stats
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QuickStatItem(
                        value = stats.totalCount.toString(),
                        label = "TOTAL",
                        color = LogColorPalette.NeonCyan
                    )
                    QuickStatItem(
                        value = stats.errorCount.toString(),
                        label = "ERR",
                        color = LogColorPalette.NeonRed,
                        highlight = stats.errorCount > 0
                    )
                    QuickStatItem(
                        value = stats.warnCount.toString(),
                        label = "WARN",
                        color = LogColorPalette.NeonYellow,
                        highlight = stats.warnCount > 0
                    )
                    QuickStatItem(
                        value = String.format("%.1f", stats.logsPerSecond),
                        label = "/SEC",
                        color = LogColorPalette.NeonGreen
                    )
                }

                // Expand indicator
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = LogColorPalette.NeonCyan.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Expanded Stats Panel
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Stats Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCard(
                        icon = Icons.Default.Warning,
                        title = "ERRORS",
                        value = stats.errorCount.toString(),
                        color = LogColorPalette.NeonRed,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        icon = Icons.Default.Info,
                        title = "WARNINGS",
                        value = stats.warnCount.toString(),
                        color = LogColorPalette.NeonYellow,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        icon = Icons.Default.CheckCircle,
                        title = "INFO",
                        value = stats.infoCount.toString(),
                        color = LogColorPalette.NeonCyan,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCard(
                        icon = Icons.Default.Send,
                        title = "TCP",
                        value = stats.tcpCount.toString(),
                        color = LogColorPalette.NeonGreen,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        icon = Icons.Default.Send,
                        title = "UDP",
                        value = stats.udpCount.toString(),
                        color = LogColorPalette.NeonBlue,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        icon = Icons.Default.Search,
                        title = "DNS",
                        value = stats.dnsCount.toString(),
                        color = LogColorPalette.NeonCyan,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCard(
                        icon = Icons.Default.Visibility,
                        title = "SNIFF",
                        value = stats.sniffCount.toString(),
                        color = LogColorPalette.NeonOrange,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        icon = Icons.Default.Memory,
                        title = "AI",
                        value = stats.aiCount.toString(),
                        color = LogColorPalette.NeonPurple,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        icon = Icons.Default.Language,
                        title = "DOMAINS",
                        value = stats.uniqueDomains.size.toString(),
                        color = LogColorPalette.NeonMagenta,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickStatItem(
    value: String,
    label: String,
    color: Color,
    highlight: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "quickStat")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = value,
            color = if (highlight) color.copy(alpha = pulseAlpha) else color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        Text(
            text = label,
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0A0A0A))
            .border(
                width = 1.dp,
                color = color.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = value,
                color = color,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Text(
                text = title,
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun LiveIndicator(
    isLive: Boolean,
    logsPerSecond: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "live")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Pulsing dot
        Box(
            modifier = Modifier
                .size(8.dp * pulseScale)
                .clip(CircleShape)
                .background(
                    if (isLive) LogColorPalette.NeonGreen.copy(alpha = pulseAlpha)
                    else Color.Gray.copy(alpha = 0.5f)
                )
        )
        
        Text(
            text = if (isLive) "LIVE" else "PAUSED",
            color = if (isLive) LogColorPalette.NeonGreen.copy(alpha = pulseAlpha) else Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.sp
        )
        
        if (isLive && logsPerSecond > 0) {
            Text(
                text = "• ${String.format("%.1f", logsPerSecond)}/s",
                color = LogColorPalette.NeonCyan.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
        }
    }
}
