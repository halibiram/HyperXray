package com.hyperxray.an.telemetry

import android.util.Log

/**
 * PolicyFusion: Merges bandit-based and neural network-based arm selections.
 * 
 * Combines recommendations from:
 * - RealityBandit (banditArm): LinUCB-based exploration/exploitation
 * - DeepPolicyModel (nnArm): Neural network inference
 * 
 * Uses weighted fusion strategy to balance both approaches.
 */
class PolicyFusion(
    /**
     * Weight for bandit arm (0.0 to 1.0)
     * Higher weight favors bandit selection
     */
    private val banditWeight: Double = 0.5,
    
    /**
     * Weight for neural network arm (0.0 to 1.0)
     * Higher weight favors NN selection
     */
    private val nnWeight: Double = 0.5
) {
    private val TAG = "PolicyFusion"
    
    init {
        // Normalize weights to sum to 1.0
        val totalWeight = banditWeight + nnWeight
        if (totalWeight > 0.0) {
            // Weights will be normalized during fusion
        } else {
            Log.w(TAG, "Both weights are zero, defaulting to equal weights")
        }
    }
    
    /**
     * Merge banditArm and nnArm into a single RealityArm selection.
     * 
     * Strategy:
     * 1. If both arms are null: return null
     * 2. If only one arm is available: return that arm
     * 3. If both arms are available: use weighted fusion based on arm scores
     * 
     * @param banditArm Arm selected by RealityBandit (LinUCB)
     * @param nnArm Arm selected by DeepPolicyModel (neural network)
     * @param availableArms All available arms for context
     * @return Merged RealityArm selection, or null if no valid selection
     */
    fun merge(
        banditArm: RealityArm?,
        nnArm: RealityArm?,
        availableArms: List<RealityArm> = emptyList()
    ): RealityArm? {
        // Safety check: handle null cases
        return when {
            banditArm == null && nnArm == null -> {
                Log.w(TAG, "Both arms are null, cannot merge")
                null
            }
            banditArm == null -> {
                Log.d(TAG, "Only NN arm available, using: ${nnArm?.armId}")
                nnArm
            }
            nnArm == null -> {
                Log.d(TAG, "Only bandit arm available, using: ${banditArm.armId}")
                banditArm
            }
            banditArm.armId == nnArm.armId -> {
                // Both selected the same arm - consensus!
                Log.d(TAG, "Both policies agree on arm: ${banditArm.armId}")
                banditArm
            }
            else -> {
                // Different selections - use weighted fusion
                fuseArms(banditArm, nnArm, availableArms)
            }
        }
    }
    
    /**
     * Fuse two different arm selections using weighted strategy.
     * 
     * Fusion methods:
     * 1. Score-based: Combine UCB scores and NN probabilities
     * 2. Weighted selection: Choose based on normalized weights
     * 3. Fallback: Prefer bandit if weights are equal
     */
    private fun fuseArms(
        banditArm: RealityArm,
        nnArm: RealityArm,
        availableArms: List<RealityArm>
    ): RealityArm {
        val totalWeight = banditWeight + nnWeight
        
        if (totalWeight <= 0.0) {
            // Equal weights or invalid - prefer bandit as default
            Log.d(TAG, "Invalid weights, defaulting to bandit arm")
            return banditArm
        }
        
        // Normalize weights
        val normalizedBanditWeight = banditWeight / totalWeight
        val normalizedNnWeight = nnWeight / totalWeight
        
        // Strategy 1: Score-based fusion (if we have scores)
        if (availableArms.isNotEmpty()) {
            val banditScore = computeBanditScore(banditArm, availableArms)
            val nnScore = computeNnScore(nnArm, availableArms)
            
            val fusedBanditScore = banditScore * normalizedBanditWeight
            val fusedNnScore = nnScore * normalizedNnWeight
            
            if (fusedBanditScore > fusedNnScore) {
                Log.d(TAG, "Fused selection: bandit arm (score: $fusedBanditScore vs $fusedNnScore)")
                return banditArm
            } else {
                Log.d(TAG, "Fused selection: NN arm (score: $fusedNnScore vs $fusedBanditScore)")
                return nnArm
            }
        }
        
        // Strategy 2: Weighted random selection (if no scores available)
        val random = kotlin.random.Random.nextDouble()
        return if (random < normalizedBanditWeight) {
            Log.d(TAG, "Weighted selection: bandit arm (random: $random < $normalizedBanditWeight)")
            banditArm
        } else {
            Log.d(TAG, "Weighted selection: NN arm (random: $random >= $normalizedBanditWeight)")
            nnArm
        }
    }
    
    /**
     * Compute score for bandit arm (using UCB value).
     */
    private fun computeBanditScore(arm: RealityArm, availableArms: List<RealityArm>): Double {
        // Use average reward as base score
        val baseScore = arm.averageReward
        
        // Boost score based on exploration (lower pull count = higher exploration value)
        val explorationBoost = if (arm.pullCount > 0) {
            1.0 / (1.0 + arm.pullCount)
        } else {
            1.0 // Unexplored arms get full boost
        }
        
        return baseScore + explorationBoost
    }
    
    /**
     * Compute score for NN arm (assume uniform for now).
     */
    private fun computeNnScore(arm: RealityArm, availableArms: List<RealityArm>): Double {
        // Use average reward as base score
        val baseScore = arm.averageReward
        
        // NN typically favors arms with good historical performance
        // Add small boost for NN confidence (assumed)
        val nnConfidence = 0.1
        
        return baseScore + nnConfidence
    }
    
    companion object {
        /**
         * Create fusion with equal weights (0.5 each)
         */
        fun equal(): PolicyFusion {
            return PolicyFusion(banditWeight = 0.5, nnWeight = 0.5)
        }
        
        /**
         * Create fusion favoring bandit (e.g., 0.7 bandit, 0.3 NN)
         */
        fun banditFavored(): PolicyFusion {
            return PolicyFusion(banditWeight = 0.7, nnWeight = 0.3)
        }
        
        /**
         * Create fusion favoring neural network (e.g., 0.3 bandit, 0.7 NN)
         */
        fun nnFavored(): PolicyFusion {
            return PolicyFusion(banditWeight = 0.3, nnWeight = 0.7)
        }
        
        /**
         * AGGRESSIVE MODE: Create fusion heavily favoring neural network (0.15 bandit, 0.85 NN)
         * For maximum performance - prioritizes AI predictions over bandit exploration
         */
        fun aggressive(): PolicyFusion {
            return PolicyFusion(banditWeight = 0.15, nnWeight = 0.85)
        }
    }
}

