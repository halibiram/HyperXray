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
import com.hyperxray.an.service.TProxyService
import com.hyperxray.an.service.managers.XrayAssetManager
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
                AiLogHelper.d(TAG, "📤 STAGE 2 [STARTING_VPN]: Sending ACTION_CONNECT intent to TProxyService")
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
                
                // STAGE 3: STARTING_XRAY - Xray instances starting
                val stage3StartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Connecting(ConnectionStage.STARTING_XRAY, progress = 0.4f)
                AiLogHelper.i(TAG, "⚡ STAGE 3 [STARTING_XRAY]: Starting Xray instances (progress: 40%)")
                
                // Step 3a: Check critical assets before process start
                AiLogHelper.d(TAG, "🔍 STAGE 3 [STARTING_XRAY]: Checking critical assets...")
                try {
                    val assetManager = XrayAssetManager(context)
                    assetManager.checkAssets(configFile.absolutePath)
                    AiLogHelper.d(TAG, "✅ STAGE 3 [STARTING_XRAY]: All assets verified")
                } catch (e: IllegalStateException) {
                    val errorMsg = "Asset check failed: ${e.message}"
                    Log.e(TAG, "❌ $errorMsg", e)
                    AiLogHelper.e(TAG, "❌ STAGE 3 [STARTING_XRAY] FAILED: $errorMsg")
                    _connectionState.value = ConnectionState.Failed(
                        error = "Missing required assets: ${e.message}",
                        retryCountdownSeconds = null
                    )
                    return@launch
                } catch (e: Exception) {
                    val errorMsg = "Asset check error: ${e.message}"
                    Log.e(TAG, "❌ $errorMsg", e)
                    AiLogHelper.e(TAG, "❌ STAGE 3 [STARTING_XRAY] FAILED: $errorMsg")
                    _connectionState.value = ConnectionState.Failed(
                        error = "Asset verification failed: ${e.message}",
                        retryCountdownSeconds = null
                    )
                    return@launch
                }
                
                // Step 3b: Monitor Xray instance startup
                AiLogHelper.d(TAG, "⏳ STAGE 3 [STARTING_XRAY]: Monitoring Xray instance status (timeout: 20s)...")
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
                    val warningMsg = "Did not observe running instance status within 20s. Will continue and rely on SOCKS5 readiness."
                    Log.w(TAG, "Stage 3 (STARTING_XRAY): $warningMsg")
                    AiLogHelper.w(TAG, "⚠️ STAGE 3 [STARTING_XRAY] WARNING: $warningMsg")
                } else {
                    val instancesInfo = runningInstances.joinToString(", ") { "PID=${it.processId}, API=${it.apiPort}" }
                    Log.d(TAG, "Stage 3 (STARTING_XRAY): Xray instances started - $instancesInfo")
                    AiLogHelper.i(TAG, "✅ STAGE 3 [STARTING_XRAY]: ${runningInstances.size} Xray instance(s) started - $instancesInfo")
                }
                
                // Step 3c: Stabilization delay and liveness check
                AiLogHelper.d(TAG, "⏳ STAGE 3 [STARTING_XRAY]: Stabilization delay (500ms) and liveness check...")
                delay(500) // Stabilization delay
                
                // Check if processes are still alive after stabilization
                val currentInstancesStatus = instancesStatus.first()
                val aliveInstances = currentInstancesStatus.values.filterIsInstance<XrayRuntimeStatus.Running>()
                val deadInstances = currentInstancesStatus.values.filterIsInstance<XrayRuntimeStatus.ProcessExited>()
                
                if (deadInstances.isNotEmpty()) {
                    val deadInfo = deadInstances.joinToString(", ") { 
                        "Instance exit code ${it.exitCode}: ${it.message ?: "Unknown error"}"
                    }
                    val errorMsg = "Xray process(es) died during startup: $deadInfo"
                    Log.e(TAG, "❌ $errorMsg")
                    AiLogHelper.e(TAG, "❌ STAGE 3 [STARTING_XRAY] FAILED: $errorMsg")
                    _connectionState.value = ConnectionState.Failed(
                        error = "Xray process terminated unexpectedly: ${deadInstances.first().message ?: "Exit code ${deadInstances.first().exitCode}"}",
                        retryCountdownSeconds = null
                    )
                    return@launch
                }
                
                if (aliveInstances.isEmpty()) {
                    val errorMsg = "No running Xray instances found after stabilization delay"
                    Log.e(TAG, "❌ $errorMsg")
                    AiLogHelper.e(TAG, "❌ STAGE 3 [STARTING_XRAY] FAILED: $errorMsg")
                    _connectionState.value = ConnectionState.Failed(
                        error = "Xray instances failed to start",
                        retryCountdownSeconds = null
                    )
                    return@launch
                }
                
                val stage3Duration = System.currentTimeMillis() - stage3StartTime
                val instancesInfo = aliveInstances.joinToString(", ") { "PID=${it.processId}, API=${it.apiPort}" }
                Log.i(TAG, "Stage 3 (STARTING_XRAY): Completed - ${aliveInstances.size} Xray instance(s) running - $instancesInfo")
                AiLogHelper.i(TAG, "✅ STAGE 3 [STARTING_XRAY] COMPLETED: ${aliveInstances.size} Xray instance(s) running and verified - $instancesInfo (duration: ${stage3Duration}ms)")
                
                // STAGE 4: ESTABLISHING - SOCKS5 becoming ready
                val stage4StartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Connecting(ConnectionStage.ESTABLISHING, progress = 0.6f)
                Log.i(TAG, "ESTABLISHING: Waiting for SOCKS5 to become ready...")
                AiLogHelper.i(TAG, "🔌 STAGE 4 [ESTABLISHING]: Waiting for SOCKS5 to become ready (progress: 60%)")
                
                // Check if SOCKS5 is already ready (race condition fix)
                val socks5ReadyValue = if (socks5Ready.first()) {
                    Log.d(TAG, "ESTABLISHING: SOCKS5 already ready (checked before ESTABLISHING stage)")
                    AiLogHelper.d(TAG, "✅ STAGE 4 [ESTABLISHING]: SOCKS5 already ready (checked before ESTABLISHING stage)")
                    true
                } else {
                    // Wait for SOCKS5 to become ready with extended timeout (30 seconds)
                    // Xray process may need time to start and open SOCKS5 port
                    Log.d(TAG, "ESTABLISHING: SOCKS5 not ready yet, waiting up to 30 seconds...")
                    AiLogHelper.d(TAG, "⏳ STAGE 4 [ESTABLISHING]: SOCKS5 not ready yet, waiting up to 30 seconds...")
                    val result = withTimeoutOrNull(30000L) {
                        socks5Ready.filter { it }.first()
                    }
                    if (result == null) {
                        Log.w(TAG, "ESTABLISHING: SOCKS5 did not become ready within 30 second timeout")
                        AiLogHelper.w(TAG, "⚠️ STAGE 4 [ESTABLISHING]: SOCKS5 did not become ready within 30 second timeout")
                    }
                    result ?: false
                }
                
                val stage4Duration = System.currentTimeMillis() - stage4StartTime
                if (!socks5ReadyValue) {
                    Log.w(TAG, "ESTABLISHING: SOCKS5 did not become ready within timeout, but continuing verification")
                    AiLogHelper.w(TAG, "⚠️ STAGE 4 [ESTABLISHING]: SOCKS5 did not become ready within timeout, but continuing verification (duration: ${stage4Duration}ms)")
                } else {
                    Log.i(TAG, "ESTABLISHING: SOCKS5 is ready, proceeding to VERIFYING stage")
                    AiLogHelper.i(TAG, "✅ STAGE 4 [ESTABLISHING] COMPLETED: SOCKS5 is ready (duration: ${stage4Duration}ms)")
                }
                
                // STAGE 5: VERIFYING - Final connection verification
                val stage5StartTime = System.currentTimeMillis()
                Log.i(TAG, "VERIFYING: Starting final connection verification")
                AiLogHelper.i(TAG, "🔍 STAGE 5 [VERIFYING]: Starting final connection verification (progress: 80%)")
                _connectionState.value = ConnectionState.Connecting(ConnectionStage.VERIFYING, progress = 0.8f)
                
                val finalServiceCheck = isServiceEnabled.first()
                val finalSocks5Check = socks5Ready.first()
                val finalInstancesCheck = instancesStatus.first().values.any { it is XrayRuntimeStatus.Running }
                val readinessSatisfied = finalSocks5Check || finalInstancesCheck
                
                Log.d(TAG, "VERIFYING: Service check=$finalServiceCheck, SOCKS5=$finalSocks5Check, Instances=$finalInstancesCheck, Readiness=$readinessSatisfied")
                AiLogHelper.d(TAG, "🔍 STAGE 5 [VERIFYING]: Service=$finalServiceCheck, SOCKS5=$finalSocks5Check, Instances=$finalInstancesCheck, Readiness=$readinessSatisfied")
                
                if (!finalServiceCheck) {
                    val errorMsg = "Service is not enabled (service=$finalServiceCheck)"
                    Log.e(TAG, "VERIFYING FAILED: $errorMsg")
                    AiLogHelper.e(TAG, "❌ STAGE 5 [VERIFYING] FAILED: $errorMsg")
                    val errorMessage = "Connection verification failed: service=$finalServiceCheck"
                    stopConnectionProcessAndSetFailed(errorMessage)
                    return@launch
                }
                if (!readinessSatisfied) {
                    val errorMsg = "Readiness not satisfied (socks5=$finalSocks5Check, instances=$finalInstancesCheck)"
                    Log.e(TAG, "VERIFYING FAILED: $errorMsg")
                    AiLogHelper.e(TAG, "❌ STAGE 5 [VERIFYING] FAILED: $errorMsg")
                    val errorMessage = "Connection verification failed: socks5=$finalSocks5Check, instances=$finalInstancesCheck"
                    stopConnectionProcessAndSetFailed(errorMessage)
                    return@launch
                }
                
                // Running instance bul ve gRPC bağlantı testi yap
                Log.d(TAG, "VERIFYING: Searching for running Xray instances...")
                AiLogHelper.d(TAG, "🔍 STAGE 5 [VERIFYING]: Searching for running Xray instances...")
                val runningInstance = instancesStatus.first().values
                    .filterIsInstance<XrayRuntimeStatus.Running>()
                    .firstOrNull()
                
                if (runningInstance != null) {
                    Log.i(TAG, "VERIFYING: Found running instance - PID=${runningInstance.processId}, API Port=${runningInstance.apiPort}")
                    AiLogHelper.i(TAG, "🔍 STAGE 5 [VERIFYING]: Found running instance - PID=${runningInstance.processId}, API Port=${runningInstance.apiPort}")
                    Log.d(TAG, "VERIFYING: Starting gRPC connection verification via XrayStatsManager...")
                    AiLogHelper.d(TAG, "🔍 STAGE 5 [VERIFYING]: Starting gRPC connection verification via XrayStatsManager (port=${runningInstance.apiPort})...")
                    val grpcStartTime = System.currentTimeMillis()
                    val connectionVerified = withContext(Dispatchers.IO) {
                        xrayStatsManager.verifyXrayConnection(runningInstance.apiPort)
                    }
                    val grpcDuration = System.currentTimeMillis() - grpcStartTime
                    if (!connectionVerified) {
                        val errorMsg = "gRPC connection verification unsuccessful (port=${runningInstance.apiPort}, duration=${grpcDuration}ms)"
                        Log.e(TAG, "VERIFYING FAILED: $errorMsg")
                        AiLogHelper.e(TAG, "❌ STAGE 5 [VERIFYING] FAILED: $errorMsg")
                        val errorMessage = "Xray server connection verification failed - gRPC test unsuccessful"
                        stopConnectionProcessAndSetFailed(errorMessage)
                        return@launch
                    }
                    Log.i(TAG, "VERIFYING SUCCESS: Xray server connection verified successfully via gRPC")
                    AiLogHelper.i(TAG, "✅ STAGE 5 [VERIFYING]: gRPC connection verified successfully (duration: ${grpcDuration}ms)")
                } else {
                    Log.w(TAG, "VERIFYING WARNING: No running Xray instance found for connection verification")
                    Log.d(TAG, "VERIFYING: Available instances: ${instancesStatus.first()}")
                    AiLogHelper.w(TAG, "⚠️ STAGE 5 [VERIFYING] WARNING: No running Xray instance found for connection verification")
                }
                
                // SUCCESS - Show 100% progress briefly before switching to Connected
                val stage5Duration = System.currentTimeMillis() - stage5StartTime
                _connectionState.value = ConnectionState.Connecting(ConnectionStage.VERIFYING, progress = 1.0f)
                delay(300)
                
                val totalDuration = System.currentTimeMillis() - connectionStartTime
                _connectionState.value = ConnectionState.Connected
                Log.d(TAG, "Connection process completed successfully")
                AiLogHelper.i(TAG, "✅ CONNECTION SUCCESS: All stages completed successfully (total duration: ${totalDuration}ms)")
                AiLogHelper.i(TAG, "📊 CONNECTION SUMMARY: Stage1=${stage1Duration}ms, Stage2=${stage2Duration}ms, Stage3=${stage3Duration}ms, Stage4=${stage4Duration}ms, Stage5=${stage5Duration}ms")
                
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
     * Handles all disconnection stages: STOPPING_XRAY -> CLOSING_TUNNEL -> STOPPING_VPN -> CLEANING_UP -> DISCONNECTED
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
                
                // STAGE 1: STOPPING_XRAY - Stopping Xray instances
                val discStage1StartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.STOPPING_XRAY,
                    progress = 0.0f
                )
                AiLogHelper.i(TAG, "🛑 DISC STAGE 1 [STOPPING_XRAY]: Stopping Xray instances (progress: 0%)")
                delay(300) // Brief delay to show stage
                
                // Wait for instances to stop (with timeout)
                AiLogHelper.d(TAG, "⏳ DISC STAGE 1 [STOPPING_XRAY]: Waiting for Xray instances to stop (timeout: 5s)...")
                val instancesStopped = withTimeoutOrNull(5000L) {
                    instancesStatus.filter { statusMap ->
                        statusMap.values.none { it is XrayRuntimeStatus.Running }
                    }.first()
                }
                val discStage1Duration = System.currentTimeMillis() - discStage1StartTime
                if (instancesStopped != null) {
                    AiLogHelper.i(TAG, "✅ DISC STAGE 1 [STOPPING_XRAY] COMPLETED: Xray instances stopped (duration: ${discStage1Duration}ms)")
                } else {
                    AiLogHelper.w(TAG, "⚠️ DISC STAGE 1 [STOPPING_XRAY] TIMEOUT: Instances did not stop within 5s (duration: ${discStage1Duration}ms)")
                }
                
                // STAGE 2: CLOSING_TUNNEL - Closing network tunnel
                val discStage2StartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.CLOSING_TUNNEL,
                    progress = 0.3f
                )
                AiLogHelper.i(TAG, "🔒 DISC STAGE 2 [CLOSING_TUNNEL]: Closing network tunnel (progress: 30%)")
                delay(200)
                val discStage2Duration = System.currentTimeMillis() - discStage2StartTime
                AiLogHelper.i(TAG, "✅ DISC STAGE 2 [CLOSING_TUNNEL] COMPLETED: Network tunnel closed (duration: ${discStage2Duration}ms)")
                
                // STAGE 3: STOPPING_VPN - Stopping VPN service
                val discStage3StartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.STOPPING_VPN,
                    progress = 0.6f
                )
                AiLogHelper.i(TAG, "🛑 DISC STAGE 3 [STOPPING_VPN]: Stopping VPN service (progress: 60%)")
                
                // Stop the service
                AiLogHelper.d(TAG, "📤 DISC STAGE 3 [STOPPING_VPN]: Sending ACTION_DISCONNECT intent to TProxyService")
                stopService()
                
                // Wait for service to be disabled (with timeout)
                AiLogHelper.d(TAG, "⏳ DISC STAGE 3 [STOPPING_VPN]: Waiting for service to be disabled (timeout: 5s)...")
                val serviceDisabled = withTimeoutOrNull(5000L) {
                    isServiceEnabled.filter { !it }.first()
                }
                val discStage3Duration = System.currentTimeMillis() - discStage3StartTime
                if (serviceDisabled != null) {
                    AiLogHelper.i(TAG, "✅ DISC STAGE 3 [STOPPING_VPN] COMPLETED: VPN service stopped (duration: ${discStage3Duration}ms)")
                } else {
                    AiLogHelper.w(TAG, "⚠️ DISC STAGE 3 [STOPPING_VPN] TIMEOUT: Service did not stop within 5s (duration: ${discStage3Duration}ms)")
                }
                
                // STAGE 4: CLEANING_UP - Final cleanup
                val discStage4StartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.CLEANING_UP,
                    progress = 0.9f
                )
                AiLogHelper.i(TAG, "🧹 DISC STAGE 4 [CLEANING_UP]: Final cleanup (progress: 90%)")
                delay(200)
                
                // SUCCESS - Complete disconnection
                _connectionState.value = ConnectionState.Disconnecting(
                    DisconnectionStage.CLEANING_UP,
                    progress = 1.0f
                )
                delay(200)
                val discStage4Duration = System.currentTimeMillis() - discStage4StartTime
                val totalDisconnectDuration = System.currentTimeMillis() - disconnectStartTime
                
                _connectionState.value = ConnectionState.Disconnected
                Log.d(TAG, "Disconnection process completed successfully")
                AiLogHelper.i(TAG, "✅ DISCONNECTION SUCCESS: All stages completed successfully (total duration: ${totalDisconnectDuration}ms)")
                AiLogHelper.i(TAG, "📊 DISCONNECTION SUMMARY: Stage1=${discStage1Duration}ms, Stage2=${discStage2Duration}ms, Stage3=${discStage3Duration}ms, Stage4=${discStage4Duration}ms")
                
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

