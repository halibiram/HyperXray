package com.hyperxray.an.core.network.warp

import android.util.Log
import com.hyperxray.an.utils.WarpUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "WarpRepository"

/**
 * Repository for WARP account management.
 * Handles the unified 2-step WARP+ license binding process:
 * 1. Register device (if not already registered)
 * 2. Update license key
 */
class WarpRepository {
    
    /**
     * Local storage for WARP identity (in-memory for now, can be persisted later).
     * In a production app, this should be stored in SharedPreferences or encrypted storage.
     */
    private var cachedClientId: String? = null
    private var cachedToken: String? = null
    private var cachedPrivateKey: String? = null
    
    /**
     * Unified flow to bind WARP+ license key to account.
     * 
     * This method implements the strict 2-step process:
     * 1. Ensures device is registered (calls registerDevice if needed)
     * 2. Updates account with user's license key
     * 
     * @param licenseKey The WARP+ license key (format: xxxx-xxxx-xxxx)
     * @param existingPrivateKey Optional existing private key. If provided, will be used for registration.
     * @return WarpLicenseBindingResult with success status and account details
     */
    suspend fun bindWarpPlusKey(
        licenseKey: String,
        existingPrivateKey: String? = null
    ): WarpLicenseBindingResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "🔑 Starting WARP+ license binding process...")
            Log.d(TAG, "   License key: ${licenseKey.take(10)}...")
            
            // Validate and normalize license key format
            val normalizedLicenseKey = normalizeLicenseKey(licenseKey)
            if (normalizedLicenseKey == null) {
                val errorMsg = "Invalid license key format. Expected format: xxxx-xxxx-xxxx (e.g., ABCD-1234-EFGH)"
                Log.e(TAG, "❌ $errorMsg")
                Log.d(TAG, "   Provided key: ${licenseKey.take(20)}... (length: ${licenseKey.length})")
                return@withContext WarpLicenseBindingResult(
                    success = false,
                    error = errorMsg
                )
            }
            
            // Step 1: Check if local WARP identity exists
            var clientId = cachedClientId
            var token = cachedToken
            var privateKey = existingPrivateKey ?: cachedPrivateKey
            
            if (clientId.isNullOrEmpty() || token.isNullOrEmpty() || privateKey.isNullOrEmpty()) {
                // No local identity exists - register device first
                Log.i(TAG, "📝 No WARP identity found, registering device...")
                
                // Generate key pair if not provided
                if (privateKey.isNullOrEmpty()) {
                    Log.d(TAG, "   Generating new key pair...")
                    privateKey = WarpUtils.generatePrivateKey()
                }
                
                // Derive public key from private key
                val publicKey = try {
                    WarpUtils.derivePublicKey(privateKey)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to derive public key: ${e.message}", e)
                    return@withContext WarpLicenseBindingResult(
                        success = false,
                        error = "Failed to derive public key: ${e.message}"
                    )
                }
                
                // Register device with WARP API
                val registrationResult = WarpApiManager.registerDevice(publicKey)
                
                if (!registrationResult.success) {
                    val errorMsg = registrationResult.error ?: "Device registration failed"
                    Log.e(TAG, "❌ $errorMsg")
                    return@withContext WarpLicenseBindingResult(
                        success = false,
                        error = errorMsg
                    )
                }
                
                // Extract client ID and token from registration
                clientId = registrationResult.clientId
                token = registrationResult.token
                
                if (clientId.isNullOrEmpty() || token.isNullOrEmpty()) {
                    Log.e(TAG, "❌ Registration succeeded but clientId or token is missing")
                    return@withContext WarpLicenseBindingResult(
                        success = false,
                        error = "Registration succeeded but clientId or token is missing"
                    )
                }
                
                // Cache the identity for future use
                cachedClientId = clientId
                cachedToken = token
                cachedPrivateKey = privateKey
                
                Log.i(TAG, "✅ Device registered successfully")
                Log.d(TAG, "   Client ID: ${clientId.take(20)}...")
                Log.d(TAG, "   Token: ${token.take(20)}...")
            } else {
                Log.i(TAG, "✅ Using existing WARP identity")
                Log.d(TAG, "   Client ID: ${clientId.take(20)}...")
            }
            
            // Step 2: Update license key (use normalized key)
            Log.i(TAG, "🔑 Updating license key...")
            var updateResult = WarpApiManager.updateLicenseKey(
                clientId = clientId!!,
                token = token!!,
                licenseKey = normalizedLicenseKey
            )
            
            // If authentication failed (401), try to re-register and update again
            // Check for 401 status code or "Authentication failed" in error message
            val isAuthError = !updateResult.success && (
                updateResult.error?.contains("401") == true ||
                updateResult.error?.contains("Authentication failed", ignoreCase = true) == true ||
                updateResult.error?.contains("invalid or expired", ignoreCase = true) == true
            )
            
            if (isAuthError) {
                Log.w(TAG, "⚠️ Authentication failed, attempting to re-register device...")
                
                // Generate new key pair
                val newPrivateKey = existingPrivateKey ?: WarpUtils.generatePrivateKey()
                val newPublicKey = try {
                    WarpUtils.derivePublicKey(newPrivateKey)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to derive public key for re-registration: ${e.message}", e)
                    return@withContext WarpLicenseBindingResult(
                        success = false,
                        error = "Failed to re-register device: ${e.message}"
                    )
                }
                
                // Re-register device
                val reRegistrationResult = WarpApiManager.registerDevice(newPublicKey)
                
                if (!reRegistrationResult.success) {
                    val errorMsg = reRegistrationResult.error ?: "Re-registration failed"
                    Log.e(TAG, "❌ Re-registration failed: $errorMsg")
                    return@withContext WarpLicenseBindingResult(
                        success = false,
                        error = "Authentication failed and re-registration also failed: $errorMsg"
                    )
                }
                
                // Update cached identity
                val newClientId = reRegistrationResult.clientId
                val newToken = reRegistrationResult.token
                
                if (newClientId.isNullOrEmpty() || newToken.isNullOrEmpty()) {
                    Log.e(TAG, "❌ Re-registration succeeded but clientId or token is missing")
                    return@withContext WarpLicenseBindingResult(
                        success = false,
                        error = "Re-registration succeeded but clientId or token is missing"
                    )
                }
                
                // Cache the new identity
                cachedClientId = newClientId
                cachedToken = newToken
                cachedPrivateKey = newPrivateKey
                
                Log.i(TAG, "✅ Device re-registered successfully, retrying license update...")
                
                // Retry license update with new credentials (use normalized key)
                updateResult = WarpApiManager.updateLicenseKey(
                    clientId = newClientId,
                    token = newToken,
                    licenseKey = normalizedLicenseKey
                )
                
                // Update clientId and token for return value
                clientId = newClientId
                token = newToken
                privateKey = newPrivateKey
            }
            
            if (!updateResult.success) {
                val errorMsg = updateResult.error ?: "License update failed"
                Log.e(TAG, "❌ $errorMsg")
                
                // Handle specific error cases
                val userFriendlyError = when {
                    errorMsg.contains("device limit", ignoreCase = true) -> 
                        "License key has reached device limit (max 5 devices)"
                    errorMsg.contains("Invalid license", ignoreCase = true) -> 
                        "Invalid license key. Please check your key and try again."
                    errorMsg.contains("Authentication failed", ignoreCase = true) -> 
                        "Authentication failed. Please try generating a new identity."
                    else -> errorMsg
                }
                
                return@withContext WarpLicenseBindingResult(
                    success = false,
                    error = userFriendlyError
                )
            }
            
            // Step 3: Parse response and check account type
            val accountType = updateResult.accountType
            val isWarpPlus = accountType?.let {
                it.equals("plus", ignoreCase = true) ||
                it.equals("unlimited", ignoreCase = true) ||
                it.equals("premium", ignoreCase = true)
            } ?: false
            
            if (isWarpPlus) {
                Log.i(TAG, "✅ License key bound successfully! Account type: $accountType")
            } else {
                Log.w(TAG, "⚠️ License update completed but account type is still: $accountType")
            }
            
            WarpLicenseBindingResult(
                success = true,
                clientId = clientId,
                token = token,
                privateKey = privateKey,
                accountType = accountType,
                license = updateResult.license,
                quota = updateResult.quota
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ WARP+ license binding failed: ${e.message}", e)
            WarpLicenseBindingResult(
                success = false,
                error = "License binding failed: ${e.message}"
            )
        }
    }
    
    /**
     * Clear cached WARP identity.
     * Useful when user wants to start fresh or switch accounts.
     */
    fun clearCachedIdentity() {
        Log.d(TAG, "Clearing cached WARP identity")
        cachedClientId = null
        cachedToken = null
        cachedPrivateKey = null
    }
    
    /**
     * Set cached WARP identity (for restoring from storage).
     */
    fun setCachedIdentity(clientId: String?, token: String?, privateKey: String?) {
        cachedClientId = clientId
        cachedToken = token
        cachedPrivateKey = privateKey
        Log.d(TAG, "Cached WARP identity restored: clientId=${clientId?.take(20)}..., token=${token?.take(20)}...")
    }
    
    /**
     * Get cached WARP identity.
     */
    fun getCachedIdentity(): WarpCachedIdentity? {
        return if (cachedClientId != null && cachedToken != null && cachedPrivateKey != null) {
            WarpCachedIdentity(
                clientId = cachedClientId!!,
                token = cachedToken!!,
                privateKey = cachedPrivateKey!!
            )
        } else {
            null
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
     * Accepts alphanumeric characters (case-insensitive) with dashes.
     */
    private fun isValidLicenseKeyFormat(licenseKey: String): Boolean {
        // Format: xxxx-xxxx-xxxx (alphanumeric with dashes)
        // Example: "1234-5678-9ABC" or "abcd-efgh-ijkl"
        // Normalize to uppercase for validation
        val normalized = licenseKey.trim().uppercase()
        val pattern = Regex("^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")
        return pattern.matches(normalized)
    }
}

/**
 * Result of WARP+ license binding operation.
 */
data class WarpLicenseBindingResult(
    val success: Boolean,
    val clientId: String? = null,
    val token: String? = null,
    val privateKey: String? = null,
    val accountType: String? = null, // "free", "plus", "unlimited", etc.
    val license: String? = null,
    val quota: Long? = null, // Premium data quota in bytes
    val error: String? = null
)

/**
 * Cached WARP identity information.
 */
data class WarpCachedIdentity(
    val clientId: String,
    val token: String,
    val privateKey: String
)

