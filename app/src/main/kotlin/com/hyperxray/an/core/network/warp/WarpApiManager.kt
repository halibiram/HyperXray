package com.hyperxray.an.core.network.warp

import android.util.Base64
import android.util.Log
import com.hyperxray.an.utils.WarpUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.math.ec.rfc7748.X25519
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security

private const val TAG = "WarpApiManager"
private const val WARP_API_BASE_URL = "https://api.cloudflareclient.com"
// Try multiple API versions - Cloudflare updates these frequently
// Based on wgcf: https://github.com/ViRb3/wgcf/blob/master/cloudflare/api.go
private val WARP_API_VERSIONS = listOf("v0a1922", "v0a2408", "v0a1927", "v0a1926", "v0a1925")
private const val WARP_API_VERSION = "v0a1922" // Default version (from wgcf)
private const val WARP_REGISTRATION_ENDPOINT = "$WARP_API_BASE_URL/$WARP_API_VERSION/reg"

/**
 * Manager for Cloudflare WARP API registration.
 * Handles automatic account registration and identity generation.
 */
object WarpApiManager {
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true // Include default values (empty strings) in serialization
    }
    
    // WARP API requires TLS 1.2 and HTTP/1.1 (not HTTP/2)
    // Based on wgcf implementation: https://github.com/ViRb3/wgcf/blob/master/cloudflare/api.go
    private val warpHttpClient = createWarpHttpClient()
    
    /**
     * Create HTTP client specifically for WARP API.
     * WARP API rejects requests with HTTP/2 and requires TLS 1.2.
     */
    private fun createWarpHttpClient(): okhttp3.OkHttpClient {
        val builder = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        
        // Force HTTP/1.1 only (WARP API doesn't accept HTTP/2)
        builder.protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        
        // Configure TLS 1.2 only (as per wgcf implementation)
        try {
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)
            
            val trustManagerFactory = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            trustManagerFactory.init(null as java.security.KeyStore?)
            val trustManagers = trustManagerFactory.trustManagers
            val x509TrustManager = trustManagers.firstOrNull { it is javax.net.ssl.X509TrustManager } as? javax.net.ssl.X509TrustManager
            
            if (x509TrustManager != null) {
                // Create socket factory that enforces TLS 1.2
                val socketFactory = object : javax.net.ssl.SSLSocketFactory() {
                    private val delegate = sslContext.socketFactory
                    
                    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
                    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
                    
                    override fun createSocket(): Socket = delegate.createSocket().apply {
                        if (this is javax.net.ssl.SSLSocket) {
                            this.enabledProtocols = arrayOf("TLSv1.2")
                        }
                    }
                    
                    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
                        return delegate.createSocket(s, host, port, autoClose).apply {
                            if (this is javax.net.ssl.SSLSocket) {
                                this.enabledProtocols = arrayOf("TLSv1.2")
                            }
                        }
                    }
                    
                    override fun createSocket(host: String, port: Int): Socket {
                        return delegate.createSocket(host, port).apply {
                            if (this is javax.net.ssl.SSLSocket) {
                                this.enabledProtocols = arrayOf("TLSv1.2")
                            }
                        }
                    }
                    
                    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket {
                        return delegate.createSocket(host, port, localHost, localPort).apply {
                            if (this is javax.net.ssl.SSLSocket) {
                                this.enabledProtocols = arrayOf("TLSv1.2")
                            }
                        }
                    }
                    
                    override fun createSocket(host: java.net.InetAddress, port: Int): Socket {
                        return delegate.createSocket(host, port).apply {
                            if (this is javax.net.ssl.SSLSocket) {
                                this.enabledProtocols = arrayOf("TLSv1.2")
                            }
                        }
                    }
                    
                    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): Socket {
                        return delegate.createSocket(address, port, localAddress, localPort).apply {
                            if (this is javax.net.ssl.SSLSocket) {
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
     * Generate a complete WARP identity by:
     * 1. Generating a local Curve25519 KeyPair
     * 2. Registering with Cloudflare WARP API
     * 3. Extracting License Key, IPv6 Address, and Reserved Bytes
     * 
     * @return WarpIdentityResult with all necessary information
     */
    suspend fun generateWarpIdentity(): WarpIdentityResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "🚀 Starting WARP identity generation...")
            
            // Step 1: Generate local Curve25519 KeyPair
            val (privateKey, publicKey) = generateKeyPair()
            Log.d(TAG, "✅ Generated Curve25519 key pair")
            Log.d(TAG, "   Private key: ${privateKey.take(20)}... (${privateKey.length} chars)")
            Log.d(TAG, "   Public key: ${publicKey.take(20)}... (${publicKey.length} chars)")
            
            // Step 2: Prepare registration request
            // Based on wgcf: install_id and fcm_token should be empty strings
            // Cloudflare expects timestamp in seconds (Unix timestamp), not milliseconds
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            
            // Validate public key format before sending
            if (publicKey.length != 44) {
                val errorMsg = "Invalid public key length: ${publicKey.length} (expected 44)"
                Log.e(TAG, "❌ $errorMsg")
                return@withContext WarpIdentityResult(
                    success = false,
                    privateKey = privateKey,
                    error = errorMsg
                )
            }
            
            // Get device model (Android device model)
            val deviceModel = try {
                android.os.Build.MODEL ?: "Android"
            } catch (e: Exception) {
                "Android"
            }
            
            // Create request body matching wgcf's field order: FcmToken, InstallId, Key, Locale, Model, Tos, Type
            val requestBody = WarpRegistrationRequest(
                fcm_token = "", // Empty as per wgcf implementation
                install_id = "", // Empty as per wgcf implementation
                key = publicKey,
                locale = "en_US",
                model = deviceModel,
                tos = timestamp,
                type = "Android"
            )
            
            // Manually build JSON to match wgcf's exact format
            // wgcf sends empty strings for install_id and fcm_token, but we'll try omitting them
            val jsonObject = org.json.JSONObject().apply {
                // Only include non-empty fields (wgcf sends empty strings but API might reject them)
                if (requestBody.install_id.isNotEmpty()) put("install_id", requestBody.install_id)
                put("tos", requestBody.tos)
                put("key", requestBody.key)
                if (requestBody.fcm_token.isNotEmpty()) put("fcm_token", requestBody.fcm_token)
                put("type", requestBody.type)
                put("locale", requestBody.locale)
                put("model", requestBody.model)
            }
            val jsonBody = jsonObject.toString()
            
            Log.d(TAG, "📤 Registration request:")
            Log.d(TAG, "   install_id: (omitted - empty)")
            Log.d(TAG, "   tos: $timestamp")
            Log.d(TAG, "   key: ${publicKey.take(20)}... (${publicKey.length} chars)")
            Log.d(TAG, "   fcm_token: (omitted - empty)")
            Log.d(TAG, "   type: ${requestBody.type}")
            Log.d(TAG, "   locale: ${requestBody.locale}")
            Log.d(TAG, "   model: $deviceModel")
            Log.d(TAG, "   Request body: $jsonBody")
            
            // Step 3: Try registration with multiple API versions if first attempt fails
            var lastError: String? = null
            var responseBody: String? = null
            var successfulResponse: okhttp3.Response? = null
            
            for (apiVersion in WARP_API_VERSIONS) {
                val endpoint = "$WARP_API_BASE_URL/$apiVersion/reg"
                Log.d(TAG, "🔄 Trying API version: $apiVersion")
                
                // CF-Client-Version should match API version
                val clientVersion = when (apiVersion) {
                    "v0a1922" -> "a-6.3-1922"
                    "v0a2408" -> "a-6.11-2408"
                    else -> "a-6.3-1922" // Default to wgcf version
                }
                
                val request = Request.Builder()
                    .url(endpoint)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", "okhttp/3.12.1")
                    .header("Content-Type", "application/json")
                    .header("CF-Client-Version", clientVersion)
                    .build()
                
                val response = warpHttpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    Log.i(TAG, "✅ Registration successful with API version: $apiVersion")
                    responseBody = response.body?.string() ?: ""
                    successfulResponse = response
                    break
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    val statusCode = response.code
                    lastError = "HTTP $statusCode: $errorBody"
                    Log.w(TAG, "⚠️ API version $apiVersion failed: $lastError")
                    response.close() // Close failed response
                    
                    // Handle rate limiting (429) - wait before retrying
                    if (statusCode == 429) {
                        val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: 60
                        val waitTime = retryAfter * 1000L // Convert to milliseconds
                        Log.w(TAG, "⏳ Rate limited (HTTP 429). Waiting ${retryAfter}s before retry...")
                        return@withContext WarpIdentityResult(
                            success = false,
                            privateKey = privateKey,
                            error = "Rate limited by Cloudflare. Please wait ${retryAfter} seconds and try again. (HTTP 429)"
                        )
                    }
                    
                    // If it's not a 400 (Bad Request), don't try other versions
                    // 400 might be format issue, but 401/403/404 are different errors
                    if (statusCode != 400) {
                        Log.e(TAG, "❌ Registration failed with non-400 error, stopping retries")
                        break
                    }
                }
            }
            
            if (responseBody == null || successfulResponse == null) {
                Log.e(TAG, "❌ Registration failed after trying all API versions: $lastError")
                return@withContext WarpIdentityResult(
                    success = false,
                    privateKey = privateKey,
                    error = "Registration failed: ${lastError ?: "No response received"}"
                )
            }
            
            successfulResponse.close() // Close successful response after reading body
            Log.d(TAG, "📥 Registration response: ${responseBody.take(200)}...")
            
            // Step 4: Parse response
            if (responseBody.isEmpty()) {
                Log.e(TAG, "❌ Empty response body received")
                return@withContext WarpIdentityResult(
                    success = false,
                    privateKey = privateKey,
                    error = "Empty response body received"
                )
            }
            
            val registrationResponse = try {
                json.decodeFromString<WarpRegistrationResponse>(responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to parse registration response: ${e.message}", e)
                return@withContext WarpIdentityResult(
                    success = false,
                    privateKey = privateKey,
                    error = "Failed to parse response: ${e.message}"
                )
            }
            
            // Step 5: Extract License Key, IPv6 Address, Client ID, and Token
            val license = registrationResponse.account?.license
            val clientId = registrationResponse.id ?: registrationResponse.account?.id
            val token = registrationResponse.token
            val localAddress = extractLocalAddress(registrationResponse.config)
            
            if (license == null) {
                Log.w(TAG, "⚠️ License key not found in response")
            } else {
                Log.i(TAG, "✅ License key extracted: ${license.take(20)}...")
            }
            
            if (token == null) {
                Log.w(TAG, "⚠️ Authentication token not found in response")
            } else {
                Log.d(TAG, "✅ Authentication token extracted: ${token.take(20)}...")
            }
            
            if (localAddress == null) {
                Log.w(TAG, "⚠️ Local address not found in response, using default")
            } else {
                Log.i(TAG, "✅ Local address extracted: $localAddress")
            }
            
            Log.i(TAG, "✅ WARP identity generation completed successfully")
            
            WarpIdentityResult(
                success = true,
                privateKey = privateKey,
                license = license,
                localAddress = localAddress,
                clientId = clientId,
                token = token,
                accountType = registrationResponse.account?.accountType
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ WARP identity generation failed: ${e.message}", e)
            WarpIdentityResult(
                success = false,
                privateKey = "",
                error = "Registration failed: ${e.message}"
            )
        }
    }
    
    /**
     * Generate Curve25519 KeyPair and return Base64-encoded keys.
     * 
     * @return Pair of (privateKey, publicKey) as Base64 strings
     */
    private fun generateKeyPair(): Pair<String, String> {
        // Ensure BouncyCastle provider is registered
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        
        try {
            // Generate X25519 key pair
            val keyPairGenerator = KeyPairGenerator.getInstance("X25519", "BC")
            val keyPair = keyPairGenerator.generateKeyPair()
            
            // Extract private key bytes (32 bytes)
            val privateKeyBytes = extractPrivateKeyBytes(keyPair.private)
            
            // Clamp private key according to WireGuard specification
            val clampedPrivateKey = clampCurve25519Key(privateKeyBytes)
            val privateKey = Base64.encodeToString(clampedPrivateKey, Base64.NO_WRAP)
            
            // Derive public key from private key using X25519
            val publicKeyBytes = ByteArray(32)
            X25519.scalarMultBase(clampedPrivateKey, 0, publicKeyBytes, 0)
            val publicKey = Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP)
            
            if (privateKey.length != 44 || publicKey.length != 44) {
                throw IllegalStateException("Invalid key length: private=${privateKey.length}, public=${publicKey.length}")
            }
            
            return Pair(privateKey, publicKey)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate key pair using BouncyCastle, trying fallback: ${e.message}")
            
            // Fallback: Use WarpUtils which has multiple fallback methods
            val privateKey = WarpUtils.generatePrivateKey()
            
            // Validate private key before deriving public key
            if (!WarpUtils.isValidPrivateKey(privateKey)) {
                throw IllegalStateException("Generated private key is invalid (length: ${privateKey.length})")
            }
            
            // For fallback, we need to derive public key manually
            // Use WarpUtils.derivePublicKey which handles X25519 properly
            try {
                val publicKey = WarpUtils.derivePublicKey(privateKey)
                
                // Validate public key format
                if (publicKey.length != 44) {
                    throw IllegalStateException("Derived public key has invalid length: ${publicKey.length} (expected 44)")
                }
                
                Log.d(TAG, "✅ Derived public key from private key using fallback method")
                return Pair(privateKey, publicKey)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to derive public key: ${e2.message}", e2)
                throw IllegalStateException("Failed to generate key pair: ${e2.message}", e2)
            }
        }
    }
    
    /**
     * Extract private key bytes from Java PrivateKey object.
     */
    private fun extractPrivateKeyBytes(privateKey: java.security.PrivateKey): ByteArray {
        val encoded = privateKey.encoded
        
        // Try to extract 32-byte key from PKCS#8 format
        // For X25519, the key is typically at the end of the encoded bytes
        if (encoded.size >= 32) {
            // Try last 32 bytes
            val candidate = encoded.sliceArray(encoded.size - 32 until encoded.size)
            if (candidate.size == 32) {
                return candidate
            }
        }
        
        throw IllegalArgumentException("Could not extract 32-byte key from private key (encoded size: ${encoded.size})")
    }
    
    /**
     * Clamp Curve25519 private key according to WireGuard specification.
     * First byte: clear bits 0, 1, 2 (mask 0xF8)
     * Last byte: clear bit 7, set bit 6 (mask 0x7F, then OR 0x40)
     */
    private fun clampCurve25519Key(key: ByteArray): ByteArray {
        if (key.size != 32) {
            throw IllegalArgumentException("Key must be 32 bytes, got ${key.size}")
        }
        
        val clampedKey = key.copyOf()
        clampedKey[0] = (clampedKey[0].toInt() and 0xF8).toByte()
        clampedKey[31] = ((clampedKey[31].toInt() and 0x7F) or 0x40).toByte()
        return clampedKey
    }
    
    /**
     * Extract local address from WARP config response.
     * Handles both object format (v4/v6) and array format.
     */
    private fun extractLocalAddress(config: WarpConfig?): String? {
        if (config == null) return null
        
        val interfaceConfig = config.`interface` ?: return null
        val addresses = interfaceConfig.addresses ?: return null
        
        // Try to extract IPv4 and IPv6 addresses
        val v4 = addresses.v4
        val v6 = addresses.v6
        
        // Prefer IPv4 for compatibility (many devices don't have IPv6)
        // Format: "172.16.0.2/32" or "172.16.0.2/32, 2606:4700:110:8f5a:7c46:852e:f45c:6d35/128"
        return when {
            v4 != null && v6 != null -> "$v4, $v6"
            v4 != null -> v4
            v6 != null -> v6
            else -> null
        }
    }
    
    /**
     * Register a new device with Cloudflare WARP API.
     * This is the first step in the 2-step WARP+ license binding process.
     * 
     * @param publicKey The public key (Base64-encoded Curve25519 public key)
     * @return WarpRegistrationResult with clientId, token, and account info
     */
    suspend fun registerNewDevice(publicKey: String): WarpRegistrationResult {
        return registerDevice(publicKey)
    }
    
    /**
     * Register a new device with Cloudflare WARP API.
     * This is the first step in the 2-step WARP+ license binding process.
     * 
     * @param publicKey The public key (Base64-encoded Curve25519 public key)
     * @return WarpRegistrationResult with clientId, token, and account info
     */
    suspend fun registerDevice(publicKey: String): WarpRegistrationResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "📝 Registering device with WARP API...")
            Log.d(TAG, "   Public key: ${publicKey.take(20)}...")
            
            // Prepare registration request
            // Based on wgcf: install_id and fcm_token should be empty strings
            // Cloudflare expects timestamp in seconds (Unix timestamp), not milliseconds
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            
            // Validate public key format
            if (publicKey.length != 44) {
                val errorMsg = "Invalid public key length: ${publicKey.length} (expected 44)"
                Log.e(TAG, "❌ $errorMsg")
                return@withContext WarpRegistrationResult(
                    success = false,
                    error = errorMsg
                )
            }
            
            // Get device model (Android device model)
            val deviceModel = try {
                android.os.Build.MODEL ?: "Android"
            } catch (e: Exception) {
                "Android"
            }
            
            // Create request body matching wgcf's field order: FcmToken, InstallId, Key, Locale, Model, Tos, Type
            val requestBody = WarpRegistrationRequest(
                fcm_token = "", // Empty as per wgcf implementation
                install_id = "", // Empty as per wgcf implementation
                key = publicKey,
                locale = "en_US",
                model = deviceModel,
                tos = timestamp,
                type = "Android"
            )
            
            // Manually build JSON to match wgcf's exact format
            // wgcf sends empty strings for install_id and fcm_token, but we'll try omitting them
            val jsonObject = org.json.JSONObject().apply {
                // Only include non-empty fields (wgcf sends empty strings but API might reject them)
                if (requestBody.install_id.isNotEmpty()) put("install_id", requestBody.install_id)
                put("tos", requestBody.tos)
                put("key", requestBody.key)
                if (requestBody.fcm_token.isNotEmpty()) put("fcm_token", requestBody.fcm_token)
                put("type", requestBody.type)
                put("locale", requestBody.locale)
                put("model", requestBody.model)
            }
            val jsonBody = jsonObject.toString()
            
            Log.d(TAG, "📤 Registration request:")
            Log.d(TAG, "   install_id: (omitted - empty)")
            Log.d(TAG, "   tos: $timestamp")
            Log.d(TAG, "   key: ${publicKey.take(20)}... (${publicKey.length} chars)")
            Log.d(TAG, "   fcm_token: (omitted - empty)")
            Log.d(TAG, "   type: ${requestBody.type}")
            Log.d(TAG, "   locale: ${requestBody.locale}")
            Log.d(TAG, "   model: $deviceModel")
            Log.d(TAG, "   Request body: $jsonBody")
            Log.d(TAG, "   model: $deviceModel")
            
            // Try registration with multiple API versions if first attempt fails
            var lastError: String? = null
            var responseBody: String? = null
            var successfulResponse: okhttp3.Response? = null
            
            for (apiVersion in WARP_API_VERSIONS) {
                val endpoint = "$WARP_API_BASE_URL/$apiVersion/reg"
                Log.d(TAG, "🔄 Trying API version: $apiVersion")
                
                // CF-Client-Version should match API version
                val clientVersion = when (apiVersion) {
                    "v0a1922" -> "a-6.3-1922"
                    "v0a2408" -> "a-6.11-2408"
                    else -> "a-6.3-1922" // Default to wgcf version
                }
                
                val request = Request.Builder()
                    .url(endpoint)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", "okhttp/3.12.1")
                    .header("Content-Type", "application/json")
                    .header("CF-Client-Version", clientVersion)
                    .build()
                
                val response = warpHttpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    Log.i(TAG, "✅ Registration successful with API version: $apiVersion")
                    responseBody = response.body?.string() ?: ""
                    successfulResponse = response
                    break
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    lastError = "HTTP ${response.code}: $errorBody"
                    Log.w(TAG, "⚠️ API version $apiVersion failed: $lastError")
                    response.close() // Close failed response
                    
                    // If it's not a 400 (Bad Request), don't try other versions
                    if (response.code != 400) {
                        Log.e(TAG, "❌ Registration failed with non-400 error, stopping retries")
                        break
                    }
                }
            }
            
            if (responseBody == null || successfulResponse == null) {
                Log.e(TAG, "❌ Device registration failed after trying all API versions: $lastError")
                return@withContext WarpRegistrationResult(
                    success = false,
                    error = "Registration failed: ${lastError ?: "No response received"}"
                )
            }
            
            successfulResponse.close() // Close successful response after reading body
            Log.d(TAG, "📥 Registration response: ${responseBody.take(500)}...")
            
            // Parse response
            if (responseBody.isEmpty()) {
                Log.e(TAG, "❌ Empty response body received")
                return@withContext WarpRegistrationResult(
                    success = false,
                    error = "Empty response body received"
                )
            }
            
            val registrationResponse = try {
                json.decodeFromString<WarpRegistrationResponse>(responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to parse registration response: ${e.message}", e)
                Log.e(TAG, "   Full response body: $responseBody")
                return@withContext WarpRegistrationResult(
                    success = false,
                    error = "Failed to parse response: ${e.message}"
                )
            }
            
            val clientId = registrationResponse.id ?: registrationResponse.account?.id
            val token = registrationResponse.token
            val localAddress = extractLocalAddress(registrationResponse.config)
            
            if (clientId.isNullOrEmpty()) {
                Log.e(TAG, "❌ Client ID not found in registration response")
                return@withContext WarpRegistrationResult(
                    success = false,
                    error = "Client ID not found in response"
                )
            }
            
            if (token.isNullOrEmpty()) {
                Log.e(TAG, "❌ Authentication token not found in registration response")
                return@withContext WarpRegistrationResult(
                    success = false,
                    error = "Authentication token not found in response"
                )
            }
            
            Log.i(TAG, "✅ Device registered successfully: clientId=${clientId.take(20)}..., token=${token.take(20)}...")
            if (localAddress != null) {
                Log.d(TAG, "   Local address: $localAddress")
            }
            
            WarpRegistrationResult(
                success = true,
                clientId = clientId,
                token = token,
                accountType = registrationResponse.account?.accountType,
                localAddress = localAddress
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Device registration failed: ${e.message}", e)
            WarpRegistrationResult(
                success = false,
                error = "Registration failed: ${e.message}"
            )
        }
    }
    
    /**
     * Update WARP account license key.
     * Binds a user-provided WARP+ license key to an existing account.
     * This is the second step in the 2-step WARP+ license binding process.
     * 
     * @param clientId The client ID from registration (account ID)
     * @param token The authentication token from registration
     * @param licenseKey The license key to bind (format: xxxx-xxxx-xxxx)
     * @return WarpLicenseUpdateResult with success status and account type
     */
    suspend fun updateLicenseKey(
        clientId: String,
        token: String,
        licenseKey: String
    ): WarpLicenseUpdateResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "🔑 Starting WARP license update...")
            Log.d(TAG, "   Client ID: ${clientId.take(20)}...")
            Log.d(TAG, "   License Key: ${licenseKey.take(10)}...")
            
            // Normalize and validate license key format (xxxx-xxxx-xxxx)
            val normalizedLicenseKey = normalizeLicenseKey(licenseKey)
            if (normalizedLicenseKey == null) {
                val errorMsg = "Invalid license key format. Expected format: xxxx-xxxx-xxxx (e.g., ABCD-1234-EFGH)"
                Log.e(TAG, "❌ $errorMsg")
                Log.d(TAG, "   Provided key: ${licenseKey.take(20)}... (length: ${licenseKey.length})")
                return@withContext WarpLicenseUpdateResult(
                    success = false,
                    error = errorMsg
                )
            }
            
            // Prepare update request body (use normalized key)
            val requestBody = json.encodeToString(
                WarpLicenseUpdateRequest.serializer(),
                WarpLicenseUpdateRequest(license = normalizedLicenseKey)
            )
            
            // Build PUT request to update account with Authorization header
            // Try multiple API versions if first attempt fails
            var lastError: String? = null
            var responseBody: String? = null
            var successfulResponse: okhttp3.Response? = null
            
            for (apiVersion in WARP_API_VERSIONS) {
                val updateUrl = "$WARP_API_BASE_URL/$apiVersion/reg/$clientId/account"
                Log.d(TAG, "📤 License update request: PUT $updateUrl (API version: $apiVersion)")
                Log.d(TAG, "   Authorization: Bearer ${token.take(20)}...")
                
                // CF-Client-Version should match API version
                val clientVersion = when (apiVersion) {
                    "v0a1922" -> "a-6.3-1922"
                    "v0a2408" -> "a-6.11-2408"
                    "v0a1927" -> "a-6.11-1927"
                    "v0a1926" -> "a-6.11-1926"
                    "v0a1925" -> "a-6.11-1925"
                    else -> "a-6.11-2408" // Default
                }
                
                val request = Request.Builder()
                    .url(updateUrl)
                    .put(requestBody.toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", "okhttp/3.12.1")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $token")
                    .header("CF-Client-Version", clientVersion)
                    .build()
                
                val response = warpHttpClient.newCall(request).execute()
                
                val statusCode = response.code
                
                if (response.isSuccessful) {
                    Log.i(TAG, "✅ License update successful with API version: $apiVersion")
                    try {
                        responseBody = response.body?.string() ?: ""
                        successfulResponse = response
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to read response body: ${e.message}", e)
                        lastError = "HTTP $statusCode: Failed to read response body"
                        response.close()
                        break
                    }
                } else {
                    val errorBody = try {
                        response.body?.string() ?: "Unknown error"
                    } catch (e: Exception) {
                        "Failed to read error body: ${e.message}"
                    }
                    lastError = "HTTP $statusCode: $errorBody"
                    Log.w(TAG, "⚠️ API version $apiVersion failed: $lastError")
                    response.close() // Close failed response
                    
                    // Handle rate limiting (429) - wait before retrying
                    if (statusCode == 429) {
                        val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: 60
                        Log.w(TAG, "⏳ Rate limited (HTTP 429). Waiting ${retryAfter}s before retry...")
                        return@withContext WarpLicenseUpdateResult(
                            success = false,
                            error = "Rate limited by Cloudflare. Please wait ${retryAfter} seconds and try again. (HTTP 429)"
                        )
                    }
                    
                    // If it's 401 (Unauthorized), try other API versions or return immediately
                    // 401 means authentication failed, might need re-registration
                    if (statusCode == 401) {
                        Log.w(TAG, "⚠️ Authentication failed (401) with API version $apiVersion, will try other versions")
                        // Continue to try other API versions
                    } else if (statusCode != 400) {
                        // If it's not 400 (Bad Request) or 401 (Unauthorized), don't try other versions
                        // 400 might be format issue, but 403/404 are different errors
                        Log.e(TAG, "❌ License update failed with non-400/401 error (HTTP $statusCode), stopping retries")
                        break
                    }
                }
            }
            
            if (responseBody == null || successfulResponse == null) {
                Log.e(TAG, "❌ License update failed after trying all API versions: $lastError")
                
                // Extract HTTP status code from lastError if available
                val statusCodeMatch = Regex("HTTP (\\d+)").find(lastError ?: "")
                val statusCode = statusCodeMatch?.groupValues?.get(1)?.toIntOrNull()
                
                // Check for common error cases
                val errorMessage = when {
                    statusCode == 401 || lastError?.contains("401") == true -> 
                        "Authentication failed. Token may be invalid or expired. Please try generating a new identity."
                    statusCode == 400 || lastError?.contains("400") == true -> 
                        "Invalid license key or request format. Please check your license key format (xxxx-xxxx-xxxx)."
                    statusCode == 403 || lastError?.contains("403") == true -> 
                        "License key has reached device limit (max 5 devices) or is invalid."
                    statusCode == 404 || lastError?.contains("404") == true -> 
                        "Account not found. Please register first."
                    statusCode == 429 || lastError?.contains("429") == true -> 
                        "Too many requests. Please try again later."
                    else -> "License update failed: ${lastError ?: "No response received"}"
                }
                
                return@withContext WarpLicenseUpdateResult(
                    success = false,
                    error = errorMessage
                )
            }
            
            successfulResponse.close() // Close successful response after reading body
            
            Log.d(TAG, "📥 License update response: ${responseBody.take(500)}...")
            
            // Parse response
            val updateResponse = try {
                json.decodeFromString<WarpRegistrationResponse>(responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to parse license update response: ${e.message}", e)
                Log.e(TAG, "   Full response body: $responseBody")
                return@withContext WarpLicenseUpdateResult(
                    success = false,
                    error = "Failed to parse response: ${e.message}"
                )
            }
            
            // Extract account type, license, and quota from response
            val accountType = updateResponse.account?.accountType
            val license = updateResponse.account?.license
            // Extract quota from premium_data or quota field (premium_data is in bytes)
            val quota = updateResponse.account?.premiumData ?: updateResponse.account?.quota
            
            // Check if account type indicates success (plus/unlimited)
            val isPlus = accountType?.let { 
                it.equals("plus", ignoreCase = true) || 
                it.equals("unlimited", ignoreCase = true) ||
                it.equals("premium", ignoreCase = true)
            } ?: false
            
            if (isPlus) {
                val quotaDisplay = quota?.let { 
                    val gb = it / (1024.0 * 1024.0 * 1024.0)
                    String.format("%.2f GB", gb)
                } ?: "Unlimited"
                Log.i(TAG, "✅ License update successful: Account type is $accountType, Quota: $quotaDisplay")
            } else {
                Log.w(TAG, "⚠️ License update completed but account type is still: $accountType")
            }
            
            WarpLicenseUpdateResult(
                success = true,
                accountType = accountType,
                license = license,
                quota = quota
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ WARP license update failed: ${e.message}", e)
            WarpLicenseUpdateResult(
                success = false,
                error = "License update failed: ${e.message}"
            )
        }
    }
    
    /**
     * Normalize license key format.
     * Removes spaces, converts to uppercase, and validates/repairs format.
     * @return Normalized license key or null if invalid
     */
    private fun normalizeLicenseKey(rawKey: String): String? {
        // Remove all spaces and convert to uppercase
        val cleaned = rawKey.replace("\\s".toRegex(), "").uppercase()
        
        // Check if it's already in correct format
        val pattern = Regex("^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")
        if (pattern.matches(cleaned)) {
            return cleaned
        }
        
        // Try to fix common issues: missing dashes, wrong dash positions
        // If it's 12 characters without dashes, add dashes
        if (cleaned.length == 12 && cleaned.all { it.isLetterOrDigit() }) {
            return "${cleaned.substring(0, 4)}-${cleaned.substring(4, 8)}-${cleaned.substring(8, 12)}"
        }
        
        // If it has dashes but in wrong positions, try to fix
        val withoutDashes = cleaned.replace("-", "").replace("_", "").replace(" ", "")
        if (withoutDashes.length == 12 && withoutDashes.all { it.isLetterOrDigit() }) {
            return "${withoutDashes.substring(0, 4)}-${withoutDashes.substring(4, 8)}-${withoutDashes.substring(8, 12)}"
        }
        
        return null
    }
    
    /**
     * Validate license key format (xxxx-xxxx-xxxx).
     * Accepts alphanumeric characters with dashes.
     */
    private fun isValidLicenseKeyFormat(licenseKey: String): Boolean {
        // Format: xxxx-xxxx-xxxx (alphanumeric with dashes)
        // Example: "1234-5678-9ABC" or "abcd-efgh-ijkl"
        val pattern = Regex("^[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}$")
        return pattern.matches(licenseKey.trim())
    }
}

