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
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

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
 * 
 * IMPROVEMENTS:
 * - Atomic cleanup using iterator.remove() to avoid ConcurrentModificationException
 * - Better binding error handling with fallback to default binding
 * - Thread-safe operations using read-write locks
 */
class DnsSocketPool(
    private val scope: CoroutineScope,
    private var vpnInterfaceIp: String? = null
) {
    // Thread-safe socket pool
    private val socketPool = ConcurrentHashMap<String, PooledSocket>()
    
    // Read-write lock for pool operations (allows concurrent reads, exclusive writes)
    private val poolLock = ReentrantReadWriteLock()
    
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
     * 
     * CRITICAL: Uses iterator.remove() for atomic cleanup to avoid ConcurrentModificationException
     * This is safe because ConcurrentHashMap.entrySet().iterator() supports remove()
     */
    private fun cleanupExpiredSockets() {
        val now = System.currentTimeMillis()
        val expiredKeys = mutableListOf<String>()
        
        // First pass: identify expired sockets (read-only, thread-safe)
        poolLock.read {
            socketPool.entries.forEach { (key, pooledSocket) ->
                val age = now - pooledSocket.lastUsed.get()
                if (age > SOCKET_POOL_TIMEOUT_MS) {
                    expiredKeys.add(key)
                }
            }
        }
        
        // Second pass: remove expired sockets (write lock, atomic removal)
        if (expiredKeys.isNotEmpty()) {
            poolLock.write {
                expiredKeys.forEach { key ->
                    val pooledSocket = socketPool.remove(key)
                    if (pooledSocket != null) {
                        try {
                            pooledSocket.safeSocket.close()
                            Log.d(TAG, "🧹 Cleaned up expired socket for $key (age: ${now - pooledSocket.lastUsed.get()}ms)")
                        } catch (e: Exception) {
                            // Ignore close errors
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Get or create socket from pool
     */
    fun getPooledSocket(server: InetAddress, timeoutMs: Long): SafeSocket {
        val serverKey = server.hostAddress
        
        // Try to get existing socket (read lock for concurrent access)
        poolLock.read {
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
                    // Socket expired, VPN IP changed, or socket invalid - will be removed below
                }
            }
        }
        
        // Need to create new socket (write lock for exclusive access)
        return poolLock.write {
            // Double-check after acquiring write lock (another thread might have created it)
            val pooled = socketPool[serverKey]
            if (pooled != null) {
                val age = System.currentTimeMillis() - pooled.lastUsed.get()
                val vpnIpChanged = pooled.vpnInterfaceIp != vpnInterfaceIp
                val socketValid = pooled.safeSocket.isValid()
                
                if (age < SOCKET_POOL_TIMEOUT_MS && !vpnIpChanged && socketValid) {
                    pooled.lastUsed.set(System.currentTimeMillis())
                    return@write pooled.safeSocket
                } else {
                    // Remove expired/invalid socket
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
            Log.d(TAG, "✅ Created new pooled socket for $serverKey (pool size: ${socketPool.size})")
            safeSocket
        }
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
     * 
     * IMPROVEMENTS:
     * - Better error handling for VPN interface binding
     * - Graceful fallback to default binding if VPN interface is down
     * - Proper exception handling for all binding scenarios
     */
    private fun createSocket(timeoutMs: Long): DatagramSocket {
        return try {
            if (vpnInterfaceIp != null) {
                // Attempt to bind to VPN interface IP to route DNS queries through VPN
                try {
                    val socket = DatagramSocket(null)
                    socket.soTimeout = timeoutMs.toInt()
                    socket.reuseAddress = true
                    
                    // Attempt to bind to VPN interface
                    val bindAddress = InetSocketAddress(vpnInterfaceIp, 0)
                    socket.bind(bindAddress)
                    
                    Log.d(TAG, "✅ DNS socket bound to VPN interface: $vpnInterfaceIp")
                    return socket
                } catch (e: SocketException) {
                    // VPN interface might be down or invalid
                    Log.w(TAG, "Failed to bind socket to VPN interface $vpnInterfaceIp: ${e.message}, using default binding")
                    // Fall through to default binding
                } catch (e: IllegalArgumentException) {
                    // Invalid IP address format
                    Log.w(TAG, "Invalid VPN interface IP $vpnInterfaceIp: ${e.message}, using default binding")
                    // Fall through to default binding
                } catch (e: Exception) {
                    // Any other error
                    Log.w(TAG, "Unexpected error binding to VPN interface $vpnInterfaceIp: ${e.message}, using default binding")
                    // Fall through to default binding
                }
            }
            
            // Default binding (no VPN interface or fallback)
            DatagramSocket().apply {
                soTimeout = timeoutMs.toInt()
                try {
                    reuseAddress = true
                } catch (e: Exception) {
                    // Some platforms may not support this, ignore
                    Log.d(TAG, "reuseAddress not supported on this platform")
                }
                Log.d(TAG, "✅ DNS socket created with default binding")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating DNS socket: ${e.message}", e)
            // Last resort: create socket with minimal configuration
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
            poolLock.write {
                val oldIp = vpnInterfaceIp
                vpnInterfaceIp = ip
                
                // Clear socket pool to force recreation with new VPN binding
                val socketsToClose = socketPool.values.toList() // Create copy to avoid concurrent modification
                socketPool.clear()
                
                socketsToClose.forEach { pooledSocket ->
                    try {
                        pooledSocket.safeSocket.close()
                    } catch (e: Exception) {
                        // Ignore close errors
                    }
                }
                
                Log.i(TAG, "VPN interface IP updated: $oldIp → $ip (socket pool cleared, ${socketsToClose.size} sockets closed)")
            }
        }
    }
    
    /**
     * Clear all sockets from pool
     */
    fun clear() {
        poolLock.write {
            val socketsToClose = socketPool.values.toList()
            socketPool.clear()
            
            socketsToClose.forEach { pooledSocket ->
                try {
                    pooledSocket.safeSocket.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
            
            Log.d(TAG, "Socket pool cleared (${socketsToClose.size} sockets closed)")
        }
    }
    
    /**
     * Get pool statistics
     */
    fun getStats(): String {
        return poolLock.read {
            "Socket Pool: ${socketPool.size} active sockets"
        }
    }
}
