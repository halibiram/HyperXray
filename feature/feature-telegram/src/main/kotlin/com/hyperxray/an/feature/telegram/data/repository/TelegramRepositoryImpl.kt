package com.hyperxray.an.feature.telegram.data.repository

import com.hyperxray.an.feature.telegram.data.datasource.TelegramApiDataSource
import com.hyperxray.an.feature.telegram.data.datasource.TelegramConfigDataSource
import com.hyperxray.an.feature.telegram.data.model.InlineKeyboardMarkup
import com.hyperxray.an.feature.telegram.data.model.TelegramConfigModel
import com.hyperxray.an.feature.telegram.domain.entity.TelegramConfig
import com.hyperxray.an.feature.telegram.domain.entity.TelegramNotification
import com.hyperxray.an.feature.telegram.domain.repository.TelegramMessage
import com.hyperxray.an.feature.telegram.domain.repository.TelegramRepository
import com.hyperxray.an.feature.telegram.domain.repository.TelegramUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Telegram repository implementation
 */
class TelegramRepositoryImpl(
    private val apiDataSource: TelegramApiDataSource,
    private val configDataSource: TelegramConfigDataSource
) : TelegramRepository {

    override suspend fun sendNotification(
        notification: TelegramNotification,
        config: TelegramConfig
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // For reliability, use None parse mode by default
        // Markdown can cause 400 errors if not properly escaped
        val parseMode = when (notification.parseMode) {
            TelegramNotification.ParseMode.Markdown -> null // Disable Markdown to avoid 400 errors
            TelegramNotification.ParseMode.HTML -> "HTML"
            TelegramNotification.ParseMode.None -> null
        }
        
        apiDataSource.sendMessage(
            config = config,
            message = notification.message,
            parseMode = parseMode
        )
    }

    override suspend fun getConfig(): Result<TelegramConfig> = withContext(Dispatchers.IO) {
        val configModel = configDataSource.getConfig()
        if (configModel != null) {
            Result.success(configModel.toDomain())
        } else {
            Result.failure(IllegalStateException("Telegram configuration not found"))
        }
    }

    override suspend fun saveConfig(config: TelegramConfig): Result<Unit> = withContext(Dispatchers.IO) {
        val configModel = TelegramConfigModel.fromDomain(config)
        val success = configDataSource.saveConfig(configModel)
        if (success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Failed to save Telegram configuration"))
        }
    }

    override suspend fun getUpdates(
        config: TelegramConfig,
        offset: Long?
    ): Result<List<TelegramUpdate>> = withContext(Dispatchers.IO) {
        apiDataSource.getUpdates(config, offset)
    }

    override suspend fun sendMessage(
        config: TelegramConfig,
        message: String,
        parseMode: String?,
        replyMarkup: InlineKeyboardMarkup?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        apiDataSource.sendMessage(config, message, parseMode, replyMarkup)
    }
    
    override suspend fun answerCallbackQuery(
        config: TelegramConfig,
        callbackQueryId: String,
        text: String?,
        showAlert: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        apiDataSource.answerCallbackQuery(config, callbackQueryId, text, showAlert)
    }
}

