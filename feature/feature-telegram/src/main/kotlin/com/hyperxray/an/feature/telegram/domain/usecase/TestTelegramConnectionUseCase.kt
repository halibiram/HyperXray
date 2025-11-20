package com.hyperxray.an.feature.telegram.domain.usecase

import com.hyperxray.an.feature.telegram.domain.entity.NotificationType
import com.hyperxray.an.feature.telegram.domain.entity.TelegramConfig
import com.hyperxray.an.feature.telegram.domain.entity.TelegramNotification
import com.hyperxray.an.feature.telegram.domain.repository.TelegramRepository

/**
 * Test Telegram connection use case
 */
class TestTelegramConnectionUseCase(
    private val repository: TelegramRepository
) {
    suspend operator fun invoke(config: TelegramConfig): Result<Unit> {
        if (!config.isValid) {
            return Result.failure(IllegalArgumentException("Invalid configuration: bot token or chat ID is empty"))
        }
        
        val testNotification = TelegramNotification(
            type = NotificationType.Manual,
            message = "✅ *Telegram Notification Test*\n\nThis is a test message. Connection successful!"
        )
        
        return repository.sendNotification(testNotification, config)
    }
}

