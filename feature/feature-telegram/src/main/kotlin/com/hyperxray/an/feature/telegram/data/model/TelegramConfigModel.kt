package com.hyperxray.an.feature.telegram.data.model

import com.hyperxray.an.feature.telegram.domain.entity.TelegramConfig

/**
 * Data layer model for Telegram configuration
 */
data class TelegramConfigModel(
    val botToken: String,
    val chatId: String,
    val enabled: Boolean = false,
    val notifyVpnStatus: Boolean = true,
    val notifyErrors: Boolean = true,
    val notifyPerformance: Boolean = false,
    val notifyDnsCache: Boolean = false,
    val notifyManual: Boolean = true
) {
    fun toDomain(): TelegramConfig {
        return TelegramConfig(
            botToken = botToken,
            chatId = chatId,
            enabled = enabled,
            notifyVpnStatus = notifyVpnStatus,
            notifyErrors = notifyErrors,
            notifyPerformance = notifyPerformance,
            notifyDnsCache = notifyDnsCache,
            notifyManual = notifyManual
        )
    }

    companion object {
        fun fromDomain(config: TelegramConfig): TelegramConfigModel {
            return TelegramConfigModel(
                botToken = config.botToken,
                chatId = config.chatId,
                enabled = config.enabled,
                notifyVpnStatus = config.notifyVpnStatus,
                notifyErrors = config.notifyErrors,
                notifyPerformance = config.notifyPerformance,
                notifyDnsCache = config.notifyDnsCache,
                notifyManual = config.notifyManual
            )
        }
    }
}






