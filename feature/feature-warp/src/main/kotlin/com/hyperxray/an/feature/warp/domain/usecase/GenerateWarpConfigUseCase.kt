package com.hyperxray.an.feature.warp.domain.usecase

import com.hyperxray.an.feature.warp.domain.entity.WarpAccount
import com.hyperxray.an.feature.warp.domain.entity.WarpConfigType
import com.hyperxray.an.feature.warp.domain.repository.WarpRepository

/**
 * Use case for generating WARP configuration
 */
class GenerateWarpConfigUseCase(
    private val repository: WarpRepository
) {
    suspend operator fun invoke(
        account: WarpAccount,
        configType: WarpConfigType,
        endpoint: String? = null
    ): Result<String> {
        return when (configType) {
            WarpConfigType.WIREGUARD -> repository.generateWireGuardConfig(account, endpoint)
            WarpConfigType.XRAY -> repository.generateXrayConfig(account, endpoint)
            WarpConfigType.SINGBOX -> repository.generateSingBoxConfig(account, endpoint)
        }
    }
}



