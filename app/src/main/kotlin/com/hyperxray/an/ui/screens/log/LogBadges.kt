package com.hyperxray.an.ui.screens.log

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LogLevelBadge(level: LogLevel) {
    val color = LogColorPalette.getLogLevelColor(level)
    CyberBadge(text = level.name, color = color)
}

@Composable
fun ConnectionTypeBadge(type: ConnectionType) {
    val color = LogColorPalette.getConnectionTypeColor(type)
    CyberBadge(text = type.name, color = color)
}

@Composable
fun SNIBadge() {
    CyberBadge(text = "SNI", color = LogColorPalette.NeonMagenta)
}

@Composable
fun SniffingBadge() {
    CyberBadge(text = "SNIFF", color = LogColorPalette.NeonOrange)
}

@Composable
fun DnsBadge() {
    CyberBadge(text = "DNS", color = LogColorPalette.NeonCyan)
}

@Composable
fun AiBadge() {
    CyberBadge(text = "AI", color = LogColorPalette.NeonPurple)
}

@Composable
fun CyberBadge(
    text: String,
    color: Color,
    animated: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "badge")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    
    val effectiveAlpha = if (animated) glowAlpha else 0.5f

    Box(
        modifier = Modifier
            .clip(CutCornerShape(4.dp))
            .background(color.copy(alpha = 0.1f))
            .border(
                width = 1.dp,
                color = color.copy(alpha = effectiveAlpha),
                shape = CutCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun GlowingBadge(
    text: String,
    color: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "glowBadge")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        color.copy(alpha = 0.15f),
                        color.copy(alpha = 0.1f),
                        color.copy(alpha = 0.15f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        color.copy(alpha = glowAlpha * 0.5f),
                        color.copy(alpha = glowAlpha),
                        color.copy(alpha = glowAlpha * 0.5f)
                    )
                ),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color.copy(alpha = glowAlpha + 0.2f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun StatusBadge(
    text: String,
    isActive: Boolean,
    activeColor: Color = LogColorPalette.NeonGreen,
    inactiveColor: Color = Color.Gray
) {
    val color = if (isActive) activeColor else inactiveColor
    
    val infiniteTransition = rememberInfiniteTransition(label = "status")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    val effectiveAlpha = if (isActive) pulseAlpha else 0.5f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        // Pulsing dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color.copy(alpha = effectiveAlpha))
        )
        
        Text(
            text = text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp
        )
    }
}

@Composable
fun CountBadge(
    count: Int,
    color: Color = LogColorPalette.NeonCyan
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 999) "999+" else count.toString(),
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp
        )
    }
}
