package com.hyperxray.an.core.network.dns

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "SocketMetrics"

/**
 * Thread-safe singleton for tracking socket statistics and health metrics.
 * Provides real-time visibility into socket pool performance and connection health.
 */
object SocketMetrics {
    // Active socket pool size
    private val activeSocketCount = AtomicInteger(0)
    
    // Lifetime statistics
    private val socketsCreated = AtomicLong(0)
    private val socketsDestroyed = AtomicLong(0)
    
    // Health and error metrics
    private val healthCheckFailures = AtomicInteger(0)
    private val packetErrors = AtomicInteger(0)
    private val retryAttempts = AtomicInteger(0)
    
    /**
     * Increment active socket count (when socket is added to pool)
     */
    fun incrementActiveSocketCount() {
        activeSocketCount.incrementAndGet()
        socketsCreated.incrementAndGet()
    }
    
    /**
     * Decrement active socket count (when socket is removed from pool)
     */
    fun decrementActiveSocketCount() {
        activeSocketCount.decrementAndGet()
        socketsDestroyed.incrementAndGet()
    }
    
    /**
     * Record a health check failure (socket discarded due to failing health check)
     */
    fun recordHealthCheckFailure() {
        healthCheckFailures.incrementAndGet()
    }
    
    /**
     * Record a packet error (send/receive failure)
     */
    fun recordPacketError() {
        packetErrors.incrementAndGet()
    }
    
    /**
     * Record a retry attempt
     */
    fun recordRetryAttempt() {
        retryAttempts.incrementAndGet()
    }
    
    /**
     * Get current active socket count
     */
    fun getActiveSocketCount(): Int = activeSocketCount.get()
    
    /**
     * Get total sockets created (lifetime)
     */
    fun getSocketsCreated(): Long = socketsCreated.get()
    
    /**
     * Get total sockets destroyed (lifetime)
     */
    fun getSocketsDestroyed(): Long = socketsDestroyed.get()
    
    /**
     * Get total health check failures
     */
    fun getHealthCheckFailures(): Int = healthCheckFailures.get()
    
    /**
     * Get total packet errors
     */
    fun getPacketErrors(): Int = packetErrors.get()
    
    /**
     * Get total retry attempts
     */
    fun getRetryAttempts(): Int = retryAttempts.get()
    
    /**
     * Get formatted snapshot of all metrics as JSON-like string
     */
    fun getSnapshot(): String {
        val snapshot = buildString {
            appendLine("{")
            appendLine("  \"activeSocketCount\": ${activeSocketCount.get()},")
            appendLine("  \"socketsCreated\": ${socketsCreated.get()},")
            appendLine("  \"socketsDestroyed\": ${socketsDestroyed.get()},")
            appendLine("  \"healthCheckFailures\": ${healthCheckFailures.get()},")
            appendLine("  \"packetErrors\": ${packetErrors.get()},")
            appendLine("  \"retryAttempts\": ${retryAttempts.get()},")
            
            // Calculate derived metrics
            val created = socketsCreated.get()
            val destroyed = socketsDestroyed.get()
            val netCreated = created - destroyed
            val healthFailureRate = if (created > 0) {
                String.format("%.2f", (healthCheckFailures.get().toDouble() / created) * 100)
            } else {
                "0.00"
            }
            val errorRate = if (created > 0) {
                String.format("%.2f", (packetErrors.get().toDouble() / created) * 100)
            } else {
                "0.00"
            }
            
            appendLine("  \"netSocketsCreated\": $netCreated,")
            appendLine("  \"healthFailureRate\": \"$healthFailureRate%\",")
            appendLine("  \"errorRate\": \"$errorRate%\"")
            appendLine("}")
        }
        return snapshot
    }
    
    /**
     * Log metrics snapshot
     */
    fun logSnapshot() {
        Log.i(TAG, "📊 Socket Metrics Snapshot:\n${getSnapshot()}")
    }
    
    /**
     * Reset all metrics (for testing or periodic reset)
     */
    fun reset() {
        activeSocketCount.set(0)
        socketsCreated.set(0)
        socketsDestroyed.set(0)
        healthCheckFailures.set(0)
        packetErrors.set(0)
        retryAttempts.set(0)
        Log.d(TAG, "Socket metrics reset")
    }
}

