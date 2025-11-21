package com.hyperxray.an.feature.dashboard

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.hyperxray.an.feature.dashboard.DashboardColors
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hyperxray.an.feature.dashboard.components.AnimatedStatCard
import com.hyperxray.an.feature.dashboard.components.ConnectionQualityCard
import com.hyperxray.an.feature.dashboard.components.ConnectionStatusCard
import com.hyperxray.an.feature.dashboard.components.DnsCacheCard
import com.hyperxray.an.feature.dashboard.components.InstanceStatusCard
import com.hyperxray.an.feature.dashboard.components.ModernStatCard
import com.hyperxray.an.feature.dashboard.components.PerformanceIndicator
import com.hyperxray.an.feature.dashboard.components.QuickStatsCard
import com.hyperxray.an.feature.dashboard.components.StatRow
import com.hyperxray.an.feature.dashboard.components.FuturisticTrafficChart

import com.hyperxray.an.feature.dashboard.formatBytes
import com.hyperxray.an.feature.dashboard.formatNumber
import com.hyperxray.an.feature.dashboard.formatUptime
import com.hyperxray.an.feature.dashboard.formatThroughput
import com.hyperxray.an.feature.dashboard.formatRtt
import com.hyperxray.an.feature.dashboard.formatLoss
import com.hyperxray.an.feature.dashboard.formatHandshakeTime
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    resources: DashboardResources,
    onSwitchVpnService: () -> Unit = {}
) {
    val coreStats by viewModel.coreStatsState.collectAsState()
    val telemetryState by viewModel.telemetryState.collectAsState()
    val dnsCacheStats by viewModel.dnsCacheStats.collectAsState()
    val isServiceEnabled by viewModel.isServiceEnabled.collectAsState()
    val controlMenuClickable by viewModel.controlMenuClickable.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val instancesStatus by viewModel.instancesStatus.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        // Poll stats every second while screen is resumed
        // Loop is safe: repeatOnLifecycle cancels when lifecycle state changes
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.updateCoreStats()
                viewModel.updateTelemetryStats()
                viewModel.updateDnsCacheStats()
                delay(1000)
            }
        }
    }

    // Stabilize: Use debounced check to prevent flickering
    // Only show/hide when connection state changes, not on every throughput update
    val showTrafficChart = remember(isServiceEnabled) {
        isServiceEnabled
    }
    
    // Stabilize traffic chart visibility - only hide when disconnected
    // Once shown, keep it visible even if throughput temporarily drops to 0
    val shouldShowTrafficChart = remember(isServiceEnabled) {
        isServiceEnabled
    }
    
    // LazyListState with increased prefetch distance for smoother scrolling
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
    
    // Cache theme-aware gradient colors to avoid recreation
    val performanceGradient = DashboardColors.performanceGradient()
    val trafficGradient = DashboardColors.trafficGradient()
    val systemGradient = DashboardColors.systemGradient()
    val memoryGradient = DashboardColors.memoryGradient()
    val telemetryGradient = DashboardColors.telemetryGradient()
    val dnsCacheGradient = DashboardColors.dnsCacheGradient()
    val successColor = DashboardColors.successColor()
    val errorColor = DashboardColors.errorColor()
    val warningColor = DashboardColors.warningColor()
    val connectionActiveColor = DashboardColors.connectionActiveColor()

    // Responsive layout: adjust padding for tablets
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600
    val horizontalPadding = if (isTablet) 32.dp else 16.dp
    val cardSpacing = if (isTablet) 24.dp else 16.dp

    Box(modifier = Modifier.fillMaxSize()) {
        // Obsidian background with subtle gradient
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF000000), // Pure obsidian black
                            Color(0xFF0A0A0A), // Slight gradient for depth
                            Color(0xFF000000)
                        )
                    )
                )
        )
        
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            contentPadding = PaddingValues(
                top = 16.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(cardSpacing),
            userScrollEnabled = isServiceEnabled,
        ) {
                item(key = "header") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF000000).copy(alpha = 0.7f), // Glassmorphism base
                                Color(0xFF0A0A0A).copy(alpha = 0.5f)
                            )
                        )
                    )
                    .border(
                        width = 1.5.dp,
                        brush = Brush.horizontalGradient(
                            colors = if (isServiceEnabled) {
                                listOf(
                                    Color(0xFF00E5FF), // Neon Cyan
                                    Color(0xFF2979FF), // Electric Blue
                                    Color(0xFF651FFF)  // Deep Purple
                                )
                            } else {
                                listOf(
                                    Color(0xFF1A1A1A),
                                    Color(0xFF0F0F0F),
                                    Color(0xFF1A1A1A)
                                )
                            }
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Dashboard",
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.5).sp
                                ),
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            Text(
                                text = if (isServiceEnabled) "Connected and secured" else "Ready to connect",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.2.sp
                                ),
                                color = Color(0xFFB0B0B0)
                            )
                        }
                        
                        // Enhanced Status Badge with Glassmorphism
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (isServiceEnabled) {
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                connectionActiveColor.copy(alpha = 0.15f),
                                                connectionActiveColor.copy(alpha = 0.08f)
                                            )
                                        )
                                    } else {
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color(0xFF1A1A1A).copy(alpha = 0.6f),
                                                Color(0xFF0F0F0F).copy(alpha = 0.4f)
                                            )
                                        )
                                    }
                                )
                                .border(
                                    width = 1.dp,
                                    brush = Brush.linearGradient(
                                        colors = if (isServiceEnabled) {
                                            listOf(
                                                connectionActiveColor.copy(alpha = 0.8f),
                                                connectionActiveColor.copy(alpha = 0.5f)
                                            )
                                        } else {
                                            listOf(
                                                Color(0xFF2A2A2A),
                                                Color(0xFF1A1A1A)
                                            )
                                        }
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Animated Pulse Indicator
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(
                                            if (isServiceEnabled) {
                                                connectionActiveColor
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            }
                                        )
                                )
                                Text(
                                    text = if (isServiceEnabled) "Active" else "Idle",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.3.sp
                                    ),
                                    color = if (isServiceEnabled) {
                                        connectionActiveColor
                                    } else {
                                        Color(0xFF808080)
                                    }
                                )
                            }
                        }
                    }
                    
                    // Current Time with Icon-like Styling
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                        )
                        Text(
                            text = SimpleDateFormat("EEEE, MMMM d • HH:mm", Locale.getDefault()).format(Date()),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.2.sp
                            ),
                            color = Color(0xFF808080)
                        )
                    }
                }
            }
        }
        // Connection Status Hero Card
        item(key = "connection_status") {
            ConnectionStatusCard(
                connectionState = connectionState,
                isClickable = controlMenuClickable,
                onToggleConnection = onSwitchVpnService,
                uptime = coreStats.uptime,
                playIconRes = resources.drawablePlay,
                pauseIconRes = resources.drawablePause,
                uplinkThroughput = coreStats.uplinkThroughput,
                downlinkThroughput = coreStats.downlinkThroughput
            )
        }

        // Instance Status Card - Show Xray-core instance statuses
        item(key = "instance_status") {
            AnimatedVisibility(
                visible = instancesStatus.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                InstanceStatusCard(
                    instancesStatus = instancesStatus
                )
            }
        }

        // Quick Stats Summary - Enhanced with better spacing
        item(key = "quick_stats") {
            AnimatedVisibility(
                visible = isServiceEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                QuickStatsCard(
                    uplink = coreStats.uplink,
                    downlink = coreStats.downlink,
                    uplinkThroughput = coreStats.uplinkThroughput,
                    downlinkThroughput = coreStats.downlinkThroughput
                )
            }
        }
        
        // Summary Stats Row - Enhanced with Modern Glass Cards
        item(key = "summary_stats") {
            AnimatedVisibility(
                visible = isServiceEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Total Traffic Card - Enhanced with Glassmorphism
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFF000000).copy(alpha = 0.6f),
                                        Color(0xFF0A0A0A).copy(alpha = 0.4f)
                                    )
                                )
                            )
                            .border(
                                width = 1.5.dp,
                                brush = Brush.linearGradient(trafficGradient),
                                shape = RoundedCornerShape(24.dp)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        colors = trafficGradient.map { it.copy(alpha = 0.08f) }
                                    )
                                )
                                .padding(20.dp)
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                    Text(
                                        text = "Total Traffic",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            letterSpacing = 0.2.sp
                                        ),
                                        color = Color(0xFFB0B0B0)
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = formatBytes(coreStats.uplink + coreStats.downlink),
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.5).sp
                                    ),
                                    color = trafficGradient.first(),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                    
                    // Connection Time Card - Enhanced with Glassmorphism
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFF000000).copy(alpha = 0.6f),
                                        Color(0xFF0A0A0A).copy(alpha = 0.4f)
                                    )
                                )
                            )
                            .border(
                                width = 1.5.dp,
                                brush = Brush.linearGradient(performanceGradient),
                                shape = RoundedCornerShape(24.dp)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        colors = performanceGradient.map { it.copy(alpha = 0.08f) }
                                    )
                                )
                                .padding(20.dp)
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(connectionActiveColor)
                                    )
                                    Text(
                                        text = "Uptime",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            letterSpacing = 0.2.sp
                                        ),
                                        color = Color(0xFFB0B0B0)
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = formatUptime(coreStats.uptime),
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.5).sp
                                    ),
                                    color = connectionActiveColor,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // Traffic Chart (always show if connected, prevents flickering) - Animated
        item(key = "traffic_chart") {
            AnimatedVisibility(
                visible = shouldShowTrafficChart,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                FuturisticTrafficChart(
                    uplinkThroughput = coreStats.uplinkThroughput,
                    downlinkThroughput = coreStats.downlinkThroughput
                )
            }
        }
        
        // Performance Indicators (always show if connected, prevents flickering) - Animated
        item(key = "performance") {
            AnimatedVisibility(
                visible = shouldShowTrafficChart,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val (totalThroughput, throughputRatio, memoryUsage) = performanceMetrics.value
                
                ModernStatCard(
                    title = "Performance",
                    iconRes = resources.drawableDashboard,
                    gradientColors = performanceGradient,
                    content = {
                        // Always show throughput indicator if connected (even if 0)
                        PerformanceIndicator(
                            label = "Total Throughput",
                            value = throughputRatio,
                            displayValue = formatThroughput(totalThroughput),
                            color = performanceGradient.first()
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        PerformanceIndicator(
                            label = "Memory Usage",
                            value = memoryUsage,
                            displayValue = "${(memoryUsage * 100).toInt()}%",
                            color = if (memoryUsage > 0.8f) errorColor else connectionActiveColor
                        )
                    }
                )
            }
        }
        
        // Network Speed Card - Enhanced Real-time Speed Indicators
        item(key = "network_speed") {
            AnimatedVisibility(
                visible = isServiceEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                ModernStatCard(
                    title = "Network Speed",
                    iconRes = resources.drawableCloudDownload,
                    gradientColors = performanceGradient,
                    content = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            // Upload Speed - Enhanced
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                performanceGradient.first().copy(alpha = 0.15f),
                                                performanceGradient[1].copy(alpha = 0.1f)
                                            )
                                        )
                                    )
                                    .padding(20.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "↑",
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = performanceGradient.first()
                                        )
                                        Text(
                                            text = "Upload",
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = formatThroughput(coreStats.uplinkThroughput),
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = performanceGradient.first(),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            
                            // Download Speed - Enhanced
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                trafficGradient[1].copy(alpha = 0.15f),
                                                trafficGradient[2].copy(alpha = 0.1f)
                                            )
                                        )
                                    )
                                    .padding(20.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "↓",
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = trafficGradient[1]
                                        )
                                        Text(
                                            text = "Download",
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = formatThroughput(coreStats.downlinkThroughput),
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = trafficGradient[1],
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
        
        // Connection Quality Card - Only show when connected and telemetry available
        item(key = "connection_quality") {
            AnimatedVisibility(
                visible = isServiceEnabled && telemetryState != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                ConnectionQualityCard(
                    rtt = telemetryState!!.rttP95,
                    packetLoss = telemetryState!!.avgLoss,
                    handshakeTime = telemetryState!!.avgHandshakeTime
                )
            }
        }
        
        // Quick Insights Section - Enhanced with Modern Design
        item(key = "quick_insights") {
            AnimatedVisibility(
                visible = isServiceEnabled && telemetryState != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                ModernStatCard(
                    title = "Quick Insights",
                    iconRes = resources.drawableOptimizer,
                    gradientColors = telemetryGradient,
                    content = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Average RTT - Enhanced
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(MaterialTheme.shapes.small)
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(
                                                    performanceGradient.first().copy(alpha = 0.15f),
                                                    performanceGradient[1].copy(alpha = 0.1f)
                                                )
                                            )
                                        )
                                        .padding(16.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "Avg RTT",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = formatRtt(telemetryState!!.rttP95),
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = performanceGradient.first()
                                        )
                                    }
                                }
                                
                                // Packet Loss - Enhanced
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(MaterialTheme.shapes.small)
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = if (telemetryState!!.avgLoss > 1.0) {
                                                    listOf(
                                                        errorColor.copy(alpha = 0.15f),
                                                        errorColor.copy(alpha = 0.1f)
                                                    )
                                                } else {
                                                    listOf(
                                                        connectionActiveColor.copy(alpha = 0.15f),
                                                        connectionActiveColor.copy(alpha = 0.1f)
                                                    )
                                                }
                                            )
                                        )
                                        .padding(16.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "Packet Loss",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = formatLoss(telemetryState!!.avgLoss),
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = if (telemetryState!!.avgLoss > 1.0) {
                                                errorColor
                                            } else {
                                                connectionActiveColor
                                            }
                                        )
                                    }
                                }
                            }
                            
                            // Handshake Time - New Addition
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                trafficGradient[1].copy(alpha = 0.15f),
                                                trafficGradient[2].copy(alpha = 0.1f)
                                            )
                                        )
                                    )
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Handshake Time",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = formatHandshakeTime(telemetryState!!.avgHandshakeTime),
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = trafficGradient[1]
                                        )
                                    }
                                    // Quality indicator dot
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (telemetryState!!.avgHandshakeTime < 200) {
                                                    connectionActiveColor
                                                } else if (telemetryState!!.avgHandshakeTime < 300) {
                                                    warningColor
                                                } else {
                                                    errorColor
                                                }
                                            )
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }

    item(key = "traffic") {
        AnimatedVisibility(
            visible = isServiceEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AnimatedStatCard(
                title = "Traffic",
                iconRes = resources.drawableCloudDownload,
                gradientColors = trafficGradient,
                animationDelay = 100,
                content = {
                StatRow(
                    label = stringResource(id = resources.stringStatsUplink),
                    value = formatBytes(coreStats.uplink)
                )
                StatRow(
                    label = stringResource(id = resources.stringStatsDownlink),
                    value = formatBytes(coreStats.downlink)
                )
                StatRow(
                    label = "Uplink Throughput",
                    value = formatThroughput(coreStats.uplinkThroughput)
                )
                StatRow(
                    label = "Downlink Throughput",
                    value = formatThroughput(coreStats.downlinkThroughput)
                )
            }
        )
        }
    }

    item(key = "system_stats") {
        AnimatedVisibility(
            visible = isServiceEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AnimatedStatCard(
                title = "System Stats",
                iconRes = resources.drawableDashboard,
                gradientColors = systemGradient,
                animationDelay = 200,
                content = {
                StatRow(
                    label = stringResource(id = resources.stringStatsNumGoroutine),
                    value = formatNumber(coreStats.numGoroutine.toLong())
                )
                StatRow(
                    label = stringResource(id = resources.stringStatsNumGc),
                    value = formatNumber(coreStats.numGC.toLong())
                )
                StatRow(
                    label = stringResource(id = resources.stringStatsUptime),
                    value = formatUptime(coreStats.uptime)
                )
            }
        )
        }
    }

    item(key = "memory_stats") {
        AnimatedVisibility(
            visible = isServiceEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AnimatedStatCard(
                title = "Memory Stats",
                iconRes = resources.drawableSettings,
                gradientColors = memoryGradient,
                animationDelay = 300,
                content = {
                StatRow(
                    label = stringResource(id = resources.stringStatsAlloc),
                    value = formatBytes(coreStats.alloc)
                )
                StatRow(
                    label = "Total Alloc",
                    value = formatBytes(coreStats.totalAlloc)
                )
                StatRow(
                    label = "Sys",
                    value = formatBytes(coreStats.sys)
                )
                StatRow(
                    label = "Mallocs",
                    value = formatNumber(coreStats.mallocs)
                )
                StatRow(
                    label = "Frees",
                    value = formatNumber(coreStats.frees)
                )
                StatRow(
                    label = "Live Objects",
                    value = formatNumber(coreStats.liveObjects)
                )
                StatRow(
                    label = "GC Pause Total",
                    value = formatUptime((coreStats.pauseTotalNs / 1_000_000_000).toInt())
                )
            }
        )
        }
    }

    item(key = "ai_telemetry") {
        AnimatedVisibility(
            visible = isServiceEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AnimatedStatCard(
                title = "AI Telemetry",
                iconRes = resources.drawableOptimizer,
                gradientColors = telemetryGradient,
                animationDelay = 400,
                content = {
                if (telemetryState != null) {
                    StatRow(
                        label = "Avg Throughput",
                        value = formatThroughput(telemetryState!!.avgThroughput)
                    )
                    StatRow(
                        label = "RTT P95",
                        value = formatRtt(telemetryState!!.rttP95)
                    )
                    StatRow(
                        label = "Avg Handshake Time",
                        value = formatHandshakeTime(telemetryState!!.avgHandshakeTime)
                    )
                    StatRow(
                        label = "Avg Packet Loss",
                        value = formatLoss(telemetryState!!.avgLoss)
                    )
                    StatRow(
                        label = "Sample Count",
                        value = formatNumber(telemetryState!!.sampleCount.toLong())
                    )
                } else {
                    Text(
                        text = stringResource(id = resources.stringVpnDisconnected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        )
        }
    }

    // DNS Cache Card
    item(key = "dns_cache") {
        AnimatedVisibility(
            visible = dnsCacheStats != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            if (dnsCacheStats != null) {
                DnsCacheCard(
                    stats = dnsCacheStats!!,
                    gradientColors = dnsCacheGradient
                )
            }
        }
    }
    }
    }
}

