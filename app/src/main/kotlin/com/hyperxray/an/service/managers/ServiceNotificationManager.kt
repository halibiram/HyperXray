package com.hyperxray.an.service.managers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hyperxray.an.R
import com.hyperxray.an.activity.MainActivity

/**
 * Manages foreground service notifications.
 * Handles notification channel initialization, notification creation, and lifecycle.
 */
class ServiceNotificationManager(private val service: Service) {
    companion object {
        private const val TAG = "ServiceNotificationManager"
        private const val NOTIFICATION_ID = 1
    }
    
    // Current notification state
    private var currentChannelName: String = "socks5"
    private var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var profileName: String? = null
    private var uploadSpeedKbps: Long = 0
    private var downloadSpeedKbps: Long = 0
    
    private enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }
    
    /**
     * Initialize notification channel.
     * 
     * @param channelName Channel ID
     * @param name Channel display name
     */
    fun initNotificationChannel(channelName: String, name: CharSequence) {
        val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Use LOW importance to reduce notification sound/vibration but keep it visible
        // This helps prevent system from killing the service
        val channel = NotificationChannel(channelName, name, NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false) // Don't show badge
        channel.enableLights(false) // Don't use LED
        channel.enableVibration(false) // Don't vibrate
        channel.setSound(null, null) // No sound
        notificationManager.createNotificationChannel(channel)
    }
    
    /**
     * Create and show foreground notification.
     * 
     * @param channelName Notification channel ID
     * @param contentTitle Notification title
     * @param contentText Notification text
     */
    fun createNotification(
        channelName: String,
        contentTitle: String,
        contentText: String
    ) {
        val i = Intent(service, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            service, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Create persistent notification to prevent system from killing the service
        val notification = NotificationCompat.Builder(service, channelName)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentIntent(pi)
            .setOngoing(true) // Make notification persistent
            .setPriority(NotificationCompat.PRIORITY_HIGH) // High priority
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false) // Don't show timestamp
            .setOnlyAlertOnce(true) // Don't alert on updates
            .build()
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(NOTIFICATION_ID, notification)
        } else {
            service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        
        Log.d(TAG, "Foreground notification created and displayed")
    }
    
    /**
     * Start foreground service with notification.
     * 
     * @param notificationId Notification ID
     * @param notification Notification to display
     */
    fun startForeground(notificationId: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(notificationId, notification)
        } else {
            service.startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }
    
    /**
     * Stop foreground service.
     * 
     * @param removeNotification If true, removes the notification
     */
    fun stopForeground(removeNotification: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            service.stopForeground(removeNotification)
        }
    }
    
    /**
     * Show "Connecting..." notification.
     */
    fun showConnecting() {
        connectionState = ConnectionState.CONNECTING
        profileName = null
        updateNotificationInternal(
            service.getString(R.string.app_name),
            "Connecting...",
            currentChannelName
        )
    }
    
    /**
     * Show "Connected to [profile]" notification.
     * 
     * @param profileName Name of the connected profile
     */
    fun showConnected(profileName: String) {
        connectionState = ConnectionState.CONNECTED
        this.profileName = profileName
        updateNotificationInternal(
            service.getString(R.string.app_name),
            "Connected to $profileName",
            currentChannelName
        )
    }
    
    /**
     * Update notification with speed information.
     * 
     * @param uploadKbps Upload speed in kilobits per second
     * @param downloadKbps Download speed in kilobits per second
     */
    fun updateSpeed(uploadKbps: Long, downloadKbps: Long) {
        this.uploadSpeedKbps = uploadKbps
        this.downloadSpeedKbps = downloadKbps
        updateNotificationInternal(
            service.getString(R.string.app_name),
            buildNotificationText(),
            currentChannelName
        )
    }
    
    /**
     * Update notification with custom title and text.
     * 
     * @param title Notification title
     * @param text Notification text
     * @param channelName Notification channel ID
     */
    fun updateNotification(title: String, text: String, channelName: String) {
        currentChannelName = channelName
        updateNotificationInternal(title, text, channelName)
    }
    
    /**
     * Build notification text based on current state.
     */
    private fun buildNotificationText(): String {
        val stateText = when (connectionState) {
            ConnectionState.CONNECTING -> "Connecting..."
            ConnectionState.CONNECTED -> profileName?.let { "Connected to $it" } ?: "VPN service is running"
            ConnectionState.DISCONNECTED -> "VPN service is running"
        }
        
        if (uploadSpeedKbps > 0 || downloadSpeedKbps > 0) {
            val uploadStr = formatSpeed(uploadSpeedKbps)
            val downloadStr = formatSpeed(downloadSpeedKbps)
            return "$stateText\n↑ $uploadStr ↓ $downloadStr"
        }
        
        return stateText
    }
    
    /**
     * Format speed in appropriate units (KB/s or MB/s).
     */
    private fun formatSpeed(kbps: Long): String {
        val kbpsValue = kbps.toDouble()
        return when {
            kbpsValue >= 1024 -> String.format("%.2f MB/s", kbpsValue / 1024.0)
            kbpsValue > 0 -> String.format("%.2f KB/s", kbpsValue)
            else -> "0 KB/s"
        }
    }
    
    /**
     * Internal method to update notification.
     */
    private fun updateNotificationInternal(title: String, text: String, channelName: String) {
        val i = Intent(service, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            service, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = NotificationCompat.Builder(service, channelName)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .build()
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(NOTIFICATION_ID, notification)
        } else {
            service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        
        Log.d(TAG, "Notification updated: $title - $text")
    }
    
    /**
     * Reset notification state (e.g., on disconnect).
     */
    fun resetState() {
        connectionState = ConnectionState.DISCONNECTED
        profileName = null
        uploadSpeedKbps = 0
        downloadSpeedKbps = 0
    }
}

