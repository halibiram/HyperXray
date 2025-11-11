package com.hyperxray.an.telemetry

import android.util.Log
import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Experience tuple for reinforcement learning.
 * Stores (state, action, reward, next_state, done) transitions.
 */
data class Experience(
    /**
     * State vector (context features)
     */
    val state: DoubleArray,
    
    /**
     * Action taken (armId or action index)
     */
    val action: String,
    
    /**
     * Reward received
     */
    val reward: Double,
    
    /**
     * Next state vector (null if terminal)
     */
    val nextState: DoubleArray?,
    
    /**
     * Whether this is a terminal state
     */
    val done: Boolean,
    
    /**
     * Timestamp of the experience
     */
    val timestamp: Instant = Instant.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as Experience
        
        if (!state.contentEquals(other.state)) return false
        if (action != other.action) return false
        if (reward != other.reward) return false
        if (nextState != null) {
            if (other.nextState == null || !nextState.contentEquals(other.nextState)) return false
        } else if (other.nextState != null) return false
        if (done != other.done) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = state.contentHashCode()
        result = 31 * result + action.hashCode()
        result = 31 * result + reward.hashCode()
        result = 31 * result + (nextState?.contentHashCode() ?: 0)
        result = 31 * result + done.hashCode()
        return result
    }
}

/**
 * ReplayBuffer: Experience replay buffer for DQN training.
 * 
 * Stores experience tuples (state, action, reward, next_state, done) in a ring buffer.
 * Supports optional local history tracking for incremental updates.
 * 
 * Features:
 * - Fixed capacity ring buffer (overwrites oldest on overflow)
 * - Thread-safe operations
 * - Batch sampling for training
 * - Optional local history window for recent experiences
 */
class ReplayBuffer(
    /**
     * Maximum capacity of the buffer
     */
    private val capacity: Int = 10000,
    
    /**
     * Size of local history window (0 = disabled)
     * When > 0, maintains a separate recent history buffer
     */
    private val localHistorySize: Int = 100
) {
    private val TAG = "ReplayBuffer"
    
    /**
     * Main experience buffer (ring buffer)
     */
    private val buffer = Array<Experience?>(capacity) { null }
    
    /**
     * Local history buffer (recent experiences, optional)
     */
    private val localHistory = if (localHistorySize > 0) {
        Array<Experience?>(localHistorySize) { null }
    } else {
        null
    }
    
    /**
     * Write index for main buffer
     */
    private var writeIndex = 0
    
    /**
     * Write index for local history
     */
    private var localHistoryIndex = 0
    
    /**
     * Current size of main buffer
     */
    private var size = 0
    
    /**
     * Current size of local history
     */
    private var localHistoryCount = 0
    
    /**
     * Thread-safe lock for concurrent access
     */
    private val lock = ReentrantReadWriteLock()
    
    /**
     * Total number of experiences added (including overwrites)
     */
    private var totalAdded = 0L
    
    /**
     * Add a new experience to the buffer.
     * 
     * @param experience Experience tuple to add
     */
    fun add(experience: Experience) {
        lock.write {
            // Validate experience
            if (!validateExperience(experience)) {
                Log.w(TAG, "Invalid experience rejected: reward=${experience.reward}, state size=${experience.state.size}")
                return
            }
            
            // Add to main buffer
            buffer[writeIndex] = experience
            writeIndex = (writeIndex + 1) % capacity
            if (size < capacity) {
                size++
            }
            
            // Add to local history if enabled
            localHistory?.let { history ->
                history[localHistoryIndex] = experience
                localHistoryIndex = (localHistoryIndex + 1) % localHistorySize
                if (localHistoryCount < localHistorySize) {
                    localHistoryCount++
                }
            }
            
            totalAdded++
        }
    }
    
    /**
     * Add multiple experiences at once
     */
    fun addAll(experiences: List<Experience>) {
        lock.write {
            experiences.forEach { experience ->
                if (validateExperience(experience)) {
                    buffer[writeIndex] = experience
                    writeIndex = (writeIndex + 1) % capacity
                    if (size < capacity) {
                        size++
                    }
                    
                    localHistory?.let { history ->
                        history[localHistoryIndex] = experience
                        localHistoryIndex = (localHistoryIndex + 1) % localHistorySize
                        if (localHistoryCount < localHistorySize) {
                            localHistoryCount++
                        }
                    }
                    
                    totalAdded++
                }
            }
        }
    }
    
    /**
     * Sample a batch of experiences for training.
     * 
     * @param batchSize Number of experiences to sample
     * @param useLocalHistory If true and local history enabled, sample from local history only
     * @return List of sampled experiences
     */
    fun sample(batchSize: Int, useLocalHistory: Boolean = false): List<Experience> {
        return lock.read {
            val sourceSize = if (useLocalHistory && localHistory != null) {
                localHistoryCount
            } else {
                size
            }
            
            if (sourceSize == 0) {
                return emptyList()
            }
            
            val actualBatchSize = batchSize.coerceAtMost(sourceSize)
            val source = if (useLocalHistory && localHistory != null) {
                getLocalHistoryList()
            } else {
                getAllList()
            }
            
            // Random sampling without replacement
            val sampled = mutableListOf<Experience>()
            val indices = (0 until sourceSize).shuffled().take(actualBatchSize)
            
            indices.forEach { idx ->
                source.getOrNull(idx)?.let { sampled.add(it) }
            }
            
            sampled
        }
    }
    
    /**
     * Get all experiences in chronological order (oldest first)
     */
    fun getAll(): List<Experience> {
        return lock.read {
            getAllList()
        }
    }
    
    /**
     * Get local history (recent experiences) if enabled
     */
    fun getLocalHistory(): List<Experience> {
        return lock.read {
            if (localHistory == null) {
                return emptyList()
            }
            getLocalHistoryList()
        }
    }
    
    /**
     * Get current size of the buffer
     */
    fun size(): Int {
        return lock.read { size }
    }
    
    /**
     * Get local history size
     */
    fun localHistorySize(): Int {
        return lock.read { localHistoryCount }
    }
    
    /**
     * Check if buffer is empty
     */
    fun isEmpty(): Boolean {
        return lock.read { size == 0 }
    }
    
    /**
     * Check if buffer is full
     */
    fun isFull(): Boolean {
        return lock.read { size == capacity }
    }
    
    /**
     * Clear all experiences
     */
    fun clear() {
        lock.write {
            for (i in buffer.indices) {
                buffer[i] = null
            }
            localHistory?.let { history ->
                for (i in history.indices) {
                    history[i] = null
                }
            }
            writeIndex = 0
            localHistoryIndex = 0
            size = 0
            localHistoryCount = 0
            totalAdded = 0
        }
    }
    
    /**
     * Get total number of experiences added (including overwrites)
     */
    fun getTotalAdded(): Long {
        return lock.read { totalAdded }
    }
    
    /**
     * Get statistics about the buffer
     */
    fun getStats(): ReplayBufferStats {
        return lock.read {
            val allExperiences = getAllList()
            val rewards = allExperiences.map { it.reward }
            
            ReplayBufferStats(
                size = size,
                capacity = capacity,
                localHistorySize = localHistoryCount,
                localHistoryCapacity = localHistorySize,
                totalAdded = totalAdded,
                avgReward = if (rewards.isNotEmpty()) rewards.average() else 0.0,
                minReward = rewards.minOrNull() ?: 0.0,
                maxReward = rewards.maxOrNull() ?: 0.0
            )
        }
    }
    
    /**
     * Validate experience for NaN/Inf values
     */
    private fun validateExperience(experience: Experience): Boolean {
        // Check state
        if (experience.state.any { it.isNaN() || it.isInfinite() }) {
            return false
        }
        
        // Check reward
        if (experience.reward.isNaN() || experience.reward.isInfinite()) {
            return false
        }
        
        // Check next state if present
        experience.nextState?.let { nextState ->
            if (nextState.any { it.isNaN() || it.isInfinite() }) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * Get all experiences as list (internal, assumes lock held)
     */
    private fun getAllList(): List<Experience> {
        if (size == 0) {
            return emptyList()
        }
        
        val result = mutableListOf<Experience>()
        
        if (size < capacity) {
            // Buffer not full, read from start
            for (i in 0 until size) {
                buffer[i]?.let { result.add(it) }
            }
        } else {
            // Buffer full, read from writeIndex (oldest) to writeIndex-1 (newest)
            for (i in 0 until capacity) {
                val index = (writeIndex + i) % capacity
                buffer[index]?.let { result.add(it) }
            }
        }
        
        return result
    }
    
    /**
     * Get local history as list (internal, assumes lock held)
     */
    private fun getLocalHistoryList(): List<Experience> {
        if (localHistory == null || localHistoryCount == 0) {
            return emptyList()
        }
        
        val result = mutableListOf<Experience>()
        
        if (localHistoryCount < localHistorySize) {
            // History not full, read from start
            for (i in 0 until localHistoryCount) {
                localHistory[i]?.let { result.add(it) }
            }
        } else {
            // History full, read from localHistoryIndex (oldest) to localHistoryIndex-1 (newest)
            for (i in 0 until localHistorySize) {
                val index = (localHistoryIndex + i) % localHistorySize
                localHistory[index]?.let { result.add(it) }
            }
        }
        
        return result
    }
}

/**
 * Statistics about the replay buffer
 */
data class ReplayBufferStats(
    val size: Int,
    val capacity: Int,
    val localHistorySize: Int,
    val localHistoryCapacity: Int,
    val totalAdded: Long,
    val avgReward: Double,
    val minReward: Double,
    val maxReward: Double
)



