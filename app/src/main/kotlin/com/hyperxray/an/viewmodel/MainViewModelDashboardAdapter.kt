package com.hyperxray.an.viewmodel

import com.hyperxray.an.feature.dashboard.DashboardViewModel
import com.hyperxray.an.feature.dashboard.CoreStatsState as FeatureCoreStatsState
import com.hyperxray.an.feature.dashboard.AggregatedTelemetry as FeatureAggregatedTelemetry
import com.hyperxray.an.feature.dashboard.DnsCacheStats as FeatureDnsCacheStats
import com.hyperxray.an.core.network.dns.DnsCacheManager
import com.hyperxray.an.telemetry.AggregatedTelemetry
import com.hyperxray.an.xray.runtime.XrayRuntimeStatus
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

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
 * Extension function to parse DnsCacheManager.getStats() string to feature module DnsCacheStats
 * Commit 312450a: DnsCacheManager only has getStats() method, not getStatsData()
 */
private fun parseDnsCacheStats(statsString: String): FeatureDnsCacheStats {
    // Parse stats string: "DNS Cache: X entries, hits=Y, misses=Z, hitRate=W%"
    val entriesMatch = Regex("(\\d+) entries").find(statsString)
    val hitsMatch = Regex("hits=(\\d+)").find(statsString)
    val missesMatch = Regex("misses=(\\d+)").find(statsString)
    val hitRateMatch = Regex("hitRate=(\\d+)%").find(statsString)
    
    val entryCount = entriesMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val hits = hitsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val misses = missesMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val hitRate = hitRateMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    
    return FeatureDnsCacheStats(
        entryCount = entryCount,
        memoryUsageMB = 0L, // Not available in commit 312450a
        memoryLimitMB = 0L, // Not available in commit 312450a
        memoryUsagePercent = 0, // Not available in commit 312450a
        hits = hits,
        misses = misses,
        hitRate = hitRate,
        avgDomainHitRate = 0, // Not available in commit 312450a
        avgHitLatencyMs = 0.0, // Not available in commit 312450a
        avgMissLatencyMs = 0.0 // Not available in commit 312450a
    )
}

/**
 * MainViewModel implementation of DashboardViewModel
 * Uses cached StateFlow transformations to ensure stable state updates
 */
class MainViewModelDashboardAdapter(private val mainViewModel: MainViewModel) : DashboardViewModel {
    // Cached transformed StateFlows - created once and reused
    // Direct map transformation without stateIn to ensure all updates are propagated
    override val coreStatsState: StateFlow<FeatureCoreStatsState> =
        mainViewModel.coreStatsState
            .map { it.toFeatureState() }
            .stateIn(
                scope = mainViewModel.viewModelScope,
                started = SharingStarted.WhileSubscribed(0), // Start immediately when subscribed, stop when no subscribers
                initialValue = mainViewModel.coreStatsState.value.toFeatureState()
            )
    
    override val telemetryState: StateFlow<FeatureAggregatedTelemetry?> =
        mainViewModel.telemetryState
            .map { it?.toFeatureState() }
            .stateIn(
                scope = mainViewModel.viewModelScope,
                started = SharingStarted.WhileSubscribed(0), // Start immediately when subscribed, stop when no subscribers
                initialValue = mainViewModel.telemetryState.value?.toFeatureState()
            )
    
    private val dnsCacheStatsFlow = MutableStateFlow<FeatureDnsCacheStats?>(null)
    override val dnsCacheStats: StateFlow<FeatureDnsCacheStats?> =
        dnsCacheStatsFlow.asStateFlow()
    
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
                    DnsCacheManager.initialize(context)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to initialize DnsCacheManager: ${e.message}", e)
                }
                
                // Commit 312450a: DnsCacheManager only has getStats() method, not getStatsData()
                val statsString = DnsCacheManager.getStats()
                val stats = parseDnsCacheStats(statsString)
                dnsCacheStatsFlow.value = stats
                Log.d(TAG, "DNS cache stats updated: $statsString")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating DNS cache stats: ${e.message}", e)
                // Keep last value instead of setting to null, so UI doesn't reset to zero
                // dnsCacheStatsFlow.value remains unchanged on error
            }
        }
    }
    
    companion object {
        private const val TAG = "MainViewModelDashboardAdapter"
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

