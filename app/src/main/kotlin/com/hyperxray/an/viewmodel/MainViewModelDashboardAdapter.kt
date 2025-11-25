package com.hyperxray.an.viewmodel

import com.hyperxray.an.feature.dashboard.DashboardViewModel
import com.hyperxray.an.feature.dashboard.CoreStatsState as FeatureCoreStatsState
import com.hyperxray.an.feature.dashboard.AggregatedTelemetry as FeatureAggregatedTelemetry
import com.hyperxray.an.feature.dashboard.DnsCacheStats as FeatureDnsCacheStats
import com.hyperxray.an.core.network.dns.DnsCacheManager
import com.hyperxray.an.telemetry.AggregatedTelemetry
import com.hyperxray.an.xray.runtime.XrayRuntimeStatus
import com.hyperxray.an.viewmodel.MainViewUiEvent
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

private const val TAG = "MainViewModelDashboardAdapter"

/**
 * Extension function to convert app module CoreStatsState to feature module CoreStatsState
 */
fun CoreStatsState.toFeatureState(): FeatureCoreStatsState {
    return FeatureCoreStatsState(
        uplink = uplink,
        downlink = downlink,
        uplinkThroughput = uplinkThroughput,
        downlinkThroughput = downlinkThroughput,
        numGoroutine = numGoroutine,
        numGC = numGC,
        alloc = alloc,
        totalAlloc = totalAlloc,
        sys = sys,
        mallocs = mallocs,
        frees = frees,
        liveObjects = liveObjects,
        pauseTotalNs = pauseTotalNs,
        uptime = uptime
    )
}

/**
 * Extension function to convert app module AggregatedTelemetry to feature module AggregatedTelemetry
 */
fun AggregatedTelemetry.toFeatureState(): FeatureAggregatedTelemetry {
    return FeatureAggregatedTelemetry(
        avgThroughput = avgThroughput,
        rttP95 = rttP95,
        avgHandshakeTime = avgHandshakeTime,
        avgLoss = avgLoss,
        sampleCount = sampleCount
    )
}

/**
 * Extension function to convert DnsCacheManager metrics to feature module DnsCacheStats
 * Maps all fields from the comprehensive DnsCacheMetrics to FeatureDnsCacheStats
 */
private fun com.hyperxray.an.core.network.dns.DnsCacheManager.DnsCacheMetrics.toFeatureState(): FeatureDnsCacheStats {
    // DEBUG: Log latency values to verify they are being passed correctly
    if (avgHitLatencyMs > 0.0 || avgMissLatencyMs > 0.0) {
        Log.d(TAG, "📊 toFeatureState: avgHitLatencyMs=${avgHitLatencyMs}, avgMissLatencyMs=${avgMissLatencyMs}")
    }
    return FeatureDnsCacheStats(
        entryCount = entryCount,
        memoryUsageMB = (memoryUsageBytes / (1024 * 1024)).toLong(), // Convert bytes to MB
        memoryLimitMB = (memoryLimitBytes / (1024 * 1024)).toLong(), // Convert bytes to MB
        memoryUsagePercent = memoryUsagePercent,
        hits = hits,
        misses = misses,
        hitRate = hitRate,
        avgDomainHitRate = avgDomainHitRate,
        avgHitLatencyMs = avgHitLatencyMs,
        avgMissLatencyMs = avgMissLatencyMs,
        avgTtlSeconds = avgTtlSeconds,
        activeEntries = activeEntries.map { entry ->
            com.hyperxray.an.feature.dashboard.DnsCacheEntryUiModel(
                domain = entry.domain,
                ips = entry.ips,
                expiryTime = entry.expiryTime
            )
        }
    )
}

/**
 * MainViewModel implementation of DashboardViewModel
 * Uses cached StateFlow transformations to ensure stable state updates
 */
class MainViewModelDashboardAdapter(private val mainViewModel: MainViewModel) : DashboardViewModel {
    // Cached transformed StateFlows - created once and reused
    // Using SharingStarted.WhileSubscribed(5000) to keep subscription alive during brief configuration changes
    // This prevents unnecessary recreation of upstream flows and reduces memory overhead
    override val coreStatsState: StateFlow<FeatureCoreStatsState> =
        mainViewModel.coreStatsState
            .map { it.toFeatureState() }
            .stateIn(
                scope = mainViewModel.viewModelScope,
                started = SharingStarted.WhileSubscribed(5000), // Keep alive for 5s after last subscriber
                initialValue = mainViewModel.coreStatsState.value.toFeatureState()
            )
    
    override val telemetryState: StateFlow<FeatureAggregatedTelemetry?> =
        mainViewModel.telemetryState
            .map { it?.toFeatureState() }
            .stateIn(
                scope = mainViewModel.viewModelScope,
                started = SharingStarted.WhileSubscribed(5000), // Keep alive for 5s after last subscriber
                initialValue = mainViewModel.telemetryState.value?.toFeatureState()
            )
    
    // Connect directly to DnsCacheManager's StateFlow and map to feature state
    // CRITICAL: Error handling returns default "zero" state instead of null to prevent flow termination
    // CRITICAL: Using SharingStarted.Lazily to keep subscription alive - metrics job runs continuously
    // and we want to ensure StateFlow updates are always available when UI subscribes
    override val dnsCacheStats: StateFlow<FeatureDnsCacheStats?> =
        DnsCacheManager.dashboardStats
            .map { metrics ->
                try {
                    metrics.toFeatureState()
                } catch (e: Exception) {
                    Log.e(TAG, "Error converting DNS cache metrics to feature state", e)
                    // Return default "zero" state instead of null to prevent flow termination
                    FeatureDnsCacheStats(
                        entryCount = 0,
                        memoryUsageMB = 0L,
                        memoryLimitMB = 0L,
                        memoryUsagePercent = 0,
                        hits = 0L,
                        misses = 0L,
                        hitRate = 0,
                        avgDomainHitRate = 0,
                        avgHitLatencyMs = 0.0,
                        avgMissLatencyMs = 0.0,
                        avgTtlSeconds = 0L,
                        activeEntries = emptyList()
                    )
                }
            }
            .stateIn(
                scope = mainViewModel.viewModelScope,
                started = SharingStarted.Lazily, // Keep subscription alive - metrics job runs continuously
                initialValue = try {
                    DnsCacheManager.dashboardStats.value.toFeatureState()
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting initial DNS cache metrics", e)
                    // Return default "zero" state instead of null
                    FeatureDnsCacheStats(
                        entryCount = 0,
                        memoryUsageMB = 0L,
                        memoryLimitMB = 0L,
                        memoryUsagePercent = 0,
                        hits = 0L,
                        misses = 0L,
                        hitRate = 0,
                        avgDomainHitRate = 0,
                        avgHitLatencyMs = 0.0,
                        avgMissLatencyMs = 0.0,
                        avgTtlSeconds = 0L,
                        activeEntries = emptyList()
                    )
                }
            )
    
    override val isServiceEnabled: StateFlow<Boolean> =
        mainViewModel.isServiceEnabled
    
    override val controlMenuClickable: StateFlow<Boolean> =
        mainViewModel.controlMenuClickable
    
    override val connectionState: StateFlow<com.hyperxray.an.feature.dashboard.ConnectionState> =
        mainViewModel.connectionState
    
    override val instancesStatus: StateFlow<Map<Int, XrayRuntimeStatus>> =
        mainViewModel.instancesStatus
    
    override fun updateCoreStats() {
        mainViewModel.viewModelScope.launch {
            mainViewModel.updateCoreStats()
        }
    }
    
    override fun updateTelemetryStats() {
        mainViewModel.viewModelScope.launch {
            mainViewModel.updateTelemetryStats()
        }
    }
    
    override fun updateDnsCacheStats() {
        mainViewModel.viewModelScope.launch {
            try {
                // Try to initialize DnsCacheManager if not already initialized
                // Use prefs to get context (prefs has access to Application)
                val context = mainViewModel.prefs.getContext()
                try {
                    // Initialize if not already initialized
                    DnsCacheManager.initialize(context)
                    
                    // CRITICAL: Ensure metrics job is running even if already initialized
                    // This handles the case where the job was cancelled or failed
                    // and the UI is resubscribing after being away
                    DnsCacheManager.ensureMetricsJobRunning()
                    
                    // Get current metrics to verify StateFlow is working
                    val currentMetrics = DnsCacheManager.dashboardStats.value
                    Log.i(TAG, "✅ DNS cache stats StateFlow connected: entries=${currentMetrics.entryCount}, hits=${currentMetrics.hits}, misses=${currentMetrics.misses}, hitRate=${currentMetrics.hitRate}%")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to initialize DnsCacheManager: ${e.message}", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing DNS cache stats: ${e.message}", e)
            }
        }
    }
    
    override fun clearDnsCache() {
        mainViewModel.viewModelScope.launch {
            try {
                DnsCacheManager.clearCache()
                // Trigger snackbar event
                mainViewModel.emitUiEvent(MainViewUiEvent.ShowSnackbar("DNS Cache Cleared"))
                Log.i(TAG, "✅ DNS cache cleared successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing DNS cache: ${e.message}", e)
                mainViewModel.emitUiEvent(MainViewUiEvent.ShowSnackbar("Failed to clear DNS cache"))
            }
        }
    }
}

/**
 * Extension property that provides cached DashboardViewModel instance
 */
val MainViewModel.dashboardViewModel: DashboardViewModel
    get() {
        // Use a lazy cached property in MainViewModel to ensure same instance is always returned
        // This will be initialized in MainViewModel class itself
        return getOrCreateDashboardAdapter()
    }

