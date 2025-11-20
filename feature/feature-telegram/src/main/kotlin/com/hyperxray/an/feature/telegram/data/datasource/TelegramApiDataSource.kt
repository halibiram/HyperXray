package com.hyperxray.an.feature.telegram.data.datasource

import android.util.Log
import com.hyperxray.an.core.network.http.HttpClientFactory
import com.hyperxray.an.feature.telegram.data.model.InlineKeyboardButton
import com.hyperxray.an.feature.telegram.data.model.InlineKeyboardMarkup
import com.hyperxray.an.feature.telegram.data.model.TelegramApiResponse
import com.hyperxray.an.feature.telegram.data.model.TelegramUpdateResponse
import com.hyperxray.an.feature.telegram.domain.entity.TelegramConfig
import com.hyperxray.an.feature.telegram.domain.entity.TelegramNotification
import com.hyperxray.an.feature.telegram.domain.repository.TelegramMessage
import com.hyperxray.an.feature.telegram.domain.repository.TelegramUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "TelegramApiDataSource"
private const val BASE_URL = "https://api.telegram.org/bot"
private const val SEND_MESSAGE_URL = "/sendMessage"
private const val GET_UPDATES_URL = "/getUpdates"
private const val ANSWER_CALLBACK_QUERY_URL = "/answerCallbackQuery"

/**
 * Telegram Bot API data source
 */
class TelegramApiDataSource {
    private val httpClient: OkHttpClient = HttpClientFactory.createHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sendMessage(
        config: TelegramConfig,
        message: String,
        parseMode: String? = null,
        replyMarkup: InlineKeyboardMarkup? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Validate bot token format
            if (config.botToken.isBlank() || !config.botToken.contains(":")) {
                Log.e(TAG, "Invalid bot token format")
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid bot token format. Bot token should be in format: '123456789:ABCdefGHIjklMNOpqrsTUVwxyz'")
                )
            }
            
            // Validate chat ID
            if (config.chatId.isBlank()) {
                Log.e(TAG, "Chat ID is empty")
                return@withContext Result.failure(
                    IllegalArgumentException("Chat ID cannot be empty")
                )
            }
            
            val url = "$BASE_URL${config.botToken}$SEND_MESSAGE_URL"
            
            // Process message based on parse mode
            val processedMessage = when (parseMode) {
                "Markdown", "MarkdownV2" -> {
                    // For Markdown, escape special characters to prevent 400 errors
                    escapeMarkdown(message)
                }
                else -> message
            }
            
            val requestBody = buildJsonBody(
                chatId = config.chatId,
                text = processedMessage,
                parseMode = parseMode,
                replyMarkup = replyMarkup
            )

            Log.d(TAG, "Sending message to Telegram API: url=$url, chatId=${config.chatId.take(5)}..., messageLength=${processedMessage.length}")

            val request = Request.Builder()
                .url(url)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to send message: HTTP ${response.code} - $responseBody")
                
                // Try to parse error response for better error message
                val errorMessage = try {
                    val errorResponse = json.decodeFromString<TelegramApiResponse>(responseBody)
                    val description = errorResponse.description ?: "Unknown error"
                    
                    // Provide user-friendly error messages
                    when {
                        description.contains("chat not found", ignoreCase = true) -> {
                            "Chat not found. Please make sure:\n" +
                            "1. You have started a conversation with the bot first\n" +
                            "2. The chat ID is correct (use @userinfobot to get your chat ID)\n" +
                            "3. The chat ID is your user ID, not the bot token"
                        }
                        description.contains("bots can't send messages to bots", ignoreCase = true) -> {
                            "Error: You cannot use a bot ID as chat ID.\n" +
                            "Please use your personal Telegram user ID instead.\n" +
                            "To get your chat ID:\n" +
                            "1. Start a chat with @userinfobot\n" +
                            "2. Send /start command\n" +
                            "3. Copy your user ID (a number like 123456789)"
                        }
                        description.contains("Forbidden", ignoreCase = true) -> {
                            "Access forbidden. Please check:\n" +
                            "1. Bot token is correct\n" +
                            "2. Chat ID is correct (your user ID, not bot ID)\n" +
                            "3. You have started a conversation with the bot"
                        }
                        description.contains("Unauthorized", ignoreCase = true) -> {
                            "Unauthorized: Bot token is invalid or expired"
                        }
                        else -> description
                    }
                } catch (e: Exception) {
                    responseBody
                }
                
                return@withContext Result.failure(
                    IOException("HTTP ${response.code}: $errorMessage")
                )
            }

            val apiResponse = json.decodeFromString<TelegramApiResponse>(responseBody)
            
            if (!apiResponse.ok) {
                Log.e(TAG, "Telegram API error: ${apiResponse.description}")
                return@withContext Result.failure(
                    IOException("Telegram API error: ${apiResponse.description ?: "Unknown error"}")
                )
            }

            Log.d(TAG, "Message sent successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            Result.failure(e)
        }
    }
    
    /**
     * Escape Markdown special characters to prevent parsing errors
     * Only escape characters that are not part of intentional formatting
     */
    private fun escapeMarkdown(text: String): String {
        // If text already contains Markdown formatting, don't escape
        // This is a simple check - if text has * or _ in pairs, assume it's intentional
        val hasIntentionalFormatting = text.contains(Regex("\\*[^*]+\\*|_[^_]+_"))
        
        if (hasIntentionalFormatting) {
            // Only escape characters that might cause issues but keep intentional formatting
            return text
                .replace("\\", "\\\\")  // Escape backslashes
                .replace("`", "\\`")     // Escape code blocks
        }
        
        // If no intentional formatting, escape all special characters
        return text
            .replace("_", "\\_")
            .replace("*", "\\*")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("~", "\\~")
            .replace("`", "\\`")
            .replace(">", "\\>")
            .replace("#", "\\#")
            .replace("+", "\\+")
            .replace("-", "\\-")
            .replace("=", "\\=")
            .replace("|", "\\|")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace(".", "\\.")
            .replace("!", "\\!")
    }
    
    suspend fun getUpdates(
        config: TelegramConfig,
        offset: Long? = null,
        timeout: Int = 30
    ): Result<List<TelegramUpdate>> = withContext(Dispatchers.IO) {
        try {
            val url = buildString {
                append("$BASE_URL${config.botToken}$GET_UPDATES_URL")
                append("?timeout=$timeout")
                offset?.let { append("&offset=$it") }
            }

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to get updates: ${response.code} - $responseBody")
                return@withContext Result.failure(
                    IOException("HTTP ${response.code}: $responseBody")
                )
            }

            val apiResponse = json.decodeFromString<TelegramUpdateResponse>(responseBody)
            
            if (!apiResponse.ok) {
                Log.e(TAG, "Telegram API error: ${apiResponse.description}")
                return@withContext Result.failure(
                    IOException("Telegram API error: ${apiResponse.description}")
                )
            }

            val updates = apiResponse.result.mapNotNull { updateResult ->
                // Process callback queries (button clicks)
                val callbackQuery = updateResult.callbackQuery
                if (callbackQuery != null && callbackQuery.data != null) {
                    TelegramUpdate(
                        updateId = updateResult.updateId,
                        message = null,
                        callbackQuery = TelegramUpdate.CallbackQuery(
                            id = callbackQuery.id,
                            data = callbackQuery.data,
                            chatId = callbackQuery.message?.chat?.id?.toString(),
                            messageId = callbackQuery.message?.messageId
                        )
                    )
                }
                // Process regular messages
                else {
                    val message = updateResult.message
                    if (message != null && message.messageId != null && message.chat != null && message.date != null) {
                        TelegramUpdate(
                            updateId = updateResult.updateId,
                            message = TelegramMessage(
                                messageId = message.messageId,
                                chatId = message.chat.id.toString(),
                                text = message.text,
                                date = message.date
                            ),
                            callbackQuery = null
                        )
                    } else {
                        // Skip updates without valid message or callback query
                        null
                    }
                }
            }

            Result.success(updates)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting updates", e)
            Result.failure(e)
        }
    }

    /**
     * Answer callback query (button click response)
     */
    suspend fun answerCallbackQuery(
        config: TelegramConfig,
        callbackQueryId: String,
        text: String? = null,
        showAlert: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL${config.botToken}$ANSWER_CALLBACK_QUERY_URL"
            
            val requestJson = org.json.JSONObject()
            requestJson.put("callback_query_id", callbackQueryId)
            text?.let { requestJson.put("text", it) }
            requestJson.put("show_alert", showAlert)
            
            val request = Request.Builder()
                .url(url)
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to answer callback query: HTTP ${response.code} - $responseBody")
                return@withContext Result.failure(
                    IOException("HTTP ${response.code}: $responseBody")
                )
            }
            
            val apiResponse = json.decodeFromString<TelegramApiResponse>(responseBody)
            if (!apiResponse.ok) {
                Log.e(TAG, "Telegram API error: ${apiResponse.description}")
                return@withContext Result.failure(
                    IOException("Telegram API error: ${apiResponse.description ?: "Unknown error"}")
                )
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error answering callback query", e)
            Result.failure(e)
        }
    }
    
    private fun buildJsonBody(
        chatId: String,
        text: String,
        parseMode: String?,
        replyMarkup: InlineKeyboardMarkup? = null
    ): String {
        val json = org.json.JSONObject()
        
        // Chat ID can be either string or integer in Telegram API
        // Try to parse as integer first, if it fails use as string
        val chatIdValue = try {
            chatId.toLongOrNull() ?: chatId
        } catch (e: Exception) {
            chatId
        }
        json.put("chat_id", chatIdValue)
        
        // Telegram has a 4096 character limit per message
        val messageText = if (text.length > 4096) {
            Log.w(TAG, "Message too long (${text.length} chars), truncating to 4096")
            text.take(4096) + "\n\n... (message truncated)"
        } else {
            text
        }
        json.put("text", messageText)
        
        parseMode?.let {
            json.put("parse_mode", it)
        }
        
        // Add inline keyboard if provided
        replyMarkup?.let { markup ->
            val keyboardJson = org.json.JSONArray()
            markup.inlineKeyboard.forEach { row ->
                val rowJson = org.json.JSONArray()
                row.forEach { button ->
                    val buttonJson = org.json.JSONObject()
                    buttonJson.put("text", button.text)
                    button.callbackData?.let { buttonJson.put("callback_data", it) }
                    button.url?.let { buttonJson.put("url", it) }
                    rowJson.put(buttonJson)
                }
                keyboardJson.put(rowJson)
            }
            val replyMarkupJson = org.json.JSONObject()
            replyMarkupJson.put("inline_keyboard", keyboardJson)
            json.put("reply_markup", replyMarkupJson)
        }
        
        return json.toString()
    }
}

