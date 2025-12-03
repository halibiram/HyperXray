package com.hyperxray.an.vpn.lifecycle

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 🚀 Declarative VPN State Machine (2030 Architecture)
 * 
 * Features:
 * - Pure functional state transitions
 * - Event-driven architecture with backpressure
 * - Time-travel debugging support
 * - Automatic telemetry emission
 * - Circuit breaker for fault tolerance
 */
class VpnStateMachine(
    private val scope: CoroutineScope,
    private val config: StateMachineConfig = StateMachineConfig()
) {
    
    // Immutable state flow - single source of truth
    private val _state = MutableStateFlow<VpnLifecycleState>(VpnLifecycleState.Idle())
    val state: StateFlow<VpnLifecycleState> = _state.asStateFlow()
    
    // Event channel with backpressure
    private val eventChannel = Channel<VpnEvent>(Channel.BUFFERED)
    
    // State history for time-travel debugging
    private val _stateHistory = MutableStateFlow<List<StateSnapshot>>(emptyList())
    val stateHistory: StateFlow<List<StateSnapshot>> = _stateHistory.asStateFlow()
    
    // Telemetry events
    private val _telemetry = MutableSharedFlow<TelemetryEvent>(extraBufferCapacity = 100)
    val telemetry: SharedFlow<TelemetryEvent> = _telemetry.asSharedFlow()
    
    // Circuit breaker state
    private val circuitBreaker = CircuitBreaker(config.circuitBreakerConfig)
    
    // Transition validators
    private val transitionValidators = mutableListOf<TransitionValidator>()
    
    // Side effect handlers
    private val sideEffectHandlers = mutableMapOf<String, SideEffectHandler>()
    
    init {
        // Start event processing loop
        scope.launch {
            processEvents()
        }
        
        // Start state history recording
        scope.launch {
            recordStateHistory()
        }
    }
    
    /**
     * Dispatch an event to the state machine
     */
    suspend fun dispatch(event: VpnEvent) {
        eventChannel.send(event)
    }
    
    /**
     * Dispatch an event without suspending (fire-and-forget)
     */
    fun dispatchAsync(event: VpnEvent) {
        scope.launch {
            eventChannel.send(event)
        }
    }
    
    /**
     * Process events and perform state transitions
     */
    private suspend fun processEvents() {
        for (event in eventChannel) {
            try {
                val currentState = _state.value
                val transition = computeTransition(currentState, event)
                
                if (transition != null) {
                    // Validate transition
                    val validationResult = validateTransition(currentState, transition.newState, event)
                    
                    if (validationResult.isValid) {
                        // Execute pre-transition side effects
                        transition.preSideEffects.forEach { effect ->
                            executeSideEffect(effect)
                        }
                        
                        // Perform state transition
                        _state.value = transition.newState
                        
                        // Emit telemetry
                        emitTransitionTelemetry(currentState, transition.newState, event)
                        
                        // Execute post-transition side effects
                        transition.postSideEffects.forEach { effect ->
                            executeSideEffect(effect)
                        }
                    } else {
                        // Emit validation failure telemetry
                        _telemetry.emit(TelemetryEvent.TransitionRejected(
                            fromState = currentState::class.simpleName ?: "Unknown",
                            event = event::class.simpleName ?: "Unknown",
                            reason = validationResult.reason
                        ))
                    }
                }
            } catch (e: Exception) {
                handleTransitionError(e, event)
            }
        }
    }
    
    /**
     * Compute the next state based on current state and event
     * Pure function - no side effects
     */
    private fun computeTransition(
        currentState: VpnLifecycleState,
        event: VpnEvent
    ): StateTransition? {
        return when (currentState) {
            is VpnLifecycleState.Idle -> handleIdleState(currentState, event)
            is VpnLifecycleState.Preparing -> handlePreparingState(currentState, event)
            is VpnLifecycleState.Connecting -> handleConnectingState(currentState, event)
            is VpnLifecycleState.Connected -> handleConnectedState(currentState, event)
            is VpnLifecycleState.Reconnecting -> handleReconnectingState(currentState, event)
            is VpnLifecycleState.Disconnecting -> handleDisconnectingState(currentState, event)
            is VpnLifecycleState.Error -> handleErrorState(currentState, event)
            is VpnLifecycleState.Suspended -> handleSuspendedState(currentState, event)
        }
    }
    
    // State handlers - pure functions returning transitions
    
    private fun handleIdleState(
        state: VpnLifecycleState.Idle,
        event: VpnEvent
    ): StateTransition? = when (event) {
        is VpnEvent.StartRequested -> StateTransition(
            newState = VpnLifecycleState.Preparing(
                sessionId = state.sessionId,
                metadata = state.metadata.incrementTransition("Idle", "Start requested"),
                phase = PreparationPhase.LOADING_CONFIG,
                progress = 0f
            ),
            preSideEffects = listOf(SideEffect.EmitLog("Starting VPN preparation")),
            postSideEffects = listOf(SideEffect.LoadConfiguration(event.configId))
        )
        else -> null
    }
    
    private fun handlePreparingState(
        state: VpnLifecycleState.Preparing,
        event: VpnEvent
    ): StateTransition? = when (event) {
        is VpnEvent.PreparationPhaseCompleted -> {
            val nextPhase = getNextPreparationPhase(state.phase)
            if (nextPhase == PreparationPhase.READY) {
                StateTransition(
                    newState = VpnLifecycleState.Connecting(
                        sessionId = state.sessionId,
                        metadata = state.metadata.incrementTransition("Preparing", "Preparation complete"),
                        phase = ConnectionPhase.CREATING_TUN,
                        progress = 0f
                    ),
                    postSideEffects = listOf(SideEffect.CreateTunInterface)
                )
            } else {
                StateTransition(
                    newState = state.copy(
                        phase = nextPhase,
                        progress = calculatePreparationProgress(nextPhase),
                        timestamp = Instant.now()
                    )
                )
            }
        }
        is VpnEvent.PreparationFailed -> StateTransition(
            newState = VpnLifecycleState.Error(
                sessionId = state.sessionId,
                metadata = state.metadata.incrementTransition("Preparing", "Preparation failed"),
                error = event.error,
                recoveryOptions = computeRecoveryOptions(event.error),
                isRecoverable = isErrorRecoverable(event.error)
            )
        )
        is VpnEvent.CancelRequested -> StateTransition(
            newState = VpnLifecycleState.Idle(
                sessionId = state.sessionId,
                metadata = state.metadata.incrementTransition("Preparing", "Cancelled")
            )
        )
        else -> null
    }
    
    private fun handleConnectingState(
        state: VpnLifecycleState.Connecting,
        event: VpnEvent
    ): StateTransition? = when (event) {
        is VpnEvent.TunCreated -> StateTransition(
            newState = state.copy(
                phase = ConnectionPhase.PROTECTING_SOCKETS,
                progress = 0.2f,
                tunFd = event.tunFd,
                timestamp = Instant.now()
            ),
            postSideEffects = listOf(SideEffect.InitializeSocketProtector)
        )
        is VpnEvent.ConnectionPhaseCompleted -> {
            val nextPhase = getNextConnectionPhase(state.phase)
            if (nextPhase == null) {
                // Connection complete
                StateTransition(
                    newState = VpnLifecycleState.Connected(
                        sessionId = state.sessionId,
                        metadata = state.metadata.incrementTransition("Connecting", "Connected"),
                        connectionInfo = event.connectionInfo!!,
                        healthStatus = HealthStatus.Healthy
                    ),
                    postSideEffects = listOf(
                        SideEffect.StartStatsMonitoring,
                        SideEffect.StartHealthMonitoring,
                        SideEffect.NotifyConnected(event.connectionInfo)
                    )
                )
            } else {
                StateTransition(
                    newState = state.copy(
                        phase = nextPhase,
                        progress = calculateConnectionProgress(nextPhase),
                        timestamp = Instant.now()
                    ),
                    postSideEffects = listOf(getConnectionPhaseSideEffect(nextPhase))
                )
            }
        }
        is VpnEvent.ConnectionFailed -> {
            if (circuitBreaker.shouldAllowRetry() && state.attempt < config.maxConnectionAttempts) {
                StateTransition(
                    newState = VpnLifecycleState.Reconnecting(
                        sessionId = state.sessionId,
                        metadata = state.metadata.incrementTransition("Connecting", "Connection failed, retrying"),
                        reason = ReconnectReason.ProtocolError(event.error.code, event.error.message),
                        attempt = state.attempt + 1,
                        maxAttempts = config.maxConnectionAttempts,
                        backoffDuration = calculateBackoff(state.attempt),
                        previousConnectionInfo = null
                    ),
                    preSideEffects = listOf(SideEffect.CleanupFailedConnection(state.tunFd))
                )
            } else {
                StateTransition(
                    newState = VpnLifecycleState.Error(
                        sessionId = state.sessionId,
                        metadata = state.metadata.incrementTransition("Connecting", "Connection failed"),
                        error = event.error,
                        recoveryOptions = computeRecoveryOptions(event.error),
                        isRecoverable = isErrorRecoverable(event.error)
                    ),
                    preSideEffects = listOf(SideEffect.CleanupFailedConnection(state.tunFd))
                )
            }
        }
        is VpnEvent.CancelRequested -> StateTransition(
            newState = VpnLifecycleState.Disconnecting(
                sessionId = state.sessionId,
                metadata = state.metadata.incrementTransition("Connecting", "Cancelled"),
                reason = DisconnectReason.UserRequested,
                phase = DisconnectionPhase.STOPPING_TUNNEL
            ),
            postSideEffects = listOf(SideEffect.StopTunnel)
        )
        else -> null
    }
    
    private fun handleConnectedState(
        state: VpnLifecycleState.Connected,
        event: VpnEvent
    ): StateTransition? = when (event) {
        is VpnEvent.StatsUpdated -> StateTransition(
            newState = state.copy(
                stats = event.stats,
                timestamp = Instant.now()
            )
        )
        is VpnEvent.HealthStatusChanged -> StateTransition(
            newState = state.copy(
                healthStatus = event.status,
                timestamp = Instant.now()
            ),
            postSideEffects = if (event.status is HealthStatus.Critical) {
                listOf(SideEffect.TriggerReconnect(ReconnectReason.PacketLoss))
            } else emptyList()
        )
        is VpnEvent.DisconnectRequested -> StateTransition(
            newState = VpnLifecycleState.Disconnecting(
                sessionId = state.sessionId,
                metadata = state.metadata.incrementTransition("Connected", "Disconnect requested"),
                reason = event.reason,
                phase = DisconnectionPhase.NOTIFYING_PEERS
            ),
            postSideEffects = listOf(SideEffect.InitiateGracefulDisconnect)
        )
        is VpnEvent.ConnectionLost -> StateTransition(
            newState = VpnLifecycleState.Reconnecting(
                sessionId = state.sessionId,
                metadata = state.metadata.incrementTransition("Connected", "Connection lost"),
                reason = event.reason,
                attempt = 1,
                maxAttempts = config.maxReconnectAttempts,
                backoffDuration = 0.seconds,
                previousConnectionInfo = state.connectionInfo
            ),
            postSideEffects = listOf(SideEffect.PrepareReconnect)
        )
        is VpnEvent.SuspendRequested -> StateTransition(
            newState = VpnLifecycleState.Suspended(
                sessionId = state.sessionId,
                metadata = state.metadata.incrementTransition("Connected", "Suspended"),
                reason = event.reason,
                preservedState = state
            ),
            postSideEffects = listOf(SideEffect.SuspendConnection)
        )
        is VpnEvent.PermissionRevoked -> StateTransition(
            newState = VpnLifecycleState.Disconnecting(
                sessionId = state.sessionId,
                metadata = state.metadata.incrementTransition("Connected", "Permission revoked"),
                reason = DisconnectReason.PermissionRevoked,
                phase = DisconnectionPhase.STOPPING_TUNNEL
            ),
            postSideEffects = listOf(SideEffect.ForceDisconnect)
        )
        else -> null
    }
    
    private fun handleReconnectingState(
        state: VpnLifecycleState.Reconnecting,
        event: VpnEvent
    ): StateTransition? = when (event) {
        is VpnEvent.ReconnectAttemptStarted -> StateTransition(
            newState = VpnLifecycleState.Connecting(
                sessionId = state.sessionId,
                metadata = state.metadata.incrementTransition("Reconnecting", "Reconnect attempt ${state.attempt}"),
                phase = ConnectionPhase.CREATING_TUN,
                attempt = state.attempt
            ),
            postSideEffects = listOf(SideEffect.CreateTunInterface)
        )
        is VpnEvent.ReconnectFailed -> {
            if (state.attempt < state.maxAttempts && circuitBreaker.shouldAllowRetry()) {
                val nextBackoff = calculateBackoff(state.attempt)
                StateTransition(
                    newState = state.copy(
                        attempt = state.attempt + 1,
                        backoffDuration = nextBackoff,
                        timestamp = Instant.now()
                    ),
                    postSideEffects = listOf(SideEffect.ScheduleReconnect(nextBackoff))
                )
            } else {
                circuitBreaker.recordFailure()
                StateTransition(
                    newState = VpnLifecycleState.Error(
                        sessionId = state.sessionId,
                        metadata = state.metadata.incrementTransition("Reconnecting", "Max attempts reached"),
                        error = VpnError.ConnectionFailed(
                            message = "Failed to reconnect after ${state.attempt} attempts",
                            phase = ConnectionPhase.ESTABLISHING_TUNNEL,
                            nativeErrorCode = null
                        ),
                        recoveryOptions = listOf(
                            RecoveryOption.Retry(delayMs = 30000),
                            RecoveryOption.ChangeServer()
                        ),
                        isRecoverable = true
                    )
                )
            }
        }
        is VpnEvent.CancelRequested -> StateTransition(
            newState = VpnLifecycleState.Idle(
                sessionId = state.sessionId,
                metadata = state.metadata.incrementTransition("Reconnecting", "Cancelled")
            )
        )
        else -> null
    }
    
    private fun handleDisconnectingState(
        state: VpnLifecycleState.Disconnecting,
        event: VpnEvent
    ): StateTransition? = when (event) {
        is VpnEvent.DisconnectionPhaseCompleted -> {
            val nextPhase = getNextDisconnectionPhase(state.phase)
            if (nextPhase == null) {
                StateTransition(
                    newState = VpnLifecycleState.Idle(
                        sessionId = generateSessionId(), // New session for next connection
                        metadata = StateMetadata()
                    ),
                    postSideEffects = listOf(
                        SideEffect.NotifyDisconnected(state.reason),
                        SideEffect.CleanupResources
                    )
                )
            } else {
                StateTransition(
                    newState = state.copy(
                        phase = nextPhase,
                        progress = calculateDisconnectionProgress(nextPhase),
                        timestamp = Instant.now()
                    ),
                    postSideEffects = listOf(getDisconnectionPhaseSideEffect(nextPhase))
                )
            }
        }
        is VpnEvent.ForceDisconnect -> StateTransition(
            newState = VpnLifecycleState.Idle(
                sessionId = generateSessionId(),
                metadata = StateMetadata()
            ),
            postSideEffects = listOf(SideEffect.ForceCleanup)
        )
        else -> null
    }
    
    private fun handleErrorState(
        state: VpnLifecycleState.Error,
        event: VpnEvent
    ): StateTransition? = when (event) {
        is VpnEvent.RetryRequested -> {
            circuitBreaker.recordSuccess() // Reset on manual retry
            StateTransition(
                newState = VpnLifecycleState.Preparing(
                    sessionId = state.sessionId,
                    metadata = state.metadata.incrementTransition("Error", "Retry requested"),
                    phase = PreparationPhase.LOADING_CONFIG
                ),
                postSideEffects = listOf(SideEffect.LoadConfiguration(null))
            )
        }
        is VpnEvent.DismissError -> StateTransition(
            newState = VpnLifecycleState.Idle(
                sessionId = generateSessionId(),
                metadata = StateMetadata()
            )
        )
        else -> null
    }
    
    private fun handleSuspendedState(
        state: VpnLifecycleState.Suspended,
        event: VpnEvent
    ): StateTransition? = when (event) {
        is VpnEvent.ResumeRequested -> StateTransition(
            newState = state.preservedState.copy(
                timestamp = Instant.now(),
                metadata = state.metadata.incrementTransition("Suspended", "Resumed")
            ),
            postSideEffects = listOf(SideEffect.ResumeConnection)
        )
        is VpnEvent.DisconnectRequested -> StateTransition(
            newState = VpnLifecycleState.Disconnecting(
                sessionId = state.sessionId,
                metadata = state.metadata.incrementTransition("Suspended", "Disconnect while suspended"),
                reason = event.reason,
                phase = DisconnectionPhase.STOPPING_TUNNEL
            ),
            postSideEffects = listOf(SideEffect.StopTunnel)
        )
        else -> null
    }
    
    // Helper functions
    
    private fun getNextPreparationPhase(current: PreparationPhase): PreparationPhase {
        return PreparationPhase.entries.getOrNull(current.ordinal + 1) ?: PreparationPhase.READY
    }
    
    private fun getNextConnectionPhase(current: ConnectionPhase): ConnectionPhase? {
        val nextOrdinal = current.ordinal + 1
        return if (nextOrdinal >= ConnectionPhase.entries.size) null
        else ConnectionPhase.entries[nextOrdinal]
    }
    
    private fun getNextDisconnectionPhase(current: DisconnectionPhase): DisconnectionPhase? {
        val nextOrdinal = current.ordinal + 1
        return if (nextOrdinal >= DisconnectionPhase.entries.size) null
        else DisconnectionPhase.entries[nextOrdinal]
    }
    
    private fun calculatePreparationProgress(phase: PreparationPhase): Float {
        return (phase.ordinal + 1).toFloat() / PreparationPhase.entries.size
    }
    
    private fun calculateConnectionProgress(phase: ConnectionPhase): Float {
        return (phase.ordinal + 1).toFloat() / ConnectionPhase.entries.size
    }
    
    private fun calculateDisconnectionProgress(phase: DisconnectionPhase): Float {
        return (phase.ordinal + 1).toFloat() / DisconnectionPhase.entries.size
    }
    
    private fun calculateBackoff(attempt: Int): Duration {
        val baseMs = config.baseBackoffMs
        val maxMs = config.maxBackoffMs
        val backoffMs = (baseMs * (1 shl attempt.coerceAtMost(10))).coerceAtMost(maxMs)
        val jitter = (Math.random() * backoffMs * 0.1).toLong()
        return (backoffMs + jitter).milliseconds
    }
    
    private fun computeRecoveryOptions(error: VpnError): List<RecoveryOption> {
        return when (error) {
            is VpnError.PermissionDenied -> listOf(RecoveryOption.RequestPermission())
            is VpnError.ConfigurationError -> listOf(RecoveryOption.ResetConfig(), RecoveryOption.ContactSupport())
            is VpnError.NetworkError -> if (error.isTransient) {
                listOf(RecoveryOption.Retry(delayMs = 5000))
            } else {
                listOf(RecoveryOption.ChangeServer(), RecoveryOption.ContactSupport())
            }
            else -> listOf(RecoveryOption.Retry(), RecoveryOption.ContactSupport())
        }
    }
    
    private fun isErrorRecoverable(error: VpnError): Boolean {
        return when (error) {
            is VpnError.PermissionDenied -> true
            is VpnError.NetworkError -> error.isTransient
            is VpnError.ConfigurationError -> true
            else -> true
        }
    }
    
    private fun getConnectionPhaseSideEffect(phase: ConnectionPhase): SideEffect {
        return when (phase) {
            ConnectionPhase.CREATING_TUN -> SideEffect.CreateTunInterface
            ConnectionPhase.PROTECTING_SOCKETS -> SideEffect.InitializeSocketProtector
            ConnectionPhase.STARTING_WIREGUARD -> SideEffect.StartWireGuard
            ConnectionPhase.STARTING_XRAY -> SideEffect.StartXray
            ConnectionPhase.ESTABLISHING_TUNNEL -> SideEffect.EstablishTunnel
            ConnectionPhase.PERFORMING_HANDSHAKE -> SideEffect.PerformHandshake
            ConnectionPhase.VERIFYING_CONNECTION -> SideEffect.VerifyConnection
            ConnectionPhase.FINALIZING -> SideEffect.FinalizeConnection
        }
    }
    
    private fun getDisconnectionPhaseSideEffect(phase: DisconnectionPhase): SideEffect {
        return when (phase) {
            DisconnectionPhase.NOTIFYING_PEERS -> SideEffect.NotifyPeers
            DisconnectionPhase.STOPPING_TUNNEL -> SideEffect.StopTunnel
            DisconnectionPhase.CLOSING_TUN -> SideEffect.CloseTun
            DisconnectionPhase.CLEANING_RESOURCES -> SideEffect.CleanupResources
            DisconnectionPhase.FLUSHING_LOGS -> SideEffect.FlushLogs
            DisconnectionPhase.COMPLETED -> SideEffect.NoOp
        }
    }
    
    private suspend fun validateTransition(
        from: VpnLifecycleState,
        to: VpnLifecycleState,
        event: VpnEvent
    ): ValidationResult {
        for (validator in transitionValidators) {
            val result = validator.validate(from, to, event)
            if (!result.isValid) return result
        }
        return ValidationResult(isValid = true)
    }
    
    private suspend fun executeSideEffect(effect: SideEffect) {
        val handler = sideEffectHandlers[effect::class.simpleName]
        handler?.handle(effect)
    }
    
    private suspend fun emitTransitionTelemetry(
        from: VpnLifecycleState,
        to: VpnLifecycleState,
        event: VpnEvent
    ) {
        _telemetry.emit(TelemetryEvent.StateTransition(
            fromState = from::class.simpleName ?: "Unknown",
            toState = to::class.simpleName ?: "Unknown",
            event = event::class.simpleName ?: "Unknown",
            timestamp = Instant.now(),
            sessionId = to.sessionId,
            traceId = to.metadata.traceId
        ))
    }
    
    private suspend fun handleTransitionError(error: Exception, event: VpnEvent) {
        _telemetry.emit(TelemetryEvent.TransitionError(
            event = event::class.simpleName ?: "Unknown",
            error = error.message ?: "Unknown error",
            timestamp = Instant.now()
        ))
    }
    
    private suspend fun recordStateHistory() {
        state.collect { newState ->
            val snapshot = StateSnapshot(
                state = newState,
                timestamp = Instant.now()
            )
            _stateHistory.update { history ->
                (history + snapshot).takeLast(config.maxHistorySize)
            }
        }
    }
    
    /**
     * Register a side effect handler
     */
    fun registerSideEffectHandler(effectType: String, handler: SideEffectHandler) {
        sideEffectHandlers[effectType] = handler
    }
    
    /**
     * Add a transition validator
     */
    fun addTransitionValidator(validator: TransitionValidator) {
        transitionValidators.add(validator)
    }
    
    /**
     * Time-travel to a previous state (for debugging)
     */
    fun timeTravel(snapshotIndex: Int) {
        val history = _stateHistory.value
        if (snapshotIndex in history.indices) {
            _state.value = history[snapshotIndex].state
        }
    }
}

// Extension function for metadata
private fun StateMetadata.incrementTransition(previousState: String, reason: String): StateMetadata {
    return copy(
        transitionCount = transitionCount + 1,
        previousState = previousState,
        transitionReason = reason,
        spanId = generateSpanId()
    )
}

private fun generateSessionId(): String = 
    "session-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"

private fun generateSpanId(): String = 
    java.util.UUID.randomUUID().toString().replace("-", "").take(16)
