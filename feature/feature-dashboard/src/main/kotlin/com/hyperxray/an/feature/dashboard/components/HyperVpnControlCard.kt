package com.hyperxray.an.feature.dashboard.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxray.an.feature.dashboard.formatBytes
import com.hyperxray.an.feature.dashboard.formatThroughput
import com.hyperxray.an.core.network.vpn.HyperVpnStateManager

// Neon Colors
private object NeonColors {
    val Cyan = Color(0xFF00F5FF)
    val Magenta = Color(0xFFFF00FF)
    val Purple = Color(0xFF8B5CF6)
    val Green = Color(0xFF00FF88)
    val Orange = Color(0xFFFF6B00)
    val Pink = Color(0xFFFF0080)
    val Yellow = Color(0xFFFFE500)
    val Red = Color(0xFFFF3366)
}


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
    val infiniteTransition = rememberInfiniteTransition(label = "vpnCard")
    
    // Animated border rotation
    val borderRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "borderRotation"
    )
    
    // Pulsing glow
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    
    val isConnected = state is HyperVpnStateManager.VpnState.Connected
    val isConnecting = state is HyperVpnStateManager.VpnState.Connecting
    val isError = state is HyperVpnStateManager.VpnState.Error
    
    val primaryColor = when {
        isConnected -> NeonColors.Green
        isConnecting -> NeonColors.Yellow
        isError -> NeonColors.Red
        else -> NeonColors.Cyan
    }
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0A15).copy(alpha = 0.95f),
                        Color(0xFF050510).copy(alpha = 0.9f)
                    )
                )
            )
            .border(
                width = 2.dp,
                brush = Brush.sweepGradient(
                    colors = if (isConnected) {
                        listOf(NeonColors.Green, NeonColors.Cyan, NeonColors.Purple, NeonColors.Green)
                    } else if (isConnecting) {
                        listOf(NeonColors.Yellow, NeonColors.Orange, NeonColors.Yellow)
                    } else {
                        listOf(Color(0xFF1A1A2E), Color(0xFF16213E), Color(0xFF1A1A2E))
                    }
                ),
                shape = RoundedCornerShape(28.dp)
            )
            .drawBehind {
                if (isConnected) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = glowAlpha * 0.2f),
                                Color.Transparent
                            ),
                            radius = size.maxDimension
                        )
                    )
                }
            }
            .padding(28.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header with animated orb
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // Glowing title
                    Box {
                        Text(
                            text = "HYPERVPN",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 3.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = primaryColor.copy(alpha = 0.3f),
                            modifier = Modifier.blur(6.dp)
                        )
                        Text(
                            text = "HYPERVPN",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 3.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "WireGuard + Xray Tunnel",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        ),
                        color = Color(0xFF606080)
                    )
                }
                
                // Animated Status Orb
                FuturisticStatusOrb(
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    primaryColor = primaryColor
                )
            }
            
            // Status Message
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                },
                label = "statusMessage"
            ) { currentState ->
                val (message, color) = when (currentState) {
                    is HyperVpnStateManager.VpnState.Connected -> {
                        val serverInfo = if (currentState.serverName.isNotEmpty()) {
                            "⚡ Connected to ${currentState.serverName}"
                        } else "⚡ QUANTUM TUNNEL ACTIVE"
                        serverInfo to NeonColors.Green
                    }
                    is HyperVpnStateManager.VpnState.Connecting -> currentState.getMessage() to NeonColors.Yellow
                    is HyperVpnStateManager.VpnState.Disconnecting -> "◉ Disconnecting..." to NeonColors.Orange
                    is HyperVpnStateManager.VpnState.Error -> "⚠ ${currentState.getMessage()}" to NeonColors.Red
                    is HyperVpnStateManager.VpnState.Disconnected -> "◉ STANDBY MODE" to Color(0xFF606080)
                }
                
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    ),
                    color = color
                )
            }


            // Error Message
            AnimatedVisibility(
                visible = error != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(NeonColors.Red.copy(alpha = 0.1f))
                        .border(1.dp, NeonColors.Red.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .clickable { onClearError() }
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = error ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = NeonColors.Red
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "TAP TO DISMISS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            ),
                            color = NeonColors.Red.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            
            // Statistics Grid
            AnimatedVisibility(
                visible = isConnected && stats.totalBytes > 0,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Traffic Stats Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        NeonStatBox("TX", formatBytes(stats.txBytes), NeonColors.Cyan, Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(12.dp))
                        NeonStatBox("RX", formatBytes(stats.rxBytes), NeonColors.Magenta, Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(12.dp))
                        NeonStatBox("TOTAL", formatBytes(stats.totalBytes), NeonColors.Purple, Modifier.weight(1f))
                    }
                    
                    // Performance Stats Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        NeonStatBox("SPEED", formatThroughput(stats.throughput), NeonColors.Green, Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(12.dp))
                        NeonStatBox("UPTIME", formatUptime(stats.uptime.toInt()), NeonColors.Yellow, Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(12.dp))
                        NeonStatBox(
                            "LATENCY",
                            "${stats.latency}ms",
                            if (stats.latency < 100) NeonColors.Green else NeonColors.Orange,
                            Modifier.weight(1f)
                        )
                    }
                    
                    // Additional Stats
                    if (stats.packetLoss > 0 || stats.lastHandshake > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (stats.packetLoss > 0) {
                                NeonStatBox(
                                    "LOSS",
                                    String.format("%.2f%%", stats.packetLoss),
                                    if (stats.packetLoss < 1.0) NeonColors.Green else NeonColors.Red,
                                    Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            if (stats.lastHandshake > 0) {
                                NeonStatBox(
                                    "HANDSHAKE",
                                    stats.lastHandshakeFormatted,
                                    NeonColors.Cyan,
                                    Modifier.weight(if (stats.packetLoss > 0) 1f else 2f)
                                )
                            }
                        }
                    }
                }
            }
            
            // Control Button
            FuturisticControlButton(
                state = state,
                onStartClick = onStartClick,
                onStopClick = onStopClick
            )
        }
    }
}


@Composable
private fun FuturisticStatusOrb(
    isConnected: Boolean,
    isConnecting: Boolean,
    primaryColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnecting) 1000 else 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    Box(
        modifier = Modifier.size(70.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer glow
        if (isConnected || isConnecting) {
            Canvas(modifier = Modifier.size(70.dp)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.3f),
                            primaryColor.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    )
                )
            }
        }
        
        // Rotating ring
        Canvas(modifier = Modifier.size(60.dp)) {
            drawArc(
                brush = Brush.sweepGradient(
                    colors = if (isConnected) {
                        listOf(NeonColors.Green, NeonColors.Cyan, NeonColors.Purple, Color.Transparent)
                    } else if (isConnecting) {
                        listOf(NeonColors.Yellow, NeonColors.Orange, Color.Transparent)
                    } else {
                        listOf(Color(0xFF303050), Color(0xFF404060), Color.Transparent)
                    }
                ),
                startAngle = rotation,
                sweepAngle = 280f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        
        // Inner core
        Box(
            modifier = Modifier
                .size((28 * if (isConnected) pulseScale else 1f).dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            primaryColor,
                            primaryColor.copy(alpha = 0.6f),
                            primaryColor.copy(alpha = 0.2f)
                        )
                    )
                )
        )
    }
}

@Composable
private fun NeonStatBox(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                ),
                color = Color(0xFF808090)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = color.copy(alpha = 0.3f),
                    modifier = Modifier.blur(2.dp)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = color
                )
            }
        }
    }
}


@Composable
private fun FuturisticControlButton(
    state: HyperVpnStateManager.VpnState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "button")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "buttonGlow"
    )
    
    val isConnected = state is HyperVpnStateManager.VpnState.Connected
    val isConnecting = state is HyperVpnStateManager.VpnState.Connecting
    val isDisconnecting = state is HyperVpnStateManager.VpnState.Disconnecting
    val isLoading = isConnecting || isDisconnecting
    
    val buttonColor = when {
        isConnected -> NeonColors.Red
        isConnecting -> NeonColors.Yellow
        isDisconnecting -> NeonColors.Orange
        else -> NeonColors.Green
    }
    
    val buttonText = when {
        isConnected -> "◼ DISCONNECT"
        isConnecting -> "◉ CONNECTING..."
        isDisconnecting -> "◉ DISCONNECTING..."
        else -> "▶ CONNECT"
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        buttonColor.copy(alpha = 0.2f),
                        buttonColor.copy(alpha = 0.1f),
                        buttonColor.copy(alpha = 0.2f)
                    )
                )
            )
            .border(
                width = 2.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        buttonColor.copy(alpha = glowAlpha),
                        buttonColor.copy(alpha = glowAlpha * 0.5f),
                        buttonColor.copy(alpha = glowAlpha)
                    )
                ),
                shape = RoundedCornerShape(18.dp)
            )
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            buttonColor.copy(alpha = glowAlpha * 0.15f),
                            Color.Transparent
                        ),
                        radius = size.maxDimension
                    )
                )
            }
            .clickable(enabled = !isLoading) {
                if (isConnected) onStopClick() else onStartClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = buttonColor,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Box {
                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    ),
                    color = buttonColor.copy(alpha = 0.4f),
                    modifier = Modifier.blur(4.dp)
                )
                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    ),
                    color = buttonColor
                )
            }
        }
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
