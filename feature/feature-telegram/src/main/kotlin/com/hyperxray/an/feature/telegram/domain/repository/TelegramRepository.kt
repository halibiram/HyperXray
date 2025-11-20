package com.hyperxray.an.feature.telegram.domain.repository

import com.hyperxray.an.feature.telegram.domain.entity.TelegramConfig
import com.hyperxray.an.feature.telegram.domain.entity.TelegramNotification

/**
 * Telegram repository interface
 */
interface TelegramRepository {
    suspend fun sendNotification(
        notification: TelegramNotification,
        config: TelegramConfig
    ): Result<Unit>

    suspend fun getConfig(): Result<TelegramConfig>

    suspend fun saveConfig(config: TelegramConfig): Result<Unit>

    suspend fun getUpdates(config: TelegramConfig, offset: Long?): Result<List<TelegramUpdate>>

    suspend fun sendMessage(
        config: TelegramConfig,
        message: String,
        parseMode: String? = null,
        replyMarkup: com.hyperxray.an.feature.telegram.data.model.InlineKeyboardMarkup? = null
    ): Result<Unit>
    
    suspend fun answerCallbackQuery(
        config: TelegramConfig,
        callbackQueryId: String,
        text: String? = null,
        showAlert: Boolean = false
    ): Result<Unit>
}

/**
 * Telegram update model for command polling
 */
data class TelegramUpdate(
    val updateId: Long,
    val message: TelegramMessage?,
    val callbackQuery: CallbackQuery? = null
) {
    /**
     * Callback query model for button clicks
     */
    data class CallbackQuery(
        val id: String,
        val data: String,
        val chatId: String?,
        val messageId: Long?
    )
}

/**
 * Telegram message model
 */
data class TelegramMessage(
    val messageId: Long,
    val chatId: String,
    val text: String?,
    val date: Long
)

