package com.hyperxray.an

import android.app.Application
import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.core.di.AppContainer
import com.hyperxray.an.core.di.DefaultAppContainer
import com.hyperxray.an.data.source.LogFileManager

/**
 * HyperXrayApplication: Application class for app initialization.
 * 
 * Responsibilities:
 * - DI container initialization
 * - Log helper initialization
 * - Delegation of component initialization
 * 
 * Note: AppInitializer contains temporary initialization logic that will be
 * moved to feature modules when telemetry classes are migrated.
 */
class HyperXrayApplication : Application() {
    private val TAG = "HyperXrayApplication"
    
    // DI container
    lateinit var appContainer: AppContainer
        private set
    
    // Temporary app initializer (will be moved to feature modules)
    private var appInitializer: AppInitializer? = null

    override fun onCreate() {
        super.onCreate()
        
        // Initialize DI container
        appContainer = DefaultAppContainer(this)
        
        // Initialize AiLogHelper
        val logFileManager = LogFileManager(this)
        AiLogHelper.initialize(this, logFileManager)
        
        Log.d(TAG, "HyperXrayApplication onCreate - initializing components")
        AiLogHelper.d(TAG, "HyperXrayApplication onCreate - initializing components")
        
        // Initialize app components (temporary - will be moved to feature modules)
        appInitializer = AppInitializer(this)
        appInitializer?.initialize()
    }
    
    companion object {
        @Volatile
        private var instance: HyperXrayApplication? = null
        
        fun getInstance(): HyperXrayApplication? {
            return instance
        }
    }
    
    init {
        instance = this
    }
}
