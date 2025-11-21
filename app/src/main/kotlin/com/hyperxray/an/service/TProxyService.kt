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
import com.hyperxray.an.xray.runtime.MultiXrayCoreManager
import com.hyperxray.an.core.network.dns.SystemDnsCacheServer
import com.hyperxray.an.core.network.dns.DnsCacheManager
import com.hyperxray.an.xray.runtime.LogLineCallback
import com.hyperxray.an.notification.TelegramNotificationManager
import com.hyperxray.an.ui.screens.log.extractDnsQuery
import com.hyperxray.an.ui.screens.log.extractSniffedDomain
import java.net.InetAddress
import com.hyperxray.an.xray.runtime.XrayRuntimeStatus
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
@OptIn(ExperimentalCoroutinesApi::class)
class TProxyService : VpnService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    // Lock-free queue for log broadcasts (replaces synchronized logBroadcastBuffer)
    // Limited capacity with DROP_OLDEST to prevent memory leaks under high traffic
    private val logBroadcastChannel = Channel<String>(
        capacity = 1000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val broadcastBuffer = mutableListOf<String>()
    private val reusableBroadcastList = ArrayList<String>(100)
    
    init {
        // Start background broadcaster coroutine (lock-free)
        serviceScope.launch {
            try {
                while (isActive) {
                    try {
                        val logEntry = logBroadcastChannel.receive()
                        broadcastBuffer.add(logEntry)

                        if (broadcastBuffer.size >= TProxyService.BROADCAST_BUFFER_SIZE_THRESHOLD) {
                            broadcastLogsBatch()
                        } else {
                            kotlinx.coroutines.selects.select<Unit> {
                                logBroadcastChannel.onReceive { newLog ->
                                    broadcastBuffer.add(newLog)
                                    if (broadcastBuffer.size >= TProxyService.BROADCAST_BUFFER_SIZE_THRESHOLD) {
                                        broadcastLogsBatch()
                                    }
                                }
                                onTimeout(TProxyService.BROADCAST_DELAY_MS) {
                                    if (broadcastBuffer.isNotEmpty()) {
                                        broadcastLogsBatch()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TProxyService.TAG, "Error in log broadcast coroutine", e)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TProxyService.TAG, "Error in log broadcast coroutine", e)
                }
            }
        }
    }
    
    private fun broadcastLogsBatch() {
        if (broadcastBuffer.isEmpty()) return
        
        val logUpdateIntent = Intent(TProxyService.ACTION_LOG_UPDATE)
        logUpdateIntent.setPackage(application.packageName)
        
        // Optimize: reuse list and only resize if needed
        reusableBroadcastList.clear()
        if (broadcastBuffer.size > reusableBroadcastList.size) {
            reusableBroadcastList.ensureCapacity(broadcastBuffer.size)
        }
        reusableBroadcastList.addAll(broadcastBuffer)
        logUpdateIntent.putStringArrayListExtra(TProxyService.EXTRA_LOG_DATA, reusableBroadcastList)
        sendBroadcast(logUpdateIntent)
        broadcastBuffer.clear()
        Log.d(TAG, "Broadcasted a batch of logs.")
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
                    Log.d(TProxyService.TAG, "Port $port unavailable: ${it.message}")
                }.onSuccess {
                    return port
                }
            }
        return null
    }

    private lateinit var logFileManager: LogFileManager

    @Volatile
    private var xrayProcess: Process? = null
    private var multiXrayCoreManager: MultiXrayCoreManager? = null
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
    private var socks5PeriodicCheckJob: Job? = null
    
    // System DNS cache server for system-wide DNS caching
    private var systemDnsCacheServer: SystemDnsCacheServer? = null
    
    // DNS cache integration - intercepts DNS queries from Xray logs (no root required)
    @Volatile
    private var dnsCacheInitialized = false
    
    // UDP monitoring
    private var udpMonitoringJob: Job? = null
    private var lastUdpStats: UdpStats? = null
    private var lastUdpStatsTime: Long = 0L
    
    // UDP recovery mechanism
    private var udpRecoveryAttempts = 0
    private var lastUdpRecoveryTime: Long = 0L
    private val MAX_RECOVERY_ATTEMPTS = 3
    private val RECOVERY_COOLDOWN_MS = 30000L // 30 seconds between recovery attempts
    
    // Connection reset error tracking
    private var connectionResetErrorCount = 0
    private var lastConnectionResetTime: Long = 0L
    private val CONNECTION_RESET_THRESHOLD = 5 // Alert after 5 resets
    private val CONNECTION_RESET_WINDOW_MS = 60000L // 1 minute window
    
    // TProxy restart throttling - limit restarts to once per 6 hours
    @Volatile
    private var lastTProxyRestartTime: Long = 0L
    private val TPROXY_RESTART_INTERVAL_MS = 6L * 60L * 60L * 1000L // 6 hours
    
    // Telegram notification manager
    private var telegramNotificationManager: TelegramNotificationManager? = null

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
            var lastDnsCacheNotificationTime = 0L
            val DNS_CACHE_NOTIFICATION_INTERVAL_MS = 10L * 60L * 1000L // 10 minutes
            
            while (isActive) {
                try {
                    // Adaptive polling: adjust interval based on traffic
                    val interval = calculateAdaptivePollingInterval(coreStatsState)
                    delay(interval)
                    // Update notification periodically to show service is alive
                    val currentChannelName = if (Preferences(this@TProxyService).disableVpn) "nosocks" else "socks5"
                    createNotification(currentChannelName)
                    Log.d(TProxyService.TAG, "Heartbeat: Service alive, notification updated")
                    
                    // Send periodic DNS cache statistics (every 10 minutes)
                    val now = System.currentTimeMillis()
                    if (lastDnsCacheNotificationTime == 0L || 
                        (now - lastDnsCacheNotificationTime) >= DNS_CACHE_NOTIFICATION_INTERVAL_MS) {
                        telegramNotificationManager?.let { manager ->
                            serviceScope.launch {
                                manager.notifyDnsCacheInfo()
                                lastDnsCacheNotificationTime = now
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TProxyService.TAG, "Heartbeat error: ${e.message}", e)
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
        
        // Initialize Telegram notification manager
        try {
            telegramNotificationManager = TelegramNotificationManager.getInstance(this)
            Log.d(TAG, "Telegram notification manager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Telegram notification manager", e)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val action = intent.action
        when (action) {
            TProxyService.ACTION_DISCONNECT -> {
                stopXray()
                return START_NOT_STICKY
            }

            TProxyService.ACTION_RELOAD_CONFIG -> {
                // Ensure notification is shown
                val channelName = if (Preferences(this).disableVpn) "nosocks" else "socks5"
                initNotificationChannel(channelName)
                createNotification(channelName)
                
                val prefs = Preferences(this)
                if (prefs.disableVpn) {
                    Log.d(TProxyService.TAG, "Received RELOAD_CONFIG action (core-only mode)")
                    reloadingRequested = true
                    xrayProcess?.destroy()
                    serviceScope.launch { runXrayProcess() }
                    return START_STICKY
                }
                if (tunFd == null) {
                    Log.w(TProxyService.TAG, "Cannot reload config, VPN service is not running.")
                    return START_STICKY
                }
                Log.d(TAG, "Received RELOAD_CONFIG action.")
                reloadingRequested = true
                xrayProcess?.destroy()
                serviceScope.launch { runXrayProcess() }
                return START_STICKY
            }

            TProxyService.ACTION_START -> {
                logFileManager.clearLogsSync()
                val prefs = Preferences(this)
                if (prefs.disableVpn) {
                    serviceScope.launch { runXrayProcess() }
                    val successIntent = Intent(TProxyService.ACTION_START)
                    // Send Telegram notification
                    telegramNotificationManager?.let { manager ->
                        serviceScope.launch {
                            manager.notifyVpnStatus(true)
                        }
                    }
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
                
                logFileManager.clearLogsSync()
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
        
        // Stop system DNS cache server
        systemDnsCacheServer?.stop()
        systemDnsCacheServer?.shutdown()
        
        // Shutdown DNS cache manager (flush cache and cleanup resources)
        DnsCacheManager.shutdown()
        systemDnsCacheServer = null
        
        // Shutdown DNS cache manager (flush cache and cleanup resources)
        DnsCacheManager.shutdown()
        
        // Stop heartbeat
        heartbeatJob?.cancel()
        heartbeatJob = null
        
        // Release wake lock
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TProxyService.TAG, "WakeLock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock: ${e.message}", e)
        }
        
        // Stop Xray and clean up all resources
        serviceScope.launch {
            stopXray()
        }
        
        // Flush log file buffer before shutdown (async)
        serviceScope.launch {
            logFileManager.flush()
        }
        
        // Stop AI optimizer
        tproxyAiOptimizer?.stopOptimization()
        tproxyAiOptimizer = null
        
        // Cleanup MultiXrayCoreManager (singleton instance)
        multiXrayCoreManager?.cleanup()
        multiXrayCoreManager = null
        // Reset singleton instance since service is being destroyed
        // This ensures a fresh instance is created if service is restarted
        MultiXrayCoreManager.resetInstance()
        
        // Release ONNX Runtime Manager
        OnnxRuntimeManager.release()
        
        Log.d(TAG, "TProxyService destroyed.")
        // Let Android handle service lifecycle - do not call exitProcess(0)
    }

    override fun onRevoke() {
        serviceScope.launch {
            stopXray()
        }
        super.onRevoke()
    }

    private fun startXray() {
        // Reset UDP error tracking when starting fresh connection
        udpErrorCount = 0
        lastUdpErrorTime = 0L
        serviceStartTime = System.currentTimeMillis()
        udpRecoveryAttempts = 0
        lastUdpRecoveryTime = 0L
        synchronized(udpErrorHistory) {
            udpErrorHistory.clear()
        }
        
        // Synchronous connection process: start VPN service first, then Xray process
        serviceScope.launch {
            // Step 1: Start VPN service (TUN interface)
            startService()
            // Step 2: Start Xray process only after VPN service is ready
            runXrayProcess()
        }
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

    private suspend fun runXrayProcess() {
        try {
            Log.d(TAG, "Attempting to start xray process(es).")
                val prefs = Preferences(applicationContext)
                val instanceCount = prefs.xrayCoreInstanceCount
                
                // Validate config path is within app's private directory
                val selectedConfigPath = prefs.selectedConfigPath
                if (selectedConfigPath == null) {
                    val errorMessage = "No configuration file selected."
                    Log.e(TProxyService.TAG, errorMessage)
                    val errorIntent = Intent(TProxyService.ACTION_ERROR)
                    errorIntent.setPackage(application.packageName)
                    errorIntent.putExtra(TProxyService.EXTRA_ERROR_MESSAGE, errorMessage)
                    sendBroadcast(errorIntent)
                    handler.post {
                        if (!isStopping) {
                            serviceScope.launch {
                                stopXray()
                            }
                        }
                    }
                    return
                }
                
                val configFile = validateConfigPath(selectedConfigPath)
                if (configFile == null) {
                    val errorMessage = "Invalid configuration file: path validation failed or file not accessible."
                    Log.e(TProxyService.TAG, errorMessage)
                    // Broadcast error to UI
                    val errorIntent = Intent(TProxyService.ACTION_ERROR)
                    errorIntent.setPackage(application.packageName)
                    errorIntent.putExtra(TProxyService.EXTRA_ERROR_MESSAGE, errorMessage)
                    sendBroadcast(errorIntent)
                    // Stop the service since we can't proceed without a valid config
                    handler.post {
                        if (!isStopping) {
                            serviceScope.launch {
                                stopXray()
                            }
                        }
                    }
                    return
                }
                
                // Read config content securely (after validation)
                val configContent = readConfigContentSecurely(configFile)
                if (configContent == null) {
                    val errorMessage = "Failed to read configuration file."
                    Log.e(TProxyService.TAG, errorMessage)
                    // Broadcast error to UI
                    val errorIntent = Intent(TProxyService.ACTION_ERROR)
                    errorIntent.setPackage(application.packageName)
                    errorIntent.putExtra(TProxyService.EXTRA_ERROR_MESSAGE, errorMessage)
                    sendBroadcast(errorIntent)
                    // Stop the service since we can't proceed without config content
                    handler.post {
                        if (!isStopping) {
                            serviceScope.launch {
                                stopXray()
                            }
                        }
                    }
                    return
                }
                
                // SOCKS5 readiness check should happen AFTER Xray-core starts, not before.
                // Previous blocking check removed to prevent deadlock.

                
                // Use MultiXrayCoreManager for multiple instances, or fallback to single process
                if (instanceCount > 1) {
                    Log.d(TProxyService.TAG, "Starting $instanceCount xray-core instances using MultiXrayCoreManager")
                    
                    // Initialize MultiXrayCoreManager if not already initialized
                    if (multiXrayCoreManager == null) {
                        multiXrayCoreManager = MultiXrayCoreManager.getInstance(applicationContext)
                        
                        // Set log callback to forward logs to logFileManager and broadcast
                        // Also intercept DNS queries and cache them (no root required)
                        multiXrayCoreManager?.setLogLineCallback(LogLineCallback { line ->
                            // Write to log file
                            logFileManager.appendLog(line)
                            // Broadcast to UI (lock-free channel)
                            logBroadcastChannel.trySend(line)
                            
                            // Intercept DNS queries from Xray-core logs and cache them
                            // This allows browser and other apps to benefit from DNS cache
                            // Root is NOT required - works by parsing Xray DNS logs
                            interceptDnsFromXrayLogs(line)
                        })
                        
                        // Observe instance status changes and broadcast to MainViewModel
                        serviceScope.launch {
                            multiXrayCoreManager?.instancesStatus?.collect { statusMap ->
                                // Broadcast instance status update whenever status changes
                                val statusIntent = Intent(TProxyService.ACTION_INSTANCE_STATUS_UPDATE)
                                statusIntent.setPackage(application.packageName)
                                // Store instance count and status info in intent
                                statusIntent.putExtra("instance_count", statusMap.size)
                                statusIntent.putExtra("has_running", statusMap.values.any { it is XrayRuntimeStatus.Running })
                                
                                // Add PID and port info for each instance
                                statusMap.forEach { (index, status) ->
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
                                
                                sendBroadcast(statusIntent)
                                Log.d(TProxyService.TAG, "Broadcasted instance status update: ${statusMap.size} instances")
                            }
                        }
                    }
                    
                    val excludedPorts = extractPortsFromJson(configContent)
                    val startedInstances = multiXrayCoreManager!!.startInstances(
                        count = instanceCount,
                        configPath = selectedConfigPath,
                        configContent = configContent,
                        excludedPorts = excludedPorts
                    )
                    
                    // Immediately broadcast initial status after instances start
                    val initialStatus = multiXrayCoreManager!!.instancesStatus.value
                    if (initialStatus.isNotEmpty()) {
                        val statusIntent = Intent(TProxyService.ACTION_INSTANCE_STATUS_UPDATE)
                        statusIntent.setPackage(application.packageName)
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
                        
                        sendBroadcast(statusIntent)
                        Log.d(TProxyService.TAG, "Broadcasted initial instance status: ${initialStatus.size} instances")
                    }
                    
                    if (startedInstances.isEmpty()) {
                        val errorMessage = "Failed to start any xray-core instances."
                        Log.e(TProxyService.TAG, errorMessage)
                        val errorIntent = Intent(TProxyService.ACTION_ERROR)
                        errorIntent.setPackage(application.packageName)
                        errorIntent.putExtra(TProxyService.EXTRA_ERROR_MESSAGE, errorMessage)
                        sendBroadcast(errorIntent)
                        handler.post {
                            if (!isStopping) {
                                serviceScope.launch {
                                    stopXray()
                                }
                            }
                        }
                        return
                    }
                    
                    // Set the first instance's port as the primary API port for backward compatibility
                    val firstPort = startedInstances.values.firstOrNull()
                    if (firstPort != null) {
                        prefs.apiPort = firstPort
                        Log.d(TProxyService.TAG, "Set primary API port to $firstPort (from ${startedInstances.size} instances)")
                    }
                    
                    // Start SOCKS5 readiness check immediately after first instance starts
                    // startInstances() now returns as soon as first instance is Running
                    socks5ReadinessChecked = false
                    socks5ReadinessJob = serviceScope.launch {
                        // Check if any instance is already Running (startInstances may have returned early)
                        val manager = multiXrayCoreManager
                        if (manager != null) {
                            // CRITICAL: Use StateFlow collector to get real-time status updates
                            // This ensures we catch status updates immediately when they happen
                            var hasFoundRunning = false
                            
                            // First, check current status (might already be Running)
                            val initialStatuses = manager.instancesStatus.value
                            val hasRunningInstance = initialStatuses.values.any { it is XrayRuntimeStatus.Running }
                            
                            if (hasRunningInstance) {
                                Log.d(TProxyService.TAG, "At least one instance is already Running, starting SOCKS5 readiness check immediately")
                                checkSocks5Readiness(prefs)
                                hasFoundRunning = true
                            }
                            
                            // If not found immediately, wait for status update using StateFlow collector
                            if (!hasFoundRunning && !isStopping) {
                                // Use StateFlow first() with timeout to get the first Running status
                                // This ensures we catch status updates immediately when they happen
                                try {
                                    val runningStatus = withTimeoutOrNull(5000) { // 5 second timeout
                                        manager.instancesStatus.first { statusMap ->
                                            statusMap.values.any { it is XrayRuntimeStatus.Running }
                                        }
                                    }
                                    
                                    if (runningStatus != null && !hasFoundRunning) {
                                        Log.d(TProxyService.TAG, "First instance is Running (detected via StateFlow), starting SOCKS5 readiness check")
                                        checkSocks5Readiness(prefs)
                                        hasFoundRunning = true
                                    }
                                } catch (e: Exception) {
                                    Log.w(TProxyService.TAG, "Error waiting for Running status: ${e.message}")
                                }
                                
                                // Final check if we didn't find Running instance via StateFlow
                                if (!hasFoundRunning && !isStopping) {
                                    val finalStatuses = manager.instancesStatus.value
                                    val hasRunning = finalStatuses.values.any { it is XrayRuntimeStatus.Running }
                                    
                                    if (hasRunning) {
                                        Log.d(TProxyService.TAG, "First instance is Running (final check), starting SOCKS5 readiness check")
                                        checkSocks5Readiness(prefs)
                                        hasFoundRunning = true
                                    } else {
                                        Log.w(TProxyService.TAG, "SOCKS5 readiness check not triggered - no instances reached Running state within timeout")
                                    }
                                }
                            }
                            
                            // Start periodic SOCKS5 health check after initial check
                            if (socks5ReadinessChecked) {
                                startPeriodicSocks5HealthCheck(prefs)
                            }
                            
                            // Start UDP monitoring
                            if (udpMonitoringJob == null) {
                                startUdpMonitoring()
                            }
                        }
                    }
                    
                    Log.i(TProxyService.TAG, "Successfully started ${startedInstances.size} xray-core instances")
                } else {
                    // Fallback to single process mode for backward compatibility
                    Log.d(TProxyService.TAG, "Starting single xray-core instance (legacy mode)")
                    runXrayProcessLegacy(configFile, configContent, prefs)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting xray process(es)", e)
            val errorIntent = Intent(ACTION_ERROR)
            errorIntent.setPackage(application.packageName)
            errorIntent.putExtra(TProxyService.EXTRA_ERROR_MESSAGE, "Failed to start: ${e.message}")
            sendBroadcast(errorIntent)
            handler.post {
                if (!isStopping) {
                    serviceScope.launch {
                        stopXray()
                    }
                }
            }
        }
    }
    
    private suspend fun runXrayProcessLegacy(configFile: File, configContent: String, prefs: Preferences) {
        var currentProcess: Process? = null
        try {
            val libraryDir = getNativeLibraryDir(applicationContext)
            if (libraryDir == null) {
                val errorMessage = "Failed to get native library directory."
                Log.e(TAG, errorMessage)
                
                // Send Telegram notification for library directory error
                telegramNotificationManager?.let { manager ->
                    serviceScope.launch {
                        manager.notifyError(
                            "Xray Library Error\n\n" +
                            "Failed to get native library directory.\n" +
                            "Xray-core cannot start."
                        )
                    }
                }
                
                val errorIntent = Intent(ACTION_ERROR)
                errorIntent.setPackage(application.packageName)
                errorIntent.putExtra(TProxyService.EXTRA_ERROR_MESSAGE, errorMessage)
                sendBroadcast(errorIntent)
                handler.post {
                    if (!isStopping) {
                        serviceScope.launch {
                            stopXray()
                        }
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
                
                // Send Telegram notification for port allocation error
                telegramNotificationManager?.let { manager ->
                    serviceScope.launch {
                        manager.notifyError(
                            "Port Allocation Error\n\n" +
                            "All ports in range 10000-65535 are in use or excluded.\n" +
                            "Xray-core cannot start without an available port."
                        )
                    }
                }
                
                val errorIntent = Intent(ACTION_ERROR)
                errorIntent.setPackage(application.packageName)
                errorIntent.putExtra(TProxyService.EXTRA_ERROR_MESSAGE, errorMessage)
                sendBroadcast(errorIntent)
                handler.post {
                    if (!isStopping) {
                        serviceScope.launch {
                            stopXray()
                        }
                    }
                }
                return
            }
            prefs.apiPort = apiPort
            Log.d(TAG, "Found and set API port: $apiPort")

            val processBuilder = getProcessBuilder(xrayPath)
            currentProcess = processBuilder.start()
            this.xrayProcess = currentProcess

            // CRITICAL: Start reading process output IMMEDIATELY (before config write)
            // This allows us to capture error messages even if process crashes before/during config read
            val processOutputJob = serviceScope.launch {
                readProcessStreamWithTimeout(currentProcess)
            }
            
            // Small delay to allow process to potentially output startup errors
            Thread.sleep(100)
            
            // Validate process startup with periodic checks to catch early exits
            // Check process status multiple times instead of fixed sleep to detect failures quickly
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
                if (!currentProcess.isAlive) {
                    val exitValue = try {
                        currentProcess.exitValue()
                    } catch (e: IllegalThreadStateException) {
                        -1
                    }
                    val errorMessage = "Xray process exited during startup after ${checksPerformed * checkInterval}ms (exit code: $exitValue)"
                    Log.e(TProxyService.TAG, errorMessage)
                    
                    // Send Telegram notification for Xray startup crash
                    telegramNotificationManager?.let { manager ->
                        serviceScope.launch {
                            manager.notifyError(
                                "Xray Startup Crash\n\n" +
                                "Xray process exited during startup.\n" +
                                "Exit code: $exitValue\n" +
                                "Startup time: ${checksPerformed * checkInterval}ms"
                            )
                        }
                    }
                    
                    throw IOException(errorMessage)
                }
                
                // After minimum checks, if process is still alive, consider it started
                // Note: We can't easily verify if process is fully ready, but being alive
                // for a reasonable time (100ms+) is a good indicator it started successfully
                if (checksPerformed >= minStartupChecks) {
                    processValidated = true
                    Log.d(TProxyService.TAG, "Process startup validated after ${checksPerformed * checkInterval}ms")
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
                    Log.e(TProxyService.TAG, errorMessage)
                    
                    // Send Telegram notification for Xray validation crash
                    telegramNotificationManager?.let { manager ->
                        serviceScope.launch {
                            manager.notifyError(
                                "Xray Validation Crash\n\n" +
                                "Xray process exited during startup validation.\n" +
                                "Exit code: $exitValue"
                            )
                        }
                    }
                    
                    throw IOException(errorMessage)
                }
                // Process is alive but we hit timeout - log warning but proceed
                Log.w(TProxyService.TAG, "Process startup validation hit timeout (${maxStartupChecks * checkInterval}ms), but process is alive. Proceeding.")
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
                                    Log.i(TProxyService.TAG, "🔍 VERIFICATION: dokodemo-door inbound #${i+1}: network=${network.toString()}, UDP enabled=$udpEnabled")
                                } else {
                                    Log.w(TProxyService.TAG, "⚠️ VERIFICATION FAILED: dokodemo-door inbound #${i+1} network is not an array: $network")
                                }
                            } else {
                                Log.w(TProxyService.TAG, "⚠️ VERIFICATION FAILED: dokodemo-door inbound #${i+1} has no settings object")
                            }
                        }
                    }
                    if (dokodemoFound && !udpEnabled) {
                        Log.e(TProxyService.TAG, "❌ CRITICAL ERROR: dokodemo-door inbound found but UDP is NOT enabled in config!")
                    } else if (dokodemoFound && udpEnabled) {
                        Log.i(TProxyService.TAG, "✅ VERIFICATION SUCCESS: dokodemo-door inbound has UDP enabled in config")
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
                Log.d(TAG, "Config written to Xray stdin successfully")
            } catch (e: IOException) {
                if (!currentProcess.isAlive) {
                    val exitValue = try { currentProcess.exitValue() } catch (ex: IllegalThreadStateException) { -1 }
                    Log.e(TProxyService.TAG, "Xray process exited while writing config, exit code: $exitValue")
                    
                    // Try to read any error output before it's lost
                    try {
                        val errorReader = BufferedReader(InputStreamReader(currentProcess.inputStream))
                        val errorOutput = StringBuilder()
                        var lineCount = 0
                        var line = errorReader.readLine()
                        while (lineCount < 10 && line != null) {
                            errorOutput.append(line).append("\n")
                            line = errorReader.readLine()
                            lineCount++
                        }
                        if (errorOutput.isNotEmpty()) {
                            Log.e(TProxyService.TAG, "Xray error output before crash:\n$errorOutput")
                        }
                    } catch (ex: Exception) {
                        Log.w(TProxyService.TAG, "Could not read error output: ${ex.message}")
                    }
                }
                throw e
            }
            
            // Start SOCKS5 readiness check after Xray process starts
            // Check readiness both when we detect "Xray ... started" in logs AND
            // after a fixed delay to ensure we catch it even if log format changes
            socks5ReadinessChecked = false
            
            // CRITICAL: Check if process is still alive after config write (synchronous)
            // Xray may exit immediately if config is invalid
            // Wait synchronously to ensure process has processed config
            delay(500) // Wait for process to process config and potentially output errors
            
            if (!currentProcess.isAlive) {
                val exitValue = try {
                    currentProcess.exitValue()
                } catch (e: IllegalThreadStateException) {
                    -1
                }
                
                // Try to read error output before process cleanup
                val errorOutput = StringBuilder()
                try {
                    val reader = BufferedReader(InputStreamReader(currentProcess.inputStream))
                    var lineCount = 0
                    var line = reader.readLine()
                    while (lineCount < 20 && line != null) {
                        errorOutput.append(line).append("\n")
                        line = reader.readLine()
                        lineCount++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read process output: ${e.message}")
                }
                
                // Wait a bit more to allow stream reading job to capture error messages
                delay(1000)
                processOutputJob.cancel()
                
                val errorMessage = if (errorOutput.isNotEmpty()) {
                    "Xray process exited immediately after config write (exit code: $exitValue)\n\n" +
                    "Xray error output:\n$errorOutput"
                } else {
                    "Xray process exited immediately after config write (exit code: $exitValue)\n\n" +
                    "No error output captured. Possible causes:\n" +
                    "1. Invalid JSON config format\n" +
                    "2. Missing required config fields\n" +
                    "3. Invalid inbound/outbound configuration\n" +
                    "4. File permissions issue\n" +
                    "5. Missing geoip/geosite files"
                }
                
                Log.e(TProxyService.TAG, errorMessage)
                
                // Send detailed error notification
                telegramNotificationManager?.let { manager ->
                    serviceScope.launch {
                        manager.notifyError(
                            "Xray Immediate Crash After Config\n\n" +
                            "Exit code: $exitValue\n\n" +
                            if (errorOutput.isNotEmpty()) {
                                "Xray Error Output:\n${errorOutput.toString().take(500)}\n\n"
                            } else {
                                ""
                            } +
                            "Check log file and config file for details."
                        )
                    }
                }
                
                val errorIntent = Intent(ACTION_ERROR)
                errorIntent.setPackage(application.packageName)
                errorIntent.putExtra(TProxyService.EXTRA_ERROR_MESSAGE, errorMessage)
                sendBroadcast(errorIntent)
                
                throw IOException(errorMessage)
            }
            
            // Synchronous process: SOCKS5 readiness is checked before Xray startup
            // No background check needed since we wait synchronously
            socks5ReadinessChecked = true
            
            Log.d(TAG, "Xray process started successfully, monitoring output stream.")
        } catch (e: InterruptedIOException) {
            Log.d(TAG, "Xray process reading interrupted.")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing xray", e)
            
            // Send Telegram error notification for Xray crash
            telegramNotificationManager?.let { manager ->
                serviceScope.launch {
                    manager.notifyError(
                        "Xray process error: ${e.message ?: "Unknown error"}\n\n" +
                        "Error type: ${e.javaClass.simpleName}"
                    )
                }
            }
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
                
                // Send Telegram notification for unexpected process exit
                if (currentProcess != null && !currentProcess.isAlive) {
                    val exitValue = try {
                        currentProcess.exitValue()
                    } catch (e: IllegalThreadStateException) {
                        -1
                    }
                    telegramNotificationManager?.let { manager ->
                        serviceScope.launch {
                            manager.notifyError(
                                "Xray process exited unexpectedly\n\n" +
                                "Exit code: $exitValue\n" +
                                "Process may have crashed"
                            )
                        }
                    }
                }
                
                // Use handler to call stopXray on main thread to avoid reentrancy issues
                handler.post {
                    if (!isStopping) {
                        serviceScope.launch {
                            stopXray()
                        }
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
    private suspend fun readProcessStreamWithTimeout(process: Process) {
        var readJob: Job? = null
        var healthCheckJob: Job? = null
        
        try {
            // Health check coroutine monitors process and cancels read job if needed
            healthCheckJob = serviceScope.launch(Dispatchers.IO) {
                    try {
                        while (isActive) {
                            delay(2000) // Check every 2 seconds
                            ensureActive() // Check for cancellation
                            
                            // Check if we're stopping
                            if (isStopping) {
                                Log.d(TProxyService.TAG, "Health check detected stop request, cancelling read job.")
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
                                Log.d(TProxyService.TAG, "Health check detected process death (exit code: $exitValue), cancelling read job.")
                                readJob?.cancel()
                                break
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TProxyService.TAG, "Error in health check coroutine: ${e.message}", e)
                        }
                    }
                }
                
            // Stream reading coroutine - can be cancelled by health check
            // Note: readLine() is blocking I/O and can't be interrupted, but we can check
            // cancellation status between reads and exit the loop when cancelled
            readJob = serviceScope.launch(Dispatchers.IO) {
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
                                            Log.d(TProxyService.TAG, "Read timeout detected, process is dead (exit code: $exitValue)")
                                            break
                                        }
                                        Log.w(TProxyService.TAG, "Read timeout: no data for ${readTimeout}ms, but process is alive. Continuing...")
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
                                        Log.d(TProxyService.TAG, "Stream unavailable (likely closed): ${e.message}")
                                        break
                                    }
                                    
                                    // Read line (this will block if data is available but line is incomplete)
                                    // However, we've already checked that reader is ready, so this should be quick
                                    val line = reader.readLine()
                                    
                                    if (line == null) {
                                        // EOF - stream is closed
                                        Log.d(TProxyService.TAG, "Stream reached EOF (null read)")
                                        break
                                    }
                                    
                                    // Update last read time on successful read
                                    lastReadTime = System.currentTimeMillis()
                                    
                                    // Process the log line
                                    logFileManager.appendLog(line)
                                    // Broadcast to UI (lock-free channel)
                                    logBroadcastChannel.trySend(line)
                                    
                                    // Check if Xray has started (indicates SOCKS5 should be ready soon)
                                    // Xray logs "Xray ... started" when it's fully initialized
                                    if (!socks5ReadinessChecked && line.contains("started", ignoreCase = true) && 
                                        line.contains("Xray", ignoreCase = true)) {
                                        Log.d(TProxyService.TAG, "Detected Xray startup in logs, checking SOCKS5 readiness")
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
                                    
                                    // CRITICAL: Detect connection reset errors and handle them
                                    // These errors indicate Xray-core is having trouble connecting to SOCKS5
                                    detectConnectionResetErrors(line)
                                    
                                    // Process SNI for TLS optimization
                                    processSNIFromLog(line)
                                    
                                    // Small delay to allow cancellation to be checked
                                    // This ensures we can respond to cancellation requests
                                    delay(10)
                                    
                                } catch (e: InterruptedIOException) {
                                    Log.d(TProxyService.TAG, "Stream read interrupted: ${e.message}")
                                    break
                                } catch (e: IOException) {
                                    // Check if process is still alive
                                    if (!process.isAlive) {
                                        val exitValue = try {
                                            process.exitValue()
                                        } catch (ex: IllegalThreadStateException) {
                                            -1
                                        }
                                        Log.d(TProxyService.TAG, "IOException during read, process is dead (exit code: $exitValue): ${e.message}")
                                    } else {
                                        Log.e(TProxyService.TAG, "IOException during stream read (process alive): ${e.message}", e)
                                    }
                                    break
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    Log.d(TProxyService.TAG, "Read coroutine cancelled")
                                    throw e // Re-throw to properly handle cancellation
                                } catch (e: Exception) {
                                    if (isActive) {
                                        Log.e(TProxyService.TAG, "Unexpected error during stream read: ${e.message}", e)
                                    }
                                    break
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        Log.d(TProxyService.TAG, "Read coroutine cancelled during stream reading")
                        throw e // Re-throw to properly handle cancellation
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TProxyService.TAG, "Error in read coroutine: ${e.message}", e)
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
                    Log.w(TProxyService.TAG, "Error cancelling stream reading coroutines: ${e.message}", e)
                }
                
                // Wait before closing streams to allow process to finish writing
                // This prevents "closed pipe" errors when Xray is handling UDP traffic
                // Increased delays to give more time for UDP operations to complete
                delay(200) // Initial delay to let process finish current operations
                
                // Only close streams if process is dead, or add delay if still alive
                val processAlive = process.isAlive
                if (processAlive) {
                    // Process still alive - wait more before closing streams to avoid pipe errors
                    delay(300) // Increased to 300ms for better UDP cleanup
                }
                
                // Now close streams - process should have finished writing by now
                try {
                    process.inputStream?.close()
                } catch (e: Exception) {
                    Log.w(TProxyService.TAG, "Error closing process input stream", e)
                }
                try {
                    process.errorStream?.close()
                } catch (e: Exception) {
                    Log.w(TProxyService.TAG, "Error closing process error stream", e)
                }
                try {
                    process.outputStream?.close()
                } catch (e: Exception) {
                    Log.w(TProxyService.TAG, "Error closing process output stream", e)
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

    private suspend fun startService() {
        synchronized(tunFdLock) {
            if (tunFd != null) return
        }

        val prefs = Preferences(this)
        
        // Start system DNS cache server before building VPN (needed for DNS configuration)
        try {
            if (systemDnsCacheServer == null) {
                systemDnsCacheServer = SystemDnsCacheServer.getInstance(this)
            }
            systemDnsCacheServer?.start()
        } catch (e: Exception) {
            Log.w(TAG, "Error starting DNS cache server: ${e.message}", e)
        }
        
        val builder = getVpnBuilder(prefs, systemDnsCacheServer)
        val newTunFd = builder.establish()

        synchronized(tunFdLock) {
            tunFd = newTunFd
        }

        if (newTunFd == null) {
            // Send Telegram notification for VPN setup failure
            telegramNotificationManager?.let { manager ->
                serviceScope.launch {
                    manager.notifyError(
                        "VPN setup failed\n\n" +
                        "Failed to establish VPN interface (TUN).\n" +
                        "Please check VPN permissions."
                    )
                }
            }
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
            
            // Send Telegram notification for TProxy config write failure
            telegramNotificationManager?.let { manager ->
                serviceScope.launch {
                    manager.notifyError(
                        "TProxy configuration error\n\n" +
                        "Failed to write TProxy config file: ${e.message}\n" +
                        "VPN connection may not work properly."
                    )
                }
            }
            
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
                    // Check if enough time has passed since last restart (6 hours minimum)
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastRestart = currentTime - lastTProxyRestartTime
                    
                    if (timeSinceLastRestart >= TPROXY_RESTART_INTERVAL_MS) {
                        Log.i(TProxyService.TAG, "AI optimizer applied new configuration, reloading TProxy...")
                        // Reload TProxy configuration by recreating the config file and restarting
                        serviceScope.launch {
                        try {
                            // Check if we're stopping before proceeding
                            if (isStopping) {
                                Log.w(TProxyService.TAG, "Skipping TProxy reload - service is stopping")
                                return@launch
                            }
                            
                            val tproxyFile = File(cacheDir, "tproxy.conf")
                            if (!tproxyFile.exists()) {
                                Log.w(TProxyService.TAG, "TProxy config file does not exist, skipping reload")
                                return@launch
                            }
                            
                            // Update config file first (async I/O to avoid blocking)
                            try {
                                withContext(Dispatchers.IO) {
                                    FileOutputStream(tproxyFile, false).use { fos ->
                                        val tproxyConf = getTproxyConf(prefs)
                                        fos.write(tproxyConf.toByteArray())
                                        fos.flush()
                                    }
                                }
                                Log.i(TProxyService.TAG, "TProxy configuration file updated with AI-optimized settings")
                            } catch (e: IOException) {
                                Log.e(TProxyService.TAG, "Error writing TProxy config file: ${e.message}", e)
                                return@launch
                            }
                            
                            // Check again if we're stopping before restarting TProxy
                            if (isStopping) {
                                Log.w(TProxyService.TAG, "Service stopping detected before TProxy restart, aborting")
                                return@launch
                            }
                            
                            // Restart TProxy service to apply new configuration
                            // This is necessary because hev-socks5-tunnel reads config at startup
                            try {
                                Log.i(TProxyService.TAG, "Restarting TProxy service to apply AI-optimized configuration...")
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
                                // Update last restart time after successful restart
                                lastTProxyRestartTime = System.currentTimeMillis()
                                Log.i(TAG, "TProxy service restarted with AI-optimized configuration")
                            } catch (e: Exception) {
                                Log.e(TProxyService.TAG, "Error restarting TProxy service: ${e.message}", e)
                            }
                            
                        } catch (e: Exception) {
                            Log.e(TProxyService.TAG, "Error updating TProxy configuration", e)
                        }
                        }
                    } else {
                        val remainingHours = (TPROXY_RESTART_INTERVAL_MS - timeSinceLastRestart) / (60L * 60L * 1000L)
                        Log.i(TProxyService.TAG, "TProxy restart throttled: Only ${remainingHours}h since last restart (minimum 6h). " +
                                "Skipping restart for now. Config updated but restart deferred.")
                    }
                }
            }
            
            optimizer.startOptimization(
                coreStatsState = coreStatsState,
                optimizationIntervalMs = 30000L // Optimize every 30 seconds
            )
        }

        val successIntent = Intent(TProxyService.ACTION_START)
        successIntent.setPackage(application.packageName)
        sendBroadcast(successIntent)
        @Suppress("SameParameterValue") val channelName = "socks5"
        initNotificationChannel(channelName)
        createNotification(channelName)
    }

    private fun getVpnBuilder(prefs: Preferences, dnsCacheServer: SystemDnsCacheServer?): Builder = Builder().apply {
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
            
            // Use DNS cache server if available (started in startService())
            // Note: DNS cache server is started before getVpnBuilder is called
            val listeningPort = dnsCacheServer?.getListeningPort()
            if (listeningPort != null) {
                if (listeningPort == 53) {
                    addDnsServer("127.0.0.1") // Use local DNS cache server
                } else {
                    // Use custom DNS server but cache will work through Xray-core
                    prefs.dnsIpv4.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
                }
            } else {
                // DNS cache server not available, use custom DNS
                prefs.dnsIpv4.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
            }
        }
        if (prefs.ipv6) {
            addAddress(prefs.tunnelIpv6Address, prefs.tunnelIpv6Prefix)
            addRoute("::", 0)
            // For IPv6, use custom DNS server or localhost if DNS cache server is running
            if (dnsCacheServer?.isRunning() == true) {
                addDnsServer("::1") // IPv6 localhost
            } else {
                prefs.dnsIpv6.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
            }
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
    
    /**
     * Intercept DNS queries from Xray-core logs and cache them
     * This allows browser and other apps to benefit from DNS cache
     * Root is NOT required - works by parsing Xray DNS logs
     */
    private fun interceptDnsFromXrayLogs(logLine: String) {
        if (!dnsCacheInitialized) {
            try {
                DnsCacheManager.initialize(this)
                dnsCacheInitialized = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize DNS cache: ${e.message}")
                return
            }
        }
        
        try {
            // Extract DNS query domain from Xray logs
            val dnsQuery = extractDnsQuery(logLine)
            if (dnsQuery != null) {
                // Check if already in cache
                val cached = DnsCacheManager.getFromCache(dnsQuery)
                if (cached != null && cached.isNotEmpty()) {
                    Log.d(TAG, "✅ DNS CACHE HIT (Xray log): $dnsQuery -> ${cached.map { it.hostAddress }}")
                    return
                }
                
                // DNS cache miss - actively resolve via SystemDnsCacheServer and cache
                // Xray-core patch: Intercept DNS queries and forward to SystemDnsCacheServer
                serviceScope.launch {
                    try {
                        // Forward DNS query to SystemDnsCacheServer for resolution
                        val resolvedAddresses = forwardDnsQueryToSystemCacheServer(dnsQuery)
                        if (resolvedAddresses.isNotEmpty()) {
                            DnsCacheManager.saveToCache(dnsQuery, resolvedAddresses)
                            Log.i(TAG, "✅ DNS resolved via SystemDnsCacheServer (Xray patch): $dnsQuery -> ${resolvedAddresses.map { it.hostAddress }}")
                        } else {
                            Log.d(TAG, "⚠️ DNS CACHE MISS (Xray log): $dnsQuery (SystemDnsCacheServer couldn't resolve)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error forwarding DNS query to SystemDnsCacheServer: $dnsQuery", e)
                    }
                }
            }
            
            // Also extract domain from sniffing logs and cache resolved IP
            val sniffedDomain = extractSniffedDomain(logLine)
            if (sniffedDomain != null) {
                // CRITICAL: Check cache first before resolving
                // This ensures cache hit for repeated domain queries
                val cached = DnsCacheManager.getFromCache(sniffedDomain)
                if (cached != null && cached.isNotEmpty()) {
                    // Cache hit - domain already resolved, no need to resolve again
                    Log.d(TAG, "✅ DNS CACHE HIT (Xray sniffing): $sniffedDomain -> ${cached.map { it.hostAddress }} (served from cache)")
                    return
                }
                
                // Cache miss - resolve via SystemDnsCacheServer with retry mechanism
                // This ensures retry mechanism is used even when Xray sniffing succeeds
                serviceScope.launch {
                    try {
                        // ALWAYS use SystemDnsCacheServer for DNS resolution with retry mechanism
                        // This prevents packet loss during DNS cache miss scenarios
                        // SystemDnsCacheServer has built-in retry and DoH fallback, so no need for InetAddress fallback
                        val addresses = forwardDnsQueryToSystemCacheServer(sniffedDomain)
                        if (addresses.isNotEmpty()) {
                            DnsCacheManager.saveToCache(sniffedDomain, addresses)
                            Log.d(TAG, "💾 DNS cached from Xray sniffing (via SystemDnsCacheServer): $sniffedDomain -> ${addresses.map { it.hostAddress }}")
                        } else {
                            // SystemDnsCacheServer has DoH fallback built-in, so if it returns empty, domain is likely invalid
                            Log.w(TAG, "⚠️ DNS resolution failed for $sniffedDomain (SystemDnsCacheServer with DoH fallback)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error caching DNS from sniffing: $sniffedDomain", e)
                    }
                }
            }
            
            // Extract resolved IP from DNS response logs
            // Pattern: "A record: 1.2.3.4" or "resolved: example.com -> 1.2.3.4"
            val dnsResponsePattern = Regex("""(?:A\s+record|resolved|answer).*?([a-zA-Z0-9][a-zA-Z0-9.-]+\.[a-zA-Z]{2,}).*?(\d+\.\d+\.\d+\.\d+)""", RegexOption.IGNORE_CASE)
            dnsResponsePattern.find(logLine)?.let { matchResult ->
                val domain = matchResult.groupValues[1]
                val ip = matchResult.groupValues[2]
                
                if (domain.isNotEmpty() && ip.isNotEmpty()) {
                    serviceScope.launch {
                        try {
                            // ALWAYS use SystemDnsCacheServer to resolve and cache (ensures consistency)
                            val addresses = forwardDnsQueryToSystemCacheServer(domain)
                            if (addresses.isNotEmpty()) {
                                DnsCacheManager.saveToCache(domain, addresses)
                                Log.d(TAG, "💾 DNS cached from Xray DNS response (via SystemDnsCacheServer): $domain -> ${addresses.map { it.hostAddress }}")
                            } else {
                                // If SystemDnsCacheServer fails, still cache the IP from Xray log (rare case)
                                val address = InetAddress.getByName(ip)
                                DnsCacheManager.saveToCache(domain, listOf(address))
                                Log.d(TAG, "💾 DNS cached from Xray DNS response (direct IP fallback): $domain -> $ip")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error caching DNS response: $domain -> $ip", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Silently fail - DNS cache integration should not break logging
        }
    }

    /**
     * Xray-core patch: Forward DNS query to SystemDnsCacheServer for resolution.
     * This actively intercepts DNS queries from Xray-core and routes them through our cache server.
     * This ensures all DNS queries from Xray-core go through SystemDnsCacheServer, providing system-wide caching.
     * 
     * Optimized with timeout control to prevent packet loss during DNS resolution.
     * DNS resolution is prioritized - connection establishment waits for DNS resolution to complete.
     */
    /**
     * ALWAYS use SystemDnsCacheServer for DNS resolution.
     * SystemDnsCacheServer has built-in retry mechanism and DoH fallback,
     * so it should always be used instead of InetAddress.getAllByName.
     * 
     * If SystemDnsCacheServer is not running, we try to start it first.
     */
    private suspend fun forwardDnsQueryToSystemCacheServer(domain: String): List<InetAddress> {
        return try {
            // Ensure SystemDnsCacheServer is running - try to start if not running
            if (systemDnsCacheServer?.isRunning() != true) {
                Log.d(TAG, "SystemDnsCacheServer not running, attempting to start...")
                if (systemDnsCacheServer == null) {
                    systemDnsCacheServer = SystemDnsCacheServer.getInstance(this@TProxyService)
                }
                systemDnsCacheServer?.start()
                
                // Wait a bit for server to start
                delay(100)
                
                if (systemDnsCacheServer?.isRunning() != true) {
                    Log.w(TAG, "SystemDnsCacheServer failed to start, cannot forward DNS query: $domain")
                    // Even if server failed to start, try resolveDomain - it may work through cache
                    return systemDnsCacheServer?.resolveDomain(domain) ?: emptyList()
                }
            }
            
            // ALWAYS use SystemDnsCacheServer - it has retry mechanism and DoH fallback built-in
            // Maximum wait time: 2 seconds (covers ultra-fast retry mechanism: 300ms + 500ms + 800ms + overhead)
            // Ultra-optimized for maximum DNS resolution speed
            val maxWaitTimeMs = 2000L
            val startTime = System.currentTimeMillis()
            
            val result = withTimeoutOrNull(maxWaitTimeMs) {
                // This call will block until DNS resolution completes (with retry mechanism and DoH fallback)
                // This prevents packet loss by ensuring DNS is resolved before connection attempts
                systemDnsCacheServer?.resolveDomain(domain) ?: emptyList()
            }
            
            val elapsedTime = System.currentTimeMillis() - startTime
            if (result == null) {
                Log.w(TAG, "⚠️ DNS resolution timeout for $domain after ${elapsedTime}ms (max: ${maxWaitTimeMs}ms)")
                // SystemDnsCacheServer has DoH fallback, so if timeout occurs, domain is likely invalid
                return emptyList()
            }
            
            if (result.isNotEmpty()) {
                Log.d(TAG, "✅ DNS resolved for $domain in ${elapsedTime}ms -> ${result.map { it.hostAddress }}")
            } else {
                Log.w(TAG, "⚠️ DNS resolution returned empty result for $domain after ${elapsedTime}ms")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding DNS query to SystemDnsCacheServer: $domain", e)
            // SystemDnsCacheServer has DoH fallback built-in, so if exception occurs, domain is likely invalid
            emptyList()
        }
    }

    private fun stopService() {
        Log.d(TAG, "stopService called, cleaning up VPN resources.")

        // Step 1: Stop system DNS cache server
        systemDnsCacheServer?.stop()
        systemDnsCacheServer?.shutdown()
        
        // Shutdown DNS cache manager (flush cache and cleanup resources)
        DnsCacheManager.shutdown()
        systemDnsCacheServer = null
        
        // Step 2: Stop AI optimizer before stopping service
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
     * UDP error categories for better tracking and analysis
     */
    enum class UdpErrorCategory {
        IDLE_TIMEOUT,      // Xray's 1-minute inactivity timer
        SHUTDOWN,          // During service shutdown
        NORMAL_OPERATION,  // During normal operation
        UNKNOWN            // Unknown context
    }
    
    /**
     * UDP error tracking data class
     */
    data class UdpErrorRecord(
        val timestamp: Long,
        val category: UdpErrorCategory,
        val logEntry: String,
        val context: UdpErrorContext
    )
    
    /**
     * Context information for UDP errors
     */
    data class UdpErrorContext(
        val isShuttingDown: Boolean,
        val serviceUptime: Long,
        val timeSinceLastError: Long,
        val errorCountInWindow: Int
    )
    
    /**
     * UDP error pattern analysis
     */
    data class UdpErrorPattern(
        val totalErrors: Int,
        val errorsByCategory: Map<UdpErrorCategory, Int>,
        val averageTimeBetweenErrors: Double,
        val errorRate: Double, // errors per minute
        val lastErrorTime: Long,
        val isRecovering: Boolean
    )
    
    // Enhanced UDP error tracking
    @Volatile
    private var udpErrorCount = 0
    @Volatile
    private var lastUdpErrorTime = 0L
    private val udpErrorHistory = mutableListOf<UdpErrorRecord>()
    private val maxErrorHistorySize = 100
    @Volatile
    private var serviceStartTime = 0L
    
    /**
     * Detects UDP closed pipe errors from Xray logs with enhanced tracking and categorization.
     * These errors indicate that UDP operations are failing because sockets/pipes are closed.
     * 
     * Common patterns:
     * - "transport/internet/udp: failed to write first UDP payload > io: read/write on closed pipe"
     * - "transport/internet/udp: failed to handle UDP input > io: read/write on closed pipe"
     * 
     * This method categorizes errors, tracks patterns, and provides context-aware analysis.
     */
    private fun detectUdpClosedPipeErrors(logEntry: String) {
        val upperEntry = logEntry.uppercase()
        
        // Check for UDP closed pipe errors
        if (upperEntry.contains("TRANSPORT/INTERNET/UDP") && 
            (upperEntry.contains("FAILED TO WRITE") || upperEntry.contains("FAILED TO HANDLE")) &&
            (upperEntry.contains("CLOSED PIPE") || upperEntry.contains("READ/WRITE ON CLOSED"))) {
            
            val currentTime = System.currentTimeMillis()
            val timeSinceLastError = if (lastUdpErrorTime > 0) currentTime - lastUdpErrorTime else Long.MAX_VALUE
            
            // Determine error category based on context
            val category = categorizeUdpError(timeSinceLastError)
            
            // Create error context
            val context = UdpErrorContext(
                isShuttingDown = isStopping,
                serviceUptime = if (serviceStartTime > 0) currentTime - serviceStartTime else 0L,
                timeSinceLastError = timeSinceLastError,
                errorCountInWindow = udpErrorCount
            )
            
            // Create error record
            val errorRecord = UdpErrorRecord(
                timestamp = currentTime,
                category = category,
                logEntry = logEntry,
                context = context
            )
            
            // Add to history
            synchronized(udpErrorHistory) {
                udpErrorHistory.add(errorRecord)
                if (udpErrorHistory.size > maxErrorHistorySize) {
                    udpErrorHistory.removeAt(0)
                }
            }
            
            // Update counters
            if (timeSinceLastError < 12000) { // Less than 12 seconds between errors
                udpErrorCount++
            } else {
                udpErrorCount = 1
            }
            lastUdpErrorTime = currentTime
            
            // Analyze pattern and log appropriately
            val pattern = analyzeUdpErrorPattern()
            handleUdpErrorWithPattern(errorRecord, pattern)
            
            // Notify telemetry if available
            notifyUdpErrorToTelemetry(errorRecord, pattern)
        }
    }
    
    /**
     * Categorizes UDP error based on context
     */
    private fun categorizeUdpError(timeSinceLastError: Long): UdpErrorCategory {
        return when {
            isStopping -> UdpErrorCategory.SHUTDOWN
            timeSinceLastError > 50000 && timeSinceLastError < 65000 -> UdpErrorCategory.IDLE_TIMEOUT // ~60 seconds = idle timeout
            timeSinceLastError < 12000 -> UdpErrorCategory.NORMAL_OPERATION // Frequent errors
            else -> UdpErrorCategory.UNKNOWN
        }
    }
    
    /**
     * Analyzes UDP error pattern from history
     */
    private fun analyzeUdpErrorPattern(): UdpErrorPattern {
        synchronized(udpErrorHistory) {
            if (udpErrorHistory.isEmpty()) {
                return UdpErrorPattern(
                    totalErrors = 0,
                    errorsByCategory = emptyMap(),
                    averageTimeBetweenErrors = 0.0,
                    errorRate = 0.0,
                    lastErrorTime = 0L,
                    isRecovering = false
                )
            }
            
            val now = System.currentTimeMillis()
            val oneMinuteAgo = now - 60000L
            val recentErrors = udpErrorHistory.filter { it.timestamp > oneMinuteAgo }
            
            // Count errors by category
            val errorsByCategory = recentErrors.groupingBy { it.category }.eachCount()
            
            // Calculate average time between errors
            val timeDeltas = if (recentErrors.size > 1) {
                recentErrors.sortedBy { it.timestamp }.zipWithNext { a, b ->
                    b.timestamp - a.timestamp
                }
            } else {
                emptyList()
            }
            val avgTimeBetween = if (timeDeltas.isNotEmpty()) {
                timeDeltas.average()
            } else {
                0.0
            }
            
            // Calculate error rate (errors per minute)
            val errorRate = if (recentErrors.isNotEmpty()) {
                val oldestError = recentErrors.minByOrNull { it.timestamp }?.timestamp ?: now
                val timeSpan = now - oldestError
                if (timeSpan > 0) {
                    (recentErrors.size * 60000.0) / timeSpan
                } else {
                    recentErrors.size.toDouble()
                }
            } else {
                0.0
            }
            
            // Determine if recovering (no errors in last 30 seconds)
            val thirtySecondsAgo = now - 30000L
            val isRecovering = recentErrors.none { it.timestamp > thirtySecondsAgo }
            
            return UdpErrorPattern(
                totalErrors = recentErrors.size,
                errorsByCategory = errorsByCategory,
                averageTimeBetweenErrors = avgTimeBetween,
                errorRate = errorRate,
                lastErrorTime = lastUdpErrorTime,
                isRecovering = isRecovering
            )
        }
    }
    
    /**
     * Handles UDP error based on pattern analysis
     */
    private fun handleUdpErrorWithPattern(errorRecord: UdpErrorRecord, pattern: UdpErrorPattern) {
        // Log based on severity and frequency
        when {
            // Critical: Very frequent errors during normal operation
            pattern.errorRate > 10 && !isStopping -> {
                Log.e(TProxyService.TAG, "🚨 CRITICAL: Very high UDP error rate (${pattern.errorRate.toInt()}/min) - ${errorRecord.category}")
                Log.e(TProxyService.TAG, "Error details: ${errorRecord.logEntry}")
                Log.e(TProxyService.TAG, "Pattern: ${pattern.errorsByCategory}")
                
                // Send Telegram notification for critical UDP errors
                telegramNotificationManager?.let { manager ->
                    serviceScope.launch {
                        manager.notifyError(
                            "🚨 Critical UDP Error Rate\n\n" +
                            "Error rate: ${pattern.errorRate.toInt()}/min\n" +
                            "Category: ${errorRecord.category}\n" +
                            "Errors: ${pattern.errorsByCategory}\n\n" +
                            "Recent error: ${errorRecord.logEntry.take(200)}"
                        )
                    }
                }
            }
            
            // Warning: Frequent errors
            pattern.errorRate > 5 && !isStopping -> {
                Log.w(TProxyService.TAG, "⚠️ FREQUENT UDP CLOSED PIPE ERRORS (${pattern.errorRate.toInt()}/min) - ${errorRecord.category}")
                Log.w(TProxyService.TAG, "Most recent: ${errorRecord.logEntry}")
                Log.w(TProxyService.TAG, "Pattern: ${pattern.errorsByCategory}")
            }
            
            // Info: Errors during shutdown (expected)
            isStopping -> {
                Log.d(TProxyService.TAG, "UDP error during shutdown (expected): ${errorRecord.category}")
            }
            
            // Debug: Occasional errors (normal race conditions)
            else -> {
                Log.d(TProxyService.TAG, "UDP closed pipe error (normal race condition): ${errorRecord.category}")
            }
        }
        
        // Trigger recovery if needed
        if (pattern.errorRate > 5 && !isStopping && !pattern.isRecovering) {
            serviceScope.launch {
                triggerUdpErrorRecovery(errorRecord, pattern)
            }
        }
    }
    
    /**
     * Notifies telemetry about UDP error
     */
    private fun notifyUdpErrorToTelemetry(errorRecord: UdpErrorRecord, pattern: UdpErrorPattern) {
        // Track UDP errors for telemetry
        // This data will be included in the next telemetry collection cycle
        // The TProxyMetricsCollector will read this data via getUdpErrorPattern()
        
        if (pattern.errorRate > 5) {
            Log.d(TProxyService.TAG, "UDP error telemetry: rate=${pattern.errorRate.toInt()}/min, " +
                    "category=${errorRecord.category}, " +
                    "totalErrors=${pattern.totalErrors}, " +
                    "byCategory=${pattern.errorsByCategory}")
        }
    }
    
    /**
     * Get UDP error pattern for telemetry collection
     */
    fun getUdpErrorPatternForTelemetry(): UdpErrorPattern {
        return analyzeUdpErrorPattern()
    }
    
    /**
     * Triggers UDP error recovery mechanism with exponential backoff
     */
    private suspend fun triggerUdpErrorRecovery(errorRecord: UdpErrorRecord, pattern: UdpErrorPattern) {
        val now = System.currentTimeMillis()
        
        // Check cooldown period
        if (lastUdpRecoveryTime > 0 && (now - lastUdpRecoveryTime) < RECOVERY_COOLDOWN_MS) {
            val remainingCooldown = RECOVERY_COOLDOWN_MS - (now - lastUdpRecoveryTime)
            Log.d(TProxyService.TAG, "UDP recovery cooldown active, ${remainingCooldown / 1000}s remaining")
            return
        }
        
        // Check if we've exceeded max recovery attempts
        if (udpRecoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            Log.w(TProxyService.TAG, "⚠️ UDP recovery: Max attempts ($MAX_RECOVERY_ATTEMPTS) reached, " +
                    "considering critical recovery (Xray restart)")
            
            // Only restart Xray as last resort for very critical situations
            if (pattern.errorRate > 20 && !isStopping) {
                serviceScope.launch {
                    attemptCriticalUdpRecovery(pattern)
                }
            }
            return
        }
        
        // Calculate exponential backoff delay
        val backoffDelay = calculateRecoveryBackoff(udpRecoveryAttempts)
        
        Log.d(TProxyService.TAG, "Triggering UDP error recovery (attempt ${udpRecoveryAttempts + 1}/$MAX_RECOVERY_ATTEMPTS): " +
                "rate=${pattern.errorRate.toInt()}/min, category=${errorRecord.category}, " +
                "backoff=${backoffDelay / 1000}s")
        
        // Wait for backoff delay
        delay(backoffDelay)
        
        // Check if still active and not stopping
        if (isStopping) {
            return
        }
        
        // Attempt recovery
        try {
            val recoverySuccess = attemptUdpErrorRecovery(errorRecord, pattern)
            
            if (recoverySuccess) {
                // Reset recovery attempts on success
                udpRecoveryAttempts = 0
                Log.i(TProxyService.TAG, "✅ UDP recovery successful")
            } else {
                // Increment recovery attempts on failure
                udpRecoveryAttempts++
                lastUdpRecoveryTime = System.currentTimeMillis()
                Log.w(TProxyService.TAG, "⚠️ UDP recovery failed (attempt $udpRecoveryAttempts/$MAX_RECOVERY_ATTEMPTS)")
            }
        } catch (e: Exception) {
            udpRecoveryAttempts++
            lastUdpRecoveryTime = System.currentTimeMillis()
            Log.e(TProxyService.TAG, "Error during UDP recovery: ${e.message}", e)
        }
    }
    
    /**
     * Calculate exponential backoff delay for recovery attempts
     */
    private fun calculateRecoveryBackoff(attempt: Int): Long {
        // Exponential backoff: 2^attempt seconds, max 30 seconds
        val backoffSeconds = (1 shl attempt).coerceAtMost(30)
        return backoffSeconds * 1000L
    }
    
    /**
     * Attempt UDP error recovery
     * Returns true if recovery was successful, false otherwise
     */
    private suspend fun attemptUdpErrorRecovery(
        errorRecord: UdpErrorRecord,
        pattern: UdpErrorPattern
    ): Boolean {
        Log.d(TProxyService.TAG, "Attempting UDP error recovery: category=${errorRecord.category}")
        
        try {
            // Step 1: Notify native tunnel to pause UDP packet sending temporarily
            // This helps reduce race conditions during recovery
            notifyNativeTunnelOfUdpError()
            
            // Step 2: Wait briefly to let current UDP operations complete
            delay(500)
            
            // Step 3: Check if Xray process is still healthy
            val process = xrayProcess
            if (process == null || !process.isAlive) {
                Log.w(TProxyService.TAG, "UDP recovery: Xray process is not alive, recovery not applicable")
                return false
            }
            
            // Step 4: Clear UDP error history to reset tracking
            // This allows fresh start for error pattern analysis
            synchronized(udpErrorHistory) {
                // Keep only recent errors (last 10) to maintain some context
                if (udpErrorHistory.size > 10) {
                    udpErrorHistory.subList(0, udpErrorHistory.size - 10).clear()
                }
            }
            
            // Step 5: Reset error counters
            udpErrorCount = 0
            lastUdpErrorTime = 0L
            
            // Step 6: Notify native tunnel that recovery is complete
            notifyNativeTunnelOfRecoveryComplete()
            
            Log.i(TProxyService.TAG, "✅ UDP recovery steps completed successfully")
            return true
            
        } catch (e: Exception) {
            Log.e(TProxyService.TAG, "Error during UDP recovery attempt: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Attempt critical UDP recovery (Xray restart as last resort)
     */
    private suspend fun attemptCriticalUdpRecovery(pattern: UdpErrorPattern) {
        Log.e(TProxyService.TAG, "🚨 CRITICAL UDP recovery: Error rate ${pattern.errorRate.toInt()}/min, " +
                "attempting Xray restart as last resort")
        
        // Only restart if error rate is extremely high and not during shutdown
        if (pattern.errorRate > 20 && !isStopping) {
            try {
                // Reset recovery attempts counter
                udpRecoveryAttempts = 0
                lastUdpRecoveryTime = 0L
                
                // Stop current Xray instance gracefully
                stopXray()
                
                // Wait for cleanup
                delay(3000)
                
                // Restart Xray
                startXray()
                
                Log.i(TProxyService.TAG, "✅ Critical UDP recovery: Xray restarted")
                
                // Send Telegram notification for critical UDP recovery
                telegramNotificationManager?.let { manager ->
                    serviceScope.launch {
                        manager.notifyError(
                            "🚨 Critical UDP Recovery\n\n" +
                            "Xray restarted due to critical UDP errors.\n" +
                            "Error rate: ${pattern.errorRate.toInt()}/min\n" +
                            "Recovery action: Xray process restarted"
                        )
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TProxyService.TAG, "Error during critical UDP recovery (Xray restart): ${e.message}", e)
                
                // Send Telegram notification for recovery failure
                telegramNotificationManager?.let { manager ->
                    serviceScope.launch {
                        manager.notifyError(
                            "🚨 UDP Recovery Failed\n\n" +
                            "Failed to recover from critical UDP errors.\n" +
                            "Error: ${e.message}\n" +
                            "Error rate: ${pattern.errorRate.toInt()}/min"
                        )
                    }
                }
            }
        } else {
            Log.w(TProxyService.TAG, "Critical UDP recovery skipped: errorRate=${pattern.errorRate.toInt()}, " +
                    "isStopping=$isStopping")
        }
    }
    
    /**
     * Notify native tunnel of UDP error (for coordination)
     */
    private fun notifyNativeTunnelOfUdpError() {
        try {
            val result = TProxyNotifyUdpError(0) // 0 = closed pipe error
            if (result) {
                Log.d(TProxyService.TAG, "Notified native tunnel of UDP error")
            } else {
                Log.w(TProxyService.TAG, "Failed to notify native tunnel of UDP error")
            }
        } catch (e: UnsatisfiedLinkError) {
            // Native function not available (hev-socks5-tunnel may not support this yet)
            Log.d(TProxyService.TAG, "UDP error notification not available in native tunnel: ${e.message}")
        } catch (e: Exception) {
            Log.e(TProxyService.TAG, "Error notifying native tunnel of UDP error: ${e.message}", e)
        }
    }
    
    /**
     * Notify native tunnel that recovery is complete
     */
    private fun notifyNativeTunnelOfRecoveryComplete() {
        try {
            val result = TProxyNotifyUdpRecoveryComplete()
            if (result) {
                Log.d(TProxyService.TAG, "Notified native tunnel of UDP recovery completion")
            } else {
                Log.w(TProxyService.TAG, "Failed to notify native tunnel of UDP recovery completion")
            }
        } catch (e: UnsatisfiedLinkError) {
            // Native function not available (hev-socks5-tunnel may not support this yet)
            Log.d(TProxyService.TAG, "UDP recovery notification not available in native tunnel: ${e.message}")
        } catch (e: Exception) {
            Log.e(TProxyService.TAG, "Error notifying native tunnel of UDP recovery: ${e.message}", e)
        }
    }
    
    /**
     * Gets current UDP error pattern for external monitoring
     */
    fun getUdpErrorPattern(): UdpErrorPattern {
        return analyzeUdpErrorPattern()
    }
    
    /**
     * UDP statistics from native tunnel
     */
    data class UdpStats(
        val txPackets: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val rxBytes: Long,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * UDP connection health status
     */
    data class UdpConnectionHealth(
        val isHealthy: Boolean,
        val packetRate: Double, // packets per second
        val byteRate: Double,   // bytes per second
        val timeSinceLastActivity: Long,
        val isIdle: Boolean,
        val recommendation: String
    )
    
    /**
     * Start proactive UDP connection monitoring.
     * Monitors UDP stats from native tunnel and detects idle connections.
     */
    private fun startUdpMonitoring() {
        // Cancel existing monitoring job if any
        udpMonitoringJob?.cancel()
        
        udpMonitoringJob = serviceScope.launch {
            while (isActive && !isStopping) {
                try {
                    delay(15000L) // Check every 15 seconds
                    ensureActive()
                    
                    // Get current UDP stats from native tunnel
                    val currentStats = getNativeUdpStats()
                    if (currentStats == null) {
                        Log.d(TAG, "UDP monitoring: Native stats unavailable")
                        continue
                    }
                    
                    // Analyze UDP connection health
                    val health = analyzeUdpConnectionHealth(currentStats)
                    
                    // Log health status if unhealthy or idle
                    if (!health.isHealthy || health.isIdle) {
                        Log.d(TAG, "UDP health: healthy=${health.isHealthy}, idle=${health.isIdle}, " +
                                "packetRate=${health.packetRate.toInt()}/s, " +
                                "recommendation=${health.recommendation}")
                    }
                    
                    // Take proactive actions if needed
                    if (health.isIdle && health.timeSinceLastActivity > 50000L) {
                        // UDP connection idle for >50 seconds - near Xray's 1-minute timeout
                        // Proactively prepare for cleanup to reduce race conditions
                        handleIdleUdpConnection(health)
                    }
                    
                    // Check for potential issues
                    if (!health.isHealthy && health.packetRate > 0) {
                        // Packets are being sent but health is poor - potential issue
                        Log.w(TAG, "⚠️ UDP connection health degraded: ${health.recommendation}")
                    }
                    
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in UDP monitoring: ${e.message}", e)
                    }
                }
            }
        }
        
        Log.d(TAG, "Started proactive UDP connection monitoring (every 15 seconds)")
    }
    
    /**
     * Get UDP statistics from native tunnel
     */
    private fun getNativeUdpStats(): UdpStats? {
        return try {
            val statsArray = TProxyGetStats()
            if (statsArray != null && statsArray.size >= 4) {
                UdpStats(
                    txPackets = statsArray[0],
                    txBytes = statsArray[1],
                    rxPackets = statsArray[2],
                    rxBytes = statsArray[3]
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get native UDP stats: ${e.message}")
            null
        }
    }
    
    /**
     * Analyze UDP connection health from statistics
     */
    private fun analyzeUdpConnectionHealth(currentStats: UdpStats): UdpConnectionHealth {
        val now = System.currentTimeMillis()
        
        // Calculate rates if we have previous stats
        val packetRate: Double
        val byteRate: Double
        val timeSinceLastActivity: Long
        
        if (lastUdpStats != null && lastUdpStatsTime > 0) {
            val timeDelta = (now - lastUdpStatsTime) / 1000.0 // seconds
            
            if (timeDelta > 0) {
                val packetDelta = (currentStats.txPackets + currentStats.rxPackets) - 
                                 (lastUdpStats!!.txPackets + lastUdpStats!!.rxPackets)
                val byteDelta = (currentStats.txBytes + currentStats.rxBytes) - 
                               (lastUdpStats!!.txBytes + lastUdpStats!!.rxBytes)
                
                packetRate = packetDelta / timeDelta
                byteRate = byteDelta / timeDelta
                
                // Calculate time since last activity (if no change in stats)
                timeSinceLastActivity = if (packetDelta == 0L && byteDelta == 0L) {
                    now - lastUdpStatsTime
                } else {
                    0L // Active
                }
            } else {
                packetRate = 0.0
                byteRate = 0.0
                timeSinceLastActivity = 0L
            }
        } else {
            // First measurement
            packetRate = 0.0
            byteRate = 0.0
            timeSinceLastActivity = 0L
        }
        
        // Update last stats
        lastUdpStats = currentStats
        lastUdpStatsTime = now
        
        // Determine if connection is idle (no activity for >40 seconds)
        val isIdle = timeSinceLastActivity > 40000L
        
        // Determine health
        val isHealthy = when {
            isIdle && timeSinceLastActivity > 55000L -> false // Near timeout
            packetRate < 0 -> false // Negative rate indicates issues
            else -> true
        }
        
        // Generate recommendation
        val recommendation = when {
            isIdle && timeSinceLastActivity > 55000L -> "Connection idle for ${timeSinceLastActivity / 1000}s, cleanup imminent"
            isIdle -> "Connection idle for ${timeSinceLastActivity / 1000}s, monitor for cleanup"
            packetRate > 0 && byteRate > 0 -> "Active: ${packetRate.toInt()} pkt/s, ${(byteRate / 1024).toInt()} KB/s"
            else -> "Connection established, waiting for activity"
        }
        
        return UdpConnectionHealth(
            isHealthy = isHealthy,
            packetRate = packetRate,
            byteRate = byteRate,
            timeSinceLastActivity = timeSinceLastActivity,
            isIdle = isIdle,
            recommendation = recommendation
        )
    }
    
    /**
     * Handle idle UDP connection proactively
     */
    private suspend fun handleIdleUdpConnection(health: UdpConnectionHealth) {
        // Proactively prepare for UDP dispatcher cleanup
        // This helps reduce race conditions when Xray closes idle connections
        
        if (health.timeSinceLastActivity > 55000L && health.timeSinceLastActivity < 65000L) {
            // Connection is about to timeout (Xray's 1-minute timer)
            // Log for monitoring but don't take action yet - Xray will handle cleanup
            Log.d(TAG, "UDP connection near timeout (${health.timeSinceLastActivity / 1000}s idle), " +
                    "preparing for potential cleanup race condition")
            
            // Notify native tunnel to be ready for potential cleanup
            // This can help reduce race conditions
            notifyNativeTunnelOfImminentCleanup()
        }
    }
    
    /**
     * Notify native tunnel of imminent UDP cleanup
     * This helps native tunnel prepare and reduce race conditions
     */
    private fun notifyNativeTunnelOfImminentCleanup() {
        try {
            val result = TProxyNotifyImminentUdpCleanup()
            if (result) {
                Log.d(TAG, "Notified native tunnel of imminent UDP cleanup")
            } else {
                Log.w(TAG, "Failed to notify native tunnel of imminent UDP cleanup")
            }
        } catch (e: UnsatisfiedLinkError) {
            // Native function not available (hev-socks5-tunnel may not support this yet)
            Log.d(TAG, "UDP cleanup notification not available in native tunnel: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying native tunnel of imminent UDP cleanup: ${e.message}", e)
        }
    }
    
    /**
     * Stop UDP monitoring
     */
    private fun stopUdpMonitoring() {
        udpMonitoringJob?.cancel()
        udpMonitoringJob = null
        lastUdpStats = null
        lastUdpStatsTime = 0L
        Log.d(TAG, "Stopped UDP monitoring")
    }
    
    /**
     * Detect connection reset errors in Xray-core logs.
     * These errors indicate Xray-core is having trouble connecting to SOCKS5 tunnel.
     * 
     * Error pattern: "read tcp 127.0.0.1:10808->127.0.0.1:XXXXX: read: connection reset by peer"
     * or "failed to transfer request payload" with "connection reset"
     */
    private fun detectConnectionResetErrors(logEntry: String) {
        val upperEntry = logEntry.uppercase()
        
        // Check for connection reset errors
        val isConnectionReset = (upperEntry.contains("CONNECTION RESET") || 
                               upperEntry.contains("RESET BY PEER") ||
                               (upperEntry.contains("FAILED TO TRANSFER") && upperEntry.contains("REQUEST PAYLOAD"))) &&
                               (upperEntry.contains("OUTBOUND") || upperEntry.contains("PROXY/VLESS") || 
                                upperEntry.contains("PROXY/VMESS") || upperEntry.contains("PROXY/TROJAN"))
        
        if (isConnectionReset) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastError = if (lastConnectionResetTime > 0) {
                currentTime - lastConnectionResetTime
            } else {
                CONNECTION_RESET_WINDOW_MS + 1 // First error, always count
            }
            
            // Reset counter if enough time has passed (errors are not frequent)
            if (timeSinceLastError > CONNECTION_RESET_WINDOW_MS) {
                connectionResetErrorCount = 1
                lastConnectionResetTime = currentTime
                Log.w(TProxyService.TAG, "⚠️ Connection reset detected: $logEntry")
            } else {
                connectionResetErrorCount++
                lastConnectionResetTime = currentTime
                
                // Log warning if threshold exceeded
                if (connectionResetErrorCount >= CONNECTION_RESET_THRESHOLD) {
                    Log.e(TProxyService.TAG, "❌ FREQUENT CONNECTION RESET ERRORS (count: $connectionResetErrorCount in last minute)")
                    Log.e(TProxyService.TAG, "Xray-core is having trouble connecting to SOCKS5 tunnel")
                    Log.e(TProxyService.TAG, "Most recent error: $logEntry")
                    
                    // Broadcast error to UI for user notification
                    val errorIntent = Intent(TProxyService.ACTION_ERROR)
                    errorIntent.setPackage(application.packageName)
                    errorIntent.putExtra(
                        TProxyService.EXTRA_ERROR_MESSAGE,
                        "Connection reset errors detected ($connectionResetErrorCount in last minute). " +
                        "Xray-core is having trouble connecting to SOCKS5 tunnel. " +
                        "Attempting automatic recovery..."
                    )
                    errorIntent.putExtra("error_type", "connection_reset")
                    errorIntent.putExtra("error_count", connectionResetErrorCount)
                    sendBroadcast(errorIntent)
                    
                    // Trigger recovery mechanism
                    serviceScope.launch {
                        handleConnectionResetRecovery()
                    }
                } else {
                    Log.w(TProxyService.TAG, "⚠️ Connection reset error (count: $connectionResetErrorCount/$CONNECTION_RESET_THRESHOLD): $logEntry")
                }
            }
        }
    }
    
    /**
     * Handle connection reset recovery by checking SOCKS5 readiness with exponential backoff retry.
     * Uses exponential backoff to avoid overwhelming the system with retry attempts.
     */
    private suspend fun handleConnectionResetRecovery() {
        try {
            Log.i(TAG, "Attempting connection reset recovery with exponential backoff...")
            val prefs = Preferences(applicationContext)
            
            var retryAttempt = 0
            val maxRetries = 3
            var baseDelayMs = 1000L // Start with 1 second
            
            while (retryAttempt < maxRetries && serviceScope.isActive && !isStopping) {
                serviceScope.ensureActive()
                
                // Check if SOCKS5 is ready
                val isReady = Socks5ReadinessChecker.isSocks5Ready(
                    context = applicationContext,
                    address = prefs.socksAddress,
                    port = prefs.socksPort
                )
                
                if (isReady) {
                    Log.i(TAG, "✅ SOCKS5 recovered after $retryAttempt retry attempts - connection reset issues should be resolved")
                    // Reset error counter on successful recovery
                    connectionResetErrorCount = 0
                    lastConnectionResetTime = 0L
                    return
                }
                
                // SOCKS5 not ready, wait with exponential backoff before retry
                if (retryAttempt < maxRetries - 1) {
                    val delayMs = baseDelayMs * (1 shl retryAttempt) // Exponential: 1s, 2s, 4s
                    Log.d(TAG, "SOCKS5 not ready, waiting ${delayMs}ms before retry ${retryAttempt + 1}/$maxRetries")
                    delay(delayMs)
                }
                
                retryAttempt++
            }
            
            // If we get here, all retries failed
            if (retryAttempt >= maxRetries) {
                Log.e(TAG, "❌ SOCKS5 did not recover after $maxRetries retry attempts - connection reset issues may persist")
                
                // Attempt one final wait with longer timeout
                Log.i(TAG, "Attempting final recovery with extended timeout...")
                val waitResult = Socks5ReadinessChecker.waitUntilSocksReady(
                    context = applicationContext,
                    address = prefs.socksAddress,
                    port = prefs.socksPort,
                    maxWaitTimeMs = 10000L, // 10 seconds final attempt
                    retryIntervalMs = 1000L
                )
                
                if (waitResult) {
                    Log.i(TAG, "✅ SOCKS5 recovered in final attempt - connection reset issues resolved")
                    connectionResetErrorCount = 0
                    lastConnectionResetTime = 0L
                    
                    // Broadcast recovery success to UI
                    val recoveryIntent = Intent(TProxyService.ACTION_ERROR)
                    recoveryIntent.setPackage(application.packageName)
                    recoveryIntent.putExtra(
                        TProxyService.EXTRA_ERROR_MESSAGE,
                        "Connection reset issues resolved. SOCKS5 tunnel is now ready."
                    )
                    recoveryIntent.putExtra("error_type", "connection_reset_recovered")
                    sendBroadcast(recoveryIntent)
                } else {
                    Log.e(TAG, "❌ SOCKS5 recovery failed - connection reset issues persist")
                    // Don't reset counter here - let it accumulate for monitoring
                    
                    // Broadcast persistent error to UI
                    val persistentErrorIntent = Intent(TProxyService.ACTION_ERROR)
                    persistentErrorIntent.setPackage(application.packageName)
                    persistentErrorIntent.putExtra(
                        TProxyService.EXTRA_ERROR_MESSAGE,
                        "Connection reset issues persist. SOCKS5 tunnel may need manual intervention. " +
                        "Consider restarting the VPN service."
                    )
                    persistentErrorIntent.putExtra("error_type", "connection_reset_persistent")
                    sendBroadcast(persistentErrorIntent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during connection reset recovery: ${e.message}", e)
        }
    }
    
    /**
     * Update core stats state for AI optimizer.
     * Called from MainViewModel when stats are updated.
     */
    /**
     * Calculates adaptive polling interval based on traffic state.
     * No traffic: 60 seconds
     * Low traffic: 30 seconds
     * High traffic: 10-15 seconds
     */
    private fun calculateAdaptivePollingInterval(stats: CoreStatsState?): Long {
        if (stats == null) {
            return 60000L // No stats available, use conservative interval
        }
        
        val totalThroughput = stats.uplinkThroughput + stats.downlinkThroughput
        val highTrafficThreshold = 100_000.0 // 100 KB/s
        val lowTrafficThreshold = 10_000.0 // 10 KB/s
        
        return when {
            totalThroughput > highTrafficThreshold -> 10000L // 10 seconds for high traffic
            totalThroughput > lowTrafficThreshold -> 30000L // 30 seconds for low traffic
            else -> 60000L // 60 seconds for no/low traffic
        }
    }
    
    fun updateCoreStatsState(stats: CoreStatsState) {
        coreStatsState = stats
        // Notify AI optimizer if it's running
        // The optimizer will pick up the new stats in its next cycle
        
        // Send periodic performance metrics notification (every 5 minutes)
        // Only send if there's significant traffic
        val totalBytes = stats.uplink + stats.downlink
        val lastPerformanceNotificationTime = getLastPerformanceNotificationTime()
        val now = System.currentTimeMillis()
        val PERFORMANCE_NOTIFICATION_INTERVAL_MS = 5L * 60L * 1000L // 5 minutes
        
        if (totalBytes > 10 * 1024 * 1024 && // At least 10 MB transferred
            (lastPerformanceNotificationTime == 0L || 
             (now - lastPerformanceNotificationTime) >= PERFORMANCE_NOTIFICATION_INTERVAL_MS)) {
            
            telegramNotificationManager?.let { manager ->
                serviceScope.launch {
                    manager.notifyPerformanceMetrics(stats)
                    setLastPerformanceNotificationTime(now)
                }
            }
        }
    }
    
    // Track last performance notification time
    private var lastPerformanceNotificationTime: Long = 0L
    
    private fun getLastPerformanceNotificationTime(): Long = lastPerformanceNotificationTime
    private fun setLastPerformanceNotificationTime(time: Long) {
        lastPerformanceNotificationTime = time
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
                            Log.d(TProxyService.TAG, "Client recreation cooldown active, ${remainingCooldown}ms remaining")
                            // Fall through to default values
                        } else {
                            // CoreStatsClient.create() now safely returns null on any error (never throws)
                            coreStatsClient = CoreStatsClient.create("127.0.0.1", apiPort)
                            if (coreStatsClient == null) {
                                Log.w(TProxyService.TAG, "Failed to create CoreStatsClient for metrics (port: $apiPort)")
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
                            
                            Log.d(TProxyService.TAG, "Using CoreStatsClient metrics: latency=${latencyMs}ms, throughput=${throughputKbps}kbps, success=$success")
                            return Triple(latencyMs, throughputKbps, success)
                        } else {
                            // Stats query succeeded but no traffic data - don't close immediately
                            // This prevents rapid recreation loops
                            Log.d(TProxyService.TAG, "CoreStatsClient query returned no traffic data, will retry later")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TProxyService.TAG, "Error getting metrics from CoreStatsClient: ${e.message}", e)
                    // Close client on error but enforce cooldown before recreation
                    try {
                        coreStatsClient?.close()
                    } catch (closeEx: Exception) {
                        Log.w(TProxyService.TAG, "Error closing CoreStatsClient: ${closeEx.message}")
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
            Regex("""alpn\s*[=:]\s*([^\s,\]]+)""", RegexOption.IGNORE_CASE),
            Regex("""ALPN\s*[=:]\s*([^\s,\]]+)""", RegexOption.IGNORE_CASE),
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
                maxWaitTimeMs = 5000L, // Wait up to 5 seconds (optimized from 15s)
                retryIntervalMs = 500L // Check every 500ms
            )
            
            if (isReady) {
                socks5ReadinessChecked = true
                Socks5ReadinessChecker.setSocks5Ready(true)
                
                // Broadcast SOCKS5 readiness to other components
                val readyIntent = Intent(TProxyService.ACTION_SOCKS5_READY)
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
     * Start periodic SOCKS5 health check to ensure it remains ready.
     * This helps detect and recover from connection issues early.
     */
    private fun startPeriodicSocks5HealthCheck(prefs: Preferences) {
        // Cancel existing periodic check if any
        socks5PeriodicCheckJob?.cancel()
        
        socks5PeriodicCheckJob = serviceScope.launch {
            while (isActive && !isStopping) {
                try {
                    delay(30000L) // Check every 30 seconds
                    ensureActive()
                    
                    val socksPort = prefs.socksPort
                    val socksAddress = prefs.socksAddress
                    
                    val isReady = Socks5ReadinessChecker.isSocks5Ready(
                        context = applicationContext,
                        address = socksAddress,
                        port = socksPort
                    )
                    
                    if (!isReady && socks5ReadinessChecked) {
                        Log.w(TAG, "⚠️ SOCKS5 became unavailable during periodic check - attempting recovery")
                        Socks5ReadinessChecker.setSocks5Ready(false)
                        socks5ReadinessChecked = false
                        
                        // Attempt to re-check readiness
                        checkSocks5Readiness(prefs)
                    } else if (isReady && !socks5ReadinessChecked) {
                        Log.i(TAG, "✅ SOCKS5 recovered - updating readiness state")
                        socks5ReadinessChecked = true
                        Socks5ReadinessChecker.setSocks5Ready(true)
                        
                        // Broadcast recovery
                        val readyIntent = Intent(TProxyService.ACTION_SOCKS5_READY)
                        readyIntent.setPackage(application.packageName)
                        readyIntent.putExtra("socks_address", socksAddress)
                        readyIntent.putExtra("socks_port", socksPort)
                        sendBroadcast(readyIntent)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in periodic SOCKS5 health check: ${e.message}", e)
                    }
                }
            }
        }
        
        Log.d(TAG, "Started periodic SOCKS5 health check (every 30 seconds)")
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
                Log.d(TProxyService.TAG, "Routing decision: optimized for $sni")
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
        
        Log.d(TProxyService.TAG, "Foreground notification created and displayed")
    }

    private fun exit() {
        val stopIntent = Intent(TProxyService.ACTION_STOP)
        stopIntent.setPackage(application.packageName)
        sendBroadcast(stopIntent)
        // Send Telegram notification
        telegramNotificationManager?.let { manager ->
            serviceScope.launch {
                manager.notifyVpnStatus(false)
            }
        }
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
        const val ACTION_INSTANCE_STATUS_UPDATE: String = "com.hyperxray.an.INSTANCE_STATUS_UPDATE"
        const val EXTRA_LOG_DATA: String = "log_data"
        const val EXTRA_ERROR_MESSAGE: String = "error_message"
        const val EXTRA_INSTANCE_STATUS: String = "instance_status"
        const val TAG = "VpnService"
        // Ultra-optimized broadcast settings for maximum performance
        // Larger batches = fewer broadcasts = better performance
        const val BROADCAST_DELAY_MS: Long = 1000 // 1 second delay for larger batches
        const val BROADCAST_BUFFER_SIZE_THRESHOLD: Int = 100 // Larger batches (100 entries)

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

        fun getNativeLibraryDir(context: Context?): String? {
            if (context == null) {
                Log.e(TAG, "Context is null")
                return null
            }
            try {
                val applicationInfo = context.applicationInfo
                if (applicationInfo != null) {
                    val nativeLibraryDir = applicationInfo.nativeLibraryDir
                    Log.d(TProxyService.TAG, "Native Library Directory: $nativeLibraryDir")
                    return nativeLibraryDir
                } else {
                    Log.e(TProxyService.TAG, "ApplicationInfo is null")
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
    }
}