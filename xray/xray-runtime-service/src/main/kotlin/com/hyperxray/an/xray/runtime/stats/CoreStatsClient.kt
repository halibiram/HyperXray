package com.hyperxray.an.xray.runtime.stats

import android.util.Log
import com.hyperxray.an.xray.runtime.stats.model.TrafficState
import com.xray.app.stats.command.QueryStatsRequest
import com.xray.app.stats.command.StatsServiceGrpc
import com.xray.app.stats.command.SysStatsRequest
import com.xray.app.stats.command.SysStatsResponse
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * gRPC client for querying Xray-core statistics API.
 * Provides system stats and traffic metrics via StatsService.
 * 
 * Moved from app module to xray-runtime-service to separate protocol/xray calls.
 */
class CoreStatsClient(private val channel: ManagedChannel) : Closeable {
    private val blockingStub: StatsServiceGrpc.StatsServiceBlockingStub =
        StatsServiceGrpc.newBlockingStub(channel)

    suspend fun getSystemStats(): SysStatsResponse? = withContext(Dispatchers.IO) {
        runCatching {
            val request = SysStatsRequest.newBuilder().build()
            blockingStub.getSysStats(request)
        }.getOrNull()
    }

    suspend fun getTraffic(): TrafficState? = withContext(Dispatchers.IO) {
        runCatching {
            // Try multiple patterns to get traffic stats
            // Empty pattern gets all stats
            val patterns = listOf("", "outbound", "inbound", "user", "traffic")
            var totalUplink = 0L
            var totalDownlink = 0L
            
            for (pattern in patterns) {
                val request = QueryStatsRequest.newBuilder()
                    .setPattern(pattern)
                    .setReset(false)
                    .build()
                
                val response = blockingStub.queryStats(request)
                val statCount = response?.statList?.size ?: 0
                Log.d("CoreStatsClient", "Pattern '$pattern' returned $statCount stats")
                
                response?.statList?.forEach { stat ->
                    Log.d("CoreStatsClient", "Stat: ${stat.name} = ${stat.value}")
                    when {
                        stat.name.contains("uplink", ignoreCase = true) -> {
                            totalUplink += stat.value
                            Log.d("CoreStatsClient", "Found uplink stat: ${stat.name} = ${stat.value}, total now: $totalUplink")
                        }
                        stat.name.contains("downlink", ignoreCase = true) -> {
                            totalDownlink += stat.value
                            Log.d("CoreStatsClient", "Found downlink stat: ${stat.name} = ${stat.value}, total now: $totalDownlink")
                        }
                        stat.name.contains("outbound", ignoreCase = true) && stat.name.contains("traffic", ignoreCase = true) -> {
                            // Try alternative pattern: outbound>>>traffic>>>uplink/downlink
                            if (stat.name.contains("uplink", ignoreCase = true)) {
                                totalUplink += stat.value
                                Log.d("CoreStatsClient", "Found uplink via outbound pattern: ${stat.name} = ${stat.value}")
                            } else if (stat.name.contains("downlink", ignoreCase = true)) {
                                totalDownlink += stat.value
                                Log.d("CoreStatsClient", "Found downlink via outbound pattern: ${stat.name} = ${stat.value}")
                            }
                        }
                        stat.name.contains("inbound", ignoreCase = true) && stat.name.contains("traffic", ignoreCase = true) -> {
                            // Try alternative pattern: inbound>>>traffic>>>uplink/downlink
                            if (stat.name.contains("uplink", ignoreCase = true)) {
                                totalUplink += stat.value
                                Log.d("CoreStatsClient", "Found uplink via inbound pattern: ${stat.name} = ${stat.value}")
                            } else if (stat.name.contains("downlink", ignoreCase = true)) {
                                totalDownlink += stat.value
                                Log.d("CoreStatsClient", "Found downlink via inbound pattern: ${stat.name} = ${stat.value}")
                            }
                        }
                    }
                }
            }
            
            Log.d("CoreStatsClient", "Total Uplink: $totalUplink, Total Downlink: $totalDownlink")
            
            if (totalUplink > 0 || totalDownlink > 0) {
                TrafficState(totalUplink, totalDownlink)
            } else {
                null
            }
        }.getOrNull()
    }

    override fun close() {
        try {
            // Initiate graceful shutdown
            channel.shutdown()
            
            // Wait for graceful shutdown with timeout (3 seconds)
            // If timeout occurs, force shutdown
            if (!channel.awaitTermination(3, TimeUnit.SECONDS)) {
                // Graceful shutdown timed out, force shutdown
                Log.w("CoreStatsClient", "Channel graceful shutdown timed out, forcing shutdown now.")
                channel.shutdownNow()
                
                // Wait for forced shutdown with timeout (2 seconds)
                // This is the final attempt - if it times out, we log and continue
                if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
                    Log.e("CoreStatsClient", "Channel force shutdown timed out. Channel may not be fully closed.")
                } else {
                    Log.d("CoreStatsClient", "Channel force shutdown completed successfully.")
                }
            } else {
                Log.d("CoreStatsClient", "Channel graceful shutdown completed successfully.")
            }
        } catch (e: InterruptedException) {
            // Thread was interrupted during shutdown
            Log.e("CoreStatsClient", "Channel shutdown interrupted, forcing shutdown now.", e)
            // Restore interrupt status
            Thread.currentThread().interrupt()
            // Force shutdown immediately
            try {
                channel.shutdownNow()
                // Try to wait for shutdown, but don't wait too long if interrupted
                channel.awaitTermination(2, TimeUnit.SECONDS)
            } catch (ex: Exception) {
                Log.e("CoreStatsClient", "Error during forced channel shutdown", ex)
            }
        } catch (e: Exception) {
            // Any other exception during shutdown
            Log.e("CoreStatsClient", "Error closing channel: ${e.message}", e)
            // Try to force shutdown as last resort
            try {
                channel.shutdownNow()
                // Final attempt to wait for shutdown (with timeout)
                channel.awaitTermination(2, TimeUnit.SECONDS)
            } catch (ex: Exception) {
                // Ignore exceptions during emergency shutdown
                // We've already logged the original error
                Log.w("CoreStatsClient", "Error during emergency channel shutdown: ${ex.message}")
            }
        }
    }

    companion object {
        fun create(host: String, port: Int): CoreStatsClient {
            val channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build()
            return CoreStatsClient(channel)
        }
    }
}

