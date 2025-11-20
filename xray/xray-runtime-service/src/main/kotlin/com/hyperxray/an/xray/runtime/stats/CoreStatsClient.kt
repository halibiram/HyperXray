package com.hyperxray.an.xray.runtime.stats

import android.util.Log
import com.hyperxray.an.xray.runtime.stats.model.TrafficState
import com.xray.app.stats.command.QueryStatsRequest
import com.xray.app.stats.command.StatsServiceGrpc
import com.xray.app.stats.command.SysStatsRequest
import com.xray.app.stats.command.SysStatsResponse
import io.grpc.ConnectivityState
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * gRPC client for querying Xray-core statistics API.
 * Provides system stats and traffic metrics via StatsService.
 * 
 * Moved from app module to xray-runtime-service to separate protocol/xray calls.
 */
class CoreStatsClient(private val channel: ManagedChannel) : Closeable {
    private val baseAsyncStub: StatsServiceGrpc.StatsServiceStub =
        StatsServiceGrpc.newStub(channel)
    
    // Cached channel state to avoid repeated polling (200ms overhead per call)
    private data class CachedState(
        val state: ConnectivityState,
        val isUsable: Boolean,
        val timestamp: Long
    )
    
    private val cachedState = AtomicReference<CachedState?>(null)
    private val cacheTtlMs = 500L // Cache validity: 500ms
    
    // Adaptive deadline: longer for first call, shorter for subsequent calls
    private val callCount = AtomicInteger(0)
    private val firstCallDeadlineSeconds = 5L // Longer deadline for first call
    private val subsequentCallDeadlineSeconds = 2L // Shorter deadline for subsequent calls
    
    /**
     * Creates a stub with an adaptive deadline for each call.
     * First call uses longer deadline (5s) to allow connection establishment.
     * Subsequent calls use shorter deadline (2s) for faster timeout.
     * This prevents negative deadlines when the stub is reused after delays.
     */
    private fun getStubWithDeadline(): StatsServiceGrpc.StatsServiceStub {
        val currentCallCount = callCount.getAndIncrement()
        val deadlineSeconds = if (currentCallCount == 0) {
            firstCallDeadlineSeconds
        } else {
            subsequentCallDeadlineSeconds
        }
        return baseAsyncStub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
    }

    /**
     * Checks if the channel is in a usable state for RPC calls.
     * Returns true if channel is READY or IDLE (can transition to READY).
     * Returns false if channel is SHUTDOWN, TRANSIENT_FAILURE, or CONNECTING.
     * Uses cached state to avoid repeated polling (optimization).
     */
    private fun isChannelUsable(): Boolean {
        val cached = cachedState.get()
        val now = System.currentTimeMillis()
        
        // Use cached state if still valid
        if (cached != null && (now - cached.timestamp) < cacheTtlMs) {
            return cached.isUsable
        }
        
        // Cache expired or not available, check actual state
        val state = channel.getState(false) // false = don't try to connect
        val isUsable = when (state) {
            ConnectivityState.READY -> true
            ConnectivityState.IDLE -> {
                // IDLE can transition to READY, so it's potentially usable
                // Try to trigger connection by checking state with connection attempt
                channel.getState(true) == ConnectivityState.READY || 
                channel.getState(true) == ConnectivityState.CONNECTING
            }
            ConnectivityState.CONNECTING -> true // Connection in progress, might succeed
            ConnectivityState.TRANSIENT_FAILURE -> false
            ConnectivityState.SHUTDOWN -> false
            else -> false
        }
        
        // Update cache
        cachedState.set(CachedState(state, isUsable, now))
        return isUsable
    }
    
    /**
     * Invalidates cached channel state (call when channel state might have changed).
     */
    private fun invalidateCache() {
        cachedState.set(null)
    }

    /**
     * Ensures channel is ready before making RPC call.
     * Returns true if channel is usable, false otherwise.
     * Optimized with cached state to avoid 200ms polling overhead.
     */
    private suspend fun ensureChannelReady(): Boolean = withContext(Dispatchers.IO) {
        // Fast path: check cached state first
        if (isChannelUsable()) {
            return@withContext true
        }
        
        // Channel is not usable, try to wait for it to become ready
        val state = channel.getState(true) // true = try to connect
        if (state == ConnectivityState.READY || state == ConnectivityState.CONNECTING) {
            // Wait up to 200ms for connection (optimized from 1s for faster response)
            var waited = 0L
            val maxWait = 200L
            val checkInterval = 50L
            
            while (waited < maxWait) {
                delay(checkInterval)
                waited += checkInterval
                val currentState = channel.getState(false)
                if (currentState == ConnectivityState.READY) {
                    // Update cache with new state
                    invalidateCache()
                    cachedState.set(CachedState(currentState, true, System.currentTimeMillis()))
                    return@withContext true
                }
                if (currentState == ConnectivityState.SHUTDOWN || 
                    currentState == ConnectivityState.TRANSIENT_FAILURE) {
                    // Update cache with failure state
                    invalidateCache()
                    cachedState.set(CachedState(currentState, false, System.currentTimeMillis()))
                    return@withContext false
                }
            }
        }
        
        // Final check and update cache
        val finalUsable = isChannelUsable()
        return@withContext finalUsable
    }

    suspend fun getSystemStats(): SysStatsResponse? = withContext(Dispatchers.IO) {
        if (!ensureChannelReady()) {
            Log.w("CoreStatsClient", "Channel not ready for getSystemStats, returning null")
            return@withContext null
        }
        
        runCatching {
            val request = SysStatsRequest.newBuilder().build()
            suspendCancellableCoroutine<SysStatsResponse?> { continuation ->
                getStubWithDeadline().getSysStats(request, object : io.grpc.stub.StreamObserver<SysStatsResponse> {
                    override fun onNext(value: SysStatsResponse) {
                        continuation.resume(value)
                    }
                    
                    override fun onError(t: Throwable) {
                        val status = when (t) {
                            is StatusException -> t.status
                            else -> Status.fromThrowable(t)
                        }
                        Log.e("CoreStatsClient", "getSystemStats failed: ${status.code} - ${status.description}", t)
                        
                        // If channel is in bad state, mark it for recreation and invalidate cache
                        if (status.code == Status.Code.UNAVAILABLE || 
                            status.code == Status.Code.DEADLINE_EXCEEDED ||
                            status.code == Status.Code.CANCELLED) {
                            val channelState = channel.getState(false)
                            if (channelState == ConnectivityState.SHUTDOWN || 
                                channelState == ConnectivityState.TRANSIENT_FAILURE) {
                                Log.w("CoreStatsClient", "Channel in bad state (${channelState}), will need recreation")
                                invalidateCache()
                            }
                        }
                        continuation.resume(null)
                    }
                    
                    override fun onCompleted() {
                        // Already handled in onNext or onError
                    }
                })
            }
        }.getOrNull()
    }

    suspend fun getTraffic(): TrafficState? = withContext(Dispatchers.IO) {
        if (!ensureChannelReady()) {
            Log.w("CoreStatsClient", "Channel not ready for getTraffic, returning null")
            return@withContext null
        }
        
        runCatching {
            // Use empty pattern to get all stats (most efficient, avoids duplicates)
            // If empty pattern doesn't work, try specific patterns but track seen stats
            val request = QueryStatsRequest.newBuilder()
                .setPattern("") // Empty pattern gets all stats
                .setReset(false)
                .build()
            
            // Async call using suspendCancellableCoroutine
            val response = suspendCancellableCoroutine<com.xray.app.stats.command.QueryStatsResponse?> { continuation ->
                getStubWithDeadline().queryStats(request, object : io.grpc.stub.StreamObserver<com.xray.app.stats.command.QueryStatsResponse> {
                    override fun onNext(value: com.xray.app.stats.command.QueryStatsResponse) {
                        continuation.resume(value)
                    }
                    
                    override fun onError(t: Throwable) {
                        val status = when (t) {
                            is StatusException -> t.status
                            else -> Status.fromThrowable(t)
                        }
                        Log.e("CoreStatsClient", "queryStats failed: ${status.code} - ${status.description}", t)
                        continuation.resume(null)
                    }
                    
                    override fun onCompleted() {
                        // Already handled in onNext or onError
                    }
                })
            }
            
            val statCount = response?.statList?.size ?: 0
            Log.d("CoreStatsClient", "Query stats returned $statCount stats")
            
            if (statCount == 0) {
                // Fallback: try specific patterns if empty pattern returns nothing
                val fallbackResult = trySpecificPatterns()
                return@runCatching fallbackResult
            }
            
            // Process all stats from empty pattern (no duplicates)
            var totalUplink = 0L
            var totalDownlink = 0L
            val seenStats = mutableSetOf<String>() // Track seen stat names to avoid duplicates
            
            response?.statList?.forEach { stat ->
                // Skip if we've already processed this stat
                if (seenStats.contains(stat.name)) {
                    Log.d("CoreStatsClient", "Skipping duplicate stat: ${stat.name}")
                    return@forEach
                }
                seenStats.add(stat.name)
                
                Log.d("CoreStatsClient", "Stat: ${stat.name} = ${stat.value}")
                
                // Check for uplink/downlink in stat name
                when {
                    stat.name.contains("uplink", ignoreCase = true) && 
                    stat.name.contains("traffic", ignoreCase = true) -> {
                        totalUplink += stat.value
                        Log.d("CoreStatsClient", "Found uplink stat: ${stat.name} = ${stat.value}, total now: $totalUplink")
                    }
                    stat.name.contains("downlink", ignoreCase = true) && 
                    stat.name.contains("traffic", ignoreCase = true) -> {
                        totalDownlink += stat.value
                        Log.d("CoreStatsClient", "Found downlink stat: ${stat.name} = ${stat.value}, total now: $totalDownlink")
                    }
                }
            }
            
            Log.d("CoreStatsClient", "Total Uplink: $totalUplink, Total Downlink: $totalDownlink")
            
            if (totalUplink > 0 || totalDownlink > 0) {
                TrafficState(totalUplink, totalDownlink)
            } else {
                null
            }
        }.onFailure { e ->
            val status = when (e) {
                is StatusException -> e.status
                else -> Status.fromThrowable(e)
            }
            Log.e("CoreStatsClient", "getTraffic failed: ${status.code} - ${status.description}", e)
            
            // If channel is in bad state, mark it for recreation
            if (status.code == Status.Code.UNAVAILABLE || 
                status.code == Status.Code.DEADLINE_EXCEEDED ||
                status.code == Status.Code.CANCELLED) {
                val channelState = channel.getState(false)
                if (channelState == ConnectivityState.SHUTDOWN || 
                    channelState == ConnectivityState.TRANSIENT_FAILURE) {
                    Log.w("CoreStatsClient", "Channel in bad state (${channelState}), will need recreation")
                }
            }
        }.getOrNull()
    }

    /**
     * Fallback method to try specific patterns if empty pattern returns no results.
     * Uses a Set to track seen stats and avoid duplicates.
     */
    private suspend fun trySpecificPatterns(): TrafficState? {
        val patterns = listOf("outbound", "inbound", "user", "traffic")
        var totalUplink = 0L
        var totalDownlink = 0L
        val seenStats = mutableSetOf<String>() // Track seen stat names to avoid duplicates
        
        for (pattern in patterns) {
            val request = QueryStatsRequest.newBuilder()
                .setPattern(pattern)
                .setReset(false)
                .build()
            
            val response = suspendCancellableCoroutine<com.xray.app.stats.command.QueryStatsResponse?> { continuation ->
                getStubWithDeadline().queryStats(request, object : io.grpc.stub.StreamObserver<com.xray.app.stats.command.QueryStatsResponse> {
                    override fun onNext(value: com.xray.app.stats.command.QueryStatsResponse) {
                        continuation.resume(value)
                    }
                    
                    override fun onError(t: Throwable) {
                        continuation.resume(null)
                    }
                    
                    override fun onCompleted() {
                        // Already handled in onNext or onError
                    }
                })
            }
            val statCount = response?.statList?.size ?: 0
            Log.d("CoreStatsClient", "Pattern '$pattern' returned $statCount stats")
            
            response?.statList?.forEach { stat ->
                // Skip if we've already processed this stat
                if (seenStats.contains(stat.name)) {
                    Log.d("CoreStatsClient", "Skipping duplicate stat: ${stat.name}")
                    return@forEach
                }
                seenStats.add(stat.name)
                
                Log.d("CoreStatsClient", "Stat: ${stat.name} = ${stat.value}")
                
                // Check for uplink/downlink in stat name
                when {
                    stat.name.contains("uplink", ignoreCase = true) && 
                    stat.name.contains("traffic", ignoreCase = true) -> {
                        totalUplink += stat.value
                        Log.d("CoreStatsClient", "Found uplink stat: ${stat.name} = ${stat.value}, total now: $totalUplink")
                    }
                    stat.name.contains("downlink", ignoreCase = true) && 
                    stat.name.contains("traffic", ignoreCase = true) -> {
                        totalDownlink += stat.value
                        Log.d("CoreStatsClient", "Found downlink stat: ${stat.name} = ${stat.value}, total now: $totalDownlink")
                    }
                }
            }
        }
        
        Log.d("CoreStatsClient", "Fallback Total Uplink: $totalUplink, Total Downlink: $totalDownlink")
        
        return if (totalUplink > 0 || totalDownlink > 0) {
            TrafficState(totalUplink, totalDownlink)
        } else {
            null
        }
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
        /**
         * Creates a new CoreStatsClient with connection verification and retry logic.
         * 
         * @param host The host address (typically "127.0.0.1")
         * @param port The port number for the stats API
         * @param maxRetries Maximum number of connection attempts (default: 3)
         * @param initialRetryDelayMs Initial delay between retries in milliseconds (default: 500)
         * @return CoreStatsClient instance, or null if all retries failed
         */
        fun create(
            host: String, 
            port: Int, 
            maxRetries: Int = 3,
            initialRetryDelayMs: Long = 500L
        ): CoreStatsClient? {
            // Validate parameters early and return null instead of throwing
            // This prevents crashes from require() exceptions
            if (port <= 0) {
                Log.e("CoreStatsClient", "Invalid port: $port (must be positive)")
                return null
            }
            if (maxRetries <= 0) {
                Log.e("CoreStatsClient", "Invalid maxRetries: $maxRetries (must be positive)")
                return null
            }
            if (host.isBlank()) {
                Log.e("CoreStatsClient", "Invalid host: '$host' (must not be blank)")
                return null
            }
            
            // Wrap entire creation logic in try-catch as safety net
            return try {
                var lastException: Exception? = null
                
                for (attempt in 0 until maxRetries) {
                    try {
                        val channel = ManagedChannelBuilder.forAddress(host, port)
                            .usePlaintext()
                            .enableRetry() // Enable automatic retry for transient failures
                            .maxRetryAttempts(2) // Allow 2 automatic retries per call
                            .build()
                        
                        // Verify channel can be created (doesn't mean it's connected yet)
                        val client = CoreStatsClient(channel)
                        
                        // Log successful creation
                        if (attempt > 0) {
                            Log.i("CoreStatsClient", "Successfully created client on attempt ${attempt + 1}")
                        } else {
                            Log.d("CoreStatsClient", "Created CoreStatsClient for $host:$port")
                        }
                        
                        return client
                    } catch (e: Exception) {
                        lastException = e
                        Log.w("CoreStatsClient", "Connection attempt ${attempt + 1}/$maxRetries failed: ${e.message}")
                        
                        // Exponential backoff: delay increases with each retry
                        if (attempt < maxRetries - 1) {
                            val delayMs = initialRetryDelayMs * (1L shl attempt) // 500ms, 1000ms, 2000ms, ...
                            try {
                                Thread.sleep(delayMs)
                            } catch (ie: InterruptedException) {
                                Thread.currentThread().interrupt()
                                Log.e("CoreStatsClient", "Interrupted during retry delay")
                                break
                            }
                        }
                    }
                }
                
                Log.e("CoreStatsClient", "Failed to create client after $maxRetries attempts", lastException)
                null
            } catch (e: Exception) {
                // Safety net: catch any unexpected exceptions (e.g., from require() if validation is bypassed)
                Log.e("CoreStatsClient", "Unexpected error during client creation: ${e.message}", e)
                null
            }
        }
    }
}

