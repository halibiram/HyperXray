package com.hyperxray.an.ui.screens.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hyperxray.an.R
import com.hyperxray.an.common.formatUptime

/**
 * A hero card component displaying VPN connection status with toggle button.
 * Features gradient styling based on connection state and animated status indicator.
 * 
 * @param isConnected Whether the VPN service is currently connected
 * @param isClickable Whether the toggle button is enabled
 * @param onToggleConnection Callback invoked when the toggle button is clicked
 * @param uptime Connection uptime in seconds (only displayed when connected)
 */
@Composable
fun ConnectionStatusCard(
    isConnected: Boolean,
    isClickable: Boolean,
    onToggleConnection: () -> Unit,
    uptime: Int,
    modifier: Modifier = Modifier
) {
    // Cache gradient colors to avoid recreation
    val connectedGradient = remember { 
        listOf(Color(0xFF10B981), Color(0xFF059669), Color(0xFF047857))
    }
    val disconnectedGradient = remember { 
        listOf(Color(0xFF6B7280), Color(0xFF4B5563), Color(0xFF374151))
    }
    
    val statusGradient = remember(isConnected) {
        if (isConnected) connectedGradient else disconnectedGradient
    }
    
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
        animationSpec = tween(400),
        label = "card_alpha"
    )
    
    val indicatorScale by animateFloatAsState(
        targetValue = if (isConnected && isVisible.value) 1f else 0.8f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 400f
        ),
        label = "indicator_scale"
    )

    LaunchedEffect(Unit) {
        if (!isVisible.value) {
            isVisible.value = true
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(cardScale)
            .alpha(cardAlpha)
            .clip(RoundedCornerShape(32.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 16.dp,
            pressedElevation = 20.dp,
            hoveredElevation = 18.dp
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = statusGradient.map { it.copy(alpha = 0.15f) }
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Status Indicator with enhanced styling
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(96.dp)
                ) {
                    // Outer glow ring
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .scale(indicatorScale * 1.15f)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        statusGradient.first().copy(alpha = 0.2f),
                                        Color.Transparent
                                    ),
                                    center = androidx.compose.ui.geometry.Offset(48f, 48f),
                                    radius = 50f
                                )
                            )
                    )
                    // Main indicator
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .scale(indicatorScale)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = statusGradient,
                                    center = androidx.compose.ui.geometry.Offset(40f, 40f),
                                    radius = 40f
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(
                                id = if (isConnected) R.drawable.play else R.drawable.pause
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Status Text
                Text(
                    text = if (isConnected) "Connected" else "Disconnected",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                if (isConnected) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Uptime: ${formatUptime(uptime)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Toggle Button with enhanced styling
                Button(
                    onClick = onToggleConnection,
                    enabled = isClickable,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) {
                            Color(0xFFEF4444)
                        } else {
                            Color(0xFF10B981)
                        },
                        contentColor = Color.White,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(18.dp),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 2.dp,
                        disabledElevation = 0.dp
                    )
                ) {
                    if (!isClickable) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                    }
                    Text(
                        text = if (isConnected) "Disconnect" else "Connect",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

