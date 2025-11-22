package com.hyperxray.an.feature.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxray.an.feature.dashboard.ConnectionState
import com.hyperxray.an.feature.dashboard.ConnectionStage
import com.hyperxray.an.feature.dashboard.DisconnectionStage
import com.hyperxray.an.feature.dashboard.formatUptime
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

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
 * @param uplinkThroughput Upload throughput in bytes per second (for connected state animation)
 * @param downlinkThroughput Download throughput in bytes per second (for connected state animation)
 */
@Composable
fun ConnectionStatusCard(
    connectionState: ConnectionState,
    isClickable: Boolean,
    onToggleConnection: () -> Unit,
    uptime: Int,
    playIconRes: Int,
    pauseIconRes: Int,
    uplinkThroughput: Double = 0.0,
    downlinkThroughput: Double = 0.0,
    modifier: Modifier = Modifier
) {
    // Determine connection state properties - simple calculations, no unnecessary optimization
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
    val isFailed = connectionState is ConnectionState.Failed
    val errorMessage = if (connectionState is ConnectionState.Failed) connectionState.error else null
    
    // Cache gradient colors to avoid recreation
    val connectedGradient = remember { 
        listOf(Color(0xFF00E5FF), Color(0xFF00B8D4), Color(0xFF006064)) // Neon Cyan
    }
    val connectingGradient = remember {
        listOf(Color(0xFF2979FF), Color(0xFF2962FF), Color(0xFF0D47A1)) // Electric Blue
    }
    val disconnectedGradient = remember { 
        listOf(Color(0xFF424242), Color(0xFF212121), Color(0xFF000000)) // Dark Gray
    }
    val disconnectingGradient = remember {
        listOf(Color(0xFFFF00E5), Color(0xFFAA00FF), Color(0xFF4A148C)) // Neon Magenta/Purple
    }
    
    val failedGradient = remember {
        listOf(Color(0xFFF97316), Color(0xFFEA580C), Color(0xFFC2410C)) // Orange gradient for failed
    }
    
    val statusGradient = remember(connectionState) {
        when (connectionState) {
            is ConnectionState.Connected -> connectedGradient
            is ConnectionState.Connecting -> connectingGradient
            is ConnectionState.Disconnecting -> disconnectingGradient
            is ConnectionState.Disconnected -> disconnectedGradient
            is ConnectionState.Failed -> failedGradient // Turuncu renk
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
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(cardScale)
            .alpha(cardAlpha)
            .clip(RoundedCornerShape(32.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF000000).copy(alpha = 0.8f), // Obsidian glass base
                        Color(0xFF0A0A0A).copy(alpha = 0.6f)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(statusGradient),
                shape = RoundedCornerShape(32.dp)
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
                                    center = Offset(48f, 48f),
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
                                    center = Offset(40f, 40f),
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
                        } else if (isConnected) {
                            AnimatedConnectionFace(
                                connectionState = connectionState,
                                uplinkThroughput = uplinkThroughput,
                                downlinkThroughput = downlinkThroughput,
                                modifier = Modifier.size(140.dp) // Larger to accommodate glow
                            )
                        } else {
                            AnimatedConnectionFace(
                                connectionState = connectionState,
                                uplinkThroughput = 0.0,
                                downlinkThroughput = 0.0,
                                modifier = Modifier.size(140.dp)
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
                                is ConnectionState.Failed -> "Failed" // Failed durumunda "Failed" göster
                            },
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = Color.White // High contrast on obsidian
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
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        letterSpacing = 0.2.sp
                                    ),
                                    color = Color(0xFFB0B0B0)
                                )
                                
                                // Error message display
                                AnimatedVisibility(
                                    visible = isFailed && errorMessage != null,
                                    enter = fadeIn() + slideInVertically(),
                                    exit = fadeOut() + slideOutVertically()
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = errorMessage ?: "Unknown error",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                letterSpacing = 0.2.sp,
                                                fontWeight = FontWeight.Medium
                                            ),
                                            color = Color(0xFFF97316), // Turuncu renk (failed durumuyla uyumlu)
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                }
                                
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
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        letterSpacing = 0.2.sp
                                    ),
                                    color = Color(0xFFB0B0B0)
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
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        letterSpacing = 0.2.sp
                                    ),
                                    color = Color(0xFFB0B0B0)
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Toggle Button with enhanced styling
                Button(
                    onClick = onToggleConnection,
                    enabled = isClickable && !isConnecting && !isDisconnecting && 
                              !(isFailed && (connectionState as? ConnectionState.Failed)?.retryCountdownSeconds != null && 
                                (connectionState as? ConnectionState.Failed)?.retryCountdownSeconds!! > 0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (connectionState) {
                            is ConnectionState.Connected -> Color(0xFFEF4444)
                            is ConnectionState.Disconnecting -> Color(0xFFF59E0B)
                            is ConnectionState.Failed -> Color(0xFFF97316) // Turuncu renk
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
                            is ConnectionState.Failed -> {
                                val countdown = connectionState.retryCountdownSeconds
                                if (countdown != null && countdown > 0) {
                                    "Retrying in $countdown..."
                                } else {
                                    "Retry Connection"
                                }
                            }
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
    
    // Map RECONNECTING to INITIALIZING (index 0) for visual progress
    val effectiveStage = if (currentStage == ConnectionStage.RECONNECTING) ConnectionStage.INITIALIZING else currentStage
    val currentStageIndex = effectiveStage?.let { allStages.indexOf(it) } ?: -1
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        allStages.forEachIndexed { index, stage ->
            // If we are reconnecting, show the RECONNECTING info for the first step
            val displayStage = if (currentStage == ConnectionStage.RECONNECTING && stage == ConnectionStage.INITIALIZING) {
                ConnectionStage.RECONNECTING
            } else {
                stage
            }
            
            ConnectionStageItem(
                stage = displayStage,
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

/**
 * A FUTURISTIC CYBER-AI FACE ANIMATION
 * Features:
 * - Holographic Eyes (Rotating rings, digital core)
 * - Waveform Mouth (Reacts to throughput)
 * - Glitch Effects (On state changes)
 * - Neon Glow (Cyberpunk aesthetic)
 * 
 * @param connectionState Current state of the VPN connection
 * @param uplinkThroughput Upload speed for excitement level
 * @param downlinkThroughput Download speed for excitement level
 */
@Composable
private fun AnimatedConnectionFace(
    connectionState: ConnectionState,
    uplinkThroughput: Double,
    downlinkThroughput: Double,
    modifier: Modifier = Modifier
) {
    // Throughput calculation for reactivity
    val maxThroughput = 5_000_000.0 // 5MB/s reference
    val totalThroughput = uplinkThroughput + downlinkThroughput
    val activityLevel = (totalThroughput / maxThroughput).coerceIn(0.0, 1.0).toFloat()
    
    val infiniteTransition = rememberInfiniteTransition(label = "cyber_face")
    
    // 1. Holographic Rotation (Eyes)
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    // 2. Breathing Glow (Pulse)
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    // 3. Glitch Effect (Random offsets)
    val glitchTrigger by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, delayMillis = 2000), // Occasional glitch
            repeatMode = RepeatMode.Restart
        ),
        label = "glitch"
    )
    
    val isGlitching = glitchTrigger > 0.95f || connectionState is ConnectionState.Connecting
    val glitchOffsetX = if (isGlitching) Random.nextFloat() * 10f - 5f else 0f
    val glitchOffsetY = if (isGlitching) Random.nextFloat() * 6f - 3f else 0f
    
    // 4. Eye Blink (Digital Shutter)
    val blink by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, delayMillis = 3000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blink"
    )
    val eyeHeight = if (blink < 0.1f) 0.1f else 1f // Digital blink
    
    // Colors based on state
    val primaryColor = when (connectionState) {
        is ConnectionState.Connected -> Color(0xFF00E5FF) // Cyan
        is ConnectionState.Connecting -> Color(0xFF2979FF) // Blue
        is ConnectionState.Disconnecting -> Color(0xFFFF00E5) // Purple
        is ConnectionState.Disconnected -> Color(0xFF555555) // Gray
        is ConnectionState.Failed -> Color(0xFFEF4444) // Red
    }
    
    val secondaryColor = when (connectionState) {
        is ConnectionState.Connected -> Color(0xFF00FF88) // Green accent
        is ConnectionState.Connecting -> Color(0xFFFFFFFF) // White accent
        else -> primaryColor
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val faceRadius = size.minDimension / 2 * 0.85f
        
        // --- BACKGROUND HUD RING ---
        if (connectionState !is ConnectionState.Disconnected) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.2f), Color.Transparent),
                    center = center,
                    radius = faceRadius * 1.2f
                ),
                radius = faceRadius * 1.2f,
                center = center
            )
            
            // Rotating HUD segments
            val segmentCount = 12
            val segmentAngle = 360f / segmentCount
            for (i in 0 until segmentCount) {
                val angle = i * segmentAngle + rotation
                val rad = angle * (Math.PI / 180.0)
                val startX = center.x + cos(rad).toFloat() * faceRadius
                val startY = center.y + sin(rad).toFloat() * faceRadius
                val endX = center.x + cos(rad).toFloat() * (faceRadius + 10.dp.toPx())
                val endY = center.y + sin(rad).toFloat() * (faceRadius + 10.dp.toPx())
                
                drawLine(
                    color = primaryColor.copy(alpha = 0.3f),
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
        
        // --- EYES (Holographic) ---
        val eyeSpacing = faceRadius * 0.4f
        val eyeY = center.y - faceRadius * 0.15f
        val eyeRadius = faceRadius * 0.25f
        
        // Left Eye
        drawCyberEye(
            center = Offset(center.x - eyeSpacing + glitchOffsetX, eyeY + glitchOffsetY),
            radius = eyeRadius,
            color = primaryColor,
            accentColor = secondaryColor,
            rotation = -rotation * 2f, // Counter-rotate
            pulse = pulse,
            heightScale = eyeHeight,
            activity = activityLevel
        )
        
        // Right Eye
        drawCyberEye(
            center = Offset(center.x + eyeSpacing + glitchOffsetX, eyeY - glitchOffsetY), // Asymmetric glitch
            radius = eyeRadius,
            color = primaryColor,
            accentColor = secondaryColor,
            rotation = rotation * 2f,
            pulse = pulse,
            heightScale = eyeHeight,
            activity = activityLevel
        )
        
        // --- MOUTH (Waveform) ---
        val mouthY = center.y + faceRadius * 0.35f
        val mouthWidth = faceRadius * 0.8f
        
        drawCyberMouth(
            center = Offset(center.x, mouthY),
            width = mouthWidth,
            color = primaryColor,
            state = connectionState,
            activity = activityLevel,
            pulse = pulse
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCyberEye(
    center: Offset,
    radius: Float,
    color: Color,
    accentColor: Color,
    rotation: Float,
    pulse: Float,
    heightScale: Float,
    activity: Float
) {
    val scaledRadius = radius * pulse
    
    // 1. Outer Ring (Dashed)
    val dashCount = 8
    val dashAngle = 360f / dashCount
    for (i in 0 until dashCount) {
        val startAngle = i * dashAngle + rotation
        drawArc(
            color = color.copy(alpha = 0.6f),
            startAngle = startAngle,
            sweepAngle = dashAngle * 0.6f,
            useCenter = false,
            topLeft = center - Offset(scaledRadius, scaledRadius * heightScale),
            size = Size(scaledRadius * 2, scaledRadius * 2 * heightScale),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
    
    // 2. Inner Core (Solid)
    val coreRadius = radius * 0.4f * (1f + activity * 0.5f) // Dilate on activity
    drawOval(
        color = accentColor,
        topLeft = center - Offset(coreRadius, coreRadius * heightScale),
        size = Size(coreRadius * 2, coreRadius * 2 * heightScale),
        style = Stroke(width = 3.dp.toPx())
    )
    
    // 3. Pupil (Filled)
    drawOval(
        color = accentColor.copy(alpha = 0.8f),
        topLeft = center - Offset(coreRadius * 0.6f, coreRadius * 0.6f * heightScale),
        size = Size(coreRadius * 1.2f, coreRadius * 1.2f * heightScale)
    )
    
    // 4. Scanline (Horizontal)
    drawLine(
        color = Color.White.copy(alpha = 0.4f),
        start = center - Offset(radius, 0f),
        end = center + Offset(radius, 0f),
        strokeWidth = 1.dp.toPx()
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCyberMouth(
    center: Offset,
    width: Float,
    color: Color,
    state: ConnectionState,
    activity: Float,
    pulse: Float
) {
    val barCount = 10
    val barWidth = width / barCount
    val maxBarHeight = width * 0.3f
    
    when (state) {
        is ConnectionState.Connected -> {
            // Audio Visualizer Effect
            for (i in 0 until barCount) {
                // Generate pseudo-random height based on index and activity
                val noise = sin(i * 1.5f + System.currentTimeMillis() / 100f).toFloat()
                val barHeight = maxBarHeight * (0.2f + activity * 0.8f * (noise + 1f) / 2f)
                
                val x = center.x - width / 2 + i * barWidth + barWidth / 2
                
                drawLine(
                    color = color.copy(alpha = 0.8f),
                    start = Offset(x, center.y - barHeight / 2),
                    end = Offset(x, center.y + barHeight / 2),
                    strokeWidth = barWidth * 0.6f,
                    cap = StrokeCap.Round
                )
            }
        }
        is ConnectionState.Connecting -> {
            // Loading Bar Effect
            val progress = (System.currentTimeMillis() % 1000) / 1000f
            val x = center.x - width / 2 + width * progress
            
            drawLine(
                color = color.copy(alpha = 0.3f),
                start = Offset(center.x - width / 2, center.y),
                end = Offset(center.x + width / 2, center.y),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
            
            drawCircle(
                color = color,
                radius = 6.dp.toPx(),
                center = Offset(x, center.y)
            )
        }
        is ConnectionState.Disconnected -> {
            // Flat Line (Standby)
            drawLine(
                color = color.copy(alpha = 0.4f),
                start = Offset(center.x - width / 3, center.y),
                end = Offset(center.x + width / 3, center.y),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        is ConnectionState.Disconnecting -> {
            // Glitchy Line
            val path = Path()
            path.moveTo(center.x - width / 2, center.y)
            for (i in 1 until barCount) {
                val x = center.x - width / 2 + i * barWidth
                val y = center.y + (Random.nextFloat() - 0.5f) * 20f
                path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.dp.toPx())
            )
        }
        is ConnectionState.Failed -> {
            // Error Indicator - Dashed Line
            val dashLength = 8.dp.toPx()
            val gapLength = 4.dp.toPx()
            var currentX = center.x - width / 2
            
            while (currentX < center.x + width / 2) {
                drawLine(
                    color = color.copy(alpha = 0.6f),
                    start = Offset(currentX, center.y),
                    end = Offset(minOf(currentX + dashLength, center.x + width / 2), center.y),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                currentX += dashLength + gapLength
            }
        }
    }
}
