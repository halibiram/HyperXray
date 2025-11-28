package com.hyperxray.an.feature.warp.domain.usecase

import com.hyperxray.an.feature.warp.domain.repository.WarpRepository

/**
 * Use case for removing a WARP device
 */
class RemoveWarpDeviceUseCase(
    private val repository: WarpRepository
) {
    suspend operator fun invoke(accountId: String, deviceId: String): Result<Unit> {
        return repository.removeDevice(accountId, deviceId)
    }
}










