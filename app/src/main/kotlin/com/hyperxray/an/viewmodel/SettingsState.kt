package com.hyperxray.an.viewmodel

import com.hyperxray.an.common.ThemeMode

data class InputFieldState(
    val value: String,
    val error: String? = null,
    val isValid: Boolean = true
)

data class SwitchStates(
    val ipv6Enabled: Boolean,
    val useTemplateEnabled: Boolean,
    val httpProxyEnabled: Boolean,
    val bypassLanEnabled: Boolean,
    val disableVpn: Boolean,
    val themeMode: ThemeMode
)

data class InfoStates(
    val appVersion: String,
    val kernelVersion: String,
    val geoipSummary: String,
    val geositeSummary: String,
    val geoipUrl: String,
    val geositeUrl: String
)

data class FileStates(
    val isGeoipCustom: Boolean,
    val isGeositeCustom: Boolean
)

data class ExtremeOptimizationSettings(
    val extremeRamCpuOptimizations: Boolean,
    val extremeConnIdleTimeout: InputFieldState,
    val extremeHandshakeTimeout: InputFieldState,
    val extremeUplinkOnly: InputFieldState,
    val extremeDownlinkOnly: InputFieldState,
    val extremeDnsCacheSize: InputFieldState,
    val extremeDisableFakeDns: Boolean,
    val extremeRoutingOptimization: Boolean,
    val maxConcurrentConnections: InputFieldState,
    val parallelDnsQueries: Boolean,
    val extremeProxyOptimization: Boolean
)

data class PerformanceSettings(
    val aggressiveSpeedOptimizations: Boolean,
    val connIdleTimeout: InputFieldState,
    val handshakeTimeout: InputFieldState,
    val uplinkOnly: InputFieldState,
    val downlinkOnly: InputFieldState,
    val dnsCacheSize: InputFieldState,
    val disableFakeDns: Boolean,
    val optimizeRoutingRules: Boolean,
    val tcpFastOpen: Boolean,
    val http2Optimization: Boolean,
    val extreme: ExtremeOptimizationSettings
)

data class SettingsState(
    val socksPort: InputFieldState,
    val dnsIpv4: InputFieldState,
    val dnsIpv6: InputFieldState,
    val switches: SwitchStates,
    val info: InfoStates,
    val files: FileStates,
    val connectivityTestTarget: InputFieldState,
    val connectivityTestTimeout: InputFieldState,
    val performance: PerformanceSettings,
    val bypassDomains: List<String> = emptyList(),
    val bypassIps: List<String> = emptyList(),
    val xrayCoreInstanceCount: Int = 1
) 