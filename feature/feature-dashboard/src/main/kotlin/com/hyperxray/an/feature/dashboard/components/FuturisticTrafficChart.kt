package com.hyperxray.an.feature.dashboard.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxray.an.feature.dashboard.formatThroughput
import kotlinx.coroutines.delay

/**
 * A futuristic, high-tech traffic chart component.
 * Features neon glowing lines, smooth bezier curves, and a cyber-grid background.
 */
@Composable
fun FuturisticTrafficChart(
    uplinkThroughput: Double,
    downlinkThroughput: Double,
    modifier: Modifier = Modifier
) {
    // Constants for chart configuration
    val maxPoints = 40
    val updateInterval = 200L // ms
    val maxThroughputBase = 1024 * 1024.0 // 1 MB/s baseline, auto-scales

    // State for data history
    var uplinkHistory by remember { mutableStateOf(List(maxPoints) { 0f }) }
    var downlinkHistory by remember { mutableStateOf(List(maxPoints) { 0f }) }
    
    // Auto-scaling max value
    var currentMax by remember { mutableStateOf(maxThroughputBase) }

    // Update logic
    val currentUplink = rememberUpdatedState(uplinkThroughput)
    val currentDownlink = rememberUpdatedState(downlinkThroughput)

    LaunchedEffect(Unit) {
        while (true) {
            val up = currentUplink.value.toFloat()
            val down = currentDownlink.value.toFloat()
            
            // Update history
            uplinkHistory = (uplinkHistory + up).takeLast(maxPoints)
            downlinkHistory = (downlinkHistory + down).takeLast(maxPoints)

            // Dynamic scaling with decay
            val maxInWindow = (uplinkHistory + downlinkHistory).maxOrNull()?.toDouble() ?: maxThroughputBase
            val targetMax = maxOf(maxThroughputBase, maxInWindow * 1.2) // 20% headroom
            
            // Smoothly adjust currentMax
            if (targetMax > currentMax) {
                currentMax = targetMax
            } else {
                currentMax = currentMax * 0.95 + targetMax * 0.05 // Slow decay
            }

            delay(updateInterval)
        }
    }

    // Visual Constants
    val neonCyan = Color(0xFF00E5FF)
    val neonPurple = Color(0xFFD500F9)
    val gridColor = Color(0xFF1A1A1A)
    val cardBg = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF050505),
            Color(0xFF0A0A0A)
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(cardBg)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF222222),
                        Color(0xFF111111)
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(3.dp, 16.dp)
                            .background(neonCyan, RoundedCornerShape(2.dp))
                    )
                    Text(
                        text = "NETWORK TRAFFIC",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        ),
                        color = Color.White.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                // Live Values
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SpeedValue(label = "UP", value = uplinkThroughput, color = neonCyan)
                    SpeedValue(label = "DOWN", value = downlinkThroughput, color = neonPurple)
                }
            }

            // Chart Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF020202))
                    .border(1.dp, Color(0xFF151515), RoundedCornerShape(12.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val stepX = width / (maxPoints - 1)

                    // Draw Grid
                    val gridStepY = height / 4
                    for (i in 1..3) {
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, gridStepY * i),
                            end = Offset(width, gridStepY * i),
                            strokeWidth = 1f
                        )
                    }
                    val gridStepX = width / 8
                    for (i in 1..7) {
                        drawLine(
                            color = gridColor,
                            start = Offset(gridStepX * i, 0f),
                            end = Offset(gridStepX * i, height),
                            strokeWidth = 1f
                        )
                    }

                    // Helper to draw path
                    fun drawTrafficPath(data: List<Float>, color: Color, isFill: Boolean = false) {
                        if (data.isEmpty()) return

                        val path = Path()
                        val points = data.mapIndexed { index, value ->
                            val x = index * stepX
                            val y = height - (value / currentMax.toFloat() * height)
                            Offset(x, y.coerceIn(0f, height))
                        }

                        if (points.isNotEmpty()) {
                            path.moveTo(points.first().x, points.first().y)
                            
                            // Cubic Bezier smoothing
                            for (i in 0 until points.size - 1) {
                                val p0 = points[i]
                                val p1 = points[i + 1]
                                val controlPoint1 = Offset((p0.x + p1.x) / 2, p0.y)
                                val controlPoint2 = Offset((p0.x + p1.x) / 2, p1.y)
                                path.cubicTo(controlPoint1.x, controlPoint1.y, controlPoint2.x, controlPoint2.y, p1.x, p1.y)
                            }
                        }

                        if (isFill) {
                            path.lineTo(width, height)
                            path.lineTo(0f, height)
                            path.close()
                            
                            drawPath(
                                path = path,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        color.copy(alpha = 0.2f),
                                        color.copy(alpha = 0.0f)
                                    ),
                                    startY = 0f,
                                    endY = height
                                )
                            )
                        } else {
                            drawPath(
                                path = path,
                                color = color,
                                style = Stroke(
                                    width = 3.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                            // Glow effect
                            drawPath(
                                path = path,
                                color = color.copy(alpha = 0.4f),
                                style = Stroke(
                                    width = 8.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }

                    // Draw Download (Fill first, then Line)
                    drawTrafficPath(downlinkHistory, neonPurple, isFill = true)
                    drawTrafficPath(downlinkHistory, neonPurple, isFill = false)

                    // Draw Upload (Fill first, then Line)
                    drawTrafficPath(uplinkHistory, neonCyan, isFill = true)
                    drawTrafficPath(uplinkHistory, neonCyan, isFill = false)
                }
            }
        }
    }
}

@Composable
private fun SpeedValue(label: String, value: Double, color: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = color.copy(alpha = 0.8f),
            fontSize = 10.sp
        )
        Text(
            text = formatThroughput(value),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            ),
            color = color
        )
    }
}
