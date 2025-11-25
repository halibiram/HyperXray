package com.hyperxray.an.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.hyperxray.an.R
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.data.source.LogFileManager
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.viewmodel.CoreStatsState
import com.hyperxray.an.core.inference.OnnxRuntimeManager
import com.hyperxray.an.xray.runtime.MultiXrayCoreManager
import com.hyperxray.an.notification.TelegramNotificationManager
import com.hyperxray.an.service.utils.TProxyUtils
import com.hyperxray.an.service.managers.TunInterfaceManager
import com.hyperxray.an.service.managers.HevSocksManager
import com.hyperxray.an.service.managers.ServiceDependencyManager
import com.hyperxray.an.service.xray.XrayRunner
import com.hyperxray.an.service.xray.XrayRunnerContext
import com.hyperxray.an.service.xray.XrayConfig
import com.hyperxray.an.service.xray.LegacyXrayRunner
import com.hyperxray.an.service.xray.MultiInstanceXrayRunner
import com.hyperxray.an.service.state.ServiceSessionState
import com.hyperxray.an.service.handlers.ServiceIntentHandler
import com.hyperxray.an.common.Socks5ReadinessChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.File
import android.net.VpnService

/**
 * VPN service orchestrator that coordinates VPN session lifecycle.
 * 
 * This class acts as an **Orchestrator** that delegates responsibilities to specialized managers:
 * 
 * **Orchestrator Flow:**
 * 
 * **Startup Sequence:**
 * 1. `onCreate()`: Initialize all managers (TunInterfaceManager, XrayRunner, HevSocksManager, etc.)
 * 2. `initiateVpnSession()`: Establish VPN interface → Start Xray-core → Start native TProxy
 *    - `tunInterfaceManager.establish()` - Creates VPN TUN interface
 *    - `xrayRunner.start()` - Starts Xray-core process(es)
 *    - `hevSocksManager.startNativeTProxy()` - Starts native SOCKS5 tunnel (via SOCKS5 readiness callback)
 * 
 * **Shutdown Sequence:**
 * 1. `stopXray()`: Stop Xray → Stop TProxy → Close VPN interface
 *    - `hevSocksManager.stopNativeTProxy()` - Stops native TProxy first
 *    - `xrayRunner.stop()` - Stops Xray-core process(es)
 *    - `tunInterfaceManager.closeTunFd()` - Closes VPN interface
 * 
 * **Managers:**
 * - **[TunInterfaceManager]**: VPN TUN interface establishment, DNS configuration, routing
 * - **[XrayRunner]**: Xray-core process execution (LegacyXrayRunner or MultiInstanceXrayRunner)
 * - **[HevSocksManager]**: Native SOCKS5 TProxy operations (JNI calls, traffic forwarding)
 * - **[XrayLogHandler]**: Process log reading, parsing, and broadcasting
 * - **[ServiceNotificationManager]**: Foreground service notifications
 * - **[ServiceDependencyManager]**: Service-level dependencies (AI optimizer, Telegram notifications, etc.)
 * 
 * The orchestrator coordinates the sequence but does not implement the details.
 * All implementation logic is delegated to the specialized managers.
 * 
 * @see TunInterfaceManager for VPN interface management
 * @see XrayRunner for Xray-core process execution
 * @see HevSocksManager for native TProxy operations
 * @see ServiceDependencyManager for service dependencies
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TProxyService : VpnService() {
    // Session state - single instance for all service state
    private val session = ServiceSessionState()
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    
    // Managers
    private lateinit var xrayLogHandler: com.hyperxray.an.service.managers.XrayLogHandler
    private lateinit var hevSocksManager: HevSocksManager
    private lateinit var intentHandler: ServiceIntentHandler
    private val dependencyManager = ServiceDependencyManager()

    // Recovery constants
    private val MAX_RECOVERY_ATTEMPTS = 3
    private val RECOVERY_COOLDOWN_MS = 30000L
    private val CONNECTION_RESET_THRESHOLD = 5
    private val CONNECTION_RESET_WINDOW_MS = 60000L

    override fun onCreate() {
        super.onCreate()
        AiLogHelper.i(TAG, "🔧 SERVICE CREATE: TProxyService onCreate() called")
        
        try {
            // CRITICAL: Initialize managers first - must complete successfully before any other setup
            initializeManagers()
            
            // Verify notificationManager was initialized successfully
            if (!session.isNotificationManagerInitialized()) {
                val errorMsg = "CRITICAL: notificationManager not initialized after initializeManagers()"
                Log.e(TAG, errorMsg)
                AiLogHelper.e(TAG, "❌ SERVICE CREATE: $errorMsg")
                throw IllegalStateException(errorMsg)
            }
            
            setupLogHandler()
            setupWakeLock()
            setupNotification()
            setupHeartbeat()
            dependencyManager.setup(this, session)
            initializeXrayRunner()
            AiLogHelper.i(TAG, "✅ SERVICE CREATE: TProxyService initialization completed")
        } catch (e: Exception) {
            val errorMsg = "CRITICAL: Service initialization failed: ${e.message}"
            Log.e(TAG, errorMsg, e)
            AiLogHelper.e(TAG, "❌ SERVICE CREATE: $errorMsg", e)
            // Don't allow service to start in broken state
            stopSelf()
            throw RuntimeException("TProxyService initialization failed", e)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return intentHandler.handleIntent(intent)
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called, cleaning up resources.")
        AiLogHelper.i(TAG, "🛑 SERVICE DESTROY: TProxyService onDestroy() called, cleaning up resources")
        
        cleanupResources()
        
        // Stop Xray and clean up all resources
        serviceScope.launch {
            stopVpn("Service onDestroy() called")
        }
        
        // Flush log file buffer before shutdown
        serviceScope.launch {
            session.logFileManager.flush()
        }
        
        dependencyManager.cleanup()
        cleanupSessionState()
        
        Log.d(TAG, "TProxyService destroyed.")
        AiLogHelper.i(TAG, "✅ SERVICE DESTROY: TProxyService destroyed successfully")
    }

    override fun onRevoke() {
        serviceScope.launch {
            session.tunInterfaceManager.closeTunFd(session)
            stopVpn("VPN permission revoked (onRevoke)")
        }
        super.onRevoke()
    }

    /**
     * Initialize all managers.
     * 
     * CRITICAL: This method must complete successfully before any other setup methods are called.
     * All managers are initialized synchronously and in order to prevent race conditions.
     * 
     * @throws IllegalStateException if any manager initialization fails
     */
    private fun initializeManagers() {
        try {
            AiLogHelper.d(TAG, "🔧 INIT MANAGERS: Starting manager initialization...")
            
            session.prefs = Preferences(this)
            AiLogHelper.d(TAG, "✅ INIT MANAGERS: Preferences initialized")
            
            session.logFileManager = LogFileManager(this)
            AiLogHelper.d(TAG, "✅ INIT MANAGERS: LogFileManager initialized")
            
            session.tunInterfaceManager = TunInterfaceManager(this)
            AiLogHelper.d(TAG, "✅ INIT MANAGERS: TunInterfaceManager initialized")
            
            // CRITICAL: notificationManager must be initialized before setupNotification() is called
            session.notificationManager = com.hyperxray.an.service.managers.ServiceNotificationManager(this)
            AiLogHelper.d(TAG, "✅ INIT MANAGERS: ServiceNotificationManager initialized")
            
            hevSocksManager = HevSocksManager(this)
            AiLogHelper.d(TAG, "✅ INIT MANAGERS: HevSocksManager initialized")
            
            intentHandler = ServiceIntentHandler(session, this, serviceScope)
            AiLogHelper.d(TAG, "✅ INIT MANAGERS: ServiceIntentHandler initialized")
            
            xrayLogHandler = com.hyperxray.an.service.managers.XrayLogHandler(serviceScope, this)
            AiLogHelper.d(TAG, "✅ INIT MANAGERS: XrayLogHandler initialized")
            
            AiLogHelper.i(TAG, "✅ INIT MANAGERS: All managers initialized successfully")
        } catch (e: Exception) {
            val errorMsg = "Failed to initialize managers: ${e.message}"
            Log.e(TAG, errorMsg, e)
            AiLogHelper.e(TAG, "❌ INIT MANAGERS: $errorMsg", e)
            throw IllegalStateException(errorMsg, e)
        }
    }

    /**
     * Setup Xray log handler with callbacks.
     */
    private fun setupLogHandler() {
        xrayLogHandler.logFileManager = session.logFileManager
        xrayLogHandler.isStopping = { session.isStopping }
        xrayLogHandler.serviceStartTime = session.serviceStartTime
        xrayLogHandler.udpErrorHistory = session.udpErrorHistory
        xrayLogHandler.maxErrorHistorySize = session.maxErrorHistorySize
        xrayLogHandler.connectionResetThreshold = CONNECTION_RESET_THRESHOLD
        xrayLogHandler.connectionResetWindowMs = CONNECTION_RESET_WINDOW_MS
        
        xrayLogHandler.listener = object : com.hyperxray.an.service.managers.XrayLogHandler.LogProcessingListener {
            override fun onXrayStarted() {
                if (!session.socks5ReadinessChecked) {
                    Log.i(TAG, "Detected Xray startup in logs, scheduling SOCKS5 readiness check")
                    session.socks5ReadinessJob?.cancel()
                    session.socks5ReadinessJob = serviceScope.launch {
                        // Wait 2 seconds for Xray to fully initialize SOCKS5 server
                        // Check isStopping periodically during delay to avoid unnecessary wait
                        var waited = 0L
                        val checkInterval = 200L // Check every 200ms
                        while (waited < 2000 && !session.isStopping) {
                            delay(checkInterval)
                            waited += checkInterval
                        }
                        
                        if (session.isStopping) {
                            Log.w(TAG, "⚠️ Service is stopping during SOCKS5 readiness check delay, aborting")
                            return@launch
                        }
                        
                        if (!session.socks5ReadinessChecked && !session.isStopping) {
                            Log.d(TAG, "Starting SOCKS5 readiness check after Xray startup (waited ${waited}ms)")
                            checkSocks5Readiness(session.prefs)
                        } else {
                            Log.d(TAG, "Skipping SOCKS5 readiness check: already checked=${session.socks5ReadinessChecked}, stopping=${session.isStopping}")
                        }
                    }
                } else {
                    Log.d(TAG, "Xray started but SOCKS5 already checked, skipping readiness check")
                }
            }

            override fun onUdpErrorDetected(
                errorRecord: com.hyperxray.an.service.utils.TProxyUtils.UdpErrorRecord,
                pattern: com.hyperxray.an.service.utils.TProxyUtils.UdpErrorPattern,
                updateLastErrorTime: (Long) -> Unit,
                updateErrorCount: (Int) -> Unit
            ) {
                TProxyUtils.handleUdpErrorWithPattern(
                    errorRecord = errorRecord,
                    pattern = pattern,
                    isStopping = session.isStopping,
                    telegramNotificationManager = session.telegramNotificationManager,
                    serviceScope = serviceScope,
                    onRecoveryTriggered = { _, _ ->
                        serviceScope.launch {
                            TProxyUtils.triggerUdpErrorRecovery(
                                errorRecord = errorRecord,
                                pattern = pattern,
                                isStopping = session.isStopping,
                                udpRecoveryAttempts = session.udpRecoveryAttempts,
                                maxRecoveryAttempts = MAX_RECOVERY_ATTEMPTS,
                                recoveryCooldownMs = RECOVERY_COOLDOWN_MS,
                                lastUdpRecoveryTime = session.lastUdpRecoveryTime,
                                serviceScope = serviceScope,
                                xrayProcess = session.xrayProcess,
                                udpErrorHistory = session.udpErrorHistory,
                                onUdpRecoveryAttemptsUpdated = { attempts -> session.udpRecoveryAttempts = attempts },
                                onLastUdpRecoveryTimeUpdated = { time -> session.lastUdpRecoveryTime = time },
                                onUdpErrorCountReset = {
                                    session.udpErrorCount = 0
                                    session.lastUdpErrorTime = 0L
                                },
                                onCriticalRecoveryTriggered = { pattern ->
                                    serviceScope.launch {
                                        TProxyUtils.attemptCriticalUdpRecovery(
                                            pattern = pattern,
                                            isStopping = session.isStopping,
                                            serviceScope = serviceScope,
                                            stopXray = { reason -> stopVpn(reason) },
                                            startXray = { initiateVpnSession() },
                                            telegramNotificationManager = session.telegramNotificationManager,
                                            onUdpRecoveryAttemptsReset = {
                                                session.udpRecoveryAttempts = 0
                                                session.lastUdpRecoveryTime = 0L
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }
                )
                TProxyUtils.notifyUdpErrorToTelemetry(errorRecord, pattern)
                updateLastErrorTime(xrayLogHandler.lastUdpErrorTime)
                updateErrorCount(xrayLogHandler.udpErrorCount)
            }

            override fun onConnectionResetErrorDetected(
                newCount: Int,
                newTime: Long,
                updateCount: (Int) -> Unit,
                updateTime: (Long) -> Unit
            ) {
                serviceScope.launch {
                    TProxyUtils.handleConnectionResetRecovery(
                        context = this@TProxyService,
                        serviceScope = serviceScope,
                        isStopping = session.isStopping,
                        prefs = session.prefs,
                        onRecoverySuccess = {
                            session.connectionResetErrorCount = 0
                            session.lastConnectionResetTime = 0L
                        },
                        onRecoveryFailure = { errorMsg ->
                            Log.e(TAG, errorMsg)
                        }
                    )
                }
                updateCount(newCount)
                updateTime(newTime)
            }

            override fun onSniDetected(sni: String, logEntry: String) {
                TProxyUtils.processSNIFromLog(
                    context = this@TProxyService,
                    logEntry = logEntry,
                    serviceScope = serviceScope,
                    onnxRuntimeManagerReady = OnnxRuntimeManager.isReady(),
                    coreStatsState = session.coreStatsState,
                    lastLatency = session.lastLatency,
                    onLatencyUpdated = { newLatency -> session.lastLatency = newLatency },
                    applyRoutingDecision = { sni, routingDecisionIndex ->
                        TProxyUtils.applyRoutingDecision(sni, routingDecisionIndex)
                    }
                )
            }
        }
    }

    /**
     * Setup wake lock to prevent system from killing service.
     */
    private fun setupWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            session.wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HyperXray::TProxyService::WakeLock"
            ).apply {
                acquire(10 * 60 * 60 * 1000L) // 10 hours timeout
            }
            Log.d(TAG, "WakeLock acquired to prevent system kill")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}", e)
        }
    }

    /**
     * Setup foreground notification.
     * 
     * CRITICAL: This method assumes notificationManager is already initialized.
     * Defensive checks are in place to prevent UninitializedPropertyAccessException.
     */
    private fun setupNotification() {
        try {
            // Defensive check: Verify notificationManager is initialized
            if (!session.isNotificationManagerInitialized()) {
                val errorMsg = "CRITICAL: notificationManager not initialized before setupNotification()"
                Log.e(TAG, errorMsg)
                AiLogHelper.e(TAG, "❌ SETUP NOTIFICATION: $errorMsg")
                throw IllegalStateException(errorMsg)
            }
            
            val channelName = "socks5"
            session.notificationManager.initNotificationChannel(channelName, "VPN Service")
            session.notificationManager.createNotification(
                channelName = channelName,
                contentTitle = getString(R.string.app_name),
                contentText = "VPN service is running"
            )
            AiLogHelper.d(TAG, "✅ SETUP NOTIFICATION: Foreground notification setup completed")
        } catch (e: UninitializedPropertyAccessException) {
            val errorMsg = "CRITICAL: notificationManager accessed before initialization: ${e.message}"
            Log.e(TAG, errorMsg, e)
            AiLogHelper.e(TAG, "❌ SETUP NOTIFICATION: $errorMsg", e)
            // Re-throw to prevent service from starting in broken state
            throw IllegalStateException(errorMsg, e)
        } catch (e: Exception) {
            val errorMsg = "Failed to setup notification: ${e.message}"
            Log.e(TAG, errorMsg, e)
            AiLogHelper.e(TAG, "❌ SETUP NOTIFICATION: $errorMsg", e)
            // Re-throw to prevent service from starting without notification
            throw IllegalStateException(errorMsg, e)
        }
    }

    /**
     * Setup heartbeat coroutine to keep service alive.
     */
    private fun setupHeartbeat() {
        session.heartbeatJob = serviceScope.launch {
            var lastDnsCacheNotificationTime = 0L
            val DNS_CACHE_NOTIFICATION_INTERVAL_MS = 10L * 60L * 1000L // 10 minutes
            
            while (isActive) {
                try {
                    val interval = TProxyUtils.calculateAdaptivePollingInterval(session.coreStatsState)
                    delay(interval)
                    
                    // Defensive check: Verify notificationManager is initialized before use
                    if (!session.isNotificationManagerInitialized()) {
                        Log.w(TAG, "⚠️ Heartbeat: notificationManager not initialized, skipping notification update")
                        delay(30000) // Wait longer before retry
                        continue
                    }
                    
                    val currentChannelName = if (Preferences(this@TProxyService).disableVpn) "nosocks" else "socks5"
                    session.notificationManager.createNotification(
                        channelName = currentChannelName,
                        contentTitle = this@TProxyService.getString(R.string.app_name),
                        contentText = "VPN service is running"
                    )
                    
                    val now = System.currentTimeMillis()
                    if (lastDnsCacheNotificationTime == 0L || 
                        (now - lastDnsCacheNotificationTime) >= DNS_CACHE_NOTIFICATION_INTERVAL_MS) {
                        session.telegramNotificationManager?.let { manager ->
                            serviceScope.launch {
                                manager.notifyDnsCacheInfo()
                                lastDnsCacheNotificationTime = now
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error: ${e.message}", e)
                    delay(30000)
                }
            }
        }
    }

    /**
     * Initialize XrayRunner based on instance count.
     */
    private fun initializeXrayRunner() {
        val instanceCount = session.prefs.xrayCoreInstanceCount
        session.xrayRunner = createXrayRunner(instanceCount)
        Log.d(TAG, "XrayRunner initialized: ${if (instanceCount >= 1) "MultiInstance" else "Legacy"} (instanceCount=$instanceCount)")
    }

    /**
     * Create XrayRunner instance based on instance count.
     * 
     * CRITICAL: Use MultiInstanceXrayRunner for instanceCount >= 1 to maintain consistency.
     * Even for single instance, MultiXrayCoreManager provides better lifecycle management.
     */
    private fun createXrayRunner(instanceCount: Int): XrayRunner {
        val context = createXrayRunnerContext()
        return if (instanceCount >= 1) {
            MultiInstanceXrayRunner(context)
        } else {
            // Fallback to Legacy only if invalid count (should not happen)
            Log.w(TAG, "Invalid instance count: $instanceCount, using LegacyXrayRunner as fallback")
            LegacyXrayRunner(context)
        }
    }
    
    /**
     * Create XrayRunnerContext implementation.
     */
    private fun createXrayRunnerContext(): XrayRunnerContext {
        return object : XrayRunnerContext {
            override val context: Context = this@TProxyService
            override val serviceScope: CoroutineScope = this@TProxyService.serviceScope
            override val handler: Handler = this@TProxyService.handler
            override val prefs: Preferences = this@TProxyService.session.prefs
            override val logFileManager: LogFileManager = this@TProxyService.session.logFileManager
            override val xrayLogHandler: com.hyperxray.an.service.managers.XrayLogHandler = this@TProxyService.xrayLogHandler
            override val telegramNotificationManager: TelegramNotificationManager? = this@TProxyService.session.telegramNotificationManager
            
            override var xrayProcess: Process?
                get() = this@TProxyService.session.xrayProcess
                set(value) { this@TProxyService.session.xrayProcess = value }
            override var multiXrayCoreManager: MultiXrayCoreManager?
                get() = this@TProxyService.session.multiXrayCoreManager
                set(value) { this@TProxyService.session.multiXrayCoreManager = value }
            
            override fun isStopping(): Boolean = this@TProxyService.session.isStopping
            override fun isStarting(): Boolean = this@TProxyService.session.isStarting
            override fun setStarting(value: Boolean) { this@TProxyService.session.isStarting = value }
            override fun setStopping(value: Boolean) { this@TProxyService.session.isStopping = value }
            override fun isReloadingRequested(): Boolean = this@TProxyService.session.reloadingRequested
            override fun setReloadingRequested(value: Boolean) { this@TProxyService.session.reloadingRequested = value }
            override fun isSocks5ReadinessChecked(): Boolean = this@TProxyService.session.socks5ReadinessChecked
            override fun setSocks5ReadinessChecked(value: Boolean) { this@TProxyService.session.socks5ReadinessChecked = value }
            
            override fun getSystemDnsCacheServer(): com.hyperxray.an.core.network.dns.SystemDnsCacheServer? = this@TProxyService.session.systemDnsCacheServer
            override fun isDnsCacheInitialized(): Boolean = this@TProxyService.session.dnsCacheInitialized
            override fun setDnsCacheInitialized(value: Boolean) { this@TProxyService.session.dnsCacheInitialized = value }
            override fun getLogBroadcastChannel(): kotlinx.coroutines.channels.SendChannel<String>? = 
                this@TProxyService.xrayLogHandler.getLogBroadcastChannel()
            override suspend fun checkSocks5Readiness(prefs: Preferences) = this@TProxyService.checkSocks5Readiness(prefs)
            
            override fun stopXray(reason: String?) = this@TProxyService.stopVpn(reason)
            
            override suspend fun runXrayProcess() = this@TProxyService.runXrayProcess()
            override fun sendBroadcast(intent: Intent) = this@TProxyService.sendBroadcast(intent)
            override fun getApplicationPackageName(): String = this@TProxyService.application.packageName
            override fun getNativeLibraryDir(): String? = TProxyService.getNativeLibraryDir(this@TProxyService)
            
            override fun getActionError(): String = TProxyService.ACTION_ERROR
            override fun getActionInstanceStatusUpdate(): String = TProxyService.ACTION_INSTANCE_STATUS_UPDATE
            override fun getActionSocks5Ready(): String = TProxyService.ACTION_SOCKS5_READY
            override fun getExtraErrorMessage(): String = TProxyService.EXTRA_ERROR_MESSAGE
        }
    }
    
    /**
     * Initiates a complete VPN session.
     * 
     * Orchestrator flow:
     * 1. Establish VPN interface (TunInterfaceManager)
     * 2. Start Xray-core process (XrayRunner)
     * 3. Start native TProxy service (HevSocksManager) - triggered via SOCKS5 readiness callback
     * 
     * If any step fails, the service is immediately stopped to prevent zombie state.
     */
    fun initiateVpnSession() {
        val sessionStartTime = System.currentTimeMillis()
        AiLogHelper.i(TAG, "🚀 VPN SESSION START: initiateVpnSession() called")
        
        // CRITICAL: Check if service is properly initialized before starting
        if (!session.isNotificationManagerInitialized()) {
            val errorMsg = "Service not fully initialized - notificationManager not ready"
            Log.e(TAG, errorMsg)
            AiLogHelper.e(TAG, "❌ VPN SESSION START: $errorMsg")
            handleStartupError(errorMsg)
            return
        }
        
        if (session.isStarting) {
            Log.d(TAG, "initiateVpnSession() already in progress, ignoring duplicate call")
            AiLogHelper.w(TAG, "⚠️ VPN SESSION START: Already in progress, ignoring duplicate call")
            return
        }
        
        if (session.isStopping) {
            Log.w(TAG, "initiateVpnSession() called while stopping, ignoring")
            AiLogHelper.w(TAG, "⚠️ VPN SESSION START: Called while stopping, ignoring")
            return
        }
        
        session.isStarting = true
        
        try {
            resetSessionState()
            TProxyUtils.ensureXrayDirectories(this)
            AiLogHelper.d(TAG, "🔧 VPN SESSION START: Session state reset, Xray directories ensured")
            
            serviceScope.launch {
                try {
                    // Step 1: Establish VPN interface
                    val step1StartTime = System.currentTimeMillis()
                    AiLogHelper.i(TAG, "🔧 VPN SESSION STEP 1: Establishing VPN interface...")
                    if (!establishVpnInterface()) {
                        val errorMsg = "VPN interface establishment failed. Cannot proceed with Xray process."
                        AiLogHelper.e(TAG, "❌ VPN SESSION STEP 1 FAILED: $errorMsg")
                        handleStartupError(errorMsg)
                        return@launch
                    }
                    val step1Duration = System.currentTimeMillis() - step1StartTime
                    AiLogHelper.i(TAG, "✅ VPN SESSION STEP 1 COMPLETED: VPN interface established (duration: ${step1Duration}ms)")
                    
                    // Step 2: Start Xray-core process
                    val step2StartTime = System.currentTimeMillis()
                    AiLogHelper.i(TAG, "🔧 VPN SESSION STEP 2: Starting Xray-core process...")
                    if (!startXrayProcess()) {
                        val errorMsg = "Failed to start Xray process."
                        AiLogHelper.e(TAG, "❌ VPN SESSION STEP 2 FAILED: $errorMsg")
                        handleStartupError(errorMsg)
                        return@launch
                    }
                    val step2Duration = System.currentTimeMillis() - step2StartTime
                    AiLogHelper.i(TAG, "✅ VPN SESSION STEP 2 COMPLETED: Xray-core process started (duration: ${step2Duration}ms)")
                    
                    // Step 3: Native TProxy will start automatically when SOCKS5 becomes ready
                    // (triggered via checkSocks5Readiness callback -> startNativeTProxy)
                    AiLogHelper.i(TAG, "⏳ VPN SESSION STEP 3: Waiting for SOCKS5 readiness (Native TProxy will start automatically)")
                    
                    val totalDuration = System.currentTimeMillis() - sessionStartTime
                    AiLogHelper.i(TAG, "✅ VPN SESSION START SUCCESS: All steps completed (total duration: ${totalDuration}ms)")
                    
                } catch (e: Exception) {
                    val errorMsg = "Error in VPN/Xray startup sequence: ${e.message}"
                    Log.e(TAG, errorMsg, e)
                    AiLogHelper.e(TAG, "❌ VPN SESSION START FAILED: $errorMsg", e)
                    handleStartupError("Error starting VPN/Xray: ${e.message}")
                } finally {
                    session.isStarting = false
                }
            }
        } catch (e: Exception) {
            val errorMsg = "Error in initiateVpnSession(): ${e.message}"
            Log.e(TAG, errorMsg, e)
            AiLogHelper.e(TAG, "❌ VPN SESSION START ERROR: $errorMsg", e)
            session.isStarting = false
            // Don't stop service immediately - let UseCase handle retry/disconnect
            handleStartupError(errorMsg)
        }
    }

    /**
     * Reset session state for fresh connection.
     */
    private fun resetSessionState() {
        session.udpErrorCount = 0
        session.lastUdpErrorTime = 0L
        session.serviceStartTime = System.currentTimeMillis()
        session.udpRecoveryAttempts = 0
        session.lastUdpRecoveryTime = 0L
        synchronized(session.udpErrorHistory) {
            session.udpErrorHistory.clear()
        }
    }

    /**
     * Handle startup error by broadcasting error.
     * 
     * CRITICAL: Do not immediately stop service - allow retry mechanism to handle it.
     * Only stop service if it's a critical unrecoverable error.
     */
    private fun handleStartupError(errorMessage: String) {
        Log.e(TAG, "Startup error: $errorMessage")
        AiLogHelper.e(TAG, "❌ STARTUP ERROR: $errorMessage")
        
        // Broadcast error to notify UI/UseCase
        val errorIntent = Intent(ACTION_ERROR)
        errorIntent.setPackage(application.packageName)
        errorIntent.putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
        sendBroadcast(errorIntent)
        
        // Reset starting flag to allow retry
        session.isStarting = false
        
        // Only stop service if it's a critical error that prevents any retry
        // For most errors, let the UseCase handle retry/disconnect logic
        // Don't call stopSelf() here - let the connection process handle cleanup
    }

    /**
     * Establish VPN interface using TunInterfaceManager.
     */
    private suspend fun establishVpnInterface(): Boolean {
        val establishStartTime = System.currentTimeMillis()
        if (session.tunInterfaceManager.isEstablished()) {
            Log.d(TAG, "VPN interface already established")
            AiLogHelper.d(TAG, "✅ VPN INTERFACE: Already established")
            return true
        }

        Log.d(TAG, "Establishing VPN interface via TunInterfaceManager...")
        AiLogHelper.i(TAG, "🔧 VPN INTERFACE: Establishing VPN interface via TunInterfaceManager...")
        val newTunFd = session.tunInterfaceManager.establish(
            prefs = session.prefs,
            sessionState = session,
            context = this,
            serviceScope = serviceScope,
            onError = { errorMessage ->
                session.telegramNotificationManager?.let { manager ->
                    serviceScope.launch {
                        manager.notifyError("VPN setup failed\n\n$errorMessage")
                    }
                }
                val errorIntent = Intent(ACTION_ERROR)
                errorIntent.setPackage(application.packageName)
                errorIntent.putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
                sendBroadcast(errorIntent)
            }
        )
        
        synchronized(session.tunFdLock) {
            session.tunFd = newTunFd
        }

        if (newTunFd == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            AiLogHelper.e(TAG, "❌ VPN INTERFACE: Failed to establish VPN interface")
            return false
        }

        val establishDuration = System.currentTimeMillis() - establishStartTime
        Log.d(TAG, "VPN interface established successfully")
        AiLogHelper.i(TAG, "✅ VPN INTERFACE: VPN interface established successfully (duration: ${establishDuration}ms, fd: ${newTunFd.fd})")
        return true
    }

    /**
     * Start Xray-core process using XrayRunner.
     */
    /**
     * Run Xray process (for core-only mode, without VPN interface).
     */
    suspend fun runXrayProcess() {
        startXrayProcess()
    }
    
    private suspend fun startXrayProcess(): Boolean {
        val startTime = System.currentTimeMillis()
        AiLogHelper.i(TAG, "🚀 XRAY PROCESS START: Starting Xray process")
        
        val selectedConfigPath = session.prefs.selectedConfigPath
            ?: return handleConfigError("No configuration file selected.").also {
                AiLogHelper.e(TAG, "❌ XRAY PROCESS START: No configuration file selected")
            }
        AiLogHelper.d(TAG, "✅ XRAY PROCESS START: Config path found: $selectedConfigPath")
        
        val configFile = TProxyUtils.validateConfigPath(this, selectedConfigPath)
            ?: return handleConfigError("Invalid configuration file: path validation failed or file not accessible.").also {
                AiLogHelper.e(TAG, "❌ XRAY PROCESS START: Invalid configuration file: path validation failed")
            }
        AiLogHelper.d(TAG, "✅ XRAY PROCESS START: Config file validated: ${configFile.name} (${configFile.length()} bytes)")
        
        val configContent = TProxyUtils.readConfigContentSecurely(configFile)
            ?: return handleConfigError("Failed to read configuration file.").also {
                AiLogHelper.e(TAG, "❌ XRAY PROCESS START: Failed to read configuration file")
            }
        AiLogHelper.d(TAG, "✅ XRAY PROCESS START: Config content read: ${configContent.length} bytes")
        
        val instanceCount = session.prefs.xrayCoreInstanceCount
        AiLogHelper.i(TAG, "📊 XRAY PROCESS START: Instance count: $instanceCount")
        
        // Check if current xrayRunner is compatible with instance count
        // If not, recreate it with the correct type
        // IMPORTANT: Only recreate if runner is NOT running to avoid disrupting active connection
        val currentRunner = session.xrayRunner
        // CRITICAL: Use MultiInstanceXrayRunner for instanceCount >= 1
        val needsMultiInstance = instanceCount >= 1
        val isCurrentMultiInstance = currentRunner is MultiInstanceXrayRunner
        val isCurrentLegacy = currentRunner is LegacyXrayRunner
        val isRunnerRunning = currentRunner?.isRunning() == true
        
        // Recreate runner if:
        // 1. Current runner is null (shouldn't happen, but handle it)
        // 2. Instance count >= 1 but current runner is Legacy AND runner is NOT running
        // 3. Instance count < 1 but current runner is MultiInstance AND runner is NOT running (should not happen)
        // 
        // If runner is running, we cannot safely recreate it without disrupting the connection.
        // In this case, log a warning and continue with the existing runner.
        // The runner will be recreated on the next service restart.
        if (currentRunner == null) {
            Log.d(TAG, "xrayRunner is null, creating new runner (instanceCount=$instanceCount)")
            session.xrayRunner = createXrayRunner(instanceCount)
            Log.d(TAG, "xrayRunner created: ${if (needsMultiInstance) "MultiInstance" else "Legacy"} (instanceCount=$instanceCount)")
        } else if ((needsMultiInstance && isCurrentLegacy) || (!needsMultiInstance && isCurrentMultiInstance)) {
            if (isRunnerRunning) {
                // Runner is running, cannot safely recreate without disrupting connection
                Log.w(TAG, "Instance count mismatch detected (current=$instanceCount, runner=${currentRunner.javaClass.simpleName}), " +
                        "but runner is running. Cannot recreate without disrupting connection. " +
                        "Runner will be recreated on next service restart. " +
                        "Consider stopping and restarting the service to apply instance count change.")
                // Continue with existing runner - it will work but may not be optimal
            } else {
                // Runner is not running, safe to recreate
                Log.w(TAG, "Instance count changed (current=$instanceCount), recreating xrayRunner. " +
                        "Current type: ${currentRunner.javaClass.simpleName}, " +
                        "Required type: ${if (needsMultiInstance) "MultiInstance" else "Legacy"}")
                
                // Recreate runner with correct type
                session.xrayRunner = createXrayRunner(instanceCount)
                Log.d(TAG, "xrayRunner recreated: ${if (needsMultiInstance) "MultiInstance" else "Legacy"} (instanceCount=$instanceCount)")
            }
        }
        
        val config = XrayConfig(
            configFile = configFile,
            configContent = configContent,
            instanceCount = instanceCount
        )
        
        session.currentConfig = config
        AiLogHelper.d(TAG, "🔧 XRAY PROCESS START: Starting XrayRunner with config...")
        session.xrayRunner?.start(config)
        val duration = System.currentTimeMillis() - startTime
        AiLogHelper.i(TAG, "✅ XRAY PROCESS START: XrayRunner started successfully (duration: ${duration}ms)")
        return true
    }

    /**
     * Handle configuration error.
     */
    private fun handleConfigError(errorMessage: String): Boolean {
        Log.e(TAG, errorMessage)
        val errorIntent = Intent(ACTION_ERROR)
        errorIntent.setPackage(application.packageName)
        errorIntent.putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
        sendBroadcast(errorIntent)
        return false
    }

    /**
     * Stop VPN session.
     * 
     * Orchestrator flow:
     * 1. Stop native TProxy service (HevSocksManager)
     * 2. Stop Xray-core process (XrayRunner)
     * 3. Close VPN interface (TunInterfaceManager)
     */
    fun stopVpn(reason: String? = null) {
        val stopStartTime = System.currentTimeMillis()
        val reasonMsg = reason ?: "No reason provided"
        AiLogHelper.i(TAG, "🛑 VPN STOP: stopVpn() called. Reason: $reasonMsg")
        
        if (session.isStopping) {
            Log.d(TAG, "stopVpn already in progress, ignoring duplicate call. Reason: $reason")
            AiLogHelper.w(TAG, "⚠️ VPN STOP: Already in progress, ignoring duplicate call. Reason: $reason")
            return
        }
        session.isStopping = true

        Log.i(TAG, "stopVpn() called. Reason: $reasonMsg")

        session.socks5ReadinessJob?.cancel()
        session.socks5ReadinessJob = null
        Socks5ReadinessChecker.setSocks5Ready(false)
        session.socks5ReadinessChecked = false
        AiLogHelper.d(TAG, "🔧 VPN STOP: SOCKS5 readiness job cancelled, readiness flags reset")

        try {
            // Step 1: Stop native TProxy service first
            val step1StartTime = System.currentTimeMillis()
            AiLogHelper.i(TAG, "🔧 VPN STOP STEP 1: Stopping native TProxy service...")
            try {
                Thread.sleep(500) // Allow in-flight UDP packets to finish
                hevSocksManager.stopNativeTProxy(waitForUdpCleanup = true, udpCleanupDelayMs = 1000L)
                val step1Duration = System.currentTimeMillis() - step1StartTime
                AiLogHelper.i(TAG, "✅ VPN STOP STEP 1 COMPLETED: Native TProxy service stopped (duration: ${step1Duration}ms)")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping native TProxy service", e)
                AiLogHelper.e(TAG, "❌ VPN STOP STEP 1 ERROR: Error stopping native TProxy service: ${e.message}", e)
            }

            // Step 2: Stop XrayRunner
            val step2StartTime = System.currentTimeMillis()
            AiLogHelper.i(TAG, "🔧 VPN STOP STEP 2: Stopping XrayRunner...")
            if (session.xrayRunner != null) {
                try {
                    runBlocking {
                        session.xrayRunner?.stop()
                    }
                    val step2Duration = System.currentTimeMillis() - step2StartTime
                    AiLogHelper.i(TAG, "✅ VPN STOP STEP 2 COMPLETED: XrayRunner stopped (duration: ${step2Duration}ms)")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping XrayRunner", e)
                    AiLogHelper.e(TAG, "❌ VPN STOP STEP 2 ERROR: Error stopping XrayRunner: ${e.message}", e)
                }
            } else {
                AiLogHelper.w(TAG, "⚠️ VPN STOP STEP 2: XrayRunner is null, skipping")
            }

            // Step 3: Cancel coroutine scope
            AiLogHelper.d(TAG, "🔧 VPN STOP STEP 3: Cancelling coroutine scope...")
            serviceScope.cancel()
            AiLogHelper.i(TAG, "✅ VPN STOP STEP 3 COMPLETED: Coroutine scope cancelled")

            // Step 4: Clear references
            AiLogHelper.d(TAG, "🔧 VPN STOP STEP 4: Clearing references...")
            session.xrayProcess = null
            session.currentConfig = null
            AiLogHelper.i(TAG, "✅ VPN STOP STEP 4 COMPLETED: References cleared")

            // Step 5: Close VPN interface
            val step5StartTime = System.currentTimeMillis()
            AiLogHelper.i(TAG, "🔧 VPN STOP STEP 5: Closing VPN interface...")
            stopService()
            val step5Duration = System.currentTimeMillis() - step5StartTime
            AiLogHelper.i(TAG, "✅ VPN STOP STEP 5 COMPLETED: VPN interface closed (duration: ${step5Duration}ms)")

        } catch (e: Exception) {
            Log.e(TAG, "Error during stopVpn cleanup", e)
            AiLogHelper.e(TAG, "❌ VPN STOP ERROR: Error during cleanup: ${e.message}", e)
        } finally {
            session.isStopping = false
            
            val stopIntent = Intent(ACTION_STOP)
            stopIntent.setPackage(application.packageName)
            sendBroadcast(stopIntent)
            
            val totalDuration = System.currentTimeMillis() - stopStartTime
            Log.d(TAG, "stopVpn cleanup completed.")
            AiLogHelper.i(TAG, "✅ VPN STOP SUCCESS: Cleanup completed (total duration: ${totalDuration}ms)")
        }
    }

    /**
     * Stop service and close VPN interface.
     */
    private fun stopService() {
        Log.d(TAG, "stopService called, cleaning up VPN resources.")

        session.systemDnsCacheServer?.setVpnInterfaceIp(null)
        session.systemDnsCacheServer?.clearSocks5Proxy()
        session.systemDnsCacheServer?.stop()
        session.systemDnsCacheServer = null
        
        session.tproxyAiOptimizer?.stopOptimization()

        if (session.tunInterfaceManager.isEstablished()) {
            try {
                hevSocksManager.stopNativeTProxy(waitForUdpCleanup = true, udpCleanupDelayMs = 200L)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping native TProxy service", e)
            }
        }

        try {
            Thread.sleep(100) // Small delay before closing TUN fd
            session.tunInterfaceManager.closeTunFd(session)
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TUN file descriptor", e)
        }
        
        try {
            // Defensive check: Verify notificationManager is initialized before use
            if (session.isNotificationManagerInitialized()) {
                session.notificationManager.stopForeground(removeNotification = true)
            } else {
                Log.w(TAG, "⚠️ stopService: notificationManager not initialized, skipping stopForeground")
            }
        } catch (e: UninitializedPropertyAccessException) {
            Log.w(TAG, "⚠️ stopService: notificationManager accessed before initialization, skipping", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground service", e)
        }
        
        TProxyUtils.exit(
            context = this,
            telegramNotificationManager = session.telegramNotificationManager,
            serviceScope = serviceScope,
            stopSelf = { stopSelf() }
        )
    }

    /**
     * Start native TProxy service.
     * Called when SOCKS5 becomes ready.
     */
    private suspend fun startNativeTProxy() {
        val success = hevSocksManager.startNativeTProxy(
            tunInterfaceManager = session.tunInterfaceManager,
            isStoppingCallback = { session.isStopping },
            stopXrayCallback = { reason -> stopVpn(reason) },
            serviceScope = serviceScope,
            tproxyAiOptimizer = session.tproxyAiOptimizer,
            coreStatsState = session.coreStatsState,
            lastTProxyRestartTime = { session.lastTProxyRestartTime },
            setLastTProxyRestartTime = { time -> session.lastTProxyRestartTime = time },
            onStartComplete = {
                val successIntent = Intent(ACTION_START)
                successIntent.setPackage(application.packageName)
                sendBroadcast(successIntent)
                
                // Defensive check: Verify notificationManager is initialized before use
                if (session.isNotificationManagerInitialized()) {
                    try {
                        session.notificationManager.initNotificationChannel("socks5", "VPN Service")
                        session.notificationManager.createNotification(
                            channelName = "socks5",
                            contentTitle = getString(R.string.app_name),
                            contentText = "Connected"
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating notification in onStartComplete: ${e.message}", e)
                    }
                } else {
                    Log.w(TAG, "⚠️ startNativeTProxy: notificationManager not initialized, skipping notification update")
                }
            },
            notificationManager = session.notificationManager
        )
        
        if (!success) {
            Log.e(TAG, "Failed to start native TProxy service")
        }
    }

    /**
     * Check SOCKS5 readiness and start TProxy when ready.
     */
    private suspend fun checkSocks5Readiness(prefs: Preferences) {
        if (session.isStopping) {
            Log.d(TAG, "Service is stopping, skipping SOCKS5 readiness check")
            return
        }
        
        Log.i(TAG, "checkSocks5Readiness called for ${prefs.socksAddress}:${prefs.socksPort}")
        try {
            hevSocksManager.checkSocks5Readiness(
                prefs = prefs,
                serviceScope = serviceScope,
                socks5ReadinessChecked = session.socks5ReadinessChecked,
                onSocks5StatusChanged = { checked, wasChecked ->
                    if (!session.isStopping) {
                        Log.d(TAG, "SOCKS5 status changed: checked=$checked, wasChecked=$wasChecked")
                        session.socks5ReadinessChecked = checked
                        if (checked) {
                            // Broadcast SOCKS5 ready event
                            try {
                                val readyIntent = Intent(ACTION_SOCKS5_READY)
                                readyIntent.setPackage(application.packageName)
                                readyIntent.putExtra("socks_address", prefs.socksAddress)
                                readyIntent.putExtra("socks_port", prefs.socksPort)
                                readyIntent.putExtra("is_ready", true)
                                sendBroadcast(readyIntent)
                                Socks5ReadinessChecker.setSocks5Ready(true)
                                Log.i(TAG, "✅ SOCKS5 is ready, broadcasting ACTION_SOCKS5_READY")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error broadcasting SOCKS5 ready event: ${e.message}", e)
                            }
                        } else {
                            // SOCKS5 became unavailable
                            Log.w(TAG, "⚠️ SOCKS5 became unavailable, updating state")
                            Socks5ReadinessChecker.setSocks5Ready(false)
                        }
                    } else {
                        Log.d(TAG, "Service is stopping, ignoring SOCKS5 status change")
                    }
                },
                systemDnsCacheServer = session.systemDnsCacheServer,
                startNativeTProxyCallback = { 
                    if (!session.isStopping) {
                        Log.d(TAG, "SOCKS5 ready, starting native TProxy...")
                        try {
                            startNativeTProxy()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting native TProxy: ${e.message}", e)
                            // Don't throw - allow retry on next check
                        }
                    } else {
                        Log.d(TAG, "Service is stopping, skipping native TProxy start")
                    }
                }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Job cancellation is expected when service is stopping
            Log.d(TAG, "SOCKS5 readiness check cancelled (service stopping)")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkSocks5Readiness: ${e.message}", e)
            // Don't throw - allow retry on next check
        }
    }

    /**
     * Update core stats state for AI optimizer.
     */
    fun updateCoreStatsState(stats: CoreStatsState) {
        session.coreStatsState = stats
        
        TProxyUtils.broadcastInstanceStatus(this, session.multiXrayCoreManager)
        
        val totalBytes = stats.uplink + stats.downlink
        val now = System.currentTimeMillis()
        val PERFORMANCE_NOTIFICATION_INTERVAL_MS = 5L * 60L * 1000L
        
        if (totalBytes > 10 * 1024 * 1024 && 
            (session.lastPerformanceNotificationTime == 0L || 
             (now - session.lastPerformanceNotificationTime) >= PERFORMANCE_NOTIFICATION_INTERVAL_MS)) {
            
            session.telegramNotificationManager?.let { manager ->
                serviceScope.launch {
                    manager.notifyPerformanceMetrics(stats)
                    session.lastPerformanceNotificationTime = now
                }
            }
        }
    }
    
    /**
     * Get AI optimizer instance (for testing/debugging).
     */
    fun getAiOptimizer(): com.hyperxray.an.telemetry.TProxyAiOptimizer? {
        return session.tproxyAiOptimizer
    }

    /**
     * Get UDP error pattern for telemetry collection.
     */
    fun getUdpErrorPatternForTelemetry(): com.hyperxray.an.service.utils.TProxyUtils.UdpErrorPattern {
        return TProxyUtils.analyzeUdpErrorPattern(session.udpErrorHistory, session.lastUdpErrorTime)
    }

    /**
     * Get UDP error pattern for external monitoring.
     */
    fun getUdpErrorPattern(): com.hyperxray.an.service.utils.TProxyUtils.UdpErrorPattern {
        return TProxyUtils.analyzeUdpErrorPattern(session.udpErrorHistory, session.lastUdpErrorTime)
    }

    /**
     * Cleanup resources in onDestroy.
     */
    private fun cleanupResources() {
        session.systemDnsCacheServer?.clearSocks5Proxy()
        session.systemDnsCacheServer?.stop()
        session.systemDnsCacheServer = null
        
        session.heartbeatJob?.cancel()
        session.heartbeatJob = null
        
        try {
            session.wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            session.wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock: ${e.message}", e)
        }
    }

    /**
     * Cleanup session state in onDestroy.
     */
    private fun cleanupSessionState() {
        session.tproxyAiOptimizer = null
        session.telegramNotificationManager = null
        
        session.multiXrayCoreManager?.cleanup()
        session.multiXrayCoreManager = null
        MultiXrayCoreManager.resetInstance()
    }
    
    /**
     * UDP error categories for tracking and analysis.
     */
    enum class UdpErrorCategory {
        IDLE_TIMEOUT,
        SHUTDOWN,
        NORMAL_OPERATION,
        UNKNOWN
    }
    
    /**
     * UDP error tracking data class.
     */
    data class UdpErrorRecord(
        val timestamp: Long,
        val category: UdpErrorCategory,
        val logEntry: String,
        val context: UdpErrorContext
    )
    
    /**
     * Context information for UDP errors.
     */
    data class UdpErrorContext(
        val isShuttingDown: Boolean,
        val serviceUptime: Long,
        val timeSinceLastError: Long,
        val errorCountInWindow: Int
    )
    
    /**
     * UDP statistics from native tunnel.
     */
    data class UdpStats(
        val txPackets: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val rxBytes: Long,
        val timestamp: Long = System.currentTimeMillis()
    )
    
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
        const val BROADCAST_DELAY_MS: Long = 1000
        const val BROADCAST_BUFFER_SIZE_THRESHOLD: Int = 100

        init {
            // Load native library to ensure JNI methods are available
            // The library is also loaded in HevSocksManager, but we need it here
            // for TProxyService native method registration
            try {
                System.loadLibrary("hev-socks5-tunnel")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Failed to load hev-socks5-tunnel library: ${e.message}")
            }
        }

        /**
         * JNI native method for native code compatibility.
         * Native code (hev-socks5-tunnel.so) expects this method in TProxyService class.
         * This method delegates to HevSocksManager.TProxyStartService implementation.
         * 
         * Note: The actual native implementation is in HevSocksManager, but native code
         * tries to register this method in TProxyService class via JNI RegisterNatives.
         */
        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyStartService(configPath: String, fd: Int)

        /**
         * JNI native method for native code compatibility.
         * Native code (hev-socks5-tunnel.so) expects this method in TProxyService class.
         * This method delegates to HevSocksManager.TProxyStopService implementation.
         * 
         * Note: The actual native implementation is in HevSocksManager, but native code
         * tries to register this method in TProxyService class via JNI RegisterNatives.
         */
        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyStopService()

        /**
         * JNI native method for native code compatibility.
         * Native code (hev-socks5-tunnel.so) expects this method in TProxyService class.
         * This method delegates to HevSocksManager.TProxyGetStats implementation.
         * 
         * Note: The actual native implementation is in HevSocksManager, but native code
         * tries to register this method in TProxyService class via JNI RegisterNatives.
         */
        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyGetStats(): LongArray?

        /**
         * JNI native method for native code compatibility.
         * Native code (hev-socks5-tunnel.so) expects this method in TProxyService class.
         * This method delegates to HevSocksManager.TProxyNotifyUdpError implementation.
         * 
         * Note: The actual native implementation is in HevSocksManager, but native code
         * tries to register this method in TProxyService class via JNI RegisterNatives.
         */
        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyNotifyUdpError(errorType: Int): Boolean

        /**
         * JNI native method for native code compatibility.
         * Native code (hev-socks5-tunnel.so) expects this method in TProxyService class.
         * This method delegates to HevSocksManager.TProxyNotifyUdpRecoveryComplete implementation.
         * 
         * Note: The actual native implementation is in HevSocksManager, but native code
         * tries to register this method in TProxyService class via JNI RegisterNatives.
         */
        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyNotifyUdpRecoveryComplete(): Boolean

        /**
         * JNI native method for native code compatibility.
         * Native code (hev-socks5-tunnel.so) expects this method in TProxyService class.
         * This method delegates to HevSocksManager.TProxyNotifyImminentUdpCleanup implementation.
         * 
         * Note: The actual native implementation is in HevSocksManager, but native code
         * tries to register this method in TProxyService class via JNI RegisterNatives.
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
}
