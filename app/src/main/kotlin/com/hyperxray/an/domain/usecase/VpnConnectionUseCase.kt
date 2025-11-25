package com.hyperxray.an.domain.usecase

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Log
import com.hyperxray.an.core.monitor.XrayStatsManager
import com.hyperxray.an.data.repository.SettingsRepository
import com.hyperxray.an.feature.dashboard.ConnectionState
import com.hyperxray.an.feature.dashboard.ConnectionStage
import com.hyperxray.an.feature.dashboard.DisconnectionStage
import com.hyperxray.an.service.TProxyService
import com.hyperxray.an.xray.runtime.XrayRuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File

/**
 * Use case for managing VPN connection lifecycle.
 * Coordinates connection state machine, handles retry countdown, and verifies connections.
 * 
 * This use case encapsulates the business logic for:
 * - Starting connection process (Service Intent -> Broadcast Receiver -> Verification)
 * - Stopping connection process
 * - Retry countdown when connection fails
 * - Connection verification via XrayStatsManager
 */
class VpnConnectionUseCase(
    private val context: Context,
    private val scope: CoroutineScope,
    private val xrayStatsManager: XrayStatsManager,
    private val settingsRepository: SettingsRepository,
    // State observers from ViewModel
    private val isServiceEnabled: Flow<Boolean>,
    private val socks5Ready: Flow<Boolean>,
    private val instancesStatus: Flow<Map<Int, XrayRuntimeStatus>>,
    private val selectedConfigFile: Flow<File?>,
    // Callbacks for service control
    private val onStartService: (Intent) -> Unit,
    private val onStopService: (Intent) -> Unit
) {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private var connectionJob: Job? = null
    private var retryCountdownJob: Job? = null
    
    companion object {
        private const val TAG = "VpnConnectionUseCase"
    }
    
    /**
     * Starts the connection process.
     * Coordinates all connection stages: INITIALIZING -> STARTING_VPN -> STARTING_XRAY -> ESTABLISHING -> VERIFYING -> CONNECTED
     */
    fun connect() {
        // Cancel any existing connection attempt
        connectionJob?.cancel()
        retryCountdownJob?.cancel()
        
        connectionJob = scope.launch {
            try {
                // Cancel any existing retry countdown
                val currentState = _connectionState.value
                if (currentState is ConnectionState.Failed) {
                    // Reset countdown when manually retrying
                    _connectionState.value = currentState.copy(retryCountdownSeconds = null)
                }
                
                _connectionState.value = ConnectionState.Connecting(ConnectionStage.INITIALIZING, progress = 0.0f)
                
                // STAGE 1: INITIALIZING - Check prerequisites
                val configFile = selectedConfigFile.first()
                if (configFile == null) {
                    _connectionState.value = ConnectionState.Failed(
                        error = "No config file selected",
                        retryCountdownSeconds = null
                    )
                    return@launch
                }
                
                // Check internet connectivity before starting VPN
                Log.i(TAG, "INITIALIZING: Checking internet connectivity...")
                val internetCheck = hasActiveInternetConnection()
                if (!internetCheck) {
                    Log.e(TAG, "INITIALIZING FAILED: device has no active internet connection - stopping entire connection process")
                    _connectionState.value = ConnectionState.Failed(
                        error = "No internet connection available",
                        retryCountdownSeconds = null
                    )
                    // Stop service if it was already started (defensive check)
                    if (isServiceEnabled.first()) {
                        Log.w(TAG, "INITIALIZING: Service was already enabled, stopping it due to no internet")
                        stopService()
                    }
                    return@launch
                }
                Log.i(TAG, "INITIALIZING: Internet connection verified - proceeding to VPN start")
                
                delay(200) // Brief delay to show initializing stage
                
                // STAGE 2: STARTING_VPN - VPN service starting
                _connectionState.value = ConnectionState.Connecting(ConnectionStage.STARTING_VPN, progress = 0.2f)
                
                // Start the service
                startService()
                
                // Wait for service to be enabled (with timeout)
                val serviceEnabled = withTimeoutOrNull(10000L) {
                    isServiceEnabled.filter { it }.first()
                }
                
                if (serviceEnabled == null) {
                    _connectionState.value = ConnectionState.Failed(
                        error = "Service did not start within timeout",
                        retryCountdownSeconds = null
                    )
                    return@launch
                }
                
                // STAGE 3: STARTING_XRAY - Xray instances starting
                _connectionState.value = ConnectionState.Connecting(ConnectionStage.STARTING_XRAY, progress = 0.4f)
                
                val instanceStatus = withTimeoutOrNull(20000L) {
                    instancesStatus.filter { statusMap ->
                        statusMap.values.any { it is XrayRuntimeStatus.Running }
                    }.first()
                }
                
                // Verify we have running instances but fall back to later stages if unavailable yet
                val runningInstances = instanceStatus
                    ?.values
                    ?.filterIsInstance<XrayRuntimeStatus.Running>()
                    ?: instancesStatus.first().values.filterIsInstance<XrayRuntimeStatus.Running>()
                
                if (runningInstances.isEmpty()) {
                    Log.w(
                        TAG,
                        "Stage 3 (STARTING_XRAY): Did not observe running instance status within 20s. " +
                            "Will continue and rely on SOCKS5 readiness."
                    )
                } else {
                    Log.d(
                        TAG,
                        "Stage 3 (STARTING_XRAY): Completed - Xray instances running (${runningInstances.size} instances)"
                    )
                }
                
                // STAGE 4: ESTABLISHING - SOCKS5 becoming ready
                _connectionState.value = ConnectionState.Connecting(ConnectionStage.ESTABLISHING, progress = 0.6f)
                Log.i(TAG, "ESTABLISHING: Waiting for SOCKS5 to become ready...")
                
                // Check if SOCKS5 is already ready (race condition fix)
                val socks5ReadyValue = if (socks5Ready.first()) {
                    Log.d(TAG, "ESTABLISHING: SOCKS5 already ready (checked before ESTABLISHING stage)")
                    true
                } else {
                    // Wait for SOCKS5 to become ready with extended timeout (30 seconds)
                    // Xray process may need time to start and open SOCKS5 port
                    Log.d(TAG, "ESTABLISHING: SOCKS5 not ready yet, waiting up to 30 seconds...")
                    val result = withTimeoutOrNull(30000L) {
                        socks5Ready.filter { it }.first()
                    }
                    if (result == null) {
                        Log.w(TAG, "ESTABLISHING: SOCKS5 did not become ready within 30 second timeout")
                    }
                    result ?: false
                }
                
                if (!socks5ReadyValue) {
                    Log.w(TAG, "ESTABLISHING: SOCKS5 did not become ready within timeout, but continuing verification")
                } else {
                    Log.i(TAG, "ESTABLISHING: SOCKS5 is ready, proceeding to VERIFYING stage")
                }
                
                // STAGE 5: VERIFYING - Final connection verification
                Log.i(TAG, "VERIFYING: Starting final connection verification")
                _connectionState.value = ConnectionState.Connecting(ConnectionStage.VERIFYING, progress = 0.8f)
                
                val finalServiceCheck = isServiceEnabled.first()
                val finalSocks5Check = socks5Ready.first()
                val finalInstancesCheck = instancesStatus.first().values.any { it is XrayRuntimeStatus.Running }
                val readinessSatisfied = finalSocks5Check || finalInstancesCheck
                
                Log.d(TAG, "VERIFYING: Service check=$finalServiceCheck, SOCKS5=$finalSocks5Check, Instances=$finalInstancesCheck, Readiness=$readinessSatisfied")
                
                if (!finalServiceCheck) {
                    Log.e(TAG, "VERIFYING FAILED: Service is not enabled (service=$finalServiceCheck)")
                    val errorMessage = "Connection verification failed: service=$finalServiceCheck"
                    stopConnectionProcessAndSetFailed(errorMessage)
                    return@launch
                }
                if (!readinessSatisfied) {
                    Log.e(TAG, "VERIFYING FAILED: Readiness not satisfied (socks5=$finalSocks5Check, instances=$finalInstancesCheck)")
                    val errorMessage = "Connection verification failed: socks5=$finalSocks5Check, instances=$finalInstancesCheck"
                    stopConnectionProcessAndSetFailed(errorMessage)
                    return@launch
                }
                
                // Running instance bul ve gRPC bağlantı testi yap
                Log.d(TAG, "VERIFYING: Searching for running Xray instances...")
                val runningInstance = instancesStatus.first().values
                    .filterIsInstance<XrayRuntimeStatus.Running>()
                    .firstOrNull()
                
                if (runningInstance != null) {
                    Log.i(TAG, "VERIFYING: Found running instance - PID=${runningInstance.processId}, API Port=${runningInstance.apiPort}")
                    Log.d(TAG, "VERIFYING: Starting gRPC connection verification via XrayStatsManager...")
                    val connectionVerified = withContext(Dispatchers.IO) {
                        xrayStatsManager.verifyXrayConnection(runningInstance.apiPort)
                    }
                    if (!connectionVerified) {
                        Log.e(TAG, "VERIFYING FAILED: gRPC connection verification unsuccessful (port=${runningInstance.apiPort})")
                        val errorMessage = "Xray server connection verification failed - gRPC test unsuccessful"
                        stopConnectionProcessAndSetFailed(errorMessage)
                        return@launch
                    }
                    Log.i(TAG, "VERIFYING SUCCESS: Xray server connection verified successfully via gRPC")
                } else {
                    Log.w(TAG, "VERIFYING WARNING: No running Xray instance found for connection verification")
                    Log.d(TAG, "VERIFYING: Available instances: ${instancesStatus.first()}")
                }
                
                // SUCCESS - Show 100% progress briefly before switching to Connected
                _connectionState.value = ConnectionState.Connecting(ConnectionStage.VERIFYING, progress = 1.0f)
                delay(300)
                
                _connectionState.value = ConnectionState.Connected
                Log.d(TAG, "Connection process completed successfully")
                
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Connection process timed out", e)
                stopConnectionProcessAndSetFailed("Connection timed out: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Connection process failed", e)
                stopConnectionProcessAndSetFailed("Connection failed: ${e.message}")
            }
        }
    }
    
    /**
     * Stops the connection process.
     * Handles all disconnection stages: STOPPING_XRAY -> CLOSING_TUNNEL -> STOPPING_VPN -> CLEANING_UP -> DISCONNECTED
     */
    fun disconnect() {
        connectionJob?.cancel()
        retryCountdownJob?.cancel()
        
        connectionJob = scope.launch {
            try {
                val currentState = _connectionState.value
                
                // Only start disconnection process if we're currently connected or connecting
                if (currentState !is ConnectionState.Connected &&
                    currentState !is ConnectionState.Connecting) {
                    // Already disconnected or in another state
                    // If Failed, keep it Failed. Otherwise set to Disconnected
                    if (currentState !is ConnectionState.Failed) {
                        _connectionState.value = ConnectionState.Disconnected
                    } else {
                        Log.d(TAG, "disconnect: Connection state is Failed, keeping Failed state")
                    }
                    return@launch
                }
                
                // STAGE 1: STOPPING_XRAY - Stopping Xray instances
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.STOPPING_XRAY,
                    progress = 0.0f
                )
                delay(300) // Brief delay to show stage
                
                // Wait for instances to stop (with timeout)
                val instancesStopped = withTimeoutOrNull(5000L) {
                    instancesStatus.filter { statusMap ->
                        statusMap.values.none { it is XrayRuntimeStatus.Running }
                    }.first()
                }
                
                // STAGE 2: CLOSING_TUNNEL - Closing network tunnel
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.CLOSING_TUNNEL,
                    progress = 0.3f
                )
                delay(200)
                
                // STAGE 3: STOPPING_VPN - Stopping VPN service
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.STOPPING_VPN,
                    progress = 0.6f
                )
                
                // Stop the service
                stopService()
                
                // Wait for service to be disabled (with timeout)
                val serviceDisabled = withTimeoutOrNull(5000L) {
                    isServiceEnabled.filter { !it }.first()
                }
                
                // STAGE 4: CLEANING_UP - Final cleanup
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.CLEANING_UP,
                    progress = 0.9f
                )
                delay(200)
                
                // SUCCESS - Complete disconnection
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.CLEANING_UP,
                    progress = 1.0f
                )
                delay(200)
                
                _connectionState.value = ConnectionState.Disconnected
                Log.d(TAG, "Disconnection process completed successfully")
                
            } catch (e: TimeoutCancellationException) {
                // Even if timeout, set to disconnected
                _connectionState.value = ConnectionState.Disconnected
                Log.w(TAG, "Disconnection process timed out, but setting to Disconnected", e)
            } catch (e: Exception) {
                // Even if error, set to disconnected
                _connectionState.value = ConnectionState.Disconnected
                Log.e(TAG, "Disconnection process failed, but setting to Disconnected", e)
            }
        }
    }
    
    /**
     * Handles disconnection process when connection fails.
     * Performs all disconnection stages and then sets state to Failed with retry countdown.
     */
    private fun stopConnectionProcessAndSetFailed(errorMessage: String) {
        Log.i(TAG, "stopConnectionProcessAndSetFailed: Starting disconnection process due to failure: $errorMessage")
        connectionJob = scope.launch {
            try {
                // STAGE 1: STOPPING_XRAY - Stopping Xray instances
                Log.d(TAG, "stopConnectionProcessAndSetFailed: STAGE 1 - Stopping Xray instances")
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.STOPPING_XRAY,
                    progress = 0.0f
                )
                delay(300) // Brief delay to show stage
                
                // Wait for instances to stop (with timeout)
                val instancesStopped = withTimeoutOrNull(5000L) {
                    instancesStatus.filter { statusMap ->
                        statusMap.values.none { it is XrayRuntimeStatus.Running }
                    }.first()
                }
                
                // STAGE 2: CLOSING_TUNNEL - Closing network tunnel
                Log.d(TAG, "stopConnectionProcessAndSetFailed: STAGE 2 - Closing network tunnel")
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.CLOSING_TUNNEL,
                    progress = 0.3f
                )
                delay(200)
                
                // STAGE 3: STOPPING_VPN - Stopping VPN service
                Log.d(TAG, "stopConnectionProcessAndSetFailed: STAGE 3 - Stopping VPN service")
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.STOPPING_VPN,
                    progress = 0.6f
                )
                
                // Stop the service
                stopService()
                
                // Wait for service to be disabled (with timeout)
                val serviceDisabled = withTimeoutOrNull(5000L) {
                    isServiceEnabled.filter { !it }.first()
                }
                
                // STAGE 4: CLEANING_UP - Final cleanup
                Log.d(TAG, "stopConnectionProcessAndSetFailed: STAGE 4 - Cleaning up")
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.CLEANING_UP,
                    progress = 0.9f
                )
                delay(200)
                
                // SUCCESS - Complete disconnection, then set to Failed
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.CLEANING_UP,
                    progress = 1.0f
                )
                delay(200)
                
                // Set to Failed state with retry countdown (initially null, buton aktif)
                Log.i(TAG, "stopConnectionProcessAndSetFailed: Disconnection complete, setting to Failed state")
                _connectionState.value = ConnectionState.Failed(
                    error = errorMessage,
                    retryCountdownSeconds = null // Buton aktif, countdown henüz başlamadı
                )
                
                // Start retry countdown after a brief delay (buton aktif kaldıktan sonra)
                delay(1000L) // 1 saniye gecikme, butonun aktif olduğunu görmek için
                startRetryCountdown()
                
            } catch (e: TimeoutCancellationException) {
                // Even if timeout, set to Failed
                Log.w(TAG, "stopConnectionProcessAndSetFailed: Disconnection timed out, setting to Failed", e)
                _connectionState.value = ConnectionState.Failed(
                    error = errorMessage,
                    retryCountdownSeconds = null
                )
                startRetryCountdown()
            } catch (e: Exception) {
                // Even if error, set to Failed
                Log.e(TAG, "stopConnectionProcessAndSetFailed: Disconnection failed, setting to Failed", e)
                _connectionState.value = ConnectionState.Failed(
                    error = errorMessage,
                    retryCountdownSeconds = null
                )
                startRetryCountdown()
            }
        }
    }
    
    /**
     * Handles retry countdown when connection fails.
     * DISABLED: Automatic retry is disabled to prevent disconnect loops.
     * Retry should be done manually by the user via UI button.
     * Buton aktif olduktan sonra countdown başlar ama otomatik retry yapılmaz.
     */
    private fun startRetryCountdown() {
        Log.i(TAG, "startRetryCountdown: Retry countdown disabled to prevent disconnect loops. User must manually retry.")
        // Automatic retry is disabled to prevent disconnect loops
        // User must manually retry via UI button
        retryCountdownJob?.cancel()
        
        // Optionally show countdown in UI but don't auto-retry
        // This allows UI to show countdown but prevents automatic reconnection
        retryCountdownJob = scope.launch {
            var countdown = 3
            while (countdown > 0) {
                val currentState = _connectionState.value
                if (currentState !is ConnectionState.Failed) {
                    // State changed, stop countdown
                    Log.d(TAG, "startRetryCountdown: State changed, stopping countdown")
                    return@launch
                }
                
                Log.d(TAG, "startRetryCountdown: Countdown: $countdown seconds remaining (auto-retry disabled)")
                _connectionState.value = currentState.copy(retryCountdownSeconds = countdown)
                delay(1000L)
                countdown--
            }
            
            // Countdown finished, but DO NOT auto-retry
            val finalState = _connectionState.value
            if (finalState is ConnectionState.Failed) {
                Log.i(TAG, "startRetryCountdown: Countdown finished, but auto-retry is disabled. User must manually retry.")
                _connectionState.value = finalState.copy(retryCountdownSeconds = null)
                // DO NOT call connect() here - let user manually retry
            } else {
                Log.d(TAG, "startRetryCountdown: State is no longer Failed, skipping retry")
            }
        }
    }
    
    /**
     * Checks if device has active internet connection (not just VPN).
     */
    private fun hasActiveInternetConnection(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                Log.w(TAG, "ConnectivityManager is null")
                return false
            }
            
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork == null) {
                Log.w(TAG, "No active network found")
                return false
            }
            
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            if (networkCapabilities == null) {
                Log.w(TAG, "No network capabilities found for active network")
                return false
            }
            
            val hasInternetCapability = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val hasRealTransport = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                  networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                  networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val isVpnNetwork = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            
            val result = if (isVpnNetwork) {
                hasInternetCapability
            } else {
                hasInternetCapability && hasRealTransport
            }
            
            Log.d(TAG, "Internet check: hasInternet=$hasInternetCapability, validated=$isValidated, hasTransport=$hasRealTransport, isVpn=$isVpnNetwork, result=$result")
            
            result
        } catch (e: Exception) {
            Log.w(TAG, "Error checking internet connectivity: ${e.message}", e)
            false
        }
    }
    
    
    /**
     * Starts the TProxy service.
     */
    private fun startService() {
        val intent = Intent(context, TProxyService::class.java).apply {
            action = TProxyService.ACTION_CONNECT
        }
        onStartService(intent)
    }
    
    /**
     * Stops the TProxy service.
     */
    private fun stopService() {
        val intent = Intent(context, TProxyService::class.java).apply {
            action = TProxyService.ACTION_DISCONNECT
        }
        onStopService(intent)
    }
    
    /**
     * Cleans up resources when use case is no longer needed.
     */
    fun cleanup() {
        connectionJob?.cancel()
        retryCountdownJob?.cancel()
    }
}

