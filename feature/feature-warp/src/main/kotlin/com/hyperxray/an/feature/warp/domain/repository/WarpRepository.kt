package com.hyperxray.an.feature.warp.domain.repository

import com.hyperxray.an.feature.warp.domain.entity.WarpAccount
import com.hyperxray.an.feature.warp.domain.entity.WarpDevice
import com.hyperxray.an.feature.warp.domain.entity.WarpConfigType

/**
 * WARP repository interface
 */
interface WarpRepository {
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
}



