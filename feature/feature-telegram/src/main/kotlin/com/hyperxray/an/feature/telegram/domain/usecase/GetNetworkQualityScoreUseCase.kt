package com.hyperxray.an.feature.telegram.domain.usecase

/**
 * Get network quality score use case
 * Returns network quality score (0-100) with detailed breakdown
 * 
 * Note: This is a placeholder. Actual implementation is in app module
 * to avoid circular dependencies.
 */
interface GetNetworkQualityScoreUseCase {
    suspend operator fun invoke(): Result<String>
}

