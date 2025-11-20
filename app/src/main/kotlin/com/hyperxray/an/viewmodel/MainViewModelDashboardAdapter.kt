package com.hyperxray.an.viewmodel

import com.hyperxray.an.feature.dashboard.DashboardViewModel
import com.hyperxray.an.feature.dashboard.CoreStatsState as FeatureCoreStatsState
import com.hyperxray.an.feature.dashboard.AggregatedTelemetry as FeatureAggregatedTelemetry
import com.hyperxray.an.telemetry.AggregatedTelemetry
import com.hyperxray.an.xray.runtime.XrayRuntimeStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
 * MainViewModel implementation of DashboardViewModel
 * Uses viewModelScope for StateFlow conversion
 */
val MainViewModel.dashboardViewModel: DashboardViewModel
    get() {
        val mainViewModel = this
        return object : DashboardViewModel {
            override val coreStatsState: StateFlow<FeatureCoreStatsState> =
                mainViewModel.coreStatsState
                    .map { it.toFeatureState() }
                    .stateIn(
                        scope = mainViewModel.viewModelScope,
                        started = SharingStarted.WhileSubscribed(5000),
                        initialValue = mainViewModel.coreStatsState.value.toFeatureState()
                    )
            
            override val telemetryState: StateFlow<FeatureAggregatedTelemetry?> =
                mainViewModel.telemetryState
                    .map { it?.toFeatureState() }
                    .stateIn(
                        scope = mainViewModel.viewModelScope,
                        started = SharingStarted.WhileSubscribed(5000),
                        initialValue = mainViewModel.telemetryState.value?.toFeatureState()
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
        }
    }

