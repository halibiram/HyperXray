package com.hyperxray.an.analytics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.hyperxray.an.common.Socks5ReadinessChecker
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.service.TProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Example AnalyticsService that demonstrates proper SOCKS5 readiness handling.
 * 
 * This service:
 * 1. Waits for SOCKS5 to be ready before connecting
 * 2. Listens for SOCKS5 readiness broadcasts
 * 3. Automatically reconnects when SOCKS5 restarts
 * 4. Handles connection failures gracefully
 * 
 * IMPORTANT: Any service that needs to connect to the local SOCKS5 proxy
 * (127.0.0.1:10808) MUST wait for SOCKS5 readiness before attempting to connect.
 */
class AnalyticsService(private val context: Context) {
    private val TAG = "AnalyticsService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var isRunning = false
    private var socks5Socket: Socket? = null
    private var readinessReceiver: BroadcastReceiver? = null
    
    /**
     * Start the analytics service.
     * This will wait for SOCKS5 to be ready before connecting.
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Service already running")
            return
        }
        
        isRunning = true
        Log.d(TAG, "Starting AnalyticsService")
        
        // Register receiver to listen for SOCKS5 readiness events
        registerReadinessReceiver()
        
        // Check if SOCKS5 is already ready, or wait for it
        serviceScope.launch {
            connectToSocks5()
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
        unregisterReadinessReceiver()
        
        // Close socket connection
        closeSocket()
        
        // Cancel coroutine scope
        serviceScope.cancel()
    }
    
    /**
     * Connect to SOCKS5 proxy, waiting for readiness if necessary.
     */
    private suspend fun connectToSocks5() {
        val prefs = Preferences(context)
        val socksAddress = prefs.socksAddress
        val socksPort = prefs.socksPort
        
        try {
            // CRITICAL: Wait for SOCKS5 to be ready before connecting
            // This prevents SocketTimeoutException when SOCKS5 hasn't started yet
            Log.d(TAG, "Waiting for SOCKS5 to be ready on $socksAddress:$socksPort")
            
            val isReady = Socks5ReadinessChecker.waitUntilSocksReady(
                context = context,
                address = socksAddress,
                port = socksPort,
                maxWaitTimeMs = 30000L, // Wait up to 30 seconds
                retryIntervalMs = 500L // Check every 500ms
            )
            
            if (!isReady) {
                Log.e(TAG, "SOCKS5 did not become ready within timeout, cannot connect")
                return
            }
            
            // SOCKS5 is ready, attempt connection
            Log.d(TAG, "SOCKS5 is ready, connecting to $socksAddress:$socksPort")
            
            val socket = Socket()
            socket.soTimeout = 10000 // 10 second timeout
            socket.connect(
                java.net.InetSocketAddress(socksAddress, socksPort),
                10000 // 10 second connection timeout
            )
            
            socks5Socket = socket
            Log.i(TAG, "✅ Successfully connected to SOCKS5 proxy on $socksAddress:$socksPort")
            
            // Now you can use the socket for SOCKS5 communication
            // Example: send analytics data through the proxy
            sendAnalyticsData(socket)
            
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "SocketTimeoutException: failed to connect to /$socksAddress (port $socksPort) after 10000ms")
            Log.e(TAG, "This should not happen if SOCKS5 readiness check passed. SOCKS5 may have stopped.")
            
            // Attempt to reconnect after a delay
            if (isRunning) {
                delay(5000) // Wait 5 seconds before retry
                if (isRunning) {
                    Log.d(TAG, "Retrying connection to SOCKS5...")
                    connectToSocks5()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to SOCKS5: ${e.message}", e)
            
            // Attempt to reconnect after a delay
            if (isRunning) {
                delay(5000) // Wait 5 seconds before retry
                if (isRunning) {
                    Log.d(TAG, "Retrying connection to SOCKS5...")
                    connectToSocks5()
                }
            }
        }
    }
    
    /**
     * Send analytics data through SOCKS5 proxy.
     * This is a placeholder - implement your actual analytics logic here.
     */
    private suspend fun sendAnalyticsData(socket: Socket) {
        try {
            // Example: Send analytics data through SOCKS5
            // In a real implementation, you would:
            // 1. Perform SOCKS5 handshake
            // 2. Send your analytics data
            // 3. Handle responses
            
            Log.d(TAG, "Sending analytics data through SOCKS5...")
            
            // Placeholder: Keep connection alive and monitor for disconnects
            while (serviceScope.isActive && isRunning) {
                delay(10000) // Check every 10 seconds
                
                // Verify socket is still connected
                if (socket.isClosed || !socket.isConnected) {
                    Log.w(TAG, "SOCKS5 socket disconnected, reconnecting...")
                    closeSocket()
                    connectToSocks5()
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending analytics data: ${e.message}", e)
            closeSocket()
            
            // Attempt to reconnect
            if (isRunning) {
                delay(5000)
                if (isRunning) {
                    connectToSocks5()
                }
            }
        }
    }
    
    /**
     * Close the SOCKS5 socket connection.
     */
    private fun closeSocket() {
        try {
            socks5Socket?.close()
            socks5Socket = null
            Log.d(TAG, "SOCKS5 socket closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket: ${e.message}")
        }
    }
    
    /**
     * Register BroadcastReceiver to listen for SOCKS5 readiness events.
     */
    private fun registerReadinessReceiver() {
        if (readinessReceiver != null) {
            return // Already registered
        }
        
        readinessReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    TProxyService.ACTION_SOCKS5_READY -> {
                        val socksAddress = intent.getStringExtra("socks_address") ?: "127.0.0.1"
                        val socksPort = intent.getIntExtra("socks_port", 10808)
                        Log.d(TAG, "Received SOCKS5 ready broadcast: $socksAddress:$socksPort")
                        
                        // If socket is not connected or disconnected, reconnect
                        if (socks5Socket == null || socks5Socket?.isClosed == true || 
                            socks5Socket?.isConnected == false) {
                            serviceScope.launch {
                                connectToSocks5()
                            }
                        }
                    }
                    TProxyService.ACTION_STOP -> {
                        Log.d(TAG, "Received service stop broadcast, closing SOCKS5 connection")
                        closeSocket()
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(TProxyService.ACTION_SOCKS5_READY)
            addAction(TProxyService.ACTION_STOP)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(readinessReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(readinessReceiver, filter)
        }
        
        Log.d(TAG, "Registered SOCKS5 readiness receiver")
    }
    
    /**
     * Unregister BroadcastReceiver.
     */
    private fun unregisterReadinessReceiver() {
        readinessReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
                readinessReceiver = null
                Log.d(TAG, "Unregistered SOCKS5 readiness receiver")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receiver: ${e.message}")
            }
        }
    }
}

