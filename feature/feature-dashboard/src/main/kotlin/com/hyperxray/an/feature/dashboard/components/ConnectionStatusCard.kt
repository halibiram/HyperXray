package com.hyperxray.an.feature.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.hyperxray.an.feature.dashboard.ConnectionState
import com.hyperxray.an.feature.dashboard.ConnectionStage
import com.hyperxray.an.feature.dashboard.DisconnectionStage
import com.hyperxray.an.feature.dashboard.formatUptime

/**
 * A modern hero card component displaying VPN connection status with detailed connection stages.
 * Features gradient styling, animated status indicators, and step-by-step connection progress.
 * 
 * @param connectionState Current connection state (Disconnected, Connecting, Connected, Disconnecting)
 * @param isClickable Whether the toggle button is enabled
 * @param onToggleConnection Callback invoked when the toggle button is clicked
 * @param uptime Connection uptime in seconds (only displayed when connected)
 * @param playIconRes Resource ID for play icon
 * @param pauseIconRes Resource ID for pause icon
 */
@Composable
fun ConnectionStatusCard(
    connectionState: ConnectionState,
    isClickable: Boolean,
    onToggleConnection: () -> Unit,
    uptime: Int,
    playIconRes: Int,
    pauseIconRes: Int,
    modifier: Modifier = Modifier
) {
    // Determine connection state properties
    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting
    val isDisconnecting = connectionState is ConnectionState.Disconnecting
    val connectingStage = when (connectionState) {
        is ConnectionState.Connecting -> connectionState.stage
        else -> null
    }
    val connectingProgress = when (connectionState) {
        is ConnectionState.Connecting -> connectionState.progress
        else -> 0f
    }
    val disconnectingStage = when (connectionState) {
        is ConnectionState.Disconnecting -> connectionState.stage
        else -> null
    }
    val disconnectingProgress = when (connectionState) {
        is ConnectionState.Disconnecting -> connectionState.progress
        else -> 0f
    }
    
    // Cache gradient colors to avoid recreation
    val connectedGradient = remember { 
        listOf(Color(0xFF10B981), Color(0xFF059669), Color(0xFF047857))
    }
    val connectingGradient = remember {
        listOf(Color(0xFF3B82F6), Color(0xFF2563EB), Color(0xFF1D4ED8))
    }
    val disconnectedGradient = remember { 
        listOf(Color(0xFF6B7280), Color(0xFF4B5563), Color(0xFF374151))
    }
    val disconnectingGradient = remember {
        listOf(Color(0xFFF59E0B), Color(0xFFD97706), Color(0xFFB45309))
    }
    
    val statusGradient = remember(connectionState) {
        when (connectionState) {
            is ConnectionState.Connected -> connectedGradient
            is ConnectionState.Connecting -> connectingGradient
            is ConnectionState.Disconnecting -> disconnectingGradient
            is ConnectionState.Disconnected -> disconnectedGradient
        }
    }
    
    // Card is always visible - no need for visibility state
    val cardScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 300f
        ),
        label = "card_scale"
    )
    
    val cardAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(400),
        label = "card_alpha"
    )
    
    val indicatorScale by animateFloatAsState(
        targetValue = if (isConnected) 1f else 0.8f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 400f
        ),
        label = "indicator_scale"
    )
    
    // Pulse animation for connecting/disconnecting state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, delayMillis = 0),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

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
                    // Outer glow ring with pulse animation for connecting
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .scale(indicatorScale * 1.15f)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        statusGradient.first().copy(
                                            alpha = if (isConnecting || isDisconnecting) pulseAlpha else 0.2f
                                        ),
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
                        if (isConnecting || isDisconnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                color = Color.White,
                                strokeWidth = 3.dp
                            )
                        } else {
                            Image(
                                painter = painterResource(
                                    id = if (isConnected) playIconRes else pauseIconRes
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                colorFilter = ColorFilter.tint(Color.White)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Status Text with animation
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = when (connectionState) {
                                is ConnectionState.Connected -> "Connected"
                                is ConnectionState.Connecting -> connectingStage?.displayName ?: "Connecting"
                                is ConnectionState.Disconnecting -> disconnectingStage?.displayName ?: "Disconnecting"
                                is ConnectionState.Disconnected -> "Disconnected"
                            },
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        // Connection stage description
                        AnimatedVisibility(
                            visible = isConnecting && connectingStage != null,
                            enter = fadeIn() + slideInVertically(),
                            exit = fadeOut() + slideOutVertically()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = connectingStage?.description ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                                
                                // Progress bar for connecting state
                                Spacer(modifier = Modifier.height(16.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        )
                                ) {
                                    LinearProgressIndicator(
                                        progress = { connectingProgress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = statusGradient.first(),
                                        trackColor = Color.Transparent
                                    )
                                }
                                
                                // Connection stages list
                                if (connectingStage != null) {
                                    Spacer(modifier = Modifier.height(20.dp))
                                    ConnectionStagesList(
                                        currentStage = connectingStage,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                        
                        // Disconnection stage description
                        AnimatedVisibility(
                            visible = isDisconnecting && disconnectingStage != null,
                            enter = fadeIn() + slideInVertically(),
                            exit = fadeOut() + slideOutVertically()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = disconnectingStage?.description ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                                
                                // Progress bar for disconnecting state
                                Spacer(modifier = Modifier.height(16.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        )
                                ) {
                                    LinearProgressIndicator(
                                        progress = { disconnectingProgress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = statusGradient.first(),
                                        trackColor = Color.Transparent
                                    )
                                }
                                
                                // Disconnection stages list
                                if (disconnectingStage != null) {
                                    Spacer(modifier = Modifier.height(20.dp))
                                    DisconnectionStagesList(
                                        currentStage = disconnectingStage,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                        
                        // Uptime display - always visible
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusGradient.first())
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Uptime: ${formatUptime(uptime)}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Toggle Button with enhanced styling
                Button(
                    onClick = onToggleConnection,
                    enabled = isClickable && !isConnecting && !isDisconnecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (connectionState) {
                            is ConnectionState.Connected -> Color(0xFFEF4444)
                            is ConnectionState.Disconnecting -> Color(0xFFF59E0B)
                            else -> Color(0xFF10B981)
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
                    if (isConnecting || isDisconnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                    }
                    Text(
                        text = when (connectionState) {
                            is ConnectionState.Connected -> "Disconnect"
                            is ConnectionState.Connecting -> "Connecting..."
                            is ConnectionState.Disconnecting -> "Disconnecting..."
                            is ConnectionState.Disconnected -> "Connect"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

/**
 * Displays a list of connection stages with visual indicators.
 * Shows which stage is currently active and which stages are completed.
 */
@Composable
private fun ConnectionStagesList(
    currentStage: ConnectionStage?,
    modifier: Modifier = Modifier
) {
    val allStages = remember {
        listOf(
            ConnectionStage.INITIALIZING,
            ConnectionStage.STARTING_VPN,
            ConnectionStage.STARTING_XRAY,
            ConnectionStage.ESTABLISHING,
            ConnectionStage.VERIFYING
        )
    }
    
    val currentStageIndex = currentStage?.let { allStages.indexOf(it) } ?: -1
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        allStages.forEachIndexed { index, stage ->
            ConnectionStageItem(
                stage = stage,
                isActive = index == currentStageIndex,
                isCompleted = index < currentStageIndex,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Displays a list of disconnection stages with visual indicators.
 * Shows which stage is currently active and which stages are completed.
 */
@Composable
private fun DisconnectionStagesList(
    currentStage: DisconnectionStage?,
    modifier: Modifier = Modifier
) {
    val allStages = remember {
        listOf(
            DisconnectionStage.STOPPING_XRAY,
            DisconnectionStage.CLOSING_TUNNEL,
            DisconnectionStage.STOPPING_VPN,
            DisconnectionStage.CLEANING_UP
        )
    }
    
    val currentStageIndex = currentStage?.let { allStages.indexOf(it) } ?: -1
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        allStages.forEachIndexed { index, stage ->
            DisconnectionStageItem(
                stage = stage,
                isActive = index == currentStageIndex,
                isCompleted = index < currentStageIndex,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Individual connection stage item with status indicator.
 */
@Composable
private fun ConnectionStageItem(
    stage: ConnectionStage,
    isActive: Boolean,
    isCompleted: Boolean,
    modifier: Modifier = Modifier
) {
    val statusColor = when {
        isCompleted -> Color(0xFF10B981) // Green for completed
        isActive -> Color(0xFF3B82F6) // Blue for active
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) // Gray for pending
    }
    
    val statusIconSize = 20.dp
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        // Status indicator circle
        Box(
            modifier = Modifier
                .size(statusIconSize)
                .clip(CircleShape)
                .background(
                    if (isActive || isCompleted) {
                        statusColor
                    } else {
                        Color.Transparent
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isActive) {
                CircularProgressIndicator(
                    modifier = Modifier.size(statusIconSize),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else if (isCompleted) {
                // Checkmark would go here, but using a simple circle for now
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Stage text
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stage.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isActive || isCompleted) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                }
            )
            if (isActive) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stage.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Individual disconnection stage item with status indicator.
 */
@Composable
private fun DisconnectionStageItem(
    stage: DisconnectionStage,
    isActive: Boolean,
    isCompleted: Boolean,
    modifier: Modifier = Modifier
) {
    val statusColor = when {
        isCompleted -> Color(0xFF10B981) // Green for completed
        isActive -> Color(0xFFF59E0B) // Orange/Amber for active (matching disconnecting gradient)
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) // Gray for pending
    }
    
    val statusIconSize = 20.dp
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        // Status indicator circle
        Box(
            modifier = Modifier
                .size(statusIconSize)
                .clip(CircleShape)
                .background(
                    if (isActive || isCompleted) {
                        statusColor
                    } else {
                        Color.Transparent
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isActive) {
                CircularProgressIndicator(
                    modifier = Modifier.size(statusIconSize),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else if (isCompleted) {
                // Checkmark would go here, but using a simple circle for now
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Stage text
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stage.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isActive || isCompleted) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                }
            )
            if (isActive) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stage.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

