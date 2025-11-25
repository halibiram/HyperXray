package com.hyperxray.an.service.handlers

import android.content.Intent
import android.util.Log
import com.hyperxray.an.R
import com.hyperxray.an.common.AiLogHelper
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
        const val ACTION_CONNECT: String = "com.hyperxray.an.CONNECT"
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
        AiLogHelper.i(TAG, "📥 INTENT HANDLER: Received intent action: $action")
        return when (action) {
            ACTION_CONNECT, ACTION_START -> {
                // ACTION_CONNECT and ACTION_START are equivalent - both start the VPN service
                if (action == ACTION_CONNECT) {
                    AiLogHelper.i(TAG, "🔌 INTENT HANDLER: Handling ACTION_CONNECT (same as ACTION_START)")
                } else {
                    AiLogHelper.i(TAG, "🚀 INTENT HANDLER: Handling ACTION_START")
                }
                handleStart()
            }
            ACTION_DISCONNECT -> {
                AiLogHelper.i(TAG, "🔌 INTENT HANDLER: Handling ACTION_DISCONNECT")
                handleDisconnect()
            }
            ACTION_RELOAD_CONFIG -> {
                AiLogHelper.i(TAG, "🔄 INTENT HANDLER: Handling ACTION_RELOAD_CONFIG")
                handleReloadConfig()
            }
            else -> {
                AiLogHelper.d(TAG, "📋 INTENT HANDLER: Handling default/unknown action: $action")
                handleDefault()
            }
        }
    }
    
    /**
     * Handles ACTION_DISCONNECT - stops the Xray service.
     */
    private fun handleDisconnect(): Int {
        AiLogHelper.i(TAG, "🛑 INTENT DISCONNECT: User requested disconnect")
        service.stopVpn("User requested disconnect (ACTION_DISCONNECT)")
        return android.app.Service.START_NOT_STICKY
    }
    
    /**
     * Handles ACTION_RELOAD_CONFIG - reloads Xray configuration.
     */
    private fun handleReloadConfig(): Int {
        AiLogHelper.i(TAG, "🔄 INTENT RELOAD: Reloading Xray configuration")
        
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
            AiLogHelper.d(TAG, "🔄 INTENT RELOAD: Core-only mode, reloading Xray process")
            session.reloadingRequested = true
            session.xrayProcess?.destroy()
            serviceScope.launch {
                service.runXrayProcess()
            }
            return android.app.Service.START_STICKY
        }
        
        if (session.tunInterfaceManager.getTunFd() == null) {
            Log.w(TAG, "Cannot reload config, VPN service is not running.")
            AiLogHelper.w(TAG, "⚠️ INTENT RELOAD: Cannot reload config, VPN service is not running")
            return android.app.Service.START_STICKY
        }
        
        Log.d(TAG, "Received RELOAD_CONFIG action.")
        AiLogHelper.i(TAG, "🔄 INTENT RELOAD: Reloading Xray process (VPN mode)")
        session.reloadingRequested = true
        session.xrayProcess?.destroy()
        serviceScope.launch {
            service.runXrayProcess()
        }
        return android.app.Service.START_STICKY
    }
    
    /**
     * Handles ACTION_START - starts the Xray service.
     * 
     * CRITICAL: Ensures service is fully initialized before starting VPN session.
     */
    private fun handleStart(): Int {
        val startTime = System.currentTimeMillis()
        AiLogHelper.i(TAG, "🚀 INTENT START: Starting Xray service")
        
        // CRITICAL: Check if service is properly initialized
        if (!session.isNotificationManagerInitialized()) {
            val errorMsg = "Service not fully initialized - cannot start VPN session"
            Log.e(TAG, errorMsg)
            AiLogHelper.e(TAG, "❌ INTENT START: $errorMsg")
            // Delay and retry once - service might still be initializing
            serviceScope.launch {
                kotlinx.coroutines.delay(1000) // Wait 1 second for initialization
                if (session.isNotificationManagerInitialized()) {
                    AiLogHelper.i(TAG, "🔄 INTENT START: Service now initialized, initiating VPN session...")
                    // Retry by calling initiateVpnSession directly instead of recursive handleStart()
                    if (!session.prefs.disableVpn) {
                        service.initiateVpnSession()
                    }
                } else {
                    val errorIntent = Intent(ACTION_ERROR)
                    errorIntent.setPackage(service.application.packageName)
                    errorIntent.putExtra(EXTRA_ERROR_MESSAGE, errorMsg)
                    service.sendBroadcast(errorIntent)
                }
            }
            return android.app.Service.START_STICKY
        }
        
        session.logFileManager.clearLogsSync()
        AiLogHelper.d(TAG, "🧹 INTENT START: Logs cleared")
        
        val prefs = session.prefs
        
        if (prefs.disableVpn) {
            AiLogHelper.i(TAG, "🚀 INTENT START: Core-only mode (disableVpn=true)")
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
            AiLogHelper.i(TAG, "✅ INTENT START: Core-only mode started successfully")
        } else {
            AiLogHelper.i(TAG, "🚀 INTENT START: Full VPN mode (disableVpn=false), initiating VPN session")
            
            // CRITICAL: Broadcast ACTION_START immediately so isServiceEnabled flow updates
            // This prevents VpnConnectionUseCase from timing out while waiting for service to be enabled
            val successIntent = Intent(ACTION_START)
            successIntent.setPackage(service.application.packageName)
            service.sendBroadcast(successIntent)
            AiLogHelper.d(TAG, "📤 INTENT START: ACTION_START broadcast sent immediately for VPN mode")
            
            // Initiate VPN session (this will take time, but service is now "enabled")
            service.initiateVpnSession()
        }
        
        val duration = System.currentTimeMillis() - startTime
        AiLogHelper.i(TAG, "✅ INTENT START: Action handled (duration: ${duration}ms)")
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
        
        // CRITICAL: Broadcast ACTION_START immediately so isServiceEnabled flow updates
        val successIntent = Intent(ACTION_START)
        successIntent.setPackage(service.application.packageName)
        service.sendBroadcast(successIntent)
        AiLogHelper.d(TAG, "📤 INTENT DEFAULT: ACTION_START broadcast sent immediately")
        
        service.initiateVpnSession()
        return android.app.Service.START_STICKY
    }
}


