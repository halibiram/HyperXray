package com.hyperxray.an.feature.dns

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class DnsRecord(
    val id: String,
    val domain: String,
    val ip: String,
    val ttl: Int,
    val type: String,
    val timestamp: Long
)

/**
 * DnsManager - DNS cache removed, using Google DNS (8.8.8.8) directly
 */
object DnsManager {
    
    fun getDnsRecords(): Flow<List<DnsRecord>> {
        // DNS cache removed - return empty list
        return flowOf(emptyList())
    }

    fun clearCache() {
        // No-op: DNS cache removed
    }

    fun getCacheStats(): Flow<DnsCacheStats> {
        // DNS cache removed - return empty stats
        return flowOf(DnsCacheStats(
            totalRecords = 0,
            hitRate = 0.0,
            memoryUsage = 0L,
            avgTtl = 0
        ))
    }
}

data class DnsCacheStats(
    val totalRecords: Int,
    val hitRate: Double,
    val memoryUsage: Long,
    val avgTtl: Int
)
