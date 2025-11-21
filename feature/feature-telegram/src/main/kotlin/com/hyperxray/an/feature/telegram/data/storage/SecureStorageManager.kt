package com.hyperxray.an.feature.telegram.data.storage

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val TAG = "SecureStorageManager"
private const val PREFS_NAME = "telegram_secure_prefs"
private const val KEY_BOT_TOKEN = "bot_token"
private const val KEY_CHAT_ID = "chat_id"

/**
 * Secure storage manager for Telegram bot credentials
 * Uses EncryptedSharedPreferences with Android Keystore
 */
class SecureStorageManager(private val context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveBotToken(token: String) {
        try {
            encryptedPrefs.edit()
                .putString(KEY_BOT_TOKEN, token)
                .apply()
            Log.d(TAG, "Bot token saved securely")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bot token", e)
            throw e
        }
    }

    fun getBotToken(): String? {
        return try {
            encryptedPrefs.getString(KEY_BOT_TOKEN, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get bot token", e)
            null
        }
    }

    fun saveChatId(chatId: String) {
        try {
            encryptedPrefs.edit()
                .putString(KEY_CHAT_ID, chatId)
                .apply()
            Log.d(TAG, "Chat ID saved securely")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save chat ID", e)
            throw e
        }
    }

    fun getChatId(): String? {
        return try {
            encryptedPrefs.getString(KEY_CHAT_ID, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get chat ID", e)
            null
        }
    }

    fun clear() {
        try {
            encryptedPrefs.edit()
                .clear()
                .apply()
            Log.d(TAG, "Secure storage cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear secure storage", e)
        }
    }
}




