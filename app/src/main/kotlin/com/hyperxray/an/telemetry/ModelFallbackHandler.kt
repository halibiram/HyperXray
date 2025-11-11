package com.hyperxray.an.telemetry

import android.content.Context
import android.util.Log
import com.hyperxray.an.telemetry.RealityArm

/**
 * ModelFallbackHandler: Handles fallback behavior when model is invalid or unavailable.
 * 
 * Provides baseline policy when:
 * - Model fails to load
 * - Model verification fails
 * - Model inference fails
 * - Model is corrupted or missing
 */
class ModelFallbackHandler(private val context: Context) {
    private val TAG = "ModelFallbackHandler"
    
    /**
     * Fallback policy type
     */
    enum class FallbackPolicy {
        RANDOM,           // Random selection
        FIRST,            // Always select first arm
        ROUND_ROBIN,      // Round-robin selection
        BANDIT_ONLY,      // Use bandit algorithm only (no deep model)
        CONSERVATIVE      // Conservative selection (lowest risk)
    }
    
    private var fallbackPolicy: FallbackPolicy = FallbackPolicy.BANDIT_ONLY
    private var roundRobinIndex: Int = 0
    
    /**
     * Select arm using fallback policy
     * 
     * @param availableArms List of available arms
     * @param banditArm Arm selected by bandit (if available)
     * @return Selected arm based on fallback policy
     */
    fun selectFallbackArm(
        availableArms: List<RealityArm>,
        banditArm: RealityArm? = null
    ): RealityArm? {
        if (availableArms.isEmpty()) {
            Log.w(TAG, "No available arms for fallback selection")
            return null
        }
        
        val selectedArm = when (fallbackPolicy) {
            FallbackPolicy.RANDOM -> {
                availableArms.random()
            }
            FallbackPolicy.FIRST -> {
                availableArms.first()
            }
            FallbackPolicy.ROUND_ROBIN -> {
                val arm = availableArms[roundRobinIndex % availableArms.size]
                roundRobinIndex++
                arm
            }
            FallbackPolicy.BANDIT_ONLY -> {
                banditArm ?: availableArms.first()
            }
            FallbackPolicy.CONSERVATIVE -> {
                // Select arm with lowest risk (highest average reward, most pulls)
                availableArms.maxByOrNull { 
                    it.averageReward * it.pullCount.toDouble() 
                } ?: availableArms.first()
            }
        }
        
        Log.d(TAG, "Fallback selection: policy=$fallbackPolicy, arm=${selectedArm?.armId}")
        return selectedArm
    }
    
    /**
     * Set fallback policy
     */
    fun setFallbackPolicy(policy: FallbackPolicy) {
        this.fallbackPolicy = policy
        Log.d(TAG, "Fallback policy set to: $policy")
    }
    
    /**
     * Get current fallback policy
     */
    fun getFallbackPolicy(): FallbackPolicy {
        return fallbackPolicy
    }
    
    /**
     * Load baseline config from assets
     * 
     * Baseline config contains default policy parameters for fallback mode
     */
    fun loadBaselineConfig(): BaselineConfig {
        return try {
            // Try to load baseline config from assets
            val configStream = context.assets.open("models/baseline_config.json")
            val configText = configStream.bufferedReader().use { it.readText() }
            configStream.close()
            
            // Parse baseline config - look for policy field
            val policy = when {
                configText.contains("\"policy\"") -> {
                    when {
                        configText.contains("BANDIT_ONLY") -> FallbackPolicy.BANDIT_ONLY
                        configText.contains("RANDOM") -> FallbackPolicy.RANDOM
                        configText.contains("FIRST") -> FallbackPolicy.FIRST
                        configText.contains("ROUND_ROBIN") -> FallbackPolicy.ROUND_ROBIN
                        configText.contains("CONSERVATIVE") -> FallbackPolicy.CONSERVATIVE
                        else -> FallbackPolicy.BANDIT_ONLY
                    }
                }
                else -> FallbackPolicy.BANDIT_ONLY
            }
            
            val description = if (configText.contains("\"description\"")) {
                // Extract description (simplified parsing)
                val descMatch = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(configText)
                descMatch?.groupValues?.get(1) ?: "Baseline policy for model fallback"
            } else {
                "Baseline policy for model fallback"
            }
            
            Log.d(TAG, "Baseline config loaded: policy=$policy")
            BaselineConfig(
                policy = policy,
                description = description
            )
        } catch (e: Exception) {
            // If no baseline config, use default
            Log.d(TAG, "No baseline config found, using defaults: ${e.message}")
            BaselineConfig(
                policy = FallbackPolicy.BANDIT_ONLY,
                description = "Default baseline policy"
            )
        }
    }
    
    /**
     * Baseline configuration
     */
    data class BaselineConfig(
        val policy: FallbackPolicy,
        val description: String
    )
    
    /**
     * Check if fallback mode should be activated
     * 
     * @param modelLoaded Whether model is loaded
     * @param modelValid Whether model verification passed
     * @return True if fallback should be used
     */
    fun shouldUseFallback(modelLoaded: Boolean, modelValid: Boolean): Boolean {
        return !modelLoaded || !modelValid
    }
    
    /**
     * Log fallback activation
     */
    fun logFallbackActivation(reason: String) {
        Log.w(TAG, "========================================")
        Log.w(TAG, "=== FALLBACK MODE ACTIVATED ===")
        Log.w(TAG, "Reason: $reason")
        Log.w(TAG, "Policy: $fallbackPolicy")
        Log.w(TAG, "Using baseline config for model fallback")
        Log.w(TAG, "========================================")
    }
    
    companion object {
        /**
         * Create a default ModelFallbackHandler instance
         */
        fun create(context: Context): ModelFallbackHandler {
            return ModelFallbackHandler(context)
        }
        
        /**
         * Create a ModelFallbackHandler with specific policy
         */
        fun createWithPolicy(
            context: Context,
            policy: FallbackPolicy
        ): ModelFallbackHandler {
            val handler = ModelFallbackHandler(context)
            handler.setFallbackPolicy(policy)
            return handler
        }
    }
}
