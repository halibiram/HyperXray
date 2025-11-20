package com.hyperxray.an.feature.dashboard.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxray.an.feature.dashboard.DashboardColors
import com.hyperxray.an.xray.runtime.XrayRuntimeStatus

/**
 * A comprehensive card component displaying Xray-core instance statuses.
 * Shows status, PID, API port, and detailed information for each instance.
 * 
 * @param instancesStatus Map of instance index to XrayRuntimeStatus
 * @param modifier Modifier for the card
 */
@Composable
fun InstanceStatusCard(
    instancesStatus: Map<Int, XrayRuntimeStatus>,
    modifier: Modifier = Modifier
) {
    if (instancesStatus.isEmpty()) {
        return
    }
    
    val systemGradient = DashboardColors.systemGradient()
    val successColor = DashboardColors.successColor()
    val errorColor = DashboardColors.errorColor()
    val warningColor = DashboardColors.warningColor()
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF000000).copy(alpha = 0.7f),
                        Color(0xFF0A0A0A).copy(alpha = 0.5f)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(systemGradient),
                shape = RoundedCornerShape(28.dp)
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = systemGradient.map { it.copy(alpha = 0.12f) }
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Instance Status",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp
                        ),
                        color = Color.White
                    )
                    Text(
                        text = "${instancesStatus.size} instance${if (instancesStatus.size > 1) "s" else ""}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = Color(0xFFB0B0B0)
                    )
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Display each instance
                instancesStatus.toList().sortedBy { it.first }.forEach { (index, status) ->
                    InstanceStatusRow(
                        instanceIndex = index,
                        status = status,
                        successColor = successColor,
                        errorColor = errorColor,
                        warningColor = warningColor
                    )
                    if (index < instancesStatus.keys.maxOrNull() ?: 0) {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun InstanceStatusRow(
    instanceIndex: Int,
    status: XrayRuntimeStatus,
    successColor: Color,
    errorColor: Color,
    warningColor: Color
) {
    val (statusColor, statusText, statusDetails) = when (status) {
        is XrayRuntimeStatus.Running -> {
            Triple(
                successColor,
                "Running",
                "PID: ${status.processId} • API Port: ${status.apiPort}"
            )
        }
        is XrayRuntimeStatus.Starting -> {
            Triple(
                warningColor,
                "Starting",
                "Initializing..."
            )
        }
        is XrayRuntimeStatus.Stopping -> {
            Triple(
                warningColor,
                "Stopping",
                "Shutting down..."
            )
        }
        is XrayRuntimeStatus.Error -> {
            Triple(
                errorColor,
                "Error",
                status.message.take(60) + if (status.message.length > 60) "..." else ""
            )
        }
        is XrayRuntimeStatus.ProcessExited -> {
            Triple(
                errorColor,
                "Exited",
                "Exit code: ${status.exitCode}${status.message?.let { " • $it" } ?: ""}"
            )
        }
        is XrayRuntimeStatus.Stopped -> {
            Triple(
                Color(0xFF808080),
                "Stopped",
                "Not running"
            )
        }
    }
    
    val isRunning = status is XrayRuntimeStatus.Running
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, delayMillis = 0)
        ),
        label = "pulse_alpha"
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        statusColor.copy(alpha = 0.15f),
                        statusColor.copy(alpha = 0.08f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        statusColor.copy(alpha = 0.6f),
                        statusColor.copy(alpha = 0.3f)
                    )
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Status indicator with pulse animation for running
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusColor)
                        .alpha(if (isRunning) pulseAlpha else 1f)
                )
                
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Instance $instanceIndex",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = statusColor
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = statusDetails,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB0B0B0),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

