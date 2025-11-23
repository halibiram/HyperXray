package com.hyperxray.an.feature.dns

import com.hyperxray.an.core.network.dns.DnsCacheManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.merge
import java.util.UUID

data class DnsRecord(
    val id: String,
    val domain: String,
    val ip: String,
    val ttl: Int,
    val type: String,
    val timestamp: Long
)

object DnsManager {
    
    fun getDnsRecords(): Flow<List<DnsRecord>> {
        // Commit 312450a: getCacheEntries() not available, so return empty list
        return flow {
            emit(emptyList<DnsRecord>())
        }
    }

    fun clearCache() {
        DnsCacheManager.clearCache()
    }

    fun getCacheStats(): Flow<DnsCacheStats> {
        // Commit 312450a: DnsCacheManager only has getStats() method, not getStatsData() or cacheUpdates
        val periodicUpdates = flow {
            while (true) {
                emit(Unit)
                delay(1000) // Update every 1 second
            }
        }
        
        return periodicUpdates.onStart { emit(Unit) }.map {
            val statsString = DnsCacheManager.getStats()
            // Parse stats string: "DNS Cache: X entries, hits=Y, misses=Z, hitRate=W%"
            val entriesMatch = Regex("(\\d+) entries").find(statsString)
            val hitsMatch = Regex("hits=(\\d+)").find(statsString)
            val missesMatch = Regex("misses=(\\d+)").find(statsString)
            val hitRateMatch = Regex("hitRate=(\\d+)%").find(statsString)
            
            val entryCount = entriesMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val hits = hitsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val misses = missesMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val hitRate = hitRateMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            DnsCacheStats(
                totalRecords = entryCount,
                hitRate = hitRate / 100.0, // Convert percentage (0-100) to decimal (0.0-1.0)
                memoryUsage = 0L, // Not available in commit 312450a
                avgTtl = 0 // Not available in commit 312450a
            )
        }
    }
}

data class DnsCacheStats(
    val totalRecords: Int,
    val hitRate: Double,
    val memoryUsage: Long,
    val avgTtl: Int
)
