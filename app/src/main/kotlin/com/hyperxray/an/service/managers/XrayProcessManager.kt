package com.hyperxray.an.service.managers

import android.content.Context
import android.os.Build
import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.common.ConfigUtils
import com.hyperxray.an.common.ConfigUtils.extractPortsFromJson
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.xray.runtime.LogLineCallback
import com.hyperxray.an.xray.runtime.MultiXrayCoreManager
import com.hyperxray.an.xray.runtime.XrayRuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.Volatile

/**
 * Manages Xray-core process lifecycle.
 * Supports both single process and multi-instance modes via MultiXrayCoreManager.
 * Handles process creation, directory setup, and config validation.
 */
class XrayProcessManager(private val context: Context) {
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
        private const val TAG = "XrayProcessManager"
        private const val WATCHDOG_DELAY_MS = 500L // Check process liveness after 500ms
        private const val WATCHDOG_TIMEOUT_MS = 1000L // Maximum time to wait for process stabilization
    }
    
    // Stream gobblers tracking
    private val streamGobblerThreads = mutableListOf<Thread>()
    private val processExitCode = AtomicInteger(-1)
    private val processExitMessage = AtomicReference<String?>(null)
    
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
        val allExcludedPorts = excludedPorts + extractPortsFromJson(configContent)
        
        // Start instances
        return try {
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
                    Thread.sleep(2000) // Wait for graceful shutdown
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
    fun isProcessRunning(): Boolean {
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
     * Get ProcessBuilder for single process mode (legacy).
     */
    fun getProcessBuilder(xrayPath: String): ProcessBuilder {
        val filesDir = context.filesDir
        
        // Ensure filesDir exists
        if (!filesDir.exists()) {
            val created = filesDir.mkdirs()
            if (!created) {
                Log.w(TAG, "Failed to create filesDir: ${filesDir.absolutePath}")
            } else {
                Log.d(TAG, "Created filesDir: ${filesDir.absolutePath}")
            }
        }
        
        // Check if libxray.so exists
        val libxrayFile = File(xrayPath)
        if (!libxrayFile.exists()) {
            throw IOException("libxray.so not found at: $xrayPath")
        }
        
        // Use Android linker to execute libxray.so
        val linkerPath = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) {
            "/system/bin/linker64"
        } else {
            "/system/bin/linker"
        }
        
        Log.d(TAG, "Using linker: $linkerPath to execute: $xrayPath")
        
        val command: MutableList<String> = mutableListOf(linkerPath, xrayPath, "run")
        
        val processBuilder = ProcessBuilder(command)
        val environment = processBuilder.environment()
        environment["XRAY_LOCATION_ASSET"] = filesDir.path
        processBuilder.directory(filesDir)
        processBuilder.redirectErrorStream(true)
        return processBuilder
    }
    
    /**
     * Start Xray process with defensive mechanisms to capture silent failures.
     * 
     * This method implements:
     * - Stream gobblers for STDOUT and STDERR (separate threads)
     * - Exit code capture
     * - Watchdog mechanism to detect process death
     * 
     * @param processBuilder The ProcessBuilder configured for Xray
     * @param configContent The JSON configuration to write to STDIN
     * @param logCallback Optional callback for log lines
     * @return ProcessResult containing the process and startup status
     */
    suspend fun startProcessWithDefensiveMechanisms(
        processBuilder: ProcessBuilder,
        configContent: String,
        logCallback: LogLineCallback? = null
    ): ProcessResult {
        val startTime = System.currentTimeMillis()
        AiLogHelper.i(TAG, "🚀 PROCESS START: Starting Xray process with defensive mechanisms")
        
        // Reset exit tracking
        processExitCode.set(-1)
        processExitMessage.set(null)
        
        try {
            // Start process
            val process = processBuilder.start()
            xrayProcess = process
            
            AiLogHelper.d(TAG, "✅ PROCESS START: Process created (PID: ${getProcessPid(process)})")
            
            // CRITICAL: Start stream gobblers IMMEDIATELY (before config write)
            // This prevents buffer blocking and captures early error messages
            startStreamGobblers(process, logCallback)
            
            // Small delay to allow process to output any startup errors
            delay(100)
            
            // Write config to STDIN
            val configWriter = XrayConfigWriter()
            try {
                process.outputStream.use { outputStream ->
                    configWriter.writeConfig(outputStream, configContent)
                }
            } catch (e: Exception) {
                // Config write may fail if process already terminated
                val exitCode = getExitCode(process)
                if (exitCode != null) {
                    val errorMsg = "Process terminated during config write (exit code: $exitCode)"
                    AiLogHelper.e(TAG, "❌ PROCESS START FAILED: $errorMsg")
                    return ProcessResult(
                        process = process,
                        isAlive = false,
                        exitCode = exitCode,
                        errorMessage = errorMsg
                    )
                }
                // Re-throw if process is still alive
                throw e
            }
            
            // Watchdog mechanism: Check process liveness after stabilization delay
            delay(WATCHDOG_DELAY_MS)
            
            val isAlive = process.isAlive
            val exitCode = getExitCode(process)
            
            if (!isAlive && exitCode != null) {
                val errorMsg = processExitMessage.get() ?: "Process died unexpectedly (exit code: $exitCode)"
                AiLogHelper.e(TAG, "❌ PROCESS START FAILED: Process died after ${System.currentTimeMillis() - startTime}ms - $errorMsg")
                return ProcessResult(
                    process = process,
                    isAlive = false,
                    exitCode = exitCode,
                    errorMessage = errorMsg
                )
            }
            
            if (!isAlive) {
                val errorMsg = "Process is not alive but exit code unavailable"
                AiLogHelper.e(TAG, "❌ PROCESS START FAILED: $errorMsg")
                return ProcessResult(
                    process = process,
                    isAlive = false,
                    exitCode = null,
                    errorMessage = errorMsg
                )
            }
            
            val duration = System.currentTimeMillis() - startTime
            AiLogHelper.i(TAG, "✅ PROCESS START SUCCESS: Process is alive after ${duration}ms (PID: ${getProcessPid(process)})")
            
            return ProcessResult(
                process = process,
                isAlive = true,
                exitCode = null,
                errorMessage = null
            )
            
        } catch (e: Exception) {
            val errorMsg = "Failed to start process: ${e.message}"
            AiLogHelper.e(TAG, "❌ PROCESS START FAILED: $errorMsg", e)
            return ProcessResult(
                process = null,
                isAlive = false,
                exitCode = null,
                errorMessage = errorMsg
            )
        }
    }
    
    /**
     * Start stream gobblers for STDOUT and STDERR in separate threads.
     * This prevents buffer blocking and ensures we capture all process output.
     */
    private fun startStreamGobblers(process: Process, logCallback: LogLineCallback?) {
        // STDOUT gobbler
        val stdoutThread = Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        logCallback?.onLogLine(line)
                        Log.d(TAG, "Xray STDOUT: $line")
                    }
                }
            } catch (e: Exception) {
                if (!isStopping) {
                    Log.d(TAG, "STDOUT gobbler finished: ${e.message}")
                }
            }
        }, "Xray-STDOUT-Gobbler").apply {
            isDaemon = true
            start()
        }
        
        // STDERR gobbler (only if redirectErrorStream is false)
        // Note: If redirectErrorStream=true, STDERR is merged into STDOUT
        val stderrThread = Thread({
            try {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        logCallback?.onLogLine("[STDERR] $line")
                        Log.e(TAG, "Xray STDERR: $line")
                        
                        // Check for common error patterns
                        if (line.contains("geoip.dat", ignoreCase = true) ||
                            line.contains("geosite.dat", ignoreCase = true)) {
                            processExitMessage.set("Asset file error: $line")
                        } else if (line.contains("config", ignoreCase = true) &&
                                   (line.contains("error", ignoreCase = true) ||
                                    line.contains("invalid", ignoreCase = true) ||
                                    line.contains("parse", ignoreCase = true))) {
                            processExitMessage.set("Config error: $line")
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isStopping) {
                    Log.d(TAG, "STDERR gobbler finished: ${e.message}")
                }
            }
        }, "Xray-STDERR-Gobbler").apply {
            isDaemon = true
            start()
        }
        
        streamGobblerThreads.add(stdoutThread)
        streamGobblerThreads.add(stderrThread)
        
        // Monitor process exit in background
        serviceScope.launch {
            try {
                val exitCode = process.waitFor()
                processExitCode.set(exitCode)
                
                // Try to get exit message from streams
                if (processExitMessage.get() == null) {
                    when (exitCode) {
                        127 -> processExitMessage.set("Library not found (exit code 127)")
                        1 -> processExitMessage.set("General error (exit code 1)")
                        2 -> processExitMessage.set("Misuse of shell command (exit code 2)")
                        else -> processExitMessage.set("Process exited with code $exitCode")
                    }
                }
                
                AiLogHelper.w(TAG, "⚠️ PROCESS EXIT: Process terminated with exit code $exitCode: ${processExitMessage.get()}")
            } catch (e: Exception) {
                Log.d(TAG, "Process exit monitoring finished: ${e.message}")
            }
        }
        
        AiLogHelper.d(TAG, "✅ STREAM GOBBLERS: Started STDOUT and STDERR gobblers")
    }
    
    /**
     * Get process exit code if available.
     */
    private fun getExitCode(process: Process): Int? {
        return try {
            if (!process.isAlive) {
                val exitCode = process.exitValue()
                processExitCode.set(exitCode)
                exitCode
            } else {
                null
            }
        } catch (e: IllegalThreadStateException) {
            // Process is still alive
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error getting exit code: ${e.message}")
            null
        }
    }
    
    /**
     * Get process PID if available.
     */
    private fun getProcessPid(process: Process): Long? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pidMethod = process.javaClass.getMethod("pid")
                pidMethod.invoke(process) as? Long
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Result of process startup with defensive mechanisms.
     */
    data class ProcessResult(
        val process: Process?,
        val isAlive: Boolean,
        val exitCode: Int?,
        val errorMessage: String?
    )
    
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
    
    /**
     * Get MultiXrayCoreManager instance (for status observation).
     */
    fun getMultiXrayCoreManager(): MultiXrayCoreManager? {
        return multiXrayCoreManager
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
                    address.takeIf { it.isNotEmpty() }
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



