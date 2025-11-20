package com.hyperxray.an.notification

import android.content.Context
import com.hyperxray.an.feature.telegram.domain.usecase.GetTrafficAnalyticsUseCase
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.xray.runtime.stats.CoreStatsClient
import com.hyperxray.an.xray.runtime.stats.model.TrafficState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Implementation of GetTrafficAnalyticsUseCase
 */
class GetTrafficAnalyticsUseCaseImpl(
    private val context: Context
) : GetTrafficAnalyticsUseCase {
    override suspend fun invoke(period: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val prefs = Preferences(context)
            val client = CoreStatsClient.create("127.0.0.1", prefs.apiPort)
            
            if (client == null) {
                return@withContext Result.success(buildAnalyticsMessage(period, null, null))
            }
            
            try {
                val traffic = client.getTraffic()
                val systemStats = client.getSystemStats()
                client.close()
                
                val message = buildAnalyticsMessage(period, traffic, systemStats)
                Result.success(message)
            } catch (e: Exception) {
                client.close()
                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun buildAnalyticsMessage(
        period: String,
        traffic: TrafficState?,
        systemStats: com.xray.app.stats.command.SysStatsResponse?
    ): String {
        return buildString {
            val periodLabel = when (period.lowercase()) {
                "daily", "day", "d" -> "Daily"
                "weekly", "week", "w" -> "Weekly"
                "monthly", "month", "m" -> "Monthly"
                else -> "Current"
            }
            
            appendLine("<b>📊 TRAFFIC ANALYTICS - $periodLabel</b>")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            
            if (traffic == null) {
                appendLine("❌ Unable to fetch traffic data")
                appendLine()
                appendLine("Ensure VPN is connected and Xray-core is running.")
                return@buildString
            }
            
            val totalBytes = traffic.uplink + traffic.downlink
            val totalMB = totalBytes / (1024.0 * 1024.0)
            val totalGB = totalMB / 1024.0
            
            val uploadMB = traffic.uplink / (1024.0 * 1024.0)
            val downloadMB = traffic.downlink / (1024.0 * 1024.0)
            
            appendLine("<b>📤 Upload:</b> ${formatBytes(traffic.uplink)}")
            appendLine("<b>📥 Download:</b> ${formatBytes(traffic.downlink)}")
            appendLine("<b>📊 Total:</b> ${formatBytes(totalBytes)}")
            appendLine()
            
            // Usage breakdown
            val uploadPercent = if (totalBytes > 0) {
                (traffic.uplink * 100.0 / totalBytes)
            } else {
                0.0
            }
            val downloadPercent = if (totalBytes > 0) {
                (traffic.downlink * 100.0 / totalBytes)
            } else {
                0.0
            }
            
            appendLine("<b>📈 Usage Breakdown:</b>")
            appendLine("Upload: <b>${String.format("%.1f", uploadPercent)}%</b>")
            appendLine("Download: <b>${String.format("%.1f", downloadPercent)}%</b>")
            appendLine()
            
            // System stats if available
            if (systemStats != null) {
                val uptimeHours = systemStats.uptime / 3600
                val uptimeMinutes = (systemStats.uptime % 3600) / 60
                
                appendLine("<b>⏱️ Uptime:</b> ${uptimeHours}h ${uptimeMinutes}m")
                
                if (uptimeHours > 0) {
                    val avgMBPerHour = totalMB / uptimeHours.coerceAtLeast(1)
                    appendLine("<b>📊 Avg/Hour:</b> ${String.format("%.2f", avgMBPerHour)} MB")
                }
                
                appendLine("<b>🔧 Goroutines:</b> ${systemStats.numGoroutine}")
                appendLine("<b>💾 Memory:</b> ${formatBytes(systemStats.alloc)}")
            }
            
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("💡 Use /traffic daily for daily stats")
            appendLine("💡 Use /traffic weekly for weekly stats")
            appendLine("💡 Use /traffic monthly for monthly stats")
        }
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
}

