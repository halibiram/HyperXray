package com.hyperxray.an.core.network.dns

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

private const val TAG = "Socks5UdpClient"

/**
 * SOCKS5 UDP proxy wrapper for DNS queries
 * Implements SOCKS5 UDP ASSOCIATE protocol
 */
class Socks5UdpClient(
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
    
    /**
     * Close SOCKS5 connection and cleanup resources
     */
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

