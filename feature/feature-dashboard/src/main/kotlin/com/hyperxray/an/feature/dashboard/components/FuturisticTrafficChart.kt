package com.hyperxray.an.feature.dashboard.components

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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxray.an.feature.dashboard.formatThroughput
import kotlinx.coroutines.delay
import kotlin.math.sin

private object ChartColors {
    val Cyan = Color(0xFF00F5FF)
    val Magenta = Color(0xFFFF00FF)
    val Purple = Color(0xFF8B5CF6)
    val Green = Color(0xFF00FF88)
    val DeepSpace = Color(0xFF000011)
    val GridLine = Color(0xFF1A1A2E)
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
                    ChartColors.Cyan.copy(alpha = 0.1f),
                    ChartColors.Magenta.copy(alpha = 0.15f),
                    ChartColors.Purple.copy(alpha = 0.1f),
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
fun FuturisticTrafficChart(
    uplinkThroughput: Double,
    downlinkThroughput: Double,
    modifier: Modifier = Modifier
) {
    val maxPoints = 50
    val updateInterval = 150L
    val maxThroughputBase = 1024 * 1024.0

    var uplinkHistory by remember { mutableStateOf(List(maxPoints) { 0f }) }
    var downlinkHistory by remember { mutableStateOf(List(maxPoints) { 0f }) }
    var currentMax by remember { mutableStateOf(maxThroughputBase) }

    val currentUplink = rememberUpdatedState(uplinkThroughput)
    val currentDownlink = rememberUpdatedState(downlinkThroughput)

    val infiniteTransition = rememberInfiniteTransition(label = "chart")
    
    // Animated scan line
    val scanLineOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanLine"
    )
    
    // Pulsing glow
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    LaunchedEffect(Unit) {
        while (true) {
            val up = currentUplink.value.toFloat()
            val down = currentDownlink.value.toFloat()
            
            uplinkHistory = (uplinkHistory + up).takeLast(maxPoints)
            downlinkHistory = (downlinkHistory + down).takeLast(maxPoints)

            val maxInWindow = (uplinkHistory + downlinkHistory).maxOrNull()?.toDouble() ?: maxThroughputBase
            val targetMax = maxOf(maxThroughputBase, maxInWindow * 1.3)
            
            currentMax = if (targetMax > currentMax) targetMax else currentMax * 0.97 + targetMax * 0.03

            delay(updateInterval)
        }
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
                    colors = listOf(ChartColors.Cyan, ChartColors.Magenta, ChartColors.Purple, ChartColors.Cyan)
                ),
                shape = RoundedCornerShape(28.dp)
            )
            .holographicShimmer()
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ChartColors.Cyan.copy(alpha = glowAlpha * 0.1f),
                            Color.Transparent
                        ),
                        radius = size.maxDimension
                    )
                )
            }
            .padding(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Animated indicator bar
                    Box(
                        modifier = Modifier
                            .size(4.dp, 24.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(ChartColors.Cyan, ChartColors.Magenta)
                                )
                            )
                    )
                    Column {
                        Box {
                            Text(
                                text = "NETWORK TRAFFIC",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 3.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = ChartColors.Cyan.copy(alpha = 0.3f),
                                modifier = Modifier.blur(4.dp)
                            )
                            Text(
                                text = "NETWORK TRAFFIC",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 3.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = Color.White
                            )
                        }
                        Text(
                            text = "REAL-TIME MONITORING",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp
                            ),
                            color = Color(0xFF606080)
                        )
                    }
                }
                
                // Live speed indicators
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    SpeedIndicator("▲ UP", uplinkThroughput, ChartColors.Cyan)
                    SpeedIndicator("▼ DOWN", downlinkThroughput, ChartColors.Magenta)
                }
            }


            // Chart Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF020208))
                    .border(1.dp, Color(0xFF1A1A2E), RoundedCornerShape(16.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    val width = size.width
                    val height = size.height
                    val stepX = width / (maxPoints - 1)

                    // Draw animated grid
                    val gridStepY = height / 5
                    for (i in 1..4) {
                        val alpha = 0.15f + (sin(scanLineOffset * Math.PI * 2 + i * 0.5).toFloat() + 1) * 0.05f
                        drawLine(
                            color = ChartColors.GridLine.copy(alpha = alpha),
                            start = Offset(0f, gridStepY * i),
                            end = Offset(width, gridStepY * i),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                        )
                    }
                    
                    val gridStepX = width / 10
                    for (i in 1..9) {
                        drawLine(
                            color = ChartColors.GridLine.copy(alpha = 0.1f),
                            start = Offset(gridStepX * i, 0f),
                            end = Offset(gridStepX * i, height),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))
                        )
                    }

                    // Scan line effect
                    val scanX = scanLineOffset * width
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                ChartColors.Cyan.copy(alpha = 0.5f),
                                Color.Transparent
                            ),
                            startX = scanX - 50f,
                            endX = scanX + 50f
                        ),
                        start = Offset(scanX, 0f),
                        end = Offset(scanX, height),
                        strokeWidth = 2f
                    )

                    // Draw traffic paths
                    fun drawTrafficPath(data: List<Float>, color: Color, isFill: Boolean = false) {
                        if (data.isEmpty()) return

                        val path = Path()
                        val points = data.mapIndexed { index, value ->
                            val x = index * stepX
                            val y = height - (value / currentMax.toFloat() * height * 0.9f)
                            Offset(x, y.coerceIn(10f, height - 10f))
                        }

                        if (points.isNotEmpty()) {
                            path.moveTo(points.first().x, points.first().y)
                            
                            for (i in 0 until points.size - 1) {
                                val p0 = points[i]
                                val p1 = points[i + 1]
                                val controlPoint1 = Offset((p0.x + p1.x) / 2, p0.y)
                                val controlPoint2 = Offset((p0.x + p1.x) / 2, p1.y)
                                path.cubicTo(controlPoint1.x, controlPoint1.y, controlPoint2.x, controlPoint2.y, p1.x, p1.y)
                            }
                        }

                        if (isFill) {
                            val fillPath = Path().apply {
                                addPath(path)
                                lineTo(width, height)
                                lineTo(0f, height)
                                close()
                            }
                            drawPath(
                                path = fillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        color.copy(alpha = 0.25f),
                                        color.copy(alpha = 0.05f),
                                        Color.Transparent
                                    ),
                                    startY = 0f,
                                    endY = height
                                )
                            )
                        } else {
                            // Glow effect
                            drawPath(
                                path = path,
                                color = color.copy(alpha = 0.3f),
                                style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                            // Main line
                            drawPath(
                                path = path,
                                brush = Brush.horizontalGradient(
                                    colors = listOf(color.copy(alpha = 0.6f), color, color.copy(alpha = 0.6f))
                                ),
                                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }

                    // Draw download first (background)
                    drawTrafficPath(downlinkHistory, ChartColors.Magenta, isFill = true)
                    drawTrafficPath(downlinkHistory, ChartColors.Magenta, isFill = false)

                    // Draw upload (foreground)
                    drawTrafficPath(uplinkHistory, ChartColors.Cyan, isFill = true)
                    drawTrafficPath(uplinkHistory, ChartColors.Cyan, isFill = false)
                    
                    // Draw current value dots
                    if (uplinkHistory.isNotEmpty()) {
                        val lastUpY = height - (uplinkHistory.last() / currentMax.toFloat() * height * 0.9f)
                        drawCircle(ChartColors.Cyan.copy(alpha = 0.5f), 12f, Offset(width, lastUpY.coerceIn(10f, height - 10f)))
                        drawCircle(ChartColors.Cyan, 6f, Offset(width, lastUpY.coerceIn(10f, height - 10f)))
                    }
                    if (downlinkHistory.isNotEmpty()) {
                        val lastDownY = height - (downlinkHistory.last() / currentMax.toFloat() * height * 0.9f)
                        drawCircle(ChartColors.Magenta.copy(alpha = 0.5f), 12f, Offset(width, lastDownY.coerceIn(10f, height - 10f)))
                        drawCircle(ChartColors.Magenta, 6f, Offset(width, lastDownY.coerceIn(10f, height - 10f)))
                    }
                }
            }
            
            // Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendItem("UPLOAD", ChartColors.Cyan)
                Spacer(modifier = Modifier.width(32.dp))
                LegendItem("DOWNLOAD", ChartColors.Magenta)
            }
        }
    }
}


@Composable
private fun SpeedIndicator(
    label: String,
    value: Double,
    color: Color
) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            ),
            color = color.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box {
            Text(
                text = formatThroughput(value),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                ),
                color = color.copy(alpha = 0.3f),
                modifier = Modifier.blur(3.dp)
            )
            Text(
                text = formatThroughput(value),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                ),
                color = color
            )
        }
    }
}

@Composable
private fun LegendItem(
    label: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            ),
            color = Color(0xFF808090)
        )
    }
}
