package com.hyperxray.an.core.network.dns

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.net.InetAddress
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private const val TAG = "DnsCacheManager"
private const val CACHE_FILE_NAME = "dns_cache.json"
private const val DEFAULT_TTL = 86400L // 24 hours in seconds
private const val POPULAR_DOMAIN_TTL = 172800L // 48 hours for popular domains
private const val DYNAMIC_DOMAIN_TTL = 86400L // 24 hours for dynamic domains (CDN, etc.)
private const val MAX_ENTRIES = 10000
private const val CACHE_VERSION = 1

// Popular domains that rarely change - use longer TTL
private val popularDomains = setOf(
    "google.com", "www.google.com", "googleapis.com",
    "facebook.com", "www.facebook.com",
    "youtube.com", "www.youtube.com",
    "instagram.com", "www.instagram.com",
    "twitter.com", "www.twitter.com",
    "amazon.com", "www.amazon.com",
    "microsoft.com", "www.microsoft.com",
    "apple.com", "www.apple.com"
)

// Dynamic domains that change frequently - use shorter TTL
private val dynamicDomainPatterns = listOf(
    Regex(".*\\.cdn\\.", RegexOption.IGNORE_CASE),
    Regex(".*\\.edge\\.", RegexOption.IGNORE_CASE),
    Regex(".*\\.cloudfront\\.net", RegexOption.IGNORE_CASE),
    Regex(".*\\.akamaiedge\\.net", RegexOption.IGNORE_CASE),
    Regex(".*\\.fastly\\.net", RegexOption.IGNORE_CASE)
)

/**
 * Manages persistent DNS cache to avoid redundant DNS queries.
 * Stores DNS resolutions in a JSON file and checks cache before making DNS queries.
 */
object DnsCacheManager {
    private var isInitialized = false
    private var cacheFile: File? = null
    private val cacheLock = ReentrantReadWriteLock()
    private val cache = mutableMapOf<String, DnsCacheEntry>()
    private var cacheHits = 0L
    private var cacheMisses = 0L

    /**
     * DNS cache entry with IP addresses, timestamp, and TTL
     */
    private data class DnsCacheEntry(
        val ips: List<String>,
        val timestamp: Long,
        val ttl: Long = DEFAULT_TTL
    ) {
        fun isExpired(): Boolean {
            val currentTime = System.currentTimeMillis() / 1000
            return (currentTime - timestamp) > ttl
        }
    }

    /**
     * Initialize the DNS cache manager with application context
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.d(TAG, "DnsCacheManager already initialized")
            return
        }

        try {
            cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
            loadCacheFromFile()
            isInitialized = true
            Log.d(TAG, "DnsCacheManager initialized: ${cache.size} entries loaded, hits=$cacheHits, misses=$cacheMisses")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DnsCacheManager", e)
            isInitialized = true // Allow operation even if cache file fails
        }
    }

    /**
     * Get DNS resolution from cache if available and not expired
     * @return List of InetAddress if found in cache and valid, null otherwise
     */
    fun getFromCache(hostname: String): List<InetAddress>? {
        if (!isInitialized || cacheFile == null) {
            Log.d(TAG, "⚠️ DNS cache not initialized, skipping cache check for: $hostname")
            return null
        }

        Log.d(TAG, "🔍 Checking DNS cache for: $hostname")
        return cacheLock.read {
            val entry = cache[hostname.lowercase()]
            if (entry != null && !entry.isExpired()) {
                cacheHits++
                try {
                    val addresses = entry.ips.mapNotNull { ip ->
                        try {
                            InetAddress.getByName(ip)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse cached IP: $ip", e)
                            null
                        }
                    }
                    if (addresses.isNotEmpty()) {
                        Log.i(TAG, "✅ DNS cache HIT: $hostname -> ${entry.ips} (age: ${(System.currentTimeMillis() / 1000 - entry.timestamp)}s)")
                        return@read addresses
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error converting cached IPs to InetAddress", e)
                }
            } else if (entry != null && entry.isExpired()) {
                // Remove expired entry
                cacheLock.write {
                    cache.remove(hostname.lowercase())
                }
            }
            cacheMisses++
            null
        }
    }

    /**
     * Get optimized TTL for a domain based on its characteristics
     */
    private fun getOptimizedTtl(hostname: String): Long {
        val lowerHostname = hostname.lowercase()
        
        // Check if it's a popular domain (longer TTL)
        if (popularDomains.any { lowerHostname == it || lowerHostname.endsWith(".$it") }) {
            return POPULAR_DOMAIN_TTL
        }
        
        // Check if it's a dynamic domain (shorter TTL)
        if (dynamicDomainPatterns.any { it.matches(lowerHostname) }) {
            return DYNAMIC_DOMAIN_TTL
        }
        
        // Default TTL
        return DEFAULT_TTL
    }
    
    /**
     * Save DNS resolution to cache with optimized TTL
     */
    fun saveToCache(hostname: String, addresses: List<InetAddress>) {
        if (!isInitialized || cacheFile == null) {
            return
        }

        try {
            val ips = addresses.mapNotNull { it.hostAddress }
            val optimizedTtl = getOptimizedTtl(hostname)
            val entry = DnsCacheEntry(
                ips = ips,
                timestamp = System.currentTimeMillis() / 1000,
                ttl = optimizedTtl
            )

            cacheLock.write {
                // Remove oldest entries if cache is too large
                if (cache.size >= MAX_ENTRIES && !cache.containsKey(hostname.lowercase())) {
                    val oldestEntry = cache.minByOrNull { it.value.timestamp }
                    if (oldestEntry != null) {
                        cache.remove(oldestEntry.key)
                        Log.d(TAG, "Removed oldest cache entry: ${oldestEntry.key} (cache full)")
                    }
                }

                cache[hostname.lowercase()] = entry
                Log.i(TAG, "💾 DNS cache SAVED: $hostname -> $ips (TTL: ${entry.ttl}s, total entries: ${cache.size})")
            }

            // Save to file asynchronously (don't block)
            saveCacheToFile()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save DNS cache entry for $hostname", e)
        }
    }

    /**
     * Load cache from JSON file
     */
    private fun loadCacheFromFile() {
        val file = cacheFile ?: return

        if (!file.exists()) {
            Log.d(TAG, "DNS cache file does not exist, starting with empty cache")
            return
        }

        try {
            FileReader(file).use { reader ->
                val jsonContent = reader.readText()
                val jsonObject = JSONObject(jsonContent)

                val version = jsonObject.optInt("version", 0)
                if (version != CACHE_VERSION) {
                    Log.w(TAG, "DNS cache version mismatch ($version != $CACHE_VERSION), clearing cache")
                    file.delete()
                    return
                }

                val entriesObject = jsonObject.optJSONObject("entries") ?: JSONObject()
                var loadedCount = 0
                var expiredCount = 0

                cacheLock.write {
                    entriesObject.keys().forEach { hostname ->
                        try {
                            val entryObject = entriesObject.getJSONObject(hostname)
                            val ipsArray = entryObject.getJSONArray("ips")
                            val ips = mutableListOf<String>()
                            for (i in 0 until ipsArray.length()) {
                                val ip = ipsArray.optString(i, null)
                                if (ip != null) {
                                    ips.add(ip)
                                }
                            }
                            val timestamp = entryObject.getLong("timestamp")
                            val ttl = entryObject.optLong("ttl", DEFAULT_TTL)

                            val entry = DnsCacheEntry(ips, timestamp, ttl)
                            if (!entry.isExpired()) {
                                cache[hostname.lowercase()] = entry
                                loadedCount++
                            } else {
                                expiredCount++
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load cache entry for $hostname", e)
                        }
                    }
                }

                Log.d(TAG, "DNS cache loaded: $loadedCount entries valid, $expiredCount expired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load DNS cache from file", e)
            // If file is corrupted, delete it
            try {
                file.delete()
            } catch (deleteException: Exception) {
                Log.w(TAG, "Failed to delete corrupted cache file", deleteException)
            }
        }
    }

    /**
     * Save cache to JSON file
     */
    private fun saveCacheToFile() {
        val file = cacheFile ?: return

        try {
            cacheLock.read {
                val jsonObject = JSONObject()
                jsonObject.put("version", CACHE_VERSION)

                val entriesObject = JSONObject()
                cache.forEach { (hostname, entry) ->
                    try {
                        val entryObject = JSONObject()
                        val ipsArray = JSONArray()
                        entry.ips.forEach { ipsArray.put(it) }
                        entryObject.put("ips", ipsArray)
                        entryObject.put("timestamp", entry.timestamp)
                        entryObject.put("ttl", entry.ttl)
                        entriesObject.put(hostname, entryObject)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to serialize cache entry for $hostname", e)
                    }
                }

                jsonObject.put("entries", entriesObject)

                FileWriter(file).use { writer ->
                    writer.write(jsonObject.toString(2))
                }

                Log.d(TAG, "DNS cache saved to file: ${cache.size} entries")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save DNS cache to file", e)
        }
    }

    /**
     * Clean up expired entries from cache
     */
    fun cleanupExpiredEntries() {
        if (!isInitialized) {
            return
        }

        try {
            cacheLock.write {
                val expiredKeys = cache.filter { it.value.isExpired() }.keys
                expiredKeys.forEach { cache.remove(it) }
                if (expiredKeys.isNotEmpty()) {
                    Log.d(TAG, "Cleaned up ${expiredKeys.size} expired DNS cache entries")
                    saveCacheToFile()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup expired DNS cache entries", e)
        }
    }

    /**
     * Get cache statistics
     */
    fun getStats(): String {
        return "DNS Cache: ${cache.size} entries, hits=$cacheHits, misses=$cacheMisses, hitRate=${if (cacheHits + cacheMisses > 0) (cacheHits * 100.0 / (cacheHits + cacheMisses)).toInt() else 0}%"
    }

    /**
     * Clear all cache entries
     */
    fun clearCache() {
        cacheLock.write {
            cache.clear()
            cacheHits = 0L
            cacheMisses = 0L
            cacheFile?.delete()
            Log.d(TAG, "DNS cache cleared")
        }
    }
}

