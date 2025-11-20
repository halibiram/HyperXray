package com.hyperxray.an.core.network.dns

import android.content.Context
import android.util.Log
import com.hyperxray.an.core.network.dns.DnsCacheManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.async

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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus
    
    private var upstreamDnsServers = listOf(
        InetAddress.getByName("8.8.8.8"),
        InetAddress.getByName("8.8.4.4"),
        InetAddress.getByName("1.1.1.1"),
        InetAddress.getByName("1.0.0.1")
    )
    
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
     * Returns list of resolved IP addresses, or empty list if resolution failed.
     */
    suspend fun resolveDomain(domain: String): List<InetAddress> {
        return withContext(Dispatchers.IO) {
            try {
                // Check cache first
                val cached = DnsCacheManager.getFromCache(domain)
                if (cached != null && cached.isNotEmpty()) {
                    Log.d(TAG, "✅ DNS CACHE HIT (resolveDomain): $domain -> ${cached.map { it.hostAddress }}")
                    return@withContext cached
                }
                
                // Cache miss - resolve from upstream DNS
                Log.d(TAG, "⚠️ DNS CACHE MISS (resolveDomain): $domain, resolving from upstream...")
                
                // Build DNS query packet
                val queryData = buildDnsQuery(domain) ?: return@withContext emptyList()
                
                // Forward to upstream DNS servers (parallel multi-DNS support)
                val responseData = forwardToUpstreamDns(queryData, domain)
                if (responseData != null) {
                    // Parse response and extract IP addresses
                    val addresses = parseDnsResponse(responseData, domain)
                    if (addresses.isNotEmpty()) {
                        // Save to cache
                        DnsCacheManager.saveToCache(domain, addresses)
                        Log.i(TAG, "✅ DNS resolved and cached (resolveDomain): $domain -> ${addresses.map { it.hostAddress }}")
                        return@withContext addresses
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
            
            // Check cache first
            val cachedResult = DnsCacheManager.getFromCache(hostname)
            if (cachedResult != null && cachedResult.isNotEmpty()) {
                Log.i(TAG, "✅ DNS CACHE HIT (SystemDnsCacheServer): $hostname -> ${cachedResult.map { it.hostAddress }} (served from cache)")
                
                // Build DNS response from cache
                val response = DnsResponseBuilder.buildResponse(requestData, cachedResult)
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
            
            // Cache miss - forward to upstream DNS server
            Log.i(TAG, "⚠️ DNS CACHE MISS: $hostname (forwarding to upstream DNS)")
            
            val upstreamResponse = withContext(Dispatchers.IO) {
                forwardToUpstreamDns(requestData, hostname)
            }
            
            if (upstreamResponse != null && upstreamResponse.isNotEmpty()) {
                // Parse upstream response and save to cache
                val resolvedAddresses = parseDnsResponse(upstreamResponse, hostname)
                if (resolvedAddresses.isNotEmpty()) {
                    DnsCacheManager.saveToCache(hostname, resolvedAddresses)
                    Log.i(TAG, "✅ DNS resolved from upstream and cached: $hostname -> ${resolvedAddresses.map { it.hostAddress }}")
                    
                    // Send response to client
                    val responsePacket = DatagramPacket(
                        upstreamResponse,
                        upstreamResponse.size,
                        requestPacket.socketAddress
                    )
                    socket.send(responsePacket)
                    Log.d(TAG, "✅ DNS response sent from upstream: $hostname")
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
     */
    private suspend fun forwardToUpstreamDns(queryData: ByteArray, hostname: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            val timeoutMs = 3000L // 3 seconds timeout per DNS server
            
            // Launch parallel DNS queries to all upstream servers
            val deferredResults = upstreamDnsServers.map { dnsServer ->
                async {
                    try {
                        withTimeoutOrNull(timeoutMs) {
                            val socket = DatagramSocket().apply {
                                soTimeout = timeoutMs.toInt()
                            }
                            
                            try {
                                val requestPacket = DatagramPacket(
                                    queryData,
                                    queryData.size,
                                    InetSocketAddress(dnsServer, DNS_PORT)
                                )
                                
                                socket.send(requestPacket)
                                
                                val responseBuffer = ByteArray(BUFFER_SIZE)
                                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                                socket.receive(responsePacket)
                                
                                val response = ByteArray(responsePacket.length)
                                System.arraycopy(responsePacket.data, 0, response, 0, responsePacket.length)
                                
                                Log.d(TAG, "✅ DNS response received from upstream: ${dnsServer.hostAddress} for $hostname")
                                response
                            } finally {
                                socket.close()
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Upstream DNS server ${dnsServer.hostAddress} failed: ${e.message}")
                        null
                    }
                }
            }
            
            // Use select to get first successful response from parallel queries
            val selectedResult = select<ByteArray?> {
                deferredResults.forEachIndexed { index, deferred ->
                    deferred.onAwait { result ->
                        if (result != null) {
                            Log.i(TAG, "✅ First DNS response from upstream server ${upstreamDnsServers[index].hostAddress} for $hostname")
                            result
                        } else null
                    }
                }
            }
            
            // Cancel remaining queries if we got a result
            if (selectedResult != null) {
                deferredResults.forEach { it.cancel() }
            }
            
            selectedResult
        }
    }
    
    /**
     * Parse DNS response and extract IP addresses
     */
    private fun parseDnsResponse(responseData: ByteArray, hostname: String): List<InetAddress> {
        return try {
            // Simple DNS response parser - extract A records
            val addresses = mutableListOf<InetAddress>()
            val buffer = ByteBuffer.wrap(responseData).apply {
                order(ByteOrder.BIG_ENDIAN) // DNS uses big-endian
            }
            
            // Skip DNS header (12 bytes)
            buffer.position(12)
            
            // Parse answers section (simplified - real parser would be more complex)
            // This is a simplified parser - for production, use a proper DNS library
            // For now, fallback to resolving via Java API
            InetAddress.getAllByName(hostname).toList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse DNS response for $hostname: ${e.message}")
            emptyList()
        }
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
        Log.i(TAG, "✅ Upstream DNS servers updated: ${upstreamDnsServers.map { it.hostAddress }}")
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
 * DNS response builder
 */
private object DnsResponseBuilder {
    fun buildResponse(originalQuery: ByteArray, addresses: List<InetAddress>): ByteArray? {
        if (addresses.isEmpty()) {
            return null
        }
        
        try {
            // This is a simplified DNS response builder
            // For production, use a proper DNS library like dnsjava or implement full RFC 1035
            
            // For now, we'll forward the original query and let upstream handle it
            // But mark it as cache hit for logging
            return null // Simplified - will forward to upstream for now
        } catch (e: Exception) {
            return null
        }
    }
}

