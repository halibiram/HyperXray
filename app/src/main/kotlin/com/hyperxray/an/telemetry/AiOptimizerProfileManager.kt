package com.hyperxray.an.telemetry

import android.content.Context
import android.os.Build
import android.util.Log
import com.hyperxray.an.common.AiLogHelper

/**
 * AiOptimizerProfileManager: Manages AI optimizer performance profiles.
 * 
 * Automatically selects appropriate profile based on device capabilities
 * and provides profile switching functionality.
 */
class AiOptimizerProfileManager(private val context: Context) {
    private val TAG = "AiOptimizerProfileManager"
    
    private var currentProfile: AiOptimizerProfile? = null
    private var deviceCapabilities: DeviceCapabilities? = null
    
    /**
     * Device capabilities detected at runtime
     */
    data class DeviceCapabilities(
        val hasNNAPI: Boolean,
        val hasGPU: Boolean,
        val deviceModel: String,
        val socModel: String?,
        val recommendedProfile: String
    )
    
    init {
        detectDeviceCapabilities()
        selectOptimalProfile()
    }
    
    /**
     * Detect device capabilities for profile selection
     */
    private fun detectDeviceCapabilities() {
        val deviceModel = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        val socModel = try {
            // Try to detect SOC model from system properties
            val socProp = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, "ro.board.platform") as? String
            socProp ?: "unknown"
        } catch (e: Exception) {
            null
        }
        
        // Check for NNAPI support (Android Neural Networks API)
        val hasNNAPI = try {
            // NNAPI is available on Android 8.0+ (API 26+)
            Build.VERSION.SDK_INT >= 26
        } catch (e: Exception) {
            false
        }
        
        // Check for GPU support (assume available on most modern devices)
        val hasGPU = true // Most Android devices have GPU
        
        // Determine recommended profile based on device
        val recommendedProfile = when {
            // High-tier devices with NPU support
            deviceModel.contains("Snapdragon", ignoreCase = true) && 
            (deviceModel.contains("8", ignoreCase = true) || deviceModel.contains("778", ignoreCase = true)) -> {
                "ultra-performance"
            }
            deviceModel.contains("Dimensity", ignoreCase = true) && 
            (deviceModel.contains("9200", ignoreCase = true) || deviceModel.contains("9300", ignoreCase = true)) -> {
                "ultra-performance"
            }
            deviceModel.contains("Tensor", ignoreCase = true) && 
            (deviceModel.contains("G2", ignoreCase = true) || deviceModel.contains("G3", ignoreCase = true)) -> {
                "ultra-performance"
            }
            // Mid-tier or unknown devices
            else -> "balanced"
        }
        
        deviceCapabilities = DeviceCapabilities(
            hasNNAPI = hasNNAPI,
            hasGPU = hasGPU,
            deviceModel = deviceModel,
            socModel = socModel,
            recommendedProfile = recommendedProfile
        )
        
        Log.i(TAG, "Device capabilities detected:")
        Log.i(TAG, "  - Device: $deviceModel")
        Log.i(TAG, "  - SOC: ${socModel ?: "unknown"}")
        Log.i(TAG, "  - NNAPI: $hasNNAPI")
        Log.i(TAG, "  - GPU: $hasGPU")
        Log.i(TAG, "  - Recommended profile: $recommendedProfile")
        
        AiLogHelper.i(TAG, "Device: $deviceModel, NNAPI: $hasNNAPI, GPU: $hasGPU, Profile: $recommendedProfile")
    }
    
    /**
     * Select optimal profile based on device capabilities
     * AGGRESSIVE MODE: Always prefer Ultra-Performance profile
     */
    fun selectOptimalProfile(): AiOptimizerProfile {
        val capabilities = deviceCapabilities ?: run {
            detectDeviceCapabilities()
            deviceCapabilities!!
        }
        
        // AGGRESSIVE MODE: Force Ultra-Performance profile for maximum performance
        val profile = if (true) { // Always use ultra-performance for aggressive mode
            Log.i(TAG, "AGGRESSIVE MODE: Selecting Ultra-Performance profile (forced)")
            AiLogHelper.i(TAG, "AGGRESSIVE MODE: Ultra-Performance profile enabled")
            AiOptimizerProfile.ultraPerformance()
        } else {
            when (capabilities.recommendedProfile) {
                "ultra-performance" -> {
                    Log.i(TAG, "Selecting Ultra-Performance profile")
                    AiOptimizerProfile.ultraPerformance()
                }
                else -> {
                    Log.i(TAG, "Selecting Balanced profile")
                    AiOptimizerProfile.balanced()
                }
            }
        }
        
        currentProfile = profile
        return profile
    }
    
    /**
     * Get current active profile
     */
    fun getCurrentProfile(): AiOptimizerProfile? {
        return currentProfile
    }
    
    /**
     * Set profile explicitly (for testing or user preference)
     */
    fun setProfile(profile: AiOptimizerProfile) {
        Log.i(TAG, "Setting profile: ${profile.name}")
        AiLogHelper.i(TAG, "Setting profile: ${profile.name}")
        currentProfile = profile
    }
    
    /**
     * Get device capabilities
     */
    fun getDeviceCapabilities(): DeviceCapabilities? {
        return deviceCapabilities
    }
    
    /**
     * Check if device supports ultra-performance profile
     */
    fun supportsUltraPerformance(): Boolean {
        val capabilities = deviceCapabilities ?: return false
        return capabilities.recommendedProfile == "ultra-performance"
    }
    
    companion object {
        /**
         * Create profile manager instance
         */
        fun create(context: Context): AiOptimizerProfileManager {
            return AiOptimizerProfileManager(context)
        }
    }
}



