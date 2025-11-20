package com.hyperxray.an.feature.telegram.domain.usecase

import com.hyperxray.an.feature.telegram.domain.entity.TelegramConfig
import com.hyperxray.an.feature.telegram.domain.repository.TelegramRepository

/**
 * Save Telegram configuration use case
 */
class SaveTelegramConfigUseCase(
    private val repository: TelegramRepository
) {
    suspend operator fun invoke(config: TelegramConfig): Result<Unit> {
        if (!config.isValid) {
            return Result.failure(IllegalArgumentException("Invalid configuration: bot token or chat ID is empty"))
        }
        return repository.saveConfig(config)
    }
}

