package com.hyperxray.an.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.hyperxray.an.common.AiLogHelper
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import com.hyperxray.an.utils.WarpUtils
import org.json.JSONObject

/**
 * WarpManager generates WireGuard configuration from Cloudflare WARP API.
 * This allows users to use free Cloudflare WARP as their WireGuard tunnel.
 * 
 * This is a critical component for the WireGuard over Xray architecture.
 * 
 * Usage:
 * ```kotlin
 * val warpManager = WarpManager()
 * val result = warpManager.registerAndGetConfig()
 * ```
 */
class WarpManager {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    companion object {
        private const val API_VERSION = "v0a2483"
        private const val BASE_URL = "https://api.cloudflareclient.com/$API_VERSION"
        private const val WARP_ENDPOINT = "engage.cloudflareclient.com"
        private const val WARP_PORT = 2408
        private const val WARP_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
        private const val WARP_MTU = 1280
    }
    
    // Data Classes for API
    @Serializable
    data class WarpIdentity(
        val id: String,
        val account: WarpAccount,
        val token: String,
        val warp_enabled: Boolean,
        val config: WarpConfig
    )
    
    @Serializable
    data class WarpAccount(
        val id: String,
        val license: String? = null,
        val account_type: String? = null
    )
    
    @Serializable
    data class WarpConfig(
        val peers: List<WarpPeer>,
        val interface_config: InterfaceConfig? = null,
        val client_id: String? = null
    )
    
    @Serializable
    data class WarpPeer(
        val public_key: String,
        val endpoint: WarpEndpoint
    )
    
    @Serializable
    data class WarpEndpoint(
        val host: String,
        val v4: String? = null,
        val v6: String? = null
    )
    
    @Serializable
    data class InterfaceConfig(
        val addresses: Addresses? = null
    )
    
    @Serializable
    data class Addresses(
        val v4: String? = null,
        val v6: String? = null
    )
    
    @Serializable
    data class RegistrationRequest(
        val key: String,
        val install_id: String = "",
        val warp_enabled: Boolean = true,
        val tos: String,
        val type: String = "Android",
        val locale: String = "en_US",
        val model: String = "Android",
        val fcm_token: String = ""
    )
    
    // Generated WireGuard Config
    data class WireGuardConfig(
        val privateKey: String,
        val publicKey: String,
        val address: String,
        val addressV6: String?,
        val dns: String = "1.1.1.1",
        val mtu: Int = WARP_MTU,
        val peerPublicKey: String = WARP_PUBLIC_KEY,
        val endpoint: String = "$WARP_ENDPOINT:$WARP_PORT",
        val allowedIPs: String = "0.0.0.0/0, ::/0",
        val persistentKeepalive: Int = 25
    ) {
        fun toConfigString(): String = """
            [Interface]
            PrivateKey = $privateKey
            Address = $address${addressV6?.let { ", $it" } ?: ""}
            DNS = $dns
            MTU = $mtu
            
            [Peer]
            PublicKey = $peerPublicKey
            Endpoint = $endpoint
            AllowedIPs = $allowedIPs
            PersistentKeepalive = $persistentKeepalive
        """.trimIndent()
        
        fun toJsonMap(): Map<String, Any> = mapOf(
            "privateKey" to privateKey,
            "publicKey" to publicKey,
            "address" to address,
            "addressV6" to (addressV6 ?: ""),
            "dns" to dns,
            "mtu" to mtu,
            "peerPublicKey" to peerPublicKey,
            "endpoint" to endpoint,
            "allowedIPs" to allowedIPs,
            "persistentKeepalive" to persistentKeepalive
        )
        
        /**
         * Convert to JSON string for native layer
         * Validates the config before serialization
         */
        fun toJsonString(): String {
            // Validate config before serialization
            if (privateKey.isBlank()) {
                throw IllegalArgumentException("WireGuard privateKey cannot be blank")
            }
            if (endpoint.isBlank()) {
                throw IllegalArgumentException("WireGuard endpoint cannot be blank")
            }
            if (peerPublicKey.isBlank()) {
                throw IllegalArgumentException("WireGuard peerPublicKey cannot be blank")
            }
            if (address.isBlank()) {
                throw IllegalArgumentException("WireGuard address cannot be blank")
            }
            
            // Use kotlinx.serialization for proper JSON encoding
            return try {
                val jsonMap = toJsonMap()
                // Convert map to JSON string using org.json.JSONObject for compatibility
                org.json.JSONObject(jsonMap).toString()
            } catch (e: Exception) {
                throw IllegalArgumentException("Failed to serialize WireGuard config to JSON: ${e.message}", e)
            }
        }
        
        /**
         * Validate configuration
         */
        fun isValid(): Boolean {
            return privateKey.isNotBlank() &&
                   publicKey.isNotBlank() &&
                   address.isNotBlank() &&
                   peerPublicKey.isNotBlank() &&
                   endpoint.isNotBlank()
        }
    }
    
    /**
     * Generate WireGuard private key (32 random bytes, clamped for Curve25519)
     * Uses existing WarpUtils for key generation
     */
    private fun generatePrivateKey(): String {
        return WarpUtils.generatePrivateKey()
    }
    
    /**
     * Generate public key from private key
     * Uses existing WarpUtils for key derivation
     */
    private fun generatePublicKey(privateKey: String): String {
        return WarpUtils.derivePublicKey(privateKey)
    }
    
    /**
     * Save WARP account data to warp-account.json file
     * @param config WireGuard configuration
     * @param identity WARP identity from API response
     * @param filesDir Application files directory
     */
    private suspend fun saveWarpAccountToFile(config: WireGuardConfig, identity: WarpIdentity, filesDir: File? = null) {
        try {
            // If filesDir is not provided, we can't save
            if (filesDir == null) {
                AiLogHelper.w("WarpManager", "Files directory not provided, cannot save WARP account")
                return
            }
            
            val accountFile = File(filesDir, "warp-account.json")
            val accountJson = JSONObject().apply {
                put("privateKey", config.privateKey)
                put("publicKey", config.publicKey)
                put("address", config.address)
                put("addressV6", config.addressV6 ?: "")
                put("dns", config.dns)
                put("mtu", config.mtu)
                put("peerPublicKey", config.peerPublicKey)
                put("endpoint", config.endpoint)
                put("allowedIPs", config.allowedIPs)
                put("persistentKeepalive", config.persistentKeepalive)
                // Save additional WARP identity info
                put("identityId", identity.id)
                put("token", identity.token)
                put("warpEnabled", identity.warp_enabled)
                identity.account.license?.let { put("license", it) }
                identity.account.account_type?.let { put("accountType", it) }
            }
            
            accountFile.writeText(accountJson.toString())
            AiLogHelper.d("WarpManager", "✅ WARP account saved to: ${accountFile.absolutePath}")
        } catch (e: Exception) {
            AiLogHelper.e("WarpManager", "Error saving WARP account file: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Load WARP account data from warp-account.json file
     * @param filesDir Application files directory
     * @return WireGuardConfig if account file exists and is valid, null otherwise
     */
    suspend fun loadWarpAccountFromFile(filesDir: File): WireGuardConfig? = withContext(Dispatchers.IO) {
        try {
            val accountFile = File(filesDir, "warp-account.json")
            if (!accountFile.exists() || !accountFile.canRead()) {
                AiLogHelper.d("WarpManager", "WARP account file not found: ${accountFile.absolutePath}")
                return@withContext null
            }
            
            val accountContent = accountFile.readText()
            val accountJson = JSONObject(accountContent)
            
            // Extract private key
            val privateKey = accountJson.optString("privateKey", "")
            if (privateKey.isBlank() || !WarpUtils.isValidPrivateKey(privateKey)) {
                AiLogHelper.w("WarpManager", "WARP account file has invalid or missing privateKey")
                return@withContext null
            }
            
            // Derive public key from private key
            val publicKey = try {
                WarpUtils.derivePublicKey(privateKey)
            } catch (e: Exception) {
                AiLogHelper.e("WarpManager", "Failed to derive public key: ${e.message}")
                return@withContext null
            }
            
            // Extract other config values
            val address = accountJson.optString("address", "172.16.0.2/32")
            val addressV6 = accountJson.optString("addressV6", "").takeIf { it.isNotBlank() }
            val endpoint = accountJson.optString("endpoint", "$WARP_ENDPOINT:$WARP_PORT")
            val peerPublicKey = accountJson.optString("peerPublicKey", WARP_PUBLIC_KEY)
            val allowedIPs = accountJson.optString("allowedIPs", "0.0.0.0/0, ::/0")
            val persistentKeepalive = accountJson.optInt("persistentKeepalive", 25)
            val dns = accountJson.optString("dns", "1.1.1.1")
            val mtu = accountJson.optInt("mtu", WARP_MTU)
            
            val config = WireGuardConfig(
                privateKey = privateKey,
                publicKey = publicKey,
                address = address,
                addressV6 = addressV6,
                dns = dns,
                mtu = mtu,
                peerPublicKey = peerPublicKey,
                endpoint = endpoint,
                allowedIPs = allowedIPs,
                persistentKeepalive = persistentKeepalive
            )
            
            if (!config.isValid()) {
                AiLogHelper.w("WarpManager", "WARP account config validation failed")
                return@withContext null
            }
            
            AiLogHelper.d("WarpManager", "✅ Loaded WARP account from file: ${accountFile.absolutePath}")
            config
        } catch (e: Exception) {
            AiLogHelper.e("WarpManager", "Error loading WARP account file: ${e.message}", e)
            null
        }
    }
    
    /**
     * Register a new WARP identity and get WireGuard configuration
     * @param filesDir Optional files directory to save account data
     */
    suspend fun registerAndGetConfig(filesDir: File? = null): Result<WireGuardConfig> = withContext(Dispatchers.IO) {
        try {
            AiLogHelper.d("WarpManager", "Starting WARP registration")
            
            // Generate WireGuard keypair using existing utilities
            val privateKey = generatePrivateKey()
            val publicKey = generatePublicKey(privateKey)
            
            if (publicKey.isEmpty()) {
                return@withContext Result.failure(Exception("Failed to generate public key"))
            }
            
            // Current timestamp for TOS
            val tos = java.time.OffsetDateTime.now().toString()
            
            // Generate install ID (random UUID-like string)
            val installId = java.util.UUID.randomUUID().toString().replace("-", "")
            
            // Create registration request
            val registrationRequest = RegistrationRequest(
                key = publicKey,
                install_id = installId,
                tos = tos
            )
            
            val requestBody = json.encodeToString(
                RegistrationRequest.serializer(), 
                registrationRequest
            ).toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$BASE_URL/reg")
                .post(requestBody)
                .header("Content-Type", "application/json")
                .header("User-Agent", "okhttp/4.12.0")
                .header("CF-Client-Version", "a-6.28-2483")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                AiLogHelper.e("WarpManager", "Registration failed: ${response.code} - $errorBody")
                return@withContext Result.failure(
                    Exception("Registration failed: ${response.code}")
                )
            }
            
            val responseBody = response.body?.string() 
                ?: return@withContext Result.failure(Exception("Empty response"))
            
            AiLogHelper.d("WarpManager", "Registration response received")
            
            val identity = json.decodeFromString(WarpIdentity.serializer(), responseBody)
            
            // Extract addresses from response
            val addressV4 = identity.config.interface_config?.addresses?.v4 
                ?: "172.16.0.2/32"
            val addressV6 = identity.config.interface_config?.addresses?.v6
            
            // Get peer endpoint
            // Check if host already contains port (format: "host:port")
            val peerEndpoint = identity.config.peers.firstOrNull()?.let { peer ->
                val host = peer.endpoint.host
                // If host already contains port (has colon), use it as-is
                if (host.contains(":")) {
                    host
                } else {
                    "$host:$WARP_PORT"
                }
            } ?: "$WARP_ENDPOINT:$WARP_PORT"
            
            val config = WireGuardConfig(
                privateKey = privateKey,
                publicKey = publicKey,
                address = addressV4,
                addressV6 = addressV6,
                endpoint = peerEndpoint
            )
            
            AiLogHelper.d("WarpManager", "Config generated successfully")
            
            // Save account data to file for future use
            if (filesDir != null) {
                try {
                    saveWarpAccountToFile(config, identity, filesDir)
                    AiLogHelper.d("WarpManager", "✅ WARP account saved to file")
                } catch (e: Exception) {
                    AiLogHelper.w("WarpManager", "⚠️ Failed to save WARP account to file: ${e.message}")
                    // Continue anyway - config is still valid
                }
            }
            
            Result.success(config)
            
        } catch (e: Exception) {
            AiLogHelper.e("WarpManager", "Registration error: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Update existing registration with license key (for WARP+)
     */
    suspend fun updateLicense(
        identityId: String,
        token: String,
        licenseKey: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val requestBody = """{"license": "$licenseKey"}"""
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$BASE_URL/reg/$identityId/account")
                .put(requestBody)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("License update failed: ${response.code}")
                )
            }
            
            Result.success(Unit)
            
        } catch (e: Exception) {
            AiLogHelper.e("WarpManager", "License update error: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Check if WARP is working by verifying trace
     */
    suspend fun verifyWarpConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.cloudflare.com/cdn-cgi/trace/")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            
            val isWarpOn = body.contains("warp=on") || body.contains("warp=plus")
            Result.success(isWarpOn)
            
        } catch (e: Exception) {
            AiLogHelper.e("WarpManager", "Verify connection error: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get best WARP endpoint based on latency
     */
    suspend fun getBestEndpoint(): String = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "engage.cloudflareclient.com:2408",
            "162.159.192.1:2408",
            "162.159.193.1:2408",
            "162.159.195.1:2408"
        )
        
        // For now, return default. Can implement latency check later
        endpoints.first()
    }
}

