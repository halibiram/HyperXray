package com.hyperxray.an.common

import android.util.Log
import java.util.UUID

/**
 * RolloutGuard manages safe configuration rollout strategies.
 * Supports shadow testing, A/B testing, and enforced rollout modes.
 *
 * Modes:
 * - SHADOW: Test configuration without affecting production traffic
 * - AB: A/B test with configurable split (default 10%)
 * - ENFORCE: Enforce configuration for all traffic
 */
object RolloutGuard {
    private const val TAG = "RolloutGuard"
    
    /**
     * Rollout mode
     */
    enum class RolloutMode {
        SHADOW,    // Shadow testing - no production impact
        AB,        // A/B testing - split traffic
        ENFORCE    // Enforce - all traffic uses new config
    }
    
    /**
     * Rollout configuration
     */
    data class RolloutConfig(
        val mode: RolloutMode,
        val abSplitPercent: Int = 10,  // Percentage of traffic for A/B testing (0-100)
        val userId: String = UUID.randomUUID().toString()  // Stable user ID for consistent assignment
    ) {
        init {
            require(abSplitPercent in 0..100) { "AB split must be between 0 and 100" }
        }
    }
    
    /**
     * Rollout decision result
     */
    data class RolloutDecision(
        val useNewConfig: Boolean,
        val reason: String,
        val mode: RolloutMode,
        val userId: String
    )
    
    /**
     * Determines if a user should use the new configuration based on rollout strategy.
     * 
     * @param config Rollout configuration
     * @return RolloutDecision indicating whether to use new config
     */
    fun shouldUseNewConfig(config: RolloutConfig): RolloutDecision {
        return when (config.mode) {
            RolloutMode.SHADOW -> {
                // Shadow mode: never use new config for production traffic
                RolloutDecision(
                    useNewConfig = false,
                    reason = "Shadow mode: new config tested without production impact",
                    mode = config.mode,
                    userId = config.userId
                )
            }
            
            RolloutMode.AB -> {
                // A/B testing: use hash of user ID to deterministically assign
                val userHash = config.userId.hashCode()
                val bucket = Math.abs(userHash % 100)
                val useNew = bucket < config.abSplitPercent
                
                RolloutDecision(
                    useNewConfig = useNew,
                    reason = if (useNew) {
                        "A/B test: user in treatment group (bucket $bucket < ${config.abSplitPercent}%)"
                    } else {
                        "A/B test: user in control group (bucket $bucket >= ${config.abSplitPercent}%)"
                    },
                    mode = config.mode,
                    userId = config.userId
                )
            }
            
            RolloutMode.ENFORCE -> {
                // Enforce mode: always use new config
                RolloutDecision(
                    useNewConfig = true,
                    reason = "Enforce mode: all traffic uses new configuration",
                    mode = config.mode,
                    userId = config.userId
                )
            }
        }
    }
    
    /**
     * Creates a default A/B test configuration with 10% split.
     */
    fun createAbTestConfig(userId: String = UUID.randomUUID().toString()): RolloutConfig {
        return RolloutConfig(
            mode = RolloutMode.AB,
            abSplitPercent = 10,
            userId = userId
        )
    }
    
    /**
     * Creates a shadow test configuration.
     */
    fun createShadowConfig(): RolloutConfig {
        return RolloutConfig(
            mode = RolloutMode.SHADOW,
            abSplitPercent = 0,
            userId = UUID.randomUUID().toString()
        )
    }
    
    /**
     * Creates an enforce configuration.
     */
    fun createEnforceConfig(): RolloutConfig {
        return RolloutConfig(
            mode = RolloutMode.ENFORCE,
            abSplitPercent = 100,
            userId = UUID.randomUUID().toString()
        )
    }
    
    /**
     * Logs rollout decision for monitoring and debugging.
     */
    fun logDecision(decision: RolloutDecision) {
        Log.d(TAG, "Rollout decision: useNewConfig=${decision.useNewConfig}, mode=${decision.mode}, reason=${decision.reason}")
    }
}




