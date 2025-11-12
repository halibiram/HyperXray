package com.hyperxray.an.runtime

import android.util.Log
import kotlin.random.Random

/**
 * BanditRouter: Epsilon-greedy multi-armed bandit for routing decisions.
 * 
 * Learns from feedback to optimize routing decisions over time.
 * Uses epsilon-greedy exploration-exploitation strategy.
 */
class BanditRouter(
    private val epsilon: Float = 0.08f,
    private val learningRate: Float = 0.1f
) {
    private val TAG = "BanditRouter"
    
    // Reward estimates for each routing decision (0=proxy, 1=direct, 2=optimized)
    private val rewards = mutableMapOf<Int, Float>(
        0 to 0.5f, // proxy
        1 to 0.5f, // direct
        2 to 0.5f  // optimized
    )
    
    // Count of times each arm was pulled
    private val counts = mutableMapOf<Int, Int>(
        0 to 0,
        1 to 0,
        2 to 0
    )
    
    /**
     * Select routing decision using epsilon-greedy strategy.
     * 
     * @param serviceType The detected service type (0-7)
     * @return Routing decision index (0=proxy, 1=direct, 2=optimized)
     */
    fun selectRoute(serviceType: Int): Int {
        val shouldExplore = Random.nextFloat() < epsilon
        
        return if (shouldExplore) {
            // Explore: random choice
            val randomRoute = Random.nextInt(3)
            Log.d(TAG, "Exploring: selected route=$randomRoute (epsilon=$epsilon)")
            randomRoute
        } else {
            // Exploit: choose best known route
            val bestRoute = rewards.maxByOrNull { it.value }?.key ?: 0
            Log.d(TAG, "Exploiting: selected route=$bestRoute (reward=${rewards[bestRoute]})")
            bestRoute
        }
    }
    
    /**
     * Update reward estimate based on feedback.
     * 
     * @param route The routing decision that was used
     * @param reward The reward signal (0.0 to 1.0, higher is better)
     */
    fun updateReward(route: Int, reward: Float) {
        synchronized(rewards) {
            val currentReward = rewards[route] ?: 0.5f
            val count = counts[route] ?: 0
            
            // Exponential moving average update
            val newReward = currentReward + learningRate * (reward - currentReward)
            rewards[route] = newReward.coerceIn(0f, 1f)
            counts[route] = count + 1
            
            Log.d(TAG, "Updated route=$route: reward=$currentReward -> $newReward (count=${count + 1})")
        }
    }
    
    /**
     * Compute reward from network metrics.
     * 
     * @param latencyMs Latency in milliseconds (lower is better)
     * @param throughputKbps Throughput in kbps (higher is better)
     * @param success Whether the connection was successful
     * @return Reward value (0.0 to 1.0)
     */
    fun computeReward(
        latencyMs: Float,
        throughputKbps: Float,
        success: Boolean
    ): Float {
        if (!success) {
            return 0.0f // Failed connections get zero reward
        }
        
        // Normalize latency (assume 0-2000ms range, lower is better)
        val latencyScore = (1.0f - (latencyMs / 2000f).coerceIn(0f, 1f))
        
        // Normalize throughput (assume 0-10000 kbps range, higher is better)
        val throughputScore = (throughputKbps / 10000f).coerceIn(0f, 1f)
        
        // Weighted combination (60% latency, 40% throughput)
        val reward = 0.6f * latencyScore + 0.4f * throughputScore
        
        return reward.coerceIn(0f, 1f)
    }
    
    /**
     * Get current reward estimates for all routes.
     */
    fun getRewards(): Map<Int, Float> {
        synchronized(rewards) {
            return rewards.toMap()
        }
    }
    
    /**
     * Get pull counts for all routes.
     */
    fun getCounts(): Map<Int, Int> {
        synchronized(counts) {
            return counts.toMap()
        }
    }
    
    /**
     * Reset all rewards and counts (for testing or reset).
     */
    fun reset() {
        synchronized(rewards) {
            rewards[0] = 0.5f
            rewards[1] = 0.5f
            rewards[2] = 0.5f
            counts[0] = 0
            counts[1] = 0
            counts[2] = 0
        }
        Log.i(TAG, "BanditRouter reset")
    }
}

