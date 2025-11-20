package com.hyperxray.an.feature.telegram.domain.usecase

import com.hyperxray.an.feature.telegram.data.model.InlineKeyboardButton
import com.hyperxray.an.feature.telegram.data.model.InlineKeyboardMarkup
import com.hyperxray.an.feature.telegram.domain.entity.NotificationType
import com.hyperxray.an.feature.telegram.domain.entity.TelegramNotification
import com.hyperxray.an.feature.telegram.domain.entity.TelegramConfig
import com.hyperxray.an.feature.telegram.domain.repository.TelegramRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process Telegram command use case
 */
class ProcessTelegramCommandUseCase(
    private val repository: TelegramRepository,
    private val getVpnStatusUseCase: GetVpnStatusUseCase,
    private val getPerformanceStatsUseCase: GetPerformanceStatsUseCase,
    private val getDnsCacheStatsUseCase: GetDnsCacheStatsUseCase,
    private val getAiOptimizerStatusUseCase: GetAiOptimizerStatusUseCase? = null,
    private val getDashboardUseCase: GetDashboardUseCase? = null,
    private val getNetworkQualityScoreUseCase: GetNetworkQualityScoreUseCase? = null,
    private val vpnControlUseCase: VpnControlUseCase? = null,
    private val monitoringControlUseCase: MonitoringControlUseCase? = null,
    private val getTrafficAnalyticsUseCase: GetTrafficAnalyticsUseCase? = null,
    private val getDiagnosticTestUseCase: GetDiagnosticTestUseCase? = null,
    private val getPerformanceGraphUseCase: GetPerformanceGraphUseCase? = null,
    private val scheduleControlUseCase: ScheduleControlUseCase? = null
) {
    suspend operator fun invoke(
        command: String,
        config: TelegramConfig,
        chatId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!config.isValid || !config.enabled) {
            return@withContext Result.failure(IllegalStateException("Telegram notifications are disabled"))
        }

        val (response, keyboard) = when (command.lowercase().trim()) {
            "/start" -> getStartMessageWithMenu()
            "/help" -> getHelpMessageWithMenu()
            "/status" -> {
                val status = getVpnStatusUseCase().getOrNull() ?: "VPN Status: Unknown"
                Pair(status, getMainMenuKeyboard())
            }
            "/stats" -> {
                val stats = getPerformanceStatsUseCase().getOrNull() ?: "Performance Stats: Unknown"
                Pair(stats, getMainMenuKeyboard())
            }
            "/dns" -> {
                val dns = getDnsCacheStatsUseCase().getOrNull() ?: "DNS Cache Stats: Unknown"
                Pair(dns, getMainMenuKeyboard())
            }
            "/info" -> {
                val info = getInfoMessage(
                    vpnStatus = getVpnStatusUseCase().getOrNull() ?: "Unknown",
                    stats = getPerformanceStatsUseCase().getOrNull() ?: "Unknown",
                    dns = getDnsCacheStatsUseCase().getOrNull() ?: "Unknown"
                )
                Pair(info, getMainMenuKeyboard())
            }
            "/ai_status" -> {
                val aiStatus = getAiOptimizerStatusUseCase?.invoke()?.getOrNull() 
                    ?: "AI Optimizer Status: Not Available"
                Pair(aiStatus, getMainMenuKeyboard())
            }
            "/dashboard" -> {
                val dashboard = getDashboardUseCase?.invoke()?.getOrNull() 
                    ?: "Dashboard: Not Available"
                Pair(dashboard, getMainMenuKeyboard())
            }
            "/quality" -> {
                val quality = getNetworkQualityScoreUseCase?.invoke()?.getOrNull() 
                    ?: "Network Quality Score: Not Available"
                Pair(quality, getMainMenuKeyboard())
            }
            "/connect" -> {
                // Request confirmation before connecting
                val message = """
                    |⚠️ <b>VPN Connection Request</b>
                    |
                    |You are about to connect to VPN.
                    |
                    |Please confirm by clicking the button below.
                """.trimMargin()
                val keyboard = InlineKeyboardMarkup(
                    inlineKeyboard = listOf(
                        listOf(
                            InlineKeyboardButton("✅ Confirm Connect", "vpn_connect_confirm"),
                            InlineKeyboardButton("❌ Cancel", "vpn_connect_cancel")
                        ),
                        listOf(InlineKeyboardButton("🏠 Main Menu", "menu_main"))
                    )
                )
                Pair(message, keyboard)
            }
            "/disconnect" -> {
                // Request confirmation before disconnecting
                val message = """
                    |⚠️ <b>VPN Disconnection Request</b>
                    |
                    |You are about to disconnect from VPN.
                    |
                    |Please confirm by clicking the button below.
                """.trimMargin()
                val keyboard = InlineKeyboardMarkup(
                    inlineKeyboard = listOf(
                        listOf(
                            InlineKeyboardButton("✅ Confirm Disconnect", "vpn_disconnect_confirm"),
                            InlineKeyboardButton("❌ Cancel", "vpn_disconnect_cancel")
                        ),
                        listOf(InlineKeyboardButton("🏠 Main Menu", "menu_main"))
                    )
                )
                Pair(message, keyboard)
            }
            "/restart" -> {
                // Request confirmation before restarting
                val message = """
                    |⚠️ <b>VPN Restart Request</b>
                    |
                    |You are about to restart VPN service.
                    |This will reload Xray-core configuration.
                    |
                    |Please confirm by clicking the button below.
                """.trimMargin()
                val keyboard = InlineKeyboardMarkup(
                    inlineKeyboard = listOf(
                        listOf(
                            InlineKeyboardButton("✅ Confirm Restart", "vpn_restart_confirm"),
                            InlineKeyboardButton("❌ Cancel", "vpn_restart_cancel")
                        ),
                        listOf(InlineKeyboardButton("🏠 Main Menu", "menu_main"))
                    )
                )
                Pair(message, keyboard)
            }
            "/monitor" -> {
                // Show monitoring status
                val status = monitoringControlUseCase?.getMonitoringStatus()?.getOrNull()
                    ?: "Monitoring Status: Not Available"
                Pair(status, getMainMenuKeyboard())
            }
            "/traffic" -> {
                // Default to current traffic (can be extended with period parameter)
                val traffic = getTrafficAnalyticsUseCase?.invoke("current")?.getOrNull()
                    ?: "Traffic Analytics: Not Available"
                Pair(traffic, getMainMenuKeyboard())
            }
            "/diagnose" -> {
                val diagnostic = getDiagnosticTestUseCase?.invoke()?.getOrNull()
                    ?: "Diagnostic Test: Not Available"
                Pair(diagnostic, getMainMenuKeyboard())
            }
            "/schedule" -> {
                val status = scheduleControlUseCase?.getScheduleStatus()?.getOrNull()
                    ?: "Schedule Status: Not Available"
                Pair(status, getMainMenuKeyboard())
            }
            "/menu" -> getMainMenuMessage()
            else -> {
                // Handle commands with parameters
                val parts = command.trim().split(" ", limit = 2)
                when (parts[0].lowercase()) {
                    "/monitor" -> {
                        if (parts.size > 1) {
                            val args = parts[1].lowercase()
                            when {
                                args == "stop" -> {
                                    val result = monitoringControlUseCase?.stopMonitoring()?.getOrNull()
                                        ?: "Failed to stop monitoring"
                                    Pair(result, getMainMenuKeyboard())
                                }
                                args.startsWith("start") -> {
                                    val intervalStr = args.removePrefix("start").trim().toIntOrNull() ?: 15
                                    val result = monitoringControlUseCase?.startMonitoring(intervalStr)?.getOrNull()
                                        ?: "Failed to start monitoring"
                                    Pair(result, getMainMenuKeyboard())
                                }
                                else -> Pair(
                                    "Usage: /monitor start [interval] or /monitor stop\nExample: /monitor start 15",
                                    getMainMenuKeyboard()
                                )
                            }
                        } else {
                            val status = monitoringControlUseCase?.getMonitoringStatus()?.getOrNull()
                                ?: "Monitoring Status: Not Available"
                            Pair(status, getMainMenuKeyboard())
                        }
                    }
                    "/traffic" -> {
                        val period = if (parts.size > 1) parts[1].lowercase() else "current"
                        val traffic = getTrafficAnalyticsUseCase?.invoke(period)?.getOrNull()
                            ?: "Traffic Analytics: Not Available"
                        Pair(traffic, getMainMenuKeyboard())
                    }
                    "/graph" -> {
                        val metric = if (parts.size > 1) parts[1].lowercase() else "throughput"
                        val graph = getPerformanceGraphUseCase?.invoke(metric, 24)?.getOrNull()
                            ?: "Performance Graph: Not Available"
                        Pair(graph, getMainMenuKeyboard())
                    }
                    "/schedule" -> {
                        if (parts.size > 1) {
                            val args = parts[1].split(" ")
                            if (args.size >= 2) {
                                val scheduleType = args[0].lowercase()
                                val action = args[1].lowercase()
                                
                                when (action) {
                                    "enable" -> {
                                        val time = if (args.size > 2) args[2] else "09:00"
                                        val result = scheduleControlUseCase?.setSchedule(scheduleType, true, time)?.getOrNull()
                                            ?: "Failed to set schedule"
                                        Pair(result, getMainMenuKeyboard())
                                    }
                                    "disable" -> {
                                        val result = scheduleControlUseCase?.setSchedule(scheduleType, false, null)?.getOrNull()
                                            ?: "Failed to disable schedule"
                                        Pair(result, getMainMenuKeyboard())
                                    }
                                    else -> Pair(
                                        "Usage: /schedule [daily|weekly] [enable|disable] [time]\nExample: /schedule daily enable 09:00",
                                        getMainMenuKeyboard()
                                    )
                                }
                            } else {
                                val status = scheduleControlUseCase?.getScheduleStatus()?.getOrNull()
                                    ?: "Schedule Status: Not Available"
                                Pair(status, getMainMenuKeyboard())
                            }
                        } else {
                            val status = scheduleControlUseCase?.getScheduleStatus()?.getOrNull()
                                ?: "Schedule Status: Not Available"
                            Pair(status, getMainMenuKeyboard())
                        }
                    }
                    else -> Pair("Unknown command. Use /help to see available commands.", getMainMenuKeyboard())
                }
            }
        }

        repository.sendMessage(config, response, "HTML", keyboard).map { response }
    }

    /**
     * Process callback query (button click)
     */
    suspend fun processCallbackQuery(
        callbackData: String,
        config: TelegramConfig,
        chatId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!config.isValid || !config.enabled) {
            return@withContext Result.failure(IllegalStateException("Telegram notifications are disabled"))
        }

        val (response, keyboard) = when (callbackData) {
            "menu_status" -> {
                val status = getVpnStatusUseCase().getOrNull() ?: "VPN Status: Unknown"
                Pair(status, getMainMenuKeyboard())
            }
            "menu_stats" -> {
                val stats = getPerformanceStatsUseCase().getOrNull() ?: "Performance Stats: Unknown"
                Pair(stats, getMainMenuKeyboard())
            }
            "menu_dns" -> {
                val dns = getDnsCacheStatsUseCase().getOrNull() ?: "DNS Cache Stats: Unknown"
                Pair(dns, getMainMenuKeyboard())
            }
            "menu_info" -> {
                val info = getInfoMessage(
                    vpnStatus = getVpnStatusUseCase().getOrNull() ?: "Unknown",
                    stats = getPerformanceStatsUseCase().getOrNull() ?: "Unknown",
                    dns = getDnsCacheStatsUseCase().getOrNull() ?: "Unknown"
                )
                Pair(info, getMainMenuKeyboard())
            }
            "menu_dashboard" -> {
                val dashboard = getDashboardUseCase?.invoke()?.getOrNull() 
                    ?: "Dashboard: Not Available"
                Pair(dashboard, getMainMenuKeyboard())
            }
            "menu_ai_status" -> {
                val aiStatus = getAiOptimizerStatusUseCase?.invoke()?.getOrNull() 
                    ?: "AI Optimizer Status: Not Available"
                Pair(aiStatus, getMainMenuKeyboard())
            }
            "menu_quality" -> {
                val quality = getNetworkQualityScoreUseCase?.invoke()?.getOrNull() 
                    ?: "Network Quality Score: Not Available"
                Pair(quality, getMainMenuKeyboard())
            }
            "menu_graph" -> {
                val graph = getPerformanceGraphUseCase?.invoke("throughput", 24)?.getOrNull()
                    ?: "Performance Graph: Not Available"
                Pair(graph, getMainMenuKeyboard())
            }
            "menu_traffic" -> {
                val traffic = getTrafficAnalyticsUseCase?.invoke("current")?.getOrNull()
                    ?: "Traffic Analytics: Not Available"
                Pair(traffic, getMainMenuKeyboard())
            }
            "menu_diagnose" -> {
                val diagnostic = getDiagnosticTestUseCase?.invoke()?.getOrNull()
                    ?: "Diagnostic Test: Not Available"
                Pair(diagnostic, getMainMenuKeyboard())
            }
            "menu_monitor" -> {
                val status = monitoringControlUseCase?.getMonitoringStatus()?.getOrNull()
                    ?: "Monitoring Status: Not Available"
                Pair(status, getMainMenuKeyboard())
            }
            "menu_schedule" -> {
                val status = scheduleControlUseCase?.getScheduleStatus()?.getOrNull()
                    ?: "Schedule Status: Not Available"
                Pair(status, getMainMenuKeyboard())
            }
            "menu_connect_request" -> {
                val message = """
                    |⚠️ <b>VPN Connection Request</b>
                    |
                    |You are about to connect to VPN.
                    |
                    |Please confirm by clicking the button below.
                """.trimMargin()
                val keyboard = InlineKeyboardMarkup(
                    inlineKeyboard = listOf(
                        listOf(
                            InlineKeyboardButton("✅ Confirm Connect", "vpn_connect_confirm"),
                            InlineKeyboardButton("❌ Cancel", "vpn_connect_cancel")
                        ),
                        listOf(InlineKeyboardButton("🏠 Main Menu", "menu_main"))
                    )
                )
                Pair(message, keyboard)
            }
            "menu_disconnect_request" -> {
                val message = """
                    |⚠️ <b>VPN Disconnection Request</b>
                    |
                    |You are about to disconnect from VPN.
                    |
                    |Please confirm by clicking the button below.
                """.trimMargin()
                val keyboard = InlineKeyboardMarkup(
                    inlineKeyboard = listOf(
                        listOf(
                            InlineKeyboardButton("✅ Confirm Disconnect", "vpn_disconnect_confirm"),
                            InlineKeyboardButton("❌ Cancel", "vpn_disconnect_cancel")
                        ),
                        listOf(InlineKeyboardButton("🏠 Main Menu", "menu_main"))
                    )
                )
                Pair(message, keyboard)
            }
            "menu_restart_request" -> {
                val message = """
                    |⚠️ <b>VPN Restart Request</b>
                    |
                    |You are about to restart VPN service.
                    |This will reload Xray-core configuration.
                    |
                    |Please confirm by clicking the button below.
                """.trimMargin()
                val keyboard = InlineKeyboardMarkup(
                    inlineKeyboard = listOf(
                        listOf(
                            InlineKeyboardButton("✅ Confirm Restart", "vpn_restart_confirm"),
                            InlineKeyboardButton("❌ Cancel", "vpn_restart_cancel")
                        ),
                        listOf(InlineKeyboardButton("🏠 Main Menu", "menu_main"))
                    )
                )
                Pair(message, keyboard)
            }
            "vpn_connect_confirm" -> {
                val result = vpnControlUseCase?.connect()
                val message = result?.getOrNull() ?: "❌ Failed to connect VPN"
                Pair(message, getMainMenuKeyboard())
            }
            "vpn_connect_cancel" -> {
                Pair("❌ VPN connection cancelled.", getMainMenuKeyboard())
            }
            "vpn_disconnect_confirm" -> {
                val result = vpnControlUseCase?.disconnect()
                val message = result?.getOrNull() ?: "❌ Failed to disconnect VPN"
                Pair(message, getMainMenuKeyboard())
            }
            "vpn_disconnect_cancel" -> {
                Pair("❌ VPN disconnection cancelled.", getMainMenuKeyboard())
            }
            "vpn_restart_confirm" -> {
                val result = vpnControlUseCase?.restart()
                val message = result?.getOrNull() ?: "❌ Failed to restart VPN"
                Pair(message, getMainMenuKeyboard())
            }
            "vpn_restart_cancel" -> {
                Pair("❌ VPN restart cancelled.", getMainMenuKeyboard())
            }
            "menu_main" -> getMainMenuMessage()
            else -> Pair("Unknown action.", getMainMenuKeyboard())
        }

        repository.sendMessage(config, response, "HTML", keyboard).map { response }
    }
    
    private fun getStartMessageWithMenu(): Pair<String, InlineKeyboardMarkup> {
        val message = """
            |*Welcome to HyperXray VPN Bot* 🤖
            |
            |This bot provides notifications and status updates for your VPN connection.
            |
            |Use the buttons below to interact with the bot.
        """.trimMargin()
        return Pair(message, getMainMenuKeyboard())
    }

    private fun getHelpMessageWithMenu(): Pair<String, InlineKeyboardMarkup> {
        val message = """
            |*HyperXray VPN Bot - Commands*
            |
            |*Available Commands:*
            |
            |/start - Start the bot and show welcome message
            |/help - Show this help message
            |/menu - Show main menu with buttons
            |/status - Get current VPN connection status
            |/stats - Get performance metrics (speed, ping, traffic)
            |/dns - Get DNS cache statistics (hits, misses, hit rate)
            |/info - Get comprehensive information (status + stats + DNS)
            |/ai_status - Get AI Optimizer status (bandit, deep model, orchestrator)
            |/dashboard - Get comprehensive dashboard (all metrics in one view)
            |/quality - Get network quality score (0-100) with detailed breakdown
            |/connect - Connect to VPN (requires confirmation)
            |/disconnect - Disconnect from VPN (requires confirmation)
            |/restart - Restart VPN service (reloads Xray-core config, requires confirmation)
            |/monitor - Start/stop automatic monitoring (usage: /monitor start [interval] or /monitor stop)
            |/traffic - Get traffic analytics (usage: /traffic [daily|weekly|monthly])
            |/diagnose - Run comprehensive system diagnostic tests
            |/graph - Get performance graph (usage: /graph [throughput|rtt])
            |/schedule - Manage scheduled reports (usage: /schedule [daily|weekly] enable [time] or disable)
            |
            |*Notification Types:*
            |You can enable/disable different notification types in the app settings.
        """.trimMargin()
        return Pair(message, getMainMenuKeyboard())
    }
    
    private fun getMainMenuMessage(): Pair<String, InlineKeyboardMarkup> {
        val message = """
            |*HyperXray VPN Bot - Main Menu* 📱
            |
            |Select an option from the menu below:
        """.trimMargin()
        return Pair(message, getMainMenuKeyboard())
    }
    
    private fun getMainMenuKeyboard(): InlineKeyboardMarkup {
        return InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                // Status & Stats Section
                listOf(
                    InlineKeyboardButton("📊 VPN Status", "menu_status"),
                    InlineKeyboardButton("⚡ Performance", "menu_stats")
                ),
                listOf(
                    InlineKeyboardButton("🌐 DNS Cache", "menu_dns"),
                    InlineKeyboardButton("📱 Dashboard", "menu_dashboard")
                ),
                listOf(
                    InlineKeyboardButton("🌐 Quality", "menu_quality"),
                    InlineKeyboardButton("ℹ️ All Info", "menu_info")
                ),
                // AI & Analytics Section
                listOf(
                    InlineKeyboardButton("🤖 AI Status", "menu_ai_status"),
                    InlineKeyboardButton("📈 Graph", "menu_graph")
                ),
                listOf(
                    InlineKeyboardButton("📊 Traffic", "menu_traffic"),
                    InlineKeyboardButton("🔍 Diagnose", "menu_diagnose")
                ),
                // Control Section
                listOf(
                    InlineKeyboardButton("▶️ Connect", "menu_connect_request"),
                    InlineKeyboardButton("⏹️ Disconnect", "menu_disconnect_request")
                ),
                listOf(
                    InlineKeyboardButton("🔄 Restart", "menu_restart_request"),
                    InlineKeyboardButton("📊 Monitor", "menu_monitor")
                ),
                // Settings Section
                listOf(
                    InlineKeyboardButton("📅 Schedule", "menu_schedule"),
                    InlineKeyboardButton("🏠 Main Menu", "menu_main")
                )
            )
        )
    }

    private fun getInfoMessage(vpnStatus: String, stats: String, dns: String): String {
        return buildString {
            appendLine("<b>📋 COMPLETE INFORMATION</b>")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine(vpnStatus)
            appendLine()
            appendLine(stats)
            appendLine()
            appendLine(dns)
        }
    }
}

