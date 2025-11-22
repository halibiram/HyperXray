package com.hyperxray.an.feature.telegram.domain.entity

/**
 * Telegram bildirim mesajı
 */
data class TelegramNotification(
    val type: NotificationType,
    val message: String,
    val parseMode: ParseMode = ParseMode.Markdown
) {
    enum class ParseMode {
        Markdown,
        HTML,
        None
    }
}






