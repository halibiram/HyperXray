package com.hyperxray.an.telemetry

import android.content.Context
import android.util.Log
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * DeepTrainer: DQN-lite reinforcement learning trainer.
 * 
 * Implements incremental training with:
 * - Partial parameter updates (update only a subset of weights)
 * - Reward-based gradient computation
 * - Experience replay integration
 * - Reward tracking for incremental tuning
 * - NaN/Inf validation
 * 
 * Note: This is a "lite" implementation that works with the existing ONNX model
 * by maintaining a separate weight cache and computing gradients for incremental updates.
 * Full model retraining would require exporting updated ONNX models.
 */
class DeepTrainer(
    /**
     * Context for accessing model resources
     */
    private val context: Context,
    
    /**
     * Replay buffer for experience storage
     */
    private val replayBuffer: ReplayBuffer = ReplayBuffer(),
    
    /**
     * Learning rate for gradient updates
     */
    private val learningRate: Double = 0.001,
    
    /**
     * Discount factor for future rewards (gamma)
     */
    private val gamma: Double = 0.99,
    
    /**
     * Update fraction: fraction of parameters to update per training step (0.0 to 1.0)
     * 1.0 = full update, 0.1 = update 10% of parameters
     */
    private val updateFraction: Double = 0.1,
    
    /**
     * Batch size for training
     */
    private val batchSize: Int = 32,
    
    /**
     * Minimum buffer size before training starts
     */
    private val minBufferSize: Int = 100
) {
    private val TAG = "DeepTrainer"
    
    /**
     * Weight cache: stores learned weights for incremental updates
     * Format: Map<layer_name, weight_array>
     */
    private val weightCache = mutableMapOf<String, DoubleArray>()
    
    /**
     * Recent rewards for tracking and incremental tuning
     * Maintains last N rewards for statistics
     */
    private val recentRewards = mutableListOf<Double>()
    
    /**
     * Maximum number of recent rewards to track
     */
    private val maxRecentRewards = 1000
    
    /**
     * Training step counter
     */
    private var trainingStep = 0L
    
    /**
     * Total reward accumulated
     */
    private var totalReward = 0.0
    
    /**
     * Thread-safe lock
     */
    private val lock = ReentrantReadWriteLock()
    
    /**
     * Whether training is enabled
     */
    private var trainingEnabled = true
    
    /**
     * Add an experience to the replay buffer and optionally train.
     * 
     * @param experience Experience tuple to add
     * @param trainImmediately If true, trigger training immediately after adding
     */
    fun addExperience(experience: Experience, trainImmediately: Boolean = false) {
        lock.write {
            // Track reward
            trackReward(experience.reward)
            
            // Add to replay buffer
            replayBuffer.add(experience)
            
            // Train if requested and buffer is large enough
            if (trainImmediately && replayBuffer.size() >= minBufferSize) {
                trainStep()
            }
        }
    }
    
    /**
     * Perform a single training step using experience replay.
     * 
     * @param useLocalHistory If true, sample from local history only
     * @return Training loss (or null if training skipped)
     */
    fun trainStep(useLocalHistory: Boolean = false): Double? {
        if (!trainingEnabled) {
            return null
        }
        
        return lock.write {
            val bufferSize = if (useLocalHistory) {
                replayBuffer.localHistorySize()
            } else {
                replayBuffer.size()
            }
            
            if (bufferSize < minBufferSize) {
                Log.d(TAG, "Buffer too small for training: $bufferSize < $minBufferSize")
                return null
            }
            
            // Sample batch
            val batch = replayBuffer.sample(batchSize, useLocalHistory)
            if (batch.isEmpty()) {
                return null
            }
            
            // Compute reward-based gradients and update weights
            val loss = computeGradientsAndUpdate(batch)
            
            trainingStep++
            
            // Validate weights for NaN/Inf
            if (!validateWeights()) {
                Log.e(TAG, "Invalid weights detected after training step, rolling back")
                rollbackWeights()
                return null
            }
            
            Log.d(TAG, "Training step $trainingStep completed, loss: $loss")
            loss
        }
    }
    
    /**
     * Compute gradients from batch and update weights using reward-based gradient.
     * 
     * This implements a simplified DQN update:
     * - Q(s, a) = reward + gamma * max_a' Q(s', a')
     * - Loss = (Q_target - Q_predicted)^2
     * - Gradient = dLoss/dWeights
     * 
     * For partial updates, only update a fraction of parameters.
     */
    private fun computeGradientsAndUpdate(batch: List<Experience>): Double {
        var totalLoss = 0.0
        var validSamples = 0
        
        // Process each experience in batch
        for (experience in batch) {
            try {
                // Compute target Q-value
                val targetQ = computeTargetQ(experience)
                
                // Compute predicted Q-value (simplified: use state-action value)
                val predictedQ = computePredictedQ(experience.state, experience.action)
                
                // Compute TD error
                val tdError = targetQ - predictedQ
                
                // Compute loss (MSE)
                val loss = tdError * tdError
                
                // Validate loss
                if (loss.isNaN() || loss.isInfinite()) {
                    Log.w(TAG, "Invalid loss detected: $loss, skipping experience")
                    continue
                }
                
                totalLoss += loss
                validSamples++
                
                // Compute gradient and update weights (partial update)
                updateWeightsPartial(experience.state, experience.action, tdError)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing experience", e)
                continue
            }
        }
        
        return if (validSamples > 0) {
            totalLoss / validSamples
        } else {
            0.0
        }
    }
    
    /**
     * Compute target Q-value: reward + gamma * max_a' Q(s', a')
     */
    private fun computeTargetQ(experience: Experience): Double {
        val immediateReward = experience.reward
        
        if (experience.done || experience.nextState == null) {
            // Terminal state: Q = reward
            return immediateReward
        }
        
        // Non-terminal: Q = reward + gamma * max Q(s', a')
        // Simplified: estimate max Q(s', a') using current weights
        val maxNextQ = estimateMaxQ(experience.nextState)
        
        val targetQ = immediateReward + gamma * maxNextQ
        
        // Validate
        if (targetQ.isNaN() || targetQ.isInfinite()) {
            Log.w(TAG, "Invalid target Q: $targetQ, using reward only")
            return immediateReward
        }
        
        return targetQ
    }
    
    /**
     * Estimate max Q-value for next state (simplified estimation)
     */
    private fun estimateMaxQ(nextState: DoubleArray): Double {
        // Simplified: use weighted sum of state features as Q estimate
        // In full DQN, this would use the target network
        val weights = getWeightsForState(nextState)
        var qValue = 0.0
        
        for (i in nextState.indices) {
            val weight = weights.getOrNull(i) ?: 0.0
            qValue += nextState[i] * weight
        }
        
        // Clip to reasonable range
        return qValue.coerceIn(-1000.0, 1000.0)
    }
    
    /**
     * Compute predicted Q-value for state-action pair
     */
    private fun computePredictedQ(state: DoubleArray, action: String): Double {
        val weights = getWeightsForState(state)
        var qValue = 0.0
        
        // Add action bias (action-specific weight)
        val actionBias = getActionBias(action)
        
        for (i in state.indices) {
            val weight = weights.getOrNull(i) ?: 0.0
            qValue += state[i] * weight
        }
        
        qValue += actionBias
        
        return qValue.coerceIn(-1000.0, 1000.0)
    }
    
    /**
     * Get weights for state (creates/retrieves weight vector)
     */
    private fun getWeightsForState(state: DoubleArray): DoubleArray {
        val key = "state_weights"
        return weightCache.getOrPut(key) {
            DoubleArray(state.size) { 0.0 }
        }
    }
    
    /**
     * Get action bias (action-specific weight)
     */
    private fun getActionBias(action: String): Double {
        val key = "action_bias_$action"
        val biasArray = weightCache.getOrPut(key) {
            DoubleArray(1) { 0.0 }
        }
        return biasArray[0]
    }
    
    /**
     * Update weights using partial update strategy.
     * Only updates a fraction (updateFraction) of parameters.
     */
    private fun updateWeightsPartial(state: DoubleArray, action: String, tdError: Double) {
        // Get state weights
        val stateWeights = getWeightsForState(state)
        val numParamsToUpdate = max(1, (stateWeights.size * updateFraction).toInt())
        
        // Select random subset of parameters to update
        val indicesToUpdate = (0 until stateWeights.size).shuffled().take(numParamsToUpdate)
        
        // Update selected parameters using gradient descent
        for (idx in indicesToUpdate) {
            // Gradient = -tdError * state[idx] (negative because we minimize loss)
            val gradient = -tdError * state[idx]
            
            // Update weight: w = w - learningRate * gradient
            val newWeight = stateWeights[idx] - learningRate * gradient
            
            // Clip to prevent explosion
            stateWeights[idx] = newWeight.coerceIn(-10.0, 10.0)
        }
        
        // Update action bias (always update)
        val actionBias = getActionBias(action)
        val biasArray = weightCache["action_bias_$action"]!!
        val biasGradient = -tdError
        val newBias = actionBias - learningRate * biasGradient
        biasArray[0] = newBias.coerceIn(-10.0, 10.0)
    }
    
    /**
     * Track reward for statistics
     */
    private fun trackReward(reward: Double) {
        recentRewards.add(reward)
        totalReward += reward
        
        // Trim to max size
        if (recentRewards.size > maxRecentRewards) {
            val removed = recentRewards.removeAt(0)
            totalReward -= removed
        }
    }
    
    /**
     * Validate all weights for NaN/Inf
     */
    private fun validateWeights(): Boolean {
        for ((key, weights) in weightCache) {
            if (weights.any { it.isNaN() || it.isInfinite() }) {
                Log.e(TAG, "Invalid weights detected in cache key: $key")
                return false
            }
        }
        return true
    }
    
    /**
     * Rollback weights to previous state (simplified: reset to zero)
     */
    private fun rollbackWeights() {
        Log.w(TAG, "Rolling back weights to safe state")
        for ((key, _) in weightCache) {
            val size = weightCache[key]!!.size
            weightCache[key] = DoubleArray(size) { 0.0 }
        }
    }
    
    /**
     * Get recent rewards statistics
     */
    fun getRecentRewards(count: Int = 5): List<Double> {
        return lock.read {
            recentRewards.takeLast(count)
        }
    }
    
    /**
     * Get average of recent rewards
     */
    fun getAverageReward(count: Int = 100): Double {
        return lock.read {
            val recent = recentRewards.takeLast(count)
            if (recent.isEmpty()) {
                0.0
            } else {
                recent.average()
            }
        }
    }
    
    /**
     * Get training statistics
     */
    fun getStats(): TrainerStats {
        return lock.read {
            val recent = recentRewards.takeLast(100)
            TrainerStats(
                trainingStep = trainingStep,
                totalReward = totalReward,
                recentRewardCount = recentRewards.size,
                avgRecentReward = if (recent.isNotEmpty()) recent.average() else 0.0,
                minRecentReward = recent.minOrNull() ?: 0.0,
                maxRecentReward = recent.maxOrNull() ?: 0.0,
                bufferSize = replayBuffer.size(),
                weightCacheSize = weightCache.size
            )
        }
    }
    
    /**
     * Print auto-summary with last 5 rewards, average, TODOs, and next stage
     */
    fun printSummary() {
        lock.read {
            val last5Rewards = recentRewards.takeLast(5)
            val avgReward = if (recentRewards.isNotEmpty()) {
                recentRewards.average()
            } else {
                0.0
            }
            
            Log.i(TAG, "========================================")
            Log.i(TAG, "=== DeepTrainer Status Summary ===")
            Log.i(TAG, "========================================")
            Log.i(TAG, "")
            Log.i(TAG, "Recent Rewards (last 5):")
            if (last5Rewards.isEmpty()) {
                Log.i(TAG, "  (no rewards yet)")
            } else {
                last5Rewards.forEachIndexed { idx, reward ->
                    Log.i(TAG, "  [$idx] $reward")
                }
            }
            Log.i(TAG, "")
            Log.i(TAG, "Average Reward: $avgReward")
            Log.i(TAG, "Training Step: $trainingStep")
            Log.i(TAG, "Total Reward: $totalReward")
            Log.i(TAG, "Buffer Size: ${replayBuffer.size()}")
            Log.i(TAG, "")
            Log.i(TAG, "Pending TODOs:")
            Log.i(TAG, "  [ ] Implement full DQN target network")
            Log.i(TAG, "  [ ] Add prioritized experience replay")
            Log.i(TAG, "  [ ] Implement ONNX model weight export")
            Log.i(TAG, "  [ ] Add gradient clipping for stability")
            Log.i(TAG, "  [ ] Implement adaptive learning rate")
            Log.i(TAG, "  [ ] Add model checkpointing")
            Log.i(TAG, "  [ ] Implement distributed training support")
            Log.i(TAG, "")
            Log.i(TAG, "========================================")
            Log.i(TAG, "NEXT-STAGE: Orchestrator Integration")
            Log.i(TAG, "========================================")
            Log.i(TAG, "")
        }
    }
    
    /**
     * Enable or disable training
     */
    fun setTrainingEnabled(enabled: Boolean) {
        lock.write {
            trainingEnabled = enabled
        }
    }
    
    /**
     * Check if training is enabled
     */
    fun isTrainingEnabled(): Boolean {
        return lock.read { trainingEnabled }
    }
    
    /**
     * Clear all training state
     */
    fun reset() {
        lock.write {
            weightCache.clear()
            recentRewards.clear()
            totalReward = 0.0
            trainingStep = 0
        }
    }
}

/**
 * Training statistics
 */
data class TrainerStats(
    val trainingStep: Long,
    val totalReward: Double,
    val recentRewardCount: Int,
    val avgRecentReward: Double,
    val minRecentReward: Double,
    val maxRecentReward: Double,
    val bufferSize: Int,
    val weightCacheSize: Int
)




