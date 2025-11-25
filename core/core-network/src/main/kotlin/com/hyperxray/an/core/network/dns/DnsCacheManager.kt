package com.hyperxray.an.core.network.dns

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "DnsCacheManager"
private const val CACHE_FILE_NAME = "dns_cache.json"
private const val DEFAULT_TTL = 86400L // 24 hours in seconds
private const val POPULAR_DOMAIN_TTL = 172800L // 48 hours for popular domains
private const val DYNAMIC_DOMAIN_TTL = 86400L // 24 hours for dynamic domains (CDN, etc.)
private const val MAX_ENTRIES = 10000
private const val CACHE_VERSION = 1
private const val CACHE_DEBOUNCE_MS = 200L // Wait 200ms after last write before saving to disk (minimum debounce)
private const val AVERAGE_ENTRY_SIZE_BYTES = 100L // Approximate memory per cache entry (hostname + IPs + metadata)
private const val MEMORY_LIMIT_MB = 10L // Maximum cache memory limit in MB
private const val MOVING_AVERAGE_ALPHA = 0.1 // Exponential moving average factor for latency (0.0-1.0)
private const val TOP_DOMAIN_COUNT = 10 // Number of top domains to track for hit rate

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
    
    // Thread-safe atomic counters for statistics
    private val totalLookups = AtomicLong(0L)
    private val cacheHits = AtomicLong(0L)
    private val cacheMisses = AtomicLong(0L)
    
    // TTL tracking for average calculation
    private val totalTtlSeconds = AtomicLong(0L)
    private val ttlSampleCount = AtomicLong(0L)
    
    // Latency tracking using exponential moving average (prevents overflow)
    // avgHitLatency: Average latency for cache hits (in milliseconds)
    // avgMissLatency: Average latency for cache misses (in milliseconds)
    @Volatile
    private var avgHitLatency = 0.0
    @Volatile
    private var avgMissLatency = 0.0
    
    // Domain-specific hit tracking (top N most accessed domains)
    private val domainHits = mutableMapOf<String, AtomicLong>()
    private val domainMisses = mutableMapOf<String, AtomicLong>()
    private val domainStatsLock = ReentrantReadWriteLock()
    
    // StateFlow for real-time metrics updates
    private val _dashboardStats = MutableStateFlow<DnsCacheMetrics>(
        DnsCacheMetrics(
            entryCount = 0,
            totalLookups = 0L,
            hits = 0L,
            misses = 0L,
            hitRate = 0,
            memoryUsageBytes = 0L,
            memoryLimitBytes = MEMORY_LIMIT_MB * 1024 * 1024,
            memoryUsagePercent = 0,
            avgHitLatencyMs = 0.0,
            avgMissLatencyMs = 0.0,
            avgDomainHitRate = 0,
            topDomains = emptyList(),
            avgTtlSeconds = 0L,
            activeEntries = emptyList()
        )
    )
    
    // Public StateFlow for dashboard consumption
    val dashboardStats: StateFlow<DnsCacheMetrics> = _dashboardStats.asStateFlow()
    
    // Metrics update job
    private var metricsUpdateJob: Job? = null
    private val metricsScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val metricsJobMutex = Mutex() // Thread-safe job management
    
    // Debounced cache I/O: Channel for triggering saves, Flow for debouncing
    private val saveTriggerChannel = Channel<Unit>(Channel.UNLIMITED)
    private var saveJob: Job? = null
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * DNS cache entry UI model for displaying cached records
     */
    data class DnsCacheEntryUiModel(
        val domain: String,
        val ips: List<String>,
        val expiryTime: Long // Unix timestamp in seconds when entry expires
    )
    
    /**
     * Comprehensive DNS cache metrics data class
     * Contains all statistics needed for dashboard display
     */
    data class DnsCacheMetrics(
        val entryCount: Int,
        val totalLookups: Long,
        val hits: Long,
        val misses: Long,
        val hitRate: Int, // 0-100 percentage
        val memoryUsageBytes: Long,
        val memoryLimitBytes: Long,
        val memoryUsagePercent: Int, // 0-100 percentage
        val avgHitLatencyMs: Double,
        val avgMissLatencyMs: Double,
        val avgDomainHitRate: Int, // 0-100 percentage (average hit rate across top domains)
        val topDomains: List<DomainHitRate>, // Top N domains by access count
        val avgTtlSeconds: Long, // Average TTL of cached entries
        val activeEntries: List<DnsCacheEntryUiModel> // Top 100 most recently updated entries
    )
    
    /**
     * Domain hit rate information
     */
    data class DomainHitRate(
        val domain: String,
        val hits: Long,
        val misses: Long,
        val hitRate: Int // 0-100 percentage
    )
    
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
        
        /**
         * Estimate memory usage for this entry
         * Includes: hostname string, IP addresses, metadata overhead
         */
        fun estimateMemoryBytes(hostname: String): Long {
            val hostnameSize = hostname.length * 2L // UTF-16 encoding
            val ipsSize = ips.sumOf { it.length * 2L } // Each IP as string
            val metadataOverhead = 64L // Timestamp, TTL, map overhead, etc.
            return hostnameSize + ipsSize + metadataOverhead
        }
    }

    /**
     * Initialize the DNS cache manager with application context
     * 
     * CRITICAL FIX: Debounced Cache I/O
     * - Starts debounced save job that waits 200ms after last write
     * - Batches disk writes instead of writing on every lookup
     * - Prevents blocking I/O on every DNS resolution
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.d(TAG, "DnsCacheManager already initialized, ensuring metrics job is running...")
            // Ensure metrics job is running even if already initialized
            // This handles cases where the job was cancelled or failed
            try {
                CoroutineScope(Dispatchers.Default).launch {
                    ensureMetricsJobRunning()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to ensure metrics job running", e)
            }
            return
        }

        try {
            cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
            loadCacheFromFile()
            
            // Start debounced save job
            startDebouncedSaveJob()
            
            // Start metrics update job (updates StateFlow periodically)
            startMetricsUpdateJob()
            
            // Update metrics immediately on initialization
            updateMetrics()
            
            isInitialized = true
            Log.i(TAG, "✅ DnsCacheManager initialized: ${cache.size} entries loaded, hits=${cacheHits.get()}, misses=${cacheMisses.get()}, metrics job started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DnsCacheManager", e)
            isInitialized = true // Allow operation even if cache file fails
        }
    }
    
    /**
     * Start debounced save job that batches disk writes
     * Waits 200ms after the last save trigger before writing to disk
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startDebouncedSaveJob() {
        saveJob?.cancel()
        saveJob = saveScope.launch {
            saveTriggerChannel.receiveAsFlow()
                .debounce(CACHE_DEBOUNCE_MS)
                .collect {
                    // Debounce period elapsed, save to disk
                    saveCacheToFileSync()
                }
        }
    }
    
    /**
     * Start metrics update job that periodically updates the StateFlow
     * Updates every 500ms to provide real-time dashboard updates
     */
    private fun startMetricsUpdateJob() {
        metricsUpdateJob?.cancel()
        metricsUpdateJob = metricsScope.launch {
            Log.i(TAG, "📊 Metrics update job started (updates every 500ms)")
            var updateCount = 0
            while (isActive) {
                try {
                    updateMetrics()
                    updateCount++
                    // Log every 10 updates (every 5 seconds) for visibility
                    if (updateCount % 10 == 0) {
                        val metrics = _dashboardStats.value
                        Log.d(TAG, "📊 Metrics update #$updateCount: entries=${metrics.entryCount}, hits=${metrics.hits}, misses=${metrics.misses}, hitRate=${metrics.hitRate}%")
                    }
                    delay(500) // Update every 500ms
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating metrics", e)
                    delay(1000) // Wait longer on error
                }
            }
            Log.i(TAG, "📊 Metrics update job stopped (total updates: $updateCount)")
        }
    }
    
    /**
     * Ensure metrics update job is running
     * Checks if the job is active and restarts it if needed
     * This is thread-safe and can be called from any coroutine context
     * 
     * CRITICAL: This function must be called when UI resubscribes to ensure
     * the producer job is running even if it was cancelled or failed
     */
    suspend fun ensureMetricsJobRunning() {
        metricsJobMutex.withLock {
            val job = metricsUpdateJob
            if (job == null || !job.isActive) {
                Log.w(TAG, "⚠️ Metrics job is not running, restarting... (wasActive=${job?.isActive}, wasNull=${job == null})")
                startMetricsUpdateJob()
                Log.i(TAG, "✅ Metrics job restarted successfully")
            } else {
                Log.d(TAG, "✅ Metrics job is already running (isActive=${job.isActive})")
            }
        }
    }
    
    /**
     * Update metrics and emit to StateFlow
     * Calculates all statistics including memory usage, latency, and domain hit rates
     * This function is called periodically by the metrics update job
     */
    private fun updateMetrics() {
        val (hits, misses, total, entryCount, memoryUsageBytes, activeEntriesList) = cacheLock.read {
            val h = cacheHits.get()
            val m = cacheMisses.get()
            val t = totalLookups.get()
            val ec = cache.size
            val mem = cache.entries.sumOf { (hostname, entry) ->
                entry.estimateMemoryBytes(hostname)
            }
            
            // Create snapshot of top 100 most recently updated entries (sorted by timestamp descending)
            // Performance constraint: Limit to 100 entries to prevent UI lag
            val entries = cache.entries
                .sortedByDescending { it.value.timestamp } // Most recent first
                .take(100) // Limit to top 100
                .map { (hostname, entry) ->
                    val currentTime = System.currentTimeMillis() / 1000
                    val expiryTime = entry.timestamp + entry.ttl
                    DnsCacheEntryUiModel(
                        domain = hostname,
                        ips = entry.ips,
                        expiryTime = expiryTime
                    )
                }
            
            Sextet<Long, Long, Long, Int, Long, List<DnsCacheEntryUiModel>>(h, m, t, ec, mem, entries)
        }
        
        // Calculate hit rate using Double to avoid integer division
        val hitRate = if (total > 0) {
            ((hits.toDouble() / total) * 100).toInt()
        } else {
            0
        }
        
        // Calculate average TTL
        val ttlCount = ttlSampleCount.get()
        val avgTtlSeconds = if (ttlCount > 0) {
            totalTtlSeconds.get() / ttlCount
        } else {
            0L
        }
        
        // Calculate memory usage
        val memoryLimitBytes = MEMORY_LIMIT_MB * 1024 * 1024
        val memoryUsagePercent = if (memoryLimitBytes > 0) {
            ((memoryUsageBytes * 100.0) / memoryLimitBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
        
        // Get top domains by access count
        val (topDomains, avgDomainHitRate) = domainStatsLock.read {
            val domainStats = (domainHits.keys + domainMisses.keys).distinct().mapNotNull { domain ->
                val domainHitsCount = domainHits[domain]?.get() ?: 0L
                val domainMissesCount = domainMisses[domain]?.get() ?: 0L
                val domainTotal = domainHitsCount + domainMissesCount
                if (domainTotal > 0) {
                    val domainHitRate = (domainHitsCount * 100.0 / domainTotal).toInt()
                    DomainHitRate(
                        domain = domain,
                        hits = domainHitsCount,
                        misses = domainMissesCount,
                        hitRate = domainHitRate
                    )
                } else {
                    null
                }
            }
                .sortedByDescending { it.hits + it.misses }
                .take(TOP_DOMAIN_COUNT)
            
            // Calculate average domain hit rate
            val avgDomainHitRate = if (domainStats.isNotEmpty()) {
                domainStats.map { it.hitRate }.average().toInt()
            } else {
                0
            }
            
            Pair(domainStats, avgDomainHitRate)
        }
        
        // Create metrics object
        val metrics = DnsCacheMetrics(
            entryCount = entryCount,
            totalLookups = total,
            hits = hits,
            misses = misses,
            hitRate = hitRate,
            memoryUsageBytes = memoryUsageBytes,
            memoryLimitBytes = memoryLimitBytes,
            memoryUsagePercent = memoryUsagePercent,
            avgHitLatencyMs = avgHitLatency,
            avgMissLatencyMs = avgMissLatency,
            avgDomainHitRate = avgDomainHitRate,
            topDomains = topDomains,
            avgTtlSeconds = avgTtlSeconds,
            activeEntries = activeEntriesList
        )
        
        // Emit to StateFlow atomically
        _dashboardStats.value = metrics
        // Log at DEBUG level for periodic visibility (called every 500ms, so verbose would be too noisy)
        // Detailed logging is done in startMetricsUpdateJob() every 10 updates
    }
    
    /**
     * Helper data class for returning multiple values from cacheLock.read
     */
    private data class Quintet<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )
    
    /**
     * Helper data class for returning multiple values from cacheLock.read (6 values)
     */
    private data class Sextet<A, B, C, D, E, F>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
        val sixth: F
    )
    
    /**
     * Record latency for a cache hit
     * Uses exponential moving average to prevent overflow and provide smooth updates
     */
    private fun recordHitLatency(latencyMs: Double) {
        if (avgHitLatency == 0.0) {
            avgHitLatency = latencyMs
        } else {
            // Exponential moving average: new_avg = alpha * new_value + (1 - alpha) * old_avg
            avgHitLatency = MOVING_AVERAGE_ALPHA * latencyMs + (1.0 - MOVING_AVERAGE_ALPHA) * avgHitLatency
        }
    }
    
    /**
     * Record latency for a cache miss
     * Uses exponential moving average to prevent overflow and provide smooth updates
     */
    private fun recordMissLatency(latencyMs: Double) {
        if (avgMissLatency == 0.0) {
            avgMissLatency = latencyMs
        } else {
            // Exponential moving average: new_avg = alpha * new_value + (1 - alpha) * old_avg
            avgMissLatency = MOVING_AVERAGE_ALPHA * latencyMs + (1.0 - MOVING_AVERAGE_ALPHA) * avgMissLatency
        }
    }
    
    /**
     * Record domain access (for hit rate tracking)
     */
    private fun recordDomainAccess(domain: String, isHit: Boolean) {
        domainStatsLock.write {
            val lowerDomain = domain.lowercase()
            if (isHit) {
                domainHits.getOrPut(lowerDomain) { AtomicLong(0) }.incrementAndGet()
            } else {
                domainMisses.getOrPut(lowerDomain) { AtomicLong(0) }.incrementAndGet()
            }
            
            // Limit domain tracking to prevent memory bloat
            // Keep only top domains, remove least accessed
            if (domainHits.size + domainMisses.size > TOP_DOMAIN_COUNT * 2) {
                val allDomains = (domainHits.keys + domainMisses.keys).distinct()
                val domainTotals = allDomains.map { dom ->
                    val hits = domainHits[dom]?.get() ?: 0L
                    val misses = domainMisses[dom]?.get() ?: 0L
                    dom to (hits + misses)
                }.sortedByDescending { it.second }
                
                // Remove domains beyond top N
                val domainsToKeep = domainTotals.take(TOP_DOMAIN_COUNT).map { it.first }.toSet()
                domainHits.keys.removeAll { it !in domainsToKeep }
                domainMisses.keys.removeAll { it !in domainsToKeep }
            }
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

        val startTime = System.nanoTime()
        totalLookups.incrementAndGet()
        
        Log.d(TAG, "🔍 Checking DNS cache for: $hostname")
        return cacheLock.read {
            val entry = cache[hostname.lowercase()]
            if (entry != null && !entry.isExpired()) {
                // Cache hit
                cacheHits.incrementAndGet()
                recordDomainAccess(hostname, isHit = true)
                
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
                        val latencyMs = (System.nanoTime() - startTime) / 1_000_000.0
                        recordHitLatency(latencyMs)
                        Log.i(TAG, "✅ DNS cache HIT: $hostname -> ${entry.ips} (age: ${(System.currentTimeMillis() / 1000 - entry.timestamp)}s, latency: ${latencyMs}ms)")
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
            
            // Cache miss
            cacheMisses.incrementAndGet()
            recordDomainAccess(hostname, isHit = false)
            val latencyMs = (System.nanoTime() - startTime) / 1_000_000.0
            recordMissLatency(latencyMs)
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
     * Save DNS resolution to cache with TTL
     * @param hostname Domain name to cache
     * @param addresses List of resolved IP addresses
     * @param ttl Time-To-Live in seconds (optional, uses optimized TTL if not provided)
     */
    fun saveToCache(hostname: String, addresses: List<InetAddress>, ttl: Long? = null) {
        if (!isInitialized || cacheFile == null) {
            return
        }

        try {
            val ips = addresses.mapNotNull { it.hostAddress }
            // Use provided TTL if available, otherwise use optimized TTL
            val finalTtl = ttl ?: getOptimizedTtl(hostname)
            
            // Track TTL for average calculation
            totalTtlSeconds.addAndGet(finalTtl)
            ttlSampleCount.incrementAndGet()
            
            val entry = DnsCacheEntry(
                ips = ips,
                timestamp = System.currentTimeMillis() / 1000,
                ttl = finalTtl
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

            // CRITICAL FIX: Debounced save - trigger save but don't block
            // Save will happen 200ms after the last write (batched)
            try {
                saveTriggerChannel.trySend(Unit)
            } catch (e: Exception) {
                // Channel might be closed, fallback to immediate save
                Log.w(TAG, "Failed to trigger debounced save, saving immediately", e)
                saveScope.launch {
                    saveCacheToFileSync()
                }
            }
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
     * Save cache to JSON file (synchronous version for debounced saves)
     * 
     * CRITICAL FIX: Renamed to saveCacheToFileSync for clarity
     * This is called by the debounced save job after 5 seconds of inactivity
     */
    private fun saveCacheToFileSync() {
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

                Log.d(TAG, "💾 DNS cache saved to file (debounced): ${cache.size} entries")
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
                    // Trigger debounced save
                    try {
                        saveTriggerChannel.trySend(Unit)
                    } catch (e: Exception) {
                        // Fallback to immediate save
                        saveScope.launch {
                            saveCacheToFileSync()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup expired DNS cache entries", e)
        }
    }

    /**
     * Structured DNS cache statistics data class (legacy, for backward compatibility)
     * @deprecated Use DnsCacheMetrics and dashboardStats StateFlow instead
     */
    @Deprecated("Use DnsCacheMetrics and dashboardStats StateFlow instead", ReplaceWith("dashboardStats.value"))
    data class DnsCacheStatsData(
        val entryCount: Int,
        val hits: Long,
        val misses: Long,
        val hitRate: Int // 0-100 percentage
    )

    /**
     * Get cache statistics as structured data (legacy method)
     * @deprecated Use dashboardStats StateFlow instead for real-time updates
     */
    @Deprecated("Use dashboardStats StateFlow instead for real-time updates", ReplaceWith("dashboardStats.value"))
    fun getStatsStructured(): DnsCacheStatsData {
        val metrics = dashboardStats.value
        return DnsCacheStatsData(
            entryCount = metrics.entryCount,
            hits = metrics.hits,
            misses = metrics.misses,
            hitRate = metrics.hitRate
        )
    }

    /**
     * Get cache statistics as string (for backward compatibility and logging)
     */
    fun getStats(): String {
        val metrics = dashboardStats.value
        return "DNS Cache: ${metrics.entryCount} entries, hits=${metrics.hits}, misses=${metrics.misses}, hitRate=${metrics.hitRate}%"
    }

    /**
     * Clear all cache entries
     */
    fun clearCache() {
        cacheLock.write {
            cache.clear()
            cacheHits.set(0L)
            cacheMisses.set(0L)
            totalLookups.set(0L)
            cacheFile?.delete()
            Log.d(TAG, "DNS cache cleared")
        }
        
        domainStatsLock.write {
            domainHits.clear()
            domainMisses.clear()
        }
        
        avgHitLatency = 0.0
        avgMissLatency = 0.0
        totalTtlSeconds.set(0L)
        ttlSampleCount.set(0L)
        
        // Update metrics immediately after clear
        updateMetrics()
    }
    
    /**
     * Shutdown and cleanup debounced save job and metrics update job
     */
    fun shutdown() {
        saveJob?.cancel()
        saveJob = null
        saveTriggerChannel.close()
        saveScope.cancel()
        
        metricsUpdateJob?.cancel()
        metricsUpdateJob = null
        metricsScope.cancel()
    }
}
