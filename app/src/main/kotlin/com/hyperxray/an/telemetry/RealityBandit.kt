package com.hyperxray.an.telemetry

import kotlin.random.Random

/**
 * RealityBandit: Multi-armed bandit for Reality server selection using LinUCB.
 * 
 * Integrates LinUCB algorithm with RealityArm objects to select optimal servers
 * based on contextual features (8D context vectors) and observed rewards.
 */
class RealityBandit(
    /**
     * LinUCB algorithm instance
     */
    private val linUCB: LinUCB = LinUCB(),
    
    /**
     * Function to extract 8D context vector from RealityArm
     * Default implementation uses arm features
     */
    private val contextExtractor: (RealityArm) -> DoubleArray = { arm ->
        extractDefaultContext(arm)
    }
) {
    /**
     * Map of armId -> RealityArm
     */
    private val arms: MutableMap<String, RealityArm> = mutableMapOf()
    
    /**
     * Total number of selections made
     */
    private var totalSelections: Int = 0
    
    /**
     * Add or update an arm in the bandit
     */
    fun addArm(arm: RealityArm) {
        arms[arm.armId] = arm
        if (!linUCB.isArmInitialized(arm.armId)) {
            linUCB.initializeArm(arm.armId)
        }
    }
    
    /**
     * Add multiple arms
     */
    fun addArms(armsList: List<RealityArm>) {
        armsList.forEach { addArm(it) }
    }
    
    /**
     * Select the best arm using LinUCB algorithm
     * Returns the RealityArm with highest UCB value
     */
    fun select(): RealityArm {
        if (arms.isEmpty()) {
            throw IllegalStateException("No arms available for selection")
        }
        
        // Filter to active arms only
        val activeArms = arms.values.filter { it.isActive }
        if (activeArms.isEmpty()) {
            throw IllegalStateException("No active arms available")
        }
        
        // Compute UCB for each active arm
        var bestArm: RealityArm? = null
        var bestUCB = Double.NEGATIVE_INFINITY
        
        for (arm in activeArms) {
            val context = contextExtractor(arm)
            val ucb = linUCB.computeUCB(arm.armId, context)
            
            if (ucb > bestUCB) {
                bestUCB = ucb
                bestArm = arm
            }
        }
        
        require(bestArm != null) { "Failed to select arm" }
        
        totalSelections++
        return bestArm
    }
    
    /**
     * Update bandit with observed reward for a selected arm
     * 
     * @param armId ID of the arm that was selected
     * @param reward Observed reward value (Double)
     */
    fun update(armId: String, reward: Double) {
        val arm = arms[armId] ?: throw IllegalArgumentException("Arm $armId not found")
        
        // Extract context from arm
        val context = contextExtractor(arm)
        
        // Update LinUCB with context and reward
        linUCB.update(armId, context, reward)
        
        // Update arm statistics
        val updatedArm = arm.updateReward(reward)
        arms[armId] = updatedArm
    }
    
    /**
     * Update bandit with observed reward for a selected arm (using RealityArm)
     */
    fun update(arm: RealityArm, reward: Double) {
        update(arm.armId, reward)
    }
    
    /**
     * Get current arm state
     */
    fun getArm(armId: String): RealityArm? {
        return arms[armId]
    }
    
    /**
     * Get all arms
     */
    fun getAllArms(): List<RealityArm> {
        return arms.values.toList()
    }
    
    /**
     * Get total number of selections
     */
    fun getTotalSelections(): Int {
        return totalSelections
    }
    
    /**
     * Get learned weights (theta vectors) for all arms
     */
    fun getLearnedWeights(): Map<String, DoubleArray> {
        return linUCB.getAllThetas()
    }
    
    /**
     * Get learned weights for a specific arm
     */
    fun getLearnedWeights(armId: String): DoubleArray? {
        return linUCB.getTheta(armId)
    }
    
    /**
     * Remove an arm from the bandit
     */
    fun removeArm(armId: String) {
        arms.remove(armId)
    }
    
    /**
     * Clear all arms
     */
    fun clear() {
        arms.clear()
        totalSelections = 0
    }
    
    companion object {
        /**
         * Default context extraction: converts RealityArm to 8D context vector
         * Uses normalized features from the arm's context
         */
        fun extractDefaultContext(arm: RealityArm): DoubleArray {
            val ctx = arm.context
            
            // Extract 8 features and normalize to [0, 1] range
            // Feature 0: Normalized port (0-65535 -> 0-1)
            val portNorm = ctx.port / 65535.0
            
            // Feature 1: Address hash (normalized)
            val addressHash = ctx.address.hashCode().toDouble()
            val addressNorm = (addressHash % 10000) / 10000.0
            
            // Feature 2: Server name hash (normalized)
            val serverNameHash = ctx.serverName.hashCode().toDouble()
            val serverNameNorm = (serverNameHash % 10000) / 10000.0
            
            // Feature 3: Short ID hash (normalized)
            val shortIdHash = ctx.shortId.hashCode().toDouble()
            val shortIdNorm = (shortIdHash % 10000) / 10000.0
            
            // Feature 4: Public key length (normalized, assuming max 200 chars)
            val publicKeyNorm = (ctx.publicKey.length / 200.0).coerceIn(0.0, 1.0)
            
            // Feature 5: Destination hash (normalized)
            val destHash = ctx.destination.hashCode().toDouble()
            val destNorm = (destHash % 10000) / 10000.0
            
            // Feature 6: Pull count (normalized, assuming max 10000 pulls)
            val pullCountNorm = (arm.pullCount / 10000.0).coerceIn(0.0, 1.0)
            
            // Feature 7: Average reward (normalized, assuming range [-100, 100])
            val avgRewardNorm = ((arm.averageReward + 100.0) / 200.0).coerceIn(0.0, 1.0)
            
            return doubleArrayOf(
                portNorm,
                addressNorm,
                serverNameNorm,
                shortIdNorm,
                publicKeyNorm,
                destNorm,
                pullCountNorm,
                avgRewardNorm
            )
        }
    }
}

