package com.hyperxray.an.service.managers

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.service.TProxyService
import com.hyperxray.an.service.utils.TProxyUtils
import com.hyperxray.an.common.Socks5ReadinessChecker
import com.hyperxray.an.telemetry.TProxyAiOptimizer
import com.hyperxray.an.viewmodel.CoreStatsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages native TProxy service (hev-socks5-tunnel) lifecycle.
 * Handles TProxy configuration file generation, native JNI calls, and statistics collection.
 * 
 * This manager encapsulates all logic related to hev-socks5-tunnel, including:
 * - Finding and loading the native library
 * - Building command arguments and configuration
 * - Starting and stopping the service
 * - Process lifecycle management
 */
class HevSocksManager(private val context: Context) {
    private val isRunningRef = AtomicBoolean(false)
    private val configFile = File(context.cacheDir, "tproxy.conf")
    private val currentTunFd = AtomicReference<ParcelFileDescriptor?>(null)
    private val currentPrefs = AtomicReference<Preferences?>(null)
    
    // Thread-safe file descriptor handling
    private val tunFdLock = Any()
    
    companion object {
        private const val TAG = "HevSocksManager"
        
        init {
            try {
                System.loadLibrary("hev-socks5-tunnel")
            } catch (e: UnsatisfiedLinkError) {
                // In test environment, native library may not be available
                // This is expected and will be handled gracefully
                if (!isTestEnvironment()) {
                    throw e
                }
            }
        }
        
        /**
         * Check if running in test environment.
         * This helps avoid native library loading errors in unit tests.
         */
        private fun isTestEnvironment(): Boolean {
            return try {
                Class.forName("org.junit.Test")
                true
            } catch (e: ClassNotFoundException) {
                false
            }
        }
        
        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyStartService(configPath: String, fd: Int)
        
        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyStopService()
        
        /**
         * Get native TProxy statistics from hev-socks5-tunnel.
         * Returns: [txPackets, txBytes, rxPackets, rxBytes] or null on error.
         * 
         * JNI maps this Java method name "TProxyGetStats" to native_get_stats C function.
         */
        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyGetStats(): LongArray?
        
        /**
         * Notify native tunnel of UDP error for coordination.
         * This allows native tunnel to pause UDP packet sending temporarily.
         * 
         * @param errorType 0=closed pipe, 1=timeout, 2=other
         * @return true if notification was successful, false otherwise
         */
        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyNotifyUdpError(errorType: Int): Boolean
        
        /**
         * Notify native tunnel that UDP recovery is complete.
         * This allows native tunnel to resume normal UDP operations.
         * 
         * @return true if notification was successful, false otherwise
         */
        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyNotifyUdpRecoveryComplete(): Boolean
        
        /**
         * Notify native tunnel of imminent UDP cleanup.
         * This helps native tunnel prepare and reduce race conditions.
         * 
         * @return true if notification was successful, false otherwise
         */
        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyNotifyImminentUdpCleanup(): Boolean
    }
    
    /**
     * Start HevSocks service with specified SOCKS5 port.
     * 
     * This method requires that TUN file descriptor and preferences have been set
     * via setTunFd() and setPreferences() before calling start().
     * 
     * @param port SOCKS5 port to use
     * @return true if started successfully, false otherwise
     */
    fun start(port: Int): Boolean {
        val tunFd = currentTunFd.get()
        val prefs = currentPrefs.get()
        
        if (tunFd == null) {
            Log.e(TAG, "Cannot start HevSocks: TUN file descriptor not set. Call setTunFd() first.")
            return false
        }
        
        if (prefs == null) {
            Log.e(TAG, "Cannot start HevSocks: Preferences not set. Call setPreferences() first.")
            return false
        }
        
        // Update SOCKS5 port in preferences if different
        if (prefs.socksPort != port) {
            prefs.socksPort = port
            Log.d(TAG, "Updated SOCKS5 port to $port")
        }
        
        return startTProxy(tunFd, prefs)
    }
    
    /**
     * Stop HevSocks service.
     */
    fun stop() {
        stopTProxy()
    }
    
    /**
     * Start native TProxy service with full lifecycle management.
     * This method handles the complete startup sequence including:
     * - Config file validation
     * - TUN file descriptor extraction (with thread safety)
     * - Native service startup
     * - AI optimizer integration
     * 
     * @param tunInterfaceManager Manager for TUN interface operations
     * @param isStoppingCallback Callback to check if service is stopping
     * @param stopXrayCallback Callback to stop Xray if needed
     * @param serviceScope Coroutine scope for async operations
     * @param tproxyAiOptimizer Optional AI optimizer for TProxy
     * @param coreStatsState Optional core stats state for optimizer
     * @param lastTProxyRestartTime Reference to last restart time (for throttling)
     * @param onStartComplete Optional callback when start completes
     * @param notificationManager Optional notification manager
     * @return true if started successfully, false otherwise
     */
    suspend fun startNativeTProxy(
        tunInterfaceManager: TunInterfaceManager,
        isStoppingCallback: () -> Boolean,
        stopXrayCallback: (String) -> Unit,
        serviceScope: CoroutineScope,
        tproxyAiOptimizer: TProxyAiOptimizer? = null,
        coreStatsState: CoreStatsState? = null,
        lastTProxyRestartTime: () -> Long,
        setLastTProxyRestartTime: (Long) -> Unit,
        onStartComplete: (() -> Unit)? = null,
        notificationManager: Any? = null
    ): Boolean {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Starting native TProxy service...")
        AiLogHelper.i(TAG, "🚀 TPROXY START: Starting native TProxy service...")
        
        val tproxyFile = File(context.cacheDir, "tproxy.conf")
        if (!tproxyFile.exists()) {
            val errorMsg = "TProxy config file not found in startNativeTProxy"
            Log.e(TAG, errorMsg)
            AiLogHelper.e(TAG, "❌ TPROXY START FAILED: $errorMsg")
            stopXrayCallback("TProxy config file not found")
            return false
        }
        AiLogHelper.d(TAG, "✅ TPROXY START: Config file found: ${tproxyFile.absolutePath} (${tproxyFile.length()} bytes)")

        // Safely get fd using TunInterfaceManager with thread safety
        // Check if we're stopping (defensive check)
        if (isStoppingCallback()) {
            val errorMsg = "Service is stopping, cannot start TProxy"
            Log.w(TAG, errorMsg)
            AiLogHelper.w(TAG, "⚠️ TPROXY START: $errorMsg")
            stopXrayCallback("Service is stopping")
            return false
        }

        val fd = synchronized(tunFdLock) {
            tunInterfaceManager.getTunFdInt()
        }
        
        if (fd == null) {
            val errorMsg = "TUN file descriptor is null or invalid in startNativeTProxy"
            Log.e(TAG, errorMsg)
            AiLogHelper.e(TAG, "❌ TPROXY START FAILED: $errorMsg")
            stopXrayCallback("TUN file descriptor is null or invalid in startNativeTProxy")
            return false
        }
        AiLogHelper.d(TAG, "✅ TPROXY START: TUN file descriptor obtained: fd=$fd")

        // Use fd immediately after extraction (minimize race window)
        val nativeStartTime = System.currentTimeMillis()
        AiLogHelper.d(TAG, "🔧 TPROXY START: Calling native TProxyStartService...")
        AiLogHelper.d(TAG, "📋 TPROXY START: Native call parameters - config: ${tproxyFile.absolutePath}, fd: $fd, config size: ${tproxyFile.length()} bytes")
        try {
            synchronized(tunFdLock) {
                com.hyperxray.an.service.TProxyService.TProxyStartService(tproxyFile.absolutePath, fd)
            }
            val nativeStartDuration = System.currentTimeMillis() - nativeStartTime
            isRunningRef.set(true)
            val totalDuration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Native TProxy service started.")
            AiLogHelper.i(TAG, "✅ TPROXY START SUCCESS: Native TProxy service started (native call: ${nativeStartDuration}ms, total: ${totalDuration}ms)")
        } catch (e: Exception) {
            val nativeStartDuration = System.currentTimeMillis() - nativeStartTime
            val totalDuration = System.currentTimeMillis() - startTime
            Log.e(TAG, "Error calling native TProxyStartService", e)
            AiLogHelper.e(TAG, "❌ TPROXY START FAILED: Error calling native TProxyStartService (native call: ${nativeStartDuration}ms, total: ${totalDuration}ms): ${e.message}", e)
            throw e
        }

        // Start AI-powered TProxy optimization
        val optimizer = tproxyAiOptimizer
        if (optimizer != null && !optimizer.isOptimizing()) {
            Log.i(TAG, "Starting AI-powered TProxy optimization")
            
            // Set callback to reload TProxy when configuration changes
            optimizer.onConfigurationApplied = { config, needsReload ->
                if (needsReload) {
                    // Check if enough time has passed since last restart (6 hours minimum)
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastRestart = currentTime - lastTProxyRestartTime()
                    val TPROXY_RESTART_INTERVAL_MS = 6L * 60L * 60L * 1000L // 6 hours
                    
                    if (timeSinceLastRestart >= TPROXY_RESTART_INTERVAL_MS) {
                        Log.i(TAG, "AI optimizer applied new configuration, reloading TProxy...")
                        // Reload TProxy configuration by recreating the config file and restarting
                        serviceScope.launch {
                            try {
                                // Check if we're stopping before proceeding
                                if (isStoppingCallback()) {
                                    Log.w(TAG, "Skipping TProxy reload - service is stopping")
                                    return@launch
                                }
                                
                                val tproxyFile = File(context.cacheDir, "tproxy.conf")
                                if (!tproxyFile.exists()) {
                                    Log.w(TAG, "TProxy config file does not exist, skipping reload")
                                    return@launch
                                }
                                
                                // Update config file first (async I/O to avoid blocking)
                                try {
                                    withContext(Dispatchers.IO) {
                                        FileOutputStream(tproxyFile, false).use { fos ->
                                            val tproxyConf = TProxyUtils.getTproxyConf(Preferences(context))
                                            fos.write(tproxyConf.toByteArray())
                                            fos.flush()
                                        }
                                    }
                                    Log.i(TAG, "TProxy configuration file updated with AI-optimized settings")
                                } catch (e: IOException) {
                                    Log.e(TAG, "Error writing TProxy config file: ${e.message}", e)
                                    return@launch
                                }
                                
                                // Check again if we're stopping before restarting TProxy
                                if (isStoppingCallback()) {
                                    Log.w(TAG, "Service stopping detected before TProxy restart, aborting")
                                    return@launch
                                }
                                
                                // Restart TProxy service to apply new configuration
                                // This is necessary because hev-socks5-tunnel reads config at startup
                                try {
                                    Log.i(TAG, "Restarting TProxy service to apply AI-optimized configuration...")
                                    synchronized(tunFdLock) {
                                        com.hyperxray.an.service.TProxyService.TProxyStopService()
                                    }
                                    Thread.sleep(100) // Brief delay to ensure clean shutdown
                                    
                                    // Check if we're stopping before restarting TProxy
                                    if (isStoppingCallback()) {
                                        Log.w(TAG, "Service stopping detected, cannot restart TProxy")
                                        return@launch
                                    }

                                    // Get fd using TunInterfaceManager
                                    val fd = synchronized(tunFdLock) {
                                        tunInterfaceManager.getTunFdInt()
                                    }
                                    if (fd == null) {
                                        Log.w(TAG, "Could not get valid file descriptor, skipping TProxy restart")
                                        return@launch
                                    }
                                    
                                    // Use fd immediately after getting it (minimize time between extraction and use)
                                    synchronized(tunFdLock) {
                                        com.hyperxray.an.service.TProxyService.TProxyStartService(tproxyFile.absolutePath, fd)
                                    }
                                    // Update last restart time after successful restart
                                    setLastTProxyRestartTime(System.currentTimeMillis())
                                    Log.i(TAG, "TProxy service restarted with AI-optimized configuration")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error restarting TProxy service: ${e.message}", e)
                                }
                                
                            } catch (e: Exception) {
                                Log.e(TAG, "Error updating TProxy configuration", e)
                            }
                        }
                    } else {
                        val remainingHours = (TPROXY_RESTART_INTERVAL_MS - timeSinceLastRestart) / (60L * 60L * 1000L)
                        Log.i(TAG, "TProxy restart throttled: Only ${remainingHours}h since last restart (minimum 6h). " +
                                "Skipping restart for now. Config updated but restart deferred.")
                    }
                }
            }
            
            optimizer.startOptimization(
                coreStatsState = coreStatsState,
                optimizationIntervalMs = 30000L // Optimize every 30 seconds
            )
        }

        // Call completion callback
        onStartComplete?.invoke()
        
        return true
    }
    
    /**
     * Stop native TProxy service with proper cleanup.
     * This method handles graceful shutdown with UDP cleanup delays.
     * 
     * @param waitForUdpCleanup Whether to wait for UDP cleanup (default: true)
     * @param udpCleanupDelayMs Delay in milliseconds for UDP cleanup (default: 1000ms)
     */
    fun stopNativeTProxy(
        waitForUdpCleanup: Boolean = true,
        udpCleanupDelayMs: Long = 1000L
    ) {
        val stopTime = System.currentTimeMillis()
        AiLogHelper.i(TAG, "🛑 TPROXY STOP: Stopping native TProxy service (waitForUdpCleanup=$waitForUdpCleanup, delay=${udpCleanupDelayMs}ms)")
        
        try {
            val nativeStopTime = System.currentTimeMillis()
            AiLogHelper.d(TAG, "🔧 TPROXY STOP: Calling native TProxyStopService...")
            synchronized(tunFdLock) {
                com.hyperxray.an.service.TProxyService.TProxyStopService()
            }
            val nativeStopDuration = System.currentTimeMillis() - nativeStopTime
            AiLogHelper.d(TAG, "✅ TPROXY STOP: Native TProxyStopService completed (duration: ${nativeStopDuration}ms)")
            
            if (waitForUdpCleanup) {
                // Give native tunnel time to clean up UDP sessions and close sockets gracefully
                // This prevents race conditions where UDP packets are written to closed pipes
                // UDP cleanup can take time, especially with active connections
                AiLogHelper.d(TAG, "⏳ TPROXY STOP: Waiting for UDP cleanup (${udpCleanupDelayMs}ms)...")
                try {
                    Thread.sleep(udpCleanupDelayMs)
                    AiLogHelper.d(TAG, "✅ TPROXY STOP: UDP cleanup wait completed")
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    AiLogHelper.w(TAG, "⚠️ TPROXY STOP: UDP cleanup wait interrupted")
                }
            }
            
            isRunningRef.set(false)
            val totalDuration = System.currentTimeMillis() - stopTime
            Log.d(TAG, "Native TProxy service stopped.")
            AiLogHelper.i(TAG, "✅ TPROXY STOP SUCCESS: Native TProxy service stopped (total duration: ${totalDuration}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping native TProxy service", e)
            AiLogHelper.e(TAG, "❌ TPROXY STOP ERROR: Error stopping native TProxy service: ${e.message}", e)
            isRunningRef.set(false)
        }
    }
    
    /**
     * Check SOCKS5 readiness and start TProxy if ready.
     * This method coordinates with TProxyUtils.checkSocks5Readiness.
     * 
     * @param prefs Preferences for configuration
     * @param serviceScope Coroutine scope for async operations
     * @param socks5ReadinessChecked Current readiness state
     * @param onSocks5StatusChanged Callback when readiness status changes
     * @param systemDnsCacheServer Optional DNS cache server
     * @param startNativeTProxyCallback Callback to start native TProxy
     */
    suspend fun checkSocks5Readiness(
        prefs: Preferences,
        serviceScope: CoroutineScope,
        socks5ReadinessChecked: Boolean,
        onSocks5StatusChanged: (Boolean, Boolean) -> Unit,
        systemDnsCacheServer: Any?,
        startNativeTProxyCallback: suspend () -> Unit
    ) {
        Log.i(TAG, "Checking SOCKS5 readiness on ${prefs.socksAddress}:${prefs.socksPort} (current checked state: $socks5ReadinessChecked)")
        
        // Use extended timeout (30 seconds) to allow Xray process more time to start SOCKS5 server
        // This is especially important on slower devices or when Xray is starting multiple instances
        val isReady = Socks5ReadinessChecker.waitUntilSocksReady(
            context = context,
            address = prefs.socksAddress,
            port = prefs.socksPort,
            maxWaitTimeMs = 30000L, // 30 seconds timeout (increased from 20s)
            retryIntervalMs = 500L // Check every 500ms
        )
        
        if (isReady) {
            Log.i(TAG, "✅ SOCKS5 is ready on ${prefs.socksAddress}:${prefs.socksPort}")
            if (!socks5ReadinessChecked) {
                Log.d(TAG, "SOCKS5 became ready, updating status and starting native TProxy")
                onSocks5StatusChanged(true, false)
                // Set DNS cache server if available
                if (systemDnsCacheServer is com.hyperxray.an.core.network.dns.SystemDnsCacheServer) {
                    try {
                        systemDnsCacheServer.setSocks5Proxy(prefs.socksAddress, prefs.socksPort)
                        Log.d(TAG, "DNS cache server configured with SOCKS5 proxy")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error setting SOCKS5 proxy for DNS cache server: ${e.message}")
                    }
                }
                // Start native TProxy when SOCKS5 is ready
                try {
                    startNativeTProxyCallback()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Native TProxy start cancelled (service stopping)")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting native TProxy after SOCKS5 became ready: ${e.message}", e)
                    // Don't throw - allow retry on next check
                }
            } else {
                Log.d(TAG, "SOCKS5 is ready but was already checked, skipping status update")
            }
        } else {
            Log.w(TAG, "❌ SOCKS5 did not become ready within timeout on ${prefs.socksAddress}:${prefs.socksPort}")
            Log.w(TAG, "Possible causes:")
            Log.w(TAG, "  1. Xray process may not have started SOCKS5 server yet")
            Log.w(TAG, "  2. SOCKS5 port may be configured incorrectly")
            Log.w(TAG, "  3. Xray process may have crashed or failed to start")
            
            if (socks5ReadinessChecked) {
                Log.d(TAG, "SOCKS5 was previously ready but is now not ready, updating status")
                onSocks5StatusChanged(false, true)
            } else {
                Log.w(TAG, "SOCKS5 never became ready - Xray process may not have started SOCKS5 server")
            }
        }
    }
    
    /**
     * Set TUN file descriptor for use with start(port).
     * 
     * @param tunFd TUN FileDescriptor
     */
    fun setTunFd(tunFd: ParcelFileDescriptor?) {
        currentTunFd.set(tunFd)
    }
    
    /**
     * Set preferences for use with start(port).
     * 
     * @param prefs Preferences for TProxy configuration
     */
    fun setPreferences(prefs: Preferences) {
        currentPrefs.set(prefs)
    }
    
    /**
     * Start TProxy service (legacy method, kept for backward compatibility).
     * 
     * @param tunFd TUN FileDescriptor
     * @param prefs Preferences for TProxy configuration
     * @return true if started successfully, false otherwise
     */
    fun startTProxy(tunFd: ParcelFileDescriptor, prefs: Preferences): Boolean {
        try {
            // Store references for use with start(port)
            currentTunFd.set(tunFd)
            currentPrefs.set(prefs)
            
            // Create config file if it doesn't exist
            if (!configFile.exists()) {
                if (!createConfigFile(prefs)) {
                    Log.e(TAG, "Failed to create TProxy config file")
                    return false
                }
            }
            
            val fd = try {
                tunFd.fd
            } catch (e: Exception) {
                Log.e(TAG, "Error accessing tunFd.fd: ${e.message}", e)
                return false
            }
            
            synchronized(tunFdLock) {
                com.hyperxray.an.service.TProxyService.TProxyStartService(configFile.absolutePath, fd)
            }
            isRunningRef.set(true)
            Log.d(TAG, "Native TProxy service started.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting TProxy service: ${e.message}", e)
            isRunningRef.set(false)
            return false
        }
    }
    
    /**
     * Stop TProxy service (legacy method, kept for backward compatibility).
     */
    fun stopTProxy() {
        stopNativeTProxy(waitForUdpCleanup = false, udpCleanupDelayMs = 0L)
    }
    
    /**
     * Get TProxy statistics.
     * 
     * @return LongArray with [txPackets, txBytes, rxPackets, rxBytes] or null on error
     */
    fun getStats(): LongArray? {
        return try {
            com.hyperxray.an.service.TProxyService.TProxyGetStats()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting TProxy stats: ${e.message}", e)
            null
        }
    }
    
    /**
     * Update TProxy configuration file.
     * 
     * @param prefs Preferences for TProxy configuration
     * @return true if updated successfully, false otherwise
     */
    fun updateConfig(prefs: Preferences): Boolean {
        return createConfigFile(prefs)
    }
    
    /**
     * Create TProxy configuration file.
     * 
     * @param prefs Preferences for TProxy configuration
     * @return true if created successfully, false otherwise
     */
    private fun createConfigFile(prefs: Preferences): Boolean {
        return try {
            configFile.createNewFile()
            FileOutputStream(configFile, false).use { fos ->
                val tproxyConf = getTproxyConf(prefs)
                fos.write(tproxyConf.toByteArray())
                fos.flush()
            }
            Log.d(TAG, "TProxy config file created/updated successfully")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write TProxy config file: ${e.message}", e)
            false
        }
    }
    
    /**
     * Check if TProxy service is running.
     * 
     * @return true if running, false otherwise
     */
    fun isRunning(): Boolean {
        return isRunningRef.get()
    }
    
    /**
     * Get TProxy configuration as string.
     * 
     * @param prefs Preferences for TProxy configuration
     * @return TProxy configuration string
     */
    private fun getTproxyConf(prefs: Preferences): String {
        // Use custom values if available, otherwise use defaults
        val mtu = prefs.tunnelMtuCustom
        val taskStack = prefs.taskStackSizeCustom
        // Maximum buffer size for optimal performance - removed 65432 limit
        val tcpBuffer = prefs.tcpBufferSize
        val nofile = prefs.limitNofile
        val connectTimeout = prefs.connectTimeout
        val readWriteTimeout = prefs.readWriteTimeout
        
        // CRITICAL: Increase UDP timeout to match Xray policy timeout (60 minutes)
        // Default native tunnel UDP timeout is 60 seconds, which is too short
        // This causes "closed pipe" errors when UDP dispatcher closes connections
        // OPTIMIZATION: Increased to 3600000ms (60 minutes) to match optimized Xray connIdle timeout
        // This further reduces race conditions and closed pipe errors
        val udpTimeout = 3600000 // 60 minutes in milliseconds (optimized)
        
        var tproxyConf = """misc:
  task-stack-size: $taskStack
  tcp-buffer-size: $tcpBuffer
  connect-timeout: $connectTimeout
  read-write-timeout: $readWriteTimeout
  udp-read-write-timeout: $udpTimeout
  udp-recv-buffer-size: 524288
  udp-copy-buffer-nums: 10
  limit-nofile: $nofile
tunnel:
  mtu: $mtu
  multi-queue: ${prefs.tunnelMultiQueue}
"""
        tproxyConf += """socks5:
  port: ${prefs.socksPort}
  address: '${prefs.socksAddress}'
  udp: '${if (prefs.udpInTcp) "tcp" else "udp"}'
  pipeline: ${prefs.socks5Pipeline}
"""
        if (prefs.socksUsername.isNotEmpty() && prefs.socksPassword.isNotEmpty()) {
            tproxyConf += "  username: '" + prefs.socksUsername + "'\n"
            tproxyConf += "  password: '" + prefs.socksPassword + "'\n"
        }
        return tproxyConf
    }
    
    /**
     * Notify native tunnel of UDP error.
     * 
     * @param errorType 0=closed pipe, 1=timeout, 2=other
     * @return true if notification was successful, false otherwise
     */
    fun notifyUdpError(errorType: Int): Boolean {
        return try {
            com.hyperxray.an.service.TProxyService.TProxyNotifyUdpError(errorType)
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying UDP error: ${e.message}", e)
            false
        }
    }
    
    /**
     * Notify native tunnel that UDP recovery is complete.
     * 
     * @return true if notification was successful, false otherwise
     */
    fun notifyUdpRecoveryComplete(): Boolean {
        return try {
            com.hyperxray.an.service.TProxyService.TProxyNotifyUdpRecoveryComplete()
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying UDP recovery: ${e.message}", e)
            false
        }
    }
    
    /**
     * Notify native tunnel of imminent UDP cleanup.
     * 
     * @return true if notification was successful, false otherwise
     */
    fun notifyImminentUdpCleanup(): Boolean {
        return try {
            com.hyperxray.an.service.TProxyService.TProxyNotifyImminentUdpCleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying imminent UDP cleanup: ${e.message}", e)
            false
        }
    }
}

