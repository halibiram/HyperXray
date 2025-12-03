package com.hyperxray.an.notification

import android.content.Context
import com.hyperxray.an.feature.telegram.domain.usecase.GetDashboardUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.GetVpnStatusUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.GetPerformanceStatsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of GetDashboardUseCase
 */
class GetDashboardUseCaseImpl(
    private val context: Context
) : GetDashboardUseCase {
    private val getVpnStatusUseCase: GetVpnStatusUseCase = GetVpnStatusUseCaseImpl(context)
    private val getPerformanceStatsUseCase: GetPerformanceStatsUseCase = GetPerformanceStatsUseCaseImpl(context)
    
    override suspend fun invoke(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Fetch all metrics in parallel for better performance
            val vpnStatus = getVpnStatusUseCase().getOrNull() ?: "VPN Status: Unknown"
            val performanceStats = getPerformanceStatsUseCase().getOrNull() ?: "Performance Stats: Unknown"
            
            // Extract key metrics for compact dashboard view
            val dashboard = buildDashboardMessage(vpnStatus, performanceStats)
            Result.success(dashboard)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun buildDashboardMessage(
        vpnStatus: String,
        performanceStats: String
    ): String {
        return buildString {
            appendLine("<b>📱 HYPERXRAY DASHBOARD</b>")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            
            // VPN Status (compact)
            appendLine("<b>📡 VPN Status:</b>")
            val vpnConnected = vpnStatus.contains("✅ CONNECTED", ignoreCase = true)
            appendLine(if (vpnConnected) "✅ <b>CONNECTED</b>" else "❌ <b>DISCONNECTED</b>")
            appendLine()
            
            // Performance Stats (key metrics only)
            appendLine("<b>⚡ Performance:</b>")
            // Extract key metrics from performance stats
            extractKeyMetrics(performanceStats) { metric, value ->
                appendLine("$metric $value")
            }
            
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("💡 Use /help for more commands")
            appendLine("📊 Use /stats for details")
        }
    }
    
    private fun extractKeyMetrics(stats: String, callback: (String, String) -> Unit) {
        // Extract upload/download from performance stats
        val uploadMatch = Regex("<b>📤 Upload:</b>\\s*([^<]+)").find(stats)
        uploadMatch?.let { callback("📤 Upload:", "<b>${it.groupValues[1].trim()}</b>") }
        
        val downloadMatch = Regex("<b>📥 Download:</b>\\s*([^<]+)").find(stats)
        downloadMatch?.let { callback("📥 Download:", "<b>${it.groupValues[1].trim()}</b>") }
        
        val uploadSpeedMatch = Regex("<b>🚀 Upload Speed:</b>\\s*([^<]+)").find(stats)
        uploadSpeedMatch?.let { callback("⬆️ Speed:", "<b>${it.groupValues[1].trim()}</b>") }
        
        val downloadSpeedMatch = Regex("<b>🚀 Download Speed:</b>\\s*([^<]+)").find(stats)
        downloadSpeedMatch?.let { callback("⬇️ Speed:", "<b>${it.groupValues[1].trim()}</b>") }
        
        // Extract DNS cache metrics
        val hitRateMatch = Regex("Hit Rate:\\s*([^<]+)").find(stats)
        hitRateMatch?.let { callback("🎯 Hit Rate:", "<b>${it.groupValues[1].trim()}</b>") }
        
        val hitsMatch = Regex("Hits:\\s*(\\d+)").find(stats)
        hitsMatch?.let { callback("✅ Hits:", "<b>${it.groupValues[1]}</b>") }
        
        val missesMatch = Regex("Misses:\\s*(\\d+)").find(stats)
        missesMatch?.let { callback("❌ Misses:", "<b>${it.groupValues[1]}</b>") }
    }
}






