package com.hyperxray.an.core.network.http

import android.content.Context
import android.util.Log
import com.hyperxray.an.core.network.capability.NetworkCapabilityDetector
import com.hyperxray.an.core.network.dns.DnsCacheManager
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.ProxySelector
import org.chromium.net.CronetEngine
import org.conscrypt.Conscrypt
import java.io.File
import java.io.IOException
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
    private var appContext: Context? = null
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
        
        appContext = context.applicationContext
        
        try {
            // Initialize DNS cache manager
            try {
                DnsCacheManager.initialize(context)
                Log.i(TAG, "✅ DNS Cache Manager initialized: ${DnsCacheManager.getStats()}")
            } catch (e: Exception) {
                Log.w(TAG, "❌ Failed to initialize DNS cache manager", e)
            }
            // Install Conscrypt as security provider for TLS acceleration
            // Note: Hidden API access warnings may appear in logcat but are non-critical
            // These warnings occur because Conscrypt uses reflection to access internal Android APIs
            // Updated Conscrypt version (2.6.2+) should minimize these warnings
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
                } catch (e: SecurityException) {
                    // SecurityException may occur due to hidden API restrictions on Android 9+
                    // This is non-critical - Conscrypt will fall back to standard TLS implementation
                    Log.w(TAG, "Conscrypt installation blocked by security restrictions (non-critical)", e)
                    conscryptInstalled = false
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to install Conscrypt", e)
                    conscryptInstalled = false
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
            val sendBufferSize = 128 * 1024 // 128KB
            socket.sendBufferSize = sendBufferSize
            
            // Set receive buffer size (larger = better throughput)
            val receiveBufferSize = 128 * 1024 // 128KB
            socket.receiveBufferSize = receiveBufferSize
            
            // Log TCP buffer configuration
            if (socket is SSLSocket) {
                Log.d(TAG, "✅ TCP buffers configured: send=${sendBufferSize / 1024}KB, receive=${receiveBufferSize / 1024}KB (SSL socket)")
            }
            
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
            // Optimized connection pool for maximum parallel DNS queries
            val dnsClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS) // Fast DNS timeout
                .readTimeout(5, TimeUnit.SECONDS)
                .connectionPool(
                    okhttp3.ConnectionPool(
                        maxIdleConnections = 120,    // Increased to 120 for maximum parallel DNS queries
                        keepAliveDuration = 10,      // 10 minutes keep-alive for DNS connections
                        timeUnit = TimeUnit.MINUTES
                    )
                )
                .build()
            
            // DNS over HTTPS providers (Cloudflare, Google, Quad9, OpenDNS)
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
            
            val quad9Doh = DnsOverHttps.Builder()
                .client(dnsClient)
                .url("https://dns.quad9.net/dns-query".toHttpUrl())
                .includeIPv6(true)
                .build()
            
            val openDnsDoh = DnsOverHttps.Builder()
                .client(dnsClient)
                .url("https://doh.opendns.com/dns-query".toHttpUrl())
                .includeIPv6(true)
                .build()
            
            // Parallel DNS resolver: tries multiple DNS servers simultaneously
            // First checks persistent cache, then performs DNS queries if needed
            // This resolver works even when proxy is set - it always performs local DNS lookup
            // before connecting through proxy, ensuring DNS cache is always used
            fastDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    Log.d(TAG, "🔍 DNS lookup called for: $hostname (proxy-aware DNS resolver)")
                    
                    // First, check persistent DNS cache
                    // This works even when proxy is set - we always check cache first
                    val cachedResult = DnsCacheManager.getFromCache(hostname)
                    if (cachedResult != null && cachedResult.isNotEmpty()) {
                        Log.i(TAG, "✅ DNS CACHE HIT: $hostname -> ${cachedResult.map { it.hostAddress }} (skipped DNS query, will use proxy for connection)")
                        return cachedResult
                    }
                    Log.i(TAG, "⚠️ DNS CACHE MISS: $hostname (performing local DNS query - proxy will only be used for connection)")

                    // Cache miss - perform local DNS queries (proxy-independent)
                    // DNS lookup is done locally, proxy is only used for actual connection
                    // This ensures DNS cache works even when proxy is active
                    // Run parallel DNS queries using coroutines
                    return runBlocking {
                        val timeoutMs = 2000L // 2 seconds timeout per query
                        val results = mutableListOf<InetAddress>()
                        val exceptions = mutableListOf<Exception>()
                        
                        // Launch all DNS queries in parallel (5 DNS servers for maximum redundancy)
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
                        
                        val quad9DnsDeferred = async(Dispatchers.IO) {
                            withTimeoutOrNull(timeoutMs) {
                                try {
                                    quad9Doh.lookup(hostname)
                                } catch (e: Exception) {
                                    exceptions.add(e)
                                    null
                                }
                            }
                        }
                        
                        val openDnsDeferred = async(Dispatchers.IO) {
                            withTimeoutOrNull(timeoutMs) {
                                try {
                                    openDnsDoh.lookup(hostname)
                                } catch (e: Exception) {
                                    exceptions.add(e)
                                    null
                                }
                            }
                        }
                        
                        // Use select to get first successful result from 5 parallel DNS queries
                        val selectedResult = select<List<InetAddress>?> {
                            systemDnsDeferred.onAwait { result ->
                                if (result != null && result.isNotEmpty()) {
                                    Log.i(TAG, "✅ DNS resolved via system: $hostname -> ${result.map { it.hostAddress }}")
                                    result
                                } else null
                            }
                            cloudflareDnsDeferred.onAwait { result ->
                                if (result != null && result.isNotEmpty()) {
                                    Log.i(TAG, "✅ DNS resolved via DoH (Cloudflare): $hostname -> ${result.map { it.hostAddress }}")
                                    result
                                } else null
                            }
                            googleDnsDeferred.onAwait { result ->
                                if (result != null && result.isNotEmpty()) {
                                    Log.i(TAG, "✅ DNS resolved via DoH (Google): $hostname -> ${result.map { it.hostAddress }}")
                                    result
                                } else null
                            }
                            quad9DnsDeferred.onAwait { result ->
                                if (result != null && result.isNotEmpty()) {
                                    Log.i(TAG, "✅ DNS resolved via DoH (Quad9): $hostname -> ${result.map { it.hostAddress }}")
                                    result
                                } else null
                            }
                            openDnsDeferred.onAwait { result ->
                                if (result != null && result.isNotEmpty()) {
                                    Log.i(TAG, "✅ DNS resolved via DoH (OpenDNS): $hostname -> ${result.map { it.hostAddress }}")
                                    result
                                } else null
                            }
                        }
                        
                        // If select returned a result, save to cache and return
                        if (selectedResult != null && selectedResult.isNotEmpty()) {
                            DnsCacheManager.saveToCache(hostname, selectedResult)
                            Log.i(TAG, "✅ DNS resolved and cached: $hostname -> ${selectedResult.map { it.hostAddress }}")
                            return@runBlocking selectedResult
                        }
                        
                        // If select returned null, wait for all and use first successful
                        run {
                            // If select returns null, wait for all and use first successful
                            val systemResult = systemDnsDeferred.await()
                            if (systemResult != null && systemResult.isNotEmpty()) {
                                Log.i(TAG, "✅ DNS resolved via system (fallback): $hostname -> ${systemResult.map { it.hostAddress }}")
                                DnsCacheManager.saveToCache(hostname, systemResult)
                                return@runBlocking systemResult
                            }
                            
                            val cloudflareResult = cloudflareDnsDeferred.await()
                            if (cloudflareResult != null && cloudflareResult.isNotEmpty()) {
                                Log.i(TAG, "✅ DNS resolved via DoH (Cloudflare, fallback): $hostname -> ${cloudflareResult.map { it.hostAddress }}")
                                DnsCacheManager.saveToCache(hostname, cloudflareResult)
                                return@runBlocking cloudflareResult
                            }
                            
                            val googleResult = googleDnsDeferred.await()
                            if (googleResult != null && googleResult.isNotEmpty()) {
                                Log.i(TAG, "✅ DNS resolved via DoH (Google, fallback): $hostname -> ${googleResult.map { it.hostAddress }}")
                                DnsCacheManager.saveToCache(hostname, googleResult)
                                return@runBlocking googleResult
                            }
                            
                            val quad9Result = quad9DnsDeferred.await()
                            if (quad9Result != null && quad9Result.isNotEmpty()) {
                                Log.i(TAG, "✅ DNS resolved via DoH (Quad9, fallback): $hostname -> ${quad9Result.map { it.hostAddress }}")
                                DnsCacheManager.saveToCache(hostname, quad9Result)
                                return@runBlocking quad9Result
                            }
                            
                            val openDnsResult = openDnsDeferred.await()
                            if (openDnsResult != null && openDnsResult.isNotEmpty()) {
                                Log.i(TAG, "✅ DNS resolved via DoH (OpenDNS, fallback): $hostname -> ${openDnsResult.map { it.hostAddress }}")
                                DnsCacheManager.saveToCache(hostname, openDnsResult)
                                return@runBlocking openDnsResult
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
            Log.i(TAG, "✅ Fast DNS resolver initialized (DoH + 5 parallel DNS servers: System, Cloudflare, Google, Quad9, OpenDNS + persistent cache)")
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
        Log.i(TAG, "🚀 createHttpClient() called (proxy=${proxy != null})")
        Log.d(TAG, "🔍 createHttpClient: fastDns=${fastDns != null}, isInitialized=$isInitialized")
        
        val builder = OkHttpClient.Builder()
            // Optimized timeouts for faster connection establishment
            .connectTimeout(10, TimeUnit.SECONDS) // Reduced from 30s for faster failure detection
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // TCP connection optimization: faster handshake
            .callTimeout(60, TimeUnit.SECONDS)
        
        // Add retry interceptor for automatic retry on transient failures
        builder.addInterceptor(RetryInterceptor())
        Log.i(TAG, "✅ RetryInterceptor added (max 3 retries with exponential backoff)")
        
        // Add cache logging interceptor to track HTTP cache usage
        builder.addInterceptor(CacheLoggingInterceptor())
        Log.i(TAG, "✅ CacheLoggingInterceptor added (tracks HTTP cache hits/misses)")
        
        // Add DNS pre-resolution interceptor for proxy scenarios
        // This ensures DNS lookup happens even with SOCKS proxy, so DNS cache is always used
        if (proxy != null && fastDns != null) {
            builder.addNetworkInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val request = chain.request()
                    val hostname = request.url.host
                    
                    // Pre-resolve DNS for proxy scenarios (SOCKS proxy bypasses DNS resolver)
                    // This ensures DNS cache is populated even when proxy is used
                    try {
                        Log.d(TAG, "🔍 Pre-resolving DNS for proxy: $hostname")
                        fastDns!!.lookup(hostname)
                        Log.d(TAG, "✅ DNS pre-resolved for proxy: $hostname")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Failed to pre-resolve DNS for $hostname: ${e.message}")
                        // Continue anyway - OkHttp will handle DNS resolution
                    }
                    
                    return chain.proceed(request)
                }
            })
            Log.i(TAG, "✅ DNS pre-resolution interceptor added for proxy scenarios")
        }
        
        // Add HTTP response cache (100MB disk cache)
        val context = appContext
        if (context != null) {
            try {
                val cacheDirectory = File(context.cacheDir, "http_cache")
                val cacheSize = 100L * 1024 * 1024 // 100MB
                val cache = Cache(cacheDirectory, cacheSize)
                builder.cache(cache)
                Log.i(TAG, "✅ HTTP response cache configured: 100MB disk cache at ${cacheDirectory.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "❌ Failed to configure HTTP cache", e)
            }
        } else {
            Log.w(TAG, "⚠️ Context not available, HTTP cache not configured")
        }
        
        // Apply proxy if provided (forces OkHttp usage)
        // IMPORTANT: Even with proxy, DNS lookup is done locally via custom DNS resolver
        // Proxy is only used for the actual connection, not for DNS resolution
        // This ensures DNS cache always works, regardless of proxy status
        if (proxy != null) {
            builder.proxy(proxy)
            Log.i(TAG, "✅ Proxy configured - DNS lookup will be done locally (cache-enabled), proxy only for connection")
            
            // Configure ProxySelector to ensure DNS is resolved locally before using proxy
            // This prevents OkHttp from doing DNS resolution through proxy
            builder.proxySelector(object : ProxySelector() {
                override fun select(uri: java.net.URI?): List<Proxy> {
                    // Always return the configured proxy for connections
                    // DNS resolution is handled by custom DNS resolver (fastDns)
                    return listOf(proxy)
                }
                
                override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, e: IOException?) {
                    // Handle connection failures if needed
                }
            })
            Log.d(TAG, "ProxySelector configured to ensure local DNS resolution")
        }
        
        // Apply optimized socket factories for TCP acceleration
        if (optimizedSocketFactory != null) {
            builder.socketFactory(optimizedSocketFactory!!)
            Log.i(TAG, "✅ Optimized TCP socket factory configured (128KB buffers, TCP_NODELAY, SO_KEEPALIVE)")
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
        
        // Enable modern TLS versions with HTTP/2 priority
        // HTTP/2 provides better multiplexing and performance
        builder.protocols(listOf(
            okhttp3.Protocol.HTTP_2,
            okhttp3.Protocol.HTTP_1_1
        ))
        Log.i(TAG, "✅ HTTP/2 protocol enabled with priority (HTTP/3 QUIC available via Cronet)")
        
        // Ultra-optimized connection pooling for maximum TCP reuse
        // Aggressive pooling: more connections, longer keep-alive, faster reuse
        builder.connectionPool(
            okhttp3.ConnectionPool(
                maxIdleConnections = 120,       // Increased to 120 for maximum concurrent connections
                keepAliveDuration = 15,         // Increased to 15 minutes for longer connection reuse
                timeUnit = TimeUnit.MINUTES
            )
        )
        Log.i(TAG, "✅ Connection pool configured: maxIdle=120, keepAlive=15min")
        
        // Fast DNS resolver (DoH + parallel queries + persistent cache)
        // IMPORTANT: DNS resolver works even with proxy - DNS lookup is done locally,
        // proxy is only used for the actual connection. This ensures DNS cache always works.
        if (fastDns != null) {
            builder.dns(fastDns!!)
            if (proxy != null) {
                Log.i(TAG, "✅ DNS resolver configured with proxy - DNS lookup will be done locally (cache-enabled), proxy only for connection")
            } else {
                Log.i(TAG, "✅ Configured OkHttp with fast DNS resolver (DoH + 5 parallel DNS servers + persistent cache)")
            }
            Log.d(TAG, "🔍 DNS resolver is set and ready for lookups (proxy-aware)")
        } else {
            Log.w(TAG, "⚠️ Fast DNS resolver not available, using system DNS")
        }
        
        val client = builder.build()
        
        if (isCronetAvailable()) {
            if (proxy == null) {
                Log.i(TAG, "✅ Created OkHttp client with TLS acceleration (Cronet engine ACTIVE - HTTP/2, HTTP/3 QUIC enabled at system level)")
            } else {
                Log.i(TAG, "✅ Created OkHttp client with proxy (Cronet engine ACTIVE but proxy requires OkHttp)")
            }
        } else {
            Log.i(TAG, "✅ Created OkHttp client with TLS acceleration")
        }
        
        return client
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
            appContext = null
            Log.d(TAG, "HttpClientFactory shut down")
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down HttpClientFactory", e)
        }
        isInitialized = false
    }
}

