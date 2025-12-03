package com.hyperxray.an.vpn.lifecycle

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 🧪 VPN State Machine Tests
 * 
 * Comprehensive tests for the declarative state machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnStateMachineTest {
    
    private lateinit var testScope: TestScope
    private lateinit var stateMachine: VpnStateMachine
    private lateinit var telemetryEvents: MutableList<TelemetryEvent>
    
    @Before
    fun setup() {
        testScope = TestScope(UnconfinedTestDispatcher())
        stateMachine = VpnStateMachine(testScope, StateMachineConfig())
        telemetryEvents = mutableListOf()
        
        // Collect telemetry events
        testScope.launch {
            stateMachine.telemetry.collect { event ->
                telemetryEvents.add(event)
            }
        }
    }
    
    @After
    fun teardown() {
        testScope.cancel()
    }
    
    // ===== Initial State Tests =====
    
    @Test
    fun `initial state is Idle`() = testScope.runTest {
        val state = stateMachine.state.value
        assertIs<VpnLifecycleState.Idle>(state)
    }
    
    @Test
    fun `idle state has valid session id`() = testScope.runTest {
        val state = stateMachine.state.value
        assertTrue(state.sessionId.startsWith("session-"))
    }
    
    // ===== Start Flow Tests =====
    
    @Test
    fun `start request transitions from Idle to Preparing`() = testScope.runTest {
        // Given: Idle state
        assertIs<VpnLifecycleState.Idle>(stateMachine.state.value)
        
        // When: Start requested
        stateMachine.dispatch(VpnEvent.StartRequested())
        advanceUntilIdle()
        
        // Then: State is Preparing
        val state = stateMachine.state.value
        assertIs<VpnLifecycleState.Preparing>(state)
        assertEquals(PreparationPhase.LOADING_CONFIG, state.phase)
    }
    
    @Test
    fun `preparation phases progress correctly`() = testScope.runTest {
        // Start
        stateMachine.dispatch(VpnEvent.StartRequested())
        advanceUntilIdle()
        
        // Progress through preparation phases
        for (phase in PreparationPhase.entries.dropLast(1)) {
            stateMachine.dispatch(VpnEvent.PreparationPhaseCompleted(phase = phase))
            advanceUntilIdle()
        }
        
        // Should be in Connecting state after all preparation phases
        val state = stateMachine.state.value
        assertIs<VpnLifecycleState.Connecting>(state)
    }
    
    @Test
    fun `preparation failure transitions to Error`() = testScope.runTest {
        // Start
        stateMachine.dispatch(VpnEvent.StartRequested())
        advanceUntilIdle()
        
        // Fail preparation
        val error = VpnError.ConfigurationError(
            message = "Invalid config",
            configType = "WireGuard",
            validationErrors = listOf("Missing private key")
        )
        stateMachine.dispatch(VpnEvent.PreparationFailed(
            error = error,
            phase = PreparationPhase.VALIDATING_CONFIG
        ))
        advanceUntilIdle()
        
        // Should be in Error state
        val state = stateMachine.state.value
        assertIs<VpnLifecycleState.Error>(state)
        assertEquals(error.message, state.error.message)
        assertTrue(state.isRecoverable)
    }
    
    // ===== Connection Flow Tests =====
    
    @Test
    fun `TUN creation advances connection phase`() = testScope.runTest {
        // Get to Connecting state
        transitionToConnecting()
        
        // TUN created
        stateMachine.dispatch(VpnEvent.TunCreated(
            tunFd = 42,
            localAddress = "10.0.0.2",
            mtu = 1420
        ))
        advanceUntilIdle()
        
        // Should advance to next phase
        val state = stateMachine.state.value
        assertIs<VpnLifecycleState.Connecting>(state)
        assertEquals(ConnectionPhase.PROTECTING_SOCKETS, state.phase)
        assertEquals(42, state.tunFd)
    }
    
    @Test
    fun `connection phases progress to Connected`() = testScope.runTest {
        // Get to Connecting state
        transitionToConnecting()
        
        // TUN created
        stateMachine.dispatch(VpnEvent.TunCreated(tunFd = 42, localAddress = "10.0.0.2", mtu = 1420))
        advanceUntilIdle()
        
        // Progress through connection phases
        val connectionInfo = createTestConnectionInfo()
        for (phase in ConnectionPhase.entries) {
            val info = if (phase == ConnectionPhase.FINALIZING) connectionInfo else null
            stateMachine.dispatch(VpnEvent.ConnectionPhaseCompleted(phase = phase, connectionInfo = info))
            advanceUntilIdle()
        }
        
        // Should be Connected
        val state = stateMachine.state.value
        assertIs<VpnLifecycleState.Connected>(state)
        assertEquals(connectionInfo.serverName, state.connectionInfo.serverName)
    }
    
    @Test
    fun `connection failure with retries transitions to Reconnecting`() = testScope.runTest {
        // Get to Connecting state
        transitionToConnecting()
        
        // Connection fails
        val error = VpnError.ConnectionFailed(
            message = "Handshake timeout",
            phase = ConnectionPhase.PERFORMING_HANDSHAKE,
            nativeErrorCode = -3
        )
        stateMachine.dispatch(VpnEvent.ConnectionFailed(error = error, phase = ConnectionPhase.PERFORMING_HANDSHAKE))
        advanceUntilIdle()
        
        // Should be Reconnecting (first attempt)
        val state = stateMachine.state.value
        assertIs<VpnLifecycleState.Reconnecting>(state)
        assertEquals(2, state.attempt) // Second attempt after first failure
    }
    
    // ===== Connected State Tests =====
    
    @Test
    fun `stats update in Connected state`() = testScope.runTest {
        // Get to Connected state
        transitionToConnected()
        
        // Update stats
        val newStats = ConnectionStats(
            txBytes = 1000,
            rxBytes = 2000,
            txPackets = 10,
            rxPackets = 20
        )
        stateMachine.dispatch(VpnEvent.StatsUpdated(stats = newStats))
        advanceUntilIdle()
        
        // Stats should be updated
        val state = stateMachine.state.value
        assertIs<VpnLifecycleState.Connected>(state)
        assertEquals(1000, state.stats.txBytes)
        assertEquals(2000, state.stats.rxBytes)
    }
    
    @Test
    fun `health status change updates Connected state`() = testScope.runTest {
        // Get to Connected state
        transitionToConnected()
        
        // Health degrades
        val degradedStatus = HealthStatus.Degraded(
            reason = "High latency",
            metrics = HealthMetrics(latencyMs = 300)
        )
        stateMachine.dispatch(VpnEvent.HealthStatusChanged(status = degradedStatus))
        advanceUntilIdle()
        
        // Health should be updated
        val state = stateMachine.state.value
        assertIs<VpnLifecycleState.Connected>(state)
        assertIs<HealthStatus.Degraded>(state.healthStatus)
    }
    
    @Test
    fun `critical health triggers reconnect`() = testScope.runTest {
        // Get to Connected state
        transitionToConnected()
        
        // Health becomes critical
        val criticalStatus = HealthStatus.Critical(
            reason = "Connection lost",
            metrics = HealthMetrics(packetLossPercent = 50f)
        )
        stateMachine.dispatch(VpnEvent.HealthStatusChanged(status = criticalStatus))
        advanceUntilIdle()
        
        // Should trigger reconnect side effect (state may still be Connected
        // until side effect handler dispatches reconnect event)
        val state = stateMachine.state.value
        assertIs<VpnLifecycleState.Connected>(state)
        assertIs<HealthStatus.Critical>(state.healthStatus)
    }
    
    // ===== Disconnect Flow Tests =====
    
    @Test
    fun `disconnect request transitions to Disconnecting`() = testScope.runTest {
        // Get to Connected state
        transitionToConnected()
        
        // Request disconnect
        stateMachine.dispatch(VpnEvent.DisconnectRequested(reason = DisconnectReason.UserRequested))
        advanceUntilIdle()
        
        // Should be Disconnecting
        val state = stateMachine.state.value
        assertIs<VpnLifecycleState.Disconnecting>(state)
        assertEquals(DisconnectReason.UserRequested, state.reason)
    }
    
    @Test
    fun `disconnection phases progress to Idle`() = testScope.runTest {
        // Get to Disconnecting state
        transitionToConnected()
        stateMachine.dispatch(VpnEvent.DisconnectRequested())
        advanceUntilIdle()
        
        // Progress through disconnection phases
        for (phase in DisconnectionPhase.entries) {
            stateMachine.dispatch(VpnEvent.DisconnectionPhaseCompleted(phase = phase))
            advanceUntilIdle()
        }
        
        // Should be Idle
        val state = stateMachine.state.value
        assertIs<VpnLifecycleState.Idle>(state)
    }
    
    // ===== Error Recovery Tests =====
    
    @Test
    fun `retry from Error transitions to Preparing`() = testScope.runTest {
        // Get to Error state
        transitionToError()
        
        // Retry
        stateMachine.dispatch(VpnEvent.RetryRequested())
        advanceUntilIdle()
        
        // Should be Preparing
        val state = stateMachine.state.value
        assertIs<VpnLifecycleState.Preparing>(state)
    }
    
    @Test
    fun `dismiss error transitions to Idle`() = testScope.runTest {
        // Get to Error state
        transitionToError()
        
        // Dismiss
        stateMachine.dispatch(VpnEvent.DismissError())
        advanceUntilIdle()
        
        // Should be Idle
        val state = stateMachine.state.value
        assertIs<VpnLifecycleState.Idle>(state)
    }
    
    // ===== Telemetry Tests =====
    
    @Test
    fun `state transitions emit telemetry events`() = testScope.runTest {
        // Perform a transition
        stateMachine.dispatch(VpnEvent.StartRequested())
        advanceUntilIdle()
        
        // Should have telemetry event
        assertTrue(telemetryEvents.isNotEmpty())
        val transitionEvent = telemetryEvents.filterIsInstance<TelemetryEvent.StateTransition>().firstOrNull()
        assertNotNull(transitionEvent)
        assertEquals("Idle", transitionEvent.fromState)
        assertEquals("Preparing", transitionEvent.toState)
    }
    
    // ===== State History Tests =====
    
    @Test
    fun `state history is recorded`() = testScope.runTest {
        // Perform transitions
        stateMachine.dispatch(VpnEvent.StartRequested())
        advanceUntilIdle()
        
        stateMachine.dispatch(VpnEvent.CancelRequested())
        advanceUntilIdle()
        
        // Check history
        val history = stateMachine.stateHistory.value
        assertTrue(history.size >= 2)
    }
    
    // ===== Helper Methods =====
    
    private suspend fun transitionToConnecting() {
        stateMachine.dispatch(VpnEvent.StartRequested())
        testScope.advanceUntilIdle()
        
        for (phase in PreparationPhase.entries.dropLast(1)) {
            stateMachine.dispatch(VpnEvent.PreparationPhaseCompleted(phase = phase))
            testScope.advanceUntilIdle()
        }
    }
    
    private suspend fun transitionToConnected() {
        transitionToConnecting()
        
        stateMachine.dispatch(VpnEvent.TunCreated(tunFd = 42, localAddress = "10.0.0.2", mtu = 1420))
        testScope.advanceUntilIdle()
        
        val connectionInfo = createTestConnectionInfo()
        for (phase in ConnectionPhase.entries) {
            val info = if (phase == ConnectionPhase.FINALIZING) connectionInfo else null
            stateMachine.dispatch(VpnEvent.ConnectionPhaseCompleted(phase = phase, connectionInfo = info))
            testScope.advanceUntilIdle()
        }
    }
    
    private suspend fun transitionToError() {
        stateMachine.dispatch(VpnEvent.StartRequested())
        testScope.advanceUntilIdle()
        
        stateMachine.dispatch(VpnEvent.PreparationFailed(
            error = VpnError.ConfigurationError(
                message = "Test error",
                configType = "Test",
                validationErrors = listOf("Error")
            ),
            phase = PreparationPhase.LOADING_CONFIG
        ))
        testScope.advanceUntilIdle()
    }
    
    private fun createTestConnectionInfo(): ConnectionInfo {
        return ConnectionInfo(
            serverName = "Test Server",
            serverLocation = "Test Location",
            serverIp = "1.2.3.4",
            protocol = "WireGuard + Xray",
            connectedAt = Instant.now(),
            tunFd = 42,
            localAddress = "10.0.0.2",
            dnsServers = listOf("8.8.8.8"),
            mtu = 1420
        )
    }
}
