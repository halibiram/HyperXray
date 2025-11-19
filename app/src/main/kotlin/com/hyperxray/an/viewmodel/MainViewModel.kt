package com.hyperxray.an.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.hyperxray.an.BuildConfig
import com.hyperxray.an.R
import com.hyperxray.an.xray.runtime.stats.CoreStatsClient
import com.hyperxray.an.common.ROUTE_AI_INSIGHTS
import com.hyperxray.an.common.ROUTE_APP_LIST
import com.hyperxray.an.common.formatBytes
import com.hyperxray.an.common.formatThroughput
import com.hyperxray.an.common.ROUTE_CONFIG_EDIT
import com.hyperxray.an.common.ThemeMode
import com.hyperxray.an.core.network.NetworkModule
import com.hyperxray.an.data.source.FileManager
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.service.TProxyService
import com.hyperxray.an.telemetry.TelemetryStore
import com.hyperxray.an.telemetry.AggregatedTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.regex.Pattern
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "MainViewModel"

/**
 * UI events for MainViewModel communication.
 */
sealed class MainViewUiEvent {
    data class ShowSnackbar(val message: String) : MainViewUiEvent()
    data class ShareLauncher(val intent: Intent) : MainViewUiEvent()
    data class StartService(val intent: Intent) : MainViewUiEvent()
    data object RefreshConfigList : MainViewUiEvent()
    data class Navigate(val route: String) : MainViewUiEvent()
}

/**
 * Main ViewModel managing app state, connection control, config management, and settings.
 * Coordinates between UI and TProxyService, handles stats collection, and manages preferences.
 */
class MainViewModel(application: Application) :
    AndroidViewModel(application) {
    val prefs: Preferences = Preferences(application)
    private val activityScope: CoroutineScope = viewModelScope
    private var compressedBackupData: ByteArray? = null

    private var coreStatsClient: CoreStatsClient? = null
    private val coreStatsClientMutex = Mutex() // For suspend functions (updateCoreStats)
    // Separate lock object for synchronous operations (used in closeCoreStatsClient)
    private val coreStatsClientLock = Any()
    
    // Client lifecycle state management to prevent rebuild loops
    @Volatile
    private var clientState: ClientState = ClientState.STOPPED
    @Volatile
    private var lastClientCloseTime: Long = 0L
    @Volatile
    private var consecutiveFailures: Int = 0
    private val MIN_RECREATE_INTERVAL_MS = 5000L // 5 seconds minimum between recreations
    private val MAX_BACKOFF_MS = 30000L // 30 seconds maximum backoff
    
    private enum class ClientState {
        STOPPED,        // Client not created or properly closed
        CREATING,       // Client creation in progress
        READY,          // Client ready and working
        FAILED,         // Client failed, needs cooldown before retry
        SHUTTING_DOWN   // Client is being shut down, ignore recreate requests
    }
    
    // Broadcast receiver registration state
    @Volatile
    private var receiversRegistered = false
    // Lock object for synchronous operations (used in init and onCleared)
    private val receiversLock = Any()
    
    // Throughput calculation state
    private var lastUplink: Long = 0L
    private var lastDownlink: Long = 0L
    private var lastStatsTime: Long = 0L

    private val fileManager: FileManager = FileManager(application, prefs)
    
    private val telemetryStore: TelemetryStore = TelemetryStore()

    var reloadView: (() -> Unit)? = null

    lateinit var appListViewModel: AppListViewModel
    lateinit var configEditViewModel: ConfigEditViewModel

    private val _settingsState = MutableStateFlow(
        SettingsState(
            socksPort = InputFieldState(prefs.socksPort.toString()),
            dnsIpv4 = InputFieldState(prefs.dnsIpv4),
            dnsIpv6 = InputFieldState(prefs.dnsIpv6),
            switches = SwitchStates(
                ipv6Enabled = prefs.ipv6,
                useTemplateEnabled = prefs.useTemplate,
                httpProxyEnabled = prefs.httpProxyEnabled,
                bypassLanEnabled = prefs.bypassLan,
                disableVpn = prefs.disableVpn,
                themeMode = prefs.theme
            ),
            info = InfoStates(
                appVersion = BuildConfig.VERSION_NAME,
                kernelVersion = "N/A",
                geoipSummary = "",
                geositeSummary = "",
                geoipUrl = prefs.geoipUrl,
                geositeUrl = prefs.geositeUrl
            ),
            files = FileStates(
                isGeoipCustom = prefs.customGeoipImported,
                isGeositeCustom = prefs.customGeositeImported
            ),
            connectivityTestTarget = InputFieldState(prefs.connectivityTestTarget),
            connectivityTestTimeout = InputFieldState(prefs.connectivityTestTimeout.toString()),
            performance = PerformanceSettings(
                aggressiveSpeedOptimizations = prefs.aggressiveSpeedOptimizations,
                connIdleTimeout = InputFieldState(prefs.connIdleTimeout.toString()),
                handshakeTimeout = InputFieldState(prefs.handshakeTimeout.toString()),
                uplinkOnly = InputFieldState(prefs.uplinkOnly.toString()),
                downlinkOnly = InputFieldState(prefs.downlinkOnly.toString()),
                dnsCacheSize = InputFieldState(prefs.dnsCacheSize.toString()),
                disableFakeDns = prefs.disableFakeDns,
                optimizeRoutingRules = prefs.optimizeRoutingRules,
                tcpFastOpen = prefs.tcpFastOpen,
                http2Optimization = prefs.http2Optimization,
                extreme = ExtremeOptimizationSettings(
                    extremeRamCpuOptimizations = prefs.extremeRamCpuOptimizations,
                    extremeConnIdleTimeout = InputFieldState(prefs.extremeConnIdleTimeout.toString()),
                    extremeHandshakeTimeout = InputFieldState(prefs.extremeHandshakeTimeout.toString()),
                    extremeUplinkOnly = InputFieldState(prefs.extremeUplinkOnly.toString()),
                    extremeDownlinkOnly = InputFieldState(prefs.extremeDownlinkOnly.toString()),
                    extremeDnsCacheSize = InputFieldState(prefs.extremeDnsCacheSize.toString()),
                    extremeDisableFakeDns = prefs.extremeDisableFakeDns,
                    extremeRoutingOptimization = prefs.extremeRoutingOptimization,
                    maxConcurrentConnections = InputFieldState(prefs.maxConcurrentConnections.toString()),
                    parallelDnsQueries = prefs.parallelDnsQueries,
                    extremeProxyOptimization = prefs.extremeProxyOptimization
                )
            ),
            bypassDomains = prefs.bypassDomains,
            bypassIps = prefs.bypassIps
        )
    )
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    private val _coreStatsState = MutableStateFlow(CoreStatsState())
    val coreStatsState: StateFlow<CoreStatsState> = _coreStatsState.asStateFlow()
    
    private val _telemetryState = MutableStateFlow<AggregatedTelemetry?>(null)
    val telemetryState: StateFlow<AggregatedTelemetry?> = _telemetryState.asStateFlow()

    private val _controlMenuClickable = MutableStateFlow(true)
    val controlMenuClickable: StateFlow<Boolean> = _controlMenuClickable.asStateFlow()

    private val _isServiceEnabled = MutableStateFlow(false)
    val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()
    
    private val _connectionState = MutableStateFlow<com.hyperxray.an.feature.dashboard.ConnectionState>(
        com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected
    )
    val connectionState: StateFlow<com.hyperxray.an.feature.dashboard.ConnectionState> = _connectionState.asStateFlow()

    private val _uiEvent = Channel<MainViewUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    private val _configFiles = MutableStateFlow<List<File>>(emptyList())
    val configFiles: StateFlow<List<File>> = _configFiles.asStateFlow()

    private val _selectedConfigFile = MutableStateFlow<File?>(null)
    val selectedConfigFile: StateFlow<File?> = _selectedConfigFile.asStateFlow()

    private val _geoipDownloadProgress = MutableStateFlow<String?>(null)
    val geoipDownloadProgress: StateFlow<String?> = _geoipDownloadProgress.asStateFlow()
    private var geoipDownloadJob: Job? = null

    private val _geositeDownloadProgress = MutableStateFlow<String?>(null)
    val geositeDownloadProgress: StateFlow<String?> = _geositeDownloadProgress.asStateFlow()
    private var geositeDownloadJob: Job? = null

    private val _isCheckingForUpdates = MutableStateFlow(false)
    val isCheckingForUpdates: StateFlow<Boolean> = _isCheckingForUpdates.asStateFlow()

    private val _newVersionAvailable = MutableStateFlow<String?>(null)
    val newVersionAvailable: StateFlow<String?> = _newVersionAvailable.asStateFlow()

    private val startReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Service started")
            setServiceEnabled(true)
            setControlMenuClickable(true)
            // Don't change connection state here - let startConnectionProcess handle it
            // This prevents race conditions where startReceiver fires before startConnectionProcess
            // or while startConnectionProcess is still in INITIALIZING stage
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Service stopped")
            setServiceEnabled(false)
            setControlMenuClickable(true)
            
            // Only set to Disconnected if not already in Disconnecting state
            // This allows the disconnection process to complete its stages
            if (_connectionState.value !is com.hyperxray.an.feature.dashboard.ConnectionState.Disconnecting) {
                _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected
            }
            
            _coreStatsState.value = CoreStatsState()
            _socks5Ready.value = false // Reset SOCKS5 ready state
            // Reset throughput calculation state
            lastUplink = 0L
            lastDownlink = 0L
            lastStatsTime = 0L
            // Close client when service stops (non-blocking, uses helper function)
            viewModelScope.launch {
                closeCoreStatsClient()
                consecutiveFailures = 0
            }
        }
    }

    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val errorMessage = intent.getStringExtra(TProxyService.EXTRA_ERROR_MESSAGE)
                ?: "An error occurred while starting the VPN service."
            Log.e(TAG, "Service error: $errorMessage")
            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(errorMessage))
            // Also stop the service state since it failed to start
            setServiceEnabled(false)
            setControlMenuClickable(true)
            _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected
        }
    }

    // Track connection stage states
    private val _socks5Ready = MutableStateFlow(false)
    private val socks5Ready: StateFlow<Boolean> = _socks5Ready.asStateFlow()

    private val socks5ReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "SOCKS5 ready")
            _socks5Ready.value = true
        }
    }

    init {
        Log.d(TAG, "MainViewModel initialized.")
        // Register broadcast receivers in init to ensure they're always registered
        registerTProxyServiceReceivers()
        viewModelScope.launch(Dispatchers.IO) {
            val isRunning = isServiceRunning(application, TProxyService::class.java)
            _isServiceEnabled.value = isRunning
            // Set initial connection state based on service status
            _connectionState.value = if (isRunning) {
                com.hyperxray.an.feature.dashboard.ConnectionState.Connected
            } else {
                com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected
            }

            updateSettingsState()
            loadKernelVersion()
            refreshConfigFileList()
        }
    }

    /**
     * Called when the ViewModel is about to be destroyed.
     * Ensures broadcast receivers are unregistered to prevent memory leaks.
     */
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "MainViewModel cleared, unregistering receivers.")
        unregisterTProxyServiceReceivers()
    }

    private fun updateSettingsState() {
        _settingsState.value = _settingsState.value.copy(
            socksPort = InputFieldState(prefs.socksPort.toString()),
            dnsIpv4 = InputFieldState(prefs.dnsIpv4),
            dnsIpv6 = InputFieldState(prefs.dnsIpv6),
            switches = SwitchStates(
                ipv6Enabled = prefs.ipv6,
                useTemplateEnabled = prefs.useTemplate,
                httpProxyEnabled = prefs.httpProxyEnabled,
                bypassLanEnabled = prefs.bypassLan,
                disableVpn = prefs.disableVpn,
                themeMode = prefs.theme
            ),
            info = _settingsState.value.info.copy(
                appVersion = BuildConfig.VERSION_NAME,
                geoipSummary = fileManager.getRuleFileSummary("geoip.dat"),
                geositeSummary = fileManager.getRuleFileSummary("geosite.dat"),
                geoipUrl = prefs.geoipUrl,
                geositeUrl = prefs.geositeUrl
            ),
            files = FileStates(
                isGeoipCustom = prefs.customGeoipImported,
                isGeositeCustom = prefs.customGeositeImported
            ),
            connectivityTestTarget = InputFieldState(prefs.connectivityTestTarget),
            connectivityTestTimeout = InputFieldState(prefs.connectivityTestTimeout.toString()),
            performance = PerformanceSettings(
                aggressiveSpeedOptimizations = prefs.aggressiveSpeedOptimizations,
                connIdleTimeout = InputFieldState(prefs.connIdleTimeout.toString()),
                handshakeTimeout = InputFieldState(prefs.handshakeTimeout.toString()),
                uplinkOnly = InputFieldState(prefs.uplinkOnly.toString()),
                downlinkOnly = InputFieldState(prefs.downlinkOnly.toString()),
                dnsCacheSize = InputFieldState(prefs.dnsCacheSize.toString()),
                disableFakeDns = prefs.disableFakeDns,
                optimizeRoutingRules = prefs.optimizeRoutingRules,
                tcpFastOpen = prefs.tcpFastOpen,
                http2Optimization = prefs.http2Optimization,
                extreme = ExtremeOptimizationSettings(
                    extremeRamCpuOptimizations = prefs.extremeRamCpuOptimizations,
                    extremeConnIdleTimeout = InputFieldState(prefs.extremeConnIdleTimeout.toString()),
                    extremeHandshakeTimeout = InputFieldState(prefs.extremeHandshakeTimeout.toString()),
                    extremeUplinkOnly = InputFieldState(prefs.extremeUplinkOnly.toString()),
                    extremeDownlinkOnly = InputFieldState(prefs.extremeDownlinkOnly.toString()),
                    extremeDnsCacheSize = InputFieldState(prefs.extremeDnsCacheSize.toString()),
                    extremeDisableFakeDns = prefs.extremeDisableFakeDns,
                    extremeRoutingOptimization = prefs.extremeRoutingOptimization,
                    maxConcurrentConnections = InputFieldState(prefs.maxConcurrentConnections.toString()),
                    parallelDnsQueries = prefs.parallelDnsQueries,
                    extremeProxyOptimization = prefs.extremeProxyOptimization
                )
            ),
            bypassDomains = prefs.bypassDomains,
            bypassIps = prefs.bypassIps
        )
    }

    private fun loadKernelVersion() {
        val libraryDir = TProxyService.getNativeLibraryDir(application)
        val xrayPath = "$libraryDir/libxray.so"
        try {
            // Check if libxray.so exists
            val xrayFile = File(xrayPath)
            if (!xrayFile.exists()) {
                Log.w(TAG, "libxray.so not found at $xrayPath")
                _settingsState.value = _settingsState.value.copy(
                    info = _settingsState.value.info.copy(
                        kernelVersion = "N/A (file not found)"
                    )
                )
                return
            }
            
            // Try to execute with linker (Android way)
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/linker64", xrayPath, "-version"))
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    val firstLine = reader.readLine()
                    
                    if (firstLine != null && firstLine.isNotEmpty()) {
                        _settingsState.value = _settingsState.value.copy(
                            info = _settingsState.value.info.copy(
                                kernelVersion = firstLine
                            )
                        )
                    } else {
                        // Fallback: show that file exists but version couldn't be read
                        _settingsState.value = _settingsState.value.copy(
                            info = _settingsState.value.info.copy(
                                kernelVersion = "Available (BoringSSL)"
                            )
                        )
                    }
                }
            } finally {
                // Ensure all process streams are closed
                try {
                    process.inputStream?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing process input stream", e)
                }
                try {
                    process.errorStream?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing process error stream", e)
                }
                try {
                    process.outputStream?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing process output stream", e)
                }
                // Destroy the process
                process.destroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get xray version", e)
            // Check if file exists at least
            val xrayFile = File(xrayPath)
            if (xrayFile.exists()) {
                _settingsState.value = _settingsState.value.copy(
                    info = _settingsState.value.info.copy(
                        kernelVersion = "Available (BoringSSL)"
                    )
                )
            } else {
                _settingsState.value = _settingsState.value.copy(
                    info = _settingsState.value.info.copy(
                        kernelVersion = "N/A"
                    )
                )
            }
        }
    }

    fun setControlMenuClickable(isClickable: Boolean) {
        _controlMenuClickable.value = isClickable
    }

    fun setServiceEnabled(enabled: Boolean) {
        _isServiceEnabled.value = enabled
        prefs.enable = enabled
    }
    
    fun setConnectionStateDisconnecting() {
        // Start disconnection process with initial stage
        startDisconnectionProcess()
    }
    
    /**
     * Starts the disconnection process with real-time stage checking.
     * Each stage has a minimum display time so users can see the progress.
     */
    fun startDisconnectionProcess() {
        viewModelScope.launch {
            // Only start if we're not already disconnected
            if (_connectionState.value is com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected) {
                return@launch
            }
            
            val minStageDisplayTime = 800L // Minimum time to show each stage (800ms)
            val checkInterval = 100L // Check every 100ms
            
            // Stage 1: STOPPING_XRAY - Stopping Xray core
            _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Disconnecting(
                stage = com.hyperxray.an.feature.dashboard.DisconnectionStage.STOPPING_XRAY,
                progress = 0.2f
            )
            
            // Show this stage for minimum time
            var elapsed = 0L
            while (elapsed < minStageDisplayTime) {
                kotlinx.coroutines.delay(checkInterval)
                elapsed += checkInterval
                
                // Check if already disconnected (shouldn't happen, but safety check)
                if (_connectionState.value is com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected) {
                    return@launch
                }
            }
            
            // Stage 2: CLOSING_TUNNEL - Closing network tunnel
            _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Disconnecting(
                stage = com.hyperxray.an.feature.dashboard.DisconnectionStage.CLOSING_TUNNEL,
                progress = 0.5f
            )
            
            // Show this stage for minimum time
            elapsed = 0L
            while (elapsed < minStageDisplayTime) {
                kotlinx.coroutines.delay(checkInterval)
                elapsed += checkInterval
                
                if (_connectionState.value is com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected) {
                    return@launch
                }
            }
            
            // Stage 3: STOPPING_VPN - Closing VPN interface
            _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Disconnecting(
                stage = com.hyperxray.an.feature.dashboard.DisconnectionStage.STOPPING_VPN,
                progress = 0.7f
            )
            
            // Show this stage for minimum time, or wait for service to stop
            elapsed = 0L
            val maxWait = 3000L // Max 3 seconds for VPN stop
            while (elapsed < minStageDisplayTime || (_isServiceEnabled.value && elapsed < maxWait)) {
                kotlinx.coroutines.delay(checkInterval)
                elapsed += checkInterval
                
                if (_connectionState.value is com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected) {
                    return@launch
                }
            }
            
            // Stage 4: CLEANING_UP - Releasing resources
            _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Disconnecting(
                stage = com.hyperxray.an.feature.dashboard.DisconnectionStage.CLEANING_UP,
                progress = 0.9f
            )
            
            // Show final cleanup stage and wait for service to completely stop
            elapsed = 0L
            val maxWaitForService = 10000L // Max 10 seconds for service to stop
            var serviceStopped = false
            
            while (elapsed < minStageDisplayTime || !serviceStopped) {
                kotlinx.coroutines.delay(checkInterval)
                elapsed += checkInterval
                
                // Check if already disconnected (shouldn't happen, but safety check)
                if (_connectionState.value is com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected) {
                    return@launch
                }
                
                // Check if service is completely stopped
                if (!serviceStopped) {
                    serviceStopped = !MainViewModel.isServiceRunning(application, TProxyService::class.java)
                    if (serviceStopped && elapsed < minStageDisplayTime) {
                        // Service stopped but we still need to show the stage for minimum time
                        continue
                    }
                }
                
                // If service stopped and minimum time passed, we can proceed
                if (serviceStopped && elapsed >= minStageDisplayTime) {
                    break
                }
                
                // Timeout check
                if (elapsed >= maxWaitForService) {
                    // Service might still be running, but we've waited long enough
                    // Check one more time
                    serviceStopped = !MainViewModel.isServiceRunning(application, TProxyService::class.java)
                    break
                }
            }
            
            // Final state: DISCONNECTED (only after service is confirmed stopped)
            _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected
        }
    }
    
    /**
     * Starts the connection process with real-time stage checking.
     * Each stage waits for actual service state changes instead of timeouts.
     */
    fun startConnectionProcess() {
        viewModelScope.launch {
            // Only start if we're not already connected
            if (_connectionState.value is com.hyperxray.an.feature.dashboard.ConnectionState.Connected) {
                return@launch
            }
            
            // Reset SOCKS5 ready state
            _socks5Ready.value = false
            
            // Stage 1: INITIALIZING - Service start command sent
            _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Connecting(
                stage = com.hyperxray.an.feature.dashboard.ConnectionStage.INITIALIZING,
                progress = 0.1f
            )
            
            // Wait for VPN service to start (check _isServiceEnabled)
            val checkInterval = 200L // Check every 200ms
            var maxWait = 10000L // 10 seconds max for VPN start
            var elapsed = 0L
            
            while (elapsed < maxWait) {
                kotlinx.coroutines.delay(checkInterval)
                elapsed += checkInterval
                
                // Check if already connected
                if (_connectionState.value is com.hyperxray.an.feature.dashboard.ConnectionState.Connected) {
                    return@launch
                }
                
                // Check if service is disabled (error occurred)
                if (!_isServiceEnabled.value && elapsed > 1000) {
                    _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected
                    return@launch
                }
                
                // Check if VPN service started
                if (_isServiceEnabled.value) {
                    break // VPN started, move to next stage
                }
            }
            
            // If timeout and service still not enabled, show error
            if (!_isServiceEnabled.value) {
                _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected
                return@launch
            }
            
            // Check if already connected
            if (_connectionState.value is com.hyperxray.an.feature.dashboard.ConnectionState.Connected) {
                return@launch
            }
            
            // Stage 2: STARTING_VPN - VPN service is starting
            _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Connecting(
                stage = com.hyperxray.an.feature.dashboard.ConnectionStage.STARTING_VPN,
                progress = 0.3f
            )
            
            // VPN is already started (we checked above), move to Xray stage
            // Small delay to show the stage
            kotlinx.coroutines.delay(300)
            
            // Check if already connected
            if (_connectionState.value is com.hyperxray.an.feature.dashboard.ConnectionState.Connected) {
                return@launch
            }
            
            // Stage 3: STARTING_XRAY - Xray service is starting
            _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Connecting(
                stage = com.hyperxray.an.feature.dashboard.ConnectionStage.STARTING_XRAY,
                progress = 0.5f
            )
            
            // Wait for Xray to start (service is enabled, but Xray might need time)
            // We'll wait for SOCKS5 ready as indicator that Xray is fully started
            elapsed = 0L
            maxWait = 15000L // 15 seconds max for Xray start
            
            while (elapsed < maxWait) {
                kotlinx.coroutines.delay(checkInterval)
                elapsed += checkInterval
                
                // Check if already connected
                if (_connectionState.value is com.hyperxray.an.feature.dashboard.ConnectionState.Connected) {
                    return@launch
                }
                
                // Check if service is disabled
                if (!_isServiceEnabled.value) {
                    _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected
                    return@launch
                }
                
                // Check if SOCKS5 is ready (indicates Xray is started)
                if (_socks5Ready.value) {
                    break // Xray started, move to next stage
                }
            }
            
            // Check if already connected
            if (_connectionState.value is com.hyperxray.an.feature.dashboard.ConnectionState.Connected) {
                return@launch
            }
            
            // Stage 4: ESTABLISHING - Connection is being established
            _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Connecting(
                stage = com.hyperxray.an.feature.dashboard.ConnectionStage.ESTABLISHING,
                progress = 0.7f
            )
            
            // SOCKS5 is ready, connection is being established
            // Small delay to show the stage
            kotlinx.coroutines.delay(500)
            
            // Check if already connected
            if (_connectionState.value is com.hyperxray.an.feature.dashboard.ConnectionState.Connected) {
                return@launch
            }
            
            // Stage 5: VERIFYING - Wait for real data to arrive
            _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Connecting(
                stage = com.hyperxray.an.feature.dashboard.ConnectionStage.VERIFYING,
                progress = 0.9f
            )
            
            // Wait for real connection data (uptime > 0 or throughput > 0)
            // No timeout - wait until data arrives or service stops
            elapsed = 0L
            maxWait = 30000L // 30 seconds max wait for data
            val progressCheckInterval = 1000L // Update progress every second
            
            while (elapsed < maxWait) {
                kotlinx.coroutines.delay(checkInterval)
                elapsed += checkInterval
                
                // Check if already marked as connected
                if (_connectionState.value is com.hyperxray.an.feature.dashboard.ConnectionState.Connected) {
                    return@launch
                }
                
                // Check if service is still enabled
                if (!_isServiceEnabled.value) {
                    _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected
                    return@launch
                }
                
                // Check for real connection data
                val stats = _coreStatsState.value
                val hasRealConnection = stats.uptime > 0 || 
                                       stats.uplinkThroughput > 0 || 
                                       stats.downlinkThroughput > 0 ||
                                       stats.uplink > 0 || 
                                       stats.downlink > 0
                
                if (hasRealConnection) {
                    // Real data is flowing, connection is established
                    _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Connected
                    return@launch
                }
                
                // Update progress periodically (slowly increase to 0.98)
                if (elapsed % progressCheckInterval < checkInterval) {
                    val progress = 0.9f + (elapsed.toFloat() / maxWait.toFloat()) * 0.08f
                    _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Connecting(
                        stage = com.hyperxray.an.feature.dashboard.ConnectionStage.VERIFYING,
                        progress = progress.coerceAtMost(0.98f)
                    )
                }
            }
            
            // If still no data after timeout, check one more time
            val finalStats = _coreStatsState.value
            val hasFinalConnection = finalStats.uptime > 0 || 
                                     finalStats.uplinkThroughput > 0 || 
                                     finalStats.downlinkThroughput > 0 ||
                                     finalStats.uplink > 0 || 
                                     finalStats.downlink > 0
            
            if (hasFinalConnection && _isServiceEnabled.value) {
                _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Connected
            }
            // Otherwise keep showing VERIFYING - updateCoreStats will update when data arrives
        }
    }

    fun clearCompressedBackupData() {
        compressedBackupData = null
    }

    fun performBackup(createFileLauncher: ActivityResultLauncher<String>) {
        activityScope.launch {
            compressedBackupData = fileManager.compressBackupData()
            val filename = "hyperxray_backup_" + System.currentTimeMillis() + ".dat"
            withContext(Dispatchers.Main) {
                createFileLauncher.launch(filename)
            }
        }
    }

    suspend fun handleBackupFileCreationResult(uri: Uri) {
        withContext(Dispatchers.IO) {
            if (compressedBackupData != null) {
                val dataToWrite: ByteArray = compressedBackupData as ByteArray
                compressedBackupData = null
                try {
                    application.contentResolver.openOutputStream(uri).use { os ->
                        if (os != null) {
                            os.write(dataToWrite)
                            Log.d(TAG, "Backup successful to: $uri")
                            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.backup_success)))
                        } else {
                            Log.e(TAG, "Failed to open output stream for backup URI: $uri")
                            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.backup_failed)))
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Error writing backup data to URI: $uri", e)
                    _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.backup_failed)))
                }
            } else {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.backup_failed)))
                Log.e(TAG, "Compressed backup data is null in launcher callback.")
            }
        }
    }

    suspend fun startRestoreTask(uri: Uri) {
        withContext(Dispatchers.IO) {
            val success = fileManager.decompressAndRestore(uri)
            if (success) {
                updateSettingsState()
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.restore_success)))
                Log.d(TAG, "Restore successful.")
                refreshConfigFileList()
            } else {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.restore_failed)))
            }
        }
    }

    suspend fun createConfigFile(): String? {
        val filePath = fileManager.createConfigFile(application.assets)
        if (filePath == null) {
            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.create_config_failed)))
        } else {
            refreshConfigFileList()
        }
        return filePath
    }

    /**
     * Closes the CoreStatsClient and clears the reference.
     * This is a thread-safe operation that should be called when:
     * - Service is stopped
     * - Client encounters an error
     * - Timeout occurs during gRPC calls
     * - Service is disabled
     * 
     * Note: This method is synchronous and can be called from any thread.
     */
    private fun closeCoreStatsClient() {
        // Use synchronized block for thread safety (non-suspend)
        // Since this may be called from onCleared() or other non-suspend contexts,
        // we use a simple synchronized block instead of Mutex
        synchronized(coreStatsClientLock) {
            // Prevent multiple shutdown attempts
            if (clientState == ClientState.SHUTTING_DOWN || clientState == ClientState.STOPPED) {
                return
            }
            
            clientState = ClientState.SHUTTING_DOWN
            lastClientCloseTime = System.currentTimeMillis()
            
            try {
                coreStatsClient?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing CoreStatsClient: ${e.message}", e)
            } finally {
                coreStatsClient = null
                clientState = ClientState.STOPPED
            }
        }
    }

    suspend fun updateCoreStats() {
        // Check if service is enabled before proceeding
        if (!_isServiceEnabled.value) {
            // Service is not enabled, ensure client is closed if it exists
            closeCoreStatsClient()
            consecutiveFailures = 0
            return
        }
        
        // Synchronize access to coreStatsClient to prevent race conditions
        val client = coreStatsClientMutex.withLock {
            // Check if we can recreate client (cooldown and state checks)
            if (coreStatsClient == null) {
                val now = System.currentTimeMillis()
                val timeSinceClose = now - lastClientCloseTime
                
                // Prevent rapid recreation: enforce cooldown period
                if (timeSinceClose < MIN_RECREATE_INTERVAL_MS) {
                    val remainingCooldown = MIN_RECREATE_INTERVAL_MS - timeSinceClose
                    Log.d(TAG, "Client recreation cooldown active, ${remainingCooldown}ms remaining")
                    return@withLock null
                }
                
                // Check state: only recreate if STOPPED or FAILED (not SHUTTING_DOWN or CREATING)
                if (clientState != ClientState.STOPPED && clientState != ClientState.FAILED) {
                    Log.d(TAG, "Client in state ${clientState}, skipping recreation")
                    return@withLock null
                }
                
                // Calculate exponential backoff based on consecutive failures
                if (consecutiveFailures > 0) {
                    val backoffMs = minOf(
                        MIN_RECREATE_INTERVAL_MS * (1L shl minOf(consecutiveFailures - 1, 4)),
                        MAX_BACKOFF_MS
                    )
                    if (timeSinceClose < backoffMs) {
                        val remainingBackoff = backoffMs - timeSinceClose
                        Log.d(TAG, "Exponential backoff active, ${remainingBackoff}ms remaining (failures: $consecutiveFailures)")
                        return@withLock null
                    }
                }
                
                // Set state to CREATING to prevent concurrent creation attempts
                clientState = ClientState.CREATING
                
                try {
                    // create() now returns nullable and handles retries internally
                    coreStatsClient = CoreStatsClient.create("127.0.0.1", prefs.apiPort)
                    if (coreStatsClient != null) {
                        Log.d(TAG, "Created new CoreStatsClient")
                        clientState = ClientState.READY
                        consecutiveFailures = 0 // Reset on success
                    } else {
                        Log.w(TAG, "Failed to create CoreStatsClient after retries")
                        clientState = ClientState.FAILED
                        consecutiveFailures++
                        lastClientCloseTime = System.currentTimeMillis()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception creating CoreStatsClient: ${e.message}", e)
                    clientState = ClientState.FAILED
                    consecutiveFailures++
                    lastClientCloseTime = System.currentTimeMillis()
                    coreStatsClient = null
                }
            }
            // If client exists but might be in bad state, we'll detect that during RPC calls
            // and recreate on next attempt (handled by CoreStatsClient internally)
            coreStatsClient
        }
        
        // Validate client was created
        if (client == null) {
            Log.w(TAG, "CoreStatsClient is null, cannot update stats - will retry on next call")
            // Don't return immediately - update state with safe fallback values
            // This allows UI to show "connecting" or "unavailable" state
            val currentState = _coreStatsState.value
            _coreStatsState.value = currentState.copy(
                // Preserve existing values, don't reset to zero
                // UI can show these as "last known" or "unavailable"
            )
            return
        }
        
        // Use withTimeoutOrNull to prevent hanging if Xray crashes or is unresponsive
        // Timeout of 5 seconds should be sufficient for gRPC calls
        // Note: withTimeoutOrNull catches all exceptions (including TimeoutCancellationException)
        // and returns null, so we catch exceptions inside to log them
        val statsResult = withTimeoutOrNull(5000L) {
            try {
                // Check if service is still enabled before making gRPC call
                if (!_isServiceEnabled.value) {
                    return@withTimeoutOrNull null
                }
                // Make gRPC call - may throw exception or timeout
                client.getSystemStats()
            } catch (e: Exception) {
                // Exception occurred during gRPC call - log it
                // Note: This exception will be caught by withTimeoutOrNull and null will be returned
                // but we catch it here to log it before that happens
                Log.e(TAG, "Error getting system stats: ${e.message}", e)
                // Return null to indicate failure (withTimeoutOrNull will return null)
                null
            }
        }
        
        // Handle timeout, exception, or service disabled
        if (statsResult == null) {
            // Timeout, exception, or service disabled occurred
            Log.w(TAG, "Stats query failed (timeout/exception/disabled)")
            // Only close client if service is disabled or we've had multiple failures
            // This prevents rapid recreate loops
            if (!_isServiceEnabled.value) {
                closeCoreStatsClient()
                consecutiveFailures = 0
            } else {
                // Service is enabled but query failed - increment failure count
                // Only close client after multiple consecutive failures to prevent loops
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    Log.w(TAG, "Multiple consecutive failures ($consecutiveFailures), closing client")
                    synchronized(coreStatsClientLock) {
                        clientState = ClientState.FAILED
                    }
                    closeCoreStatsClient()
                } else {
                    Log.d(TAG, "Stats query failed but keeping client (failures: $consecutiveFailures)")
                }
            }
            return
        }
        
        // Success: reset failure count
        consecutiveFailures = 0
        synchronized(coreStatsClientLock) {
            if (clientState != ClientState.READY) {
                clientState = ClientState.READY
            }
        }
        
        // Check if service is still enabled after first gRPC call
        if (!_isServiceEnabled.value) {
            Log.d(TAG, "Service disabled during stats update, skipping")
            closeCoreStatsClient()
            return
        }
        
        val trafficResult = withTimeoutOrNull(5000L) {
            try {
                // Check if service is still enabled before making gRPC call
                if (!_isServiceEnabled.value) {
                    return@withTimeoutOrNull null
                }
                // Make gRPC call - may throw exception or timeout
                client.getTraffic()
            } catch (e: Exception) {
                // Exception occurred during gRPC call - log it
                // Note: This exception will be caught by withTimeoutOrNull and null will be returned
                // but we catch it here to log it before that happens
                Log.e(TAG, "Error getting traffic stats: ${e.message}", e)
                // Return null to indicate failure (withTimeoutOrNull will return null)
                null
            }
        }
        
        // Handle timeout, exception, or service disabled
        if (trafficResult == null) {
            // Timeout, exception, or service disabled occurred
            Log.w(TAG, "Traffic query failed (timeout/exception/disabled)")
            // Don't close client immediately - it might recover on next call
            // Only close if service is disabled
            if (!_isServiceEnabled.value) {
                closeCoreStatsClient()
                consecutiveFailures = 0
            } else {
                // Increment failure count but don't close immediately
                // Traffic failures are less critical than system stats failures
                consecutiveFailures++
                if (consecutiveFailures >= 5) {
                    Log.w(TAG, "Multiple consecutive failures ($consecutiveFailures), closing client")
                    synchronized(coreStatsClientLock) {
                        clientState = ClientState.FAILED
                    }
                    closeCoreStatsClient()
                }
            }
            // Preserve existing traffic values instead of returning
            // This allows UI to show last known values
            // Note: We don't update state here since traffic failed - preserve all existing values
            return
        }
        
        // Success: reset failure count
        consecutiveFailures = 0
        synchronized(coreStatsClientLock) {
            if (clientState != ClientState.READY) {
                clientState = ClientState.READY
            }
        }
        
        // Check if service is still enabled after both gRPC calls
        if (!_isServiceEnabled.value) {
            Log.d(TAG, "Service disabled during stats update, skipping")
            closeCoreStatsClient()
            return
        }
        
        val stats = statsResult
        val traffic = trafficResult

        // Validate that we got some data
        if (stats == null && traffic == null) {
            Log.w(TAG, "Both stats and traffic are null, closing client")
            closeCoreStatsClient()
            return
        }

        // Preserve existing traffic values if new traffic data is null
        val currentState = _coreStatsState.value
        val newUplink = traffic?.uplink ?: currentState.uplink
        val newDownlink = traffic?.downlink ?: currentState.downlink

        // Calculate throughput (bytes per second)
        val now = System.currentTimeMillis()
        var uplinkThroughput = 0.0
        var downlinkThroughput = 0.0
        
        if (lastStatsTime > 0 && now > lastStatsTime) {
            val timeDelta = (now - lastStatsTime) / 1000.0 // Convert to seconds
            
            if (timeDelta > 0) {
                val uplinkDelta = newUplink - lastUplink
                val downlinkDelta = newDownlink - lastDownlink
                
                uplinkThroughput = uplinkDelta / timeDelta
                downlinkThroughput = downlinkDelta / timeDelta
                
                Log.d(TAG, "Throughput calculated: uplink=${formatThroughput(uplinkThroughput)}, downlink=${formatThroughput(downlinkThroughput)}, timeDelta=${timeDelta}s")
            }
        } else if (lastStatsTime == 0L) {
            // First measurement - initialize baseline
            Log.d(TAG, "First throughput measurement - initializing baseline")
        }
        
        // Update last values for next calculation
        lastUplink = newUplink
        lastDownlink = newDownlink
        lastStatsTime = now

        val newStats = CoreStatsState(
            uplink = newUplink,
            downlink = newDownlink,
            uplinkThroughput = uplinkThroughput,
            downlinkThroughput = downlinkThroughput,
            numGoroutine = stats?.numGoroutine ?: currentState.numGoroutine,
            numGC = stats?.numGC ?: currentState.numGC,
            alloc = stats?.alloc ?: currentState.alloc,
            totalAlloc = stats?.totalAlloc ?: currentState.totalAlloc,
            sys = stats?.sys ?: currentState.sys,
            mallocs = stats?.mallocs ?: currentState.mallocs,
            frees = stats?.frees ?: currentState.frees,
            liveObjects = stats?.liveObjects ?: currentState.liveObjects,
            pauseTotalNs = stats?.pauseTotalNs ?: currentState.pauseTotalNs,
            uptime = stats?.uptime ?: currentState.uptime
        )
        _coreStatsState.value = newStats
        
        // Only update to Connected if we're in VERIFYING stage and real data has arrived
        // Don't update during INITIALIZING, STARTING_VPN, STARTING_XRAY, or ESTABLISHING stages
        val currentConnectionState = _connectionState.value
        if (currentConnectionState is com.hyperxray.an.feature.dashboard.ConnectionState.Connecting) {
            // Only proceed if we're in VERIFYING stage
            if (currentConnectionState.stage == com.hyperxray.an.feature.dashboard.ConnectionStage.VERIFYING) {
                val hasRealConnection = newStats.uptime > 0 || 
                                       newStats.uplinkThroughput > 0 || 
                                       newStats.downlinkThroughput > 0 ||
                                       newStats.uplink > 0 || 
                                       newStats.downlink > 0
                
                if (hasRealConnection && _isServiceEnabled.value) {
                    Log.d(TAG, "Real connection data detected in VERIFYING stage, updating to Connected state")
                    _connectionState.value = com.hyperxray.an.feature.dashboard.ConnectionState.Connected
                }
            }
            // For other stages (INITIALIZING, STARTING_VPN, STARTING_XRAY, ESTABLISHING), 
            // don't update to Connected - let startConnectionProcess handle the progression
        }
        
        Log.d(TAG, "Core stats updated - Uplink: ${formatBytes(newUplink)}, Downlink: ${formatBytes(newDownlink)}, Uplink Throughput: ${formatThroughput(uplinkThroughput)}, Downlink Throughput: ${formatThroughput(downlinkThroughput)}")
    }

    suspend fun updateTelemetryStats() {
        if (!_isServiceEnabled.value) {
            _telemetryState.value = null
            return
        }
        
        val aggregated = telemetryStore.getAggregated()
        _telemetryState.value = aggregated
    }
    
    /**
     * Get TelemetryStore instance for storing telemetry metrics
     */
    fun getTelemetryStore(): TelemetryStore {
        return telemetryStore
    }

    suspend fun importConfigFromClipboard(): String? {
        val filePath = fileManager.importConfigFromClipboard()
        if (filePath == null) {
            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.import_failed)))
        } else {
            refreshConfigFileList()
        }
        return filePath
    }

    suspend fun handleSharedContent(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!fileManager.importConfigFromContent(content).isNullOrEmpty()) {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.import_success)))
                refreshConfigFileList()
            } else {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.invalid_config_format)))
            }
        }
    }

    suspend fun deleteConfigFile(file: File, callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_isServiceEnabled.value && _selectedConfigFile.value != null &&
                _selectedConfigFile.value == file
            ) {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.config_in_use)))
                Log.w(TAG, "Attempted to delete selected config file: ${file.name}")
                return@launch
            }

            val success = fileManager.deleteConfigFile(file)
            if (success) {
                withContext(Dispatchers.Main) {
                    refreshConfigFileList()
                }
            } else {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.delete_fail)))
            }
            callback()
        }
    }

    fun extractAssetsIfNeeded() {
        fileManager.extractAssetsIfNeeded()
    }

    fun updateSocksPort(portString: String): Boolean {
        return try {
            val port = portString.toInt()
            if (port in 1025..65535) {
                prefs.socksPort = port
                _settingsState.value = _settingsState.value.copy(
                    socksPort = InputFieldState(portString)
                )
                true
            } else {
                _settingsState.value = _settingsState.value.copy(
                    socksPort = InputFieldState(
                        value = portString,
                        error = application.getString(R.string.invalid_port_range),
                        isValid = false
                    )
                )
                false
            }
        } catch (e: NumberFormatException) {
            _settingsState.value = _settingsState.value.copy(
                socksPort = InputFieldState(
                    value = portString,
                    error = application.getString(R.string.invalid_port),
                    isValid = false
                )
            )
            false
        }
    }

    fun updateDnsIpv4(ipv4Addr: String): Boolean {
        val matcher = IPV4_PATTERN.matcher(ipv4Addr)
        return if (matcher.matches()) {
            prefs.dnsIpv4 = ipv4Addr
            _settingsState.value = _settingsState.value.copy(
                dnsIpv4 = InputFieldState(ipv4Addr)
            )
            true
        } else {
            _settingsState.value = _settingsState.value.copy(
                dnsIpv4 = InputFieldState(
                    value = ipv4Addr,
                    error = application.getString(R.string.invalid_ipv4),
                    isValid = false
                )
            )
            false
        }
    }

    fun updateDnsIpv6(ipv6Addr: String): Boolean {
        val matcher = IPV6_PATTERN.matcher(ipv6Addr)
        return if (matcher.matches()) {
            prefs.dnsIpv6 = ipv6Addr
            _settingsState.value = _settingsState.value.copy(
                dnsIpv6 = InputFieldState(ipv6Addr)
            )
            true
        } else {
            _settingsState.value = _settingsState.value.copy(
                dnsIpv6 = InputFieldState(
                    value = ipv6Addr,
                    error = application.getString(R.string.invalid_ipv6),
                    isValid = false
                )
            )
            false
        }
    }

    fun setIpv6Enabled(enabled: Boolean) {
        prefs.ipv6 = enabled
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(ipv6Enabled = enabled)
        )
    }

    fun setUseTemplateEnabled(enabled: Boolean) {
        prefs.useTemplate = enabled
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(useTemplateEnabled = enabled)
        )
    }

    fun setHttpProxyEnabled(enabled: Boolean) {
        prefs.httpProxyEnabled = enabled
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(httpProxyEnabled = enabled)
        )
    }

    fun setBypassLanEnabled(enabled: Boolean) {
        prefs.bypassLan = enabled
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(bypassLanEnabled = enabled)
        )
    }

    fun setDisableVpnEnabled(enabled: Boolean) {
        prefs.disableVpn = enabled
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(disableVpn = enabled)
        )
    }

    fun setTheme(mode: ThemeMode) {
        prefs.theme = mode
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(themeMode = mode)
        )
        reloadView?.invoke()
    }

    // Performance Settings Functions
    fun setAggressiveSpeedOptimizations(enabled: Boolean) {
        prefs.aggressiveSpeedOptimizations = enabled
        _settingsState.value = _settingsState.value.copy(
            performance = _settingsState.value.performance.copy(
                aggressiveSpeedOptimizations = enabled
            )
        )
    }

    fun updateConnIdleTimeout(value: String) {
        val timeout = value.toIntOrNull()
        if (timeout != null && timeout > 0) {
            prefs.connIdleTimeout = timeout
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    connIdleTimeout = InputFieldState(value)
                )
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    connIdleTimeout = InputFieldState(
                        value = value,
                        error = application.getString(R.string.invalid_timeout),
                        isValid = false
                    )
                )
            )
        }
    }

    fun updateHandshakeTimeout(value: String) {
        val timeout = value.toIntOrNull()
        if (timeout != null && timeout > 0) {
            prefs.handshakeTimeout = timeout
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    handshakeTimeout = InputFieldState(value)
                )
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    handshakeTimeout = InputFieldState(
                        value = value,
                        error = application.getString(R.string.invalid_timeout),
                        isValid = false
                    )
                )
            )
        }
    }

    fun updateUplinkOnly(value: String) {
        val uplink = value.toIntOrNull()
        if (uplink != null && uplink >= 0) {
            prefs.uplinkOnly = uplink
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    uplinkOnly = InputFieldState(value)
                )
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    uplinkOnly = InputFieldState(
                        value = value,
                        error = "Invalid value",
                        isValid = false
                    )
                )
            )
        }
    }

    fun updateDownlinkOnly(value: String) {
        val downlink = value.toIntOrNull()
        if (downlink != null && downlink >= 0) {
            prefs.downlinkOnly = downlink
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    downlinkOnly = InputFieldState(value)
                )
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    downlinkOnly = InputFieldState(
                        value = value,
                        error = "Invalid value",
                        isValid = false
                    )
                )
            )
        }
    }

    fun updateDnsCacheSize(value: String) {
        val cacheSize = value.toIntOrNull()
        if (cacheSize != null && cacheSize > 0) {
            prefs.dnsCacheSize = cacheSize
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    dnsCacheSize = InputFieldState(value)
                )
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    dnsCacheSize = InputFieldState(
                        value = value,
                        error = "Invalid cache size",
                        isValid = false
                    )
                )
            )
        }
    }

    fun setDisableFakeDns(enabled: Boolean) {
        prefs.disableFakeDns = enabled
        _settingsState.value = _settingsState.value.copy(
            performance = _settingsState.value.performance.copy(
                disableFakeDns = enabled
            )
        )
    }

    fun setOptimizeRoutingRules(enabled: Boolean) {
        prefs.optimizeRoutingRules = enabled
        _settingsState.value = _settingsState.value.copy(
            performance = _settingsState.value.performance.copy(
                optimizeRoutingRules = enabled
            )
        )
    }

    fun setTcpFastOpen(enabled: Boolean) {
        prefs.tcpFastOpen = enabled
        _settingsState.value = _settingsState.value.copy(
            performance = _settingsState.value.performance.copy(
                tcpFastOpen = enabled
            )
        )
    }

    fun setHttp2Optimization(enabled: Boolean) {
        prefs.http2Optimization = enabled
        _settingsState.value = _settingsState.value.copy(
            performance = _settingsState.value.performance.copy(
                http2Optimization = enabled
            )
        )
    }

    // Extreme RAM/CPU Optimization Functions
    fun setExtremeRamCpuOptimizations(enabled: Boolean) {
        prefs.extremeRamCpuOptimizations = enabled
        _settingsState.value = _settingsState.value.copy(
            performance = _settingsState.value.performance.copy(
                extreme = _settingsState.value.performance.extreme.copy(
                    extremeRamCpuOptimizations = enabled
                )
            )
        )
    }

    fun updateExtremeConnIdleTimeout(value: String) {
        val timeout = value.toIntOrNull()
        if (timeout != null && timeout > 0) {
            prefs.extremeConnIdleTimeout = timeout
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    extreme = _settingsState.value.performance.extreme.copy(
                        extremeConnIdleTimeout = InputFieldState(value)
                    )
                )
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    extreme = _settingsState.value.performance.extreme.copy(
                        extremeConnIdleTimeout = InputFieldState(
                            value = value,
                            error = application.getString(R.string.invalid_timeout),
                            isValid = false
                        )
                    )
                )
            )
        }
    }

    fun updateExtremeHandshakeTimeout(value: String) {
        val timeout = value.toIntOrNull()
        if (timeout != null && timeout > 0) {
            prefs.extremeHandshakeTimeout = timeout
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    extreme = _settingsState.value.performance.extreme.copy(
                        extremeHandshakeTimeout = InputFieldState(value)
                    )
                )
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    extreme = _settingsState.value.performance.extreme.copy(
                        extremeHandshakeTimeout = InputFieldState(
                            value = value,
                            error = application.getString(R.string.invalid_timeout),
                            isValid = false
                        )
                    )
                )
            )
        }
    }

    fun updateExtremeUplinkOnly(value: String) {
        val uplink = value.toIntOrNull()
        if (uplink != null && uplink >= 0) {
            prefs.extremeUplinkOnly = uplink
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    extreme = _settingsState.value.performance.extreme.copy(
                        extremeUplinkOnly = InputFieldState(value)
                    )
                )
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    extreme = _settingsState.value.performance.extreme.copy(
                        extremeUplinkOnly = InputFieldState(
                            value = value,
                            error = "Invalid value",
                            isValid = false
                        )
                    )
                )
            )
        }
    }

    fun updateExtremeDownlinkOnly(value: String) {
        val downlink = value.toIntOrNull()
        if (downlink != null && downlink >= 0) {
            prefs.extremeDownlinkOnly = downlink
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    extreme = _settingsState.value.performance.extreme.copy(
                        extremeDownlinkOnly = InputFieldState(value)
                    )
                )
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    extreme = _settingsState.value.performance.extreme.copy(
                        extremeDownlinkOnly = InputFieldState(
                            value = value,
                            error = "Invalid value",
                            isValid = false
                        )
                    )
                )
            )
        }
    }

    fun updateExtremeDnsCacheSize(value: String) {
        val cacheSize = value.toIntOrNull()
        if (cacheSize != null && cacheSize > 0) {
            prefs.extremeDnsCacheSize = cacheSize
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    extreme = _settingsState.value.performance.extreme.copy(
                        extremeDnsCacheSize = InputFieldState(value)
                    )
                )
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    extreme = _settingsState.value.performance.extreme.copy(
                        extremeDnsCacheSize = InputFieldState(
                            value = value,
                            error = "Invalid cache size",
                            isValid = false
                        )
                    )
                )
            )
        }
    }

    fun setExtremeDisableFakeDns(enabled: Boolean) {
        prefs.extremeDisableFakeDns = enabled
        _settingsState.value = _settingsState.value.copy(
            performance = _settingsState.value.performance.copy(
                extreme = _settingsState.value.performance.extreme.copy(
                    extremeDisableFakeDns = enabled
                )
            )
        )
    }

    fun setExtremeRoutingOptimization(enabled: Boolean) {
        prefs.extremeRoutingOptimization = enabled
        _settingsState.value = _settingsState.value.copy(
            performance = _settingsState.value.performance.copy(
                extreme = _settingsState.value.performance.extreme.copy(
                    extremeRoutingOptimization = enabled
                )
            )
        )
    }

    fun updateMaxConcurrentConnections(value: String) {
        val maxConn = value.toIntOrNull()
        if (maxConn != null && maxConn >= 0) {
            prefs.maxConcurrentConnections = maxConn
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    extreme = _settingsState.value.performance.extreme.copy(
                        maxConcurrentConnections = InputFieldState(value)
                    )
                )
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                performance = _settingsState.value.performance.copy(
                    extreme = _settingsState.value.performance.extreme.copy(
                        maxConcurrentConnections = InputFieldState(
                            value = value,
                            error = "Invalid value (0 = unlimited)",
                            isValid = false
                        )
                    )
                )
            )
        }
    }

    fun setParallelDnsQueries(enabled: Boolean) {
        prefs.parallelDnsQueries = enabled
        _settingsState.value = _settingsState.value.copy(
            performance = _settingsState.value.performance.copy(
                extreme = _settingsState.value.performance.extreme.copy(
                    parallelDnsQueries = enabled
                )
            )
        )
    }

    fun setExtremeProxyOptimization(enabled: Boolean) {
        prefs.extremeProxyOptimization = enabled
        _settingsState.value = _settingsState.value.copy(
            performance = _settingsState.value.performance.copy(
                extreme = _settingsState.value.performance.extreme.copy(
                    extremeProxyOptimization = enabled
                )
            )
        )
    }

    fun setBypassDomains(domains: List<String>) {
        prefs.bypassDomains = domains
        _settingsState.value = _settingsState.value.copy(
            bypassDomains = domains
        )
    }

    fun setBypassIps(ips: List<String>) {
        prefs.bypassIps = ips
        _settingsState.value = _settingsState.value.copy(
            bypassIps = ips
        )
    }

    fun importRuleFile(uri: Uri, fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = fileManager.importRuleFile(uri, fileName)
            if (success) {
                when (fileName) {
                    "geoip.dat" -> {
                        _settingsState.value = _settingsState.value.copy(
                            files = _settingsState.value.files.copy(
                                isGeoipCustom = prefs.customGeoipImported
                            ),
                            info = _settingsState.value.info.copy(
                                geoipSummary = fileManager.getRuleFileSummary("geoip.dat")
                            )
                        )
                    }

                    "geosite.dat" -> {
                        _settingsState.value = _settingsState.value.copy(
                            files = _settingsState.value.files.copy(
                                isGeositeCustom = prefs.customGeositeImported
                            ),
                            info = _settingsState.value.info.copy(
                                geositeSummary = fileManager.getRuleFileSummary("geosite.dat")
                            )
                        )
                    }
                }
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        "$fileName ${application.getString(R.string.import_success)}"
                    )
                )
            } else {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.import_failed)))
            }
        }
    }

    fun showExportFailedSnackbar() {
        _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.export_failed)))
    }

    fun startTProxyService(action: String) {
        // Check config file synchronously for immediate feedback
        if (_selectedConfigFile.value == null) {
            viewModelScope.launch {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.not_select_config)))
            }
            Log.w(TAG, "Cannot start service: no config file selected.")
            setControlMenuClickable(true)
            return
        }
        
        // Create and send intent immediately (non-blocking)
        val intent = Intent(application, TProxyService::class.java).setAction(action)
        viewModelScope.launch {
            _uiEvent.trySend(MainViewUiEvent.StartService(intent))
        }
    }

    fun editConfig(filePath: String) {
        viewModelScope.launch {
            configEditViewModel = ConfigEditViewModel(application, filePath, prefs)
            _uiEvent.trySend(MainViewUiEvent.Navigate(ROUTE_CONFIG_EDIT))
        }
    }

    fun shareIntent(chooserIntent: Intent, packageManager: PackageManager) {
        viewModelScope.launch {
            if (chooserIntent.resolveActivity(packageManager) != null) {
                _uiEvent.trySend(MainViewUiEvent.ShareLauncher(chooserIntent))
                Log.d(TAG, "Export intent resolved and started.")
            } else {
                Log.w(TAG, "No activity found to handle export intent.")
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        application.getString(R.string.no_app_for_export)
                    )
                )
            }
        }
    }

    fun stopTProxyService() {
        // Create and send intent immediately (non-blocking)
        val intent = Intent(
            application,
            TProxyService::class.java
        ).setAction(TProxyService.ACTION_DISCONNECT)
        viewModelScope.launch {
            _uiEvent.trySend(MainViewUiEvent.StartService(intent))
        }
    }

    fun prepareAndStartVpn(vpnPrepareLauncher: ActivityResultLauncher<Intent>) {
        // Check config file synchronously for immediate feedback
        if (_selectedConfigFile.value == null) {
            viewModelScope.launch {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.not_select_config)))
            }
            Log.w(TAG, "Cannot prepare VPN: no config file selected.")
            setControlMenuClickable(true)
            return
        }
        
        // Check VPN permission and launch immediately
        viewModelScope.launch {
            val vpnIntent = VpnService.prepare(application)
            if (vpnIntent != null) {
                vpnPrepareLauncher.launch(vpnIntent)
            } else {
                startTProxyService(TProxyService.ACTION_CONNECT)
            }
        }
    }

    fun navigateToAppList() {
        viewModelScope.launch {
            appListViewModel = AppListViewModel(application)
            _uiEvent.trySend(MainViewUiEvent.Navigate(ROUTE_APP_LIST))
        }
    }

    fun navigateToAiInsights() {
        viewModelScope.launch {
            _uiEvent.trySend(MainViewUiEvent.Navigate(ROUTE_AI_INSIGHTS))
        }
    }

    fun moveConfigFile(fromIndex: Int, toIndex: Int) {
        val currentList = _configFiles.value.toMutableList()
        val movedItem = currentList.removeAt(fromIndex)
        currentList.add(toIndex, movedItem)
        _configFiles.value = currentList
        prefs.configFilesOrder = currentList.map { it.name }
    }

    fun refreshConfigFileList() {
        viewModelScope.launch(Dispatchers.IO) {
            val filesDir = application.filesDir
            val actualFiles =
                filesDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.toList()
                    ?: emptyList()
            val actualFilesByName = actualFiles.associateBy { it.name }
            val savedOrder = prefs.configFilesOrder

            val newOrder = mutableListOf<File>()
            val remainingActualFileNames = actualFilesByName.toMutableMap()

            savedOrder.forEach { filename ->
                actualFilesByName[filename]?.let { file ->
                    newOrder.add(file)
                    remainingActualFileNames.remove(filename)
                }
            }

            newOrder.addAll(remainingActualFileNames.values.filter { it !in newOrder })

            _configFiles.value = newOrder
            prefs.configFilesOrder = newOrder.map { it.name }

            val currentSelectedPath = prefs.selectedConfigPath
            var fileToSelect: File? = null

            if (currentSelectedPath != null) {
                val foundSelected = newOrder.find { it.absolutePath == currentSelectedPath }
                if (foundSelected != null) {
                    fileToSelect = foundSelected
                }
            }

            if (fileToSelect == null) {
                fileToSelect = newOrder.firstOrNull()
            }

            _selectedConfigFile.value = fileToSelect
            prefs.selectedConfigPath = fileToSelect?.absolutePath
        }
    }

    fun updateSelectedConfigFile(file: File?) {
        _selectedConfigFile.value = file
        prefs.selectedConfigPath = file?.absolutePath
    }

    fun updateConnectivityTestTarget(target: String) {
        val isValid = try {
            val url = URL(target)
            url.protocol == "http" || url.protocol == "https"
        } catch (e: Exception) {
            false
        }
        if (isValid) {
            prefs.connectivityTestTarget = target
            _settingsState.value = _settingsState.value.copy(
                connectivityTestTarget = InputFieldState(target)
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                connectivityTestTarget = InputFieldState(
                    value = target,
                    error = application.getString(R.string.connectivity_test_invalid_url),
                    isValid = false
                )
            )
        }
    }

    fun updateConnectivityTestTimeout(timeout: String) {
        val timeoutInt = timeout.toIntOrNull()
        if (timeoutInt != null && timeoutInt > 0) {
            prefs.connectivityTestTimeout = timeoutInt
            _settingsState.value = _settingsState.value.copy(
                connectivityTestTimeout = InputFieldState(timeout)
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                connectivityTestTimeout = InputFieldState(
                    value = timeout,
                    error = application.getString(R.string.invalid_timeout),
                    isValid = false
                )
            )
        }
    }

    fun testConnectivity() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = prefs
            val url: URL
            try {
                url = URL(prefs.connectivityTestTarget)
            } catch (e: Exception) {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.connectivity_test_invalid_url)))
                return@launch
            }
            val host = url.host
            val port = if (url.port > 0) url.port else url.defaultPort
            val path = if (url.path.isNullOrEmpty()) "/" else url.path
            val isHttps = url.protocol == "https"
            val proxy =
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(prefs.socksAddress, prefs.socksPort))
            val timeout = prefs.connectivityTestTimeout
            val start = System.currentTimeMillis()
            try {
                Socket(proxy).use { socket ->
                    socket.soTimeout = timeout
                    socket.connect(InetSocketAddress(host, port), timeout)
                    val (writer, reader) = if (isHttps) {
                        val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                            .createSocket(socket, host, port, true) as javax.net.ssl.SSLSocket
                        sslSocket.startHandshake()
                        Pair(
                            sslSocket.outputStream.bufferedWriter(),
                            sslSocket.inputStream.bufferedReader()
                        )
                    } else {
                        Pair(
                            socket.getOutputStream().bufferedWriter(),
                            socket.getInputStream().bufferedReader()
                        )
                    }
                    writer.write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n")
                    writer.flush()
                    val firstLine = reader.readLine()
                    val latency = System.currentTimeMillis() - start
                    if (firstLine != null && firstLine.startsWith("HTTP/")) {
                        _uiEvent.trySend(
                            MainViewUiEvent.ShowSnackbar(
                                application.getString(
                                    R.string.connectivity_test_latency,
                                    latency.toInt()
                                )
                            )
                        )
                    } else {
                        _uiEvent.trySend(
                            MainViewUiEvent.ShowSnackbar(
                                application.getString(R.string.connectivity_test_failed)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        application.getString(R.string.connectivity_test_failed)
                    )
                )
            }
        }
    }

    /**
     * Registers broadcast receivers for TProxyService events.
     * This method is idempotent - it will not register receivers multiple times.
     * Called in init block to ensure receivers are always registered when ViewModel is created.
     * 
     * Note: This method is synchronous and can be called from init.
     * The registration happens immediately on the calling thread.
     */
    fun registerTProxyServiceReceivers() {
        // Check if receivers are already registered to prevent double registration
        // Note: @Volatile ensures visibility across threads
        if (receiversRegistered) {
            Log.d(TAG, "Receivers already registered, skipping.")
            return
        }
        
        // Use synchronized block for thread safety
        // Since this is called from init, we use a simple lock
        synchronized(receiversLock) {
            // Double-check pattern: check again after acquiring lock
            if (receiversRegistered) {
                Log.d(TAG, "Receivers already registered (double-check), skipping.")
                return@synchronized
            }
            
            try {
                val application = application
                val startSuccessFilter = IntentFilter(TProxyService.ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    application.registerReceiver(
                        startReceiver,
                        startSuccessFilter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    application.registerReceiver(startReceiver, startSuccessFilter)
                }

                val stopSuccessFilter = IntentFilter(TProxyService.ACTION_STOP)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    application.registerReceiver(
                        stopReceiver,
                        stopSuccessFilter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    application.registerReceiver(stopReceiver, stopSuccessFilter)
                }

                val errorFilter = IntentFilter(TProxyService.ACTION_ERROR)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    application.registerReceiver(
                        errorReceiver,
                        errorFilter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    application.registerReceiver(errorReceiver, errorFilter)
                }

                val socks5ReadyFilter = IntentFilter(TProxyService.ACTION_SOCKS5_READY)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    application.registerReceiver(
                        socks5ReadyReceiver,
                        socks5ReadyFilter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    application.registerReceiver(socks5ReadyReceiver, socks5ReadyFilter)
                }
                
                receiversRegistered = true
                Log.d(TAG, "TProxyService receivers registered.")
            } catch (e: Exception) {
                Log.e(TAG, "Error registering receivers: ${e.message}", e)
                // Don't set receiversRegistered = true on error
            }
        }
    }

    /**
     * Unregisters broadcast receivers for TProxyService events.
     * This method is idempotent - it will not throw if receivers are not registered.
     * Called in onCleared() to ensure receivers are always unregistered when ViewModel is destroyed.
     * 
     * Note: This method is synchronous and can be called from onCleared().
     * The unregistration happens immediately on the calling thread.
     */
    fun unregisterTProxyServiceReceivers() {
        // Check if receivers are already unregistered to prevent double unregistration
        // Note: @Volatile ensures visibility across threads
        if (!receiversRegistered) {
            Log.d(TAG, "Receivers already unregistered, skipping.")
            return
        }
        
        // Use synchronized block for thread safety
        // Since onCleared() is called synchronously on the main thread, we use a simple lock
        synchronized(receiversLock) {
            // Double-check pattern: check again after acquiring lock
            if (!receiversRegistered) {
                Log.d(TAG, "Receivers already unregistered (double-check), skipping.")
                return@synchronized
            }
            
            try {
                val application = application
                // Unregister each receiver, handling IllegalArgumentException if not registered
                try {
                    application.unregisterReceiver(startReceiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "startReceiver not registered: ${e.message}")
                }
                
                try {
                    application.unregisterReceiver(stopReceiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "stopReceiver not registered: ${e.message}")
                }
                
                try {
                    application.unregisterReceiver(errorReceiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "errorReceiver not registered: ${e.message}")
                }
                
                receiversRegistered = false
                Log.d(TAG, "TProxyService receivers unregistered.")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receivers: ${e.message}", e)
                // Set receiversRegistered = false even on error to allow retry
                receiversRegistered = false
            }
        }
    }

    fun restoreDefaultGeoip(callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            fileManager.restoreDefaultGeoip()
            _settingsState.value = _settingsState.value.copy(
                files = _settingsState.value.files.copy(
                    isGeoipCustom = prefs.customGeoipImported
                ),
                info = _settingsState.value.info.copy(
                    geoipSummary = fileManager.getRuleFileSummary("geoip.dat")
                )
            )
            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.rule_file_restore_geoip_success)))
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Restored default geoip.dat.")
                callback()
            }
        }
    }

    fun restoreDefaultGeosite(callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            fileManager.restoreDefaultGeosite()
            _settingsState.value = _settingsState.value.copy(
                files = _settingsState.value.files.copy(
                    isGeositeCustom = prefs.customGeositeImported
                ),
                info = _settingsState.value.info.copy(
                    geositeSummary = fileManager.getRuleFileSummary("geosite.dat")
                )
            )
            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.rule_file_restore_geosite_success)))
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Restored default geosite.dat.")
                callback()
            }
        }
    }

    fun cancelDownload(fileName: String) {
        viewModelScope.launch {
            if (fileName == "geoip.dat") {
                geoipDownloadJob?.cancel()
            } else {
                geositeDownloadJob?.cancel()
            }
            Log.d(TAG, "Download cancellation requested for $fileName")
        }
    }

    fun downloadRuleFile(url: String, fileName: String) {
        val currentJob = if (fileName == "geoip.dat") geoipDownloadJob else geositeDownloadJob
        if (currentJob?.isActive == true) {
            Log.w(TAG, "Download already in progress for $fileName")
            return
        }

        val job = viewModelScope.launch(Dispatchers.IO) {
            val progressFlow = if (fileName == "geoip.dat") {
                prefs.geoipUrl = url
                _geoipDownloadProgress
            } else {
                prefs.geositeUrl = url
                _geositeDownloadProgress
            }

            val proxy = if (_isServiceEnabled.value) {
                Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", prefs.socksPort))
            } else {
                null
            }
            val client = NetworkModule.getHttpClientFactory().createHttpClient(proxy)

            try {
                progressFlow.value = application.getString(R.string.connecting)

                val request = Request.Builder().url(url).build()
                val call = client.newCall(request)
                val response = call.await()

                if (!response.isSuccessful) {
                    throw IOException("Failed to download file: ${response.code}")
                }

                val body = response.body ?: throw IOException("Response body is null")
                val totalBytes = body.contentLength()
                var bytesRead = 0L
                var lastProgress = -1

                body.byteStream().use { inputStream ->
                    val success = fileManager.saveRuleFile(inputStream, fileName) { read ->
                        ensureActive()
                        bytesRead += read
                        if (totalBytes > 0) {
                            val progress = (bytesRead * 100 / totalBytes).toInt()
                            if (progress != lastProgress) {
                                progressFlow.value =
                                    application.getString(R.string.downloading, progress)
                                lastProgress = progress
                            }
                        } else {
                            if (lastProgress == -1) {
                                progressFlow.value =
                                    application.getString(R.string.downloading_no_size)
                                lastProgress = 0
                            }
                        }
                    }
                    if (success) {
                        when (fileName) {
                            "geoip.dat" -> {
                                _settingsState.value = _settingsState.value.copy(
                                    files = _settingsState.value.files.copy(
                                        isGeoipCustom = prefs.customGeoipImported
                                    ),
                                    info = _settingsState.value.info.copy(
                                        geoipSummary = fileManager.getRuleFileSummary("geoip.dat")
                                    )
                                )
                            }

                            "geosite.dat" -> {
                                _settingsState.value = _settingsState.value.copy(
                                    files = _settingsState.value.files.copy(
                                        isGeositeCustom = prefs.customGeositeImported
                                    ),
                                    info = _settingsState.value.info.copy(
                                        geositeSummary = fileManager.getRuleFileSummary("geosite.dat")
                                    )
                                )
                            }
                        }
                        _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.download_success)))
                    } else {
                        _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.download_failed)))
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled for $fileName")
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.download_cancelled)))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download rule file", e)
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.download_failed) + ": " + e.message))
            } finally {
                progressFlow.value = null
                updateSettingsState()
            }
        }

        if (fileName == "geoip.dat") {
            geoipDownloadJob = job
        } else {
            geositeDownloadJob = job
        }

        job.invokeOnCompletion {
            if (fileName == "geoip.dat") {
                geoipDownloadJob = null
            } else {
                geositeDownloadJob = null
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response, null)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWith(Result.failure(e))
            }
        })
        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {
            }
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            _isCheckingForUpdates.value = true
            val proxy = if (_isServiceEnabled.value) {
                Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", prefs.socksPort))
            } else {
                null
            }
            val client = NetworkModule.getHttpClientFactory().createHttpClient(proxy)

            val request = Request.Builder()
                .url(application.getString(R.string.source_url) + "/releases/latest")
                .head()
                .build()

            try {
                val response = client.newCall(request).await()
                val location = response.request.url.toString()
                val latestTag = location.substringAfterLast("/tag/v")
                Log.d(TAG, "Latest version tag: $latestTag")
                val updateAvailable = compareVersions(latestTag) > 0
                if (updateAvailable) {
                    _newVersionAvailable.value = latestTag
                } else {
                    _uiEvent.trySend(
                        MainViewUiEvent.ShowSnackbar(
                            application.getString(R.string.no_new_version_available)
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        application.getString(R.string.failed_to_check_for_updates) + ": " + e.message
                    )
                )
            } finally {
                _isCheckingForUpdates.value = false
            }
        }
    }

    fun downloadNewVersion(versionTag: String) {
        val url = application.getString(R.string.source_url) + "/releases/tag/v$versionTag"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        application.startActivity(intent)
        _newVersionAvailable.value = null
    }

    fun clearNewVersionAvailable() {
        _newVersionAvailable.value = null
    }

    private fun compareVersions(version1: String): Int {
        val parts1 = version1.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 =
            BuildConfig.VERSION_NAME.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) {
                return p1.compareTo(p2)
            }
        }
        return 0
    }

    companion object {
        private const val IPV4_REGEX =
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
        private val IPV4_PATTERN: Pattern = Pattern.compile(IPV4_REGEX)
        private const val IPV6_REGEX =
            "^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80::(fe80(:[0-9a-fA-F]{0,4})?){0,4}%[0-9a-zA-Z]+|::(ffff(:0{1,4})?:)?((25[0-5]|(2[0-4]|1?\\d)?\\d)\\.){3}(25[0-5]|(2[0-4]|1?\\d)?\\d)|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1?\\d)?\\d)\\.){3}(25[0-5]|(2[0-4]|1?\\d)?\\d))$"
        private val IPV6_PATTERN: Pattern = Pattern.compile(IPV6_REGEX)

        @Suppress("DEPRECATION")
        fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return activityManager.getRunningServices(Int.MAX_VALUE).any { service ->
                serviceClass.name == service.service.className
            }
        }
    }
}

/**
 * Factory for creating MainViewModel instances.
 */
class MainViewModelFactory(
    private val application: Application
) : ViewModelProvider.AndroidViewModelFactory(application) {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

