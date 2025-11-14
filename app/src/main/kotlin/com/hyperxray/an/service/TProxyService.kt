package com.hyperxray.an.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
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
import com.hyperxray.an.core.inference.OnnxRuntimeManager
import com.hyperxray.an.core.network.TLSFeatureEncoder
import com.hyperxray.an.core.monitor.OptimizerLogger
import com.hyperxray.an.ui.screens.log.extractSNI
import com.hyperxray.an.xray.runtime.stats.CoreStatsClient
import com.hyperxray.an.common.Socks5ReadinessChecker
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
    // Reusable ArrayList to avoid creating new one on every broadcast
    private val reusableBroadcastList = ArrayList<String>(100)
    private val broadcastLogsRunnable = Runnable {
        synchronized(logBroadcastBuffer) {
            if (logBroadcastBuffer.isNotEmpty()) {
                val logUpdateIntent = Intent(ACTION_LOG_UPDATE)
                logUpdateIntent.setPackage(application.packageName)
                // Reuse ArrayList instead of creating new one - reduces GC pressure
                reusableBroadcastList.clear()
                reusableBroadcastList.addAll(logBroadcastBuffer)
                logUpdateIntent.putStringArrayListExtra(
                    EXTRA_LOG_DATA, reusableBroadcastList
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
    private var coreStatsClient: CoreStatsClient? = null
    @Volatile
    private var lastClientCloseTime: Long = 0L
    private val MIN_RECREATE_INTERVAL_MS = 5000L // 5 seconds minimum between recreations
    
    // WakeLock to prevent system from killing service (partial wake lock for CPU only)
    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeatJob: Job? = null
    
    // SOCKS5 readiness tracking
    @Volatile
    private var socks5ReadinessChecked = false
    private var socks5ReadinessJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        logFileManager = LogFileManager(this)
        
        // Acquire partial wake lock to prevent system from killing service
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HyperXray::TProxyService::WakeLock"
            ).apply {
                acquire(10 * 60 * 60 * 1000L) // 10 hours timeout
            }
            Log.d(TAG, "WakeLock acquired to prevent system kill")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}", e)
        }
        
        // Initialize notification channel and show notification immediately
        // This ensures service stays alive in background
        val channelName = "socks5"
        initNotificationChannel(channelName)
        createNotification(channelName)
        
        // Start heartbeat coroutine to keep service alive and update notification
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                try {
                    delay(30000) // Wait 30 seconds
                    // Update notification periodically to show service is alive
                    val currentChannelName = if (Preferences(this@TProxyService).disableVpn) "nosocks" else "socks5"
                    createNotification(currentChannelName)
                    Log.d(TAG, "Heartbeat: Service alive, notification updated")
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error: ${e.message}", e)
                    delay(30000) // Wait before retry
                }
            }
        }
        Log.d(TAG, "Heartbeat coroutine started")
        
        // Initialize AI-powered TProxy optimizer
        val prefs = Preferences(this)
        tproxyAiOptimizer = TProxyAiOptimizer(this, prefs)
        Log.d(TAG, "TProxyService created with AI optimizer.")
        
        // Initialize ONNX Runtime Manager for TLS SNI optimization
        try {
            OnnxRuntimeManager.init(this)
            if (OnnxRuntimeManager.isReady()) {
                Log.i(TAG, "TLS SNI optimizer model loaded successfully")
            } else {
                Log.w(TAG, "TLS SNI optimizer model failed to load")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TLS SNI optimizer: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val action = intent.action
        when (action) {
            ACTION_DISCONNECT -> {
                stopXray()
                return START_NOT_STICKY
            }

            ACTION_RELOAD_CONFIG -> {
                // Ensure notification is shown
                val channelName = if (Preferences(this).disableVpn) "nosocks" else "socks5"
                initNotificationChannel(channelName)
                createNotification(channelName)
                
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
                // Ensure notification is shown for any start
                val channelName = "socks5"
                initNotificationChannel(channelName)
                createNotification(channelName)
                
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
        
        // Stop heartbeat
        heartbeatJob?.cancel()
        heartbeatJob = null
        
        // Release wake lock
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock: ${e.message}", e)
        }
        
        // Stop Xray and clean up all resources
        stopXray()
        
        // Remove any pending log broadcasts and flush remaining logs
        handler.removeCallbacks(broadcastLogsRunnable)
        broadcastLogsRunnable.run()
        
        // Flush log file buffer before shutdown
        logFileManager.flush()
        
        // Stop AI optimizer
        tproxyAiOptimizer?.stopOptimization()
        tproxyAiOptimizer = null
        
        // Release ONNX Runtime Manager
        OnnxRuntimeManager.release()
        
        Log.d(TAG, "TProxyService destroyed.")
        // Let Android handle service lifecycle - do not call exitProcess(0)
    }

    override fun onRevoke() {
        stopXray()
        super.onRevoke()
    }

    private fun startXray() {
        // Reset UDP error count when starting fresh connection
        udpErrorCount = 0
        lastUdpErrorTime = 0L
        
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
            
            // CRITICAL: Verify UDP support is enabled in dokodemo-door inbounds before sending to Xray
            try {
                val configJson = org.json.JSONObject(injectedConfigContent)
                val inboundsArray = configJson.optJSONArray("inbounds") ?: configJson.optJSONArray("inbound")
                if (inboundsArray != null) {
                    var dokodemoFound = false
                    var udpEnabled = false
                    for (i in 0 until inboundsArray.length()) {
                        val inbound = inboundsArray.getJSONObject(i)
                        val protocol = inbound.optString("protocol", "").lowercase()
                        if (protocol == "dokodemo-door" || protocol == "dokodemo" || protocol == "tunnel") {
                            dokodemoFound = true
                            val settings = inbound.optJSONObject("settings")
                            if (settings != null) {
                                val network = settings.opt("network")
                                if (network is org.json.JSONArray) {
                                    val networks = mutableSetOf<String>()
                                    for (j in 0 until network.length()) {
                                        networks.add(network.optString(j, "").lowercase())
                                    }
                                    udpEnabled = networks.contains("udp") && networks.contains("tcp")
                                    Log.i(TAG, "🔍 VERIFICATION: dokodemo-door inbound #${i+1}: network=${network.toString()}, UDP enabled=$udpEnabled")
                                } else {
                                    Log.w(TAG, "⚠️ VERIFICATION FAILED: dokodemo-door inbound #${i+1} network is not an array: $network")
                                }
                            } else {
                                Log.w(TAG, "⚠️ VERIFICATION FAILED: dokodemo-door inbound #${i+1} has no settings object")
                            }
                        }
                    }
                    if (dokodemoFound && !udpEnabled) {
                        Log.e(TAG, "❌ CRITICAL ERROR: dokodemo-door inbound found but UDP is NOT enabled in config!")
                    } else if (dokodemoFound && udpEnabled) {
                        Log.i(TAG, "✅ VERIFICATION SUCCESS: dokodemo-door inbound has UDP enabled in config")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error verifying config before writing to Xray: ${e.message}", e)
            }
            
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

            // Start SOCKS5 readiness check after Xray process starts
            // Check readiness both when we detect "Xray ... started" in logs AND
            // after a fixed delay to ensure we catch it even if log format changes
            socks5ReadinessChecked = false
            
            // Start a background readiness check job that will run after process validation
            // This ensures we check readiness even if log detection fails
            socks5ReadinessJob = serviceScope.launch {
                // Wait for process to be validated (at least 100ms) plus extra time for Xray to initialize
                delay(2000) // Wait 2 seconds for Xray to fully start and bind ports
                if (!socks5ReadinessChecked && !isStopping && currentProcess.isAlive) {
                    Log.d(TAG, "Background SOCKS5 readiness check triggered")
                    checkSocks5Readiness(prefs)
                }
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
                                        // Only broadcast immediately if buffer threshold reached, otherwise delay
                                        if (logBroadcastBuffer.size >= BROADCAST_BUFFER_SIZE_THRESHOLD) {
                                            handler.post(broadcastLogsRunnable)
                                        } else {
                                            handler.postDelayed(broadcastLogsRunnable, BROADCAST_DELAY_MS)
                                        }
                                    }
                                    
                                    // Check if Xray has started (indicates SOCKS5 should be ready soon)
                                    // Xray logs "Xray ... started" when it's fully initialized
                                    if (!socks5ReadinessChecked && line.contains("started", ignoreCase = true) && 
                                        line.contains("Xray", ignoreCase = true)) {
                                        Log.d(TAG, "Detected Xray startup in logs, checking SOCKS5 readiness")
                                        // Trigger readiness check after a short delay to allow port binding
                                        // Cancel any existing readiness job to avoid duplicates
                                        socks5ReadinessJob?.cancel()
                                        socks5ReadinessJob = serviceScope.launch {
                                            delay(1000) // Wait 1 second for port to bind
                                            if (!socks5ReadinessChecked && !isStopping) {
                                                checkSocks5Readiness(Preferences(this@TProxyService))
                                            }
                                        }
                                    }
                                    
                                    // CRITICAL: Detect UDP closed pipe errors and log them prominently
                                    // These errors indicate UDP operations are failing due to closed sockets/pipes
                                    detectUdpClosedPipeErrors(line)
                                    
                                    // Process SNI for TLS optimization
                                    processSNIFromLog(line)
                                    
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
                
                // Wait before closing streams to allow process to finish writing
                // This prevents "closed pipe" errors when Xray is handling UDP traffic
                // Increased delays to give more time for UDP operations to complete
                try {
                    Thread.sleep(200) // Initial delay to let process finish current operations
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                
                // Only close streams if process is dead, or add delay if still alive
                val processAlive = process.isAlive
                if (processAlive) {
                    // Process still alive - wait more before closing streams to avoid pipe errors
                    try {
                        Thread.sleep(300) // Increased to 300ms for better UDP cleanup
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                
                // Now close streams - process should have finished writing by now
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
        
        // Cancel SOCKS5 readiness check job
        socks5ReadinessJob?.cancel()
        socks5ReadinessJob = null
        
        // Mark SOCKS5 as not ready
        Socks5ReadinessChecker.setSocks5Ready(false)
        socks5ReadinessChecked = false
        
        try {
            Log.d(TAG, "stopXray called, starting cleanup sequence.")
            
            val processToDestroy = xrayProcess
            
            // Step 1: Stop native TProxy service FIRST to prevent new UDP packets from being forwarded
            // This ensures no new UDP packets are sent to Xray while we're shutting down
            // IMPORTANT: Stop TProxy BEFORE stopping Xray to prevent "closed pipe" errors
            // CRITICAL: Give extra time for UDP cleanup to prevent race conditions
            try {
                Log.d(TAG, "Stopping native TProxy service to prevent new UDP packets...")
                // First, wait a bit to allow any in-flight UDP packets to be processed
                // This prevents UDP packets from arriving after TProxy is stopped
                try {
                    Thread.sleep(500) // Increased to 500ms to let UDP packets finish
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                TProxyStopService()
                // Give native tunnel time to clean up UDP sessions and close sockets gracefully
                // This prevents race conditions where UDP packets are written to closed pipes
                // UDP cleanup can take time, especially with active connections
                try {
                    Thread.sleep(1000) // Increased to 1000ms (1 second) for UDP cleanup
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                Log.d(TAG, "Native TProxy service stopped.")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping native TProxy service", e)
            }
            
            // Step 2: Signal graceful shutdown by closing stdin (if still open)
            // This gives Xray a chance to finish current operations before termination
            if (processToDestroy != null && processToDestroy.isAlive) {
                try {
                    // First, give Xray EXTENDED time to finish any in-flight UDP operations
                    // This prevents "closed pipe" errors when Xray is handling UDP traffic
                    // UDP operations can take time, especially with multiple active connections
                    // CRITICAL: Wait longer to ensure all UDP packets are processed
                    try {
                        Thread.sleep(1000) // Increased to 1000ms (1 second) to allow UDP cleanup
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    
                    // Close stdin to signal shutdown (Xray may check for stdin closure)
                    // After this, Xray will start shutting down, but UDP packets might still arrive
                    processToDestroy.outputStream?.close()
                    Log.d(TAG, "Closed stdin to signal graceful shutdown.")
                    
                    // Additional EXTENDED grace period after closing stdin to allow UDP cleanup
                    // This gives time for:
                    // - In-flight UDP packets to complete
                    // - UDP dispatcher to clean up connections (can take time with active sessions)
                    // - Any pending UDP writes to finish or fail gracefully
                    // CRITICAL: UDP dispatcher has 1-minute timeout, but we need time for cleanup
                    try {
                        Thread.sleep(2000) // Increased to 2000ms (2 seconds) for UDP dispatcher cleanup
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Error during graceful shutdown: ${e.message}")
                }
            }
            
            // Step 3: Cancel coroutine scope to stop stream reading
            // This is non-blocking and prevents new coroutines from starting
            Log.d(TAG, "Cancelling CoroutineScope.")
            serviceScope.cancel()
            Log.d(TAG, "CoroutineScope cancelled.")
            
            // Step 4: Destroy the xray process after grace period
            if (processToDestroy != null) {
                Log.d(TAG, "Destroying xray process.")
                try {
                    // Try graceful termination first (sends SIGTERM on Unix)
                    processToDestroy.destroy()
                    // Wait for graceful shutdown - EXTENDED wait for UDP cleanup
                    // UDP dispatcher cleanup can take time, especially with active connections
                    try {
                        Thread.sleep(2000) // Increased to 2000ms (2 seconds) for UDP operations to complete
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    // Force kill if still alive
                    if (processToDestroy.isAlive) {
                        Log.d(TAG, "Process still alive after graceful shutdown, forcing destroy.")
                        processToDestroy.destroyForcibly()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error destroying xray process", e)
                }
                // Clear reference
                xrayProcess = null
                Log.d(TAG, "xrayProcess reference nulled.")
            }
            
            // Step 5: Stop the VPN service (closes TUN interface)
            // TProxy is already stopped, so this just closes the TUN fd
            // This is non-blocking and will clean up VPN resources
            Log.d(TAG, "Calling stopService (stopping VPN).")
            stopService()
            
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
        
        // Step 2: Native TProxy service is already stopped in stopXray() to prevent UDP race conditions
        // Only stop it here if stopService() is called directly (not from stopXray())
        // Check if TProxy is still running by verifying tunFd is set
        synchronized(tunFdLock) {
            if (tunFd != null) {
                // TProxy might still be running, stop it first
                try {
                    Log.d(TAG, "Stopping native TProxy service from stopService()...")
                    TProxyStopService()
                    // Give native tunnel time to clean up UDP sessions
                    try {
                        Thread.sleep(200) // Allow UDP cleanup
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    Log.d(TAG, "Native TProxy service stopped.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping native TProxy service", e)
                }
            }
        }
        
        // Step 3: Close TUN file descriptor (VPN interface) with synchronization
        // IMPORTANT: Close TUN fd AFTER stopping TProxy to prevent race conditions
        // where UDP packets are written to a closed file descriptor
        synchronized(tunFdLock) {
            tunFd?.let { fd ->
                try {
                    Log.d(TAG, "Closing TUN file descriptor.")
                    // Give a small grace period to ensure all UDP operations have completed
                    // This prevents "read/write on closed pipe" errors
                    try {
                        Thread.sleep(100) // Small delay before closing TUN fd
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
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
     * Detects UDP closed pipe errors from Xray logs.
     * These errors indicate that UDP operations are failing because sockets/pipes are closed.
     * 
     * Common patterns:
     * - "transport/internet/udp: failed to write first UDP payload > io: read/write on closed pipe"
     * - "transport/internet/udp: failed to handle UDP input > io: read/write on closed pipe"
     * 
     * This method logs these errors prominently so they can be debugged.
     */
    @Volatile
    private var udpErrorCount = 0
    @Volatile
    private var lastUdpErrorTime = 0L
    
    private fun detectUdpClosedPipeErrors(logEntry: String) {
        val upperEntry = logEntry.uppercase()
        
        // Check for UDP closed pipe errors
        if (upperEntry.contains("TRANSPORT/INTERNET/UDP") && 
            (upperEntry.contains("FAILED TO WRITE") || upperEntry.contains("FAILED TO HANDLE")) &&
            (upperEntry.contains("CLOSED PIPE") || upperEntry.contains("READ/WRITE ON CLOSED"))) {
            
            // CRITICAL: These errors occur when:
            // 1. UDP dispatcher closes connection after 1-minute inactivity (hardcoded in Xray)
            // 2. Native tunnel still tries to send UDP packets after connection is closed
            // 3. This is a race condition that's hard to prevent completely
            
            // We've already:
            // - Increased UDP timeout to 30 minutes (connIdle=1800s)
            // - Extended lifecycle shutdown delays (5+ seconds total)
            // - Improved TProxy shutdown sequence
            
            // However, Xray's UDP dispatcher has a hardcoded 1-minute inactivity timer
            // that's not controlled by policy settings. This causes occasional closed pipe errors.
            
            // For now, we'll silently ignore these errors during normal operation
            // as they don't affect functionality - UDP connections are automatically recreated.
            // Only log if errors are very frequent (more than 5 per minute)
            
            val currentTime = System.currentTimeMillis()
            val timeSinceLastError = currentTime - lastUdpErrorTime
            
            // Only log if errors are very frequent (more than 5 per minute)
            // This helps identify real problems while ignoring normal race conditions
            if (timeSinceLastError < 12000) { // Less than 12 seconds between errors
                udpErrorCount++
                lastUdpErrorTime = currentTime
                
                // Only log if errors are very frequent
                if (udpErrorCount > 5) {
                    Log.w(TAG, "⚠️ FREQUENT UDP CLOSED PIPE ERRORS (count: $udpErrorCount in last minute)")
                    Log.w(TAG, "This may indicate UDP connection issues. Most recent: $logEntry")
                } else {
                    // Silently ignore occasional errors - they're normal race conditions
                    // caused by Xray's UDP dispatcher 1-minute inactivity timer
                }
            } else {
                // Reset counter if enough time has passed (errors are not frequent)
                udpErrorCount = 1
                lastUdpErrorTime = currentTime
            }
        }
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
    
    /**
     * Process SNI from Xray logs and make routing decisions using ONNX model.
     * Called when SNI is detected in log entries.
     */
    private fun processSNIFromLog(logEntry: String) {
        // Extract SNI from log entry first
        val sni = try {
            extractSNI(logEntry)
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting SNI: ${e.message}")
            null
        }
        
        if (sni == null || sni.isEmpty()) {
            // No SNI found in this log entry - this is normal for non-TLS logs
            // Debug: Check if log entry contains Instagram/TikTok keywords (to debug why they're not being extracted)
            if (logEntry.contains("instagram", ignoreCase = true) || 
                logEntry.contains("tiktok", ignoreCase = true) ||
                logEntry.contains(".ig.", ignoreCase = true) ||
                logEntry.contains("i.instagram", ignoreCase = true) ||
                logEntry.contains("api.instagram", ignoreCase = true) ||
                logEntry.contains("graph.instagram", ignoreCase = true)) {
                Log.d(TAG, "Found Instagram/TikTok keyword in log but SNI not extracted: ${logEntry.take(200)}")
            }
            return
        }
        
        Log.d(TAG, "Processing SNI: $sni from log entry")
        
        // Try auto-learning optimizer first (v9)
        try {
            if (com.hyperxray.an.optimizer.OrtHolder.isReady() || 
                com.hyperxray.an.optimizer.OrtHolder.init(this)) {
                processSNIWithAutoLearner(sni, logEntry)
                Log.d(TAG, "SNI processed with auto-learner: $sni")
                return // Use auto-learner if available
            } else {
                Log.d(TAG, "Auto-learner not ready, falling back to OnnxRuntimeManager")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auto-learner processing failed, falling back to OnnxRuntimeManager: ${e.message}")
        }
        
        // Fallback to OnnxRuntimeManager optimizer
        if (!OnnxRuntimeManager.isReady()) {
            Log.w(TAG, "OnnxRuntimeManager not ready, skipping SNI processing for: $sni")
            return // Model not loaded, skip processing
        }
        
        try {
            // Extract ALPN if available (default to h2)
            val alpn = extractALPN(logEntry) ?: "h2"
            Log.d(TAG, "SNI: $sni, ALPN: $alpn")
            
            // Encode TLS features
            val features = TLSFeatureEncoder.encode(sni, alpn)
            Log.d(TAG, "Encoded TLS features for SNI: $sni")
            
            // Run ONNX inference
            val (serviceTypeIndex, routingDecisionIndex) = OnnxRuntimeManager.predict(features)
            Log.d(TAG, "ONNX inference result for SNI $sni: service=$serviceTypeIndex, route=$routingDecisionIndex")
            
            // Log the decision
            OptimizerLogger.logDecision(sni, serviceTypeIndex, routingDecisionIndex)
            
            // Apply routing decision (future: modify Xray routing rules)
            // For now, we just log the decision
            applyRoutingDecision(sni, routingDecisionIndex)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing SNI from log: ${e.message}", e)
        }
    }
    
    /**
     * Process SNI using auto-learning optimizer (v9).
     * Uses real-time metrics from CoreStatsState and CoreStatsClient.
     */
    private fun processSNIWithAutoLearner(sni: String, logEntry: String) {
        try {
            // Initialize OrtHolder if needed
            if (!com.hyperxray.an.optimizer.OrtHolder.isReady()) {
                com.hyperxray.an.optimizer.OrtHolder.init(this)
            }
            
            if (!com.hyperxray.an.optimizer.OrtHolder.isReady()) {
                return // Auto-learner not available
            }
            
            // Run inference (async) with real metrics
            serviceScope.launch {
                // Get real-time metrics from CoreStatsState or CoreStatsClient
                val (latencyMs, throughputKbps, success) = getRealTimeMetrics()
                
                val decision = com.hyperxray.an.optimizer.Inference.optimizeSni(
                    context = this@TProxyService,
                    sni = sni,
                    latencyMs = latencyMs,
                    throughputKbps = throughputKbps
                )
                
                Log.d(TAG, "Auto-learner decision: sni=$sni, svc=${decision.svcClass}, route=${decision.routeDecision}, alpn=${decision.alpn}, latency=${latencyMs}ms, throughput=${throughputKbps}kbps")
                
                // Get network context for feedback
                val networkContext = getNetworkContext()
                val rtt = coreStatsState?.let { estimateLatencyFromStats(it) } ?: latencyMs
                val jitter = estimateJitter(latencyMs)
                
                // Log feedback (will be used for learning)
                com.hyperxray.an.optimizer.LearnerLogger.logFeedback(
                    context = this@TProxyService,
                    sni = sni,
                    svcClass = decision.svcClass,
                    routeDecision = decision.routeDecision,
                    success = success,
                    latencyMs = latencyMs.toFloat(),
                    throughputKbps = throughputKbps.toFloat(),
                    alpn = decision.alpn,
                    rtt = rtt,
                    jitter = jitter,
                    networkType = networkContext
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in auto-learner processing: ${e.message}", e)
        }
    }
    
    /**
     * Get real-time metrics from CoreStatsState or CoreStatsClient.
     * Returns (latencyMs, throughputKbps, success).
     */
    private suspend fun getRealTimeMetrics(): Triple<Double, Double, Boolean> {
        return try {
            // Try to get metrics from CoreStatsState first (faster, already cached)
            val stats = coreStatsState
            if (stats != null && (stats.uplinkThroughput > 0 || stats.downlinkThroughput > 0)) {
                // Calculate total throughput (bytes/sec -> kbps)
                val totalThroughputBytesPerSec = stats.uplinkThroughput + stats.downlinkThroughput
                val throughputKbps = (totalThroughputBytesPerSec * 8.0) / 1000.0 // bytes/sec * 8 bits/byte / 1000 = kbps
                
                // Estimate latency from goroutines and memory (heuristic)
                // Higher goroutines/memory pressure = higher latency
                val latencyMs = estimateLatencyFromStats(stats)
                
                // Success if throughput > 0 and latency is reasonable
                val success = throughputKbps > 0 && latencyMs < 5000.0
                
                Log.d(TAG, "Using CoreStatsState metrics: latency=${latencyMs}ms, throughput=${throughputKbps}kbps, success=$success")
                return Triple(latencyMs, throughputKbps, success)
            }
            
            // Fallback: Try CoreStatsClient (slower, requires gRPC call)
            val prefs = Preferences(this)
            val apiPort = prefs.apiPort
            // Validate port before attempting creation (defensive check)
            if (apiPort > 0 && apiPort <= 65535) {
                try {
                    // Initialize client if needed (create() now returns nullable and never throws)
                    // Enforce cooldown to prevent rapid recreation loops
                    if (coreStatsClient == null) {
                        val now = System.currentTimeMillis()
                        val timeSinceClose = now - lastClientCloseTime
                        
                        if (timeSinceClose < MIN_RECREATE_INTERVAL_MS) {
                            val remainingCooldown = MIN_RECREATE_INTERVAL_MS - timeSinceClose
                            Log.d(TAG, "Client recreation cooldown active, ${remainingCooldown}ms remaining")
                            // Fall through to default values
                        } else {
                            // CoreStatsClient.create() now safely returns null on any error (never throws)
                            coreStatsClient = CoreStatsClient.create("127.0.0.1", apiPort)
                            if (coreStatsClient == null) {
                                Log.w(TAG, "Failed to create CoreStatsClient for metrics (port: $apiPort)")
                                lastClientCloseTime = System.currentTimeMillis()
                                // Fall through to default values
                            }
                        }
                    }
                    
                    // Only proceed if client was successfully created
                    val client = coreStatsClient
                    if (client != null) {
                        // Get system stats and traffic
                        val systemStats = client.getSystemStats()
                        val trafficStats = client.getTraffic()
                        
                        if (trafficStats != null && (trafficStats.uplink > 0 || trafficStats.downlink > 0)) {
                            // Calculate throughput (bytes/sec -> kbps)
                            // Note: This is cumulative, not per-second, so we need to track deltas
                            // For now, use a simple heuristic based on total traffic
                            val totalTraffic = trafficStats.uplink + trafficStats.downlink
                            val throughputKbps = if (totalTraffic > 0) {
                                // Heuristic: assume traffic accumulated over last 60 seconds
                                (totalTraffic * 8.0) / (60.0 * 1000.0) // bytes / 60 sec * 8 bits/byte / 1000 = kbps
                            } else {
                                0.0
                            }
                            
                            // Estimate latency from system stats
                            val latencyMs = if (systemStats != null) {
                                estimateLatencyFromSystemStats(systemStats)
                            } else {
                                100.0 // Default latency
                            }
                            
                            // Success if throughput > 0
                            val success = throughputKbps > 0
                            
                            Log.d(TAG, "Using CoreStatsClient metrics: latency=${latencyMs}ms, throughput=${throughputKbps}kbps, success=$success")
                            return Triple(latencyMs, throughputKbps, success)
                        } else {
                            // Stats query succeeded but no traffic data - don't close immediately
                            // This prevents rapid recreation loops
                            Log.d(TAG, "CoreStatsClient query returned no traffic data, will retry later")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error getting metrics from CoreStatsClient: ${e.message}", e)
                    // Close client on error but enforce cooldown before recreation
                    try {
                        coreStatsClient?.close()
                    } catch (closeEx: Exception) {
                        Log.w(TAG, "Error closing CoreStatsClient: ${closeEx.message}")
                    }
                    coreStatsClient = null
                    lastClientCloseTime = System.currentTimeMillis()
                }
            }
            
            // Fallback: Use default values if no metrics available
            Log.w(TAG, "No real-time metrics available, using default values")
            Triple(100.0, 1000.0, true) // Default: 100ms latency, 1000 kbps throughput
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting real-time metrics: ${e.message}", e)
            Triple(100.0, 1000.0, true) // Default fallback
        }
    }
    
    /**
     * Get network context (WiFi/4G/5G).
     */
    private fun getNetworkContext(): String? {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connectivityManager?.activeNetwork ?: return null
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
            
            when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    // Try to determine cellular generation (4G/5G)
                    // Check for 5G using available capabilities
                    val has5G = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Android 10+ (API 29+): Check for NR (New Radio) capability
                        try {
                            // NET_CAPABILITY_NR is available in API 29+
                            val nrCapability = NetworkCapabilities::class.java.getField("NET_CAPABILITY_NR")
                            val nrValue = nrCapability.getInt(null)
                            networkCapabilities.hasCapability(nrValue)
                        } catch (e: Exception) {
                            // Fallback: Use link bandwidth as heuristic
                            // 5G typically has higher bandwidth
                            val downstreamKbps = networkCapabilities.linkDownstreamBandwidthKbps
                            downstreamKbps > 100000 // > 100 Mbps suggests 5G
                        }
                    } else {
                        // Android 9 and below: Use bandwidth heuristic
                        val downstreamKbps = networkCapabilities.linkDownstreamBandwidthKbps
                        downstreamKbps > 100000 // > 100 Mbps suggests 5G
                    }
                    if (has5G) "5G" else "4G"
                }
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting network context: ${e.message}")
            null
        }
    }
    
    /**
     * Estimate jitter from latency (simple variance approximation).
     * In a real implementation, this would track latency history.
     */
    @Volatile
    private var lastLatency: Double? = null
    
    private fun estimateJitter(currentLatency: Double): Double? {
        return try {
            val jitter = if (lastLatency != null) {
                kotlin.math.abs(currentLatency - lastLatency!!)
            } else {
                null
            }
            lastLatency = currentLatency
            jitter
        } catch (e: Exception) {
            Log.w(TAG, "Error estimating jitter: ${e.message}")
            null
        }
    }
    
    /**
     * Estimate latency from CoreStatsState (heuristic based on goroutines and memory).
     */
    private fun estimateLatencyFromStats(stats: CoreStatsState): Double {
        // Base latency: 50ms
        var latency = 50.0
        
        // Higher goroutines = more connections = potential higher latency
        if (stats.numGoroutine > 100) {
            latency += (stats.numGoroutine - 100) * 0.5 // +0.5ms per goroutine above 100
        }
        
        // Higher memory pressure = potential higher latency
        if (stats.alloc > 100 * 1024 * 1024) { // > 100MB
            latency += ((stats.alloc - 100 * 1024 * 1024) / (1024 * 1024)) * 0.1 // +0.1ms per MB above 100MB
        }
        
        // Clamp to reasonable range
        return latency.coerceIn(10.0, 2000.0)
    }
    
    /**
     * Estimate latency from system stats (heuristic).
     */
    private fun estimateLatencyFromSystemStats(systemStats: com.xray.app.stats.command.SysStatsResponse): Double {
        // Base latency: 50ms
        var latency = 50.0
        
        // Higher goroutines = more connections = potential higher latency
        val numGoroutine = systemStats.numGoroutine
        if (numGoroutine > 100) {
            latency += (numGoroutine - 100) * 0.5 // +0.5ms per goroutine above 100
        }
        
        // Higher memory pressure = potential higher latency
        val alloc = systemStats.alloc
        if (alloc > 100 * 1024 * 1024) { // > 100MB
            latency += ((alloc - 100 * 1024 * 1024) / (1024 * 1024)) * 0.1 // +0.1ms per MB above 100MB
        }
        
        // Clamp to reasonable range
        return latency.coerceIn(10.0, 2000.0)
    }
    
    /**
     * Extract ALPN protocol from log entry.
     */
    private fun extractALPN(logEntry: String): String? {
        // Try to extract ALPN from log patterns
        val alpnPatterns = listOf(
            Regex("""alpn\s*[=:]\s*([^\s,}\]]+)""", RegexOption.IGNORE_CASE),
            Regex("""ALPN\s*[=:]\s*([^\s,}\]]+)""", RegexOption.IGNORE_CASE),
            Regex("""protocol\s*[=:]\s*(h2|h3|http/1\.1)""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in alpnPatterns) {
            pattern.find(logEntry)?.let {
                val alpn = it.groupValues[1].trim().lowercase()
                if (alpn.isNotEmpty() && (alpn == "h2" || alpn == "h3" || alpn == "http/1.1")) {
                    return when (alpn) {
                        "h3" -> "h3"
                        "h2" -> "h2"
                        else -> "h2" // Default
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Check if SOCKS5 proxy is ready and broadcast readiness status.
     * This function waits for Xray to fully initialize and bind to the SOCKS5 port.
     * 
     * @param prefs Preferences instance to get SOCKS5 port
     */
    private suspend fun checkSocks5Readiness(prefs: Preferences) {
        if (socks5ReadinessChecked) {
            return // Already checked
        }
        
        try {
            val socksPort = prefs.socksPort
            val socksAddress = prefs.socksAddress
            
            Log.d(TAG, "Checking SOCKS5 readiness on $socksAddress:$socksPort")
            
            // Wait for SOCKS5 to become ready with retries
            val isReady = Socks5ReadinessChecker.waitUntilSocksReady(
                context = this,
                address = socksAddress,
                port = socksPort,
                maxWaitTimeMs = 15000L, // Wait up to 15 seconds
                retryIntervalMs = 500L // Check every 500ms
            )
            
            if (isReady) {
                socks5ReadinessChecked = true
                Socks5ReadinessChecker.setSocks5Ready(true)
                
                // Broadcast SOCKS5 readiness to other components
                val readyIntent = Intent(ACTION_SOCKS5_READY)
                readyIntent.setPackage(application.packageName)
                readyIntent.putExtra("socks_address", socksAddress)
                readyIntent.putExtra("socks_port", socksPort)
                sendBroadcast(readyIntent)
                
                Log.i(TAG, "✅ SOCKS5 is ready on $socksAddress:$socksPort - broadcast sent")
            } else {
                Log.w(TAG, "⚠️ SOCKS5 did not become ready within timeout")
                Socks5ReadinessChecker.setSocks5Ready(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking SOCKS5 readiness: ${e.message}", e)
            Socks5ReadinessChecker.setSocks5Ready(false)
        }
    }
    
    /**
     * Apply routing decision based on ONNX model output.
     * 
     * @param sni The Server Name Indication
     * @param routingDecisionIndex 0=proxy, 1=direct, 2=optimized
     */
    private fun applyRoutingDecision(sni: String, routingDecisionIndex: Int) {
        // TODO: Implement actual routing decision application
        // This could involve:
        // - Modifying Xray routing rules dynamically
        // - Updating TProxy configuration
        // - Applying split tunneling rules
        
        when (routingDecisionIndex) {
            0 -> {
                // Route via proxy (default behavior)
                Log.d(TAG, "Routing decision: proxy for $sni")
            }
            1 -> {
                // Route direct (bypass proxy)
                Log.d(TAG, "Routing decision: direct for $sni")
                // Future: Add to direct routing rules
            }
            2 -> {
                // Route optimized (special handling)
                Log.d(TAG, "Routing decision: optimized for $sni")
                // Future: Apply optimized routing configuration
            }
        }
    }

    @Suppress("SameParameterValue")
    private fun createNotification(channelName: String) {
        val i = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Create persistent notification to prevent system from killing the service
        val notification = NotificationCompat.Builder(this, channelName)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("VPN service is running")
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentIntent(pi)
            .setOngoing(true) // Make notification persistent
            .setPriority(NotificationCompat.PRIORITY_HIGH) // High priority
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false) // Don't show timestamp
            .setOnlyAlertOnce(true) // Don't alert on updates
            .build()
            
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification)
        } else {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        
        Log.d(TAG, "Foreground notification created and displayed")
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
        // Use LOW importance to reduce notification sound/vibration but keep it visible
        // This helps prevent system from killing the service
        val channel = NotificationChannel(channelName, name, NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false) // Don't show badge
        channel.enableLights(false) // Don't use LED
        channel.enableVibration(false) // Don't vibrate
        channel.setSound(null, null) // No sound
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
        const val ACTION_SOCKS5_READY: String = "com.hyperxray.an.SOCKS5_READY"
        const val EXTRA_LOG_DATA: String = "log_data"
        const val EXTRA_ERROR_MESSAGE: String = "error_message"
        private const val TAG = "VpnService"
        // Ultra-optimized broadcast settings for maximum performance
        // Larger batches = fewer broadcasts = better performance
        private const val BROADCAST_DELAY_MS: Long = 1000 // 1 second delay for larger batches
        private const val BROADCAST_BUFFER_SIZE_THRESHOLD: Int = 100 // Larger batches (100 entries)

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
            
            // CRITICAL: Increase UDP timeout to match Xray policy timeout (30 minutes)
            // Default native tunnel UDP timeout is 60 seconds, which is too short
            // This causes "closed pipe" errors when UDP dispatcher closes connections
            // Set to 1800000ms (30 minutes) to match Xray connIdle timeout
            val udpTimeout = 1800000 // 30 minutes in milliseconds
            
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
    }
}