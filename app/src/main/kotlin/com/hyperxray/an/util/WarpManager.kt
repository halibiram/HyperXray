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
 * Thread-safe singleton pattern to prevent multiple client instances.
 *
 * Usage:
 * ```kotlin
 * val warpManager = WarpManager.getInstance()
 * val result = warpManager.registerAndGetConfig()
 * ```
 */
class WarpManager private constructor() {
    
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
        private const val API_VERSION = "v0a2596"
        private const val BASE_URL = "https://api.cloudflareclient.com/$API_VERSION"
        private const val WARP_ENDPOINT = "engage.cloudflareclient.com"
        private const val WARP_PORT = 2408
        private const val WARP_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
        private const val WARP_MTU = 1280

        @Volatile
        private var INSTANCE: WarpManager? = null

        /**
         * Get singleton instance of WarpManager.
         * Thread-safe lazy initialization using double-checked locking pattern.
         *
         * @return Singleton instance of WarpManager
         */
        fun getInstance(): WarpManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WarpManager().also { INSTANCE = it }
            }
        }
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
        val persistentKeepalive: Int = 300 // 5 minutes
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
     * Save WARP account data to warp-account.json (account info only, not overwritten if exists)
     * Also saves WireGuard config to config/warp/ and MASQUE config to config/masque/
     * @param config WireGuard configuration
     * @param identity WARP identity from API response
     * @param filesDir Application files directory
     */
    private suspend fun saveWarpAccountToFile(config: WireGuardConfig, identity: WarpIdentity, filesDir: File? = null) {
        try {
            if (filesDir == null) {
                AiLogHelper.w("WarpManager", "Files directory not provided, cannot save WARP data")
                return
            }
            
            // Save account info only if warp-account.json doesn't exist (preserve existing account)
            val accountFile = File(filesDir, "warp-account.json")
            if (!accountFile.exists()) {
                val accountJson = JSONObject().apply {
                    put("identityId", identity.id)
                    put("token", identity.token)
                    put("warpEnabled", identity.warp_enabled)
                    put("accountId", identity.account.id)
                    identity.account.license?.let { put("license", it) }
                    identity.account.account_type?.let { put("accountType", it) }
                    // Store private key in account for backup purposes
                    put("privateKey", config.privateKey)
                }
                accountFile.writeText(accountJson.toString(2))
                AiLogHelper.d("WarpManager", "✅ WARP account saved to: ${accountFile.absolutePath}")
            } else {
                AiLogHelper.d("WarpManager", "⚠️ WARP account exists, preserving existing account")
            }
            
            // Always save WireGuard and MASQUE config files
            saveWireGuardConfig(config, filesDir)
            saveMasqueConfig(config, filesDir)
            
        } catch (e: Exception) {
            AiLogHelper.e("WarpManager", "Error saving WARP data: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Save WireGuard config to config/warp/wireguard.json
     * This is the config file that WireGuard will use
     */
    private fun saveWireGuardConfig(config: WireGuardConfig, filesDir: File) {
        try {
            val warpDir = File(filesDir, "config/warp")
            warpDir.mkdirs()
            
            val wgConfigFile = File(warpDir, "wireguard.json")
            val wgConfigJson = JSONObject().apply {
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
            }
            wgConfigFile.writeText(wgConfigJson.toString(2))
            AiLogHelper.d("WarpManager", "✅ WireGuard config saved to: ${wgConfigFile.absolutePath}")
        } catch (e: Exception) {
            AiLogHelper.e("WarpManager", "Error saving WireGuard config: ${e.message}", e)
        }
    }
    
    /**
     * Save MASQUE config to config/masque/masque.json
     * MASQUE uses HTTP/3 QUIC on port 443 for Cloudflare WARP
     */
    private fun saveMasqueConfig(config: WireGuardConfig, filesDir: File) {
        try {
            val masqueDir = File(filesDir, "config/masque")
            masqueDir.mkdirs()
            
            val masqueConfigFile = File(masqueDir, "masque.json")
            val warpHost = config.endpoint.substringBefore(":")
            val masqueConfigJson = JSONObject().apply {
                put("proxyEndpoint", "$warpHost:443")
                put("mode", "connect-udp")
                put("maxReconnects", 5)
                put("reconnectDelay", 1000)
                put("queueSize", 131072)
                put("mtu", 1420)
                put("wireguard", JSONObject().apply {
                    put("privateKey", config.privateKey)
                    put("publicKey", config.publicKey)
                    put("address", config.address)
                    put("addressV6", config.addressV6 ?: "")
                    put("peerPublicKey", config.peerPublicKey)
                })
            }
            masqueConfigFile.writeText(masqueConfigJson.toString(2))
            AiLogHelper.d("WarpManager", "✅ MASQUE config saved to: ${masqueConfigFile.absolutePath}")
        } catch (e: Exception) {
            AiLogHelper.e("WarpManager", "Error saving MASQUE config: ${e.message}", e)
        }
    }
    
    /**
     * Load WireGuard config from config files
     * Priority: 1) config/warp/wireguard.json, 2) create from warp-account.json
     * @param filesDir Application files directory
     * @return WireGuardConfig if config exists and is valid, null otherwise
     */
    suspend fun loadWarpAccountFromFile(filesDir: File): WireGuardConfig? = withContext(Dispatchers.IO) {
        try {
            val warpConfigFile = File(filesDir, "config/warp/wireguard.json")
            val accountFile = File(filesDir, "warp-account.json")
            
            // Try loading from config/warp/wireguard.json first
            if (warpConfigFile.exists() && warpConfigFile.canRead()) {
                AiLogHelper.d("WarpManager", "📁 Loading WireGuard config from: ${warpConfigFile.absolutePath}")
                val config = parseWireGuardConfig(warpConfigFile)
                if (config != null) {
                    return@withContext config
                }
                AiLogHelper.w("WarpManager", "⚠️ WireGuard config invalid, trying warp-account.json")
            }
            
            // If wireguard.json doesn't exist or is invalid, create from warp-account.json
            if (accountFile.exists() && accountFile.canRead()) {
                AiLogHelper.d("WarpManager", "📁 Creating WireGuard config from warp-account.json")
                val config = createWireGuardConfigFromAccount(accountFile, filesDir)
                if (config != null) {
                    return@withContext config
                }
            }
            
            AiLogHelper.d("WarpManager", "No WARP config found")
            null
        } catch (e: Exception) {
            AiLogHelper.e("WarpManager", "Error loading WARP config: ${e.message}", e)
            null
        }
    }
    
    /**
     * Parse WireGuard config from JSON file
     */
    private fun parseWireGuardConfig(configFile: File): WireGuardConfig? {
        return try {
            val configJson = JSONObject(configFile.readText())
            
            val privateKey = configJson.optString("privateKey", "")
            if (privateKey.isBlank() || !WarpUtils.isValidPrivateKey(privateKey)) {
                AiLogHelper.w("WarpManager", "Invalid privateKey in config")
                return null
            }
            
            val publicKey = try {
                WarpUtils.derivePublicKey(privateKey)
            } catch (e: Exception) {
                AiLogHelper.e("WarpManager", "Failed to derive public key: ${e.message}")
                return null
            }
            
            val config = WireGuardConfig(
                privateKey = privateKey,
                publicKey = publicKey,
                address = configJson.optString("address", "172.16.0.2/32"),
                addressV6 = configJson.optString("addressV6", "").takeIf { it.isNotBlank() },
                dns = configJson.optString("dns", "1.1.1.1"),
                mtu = configJson.optInt("mtu", WARP_MTU),
                peerPublicKey = configJson.optString("peerPublicKey", WARP_PUBLIC_KEY),
                endpoint = configJson.optString("endpoint", "$WARP_ENDPOINT:$WARP_PORT"),
                allowedIPs = configJson.optString("allowedIPs", "0.0.0.0/0, ::/0"),
                persistentKeepalive = configJson.optInt("persistentKeepalive", 300)
            )
            
            if (config.isValid()) config else null
        } catch (e: Exception) {
            AiLogHelper.e("WarpManager", "Error parsing WireGuard config: ${e.message}")
            null
        }
    }
    
    /**
     * Create WireGuard config from warp-account.json and save to config/warp/
     */
    private fun createWireGuardConfigFromAccount(accountFile: File, filesDir: File): WireGuardConfig? {
        return try {
            val accountJson = JSONObject(accountFile.readText())
            
            // Get privateKey from account
            val privateKey = accountJson.optString("privateKey", "")
            if (privateKey.isBlank() || !WarpUtils.isValidPrivateKey(privateKey)) {
                AiLogHelper.w("WarpManager", "warp-account.json has no valid privateKey")
                return null
            }
            
            val publicKey = try {
                WarpUtils.derivePublicKey(privateKey)
            } catch (e: Exception) {
                AiLogHelper.e("WarpManager", "Failed to derive public key: ${e.message}")
                return null
            }
            
            // Create WireGuard config with WARP defaults
            val config = WireGuardConfig(
                privateKey = privateKey,
                publicKey = publicKey,
                address = "172.16.0.2/32",
                addressV6 = null,
                dns = "1.1.1.1",
                mtu = WARP_MTU,
                peerPublicKey = WARP_PUBLIC_KEY,
                endpoint = "$WARP_ENDPOINT:$WARP_PORT",
                allowedIPs = "0.0.0.0/0, ::/0",
                persistentKeepalive = 300 // 5 minutes
            )
            
            if (!config.isValid()) {
                AiLogHelper.w("WarpManager", "Generated config is invalid")
                return null
            }
            
            // Save WireGuard config to config/warp/wireguard.json
            saveWireGuardConfig(config, filesDir)
            // Also save MASQUE config
            saveMasqueConfig(config, filesDir)
            
            AiLogHelper.d("WarpManager", "✅ Created WireGuard config from warp-account.json")
            config
        } catch (e: Exception) {
            AiLogHelper.e("WarpManager", "Error creating config from account: ${e.message}")
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
                .header("CF-Client-Version", "a-6.30-2596")
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

