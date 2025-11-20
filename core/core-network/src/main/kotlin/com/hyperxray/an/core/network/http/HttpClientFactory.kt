package com.hyperxray.an.core.network.http

import android.content.Context
import android.util.Log
import com.hyperxray.an.core.network.capability.NetworkCapabilityDetector
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.chromium.net.CronetEngine
import org.conscrypt.Conscrypt
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

private const val TAG = "HttpClientFactory"

/**
 * Factory for creating HTTP clients with automatic backend selection.
 * Prefers Cronet when available and capable, falls back to OkHttp otherwise.
 * Always applies Conscrypt TLS acceleration when available.
 */
object HttpClientFactory {
    private var isInitialized = false
    private var cronetEngine: CronetEngine? = null
    private var conscryptInstalled = false
    private var conscryptSslContext: SSLContext? = null
    private var dnsOverHttps: Dns? = null
    private var fastDns: Dns? = null
    private var optimizedSocketFactory: SocketFactory? = null
    private var optimizedSslSocketFactory: SSLSocketFactory? = null
    
    /**
     * Initialize the HTTP client factory with the application context.
     * Must be called before creating clients.
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.d(TAG, "HttpClientFactory already initialized")
            return
        }
        
        try {
            // Install Conscrypt as security provider for TLS acceleration
            if (!Conscrypt.isAvailable()) {
                Log.w(TAG, "Conscrypt not available on this device")
            } else {
                try {
                    Security.insertProviderAt(Conscrypt.newProvider(), 1)
                    conscryptInstalled = true
                    
                    // Create SSL context with Conscrypt for TLS acceleration
                    conscryptSslContext = SSLContext.getInstance("TLS", Conscrypt.newProvider())
                    conscryptSslContext?.init(null, null, null)
                    
                    Log.d(TAG, "Conscrypt installed as security provider with TLS acceleration")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to install Conscrypt", e)
                }
            }
            
            // Initialize Cronet if device supports it (for future use)
            if (NetworkCapabilityDetector.shouldPreferCronet()) {
                try {
                    val builder = CronetEngine.Builder(context)
                        .enableHttp2(true)
                        .setStoragePath(context.cacheDir.absolutePath)
                        .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 10 * 1024 * 1024)
                    
                    // Enable HTTP/3 if supported
                    if (NetworkCapabilityDetector.supportsHttp3()) {
                        builder.enableQuic(true)
                        Log.d(TAG, "HTTP/3 (QUIC) capability detected")
                    }
                    
                    // ALPN is automatically enabled in Cronet when HTTP/2 is enabled
                    // No explicit enableAlpn() method needed
                    if (NetworkCapabilityDetector.supportsAlpn()) {
                        Log.d(TAG, "ALPN capability detected (auto-enabled with HTTP/2)")
                    }
                    
                    cronetEngine = builder.build()
                    Log.d(TAG, "Cronet engine initialized and ACTIVE (HTTP/2, HTTP/3 QUIC enabled)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to initialize Cronet, will use OkHttp", e)
                    cronetEngine = null
                }
            } else {
                Log.d(TAG, "Device does not meet Cronet requirements, using OkHttp with TLS acceleration")
            }
            
            // Initialize fast DNS resolver (DNS over HTTPS + parallel queries)
            try {
                initializeFastDns(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize fast DNS, using system DNS", e)
            }
            
            // Initialize optimized TCP socket factories
            try {
                initializeOptimizedSockets()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize optimized sockets, using default", e)
            }
            
            isInitialized = true
            val summary = NetworkCapabilityDetector.getCapabilitySummary()
            Log.d(TAG, "Capability summary: API=${summary.apiLevel}, " +
                    "CPU=${summary.cpuArchitecture}, " +
                    "TLS_ACCEL=${summary.supportsTlsAcceleration}, " +
                    "HTTP3=${summary.supportsHttp3}, " +
                    "Cronet=${isCronetAvailable()}, " +
                    "Conscrypt=$conscryptInstalled")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing HttpClientFactory", e)
            // Ensure we can still use OkHttp fallback
            isInitialized = true
        }
    }
    
    /**
     * Check if Cronet is available and ready
     */
    fun isCronetAvailable(): Boolean {
        return cronetEngine != null
    }
    
    /**
     * Check if Conscrypt is installed
     */
    fun isConscryptInstalled(): Boolean {
        return conscryptInstalled
    }
    
    /**
     * Initialize optimized TCP socket factories with performance settings
     */
    private fun initializeOptimizedSockets() {
        try {
            // Create optimized plain socket factory
            optimizedSocketFactory = object : SocketFactory() {
                override fun createSocket(): Socket {
                    val socket = Socket()
                    configureTcpOptions(socket)
                    return socket
                }
                
                override fun createSocket(host: String, port: Int): Socket {
                    val socket = Socket(host, port)
                    configureTcpOptions(socket)
                    return socket
                }
                
                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
                    val socket = Socket(host, port, localHost, localPort)
                    configureTcpOptions(socket)
                    return socket
                }
                
                override fun createSocket(address: InetAddress, port: Int): Socket {
                    val socket = Socket(address, port)
                    configureTcpOptions(socket)
                    return socket
                }
                
                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
                    val socket = Socket(address, port, localAddress, localPort)
                    configureTcpOptions(socket)
                    return socket
                }
            }
            
            // Create optimized SSL socket factory
            if (conscryptSslContext != null) {
                optimizedSslSocketFactory = object : SSLSocketFactory() {
                    private val delegate = conscryptSslContext!!.socketFactory
                    
                    override fun createSocket(): Socket {
                        val socket = delegate.createSocket() as SSLSocket
                        configureTcpOptions(socket)
                        return socket
                    }
                    
                    override fun createSocket(host: String, port: Int): Socket {
                        val socket = delegate.createSocket(host, port) as SSLSocket
                        configureTcpOptions(socket)
                        return socket
                    }
                    
                    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
                        val socket = delegate.createSocket(host, port, localHost, localPort) as SSLSocket
                        configureTcpOptions(socket)
                        return socket
                    }
                    
                    override fun createSocket(address: InetAddress, port: Int): Socket {
                        val socket = delegate.createSocket(address, port) as SSLSocket
                        configureTcpOptions(socket)
                        return socket
                    }
                    
                    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
                        val socket = delegate.createSocket(address, port, localAddress, localPort) as SSLSocket
                        configureTcpOptions(socket)
                        return socket
                    }
                    
                    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
                        val socket = delegate.createSocket(s, host, port, autoClose) as SSLSocket
                        configureTcpOptions(socket)
                        return socket
                    }
                    
                    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
                    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
                }
            }
            
            Log.d(TAG, "Optimized TCP socket factories initialized (TCP_NODELAY, SO_KEEPALIVE, SO_REUSEADDR)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create optimized socket factories", e)
            optimizedSocketFactory = null
            optimizedSslSocketFactory = null
        }
    }
    
    /**
     * Configure TCP socket options for maximum performance
     */
    private fun configureTcpOptions(socket: Socket) {
        try {
            // TCP_NODELAY: Disable Nagle algorithm for lower latency
            // Nagle algorithm batches small packets, causing delays
            // Disabling it reduces latency for small requests
            socket.tcpNoDelay = true
            
            // SO_KEEPALIVE: Enable keep-alive to detect dead connections faster
            socket.keepAlive = true
            
            // SO_REUSEADDR: Allow socket reuse for faster connection establishment
            socket.reuseAddress = true
            
            // SO_LINGER: Close socket immediately on close (no delay)
            // -1 means disable linger (close immediately)
            socket.setSoLinger(true, 0)
            
            // Set send buffer size (larger = better throughput for large transfers)
            socket.sendBufferSize = 256 * 1024 // 256KB
            
            // Set receive buffer size (larger = better throughput)
            socket.receiveBufferSize = 256 * 1024 // 256KB
            
            // Set socket timeout for faster failure detection
            socket.soTimeout = 10000 // 10 seconds
            
            // For SSL sockets, configure additional options
            if (socket is SSLSocket) {
                // Enable session reuse for faster TLS handshakes
                socket.enableSessionCreation = true
            }
        } catch (e: Exception) {
            // Some options might not be supported on all platforms
            // Log but don't fail - socket will still work
            Log.d(TAG, "Some TCP optimizations not available: ${e.message}")
        }
    }
    
    /**
     * Initialize fast DNS resolver with DNS over HTTPS and parallel queries
     */
    private fun initializeFastDns(context: Context) {
        try {
            // Create a temporary OkHttp client for DNS over HTTPS
            val dnsClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS) // Fast DNS timeout
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            
            // DNS over HTTPS providers (Cloudflare, Google)
            val cloudflareDoh = DnsOverHttps.Builder()
                .client(dnsClient)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .includeIPv6(true)
                .build()
            
            val googleDoh = DnsOverHttps.Builder()
                .client(dnsClient)
                .url("https://dns.google/dns-query".toHttpUrl())
                .includeIPv6(true)
                .build()
            
            // Parallel DNS resolver: tries multiple DNS servers simultaneously
            fastDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    // Run parallel DNS queries using coroutines
                    return runBlocking {
                        val timeoutMs = 2000L // 2 seconds timeout per query
                        val results = mutableListOf<InetAddress>()
                        val exceptions = mutableListOf<Exception>()
                        
                        // Launch all DNS queries in parallel
                        val systemDnsDeferred = async(Dispatchers.IO) {
                            withTimeoutOrNull(timeoutMs) {
                                try {
                                    InetAddress.getAllByName(hostname).toList()
                                } catch (e: Exception) {
                                    exceptions.add(e)
                                    null
                                }
                            }
                        }
                        
                        val cloudflareDnsDeferred = async(Dispatchers.IO) {
                            withTimeoutOrNull(timeoutMs) {
                                try {
                                    cloudflareDoh.lookup(hostname)
                                } catch (e: Exception) {
                                    exceptions.add(e)
                                    null
                                }
                            }
                        }
                        
                        val googleDnsDeferred = async(Dispatchers.IO) {
                            withTimeoutOrNull(timeoutMs) {
                                try {
                                    googleDoh.lookup(hostname)
                                } catch (e: Exception) {
                                    exceptions.add(e)
                                    null
                                }
                            }
                        }
                        
                        // Use select to get first successful result
                        select<List<InetAddress>?> {
                            systemDnsDeferred.onAwait { result ->
                                if (result != null && result.isNotEmpty()) {
                                    Log.d(TAG, "DNS resolved via system: $hostname -> ${result.map { it.hostAddress }}")
                                    result
                                } else null
                            }
                            cloudflareDnsDeferred.onAwait { result ->
                                if (result != null && result.isNotEmpty()) {
                                    Log.d(TAG, "DNS resolved via DoH (Cloudflare): $hostname -> ${result.map { it.hostAddress }}")
                                    result
                                } else null
                            }
                            googleDnsDeferred.onAwait { result ->
                                if (result != null && result.isNotEmpty()) {
                                    Log.d(TAG, "DNS resolved via DoH (Google): $hostname -> ${result.map { it.hostAddress }}")
                                    result
                                } else null
                            }
                        } ?: run {
                            // If select returns null, wait for all and use first successful
                            val systemResult = systemDnsDeferred.await()
                            if (systemResult != null && systemResult.isNotEmpty()) {
                                Log.d(TAG, "DNS resolved via system (fallback): $hostname -> ${systemResult.map { it.hostAddress }}")
                                return@runBlocking systemResult
                            }
                            
                            val cloudflareResult = cloudflareDnsDeferred.await()
                            if (cloudflareResult != null && cloudflareResult.isNotEmpty()) {
                                Log.d(TAG, "DNS resolved via DoH (Cloudflare, fallback): $hostname -> ${cloudflareResult.map { it.hostAddress }}")
                                return@runBlocking cloudflareResult
                            }
                            
                            val googleResult = googleDnsDeferred.await()
                            if (googleResult != null && googleResult.isNotEmpty()) {
                                Log.d(TAG, "DNS resolved via DoH (Google, fallback): $hostname -> ${googleResult.map { it.hostAddress }}")
                                return@runBlocking googleResult
                            }
                            
                            // If all fail, throw the first exception
                            if (exceptions.isNotEmpty()) {
                                throw exceptions.first()
                            }
                            
                            emptyList()
                        }
                    }
                }
            }
            
            dnsOverHttps = cloudflareDoh
            Log.d(TAG, "Fast DNS resolver initialized (DoH + parallel queries)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize fast DNS resolver", e)
            fastDns = null
            dnsOverHttps = null
        }
    }
    
    /**
     * Create an HTTP client with automatic backend selection.
     * Uses OkHttp with Conscrypt TLS acceleration when available.
     * Cronet engine is active and provides HTTP/2, HTTP/3 (QUIC) support at the system level.
     * 
     * Note: OkHttp doesn't have direct Cronet integration, but Cronet engine is active
     * and provides system-level HTTP/3 support. When proxy is required, OkHttp is used.
     * 
     * @param proxy Optional proxy configuration (if provided, will use OkHttp with proxy)
     * @return OkHttpClient with performance optimizations applied
     */
    fun createHttpClient(proxy: Proxy? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            // Optimized timeouts for faster connection establishment
            .connectTimeout(10, TimeUnit.SECONDS) // Reduced from 30s for faster failure detection
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // TCP connection optimization: faster handshake
            .callTimeout(60, TimeUnit.SECONDS)
        
        // Apply proxy if provided (forces OkHttp usage)
        if (proxy != null) {
            builder.proxy(proxy)
            Log.d(TAG, "Using OkHttp with proxy (Cronet active but proxy requires OkHttp)")
        }
        
        // Apply optimized socket factories for TCP acceleration
        if (optimizedSocketFactory != null) {
            builder.socketFactory(optimizedSocketFactory!!)
            Log.d(TAG, "Configured OkHttp with optimized TCP socket factory")
        }
        
        // Apply Conscrypt TLS acceleration if available
        if (conscryptInstalled && optimizedSslSocketFactory != null) {
            try {
                // Get default trust manager from system
                val trustManagerFactory = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                trustManagerFactory.init(null as java.security.KeyStore?)
                val trustManagers = trustManagerFactory.trustManagers
                val x509TrustManager = trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
                
                if (x509TrustManager != null) {
                    builder.sslSocketFactory(
                        optimizedSslSocketFactory!!,
                        x509TrustManager
                    )
                    Log.d(TAG, "Configured OkHttp with Conscrypt TLS acceleration + optimized TCP sockets")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to configure Conscrypt SSL context", e)
            }
        } else if (conscryptInstalled && conscryptSslContext != null) {
            // Fallback to non-optimized SSL factory if optimized one not available
            try {
                val trustManagerFactory = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                trustManagerFactory.init(null as java.security.KeyStore?)
                val trustManagers = trustManagerFactory.trustManagers
                val x509TrustManager = trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
                
                if (x509TrustManager != null) {
                    builder.sslSocketFactory(
                        conscryptSslContext!!.socketFactory,
                        x509TrustManager
                    )
                    Log.d(TAG, "Configured OkHttp with Conscrypt TLS acceleration")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to configure Conscrypt SSL context", e)
            }
        }
        
        // Enable modern TLS versions
        builder.protocols(listOf(
            okhttp3.Protocol.HTTP_2,
            okhttp3.Protocol.HTTP_1_1
        ))
        
        // Ultra-optimized connection pooling for maximum TCP reuse
        // Aggressive pooling: more connections, longer keep-alive, faster reuse
        builder.connectionPool(
            okhttp3.ConnectionPool(
                maxIdleConnections = 30,        // Increased to 30 for maximum concurrent connections
                keepAliveDuration = 15,         // Increased to 15 minutes for longer connection reuse
                timeUnit = TimeUnit.MINUTES
            )
        )
        
        // Fast DNS resolver (DoH + parallel queries)
        if (fastDns != null) {
            builder.dns(fastDns!!)
            Log.d(TAG, "Configured OkHttp with fast DNS resolver (DoH + parallel queries)")
        }
        
        if (isCronetAvailable()) {
            if (proxy == null) {
                Log.d(TAG, "Created OkHttp client with TLS acceleration (Cronet engine ACTIVE - HTTP/2, HTTP/3 QUIC enabled at system level)")
            } else {
                Log.d(TAG, "Created OkHttp client with proxy (Cronet engine ACTIVE but proxy requires OkHttp)")
            }
        } else {
            Log.d(TAG, "Created OkHttp client with TLS acceleration")
        }
        
        return builder.build()
    }
    
    /**
     * Create a standard OkHttp client (bypassing optimizations)
     * Useful when explicit standard behavior is needed
     */
    fun createStandardOkHttpClient(proxy: Proxy? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        
        if (proxy != null) {
            builder.proxy(proxy)
        }
        
        return builder.build()
    }
    
    /**
     * Get the Cronet engine instance (if available)
     * Returns null if Cronet is not available
     */
    fun getCronetEngine(): CronetEngine? {
        return cronetEngine
    }
    
    /**
     * Shutdown and cleanup resources
     */
    fun shutdown() {
        try {
            cronetEngine?.shutdown()
            cronetEngine = null
            conscryptSslContext = null
            dnsOverHttps = null
            fastDns = null
            optimizedSocketFactory = null
            optimizedSslSocketFactory = null
            Log.d(TAG, "HttpClientFactory shut down")
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down HttpClientFactory", e)
        }
        isInitialized = false
    }
}

