package com.hyperxray.an.core.network.dns

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "Socks5UdpClient"
private const val RESPONSE_TIMEOUT_MS = 10000L // 10 seconds timeout for responses
private const val KEEP_ALIVE_CHECK_INTERVAL_MS = 30000L // Check connection health every 30s
private const val MAX_RETRIES = 1 // Maximum retry attempts on send failure

/**
 * SOCKS5 UDP proxy wrapper for DNS queries with Transaction ID-based response dispatching
 * 
 * CRITICAL ARCHITECTURE:
 * - Background response listener loop continuously reads from UDP socket
 * - Transaction ID matching routes responses to correct waiting coroutines
 * - Mutex ensures only one thread writes to UDP socket at a time
 * - Auto-reconnect and keep-alive maintain connection health
 * - Supports 50+ concurrent DNS queries without mixing responses
 */
class Socks5UdpClient(
    private val proxyAddress: String,
    private val proxyPort: Int
) {
    // TCP socket for SOCKS5 handshake and UDP ASSOCIATE
    private var tcpSocket: Socket? = null
    
    // UDP relay endpoint from SOCKS5 server
    @Volatile
    private var udpRelayAddress: InetAddress? = null
    
    @Volatile
    private var udpRelayPort: Int = 0
    
    // Single UDP socket for all SOCKS5 UDP traffic (SOCKS5 requirement)
    @Volatile
    private var udpSocket: DatagramSocket? = null
    
    // Response dispatcher: Transaction ID -> CompletableDeferred<ByteArray>
    // CRITICAL: This maps DNS Transaction IDs to waiting coroutines
    private val pendingResponses = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()
    
    // Mutex for serializing UDP socket writes (prevents buffer corruption)
    private val writeMutex = Mutex()
    
    // Background response listener job
    private var responseListenerJob: Job? = null
    
    // Coroutine scope for background operations
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Connection state tracking
    private val isEstablished = AtomicBoolean(false)
    private val isShuttingDown = AtomicBoolean(false)
    
    // Transaction ID generator (simple counter, wraps at 65535)
    private val transactionIdCounter = AtomicInteger((System.currentTimeMillis() % 65536).toInt())
    
    /**
     * Generate unique Transaction ID for DNS query
     * DNS Transaction ID is 16-bit (0-65535)
     */
    private fun generateTransactionId(): Int {
        return transactionIdCounter.incrementAndGet() and 0xFFFF
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
     * Establish SOCKS5 connection and get UDP relay endpoint
     * Also starts background response listener
     * Uses exponential backoff retry for network resilience
     */
    suspend fun establish(): Boolean = withContext(Dispatchers.IO) {
        if (isShuttingDown.get()) {
            Log.w(TAG, "Cannot establish: client is shutting down")
            return@withContext false
        }
        
        return@withContext try {
            retryWithBackoff(times = 3, initialDelay = 200, factor = 2.0) {
                // Close existing connection if any
                closeInternal()
                
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
                    throw IOException("SOCKS5 handshake failed: ${handshakeResponse.joinToString { "%02x".format(it) }}")
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
                    throw IOException("SOCKS5 UDP ASSOCIATE failed: REP=${udpAssociateResponse[1]}")
                }
                
                // Extract UDP relay endpoint
                val addrBytes = udpAssociateResponse.sliceArray(4..7)
                udpRelayAddress = InetAddress.getByAddress(addrBytes)
                udpRelayPort = ((udpAssociateResponse[8].toInt() and 0xFF) shl 8) or (udpAssociateResponse[9].toInt() and 0xFF)
                
                // Create UDP socket for SOCKS5 relay
                udpSocket = DatagramSocket().apply {
                    soTimeout = 5000 // Default timeout for receive operations
                }
                
                // Start background response listener
                startResponseListener()
                
                // Start keep-alive monitor
                startKeepAliveMonitor()
                
                isEstablished.set(true)
                Log.i(TAG, "✅ SOCKS5 UDP ASSOCIATE established: ${udpRelayAddress?.hostAddress}:$udpRelayPort")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish SOCKS5 UDP proxy after retries: ${e.message}", e)
            closeInternal()
            false
        }
    }
    
    /**
     * Start background response listener loop
     * Continuously reads from UDP socket and dispatches responses by Transaction ID
     */
    private fun startResponseListener() {
        // Cancel existing listener if any
        responseListenerJob?.cancel()
        
        responseListenerJob = clientScope.launch {
            val socket = udpSocket ?: return@launch
            val buffer = ByteArray(65535) // Maximum UDP packet size
            
            Log.d(TAG, "🔄 Starting background response listener")
            
            while (isActive && !isShuttingDown.get() && !socket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    
                    // Unwrap SOCKS5 header
                    val wrapped = ByteArray(packet.length)
                    System.arraycopy(packet.data, 0, wrapped, 0, packet.length)
                    val unwrapped = unwrapUdpPacket(wrapped)
                    
                    if (unwrapped == null) {
                        Log.w(TAG, "⚠️ Failed to unwrap SOCKS5 UDP packet (${wrapped.size} bytes)")
                        continue
                    }
                    
                    // Extract Transaction ID from DNS packet
                    val transactionId = extractTransactionId(unwrapped)
                    if (transactionId == null) {
                        Log.w(TAG, "⚠️ Failed to extract Transaction ID from DNS response (${unwrapped.size} bytes)")
                        continue
                    }
                    
                    // Route response to waiting coroutine
                    val deferred = pendingResponses.remove(transactionId)
                    if (deferred != null) {
                        if (deferred.isActive) {
                            deferred.complete(unwrapped)
                            Log.d(TAG, "✅ Dispatched response for TXID=0x%04x (${unwrapped.size} bytes)".format(transactionId))
                        } else {
                            Log.d(TAG, "⚠️ Deferred for TXID=0x%04x is no longer active, dropping response".format(transactionId))
                        }
                    } else {
                        Log.w(TAG, "⚠️ No pending request found for TXID=0x%04x, dropping response".format(transactionId))
                    }
                } catch (e: SocketTimeoutException) {
                    // Expected timeout, continue listening
                    continue
                } catch (e: SocketException) {
                    if (!isShuttingDown.get() && isActive) {
                        Log.w(TAG, "SocketException in response listener: ${e.message}, reconnecting...")
                        // Attempt to reconnect
                        delay(1000)
                        if (isActive && !isShuttingDown.get()) {
                            establish()
                        }
                    }
                    break
                } catch (e: Exception) {
                    if (!isShuttingDown.get() && isActive) {
                        Log.e(TAG, "Error in response listener: ${e.message}", e)
                        delay(1000) // Brief delay before retrying
                    } else {
                        break
                    }
                }
            }
            
            Log.d(TAG, "🛑 Background response listener stopped")
        }
    }
    
    /**
     * Start keep-alive monitor to check connection health
     */
    private fun startKeepAliveMonitor() {
        clientScope.launch {
            while (isActive && !isShuttingDown.get()) {
                delay(KEEP_ALIVE_CHECK_INTERVAL_MS)
                
                // Check TCP socket health
                val tcp = tcpSocket
                if (tcp == null || tcp.isClosed || !tcp.isConnected) {
                    Log.w(TAG, "⚠️ TCP socket unhealthy, reconnecting...")
                    if (!isShuttingDown.get()) {
                        establish()
                    }
                    continue
                }
                
                // Check UDP socket health
                val udp = udpSocket
                if (udp == null || udp.isClosed) {
                    Log.w(TAG, "⚠️ UDP socket unhealthy, reconnecting...")
                    if (!isShuttingDown.get()) {
                        establish()
                    }
                    continue
                }
            }
        }
    }
    
    /**
     * Check if connection is established and healthy
     */
    private suspend fun ensureConnection(): Boolean {
        if (isShuttingDown.get()) return false
        
        val tcp = tcpSocket
        val udp = udpSocket
        val relayAddr = udpRelayAddress
        val relayPort = udpRelayPort
        
        // Check if connection is valid
        if (tcp != null && !tcp.isClosed && tcp.isConnected &&
            udp != null && !udp.isClosed &&
            relayAddr != null && relayPort != 0) {
            return true
        }
        
        // Connection is invalid, try to reestablish
        Log.w(TAG, "Connection invalid, reestablishing...")
        return establish()
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
            return null
        }
        
        // Check RSV (must be 0x0000)
        if (wrapped[0] != 0x00.toByte() || wrapped[1] != 0x00.toByte()) {
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
            return null
        }
        
        // Extract data (skip header)
        val data = wrapped.sliceArray(headerSize until wrapped.size)
        if (data.isEmpty()) {
            return null
        }
        
        return data
    }
    
    /**
     * Send UDP packet through SOCKS5 proxy and wait for response
     * 
     * CRITICAL: This method extracts Transaction ID from DNS query, registers a deferred,
     * sends the packet, and waits for the matching response via the dispatcher.
     * 
     * @param data DNS query packet (must contain Transaction ID in first 2 bytes)
     * @param targetAddress Target DNS server address
     * @param targetPort Target DNS server port (usually 53)
     * @param timeoutMs Timeout in milliseconds
     * @return DNS response packet or null on timeout/error
     */
    suspend fun sendUdpAndReceive(
        data: ByteArray,
        targetAddress: InetAddress,
        targetPort: Int,
        timeoutMs: Int = 5000
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (isShuttingDown.get()) {
            return@withContext null
        }
        
        // Ensure connection is established
        if (!ensureConnection()) {
            Log.e(TAG, "Failed to establish connection")
            return@withContext null
        }
        
        // Extract Transaction ID from DNS query
        val transactionId = extractTransactionId(data)
        if (transactionId == null) {
            Log.e(TAG, "Failed to extract Transaction ID from DNS query (${data.size} bytes)")
            return@withContext null
        }
        
        // Create deferred for this response
        val deferred = CompletableDeferred<ByteArray>()
        pendingResponses[transactionId] = deferred
        
        try {
            // Wrap packet with SOCKS5 header
            val wrapped = wrapUdpPacket(data, targetAddress, targetPort)
            val relayAddr = udpRelayAddress!!
            val relayPort = udpRelayPort
            
            // Send with mutex protection and retry logic
            try {
                retryWithBackoff(times = 3, initialDelay = 100, factor = 2.0) {
                    writeMutex.withLock {
                        val socket = udpSocket
                        if (socket == null || socket.isClosed) {
                            throw SocketException("UDP socket is closed")
                        }
                        
                        val packet = DatagramPacket(wrapped, wrapped.size, relayAddr, relayPort)
                        socket.send(packet)
                        Log.d(TAG, "📤 Sent DNS query via SOCKS5: TXID=0x%04x, ${data.size} bytes".format(transactionId))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send UDP packet after retries: ${e.message}")
                SocketMetrics.recordPacketError()
                pendingResponses.remove(transactionId)
                return@withContext null
            }
            
            // Wait for response with timeout
            val response = withTimeoutOrNull(timeoutMs.toLong()) {
                deferred.await()
            }
            
            if (response == null) {
                Log.w(TAG, "⏱️ Timeout waiting for response: TXID=0x%04x (${timeoutMs}ms)".format(transactionId))
            } else {
                Log.d(TAG, "📥 Received DNS response via SOCKS5: TXID=0x%04x, ${response.size} bytes".format(transactionId))
            }
            
            return@withContext response
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendUdpAndReceive: ${e.message}", e)
            SocketMetrics.recordPacketError()
            pendingResponses.remove(transactionId)
            return@withContext null
        } finally {
            // Cleanup: remove deferred if still present (timeout case)
            pendingResponses.remove(transactionId)
        }
    }
    
    /**
     * Legacy method: Send UDP packet (for backward compatibility)
     * Note: This doesn't wait for response - use sendUdpAndReceive for full request/response
     */
    suspend fun sendUdp(data: ByteArray, targetAddress: InetAddress, targetPort: Int): Boolean {
        if (isShuttingDown.get()) return false
        
        if (!ensureConnection()) {
            return false
        }
        
        return try {
            val wrapped = wrapUdpPacket(data, targetAddress, targetPort)
            val relayAddr = udpRelayAddress!!
            val relayPort = udpRelayPort
            
            writeMutex.withLock {
                val socket = udpSocket
                if (socket == null || socket.isClosed) {
                    return false
                }
                
                val packet = DatagramPacket(wrapped, wrapped.size, relayAddr, relayPort)
                socket.send(packet)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send UDP through SOCKS5: ${e.message}", e)
            SocketMetrics.recordPacketError()
            false
        }
    }
    
    /**
     * Legacy method: Receive UDP packet (for backward compatibility)
     * Note: This blocks and may receive wrong response - use sendUdpAndReceive instead
     */
    suspend fun receiveUdp(buffer: ByteArray, timeoutMs: Int): ByteArray? {
        // This method is deprecated - responses are handled by the dispatcher
        // But we keep it for backward compatibility
        Log.w(TAG, "receiveUdp() is deprecated - use sendUdpAndReceive() instead")
        return null
    }
    
    /**
     * Internal close method (doesn't cancel scope)
     */
    private fun closeInternal() {
        responseListenerJob?.cancel()
        responseListenerJob = null
        
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
        
        // Cancel all pending responses
        pendingResponses.values.forEach { deferred ->
            if (deferred.isActive) {
                deferred.cancel()
            }
        }
        pendingResponses.clear()
        
        isEstablished.set(false)
    }
    
    /**
     * Close SOCKS5 connection and cleanup resources
     */
    fun close() {
        isShuttingDown.set(true)
        closeInternal()
        clientScope.cancel()
    }
}
