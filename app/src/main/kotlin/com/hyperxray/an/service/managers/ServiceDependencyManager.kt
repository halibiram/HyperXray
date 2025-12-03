package com.hyperxray.an.service.managers

import android.content.Context
import android.util.Log
import com.hyperxray.an.core.inference.OnnxRuntimeManager
import com.hyperxray.an.notification.TelegramNotificationManager
import com.hyperxray.an.service.state.ServiceSessionState

/**
 * Manages initialization and cleanup of service dependencies.
 * Centralizes dependency management to keep TProxyService.onCreate() clean.
 * 
 * Note: DNS cache removed - using Google DNS (8.8.8.8) directly
 */
class ServiceDependencyManager {
    private val TAG = "ServiceDependencyManager"
    
    // Dependencies - exposed as properties
    
    var telegramNotificationManager: TelegramNotificationManager? = null
        private set
    
    /**
     * Initialize all service dependencies.
     * @param context Service context
     * @param session Service session state
     */
    fun setup(context: Context, session: ServiceSessionState) {
        Log.d(TAG, "Setting up service dependencies...")
        
        // Initialize ONNX Runtime Manager
        initializeOnnxRuntimeManager(context)
        
        // Initialize Telegram Notification Manager
        initializeTelegramNotificationManager(context, session)
        
        Log.d(TAG, "Service dependencies setup completed")
    }
    
    /**
     * Cleanup all service dependencies.
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up service dependencies...")
        
        // Release ONNX Runtime Manager
        OnnxRuntimeManager.release()
        
        // Telegram notification manager doesn't need explicit cleanup
        telegramNotificationManager = null
        
        Log.d(TAG, "Service dependencies cleanup completed")
    }
    
    /**
     * Initialize ONNX Runtime Manager for TLS SNI optimization.
     */
    private fun initializeOnnxRuntimeManager(context: Context) {
        try {
            OnnxRuntimeManager.init(context)
            if (OnnxRuntimeManager.isReady()) {
                Log.i(TAG, "TLS SNI optimizer model loaded successfully")
            } else {
                Log.w(TAG, "TLS SNI optimizer model failed to load")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TLS SNI optimizer: ${e.message}", e)
        }
    }
    
    /**
     * Initialize Telegram notification manager.
     */
    private fun initializeTelegramNotificationManager(context: Context, session: ServiceSessionState) {
        try {
            session.telegramNotificationManager = TelegramNotificationManager.getInstance(context)
            telegramNotificationManager = session.telegramNotificationManager
            Log.d(TAG, "Telegram notification manager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Telegram notification manager", e)
        }
    }
}
