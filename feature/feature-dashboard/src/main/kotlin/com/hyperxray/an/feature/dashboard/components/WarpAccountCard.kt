package com.hyperxray.an.feature.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxray.an.feature.dashboard.DashboardColors

/**
 * Futuristic WARP Account card component
 * Displays Cloudflare WARP account information with modern glassmorphism design
 */
@Composable
fun WarpAccountCard(
    accountExists: Boolean,
    publicKey: String? = null,
    endpoint: String? = null,
    accountType: String? = null,
    license: String? = null,
    warpEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    val warpGradient = DashboardColors.warpGradient()
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
                        Color(0xFF000000).copy(alpha = 0.7f), // Obsidian glass base
                        Color(0xFF0A0A0A).copy(alpha = 0.5f)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(warpGradient),
                shape = RoundedCornerShape(28.dp)
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = warpGradient.map { it.copy(alpha = 0.12f) } // Enhanced neon tint
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header with icon and title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // WARP Icon Box
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(warpGradient)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            // Cloudflare WARP symbol (simplified as text for now)
                            Text(
                                text = "W",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color.White
                            )
                        }
                        Column {
                            Text(
                                text = "WARP Account",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.3).sp
                                ),
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (accountExists) "Cloudflare WARP" else "No account found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (accountExists) successColor else Color(0xFF808080)
                            )
                        }
                    }
                    
                    // Status indicator
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (accountExists) {
                                    if (warpEnabled) successColor else warningColor
                                } else {
                                    Color(0xFF404040)
                                }
                            )
                    )
                }
                
                // Account details (only show if account exists)
                AnimatedVisibility(
                    visible = accountExists,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Account Type Badge
                        accountType?.let { type ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                warpGradient[0].copy(alpha = 0.15f),
                                                warpGradient[1].copy(alpha = 0.1f)
                                            )
                                        )
                                    )
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(warpGradient[0])
                                )
                                Text(
                                    text = "Type: $type",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = warpGradient[0]
                                )
                            }
                        }
                        
                        // License Status
                        license?.let {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                successColor.copy(alpha = 0.15f),
                                                successColor.copy(alpha = 0.1f)
                                            )
                                        )
                                    )
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(successColor)
                                )
                                Text(
                                    text = "WARP+ Licensed",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = successColor
                                )
                            }
                        } ?: run {
                            if (warpEnabled) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(
                                                    warningColor.copy(alpha = 0.15f),
                                                    warningColor.copy(alpha = 0.1f)
                                                )
                                            )
                                        )
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(warningColor)
                                    )
                                    Text(
                                        text = "Free WARP",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = warningColor
                                    )
                                }
                            }
                        }
                        
                        // Public Key (truncated)
                        publicKey?.let { key ->
                            InfoRow(
                                label = "Public Key",
                                value = if (key.length > 32) {
                                    "${key.take(16)}...${key.takeLast(8)}"
                                } else {
                                    key
                                },
                                color = warpGradient[1]
                            )
                        }
                        
                        // Endpoint
                        endpoint?.let { ep ->
                            InfoRow(
                                label = "Endpoint",
                                value = ep,
                                color = warpGradient[2]
                            )
                        }
                    }
                }
                
                // No account message
                AnimatedVisibility(
                    visible = !accountExists,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFF1A1A1A).copy(alpha = 0.6f),
                                        Color(0xFF0F0F0F).copy(alpha = 0.4f)
                                    )
                                )
                            )
                            .border(
                                1.dp,
                                Color(0xFF2A2A2A),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "No WARP Account",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Medium
                                ),
                                color = Color(0xFF808080)
                            )
                            Text(
                                text = "Register a new account or load existing configuration",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF606060)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        color.copy(alpha = 0.1f),
                        color.copy(alpha = 0.05f)
                    )
                )
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF808080)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium
            ),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(200.dp)
        )
    }
}

