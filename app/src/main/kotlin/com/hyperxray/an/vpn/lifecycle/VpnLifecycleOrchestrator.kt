package com.hyperxray.an.vpn.lifecycle

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * 🚀 VPN Lifecycle Orchestrator (2030 Architecture)
 * 
 * Central coordinator that bridges the declarative state machine
 * with actual VPN operations. Implements side effect handlers
 * and manages the complete VPN lifecycle.
 * 
 * Features:
 * - Declarative state management
 * - Automatic recovery with circuit breaker
 * - Predictive health monitoring
 * - Full observability via telemetry
 * - Zero-downtime reconnection
 */
class VpnLifecycleOrchestrator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val nativeBridge: NativeTunnelBridge,
    private val configProvider: ConfigProvider,
    private val config: OrchestratorConfig = OrchestratorConfig()
) {
    
    // State machine
    private val stateMachine = VpnStateMachine(scope, config.stateMachineConfig)
    
    // Telemetry
    private val telemetryCollector = TelemetryCollector(scope, config.telemetryConfig)
    
    // Health monitor (initialized when connected)
    private var healthMonitor: HealthMonitor? = null
    
    // Current TUN file descriptor
    private var currentTunFd: ParcelFileDescriptor? = null
    private val tunFdLock = Any()
    
    // Public state flow
    val state: StateFlow<VpnLifecycleState> = stateMachine.state
    val telemetry: SharedFlow<TelemetryEvent> = stateMachine.telemetry
    val metrics: StateFlow<AggregatedMetrics> = telemetryCollector.aggregatedMetrics
    
    init {
        // Register side effect handlers
        registerSideEffectHandlers()
        
        // Forward telemetry events to collector
        scope.launch {
            stateMachine.telemetry.collect { event ->
                telemetryCollector.record(event)
            }
        }
        
        // Monitor state changes for logging
        scope.launch {
            stateMachine.state.collect { state ->
                logStateChange(state)
            }
        }
    }
    
    // ===== Public API =====
    
    /**
     * Start VPN connection
     */
    suspend fun start(configId: String? = null) {
        stateMachine.dispatch(VpnEvent.StartRequested(configId = configId))
    }
    
    /**
     * Stop VPN connection
     */
    suspend fun stop(reason: DisconnectReason = DisconnectReason.UserRequested) {
        stateMachine.dispatch(VpnEvent.DisconnectRequested(reason = reason))
    }
    
    /**
     * Cancel current operation
     */
    suspend fun cancel() {
        stateMachine.dispatch(VpnEvent.CancelRequested())
    }
    
    /**
     * Retry after error
     */
    suspend fun retry() {
        stateMachine.dispatch(VpnEvent.RetryRequested())
    }
    
    /**
     * Dismiss error and return to idle
     */
    suspend fun dismissError() {
        stateMachine.dispatch(VpnEvent.DismissError())
    }
    
    /**
     * Get current connection stats
     */
    fun getConnectionStats(): ConnectionStats? {
        val currentState = state.value
        return if (currentState is VpnLifecycleState.Connected) {
            currentState.stats
        } else null
    }
    
    /**
     * Get health status
     */
    fun getHealthStatus(): HealthStatus? {
        val currentState = state.value
        return if (currentState is VpnLifecycleState.Connected) {
            currentState.healthStatus
        } else null
    }
    
    // ===== Side Effect Handlers =====
    
    private fun registerSideEffectHandlers() {
        val handlers = CompositeSideEffectHandler()
        
        // Configuration loading
        handlers.register<SideEffect.LoadConfiguration> { effect ->
            handleLoadConfiguration(effect as SideEffect.LoadConfiguration)
        }
        
        // TUN interface
        handlers.register<SideEffect.CreateTunInterface> {
            handleCreateTunInterface()
        }
        
        handlers.register<SideEffect.CloseTun> {
            handleCloseTun()
        }
        
        // Socket protection
        handlers.register<SideEffect.InitializeSocketProtector> {
            handleInitializeSocketProtector()
        }
        
        // Tunnel operations
        handlers.register<SideEffect.StartWireGuard> {
            handleStartWireGuard()
        }
        
        handlers.register<SideEffect.StartXray> {
            handleStartXray()
        }
        
        handlers.register<SideEffect.EstablishTunnel> {
            handleEstablishTunnel()
        }
        
        handlers.register<SideEffect.PerformHandshake> {
            handlePerformHandshake()
        }
        
        handlers.register<SideEffect.VerifyConnection> {
            handleVerifyConnection()
        }
        
        handlers.register<SideEffect.FinalizeConnection> {
            handleFinalizeConnection()
        }
        
        handlers.register<SideEffect.StopTunnel> {
            handleStopTunnel()
        }
        
        // Monitoring
        handlers.register<SideEffect.StartStatsMonitoring> {
            handleStartStatsMonitoring()
        }
        
        handlers.register<SideEffect.StartHealthMonitoring> {
            handleStartHealthMonitoring()
        }
        
        handlers.register<SideEffect.StopStatsMonitoring> {
            handleStopStatsMonitoring()
        }
        
        handlers.register<SideEffect.StopHealthMonitoring> {
            handleStopHealthMonitoring()
        }
        
        // Notifications
        handlers.register<SideEffect.NotifyConnected> { effect ->
            handleNotifyConnected(effect as SideEffect.NotifyConnected)
        }
        
        handlers.register<SideEffect.NotifyDisconnected> { effect ->
            handleNotifyDisconnected(effect as SideEffect.NotifyDisconnected)
        }
        
        // Cleanup
        handlers.register<SideEffect.CleanupResources> {
            handleCleanupResources()
        }
        
        handlers.register<SideEffect.CleanupFailedConnection> { effect ->
            handleCleanupFailedConnection(effect as SideEffect.CleanupFailedConnection)
        }
        
        // Reconnection
        handlers.register<SideEffect.PrepareReconnect> {
            handlePrepareReconnect()
        }
        
        handlers.register<SideEffect.ScheduleReconnect> { effect ->
            handleScheduleReconnect(effect as SideEffect.ScheduleReconnect)
        }
        
        // Register composite handler
        stateMachine.registerSideEffectHandler("Composite", handlers)
    }
    
    // ===== Handler Implementations =====
    
    private suspend fun handleLoadConfiguration(effect: SideEffect.LoadConfiguration) {
        try {
            val configs = configProvider.loadConfigs(effect.configId)
            
            // Validate configs
            if (!configProvider.validateWireGuardConfig(configs.wireGuardConfig)) {
                stateMachine.dispatch(VpnEvent.PreparationFailed(
                    error = VpnError.ConfigurationError(
                        message = "Invalid WireGuard configuration",
                        configType = "WireGuard",
                        validationErrors = listOf("Configuration validation failed")
                    ),
                    phase = PreparationPhase.VALIDATING_CONFIG
                ))
                return
            }
            
            if (!configProvider.validateXrayConfig(configs.xrayConfig)) {
                stateMachine.dispatch(VpnEvent.PreparationFailed(
                    error = VpnError.ConfigurationError(
                        message = "Invalid Xray configuration",
                        configType = "Xray",
                        validationErrors = listOf("Configuration validation failed")
                    ),
                    phase = PreparationPhase.VALIDATING_CONFIG
                ))
                return
            }
            
            // Progress through preparation phases
            for (phase in PreparationPhase.entries) {
                if (phase == PreparationPhase.READY) break
                stateMachine.dispatch(VpnEvent.PreparationPhaseCompleted(phase = phase))
                delay(50) // Small delay for UI feedback
            }
            
            // Signal ready
            stateMachine.dispatch(VpnEvent.PreparationPhaseCompleted(phase = PreparationPhase.PREPARING_CRYPTO))
            
        } catch (e: Exception) {
            stateMachine.dispatch(VpnEvent.PreparationFailed(
                error = VpnError.ConfigurationError(
                    message = "Failed to load configuration: ${e.message}",
                    configType = "Unknown",
                    validationErrors = listOf(e.message ?: "Unknown error"),
                    stackTrace = e.stackTraceToString()
                ),
                phase = PreparationPhase.LOADING_CONFIG
            ))
        }
    }
    
    private suspend fun handleCreateTunInterface() {
        try {
            // This would be called from VpnService context
            // For now, emit event that TUN needs to be created
            // The actual VpnService.Builder.establish() call happens in the service
            
            val tunFd = nativeBridge.createTunInterface()
            
            synchronized(tunFdLock) {
                currentTunFd = tunFd
            }
            
            stateMachine.dispatch(VpnEvent.TunCreated(
                tunFd = tunFd.fd,
                localAddress = "10.0.0.2",
                mtu = 1420
            ))
            
        } catch (e: Exception) {
            stateMachine.dispatch(VpnEvent.ConnectionFailed(
                error = VpnError.TunCreationFailed(
                    message = "Failed to create TUN interface: ${e.message}",
                    reason = e.message ?: "Unknown",
                    stackTrace = e.stackTraceToString()
                ),
                phase = ConnectionPhase.CREATING_TUN
            ))
        }
    }
    
    private suspend fun handleCloseTun() {
        synchronized(tunFdLock) {
            try {
                currentTunFd?.close()
            } catch (e: Exception) {
                // Ignore close errors
            } finally {
                currentTunFd = null
            }
        }
        
        stateMachine.dispatch(VpnEvent.DisconnectionPhaseCompleted(
            phase = DisconnectionPhase.CLOSING_TUN
        ))
    }
    
    private suspend fun handleInitializeSocketProtector() {
        try {
            nativeBridge.initializeSocketProtector()
            
            stateMachine.dispatch(VpnEvent.ConnectionPhaseCompleted(
                phase = ConnectionPhase.PROTECTING_SOCKETS
            ))
            
        } catch (e: Exception) {
            stateMachine.dispatch(VpnEvent.ConnectionFailed(
                error = VpnError.ConnectionFailed(
                    message = "Socket protector initialization failed: ${e.message}",
                    phase = ConnectionPhase.PROTECTING_SOCKETS,
                    nativeErrorCode = -35,
                    stackTrace = e.stackTraceToString()
                ),
                phase = ConnectionPhase.PROTECTING_SOCKETS
            ))
        }
    }
    
    private suspend fun handleStartWireGuard() {
        stateMachine.dispatch(VpnEvent.ConnectionPhaseCompleted(
            phase = ConnectionPhase.STARTING_WIREGUARD
        ))
    }
    
    private suspend fun handleStartXray() {
        stateMachine.dispatch(VpnEvent.ConnectionPhaseCompleted(
            phase = ConnectionPhase.STARTING_XRAY
        ))
    }
    
    private suspend fun handleEstablishTunnel() {
        try {
            val tunFd = synchronized(tunFdLock) { currentTunFd }
                ?: throw IllegalStateException("TUN fd is null")
            
            val configs = configProvider.getCurrentConfigs()
            
            val result = nativeBridge.startTunnel(
                tunFd = tunFd.fd,
                wgConfig = configs.wireGuardConfig,
                xrayConfig = configs.xrayConfig
            )
            
            if (result == 0) {
                stateMachine.dispatch(VpnEvent.ConnectionPhaseCompleted(
                    phase = ConnectionPhase.ESTABLISHING_TUNNEL
                ))
            } else {
                throw RuntimeException("Native tunnel start failed with code: $result")
            }
            
        } catch (e: Exception) {
            stateMachine.dispatch(VpnEvent.ConnectionFailed(
                error = VpnError.ConnectionFailed(
                    message = "Tunnel establishment failed: ${e.message}",
                    phase = ConnectionPhase.ESTABLISHING_TUNNEL,
                    nativeErrorCode = -2,
                    stackTrace = e.stackTraceToString()
                ),
                phase = ConnectionPhase.ESTABLISHING_TUNNEL
            ))
        }
    }
    
    private suspend fun handlePerformHandshake() {
        try {
            val rtt = nativeBridge.performHandshake()
            
            if (rtt > 0) {
                telemetryCollector.recordHistogram("handshake_rtt_ms", rtt.toDouble())
                
                stateMachine.dispatch(VpnEvent.ConnectionPhaseCompleted(
                    phase = ConnectionPhase.PERFORMING_HANDSHAKE
                ))
            } else {
                throw RuntimeException("Handshake failed")
            }
            
        } catch (e: Exception) {
            stateMachine.dispatch(VpnEvent.ConnectionFailed(
                error = VpnError.ConnectionFailed(
                    message = "Handshake failed: ${e.message}",
                    phase = ConnectionPhase.PERFORMING_HANDSHAKE,
                    nativeErrorCode = null,
                    stackTrace = e.stackTraceToString()
                ),
                phase = ConnectionPhase.PERFORMING_HANDSHAKE
            ))
        }
    }
    
    private suspend fun handleVerifyConnection() {
        try {
            val isConnected = nativeBridge.verifyConnection()
            
            if (isConnected) {
                stateMachine.dispatch(VpnEvent.ConnectionPhaseCompleted(
                    phase = ConnectionPhase.VERIFYING_CONNECTION
                ))
            } else {
                throw RuntimeException("Connection verification failed")
            }
            
        } catch (e: Exception) {
            stateMachine.dispatch(VpnEvent.ConnectionFailed(
                error = VpnError.ConnectionFailed(
                    message = "Connection verification failed: ${e.message}",
                    phase = ConnectionPhase.VERIFYING_CONNECTION,
                    nativeErrorCode = null,
                    stackTrace = e.stackTraceToString()
                ),
                phase = ConnectionPhase.VERIFYING_CONNECTION
            ))
        }
    }
    
    private suspend fun handleFinalizeConnection() {
        val tunFd = synchronized(tunFdLock) { currentTunFd }
            ?: throw IllegalStateException("TUN fd is null")
        
        val connectionInfo = ConnectionInfo(
            serverName = configProvider.getServerName(),
            serverLocation = configProvider.getServerLocation(),
            serverIp = configProvider.getServerIp(),
            protocol = "WireGuard + Xray",
            connectedAt = Instant.now(),
            tunFd = tunFd.fd,
            localAddress = "10.0.0.2",
            dnsServers = listOf("8.8.8.8", "1.1.1.1"),
            mtu = 1420
        )
        
        stateMachine.dispatch(VpnEvent.ConnectionPhaseCompleted(
            phase = ConnectionPhase.FINALIZING,
            connectionInfo = connectionInfo
        ))
    }
    
    private suspend fun handleStopTunnel() {
        try {
            nativeBridge.stopTunnel()
        } catch (e: Exception) {
            // Log but continue with disconnection
        }
        
        stateMachine.dispatch(VpnEvent.DisconnectionPhaseCompleted(
            phase = DisconnectionPhase.STOPPING_TUNNEL
        ))
    }
    
    private suspend fun handleStartStatsMonitoring() {
        scope.launch {
            while (state.value is VpnLifecycleState.Connected) {
                try {
                    val stats = nativeBridge.getTunnelStats()
                    stateMachine.dispatch(VpnEvent.StatsUpdated(stats = stats))
                    delay(config.statsUpdateIntervalMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    delay(config.statsUpdateIntervalMs)
                }
            }
        }
    }
    
    private suspend fun handleStartHealthMonitoring() {
        healthMonitor = HealthMonitor(
            scope = scope,
            config = config.healthMonitorConfig
        ) { newStatus ->
            stateMachine.dispatch(VpnEvent.HealthStatusChanged(status = newStatus))
        }
        healthMonitor?.start()
    }
    
    private suspend fun handleStopStatsMonitoring() {
        // Stats monitoring stops automatically when state changes
    }
    
    private suspend fun handleStopHealthMonitoring() {
        healthMonitor?.stop()
        healthMonitor = null
    }
    
    private suspend fun handleNotifyConnected(effect: SideEffect.NotifyConnected) {
        telemetryCollector.incrementCounter("connections_established")
        // Additional notification logic (Telegram, etc.)
    }
    
    private suspend fun handleNotifyDisconnected(effect: SideEffect.NotifyDisconnected) {
        telemetryCollector.incrementCounter("disconnections_total")
        // Additional notification logic
    }
    
    private suspend fun handleCleanupResources() {
        handleStopHealthMonitoring()
        handleCloseTun()
        
        stateMachine.dispatch(VpnEvent.DisconnectionPhaseCompleted(
            phase = DisconnectionPhase.CLEANING_RESOURCES
        ))
    }
    
    private suspend fun handleCleanupFailedConnection(effect: SideEffect.CleanupFailedConnection) {
        effect.tunFd?.let { fd ->
            try {
                ParcelFileDescriptor.adoptFd(fd).close()
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        synchronized(tunFdLock) {
            try {
                currentTunFd?.close()
            } catch (e: Exception) {
                // Ignore
            }
            currentTunFd = null
        }
    }
    
    private suspend fun handlePrepareReconnect() {
        // Cleanup before reconnect
        handleStopHealthMonitoring()
        
        // Small delay before reconnect attempt
        delay(500)
        
        stateMachine.dispatch(VpnEvent.ReconnectAttemptStarted(attempt = 1))
    }
    
    private suspend fun handleScheduleReconnect(effect: SideEffect.ScheduleReconnect) {
        delay(effect.delay.inWholeMilliseconds)
        stateMachine.dispatch(VpnEvent.ReconnectAttemptStarted(
            attempt = (state.value as? VpnLifecycleState.Reconnecting)?.attempt ?: 1
        ))
    }
    
    private fun logStateChange(state: VpnLifecycleState) {
        val stateName = state::class.simpleName
        telemetryCollector.setGauge("current_state_ordinal", getStateOrdinal(state).toDouble())
    }
    
    private fun getStateOrdinal(state: VpnLifecycleState): Int {
        return when (state) {
            is VpnLifecycleState.Idle -> 0
            is VpnLifecycleState.Preparing -> 1
            is VpnLifecycleState.Connecting -> 2
            is VpnLifecycleState.Connected -> 3
            is VpnLifecycleState.Reconnecting -> 4
            is VpnLifecycleState.Disconnecting -> 5
            is VpnLifecycleState.Error -> 6
            is VpnLifecycleState.Suspended -> 7
        }
    }
}

/**
 * Orchestrator configuration
 */
data class OrchestratorConfig(
    val stateMachineConfig: StateMachineConfig = StateMachineConfig(),
    val telemetryConfig: TelemetryConfig = TelemetryConfig(),
    val healthMonitorConfig: HealthMonitorConfig = HealthMonitorConfig(),
    val statsUpdateIntervalMs: Long = 1000
)

/**
 * Native tunnel bridge interface
 */
interface NativeTunnelBridge {
    suspend fun createTunInterface(): ParcelFileDescriptor
    suspend fun initializeSocketProtector()
    suspend fun startTunnel(tunFd: Int, wgConfig: String, xrayConfig: String): Int
    suspend fun stopTunnel(): Int
    suspend fun performHandshake(): Long
    suspend fun verifyConnection(): Boolean
    suspend fun getTunnelStats(): ConnectionStats
}

/**
 * Configuration provider interface
 */
interface ConfigProvider {
    suspend fun loadConfigs(configId: String?): VpnConfigs
    suspend fun getCurrentConfigs(): VpnConfigs
    fun validateWireGuardConfig(config: String): Boolean
    fun validateXrayConfig(config: String): Boolean
    fun getServerName(): String
    fun getServerLocation(): String
    fun getServerIp(): String
}

/**
 * VPN configurations container
 */
data class VpnConfigs(
    val wireGuardConfig: String,
    val xrayConfig: String
)
