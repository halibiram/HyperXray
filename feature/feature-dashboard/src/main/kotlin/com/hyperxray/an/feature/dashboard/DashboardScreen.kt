package com.hyperxray.an.feature.dashboard

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hyperxray.an.feature.dashboard.components.*
import com.hyperxray.an.core.network.vpn.HyperVpnStateManager
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

// Futuristic Neon Colors
private object NeonColors {
    val Cyan = Color(0xFF00F5FF)
    val Magenta = Color(0xFFFF00FF)
    val Purple = Color(0xFF8B5CF6)
    val Green = Color(0xFF00FF88)
    val Orange = Color(0xFFFF6B00)
    val Pink = Color(0xFFFF0080)
    val Yellow = Color(0xFFFFE500)
    val DeepSpace = Color(0xFF000011)
    val VoidBlack = Color(0xFF0A0A15)
}


// Animated Particle Background
@Composable
private fun ParticleBackground(
    modifier: Modifier = Modifier,
    particleCount: Int = 50
) {
    val infiniteTransition = rememberInfiniteTransition(label = "particles")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particleTime"
    )
    
    // Pre-generate particles outside Canvas
    val particles = remember {
        List(particleCount) { index ->
            Triple(
                (index * 37 % 100) / 100f, // normalized x position
                (index * 53 % 100) / 100f, // normalized y position
                ((index % 3) + 1).toFloat() // radius 1-3
            )
        }
    }
    
    Canvas(modifier = modifier.fillMaxSize()) {
        particles.forEachIndexed { index, (normX, normY, radius) ->
            val baseX = normX * size.width
            val baseY = normY * size.height
            val offset = (time + index * 7) % 360
            val x = baseX + sin(Math.toRadians(offset.toDouble())).toFloat() * 20
            val y = baseY + cos(Math.toRadians(offset.toDouble())).toFloat() * 20
            val alpha = (0.1f + (sin(Math.toRadians((time * 2 + index * 10).toDouble())).toFloat() + 1) * 0.15f)
            
            drawCircle(
                color = NeonColors.Cyan.copy(alpha = alpha),
                radius = radius,
                center = Offset(x, y)
            )
        }
    }
}

// Animated Neon Border
@Composable
private fun Modifier.animatedNeonBorder(
    colors: List<Color> = listOf(NeonColors.Cyan, NeonColors.Magenta, NeonColors.Purple),
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    borderWidth: Float = 2f
): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "neonBorder")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "borderOffset"
    )
    
    return this.border(
        width = borderWidth.dp,
        brush = Brush.sweepGradient(
            colors = colors + colors.first(),
            center = Offset(offset * 1000, offset * 1000)
        ),
        shape = shape
    )
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
                    NeonColors.Cyan.copy(alpha = 0.1f),
                    NeonColors.Magenta.copy(alpha = 0.15f),
                    NeonColors.Purple.copy(alpha = 0.1f),
                    Color.Transparent
                ),
                start = Offset(shimmerOffset, 0f),
                end = Offset(shimmerOffset + 300f, size.height)
            ),
            blendMode = BlendMode.Screen
        )
    }
}

// Pulsing Glow Effect
@Composable
private fun Modifier.pulsingGlow(
    color: Color = NeonColors.Cyan,
    radius: Float = 20f
): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    
    return this.drawBehind {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = alpha * 0.5f),
                    color.copy(alpha = alpha * 0.2f),
                    Color.Transparent
                ),
                radius = radius * 3
            )
        )
    }
}


// Futuristic Header with Animated Elements
@Composable
private fun FuturisticHeader(
    isServiceEnabled: Boolean,
    connectionActiveColor: Color
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val isSmallScreen = screenWidthDp < 360
    val isCompact = screenWidthDp < 400
    
    val infiniteTransition = rememberInfiniteTransition(label = "header")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    // Responsive values
    val cardPadding = if (isSmallScreen) 16.dp else if (isCompact) 20.dp else 28.dp
    val cornerRadius = if (isSmallScreen) 20.dp else 28.dp
    val titleLetterSpacing = if (isSmallScreen) 1.sp else if (isCompact) 2.sp else 4.sp
    val subtitleLetterSpacing = if (isSmallScreen) 0.5.sp else 2.sp
    val orbSize = if (isSmallScreen) 48.dp else 60.dp
    val orbRingSize = if (isSmallScreen) 44.dp else 56.dp
    val orbCoreSize = if (isSmallScreen) 18.dp else 24.dp
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0A15).copy(alpha = 0.95f),
                        Color(0xFF050510).copy(alpha = 0.9f)
                    )
                )
            )
            .animatedNeonBorder(
                colors = if (isServiceEnabled) {
                    listOf(NeonColors.Cyan, NeonColors.Green, NeonColors.Purple)
                } else {
                    listOf(Color(0xFF1A1A2E), Color(0xFF16213E), Color(0xFF1A1A2E))
                },
                shape = RoundedCornerShape(cornerRadius)
            )
            .holographicShimmer()
            .padding(cardPadding)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // Glowing Title - responsive typography
                    Box {
                        Text(
                            text = "HYPERXRAY",
                            style = if (isSmallScreen) {
                                MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = titleLetterSpacing,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else if (isCompact) {
                                MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = titleLetterSpacing,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                MaterialTheme.typography.displaySmall.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = titleLetterSpacing,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            color = NeonColors.Cyan.copy(alpha = 0.3f),
                            modifier = Modifier.blur(if (isSmallScreen) 4.dp else 8.dp)
                        )
                        Text(
                            text = "HYPERXRAY",
                            style = if (isSmallScreen) {
                                MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = titleLetterSpacing,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else if (isCompact) {
                                MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = titleLetterSpacing,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                MaterialTheme.typography.displaySmall.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = titleLetterSpacing,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(if (isSmallScreen) 4.dp else 8.dp))
                    Text(
                        text = if (isServiceEnabled) "⚡ QUANTUM TUNNEL ACTIVE" else "◉ STANDBY MODE",
                        style = (if (isSmallScreen) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium).copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = subtitleLetterSpacing,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = if (isServiceEnabled) NeonColors.Green else Color(0xFF606080),
                        maxLines = 1
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // Animated Status Orb
                Box(
                    modifier = Modifier
                        .size(orbSize)
                        .pulsingGlow(
                            color = if (isServiceEnabled) NeonColors.Green else Color(0xFF404060),
                            radius = if (isSmallScreen) 30f else 40f
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer rotating ring
                    val orbColors = if (isServiceEnabled) {
                        listOf(NeonColors.Cyan, NeonColors.Green, NeonColors.Purple, NeonColors.Cyan)
                    } else {
                        listOf(Color(0xFF303050), Color(0xFF404060), Color(0xFF303050))
                    }
                    Canvas(modifier = Modifier.size(orbRingSize)) {
                        drawArc(
                            brush = Brush.sweepGradient(colors = orbColors),
                            startAngle = rotationAngle,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = Stroke(width = if (isSmallScreen) 2f else 3f)
                        )
                    }
                    // Inner pulsing core
                    Box(
                        modifier = Modifier
                            .size((orbCoreSize.value * pulseScale).dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = if (isServiceEnabled) {
                                        listOf(NeonColors.Green, NeonColors.Green.copy(alpha = 0.5f), Color.Transparent)
                                    } else {
                                        listOf(Color(0xFF404060), Color(0xFF303050), Color.Transparent)
                                    }
                                )
                            )
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(if (isSmallScreen) 10.dp else 16.dp))
            
            // Animated Time Display - responsive layout
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (isSmallScreen) 6.dp else 12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Blinking indicator
                val blinkAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(500),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "blink"
                )
                Box(
                    modifier = Modifier
                        .size(if (isSmallScreen) 4.dp else 6.dp)
                        .clip(CircleShape)
                        .background(NeonColors.Cyan.copy(alpha = blinkAlpha))
                )
                Text(
                    text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
                    style = (if (isSmallScreen) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge).copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = if (isSmallScreen) 0.5.sp else 2.sp
                    ),
                    color = NeonColors.Cyan.copy(alpha = 0.8f)
                )
                Text(
                    text = "•",
                    color = Color(0xFF404060)
                )
                Text(
                    text = SimpleDateFormat(if (isSmallScreen) "MMM d" else "EEEE, MMM d", Locale.getDefault()).format(Date()),
                    style = (if (isSmallScreen) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium).copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = Color(0xFF808090),
                    maxLines = 1
                )
            }
        }
    }
}


// Futuristic Stat Card with Neon Effects
@Composable
private fun FuturisticStatCard(
    title: String,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
    animationDelay: Int = 0,
    content: @Composable ColumnScope.() -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        delay(animationDelay.toLong())
        visible = true
    }
    
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(500)) + slideInVertically(
            initialOffsetY = { it / 2 },
            animationSpec = tween(500, easing = FastOutSlowInEasing)
        ),
        exit = fadeOut()
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0A0A15).copy(alpha = 0.9f),
                            Color(0xFF050510).copy(alpha = 0.85f)
                        )
                    )
                )
                .animatedNeonBorder(colors = gradientColors)
                .padding(24.dp)
        ) {
            Column {
                // Title with glow
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(4.dp, 20.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                Brush.verticalGradient(gradientColors)
                            )
                    )
                    Box {
                        Text(
                            text = title.uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = gradientColors.first().copy(alpha = 0.4f),
                            modifier = Modifier.blur(4.dp)
                        )
                        Text(
                            text = title.uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = Color.White
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                content()
            }
        }
    }
}

// Neon Stat Row
@Composable
private fun NeonStatRow(
    label: String,
    value: String,
    color: Color = NeonColors.Cyan
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = Color(0xFF808090)
        )
        Box {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                ),
                color = color.copy(alpha = 0.3f),
                modifier = Modifier.blur(3.dp)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                ),
                color = color
            )
        }
    }
}


// Animated Progress Ring with Neon Glow
@Composable
private fun NeonProgressRing(
    progress: Float,
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ring")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringGlow"
    )
    
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "progress"
    )
    
    val density = LocalDensity.current
    val glowStrokeWidth = with(density) { 16.dp.toPx() }
    val trackStrokeWidth = with(density) { 8.dp.toPx() }
    
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(100.dp),
            contentAlignment = Alignment.Center
        ) {
            // Glow layer
            Canvas(modifier = Modifier.size(100.dp)) {
                drawArc(
                    color = color.copy(alpha = glowAlpha * 0.3f),
                    startAngle = -90f,
                    sweepAngle = animatedProgress * 360f,
                    useCenter = false,
                    style = Stroke(width = glowStrokeWidth, cap = StrokeCap.Round)
                )
            }
            // Track
            Canvas(modifier = Modifier.size(90.dp)) {
                drawArc(
                    color = Color(0xFF1A1A2E),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = trackStrokeWidth)
                )
            }
            // Progress
            Canvas(modifier = Modifier.size(90.dp)) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(color, color.copy(alpha = 0.5f), color)
                    ),
                    startAngle = -90f,
                    sweepAngle = animatedProgress * 360f,
                    useCenter = false,
                    style = Stroke(width = trackStrokeWidth, cap = StrokeCap.Round)
                )
            }
            // Value
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = color
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
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

// Quick Insight Box with Neon Effect
@Composable
private fun NeonInsightBox(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        color.copy(alpha = 0.1f),
                        color.copy(alpha = 0.05f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(color.copy(alpha = 0.5f), color.copy(alpha = 0.2f))
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                ),
                color = Color(0xFF808090)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = color.copy(alpha = 0.3f),
                    modifier = Modifier.blur(3.dp)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = color
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    resources: DashboardResources
) {
    val coreStats by viewModel.coreStatsState.collectAsState()
    val telemetryState by viewModel.telemetryState.collectAsState()
    val dnsCacheStats by viewModel.dnsCacheStats.collectAsState()
    val isServiceEnabled by viewModel.isServiceEnabled.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    val isRecentlyConnected = remember { mutableStateOf(false) }

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            isRecentlyConnected.value = true
            viewModel.updateTelemetryStats()
            viewModel.updateDnsCacheStats()
            delay(10000L)
            isRecentlyConnected.value = false
        } else {
            isRecentlyConnected.value = false
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.updateCoreStats()
            viewModel.updateTelemetryStats()
            viewModel.updateDnsCacheStats()
            
            while (true) {
                viewModel.updateTelemetryStats()
                viewModel.updateDnsCacheStats()
                delay(1000L)
            }
        }
    }

    val shouldShowTrafficChart = remember(isServiceEnabled) { isServiceEnabled }
    val lazyListState = rememberLazyListState()
    
    val performanceMetrics = remember(coreStats) {
        derivedStateOf {
            val totalThroughput = coreStats.uplinkThroughput + coreStats.downlinkThroughput
            val maxThroughput = 10_000_000.0
            val throughputRatio = (totalThroughput / maxThroughput).toFloat().coerceIn(0f, 1f)
            val memoryUsage = if (coreStats.sys > 0) {
                (coreStats.alloc.toFloat() / coreStats.sys.toFloat()).coerceIn(0f, 1f)
            } else 0f
            Triple(totalThroughput, throughputRatio, memoryUsage)
        }
    }
    
    // Theme colors
    val performanceGradient = listOf(NeonColors.Cyan, NeonColors.Purple, NeonColors.Magenta)
    val trafficGradient = listOf(NeonColors.Cyan, NeonColors.Magenta, NeonColors.Pink)
    val systemGradient = listOf(NeonColors.Green, NeonColors.Cyan, NeonColors.Purple)
    val memoryGradient = listOf(NeonColors.Yellow, NeonColors.Orange, NeonColors.Pink)
    val telemetryGradient = listOf(NeonColors.Pink, NeonColors.Magenta, NeonColors.Purple)
    val connectionActiveColor = NeonColors.Green
    val errorColor = Color(0xFFFF3366)
    val warningColor = NeonColors.Yellow

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val isTablet = screenWidthDp >= 600
    val isSmallScreen = screenWidthDp < 360
    val horizontalPadding = when {
        isTablet -> 32.dp
        isSmallScreen -> 8.dp
        else -> 16.dp
    }
    val cardSpacing = when {
        isTablet -> 24.dp
        isSmallScreen -> 12.dp
        else -> 16.dp
    }


    // Animated time for header refresh
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(1000L)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Deep Space Background with Gradient
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            NeonColors.DeepSpace,
                            Color(0xFF050510),
                            NeonColors.VoidBlack,
                            NeonColors.DeepSpace
                        )
                    )
                )
        )
        
        // Animated Particle Background
        if (isServiceEnabled) {
            ParticleBackground(particleCount = 30)
        }
        
        // Scan Lines Effect
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    var y = 0f
                    while (y < size.height) {
                        drawLine(
                            color = Color.White.copy(alpha = 0.02f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                        y += 3f
                    }
                }
        )
        
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(cardSpacing),
            userScrollEnabled = isServiceEnabled
        ) {
            // Futuristic Header
            item(key = "header") {
                FuturisticHeader(
                    isServiceEnabled = isServiceEnabled,
                    connectionActiveColor = connectionActiveColor
                )
            }
            
            // HyperVPN Control Card
            item(key = "hyper_vpn_control") {
                val hyperVpnState by (viewModel.hyperVpnState ?: kotlinx.coroutines.flow.MutableStateFlow(
                    HyperVpnStateManager.VpnState.Disconnected
                )).collectAsState()
                val hyperVpnStats by (viewModel.hyperVpnStats ?: kotlinx.coroutines.flow.MutableStateFlow(
                    HyperVpnStateManager.TunnelStats()
                )).collectAsState()
                val hyperVpnError by (viewModel.hyperVpnError ?: kotlinx.coroutines.flow.MutableStateFlow<String?>(null)).collectAsState()
                
                HyperVpnControlCard(
                    state = hyperVpnState,
                    stats = hyperVpnStats,
                    error = hyperVpnError,
                    onStartClick = { viewModel.startHyperVpn() },
                    onStopClick = { viewModel.stopHyperVpn() },
                    onClearError = { viewModel.clearHyperVpnError() }
                )
            }

            // WARP Account Card
            item(key = "warp_account") {
                val warpAccountInfo by (viewModel.warpAccountInfo ?: kotlinx.coroutines.flow.MutableStateFlow(
                    WarpAccountInfo()
                )).collectAsState()
                
                WarpAccountCard(
                    accountExists = warpAccountInfo.accountExists,
                    publicKey = warpAccountInfo.publicKey,
                    endpoint = warpAccountInfo.endpoint,
                    accountType = warpAccountInfo.accountType,
                    license = warpAccountInfo.license,
                    warpEnabled = warpAccountInfo.warpEnabled
                )
            }


            // Traffic Chart
            item(key = "traffic_chart") {
                val hyperVpnStats by (viewModel.hyperVpnStats ?: kotlinx.coroutines.flow.MutableStateFlow(
                    HyperVpnStateManager.TunnelStats()
                )).collectAsState()
                
                var previousTxBytes by remember { mutableStateOf(0L) }
                var previousRxBytes by remember { mutableStateOf(0L) }
                var lastUpdateTime by remember { mutableStateOf(System.currentTimeMillis()) }
                var previousUptime by remember { mutableStateOf(0L) }
                var txThroughput by remember { mutableStateOf(0.0) }
                var rxThroughput by remember { mutableStateOf(0.0) }
                
                val isConnectedKey = hyperVpnStats.uptime > 0L
                
                LaunchedEffect(isConnectedKey) {
                    val wasConnected = previousUptime > 0L
                    val isConnected = hyperVpnStats.uptime > 0L
                    
                    if (wasConnected != isConnected) {
                        if (!isConnected && wasConnected) {
                            previousTxBytes = 0L
                            previousRxBytes = 0L
                            txThroughput = 0.0
                            rxThroughput = 0.0
                        } else if (isConnected && !wasConnected) {
                            previousTxBytes = hyperVpnStats.txBytes
                            previousRxBytes = hyperVpnStats.rxBytes
                            lastUpdateTime = System.currentTimeMillis()
                        }
                        previousUptime = hyperVpnStats.uptime
                    }
                }
                
                val currentStats = rememberUpdatedState(hyperVpnStats)
                val currentUptime = rememberUpdatedState(hyperVpnStats.uptime)
                
                LaunchedEffect(Unit) {
                    while (true) {
                        val stats = currentStats.value
                        val uptime = currentUptime.value
                        
                        if (uptime > 0) {
                            val currentTimeMs = System.currentTimeMillis()
                            val timeDelta = ((currentTimeMs - lastUpdateTime).coerceAtLeast(1000L)) / 1000.0
                            
                            if (timeDelta > 0 && previousTxBytes > 0 && previousRxBytes > 0) {
                                val txDiff = (stats.txBytes - previousTxBytes).coerceAtLeast(0L)
                                val rxDiff = (stats.rxBytes - previousRxBytes).coerceAtLeast(0L)
                                txThroughput = txDiff / timeDelta
                                rxThroughput = rxDiff / timeDelta
                            } else if (previousTxBytes == 0L && previousRxBytes == 0L && stats.txBytes > 0) {
                                previousTxBytes = stats.txBytes
                                previousRxBytes = stats.rxBytes
                                lastUpdateTime = currentTimeMs
                            }
                            
                            previousTxBytes = stats.txBytes
                            previousRxBytes = stats.rxBytes
                            lastUpdateTime = currentTimeMs
                        } else {
                            txThroughput = 0.0
                            rxThroughput = 0.0
                        }
                        delay(1000L)
                    }
                }
                
                AnimatedVisibility(
                    visible = shouldShowTrafficChart,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    FuturisticTrafficChart(
                        uplinkThroughput = txThroughput,
                        downlinkThroughput = rxThroughput
                    )
                }
            }


            // Performance Card with Neon Rings
            item(key = "performance") {
                AnimatedVisibility(
                    visible = shouldShowTrafficChart,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val (totalThroughput, throughputRatio, memoryUsage) = performanceMetrics.value
                    
                    FuturisticStatCard(
                        title = "Performance Matrix",
                        gradientColors = performanceGradient,
                        animationDelay = 100
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            NeonProgressRing(
                                progress = throughputRatio,
                                label = "THROUGHPUT",
                                value = formatThroughput(totalThroughput),
                                color = NeonColors.Cyan
                            )
                            NeonProgressRing(
                                progress = memoryUsage,
                                label = "MEMORY",
                                value = "${(memoryUsage * 100).toInt()}%",
                                color = if (memoryUsage > 0.8f) errorColor else NeonColors.Green
                            )
                        }
                    }
                }
            }
            
            // Quick Insights with Neon Boxes
            item(key = "quick_insights") {
                AnimatedVisibility(
                    visible = isServiceEnabled && telemetryState != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    FuturisticStatCard(
                        title = "Network Telemetry",
                        gradientColors = telemetryGradient,
                        animationDelay = 200
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                NeonInsightBox(
                                    label = "AVG RTT",
                                    value = formatRtt(telemetryState!!.rttP95),
                                    color = NeonColors.Cyan,
                                    modifier = Modifier.weight(1f)
                                )
                                NeonInsightBox(
                                    label = "PACKET LOSS",
                                    value = formatLoss(telemetryState!!.avgLoss),
                                    color = if (telemetryState!!.avgLoss > 1.0) errorColor else NeonColors.Green,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            NeonInsightBox(
                                label = "HANDSHAKE TIME",
                                value = formatHandshakeTime(telemetryState!!.avgHandshakeTime),
                                color = when {
                                    telemetryState!!.avgHandshakeTime < 200 -> NeonColors.Green
                                    telemetryState!!.avgHandshakeTime < 300 -> warningColor
                                    else -> errorColor
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }


            // System Stats Card
            item(key = "system_stats") {
                AnimatedVisibility(
                    visible = isServiceEnabled,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    FuturisticStatCard(
                        title = "System Core",
                        gradientColors = systemGradient,
                        animationDelay = 300
                    ) {
                        NeonStatRow(
                            label = stringResource(id = resources.stringStatsNumGoroutine),
                            value = formatNumber(coreStats.numGoroutine.toLong()),
                            color = NeonColors.Green
                        )
                        NeonStatRow(
                            label = stringResource(id = resources.stringStatsNumGc),
                            value = formatNumber(coreStats.numGC.toLong()),
                            color = NeonColors.Cyan
                        )
                        NeonStatRow(
                            label = stringResource(id = resources.stringStatsUptime),
                            value = formatUptime(coreStats.uptime),
                            color = NeonColors.Purple
                        )
                    }
                }
            }

            // Memory Stats Card
            item(key = "memory_stats") {
                val androidMemoryStats by (viewModel.androidMemoryStats ?: kotlinx.coroutines.flow.MutableStateFlow(
                    AndroidMemoryStats()
                )).collectAsState()
                
                AnimatedVisibility(
                    visible = isServiceEnabled,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    FuturisticStatCard(
                        title = "Memory Analysis",
                        gradientColors = memoryGradient,
                        animationDelay = 400
                    ) {
                        // Go Runtime Section
                        Text(
                            text = "◈ GO RUNTIME",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp
                            ),
                            color = NeonColors.Yellow,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        if (androidMemoryStats.goAlloc > 0L || androidMemoryStats.goSys > 0L) {
                            NeonStatRow(stringResource(id = resources.stringStatsAlloc), formatBytes(androidMemoryStats.goAlloc), NeonColors.Yellow)
                            NeonStatRow("Total Alloc", formatBytes(androidMemoryStats.goTotalAlloc), NeonColors.Orange)
                            NeonStatRow("Sys", formatBytes(androidMemoryStats.goSys), NeonColors.Pink)
                            NeonStatRow("Live Objects", formatNumber(androidMemoryStats.goLiveObjects), NeonColors.Cyan)
                        } else {
                            NeonStatRow(stringResource(id = resources.stringStatsAlloc), formatBytes(coreStats.alloc), NeonColors.Yellow)
                            NeonStatRow("Total Alloc", formatBytes(coreStats.totalAlloc), NeonColors.Orange)
                            NeonStatRow("Sys", formatBytes(coreStats.sys), NeonColors.Pink)
                            NeonStatRow("Live Objects", formatNumber(coreStats.liveObjects), NeonColors.Cyan)
                        }
                        
                        // Android System Section
                        if (androidMemoryStats.totalPss > 0L) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "◈ ANDROID SYSTEM",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 2.sp
                                ),
                                color = NeonColors.Cyan,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            NeonStatRow("Total PSS", formatBytes(androidMemoryStats.totalPss), NeonColors.Cyan)
                            NeonStatRow("Native Heap", formatBytes(androidMemoryStats.nativeHeap), NeonColors.Green)
                            NeonStatRow("Dalvik Heap", formatBytes(androidMemoryStats.dalvikHeap), NeonColors.Purple)
                            NeonStatRow("Process Usage", "${androidMemoryStats.processMemoryUsagePercent}%", NeonColors.Magenta)
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "◈ SYSTEM MEMORY",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 2.sp
                                ),
                                color = NeonColors.Green,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            NeonStatRow("Total", formatBytes(androidMemoryStats.systemTotalMem), NeonColors.Green)
                            NeonStatRow("Available", formatBytes(androidMemoryStats.systemAvailMem), NeonColors.Cyan)
                            NeonStatRow("System Usage", "${androidMemoryStats.systemMemoryUsagePercent}%", 
                                if (androidMemoryStats.systemLowMemory) errorColor else NeonColors.Green)
                        }
                    }
                }
            }
        }
    }
}
