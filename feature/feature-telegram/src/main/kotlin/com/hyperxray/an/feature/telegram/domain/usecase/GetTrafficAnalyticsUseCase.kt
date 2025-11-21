package com.hyperxray.an.feature.telegram.domain.usecase

/**
 * Get traffic analytics use case
 * Returns traffic analysis for daily/weekly/monthly periods
 * 
 * Note: This is a placeholder. Actual implementation is in app module
 * to avoid circular dependencies.
 */
interface GetTrafficAnalyticsUseCase {
    suspend operator fun invoke(period: String): Result<String>
}




