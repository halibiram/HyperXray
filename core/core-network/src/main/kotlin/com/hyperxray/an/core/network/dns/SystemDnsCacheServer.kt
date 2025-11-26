package com.hyperxray.an.core.network.dns

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Semaphore

private const val TAG = "SystemDnsCacheServer"
private const val DNS_PORT = 53
private const val DNS_PORT_ALT = 5353 // Alternative port (no root required)
private const val BUFFER_SIZE = 512
private const val SOCKET_TIMEOUT_MS = 5000
private const val WARM_UP_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours
private const val MAX_CONCURRENT_REQUESTS = 50 // Limit concurrent DNS handling coroutines

/**
 * System-level DNS cache server that intercepts DNS queries from all apps.
 * Listens on localhost (127.0.0.1:53) and serves cached DNS responses.
 * Cache misses are forwarded to upstream DNS servers.
 * 
 * Modular Architecture:
 * - Uses DnsUpstreamClient for upstream logic (UDP, DoH, DoT, Happy Eyeballs)
 * - Uses DnsSocketPool for socket lifecycle management
 * - Uses DnsPacketUtils for DNS packet parsing/building
 * - Uses Socks5UdpClient for SOCKS5 UDP proxy
 * - Uses DnsWarmupManager for Smart Tiering and warm-up
 */
class SystemDnsCacheServer private constructor(private val context: Context) {
    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var serverJob: Job? = null
    private var warmUpJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Semaphore to limit concurrent DNS handling coroutines (prevents OOM on UDP floods)
    private val requestSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
    
    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus
    
    // VPN interface IP for binding UDP sockets to VPN interface
    @Volatile
    private var vpnInterfaceIp: String? = null
    
    // Modular components
    private val socketPool: DnsSocketPool
    private var socks5Client: Socks5UdpClient? = null
    private val upstreamClient: DnsUpstreamClient
    private val warmupManager: DnsWarmupManager
    
    // Server domains that should bypass SOCKS5 proxy for initial DNS resolution
    @Volatile
    private var serverDomains: Set<String> = emptySet()
    
    // Default upstream DNS servers
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
        InetAddress.getByName("45.90.28.0"),
        InetAddress.getByName("45.90.30.0"),
        InetAddress.getByName("77.88.8.8"),
        InetAddress.getByName("77.88.8.1"),
        InetAddress.getByName("8.26.56.26"),
        InetAddress.getByName("8.20.247.20")
    )
    
    init {
        // Initialize socket pool
        socketPool = DnsSocketPool(scope, vpnInterfaceIp)
        
        // Initialize upstream client
        upstreamClient = DnsUpstreamClient(socketPool)
        upstreamClient.initializeServers(upstreamDnsServers)
        
        // Initialize warmup manager with resolveDomain function
        warmupManager = DnsWarmupManager(scope) { domain ->
            resolveDomain(domain)
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
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "🔧 DNS SERVER START: Starting DNS cache server (requested port: $port)")
        
        if (isRunning.get()) {
            Log.w(TAG, "⚠️ DNS SERVER START: DNS cache server is already running")
            return false
        }
        
        // Ensure DnsCacheManager is initialized
        val initStartTime = System.currentTimeMillis()
        DnsCacheManager.initialize(context)
        val initDuration = System.currentTimeMillis() - initStartTime
        Log.d(TAG, "✅ DNS SERVER START: DnsCacheManager initialized (duration: ${initDuration}ms)")
        
        // Try requested port first
        val requestedPortStartTime = System.currentTimeMillis()
        if (tryStartOnPort(port)) {
            val requestedPortDuration = System.currentTimeMillis() - requestedPortStartTime
            val totalDuration = System.currentTimeMillis() - startTime
            Log.i(TAG, "✅ DNS SERVER START SUCCESS: DNS cache server started on port $port (requestedPort: ${requestedPortDuration}ms, total: ${totalDuration}ms)")
            return true
        }
        val requestedPortDuration = System.currentTimeMillis() - requestedPortStartTime
        Log.w(TAG, "⚠️ DNS SERVER START: Port $port not available (duration: ${requestedPortDuration}ms)")
        
        // If requested port was 53, try alternative port 5353 as fallback
        if (port == DNS_PORT) {
            Log.w(TAG, "⚠️ DNS SERVER START: Trying alternative port $DNS_PORT_ALT as fallback...")
            val altPortStartTime = System.currentTimeMillis()
            if (tryStartOnPort(DNS_PORT_ALT)) {
                val altPortDuration = System.currentTimeMillis() - altPortStartTime
                val totalDuration = System.currentTimeMillis() - startTime
                Log.w(TAG, "⚠️ DNS SERVER START SUCCESS: DNS cache server started on port $DNS_PORT_ALT (modem configuration required, altPort: ${altPortDuration}ms, total: ${totalDuration}ms)")
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
            // Bind to 0.0.0.0 to accept connections from all interfaces (including VPN interface)
            // Xray sends to 127.0.0.1:5353 but packets may be routed through VPN interface
            Log.i(TAG, "🚀 Starting system DNS cache server on 0.0.0.0:$port (all interfaces including VPN)")
            
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = SOCKET_TIMEOUT_MS
                bind(InetSocketAddress("0.0.0.0", port))
            }
            
            isRunning.set(true)
            _serverStatus.value = ServerStatus.Running
            
            // Warm up DNS cache with popular domains
            warmUpCache()
            
            // Start periodic warm-up job (every 6 hours)
            warmUpJob = scope.launch {
                while (isActive && isRunning.get()) {
                    delay(WARM_UP_INTERVAL_MS)
                    if (isRunning.get()) {
                        Log.i(TAG, "🔄 Starting periodic DNS cache warm-up...")
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
            
            Log.i(TAG, "✅ System DNS cache server started successfully on 0.0.0.0:$port")
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
        val port = socket?.localPort
        if (port == DNS_PORT_ALT) {
            Log.w(TAG, "⚠️ WARNING: Server running on port $DNS_PORT_ALT - modem DNS queries will fail!")
        }
        return port
    }
    
    /**
     * Xray-core patch: Actively resolve domain through SystemDnsCacheServer.
     * Checks cache first, then resolves from upstream DNS if needed.
     * Returns list of resolved IP addresses, or empty list if resolution failed.
     */
    suspend fun resolveDomain(domain: String): List<InetAddress> {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "🔍 DNS RESOLVE START: Resolving domain: $domain")
            try {
                // Check cache first
                val cacheCheckStartTime = System.currentTimeMillis()
                val cached = DnsCacheManager.getFromCache(domain)
                val cacheCheckDuration = System.currentTimeMillis() - cacheCheckStartTime
                if (cached != null && cached.isNotEmpty()) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "✅ DNS RESOLVE CACHE HIT: $domain -> ${cached.map { it.hostAddress }} (cache check: ${cacheCheckDuration}ms, total: ${elapsed}ms)")
                    // Track domain access for warmup manager
                    warmupManager.trackDomainAccess(domain)
                    return@withContext cached
                }
                Log.d(TAG, "⚠️ DNS RESOLVE CACHE MISS: $domain (cache check: ${cacheCheckDuration}ms), resolving from upstream...")
                
                // Build DNS query packet
                val queryBuildStartTime = System.currentTimeMillis()
                val queryData = DnsResponseBuilder.buildQuery(domain) ?: run {
                    val queryBuildDuration = System.currentTimeMillis() - queryBuildStartTime
                    Log.e(TAG, "❌ DNS RESOLVE ERROR: Failed to build query packet for $domain (duration: ${queryBuildDuration}ms)")
                    return@withContext emptyList()
                }
                val queryBuildDuration = System.currentTimeMillis() - queryBuildStartTime
                Log.d(TAG, "✅ DNS RESOLVE: Query packet built (size: ${queryData.size} bytes, duration: ${queryBuildDuration}ms)")
                
                // Forward to upstream DNS servers with Happy Eyeballs
                val upstreamStartTime = System.currentTimeMillis()
                val responseData = upstreamClient.forwardQuery(queryData, domain)
                val upstreamDuration = System.currentTimeMillis() - upstreamStartTime
                if (responseData != null) {
                    Log.d(TAG, "✅ DNS RESOLVE: Upstream query completed (response size: ${responseData.size} bytes, duration: ${upstreamDuration}ms)")
                    // Parse response with TTL
                    val parseStartTime = System.currentTimeMillis()
                    val parseResult = DnsResponseParser.parseResponseWithTtl(responseData, domain)
                    val parseDuration = System.currentTimeMillis() - parseStartTime
                    if (parseResult.addresses.isNotEmpty()) {
                        // Save to cache with actual TTL from DNS response
                        val cacheSaveStartTime = System.currentTimeMillis()
                        val ttl = parseResult.ttl ?: null // Use null to fallback to optimized TTL
                        DnsCacheManager.saveToCache(domain, parseResult.addresses, ttl)
                        val cacheSaveDuration = System.currentTimeMillis() - cacheSaveStartTime
                        val totalDuration = System.currentTimeMillis() - startTime
                        Log.i(TAG, "✅ DNS RESOLVE SUCCESS: $domain -> ${parseResult.addresses.map { it.hostAddress ?: "unknown" }} (TTL: ${parseResult.ttl ?: "N/A"}s, parse: ${parseDuration}ms, cache save: ${cacheSaveDuration}ms, total: ${totalDuration}ms)")
                        // Track domain access for warmup manager
                        warmupManager.trackDomainAccess(domain)
                        return@withContext parseResult.addresses
                    } else if (parseResult.isNxDomain) {
                        val totalDuration = System.currentTimeMillis() - startTime
                        // NXDOMAIN - don't cache negative results
                        Log.d(TAG, "✅ DNS RESOLVE NXDOMAIN: $domain (not cached, parse: ${parseDuration}ms, total: ${totalDuration}ms)")
                    } else {
                        val totalDuration = System.currentTimeMillis() - startTime
                        Log.w(TAG, "⚠️ DNS RESOLVE: Empty addresses in response for $domain (parse: ${parseDuration}ms, total: ${totalDuration}ms)")
                    }
                } else {
                    val totalDuration = System.currentTimeMillis() - startTime
                    Log.e(TAG, "❌ DNS RESOLVE FAILED: Upstream query returned null for $domain (upstream: ${upstreamDuration}ms, total: ${totalDuration}ms)")
                }
                
                emptyList()
            } catch (e: Exception) {
                val totalDuration = System.currentTimeMillis() - startTime
                Log.e(TAG, "❌ DNS RESOLVE ERROR: Error resolving domain: $domain (duration: ${totalDuration}ms)", e)
                emptyList()
            }
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
            warmUpJob?.cancel()
            warmUpJob = null
            
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
     * 
     * CRITICAL FIX: Buffer Race Condition Prevention
     * - Allocates a NEW ByteArray buffer for every iteration
     * - Copies packet data immediately before launching coroutine
     * - Prevents buffer overwrite when multiple packets arrive concurrently
     */
    private suspend fun serverLoop() {
        val socket = socket ?: return
        
        Log.i(TAG, "🔍 DNS server loop started, waiting for queries on port ${socket.localPort}...")
        Log.i(TAG, "📡 Listening on 0.0.0.0:${socket.localPort} - accepting queries from all interfaces (including VPN)")
        
        while (isRunning.get() && scope.isActive) {
            try {
                // CRITICAL FIX: Allocate NEW buffer for each iteration to prevent race condition
                val buffer = ByteArray(BUFFER_SIZE)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                
                Log.i(TAG, "📥 DNS query received from ${packet.address.hostAddress}:${packet.port}, length: ${packet.length}")
                
                // CRITICAL FIX: Copy packet data immediately before launching coroutine
                // This prevents the buffer from being overwritten by the next socket.receive() call
                val packetData = ByteArray(packet.length)
                System.arraycopy(packet.data, packet.offset, packetData, 0, packet.length)
                val clientAddress = packet.address
                val clientPort = packet.port
                
                // Handle query in separate coroutine with semaphore limiting
                scope.launch {
                    // Acquire semaphore to limit concurrent requests (prevents OOM on UDP floods)
                    if (!requestSemaphore.tryAcquire()) {
                        Log.w(TAG, "⚠️ Max concurrent requests reached ($MAX_CONCURRENT_REQUESTS), dropping query from ${clientAddress.hostAddress}:$clientPort")
                        return@launch
                    }
                    
                    try {
                        // Create new DatagramPacket with copied data
                        val copiedPacket = DatagramPacket(packetData, packetData.size, clientAddress, clientPort)
                        handleDnsQuery(copiedPacket, socket)
                    } finally {
                        // Always release semaphore
                        requestSemaphore.release()
                    }
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
                    // Track domain access for warmup manager
                    warmupManager.trackDomainAccess(hostname)
                    return
                }
            }
            
            // Cache miss - forward to upstream DNS server
            // Check if this is a server domain that should bypass SOCKS5 proxy
            val isServerDomain = serverDomains.contains(hostname.lowercase())
            if (isServerDomain) {
                Log.i(TAG, "⚠️ DNS CACHE MISS (SERVER DOMAIN - BYPASS PROXY): $hostname (forwarding to upstream DNS without SOCKS5)")
            } else {
                Log.i(TAG, "⚠️ DNS CACHE MISS: $hostname (forwarding to upstream DNS)")
            }
            
            val upstreamResponse = withContext(Dispatchers.IO) {
                val queryData = DnsResponseBuilder.buildQuery(hostname) ?: return@withContext null
                upstreamClient.forwardQuery(queryData, hostname, bypassProxy = isServerDomain)
            }
            
            if (upstreamResponse != null && upstreamResponse.isNotEmpty()) {
                // Parse upstream response with TTL
                val parseResult = DnsResponseParser.parseResponseWithTtl(upstreamResponse, hostname)
                if (parseResult.addresses.isNotEmpty()) {
                    // Save to cache with actual TTL from DNS response
                    val ttl = parseResult.ttl ?: null // Use null to fallback to optimized TTL
                    DnsCacheManager.saveToCache(hostname, parseResult.addresses, ttl)
                    Log.i(TAG, "✅ DNS resolved from upstream and cached: $hostname -> ${parseResult.addresses.map { it.hostAddress ?: "unknown" }} (TTL: ${parseResult.ttl ?: "N/A"}s)")
                    
                    // Send response to client
                    val responsePacket = DatagramPacket(
                        upstreamResponse,
                        upstreamResponse.size,
                        requestPacket.socketAddress
                    )
                    socket.send(responsePacket)
                    Log.d(TAG, "✅ DNS response sent from upstream: $hostname")
                    // Track domain access for warmup manager
                    warmupManager.trackDomainAccess(hostname)
                } else if (parseResult.isNxDomain) {
                    // NXDOMAIN - don't cache negative results
                    Log.d(TAG, "✅ NXDOMAIN for $hostname (not cached)")
                    
                    // Send NXDOMAIN response to client
                    val responsePacket = DatagramPacket(
                        upstreamResponse,
                        upstreamResponse.size,
                        requestPacket.socketAddress
                    )
                    socket.send(responsePacket)
                    Log.d(TAG, "✅ NXDOMAIN response sent: $hostname")
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
     * Warm up DNS cache with popular domains
     * Uses DnsWarmupManager with Smart Tiering and Force-Max strategy
     */
    fun warmUpCache() {
        if (!isRunning.get()) {
            Log.d(TAG, "DNS cache server not running, skipping warm-up")
            return
        }
        
        scope.launch {
            warmupManager.warmUpCache()
        }
    }
    
    /**
     * Get warm-up statistics
     */
    fun getWarmUpStats(): WarmUpStats {
        return warmupManager.getWarmUpStats()
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
        upstreamClient.setUpstreamDnsServers(upstreamDnsServers)
        Log.i(TAG, "✅ Upstream DNS servers updated: ${upstreamDnsServers.map { it.hostAddress }}")
    }
    
    /**
     * Set VPN interface IP for binding UDP sockets to VPN interface
     * This ensures DNS queries are routed through VPN
     */
    fun setVpnInterfaceIp(ip: String?) {
        vpnInterfaceIp = ip
        socketPool.setVpnInterfaceIp(ip)
        if (ip != null) {
            Log.i(TAG, "✅ VPN interface IP set: $ip (DNS queries will be routed through VPN)")
        } else {
            Log.i(TAG, "VPN interface IP cleared (DNS queries will use default interface)")
        }
    }
    
    /**
     * Set SOCKS5 proxy for UDP DNS queries
     * This routes DNS queries through SOCKS5 proxy instead of direct UDP
     */
    fun setSocks5Proxy(address: String, port: Int) {
        scope.launch {
            try {
                val proxy = Socks5UdpClient(address, port)
                if (proxy.establish()) {
                    socks5Client?.close()
                    socks5Client = proxy
                    // Update upstream client with new SOCKS5 client
                    upstreamClient.setSocks5Client(proxy)
                    Log.i(TAG, "✅ SOCKS5 UDP proxy set: $address:$port")
                } else {
                    Log.e(TAG, "Failed to establish SOCKS5 UDP proxy: $address:$port")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting SOCKS5 proxy: ${e.message}", e)
            }
        }
    }
    
    /**
     * Clear SOCKS5 proxy
     */
    fun clearSocks5Proxy() {
        socks5Client?.close()
        socks5Client = null
        upstreamClient.setSocks5Client(null)
        Log.d(TAG, "SOCKS5 UDP proxy cleared")
    }
    
    /**
     * Set server domains that should bypass SOCKS5 proxy for initial DNS resolution
     * These domains are resolved directly (without proxy) to avoid circular dependency
     * when VPN is starting and needs to resolve the server domain
     */
    fun setServerDomains(domains: Set<String>) {
        serverDomains = domains.map { it.lowercase() }.toSet()
        Log.i(TAG, "✅ Server domains set for proxy bypass: ${serverDomains.joinToString(", ")}")
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
        clearSocks5Proxy()
        socketPool.clear()
        scope.cancel()
    }
}
