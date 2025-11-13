package com.hyperxray.an.ui.screens.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * A mini line chart component for displaying simple data trends.
 * Creates a smooth line graph from a list of values.
 * 
 * @param dataPoints List of values to plot (0.0 to 1.0 normalized)
 * @param lineColor Color of the line
 * @param fillGradient Optional gradient for area fill
 * @param height Height of the chart
 */
@Composable
fun MiniChart(
    dataPoints: List<Float>,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fillGradient: List<Color>? = null,
    height: androidx.compose.ui.unit.Dp = 60.dp,
    modifier: Modifier = Modifier
) {
    if (dataPoints.isEmpty()) {
        // Show empty chart placeholder
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        )
        return
    }

    // Use dataPoints directly - simpler and more reliable
    // Animation will happen naturally through recomposition
    val currentPoints = dataPoints

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Canvas(modifier = Modifier.fillMaxWidth()) {
            val width = size.width
            val chartHeight = size.height
            
            if (currentPoints.isEmpty() || width <= 0f || chartHeight <= 0f) return@Canvas
            
            val horizontalPadding = 8.dp.toPx().coerceAtMost(width / 4f)
            val verticalPadding = 4.dp.toPx().coerceAtMost(chartHeight / 4f)
            
            // Calculate min/max for normalization with some padding
            val maxValue = currentPoints.maxOrNull() ?: 0f
            val minValue = currentPoints.minOrNull() ?: 0f
            
            // Add small padding to range to prevent clipping at edges
            val valueRange = maxValue - minValue
            val paddingAmount = if (valueRange > 0.01f) valueRange * 0.1f else 0.01f
            val adjustedMin = (minValue - paddingAmount).coerceAtLeast(0f)
            val adjustedMax = maxValue + paddingAmount
            val range = max(adjustedMax - adjustedMin, 0.01f)
            
            val drawableWidth = width - 2 * horizontalPadding
            val drawableHeight = chartHeight - 2 * verticalPadding
            
            // Safety check: if drawable area is too small, skip drawing
            if (drawableWidth <= 1f || drawableHeight <= 1f) return@Canvas
            
            val points = currentPoints.mapIndexed { index, value ->
                // Calculate X position
                val x = if (currentPoints.size > 1) {
                    horizontalPadding + (index.toFloat() / (currentPoints.size - 1)) * drawableWidth
                } else {
                    width / 2f
                }
                
                // Normalize value: map from [adjustedMin, adjustedMax] to [0, 1]
                val normalizedValue = if (range > 0.01f) {
                    ((value - adjustedMin) / range).coerceIn(0f, 1f)
                } else {
                    // If all values are same (or all 0), show at bottom
                    0f
                }
                
                // Calculate Y position: 0 at bottom, 1 at top (inverted)
                val y = chartHeight - verticalPadding - normalizedValue * drawableHeight
                Offset(x.coerceIn(0f, width), y.coerceIn(0f, chartHeight))
            }
            
            if (points.size > 1) {
                val bottomY = chartHeight - verticalPadding
                
                // Draw area fill with gradient
                if (fillGradient != null && fillGradient.isNotEmpty()) {
                    val fillPath = Path().apply {
                        moveTo(points.first().x, bottomY)
                        points.forEach { lineTo(it.x, it.y) }
                        lineTo(points.last().x, bottomY)
                        close()
                    }
                    
                    val minY = points.minOfOrNull { it.y } ?: bottomY
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = fillGradient,
                            startY = minY,
                            endY = bottomY
                        )
                    )
                }
                
                // Draw line - always draw even if all values are 0
                val linePath = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { 
                        lineTo(it.x, it.y)
                    }
                }
                
                drawPath(
                    path = linePath,
                    color = lineColor,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )
            } else if (points.size == 1) {
                // Draw a single point as a circle
                val point = points.first()
                drawCircle(
                    color = lineColor,
                    radius = 3.dp.toPx(),
                    center = Offset(
                        point.x.coerceIn(0f, width),
                        point.y.coerceIn(0f, chartHeight)
                    )
                )
            }
        }
    }
}

