package com.hyperxray.an.core.network.http

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

private const val TAG = "RetryInterceptor"
private const val MAX_RETRY_ATTEMPTS = 3

/**
 * Interceptor that automatically retries failed requests with exponential backoff.
 * Retries on transient network errors like timeouts and connection failures.
 */
class RetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response: Response? = null
        var lastException: IOException? = null

        // Try up to MAX_RETRY_ATTEMPTS times
        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            try {
                response = chain.proceed(request)
                
                // If response is successful, return it immediately
                if (response.isSuccessful) {
                    if (attempt > 1) {
                        Log.i(TAG, "✅ Request succeeded after $attempt retry attempts: ${request.url}")
                    }
                    return response
                }

                // If response is not successful but not a retryable error, return it
                val statusCode = response.code
                if (!isRetryableError(statusCode)) {
                    Log.i(TAG, "⚠️ Request failed with non-retryable error $statusCode: ${request.url}")
                    return response
                }

                // Close the response body before retrying
                response.close()
                response = null

                Log.i(TAG, "🔄 Request failed with retryable error $statusCode, retrying (attempt $attempt/$MAX_RETRY_ATTEMPTS): ${request.url}")

            } catch (e: IOException) {
                lastException = e
                
                // Check if this is a retryable exception
                if (!isRetryableException(e)) {
                    Log.d(TAG, "Non-retryable exception: ${e.javaClass.simpleName}, giving up")
                    throw e
                }

                Log.i(TAG, "🔄 Request failed with retryable exception: ${e.javaClass.simpleName}, retrying (attempt $attempt/$MAX_RETRY_ATTEMPTS): ${request.url}")

                // If this was the last attempt, throw the exception
                if (attempt == MAX_RETRY_ATTEMPTS) {
                    Log.w(TAG, "❌ Max retry attempts ($MAX_RETRY_ATTEMPTS) reached, giving up: ${request.url}")
                    throw e
                }
            }

            // Calculate exponential backoff delay: 1s, 2s, 4s
            val backoffDelay = (1 shl (attempt - 1)).toLong() // 2^(attempt-1) seconds
            Log.i(TAG, "⏳ Waiting ${backoffDelay}s before retry attempt ${attempt + 1}...")

            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(backoffDelay))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Retry interrupted", e)
            }
        }

        // If we get here, all retries failed
        if (response != null) {
            return response
        }

        // Throw the last exception if we have one
        throw lastException ?: IOException("Request failed after $MAX_RETRY_ATTEMPTS attempts")
    }

    /**
     * Check if an HTTP status code is retryable
     */
    private fun isRetryableError(code: Int): Boolean {
        // Retry on server errors (5xx) and some client errors that might be transient
        return code in 500..599 || code == 408 // Request Timeout
    }

    /**
     * Check if an exception is retryable
     */
    private fun isRetryableException(e: IOException): Boolean {
        return when (e) {
            is SocketTimeoutException -> true
            is ConnectException -> true
            else -> {
                // Check for other transient network errors
                val message = e.message?.lowercase() ?: ""
                message.contains("timeout") ||
                message.contains("connection") ||
                message.contains("network") ||
                message.contains("reset") ||
                message.contains("refused")
            }
        }
    }
}

