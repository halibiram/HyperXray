package com.hyperxray.an.service.state

import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.hyperxray.an.data.source.LogFileManager
import com.hyperxray.an.feature.dashboard.ConnectionState
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.service.TProxyService
import com.hyperxray.an.service.managers.HevSocksManager
import com.hyperxray.an.service.managers.ServiceNotificationManager
import com.hyperxray.an.service.managers.TrafficStatsHandler
import com.hyperxray.an.service.managers.TunInterfaceManager
import com.hyperxray.an.service.managers.XrayCoreManager
import com.hyperxray.an.service.managers.XrayLogHandler
import com.hyperxray.an.service.xray.XrayConfig
import com.hyperxray.an.service.xray.XrayRunner
import com.hyperxray.an.telemetry.TProxyAiOptimizer
import com.hyperxray.an.viewmodel.CoreStatsState
import com.hyperxray.an.core.network.dns.SystemDnsCacheServer
import com.hyperxray.an.notification.TelegramNotificationManager
import com.hyperxray.an.xray.runtime.MultiXrayCoreManager
import com.hyperxray.an.xray.runtime.stats.CoreStatsClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.Volatile

/**
 * Holds all session state for TProxyService.
 * Separates data (state) from logic (service operations).
 */
class ServiceSessionState {
    // Lateinit managers - initialized in onCreate()
    lateinit var logFileManager: LogFileManager
    lateinit var prefs: Preferences
    lateinit var tunInterfaceManager: TunInterfaceManager
    lateinit var xrayCoreManager: XrayCoreManager
    lateinit var hevSocksManager: HevSocksManager
    lateinit var notificationManager: ServiceNotificationManager
    lateinit var logHandler: XrayLogHandler
    lateinit var trafficStatsHandler: TrafficStatsHandler

    // Service state flags
    @Volatile
    var reloadingRequested = false
    
    @Volatile
    var isRunning: Boolean = false
    
    @Volatile
    var isStopping = false
    
    @Volatile
    var isStarting = false
    
    // SOCKS5 readiness tracking
    @Volatile
    var socks5ReadinessChecked = false
    
    // DNS cache integration
    @Volatile
    var dnsCacheInitialized = false

    // Managers and objects
    var multiXrayCoreManager: MultiXrayCoreManager? = null
    
    // Xray process and runner
    @Volatile
    var xrayProcess: Process? = null
    
    var xrayRunner: XrayRunner? = null
    
    @Volatile
    var tunFd: ParcelFileDescriptor? = null
    val tunFdLock = Any() // Synchronization lock for tunFd access
    
    // Start time tracking
    @Volatile
    var startTime: Long = 0
    
    // Current configuration
    var currentConfig: XrayConfig? = null
    
    // AI-powered TProxy optimizer
    var tproxyAiOptimizer: TProxyAiOptimizer? = null
    var coreStatsState: CoreStatsState? = null
    var coreStatsClient: CoreStatsClient? = null
    
    @Volatile
    var lastClientCloseTime: Long = 0L
    
    // WakeLock to prevent system from killing service
    var wakeLock: PowerManager.WakeLock? = null
    
    // Jobs
    var heartbeatJob: Job? = null
    var socks5ReadinessJob: Job? = null
    var socks5PeriodicCheckJob: Job? = null
    
    // System DNS cache server
    var systemDnsCacheServer: SystemDnsCacheServer? = null
    
    // UDP monitoring
    var udpMonitoringJob: Job? = null
    var lastUdpStats: TProxyService.UdpStats? = null
    var lastUdpStatsTime: Long = 0L
    
    // Current UDP stats
    val udpStats = TProxyService.UdpStats(
        txPackets = 0L,
        txBytes = 0L,
        rxPackets = 0L,
        rxBytes = 0L,
        timestamp = 0L
    )
    
    // Connection state
    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    
    // UDP recovery mechanism
    var udpRecoveryAttempts = 0
    var lastUdpRecoveryTime: Long = 0L
    
    // Connection reset error tracking
    var connectionResetErrorCount = 0
    var lastConnectionResetTime: Long = 0L
    
    // TProxy restart throttling
    @Volatile
    var lastTProxyRestartTime: Long = 0L
    
    // Telegram notification manager
    var telegramNotificationManager: TelegramNotificationManager? = null
    
    // Enhanced UDP error tracking
    @Volatile
    var udpErrorCount = 0
    
    @Volatile
    var lastUdpErrorTime = 0L
    
    val udpErrorHistory = mutableListOf<com.hyperxray.an.service.utils.TProxyUtils.UdpErrorRecord>()
    val maxErrorHistorySize = 100
    
    @Volatile
    var serviceStartTime = 0L
    
    // Performance tracking
    var lastPerformanceNotificationTime: Long = 0L
    var lastLatency: Double? = null
}


