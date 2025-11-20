package com.hyperxray.an.ui.screens.log

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxray.an.ui.theme.LogColors

@Composable
fun LogLevelBadge(level: LogLevel) {
    val color = when (level) {
        LogLevel.ERROR -> Color(0xFFFF0055)
        LogLevel.WARN -> Color(0xFFFFD700)
        LogLevel.INFO -> Color(0xFF00FFFF)
        LogLevel.DEBUG -> Color(0xFF00FF99)
        LogLevel.UNKNOWN -> Color.Gray
    }
    
    CyberBadge(text = level.name, color = color)
}

@Composable
fun ConnectionTypeBadge(type: ConnectionType) {
    val color = when (type) {
        ConnectionType.TCP -> Color(0xFF00FF99)
        ConnectionType.UDP -> Color(0xFF00FFFF)
        ConnectionType.UNKNOWN -> Color.Gray
    }
    
    CyberBadge(text = type.name, color = color)
}

@Composable
fun SNIBadge() {
    CyberBadge(text = "SNI", color = Color(0xFFBD00FF))
}

@Composable
fun SniffingBadge() {
    CyberBadge(text = "SNIFF", color = Color(0xFFFF9900))
}

@Composable
fun DnsBadge() {
    CyberBadge(text = "DNS", color = Color(0xFF00FFFF))
}

@Composable
fun CyberBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.5f), CutCornerShape(4.dp))
            .background(color.copy(alpha = 0.1f), CutCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp
        )
    }
}
