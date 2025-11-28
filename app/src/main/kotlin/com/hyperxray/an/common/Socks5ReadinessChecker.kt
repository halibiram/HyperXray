package com.hyperxray.an.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.vpn.HyperVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Utility class to check if SOCKS5 proxy is ready and wait for it to become available.
 * 
 * This class provides:
 * - Health check function to test if SOCKS5 port is listening
 * - Suspend function to wait until SOCKS5 is ready
 * - StateFlow to observe SOCKS5 readiness status
 * - Auto-reconnection support when SOCKS5 restarts
 */
object Socks5ReadinessChecker {
    private const val TAG = "Socks5ReadinessChecker"
    
    // Default timeout for socket connection attempts
    // Increased from 500ms to 2000ms to allow more time for connection establishment
    // This prevents premature failures when SOCKS5 server is starting up
    private const val SOCKET_CONNECT_TIMEOUT_MS = 2000L
    
    // Retry interval when waiting for SOCKS5 to become ready
    // Increased from 100ms to 500ms to reduce CPU usage and allow server more time to start
    private const val RETRY_INTERVAL_MS = 500L
    
    // Maximum wait time before giving up (fallback timeout)
    // Increased to 20 seconds to allow Xray process more time to start SOCKS5 server
    private const val MAX_WAIT_TIME_MS = 20000L // 20 seconds
    
    // StateFlow to track SOCKS5 readiness
    private val _isSocks5Ready = MutableStateFlow<Boolean>(false)
    val isSocks5Ready: StateFlow<Boolean> = _isSocks5Ready.asStateFlow()
    
    /**
     * Check if SOCKS5 proxy is ready by attempting to connect to the port.
     * 
     * @param context Application context
     * @param address SOCKS5 address (default: 127.0.0.1)
     * @param port SOCKS5 port (default: from Preferences or 10808)
     * @return true if port is listening and accepting connections, false otherwise
     */
    fun isSocks5Ready(
        context: Context,
        address: String = "127.0.0.1",
        port: Int? = null
    ): Boolean {
        val socksPort = port ?: Preferences(context).socksPort
        val socksAddress = address
        
        return try {
            Socket().use { socket ->
                socket.soTimeout = SOCKET_CONNECT_TIMEOUT_MS.toInt()
                socket.connect(
                    java.net.InetSocketAddress(socksAddress, socksPort),
                    SOCKET_CONNECT_TIMEOUT_MS.toInt()
                )
                // If connection succeeds, port is listening
                Log.d(TAG, "SOCKS5 readiness check: port $socksPort is ready")
                true
            }
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "SOCKS5 readiness check: port $socksPort not ready (timeout after ${SOCKET_CONNECT_TIMEOUT_MS}ms)")
            false
        } catch (e: java.net.ConnectException) {
            // Connection refused - server not listening yet
            val errorMsg = e.message ?: "Unknown connection error"
            if (errorMsg.contains("ECONNREFUSED", ignoreCase = true) || 
                errorMsg.contains("Connection refused", ignoreCase = true)) {
                Log.d(TAG, "SOCKS5 readiness check: port $socksPort not ready (connection refused - server not listening)")
            } else {
                Log.d(TAG, "SOCKS5 readiness check: port $socksPort not ready (connect exception: $errorMsg)")
            }
            false
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error"
            Log.d(TAG, "SOCKS5 readiness check: port $socksPort not ready (${e.javaClass.simpleName}: $errorMsg)")
            false
        }
    }
    
    /**
     * Wait until SOCKS5 proxy is ready, with retries and timeout.
     * 
     * @param context Application context
     * @param address SOCKS5 address (default: 127.0.0.1)
     * @param port SOCKS5 port (default: from Preferences or 10808)
     * @param maxWaitTimeMs Maximum time to wait in milliseconds (default: 30 seconds)
     * @param retryIntervalMs Interval between retry attempts in milliseconds (default: 500ms)
     * @return true if SOCKS5 became ready within timeout, false if timeout was reached
     */
    suspend fun waitUntilSocksReady(
        context: Context,
        address: String = "127.0.0.1",
        port: Int? = null,
        maxWaitTimeMs: Long = MAX_WAIT_TIME_MS,
        retryIntervalMs: Long = RETRY_INTERVAL_MS
    ): Boolean {
        val socksPort = port ?: Preferences(context).socksPort
        val socksAddress = address
        val startTime = System.currentTimeMillis()
        
        Log.d(TAG, "Waiting for SOCKS5 to become ready on $socksAddress:$socksPort (max wait: ${maxWaitTimeMs}ms)")
        
        var attemptCount = 0
        while (System.currentTimeMillis() - startTime < maxWaitTimeMs) {
            attemptCount++
            val elapsed = System.currentTimeMillis() - startTime
            
            if (isSocks5Ready(context, socksAddress, socksPort)) {
                _isSocks5Ready.value = true
                Log.i(TAG, "✅ SOCKS5 is ready on $socksAddress:$socksPort (after ${elapsed}ms, $attemptCount attempts)")
                return true
            }
            
            // Check if we've exceeded max wait time
            if (elapsed >= maxWaitTimeMs) {
                break
            }
            
            // Log progress every 5 seconds
            if (attemptCount % 10 == 0 || elapsed % 5000 < retryIntervalMs) {
                Log.d(TAG, "SOCKS5 not ready yet (attempt $attemptCount, elapsed: ${elapsed}ms/${maxWaitTimeMs}ms)")
            }
            
            // Wait before retrying
            val remainingWait = (maxWaitTimeMs - elapsed).coerceAtMost(retryIntervalMs)
            delay(remainingWait)
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        Log.w(TAG, "❌ SOCKS5 did not become ready within ${elapsed}ms (timeout: ${maxWaitTimeMs}ms, attempts: $attemptCount)")
        Log.w(TAG, "This may indicate that Xray process has not started SOCKS5 server on $socksAddress:$socksPort")
        _isSocks5Ready.value = false
        return false
    }
    
    /**
     * Update readiness state (called by TProxyService when SOCKS5 starts/stops).
     */
    fun setSocks5Ready(ready: Boolean) {
        if (_isSocks5Ready.value != ready) {
            _isSocks5Ready.value = ready
            Log.d(TAG, "SOCKS5 readiness state updated: $ready")
        }
    }
    
    /**
     * Register a BroadcastReceiver to listen for SOCKS5 readiness events from TProxyService.
     * 
     * @param context Application context
     * @param receiver BroadcastReceiver to register
     */
    fun registerReadinessReceiver(context: Context, receiver: BroadcastReceiver) {
        val filter = IntentFilter("com.hyperxray.an.SOCKS5_READY")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        Log.d(TAG, "Registered SOCKS5 readiness receiver")
    }
    
    /**
     * Unregister a BroadcastReceiver.
     * 
     * @param context Application context
     * @param receiver BroadcastReceiver to unregister
     */
    fun unregisterReadinessReceiver(context: Context, receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
            Log.d(TAG, "Unregistered SOCKS5 readiness receiver")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering SOCKS5 readiness receiver: ${e.message}")
        }
    }
}

