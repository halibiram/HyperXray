package com.hyperxray.an.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.hyperxray.an.BuildConfig
import com.hyperxray.an.R
import com.hyperxray.an.common.ROUTE_APP_LIST
import com.hyperxray.an.common.formatBytes
import com.hyperxray.an.common.formatThroughput
import com.hyperxray.an.common.ROUTE_CONFIG_EDIT
import com.hyperxray.an.common.ThemeMode
import com.hyperxray.an.core.monitor.XrayStatsManager
import com.hyperxray.an.core.service.ServiceEventObserver
import com.hyperxray.an.core.service.ServiceEvent
import com.hyperxray.an.data.repository.AppUpdateRepository
import com.hyperxray.an.data.repository.ConfigRepository
import com.hyperxray.an.data.repository.SettingsRepository
import com.hyperxray.an.domain.usecase.VpnConnectionUseCase
import com.hyperxray.an.core.network.ConnectivityTester
import com.hyperxray.an.core.network.ConnectivityTestConfig
import com.hyperxray.an.core.network.ConnectivityTestResult
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.vpn.HyperVpnService
import com.hyperxray.an.telemetry.TelemetryStore
import com.hyperxray.an.telemetry.AggregatedTelemetry
import com.hyperxray.an.vpn.HyperVpnHelper
import com.hyperxray.an.core.network.vpn.HyperVpnStateManager
import com.hyperxray.an.common.AiLogHelper
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import com.hyperxray.an.feature.dashboard.ConnectionState
import com.hyperxray.an.feature.dashboard.ConnectionStage
import com.hyperxray.an.feature.dashboard.DisconnectionStage
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.regex.Pattern
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
    data object ShowWarpAccountRequiredDialog : MainViewUiEvent()
}

/**
 * Main ViewModel managing app state, connection control, config management, and settings.
 * Coordinates between UI and HyperVpnService, handles stats collection, and manages preferences.
 */
class MainViewModel(
    application: Application,
    private val configRepository: ConfigRepository,
    private val settingsRepository: SettingsRepository,
    private val serviceEventObserver: ServiceEventObserver,
    private val appUpdateRepository: AppUpdateRepository,
    private val connectivityTester: ConnectivityTester,
    private val telemetryStore: TelemetryStore
) : AndroidViewModel(application) {
    val prefs: Preferences = Preferences(application)
    private val activityScope: CoroutineScope = viewModelScope
    
    // Xray stats manager - created in init block since it needs viewModelScope
    private lateinit var xrayStatsManager: XrayStatsManager
    
    // Android memory stats manager - created in init block since it needs viewModelScope
    private lateinit var androidMemoryStatsManager: com.hyperxray.an.core.monitor.AndroidMemoryStatsManager

    var reloadView: (() -> Unit)? = null

    lateinit var appListViewModel: AppListViewModel
    lateinit var configEditViewModel: ConfigEditViewModel
    
    // Cached dashboard adapter instance
    private var _dashboardAdapter: MainViewModelDashboardAdapter? = null
    
    fun getOrCreateDashboardAdapter(): MainViewModelDashboardAdapter {
        if (_dashboardAdapter == null) {
            _dashboardAdapter = MainViewModelDashboardAdapter(this)
        }
        return _dashboardAdapter!!
    }

    // Settings state is now managed by SettingsRepository
    val settingsState: StateFlow<SettingsState> = settingsRepository.settingsState

    // Core stats state - delegated to XrayStatsManager (initialized in init block)
    lateinit var coreStatsState: StateFlow<CoreStatsState>
    
    private val _telemetryState = MutableStateFlow<AggregatedTelemetry?>(null)
    val telemetryState: StateFlow<AggregatedTelemetry?> = _telemetryState.asStateFlow()

    private val _controlMenuClickable = MutableStateFlow(true)
    val controlMenuClickable: StateFlow<Boolean> = _controlMenuClickable.asStateFlow()

    private val _isServiceEnabled = MutableStateFlow(false)
    val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()
    
    // Connection state is now managed by VpnConnectionUseCase (initialized in init block)
    lateinit var connectionState: StateFlow<com.hyperxray.an.feature.dashboard.ConnectionState>

    // VPN Connection Use Case - created in init block after StateFlows are declared
    private lateinit var vpnConnectionUseCase: VpnConnectionUseCase
    
    // HyperVpnService state manager
    private val hyperVpnStateManager = HyperVpnStateManager(application)
    
    // HyperVpnService state flows
    val hyperVpnState: StateFlow<HyperVpnStateManager.VpnState> = hyperVpnStateManager.state
    val hyperVpnStats: StateFlow<HyperVpnStateManager.TunnelStats> = hyperVpnStateManager.stats
    val hyperVpnError: StateFlow<String?> = hyperVpnStateManager.error
    
    // WARP Account state
    private val _warpAccountInfo = MutableStateFlow<com.hyperxray.an.feature.dashboard.WarpAccountInfo>(
        com.hyperxray.an.feature.dashboard.WarpAccountInfo()
    )
    val warpAccountInfo: StateFlow<com.hyperxray.an.feature.dashboard.WarpAccountInfo> = _warpAccountInfo.asStateFlow()
    
    // Show WARP account creation modal
    private val _showWarpAccountModal = MutableStateFlow(false)
    val showWarpAccountModal: StateFlow<Boolean> = _showWarpAccountModal.asStateFlow()
    
    // WARP account creation in progress
    private val _isCreatingWarpAccount = MutableStateFlow(false)
    val isCreatingWarpAccount: StateFlow<Boolean> = _isCreatingWarpAccount.asStateFlow()
    
    /**
     * Show/hide WARP account creation dialog
     */
    fun setShowWarpAccountDialog(show: Boolean) {
        _showWarpAccountModal.value = show
    }

    private val _uiEvent = Channel<MainViewUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()
    
    /**
     * Emit a UI event (for use by adapters and other internal components)
     */
    fun emitUiEvent(event: MainViewUiEvent) {
        _uiEvent.trySend(event)
    }

    // Config files state - delegated to ConfigRepository
    val configFiles: StateFlow<List<File>> = configRepository.configFiles
    val selectedConfigFile: StateFlow<File?> = configRepository.selectedConfigFile

    // Download progress - delegated to ConfigRepository
    val geoipDownloadProgress: StateFlow<String?> = configRepository.geoipDownloadProgress
    val geositeDownloadProgress: StateFlow<String?> = configRepository.geositeDownloadProgress

    private val _isCheckingForUpdates = MutableStateFlow(false)
    val isCheckingForUpdates: StateFlow<Boolean> = _isCheckingForUpdates.asStateFlow()

    private val _newVersionAvailable = MutableStateFlow<String?>(null)
    val newVersionAvailable: StateFlow<String?> = _newVersionAvailable.asStateFlow()

    // Theme state management
    private val _systemNightMode = MutableStateFlow<Int>(android.content.res.Configuration.UI_MODE_NIGHT_NO)
    // CRITICAL FIX: Using SharingStarted.Lazily to ensure restart after process death
    val isDarkTheme: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(
        settingsState,
        _systemNightMode
    ) { state, nightMode ->
        com.hyperxray.an.common.ThemeUtils.calculateIsDark(
            state.switches.themeMode,
            nightMode
        )
    }.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.Lazily, // Restart after process death
        initialValue = false
    )

    /**
     * Update system night mode and trigger theme recalculation.
     */
    fun updateSystemNightMode(nightMode: Int) {
        _systemNightMode.value = nightMode
    }

    init {
        Log.d(TAG, "MainViewModel initialized.")
        
        // Initialize XrayStatsManager - needs viewModelScope which is only available here
        // Use default port 65276 if apiPort is 0 (same as ConfigInjector)
        // Pass HyperVpnStateManager to enable gRPC availability check from VPN service process
        xrayStatsManager = XrayStatsManager(
            scope = viewModelScope,
            apiPortProvider = { 
                val port = prefs.apiPort
                if (port > 0 && port <= 65535) port else 65276
            },
            hyperVpnStateManager = hyperVpnStateManager
        )
        
        // Initialize coreStatsState after XrayStatsManager is created
        coreStatsState = xrayStatsManager.stats
        
        // Initialize AndroidMemoryStatsManager - needs viewModelScope which is only available here
        // Pass XrayStatsManager reference to enable Go runtime memory stats collection
        androidMemoryStatsManager = com.hyperxray.an.core.monitor.AndroidMemoryStatsManager(
            context = application,
            scope = viewModelScope,
            xrayStatsManager = xrayStatsManager
        )
        
        // Initialize VPN Connection Use Case after StateFlows and XrayStatsManager are declared
        vpnConnectionUseCase = VpnConnectionUseCase(
            context = application,
            scope = viewModelScope,
            xrayStatsManager = xrayStatsManager,
            settingsRepository = settingsRepository,
            isServiceEnabled = _isServiceEnabled.asStateFlow(),
            selectedConfigFile = selectedConfigFile,
            onStartService = { intent -> _uiEvent.trySend(MainViewUiEvent.StartService(intent)) },
            onStopService = { intent -> _uiEvent.trySend(MainViewUiEvent.StartService(intent)) } // Note: StopService uses same event type but different intent action
        )
        
        // Start observing HyperVpnService
        hyperVpnStateManager.startObserving()
        
        // Load WARP account info initially
        loadWarpAccountInfo()
        
        // Start watching WARP account file for changes (updates dashboard automatically)
        startWarpAccountFileWatcher()
        
        // Map HyperVpnStateManager state to connectionState for dashboard
        // This ensures dashboard reflects the actual VPN service state
        // CRITICAL FIX: Using SharingStarted.Lazily to ensure restart after process death
        connectionState = hyperVpnState.map { vpnState ->
            when (vpnState) {
                is HyperVpnStateManager.VpnState.Connected -> com.hyperxray.an.feature.dashboard.ConnectionState.Connected
                is HyperVpnStateManager.VpnState.Connecting -> com.hyperxray.an.feature.dashboard.ConnectionState.Connecting(
                    stage = com.hyperxray.an.feature.dashboard.ConnectionStage.ESTABLISHING,
                    progress = vpnState.progress
                )
                is HyperVpnStateManager.VpnState.Disconnecting -> com.hyperxray.an.feature.dashboard.ConnectionState.Disconnecting(
                    stage = com.hyperxray.an.feature.dashboard.DisconnectionStage.STOPPING_VPN,
                    progress = vpnState.progress
                )
                is HyperVpnStateManager.VpnState.Error -> com.hyperxray.an.feature.dashboard.ConnectionState.Failed(
                    error = vpnState.getMessage(),
                    retryCountdownSeconds = if (vpnState.retryable) null else null
                )
                is HyperVpnStateManager.VpnState.Disconnected -> com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected
            }
        }.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.Lazily, // Restart after process death
            initialValue = com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected
        )
        
        // Optimize: Update isServiceEnabled immediately when hyperVpnState changes
        // This ensures VpnConnectionUseCase disconnect doesn't wait for ServiceEvent.Stopped
        viewModelScope.launch {
            hyperVpnState.collect { vpnState ->
                val shouldBeEnabled = when (vpnState) {
                    is HyperVpnStateManager.VpnState.Connected,
                    is HyperVpnStateManager.VpnState.Connecting -> true
                    is HyperVpnStateManager.VpnState.Disconnected,
                    is HyperVpnStateManager.VpnState.Disconnecting,
                    is HyperVpnStateManager.VpnState.Error -> false
                }
                if (_isServiceEnabled.value != shouldBeEnabled) {
                    _isServiceEnabled.value = shouldBeEnabled
                    AiLogHelper.d(TAG, "isServiceEnabled updated to $shouldBeEnabled based on hyperVpnState: ${vpnState.javaClass.simpleName}")
                }
            }
        }
        
        // Start observing service events
        serviceEventObserver.startObserving(application)
        
        // Collect service events
        viewModelScope.launch {
            serviceEventObserver.events.collect { event ->
                when (event) {
                    is ServiceEvent.Started -> {
                        Log.d(TAG, "Service started")
                        setServiceEnabled(true)
                        setControlMenuClickable(true)
                        
                        // Auto-connect is disabled by default to prevent cancellation loops
                        // User must manually trigger connect() via UI
                        // This prevents the issue where auto-connect cancels user-initiated connections
                        Log.d(TAG, "Service started, auto-connect is disabled. User must manually trigger connect()")
                    }
                    is ServiceEvent.Stopped -> {
                        Log.d(TAG, "Service stopped")
                        setServiceEnabled(false)
                        setControlMenuClickable(true)
                        
                        // CRITICAL: Complete disconnection process when service stops
                        // This ensures StatusCard shows proper disconnection stages
                        // Note: VpnConnectionUseCase handles disconnection state transitions internally
                        
                        // Stop stats monitoring when service stops
                        xrayStatsManager.stopMonitoring()
                    }
                    is ServiceEvent.Error -> {
                        Log.e(TAG, "Service error: ${event.errorMessage}")
                        _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(event.errorMessage))
                        // Also stop the service state since it failed to start
                        setServiceEnabled(false)
                        setControlMenuClickable(true)
                    }
                    else -> {
                        // Handle any other events (e.g., log updates)
                        Log.d(TAG, "Service event received: $event")
                    }
                }
            }
        }
        
        // Observe connection state changes to manage stats monitoring
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is com.hyperxray.an.feature.dashboard.ConnectionState.Connected -> {
                        startMonitoring()
                    }
                    is com.hyperxray.an.feature.dashboard.ConnectionState.Disconnected,
                    is com.hyperxray.an.feature.dashboard.ConnectionState.Failed -> {
                        stopMonitoring()
                    }
                    else -> {
                        // Connecting or Disconnecting states - no action needed
                    }
                }
            }
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isServiceEnabled.value = isServiceRunning(application, HyperVpnService::class.java)

                updateSettingsState()
                loadKernelVersion()
                configRepository.loadConfigs()
            } catch (e: Exception) {
                Log.e(TAG, "Error during initialization", e)
                // Don't crash the app, but log the error
                // UI will show default/empty states if initialization fails
            }
        }
        
        // Collect download progress flows from repository (no action needed, just for observation)
        // The flows are already exposed as StateFlows, so UI can observe them directly
    }

    /**
     * Called when the ViewModel is about to be destroyed.
     * Ensures service event observer is stopped to prevent memory leaks.
     */
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "MainViewModel cleared, stopping service event observer.")
        
        // Cleanup dashboard adapter
        _dashboardAdapter = null
        
        // Stop monitoring before cleanup
        stopMonitoring()
        
        // Cleanup service event observer
        serviceEventObserver.stopObserving(application)
        
        // Cleanup use case resources
        vpnConnectionUseCase.cleanup()
        
        // Cleanup stats manager resources
        xrayStatsManager.cleanup()
        
        // Cleanup memory stats manager
        androidMemoryStatsManager.stopMonitoring()
        
        // Cleanup repository resources
        configRepository.cleanup()
        
        // Cleanup HyperVpnService observer
        hyperVpnStateManager.stopObserving()
        
        Log.d(TAG, "MainViewModel cleanup completed")
    }
    
    /**
     * Start HyperVpnService with WARP
     * Checks if WARP account exists, if not shows dialog to create one
     */
    fun startHyperVpn() {
        viewModelScope.launch {
            try {
                // Check if WARP account exists
                val filesDir = getApplication<Application>().filesDir
                val accountFile = File(filesDir, "warp-account.json")
                
                if (!accountFile.exists() || !accountFile.canRead()) {
                    // No WARP account - show dialog to create one
                    Log.d(TAG, "No WARP account found, showing create account dialog")
                    _uiEvent.trySend(MainViewUiEvent.ShowWarpAccountRequiredDialog)
                    return@launch
                }
                
                // Validate account file has required fields
                try {
                    val accountContent = accountFile.readText()
                    val accountJson = JSONObject(accountContent)
                    val privateKey = accountJson.optString("privateKey", "")
                    
                    if (privateKey.isBlank()) {
                        Log.w(TAG, "WARP account file exists but privateKey is missing")
                        _uiEvent.trySend(MainViewUiEvent.ShowWarpAccountRequiredDialog)
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error validating WARP account: ${e.message}", e)
                    _uiEvent.trySend(MainViewUiEvent.ShowWarpAccountRequiredDialog)
                    return@launch
                }
                
                // WARP account exists and is valid - start VPN
                HyperVpnHelper.startVpnWithWarp(application)
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar("Starting HyperVpn..."))
            } catch (e: Exception) {
                Log.e(TAG, "Error starting HyperVpn: ${e.message}", e)
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar("Failed to start HyperVpn: ${e.message}"))
            }
        }
    }
    
    /**
     * Create WARP account and then start VPN
     */
    fun createWarpAccountAndConnect() {
        viewModelScope.launch {
            try {
                _isCreatingWarpAccount.value = true
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar("Creating WARP account..."))
                
                val filesDir = getApplication<Application>().filesDir
                val warpManager = com.hyperxray.an.util.WarpManager.getInstance()
                
                val result = warpManager.registerAndGetConfig(filesDir)
                
                if (result.isSuccess) {
                    Log.d(TAG, "✅ WARP account created successfully")
                    loadWarpAccountInfo() // Refresh dashboard
                    
                    // Now start VPN
                    HyperVpnHelper.startVpnWithWarp(application)
                    _uiEvent.trySend(MainViewUiEvent.ShowSnackbar("WARP account created, connecting..."))
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "❌ Failed to create WARP account: $error")
                    _uiEvent.trySend(MainViewUiEvent.ShowSnackbar("Failed to create WARP account: $error"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating WARP account: ${e.message}", e)
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar("Error: ${e.message}"))
            } finally {
                _isCreatingWarpAccount.value = false
            }
        }
    }
    
    /**
     * Stop HyperVpnService
     */
    fun stopHyperVpn() {
        viewModelScope.launch {
            try {
                HyperVpnHelper.stopVpn(application)
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar("Stopping HyperVpn..."))
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping HyperVpn: ${e.message}", e)
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar("Failed to stop HyperVpn: ${e.message}"))
            }
        }
    }
    
    /**
     * Check if HyperVpnService is running
     */
    fun isHyperVpnRunning(): Boolean {
        return hyperVpnStateManager.isServiceRunning()
    }
    
    /**
     * Clear HyperVpnService error
     */
    fun clearHyperVpnError() {
        hyperVpnStateManager.clearError()
    }

    private fun updateSettingsState() {
        // Update rule file info in settings repository
        settingsRepository.updateRuleFileInfo(
            geoipSummary = configRepository.getRuleFileSummary("geoip.dat"),
            geositeSummary = configRepository.getRuleFileSummary("geosite.dat")
        )
        settingsRepository.updateRuleFileUrls(
            geoipUrl = prefs.geoipUrl,
            geositeUrl = prefs.geositeUrl
        )
        settingsRepository.updateRuleFileCustomStatus(
            isGeoipCustom = prefs.customGeoipImported,
            isGeositeCustom = prefs.customGeositeImported
        )
    }

    private fun loadKernelVersion() {
        // DEPRECATED: Xray-core is embedded in libhyperxray.so, not available as separate libxray.so
        Log.d(TAG, "loadKernelVersion() skipped - Xray-core is embedded in libhyperxray.so")
        // Note: kernelVersion is not part of SettingsState managed by repository
        // This is a read-only info field, so we skip updating it
        settingsRepository.updateKernelVersion("Embedded in libhyperxray.so")
    }

    fun setControlMenuClickable(isClickable: Boolean) {
        _controlMenuClickable.value = isClickable
    }

    fun setServiceEnabled(enabled: Boolean) {
        _isServiceEnabled.value = enabled
        prefs.enable = enabled
        updateStatsManagerServiceState(enabled)
    }

    /**
     * Initiates backup process by launching file picker.
     * The actual backup creation is handled in the launcher callback.
     */
    fun performBackup(createFileLauncher: ActivityResultLauncher<String>) {
        val filename = "hyperxray_backup_" + System.currentTimeMillis() + ".dat"
        createFileLauncher.launch(filename)
    }

    /**
     * Handles backup file creation result from launcher.
     * Delegates to ConfigRepository for actual backup creation.
     */
    suspend fun handleBackupFileCreationResult(uri: Uri) {
        configRepository.createBackup(uri)
            .onSuccess {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.backup_success)))
            }
            .onFailure { e ->
                Log.e(TAG, "Backup failed", e)
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.backup_failed)))
            }
    }

    suspend fun startRestoreTask(uri: Uri) {
        withContext(Dispatchers.IO) {
            val success = configRepository.decompressAndRestore(uri)
            if (success) {
                updateSettingsState()
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.restore_success)))
                Log.d(TAG, "Restore successful.")
                configRepository.loadConfigs()
            } else {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.restore_failed)))
            }
        }
    }

    suspend fun createConfigFile(): String? {
        val filePath = configRepository.createConfigFile(application.assets)
        if (filePath == null) {
            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.create_config_failed)))
        } else {
            configRepository.loadConfigs()
        }
        return filePath
    }

    /**
     * Updates core statistics from Xray-core via gRPC.
     * Delegates to XrayStatsManager for immediate stats update.
     * Note: For automatic monitoring, use startMonitoring()/stopMonitoring() instead.
     */
    suspend fun updateCoreStats() {
        xrayStatsManager.updateCoreStats()
    }

    /**
     * Starts automatic stats monitoring.
     * Should be called when connection is established.
     */
    fun startMonitoring() {
        xrayStatsManager.startMonitoring()
        androidMemoryStatsManager.startMonitoring()
    }

    /**
     * Stops automatic stats monitoring.
     * Should be called when connection is lost or service is disabled.
     */
    fun stopMonitoring() {
        xrayStatsManager.stopMonitoring()
        androidMemoryStatsManager.stopMonitoring()
    }
    
    // Android memory stats state - delegated to AndroidMemoryStatsManager
    val androidMemoryStats: StateFlow<com.hyperxray.an.core.monitor.AndroidMemoryStats> = 
        androidMemoryStatsManager.memoryStats

    /**
     * Updates service enabled state in stats manager.
     */
    private fun updateStatsManagerServiceState(enabled: Boolean) {
        xrayStatsManager.setServiceEnabled(enabled)
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
        val filePath = configRepository.importConfigFromClipboard()
        if (filePath == null) {
            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.import_failed)))
        } else {
            configRepository.loadConfigs()
        }
        return filePath
    }

    suspend fun handleSharedContent(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!configRepository.importConfigFromContent(content).isNullOrEmpty()) {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.import_success)))
                configRepository.loadConfigs()
            } else {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.invalid_config_format)))
            }
        }
    }

    suspend fun deleteConfigFile(file: File, callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_isServiceEnabled.value && selectedConfigFile.value != null &&
                selectedConfigFile.value == file
            ) {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.config_in_use)))
                Log.w(TAG, "Attempted to delete selected config file: ${file.name}")
                return@launch
            }

            val success = configRepository.deleteConfigFile(file)
            if (success) {
                withContext(Dispatchers.Main) {
                    configRepository.loadConfigs()
                }
            } else {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.delete_fail)))
            }
            callback()
        }
    }

    fun extractAssetsIfNeeded() {
        configRepository.extractAssetsIfNeeded()
    }

    fun updateSocksPort(portString: String): Boolean {
        return settingsRepository.updateSocksPort(portString)
    }

    fun updateDnsIpv4(ipv4Addr: String): Boolean {
        return settingsRepository.updateDnsIpv4(ipv4Addr)
    }

    fun updateDnsIpv6(ipv6Addr: String): Boolean {
        return settingsRepository.updateDnsIpv6(ipv6Addr)
    }

    fun setIpv6Enabled(enabled: Boolean) {
        settingsRepository.setIpv6Enabled(enabled)
    }

    fun setUseTemplateEnabled(enabled: Boolean) {
        settingsRepository.setUseTemplateEnabled(enabled)
    }

    fun setHttpProxyEnabled(enabled: Boolean) {
        settingsRepository.setHttpProxyEnabled(enabled)
    }

    fun setBypassLanEnabled(enabled: Boolean) {
        settingsRepository.setBypassLanEnabled(enabled)
    }

    fun setBypassSystemDns(enabled: Boolean) {
        settingsRepository.setBypassSystemDns(enabled)
    }

    fun setBypassLocalDnsOnly(enabled: Boolean) {
        settingsRepository.setBypassLocalDnsOnly(enabled)
    }

    fun setAllowAppBypass(enabled: Boolean) {
        settingsRepository.setAllowAppBypass(enabled)
    }

    fun setDisableVpnEnabled(enabled: Boolean) {
        settingsRepository.setDisableVpnEnabled(enabled)
    }

    fun setTheme(mode: ThemeMode) {
        settingsRepository.setTheme(mode)
        // Theme update is handled by SettingsRepository, no additional action needed
        reloadView?.invoke()
    }

    fun setAutoStart(enabled: Boolean) {
        settingsRepository.setAutoStart(enabled)
    }

    fun setTunnelMode(mode: com.hyperxray.an.common.TunnelMode) {
        settingsRepository.setTunnelMode(mode)
        // Note: Tunnel mode change requires VPN restart to take effect
        Log.d(TAG, "Tunnel mode changed to: ${mode.name}")
    }

    fun setConnectionStateDisconnecting() {
        // Connection state is now managed by VpnConnectionUseCase
        // This method is kept for backward compatibility but does nothing
        // The use case will handle disconnection state transitions
        vpnConnectionUseCase.disconnect()
    }

    fun navigate(route: String) {
        viewModelScope.launch {
            _uiEvent.trySend(MainViewUiEvent.Navigate(route))
        }
    }

    fun setXrayCoreInstanceCount(count: Int) {
        settingsRepository.setXrayCoreInstanceCount(count)
    }

    // Performance Settings Functions - delegated to SettingsRepository
    fun setAggressiveSpeedOptimizations(enabled: Boolean) {
        settingsRepository.setAggressiveSpeedOptimizations(enabled)
    }

    fun updateConnIdleTimeout(value: String) {
        settingsRepository.updateConnIdleTimeout(value)
    }

    fun updateHandshakeTimeout(value: String) {
        settingsRepository.updateHandshakeTimeout(value)
    }

    fun updateUplinkOnly(value: String) {
        settingsRepository.updateUplinkOnly(value)
    }

    fun updateDownlinkOnly(value: String) {
        settingsRepository.updateDownlinkOnly(value)
    }

    fun updateDnsCacheSize(value: String) {
        settingsRepository.updateDnsCacheSize(value)
    }

    fun setDisableFakeDns(enabled: Boolean) {
        settingsRepository.setDisableFakeDns(enabled)
    }

    fun setOptimizeRoutingRules(enabled: Boolean) {
        settingsRepository.setOptimizeRoutingRules(enabled)
    }

    fun setTcpFastOpen(enabled: Boolean) {
        settingsRepository.setTcpFastOpen(enabled)
    }

    fun setHttp2Optimization(enabled: Boolean) {
        settingsRepository.setHttp2Optimization(enabled)
    }

    // Extreme RAM/CPU Optimization Functions - delegated to SettingsRepository
    fun setExtremeRamCpuOptimizations(enabled: Boolean) {
        settingsRepository.setExtremeRamCpuOptimizations(enabled)
    }

    fun updateExtremeConnIdleTimeout(value: String) {
        settingsRepository.updateExtremeConnIdleTimeout(value)
    }

    fun updateExtremeHandshakeTimeout(value: String) {
        settingsRepository.updateExtremeHandshakeTimeout(value)
    }

    fun updateExtremeUplinkOnly(value: String) {
        settingsRepository.updateExtremeUplinkOnly(value)
    }

    fun updateExtremeDownlinkOnly(value: String) {
        settingsRepository.updateExtremeDownlinkOnly(value)
    }

    fun updateExtremeDnsCacheSize(value: String) {
        settingsRepository.updateExtremeDnsCacheSize(value)
    }

    fun setExtremeDisableFakeDns(enabled: Boolean) {
        settingsRepository.setExtremeDisableFakeDns(enabled)
    }

    fun setExtremeRoutingOptimization(enabled: Boolean) {
        settingsRepository.setExtremeRoutingOptimization(enabled)
    }

    fun updateMaxConcurrentConnections(value: String) {
        settingsRepository.updateMaxConcurrentConnections(value)
    }

    fun setParallelDnsQueries(enabled: Boolean) {
        settingsRepository.setParallelDnsQueries(enabled)
    }

    fun setExtremeProxyOptimization(enabled: Boolean) {
        settingsRepository.setExtremeProxyOptimization(enabled)
    }

    fun setBypassDomains(domains: List<String>) {
        settingsRepository.setBypassDomains(domains)
    }

    fun setBypassIps(ips: List<String>) {
        settingsRepository.setBypassIps(ips)
    }

    fun importRuleFile(uri: Uri, fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = configRepository.importRuleFile(uri, fileName)
            if (success) {
                when (fileName) {
                    "geoip.dat" -> {
                        settingsRepository.updateRuleFileCustomStatus(
                            isGeoipCustom = prefs.customGeoipImported,
                            isGeositeCustom = prefs.customGeositeImported
                        )
                        settingsRepository.updateRuleFileInfo(
                            geoipSummary = configRepository.getRuleFileSummary("geoip.dat"),
                            geositeSummary = configRepository.getRuleFileSummary("geosite.dat")
                        )
                    }

                    "geosite.dat" -> {
                        settingsRepository.updateRuleFileCustomStatus(
                            isGeoipCustom = prefs.customGeoipImported,
                            isGeositeCustom = prefs.customGeositeImported
                        )
                        settingsRepository.updateRuleFileInfo(
                            geoipSummary = configRepository.getRuleFileSummary("geoip.dat"),
                            geositeSummary = configRepository.getRuleFileSummary("geosite.dat")
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

    fun startVpnService(action: String) {
        viewModelScope.launch {
            if (selectedConfigFile.value == null) {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.not_select_config)))
                Log.w(TAG, "Cannot start service: no config file selected.")
                setControlMenuClickable(true)
                return@launch
            }
            val intent = Intent(application, HyperVpnService::class.java).setAction(action)
            _uiEvent.trySend(MainViewUiEvent.StartService(intent))
        }
    }
    
    // Backward compatibility alias
    fun startTProxyService(action: String) = startVpnService(action)

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

    fun stopVpnService() {
        viewModelScope.launch {
            // Start disconnection process to show proper state transitions
            vpnConnectionUseCase.disconnect()
            
            val intent = Intent(
                application,
                HyperVpnService::class.java
            ).setAction(HyperVpnService.ACTION_DISCONNECT)
            _uiEvent.trySend(MainViewUiEvent.StartService(intent))
        }
    }
    
    // Backward compatibility alias
    fun stopTProxyService() = stopVpnService()

    fun prepareAndStartVpn(vpnPrepareLauncher: ActivityResultLauncher<Intent>) {
        viewModelScope.launch {
            if (selectedConfigFile.value == null) {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.not_select_config)))
                Log.w(TAG, "Cannot prepare VPN: no config file selected.")
                setControlMenuClickable(true)
                return@launch
            }
            val vpnIntent = VpnService.prepare(application)
            if (vpnIntent != null) {
                Log.d(TAG, "VPN permission needed, launching system dialog...")
                vpnPrepareLauncher.launch(vpnIntent)
            } else {
                Log.d(TAG, "VPN permission already granted, starting service...")
                startVpnService(HyperVpnService.ACTION_CONNECT)
            }
        }
    }

    /**
     * Starts the connection process.
     * Delegates to VpnConnectionUseCase which handles all connection stages.
     */
    fun startConnectionProcess() {
        vpnConnectionUseCase.connect()
    }

    /**
     * Stops the connection process.
     * Delegates to VpnConnectionUseCase which handles all disconnection stages.
     */
    fun stopConnectionProcess() {
        vpnConnectionUseCase.disconnect()
    }

    fun checkAndStartAutoVpn(vpnPrepareLauncher: ActivityResultLauncher<Intent>) {
        viewModelScope.launch {
            if (prefs.autoStart && !_isServiceEnabled.value && selectedConfigFile.value != null) {
                Log.d(TAG, "Auto start enabled, starting VPN")
                prepareAndStartVpn(vpnPrepareLauncher)
            }
        }
    }

    fun navigateToAppList() {
        viewModelScope.launch {
            appListViewModel = AppListViewModel(application)
            _uiEvent.trySend(MainViewUiEvent.Navigate(ROUTE_APP_LIST))
        }
    }

    fun moveConfigFile(fromIndex: Int, toIndex: Int) {
        configRepository.moveConfigFile(fromIndex, toIndex)
    }

    fun refreshConfigFileList() {
        viewModelScope.launch(Dispatchers.IO) {
            configRepository.loadConfigs()
        }
    }

    fun updateSelectedConfigFile(file: File?) {
        configRepository.updateSelectedConfigFile(file)
    }

    fun updateConnectivityTestTarget(target: String) {
        settingsRepository.updateConnectivityTestTarget(target)
    }

    fun updateConnectivityTestTimeout(timeout: String) {
        settingsRepository.updateConnectivityTestTimeout(timeout)
    }

    fun testConnectivity() {
        viewModelScope.launch(Dispatchers.IO) {
            val config = ConnectivityTestConfig(
                targetUrl = prefs.connectivityTestTarget,
                proxyAddress = prefs.socksAddress,
                proxyPort = prefs.socksPort,
                timeoutMs = prefs.connectivityTestTimeout
            )
            
            val result = connectivityTester.testConnectivity(config)
            
            when (result) {
                is ConnectivityTestResult.Success -> {
                    _uiEvent.trySend(
                        MainViewUiEvent.ShowSnackbar(
                            application.getString(
                                R.string.connectivity_test_latency,
                                result.latencyMs.toInt()
                            )
                        )
                    )
                }
                is ConnectivityTestResult.Failure -> {
                    val message = if (result.errorMessage.contains("Invalid URL", ignoreCase = true)) {
                        application.getString(R.string.connectivity_test_invalid_url)
                    } else {
                        application.getString(R.string.connectivity_test_failed)
                    }
                    _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(message))
                }
            }
        }
    }


    fun restoreDefaultGeoip(callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = configRepository.restoreDefaultGeoip()
            if (success) {
                settingsRepository.updateRuleFileCustomStatus(
                    isGeoipCustom = prefs.customGeoipImported,
                    isGeositeCustom = prefs.customGeositeImported
                )
                settingsRepository.updateRuleFileInfo(
                    geoipSummary = configRepository.getRuleFileSummary("geoip.dat"),
                    geositeSummary = configRepository.getRuleFileSummary("geosite.dat")
                )
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.rule_file_restore_geoip_success)))
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Restored default geoip.dat.")
                    callback()
                }
            } else {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.restore_failed)))
                callback()
            }
        }
    }

    fun restoreDefaultGeosite(callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = configRepository.restoreDefaultGeosite()
            if (success) {
                settingsRepository.updateRuleFileCustomStatus(
                    isGeoipCustom = prefs.customGeoipImported,
                    isGeositeCustom = prefs.customGeositeImported
                )
                settingsRepository.updateRuleFileInfo(
                    geoipSummary = configRepository.getRuleFileSummary("geoip.dat"),
                    geositeSummary = configRepository.getRuleFileSummary("geosite.dat")
                )
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.rule_file_restore_geosite_success)))
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Restored default geosite.dat.")
                    callback()
                }
            } else {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.restore_failed)))
                callback()
            }
        }
    }

    fun cancelDownload(fileName: String) {
        configRepository.cancelDownload(fileName)
        Log.d(TAG, "Download cancellation requested for $fileName")
    }

    fun downloadRuleFile(url: String, fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = configRepository.downloadRuleFile(
                url = url,
                fileName = fileName,
                isServiceEnabled = _isServiceEnabled.value,
                socksPort = prefs.socksPort
            )
            
            result.fold(
                onSuccess = {
                    // Update settings after successful download
                    when (fileName) {
                        "geoip.dat" -> {
                            settingsRepository.updateRuleFileCustomStatus(
                                isGeoipCustom = prefs.customGeoipImported,
                                isGeositeCustom = prefs.customGeositeImported
                            )
                            settingsRepository.updateRuleFileInfo(
                                geoipSummary = configRepository.getRuleFileSummary("geoip.dat"),
                                geositeSummary = configRepository.getRuleFileSummary("geosite.dat")
                            )
                        }
                        "geosite.dat" -> {
                            settingsRepository.updateRuleFileCustomStatus(
                                isGeoipCustom = prefs.customGeoipImported,
                                isGeositeCustom = prefs.customGeositeImported
                            )
                            settingsRepository.updateRuleFileInfo(
                                geoipSummary = configRepository.getRuleFileSummary("geoip.dat"),
                                geositeSummary = configRepository.getRuleFileSummary("geosite.dat")
                            )
                        }
                    }
                    updateSettingsState()
                    _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.download_success)))
                },
                onFailure = { exception ->
                    when (exception) {
                        is CancellationException -> {
                            Log.d(TAG, "Download cancelled for $fileName")
                            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.download_cancelled)))
                        }
                        is IllegalStateException -> {
                            // Download already in progress
                            Log.w(TAG, exception.message ?: "Download already in progress")
                        }
                        else -> {
                            Log.e(TAG, "Failed to download rule file", exception)
                            _uiEvent.trySend(
                                MainViewUiEvent.ShowSnackbar(
                                    application.getString(R.string.download_failed) + ": " + (exception.message ?: "Unknown error")
                                )
                            )
                        }
                    }
                    updateSettingsState()
                }
            )
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            _isCheckingForUpdates.value = true
            val result = appUpdateRepository.checkForUpdates(_isServiceEnabled.value)
            
            result.fold(
                onSuccess = { updateInfo ->
                    if (updateInfo.isUpdateAvailable) {
                        _newVersionAvailable.value = updateInfo.latestVersion
                    } else {
                        _uiEvent.trySend(
                            MainViewUiEvent.ShowSnackbar(
                                application.getString(R.string.no_new_version_available)
                            )
                        )
                    }
                },
                onFailure = { exception ->
                    Log.e(TAG, "Failed to check for updates", exception)
                    _uiEvent.trySend(
                        MainViewUiEvent.ShowSnackbar(
                            application.getString(R.string.failed_to_check_for_updates) + ": " + (exception.message ?: "Unknown error")
                        )
                    )
                }
            )
            _isCheckingForUpdates.value = false
        }
    }

    fun downloadNewVersion(versionTag: String) {
        appUpdateRepository.downloadNewVersion(versionTag)
        _newVersionAvailable.value = null
    }

    fun clearNewVersionAvailable() {
        _newVersionAvailable.value = null
    }

    // Track last modified time to detect file changes
    private var lastAccountFileModified: Long = 0L
    private var accountFileWatcherJob: Job? = null
    
    /**
     * Load WARP account information from file
     * Public so it can be called when account is created/updated
     */
    fun loadWarpAccountInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val filesDir = getApplication<Application>().filesDir
                val accountFile = File(filesDir, "warp-account.json")
                
                if (!accountFile.exists() || !accountFile.canRead()) {
                    _warpAccountInfo.value = com.hyperxray.an.feature.dashboard.WarpAccountInfo()
                    lastAccountFileModified = 0L
                    return@launch
                }
                
                val currentModified = accountFile.lastModified()
                // Skip if file hasn't changed
                if (currentModified == lastAccountFileModified && _warpAccountInfo.value.accountExists) {
                    return@launch
                }
                
                lastAccountFileModified = currentModified
                
                val accountContent = accountFile.readText()
                val accountJson = JSONObject(accountContent)
                
                val publicKey = accountJson.optString("publicKey", null).takeIf { it.isNotBlank() }
                val endpoint = accountJson.optString("endpoint", null).takeIf { it.isNotBlank() }
                val accountType = accountJson.optString("accountType", null).takeIf { it.isNotBlank() }
                val license = accountJson.optString("license", null).takeIf { it.isNotBlank() }
                val warpEnabled = accountJson.optBoolean("warpEnabled", false)
                
                _warpAccountInfo.value = com.hyperxray.an.feature.dashboard.WarpAccountInfo(
                    accountExists = true,
                    publicKey = publicKey,
                    endpoint = endpoint,
                    accountType = accountType,
                    license = license,
                    warpEnabled = warpEnabled
                )
                
                Log.d(TAG, "✅ WARP account info loaded and updated in dashboard")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading WARP account info: ${e.message}", e)
                _warpAccountInfo.value = com.hyperxray.an.feature.dashboard.WarpAccountInfo()
            }
        }
    }
    
    /**
     * Start watching WARP account file for changes
     * Automatically updates dashboard when account file is modified
     */
    private fun startWarpAccountFileWatcher() {
        accountFileWatcherJob?.cancel()
        accountFileWatcherJob = viewModelScope.launch {
            while (isActive) {
                try {
                    loadWarpAccountInfo()
                    // Check every 2 seconds for file changes
                    delay(2000)
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in WARP account file watcher: ${e.message}", e)
                        delay(2000) // Continue watching even on error
                    }
                }
            }
        }
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
 * Creates and injects dependencies to enable testability.
 */
class MainViewModelFactory(
    private val application: Application
) : ViewModelProvider.AndroidViewModelFactory(application) {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            // Create dependencies
            val prefs = Preferences(application)
            val configRepository = ConfigRepository(application, prefs)
            val settingsRepository = SettingsRepository(application, prefs)
            val serviceEventObserver = ServiceEventObserver()
            val appUpdateRepository = AppUpdateRepository(application, prefs)
            val connectivityTester = ConnectivityTester()
            val telemetryStore = TelemetryStore()
            
            // Note: XrayStatsManager is created in ViewModel's init block
            // because it needs viewModelScope which is only available in the ViewModel
            
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(
                application = application,
                configRepository = configRepository,
                settingsRepository = settingsRepository,
                serviceEventObserver = serviceEventObserver,
                appUpdateRepository = appUpdateRepository,
                connectivityTester = connectivityTester,
                telemetryStore = telemetryStore
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

