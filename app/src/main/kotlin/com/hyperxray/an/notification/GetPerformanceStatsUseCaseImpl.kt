package com.hyperxray.an.notification

import android.content.Context
import com.hyperxray.an.feature.telegram.domain.usecase.GetPerformanceStatsUseCase
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.viewmodel.CoreStatsState
import com.hyperxray.an.xray.runtime.stats.CoreStatsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of GetPerformanceStatsUseCase
 */
class GetPerformanceStatsUseCaseImpl(
    private val context: Context
) : GetPerformanceStatsUseCase {
    override suspend fun invoke(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val stats = getCurrentStats()
            val message = buildString {
                appendLine("<b>⚡ PERFORMANCE STATISTICS</b>")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("<b>📤 Upload:</b> ${formatBytes(stats.uplink)}")
                appendLine("<b>📥 Download:</b> ${formatBytes(stats.downlink)}")
                appendLine("<b>📊 Total:</b> ${formatBytes(stats.uplink + stats.downlink)}")
                if (stats.uplinkThroughput > 0 || stats.downlinkThroughput > 0) {
                    appendLine("")
                    appendLine("<b>🚀 Upload Speed:</b> ${formatBytesPerSecond(stats.uplinkThroughput)}")
                    appendLine("<b>🚀 Download Speed:</b> ${formatBytesPerSecond(stats.downlinkThroughput)}")
                }
                if (stats.uptime > 0) {
                    appendLine("")
                    appendLine("<b>⏱️ Uptime:</b> ${formatUptime(stats.uptime)}")
                }
                if (stats.numGoroutine > 0) {
                    appendLine("")
                    appendLine("<b>🔧 Goroutines:</b> ${stats.numGoroutine}")
                }
            }
            Result.success(message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    

    private suspend fun getCurrentStats(): CoreStatsState {
        return try {
            val prefs = Preferences(context)
            val client = CoreStatsClient.create("127.0.0.1", prefs.apiPort)
            if (client != null) {
                try {
                    val traffic = client.getTraffic()
                    client.close()
                    
                    if (traffic != null) {
                        CoreStatsState(
                            uplink = traffic.uplink,
                            downlink = traffic.downlink,
                            uplinkThroughput = 0.0, // Throughput calculation requires time-based comparison
                            downlinkThroughput = 0.0,
                            uptime = 0, // Uptime not available in TrafficState
                            numGoroutine = 0
                        )
                    } else {
                        CoreStatsState() // Return empty stats if traffic is null
                    }
                } catch (e: Exception) {
                    client.close()
                    CoreStatsState() // Return empty stats on error
                }
            } else {
                CoreStatsState() // Return empty stats if client creation failed
            }
        } catch (e: Exception) {
            CoreStatsState() // Return empty stats on error
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

    private fun formatUptime(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> String.format("%dh %dm %ds", hours, minutes, secs)
            minutes > 0 -> String.format("%dm %ds", minutes, secs)
            else -> String.format("%ds", secs)
        }
    }
}

