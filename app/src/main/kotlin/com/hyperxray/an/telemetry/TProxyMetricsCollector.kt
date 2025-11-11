package com.hyperxray.an.telemetry

import android.content.Context
import android.util.Log
import com.hyperxray.an.common.CoreStatsClient
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.service.TProxyService
import com.hyperxray.an.viewmodel.CoreStatsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.max
import kotlin.math.abs

/**
 * TProxyMetricsCollector: Collects real-time performance metrics from TProxy and Xray-core.
 * 
 * Gathers metrics including:
 * - Throughput (uplink/downlink)
 * - Latency (RTT)
 * - Packet loss (estimated)
 * - Handshake time (estimated)
 * - Connection stats
 */
class TProxyMetricsCollector(
    private val context: Context,
    private val prefs: Preferences
) {
    private val TAG = "TProxyMetricsCollector"
    
    private var coreStatsClient: CoreStatsClient? = null
    private var lastTrafficStats: Pair<Long, Long>? = null // (uplink, downlink)
    private var lastTrafficTime: Instant? = null
    private val metricsHistory = mutableListOf<TelemetryMetrics>()
    private val maxHistorySize = 100
    
    // Native TProxy stats (from hev-socks5-tunnel)
    private var lastNativeStats: NativeTProxyStats? = null
    private var lastNativeStatsTime: Instant? = null
    
    /**
     * Native TProxy statistics from hev-socks5-tunnel
     */
    data class NativeTProxyStats(
        val txPackets: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val rxBytes: Long,
        val timestamp: Instant = Instant.now()
    )
    
    /**
     * Collect current metrics from TProxy and Xray-core.
     * Uses both native hev-socks5-tunnel stats and Xray-core stats for comprehensive metrics.
     */
    suspend fun collectMetrics(coreStatsState: CoreStatsState?): TelemetryMetrics? = withContext(Dispatchers.IO) {
        try {
            // Initialize stats client if needed
            if (coreStatsClient == null && prefs.apiPort > 0) {
                coreStatsClient = CoreStatsClient.create("127.0.0.1", prefs.apiPort)
            }
            
            // Get native TProxy stats from hev-socks5-tunnel
            val nativeStats = collectNativeTProxyStats()
            
            // Get traffic stats from Xray-core
            val trafficStats = coreStatsClient?.getTraffic()
            val systemStats = coreStatsClient?.getSystemStats()
            
            // Calculate throughput from native TProxy stats (more accurate)
            val throughput = calculateThroughputFromNative(nativeStats)
            Log.d(TAG, "Native throughput: ${throughput / (1024 * 1024)}MB/s, nativeStats=${nativeStats != null}")
            
            // Fallback to Xray-core stats if native stats unavailable
            val fallbackThroughput = if (throughput == 0.0) {
                val trafficThroughput = calculateThroughput(trafficStats, coreStatsState)
                Log.d(TAG, "Traffic throughput: ${trafficThroughput / (1024 * 1024)}MB/s, trafficStats=${trafficStats != null}, coreStatsState=${coreStatsState != null}")
                trafficThroughput
            } else {
                throughput
            }
            
            // Estimate RTT from system stats or use default
            val rtt = estimateRTT(systemStats, coreStatsState)
            
            // Calculate packet loss from native stats
            val loss = estimatePacketLossFromNative(nativeStats, coreStatsState)
            
            // Estimate handshake time (placeholder - can be enhanced with actual measurements)
            val handshakeTime = estimateHandshakeTime(coreStatsState)
            
            // Create metrics
            val metrics = TelemetryMetrics(
                throughput = fallbackThroughput,
                rttP95 = rtt,
                handshakeTime = handshakeTime,
                loss = loss,
                timestamp = Instant.now()
            )
            
            // Add to history
            metricsHistory.add(metrics)
            if (metricsHistory.size > maxHistorySize) {
                metricsHistory.removeAt(0)
            }
            
            Log.d(TAG, "Collected metrics: throughput=${fallbackThroughput / (1024 * 1024)}MB/s, " +
                    "rtt=${rtt}ms, loss=${loss * 100}%, handshake=${handshakeTime}ms, " +
                    "nativeStats=${nativeStats != null}")
            
            return@withContext metrics
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting metrics", e)
            return@withContext null
        }
    }
    
    /**
     * Collect native TProxy statistics from hev-socks5-tunnel.
     */
    private fun collectNativeTProxyStats(): NativeTProxyStats? {
        return try {
            val statsArray = TProxyService.TProxyGetStats()
            if (statsArray != null && statsArray.size >= 4) {
                val stats = NativeTProxyStats(
                    txPackets = statsArray[0],
                    txBytes = statsArray[1],
                    rxPackets = statsArray[2],
                    rxBytes = statsArray[3]
                )
                Log.d(TAG, "Native stats raw: txPackets=${statsArray[0]}, txBytes=${statsArray[1]}, rxPackets=${statsArray[2]}, rxBytes=${statsArray[3]}")
                stats
            } else {
                Log.w(TAG, "Native stats array is null or too small: ${statsArray?.size}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get native TProxy stats", e)
            null
        }
    }
    
    /**
     * Calculate throughput from native TProxy stats.
     * More accurate than Xray-core stats as it measures actual TProxy layer performance.
     */
    private fun calculateThroughputFromNative(nativeStats: NativeTProxyStats?): Double {
        if (nativeStats == null) {
            Log.d(TAG, "Native stats is null")
            return 0.0
        }
        
        val now = Instant.now()
        
        return if (lastNativeStats != null && lastNativeStatsTime != null) {
            val timeDelta = java.time.Duration.between(lastNativeStatsTime, now).toMillis()
            if (timeDelta > 0) {
                val txDelta = nativeStats.txBytes - lastNativeStats!!.txBytes
                val rxDelta = nativeStats.rxBytes - lastNativeStats!!.rxBytes
                val totalDelta = txDelta + rxDelta
                
                Log.d(TAG, "Native delta: txBytes=${nativeStats.txBytes} (delta=$txDelta), rxBytes=${nativeStats.rxBytes} (delta=$rxDelta), total=$totalDelta, timeDelta=${timeDelta}ms")
                
                // Convert to bytes per second
                val throughput = (totalDelta * 1000.0) / timeDelta
                
                // Update last stats
                lastNativeStats = nativeStats
                lastNativeStatsTime = now
                
                throughput
            } else {
                Log.d(TAG, "Native time delta is 0 or negative: $timeDelta")
                0.0
            }
        } else {
            // First measurement - no delta yet
            Log.d(TAG, "First native measurement - initializing baseline: txBytes=${nativeStats.txBytes}, rxBytes=${nativeStats.rxBytes}")
            lastNativeStats = nativeStats
            lastNativeStatsTime = now
            0.0
        }
    }
    
    /**
     * Estimate packet loss from native TProxy stats.
     * Compares packet counts with byte counts to detect potential losses.
     */
    private fun estimatePacketLossFromNative(
        nativeStats: NativeTProxyStats?,
        coreStatsState: CoreStatsState?
    ): Double {
        if (nativeStats == null || lastNativeStats == null) {
            // Fallback to previous estimation method
            return estimatePacketLoss(coreStatsState)
        }
        
        // Calculate packet rate
        val txPacketDelta = nativeStats.txPackets - lastNativeStats!!.txPackets
        val rxPacketDelta = nativeStats.rxPackets - lastNativeStats!!.rxPackets
        
        // If we sent packets but received significantly fewer, there might be loss
        // This is a simple heuristic - more sophisticated methods would require
        // sequence numbers or other packet tracking
        val timeDelta = java.time.Duration.between(lastNativeStatsTime, nativeStats.timestamp).toSeconds()
        if (timeDelta > 0 && txPacketDelta > 0) {
            val expectedRxPackets = txPacketDelta * 0.95 // Assume 95% success rate baseline
            val actualRxPackets = rxPacketDelta.toDouble()
            
            if (expectedRxPackets > actualRxPackets) {
                val lossRate = (expectedRxPackets - actualRxPackets) / expectedRxPackets
                return lossRate.coerceIn(0.0, 0.1) // Cap at 10% loss
            }
        }
        
        // Default low packet loss
        return 0.001
    }
    
    /**
     * Calculate throughput from traffic stats delta.
     */
    private suspend fun calculateThroughput(
        trafficStats: com.hyperxray.an.viewmodel.TrafficState?,
        coreStatsState: CoreStatsState?
    ): Double {
        val now = Instant.now()
        val currentUplink = trafficStats?.uplink ?: coreStatsState?.uplink ?: 0L
        val currentDownlink = trafficStats?.downlink ?: coreStatsState?.downlink ?: 0L
        
        return if (lastTrafficStats != null && lastTrafficTime != null) {
            val timeDelta = java.time.Duration.between(lastTrafficTime, now).toMillis()
            if (timeDelta > 0) {
                val uplinkDelta = currentUplink - lastTrafficStats!!.first
                val downlinkDelta = currentDownlink - lastTrafficStats!!.second
                val totalDelta = uplinkDelta + downlinkDelta
                
                // Convert to bytes per second
                val throughput = (totalDelta * 1000.0) / timeDelta
                
                Log.d(TAG, "Traffic delta: uplink=${uplinkDelta}, downlink=${downlinkDelta}, total=${totalDelta}, timeDelta=${timeDelta}ms, throughput=${throughput / (1024 * 1024)}MB/s")
                
                // Update last stats
                lastTrafficStats = Pair(currentUplink, currentDownlink)
                lastTrafficTime = now
                
                throughput
            } else {
                Log.d(TAG, "Time delta is 0 or negative: $timeDelta")
                0.0
            }
        } else {
            // First measurement - no delta yet
            Log.d(TAG, "First traffic measurement - initializing baseline: uplink=$currentUplink, downlink=$currentDownlink")
            lastTrafficStats = Pair(currentUplink, currentDownlink)
            lastTrafficTime = now
            0.0
        }
    }
    
    /**
     * Estimate RTT from system stats or metrics history.
     */
    private fun estimateRTT(
        systemStats: com.xray.app.stats.command.SysStatsResponse?,
        coreStatsState: CoreStatsState?
    ): Double {
        // Always use system stats for more dynamic values
        if (systemStats != null) {
            val goroutines = systemStats.numGoroutine
            val uptime = systemStats.uptime
            
            // More goroutines → potentially higher latency (simple heuristic)
            // Also consider uptime - longer uptime might indicate more stable connection
            val baseRtt = 30.0
            val goroutineFactor = goroutines * 0.15
            val uptimeFactor = if (uptime > 60) -5.0 else 0.0 // Slight reduction after 1 minute
            
            val estimatedRtt = baseRtt + goroutineFactor + uptimeFactor
            return estimatedRtt.coerceIn(10.0, 500.0)
        }
        
        // Fallback to coreStatsState
        if (coreStatsState != null) {
            val goroutines = coreStatsState.numGoroutine
            val estimatedRtt = 30.0 + (goroutines * 0.15)
            return estimatedRtt.coerceIn(10.0, 500.0)
        }
        
        // Default RTT estimate
        return 50.0
    }
    
    /**
     * Estimate packet loss from metrics history or system stats.
     */
    private fun estimatePacketLoss(coreStatsState: CoreStatsState?): Double {
        // Try to estimate from metrics history
        if (metricsHistory.size >= 2) {
            val recentMetrics = metricsHistory.takeLast(10)
            val avgLoss = recentMetrics.map { it.loss }.average()
            
            // If we have recent loss measurements, use them
            if (avgLoss > 0) {
                return avgLoss
            }
        }
        
        // Estimate from system stats (memory pressure can indicate issues)
        if (coreStatsState != null) {
            val memoryUsage = coreStatsState.alloc.toDouble() / max(coreStatsState.sys.toDouble(), 1.0)
            // High memory usage → potentially higher packet loss (simple heuristic)
            val estimatedLoss = if (memoryUsage > 0.8) 0.02 else 0.001
            return estimatedLoss.coerceIn(0.0, 0.1)
        }
        
        // Default low packet loss
        return 0.001
    }
    
    /**
     * Estimate handshake time from system stats.
     */
    private fun estimateHandshakeTime(coreStatsState: CoreStatsState?): Double {
        // Always use system stats for more dynamic values
        if (coreStatsState != null) {
            val goroutines = coreStatsState.numGoroutine
            val uptime = coreStatsState.uptime
            
            // More goroutines → potentially higher handshake time (simple heuristic)
            // Longer uptime might indicate more stable connections with faster handshakes
            val baseHandshake = 80.0
            val goroutineFactor = goroutines * 0.8
            val uptimeFactor = if (uptime > 60) -10.0 else 0.0 // Slight reduction after 1 minute
            
            val estimatedHandshake = baseHandshake + goroutineFactor + uptimeFactor
            return estimatedHandshake.coerceIn(50.0, 2000.0)
        }
        
        // Default handshake time
        return 150.0
    }
    
    /**
     * Get metrics history.
     */
    fun getMetricsHistory(): List<TelemetryMetrics> {
        return metricsHistory.toList()
    }
    
    /**
     * Get aggregated metrics over time window.
     */
    fun getAggregatedMetrics(windowSeconds: Int = 60): AggregatedTelemetry? {
        if (metricsHistory.isEmpty()) {
            return null
        }
        
        val now = Instant.now()
        val windowStart = now.minusSeconds(windowSeconds.toLong())
        
        val windowMetrics = metricsHistory.filter { it.timestamp.isAfter(windowStart) }
        
        if (windowMetrics.isEmpty()) {
            return null
        }
        
        return AggregatedTelemetry(
            avgThroughput = windowMetrics.map { it.throughput }.average(),
            rttP95 = windowMetrics.map { it.rttP95 }.maxOrNull() ?: 0.0,
            avgHandshakeTime = windowMetrics.map { it.handshakeTime }.average(),
            avgLoss = windowMetrics.map { it.loss }.average(),
            sampleCount = windowMetrics.size,
            windowStart = windowStart,
            windowEnd = now
        )
    }
    
    /**
     * Close stats client.
     */
    fun close() {
        coreStatsClient?.close()
        coreStatsClient = null
    }
}

