package com.hyperxray.an.core.monitor

import android.util.Log
import com.hyperxray.an.viewmodel.CoreStatsState
import com.hyperxray.an.xray.runtime.stats.CoreStatsClient
import com.hyperxray.an.xray.runtime.stats.model.TrafficState
import com.xray.app.stats.command.SysStatsResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * Collects and manages Xray core statistics in single instance mode.
 * Exposes stats updates via StateFlow for UI consumption.
 */
class XrayStatsManager(
    private val scope: CoroutineScope,
    private val apiPortProvider: () -> Int,
    private val activeInstancesProvider: (suspend () -> Map<Int, Int>)? = null
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
        
        // Reset stats when starting new monitoring session to clear old data
        // This ensures fresh stats for new connection
        resetStats()
        
        // FIXED: Reset client cooldown when starting new monitoring session
        // This allows immediate client creation on first stats update
        // instead of waiting for cooldown period (5 seconds)
        synchronized(coreStatsClientLock) {
            lastClientCloseTime = 0L
            consecutiveFailures = 0
            if (clientState == ClientState.FAILED) {
                clientState = ClientState.STOPPED
            }
            Log.d(TAG, "Reset client cooldown for new monitoring session")
        }
        
        // Start monitoring loop with dynamic interval based on traffic load
        monitoringJob = scope.launch {
            // FIXED: Perform immediate first stats update without delay
            // This ensures stats appear immediately when connection is established
            // instead of waiting for the first interval delay (2 seconds)
            Log.d(TAG, "Performing immediate first stats update")
            updateCoreStats()
            
            // Then start the regular polling loop
            while (isActive && isMonitoring) {
                // Dynamic interval based on current throughput
                // High traffic -> fast polling (1s), low traffic -> slow polling (2s)
                val currentStats = _statsState.value
                val totalThroughput = currentStats.uplinkThroughput + currentStats.downlinkThroughput
                val interval = when {
                    totalThroughput > 1_000_000 -> 1000L   // > 1MB/s: 1s (high traffic)
                    totalThroughput > 100_000 -> 1500L     // > 100KB/s: 1.5s (medium traffic)
                    else -> 2000L                          // Low/Idle: 2s (battery saving)
                }
                delay(interval)
                
                // Only update stats if still monitoring (check after delay)
                if (isActive && isMonitoring) {
                    updateCoreStats()
                }
            }
        }
    }
    
    /**
     * Stops monitoring Xray stats.
     * Should be called when connection is lost or service is disabled.
     * Note: Stats are NOT reset here - they are reset when new monitoring starts.
     * This allows stats to remain visible briefly after disconnection.
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
        
        // Do NOT reset stats here - they will be reset when startMonitoring() is called
        // This ensures stats remain visible briefly after disconnection and are properly
        // reset when a new connection starts
    }
    
    /**
     * Resets all statistics to zero.
     * Should be called when starting a new connection to clear old data.
     */
    fun resetStats() {
        Log.d(TAG, "Resetting stats state to zero")
        
        // Reset throughput calculation state
        lastUplink = 0L
        lastDownlink = 0L
        lastStatsTime = 0L
        
        // Reset stats state to zero
        _statsState.value = CoreStatsState()
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
     * Collects and manages Xray core statistics in single instance mode.
     */
    suspend fun updateCoreStats() {
        // Check if service is enabled before proceeding
        if (!isServiceEnabled) {
            // Service is not enabled, ensure clients are closed if they exist
            closeCoreStatsClient()
            consecutiveFailures = 0
            return
        }
        
        // Single instance mode: use legacy apiPortProvider
        Log.d(TAG, "Using single instance mode (apiPort: ${apiPortProvider()})")
        updateCoreStatsSingleInstance()
    }
    
    /**
     * Updates core statistics from single instance (legacy mode).
     */
    private suspend fun updateCoreStatsSingleInstance() {
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
                // FIXED: Reduced backoff to prevent stats from stopping for too long
                // Max backoff is now 10 seconds instead of 30 seconds
                if (consecutiveFailures > 0) {
                    val backoffMs = minOf(
                        MIN_RECREATE_INTERVAL_MS * (1L shl minOf(consecutiveFailures - 1, 2)), // Max 2^2 = 4x = 20s, but capped at 10s
                        10000L // Cap at 10 seconds instead of 30 seconds
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
            val currentState = _statsState.value
            _statsState.value = currentState.copy()
            return
        }
        
        // Get stats from single instance
        val statsResult = withTimeoutOrNull(5000L) {
            try {
                if (!isServiceEnabled) return@withTimeoutOrNull null
                client.getSystemStats()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting system stats: ${e.message}", e)
                null
            }
        }
        
        if (statsResult == null) {
            Log.w(TAG, "Stats query failed (timeout/exception/disabled)")
            if (!isServiceEnabled) {
                closeCoreStatsClient()
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    Log.w(TAG, "Multiple consecutive failures ($consecutiveFailures), closing client")
                    synchronized(coreStatsClientLock) {
                        clientState = ClientState.FAILED
                    }
                    closeCoreStatsClient()
                }
            }
            return
        }
        
        // FIXED: Reset consecutiveFailures on successful stats query (not just client creation)
        consecutiveFailures = 0
        synchronized(coreStatsClientLock) {
            if (clientState != ClientState.READY) {
                clientState = ClientState.READY
            }
        }
        
        if (!isServiceEnabled) {
            Log.d(TAG, "Service disabled during stats update, skipping")
            closeCoreStatsClient()
            return
        }
        
        val trafficResult = withTimeoutOrNull(5000L) {
            try {
                if (!isServiceEnabled) return@withTimeoutOrNull null
                client.getTraffic()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting traffic stats: ${e.message}", e)
                null
            }
        }
        
        if (trafficResult == null) {
            Log.w(TAG, "Traffic query failed (timeout/exception/disabled)")
            if (!isServiceEnabled) {
                closeCoreStatsClient()
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                // FIXED: Increased threshold from 5 to 10 to prevent premature client closure
                // Also, don't close client immediately - let it retry with backoff
                if (consecutiveFailures >= 10) {
                    Log.w(TAG, "Multiple consecutive failures ($consecutiveFailures), closing client for retry")
                    synchronized(coreStatsClientLock) {
                        clientState = ClientState.FAILED
                    }
                    closeCoreStatsClient()
                    consecutiveFailures = 0 // Reset to allow immediate retry after backoff
                }
            }
            return
        }
        
        // FIXED: Reset consecutiveFailures on successful traffic query
        consecutiveFailures = 0
        synchronized(coreStatsClientLock) {
            if (clientState != ClientState.READY) {
                clientState = ClientState.READY
            }
        }
        
        if (!isServiceEnabled) {
            Log.d(TAG, "Service disabled during stats update, skipping")
            closeCoreStatsClient()
            return
        }
        
        updateStatsState(statsResult, trafficResult)
    }
    
    /**
     * Updates core statistics from multiple instances and aggregates them.
     */
    
    /**
     * Updates the stats state with new values and calculates throughput.
     */
    private suspend fun updateStatsState(
        stats: SysStatsResponse?,
        traffic: TrafficState?
    ) {
        val currentState = _statsState.value
        val newUplink = traffic?.uplink ?: currentState.uplink
        val newDownlink = traffic?.downlink ?: currentState.downlink
        
        // Validate data consistency - detect if downlink stat is stuck
        val isDownlinkStuck = newDownlink == currentState.downlink && newUplink != currentState.uplink && currentState.downlink > 0L
        
        if (isDownlinkStuck) {
            Log.w(TAG, "⚠️ Downlink stat appears stuck at ${formatBytes(newDownlink)} (uplink changed: ${formatBytes(currentState.uplink)} -> ${formatBytes(newUplink)})")
            // Reset lastDownlink to current value to prevent false throughput calculation
            // This handles the case where Xray-core's downlink stat is not updating
            lastDownlink = newDownlink
        }
        
        // Handle negative deltas (shouldn't happen but protect against it)
        val now = System.currentTimeMillis()
        var uplinkThroughput = currentState.uplinkThroughput
        var downlinkThroughput = currentState.downlinkThroughput
        
        if (lastStatsTime > 0 && now > lastStatsTime) {
            val timeDelta = (now - lastStatsTime) / 1000.0
            
            if (timeDelta >= 0.5) {
                val uplinkDelta = newUplink - lastUplink
                val downlinkDelta = newDownlink - lastDownlink
                
                // Protect against negative deltas (can happen if stats reset or instance restarts)
                if (uplinkDelta >= 0) {
                    uplinkThroughput = uplinkDelta / timeDelta
                } else {
                    Log.w(TAG, "⚠️ Negative uplink delta detected: $uplinkDelta (new=${formatBytes(newUplink)}, last=${formatBytes(lastUplink)}) - resetting baseline")
                    uplinkThroughput = 0.0
                    lastUplink = newUplink // Reset baseline
                }
                
                // Handle stuck downlink stat: if delta is 0 but uplink changed, keep previous throughput
                // This prevents showing 0 B/s when downlink stat is stuck in Xray-core
                if (downlinkDelta > 0) {
                    downlinkThroughput = downlinkDelta / timeDelta
                } else if (downlinkDelta == 0L && uplinkDelta > 0 && isDownlinkStuck) {
                    // Downlink stat is stuck - preserve previous throughput instead of showing 0
                    Log.d(TAG, "Downlink stat stuck, preserving previous throughput: ${formatThroughput(downlinkThroughput)}")
                    // Don't reset lastDownlink here - already reset above
                } else if (downlinkDelta < 0) {
                    Log.w(TAG, "⚠️ Negative downlink delta detected: $downlinkDelta (new=${formatBytes(newDownlink)}, last=${formatBytes(lastDownlink)}) - resetting baseline")
                    downlinkThroughput = 0.0
                    lastDownlink = newDownlink // Reset baseline
                } else {
                    // Delta is 0 and not stuck - normal idle state
                    downlinkThroughput = 0.0
                }
                
                Log.d(TAG, "Throughput calculated: uplink=${formatThroughput(uplinkThroughput)}, downlink=${formatThroughput(downlinkThroughput)}, timeDelta=${timeDelta}s, uplinkDelta=${formatBytes(uplinkDelta)}, downlinkDelta=${formatBytes(downlinkDelta)}")
            } else {
                Log.d(TAG, "Time delta too small (${timeDelta}s < 0.5s), preserving previous throughput values")
            }
        } else if (lastStatsTime == 0L) {
            Log.d(TAG, "First throughput measurement - initializing baseline: uplink=${formatBytes(newUplink)}, downlink=${formatBytes(newDownlink)}")
        }
        
        lastUplink = newUplink
        // Only update lastDownlink if it's not stuck (already updated above if stuck)
        if (!isDownlinkStuck) {
            lastDownlink = newDownlink
        }
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
            
            // Test 2: Traffic stats - verify we can actually get traffic data
            // NOTE: Traffic can be 0 initially (no traffic yet), so we only verify that the API is accessible
            Log.d(TAG, "verifyXrayConnection: Testing traffic stats API accessibility...")
            val trafficStats = withTimeoutOrNull(3000L) {
                client.getTraffic()
            }
            
            if (trafficStats == null) {
                Log.e(TAG, "verifyXrayConnection FAILED: getTraffic returned null (port: $apiPort) - traffic stats API not accessible")
                client.close()
                return false
            }
            
            val totalTraffic = trafficStats.uplink + trafficStats.downlink
            Log.d(TAG, "verifyXrayConnection: Traffic stats API accessible - uplink=${trafficStats.uplink} bytes, downlink=${trafficStats.downlink} bytes, total=$totalTraffic bytes")
            
            // Traffic can be 0 initially - this is normal for a fresh connection
            // We only verify that the API is accessible, not that there's actual traffic
            if (totalTraffic > 0) {
                Log.i(TAG, "verifyXrayConnection: Traffic detected - connection is active")
            } else {
                Log.d(TAG, "verifyXrayConnection: No traffic yet (0 bytes) - this is normal for a fresh connection")
            }
            
            client.close()
            Log.d(TAG, "verifyXrayConnection: CoreStatsClient closed")
            
            // Success: System stats OK and traffic stats API is accessible
            // Traffic being 0 is acceptable - it means connection is ready but no traffic yet
            Log.i(TAG, "verifyXrayConnection SUCCESS: All checks passed - System stats OK, Traffic stats API accessible (uplink=${trafficStats.uplink} bytes, downlink=${trafficStats.downlink} bytes)")
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
        kotlinx.coroutines.runBlocking {
        }
    }
}

// Helper functions for formatting (imported from common)
private fun formatBytes(bytes: Long): String {
    return com.hyperxray.an.common.formatBytes(bytes)
}

private fun formatThroughput(throughput: Double): String {
    return com.hyperxray.an.common.formatThroughput(throughput)
}

