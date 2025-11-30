package com.hyperxray.an.core.monitor

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages Android system memory statistics collection.
 * Collects process memory info (PSS, native heap, Dalvik heap), system memory info,
 * and Go runtime memory stats from native Xray-core.
 * Updates stats periodically when monitoring is active.
 */
class AndroidMemoryStatsManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val xrayStatsManager: XrayStatsManager? = null
) {
    private val TAG = "AndroidMemoryStatsManager"
    
    @Volatile
    private var isMonitoring: Boolean = false
    
    private var monitoringJob: Job? = null
    private var xrayStatsCollectJob: Job? = null
    
    private val _memoryStats = MutableStateFlow(AndroidMemoryStats())
    val memoryStats: StateFlow<AndroidMemoryStats> = _memoryStats.asStateFlow()
    
    // Cache current Go runtime stats to avoid reading StateFlow.value repeatedly
    @Volatile
    private var currentGoRuntimeStats: com.hyperxray.an.viewmodel.CoreStatsState? = null
    
    /**
     * Starts monitoring Android memory stats.
     * Should be called when connection is established or stats are needed.
     */
    fun startMonitoring() {
        if (isMonitoring) {
            Log.d(TAG, "Memory monitoring already started")
            return
        }
        
        isMonitoring = true
        Log.d(TAG, "Starting Android memory stats monitoring")
        
        // Reset stats when starting new monitoring session
        resetStats()
        
        // Start collecting XrayStatsManager stats updates if available
        xrayStatsCollectJob?.cancel()
        if (xrayStatsManager != null) {
            xrayStatsCollectJob = scope.launch {
                try {
                    Log.d(TAG, "Starting to collect XrayStatsManager stats updates")
                    xrayStatsManager.stats.collectLatest { stats ->
                        // Update cached Go runtime stats whenever XrayStatsManager updates
                        // The normal polling loop (2s interval) will use this cached value
                        currentGoRuntimeStats = stats
                        if (stats.alloc > 0L || stats.sys > 0L) {
                            Log.d(TAG, "✅ Go runtime stats received: alloc=${formatBytes(stats.alloc)}, sys=${formatBytes(stats.sys)}, mallocs=${stats.mallocs}, frees=${stats.frees}, liveObjects=${stats.liveObjects}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error collecting XrayStatsManager stats: ${e.message}", e)
                }
            }
        } else {
            Log.w(TAG, "⚠️ XrayStatsManager is null, Go runtime stats will not be available")
            xrayStatsCollectJob = null
        }
        
        // Start monitoring loop with 2 second interval
        monitoringJob = scope.launch {
            // Perform immediate first stats update
            Log.d(TAG, "Performing immediate first memory stats update")
            updateMemoryStats()
            
            // Then start the regular polling loop
            while (isActive && isMonitoring) {
                delay(2000L) // 2 seconds interval
                
                // Only update stats if still monitoring (check after delay)
                if (isActive && isMonitoring) {
                    updateMemoryStats()
                }
            }
        }
    }
    
    /**
     * Stops monitoring Android memory stats.
     * Should be called when connection is lost or monitoring is no longer needed.
     */
    fun stopMonitoring() {
        if (!isMonitoring) {
            return
        }
        
        Log.d(TAG, "Stopping Android memory stats monitoring")
        isMonitoring = false
        
        monitoringJob?.cancel()
        monitoringJob = null
        
        xrayStatsCollectJob?.cancel()
        xrayStatsCollectJob = null
    }
    
    /**
     * Resets all memory statistics to zero.
     * Should be called when starting a new connection to clear old data.
     */
    fun resetStats() {
        Log.d(TAG, "Resetting memory stats state to zero")
        _memoryStats.value = AndroidMemoryStats()
    }
    
    /**
     * Updates Android memory statistics from system.
     * Collects process memory info and system memory info.
     */
    private suspend fun updateMemoryStats() = withContext(Dispatchers.IO) {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val systemMemInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(systemMemInfo)
            
            val pid = Process.myPid()
            val pidMemInfo = Debug.MemoryInfo()
            // Use single-parameter version for current process
            Debug.getMemoryInfo(pidMemInfo)
            
            // Process memory in bytes
            val totalPss = pidMemInfo.totalPss * 1024L // Convert KB to bytes
            val nativeHeap = pidMemInfo.nativePss * 1024L
            val dalvikHeap = pidMemInfo.dalvikPss * 1024L
            val otherPss = pidMemInfo.otherPss * 1024L
            
            // Runtime memory
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val freeMemory = runtime.freeMemory()
            
            // System memory in bytes
            val systemTotalMem = systemMemInfo.totalMem
            val systemAvailMem = systemMemInfo.availMem
            val systemUsedMem = systemTotalMem - systemAvailMem
            val systemThreshold = systemMemInfo.threshold
            val systemLowMemory = systemMemInfo.lowMemory
            
            // Calculate percentages
            val processMemoryUsagePercent = if (systemTotalMem > 0) {
                ((totalPss.toDouble() / systemTotalMem) * 100.0).toInt().coerceIn(0, 100)
            } else 0
            
            val runtimeMemoryUsagePercent = if (maxMemory > 0) {
                ((usedMemory.toDouble() / maxMemory) * 100.0).toInt().coerceIn(0, 100)
            } else 0
            
            val systemMemoryUsagePercent = if (systemTotalMem > 0) {
                ((systemUsedMem.toDouble() / systemTotalMem) * 100.0).toInt().coerceIn(0, 100)
            } else 0
            
            // Get Go runtime memory stats from cached value (updated via collectLatest)
            // Fallback to reading StateFlow.value if cache is not available
            val goRuntimeStats = currentGoRuntimeStats ?: xrayStatsManager?.stats?.value
            val goAlloc = goRuntimeStats?.alloc ?: 0L
            val goTotalAlloc = goRuntimeStats?.totalAlloc ?: 0L
            val goSys = goRuntimeStats?.sys ?: 0L
            val goMallocs = goRuntimeStats?.mallocs ?: 0L
            val goFrees = goRuntimeStats?.frees ?: 0L
            val goLiveObjects = goRuntimeStats?.liveObjects ?: 0L
            val goPauseTotalNs = goRuntimeStats?.pauseTotalNs ?: 0L
            
            // Debug log if Go runtime stats are missing
            if (goAlloc == 0L && goSys == 0L && xrayStatsManager != null) {
                Log.v(TAG, "⚠️ Go runtime stats are zero - XrayStatsManager available but stats not yet received")
            }
            
            _memoryStats.value = AndroidMemoryStats(
                // Process memory
                totalPss = totalPss,
                nativeHeap = nativeHeap,
                dalvikHeap = dalvikHeap,
                otherPss = otherPss,
                // Runtime memory
                usedMemory = usedMemory,
                maxMemory = maxMemory,
                freeMemory = freeMemory,
                // System memory
                systemTotalMem = systemTotalMem,
                systemAvailMem = systemAvailMem,
                systemUsedMem = systemUsedMem,
                systemThreshold = systemThreshold,
                systemLowMemory = systemLowMemory,
                // Go runtime memory (from native Xray-core)
                goAlloc = goAlloc,
                goTotalAlloc = goTotalAlloc,
                goSys = goSys,
                goMallocs = goMallocs,
                goFrees = goFrees,
                goLiveObjects = goLiveObjects,
                goPauseTotalNs = goPauseTotalNs,
                // Percentages
                processMemoryUsagePercent = processMemoryUsagePercent,
                runtimeMemoryUsagePercent = runtimeMemoryUsagePercent,
                systemMemoryUsagePercent = systemMemoryUsagePercent,
                // Timestamp
                updateTimestamp = System.currentTimeMillis()
            )
            
            Log.v(TAG, "Memory stats updated - Total PSS: ${formatBytes(totalPss)}, Native: ${formatBytes(nativeHeap)}, Dalvik: ${formatBytes(dalvikHeap)}, Go Alloc: ${formatBytes(goAlloc)}")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating memory stats: ${e.message}", e)
        }
    }
    
    /**
     * Cleans up resources. Should be called when manager is no longer needed.
     */
    fun cleanup() {
        stopMonitoring()
    }
    
    companion object {
        private fun formatBytes(bytes: Long): String {
            return com.hyperxray.an.common.formatBytes(bytes)
        }
    }
}

/**
 * Android system memory statistics data class.
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

