package com.hyperxray.an.vpn.lifecycle

import kotlin.time.Duration

/**
 * 🚀 Side Effects - Actions triggered by state transitions
 * 
 * Side effects are pure descriptions of actions to be performed.
 * They are executed by registered handlers, keeping the state machine pure.
 */
sealed interface SideEffect {
    val priority: Priority
    val timeout: Duration?
    
    enum class Priority { LOW, NORMAL, HIGH, CRITICAL }
    
    // ===== No-Op =====
    
    data object NoOp : SideEffect {
        override val priority = Priority.LOW
        override val timeout: Duration? = null
    }
    
    // ===== Logging =====
    
    data class EmitLog(
        val message: String,
        val level: LogLevel = LogLevel.INFO,
        val tags: Map<String, String> = emptyMap(),
        override val priority: Priority = Priority.LOW,
        override val timeout: Duration? = null
    ) : SideEffect
    
    // ===== Configuration =====
    
    data class LoadConfiguration(
        val configId: String?,
        override val priority: Priority = Priority.HIGH,
        override val timeout: Duration? = Duration.parse("10s")
    ) : SideEffect
    
    data class ValidateConfiguration(
        val configJson: String,
        override val priority: Priority = Priority.HIGH,
        override val timeout: Duration? = Duration.parse("5s")
    ) : SideEffect
    
    // ===== TUN Interface =====
    
    data object CreateTunInterface : SideEffect {
        override val priority = Priority.CRITICAL
        override val timeout: Duration? = Duration.parse("15s")
    }
    
    data object CloseTun : SideEffect {
        override val priority = Priority.HIGH
        override val timeout: Duration? = Duration.parse("5s")
    }
    
    // ===== Socket Protection =====
    
    data object InitializeSocketProtector : SideEffect {
        override val priority = Priority.CRITICAL
        override val timeout: Duration? = Duration.parse("5s")
    }
    
    data object CleanupSocketProtector : SideEffect {
        override val priority = Priority.HIGH
        override val timeout: Duration? = Duration.parse("3s")
    }
    
    // ===== Native Tunnel =====
    
    data object StartWireGuard : SideEffect {
        override val priority = Priority.CRITICAL
        override val timeout: Duration? = Duration.parse("30s")
    }
    
    data object StartXray : SideEffect {
        override val priority = Priority.CRITICAL
        override val timeout: Duration? = Duration.parse("30s")
    }
    
    data object EstablishTunnel : SideEffect {
        override val priority = Priority.CRITICAL
        override val timeout: Duration? = Duration.parse("60s")
    }
    
    data object PerformHandshake : SideEffect {
        override val priority = Priority.CRITICAL
        override val timeout: Duration? = Duration.parse("30s")
    }
    
    data object VerifyConnection : SideEffect {
        override val priority = Priority.HIGH
        override val timeout: Duration? = Duration.parse("10s")
    }
    
    data object FinalizeConnection : SideEffect {
        override val priority = Priority.NORMAL
        override val timeout: Duration? = Duration.parse("5s")
    }
    
    data object StopTunnel : SideEffect {
        override val priority = Priority.CRITICAL
        override val timeout: Duration? = Duration.parse("10s")
    }
    
    data class CleanupFailedConnection(
        val tunFd: Int?,
        override val priority: Priority = Priority.HIGH,
        override val timeout: Duration? = Duration.parse("5s")
    ) : SideEffect
    
    // ===== Monitoring =====
    
    data object StartStatsMonitoring : SideEffect {
        override val priority = Priority.NORMAL
        override val timeout: Duration? = null
    }
    
    data object StopStatsMonitoring : SideEffect {
        override val priority = Priority.NORMAL
        override val timeout: Duration? = Duration.parse("2s")
    }
    
    data object StartHealthMonitoring : SideEffect {
        override val priority = Priority.NORMAL
        override val timeout: Duration? = null
    }
    
    data object StopHealthMonitoring : SideEffect {
        override val priority = Priority.NORMAL
        override val timeout: Duration? = Duration.parse("2s")
    }
    
    // ===== Notifications =====
    
    data class NotifyConnected(
        val connectionInfo: ConnectionInfo,
        override val priority: Priority = Priority.NORMAL,
        override val timeout: Duration? = Duration.parse("5s")
    ) : SideEffect
    
    data class NotifyDisconnected(
        val reason: DisconnectReason,
        override val priority: Priority = Priority.NORMAL,
        override val timeout: Duration? = Duration.parse("5s")
    ) : SideEffect
    
    data object NotifyPeers : SideEffect {
        override val priority = Priority.NORMAL
        override val timeout: Duration? = Duration.parse("3s")
    }
    
    // ===== Reconnection =====
    
    data object PrepareReconnect : SideEffect {
        override val priority = Priority.HIGH
        override val timeout: Duration? = Duration.parse("5s")
    }
    
    data class ScheduleReconnect(
        val delay: Duration,
        override val priority: Priority = Priority.NORMAL,
        override val timeout: Duration? = null
    ) : SideEffect
    
    data class TriggerReconnect(
        val reason: ReconnectReason,
        override val priority: Priority = Priority.HIGH,
        override val timeout: Duration? = null
    ) : SideEffect
    
    // ===== Disconnection =====
    
    data object InitiateGracefulDisconnect : SideEffect {
        override val priority = Priority.HIGH
        override val timeout: Duration? = Duration.parse("10s")
    }
    
    data object ForceDisconnect : SideEffect {
        override val priority = Priority.CRITICAL
        override val timeout: Duration? = Duration.parse("3s")
    }
    
    // ===== Cleanup =====
    
    data object CleanupResources : SideEffect {
        override val priority = Priority.HIGH
        override val timeout: Duration? = Duration.parse("10s")
    }
    
    data object ForceCleanup : SideEffect {
        override val priority = Priority.CRITICAL
        override val timeout: Duration? = Duration.parse("3s")
    }
    
    data object FlushLogs : SideEffect {
        override val priority = Priority.LOW
        override val timeout: Duration? = Duration.parse("5s")
    }
    
    // ===== Suspension =====
    
    data object SuspendConnection : SideEffect {
        override val priority = Priority.HIGH
        override val timeout: Duration? = Duration.parse("5s")
    }
    
    data object ResumeConnection : SideEffect {
        override val priority = Priority.HIGH
        override val timeout: Duration? = Duration.parse("10s")
    }
    
    // ===== Telemetry =====
    
    data class EmitTelemetry(
        val event: TelemetryEvent,
        override val priority: Priority = Priority.LOW,
        override val timeout: Duration? = null
    ) : SideEffect
}

/**
 * Log levels
 */
enum class LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR, FATAL
}

/**
 * Side effect handler interface
 */
fun interface SideEffectHandler {
    suspend fun handle(effect: SideEffect)
}

/**
 * Composite side effect handler that delegates to specific handlers
 */
class CompositeSideEffectHandler : SideEffectHandler {
    private val handlers = mutableMapOf<Class<out SideEffect>, SideEffectHandler>()
    
    fun <T : SideEffect> register(effectClass: Class<T>, handler: SideEffectHandler) {
        handlers[effectClass] = handler
    }
    
    inline fun <reified T : SideEffect> register(handler: SideEffectHandler) {
        register(T::class.java, handler)
    }
    
    override suspend fun handle(effect: SideEffect) {
        val handler = handlers[effect::class.java]
        handler?.handle(effect)
    }
}
