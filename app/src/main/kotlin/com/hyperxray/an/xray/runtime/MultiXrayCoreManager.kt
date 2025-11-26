package com.hyperxray.an.xray.runtime

import android.content.Context
import android.util.Log
import com.hyperxray.an.common.AiLogHelper
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
import com.hyperxray.an.core.config.utils.ConfigInjector
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
    
    // Sticky routing cache for domain/IP to instance mapping
    private val routingCache = InstanceRoutingCache(
        maxSize = prefs.stickyRoutingCacheSize,
        ttlMs = prefs.stickyRoutingTtlMs
    )
    
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
    ): Map<Int, Int> {
        val startTime = System.currentTimeMillis()
        AiLogHelper.i(TAG, "🚀 XRAY MANAGER START: startInstances() called with count=$count, configPath=$configPath")
        
        // Step 1: Acquire lock -> Check state -> Release lock
        val shouldProceed = mutex.withLock {
            // Prevent concurrent start calls
            if (isStarting) {
                val stackTrace = Exception("Duplicate startInstances call")
                Log.w(TAG, "Start already in progress, ignoring duplicate call", stackTrace)
                AiLogHelper.w(TAG, "⚠️ XRAY MANAGER START: Already in progress, ignoring duplicate call")
                return@withLock false
            }
            
            if (count < 1 || count > 4) {
                val errorMsg = "Invalid instance count: $count (must be 1-4)"
                Log.e(TAG, "❌ $errorMsg")
                AiLogHelper.e(TAG, "❌ XRAY MANAGER START FAILED: $errorMsg")
                return@withLock false
            }
            
            Log.i(TAG, "🚀 MultiXrayCoreManager.startInstances() called with count=$count")
            AiLogHelper.i(TAG, "🚀 XRAY MANAGER START: Starting $count instance(s)")
            isStarting = true
            true
        }
        
        if (!shouldProceed) {
            return emptyMap()
        }
        
        try {
            // Step 2: Check existing instances and log reconfiguration
            val previousCount: Int
            val previousPorts: List<Int>
            mutex.withLock {
                previousCount = instances.size
                previousPorts = instancePorts.values.toList()
            }
            
            if (previousCount > 0) {
                Log.i(TAG, "🔄 Reconfiguring instances from $previousCount to $count (previous ports: $previousPorts)")
                AiLogHelper.i(TAG, "🔄 XRAY MANAGER START: Reconfiguring instances from $previousCount to $count (previous ports: $previousPorts)")
            }
            
            // Step 3: Stop existing instances first
            // Acquire mutex only for the stop operation, then release before starting new instances
            if (previousCount > 0) {
                Log.i(TAG, "🛑 Stopping $previousCount existing instances before starting new ones...")
                AiLogHelper.i(TAG, "🛑 XRAY MANAGER START: Stopping $previousCount existing instances before starting new ones...")
                val stopStartTime = System.currentTimeMillis()
                mutex.withLock {
                    stopAllInstancesInternal()
                }
                val stopDuration = System.currentTimeMillis() - stopStartTime
                Log.i(TAG, "✅ Existing instances stopped, proceeding with new instance startup")
                AiLogHelper.i(TAG, "✅ XRAY MANAGER START: Existing instances stopped (duration: ${stopDuration}ms), proceeding with new instance startup")
            }
            
            Log.i(TAG, "▶️ Starting $count Xray-core instances sequentially")
            Log.d(TAG, "Instance startup configuration: count=$count, configPath=$configPath, excludedPortsCount=${excludedPorts.size}")
            AiLogHelper.i(TAG, "▶️ XRAY MANAGER START: Starting $count Xray-core instances sequentially")
            AiLogHelper.d(TAG, "📋 XRAY MANAGER START: Configuration - count=$count, configPath=$configPath, excludedPorts=${excludedPorts.size}")
        
            // OPTIMIZATION: Inject common config once (without API port)
            // Then each instance only needs lightweight port injection
            val commonConfigContent = try {
                ConfigInjector.injectCommonConfig(prefs, configContent ?: "")
            } catch (e: Exception) {
                Log.e(TAG, "Error injecting common config", e)
                configContent ?: ""
            }
            
            val startedInstances = mutableMapOf<Int, Int>()
            val allExcludedPorts = excludedPorts.toMutableSet()
            
            // SYNC: Start instances sequentially, each waits for the previous to complete
            Log.i(TAG, "Starting ${count} instances sequentially (sync mode)")
            val servicesToWait = mutableListOf<Pair<Int, XrayRuntimeServiceApi>>()
            
            // Step 4: Start each instance sequentially (SYNC MODE)
            // CRITICAL: Mutex is NOT held during service.start() or waiting for Running state
            for (i in 0 until count) {
                val instanceStartTime = System.currentTimeMillis()
                try {
                    Log.i(TAG, "📋 Starting instance $i (${i + 1}/$count) sequentially...")
                    AiLogHelper.i(TAG, "📋 XRAY MANAGER START: Starting instance $i (${i + 1}/$count) sequentially...")
                    
                    // Check cancellation
                    coroutineContext.ensureActive()
                    
                    // Check isStarting flag (no mutex needed for read)
                    if (!isStarting) {
                        Log.w(TAG, "⚠️ Instance $i: Startup cancelled - isStarting flag is false")
                        AiLogHelper.w(TAG, "⚠️ XRAY MANAGER START: Instance $i startup cancelled - isStarting flag is false")
                        break
                    }
                    
                    // Allocate port (no mutex needed)
                    Log.d(TAG, "Instance $i: Allocating port...")
                    AiLogHelper.d(TAG, "🔧 XRAY MANAGER START: Instance $i - Allocating port...")
                    val apiPort: Int? = this@MultiXrayCoreManager.findAvailablePort(allExcludedPorts)
                    
                    if (apiPort == null) {
                        Log.e(TAG, "❌ Instance $i: Failed to find available port")
                        AiLogHelper.e(TAG, "❌ XRAY MANAGER START: Instance $i - Failed to find available port")
                        break
                    }
                    Log.d(TAG, "Instance $i: Port allocated: $apiPort")
                    AiLogHelper.i(TAG, "✅ XRAY MANAGER START: Instance $i - Port allocated: $apiPort")
                    allExcludedPorts.add(apiPort)
                    
                    // Inject API port into config (no mutex needed)
                    Log.d(TAG, "Instance $i: Injecting API port $apiPort into config...")
                    AiLogHelper.d(TAG, "🔧 XRAY MANAGER START: Instance $i - Injecting API port $apiPort into config...")
                    val injectedConfigContent = try {
                        ConfigInjector.injectApiPort(commonConfigContent, apiPort)
                            .also { Log.d(TAG, "Instance $i: Config injection completed (config size: ${it.length} bytes)") }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Instance $i: Error injecting API port", e)
                        try {
                            ConfigInjector.injectStatsServiceWithPort(prefs, configContent ?: "", apiPort)
                                .also { Log.d(TAG, "Instance $i: Fallback config injection completed") }
                        } catch (e2: Exception) {
                            Log.e(TAG, "❌ Instance $i: Error in fallback config injection", e2)
                            configContent ?: ""
                        }
                    }
                    
                    // Create service (no mutex needed)
                    Log.d(TAG, "Instance $i: Creating XrayRuntimeService...")
                    val service = XrayRuntimeServiceFactory.create(context)
                    Log.d(TAG, "Instance $i: XrayRuntimeService created successfully")
                    
                    // Step 4a: Acquire Lock -> Reserve Port & Create Service object -> Release Lock
                    mutex.withLock {
                        coroutineContext.ensureActive()
                        instances[i] = service
                        instancePorts[i] = apiPort
                        Log.d(TAG, "Instance $i: Service and port stored in instances map")
                    }
                    
                    // Set log callback (no mutex needed)
                    logLineCallback?.let { baseCallback ->
                        val port = apiPort
                        service.setLogLineCallback(LogLineCallback { line ->
                            val taggedLine = "[Instance-$i:Port-$port] $line"
                            baseCallback.onLogLine(taggedLine)
                        })
                        Log.d(TAG, "Instance $i: Log callback set successfully")
                    }
                    
                    // Observe status changes in background (no mutex needed for launch)
                    serviceScope.launch {
                        service.status.collect { status ->
                            // Only acquire mutex for map update
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
                    
                    // Step 4b: service.start() - NO MUTEX HERE (IO Operation)
                    Log.i(TAG, "🚀 Instance $i: Starting on port $apiPort (configPath: $configPath)")
                    val startTime = System.currentTimeMillis()
                    val startedPort = try {
                        service.start(configPath, injectedConfigContent, apiPort)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Instance $i: Exception during service.start() call", e)
                        // Acquire lock only for cleanup
                        mutex.withLock {
                            instances.remove(i)
                            instancePorts.remove(i)
                        }
                        continue
                    }
                    val startDuration = System.currentTimeMillis() - startTime
                    
                    if (startedPort == null) {
                        Log.e(TAG, "❌ Instance $i: service.start() returned null after ${startDuration}ms")
                        // Acquire lock only for cleanup
                        mutex.withLock {
                            instances.remove(i)
                            instancePorts.remove(i)
                        }
                        continue
                    }
                    
                    Log.i(TAG, "✅ Instance $i: start() returned port $startedPort after ${startDuration}ms (expected: $apiPort)")
                    
                    // Step 4c: Wait for Running state - NO MUTEX HERE (blocks until running)
                    Log.i(TAG, "⏳ Instance $i: Waiting for Running state (timeout: ${INSTANCE_STARTUP_TIMEOUT_MS}ms)...")
                    val waitStartTime = System.currentTimeMillis()
                    try {
                        withTimeout(INSTANCE_STARTUP_TIMEOUT_MS) {
                            val runningStatus = service.status.first { status ->
                                coroutineContext.ensureActive()
                                status is XrayRuntimeStatus.Running || 
                                status is XrayRuntimeStatus.Error ||
                                status is XrayRuntimeStatus.ProcessExited
                            }
                            
                            val waitDuration = System.currentTimeMillis() - waitStartTime
                            when (runningStatus) {
                                is XrayRuntimeStatus.Running -> {
                                    // Step 4d: Acquire Lock -> Update instances map & Status -> Release Lock
                                    mutex.withLock {
                                        startedInstances[i] = apiPort
                                    }
                                    Log.i(TAG, "✅ Instance $i: Running state reached on port $apiPort (PID: ${runningStatus.processId}) after ${waitDuration}ms")
                                    servicesToWait.add(Pair(i, service))
                                }
                                is XrayRuntimeStatus.Error -> {
                                    Log.e(TAG, "❌ Instance $i failed to start: ${runningStatus.message}", runningStatus.throwable)
                                    // Acquire lock only for cleanup
                                    mutex.withLock {
                                        instances.remove(i)
                                        instancePorts.remove(i)
                                    }
                                }
                                is XrayRuntimeStatus.ProcessExited -> {
                                    Log.e(TAG, "❌ Instance $i exited during startup with code ${runningStatus.exitCode}: ${runningStatus.message}")
                                    // Acquire lock only for cleanup
                                    mutex.withLock {
                                        instances.remove(i)
                                        instancePorts.remove(i)
                                    }
                                }
                                else -> {
                                    Log.w(TAG, "⚠️ Instance $i reached unexpected status: $runningStatus")
                                    // Acquire lock only for cleanup
                                    mutex.withLock {
                                        instances.remove(i)
                                        instancePorts.remove(i)
                                    }
                                }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        val waitDuration = System.currentTimeMillis() - waitStartTime
                        val currentStatus = service.status.value
                        Log.e(TAG, "❌ Instance $i: Timeout! Did not reach Running state within ${INSTANCE_STARTUP_TIMEOUT_MS}ms (waited ${waitDuration}ms, current status: $currentStatus)")
                        try {
                            service.stop()
                        } catch (stopException: Exception) {
                            Log.e(TAG, "Error stopping failed instance $i", stopException)
                        }
                        // Acquire lock only for cleanup
                        mutex.withLock {
                            instances.remove(i)
                            instancePorts.remove(i)
                        }
                        continue
                    } catch (e: CancellationException) {
                        Log.d(TAG, "Instance $i startup cancelled", e)
                        try {
                            service.stop()
                        } catch (stopException: Exception) {
                            Log.e(TAG, "Error stopping cancelled instance $i", stopException)
                        }
                        // Acquire lock only for cleanup
                        mutex.withLock {
                            instances.remove(i)
                            instancePorts.remove(i)
                        }
                        throw e
                    }
                    
                    Log.i(TAG, "✅ Instance $i started successfully, proceeding to next instance...")
                    
                } catch (e: CancellationException) {
                    Log.d(TAG, "Instance $i startup cancelled", e)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error starting instance $i", e)
                    // Continue with next instance
                }
            }
            
            // Check if start was cancelled
            if (!isStarting) {
                Log.w(TAG, "⚠️ Start operation was cancelled during instance creation, cleaning up...")
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
                Log.e(TAG, "❌ No instances started successfully")
                mutex.withLock {
                    instances.clear()
                    instancePorts.clear()
                    _instancesStatus.value = emptyMap()
                }
                return emptyMap()
            }
            
            Log.i(TAG, "✅ All ${servicesToWait.size} instances started successfully (requested: $count)")
            
            // Final check and status update
            return mutex.withLock {
                if (startedInstances.isEmpty()) {
                    Log.e(TAG, "No instances started successfully")
                    _instancesStatus.value = emptyMap()
                    emptyMap()
                } else {
                    val startedCount = startedInstances.size
                    val failedCount = count - startedCount
                    if (failedCount > 0) {
                        Log.w(TAG, "⚠️ Partially successful: Started $startedCount out of $count instances ($failedCount failed)")
                    } else {
                        Log.i(TAG, "✅ Successfully started all $startedCount instances")
                    }
                    Log.d(TAG, "Instance status map: ${instances.mapValues { (_, service) -> service.status.value }}")
                    _instancesStatus.value = instances.mapValues { (_, service) ->
                        service.status.value
                    }
                    startedInstances
                }
            }
        } finally {
            mutex.withLock {
                isStarting = false
            }
        }
    }
    
    /**
     * Stop all instances.
     * Thread-safe public method that acquires mutex.
     * Use stopAllInstancesInternal() when already holding the mutex.
     */
    suspend fun stopAllInstances() {
        val stopTime = System.currentTimeMillis()
        AiLogHelper.i(TAG, "🛑 XRAY MANAGER STOP: stopAllInstances() called")
        mutex.withLock {
            stopAllInstancesInternal()
        }
        val stopDuration = System.currentTimeMillis() - stopTime
        AiLogHelper.i(TAG, "✅ XRAY MANAGER STOP SUCCESS: All instances stopped (duration: ${stopDuration}ms)")
    }
    
    /**
     * Get list of active (running) instances.
     * 
     * @return Map of instance index to API port
     */
    fun getActiveInstances(): Map<Int, Int> {
        Log.d(TAG, "getActiveInstances() called - Total instances: ${instances.size}, Total ports: ${instancePorts.size}")
        
        val activeInstances = instancePorts.filter { (index, _) ->
            val service = instances[index]
            val isRunning = service != null && service.isRunning()
            
            if (!isRunning && service != null) {
                // Log why instance is not considered active
                val status = service.status.value
                val port = instancePorts[index]
                Log.d(TAG, "Instance $index (port $port) is not active: status=$status, isRunning=$isRunning, isAlive=${service.getProcessId() != null}")
            } else if (isRunning) {
                val port = instancePorts[index]
                Log.d(TAG, "Instance $index (port $port) is active and running")
            }
            
            isRunning
        }
        
        if (activeInstances.isEmpty() && instancePorts.isNotEmpty()) {
            // Log detailed status of all instances for debugging
            Log.w(TAG, "⚠️ No active instances found. Total instances: ${instances.size}, Ports: ${instancePorts.size}")
            instances.forEach { (index, service) ->
                val status = service.status.value
                val port = instancePorts[index]
                Log.w(TAG, "Instance $index: port=$port, status=$status, isRunning=${service.isRunning()}, processId=${service.getProcessId()}")
            }
        } else if (activeInstances.isNotEmpty()) {
            Log.d(TAG, "✅ Found ${activeInstances.size} active instances: ${activeInstances.map { "Instance-${it.key}:Port-${it.value}" }}")
        } else {
            Log.d(TAG, "No instances configured yet (instances=${instances.size}, ports=${instancePorts.size})")
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
     * Get instance for domain using sticky routing.
     * Returns cached instance if available, otherwise selects new instance based on hash.
     * 
     * @param domain Domain name
     * @return Pair of (instance index, API port), or null if no active instances
     */
    suspend fun getInstanceForDomain(domain: String): Pair<Int, Int>? {
        if (!prefs.stickyRoutingEnabled) {
            return getNextInstance()
        }
        
        val activeInstances = getActiveInstances()
        if (activeInstances.isEmpty()) {
            return null
        }
        
        // Try to get from cache
        val cached = routingCache.getInstanceForDomain(domain)
        if (cached != null) {
            // Verify cached instance is still active
            val (instanceIndex, apiPort) = cached
            if (activeInstances.containsKey(instanceIndex) && activeInstances[instanceIndex] == apiPort) {
                return cached
            } else {
                // Cached instance is no longer active, remove from cache
                Log.d(TAG, "Cached instance $instanceIndex for domain $domain is no longer active, selecting new instance")
            }
        }
        
        // Select new instance based on hash for deterministic assignment
        val instanceList = activeInstances.toList().sortedBy { it.first }
        val instanceIndex = selectInstanceByHash(domain, instanceList.size)
        val selected = instanceList[instanceIndex]
        
        // Cache the selection
        routingCache.setInstanceForDomain(domain, selected.first, selected.second)
        
        Log.d(TAG, "Selected instance ${selected.first} (port ${selected.second}) for domain $domain")
        return selected
    }
    
    /**
     * Get instance for IP using sticky routing.
     * Returns cached instance if available, otherwise selects new instance based on hash.
     * 
     * @param ip IP address
     * @return Pair of (instance index, API port), or null if no active instances
     */
    suspend fun getInstanceForIp(ip: String): Pair<Int, Int>? {
        if (!prefs.stickyRoutingEnabled) {
            return getNextInstance()
        }
        
        val activeInstances = getActiveInstances()
        if (activeInstances.isEmpty()) {
            return null
        }
        
        // Try to get from cache
        val cached = routingCache.getInstanceForIp(ip)
        if (cached != null) {
            // Verify cached instance is still active
            val (instanceIndex, apiPort) = cached
            if (activeInstances.containsKey(instanceIndex) && activeInstances[instanceIndex] == apiPort) {
                return cached
            } else {
                // Cached instance is no longer active, remove from cache
                Log.d(TAG, "Cached instance $instanceIndex for IP $ip is no longer active, selecting new instance")
            }
        }
        
        // Select new instance based on hash for deterministic assignment
        val instanceList = activeInstances.toList().sortedBy { it.first }
        val instanceIndex = selectInstanceByHash(ip, instanceList.size)
        val selected = instanceList[instanceIndex]
        
        // Cache the selection
        routingCache.setInstanceForIp(ip, selected.first, selected.second)
        
        Log.d(TAG, "Selected instance ${selected.first} (port ${selected.second}) for IP $ip")
        return selected
    }
    
    /**
     * Get instance for connection using smart selection based on domain/IP.
     * Prefers domain over IP if both are available.
     * 
     * @param domain Domain name (optional)
     * @param ip IP address (optional)
     * @return Pair of (instance index, API port), or null if no active instances
     */
    suspend fun getInstanceForConnection(domain: String?, ip: String?): Pair<Int, Int>? {
        if (!prefs.stickyRoutingEnabled) {
            return getNextInstance()
        }
        
        // Prefer domain over IP
        if (domain != null && domain.isNotBlank()) {
            return getInstanceForDomain(domain)
        }
        
        if (ip != null && ip.isNotBlank()) {
            return getInstanceForIp(ip)
        }
        
        // Fallback to round-robin if no domain/IP available
        return getNextInstance()
    }
    
    /**
     * Select instance index based on hash of key for deterministic assignment.
     * 
     * @param key Key to hash (domain or IP)
     * @param instanceCount Number of active instances
     * @return Instance index (0-based)
     */
    private fun selectInstanceByHash(key: String, instanceCount: Int): Int {
        val hash = key.hashCode()
        return Math.abs(hash) % instanceCount
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
     * Used when we're already holding the mutex or when called from within a locked block.
     * 
     * CRITICAL: This method must NOT acquire mutex to prevent deadlock.
     * It should only be called:
     * 1. From within a mutex.withLock block
     * 2. From startInstances() before acquiring mutex for new instances
     */
    private suspend fun stopAllInstancesInternal() {
        val instanceCount = instances.size
        if (instanceCount == 0) {
            Log.d(TAG, "No instances to stop")
            AiLogHelper.d(TAG, "✅ XRAY MANAGER STOP: No instances to stop")
            return
        }
        
        Log.i(TAG, "Stopping all $instanceCount instances (internal, mutex-free)")
        AiLogHelper.i(TAG, "🛑 XRAY MANAGER STOP: Stopping all $instanceCount instances")
        
        // Get snapshot of services to stop (no mutex needed - we're already synchronized or called from safe context)
        val servicesToStop = instances.values.toList()
        val portsToStop = instancePorts.values.toList()
        AiLogHelper.d(TAG, "📋 XRAY MANAGER STOP: Instances to stop - ports: $portsToStop")
        
        // Stop services (NO MUTEX HERE - these are IO operations)
        servicesToStop.forEachIndexed { index, service ->
            val instanceStopTime = System.currentTimeMillis()
            try {
                val port = portsToStop.getOrNull(index)
                Log.d(TAG, "Stopping instance $index (port: $port)")
                AiLogHelper.d(TAG, "🔧 XRAY MANAGER STOP: Stopping instance $index (port: $port)...")
                service.stop()
                val instanceStopDuration = System.currentTimeMillis() - instanceStopTime
                AiLogHelper.i(TAG, "✅ XRAY MANAGER STOP: Instance $index stopped (port: $port, duration: ${instanceStopDuration}ms)")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping instance $index", e)
                AiLogHelper.e(TAG, "❌ XRAY MANAGER STOP: Error stopping instance $index: ${e.message}", e)
            }
        }
        
        // Cleanup services (NO MUTEX HERE - these are cleanup operations)
        servicesToStop.forEachIndexed { index, service ->
            try {
                AiLogHelper.d(TAG, "🧹 XRAY MANAGER STOP: Cleaning up instance $index...")
                service.cleanup()
                AiLogHelper.d(TAG, "✅ XRAY MANAGER STOP: Instance $index cleaned up")
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up instance $index", e)
                AiLogHelper.e(TAG, "❌ XRAY MANAGER STOP: Error cleaning up instance $index: ${e.message}", e)
            }
        }
        
        // Clear maps and state (only if we're in a mutex block, otherwise caller should handle)
        // Since this is called from startInstances() before mutex, we need to clear here
        // But we need to be careful - if called from within mutex, we're already safe
        instances.clear()
        instancePorts.clear()
        _instancesStatus.value = emptyMap()
        loadBalancerCounter.set(0)
        
        // Clear routing cache when all instances stop
        routingCache.clear()
        AiLogHelper.d(TAG, "🧹 XRAY MANAGER STOP: Routing cache cleared")
        
        Log.i(TAG, "All $instanceCount instances stopped (internal)")
        AiLogHelper.i(TAG, "✅ XRAY MANAGER STOP: All $instanceCount instances stopped (internal)")
    }
    
    /**
     * Clean up stale routing cache entries.
     * Should be called periodically to remove expired entries.
     */
    suspend fun cleanupRoutingCache() {
        val removedCount = routingCache.removeStaleEntries()
        if (removedCount > 0) {
            Log.d(TAG, "Cleaned up $removedCount stale routing cache entries")
        }
    }
    
    /**
     * Get routing cache statistics.
     * 
     * @return Pair of (domainCacheSize, ipCacheSize)
     */
    fun getRoutingCacheStats(): Pair<Int, Int> {
        return routingCache.getStats()
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

