package com.hyperxray.an.vpn

import android.app.Application
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hyperxray.an.R
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.activity.MainActivity
import com.hyperxray.an.util.WarpManager
import com.hyperxray.an.data.repository.ConfigRepository
import com.hyperxray.an.data.source.LogFileManager
import com.hyperxray.an.notification.TelegramNotificationManager
import com.hyperxray.an.core.inference.OnnxRuntimeManager
import com.hyperxray.an.core.network.vpn.HyperVpnStateManager
import com.hyperxray.an.core.config.utils.ConfigInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.CompletableFuture

/**
 * HyperVpnService - Native Go library based VPN service
 * 
 * Uses libhyperxray.so (Go binary) to create WireGuard + Xray tunnel.
 * This service provides a high-performance VPN connection using native Go code.
 */
class HyperVpnService : VpnService() {
    
    companion object {
        private const val TAG = "HyperVpnService"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "hyper_vpn_channel"
        
        // Primary actions (native Go based)
        const val ACTION_START = "com.hyperxray.an.HYPER_VPN_START"
        const val ACTION_STOP = "com.hyperxray.an.HYPER_VPN_STOP"
        
        // TProxyService compatible actions (for backward compatibility)
        const val ACTION_CONNECT = "com.hyperxray.an.CONNECT"
        const val ACTION_DISCONNECT = "com.hyperxray.an.DISCONNECT"
        const val ACTION_RELOAD_CONFIG = "com.hyperxray.an.RELOAD_CONFIG"
        const val ACTION_ERROR = "com.hyperxray.an.ERROR"
        const val ACTION_LOG_UPDATE = "com.hyperxray.an.LOG_UPDATE"
        
        // Extras
        const val EXTRA_WG_CONFIG = "wg_config"
        const val EXTRA_XRAY_CONFIG = "xray_config"
        const val EXTRA_LOG_DATA = "log_data"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        
        // Constants
        const val BROADCAST_DELAY_MS: Long = 1000
        const val BROADCAST_BUFFER_SIZE_THRESHOLD: Int = 100
        
        // Track library loading status
        private var nativeLibraryLoaded = false
        private var nativeLibraryError: String? = null
        private var goLibraryLoaded = false
        
        // Load native library
        // Load Go library first, then JNI wrapper
        init {
            loadNativeLibraries()
        }
        
        private fun loadNativeLibraries() {
            // CRITICAL: Load JNI wrapper FIRST so g_protector symbol is available
            // when Go library (hyperxray) loads and tries to resolve it
            try {
                System.loadLibrary("hyperxray-jni")
                nativeLibraryLoaded = true
                Log.i(TAG, "JNI wrapper (hyperxray-jni) loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                nativeLibraryError = e.message
                Log.e(TAG, "CRITICAL: Failed to load JNI wrapper: ${e.message}", e)
            }
            
            // Now load Go library - it will find g_protector symbol from hyperxray-jni
            try {
                System.loadLibrary("hyperxray")
                goLibraryLoaded = true
                Log.i(TAG, "Go library (hyperxray) loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Could not load Go library directly: ${e.message}")
                // This is OK - JNI wrapper will load it via dlopen
            } catch (e: Exception) {
                nativeLibraryError = e.message
                Log.e(TAG, "Unexpected error loading JNI wrapper: ${e.message}", e)
            }
        }
        
        fun isNativeLibraryAvailable(): Boolean = nativeLibraryLoaded
        fun getNativeLibraryError(): String? = nativeLibraryError
        
        fun getNativeLibraryDir(context: Context?): String? {
            if (context == null) {
                Log.e(TAG, "Context is null")
                return null
            }
            return try {
                val applicationInfo = context.applicationInfo
                applicationInfo?.nativeLibraryDir?.also {
                    Log.d(TAG, "Native Library Directory: $it")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting native library dir", e)
                null
            }
        }
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var tunFd: ParcelFileDescriptor? = null
    private var isRunning = false
    private var isStopping = false
    private var isStarting = false
    private val warpManager = WarpManager.getInstance()
    private var startTime: Long = 0
    private var lastStatsUpdate: Long = 0
    
    // Session state (simplified, without SOCKS5/MultiInstance/SystemDnsCacheServer)
    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private var logFileManager: LogFileManager? = null
    private var telegramNotificationManager: TelegramNotificationManager? = null
    private var prefs: Preferences? = null
    
    // Recovery tracking
    private var recoveryAttempts = 0
    private var lastRecoveryTime: Long = 0L
    private val MAX_RECOVERY_ATTEMPTS = 3
    private val RECOVERY_COOLDOWN_MS = 30000L
    
    // Native function declarations
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
    
    private external fun getTunnelStats(): String
    
    private external fun getLastNativeError(): String
    
    private external fun nativeGeneratePublicKey(privateKeyBase64: String): String
    
    private external fun freeString(str: String)
    
    /**
     * Check if native library is ready
     * @return true if library is loaded and ready
     */
    /**
     * Check if native library is ready
     * @return true if library is loaded and ready
     */
    private external fun isNativeLibraryReady(): Boolean
    
    /**
     * Load Go library from specific path
     * @param path Absolute path to libhyperxray.so
     * @return true if loaded successfully
     */
    private external fun loadGoLibraryWithPath(path: String): Boolean
    
    // Multi-instance native function declarations
    private external fun initMultiInstanceManager(
        nativeLibDir: String,
        filesDir: String,
        maxInstances: Int
    ): Int
    
    private external fun startMultiInstances(
        count: Int,
        configJSON: String,
        excludedPortsJSON: String
    ): String
    
    private external fun stopMultiInstance(index: Int): Int
    
    private external fun stopAllMultiInstances(): Int
    
    private external fun getMultiInstanceStatus(index: Int): String
    
    private external fun getAllMultiInstancesStatus(): String
    
    private external fun getMultiInstanceCount(): Int
    
    private external fun isMultiInstanceRunning(): Boolean
    
    // DNS native function declarations
    private external fun initDNSCache(cacheDir: String): Int
    private external fun dnsCacheLookup(hostname: String): String
    private external fun dnsCacheLookupAll(hostname: String): String
    private external fun dnsCacheSave(hostname: String, ipsJSON: String, ttl: Long)
    private external fun getDNSCacheMetrics(): String
    private external fun dnsCacheClear()
    private external fun dnsCacheCleanupExpired(): Int
    private external fun startDNSServer(port: Int, upstreamDNS: String): Int
    private external fun stopDNSServer(): Int
    private external fun isDNSServerRunning(): Boolean
    private external fun getDNSServerPort(): Int
    private external fun getDNSServerStats(): String
    private external fun dnsResolve(hostname: String): String
    
    // AiLogHelper callback for native code
    private external fun setAiLogHelperCallback()
    
    // Socket protector native methods
    private external fun initSocketProtector()
    private external fun cleanupSocketProtector()
    
    /**
     * Called from native code to log to AiLogHelper
     * This method is called via JNI from Go code
     */
    @Suppress("unused")
    private fun logToAiLogHelper(tag: String, level: String, message: String) {
        when (level.uppercase()) {
            "ERROR", "E" -> AiLogHelper.e(tag, message)
            "WARN", "W" -> AiLogHelper.w(tag, message)
            "INFO", "I" -> AiLogHelper.i(tag, message)
            "DEBUG", "D" -> AiLogHelper.d(tag, message)
            else -> AiLogHelper.d(tag, message)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeManagers()
        setupWakeLock()
        setupHeartbeat()
        
        // Ensure Go library is loaded (fix for "native library not read" error)
        // JNI wrapper (libhyperxray-jni.so) loads first, but Go library (libhyperxray.so) 
        // must be manually loaded via dlopen because it has Go exports
        if (nativeLibraryLoaded && !goLibraryLoaded) {
            AiLogHelper.w(TAG, "JNI wrapper loaded but Go library not loaded, attempting manual load...")
            val libDir = getNativeLibraryDir(this)
            if (libDir != null) {
                val libPath = File(libDir, "libhyperxray.so").absolutePath
                AiLogHelper.d(TAG, "Attempting to load Go library from: $libPath")
                try {
                    if (loadGoLibraryWithPath(libPath)) {
                        goLibraryLoaded = true
                        AiLogHelper.i(TAG, "✅ Go library loaded successfully via manual path")
                    } else {
                        AiLogHelper.e(TAG, "❌ Failed to load Go library via manual path")
                    }
                } catch (e: UnsatisfiedLinkError) {
                    AiLogHelper.e(TAG, "❌ JNI method loadGoLibraryWithPath not found: ${e.message}", e)
                } catch (e: Exception) {
                    AiLogHelper.e(TAG, "❌ Error loading Go library manually: ${e.message}", e)
                }
            } else {
                AiLogHelper.e(TAG, "❌ Could not determine native library directory")
            }
        } else if (!nativeLibraryLoaded) {
            AiLogHelper.e(TAG, "❌ JNI wrapper not loaded: $nativeLibraryError")
        } else {
            AiLogHelper.i(TAG, "✅ Both JNI wrapper and Go library loaded successfully")
        }
        
        // Initialize socket protector FIRST!
        // This must be done before any network operations
        Log.d(TAG, "Initializing socket protector...")
        try {
            initSocketProtector()
            Log.d(TAG, "Socket protector initialized")
            AiLogHelper.d(TAG, "Socket protector initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize socket protector: ${e.message}", e)
            AiLogHelper.e(TAG, "Failed to initialize socket protector: ${e.message}", e)
        }
        
        // Set AiLogHelper callback for native Go code
        try {
            setAiLogHelperCallback()
            AiLogHelper.d(TAG, "AiLogHelper callback set for native code")
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Failed to set AiLogHelper callback: ${e.message}", e)
        }
        
        AiLogHelper.d(TAG, "HyperVpnService created")
    }
    
    /**
     * Initialize all managers.
     */
    private fun initializeManagers() {
        prefs = Preferences(this)
        logFileManager = LogFileManager(this)
        
        // Initialize Telegram notification manager
        try {
            telegramNotificationManager = TelegramNotificationManager.getInstance(this)
            AiLogHelper.d(TAG, "Telegram notification manager initialized")
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Failed to initialize Telegram notification manager", e)
        }
        
        // Initialize AI optimizer
        try {
            prefs?.let {
                AiLogHelper.d(TAG, "AI optimizer initialized")
            }
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Failed to initialize AI optimizer: ${e.message}", e)
        }
        
        // Initialize ONNX Runtime Manager
        try {
            OnnxRuntimeManager.init(this)
            if (OnnxRuntimeManager.isReady()) {
                AiLogHelper.i(TAG, "TLS SNI optimizer model loaded successfully")
            }
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Failed to initialize TLS SNI optimizer: ${e.message}", e)
        }
    }
    
    /**
     * Setup WakeLock to prevent system from killing service.
     */
    private fun setupWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HyperXray::HyperVpnService::WakeLock"
            ).apply {
                acquire(10 * 60 * 60 * 1000L) // 10 hours timeout
            }
            AiLogHelper.d(TAG, "WakeLock acquired to prevent system kill")
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Failed to acquire WakeLock: ${e.message}", e)
        }
    }
    
    /**
     * Setup heartbeat coroutine to keep service alive.
     */
    private fun setupHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                try {
                    delay(30000) // 30 seconds
                    
                    // Update notification if running
                    if (isRunning) {
                        val uptime = if (startTime > 0) {
                            (System.currentTimeMillis() - startTime) / 1000
                        } else 0
                        AiLogHelper.d(TAG, "Heartbeat - VPN running for ${uptime}s")
                    }
                } catch (e: Exception) {
                    AiLogHelper.e(TAG, "Heartbeat error: ${e.message}", e)
                    delay(30000)
                }
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AiLogHelper.d(TAG, "onStartCommand: action=${intent?.action}")
        
        // Start foreground immediately to avoid ForegroundServiceDidNotStartInTimeException
        // Android requires startForeground() to be called within 5 seconds of startForegroundService()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, createNotification("Starting..."))
            } else {
                startForeground(NOTIFICATION_ID, createNotification("Starting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
            AiLogHelper.d(TAG, "Foreground service started with placeholder notification")
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Failed to start foreground service: ${e.message}", e)
        }
        
        when (intent?.action) {
            ACTION_START, ACTION_CONNECT -> {
                val wgConfig = intent.getStringExtra(EXTRA_WG_CONFIG)
                val xrayConfig = intent.getStringExtra(EXTRA_XRAY_CONFIG)
                startVpn(wgConfig, xrayConfig)
            }
            ACTION_STOP, ACTION_DISCONNECT -> {
                stopVpn()
            }
            ACTION_RELOAD_CONFIG -> {
                // For native Go tunnel, reload means restart
                AiLogHelper.d(TAG, "Reload config requested - restarting tunnel")
                serviceScope.launch {
                    stopVpn()
                    delay(1000)
                    startVpn(null, null)
                }
            }
            else -> {
                // Default action: start VPN
                if (intent?.action != null) {
                    AiLogHelper.w(TAG, "Unknown action: ${intent.action}")
                }
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return LocalBinder()
    }
    
    override fun onRevoke() {
        AiLogHelper.w(TAG, "VPN permission revoked (onRevoke)")
        serviceScope.launch {
            // Use try-catch to avoid double-close errors
            try {
                tunFd?.close()
            } catch (e: Exception) {
                AiLogHelper.w(TAG, "Error closing tunFd in onRevoke: ${e.message}")
            }
            tunFd = null
            stopVpn()
        }
        super.onRevoke()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        AiLogHelper.d(TAG, "onDestroy called, cleaning up resources")
        
        // Stop VPN
        stopVpn()
        
        // Cleanup socket protector
        Log.d(TAG, "Service destroying")
        try {
            cleanupSocketProtector()
            Log.d(TAG, "Socket protector cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up socket protector: ${e.message}", e)
        }
        
        // Cancel heartbeat job
        heartbeatJob?.cancel()
        heartbeatJob = null
        
        // Flush log file
        serviceScope.launch {
            logFileManager?.flush()
        }
        
        // Release WakeLock
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Error releasing WakeLock: ${e.message}", e)
        }
        
        // Cleanup AI optimizer
        
        // Release ONNX Runtime
        OnnxRuntimeManager.release()
        
        // Cleanup Telegram manager
        telegramNotificationManager = null
        
        // Cancel service scope
        serviceScope.cancel()
        
        // Broadcast stop event
        val stopIntent = Intent(ACTION_STOP)
        stopIntent.setPackage(application.packageName)
        sendBroadcast(stopIntent)
        
        AiLogHelper.d(TAG, "HyperVpnService destroyed")
    }
    
    /**
     * Start VPN with WireGuard and Xray configurations
     */
    private fun startVpn(wgConfigJson: String?, xrayConfigJson: String?) {
        if (isRunning) {
            AiLogHelper.w(TAG, "VPN is already running")
            return
        }
        
        if (isStarting) {
            AiLogHelper.d(TAG, "startVpn() already in progress, ignoring duplicate call")
            return
        }
        
        if (isStopping) {
            AiLogHelper.w(TAG, "startVpn() called while stopping, ignoring")
            return
        }
        
        isStarting = true
        
        // Reset recovery state for new connection
        recoveryAttempts = 0
        lastRecoveryTime = 0L
        
        // Close any existing tunFd before starting new connection
        // Use try-catch to avoid double-close errors
        try {
            tunFd?.close()
        } catch (e: Exception) {
            AiLogHelper.w(TAG, "Error closing existing tunFd (may already be closed): ${e.message}")
        }
        tunFd = null
        
        serviceScope.launch {
            try {
                // VPN permission is already granted at Activity/ViewModel level
                // No need to check again here - service assumes permission was granted before start
                
                // ===== STEP 1: Initializing =====
                broadcastState("connecting", 0.1f, "Initializing...")
                
                // Initialize ConfigRepository early to ensure configs are loaded
                AiLogHelper.d(TAG, "🔧 VPN START: Initializing ConfigRepository...")
                val app = applicationContext as Application
                val prefs = Preferences(app)
                val configRepo = ConfigRepository(app, prefs)
                
                // Ensure configs are loaded before proceeding
                try {
                    configRepo.loadConfigs()
                    val configFiles = configRepo.configFiles.first()
                    val selectedFile = configRepo.selectedConfigFile.first()
                    AiLogHelper.d(TAG, "✅ VPN START: ConfigRepository initialized - ${configFiles.size} config files, selected: ${selectedFile?.name ?: "none"}")
                } catch (e: Exception) {
                    AiLogHelper.w(TAG, "⚠️ VPN START: ConfigRepository loadConfigs() failed: ${e.message}, continuing anyway...")
                }
                
                // ===== STEP 2: Loading Configurations =====
                broadcastState("connecting", 0.3f, "Loading configurations...")
                
                // Get WireGuard config (WARP or custom)
                AiLogHelper.d(TAG, "🔧 VPN START: Getting WireGuard configuration...")
                var wgConfig = wgConfigJson ?: getWarpConfig()
                AiLogHelper.d(TAG, "✅ VPN START: WireGuard config obtained, length: ${wgConfig.length} bytes")
                
                // Check if WARP is being used (by checking if config contains WARP endpoint)
                val wgConfigObj = try {
                    org.json.JSONObject(wgConfig)
                } catch (e: Exception) {
                    AiLogHelper.e(TAG, "Failed to parse WireGuard config: ${e.message}")
                    broadcastError("Invalid WireGuard configuration", -4, e.stackTraceToString())
                    return@launch
                }
                
                val isWarpUsed = wgConfigObj.optString("endpoint", "").contains("162.159.192") || 
                                 wgConfigObj.optString("endpoint", "").contains("cloudflare")
                
                // Get Xray config (VLESS profile)
                AiLogHelper.d(TAG, "🔧 VPN START: Getting Xray configuration...")
                var xrayConfig = xrayConfigJson
                if (xrayConfig == null) {
                    AiLogHelper.d(TAG, "🔧 VPN START: No Xray config provided, loading from profile...")
                    val profileConfig = getXrayConfigFromProfile()
                    
                    AiLogHelper.d(TAG, "🔍 VPN START: Profile config obtained, length: ${profileConfig.length} bytes")
                    
                    // Validate Xray config has valid outbounds
                    var profileConfigObj: org.json.JSONObject? = null
                    try {
                        profileConfigObj = org.json.JSONObject(profileConfig)
                        AiLogHelper.d(TAG, "✅ VPN START: Profile config JSON parsed successfully")
                    } catch (e: Exception) {
                        AiLogHelper.e(TAG, "❌ VPN START: Failed to parse Xray config: ${e.message}", e)
                        if (!isWarpUsed) {
                            // WARP olmadan VLESS profile zorunlu
                            val errorMsg = "WireGuard over Xray-core requires a VLESS profile. Please create or import a profile first."
                            broadcastError(errorMsg, -12, "No valid VLESS profile found. Config parsing failed: ${e.message}")
                            return@launch
                        }
                        // WARP kullanıldığında fallback: direct outbound
                        AiLogHelper.w(TAG, "⚠️ VPN START: Using fallback direct outbound (WARP without valid profile)")
                        xrayConfig = getDefaultXrayConfig()
                    }
                    
                    // If xrayConfig is still null, check profile config
                    if (xrayConfig == null && profileConfigObj != null) {
                        val outbounds = profileConfigObj.optJSONArray("outbounds")
                        if (outbounds == null || outbounds.length() == 0) {
                            if (!isWarpUsed) {
                                // WARP olmadan VLESS profile zorunlu
                                val errorMsg = "WireGuard over Xray-core requires a VLESS profile. Please create or import a profile first."
                                broadcastError(errorMsg, -12, "Profile does not contain valid VLESS outbound. Outbounds array is empty or null.")
                                return@launch
                            }
                            // WARP kullanıldığında fallback: direct outbound
                            AiLogHelper.w(TAG, "⚠️ VPN START: Using fallback direct outbound (WARP without outbounds)")
                            xrayConfig = getDefaultXrayConfig()
                        } else {
                            xrayConfig = profileConfig
                            AiLogHelper.d(TAG, "✅ VPN START: Using profile config with ${outbounds.length()} outbound(s)")
                        }
                    }
                } else {
                    AiLogHelper.d(TAG, "✅ VPN START: Using provided Xray config, length: ${xrayConfig.length} bytes")
                }
                
                // Validate Xray config format and ensure outbounds exist
                AiLogHelper.d(TAG, "🔧 VPN START: Validating Xray config format...")
                val xrayConfigObj = try {
                    org.json.JSONObject(xrayConfig)
                } catch (e: Exception) {
                    AiLogHelper.e(TAG, "❌ VPN START: Invalid Xray config format: ${e.message}", e)
                    broadcastError("Invalid Xray configuration format", -5, "Config JSON parsing failed: ${e.message}")
                    return@launch
                }
                
                val xrayOutbounds = xrayConfigObj.optJSONArray("outbounds")
                if (xrayOutbounds == null || xrayOutbounds.length() == 0) {
                    if (!isWarpUsed) {
                        val errorMsg = "WireGuard over Xray-core requires a VLESS profile. Please create or import a profile first."
                        broadcastError(errorMsg, -12, "Xray config has no outbounds. Config validation failed.")
                        return@launch
                    }
                    // WARP kullanıldığında fallback: direct outbound
                    AiLogHelper.w(TAG, "⚠️ VPN START: Using fallback direct outbound (WARP without VLESS profile)")
                    xrayConfigObj.put("outbounds", JSONArray().apply {
                        put(JSONObject().apply {
                            put("protocol", "freedom")
                            put("tag", "direct")
                        })
                    })
                    xrayConfig = xrayConfigObj.toString()
                }
                
                // ===== STEP 3: Validating Configurations =====
                broadcastState("connecting", 0.5f, "Validating configurations...")
                AiLogHelper.i(TAG, "🚀 VPN START: Starting VPN tunnel (WireGuard over Xray-core)...")
                AiLogHelper.d(TAG, "📋 VPN START: WireGuard config type: ${if (isWarpUsed) "WARP" else "Custom"}")
                
                // Check final Xray config type
                val finalXrayConfigObj = org.json.JSONObject(xrayConfig)
                val finalOutbounds = finalXrayConfigObj.optJSONArray("outbounds")
                val firstOutbound = finalOutbounds?.optJSONObject(0)
                val protocol = firstOutbound?.optString("protocol", "") ?: ""
                val xrayConfigType = when {
                    protocol == "freedom" -> "Direct fallback"
                    protocol == "vless" || protocol == "vmess" -> "VLESS/VMESS profile"
                    else -> "Unknown"
                }
                AiLogHelper.d(TAG, "📋 VPN START: Xray config type: $xrayConfigType (protocol: $protocol)")
                AiLogHelper.d(TAG, "📋 VPN START: Final configs - WG: ${wgConfig.length} bytes, Xray: ${xrayConfig?.length ?: 0} bytes")
                
                // ===== STEP 4: Creating TUN Interface =====
                broadcastState("connecting", 0.7f, "Creating TUN interface...")
                AiLogHelper.i(TAG, "🔧 TUN ESTABLISH: Building VPN interface configuration...")
                val builder = Builder()
                builder.setSession("HyperXray VPN")
                builder.addAddress("10.0.0.2", 30)
                builder.addRoute("0.0.0.0", 0)
                // Force lower MTU for WireGuard over Xray (adds overhead)
                // 1280 is conservative and prevents fragmentation issues
                builder.setMtu(1280)
                
                // Establish VPN interface with timeout protection
                AiLogHelper.d(TAG, "🔧 TUN ESTABLISH: Calling builder.establish() with 10s timeout protection...")
                val establishStartTime = System.currentTimeMillis()
                tunFd = try {
                    val future = CompletableFuture.supplyAsync({
                        builder.establish()
                    }, java.util.concurrent.Executors.newSingleThreadExecutor())
                    
                    // Wait with timeout
                    future.get(10, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    val duration = System.currentTimeMillis() - establishStartTime
                    AiLogHelper.e(TAG, "❌ TUN ESTABLISH TIMEOUT: builder.establish() took longer than 10 seconds (${duration}ms) - possible system-level hang")
                    Log.e(TAG, "❌ TUN ESTABLISH TIMEOUT: builder.establish() took longer than 10 seconds!", e)
                    null
                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - establishStartTime
                    AiLogHelper.e(TAG, "❌ TUN ESTABLISH EXCEPTION: ${e.message} (${duration}ms)")
                    Log.e(TAG, "❌ TUN ESTABLISH EXCEPTION: ${e.message}", e)
                    null
                }
                
                val establishDuration = System.currentTimeMillis() - establishStartTime
                val establishedTunFd = tunFd
                if (establishedTunFd == null) {
                    AiLogHelper.e(TAG, "❌ TUN ESTABLISH FAILED: builder.establish() returned null (duration: ${establishDuration}ms)")
                    Log.e(TAG, "Failed to establish VPN interface (TUN). builder.establish() returned null.")
                    Log.e(TAG, "Possible causes: VPN permission not granted, another VPN is active, or system-level TUN creation error.")
                    broadcastError("Failed to establish VPN interface. Please check VPN permissions or disable other VPN services.", -1, "TUN interface creation failed after ${establishDuration}ms")
                    return@launch
                }
                
                AiLogHelper.i(TAG, "✅ TUN ESTABLISH: VPN interface established successfully (fd=${establishedTunFd.fd}, duration: ${establishDuration}ms)")
                Log.d(TAG, "VPN interface established successfully (fd=${establishedTunFd.fd}, duration: ${establishDuration}ms)")
                
                // Check if native library is loaded
                if (!nativeLibraryLoaded) {
                    val errorMsg = "Cannot start VPN: Native library not loaded. Error: $nativeLibraryError"
                    AiLogHelper.e(TAG, errorMsg)
                    broadcastError(errorMsg, -14, "Native library failed to load during initialization")
                    return@launch
                }
                
                // Double-check with native method
                val ready = try {
                    isNativeLibraryReady()
                } catch (e: UnsatisfiedLinkError) {
                    val errorMsg = "isNativeLibraryReady() failed: ${e.message}"
                    AiLogHelper.e(TAG, errorMsg, e)
                    broadcastError("JNI function not found: ${e.message}", -14, e.stackTraceToString())
                    return@launch
                } catch (e: Exception) {
                    val errorMsg = "Unexpected error checking library readiness: ${e.message}"
                    AiLogHelper.e(TAG, errorMsg, e)
                    broadcastError(errorMsg, -14, e.stackTraceToString())
                    return@launch
                }
                
                if (!ready) {
                    val errorMsg = "Cannot start VPN: Native library not ready (Go library may not be loaded)"
                    AiLogHelper.e(TAG, errorMsg)
                    broadcastError(errorMsg, -14, "Go library failed to load via dlopen")
                    return@launch
                }
                
                // Extract WARP endpoint and private key from WireGuard config (wgConfigObj already defined above)
                var warpEndpoint = wgConfigObj.optString("endpoint", "")
                val warpPrivateKey = wgConfigObj.optString("privateKey", "")
                
                AiLogHelper.d(TAG, "📋 VPN START: WARP endpoint (original): $warpEndpoint")
                
                // Resolve endpoint hostname to IP address
                // WireGuard requires IP address, not hostname
                // Update both the parameter AND the JSON config
                if (warpEndpoint.isNotBlank()) {
                    val resolvedEndpoint = try {
                        resolveEndpointToIp(warpEndpoint)
                    } catch (e: Exception) {
                        AiLogHelper.e(TAG, "❌ VPN START: Failed to resolve endpoint: ${e.message}", e)
                        // Fallback to original endpoint (may fail, but better than nothing)
                        warpEndpoint
                    }
                    
                    if (resolvedEndpoint != warpEndpoint) {
                        // Update endpoint in JSON config
                        wgConfigObj.put("endpoint", resolvedEndpoint)
                        // Update the wgConfig string
                        wgConfig = wgConfigObj.toString()
                        AiLogHelper.d(TAG, "📋 VPN START: Updated WireGuard config with resolved endpoint")
                        warpEndpoint = resolvedEndpoint
                    }
                }
                
                AiLogHelper.d(TAG, "📋 VPN START: WARP endpoint (resolved): $warpEndpoint")
                AiLogHelper.d(TAG, "📋 VPN START: WARP private key length: ${warpPrivateKey.length}")
                
                // Start native tunnel
                // Ensure xrayConfig is not null (should be validated above)
                val finalXrayConfig = xrayConfig ?: getDefaultXrayConfig()
                
                // Store Xray config for later use (to start Xray-core process)
                val xrayConfigForProcess = finalXrayConfig
                
                // Final validation before native call
                if (wgConfig.isBlank() || wgConfig == "{}") {
                    val errorMsg = "WireGuard config is empty or invalid"
                    AiLogHelper.e(TAG, "❌ VPN START: $errorMsg")
                    broadcastError(errorMsg, -4, "WireGuard configuration is empty or invalid JSON")
                    return@launch
                }
                
                if (finalXrayConfig.isBlank() || finalXrayConfig == "{}") {
                    val errorMsg = "Xray config is empty or invalid"
                    AiLogHelper.e(TAG, "❌ VPN START: $errorMsg")
                    broadcastError(errorMsg, -5, "Xray configuration is empty or invalid JSON")
                    return@launch
                }
                
                AiLogHelper.d(TAG, "✅ VPN START: Configs validated - WG: ${wgConfig.length} bytes, Xray: ${finalXrayConfig.length} bytes")
                
                // Get tunFd safely
                val currentTunFd = tunFd
                if (currentTunFd == null) {
                    val errorMsg = "TUN file descriptor is null"
                    AiLogHelper.e(TAG, "❌ VPN START: $errorMsg")
                    broadcastError(errorMsg, -3, "VPN interface was not established")
                    return@launch
                }
                
                // Detach file descriptor to transfer ownership to native code
                // This prevents fdsan ownership exchange errors
                val fdInt = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        currentTunFd.detachFd()
                    } else {
                        @Suppress("DEPRECATION")
                        currentTunFd.fd
                    }
                } catch (e: Exception) {
                    val errorMsg = "Failed to detach file descriptor: ${e.message}"
                    AiLogHelper.e(TAG, "❌ VPN START: $errorMsg", e)
                    broadcastError(errorMsg, -3, e.stackTraceToString())
                    try {
                        currentTunFd.close()
                    } catch (closeErr: Exception) {
                        AiLogHelper.w(TAG, "Error closing tunFd after detach failure: ${closeErr.message}")
                    }
                    tunFd = null
                    return@launch
                }
                
                AiLogHelper.d(TAG, "✅ VPN START: TUN fd detached: $fdInt")
                
                // After detaching, the ParcelFileDescriptor is invalid, so clear the reference
                // Native code now owns the file descriptor
                tunFd = null
                
                AiLogHelper.i(TAG, "🚀 VPN START: Calling startHyperTunnel native function...")
                AiLogHelper.d(TAG, "📋 VPN START: Native call params - tunFd: $fdInt, wgConfig: ${wgConfig.length} bytes, xrayConfig: ${finalXrayConfig.length} bytes")
                
                // Get native library and files directories with null safety
                val appInfo = applicationInfo
                if (appInfo == null) {
                    val errorMsg = "ApplicationInfo is null - cannot get native library directory"
                    AiLogHelper.e(TAG, "❌ VPN START: $errorMsg")
                    broadcastError(errorMsg, -14, "applicationInfo returned null")
                    return@launch
                }
                
                val nativeLibDir = appInfo.nativeLibraryDir
                if (nativeLibDir.isNullOrEmpty()) {
                    val errorMsg = "Native library directory is null or empty"
                    AiLogHelper.e(TAG, "❌ VPN START: $errorMsg")
                    broadcastError(errorMsg, -14, "applicationInfo.nativeLibraryDir returned null/empty")
                    return@launch
                }
                
                val filesDirPath = filesDir.absolutePath
                if (filesDirPath.isNullOrEmpty()) {
                    val errorMsg = "Files directory path is null or empty"
                    AiLogHelper.e(TAG, "❌ VPN START: $errorMsg")
                    broadcastError(errorMsg, -14, "filesDir.absolutePath returned null/empty")
                    return@launch
                }
                
                AiLogHelper.d(TAG, "📋 VPN START: Native lib dir: $nativeLibDir, Files dir: $filesDirPath")
                
                // CRITICAL: Inject gRPC StatsService into Xray config before starting tunnel
                // libhyperxray.so içindeki Xray-core'un gRPC API'sine erişebilmesi için gerekli
                // libxray.so ayrı process olarak başlatılmamalı - libhyperxray.so içindeki Xray-core kullanılmalı
                val apiPort = prefs.apiPort
                val xrayConfigWithApi = try {
                    ConfigInjector.injectApiPort(finalXrayConfig, apiPort)
                } catch (e: Exception) {
                    AiLogHelper.w(TAG, "⚠️ VPN START: Failed to inject API port into Xray config: ${e.message}, using original config")
                    finalXrayConfig
                }
                AiLogHelper.d(TAG, "✅ VPN START: gRPC StatsService injected (port: $apiPort) - libhyperxray.so içindeki Xray-core kullanılacak")
                
                val result = try {
                    startHyperTunnel(
                        tunFd = fdInt,
                        wgConfigJSON = wgConfig,
                        xrayConfigJSON = xrayConfigWithApi,
                        warpEndpoint = warpEndpoint,
                        warpPrivateKey = warpPrivateKey,
                        nativeLibDir = nativeLibDir,
                        filesDir = filesDirPath
                    )
                } catch (e: UnsatisfiedLinkError) {
                    val errorMsg = "startHyperTunnel() failed with UnsatisfiedLinkError: ${e.message}"
                    AiLogHelper.e(TAG, errorMsg, e)
                    broadcastError("JNI call failed: ${e.message}", -14, e.stackTraceToString())
                    // File descriptor was detached, native code should handle cleanup
                    // If native code didn't start, the fd will be closed by the system when process exits
                    // Note: tunFd is already null after detachFd()
                    return@launch
                } catch (e: Exception) {
                    val errorMsg = "startHyperTunnel() threw exception: ${e.message}"
                    AiLogHelper.e(TAG, errorMsg, e)
                    broadcastError("Unexpected error: ${e.message}", -14, e.stackTraceToString())
                    // File descriptor was detached, native code should handle cleanup
                    // If native code didn't start, the fd will be closed by the system when process exits
                    // Note: tunFd is already null after detachFd()
                    return@launch
                }
                
                if (result == 0) {
                    isRunning = true
                    startTime = System.currentTimeMillis()
                    
                    // Determine server info
                    val serverName = if (isWarpUsed) "Cloudflare WARP" else "Custom Server"
                    val serverLocation = if (isWarpUsed) "Global" else "Unknown"
                    val connectedAt = startTime
                    
                    // Start foreground with proper service type
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIFICATION_ID, createNotification("Connected"))
                    } else {
                        startForeground(NOTIFICATION_ID, createNotification("Connected"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    }
                    
                    AiLogHelper.i(TAG, "✅ VPN tunnel started successfully")
                    
                    // Initialize native DNS cache and server
                    initializeDnsComponents()
                    
                    // Note: Xray-core is integrated in native Go code (startHyperTunnel)
                    // No need to start separate Xray-core process
                    
                    // Log to file
                    logFileManager?.appendLog("[${System.currentTimeMillis()}] VPN tunnel started successfully")
                    
                    // ===== STEP 5: Connected =====
                    broadcastState("connected", 1.0f, "Connected", serverName, serverLocation, connectedAt)
                    
                    // Broadcast log update
                    broadcastLogUpdate("VPN tunnel started successfully")
                    
                    // Notify via Telegram if configured
                    telegramNotificationManager?.let { manager ->
                        serviceScope.launch {
                            try {
                                manager.notifyServiceStarted()
                            } catch (e: Exception) {
                                AiLogHelper.e(TAG, "Failed to send Telegram notification: ${e.message}")
                            }
                        }
                    }
                    
                    // Start stats monitoring
                    startStatsMonitoring()
                } else {
                    handleTunnelError(result)
                    // File descriptor was detached, native code owns it now
                    // ErrorTunnelStartFailed (-2) means native code already called tunnel.Stop() and closed TUN
                    // Other errors mean native code didn't start, but fd was detached so we can't close it
                    // The fd will be closed by the system when process exits
                    // Note: tunFd is already null after detachFd()
                    if (result == -2) {
                        AiLogHelper.d(TAG, "Native code already closed TUN (ErrorTunnelStartFailed)")
                    } else {
                        AiLogHelper.w(TAG, "Native code failed to start, fd was detached and will be closed by system")
                    }
                }
                
            } catch (e: Exception) {
                AiLogHelper.e(TAG, "Error starting VPN: ${e.message}", e)
                broadcastError("Failed to start VPN: ${e.message}", -1, e.stackTraceToString())
                // If detachFd() was called, tunFd is already null and fd ownership transferred to native
                // If detachFd() wasn't called (exception before that), tunFd might still be valid
                // Try to close only if tunFd is still valid (not detached)
                try {
                    tunFd?.close()
                } catch (closeErr: Exception) {
                    AiLogHelper.w(TAG, "Error closing tunFd after exception (may already be detached): ${closeErr.message}")
                }
                tunFd = null
            } finally {
                isStarting = false
            }
        }
    }
    
    /**
     * Stop VPN tunnel
     * Optimized for fast disconnect (< 2 seconds)
     */
    private fun stopVpn() {
        if (isStopping) {
            AiLogHelper.d(TAG, "stopVpn already in progress, ignoring duplicate call")
            return
        }
        
        if (!isRunning && !isStarting) {
            AiLogHelper.d(TAG, "VPN not running, nothing to stop")
            return
        }
        
        isStopping = true
        val disconnectStartTime = System.currentTimeMillis()
        
        // Immediately broadcast disconnecting state (don't wait for anything)
        broadcastState("disconnecting", 0.5f, "Disconnecting...")
        
        serviceScope.launch {
            try {
                AiLogHelper.d(TAG, "Stopping VPN tunnel...")
                
                // Fire-and-forget: Log to file asynchronously (don't wait)
                serviceScope.launch {
                    try {
                        logFileManager?.appendLog("[${System.currentTimeMillis()}] Stopping VPN tunnel...")
                    } catch (e: Exception) {
                        // Ignore log errors
                    }
                }
                
                // Update state immediately (BEFORE stopping tunnel - don't wait)
                // Save tunFd reference before nulling (for potential cleanup)
                val tunFdToClose = tunFd
                tunFd = null
                isRunning = false
                startTime = 0
                
                // Broadcast disconnected state IMMEDIATELY (before stopping tunnel)
                // This ensures UI updates instantly, tunnel stops in background
                broadcastState("disconnected")
                
                // Start DNS shutdown asynchronously with timeout (don't block disconnect)
                val dnsShutdownJob = serviceScope.launch {
                    try {
                        withTimeoutOrNull(500L) {
                            shutdownDnsComponents()
                        } ?: run {
                            AiLogHelper.w(TAG, "DNS shutdown timeout (500ms), continuing disconnect")
                        }
                    } catch (e: Exception) {
                        AiLogHelper.e(TAG, "Error in DNS shutdown: ${e.message}")
                    }
                }
                
                // Stop native tunnel asynchronously (don't block disconnect)
                // Tunnel stops in background, UI already updated to disconnected
                val stopTunnelJob = serviceScope.launch {
                    try {
                        val result = withTimeoutOrNull(1000L) {
                            stopHyperTunnel()
                        } ?: run {
                            AiLogHelper.w(TAG, "stopHyperTunnel timeout (1000ms), assuming stopped")
                            0 // Assume success if timeout
                        }
                        
                        // Log result asynchronously (don't block)
                        if (result == 0) {
                            AiLogHelper.i(TAG, "✅ VPN tunnel stopped successfully")
                        } else {
                            AiLogHelper.w(TAG, "Warning: stopHyperTunnel returned $result")
                            // Native code failed to stop properly, close manually (async, don't block)
                            tunFdToClose?.let { fd ->
                                try {
                                    fd.close()
                                } catch (e: Exception) {
                                    AiLogHelper.e(TAG, "Error closing tunFd after failed stop: ${e.message}")
                                }
                            }
                        }
                    } catch (e: UnsatisfiedLinkError) {
                        AiLogHelper.e(TAG, "stopHyperTunnel() failed: ${e.message}", e)
                    } catch (e: Exception) {
                        AiLogHelper.e(TAG, "stopHyperTunnel() threw exception: ${e.message}", e)
                    }
                }
                
                // Fire-and-forget: Log operations (don't wait)
                serviceScope.launch {
                    try {
                        logFileManager?.appendLog("[${System.currentTimeMillis()}] VPN tunnel stopped")
                    } catch (e: Exception) {
                        // Ignore log errors
                    }
                }
                
                // Fire-and-forget: Broadcast log update (don't wait)
                serviceScope.launch {
                    try {
                        broadcastLogUpdate("VPN tunnel stopped")
                    } catch (e: Exception) {
                        // Ignore broadcast errors
                    }
                }
                
                // Fire-and-forget: Telegram notification (don't wait)
                telegramNotificationManager?.let { manager ->
                    serviceScope.launch {
                        try {
                            manager.notifyServiceStopped()
                        } catch (e: Exception) {
                            AiLogHelper.e(TAG, "Failed to send Telegram notification: ${e.message}")
                        }
                    }
                }
                
                // Stop service immediately (don't wait for DNS shutdown or other async operations)
                val disconnectDuration = System.currentTimeMillis() - disconnectStartTime
                AiLogHelper.i(TAG, "✅ Disconnect completed in ${disconnectDuration}ms")
                
                stopForeground(true)
                stopSelf()
                
            } catch (e: Exception) {
                AiLogHelper.e(TAG, "Error stopping VPN: ${e.message}", e)
                // Still stop service even on error
                try {
                    stopForeground(true)
                    stopSelf()
                } catch (stopErr: Exception) {
                    AiLogHelper.e(TAG, "Error stopping service: ${stopErr.message}")
                }
            } finally {
                isStopping = false
            }
        }
    }
    
    /**
     * Start monitoring tunnel statistics
     */
    private fun startStatsMonitoring() {
        serviceScope.launch {
            while (isRunning) {
                try {
                    val statsJson = getTunnelStats()
                    AiLogHelper.d(TAG, "📊 Tunnel stats JSON: $statsJson")
                    val stats = JSONObject(statsJson)
                    
                    val txBytes = stats.optLong("txBytes", 0)
                    val rxBytes = stats.optLong("rxBytes", 0)
                    val txPackets = stats.optLong("txPackets", 0)
                    val rxPackets = stats.optLong("rxPackets", 0)
                    val lastHandshake = stats.optLong("lastHandshake", 0)
                    val connected = stats.optBoolean("connected", false)
                    
                    AiLogHelper.d(TAG, "📊 Tunnel stats - connected: $connected, txBytes: $txBytes, rxBytes: $rxBytes, txPackets: $txPackets, rxPackets: $rxPackets, lastHandshake: $lastHandshake")
                    
                    // Calculate additional metrics
                    val uptime = if (startTime > 0) {
                        (System.currentTimeMillis() - startTime) / 1000
                    } else 0
                    
                    val totalBytes = txBytes + rxBytes
                    val totalPackets = txPackets + rxPackets
                    val elapsed = (System.currentTimeMillis() - lastStatsUpdate).coerceAtLeast(1000)
                    val throughput = if (lastStatsUpdate > 0 && elapsed > 0) {
                        (totalBytes * 1000.0) / elapsed
                    } else 0.0
                    
                    lastStatsUpdate = System.currentTimeMillis()
                    
                    // Enhanced stats with calculated metrics
                    val enhancedStats = JSONObject().apply {
                        put("txBytes", txBytes)
                        put("rxBytes", rxBytes)
                        put("txPackets", txPackets)
                        put("rxPackets", rxPackets)
                        put("lastHandshake", lastHandshake)
                        put("connected", connected)
                        put("uptime", uptime)
                        put("latency", calculateLatency())
                        put("packetLoss", calculatePacketLoss(txPackets, rxPackets))
                        put("throughput", throughput)
                    }
                    
                    // Broadcast stats update
                    broadcastStats(enhancedStats.toString())
                    
                    if (!connected) {
                        AiLogHelper.w(TAG, "Tunnel disconnected")
                        broadcastError("Tunnel disconnected", -3, "Native tunnel reported disconnected state")
                        stopVpn()
                        break
                    }
                    
                    // Update notification with stats
                    updateNotification(
                        "Connected - TX: ${formatBytes(txBytes)}, RX: ${formatBytes(rxBytes)}"
                    )
                    
                    delay(1000) // Update every 1 second
                    
                } catch (e: Exception) {
                    AiLogHelper.e(TAG, "Error getting stats: ${e.message}", e)
                    broadcastError("Stats error: ${e.message}", -4, e.stackTraceToString())
                    delay(5000)
                }
            }
        }
    }
    
    /**
     * Calculate estimated latency (placeholder - can be enhanced)
     */
    private fun calculateLatency(): Long {
        // This is a placeholder. In a real implementation, you might:
        // - Use ping measurements
        // - Calculate from handshake times
        // - Use WireGuard's internal metrics
        return 50L // Default 50ms
    }
    
    /**
     * Calculate packet loss percentage
     */
    private fun calculatePacketLoss(txPackets: Long, rxPackets: Long): Double {
        if (txPackets == 0L) return 0.0
        val loss = ((txPackets - rxPackets).toDouble() / txPackets) * 100.0
        return loss.coerceIn(0.0, 100.0)
    }
    
    /**
     * Broadcast state change
     */
    private fun broadcastState(
        state: String, 
        progress: Float = 0f, 
        message: String? = null,
        serverName: String? = null,
        serverLocation: String? = null,
        connectedAt: Long? = null
    ) {
        val intent = Intent(HyperVpnStateManager.ACTION_STATE_CHANGED).apply {
            putExtra(HyperVpnStateManager.EXTRA_STATE, state)
            putExtra("progress", progress)
            message?.let { putExtra("message", it) }
            serverName?.let { putExtra("serverName", it) }
            serverLocation?.let { putExtra("serverLocation", it) }
            connectedAt?.let { putExtra("connectedAt", it) }
            setPackage(application.packageName)
        }
        sendBroadcast(intent)
    }
    
    /**
     * Broadcast statistics update
     */
    private fun broadcastStats(statsJson: String) {
        val intent = Intent(HyperVpnStateManager.ACTION_STATS_UPDATE).apply {
            putExtra(HyperVpnStateManager.EXTRA_STATS, statsJson)
            setPackage(application.packageName)
        }
        sendBroadcast(intent)
    }
    
    /**
     * Broadcast error
     */
    private fun broadcastError(message: String, code: Int, details: String? = null) {
        // Broadcast to HyperVpnStateManager
        val stateIntent = Intent(HyperVpnStateManager.ACTION_ERROR).apply {
            putExtra(HyperVpnStateManager.EXTRA_ERROR, message)
            putExtra(HyperVpnStateManager.EXTRA_ERROR_CODE, code)
            details?.let { putExtra(HyperVpnStateManager.EXTRA_ERROR_DETAILS, it) }
            setPackage(application.packageName)
        }
        sendBroadcast(stateIntent)
        
        // Also broadcast with ACTION_ERROR for backward compatibility
        val errorIntent = Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
            setPackage(application.packageName)
        }
        sendBroadcast(errorIntent)
        
        // Log to file
        logFileManager?.appendLog("[${System.currentTimeMillis()}] ERROR: $message (code: $code)")
    }
    
    /**
     * Broadcast log update
     */
    private fun broadcastLogUpdate(logData: String) {
        val intent = Intent(ACTION_LOG_UPDATE).apply {
            putExtra(EXTRA_LOG_DATA, logData)
            setPackage(application.packageName)
        }
        sendBroadcast(intent)
    }
    
    /**
     * Handle tunnel error with detailed error information
     */
    private fun handleTunnelError(errorCode: Int) {
        val errorMessage = when (errorCode) {
            0 -> "Success"
            -1 -> "Failed to create tunnel instance"
            -2 -> "Failed to start tunnel"
            -3 -> "Invalid TUN file descriptor"
            -4 -> "Invalid WireGuard configuration"
            -5 -> "Invalid Xray configuration"
            -6 -> "Tunnel already running"
            -7 -> "Tunnel not running"
            -20 -> "Xray cannot reach internet"
            -21 -> "Cannot connect to Xray server"
            -22 -> "TLS handshake with Xray server failed"
            -99 -> "Panic in native code"
            else -> "Unknown error"
        }
        
        // Get detailed error from Go
        val nativeError = try {
            getLastNativeError()
        } catch (e: Exception) {
            "Could not get native error: ${e.message}"
        }
        
        Log.e(TAG, "Tunnel error $errorCode: $errorMessage")
        Log.e(TAG, "Native error details: $nativeError")
        
        // Get user-friendly error details
        val errorDetails = getErrorDetails(errorCode, nativeError)
        
        // Update UI/state
        broadcastError(errorMessage, errorCode, errorDetails)
    }
    
    /**
     * Get human-readable error message from error code
     */
    private fun getErrorMessage(code: Int): String {
        return when (code) {
            0 -> "Success"
            -1 -> "Failed to create tunnel instance. Check WireGuard and Xray configurations."
            -2 -> "Failed to start tunnel. Verify network connectivity and server availability."
            -3 -> "Invalid TUN file descriptor. VPN permission may not be granted."
            -4 -> "Invalid WireGuard configuration. Check config format and keys."
            -5 -> "Invalid Xray configuration. Check config format and server settings."
            -6 -> "Tunnel already running. Stop existing tunnel first."
            -7 -> "Tunnel not running. No tunnel to stop."
            -20 -> "Xray cannot reach internet. Check your proxy server."
            -21 -> "Cannot connect to Xray server"
            -22 -> "TLS handshake with Xray server failed"
            -99 -> "Panic in native code. This is a bug, please report it."
            -10 -> "VPN permission not granted. Please grant VPN permission in system settings."
            -11 -> "Failed to get WARP configuration. Check internet connection."
            -12 -> "No profiles available or invalid Xray configuration. Please create or import a VLESS profile first."
            -13 -> "Failed to establish VPN interface. Another VPN may be active."
            -14 -> "Native library error. Reinstall the app if problem persists."
            -100 -> "Failed to load Go library. Check if libhyperxray.so is present."
            -101 -> "Go library function not found. Library may be corrupted."
            -102 -> "String conversion failed. Check input parameters."
            else -> "Unknown error (code: $code). Check logs for details."
        }
    }
    
    /**
     * Get detailed error information for user
     */
    private fun getErrorDetails(code: Int, nativeError: String? = null): String {
        val baseDetails = when (code) {
            -1 -> "Possible causes: Invalid WireGuard keys, malformed config JSON, or missing dependencies."
            -2 -> "Possible causes: Server unreachable, firewall blocking, or invalid endpoint."
            -3 -> "Possible causes: VPN permission not granted, or TUN file descriptor is invalid."
            -4 -> "Possible causes: Invalid JSON format, missing required fields, or invalid key format."
            -5 -> "Possible causes: Invalid JSON format, missing outbounds, or invalid server configuration."
            -6 -> "Stop the existing tunnel before starting a new one."
            -7 -> "No tunnel is currently running."
            -20 -> "Xray started but cannot reach the internet. Check:\n1. Xray server is reachable\n2. VLESS/VMess credentials are correct\n3. TLS/REALITY handshake settings\n4. Network/firewall is not blocking"
            -21 -> "Cannot establish connection to Xray server. Check server address and port."
            -22 -> "TLS handshake failed with Xray server. Check TLS/REALITY configuration."
            -99 -> "A panic occurred in Go code. This indicates a bug. Please report with logs."
            -10 -> "Go to Settings > Apps > HyperXray > Permissions and enable VPN."
            -11 -> "WARP registration failed. Check internet connection and try again."
            -12 -> "No profiles available or invalid Xray configuration.\n\nPossible causes:\n- No profile files found in app directory\n- Selected profile file was deleted\n- Profile file is empty or corrupted\n- Profile does not contain valid VLESS/VMESS outbound\n\nSolution:\n1. Go to Profiles section in the app\n2. Create a new VLESS profile or import an existing one\n3. Ensure the profile contains at least one VLESS or VMESS outbound\n4. Try connecting again"
            -13 -> "Disconnect other VPN apps and try again."
            -14 -> "Native library may be corrupted. Try reinstalling the app."
            -100 -> "Go library (libhyperxray.so) not found in APK. Reinstall the app."
            -101 -> "Go library symbols not found. Library may be incompatible or corrupted."
            -102 -> "Parameter conversion error. This is a bug, please report it."
            else -> "See application logs for technical details."
        }
        
        return if (nativeError != null && nativeError.isNotEmpty()) {
            "$baseDetails\n\nNative error: $nativeError"
        } else {
            baseDetails
        }
    }
    
    /**
     * Generate WireGuard public key from private key
     */
    fun generatePublicKey(privateKeyBase64: String): String? {
        return try {
            val result = nativeGeneratePublicKey(privateKeyBase64)
            result.ifEmpty { null }
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Error generating public key: ${e.message}", e)
            null
        }
    }
    
    /**
     * Get WARP configuration by loading from account file or registering with Cloudflare
     */
    private suspend fun getWarpConfig(): String {
        return try {
            // First, try to load from warp-account.json file
            val filesDir = applicationContext.filesDir
            val savedConfig = warpManager.loadWarpAccountFromFile(filesDir)
            
            if (savedConfig != null) {
                AiLogHelper.d(TAG, "✅ WARP CONFIG: Loaded from warp-account.json file")
                broadcastState("connecting", 0.35f, "Using saved WARP account...")
                
                // Validate config before converting to JSON
                if (!savedConfig.isValid()) {
                    val errorMsg = "WARP account config validation failed"
                    AiLogHelper.e(TAG, "❌ WARP CONFIG: $errorMsg")
                    broadcastError(errorMsg, -11, "WARP account configuration is missing required fields")
                    return getDefaultWgConfig()
                }
                
                AiLogHelper.d(TAG, "✅ WARP CONFIG: Account config validated, converting to JSON...")
                
                // Convert to JSON
                val jsonConfig = try {
                    savedConfig.toJsonString()
                } catch (e: Exception) {
                    // Fallback to manual JSON construction
                    AiLogHelper.w(TAG, "⚠️ WARP CONFIG: toJsonString() failed, using fallback: ${e.message}")
                    JSONObject().apply {
                        put("privateKey", savedConfig.privateKey)
                        put("publicKey", savedConfig.publicKey)
                        put("address", savedConfig.address)
                        put("addressV6", savedConfig.addressV6 ?: "")
                        put("dns", savedConfig.dns)
                        put("mtu", savedConfig.mtu)
                        put("peerPublicKey", savedConfig.peerPublicKey)
                        put("endpoint", savedConfig.endpoint)
                        put("allowedIPs", savedConfig.allowedIPs)
                        put("persistentKeepalive", savedConfig.persistentKeepalive)
                    }.toString()
                }
                
                // Validate JSON format
                try {
                    JSONObject(jsonConfig)
                    AiLogHelper.d(TAG, "✅ WARP CONFIG: JSON validated, length: ${jsonConfig.length} bytes")
                    AiLogHelper.d(TAG, "📋 WARP CONFIG: Using saved account (first 200 chars): ${jsonConfig.take(200)}...")
                    return jsonConfig
                } catch (e: Exception) {
                    val errorMsg = "WARP account config JSON validation failed"
                    AiLogHelper.e(TAG, "❌ WARP CONFIG: $errorMsg - ${e.message}", e)
                    broadcastError(errorMsg, -11, "WARP account config is not valid JSON: ${e.message}")
                    // Fall through to registration
                }
            } else {
                AiLogHelper.d(TAG, "ℹ️ WARP CONFIG: No saved account found, will register new account")
            }
            
            // If no saved account or validation failed, register new account
            broadcastState("connecting", 0.35f, "Registering with Cloudflare WARP...")
            AiLogHelper.d(TAG, "🔧 WARP CONFIG: Starting WARP registration...")
            
            val result = warpManager.registerAndGetConfig(filesDir)
            if (result.isSuccess) {
                val config = result.getOrNull()
                if (config != null) {
                    // Validate config before converting to JSON
                    if (!config.isValid()) {
                        val errorMsg = "WARP config validation failed"
                        AiLogHelper.e(TAG, "❌ WARP CONFIG: $errorMsg")
                        broadcastError(errorMsg, -11, "WARP configuration is missing required fields")
                        return getDefaultWgConfig()
                    }
                    
                    AiLogHelper.d(TAG, "✅ WARP CONFIG: Config validated, converting to JSON...")
                    
                    // Convert to JSON using toJsonString() if available, otherwise use manual conversion
                    val jsonConfig = try {
                        config.toJsonString()
                    } catch (e: Exception) {
                        // Fallback to manual JSON construction
                        AiLogHelper.w(TAG, "⚠️ WARP CONFIG: toJsonString() failed, using fallback: ${e.message}")
                        JSONObject().apply {
                            put("privateKey", config.privateKey)
                            put("publicKey", config.publicKey)
                            put("address", config.address)
                            put("addressV6", config.addressV6 ?: "")
                            put("dns", config.dns)
                            put("mtu", config.mtu)
                            put("peerPublicKey", config.peerPublicKey)
                            put("endpoint", config.endpoint)
                            put("allowedIPs", config.allowedIPs)
                            put("persistentKeepalive", config.persistentKeepalive)
                        }.toString()
                    }
                    
                    // Validate JSON format
                    try {
                        JSONObject(jsonConfig)
                        AiLogHelper.d(TAG, "✅ WARP CONFIG: JSON validated, length: ${jsonConfig.length} bytes")
                        AiLogHelper.d(TAG, "📋 WARP CONFIG: Config preview (first 200 chars): ${jsonConfig.take(200)}...")
                        jsonConfig
                    } catch (e: Exception) {
                        val errorMsg = "WARP config JSON validation failed"
                        AiLogHelper.e(TAG, "❌ WARP CONFIG: $errorMsg - ${e.message}", e)
                        broadcastError(errorMsg, -11, "Generated WARP config is not valid JSON: ${e.message}")
                        getDefaultWgConfig()
                    }
                } else {
                    val errorMsg = "WARP registration returned empty config"
                    AiLogHelper.e(TAG, "❌ WARP CONFIG: $errorMsg")
                    broadcastError(errorMsg, -11, "Cloudflare WARP API returned invalid response (config is null)")
                    getDefaultWgConfig()
                }
            } else {
                val exception = result.exceptionOrNull()
                val errorMsg = "Failed to get WARP config: ${exception?.message ?: "Unknown error"}"
                if (exception != null) {
                    AiLogHelper.e(TAG, "❌ WARP CONFIG: $errorMsg", exception)
                } else {
                    AiLogHelper.e(TAG, "❌ WARP CONFIG: $errorMsg")
                }
                broadcastError(errorMsg, -11, exception?.stackTraceToString() ?: "WARP registration failed")
                getDefaultWgConfig()
            }
        } catch (e: Exception) {
            val errorMsg = "Error getting WARP config: ${e.message}"
            AiLogHelper.e(TAG, "❌ WARP CONFIG: $errorMsg", e)
            broadcastError(errorMsg, -11, "WARP registration exception: ${e.message}. Stack: ${e.stackTraceToString().take(500)}")
            getDefaultWgConfig()
        }
    }
    
    /**
     * Get Xray configuration from selected profile
     * Returns a valid Xray config JSON with VLESS outbound for WireGuard over Xray-core
     */
    private suspend fun getXrayConfigFromProfile(): String {
        return try {
            broadcastState("connecting", 0.4f, "Loading Xray configuration from profile...")
            
            // Initialize ConfigRepository
            val app = applicationContext as Application
            val prefs = Preferences(app)
            val configRepo = ConfigRepository(app, prefs)
            
            AiLogHelper.d(TAG, "🔍 CONFIG LOAD: Initializing ConfigRepository...")
            
            // CRITICAL: Load configs first to ensure StateFlow is populated
            try {
                configRepo.loadConfigs()
                AiLogHelper.d(TAG, "✅ CONFIG LOAD: loadConfigs() completed")
            } catch (e: Exception) {
                AiLogHelper.e(TAG, "❌ CONFIG LOAD: Failed to load configs: ${e.message}", e)
                // Continue anyway, might have cached configs
            }
            
            // Get selected config file from StateFlow (preferred method)
            val selectedConfigFile = configRepo.selectedConfigFile.first()
            AiLogHelper.d(TAG, "🔍 CONFIG LOAD: Selected config file from StateFlow: ${selectedConfigFile?.name ?: "null"}")
            
            // Fallback: If no selected config, try to get from configFiles list
            val activeConfigFile = selectedConfigFile ?: run {
                AiLogHelper.w(TAG, "⚠️ CONFIG LOAD: No selected config, trying configFiles list...")
                val configFiles = configRepo.configFiles.first()
                AiLogHelper.d(TAG, "🔍 CONFIG LOAD: Found ${configFiles.size} config files in list")
                configFiles.firstOrNull()?.also {
                    AiLogHelper.d(TAG, "✅ CONFIG LOAD: Using first config from list: ${it.name}")
                }
            }
            
            // Check if we have a config file
            if (activeConfigFile == null) {
                val errorMsg = "No profiles available"
                AiLogHelper.e(TAG, "❌ CONFIG LOAD: $errorMsg")
                broadcastError(errorMsg, -12, "Please create or import a VLESS profile first. ConfigRepository returned no config files.")
                return getDefaultXrayConfig()
            }
            
            AiLogHelper.d(TAG, "🔍 CONFIG LOAD: Active config file: ${activeConfigFile.name}")
            AiLogHelper.d(TAG, "🔍 CONFIG LOAD: Config file path: ${activeConfigFile.absolutePath}")
            
            // Check if config file exists
            if (!activeConfigFile.exists()) {
                val errorMsg = "Selected profile file does not exist"
                AiLogHelper.e(TAG, "❌ CONFIG LOAD: $errorMsg - Path: ${activeConfigFile.absolutePath}")
                broadcastError(errorMsg, -12, "Profile file not found: ${activeConfigFile.absolutePath}. File may have been deleted.")
                return getDefaultXrayConfig()
            }
            
            AiLogHelper.d(TAG, "✅ CONFIG LOAD: Config file exists, size: ${activeConfigFile.length()} bytes")
            
            // Read config file content with proper error handling
            val configContent = try {
                activeConfigFile.readText()
            } catch (e: Exception) {
                val errorMsg = "Failed to read config file: ${e.message}"
                AiLogHelper.e(TAG, "❌ CONFIG LOAD: $errorMsg", e)
                broadcastError(errorMsg, -12, "Cannot read profile file: ${activeConfigFile.absolutePath}. Error: ${e.message}")
                return getDefaultXrayConfig()
            }
            
            AiLogHelper.d(TAG, "✅ CONFIG LOAD: Config content read, length: ${configContent.length} bytes")
            
            if (configContent.isBlank()) {
                val errorMsg = "Selected profile has empty configuration"
                AiLogHelper.e(TAG, "❌ CONFIG LOAD: $errorMsg")
                broadcastError(errorMsg, -12, "Profile file is empty: ${activeConfigFile.name}. Please select a valid VLESS profile with configuration.")
                return getDefaultXrayConfig()
            }
            
            // Parse Xray config JSON
            val config = try {
                JSONObject(configContent)
            } catch (e: org.json.JSONException) {
                val errorMsg = "Invalid JSON in profile configuration"
                AiLogHelper.e(TAG, "❌ CONFIG LOAD: $errorMsg - ${e.message}", e)
                broadcastError(errorMsg, -12, "Profile contains invalid JSON: ${e.message}. File: ${activeConfigFile.name}")
                return getDefaultXrayConfig()
            }
            
            AiLogHelper.d(TAG, "✅ CONFIG LOAD: JSON parsed successfully")
            
            // Validate that config has outbounds array
            val outbounds = config.optJSONArray("outbounds")
            if (outbounds == null || outbounds.length() == 0) {
                val errorMsg = "No outbound configuration found in profile"
                AiLogHelper.e(TAG, "❌ CONFIG LOAD: $errorMsg")
                broadcastError(errorMsg, -12, "Profile must contain at least one VLESS outbound. File: ${activeConfigFile.name}")
                return getDefaultXrayConfig()
            }
            
            AiLogHelper.d(TAG, "✅ CONFIG LOAD: Found ${outbounds.length()} outbound(s)")
            
            // Validate first outbound is VLESS
            val outbound = outbounds.optJSONObject(0)
            if (outbound == null) {
                val errorMsg = "No outbound configuration found in profile"
                AiLogHelper.e(TAG, "❌ CONFIG LOAD: $errorMsg")
                broadcastError(errorMsg, -12, "Profile must contain at least one VLESS outbound. File: ${activeConfigFile.name}")
                return getDefaultXrayConfig()
            }
            
            val protocol = outbound.optString("protocol", "").lowercase()
            AiLogHelper.d(TAG, "🔍 CONFIG LOAD: First outbound protocol: $protocol")
            
            if (protocol != "vless" && protocol != "vmess") {
                val errorMsg = "Profile must contain VLESS or VMESS outbound (found: $protocol)"
                AiLogHelper.e(TAG, "❌ CONFIG LOAD: $errorMsg")
                broadcastError(errorMsg, -12, "WireGuard over Xray-core requires a VLESS or VMESS profile. Found protocol: $protocol")
                return getDefaultXrayConfig()
            }
            
            // Validate server configuration
            val settings = outbound.optJSONObject("settings")
            val vnext = settings?.optJSONArray("vnext")
            val serverConfig = vnext?.optJSONObject(0)
            
            val serverAddress = serverConfig?.optString("address") ?: ""
            val serverPort = serverConfig?.optInt("port") ?: 0
            
            AiLogHelper.d(TAG, "🔍 CONFIG LOAD: Server address: $serverAddress, port: $serverPort")
            
            if (serverAddress.isBlank() || serverPort == 0) {
                val errorMsg = "Invalid server address or port in profile"
                AiLogHelper.e(TAG, "❌ CONFIG LOAD: $errorMsg - address: '$serverAddress', port: $serverPort")
                broadcastError(errorMsg, -12, "Profile must contain valid server address and port. Found: address='$serverAddress', port=$serverPort")
                return getDefaultXrayConfig()
            }
            
            // Return the full Xray config JSON (Go layer expects full config format)
            // Ensure inbounds array exists (even if empty)
            val resultConfig = JSONObject(configContent)
            if (!resultConfig.has("inbounds")) {
                resultConfig.put("inbounds", JSONArray())
                AiLogHelper.d(TAG, "🔧 CONFIG LOAD: Added empty inbounds array")
            }
            
            // Ensure outbounds array exists and is valid
            if (!resultConfig.has("outbounds") || resultConfig.optJSONArray("outbounds") == null || resultConfig.optJSONArray("outbounds")!!.length() == 0) {
                val errorMsg = "Profile has invalid outbounds configuration"
                AiLogHelper.e(TAG, "❌ CONFIG LOAD: $errorMsg")
                broadcastError(errorMsg, -12, "Profile must contain at least one valid VLESS outbound. File: ${activeConfigFile.name}")
                return getDefaultXrayConfig()
            }
            
            val finalConfigJson = resultConfig.toString()
            AiLogHelper.i(TAG, "✅ CONFIG LOAD: Successfully loaded $protocol config from profile: $serverAddress:$serverPort")
            AiLogHelper.d(TAG, "📋 CONFIG LOAD: Final config JSON length: ${finalConfigJson.length} bytes")
            AiLogHelper.d(TAG, "📋 CONFIG LOAD: Final config preview (first 200 chars): ${finalConfigJson.take(200)}...")
            
            finalConfigJson
        } catch (e: org.json.JSONException) {
            val errorMsg = "Invalid JSON in profile configuration"
            AiLogHelper.e(TAG, "❌ CONFIG LOAD: $errorMsg", e)
            broadcastError(errorMsg, -12, "Profile contains invalid JSON: ${e.message}")
            getDefaultXrayConfig()
        } catch (e: Exception) {
            val errorMsg = "Error getting Xray config: ${e.message}"
            AiLogHelper.e(TAG, "❌ CONFIG LOAD: $errorMsg", e)
            broadcastError(errorMsg, -12, "Unexpected error loading profile: ${e.message}. Stack: ${e.stackTraceToString().take(500)}")
            getDefaultXrayConfig()
        }
    }
    
    /**
     * Get default WireGuard configuration (fallback)
     */
    private fun getDefaultWgConfig(): String {
        return JSONObject().apply {
            put("privateKey", "")
            put("publicKey", "")
            put("address", "172.16.0.2/32")
            put("addressV6", "")
            put("dns", "1.1.1.1")
            put("mtu", 1280)
            put("peerPublicKey", "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")
            put("endpoint", "162.159.192.1:2408")
            put("allowedIPs", "0.0.0.0/0, ::/0")
            put("persistentKeepalive", 25)
        }.toString()
    }
    
    // ============================================================================
    // DNS COMPONENTS
    // ============================================================================
    
    private var nativeDnsManager: NativeDnsManager? = null
    
    /**
     * Initialize native DNS cache and server
     */
    private fun initializeDnsComponents() {
        try {
            // Initialize DNS cache
            val cacheDirPath = cacheDir.absolutePath
            val cacheResult = initDNSCache(cacheDirPath)
            if (cacheResult == 0) {
                AiLogHelper.i(TAG, "✅ Native DNS cache initialized")
            } else {
                AiLogHelper.w(TAG, "⚠️ Failed to initialize native DNS cache: $cacheResult")
            }
            
            // Start DNS server for local DNS resolution
            val dnsPort = 5353
            val upstreamDns = "1.1.1.1:53" // Cloudflare DNS
            val serverResult = startDNSServer(dnsPort, upstreamDns)
            if (serverResult > 0) {
                AiLogHelper.i(TAG, "✅ Native DNS server started on port $serverResult")
            } else {
                AiLogHelper.w(TAG, "⚠️ Failed to start native DNS server: $serverResult")
            }
            
            // Initialize Kotlin wrapper
            nativeDnsManager = NativeDnsManager.getInstance(this).also { manager ->
                manager.initializeCache(this)
            }
            
            AiLogHelper.i(TAG, "✅ DNS components initialized successfully")
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Error initializing DNS components: ${e.message}", e)
        }
    }
    
    /**
     * Shutdown DNS components
     */
    private fun shutdownDnsComponents() {
        try {
            // Shutdown Kotlin wrapper
            nativeDnsManager?.shutdown()
            nativeDnsManager = null
            
            // Stop DNS server
            val serverResult = stopDNSServer()
            if (serverResult == 0) {
                AiLogHelper.i(TAG, "✅ Native DNS server stopped")
            } else {
                AiLogHelper.w(TAG, "⚠️ stopDNSServer returned: $serverResult")
            }
            
            // Cleanup expired cache entries before shutdown
            val cleanedUp = dnsCacheCleanupExpired()
            if (cleanedUp > 0) {
                AiLogHelper.d(TAG, "Cleaned up $cleanedUp expired DNS cache entries")
            }
            
            AiLogHelper.i(TAG, "✅ DNS components shutdown complete")
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Error shutting down DNS components: ${e.message}", e)
        }
    }
    
    /**
     * Get DNS cache metrics for UI display
     */
    fun getDnsCacheMetricsData(): String {
        return try {
            getDNSCacheMetrics()
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Error getting DNS cache metrics: ${e.message}")
            "{}"
        }
    }
    
    /**
     * Get DNS server statistics for UI display
     */
    fun getDnsServerStatsData(): String {
        return try {
            getDNSServerStats()
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Error getting DNS server stats: ${e.message}")
            "{}"
        }
    }
    
    /**
     * Check if DNS server is running
     */
    fun isDnsServerActive(): Boolean {
        return try {
            isDNSServerRunning()
        } catch (e: Exception) {
            false
        }
    }
    
    // Note: Xray-core integration is handled in native Go code (startHyperTunnel)
    // WireGuard TUN traffic is directly routed to Xray-core via native bridge
    
    /**
     * Resolve endpoint hostname to IP address
     * Input format: "hostname:port" or "IP:port"
     * Output format: "IP:port"
     */
    private suspend fun resolveEndpointToIp(endpoint: String): String = withContext(Dispatchers.IO) {
        try {
            // Parse endpoint to extract host and port
            val parts = endpoint.split(":")
            if (parts.size != 2) {
                AiLogHelper.w(TAG, "⚠️ Invalid endpoint format: $endpoint (expected host:port)")
                return@withContext endpoint
            }
            
            val host = parts[0]
            val port = parts[1]
            
            // Check if host is already an IP address
            val isIpAddress = try {
                host.split(".").size == 4 && host.split(".").all { it.toIntOrNull() in 0..255 }
            } catch (e: Exception) {
                false
            }
            
            if (isIpAddress) {
                AiLogHelper.d(TAG, "✅ Endpoint is already an IP address: $endpoint")
                return@withContext endpoint
            }
            
            // Resolve domain name to IP address
            AiLogHelper.d(TAG, "🔍 Resolving endpoint hostname: $host")
            val addresses = InetAddress.getAllByName(host)
            
            if (addresses.isEmpty()) {
                AiLogHelper.e(TAG, "❌ No IP addresses found for: $host")
                return@withContext endpoint
            }
            
            // Prefer IPv4 over IPv6
            val selectedAddress = addresses.firstOrNull { it.address.size == 4 } ?: addresses.first()
            val resolvedIp = selectedAddress.hostAddress
            
            val resolvedEndpoint = "$resolvedIp:$port"
            AiLogHelper.i(TAG, "✅ Resolved endpoint: $endpoint -> $resolvedEndpoint")
            
            resolvedEndpoint
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "❌ Failed to resolve endpoint $endpoint: ${e.message}", e)
            // Return original endpoint on error (may fail, but better than crashing)
            endpoint
        }
    }
    
    /**
     * Get default Xray configuration (fallback)
     * Returns a valid Xray config with minimal "direct" outbound for WireGuard over Xray-core fallback
     */
    private fun getDefaultXrayConfig(): String {
        return JSONObject().apply {
            // Empty inbounds array (not needed for WireGuard over Xray-core)
            put("inbounds", JSONArray())
            
            // Minimal "direct" outbound for fallback (when WARP is used without VLESS profile)
            put("outbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("protocol", "freedom")
                    put("tag", "direct")
                })
            })
        }.toString()
    }
    
    /**
     * Create notification channel
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "HyperXray VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "HyperXray VPN Service"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
    
    /**
     * Create notification
     */
    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("HyperXray VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    /**
     * Update notification
     */
    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification(text))
    }
    
    /**
     * Format bytes to human readable string
     */
    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.2f MB", mb)
            kb >= 1.0 -> String.format("%.2f KB", kb)
            else -> "$bytes B"
        }
    }
    
    /**
     * Local binder for service binding
     */
    inner class LocalBinder : Binder() {
        fun getService(): HyperVpnService = this@HyperVpnService
    }
}
