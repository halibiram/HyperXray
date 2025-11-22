package com.hyperxray.an.notification

import android.content.Context
import com.hyperxray.an.feature.telegram.domain.usecase.GetNetworkQualityScoreUseCase
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.viewmodel.CoreStatsState
import com.hyperxray.an.xray.runtime.stats.CoreStatsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of GetNetworkQualityScoreUseCase
 */
class GetNetworkQualityScoreUseCaseImpl(
    private val context: Context
) : GetNetworkQualityScoreUseCase {
    override suspend fun invoke(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val prefs = Preferences(context)
            val client = CoreStatsClient.create("127.0.0.1", prefs.apiPort)
            
            if (client == null) {
                return@withContext Result.success(buildQualityMessage(
                    score = 0.0,
                    rtt = null,
                    throughput = null,
                    loss = null,
                    uptime = null,
                    error = "Cannot connect to Xray-core"
                ))
            }
            
            try {
                val traffic = client.getTraffic()
                val systemStats = client.getSystemStats()
                client.close()
                
                if (systemStats == null) {
                    return@withContext Result.success(buildQualityMessage(
                        score = 0.0,
                        rtt = null,
                        throughput = null,
                        loss = null,
                        uptime = null,
                        error = "Failed to get system stats"
                    ))
                }
                
                // Calculate metrics
                val throughput = if (traffic != null) {
                    (traffic.uplink + traffic.downlink).toDouble() / 60.0 // bytes per second estimate
                } else {
                    0.0
                }
                
                val rtt = estimateRtt(systemStats)
                val loss = estimateLoss(systemStats)
                val jitter = estimateJitter(rtt) // Simplified - would need history
                val uptime = systemStats.uptime
                
                // Calculate quality score (0-100)
                val score = calculateQualityScore(rtt, loss, jitter, throughput)
                
                val message = buildQualityMessage(
                    score = score,
                    rtt = rtt,
                    throughput = throughput,
                    loss = loss,
                    uptime = uptime,
                    error = null
                )
                
                Result.success(message)
            } catch (e: Exception) {
                client.close()
                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun estimateRtt(stats: com.xray.app.stats.command.SysStatsResponse): Double {
        val baseRtt = 50.0
        val goroutineFactor = stats.numGoroutine * 0.5
        val memoryFactor = (stats.alloc / (1024.0 * 1024.0 * 1024.0)) * 10.0
        return baseRtt + goroutineFactor + memoryFactor
    }
    
    private fun estimateLoss(stats: com.xray.app.stats.command.SysStatsResponse): Double {
        val memoryPressure = (stats.alloc / (1024.0 * 1024.0 * 1024.0)).coerceIn(0.0, 2.0)
        val goroutinePressure = (stats.numGoroutine / 1000.0).coerceIn(0.0, 1.0)
        return (memoryPressure * 0.02 + goroutinePressure * 0.01).coerceIn(0.0, 0.1)
    }
    
    private fun estimateJitter(rtt: Double): Double {
        // Simplified jitter estimation (would need RTT history in production)
        return rtt * 0.1 // Assume 10% of RTT as jitter
    }
    
    private fun calculateQualityScore(
        rtt: Double,
        loss: Double,
        jitter: Double,
        throughput: Double
    ): Double {
        // RTT score: lower is better (0-500ms range, 30% weight)
        val rttScore = (1.0 - (rtt / 500.0).coerceIn(0.0, 1.0)) * 0.3
        
        // Loss score: lower is better (0-10% range, 30% weight)
        val lossScore = (1.0 - (loss * 10.0).coerceIn(0.0, 1.0)) * 0.3
        
        // Jitter score: lower is better (0-100ms range, 20% weight)
        val jitterScore = (1.0 - (jitter / 100.0).coerceIn(0.0, 1.0)) * 0.2
        
        // Throughput score: higher is better (0-100 Mbps range, 20% weight)
        val throughputMbps = (throughput * 8.0) / (1024 * 1024)
        val throughputScore = (throughputMbps / 100.0).coerceIn(0.0, 1.0) * 0.2
        
        val totalScore = (rttScore + lossScore + jitterScore + throughputScore) * 100.0
        return totalScore.coerceIn(0.0, 100.0)
    }
    
    private fun buildQualityMessage(
        score: Double,
        rtt: Double?,
        throughput: Double?,
        loss: Double?,
        uptime: Int?,
        error: String?
    ): String {
        return buildString {
            appendLine("<b>🌐 NETWORK QUALITY SCORE</b>")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            
            if (error != null) {
                appendLine("❌ <b>Error:</b> $error")
                appendLine()
                appendLine("Quality Score: <b>N/A</b>")
                return@buildString
            }
            
            // Overall score with emoji indicator
            val scoreEmoji = when {
                score >= 80 -> "🟢"
                score >= 60 -> "🟡"
                score >= 40 -> "🟠"
                else -> "🔴"
            }
            
            appendLine("$scoreEmoji <b>Quality Score: ${String.format("%.1f", score)}/100</b>")
            
            val qualityLevel = when {
                score >= 80 -> "EXCELLENT"
                score >= 60 -> "GOOD"
                score >= 40 -> "FAIR"
                else -> "POOR"
            }
            appendLine("Status: <b>$qualityLevel</b>")
            appendLine()
            
            // Detailed breakdown
            appendLine("<b>📊 Detailed Breakdown:</b>")
            appendLine()
            
            // RTT
            if (rtt != null) {
                val rttStatus = when {
                    rtt < 50 -> "🟢 Excellent"
                    rtt < 100 -> "🟡 Good"
                    rtt < 200 -> "🟠 Fair"
                    else -> "🔴 Poor"
                }
                appendLine("<b>⏱️ Latency (RTT):</b> ${String.format("%.1f", rtt)}ms $rttStatus")
            }
            
            // Packet Loss
            if (loss != null) {
                val lossPercent = loss * 100.0
                val lossStatus = when {
                    lossPercent < 0.1 -> "🟢 Excellent"
                    lossPercent < 1.0 -> "🟡 Good"
                    lossPercent < 5.0 -> "🟠 Fair"
                    else -> "🔴 Poor"
                }
                appendLine("<b>📉 Packet Loss:</b> ${String.format("%.2f", lossPercent)}% $lossStatus")
            }
            
            // Throughput
            if (throughput != null) {
                val throughputMbps = (throughput * 8.0) / (1024 * 1024)
                val throughputStatus = when {
                    throughputMbps > 50 -> "🟢 Excellent"
                    throughputMbps > 10 -> "🟡 Good"
                    throughputMbps > 1 -> "🟠 Fair"
                    else -> "🔴 Poor"
                }
                appendLine("<b>🚀 Throughput:</b> ${String.format("%.2f", throughputMbps)} Mbps $throughputStatus")
            }
            
            // Uptime
            if (uptime != null && uptime > 0) {
                val hours = uptime / 3600
                val minutes = (uptime % 3600) / 60
                val uptimeStatus = when {
                    uptime > 86400 -> "🟢 Excellent (>24h)"
                    uptime > 3600 -> "🟡 Good (>1h)"
                    else -> "🟠 Fair"
                }
                appendLine("<b>⏳ Uptime:</b> ${hours}h ${minutes}m $uptimeStatus")
            }
            
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("💡 Use /stats for detailed metrics")
        }
    }
}






