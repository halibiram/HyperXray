package com.hyperxray.an.notification

import android.content.Context
import android.content.SharedPreferences
import com.hyperxray.an.feature.telegram.domain.usecase.ScheduleControlUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.GetDashboardUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.GetTelegramConfigUseCase
import com.hyperxray.an.feature.telegram.domain.repository.TelegramRepository
import com.hyperxray.an.feature.telegram.data.datasource.TelegramApiDataSource
import com.hyperxray.an.feature.telegram.data.datasource.TelegramConfigDataSource
import com.hyperxray.an.feature.telegram.data.storage.SecureStorageManager
import kotlinx.coroutines.*
import java.util.Calendar

private const val PREFS_NAME = "telegram_schedule"
private const val KEY_DAILY_ENABLED = "daily_enabled"
private const val KEY_DAILY_TIME = "daily_time"
private const val KEY_WEEKLY_ENABLED = "weekly_enabled"
private const val KEY_WEEKLY_DAY = "weekly_day"
private const val KEY_WEEKLY_TIME = "weekly_time"

/**
 * Implementation of ScheduleControlUseCase
 */
class ScheduleControlUseCaseImpl(
    private val context: Context
) : ScheduleControlUseCase {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureStorage = SecureStorageManager(context)
    private val configDataSource = TelegramConfigDataSource(context, secureStorage)
    private val apiDataSource = TelegramApiDataSource(context)
    private val repository: TelegramRepository = com.hyperxray.an.feature.telegram.data.repository.TelegramRepositoryImpl(apiDataSource, configDataSource)
    private val getConfigUseCase = GetTelegramConfigUseCase(repository)
    private val getDashboardUseCase: GetDashboardUseCase = GetDashboardUseCaseImpl(context)
    
    private val scheduleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dailyJob: Job? = null
    private var weeklyJob: Job? = null
    
    override suspend fun setSchedule(scheduleType: String, enabled: Boolean, time: String?): Result<String> = withContext(Dispatchers.IO) {
        try {
            when (scheduleType.lowercase()) {
                "daily", "day", "d" -> {
                    if (enabled && time != null) {
                        val timeParts = time.split(":")
                        if (timeParts.size != 2) {
                            return@withContext Result.failure(
                                IllegalArgumentException("Time format must be HH:MM (e.g., 09:00)")
                            )
                        }
                        prefs.edit()
                            .putBoolean(KEY_DAILY_ENABLED, true)
                            .putString(KEY_DAILY_TIME, time)
                            .apply()
                        startDailySchedule(timeParts[0].toInt(), timeParts[1].toInt())
                        Result.success("✅ Daily schedule enabled at $time")
                    } else {
                        prefs.edit().putBoolean(KEY_DAILY_ENABLED, false).apply()
                        dailyJob?.cancel()
                        dailyJob = null
                        Result.success("✅ Daily schedule disabled")
                    }
                }
                "weekly", "week", "w" -> {
                    if (enabled && time != null) {
                        val timeParts = time.split(":")
                        if (timeParts.size != 2) {
                            return@withContext Result.failure(
                                IllegalArgumentException("Time format must be HH:MM (e.g., 09:00)")
                            )
                        }
                        prefs.edit()
                            .putBoolean(KEY_WEEKLY_ENABLED, true)
                            .putString(KEY_WEEKLY_TIME, time)
                            .apply()
                        Result.success("✅ Weekly schedule enabled at $time (every Monday)")
                    } else {
                        prefs.edit().putBoolean(KEY_WEEKLY_ENABLED, false).apply()
                        weeklyJob?.cancel()
                        weeklyJob = null
                        Result.success("✅ Weekly schedule disabled")
                    }
                }
                else -> Result.failure(IllegalArgumentException("Unknown schedule type: $scheduleType"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getScheduleStatus(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val dailyEnabled = prefs.getBoolean(KEY_DAILY_ENABLED, false)
            val dailyTime = prefs.getString(KEY_DAILY_TIME, "09:00") ?: "09:00"
            val weeklyEnabled = prefs.getBoolean(KEY_WEEKLY_ENABLED, false)
            val weeklyTime = prefs.getString(KEY_WEEKLY_TIME, "09:00") ?: "09:00"
            
            val message = buildString {
                appendLine("<b>📅 SCHEDULED REPORTS</b>")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                
                appendLine("<b>📆 Daily Reports:</b>")
                if (dailyEnabled) {
                    appendLine("Status: ✅ <b>ENABLED</b>")
                    appendLine("Time: <b>$dailyTime</b>")
                } else {
                    appendLine("Status: ❌ <b>DISABLED</b>")
                }
                appendLine()
                
                appendLine("<b>📅 Weekly Reports:</b>")
                if (weeklyEnabled) {
                    appendLine("Status: ✅ <b>ENABLED</b>")
                    appendLine("Time: <b>$weeklyTime</b> (Monday)")
                } else {
                    appendLine("Status: ❌ <b>DISABLED</b>")
                }
                
                appendLine()
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("💡 Use /schedule daily enable 09:00")
                appendLine("💡 Use /schedule weekly enable 09:00")
                appendLine("💡 Use /schedule daily disable to disable")
            }
            
            Result.success(message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun startDailySchedule(hour: Int, minute: Int) {
        dailyJob?.cancel()
        
        dailyJob = scheduleScope.launch {
            while (isActive) {
                try {
                    val now = Calendar.getInstance()
                    val target = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        if (before(now)) {
                            add(Calendar.DAY_OF_YEAR, 1)
                        }
                    }
                    
                    val delayMs = target.timeInMillis - now.timeInMillis
                    delay(delayMs)
                    
                    // Send scheduled report
                    val config = getConfigUseCase().getOrNull()
                    if (config != null && config.enabled && config.isValid) {
                        val dashboard = getDashboardUseCase().getOrNull()
                        if (dashboard != null) {
                            repository.sendMessage(
                                config = config,
                                message = "📅 <b>Daily Scheduled Report</b>\n\n$dashboard",
                                parseMode = "HTML"
                            )
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ScheduleControl", "Error in daily schedule", e)
                }
            }
        }
    }
    
    fun cleanup() {
        dailyJob?.cancel()
        weeklyJob?.cancel()
        scheduleScope.cancel()
    }
}

