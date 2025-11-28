package com.hyperxray.an.service.managers

import android.content.Context
import android.os.Build
import android.util.Log
import com.hyperxray.an.core.config.utils.ConfigParser
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.xray.runtime.LogLineCallback
import com.hyperxray.an.xray.runtime.MultiXrayCoreManager
import com.hyperxray.an.xray.runtime.XrayRuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.Volatile

/**
 * Manages Xray-core process lifecycle and all native library interactions.
 * Consolidates process management logic from TProxyService and XrayProcessManager.
 * Handles process creation, lifecycle management, stream reading, and validation.
 */
class XrayCoreManager(private val context: Context) {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = Preferences(context)
    
    @Volatile
    private var xrayProcess: Process? = null
    
    @Volatile
    private var multiXrayCoreManager: MultiXrayCoreManager? = null
    
    @Volatile
    private var isStopping = false
    
    @Volatile
    private var isStarting = false
    
    private val mutex = Mutex()
    
    companion object {
        private const val TAG = "XrayCoreManager"
        
        /**
         * Get the native library directory path.
         * 
         * @param context Android context
         * @return Native library directory path, or null if unavailable
         */
        fun getNativeLibraryDir(context: Context?): String? {
            if (context == null) {
                Log.e(TAG, "Context is null")
                return null
            }
            try {
                val applicationInfo = context.applicationInfo
                if (applicationInfo != null) {
                    val nativeLibraryDir = applicationInfo.nativeLibraryDir
                    Log.d(TAG, "Native Library Directory: $nativeLibraryDir")
                    return nativeLibraryDir
                } else {
                    Log.e(TAG, "ApplicationInfo is null")
                    return null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting native library dir", e)
                return null
            }
        }
    }
    
    /**
     * Start Xray process(es).
     * 
     * @param configPath Path to Xray configuration file
     * @param configContent Optional pre-read configuration content
     * @param excludedPorts Set of ports to exclude when finding available ports
     * @param instanceCount Number of instances to start (1-4)
     * @param logCallback Optional callback for log lines
     * @return Map of instance index to API port, or null if startup failed
     */
    suspend fun startProcess(
        configPath: String,
        configContent: String?,
        excludedPorts: Set<Int>,
        instanceCount: Int,
        logCallback: LogLineCallback?
    ): Map<Int, Int>? {
        // Prevent duplicate start calls
        val shouldProceed = mutex.withLock {
            if (isStarting) {
                Log.d(TAG, "startProcess() already in progress, ignoring duplicate call")
                return null
            }
            
            if (isStopping) {
                Log.w(TAG, "startProcess() called while stopping, ignoring")
                return null
            }
            
            isStarting = true
            true
        }
        
        if (!shouldProceed) {
            return null
        }
        
        try {
            // Ensure directories exist
            ensureDirectories()
            
            // Validate config path
            val configFile = validateConfigPath(configPath)
            if (configFile == null) {
                Log.e(TAG, "Invalid configuration file: path validation failed")
                return null
            }
            
            // Read config content securely
            val finalConfigContent = configContent ?: readConfigContentSecurely(configFile)
            if (finalConfigContent == null) {
                Log.e(TAG, "Failed to read configuration file")
                return null
            }
            
            // Pre-resolve server address if needed (using system DNS, not VPN DNS)
            preResolveServerAddress(finalConfigContent)
            
            // Use MultiXrayCoreManager for multiple instances, or fallback to single process
            if (instanceCount > 1) {
                return startMultiInstance(
                    configPath = configPath,
                    configContent = finalConfigContent,
                    excludedPorts = excludedPorts,
                    instanceCount = instanceCount,
                    logCallback = logCallback
                )
            } else {
                // Single process mode (legacy, not currently used but kept for compatibility)
                Log.w(TAG, "Single process mode is deprecated. Use MultiXrayCoreManager with instanceCount=1.")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Xray process: ${e.message}", e)
            return null
        } finally {
            mutex.withLock {
                isStarting = false
            }
        }
    }
    
    /**
     * Start multiple Xray instances using MultiXrayCoreManager.
     */
    private suspend fun startMultiInstance(
        configPath: String,
        configContent: String,
        excludedPorts: Set<Int>,
        instanceCount: Int,
        logCallback: LogLineCallback?
    ): Map<Int, Int>? {
        Log.i(TAG, "🚀 Starting $instanceCount xray-core instances using MultiXrayCoreManager")
        
        // Initialize MultiXrayCoreManager if not already initialized
        val manager = mutex.withLock {
            if (multiXrayCoreManager == null) {
                multiXrayCoreManager = MultiXrayCoreManager.getInstance(context)
            }
            multiXrayCoreManager
        } ?: run {
            Log.e(TAG, "Failed to initialize MultiXrayCoreManager")
            return null
        }
        
        // Set log callback
        logCallback?.let { callback ->
            manager.setLogLineCallback(callback)
        }
        
        // Extract excluded ports from config
        val allExcludedPorts = excludedPorts + ConfigParser.extractPortsFromJson(configContent)
        
        // Start instances
        val result = try {
            manager.startInstances(
                count = instanceCount,
                configPath = configPath,
                configContent = configContent,
                excludedPorts = allExcludedPorts
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting instances: ${e.message}", e)
            null
        }
        
        
        return result
    }
    
    
    /**
     * Start Xray process in legacy single-instance mode.
     * DEPRECATED: This method is no longer used. Xray-core is embedded in libhyperxray.so.
     * 
     * @param configFile Configuration file
     * @param configContent Configuration content
     * @param logCallback Optional callback for log lines
     * @return Process instance if started successfully, null otherwise
     */
    suspend fun startProcessLegacy(
        configFile: File,
        configContent: String,
        logCallback: ((String) -> Unit)?
    ): Process? {
        Log.w(TAG, "startProcessLegacy() is deprecated - Xray-core is embedded in libhyperxray.so")
        return null
    }
    
    /**
     * Stop Xray process(es).
     * 
     * @param reason Optional reason for stopping
     */
    suspend fun stopProcess(reason: String? = null) {
        val shouldProceed = mutex.withLock {
            if (isStopping) {
                Log.d(TAG, "stopProcess() already in progress, ignoring duplicate call")
                return
            }
            isStopping = true
            true
        }
        
        if (!shouldProceed) {
            return
        }
        
        try {
            val reasonMsg = reason ?: "No reason provided"
            Log.i(TAG, "stopProcess() called. Reason: $reasonMsg")
            
            // Stop MultiXrayCoreManager instances
            val manager = multiXrayCoreManager
            if (manager != null) {
                try {
                    Log.d(TAG, "Stopping MultiXrayCoreManager instances...")
                    manager.stopAllInstances()
                    Log.d(TAG, "MultiXrayCoreManager instances stopped.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping MultiXrayCoreManager instances", e)
                }
            }
            
            // Stop single process if exists
            val processToDestroy = xrayProcess
            if (processToDestroy != null && processToDestroy.isAlive) {
                try {
                    // Try graceful termination first
                    processToDestroy.destroy()
                    delay(2000) // Wait for graceful shutdown
                    // Force kill if still alive
                    if (processToDestroy.isAlive) {
                        Log.d(TAG, "Process still alive after graceful shutdown, forcing destroy.")
                        processToDestroy.destroyForcibly()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error destroying xray process", e)
                }
                xrayProcess = null
            }
            
            // Cancel coroutine scope
            serviceScope.cancel()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during stopProcess cleanup", e)
        } finally {
            mutex.withLock {
                isStopping = false
            }
        }
    }
    
    /**
     * Kill Xray process(es) forcefully.
     */
    suspend fun killProcess() {
        stopProcess("Force kill requested")
    }
    
    /**
     * Check if process is running.
     * 
     * @return true if running, false otherwise
     */
    fun isProcessAlive(): Boolean {
        return kotlinx.coroutines.runBlocking {
            mutex.withLock {
                val manager = multiXrayCoreManager
                if (manager != null) {
                    manager.hasRunningInstances()
                } else {
                    val process = xrayProcess
                    process != null && process.isAlive
                }
            }
        }
    }
    
    /**
     * Get process ID (for single process mode).
     * 
     * @return Process ID, or null if not running
     */
    fun getProcessId(): Long? {
        val process = xrayProcess
        return if (process != null && process.isAlive) {
            try {
                // Process.pid() is available from API 26+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val pidMethod = process.javaClass.getMethod("pid")
                    pidMethod.invoke(process) as? Long
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting process ID: ${e.message}", e)
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Get ProcessBuilder for libxray.so execution.
     * DEPRECATED: This method is no longer used. Xray-core is embedded in libhyperxray.so.
     * 
     * @param xrayPath Path to libxray.so (ignored)
     * @return ProcessBuilder configured for Xray execution
     */
    fun getProcessBuilder(xrayPath: String): ProcessBuilder {
        Log.w(TAG, "getProcessBuilder() is deprecated - Xray-core is embedded in libhyperxray.so")
        throw UnsupportedOperationException("libxray.so is no longer used - Xray-core is embedded in libhyperxray.so")
    }
    
    /**
     * Find an available port in the range 10000-65535.
     * 
     * @param excludedPorts Set of ports to exclude
     * @return Available port number, or null if none found
     */
    fun findAvailablePort(excludedPorts: Set<Int>): Int? {
        (10000..65535)
            .shuffled()
            .forEach { port ->
                if (port in excludedPorts) return@forEach
                runCatching {
                    ServerSocket(port).use { socket ->
                        socket.reuseAddress = true
                    }
                    port
                }.onFailure {
                    Log.d(TAG, "Port $port unavailable: ${it.message}")
                }.onSuccess {
                    return port
                }
            }
        return null
    }
    
    /**
     * Read process output stream with timeout protection and health checks.
     * Prevents thread hangs when process dies but stream remains open.
     * Uses coroutines for proper cancellation and resource management.
     * 
     * @param process Process to read from
     * @param logCallback Optional callback for each log line
     */
    suspend fun readProcessStream(process: Process, logCallback: ((String) -> Unit)?) {
        var readJob: kotlinx.coroutines.Job? = null
        var stderrJob: kotlinx.coroutines.Job? = null
        var healthCheckJob: kotlinx.coroutines.Job? = null
        
        try {
            // Start reading stderr in parallel to capture error messages immediately
            stderrJob = serviceScope.launch(Dispatchers.IO) {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { errorReader ->
                        while (isActive && process.isAlive) {
                            try {
                                if (!errorReader.ready()) {
                                    delay(100)
                                    ensureActive()
                                    continue
                                }
                                val line = errorReader.readLine()
                                if (line == null) {
                                    delay(100)
                                    continue
                                }
                                // Log stderr output prominently
                                Log.e(TAG, "Xray stderr: $line")
                                logCallback?.invoke("STDERR: $line")
                            } catch (e: IOException) {
                                if (process.isAlive) {
                                    delay(100)
                                    continue
                                } else {
                                    break
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w(TAG, "Error reading stderr: ${e.message}")
                    }
                }
            }
            
            // Health check coroutine monitors process and cancels read job if needed
            healthCheckJob = serviceScope.launch(Dispatchers.IO) {
                try {
                    delay(5000) // Wait 5 seconds before first health check
                    while (isActive) {
                        delay(2000) // Check every 2 seconds
                        ensureActive()
                        
                        // Check if we're stopping
                        if (isStopping) {
                            Log.d(TAG, "Health check detected stop request, cancelling read job.")
                            readJob?.cancel()
                            stderrJob?.cancel()
                            break
                        }
                        
                        // Check if process is still alive
                        if (!process.isAlive) {
                            val exitValue = try {
                                process.exitValue()
                            } catch (e: IllegalThreadStateException) {
                                -1
                            }
                            Log.d(TAG, "Health check detected process death (exit code: $exitValue), cancelling read job.")
                            readJob?.cancel()
                            stderrJob?.cancel()
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in health check coroutine: ${e.message}", e)
                    }
                }
            }
            
            // Stream reading coroutine
            readJob = serviceScope.launch(Dispatchers.IO) {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var lastReadTime = System.currentTimeMillis()
                        val readTimeout = 10000L // 10 seconds without any read
                        
                        while (isActive) {
                            try {
                                ensureActive()
                                
                                // Check if we've been reading for too long without data
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastReadTime > readTimeout) {
                                    if (!process.isAlive) {
                                        val exitValue = try {
                                            process.exitValue()
                                        } catch (e: IllegalThreadStateException) {
                                            -1
                                        }
                                        Log.d(TAG, "Read timeout detected, process is dead (exit code: $exitValue)")
                                        break
                                    }
                                    Log.w(TAG, "Read timeout: no data for ${readTimeout}ms, but process is alive. Continuing...")
                                    lastReadTime = currentTime
                                }
                                
                                // Check if reader is ready before attempting to read
                                if (!reader.ready()) {
                                    delay(100)
                                    ensureActive()
                                    continue
                                }
                                
                                // Check available bytes to detect if stream is closed
                                try {
                                    process.inputStream.available()
                                } catch (e: IOException) {
                                    Log.d(TAG, "Stream unavailable (likely closed): ${e.message}")
                                    break
                                }
                                
                                // Read line
                                val line = reader.readLine()
                                
                                if (line == null) {
                                    Log.d(TAG, "Stream reached EOF (null read)")
                                    break
                                }
                                
                                // Update last read time on successful read
                                lastReadTime = System.currentTimeMillis()
                                
                                // Process the log line
                                logCallback?.invoke(line)
                                
                                // Small delay to allow cancellation to be checked
                                delay(10)
                                
                            } catch (e: InterruptedIOException) {
                                Log.d(TAG, "Stream read interrupted: ${e.message}")
                                break
                            } catch (e: IOException) {
                                if (!process.isAlive) {
                                    val exitValue = try {
                                        process.exitValue()
                                    } catch (ex: IllegalThreadStateException) {
                                        -1
                                    }
                                    Log.d(TAG, "IOException during read, process is dead (exit code: $exitValue): ${e.message}")
                                } else {
                                    Log.e(TAG, "IOException during stream read (process alive): ${e.message}", e)
                                }
                                break
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                Log.d(TAG, "Read coroutine cancelled")
                                throw e
                            } catch (e: Exception) {
                                if (isActive) {
                                    Log.e(TAG, "Unexpected error during stream read: ${e.message}", e)
                                }
                                break
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Read coroutine cancelled during stream reading")
                    throw e
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in read coroutine: ${e.message}", e)
                    }
                }
            }
            
            // Wait for read job to complete
            readJob.join()
            
            // Cancel health check and stderr jobs
            healthCheckJob?.cancel()
            stderrJob?.cancel()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up stream reading: ${e.message}", e)
        } finally {
            // Cancel all jobs and wait for them to finish
            try {
                healthCheckJob?.cancel()
                readJob?.cancel()
                stderrJob?.cancel()
                withTimeoutOrNull(1000) {
                    healthCheckJob?.join()
                    readJob?.join()
                    stderrJob?.join()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling stream reading coroutines: ${e.message}", e)
            }
            
            // Wait before closing streams
            delay(200)
            
            val processAlive = process.isAlive
            if (processAlive) {
                delay(300)
            }
            
            // Close streams
            try {
                process.inputStream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing process input stream", e)
            }
            try {
                process.errorStream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing process error stream", e)
            }
            try {
                process.outputStream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing process output stream", e)
            }
        }
    }
    
    /**
     * Validate that process started successfully.
     * Checks process status multiple times to detect early exits.
     * 
     * @param process Process to validate
     * @return true if process is validated, false if it exited
     */
    suspend fun validateProcessStartup(process: Process): Boolean {
        val checkInterval = 50L // Check every 50ms
        val minStartupChecks = 2 // Minimum 2 checks (100ms) before considering process started
        val maxStartupChecks = 100 // Maximum 100 checks (5 seconds) as safety timeout
        
        var checksPerformed = 0
        var processValidated = false
        
        // Periodically check if process stays alive during startup
        while (checksPerformed < maxStartupChecks) {
            delay(checkInterval)
            checksPerformed++
            
            // Check if process exited during startup
            if (!process.isAlive) {
                val exitValue = try {
                    process.exitValue()
                } catch (e: IllegalThreadStateException) {
                    -1
                }
                val errorMessage = "Xray process exited during startup after ${checksPerformed * checkInterval}ms (exit code: $exitValue)"
                Log.e(TAG, errorMessage)
                return false
            }
            
            // After minimum checks, if process is still alive, consider it started
            if (checksPerformed >= minStartupChecks) {
                processValidated = true
                Log.d(TAG, "Process startup validated after ${checksPerformed * checkInterval}ms")
                break
            }
        }
        
        // Final validation check
        if (!processValidated) {
            if (!process.isAlive) {
                val exitValue = try {
                    process.exitValue()
                } catch (e: IllegalThreadStateException) {
                    -1
                }
                val errorMessage = "Xray process exited during startup validation (exit code: $exitValue)"
                Log.e(TAG, errorMessage)
                return false
            }
            Log.w(TAG, "Process startup validation hit timeout (${maxStartupChecks * checkInterval}ms), but process is alive. Proceeding.")
        }
        
        return true
    }
    
    /**
     * Ensure required directories exist.
     */
    fun ensureDirectories() {
        val filesDir = context.filesDir
        val directories = listOf(
            File(filesDir, "logs"),
            File(filesDir, "frames"),
            File(filesDir, "xray_config")
        )
        
        directories.forEach { dir ->
            if (!dir.exists()) {
                val created = dir.mkdirs()
                if (created) {
                    Log.d(TAG, "Created directory: ${dir.absolutePath}")
                } else {
                    Log.w(TAG, "Failed to create directory: ${dir.absolutePath}")
                }
            }
        }
    }
    
    /**
     * Get MultiXrayCoreManager instance (for status observation).
     */
    fun getMultiXrayCoreManager(): MultiXrayCoreManager? {
        return multiXrayCoreManager
    }
    
    
    /**
     * Cleanup all resources.
     */
    fun cleanup() {
        runBlocking {
            stopProcess("Cleanup requested")
        }
        serviceScope.cancel()
        multiXrayCoreManager = null
    }
    
    // Private helper methods
    
    private fun validateConfigPath(configPath: String?): File? {
        if (configPath == null) {
            Log.e(TAG, "Config path is null")
            return null
        }
        
        try {
            val configFile = File(configPath)
            
            if (!configFile.exists()) {
                Log.e(TAG, "Config file does not exist: $configPath")
                return null
            }
            
            if (!configFile.isFile) {
                Log.e(TAG, "Config path is not a file: $configPath")
                return null
            }
            
            val canonicalConfigPath = configFile.canonicalPath
            val privateDir = context.filesDir
            val canonicalPrivateDir = privateDir.canonicalPath
            
            if (!canonicalConfigPath.startsWith(canonicalPrivateDir)) {
                Log.e(TAG, "Config file is outside private directory: $canonicalConfigPath")
                return null
            }
            
            if (!configFile.canRead()) {
                Log.e(TAG, "Config file is not readable: $canonicalConfigPath")
                return null
            }
            
            return configFile
        } catch (e: Exception) {
            Log.e(TAG, "Error validating config path: $configPath", e)
            return null
        }
    }
    
    private fun readConfigContentSecurely(configFile: File): String? {
        try {
            if (!configFile.exists() || !configFile.isFile || !configFile.canRead()) {
                Log.e(TAG, "Config file validation failed during read: ${configFile.canonicalPath}")
                return null
            }
            
            return configFile.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading config file: ${configFile.canonicalPath}", e)
            return null
        }
    }
    
    private suspend fun preResolveServerAddress(configContent: String) {
        val serverAddress = extractServerAddressFromConfig(configContent)
        if (serverAddress != null && !isValidIpAddress(serverAddress)) {
            // Server address is a domain name, resolve it using system DNS
            Log.d(TAG, "Pre-resolving server address: $serverAddress (using system DNS)")
            try {
                val resolvedAddresses = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    InetAddress.getAllByName(serverAddress)
                }
                if (resolvedAddresses.isNotEmpty()) {
                    Log.i(TAG, "✅ Server address resolved: $serverAddress -> ${resolvedAddresses.map { it.hostAddress }}")
                } else {
                    Log.w(TAG, "⚠️ Server address resolved but no IPs found: $serverAddress")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve server address: $serverAddress (${e.message})")
                // Continue anyway - Xray-core can resolve DNS itself
            }
        } else if (serverAddress != null && isValidIpAddress(serverAddress)) {
            Log.d(TAG, "Server address is already an IP: $serverAddress (no DNS resolution needed)")
        }
    }
    
    private fun extractServerAddressFromConfig(configContent: String): String? {
        return try {
            val jsonObject = org.json.JSONObject(configContent)
            val outbounds = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
            if (outbounds != null && outbounds.length() > 0) {
                val outbound = outbounds.getJSONObject(0)
                val settings = outbound.optJSONObject("settings")
                val vnext = settings?.optJSONArray("vnext")
                if (vnext != null && vnext.length() > 0) {
                    val server = vnext.getJSONObject(0)
                    val address = server.optString("address", "")
                    if (address.isNotEmpty()) address else null
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting server address from config: ${e.message}")
            null
        }
    }
    
    private fun isValidIpAddress(address: String): Boolean {
        return try {
            val parts = address.split(".")
            if (parts.size != 4) return false
            parts.all { part ->
                val num = part.toIntOrNull()
                num != null && num in 0..255
            }
        } catch (e: Exception) {
            false
        }
    }
}

