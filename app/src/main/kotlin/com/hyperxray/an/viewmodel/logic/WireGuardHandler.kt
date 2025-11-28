package com.hyperxray.an.viewmodel.logic

import android.util.Log
import com.hyperxray.an.core.network.warp.WarpApiManager
import com.hyperxray.an.core.network.warp.WarpRepository
import com.hyperxray.an.utils.WarpDefaults
import com.hyperxray.an.utils.WarpUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "WireGuardHandler"

/**
 * Handler for WireGuard/WARP protocol logic.
 * Manages private/public key generation, WARP API interactions,
 * endpoint IP rotation, and reserved bytes calculation.
 */
class WireGuardHandler(
    private val warpRepository: WarpRepository = WarpRepository()
) {
    // WARP settings state
    private val _enableWarp = MutableStateFlow<Boolean>(false)
    val enableWarp: StateFlow<Boolean> = _enableWarp.asStateFlow()

    private val _warpPrivateKey = MutableStateFlow<String>("")
    val warpPrivateKey: StateFlow<String> = _warpPrivateKey.asStateFlow()

    private val _warpPeerPublicKey = MutableStateFlow<String>(WarpDefaults.PEER_PUBLIC_KEY)
    val warpPeerPublicKey: StateFlow<String> = _warpPeerPublicKey.asStateFlow()

    private val _warpEndpoint = MutableStateFlow<String>(WarpDefaults.ENDPOINT)
    val warpEndpoint: StateFlow<String> = _warpEndpoint.asStateFlow()

    private val _warpLocalAddress = MutableStateFlow<String>(WarpDefaults.LOCAL_ADDRESS)
    val warpLocalAddress: StateFlow<String> = _warpLocalAddress.asStateFlow()

    private val _warpClientId = MutableStateFlow<String?>(null)
    val warpClientId: StateFlow<String?> = _warpClientId.asStateFlow()

    private val _warpLicenseKey = MutableStateFlow<String>("")
    val warpLicenseKey: StateFlow<String> = _warpLicenseKey.asStateFlow()

    private val _warpAccountType = MutableStateFlow<String?>(null)
    val warpAccountType: StateFlow<String?> = _warpAccountType.asStateFlow()

    private val _warpQuota = MutableStateFlow<String>("Unknown")
    val warpQuota: StateFlow<String> = _warpQuota.asStateFlow()

    private val _isBindingLicense = MutableStateFlow<Boolean>(false)
    val isBindingLicense: StateFlow<Boolean> = _isBindingLicense.asStateFlow()

    /**
     * Update WARP private key StateFlow without updating config.
     * This allows users to type the key without triggering config updates on every keystroke.
     */
    fun setWarpPrivateKey(key: String) {
        _warpPrivateKey.value = key
    }

    /**
     * Update WARP license key input.
     */
    fun updateLicenseKeyInput(licenseKey: String) {
        _warpLicenseKey.value = licenseKey
    }

    /**
     * Calculate quota display string from account type and quota bytes.
     */
    private fun calculateQuotaFromAccountType(accountType: String?, quotaBytes: Long? = null): String {
        // If quota is provided, format it as GB
        if (quotaBytes != null && quotaBytes > 0) {
            val gb = quotaBytes / (1024.0 * 1024.0 * 1024.0)
            return String.format("%.2f GB", gb)
        }
        
        // Fallback to account type-based display
        return when {
            accountType == null -> "Unknown"
            accountType.equals("plus", ignoreCase = true) -> "Unlimited"
            accountType.equals("unlimited", ignoreCase = true) -> "Unlimited"
            accountType.equals("premium", ignoreCase = true) -> "Unlimited"
            accountType.equals("free", ignoreCase = true) -> "Limited"
            else -> "Unknown"
        }
    }

    /**
     * Parse WireGuard over Xray settings from config JSON.
     * Checks for WireGuard outbound and routing rules.
     */
    fun parseWarpSettings(jsonObject: JSONObject) {
        try {
            val outbounds = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
            if (outbounds == null) {
                _enableWarp.value = false
                return
            }

            // Check if WireGuard outbound exists
            var hasWarpOutbound = false
            var warpOutbound: JSONObject? = null

            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.getJSONObject(i)
                if (outbound.optString("protocol") == "wireguard" &&
                    outbound.optString("tag") == "warp-out") {
                    hasWarpOutbound = true
                    warpOutbound = outbound
                    break
                }
            }

            if (!hasWarpOutbound) {
                _enableWarp.value = false
                return
            }

            // Check if routing rules exist for WireGuard outbound
            var hasWireGuardRoutingRule = false
            val routingObject = jsonObject.optJSONObject("routing")
            if (routingObject != null) {
                val rulesArray = routingObject.optJSONArray("rules")
                if (rulesArray != null) {
                    for (i in 0 until rulesArray.length()) {
                        val rule = rulesArray.getJSONObject(i)
                        val outboundTag = rule.optString("outboundTag", "")
                        if (outboundTag == "warp-out") {
                            hasWireGuardRoutingRule = true
                            break
                        }
                    }
                }
            }

            if (!hasWireGuardRoutingRule) {
                _enableWarp.value = false
                return
            }

            // Parse WireGuard settings
            _enableWarp.value = true

            val settings = warpOutbound?.optJSONObject("settings")
            if (settings != null) {
                val secretKey = settings.optString("secretKey", "")
                _warpPrivateKey.value = secretKey

                val peers = settings.optJSONArray("peers")
                if (peers != null && peers.length() > 0) {
                    val peer = peers.getJSONObject(0)
                    val publicKey = peer.optString("publicKey", "")
                    val endpoint = peer.optString("endpoint", "")

                    if (publicKey.isNotEmpty()) {
                        _warpPeerPublicKey.value = publicKey
                    }
                    if (endpoint.isNotEmpty()) {
                        _warpEndpoint.value = endpoint
                    }
                }

                // Xray-core stores address as an array, parse it back to comma-separated string
                val addressObj = settings.opt("address")
                val address = when {
                    addressObj is String -> addressObj
                    addressObj is org.json.JSONArray -> {
                        (0 until addressObj.length())
                            .mapNotNull { addressObj.optString(it, null) }
                            .joinToString(", ")
                    }
                    else -> ""
                }
                if (address.isNotEmpty()) {
                    _warpLocalAddress.value = address
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse WARP settings: ${e.message}")
            _enableWarp.value = false
        }
    }

    /**
     * Update WireGuard over Xray settings in config JSON.
     * Always uses standalone WireGuard outbound with routing rules (WireGuard over Xray mode).
     */
    fun updateWarpSettings(
        configJson: JSONObject,
        enabled: Boolean,
        privateKey: String = "",
        endpoint: String = "",
        localAddress: String = ""
    ): Result<String> {
        // Use defaults if not provided
        val finalEndpoint = endpoint.ifEmpty { _warpEndpoint.value.ifEmpty { WarpDefaults.ENDPOINT } }
        val finalLocalAddress = localAddress.ifEmpty { _warpLocalAddress.value.ifEmpty { WarpDefaults.LOCAL_ADDRESS } }
        return try {
            val outbounds = configJson.optJSONArray("outbounds") ?: configJson.optJSONArray("outbound")

            if (outbounds == null) {
                return Result.failure(IllegalStateException("No outbounds found in config"))
            }

            if (enabled) {
                // Find or create WireGuard outbound
                var warpOutboundIndex = -1
                for (i in 0 until outbounds.length()) {
                    val outbound = outbounds.getJSONObject(i)
                    if (outbound.optString("protocol") == "wireguard" &&
                        outbound.optString("tag") == "warp-out") {
                        warpOutboundIndex = i
                        break
                    }
                }

                val warpOutbound = if (warpOutboundIndex >= 0) {
                    outbounds.getJSONObject(warpOutboundIndex)
                } else {
                    val newWarpOutbound = JSONObject()
                    newWarpOutbound.put("protocol", "wireguard")
                    newWarpOutbound.put("tag", "warp-out")
                    outbounds.put(newWarpOutbound)
                    newWarpOutbound
                }

                // Configure WireGuard settings
                val settings = JSONObject()

                // Use provided privateKey, or current StateFlow value, or generate new one
                val keyToUse = when {
                    privateKey.isNotEmpty() -> privateKey
                    _warpPrivateKey.value.isNotEmpty() -> _warpPrivateKey.value
                    else -> ""
                }

                val finalPrivateKey = when {
                    keyToUse.isNotEmpty() -> {
                        if (keyToUse.length == 44) {
                            if (!WarpUtils.isValidPrivateKey(keyToUse)) {
                                throw IllegalArgumentException("Invalid WARP private key format. Expected 44-character Base64-encoded 32-byte key.")
                            }
                            keyToUse
                        } else if (keyToUse.length > 0 && keyToUse.length < 44) {
                            Log.d(TAG, "Using incomplete key (${keyToUse.length}/44 chars) - user is typing")
                            keyToUse
                        } else {
                            throw IllegalArgumentException("Invalid WARP private key format. Expected 44-character Base64-encoded 32-byte key (got ${keyToUse.length} chars).")
                        }
                    }
                    else -> {
                        try {
                            val (newPrivateKey, _) = WarpUtils.generateWarpKeys()
                            if (newPrivateKey.isEmpty() || !WarpUtils.isValidPrivateKey(newPrivateKey)) {
                                throw IllegalStateException("Generated WARP private key is invalid or empty")
                            }
                            Log.i(TAG, "Generated new WARP private key (no key provided)")
                            newPrivateKey
                        } catch (e: IllegalStateException) {
                            throw IllegalStateException("Failed to generate WARP private key: ${e.message}. Please ensure BouncyCastle is properly configured.", e)
                        } catch (e: Exception) {
                            throw IllegalStateException("Unexpected error generating WARP private key: ${e.message}", e)
                        }
                    }
                }

                // Validate endpoint format
                if (!WarpUtils.isValidEndpoint(finalEndpoint)) {
                    throw IllegalArgumentException("Invalid WARP endpoint format: $finalEndpoint (expected format: host:port or IP:port)")
                }

                // Validate local address format
                if (!WarpUtils.isValidLocalAddress(finalLocalAddress)) {
                    throw IllegalArgumentException("Invalid WARP local address format: $finalLocalAddress (expected CIDR format, e.g., 172.16.0.2/32)")
                }

                settings.put("secretKey", finalPrivateKey)
                settings.put("mtu", 1280)
                settings.put("workers", 2)

                val peers = JSONArray()
                val peer = JSONObject()
                peer.put("publicKey", WarpDefaults.PEER_PUBLIC_KEY)

                // Randomize endpoint selection for better reliability if using default
                val randomizedEndpoint = if (finalEndpoint == WarpDefaults.ENDPOINT) {
                    val ipv4Endpoints = WarpDefaults.WARP_IPS_V4
                    if (ipv4Endpoints.isNotEmpty()) {
                        ipv4Endpoints[kotlin.random.Random.nextInt(ipv4Endpoints.size)]
                    } else {
                        val ipv6Endpoints = WarpDefaults.WARP_IPS_V6
                        if (ipv6Endpoints.isNotEmpty()) {
                            ipv6Endpoints[kotlin.random.Random.nextInt(ipv6Endpoints.size)]
                        } else {
                            WarpDefaults.ENDPOINT
                        }
                    }
                } else {
                    finalEndpoint
                }
                peer.put("endpoint", randomizedEndpoint)

                // Add reserved bytes [0,0,0] for WARP handshake
                val reservedArray = JSONArray()
                reservedArray.put(0)
                reservedArray.put(0)
                reservedArray.put(0)
                peer.put("reserved", reservedArray)

                peers.put(peer)
                settings.put("peers", peers)

                // Xray-core expects address as an array, only use IPv4 addresses
                val addressArray = JSONArray()
                finalLocalAddress.split(",").forEach { addr ->
                    val trimmedAddr = addr.trim()
                    if (trimmedAddr.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/\\d+$"))) {
                        addressArray.put(trimmedAddr)
                    } else {
                        Log.w(TAG, "⚠️ Skipping IPv6 address in WARP config: $trimmedAddr")
                    }
                }

                if (addressArray.length() == 0) {
                    addressArray.put("172.16.0.2/32")
                    Log.d(TAG, "✅ Added default WARP IPv4 address: 172.16.0.2/32")
                }

                settings.put("address", addressArray)
                warpOutbound.put("settings", settings)

                // WireGuard over Xray mode: Always use routing rules (standalone mode)
                Log.i(TAG, "🔧 WireGuard over Xray: Creating standalone WireGuard outbound with routing rules")
                ensureWireGuardRoutingRules(configJson, "warp-out")

                // Update JSON array
                if (configJson.has("outbounds")) {
                    configJson.put("outbounds", outbounds)
                } else {
                    configJson.put("outbound", outbounds)
                }

                // Update state
                _warpPrivateKey.value = finalPrivateKey
                _warpEndpoint.value = randomizedEndpoint
                _warpLocalAddress.value = finalLocalAddress
                _enableWarp.value = true

                Log.i(TAG, "✅ WireGuard over Xray configuration updated successfully")
                Result.success(configJson.toString(2))
            } else {
                // Remove WireGuard outbound and routing rules
                val newOutbounds = JSONArray()
                for (i in 0 until outbounds.length()) {
                    val outbound = outbounds.getJSONObject(i)
                    if (outbound.optString("protocol") == "wireguard" &&
                        outbound.optString("tag") == "warp-out") {
                        continue
                    }
                    newOutbounds.put(outbound)
                }
                
                // Remove WireGuard routing rules
                removeWireGuardRoutingRules(configJson, "warp-out")

                if (configJson.has("outbounds")) {
                    configJson.put("outbounds", newOutbounds)
                } else {
                    configJson.put("outbound", newOutbounds)
                }

                _enableWarp.value = false
                Result.success(configJson.toString(2))
            }
        } catch (e: Exception) {
            val errorMessage = when {
                e is IllegalArgumentException -> "WARP configuration error: ${e.message}"
                e is IllegalStateException -> "WARP key generation error: ${e.message}"
                else -> "Failed to update WARP settings: ${e.message}"
            }
            Log.e(TAG, errorMessage, e)
            Result.failure(Exception(errorMessage, e))
        }
    }

    /**
     * Remove WireGuard routing rules from config.
     */
    private fun removeWireGuardRoutingRules(configJson: JSONObject, wireguardTag: String) {
        try {
            val routingObject = configJson.optJSONObject("routing") ?: return
            val rulesArray = routingObject.optJSONArray("rules") ?: return
            
            val newRulesArray = JSONArray()
            for (i in 0 until rulesArray.length()) {
                val rule = rulesArray.getJSONObject(i)
                val outboundTag = rule.optString("outboundTag", "")
                if (outboundTag != wireguardTag) {
                    newRulesArray.put(rule)
                }
            }
            
            if (newRulesArray.length() != rulesArray.length()) {
                routingObject.put("rules", newRulesArray)
                configJson.put("routing", routingObject)
                Log.i(TAG, "✅ Removed WireGuard routing rules")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove WireGuard routing rules: ${e.message}", e)
        }
    }

    /**
     * Ensure WireGuard outbound has proper routing rules for standalone mode.
     * Creates routing rules to route all traffic through WireGuard outbound.
     */
    private fun ensureWireGuardRoutingRules(configJson: JSONObject, wireguardTag: String) {
        try {
            // Get or create routing object
            var routingObject = configJson.optJSONObject("routing")
            if (routingObject == null) {
                routingObject = JSONObject()
                configJson.put("routing", routingObject)
            }

            // Get or create rules array
            var rulesArray = routingObject.optJSONArray("rules")
            if (rulesArray == null) {
                rulesArray = JSONArray()
                routingObject.put("rules", rulesArray)
            }

            // Check if WireGuard routing rule already exists
            var hasWireGuardRule = false
            for (i in 0 until rulesArray.length()) {
                val rule = rulesArray.getJSONObject(i)
                val outboundTag = rule.optString("outboundTag", "")
                if (outboundTag == wireguardTag) {
                    hasWireGuardRule = true
                    break
                }
            }

            // Add WireGuard routing rule if not exists
            if (!hasWireGuardRule) {
                val wireguardRule = JSONObject()
                wireguardRule.put("type", "field")
                wireguardRule.put("outboundTag", wireguardTag)
                
                // Insert at index 0 (highest priority) to route all traffic through WireGuard
                val newRulesArray = JSONArray()
                newRulesArray.put(wireguardRule)
                for (i in 0 until rulesArray.length()) {
                    newRulesArray.put(rulesArray.get(i))
                }
                routingObject.put("rules", newRulesArray)
                configJson.put("routing", routingObject)
                
                Log.i(TAG, "✅ Added WireGuard routing rule: All traffic → $wireguardTag")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add WireGuard routing rules: ${e.message}", e)
        }
    }

    /**
     * Generate new WARP keys and return private key.
     */
    fun generateWarpKeys(): Pair<String, String> {
        return WarpUtils.generateWarpKeys()
    }

    /**
     * Generate WARP identity via Cloudflare API registration.
     */
    suspend fun generateWarpIdentity(): Result<WarpIdentityResult> = withContext(Dispatchers.IO) {
        try {
            val result = WarpApiManager.generateWarpIdentity()

            if (result.success && result.privateKey.isNotEmpty()) {
                val localAddress = result.localAddress ?: _warpLocalAddress.value

                if (result.clientId != null) {
                    _warpClientId.value = result.clientId
                }

                if (result.clientId != null && result.token != null) {
                    warpRepository.setCachedIdentity(
                        result.clientId,
                        result.token,
                        result.privateKey
                    )
                }

                if (result.accountType != null) {
                    _warpAccountType.value = result.accountType
                    _warpQuota.value = calculateQuotaFromAccountType(result.accountType)
                }

                Result.success(
                    WarpIdentityResult(
                        privateKey = result.privateKey,
                        localAddress = localAddress,
                        clientId = result.clientId,
                        license = result.license,
                        accountType = result.accountType
                    )
                )
            } else {
                val errorMessage = result.error ?: "Registration failed"
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e(TAG, "WARP identity generation error", e)
            Result.failure(e)
        }
    }

    /**
     * Create a free WARP account identity.
     * Generates local keys, registers with Cloudflare API, and updates UI state.
     * This is a one-click solution for users without a paid license.
     * 
     * @return Result containing the generated identity information
     */
    suspend fun createFreeIdentity(): Result<WarpIdentityResult> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "🚀 Creating free WARP account identity...")
            
            // Step 1: Generate local Curve25519 KeyPair
            val (privateKey, publicKey) = generateWarpKeys()
            Log.d(TAG, "✅ Generated Curve25519 key pair")
            
            // Step 2: Register with Cloudflare API
            val registrationResult = WarpApiManager.registerNewDevice(publicKey)
            
            if (!registrationResult.success) {
                val errorMessage = registrationResult.error ?: "Registration failed"
                Log.e(TAG, "❌ Registration failed: $errorMessage")
                return@withContext Result.failure(Exception(errorMessage))
            }
            
            val clientId = registrationResult.clientId
            val token = registrationResult.token
            
            if (clientId.isNullOrEmpty() || token.isNullOrEmpty()) {
                val errorMsg = "Registration succeeded but clientId or token is missing"
                Log.e(TAG, "❌ $errorMsg")
                return@withContext Result.failure(Exception(errorMsg))
            }
            
            // Step 3: Save session in repository
            warpRepository.setCachedIdentity(clientId, token, privateKey)
            
            // Step 4: Update UI state
            val localAddress = registrationResult.localAddress ?: _warpLocalAddress.value.ifEmpty { WarpDefaults.LOCAL_ADDRESS }
            val accountType = registrationResult.accountType ?: "free"
            
            _warpClientId.value = clientId
            _warpPrivateKey.value = privateKey
            _warpLocalAddress.value = localAddress
            _warpAccountType.value = accountType
            _warpQuota.value = calculateQuotaFromAccountType(accountType)
            
            Log.i(TAG, "✅ Free WARP account created successfully")
            Log.d(TAG, "   Account Type: $accountType")
            Log.d(TAG, "   Quota: ${_warpQuota.value}")
            Log.d(TAG, "   Local Address: $localAddress")
            
            Result.success(
                WarpIdentityResult(
                    privateKey = privateKey,
                    localAddress = localAddress,
                    clientId = clientId,
                    license = null,
                    accountType = accountType
                )
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Free WARP account creation failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Bind WARP+ license key to existing account.
     */
    suspend fun bindLicenseKey(): Result<WarpLicenseBindingResult> = withContext(Dispatchers.IO) {
        try {
            val rawLicenseKey = _warpLicenseKey.value.trim()

            if (rawLicenseKey.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Please enter a license key"))
            }

            // Normalize license key: remove all spaces, convert to uppercase, ensure dashes are correct
            val normalizedLicenseKey = normalizeLicenseKey(rawLicenseKey)
            
            if (normalizedLicenseKey == null) {
                return@withContext Result.failure(IllegalArgumentException("Invalid license key format. Expected: xxxx-xxxx-xxxx (e.g., ABCD-1234-EFGH)"))
            }

            _isBindingLicense.value = true

            val cachedIdentity = warpRepository.getCachedIdentity()
            if (cachedIdentity != null) {
                Log.d(TAG, "Restoring cached WARP identity")
                warpRepository.setCachedIdentity(
                    cachedIdentity.clientId,
                    cachedIdentity.token,
                    cachedIdentity.privateKey
                )
            }

            val existingPrivateKey = if (_warpPrivateKey.value.isNotEmpty() &&
                WarpUtils.isValidPrivateKey(_warpPrivateKey.value)) {
                _warpPrivateKey.value
            } else {
                null
            }

            val bindingResult = warpRepository.bindWarpPlusKey(
                licenseKey = normalizedLicenseKey,
                existingPrivateKey = existingPrivateKey
            )

            _isBindingLicense.value = false

            if (bindingResult.success) {
                if (bindingResult.clientId != null) {
                    _warpClientId.value = bindingResult.clientId
                }

                _warpAccountType.value = bindingResult.accountType
                // Use quota from response if available, otherwise calculate from account type
                _warpQuota.value = calculateQuotaFromAccountType(
                    bindingResult.accountType,
                    bindingResult.quota
                )

                Result.success(
                    WarpLicenseBindingResult(
                        privateKey = bindingResult.privateKey,
                        clientId = bindingResult.clientId,
                        accountType = bindingResult.accountType
                    )
                )
            } else {
                val errorMsg = bindingResult.error ?: "Unknown error"
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "License binding error", e)
            _isBindingLicense.value = false
            Result.failure(e)
        }
    }

    /**
     * Normalize license key format.
     * Removes spaces, converts to uppercase, and validates format.
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
     * Reset WARP settings to defaults.
     */
    fun reset() {
        _enableWarp.value = false
        _warpPrivateKey.value = ""
        _warpPeerPublicKey.value = WarpDefaults.PEER_PUBLIC_KEY
        _warpEndpoint.value = WarpDefaults.ENDPOINT
        _warpLocalAddress.value = WarpDefaults.LOCAL_ADDRESS
        _warpAccountType.value = null
        _warpQuota.value = "Unknown"
    }

    /**
     * Result of WARP identity generation.
     */
    data class WarpIdentityResult(
        val privateKey: String,
        val localAddress: String,
        val clientId: String?,
        val license: String?,
        val accountType: String?
    )

    /**
     * Result of WARP license binding.
     */
    data class WarpLicenseBindingResult(
        val privateKey: String?,
        val clientId: String?,
        val accountType: String?
    )
}

