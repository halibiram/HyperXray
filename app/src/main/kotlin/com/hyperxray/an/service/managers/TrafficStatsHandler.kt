package com.hyperxray.an.service.managers

import android.content.Context
import android.util.Log
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.viewmodel.CoreStatsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Handles traffic stats polling and notification updates.
 * Polls CoreStatsState at adaptive intervals and updates notification with speed stats.
 */
class TrafficStatsHandler(
    private val serviceScope: CoroutineScope,
    private val context: Context
) {
    companion object {
        private const val TAG = "TrafficStatsHandler"
        private const val HIGH_TRAFFIC_THRESHOLD = 100_000.0 // 100 KB/s
        private const val LOW_TRAFFIC_THRESHOLD = 10_000.0 // 10 KB/s
        private const val DEFAULT_INTERVAL_MS = 60000L // 60 seconds
        private const val HIGH_TRAFFIC_INTERVAL_MS = 10000L // 10 seconds
        private const val LOW_TRAFFIC_INTERVAL_MS = 30000L // 30 seconds
    }

    private var pollingJob: Job? = null
    @Volatile
    private var currentStats: CoreStatsState? = null
    private var notificationManager: ServiceNotificationManager? = null

    /**
     * Start polling stats and updating notification.
     * 
     * @param initialStats Initial CoreStatsState (can be null)
     * @param notificationManager Notification manager for updates
     */
    fun startPolling(
        initialStats: CoreStatsState?,
        notificationManager: ServiceNotificationManager
    ) {
        if (pollingJob?.isActive == true) {
            Log.w(TAG, "Stats polling already active, stopping previous polling")
            stopPolling()
        }

        this.currentStats = initialStats
        this.notificationManager = notificationManager

        Log.d(TAG, "Starting traffic stats polling")
        pollingJob = serviceScope.launch {
            while (isActive) {
                try {
                    // Adaptive polling: adjust interval based on traffic
                    val interval = calculateAdaptivePollingInterval(currentStats)
                    delay(interval)

                    // Update notification with speed info if available
                    val stats = currentStats
                    val prefs = Preferences(context)
                    val currentChannelName = if (prefs.disableVpn) "nosocks" else "socks5"

                    if (stats != null && (stats.uplinkThroughput > 0 || stats.downlinkThroughput > 0)) {
                        // Convert bytes/sec to kbps
                        val uploadKbps = ((stats.uplinkThroughput * 8.0) / 1000.0).toLong()
                        val downloadKbps = ((stats.downlinkThroughput * 8.0) / 1000.0).toLong()
                        notificationManager.updateSpeed(uploadKbps, downloadKbps)
                    } else {
                        // No speed data available, just update with basic status
                        notificationManager.updateNotification(
                            context.getString(com.hyperxray.an.R.string.app_name),
                            "VPN service is running",
                            currentChannelName
                        )
                    }
                    Log.d(TAG, "Heartbeat: Service alive, notification updated")
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error: ${e.message}", e)
                    delay(30000) // Wait before retry
                }
            }
        }
    }

    /**
     * Stop polling stats.
     */
    fun stopPolling() {
        Log.d(TAG, "Stopping traffic stats polling")
        pollingJob?.cancel()
        pollingJob = null
        currentStats = null
        notificationManager = null
    }

    /**
     * Update current stats state.
     * 
     * @param stats New CoreStatsState
     */
    fun updateCoreStatsState(stats: CoreStatsState) {
        currentStats = stats
        Log.d(TAG, "Stats state updated: uplink=${stats.uplinkThroughput} bytes/s, downlink=${stats.downlinkThroughput} bytes/s")
    }

    /**
     * Calculates adaptive polling interval based on traffic state.
     * No traffic: 60 seconds
     * Low traffic: 30 seconds
     * High traffic: 10 seconds
     */
    private fun calculateAdaptivePollingInterval(stats: CoreStatsState?): Long {
        if (stats == null) {
            return DEFAULT_INTERVAL_MS // No stats available, use conservative interval
        }

        val totalThroughput = stats.uplinkThroughput + stats.downlinkThroughput

        return when {
            totalThroughput > HIGH_TRAFFIC_THRESHOLD -> HIGH_TRAFFIC_INTERVAL_MS // 10 seconds for high traffic
            totalThroughput > LOW_TRAFFIC_THRESHOLD -> LOW_TRAFFIC_INTERVAL_MS // 30 seconds for low traffic
            else -> DEFAULT_INTERVAL_MS // 60 seconds for no/low traffic
        }
    }
}

