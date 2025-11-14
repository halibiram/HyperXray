package com.hyperxray.an.feature.dashboard.components

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
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.max

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
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            )
    ) {
        Canvas(modifier = Modifier.fillMaxWidth()) {
            val width = size.width
            val chartHeight = size.height
            
            if (currentPoints.isEmpty() || width <= 0f || chartHeight <= 0f) return@Canvas
            
            val horizontalPadding = 8.dp.toPx().coerceAtMost(width / 4f)
            val verticalPadding = 4.dp.toPx().coerceAtMost(chartHeight / 4f)
            
            // Calculate min/max for normalization with better handling for zero/very small values
            val maxValue = currentPoints.maxOrNull() ?: 0.01f
            val minValue = currentPoints.minOrNull() ?: 0f
            
            // For better visibility: if max is very small, use a reference scale
            // This ensures chart is always visible and scales properly
            val referenceMax = if (maxValue < 0.05f) {
                // If values are very small, use 0.1 as reference (10% of max scale)
                0.1f
            } else {
                // Otherwise use actual max with some padding
                maxValue * 1.1f
            }
            
            val adjustedMin = 0f
            val adjustedMax = referenceMax
            val range = max(adjustedMax - adjustedMin, 0.1f) // Minimum range for visibility
            
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
                // Ensure minimum baseline visibility for very small values
                val normalizedValue = if (range > 0.01f) {
                    val normalized = ((value - adjustedMin) / range).coerceIn(0f, 1f)
                    // If value is very small but not zero, show at least 3% from bottom for visibility
                    if (value > 0f && normalized < 0.03f) {
                        0.03f
                    } else {
                        normalized
                    }
                } else {
                    // Fallback: show at 3% from bottom
                    0.03f
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
                
                // Draw line with smooth appearance
                drawPath(
                    path = linePath,
                    color = lineColor,
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )
            } else if (points.size == 1) {
                // Draw a single point as a simple circle
                val point = points.first()
                val center = Offset(
                    point.x.coerceIn(0f, width),
                    point.y.coerceIn(0f, chartHeight)
                )
                drawCircle(
                    color = lineColor,
                    radius = 3.dp.toPx(),
                    center = center,
                    style = Fill
                )
            }
        }
    }
}

