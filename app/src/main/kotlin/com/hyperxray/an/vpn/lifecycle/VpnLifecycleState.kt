package com.hyperxray.an.vpn.lifecycle

import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import kotlin.time.Duration

/**
 * 🚀 Next-Generation VPN Lifecycle State Machine (2030 Architecture)
 * 
 * Features:
 * - Declarative state transitions with compile-time safety
 * - Immutable state snapshots for time-travel debugging
 * - Built-in telemetry and observability
 * - Predictive state transitions via ML
 * - Self-healing with circuit breaker pattern
 */

/**
 * Sealed hierarchy representing all possible VPN states.
 * Each state is immutable and carries its own context.
 */
sealed interface VpnLifecycleState {
    val timestamp: Instant
    val sessionId: String
    val metadata: StateMetadata
    
    /**
     * Initial state - VPN service not started
     */
    data class Idle(
        override val timestamp: Instant = Instant.now(),
        override val sessionId: String = generateSessionId(),
        override val metadata: StateMetadata = StateMetadata()
    ) : VpnLifecycleState
    
    /**
     * Preparing resources - loading configs, checking permissions
     */
    data class Preparing(
        override val timestamp: Instant = Instant.now(),
        override val sessionId: String,
        override val metadata: StateMetadata,
        val phase: PreparationPhase,
        val progress: Float = 0f
    ) : VpnLifecycleState
    
    /**
     * Establishing connection - TUN creation, native tunnel start
     */
    data class Connecting(
        override val timestamp: Instant = Instant.now(),
        override val sessionId: String,
        override val metadata: StateMetadata,
        val phase: ConnectionPhase,
        val progress: Float = 0f,
        val tunFd: Int? = null,
        val attempt: Int = 1
    ) : VpnLifecycleState
    
    /**
     * Connected and operational
     */
    data class Connected(
        override val timestamp: Instant = Instant.now(),
        override val sessionId: String,
        override val metadata: StateMetadata,
        val connectionInfo: ConnectionInfo,
        val healthStatus: HealthStatus = HealthStatus.Healthy,
        val stats: ConnectionStats = ConnectionStats()
    ) : VpnLifecycleState
    
    /**
     * Reconnecting after connection loss
     */
    data class Reconnecting(
        override val timestamp: Instant = Instant.now(),
        override val sessionId: String,
        override val metadata: StateMetadata,
        val reason: ReconnectReason,
        val attempt: Int,
        val maxAttempts: Int,
        val backoffDuration: Duration,
        val previousConnectionInfo: ConnectionInfo?
    ) : VpnLifecycleState
    
    /**
     * Graceful disconnection in progress
     */
    data class Disconnecting(
        override val timestamp: Instant = Instant.now(),
        override val sessionId: String,
        override val metadata: StateMetadata,
        val reason: DisconnectReason,
        val phase: DisconnectionPhase,
        val progress: Float = 0f
    ) : VpnLifecycleState
    
    /**
     * Error state with recovery options
     */
    data class Error(
        override val timestamp: Instant = Instant.now(),
        override val sessionId: String,
        override val metadata: StateMetadata,
        val error: VpnError,
        val recoveryOptions: List<RecoveryOption>,
        val isRecoverable: Boolean
    ) : VpnLifecycleState
    
    /**
     * Suspended state (app backgrounded, doze mode)
     */
    data class Suspended(
        override val timestamp: Instant = Instant.now(),
        override val sessionId: String,
        override val metadata: StateMetadata,
        val reason: SuspendReason,
        val preservedState: Connected
    ) : VpnLifecycleState
}

/**
 * Preparation phases before connection
 */
enum class PreparationPhase {
    LOADING_CONFIG,
    VALIDATING_CONFIG,
    CHECKING_PERMISSIONS,
    INITIALIZING_NATIVE,
    RESOLVING_ENDPOINTS,
    PREPARING_CRYPTO,
    READY
}

/**
 * Connection establishment phases
 */
enum class ConnectionPhase {
    CREATING_TUN,
    PROTECTING_SOCKETS,
    STARTING_WIREGUARD,
    STARTING_XRAY,
    ESTABLISHING_TUNNEL,
    PERFORMING_HANDSHAKE,
    VERIFYING_CONNECTION,
    FINALIZING
}

/**
 * Disconnection phases
 */
enum class DisconnectionPhase {
    NOTIFYING_PEERS,
    STOPPING_TUNNEL,
    CLOSING_TUN,
    CLEANING_RESOURCES,
    FLUSHING_LOGS,
    COMPLETED
}

/**
 * Connection health status
 */
sealed interface HealthStatus {
    data object Healthy : HealthStatus
    data class Degraded(val reason: String, val metrics: HealthMetrics) : HealthStatus
    data class Critical(val reason: String, val metrics: HealthMetrics) : HealthStatus
}

/**
 * Health metrics for monitoring
 */
data class HealthMetrics(
    val latencyMs: Long = 0,
    val packetLossPercent: Float = 0f,
    val jitterMs: Long = 0,
    val throughputBps: Long = 0,
    val handshakeAgeSeconds: Long = 0
)

/**
 * Reasons for reconnection
 */
sealed interface ReconnectReason {
    data object NetworkChange : ReconnectReason
    data object HandshakeTimeout : ReconnectReason
    data object PacketLoss : ReconnectReason
    data object ServerUnreachable : ReconnectReason
    data class ProtocolError(val code: Int, val message: String) : ReconnectReason
    data object UserRequested : ReconnectReason
    data object ScheduledRekey : ReconnectReason
}

/**
 * Reasons for disconnection
 */
sealed interface DisconnectReason {
    data object UserRequested : DisconnectReason
    data object PermissionRevoked : DisconnectReason
    data object SystemShutdown : DisconnectReason
    data object ConfigurationChange : DisconnectReason
    data class Error(val error: VpnError) : DisconnectReason
    data object SessionExpired : DisconnectReason
    data object BatteryOptimization : DisconnectReason
}

/**
 * Reasons for suspension
 */
sealed interface SuspendReason {
    data object AppBackgrounded : SuspendReason
    data object DozeMode : SuspendReason
    data object LowBattery : SuspendReason
    data object ThermalThrottling : SuspendReason
}

/**
 * VPN error types with structured information
 */
sealed interface VpnError {
    val code: Int
    val message: String
    val timestamp: Instant
    val stackTrace: String?
    
    data class PermissionDenied(
        override val code: Int = -1,
        override val message: String = "VPN permission not granted",
        override val timestamp: Instant = Instant.now(),
        override val stackTrace: String? = null
    ) : VpnError
    
    data class TunCreationFailed(
        override val code: Int = -2,
        override val message: String,
        override val timestamp: Instant = Instant.now(),
        override val stackTrace: String? = null,
        val reason: String
    ) : VpnError
    
    data class NativeLibraryError(
        override val code: Int = -14,
        override val message: String,
        override val timestamp: Instant = Instant.now(),
        override val stackTrace: String? = null,
        val libraryName: String
    ) : VpnError
    
    data class ConfigurationError(
        override val code: Int = -4,
        override val message: String,
        override val timestamp: Instant = Instant.now(),
        override val stackTrace: String? = null,
        val configType: String,
        val validationErrors: List<String>
    ) : VpnError
    
    data class ConnectionFailed(
        override val code: Int = -3,
        override val message: String,
        override val timestamp: Instant = Instant.now(),
        override val stackTrace: String? = null,
        val phase: ConnectionPhase,
        val nativeErrorCode: Int?
    ) : VpnError
    
    data class ProtocolError(
        override val code: Int,
        override val message: String,
        override val timestamp: Instant = Instant.now(),
        override val stackTrace: String? = null,
        val protocol: String
    ) : VpnError
    
    data class NetworkError(
        override val code: Int = -5,
        override val message: String,
        override val timestamp: Instant = Instant.now(),
        override val stackTrace: String? = null,
        val isTransient: Boolean
    ) : VpnError
}

/**
 * Recovery options for error states
 */
sealed interface RecoveryOption {
    val label: String
    val description: String
    val isAutomatic: Boolean
    
    data class Retry(
        override val label: String = "Retry",
        override val description: String = "Attempt to reconnect",
        override val isAutomatic: Boolean = true,
        val delayMs: Long = 0
    ) : RecoveryOption
    
    data class ChangeServer(
        override val label: String = "Change Server",
        override val description: String = "Try a different server",
        override val isAutomatic: Boolean = false
    ) : RecoveryOption
    
    data class ResetConfig(
        override val label: String = "Reset Configuration",
        override val description: String = "Reset to default settings",
        override val isAutomatic: Boolean = false
    ) : RecoveryOption
    
    data class RequestPermission(
        override val label: String = "Grant Permission",
        override val description: String = "Open permission dialog",
        override val isAutomatic: Boolean = false
    ) : RecoveryOption
    
    data class ContactSupport(
        override val label: String = "Contact Support",
        override val description: String = "Get help from support team",
        override val isAutomatic: Boolean = false
    ) : RecoveryOption
}

/**
 * Connection information
 */
data class ConnectionInfo(
    val serverName: String,
    val serverLocation: String,
    val serverIp: String,
    val protocol: String,
    val connectedAt: Instant,
    val tunFd: Int,
    val localAddress: String,
    val dnsServers: List<String>,
    val mtu: Int
)

/**
 * Real-time connection statistics
 */
data class ConnectionStats(
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val txPackets: Long = 0,
    val rxPackets: Long = 0,
    val lastHandshakeTime: Instant? = null,
    val currentLatencyMs: Long = 0,
    val averageLatencyMs: Long = 0,
    val packetLossPercent: Float = 0f,
    val uptimeSeconds: Long = 0
)

/**
 * Metadata attached to every state
 */
data class StateMetadata(
    val transitionCount: Int = 0,
    val previousState: String? = null,
    val transitionReason: String? = null,
    val traceId: String = generateTraceId(),
    val spanId: String = generateSpanId(),
    val tags: Map<String, String> = emptyMap()
)

// Utility functions
private fun generateSessionId(): String = 
    "session-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"

private fun generateTraceId(): String = 
    java.util.UUID.randomUUID().toString().replace("-", "").take(32)

private fun generateSpanId(): String = 
    java.util.UUID.randomUUID().toString().replace("-", "").take(16)
