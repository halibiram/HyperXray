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

private const val TAG = "SystemDnsCacheServer"
private const val DNS_PORT = 53
private const val DNS_PORT_ALT = 5353 // Alternative port (no root required)
private const val BUFFER_SIZE = 512
private const val SOCKET_TIMEOUT_MS = 5000
private const val WARM_UP_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours

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
        if (isRunning.get()) {
            Log.w(TAG, "DNS cache server is already running")
            return false
        }
        
        // Ensure DnsCacheManager is initialized
        DnsCacheManager.initialize(context)
        
        // CRITICAL: Try port 53 first - VpnService may allow this without root
        Log.i(TAG, "🚀 Attempting to start DNS cache server on port 53 (root not required with VpnService)")
        
        if (tryStartOnPort(DNS_PORT)) {
            Log.i(TAG, "✅ DNS cache server started on port 53 (modem compatible, no root required)")
            return true
        }
        
        // If port 53 fails, warn user about modem compatibility
        Log.w(TAG, "⚠️ Port 53 not available - trying alternative port $DNS_PORT_ALT")
        Log.w(TAG, "⚠️ Modem DNS queries will fail unless modem is configured to use port $DNS_PORT_ALT")
        
        // Try alternative port as fallback
        if (port == DNS_PORT) {
            if (tryStartOnPort(DNS_PORT_ALT)) {
                Log.w(TAG, "⚠️ DNS cache server started on port $DNS_PORT_ALT (modem configuration required)")
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
            // Bind to 0.0.0.0 to accept connections from all interfaces (including USB tethering)
            Log.i(TAG, "🚀 Starting system DNS cache server on 0.0.0.0:$port (all interfaces)")
            
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
            try {
                // Check cache first
                val cached = DnsCacheManager.getFromCache(domain)
                if (cached != null && cached.isNotEmpty()) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "✅ DNS CACHE HIT (resolveDomain): $domain -> ${cached.map { it.hostAddress }} (${elapsed}ms)")
                    // Track domain access for warmup manager
                    warmupManager.trackDomainAccess(domain)
                    return@withContext cached
                }
                
                // Cache miss - resolve from upstream DNS
                Log.d(TAG, "⚠️ DNS CACHE MISS (resolveDomain): $domain, resolving from upstream...")
                
                // Build DNS query packet
                val queryData = DnsResponseBuilder.buildQuery(domain) ?: return@withContext emptyList()
                
                // Forward to upstream DNS servers with Happy Eyeballs
                val responseData = upstreamClient.forwardQuery(queryData, domain)
                if (responseData != null) {
                    // Parse response with TTL
                    val parseResult = DnsResponseParser.parseResponseWithTtl(responseData, domain)
                    if (parseResult.addresses.isNotEmpty()) {
                        // Save to cache
                        DnsCacheManager.saveToCache(domain, parseResult.addresses)
                        Log.i(TAG, "✅ DNS resolved and cached (resolveDomain): $domain -> ${parseResult.addresses.map { it.hostAddress ?: "unknown" }} (TTL: ${parseResult.ttl ?: "N/A"}s)")
                        // Track domain access for warmup manager
                        warmupManager.trackDomainAccess(domain)
                        return@withContext parseResult.addresses
                    } else if (parseResult.isNxDomain) {
                        // NXDOMAIN - don't cache negative results
                        Log.d(TAG, "✅ NXDOMAIN for $domain (not cached)")
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
     */
    private suspend fun serverLoop() {
        val socket = socket ?: return
        val buffer = ByteArray(BUFFER_SIZE)
        
        Log.i(TAG, "🔍 DNS server loop started, waiting for queries on port ${socket.localPort}...")
        Log.i(TAG, "📡 Listening on all interfaces (0.0.0.0) - modem can send queries")
        
        while (isRunning.get() && scope.isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                
                Log.i(TAG, "📥 DNS query received from ${packet.address.hostAddress}:${packet.port}, length: ${packet.length}")
                
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
                    // Track domain access for warmup manager
                    warmupManager.trackDomainAccess(hostname)
                    return
                }
            }
            
            // Cache miss - forward to upstream DNS server
            Log.i(TAG, "⚠️ DNS CACHE MISS: $hostname (forwarding to upstream DNS)")
            
            val upstreamResponse = withContext(Dispatchers.IO) {
                val queryData = DnsResponseBuilder.buildQuery(hostname) ?: return@withContext null
                upstreamClient.forwardQuery(queryData, hostname)
            }
            
            if (upstreamResponse != null && upstreamResponse.isNotEmpty()) {
                // Parse upstream response with TTL
                val parseResult = DnsResponseParser.parseResponseWithTtl(upstreamResponse, hostname)
                if (parseResult.addresses.isNotEmpty()) {
                    // Save to cache
                    DnsCacheManager.saveToCache(hostname, parseResult.addresses)
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
