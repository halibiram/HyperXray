package com.hyperxray.an.feature.warp.domain.usecase

import com.hyperxray.an.feature.warp.domain.entity.WarpAccount
import com.hyperxray.an.feature.warp.domain.repository.WarpRepository

/**
 * Use case for importing a WARP account from account key/JSON
 * 
 * Supports multiple import formats:
 * - Full JSON export (from warp-account.json)
 * - License key only (will register new account with license)
 * - WireGuard config format (extracts private key and addresses)
 */
class ImportWarpAccountUseCase(
    private val repository: WarpRepository
) {
    /**
     * Import account from JSON string
     */
    suspend operator fun invoke(accountJson: String): Result<WarpAccount> {
        return repository.importAccount(accountJson)
    }
    
    /**
     * Import account from license key (registers new account with license)
     */
    suspend fun fromLicenseKey(licenseKey: String): Result<WarpAccount> {
        return repository.registerAccount(licenseKey)
    }
}
