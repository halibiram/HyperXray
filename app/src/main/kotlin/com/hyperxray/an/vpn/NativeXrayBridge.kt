package com.hyperxray.an.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * DEPRECATED: NativeXrayBridge is no longer functional.
 * 
 * The native multi-instance methods have been removed from HyperVpnService.
 * Xray-core is now managed directly through startHyperTunnel() which embeds Xray-core.
 * 
 * This class is kept for backward compatibility but all methods are no-op.
 * 
 * @see com.hyperxray.an.vpn.HyperVpnService.startHyperTunnel()
 */
@Deprecated(
    message = "Native multi-instance methods removed. Xray-core is managed through startHyperTunnel().",
    replaceWith = ReplaceWith("HyperVpnService.startHyperTunnel()", "com.hyperxray.an.vpn.HyperVpnService"),
    level = DeprecationLevel.WARNING
)
class NativeXrayBridge private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "NativeXrayBridge"
        
        // Constant instance ID - hidden from public API
        private const val INSTANCE_ID = 1
        
        @Volatile
        private var instance: NativeXrayBridge? = null
        
        /**
         * Get singleton instance.
         */
        fun getInstance(context: Context): NativeXrayBridge {
            return instance ?: synchronized(this) {
                instance ?: NativeXrayBridge(context.applicationContext).also {
                    instance = it
                }
            }
        }
        
        /**
         * Reset singleton (for testing).
         */
        @Synchronized
        fun resetInstance() {
            instance?.cleanup()
            instance = null
        }
    }
    
    /**
     * Instance status enum.
     */
    enum class InstanceStatus {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    }
    
    /**
     * Data class representing instance info.
     */
    data class InstanceInfo(
        val status: InstanceStatus,
        val apiPort: Int = 0,
        val startTime: Long = 0,
        val errorMsg: String? = null,
        val txBytes: Long = 0,
        val rxBytes: Long = 0,
        val connections: Int = 0
    )
    
    private val _status = MutableStateFlow<InstanceInfo>(
        InstanceInfo(InstanceStatus.STOPPED)
    )
    val status: StateFlow<InstanceInfo> = _status.asStateFlow()
    
    private var initialized = false
    private var vpnService: HyperVpnService? = null
    
    /**
     * Initialize the native Xray bridge.
     * Must be called before any other operations.
     * 
     * @param service The HyperVpnService instance (required for JNI calls)
     * @return true if initialization was successful
     */
    fun initialize(service: HyperVpnService): Boolean {
        Log.w(TAG, "NativeXrayBridge.initialize() is deprecated - native methods removed")
        Log.w(TAG, "Xray-core is now managed through HyperVpnService.startHyperTunnel()")
        // No-op: Native methods removed
        return false
    }
    
    /**
     * Start Xray-core instance with given configuration.
     * 
     * This method implements force restart logic: if an instance is already running,
     * it will stop it first, wait briefly, then start with the new configuration.
     * 
     * @param configPath Path to the Xray configuration JSON file
     * @param configJSON Xray configuration JSON content (optional, will read from configPath if null)
     * @return true if start was successful, false otherwise
     */
    fun start(configPath: String, configJSON: String? = null): Boolean {
        val service = vpnService
        if (service == null || !initialized) {
            Log.e(TAG, "Bridge not initialized")
            return false
        }
        
        // Force restart logic: stop first if running
        if (isRunning()) {
            Log.i(TAG, "Instance is running, stopping first for force restart...")
            stop()
            // Wait briefly for cleanup
            Thread.sleep(300)
        }
        
        Log.i(TAG, "Starting Xray-core instance (ID=$INSTANCE_ID)...")
        
        val configToUse = configJSON ?: try {
            java.io.File(configPath).readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read config from path: ${e.message}", e)
            return false
        }
        
        // Update status to STARTING
        _status.value = InstanceInfo(InstanceStatus.STARTING)
        
        Log.w(TAG, "NativeXrayBridge.start() is deprecated - native methods removed")
        Log.w(TAG, "Xray-core is now managed through HyperVpnService.startHyperTunnel()")
        
        // No-op: Native methods removed
        _status.value = InstanceInfo(
            status = InstanceStatus.ERROR,
            errorMsg = "Native multi-instance methods removed. Use HyperVpnService.startHyperTunnel() instead."
        )
        return false
    }
    
    /**
     * Stop the Xray-core instance.
     * 
     * @return true if stop was successful, false otherwise
     */
    fun stop(): Boolean {
        val service = vpnService
        if (service == null || !initialized) {
            Log.e(TAG, "Bridge not initialized")
            return false
        }
        
        Log.i(TAG, "Stopping Xray-core instance (ID=$INSTANCE_ID)...")
        
        // Update status to STOPPING
        _status.value = InstanceInfo(InstanceStatus.STOPPING)
        
        Log.w(TAG, "NativeXrayBridge.stop() is deprecated - native methods removed")
        Log.w(TAG, "Xray-core is now managed through HyperVpnService.stopHyperTunnel()")
        
        // No-op: Native methods removed
        _status.value = InstanceInfo(InstanceStatus.STOPPED)
        return true
    }
    
    /**
     * Check if Xray-core instance is running.
     * 
     * @return true if instance is running, false otherwise
     */
    fun isRunning(): Boolean {
        val service = vpnService
        if (service == null || !initialized) {
            return false
        }
        
        Log.w(TAG, "NativeXrayBridge.isRunning() is deprecated - native methods removed")
        // No-op: Native methods removed
        return false
    }
    
    /**
     * Get current instance status and update StateFlow.
     */
    fun updateStatusFromNative() {
        val service = vpnService
        if (service == null || !initialized) {
            return
        }
        
        Log.w(TAG, "NativeXrayBridge.updateStatusFromNative() is deprecated - native methods removed")
        // No-op: Native methods removed
        _status.value = InstanceInfo(InstanceStatus.STOPPED)
    }
    
    /**
     * Cleanup resources.
     */
    fun cleanup() {
        stop()
        initialized = false
        vpnService = null
        _status.value = InstanceInfo(InstanceStatus.STOPPED)
    }
    
    /**
     * Parse InstanceInfo from JSON.
     */
    private fun parseInstanceInfo(json: JSONObject): InstanceInfo? {
        return try {
            InstanceInfo(
                status = parseStatus(json.optString("status", "stopped")),
                apiPort = json.optInt("apiPort", 0),
                startTime = json.optLong("startTime", 0),
                errorMsg = json.optString("errorMsg", "").takeIf { it.isNotEmpty() },
                txBytes = json.optLong("txBytes", 0),
                rxBytes = json.optLong("rxBytes", 0),
                connections = json.optInt("connections", 0)
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Parse status string to enum.
     */
    private fun parseStatus(status: String): InstanceStatus {
        return when (status.lowercase()) {
            "stopped" -> InstanceStatus.STOPPED
            "starting" -> InstanceStatus.STARTING
            "running" -> InstanceStatus.RUNNING
            "stopping" -> InstanceStatus.STOPPING
            "error" -> InstanceStatus.ERROR
            else -> InstanceStatus.STOPPED
        }
    }
}

/**
 * REMOVED: Extension functions for native multi-instance methods.
 * 
 * These methods have been removed as part of architectural cleanup.
 * The native multi-instance methods are no longer available in HyperVpnService.
 * 
 * Xray-core is now managed directly through startHyperTunnel() which embeds Xray-core.
 */

