package com.hyperxray.an.feature.dashboard

import kotlinx.coroutines.flow.StateFlow

/**
 * Core statistics state from Xray-core including traffic, memory, and runtime metrics.
 */
data class CoreStatsState(
    val uplink: Long = 0,
    val downlink: Long = 0,
    val uplinkThroughput: Double = 0.0, // bytes per second
    val downlinkThroughput: Double = 0.0, // bytes per second
    val numGoroutine: Int = 0,
    val numGC: Int = 0,
    val alloc: Long = 0,
    val totalAlloc: Long = 0,
    val sys: Long = 0,
    val mallocs: Long = 0,
    val frees: Long = 0,
    val liveObjects: Long = 0,
    val pauseTotalNs: Long = 0,
    val uptime: Int = 0
)

/**
 * Aggregated telemetry data for a time window
 */
data class AggregatedTelemetry(
    /**
     * Average throughput in bytes per second
     */
    val avgThroughput: Double,
    
    /**
     * 95th percentile RTT in milliseconds
     */
    val rttP95: Double,
    
    /**
     * Average handshake time in milliseconds
     */
    val avgHandshakeTime: Double,
    
    /**
     * Average packet loss rate (0.0 to 1.0)
     */
    val avgLoss: Double,
    
    /**
     * Number of samples aggregated
     */
    val sampleCount: Int
)

/**
 * ViewModel interface for Dashboard screen.
 * This allows the feature module to work without depending on MainViewModel from app module.
 */
interface DashboardViewModel {
    val coreStatsState: StateFlow<CoreStatsState>
    val telemetryState: StateFlow<AggregatedTelemetry?>
    val isServiceEnabled: StateFlow<Boolean>
    val controlMenuClickable: StateFlow<Boolean>
    val connectionState: StateFlow<ConnectionState>
    
    fun updateCoreStats()
    fun updateTelemetryStats()
}

