package com.hyperxray.an.domain.usecase

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import com.hyperxray.an.core.monitor.XrayStatsManager
import com.hyperxray.an.data.repository.SettingsRepository
import com.hyperxray.an.feature.dashboard.ConnectionState
import com.hyperxray.an.feature.dashboard.ConnectionStage
import com.hyperxray.an.feature.dashboard.DisconnectionStage
import com.hyperxray.an.vpn.HyperVpnService
import com.hyperxray.an.service.managers.XrayAssetManager
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
 * 
 * Note: This version is simplified for HyperVpnService which uses native Go tunnel.
 * SOCKS5 readiness and multi-instance tracking have been removed.
 */
class VpnConnectionUseCase(
    private val context: Context,
    private val scope: CoroutineScope,
    private val xrayStatsManager: XrayStatsManager,
    private val settingsRepository: SettingsRepository,
    // State observers from ViewModel
    private val isServiceEnabled: Flow<Boolean>,
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
     * Coordinates all connection stages: INITIALIZING -> STARTING_VPN -> ESTABLISHING -> VERIFYING -> CONNECTED
     * 
     * Note: STARTING_XRAY stage has been removed since HyperVpnService uses native Go tunnel.
     */
    fun connect() {
        // Cancel any existing connection attempt
        connectionJob?.cancel()
        retryCountdownJob?.cancel()
        
        val connectionStartTime = System.currentTimeMillis()
        AiLogHelper.i(TAG, "🔌 CONNECTION START: Beginning connection process")
        
        connectionJob = scope.launch {
            try {
                // Cancel any existing retry countdown
                val currentState = _connectionState.value
                if (currentState is ConnectionState.Failed) {
                    // Reset countdown when manually retrying
                    _connectionState.value = currentState.copy(retryCountdownSeconds = null)
                    AiLogHelper.d(TAG, "🔄 CONNECTION RETRY: Resetting failed state for manual retry")
                }
                
                _connectionState.value = ConnectionState.Connecting(ConnectionStage.INITIALIZING, progress = 0.0f)
                
                // STAGE 1: INITIALIZING - Check prerequisites
                val stage1StartTime = System.currentTimeMillis()
                AiLogHelper.i(TAG, "📋 STAGE 1 [INITIALIZING]: Starting prerequisites check (progress: 0%)")
                
                val configFile = selectedConfigFile.first()
                if (configFile == null) {
                    val errorMsg = "No config file selected"
                    AiLogHelper.e(TAG, "❌ STAGE 1 [INITIALIZING] FAILED: $errorMsg")
                    _connectionState.value = ConnectionState.Failed(
                        error = errorMsg,
                        retryCountdownSeconds = null
                    )
                    return@launch
                }
                AiLogHelper.d(TAG, "✅ STAGE 1 [INITIALIZING]: Config file found: ${configFile.name} (${configFile.length()} bytes)")
                
                // Check internet connectivity before starting VPN
                Log.i(TAG, "INITIALIZING: Checking internet connectivity...")
                AiLogHelper.d(TAG, "🌐 STAGE 1 [INITIALIZING]: Checking internet connectivity...")
                val internetCheck = hasActiveInternetConnection()
                if (!internetCheck) {
                    val errorMsg = "INITIALIZING FAILED: device has no active internet connection - stopping entire connection process"
                    Log.e(TAG, errorMsg)
                    AiLogHelper.e(TAG, "❌ STAGE 1 [INITIALIZING] FAILED: $errorMsg")
                    _connectionState.value = ConnectionState.Failed(
                        error = "No internet connection available",
                        retryCountdownSeconds = null
                    )
                    // Stop service if it was already started (defensive check)
                    if (isServiceEnabled.first()) {
                        Log.w(TAG, "INITIALIZING: Service was already enabled, stopping it due to no internet")
                        AiLogHelper.w(TAG, "⚠️ STAGE 1 [INITIALIZING]: Service was already enabled, stopping it due to no internet")
                        stopService()
                    }
                    return@launch
                }
                val stage1Duration = System.currentTimeMillis() - stage1StartTime
                Log.i(TAG, "INITIALIZING: Internet connection verified - proceeding to VPN start")
                AiLogHelper.i(TAG, "✅ STAGE 1 [INITIALIZING] COMPLETED: Internet connection verified (duration: ${stage1Duration}ms)")
                
                delay(200) // Brief delay to show initializing stage
                
                // STAGE 2: STARTING_VPN - VPN service starting
                val stage2StartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Connecting(ConnectionStage.STARTING_VPN, progress = 0.2f)
                AiLogHelper.i(TAG, "🚀 STAGE 2 [STARTING_VPN]: Starting VPN service (progress: 20%)")
                
                // Start the service
                AiLogHelper.d(TAG, "📤 STAGE 2 [STARTING_VPN]: Sending ACTION_CONNECT intent to HyperVpnService")
                startService()
                
                // Wait for service to be enabled (with timeout)
                AiLogHelper.d(TAG, "⏳ STAGE 2 [STARTING_VPN]: Waiting for service to be enabled (timeout: 10s)...")
                val serviceEnabled = withTimeoutOrNull(10000L) {
                    isServiceEnabled.filter { it }.first()
                }
                
                if (serviceEnabled == null) {
                    val errorMsg = "Service did not start within timeout (10s)"
                    AiLogHelper.e(TAG, "❌ STAGE 2 [STARTING_VPN] FAILED: $errorMsg")
                    _connectionState.value = ConnectionState.Failed(
                        error = "Service did not start within timeout",
                        retryCountdownSeconds = null
                    )
                    return@launch
                }
                val stage2Duration = System.currentTimeMillis() - stage2StartTime
                AiLogHelper.i(TAG, "✅ STAGE 2 [STARTING_VPN] COMPLETED: VPN service started successfully (duration: ${stage2Duration}ms)")
                
                // STAGE 3: ESTABLISHING - Native Go tunnel establishing connection
                val stage3StartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Connecting(ConnectionStage.ESTABLISHING, progress = 0.5f)
                AiLogHelper.i(TAG, "🔌 STAGE 3 [ESTABLISHING]: Native Go tunnel establishing connection (progress: 50%)")
                
                // Wait for native tunnel to establish (native Go handles this internally)
                // We just give it some time and check service status
                delay(2000) // Allow native tunnel time to establish
                
                val stage3Duration = System.currentTimeMillis() - stage3StartTime
                AiLogHelper.i(TAG, "✅ STAGE 3 [ESTABLISHING] COMPLETED: Tunnel establishment initiated (duration: ${stage3Duration}ms)")
                
                // STAGE 4: VERIFYING - Final connection verification
                val stage4StartTime = System.currentTimeMillis()
                Log.i(TAG, "VERIFYING: Starting final connection verification")
                AiLogHelper.i(TAG, "🔍 STAGE 4 [VERIFYING]: Starting final connection verification (progress: 80%)")
                _connectionState.value = ConnectionState.Connecting(ConnectionStage.VERIFYING, progress = 0.8f)
                
                val finalServiceCheck = isServiceEnabled.first()
                
                Log.d(TAG, "VERIFYING: Service check=$finalServiceCheck")
                AiLogHelper.d(TAG, "🔍 STAGE 4 [VERIFYING]: Service=$finalServiceCheck")
                
                if (!finalServiceCheck) {
                    val errorMsg = "Service is not enabled (service=$finalServiceCheck)"
                    Log.e(TAG, "VERIFYING FAILED: $errorMsg")
                    AiLogHelper.e(TAG, "❌ STAGE 4 [VERIFYING] FAILED: $errorMsg")
                    val errorMessage = "Connection verification failed: service=$finalServiceCheck"
                    stopConnectionProcessAndSetFailed(errorMessage)
                    return@launch
                }
                
                val stage4Duration = System.currentTimeMillis() - stage4StartTime
                
                // SUCCESS - Show 100% progress briefly before switching to Connected
                _connectionState.value = ConnectionState.Connecting(ConnectionStage.VERIFYING, progress = 1.0f)
                delay(300)
                
                val totalDuration = System.currentTimeMillis() - connectionStartTime
                _connectionState.value = ConnectionState.Connected
                Log.d(TAG, "Connection process completed successfully")
                AiLogHelper.i(TAG, "✅ CONNECTION SUCCESS: All stages completed successfully (total duration: ${totalDuration}ms)")
                AiLogHelper.i(TAG, "📊 CONNECTION SUMMARY: Stage1=${stage1Duration}ms, Stage2=${stage2Duration}ms, Stage3=${stage3Duration}ms, Stage4=${stage4Duration}ms")
                
            } catch (e: TimeoutCancellationException) {
                val errorMsg = "Connection process timed out: ${e.message}"
                Log.e(TAG, errorMsg, e)
                AiLogHelper.e(TAG, "❌ CONNECTION TIMEOUT: $errorMsg")
                stopConnectionProcessAndSetFailed("Connection timed out: ${e.message}")
            } catch (e: Exception) {
                val errorMsg = "Connection process failed: ${e.message}"
                Log.e(TAG, errorMsg, e)
                AiLogHelper.e(TAG, "❌ CONNECTION FAILED: $errorMsg", e)
                stopConnectionProcessAndSetFailed("Connection failed: ${e.message}")
            }
        }
    }
    
    /**
     * Stops the connection process.
     * Handles all disconnection stages: CLOSING_TUNNEL -> STOPPING_VPN -> CLEANING_UP -> DISCONNECTED
     */
    fun disconnect() {
        connectionJob?.cancel()
        retryCountdownJob?.cancel()
        
        val disconnectStartTime = System.currentTimeMillis()
        AiLogHelper.i(TAG, "🔌 DISCONNECTION START: Beginning disconnection process")
        
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
                        AiLogHelper.d(TAG, "🔌 DISCONNECTION: Already disconnected, setting to Disconnected state")
                    } else {
                        Log.d(TAG, "disconnect: Connection state is Failed, keeping Failed state")
                        AiLogHelper.d(TAG, "🔌 DISCONNECTION: Connection state is Failed, keeping Failed state")
                    }
                    return@launch
                }
                
                // STAGE 1: CLOSING_TUNNEL - Closing network tunnel (optimized: no delay)
                val discStage1StartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.CLOSING_TUNNEL,
                    progress = 0.0f
                )
                AiLogHelper.i(TAG, "🔒 DISC STAGE 1 [CLOSING_TUNNEL]: Closing network tunnel (progress: 0%)")
                // No delay - immediate transition
                val discStage1Duration = System.currentTimeMillis() - discStage1StartTime
                AiLogHelper.i(TAG, "✅ DISC STAGE 1 [CLOSING_TUNNEL] COMPLETED: Network tunnel closed (duration: ${discStage1Duration}ms)")
                
                // STAGE 2: STOPPING_VPN - Stopping VPN service (optimized: don't wait, service stops in background)
                val discStage2StartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.STOPPING_VPN,
                    progress = 0.4f
                )
                AiLogHelper.i(TAG, "🛑 DISC STAGE 2 [STOPPING_VPN]: Stopping VPN service (progress: 40%)")
                
                // Stop the service (fire-and-forget, don't wait)
                AiLogHelper.d(TAG, "📤 DISC STAGE 2 [STOPPING_VPN]: Sending ACTION_DISCONNECT intent to HyperVpnService")
                stopService()
                
                // Don't wait for service to be disabled - service stops in background
                // UI should update immediately, service state will update via hyperVpnState
                val discStage2Duration = System.currentTimeMillis() - discStage2StartTime
                AiLogHelper.i(TAG, "✅ DISC STAGE 2 [STOPPING_VPN] COMPLETED: Disconnect command sent (duration: ${discStage2Duration}ms), service stopping in background")
                
                // STAGE 3: CLEANING_UP - Final cleanup (optimized: no delays)
                val discStage3StartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.CLEANING_UP,
                    progress = 0.8f
                )
                AiLogHelper.i(TAG, "🧹 DISC STAGE 3 [CLEANING_UP]: Final cleanup (progress: 80%)")
                // No delay - immediate transition to disconnected
                
                val discStage3Duration = System.currentTimeMillis() - discStage3StartTime
                val totalDisconnectDuration = System.currentTimeMillis() - disconnectStartTime
                
                // SUCCESS - Complete disconnection immediately
                _connectionState.value = ConnectionState.Disconnected
                Log.d(TAG, "Disconnection process completed successfully")
                AiLogHelper.i(TAG, "✅ DISCONNECTION SUCCESS: All stages completed successfully (total duration: ${totalDisconnectDuration}ms)")
                AiLogHelper.i(TAG, "📊 DISCONNECTION SUMMARY: Stage1=${discStage1Duration}ms, Stage2=${discStage2Duration}ms, Stage3=${discStage3Duration}ms")
                
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
                // STAGE 1: CLOSING_TUNNEL - Closing network tunnel (optimized: no delay)
                Log.d(TAG, "stopConnectionProcessAndSetFailed: STAGE 1 - Closing network tunnel")
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.CLOSING_TUNNEL,
                    progress = 0.0f
                )
                // No delay - immediate transition
                
                // STAGE 2: STOPPING_VPN - Stopping VPN service (optimized: 2s timeout)
                Log.d(TAG, "stopConnectionProcessAndSetFailed: STAGE 2 - Stopping VPN service")
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.STOPPING_VPN,
                    progress = 0.4f
                )
                
                // Stop the service (fire-and-forget, don't wait)
                stopService()
                
                // Don't wait for service to be disabled - service stops in background
                // UI should update immediately, service state will update via hyperVpnState
                
                // STAGE 3: CLEANING_UP - Final cleanup (optimized: no delays)
                Log.d(TAG, "stopConnectionProcessAndSetFailed: STAGE 3 - Cleaning up")
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.CLEANING_UP,
                    progress = 0.8f
                )
                // No delay - immediate transition to Failed
                
                // Set to Failed state with retry countdown (initially null, button active)
                Log.i(TAG, "stopConnectionProcessAndSetFailed: Disconnection complete, setting to Failed state")
                _connectionState.value = ConnectionState.Failed(
                    error = errorMessage,
                    retryCountdownSeconds = null // Button active, countdown not started yet
                )
                
                // Start retry countdown after a brief delay
                delay(1000L)
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
     */
    private fun startRetryCountdown() {
        Log.i(TAG, "startRetryCountdown: Retry countdown disabled to prevent disconnect loops. User must manually retry.")
        retryCountdownJob?.cancel()
        
        retryCountdownJob = scope.launch {
            var countdown = 3
            while (countdown > 0) {
                val currentState = _connectionState.value
                if (currentState !is ConnectionState.Failed) {
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
     * Starts the VPN service.
     */
    private fun startService() {
        val intent = Intent(context, HyperVpnService::class.java).apply {
            action = HyperVpnService.ACTION_CONNECT
        }
        onStartService(intent)
    }
    
    /**
     * Stops the VPN service.
     */
    private fun stopService() {
        val intent = Intent(context, HyperVpnService::class.java).apply {
            action = HyperVpnService.ACTION_DISCONNECT
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
