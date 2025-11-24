package com.hyperxray.an.core.monitor

import android.util.Log
import com.hyperxray.an.viewmodel.CoreStatsState
import com.hyperxray.an.xray.runtime.stats.CoreStatsClient
import com.hyperxray.an.xray.runtime.stats.model.TrafficState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages Xray-core statistics collection via gRPC.
 * Handles CoreStatsClient lifecycle, retry logic, exponential backoff, and cooldown.
 * Exposes stats updates via StateFlow for UI consumption.
 */
class XrayStatsManager(
    private val scope: CoroutineScope,
    private val apiPortProvider: () -> Int
) {
    private val TAG = "XrayStatsManager"
    
    private var coreStatsClient: CoreStatsClient? = null
    private val coreStatsClientMutex = Mutex() // For suspend functions
    private val coreStatsClientLock = Any() // For synchronous operations
    
    // Client lifecycle state management to prevent rebuild loops
    @Volatile
    private var clientState: ClientState = ClientState.STOPPED
    
    @Volatile
    private var lastClientCloseTime: Long = 0L
    
    @Volatile
    private var consecutiveFailures: Int = 0
    
    private val MIN_RECREATE_INTERVAL_MS = 5000L // 5 seconds minimum between recreations
    private val MAX_BACKOFF_MS = 30000L // 30 seconds maximum backoff
    
    private enum class ClientState {
        STOPPED,        // Client not created or properly closed
        CREATING,       // Client creation in progress
        READY,          // Client ready and working
        FAILED,         // Client failed, needs cooldown before retry
        SHUTTING_DOWN   // Client is being shut down, ignore recreate requests
    }
    
    // Throughput calculation state
    private var lastUplink: Long = 0L
    private var lastDownlink: Long = 0L
    private var lastStatsTime: Long = 0L
    
    // Monitoring state
    @Volatile
    private var isMonitoring: Boolean = false
    
    @Volatile
    private var isServiceEnabled: Boolean = false
    
    private var monitoringJob: Job? = null
    
    private val _statsState = MutableStateFlow(CoreStatsState())
    val stats: StateFlow<CoreStatsState> = _statsState.asStateFlow()
    
    /**
     * Starts monitoring Xray stats.
     * Should be called when connection is established.
     */
    fun startMonitoring() {
        if (isMonitoring) {
            Log.d(TAG, "Monitoring already started")
            return
        }
        
        isMonitoring = true
        isServiceEnabled = true
        
        Log.d(TAG, "Starting Xray stats monitoring")
        
        // Start monitoring loop
        monitoringJob = scope.launch {
            while (isActive && isMonitoring) {
                updateCoreStats()
                delay(1000L) // Default polling interval, can be adjusted
            }
        }
    }
    
    /**
     * Stops monitoring Xray stats.
     * Should be called when connection is lost or service is disabled.
     */
    fun stopMonitoring() {
        if (!isMonitoring) {
            return
        }
        
        Log.d(TAG, "Stopping Xray stats monitoring")
        isMonitoring = false
        isServiceEnabled = false
        
        monitoringJob?.cancel()
        monitoringJob = null
        
        closeCoreStatsClient()
    }
    
    /**
     * Updates service enabled state.
     * When disabled, stops monitoring and closes client.
     */
    fun setServiceEnabled(enabled: Boolean) {
        isServiceEnabled = enabled
        if (!enabled) {
            closeCoreStatsClient()
            consecutiveFailures = 0
        }
    }
    
    /**
     * Updates core statistics from Xray-core via gRPC.
     * Handles client lifecycle, retry logic, and error recovery.
     */
    suspend fun updateCoreStats() {
        // Check if service is enabled before proceeding
        if (!isServiceEnabled) {
            // Service is not enabled, ensure client is closed if it exists
            closeCoreStatsClient()
            consecutiveFailures = 0
            return
        }
        
        // Synchronize access to coreStatsClient to prevent race conditions
        val client = coreStatsClientMutex.withLock {
            // Check if we can recreate client (cooldown and state checks)
            if (coreStatsClient == null) {
                val now = System.currentTimeMillis()
                val timeSinceClose = now - lastClientCloseTime
                
                // Prevent rapid recreation: enforce cooldown period
                if (timeSinceClose < MIN_RECREATE_INTERVAL_MS) {
                    val remainingCooldown = MIN_RECREATE_INTERVAL_MS - timeSinceClose
                    Log.d(TAG, "Client recreation cooldown active, ${remainingCooldown}ms remaining")
                    return@withLock null
                }
                
                // Check state: only recreate if STOPPED or FAILED (not SHUTTING_DOWN or CREATING)
                if (clientState != ClientState.STOPPED && clientState != ClientState.FAILED) {
                    Log.d(TAG, "Client in state ${clientState}, skipping recreation")
                    return@withLock null
                }
                
                // Calculate exponential backoff based on consecutive failures
                if (consecutiveFailures > 0) {
                    val backoffMs = minOf(
                        MIN_RECREATE_INTERVAL_MS * (1L shl minOf(consecutiveFailures - 1, 4)),
                        MAX_BACKOFF_MS
                    )
                    if (timeSinceClose < backoffMs) {
                        val remainingBackoff = backoffMs - timeSinceClose
                        Log.d(TAG, "Exponential backoff active, ${remainingBackoff}ms remaining (failures: $consecutiveFailures)")
                        return@withLock null
                    }
                }
                
                // Set state to CREATING to prevent concurrent creation attempts
                clientState = ClientState.CREATING
                
                try {
                    // create() now returns nullable and handles retries internally
                    coreStatsClient = CoreStatsClient.create("127.0.0.1", apiPortProvider())
                    if (coreStatsClient != null) {
                        Log.d(TAG, "Created new CoreStatsClient")
                        clientState = ClientState.READY
                        consecutiveFailures = 0 // Reset on success
                    } else {
                        Log.w(TAG, "Failed to create CoreStatsClient after retries")
                        clientState = ClientState.FAILED
                        consecutiveFailures++
                        lastClientCloseTime = System.currentTimeMillis()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception creating CoreStatsClient: ${e.message}", e)
                    clientState = ClientState.FAILED
                    consecutiveFailures++
                    lastClientCloseTime = System.currentTimeMillis()
                    coreStatsClient = null
                }
            }
            // If client exists but might be in bad state, we'll detect that during RPC calls
            // and recreate on next attempt (handled by CoreStatsClient internally)
            coreStatsClient
        }
        
        // Validate client was created
        if (client == null) {
            Log.w(TAG, "CoreStatsClient is null, cannot update stats - will retry on next call")
            // Don't return immediately - update state with safe fallback values
            // This allows UI to show "connecting" or "unavailable" state
            val currentState = _statsState.value
            _statsState.value = currentState.copy(
                // Preserve existing values, don't reset to zero
                // UI can show these as "last known" or "unavailable"
            )
            return
        }
        
        // Use withTimeoutOrNull to prevent hanging if Xray crashes or is unresponsive
        // Timeout of 5 seconds should be sufficient for gRPC calls
        val statsResult = withTimeoutOrNull(5000L) {
            try {
                // Check if service is still enabled before making gRPC call
                if (!isServiceEnabled) {
                    return@withTimeoutOrNull null
                }
                // Make gRPC call - may throw exception or timeout
                client.getSystemStats()
            } catch (e: Exception) {
                // Exception occurred during gRPC call - log it
                Log.e(TAG, "Error getting system stats: ${e.message}", e)
                null
            }
        }
        
        // Handle timeout, exception, or service disabled
        if (statsResult == null) {
            // Timeout, exception, or service disabled occurred
            Log.w(TAG, "Stats query failed (timeout/exception/disabled)")
            // Only close client if service is disabled or we've had multiple failures
            if (!isServiceEnabled) {
                closeCoreStatsClient()
                consecutiveFailures = 0
            } else {
                // Service is enabled but query failed - increment failure count
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    Log.w(TAG, "Multiple consecutive failures ($consecutiveFailures), closing client")
                    synchronized(coreStatsClientLock) {
                        clientState = ClientState.FAILED
                    }
                    closeCoreStatsClient()
                } else {
                    Log.d(TAG, "Stats query failed but keeping client (failures: $consecutiveFailures)")
                }
            }
            return
        }
        
        // Success: reset failure count
        consecutiveFailures = 0
        synchronized(coreStatsClientLock) {
            if (clientState != ClientState.READY) {
                clientState = ClientState.READY
            }
        }
        
        // Check if service is still enabled after first gRPC call
        if (!isServiceEnabled) {
            Log.d(TAG, "Service disabled during stats update, skipping")
            closeCoreStatsClient()
            return
        }
        
        val trafficResult = withTimeoutOrNull(5000L) {
            try {
                // Check if service is still enabled before making gRPC call
                if (!isServiceEnabled) {
                    return@withTimeoutOrNull null
                }
                // Make gRPC call - may throw exception or timeout
                client.getTraffic()
            } catch (e: Exception) {
                // Exception occurred during gRPC call - log it
                Log.e(TAG, "Error getting traffic stats: ${e.message}", e)
                null
            }
        }
        
        // Handle timeout, exception, or service disabled
        if (trafficResult == null) {
            // Timeout, exception, or service disabled occurred
            Log.w(TAG, "Traffic query failed (timeout/exception/disabled)")
            // Don't close client immediately - it might recover on next call
            // Only close if service is disabled
            if (!isServiceEnabled) {
                closeCoreStatsClient()
                consecutiveFailures = 0
            } else {
                // Increment failure count but don't close immediately
                // Traffic failures are less critical than system stats failures
                consecutiveFailures++
                if (consecutiveFailures >= 5) {
                    Log.w(TAG, "Multiple consecutive failures ($consecutiveFailures), closing client")
                    synchronized(coreStatsClientLock) {
                        clientState = ClientState.FAILED
                    }
                    closeCoreStatsClient()
                }
            }
            // Preserve existing traffic values instead of returning
            return
        }
        
        // Success: reset failure count
        consecutiveFailures = 0
        synchronized(coreStatsClientLock) {
            if (clientState != ClientState.READY) {
                clientState = ClientState.READY
            }
        }
        
        // Check if service is still enabled after both gRPC calls
        if (!isServiceEnabled) {
            Log.d(TAG, "Service disabled during stats update, skipping")
            closeCoreStatsClient()
            return
        }
        
        val stats = statsResult
        val traffic = trafficResult
        
        // Preserve existing traffic values if new traffic data is null
        val currentState = _statsState.value
        val newUplink = traffic?.uplink ?: currentState.uplink
        val newDownlink = traffic?.downlink ?: currentState.downlink
        
        // Calculate throughput (bytes per second)
        val now = System.currentTimeMillis()
        var uplinkThroughput = 0.0
        var downlinkThroughput = 0.0
        
        if (lastStatsTime > 0 && now > lastStatsTime) {
            val timeDelta = (now - lastStatsTime) / 1000.0 // Convert to seconds
            
            if (timeDelta > 0) {
                val uplinkDelta = newUplink - lastUplink
                val downlinkDelta = newDownlink - lastDownlink
                
                uplinkThroughput = uplinkDelta / timeDelta
                downlinkThroughput = downlinkDelta / timeDelta
                
                Log.d(TAG, "Throughput calculated: uplink=${formatThroughput(uplinkThroughput)}, downlink=${formatThroughput(downlinkThroughput)}, timeDelta=${timeDelta}s")
            }
        } else if (lastStatsTime == 0L) {
            // First measurement - initialize baseline
            Log.d(TAG, "First throughput measurement - initializing baseline")
        }
        
        // Update last values for next calculation
        lastUplink = newUplink
        lastDownlink = newDownlink
        lastStatsTime = now
        
        _statsState.value = CoreStatsState(
            uplink = newUplink,
            downlink = newDownlink,
            uplinkThroughput = uplinkThroughput,
            downlinkThroughput = downlinkThroughput,
            numGoroutine = stats?.numGoroutine ?: currentState.numGoroutine,
            numGC = stats?.numGC ?: currentState.numGC,
            alloc = stats?.alloc ?: currentState.alloc,
            totalAlloc = stats?.totalAlloc ?: currentState.totalAlloc,
            sys = stats?.sys ?: currentState.sys,
            mallocs = stats?.mallocs ?: currentState.mallocs,
            frees = stats?.frees ?: currentState.frees,
            liveObjects = stats?.liveObjects ?: currentState.liveObjects,
            pauseTotalNs = stats?.pauseTotalNs ?: currentState.pauseTotalNs,
            uptime = stats?.uptime ?: currentState.uptime
        )
        Log.d(TAG, "Core stats updated - Uplink: ${formatBytes(newUplink)}, Downlink: ${formatBytes(newDownlink)}, Uplink Throughput: ${formatThroughput(uplinkThroughput)}, Downlink Throughput: ${formatThroughput(downlinkThroughput)}")
    }
    
    /**
     * Verifies Xray connection by testing gRPC connectivity.
     * Creates a temporary client, tests system and traffic stats, then closes it.
     * 
     * @param apiPort The API port to verify
     * @return true if connection is verified, false otherwise
     */
    suspend fun verifyXrayConnection(apiPort: Int): Boolean {
        Log.d(TAG, "verifyXrayConnection: Starting verification for port $apiPort")
        return try {
            Log.d(TAG, "verifyXrayConnection: Creating CoreStatsClient...")
            val client = CoreStatsClient.create("127.0.0.1", apiPort, maxRetries = 2, initialRetryDelayMs = 500L)
            if (client == null) {
                Log.e(TAG, "verifyXrayConnection FAILED: Failed to create CoreStatsClient for port $apiPort")
                return false
            }
            Log.d(TAG, "verifyXrayConnection: CoreStatsClient created successfully")
            
            // Test 1: System stats connection
            Log.d(TAG, "verifyXrayConnection: Testing system stats connection (timeout: 3000ms)...")
            val systemStats = withTimeoutOrNull(3000L) {
                client.getSystemStats()
            }
            if (systemStats == null) {
                Log.e(TAG, "verifyXrayConnection FAILED: getSystemStats returned null (port: $apiPort) - connection may be down")
                client.close()
                return false
            }
            Log.i(TAG, "verifyXrayConnection: System stats check PASSED - uptime=${systemStats.uptime}s, numGoroutine=${systemStats.numGoroutine}, numGC=${systemStats.numGC}")
            
            // Test 2: Traffic stats - verify we can actually get traffic data with retry mechanism
            Log.d(TAG, "verifyXrayConnection: Testing traffic stats connection with retries (500ms intervals)...")
            var hasTraffic = false
            var lastTrafficStats: TrafficState? = null
            val maxRetries = 6 // 6 retries with 500ms intervals = up to 3 seconds wait
            val retryDelay = 500L // 500ms between retries
            
            for (retry in 1..maxRetries) {
                Log.d(TAG, "verifyXrayConnection: Traffic stats retry $retry/$maxRetries...")
                val trafficStats = withTimeoutOrNull(3000L) {
                    client.getTraffic()
                }
                
                if (trafficStats == null) {
                    Log.w(TAG, "verifyXrayConnection: getTraffic returned null on retry $retry/$maxRetries")
                    if (retry < maxRetries) {
                        delay(retryDelay)
                        continue
                    } else {
                        Log.e(TAG, "verifyXrayConnection FAILED: getTraffic returned null on all retries (port: $apiPort) - connection may not be fully functional")
                        client.close()
                        return false
                    }
                }
                
                lastTrafficStats = trafficStats
                val totalTraffic = trafficStats.uplink + trafficStats.downlink
                Log.d(TAG, "verifyXrayConnection: Retry $retry/$maxRetries - uplink=${trafficStats.uplink} bytes, downlink=${trafficStats.downlink} bytes, total=$totalTraffic bytes")
                
                if (totalTraffic > 0) {
                    hasTraffic = true
                    Log.i(TAG, "verifyXrayConnection: Traffic detected on retry $retry/$maxRetries - connection is functional")
                    break
                }
                
                // Wait before next retry (except on last retry)
                if (retry < maxRetries) {
                    delay(retryDelay)
                }
            }
            
            client.close()
            Log.d(TAG, "verifyXrayConnection: CoreStatsClient closed")
            
            // If all retries showed 0 traffic, connection is considered failed
            if (!hasTraffic) {
                val finalUplink = lastTrafficStats?.uplink ?: 0
                val finalDownlink = lastTrafficStats?.downlink ?: 0
                Log.e(TAG, "verifyXrayConnection FAILED: All $maxRetries retries showed 0 traffic (uplink=$finalUplink, downlink=$finalDownlink) - connection may not be functional")
                return false
            }
            
            Log.i(TAG, "verifyXrayConnection SUCCESS: All checks passed - System stats OK, Traffic stats available (uplink=${lastTrafficStats?.uplink} bytes, downlink=${lastTrafficStats?.downlink} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "verifyXrayConnection EXCEPTION: Error verifying Xray connection on port $apiPort: ${e.message}", e)
            false
        }
    }
    
    /**
     * Closes the CoreStatsClient and clears the reference.
     * This is a thread-safe operation that should be called when:
     * - Service is stopped
     * - Client encounters an error
     * - Timeout occurs during gRPC calls
     * - Service is disabled
     */
    private fun closeCoreStatsClient() {
        // Use synchronized block for thread safety (non-suspend)
        synchronized(coreStatsClientLock) {
            // Prevent multiple shutdown attempts
            if (clientState == ClientState.SHUTTING_DOWN || clientState == ClientState.STOPPED) {
                return
            }
            
            clientState = ClientState.SHUTTING_DOWN
            lastClientCloseTime = System.currentTimeMillis()
            
            try {
                coreStatsClient?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing CoreStatsClient: ${e.message}", e)
            } finally {
                coreStatsClient = null
                clientState = ClientState.STOPPED
            }
        }
    }
    
    /**
     * Cleans up resources. Should be called when manager is no longer needed.
     */
    fun cleanup() {
        stopMonitoring()
        closeCoreStatsClient()
    }
}

// Helper functions for formatting (imported from common)
private fun formatBytes(bytes: Long): String {
    return com.hyperxray.an.common.formatBytes(bytes)
}

private fun formatThroughput(throughput: Double): String {
    return com.hyperxray.an.common.formatThroughput(throughput)
}

