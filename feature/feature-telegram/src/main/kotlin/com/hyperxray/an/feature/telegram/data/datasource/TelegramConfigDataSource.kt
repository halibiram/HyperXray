package com.hyperxray.an.feature.telegram.data.datasource

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log
import com.hyperxray.an.feature.telegram.data.model.TelegramConfigModel
import com.hyperxray.an.feature.telegram.data.storage.SecureStorageManager

private const val TAG = "TelegramConfigDataSource"
private const val PREFS_AUTHORITY = "com.hyperxray.an.prefsprovider"
private const val PREFS_PATH = "prefs"

/**
 * Data source for Telegram configuration
 * Uses ContentProvider for non-sensitive data and SecureStorageManager for credentials
 */
class TelegramConfigDataSource(
    private val context: Context,
    private val secureStorage: SecureStorageManager
) {
    private val contentResolver: ContentResolver = context.contentResolver

    private fun getPrefData(key: String): String? {
        val uri = Uri.parse("content://$PREFS_AUTHORITY/$PREFS_PATH/$key")
        return try {
            contentResolver.query(uri, arrayOf("pref_value"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val valueIndex = cursor.getColumnIndex("pref_value")
                    if (valueIndex >= 0) {
                        cursor.getString(valueIndex)
                    } else null
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading preference: $key", e)
            null
        }
    }

    private fun setPrefData(key: String, value: String) {
        val uri = Uri.parse("content://$PREFS_AUTHORITY/$PREFS_PATH/$key")
        try {
            val values = ContentValues().apply {
                put("pref_value", value)
            }
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing preference: $key", e)
        }
    }

    private fun setPrefData(key: String, value: Boolean) {
        setPrefData(key, value.toString())
    }

    fun getConfig(): TelegramConfigModel? {
        return try {
            val botToken = secureStorage.getBotToken() ?: return null
            val chatId = secureStorage.getChatId() ?: return null

            TelegramConfigModel(
                botToken = botToken,
                chatId = chatId,
                enabled = getPrefData("TelegramEnabled")?.toBoolean() ?: false,
                notifyVpnStatus = getPrefData("TelegramNotifyVpnStatus")?.toBoolean() ?: true,
                notifyErrors = getPrefData("TelegramNotifyErrors")?.toBoolean() ?: true,
                notifyPerformance = getPrefData("TelegramNotifyPerformance")?.toBoolean() ?: false,
                notifyManual = getPrefData("TelegramNotifyManual")?.toBoolean() ?: true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get config", e)
            null
        }
    }

    fun saveConfig(config: TelegramConfigModel): Boolean {
        return try {
            // Save sensitive data to secure storage
            secureStorage.saveBotToken(config.botToken)
            secureStorage.saveChatId(config.chatId)

            // Save non-sensitive preferences
            setPrefData("TelegramEnabled", config.enabled)
            setPrefData("TelegramNotifyVpnStatus", config.notifyVpnStatus)
            setPrefData("TelegramNotifyErrors", config.notifyErrors)
            setPrefData("TelegramNotifyPerformance", config.notifyPerformance)
            setPrefData("TelegramNotifyManual", config.notifyManual)

            Log.d(TAG, "Config saved successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
            false
        }
    }

    fun clearConfig() {
        try {
            secureStorage.clear()
            setPrefData("TelegramEnabled", false)
            setPrefData("TelegramNotifyVpnStatus", true)
            setPrefData("TelegramNotifyErrors", true)
            setPrefData("TelegramNotifyPerformance", false)
            setPrefData("TelegramNotifyManual", true)
            Log.d(TAG, "Config cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear config", e)
        }
    }
}

