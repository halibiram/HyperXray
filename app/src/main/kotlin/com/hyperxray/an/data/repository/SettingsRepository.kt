package com.hyperxray.an.data.repository

import android.app.Application
import android.util.Log
import com.hyperxray.an.BuildConfig
import com.hyperxray.an.common.ThemeMode
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.viewmodel.ExtremeOptimizationSettings
import com.hyperxray.an.viewmodel.InputFieldState
import com.hyperxray.an.viewmodel.InfoStates
import com.hyperxray.an.viewmodel.PerformanceSettings
import com.hyperxray.an.viewmodel.SettingsState
import com.hyperxray.an.viewmodel.SwitchStates
import com.hyperxray.an.viewmodel.FileStates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.regex.Pattern

/**
 * Repository for managing application settings and preferences.
 * Exposes a Flow<SettingsState> that can be collected by ViewModels.
 */
class SettingsRepository(
    private val application: Application,
    private val prefs: Preferences
) {
    private val _settingsState = MutableStateFlow(createInitialSettingsState())
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    /**
     * Get settings state as Flow for reactive updates.
     */
    fun getSettingsStateFlow(): Flow<SettingsState> = _settingsState.asStateFlow()

    /**
     * Create initial settings state from preferences.
     */
    private fun createInitialSettingsState(): SettingsState {
        return SettingsState(
            socksPort = InputFieldState(prefs.socksPort.toString()),
            dnsIpv4 = InputFieldState(prefs.dnsIpv4),
            dnsIpv6 = InputFieldState(prefs.dnsIpv6),
            switches = SwitchStates(
                ipv6Enabled = prefs.ipv6,
                useTemplateEnabled = prefs.useTemplate,
                httpProxyEnabled = prefs.httpProxyEnabled,
                bypassLanEnabled = prefs.bypassLan,
                disableVpn = prefs.disableVpn,
                themeMode = prefs.theme,
                autoStart = prefs.autoStart
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
    }

    /**
     * Update SOCKS port setting.
     */
    fun updateSocksPort(portString: String): Boolean {
        return try {
            val port = portString.toInt()
            if (port in 1025..65535) {
                prefs.socksPort = port
                _settingsState.update { it.copy(socksPort = InputFieldState(portString)) }
                true
            } else {
                _settingsState.update {
                    it.copy(
                        socksPort = InputFieldState(
                            value = portString,
                            error = application.getString(com.hyperxray.an.R.string.invalid_port_range),
                            isValid = false
                        )
                    )
                }
                false
            }
        } catch (e: NumberFormatException) {
            _settingsState.update {
                it.copy(
                    socksPort = InputFieldState(
                        value = portString,
                        error = application.getString(com.hyperxray.an.R.string.invalid_port),
                        isValid = false
                    )
                )
            }
            false
        }
    }

    /**
     * Update DNS IPv4 setting.
     */
    fun updateDnsIpv4(ipv4Addr: String): Boolean {
        val matcher = IPV4_PATTERN.matcher(ipv4Addr)
        return if (matcher.matches()) {
            prefs.dnsIpv4 = ipv4Addr
            _settingsState.update { it.copy(dnsIpv4 = InputFieldState(ipv4Addr)) }
            true
        } else {
            _settingsState.update {
                it.copy(
                    dnsIpv4 = InputFieldState(
                        value = ipv4Addr,
                        error = application.getString(com.hyperxray.an.R.string.invalid_ipv4),
                        isValid = false
                    )
                )
            }
            false
        }
    }

    /**
     * Update DNS IPv6 setting.
     */
    fun updateDnsIpv6(ipv6Addr: String): Boolean {
        val matcher = IPV6_PATTERN.matcher(ipv6Addr)
        return if (matcher.matches()) {
            prefs.dnsIpv6 = ipv6Addr
            _settingsState.update { it.copy(dnsIpv6 = InputFieldState(ipv6Addr)) }
            true
        } else {
            _settingsState.update {
                it.copy(
                    dnsIpv6 = InputFieldState(
                        value = ipv6Addr,
                        error = application.getString(com.hyperxray.an.R.string.invalid_ipv6),
                        isValid = false
                    )
                )
            }
            false
        }
    }

    /**
     * Set IPv6 enabled.
     */
    fun setIpv6Enabled(enabled: Boolean) {
        prefs.ipv6 = enabled
        _settingsState.update {
            it.copy(switches = it.switches.copy(ipv6Enabled = enabled))
        }
    }

    /**
     * Set use template enabled.
     */
    fun setUseTemplateEnabled(enabled: Boolean) {
        prefs.useTemplate = enabled
        _settingsState.update {
            it.copy(switches = it.switches.copy(useTemplateEnabled = enabled))
        }
    }

    /**
     * Set HTTP proxy enabled.
     */
    fun setHttpProxyEnabled(enabled: Boolean) {
        prefs.httpProxyEnabled = enabled
        _settingsState.update {
            it.copy(switches = it.switches.copy(httpProxyEnabled = enabled))
        }
    }

    /**
     * Set bypass LAN enabled.
     */
    fun setBypassLanEnabled(enabled: Boolean) {
        prefs.bypassLan = enabled
        _settingsState.update {
            it.copy(switches = it.switches.copy(bypassLanEnabled = enabled))
        }
    }

    /**
     * Set disable VPN enabled.
     */
    fun setDisableVpnEnabled(enabled: Boolean) {
        prefs.disableVpn = enabled
        _settingsState.update {
            it.copy(switches = it.switches.copy(disableVpn = enabled))
        }
    }

    /**
     * Set theme mode.
     */
    fun setTheme(mode: ThemeMode) {
        prefs.theme = mode
        _settingsState.update {
            it.copy(switches = it.switches.copy(themeMode = mode))
        }
    }

    /**
     * Set auto start enabled.
     */
    fun setAutoStart(enabled: Boolean) {
        prefs.autoStart = enabled
        _settingsState.update {
            it.copy(switches = it.switches.copy(autoStart = enabled))
        }
    }

    /**
     * Update connectivity test target.
     */
    fun updateConnectivityTestTarget(target: String) {
        val isValid = try {
            val url = java.net.URL(target)
            url.protocol == "http" || url.protocol == "https"
        } catch (e: Exception) {
            false
        }
        if (isValid) {
            prefs.connectivityTestTarget = target
            _settingsState.update {
                it.copy(connectivityTestTarget = InputFieldState(target))
            }
        } else {
            _settingsState.update {
                it.copy(
                    connectivityTestTarget = InputFieldState(
                        value = target,
                        error = application.getString(com.hyperxray.an.R.string.connectivity_test_invalid_url),
                        isValid = false
                    )
                )
            }
        }
    }

    /**
     * Update connectivity test timeout.
     */
    fun updateConnectivityTestTimeout(timeout: String) {
        val timeoutInt = timeout.toIntOrNull()
        if (timeoutInt != null && timeoutInt > 0) {
            prefs.connectivityTestTimeout = timeoutInt
            _settingsState.update {
                it.copy(connectivityTestTimeout = InputFieldState(timeout))
            }
        } else {
            _settingsState.update {
                it.copy(
                    connectivityTestTimeout = InputFieldState(
                        value = timeout,
                        error = application.getString(com.hyperxray.an.R.string.invalid_timeout),
                        isValid = false
                    )
                )
            }
        }
    }

    // Performance Settings Functions

    /**
     * Set aggressive speed optimizations.
     */
    fun setAggressiveSpeedOptimizations(enabled: Boolean) {
        prefs.aggressiveSpeedOptimizations = enabled
        _settingsState.update {
            it.copy(
                performance = it.performance.copy(
                    aggressiveSpeedOptimizations = enabled
                )
            )
        }
    }

    /**
     * Update connection idle timeout.
     */
    fun updateConnIdleTimeout(value: String) {
        val timeout = value.toIntOrNull()
        if (timeout != null && timeout > 0) {
            prefs.connIdleTimeout = timeout
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        connIdleTimeout = InputFieldState(value)
                    )
                )
            }
        } else {
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        connIdleTimeout = InputFieldState(
                            value = value,
                            error = application.getString(com.hyperxray.an.R.string.invalid_timeout),
                            isValid = false
                        )
                    )
                )
            }
        }
    }

    /**
     * Update handshake timeout.
     */
    fun updateHandshakeTimeout(value: String) {
        val timeout = value.toIntOrNull()
        if (timeout != null && timeout > 0) {
            prefs.handshakeTimeout = timeout
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        handshakeTimeout = InputFieldState(value)
                    )
                )
            }
        } else {
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        handshakeTimeout = InputFieldState(
                            value = value,
                            error = application.getString(com.hyperxray.an.R.string.invalid_timeout),
                            isValid = false
                        )
                    )
                )
            }
        }
    }

    /**
     * Update uplink only setting.
     */
    fun updateUplinkOnly(value: String) {
        val uplink = value.toIntOrNull()
        if (uplink != null && uplink >= 0) {
            prefs.uplinkOnly = uplink
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        uplinkOnly = InputFieldState(value)
                    )
                )
            }
        } else {
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        uplinkOnly = InputFieldState(
                            value = value,
                            error = "Invalid value",
                            isValid = false
                        )
                    )
                )
            }
        }
    }

    /**
     * Update downlink only setting.
     */
    fun updateDownlinkOnly(value: String) {
        val downlink = value.toIntOrNull()
        if (downlink != null && downlink >= 0) {
            prefs.downlinkOnly = downlink
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        downlinkOnly = InputFieldState(value)
                    )
                )
            }
        } else {
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        downlinkOnly = InputFieldState(
                            value = value,
                            error = "Invalid value",
                            isValid = false
                        )
                    )
                )
            }
        }
    }

    /**
     * Update DNS cache size.
     */
    fun updateDnsCacheSize(value: String) {
        val cacheSize = value.toIntOrNull()
        if (cacheSize != null && cacheSize > 0) {
            prefs.dnsCacheSize = cacheSize
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        dnsCacheSize = InputFieldState(value)
                    )
                )
            }
        } else {
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        dnsCacheSize = InputFieldState(
                            value = value,
                            error = "Invalid cache size",
                            isValid = false
                        )
                    )
                )
            }
        }
    }

    /**
     * Set disable fake DNS.
     */
    fun setDisableFakeDns(enabled: Boolean) {
        prefs.disableFakeDns = enabled
        _settingsState.update {
            it.copy(
                performance = it.performance.copy(disableFakeDns = enabled)
            )
        }
    }

    /**
     * Set optimize routing rules.
     */
    fun setOptimizeRoutingRules(enabled: Boolean) {
        prefs.optimizeRoutingRules = enabled
        _settingsState.update {
            it.copy(
                performance = it.performance.copy(optimizeRoutingRules = enabled)
            )
        }
    }

    /**
     * Set TCP fast open.
     */
    fun setTcpFastOpen(enabled: Boolean) {
        prefs.tcpFastOpen = enabled
        _settingsState.update {
            it.copy(
                performance = it.performance.copy(tcpFastOpen = enabled)
            )
        }
    }

    /**
     * Set HTTP/2 optimization.
     */
    fun setHttp2Optimization(enabled: Boolean) {
        prefs.http2Optimization = enabled
        _settingsState.update {
            it.copy(
                performance = it.performance.copy(http2Optimization = enabled)
            )
        }
    }

    // Extreme Optimization Settings

    /**
     * Set extreme RAM/CPU optimizations.
     */
    fun setExtremeRamCpuOptimizations(enabled: Boolean) {
        prefs.extremeRamCpuOptimizations = enabled
        _settingsState.update {
            it.copy(
                performance = it.performance.copy(
                    extreme = it.performance.extreme.copy(
                        extremeRamCpuOptimizations = enabled
                    )
                )
            )
        }
    }

    /**
     * Update extreme connection idle timeout.
     */
    fun updateExtremeConnIdleTimeout(value: String) {
        val timeout = value.toIntOrNull()
        if (timeout != null && timeout > 0) {
            prefs.extremeConnIdleTimeout = timeout
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        extreme = it.performance.extreme.copy(
                            extremeConnIdleTimeout = InputFieldState(value)
                        )
                    )
                )
            }
        } else {
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        extreme = it.performance.extreme.copy(
                            extremeConnIdleTimeout = InputFieldState(
                                value = value,
                                error = application.getString(com.hyperxray.an.R.string.invalid_timeout),
                                isValid = false
                            )
                        )
                    )
                )
            }
        }
    }

    /**
     * Update extreme handshake timeout.
     */
    fun updateExtremeHandshakeTimeout(value: String) {
        val timeout = value.toIntOrNull()
        if (timeout != null && timeout > 0) {
            prefs.extremeHandshakeTimeout = timeout
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        extreme = it.performance.extreme.copy(
                            extremeHandshakeTimeout = InputFieldState(value)
                        )
                    )
                )
            }
        } else {
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        extreme = it.performance.extreme.copy(
                            extremeHandshakeTimeout = InputFieldState(
                                value = value,
                                error = application.getString(com.hyperxray.an.R.string.invalid_timeout),
                                isValid = false
                            )
                        )
                    )
                )
            }
        }
    }

    /**
     * Update extreme uplink only.
     */
    fun updateExtremeUplinkOnly(value: String) {
        val uplink = value.toIntOrNull()
        if (uplink != null && uplink >= 0) {
            prefs.extremeUplinkOnly = uplink
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        extreme = it.performance.extreme.copy(
                            extremeUplinkOnly = InputFieldState(value)
                        )
                    )
                )
            }
        } else {
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        extreme = it.performance.extreme.copy(
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
    }

    /**
     * Update extreme downlink only.
     */
    fun updateExtremeDownlinkOnly(value: String) {
        val downlink = value.toIntOrNull()
        if (downlink != null && downlink >= 0) {
            prefs.extremeDownlinkOnly = downlink
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        extreme = it.performance.extreme.copy(
                            extremeDownlinkOnly = InputFieldState(value)
                        )
                    )
                )
            }
        } else {
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        extreme = it.performance.extreme.copy(
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
    }

    /**
     * Update extreme DNS cache size.
     */
    fun updateExtremeDnsCacheSize(value: String) {
        val cacheSize = value.toIntOrNull()
        if (cacheSize != null && cacheSize > 0) {
            prefs.extremeDnsCacheSize = cacheSize
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        extreme = it.performance.extreme.copy(
                            extremeDnsCacheSize = InputFieldState(value)
                        )
                    )
                )
            }
        } else {
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        extreme = it.performance.extreme.copy(
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
    }

    /**
     * Set extreme disable fake DNS.
     */
    fun setExtremeDisableFakeDns(enabled: Boolean) {
        prefs.extremeDisableFakeDns = enabled
        _settingsState.update {
            it.copy(
                performance = it.performance.copy(
                    extreme = it.performance.extreme.copy(
                        extremeDisableFakeDns = enabled
                    )
                )
            )
        }
    }

    /**
     * Set extreme routing optimization.
     */
    fun setExtremeRoutingOptimization(enabled: Boolean) {
        prefs.extremeRoutingOptimization = enabled
        _settingsState.update {
            it.copy(
                performance = it.performance.copy(
                    extreme = it.performance.extreme.copy(
                        extremeRoutingOptimization = enabled
                    )
                )
            )
        }
    }

    /**
     * Update max concurrent connections.
     */
    fun updateMaxConcurrentConnections(value: String) {
        val maxConn = value.toIntOrNull()
        if (maxConn != null && maxConn >= 0) {
            prefs.maxConcurrentConnections = maxConn
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        extreme = it.performance.extreme.copy(
                            maxConcurrentConnections = InputFieldState(value)
                        )
                    )
                )
            }
        } else {
            _settingsState.update {
                it.copy(
                    performance = it.performance.copy(
                        extreme = it.performance.extreme.copy(
                            maxConcurrentConnections = InputFieldState(
                                value = value,
                                error = "Invalid value",
                                isValid = false
                            )
                        )
                    )
                )
            }
        }
    }

    /**
     * Set parallel DNS queries.
     */
    fun setParallelDnsQueries(enabled: Boolean) {
        prefs.parallelDnsQueries = enabled
        _settingsState.update {
            it.copy(
                performance = it.performance.copy(
                    extreme = it.performance.extreme.copy(
                        parallelDnsQueries = enabled
                    )
                )
            )
        }
    }

    /**
     * Set extreme proxy optimization.
     */
    fun setExtremeProxyOptimization(enabled: Boolean) {
        prefs.extremeProxyOptimization = enabled
        _settingsState.update {
            it.copy(
                performance = it.performance.copy(
                    extreme = it.performance.extreme.copy(
                        extremeProxyOptimization = enabled
                    )
                )
            )
        }
    }

    /**
     * Update rule file info in settings state.
     */
    fun updateRuleFileInfo(geoipSummary: String, geositeSummary: String) {
        _settingsState.update {
            it.copy(
                info = it.info.copy(
                    geoipSummary = geoipSummary,
                    geositeSummary = geositeSummary
                )
            )
        }
    }

    /**
     * Update rule file custom status.
     */
    fun updateRuleFileCustomStatus(isGeoipCustom: Boolean, isGeositeCustom: Boolean) {
        _settingsState.update {
            it.copy(
                files = it.files.copy(
                    isGeoipCustom = isGeoipCustom,
                    isGeositeCustom = isGeositeCustom
                )
            )
        }
    }

    /**
     * Update rule file URLs.
     */
    fun updateRuleFileUrls(geoipUrl: String, geositeUrl: String) {
        _settingsState.update {
            it.copy(
                info = it.info.copy(
                    geoipUrl = geoipUrl,
                    geositeUrl = geositeUrl
                )
            )
        }
    }

    /**
     * Update kernel version info.
     */
    fun updateKernelVersion(kernelVersion: String) {
        _settingsState.update {
            it.copy(
                info = it.info.copy(
                    kernelVersion = kernelVersion
                )
            )
        }
    }

    /**
     * Set Xray core instance count.
     */
    fun setXrayCoreInstanceCount(count: Int) {
        prefs.xrayCoreInstanceCount = count
        _settingsState.update {
            it.copy(xrayCoreInstanceCount = count)
        }
    }

    /**
     * Set bypass domains.
     */
    fun setBypassDomains(domains: List<String>) {
        prefs.bypassDomains = domains
        _settingsState.update {
            it.copy(bypassDomains = domains)
        }
    }

    /**
     * Set bypass IPs.
     */
    fun setBypassIps(ips: List<String>) {
        prefs.bypassIps = ips
        _settingsState.update {
            it.copy(bypassIps = ips)
        }
    }

    companion object {
        private const val TAG = "SettingsRepository"
        private val IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
        )
        private val IPV6_PATTERN = Pattern.compile(
            "^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|^::1$|^::$"
        )
    }
}

