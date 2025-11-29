package com.hyperxray.an.vpn

import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.hyperxray.an.data.source.LogFileManager
import com.hyperxray.an.feature.dashboard.ConnectionState
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.service.managers.ServiceNotificationManager
import com.hyperxray.an.viewmodel.CoreStatsState
import com.hyperxray.an.notification.TelegramNotificationManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.Volatile

/**
 * Holds all session state for HyperVpnService.
 * Separates data (state) from logic (service operations).
 * 
 * Note: This is a simplified version for the native Go-based HyperVpnService.
 * SOCKS5, MultiInstanceXrayRunner, and SystemDnsCacheServer are NOT included
 * as they are not compatible with the native Go architecture.
 */
class HyperVpnSessionState {
    // Lateinit managers - initialized in onCreate()
    lateinit var logFileManager: LogFileManager
    lateinit var prefs: Preferences
    lateinit var notificationManager: ServiceNotificationManager

    // Service state flags
    @Volatile
    var reloadingRequested = false
    
    @Volatile
    var isRunning: Boolean = false
    
    @Volatile
    var isStopping = false
    
    @Volatile
    var isStarting = false
    
    // DNS cache integration (native Go handles this internally)
    @Volatile
    var dnsCacheInitialized = false

    // TUN file descriptor
    @Volatile
    var tunFd: ParcelFileDescriptor? = null
    val tunFdLock = Any() // Synchronization lock for tunFd access
    
    // Start time tracking
    @Volatile
    var startTime: Long = 0
    
    var coreStatsState: CoreStatsState? = null
    
    @Volatile
    var lastStatsUpdate: Long = 0L
    
    // WakeLock to prevent system from killing service
    var wakeLock: PowerManager.WakeLock? = null
    
    // Jobs
    var heartbeatJob: Job? = null
    var statsMonitoringJob: Job? = null
    
    // Connection state
    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    
    // Error recovery tracking
    var recoveryAttempts = 0
    var lastRecoveryTime: Long = 0L
    
    // Connection error tracking
    var errorCount = 0
    var lastErrorTime: Long = 0L
    
    // Telegram notification manager
    var telegramNotificationManager: TelegramNotificationManager? = null
    
    // Service start time tracking
    @Volatile
    var serviceStartTime = 0L
    
    // Performance tracking
    var lastPerformanceNotificationTime: Long = 0L
    var lastLatency: Double? = null
    
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
            notificationManager
            true
        } catch (e: kotlin.UninitializedPropertyAccessException) {
            false
        } catch (e: Exception) {
            // Any other exception means we can't determine, assume not initialized for safety
            false
        }
    }
    
    /**
     * Reset session state for fresh connection.
     */
    fun resetForNewConnection() {
        errorCount = 0
        lastErrorTime = 0L
        serviceStartTime = System.currentTimeMillis()
        recoveryAttempts = 0
        lastRecoveryTime = 0L
    }
    
    /**
     * Cleanup all resources with proper error handling.
     * This method ensures all resources are cleaned up even if individual cleanups fail.
     */
    fun cleanup() {
        try {
            // Cancel heartbeat job
            try {
                heartbeatJob?.cancel()
            } catch (e: Exception) {
                // Ignore cancellation errors
            } finally {
                heartbeatJob = null
            }

            // Cancel stats monitoring job
            try {
                statsMonitoringJob?.cancel()
            } catch (e: Exception) {
                // Ignore cancellation errors
            } finally {
                statsMonitoringJob = null
            }

            // Cleanup telegram notification manager
            telegramNotificationManager = null

            // Release wake lock
            try {
                wakeLock?.let {
                    if (it.isHeld) {
                        it.release()
                    }
                }
            } catch (e: Exception) {
                // Ignore wake lock release errors
            } finally {
                wakeLock = null
            }

            // Close TUN file descriptor
            synchronized(tunFdLock) {
                try {
                    tunFd?.close()
                } catch (e: Exception) {
                    // TUN fd might already be closed by native code
                } finally {
                    tunFd = null
                }
            }

            // Reset state flags
            isRunning = false
            isStopping = false
            isStarting = false
            startTime = 0L
            lastStatsUpdate = 0L

        } catch (e: Exception) {
            // Log any unexpected errors during cleanup
            // We don't rethrow to ensure cleanup completes
        }
    }
}


