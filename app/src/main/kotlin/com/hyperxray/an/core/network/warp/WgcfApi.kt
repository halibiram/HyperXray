package com.hyperxray.an.core.network.warp

import android.util.Log
import com.hyperxray.an.core.network.warp.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

private const val TAG = "WgcfApi"

/**
 * WgcfApi: Direct port of wgcf API client.
 * 
 * Maintains 100% fidelity to wgcf's API calls, headers, and payloads.
 * Based on: https://github.com/ViRb3/wgcf/blob/master/cloudflare/api.go
 * 
 * API Base URL: https://api.cloudflareclient.com/v0a2408
 * Headers: User-Agent: okhttp/3.12.1, Content-Type: application/json
 */
object WgcfApi {
    
    // Base URL exactly as in wgcf
    // Based on: https://github.com/ViRb3/wgcf/blob/master/cloudflare/api.go
    private const val BASE_URL = "https://api.cloudflareclient.com"
    private const val API_VERSION = "v0a1922" // Exact version from wgcf (default in wgcf repo)
    
    // Headers exactly as in wgcf
    private const val USER_AGENT = "okhttp/3.12.1"
    private const val CONTENT_TYPE = "application/json"
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    
    // HTTP client configured for WARP API (TLS 1.2, HTTP/1.1 only)
    private val httpClient: OkHttpClient = createWarpHttpClient()
    
    /**
     * Create HTTP client specifically for WARP API.
     * WARP API rejects requests with HTTP/2 and requires TLS 1.2.
     * This matches wgcf's HTTP client configuration.
     */
    private fun createWarpHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
        
        // Force HTTP/1.1 only (WARP API doesn't accept HTTP/2)
        builder.protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        
        // Configure TLS 1.2 only (as per wgcf implementation)
        try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)
            
            val trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            )
            trustManagerFactory.init(null as java.security.KeyStore?)
            val trustManagers = trustManagerFactory.trustManagers
            val x509TrustManager = trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
            
            if (x509TrustManager != null) {
                val socketFactory = object : SSLSocketFactory() {
                    private val delegate = sslContext.socketFactory
                    
                    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
                    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
                    
                    override fun createSocket(): Socket = delegate.createSocket().apply {
                        if (this is SSLSocket) {
                            this.enabledProtocols = arrayOf("TLSv1.2")
                        }
                    }
                    
                    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
                        return delegate.createSocket(s, host, port, autoClose).apply {
                            if (this is SSLSocket) {
                                this.enabledProtocols = arrayOf("TLSv1.2")
                            }
                        }
                    }
                    
                    override fun createSocket(host: String, port: Int): Socket {
                        return delegate.createSocket(host, port).apply {
                            if (this is SSLSocket) {
                                this.enabledProtocols = arrayOf("TLSv1.2")
                            }
                        }
                    }
                    
                    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket {
                        return delegate.createSocket(host, port, localHost, localPort).apply {
                            if (this is SSLSocket) {
                                this.enabledProtocols = arrayOf("TLSv1.2")
                            }
                        }
                    }
                    
                    override fun createSocket(host: java.net.InetAddress, port: Int): Socket {
                        return delegate.createSocket(host, port).apply {
                            if (this is SSLSocket) {
                                this.enabledProtocols = arrayOf("TLSv1.2")
                            }
                        }
                    }
                    
                    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): Socket {
                        return delegate.createSocket(address, port, localAddress, localPort).apply {
                            if (this is SSLSocket) {
                                this.enabledProtocols = arrayOf("TLSv1.2")
                            }
                        }
                    }
                }
                
                builder.sslSocketFactory(socketFactory, x509TrustManager)
                Log.d(TAG, "✅ WARP HTTP client configured: TLS 1.2 only, HTTP/1.1 only")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure TLS 1.2 for WARP client, using default: ${e.message}")
        }
        
        return builder.build()
    }
    
    /**
     * Register a new device with Cloudflare WARP API.
     * 
     * Endpoint: POST /v0a2408/reg
     * 
     * Request body matches wgcf's RegisterRequest struct exactly:
     * {
     *   "install_id": "",
     *   "tos": "<timestamp>",
     *   "key": "<public_key>",
     *   "fcm_token": "",
     *   "type": "Android",
     *   "locale": "en_US"
     * }
     * 
     * @param publicKey Base64-encoded Curve25519 public key (44 chars)
     * @return WgcfRegistrationResponse with id, token, and config
     */
    suspend fun register(publicKey: String): WgcfRegistrationResponse = withContext(Dispatchers.IO) {
        val endpoint = "$BASE_URL/$API_VERSION/reg"
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        
        // Build request body exactly as wgcf does
        val requestBody = WgcfRegisterRequest(
            install_id = "",
            tos = timestamp,
            key = publicKey,
            fcm_token = "",
            type = "Android",
            locale = "en_US"
        )
        
        val jsonBody = json.encodeToString(WgcfRegisterRequest.serializer(), requestBody)
        
        Log.d(TAG, "📤 POST $endpoint")
        Log.d(TAG, "   Body: $jsonBody")
        
        val request = Request.Builder()
            .url(endpoint)
            .post(jsonBody.toRequestBody(CONTENT_TYPE.toMediaType()))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", CONTENT_TYPE)
            .build()
        
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            response.close()
            throw WgcfApiException("Registration failed: HTTP ${response.code} - $errorBody")
        }
        
        val responseBody = response.body?.string() ?: ""
        response.close()
        
        if (responseBody.isEmpty()) {
            throw WgcfApiException("Empty response body received")
        }
        
        Log.d(TAG, "📥 Registration response: ${responseBody.take(200)}...")
        
        try {
            json.decodeFromString<WgcfRegistrationResponse>(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse registration response: ${e.message}")
            Log.e(TAG, "Full response: $responseBody")
            throw WgcfApiException("Failed to parse response: ${e.message}", e)
        }
    }
    
    /**
     * Update device configuration (enable/disable device).
     * 
     * Endpoint: PATCH /v0a2408/reg/{id}
     * 
     * This is CRITICAL for enabling the device after registration or license binding.
     * wgcf calls this after registration and after license binding.
     * 
     * Request body:
     * {
     *   "active": true
     * }
     * 
     * @param id Device ID from registration
     * @param token Authentication token from registration
     * @param active Whether to activate the device
     */
    suspend fun updateConfig(id: String, token: String, active: Boolean): Unit = withContext(Dispatchers.IO) {
        val endpoint = "$BASE_URL/$API_VERSION/reg/$id"
        
        val requestBody = WgcfUpdateConfigRequest(active = active)
        val jsonBody = json.encodeToString(WgcfUpdateConfigRequest.serializer(), requestBody)
        
        Log.d(TAG, "📤 PATCH $endpoint")
        Log.d(TAG, "   Body: $jsonBody")
        Log.d(TAG, "   Authorization: Bearer ${token.take(20)}...")
        
        val request = Request.Builder()
            .url(endpoint)
            .method("PATCH", jsonBody.toRequestBody(CONTENT_TYPE.toMediaType()))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", CONTENT_TYPE)
            .header("Authorization", "Bearer $token")
            .build()
        
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            response.close()
            throw WgcfApiException("Update config failed: HTTP ${response.code} - $errorBody")
        }
        
        response.close()
        Log.d(TAG, "✅ Config updated successfully")
    }
    
    /**
     * Bind license key to account.
     * 
     * Endpoint: PUT /v0a2408/reg/{id}/account
     * 
     * Request body:
     * {
     *   "license": "<license_key>"
     * }
     * 
     * @param id Device ID from registration
     * @param token Authentication token from registration
     * @param license License key (format: xxxxxxxx-xxxxxxxx-xxxxxxxx)
     * @return WgcfAccount with updated account information
     */
    suspend fun bindLicense(id: String, token: String, license: String): WgcfAccount = withContext(Dispatchers.IO) {
        val endpoint = "$BASE_URL/$API_VERSION/reg/$id/account"
        
        val requestBody = WgcfBindLicenseRequest(license = license)
        val jsonBody = json.encodeToString(WgcfBindLicenseRequest.serializer(), requestBody)
        
        Log.d(TAG, "📤 PUT $endpoint")
        Log.d(TAG, "   Body: $jsonBody")
        Log.d(TAG, "   Authorization: Bearer ${token.take(20)}...")
        
        val request = Request.Builder()
            .url(endpoint)
            .put(jsonBody.toRequestBody(CONTENT_TYPE.toMediaType()))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", CONTENT_TYPE)
            .header("Authorization", "Bearer $token")
            .build()
        
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            response.close()
            throw WgcfApiException("Bind license failed: HTTP ${response.code} - $errorBody")
        }
        
        val responseBody = response.body?.string() ?: ""
        response.close()
        
        if (responseBody.isEmpty()) {
            throw WgcfApiException("Empty response body received")
        }
        
        Log.d(TAG, "📥 License binding response: ${responseBody.take(200)}...")
        
        try {
            val registrationResponse = json.decodeFromString<WgcfRegistrationResponse>(responseBody)
            val account = registrationResponse.account
                ?: throw WgcfApiException("Account information not found in response")
            return@withContext account
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse license binding response: ${e.message}")
            Log.e(TAG, "Full response: $responseBody")
            throw WgcfApiException("Failed to parse response: ${e.message}", e)
        }
    }
}

/**
 * Exception thrown by WgcfApi operations.
 */
class WgcfApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

