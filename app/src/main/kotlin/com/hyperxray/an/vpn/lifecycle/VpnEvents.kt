package com.hyperxray.an.vpn.lifecycle

import java.time.Instant

/**
 * 🚀 VPN Events - Immutable event definitions for state machine
 * 
 * All events are immutable data classes that carry the necessary
 * information for state transitions.
 */
sealed interface VpnEvent {
    val timestamp: Instant
    val correlationId: String
    
    // ===== Lifecycle Events =====
    
    data class StartRequested(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val configId: String? = null,
        val source: EventSource = EventSource.USER
    ) : VpnEvent
    
    data class CancelRequested(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val source: EventSource = EventSource.USER
    ) : VpnEvent
    
    data class DisconnectRequested(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val reason: DisconnectReason = DisconnectReason.UserRequested,
        val source: EventSource = EventSource.USER
    ) : VpnEvent
    
    data class RetryRequested(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val source: EventSource = EventSource.USER
    ) : VpnEvent
    
    data class DismissError(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId()
    ) : VpnEvent
    
    // ===== Preparation Events =====
    
    data class PreparationPhaseCompleted(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val phase: PreparationPhase,
        val result: PhaseResult = PhaseResult.Success
    ) : VpnEvent
    
    data class PreparationFailed(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val error: VpnError,
        val phase: PreparationPhase
    ) : VpnEvent
    
    // ===== Connection Events =====
    
    data class TunCreated(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val tunFd: Int,
        val localAddress: String,
        val mtu: Int
    ) : VpnEvent
    
    data class ConnectionPhaseCompleted(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val phase: ConnectionPhase,
        val connectionInfo: ConnectionInfo? = null
    ) : VpnEvent
    
    data class ConnectionFailed(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val error: VpnError,
        val phase: ConnectionPhase
    ) : VpnEvent
    
    data class ConnectionLost(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val reason: ReconnectReason
    ) : VpnEvent
    
    // ===== Reconnection Events =====
    
    data class ReconnectAttemptStarted(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val attempt: Int
    ) : VpnEvent
    
    data class ReconnectFailed(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val attempt: Int,
        val error: VpnError
    ) : VpnEvent
    
    // ===== Disconnection Events =====
    
    data class DisconnectionPhaseCompleted(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val phase: DisconnectionPhase
    ) : VpnEvent
    
    data class ForceDisconnect(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val reason: String
    ) : VpnEvent
    
    // ===== Runtime Events =====
    
    data class StatsUpdated(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val stats: ConnectionStats
    ) : VpnEvent
    
    data class HealthStatusChanged(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val status: HealthStatus,
        val previousStatus: HealthStatus? = null
    ) : VpnEvent
    
    data class PermissionRevoked(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId()
    ) : VpnEvent
    
    // ===== Suspension Events =====
    
    data class SuspendRequested(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val reason: SuspendReason
    ) : VpnEvent
    
    data class ResumeRequested(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId()
    ) : VpnEvent
    
    // ===== System Events =====
    
    data class NetworkChanged(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val networkType: NetworkType,
        val isConnected: Boolean
    ) : VpnEvent
    
    data class BatteryLow(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val batteryPercent: Int
    ) : VpnEvent
    
    data class ThermalThrottling(
        override val timestamp: Instant = Instant.now(),
        override val correlationId: String = generateCorrelationId(),
        val thermalStatus: Int
    ) : VpnEvent
}

/**
 * Event source - where the event originated
 */
enum class EventSource {
    USER,           // User action (button press, etc.)
    SYSTEM,         // Android system (permission revoked, etc.)
    NETWORK,        // Network change
    HEALTH_MONITOR, // Health monitoring system
    SCHEDULER,      // Scheduled task
    RECOVERY,       // Auto-recovery system
    NATIVE          // Native code callback
}

/**
 * Network type
 */
enum class NetworkType {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    UNKNOWN,
    NONE
}

/**
 * Phase result
 */
sealed interface PhaseResult {
    data object Success : PhaseResult
    data class Warning(val message: String) : PhaseResult
    data class Skipped(val reason: String) : PhaseResult
}

// Utility
private fun generateCorrelationId(): String = 
    "evt-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"
