package com.hyperxray.an.feature.warp.domain.repository

import com.hyperxray.an.feature.warp.domain.entity.WarpAccount
import com.hyperxray.an.feature.warp.domain.entity.WarpDevice
import com.hyperxray.an.feature.warp.domain.entity.WarpConfigType
import kotlinx.coroutines.flow.StateFlow

/**
 * WARP repository interface
 */
interface WarpRepository {
    /**
     * Reactive flow of current WARP account state.
     * Emits null when no account is registered, or WarpAccount when account exists.
     * This is the single source of truth for WARP account state.
     */
    val accountFlow: StateFlow<WarpAccount?>
    
    /**
     * Register a new WARP account
     */
    suspend fun registerAccount(licenseKey: String? = null): Result<WarpAccount>
    
    /**
     * Update account license key
     */
    suspend fun updateLicense(accountId: String, licenseKey: String): Result<WarpAccount>
    
    /**
     * Get account information
     */
    suspend fun getAccountInfo(accountId: String): Result<WarpAccount>
    
    /**
     * Get list of devices bound to the account
     */
    suspend fun getDevices(accountId: String): Result<List<WarpDevice>>
    
    /**
     * Remove/unbind a device from account
     */
    suspend fun removeDevice(accountId: String, deviceId: String): Result<Unit>
    
    /**
     * Regenerate WireGuard keypair
     */
    suspend fun regenerateKey(accountId: String): Result<WarpAccount>
    
    /**
     * Save account to local storage
     */
    suspend fun saveAccount(account: WarpAccount): Result<Unit>
    
    /**
     * Load account from local storage
     */
    suspend fun loadAccount(): Result<WarpAccount>
    
    /**
     * Generate WireGuard configuration
     */
    suspend fun generateWireGuardConfig(account: WarpAccount, endpoint: String? = null): Result<String>
    
    /**
     * Generate Xray outbound configuration
     */
    suspend fun generateXrayConfig(account: WarpAccount, endpoint: String? = null): Result<String>
    
    /**
     * Generate sing-box outbound configuration
     */
    suspend fun generateSingBoxConfig(account: WarpAccount, endpoint: String? = null): Result<String>
    
    /**
     * Create a free WARP account (convenience method for registerAccount with null license).
     * This is the recommended way to create a new account without a license key.
     */
    suspend fun createFreeAccount(): Result<WarpAccount> {
        return registerAccount(licenseKey = null)
    }
    
    /**
     * Bind a license key to the current account (convenience method).
     * If no account exists, creates a new account with the license.
     * If account exists, updates the license.
     */
    suspend fun bindLicense(licenseKey: String): Result<WarpAccount> {
        val currentAccount = accountFlow.value
        return if (currentAccount != null) {
            updateLicense(currentAccount.accountId, licenseKey)
        } else {
            registerAccount(licenseKey)
        }
    }
}



