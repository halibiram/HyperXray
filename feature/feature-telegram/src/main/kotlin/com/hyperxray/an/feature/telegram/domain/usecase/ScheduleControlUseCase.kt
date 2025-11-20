package com.hyperxray.an.feature.telegram.domain.usecase

/**
 * Schedule control use case
 * Controls scheduled reports (daily/weekly/monthly)
 * 
 * Note: This is a placeholder. Actual implementation is in app module
 * to avoid circular dependencies.
 */
interface ScheduleControlUseCase {
    suspend fun setSchedule(scheduleType: String, enabled: Boolean, time: String?): Result<String>
    suspend fun getScheduleStatus(): Result<String>
}

