package com.hyperxray.an.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Futuristic Theme - Cyberpunk/Neon Design System
 * Features: Glassmorphism, Neon Glow, Animated Gradients, Holographic Effects
 */
object FuturisticColors {
    // Neon Primary Colors
    val NeonCyan = Color(0xFF00F5FF)
    val NeonMagenta = Color(0xFFFF00FF)
    val NeonPurple = Color(0xFF8B5CF6)
    val NeonBlue = Color(0xFF3B82F6)
    val NeonGreen = Color(0xFF00FF88)
    val NeonOrange = Color(0xFFFF6B00)
    val NeonPink = Color(0xFFFF0080)
    val NeonYellow = Color(0xFFFFE500)
    
    // Background Colors
    val DeepSpace = Color(0xFF000011)
    val DarkMatter = Color(0xFF050510)
    val VoidBlack = Color(0xFF0A0A15)
    val CyberDark = Color(0xFF0D0D1A)
    val MatrixGreen = Color(0xFF003300)
    
    // Glass Colors
    val GlassWhite = Color(0x1AFFFFFF)
    val GlassCyan = Color(0x1A00F5FF)
    val GlassMagenta = Color(0x1AFF00FF)
    val GlassPurple = Color(0x1A8B5CF6)
    
    // Status Colors
    val SuccessGlow = Color(0xFF00FF88)
    val ErrorGlow = Color(0xFFFF3366)
    val WarningGlow = Color(0xFFFFAA00)
    val InfoGlow = Color(0xFF00AAFF)
    
    // Gradient Presets
    val CyberGradient = listOf(NeonCyan, NeonMagenta, NeonPurple)
    val MatrixGradient = listOf(NeonGreen, NeonCyan, NeonBlue)
    val SunsetGradient = listOf(NeonOrange, NeonPink, NeonMagenta)
    val AuroraGradient = listOf(NeonGreen, NeonCyan, NeonPurple, NeonMagenta)
    val HolographicGradient = listOf(
        NeonCyan.copy(alpha = 0.8f),
        NeonMagenta.copy(alpha = 0.6f),
        NeonPurple.copy(alpha = 0.8f),
        NeonBlue.copy(alpha = 0.6f)
    )
}

/**
 * Animated Neon Glow Border
 */
@Composable
fun Modifier.neonGlow(
    color: Color = FuturisticColors.NeonCyan,
    glowRadius: Dp = 8.dp,
    animated: Boolean = true
): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "neonGlow")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    
    val effectiveAlpha = if (animated) alpha else 0.8f
    
    return this.drawBehind {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = effectiveAlpha * 0.3f),
                    color.copy(alpha = effectiveAlpha * 0.1f),
                    Color.Transparent
                ),
                radius = glowRadius.toPx() * 3
            )
        )
    }
}

/**
 * Glassmorphism Card
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    glowColor: Color = FuturisticColors.NeonCyan,
    borderGradient: List<Color> = listOf(
        glowColor.copy(alpha = 0.5f),
        glowColor.copy(alpha = 0.1f)
    ),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .neonGlow(glowColor, 12.dp)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(borderGradient),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = FuturisticColors.GlassWhite
        )
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            glowColor.copy(alpha = 0.05f),
                            Color.Transparent,
                            glowColor.copy(alpha = 0.02f)
                        )
                    )
                )
                .padding(20.dp),
            content = content
        )
    }
}

/**
 * Holographic Shimmer Effect
 */
@Composable
fun Modifier.holographicShimmer(): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "holographic")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
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
                colors = FuturisticColors.HolographicGradient,
                start = Offset(offset - 500f, 0f),
                end = Offset(offset, size.height)
            ),
            blendMode = BlendMode.Overlay,
            alpha = 0.15f
        )
    }
}

/**
 * Animated Gradient Background
 */
@Composable
fun AnimatedGradientBackground(
    colors: List<Color> = FuturisticColors.CyberGradient,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "gradientBg")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gradientAngle"
    )
    
    Box(
        modifier = modifier
            .background(FuturisticColors.DeepSpace)
            .drawBehind {
                val angleRad = Math.toRadians(angle.toDouble())
                val centerX = size.width / 2
                val centerY = size.height / 2
                val radius = maxOf(size.width, size.height)
                
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = colors.map { it.copy(alpha = 0.15f) } + Color.Transparent,
                        center = Offset(
                            centerX + (radius * 0.3f * kotlin.math.cos(angleRad)).toFloat(),
                            centerY + (radius * 0.3f * kotlin.math.sin(angleRad)).toFloat()
                        ),
                        radius = radius * 0.6f
                    )
                )
            },
        content = content
    )
}

/**
 * Cyber Button with Neon Effect
 */
@Composable
fun CyberButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glowColor: Color = FuturisticColors.NeonCyan,
    content: @Composable RowScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "buttonGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "buttonGlowAlpha"
    )
    
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .neonGlow(glowColor.copy(alpha = if (enabled) glowAlpha else 0.2f), 6.dp, enabled),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = glowColor.copy(alpha = 0.2f),
            contentColor = glowColor,
            disabledContainerColor = Color.Gray.copy(alpha = 0.1f),
            disabledContentColor = Color.Gray
        ),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    glowColor.copy(alpha = if (enabled) 0.8f else 0.3f),
                    glowColor.copy(alpha = if (enabled) 0.3f else 0.1f)
                )
            )
        ),
        content = content
    )
}

/**
 * Neon Text with Glow
 */
@Composable
fun NeonText(
    text: String,
    color: Color = FuturisticColors.NeonCyan,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Glow layer
        Text(
            text = text,
            style = style,
            color = color.copy(alpha = 0.5f),
            modifier = Modifier.blur(4.dp)
        )
        // Main text
        Text(
            text = text,
            style = style,
            color = color
        )
    }
}

/**
 * Animated Progress Ring
 */
@Composable
fun NeonProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = FuturisticColors.NeonCyan,
    trackColor: Color = color.copy(alpha = 0.1f),
    strokeWidth: Dp = 8.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "progressGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "progressGlowAlpha"
    )
    
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxSize()
                .neonGlow(color.copy(alpha = glowAlpha * 0.5f), 8.dp),
            color = color,
            trackColor = trackColor,
            strokeWidth = strokeWidth,
            strokeCap = StrokeCap.Round
        )
    }
}

/**
 * Scan Line Effect (Retro CRT)
 */
@Composable
fun Modifier.scanLines(
    lineColor: Color = Color.White.copy(alpha = 0.03f),
    lineSpacing: Dp = 2.dp
): Modifier {
    return this.drawWithContent {
        drawContent()
        val spacingPx = lineSpacing.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = lineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
            y += spacingPx
        }
    }
}

/**
 * Pulse Animation for Status Indicators
 */
@Composable
fun PulsingDot(
    color: Color = FuturisticColors.NeonGreen,
    size: Dp = 12.dp,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    Box(
        modifier = modifier
            .size(size * scale)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = alpha))
            .neonGlow(color, 4.dp)
    )
}
