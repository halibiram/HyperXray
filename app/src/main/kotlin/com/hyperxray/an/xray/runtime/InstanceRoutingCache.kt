package com.hyperxray.an.xray.runtime

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe cache for domain/IP to instance mapping.
 * Implements LRU eviction and TTL-based expiration for sticky routing.
 * 
 * This cache ensures that the same domain/IP always routes to the same
 * Xray instance, preventing packet loss when connections switch instances.
 */
class InstanceRoutingCache(
    private val maxSize: Int = 1000,
    private val ttlMs: Long = 3600000L // 1 hour default
) {
    private val TAG = "InstanceRoutingCache"
    
    /**
     * Cache entry with timestamp for TTL expiration
     */
    private data class CacheEntry(
        val instanceIndex: Int,
        val apiPort: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(ttlMs: Long): Boolean {
            return System.currentTimeMillis() - timestamp > ttlMs
        }
    }
    
    // Thread-safe maps for domain and IP mappings
    private val domainCache = ConcurrentHashMap<String, CacheEntry>()
    private val ipCache = ConcurrentHashMap<String, CacheEntry>()
    
    // Access order tracking for LRU eviction (domain)
    private val domainAccessOrder = mutableListOf<String>()
    private val domainAccessMutex = Mutex()
    
    // Access order tracking for LRU eviction (IP)
    private val ipAccessOrder = mutableListOf<String>()
    private val ipAccessMutex = Mutex()
    
    /**
     * Get instance for domain. Returns cached instance or null if not found/expired.
     * 
     * @param domain Domain name
     * @return Pair of (instanceIndex, apiPort) or null if not found/expired
     */
    suspend fun getInstanceForDomain(domain: String): Pair<Int, Int>? {
        val entry = domainCache[domain] ?: return null
        
        // Check if expired
        if (entry.isExpired(ttlMs)) {
            domainCache.remove(domain)
            domainAccessMutex.withLock {
                domainAccessOrder.remove(domain)
            }
            Log.d(TAG, "Domain cache entry expired: $domain")
            return null
        }
        
        // Update access order for LRU
        domainAccessMutex.withLock {
            domainAccessOrder.remove(domain)
            domainAccessOrder.add(domain)
        }
        
        Log.d(TAG, "Domain cache hit: $domain -> instance ${entry.instanceIndex}, port ${entry.apiPort}")
        return Pair(entry.instanceIndex, entry.apiPort)
    }
    
    /**
     * Get instance for IP. Returns cached instance or null if not found/expired.
     * 
     * @param ip IP address
     * @return Pair of (instanceIndex, apiPort) or null if not found/expired
     */
    suspend fun getInstanceForIp(ip: String): Pair<Int, Int>? {
        val entry = ipCache[ip] ?: return null
        
        // Check if expired
        if (entry.isExpired(ttlMs)) {
            ipCache.remove(ip)
            ipAccessMutex.withLock {
                ipAccessOrder.remove(ip)
            }
            Log.d(TAG, "IP cache entry expired: $ip")
            return null
        }
        
        // Update access order for LRU
        ipAccessMutex.withLock {
            ipAccessOrder.remove(ip)
            ipAccessOrder.add(ip)
        }
        
        Log.d(TAG, "IP cache hit: $ip -> instance ${entry.instanceIndex}, port ${entry.apiPort}")
        return Pair(entry.instanceIndex, entry.apiPort)
    }
    
    /**
     * Set instance for domain.
     * 
     * @param domain Domain name
     * @param instanceIndex Instance index (0-based)
     * @param apiPort API port
     */
    suspend fun setInstanceForDomain(domain: String, instanceIndex: Int, apiPort: Int) {
        // Evict LRU entry if cache is full
        if (domainCache.size >= maxSize && !domainCache.containsKey(domain)) {
            domainAccessMutex.withLock {
                if (domainAccessOrder.isNotEmpty()) {
                    val lruDomain = domainAccessOrder.removeAt(0)
                    domainCache.remove(lruDomain)
                    Log.d(TAG, "Evicted LRU domain: $lruDomain")
                }
            }
        }
        
        val entry = CacheEntry(instanceIndex, apiPort)
        domainCache[domain] = entry
        
        domainAccessMutex.withLock {
            domainAccessOrder.remove(domain)
            domainAccessOrder.add(domain)
        }
        
        Log.d(TAG, "Domain cache set: $domain -> instance $instanceIndex, port $apiPort")
    }
    
    /**
     * Set instance for IP.
     * 
     * @param ip IP address
     * @param instanceIndex Instance index (0-based)
     * @param apiPort API port
     */
    suspend fun setInstanceForIp(ip: String, instanceIndex: Int, apiPort: Int) {
        // Evict LRU entry if cache is full
        if (ipCache.size >= maxSize && !ipCache.containsKey(ip)) {
            ipAccessMutex.withLock {
                if (ipAccessOrder.isNotEmpty()) {
                    val lruIp = ipAccessOrder.removeAt(0)
                    ipCache.remove(lruIp)
                    Log.d(TAG, "Evicted LRU IP: $lruIp")
                }
            }
        }
        
        val entry = CacheEntry(instanceIndex, apiPort)
        ipCache[ip] = entry
        
        ipAccessMutex.withLock {
            ipAccessOrder.remove(ip)
            ipAccessOrder.add(ip)
        }
        
        Log.d(TAG, "IP cache set: $ip -> instance $instanceIndex, port $apiPort")
    }
    
    /**
     * Clear all mappings.
     */
    suspend fun clear() {
        domainCache.clear()
        ipCache.clear()
        domainAccessMutex.withLock {
            domainAccessOrder.clear()
        }
        ipAccessMutex.withLock {
            ipAccessOrder.clear()
        }
        Log.d(TAG, "Cache cleared")
    }
    
    /**
     * Remove stale entries (expired based on TTL).
     * 
     * @return Number of entries removed
     */
    suspend fun removeStaleEntries(): Int {
        var removedCount = 0
        
        // Remove stale domain entries
        val expiredDomains = domainCache.entries
            .filter { it.value.isExpired(ttlMs) }
            .map { it.key }
            .toList()
        
        expiredDomains.forEach { domain ->
            domainCache.remove(domain)
            domainAccessMutex.withLock {
                domainAccessOrder.remove(domain)
            }
            removedCount++
        }
        
        // Remove stale IP entries
        val expiredIps = ipCache.entries
            .filter { it.value.isExpired(ttlMs) }
            .map { it.key }
            .toList()
        
        expiredIps.forEach { ip ->
            ipCache.remove(ip)
            ipAccessMutex.withLock {
                ipAccessOrder.remove(ip)
            }
            removedCount++
        }
        
        if (removedCount > 0) {
            Log.d(TAG, "Removed $removedCount stale cache entries")
        }
        
        return removedCount
    }
    
    /**
     * Get cache statistics.
     * 
     * @return Pair of (domainCacheSize, ipCacheSize)
     */
    fun getStats(): Pair<Int, Int> {
        return Pair(domainCache.size, ipCache.size)
    }
}

