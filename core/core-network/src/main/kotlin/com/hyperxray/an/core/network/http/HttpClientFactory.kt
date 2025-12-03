package com.hyperxray.an.core.network.http

import android.content.Context
import android.util.Log
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import org.conscrypt.Conscrypt
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

private const val TAG = "HttpClientFactory"

/**
 * Factory for creating OkHttp clients with Conscrypt TLS acceleration.
 */
object HttpClientFactory {
    
    private var appContext: Context? = null
    private var sslContext: SSLContext? = null
    private var trustManager: X509TrustManager? = null
    private var conscryptEnabled = false
    
    /**
     * Initialize with application context. Call once at app startup.
     */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        setupConscrypt()
        Log.i(TAG, "Initialized (conscrypt=$conscryptEnabled)")
    }
    
    private fun setupConscrypt() {
        try {
            if (!Conscrypt.isAvailable()) return
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
            
            sslContext = SSLContext.getInstance("TLS", Conscrypt.newProvider()).apply {
                init(null, null, null)
            }
            
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as java.security.KeyStore?)
            trustManager = tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
            
            conscryptEnabled = true
        } catch (e: Exception) {
            Log.w(TAG, "Conscrypt setup failed", e)
        }
    }
    
    /**
     * Create an OkHttp client with retry, cache, and TLS acceleration.
     */
    fun create(
        proxy: Proxy? = null,
        connectTimeoutSec: Long = 30,
        readTimeoutSec: Long = 60,
        writeTimeoutSec: Long = 30
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
            .proxy(proxy ?: Proxy.NO_PROXY)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .addInterceptor(RetryInterceptor())
        
        // Cache
        appContext?.let { ctx ->
            try {
                val cacheDir = File(ctx.cacheDir, "okhttp")
                builder.cache(Cache(cacheDir, 50L * 1024 * 1024))
            } catch (e: Exception) {
                Log.w(TAG, "Cache setup failed", e)
            }
        }
        
        // Conscrypt TLS
        if (conscryptEnabled && sslContext != null && trustManager != null) {
            builder.sslSocketFactory(sslContext!!.socketFactory, trustManager!!)
        }
        
        return builder.build()
    }
    
    /**
     * Create a simple client without retry or cache.
     */
    fun createSimple(
        proxy: Proxy? = null,
        timeoutSec: Long = 30
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .proxy(proxy ?: Proxy.NO_PROXY)
            .build()
    }
    
    fun isConscryptEnabled() = conscryptEnabled
    
    fun shutdown() {
        appContext = null
        sslContext = null
        trustManager = null
        conscryptEnabled = false
    }
}

/**
 * Retry interceptor with exponential backoff.
 */
private class RetryInterceptor(
    private val maxRetries: Int = 3
) : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var lastException: IOException? = null
        
        repeat(maxRetries) { attempt ->
            try {
                val response = chain.proceed(request)
                if (response.isSuccessful || !isRetryable(response.code)) {
                    return response
                }
                response.close()
                Log.d(TAG, "Retry ${attempt + 1}/$maxRetries: ${response.code}")
            } catch (e: IOException) {
                if (!isRetryable(e)) throw e
                lastException = e
                Log.d(TAG, "Retry ${attempt + 1}/$maxRetries: ${e.javaClass.simpleName}")
            }
            
            // Exponential backoff: 1s, 2s, 4s
            Thread.sleep((1L shl attempt) * 1000)
        }
        
        throw lastException ?: IOException("Request failed after $maxRetries retries")
    }
    
    private fun isRetryable(code: Int) = code in 500..599 || code == 408
    
    private fun isRetryable(e: IOException) = e is SocketTimeoutException || e is ConnectException
}
