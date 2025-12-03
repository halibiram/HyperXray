package com.hyperxray.an.vpn.lifecycle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.hyperxray.an.R
import com.hyperxray.an.activity.MainActivity
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.prefs.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant

/**
 * 🚀 HyperVpnService Adapter (2030 Architecture)
 * 
 * Bridges the new declarative lifecycle system with Android VpnService.
 * This adapter:
 * - Implements Android VpnService lifecycle
 * - Delegates to VpnLifecycleOrchestrator for state management
 * - Provides backward-compatible API
 * - Handles foreground service requirements
 */
class HyperVpnServiceAdapter : VpnService() {
    
    companion object {
        private const val TAG = "HyperVpnServiceAdapter"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "hyper_vpn_channel"
        
        // Actions
        const val ACTION_START = "com.hyperxray.an.HYPER_VPN_START"
        const val ACTION_STOP = "com.hyperxray.an.HYPER_VPN_STOP"
        const val ACTION_CONNECT = "com.hyperxray.an.CONNECT"
        const val ACTION_DISCONNECT = "com.hyperxray.an.DISCONNECT"
        
        // Extras
        const val EXTRA_CONFIG_ID = "config_id"
        
        // Native library status
        private var nativeLibraryLoaded = false
        private var nativeLibraryError: String? = null
        
        init {
            loadNativeLibraries()
        }
        
        private fun loadNativeLibraries() {
            try {
                System.loadLibrary("hyperxray-jni")
                System.loadLibrary("hyperxray")
                nativeLibraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                nativeLibraryError = e.message
            }
        }
        
        fun isNativeLibraryAvailable(): Boolean = nativeLibraryLoaded
    }
    
    // Service scope
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Lifecycle orchestrator
    private lateinit var orchestrator: VpnLifecycleOrchestrator
    
    // Native bridge implementation
    private lateinit var nativeBridge: NativeTunnelBridgeImpl
    
    // Config provider implementation
    private lateinit var configProvider: ConfigProviderImpl
    
    // Preferences
    private lateinit var prefs: Preferences
    
    // Wake lock
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Binder for local binding
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): HyperVpnServiceAdapter = this@HyperVpnServiceAdapter
        fun getOrchestrator(): VpnLifecycleOrchestrator = orchestrator
    }
    
    // ===== Public API =====
    
    /**
     * Get current VPN state as Flow
     */
    val state: StateFlow<VpnLifecycleState>
        get() = orchestrator.state
    
    /**
     * Get telemetry events
     */
    val telemetry: SharedFlow<TelemetryEvent>
        get() = orchestrator.telemetry
    
    /**
     * Check if VPN is connected
     */
    val isConnected: Boolean
        get() = orchestrator.state.value is VpnLifecycleState.Connected
    
    /**
     * Get connection stats
     */
    fun getStats(): ConnectionStats? = orchestrator.getConnectionStats()
    
    /**
     * Get health status
     */
    fun getHealth(): HealthStatus? = orchestrator.getHealthStatus()
    
    // ===== Lifecycle Methods =====
    
    override fun onCreate() {
        super.onCreate()
        AiLogHelper.i(TAG, "🚀 HyperVpnServiceAdapter onCreate - Next-Gen Lifecycle")
        
        // Initialize components
        prefs = Preferences(this)
        nativeBridge = NativeTunnelBridgeImpl(this)
        configProvider = ConfigProviderImpl(this, prefs)
        
        // Create orchestrator
        orchestrator = VpnLifecycleOrchestrator(
            context = this,
            scope = serviceScope,
            nativeBridge = nativeBridge,
            configProvider = configProvider
        )
        
        // Create notification channel
        createNotificationChannel()
        
        // Acquire wake lock
        acquireWakeLock()
        
        // Observe state changes for notifications
        observeStateChanges()
        
        AiLogHelper.i(TAG, "✅ Service initialized with declarative lifecycle")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AiLogHelper.d(TAG, "onStartCommand: action=${intent?.action}")
        
        // Start foreground immediately
        startForegroundWithNotification("Initializing...")
        
        when (intent?.action) {
            ACTION_START, ACTION_CONNECT -> {
                val configId = intent.getStringExtra(EXTRA_CONFIG_ID)
                serviceScope.launch {
                    orchestrator.start(configId)
                }
            }
            ACTION_STOP, ACTION_DISCONNECT -> {
                serviceScope.launch {
                    orchestrator.stop()
                }
            }
            else -> {
                // Default: start VPN
                serviceScope.launch {
                    orchestrator.start()
                }
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onRevoke() {
        AiLogHelper.w(TAG, "VPN permission revoked")
        serviceScope.launch {
            orchestrator.stop(DisconnectReason.PermissionRevoked)
        }
        super.onRevoke()
    }
    
    override fun onDestroy() {
        AiLogHelper.d(TAG, "onDestroy")
        
        // Stop orchestrator
        serviceScope.launch {
            orchestrator.stop(DisconnectReason.SystemShutdown)
        }
        
        // Release wake lock
        releaseWakeLock()
        
        // Cancel scope
        serviceScope.cancel()
        
        super.onDestroy()
    }
    
    // ===== Private Methods =====
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "HyperXray VPN",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VPN connection status"
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun startForegroundWithNotification(status: String) {
        val notification = createNotification(status)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    
    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("HyperXray VPN")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
    
    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HyperXray::VpnService"
            ).apply {
                acquire(10 * 60 * 60 * 1000L) // 10 hours
            }
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }
    
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Error releasing wake lock: ${e.message}")
        }
    }
    
    private fun observeStateChanges() {
        serviceScope.launch {
            orchestrator.state.collect { state ->
                handleStateChange(state)
            }
        }
    }
    
    private fun handleStateChange(state: VpnLifecycleState) {
        val (status, shouldStop) = when (state) {
            is VpnLifecycleState.Idle -> "Disconnected" to true
            is VpnLifecycleState.Preparing -> "Preparing... ${(state.progress * 100).toInt()}%" to false
            is VpnLifecycleState.Connecting -> "Connecting... ${(state.progress * 100).toInt()}%" to false
            is VpnLifecycleState.Connected -> {
                val uptime = java.time.Duration.between(state.connectionInfo.connectedAt, Instant.now())
                "Connected (${formatDuration(uptime)})" to false
            }
            is VpnLifecycleState.Reconnecting -> "Reconnecting (${state.attempt}/${state.maxAttempts})..." to false
            is VpnLifecycleState.Disconnecting -> "Disconnecting..." to false
            is VpnLifecycleState.Error -> "Error: ${state.error.message}" to true
            is VpnLifecycleState.Suspended -> "Suspended" to false
        }
        
        updateNotification(status)
        
        // Broadcast state change for HyperVpnStateManager compatibility
        broadcastStateChange(state)
        
        if (shouldStop && state is VpnLifecycleState.Idle) {
            stopForegroundAndSelf()
        }
    }
    
    /**
     * Broadcast state change for backward compatibility with HyperVpnStateManager
     */
    private fun broadcastStateChange(state: VpnLifecycleState) {
        val intent = Intent("com.hyperxray.an.HYPER_VPN_STATE_CHANGED").apply {
            setPackage(packageName)
            
            when (state) {
                is VpnLifecycleState.Idle -> {
                    putExtra("state", "disconnected")
                }
                is VpnLifecycleState.Preparing -> {
                    putExtra("state", "connecting")
                    putExtra("progress", state.progress)
                    putExtra("message", "Preparing: ${state.phase.name}")
                }
                is VpnLifecycleState.Connecting -> {
                    putExtra("state", "connecting")
                    putExtra("progress", state.progress)
                    putExtra("message", "Connecting: ${state.phase.name}")
                }
                is VpnLifecycleState.Connected -> {
                    putExtra("state", "connected")
                    putExtra("serverName", state.connectionInfo.serverName)
                    putExtra("serverLocation", state.connectionInfo.serverLocation)
                    putExtra("connectedAt", state.connectionInfo.connectedAt.toEpochMilli())
                }
                is VpnLifecycleState.Reconnecting -> {
                    putExtra("state", "connecting")
                    putExtra("progress", state.attempt.toFloat() / state.maxAttempts)
                    putExtra("message", "Reconnecting (${state.attempt}/${state.maxAttempts})")
                }
                is VpnLifecycleState.Disconnecting -> {
                    putExtra("state", "disconnecting")
                    putExtra("progress", state.progress)
                    putExtra("message", "Disconnecting: ${state.phase.name}")
                }
                is VpnLifecycleState.Error -> {
                    putExtra("state", "error")
                    putExtra("error", state.error.message)
                    putExtra("error_code", state.error.code)
                    putExtra("error_details", state.error.stackTrace)
                }
                is VpnLifecycleState.Suspended -> {
                    putExtra("state", "connecting")
                    putExtra("progress", 0f)
                    putExtra("message", "Suspended")
                }
            }
        }
        sendBroadcast(intent)
    }
    
    private fun formatDuration(duration: java.time.Duration): String {
        val hours = duration.toHours()
        val minutes = duration.toMinutes() % 60
        val seconds = duration.seconds % 60
        
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
    
    private fun stopForegroundAndSelf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }
}

/**
 * Native tunnel bridge implementation
 * Bridges the new lifecycle system with existing native JNI code
 */
class NativeTunnelBridgeImpl(
    private val service: VpnService
) : NativeTunnelBridge {
    
    companion object {
        private const val TAG = "NativeTunnelBridge"
        
        init {
            try {
                System.loadLibrary("hyperxray-jni")
                System.loadLibrary("hyperxray")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e(TAG, "Failed to load native libraries: ${e.message}")
            }
        }
    }
    
    // Native method declarations (same as HyperVpnService)
    private external fun startHyperTunnel(
        tunFd: Int,
        wgConfigJSON: String,
        xrayConfigJSON: String,
        warpEndpoint: String,
        warpPrivateKey: String,
        nativeLibDir: String,
        filesDir: String
    ): Int
    
    private external fun stopHyperTunnel(): Int
    private external fun nativeGetTunnelStats(): String
    private external fun getHandshakeRTT(): Long
    private external fun initSocketProtector()
    private external fun isSocketProtectorVerified(): Boolean
    
    override suspend fun createTunInterface(): ParcelFileDescriptor {
        return withContext(Dispatchers.IO) {
            val builder = service.Builder()
                .setSession("HyperXray VPN (Next-Gen)")
                .addAddress("10.0.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .setMtu(1420)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
            
            // Add IPv6 for complete traffic capture
            try {
                builder.addAddress("fd00::2", 126)
                builder.addRoute("::", 0)
                builder.addDnsServer("2001:4860:4860::8888")
                builder.addDnsServer("2606:4700:4700::1111")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "IPv6 not supported: ${e.message}")
            }
            
            builder.establish() ?: throw IllegalStateException("Failed to establish VPN interface")
        }
    }
    
    override suspend fun initializeSocketProtector() {
        withContext(Dispatchers.IO) {
            try {
                initSocketProtector()
                if (!isSocketProtectorVerified()) {
                    throw IllegalStateException("Socket protector verification failed")
                }
            } catch (e: UnsatisfiedLinkError) {
                throw IllegalStateException("Native method not found: ${e.message}")
            }
        }
    }
    
    override suspend fun startTunnel(tunFd: Int, wgConfig: String, xrayConfig: String): Int {
        return withContext(Dispatchers.IO) {
            val context = service.applicationContext
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val filesDir = context.filesDir.absolutePath
            
            // Extract endpoint and private key from WG config
            val wgJson = org.json.JSONObject(wgConfig)
            val endpoint = wgJson.optString("endpoint", "")
            val privateKey = wgJson.optString("privateKey", "")
            
            try {
                startHyperTunnel(
                    tunFd = tunFd,
                    wgConfigJSON = wgConfig,
                    xrayConfigJSON = xrayConfig,
                    warpEndpoint = endpoint,
                    warpPrivateKey = privateKey,
                    nativeLibDir = nativeLibDir,
                    filesDir = filesDir
                )
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e(TAG, "startHyperTunnel failed: ${e.message}")
                -14 // Native library error code
            }
        }
    }
    
    override suspend fun stopTunnel(): Int {
        return withContext(Dispatchers.IO) {
            try {
                stopHyperTunnel()
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e(TAG, "stopHyperTunnel failed: ${e.message}")
                -1
            }
        }
    }
    
    override suspend fun performHandshake(): Long {
        return withContext(Dispatchers.IO) {
            try {
                getHandshakeRTT()
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e(TAG, "getHandshakeRTT failed: ${e.message}")
                -1L
            }
        }
    }
    
    override suspend fun verifyConnection(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val stats = nativeGetTunnelStats()
                val json = org.json.JSONObject(stats)
                json.optBoolean("connected", false)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "verifyConnection failed: ${e.message}")
                false
            }
        }
    }
    
    override suspend fun getTunnelStats(): ConnectionStats {
        return withContext(Dispatchers.IO) {
            try {
                val statsJson = nativeGetTunnelStats()
                val json = org.json.JSONObject(statsJson)
                
                ConnectionStats(
                    txBytes = json.optLong("txBytes", 0),
                    rxBytes = json.optLong("rxBytes", 0),
                    txPackets = json.optLong("txPackets", 0),
                    rxPackets = json.optLong("rxPackets", 0),
                    lastHandshakeTime = json.optLong("lastHandshake", 0).let {
                        if (it > 0) Instant.ofEpochMilli(it) else null
                    },
                    currentLatencyMs = json.optLong("latency", 0),
                    averageLatencyMs = json.optLong("avgLatency", 0),
                    packetLossPercent = json.optDouble("packetLoss", 0.0).toFloat(),
                    uptimeSeconds = json.optLong("uptime", 0)
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "getTunnelStats failed: ${e.message}")
                ConnectionStats()
            }
        }
    }
}

/**
 * Config provider implementation
 * Uses WarpManager for WARP configuration
 */
class ConfigProviderImpl(
    private val context: Context,
    private val prefs: Preferences
) : ConfigProvider {
    
    private var currentConfigs: VpnConfigs? = null
    private var serverInfo: ServerInfo = ServerInfo()
    private val warpManager = com.hyperxray.an.util.WarpManager.getInstance()
    
    data class ServerInfo(
        val name: String = "Unknown",
        val location: String = "Unknown",
        val ip: String = ""
    )
    
    override suspend fun loadConfigs(configId: String?): VpnConfigs {
        return withContext(Dispatchers.IO) {
            // Load WireGuard config (WARP or custom)
            val wgConfig = loadWireGuardConfig()
            
            // Load Xray config from profile
            val xrayConfig = loadXrayConfig(configId)
            
            // Extract server info
            extractServerInfo(wgConfig, xrayConfig)
            
            VpnConfigs(
                wireGuardConfig = wgConfig,
                xrayConfig = xrayConfig
            ).also {
                currentConfigs = it
            }
        }
    }
    
    override suspend fun getCurrentConfigs(): VpnConfigs {
        return currentConfigs ?: loadConfigs(null)
    }
    
    override fun validateWireGuardConfig(config: String): Boolean {
        return try {
            val json = org.json.JSONObject(config)
            json.has("privateKey") && json.has("endpoint")
        } catch (e: Exception) {
            false
        }
    }
    
    override fun validateXrayConfig(config: String): Boolean {
        return try {
            val json = org.json.JSONObject(config)
            json.has("outbounds")
        } catch (e: Exception) {
            false
        }
    }
    
    override fun getServerName(): String = serverInfo.name
    override fun getServerLocation(): String = serverInfo.location
    override fun getServerIp(): String = serverInfo.ip
    
    private suspend fun loadWireGuardConfig(): String {
        // Try to load saved WARP config first
        val savedConfig = loadSavedWarpConfig()
        if (savedConfig != null) {
            return savedConfig
        }
        
        // Register with WARP API and get config
        return try {
            val result = warpManager.registerAndGetConfig(context.filesDir)
            if (result.isSuccess) {
                result.getOrNull()?.toJsonString() ?: getDefaultWarpConfig()
            } else {
                getDefaultWarpConfig()
            }
        } catch (e: Exception) {
            android.util.Log.e("ConfigProvider", "Failed to get WARP config: ${e.message}")
            getDefaultWarpConfig()
        }
    }
    
    private fun loadSavedWarpConfig(): String? {
        val warpFile = java.io.File(context.filesDir, "warp_account.json")
        if (!warpFile.exists()) return null
        
        return try {
            val content = warpFile.readText()
            val json = org.json.JSONObject(content)
            
            org.json.JSONObject().apply {
                put("privateKey", json.optString("privateKey", ""))
                put("publicKey", "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")
                put("endpoint", json.optString("endpoint", "162.159.192.1:2408"))
                put("address", json.optString("address", "10.0.0.2/32"))
                put("dns", "1.1.1.1")
            }.toString()
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getDefaultWarpConfig(): String {
        return org.json.JSONObject().apply {
            put("privateKey", "")
            put("publicKey", "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")
            put("endpoint", "162.159.192.1:2408")
            put("address", "10.0.0.2/32")
            put("dns", "1.1.1.1")
        }.toString()
    }
    
    private fun loadXrayConfig(configId: String?): String {
        // Load from selected profile or default
        return org.json.JSONObject().apply {
            put("outbounds", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("protocol", "freedom")
                    put("tag", "direct")
                })
            })
        }.toString()
    }
    
    private fun extractServerInfo(wgConfig: String, xrayConfig: String) {
        try {
            val wgJson = org.json.JSONObject(wgConfig)
            val endpoint = wgJson.optString("endpoint", "")
            
            serverInfo = if (endpoint.contains("162.159.192") || endpoint.contains("cloudflare")) {
                ServerInfo(
                    name = "Cloudflare WARP",
                    location = "Global",
                    ip = endpoint.substringBefore(":")
                )
            } else {
                ServerInfo(
                    name = "Custom Server",
                    location = "Unknown",
                    ip = endpoint.substringBefore(":")
                )
            }
        } catch (e: Exception) {
            // Keep default
        }
    }
}
