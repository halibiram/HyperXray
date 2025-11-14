package com.hyperxray.an.xray.runtime

/**
 * Xray Runtime Service Module
 * 
 * This module provides runtime management for the Xray-core binary:
 * - Start / stop / restart Xray-core binary
 * - Send status events through Flows
 * - Provide a safe public API for control
 * 
 * Architecture:
 * - Core modules may depend on xray-runtime-service
 * - App and features CANNOT talk to binary directly
 * - All binary interaction goes through this service
 */
object XrayRuntimeModule {
    /**
     * Module identifier
     */
    const val MODULE_NAME = "xray-runtime-service"
    
    /**
     * Initializes the Xray Runtime Service module
     */
    fun initialize() {
        // Module initialization if needed
    }
}

