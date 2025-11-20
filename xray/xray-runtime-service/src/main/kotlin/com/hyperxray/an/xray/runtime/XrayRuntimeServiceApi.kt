package com.hyperxray.an.xray.runtime

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Callback interface for log lines from Xray process.
 */
fun interface LogLineCallback {
    fun onLogLine(line: String)
}

/**
 * Public API for Xray Runtime Service.
 * 
 * This interface provides a safe, controlled API for managing the Xray-core
 * binary lifecycle. It abstracts away implementation details and provides
 * a clean contract for consumers.
 * 
 * Thread-safety: All methods are thread-safe and can be called from any thread.
 */
interface XrayRuntimeServiceApi {
    /**
     * Current runtime status of Xray-core.
     * 
     * Observers can collect from this StateFlow to react to status changes.
     */
    val status: StateFlow<XrayRuntimeStatus>
    
    /**
     * Start Xray-core with the provided configuration.
     * 
     * @param configPath Path to the Xray configuration JSON file
     * @param configContent Optional pre-read configuration content (for security)
     * @param apiPort Optional API port (if null, will find available port)
     * @return The API port number used, or null if startup failed
     */
    fun start(
        configPath: String,
        configContent: String? = null,
        apiPort: Int? = null
    ): Int?
    
    /**
     * Stop Xray-core gracefully.
     */
    fun stop()
    
    /**
     * Restart Xray-core with new configuration.
     * 
     * @param configPath Path to the Xray configuration JSON file
     * @param configContent Optional pre-read configuration content
     * @param apiPort Optional API port
     * @return The API port number used, or null if restart failed
     */
    fun restart(
        configPath: String,
        configContent: String? = null,
        apiPort: Int? = null
    ): Int?
    
    /**
     * Check if Xray-core is currently running.
     * 
     * @return true if running, false otherwise
     */
    fun isRunning(): Boolean
    
    /**
     * Get the current process ID if running.
     * 
     * @return Process ID, or null if not running
     */
    fun getProcessId(): Long?
    
    /**
     * Get the current API port if running.
     * 
     * @return API port number, or null if not running
     */
    fun getApiPort(): Int?
    
    /**
     * Cleanup resources. Call this when the service is no longer needed.
     */
    fun cleanup()
    
    /**
     * Set callback for log lines from Xray process.
     * 
     * @param callback Callback to receive log lines, or null to disable
     */
    fun setLogLineCallback(callback: LogLineCallback?)
}

/**
 * Factory for creating XrayRuntimeServiceApi instances.
 */
object XrayRuntimeServiceFactory {
    /**
     * Create a new XrayRuntimeServiceApi instance.
     * 
     * @param context Android context
     * @return XrayRuntimeServiceApi instance
     */
    fun create(context: Context): XrayRuntimeServiceApi {
        return XrayRuntimeService(context)
    }
}

