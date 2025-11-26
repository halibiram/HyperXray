package com.hyperxray.an.service.managers

import android.content.Context
import android.os.Build
import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.core.config.utils.ConfigParser
import com.hyperxray.an.core.network.dns.DnsCacheManager
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
        val startTime = System.currentTimeMillis()
        AiLogHelper.i(TAG, "🚀 XRAY PROCESS START: Starting Xray process (instances: $instanceCount, config: $configPath)")
        
        // Prevent duplicate start calls
        val shouldProceed = mutex.withLock {
            if (isStarting) {
                Log.d(TAG, "startProcess() already in progress, ignoring duplicate call")
                AiLogHelper.w(TAG, "⚠️ XRAY PROCESS START: Already in progress, ignoring duplicate call")
                return null
            }
            
            if (isStopping) {
                Log.w(TAG, "startProcess() called while stopping, ignoring")
                AiLogHelper.w(TAG, "⚠️ XRAY PROCESS START: Called while stopping, ignoring")
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
            val dirStartTime = System.currentTimeMillis()
            ensureDirectories()
            val dirDuration = System.currentTimeMillis() - dirStartTime
            AiLogHelper.d(TAG, "✅ XRAY PROCESS START: Directories ensured (duration: ${dirDuration}ms)")
            
            // Validate config path
            val validateStartTime = System.currentTimeMillis()
            val configFile = validateConfigPath(configPath)
            val validateDuration = System.currentTimeMillis() - validateStartTime
            if (configFile == null) {
                Log.e(TAG, "Invalid configuration file: path validation failed")
                AiLogHelper.e(TAG, "❌ XRAY PROCESS START: Invalid configuration file: path validation failed (duration: ${validateDuration}ms)")
                return null
            }
            AiLogHelper.d(TAG, "✅ XRAY PROCESS START: Config path validated (path: $configPath, size: ${configFile.length()} bytes, duration: ${validateDuration}ms)")
            
            // Read config content securely
            val readStartTime = System.currentTimeMillis()
            val finalConfigContent = configContent ?: readConfigContentSecurely(configFile)
            val readDuration = System.currentTimeMillis() - readStartTime
            if (finalConfigContent == null) {
                Log.e(TAG, "Failed to read configuration file")
                AiLogHelper.e(TAG, "❌ XRAY PROCESS START: Failed to read configuration file (duration: ${readDuration}ms)")
                return null
            }
            AiLogHelper.d(TAG, "✅ XRAY PROCESS START: Config content read (size: ${finalConfigContent.length} bytes, duration: ${readDuration}ms)")
            
            // Ensure DnsCacheManager is initialized before pre-resolving
            DnsCacheManager.initialize(context)
            
            // Pre-resolve server address if needed (using system DNS, not VPN DNS)
            val resolveStartTime = System.currentTimeMillis()
            val resolvedConfigContent = preResolveServerAddress(finalConfigContent)
            val resolveDuration = System.currentTimeMillis() - resolveStartTime
            AiLogHelper.d(TAG, "✅ XRAY PROCESS START: Server address pre-resolved (duration: ${resolveDuration}ms)")
            
            // Use MultiXrayCoreManager for multiple instances, or fallback to single process
            if (instanceCount > 1) {
                AiLogHelper.i(TAG, "🔧 XRAY PROCESS START: Using MultiXrayCoreManager for $instanceCount instances")
                val multiStartTime = System.currentTimeMillis()
                val result = startMultiInstance(
                    configPath = configPath,
                    configContent = resolvedConfigContent,
                    excludedPorts = excludedPorts,
                    instanceCount = instanceCount,
                    logCallback = logCallback
                )
                val multiDuration = System.currentTimeMillis() - multiStartTime
                val totalDuration = System.currentTimeMillis() - startTime
                if (result != null) {
                    AiLogHelper.i(TAG, "✅ XRAY PROCESS START SUCCESS: Multi-instance started (instances: ${result.size}, duration: ${multiDuration}ms, total: ${totalDuration}ms)")
                } else {
                    AiLogHelper.e(TAG, "❌ XRAY PROCESS START FAILED: Multi-instance startup failed (duration: ${multiDuration}ms, total: ${totalDuration}ms)")
                }
                return result
            } else {
                // Single process mode (legacy, not currently used but kept for compatibility)
                Log.w(TAG, "Single process mode is deprecated. Use MultiXrayCoreManager with instanceCount=1.")
                AiLogHelper.w(TAG, "⚠️ XRAY PROCESS START: Single process mode is deprecated. Use MultiXrayCoreManager with instanceCount=1.")
                return null
            }
        } catch (e: Exception) {
            val totalDuration = System.currentTimeMillis() - startTime
            Log.e(TAG, "Error starting Xray process: ${e.message}", e)
            AiLogHelper.e(TAG, "❌ XRAY PROCESS START ERROR: Error starting Xray process (duration: ${totalDuration}ms): ${e.message}", e)
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
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "🚀 Starting $instanceCount xray-core instances using MultiXrayCoreManager")
        AiLogHelper.i(TAG, "🚀 XRAY MULTI INSTANCE: Starting $instanceCount xray-core instances using MultiXrayCoreManager")
        
        // Initialize MultiXrayCoreManager if not already initialized
        val initStartTime = System.currentTimeMillis()
        val manager = mutex.withLock {
            if (multiXrayCoreManager == null) {
                AiLogHelper.d(TAG, "🔧 XRAY MULTI INSTANCE: Initializing MultiXrayCoreManager...")
                multiXrayCoreManager = MultiXrayCoreManager.getInstance(context)
            }
            multiXrayCoreManager
        } ?: run {
            Log.e(TAG, "Failed to initialize MultiXrayCoreManager")
            AiLogHelper.e(TAG, "❌ XRAY MULTI INSTANCE: Failed to initialize MultiXrayCoreManager")
            return null
        }
        val initDuration = System.currentTimeMillis() - initStartTime
        AiLogHelper.d(TAG, "✅ XRAY MULTI INSTANCE: MultiXrayCoreManager initialized (duration: ${initDuration}ms)")
        
        // Set log callback
        val callbackStartTime = System.currentTimeMillis()
        logCallback?.let { callback ->
            manager.setLogLineCallback(callback)
            AiLogHelper.d(TAG, "✅ XRAY MULTI INSTANCE: Log callback set")
        } ?: run {
            AiLogHelper.d(TAG, "ℹ️ XRAY MULTI INSTANCE: No log callback provided")
        }
        val callbackDuration = System.currentTimeMillis() - callbackStartTime
        
        // Extract excluded ports from config
        val extractStartTime = System.currentTimeMillis()
        val allExcludedPorts = excludedPorts + ConfigParser.extractPortsFromJson(configContent)
        val extractDuration = System.currentTimeMillis() - extractStartTime
        AiLogHelper.d(TAG, "✅ XRAY MULTI INSTANCE: Excluded ports extracted (count: ${allExcludedPorts.size}, duration: ${extractDuration}ms)")
        
        // Start instances
        val instancesStartTime = System.currentTimeMillis()
        return try {
            val result = manager.startInstances(
                count = instanceCount,
                configPath = configPath,
                configContent = configContent,
                excludedPorts = allExcludedPorts
            )
            val instancesDuration = System.currentTimeMillis() - instancesStartTime
            val totalDuration = System.currentTimeMillis() - startTime
            AiLogHelper.i(TAG, "✅ XRAY MULTI INSTANCE SUCCESS: Started ${result.size} instances (instances duration: ${instancesDuration}ms, total: ${totalDuration}ms, ports: ${result.values.joinToString()})")
            result
        } catch (e: Exception) {
            val instancesDuration = System.currentTimeMillis() - instancesStartTime
            val totalDuration = System.currentTimeMillis() - startTime
            Log.e(TAG, "Error starting instances: ${e.message}", e)
            AiLogHelper.e(TAG, "❌ XRAY MULTI INSTANCE ERROR: Error starting instances (duration: ${instancesDuration}ms, total: ${totalDuration}ms): ${e.message}", e)
            null
        }
    }
    
    /**
     * Stop Xray process(es).
     * 
     * @param reason Optional reason for stopping
     */
    suspend fun stopProcess(reason: String? = null) {
        val startTime = System.currentTimeMillis()
        val shouldProceed = mutex.withLock {
            if (isStopping) {
                Log.d(TAG, "stopProcess() already in progress, ignoring duplicate call")
                AiLogHelper.w(TAG, "⚠️ XRAY PROCESS STOP: Already in progress, ignoring duplicate call")
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
            AiLogHelper.i(TAG, "🛑 XRAY PROCESS STOP: Stopping Xray process (reason: $reasonMsg)")
            
            // Stop MultiXrayCoreManager instances
            val managerStopStartTime = System.currentTimeMillis()
            val manager = multiXrayCoreManager
            if (manager != null) {
                try {
                    Log.d(TAG, "Stopping MultiXrayCoreManager instances...")
                    AiLogHelper.d(TAG, "🔧 XRAY PROCESS STOP: Stopping MultiXrayCoreManager instances...")
                    manager.stopAllInstances()
                    val managerStopDuration = System.currentTimeMillis() - managerStopStartTime
                    Log.d(TAG, "MultiXrayCoreManager instances stopped.")
                    AiLogHelper.i(TAG, "✅ XRAY PROCESS STOP: MultiXrayCoreManager instances stopped (duration: ${managerStopDuration}ms)")
                } catch (e: Exception) {
                    val managerStopDuration = System.currentTimeMillis() - managerStopStartTime
                    Log.e(TAG, "Error stopping MultiXrayCoreManager instances", e)
                    AiLogHelper.e(TAG, "❌ XRAY PROCESS STOP: Error stopping MultiXrayCoreManager instances (duration: ${managerStopDuration}ms): ${e.message}", e)
                }
            } else {
                AiLogHelper.d(TAG, "ℹ️ XRAY PROCESS STOP: No MultiXrayCoreManager to stop")
            }
            
            // Stop single process if exists
            val processStopStartTime = System.currentTimeMillis()
            val processToDestroy = xrayProcess
            if (processToDestroy != null && processToDestroy.isAlive) {
                try {
                    AiLogHelper.d(TAG, "🔧 XRAY PROCESS STOP: Stopping single process (graceful shutdown)...")
                    // Try graceful termination first
                    processToDestroy.destroy()
                    delay(2000) // Wait for graceful shutdown
                    // Force kill if still alive
                    if (processToDestroy.isAlive) {
                        Log.d(TAG, "Process still alive after graceful shutdown, forcing destroy.")
                        AiLogHelper.w(TAG, "⚠️ XRAY PROCESS STOP: Process still alive after graceful shutdown, forcing destroy")
                        processToDestroy.destroyForcibly()
                    }
                    val processStopDuration = System.currentTimeMillis() - processStopStartTime
                    AiLogHelper.i(TAG, "✅ XRAY PROCESS STOP: Single process stopped (duration: ${processStopDuration}ms)")
                } catch (e: Exception) {
                    val processStopDuration = System.currentTimeMillis() - processStopStartTime
                    Log.e(TAG, "Error destroying xray process", e)
                    AiLogHelper.e(TAG, "❌ XRAY PROCESS STOP: Error destroying xray process (duration: ${processStopDuration}ms): ${e.message}", e)
                }
                xrayProcess = null
            } else {
                AiLogHelper.d(TAG, "ℹ️ XRAY PROCESS STOP: No single process to stop")
            }
            
            // Cancel coroutine scope
            val cancelStartTime = System.currentTimeMillis()
            serviceScope.cancel()
            val cancelDuration = System.currentTimeMillis() - cancelStartTime
            AiLogHelper.d(TAG, "✅ XRAY PROCESS STOP: Coroutine scope cancelled (duration: ${cancelDuration}ms)")
            
            val totalDuration = System.currentTimeMillis() - startTime
            AiLogHelper.i(TAG, "✅ XRAY PROCESS STOP COMPLETE: Process stopped (total duration: ${totalDuration}ms)")
            
        } catch (e: Exception) {
            val totalDuration = System.currentTimeMillis() - startTime
            Log.e(TAG, "Error during stopProcess cleanup", e)
            AiLogHelper.e(TAG, "❌ XRAY PROCESS STOP ERROR: Error during stopProcess cleanup (duration: ${totalDuration}ms): ${e.message}", e)
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
     * 
     * Executes libxray.so directly from nativeLibraryDir to avoid W^X violations on Android 10+.
     * The native library directory is system-managed and has execution permissions, unlike filesDir
     * which is mounted as noexec on modern Android devices (Samsung, Xiaomi, Android 10+).
     * 
     * Note: The xrayPath parameter is ignored. The executable path is always resolved
     * from nativeLibraryDir to ensure compatibility with Android 10+ security restrictions.
     * 
     * @param xrayPath Legacy parameter (ignored, kept for backward compatibility)
     * @return ProcessBuilder configured to execute libxray.so from nativeLibraryDir
     * @throws IOException if native library is missing or cannot be accessed
     */
    fun getProcessBuilder(xrayPath: String): ProcessBuilder {
        val filesDir = context.filesDir
        
        // Log if provided path differs from nativeLibraryDir (for debugging)
        // This helps identify if callers are still passing filesDir paths
        if (xrayPath.isNotEmpty() && !xrayPath.contains("lib/")) {
            Log.d(TAG, "Note: xrayPath parameter provided ($xrayPath) but will use nativeLibraryDir instead")
            AiLogHelper.d(TAG, "ℹ️ PROCESS BUILDER: Legacy xrayPath parameter ignored, using nativeLibraryDir")
        }
        
        // Ensure filesDir exists (still needed for asset files like geoip.dat, geosite.dat)
        if (!filesDir.exists()) {
            val created = filesDir.mkdirs()
            if (!created) {
                Log.w(TAG, "Failed to create filesDir: ${filesDir.absolutePath}")
                AiLogHelper.w(TAG, "⚠️ PROCESS BUILDER: Failed to create filesDir: ${filesDir.absolutePath}")
            } else {
                Log.d(TAG, "Created filesDir: ${filesDir.absolutePath}")
                AiLogHelper.d(TAG, "✅ PROCESS BUILDER: Created filesDir: ${filesDir.absolutePath}")
            }
        }
        
        // CRITICAL: Get native library directory where Android extracts .so files
        // This directory has execution permissions and avoids W^X violations
        // On Android 10+, filesDir is mounted as noexec, so we MUST use nativeLibraryDir
        val nativeDir = try {
            val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
            if (nativeLibraryDir == null || nativeLibraryDir.isEmpty()) {
                throw IOException("nativeLibraryDir is null or empty")
            }
            Log.i(TAG, "✅ Native library directory: $nativeLibraryDir")
            AiLogHelper.i(TAG, "✅ PROCESS BUILDER: Native library directory resolved: $nativeLibraryDir")
            nativeLibraryDir
        } catch (e: Exception) {
            val errorMsg = "Failed to get native library directory: ${e.message}"
            Log.e(TAG, errorMsg, e)
            AiLogHelper.e(TAG, "❌ PROCESS BUILDER: $errorMsg", e)
            throw IOException(errorMsg, e)
        }
        
        // Construct path to libxray.so in native library directory
        val executableFile = File(nativeDir, "libxray.so")
        val executablePath = executableFile.absolutePath
        
        // Validate executable exists before attempting execution
        if (!executableFile.exists()) {
            val errorMsg = "Native library not found at $executablePath. " +
                    "Expected location: $nativeDir/libxray.so. " +
                    "This may indicate a split APK issue or missing native library."
            Log.e(TAG, errorMsg)
            AiLogHelper.e(TAG, "❌ PROCESS BUILDER: $errorMsg")
            
            // Optional fallback: Try legacy filesDir copy (rare case for split APKs)
            val legacyPath = File(filesDir, "libxray.so")
            if (legacyPath.exists() && legacyPath.canRead()) {
                Log.w(TAG, "⚠️ Fallback: Found libxray.so in filesDir, but execution will likely fail on Android 10+ due to noexec mount")
                AiLogHelper.w(TAG, "⚠️ PROCESS BUILDER: Fallback to filesDir detected (may fail on Android 10+)")
                // Note: This will likely fail on Android 10+, but we try anyway for compatibility
                return createProcessBuilderWithPath(legacyPath.absolutePath, filesDir)
            }
            
            throw IOException(errorMsg)
        }
        
        // Check if file is readable
        if (!executableFile.canRead()) {
            val errorMsg = "Native library is not readable at $executablePath"
            Log.e(TAG, errorMsg)
            AiLogHelper.e(TAG, "❌ PROCESS BUILDER: $errorMsg")
            throw IOException(errorMsg)
        }
        
        // Log executable details for debugging
        Log.i(TAG, "✅ Executable found at: $executablePath")
        Log.d(TAG, "Executable size: ${executableFile.length()} bytes")
        Log.d(TAG, "Executable permissions: readable=${executableFile.canRead()}, executable=${executableFile.canExecute()}")
        AiLogHelper.i(TAG, "✅ PROCESS BUILDER: Executable validated - path: $executablePath, size: ${executableFile.length()} bytes")
        
        return createProcessBuilderWithPath(executablePath, filesDir)
    }
    
    /**
     * Create ProcessBuilder with the specified executable path.
     * 
     * @param executablePath Path to libxray.so executable
     * @param filesDir Application files directory (for assets and config)
     * @return Configured ProcessBuilder
     */
    private fun createProcessBuilderWithPath(executablePath: String, filesDir: File): ProcessBuilder {
        // CRITICAL: Execute libxray.so directly from nativeLibraryDir
        // Direct execution works because nativeLibraryDir has execution permissions
        // We do NOT use the linker (/system/bin/linker) as it's not needed for nativeLibraryDir
        val command: MutableList<String> = mutableListOf(executablePath, "run")
        
        Log.i(TAG, "🚀 Command: ${command.joinToString(" ")}")
        AiLogHelper.i(TAG, "🚀 PROCESS BUILDER: Command prepared - ${command.joinToString(" ")}")
        AiLogHelper.d(TAG, "🔧 PROCESS BUILDER: Executing directly from nativeLibraryDir (no linker needed)")
        
        val processBuilder = ProcessBuilder(command)
        val environment = processBuilder.environment()
        
        // CRITICAL: XRAY_LOCATION_ASSET must point to filesDir where geoip.dat, geosite.dat are located
        // The binary runs from nativeLibraryDir, but assets are in filesDir
        environment["XRAY_LOCATION_ASSET"] = filesDir.absolutePath
        Log.d(TAG, "XRAY_LOCATION_ASSET set to: ${filesDir.absolutePath}")
        AiLogHelper.d(TAG, "✅ PROCESS BUILDER: XRAY_LOCATION_ASSET=${filesDir.absolutePath}")
        
        // Set working directory to filesDir (where config and assets are)
        processBuilder.directory(filesDir)
        processBuilder.redirectErrorStream(true)
        
        Log.d(TAG, "ProcessBuilder configured - executable: $executablePath, workingDir: ${filesDir.absolutePath}")
        AiLogHelper.d(TAG, "✅ PROCESS BUILDER: ProcessBuilder configured successfully")
        
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
    
    /**
     * Pre-resolve server addresses (VLESS/VMess and WireGuard) using system DNS.
     * Replaces domain names with IP addresses in the config JSON to prevent DNS bootstrap failures.
     * 
     * @param configContent Original config JSON string
     * @return Modified config JSON string with resolved IP addresses, or original if no changes needed
     */
    private suspend fun preResolveServerAddress(configContent: String): String {
        var modifiedConfig = configContent
        
        try {
            val jsonObject = org.json.JSONObject(configContent)
            val outbounds = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
            if (outbounds == null || outbounds.length() == 0) {
                return modifiedConfig
            }
            
            var configModified = false
            
            // Process each outbound
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.getJSONObject(i)
                val protocol = outbound.optString("protocol", "")
                val settings = outbound.optJSONObject("settings")
                
                    // Handle VLESS/VMess protocols
                    if (settings != null) {
                        val vnext = settings.optJSONArray("vnext")
                        if (vnext != null && vnext.length() > 0) {
                            val server = vnext.getJSONObject(0)
                            val address = server.optString("address", "")
                            if (address.isNotEmpty() && !isValidIpAddress(address)) {
                                // Domain name found, resolve it
                                val resolvedResult = resolveDomainToIpAndCache(address)
                                if (resolvedResult != null) {
                                    val (resolvedIp, resolvedAddresses) = resolvedResult
                                    server.put("address", resolvedIp)
                                    configModified = true
                                    Log.i(TAG, "✅ Pre-resolved VLESS/VMess address: $address -> $resolvedIp")
                                    AiLogHelper.i(TAG, "✅ DNS PRE-RESOLVE: VLESS/VMess $address -> $resolvedIp (cached in DnsCacheManager)")
                                }
                            }
                        }
                    
                    // Handle WireGuard protocol
                    if (protocol == "wireguard") {
                        Log.d(TAG, "🔍 Found WireGuard outbound, checking for endpoint...")
                        AiLogHelper.d(TAG, "🔍 DNS PRE-RESOLVE: Found WireGuard outbound, checking for endpoint...")
                        val peers = settings.optJSONArray("peers")
                        if (peers != null && peers.length() > 0) {
                            Log.d(TAG, "🔍 WireGuard has ${peers.length()} peer(s)")
                            for (j in 0 until peers.length()) {
                                val peer = peers.getJSONObject(j)
                                val endpoint = peer.optString("endpoint", "")
                                Log.d(TAG, "🔍 WireGuard peer[$j] endpoint: $endpoint")
                                if (endpoint.isNotEmpty()) {
                                    // Extract domain from endpoint (format: "domain.com:port" or "IP:port")
                                    val endpointParts = endpoint.split(":")
                                    if (endpointParts.size >= 2) {
                                        val domain = endpointParts[0]
                                        val port = endpointParts[1]
                                        
                                        Log.d(TAG, "🔍 WireGuard endpoint parsed: domain=$domain, port=$port")
                                        
                                        if (!isValidIpAddress(domain)) {
                                            // Domain name found, resolve it and cache
                                            Log.i(TAG, "🌐 WireGuard endpoint is domain, resolving: $domain")
                                            AiLogHelper.i(TAG, "🌐 DNS PRE-RESOLVE: WireGuard endpoint is domain, resolving: $domain")
                                            val resolvedResult = resolveDomainToIpAndCache(domain)
                                            if (resolvedResult != null) {
                                                val (resolvedIp, resolvedAddresses) = resolvedResult
                                                // Replace domain with IP in endpoint
                                                val newEndpoint = "$resolvedIp:$port"
                                                peer.put("endpoint", newEndpoint)
                                                configModified = true
                                                Log.i(TAG, "✅ Pre-resolved WireGuard endpoint: $endpoint -> $newEndpoint")
                                                AiLogHelper.i(TAG, "✅ DNS PRE-RESOLVE: WireGuard $endpoint -> $newEndpoint (cached in DnsCacheManager)")
                                            } else {
                                                Log.w(TAG, "⚠️ Failed to resolve WireGuard endpoint domain: $domain")
                                                AiLogHelper.w(TAG, "⚠️ DNS PRE-RESOLVE: Failed to resolve WireGuard endpoint domain: $domain")
                                            }
                                        } else {
                                            Log.d(TAG, "✅ WireGuard endpoint is already IP: $domain (no DNS resolution needed)")
                                            AiLogHelper.d(TAG, "✅ DNS PRE-RESOLVE: WireGuard endpoint is already IP: $domain")
                                        }
                                    } else {
                                        Log.w(TAG, "⚠️ WireGuard endpoint format invalid: $endpoint (expected format: host:port)")
                                        AiLogHelper.w(TAG, "⚠️ DNS PRE-RESOLVE: WireGuard endpoint format invalid: $endpoint")
                                    }
                                } else {
                                    Log.w(TAG, "⚠️ WireGuard peer[$j] has no endpoint configured")
                                    AiLogHelper.w(TAG, "⚠️ DNS PRE-RESOLVE: WireGuard peer[$j] has no endpoint")
                                }
                            }
                        } else {
                            Log.w(TAG, "⚠️ WireGuard outbound has no peers configured")
                            AiLogHelper.w(TAG, "⚠️ DNS PRE-RESOLVE: WireGuard outbound has no peers")
                        }
                    }
                }
            }
            
            // Return modified config if changes were made
            if (configModified) {
                modifiedConfig = jsonObject.toString()
                Log.i(TAG, "✅ Config modified with pre-resolved IP addresses")
                AiLogHelper.i(TAG, "✅ DNS PRE-RESOLVE: Config modified with resolved IP addresses")
            } else {
                Log.d(TAG, "No domain names found to resolve (all addresses are already IPs)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during pre-resolve: ${e.message}", e)
            AiLogHelper.w(TAG, "⚠️ DNS PRE-RESOLVE: Error during pre-resolve: ${e.message}")
            // Return original config on error
        }
        
        return modifiedConfig
    }
    
    /**
     * Resolve a domain name to IP address using system DNS.
     * 
     * @param domain Domain name to resolve
     * @return First resolved IP address, or null if resolution fails
     */
    private suspend fun resolveDomainToIp(domain: String): String? {
        return try {
            val resolvedAddresses = kotlinx.coroutines.withContext(Dispatchers.IO) {
                InetAddress.getAllByName(domain)
            }
            if (resolvedAddresses.isNotEmpty()) {
                // Return first IPv4 address, or first address if no IPv4
                val ipv4 = resolvedAddresses.firstOrNull { it.hostAddress?.contains(".") == true }
                ipv4?.hostAddress ?: resolvedAddresses[0].hostAddress
            } else {
                Log.w(TAG, "⚠️ Domain resolved but no IPs found: $domain")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve domain: $domain (${e.message})")
            null
        }
    }
    
    /**
     * Resolve a domain name to IP address using system DNS and cache it in DnsCacheManager.
     * This is used for WireGuard and VLESS/VMess bootstrap DNS resolution.
     * 
     * @param domain Domain name to resolve
     * @return Pair of (resolved IP address, list of all resolved addresses), or null if resolution fails
     */
    private suspend fun resolveDomainToIpAndCache(domain: String): Pair<String, List<InetAddress>>? {
        return try {
            val resolvedAddresses = kotlinx.coroutines.withContext(Dispatchers.IO) {
                InetAddress.getAllByName(domain)
            }
            if (resolvedAddresses.isNotEmpty()) {
                // Get first IPv4 address, or first address if no IPv4
                val ipv4 = resolvedAddresses.firstOrNull { it.hostAddress?.contains(".") == true }
                val resolvedIp = ipv4?.hostAddress ?: resolvedAddresses[0].hostAddress
                
                if (resolvedIp != null) {
                    // CRITICAL: Cache resolved IP in DnsCacheManager
                    // This ensures SystemDnsCacheServer is aware of the resolved IP
                    try {
                        DnsCacheManager.saveToCache(domain, resolvedAddresses.toList())
                        Log.i(TAG, "✅ Bootstrap DNS: Resolved $domain -> $resolvedIp and cached in DnsCacheManager")
                        AiLogHelper.i(TAG, "✅ DNS BOOTSTRAP: Resolved $domain -> $resolvedIp and cached")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Failed to cache resolved IP for $domain: ${e.message}", e)
                        AiLogHelper.w(TAG, "⚠️ DNS BOOTSTRAP: Failed to cache $domain: ${e.message}")
                    }
                    
                    return Pair(resolvedIp, resolvedAddresses.toList())
                } else {
                    Log.w(TAG, "⚠️ Domain resolved but no valid IP found: $domain")
                    null
                }
            } else {
                Log.w(TAG, "⚠️ Domain resolved but no IPs found: $domain")
                null
            }
        } catch (e: java.net.UnknownHostException) {
            Log.w(TAG, "⚠️ Failed to resolve domain: $domain (UnknownHostException: ${e.message})")
            AiLogHelper.w(TAG, "⚠️ DNS BOOTSTRAP: Failed to resolve $domain: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to resolve domain: $domain (${e.message})", e)
            AiLogHelper.w(TAG, "⚠️ DNS BOOTSTRAP: Error resolving $domain: ${e.message}")
            null
        }
    }
    
    /**
     * Extract server address from config (for logging/debugging purposes).
     * Supports both VLESS/VMess (vnext) and WireGuard (peers endpoint) protocols.
     * 
     * @param configContent Config JSON string
     * @return Server address/domain if found, null otherwise
     */
    private fun extractServerAddressFromConfig(configContent: String): String? {
        return try {
            val jsonObject = org.json.JSONObject(configContent)
            val outbounds = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
            if (outbounds != null && outbounds.length() > 0) {
                val outbound = outbounds.getJSONObject(0)
                val protocol = outbound.optString("protocol", "")
                val settings = outbound.optJSONObject("settings")
                
                // Check VLESS/VMess protocols
                val vnext = settings?.optJSONArray("vnext")
                if (vnext != null && vnext.length() > 0) {
                    val server = vnext.getJSONObject(0)
                    val address = server.optString("address", "")
                    if (address.isNotEmpty()) {
                        return address
                    }
                }
                
                // Check WireGuard protocol
                if (protocol == "wireguard") {
                    val peers = settings?.optJSONArray("peers")
                    if (peers != null && peers.length() > 0) {
                        val peer = peers.getJSONObject(0)
                        val endpoint = peer.optString("endpoint", "")
                        if (endpoint.isNotEmpty()) {
                            // Extract domain from endpoint (format: "domain.com:port")
                            val endpointParts = endpoint.split(":")
                            if (endpointParts.isNotEmpty()) {
                                return endpointParts[0]
                            }
                        }
                    }
                }
            }
            null
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



