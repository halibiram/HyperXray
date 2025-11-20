package com.hyperxray.an.feature.telegram.domain.usecase

/**
 * Get diagnostic test use case
 * Returns comprehensive system diagnostic information
 * 
 * Note: This is a placeholder. Actual implementation is in app module
 * to avoid circular dependencies.
 */
interface GetDiagnosticTestUseCase {
    suspend operator fun invoke(): Result<String>
}

