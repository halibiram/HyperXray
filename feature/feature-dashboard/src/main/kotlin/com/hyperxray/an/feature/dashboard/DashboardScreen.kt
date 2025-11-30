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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
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
import com.hyperxray.an.feature.dashboard.components.DnsCacheCard
import com.hyperxray.an.feature.dashboard.components.ModernStatCard
import com.hyperxray.an.feature.dashboard.components.PerformanceIndicator
import com.hyperxray.an.feature.dashboard.components.StatRow
import com.hyperxray.an.feature.dashboard.components.FuturisticTrafficChart
import com.hyperxray.an.feature.dashboard.components.HyperVpnControlCard
import com.hyperxray.an.feature.dashboard.components.WarpAccountCard

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
import com.hyperxray.an.core.network.vpn.HyperVpnStateManager

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

    // Note: Removed updateCoreStats() call here to prevent double polling
    // Core stats are automatically updated by XrayStatsManager's internal monitoring loop
    LaunchedEffect(connectionState) {
        if (connectionState is com.hyperxray.an.feature.dashboard.ConnectionState.Connected) {
            isRecentlyConnected.value = true
            // Only update DNS cache and telemetry stats immediately
            // Core stats will be updated by XrayStatsManager automatically
            viewModel.updateTelemetryStats()
            viewModel.updateDnsCacheStats()
            // Fast poll for 10 seconds to ensure immediate stats for DNS/Telemetry
            delay(10000L)
            isRecentlyConnected.value = false
        } else {
            isRecentlyConnected.value = false
        }
    }

    // CRITICAL: Use lifecycle-aware LaunchedEffect to ensure stats are refreshed
    // when screen becomes visible (especially after process death or bring-to-front)
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Immediately refresh stats when screen becomes visible
            // This ensures cards update correctly after process death or bring-to-front
            viewModel.updateCoreStats()
            viewModel.updateTelemetryStats()
            viewModel.updateDnsCacheStats()
            
            // Then continue with regular polling
            while (true) {
                // Only update DNS cache and telemetry stats here
                // updateCoreStats() is removed to prevent double polling
                viewModel.updateTelemetryStats()
                viewModel.updateDnsCacheStats()
                
                // Fixed interval: 1000ms (1 second)
                // Core stats are updated by XrayStatsManager at its own interval
                delay(1000L)
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
        // HyperVpnService Control Card
        item(key = "hyper_vpn_control") {
            val hyperVpnState by (viewModel.hyperVpnState ?: kotlinx.coroutines.flow.MutableStateFlow(
                com.hyperxray.an.core.network.vpn.HyperVpnStateManager.VpnState.Disconnected
            )).collectAsState()
            val hyperVpnStats by (viewModel.hyperVpnStats ?: kotlinx.coroutines.flow.MutableStateFlow(
                com.hyperxray.an.core.network.vpn.HyperVpnStateManager.TunnelStats()
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

        // Traffic Chart (always show if connected, prevents flickering) - Animated
        item(key = "traffic_chart") {
            val hyperVpnStats by (viewModel.hyperVpnStats ?: kotlinx.coroutines.flow.MutableStateFlow(
                com.hyperxray.an.core.network.vpn.HyperVpnStateManager.TunnelStats()
            )).collectAsState()
            
            // Throughput hesaplama için önceki değerleri sakla
            var previousTxBytes by remember { mutableStateOf(0L) }
            var previousRxBytes by remember { mutableStateOf(0L) }
            var lastUpdateTime by remember { mutableStateOf(System.currentTimeMillis()) }
            var previousUptime by remember { mutableStateOf(0L) }
            var txThroughput by remember { mutableStateOf(0.0) }
            var rxThroughput by remember { mutableStateOf(0.0) }
            
            // Bağlantı durumu değiştiğinde önceki değerleri sıfırla
            // Sadece bağlantı başladığında (0 -> >0) veya kesildiğinde (>0 -> 0) tetikle
            // Uptime'ın 0 olup olmadığını key olarak kullan (sadece 0 <-> >0 geçişlerinde tetiklenir)
            val isConnectedKey = hyperVpnStats.uptime > 0L
            
            LaunchedEffect(isConnectedKey) {
                val wasConnected = previousUptime > 0L
                val isConnected = hyperVpnStats.uptime > 0L
                
                if (wasConnected != isConnected) {
                    // Bağlantı durumu değişti
                    if (!isConnected && wasConnected) {
                        // Bağlantı kesildi - değerleri sıfırla
                        previousTxBytes = 0L
                        previousRxBytes = 0L
                        txThroughput = 0.0
                        rxThroughput = 0.0
                    } else if (isConnected && !wasConnected) {
                        // Yeni bağlantı başladı - önceki değerleri sıfırla
                        previousTxBytes = hyperVpnStats.txBytes
                        previousRxBytes = hyperVpnStats.rxBytes
                        lastUpdateTime = System.currentTimeMillis()
                    }
                    previousUptime = hyperVpnStats.uptime
                }
            }
            
            // Throughput hesaplama - HyperVpnStateManager verilerini kullan
            // Uptime'a göre güncelleme yap (sadece bağlıyken)
            // Use rememberUpdatedState to track latest stats without restarting coroutine
            val currentStats = rememberUpdatedState(hyperVpnStats)
            val currentUptime = rememberUpdatedState(hyperVpnStats.uptime)
            
            LaunchedEffect(Unit) {
                while (true) {
                    val stats = currentStats.value
                    val uptime = currentUptime.value
                    
                    // Sadece uptime > 0 olduğunda (bağlıyken) güncelleme yap
                    if (uptime > 0) {
                        val currentTime = System.currentTimeMillis()
                        val timeDelta = ((currentTime - lastUpdateTime).coerceAtLeast(1000L)) / 1000.0 // seconds (minimum 1 saniye)
                        
                        if (timeDelta > 0 && previousTxBytes > 0 && previousRxBytes > 0) {
                            val txDiff = (stats.txBytes - previousTxBytes).coerceAtLeast(0L)
                            val rxDiff = (stats.rxBytes - previousRxBytes).coerceAtLeast(0L)
                            
                            txThroughput = txDiff / timeDelta
                            rxThroughput = rxDiff / timeDelta
                        } else if (previousTxBytes == 0L && previousRxBytes == 0L && stats.txBytes > 0) {
                            // İlk güncelleme - önceki değerleri set et
                            previousTxBytes = stats.txBytes
                            previousRxBytes = stats.rxBytes
                            lastUpdateTime = currentTime
                        }
                        
                        previousTxBytes = stats.txBytes
                        previousRxBytes = stats.rxBytes
                        lastUpdateTime = currentTime
                    } else {
                        // Bağlantı yok - throughput'u sıfırla
                        txThroughput = 0.0
                        rxThroughput = 0.0
                    }
                    
                    // Veriler 1 saniyede bir geldiği için 1 saniyede bir güncelle
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
        val androidMemoryStats by (viewModel.androidMemoryStats ?: kotlinx.coroutines.flow.MutableStateFlow(
            com.hyperxray.an.feature.dashboard.AndroidMemoryStats()
        )).collectAsState()
        
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
                    // Go Runtime Memory Section (from native Xray-core)
                    // Show Go runtime stats from androidMemoryStats which gets data from native Xray
                    if (androidMemoryStats.goAlloc > 0L || androidMemoryStats.goSys > 0L) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Go Runtime",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = memoryGradient.first(),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            StatRow(
                                label = stringResource(id = resources.stringStatsAlloc),
                                value = formatBytes(androidMemoryStats.goAlloc)
                            )
                            StatRow(
                                label = "Total Alloc",
                                value = formatBytes(androidMemoryStats.goTotalAlloc)
                            )
                            StatRow(
                                label = "Sys",
                                value = formatBytes(androidMemoryStats.goSys)
                            )
                            StatRow(
                                label = "Mallocs",
                                value = formatNumber(androidMemoryStats.goMallocs)
                            )
                            StatRow(
                                label = "Frees",
                                value = formatNumber(androidMemoryStats.goFrees)
                            )
                            StatRow(
                                label = "Live Objects",
                                value = formatNumber(androidMemoryStats.goLiveObjects)
                            )
                            StatRow(
                                label = "GC Pause Total",
                                value = formatUptime((androidMemoryStats.goPauseTotalNs / 1_000_000_000).toInt())
                            )
                        }
                    } else {
                        // Fallback to coreStats if androidMemoryStats doesn't have Go runtime data yet
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Go Runtime",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = memoryGradient.first(),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
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
                    }
                    
                    // Android System Memory Section
                    if (androidMemoryStats.totalPss > 0L) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Android System",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = memoryGradient.first(),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            StatRow(
                                label = "Total PSS",
                                value = formatBytes(androidMemoryStats.totalPss)
                            )
                            StatRow(
                                label = "Native Heap",
                                value = formatBytes(androidMemoryStats.nativeHeap)
                            )
                            StatRow(
                                label = "Dalvik Heap",
                                value = formatBytes(androidMemoryStats.dalvikHeap)
                            )
                            StatRow(
                                label = "Other PSS",
                                value = formatBytes(androidMemoryStats.otherPss)
                            )
                            StatRow(
                                label = "Process Memory Usage",
                                value = "${androidMemoryStats.processMemoryUsagePercent}%"
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Java Runtime",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = memoryGradient.first(),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            StatRow(
                                label = "Used Memory",
                                value = formatBytes(androidMemoryStats.usedMemory)
                            )
                            StatRow(
                                label = "Max Memory",
                                value = formatBytes(androidMemoryStats.maxMemory)
                            )
                            StatRow(
                                label = "Free Memory",
                                value = formatBytes(androidMemoryStats.freeMemory)
                            )
                            StatRow(
                                label = "Runtime Usage",
                                value = "${androidMemoryStats.runtimeMemoryUsagePercent}%"
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "System Memory",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = memoryGradient.first(),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            StatRow(
                                label = "Total System",
                                value = formatBytes(androidMemoryStats.systemTotalMem)
                            )
                            StatRow(
                                label = "Available",
                                value = formatBytes(androidMemoryStats.systemAvailMem)
                            )
                            StatRow(
                                label = "Used",
                                value = formatBytes(androidMemoryStats.systemUsedMem)
                            )
                            StatRow(
                                label = "System Usage",
                                value = "${androidMemoryStats.systemMemoryUsagePercent}%"
                            )
                            if (androidMemoryStats.systemLowMemory) {
                                StatRow(
                                    label = "Low Memory",
                                    value = "⚠️ Yes"
                                )
                            }
                        }
                    }
                }
            )
        }
    }
    }
    }
}

