package com.hyperxray.an.core.network.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Manages HyperVpnService state and statistics.
 * Observes service broadcasts and provides state flows for UI.
 */
class HyperVpnStateManager(private val context: Context) {
    
    companion object {
        private const val TAG = "HyperVpnStateManager"
        
        const val ACTION_STATE_CHANGED = "com.hyperxray.an.HYPER_VPN_STATE_CHANGED"
        const val ACTION_STATS_UPDATE = "com.hyperxray.an.HYPER_VPN_STATS_UPDATE"
        const val ACTION_ERROR = "com.hyperxray.an.HYPER_VPN_ERROR"
        
        const val EXTRA_STATE = "state"
        const val EXTRA_STATS = "stats"
        const val EXTRA_ERROR = "error"
        const val EXTRA_ERROR_CODE = "error_code"
        const val EXTRA_ERROR_DETAILS = "error_details"
    }
    
    /**
     * HyperVpn connection state
     */
    sealed class VpnState {
        object Disconnected : VpnState()
        data class Connecting(
            val progress: Float = 0f,
            val statusMessage: String = "Initializing..."
        ) : VpnState()
        data class Connected(
            val serverName: String = "",
            val serverLocation: String = "",
            val connectedAt: Long = System.currentTimeMillis()
        ) : VpnState()
        data class Disconnecting(val progress: Float = 0f, val statusMessage: String = "Disconnecting...") : VpnState()
        data class Error(
            val errorMessage: String,
            val code: Int = -1,
            val details: String? = null,
            val retryable: Boolean = true
        ) : VpnState()

        /**
         * Helper properties
         */
        val isConnected: Boolean get() = this is Connected
        val isConnecting: Boolean get() = this is Connecting
        val isDisconnected: Boolean get() = this is Disconnected
        val isDisconnecting: Boolean get() = this is Disconnecting
        val isError: Boolean get() = this is Error
        
        /**
         * Get display name for UI
         */
        fun getDisplayName(): String = when (this) {
            is Disconnected -> "Disconnected"
            is Connecting -> "Connecting..."
            is Connected -> "Connected"
            is Disconnecting -> "Disconnecting..."
            is Error -> "Error"
        }

        /**
         * Get detailed message for UI
         */
        fun getMessage(): String = when (this) {
            is Disconnected -> "VPN is not connected"
            is Connecting -> statusMessage
            is Connected -> "Connected to $serverName"
            is Disconnecting -> statusMessage
            is Error -> errorMessage
        }
        
        /**
         * Get status color resource (as Long for Color constructor)
         */
        fun getStatusColor(): Long = when (this) {
            is Disconnected -> 0xFF9E9E9E  // Gray
            is Connecting -> 0xFFFFA726    // Orange
            is Connected -> 0xFF4CAF50     // Green
            is Disconnecting -> 0xFFFFA726 // Orange
            is Error -> 0xFFF44336         // Red
        }
    }
    
    /**
     * Detailed tunnel statistics
     */
    data class TunnelStats(
        val txBytes: Long = 0,
        val rxBytes: Long = 0,
        val txPackets: Long = 0,
        val rxPackets: Long = 0,
        val lastHandshake: Long = 0,
        val lastHandshakeFormatted: String = "Never",
        val connected: Boolean = false,
        val uptime: Long = 0, // seconds
        val latency: Long = 0, // milliseconds
        val packetLoss: Double = 0.0, // percentage
        val throughput: Double = 0.0 // bytes per second
    ) {
        val totalBytes: Long get() = txBytes + rxBytes
        val totalPackets: Long get() = txPackets + rxPackets
        
        companion object {
            fun fromJson(json: String): TunnelStats {
                return try {
                    val obj = JSONObject(json)
                    TunnelStats(
                        txBytes = obj.optLong("txBytes", 0),
                        rxBytes = obj.optLong("rxBytes", 0),
                        txPackets = obj.optLong("txPackets", 0),
                        rxPackets = obj.optLong("rxPackets", 0),
                        lastHandshake = obj.optLong("lastHandshake", 0),
                        lastHandshakeFormatted = obj.optString("lastHandshakeFormatted", "Never"),
                        connected = obj.optBoolean("connected", false),
                        uptime = obj.optLong("uptime", 0),
                        latency = obj.optLong("latency", 0),
                        packetLoss = obj.optDouble("packetLoss", 0.0),
                        throughput = obj.optDouble("throughput", 0.0)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing stats JSON: ${e.message}", e)
                    TunnelStats()
                }
            }
        }
    }
    
    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()
    
    private val _stats = MutableStateFlow<TunnelStats>(TunnelStats())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STATE_CHANGED -> {
                    val stateStr = intent.getStringExtra(EXTRA_STATE) ?: "disconnected"
                    _state.value = when (stateStr) {
                        "connected" -> {
                            val serverName = intent.getStringExtra("serverName") ?: ""
                            val serverLocation = intent.getStringExtra("serverLocation") ?: ""
                            val connectedAt = intent.getLongExtra("connectedAt", System.currentTimeMillis())
                            VpnState.Connected(serverName, serverLocation, connectedAt)
                        }
                        "connecting" -> {
                            val progress = intent.getFloatExtra("progress", 0f)
                            val statusMessage = intent.getStringExtra("message") ?: "Initializing..."
                            VpnState.Connecting(progress, statusMessage)
                        }
                        "disconnecting" -> VpnState.Disconnecting(
                            progress = intent.getFloatExtra("progress", 0f),
                            statusMessage = intent.getStringExtra("message") ?: "Disconnecting..."
                        )
                        "error" -> {
                            val errorMsg = intent.getStringExtra(EXTRA_ERROR) ?: "Unknown error"
                            val errorCode = intent.getIntExtra(EXTRA_ERROR_CODE, -1)
                            val errorDetails = intent.getStringExtra(EXTRA_ERROR_DETAILS)
                            VpnState.Error(errorMsg, errorCode, errorDetails)
                        }
                        else -> VpnState.Disconnected
                    }
                    Log.d(TAG, "State changed: ${_state.value}")
                }
                
                ACTION_STATS_UPDATE -> {
                    val statsJson = intent.getStringExtra(EXTRA_STATS)
                    if (statsJson != null) {
                        _stats.value = TunnelStats.fromJson(statsJson)
                    }
                }
                
                ACTION_ERROR -> {
                    val errorMsg = intent.getStringExtra(EXTRA_ERROR) ?: "Unknown error"
                    val errorCode = intent.getIntExtra(EXTRA_ERROR_CODE, -1)
                    val errorDetails = intent.getStringExtra(EXTRA_ERROR_DETAILS)
                    _error.value = errorMsg
                    _state.value = VpnState.Error(errorMsg, errorCode, errorDetails)
                    Log.e(TAG, "Error received: $errorMsg (code: $errorCode)")
                }
            }
        }
    }
    
    /**
     * Start observing service broadcasts
     */
    fun startObserving() {
        val filter = IntentFilter().apply {
            addAction(ACTION_STATE_CHANGED)
            addAction(ACTION_STATS_UPDATE)
            addAction(ACTION_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(broadcastReceiver, filter)
        }
        Log.d(TAG, "Started observing HyperVpnService broadcasts")
    }
    
    /**
     * Stop observing service broadcasts
     */
    fun stopObserving() {
        try {
            context.unregisterReceiver(broadcastReceiver)
            Log.d(TAG, "Stopped observing HyperVpnService broadcasts")
        } catch (e: IllegalArgumentException) {
            // Receiver not registered - this is fine
            Log.d(TAG, "Receiver was not registered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}", e)
        }
    }
    
    /**
     * Check if service is currently running
     */
    fun isServiceRunning(): Boolean {
        return _state.value is VpnState.Connected || _state.value is VpnState.Connecting
    }
    
    /**
     * Clear error state
     */
    fun clearError() {
        if (_state.value is VpnState.Error) {
            _state.value = VpnState.Disconnected
        }
        _error.value = null
    }
}
