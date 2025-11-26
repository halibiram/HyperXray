package com.hyperxray.an.feature.warp.data.datasource

import android.util.Log
import com.hyperxray.an.core.network.http.HttpClientFactory
import com.hyperxray.an.feature.warp.data.model.WarpApiResponse
import com.hyperxray.an.feature.warp.data.model.WarpDeviceResponse
import com.hyperxray.an.feature.warp.data.model.WarpRegistrationRequest
import com.hyperxray.an.feature.warp.data.model.WarpUpdateLicenseRequest
import com.hyperxray.an.feature.warp.data.model.WarpUpdateKeyRequest
import com.hyperxray.an.feature.warp.data.util.WireGuardKeyGenerator
import com.hyperxray.an.feature.warp.data.util.WarpIdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

private const val TAG = "WarpApiDataSource"

/**
 * Cloudflare WARP API versions to try
 */
private val API_VERSIONS = listOf("v0a2484", "v0i2409", "v0a977", "v0a769", "v0a737")

/**
 * Base URL template
 */
private const val BASE_URL_TEMPLATE = "https://api.cloudflareclient.com/{version}"

/**
 * WARP public key
 */
private const val WARP_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

/**
 * Default WARP endpoint
 */
private const val WARP_ENDPOINT = "engage.cloudflareclient.com:2408"

/**
 * Default headers mimicking official Android client
 */
private val DEFAULT_HEADERS = mapOf(
    "Content-Type" to "application/json",
    "Accept" to "application/json",
    "Accept-Encoding" to "gzip",
    "User-Agent" to "okhttp/3.12.1",
    "CF-Client-Version" to "a-6.28"
)

/**
 * Alternative headers mimicking iOS client
 */
private val IOS_HEADERS = mapOf(
    "Content-Type" to "application/json",
    "Accept" to "application/json",
    "Accept-Encoding" to "gzip, deflate",
    "User-Agent" to "1.1.1.1/2024.5.397.0 CFNetwork/1496.0.7 Darwin/23.5.0",
    "CF-Client-Version" to "i-6.27"
)

/**
 * JSON serializer
 */
private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
}

/**
 * Data source for Cloudflare WARP API
 */
class WarpApiDataSource(
    private val useIosHeaders: Boolean = false
) {
    
    private val httpClient = HttpClientFactory.createHttpClient()
    private val headers = if (useIosHeaders) IOS_HEADERS else DEFAULT_HEADERS
    private var workingApiVersion: String? = null
    private var baseUrl: String? = null
    private var authToken: String? = null
    
    /**
     * Initialize API connection from saved account
     * This restores baseUrl and authToken when account is loaded
     */
    fun initializeFromAccount(accountId: String, token: String?) {
        // Try to find working API version by testing with account info
        // Or use the first API version as default (most likely to work)
        if (workingApiVersion == null || baseUrl == null) {
            // Use first API version as default (v0a2484 is usually working)
            workingApiVersion = API_VERSIONS.firstOrNull() ?: "v0a2484"
            baseUrl = BASE_URL_TEMPLATE.replace("{version}", workingApiVersion!!)
            Log.d(TAG, "Initialized API connection with version: $workingApiVersion")
        }
        
        // Restore auth token
        token?.let {
            authToken = it
            Log.d(TAG, "Auth token restored")
        }
    }
    
    /**
     * Register a new WARP account
     */
    suspend fun registerAccount(licenseKey: String? = null): Result<WarpApiResponse> {
        Log.d(TAG, "Registering new WARP account...")
        
        // Try each API version
        for (apiVersion in API_VERSIONS) {
            Log.d(TAG, "Trying API version: $apiVersion...")
            
            val result = tryRegisterWithVersion(apiVersion)
            if (result != null) {
                workingApiVersion = apiVersion
                baseUrl = BASE_URL_TEMPLATE.replace("{version}", apiVersion)
                
                // Store auth token if available
                result.token?.let { token ->
                    authToken = token
                }
                
                Log.d(TAG, "Success with API version: $apiVersion")
                
                // Update license if provided
                if (licenseKey != null && result.id != null) {
                    updateLicense(result.id, licenseKey)
                }
                
                return Result.success(result)
            }
        }
        
        return Result.failure(
            IOException("All API versions failed. This is usually caused by:\n" +
                "1. Running from a cloud server (AWS, GCP, Azure, etc.)\n" +
                "2. Behind a VPN or proxy\n" +
                "3. IP rate-limited\n" +
                "4. Cloudflare API changes")
        )
    }
    
    /**
     * Try to register with a specific API version
     */
    private suspend fun tryRegisterWithVersion(apiVersion: String): WarpApiResponse? = withContext(Dispatchers.IO) {
        val baseUrl = BASE_URL_TEMPLATE.replace("{version}", apiVersion)
        
        // Generate keypair
        val (privateKey, publicKey) = WireGuardKeyGenerator.generateKeyPair()
        
        // Prepare registration payload
        val installId = WarpIdGenerator.generateInstallId()
        val fcmToken = WarpIdGenerator.generateFcmToken()
        val tosTimestamp = WarpIdGenerator.getTimestamp()
        
        val payload = WarpRegistrationRequest(
            key = publicKey,
            installId = installId,
            fcmToken = fcmToken,
            tos = tosTimestamp,
            type = "Android",
            model = "PC",
            locale = "en_US",
            warpEnabled = true
        )
        
        try {
            val requestBody = json.encodeToString(WarpRegistrationRequest.serializer(), payload)
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/reg")
                .post(requestBody)
                .apply {
                    headers.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                .build()
            
            val response = httpClient.newCall(request).execute()
            val statusCode = response.code
            
            // Safely read response body - check for gzip compression
            val responseBody = response.use { resp ->
                val contentEncoding = resp.header("Content-Encoding") ?: ""
                val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
                
                Log.d(TAG, "Response status: $statusCode, Content-Encoding: $contentEncoding, body size: ${bodyBytes.size}")
                
                // Check if response is gzip compressed (by magic bytes: 0x1F 0x8B)
                val isGzip = contentEncoding.contains("gzip", ignoreCase = true) ||
                        (bodyBytes.size >= 2 && bodyBytes[0] == 0x1F.toByte() && bodyBytes[1] == 0x8B.toByte())
                
                if (isGzip) {
                    Log.d(TAG, "Response is gzip compressed, decompressing manually...")
                    try {
                        GZIPInputStream(ByteArrayInputStream(bodyBytes)).use { gzipStream ->
                            gzipStream.readBytes().toString(Charsets.UTF_8)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decompress gzip response", e)
                        // Fallback: try as plain text
                        try {
                            bodyBytes.toString(Charsets.UTF_8)
                        } catch (e2: Exception) {
                            Log.e(TAG, "UTF-8 decode also failed", e2)
                            ""
                        }
                    }
                } else {
                    // Plain text response
                    try {
                        bodyBytes.toString(Charsets.UTF_8)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode response as UTF-8", e)
                        ""
                    }
                }
            }
            
            Log.d(TAG, "Response body length: ${responseBody.length}")
            if (responseBody.length > 0 && responseBody.length <= 2000) {
                Log.d(TAG, "Response body: $responseBody")
            } else if (responseBody.length > 2000) {
                Log.d(TAG, "Response body preview (first 500 chars): ${responseBody.take(500)}")
            }
            
            if (responseBody.isBlank()) {
                Log.w(TAG, "Empty response body for $apiVersion (status: $statusCode)")
                return@withContext null
            }
            
            if (statusCode == 200 || statusCode == 500) {
                // Some versions return 500 but still create the account
                try {
                    Log.d(TAG, "Attempting to parse JSON response...")
                    val apiResponse = json.decodeFromString<WarpApiResponse>(responseBody)
                    if (apiResponse.id != null) {
                        Log.d(TAG, "✅ Successfully parsed response with account ID: ${apiResponse.id}")
                        return@withContext apiResponse.copy(
                            // Store private key in response for later use
                            privateKey = privateKey,
                            publicKey = publicKey
                        )
                    } else {
                        Log.w(TAG, "⚠️ Response parsed but no account ID found")
                        Log.d(TAG, "Response keys: ${apiResponse.token != null}, ${apiResponse.config != null}, ${apiResponse.account != null}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to parse response for $apiVersion", e)
                    Log.e(TAG, "Response body (first 1000 chars): ${responseBody.take(1000)}")
                    // Try to see if it's HTML or error page
                    if (responseBody.contains("<html", ignoreCase = true)) {
                        Log.e(TAG, "Response appears to be HTML, not JSON")
                    }
                }
            } else {
                Log.w(TAG, "⚠️ Unexpected status code $statusCode for $apiVersion")
                if (statusCode == 403) {
                    Log.e(TAG, "❌ 403 Forbidden - likely blocked by Cloudflare (datacenter IP or rate limit)")
                } else if (statusCode == 429) {
                    Log.e(TAG, "❌ 429 Too Many Requests - rate limited")
                }
                Log.d(TAG, "Response body: ${responseBody.take(500)}")
            }
            
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error with $apiVersion", e)
            null
        }
    }
    
    /**
     * Update account license key
     */
    suspend fun updateLicense(accountId: String, licenseKey: String): Result<WarpApiResponse> {
        // Ensure baseUrl is set (initialize if needed)
        if (baseUrl == null) {
            initializeFromAccount(accountId, authToken)
        }
        val baseUrl = baseUrl ?: return Result.failure(IllegalStateException("No working API version"))
        
        val payload = WarpUpdateLicenseRequest(license = licenseKey)
        val requestBody = json.encodeToString(WarpUpdateLicenseRequest.serializer(), payload)
            .toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$baseUrl/reg/$accountId/account")
            .put(requestBody)
            .apply {
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
                authToken?.let {
                    addHeader("Authorization", "Bearer $it")
                }
            }
            .build()
        
        return try {
            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }
            if (response.isSuccessful) {
                // Handle gzip compression like in registerAccount
                val responseBody = response.use { resp ->
                    val contentEncoding = resp.header("Content-Encoding") ?: ""
                    val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
                    
                    Log.d(TAG, "License update response - Content-Encoding: $contentEncoding, body size: ${bodyBytes.size}")
                    
                    // Check if response is gzip compressed
                    val isGzip = contentEncoding.contains("gzip", ignoreCase = true) ||
                            (bodyBytes.size >= 2 && bodyBytes[0] == 0x1F.toByte() && bodyBytes[1] == 0x8B.toByte())
                    
                    if (isGzip) {
                        Log.d(TAG, "License update response is gzip compressed, decompressing...")
                        try {
                            GZIPInputStream(ByteArrayInputStream(bodyBytes)).use { gzipStream ->
                                gzipStream.readBytes().toString(Charsets.UTF_8)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to decompress license update response", e)
                            bodyBytes.toString(Charsets.UTF_8)
                        }
                    } else {
                        bodyBytes.toString(Charsets.UTF_8)
                    }
                }
                
                if (responseBody.isBlank()) {
                    return Result.failure(IOException("Empty response"))
                }
                
                Log.d(TAG, "License update response body: $responseBody")
                val apiResponse = json.decodeFromString<WarpApiResponse>(responseBody)
                Result.success(apiResponse)
            } else {
                Result.failure(IOException("License update failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating license", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get account information
     */
    suspend fun getAccountInfo(accountId: String): Result<WarpApiResponse> {
        // Ensure baseUrl is set (initialize if needed)
        if (baseUrl == null) {
            initializeFromAccount(accountId, authToken)
        }
        val baseUrl = baseUrl ?: return Result.failure(IllegalStateException("No working API version"))
        
        val request = Request.Builder()
            .url("$baseUrl/reg/$accountId")
            .get()
            .apply {
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
                authToken?.let {
                    addHeader("Authorization", "Bearer $it")
                }
            }
            .build()
        
        return try {
            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }
            if (response.isSuccessful) {
                // Handle gzip compression like in other functions
                val responseBody = response.use { resp ->
                    val contentEncoding = resp.header("Content-Encoding") ?: ""
                    val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
                    
                    Log.d(TAG, "Account info response - Content-Encoding: $contentEncoding, body size: ${bodyBytes.size}")
                    
                    // Check if response is gzip compressed
                    val isGzip = contentEncoding.contains("gzip", ignoreCase = true) ||
                            (bodyBytes.size >= 2 && bodyBytes[0] == 0x1F.toByte() && bodyBytes[1] == 0x8B.toByte())
                    
                    if (isGzip) {
                        Log.d(TAG, "Account info response is gzip compressed, decompressing...")
                        try {
                            GZIPInputStream(ByteArrayInputStream(bodyBytes)).use { gzipStream ->
                                gzipStream.readBytes().toString(Charsets.UTF_8)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to decompress account info response", e)
                            bodyBytes.toString(Charsets.UTF_8)
                        }
                    } else {
                        bodyBytes.toString(Charsets.UTF_8)
                    }
                }
                
                if (responseBody.isBlank()) {
                    return Result.failure(IOException("Empty response"))
                }
                
                Log.d(TAG, "Account info response body: $responseBody")
                val apiResponse = json.decodeFromString<WarpApiResponse>(responseBody)
                Result.success(apiResponse)
            } else {
                Result.failure(IOException("Failed to get account info: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting account info", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get list of devices
     */
    suspend fun getDevices(accountId: String): Result<List<WarpDeviceResponse>> {
        // Ensure baseUrl is set (initialize if needed)
        if (baseUrl == null) {
            initializeFromAccount(accountId, authToken)
        }
        val baseUrl = baseUrl ?: return Result.failure(IllegalStateException("No working API version"))
        
        val request = Request.Builder()
            .url("$baseUrl/reg/$accountId/account/devices")
            .get()
            .apply {
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
                authToken?.let {
                    addHeader("Authorization", "Bearer $it")
                }
            }
            .build()
        
        return try {
            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }
            if (response.isSuccessful) {
                // Handle gzip compression like in registerAccount
                val responseBody = response.use { resp ->
                    val contentEncoding = resp.header("Content-Encoding") ?: ""
                    val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
                    
                    Log.d(TAG, "Devices response - Content-Encoding: $contentEncoding, body size: ${bodyBytes.size}")
                    
                    // Check if response is gzip compressed
                    val isGzip = contentEncoding.contains("gzip", ignoreCase = true) ||
                            (bodyBytes.size >= 2 && bodyBytes[0] == 0x1F.toByte() && bodyBytes[1] == 0x8B.toByte())
                    
                    if (isGzip) {
                        Log.d(TAG, "Devices response is gzip compressed, decompressing...")
                        try {
                            GZIPInputStream(ByteArrayInputStream(bodyBytes)).use { gzipStream ->
                                gzipStream.readBytes().toString(Charsets.UTF_8)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to decompress devices response", e)
                            bodyBytes.toString(Charsets.UTF_8)
                        }
                    } else {
                        bodyBytes.toString(Charsets.UTF_8)
                    }
                }
                
                if (responseBody.isBlank()) {
                    return Result.failure(IOException("Empty response"))
                }
                
                Log.d(TAG, "Devices response body: $responseBody")
                val devices = json.decodeFromString<List<WarpDeviceResponse>>(responseBody)
                Result.success(devices)
            } else {
                Result.failure(IOException("Failed to get devices: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting devices", e)
            Result.failure(e)
        }
    }
    
    /**
     * Remove a device from account
     */
    suspend fun removeDevice(accountId: String, deviceId: String): Result<Unit> {
        // Ensure baseUrl is set (initialize if needed)
        if (baseUrl == null) {
            initializeFromAccount(accountId, authToken)
        }
        val baseUrl = baseUrl ?: return Result.failure(IllegalStateException("No working API version"))
        
        val request = Request.Builder()
            .url("$baseUrl/reg/$accountId/account/reg/$deviceId")
            .delete()
            .apply {
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
                authToken?.let {
                    addHeader("Authorization", "Bearer $it")
                }
            }
            .build()
        
        return try {
            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(IOException("Failed to remove device: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Regenerate WireGuard keypair
     */
    suspend fun regenerateKey(accountId: String): Result<WarpApiResponse> {
        // Ensure baseUrl is set (initialize if needed)
        if (baseUrl == null) {
            initializeFromAccount(accountId, authToken)
        }
        val baseUrl = baseUrl ?: return Result.failure(IllegalStateException("No working API version"))
        
        val (privateKey, publicKey) = WireGuardKeyGenerator.generateKeyPair()
        val payload = WarpUpdateKeyRequest(key = publicKey)
        val requestBody = json.encodeToString(WarpUpdateKeyRequest.serializer(), payload)
            .toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$baseUrl/reg/$accountId")
            .patch(requestBody)
            .apply {
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
                authToken?.let {
                    addHeader("Authorization", "Bearer $it")
                }
            }
            .build()
        
        return try {
            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return Result.failure(IOException("Empty response"))
                val apiResponse = json.decodeFromString<WarpApiResponse>(responseBody)
                Result.success(apiResponse.copy(
                    privateKey = privateKey,
                    publicKey = publicKey
                ))
            } else {
                Result.failure(IOException("Failed to regenerate key: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Set auth token for authenticated requests
     */
    fun setAuthToken(token: String) {
        authToken = token
    }
    
    /**
     * Set working API version and base URL
     */
    fun setWorkingApiVersion(version: String) {
        workingApiVersion = version
        baseUrl = BASE_URL_TEMPLATE.replace("{version}", version)
    }
}

