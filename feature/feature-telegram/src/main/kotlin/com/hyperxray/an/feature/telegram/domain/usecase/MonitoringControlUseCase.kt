package com.hyperxray.an.feature.telegram.domain.usecase

/**
 * Monitoring control use case
 * Controls automatic monitoring with interval-based reporting
 * 
 * Note: This is a placeholder. Actual implementation is in app module
 * to avoid circular dependencies.
 */
interface MonitoringControlUseCase {
    suspend fun startMonitoring(intervalMinutes: Int): Result<String>
    suspend fun stopMonitoring(): Result<String>
    suspend fun getMonitoringStatus(): Result<String>
}

