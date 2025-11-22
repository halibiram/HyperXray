package com.hyperxray.an.core.network.dns

import android.content.Context
import android.util.Log
import com.hyperxray.an.core.network.dns.DnsCacheManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl

private const val TAG = "SystemDnsCacheServer"
private const val DNS_PORT = 53
private const val DNS_PORT_ALT = 5353 // Alternative port (no root required)
private const val BUFFER_SIZE = 512
private const val SOCKET_TIMEOUT_MS = 5000

/**
 * System-level DNS cache server that intercepts DNS queries from all apps.
 * Listens on localhost (127.0.0.1:53) and serves cached DNS responses.
 * Cache misses are forwarded to upstream DNS servers.
 */
class SystemDnsCacheServer private constructor(private val context: Context) {
    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var serverJob: Job? = null
    private var warmUpJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus
    
    private var upstreamDnsServers = listOf(
        // Google DNS (fast and reliable)
        InetAddress.getByName("8.8.8.8"),
        InetAddress.getByName("8.8.4.4"),
        // Cloudflare DNS (fast and privacy-focused)
        InetAddress.getByName("1.1.1.1"),
        InetAddress.getByName("1.0.0.1"),
        // Quad9 DNS (security-focused, fast)
        InetAddress.getByName("9.9.9.9"),
        InetAddress.getByName("149.112.112.112"),
        // OpenDNS (reliable, fast)
        InetAddress.getByName("208.67.222.222"),
        InetAddress.getByName("208.67.220.220"),
        // AdGuard DNS (privacy-focused, fast)
        InetAddress.getByName("94.140.14.14"),
        InetAddress.getByName("94.140.15.15"),
        // Additional DNS servers for better redundancy and performance
        // NextDNS (privacy-focused, fast)
        InetAddress.getByName("45.90.28.0"),
        InetAddress.getByName("45.90.30.0"),
        // Yandex DNS (fast in some regions)
        InetAddress.getByName("77.88.8.8"),
        InetAddress.getByName("77.88.8.1"),
        // Comodo Secure DNS (security-focused)
        InetAddress.getByName("8.26.56.26"),
        InetAddress.getByName("8.20.247.20")
    )
    
    // DNS server performance tracking for faster resolution
    private data class DnsServerStats(
        val server: InetAddress,
        var successCount: Int = 0,
        var failureCount: Int = 0,
        var totalLatency: Long = 0,
        var lastSuccessTime: Long = 0,
        var lastFailureTime: Long = 0,
        var isHealthy: Boolean = true
    ) {
        val averageLatency: Long
            get() = if (successCount > 0) totalLatency / successCount else Long.MAX_VALUE
        
        val successRate: Double
            get() = if (successCount + failureCount > 0) successCount.toDouble() / (successCount + failureCount) else 1.0
    }
    
    private val dnsServerStats = mutableMapOf<String, DnsServerStats>()
    private val HEALTH_CHECK_INTERVAL_MS = 60000L // 60 seconds
    private val MAX_FAILURES_BEFORE_UNHEALTHY = 5 // More tolerant (was 3)
    private val MIN_SUCCESS_RATE = 0.3 // 30% success rate minimum (more tolerant, was 50%)
    
    // Warm-up statistics tracking for monitoring
    data class WarmUpStats(
        var totalWarmUps: Int = 0,
        var totalSuccess: Int = 0,
        var totalFailed: Int = 0,
        var totalElapsed: Long = 0L,
        var lastWarmUpTime: Long = 0L,
        var averageSuccessRate: Double = 0.0,
        var averageElapsed: Long = 0L
    )
    private val warmUpStats = WarmUpStats()
    
    private val WARM_UP_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours - periodic warm-up for cache refresh
    
    // DNS cache warm-up: Popular domains to pre-resolve (expanded for faster first-time access)
    private val popularDomains = listOf(
        // Top tier domains (most visited)
        "google.com", "www.google.com", "googleapis.com", "gstatic.com",
        "facebook.com", "www.facebook.com", "graph.facebook.com",
        "youtube.com", "www.youtube.com", "youtu.be",
        "instagram.com", "www.instagram.com",
        "twitter.com", "www.twitter.com", "x.com",
        "amazon.com", "www.amazon.com",
        "microsoft.com", "www.microsoft.com", "microsoftonline.com",
        "apple.com", "www.apple.com", "icloud.com",
        "cloudflare.com", "dns.google",
        // Common services
        "github.com", "githubusercontent.com",
        "stackoverflow.com",
        "reddit.com", "www.reddit.com",
        "linkedin.com", "www.linkedin.com",
        "netflix.com", "nflxvideo.net",
        "spotify.com",
        "discord.com", "discordapp.com",
        // CDN and common subdomains
        "cdnjs.cloudflare.com", "cdn.jsdelivr.net",
        "fonts.googleapis.com", "fonts.gstatic.com",
        // Analytics and tracking (commonly used)
        "google-analytics.com", "googletagmanager.com",
        "doubleclick.net", "googlesyndication.com",
        // TikTok domains (frequently timeout, added for faster resolution)
        "tiktok.com", "www.tiktok.com", "tiktokcdn.com", "tiktokv.com",
        "musical.ly", "tiktokads.com", "bytedance.com",
        // TikTok common subdomains (CDN, API, logging)
        "log-normal-alisg.tiktokv.com", "log16-normal-alisg.tiktokv.com",
        "log22-normal-alisg.tiktokv.com", "log32-normal-alisg.tiktokv.com",
        "aggr-normal-alisg.tiktokv.com", "aggr16-normal-alisg.tiktokv.com",
        "aggr22-normal-alisg.tiktokv.com", "aggr32-normal-alisg.tiktokv.com",
        "bsync-normal-alisg.tiktokv.com", "bsync16-normal-alisg.tiktokv.com",
        "bsync22-normal-alisg.tiktokv.com", "bsync32-normal-alisg.tiktokv.com",
        "api16-normal-alisg.tiktokv.com", "api-normal-alisg.tiktokv.com",
        "hotapi16-normal-alisg.tiktokv.com", "hotapi-normal-alisg.tiktokv.com",
        "v16.tiktokcdn.com", "v16m-us.tiktokcdn.com", "v19-us.tiktokcdn.com",
        "sf16-ies-music-va.tiktokcdn.com", "sf16-ies-music-tt.tiktokcdn.com"
    )
    
    // Adaptive timeout tracking per server (optimized for faster first-time access)
    private val adaptiveTimeouts = ConcurrentHashMap<String, Long>()
    private val BASE_TIMEOUT_MS = 200L // Reduced for faster responses
    private val MAX_TIMEOUT_MS = 800L // Reduced maximum timeout
    
    // Query deduplication: track pending queries to avoid duplicate upstream requests
    private data class PendingQuery(
        val deferred: CompletableDeferred<ByteArray?>,
        val timestamp: Long = System.currentTimeMillis()
    )
    private val pendingQueries = ConcurrentHashMap<String, PendingQuery>()
    private val QUERY_DEDUP_TIMEOUT_MS = 5000L // 5 seconds max wait for duplicate queries
    
    // DoH (DNS over HTTPS) providers for fallback when UDP DNS fails
    private val dohProviders: List<DnsOverHttps> by lazy {
        val dnsClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .connectionPool(
                okhttp3.ConnectionPool(
                    maxIdleConnections = 10,
                    keepAliveDuration = 5,
                    timeUnit = TimeUnit.MINUTES
                )
            )
            .build()
        
        listOf(
            DnsOverHttps.Builder()
                .client(dnsClient)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .includeIPv6(true)
                .build(),
            DnsOverHttps.Builder()
                .client(dnsClient)
                .url("https://dns.google/dns-query".toHttpUrl())
                .includeIPv6(true)
                .build(),
            DnsOverHttps.Builder()
                .client(dnsClient)
                .url("https://dns.quad9.net/dns-query".toHttpUrl())
                .includeIPv6(true)
                .build(),
            DnsOverHttps.Builder()
                .client(dnsClient)
                .url("https://doh.opendns.com/dns-query".toHttpUrl())
                .includeIPv6(true)
                .build()
        )
    }
    
    init {
        // Initialize stats for all DNS servers
        upstreamDnsServers.forEach { server ->
            val hostAddress = server.hostAddress ?: return@forEach
            dnsServerStats[hostAddress] = DnsServerStats(server)
            adaptiveTimeouts[hostAddress] = BASE_TIMEOUT_MS
        }
    }
    
    /**
     * Get adaptive timeout for a DNS server based on its performance
     */
    private fun getAdaptiveTimeout(server: InetAddress): Long {
        val hostAddress = server.hostAddress ?: return BASE_TIMEOUT_MS
        val stats = dnsServerStats[hostAddress] ?: return BASE_TIMEOUT_MS
        val currentTimeout = adaptiveTimeouts[hostAddress] ?: BASE_TIMEOUT_MS
        
        // Adjust timeout based on average latency
        val avgLatency = stats.averageLatency
        return when {
            avgLatency < 50 -> (BASE_TIMEOUT_MS * 0.8).toLong().coerceAtLeast(200L) // Faster server, lower timeout
            avgLatency < 100 -> BASE_TIMEOUT_MS // Normal timeout
            avgLatency < 200 -> (BASE_TIMEOUT_MS * 1.5).toLong().coerceAtMost(MAX_TIMEOUT_MS) // Slower server
            else -> MAX_TIMEOUT_MS // Very slow server
        }
    }
    
    /**
     * Warm up DNS cache with popular domains (enhanced with adaptive prioritization and TTL-aware prefetching)
     * Uses tiered approach: critical domains first, then others in parallel
     */
    fun warmUpCache() {
        if (!isRunning.get()) {
            Log.d(TAG, "DNS cache server not running, skipping warm-up")
            return
        }
        
        scope.launch {
            val startTime = System.currentTimeMillis()
            Log.i(TAG, "🔥 Starting ENHANCED DNS cache warm-up with adaptive prioritization...")
            
            // Step 1: Get adaptive domain list (prioritized by hit rate and TTL expiration)
            val adaptiveDomains = getAdaptiveWarmUpDomains()
            Log.i(TAG, "📊 Adaptive warm-up: ${adaptiveDomains.size} domains (${adaptiveDomains.size - popularDomains.size} dynamically added)")
            
            // Step 2: Tier 1 - Critical domains first (top priority: frequently accessed + about to expire)
            val tier1Domains = adaptiveDomains.filter { it.priority == DomainPriority.CRITICAL }
            var tier1Results: WarmUpResult? = null
            if (tier1Domains.isNotEmpty()) {
                Log.i(TAG, "🚀 Tier 1 warm-up: ${tier1Domains.size} critical domains...")
                tier1Results = warmUpDomainsTiered(tier1Domains.map { it.domain }, tier = 1)
                Log.i(TAG, "✅ Tier 1 completed: ${tier1Results.success}/${tier1Results.total} domains resolved in ${tier1Results.elapsed}ms")
            }
            
            // Step 3: Tier 2 - High priority domains (parallel with Tier 3)
            val tier2Domains = adaptiveDomains.filter { it.priority == DomainPriority.HIGH }
            val tier3Domains = adaptiveDomains.filter { it.priority == DomainPriority.NORMAL }
            
            // Tier 2 and 3 can resolve in parallel (they're less critical)
            val deferredTier2 = if (tier2Domains.isNotEmpty()) {
                async {
                    Log.i(TAG, "⚡ Tier 2 warm-up: ${tier2Domains.size} high priority domains...")
                    warmUpDomainsTiered(tier2Domains.map { it.domain }, tier = 2)
                }
            } else null
            
            val deferredTier3 = if (tier3Domains.isNotEmpty()) {
                async {
                    Log.i(TAG, "📦 Tier 3 warm-up: ${tier3Domains.size} normal priority domains...")
                    warmUpDomainsTiered(tier3Domains.map { it.domain }, tier = 3)
                }
            } else null
            
            // Wait for Tier 2 and 3 to complete
            val tier2Result = deferredTier2?.await()
            val tier3Result = deferredTier3?.await()
            
            // Log Tier 2 and 3 completion (INFO level for visibility)
            if (tier2Result != null) {
                val tier2SuccessRate = if (tier2Result.total > 0) {
                    (tier2Result.success * 100.0 / tier2Result.total).toInt()
                } else 0
                Log.i(TAG, "✅ Tier 2 completed: ${tier2Result.success}/${tier2Result.total} domains resolved in ${tier2Result.elapsed}ms (${tier2SuccessRate}% success rate)")
            }
            
            if (tier3Result != null) {
                val tier3SuccessRate = if (tier3Result.total > 0) {
                    (tier3Result.success * 100.0 / tier3Result.total).toInt()
                } else 0
                Log.i(TAG, "✅ Tier 3 completed: ${tier3Result.success}/${tier3Result.total} domains resolved in ${tier3Result.elapsed}ms (${tier3SuccessRate}% success rate)")
            }
            
            // Calculate comprehensive statistics
            val totalSuccess = (tier1Results?.success ?: 0) + (tier2Result?.success ?: 0) + (tier3Result?.success ?: 0)
            val totalFailed = (tier1Results?.failed ?: 0) + (tier2Result?.failed ?: 0) + (tier3Result?.failed ?: 0)
            val totalDomains = adaptiveDomains.size
            val totalSuccessRate = if (totalDomains > 0) {
                (totalSuccess * 100.0 / totalDomains).toInt()
            } else 0
            val totalElapsed = System.currentTimeMillis() - startTime
            
            // Enhanced completion log with detailed statistics (INFO level)
            Log.i(TAG, "✅ Enhanced DNS cache warm-up completed: $totalSuccess/$totalDomains domains resolved in ${totalElapsed}ms (${totalSuccessRate}% success rate)")
            Log.i(TAG, "📊 Warm-up statistics - Tier 1: ${tier1Results?.success ?: 0}/${tier1Domains.size} (${tier1Results?.elapsed ?: 0}ms), Tier 2: ${tier2Result?.success ?: 0}/${tier2Domains.size} (${tier2Result?.elapsed ?: 0}ms), Tier 3: ${tier3Result?.success ?: 0}/${tier3Domains.size} (${tier3Result?.elapsed ?: 0}ms)")
            Log.i(TAG, "📈 Warm-up performance: ${totalSuccess} successes, ${totalFailed} failures, average ${if (totalSuccess > 0) (totalElapsed / totalSuccess) else 0}ms per domain")
            
            // Track warm-up success rate for monitoring
            trackWarmUpStats(totalSuccess, totalFailed, totalElapsed, totalSuccessRate)
        }
    }
    
    /**
     * Track warm-up statistics for monitoring
     * Updates global warm-up stats with latest results
     */
    private fun trackWarmUpStats(success: Int, failed: Int, elapsed: Long, successRate: Int) {
        synchronized(warmUpStats) {
            warmUpStats.totalWarmUps++
            warmUpStats.totalSuccess += success
            warmUpStats.totalFailed += failed
            warmUpStats.totalElapsed += elapsed
            warmUpStats.lastWarmUpTime = System.currentTimeMillis()
            
            // Calculate average success rate
            val totalAttempts = warmUpStats.totalSuccess + warmUpStats.totalFailed
            warmUpStats.averageSuccessRate = if (totalAttempts > 0) {
                (warmUpStats.totalSuccess * 100.0 / totalAttempts)
            } else 0.0
            
            // Calculate average elapsed time
            warmUpStats.averageElapsed = if (warmUpStats.totalWarmUps > 0) {
                warmUpStats.totalElapsed / warmUpStats.totalWarmUps
            } else 0L
            
            // Log monitoring statistics periodically (every 5 warm-ups)
            if (warmUpStats.totalWarmUps % 5 == 0) {
                Log.i(TAG, "📈 Warm-up monitoring: ${warmUpStats.totalWarmUps} warm-ups, avg success rate: ${String.format("%.1f", warmUpStats.averageSuccessRate)}%, avg elapsed: ${warmUpStats.averageElapsed}ms, total: ${warmUpStats.totalSuccess}/${warmUpStats.totalSuccess + warmUpStats.totalFailed}")
            }
        }
    }
    
    /**
     * Get warm-up statistics for monitoring
     * Returns current warm-up statistics snapshot
     */
    fun getWarmUpStats(): WarmUpStats {
        return synchronized(warmUpStats) {
            warmUpStats.copy()
        }
    }
    
    /**
     * Domain priority for tiered warm-up strategy
     */
    private enum class DomainPriority {
        CRITICAL,  // Frequently accessed + about to expire (TTL < 1 hour remaining)
        HIGH,      // Frequently accessed (hit rate > 0.8)
        NORMAL     // Standard popular domains
    }
    
    /**
     * Domain with priority information for adaptive warm-up
     */
    private data class PrioritizedDomain(
        val domain: String,
        val priority: DomainPriority,
        val reason: String
    )
    
    /**
     * Get adaptive warm-up domain list (prioritized by hit rate, TTL expiration, and user behavior)
     */
    private fun getAdaptiveWarmUpDomains(): List<PrioritizedDomain> {
        val result = mutableListOf<PrioritizedDomain>()
        
        // Get high hit rate domains from cache statistics (dynamically learned)
        val highHitRateDomains = DnsCacheManager.getPrefetchCandidates()
        val highHitRateSet = highHitRateDomains.toSet()
        
        // Get domains about to expire (TTL-aware prefetching)
        val expiringSoonDomains = DnsCacheManager.getExpiringSoonDomains(hoursThreshold = 1)
        val expiringSoonSet = expiringSoonDomains.toSet()
        
        // Categorize popular domains with priorities
        popularDomains.forEach { domain ->
            val priority = when {
                // Critical: Frequently accessed AND about to expire
                highHitRateSet.contains(domain) && expiringSoonSet.contains(domain) -> {
                    DomainPriority.CRITICAL to "high hit rate + expiring soon"
                }
                // Critical: About to expire (needs refresh)
                expiringSoonSet.contains(domain) -> {
                    DomainPriority.CRITICAL to "expiring soon (TTL < 1h)"
                }
                // High: Frequently accessed (high hit rate)
                highHitRateSet.contains(domain) -> {
                    DomainPriority.HIGH to "high hit rate"
                }
                // Critical: Top tier domains (always prioritize)
                domain in listOf("google.com", "www.google.com", "facebook.com", "www.facebook.com", 
                                "youtube.com", "www.youtube.com", "instagram.com", "www.instagram.com") -> {
                    DomainPriority.CRITICAL to "top tier domain"
                }
                // Normal: Standard popular domains
                else -> {
                    DomainPriority.NORMAL to "popular domain"
                }
            }
            result.add(PrioritizedDomain(domain, priority.first, priority.second))
        }
        
        // Add dynamically learned domains (not in popular list) with high priority
        highHitRateDomains.filterNot { it in popularDomains }.take(20).forEach { domain ->
            result.add(PrioritizedDomain(domain, DomainPriority.HIGH, "dynamically learned (high hit rate)"))
        }
        
        // Sort by priority (CRITICAL first, then HIGH, then NORMAL)
        return result.sortedBy { 
            when (it.priority) {
                DomainPriority.CRITICAL -> 0
                DomainPriority.HIGH -> 1
                DomainPriority.NORMAL -> 2
            }
        }
    }
    
    /**
     * Warm-up domains in a specific tier (with retry mechanism, subdomain prefetching, and enhanced statistics)
     */
    private suspend fun warmUpDomainsTiered(
        domains: List<String>,
        tier: Int,
        maxConcurrency: Int = 50, // Limit concurrent queries per tier to avoid overwhelming
        prefetchSubdomains: Boolean = tier <= 2 // Only prefetch subdomains for critical and high priority tiers
    ): WarmUpResult {
        val startTime = System.currentTimeMillis()
        var successCount = 0
        var failedCount = 0
        val subdomainSuccessCount = java.util.concurrent.atomic.AtomicInteger(0)
        val subdomainTotalCount = java.util.concurrent.atomic.AtomicInteger(0)
        
        // Process domains in batches to control concurrency
        for (batch in domains.chunked(maxConcurrency)) {
            val deferredResults = batch.map { domain ->
                scope.async(Dispatchers.IO) {
                    try {
                        // Retry mechanism for warm-up reliability
                        var lastError: Exception? = null
                        
                        for (attempt in 1..3) {
                            try {
                                val addresses = resolveDomain(domain)
                                if (addresses.isNotEmpty()) {
                                    Log.d(TAG, "✅ Warm-up [Tier $tier]: $domain -> ${addresses.map { it.hostAddress ?: "unknown" }}")
                                    
                                    // Prefetch related subdomains if enabled (background task, non-blocking)
                                    if (prefetchSubdomains && tier <= 2) {
                                        scope.launch {
                                            try {
                                                val relatedDomains = generateRelatedSubdomains(domain)
                                                relatedDomains.forEach { subdomain ->
                                                    subdomainTotalCount.incrementAndGet()
                                                    try {
                                                        val subdomainAddresses = resolveDomain(subdomain)
                                                        if (subdomainAddresses.isNotEmpty()) {
                                                            subdomainSuccessCount.incrementAndGet()
                                                            Log.d(TAG, "✅ Warm-up [Tier $tier] subdomain: $subdomain -> ${subdomainAddresses.map { it.hostAddress ?: "unknown" }}")
                                                        }
                                                    } catch (e: Exception) {
                                                        // Subdomain prefetch failures are not critical, just log
                                                        Log.d(TAG, "⚠️ Warm-up [Tier $tier] subdomain failed: $subdomain (${e.message})")
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.d(TAG, "⚠️ Error generating related subdomains for $domain: ${e.message}")
                                            }
                                        }
                                    }
                                    
                                    return@async WarmUpDomainResult(domain, true, null)
                                }
                            } catch (e: Exception) {
                                lastError = e
                                if (attempt < 3) {
                                    kotlinx.coroutines.delay(100L * attempt) // Exponential backoff
                                }
                            }
                        }
                        Log.d(TAG, "⚠️ Warm-up [Tier $tier] failed for $domain after 3 attempts: ${lastError?.message}")
                        return@async WarmUpDomainResult(domain, false, lastError)
                    } catch (e: Exception) {
                        Log.d(TAG, "⚠️ Warm-up [Tier $tier] error for $domain: ${e.message}")
                        return@async WarmUpDomainResult(domain, false, e)
                    }
                }
            }
            
            val results = deferredResults.awaitAll()
            successCount += results.count { result -> result.success }
            failedCount += results.count { result -> !result.success }
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        
        // Log enhanced statistics (wait a bit for subdomain prefetching to complete)
        if (prefetchSubdomains) {
            kotlinx.coroutines.delay(2000) // Wait 2 seconds for subdomain prefetching to complete
            val subdomainSuccess = subdomainSuccessCount.get()
            val subdomainTotal = subdomainTotalCount.get()
            if (subdomainTotal > 0) {
                val subdomainSuccessRate = if (subdomainTotal > 0) {
                    (subdomainSuccess * 100.0 / subdomainTotal).toInt()
                } else 0
                Log.i(TAG, "📊 Warm-up [Tier $tier] stats: ${successCount}/${domains.size} domains, ${subdomainSuccess}/${subdomainTotal} subdomains (${subdomainSuccessRate}% success), ${elapsed}ms")
            }
        }
        
        // Log tier statistics at INFO level for visibility (if not logged above)
        if (!prefetchSubdomains || subdomainTotalCount.get() == 0) {
            val tierSuccessRate = if ((successCount + failedCount) > 0) {
                (successCount * 100.0 / (successCount + failedCount)).toInt()
            } else 0
            Log.i(TAG, "📊 Warm-up [Tier $tier] stats: ${successCount}/${domains.size} domains resolved in ${elapsed}ms (${tierSuccessRate}% success rate)")
        }
        
        return WarmUpResult(successCount, failedCount, successCount + failedCount, elapsed)
    }
    
    /**
     * Generate related subdomains for a domain (www, cdn, api, static, etc.)
     * Enhanced with common patterns for popular domains
     */
    private fun generateRelatedSubdomains(domain: String): List<String> {
        val parts = domain.split(".")
        if (parts.size < 2) return emptyList()
        
        val baseDomain = parts.takeLast(2).joinToString(".")
        val currentSubdomain = if (parts.size > 2) parts.dropLast(2).joinToString(".") else null
        
        val commonSubdomains = listOf(
            "www", "cdn", "api", "static", "assets", "media", "img", "images",
            "js", "css", "fonts", "blog", "mail", "ftp", "admin", "secure"
        )
        
        val related = mutableListOf<String>()
        
        // Only generate subdomains if current domain doesn't already have a common subdomain
        if (currentSubdomain == null || currentSubdomain !in commonSubdomains) {
            // Generate top 5 most common subdomains (limit to avoid too many queries)
            commonSubdomains.take(5).forEach { prefix ->
                val relatedDomain = "$prefix.$baseDomain"
                // Skip if it's the same as current domain
                if (relatedDomain != domain) {
                    related.add(relatedDomain)
                }
            }
        }
        
        return related
    }
    
    /**
     * Result of tiered warm-up operation
     */
    private data class WarmUpResult(
        val success: Int,
        val failed: Int,
        val total: Int,
        val elapsed: Long
    )
    
    /**
     * Result of single domain warm-up attempt
     */
    private data class WarmUpDomainResult(
        val domain: String,
        val success: Boolean,
        val error: Exception?
    )
    
    /**
     * Get DNS servers sorted by performance and geographic proximity (fastest and closest first)
     * Unhealthy servers are excluded or placed last
     * Uses performance-based ordering with geographic awareness
     */
    private fun getSortedDnsServers(): List<InetAddress> {
        val now = System.currentTimeMillis()
        return dnsServerStats.values
            .filter { stats ->
                // Include healthy servers or servers that haven't failed recently
                stats.isHealthy || (now - stats.lastFailureTime > HEALTH_CHECK_INTERVAL_MS)
            }
            .sortedWith(compareBy<DnsServerStats> { !it.isHealthy }
                .thenBy { it.averageLatency }
                .thenBy { -it.successRate }
                .thenBy { estimateGeographicDistance(it.server) }) // Geographic proximity as tie-breaker
            .map { it.server }
    }
    
    /**
     * Estimate geographic distance based on DNS server IP ranges
     * Returns a score (lower = closer, higher = farther)
     * This is a simple heuristic - real geolocation would require a database
     */
    private fun estimateGeographicDistance(server: InetAddress): Int {
        val ip = server.hostAddress ?: return 5
        // Simple heuristic: prioritize well-known fast DNS servers
        // Cloudflare and Google are typically well-distributed globally
        return when {
            ip.startsWith("1.1.1.") || ip.startsWith("1.0.0.") -> 0 // Cloudflare - very fast globally
            ip.startsWith("8.8.") -> 1 // Google - fast globally
            ip.startsWith("9.9.9.") || ip.startsWith("149.112.") -> 2 // Quad9
            ip.startsWith("208.67.") -> 3 // OpenDNS
            ip.startsWith("94.140.") -> 4 // AdGuard
            else -> 5 // Unknown
        }
    }
    
    /**
     * Batch resolve multiple domains in parallel
     * Useful for resolving related domains (e.g., www.example.com and cdn.example.com)
     */
    suspend fun batchResolveDomains(domains: List<String>): Map<String, List<InetAddress>> {
        return withContext(Dispatchers.IO) {
            val deferredResults = domains.map { domain ->
                async {
                    domain to resolveDomain(domain)
                }
            }
            deferredResults.awaitAll().toMap()
        }
    }
    
    /**
     * Update DNS server performance stats
     */
    private fun updateDnsServerStats(server: InetAddress, latency: Long, success: Boolean = true) {
        val hostAddress = server.hostAddress ?: return
        val stats = dnsServerStats[hostAddress] ?: return
        
        if (success) {
            stats.successCount++
            stats.totalLatency += latency
            stats.lastSuccessTime = System.currentTimeMillis()
            
            // Mark as healthy if it was unhealthy but now succeeding
            if (!stats.isHealthy && stats.successCount >= 2) {
                stats.isHealthy = true
                stats.failureCount = 0
                Log.d(TAG, "✅ DNS server ${server.hostAddress ?: "unknown"} recovered and marked as healthy")
            }
        } else {
            stats.failureCount++
            stats.lastFailureTime = System.currentTimeMillis()
            
            // Mark as unhealthy if too many failures (only if we have enough data points)
            val totalAttempts = stats.successCount + stats.failureCount
            if (totalAttempts >= 3 && (stats.failureCount >= MAX_FAILURES_BEFORE_UNHEALTHY || stats.successRate < MIN_SUCCESS_RATE)) {
                stats.isHealthy = false
                Log.w(TAG, "⚠️ DNS server ${server.hostAddress ?: "unknown"} marked as unhealthy (failures: ${stats.failureCount}/${totalAttempts}, success rate: ${(stats.successRate * 100).toInt()}%)")
            }
        }
    }
    
    enum class ServerStatus {
        Stopped,
        Starting,
        Running,
        Stopping,
        Error
    }
    
    companion object {
        @Volatile
        private var INSTANCE: SystemDnsCacheServer? = null
        private val lock = Any()
        
        fun getInstance(context: Context): SystemDnsCacheServer {
            return INSTANCE ?: synchronized(lock) {
                INSTANCE ?: SystemDnsCacheServer(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        fun isInstanceCreated(): Boolean = INSTANCE != null
    }
    
    /**
     * Start the DNS cache server on localhost
     * Tries port 53 first (may require root), falls back to 5353 (no root required)
     */
    fun start(port: Int = DNS_PORT): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "DNS cache server is already running")
            return false
        }
        
        // Ensure DnsCacheManager is initialized
        DnsCacheManager.initialize(context)
        
        // Try specified port first
        if (tryStartOnPort(port)) {
            return true
        }
        
        // If port 53 fails, try alternative port (no root required)
        if (port == DNS_PORT) {
            Log.i(TAG, "Port 53 not available (may require root), trying alternative port $DNS_PORT_ALT")
            if (tryStartOnPort(DNS_PORT_ALT)) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * Try to start DNS server on a specific port
     */
    private fun tryStartOnPort(port: Int): Boolean {
        return try {
            _serverStatus.value = ServerStatus.Starting
            Log.i(TAG, "🚀 Starting system DNS cache server on 127.0.0.1:$port")
            
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = SOCKET_TIMEOUT_MS
                bind(InetSocketAddress("127.0.0.1", port))
            }
            
            isRunning.set(true)
            _serverStatus.value = ServerStatus.Running
            
            // Warm up DNS cache with popular domains (initial warm-up)
            warmUpCache()
            
            // Start periodic warm-up job (every 6 hours)
            warmUpJob = scope.launch {
                while (isRunning.get()) {
                    delay(WARM_UP_INTERVAL_MS)
                    if (isRunning.get()) {
                        Log.i(TAG, "🔄 Periodic DNS cache warm-up triggered (6 hours interval)...")
                        warmUpCache()
                    }
                }
            }
            
            // Start server loop in coroutine
            serverJob = scope.launch {
                try {
                    serverLoop()
                } catch (e: Exception) {
                    Log.e(TAG, "DNS server loop error", e)
                    _serverStatus.value = ServerStatus.Error
                    isRunning.set(false)
                }
            }
            
            Log.i(TAG, "✅ System DNS cache server started successfully on 127.0.0.1:$port")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start DNS cache server on port $port: ${e.message}")
            socket?.close()
            socket = null
            false
        }
    }
    
    /**
     * Get the port the DNS server is listening on
     */
    fun getListeningPort(): Int? {
        return socket?.localPort
    }
    
    /**
     * Xray-core patch: Actively resolve domain through SystemDnsCacheServer.
     * Checks cache first, then resolves from upstream DNS if needed.
     * Uses retry mechanism to reduce packet loss during DNS cache miss.
     * Returns list of resolved IP addresses, or empty list if resolution failed.
     */
    suspend fun resolveDomain(domain: String): List<InetAddress> {
        return withContext(Dispatchers.IO) {
            try {
                // Check cache first
                val cached = DnsCacheManager.getFromCache(domain)
                if (cached != null && cached.isNotEmpty()) {
                    Log.d(TAG, "✅ DNS CACHE HIT (resolveDomain): $domain -> ${cached.map { it.hostAddress ?: "unknown" }}")
                    return@withContext cached
                }
                
                // Cache miss - resolve from upstream DNS with retry mechanism
                Log.d(TAG, "⚠️ DNS CACHE MISS (resolveDomain): $domain, resolving from upstream with retry...")
                
                // Build DNS query packet
                val queryData = buildDnsQuery(domain) ?: return@withContext emptyList()
                
                // Forward to upstream DNS servers with retry mechanism (reduces packet loss)
                val responseData = forwardToUpstreamDnsWithRetry(queryData, domain)
                if (responseData != null) {
                    // Parse response and extract IP addresses and TTL
                    val parseResult = parseDnsResponseWithTtl(responseData, domain)
                    if (parseResult.addresses.isNotEmpty()) {
                        // Save to cache with TTL and raw response packet
                        DnsCacheManager.saveToCache(
                            domain, 
                            parseResult.addresses, 
                            ttl = parseResult.ttl,
                            rawResponse = responseData
                        )
                        Log.i(TAG, "✅ DNS resolved and cached (resolveDomain): $domain -> ${parseResult.addresses.map { it.hostAddress ?: "unknown" }} (TTL: ${parseResult.ttl}s)")
                        return@withContext parseResult.addresses
                    } else if (parseResult.isNxDomain) {
                        // NXDOMAIN - save as negative cache
                        DnsCacheManager.saveToCache(
                            domain,
                            emptyList(),
                            ttl = null,
                            rawResponse = responseData,
                            isNegative = true
                        )
                        Log.d(TAG, "✅ NXDOMAIN cached for $domain")
                    }
                }
                
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving domain: $domain", e)
                emptyList()
            }
        }
    }
    
    /**
     * Compress DNS query by optimizing domain name encoding
     * Uses DNS name compression to reduce packet size
     */
    private fun compressDnsQuery(queryData: ByteArray, hostname: String): ByteArray {
        // For now, return original query (DNS compression is complex)
        // Future optimization: implement DNS name compression
        // This would reduce packet size for repeated domain parts
        return queryData
    }
    
    /**
     * Build DNS query packet from domain name
     */
    private fun buildDnsQuery(domain: String): ByteArray? {
        return try {
            val buffer = ByteBuffer.allocate(BUFFER_SIZE).apply {
                order(ByteOrder.BIG_ENDIAN)
            }
            
            // DNS header
            val transactionId = (System.currentTimeMillis().toInt() and 0xFFFF).toShort()
            buffer.putShort(transactionId) // Transaction ID
            buffer.putShort(0x0100.toShort()) // Flags: standard query, recursion desired
            buffer.putShort(1) // Questions: 1
            buffer.putShort(0) // Answer RRs: 0
            buffer.putShort(0) // Authority RRs: 0
            buffer.putShort(0) // Additional RRs: 0
            
            // Question section
            domain.split(".").forEach { part ->
                buffer.put(part.length.toByte())
                buffer.put(part.toByteArray(Charsets.UTF_8))
            }
            buffer.put(0) // Null terminator
            buffer.putShort(1) // QTYPE: A (IPv4)
            buffer.putShort(1) // QCLASS: IN (Internet)
            
            val querySize = buffer.position()
            val queryData = ByteArray(querySize)
            buffer.flip()
            buffer.get(queryData)
            
            queryData
        } catch (e: Exception) {
            Log.e(TAG, "Error building DNS query for $domain: ${e.message}")
            null
        }
    }
    
    /**
     * Stop the DNS cache server
     */
    fun stop() {
        if (!isRunning.get()) {
            return
        }
        
        try {
            _serverStatus.value = ServerStatus.Stopping
            Log.i(TAG, "🛑 Stopping system DNS cache server...")
            
            isRunning.set(false)
            warmUpJob?.cancel()
            warmUpJob = null
            serverJob?.cancel()
            serverJob = null
            
            socket?.close()
            socket = null
            
            _serverStatus.value = ServerStatus.Stopped
            Log.i(TAG, "✅ System DNS cache server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping DNS cache server", e)
            _serverStatus.value = ServerStatus.Error
        }
    }
    
    /**
     * Main server loop - handles incoming DNS queries
     */
    private suspend fun serverLoop() {
        val socket = socket ?: return
        val buffer = ByteArray(BUFFER_SIZE)
        
        Log.i(TAG, "🔍 DNS server loop started, waiting for queries on port ${socket.localPort}...")
        
        while (isRunning.get() && scope.isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                
                Log.i(TAG, "📥 DNS query received from ${packet.address}:${packet.port}, length: ${packet.length}")
                
                // Handle query in separate coroutine
                scope.launch {
                    handleDnsQuery(packet, socket)
                }
            } catch (e: SocketTimeoutException) {
                // Timeout is normal, continue waiting
                continue
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "Error receiving DNS query", e)
                }
            }
        }
    }
    
    /**
     * Handle incoming DNS query
     */
    private suspend fun handleDnsQuery(requestPacket: DatagramPacket, socket: DatagramSocket) {
        try {
            val requestData = ByteArray(requestPacket.length)
            System.arraycopy(requestPacket.data, 0, requestData, 0, requestPacket.length)
            
            // Parse DNS query
            val query = DnsQueryParser.parseQuery(requestData)
            if (query == null) {
                Log.w(TAG, "Failed to parse DNS query")
                return
            }
            
            val hostname = query.hostname
            Log.i(TAG, "🔍 DNS query parsed: $hostname from ${requestPacket.address}:${requestPacket.port}")
            
            // Check cache first (including raw response packet)
            val rawResponse = DnsCacheManager.getRawResponseFromCache(hostname)
            if (rawResponse != null) {
                // Use cached raw response packet for fastest response
                val responsePacket = DatagramPacket(
                    rawResponse,
                    rawResponse.size,
                    requestPacket.socketAddress
                )
                // Update transaction ID to match request
                val requestTransactionId = ByteBuffer.wrap(requestData, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
                rawResponse[0] = ((requestTransactionId shr 8) and 0xFF).toByte()
                rawResponse[1] = (requestTransactionId and 0xFF).toByte()
                
                socket.send(responsePacket)
                Log.d(TAG, "✅ DNS response sent from cached raw packet: $hostname")
                return
            }
            
            // Fallback to regular cache check
            val cachedResult = DnsCacheManager.getFromCache(hostname, allowStale = true)
            if (cachedResult != null) {
                if (cachedResult.isEmpty()) {
                    // Negative cache (NXDOMAIN)
                    Log.d(TAG, "✅ DNS negative cache HIT (SystemDnsCacheServer): $hostname (NXDOMAIN)")
                    // Build NXDOMAIN response
                    val response = buildNxDomainResponse(requestData)
                    if (response != null) {
                        val responsePacket = DatagramPacket(
                            response,
                            response.size,
                            requestPacket.socketAddress
                        )
                        socket.send(responsePacket)
                        return
                    }
                } else {
                    Log.i(TAG, "✅ DNS CACHE HIT (SystemDnsCacheServer): $hostname -> ${cachedResult.map { it.hostAddress ?: "unknown" }} (served from cache)")
                    
                    // Build DNS response from cache using buildDnsResponse
                    val response = buildDnsResponse(requestData, cachedResult)
                    if (response != null) {
                        val responsePacket = DatagramPacket(
                            response,
                            response.size,
                            requestPacket.socketAddress
                        )
                        socket.send(responsePacket)
                        Log.d(TAG, "✅ DNS response sent from cache: $hostname")
                        return
                    }
                }
            }
            
            // Cache miss - forward to upstream DNS server with retry mechanism
            Log.i(TAG, "⚠️ DNS CACHE MISS: $hostname (forwarding to upstream DNS with retry)")
            
            val upstreamResponse = withContext(Dispatchers.IO) {
                forwardToUpstreamDnsWithRetry(requestData, hostname)
            }
            
            if (upstreamResponse != null && upstreamResponse.isNotEmpty()) {
                // Parse upstream response and save to cache
                val parseResult = parseDnsResponseWithTtl(upstreamResponse, hostname)
                if (parseResult.addresses.isNotEmpty()) {
                    DnsCacheManager.saveToCache(
                        hostname,
                        parseResult.addresses,
                        ttl = parseResult.ttl,
                        rawResponse = upstreamResponse
                    )
                    Log.i(TAG, "✅ DNS resolved from upstream and cached: $hostname -> ${parseResult.addresses.map { it.hostAddress ?: "unknown" }} (TTL: ${parseResult.ttl}s)")
                    
                    // Send response to client
                    val responsePacket = DatagramPacket(
                        upstreamResponse,
                        upstreamResponse.size,
                        requestPacket.socketAddress
                    )
                    socket.send(responsePacket)
                    Log.d(TAG, "✅ DNS response sent from upstream: $hostname")
                } else if (parseResult.isNxDomain) {
                    // NXDOMAIN - save as negative cache
                    DnsCacheManager.saveToCache(
                        hostname,
                        emptyList(),
                        ttl = null,
                        rawResponse = upstreamResponse,
                        isNegative = true
                    )
                    Log.d(TAG, "✅ NXDOMAIN cached for $hostname")
                    
                    // Send NXDOMAIN response to client
                    val responsePacket = DatagramPacket(
                        upstreamResponse,
                        upstreamResponse.size,
                        requestPacket.socketAddress
                    )
                    socket.send(responsePacket)
                } else {
                    Log.w(TAG, "Failed to parse upstream DNS response for $hostname")
                }
            } else {
                Log.w(TAG, "No response from upstream DNS servers for $hostname")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling DNS query", e)
        }
    }
    
    /**
     * Forward DNS query to upstream DNS servers (parallel multi-DNS support)
     * Queries all upstream DNS servers simultaneously and returns first successful response
     * Optimized timeout: 1000ms (reduced from 3000ms to minimize packet loss)
     */
    private suspend fun forwardToUpstreamDns(queryData: ByteArray, hostname: String): ByteArray? {
        // Use optimized retry mechanism instead of single attempt
        return forwardToUpstreamDnsWithRetry(queryData, hostname)
    }
    
    /**
     * Forward DNS query to upstream DNS servers with optimized retry mechanism
     * Retries with ultra-fast timeouts (300ms -> 500ms -> 800ms) for maximum speed
     * Uses performance-based DNS server ordering (fastest first)
     * Uses aggressive parallel queries to minimize latency
     * Implements query deduplication to avoid duplicate upstream requests
     * This reduces packet loss during DNS cache miss scenarios
     */
    private suspend fun forwardToUpstreamDnsWithRetry(queryData: ByteArray, hostname: String): ByteArray? {
        val lowerHostname = hostname.lowercase()
        
        // Check if there's already a pending query for this domain (deduplication)
        val existingQuery = pendingQueries[lowerHostname]
        if (existingQuery != null) {
            val age = System.currentTimeMillis() - existingQuery.timestamp
            if (age < QUERY_DEDUP_TIMEOUT_MS) {
                // Wait for existing query to complete
                Log.d(TAG, "🔄 Query deduplication: waiting for existing query for $hostname (age: ${age}ms)")
                return existingQuery.deferred.await()
            } else {
                // Existing query timed out, remove it
                pendingQueries.remove(lowerHostname)
            }
        }
        
        // Create new pending query
        val deferred = CompletableDeferred<ByteArray?>()
        pendingQueries[lowerHostname] = PendingQuery(deferred)
        
        try {
            // Single attempt with short timeout - no retry mechanism
            // Fast failure to quickly move to next method (DoH fallback)
            val timeoutMs = 300L // Short timeout for fast fallback
            
            try {
                val startTime = System.currentTimeMillis()
                val result = forwardToUpstreamDnsWithTimeout(queryData, hostname, timeoutMs)
                val elapsed = System.currentTimeMillis() - startTime
                
                if (result != null) {
                    Log.d(TAG, "✅ DNS resolved via UDP for $hostname (${elapsed}ms)")
                    
                    // Complete deferred and notify waiting queries
                    deferred.complete(result)
                    pendingQueries.remove(lowerHostname)
                    return result
                }
            } catch (e: Exception) {
                Log.d(TAG, "⚠️ UDP DNS query failed for $hostname: ${e.message}, trying DoT fallback...")
            }
            
            // Try DoT fallback if UDP failed
            val dotResult = tryDoTFallback(queryData, hostname)
            if (dotResult != null) {
                deferred.complete(dotResult)
                pendingQueries.remove(lowerHostname)
                return dotResult
            }
            
            // Try DoH fallback if DoT failed
            val dohResult = tryDoHFallback(hostname)
            if (dohResult != null) {
                deferred.complete(dohResult)
                pendingQueries.remove(lowerHostname)
                return dohResult
            }
            
            // Try TCP DNS fallback if DoH failed
            val tcpResult = tryTcpDnsFallback(queryData, hostname)
            if (tcpResult != null) {
                deferred.complete(tcpResult)
                pendingQueries.remove(lowerHostname)
                return tcpResult
            }
            
            // Complete with null on failure
            deferred.complete(null)
            pendingQueries.remove(lowerHostname)
            return null
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
            pendingQueries.remove(lowerHostname)
            throw e
        }
    }
    
    /**
     * DNS over HTTPS (DoH) fallback when UDP DNS fails
     * Uses real DoH providers (Cloudflare, Google, Quad9, OpenDNS) with parallel queries
     * Falls back to system DNS resolver as last resort
     */
    private suspend fun tryDoHFallback(hostname: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            
            // Single attempt with short timeout - no retry mechanism
            // Fast failure to quickly move to next method (system DNS)
            val timeoutMs = 1000L // Short timeout for fast fallback
            
            Log.d(TAG, "🔄 Trying DoH fallback for $hostname (real DNS over HTTPS)...")
            
            try {
                // Parallel DoH queries - try all providers simultaneously
                val deferredResults = dohProviders.mapIndexed { index, dohProvider ->
                    async(Dispatchers.IO) {
                        withTimeoutOrNull(timeoutMs) {
                            try {
                                val result = dohProvider.lookup(hostname)
                                if (result.isNotEmpty()) {
                                    val providerName = when (index) {
                                        0 -> "Cloudflare"
                                        1 -> "Google"
                                        2 -> "Quad9"
                                        3 -> "OpenDNS"
                                        else -> "Unknown"
                                    }
                                    val elapsed = System.currentTimeMillis() - startTime
                                    Log.i(TAG, "✅ DNS resolved via DoH ($providerName): $hostname -> ${result.map { it.hostAddress ?: "unknown" }} (${elapsed}ms)")
                                    result
                                } else null
                            } catch (e: Exception) {
                                Log.d(TAG, "⚠️ DoH provider ${index + 1} failed for $hostname: ${e.message}")
                                null
                            }
                        }
                    }
                }
                
                // Get first successful result from parallel DoH queries
                var selectedResult: List<InetAddress>? = null
                val results = deferredResults.awaitAll()
                for ((index, result) in results.withIndex()) {
                    if (result != null && result.isNotEmpty()) {
                        selectedResult = result
                        // Cancel remaining queries
                        deferredResults.forEachIndexed { idx, deferred ->
                            if (idx > index) {
                                deferred.cancel()
                            }
                        }
                        break
                    }
                }
                
                if (selectedResult != null && selectedResult.isNotEmpty()) {
                    // Build DNS response packet from resolved addresses
                    val queryData = buildDnsQuery(hostname) ?: return@withContext null
                    val responseData = buildDnsResponse(queryData, selectedResult)
                    
                    // Cache the result immediately for faster subsequent access
                    DnsCacheManager.saveToCache(hostname, selectedResult, ttl = 3600L)
                    
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i(TAG, "✅ DoH fallback successful for $hostname (${elapsed}ms)")
                    return@withContext responseData
                }
            } catch (e: Exception) {
                Log.d(TAG, "⚠️ DoH fallback failed for $hostname: ${e.message}, trying TCP DNS...")
            }
            
            // All DoH providers failed - try TCP DNS
            val queryData = buildDnsQuery(hostname) ?: return@withContext null
            val tcpResult = tryTcpDnsFallback(queryData, hostname)
            if (tcpResult != null) {
                return@withContext tcpResult
            }
            
            // Try system DNS as last resort
            Log.d(TAG, "🔄 All methods failed, trying system DNS as last resort for $hostname...")
            return@withContext trySystemDnsFallback(hostname)
        }
    }
    
    /**
     * DNS over TLS (DoT) fallback when UDP DNS fails
     * Uses TLS-encrypted DNS queries on port 853
     * Faster than DoH, more secure than UDP DNS
     */
    private suspend fun tryDoTFallback(queryData: ByteArray, hostname: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            
            // Single attempt with short timeout - no retry mechanism
            val timeoutMs = 1000L // Short timeout for fast fallback
            
            Log.d(TAG, "🔄 Trying DoT fallback for $hostname (DNS over TLS)...")
            
            // DoT servers (port 853)
            val dotServers = listOf(
                Pair("1.1.1.1", 853),  // Cloudflare
                Pair("8.8.8.8", 853),  // Google
                Pair("9.9.9.9", 853)   // Quad9
            )
            
            // Try DoT servers in parallel
            val deferredResults = dotServers.mapIndexed { index, (serverHost, port) ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(timeoutMs) {
                        try {
                            val serverAddress = InetAddress.getByName(serverHost)
                            val sslSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                            
                            val sslSocket = sslSocketFactory.createSocket(serverAddress, port) as SSLSocket
                            sslSocket.soTimeout = timeoutMs.toInt()
                            
                            // Enable TLS
                            sslSocket.startHandshake()
                            
                            // Send DNS query (prepend 2-byte length for TCP DNS)
                            val lengthPrefix = ByteArray(2)
                            ByteBuffer.wrap(lengthPrefix).order(ByteOrder.BIG_ENDIAN).putShort(queryData.size.toShort())
                            
                            val outputStream = sslSocket.getOutputStream()
                            outputStream.write(lengthPrefix)
                            outputStream.write(queryData)
                            outputStream.flush()
                            
                            // Read DNS response (first 2 bytes = length)
                            val inputStream = sslSocket.getInputStream()
                            val lengthBytes = ByteArray(2)
                            inputStream.read(lengthBytes)
                            val responseLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                            
                            val responseData = ByteArray(responseLength)
                            var totalRead = 0
                            while (totalRead < responseLength) {
                                val read = inputStream.read(responseData, totalRead, responseLength - totalRead)
                                if (read == -1) break
                                totalRead += read
                            }
                            
                            sslSocket.close()
                            
                            if (totalRead == responseLength && responseData.isNotEmpty()) {
                                val providerName = when (index) {
                                    0 -> "Cloudflare"
                                    1 -> "Google"
                                    2 -> "Quad9"
                                    else -> "Unknown"
                                }
                                val elapsed = System.currentTimeMillis() - startTime
                                Log.i(TAG, "✅ DNS resolved via DoT ($providerName): $hostname (${elapsed}ms)")
                                
                                // Parse and cache result
                                val addresses = parseDnsResponse(responseData, hostname)
                                if (addresses.isNotEmpty()) {
                                    DnsCacheManager.saveToCache(hostname, addresses, ttl = 3600L)
                                }
                                
                                return@withTimeoutOrNull responseData
                            }
                            null
                        } catch (e: Exception) {
                            Log.d(TAG, "⚠️ DoT server ${index + 1} ($serverHost:$port) failed for $hostname: ${e.message}")
                            null
                        }
                    }
                }
            }
            
            // Get first successful result
            val results = deferredResults.awaitAll()
            for (result in results) {
                if (result != null) {
                    return@withContext result
                }
            }
            
            Log.d(TAG, "⚠️ DoT fallback failed for $hostname, trying DoH...")
            null
        }
    }
    
    /**
     * TCP DNS fallback when UDP and DoT fail
     * Uses plain TCP connection on port 53
     * Useful when UDP is blocked but TCP works
     */
    private suspend fun tryTcpDnsFallback(queryData: ByteArray, hostname: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            
            // Single attempt with short timeout - no retry mechanism
            val timeoutMs = 1000L // Short timeout for fast fallback
            
            Log.d(TAG, "🔄 Trying TCP DNS fallback for $hostname...")
            
            // Try primary DNS servers via TCP
            val tcpDnsServers = listOf(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("9.9.9.9")
            )
            
            // Try TCP DNS servers in parallel
            val deferredResults = tcpDnsServers.mapIndexed { index, serverAddress ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(timeoutMs) {
                        try {
                            val socket = Socket()
                            socket.soTimeout = timeoutMs.toInt()
                            socket.connect(InetSocketAddress(serverAddress, DNS_PORT), timeoutMs.toInt())
                            
                            // Send DNS query (prepend 2-byte length for TCP DNS)
                            val lengthPrefix = ByteArray(2)
                            ByteBuffer.wrap(lengthPrefix).order(ByteOrder.BIG_ENDIAN).putShort(queryData.size.toShort())
                            
                            val outputStream = socket.getOutputStream()
                            outputStream.write(lengthPrefix)
                            outputStream.write(queryData)
                            outputStream.flush()
                            
                            // Read DNS response (first 2 bytes = length)
                            val inputStream = socket.getInputStream()
                            val lengthBytes = ByteArray(2)
                            inputStream.read(lengthBytes)
                            val responseLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                            
                            val responseData = ByteArray(responseLength)
                            var totalRead = 0
                            while (totalRead < responseLength) {
                                val read = inputStream.read(responseData, totalRead, responseLength - totalRead)
                                if (read == -1) break
                                totalRead += read
                            }
                            
                            socket.close()
                            
                            if (totalRead == responseLength && responseData.isNotEmpty()) {
                                val serverName = when (index) {
                                    0 -> "Google"
                                    1 -> "Cloudflare"
                                    2 -> "Quad9"
                                    else -> "Unknown"
                                }
                                val elapsed = System.currentTimeMillis() - startTime
                                Log.i(TAG, "✅ DNS resolved via TCP DNS ($serverName): $hostname (${elapsed}ms)")
                                
                                // Parse and cache result
                                val addresses = parseDnsResponse(responseData, hostname)
                                if (addresses.isNotEmpty()) {
                                    DnsCacheManager.saveToCache(hostname, addresses, ttl = 3600L)
                                }
                                
                                return@withTimeoutOrNull responseData
                            }
                            null
                        } catch (e: Exception) {
                            Log.d(TAG, "⚠️ TCP DNS server ${index + 1} failed for $hostname: ${e.message}")
                            null
                        }
                    }
                }
            }
            
            // Get first successful result
            val results = deferredResults.awaitAll()
            for (result in results) {
                if (result != null) {
                    return@withContext result
                }
            }
            
            Log.d(TAG, "⚠️ TCP DNS fallback failed for $hostname")
            null
        }
    }
    
    /**
     * System DNS fallback (last resort when all other methods fail)
     * Uses Android's built-in DNS resolver
     */
    private suspend fun trySystemDnsFallback(hostname: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            // Single attempt with short timeout - no retry mechanism
            // Last resort fallback, fast failure if it doesn't work
            val timeoutMs = 1000L // Short timeout for fast failure
            
            Log.d(TAG, "🔄 Trying system DNS fallback for $hostname...")
            
            try {
                // Use system DNS resolver as last resort (Android's built-in DNS)
                val startTime = System.currentTimeMillis()
                val addresses = withTimeoutOrNull(timeoutMs) {
                    InetAddress.getAllByName(hostname)
                }
                val elapsed = System.currentTimeMillis() - startTime
                
                if (addresses != null && addresses.isNotEmpty()) {
                    // Build DNS response packet from resolved addresses
                    val queryData = buildDnsQuery(hostname) ?: return@withContext null
                    val responseData = buildDnsResponse(queryData, addresses.toList())
                    
                    // Cache the result immediately for faster subsequent access
                    DnsCacheManager.saveToCache(hostname, addresses.toList(), ttl = 3600L)
                    
                    Log.i(TAG, "✅ System DNS fallback successful for $hostname -> ${addresses.map { it.hostAddress ?: "unknown" }} (${elapsed}ms)")
                    return@withContext responseData
                }
            } catch (e: Exception) {
                Log.w(TAG, "❌ System DNS fallback failed for $hostname: ${e.message}")
            }
            
            null
        }
    }
    
    /**
     * Build DNS response packet from resolved addresses
     */
    private fun buildDnsResponse(queryData: ByteArray, addresses: List<InetAddress>): ByteArray? {
        return try {
            // Parse query to get transaction ID and question
            val transactionId = ByteBuffer.wrap(queryData, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
            val flags = 0x8180.toShort() // Standard response, no error
            val questions = 1.toShort()
            val answers = addresses.size.toShort()
            val authority = 0.toShort()
            val additional = 0.toShort()
            
            // Calculate response size
            val questionSize = queryData.size - 12 // Skip header
            val answerSize = addresses.sumOf { 16 } // Each A record is ~16 bytes
            val responseSize = 12 + questionSize + answerSize
            
            val response = ByteArray(responseSize)
            val buffer = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN)
            
            // Write header
            buffer.putShort(transactionId.toShort())
            buffer.putShort(flags)
            buffer.putShort(questions)
            buffer.putShort(answers)
            buffer.putShort(authority)
            buffer.putShort(additional)
            
            // Copy question section
            System.arraycopy(queryData, 12, response, 12, questionSize)
            
            // Write answer section
            var offset = 12 + questionSize
            addresses.forEach { address ->
                val ipBytes = address.address
                // Name pointer to question
                response[offset++] = 0xC0.toByte()
                response[offset++] = 0x0C.toByte()
                // Type A (0x0001)
                response[offset++] = 0x00
                response[offset++] = 0x01
                // Class IN (0x0001)
                response[offset++] = 0x00
                response[offset++] = 0x01
                // TTL (3600 seconds)
                response[offset++] = 0x00
                response[offset++] = 0x00
                response[offset++] = 0x0E.toByte()
                response[offset++] = 0x10
                // Data length (4 bytes for IPv4)
                response[offset++] = 0x00
                response[offset++] = 0x04
                // IP address
                System.arraycopy(ipBytes, 0, response, offset, 4)
                offset += 4
            }
            
            response
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build DNS response: ${e.message}")
            null
        }
    }
    
    /**
     * Forward DNS query with specific timeout (optimized for speed)
     * Uses performance-based DNS server ordering (fastest first)
     * Uses aggressive parallel queries with optimized socket settings
     */
    private suspend fun forwardToUpstreamDnsWithTimeout(queryData: ByteArray, hostname: String, timeoutMs: Long): ByteArray? {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            
            // Use performance-sorted DNS servers (fastest first) for better results
            val sortedServers = getSortedDnsServers()
            
            // Launch parallel DNS queries to all upstream servers (optimized for speed)
            // Fast failover: if a server fails, immediately try next one
            val deferredResults = sortedServers.map { dnsServer ->
                async {
                    try {
                        // Skip unhealthy servers immediately (fast failover)
                        val hostAddress = dnsServer.hostAddress ?: return@async null
                        val stats = dnsServerStats[hostAddress]
                        if (stats != null && !stats.isHealthy) {
                            val now = System.currentTimeMillis()
                            if (now - stats.lastFailureTime < HEALTH_CHECK_INTERVAL_MS) {
                                // Server is unhealthy and recently failed, skip immediately
                                return@async null
                            }
                        }
                        
                        // Use adaptive timeout based on server performance
                        val adaptiveTimeout = getAdaptiveTimeout(dnsServer).coerceAtMost(timeoutMs)
                        
                        withTimeoutOrNull(adaptiveTimeout) {
                            // Create per-query socket to avoid monitor contention
                            // UDP sockets are lightweight, creation overhead is negligible compared to network latency
                            DatagramSocket().use { socket ->
                                socket.soTimeout = adaptiveTimeout.toInt()
                                try {
                                    socket.reuseAddress = true
                                } catch (e: Exception) {
                                    // Some platforms may not support this
                                }
                                
                                // Compress DNS query if possible (reuse domain name compression)
                                val compressedQuery = compressDnsQuery(queryData, hostname)
                                
                                val requestPacket = DatagramPacket(
                                    compressedQuery,
                                    compressedQuery.size,
                                    InetSocketAddress(dnsServer, DNS_PORT)
                                )
                                
                                val sendStart = System.currentTimeMillis()
                                socket.send(requestPacket)
                                
                                val responseBuffer = ByteArray(BUFFER_SIZE)
                                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                                socket.receive(responsePacket)
                                
                                val response = ByteArray(responsePacket.length)
                                System.arraycopy(responsePacket.data, 0, response, 0, responsePacket.length)
                                
                                val elapsed = System.currentTimeMillis() - sendStart
                                
                                // Update adaptive timeout based on performance
                                val currentTimeout = adaptiveTimeouts[hostAddress] ?: BASE_TIMEOUT_MS
                                val newTimeout = when {
                                    elapsed < currentTimeout * 0.7 -> (currentTimeout * 0.9).toLong().coerceAtLeast(200L) // Faster, reduce timeout
                                    elapsed > currentTimeout * 1.5 -> (currentTimeout * 1.2).toLong().coerceAtMost(MAX_TIMEOUT_MS) // Slower, increase timeout
                                    else -> currentTimeout // Keep current
                                }
                                adaptiveTimeouts[hostAddress] = newTimeout
                                
                                // Update performance stats for this DNS server (success)
                                updateDnsServerStats(dnsServer, elapsed, success = true)
                                
                                Log.d(TAG, "✅ DNS response from ${dnsServer.hostAddress ?: "unknown"} for $hostname (${elapsed}ms, timeout: ${adaptiveTimeout}ms)")
                                response
                            }
                        }
                    } catch (e: Exception) {
                        // Update performance stats for this DNS server (failure) - fast failover
                        updateDnsServerStats(dnsServer, 0, success = false)
                        
                        // Only log failures on final attempt to reduce log noise
                        if (timeoutMs >= 800L) {
                            Log.d(TAG, "Upstream DNS server ${dnsServer.hostAddress ?: "unknown"} failed: ${e.message}")
                        }
                        null
                    }
                }
            }
            
            // Use select to get first successful response from parallel queries
            // This ensures we get the fastest response from any DNS server
            val selectedResult = select<ByteArray?> {
                deferredResults.forEachIndexed { index, deferred ->
                    deferred.onAwait { result ->
                        if (result != null) {
                            val totalElapsed = System.currentTimeMillis() - startTime
                            val fastestServer = sortedServers[index]
                            Log.d(TAG, "✅ Fastest DNS response from ${fastestServer.hostAddress ?: "unknown"} for $hostname (${totalElapsed}ms)")
                            result
                        } else null
                    }
                }
            }
            
            // Cancel remaining queries if we got a result (save resources)
            if (selectedResult != null) {
                deferredResults.forEach { it.cancel() }
            }
            
            selectedResult
        }
    }
    
    /**
     * Parse result containing addresses, TTL, and NXDOMAIN flag
     */
    private data class DnsParseResult(
        val addresses: List<InetAddress>,
        val ttl: Long? = null,
        val isNxDomain: Boolean = false
    )
    
    /**
     * Parse DNS response and extract IP addresses with TTL
     * Full DNS packet parser implementation according to RFC 1035
     */
    private fun parseDnsResponse(responseData: ByteArray, hostname: String): List<InetAddress> {
        return parseDnsResponseWithTtl(responseData, hostname).addresses
    }
    
    /**
     * Parse DNS response and extract IP addresses, TTL, and NXDOMAIN flag
     * Full DNS packet parser implementation according to RFC 1035
     */
    private fun parseDnsResponseWithTtl(responseData: ByteArray, hostname: String): DnsParseResult {
        return try {
            if (responseData.size < 12) {
                Log.w(TAG, "DNS response too short: ${responseData.size} bytes")
                return DnsParseResult(emptyList())
            }
            
            val buffer = ByteBuffer.wrap(responseData).apply {
                order(ByteOrder.BIG_ENDIAN)
            }
            
            // Parse DNS header
            val transactionId = buffer.short.toInt() and 0xFFFF
            val flags = buffer.short.toInt() and 0xFFFF
            val questions = buffer.short.toInt() and 0xFFFF
            val answers = buffer.short.toInt() and 0xFFFF
            val authority = buffer.short.toInt() and 0xFFFF
            val additional = buffer.short.toInt() and 0xFFFF
            
            // Check if response is valid (QR bit must be 1, RCODE should be 0)
            val qr = (flags shr 15) and 0x01
            val rcode = flags and 0x0F
            
            if (qr != 1) {
                Log.w(TAG, "Invalid DNS response: not a response packet")
                return DnsParseResult(emptyList())
            }
            
            if (rcode != 0) {
                // NXDOMAIN or other error
                if (rcode == 3) {
                    Log.d(TAG, "NXDOMAIN for $hostname")
                    return DnsParseResult(emptyList(), isNxDomain = true)
                } else {
                    Log.w(TAG, "DNS error response for $hostname: RCODE=$rcode")
                    return DnsParseResult(emptyList())
                }
            }
            
            // Skip question section
            buffer.position(12)
            for (i in 0 until questions) {
                skipDnsName(buffer)
                buffer.getShort() // QTYPE
                buffer.getShort() // QCLASS
            }
            
            // Parse answer section
            val addresses = mutableListOf<InetAddress>()
            var minTtl: Long? = null
            
            for (i in 0 until answers) {
                // Check buffer bounds before parsing
                if (buffer.position() >= buffer.limit()) {
                    Log.w(TAG, "Buffer overflow: position ${buffer.position()} >= limit ${buffer.limit()}, stopping answer parsing")
                    break
                }
                
                val answerOffset = buffer.position()
                
                // Parse name (may use compression)
                try {
                    val nameEndPos = skipDnsName(buffer)
                    // Ensure we're at the correct position after skipping name
                    if (nameEndPos != buffer.position()) {
                        Log.w(TAG, "DNS name skip mismatch: expected position $nameEndPos, actual ${buffer.position()}, correcting...")
                        buffer.position(nameEndPos)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to skip DNS name at position $answerOffset: ${e.message}")
                    break
                }
                
                // Check bounds before reading type, class, TTL, and dataLength
                if (buffer.remaining() < 10) { // 2 (type) + 2 (class) + 4 (TTL) + 2 (dataLength) = 10 bytes
                    Log.w(TAG, "Insufficient buffer space for answer record header at position ${buffer.position()}")
                    break
                }
                
                val type = buffer.short.toInt() and 0xFFFF
                val qclass = buffer.short.toInt() and 0xFFFF
                val ttl = buffer.int.toLong() and 0xFFFFFFFFL
                val dataLength = buffer.short.toInt() and 0xFFFF
                
                // Validate dataLength to prevent buffer overflow
                if (dataLength < 0 || dataLength > buffer.remaining()) {
                    Log.w(TAG, "Invalid dataLength $dataLength (remaining: ${buffer.remaining()}) at position ${buffer.position()}, skipping record")
                    break
                }
                
                // Track minimum TTL from all A/AAAA records
                if (type == 1 || type == 28) {
                    if (minTtl == null || ttl < minTtl) {
                        minTtl = ttl
                    }
                }
                
                when (type) {
                    1 -> { // A record (IPv4)
                        if (dataLength == 4 && buffer.remaining() >= 4) {
                            val ipBytes = ByteArray(4)
                            buffer.get(ipBytes)
                            try {
                                val address = InetAddress.getByAddress(ipBytes)
                                addresses.add(address)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to create InetAddress from A record", e)
                            }
                        } else {
                            // Skip invalid length or insufficient buffer
                            val skipAmount = minOf(dataLength, buffer.remaining())
                            if (skipAmount > 0) {
                                buffer.position(buffer.position() + skipAmount)
                            } else {
                                Log.w(TAG, "Cannot skip A record data: dataLength=$dataLength, remaining=${buffer.remaining()}")
                                break
                            }
                        }
                    }
                    28 -> { // AAAA record (IPv6)
                        if (dataLength == 16 && buffer.remaining() >= 16) {
                            val ipBytes = ByteArray(16)
                            buffer.get(ipBytes)
                            try {
                                val address = InetAddress.getByAddress(ipBytes)
                                addresses.add(address)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to create InetAddress from AAAA record", e)
                            }
                        } else {
                            // Skip invalid length or insufficient buffer
                            val skipAmount = minOf(dataLength, buffer.remaining())
                            if (skipAmount > 0) {
                                buffer.position(buffer.position() + skipAmount)
                            } else {
                                Log.w(TAG, "Cannot skip AAAA record data: dataLength=$dataLength, remaining=${buffer.remaining()}")
                                break
                            }
                        }
                    }
                    5 -> { // CNAME - skip for now, could follow chain
                        val skipAmount = minOf(dataLength, buffer.remaining())
                        if (skipAmount > 0) {
                            buffer.position(buffer.position() + skipAmount)
                        } else {
                            Log.w(TAG, "Cannot skip CNAME record data: dataLength=$dataLength, remaining=${buffer.remaining()}")
                            break
                        }
                    }
                    else -> {
                        // Unknown record type, skip
                        val skipAmount = minOf(dataLength, buffer.remaining())
                        if (skipAmount > 0) {
                            buffer.position(buffer.position() + skipAmount)
                        } else {
                            Log.w(TAG, "Cannot skip unknown record type $type data: dataLength=$dataLength, remaining=${buffer.remaining()}")
                            break
                        }
                    }
                }
            }
            
            if (addresses.isEmpty()) {
                Log.d(TAG, "No A/AAAA records found in DNS response for $hostname")
            } else {
                Log.d(TAG, "Parsed ${addresses.size} IP addresses from DNS response for $hostname (TTL: ${minTtl}s)")
            }
            
            DnsParseResult(addresses, minTtl)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse DNS response for $hostname: ${e.message}", e)
            DnsParseResult(emptyList())
        }
    }
    
    /**
     * Skip DNS name in packet (handles compression pointers)
     * Returns the final buffer position after skipping the name
     * 
     * DNS name format:
     * - Normal label: [length byte][label bytes]...
     * - Compression pointer: [0xC0 | high 6 bits of offset][low 8 bits of offset]
     * - End: [0x00]
     * 
     * When a compression pointer is encountered, we follow it to read the name,
     * but then return to the position AFTER the compression pointer (2 bytes consumed)
     */
    private fun skipDnsName(buffer: ByteBuffer): Int {
        val startPos = buffer.position()
        var maxJumps = 10 // Prevent infinite loops
        var jumps = 0
        var encounteredCompression = false
        var compressionEndPos = startPos + 2 // Default: if compression, consume 2 bytes
        
        while (buffer.hasRemaining() && jumps < maxJumps) {
            // Check if we have at least 1 byte
            if (buffer.remaining() < 1) {
                break
            }
            
            val length = buffer.get().toInt() and 0xFF
            
            if (length == 0) {
                // End of name - normal termination
                if (!encounteredCompression) {
                    return buffer.position()
                } else {
                    // We encountered compression, return to position after compression pointer
                    return compressionEndPos
                }
            } else if ((length and 0xC0) == 0xC0) {
                // Compression pointer - read the second byte
                if (buffer.remaining() < 1) {
                    break
                }
                val secondByte = buffer.get().toInt() and 0xFF
                val offset = ((length and 0x3F) shl 8) or secondByte
                
                // Compression pointer: we've consumed 2 bytes total
                compressionEndPos = buffer.position() // Position after reading compression pointer (2 bytes consumed)
                encounteredCompression = true
                
                // Validate offset is within buffer bounds
                if (offset >= 0 && offset < buffer.limit() && offset < startPos) {
                    // Follow compression pointer (offset must be before current position to avoid loops)
                    val savedPos = buffer.position()
                    buffer.position(offset)
                    jumps++
                    
                    // Recursively skip the name at the compression target
                    // This will read the name but we'll return to compressionEndPos
                    skipDnsName(buffer)
                    
                    // Return to position after compression pointer
                    return compressionEndPos
                } else {
                    Log.w(TAG, "Invalid compression pointer offset: $offset (buffer limit: ${buffer.limit()}, start: $startPos)")
                    return compressionEndPos
                }
            } else if (length <= 63) {
                // Normal label - skip length bytes
                if (buffer.remaining() < length) {
                    break
                }
                buffer.position(buffer.position() + length)
            } else {
                // Invalid length
                Log.w(TAG, "Invalid DNS name length: $length at position ${buffer.position() - 1}")
                return if (encounteredCompression) compressionEndPos else buffer.position()
            }
        }
        
        return if (encounteredCompression) compressionEndPos else buffer.position()
    }
    
    /**
     * Set upstream DNS servers
     */
    fun setUpstreamDnsServers(servers: List<String>) {
        upstreamDnsServers = servers.mapNotNull { server ->
            try {
                InetAddress.getByName(server)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid DNS server address: $server", e)
                null
            }
        }
        Log.i(TAG, "✅ Upstream DNS servers updated: ${upstreamDnsServers.map { it.hostAddress ?: "unknown" }}")
    }
    
    /**
     * Check if server is running
     */
    fun isRunning(): Boolean = isRunning.get()
    
    /**
     * Shutdown and cleanup
     */
    fun shutdown() {
        stop()
        scope.cancel()
    }
}

/**
 * Simple DNS query parser
 */
private object DnsQueryParser {
    fun parseQuery(queryData: ByteArray): DnsQuery? {
        if (queryData.size < 12) {
            return null
        }
        
        try {
            val buffer = ByteBuffer.wrap(queryData).apply {
                order(ByteOrder.BIG_ENDIAN) // DNS uses big-endian
            }
            
            // Parse DNS header
            val transactionId = buffer.short.toInt() and 0xFFFF
            val flags = buffer.short.toInt() and 0xFFFF
            val questions = buffer.short.toInt() and 0xFFFF
            
            if (questions == 0) {
                return null
            }
            
            // Parse question section - extract hostname
            buffer.position(12) // Skip header
            val hostname = parseHostname(buffer) ?: return null
            val qtype = buffer.short.toInt() and 0xFFFF
            val qclass = buffer.short.toInt() and 0xFFFF
            
            return DnsQuery(transactionId, hostname, qtype, qclass)
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun parseHostname(buffer: ByteBuffer): String? {
        val parts = mutableListOf<String>()
        
        while (buffer.hasRemaining()) {
            val length = buffer.get().toInt() and 0xFF
            if (length == 0) {
                break
            }
            if (length > 63) {
                // Compression pointer - simplified handling
                return null
            }
            val bytes = ByteArray(length)
            buffer.get(bytes)
            parts.add(String(bytes, Charsets.UTF_8))
        }
        
        return if (parts.isNotEmpty()) parts.joinToString(".") else null
    }
}

/**
 * DNS query data class
 */
private data class DnsQuery(
    val transactionId: Int,
    val hostname: String,
    val qtype: Int,
    val qclass: Int
)


/**
 * Build NXDOMAIN response
 */
private fun buildNxDomainResponse(queryData: ByteArray): ByteArray? {
    return try {
        if (queryData.size < 12) return null
        
        val buffer = ByteBuffer.wrap(queryData).apply {
            order(ByteOrder.BIG_ENDIAN)
        }
        
        val transactionId = buffer.short.toInt() and 0xFFFF
        val questions = buffer.short.toInt() and 0xFFFF
        
        // Skip rest of header and question
        buffer.position(12)
        val questionSize = queryData.size - 12
        
        // Build NXDOMAIN response
        val response = ByteArray(12 + questionSize)
        val responseBuffer = ByteBuffer.wrap(response).apply {
            order(ByteOrder.BIG_ENDIAN)
        }
        
        // Header
        responseBuffer.putShort(transactionId.toShort())
        responseBuffer.putShort(0x8183.toShort()) // QR=1, RCODE=3 (NXDOMAIN)
        responseBuffer.putShort(questions.toShort())
        responseBuffer.putShort(0) // Answers
        responseBuffer.putShort(0) // Authority
        responseBuffer.putShort(0) // Additional
        
        // Copy question section
        System.arraycopy(queryData, 12, response, 12, questionSize)
        
        response
    } catch (e: Exception) {
        Log.w(TAG, "Failed to build NXDOMAIN response", e)
        null
    }
}

