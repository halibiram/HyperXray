package com.hyperxray.an.feature.telegram.domain.usecase

/**
 * Get VPN status use case
 * Returns formatted VPN connection status string
 * 
 * Note: This is a placeholder. Actual implementation is in app module
 * to avoid circular dependencies.
 */
interface GetVpnStatusUseCase {
    suspend operator fun invoke(): Result<String>
}

