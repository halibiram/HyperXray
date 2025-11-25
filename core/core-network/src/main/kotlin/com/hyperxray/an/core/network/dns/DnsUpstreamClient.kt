package com.hyperxray.an.core.network.dns

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "DnsUpstreamClient"
private const val DNS_PORT = 53
private const val BUFFER_SIZE = 512
private const val DEFAULT_TIMEOUT_MS = 1000L // Default timeout: 1000ms
private const val MAX_TIMEOUT_MS = 3000L // Maximum timeout: 3000ms
private const val HAPPY_EYEBALLS_WAVE_DELAY_MS = 400L // Wave 2 delay: 400ms
private const val TOP_FASTEST_SERVERS = 3 // Query only top 3 fastest servers initially
private const val SERVERS_PER_WAVE = 3 // Servers per wave

/**
 * DNS server performance statistics
 */
data class DnsServerStats(
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

/**
 * Singleton DoH client provider to avoid creating multiple thread pools
 * This optimizes memory usage by reusing a single OkHttpClient instance
 */
object DoHClientProvider {
    @Volatile
    private var dohClient: OkHttpClient? = null
    
    @Volatile
    private var dohProviders: List<DnsOverHttps>? = null
    
    /**
     * Get or create DoH providers (singleton pattern)
     */
    fun getDoHProviders(): List<DnsOverHttps> {
        if (dohProviders != null) {
            return dohProviders!!
        }
        
        synchronized(this) {
            if (dohProviders != null) {
                return dohProviders!!
            }
            
            // Create shared OkHttpClient with connection pooling
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
            
            dohClient = dnsClient
            
            dohProviders = listOf(
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
            
            return dohProviders!!
        }
    }
    
    /**
     * Close DoH client resources (call when app is shutting down)
     */
    fun close() {
        synchronized(this) {
            dohClient?.dispatcher?.executorService?.shutdown()
            dohClient = null
            dohProviders = null
        }
    }
}

/**
 * DNS Upstream Client
 * Handles all upstream logic (UDP, DoH, DoT, Fallbacks) and "Happy Eyeballs" algorithm
 */
class DnsUpstreamClient(
    private val socketPool: DnsSocketPool
) {
    @Volatile
    private var socks5Client: Socks5UdpClient? = null
    
    /**
     * Update SOCKS5 client dynamically
     */
    fun setSocks5Client(client: Socks5UdpClient?) {
        socks5Client = client
    }
    
    private val dnsServerStats = ConcurrentHashMap<String, DnsServerStats>()
    private val adaptiveTimeouts = ConcurrentHashMap<String, Long>()
    private val HEALTH_CHECK_INTERVAL_MS = 60000L // 60 seconds
    private val MAX_FAILURES_BEFORE_UNHEALTHY = 5
    private val MIN_SUCCESS_RATE = 0.3 // 30% success rate minimum
    
    // Query deduplication: track pending queries to avoid duplicate upstream requests
    private data class PendingQuery(
        val deferred: CompletableDeferred<ByteArray?>,
        val timestamp: Long = System.currentTimeMillis()
    )
    private val pendingQueries = ConcurrentHashMap<String, PendingQuery>()
    private val QUERY_DEDUP_TIMEOUT_MS = 5000L // 5 seconds max wait for duplicate queries
    
    /**
     * Initialize DNS servers with default list
     */
    fun initializeServers(servers: List<InetAddress>) {
        servers.forEach { server ->
            dnsServerStats[server.hostAddress] = DnsServerStats(server)
            adaptiveTimeouts[server.hostAddress] = DEFAULT_TIMEOUT_MS
        }
    }
    
    /**
     * Get DNS servers sorted by performance (fastest first)
     * Unhealthy servers are excluded or placed last
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
                .thenBy { estimateGeographicDistance(it.server) })
            .map { it.server }
    }
    
    /**
     * Estimate geographic distance based on DNS server IP ranges
     */
    private fun estimateGeographicDistance(server: InetAddress): Int {
        val ip = server.hostAddress
        return when {
            ip.startsWith("1.1.1.") || ip.startsWith("1.0.0.") -> 0 // Cloudflare
            ip.startsWith("8.8.") -> 1 // Google
            ip.startsWith("9.9.9.") || ip.startsWith("149.112.") -> 2 // Quad9
            ip.startsWith("208.67.") -> 3 // OpenDNS
            ip.startsWith("94.140.") -> 4 // AdGuard
            else -> 5 // Unknown
        }
    }
    
    /**
     * Get adaptive timeout for a DNS server based on its performance
     */
    private fun getAdaptiveTimeout(server: InetAddress): Long {
        val stats = dnsServerStats[server.hostAddress] ?: return DEFAULT_TIMEOUT_MS
        val currentTimeout = adaptiveTimeouts[server.hostAddress] ?: DEFAULT_TIMEOUT_MS
        
        // Adjust timeout based on average latency
        val avgLatency = stats.averageLatency
        return when {
            avgLatency < 50 -> (DEFAULT_TIMEOUT_MS * 0.8).toLong().coerceAtLeast((DEFAULT_TIMEOUT_MS * 0.5).toLong())
            avgLatency < 100 -> DEFAULT_TIMEOUT_MS
            avgLatency < 200 -> (DEFAULT_TIMEOUT_MS * 1.5).toLong().coerceAtMost(MAX_TIMEOUT_MS)
            else -> MAX_TIMEOUT_MS
        }
    }
    
    /**
     * Forward DNS query to upstream DNS servers with "Happy Eyeballs" algorithm
     * Stop Flood: Query only Top 3 Fastest servers initially
     * Wave 2: If no response in 400ms, query next 3
     * Winner Takes All: Cancel pending queries safely upon first success
     */
    suspend fun forwardQuery(
        queryData: ByteArray,
        hostname: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ByteArray? {
        val lowerHostname = hostname.lowercase()
        
        // Check for duplicate query (query deduplication)
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
        
        // Create new deferred for this query
        val deferred = CompletableDeferred<ByteArray?>()
        pendingQueries[lowerHostname] = PendingQuery(deferred)
        
        // Use supervisorScope to ensure one child's failure doesn't crash siblings
        val startTime = System.currentTimeMillis()
        return supervisorScope {
            try {
                Log.d(TAG, "🔍 DNS UPSTREAM QUERY START: $hostname (query size: ${queryData.size} bytes, timeout: ${timeoutMs}ms)")
                
                // Try UDP first with Happy Eyeballs
                val udpStartTime = System.currentTimeMillis()
                val result = forwardToUpstreamDnsWithHappyEyeballs(queryData, hostname, timeoutMs)
                val udpDuration = System.currentTimeMillis() - udpStartTime
                
                if (result != null) {
                    val totalDuration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "✅ DNS UPSTREAM QUERY SUCCESS (UDP): $hostname -> ${result.size} bytes (UDP: ${udpDuration}ms, total: ${totalDuration}ms)")
                    deferred.complete(result)
                    pendingQueries.remove(lowerHostname)
                    return@supervisorScope result
                }
                Log.d(TAG, "⚠️ DNS UPSTREAM QUERY UDP FAILED: $hostname (UDP: ${udpDuration}ms), trying DoT fallback...")
                
                // Try DoT fallback if UDP failed
                val dotStartTime = System.currentTimeMillis()
                val dotResult = tryDoTFallback(queryData, hostname)
                val dotDuration = System.currentTimeMillis() - dotStartTime
                if (dotResult != null) {
                    val totalDuration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "✅ DNS UPSTREAM QUERY SUCCESS (DoT): $hostname -> ${dotResult.size} bytes (DoT: ${dotDuration}ms, total: ${totalDuration}ms)")
                    deferred.complete(dotResult)
                    pendingQueries.remove(lowerHostname)
                    return@supervisorScope dotResult
                }
                Log.d(TAG, "⚠️ DNS UPSTREAM QUERY DoT FAILED: $hostname (DoT: ${dotDuration}ms), trying DoH fallback...")
                
                // Try DoH fallback if DoT failed
                val dohStartTime = System.currentTimeMillis()
                val dohResult = tryDoHFallback(hostname)
                val dohDuration = System.currentTimeMillis() - dohStartTime
                if (dohResult != null) {
                    val totalDuration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "✅ DNS UPSTREAM QUERY SUCCESS (DoH): $hostname -> ${dohResult.size} bytes (DoH: ${dohDuration}ms, total: ${totalDuration}ms)")
                    deferred.complete(dohResult)
                    pendingQueries.remove(lowerHostname)
                    return@supervisorScope dohResult
                }
                Log.d(TAG, "⚠️ DNS UPSTREAM QUERY DoH FAILED: $hostname (DoH: ${dohDuration}ms), trying TCP fallback...")
                
                // Try TCP DNS fallback if DoH failed
                val tcpStartTime = System.currentTimeMillis()
                val tcpResult = tryTcpDnsFallback(queryData, hostname)
                val tcpDuration = System.currentTimeMillis() - tcpStartTime
                if (tcpResult != null) {
                    val totalDuration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "✅ DNS UPSTREAM QUERY SUCCESS (TCP): $hostname -> ${tcpResult.size} bytes (TCP: ${tcpDuration}ms, total: ${totalDuration}ms)")
                    deferred.complete(tcpResult)
                    pendingQueries.remove(lowerHostname)
                    return@supervisorScope tcpResult
                }
                
                // Complete with null on failure
                val totalDuration = System.currentTimeMillis() - startTime
                Log.e(TAG, "❌ DNS UPSTREAM QUERY FAILED: All methods failed for $hostname (UDP: ${udpDuration}ms, DoT: ${dotDuration}ms, DoH: ${dohDuration}ms, TCP: ${tcpDuration}ms, total: ${totalDuration}ms)")
                deferred.complete(null)
                pendingQueries.remove(lowerHostname)
                null
            } catch (e: Exception) {
                val totalDuration = System.currentTimeMillis() - startTime
                Log.e(TAG, "❌ DNS UPSTREAM QUERY ERROR: Exception for $hostname (duration: ${totalDuration}ms)", e)
                deferred.completeExceptionally(e)
                pendingQueries.remove(lowerHostname)
                throw e
            }
        }
    }
    
    /**
     * Forward DNS query with "Happy Eyeballs" tiered strategy
     * Step 1: Query top 3 fastest servers
     * Step 2: If no response in 400ms, launch queries to next 3 servers
     * This prevents network congestion and "Self-DoS" scenarios
     */
    private suspend fun forwardToUpstreamDnsWithHappyEyeballs(
        queryData: ByteArray,
        hostname: String,
        timeoutMs: Long
    ): ByteArray? {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            
            // Use performance-sorted DNS servers (fastest first)
            val sortedServers = getSortedDnsServers()
            if (sortedServers.isEmpty()) {
                Log.w(TAG, "No DNS servers available for $hostname")
                return@withContext null
            }
            
            // Filter out unhealthy servers
            val healthyServers = sortedServers.filter { dnsServer ->
                val stats = dnsServerStats[dnsServer.hostAddress]
                if (stats != null && !stats.isHealthy) {
                    val now = System.currentTimeMillis()
                    val recentlyFailed = now - stats.lastFailureTime < HEALTH_CHECK_INTERVAL_MS
                    !recentlyFailed
                } else {
                    true
                }
            }
            
            val serversToUse = if (healthyServers.isNotEmpty()) healthyServers else sortedServers
            
            // Happy Eyeballs: Query Top 3 Fastest servers initially
            val wave1Servers = serversToUse.take(TOP_FASTEST_SERVERS)
            Log.d(TAG, "🔍 Happy Eyeballs Wave 1: Querying top ${wave1Servers.size} fastest servers for $hostname")
            
            // Launch parallel queries for Wave 1
            val wave1DeferredResults = wave1Servers.mapIndexed { index, dnsServer ->
                async {
                    try {
                        val adaptiveTimeout = getAdaptiveTimeout(dnsServer).coerceAtMost(timeoutMs)
                        withTimeoutOrNull(adaptiveTimeout) {
                            queryDnsServerUdp(queryData, hostname, dnsServer, adaptiveTimeout)
                        }
                    } catch (e: CancellationException) {
                        null
                    } catch (e: Exception) {
                        updateDnsServerStats(dnsServer, 0, success = false)
                        null
                    }
                }
            }
            
            // Wait for first successful response or timeout
            var selectedResult: ByteArray? = null
            var foundResult = false
            
            try {
                while (!foundResult && wave1DeferredResults.any { it.isActive }) {
                    val result = select<ByteArray?> {
                        wave1DeferredResults.forEachIndexed { index, deferred ->
                            if (deferred.isActive) {
                                deferred.onAwait { result ->
                                    result
                                }
                            }
                        }
                    }
                    
                    if (result != null) {
                        selectedResult = result
                        foundResult = true
                    } else {
                        if (!wave1DeferredResults.any { it.isActive }) {
                            break
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Expected during cancellation
            } catch (e: Exception) {
                Log.d(TAG, "Error in select for Wave 1: ${e.message}")
            }
            
            if (selectedResult != null) {
                // Winner Takes All: Cancel remaining queries
                wave1DeferredResults.forEach { deferred ->
                    if (deferred.isActive) {
                        try {
                            deferred.cancel()
                        } catch (e: Exception) {
                            // Ignore cancellation errors
                        }
                    }
                }
                
                val totalElapsed = System.currentTimeMillis() - startTime
                val serverIndex = wave1DeferredResults.indexOfFirst { it.isCompleted && it.getCompleted() == selectedResult }
                val server = if (serverIndex >= 0) wave1Servers[serverIndex] else wave1Servers.first()
                Log.d(TAG, "✅ DNS response from ${server.hostAddress} for $hostname (Wave 1, total: ${totalElapsed}ms)")
                return@withContext selectedResult
            }
            
            // Wave 2: If no response in 400ms, query next 3 servers
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < HAPPY_EYEBALLS_WAVE_DELAY_MS && wave1Servers.size < serversToUse.size) {
                val remainingDelay = HAPPY_EYEBALLS_WAVE_DELAY_MS - elapsed
                Log.d(TAG, "⏳ No response from Wave 1 after ${elapsed}ms, waiting ${remainingDelay}ms before Wave 2...")
                delay(remainingDelay)
            }
            
            // Wait for all Wave 1 queries to complete or be cancelled
            wave1DeferredResults.forEach { deferred ->
                try {
                    deferred.await()
                } catch (e: CancellationException) {
                    // Expected when cancelled
                } catch (e: Exception) {
                    // Ignore other errors
                }
            }
            
            // Check if we got a result from Wave 1
            if (selectedResult != null) {
                return@withContext selectedResult
            }
            
            // Wave 2: Query next 3 servers
            val wave2Start = TOP_FASTEST_SERVERS
            val wave2End = minOf(wave2Start + SERVERS_PER_WAVE, serversToUse.size)
            if (wave2Start < serversToUse.size) {
                val wave2Servers = serversToUse.subList(wave2Start, wave2End)
                Log.d(TAG, "🔍 Happy Eyeballs Wave 2: Querying next ${wave2Servers.size} servers for $hostname")
                
                val wave2DeferredResults = wave2Servers.mapIndexed { index, dnsServer ->
                    async {
                        try {
                            val adaptiveTimeout = getAdaptiveTimeout(dnsServer).coerceAtMost(timeoutMs)
                            withTimeoutOrNull(adaptiveTimeout) {
                                queryDnsServerUdp(queryData, hostname, dnsServer, adaptiveTimeout)
                            }
                        } catch (e: CancellationException) {
                            null
                        } catch (e: Exception) {
                            updateDnsServerStats(dnsServer, 0, success = false)
                            null
                        }
                    }
                }
                
                // Wait for first successful response
                var wave2Result: ByteArray? = null
                try {
                    while (wave2Result == null && wave2DeferredResults.any { it.isActive }) {
                        val result = select<ByteArray?> {
                            wave2DeferredResults.forEachIndexed { index, deferred ->
                                if (deferred.isActive) {
                                    deferred.onAwait { result ->
                                        result
                                    }
                                }
                            }
                        }
                        
                        if (result != null) {
                            wave2Result = result
                        } else {
                            if (!wave2DeferredResults.any { it.isActive }) {
                                break
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    // Expected during cancellation
                } catch (e: Exception) {
                    Log.d(TAG, "Error in select for Wave 2: ${e.message}")
                }
                
                if (wave2Result != null) {
                    // Winner Takes All: Cancel remaining queries
                    wave2DeferredResults.forEach { deferred ->
                        if (deferred.isActive) {
                            try {
                                deferred.cancel()
                            } catch (e: Exception) {
                                // Ignore cancellation errors
                            }
                        }
                    }
                    
                    val totalElapsed = System.currentTimeMillis() - startTime
                    val serverIndex = wave2DeferredResults.indexOfFirst { it.isCompleted && it.getCompleted() == wave2Result }
                    val server = if (serverIndex >= 0) wave2Servers[serverIndex] else wave2Servers.first()
                    Log.d(TAG, "✅ DNS response from ${server.hostAddress} for $hostname (Wave 2, total: ${totalElapsed}ms)")
                    return@withContext wave2Result
                }
            }
            
            val totalElapsed = System.currentTimeMillis() - startTime
            Log.w(TAG, "❌ No DNS response from any upstream server for $hostname (total: ${totalElapsed}ms)")
            null
        }
    }
    
    /**
     * Query a single DNS server via UDP with retry logic
     * Uses SafeSocket to prevent "Closed Pipe" crashes
     */
    private suspend fun queryDnsServerUdp(
        queryData: ByteArray,
        hostname: String,
        dnsServer: InetAddress,
        timeoutMs: Long
    ): ByteArray? {
        return supervisorScope {
            // Wrap critical DNS query with retry logic
            try {
                retryWithBackoff(times = 3, initialDelay = 100, factor = 2.0) {
                    // Use ephemeral socket for this query to avoid race conditions
                    val safeSocket = socketPool.createEphemeralSocket(timeoutMs)
                    
                    try {
                        // Compress DNS query if possible
                        val compressedQuery = DnsCompression.compressQuery(queryData, hostname)
                        
                        val requestPacket = java.net.DatagramPacket(
                            compressedQuery,
                            compressedQuery.size,
                            InetSocketAddress(dnsServer, DNS_PORT)
                        )
                        
                        val sendStart = System.currentTimeMillis()
                        
                        // Robust send with error handling
                        if (!safeSocket.send(requestPacket)) {
                            throw IOException("Failed to send DNS query packet")
                        }
                        
                        // Robust receive with error handling
                        val responseBuffer = ByteArray(BUFFER_SIZE)
                        val responsePacket = java.net.DatagramPacket(responseBuffer, responseBuffer.size)
                        
                        if (!safeSocket.receive(responsePacket)) {
                            throw IOException("Failed to receive DNS response packet")
                        }
                        
                        val response = ByteArray(responsePacket.length)
                        System.arraycopy(responsePacket.data, 0, response, 0, responsePacket.length)
                        
                        val elapsed = System.currentTimeMillis() - sendStart
                        Log.d(TAG, "📥 [DIRECT] DNS response received via direct UDP from ${dnsServer.hostAddress}: ${response.size} bytes (${elapsed}ms)")
                        
                        // Update adaptive timeout based on performance
                        val currentTimeout = adaptiveTimeouts[dnsServer.hostAddress] ?: DEFAULT_TIMEOUT_MS
                        val newTimeout = when {
                            elapsed < currentTimeout * 0.7 -> (currentTimeout * 0.9).toLong().coerceAtLeast((DEFAULT_TIMEOUT_MS * 0.5).toLong())
                            elapsed > currentTimeout * 1.5 -> (currentTimeout * 1.2).toLong().coerceAtMost(MAX_TIMEOUT_MS)
                            else -> currentTimeout
                        }
                        adaptiveTimeouts[dnsServer.hostAddress] = newTimeout
                        
                        // Update performance stats for this DNS server (success)
                        updateDnsServerStats(dnsServer, elapsed, success = true)
                        
                        response
                    } finally {
                        safeSocket.close()
                    }
                }
            } catch (e: CancellationException) {
                null
            } catch (e: Exception) {
                // Try SOCKS5 fallback if available after retries exhausted
                Log.d(TAG, "⚠️ [DIRECT] Direct UDP failed for ${dnsServer.hostAddress} after retries: ${e.message}, trying SOCKS5 fallback...")
                trySocks5Fallback(queryData, hostname, dnsServer, timeoutMs)
            }
        }
    }
    
    /**
     * Try SOCKS5 fallback when direct UDP fails
     * 
     * CRITICAL: Uses new sendUdpAndReceive() method which handles Transaction ID matching
     * The Transaction ID from the original query is preserved in the response
     */
    private suspend fun trySocks5Fallback(
        queryData: ByteArray,
        hostname: String,
        dnsServer: InetAddress,
        timeoutMs: Long
    ): ByteArray? {
        val proxy = socks5Client ?: return null
        
        return try {
            val compressedQuery = DnsCompression.compressQuery(queryData, hostname)
            val sendStart = System.currentTimeMillis()
            Log.d(TAG, "🔌 [SOCKS5] Fallback: Using SOCKS5 proxy for DNS query: $hostname → ${dnsServer.hostAddress}:$DNS_PORT")
            
            // Use new sendUdpAndReceive() method with Transaction ID matching
            // This ensures the response matches the query's Transaction ID
            val response = proxy.sendUdpAndReceive(
                compressedQuery,
                dnsServer,
                DNS_PORT,
                timeoutMs.toInt().coerceAtMost(5000) // Cap at 5 seconds
            )
            
            if (response != null) {
                val elapsed = System.currentTimeMillis() - sendStart
                Log.d(TAG, "📥 [SOCKS5] DNS response received via SOCKS5: ${response.size} bytes (${elapsed}ms)")
                
                // Verify Transaction ID matches (sanity check)
                val queryTxId = extractTransactionId(queryData)
                val responseTxId = extractTransactionId(response)
                if (queryTxId != null && responseTxId != null && queryTxId != responseTxId) {
                    Log.w(TAG, "⚠️ [SOCKS5] Transaction ID mismatch: query=0x%04x, response=0x%04x".format(queryTxId, responseTxId))
                    // Still return response - dispatcher should have matched correctly
                }
                
                // Update adaptive timeout and stats
                val currentTimeout = adaptiveTimeouts[dnsServer.hostAddress] ?: DEFAULT_TIMEOUT_MS
                val newTimeout = when {
                    elapsed < currentTimeout * 0.7 -> (currentTimeout * 0.9).toLong().coerceAtLeast((DEFAULT_TIMEOUT_MS * 0.5).toLong())
                    elapsed > currentTimeout * 1.5 -> (currentTimeout * 1.2).toLong().coerceAtMost(MAX_TIMEOUT_MS)
                    else -> currentTimeout
                }
                adaptiveTimeouts[dnsServer.hostAddress] = newTimeout
                updateDnsServerStats(dnsServer, elapsed, success = true)
                
                Log.d(TAG, "✅ DNS response via SOCKS5 proxy (fallback) from ${dnsServer.hostAddress} for $hostname (${elapsed}ms)")
                response
            } else {
                Log.w(TAG, "⚠️ [SOCKS5] No DNS response received via SOCKS5 proxy (timeout: ${timeoutMs}ms)")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "❌ [SOCKS5] SOCKS5 UDP proxy fallback failed for ${dnsServer.hostAddress}: ${e.message}")
            null
        }
    }
    
    /**
     * Extract Transaction ID from DNS packet (first 2 bytes, big-endian)
     */
    private fun extractTransactionId(dnsPacket: ByteArray): Int? {
        if (dnsPacket.size < 2) return null
        return ByteBuffer.wrap(dnsPacket, 0, 2)
            .order(ByteOrder.BIG_ENDIAN)
            .short.toInt() and 0xFFFF
    }
    
    /**
     * DNS over TLS (DoT) fallback when UDP DNS fails
     */
    private suspend fun tryDoTFallback(queryData: ByteArray, hostname: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val timeoutMs = DEFAULT_TIMEOUT_MS
            
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
                                val addresses = DnsResponseParser.parseResponse(responseData, hostname)
                                if (addresses.isNotEmpty()) {
                                    DnsCacheManager.saveToCache(hostname, addresses)
                                }
                                
                                return@withTimeoutOrNull responseData
                            }
                            null
                        } catch (e: Exception) {
                            Log.d(TAG, "⚠️ DoT server ${index + 1} failed for $hostname: ${e.message}")
                            null
                        }
                    }
                }
            }
            
            // Get first successful result
            val results = deferredResults.awaitAll()
            for ((index, result) in results.withIndex()) {
                if (result != null) {
                    // Cancel remaining queries
                    deferredResults.forEachIndexed { idx, deferred ->
                        if (idx > index) {
                            deferred.cancel()
                        }
                    }
                    return@withContext result
                }
            }
            
            Log.d(TAG, "⚠️ DoT fallback failed for $hostname, trying DoH...")
            null
        }
    }
    
    /**
     * DNS over HTTPS (DoH) fallback when UDP DNS fails
     */
    private suspend fun tryDoHFallback(hostname: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val timeoutMs = DEFAULT_TIMEOUT_MS
            
            Log.d(TAG, "🔄 Trying DoH fallback for $hostname (real DNS over HTTPS)...")
            
            try {
                // Get DoH providers from singleton
                val dohProviders = DoHClientProvider.getDoHProviders()
                
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
                    val queryData = DnsResponseBuilder.buildQuery(hostname) ?: return@withContext null
                    val responseData = DnsResponseBuilder.buildResponse(queryData, selectedResult)
                    
                    // Cache the result immediately for faster subsequent access
                    DnsCacheManager.saveToCache(hostname, selectedResult)
                    
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i(TAG, "✅ DoH fallback successful for $hostname (${elapsed}ms)")
                    return@withContext responseData
                }
            } catch (e: Exception) {
                Log.d(TAG, "⚠️ DoH fallback failed for $hostname: ${e.message}, trying TCP DNS...")
            }
            
            // All DoH providers failed - try TCP DNS
            val queryData = DnsResponseBuilder.buildQuery(hostname) ?: return@withContext null
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
     * TCP DNS fallback when UDP and DoT fail
     */
    private suspend fun tryTcpDnsFallback(queryData: ByteArray, hostname: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val timeoutMs = DEFAULT_TIMEOUT_MS
            
            Log.d(TAG, "🔄 Trying TCP DNS fallback for $hostname...")
            
            // Try primary DNS servers via TCP
            val tcpDnsServers = listOf(
                "8.8.8.8",      // Google
                "1.1.1.1",      // Cloudflare
                "9.9.9.9"       // Quad9
            )
            
            // Try TCP DNS servers in parallel
            val deferredResults = tcpDnsServers.mapIndexed { index, serverHost ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(timeoutMs) {
                        try {
                            val serverAddress = InetAddress.getByName(serverHost)
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
                                val providerName = when (index) {
                                    0 -> "Google"
                                    1 -> "Cloudflare"
                                    2 -> "Quad9"
                                    else -> "Unknown"
                                }
                                val elapsed = System.currentTimeMillis() - startTime
                                Log.i(TAG, "✅ DNS resolved via TCP DNS ($providerName): $hostname (${elapsed}ms)")
                                
                                // Parse and cache result
                                val addresses = DnsResponseParser.parseResponse(responseData, hostname)
                                if (addresses.isNotEmpty()) {
                                    DnsCacheManager.saveToCache(hostname, addresses)
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
            for ((index, result) in results.withIndex()) {
                if (result != null) {
                    // Cancel remaining queries
                    deferredResults.forEachIndexed { idx, deferred ->
                        if (idx > index) {
                            deferred.cancel()
                        }
                    }
                    return@withContext result
                }
            }
            
            Log.d(TAG, "⚠️ TCP DNS fallback failed for $hostname")
            null
        }
    }
    
    /**
     * System DNS fallback as last resort
     */
    private suspend fun trySystemDnsFallback(hostname: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🔄 Trying system DNS fallback for $hostname...")
                val addresses = InetAddress.getAllByName(hostname)
                if (addresses.isNotEmpty()) {
                    // Build DNS response packet from resolved addresses
                    val queryData = DnsResponseBuilder.buildQuery(hostname) ?: return@withContext null
                    val responseData = DnsResponseBuilder.buildResponse(queryData, addresses.toList())
                    
                    // Cache the result
                    DnsCacheManager.saveToCache(hostname, addresses.toList())
                    
                    Log.i(TAG, "✅ System DNS fallback successful for $hostname -> ${addresses.map { it.hostAddress }}")
                    return@withContext responseData
                }
            } catch (e: Exception) {
                Log.w(TAG, "System DNS fallback failed for $hostname: ${e.message}")
            }
            null
        }
    }
    
    /**
     * Update DNS server performance stats
     */
    private fun updateDnsServerStats(server: InetAddress, latency: Long, success: Boolean = true) {
        val stats = dnsServerStats[server.hostAddress] ?: return
        
        if (success) {
            stats.successCount++
            stats.totalLatency += latency
            stats.lastSuccessTime = System.currentTimeMillis()
            
            // Mark as healthy if it was unhealthy but now succeeding
            if (!stats.isHealthy && stats.successCount >= 2) {
                stats.isHealthy = true
                stats.failureCount = 0
                Log.d(TAG, "✅ DNS server ${server.hostAddress} recovered and marked as healthy")
            }
        } else {
            stats.failureCount++
            stats.lastFailureTime = System.currentTimeMillis()
            
            // Mark as unhealthy if too many failures
            val totalAttempts = stats.successCount + stats.failureCount
            if (totalAttempts >= 3 && (stats.failureCount >= MAX_FAILURES_BEFORE_UNHEALTHY || stats.successRate < MIN_SUCCESS_RATE)) {
                stats.isHealthy = false
                Log.w(TAG, "⚠️ DNS server ${server.hostAddress} marked as unhealthy (failures: ${stats.failureCount}/${totalAttempts}, success rate: ${(stats.successRate * 100).toInt()}%)")
            }
        }
    }
    
    /**
     * Update upstream DNS servers
     */
    fun setUpstreamDnsServers(servers: List<InetAddress>) {
        servers.forEach { server ->
            if (!dnsServerStats.containsKey(server.hostAddress)) {
                dnsServerStats[server.hostAddress] = DnsServerStats(server)
                adaptiveTimeouts[server.hostAddress] = DEFAULT_TIMEOUT_MS
            }
        }
        Log.i(TAG, "✅ Upstream DNS servers updated: ${servers.map { it.hostAddress }}")
    }
}
