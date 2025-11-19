package com.hyperxray.an.feature.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import com.hyperxray.an.feature.dashboard.ConnectionState
import com.hyperxray.an.feature.dashboard.ConnectionStage
import com.hyperxray.an.feature.dashboard.DisconnectionStage
import com.hyperxray.an.feature.dashboard.formatUptime
import kotlin.math.cos
import kotlin.math.sin

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
                        } else if (isConnected) {
                            AnimatedConnectionFace(
                                uplinkThroughput = uplinkThroughput,
                                downlinkThroughput = downlinkThroughput,
                                modifier = Modifier.size(140.dp) // Larger to accommodate glow
                            )
                        } else {
                            Image(
                                painter = painterResource(id = pauseIconRes),
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

/**
 * INSANELY EPIC animated connection face with WILD expressions, CRAZY eyes, and MIND-BLOWING effects!
 * Features: Happy, excited, surprised, cool, wink, tongue-out, dancing, party, sleepy, and more!
 * With smooth story-like transitions, celebration bursts, and throughput-reactive personality!
 * 
 * @param uplinkThroughput Upload throughput in bytes per second
 * @param downlinkThroughput Download throughput in bytes per second
 */
@Composable
private fun AnimatedConnectionFace(
    uplinkThroughput: Double,
    downlinkThroughput: Double,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "epic_face_animation")
    
    // Main expression cycle: ENHANCED with more expressions and story-like progression
    val expressionCycle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 10f, // 10 different expressions for more variety!
        animationSpec = infiniteRepeatable(
            animation = tween(12000, delayMillis = 0, easing = FastOutSlowInEasing), // Smooth easing for better transitions
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "expression_cycle"
    )
    
    // Determine current expression with SMOOTH story-like transitions
    val expressionPhase = expressionCycle % 1f
    
    // Dynamic expression selection - creates a fun story progression!
    val expressionType = when {
        expressionCycle < 1f -> "happy"        // Start happy
        expressionCycle < 1.8f -> "excited"     // Get excited
        expressionCycle < 2.6f -> "dancing"     // Dance party!
        expressionCycle < 3.4f -> "wink"        // Playful wink
        expressionCycle < 4.2f -> "surprised"   // Surprise moment
        expressionCycle < 5f -> "tongue"        // Silly tongue
        expressionCycle < 5.8f -> "party"       // Party mode!
        expressionCycle < 6.6f -> "cool"         // Cool down
        expressionCycle < 7.4f -> "sleepy"      // Get sleepy
        else -> "happy"                         // Back to happy (cycle continues)
    }
    
    // Transition smoothness between expressions
    val transitionProgress = when {
        expressionCycle < 1f -> expressionCycle % 1f
        expressionCycle < 2f -> (expressionCycle - 1f) % 1f
        expressionCycle < 3f -> (expressionCycle - 2f) % 1f
        expressionCycle < 4f -> (expressionCycle - 3f) % 1f
        expressionCycle < 5f -> (expressionCycle - 4f) % 1f
        expressionCycle < 6f -> (expressionCycle - 5f) % 1f
        expressionCycle < 7f -> (expressionCycle - 6f) % 1f
        expressionCycle < 8f -> (expressionCycle - 7f) % 1f
        expressionCycle < 9f -> (expressionCycle - 8f) % 1f
        else -> (expressionCycle - 9f) % 1f
    }
    
    // Eye rotation - ENHANCED with variable speed based on expression
    val eyeRotationSpeed = when (expressionType) {
        "dancing", "party" -> 3000f // Faster for party modes
        "sleepy" -> 8000f // Slower when sleepy
        else -> 5000f
    }
    val eyeRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(eyeRotationSpeed.toInt(), delayMillis = 0, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "eye_rotation"
    )
    
    // Eye size pulsing - OPTIMIZED gentle pulsing
    val eyePulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f, // More subtle range
        animationSpec = infiniteRepeatable(
            animation = tween(2000, delayMillis = 0, easing = FastOutSlowInEasing), // Smooth easing animation
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "eye_pulse"
    )
    
    // Independent eye blinks - ENHANCED with expression-based timing
    val blinkSpeed = when (expressionType) {
        "sleepy" -> 2000f // Slower, heavier blinks when sleepy
        "dancing", "party" -> 2500f // Faster blinks for energetic expressions
        else -> 3000f
    }
    val blinkCycle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(blinkSpeed.toInt(), delayMillis = 0, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "blink_cycle"
    )
    
    // Smart blink system - ENHANCED with expression-based patterns (eyes never disappear)
    val leftEyeBlink = when {
        expressionType == "wink" -> if (blinkCycle < 0.25f) 0.3f else 1f // Minimum 0.3f so eye stays visible
        expressionType == "sleepy" -> {
            // Slower, heavier blinks for sleepy
            val blinkPhase = (blinkCycle * 1.5f) % 1f
            if (blinkPhase < 0.15f) 0.4f else 1f // Minimum 0.4f
        }
        expressionType == "dancing" || expressionType == "party" -> {
            // Quick, energetic blinks
            val blinkPhase = (blinkCycle * 3f) % 1f
            if (blinkPhase < 0.06f) 0.35f else 1f // Minimum 0.35f
        }
        else -> {
            val blinkPhase = (blinkCycle * 2.5f) % 1f
            if (blinkPhase < 0.08f) 0.4f else 1f // Minimum 0.4f
        }
    }
    
    val rightEyeBlink = when {
        expressionType == "wink" -> if (blinkCycle > 0.35f && blinkCycle < 0.6f) 0.3f else 1f // Minimum 0.3f
        expressionType == "sleepy" -> {
            // Slower, heavier blinks for sleepy
            val blinkPhase = ((blinkCycle * 1.5f) + 0.3f) % 1f
            if (blinkPhase < 0.15f) 0.4f else 1f // Minimum 0.4f
        }
        expressionType == "dancing" || expressionType == "party" -> {
            // Quick, energetic blinks
            val blinkPhase = ((blinkCycle * 3f) + 0.5f) % 1f
            if (blinkPhase < 0.06f) 0.35f else 1f // Minimum 0.35f
        }
        else -> {
            val blinkPhase = ((blinkCycle * 2.5f) + 0.5f) % 1f
            if (blinkPhase < 0.08f) 0.4f else 1f // Minimum 0.4f
        }
    }
    
    // Eye wiggle - ENHANCED with expression-based intensity
    val wiggleRange = when (expressionType) {
        "dancing", "party" -> 3f // More wiggle for energetic expressions!
        "sleepy" -> 0.5f // Less wiggle when sleepy
        else -> 1.5f
    }
    val wiggleSpeed = when (expressionType) {
        "dancing", "party" -> 800f // Faster wiggle
        "sleepy" -> 2500f // Slower when sleepy
        else -> 1500f
    }
    
    val leftEyeWiggleX by infiniteTransition.animateFloat(
        initialValue = -wiggleRange,
        targetValue = wiggleRange,
        animationSpec = infiniteRepeatable(
            animation = tween(wiggleSpeed.toInt(), delayMillis = 0, easing = FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "left_eye_wiggle"
    )
    
    val rightEyeWiggleX by infiniteTransition.animateFloat(
        initialValue = -wiggleRange,
        targetValue = wiggleRange,
        animationSpec = infiniteRepeatable(
            animation = tween(wiggleSpeed.toInt(), delayMillis = 200, easing = FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "right_eye_wiggle"
    )
    
    // Mouth animation - OPTIMIZED expressive timing
    val mouthAnimation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, delayMillis = 0, easing = FastOutSlowInEasing), // Smooth mouth animation
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "mouth_animation"
    )
    
    // Tongue animation (for tongue-out expression) - OPTIMIZED
    val tongueAnimation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, delayMillis = 0, easing = FastOutSlowInEasing), // Smooth tongue animation
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "tongue_animation"
    )
    
    // Bounce animation - ENHANCED with expression-based intensity
    val maxThroughput = 10_000_000.0
    val totalThroughput = uplinkThroughput + downlinkThroughput
    val throughputIntensity = (totalThroughput / maxThroughput).toFloat().coerceIn(0f, 1f)
    
    // Dancing/Party bounce - ENHANCED vertical movement (defined before use)
    val danceBounce by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 0, easing = FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "dance_bounce"
    )
    
    val bounceIntensity = when (expressionType) {
        "dancing" -> 1.5f // More bounce for dancing
        "party" -> 2f // Maximum bounce for party!
        "sleepy" -> 0.3f // Gentle when sleepy
        else -> 1f
    }
    
    val bounceY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -10f * (0.3f + throughputIntensity * 0.5f) * bounceIntensity * (1f + danceBounce * 0.5f),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (expressionType) {
                    "dancing", "party" -> 400 // Faster bounce for dancing
                    "sleepy" -> 1200 // Slower when sleepy
                    else -> 800
                },
                delayMillis = 0,
                easing = FastOutSlowInEasing
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "bounce"
    )
    
    // Face rotation - ENHANCED with expression-based movement
    val faceRotationRange = when (expressionType) {
        "dancing" -> 15f // More dramatic for dancing
        "party" -> 20f // Even more for party!
        "sleepy" -> 3f // Gentle when sleepy
        else -> 8f
    }
    val faceRotationSpeed = when (expressionType) {
        "dancing", "party" -> 2000f // Faster movement
        "sleepy" -> 5000f // Slower when sleepy
        else -> 3500f
    }
    val faceRotation by infiniteTransition.animateFloat(
        initialValue = -faceRotationRange,
        targetValue = faceRotationRange,
        animationSpec = infiniteRepeatable(
            animation = tween(faceRotationSpeed.toInt(), delayMillis = 0, easing = FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "face_rotation"
    )
    
    // Celebration burst animation - for party moments!
    val celebrationBurst by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, delayMillis = 0, easing = FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "celebration_burst"
    )
    
    // Face scale - ENHANCED breathing effect with expression-based intensity
    val scaleRange = when (expressionType) {
        "party" -> 0.95f to 1.08f // More dramatic for party!
        "dancing" -> 0.96f to 1.06f // More movement for dancing
        "excited" -> 0.97f to 1.05f // More energetic
        "sleepy" -> 0.98f to 1.02f // Gentle when sleepy
        else -> 0.97f to 1.03f
    }
    val scaleSpeed = when (expressionType) {
        "dancing", "party" -> 1800f // Faster breathing for energetic expressions
        "sleepy" -> 3500f // Slower when sleepy
        else -> 2500f
    }
    
    val faceScale by infiniteTransition.animateFloat(
        initialValue = scaleRange.first,
        targetValue = scaleRange.second,
        animationSpec = infiniteRepeatable(
            animation = tween(scaleSpeed.toInt(), delayMillis = 0, easing = FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "face_scale"
    )
    
    // Rainbow color cycle - OPTIMIZED smooth rainbow
    val rainbowCycle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, delayMillis = 0, easing = androidx.compose.animation.core.LinearEasing), // Smooth rainbow transition
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "rainbow"
    )
    
    // Sparkle burst animation - OPTIMIZED
    val sparkleCycle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, delayMillis = 0, easing = FastOutSlowInEasing), // Smooth sparkle cycle
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "sparkle_cycle"
    )
    
    // Smooth pupil movement for left eye - OPTIMIZED gentle motion
    val leftPupilX by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, delayMillis = 0, easing = FastOutSlowInEasing), // Very smooth and slow
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "left_pupil_x"
    )
    
    val leftPupilY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(7000, delayMillis = 0, easing = FastOutSlowInEasing), // Slightly offset for natural movement
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "left_pupil_y"
    )
    
    // Smooth pupil movement for right eye - OPTIMIZED gentle motion
    val rightPupilX by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6500, delayMillis = 0, easing = FastOutSlowInEasing), // Independent smooth motion
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "right_pupil_x"
    )
    
    val rightPupilY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(7500, delayMillis = 0, easing = FastOutSlowInEasing), // Independent smooth motion
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "right_pupil_y"
    )
    
    // Smooth eye shine movement - OPTIMIZED gentle shimmer
    val eyeShineOffsetX by infiniteTransition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, delayMillis = 0, easing = FastOutSlowInEasing), // Smooth shine movement
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "eye_shine_x"
    )
    
    val eyeShineOffsetY by infiniteTransition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(4500, delayMillis = 0, easing = FastOutSlowInEasing), // Smooth shine movement
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "eye_shine_y"
    )
    
    val eyeShineAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, delayMillis = 0, easing = FastOutSlowInEasing), // Smooth shine pulsing
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "eye_shine_alpha"
    )
    
    // Upload/download progress
    val uploadProgress = (uplinkThroughput / maxThroughput).toFloat().coerceIn(0f, 1f)
    val downloadProgress = (downlinkThroughput / maxThroughput).toFloat().coerceIn(0f, 1f)
    val uploadAngle = 180f * uploadProgress
    val downloadAngle = 180f * downloadProgress
    
    // Dynamic glow colors based on throughput - MORE INTENSE
    val glowIntensity = 0.9f + (throughputIntensity * 0.3f)
    val glowColor = Color(0xFF00FF88)
    val brightGlowColor = Color(0xFF34D399)
    
    // ENHANCED Rainbow colors for eyes - MORE VIBRANT
    fun getRainbowColor(hue: Float, alphaMultiplier: Float = 1f): Color {
        val h = (hue % 360f) / 360f
        val baseAlpha = (0.85f + h * 0.15f) * alphaMultiplier
        return when {
            h < 0.166f -> Color(0xFFFF0000).copy(alpha = baseAlpha.coerceIn(0.7f, 1f)) // Red
            h < 0.333f -> Color(0xFFFF5500).copy(alpha = baseAlpha.coerceIn(0.7f, 1f)) // Orange
            h < 0.5f -> Color(0xFFFFFF00).copy(alpha = baseAlpha.coerceIn(0.7f, 1f)) // Yellow
            h < 0.666f -> Color(0xFF00FF55).copy(alpha = baseAlpha.coerceIn(0.7f, 1f)) // Green
            h < 0.833f -> Color(0xFF00AAFF).copy(alpha = baseAlpha.coerceIn(0.7f, 1f)) // Blue
            else -> Color(0xFFAA00FF).copy(alpha = baseAlpha.coerceIn(0.7f, 1f)) // Purple
        }
    }
    
    // Eyes are always white - no color animation
    val leftEyeColor = Color.White
    val rightEyeColor = Color.White
    
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // INSANE GLOW BACKGROUND - ENHANCED multi-layered rainbow explosion
        Canvas(modifier = Modifier.size(180.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val maxRadius = size.minDimension / 2
            
            // Expression-based glow intensity
            val glowMultiplier = when (expressionType) {
                "party" -> 1.8f // MEGA glow for party!
                "dancing" -> 1.5f // Strong glow for dancing
                "excited" -> 1.3f
                "sleepy" -> 0.7f // Softer when sleepy
                else -> 1f
            }
            
            // MORE glowing circles with ENHANCED rainbow effect
            val circleCount = when (expressionType) {
                "party" -> 60 // More circles for party!
                "dancing" -> 50
                else -> 40
            }
            
            for (i in 0 until circleCount) {
                val progress = i / circleCount.toFloat()
                val radius = maxRadius * (1f + progress * 0.6f)
                val alpha = glowIntensity * (1f - progress) * 0.5f * (1f + throughputIntensity * 0.3f) * glowMultiplier
                val hue = (rainbowCycle + i * 9f) % 360f
                val pulsingRadius = radius * (1f + faceScale * 0.1f) * (1f + when (expressionType) {
                    "party" -> celebrationBurst * 0.15f
                    "dancing" -> danceBounce * 0.1f
                    else -> 0f
                })
                if (alpha > 0.01f) {
                    drawCircle(
                        color = getRainbowColor(hue, alpha / 0.5f),
                        radius = pulsingRadius,
                        center = center
                    )
                }
            }
        }
        
        // SPINNING SUN RAYS - FASTER rotating with ENHANCED rainbow colors
        Canvas(modifier = Modifier.size(150.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2
            
            // MORE rays for CRAZIER effect!
            for (i in 0 until 24) {
                val baseAngle = (i * 360f / 24) * (kotlin.math.PI / 180f).toFloat()
                val rotationOffset = (eyeRotation * kotlin.math.PI / 180f).toFloat()
                val angle = baseAngle + rotationOffset
                val rayLength = radius * (1.9f + throughputIntensity * 0.2f) // Dynamic length
                
                val startX = center.x + cos(angle.toDouble()).toFloat() * radius
                val startY = center.y + sin(angle.toDouble()).toFloat() * radius
                val endX = center.x + cos(angle.toDouble()).toFloat() * rayLength
                val endY = center.y + sin(angle.toDouble()).toFloat() * rayLength
                
                val hue = (rainbowCycle + i * 15f) % 360f
                val rayGlow = glowIntensity * (1f + sparkleCycle * 0.3f)
                drawLine(
                    color = getRainbowColor(hue, rayGlow).copy(alpha = rayGlow.coerceIn(0.7f, 1f)),
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = (10.dp.toPx() * (1f + throughputIntensity * 0.2f)),
                    cap = StrokeCap.Round
                )
            }
        }
        
        // SPARKLE BURSTS - ENHANCED with expression-based intensity!
        Canvas(modifier = Modifier.size(170.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val sparkleMultiplier = when (expressionType) {
                "party" -> 2.5f // MEGA sparkles for party!
                "dancing" -> 2f // More sparkles for dancing
                "excited" -> 1.5f
                "sleepy" -> 0.5f // Fewer sparkles when sleepy
                else -> 1f
            }
            val particleCount = ((throughputIntensity * 20 + sparkleCycle * 10) * sparkleMultiplier).toInt().coerceIn(5, 50)
            
            for (i in 0 until particleCount) {
                val angle = (i * 360f / particleCount + eyeRotation * 0.5f) * (kotlin.math.PI / 180f).toFloat()
                val distance = size.minDimension / 2 * (0.7f + (sparkleCycle + i % 3) * 0.15f)
                val x = center.x + cos(angle.toDouble()).toFloat() * distance
                val y = center.y + sin(angle.toDouble()).toFloat() * distance
                
                val hue = (rainbowCycle + i * 12f) % 360f
                val sparkleAlpha = (0.8f + sparkleCycle * 0.2f) * glowIntensity
                val sparkleSize = 3.dp.toPx() * (1f + sparkleCycle * 0.5f) * (1f + throughputIntensity * 0.3f) * sparkleMultiplier
                
                // Main sparkle
                drawCircle(
                    color = getRainbowColor(hue, sparkleAlpha),
                    radius = sparkleSize,
                    center = Offset(x, y)
                )
                
                // Cross sparkle effect
                drawLine(
                    color = getRainbowColor(hue, sparkleAlpha * 0.8f),
                    start = Offset(x - sparkleSize * 2, y),
                    end = Offset(x + sparkleSize * 2, y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = getRainbowColor(hue, sparkleAlpha * 0.8f),
                    start = Offset(x, y - sparkleSize * 2),
                    end = Offset(x, y + sparkleSize * 2),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            
            // CELEBRATION BURST EFFECT for party mode!
            if (expressionType == "party" || expressionType == "dancing") {
                val burstRadius = size.minDimension / 2 * (1.2f + celebrationBurst * 0.8f)
                val burstAlpha = (0.6f - celebrationBurst * 0.4f).coerceIn(0.1f, 0.6f)
                
                for (i in 0 until 16) {
                    val burstAngle = (i * 360f / 16 + eyeRotation * 0.3f) * (kotlin.math.PI / 180f).toFloat()
                    val burstDistance = burstRadius * (0.8f + celebrationBurst * 0.4f)
                    val burstX = center.x + cos(burstAngle.toDouble()).toFloat() * burstDistance
                    val burstY = center.y + sin(burstAngle.toDouble()).toFloat() * burstDistance
                    
                    val burstHue = (rainbowCycle + i * 22.5f) % 360f
                    drawCircle(
                        color = getRainbowColor(burstHue, burstAlpha),
                        radius = 6.dp.toPx() * (1f + celebrationBurst * 0.5f),
                        center = Offset(burstX, burstY)
                    )
                }
            }
        }
        
        // Upload ring with particles
        Canvas(modifier = Modifier.size(100.dp)) {
            if (uploadProgress > 0.01f) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension / 2 - 10.dp.toPx()
                
                val path = Path().apply {
                    moveTo(center.x, center.y - radius)
                    arcTo(
                        rect = androidx.compose.ui.geometry.Rect(
                            center.x - radius,
                            center.y - radius,
                            center.x + radius,
                            center.y + radius
                        ),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = -uploadAngle,
                        forceMoveTo = false
                    )
                }
                
                drawPath(
                    path = path,
                    color = Color(0xFF00FF88),
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                )
                
                // Particles along the ring
                for (i in 0 until (uploadProgress * 8).toInt()) {
                    val angleDegrees = 180f - (i * uploadAngle / 8f)
                    val angleRad = angleDegrees * (kotlin.math.PI / 180f).toFloat()
                    val particleX = center.x + cos(angleRad.toDouble()).toFloat() * radius
                    val particleY = center.y + sin(angleRad.toDouble()).toFloat() * radius
                    drawCircle(
                        color = Color(0xFF00FF88),
                        radius = 3.dp.toPx(),
                        center = Offset(particleX, particleY)
                    )
                }
            }
        }
        
        // Download ring with particles
        Canvas(modifier = Modifier.size(100.dp)) {
            if (downloadProgress > 0.01f) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension / 2 - 10.dp.toPx()
                
                val path = Path().apply {
                    moveTo(center.x, center.y - radius)
                    arcTo(
                        rect = androidx.compose.ui.geometry.Rect(
                            center.x - radius,
                            center.y - radius,
                            center.x + radius,
                            center.y + radius
                        ),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = downloadAngle,
                        forceMoveTo = false
                    )
                }
                
                drawPath(
                    path = path,
                    color = Color(0xFF3B82F6),
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                )
                
                // Particles along the ring
                for (i in 0 until (downloadProgress * 8).toInt()) {
                    val angleDegrees = i * downloadAngle / 8f
                    val angleRad = angleDegrees * (kotlin.math.PI / 180f).toFloat()
                    val particleX = center.x + cos(angleRad.toDouble()).toFloat() * radius
                    val particleY = center.y + sin(angleRad.toDouble()).toFloat() * radius
                    drawCircle(
                        color = Color(0xFF3B82F6),
                        radius = 3.dp.toPx(),
                        center = Offset(particleX, particleY)
                    )
                }
            }
        }
        
        // MAIN FACE - ENHANCED Black circle with CRAZY bounce, rotation, and scaling!
        Box(
            modifier = Modifier
                .size(80.dp)
                .offset(
                    y = bounceY.dp,
                    x = when (expressionType) {
                        "dancing" -> (faceRotation * 0.4f + danceBounce * 2f).dp // Side-to-side dance movement
                        "party" -> (faceRotation * 0.5f + celebrationBurst * 1.5f).dp // Party movement
                        else -> 0.dp
                    }
                )
                .scale(faceScale * (1f + throughputIntensity * 0.15f))
                .clip(CircleShape)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.size(80.dp),
                contentAlignment = Alignment.Center
            ) {
                // CRAZY EYES - SUPER Dynamic, EXTREMELY colorful, and WIGGLY!
                Row(
                    modifier = Modifier
                        .offset(y = (-12).dp)
                        .offset(x = (faceRotation * 0.3f).dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left eye - ENHANCED with wiggle, rotation, and WILD colors!
                    Box(
                        modifier = Modifier
                            .size(12.dp * eyePulse) // Fixed size, no blink multiplication
                            .offset(x = leftEyeWiggleX.dp)
                            .scale(leftEyeBlink) // Only scale for blink effect
                            .clip(CircleShape)
                            .background(leftEyeColor),
                        contentAlignment = Alignment.Center
                    ) {
                        // Smooth eye pupil movement - OPTIMIZED gentle motion
                        val pupilMoveIntensity = when (expressionType) {
                            "surprised" -> 2.5f
                            "excited" -> 2f
                            else -> 1.2f
                        }
                        // Convert 0-1 range to -1 to 1 range for circular motion
                        val pupilOffsetX = ((leftPupilX * 2f - 1f) * pupilMoveIntensity).dp
                        val pupilOffsetY = ((leftPupilY * 2f - 1f) * pupilMoveIntensity).dp
                        Box(
                            modifier = Modifier
                                .size((5.dp * eyePulse))
                                .offset(x = pupilOffsetX, y = pupilOffsetY)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.9f))
                        )
                        // Smooth eye shine - OPTIMIZED gentle shimmer
                        Box(
                            modifier = Modifier
                                .size((2.dp * (0.8f + eyeShineAlpha * 0.2f)))
                                .offset(x = (eyeShineOffsetX - 2f).dp, y = (eyeShineOffsetY - 2f).dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = eyeShineAlpha))
                        )
                    }
                    
                    // Right eye - ENHANCED with wiggle, rotation, and WILD colors!
                    Box(
                        modifier = Modifier
                            .size(12.dp * eyePulse) // Fixed size, no blink multiplication
                            .offset(x = rightEyeWiggleX.dp)
                            .scale(rightEyeBlink) // Only scale for blink effect
                            .clip(CircleShape)
                            .background(rightEyeColor),
                        contentAlignment = Alignment.Center
                    ) {
                        // Smooth eye pupil movement - OPTIMIZED gentle motion
                        val pupilMoveIntensity = when (expressionType) {
                            "surprised" -> 2.5f
                            "excited" -> 2f
                            else -> 1.2f
                        }
                        // Convert 0-1 range to -1 to 1 range for circular motion
                        val pupilOffsetX = ((rightPupilX * 2f - 1f) * pupilMoveIntensity).dp
                        val pupilOffsetY = ((rightPupilY * 2f - 1f) * pupilMoveIntensity).dp
                        Box(
                            modifier = Modifier
                                .size((5.dp * eyePulse))
                                .offset(x = pupilOffsetX, y = pupilOffsetY)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.9f))
                        )
                        // Smooth eye shine - OPTIMIZED gentle shimmer
                        Box(
                            modifier = Modifier
                                .size((2.dp * (0.8f + eyeShineAlpha * 0.2f)))
                                .offset(x = (eyeShineOffsetX - 2f).dp, y = (eyeShineOffsetY - 2f).dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = eyeShineAlpha))
                        )
                    }
                }
                
                // CRAZY MOUTH - WILDLY different shapes based on expression!
                Box(
                    modifier = Modifier
                        .offset(y = (12 + (faceRotation * 0.2f)).dp)
                        .offset(x = (faceRotation * 0.2f).dp)
                        .fillMaxWidth(0.6f)
                        .height(12.dp)
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val centerX = size.width / 2
                        val centerY = size.height / 2
                        val width = size.width
                        val height = size.height
                        
                        val mouthPath = Path()
                        val strokeWidth = 3.5.dp.toPx() * (1f + throughputIntensity * 0.2f)
                        
                        when (expressionType) {
                            "happy" -> {
                                // HUGE smile that gets BIGGER
                                val smileWidth = width * (0.65f + mouthAnimation * 0.45f)
                                val smileHeight = height * (1.0f + mouthAnimation * 0.4f)
                                mouthPath.moveTo(centerX - smileWidth / 2, centerY)
                                mouthPath.quadraticBezierTo(
                                    centerX, centerY + smileHeight,
                                    centerX + smileWidth / 2, centerY
                                )
                            }
                            "excited" -> {
                                // WIDE open mouth (O shape) - VERY EXCITED!
                                val mouthSize = width * (0.45f + mouthAnimation * 0.35f)
                                mouthPath.addOval(
                                    androidx.compose.ui.geometry.Rect(
                                        centerX - mouthSize / 2,
                                        centerY - mouthSize / 2,
                                        centerX + mouthSize / 2,
                                        centerY + mouthSize / 2
                                    )
                                )
                            }
                            "dancing" -> {
                                // WIDE smile with rhythm - dancing expression!
                                val dancePhase = (danceBounce * 2f).toInt() % 2
                                val smileWidth = width * (0.7f + mouthAnimation * 0.3f + danceBounce * 0.2f)
                                val smileHeight = height * (1.2f + mouthAnimation * 0.3f + danceBounce * 0.3f)
                                mouthPath.moveTo(centerX - smileWidth / 2, centerY)
                                mouthPath.quadraticBezierTo(
                                    centerX, centerY + smileHeight,
                                    centerX + smileWidth / 2, centerY
                                )
                            }
                            "wink" -> {
                                // Playful smirk while winking
                                val smirkWidth = width * (0.5f + mouthAnimation * 0.2f)
                                val smirkHeight = height * 0.4f
                                mouthPath.moveTo(centerX - smirkWidth / 2, centerY)
                                mouthPath.quadraticBezierTo(
                                    centerX, centerY + smirkHeight,
                                    centerX + smirkWidth / 2, centerY + height * 0.2f
                                )
                            }
                            "tongue" -> {
                                // Tongue sticking out - HILARIOUS!
                                val tongueWidth = width * (0.35f + tongueAnimation * 0.15f)
                                val tongueHeight = height * (1.5f + tongueAnimation * 0.8f)
                                
                                // Mouth opening
                                val mouthSize = width * (0.4f + tongueAnimation * 0.1f)
                                mouthPath.addOval(
                                    androidx.compose.ui.geometry.Rect(
                                        centerX - mouthSize / 2,
                                        centerY - mouthSize / 2,
                                        centerX + mouthSize / 2,
                                        centerY + mouthSize / 2 + tongueHeight
                                    )
                                )
                                
                                // Draw tongue separately below
                                val tongueY = centerY + mouthSize / 2 + 2
                                drawPath(
                                    path = Path().apply {
                                        addOval(
                                            androidx.compose.ui.geometry.Rect(
                                                centerX - tongueWidth / 2,
                                                tongueY,
                                                centerX + tongueWidth / 2,
                                                tongueY + tongueHeight
                                            )
                                        )
                                    },
                                    color = Color(0xFFFF69B4), // Pink tongue!
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                            }
                            "party" -> {
                                // MEGA WIDE smile for party mode!
                                val partyIntensity = celebrationBurst
                                val smileWidth = width * (0.75f + mouthAnimation * 0.4f + partyIntensity * 0.2f)
                                val smileHeight = height * (1.3f + mouthAnimation * 0.5f + partyIntensity * 0.3f)
                                mouthPath.moveTo(centerX - smileWidth / 2, centerY)
                                mouthPath.quadraticBezierTo(
                                    centerX, centerY + smileHeight,
                                    centerX + smileWidth / 2, centerY
                                )
                            }
                            "surprised" -> {
                                // PERFECT round O shape
                                val mouthSize = width * (0.35f + mouthAnimation * 0.1f)
                                mouthPath.addOval(
                                    androidx.compose.ui.geometry.Rect(
                                        centerX - mouthSize / 2,
                                        centerY - mouthSize / 2,
                                        centerX + mouthSize / 2,
                                        centerY + mouthSize / 2
                                    )
                                )
                            }
                            "sleepy" -> {
                                // Gentle, small smile when sleepy
                                val smileWidth = width * (0.4f + mouthAnimation * 0.1f)
                                val smileHeight = height * (0.3f + mouthAnimation * 0.1f)
                                mouthPath.moveTo(centerX - smileWidth / 2, centerY)
                                mouthPath.quadraticBezierTo(
                                    centerX, centerY + smileHeight,
                                    centerX + smileWidth / 2, centerY
                                )
                            }
                            else -> {
                                // Cool smirk - VERY COOL
                                val smirkWidth = width * (0.55f + mouthAnimation * 0.15f)
                                val smirkHeight = height * 0.5f
                                mouthPath.moveTo(centerX - smirkWidth / 2, centerY)
                                mouthPath.quadraticBezierTo(
                                    centerX, centerY - smirkHeight,
                                    centerX + smirkWidth / 2, centerY
                                )
                            }
                        }
                        
                        drawPath(
                            path = mouthPath,
                            color = Color.White.copy(alpha = 0.95f + throughputIntensity * 0.05f),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                    }
                }
                
                // CHEEKS - ENHANCED cute blush effect for happy expressions
                if (expressionType == "happy" || expressionType == "excited" || expressionType == "wink" || 
                    expressionType == "dancing" || expressionType == "party") {
                    Row(
                        modifier = Modifier
                            .offset(y = 8.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val cheekSize = when (expressionType) {
                            "party" -> 9.dp * (1f + mouthAnimation * 0.4f + celebrationBurst * 0.2f) // Bigger for party!
                            "dancing" -> 8.dp * (1f + mouthAnimation * 0.35f + danceBounce * 0.15f)
                            else -> 7.dp * (1f + mouthAnimation * 0.3f)
                        }
                        val cheekAlpha = when (expressionType) {
                            "party" -> 0.8f + mouthAnimation * 0.6f + celebrationBurst * 0.2f
                            "dancing" -> 0.75f + mouthAnimation * 0.55f + danceBounce * 0.15f
                            else -> 0.7f + mouthAnimation * 0.5f
                        }
                        
                        // Left cheek - BIGGER and MORE vibrant
                        Box(
                            modifier = Modifier
                                .size(cheekSize)
                                .offset(x = (-9).dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFFFF69B4).copy(alpha = cheekAlpha.coerceIn(0.5f, 1f)),
                                            Color(0xFFFF1493).copy(alpha = 0f)
                                        )
                                    )
                                )
                        )
                        
                        // Right cheek - BIGGER and MORE vibrant
                        Box(
                            modifier = Modifier
                                .size(cheekSize)
                                .offset(x = 9.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFFFF69B4).copy(alpha = cheekAlpha.coerceIn(0.5f, 1f)),
                                            Color(0xFFFF1493).copy(alpha = 0f)
                                        )
                                    )
                                )
                        )
                    }
                }
                
                // SLEEPY ZZZ BUBBLES - Cute effect for sleepy expression!
                if (expressionType == "sleepy") {
                    Row(
                        modifier = Modifier
                            .offset(y = (-20).dp)
                            .offset(x = (faceRotation * 0.3f).dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // ZZZ bubbles floating up
                        val zzzOffset = (mouthAnimation * 8f).dp
                        val zzzAlpha = (0.6f + mouthAnimation * 0.4f).coerceIn(0.4f, 1f)
                        
                        // Left Z bubble
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .offset(x = (-8).dp, y = -zzzOffset)
                                .clip(CircleShape)
                                .background(Color(0xFF88CCFF).copy(alpha = zzzAlpha))
                        )
                        
                        // Middle Z bubble
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .offset(x = 0.dp, y = -zzzOffset * 1.2f)
                                .clip(CircleShape)
                                .background(Color(0xFF88CCFF).copy(alpha = zzzAlpha * 0.9f))
                        )
                        
                        // Right Z bubble
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .offset(x = 8.dp, y = -zzzOffset * 0.8f)
                                .clip(CircleShape)
                                .background(Color(0xFF88CCFF).copy(alpha = zzzAlpha * 0.8f))
                        )
                    }
                }
                
                // SWEAT DROPS for "surprised" expression - ADD MORE CHARACTER!
                if (expressionType == "surprised") {
                    Row(
                        modifier = Modifier
                            .offset(y = (-8).dp)
                            .offset(x = (faceRotation * 0.5f).dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left sweat drop
                        Box(
                            modifier = Modifier
                                .size(3.dp)
                                .offset(x = (-6).dp, y = 2.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF88CCFF).copy(alpha = 0.8f + mouthAnimation * 0.2f))
                        )
                        
                        // Right sweat drop
                        Box(
                            modifier = Modifier
                                .size(3.dp)
                                .offset(x = 6.dp, y = 2.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF88CCFF).copy(alpha = 0.8f + mouthAnimation * 0.2f))
                        )
                    }
                }
            }
        }
        
        // ENHANCED FLOATING PARTICLES - MORE sparkles based on throughput!
        if (throughputIntensity > 0.05f) {
            Canvas(modifier = Modifier.size(160.dp)) {
                val center = Offset(size.width / 2, size.height / 2)
                val particleCount = (throughputIntensity * 18 + sparkleCycle * 8).toInt().coerceIn(3, 25)
                
                for (i in 0 until particleCount) {
                    val angle = (i * 360f / particleCount + eyeRotation * 0.7f) * (kotlin.math.PI / 180f).toFloat()
                    val distance = size.minDimension / 2 * (0.5f + (sparkleCycle + i % 4) * 0.25f)
                    val x = center.x + cos(angle.toDouble()).toFloat() * distance
                    val y = center.y + sin(angle.toDouble()).toFloat() * distance
                    
                    val hue = (rainbowCycle + i * 20f) % 360f
                    val particleSize = (3.dp.toPx() * (1f + throughputIntensity * 0.5f) * (1f + sparkleCycle * 0.4f))
                    
                    // Main particle
                    drawCircle(
                        color = getRainbowColor(hue, 0.9f + sparkleCycle * 0.1f),
                        radius = particleSize,
                        center = Offset(x, y)
                    )
                    
                    // Glow around particle
                    drawCircle(
                        color = getRainbowColor(hue, 0.3f),
                        radius = particleSize * 2,
                        center = Offset(x, y)
                    )
                }
            }
        }
    }
}

