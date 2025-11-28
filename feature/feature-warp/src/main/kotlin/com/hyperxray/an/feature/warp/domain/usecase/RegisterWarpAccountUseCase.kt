package com.hyperxray.an.feature.warp.domain.usecase

import com.hyperxray.an.feature.warp.domain.entity.WarpAccount
import com.hyperxray.an.feature.warp.domain.repository.WarpRepository

/**
 * Use case for registering a new WARP account
 */
class RegisterWarpAccountUseCase(
    private val repository: WarpRepository
) {
    suspend operator fun invoke(licenseKey: String? = null): Result<WarpAccount> {
        return repository.registerAccount(licenseKey)
    }
}










