package com.hyperxray.an.core.network.dns

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "DnsWarmupManager"

/**
 * Domain priority for tiered warm-up strategy
 */
enum class DomainPriority {
    TIER_1_CRITICAL,  // Top 20 most accessed domains (last 24h) + Expiring domains
    TIER_2_HIGH,      // Recent domains (3 days) + Hardcoded "Super Popular" list
    TIER_3_SPECULATIVE // Predicted subdomains
}

/**
 * Domain with priority information for adaptive warm-up
 */
data class PrioritizedDomain(
    val domain: String,
    val priority: DomainPriority,
    val reason: String,
    val accessCount: Int = 0,
    val lastAccessTime: Long = 0L
)

/**
 * Result of tiered warm-up operation
 */
data class WarmUpResult(
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
 * Warm-up statistics tracking
 */
data class WarmUpStats(
    var totalWarmUps: Int = 0,
    var totalSuccess: Int = 0,
    var totalFailed: Int = 0,
    var totalElapsed: Long = 0L,
    var lastWarmUpTime: Long = 0L,
    var averageSuccessRate: Double = 0.0,
    var averageElapsed: Long = 0L
)

/**
 * DNS Warm-up Manager
 * Encapsulates Smart Tiering, Frequency Analysis, and Warm-up concurrency logic
 * Implements "Force-Max" Network Strategy: Execute warm-up for ALL TIERS regardless of network type
 */
class DnsWarmupManager(
    private val scope: CoroutineScope,
    private val resolveDomain: suspend (String) -> List<InetAddress>
) {
    // Popular domains list (hardcoded "Super Popular" list)
    private val superPopularDomains = listOf(
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
        // TikTok domains
        "tiktok.com", "www.tiktok.com", "tiktokcdn.com", "tiktokv.com",
        "musical.ly", "tiktokads.com", "bytedance.com"
    )
    
    private val warmUpStats = WarmUpStats()
    private val WARM_UP_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours
    
    // Domain access tracking (simplified - in production, this would come from DnsCacheManager)
    private val domainAccessStats = mutableMapOf<String, DomainAccessInfo>()
    
    private data class DomainAccessInfo(
        var accessCount: Int = 0,
        var lastAccessTime: Long = 0L,
        var firstAccessTime: Long = 0L
    )
    
    /**
     * Track domain access for frequency analysis
     */
    fun trackDomainAccess(domain: String) {
        val info = domainAccessStats.getOrPut(domain.lowercase()) {
            DomainAccessInfo(firstAccessTime = System.currentTimeMillis())
        }
        info.accessCount++
        info.lastAccessTime = System.currentTimeMillis()
    }
    
    /**
     * Get adaptive warm-up domain list (prioritized by frequency, TTL expiration, and user behavior)
     * Tier 1 (Critical): Top 20 most accessed domains (last 24h) + Expiring domains
     * Tier 2 (High): Recent domains (3 days) + Hardcoded "Super Popular" list
     * Tier 3 (Speculative): Predicted subdomains
     */
    private fun getAdaptiveWarmUpDomains(): List<PrioritizedDomain> {
        val result = mutableListOf<PrioritizedDomain>()
        val now = System.currentTimeMillis()
        val oneDayAgo = now - (24 * 60 * 60 * 1000L)
        val threeDaysAgo = now - (3 * 24 * 60 * 60 * 1000L)
        
        // Get top 20 most accessed domains in last 24h (Tier 1)
        val topAccessedDomains = domainAccessStats.entries
            .filter { it.value.lastAccessTime >= oneDayAgo }
            .sortedByDescending { it.value.accessCount }
            .take(20)
            .map { it.key }
            .toSet()
        
        // Get expiring domains (would need DnsCacheManager integration)
        val expiringDomains = emptySet<String>() // Placeholder - would need DnsCacheManager.getExpiringSoonDomains()
        
        // Get recent domains (3 days) for Tier 2
        val recentDomains = domainAccessStats.entries
            .filter { it.value.lastAccessTime >= threeDaysAgo && it.value.lastAccessTime < oneDayAgo }
            .map { it.key }
            .toSet()
        
        // Categorize domains with priorities
        superPopularDomains.forEach { domain ->
            val priority = when {
                // Tier 1: Frequently accessed AND about to expire
                topAccessedDomains.contains(domain) && expiringDomains.contains(domain) -> {
                    DomainPriority.TIER_1_CRITICAL to "top accessed + expiring soon"
                }
                // Tier 1: About to expire (needs refresh)
                expiringDomains.contains(domain) -> {
                    DomainPriority.TIER_1_CRITICAL to "expiring soon (TTL < 1h)"
                }
                // Tier 1: Top accessed domains
                topAccessedDomains.contains(domain) -> {
                    DomainPriority.TIER_1_CRITICAL to "top accessed (last 24h)"
                }
                // Tier 2: Recent domains (3 days)
                recentDomains.contains(domain) -> {
                    DomainPriority.TIER_2_HIGH to "recent access (3 days)"
                }
                // Tier 2: Super popular domains
                domain in listOf("google.com", "www.google.com", "facebook.com", "www.facebook.com",
                                "youtube.com", "www.youtube.com", "instagram.com", "www.instagram.com") -> {
                    DomainPriority.TIER_2_HIGH to "super popular domain"
                }
                // Tier 3: Standard popular domains
                else -> {
                    DomainPriority.TIER_3_SPECULATIVE to "popular domain"
                }
            }
            
            val accessInfo = domainAccessStats[domain.lowercase()]
            result.add(PrioritizedDomain(
                domain = domain,
                priority = priority.first,
                reason = priority.second,
                accessCount = accessInfo?.accessCount ?: 0,
                lastAccessTime = accessInfo?.lastAccessTime ?: 0L
            ))
        }
        
        // Add dynamically learned domains (not in popular list) with high priority
        topAccessedDomains.filterNot { it in superPopularDomains }.take(20).forEach { domain ->
            val accessInfo = domainAccessStats[domain]
            result.add(PrioritizedDomain(
                domain = domain,
                priority = DomainPriority.TIER_2_HIGH,
                reason = "dynamically learned (high access)",
                accessCount = accessInfo?.accessCount ?: 0,
                lastAccessTime = accessInfo?.lastAccessTime ?: 0L
            ))
        }
        
        // Sort by priority (TIER_1_CRITICAL first, then TIER_2_HIGH, then TIER_3_SPECULATIVE)
        return result.sortedBy {
            when (it.priority) {
                DomainPriority.TIER_1_CRITICAL -> 0
                DomainPriority.TIER_2_HIGH -> 1
                DomainPriority.TIER_3_SPECULATIVE -> 2
            }
        }
    }
    
    /**
     * Warm up DNS cache with "Force-Max" Network Strategy
     * Execute warm-up for ALL TIERS regardless of network type (Wi-Fi/Mobile)
     * Prioritize speed over data
     */
    suspend fun warmUpCache(): WarmUpResult {
        val startTime = System.currentTimeMillis()
        
        // Step 1: Get adaptive warm-up domain list (prioritized by frequency, TTL expiration, and user behavior)
        val adaptiveDomains = getAdaptiveWarmUpDomains()
        Log.i(TAG, "📊 Adaptive warm-up: ${adaptiveDomains.size} domains")
        
        // Step 2: Tier 1 - Critical domains first (High Priority, 50 threads)
        val tier1Domains = adaptiveDomains.filter { it.priority == DomainPriority.TIER_1_CRITICAL }
        var tier1Results: WarmUpResult? = null
        if (tier1Domains.isNotEmpty()) {
            Log.i(TAG, "🚀 Tier 1 warm-up: ${tier1Domains.size} critical domains (50 threads)...")
            tier1Results = warmUpDomainsTiered(tier1Domains.map { it.domain }, tier = 1, maxConcurrency = 50)
            Log.i(TAG, "✅ Tier 1 completed: ${tier1Results.success}/${tier1Results.total} domains resolved in ${tier1Results.elapsed}ms")
        }
        
        // Step 3: Tier 2 and 3 - High/Normal priority domains (Medium Priority, 20 threads each)
        val tier2Domains = adaptiveDomains.filter { it.priority == DomainPriority.TIER_2_HIGH }
        val tier3Domains = adaptiveDomains.filter { it.priority == DomainPriority.TIER_3_SPECULATIVE }
        
        // Tier 2 and 3 can resolve in parallel (they're less critical)
        val (tier2Result, tier3Result) = coroutineScope {
            val deferredTier2 = if (tier2Domains.isNotEmpty()) {
                async<WarmUpResult> {
                    Log.i(TAG, "⚡ Tier 2 warm-up: ${tier2Domains.size} high priority domains (20 threads)...")
                    warmUpDomainsTiered(tier2Domains.map { it.domain }, tier = 2, maxConcurrency = 20)
                }
            } else null
            
            val deferredTier3 = if (tier3Domains.isNotEmpty()) {
                async<WarmUpResult> {
                    Log.i(TAG, "📦 Tier 3 warm-up: ${tier3Domains.size} speculative domains (20 threads)...")
                    warmUpDomainsTiered(tier3Domains.map { it.domain }, tier = 3, maxConcurrency = 20)
                }
            } else null
            
            // Wait for Tier 2 and 3 to complete
            Pair(deferredTier2?.await(), deferredTier3?.await())
        }
        
        // Log Tier 2 and 3 completion
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
        
        // Enhanced completion log with detailed statistics
        Log.i(TAG, "✅ Enhanced DNS cache warm-up completed: $totalSuccess/$totalDomains domains resolved in ${totalElapsed}ms (${totalSuccessRate}% success rate)")
        Log.i(TAG, "📊 Warm-up statistics - Tier 1: ${tier1Results?.success ?: 0}/${tier1Domains.size} (${tier1Results?.elapsed ?: 0}ms), Tier 2: ${tier2Result?.success ?: 0}/${tier2Domains.size} (${tier2Result?.elapsed ?: 0}ms), Tier 3: ${tier3Result?.success ?: 0}/${tier3Domains.size} (${tier3Result?.elapsed ?: 0}ms)")
        Log.i(TAG, "📈 Warm-up performance: $totalSuccess successes, $totalFailed failures, average ${if (totalSuccess > 0) (totalElapsed / totalSuccess) else 0}ms per domain")
        
        // Track warm-up success rate for monitoring
        trackWarmUpStats(totalSuccess, totalFailed, totalElapsed, totalSuccessRate)
        
        return WarmUpResult(totalSuccess, totalFailed, totalDomains, totalElapsed)
    }
    
    /**
     * Warm-up domains in a specific tier (with retry mechanism, subdomain prefetching, and enhanced statistics)
     * For Tier 1: Also performs "Pre-Connection" Warm-up (TCP Fast Open) to warm up Xray routing table
     */
    private suspend fun warmUpDomainsTiered(
        domains: List<String>,
        tier: Int,
        maxConcurrency: Int,
        prefetchSubdomains: Boolean = tier <= 2 // Only prefetch subdomains for critical and high priority tiers
    ): WarmUpResult {
        val startTime = System.currentTimeMillis()
        var successCount = 0
        var failedCount = 0
        val subdomainSuccessCount = AtomicInteger(0)
        val subdomainTotalCount = AtomicInteger(0)
        
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
                                    
                                    // Pre-Connection Warm-up (TCP Fast Open) for Tier 1 domains
                                    if (tier == 1) {
                                        scope.launch {
                                            try {
                                                // Open a lightweight TCP socket to port 443 to warm up Xray routing table
                                                addresses.firstOrNull()?.let { address ->
                                                    try {
                                                        val socket = Socket()
                                                        socket.soTimeout = 1000 // 1 second timeout
                                                        socket.connect(java.net.InetSocketAddress(address, 443), 1000)
                                                        socket.close()
                                                        Log.d(TAG, "✅ Pre-connection warm-up [Tier $tier]: $domain -> ${address.hostAddress}:443")
                                                    } catch (e: Exception) {
                                                        // Ignore pre-connection errors (non-critical)
                                                        Log.d(TAG, "⚠️ Pre-connection warm-up failed for $domain: ${e.message}")
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                // Ignore pre-connection errors
                                            }
                                        }
                                    }
                                    
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
                                    delay(100L * attempt) // Exponential backoff
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
            delay(2000) // Wait 2 seconds for subdomain prefetching to complete
            val subdomainSuccess = subdomainSuccessCount.get()
            val subdomainTotal = subdomainTotalCount.get()
            if (subdomainTotal > 0) {
                Log.d(TAG, "📊 Tier $tier subdomain prefetch: $subdomainSuccess/$subdomainTotal subdomains resolved")
            }
        }
        
        return WarmUpResult(successCount, failedCount, domains.size, elapsed)
    }
    
    /**
     * Generate related subdomains for a given domain (for prefetching)
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
     * Track warm-up statistics for monitoring
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
                Log.i(TAG, "📊 Warm-up stats: ${warmUpStats.totalWarmUps} warm-ups, ${String.format("%.1f", warmUpStats.averageSuccessRate)}% avg success rate, ${warmUpStats.averageElapsed}ms avg elapsed")
            }
        }
    }
    
    /**
     * Get warm-up statistics
     */
    fun getWarmUpStats(): WarmUpStats {
        return synchronized(warmUpStats) {
            warmUpStats.copy()
        }
    }
}

