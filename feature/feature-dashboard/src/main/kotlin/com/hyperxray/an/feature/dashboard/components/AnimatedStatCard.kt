package com.hyperxray.an.feature.dashboard.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * An animated stat card with staggered entrance animation.
 * Each card appears with a delay based on its index for a cascading effect.
 * 
 * @param title The title text displayed in the card header
 * @param iconRes Optional drawable resource ID for the icon
 * @param gradientColors List of colors for the gradient effect
 * @param content The composable content to display inside the card
 * @param animationDelay Delay in milliseconds before animation starts (for staggered effect)
 * @param headerAction Optional composable action to display in the header (e.g., clear button)
 */
@Composable
fun AnimatedStatCard(
    title: String,
    iconRes: Int? = null,
    gradientColors: List<Color>,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    animationDelay: Long = 0,
    headerAction: (@Composable () -> Unit)? = null
) {
    // Use key to prevent re-animation on recomposition
    val animationKey = remember(title, iconRes) { "${title}_${iconRes}" }
    var isVisible = remember(animationKey) { mutableStateOf(false) }
    
    val scale by animateFloatAsState(
        targetValue = if (isVisible.value) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 300f
        ),
        label = "card_scale"
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (isVisible.value) 1f else 0f,
        animationSpec = tween(
            durationMillis = 400,
            delayMillis = animationDelay.toInt()
        ),
        label = "card_alpha"
    )

    LaunchedEffect(animationKey) {
        if (!isVisible.value) {
            if (animationDelay > 0) {
                delay(animationDelay)
            }
            isVisible.value = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .alpha(alpha)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF000000).copy(alpha = 0.7f), // Obsidian glass
                        Color(0xFF0A0A0A).copy(alpha = 0.5f)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(gradientColors),
                shape = RoundedCornerShape(28.dp)
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = gradientColors.map { it.copy(alpha = 0.12f) } // Enhanced neon tint
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (iconRes != null) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Brush.linearGradient(gradientColors)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    colorFilter = ColorFilter.tint(Color.White)
                                )
                            }
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.3).sp
                            ),
                            color = Color.White // High contrast on obsidian
                        )
                    }
                    // Header action (e.g., clear cache button)
                    headerAction?.invoke()
                }
                Spacer(modifier = Modifier.height(20.dp))
                content()
            }
        }
    }
}

