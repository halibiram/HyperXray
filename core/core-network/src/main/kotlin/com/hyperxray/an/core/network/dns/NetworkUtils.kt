package com.hyperxray.an.core.network.dns

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.IOException
import java.net.SocketException
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "NetworkUtils"

/**
 * Retry a suspend function with exponential backoff.
 * 
 * @param times Maximum number of retry attempts (default: 3)
 * @param initialDelay Initial delay in milliseconds before first retry (default: 100ms)
 * @param factor Exponential backoff factor (default: 2.0)
 * @param block The suspend function to retry
 * @return Result of the block function
 * @throws Exception If all retries are exhausted or coroutine is cancelled
 */
suspend fun <T> retryWithBackoff(
    times: Int = 3,
    initialDelay: Long = 100,
    factor: Double = 2.0,
    block: suspend () -> T
): T {
    var currentDelay = initialDelay
    var lastException: Exception? = null
    
    repeat(times) { attempt ->
        try {
            // Check if coroutine is still active before attempting
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("Coroutine was cancelled")
            }
            
            return block()
        } catch (e: CancellationException) {
            // Re-throw cancellation immediately (don't retry)
            throw e
        } catch (e: IOException) {
            lastException = e
            if (attempt < times - 1) {
                // Check if coroutine is still active before waiting
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("Coroutine was cancelled during retry")
                }
                
                Log.d(TAG, "Retry attempt ${attempt + 1}/$times after ${e.javaClass.simpleName}: ${e.message}, waiting ${currentDelay}ms...")
                SocketMetrics.recordRetryAttempt()
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong()
            } else {
                Log.w(TAG, "All retry attempts exhausted after $times attempts: ${e.message}")
            }
        } catch (e: SocketException) {
            lastException = e
            if (attempt < times - 1) {
                // Check if coroutine is still active before waiting
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("Coroutine was cancelled during retry")
                }
                
                Log.d(TAG, "Retry attempt ${attempt + 1}/$times after ${e.javaClass.simpleName}: ${e.message}, waiting ${currentDelay}ms...")
                SocketMetrics.recordRetryAttempt()
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong()
            } else {
                Log.w(TAG, "All retry attempts exhausted after $times attempts: ${e.message}")
            }
        } catch (e: Exception) {
            // For non-network exceptions, don't retry
            Log.e(TAG, "Non-retryable exception: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
    }
    
    // If we get here, all retries were exhausted
    throw lastException ?: IOException("All retry attempts exhausted")
}

