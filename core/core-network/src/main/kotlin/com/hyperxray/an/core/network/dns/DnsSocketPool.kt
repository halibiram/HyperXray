package com.hyperxray.an.core.network.dns

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.channels.ClosedChannelException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "DnsSocketPool"
private const val SOCKET_POOL_TIMEOUT_MS = 10000L // 10 seconds - sockets expire after 10s of inactivity
private const val SOCKET_POOL_CLEANUP_INTERVAL_MS = 30000L // Cleanup every 30 seconds

/**
 * Safe socket wrapper that prevents "Closed Pipe" crashes
 * Wraps all UDP operations with robust error handling
 */
class SafeSocket(
    private val socket: DatagramSocket,
    private val server: InetAddress
) {
    /**
     * Send UDP packet with robust error handling
     * Swallows SocketException during cancellation
     */
    suspend fun send(packet: DatagramPacket): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            socket.send(packet)
            true
        } catch (e: SocketException) {
            // Socket closed or network error - ignore during cancellation
            Log.w(TAG, "SocketException during send: ${e.message}")
            false
        } catch (e: ClosedChannelException) {
            // Socket closed - ignore during cancellation
            Log.w(TAG, "ClosedChannelException during send: ${e.message}")
            false
        } catch (e: InterruptedIOException) {
            // Interrupted - ignore during cancellation
            Log.w(TAG, "InterruptedIOException during send: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during send: ${e.message}", e)
            false
        }
    }
    
    /**
     * Receive UDP packet with robust error handling
     * Swallows SocketException during cancellation
     */
    suspend fun receive(packet: DatagramPacket): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            socket.receive(packet)
            true
        } catch (e: SocketException) {
            // Socket closed or network error - ignore during cancellation
            Log.w(TAG, "SocketException during receive: ${e.message}")
            false
        } catch (e: ClosedChannelException) {
            // Socket closed - ignore during cancellation
            Log.w(TAG, "ClosedChannelException during receive: ${e.message}")
            false
        } catch (e: InterruptedIOException) {
            // Interrupted - ignore during cancellation
            Log.w(TAG, "InterruptedIOException during receive: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during receive: ${e.message}", e)
            false
        }
    }
    
    /**
     * Check if socket is valid
     */
    fun isValid(): Boolean {
        return try {
            !socket.isClosed && socket.isBound
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get underlying socket (for direct access when needed)
     */
    fun getSocket(): DatagramSocket = socket
    
    /**
     * Get server address
     */
    fun getServer(): InetAddress = server
    
    /**
     * Close socket atomically
     */
    fun close() {
        try {
            if (!socket.isClosed) {
                socket.close()
            }
        } catch (e: Exception) {
            // Ignore close errors
        }
    }
}

/**
 * Pooled socket data class
 */
private data class PooledSocket(
    val safeSocket: SafeSocket,
    val lastUsed: AtomicLong = AtomicLong(System.currentTimeMillis()),
    val vpnInterfaceIp: String? = null // Track which VPN IP this socket was bound to
)

/**
 * DNS Socket Pool Manager
 * Manages socket lifecycle, pooling, and "Closed Pipe" prevention
 */
class DnsSocketPool(
    private val scope: CoroutineScope,
    private var vpnInterfaceIp: String? = null
) {
    private val socketPool = ConcurrentHashMap<String, PooledSocket>()
    
    init {
        // Start socket pool cleanup job
        scope.launch {
            while (isActive) {
                delay(SOCKET_POOL_CLEANUP_INTERVAL_MS)
                cleanupExpiredSockets()
            }
        }
    }
    
    /**
     * Cleanup expired sockets from pool
     */
    private fun cleanupExpiredSockets() {
        val now = System.currentTimeMillis()
        socketPool.entries.removeIf { (_, pooledSocket) ->
            val age = now - pooledSocket.lastUsed.get()
            if (age > SOCKET_POOL_TIMEOUT_MS) {
                try {
                    pooledSocket.safeSocket.close()
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
    fun getPooledSocket(server: InetAddress, timeoutMs: Long): SafeSocket {
        val serverKey = server.hostAddress
        val pooled = socketPool[serverKey]
        
        if (pooled != null) {
            val age = System.currentTimeMillis() - pooled.lastUsed.get()
            // Check if VPN IP changed or socket is not bound to current VPN IP
            val vpnIpChanged = pooled.vpnInterfaceIp != vpnInterfaceIp
            // Also check if socket is still valid
            val socketValid = pooled.safeSocket.isValid()
            
            if (age < SOCKET_POOL_TIMEOUT_MS && !vpnIpChanged && socketValid) {
                pooled.lastUsed.set(System.currentTimeMillis())
                return pooled.safeSocket
            } else {
                // Socket expired, VPN IP changed, or socket invalid - remove from pool
                socketPool.remove(serverKey)
                try {
                    pooled.safeSocket.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
        }
        
        // Create new socket
        val newSocket = createSocket(timeoutMs)
        val safeSocket = SafeSocket(newSocket, server)
        
        // Add to pool with VPN IP tracking
        socketPool[serverKey] = PooledSocket(safeSocket, vpnInterfaceIp = vpnInterfaceIp)
        return safeSocket
    }
    
    /**
     * Create ephemeral socket for a single query (not from pool)
     * This prevents race conditions when queries are cancelled
     */
    fun createEphemeralSocket(timeoutMs: Long): SafeSocket {
        val socket = createSocket(timeoutMs)
        // Use a dummy server address for ephemeral sockets
        return SafeSocket(socket, InetAddress.getByName("127.0.0.1"))
    }
    
    /**
     * Create a new DatagramSocket with proper configuration
     */
    private fun createSocket(timeoutMs: Long): DatagramSocket {
        return try {
            if (vpnInterfaceIp != null) {
                // Bind to VPN interface IP to route DNS queries through VPN
                DatagramSocket(null).apply {
                    soTimeout = timeoutMs.toInt()
                    try {
                        reuseAddress = true
                        bind(InetSocketAddress(vpnInterfaceIp, 0))
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
    }
    
    /**
     * Update VPN interface IP and clear socket pool
     */
    fun setVpnInterfaceIp(ip: String?) {
        if (vpnInterfaceIp != ip) {
            vpnInterfaceIp = ip
            // Clear socket pool to force recreation with new VPN binding
            socketPool.values.forEach { pooledSocket ->
                try {
                    pooledSocket.safeSocket.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
            socketPool.clear()
            Log.i(TAG, "VPN interface IP updated: $ip (socket pool cleared)")
        }
    }
    
    /**
     * Clear all sockets from pool
     */
    fun clear() {
        socketPool.values.forEach { pooledSocket ->
            try {
                pooledSocket.safeSocket.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
        socketPool.clear()
    }
    
    /**
     * Get pool statistics
     */
    fun getStats(): String {
        return "Socket Pool: ${socketPool.size} active sockets"
    }
}

