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
import com.hyperxray.an.common.CoreStatsClient
import com.hyperxray.an.common.ROUTE_APP_LIST
import com.hyperxray.an.common.formatBytes
import com.hyperxray.an.common.ROUTE_CONFIG_EDIT
import com.hyperxray.an.common.ThemeMode
import com.hyperxray.an.data.source.FileManager
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.service.TProxyService
import com.hyperxray.an.telemetry.TelemetryStore
import com.hyperxray.an.telemetry.AggregatedTelemetry
import com.hyperxray.an.telemetry.TProxyMetricsCollector
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
import kotlinx.coroutines.withContext
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
import java.time.Instant

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
    
    // Throughput calculation tracking
    private var lastTrafficStats: Pair<Long, Long>? = null // (uplink, downlink)
    private var lastTrafficTime: Instant? = null

    private val fileManager: FileManager = FileManager(application, prefs)
    
    private val telemetryStore: TelemetryStore = TelemetryStore()
    private var metricsCollector: TProxyMetricsCollector? = null

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
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Service stopped")
            setServiceEnabled(false)
            setControlMenuClickable(true)
            _coreStatsState.value = CoreStatsState()
            coreStatsClient?.close()
            coreStatsClient = null
            // Reset throughput tracking
            lastTrafficStats = null
            lastTrafficTime = null
        }
    }

    init {
        Log.d(TAG, "MainViewModel initialized.")
        viewModelScope.launch(Dispatchers.IO) {
            _isServiceEnabled.value = isServiceRunning(application, TProxyService::class.java)

            updateSettingsState()
            loadKernelVersion()
            refreshConfigFileList()
        }
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
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val firstLine = reader.readLine()
            process.destroy()
            
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
     * Calculate throughput (bytes per second) from traffic delta.
     * Returns Pair<uplinkThroughput, downlinkThroughput> in bytes per second.
     */
    private fun calculateThroughput(
        currentUplink: Long,
        currentDownlink: Long
    ): Pair<Double, Double> {
        val now = Instant.now()
        
        return if (lastTrafficStats != null && lastTrafficTime != null) {
            val timeDelta = java.time.Duration.between(lastTrafficTime, now).toMillis()
            
            if (timeDelta > 0) {
                val uplinkDelta = currentUplink - lastTrafficStats!!.first
                val downlinkDelta = currentDownlink - lastTrafficStats!!.second
                
                // Calculate bytes per second
                val uplinkThroughput = (uplinkDelta.toDouble() / timeDelta) * 1000.0
                val downlinkThroughput = (downlinkDelta.toDouble() / timeDelta) * 1000.0
                
                Log.d(TAG, "Throughput calc: timeDelta=${timeDelta}ms, uplinkDelta=$uplinkDelta, downlinkDelta=$downlinkDelta, " +
                        "uplinkThroughput=${uplinkThroughput}B/s, downlinkThroughput=${downlinkThroughput}B/s")
                
                // Update last stats
                lastTrafficStats = Pair(currentUplink, currentDownlink)
                lastTrafficTime = now
                
                Pair(uplinkThroughput, downlinkThroughput)
            } else {
                // Time delta too small, return previous throughput
                Log.d(TAG, "Throughput calc: timeDelta too small ($timeDelta ms), returning 0")
                Pair(0.0, 0.0)
            }
        } else {
            // First measurement - initialize baseline
            Log.d(TAG, "Throughput calc: First measurement - initializing baseline: uplink=$currentUplink, downlink=$currentDownlink")
            lastTrafficStats = Pair(currentUplink, currentDownlink)
            lastTrafficTime = now
            Pair(0.0, 0.0)
        }
    }

    suspend fun updateCoreStats() {
        if (!_isServiceEnabled.value) return
        if (coreStatsClient == null)
            coreStatsClient = CoreStatsClient.create("127.0.0.1", prefs.apiPort)

        val stats = coreStatsClient?.getSystemStats()
        val traffic = coreStatsClient?.getTraffic()

        if (stats == null && traffic == null) {
            Log.w(TAG, "Both stats and traffic are null, closing client")
            coreStatsClient?.close()
            coreStatsClient = null
            return
        }

        // Preserve existing traffic values if new traffic data is null
        val currentState = _coreStatsState.value
        val newUplink = traffic?.uplink ?: currentState.uplink
        val newDownlink = traffic?.downlink ?: currentState.downlink

        // Calculate throughput
        val (uplinkThroughput, downlinkThroughput) = calculateThroughput(newUplink, newDownlink)

        _coreStatsState.value = CoreStatsState(
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
        Log.d(TAG, "Core stats updated - Uplink: ${formatBytes(newUplink)}, Downlink: ${formatBytes(newDownlink)}, " +
                "Uplink Throughput: ${formatBytes(uplinkThroughput.toLong())}/s, " +
                "Downlink Throughput: ${formatBytes(downlinkThroughput.toLong())}/s")
    }

    suspend fun updateTelemetryStats() {
        if (!_isServiceEnabled.value) {
            _telemetryState.value = null
            metricsCollector?.close()
            metricsCollector = null
            return
        }
        
        // Initialize metrics collector if needed
        if (metricsCollector == null) {
            metricsCollector = TProxyMetricsCollector(application, prefs)
        }
        
        // Collect current metrics and add to store
        val metrics = metricsCollector?.collectMetrics(_coreStatsState.value)
        metrics?.let {
            // Only add metrics with valid throughput (> 1 KB/s) to avoid polluting the store with very small values
            // This ensures we only track actual meaningful traffic data
            val throughputKBps = it.throughput / 1024.0
            if (throughputKBps > 1.0) {
                telemetryStore.add(it)
                Log.d(TAG, "Added telemetry metrics: throughput=${it.throughput / (1024 * 1024)}MB/s (${throughputKBps}KB/s)")
            } else {
                Log.d(TAG, "Skipping telemetry metrics with low throughput: ${throughputKBps}KB/s (threshold: 1KB/s)")
            }
        }
        
        // Get aggregated data and update state
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
        viewModelScope.launch {
            if (_selectedConfigFile.value == null) {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.not_select_config)))
                Log.w(TAG, "Cannot start service: no config file selected.")
                setControlMenuClickable(true)
                return@launch
            }
            val intent = Intent(application, TProxyService::class.java).setAction(action)
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
        viewModelScope.launch {
            val intent = Intent(
                application,
                TProxyService::class.java
            ).setAction(TProxyService.ACTION_DISCONNECT)
            _uiEvent.trySend(MainViewUiEvent.StartService(intent))
        }
    }

    fun prepareAndStartVpn(vpnPrepareLauncher: ActivityResultLauncher<Intent>) {
        viewModelScope.launch {
            if (_selectedConfigFile.value == null) {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.not_select_config)))
                Log.w(TAG, "Cannot prepare VPN: no config file selected.")
                setControlMenuClickable(true)
                return@launch
            }
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

    fun registerTProxyServiceReceivers() {
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
        Log.d(TAG, "TProxyService receivers registered.")
    }

    fun unregisterTProxyServiceReceivers() {
        val application = application
        application.unregisterReceiver(startReceiver)
        application.unregisterReceiver(stopReceiver)
        Log.d(TAG, "TProxyService receivers unregistered.")
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

            val client = OkHttpClient.Builder().apply {
                if (_isServiceEnabled.value) {
                    proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", prefs.socksPort)))
                }
            }.build()

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
            val client = OkHttpClient.Builder().apply {
                if (_isServiceEnabled.value) {
                    proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", prefs.socksPort)))
                }
            }.build()

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

