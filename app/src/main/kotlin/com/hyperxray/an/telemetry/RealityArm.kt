package com.hyperxray.an.telemetry

import kotlinx.serialization.Serializable

/**
 * Represents an "arm" in a multi-armed bandit algorithm.
 * Each arm corresponds to a Reality server configuration that can be selected.
 */
@Serializable
data class RealityArm(
    /**
     * Unique identifier for this arm
     */
    val armId: String,
    
    /**
     * Reality context associated with this arm
     */
    val context: RealityContext,
    
    /**
     * Number of times this arm has been pulled (selected)
     */
    val pullCount: Int = 0,
    
    /**
     * Cumulative reward for this arm
     */
    val cumulativeReward: Double = 0.0,
    
    /**
     * Average reward for this arm
     */
    val averageReward: Double = 0.0,
    
    /**
     * Last time this arm was pulled
     */
    @Serializable(with = InstantSerializer::class)
    val lastPulledAt: java.time.Instant? = null,
    
    /**
     * Whether this arm is currently active/available
     */
    val isActive: Boolean = true
) {
    /**
     * Update arm statistics after a pull with reward
     */
    fun updateReward(reward: Double, timestamp: java.time.Instant = java.time.Instant.now()): RealityArm {
        val newPullCount = pullCount + 1
        val newCumulativeReward = cumulativeReward + reward
        val newAverageReward = newCumulativeReward / newPullCount
        
        return copy(
            pullCount = newPullCount,
            cumulativeReward = newCumulativeReward,
            averageReward = newAverageReward,
            lastPulledAt = timestamp
        )
    }
    
    /**
     * Calculate upper confidence bound (UCB) for this arm
     * UCB = averageReward + c * sqrt(ln(totalPulls) / pullCount)
     */
    fun calculateUCB(totalPulls: Int, explorationConstant: Double = 1.414): Double {
        if (pullCount == 0) {
            return Double.POSITIVE_INFINITY // Always explore unexplored arms
        }
        if (totalPulls == 0) {
            return averageReward
        }
        
        val explorationTerm = explorationConstant * 
            kotlin.math.sqrt(kotlin.math.ln(totalPulls.toDouble()) / pullCount)
        
        return averageReward + explorationTerm
    }
    
    companion object {
        /**
         * Create a new arm from a Reality context
         */
        fun create(context: RealityContext): RealityArm {
            return RealityArm(
                armId = context.configId,
                context = context,
                pullCount = 0,
                cumulativeReward = 0.0,
                averageReward = 0.0,
                lastPulledAt = null,
                isActive = true
            )
        }
    }
}




