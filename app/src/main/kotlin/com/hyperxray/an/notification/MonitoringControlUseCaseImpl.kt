package com.hyperxray.an.notification

import android.content.Context
import android.content.SharedPreferences
import com.hyperxray.an.feature.telegram.domain.usecase.MonitoringControlUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.GetDashboardUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.GetTelegramConfigUseCase
import com.hyperxray.an.feature.telegram.domain.repository.TelegramRepository
import com.hyperxray.an.feature.telegram.data.repository.TelegramRepositoryImpl
import com.hyperxray.an.feature.telegram.data.datasource.TelegramApiDataSource
import com.hyperxray.an.feature.telegram.data.datasource.TelegramConfigDataSource
import com.hyperxray.an.feature.telegram.data.storage.SecureStorageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val PREFS_NAME = "telegram_monitoring"
private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
private const val KEY_MONITORING_INTERVAL = "monitoring_interval"
private const val KEY_MONITORING_JOB_ID = "monitoring_job_id"

/**
 * Implementation of MonitoringControlUseCase
 */
class MonitoringControlUseCaseImpl(
    private val context: Context
) : MonitoringControlUseCase {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureStorage = SecureStorageManager(context)
    private val configDataSource = TelegramConfigDataSource(context, secureStorage)
    private val apiDataSource = TelegramApiDataSource(context)
    private val repository: TelegramRepository = com.hyperxray.an.feature.telegram.data.repository.TelegramRepositoryImpl(apiDataSource, configDataSource)
    private val getConfigUseCase = GetTelegramConfigUseCase(repository)
    private val getDashboardUseCase: GetDashboardUseCase = GetDashboardUseCaseImpl(context)
    
    private val monitoringScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitoringJob: Job? = null
    
    private val _isMonitoringActive = MutableStateFlow(false)
    val isMonitoringActive: StateFlow<Boolean> = _isMonitoringActive
    
    init {
        // Restore monitoring state on init
        if (prefs.getBoolean(KEY_MONITORING_ENABLED, false)) {
            val interval = prefs.getInt(KEY_MONITORING_INTERVAL, 15)
            startMonitoringInternal(interval)
        }
    }
    
    override suspend fun startMonitoring(intervalMinutes: Int): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (intervalMinutes < 1 || intervalMinutes > 1440) {
                return@withContext Result.failure(
                    IllegalArgumentException("Interval must be between 1 and 1440 minutes")
                )
            }
            
            // Stop existing monitoring if any
            stopMonitoringInternal()
            
            // Start new monitoring
            startMonitoringInternal(intervalMinutes)
            
            // Save state
            prefs.edit()
                .putBoolean(KEY_MONITORING_ENABLED, true)
                .putInt(KEY_MONITORING_INTERVAL, intervalMinutes)
                .apply()
            
            Result.success(
                "✅ Monitoring started!\n\n" +
                "📊 Reports will be sent every <b>$intervalMinutes minutes</b>\n" +
                "Use /monitor stop to disable"
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun stopMonitoring(): Result<String> = withContext(Dispatchers.IO) {
        try {
            stopMonitoringInternal()
            
            // Save state
            prefs.edit()
                .putBoolean(KEY_MONITORING_ENABLED, false)
                .apply()
            
            Result.success("✅ Monitoring stopped successfully!")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getMonitoringStatus(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val isActive = _isMonitoringActive.value
            val interval = prefs.getInt(KEY_MONITORING_INTERVAL, 15)
            
            val message = buildString {
                appendLine("<b>📊 MONITORING STATUS</b>")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                
                if (isActive) {
                    appendLine("Status: ✅ <b>ACTIVE</b>")
                    appendLine("Interval: <b>$interval minutes</b>")
                    appendLine()
                    appendLine("Reports are being sent automatically.")
                } else {
                    appendLine("Status: ❌ <b>INACTIVE</b>")
                    appendLine()
                    appendLine("Use /monitor start [interval] to enable")
                    appendLine("Example: /monitor start 15")
                }
                
                appendLine()
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("💡 Use /monitor start [5-1440] to start")
                appendLine("💡 Use /monitor stop to disable")
            }
            
            Result.success(message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun startMonitoringInternal(intervalMinutes: Int) {
        monitoringJob?.cancel()
        
        _isMonitoringActive.value = true
        
        monitoringJob = monitoringScope.launch {
            while (isActive) {
                try {
                    // Get config
                    val config = getConfigUseCase().getOrNull()
                    if (config != null && config.enabled && config.isValid) {
                        // Get dashboard metrics
                        val dashboard = getDashboardUseCase().getOrNull()
                        if (dashboard != null) {
                            // Send dashboard report
                            repository.sendMessage(
                                config = config,
                                message = dashboard,
                                parseMode = "HTML"
                            )
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringControl", "Error sending monitoring report", e)
                }
                
                // Wait for interval
                delay(intervalMinutes * 60 * 1000L)
            }
        }
    }
    
    private fun stopMonitoringInternal() {
        monitoringJob?.cancel()
        monitoringJob = null
        _isMonitoringActive.value = false
    }
    
    fun cleanup() {
        stopMonitoringInternal()
        monitoringScope.cancel()
    }
}

