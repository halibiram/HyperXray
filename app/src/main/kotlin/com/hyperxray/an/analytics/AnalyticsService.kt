package com.hyperxray.an.analytics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.vpn.HyperVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Analytics service that demonstrates proper VPN service event handling.
 * 
 * This service:
 * 1. Listens for VPN service start/stop broadcasts
 * 2. Handles connection lifecycle events
 * 3. Collects analytics data when VPN is connected
 * 
 * Note: This service has been simplified for the HyperVpnService architecture.
 * SOCKS5 readiness handling has been removed as HyperVpnService uses native Go tunnel.
 */
class AnalyticsService(private val context: Context) {
    private val TAG = "AnalyticsService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var isRunning = false
    private var isVpnConnected = false
    private var serviceEventReceiver: BroadcastReceiver? = null
    
    /**
     * Start the analytics service.
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Service already running")
            return
        }
        
        isRunning = true
        Log.d(TAG, "Starting AnalyticsService")
        
        // Register receiver to listen for VPN service events
        registerServiceEventReceiver()
        
        // Start analytics collection if VPN is already connected
        serviceScope.launch {
            startAnalyticsCollection()
        }
    }
    
    /**
     * Stop the analytics service.
     */
    fun stop() {
        if (!isRunning) {
            return
        }
        
        isRunning = false
        Log.d(TAG, "Stopping AnalyticsService")
        
        // Unregister receiver
        unregisterServiceEventReceiver()
        
        // Cancel coroutine scope
        serviceScope.cancel()
    }
    
    /**
     * Start collecting analytics data.
     */
    private suspend fun startAnalyticsCollection() {
        val prefs = Preferences(context)
        
        try {
            Log.d(TAG, "Starting analytics collection")
            
            // Placeholder: Keep collecting data while service is running
            while (serviceScope.isActive && isRunning) {
                if (isVpnConnected) {
                    // Collect analytics data when VPN is connected
                    collectAnalyticsData()
                }
                delay(60000) // Collect data every 60 seconds
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in analytics collection: ${e.message}", e)
        }
    }
    
    /**
     * Collect analytics data.
     * This is a placeholder - implement your actual analytics logic here.
     */
    private suspend fun collectAnalyticsData() {
        try {
            Log.d(TAG, "Collecting analytics data...")
            
            // Example analytics data collection:
            // - Connection duration
            // - Data transfer statistics
            // - Error counts
            // - Performance metrics
            
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting analytics data: ${e.message}", e)
        }
    }
    
    /**
     * Register BroadcastReceiver to listen for VPN service events.
     */
    private fun registerServiceEventReceiver() {
        if (serviceEventReceiver != null) {
            return // Already registered
        }
        
        serviceEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    HyperVpnService.ACTION_START -> {
                        Log.d(TAG, "Received VPN started broadcast")
                        isVpnConnected = true
                    }
                    HyperVpnService.ACTION_STOP -> {
                        Log.d(TAG, "Received VPN stopped broadcast")
                        isVpnConnected = false
                    }
                    HyperVpnService.ACTION_ERROR -> {
                        val errorMessage = intent.getStringExtra(HyperVpnService.EXTRA_ERROR_MESSAGE) ?: "Unknown error"
                        Log.e(TAG, "Received VPN error broadcast: $errorMessage")
                        isVpnConnected = false
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(HyperVpnService.ACTION_START)
            addAction(HyperVpnService.ACTION_STOP)
            addAction(HyperVpnService.ACTION_ERROR)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(serviceEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(serviceEventReceiver, filter)
        }
        
        Log.d(TAG, "Registered VPN service event receiver")
    }
    
    /**
     * Unregister BroadcastReceiver.
     */
    private fun unregisterServiceEventReceiver() {
        serviceEventReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
                serviceEventReceiver = null
                Log.d(TAG, "Unregistered VPN service event receiver")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receiver: ${e.message}")
            }
        }
    }
}
