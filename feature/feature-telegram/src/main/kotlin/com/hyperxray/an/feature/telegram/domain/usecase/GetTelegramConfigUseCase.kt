package com.hyperxray.an.feature.telegram.domain.usecase

import com.hyperxray.an.feature.telegram.domain.entity.TelegramConfig
import com.hyperxray.an.feature.telegram.domain.repository.TelegramRepository

/**
 * Get Telegram configuration use case
 */
class GetTelegramConfigUseCase(
    private val repository: TelegramRepository
) {
    suspend operator fun invoke(): Result<TelegramConfig> {
        return repository.getConfig()
    }
}




