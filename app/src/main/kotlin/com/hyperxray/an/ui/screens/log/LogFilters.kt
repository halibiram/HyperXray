package com.hyperxray.an.ui.screens.log

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        // HUD Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF080808)) // Darker backing
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF202020),
                            Color(0xFF303030),
                            Color(0xFF202020)
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Group 1: Levels
                FilterGroup(label = "LEVEL") {
                    NeonFilterChip(
                        text = "ERR",
                        isSelected = selectedLogLevel == LogLevel.ERROR,
                        activeColor = Color(0xFFFF0055),
                        onClick = { onLogLevelSelected(if (selectedLogLevel == LogLevel.ERROR) null else LogLevel.ERROR) }
                    )
                    NeonFilterChip(
                        text = "WARN",
                        isSelected = selectedLogLevel == LogLevel.WARN,
                        activeColor = Color(0xFFFFD700),
                        onClick = { onLogLevelSelected(if (selectedLogLevel == LogLevel.WARN) null else LogLevel.WARN) }
                    )
                    NeonFilterChip(
                        text = "INFO",
                        isSelected = selectedLogLevel == LogLevel.INFO,
                        activeColor = Color(0xFF00FFFF),
                        onClick = { onLogLevelSelected(if (selectedLogLevel == LogLevel.INFO) null else LogLevel.INFO) }
                    )
                }

                VerticalSeparator()

                // Group 2: Transport
                FilterGroup(label = "NET") {
                    NeonFilterChip(
                        text = "UDP",
                        isSelected = selectedConnectionType == ConnectionType.UDP,
                        activeColor = Color(0xFF00FF99),
                        onClick = { onConnectionTypeSelected(if (selectedConnectionType == ConnectionType.UDP) null else ConnectionType.UDP) }
                    )
                    NeonFilterChip(
                        text = "TCP",
                        isSelected = selectedConnectionType == ConnectionType.TCP,
                        activeColor = Color(0xFF00FF99),
                        onClick = { onConnectionTypeSelected(if (selectedConnectionType == ConnectionType.TCP) null else ConnectionType.TCP) }
                    )
                }

                VerticalSeparator()

                // Group 3: Focus
                FilterGroup(label = "FOCUS") {
                    NeonFilterChip(
                        text = "SNIFF",
                        isSelected = showSniffingOnly,
                        activeColor = Color(0xFFFF9900),
                        onClick = { onShowSniffingOnlyChanged(!showSniffingOnly) }
                    )
                    NeonFilterChip(
                        text = "AI",
                        isSelected = showAiOnly,
                        activeColor = Color(0xFFBD00FF),
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
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            color = Color.Gray.copy(alpha = 0.5f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
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
            .height(24.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFF303030),
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
                color = if (isSelected) activeColor.copy(alpha = 0.6f) else Color(0xFF252525),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
        
        // Inner glow effect when selected
        if (isSelected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                activeColor.copy(alpha = 0.1f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}
