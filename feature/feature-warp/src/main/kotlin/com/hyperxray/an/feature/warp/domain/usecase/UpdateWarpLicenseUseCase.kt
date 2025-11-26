package com.hyperxray.an.feature.warp.domain.usecase

import com.hyperxray.an.feature.warp.domain.entity.WarpAccount
import com.hyperxray.an.feature.warp.domain.repository.WarpRepository

/**
 * Use case for updating WARP license key
 */
class UpdateWarpLicenseUseCase(
    private val repository: WarpRepository
) {
    suspend operator fun invoke(accountId: String, licenseKey: String): Result<WarpAccount> {
        return repository.updateLicense(accountId, licenseKey)
    }
}



