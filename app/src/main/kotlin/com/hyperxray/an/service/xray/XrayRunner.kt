package com.hyperxray.an.service.xray

/**
 * Strategy interface for executing Xray-core processes.
 * 
 * Different implementations handle single-instance (legacy) vs multi-instance execution.
 */
interface XrayRunner {
    /**
     * Start Xray-core with the provided configuration.
     * 
     * @param config Configuration containing file path, content, and other parameters
     */
    suspend fun start(config: XrayConfig)
    
    /**
     * Stop Xray-core gracefully.
     */
    suspend fun stop()
    
    /**
     * Check if Xray-core is currently running.
     * 
     * @return true if running, false otherwise
     */
    fun isRunning(): Boolean
}


