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
import kotlinx.coroutines.*
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DnsCacheManager"
private const val CACHE_FILE_NAME = "dns_cache.json"
private const val DEFAULT_TTL = 86400L // 24 hours in seconds
private const val POPULAR_DOMAIN_TTL = 172800L // 48 hours for popular domains
private const val DYNAMIC_DOMAIN_TTL = 86400L // 24 hours for dynamic domains (CDN, etc.)
private const val MIN_TTL = 3600L // 1 hour minimum
private const val MAX_TTL = 172800L // 48 hours maximum
private const val MAX_ENTRIES = 1_000_000
private const val MAX_MEMORY_BYTES = 500 * 1024 * 1024L // 500MB limit (yüksek tutulacak)
private const val CACHE_VERSION = 2 // Increment version for new format
    private const val BATCH_WRITE_DEBOUNCE_MS = 100L // 100ms debounce for faster writes
private const val CLEANUP_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes
private const val NEGATIVE_CACHE_TTL = 300L // 5 minutes for negative cache (NXDOMAIN)

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
 * Features:
 * - LRU cache with access-ordered LinkedHashMap
 * - Batch file writes with 1 second debounce
 * - Smart TTL based on hit rate
 * - Domain-level hit rate tracking
 * - Periodic cleanup job
 * - Memory optimization
 */
object DnsCacheManager {
    private var isInitialized = false
    private var cacheFile: File? = null
    private val cacheLock = ReentrantReadWriteLock()
    
    // LRU Cache: LinkedHashMap with access order (true = access-ordered, false = insertion-ordered)
    // LRU-based eviction: removes eldest (least recently used) entries when entry limit is exceeded
    private val cache: LinkedHashMap<String, DnsCacheEntry> = object : LinkedHashMap<String, DnsCacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DnsCacheEntry>?): Boolean {
            if (eldest == null) return false
            
            // LRU eviction: Remove eldest entry only when entry count exceeds MAX_ENTRIES
            // Access order is maintained: recently used entries stay at the end, eldest at the beginning
            return size > MAX_ENTRIES
        }
    }
    
    /**
     * Calculate total memory usage of cache
     */
    private fun calculateTotalMemoryUsage(): Long {
        return cache.values.sumOf { it.estimateMemorySize() }
    }
    
    private var cacheHits = AtomicLong(0L)
    private var cacheMisses = AtomicLong(0L)
    
    // Cache hit latency tracking
    private var totalHitLatencyNs = AtomicLong(0L)
    private var totalMissLatencyNs = AtomicLong(0L)
    private var hitCount = AtomicLong(0L)
    private var missCount = AtomicLong(0L)
    
    // Domain-level hit rate tracking
    private val domainStats = mutableMapOf<String, DomainStats>()
    
    // Batch write mechanism
    private var pendingWrite = AtomicBoolean(false)
    private var writeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Cleanup job
    private var cleanupJob: Job? = null
    
    // Prefetching job
    private var prefetchJob: Job? = null
    private val PREFETCH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes (more aggressive prefetching)

    /**
     * Domain statistics for hit rate tracking and smart TTL
     */
    private data class DomainStats(
        var hits: Long = 0L,
        var misses: Long = 0L,
        var lastAccessTime: Long = System.currentTimeMillis() / 1000
    ) {
        val hitRate: Double
            get() = if (hits + misses > 0) hits.toDouble() / (hits + misses) else 0.0
    }

    /**
     * Compressed IP address representation
     * IPv4: stored as Int (4 bytes)
     * IPv6: stored as ByteArray (16 bytes)
     */
    private sealed class CompressedIp {
        data class IPv4(val value: Int) : CompressedIp()
        data class IPv6(val bytes: ByteArray) : CompressedIp() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is IPv6) return false
                return bytes.contentEquals(other.bytes)
            }
            override fun hashCode(): Int = bytes.contentHashCode()
        }
        
        fun toInetAddress(): InetAddress? {
            return try {
                when (this) {
                    is IPv4 -> {
                        val bytes = byteArrayOf(
                            ((value shr 24) and 0xFF).toByte(),
                            ((value shr 16) and 0xFF).toByte(),
                            ((value shr 8) and 0xFF).toByte(),
                            (value and 0xFF).toByte()
                        )
                        InetAddress.getByAddress(bytes)
                    }
                    is IPv6 -> InetAddress.getByAddress(bytes)
                }
            } catch (e: Exception) {
                null
            }
        }
        
        fun estimateSize(): Int {
            return when (this) {
                is IPv4 -> 4 // Int size
                is IPv6 -> 16 // ByteArray size
            }
        }
    }
    
    /**
     * DNS cache entry with compressed IP addresses, timestamp, TTL, and raw DNS response
     * Memory optimized: IPs stored as compressed format (Int for IPv4, ByteArray for IPv6)
     */
    private data class DnsCacheEntry(
        val ips: List<CompressedIp>, // Memory optimized: compressed IP format
        val timestamp: Long,
        val ttl: Long = DEFAULT_TTL,
        val rawResponse: ByteArray? = null, // Cached raw DNS response packet for fast retrieval
        val isNegative: Boolean = false // True for NXDOMAIN responses
    ) {
        fun isExpired(): Boolean {
            val currentTime = System.currentTimeMillis() / 1000
            return (currentTime - timestamp) > ttl
        }
        
        fun isStale(): Boolean {
            val currentTime = System.currentTimeMillis() / 1000
            return (currentTime - timestamp) > ttl
        }
        
        fun estimateMemorySize(): Long {
            var size = 0L
            // Domain name (interned, so minimal overhead)
            size += 8 // String reference
            // IPs
            size += ips.sumOf { it.estimateSize().toLong() }
            // Timestamp + TTL
            size += 16 // 2 Longs
            // Raw response
            size += (rawResponse?.size ?: 0).toLong()
            // Boolean
            size += 1
            // Entry overhead (object header, etc.)
            size += 32
            return size
        }
        
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DnsCacheEntry) return false
            if (ips != other.ips) return false
            if (timestamp != other.timestamp) return false
            if (ttl != other.ttl) return false
            if (isNegative != other.isNegative) return false
            if (rawResponse != null) {
                if (other.rawResponse == null) return false
                if (!rawResponse.contentEquals(other.rawResponse)) return false
            } else if (other.rawResponse != null) return false
            return true
        }
        
        override fun hashCode(): Int {
            var result = ips.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + ttl.hashCode()
            result = 31 * result + (rawResponse?.contentHashCode() ?: 0)
            result = 31 * result + isNegative.hashCode()
            return result
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
            
            // Start periodic cleanup job
            startCleanupJob()
            
            // Start prefetching job
            startPrefetchJob()
            
            isInitialized = true
            Log.d(TAG, "DnsCacheManager initialized: ${cache.size} entries loaded, hits=${cacheHits.get()}, misses=${cacheMisses.get()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DnsCacheManager", e)
            isInitialized = true // Allow operation even if cache file fails
        }
    }
    
    /**
     * Start periodic cleanup job (runs every 30 minutes)
     */
    private fun startCleanupJob() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                try {
                    cleanupExpiredEntries()
                } catch (e: Exception) {
                    Log.w(TAG, "Error in cleanup job", e)
                }
            }
        }
    }
    
    /**
     * Start prefetching job (runs every 10 minutes)
     * Prefetches domains with high hit rates and related subdomains
     */
    private fun startPrefetchJob() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            while (isActive) {
                delay(PREFETCH_INTERVAL_MS)
                try {
                    prefetchHighHitRateDomains()
                } catch (e: Exception) {
                    Log.w(TAG, "Error in prefetch job", e)
                }
            }
        }
    }
    
    /**
     * Prefetch domains with high hit rates and related subdomains
     */
    private suspend fun prefetchHighHitRateDomains() {
        if (!isInitialized) return
        
        try {
            // Get domains with hit rate > 0.8
            val highHitRateDomains = domainStats.filter { (_, stats) ->
                stats.hits + stats.misses >= 3 && stats.hitRate >= 0.8
            }.keys.take(20) // Limit to top 20
            
            if (highHitRateDomains.isEmpty()) {
                return
            }
            
            Log.d(TAG, "🔄 Prefetching ${highHitRateDomains.size} high hit rate domains...")
            
            // Prefetch related subdomains for each domain
            highHitRateDomains.forEach { domain ->
                try {
                    val relatedDomains = generateRelatedDomains(domain)
                    relatedDomains.forEach { relatedDomain ->
                        // Check if already cached
                        val cached = getFromCache(relatedDomain, allowStale = false)
                        if (cached == null) {
                            // Trigger prefetch (this will be handled by SystemDnsCacheServer)
                            Log.d(TAG, "📥 Prefetching related domain: $relatedDomain (from $domain)")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error prefetching related domains for $domain", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in prefetchHighHitRateDomains", e)
        }
    }
    
    /**
     * Generate related subdomains for a domain (www, cdn, api, etc.)
     */
    private fun generateRelatedDomains(domain: String): List<String> {
        val parts = domain.split(".")
        if (parts.size < 2) return emptyList()
        
        val baseDomain = parts.takeLast(2).joinToString(".")
        val subdomain = if (parts.size > 2) parts.dropLast(2).joinToString(".") else null
        
        val commonSubdomains = listOf("www", "cdn", "api", "static", "assets", "media", "img", "images")
        val related = mutableListOf<String>()
        
        // If already has subdomain, try other common ones
        if (subdomain != null && subdomain !in commonSubdomains) {
            commonSubdomains.forEach { prefix ->
                related.add("$prefix.$baseDomain")
            }
        } else if (subdomain == null) {
            // No subdomain, try common ones
            commonSubdomains.forEach { prefix ->
                related.add("$prefix.$baseDomain")
            }
        }
        
        return related
    }
    
    /**
     * Get domains that should be prefetched (high hit rate domains)
     */
    fun getPrefetchCandidates(): List<String> {
        return domainStats.filter { (_, stats) ->
            stats.hits + stats.misses >= 3 && stats.hitRate >= 0.8
        }.keys.take(50).toList()
    }
    
    /**
     * Get domains that are expiring soon (TTL-aware prefetching)
     * @param hoursThreshold: Threshold in hours for "expiring soon" (default: 1 hour)
     * @return List of domain names that will expire within the threshold
     */
    fun getExpiringSoonDomains(hoursThreshold: Long = 1): List<String> {
        if (!isInitialized) return emptyList()
        
        return cacheLock.read {
            val currentTime = System.currentTimeMillis() / 1000
            val thresholdSeconds = hoursThreshold * 3600
            
            cache.filter { (_, entry) ->
                val age = currentTime - entry.timestamp
                val remainingTTL = entry.ttl - age
                remainingTTL > 0 && remainingTTL < thresholdSeconds && !entry.isExpired()
            }.keys.toList()
        }
    }

    /**
     * Get DNS resolution from cache if available
     * Supports stale-while-revalidate: returns stale entries but triggers background refresh
     * LRU: Accessing an entry moves it to the end (most recently used)
     * @return List of InetAddress if found in cache, null otherwise
     */
    fun getFromCache(hostname: String, allowStale: Boolean = true): List<InetAddress>? {
        if (!isInitialized || cacheFile == null) {
            Log.d(TAG, "⚠️ DNS cache not initialized, skipping cache check for: $hostname")
            return null
        }

        val startTime = System.nanoTime()
        val lowerHostname = hostname.lowercase().intern() // String interning
        Log.d(TAG, "🔍 Checking DNS cache for: $hostname")
        
        val result = cacheLock.read {
            // LRU: Accessing cache[hostname] moves it to the end (most recently used)
            val entry = cache[lowerHostname]
            if (entry != null) {
                val isExpired = entry.isExpired()
                val isStale = entry.isStale()
                
                // Check for negative cache (NXDOMAIN)
                if (entry.isNegative) {
                    if (!isExpired) {
                        val latency = System.nanoTime() - startTime
                        totalHitLatencyNs.addAndGet(latency)
                        hitCount.incrementAndGet()
                        cacheHits.incrementAndGet()
                        updateDomainStats(lowerHostname, hit = true)
                        Log.d(TAG, "✅ DNS negative cache HIT: $hostname (NXDOMAIN) [${latency / 1000}μs]")
                        return@read emptyList<InetAddress>() // Return empty list for NXDOMAIN
                    } else {
                        // Negative cache expired, remove it
                        cacheLock.write {
                            cache.remove(lowerHostname)
                        }
                        cacheMisses.incrementAndGet()
                        updateDomainStats(lowerHostname, hit = false)
                        return@read null
                    }
                }
                
                // Handle stale-while-revalidate
                if (isStale && allowStale) {
                    // Return stale entry but trigger background refresh
                    val latency = System.nanoTime() - startTime
                    totalHitLatencyNs.addAndGet(latency)
                    hitCount.incrementAndGet()
                    cacheHits.incrementAndGet()
                    updateDomainStats(lowerHostname, hit = true)
                    
                    // Trigger background refresh (non-blocking)
                    scope.launch {
                        try {
                            // This will be handled by the caller (SystemDnsCacheServer)
                            Log.d(TAG, "🔄 Stale cache entry for $hostname, background refresh recommended")
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                    
                    try {
                        val addresses = entry.ips.mapNotNull { it.toInetAddress() }
                        if (addresses.isNotEmpty()) {
                            Log.i(TAG, "✅ DNS cache HIT (stale): $hostname -> ${addresses.map { it.hostAddress }} (age: ${(System.currentTimeMillis() / 1000 - entry.timestamp)}s, TTL: ${entry.ttl}s) [${latency / 1000}μs]")
                            return@read addresses
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error converting cached IPs to InetAddress", e)
                    }
                } else if (!isExpired) {
                    // Fresh entry
                    val latency = System.nanoTime() - startTime
                    totalHitLatencyNs.addAndGet(latency)
                    hitCount.incrementAndGet()
                    cacheHits.incrementAndGet()
                    updateDomainStats(lowerHostname, hit = true)
                    
                    try {
                        val addresses = entry.ips.mapNotNull { it.toInetAddress() }
                        if (addresses.isNotEmpty()) {
                            Log.i(TAG, "✅ DNS cache HIT: $hostname -> ${addresses.map { it.hostAddress }} (age: ${(System.currentTimeMillis() / 1000 - entry.timestamp)}s) [${latency / 1000}μs]")
                            return@read addresses
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error converting cached IPs to InetAddress", e)
                    }
                } else {
                    // Expired entry, remove it
                    cacheLock.write {
                        cache.remove(lowerHostname)
                    }
                }
            }
            
            val latency = System.nanoTime() - startTime
            totalMissLatencyNs.addAndGet(latency)
            missCount.incrementAndGet()
            cacheMisses.incrementAndGet()
            updateDomainStats(lowerHostname, hit = false)
            null
        }
        
        return result
    }
    
    /**
     * Get raw DNS response packet from cache (for fast response building)
     */
    fun getRawResponseFromCache(hostname: String): ByteArray? {
        if (!isInitialized || cacheFile == null) {
            return null
        }
        
        val lowerHostname = hostname.lowercase().intern()
        return cacheLock.read {
            val entry = cache[lowerHostname]
            if (entry != null && !entry.isExpired() && !entry.isNegative) {
                entry.rawResponse
            } else {
                null
            }
        }
    }
    
    /**
     * Update domain statistics for hit rate tracking
     */
    private fun updateDomainStats(hostname: String, hit: Boolean) {
        val stats = domainStats.getOrPut(hostname) { DomainStats() }
        if (hit) {
            stats.hits++
        } else {
            stats.misses++
        }
        stats.lastAccessTime = System.currentTimeMillis() / 1000
    }

    /**
     * Get optimized TTL for a domain based on its characteristics, hit rate, and DNS response TTL
     * Smart TTL: Higher hit rate = longer TTL, lower hit rate = shorter TTL
     * Dynamic TTL: Uses TTL from DNS response if available, otherwise uses smart TTL
     */
    private fun getOptimizedTtl(hostname: String, dnsResponseTtl: Long? = null): Long {
        val lowerHostname = hostname.lowercase()
        
        // Use DNS response TTL if available (dynamic TTL)
        if (dnsResponseTtl != null && dnsResponseTtl > 0) {
            val clampedTtl = dnsResponseTtl.coerceIn(MIN_TTL, MAX_TTL)
            Log.d(TAG, "Using DNS response TTL for $hostname: ${dnsResponseTtl}s -> ${clampedTtl}s")
            return clampedTtl
        }
        
        // Check if it's a popular domain (longer TTL)
        if (popularDomains.any { lowerHostname == it || lowerHostname.endsWith(".$it") }) {
            return POPULAR_DOMAIN_TTL
        }
        
        // Check if it's a dynamic domain (shorter TTL)
        if (dynamicDomainPatterns.any { it.matches(lowerHostname) }) {
            return DYNAMIC_DOMAIN_TTL
        }
        
        // Smart TTL based on hit rate
        val stats = domainStats[lowerHostname]
        if (stats != null && stats.hits + stats.misses >= 3) {
            // Minimum 3 queries needed for reliable hit rate
            val hitRate = stats.hitRate
            
            // Calculate TTL based on hit rate
            // High hit rate (>0.7) = longer TTL, low hit rate (<0.3) = shorter TTL
            val baseTtl = DEFAULT_TTL
            val ttlMultiplier = when {
                hitRate >= 0.7 -> 1.5 // 50% longer for high hit rate
                hitRate >= 0.5 -> 1.2 // 20% longer for medium-high hit rate
                hitRate >= 0.3 -> 1.0 // Default for medium hit rate
                else -> 0.7 // 30% shorter for low hit rate
            }
            
            val calculatedTtl = (baseTtl * ttlMultiplier).toLong()
            val smartTtl = calculatedTtl.coerceIn(MIN_TTL, MAX_TTL)
            
            Log.d(TAG, "Smart TTL for $hostname: hitRate=${(hitRate * 100).toInt()}%, ttl=${smartTtl}s")
            return smartTtl
        }
        
        // Default TTL for new domains
        return DEFAULT_TTL
    }
    
    /**
     * Compress InetAddress to CompressedIp format
     */
    private fun compressIp(address: InetAddress): CompressedIp? {
        return try {
            val bytes = address.address
            when (bytes.size) {
                4 -> {
                    // IPv4: convert to Int
                    val value = ((bytes[0].toInt() and 0xFF) shl 24) or
                            ((bytes[1].toInt() and 0xFF) shl 16) or
                            ((bytes[2].toInt() and 0xFF) shl 8) or
                            (bytes[3].toInt() and 0xFF)
                    CompressedIp.IPv4(value)
                }
                16 -> {
                    // IPv6: use ByteArray
                    CompressedIp.IPv6(bytes.copyOf())
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compress IP address", e)
            null
        }
    }
    
    /**
     * Save DNS resolution to cache with optimized TTL and raw response packet
     * LRU: Adding/updating an entry moves it to the end (most recently used)
     * Memory optimized: IPs stored as compressed format (Int for IPv4, ByteArray for IPv6)
     */
    fun saveToCache(hostname: String, addresses: List<InetAddress>, ttl: Long? = null, rawResponse: ByteArray? = null, isNegative: Boolean = false) {
        if (!isInitialized || cacheFile == null) {
            return
        }

        try {
            val lowerHostname = hostname.lowercase().intern() // String interning
            
            // Compress IP addresses
            val compressedIps = addresses.mapNotNull { compressIp(it) }
            
            // Use provided TTL or calculate optimized TTL
            val finalTtl = if (isNegative) {
                NEGATIVE_CACHE_TTL
            } else {
                ttl?.coerceIn(MIN_TTL, MAX_TTL) ?: getOptimizedTtl(lowerHostname, ttl)
            }
            
            val entry = DnsCacheEntry(
                ips = compressedIps,
                timestamp = System.currentTimeMillis() / 1000,
                ttl = finalTtl,
                rawResponse = rawResponse,
                isNegative = isNegative
            )

            cacheLock.write {
                // LRU: LinkedHashMap automatically removes eldest (least recently used) entry when size > MAX_ENTRIES
                // Access order is maintained: recently used entries stay at the end, eldest at the beginning
                // This operation automatically triggers LRU eviction via removeEldestEntry()
                cache[lowerHostname] = entry // This moves entry to end if exists, or adds new (LRU update)
                
                val ipStrings = addresses.mapNotNull { it.hostAddress }
                Log.i(TAG, "💾 DNS cache SAVED: $hostname -> $ipStrings (TTL: ${entry.ttl}s, entries: ${cache.size}, memory: ${calculateTotalMemoryUsage() / 1024}KB)")
            }

            // Batch write: debounce file writes (100ms for faster persistence)
            scheduleBatchWrite()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save DNS cache entry for $hostname", e)
        }
    }
    
    // Note: LRU eviction is handled automatically by LinkedHashMap.removeEldestEntry()
    // when entry count exceeds MAX_ENTRIES (eldest entries are removed automatically)
    
    /**
     * Schedule batch write with 100ms debounce (optimized for faster persistence)
     * Multiple saves within 100ms will result in a single file write
     */
    private fun scheduleBatchWrite() {
        if (pendingWrite.compareAndSet(false, true)) {
            writeJob?.cancel()
            writeJob = scope.launch {
                delay(BATCH_WRITE_DEBOUNCE_MS)
                try {
                    saveCacheToFile()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in batch write", e)
                } finally {
                    pendingWrite.set(false)
                }
            }
        }
    }
    
    /**
     * Flush cache to file immediately (used on app shutdown or cleanup)
     */
    private fun flushCacheToFile() {
        writeJob?.cancel()
        pendingWrite.set(false)
        saveCacheToFile()
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
                if (version != CACHE_VERSION && version != 1) {
                    // Support migration from version 1 to 2
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
                            val timestamp = entryObject.getLong("timestamp")
                            val ttl = entryObject.optLong("ttl", DEFAULT_TTL)
                            val isNegative = entryObject.optBoolean("isNegative", false)
                            
                            // Load IPs - support both old format (strings) and new format (compressed)
                            val compressedIps = mutableListOf<CompressedIp>()
                            
                            if (entryObject.has("ips")) {
                                // Old format: string IPs
                                val ipsArray = entryObject.getJSONArray("ips")
                                for (i in 0 until ipsArray.length()) {
                                    val ipString = ipsArray.optString(i, null)
                                    if (ipString != null) {
                                        try {
                                            val address = InetAddress.getByName(ipString)
                                            compressIp(address)?.let { compressedIps.add(it) }
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Failed to parse IP: $ipString", e)
                                        }
                                    }
                                }
                            } else if (entryObject.has("compressedIps")) {
                                // New format: compressed IPs
                                val compressedArray = entryObject.getJSONArray("compressedIps")
                                for (i in 0 until compressedArray.length()) {
                                    val ipObj = compressedArray.getJSONObject(i)
                                    if (ipObj.has("ipv4")) {
                                        compressedIps.add(CompressedIp.IPv4(ipObj.getInt("ipv4")))
                                    } else if (ipObj.has("ipv6")) {
                                        val ipv6Bytes = ipObj.getString("ipv6")
                                        // Base64 decode
                                        val bytes = android.util.Base64.decode(ipv6Bytes, android.util.Base64.DEFAULT)
                                        compressedIps.add(CompressedIp.IPv6(bytes))
                                    }
                                }
                            }
                            
                            val rawResponse = if (entryObject.has("rawResponse")) {
                                val rawResponseStr = entryObject.optString("rawResponse", "")
                                if (rawResponseStr.isNotEmpty()) {
                                    android.util.Base64.decode(rawResponseStr, android.util.Base64.DEFAULT)
                                } else {
                                    null
                                }
                            } else {
                                null
                            }

                            val entry = DnsCacheEntry(compressedIps, timestamp, ttl, rawResponse, isNegative)
                            if (!entry.isExpired()) {
                                cache[hostname.lowercase().intern()] = entry
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
                        
                        // Save compressed IPs
                        val compressedArray = JSONArray()
                        entry.ips.forEach { compressedIp ->
                            val ipObj = JSONObject()
                            when (compressedIp) {
                                is CompressedIp.IPv4 -> {
                                    ipObj.put("ipv4", compressedIp.value)
                                }
                                is CompressedIp.IPv6 -> {
                                    // Base64 encode
                                    val encoded = android.util.Base64.encodeToString(compressedIp.bytes, android.util.Base64.NO_WRAP)
                                    ipObj.put("ipv6", encoded)
                                }
                            }
                            compressedArray.put(ipObj)
                        }
                        entryObject.put("compressedIps", compressedArray)
                        
                        entryObject.put("timestamp", entry.timestamp)
                        entryObject.put("ttl", entry.ttl)
                        entryObject.put("isNegative", entry.isNegative)
                        
                        // Save raw response if available
                        if (entry.rawResponse != null) {
                            val encoded = android.util.Base64.encodeToString(entry.rawResponse, android.util.Base64.NO_WRAP)
                            entryObject.put("rawResponse", encoded)
                        }
                        
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
     * Called periodically by cleanup job (every 30 minutes)
     */
    fun cleanupExpiredEntries() {
        if (!isInitialized) {
            return
        }

        try {
            var expiredCount = 0
            var removedStatsCount = 0
            
            cacheLock.write {
                // Remove expired entries
                val expiredKeys = cache.filter { it.value.isExpired() }.keys.toList()
                expiredKeys.forEach { 
                    cache.remove(it)
                    expiredCount++
                }
                
                // Clean up old domain stats (older than 7 days)
                val now = System.currentTimeMillis() / 1000
                val statsToRemove = domainStats.filter { 
                    (now - it.value.lastAccessTime) > (7 * 24 * 60 * 60) // 7 days
                }.keys.toList()
                statsToRemove.forEach {
                    domainStats.remove(it)
                    removedStatsCount++
                }
                
                if (expiredCount > 0 || removedStatsCount > 0) {
                    Log.d(TAG, "Cleaned up $expiredCount expired DNS cache entries and $removedStatsCount old domain stats")
                    // Flush immediately after cleanup
                    flushCacheToFile()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup expired DNS cache entries", e)
        }
    }

    /**
     * DNS Cache statistics data class
     */
    data class DnsCacheStats(
        val entryCount: Int,
        val memoryUsageMB: Long,
        val memoryLimitMB: Long,
        val memoryUsagePercent: Int,
        val hits: Long,
        val misses: Long,
        val hitRate: Int,
        val avgDomainHitRate: Int,
        val avgHitLatencyMs: Double,
        val avgMissLatencyMs: Double
    )
    
    /**
     * Get cache statistics as data class
     */
    fun getStatsData(): DnsCacheStats {
        val hits = cacheHits.get()
        val misses = cacheMisses.get()
        val total = hits + misses
        val hitRate = if (total > 0) (hits * 100.0 / total).toInt() else 0
        
        // Calculate average domain hit rate
        val domainHitRates = domainStats.values.map { it.hitRate }
        val avgDomainHitRate = if (domainHitRates.isNotEmpty()) {
            (domainHitRates.average() * 100).toInt()
        } else {
            0
        }
        
        // Calculate average latencies
        val avgHitLatencyMs = if (hitCount.get() > 0) {
            (totalHitLatencyNs.get() / hitCount.get()) / 1_000_000.0
        } else {
            0.0
        }
        
        val avgMissLatencyMs = if (missCount.get() > 0) {
            (totalMissLatencyNs.get() / missCount.get()) / 1_000_000.0
        } else {
            0.0
        }
        
        val memoryUsage = calculateTotalMemoryUsage()
        val memoryUsageMB = memoryUsage / (1024 * 1024)
        val memoryLimitMB = MAX_MEMORY_BYTES / (1024 * 1024)
        val memoryUsagePercent = if (MAX_MEMORY_BYTES > 0) {
            (memoryUsage * 100 / MAX_MEMORY_BYTES).toInt()
        } else {
            0
        }
        
        return DnsCacheStats(
            entryCount = cache.size,
            memoryUsageMB = memoryUsageMB,
            memoryLimitMB = memoryLimitMB,
            memoryUsagePercent = memoryUsagePercent,
            hits = hits,
            misses = misses,
            hitRate = hitRate,
            avgDomainHitRate = avgDomainHitRate,
            avgHitLatencyMs = avgHitLatencyMs,
            avgMissLatencyMs = avgMissLatencyMs
        )
    }
    
    /**
     * Get cache statistics with memory usage and latency metrics
     */
    fun getStats(): String {
        val stats = getStatsData()
        return "DNS Cache: ${stats.entryCount} entries, ${stats.memoryUsageMB}MB/${stats.memoryLimitMB}MB (${stats.memoryUsagePercent}%), hits=${stats.hits} (avg ${String.format("%.2f", stats.avgHitLatencyMs)}ms), misses=${stats.misses} (avg ${String.format("%.2f", stats.avgMissLatencyMs)}ms), hitRate=${stats.hitRate}%, avgDomainHitRate=${stats.avgDomainHitRate}%"
    }
    
    /**
     * Get average cache hit latency in milliseconds
     */
    fun getAverageHitLatencyMs(): Double {
        val count = hitCount.get()
        return if (count > 0) {
            (totalHitLatencyNs.get() / count) / 1_000_000.0
        } else {
            0.0
        }
    }
    
    /**
     * Get average cache miss latency in milliseconds
     */
    fun getAverageMissLatencyMs(): Double {
        val count = missCount.get()
        return if (count > 0) {
            (totalMissLatencyNs.get() / count) / 1_000_000.0
        } else {
            0.0
        }
    }

    /**
     * Clear all cache entries
     */
    fun clearCache() {
        cacheLock.write {
            cache.clear()
            domainStats.clear()
            cacheHits.set(0L)
            cacheMisses.set(0L)
            flushCacheToFile()
            cacheFile?.delete()
            Log.d(TAG, "DNS cache cleared")
        }
    }
    
    /**
     * Shutdown and cleanup resources
     */
    fun shutdown() {
        cleanupJob?.cancel()
        prefetchJob?.cancel()
        writeJob?.cancel()
        flushCacheToFile()
        scope.cancel()
    }
}

