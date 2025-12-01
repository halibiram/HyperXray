package com.hyperxray.an.ui.screens.log

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
fun LogFilters(
    selectedLogLevel: LogLevel?,
    selectedConnectionType: ConnectionType?,
    showSniffingOnly: Boolean,
    showAiOnly: Boolean,
    onLogLevelSelected: (LogLevel?) -> Unit,
    onConnectionTypeSelected: (ConnectionType?) -> Unit,
    onShowSniffingOnlyChanged: (Boolean) -> Unit,
    onShowAiOnlyChanged: (Boolean) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "filters")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "border"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // HUD Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF080808),
                            Color(0xFF0A0A0D),
                            Color(0xFF080808)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            LogColorPalette.NeonCyan.copy(alpha = borderAlpha),
                            Color(0xFF202025),
                            LogColorPalette.NeonPurple.copy(alpha = borderAlpha)
                        )
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Group 1: Levels
                FilterGroup(label = "LEVEL") {
                    NeonFilterChip(
                        text = "ERR",
                        isSelected = selectedLogLevel == LogLevel.ERROR,
                        activeColor = LogColorPalette.NeonRed,
                        onClick = { onLogLevelSelected(if (selectedLogLevel == LogLevel.ERROR) null else LogLevel.ERROR) }
                    )
                    NeonFilterChip(
                        text = "WARN",
                        isSelected = selectedLogLevel == LogLevel.WARN,
                        activeColor = LogColorPalette.NeonYellow,
                        onClick = { onLogLevelSelected(if (selectedLogLevel == LogLevel.WARN) null else LogLevel.WARN) }
                    )
                    NeonFilterChip(
                        text = "INFO",
                        isSelected = selectedLogLevel == LogLevel.INFO,
                        activeColor = LogColorPalette.NeonCyan,
                        onClick = { onLogLevelSelected(if (selectedLogLevel == LogLevel.INFO) null else LogLevel.INFO) }
                    )
                    NeonFilterChip(
                        text = "DBG",
                        isSelected = selectedLogLevel == LogLevel.DEBUG,
                        activeColor = LogColorPalette.NeonGreen,
                        onClick = { onLogLevelSelected(if (selectedLogLevel == LogLevel.DEBUG) null else LogLevel.DEBUG) }
                    )
                }

                VerticalSeparator()

                // Group 2: Transport
                FilterGroup(label = "NETWORK") {
                    NeonFilterChip(
                        text = "TCP",
                        isSelected = selectedConnectionType == ConnectionType.TCP,
                        activeColor = LogColorPalette.NeonGreen,
                        onClick = { onConnectionTypeSelected(if (selectedConnectionType == ConnectionType.TCP) null else ConnectionType.TCP) }
                    )
                    NeonFilterChip(
                        text = "UDP",
                        isSelected = selectedConnectionType == ConnectionType.UDP,
                        activeColor = LogColorPalette.NeonBlue,
                        onClick = { onConnectionTypeSelected(if (selectedConnectionType == ConnectionType.UDP) null else ConnectionType.UDP) }
                    )
                }

                VerticalSeparator()

                // Group 3: Focus
                FilterGroup(label = "FOCUS") {
                    NeonFilterChip(
                        text = "SNIFF",
                        isSelected = showSniffingOnly,
                        activeColor = LogColorPalette.NeonOrange,
                        onClick = { onShowSniffingOnlyChanged(!showSniffingOnly) }
                    )
                    NeonFilterChip(
                        text = "AI",
                        isSelected = showAiOnly,
                        activeColor = LogColorPalette.NeonPurple,
                        onClick = { onShowAiOnlyChanged(!showAiOnly) }
                    )
                }
            }
        }
    }
}

@Composable
fun FilterGroup(
    label: String,
    content: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            color = Color.Gray.copy(alpha = 0.5f),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 2.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            content()
        }
    }
}

@Composable
fun VerticalSeparator() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(32.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFF252530),
                        Color.Transparent
                    )
                )
            )
    )
}

@Composable
fun NeonFilterChip(
    text: String,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "chip")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    
    val animatedColor by animateColorAsState(
        targetValue = if (isSelected) activeColor else Color(0xFF404040),
        animationSpec = tween(300),
        label = "color"
    )
    
    val animatedBg by animateColorAsState(
        targetValue = if (isSelected) activeColor.copy(alpha = 0.15f) else Color.Transparent,
        animationSpec = tween(300),
        label = "bg"
    )

    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .clip(RoundedCornerShape(8.dp))
            .background(animatedBg)
            .border(
                width = 1.dp,
                color = if (isSelected) activeColor.copy(alpha = glowAlpha) else Color(0xFF202025),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) activeColor else Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp
        )
    }
}
