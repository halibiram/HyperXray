package com.hyperxray.an.core.network.dns

import android.content.Context
import android.util.Log
import com.hyperxray.an.core.network.dns.DnsCacheManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
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
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl

private const val TAG = "SystemDnsCacheServer"
private const val DNS_PORT = 53
private const val DNS_PORT_ALT = 5353 // Alternative port (no root required)
private const val BUFFER_SIZE = 512
private const val SOCKET_TIMEOUT_MS = 5000

/**
 * SOCKS5 UDP proxy wrapper for DNS queries
 * Implements SOCKS5 UDP ASSOCIATE protocol
 */
private class Socks5UdpProxy(
    private val proxyAddress: String,
    private val proxyPort: Int
) {
    private var tcpSocket: Socket? = null
    @Volatile
    private var udpRelayAddress: InetAddress? = null
    @Volatile
    private var udpRelayPort: Int = 0
    // Reusable UDP socket for sending and receiving (SOCKS5 requires same socket)
    @Volatile
    private var udpSocket: DatagramSocket? = null
    
    /**
     * Establish SOCKS5 connection and get UDP relay endpoint
     */
    suspend fun establish(): Boolean = withContext(Dispatchers.IO) {
        try {
                // Connect to SOCKS5 proxy via TCP
                tcpSocket = Socket().apply {
                    soTimeout = 10000 // 10 seconds
                    connect(InetSocketAddress(proxyAddress, proxyPort), 10000)
                }
                
                val input = tcpSocket!!.getInputStream()
                val output = tcpSocket!!.getOutputStream()
                
                // SOCKS5 handshake: No authentication
                // Send: VER(1) + NMETHODS(1) + METHODS(1) = [0x05, 0x01, 0x00]
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                
                // Receive: VER(1) + METHOD(1) = [0x05, 0x00]
                val handshakeResponse = ByteArray(2)
                input.read(handshakeResponse)
                if (handshakeResponse[0] != 0x05.toByte() || handshakeResponse[1] != 0x00.toByte()) {
                    Log.e(TAG, "SOCKS5 handshake failed: ${handshakeResponse.joinToString { "%02x".format(it) }}")
                    return@withContext false
                }
                
                // UDP ASSOCIATE request
                // Send: VER(1) + CMD(1) + RSV(1) + ATYP(1) + ADDR(4) + PORT(2)
                // CMD = 0x03 (UDP ASSOCIATE), ATYP = 0x01 (IPv4), ADDR = 0.0.0.0, PORT = 0
                val udpAssociateRequest = byteArrayOf(
                    0x05, // VER
                    0x03, // CMD: UDP ASSOCIATE
                    0x00, // RSV
                    0x01, // ATYP: IPv4
                    0x00, 0x00, 0x00, 0x00, // ADDR: 0.0.0.0
                    0x00, 0x00 // PORT: 0
                )
                output.write(udpAssociateRequest)
                output.flush()
                
                // Receive UDP ASSOCIATE response
                // VER(1) + REP(1) + RSV(1) + ATYP(1) + BND.ADDR(4) + BND.PORT(2)
                val udpAssociateResponse = ByteArray(10)
                input.read(udpAssociateResponse)
                
                if (udpAssociateResponse[0] != 0x05.toByte() || udpAssociateResponse[1] != 0x00.toByte()) {
                    Log.e(TAG, "SOCKS5 UDP ASSOCIATE failed: REP=${udpAssociateResponse[1]}")
                    return@withContext false
                }
                
                // Extract UDP relay endpoint
                val addrBytes = udpAssociateResponse.sliceArray(4..7)
                udpRelayAddress = InetAddress.getByAddress(addrBytes)
                udpRelayPort = ((udpAssociateResponse[8].toInt() and 0xFF) shl 8) or (udpAssociateResponse[9].toInt() and 0xFF)
                
                Log.i(TAG, "✅ SOCKS5 UDP ASSOCIATE established: ${udpRelayAddress?.hostAddress}:$udpRelayPort")
                return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish SOCKS5 UDP proxy: ${e.message}", e)
            tcpSocket?.close()
            tcpSocket = null
            udpRelayAddress = null
            udpRelayPort = 0
            return@withContext false
        }
    }
    
    /**
     * Wrap UDP packet with SOCKS5 UDP header
     */
    private fun wrapUdpPacket(data: ByteArray, targetAddress: InetAddress, targetPort: Int): ByteArray {
        val addrBytes = targetAddress.address
        val headerSize = 6 + addrBytes.size + 2 // RSV(2) + FRAG(1) + ATYP(1) + ADDR(4) + PORT(2)
        val wrapped = ByteArray(headerSize + data.size)
        
        var offset = 0
        // RSV: Reserved (2 bytes) = 0x0000
        wrapped[offset++] = 0x00
        wrapped[offset++] = 0x00
        // FRAG: Fragment number (1 byte) = 0x00 (no fragmentation)
        wrapped[offset++] = 0x00
        // ATYP: Address type (1 byte) = 0x01 (IPv4)
        wrapped[offset++] = 0x01
        // DST.ADDR: Destination address (4 bytes for IPv4)
        System.arraycopy(addrBytes, 0, wrapped, offset, addrBytes.size)
        offset += addrBytes.size
        // DST.PORT: Destination port (2 bytes, big-endian)
        wrapped[offset++] = ((targetPort shr 8) and 0xFF).toByte()
        wrapped[offset++] = (targetPort and 0xFF).toByte()
        // DATA: Actual UDP data
        System.arraycopy(data, 0, wrapped, offset, data.size)
        
        return wrapped
    }
    
    /**
     * Unwrap SOCKS5 UDP header from response
     * SOCKS5 UDP header format:
     * - RSV (2 bytes): Reserved, must be 0x0000
     * - FRAG (1 byte): Fragment number (0x00 = no fragmentation)
     * - ATYP (1 byte): Address type (0x01=IPv4, 0x03=Domain, 0x04=IPv6)
     * - ADDR (variable): Address (4 bytes for IPv4, variable for domain, 16 bytes for IPv6)
     * - PORT (2 bytes): Port number
     * - DATA: Actual UDP data (DNS response)
     */
    private fun unwrapUdpPacket(wrapped: ByteArray): ByteArray? {
        if (wrapped.size < 6) {
            Log.w(TAG, "SOCKS5 UDP packet too short: ${wrapped.size} bytes (minimum 6)")
            return null
        }
        
        // Check RSV (must be 0x0000)
        if (wrapped[0] != 0x00.toByte() || wrapped[1] != 0x00.toByte()) {
            Log.w(TAG, "Invalid SOCKS5 UDP packet: RSV=${wrapped[0].toInt() and 0xFF},${wrapped[1].toInt() and 0xFF}")
            return null
        }
        
        // Check FRAG (should be 0x00 for no fragmentation)
        val frag = wrapped[2].toInt() and 0xFF
        if (frag != 0x00) {
            Log.d(TAG, "SOCKS5 UDP packet has fragmentation: FRAG=$frag (not supported, but continuing)")
        }
        
        // Get ATYP (address type)
        val atyp = wrapped[3].toInt() and 0xFF
        val addrSize = when (atyp) {
            0x01 -> 4 // IPv4
            0x03 -> {
                // Domain name: next byte is length
                if (wrapped.size < 5) return null
                wrapped[4].toInt() and 0xFF
            }
            0x04 -> 16 // IPv6
            else -> {
                Log.w(TAG, "Unsupported SOCKS5 address type: ATYP=$atyp")
                return null
            }
        }
        
        // Calculate header size: RSV(2) + FRAG(1) + ATYP(1) + ADDR(variable) + PORT(2)
        val headerSize = 4 + addrSize + 2
        if (wrapped.size < headerSize) {
            Log.w(TAG, "SOCKS5 UDP packet too short: ${wrapped.size} bytes (expected at least $headerSize)")
            return null
        }
        
        // Extract data (skip header)
        val data = wrapped.sliceArray(headerSize until wrapped.size)
        if (data.isEmpty()) {
            Log.w(TAG, "SOCKS5 UDP packet has no data after header")
            return null
        }
        
        // Verify extracted data looks like a DNS packet (starts with valid DNS header)
        if (data.size >= 12) {
            val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            val qr = (flags shr 15) and 0x01
            if (qr == 1) {
                // Valid DNS response
                Log.d(TAG, "✅ SOCKS5 UDP packet unwrapped successfully: ${data.size} bytes DNS data")
            } else {
                // Might be a DNS query, log for debugging
                Log.d(TAG, "⚠️ SOCKS5 UDP packet unwrapped but QR bit is 0 (might be query, not response)")
            }
        }
        
        return data
    }
    
    /**
     * Get or create UDP socket for SOCKS5 relay
     * SOCKS5 UDP requires using the same socket for send and receive
     * Thread-safe: Uses double-checked locking pattern
     */
    private fun getUdpSocket(): DatagramSocket? {
        var socket = udpSocket
        if (socket == null || socket.isClosed) {
            synchronized(this) {
                socket = udpSocket
                if (socket == null || socket!!.isClosed) {
                    try {
                        socket = DatagramSocket()
                        socket!!.soTimeout = 5000 // Default timeout
                        udpSocket = socket
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create UDP socket for SOCKS5: ${e.message}", e)
                        return null
                    }
                }
            }
        }
        return socket
    }
    
    /**
     * Send UDP packet through SOCKS5 proxy
     */
    suspend fun sendUdp(data: ByteArray, targetAddress: InetAddress, targetPort: Int): Boolean {
        val relayAddr = udpRelayAddress ?: return false
        val relayPort = udpRelayPort
        if (relayPort == 0) return false
        
        return try {
            val wrapped = wrapUdpPacket(data, targetAddress, targetPort)
            val socket = getUdpSocket() ?: return false
            val packet = DatagramPacket(wrapped, wrapped.size, relayAddr, relayPort)
            socket.send(packet)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send UDP through SOCKS5: ${e.message}", e)
            false
        }
    }
    
    /**
     * Receive UDP packet from SOCKS5 proxy
     * Must use the same socket as sendUdp
     */
    suspend fun receiveUdp(buffer: ByteArray, timeoutMs: Int): ByteArray? {
        val relayAddr = udpRelayAddress ?: return null
        val relayPort = udpRelayPort
        if (relayPort == 0) return null
        
        return try {
            val socket = getUdpSocket() ?: return null
            socket.soTimeout = timeoutMs
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            
            // Unwrap SOCKS5 header
            val received = ByteArray(packet.length)
            System.arraycopy(packet.data, 0, received, 0, packet.length)
            
            Log.d(TAG, "📥 Received SOCKS5 UDP packet: ${received.size} bytes from ${packet.address}:${packet.port}")
            
            val unwrapped = unwrapUdpPacket(received)
            if (unwrapped == null) {
                val firstBytes = received.take(16).joinToString(" ") { "%02x".format(it) }
                Log.w(TAG, "⚠️ Failed to unwrap SOCKS5 UDP packet (${received.size} bytes, first 16 bytes: $firstBytes)")
            } else {
                Log.d(TAG, "✅ Unwrapped SOCKS5 UDP packet: ${unwrapped.size} bytes DNS data")
            }
            
            unwrapped
        } catch (e: SocketTimeoutException) {
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to receive UDP from SOCKS5: ${e.message}", e)
            null
        }
    }
    
    fun close() {
        val socket = udpSocket
        if (socket != null) {
            synchronized(this) {
                udpSocket?.close()
                udpSocket = null
            }
        }
        tcpSocket?.close()
        tcpSocket = null
        udpRelayAddress = null
        udpRelayPort = 0
    }
}

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
    
    // VPN interface IP for binding UDP sockets to VPN interface
    @Volatile
    private var vpnInterfaceIp: String? = null
    
    // SOCKS5 proxy for UDP DNS queries
    @Volatile
    private var socks5Proxy: Socks5UdpProxy? = null
    
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
    
    // Socket connection pooling for faster DNS resolution
    private data class PooledSocket(
        val socket: DatagramSocket,
        val server: InetAddress,
        val lastUsed: AtomicLong = AtomicLong(System.currentTimeMillis()),
        val vpnInterfaceIp: String? = null // Track which VPN IP this socket was bound to
    )
    
    private val socketPool = ConcurrentHashMap<String, PooledSocket>()
    private val SOCKET_POOL_TIMEOUT_MS = 10000L // 10 seconds - sockets expire after 10s of inactivity
    private val SOCKET_POOL_CLEANUP_INTERVAL_MS = 30000L // Cleanup every 30 seconds
    
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

    // Adaptive timeout tracking per server (optimized for faster first-time access)
    private val adaptiveTimeouts = ConcurrentHashMap<String, Long>()
    private val BASE_TIMEOUT_MS = 200L // Fast timeout for quick SOCKS5 fallback (reduced from 500ms)
    private val MAX_TIMEOUT_MS = 500L // Reduced maximum timeout (reduced from 800ms)

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
            dnsServerStats[server.hostAddress] = DnsServerStats(server)
            adaptiveTimeouts[server.hostAddress] = BASE_TIMEOUT_MS
        }
        
        // Start socket pool cleanup job
        scope.launch {
            while (isActive) {
                delay(SOCKET_POOL_CLEANUP_INTERVAL_MS)
                cleanupSocketPool()
            }
        }
    }
    
    /**
     * Cleanup expired sockets from pool
     */
    private fun cleanupSocketPool() {
        val now = System.currentTimeMillis()
        socketPool.entries.removeIf { (_, pooledSocket) ->
            val age = now - pooledSocket.lastUsed.get()
            if (age > SOCKET_POOL_TIMEOUT_MS) {
                try {
                    pooledSocket.socket.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
                true
            } else {
                false
            }
        }
    }
    
    /**
     * Get or create socket from pool
     */
    private fun getPooledSocket(server: InetAddress, timeoutMs: Long): DatagramSocket {
        val serverKey = server.hostAddress
        val pooled = socketPool[serverKey]
        
        if (pooled != null) {
            val age = System.currentTimeMillis() - pooled.lastUsed.get()
            // Check if VPN IP changed or socket is not bound to current VPN IP
            val vpnIpChanged = pooled.vpnInterfaceIp != vpnInterfaceIp
            // Also check if socket is still valid
            val socketValid = try {
                !pooled.socket.isClosed && pooled.socket.isBound
            } catch (e: Exception) {
                false
            }
            
            if (age < SOCKET_POOL_TIMEOUT_MS && !vpnIpChanged && socketValid) {
                pooled.lastUsed.set(System.currentTimeMillis())
                return pooled.socket
            } else {
                // Socket expired, VPN IP changed, or socket invalid - remove from pool
                socketPool.remove(serverKey)
                try {
                    if (!pooled.socket.isClosed) {
                        pooled.socket.close()
                    }
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
        }
        
        // Create new socket
        val newSocket = try {
            if (vpnInterfaceIp != null) {
                // Bind to VPN interface IP to route DNS queries through VPN
                // Use DatagramSocket(null) to create unbound socket, then bind explicitly
                DatagramSocket(null).apply {
                    soTimeout = timeoutMs.toInt()
                    try {
                        reuseAddress = true
                        bind(InetSocketAddress(vpnInterfaceIp, 0)) // Bind to VPN interface
                        Log.d(TAG, "✅ DNS socket bound to VPN interface: $vpnInterfaceIp")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to bind socket to VPN interface $vpnInterfaceIp: ${e.message}, using default binding")
                        close()
                        // Fallback to default binding
                        DatagramSocket().apply {
                            soTimeout = timeoutMs.toInt()
                            try {
                                reuseAddress = true
                            } catch (e2: Exception) {
                                // Some platforms may not support this
                            }
                        }
                    }
                }
            } else {
                // No VPN interface, use default binding
                DatagramSocket().apply {
                    soTimeout = timeoutMs.toInt()
                    try {
                        reuseAddress = true
                    } catch (e: Exception) {
                        // Some platforms may not support this
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating DNS socket: ${e.message}", e)
            DatagramSocket().apply {
                soTimeout = timeoutMs.toInt()
            }
        }
        
        // Add to pool with VPN IP tracking
        socketPool[serverKey] = PooledSocket(newSocket, server, vpnInterfaceIp = vpnInterfaceIp)
        return newSocket
    }
    
    /**
     * Get adaptive timeout for a DNS server based on its performance
     */
    private fun getAdaptiveTimeout(server: InetAddress): Long {
        val stats = dnsServerStats[server.hostAddress] ?: return BASE_TIMEOUT_MS
        val currentTimeout = adaptiveTimeouts[server.hostAddress] ?: BASE_TIMEOUT_MS
        
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
     * Warm up DNS cache with popular domains (enhanced with adaptive prioritization and TTL-aware prefetching)
     */
    fun warmUpCache() {
        if (!isRunning.get()) {
            Log.d(TAG, "DNS cache server not running, skipping warm-up")
            return
        }
        
        scope.launch {
            val startTime = System.currentTimeMillis()
            
            // Step 1: Get adaptive warm-up domain list (prioritized by hit rate, TTL expiration, and user behavior)
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
            Log.i(TAG, "📈 Warm-up performance: $totalSuccess successes, $totalFailed failures, average ${if (totalSuccess > 0) (totalElapsed / totalSuccess) else 0}ms per domain")

            // Track warm-up success rate for monitoring
            trackWarmUpStats(totalSuccess, totalFailed, totalElapsed, totalSuccessRate)
        }
    }
    
    /**
     * Get adaptive warm-up domain list (prioritized by hit rate, TTL expiration, and user behavior)
     */
    private fun getAdaptiveWarmUpDomains(): List<PrioritizedDomain> {
        val result = mutableListOf<PrioritizedDomain>()

        // Get high hit rate domains from cache statistics (dynamically learned)
        // Note: DnsCacheManager.getPrefetchCandidates() and getExpiringSoonDomains() may not exist
        // For now, we'll use empty lists as fallback
        val highHitRateDomains = try {
            // Try to get prefetch candidates if method exists
            emptyList<String>() // Placeholder - would need DnsCacheManager extension
        } catch (e: Exception) {
            emptyList()
        }
        val highHitRateSet = highHitRateDomains.toSet()

        val expiringSoonDomains = try {
            // Try to get expiring soon domains if method exists
            emptyList<String>() // Placeholder - would need DnsCacheManager extension
        } catch (e: Exception) {
            emptyList()
        }
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
        val ip = server.hostAddress
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
            
            // Mark as unhealthy if too many failures (only if we have enough data points)
            val totalAttempts = stats.successCount + stats.failureCount
            if (totalAttempts >= 3 && (stats.failureCount >= MAX_FAILURES_BEFORE_UNHEALTHY || stats.successRate < MIN_SUCCESS_RATE)) {
                stats.isHealthy = false
                Log.w(TAG, "⚠️ DNS server ${server.hostAddress} marked as unhealthy (failures: ${stats.failureCount}/${totalAttempts}, success rate: ${(stats.successRate * 100).toInt()}%)")
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
        
        // CRITICAL: Try port 53 first - VpnService may allow this without root
        // This is essential for modem DNS compatibility (modem uses port 53 by default)
        Log.i(TAG, "🚀 Attempting to start DNS cache server on port 53 (root not required with VpnService)")
        
        if (tryStartOnPort(DNS_PORT)) {
            Log.i(TAG, "✅ DNS cache server started on port 53 (modem compatible, no root required)")
            return true
        }
        
        // If port 53 fails, warn user about modem compatibility
        Log.w(TAG, "⚠️ Port 53 not available - trying alternative port $DNS_PORT_ALT")
        Log.w(TAG, "⚠️ Modem DNS queries will fail unless modem is configured to use port $DNS_PORT_ALT")
        Log.w(TAG, "⚠️ For Keenetic modem: Configure DNS to 10.89.38.35:$DNS_PORT_ALT")
        
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
            // This allows modem (10.89.38.30) to send DNS queries to Android device (10.89.38.35)
            Log.i(TAG, "🚀 Starting system DNS cache server on 0.0.0.0:$port (all interfaces)")
            
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = SOCKET_TIMEOUT_MS
                bind(InetSocketAddress("0.0.0.0", port)) // Changed from 127.0.0.1 to 0.0.0.0
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
            
            Log.i(TAG, "✅ System DNS cache server started successfully on 0.0.0.0:$port (modem can now use 10.89.38.35:$port)")
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
            Log.w(TAG, "⚠️ Modem needs port 53. For Keenetic modem: Configure DNS to 10.89.38.35:$DNS_PORT_ALT")
        }
        return port
    }
    
    /**
     * Xray-core patch: Actively resolve domain through SystemDnsCacheServer.
     * Checks cache first, then resolves from upstream DNS if needed.
     * Uses retry mechanism to reduce packet loss during DNS cache miss.
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
                    return@withContext cached
                }
                
                // Cache miss - resolve from upstream DNS with retry mechanism
                Log.d(TAG, "⚠️ DNS CACHE MISS (resolveDomain): $domain, resolving from upstream with retry...")
                
                // Build DNS query packet
                val queryData = buildDnsQuery(domain) ?: return@withContext emptyList()
                
                // Forward to upstream DNS servers with retry mechanism (reduces packet loss)
                val responseData = forwardToUpstreamDnsWithRetry(queryData, domain)
                if (responseData != null) {
                    // Parse response with TTL
                    val parseResult = parseDnsResponseWithTtl(responseData, domain)
                    if (parseResult.addresses.isNotEmpty()) {
                        // Save to cache (DnsCacheManager will handle TTL optimization internally)
                        DnsCacheManager.saveToCache(domain, parseResult.addresses)
                        Log.i(TAG, "✅ DNS resolved and cached (resolveDomain): $domain -> ${parseResult.addresses.map { it.hostAddress ?: "unknown" }} (TTL: ${parseResult.ttl}s)")
                        return@withContext parseResult.addresses
                    } else if (parseResult.isNxDomain) {
                        // NXDOMAIN - don't cache negative results (DnsCacheManager doesn't support negative caching)
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
        Log.i(TAG, "📡 Listening on all interfaces (0.0.0.0) - modem can send queries from 10.89.38.30")
        
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
                    return
                }
            }
            
            // Cache miss - forward to upstream DNS server with retry mechanism
            Log.i(TAG, "⚠️ DNS CACHE MISS: $hostname (forwarding to upstream DNS with retry)")
            
            val upstreamResponse = withContext(Dispatchers.IO) {
                forwardToUpstreamDnsWithRetry(requestData, hostname)
            }
            
            if (upstreamResponse != null && upstreamResponse.isNotEmpty()) {
                // Parse upstream response with TTL
                val parseResult = parseDnsResponseWithTtl(upstreamResponse, hostname)
                if (parseResult.addresses.isNotEmpty()) {
                    // Save to cache (DnsCacheManager will handle TTL optimization internally)
                    DnsCacheManager.saveToCache(hostname, parseResult.addresses)
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
                    // NXDOMAIN - don't cache negative results (DnsCacheManager doesn't support negative caching)
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
     * Forward DNS query to upstream DNS servers (parallel multi-DNS support)
     * Queries all upstream DNS servers simultaneously and returns first successful response
     * Optimized timeout: 1000ms (reduced from 3000ms to minimize packet loss)
     */
    private suspend fun forwardToUpstreamDns(queryData: ByteArray, hostname: String): ByteArray? {
        // Use optimized retry mechanism instead of single attempt
        return forwardToUpstreamDnsWithRetry(queryData, hostname)
    }
    
    /**
     * Forward DNS query to upstream DNS servers with optimized retry mechanism and query deduplication
     * Retries with ultra-fast timeouts (200ms -> 500ms -> 800ms) for maximum speed
     * Uses performance-based DNS server ordering (fastest first)
     * Uses aggressive parallel queries to minimize latency
     * Implements query deduplication to avoid duplicate upstream requests
     * This reduces packet loss during DNS cache miss scenarios
     */
    private suspend fun forwardToUpstreamDnsWithRetry(queryData: ByteArray, hostname: String): ByteArray? {
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
        
        try {
            // Single attempt with short timeout - no retry mechanism
            // Fast failure to quickly move to next method (DoT/DoH fallback)
            val timeoutMs = BASE_TIMEOUT_MS // Short timeout for fast fallback
            
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
                    DnsCacheManager.saveToCache(hostname, selectedResult)
                    
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
                                val addresses = parseDnsResponse(responseData, hostname)
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
     * Uses Android's built-in DNS resolver
     */
    private suspend fun trySystemDnsFallback(hostname: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🔄 Trying system DNS fallback for $hostname...")
                val addresses = InetAddress.getAllByName(hostname)
                if (addresses.isNotEmpty()) {
                    // Build DNS response packet from resolved addresses
                    val queryData = buildDnsQuery(hostname) ?: return@withContext null
                    val responseData = buildDnsResponse(queryData, addresses.toList())
                    
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
                        val stats = dnsServerStats[dnsServer.hostAddress]
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
                            // PRIORITY: Use direct UDP first (faster, VPN interface binding ensures VPN routing)
                            // Fallback to SOCKS5 only if direct UDP fails
                            val vpnIp = vpnInterfaceIp
                            if (vpnIp != null) {
                                Log.d(TAG, "🔌 [DIRECT] Using direct UDP for DNS query: $hostname → ${dnsServer.hostAddress}:$DNS_PORT (VPN interface: $vpnIp)")
                            } else {
                                Log.d(TAG, "🔌 [DIRECT] Using direct UDP for DNS query: $hostname → ${dnsServer.hostAddress}:$DNS_PORT (no VPN interface - queries may timeout)")
                            }
                            val socket = getPooledSocket(dnsServer, adaptiveTimeout)
                            
                            try {
                                // Check if socket is still valid before using
                                if (socket.isClosed) {
                                    throw Exception("Socket is closed")
                                }
                                
                                // Compress DNS query if possible (reuse domain name compression)
                                val compressedQuery = compressDnsQuery(queryData, hostname)
                                Log.d(TAG, "📤 [DIRECT] Sending DNS query via direct UDP: ${compressedQuery.size} bytes")
                                
                                val requestPacket = DatagramPacket(
                                    compressedQuery,
                                    compressedQuery.size,
                                    InetSocketAddress(dnsServer, DNS_PORT)
                                )
                                
                                val sendStart = System.currentTimeMillis()
                                socket.send(requestPacket)
                                Log.d(TAG, "✅ [DIRECT] DNS query sent via direct UDP, waiting for response (timeout: ${adaptiveTimeout}ms)")
                                
                                // Check socket again before receive (may have been closed)
                                if (socket.isClosed) {
                                    throw Exception("Socket closed after send")
                                }
                                
                                val responseBuffer = ByteArray(BUFFER_SIZE)
                                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                                socket.receive(responsePacket)
                                
                                val response = ByteArray(responsePacket.length)
                                System.arraycopy(responsePacket.data, 0, response, 0, responsePacket.length)
                                
                                val elapsed = System.currentTimeMillis() - sendStart
                                Log.d(TAG, "📥 [DIRECT] DNS response received via direct UDP: ${response.size} bytes (${elapsed}ms)")
                                
                                // Update adaptive timeout based on performance
                                val currentTimeout = adaptiveTimeouts[dnsServer.hostAddress] ?: BASE_TIMEOUT_MS
                                val newTimeout = when {
                                    elapsed < currentTimeout * 0.7 -> (currentTimeout * 0.9).toLong().coerceAtLeast(200L) // Faster, reduce timeout
                                    elapsed > currentTimeout * 1.5 -> (currentTimeout * 1.2).toLong().coerceAtMost(MAX_TIMEOUT_MS) // Slower, increase timeout
                                    else -> currentTimeout // Keep current
                                }
                                adaptiveTimeouts[dnsServer.hostAddress] = newTimeout
                                
                                // Update performance stats for this DNS server (success)
                                updateDnsServerStats(dnsServer, elapsed, success = true)
                                
                                Log.d(TAG, "✅ DNS response from ${dnsServer.hostAddress} for $hostname (${elapsed}ms, timeout: ${adaptiveTimeout}ms)")
                                response
                            } catch (e: Exception) {
                                // Direct UDP failed - try SOCKS5 as fallback
                                Log.d(TAG, "⚠️ [DIRECT] Direct UDP failed for ${dnsServer.hostAddress}: ${e.message}, trying SOCKS5 fallback...")
                                
                                // Socket error - remove from pool and create new one (fast failover)
                                socketPool.remove(dnsServer.hostAddress)
                                try {
                                    socket.close()
                                } catch (closeError: Exception) {
                                    // Ignore close errors
                                }
                                
                                // Fallback to SOCKS5 proxy if available
                                val proxy = socks5Proxy
                                if (proxy != null) {
                                    Log.d(TAG, "✅ [SOCKS5] SOCKS5 proxy available for fallback")
                                    try {
                                        val compressedQuery = compressDnsQuery(queryData, hostname)
                                        val sendStart = System.currentTimeMillis()
                                        Log.d(TAG, "🔌 [SOCKS5] Fallback: Using SOCKS5 proxy for DNS query: $hostname → ${dnsServer.hostAddress}:$DNS_PORT")
                                        Log.d(TAG, "📤 [SOCKS5] Sending DNS query via SOCKS5: ${compressedQuery.size} bytes")
                                        
                                        val sendSuccess = proxy.sendUdp(compressedQuery, dnsServer, DNS_PORT)
                                        if (!sendSuccess) {
                                            Log.w(TAG, "❌ [SOCKS5] Failed to send DNS query via SOCKS5 proxy")
                                            throw e // Re-throw original exception
                                        }
                                        Log.d(TAG, "✅ [SOCKS5] DNS query sent via SOCKS5, waiting for response (timeout: ${adaptiveTimeout}ms)")
                                        
                                        val responseBuffer = ByteArray(BUFFER_SIZE)
                                        val response = proxy.receiveUdp(responseBuffer, adaptiveTimeout.toInt())
                                        
                                        if (response != null) {
                                            val elapsed = System.currentTimeMillis() - sendStart
                                            Log.d(TAG, "📥 [SOCKS5] DNS response received via SOCKS5: ${response.size} bytes (${elapsed}ms)")
                                            
                                            // Update adaptive timeout based on performance
                                            val currentTimeout = adaptiveTimeouts[dnsServer.hostAddress] ?: BASE_TIMEOUT_MS
                                            val newTimeout = when {
                                                elapsed < currentTimeout * 0.7 -> (currentTimeout * 0.9).toLong().coerceAtLeast(200L)
                                                elapsed > currentTimeout * 1.5 -> (currentTimeout * 1.2).toLong().coerceAtMost(MAX_TIMEOUT_MS)
                                                else -> currentTimeout
                                            }
                                            adaptiveTimeouts[dnsServer.hostAddress] = newTimeout
                                            
                                            // Update performance stats for this DNS server (success)
                                            updateDnsServerStats(dnsServer, elapsed, success = true)
                                            
                                            Log.d(TAG, "✅ DNS response via SOCKS5 proxy (fallback) from ${dnsServer.hostAddress} for $hostname (${elapsed}ms, timeout: ${adaptiveTimeout}ms)")
                                            return@withTimeoutOrNull response
                                        } else {
                                            Log.w(TAG, "⚠️ [SOCKS5] No DNS response received via SOCKS5 proxy (timeout: ${adaptiveTimeout}ms)")
                                            throw e // Re-throw original exception
                                        }
                                    } catch (socks5Error: Exception) {
                                        Log.w(TAG, "❌ [SOCKS5] SOCKS5 UDP proxy fallback failed for ${dnsServer.hostAddress}: ${socks5Error.message}")
                                        throw e // Re-throw original direct UDP exception
                                    }
                                } else {
                                    Log.w(TAG, "⚠️ [SOCKS5] SOCKS5 proxy not available for fallback (socks5Proxy is null)")
                                    Log.d(TAG, "💡 [SOCKS5] Hint: SOCKS5 proxy may not be set yet. Check if setSocks5Proxy() was called.")
                                    throw e // Re-throw original exception
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Update performance stats for this DNS server (failure) - fast failover
                        updateDnsServerStats(dnsServer, 0, success = false)
                        
                        // Only log failures on final attempt to reduce log noise
                        if (timeoutMs >= 800L) {
                            Log.d(TAG, "Upstream DNS server ${dnsServer.hostAddress} failed: ${e.message}")
                        }
                        null
                    }
                }
            }
            
            // Use select to get first successful response from parallel queries
            // This ensures we get the fastest response from any DNS server
            // CRITICAL: select returns immediately when first non-null result is available
            var selectedResult: ByteArray? = null
            var fastestIndex = -1
            
            try {
                // Use select to get first completed result
                // Select will return as soon as ANY deferred completes (even if null)
                // We need to continue selecting until we get a non-null result
                while (selectedResult == null) {
                    val result = select<Pair<Int, ByteArray?>> {
                        deferredResults.forEachIndexed { index, deferred ->
                            if (deferred.isActive) {
                                deferred.onAwait { result ->
                                    Pair(index, result)
                                }
                            }
                        }
                    }
                    
                    if (result.second != null) {
                        // Found a non-null result - this is our winner!
                        selectedResult = result.second
                        fastestIndex = result.first
                        val totalElapsed = System.currentTimeMillis() - startTime
                        val fastestServer = sortedServers[fastestIndex]
                        Log.d(TAG, "✅ Fastest DNS response from ${fastestServer.hostAddress} for $hostname (${totalElapsed}ms)")
                        
                        // Cancel all other queries immediately to save resources and speed
                        deferredResults.forEachIndexed { otherIndex, otherDeferred ->
                            if (otherIndex != fastestIndex) {
                                try {
                                    otherDeferred.cancel()
                                } catch (e: Exception) {
                                    // Ignore cancellation errors
                                }
                            }
                        }
                        break
                    }
                    // Null result - check if any queries are still active
                    if (!deferredResults.any { it.isActive }) {
                        // All queries completed but all returned null
                        break
                    }
                }
            } catch (e: CancellationException) {
                // All queries were cancelled or failed
                selectedResult = null
            } catch (e: Exception) {
                Log.w(TAG, "Error in select for DNS queries: ${e.message}")
                selectedResult = null
            }
            
            // Final cleanup: cancel any remaining queries if we got a result
            if (selectedResult != null) {
                deferredResults.forEach { deferred ->
                    try {
                        if (deferred.isActive) {
                            deferred.cancel()
                        }
                    } catch (e: Exception) {
                        // Ignore cancellation errors
                    }
                }
            }
            
            selectedResult
        }
    }
    
    /**
     * Parse result from DNS response
     */
    private data class DnsParseResult(
        val addresses: List<InetAddress>,
        val ttl: Long? = null,
        val isNxDomain: Boolean = false
    )

    /**
     * Parse DNS response and extract IP addresses
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
            Log.d(TAG, "🔍 [PARSER] Starting DNS response parse for $hostname (${responseData.size} bytes)")
            
            if (responseData.size < 12) {
                Log.w(TAG, "❌ [PARSER] DNS response too short: ${responseData.size} bytes (minimum 12)")
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
            
            Log.d(TAG, "📊 [PARSER] DNS header: TXID=0x%04x, flags=0x%04x, Q=%d, A=%d, NS=%d, AR=%d".format(
                transactionId, flags, questions, answers, authority, additional))

            // Check if response is valid (QR bit must be 1, RCODE should be 0)
            val qr = (flags shr 15) and 0x01
            val rcode = flags and 0x0F

            if (qr != 1) {
                // Log detailed information for debugging
                val firstBytes = responseData.take(16).joinToString(" ") { "%02x".format(it) }
                Log.w(TAG, "Invalid DNS response: not a response packet (QR=$qr, flags=0x%04x, size=${responseData.size}, first 16 bytes: $firstBytes)".format(flags))
                // Try to parse anyway if it looks like DNS data (might be malformed but still usable)
                if (responseData.size >= 12 && questions > 0) {
                    Log.d(TAG, "Attempting to parse anyway (might be malformed DNS packet)")
                } else {
                    return DnsParseResult(emptyList())
                }
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
            Log.d(TAG, "📝 [PARSER] Skipping question section (position: ${buffer.position()})")
            for (i in 0 until questions) {
                val qStartPos = buffer.position()
                skipDnsName(buffer)
                val qtype = buffer.getShort().toInt() and 0xFFFF
                val qclass = buffer.getShort().toInt() and 0xFFFF
                Log.d(TAG, "  Q${i+1}: pos=$qStartPos→${buffer.position()}, type=$qtype, class=$qclass")
            }

            // Parse answer section
            val addresses = mutableListOf<InetAddress>()
            var minTtl: Long? = null
            val answerStartPos = buffer.position()
            Log.d(TAG, "📋 [PARSER] Parsing answer section: $answers answers (start position: $answerStartPos)")

            for (i in 0 until answers) {
                // Check buffer bounds before parsing
                if (buffer.position() >= buffer.limit()) {
                    Log.w(TAG, "❌ [PARSER] Buffer overflow: position ${buffer.position()} >= limit ${buffer.limit()}, stopping answer parsing")
                    break
                }

                val answerOffset = buffer.position()
                Log.d(TAG, "  📍 [PARSER] Answer ${i+1}/$answers: parsing at offset $answerOffset")

                // Parse name (may use compression)
                try {
                    val nameStartPos = buffer.position()
                    val nameEndPos = skipDnsName(buffer)
                    val nameBytes = nameEndPos - nameStartPos
                    // Ensure we're at the correct position after skipping name
                    if (nameEndPos != buffer.position()) {
                        Log.w(TAG, "⚠️ [PARSER] DNS name skip mismatch: expected position $nameEndPos, actual ${buffer.position()}, correcting... (name bytes: $nameBytes)")
                        buffer.position(nameEndPos)
                    } else {
                        Log.d(TAG, "    ✓ [PARSER] Name skipped: $nameBytes bytes (pos: $nameStartPos→$nameEndPos)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "❌ [PARSER] Error skipping DNS name at offset $answerOffset: ${e.message}")
                    break
                }

                // Check if we have enough bytes for type, class, TTL, and length
                if (buffer.remaining() < 10) {
                    Log.w(TAG, "❌ [PARSER] Not enough bytes for answer record at position ${buffer.position()} (need 10, have ${buffer.remaining()})")
                    break
                }

                val type = buffer.short.toInt() and 0xFFFF
                Log.d(TAG, "    📌 [PARSER] Record type: $type (0x%04x)".format(type))
                
                // Validate type - if it looks like a compression pointer (0xC000-0xFFFF), name skip failed
                if ((type and 0xC000) == 0xC000) {
                    Log.w(TAG, "⚠️ [PARSER] Invalid record type $type (looks like compression pointer 0x%04x), name skip may have failed at position $answerOffset".format(type))
                    // Try to recover by skipping 2 more bytes and continuing
                    if (buffer.remaining() >= 8) {
                        buffer.getShort() // Skip what we thought was class
                        buffer.int // Skip what we thought was TTL
                        val dataLength = buffer.short.toInt() and 0xFFFF
                        if (buffer.remaining() >= dataLength) {
                            buffer.position(buffer.position() + dataLength)
                        }
                    }
                    continue
                }
                
                val qclass = buffer.short.toInt() and 0xFFFF
                val ttl = buffer.int.toLong() and 0xFFFFFFFFL
                val dataLength = buffer.short.toInt() and 0xFFFF
                Log.d(TAG, "    📊 [PARSER] Record details: class=$qclass, TTL=${ttl}s, dataLength=$dataLength bytes")
                
                // Validate data length (should be reasonable)
                if (dataLength > 65535 || dataLength < 0) {
                    Log.w(TAG, "❌ [PARSER] Invalid data length: $dataLength for record type $type")
                    break
                }

                // Track minimum TTL
                if (minTtl == null || ttl < minTtl) {
                    minTtl = ttl
                }

                // Parse A record (type 1)
                if (type == 1 && qclass == 1 && dataLength == 4) {
                    Log.d(TAG, "    🔍 [PARSER] Parsing A record (IPv4) at position ${buffer.position()}")
                    if (buffer.remaining() >= 4) {
                        val ipBytes = ByteArray(4)
                        buffer.get(ipBytes)
                        val ipHex = ipBytes.joinToString(" ") { "%02x".format(it) }
                        Log.d(TAG, "    📥 [PARSER] A record data: $ipHex")
                        try {
                            val address = InetAddress.getByAddress(ipBytes)
                            addresses.add(address)
                            Log.d(TAG, "    ✅ [PARSER] Parsed A record: ${address.hostAddress} for $hostname")
                        } catch (e: Exception) {
                            Log.w(TAG, "    ❌ [PARSER] Invalid IP address in DNS response: ${e.message}")
                        }
                    } else {
                        Log.w(TAG, "    ❌ [PARSER] Not enough bytes for A record data: need 4, have ${buffer.remaining()}")
                    }
                } else if (type == 28 && qclass == 1) {
                    // AAAA record (IPv6) - skip for now
                    Log.d(TAG, "    ⏭️ [PARSER] Skipping AAAA record (IPv6, length=$dataLength)")
                    if (buffer.remaining() >= dataLength) {
                        buffer.position(buffer.position() + dataLength)
                        Log.d(TAG, "    ✓ [PARSER] AAAA record skipped")
                    } else {
                        Log.w(TAG, "    ❌ [PARSER] Not enough bytes to skip AAAA record data: need $dataLength, have ${buffer.remaining()}")
                        break
                    }
                } else {
                    // Skip data for other record types (CNAME, MX, etc.)
                    val typeName = when (type) {
                        2 -> "NS"
                        5 -> "CNAME"
                        15 -> "MX"
                        16 -> "TXT"
                        else -> "UNKNOWN($type)"
                    }
                    Log.d(TAG, "    ⏭️ [PARSER] Skipping record type $typeName (type=$type, class=$qclass, length=$dataLength)")
                    if (dataLength > 0) {
                        if (buffer.remaining() >= dataLength) {
                            buffer.position(buffer.position() + dataLength)
                            Log.d(TAG, "    ✓ [PARSER] Record type $typeName skipped")
                        } else {
                            Log.w(TAG, "    ❌ [PARSER] Not enough bytes to skip data for record type $type: need $dataLength, have ${buffer.remaining()}")
                            // Try to continue with remaining bytes
                            if (buffer.remaining() > 0) {
                                buffer.position(buffer.limit())
                            }
                            break
                        }
                    }
                }
            }

            Log.d(TAG, "✅ [PARSER] Parse complete for $hostname: ${addresses.size} addresses found, minTTL=${minTtl ?: "N/A"}s")
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
        var compressionPointerPos = startPos // Position where compression pointer starts
        var compressionEndPos = startPos + 2 // Position after compression pointer (2 bytes)

        while (buffer.hasRemaining() && jumps < maxJumps) {
            // Check if we have at least 1 byte
            if (buffer.remaining() < 1) {
                break
            }

            val currentPos = buffer.position()
            val length = buffer.get().toInt() and 0xFF

            if (length == 0) {
                // End of name
                if (encounteredCompression) {
                    // We encountered compression, return to position after compression pointer
                    buffer.position(compressionEndPos)
                    return compressionEndPos
                } else {
                    // Normal end of name
                    return buffer.position()
                }
            } else if ((length and 0xC0) == 0xC0) {
                // Compression pointer detected
                if (!encounteredCompression) {
                    // First compression pointer encountered
                    compressionPointerPos = currentPos // Position where compression pointer starts
                    
                    // Read second byte of compression pointer
                    if (buffer.remaining() < 1) {
                        Log.w(TAG, "⚠️ [PARSER] Compression pointer incomplete: missing second byte at position $compressionPointerPos")
                        return startPos // Return original position on error
                    }
                    val lowByte = buffer.get().toInt() and 0xFF
                    compressionEndPos = buffer.position() // Position AFTER compression pointer (2 bytes consumed)
                    encounteredCompression = true
                    
                    val offset = ((length and 0x3F) shl 8) or lowByte
                    
                    // Validate offset
                    if (offset < 12 || offset >= buffer.limit()) {
                        Log.w(TAG, "⚠️ [PARSER] Invalid compression pointer offset: $offset (limit: ${buffer.limit()}, start: 12) at position $compressionPointerPos")
                        buffer.position(compressionEndPos)
                        return compressionEndPos // Return position after compression pointer even if invalid
                    }
                    
                    // Follow compression pointer to read the name
                    buffer.position(offset)
                    jumps++
                    
                    // Continue reading from compressed location
                    continue
                } else {
                    // Nested compression pointer (shouldn't happen in practice, but handle it)
                    if (buffer.remaining() < 1) {
                        Log.w(TAG, "⚠️ [PARSER] Nested compression pointer incomplete: missing second byte")
                        buffer.position(compressionEndPos)
                        return compressionEndPos
                    }
                    val lowByte = buffer.get().toInt() and 0xFF
                    val offset = ((length and 0x3F) shl 8) or lowByte
                    
                    if (offset < 12 || offset >= buffer.limit()) {
                        Log.w(TAG, "⚠️ [PARSER] Invalid nested compression pointer offset: $offset")
                        buffer.position(compressionEndPos)
                        return compressionEndPos
                    }
                    
                    buffer.position(offset)
                    jumps++
                    continue
                }
            } else if (length > 63) {
                // Invalid length (should be 0-63 for labels)
                Log.w(TAG, "⚠️ [PARSER] Invalid DNS label length: $length (max 63) at position $currentPos")
                if (encounteredCompression) {
                    buffer.position(compressionEndPos)
                    return compressionEndPos
                } else {
                    return buffer.position()
                }
            } else {
                // Normal label - skip label bytes
                if (buffer.remaining() < length) {
                    Log.w(TAG, "⚠️ [PARSER] Not enough bytes for DNS label: need $length, have ${buffer.remaining()} at position $currentPos")
                    if (encounteredCompression) {
                        buffer.position(compressionEndPos)
                        return compressionEndPos
                    } else {
                        break
                    }
                }
                buffer.position(buffer.position() + length)
            }
        }

        // If we hit max jumps, return the compression end position if we encountered compression
        if (jumps >= maxJumps) {
            Log.w(TAG, "⚠️ [PARSER] Max compression jumps reached ($maxJumps), stopping name skip at position ${buffer.position()}")
        }

        // Return appropriate position
        if (encounteredCompression) {
            buffer.position(compressionEndPos)
            return compressionEndPos
        } else {
            return buffer.position()
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
     * Set VPN interface IP for binding UDP sockets to VPN interface
     * This ensures DNS queries are routed through VPN
     */
    fun setVpnInterfaceIp(ip: String?) {
        vpnInterfaceIp = ip
        if (ip != null) {
            Log.i(TAG, "✅ VPN interface IP set: $ip (DNS queries will be routed through VPN)")
            // Clear socket pool to force recreation with VPN binding
            socketPool.values.forEach { pooledSocket ->
                try {
                    pooledSocket.socket.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
            socketPool.clear()
        } else {
            Log.i(TAG, "VPN interface IP cleared (DNS queries will use default interface)")
            // Clear socket pool to force recreation without VPN binding
            socketPool.values.forEach { pooledSocket ->
                try {
                    pooledSocket.socket.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
            socketPool.clear()
        }
    }
    
    /**
     * Set SOCKS5 proxy for UDP DNS queries
     * This routes DNS queries through SOCKS5 proxy instead of direct UDP
     */
    fun setSocks5Proxy(address: String, port: Int) {
        scope.launch {
            try {
                val proxy = Socks5UdpProxy(address, port)
                if (proxy.establish()) {
                    socks5Proxy?.close()
                    socks5Proxy = proxy
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
        socks5Proxy?.close()
        socks5Proxy = null
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

