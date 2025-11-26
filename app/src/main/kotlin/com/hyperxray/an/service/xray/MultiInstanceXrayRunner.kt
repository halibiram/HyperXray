package com.hyperxray.an.service.xray

import android.content.Intent
import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.common.ConfigUtils.extractPortsFromJson
import com.hyperxray.an.core.network.dns.DnsCacheManager
import com.hyperxray.an.core.network.dns.SystemDnsCacheServer
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.service.utils.TProxyUtils
import com.hyperxray.an.xray.runtime.LogLineCallback
import com.hyperxray.an.xray.runtime.MultiXrayCoreManager
import com.hyperxray.an.xray.runtime.XrayRuntimeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/**
 * Multi-instance XrayRunner implementation for multiple Xray-core instances.
 * 
 * This implementation uses MultiXrayCoreManager to run multiple Xray-core
 * instances simultaneously for load balancing and high availability.
 */
class MultiInstanceXrayRunner(
    private val runnerContext: XrayRunnerContext
) : XrayRunner {
    
    private val TAG = "MultiInstanceXrayRunner"
    
    @Volatile
    private var isRunning = false
    
    override suspend fun start(config: XrayConfig) {
        val startTime = System.currentTimeMillis()
        AiLogHelper.i(TAG, "🚀 XRAY START: Beginning Xray process start")
        
        // Prevent duplicate calls
        if (runnerContext.isStopping()) {
            Log.w(TAG, "start() called while stopping, aborting.")
            AiLogHelper.w(TAG, "⚠️ XRAY START: Called while stopping, aborting")
            return
        }
        
        try {
            Log.d(TAG, "Attempting to start xray process(es).")
            AiLogHelper.d(TAG, "🔧 XRAY START: Attempting to start xray process(es)")
            
            // CRITICAL: Read instanceCount directly from Preferences to ensure latest value
            // This ensures settings changes are respected even if config was created with old value
            val instanceCount = runnerContext.prefs.xrayCoreInstanceCount
            Log.i(TAG, "📊 Xray Core Instance Count: $instanceCount (from Preferences, will start ${if (instanceCount == 1) "1 instance" else "$instanceCount instances"})")
            AiLogHelper.i(TAG, "📊 XRAY START: Instance count: $instanceCount (from Preferences, will start ${if (instanceCount == 1) "1 instance" else "$instanceCount instances"})")
            
            // Validate instanceCount matches config (log warning if mismatch, but use Preferences value)
            if (config.instanceCount != instanceCount) {
                Log.w(TAG, "⚠️ Instance count mismatch: config.instanceCount=${config.instanceCount}, Preferences.xrayCoreInstanceCount=$instanceCount. Using Preferences value.")
            }
            
            // Validate config path is within app's private directory
            val selectedConfigPath = runnerContext.prefs.selectedConfigPath
            if (selectedConfigPath == null) {
                val errorMessage = "No configuration file selected."
                Log.e(TAG, errorMessage)
                AiLogHelper.e(TAG, "❌ XRAY START FAILED: $errorMessage")
                val errorIntent = Intent(runnerContext.getActionError())
                errorIntent.setPackage(runnerContext.getApplicationPackageName())
                errorIntent.putExtra(runnerContext.getExtraErrorMessage(), errorMessage)
                runnerContext.sendBroadcast(errorIntent)
                runnerContext.handler.post {
                    if (!runnerContext.isStopping()) {
                        runnerContext.serviceScope.launch {
                            runnerContext.stopXray("No configuration file selected")
                        }
                    }
                }
                return
            }
            AiLogHelper.d(TAG, "✅ XRAY START: Config path found: $selectedConfigPath")
            
            val configFile = TProxyUtils.validateConfigPath(runnerContext.context, selectedConfigPath)
            if (configFile == null) {
                val errorMessage = "Invalid configuration file: path validation failed or file not accessible."
                Log.e(TAG, errorMessage)
                AiLogHelper.e(TAG, "❌ XRAY START FAILED: $errorMessage")
                val errorIntent = Intent(runnerContext.getActionError())
                errorIntent.setPackage(runnerContext.getApplicationPackageName())
                errorIntent.putExtra(runnerContext.getExtraErrorMessage(), errorMessage)
                runnerContext.sendBroadcast(errorIntent)
                runnerContext.handler.post {
                    if (!runnerContext.isStopping()) {
                        runnerContext.serviceScope.launch {
                            runnerContext.stopXray("Invalid configuration file: path validation failed")
                        }
                    }
                }
                return
            }
            AiLogHelper.d(TAG, "✅ XRAY START: Config file validated: ${configFile.name} (${configFile.length()} bytes)")
            
            // Read config content securely (after validation)
            val configReadStartTime = System.currentTimeMillis()
            AiLogHelper.d(TAG, "🔧 XRAY START: Reading config content...")
            val configContent = TProxyUtils.readConfigContentSecurely(configFile)
            val configReadDuration = System.currentTimeMillis() - configReadStartTime
            if (configContent == null) {
                val errorMessage = "Failed to read configuration file."
                Log.e(TAG, errorMessage)
                AiLogHelper.e(TAG, "❌ XRAY START FAILED: $errorMessage")
                val errorIntent = Intent(runnerContext.getActionError())
                errorIntent.setPackage(runnerContext.getApplicationPackageName())
                errorIntent.putExtra(runnerContext.getExtraErrorMessage(), errorMessage)
                runnerContext.sendBroadcast(errorIntent)
                runnerContext.handler.post {
                    if (!runnerContext.isStopping()) {
                        runnerContext.serviceScope.launch {
                            runnerContext.stopXray("Failed to read configuration file")
                        }
                    }
                }
                return
            }
            AiLogHelper.i(TAG, "✅ XRAY START: Config content read successfully (${configContent.length} bytes, duration: ${configReadDuration}ms)")
            
            // Pre-resolve server address BEFORE starting Xray (using system DNS, not VPN DNS)
            // This ensures server IP is available even if only YouTube package exists
            val serverAddress = TProxyUtils.extractServerAddressFromConfig(configContent)
            if (serverAddress != null && !TProxyUtils.isValidIpAddress(serverAddress)) {
                // Server address is a domain name, resolve it using system DNS (not VPN DNS)
                Log.d(TAG, "Pre-resolving server address: $serverAddress (using system DNS before VPN start)")
                try {
                    val resolvedAddresses = withContext(Dispatchers.IO) {
                        // Use system DNS (InetAddress) - works before VPN starts
                        InetAddress.getAllByName(serverAddress)
                    }
                    if (resolvedAddresses.isNotEmpty()) {
                        Log.i(TAG, "✅ Server address resolved: $serverAddress -> ${resolvedAddresses.map { it.hostAddress }}")
                        // Cache resolved IPs in SystemDnsCacheServer if it's already started
                        val systemDnsCacheServer = runnerContext.getSystemDnsCacheServer()
                        if (systemDnsCacheServer != null && systemDnsCacheServer.isRunning()) {
                            try {
                                DnsCacheManager.saveToCache(serverAddress, resolvedAddresses.toList())
                                Log.d(TAG, "💾 Cached server address resolution in DNS cache")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to cache server address: ${e.message}")
                            }
                        }
                    } else {
                        Log.w(TAG, "⚠️ Server address resolved but no IPs found: $serverAddress")
                    }
                } catch (e: Exception) {
                    val errorMessage = "Failed to resolve server address: $serverAddress (${e.message})"
                    Log.w(TAG, errorMessage, e)
                    // Broadcast warning to UI but don't stop - Xray can resolve DNS itself
                    val errorIntent = Intent(runnerContext.getActionError())
                    errorIntent.setPackage(runnerContext.getApplicationPackageName())
                    errorIntent.putExtra(runnerContext.getExtraErrorMessage(), "DNS resolution failed for server. Xray will try to resolve it.")
                    runnerContext.sendBroadcast(errorIntent)
                    // Continue anyway - Xray-core can resolve DNS itself
                }
            } else if (serverAddress != null && TProxyUtils.isValidIpAddress(serverAddress)) {
                Log.d(TAG, "Server address is already an IP: $serverAddress (no DNS resolution needed)")
            }
            
            // Use MultiXrayCoreManager for 1 or more instances (uniform handling)
            if (instanceCount >= 1) {
                val managerStartTime = System.currentTimeMillis()
                Log.i(TAG, "🚀 Starting $instanceCount xray-core instance(s) using MultiXrayCoreManager")
                AiLogHelper.i(TAG, "🚀 XRAY START: Starting $instanceCount xray-core instance(s) using MultiXrayCoreManager")
                Log.d(TAG, "Instance count details: requested=$instanceCount, will start ${if (instanceCount == 1) "1 instance" else "$instanceCount instances"} for ${if (instanceCount == 1) "single instance mode" else "load balancing"}")
                AiLogHelper.d(TAG, "📊 XRAY START: Instance details - requested=$instanceCount, mode=${if (instanceCount == 1) "single instance" else "load balancing"}")
                
                // Initialize MultiXrayCoreManager if not already initialized
                if (runnerContext.multiXrayCoreManager == null) {
                    AiLogHelper.d(TAG, "🔧 XRAY START: Initializing MultiXrayCoreManager...")
                    runnerContext.multiXrayCoreManager = MultiXrayCoreManager.getInstance(runnerContext.context)
                    AiLogHelper.i(TAG, "✅ XRAY START: MultiXrayCoreManager initialized")
                    
                    // Set log callback to forward logs to logFileManager and broadcast
                    // Also intercept DNS queries and cache them (no root required)
                    runnerContext.multiXrayCoreManager?.setLogLineCallback(LogLineCallback { line ->
                        // Write to log file
                        runnerContext.logFileManager.appendLog(line)
                        // Broadcast to UI (lock-free channel)
                        runnerContext.getLogBroadcastChannel()?.trySend(line)
                        
                        // Intercept DNS queries from Xray-core logs and cache them
                        val systemDnsCacheServer = runnerContext.getSystemDnsCacheServer()
                        val newDnsCacheInitialized = TProxyUtils.interceptDnsFromXrayLogs(
                            context = runnerContext.context,
                            logLine = line,
                            dnsCacheInitialized = runnerContext.isDnsCacheInitialized(),
                            systemDnsCacheServer = systemDnsCacheServer,
                            serviceScope = runnerContext.serviceScope
                        )
                        if (newDnsCacheInitialized) {
                            runnerContext.setDnsCacheInitialized(true)
                        }
                        
                        // Extract domain/IP for sticky routing
                        val multiXrayCoreManager = runnerContext.multiXrayCoreManager
                        if (multiXrayCoreManager != null) {
                            TProxyUtils.processStickyRoutingFromLog(
                                logLine = line,
                                prefs = runnerContext.prefs,
                                multiXrayCoreManager = multiXrayCoreManager,
                                serviceScope = runnerContext.serviceScope,
                                isActive = runnerContext.serviceScope.coroutineContext.isActive
                            )
                        }
                    })
                    
                    // Observe instance status changes and broadcast to MainViewModel
                    runnerContext.serviceScope.launch {
                        runnerContext.multiXrayCoreManager?.instancesStatus?.collect { statusMap ->
                            // Broadcast instance status update whenever status changes
                            val statusIntent = Intent(runnerContext.getActionInstanceStatusUpdate())
                            statusIntent.setPackage(runnerContext.getApplicationPackageName())
                            statusIntent.putExtra("instance_count", statusMap.size)
                            statusIntent.putExtra("has_running", statusMap.values.any { it is XrayRuntimeStatus.Running })
                            
                            // Add PID, port, and status type info for each instance
                            statusMap.forEach { (index, status) ->
                                when (status) {
                                    is XrayRuntimeStatus.Running -> {
                                        statusIntent.putExtra("instance_${index}_pid", status.processId)
                                        statusIntent.putExtra("instance_${index}_port", status.apiPort)
                                        statusIntent.putExtra("instance_${index}_status_type", "Running")
                                    }
                                    is XrayRuntimeStatus.Starting -> {
                                        statusIntent.putExtra("instance_${index}_pid", 0L)
                                        statusIntent.putExtra("instance_${index}_port", 0)
                                        statusIntent.putExtra("instance_${index}_status_type", "Starting")
                                    }
                                    is XrayRuntimeStatus.Stopping -> {
                                        statusIntent.putExtra("instance_${index}_pid", 0L)
                                        statusIntent.putExtra("instance_${index}_port", 0)
                                        statusIntent.putExtra("instance_${index}_status_type", "Stopping")
                                    }
                                    is XrayRuntimeStatus.Error -> {
                                        statusIntent.putExtra("instance_${index}_pid", 0L)
                                        statusIntent.putExtra("instance_${index}_port", 0)
                                        statusIntent.putExtra("instance_${index}_status_type", "Error")
                                        statusIntent.putExtra("instance_${index}_error_message", status.message)
                                    }
                                    is XrayRuntimeStatus.ProcessExited -> {
                                        statusIntent.putExtra("instance_${index}_pid", 0L)
                                        statusIntent.putExtra("instance_${index}_port", 0)
                                        statusIntent.putExtra("instance_${index}_status_type", "ProcessExited")
                                        statusIntent.putExtra("instance_${index}_exit_code", status.exitCode)
                                        status.message?.let { 
                                            statusIntent.putExtra("instance_${index}_exit_message", it)
                                        }
                                    }
                                    is XrayRuntimeStatus.Stopped -> {
                                        statusIntent.putExtra("instance_${index}_pid", 0L)
                                        statusIntent.putExtra("instance_${index}_port", 0)
                                        statusIntent.putExtra("instance_${index}_status_type", "Stopped")
                                    }
                                }
                            }
                            
                            runnerContext.sendBroadcast(statusIntent)
                            
                            // Log detailed instance status for debugging
                            val runningCount = statusMap.values.count { it is XrayRuntimeStatus.Running }
                            val startingCount = statusMap.values.count { it is XrayRuntimeStatus.Starting }
                            val errorCount = statusMap.values.count { it is XrayRuntimeStatus.Error }
                            val stoppedCount = statusMap.values.count { it is XrayRuntimeStatus.Stopped }
                            
                            Log.d(TAG, "Broadcasted instance status update: ${statusMap.size} total instances " +
                                "(Running: $runningCount, Starting: $startingCount, Error: $errorCount, Stopped: $stoppedCount)")
                        }
                    }
                }
                
                val excludedPorts = extractPortsFromJson(configContent)
                Log.i(TAG, "🔧 Starting $instanceCount xray-core instances with excluded ports: $excludedPorts")
                Log.d(TAG, "Instance startup parameters: count=$instanceCount, configPath=$selectedConfigPath, excludedPortsCount=${excludedPorts.size}")
                
                // Check service lifecycle before starting instances
                if (runnerContext.isStopping()) {
                    Log.w(TAG, "⚠️ Service is stopping, aborting instance startup")
                    return
                }
                
                Log.i(TAG, "▶️ Calling startInstances() with count=$instanceCount, configPath=$selectedConfigPath")
                val startedInstances = try {
                    val manager = runnerContext.multiXrayCoreManager
                    if (manager == null) {
                        Log.e(TAG, "❌ MultiXrayCoreManager is null, cannot start instances")
                        AiLogHelper.e(TAG, "❌ XRAY START FAILED: MultiXrayCoreManager is null")
                        return
                    }
                    val result = manager.startInstances(
                        count = instanceCount,
                        configPath = selectedConfigPath,
                        configContent = configContent,
                        excludedPorts = excludedPorts
                    )
                    val completedCount = result.size
                    Log.i(TAG, "✅ startInstances() completed: requested=$instanceCount, started=$completedCount instances")
                    if (completedCount < instanceCount) {
                        Log.w(TAG, "⚠️ Warning: Only $completedCount out of $instanceCount instances started successfully")
                    }
                    result
                } catch (e: CancellationException) {
                    Log.w(TAG, "🛑 Instance startup cancelled (expected during shutdown)", e)
                    emptyMap()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Exception during startInstances() call", e)
                    emptyMap()
                }
                
                // Check service lifecycle after starting instances
                if (runnerContext.isStopping()) {
                    Log.w(TAG, "⚠️ Service is stopping after instance startup, cleaning up")
                    if (startedInstances.isNotEmpty()) {
                        runnerContext.serviceScope.launch {
                            runnerContext.multiXrayCoreManager?.stopAllInstances()
                        }
                    }
                    return
                }
                
                Log.i(TAG, "📋 startInstances() result: ${startedInstances.size} instances started successfully out of $instanceCount requested")
                if (startedInstances.isNotEmpty()) {
                    Log.d(TAG, "Instance details: $startedInstances")
                } else {
                    Log.e(TAG, "❌ No instances started! Check logs above for errors.")
                }
                
                // Check service lifecycle again before proceeding
                if (runnerContext.isStopping()) {
                    Log.w(TAG, "Service is stopping after instances started, cleaning up")
                    if (startedInstances.isNotEmpty()) {
                        runnerContext.serviceScope.launch {
                            runnerContext.multiXrayCoreManager?.stopAllInstances()
                        }
                    }
                    return
                }
                
                // Immediately broadcast initial status after instances start
                val manager = runnerContext.multiXrayCoreManager
                if (manager == null) {
                    Log.e(TAG, "❌ MultiXrayCoreManager is null, cannot get instance status")
                    return
                }
                val initialStatus = manager.instancesStatus.value
                Log.d(TAG, "Initial instance status: ${initialStatus.size} instances registered")
                initialStatus.forEach { (index, status) ->
                    Log.d(TAG, "Instance $index status: $status")
                }
                if (initialStatus.isNotEmpty()) {
                    val statusIntent = Intent(runnerContext.getActionInstanceStatusUpdate())
                    statusIntent.setPackage(runnerContext.getApplicationPackageName())
                    statusIntent.putExtra("instance_count", initialStatus.size)
                    statusIntent.putExtra("has_running", initialStatus.values.any { it is XrayRuntimeStatus.Running })
                    
                    // Add PID and port info for each instance
                    initialStatus.forEach { (index, status) ->
                        when (status) {
                            is XrayRuntimeStatus.Running -> {
                                statusIntent.putExtra("instance_${index}_pid", status.processId)
                                statusIntent.putExtra("instance_${index}_port", status.apiPort)
                            }
                            else -> {
                                // For non-running statuses, set pid and port to 0
                                statusIntent.putExtra("instance_${index}_pid", 0L)
                                statusIntent.putExtra("instance_${index}_port", 0)
                            }
                        }
                    }
                    
                    runnerContext.sendBroadcast(statusIntent)
                    Log.d(TAG, "Broadcasted initial instance status: ${initialStatus.size} instances")
                }
                
                if (startedInstances.isEmpty()) {
                    val statusDetails = initialStatus.map { (index, status) -> 
                        "Instance $index: $status"
                    }.joinToString(", ")
                    val errorMessage = "Failed to start any xray-core instances. Attempted: $instanceCount, Started: 0. Status details: $statusDetails"
                    Log.e(TAG, errorMessage)
                    val errorIntent = Intent(runnerContext.getActionError())
                    errorIntent.setPackage(runnerContext.getApplicationPackageName())
                    errorIntent.putExtra(runnerContext.getExtraErrorMessage(), "Failed to start any xray-core instances. Check logs for details.")
                    runnerContext.sendBroadcast(errorIntent)
                    // CRITICAL: Stop service to prevent "Connecting..." stuck state
                    runnerContext.handler.post {
                        if (!runnerContext.isStopping()) {
                            runnerContext.serviceScope.launch {
                                runnerContext.stopXray("Failed to start any xray-core instances. Attempted: $instanceCount, Started: 0")
                            }
                        }
                    }
                    // Explicitly throw exception to prevent continuation
                    throw IllegalStateException("startInstances() returned empty map. No instances started successfully.")
                }
                
                Log.i(TAG, "Successfully started ${startedInstances.size} out of $instanceCount xray-core instances")
                
                // Set the first instance's port as the primary API port for backward compatibility
                val firstPort = startedInstances.values.firstOrNull()
                if (firstPort != null) {
                    runnerContext.prefs.apiPort = firstPort
                    Log.d(TAG, "Set primary API port to $firstPort (from ${startedInstances.size} instances)")
                }
                
                // Start routing cache cleanup for sticky routing
                if (startedInstances.isNotEmpty() && runnerContext.prefs.stickyRoutingEnabled) {
                    TProxyUtils.startRoutingCacheCleanup(
                        prefs = runnerContext.prefs,
                        multiXrayCoreManager = runnerContext.multiXrayCoreManager,
                        isStopping = runnerContext.isStopping(),
                        serviceScope = runnerContext.serviceScope,
                        isActive = runnerContext.serviceScope.coroutineContext.isActive
                    )
                }
                
                // Start SOCKS5 readiness check immediately after first instance starts
                runnerContext.setSocks5ReadinessChecked(false)
                runnerContext.serviceScope.launch {
                    // Check if any instance is already Running (startInstances may have returned early)
                    val manager = runnerContext.multiXrayCoreManager
                    if (manager != null) {
                        // CRITICAL: Use StateFlow collector to get real-time status updates
                        var hasFoundRunning = false
                        
                        // First, check current status (might already be Running)
                        val initialStatuses = manager.instancesStatus.value
                        val hasRunningInstance = initialStatuses.values.any { it is XrayRuntimeStatus.Running }
                        
                        if (hasRunningInstance) {
                            Log.d(TAG, "At least one instance is already Running, starting SOCKS5 readiness check immediately")
                            runnerContext.checkSocks5Readiness(runnerContext.prefs)
                            hasFoundRunning = true
                        }
                        
                        // If not found immediately, wait for status update using StateFlow collector
                        if (!hasFoundRunning && !runnerContext.isStopping()) {
                            // Use StateFlow first() with timeout to get the first Running status
                            try {
                                val runningStatus = withTimeoutOrNull(5000) { // 5 second timeout
                                    manager.instancesStatus.first { statusMap ->
                                        statusMap.values.any { it is XrayRuntimeStatus.Running }
                                    }
                                }
                                
                                if (runningStatus != null && !hasFoundRunning) {
                                    Log.d(TAG, "First instance is Running (detected via StateFlow), starting SOCKS5 readiness check")
                                    runnerContext.checkSocks5Readiness(runnerContext.prefs)
                                    hasFoundRunning = true
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error waiting for Running status: ${e.message}")
                            }
                            
                            // Final check if we didn't find Running instance via StateFlow
                            if (!hasFoundRunning && !runnerContext.isStopping()) {
                                val finalStatuses = manager.instancesStatus.value
                                val hasRunning = finalStatuses.values.any { it is XrayRuntimeStatus.Running }
                                
                                if (hasRunning) {
                                    Log.d(TAG, "First instance is Running (final check), starting SOCKS5 readiness check")
                                    runnerContext.checkSocks5Readiness(runnerContext.prefs)
                                    hasFoundRunning = true
                                } else {
                                    Log.w(TAG, "SOCKS5 readiness check not triggered - no instances reached Running state within timeout")
                                }
                            }
                        }
                        
                        // Start periodic SOCKS5 health check after initial check
                        if (runnerContext.isSocks5ReadinessChecked()) {
                            val systemDnsCacheServer = runnerContext.getSystemDnsCacheServer()
                            TProxyUtils.startPeriodicSocks5HealthCheck(
                                context = runnerContext.context,
                                prefs = runnerContext.prefs,
                                isStopping = runnerContext.isStopping(),
                                socks5ReadinessChecked = runnerContext.isSocks5ReadinessChecked(),
                                systemDnsCacheServer = systemDnsCacheServer,
                                serviceScope = runnerContext.serviceScope,
                                isActive = runnerContext.serviceScope.coroutineContext.isActive,
                                onReadinessChanged = { checked ->
                                    runnerContext.setSocks5ReadinessChecked(checked)
                                },
                                checkSocks5Readiness = { prefs -> runnerContext.checkSocks5Readiness(prefs) }
                            )
                        }
                        
                        // Start UDP monitoring
                        // Note: UDP monitoring job management is handled by TProxyService
                        // This is just a placeholder - actual implementation should be in TProxyService
                    }
                }
                
                isRunning = true
            } else {
                // This should not happen - instanceCount should be >= 1
                val errorMessage = "Invalid instance count: $instanceCount (must be >= 1)"
                Log.e(TAG, errorMessage)
                val errorIntent = Intent(runnerContext.getActionError())
                errorIntent.setPackage(runnerContext.getApplicationPackageName())
                errorIntent.putExtra(runnerContext.getExtraErrorMessage(), errorMessage)
                runnerContext.sendBroadcast(errorIntent)
                runnerContext.handler.post {
                    if (!runnerContext.isStopping()) {
                        runnerContext.serviceScope.launch {
                            runnerContext.stopXray("Invalid instance count: $instanceCount")
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // Expected cancellation during shutdown - don't log as error
            Log.d(TAG, "Xray process startup cancelled (expected during shutdown)", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error starting xray process(es)", e)
            val errorIntent = Intent(runnerContext.getActionError())
            errorIntent.setPackage(runnerContext.getApplicationPackageName())
            errorIntent.putExtra(runnerContext.getExtraErrorMessage(), "Failed to start: ${e.message}")
            runnerContext.sendBroadcast(errorIntent)
            // Only stop if it's a critical error and we're not already stopping
            val hasRunningInstances = runnerContext.multiXrayCoreManager?.hasRunningInstances() == true
            if (!hasRunningInstances && !runnerContext.isStopping()) {
                Log.w(TAG, "No running instances found after error, stopping service")
                runnerContext.handler.post {
                    if (!runnerContext.isStopping()) {
                        runnerContext.serviceScope.launch {
                            runnerContext.stopXray("Critical error during startup: ${e.message}")
                        }
                    }
                }
            } else if (hasRunningInstances) {
                Log.i(TAG, "Some instances are running despite error, continuing operation")
            }
        }
    }
    
    override suspend fun stop() {
        val stopTime = System.currentTimeMillis()
        AiLogHelper.i(TAG, "🛑 XRAY STOP: Stopping Xray instances")
        isRunning = false
        val manager = runnerContext.multiXrayCoreManager
        if (manager != null) {
            try {
                Log.d(TAG, "Stopping MultiXrayCoreManager instances...")
                AiLogHelper.d(TAG, "🔧 XRAY STOP: Stopping MultiXrayCoreManager instances...")
                manager.stopAllInstances()
                val stopDuration = System.currentTimeMillis() - stopTime
                Log.d(TAG, "MultiXrayCoreManager instances stopped.")
                AiLogHelper.i(TAG, "✅ XRAY STOP SUCCESS: MultiXrayCoreManager instances stopped (duration: ${stopDuration}ms)")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MultiXrayCoreManager instances", e)
                AiLogHelper.e(TAG, "❌ XRAY STOP ERROR: Error stopping MultiXrayCoreManager instances: ${e.message}", e)
            }
        } else {
            AiLogHelper.w(TAG, "⚠️ XRAY STOP: MultiXrayCoreManager is null, nothing to stop")
        }
    }
    
    override fun isRunning(): Boolean {
        return isRunning && (runnerContext.multiXrayCoreManager?.hasRunningInstances() == true)
    }
}


