package com.hyperxray.an.core.network.warp

import android.util.Base64
import android.util.Log
import com.hyperxray.an.core.network.warp.model.*
import com.hyperxray.an.utils.WarpUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.KeyPairGenerator
import java.security.Security

private const val TAG = "WgcfManager"

/**
 * WgcfManager: Core logic manager ported from wgcf.
 * 
 * Maintains 100% fidelity to wgcf's logic flow:
 * 1. Generate Curve25519 KeyPair locally
 * 2. Register device (POST /reg)
 * 3. Update config to enable device (PATCH /reg/{id})
 * 4. Bind license if provided (PUT /reg/{id}/account)
 * 5. Re-activate device after license binding (PATCH /reg/{id})
 * 
 * Based on: https://github.com/ViRb3/wgcf/blob/master/cloudflare/api.go
 * 
 * CRITICAL: Private key is NEVER sent to the server (only public key).
 */
object WgcfManager {
    
    /**
     * Register a new WARP account and generate identity.
     * 
     * This is the complete wgcf registration flow:
     * 1. Generate Curve25519 KeyPair locally
     * 2. Register with Cloudflare (POST /reg)
     * 3. Enable device (PATCH /reg/{id})
     * 
     * @return WgcfAccountResult with private key, public key, device ID, token, and config
     */
    suspend fun register(): WgcfAccountResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "🚀 Starting wgcf registration flow...")
            
            // Step 1: Generate Curve25519 KeyPair locally
            val (privateKey, publicKey) = generateKeyPair()
            Log.d(TAG, "✅ Generated Curve25519 key pair")
            Log.d(TAG, "   Private key: ${privateKey.take(20)}... (${privateKey.length} chars)")
            Log.d(TAG, "   Public key: ${publicKey.take(20)}... (${publicKey.length} chars)")
            
            // Validate public key format
            if (publicKey.length != 44) {
                val errorMsg = "Invalid public key length: ${publicKey.length} (expected 44)"
                Log.e(TAG, "❌ $errorMsg")
                return@withContext WgcfAccountResult(
                    success = false,
                    privateKey = privateKey,
                    publicKey = publicKey,
                    error = errorMsg
                )
            }
            
            // Step 2: Register device (POST /reg)
            Log.i(TAG, "📝 Registering device with Cloudflare WARP API...")
            val registrationResponse = try {
                WgcfApi.register(publicKey)
            } catch (e: WgcfApiException) {
                Log.e(TAG, "❌ Registration failed: ${e.message}", e)
                return@withContext WgcfAccountResult(
                    success = false,
                    privateKey = privateKey,
                    publicKey = publicKey,
                    error = "Registration failed: ${e.message}"
                )
            }
            
            val deviceId = registrationResponse.id
            val token = registrationResponse.token
            
            if (deviceId.isNullOrEmpty()) {
                val errorMsg = "Device ID not found in registration response"
                Log.e(TAG, "❌ $errorMsg")
                return@withContext WgcfAccountResult(
                    success = false,
                    privateKey = privateKey,
                    publicKey = publicKey,
                    error = errorMsg
                )
            }
            
            if (token.isNullOrEmpty()) {
                val errorMsg = "Authentication token not found in registration response"
                Log.e(TAG, "❌ $errorMsg")
                return@withContext WgcfAccountResult(
                    success = false,
                    privateKey = privateKey,
                    publicKey = publicKey,
                    error = errorMsg
                )
            }
            
            Log.i(TAG, "✅ Device registered successfully")
            Log.d(TAG, "   Device ID: ${deviceId.take(20)}...")
            Log.d(TAG, "   Token: ${token.take(20)}...")
            
            // Step 3: Update config to enable device (PATCH /reg/{id})
            // This is CRITICAL - wgcf always calls this after registration
            Log.i(TAG, "🔧 Enabling device...")
            try {
                WgcfApi.updateConfig(deviceId, token, active = true)
                Log.i(TAG, "✅ Device enabled successfully")
            } catch (e: WgcfApiException) {
                Log.w(TAG, "⚠️ Failed to enable device (non-critical): ${e.message}")
                // Continue anyway - device might still work
            }
            
            // Extract config information
            val config = registrationResponse.config
            val peerPublicKey = config?.peers?.firstOrNull()?.publicKey
            val endpoint = config?.peers?.firstOrNull()?.endpoint?.host 
                ?: config?.peers?.firstOrNull()?.endpoint?.v4
            val localAddress = extractLocalAddress(config)
            val reserved = listOf(0, 0, 0) // Default reserved bytes (wgcf uses [0,0,0] for free accounts)
            
            Log.i(TAG, "✅ wgcf registration flow completed successfully")
            
            WgcfAccountResult(
                success = true,
                privateKey = privateKey,
                publicKey = publicKey,
                deviceId = deviceId,
                token = token,
                accountType = registrationResponse.account?.accountType ?: "free",
                license = registrationResponse.account?.license,
                peerPublicKey = peerPublicKey,
                endpoint = endpoint,
                localAddress = localAddress,
                reserved = reserved
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ wgcf registration flow failed: ${e.message}", e)
            WgcfAccountResult(
                success = false,
                privateKey = "",
                publicKey = "",
                error = "Registration failed: ${e.message}"
            )
        }
    }
    
    /**
     * Bind a license key to an existing account.
     * 
     * This implements wgcf's license binding flow:
     * 1. Bind license (PUT /reg/{id}/account)
     * 2. Re-activate device (PATCH /reg/{id})
     * 
     * @param deviceId Device ID from registration
     * @param token Authentication token from registration
     * @param license License key (format: xxxxxxxx-xxxxxxxx-xxxxxxxx)
     * @return WgcfLicenseBindingResult with updated account information
     */
    suspend fun bindLicense(
        deviceId: String,
        token: String,
        license: String
    ): WgcfLicenseBindingResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "🔑 Starting license binding flow...")
            Log.d(TAG, "   Device ID: ${deviceId.take(20)}...")
            Log.d(TAG, "   License (raw): $license")
            Log.d(TAG, "   License length: ${license.length}")
            
            // Normalize license key format
            val normalizedLicense = normalizeLicenseKey(license)
            if (normalizedLicense == null) {
                val errorMsg = "Invalid license key format. Expected: xxxxxxxx-xxxxxxxx-xxxxxxxx"
                Log.e(TAG, "❌ $errorMsg")
                Log.e(TAG, "   Provided key: $license (length: ${license.length})")
                return@withContext WgcfLicenseBindingResult(
                    success = false,
                    error = errorMsg
                )
            }
            
            Log.d(TAG, "   License (normalized): $normalizedLicense")
            
            // Step 1: Bind license (PUT /reg/{id}/account)
            Log.i(TAG, "📤 Binding license to account...")
            val account = try {
                WgcfApi.bindLicense(deviceId, token, normalizedLicense)
            } catch (e: WgcfApiException) {
                Log.e(TAG, "❌ License binding failed: ${e.message}", e)
                return@withContext WgcfLicenseBindingResult(
                    success = false,
                    error = "License binding failed: ${e.message}"
                )
            }
            
            Log.i(TAG, "✅ License bound successfully")
            Log.d(TAG, "   Account type: ${account.accountType}")
            Log.d(TAG, "   License: ${account.license?.take(10)}...")
            
            // Step 2: Re-activate device (PATCH /reg/{id})
            // This is CRITICAL - wgcf always calls this after license binding
            Log.i(TAG, "🔧 Re-activating device with new license...")
            try {
                WgcfApi.updateConfig(deviceId, token, active = true)
                Log.i(TAG, "✅ Device re-activated successfully")
            } catch (e: WgcfApiException) {
                Log.w(TAG, "⚠️ Failed to re-activate device (non-critical): ${e.message}")
                // Continue anyway - license is bound
            }
            
            val isPlus = account.accountType?.let {
                it.equals("plus", ignoreCase = true) ||
                it.equals("unlimited", ignoreCase = true) ||
                it.equals("premium", ignoreCase = true)
            } ?: false
            
            if (isPlus) {
                val quota = account.premiumData ?: account.quota
                val quotaDisplay = quota?.let {
                    val gb = it / (1024.0 * 1024.0 * 1024.0)
                    String.format("%.2f GB", gb)
                } ?: "Unlimited"
                Log.i(TAG, "✅ License binding successful: Account type is ${account.accountType}, Quota: $quotaDisplay")
            } else {
                Log.w(TAG, "⚠️ License binding completed but account type is still: ${account.accountType}")
            }
            
            WgcfLicenseBindingResult(
                success = true,
                accountType = account.accountType,
                license = account.license,
                quota = account.premiumData ?: account.quota
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ License binding flow failed: ${e.message}", e)
            WgcfLicenseBindingResult(
                success = false,
                error = "License binding failed: ${e.message}"
            )
        }
    }
    
    /**
     * Generate Curve25519 KeyPair matching wgcf's key generation.
     * 
     * Uses BouncyCastle X25519 to generate keys exactly as wgcf does.
     * Private key is clamped according to WireGuard specification.
     * 
     * @return Pair of (privateKey, publicKey) as Base64 strings (44 chars each)
     */
    private fun generateKeyPair(): Pair<String, String> {
        // Ensure BouncyCastle provider is registered
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        
        try {
            // Generate X25519 key pair using BouncyCastle
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
            
            if (!WarpUtils.isValidPrivateKey(privateKey)) {
                throw IllegalStateException("Generated private key is invalid (length: ${privateKey.length})")
            }
            
            // Derive public key
            val publicKey = WarpUtils.derivePublicKey(privateKey)
            
            if (publicKey.length != 44) {
                throw IllegalStateException("Derived public key has invalid length: ${publicKey.length} (expected 44)")
            }
            
            Log.d(TAG, "✅ Generated key pair using fallback method")
            return Pair(privateKey, publicKey)
        }
    }
    
    /**
     * Extract private key bytes from Java PrivateKey object.
     */
    private fun extractPrivateKeyBytes(privateKey: java.security.PrivateKey): ByteArray {
        val encoded = privateKey.encoded
        
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
     * 
     * First byte: clear bits 0, 1, 2 (mask 0xF8)
     * Last byte: clear bit 7, set bit 6 (mask 0x7F, then OR 0x40)
     * 
     * This matches wgcf's key clamping logic exactly.
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
     * 
     * Handles both object format (v4/v6) and array format.
     * Returns format: "172.16.0.2/32" or "172.16.0.2/32, 2606:4700:110:8f5a:7c46:852e:f45c:6d35/128"
     */
    private fun extractLocalAddress(config: WgcfConfig?): String? {
        if (config == null) return null
        
        val interfaceConfig = config.`interface` ?: return null
        val addresses = interfaceConfig.addresses ?: return null
        
        val v4 = addresses.v4
        val v6 = addresses.v6
        
        // Prefer IPv4 for compatibility, but include IPv6 if available
        return when {
            v4 != null && v6 != null -> "$v4, $v6"
            v4 != null -> v4
            v6 != null -> v6
            else -> null
        }
    }
    
    /**
     * Normalize license key format.
     * 
     * Removes spaces and validates/repairs format.
     * Format: xxxxxxxx-xxxxxxxx-xxxxxxxx (8-8-8 characters)
     * 
     * Note: Case is preserved (not converted to uppercase) as license keys are case-sensitive.
     * 
     * @return Normalized license key (xxxxxxxx-xxxxxxxx-xxxxxxxx) or null if invalid
     */
    private fun normalizeLicenseKey(rawKey: String): String? {
        Log.d(TAG, "🔍 Normalizing license key: ${rawKey.take(30)}... (length: ${rawKey.length})")
        
        // Remove all spaces (preserve case - license keys are case-sensitive)
        val cleaned = rawKey.replace("\\s".toRegex(), "")
        Log.d(TAG, "   After removing spaces: $cleaned (length: ${cleaned.length})")
        
        // Check if it's already in correct format (8-8-8 characters)
        val pattern = Regex("^[A-Za-z0-9]{8}-[A-Za-z0-9]{8}-[A-Za-z0-9]{8}$")
        if (pattern.matches(cleaned)) {
            Log.d(TAG, "✅ License key already in correct format: $cleaned")
            return cleaned
        }
        
        Log.d(TAG, "⚠️ License key not in correct format, attempting to fix...")
        
        // Try to fix common issues: missing dashes, wrong dash positions
        // If it's 24 characters without dashes, add dashes
        val withoutDashes = cleaned.replace("-", "").replace("_", "").replace(" ", "")
        Log.d(TAG, "   Without dashes: $withoutDashes (length: ${withoutDashes.length})")
        
        if (withoutDashes.length == 24 && withoutDashes.all { it.isLetterOrDigit() }) {
            val normalized = "${withoutDashes.substring(0, 8)}-${withoutDashes.substring(8, 16)}-${withoutDashes.substring(16, 24)}"
            Log.d(TAG, "✅ Fixed license key format: $normalized")
            return normalized
        }
        
        Log.e(TAG, "❌ Failed to normalize license key. Length: ${withoutDashes.length}, Valid chars: ${withoutDashes.all { it.isLetterOrDigit() }}")
        return null
    }
}

/**
 * Result of wgcf registration flow.
 */
data class WgcfAccountResult(
    val success: Boolean,
    val privateKey: String, // Base64-encoded private key (44 chars)
    val publicKey: String, // Base64-encoded public key (44 chars)
    val deviceId: String? = null, // Device ID from registration (CRITICAL - must be stored)
    val token: String? = null, // Authentication token (CRITICAL - must be stored)
    val accountType: String? = null, // "free", "plus", "unlimited", etc.
    val license: String? = null, // License key if account has one
    val peerPublicKey: String? = null, // Cloudflare peer public key
    val endpoint: String? = null, // WARP endpoint (host:port)
    val localAddress: String? = null, // Local address (CIDR format)
    val reserved: List<Int> = listOf(0, 0, 0), // Reserved bytes (default [0,0,0])
    val error: String? = null
)

/**
 * Result of license binding flow.
 */
data class WgcfLicenseBindingResult(
    val success: Boolean,
    val accountType: String? = null, // "free", "plus", "unlimited", etc.
    val license: String? = null, // License key
    val quota: Long? = null, // Premium data quota in bytes
    val error: String? = null
)

