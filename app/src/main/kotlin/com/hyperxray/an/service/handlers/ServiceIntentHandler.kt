package com.hyperxray.an.service.handlers

import android.content.Intent
import android.util.Log
import com.hyperxray.an.R
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.service.TProxyService
import com.hyperxray.an.service.state.ServiceSessionState
import kotlinx.coroutines.launch

/**
 * Handles all intent actions from TProxyService.onStartCommand.
 * Extracted from TProxyService to improve separation of concerns.
 */
class ServiceIntentHandler(
    private val session: ServiceSessionState,
    private val service: TProxyService,
    private val serviceScope: kotlinx.coroutines.CoroutineScope
) {
    
    companion object {
        private const val TAG = "ServiceIntentHandler"
        
        // Intent action constants (from TProxyService companion object)
        const val ACTION_DISCONNECT: String = "com.hyperxray.an.DISCONNECT"
        const val ACTION_RELOAD_CONFIG: String = "com.hyperxray.an.RELOAD_CONFIG"
        const val ACTION_START: String = "com.hyperxray.an.START"
        const val ACTION_ERROR: String = "com.hyperxray.an.ERROR"
        const val ACTION_STOP: String = "com.hyperxray.an.STOP"
        const val ACTION_LOG_UPDATE: String = "com.hyperxray.an.LOG_UPDATE"
        const val ACTION_SOCKS5_READY: String = "com.hyperxray.an.SOCKS5_READY"
        const val ACTION_INSTANCE_STATUS_UPDATE: String = "com.hyperxray.an.INSTANCE_STATUS_UPDATE"
        const val EXTRA_LOG_DATA: String = "log_data"
        const val EXTRA_ERROR_MESSAGE: String = "error_message"
        const val EXTRA_INSTANCE_STATUS: String = "instance_status"
    }
    
    /**
     * Handles intent action and returns the appropriate service start mode.
     * 
     * @param intent The intent received in onStartCommand
     * @return START_STICKY or START_NOT_STICKY
     */
    fun handleIntent(intent: Intent): Int {
        val action = intent.action
        return when (action) {
            ACTION_DISCONNECT -> handleDisconnect()
            ACTION_RELOAD_CONFIG -> handleReloadConfig()
            ACTION_START -> handleStart()
            else -> handleDefault()
        }
    }
    
    /**
     * Handles ACTION_DISCONNECT - stops the Xray service.
     */
    private fun handleDisconnect(): Int {
        service.stopVpn("User requested disconnect (ACTION_DISCONNECT)")
        return android.app.Service.START_NOT_STICKY
    }
    
    /**
     * Handles ACTION_RELOAD_CONFIG - reloads Xray configuration.
     */
    private fun handleReloadConfig(): Int {
        // Ensure notification is shown
        val channelName = if (session.prefs.disableVpn) "nosocks" else "socks5"
        session.notificationManager.initNotificationChannel(
            channelName,
            service.getString(R.string.app_name)
        )
        session.notificationManager.updateNotification(
            service.getString(R.string.app_name),
            "VPN service is running",
            channelName
        )
        
        val prefs = session.prefs
        if (prefs.disableVpn) {
            Log.d(TAG, "Received RELOAD_CONFIG action (core-only mode)")
            session.reloadingRequested = true
            session.xrayProcess?.destroy()
            serviceScope.launch {
                service.runXrayProcess()
            }
            return android.app.Service.START_STICKY
        }
        
        if (session.tunInterfaceManager.getTunFd() == null) {
            Log.w(TAG, "Cannot reload config, VPN service is not running.")
            return android.app.Service.START_STICKY
        }
        
        Log.d(TAG, "Received RELOAD_CONFIG action.")
            session.reloadingRequested = true
            session.xrayProcess?.destroy()
            serviceScope.launch {
                service.runXrayProcess()
            }
        return android.app.Service.START_STICKY
    }
    
    /**
     * Handles ACTION_START - starts the Xray service.
     */
    private fun handleStart(): Int {
        session.logFileManager.clearLogsSync()
        val prefs = session.prefs
        
        if (prefs.disableVpn) {
            serviceScope.launch {
                service.runXrayProcess()
            }
            
            val successIntent = Intent(ACTION_START)
            // Send Telegram notification
            session.telegramNotificationManager?.let { manager ->
                serviceScope.launch {
                    manager.notifyVpnStatus(true)
                }
            }
            successIntent.setPackage(service.application.packageName)
            service.sendBroadcast(successIntent)
            
            @Suppress("SameParameterValue")
            val channelName = "nosocks"
            session.notificationManager.initNotificationChannel(
                channelName,
                service.getString(R.string.app_name)
            )
            session.notificationManager.updateNotification(
                service.getString(R.string.app_name),
                "VPN service is running",
                channelName
            )
        } else {
            service.initiateVpnSession()
        }
        
        return android.app.Service.START_STICKY
    }
    
    /**
     * Handles default/unknown actions - starts the Xray service with default behavior.
     */
    private fun handleDefault(): Int {
        // Ensure notification is shown for any start
        val channelName = "socks5"
        session.notificationManager.initNotificationChannel(
            channelName,
            service.getString(R.string.app_name)
        )
        session.notificationManager.showConnecting()
        
        session.logFileManager.clearLogsSync()
        service.initiateVpnSession()
        return android.app.Service.START_STICKY
    }
}


