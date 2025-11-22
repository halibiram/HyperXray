package com.hyperxray.an.xray.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile
import com.hyperxray.an.common.ConfigUtils
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.xray.runtime.LogLineCallback

/**
 * Manages multiple Xray-core instances for load distribution.
 * 
 * This manager allows running multiple xray-core instances simultaneously
 * to handle high client loads. Each instance runs independently with its
 * own API port and process lifecycle.
 * 
 * Thread-safety: All public methods are thread-safe.
 */
class MultiXrayCoreManager(private val context: Context) {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = Preferences(context)
    private var logLineCallback: LogLineCallback? = null
    
    @Volatile
    private var instances: MutableMap<Int, XrayRuntimeServiceApi> = mutableMapOf()
    
    @Volatile
    private var instancePorts: MutableMap<Int, Int> = mutableMapOf()
    
    private val loadBalancerCounter = AtomicInteger(0)
    private val mutex = Mutex()
    
    @Volatile
    private var isStarting = false
    
    private val _instancesStatus = MutableStateFlow<Map<Int, XrayRuntimeStatus>>(emptyMap())
    
    /**
     * Current status of all instances.
     * Map key is instance index (0-based), value is the status.
     */
    val instancesStatus: StateFlow<Map<Int, XrayRuntimeStatus>> = _instancesStatus.asStateFlow()
    
    companion object {
        private const val TAG = "MultiXrayCoreManager"
        private const val MIN_PORT = 10000
        private const val MAX_PORT = 65535
        private const val INSTANCE_STARTUP_TIMEOUT_MS = 15000L // 15 seconds timeout for instance to reach Running state
        
        @Volatile
        private var INSTANCE: MultiXrayCoreManager? = null
        
        /**
         * Get singleton instance of MultiXrayCoreManager.
         * Thread-safe lazy initialization using double-checked locking pattern.
         * 
         * @param context Application context (will use applicationContext to prevent memory leaks)
         * @return Singleton instance of MultiXrayCoreManager
         */
        fun getInstance(context: Context): MultiXrayCoreManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MultiXrayCoreManager(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        /**
         * Reset singleton instance (mainly for testing purposes).
         * Use with caution - only call when you're sure no other code is using the instance.
         */
        @Synchronized
        fun resetInstance() {
            INSTANCE?.cleanup()
            INSTANCE = null
        }
    }
    
    /**
     * Start multiple Xray-core instances.
     * 
     * @param count Number of instances to start (1-4)
     * @param configPath Path to the Xray configuration JSON file
     * @param configContent Optional pre-read configuration content
     * @param excludedPorts Set of ports to exclude when finding available ports
     * @return Map of instance index to API port, or empty map if startup failed
     */
    suspend fun startInstances(
        count: Int,
        configPath: String,
        configContent: String?,
        excludedPorts: Set<Int> = emptySet()
    ): Map<Int, Int> = mutex.withLock {
        // Prevent concurrent start calls
        if (isStarting) {
            val stackTrace = Exception("Duplicate startInstances call")
            Log.w(TAG, "Start already in progress, ignoring duplicate call", stackTrace)
            return emptyMap()
        }
        
        if (count < 1 || count > 4) {
            Log.e(TAG, "Invalid instance count: $count (must be 1-4)")
            return emptyMap()
        }
        
        isStarting = true
        try {
            // Check if we have any running instances - if yes, log warning but continue
            val runningCount = instances.values.count { it.isRunning() }
            if (runningCount > 0) {
                Log.w(TAG, "Starting new instances while $runningCount instances are still running. Stopping existing instances first.")
            }
            
            // Stop existing instances first (stopAllInstances already uses mutex internally)
            // Note: We're already holding mutex, so we need to call stopAllInstances without mutex
            stopAllInstancesInternal()
            
            Log.i(TAG, "Starting $count Xray-core instances in parallel")
        
            // OPTIMIZATION: Inject common config once (without API port)
            // Then each instance only needs lightweight port injection
            val commonConfigContent = try {
                ConfigUtils.injectCommonConfig(prefs, configContent ?: "")
            } catch (e: Exception) {
                Log.e(TAG, "Error injecting common config", e)
                configContent ?: ""
            }
            
            val startedInstances = mutableMapOf<Int, Int>()
            val allExcludedPorts = excludedPorts.toMutableSet()
            val portMutex = Mutex()
            
            // Phase 1: Parallel port allocation and instance creation
            val instanceStartupJobs = (0 until count).map { i ->
                serviceScope.async {
                    try {
                        // Check if cancelled or if start was cancelled before starting
                        ensureActive()
                        
                        // Double-check isStarting flag (defensive check)
                        if (!isStarting) {
                            Log.w(TAG, "Instance $i startup cancelled - isStarting flag is false")
                            return@async null
                        }
                        
                        // Allocate port with mutex to avoid conflicts
                        val apiPort: Int? = portMutex.withLock {
                            ensureActive()
                            this@MultiXrayCoreManager.findAvailablePort(allExcludedPorts)
                        }
                        
                        if (apiPort == null) {
                            Log.e(TAG, "Failed to find available port for instance $i")
                            return@async null
                        }
                        
                        portMutex.withLock {
                            allExcludedPorts.add(apiPort)
                        }
                        
                        // Check cancellation before config injection
                        ensureActive()
                        
                        // Check isStarting flag again (defensive check)
                        if (!isStarting) {
                            Log.w(TAG, "Instance $i startup cancelled during config injection - isStarting flag is false")
                            return@async null
                        }
                        
                        // OPTIMIZATION: Only inject API port into pre-processed common config
                        // This is much faster than full config injection for each instance
                        val injectedConfigContent = try {
                            ConfigUtils.injectApiPort(commonConfigContent, apiPort)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error injecting API port for instance $i", e)
                            // Fallback to old method if port injection fails
                            try {
                                ConfigUtils.injectStatsServiceWithPort(prefs, configContent ?: "", apiPort)
                            } catch (e2: Exception) {
                                Log.e(TAG, "Error in fallback config injection for instance $i", e2)
                                configContent ?: ""
                            }
                        }
                        
                        // Check cancellation before creating service
                        ensureActive()
                        
                        // Check isStarting flag again (defensive check)
                        if (!isStarting) {
                            Log.w(TAG, "Instance $i startup cancelled before service creation - isStarting flag is false")
                            return@async null
                        }
                        
                        val service = XrayRuntimeServiceFactory.create(context)
                        
                        mutex.withLock {
                            ensureActive()
                            instances[i] = service
                            instancePorts[i] = apiPort
                        }
                        
                        // Set log callback with instance tag if available
                        logLineCallback?.let { baseCallback ->
                            val port = apiPort
                            service.setLogLineCallback(LogLineCallback { line ->
                                val taggedLine = "[Instance-$i:Port-$port] $line"
                                baseCallback.onLogLine(taggedLine)
                            })
                        }
                        
                        // Observe status changes in background
                        serviceScope.launch {
                            service.status.collect { status ->
                                mutex.withLock {
                                    this@MultiXrayCoreManager.updateInstanceStatus(i, status)
                                }
                                when (status) {
                                    is XrayRuntimeStatus.Running -> {
                                        Log.i(TAG, "Instance $i is now running on port ${status.apiPort}")
                                    }
                                    is XrayRuntimeStatus.Error -> {
                                        Log.e(TAG, "Instance $i error: ${status.message}", status.throwable)
                                    }
                                    is XrayRuntimeStatus.ProcessExited -> {
                                        Log.e(TAG, "Instance $i exited with code ${status.exitCode}: ${status.message}")
                                    }
                                    else -> {}
                                }
                            }
                        }
                        
                        // Check cancellation before starting service
                        ensureActive()
                        
                        // Final check isStarting flag before actually starting
                        if (!isStarting) {
                            Log.w(TAG, "Instance $i startup cancelled before start() call - isStarting flag is false")
                            mutex.withLock {
                                instances.remove(i)
                                instancePorts.remove(i)
                            }
                            return@async null
                        }
                        
                        // Start the instance with injected config
                        Log.i(TAG, "Starting instance $i on port $apiPort")
                        val startTime = System.currentTimeMillis()
                        val startedPort = service.start(configPath, injectedConfigContent, apiPort)
                        val startDuration = System.currentTimeMillis() - startTime
                        
                        if (startedPort == null) {
                            Log.e(TAG, "Failed to start instance $i - service.start() returned null after ${startDuration}ms")
                            // Check current status for more details
                            val currentStatus = service.status.value
                            Log.e(TAG, "Instance $i current status: $currentStatus")
                            mutex.withLock {
                                instances.remove(i)
                                instancePorts.remove(i)
                            }
                            return@async null
                        }
                        
                        Log.i(TAG, "Instance $i start() returned port $startedPort after ${startDuration}ms (expected: $apiPort)")
                        
                        Pair(i, service)
                    } catch (e: CancellationException) {
                        // Expected cancellation - clean up and re-throw
                        Log.d(TAG, "Instance $i startup cancelled", e)
                        mutex.withLock {
                            instances.remove(i)
                            instancePorts.remove(i)
                        }
                        throw e // Re-throw to propagate cancellation
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting instance $i", e)
                        mutex.withLock {
                            instances.remove(i)
                            instancePorts.remove(i)
                        }
                        null
                    }
                }
            }
            
            // Wait for all instances to start (parallel)
            val servicesToWait = instanceStartupJobs.awaitAll().filterNotNull()
            
            // Check if start was cancelled during async operations
            if (!isStarting) {
                Log.w(TAG, "Start operation was cancelled during instance creation, cleaning up...")
                servicesToWait.forEach { (i, service) ->
                    try {
                        service.stop()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping instance $i during cleanup", e)
                    }
                }
                mutex.withLock {
                    instances.clear()
                    instancePorts.clear()
                    _instancesStatus.value = emptyMap()
                }
                return emptyMap()
            }
            
            if (servicesToWait.isEmpty()) {
                Log.e(TAG, "No instances started successfully")
                mutex.withLock {
                    instances.clear()
                    instancePorts.clear()
                    _instancesStatus.value = emptyMap()
                }
                return emptyMap()
            }
            
            // Phase 2: Wait for first instance to reach Running state, then continue in background
            // This allows early return for faster startup while other instances continue in background
            Log.i(TAG, "Phase 2: Waiting for ${servicesToWait.size} instances to reach Running state")
            val waitJobs = servicesToWait.map { (i, service) ->
                serviceScope.async {
                    val waitStartTime = System.currentTimeMillis()
                    try {
                        ensureActive()
                        val initialStatus = service.status.value
                        Log.d(TAG, "Instance $i: Initial status=$initialStatus, waiting for Running state (timeout: ${INSTANCE_STARTUP_TIMEOUT_MS}ms)")
                        withTimeout(INSTANCE_STARTUP_TIMEOUT_MS) {
                            // Wait for Running status
                            val runningStatus = service.status.first { status ->
                                ensureActive()
                                status is XrayRuntimeStatus.Running || 
                                status is XrayRuntimeStatus.Error ||
                                status is XrayRuntimeStatus.ProcessExited
                            }
                            
                            val waitDuration = System.currentTimeMillis() - waitStartTime
                            when (runningStatus) {
                                is XrayRuntimeStatus.Running -> {
                                    val port = mutex.withLock {
                                        instancePorts[i]
                                    }
                                    if (port != null) {
                                        mutex.withLock {
                                            startedInstances[i] = port
                                        }
                                        Log.i(TAG, "Instance $i started successfully and reached Running state on port $port (PID: ${runningStatus.processId}) after ${waitDuration}ms")
                                        Pair(i, true)
                                    } else {
                                        Log.e(TAG, "Instance $i reached Running state but port is null in instancePorts map")
                                        Pair(i, false)
                                    }
                                }
                                is XrayRuntimeStatus.Error -> {
                                    Log.e(TAG, "Instance $i failed to start: ${runningStatus.message}", runningStatus.throwable)
                                    mutex.withLock {
                                        instances.remove(i)
                                        instancePorts.remove(i)
                                    }
                                    Pair(i, false)
                                }
                                is XrayRuntimeStatus.ProcessExited -> {
                                    Log.e(TAG, "Instance $i exited during startup with code ${runningStatus.exitCode}: ${runningStatus.message}")
                                    mutex.withLock {
                                        instances.remove(i)
                                        instancePorts.remove(i)
                                    }
                                    Pair(i, false)
                                }
                                else -> {
                                    Log.w(TAG, "Instance $i reached unexpected status: $runningStatus")
                                    mutex.withLock {
                                        instances.remove(i)
                                        instancePorts.remove(i)
                                    }
                                    Pair(i, false)
                                }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        val waitDuration = System.currentTimeMillis() - waitStartTime
                        val currentStatus = service.status.value
                        Log.e(TAG, "Instance $i did not reach Running state within ${INSTANCE_STARTUP_TIMEOUT_MS}ms timeout (waited ${waitDuration}ms, current status: $currentStatus)")
                        
                        // Log process info if available
                        val processId = service.getProcessId()
                        if (processId != null) {
                            Log.e(TAG, "Instance $i process ID: $processId")
                        } else {
                            Log.e(TAG, "Instance $i process ID: null (process may have exited)")
                        }
                        
                        try {
                            service.stop()
                        } catch (stopException: Exception) {
                            Log.e(TAG, "Error stopping failed instance $i", stopException)
                        }
                        mutex.withLock {
                            instances.remove(i)
                            instancePorts.remove(i)
                        }
                        Pair(i, false)
                    } catch (e: CancellationException) {
                        // Expected cancellation - clean up and re-throw
                        Log.d(TAG, "Instance $i wait cancelled", e)
                        try {
                            service.stop()
                        } catch (stopException: Exception) {
                            Log.e(TAG, "Error stopping cancelled instance $i", stopException)
                        }
                        mutex.withLock {
                            instances.remove(i)
                            instancePorts.remove(i)
                        }
                        throw e // Re-throw to propagate cancellation
                    } catch (e: Exception) {
                        Log.e(TAG, "Error waiting for instance $i to start", e)
                        try {
                            service.stop()
                        } catch (stopException: Exception) {
                            Log.e(TAG, "Error stopping failed instance $i", stopException)
                        }
                        mutex.withLock {
                            instances.remove(i)
                            instancePorts.remove(i)
                        }
                        Pair(i, false)
                    }
                }
            }
            
            // Wait for first instance to become Running, then return immediately
            // Other instances continue in background
            var firstRunningFound = false
            try {
                for (job in waitJobs) {
                    coroutineContext.ensureActive()
                    val (index, success) = job.await()
                    if (success && !firstRunningFound) {
                        firstRunningFound = true
                        Log.i(TAG, "First instance ($index) is Running, returning early. Other instances continue in background.")
                        // Continue waiting for other instances in background
                        serviceScope.launch {
                            waitJobs.filter { it != job }.forEach { remainingJob ->
                                try {
                                    remainingJob.await()
                                } catch (e: CancellationException) {
                                    Log.d(TAG, "Background instance startup cancelled: ${e.message}")
                                    throw e // Re-throw cancellation
                                } catch (e: Exception) {
                                    Log.w(TAG, "Background instance startup error: ${e.message}")
                                }
                            }
                        }
                        break
                    }
                }
                
                // If no instance became Running, wait for all to complete
                if (!firstRunningFound) {
                    coroutineContext.ensureActive()
                    waitJobs.awaitAll()
                }
            } catch (e: CancellationException) {
                // Cleanup on cancellation
                Log.d(TAG, "Instance startup wait cancelled, cleaning up...")
                // Stop any instances that were created
                servicesToWait.forEach { (i, service) ->
                    try {
                        service.stop()
                    } catch (stopException: Exception) {
                        Log.e(TAG, "Error stopping instance $i during cancellation cleanup", stopException)
                    }
                }
                mutex.withLock {
                    instances.clear()
                    instancePorts.clear()
                    _instancesStatus.value = emptyMap()
                }
                throw e // Re-throw to propagate cancellation
            }
            
            // Final check and status update
            return mutex.withLock {
                if (startedInstances.isEmpty()) {
                    Log.e(TAG, "No instances started successfully")
                    _instancesStatus.value = emptyMap()
                    emptyMap()
                } else {
                    Log.i(TAG, "Successfully started ${startedInstances.size} out of $count instances")
                    _instancesStatus.value = instances.mapValues { (_, service) ->
                        service.status.value
                    }
                    startedInstances
                }
            }
        } finally {
            isStarting = false
        }
    }
    
    /**
     * Stop all instances.
     */
    suspend fun stopAllInstances() {
        mutex.withLock {
            Log.i(TAG, "Stopping all ${instances.size} instances")
            
            instances.values.forEach { service ->
                try {
                    service.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping instance", e)
                }
            }
            
            instances.values.forEach { service ->
                try {
                    service.cleanup()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up instance", e)
                }
            }
            
            instances.clear()
            instancePorts.clear()
            _instancesStatus.value = emptyMap()
            loadBalancerCounter.set(0)
            
            Log.i(TAG, "All instances stopped")
        }
    }
    
    /**
     * Get list of active (running) instances.
     * 
     * @return Map of instance index to API port
     */
    fun getActiveInstances(): Map<Int, Int> {
        val activeInstances = instancePorts.filter { (index, _) ->
            val service = instances[index]
            val isRunning = service != null && service.isRunning()
            
            if (!isRunning && service != null) {
                // Log why instance is not considered active
                val status = service.status.value
                Log.d(TAG, "Instance $index is not active: status=$status, isAlive=${service.getProcessId() != null}")
            }
            
            isRunning
        }
        
        if (activeInstances.isEmpty() && instancePorts.isNotEmpty()) {
            // Log detailed status of all instances for debugging
            Log.w(TAG, "No active instances found. Total instances: ${instances.size}, Ports: ${instancePorts.size}")
            instances.forEach { (index, service) ->
                val status = service.status.value
                val port = instancePorts[index]
                Log.w(TAG, "Instance $index: port=$port, status=$status, isRunning=${service.isRunning()}, processId=${service.getProcessId()}")
            }
        }
        
        return activeInstances
    }
    
    /**
     * Get next instance for load balancing (round-robin).
     * 
     * @return Pair of (instance index, API port), or null if no active instances
     */
    fun getNextInstance(): Pair<Int, Int>? {
        val activeInstances = getActiveInstances()
        if (activeInstances.isEmpty()) {
            return null
        }
        
        val instanceList = activeInstances.toList()
        val index = loadBalancerCounter.getAndIncrement() % instanceList.size
        return instanceList[index]
    }
    
    /**
     * Get instance by index.
     * 
     * @param index Instance index (0-based)
     * @return API port, or null if instance doesn't exist or not running
     */
    fun getInstancePort(index: Int): Int? {
        val service = instances[index]
        return if (service != null && service.isRunning()) {
            instancePorts[index]
        } else {
            null
        }
    }
    
    /**
     * Get total number of instances (running or not).
     */
    fun getInstanceCount(): Int {
        return instances.size
    }
    
    /**
     * Check if any instances are running.
     */
    fun hasRunningInstances(): Boolean {
        return instances.values.any { it.isRunning() }
    }
    
    /**
     * Cleanup all resources.
     */
    fun cleanup() {
        // Use runBlocking to ensure cleanup completes synchronously
        // This is safe because cleanup is called during service destruction
        runBlocking {
            stopAllInstances()
        }
        serviceScope.cancel()
    }
    
    // Private helper methods
    
    /**
     * Internal version of stopAllInstances that doesn't acquire mutex.
     * Used when we're already holding the mutex.
     */
    private suspend fun stopAllInstancesInternal() {
        Log.i(TAG, "Stopping all ${instances.size} instances (internal)")
        
        instances.values.forEach { service ->
            try {
                service.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping instance", e)
            }
        }
        
        instances.values.forEach { service ->
            try {
                service.cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up instance", e)
            }
        }
        
        instances.clear()
        instancePorts.clear()
        _instancesStatus.value = emptyMap()
        loadBalancerCounter.set(0)
        
        Log.i(TAG, "All instances stopped (internal)")
    }
    
    private fun findAvailablePort(excludedPorts: Set<Int>): Int? {
        (MIN_PORT..MAX_PORT)
            .shuffled()
            .forEach { port ->
                if (port in excludedPorts) return@forEach
                runCatching {
                    java.net.ServerSocket(port).use { socket ->
                        socket.reuseAddress = true
                    }
                    port
                }.onSuccess {
                    return it
                }
            }
        return null
    }
    
    private fun updateInstanceStatus(index: Int, status: XrayRuntimeStatus) {
        val currentStatus = _instancesStatus.value.toMutableMap()
        currentStatus[index] = status
        _instancesStatus.value = currentStatus
    }
    
    /**
     * Set callback for log lines from all Xray instances.
     * 
     * @param callback Callback to receive log lines, or null to disable
     */
    fun setLogLineCallback(callback: LogLineCallback?) {
        logLineCallback = callback
        // Update existing instances with instance tags (async to avoid blocking)
        serviceScope.launch {
            mutex.withLock {
                instances.forEach { (index, service) ->
                    if (callback != null) {
                        val port = instancePorts[index] ?: 0
                        // Create instance-specific callback that adds instance tag with port
                        service.setLogLineCallback(LogLineCallback { line ->
                            val taggedLine = "[Instance-$index:Port-$port] $line"
                            callback.onLogLine(taggedLine)
                        })
                    } else {
                        service.setLogLineCallback(null)
                    }
                }
            }
        }
    }
    
    /**
     * Extract ports from JSON config content to avoid conflicts.
     */
    fun extractPortsFromJson(jsonContent: String): Set<Int> {
        val portPattern = Regex("""["']port["']\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
        return portPattern.findAll(jsonContent)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .toSet()
    }
}

