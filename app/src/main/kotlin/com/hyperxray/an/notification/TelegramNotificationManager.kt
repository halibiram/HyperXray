package com.hyperxray.an.notification

import android.content.Context
import android.util.Log
import com.hyperxray.an.core.network.dns.DnsCacheManager
import com.hyperxray.an.feature.telegram.data.datasource.TelegramApiDataSource
import com.hyperxray.an.feature.telegram.data.datasource.TelegramConfigDataSource
import com.hyperxray.an.feature.telegram.data.repository.TelegramRepositoryImpl
import com.hyperxray.an.feature.telegram.data.storage.SecureStorageManager
import com.hyperxray.an.feature.telegram.domain.entity.NotificationType
import com.hyperxray.an.feature.telegram.domain.entity.TelegramConfig
import com.hyperxray.an.feature.telegram.domain.entity.TelegramNotification
import com.hyperxray.an.feature.telegram.domain.repository.TelegramRepository
import com.hyperxray.an.feature.telegram.domain.repository.TelegramUpdate
import com.hyperxray.an.feature.telegram.domain.usecase.GetTelegramConfigUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.SendTelegramNotificationUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.ProcessTelegramCommandUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.GetVpnStatusUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.GetPerformanceStatsUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.GetDnsCacheStatsUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.GetAiOptimizerStatusUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.GetDashboardUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.GetNetworkQualityScoreUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.VpnControlUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.MonitoringControlUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.GetTrafficAnalyticsUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.GetDiagnosticTestUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.GetPerformanceGraphUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.ScheduleControlUseCase
import com.hyperxray.an.viewmodel.CoreStatsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TelegramNotificationManager"
private const val COMMAND_POLLING_INTERVAL_MS = 5000L // 5 seconds

/**
 * Telegram notification manager
 * Handles sending notifications and processing bot commands
 */
class TelegramNotificationManager private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val secureStorage = SecureStorageManager(applicationContext)
    private val configDataSource = TelegramConfigDataSource(applicationContext, secureStorage)
    private val apiDataSource = TelegramApiDataSource(applicationContext)
    private val repository: TelegramRepository = TelegramRepositoryImpl(apiDataSource, configDataSource)
    
    private val getConfigUseCase = GetTelegramConfigUseCase(repository)
    private val sendNotificationUseCase = SendTelegramNotificationUseCase(repository)
    
    // Use cases for command processing
    private val getVpnStatusUseCase: GetVpnStatusUseCase = GetVpnStatusUseCaseImpl(applicationContext)
    private val getPerformanceStatsUseCase: GetPerformanceStatsUseCase = GetPerformanceStatsUseCaseImpl(applicationContext)
    private val getDnsCacheStatsUseCase = GetDnsCacheStatsUseCase(applicationContext)
    private val getAiOptimizerStatusUseCase: GetAiOptimizerStatusUseCase = GetAiOptimizerStatusUseCaseImpl(applicationContext)
    private val getDashboardUseCase: GetDashboardUseCase = GetDashboardUseCaseImpl(applicationContext)
    private val getNetworkQualityScoreUseCase: GetNetworkQualityScoreUseCase = GetNetworkQualityScoreUseCaseImpl(applicationContext)
    private val vpnControlUseCase: VpnControlUseCase = VpnControlUseCaseImpl(applicationContext)
    private val monitoringControlUseCase: MonitoringControlUseCase = MonitoringControlUseCaseImpl(applicationContext)
    private val getTrafficAnalyticsUseCase: GetTrafficAnalyticsUseCase = GetTrafficAnalyticsUseCaseImpl(applicationContext)
    private val getDiagnosticTestUseCase: GetDiagnosticTestUseCase = GetDiagnosticTestUseCaseImpl(applicationContext)
    private val getPerformanceGraphUseCase: GetPerformanceGraphUseCase = GetPerformanceGraphUseCaseImpl(applicationContext)
    private val scheduleControlUseCase: ScheduleControlUseCase = ScheduleControlUseCaseImpl(applicationContext)
    private val processCommandUseCase = ProcessTelegramCommandUseCase(
        repository = repository,
        getVpnStatusUseCase = getVpnStatusUseCase,
        getPerformanceStatsUseCase = getPerformanceStatsUseCase,
        getDnsCacheStatsUseCase = getDnsCacheStatsUseCase,
        getAiOptimizerStatusUseCase = getAiOptimizerStatusUseCase,
        getDashboardUseCase = getDashboardUseCase,
        getNetworkQualityScoreUseCase = getNetworkQualityScoreUseCase,
        vpnControlUseCase = vpnControlUseCase,
        monitoringControlUseCase = monitoringControlUseCase,
        getTrafficAnalyticsUseCase = getTrafficAnalyticsUseCase,
        getDiagnosticTestUseCase = getDiagnosticTestUseCase,
        getPerformanceGraphUseCase = getPerformanceGraphUseCase,
        scheduleControlUseCase = scheduleControlUseCase
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var commandPollingJob: kotlinx.coroutines.Job? = null
    private var lastUpdateId: Long? = null

    companion object {
        @Volatile
        private var INSTANCE: TelegramNotificationManager? = null

        fun getInstance(context: Context): TelegramNotificationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TelegramNotificationManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        startCommandPolling()
    }

    /**
     * Send VPN status notification
     */
    suspend fun notifyVpnStatus(isConnected: Boolean) {
        withContext(Dispatchers.IO) {
            val config = getConfig() ?: return@withContext
            
            val message = if (isConnected) {
                "✅ *VPN Connected*\n\nVPN connection established successfully."
            } else {
                "❌ *VPN Disconnected*\n\nVPN connection has been terminated."
            }

            val notification = TelegramNotification(
                type = NotificationType.VpnStatus,
                message = message
            )

            sendNotificationUseCase(notification, config).fold(
                onSuccess = { Log.d(TAG, "VPN status notification sent") },
                onFailure = { error -> Log.e(TAG, "Failed to send VPN status notification", error) }
            )
        }
    }

    /**
     * Send error notification
     */
    suspend fun notifyError(errorMessage: String) {
        withContext(Dispatchers.IO) {
            val config = getConfig() ?: return@withContext

            val message = "⚠️ *Error*\n\n$errorMessage"

            val notification = TelegramNotification(
                type = NotificationType.Error,
                message = message
            )

            sendNotificationUseCase(notification, config).fold(
                onSuccess = { Log.d(TAG, "Error notification sent") },
                onFailure = { error -> Log.e(TAG, "Failed to send error notification", error) }
            )
        }
    }

    /**
     * Send performance metrics notification
     */
    suspend fun notifyPerformanceMetrics(stats: CoreStatsState) {
        withContext(Dispatchers.IO) {
            val config = getConfig() ?: return@withContext

            val message = buildString {
                appendLine("📊 *Performance Metrics*")
                appendLine()
                appendLine("*Upload:* ${formatBytes(stats.uplink)}")
                appendLine("*Download:* ${formatBytes(stats.downlink)}")
                appendLine("*Total:* ${formatBytes(stats.uplink + stats.downlink)}")
                appendLine("*Upload Speed:* ${formatBytesPerSecond(stats.uplinkThroughput)}")
                appendLine("*Download Speed:* ${formatBytesPerSecond(stats.downlinkThroughput)}")
            }

            val notification = TelegramNotification(
                type = NotificationType.PerformanceMetrics,
                message = message
            )

            sendNotificationUseCase(notification, config).fold(
                onSuccess = { Log.d(TAG, "Performance metrics notification sent") },
                onFailure = { error -> Log.e(TAG, "Failed to send performance metrics notification", error) }
            )
        }
    }

    /**
     * Send DNS cache info notification
     */
    suspend fun notifyDnsCacheInfo() {
        withContext(Dispatchers.IO) {
            val config = getConfig() ?: return@withContext

            val stats = DnsCacheManager.getStats()
            val message = "🌐 *DNS Cache Statistics*\n\n$stats"

            val notification = TelegramNotification(
                type = NotificationType.DnsCacheInfo,
                message = message
            )

            sendNotificationUseCase(notification, config).fold(
                onSuccess = { Log.d(TAG, "DNS cache info notification sent") },
                onFailure = { error -> Log.e(TAG, "Failed to send DNS cache info notification", error) }
            )
        }
    }

    /**
     * Start command polling to process bot commands
     */
    private fun startCommandPolling() {
        commandPollingJob?.cancel()
        Log.d(TAG, "Starting command polling (interval: ${COMMAND_POLLING_INTERVAL_MS}ms)")
        commandPollingJob = scope.launch {
            while (isActive) {
                try {
                    val config = getConfig()
                    if (config == null) {
                        Log.d(TAG, "Telegram config is null, skipping polling")
                    } else if (!config.enabled) {
                        Log.d(TAG, "Telegram notifications are disabled, skipping polling")
                    } else if (!config.isValid) {
                        Log.w(TAG, "Telegram config is invalid (botToken: ${config.botToken.take(10)}..., chatId: ${config.chatId.take(10)}...), skipping polling")
                    } else {
                        Log.d(TAG, "Config is valid and enabled, processing commands")
                        processCommands(config)
                    }
                    delay(COMMAND_POLLING_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in command polling: ${e.message}", e)
                    delay(COMMAND_POLLING_INTERVAL_MS * 2) // Wait longer on error
                }
            }
        }
    }

    /**
     * Process incoming bot commands and callback queries
     */
    private suspend fun processCommands(config: TelegramConfig) {
        try {
            Log.d(TAG, "Polling for updates (lastUpdateId: $lastUpdateId)")
            val updates = repository.getUpdates(config, lastUpdateId)
            updates.fold(
                onSuccess = { updateList ->
                    Log.d(TAG, "Received ${updateList.size} updates")
                    if (updateList.isEmpty()) {
                        Log.d(TAG, "No new updates")
                    }
                    updateList.forEach { update ->
                        Log.d(TAG, "Processing update ${update.updateId}")
                        // Handle callback queries (button clicks)
                        update.callbackQuery?.let { callbackQuery ->
                            Log.d(TAG, "Handling callback query: ${callbackQuery.data}")
                            handleCallbackQuery(callbackQuery, config, callbackQuery.chatId ?: config.chatId)
                            lastUpdateId = update.updateId + 1
                            return@forEach
                        }
                        
                        // Handle regular commands
                        val message = update.message
                        message?.text?.let { text ->
                            Log.d(TAG, "Received message: $text from chat: ${message.chatId}")
                            if (text.startsWith("/")) {
                                Log.d(TAG, "Detected command: $text")
                                handleCommand(text, config, message.chatId)
                            } else {
                                Log.d(TAG, "Message is not a command, ignoring")
                            }
                        } ?: run {
                            Log.d(TAG, "Update has no message text")
                        }
                        lastUpdateId = update.updateId + 1
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to get updates: ${error.message}", error)
                    Log.e(TAG, "Error type: ${error.javaClass.simpleName}")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing commands: ${e.message}", e)
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Handle bot command
     */
    private suspend fun handleCommand(command: String, config: TelegramConfig, chatId: String) {
        try {
            Log.d(TAG, "Processing command: $command from chat: $chatId")
            processCommandUseCase(command, config, chatId).fold(
                onSuccess = { response ->
                    Log.d(TAG, "Command processed successfully: $command")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to process command: $command", error)
                    // Send error message to user
                    repository.sendMessage(
                        config,
                        "❌ Error processing command: ${error.message ?: "Unknown error"}"
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception while handling command: $command", e)
        }
    }
    
    /**
     * Handle callback query (button click)
     */
    private suspend fun handleCallbackQuery(
        callbackQuery: TelegramUpdate.CallbackQuery,
        config: TelegramConfig,
        chatId: String
    ) {
        try {
            Log.d(TAG, "Processing callback query: ${callbackQuery.data} from chat: $chatId")
            
            // Answer callback query first (to remove loading state)
            repository.answerCallbackQuery(
                config = config,
                callbackQueryId = callbackQuery.id,
                text = null,
                showAlert = false
            ).fold(
                onSuccess = { },
                onFailure = { error ->
                    Log.e(TAG, "Failed to answer callback query", error)
                }
            )
            
            // Process callback query
            processCommandUseCase.processCallbackQuery(callbackQuery.data, config, chatId).fold(
                onSuccess = { response ->
                    Log.d(TAG, "Callback query processed successfully: ${callbackQuery.data}")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to process callback query: ${callbackQuery.data}", error)
                    // Send error message to user
                    repository.sendMessage(
                        config,
                        "❌ Error processing action: ${error.message ?: "Unknown error"}"
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception while handling callback query: ${callbackQuery.data}", e)
        }
    }

    private suspend fun getConfig(): TelegramConfig? {
        return getConfigUseCase().getOrNull()
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.2f MB", mb)
            kb >= 1.0 -> String.format("%.2f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun formatBytesPerSecond(bytesPerSecond: Double): String {
        val kb = bytesPerSecond / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        
        return when {
            gb >= 1.0 -> String.format("%.2f GB/s", gb)
            mb >= 1.0 -> String.format("%.2f MB/s", mb)
            kb >= 1.0 -> String.format("%.2f KB/s", kb)
            else -> String.format("%.2f B/s", bytesPerSecond)
        }
    }
}

