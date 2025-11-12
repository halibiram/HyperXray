package com.hyperxray.an.optimizer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OnDeviceLearner: Implements exponential moving average (EMA) updates for biases.
 * 
 * Updates temperature and biases based on feedback from traffic performance.
 */
class OnDeviceLearner(
    private val context: Context,
    private val learningRate: Float = 0.01f
) {
    private val TAG = "OnDeviceLearner"
    private val learnerState = LearnerState(context)
    
    /**
     * Update learner state with feedback using EMA.
     * 
     * Formula: bias += α * (target - bias)
     *          temperature = clamp(T ± Δ)
     * 
     * @param success Whether the connection was successful
     * @param throughputKbps Throughput in kbps
     * @param svcIdx Service type index (0-7)
     * @param routeIdx Routing decision index (0-2)
     */
    suspend fun updateWithFeedback(
        success: Boolean,
        throughputKbps: Float,
        svcIdx: Int,
        routeIdx: Int
    ) = withContext(Dispatchers.Default) {
        try {
            // Compute target reward signal (0.0 to 1.0)
            val target = computeReward(success, throughputKbps)
            
            // EMA update for service type bias: bias += α * (target - bias)
            if (svcIdx in 0..7) {
                val currentBias = learnerState.getSvcBiases()[svcIdx]
                val newBias = currentBias + learningRate * (target - currentBias)
                learnerState.updateSvcBias(svcIdx, newBias.coerceIn(-1f, 1f))
            }
            
            // EMA update for routing decision bias
            if (routeIdx in 0..2) {
                val currentBias = learnerState.getRouteBiases()[routeIdx]
                val newBias = currentBias + learningRate * (target - currentBias)
                learnerState.updateRouteBias(routeIdx, newBias.coerceIn(-1f, 1f))
            }
            
            // Update temperature (adaptive confidence)
            // Higher throughput/success -> lower temperature (more confident)
            val currentTemp = learnerState.getTemperature()
            val tempDelta = if (success && throughputKbps > 1000f) {
                -learningRate * 0.1f // Decrease temperature (more confident)
            } else if (!success || throughputKbps < 100f) {
                learningRate * 0.1f // Increase temperature (less confident)
            } else {
                0f // No change
            }
            val newTemp = (currentTemp + tempDelta).coerceIn(0.5f, 2.0f) // clamp
            learnerState.updateTemperature(newTemp)
            
            Log.d(TAG, "EMA update: success=$success, tput=${throughputKbps}kbps, " +
                    "svc=$svcIdx, route=$routeIdx, target=$target")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating with feedback: ${e.message}", e)
        }
    }
    
    /**
     * Compute reward signal from feedback.
     * 
     * @param success Connection success
     * @param throughputKbps Throughput in kbps
     * @return Reward value (0.0 to 1.0)
     */
    private fun computeReward(success: Boolean, throughputKbps: Float): Float {
        if (!success) {
            return 0.0f // Failed connections get zero reward
        }
        
        // Normalize throughput (assume 0-10000 kbps range)
        val throughputScore = (throughputKbps / 10000f).coerceIn(0f, 1f)
        
        // Reward is based on throughput (higher is better)
        return throughputScore
    }
    
    /**
     * Get current learner state.
     */
    fun getState(): LearnerState = learnerState
    
    /**
     * Reset learner to default state.
     */
    fun reset() {
        learnerState.reset()
        Log.i(TAG, "Learner reset to default state")
    }
}

