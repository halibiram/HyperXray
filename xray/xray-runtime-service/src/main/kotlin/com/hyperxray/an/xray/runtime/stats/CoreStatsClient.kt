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
        if (state == ConnectivityState.READY || state == ConnectivityState.CONNECTING || state == ConnectivityState.IDLE) {
            // Wait up to 500ms for connection (increased from 200ms for better reliability)
            var waited = 0L
            val maxWait = 500L
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
        Log.d("CoreStatsClient", "getSystemStats() called")
        
        if (!ensureChannelReady()) {
            val channelState = channel.getState(false)
            Log.w("CoreStatsClient", "Channel not ready for getSystemStats (state: $channelState), returning null")
            return@withContext null
        }
        
        Log.d("CoreStatsClient", "Channel ready, making getSystemStats RPC call")
        
        runCatching {
            val request = SysStatsRequest.newBuilder().build()
            suspendCancellableCoroutine<SysStatsResponse?> { continuation ->
                Log.d("CoreStatsClient", "Starting getSystemStats RPC call with deadline")
                getStubWithDeadline().getSysStats(request, object : io.grpc.stub.StreamObserver<SysStatsResponse> {
                    override fun onNext(value: SysStatsResponse) {
                        Log.d("CoreStatsClient", "getSystemStats received response: uptime=${value.uptime}s, numGoroutine=${value.numGoroutine}, numGC=${value.numGC}, alloc=${value.alloc}, totalAlloc=${value.totalAlloc}, sys=${value.sys}, mallocs=${value.mallocs}, frees=${value.frees}, liveObjects=${value.liveObjects}, pauseTotalNs=${value.pauseTotalNs}")
                        continuation.resume(value)
                    }
                    
                    override fun onError(t: Throwable) {
                        val status = when (t) {
                            is StatusException -> t.status
                            else -> Status.fromThrowable(t)
                        }
                        val channelState = channel.getState(false)
                        Log.e("CoreStatsClient", "getSystemStats failed: ${status.code} - ${status.description}, channel state: $channelState", t)
                        
                        // If channel is in bad state, mark it for recreation and invalidate cache
                        if (status.code == Status.Code.UNAVAILABLE || 
                            status.code == Status.Code.DEADLINE_EXCEEDED ||
                            status.code == Status.Code.CANCELLED ||
                            status.code == Status.Code.UNIMPLEMENTED) {
                            if (channelState == ConnectivityState.SHUTDOWN || 
                                channelState == ConnectivityState.TRANSIENT_FAILURE) {
                                Log.w("CoreStatsClient", "Channel in bad state (${channelState}), will need recreation")
                                invalidateCache()
                            }
                            
                            // Log specific error codes
                            when (status.code) {
                                Status.Code.UNIMPLEMENTED -> {
                                    Log.w("CoreStatsClient", "GetSysStats RPC not implemented by Xray-core - this endpoint may not be available")
                                }
                                Status.Code.DEADLINE_EXCEEDED -> {
                                    Log.w("CoreStatsClient", "GetSysStats RPC deadline exceeded - Xray-core may be slow to respond")
                                }
                                Status.Code.UNAVAILABLE -> {
                                    Log.w("CoreStatsClient", "GetSysStats RPC unavailable - Xray-core may not be ready")
                                }
                                else -> {
                                    // Other error codes
                                }
                            }
                        }
                        continuation.resume(null)
                    }
                    
                    override fun onCompleted() {
                        // Already handled in onNext or onError
                        Log.d("CoreStatsClient", "getSystemStats RPC completed")
                    }
                })
            }
        }.onFailure { e ->
            Log.e("CoreStatsClient", "getSystemStats runCatching failed: ${e.message}", e)
        }.getOrNull().let { result ->
            if (result != null) {
                Log.d("CoreStatsClient", "getSystemStats successful: returning response with uptime=${result.uptime}s, alloc=${result.alloc}, sys=${result.sys}, mallocs=${result.mallocs}, frees=${result.frees}")
            } else {
                Log.w("CoreStatsClient", "getSystemStats returned null")
            }
            result
        }
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
            val allStatNames = mutableListOf<String>() // Track all stat names for debugging
            var matchedStatsCount = 0 // Track how many stats matched our patterns
            
            Log.d("CoreStatsClient", "Processing $statCount stats for traffic data...")
            
            response?.statList?.forEach { stat ->
                // Skip if we've already processed this stat
                if (seenStats.contains(stat.name)) {
                    Log.d("CoreStatsClient", "Skipping duplicate stat: ${stat.name}")
                    return@forEach
                }
                seenStats.add(stat.name)
                allStatNames.add(stat.name)
                
                Log.d("CoreStatsClient", "Stat: ${stat.name} = ${stat.value}")
                
                val statNameLower = stat.name.lowercase()
                val isUplink = statNameLower.contains("uplink", ignoreCase = true)
                val isDownlink = statNameLower.contains("downlink", ignoreCase = true)
                val hasTraffic = statNameLower.contains("traffic", ignoreCase = true)
                val isOutbound = statNameLower.contains("outbound", ignoreCase = true)
                val isInbound = statNameLower.contains("inbound", ignoreCase = true)
                
                // Improved pattern matching:
                // 1. First priority: stat names containing both "uplink"/"downlink" AND "traffic"
                // 2. Second priority: stat names containing "uplink"/"downlink" (even without "traffic")
                // 3. Also check for "outbound" (usually uplink) and "inbound" (usually downlink) patterns
                // Note: Check downlink patterns BEFORE uplink patterns to avoid conflicts
                var matched = false
                when {
                    // Downlink patterns (checked first to prioritize)
                    (isDownlink && hasTraffic) -> {
                        totalDownlink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found downlink+traffic stat: ${stat.name} = ${stat.value}, total now: $totalDownlink")
                    }
                    (isDownlink && !isUplink) -> {
                        // Only downlink, no uplink - likely downlink traffic
                        totalDownlink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found downlink-only stat: ${stat.name} = ${stat.value}, total now: $totalDownlink")
                    }
                    (isInbound && hasTraffic) -> {
                        // Inbound usually means downlink
                        totalDownlink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found inbound+traffic stat (downlink): ${stat.name} = ${stat.value}, total now: $totalDownlink")
                    }
                    (isInbound && !isOutbound && !isUplink) -> {
                        // Inbound without outbound/uplink - likely downlink (even without "traffic" keyword)
                        totalDownlink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found inbound-only stat (downlink): ${stat.name} = ${stat.value}, total now: $totalDownlink")
                    }
                    // Uplink patterns
                    (isUplink && hasTraffic) -> {
                        totalUplink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found uplink+traffic stat: ${stat.name} = ${stat.value}, total now: $totalUplink")
                    }
                    (isUplink && !isDownlink) -> {
                        // Only uplink, no downlink - likely uplink traffic
                        totalUplink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found uplink-only stat: ${stat.name} = ${stat.value}, total now: $totalUplink")
                    }
                    (isOutbound && hasTraffic) -> {
                        // Outbound usually means uplink
                        totalUplink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found outbound+traffic stat (uplink): ${stat.name} = ${stat.value}, total now: $totalUplink")
                    }
                    (isOutbound && !isInbound && !isDownlink) -> {
                        // Outbound without inbound/downlink - likely uplink (even without "traffic" keyword)
                        totalUplink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found outbound-only stat (uplink): ${stat.name} = ${stat.value}, total now: $totalUplink")
                    }
                }
                
                // Log unmatched stats for debugging (only if they seem traffic-related)
                if (!matched && (isUplink || isDownlink || isOutbound || isInbound || hasTraffic)) {
                    Log.d("CoreStatsClient", "⚠ Unmatched traffic-related stat: ${stat.name} = ${stat.value} (uplink=$isUplink, downlink=$isDownlink, traffic=$hasTraffic, outbound=$isOutbound, inbound=$isInbound)")
                }
            }
            
            // Log summary
            if (totalUplink == 0L && totalDownlink == 0L && allStatNames.isNotEmpty()) {
                // Stats were found and processed but all values are 0 (normal at startup)
                Log.d("CoreStatsClient", "✓ Traffic stats found but all zero (startup): Uplink=0, Downlink=0 (matched $matchedStatsCount/${allStatNames.size} stats)")
            } else if (matchedStatsCount > 0) {
                Log.i("CoreStatsClient", "✓ Traffic stats summary: Uplink=${totalUplink} bytes, Downlink=${totalDownlink} bytes (matched $matchedStatsCount/${allStatNames.size} stats)")
            } else {
                Log.w("CoreStatsClient", "⚠ No traffic stats matched! Processed ${allStatNames.size} unique stats, matched $matchedStatsCount. All stat names: ${allStatNames.joinToString(", ")}")
            }
            
            // Return TrafficState if stats were matched (even if values are 0)
            // Zero values are valid - they just mean no traffic yet (normal at startup)
            if (matchedStatsCount > 0) {
                TrafficState(totalUplink, totalDownlink)
            } else {
                // No stats matched the traffic patterns
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
        val allStatNames = mutableListOf<String>() // Track all stat names for debugging
        var matchedStatsCount = 0 // Track how many stats matched our patterns
        
        Log.d("CoreStatsClient", "Trying fallback patterns: ${patterns.joinToString(", ")}")
        
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
            Log.d("CoreStatsClient", "Fallback pattern '$pattern' returned $statCount stats")
            
            response?.statList?.forEach { stat ->
                // Skip if we've already processed this stat
                if (seenStats.contains(stat.name)) {
                    Log.d("CoreStatsClient", "Skipping duplicate stat: ${stat.name}")
                    return@forEach
                }
                seenStats.add(stat.name)
                allStatNames.add(stat.name)
                
                Log.d("CoreStatsClient", "Fallback stat: ${stat.name} = ${stat.value}")
                
                val statNameLower = stat.name.lowercase()
                val isUplink = statNameLower.contains("uplink", ignoreCase = true)
                val isDownlink = statNameLower.contains("downlink", ignoreCase = true)
                val hasTraffic = statNameLower.contains("traffic", ignoreCase = true)
                val isOutbound = statNameLower.contains("outbound", ignoreCase = true)
                val isInbound = statNameLower.contains("inbound", ignoreCase = true)
                
                // Improved pattern matching (same as main function):
                // 1. First priority: stat names containing both "uplink"/"downlink" AND "traffic"
                // 2. Second priority: stat names containing "uplink"/"downlink" (even without "traffic")
                // 3. Also check for "outbound" (usually uplink) and "inbound" (usually downlink) patterns
                // Note: Check downlink patterns BEFORE uplink patterns to avoid conflicts
                var matched = false
                when {
                    // Downlink patterns (checked first to prioritize)
                    (isDownlink && hasTraffic) -> {
                        totalDownlink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found downlink+traffic stat (fallback): ${stat.name} = ${stat.value}, total now: $totalDownlink")
                    }
                    (isDownlink && !isUplink) -> {
                        // Only downlink, no uplink - likely downlink traffic
                        totalDownlink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found downlink-only stat (fallback): ${stat.name} = ${stat.value}, total now: $totalDownlink")
                    }
                    (isInbound && hasTraffic) -> {
                        // Inbound usually means downlink
                        totalDownlink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found inbound+traffic stat (downlink, fallback): ${stat.name} = ${stat.value}, total now: $totalDownlink")
                    }
                    (isInbound && !isOutbound && !isUplink) -> {
                        // Inbound without outbound/uplink - likely downlink (even without "traffic" keyword)
                        totalDownlink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found inbound-only stat (downlink, fallback): ${stat.name} = ${stat.value}, total now: $totalDownlink")
                    }
                    // Uplink patterns
                    (isUplink && hasTraffic) -> {
                        totalUplink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found uplink+traffic stat (fallback): ${stat.name} = ${stat.value}, total now: $totalUplink")
                    }
                    (isUplink && !isDownlink) -> {
                        // Only uplink, no downlink - likely uplink traffic
                        totalUplink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found uplink-only stat (fallback): ${stat.name} = ${stat.value}, total now: $totalUplink")
                    }
                    (isOutbound && hasTraffic) -> {
                        // Outbound usually means uplink
                        totalUplink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found outbound+traffic stat (uplink, fallback): ${stat.name} = ${stat.value}, total now: $totalUplink")
                    }
                    (isOutbound && !isInbound && !isDownlink) -> {
                        // Outbound without inbound/downlink - likely uplink (even without "traffic" keyword)
                        totalUplink += stat.value
                        matched = true
                        matchedStatsCount++
                        Log.d("CoreStatsClient", "✓ Found outbound-only stat (uplink, fallback): ${stat.name} = ${stat.value}, total now: $totalUplink")
                    }
                }
                
                // Log unmatched stats for debugging (only if they seem traffic-related)
                if (!matched && (isUplink || isDownlink || isOutbound || isInbound || hasTraffic)) {
                    Log.d("CoreStatsClient", "⚠ Unmatched traffic-related stat (fallback): ${stat.name} = ${stat.value} (uplink=$isUplink, downlink=$isDownlink, traffic=$hasTraffic, outbound=$isOutbound, inbound=$isInbound)")
                }
            }
        }
        
        // Log summary
        if (totalUplink == 0L && totalDownlink == 0L && allStatNames.isNotEmpty()) {
            // Stats were found and processed but all values are 0 (normal at startup)
            Log.d("CoreStatsClient", "✓ Fallback: Traffic stats found but all zero (startup): Uplink=0, Downlink=0 (matched $matchedStatsCount/${allStatNames.size} stats)")
        } else if (matchedStatsCount > 0) {
            Log.i("CoreStatsClient", "✓ Fallback traffic stats summary: Uplink=${totalUplink} bytes, Downlink=${totalDownlink} bytes (matched $matchedStatsCount/${allStatNames.size} stats)")
        } else {
            Log.w("CoreStatsClient", "⚠ Fallback: No traffic stats matched! Processed ${allStatNames.size} unique stats, matched $matchedStatsCount. All stat names: ${allStatNames.joinToString(", ")}")
        }
        
        // Return TrafficState if stats were matched (even if values are 0)
        // Zero values are valid - they just mean no traffic yet (normal at startup)
        return if (matchedStatsCount > 0) {
            TrafficState(totalUplink, totalDownlink)
        } else {
            // No stats matched the traffic patterns
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



