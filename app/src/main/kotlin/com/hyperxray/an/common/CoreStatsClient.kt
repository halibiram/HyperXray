package com.hyperxray.an.common

import android.util.Log
import com.hyperxray.an.viewmodel.TrafficState
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
            val patterns = listOf("outbound", "inbound", "user")
            var totalUplink = 0L
            var totalDownlink = 0L
            
            for (pattern in patterns) {
                val request = QueryStatsRequest.newBuilder()
                    .setPattern(pattern)
                    .setReset(false)
                    .build()
                
                val response = blockingStub.queryStats(request)
                response?.statList?.forEach { stat ->
                    Log.d("CoreStatsClient", "Stat: ${stat.name} = ${stat.value}")
                    when {
                        stat.name.contains("uplink", ignoreCase = true) -> {
                            totalUplink += stat.value
                        }
                        stat.name.contains("downlink", ignoreCase = true) -> {
                            totalDownlink += stat.value
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
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
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