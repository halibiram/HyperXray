package com.hyperxray.an.feature.dashboard.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private object WarpColors {
    val Orange = Color(0xFFFF6B35)
    val Amber = Color(0xFFFF8C42)
    val Gold = Color(0xFFFFA500)
    val Cyan = Color(0xFF00F5FF)
    val Green = Color(0xFF00FF88)
    val Yellow = Color(0xFFFFE500)
    val Red = Color(0xFFFF3366)
    val Purple = Color(0xFF8B5CF6)
    val Magenta = Color(0xFFFF00FF)
}

// Holographic Shimmer Effect
@Composable
private fun Modifier.holographicShimmer(): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "holographic")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -500f,
        targetValue = 1500f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )
    
    return this.drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    WarpColors.Cyan.copy(alpha = 0.1f),
                    WarpColors.Magenta.copy(alpha = 0.15f),
                    WarpColors.Purple.copy(alpha = 0.1f),
                    Color.Transparent
                ),
                start = Offset(shimmerOffset, 0f),
                end = Offset(shimmerOffset + 300f, size.height)
            ),
            blendMode = BlendMode.Screen
        )
    }
}


@Composable
fun WarpAccountCard(
    accountExists: Boolean,
    publicKey: String? = null,
    endpoint: String? = null,
    accountType: String? = null,
    license: String? = null,
    warpEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "warp")
    
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    val primaryColor = when {
        accountExists && warpEnabled -> WarpColors.Green
        accountExists -> WarpColors.Orange
        else -> Color(0xFF404060)
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
                    colors = if (accountExists) {
                        listOf(WarpColors.Orange, WarpColors.Amber, WarpColors.Gold, WarpColors.Orange)
                    } else {
                        listOf(Color(0xFF1A1A2E), Color(0xFF16213E), Color(0xFF1A1A2E))
                    }
                ),
                shape = RoundedCornerShape(28.dp)
            )
            .holographicShimmer()
            .drawBehind {
                if (accountExists) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                WarpColors.Orange.copy(alpha = glowAlpha * 0.15f),
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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Animated WARP Logo
                    Box(
                        modifier = Modifier.size(56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Rotating ring
                        Canvas(modifier = Modifier.size(56.dp)) {
                            drawArc(
                                brush = Brush.sweepGradient(
                                    colors = listOf(WarpColors.Orange, WarpColors.Amber, WarpColors.Gold, Color.Transparent)
                                ),
                                startAngle = rotationAngle,
                                sweepAngle = 280f,
                                useCenter = false,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                        // Inner logo
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(WarpColors.Orange, WarpColors.Amber)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "W",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = Color.White
                            )
                        }
                    }
                    
                    Column {
                        Box {
                            Text(
                                text = "CLOUDFLARE WARP",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = WarpColors.Orange.copy(alpha = 0.3f),
                                modifier = Modifier.blur(4.dp)
                            )
                            Text(
                                text = "CLOUDFLARE WARP",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (accountExists) "ACCOUNT ACTIVE" else "NO ACCOUNT",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            ),
                            color = if (accountExists) WarpColors.Green else Color(0xFF606080)
                        )
                    }
                }
                
                // Status orb
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(primaryColor, primaryColor.copy(alpha = 0.5f))
                            )
                        )
                )
            }


            // Account Details
            AnimatedVisibility(
                visible = accountExists,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Account Type & License Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        accountType?.let { type ->
                            NeonInfoBadge(
                                label = "TYPE",
                                value = type.uppercase(),
                                color = WarpColors.Orange,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        NeonInfoBadge(
                            label = "LICENSE",
                            value = if (license != null) "WARP+" else "FREE",
                            color = if (license != null) WarpColors.Green else WarpColors.Yellow,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    // Public Key
                    publicKey?.let { key ->
                        NeonInfoRow(
                            label = "PUBLIC KEY",
                            value = if (key.length > 32) "${key.take(12)}...${key.takeLast(8)}" else key,
                            color = WarpColors.Cyan
                        )
                    }
                    
                    // Endpoint
                    endpoint?.let { ep ->
                        NeonInfoRow(
                            label = "ENDPOINT",
                            value = ep,
                            color = WarpColors.Amber
                        )
                    }
                    
                    // WARP Status
                    NeonInfoRow(
                        label = "STATUS",
                        value = if (warpEnabled) "⚡ ENABLED" else "◉ DISABLED",
                        color = if (warpEnabled) WarpColors.Green else Color(0xFF606080)
                    )
                }
            }
            
            // No Account State
            AnimatedVisibility(
                visible = !accountExists,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0A0A15).copy(alpha = 0.8f))
                        .border(1.dp, Color(0xFF1A1A2E), RoundedCornerShape(16.dp))
                        .padding(20.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "◉ NO WARP ACCOUNT",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            ),
                            color = Color(0xFF606080)
                        )
                        Text(
                            text = "Register or load existing configuration",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = Color(0xFF404060)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NeonInfoBadge(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(14.dp),
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
private fun NeonInfoRow(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            ),
            color = Color(0xFF808090)
        )
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
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
