package com.hyperxray.an.feature.telegram.domain.usecase

import android.content.Context
import com.hyperxray.an.core.network.dns.DnsCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Get DNS cache statistics use case
 * Returns formatted DNS cache statistics string using structured metrics
 * Now uses DnsCacheMetrics for accurate, real-time data (consistent with Dashboard)
 */
class GetDnsCacheStatsUseCase(
    private val context: Context
) {
    suspend operator fun invoke(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Ensure DnsCacheManager is initialized
            DnsCacheManager.initialize(context)
            
            // Get structured metrics (same source as Dashboard)
            val metrics = DnsCacheManager.dashboardStats.value
            
            // Format message with comprehensive statistics
            val message = buildString {
                appendLine("<b>🌐 DNS CACHE STATISTICS</b>")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("<b>📦 Entries:</b> ${metrics.entryCount}")
                appendLine("<b>🔍 Total Lookups:</b> ${metrics.totalLookups}")
                appendLine("<b>✅ Hits:</b> ${metrics.hits}")
                appendLine("<b>❌ Misses:</b> ${metrics.misses}")
                appendLine("")
                appendLine("<b>📊 Hit Rate:</b> ${metrics.hitRate}%")
                appendLine("<b>📈 Avg Domain Hit Rate:</b> ${metrics.avgDomainHitRate}%")
                appendLine("")
                appendLine("<b>💾 Memory Usage:</b> ${metrics.memoryUsageBytes / (1024 * 1024)} MB / ${metrics.memoryLimitBytes / (1024 * 1024)} MB")
                appendLine("<b>📉 Memory Usage:</b> ${metrics.memoryUsagePercent}%")
                appendLine("")
                appendLine("<b>⚡ Avg Hit Latency:</b> ${String.format("%.2f", metrics.avgHitLatencyMs)} ms")
                appendLine("<b>🐌 Avg Miss Latency:</b> ${String.format("%.2f", metrics.avgMissLatencyMs)} ms")
                
                // Show top domains if available
                if (metrics.topDomains.isNotEmpty()) {
                    appendLine("")
                    appendLine("<b>🏆 Top Domains:</b>")
                    metrics.topDomains.take(5).forEachIndexed { index, domain ->
                        appendLine("  ${index + 1}. ${domain.domain}: ${domain.hitRate}% (${domain.hits} hits, ${domain.misses} misses)")
                    }
                }
            }
            Result.success(message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
}

