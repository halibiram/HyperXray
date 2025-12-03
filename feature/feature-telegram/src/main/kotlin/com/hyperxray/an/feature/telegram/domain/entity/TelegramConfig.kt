package com.hyperxray.an.feature.telegram.domain.entity

/**
 * Telegram bot konfigürasyonu
 */
data class TelegramConfig(
    val botToken: String,
    val chatId: String,
    val enabled: Boolean = false,
    val notifyVpnStatus: Boolean = true,
    val notifyErrors: Boolean = true,
    val notifyPerformance: Boolean = false,
    val notifyManual: Boolean = true
) {
    val isValid: Boolean
        get() = botToken.isNotBlank() && chatId.isNotBlank()
}






