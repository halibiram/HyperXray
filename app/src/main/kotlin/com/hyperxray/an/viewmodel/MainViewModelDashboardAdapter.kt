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
 * Extension function to convert DnsCacheManager structured stats to feature module DnsCacheStats
 * Uses the new getStatsStructured() method for robust parsing
 */
private fun com.hyperxray.an.core.network.dns.DnsCacheManager.DnsCacheStatsData.toFeatureState(): FeatureDnsCacheStats {
    return FeatureDnsCacheStats(
        entryCount = entryCount,
        memoryUsageMB = 0L, // Not available in current implementation
        memoryLimitMB = 0L, // Not available in current implementation
        memoryUsagePercent = 0, // Not available in current implementation
        hits = hits,
        misses = misses,
        hitRate = hitRate,
        avgDomainHitRate = 0, // Not available in current implementation
        avgHitLatencyMs = 0.0, // Not available in current implementation
        avgMissLatencyMs = 0.0 // Not available in current implementation
    )
}

/**
 * Fallback function to parse DnsCacheManager.getStats() string (for backward compatibility)
 * This is more robust than the previous implementation with better error handling
 */
private fun parseDnsCacheStatsFallback(statsString: String): FeatureDnsCacheStats {
    return try {
        // Parse stats string: "DNS Cache: X entries, hits=Y, misses=Z, hitRate=W%"
        // Use more flexible regex patterns to handle format variations
        val entriesMatch = Regex("(\\d+)\\s+entries?", RegexOption.IGNORE_CASE).find(statsString)
        val hitsMatch = Regex("hits\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE).find(statsString)
        val missesMatch = Regex("misses\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE).find(statsString)
        val hitRateMatch = Regex("hitRate\\s*=\\s*(\\d+)%?", RegexOption.IGNORE_CASE).find(statsString)
        
        val entryCount = entriesMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val hits = hitsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val misses = missesMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val hitRate = hitRateMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        
        FeatureDnsCacheStats(
            entryCount = entryCount,
            memoryUsageMB = 0L,
            memoryLimitMB = 0L,
            memoryUsagePercent = 0,
            hits = hits,
            misses = misses,
            hitRate = hitRate,
            avgDomainHitRate = 0,
            avgHitLatencyMs = 0.0,
            avgMissLatencyMs = 0.0
        )
    } catch (e: Exception) {
        Log.e(TAG, "Error parsing DNS cache stats string: $statsString", e)
        // Return safe default values instead of zeros to indicate parsing failure
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
            avgMissLatencyMs = 0.0
        )
    }
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
                
                // Prefer structured method for robust parsing
                val stats = try {
                    val structuredStats = DnsCacheManager.getStatsStructured()
                    structuredStats.toFeatureState()
                } catch (e: Exception) {
                    // Fallback to string parsing if structured method fails
                    Log.w(TAG, "Failed to get structured DNS stats, falling back to string parsing: ${e.message}", e)
                    val statsString = DnsCacheManager.getStats()
                    parseDnsCacheStatsFallback(statsString)
                }
                
                dnsCacheStatsFlow.value = stats
                Log.d(TAG, "DNS cache stats updated: entries=${stats.entryCount}, hits=${stats.hits}, misses=${stats.misses}, hitRate=${stats.hitRate}%")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating DNS cache stats: ${e.message}", e)
                // Keep last value instead of setting to null, so UI doesn't reset to zero
                // dnsCacheStatsFlow.value remains unchanged on error
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

