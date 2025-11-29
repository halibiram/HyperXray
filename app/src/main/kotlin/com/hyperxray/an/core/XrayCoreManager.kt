package com.hyperxray.an.core

import android.content.Context
import android.util.Log
import com.hyperxray.an.vpn.HyperVpnService
// REMOVED: NativeXrayBridge import - deprecated
// import com.hyperxray.an.vpn.NativeXrayBridge
import kotlinx.coroutines.flow.StateFlow

/**
 * DEPRECATED: XrayCoreManager is no longer needed.
 * 
 * Xray-core is now managed directly through HyperVpnService.startHyperTunnel()
 * which embeds Xray-core. This manager is kept for backward compatibility
 * but all methods are no-op.
 * 
 * @see com.hyperxray.an.vpn.HyperVpnService.startHyperTunnel()
 */
@Deprecated(
    message = "Xray-core is managed through startHyperTunnel(). This manager is no longer needed.",
    replaceWith = ReplaceWith("HyperVpnService.startHyperTunnel()", "com.hyperxray.an.vpn.HyperVpnService"),
    level = DeprecationLevel.WARNING
)
class XrayCoreManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "XrayCoreManager"
        
        @Volatile
        private var instance: XrayCoreManager? = null
        
        /**
         * Get singleton instance.
         */
        fun getInstance(context: Context): XrayCoreManager {
            return instance ?: synchronized(this) {
                instance ?: XrayCoreManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
        
        /**
         * Reset singleton (for testing).
         */
        @Synchronized
        fun resetInstance() {
            instance = null
        }
    }
    
    private var vpnService: HyperVpnService? = null
    
    /**
     * Initialize the manager with VPN service.
     * 
     * DEPRECATED: No-op - Xray-core is managed through startHyperTunnel()
     */
    fun initialize(service: HyperVpnService): Boolean {
        Log.w(TAG, "XrayCoreManager.initialize() is deprecated - Xray-core is managed through startHyperTunnel()")
        vpnService = service
        return true
    }
    
    /**
     * Start Xray-core with given configuration.
     * 
     * DEPRECATED: No-op - Xray-core is managed through startHyperTunnel()
     */
    fun start(configPath: String, configJSON: String? = null): Boolean {
        Log.w(TAG, "XrayCoreManager.start() is deprecated - Xray-core is managed through startHyperTunnel()")
        return false
    }
    
    /**
     * Stop Xray-core instance.
     * 
     * DEPRECATED: No-op - Xray-core is managed through stopHyperTunnel()
     */
    fun stop(): Boolean {
        Log.w(TAG, "XrayCoreManager.stop() is deprecated - Xray-core is managed through stopHyperTunnel()")
        return true
    }
    
    /**
     * Check if Xray-core is running.
     * 
     * DEPRECATED: No-op - Xray-core is managed through startHyperTunnel()
     */
    fun isRunning(): Boolean {
        Log.w(TAG, "XrayCoreManager.isRunning() is deprecated - Xray-core is managed through startHyperTunnel()")
        return false
    }
    
    /**
     * Get current status flow.
     * 
     * DEPRECATED: Returns null - Xray-core is managed through startHyperTunnel()
     */
    fun getStatus(): StateFlow<Any>? {
        Log.w(TAG, "XrayCoreManager.getStatus() is deprecated - Xray-core is managed through startHyperTunnel()")
        return null
    }
    
    /**
     * Update status from native layer.
     * 
     * DEPRECATED: No-op - Xray-core is managed through startHyperTunnel()
     */
    fun updateStatus() {
        Log.w(TAG, "XrayCoreManager.updateStatus() is deprecated - Xray-core is managed through startHyperTunnel()")
    }
    
    /**
     * Cleanup resources.
     * 
     * DEPRECATED: No-op - Xray-core is managed through stopHyperTunnel()
     */
    fun cleanup() {
        Log.w(TAG, "XrayCoreManager.cleanup() is deprecated - Xray-core is managed through stopHyperTunnel()")
        vpnService = null
    }
}

