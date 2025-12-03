package com.hyperxray.an.core.network

import android.content.Context
import com.hyperxray.an.core.network.http.HttpClientFactory
import android.util.Log

private const val TAG = "NetworkModule"

/**
 * Core Network Module
 * 
 * This module provides network-related functionality including:
 * - gRPC client communication
 * - HTTP client operations with OkHttp + Conscrypt TLS acceleration
 * - Network utilities and helpers
 * - TLS/SSL feature encoding
 */
object NetworkModule {
    /**
     * Module identifier
     */
    const val MODULE_NAME = "core-network"
    
    private var isInitialized = false
    
    /**
     * Initializes the network module with the application context.
     * Must be called during app startup (e.g., in Application.onCreate()).
     * 
     * @param context Application context
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.i(TAG, "⚠️ NetworkModule already initialized")
            return
        }
        
        try {
            // Initialize HTTP client factory with OkHttp + Conscrypt
            Log.i(TAG, "🚀 Initializing NetworkModule (HttpClientFactory + DNS Cache + HTTP Cache + Retry)")
            HttpClientFactory.init(context.applicationContext)
            
            isInitialized = true
            Log.i(TAG, "✅ NetworkModule initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize NetworkModule", e)
            // Continue anyway - OkHttp fallback will work
            isInitialized = true
        }
    }
    
    /**
     * Check if the module has been initialized
     */
    fun isInitialized(): Boolean {
        return isInitialized
    }
    
    /**
     * Get the HTTP client factory instance
     */
    fun getHttpClientFactory(): HttpClientFactory {
        return HttpClientFactory
    }
    
    /**
     * Shutdown and cleanup network resources
     */
    fun shutdown() {
        try {
            HttpClientFactory.shutdown()
            isInitialized = false
            Log.d(TAG, "NetworkModule shut down")
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down NetworkModule", e)
        }
    }
}

