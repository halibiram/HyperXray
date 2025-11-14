package com.hyperxray.an.core.network.http

import android.content.Context
import android.util.Log
import com.hyperxray.an.core.network.capability.NetworkCapabilityDetector
import com.hyperxray.an.core.network.capability.shouldPreferCronet
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import org.conscrypt.Conscrypt
import java.net.Proxy
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
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
            if (shouldPreferCronet()) {
                try {
                    val builder = CronetEngine.Builder(context)
                        .enableHttp2(true)
                        .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 10 * 1024 * 1024)
                    
                    // Enable HTTP/3 if supported
                    if (NetworkCapabilityDetector.supportsHttp3()) {
                        builder.enableQuic(true)
                        Log.d(TAG, "HTTP/3 (QUIC) capability detected")
                    }
                    
                    // Enable ALPN if supported
                    if (NetworkCapabilityDetector.supportsAlpn()) {
                        builder.enableAlpn(true)
                        Log.d(TAG, "ALPN capability detected")
                    }
                    
                    cronetEngine = builder.build()
                    Log.d(TAG, "Cronet engine initialized (ready for future HTTP/3 integration)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to initialize Cronet, will use OkHttp", e)
                    cronetEngine = null
                }
            } else {
                Log.d(TAG, "Device does not meet Cronet requirements, using OkHttp with TLS acceleration")
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
     * Create an HTTP client with automatic backend selection.
     * Uses OkHttp with Conscrypt TLS acceleration when available.
     * 
     * @param proxy Optional proxy configuration
     * @return OkHttpClient with performance optimizations applied
     */
    fun createHttpClient(proxy: Proxy? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        
        // Apply proxy if provided
        if (proxy != null) {
            builder.proxy(proxy)
        }
        
        // Apply Conscrypt TLS acceleration if available
        if (conscryptInstalled && conscryptSslContext != null) {
            try {
                // Get default trust manager from system
                val trustManagerFactory = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                trustManagerFactory.init(null)
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
        
        // Enable connection pooling and HTTP/2 multiplexing
        builder.connectionPool(okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES))
        
        if (isCronetAvailable()) {
            Log.d(TAG, "Created OkHttp client with TLS acceleration (Cronet available for future use)")
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
            Log.d(TAG, "HttpClientFactory shut down")
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down HttpClientFactory", e)
        }
        isInitialized = false
    }
}

