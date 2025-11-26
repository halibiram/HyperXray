package com.hyperxray.an.feature.warp.domain.usecase

import com.hyperxray.an.feature.warp.domain.entity.WarpAccount
import com.hyperxray.an.feature.warp.domain.repository.WarpRepository

/**
 * Use case for loading saved WARP account from storage
 */
class LoadWarpAccountUseCase(
    private val repository: WarpRepository
) {
    suspend operator fun invoke(): Result<WarpAccount> {
        return repository.loadAccount()
    }
}



