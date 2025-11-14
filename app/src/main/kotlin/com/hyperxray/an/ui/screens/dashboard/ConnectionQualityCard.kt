package com.hyperxray.an.ui.screens.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A card component displaying connection quality metrics.
 * Shows visual indicators for connection health.
 * 
 * @param rtt Average round-trip time in milliseconds
 * @param packetLoss Packet loss percentage
 * @param handshakeTime Average handshake time in milliseconds
 */
@Composable
fun ConnectionQualityCard(
    rtt: Double,
    packetLoss: Double,
    handshakeTime: Double,
    modifier: Modifier = Modifier
) {
    var isVisible = remember { mutableStateOf(false) }
    
    val cardScale by animateFloatAsState(
        targetValue = if (isVisible.value) 1f else 0.95f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 300f
        ),
        label = "card_scale"
    )
    
    val cardAlpha by animateFloatAsState(
        targetValue = if (isVisible.value) 1f else 0f,
        animationSpec = tween(300),
        label = "card_alpha"
    )

    LaunchedEffect(Unit) {
        if (!isVisible.value) {
            isVisible.value = true
        }
    }
    
    // Determine quality based on metrics
    val quality = remember(rtt, packetLoss, handshakeTime) {
        when {
            rtt < 50 && packetLoss < 0.5 && handshakeTime < 100 -> "Excellent"
            rtt < 100 && packetLoss < 1.0 && handshakeTime < 200 -> "Good"
            rtt < 200 && packetLoss < 2.0 && handshakeTime < 300 -> "Fair"
            else -> "Poor"
        }
    }
    
    val qualityColor = remember(quality) {
        when (quality) {
            "Excellent" -> Color(0xFF10B981)
            "Good" -> Color(0xFF3B82F6)
            "Fair" -> Color(0xFFF59E0B)
            else -> Color(0xFFEF4444)
        }
    }
    
    val qualityGradient = remember(qualityColor) {
        listOf(
            qualityColor,
            qualityColor.copy(alpha = 0.7f),
            qualityColor.copy(alpha = 0.5f)
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(cardScale)
            .alpha(cardAlpha)
            .clip(RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 8.dp,
            pressedElevation = 12.dp,
            hoveredElevation = 10.dp
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = qualityGradient.map { it.copy(alpha = 0.1f) }
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Connection Quality",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    // Quality Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(qualityColor)
                        )
                        Text(
                            text = quality,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = qualityColor
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Quality Metrics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QualityMetric(
                        label = "RTT",
                        value = "${rtt.toInt()}ms",
                        color = if (rtt < 100) Color(0xFF10B981) else if (rtt < 200) Color(0xFFF59E0B) else Color(0xFFEF4444)
                    )
                    QualityMetric(
                        label = "Loss",
                        value = "${String.format("%.2f", packetLoss)}%",
                        color = if (packetLoss < 1.0) Color(0xFF10B981) else if (packetLoss < 2.0) Color(0xFFF59E0B) else Color(0xFFEF4444)
                    )
                    QualityMetric(
                        label = "Handshake",
                        value = "${handshakeTime.toInt()}ms",
                        color = if (handshakeTime < 200) Color(0xFF10B981) else if (handshakeTime < 300) Color(0xFFF59E0B) else Color(0xFFEF4444)
                    )
                }
            }
        }
    }
}

@Composable
private fun QualityMetric(
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
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

