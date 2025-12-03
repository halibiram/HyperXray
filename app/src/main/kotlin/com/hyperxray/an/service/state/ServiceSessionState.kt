package com.hyperxray.an.service.state

import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.hyperxray.an.data.source.LogFileManager
import com.hyperxray.an.feature.dashboard.ConnectionState
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.service.managers.ServiceNotificationManager
import com.hyperxray.an.service.managers.TrafficStatsHandler
import com.hyperxray.an.service.managers.TunInterfaceManager
import com.hyperxray.an.service.managers.XrayLogHandler
import com.hyperxray.an.viewmodel.CoreStatsState
import com.hyperxray.an.notification.TelegramNotificationManager
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
    // TunInterfaceManager (legacy, not used in WireGuard over Xray - HyperVpnService manages TUN directly)
    lateinit var tunInterfaceManager: TunInterfaceManager
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
    
    // DNS cache integration
    @Volatile
    var dnsCacheInitialized = false

    // Managers and objects (legacy, not used in WireGuard over Xray)
    
    // Xray process (legacy, not used in WireGuard over Xray - native Go library handles this)
    @Volatile
    var xrayProcess: Process? = null
    
    @Volatile
    var tunFd: ParcelFileDescriptor? = null
    val tunFdLock = Any() // Synchronization lock for tunFd access
    
    // Start time tracking
    @Volatile
    var startTime: Long = 0
    
    // Current configuration (legacy, not used in WireGuard over Xray)
    // Config is managed directly by HyperVpnService via native library
    
    var coreStatsState: CoreStatsState? = null
    var coreStatsClient: CoreStatsClient? = null
    
    // WakeLock to prevent system from killing service
    var wakeLock: PowerManager.WakeLock? = null
    
    // Jobs
    var heartbeatJob: Job? = null
    
    // Connection state
    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    
    // Telegram notification manager
    var telegramNotificationManager: TelegramNotificationManager? = null
    
    @Volatile
    var serviceStartTime = 0L
    
    /**
     * Safely check if notificationManager is initialized.
     * Attempts to access the property and catches UninitializedPropertyAccessException
     * if the lateinit property has not been initialized.
     * 
     * @return true if notificationManager is initialized, false otherwise
     */
    fun isNotificationManagerInitialized(): Boolean {
        return try {
            // Access the property - this will throw UninitializedPropertyAccessException
            // if the lateinit property has not been initialized
            // If we reach here, the property is initialized
            notificationManager
            true
        } catch (e: kotlin.UninitializedPropertyAccessException) {
            false
        } catch (e: Exception) {
            // Any other exception means we can't determine, assume not initialized for safety
            false
        }
    }
}


