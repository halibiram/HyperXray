package com.hyperxray.an.feature.telegram.domain.usecase

import com.hyperxray.an.feature.telegram.domain.entity.TelegramConfig
import com.hyperxray.an.feature.telegram.domain.repository.TelegramRepository

/**
 * Get notification preferences use case
 */
class GetNotificationPreferencesUseCase(
    private val repository: TelegramRepository
) {
    suspend operator fun invoke(): Result<TelegramConfig> {
        return repository.getConfig()
    }
}






