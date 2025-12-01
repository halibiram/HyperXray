package com.hyperxray.an

import android.app.Application
import android.os.Process
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.core.di.AppContainer
import com.hyperxray.an.core.di.DefaultAppContainer
import com.hyperxray.an.core.network.NetworkModule
import com.hyperxray.an.data.source.LogFileManager
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

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

    /**
     * Get AppInitializer instance (if initialized)
     * Used by notification features to access AI optimizer components
     */
    fun getAppInitializer(): AppInitializer? = appInitializer

    override fun onCreate() {
        super.onCreate()
        
        // Install global uncaught exception handler to prevent crashes
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", exception)
            AiLogHelper.e(TAG, "Uncaught exception in thread ${thread.name}: ${exception.message}")
            
            // Save crash log to file
            saveCrashLog(exception)
            
            // Call default handler (which will show crash dialog and kill process)
            defaultHandler?.uncaughtException(thread, exception) ?: run {
                // If no default handler, kill process gracefully
                Process.killProcess(Process.myPid())
            }
        }
        
        // Initialize DI container
        try {
            appContainer = DefaultAppContainer(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DI container: ${e.message}", e)
            AiLogHelper.e(TAG, "Failed to initialize DI container: ${e.message}")
            // Continue with initialization even if DI fails
        }
        
        // Initialize AiLogHelper
        try {
            val logFileManager = LogFileManager(this)
            AiLogHelper.initialize(this, logFileManager)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AiLogHelper: ${e.message}", e)
            // Continue without AI logging
        }
        
        // Set singleton instance after super.onCreate() and before any initialization
        instance = this
        
        Log.d(TAG, "HyperXrayApplication onCreate - initializing components")
        AiLogHelper.d(TAG, "HyperXrayApplication onCreate - initializing components")
        
        // Initialize WorkManager early to prevent initialization errors
        try {
            val workManagerConfig = Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build()
            WorkManager.initialize(this, workManagerConfig)
            Log.d(TAG, "WorkManager initialized")
        } catch (e: IllegalStateException) {
            // WorkManager already initialized, ignore
            Log.d(TAG, "WorkManager already initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize WorkManager: ${e.message}", e)
            // Continue without WorkManager - workers will retry later
        }
        
        // Initialize NetworkModule (Cronet + Conscrypt TLS acceleration)
        try {
            NetworkModule.initialize(this)
            Log.d(TAG, "NetworkModule initialized (Conscrypt TLS acceleration enabled)")
            AiLogHelper.d(TAG, "NetworkModule initialized (Conscrypt TLS acceleration enabled)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize NetworkModule: ${e.message}", e)
            AiLogHelper.w(TAG, "Failed to initialize NetworkModule: ${e.message}")
            // Continue without network optimizations - OkHttp fallback will work
        }
        
        // Initialize app components (temporary - will be moved to feature modules)
        try {
            appInitializer = AppInitializer(this)
            appInitializer?.initialize()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AppInitializer: ${e.message}", e)
            AiLogHelper.e(TAG, "Failed to initialize AppInitializer: ${e.message}")
            // Continue without app initializer - app should still be usable
        }
    }
    
    /**
     * Save crash log to file for debugging.
     */
    private fun saveCrashLog(exception: Throwable) {
        try {
            val crashDir = File(filesDir, "crashes")
            if (!crashDir.exists()) {
                crashDir.mkdirs()
            }
            
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val crashFile = File(crashDir, "crash_$timestamp.txt")
            
            FileWriter(crashFile).use { writer ->
                PrintWriter(writer).use { printWriter ->
                    printWriter.println("HyperXray Crash Report")
                    printWriter.println("Timestamp: $timestamp")
                    printWriter.println("PID: ${Process.myPid()}")
                    printWriter.println("Package: $packageName")
                    printWriter.println()
                    printWriter.println("Exception:")
                    exception.printStackTrace(printWriter)
                    printWriter.println()
                    printWriter.println("Stack trace:")
                    val sw = StringWriter()
                    exception.printStackTrace(PrintWriter(sw))
                    printWriter.println(sw.toString())
                }
            }
            
            Log.d(TAG, "Crash log saved to: ${crashFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log: ${e.message}", e)
        }
    }
    
    companion object {
        @Volatile
        private var instance: HyperXrayApplication? = null
        
        fun getInstance(): HyperXrayApplication? {
            return instance
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        
        // Cleanup AppInitializer resources (cancel coroutine scope, release AI models)
        try {
            appInitializer?.cleanup()
            appInitializer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up AppInitializer: ${e.message}", e)
        }
        
        instance = null
        Log.d(TAG, "HyperXrayApplication terminated")
    }
}
