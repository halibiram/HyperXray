package com.hyperxray.an.vpn.lifecycle

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 🚀 VPN Lifecycle ViewModel (2030 Architecture)
 * 
 * Reactive ViewModel that exposes VPN state to UI layer.
 * Features:
 * - Declarative state observation
 * - Type-safe state handling
 * - Automatic service binding
 * - Rich UI state derivation
 */
class VpnLifecycleViewModel(application: Application) : AndroidViewModel(application) {
    
    // Service binding
    private var service: HyperVpnServiceAdapter? = null
    private var isBound = false
    
    // UI State
    private val _uiState = MutableStateFlow(VpnUiState())
    val uiState: StateFlow<VpnUiState> = _uiState.asStateFlow()
    
    // Connection for service binding
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? HyperVpnServiceAdapter.LocalBinder
            service = localBinder?.getService()
            isBound = true
            
            // Start observing service state
            service?.let { observeServiceState(it) }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
        }
    }
    
    init {
        bindService()
    }
    
    // ===== Public Actions =====
    
    /**
     * Connect VPN
     */
    fun connect(configId: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            val intent = Intent(getApplication(), HyperVpnServiceAdapter::class.java).apply {
                action = HyperVpnServiceAdapter.ACTION_START
                configId?.let { putExtra(HyperVpnServiceAdapter.EXTRA_CONFIG_ID, it) }
            }
            
            getApplication<Application>().startForegroundService(intent)
        }
    }
    
    /**
     * Disconnect VPN
     */
    fun disconnect() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            val intent = Intent(getApplication(), HyperVpnServiceAdapter::class.java).apply {
                action = HyperVpnServiceAdapter.ACTION_STOP
            }
            
            getApplication<Application>().startService(intent)
        }
    }
    
    /**
     * Retry after error
     */
    fun retry() {
        viewModelScope.launch {
            service?.let {
                val orchestrator = (it as? HyperVpnServiceAdapter.LocalBinder)
                    ?.getOrchestrator()
                orchestrator?.retry()
            }
        }
    }
    
    /**
     * Dismiss error
     */
    fun dismissError() {
        viewModelScope.launch {
            service?.let {
                val orchestrator = (it as? HyperVpnServiceAdapter.LocalBinder)
                    ?.getOrchestrator()
                orchestrator?.dismissError()
            }
        }
    }
    
    // ===== Private Methods =====
    
    private fun bindService() {
        val intent = Intent(getApplication(), HyperVpnServiceAdapter::class.java)
        getApplication<Application>().bindService(
            intent,
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }
    
    private fun observeServiceState(service: HyperVpnServiceAdapter) {
        viewModelScope.launch {
            service.state.collect { state ->
                _uiState.update { currentUi ->
                    deriveUiState(state, currentUi)
                }
            }
        }
        
        // Observe telemetry for debugging
        viewModelScope.launch {
            service.telemetry.collect { event ->
                handleTelemetryEvent(event)
            }
        }
    }
    
    private fun deriveUiState(state: VpnLifecycleState, currentUi: VpnUiState): VpnUiState {
        return when (state) {
            is VpnLifecycleState.Idle -> VpnUiState(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                statusText = "Disconnected",
                canConnect = true,
                canDisconnect = false,
                isLoading = false
            )
            
            is VpnLifecycleState.Preparing -> VpnUiState(
                connectionStatus = ConnectionStatus.CONNECTING,
                statusText = "Preparing: ${state.phase.name.lowercase().replace("_", " ")}",
                progress = state.progress,
                canConnect = false,
                canDisconnect = true,
                isLoading = true
            )
            
            is VpnLifecycleState.Connecting -> VpnUiState(
                connectionStatus = ConnectionStatus.CONNECTING,
                statusText = "Connecting: ${state.phase.name.lowercase().replace("_", " ")}",
                progress = state.progress,
                canConnect = false,
                canDisconnect = true,
                isLoading = true,
                connectionAttempt = state.attempt
            )
            
            is VpnLifecycleState.Connected -> {
                val uptime = java.time.Duration.between(
                    state.connectionInfo.connectedAt,
                    Instant.now()
                )
                
                VpnUiState(
                    connectionStatus = ConnectionStatus.CONNECTED,
                    statusText = "Connected",
                    canConnect = false,
                    canDisconnect = true,
                    isLoading = false,
                    serverName = state.connectionInfo.serverName,
                    serverLocation = state.connectionInfo.serverLocation,
                    connectedAt = state.connectionInfo.connectedAt,
                    uptimeSeconds = uptime.seconds,
                    stats = state.stats,
                    healthStatus = state.healthStatus
                )
            }
            
            is VpnLifecycleState.Reconnecting -> VpnUiState(
                connectionStatus = ConnectionStatus.RECONNECTING,
                statusText = "Reconnecting (${state.attempt}/${state.maxAttempts})...",
                progress = state.attempt.toFloat() / state.maxAttempts,
                canConnect = false,
                canDisconnect = true,
                isLoading = true,
                connectionAttempt = state.attempt,
                reconnectReason = state.reason.toDisplayString()
            )
            
            is VpnLifecycleState.Disconnecting -> VpnUiState(
                connectionStatus = ConnectionStatus.DISCONNECTING,
                statusText = "Disconnecting: ${state.phase.name.lowercase().replace("_", " ")}",
                progress = state.progress,
                canConnect = false,
                canDisconnect = false,
                isLoading = true
            )
            
            is VpnLifecycleState.Error -> VpnUiState(
                connectionStatus = ConnectionStatus.ERROR,
                statusText = "Error",
                canConnect = state.isRecoverable,
                canDisconnect = false,
                isLoading = false,
                error = state.error.toUiError(),
                recoveryOptions = state.recoveryOptions.map { it.toUiOption() }
            )
            
            is VpnLifecycleState.Suspended -> VpnUiState(
                connectionStatus = ConnectionStatus.SUSPENDED,
                statusText = "Suspended: ${state.reason.toDisplayString()}",
                canConnect = false,
                canDisconnect = true,
                isLoading = false,
                serverName = state.preservedState.connectionInfo.serverName
            )
        }
    }
    
    private fun handleTelemetryEvent(event: TelemetryEvent) {
        // Log or process telemetry events
        when (event) {
            is TelemetryEvent.StateTransition -> {
                // Could update debug info
            }
            is TelemetryEvent.TransitionError -> {
                // Could show toast or log
            }
            else -> {}
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
    }
}

// ===== UI State Models =====

/**
 * UI state for VPN screen
 */
data class VpnUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val statusText: String = "Disconnected",
    val progress: Float = 0f,
    val canConnect: Boolean = true,
    val canDisconnect: Boolean = false,
    val isLoading: Boolean = false,
    val serverName: String? = null,
    val serverLocation: String? = null,
    val connectedAt: Instant? = null,
    val uptimeSeconds: Long = 0,
    val stats: ConnectionStats? = null,
    val healthStatus: HealthStatus? = null,
    val error: UiError? = null,
    val recoveryOptions: List<UiRecoveryOption> = emptyList(),
    val connectionAttempt: Int = 0,
    val reconnectReason: String? = null
)

/**
 * Connection status for UI
 */
enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTING,
    ERROR,
    SUSPENDED
}

/**
 * UI-friendly error representation
 */
data class UiError(
    val title: String,
    val message: String,
    val code: Int,
    val isRecoverable: Boolean
)

/**
 * UI-friendly recovery option
 */
data class UiRecoveryOption(
    val id: String,
    val label: String,
    val description: String,
    val isAutomatic: Boolean
)

// ===== Extension Functions =====

private fun VpnError.toUiError(): UiError {
    return UiError(
        title = when (this) {
            is VpnError.PermissionDenied -> "Permission Required"
            is VpnError.TunCreationFailed -> "Connection Failed"
            is VpnError.NativeLibraryError -> "System Error"
            is VpnError.ConfigurationError -> "Configuration Error"
            is VpnError.ConnectionFailed -> "Connection Failed"
            is VpnError.ProtocolError -> "Protocol Error"
            is VpnError.NetworkError -> "Network Error"
        },
        message = this.message,
        code = this.code,
        isRecoverable = when (this) {
            is VpnError.PermissionDenied -> true
            is VpnError.NetworkError -> this.isTransient
            else -> true
        }
    )
}

private fun RecoveryOption.toUiOption(): UiRecoveryOption {
    return UiRecoveryOption(
        id = this::class.simpleName ?: "unknown",
        label = this.label,
        description = this.description,
        isAutomatic = this.isAutomatic
    )
}

private fun ReconnectReason.toDisplayString(): String {
    return when (this) {
        is ReconnectReason.NetworkChange -> "Network changed"
        is ReconnectReason.HandshakeTimeout -> "Handshake timeout"
        is ReconnectReason.PacketLoss -> "High packet loss"
        is ReconnectReason.ServerUnreachable -> "Server unreachable"
        is ReconnectReason.ProtocolError -> "Protocol error: $message"
        is ReconnectReason.UserRequested -> "User requested"
        is ReconnectReason.ScheduledRekey -> "Scheduled rekey"
    }
}

private fun SuspendReason.toDisplayString(): String {
    return when (this) {
        is SuspendReason.AppBackgrounded -> "App in background"
        is SuspendReason.DozeMode -> "Device in doze mode"
        is SuspendReason.LowBattery -> "Low battery"
        is SuspendReason.ThermalThrottling -> "Device overheating"
    }
}
