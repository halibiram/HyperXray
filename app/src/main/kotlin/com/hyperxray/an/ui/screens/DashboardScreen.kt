package com.hyperxray.an.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hyperxray.an.R
import com.hyperxray.an.common.formatBytes
import com.hyperxray.an.common.formatNumber
import com.hyperxray.an.common.formatUptime
import com.hyperxray.an.common.formatThroughput
import com.hyperxray.an.common.formatRtt
import com.hyperxray.an.common.formatLoss
import com.hyperxray.an.common.formatHandshakeTime
import com.hyperxray.an.ui.screens.dashboard.AnimatedStatCard
import com.hyperxray.an.ui.screens.dashboard.ConnectionStatusCard
import com.hyperxray.an.ui.screens.dashboard.ModernStatCard
import com.hyperxray.an.ui.screens.dashboard.PerformanceIndicator
import com.hyperxray.an.ui.screens.dashboard.QuickStatsCard
import com.hyperxray.an.ui.screens.dashboard.StatRow
import com.hyperxray.an.ui.screens.dashboard.TrafficChartCard
import com.hyperxray.an.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    mainViewModel: MainViewModel,
    onSwitchVpnService: () -> Unit = {}
) {
    val coreStats by mainViewModel.coreStatsState.collectAsState()
    val telemetryState by mainViewModel.telemetryState.collectAsState()
    val isServiceEnabled by mainViewModel.isServiceEnabled.collectAsState()
    val controlMenuClickable by mainViewModel.controlMenuClickable.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        // Poll stats every second while screen is resumed
        // Loop is safe: repeatOnLifecycle cancels when lifecycle state changes
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                mainViewModel.updateCoreStats()
                mainViewModel.updateTelemetryStats()
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
    
    // Cache gradient colors to avoid recreation
    val performanceGradient = remember { listOf(Color(0xFF06B6D4), Color(0xFF3B82F6), Color(0xFF6366F1)) }
    val trafficGradient = remember { listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFA855F7)) }
    val systemGradient = remember { listOf(Color(0xFF10B981), Color(0xFF059669), Color(0xFF047857)) }
    val memoryGradient = remember { listOf(Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFFDC2626)) }
    val telemetryGradient = remember { listOf(Color(0xFFEC4899), Color(0xFFDB2777), Color(0xFFBE185D)) }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        // Optimize: Pre-compose items that are about to become visible
        // This is handled automatically by LazyColumn, but we ensure keys are stable
    ) {
            // Connection Status Hero Card
            item(key = "connection_status") {
                ConnectionStatusCard(
                    isConnected = isServiceEnabled,
                    isClickable = controlMenuClickable,
                    onToggleConnection = onSwitchVpnService,
                    uptime = coreStats.uptime
                )
            }

            // Quick Stats Summary
            item(key = "quick_stats") {
                QuickStatsCard(
                    uplink = coreStats.uplink,
                    downlink = coreStats.downlink,
                    uplinkThroughput = coreStats.uplinkThroughput,
                    downlinkThroughput = coreStats.downlinkThroughput
                )
            }
            
            // Traffic Chart (always show if connected, prevents flickering)
            if (shouldShowTrafficChart) {
                item(key = "traffic_chart") {
                    TrafficChartCard(
                        uplinkThroughput = coreStats.uplinkThroughput,
                        downlinkThroughput = coreStats.downlinkThroughput
                    )
                }
            }
            
            // Performance Indicators (always show if connected, prevents flickering)
            if (shouldShowTrafficChart) {
                item(key = "performance") {
                    val (totalThroughput, throughputRatio, memoryUsage) = performanceMetrics.value
                    
                    ModernStatCard(
                        title = "Performance",
                        iconRes = R.drawable.dashboard,
                        gradientColors = performanceGradient,
                        content = {
                            // Always show throughput indicator if connected (even if 0)
                            PerformanceIndicator(
                                label = "Total Throughput",
                                value = throughputRatio,
                                displayValue = formatThroughput(totalThroughput),
                                color = Color(0xFF06B6D4)
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            PerformanceIndicator(
                                label = "Memory Usage",
                                value = memoryUsage,
                                displayValue = "${(memoryUsage * 100).toInt()}%",
                                color = if (memoryUsage > 0.8f) Color(0xFFEF4444) else Color(0xFF10B981)
                            )
                        }
                    )
                }
            }

        item(key = "traffic") {
            AnimatedStatCard(
                title = "Traffic",
                iconRes = R.drawable.cloud_download,
                gradientColors = trafficGradient,
                animationDelay = 100,
                content = {
                    StatRow(
                        label = stringResource(id = R.string.stats_uplink),
                        value = formatBytes(coreStats.uplink)
                    )
                    StatRow(
                        label = stringResource(id = R.string.stats_downlink),
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

        item(key = "system_stats") {
            AnimatedStatCard(
                title = "System Stats",
                iconRes = R.drawable.dashboard,
                gradientColors = systemGradient,
                animationDelay = 200,
                content = {
                    StatRow(
                        label = stringResource(id = R.string.stats_num_goroutine),
                        value = formatNumber(coreStats.numGoroutine.toLong())
                    )
                    StatRow(
                        label = stringResource(id = R.string.stats_num_gc),
                        value = formatNumber(coreStats.numGC.toLong())
                    )
                    StatRow(
                        label = stringResource(id = R.string.stats_uptime),
                        value = formatUptime(coreStats.uptime)
                    )
                }
            )
        }

        item(key = "memory_stats") {
            AnimatedStatCard(
                title = "Memory Stats",
                iconRes = R.drawable.settings,
                gradientColors = memoryGradient,
                animationDelay = 300,
                content = {
                    StatRow(
                        label = stringResource(id = R.string.stats_alloc),
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

        item(key = "ai_telemetry") {
            AnimatedStatCard(
                title = "AI Telemetry",
                iconRes = R.drawable.optimizer,
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
                            text = stringResource(id = R.string.vpn_disconnected),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            )
        }
    }
}
