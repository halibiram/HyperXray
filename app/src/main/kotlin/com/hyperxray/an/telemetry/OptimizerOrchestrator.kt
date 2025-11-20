package com.hyperxray.an.telemetry

import android.content.Context
import android.util.Log
import com.hyperxray.an.common.Safeguard
import com.hyperxray.an.viewmodel.CoreStatsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * OptimizerOrchestrator: Fuses Bandit + Deep Model + RL + Safeguard.
 * 
 * Orchestrates the complete optimization cycle:
 * 1. Collect context
 * 2. Select arm (bandit)
 * 3. Infer with deep model
 * 4. Fuse policy
 * 5. Test arm
 * 6. Safeguard check
 * 7. Update rewards
 * 
 * Includes exception handling and rollback on failure.
 */
class OptimizerOrchestrator(
    private val context: Context,
    // AGGRESSIVE MODE: Use aggressive LinUCB with higher exploration (alpha=2.0)
    private val bandit: RealityBandit = RealityBandit(linUCB = LinUCB(alpha = 2.0)), // Increased from 1.0 to 2.0
    private val deepModel: DeepPolicyModel = DeepPolicyModel(context),
    // AGGRESSIVE MODE: Heavily favor Neural Network (0.15 bandit, 0.85 NN) for maximum performance
    private val policyFusion: PolicyFusion = PolicyFusion.aggressive(), // Maximum AI-driven performance
    private val shadowTester: ShadowTester = ShadowTester.createAverage(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val TAG = "OptimizerOrchestrator"
    
    /**
     * Cycle result containing all information about the optimization cycle
     */
    data class CycleResult(
        val cycleNumber: Int,
        val selectedArm: RealityArm?,
        val metrics: TelemetryMetrics?,
        val reward: Double?,
        val safeguardPassed: Boolean,
        val safeguardViolations: List<String>,
        val success: Boolean,
        val error: String?,
        val timestamp: Instant = Instant.now()
    )
    
    /**
     * Context collected from system state
     */
    data class OptimizerContext(
        val coreStats: CoreStatsState?,
        val availableArms: List<RealityArm>,
        val cycleNumber: Int,
        val timestamp: Instant = Instant.now()
    )
    
    /**
     * State snapshot for rollback
     */
    private data class StateSnapshot(
        val armStates: Map<String, RealityArm>,
        val cycleNumber: Int
    )
    
    private var cycleCount: Int = 0
    private val rewardHistory: MutableList<Double> = mutableListOf()
    private var lastSnapshot: StateSnapshot? = null
    
    /**
     * Run a complete optimization cycle.
     * 
     * @param coreStats Current core statistics (optional)
     * @param availableArms List of available arms to select from
     * @return CycleResult with complete cycle information
     */
    suspend fun runOptimizerCycle(
        coreStats: CoreStatsState? = null,
        availableArms: List<RealityArm>
    ): CycleResult = withContext(Dispatchers.Default) {
        ensureActive()
        
        cycleCount++
        val cycleNum = cycleCount
        
        Log.i(TAG, "========================================")
        Log.i(TAG, "=== Starting Optimizer Cycle #$cycleNum ===")
        Log.i(TAG, "========================================")
        
        // Save state snapshot for rollback
        val snapshot = saveStateSnapshot()
        lastSnapshot = snapshot
        
        return@withContext try {
            // Step 1: Collect context
            Log.d(TAG, "[Step 1/7] Collecting context...")
            val optimizerContext = collectContext(coreStats, availableArms, cycleNum)
            Log.d(TAG, "Context collected: ${optimizerContext.availableArms.size} arms available")
            
            // Step 2: Select arm (bandit)
            Log.d(TAG, "[Step 2/7] Selecting arm with bandit...")
            val banditArm = selectArmWithBandit(optimizerContext)
            Log.d(TAG, "Bandit selected arm: ${banditArm?.armId}")
            
            // Step 3: Infer with deep model (passes banditArm for fallback)
            Log.d(TAG, "[Step 3/7] Running deep model inference...")
            val nnArm = inferWithDeepModel(optimizerContext, banditArm)
            Log.d(TAG, "Deep model selected arm: ${nnArm?.armId}")
            
            // Step 4: Fuse policy
            Log.d(TAG, "[Step 4/7] Fusing policies...")
            val fusedArm = fusePolicy(banditArm, nnArm, optimizerContext.availableArms)
            if (fusedArm == null) {
                throw IllegalStateException("Policy fusion returned null - no valid arm selected")
            }
            Log.d(TAG, "Fused policy selected arm: ${fusedArm.armId}")
            
            // Step 5: Test arm
            Log.d(TAG, "[Step 5/7] Testing selected arm...")
            val metrics = testArm(fusedArm)
            Log.d(TAG, "Test metrics: thr=${metrics.throughput}, rtt=${metrics.rttP95}, " +
                    "hs=${metrics.handshakeTime}, loss=${metrics.loss}")
            
            // Step 6: Safeguard check
            Log.d(TAG, "[Step 6/7] Running safeguard check...")
            val safeguardResult = Safeguard.check(metrics)
            val safeguardPassed = safeguardResult.isOk()
            Log.d(TAG, "Safeguard check: ${if (safeguardPassed) "PASSED" else "FAILED"}")
            if (!safeguardPassed) {
                Log.w(TAG, "Safeguard violations: ${safeguardResult.violations.joinToString()}")
            }
            
            // Step 7: Update rewards
            Log.d(TAG, "[Step 7/7] Updating rewards...")
            val reward = calculateReward(metrics)
            updateRewards(fusedArm, reward)
            rewardHistory.add(reward)
            Log.d(TAG, "Reward calculated: $reward")
            
            // Log cycle completion
            Log.i(TAG, "========================================")
            Log.i(TAG, "=== Cycle #$cycleNum COMPLETED ===")
            Log.i(TAG, "Selected arm: ${fusedArm.armId}")
            Log.i(TAG, "Reward: $reward")
            Log.i(TAG, "Safeguard: ${if (safeguardPassed) "PASSED" else "FAILED"}")
            Log.i(TAG, "========================================")
            
            CycleResult(
                cycleNumber = cycleNum,
                selectedArm = fusedArm,
                metrics = metrics,
                reward = reward,
                safeguardPassed = safeguardPassed,
                safeguardViolations = safeguardResult.violations,
                success = true,
                error = null,
                timestamp = Instant.now()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Cycle #$cycleNum FAILED with exception", e)
            
            // Rollback on failure
            rollbackToSnapshot(snapshot)
            
            CycleResult(
                cycleNumber = cycleNum,
                selectedArm = null,
                metrics = null,
                reward = null,
                safeguardPassed = false,
                safeguardViolations = emptyList(),
                success = false,
                error = e.message ?: "Unknown error",
                timestamp = Instant.now()
            )
        }
    }
    
    /**
     * Step 1: Collect context from system state
     */
    private fun collectContext(
        coreStats: CoreStatsState?,
        availableArms: List<RealityArm>,
        cycleNumber: Int
    ): OptimizerContext {
        // Ensure all arms are registered in bandit
        bandit.addArms(availableArms)
        
        return OptimizerContext(
            coreStats = coreStats,
            availableArms = availableArms,
            cycleNumber = cycleNumber
        )
    }
    
    /**
     * Step 2: Select arm using bandit algorithm
     */
    private fun selectArmWithBandit(context: OptimizerContext): RealityArm? {
        return try {
            if (context.availableArms.isEmpty()) {
                Log.w(TAG, "No available arms for bandit selection")
                return null
            }
            bandit.select()
        } catch (e: Exception) {
            Log.e(TAG, "Bandit selection failed", e)
            null
        }
    }
    
    /**
     * Step 3: Infer with deep model
     */
    private fun inferWithDeepModel(
        context: OptimizerContext,
        banditArm: RealityArm?
    ): RealityArm? {
        return try {
            if (context.availableArms.isEmpty()) {
                Log.w(TAG, "No available arms for deep model inference")
                return null
            }
            
            // Extract context vector from first arm (or use aggregated context)
            val contextVector = if (context.availableArms.isNotEmpty()) {
                RealityBandit.extractDefaultContext(context.availableArms.first())
            } else {
                DoubleArray(8) { 0.0 }
            }
            
            // Infer with deep model (includes fallback support via banditArm)
            deepModel.infer(contextVector, context.availableArms, banditArm)
        } catch (e: Exception) {
            Log.e(TAG, "Deep model inference failed", e)
            null
        }
    }
    
    /**
     * Step 4: Fuse bandit and deep model policies
     */
    private fun fusePolicy(
        banditArm: RealityArm?,
        nnArm: RealityArm?,
        availableArms: List<RealityArm>
    ): RealityArm? {
        return try {
            policyFusion.merge(banditArm, nnArm, availableArms)
        } catch (e: Exception) {
            Log.e(TAG, "Policy fusion failed", e)
            // Fallback to bandit arm if available
            banditArm ?: nnArm
        }
    }
    
    /**
     * Step 5: Test the selected arm
     */
    private fun testArm(arm: RealityArm): TelemetryMetrics {
        // Use ShadowTester to generate test metrics
        // In production, this would make actual network calls
        return shadowTester.generateMetrics()
    }
    
    /**
     * Step 6: Safeguard check is done in runOptimizerCycle
     */
    
    /**
     * Step 7: Update rewards in bandit
     */
    private fun updateRewards(arm: RealityArm, reward: Double) {
        try {
            bandit.update(arm.armId, reward)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update rewards", e)
        }
    }
    
    /**
     * Save current state snapshot for rollback
     */
    private fun saveStateSnapshot(): StateSnapshot {
        val armStates = bandit.getAllArms().associateBy { it.armId }
        return StateSnapshot(
            armStates = armStates,
            cycleNumber = cycleCount
        )
    }
    
    /**
     * Rollback to a previous state snapshot
     */
    private fun rollbackToSnapshot(snapshot: StateSnapshot?) {
        if (snapshot == null) {
            Log.w(TAG, "No snapshot available for rollback")
            return
        }
        
        try {
            Log.i(TAG, "Rolling back to snapshot from cycle #${snapshot.cycleNumber}")
            
            // Restore arm states
            bandit.clear()
            snapshot.armStates.values.forEach { arm ->
                bandit.addArm(arm)
            }
            
            // Reset cycle count if needed
            if (snapshot.cycleNumber < cycleCount) {
                cycleCount = snapshot.cycleNumber
            }
            
            Log.i(TAG, "Rollback completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Rollback failed", e)
        }
    }
    
    /**
     * Get reward history for trend analysis
     */
    fun getRewardHistory(): List<Double> {
        return rewardHistory.toList()
    }
    
    /**
     * Get average reward over last N cycles
     */
    fun getAverageReward(lastN: Int = 10): Double? {
        if (rewardHistory.isEmpty()) return null
        val recent = rewardHistory.takeLast(lastN)
        return recent.average()
    }
    
    /**
     * Check if rewards are trending upward
     */
    fun isRewardTrendingUpward(windowSize: Int = 10): Boolean {
        if (rewardHistory.size < windowSize * 2) return false
        
        val recent = rewardHistory.takeLast(windowSize)
        val previous = rewardHistory.dropLast(windowSize).takeLast(windowSize)
        
        val recentAvg = recent.average()
        val previousAvg = previous.average()
        
        return recentAvg > previousAvg
    }
    
    /**
     * Get cycle count
     */
    fun getCycleCount(): Int = cycleCount
    
    /**
     * Get RealityBandit instance (for status reporting)
     */
    fun getBandit(): RealityBandit = bandit
    
    /**
     * Print sample cycle output and summary
     */
    fun printSummary() {
        Log.i(TAG, "========================================")
        Log.i(TAG, "=== OptimizerOrchestrator Summary ===")
        Log.i(TAG, "========================================")
        Log.i(TAG, "Total cycles: $cycleCount")
        Log.i(TAG, "Reward history size: ${rewardHistory.size}")
        
        if (rewardHistory.isNotEmpty()) {
            val avgReward = rewardHistory.average()
            val recentAvg = getAverageReward(10) ?: 0.0
            val trending = isRewardTrendingUpward()
            
            Log.i(TAG, "Average reward (all): $avgReward")
            Log.i(TAG, "Average reward (last 10): $recentAvg")
            Log.i(TAG, "Reward trending upward: $trending")
        }
        
        Log.i(TAG, "Available arms: ${bandit.getAllArms().size}")
        Log.i(TAG, "Bandit selections: ${bandit.getTotalSelections()}")
        Log.i(TAG, "Deep model loaded: ${deepModel.isModelLoaded()}")
        
        // Sample cycle output
        if (rewardHistory.isNotEmpty()) {
            Log.i(TAG, "")
            Log.i(TAG, "Sample cycle output:")
            Log.i(TAG, "  Cycle #${cycleCount}: Reward = ${rewardHistory.last()}")
            if (rewardHistory.size >= 2) {
                Log.i(TAG, "  Cycle #${cycleCount - 1}: Reward = ${rewardHistory[rewardHistory.size - 2]}")
            }
        }
        
        Log.i(TAG, "")
        Log.i(TAG, "TODOs for next step:")
        Log.i(TAG, "  [ ] Integrate with actual network testing (replace ShadowTester)")
        Log.i(TAG, "  [ ] Add persistent storage for reward history")
        Log.i(TAG, "  [ ] Implement adaptive policy fusion weights")
        Log.i(TAG, "  [ ] Add cycle scheduling and rate limiting")
        Log.i(TAG, "  [ ] Implement multi-arm testing (parallel evaluation)")
        Log.i(TAG, "  [ ] Add reward normalization and scaling")
        Log.i(TAG, "  [ ] Implement A/B testing framework")
        Log.i(TAG, "  [ ] Add performance metrics and monitoring")
        
        Log.i(TAG, "")
        Log.i(TAG, "========================================")
        Log.i(TAG, "NEXT-STAGE: Validator & QA")
        Log.i(TAG, "========================================")
        Log.i(TAG, "")
    }
    
    /**
     * Cleanup resources
     */
    fun close() {
        try {
            deepModel.close()
            Log.d(TAG, "OptimizerOrchestrator closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing OptimizerOrchestrator", e)
        }
    }
}

