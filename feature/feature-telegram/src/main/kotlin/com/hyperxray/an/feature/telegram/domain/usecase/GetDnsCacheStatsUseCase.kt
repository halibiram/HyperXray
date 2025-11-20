package com.hyperxray.an.feature.telegram.domain.usecase

import android.content.Context
import com.hyperxray.an.core.network.dns.DnsCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Get DNS cache statistics use case
 * Returns formatted DNS cache statistics string
 */
class GetDnsCacheStatsUseCase(
    private val context: Context
) {
    suspend operator fun invoke(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Ensure DnsCacheManager is initialized
            DnsCacheManager.initialize(context)
            val stats = DnsCacheManager.getStats()
            
            // Parse stats string: "DNS Cache: X entries, hits=Y, misses=Z, hitRate=W%"
            val entriesMatch = Regex("(\\d+) entries").find(stats)
            val hitsMatch = Regex("hits=(\\d+)").find(stats)
            val missesMatch = Regex("misses=(\\d+)").find(stats)
            val hitRateMatch = Regex("hitRate=(\\d+)%").find(stats)
            
            val entries = entriesMatch?.groupValues?.get(1) ?: "0"
            val hits = hitsMatch?.groupValues?.get(1) ?: "0"
            val misses = missesMatch?.groupValues?.get(1) ?: "0"
            val hitRate = hitRateMatch?.groupValues?.get(1) ?: "0"
            
            val message = buildString {
                appendLine("<b>🌐 DNS CACHE STATISTICS</b>")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("<b>📦 Entries:</b> $entries")
                appendLine("<b>✅ Hits:</b> $hits")
                appendLine("<b>❌ Misses:</b> $misses")
                appendLine("")
                appendLine("<b>📊 Hit Rate:</b> $hitRate%")
            }
            Result.success(message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
}

