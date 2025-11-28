package com.hyperxray.an.feature.dashboard

import com.hyperxray.an.xray.runtime.XrayRuntimeStatus
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
 * DNS Cache entry UI model
 */
data class DnsCacheEntryUiModel(
    val domain: String,
    val ips: List<String>,
    val expiryTime: Long // Unix timestamp in seconds when entry expires
)

/**
 * DNS Cache statistics
 */
data class DnsCacheStats(
    val entryCount: Int,
    val memoryUsageMB: Long,
    val memoryLimitMB: Long,
    val memoryUsagePercent: Int,
    val hits: Long,
    val misses: Long,
    val hitRate: Int,
    val avgDomainHitRate: Int,
    val avgHitLatencyMs: Double,
    val avgMissLatencyMs: Double,
    val avgTtlSeconds: Long, // Average TTL of cached entries
    val activeEntries: List<DnsCacheEntryUiModel> // Top 100 most recently updated entries
)

/**
 * ViewModel interface for Dashboard screen.
 * This allows the feature module to work without depending on MainViewModel from app module.
 */
interface DashboardViewModel {
    val coreStatsState: StateFlow<CoreStatsState>
    val telemetryState: StateFlow<AggregatedTelemetry?>
    val dnsCacheStats: StateFlow<DnsCacheStats?>
    val isServiceEnabled: StateFlow<Boolean>
    val controlMenuClickable: StateFlow<Boolean>
    val connectionState: StateFlow<ConnectionState>
    val instancesStatus: StateFlow<Map<Int, XrayRuntimeStatus>>
    
    // HyperVpnService state (optional - may be null if not available)
    val hyperVpnState: StateFlow<com.hyperxray.an.core.network.vpn.HyperVpnStateManager.VpnState>?
    val hyperVpnStats: StateFlow<com.hyperxray.an.core.network.vpn.HyperVpnStateManager.TunnelStats>?
    val hyperVpnError: StateFlow<String?>?
    
    fun updateCoreStats()
    fun updateTelemetryStats()
    fun updateDnsCacheStats()
    fun clearDnsCache()
    
    // HyperVpnService control functions (optional)
    fun startHyperVpn() {}
    fun stopHyperVpn() {}
    fun clearHyperVpnError() {}
}

