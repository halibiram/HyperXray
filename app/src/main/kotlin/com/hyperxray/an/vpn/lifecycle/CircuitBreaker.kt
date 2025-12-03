package com.hyperxray.an.vpn.lifecycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 🚀 Circuit Breaker Pattern (2030 Architecture)
 * 
 * Prevents cascade failures by tracking failure rates and
 * temporarily blocking operations when failure threshold is exceeded.
 * 
 * States:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Failures exceeded threshold, requests blocked
 * - HALF_OPEN: Testing if system recovered, limited requests allowed
 */
class CircuitBreaker(
    private val config: CircuitBreakerConfig = CircuitBreakerConfig()
) {
    
    private val _state = MutableStateFlow(CircuitState.CLOSED)
    val state: StateFlow<CircuitState> = _state.asStateFlow()
    
    private val failureCount = AtomicInteger(0)
    private val successCount = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0)
    private val lastStateChangeTime = AtomicLong(System.currentTimeMillis())
    private val halfOpenAttempts = AtomicInteger(0)
    
    // Metrics
    private val _metrics = MutableStateFlow(CircuitMetrics())
    val metrics: StateFlow<CircuitMetrics> = _metrics.asStateFlow()
    
    /**
     * Check if a retry should be allowed
     */
    fun shouldAllowRetry(): Boolean {
        return when (_state.value) {
            CircuitState.CLOSED -> true
            CircuitState.OPEN -> {
                // Check if enough time has passed to try half-open
                val timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime.get()
                if (timeSinceLastFailure >= config.openDuration.inWholeMilliseconds) {
                    transitionTo(CircuitState.HALF_OPEN)
                    true
                } else {
                    false
                }
            }
            CircuitState.HALF_OPEN -> {
                // Allow limited attempts in half-open state
                halfOpenAttempts.get() < config.halfOpenMaxAttempts
            }
        }
    }
    
    /**
     * Record a successful operation
     */
    fun recordSuccess() {
        successCount.incrementAndGet()
        
        when (_state.value) {
            CircuitState.HALF_OPEN -> {
                val attempts = halfOpenAttempts.incrementAndGet()
                if (attempts >= config.halfOpenSuccessThreshold) {
                    // Enough successes in half-open, close the circuit
                    reset()
                }
            }
            CircuitState.CLOSED -> {
                // Reset failure count on success
                failureCount.set(0)
            }
            CircuitState.OPEN -> {
                // Shouldn't happen, but handle gracefully
            }
        }
        
        updateMetrics()
    }
    
    /**
     * Record a failed operation
     */
    fun recordFailure() {
        val failures = failureCount.incrementAndGet()
        lastFailureTime.set(System.currentTimeMillis())
        
        when (_state.value) {
            CircuitState.CLOSED -> {
                if (failures >= config.failureThreshold) {
                    transitionTo(CircuitState.OPEN)
                }
            }
            CircuitState.HALF_OPEN -> {
                // Any failure in half-open reopens the circuit
                transitionTo(CircuitState.OPEN)
            }
            CircuitState.OPEN -> {
                // Already open, just update metrics
            }
        }
        
        updateMetrics()
    }
    
    /**
     * Reset the circuit breaker to closed state
     */
    fun reset() {
        failureCount.set(0)
        successCount.set(0)
        halfOpenAttempts.set(0)
        transitionTo(CircuitState.CLOSED)
        updateMetrics()
    }
    
    /**
     * Force the circuit to open state (for testing or manual intervention)
     */
    fun forceOpen() {
        transitionTo(CircuitState.OPEN)
        updateMetrics()
    }
    
    private fun transitionTo(newState: CircuitState) {
        val oldState = _state.value
        if (oldState != newState) {
            _state.value = newState
            lastStateChangeTime.set(System.currentTimeMillis())
            
            when (newState) {
                CircuitState.HALF_OPEN -> halfOpenAttempts.set(0)
                CircuitState.CLOSED -> {
                    failureCount.set(0)
                    halfOpenAttempts.set(0)
                }
                CircuitState.OPEN -> { /* Keep failure count */ }
            }
        }
    }
    
    private fun updateMetrics() {
        _metrics.value = CircuitMetrics(
            state = _state.value,
            failureCount = failureCount.get(),
            successCount = successCount.get(),
            lastFailureTime = if (lastFailureTime.get() > 0) Instant.ofEpochMilli(lastFailureTime.get()) else null,
            lastStateChangeTime = Instant.ofEpochMilli(lastStateChangeTime.get()),
            failureRate = calculateFailureRate()
        )
    }
    
    private fun calculateFailureRate(): Float {
        val total = failureCount.get() + successCount.get()
        return if (total > 0) failureCount.get().toFloat() / total else 0f
    }
}

/**
 * Circuit breaker states
 */
enum class CircuitState {
    CLOSED,     // Normal operation
    OPEN,       // Blocking requests
    HALF_OPEN   // Testing recovery
}

/**
 * Circuit breaker configuration
 */
data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,
    val openDuration: Duration = 30.seconds,
    val halfOpenMaxAttempts: Int = 3,
    val halfOpenSuccessThreshold: Int = 2,
    val slidingWindowSize: Int = 10,
    val minimumThroughput: Int = 5
)

/**
 * Circuit breaker metrics
 */
data class CircuitMetrics(
    val state: CircuitState = CircuitState.CLOSED,
    val failureCount: Int = 0,
    val successCount: Int = 0,
    val lastFailureTime: Instant? = null,
    val lastStateChangeTime: Instant = Instant.now(),
    val failureRate: Float = 0f
)

/**
 * State machine configuration
 */
data class StateMachineConfig(
    val maxConnectionAttempts: Int = 3,
    val maxReconnectAttempts: Int = 5,
    val baseBackoffMs: Long = 1000,
    val maxBackoffMs: Long = 60000,
    val maxHistorySize: Int = 100,
    val circuitBreakerConfig: CircuitBreakerConfig = CircuitBreakerConfig()
)

/**
 * State snapshot for time-travel debugging
 */
data class StateSnapshot(
    val state: VpnLifecycleState,
    val timestamp: Instant
)

/**
 * State transition result
 */
data class StateTransition(
    val newState: VpnLifecycleState,
    val preSideEffects: List<SideEffect> = emptyList(),
    val postSideEffects: List<SideEffect> = emptyList()
)

/**
 * Transition validation result
 */
data class ValidationResult(
    val isValid: Boolean,
    val reason: String = ""
)

/**
 * Transition validator interface
 */
fun interface TransitionValidator {
    suspend fun validate(
        from: VpnLifecycleState,
        to: VpnLifecycleState,
        event: VpnEvent
    ): ValidationResult
}
