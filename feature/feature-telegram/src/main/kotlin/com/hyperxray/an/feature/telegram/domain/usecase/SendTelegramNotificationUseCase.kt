package com.hyperxray.an.feature.telegram.domain.usecase

import com.hyperxray.an.feature.telegram.domain.entity.NotificationType
import com.hyperxray.an.feature.telegram.domain.entity.TelegramNotification
import com.hyperxray.an.feature.telegram.domain.repository.TelegramRepository

/**
 * Send Telegram notification use case
 */
class SendTelegramNotificationUseCase(
    private val repository: TelegramRepository
) {
    suspend operator fun invoke(
        notification: TelegramNotification,
        config: com.hyperxray.an.feature.telegram.domain.entity.TelegramConfig
    ): Result<Unit> {
        // Check if notification type is enabled
        val isEnabled = when (notification.type) {
            is NotificationType.VpnStatus -> config.notifyVpnStatus
            is NotificationType.Error -> config.notifyErrors
            is NotificationType.PerformanceMetrics -> config.notifyPerformance
            is NotificationType.DnsCacheInfo -> config.notifyDnsCache
            is NotificationType.Manual -> config.notifyManual
        }
        
        if (!isEnabled || !config.enabled) {
            return Result.failure(IllegalStateException("Notification type is disabled"))
        }
        
        return repository.sendNotification(notification, config)
    }
}

