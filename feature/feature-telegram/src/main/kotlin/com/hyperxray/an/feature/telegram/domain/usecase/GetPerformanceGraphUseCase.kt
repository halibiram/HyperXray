package com.hyperxray.an.feature.telegram.domain.usecase

/**
 * Get performance graph use case
 * Returns ASCII-based performance trend graph
 * 
 * Note: This is a placeholder. Actual implementation is in app module
 * to avoid circular dependencies.
 */
interface GetPerformanceGraphUseCase {
    suspend operator fun invoke(metric: String, hours: Int): Result<String>
}




