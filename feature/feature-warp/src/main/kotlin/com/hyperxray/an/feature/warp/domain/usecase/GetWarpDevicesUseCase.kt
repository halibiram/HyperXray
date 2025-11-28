package com.hyperxray.an.feature.warp.domain.usecase

import com.hyperxray.an.feature.warp.domain.entity.WarpDevice
import com.hyperxray.an.feature.warp.domain.repository.WarpRepository

/**
 * Use case for getting list of WARP devices
 */
class GetWarpDevicesUseCase(
    private val repository: WarpRepository
) {
    suspend operator fun invoke(accountId: String): Result<List<WarpDevice>> {
        return repository.getDevices(accountId)
    }
}










