package com.hyperxray.an.viewmodel

import com.hyperxray.an.feature.dashboard.DashboardViewModel
import com.hyperxray.an.feature.dashboard.CoreStatsState as FeatureCoreStatsState
import com.hyperxray.an.feature.dashboard.AggregatedTelemetry as FeatureAggregatedTelemetry
import com.hyperxray.an.feature.dashboard.DnsCacheStats as FeatureDnsCacheStats
import com.hyperxray.an.feature.dashboard.AndroidMemoryStats as FeatureAndroidMemoryStats
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
 * Extension function to convert app module AndroidMemoryStats to feature module AndroidMemoryStats
 */
fun com.hyperxray.an.core.monitor.AndroidMemoryStats.toFeatureState(): FeatureAndroidMemoryStats {
    return FeatureAndroidMemoryStats(
        totalPss = totalPss,
        nativeHeap = nativeHeap,
        dalvikHeap = dalvikHeap,
        otherPss = otherPss,
        usedMemory = usedMemory,
        maxMemory = maxMemory,
        freeMemory = freeMemory,
        systemTotalMem = systemTotalMem,
        systemAvailMem = systemAvailMem,
        systemUsedMem = systemUsedMem,
        systemThreshold = systemThreshold,
        systemLowMemory = systemLowMemory,
        goAlloc = goAlloc,
        goTotalAlloc = goTotalAlloc,
        goSys = goSys,
        goMallocs = goMallocs,
        goFrees = goFrees,
        goLiveObjects = goLiveObjects,
        goPauseTotalNs = goPauseTotalNs,
        processMemoryUsagePercent = processMemoryUsagePercent,
        runtimeMemoryUsagePercent = runtimeMemoryUsagePercent,
        systemMemoryUsagePercent = systemMemoryUsagePercent,
        updateTimestamp = updateTimestamp
    )
}


/**
 * MainViewModel implementation of DashboardViewModel
 * DNS cache removed - using Google DNS (8.8.8.8) directly
 */
class MainViewModelDashboardAdapter(private val mainViewModel: MainViewModel) : DashboardViewModel {
    
    override val coreStatsState: StateFlow<FeatureCoreStatsState> =
        mainViewModel.coreStatsState
            .map { it.toFeatureState() }
            .stateIn(
                scope = mainViewModel.viewModelScope,
                started = SharingStarted.Lazily,
                initialValue = mainViewModel.coreStatsState.value.toFeatureState()
            )
    
    override val telemetryState: StateFlow<FeatureAggregatedTelemetry?> =
        mainViewModel.telemetryState
            .map { it?.toFeatureState() }
            .stateIn(
                scope = mainViewModel.viewModelScope,
                started = SharingStarted.Lazily,
                initialValue = mainViewModel.telemetryState.value?.toFeatureState()
            )
    
    // DNS cache removed - return empty stats
    private val _dnsCacheStats = MutableStateFlow<FeatureDnsCacheStats?>(
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
    )
    override val dnsCacheStats: StateFlow<FeatureDnsCacheStats?> = _dnsCacheStats.asStateFlow()
    
    override val isServiceEnabled: StateFlow<Boolean> = mainViewModel.isServiceEnabled
    
    override val controlMenuClickable: StateFlow<Boolean> = mainViewModel.controlMenuClickable
    
    override val connectionState: StateFlow<com.hyperxray.an.feature.dashboard.ConnectionState> =
        mainViewModel.connectionState
    
    override val instancesStatus: StateFlow<Map<Int, XrayRuntimeStatus>> =
        MutableStateFlow<Map<Int, XrayRuntimeStatus>>(emptyMap()).asStateFlow()
    
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
        // No-op: DNS cache removed, using Google DNS directly
        Log.d(TAG, "updateDnsCacheStats: DNS cache removed, using Google DNS directly")
    }
    
    override fun clearDnsCache() {
        // No-op: DNS cache removed
        mainViewModel.viewModelScope.launch {
            mainViewModel.emitUiEvent(MainViewUiEvent.ShowSnackbar("Using Google DNS directly (no cache)"))
        }
    }
    
    override val hyperVpnState: StateFlow<com.hyperxray.an.core.network.vpn.HyperVpnStateManager.VpnState>? =
        mainViewModel.hyperVpnState
    
    override val hyperVpnStats: StateFlow<com.hyperxray.an.core.network.vpn.HyperVpnStateManager.TunnelStats>? =
        mainViewModel.hyperVpnStats
    
    override val hyperVpnError: StateFlow<String?>? = mainViewModel.hyperVpnError
    
    override fun startHyperVpn() { mainViewModel.startHyperVpn() }
    
    override fun stopHyperVpn() { mainViewModel.stopHyperVpn() }
    
    override fun clearHyperVpnError() { mainViewModel.clearHyperVpnError() }
    
    override fun createWarpAccountAndConnect() { mainViewModel.createWarpAccountAndConnect() }
    
    override val warpAccountInfo: StateFlow<com.hyperxray.an.feature.dashboard.WarpAccountInfo>? =
        mainViewModel.warpAccountInfo
    
    override val androidMemoryStats: StateFlow<FeatureAndroidMemoryStats>? =
        mainViewModel.androidMemoryStats
            .map { it.toFeatureState() }
            .stateIn(
                scope = mainViewModel.viewModelScope,
                started = SharingStarted.Lazily,
                initialValue = mainViewModel.androidMemoryStats.value.toFeatureState()
            )
}

/**
 * Extension property that provides cached DashboardViewModel instance
 */
val MainViewModel.dashboardViewModel: DashboardViewModel
    get() = getOrCreateDashboardAdapter()
