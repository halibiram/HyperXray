package com.hyperxray.an.feature.telegram.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Telegram API response models
 */
@Serializable
data class TelegramApiResponse(
    val ok: Boolean,
    val result: TelegramMessageResult? = null,
    val description: String? = null,
    @SerialName("error_code")
    val errorCode: Int? = null
)

@Serializable
data class TelegramMessageResult(
    @SerialName("message_id")
    val messageId: Long? = null, // Can be null in some cases
    val chat: TelegramChat? = null, // Can be null in some cases
    val date: Long? = null, // Can be null in some cases
    val text: String? = null
)

@Serializable
data class TelegramChat(
    val id: Long,
    val type: String
)

@Serializable
data class TelegramUpdateResponse(
    val ok: Boolean,
    val result: List<TelegramUpdateResult> = emptyList(),
    val description: String? = null,
    @SerialName("error_code")
    val errorCode: Int? = null
)

@Serializable
data class TelegramUpdateResult(
    @SerialName("update_id")
    val updateId: Long,
    val message: TelegramUpdateMessage? = null,
    @SerialName("callback_query")
    val callbackQuery: TelegramCallbackQuery? = null
)

@Serializable
data class TelegramUpdateMessage(
    @SerialName("message_id")
    val messageId: Long? = null, // Can be null for some update types (edited_message, channel_post, etc.)
    val chat: TelegramChat? = null, // Can be null for some update types
    val date: Long? = null, // Can be null for some update types
    val text: String? = null
)

/**
 * Inline keyboard button model
 */
data class InlineKeyboardButton(
    val text: String,
    val callbackData: String? = null,
    val url: String? = null
)

/**
 * Inline keyboard markup model
 */
data class InlineKeyboardMarkup(
    val inlineKeyboard: List<List<InlineKeyboardButton>>
)

/**
 * Callback query model for button clicks
 */
@Serializable
data class TelegramCallbackQuery(
    val id: String,
    val from: TelegramUser? = null,
    val message: TelegramUpdateMessage? = null,
    @SerialName("chat_instance")
    val chatInstance: String? = null,
    val data: String? = null
)

@Serializable
data class TelegramUser(
    val id: Long,
    @SerialName("is_bot")
    val isBot: Boolean = false,
    @SerialName("first_name")
    val firstName: String? = null,
    @SerialName("last_name")
    val lastName: String? = null,
    val username: String? = null
)

