package com.hyperxray.an.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hyperxray.an.BuildConfig
import com.hyperxray.an.R
import com.hyperxray.an.activity.MainActivity
import com.hyperxray.an.common.ConfigUtils
import com.hyperxray.an.common.ConfigUtils.extractPortsFromJson
import com.hyperxray.an.data.source.LogFileManager
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.telemetry.TProxyAiOptimizer
import com.hyperxray.an.viewmodel.CoreStatsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.net.ServerSocket
import kotlin.concurrent.Volatile

/**
 * VPN service that manages Xray-core process execution and TUN interface.
 * Handles connection lifecycle, log streaming, and configuration management.
 */
class TProxyService : VpnService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val logBroadcastBuffer: MutableList<String> = mutableListOf()
    private val broadcastLogsRunnable = Runnable {
        synchronized(logBroadcastBuffer) {
            if (logBroadcastBuffer.isNotEmpty()) {
                val logUpdateIntent = Intent(ACTION_LOG_UPDATE)
                logUpdateIntent.setPackage(application.packageName)
                logUpdateIntent.putStringArrayListExtra(
                    EXTRA_LOG_DATA, ArrayList(logBroadcastBuffer)
                )
                sendBroadcast(logUpdateIntent)
                logBroadcastBuffer.clear()
                Log.d(TAG, "Broadcasted a batch of logs.")
            }
        }
    }

    private fun findAvailablePort(excludedPorts: Set<Int>): Int? {
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

    private lateinit var logFileManager: LogFileManager

    @Volatile
    private var xrayProcess: Process? = null
    @Volatile
    private var tunFd: ParcelFileDescriptor? = null
    private val tunFdLock = Any() // Synchronization lock for tunFd access

    @Volatile
    private var reloadingRequested = false
    
    @Volatile
    private var isStopping = false
    
    // AI-powered TProxy optimizer
    private var tproxyAiOptimizer: TProxyAiOptimizer? = null
    private var coreStatsState: CoreStatsState? = null

    override fun onCreate() {
        super.onCreate()
        logFileManager = LogFileManager(this)
        
        // Initialize AI-powered TProxy optimizer
        val prefs = Preferences(this)
        tproxyAiOptimizer = TProxyAiOptimizer(this, prefs)
        Log.d(TAG, "TProxyService created with AI optimizer.")
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val action = intent.action
        when (action) {
            ACTION_DISCONNECT -> {
                stopXray()
                return START_NOT_STICKY
            }

            ACTION_RELOAD_CONFIG -> {
                val prefs = Preferences(this)
                if (prefs.disableVpn) {
                    Log.d(TAG, "Received RELOAD_CONFIG action (core-only mode)")
                    reloadingRequested = true
                    xrayProcess?.destroy()
                    serviceScope.launch { runXrayProcess() }
                    return START_STICKY
                }
                if (tunFd == null) {
                    Log.w(TAG, "Cannot reload config, VPN service is not running.")
                    return START_STICKY
                }
                Log.d(TAG, "Received RELOAD_CONFIG action.")
                reloadingRequested = true
                xrayProcess?.destroy()
                serviceScope.launch { runXrayProcess() }
                return START_STICKY
            }

            ACTION_START -> {
                logFileManager.clearLogs()
                val prefs = Preferences(this)
                if (prefs.disableVpn) {
                    serviceScope.launch { runXrayProcess() }
                    val successIntent = Intent(ACTION_START)
                    successIntent.setPackage(application.packageName)
                    sendBroadcast(successIntent)

                    @Suppress("SameParameterValue") val channelName = "nosocks"
                    initNotificationChannel(channelName)
                    createNotification(channelName)

                } else {
                    startXray()
                }
                return START_STICKY
            }

            else -> {
                logFileManager.clearLogs()
                startXray()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called, cleaning up resources.")
        
        // Stop Xray and clean up all resources
        stopXray()
        
        // Remove any pending log broadcasts
        handler.removeCallbacks(broadcastLogsRunnable)
        broadcastLogsRunnable.run()
        
        // Stop AI optimizer
        tproxyAiOptimizer?.stopOptimization()
        tproxyAiOptimizer = null
        
        Log.d(TAG, "TProxyService destroyed.")
        // Let Android handle service lifecycle - do not call exitProcess(0)
    }

    override fun onRevoke() {
        stopXray()
        super.onRevoke()
    }

    private fun startXray() {
        startService()
        serviceScope.launch { runXrayProcess() }
    }

    /**
     * Validates that a config file path is within the app's private directory.
     * Prevents path traversal attacks and ensures config files are secure.
     * 
     * @param configPath The path to validate
     * @return The validated File object, or null if validation fails
     */
    private fun validateConfigPath(configPath: String?): File? {
        if (configPath == null) {
            Log.e(TAG, "Config path is null")
            return null
        }
        
        try {
            val configFile = File(configPath)
            
            // Check if file exists
            if (!configFile.exists()) {
                Log.e(TAG, "Config file does not exist: $configPath")
                return null
            }
            
            // Check if it's a file (not a directory)
            if (!configFile.isFile) {
                Log.e(TAG, "Config path is not a file: $configPath")
                return null
            }
            
            // Get canonical paths to prevent path traversal attacks
            val canonicalConfigPath = configFile.canonicalPath
            val privateDir = applicationContext.filesDir
            val canonicalPrivateDir = privateDir.canonicalPath
            
            // Validate that config file is within app's private directory
            if (!canonicalConfigPath.startsWith(canonicalPrivateDir)) {
                Log.e(TAG, "Config file is outside private directory: $canonicalConfigPath (private dir: $canonicalPrivateDir)")
                return null
            }
            
            // Check if file is readable
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
    
    /**
     * Reads config content securely after validation.
     * This prevents TOCTOU (Time-of-Check-Time-of-Use) race conditions.
     * 
     * @param configFile The validated config file
     * @return The config content, or null if reading fails
     */
    private fun readConfigContentSecurely(configFile: File): String? {
        try {
            // Re-validate file is still valid and readable (defense in depth)
            if (!configFile.exists() || !configFile.isFile || !configFile.canRead()) {
                Log.e(TAG, "Config file validation failed during read: ${configFile.canonicalPath}")
                return null
            }
            
            // Read content atomically
            return configFile.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading config file: ${configFile.canonicalPath}", e)
            return null
        }
    }

    private fun runXrayProcess() {
        var currentProcess: Process? = null
        try {
            Log.d(TAG, "Attempting to start xray process.")
            val libraryDir = getNativeLibraryDir(applicationContext)
            val prefs = Preferences(applicationContext)
            if (libraryDir == null) {
                val errorMessage = "Failed to get native library directory."
                Log.e(TAG, errorMessage)
                // Broadcast error to UI
                val errorIntent = Intent(ACTION_ERROR)
                errorIntent.setPackage(application.packageName)
                errorIntent.putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
                sendBroadcast(errorIntent)
                // Stop the service since we can't proceed without library directory
                handler.post {
                    if (!isStopping) {
                        stopXray()
                    }
                }
                return
            }
            
            // Validate config path is within app's private directory
            val selectedConfigPath = prefs.selectedConfigPath
            val configFile = validateConfigPath(selectedConfigPath)
            if (configFile == null) {
                val errorMessage = "Invalid configuration file: path validation failed or file not accessible."
                Log.e(TAG, errorMessage)
                // Broadcast error to UI
                val errorIntent = Intent(ACTION_ERROR)
                errorIntent.setPackage(application.packageName)
                errorIntent.putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
                sendBroadcast(errorIntent)
                // Stop the service since we can't proceed without a valid config
                handler.post {
                    if (!isStopping) {
                        stopXray()
                    }
                }
                return
            }
            
            // Read config content securely (after validation)
            val configContent = readConfigContentSecurely(configFile)
            if (configContent == null) {
                val errorMessage = "Failed to read configuration file."
                Log.e(TAG, errorMessage)
                // Broadcast error to UI
                val errorIntent = Intent(ACTION_ERROR)
                errorIntent.setPackage(application.packageName)
                errorIntent.putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
                sendBroadcast(errorIntent)
                // Stop the service since we can't proceed without config content
                handler.post {
                    if (!isStopping) {
                        stopXray()
                    }
                }
                return
            }
            
            // Use libxray.so directly with Android linker
            val xrayPath = "$libraryDir/libxray.so"
            val excludedPorts = extractPortsFromJson(configContent)
            val apiPort = findAvailablePort(excludedPorts)
            if (apiPort == null) {
                val errorMessage = "Failed to find available port. All ports in range 10000-65535 are in use or excluded."
                Log.e(TAG, errorMessage)
                // Broadcast error to UI
                val errorIntent = Intent(ACTION_ERROR)
                errorIntent.setPackage(application.packageName)
                errorIntent.putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
                sendBroadcast(errorIntent)
                // Stop the service since we can't proceed without a port
                handler.post {
                    if (!isStopping) {
                        stopXray()
                    }
                }
                return
            }
            prefs.apiPort = apiPort
            Log.d(TAG, "Found and set API port: $apiPort")

            val processBuilder = getProcessBuilder(xrayPath)
            currentProcess = processBuilder.start()
            this.xrayProcess = currentProcess

            // Validate process startup with periodic checks to catch early exits
            // Check process status multiple times instead of fixed sleep to detect failures quickly
            val checkInterval = 50L // Check every 50ms
            val minStartupChecks = 2 // Minimum 2 checks (100ms) before considering process started
            val maxStartupChecks = 100 // Maximum 100 checks (5 seconds) as safety timeout
            
            var checksPerformed = 0
            var processValidated = false
            
            // Periodically check if process stays alive during startup
            while (checksPerformed < maxStartupChecks) {
                Thread.sleep(checkInterval)
                checksPerformed++
                
                // Check if process exited during startup
                if (!currentProcess.isAlive) {
                    val exitValue = try {
                        currentProcess.exitValue()
                    } catch (e: IllegalThreadStateException) {
                        -1
                    }
                    val errorMessage = "Xray process exited during startup after ${checksPerformed * checkInterval}ms (exit code: $exitValue)"
                    Log.e(TAG, errorMessage)
                    throw IOException(errorMessage)
                }
                
                // After minimum checks, if process is still alive, consider it started
                // Note: We can't easily verify if process is fully ready, but being alive
                // for a reasonable time (100ms+) is a good indicator it started successfully
                if (checksPerformed >= minStartupChecks) {
                    processValidated = true
                    Log.d(TAG, "Process startup validated after ${checksPerformed * checkInterval}ms")
                    break
                }
            }
            
            // Final validation check
            if (!processValidated) {
                // We hit the maximum check limit - verify process is still alive
                if (!currentProcess.isAlive) {
                    val exitValue = try {
                        currentProcess.exitValue()
                    } catch (e: IllegalThreadStateException) {
                        -1
                    }
                    val errorMessage = "Xray process exited during startup validation (exit code: $exitValue)"
                    Log.e(TAG, errorMessage)
                    throw IOException(errorMessage)
                }
                // Process is alive but we hit timeout - log warning but proceed
                Log.w(TAG, "Process startup validation hit timeout (${maxStartupChecks * checkInterval}ms), but process is alive. Proceeding.")
            }

            Log.d(TAG, "Writing config to xray stdin from: ${configFile.canonicalPath}")
            val injectedConfigContent =
                ConfigUtils.injectStatsService(prefs, configContent)
            
            try {
                currentProcess.outputStream.use { os ->
                    os.write(injectedConfigContent.toByteArray())
                    os.flush()
                }
            } catch (e: IOException) {
                if (!currentProcess.isAlive) {
                    val exitValue = try { currentProcess.exitValue() } catch (ex: IllegalThreadStateException) { -1 }
                    Log.e(TAG, "Xray process exited while writing config, exit code: $exitValue")
                }
                throw e
            }

            // Use robust stream reading with timeout and health checks
            Log.d(TAG, "Reading xray process output with timeout protection.")
            readProcessStreamWithTimeout(currentProcess)
            Log.d(TAG, "xray process output stream finished.")
        } catch (e: InterruptedIOException) {
            Log.d(TAG, "Xray process reading interrupted.")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing xray", e)
        } finally {
            Log.d(TAG, "Xray process task finished.")
            
            // Clean up process reference
            if (this.xrayProcess === currentProcess) {
                this.xrayProcess = null
            } else {
                Log.w(TAG, "Finishing task for an old xray process instance.")
            }
            
            // Only call stopXray if not reloading and not already stopping
            if (reloadingRequested) {
                Log.d(TAG, "Xray process stopped due to configuration reload.")
                reloadingRequested = false
            } else if (!isStopping) {
                Log.d(TAG, "Xray process exited unexpectedly or due to stop request. Stopping VPN.")
                // Use handler to call stopXray on main thread to avoid reentrancy issues
                handler.post {
                    if (!isStopping) {
                        stopXray()
                    }
                }
            }
        }
    }

    /**
     * Reads process output stream with timeout protection and health checks.
     * Prevents thread hangs when process dies but stream remains open.
     * Uses coroutines for proper cancellation and resource management.
     */
    private fun readProcessStreamWithTimeout(process: Process) {
        runBlocking {
            var readJob: Job? = null
            var healthCheckJob: Job? = null
            
            try {
                // Health check coroutine monitors process and cancels read job if needed
                healthCheckJob = launch(Dispatchers.IO) {
                    try {
                        while (isActive) {
                            delay(2000) // Check every 2 seconds
                            ensureActive() // Check for cancellation
                            
                            // Check if we're stopping
                            if (isStopping) {
                                Log.d(TAG, "Health check detected stop request, cancelling read job.")
                                readJob?.cancel()
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
                                break
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Error in health check coroutine: ${e.message}", e)
                        }
                    }
                }
                
                // Stream reading coroutine - can be cancelled by health check
                // Note: readLine() is blocking I/O and can't be interrupted, but we can check
                // cancellation status between reads and exit the loop when cancelled
                readJob = launch(Dispatchers.IO) {
                    try {
                        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                            var lastReadTime = System.currentTimeMillis()
                            val readTimeout = 10000L // 10 seconds without any read
                            
                            while (isActive) {
                                try {
                                    ensureActive() // Check for cancellation before reading
                                    
                                    // Check if we've been reading for too long without data
                                    val currentTime = System.currentTimeMillis()
                                    if (currentTime - lastReadTime > readTimeout) {
                                        // Check if process is still alive
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
                                        lastReadTime = currentTime // Reset to avoid spamming logs
                                    }
                                    
                                    // Check if reader is ready before attempting to read
                                    // This helps avoid blocking when no data is available
                                    if (!reader.ready()) {
                                        // No data available yet, delay and check cancellation
                                        delay(100)
                                        ensureActive() // Check cancellation during delay
                                        continue
                                    }
                                    
                                    // Check available bytes to detect if stream is closed
                                    try {
                                        process.inputStream.available()
                                    } catch (e: IOException) {
                                        Log.d(TAG, "Stream unavailable (likely closed): ${e.message}")
                                        break
                                    }
                                    
                                    // Read line (this will block if data is available but line is incomplete)
                                    // However, we've already checked that reader is ready, so this should be quick
                                    val line = reader.readLine()
                                    
                                    if (line == null) {
                                        // EOF - stream is closed
                                        Log.d(TAG, "Stream reached EOF (null read)")
                                        break
                                    }
                                    
                                    // Update last read time on successful read
                                    lastReadTime = System.currentTimeMillis()
                                    
                                    // Process the log line
                                    logFileManager.appendLog(line)
                                    synchronized(logBroadcastBuffer) {
                                        logBroadcastBuffer.add(line)
                                        handler.removeCallbacks(broadcastLogsRunnable)
                                        handler.post(broadcastLogsRunnable)
                                    }
                                    
                                    // Small delay to allow cancellation to be checked
                                    // This ensures we can respond to cancellation requests
                                    delay(10)
                                    
                                } catch (e: InterruptedIOException) {
                                    Log.d(TAG, "Stream read interrupted: ${e.message}")
                                    break
                                } catch (e: IOException) {
                                    // Check if process is still alive
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
                                    throw e // Re-throw to properly handle cancellation
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
                        throw e // Re-throw to properly handle cancellation
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Error in read coroutine: ${e.message}", e)
                        }
                    }
                }
                
                // Wait for read job to complete (it will finish when stream ends or is cancelled)
                readJob.join()
                
                // Read job finished, cancel health check job since it's no longer needed
                healthCheckJob?.cancel()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up stream reading: ${e.message}", e)
            } finally {
                // Cancel both jobs and wait for them to finish
                try {
                    healthCheckJob?.cancel()
                    readJob?.cancel()
                    // Wait for jobs to complete cancellation (with timeout)
                    withTimeoutOrNull(1000) {
                        healthCheckJob?.join()
                        readJob?.join()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error cancelling stream reading coroutines: ${e.message}", e)
                }
                
                // Ensure process streams are closed
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
    }

    private fun getProcessBuilder(xrayPath: String): ProcessBuilder {
        val filesDir = applicationContext.filesDir

        // Check if libxray.so exists
        val libxrayFile = File(xrayPath)
        if (!libxrayFile.exists()) {
            throw IOException("libxray.so not found at: $xrayPath")
        }

        // Use Android linker to execute libxray.so
        // For 64-bit: /system/bin/linker64
        // For 32-bit: /system/bin/linker
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

    private fun stopXray() {
        // Prevent multiple simultaneous stop calls
        if (isStopping) {
            Log.d(TAG, "stopXray already in progress, ignoring duplicate call.")
            return
        }
        isStopping = true
        
        try {
            Log.d(TAG, "stopXray called, starting cleanup sequence.")
            
            // Step 1: Cancel coroutine scope first to stop any running operations
            // This is non-blocking and prevents new coroutines from starting
            Log.d(TAG, "Cancelling CoroutineScope.")
            serviceScope.cancel()
            Log.d(TAG, "CoroutineScope cancelled.")
            
            // Step 2: Destroy the xray process (non-blocking, but may take time)
            // Note: destroy() is non-blocking, destroyForcibly() is also non-blocking
            val processToDestroy = xrayProcess
            if (processToDestroy != null) {
                Log.d(TAG, "Destroying xray process.")
                try {
                    // Try graceful termination first (non-blocking)
                    processToDestroy.destroy()
                    // Check if process is still alive and force kill if needed (non-blocking check)
                    // Note: We don't wait here - the process will be killed asynchronously
                    if (processToDestroy.isAlive) {
                        Log.d(TAG, "Process still alive, forcing destroy.")
                        processToDestroy.destroyForcibly()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error destroying xray process", e)
                }
                // Clear reference immediately (non-blocking)
                xrayProcess = null
                Log.d(TAG, "xrayProcess reference nulled.")
            }
            
            // Step 3: Stop the VPN service (closes TUN interface and native TProxy)
            // This is non-blocking and will clean up VPN resources
            Log.d(TAG, "Calling stopService (stopping VPN).")
            stopService()
            
            // Note: We don't wait for process destruction or coroutine cleanup
            // - Process destruction happens asynchronously
            // - Coroutines are cancelled and will finish when they can
            // - VPN service cleanup is handled by Android
            // This prevents ANR by keeping stopXray() non-blocking
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during stopXray cleanup", e)
        } finally {
            isStopping = false
            Log.d(TAG, "stopXray cleanup completed.")
        }
    }

    private fun startService() {
        synchronized(tunFdLock) {
            if (tunFd != null) return
        }
        
        val prefs = Preferences(this)
        val builder = getVpnBuilder(prefs)
        val newTunFd = builder.establish()
        
        synchronized(tunFdLock) {
            tunFd = newTunFd
        }
        
        if (newTunFd == null) {
            stopXray()
            return
        }
        
        val tproxyFile = File(cacheDir, "tproxy.conf")
        try {
            tproxyFile.createNewFile()
            FileOutputStream(tproxyFile, false).use { fos ->
                val tproxyConf = getTproxyConf(prefs)
                fos.write(tproxyConf.toByteArray())
            }
        } catch (e: IOException) {
            Log.e(TAG, e.toString())
            stopXray()
            return
        }
        
        // Safely get fd with synchronization - check all conditions atomically
        val fd: Int? = synchronized(tunFdLock) {
            // Check if we're stopping (defensive check)
            if (isStopping) {
                Log.w(TAG, "Service is stopping, cannot start TProxy")
                return@synchronized null
            }
            
            // Get fd while holding lock to prevent race condition
            val currentTunFd = tunFd
            if (currentTunFd == null) {
                return@synchronized null
            }
            
            try {
                currentTunFd.fd
            } catch (e: Exception) {
                Log.e(TAG, "Error accessing tunFd.fd: ${e.message}", e)
                null
            }
        }
        
        fd?.let { fileDescriptor ->
            // Use fd immediately after extraction (minimize race window)
            TProxyStartService(tproxyFile.absolutePath, fileDescriptor)
        } ?: run {
            Log.e(TAG, "tunFd is null or invalid after establish()")
            stopXray()
            return
        }

        // Start AI-powered TProxy optimization
        // Note: prefs is already defined above (line 321)
        val optimizer = tproxyAiOptimizer
        if (optimizer != null && !optimizer.isOptimizing()) {
            Log.i(TAG, "Starting AI-powered TProxy optimization")
            
            // Set callback to reload TProxy when configuration changes
            optimizer.onConfigurationApplied = { config, needsReload ->
                if (needsReload) {
                    Log.i(TAG, "AI optimizer applied new configuration, reloading TProxy...")
                    // Reload TProxy configuration by recreating the config file and restarting
                    serviceScope.launch {
                        try {
                            // Check if we're stopping before proceeding
                            if (isStopping) {
                                Log.w(TAG, "Skipping TProxy reload - service is stopping")
                                return@launch
                            }
                            
                            val tproxyFile = File(cacheDir, "tproxy.conf")
                            if (!tproxyFile.exists()) {
                                Log.w(TAG, "TProxy config file does not exist, skipping reload")
                                return@launch
                            }
                            
                            // Update config file first (doesn't require tunFd lock)
                            try {
                                FileOutputStream(tproxyFile, false).use { fos ->
                                    val tproxyConf = getTproxyConf(prefs)
                                    fos.write(tproxyConf.toByteArray())
                                    fos.flush()
                                }
                                Log.i(TAG, "TProxy configuration file updated with AI-optimized settings")
                            } catch (e: IOException) {
                                Log.e(TAG, "Error writing TProxy config file: ${e.message}", e)
                                return@launch
                            }
                            
                            // Check again if we're stopping before restarting TProxy
                            if (isStopping) {
                                Log.w(TAG, "Service stopping detected before TProxy restart, aborting")
                                return@launch
                            }
                            
                            // Restart TProxy service to apply new configuration
                            // This is necessary because hev-socks5-tunnel reads config at startup
                            try {
                                Log.i(TAG, "Restarting TProxy service to apply AI-optimized configuration...")
                                TProxyStopService()
                                Thread.sleep(100) // Brief delay to ensure clean shutdown
                                
                                // Get fd atomically right before using it to prevent TOCTOU race condition
                                // All validation and fd extraction happens within synchronized block
                                // fd is extracted immediately before use to minimize race window
                                val fd: Int? = synchronized(tunFdLock) {
                                    // Check all conditions within the lock - no operations outside
                                    if (isStopping) {
                                        Log.w(TAG, "Service stopping detected, cannot restart TProxy")
                                        return@synchronized null
                                    }
                                    
                                    val currentTunFd = tunFd
                                    if (currentTunFd == null) {
                                        Log.w(TAG, "tunFd is null, cannot restart TProxy")
                                        return@synchronized null
                                    }
                                    
                                    // Extract fd value while holding the lock
                                    // fd is an Int that won't change even if tunFd becomes null later
                                    try {
                                        currentTunFd.fd
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error accessing tunFd.fd: ${e.message}", e)
                                        null
                                    }
                                }
                                
                                // Validate fd outside lock (fd is an Int value, safe to check)
                                if (fd == null) {
                                    Log.w(TAG, "Could not get valid file descriptor, skipping TProxy restart")
                                    return@launch
                                }
                                
                                // Use fd immediately after getting it (minimize time between extraction and use)
                                // Note: fd is an Int value that remains valid even if tunFd is set to null
                                // The only risk is if the file descriptor is closed, but we validate it was
                                // not null before use, and TProxyStartService will handle invalid fd
                                TProxyStartService(tproxyFile.absolutePath, fd)
                                Log.i(TAG, "TProxy service restarted with AI-optimized configuration")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error restarting TProxy service: ${e.message}", e)
                            }
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating TProxy configuration", e)
                        }
                    }
                }
            }
            
            optimizer.startOptimization(
                coreStatsState = coreStatsState,
                optimizationIntervalMs = 30000L // Optimize every 30 seconds
            )
        }

        val successIntent = Intent(ACTION_START)
        successIntent.setPackage(application.packageName)
        sendBroadcast(successIntent)
        @Suppress("SameParameterValue") val channelName = "socks5"
        initNotificationChannel(channelName)
        createNotification(channelName)
    }

    private fun getVpnBuilder(prefs: Preferences): Builder = Builder().apply {
        setBlocking(false)
        setMtu(prefs.tunnelMtu)

        if (prefs.bypassLan) {
            addRoute("10.0.0.0", 8)
            addRoute("172.16.0.0", 12)
            addRoute("192.168.0.0", 16)
        }
        if (prefs.httpProxyEnabled) {
            setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", prefs.socksPort))
        }
        if (prefs.ipv4) {
            addAddress(prefs.tunnelIpv4Address, prefs.tunnelIpv4Prefix)
            addRoute("0.0.0.0", 0)
            prefs.dnsIpv4.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
        }
        if (prefs.ipv6) {
            addAddress(prefs.tunnelIpv6Address, prefs.tunnelIpv6Prefix)
            addRoute("::", 0)
            prefs.dnsIpv6.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
        }

        prefs.apps?.forEach { appName ->
            appName?.let { name ->
                try {
                    when {
                        prefs.bypassSelectedApps -> addDisallowedApplication(name)
                        else -> addAllowedApplication(name)
                    }
                } catch (ignored: PackageManager.NameNotFoundException) {
                }
            }
        }
        if (prefs.bypassSelectedApps || prefs.apps.isNullOrEmpty())
            addDisallowedApplication(BuildConfig.APPLICATION_ID)
    }

    private fun stopService() {
        Log.d(TAG, "stopService called, cleaning up VPN resources.")
        
        // Step 1: Stop AI optimizer before stopping service
        tproxyAiOptimizer?.stopOptimization()
        
        // Step 2: Stop native TProxy service first
        try {
            TProxyStopService()
            Log.d(TAG, "Native TProxy service stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping native TProxy service", e)
        }
        
        // Step 3: Close TUN file descriptor (VPN interface) with synchronization
        synchronized(tunFdLock) {
            tunFd?.let { fd ->
                try {
                    Log.d(TAG, "Closing TUN file descriptor.")
                    fd.close()
                    Log.d(TAG, "TUN file descriptor closed.")
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing TUN file descriptor", e)
                } finally {
                    tunFd = null
                }
            }
        }
        
        // Step 4: Stop foreground service
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            Log.d(TAG, "Foreground service stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground service", e)
        }
        
        // Step 5: Exit the service
        exit()
    }
    
    /**
     * Update core stats state for AI optimizer.
     * Called from MainViewModel when stats are updated.
     */
    fun updateCoreStatsState(stats: CoreStatsState) {
        coreStatsState = stats
        // Notify AI optimizer if it's running
        // The optimizer will pick up the new stats in its next cycle
    }
    
    /**
     * Get AI optimizer instance (for testing/debugging).
     */
    fun getAiOptimizer(): TProxyAiOptimizer? {
        return tproxyAiOptimizer
    }

    @Suppress("SameParameterValue")
    private fun createNotification(channelName: String) {
        val i = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, channelName)
        val notify = notification.setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_stat_name).setContentIntent(pi).build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notify)
        } else {
            startForeground(1, notify, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    private fun exit() {
        val stopIntent = Intent(ACTION_STOP)
        stopIntent.setPackage(application.packageName)
        sendBroadcast(stopIntent)
        stopSelf()
    }

    @Suppress("SameParameterValue")
    private fun initNotificationChannel(channelName: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val name: CharSequence = getString(R.string.app_name)
        val channel = NotificationChannel(channelName, name, NotificationManager.IMPORTANCE_DEFAULT)
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_CONNECT: String = "com.hyperxray.an.CONNECT"
        const val ACTION_DISCONNECT: String = "com.hyperxray.an.DISCONNECT"
        const val ACTION_START: String = "com.hyperxray.an.START"
        const val ACTION_STOP: String = "com.hyperxray.an.STOP"
        const val ACTION_ERROR: String = "com.hyperxray.an.ERROR"
        const val ACTION_LOG_UPDATE: String = "com.hyperxray.an.LOG_UPDATE"
        const val ACTION_RELOAD_CONFIG: String = "com.hyperxray.an.RELOAD_CONFIG"
        const val EXTRA_LOG_DATA: String = "log_data"
        const val EXTRA_ERROR_MESSAGE: String = "error_message"
        private const val TAG = "VpnService"
        private const val BROADCAST_DELAY_MS: Long = 10
        private const val BROADCAST_BUFFER_SIZE_THRESHOLD: Int = 1

        init {
            System.loadLibrary("hev-socks5-tunnel")
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

        private fun getTproxyConf(prefs: Preferences): String {
            // Use custom values if available, otherwise use defaults
            val mtu = prefs.tunnelMtuCustom
            val taskStack = prefs.taskStackSizeCustom
            // Maximum buffer size for optimal performance - removed 65432 limit
            val tcpBuffer = prefs.tcpBufferSize
            val nofile = prefs.limitNofile
            val connectTimeout = prefs.connectTimeout
            val readWriteTimeout = prefs.readWriteTimeout
            
            var tproxyConf = """misc:
  task-stack-size: $taskStack
  tcp-buffer-size: $tcpBuffer
  connect-timeout: $connectTimeout
  read-write-timeout: $readWriteTimeout
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
    }
}