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
 * WARP Account information for dashboard display
 */
data class WarpAccountInfo(
    val accountExists: Boolean = false,
    val publicKey: String? = null,
    val endpoint: String? = null,
    val accountType: String? = null,
    val license: String? = null,
    val warpEnabled: Boolean = false
)

/**
 * Android system memory statistics for dashboard display.
 * Includes Android system memory info and Go runtime memory stats from native Xray-core.
 */
data class AndroidMemoryStats(
    // Process memory (PSS - Proportional Set Size)
    val totalPss: Long = 0L,           // Total PSS in bytes
    val nativeHeap: Long = 0L,         // Native heap PSS in bytes
    val dalvikHeap: Long = 0L,         // Dalvik heap PSS in bytes
    val otherPss: Long = 0L,           // Other PSS in bytes
    
    // Runtime memory (Java heap)
    val usedMemory: Long = 0L,         // Used memory in bytes
    val maxMemory: Long = 0L,          // Max memory in bytes
    val freeMemory: Long = 0L,         // Free memory in bytes
    
    // System memory
    val systemTotalMem: Long = 0L,     // Total system memory in bytes
    val systemAvailMem: Long = 0L,    // Available system memory in bytes
    val systemUsedMem: Long = 0L,     // Used system memory in bytes
    val systemThreshold: Long = 0L,    // Low memory threshold in bytes
    val systemLowMemory: Boolean = false, // Is system in low memory state
    
    // Go runtime memory (from native Xray-core via gRPC)
    val goAlloc: Long = 0L,            // Go runtime allocated memory in bytes
    val goTotalAlloc: Long = 0L,       // Go runtime total allocated memory in bytes
    val goSys: Long = 0L,              // Go runtime system memory in bytes
    val goMallocs: Long = 0L,          // Go runtime total mallocs
    val goFrees: Long = 0L,            // Go runtime total frees
    val goLiveObjects: Long = 0L,      // Go runtime live objects (mallocs - frees)
    val goPauseTotalNs: Long = 0L,     // Go runtime total GC pause time in nanoseconds
    
    // Percentages
    val processMemoryUsagePercent: Int = 0,  // Process memory usage % of system total
    val runtimeMemoryUsagePercent: Int = 0,  // Runtime memory usage % of max
    val systemMemoryUsagePercent: Int = 0,   // System memory usage %
    
    // Metadata
    val updateTimestamp: Long = 0L     // Last update timestamp
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
    
    // WARP Account state (optional - may be null if not available)
    val warpAccountInfo: StateFlow<WarpAccountInfo>?
    
    // Android Memory Stats (optional - may be null if not available)
    val androidMemoryStats: StateFlow<AndroidMemoryStats>?
    
    fun updateCoreStats()
    fun updateTelemetryStats()
    fun updateDnsCacheStats()
    fun clearDnsCache()
    
    // HyperVpnService control functions (optional)
    fun startHyperVpn() {}
    fun stopHyperVpn() {}
    fun clearHyperVpnError() {}
}

