package com.hyperxray.an.service.managers

import android.content.Context
import android.util.Log
import com.hyperxray.an.core.inference.OnnxRuntimeManager
import com.hyperxray.an.core.network.dns.DnsCacheManager
import com.hyperxray.an.notification.TelegramNotificationManager
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.service.state.ServiceSessionState
import com.hyperxray.an.telemetry.TProxyAiOptimizer

/**
 * Manages initialization and cleanup of service dependencies.
 * Centralizes dependency management to keep TProxyService.onCreate() clean.
 */
class ServiceDependencyManager {
    private val TAG = "ServiceDependencyManager"
    
    // Dependencies - exposed as properties
    var tproxyAiOptimizer: TProxyAiOptimizer? = null
        private set
    
    var telegramNotificationManager: TelegramNotificationManager? = null
        private set
    
    private var dnsCacheInitialized = false
    
    /**
     * Initialize all service dependencies.
     * @param context Service context
     * @param session Service session state
     */
    fun setup(context: Context, session: ServiceSessionState) {
        Log.d(TAG, "Setting up service dependencies...")
        
        // Initialize TProxyAiOptimizer
        initializeTProxyAiOptimizer(context, session)
        
        // Initialize ONNX Runtime Manager
        initializeOnnxRuntimeManager(context)
        
        // Initialize Telegram Notification Manager
        initializeTelegramNotificationManager(context, session)
        
        // Initialize DNS Cache Manager
        initializeDnsCacheManager(context, session)
        
        Log.d(TAG, "Service dependencies setup completed")
    }
    
    /**
     * Cleanup all service dependencies.
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up service dependencies...")
        
        // Stop AI optimizer
        tproxyAiOptimizer?.stopOptimization()
        tproxyAiOptimizer = null
        
        // Release ONNX Runtime Manager
        OnnxRuntimeManager.release()
        
        // Telegram notification manager doesn't need explicit cleanup
        telegramNotificationManager = null
        
        // DNS Cache Manager doesn't need explicit cleanup (object singleton)
        dnsCacheInitialized = false
        
        Log.d(TAG, "Service dependencies cleanup completed")
    }
    
    /**
     * Initialize AI-powered TProxy optimizer.
     */
    private fun initializeTProxyAiOptimizer(context: Context, session: ServiceSessionState) {
        try {
            val prefs = Preferences(context)
            session.tproxyAiOptimizer = TProxyAiOptimizer(context, prefs)
            tproxyAiOptimizer = session.tproxyAiOptimizer
            Log.d(TAG, "TProxyAiOptimizer initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TProxyAiOptimizer: ${e.message}", e)
        }
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
    
    /**
     * Initialize DNS Cache Manager.
     */
    private fun initializeDnsCacheManager(context: Context, session: ServiceSessionState) {
        if (session.dnsCacheInitialized) {
            Log.d(TAG, "DnsCacheManager already initialized")
            return
        }
        
        try {
            DnsCacheManager.initialize(context)
            session.dnsCacheInitialized = true
            dnsCacheInitialized = true
            Log.d(TAG, "DnsCacheManager initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize DnsCacheManager: ${e.message}", e)
        }
    }
}




