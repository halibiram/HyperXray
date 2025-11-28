package com.hyperxray.an.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress

/**
 * Kotlin wrapper for native Go DNS cache and server.
 * Provides a clean API for DNS operations through native Go code.
 */
class NativeDnsManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "NativeDnsManager"
        private const val METRICS_UPDATE_INTERVAL_MS = 500L
        
        @Volatile
        private var instance: NativeDnsManager? = null
        
        fun getInstance(context: Context): NativeDnsManager {
            return instance ?: synchronized(this) {
                instance ?: NativeDnsManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
        
        @Synchronized
        fun resetInstance() {
            instance?.shutdown()
            instance = null
        }
    }
    
    /**
     * DNS cache metrics data class.
     */
    data class DnsCacheMetrics(
        val entryCount: Int = 0,
        val totalLookups: Long = 0,
        val hits: Long = 0,
        val misses: Long = 0,
        val hitRate: Int = 0,
        val memoryUsageBytes: Long = 0,
        val memoryLimitBytes: Long = 0,
        val memoryUsagePercent: Int = 0,
        val avgHitLatencyMs: Double = 0.0,
        val avgMissLatencyMs: Double = 0.0,
        val avgDomainHitRate: Int = 0,
        val topDomains: List<DomainHitRate> = emptyList(),
        val avgTTLSeconds: Long = 0,
        val activeEntries: List<CacheEntry> = emptyList(),
        val updateTimestamp: Long = System.currentTimeMillis()
    )
    
    data class DomainHitRate(
        val domain: String,
        val hits: Long,
        val misses: Long,
        val hitRate: Int
    )
    
    data class CacheEntry(
        val domain: String,
        val ips: List<String>,
        val expiryTime: Long
    )
    
    /**
     * DNS server statistics.
     */
    data class DnsServerStats(
        val listenPort: Int = 0,
        val running: Boolean = false,
        val totalQueries: Long = 0,
        val cachedResponses: Long = 0,
        val forwardedQueries: Long = 0,
        val failedQueries: Long = 0,
        val cacheHitRate: Int = 0
    )
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var metricsJob: Job? = null
    private var vpnService: HyperVpnService? = null
    private var cacheInitialized = false
    private var serverRunning = false
    
    private val _cacheMetrics = MutableStateFlow(DnsCacheMetrics())
    val cacheMetrics: StateFlow<DnsCacheMetrics> = _cacheMetrics.asStateFlow()
    
    private val _serverStats = MutableStateFlow(DnsServerStats())
    val serverStats: StateFlow<DnsServerStats> = _serverStats.asStateFlow()
    
    /**
     * Initialize the DNS cache.
     */
    fun initializeCache(service: HyperVpnService): Boolean {
        vpnService = service
        
        if (cacheInitialized) {
            Log.d(TAG, "DNS cache already initialized")
            return true
        }
        
        val cacheDir = context.cacheDir.absolutePath
        Log.i(TAG, "Initializing native DNS cache: cacheDir=$cacheDir")
        
        return try {
            val result = service.initDNSCacheNative(cacheDir)
            if (result == 0) {
                cacheInitialized = true
                startMetricsUpdateJob()
                Log.i(TAG, "Native DNS cache initialized successfully")
                true
            } else {
                Log.e(TAG, "Failed to initialize DNS cache: error code $result")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing DNS cache: ${e.message}", e)
            false
        }
    }
    
    /**
     * Lookup a hostname in the DNS cache.
     * @return List of IP addresses, or null if not found
     */
    fun lookup(hostname: String): List<InetAddress>? {
        val service = vpnService ?: return null
        
        return try {
            val ipsJson = service.dnsCacheLookupAllNative(hostname)
            if (ipsJson.isNullOrEmpty() || ipsJson == "[]") {
                null
            } else {
                val jsonArray = JSONArray(ipsJson)
                val ips = mutableListOf<InetAddress>()
                for (i in 0 until jsonArray.length()) {
                    val ipStr = jsonArray.getString(i)
                    try {
                        ips.add(InetAddress.getByName(ipStr))
                    } catch (e: Exception) {
                        Log.w(TAG, "Invalid IP: $ipStr")
                    }
                }
                if (ips.isNotEmpty()) ips else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNS lookup error: ${e.message}")
            null
        }
    }
    
    /**
     * Save a DNS resolution to cache.
     */
    fun save(hostname: String, ips: List<InetAddress>, ttl: Long = 86400) {
        val service = vpnService ?: return
        
        try {
            val ipStrings = ips.mapNotNull { it.hostAddress }
            val ipsJson = JSONArray(ipStrings).toString()
            service.dnsCacheSaveNative(hostname, ipsJson, ttl)
        } catch (e: Exception) {
            Log.e(TAG, "DNS save error: ${e.message}")
        }
    }
    
    /**
     * Clear the DNS cache.
     */
    fun clearCache() {
        val service = vpnService ?: return
        
        try {
            service.dnsCacheClearNative()
            Log.i(TAG, "DNS cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Clear cache error: ${e.message}")
        }
    }
    
    /**
     * Cleanup expired cache entries.
     * @return Number of entries removed
     */
    fun cleanupExpired(): Int {
        val service = vpnService ?: return 0
        
        return try {
            service.dnsCacheCleanupExpiredNative()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
            0
        }
    }
    
    /**
     * Start the DNS server.
     * @param port Port to listen on (default 5353)
     * @param upstreamDns Upstream DNS server (default "1.1.1.1:53")
     * @return Actual listening port, or -1 on error
     */
    fun startServer(port: Int = 5353, upstreamDns: String = "1.1.1.1:53"): Int {
        val service = vpnService ?: return -1
        
        if (serverRunning) {
            Log.d(TAG, "DNS server already running")
            return service.getDNSServerPortNative()
        }
        
        Log.i(TAG, "Starting DNS server on port $port with upstream $upstreamDns")
        
        return try {
            val result = service.startDNSServerNative(port, upstreamDns)
            if (result > 0) {
                serverRunning = true
                Log.i(TAG, "DNS server started on port $result")
            } else {
                Log.e(TAG, "Failed to start DNS server: $result")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Start server error: ${e.message}", e)
            -1
        }
    }
    
    /**
     * Stop the DNS server.
     */
    fun stopServer(): Boolean {
        val service = vpnService ?: return false
        
        if (!serverRunning) return true
        
        return try {
            val result = service.stopDNSServerNative()
            serverRunning = false
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Stop server error: ${e.message}")
            false
        }
    }
    
    /**
     * Check if DNS server is running.
     */
    fun isServerRunning(): Boolean {
        val service = vpnService ?: return false
        return try {
            service.isDNSServerRunningNative()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get DNS server port.
     */
    fun getServerPort(): Int {
        val service = vpnService ?: return 0
        return try {
            service.getDNSServerPortNative()
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Resolve a hostname using cache or upstream.
     * @return List of IP addresses as strings, or empty list
     */
    fun resolve(hostname: String): List<String> {
        val service = vpnService ?: return emptyList()
        
        return try {
            val result = service.dnsResolveNative(hostname)
            if (result.isNullOrEmpty() || result == "[]") {
                emptyList()
            } else {
                val jsonArray = JSONArray(result)
                val ips = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    ips.add(jsonArray.getString(i))
                }
                ips
            }
        } catch (e: Exception) {
            Log.e(TAG, "Resolve error: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Start metrics update job.
     */
    private fun startMetricsUpdateJob() {
        metricsJob?.cancel()
        metricsJob = scope.launch {
            while (isActive) {
                updateMetrics()
                delay(METRICS_UPDATE_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Update metrics from native.
     */
    private fun updateMetrics() {
        val service = vpnService ?: return
        
        try {
            // Update cache metrics
            val cacheMetricsJson = service.getDNSCacheMetricsNative()
            _cacheMetrics.value = parseCacheMetrics(cacheMetricsJson)
            
            // Update server stats
            if (serverRunning) {
                val serverStatsJson = service.getDNSServerStatsNative()
                _serverStats.value = parseServerStats(serverStatsJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update metrics error: ${e.message}")
        }
    }
    
    /**
     * Parse cache metrics from JSON.
     */
    private fun parseCacheMetrics(json: String): DnsCacheMetrics {
        return try {
            val obj = JSONObject(json)
            
            val topDomains = mutableListOf<DomainHitRate>()
            val topDomainsArray = obj.optJSONArray("topDomains")
            if (topDomainsArray != null) {
                for (i in 0 until topDomainsArray.length()) {
                    val d = topDomainsArray.getJSONObject(i)
                    topDomains.add(DomainHitRate(
                        domain = d.optString("domain", ""),
                        hits = d.optLong("hits", 0),
                        misses = d.optLong("misses", 0),
                        hitRate = d.optInt("hitRate", 0)
                    ))
                }
            }
            
            val activeEntries = mutableListOf<CacheEntry>()
            val entriesArray = obj.optJSONArray("activeEntries")
            if (entriesArray != null) {
                for (i in 0 until entriesArray.length()) {
                    val e = entriesArray.getJSONObject(i)
                    val ipsArray = e.optJSONArray("ips")
                    val ips = mutableListOf<String>()
                    if (ipsArray != null) {
                        for (j in 0 until ipsArray.length()) {
                            ips.add(ipsArray.getString(j))
                        }
                    }
                    activeEntries.add(CacheEntry(
                        domain = e.optString("domain", ""),
                        ips = ips,
                        expiryTime = e.optLong("expiryTime", 0)
                    ))
                }
            }
            
            DnsCacheMetrics(
                entryCount = obj.optInt("entryCount", 0),
                totalLookups = obj.optLong("totalLookups", 0),
                hits = obj.optLong("hits", 0),
                misses = obj.optLong("misses", 0),
                hitRate = obj.optInt("hitRate", 0),
                memoryUsageBytes = obj.optLong("memoryUsageBytes", 0),
                memoryLimitBytes = obj.optLong("memoryLimitBytes", 0),
                memoryUsagePercent = obj.optInt("memoryUsagePercent", 0),
                avgHitLatencyMs = obj.optDouble("avgHitLatencyMs", 0.0),
                avgMissLatencyMs = obj.optDouble("avgMissLatencyMs", 0.0),
                avgDomainHitRate = obj.optInt("avgDomainHitRate", 0),
                topDomains = topDomains,
                avgTTLSeconds = obj.optLong("avgTtlSeconds", 0),
                activeEntries = activeEntries,
                updateTimestamp = obj.optLong("updateTimestamp", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse cache metrics error: ${e.message}")
            DnsCacheMetrics()
        }
    }
    
    /**
     * Parse server stats from JSON.
     */
    private fun parseServerStats(json: String): DnsServerStats {
        return try {
            val obj = JSONObject(json)
            DnsServerStats(
                listenPort = obj.optInt("listenPort", 0),
                running = obj.optBoolean("running", false),
                totalQueries = obj.optLong("totalQueries", 0),
                cachedResponses = obj.optLong("cachedResponses", 0),
                forwardedQueries = obj.optLong("forwardedQueries", 0),
                failedQueries = obj.optLong("failedQueries", 0),
                cacheHitRate = obj.optInt("cacheHitRate", 0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse server stats error: ${e.message}")
            DnsServerStats()
        }
    }
    
    /**
     * Shutdown and cleanup.
     */
    fun shutdown() {
        metricsJob?.cancel()
        metricsJob = null
        stopServer()
        cacheInitialized = false
        vpnService = null
    }
}

// Extension functions to expose native DNS methods from HyperVpnService
internal fun HyperVpnService.initDNSCacheNative(cacheDir: String): Int {
    val method = this::class.java.getDeclaredMethod("initDNSCache", String::class.java)
    method.isAccessible = true
    return method.invoke(this, cacheDir) as Int
}

internal fun HyperVpnService.dnsCacheLookupAllNative(hostname: String): String {
    val method = this::class.java.getDeclaredMethod("dnsCacheLookupAll", String::class.java)
    method.isAccessible = true
    return method.invoke(this, hostname) as String
}

internal fun HyperVpnService.dnsCacheSaveNative(hostname: String, ipsJSON: String, ttl: Long) {
    val method = this::class.java.getDeclaredMethod(
        "dnsCacheSave",
        String::class.java,
        String::class.java,
        Long::class.java
    )
    method.isAccessible = true
    method.invoke(this, hostname, ipsJSON, ttl)
}

internal fun HyperVpnService.getDNSCacheMetricsNative(): String {
    val method = this::class.java.getDeclaredMethod("getDNSCacheMetrics")
    method.isAccessible = true
    return method.invoke(this) as String
}

internal fun HyperVpnService.dnsCacheClearNative() {
    val method = this::class.java.getDeclaredMethod("dnsCacheClear")
    method.isAccessible = true
    method.invoke(this)
}

internal fun HyperVpnService.dnsCacheCleanupExpiredNative(): Int {
    val method = this::class.java.getDeclaredMethod("dnsCacheCleanupExpired")
    method.isAccessible = true
    return method.invoke(this) as Int
}

internal fun HyperVpnService.startDNSServerNative(port: Int, upstreamDNS: String): Int {
    val method = this::class.java.getDeclaredMethod(
        "startDNSServer",
        Int::class.java,
        String::class.java
    )
    method.isAccessible = true
    return method.invoke(this, port, upstreamDNS) as Int
}

internal fun HyperVpnService.stopDNSServerNative(): Int {
    val method = this::class.java.getDeclaredMethod("stopDNSServer")
    method.isAccessible = true
    return method.invoke(this) as Int
}

internal fun HyperVpnService.isDNSServerRunningNative(): Boolean {
    val method = this::class.java.getDeclaredMethod("isDNSServerRunning")
    method.isAccessible = true
    return method.invoke(this) as Boolean
}

internal fun HyperVpnService.getDNSServerPortNative(): Int {
    val method = this::class.java.getDeclaredMethod("getDNSServerPort")
    method.isAccessible = true
    return method.invoke(this) as Int
}

internal fun HyperVpnService.getDNSServerStatsNative(): String {
    val method = this::class.java.getDeclaredMethod("getDNSServerStats")
    method.isAccessible = true
    return method.invoke(this) as String
}

internal fun HyperVpnService.dnsResolveNative(hostname: String): String {
    val method = this::class.java.getDeclaredMethod("dnsResolve", String::class.java)
    method.isAccessible = true
    return method.invoke(this, hostname) as String
}





