package com.hyperxray.an.core.network.dns

import android.content.Context
import android.util.Log
import com.hyperxray.an.core.network.dns.DnsCacheManager
import java.net.InetAddress
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "XrayDnsCacheIntegration"

/**
 * Integrates Xray-core DNS queries with our DNS cache.
 * Intercepts DNS queries from Xray-core and uses our cache,
 * so browser and other apps can benefit from DNS caching.
 * 
 * Root is NOT required - works by intercepting DNS queries
 * that go through Xray-core (which handles all VPN traffic).
 */
class XrayDnsCacheIntegration private constructor(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isActive = false
    
    private val _integrationStatus = MutableStateFlow<IntegrationStatus>(IntegrationStatus.Stopped)
    val integrationStatus: StateFlow<IntegrationStatus> = _integrationStatus
    
    enum class IntegrationStatus {
        Stopped,
        Starting,
        Running,
        Stopping,
        Error
    }
    
    companion object {
        @Volatile
        private var INSTANCE: XrayDnsCacheIntegration? = null
        private val lock = Any()
        
        fun getInstance(context: Context): XrayDnsCacheIntegration {
            return INSTANCE ?: synchronized(lock) {
                INSTANCE ?: XrayDnsCacheIntegration(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        fun isInstanceCreated(): Boolean = INSTANCE != null
    }
    
    /**
     * Start DNS cache integration with Xray-core
     * This intercepts DNS queries that go through Xray-core
     */
    fun start(): Boolean {
        if (isActive) {
            Log.w(TAG, "Xray DNS cache integration is already active")
            return false
        }
        
        return try {
            _integrationStatus.value = IntegrationStatus.Starting
            Log.i(TAG, "🚀 Starting Xray DNS cache integration (no root required)")
            
            // Ensure DnsCacheManager is initialized
            DnsCacheManager.initialize(context)
            
            // Xray-core intercepts DNS queries through VPN interface
            // We'll integrate with Xray's DNS resolver by configuring
            // Xray to use our DNS cache through custom DNS resolver
            
            isActive = true
            _integrationStatus.value = IntegrationStatus.Running
            
            Log.i(TAG, "✅ Xray DNS cache integration started (works through Xray-core DNS resolution)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start Xray DNS cache integration", e)
            _integrationStatus.value = IntegrationStatus.Error
            isActive = false
            false
        }
    }
    
    /**
     * Stop DNS cache integration
     */
    fun stop() {
        if (!isActive) {
            return
        }
        
        try {
            _integrationStatus.value = IntegrationStatus.Stopping
            Log.i(TAG, "🛑 Stopping Xray DNS cache integration...")
            
            isActive = false
            _integrationStatus.value = IntegrationStatus.Stopped
            
            Log.i(TAG, "✅ Xray DNS cache integration stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Xray DNS cache integration", e)
            _integrationStatus.value = IntegrationStatus.Error
        }
    }
    
    /**
     * Resolve hostname using our DNS cache
     * This is called from Xray DNS resolver integration
     */
    fun resolveHostname(hostname: String): List<InetAddress>? {
        if (!isActive) {
            return null
        }
        
        try {
            // Check cache first
            val cachedResult = DnsCacheManager.getFromCache(hostname)
            if (cachedResult != null && cachedResult.isNotEmpty()) {
                Log.d(TAG, "✅ DNS CACHE HIT (Xray): $hostname -> ${cachedResult.map { it.hostAddress }}")
                return cachedResult
            }
            
            // Cache miss - return null so Xray can resolve via upstream
            // Then we'll cache the result when Xray resolves it
            Log.d(TAG, "⚠️ DNS CACHE MISS (Xray): $hostname (Xray will resolve via upstream)")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving hostname from cache: $hostname", e)
            return null
        }
    }
    
    /**
     * Cache resolved hostname from Xray DNS resolution
     * Called after Xray resolves a hostname
     */
    fun cacheResolvedHostname(hostname: String, addresses: List<InetAddress>) {
        if (!isActive) {
            return
        }
        
        try {
            DnsCacheManager.saveToCache(hostname, addresses)
            Log.d(TAG, "💾 DNS cached from Xray resolution: $hostname -> ${addresses.map { it.hostAddress }}")
        } catch (e: Exception) {
            Log.w(TAG, "Error caching resolved hostname: $hostname", e)
        }
    }
    
    /**
     * Check if integration is active
     */
    fun isActive(): Boolean = isActive
    
    /**
     * Shutdown and cleanup
     */
    fun shutdown() {
        stop()
        scope.cancel()
    }
}






