package com.hyperxray.an.core.network.http

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

private const val TAG = "CacheLoggingInterceptor"

/**
 * Interceptor that logs HTTP cache usage (cache hits/misses)
 */
class CacheLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        
        // Log cache usage
        val cacheControl = response.cacheControl.toString()
        val networkResponse = response.networkResponse
        val cacheResponse = response.cacheResponse
        
        when {
            cacheResponse != null -> {
                // Response served from cache
                Log.i(TAG, "✅ HTTP CACHE HIT: ${request.url} (served from cache)")
            }
            networkResponse != null -> {
                // Response from network
                Log.i(TAG, "⚠️ HTTP CACHE MISS: ${request.url} (served from network)")
            }
            else -> {
                // Response from both (conditional request)
                Log.i(TAG, "🔄 HTTP CACHE: ${request.url} (conditional request)")
            }
        }
        
        return response
    }
}

