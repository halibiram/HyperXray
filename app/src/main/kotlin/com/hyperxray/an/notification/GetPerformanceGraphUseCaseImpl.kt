package com.hyperxray.an.notification

import android.content.Context
import com.hyperxray.an.feature.telegram.domain.usecase.GetPerformanceGraphUseCase
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.viewmodel.CoreStatsState
import com.hyperxray.an.xray.runtime.stats.CoreStatsClient
import com.hyperxray.an.xray.runtime.stats.model.TrafficState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of GetPerformanceGraphUseCase
 * Generates simple ASCII graphs based on current stats
 */
class GetPerformanceGraphUseCaseImpl(
    private val context: Context
) : GetPerformanceGraphUseCase {
    override suspend fun invoke(metric: String, hours: Int): Result<String> = withContext(Dispatchers.IO) {
        try {
            val prefs = Preferences(context)
            val client = CoreStatsClient.create("127.0.0.1", prefs.apiPort)
            
            if (client == null) {
                return@withContext Result.success("❌ Cannot connect to Xray-core for graph data")
            }
            
            try {
                val traffic = client.getTraffic()
                val systemStats = client.getSystemStats()
                client.close()
                
                // Generate simple ASCII graph
                val graph = generateAsciiGraph(metric, traffic, systemStats, hours)
                Result.success(graph)
            } catch (e: Exception) {
                client.close()
                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun generateAsciiGraph(
        metric: String,
        traffic: TrafficState?,
        systemStats: com.xray.app.stats.command.SysStatsResponse?,
        hours: Int
    ): String {
        return buildString {
            val metricLabel = when (metric.lowercase()) {
                "throughput", "speed", "t" -> "Throughput"
                "rtt", "latency", "l" -> "RTT (Latency)"
                "loss", "packet_loss", "p" -> "Packet Loss"
                else -> "Performance"
            }
            
            appendLine("<b>📈 PERFORMANCE GRAPH - $metricLabel</b>")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            
            if (traffic == null) {
                appendLine("❌ No data available")
                appendLine()
                appendLine("Ensure VPN is connected and Xray-core is running.")
                return@buildString
            }
            
            // Generate simple trend indicator
            val totalBytes = traffic.uplink + traffic.downlink
            val throughputMbps = if (hours > 0) {
                (totalBytes * 8.0) / (1024.0 * 1024.0 * hours * 3600.0)
            } else {
                (totalBytes * 8.0) / (1024.0 * 1024.0 * 3600.0) // Default to 1 hour
            }
            
            // Create simple bar chart
            appendLine("<b>Current Performance:</b>")
            appendLine()
            
            when (metric.lowercase()) {
                "throughput", "speed", "t" -> {
                    appendLine("Throughput: <b>${String.format("%.2f", throughputMbps)} Mbps</b>")
                    appendLine()
                    // Simple bar representation
                    val barLength = (throughputMbps / 100.0 * 20.0).coerceIn(0.0, 20.0).toInt()
                    appendLine("[" + "█".repeat(barLength) + "░".repeat(20 - barLength) + "]")
                    appendLine("0${" ".repeat(8)}50${" ".repeat(8)}100 Mbps")
                }
                "rtt", "latency", "l" -> {
                    val rtt = estimateRtt(systemStats)
                    appendLine("RTT: <b>${String.format("%.1f", rtt)} ms</b>")
                    appendLine()
                    val barLength = ((500.0 - rtt) / 500.0 * 20.0).coerceIn(0.0, 20.0).toInt()
                    appendLine("[" + "█".repeat(barLength) + "░".repeat(20 - barLength) + "]")
                    appendLine("0${" ".repeat(7)}250${" ".repeat(7)}500 ms")
                    appendLine()
                    appendLine("Lower is better ↑")
                }
                else -> {
                    appendLine("Total Traffic: <b>${formatBytes(totalBytes)}</b>")
                    appendLine("Upload: <b>${formatBytes(traffic.uplink)}</b>")
                    appendLine("Download: <b>${formatBytes(traffic.downlink)}</b>")
                }
            }
            
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("💡 Use /graph throughput for throughput graph")
            appendLine("💡 Use /graph rtt for latency graph")
            appendLine("💡 Historical data requires monitoring to be enabled")
        }
    }
    
    private fun estimateRtt(stats: com.xray.app.stats.command.SysStatsResponse?): Double {
        if (stats == null) return 50.0
        val baseRtt = 50.0
        val goroutineFactor = stats.numGoroutine * 0.5
        val memoryFactor = (stats.alloc / (1024.0 * 1024.0 * 1024.0)) * 10.0
        return baseRtt + goroutineFactor + memoryFactor
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

