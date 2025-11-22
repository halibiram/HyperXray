package com.hyperxray.an.xray.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import kotlin.concurrent.Volatile
import org.json.JSONException
import org.json.JSONObject

/**
 * Service for managing Xray-core binary lifecycle.
 * 
 * This service provides a safe, controlled interface for starting, stopping,
 * and restarting the Xray-core binary process. It exposes status events
 * through StateFlow for reactive programming.
 * 
 * Thread-safety: All public methods are thread-safe and can be called from
 * any thread. Internal state is protected with @Volatile and synchronization.
 * 
 * @param context Android context for accessing native library directory
 */
class XrayRuntimeService(private val context: Context) : XrayRuntimeServiceApi {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    @Volatile
    private var xrayProcess: Process? = null
    
    @Volatile
    private var isStopping = false
    
    @Volatile
    private var isStarting = false
    
    @Volatile
    private var logLineCallback: LogLineCallback? = null
    
    private val _status = MutableStateFlow<XrayRuntimeStatus>(XrayRuntimeStatus.Stopped)
    
    /**
     * Current runtime status of Xray-core.
     * 
     * This StateFlow emits status updates whenever the Xray-core process
     * state changes. Observers can collect from this flow to react to
     * status changes.
     * 
     * Example:
     * ```
     * service.status.collect { status ->
     *     when (status) {
     *         is XrayRuntimeStatus.Running -> println("Xray is running")
     *         is XrayRuntimeStatus.Error -> println("Error: ${status.message}")
     *         else -> {}
     *     }
     * }
     * ```
     */
    override val status: StateFlow<XrayRuntimeStatus> = _status.asStateFlow()
    
    companion object {
        private const val TAG = "XrayRuntimeService"
        
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
     * Start Xray-core with the provided configuration.
     * 
     * This method will:
     * 1. Validate the configuration file path
     * 2. Find an available API port
     * 3. Launch the Xray-core process
     * 4. Write configuration to stdin
     * 5. Monitor the process and update status
     * 
     * @param configPath Path to the Xray configuration JSON file
     * @param configContent Optional pre-read configuration content (for security)
     * @param apiPort Optional API port (if null, will find available port)
     * @return The API port number used, or null if startup failed
     */
    @Synchronized
    override fun start(
        configPath: String,
        configContent: String?,
        apiPort: Int?
    ): Int? {
        // Prevent concurrent start calls
        if (isStarting) {
            Log.w(TAG, "Start already in progress, ignoring duplicate call")
            return null
        }
        
        if (isStopping) {
            Log.w(TAG, "Cannot start while stopping")
            return null
        }
        
        // Check if process is already running and alive
        val currentProcess = xrayProcess
        if (currentProcess != null && currentProcess.isAlive) {
            val currentStatus = _status.value
            if (currentStatus is XrayRuntimeStatus.Running) {
                Log.w(TAG, "Process already running (pid=${(currentStatus as XrayRuntimeStatus.Running).processId}), ignoring start request")
                return (currentStatus as? XrayRuntimeStatus.Running)?.apiPort
            } else {
                // Process is alive but not in Running state - might be in transition
                Log.w(TAG, "Process exists but not in Running state (status=$currentStatus), stopping it first")
                try {
                    currentProcess.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying existing process: ${e.message}")
                }
            }
        }
        
        val currentStatus = _status.value
        if (currentStatus is XrayRuntimeStatus.Running) {
            Log.w(TAG, "Already running, ignoring start request")
            return (currentStatus as? XrayRuntimeStatus.Running)?.apiPort
        }
        
        isStarting = true
        _status.value = XrayRuntimeStatus.Starting
        
        return try {
            val libraryDir = getNativeLibraryDir(context)
            if (libraryDir == null) {
                val errorMessage = "Failed to get native library directory"
                Log.e(TAG, errorMessage)
                _status.value = XrayRuntimeStatus.Error(errorMessage)
                isStarting = false
                return null
            }
            
            // Validate config path
            val configFile = validateConfigPath(configPath)
            if (configFile == null) {
                val errorMessage = "Invalid configuration file: path validation failed or file not accessible"
                Log.e(TAG, errorMessage)
                _status.value = XrayRuntimeStatus.Error(errorMessage)
                isStarting = false
                return null
            }
            
            // Read config content if not provided
            val finalConfigContent = configContent ?: readConfigContentSecurely(configFile)
            if (finalConfigContent == null) {
                val errorMessage = "Failed to read configuration file"
                Log.e(TAG, errorMessage)
                _status.value = XrayRuntimeStatus.Error(errorMessage)
                isStarting = false
                return null
            }
            
            // CRITICAL: Validate JSON format before starting process
            // This prevents Xray from crashing immediately after config write
            if (!validateJsonConfig(finalConfigContent)) {
                val errorMessage = "Invalid JSON configuration format"
                Log.e(TAG, errorMessage)
                _status.value = XrayRuntimeStatus.Error(errorMessage)
                isStarting = false
                return null
            }
            
            // Find available port if not provided
            val finalApiPort = apiPort ?: findAvailablePort(extractPortsFromJson(finalConfigContent))
            if (finalApiPort == null) {
                val errorMessage = "Failed to find available port"
                Log.e(TAG, errorMessage)
                _status.value = XrayRuntimeStatus.Error(errorMessage)
                isStarting = false
                return null
            }
            
            // Start the process
            // NOTE: isStarting will be reset in runXrayProcess() after process actually starts or fails
            serviceScope.launch {
                runXrayProcess(libraryDir, configFile, finalConfigContent, finalApiPort)
            }
            
            // Don't reset isStarting here - it will be reset in runXrayProcess()
            finalApiPort
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Xray", e)
            _status.value = XrayRuntimeStatus.Error("Failed to start: ${e.message}", e)
            isStarting = false
            null
        }
    }
    
    /**
     * Stop Xray-core gracefully.
     * 
     * This method will:
     * 1. Signal graceful shutdown by closing stdin
     * 2. Wait for process to finish current operations
     * 3. Destroy the process if still alive
     * 4. Update status to Stopped
     */
    override fun stop() {
        if (isStopping) {
            Log.d(TAG, "Stop already in progress, ignoring duplicate call")
            return
        }
        
        isStopping = true
        _status.value = XrayRuntimeStatus.Stopping
        
        try {
            val processToDestroy = xrayProcess
            
            // Signal graceful shutdown
            if (processToDestroy != null && processToDestroy.isAlive) {
                try {
                    processToDestroy.outputStream?.close()
                    Log.d(TAG, "Closed stdin to signal graceful shutdown")
                } catch (e: Exception) {
                    Log.d(TAG, "Error closing stdin: ${e.message}")
                }
                
                // Grace period for UDP operations
                try {
                    Thread.sleep(400)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            
            // Cancel coroutine scope
            serviceScope.cancel()
            
            // Destroy process
            if (processToDestroy != null) {
                try {
                    processToDestroy.destroy()
                    Thread.sleep(500)
                    if (processToDestroy.isAlive) {
                        Log.d(TAG, "Process still alive, forcing destroy")
                        processToDestroy.destroyForcibly()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error destroying process", e)
                }
                xrayProcess = null
            }
            
            _status.value = XrayRuntimeStatus.Stopped
        } catch (e: Exception) {
            Log.e(TAG, "Error during stop", e)
            _status.value = XrayRuntimeStatus.Error("Failed to stop: ${e.message}", e)
        } finally {
            isStopping = false
        }
    }
    
    /**
     * Restart Xray-core with new configuration.
     * 
     * This is equivalent to calling stop() followed by start().
     * 
     * @param configPath Path to the Xray configuration JSON file
     * @param configContent Optional pre-read configuration content
     * @param apiPort Optional API port
     * @return The API port number used, or null if restart failed
     */
    override fun restart(
        configPath: String,
        configContent: String?,
        apiPort: Int?
    ): Int? {
        Log.d(TAG, "Restarting Xray-core")
        stop()
        
        // Wait a bit for cleanup
        try {
            Thread.sleep(200)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        
        return start(configPath, configContent, apiPort)
    }
    
    /**
     * Check if Xray-core is currently running.
     * 
     * @return true if running, false otherwise
     */
    override fun isRunning(): Boolean {
        return _status.value is XrayRuntimeStatus.Running && 
               xrayProcess?.isAlive == true
    }
    
    /**
     * Get the current process ID if running.
     * 
     * @return Process ID, or null if not running
     */
    override fun getProcessId(): Long? {
        val currentStatus = _status.value
        return if (currentStatus is XrayRuntimeStatus.Running) {
            currentStatus.processId
        } else {
            null
        }
    }
    
    /**
     * Get the current API port if running.
     * 
     * @return API port number, or null if not running
     */
    override fun getApiPort(): Int? {
        val currentStatus = _status.value
        return if (currentStatus is XrayRuntimeStatus.Running) {
            currentStatus.apiPort
        } else {
            null
        }
    }
    
    /**
     * Cleanup resources. Call this when the service is no longer needed.
     */
    override fun cleanup() {
        stop()
        serviceScope.cancel()
    }
    
    /**
     * Set callback for log lines from Xray process.
     */
    override fun setLogLineCallback(callback: LogLineCallback?) {
        logLineCallback = callback
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
     * Validates that the config content is valid JSON.
     * This prevents Xray from crashing immediately after config write due to invalid JSON.
     * 
     * @param configContent The config content to validate
     * @return true if valid JSON, false otherwise
     */
    private fun validateJsonConfig(configContent: String): Boolean {
        return try {
            // Try to parse as JSON
            org.json.JSONObject(configContent)
            true
        } catch (e: org.json.JSONException) {
            Log.e(TAG, "Invalid JSON config format: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error validating JSON config: ${e.message}")
            false
        }
    }
    
    private fun extractPortsFromJson(jsonContent: String): Set<Int> {
        // Simple regex-based extraction of port numbers
        // This is a basic implementation - can be enhanced
        val portPattern = Regex("""["']port["']\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
        return portPattern.findAll(jsonContent)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .toSet()
    }
    
    private fun findAvailablePort(excludedPorts: Set<Int>): Int? {
        (10000..65535)
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
    
    private suspend fun runXrayProcess(
        libraryDir: String,
        configFile: File,
        configContent: String,
        apiPort: Int
    ) {
        var currentProcess: Process? = null
        try {
            Log.d(TAG, "Starting Xray-core process")
            
            val xrayPath = "$libraryDir/libxray.so"
            val processBuilder = createProcessBuilder(xrayPath)
            currentProcess = processBuilder.start()
            xrayProcess = currentProcess
            
            // Validate process startup
            var checksPerformed = 0
            val checkInterval = 50L
            val minStartupChecks = 2
            val maxStartupChecks = 100
            
            while (checksPerformed < maxStartupChecks) {
                delay(checkInterval)
                checksPerformed++
                
                if (!currentProcess.isAlive) {
                    val exitValue = try {
                        currentProcess.exitValue()
                    } catch (e: IllegalThreadStateException) {
                        -1
                    }
                    val errorMessage = "Xray process exited during startup (exit code: $exitValue)"
                    Log.e(TAG, errorMessage)
                    _status.value = XrayRuntimeStatus.ProcessExited(exitValue, errorMessage)
                    isStarting = false
                    return
                }
                
                if (checksPerformed >= minStartupChecks) {
                    Log.d(TAG, "Process startup validated after ${checksPerformed * checkInterval}ms")
                    break
                }
            }
            
            // CRITICAL: Start reading process output IMMEDIATELY (before config write)
            // This allows us to capture error messages even if process crashes immediately after config write
            val capturedOutput = mutableListOf<String>()
            val processOutputJob = serviceScope.launch {
                readProcessStreamWithCapture(currentProcess, capturedOutput)
            }
            
            // Small delay to allow process to potentially output startup errors
            delay(100)
            
            // Write config to stdin
            Log.d(TAG, "Writing config to Xray stdin")
            try {
                currentProcess.outputStream.use { os ->
                    os.write(configContent.toByteArray())
                    os.flush()
                }
                Log.d(TAG, "Config written to Xray stdin successfully")
            } catch (e: IOException) {
                if (!currentProcess.isAlive) {
                    val exitValue = try { currentProcess.exitValue() } catch (ex: IllegalThreadStateException) { -1 }
                    Log.e(TAG, "Xray process exited while writing config, exit code: $exitValue")
                    
                    // Wait a moment for output capture to collect error messages
                    delay(200)
                    processOutputJob.cancel()
                    
                    val errorOutput = capturedOutput.joinToString("\n")
                    val errorMessage = if (errorOutput.isNotEmpty()) {
                        "Xray process exited during config write (exit code: $exitValue)\n\n" +
                        "Xray error output:\n$errorOutput"
                    } else {
                        "Xray process exited during config write (exit code: $exitValue)\n\n" +
                        "No error output captured. Possible causes:\n" +
                        "1. Invalid JSON config format\n" +
                        "2. Missing required config fields\n" +
                        "3. Invalid inbound/outbound configuration"
                    }
                    
                    Log.e(TAG, errorMessage)
                    _status.value = XrayRuntimeStatus.ProcessExited(exitValue, errorMessage)
                    isStarting = false
                } else {
                    processOutputJob.cancel()
                }
                throw e
            }
            
            // CRITICAL: Check if process is still alive after config write
            // Xray may exit immediately if config is invalid
            // Use periodic checks instead of single delay to catch early exits faster
            var postConfigChecks = 0
            val maxPostConfigChecks = 20 // 20 * 100ms = 2 seconds total
            val postConfigCheckInterval = 100L
            
            while (postConfigChecks < maxPostConfigChecks) {
                delay(postConfigCheckInterval)
                postConfigChecks++
                
                if (!currentProcess.isAlive) {
                    val exitValue = try {
                        currentProcess.exitValue()
                    } catch (e: IllegalThreadStateException) {
                        -1
                    }
                    
                    // Wait a moment for output capture to collect error messages
                    delay(300)
                    processOutputJob.cancel()
                    
                    val errorOutput = capturedOutput.joinToString("\n")
                    val errorMessage = if (errorOutput.isNotEmpty()) {
                        "Xray process exited immediately after config write (exit code: $exitValue, after ${postConfigChecks * postConfigCheckInterval}ms)\n\n" +
                        "Xray error output:\n$errorOutput"
                    } else {
                        "Xray process exited immediately after config write (exit code: $exitValue, after ${postConfigChecks * postConfigCheckInterval}ms)\n\n" +
                        "No error output captured. Possible causes:\n" +
                        "1. Invalid JSON config format\n" +
                        "2. Missing required config fields\n" +
                        "3. Invalid inbound/outbound configuration\n" +
                        "4. File permissions issue\n" +
                        "5. Missing geoip/geosite files\n" +
                        "6. Port conflict or binding error"
                    }
                    
                    Log.e(TAG, errorMessage)
                    _status.value = XrayRuntimeStatus.ProcessExited(exitValue, errorMessage)
                    isStarting = false
                    return@runXrayProcess
                }
                
                // If process is still alive after reasonable time, assume it's starting successfully
                if (postConfigChecks >= 5) { // After 500ms, if still alive, likely OK
                    Log.d(TAG, "Process still alive after ${postConfigChecks * postConfigCheckInterval}ms, assuming successful startup")
                    break
                }
            }
            
            // Update status to Running
            val processId = try {
                // Get process ID using reflection (Android doesn't expose this directly)
                val pidField = currentProcess.javaClass.getDeclaredField("pid")
                pidField.isAccessible = true
                pidField.getLong(currentProcess)
            } catch (e: Exception) {
                Log.w(TAG, "Could not get process ID: ${e.message}")
                0L
            }
            
            _status.value = XrayRuntimeStatus.Running(processId, apiPort)
            // Reset isStarting flag now that process is successfully running
            isStarting = false
            Log.d(TAG, "Xray-core started successfully (pid=$processId, apiPort=$apiPort)")
            
            // Monitor process output continues in background job
            // CRITICAL FIX: Wait for process to exit. 
            // If we don't wait here, the method returns, finally block executes, 
            // and status is set to ProcessExited while process is actually running.
            currentProcess.waitFor()
            Log.d(TAG, "Xray process exited naturally")
            
        } catch (e: InterruptedIOException) {
            Log.d(TAG, "Xray process reading interrupted")
            isStarting = false
        } catch (e: Exception) {
            Log.e(TAG, "Error executing Xray", e)
            _status.value = XrayRuntimeStatus.Error("Process execution failed: ${e.message}", e)
            isStarting = false
        } finally {
            // Ensure isStarting is always reset in finally block
            if (isStarting) {
                // Only reset if we haven't already done so
                // This handles edge cases where we might have missed a reset
                val currentStatus = _status.value
                if (currentStatus !is XrayRuntimeStatus.Running && currentStatus !is XrayRuntimeStatus.Starting) {
                    isStarting = false
                }
            }
            
            if (xrayProcess === currentProcess) {
                xrayProcess = null
            }
            
            if (!isStopping) {
                val exitValue = try {
                    currentProcess?.exitValue() ?: -1
                } catch (e: IllegalThreadStateException) {
                    -1
                }
                // Only set ProcessExited if we haven't already set a status
                val currentStatus = _status.value
                if (currentStatus !is XrayRuntimeStatus.ProcessExited && 
                    currentStatus !is XrayRuntimeStatus.Error &&
                    currentStatus !is XrayRuntimeStatus.Running) {
                    _status.value = XrayRuntimeStatus.ProcessExited(exitValue, "Process exited unexpectedly")
                }
            }
        }
    }
    
    private fun createProcessBuilder(xrayPath: String): ProcessBuilder {
        val filesDir = context.filesDir
        
        val libxrayFile = File(xrayPath)
        if (!libxrayFile.exists()) {
            throw IOException("libxray.so not found at: $xrayPath")
        }
        
        val linkerPath = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) {
            "/system/bin/linker64"
        } else {
            "/system/bin/linker"
        }
        
        Log.i(TAG, "Using linker: $linkerPath to execute: $xrayPath")
        
        val command = mutableListOf(linkerPath, xrayPath)
        // Removed "run" argument as it might interfere with stdin config reading or cause issues on some Android versions
        // val command = mutableListOf(linkerPath, xrayPath, "run")
        val processBuilder = ProcessBuilder(command)
        val environment = processBuilder.environment()
        environment["XRAY_LOCATION_ASSET"] = filesDir.path
        processBuilder.directory(filesDir)
        processBuilder.redirectErrorStream(true)
        return processBuilder
    }
    
    private suspend fun readProcessStream(process: Process) {
        // Read process output and forward to callback if set
        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    // Forward to callback if set
                    logLineCallback?.onLogLine(line)
                    // Also log to Android Log for debugging
                    Log.i(TAG, "Xray: $line")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation is expected when stopping
            throw e
        } catch (e: Exception) {
            if (!isStopping) {
                Log.e(TAG, "Stream reading finished with error: ${e.message}", e)
            }
        }
    }
    
    /**
     * Reads process output and captures it to a list for error analysis.
     * This is used during startup to capture error messages if process crashes.
     * 
     * @param process The process to read from
     * @param capturedOutput List to store captured output lines
     */
    private suspend fun readProcessStreamWithCapture(process: Process, capturedOutput: MutableList<String>) {
        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    // Capture line for error analysis
                    synchronized(capturedOutput) {
                        capturedOutput.add(line)
                        // Keep only last 50 lines to prevent memory issues
                        if (capturedOutput.size > 50) {
                            capturedOutput.removeAt(0)
                        }
                    }
                    // Forward to callback if set
                    logLineCallback?.onLogLine(line)
                    // Also log to Android Log for debugging
                    Log.i(TAG, "Xray: $line")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation is expected when stopping
            throw e
        } catch (e: Exception) {
            if (!isStopping) {
                Log.e(TAG, "Stream reading finished with error: ${e.message}", e)
            }
        }
    }
}

