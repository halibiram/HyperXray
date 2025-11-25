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
                // Format latency with appropriate precision (same as Dashboard)
                val hitLatencyFormatted = when {
                    metrics.avgHitLatencyMs <= 0.0 || metrics.avgHitLatencyMs.isNaN() -> "N/A"
                    metrics.avgHitLatencyMs < 1.0 -> String.format("%.3f", metrics.avgHitLatencyMs) // Sub-millisecond precision
                    metrics.avgHitLatencyMs < 10.0 -> String.format("%.2f", metrics.avgHitLatencyMs) // 2 decimal places
                    else -> String.format("%.1f", metrics.avgHitLatencyMs) // 1 decimal place
                }
                val missLatencyFormatted = when {
                    metrics.avgMissLatencyMs <= 0.0 || metrics.avgMissLatencyMs.isNaN() -> "N/A"
                    metrics.avgMissLatencyMs < 1.0 -> String.format("%.3f", metrics.avgMissLatencyMs) // Sub-millisecond precision
                    metrics.avgMissLatencyMs < 10.0 -> String.format("%.2f", metrics.avgMissLatencyMs) // 2 decimal places
                    else -> String.format("%.1f", metrics.avgMissLatencyMs) // 1 decimal place
                }
                appendLine("<b>⚡ Avg Hit Latency:</b> $hitLatencyFormatted ms")
                appendLine("<b>🐌 Avg Miss Latency:</b> $missLatencyFormatted ms")
                
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

