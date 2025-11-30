package com.hyperxray.an.prefs

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hyperxray.an.common.ThemeMode

class Preferences(context: Context) {
    private val contentResolver: ContentResolver
    private val gson: Gson
    private val context1: Context = context.applicationContext

    init {
        this.contentResolver = context1.contentResolver
        this.gson = Gson()
    }
    
    /**
     * Get application context for external use (e.g., initializing components)
     */
    fun getContext(): Context = context1

    private fun getPrefData(key: String): Pair<String?, String?> {
        val uri = PrefsContract.PrefsEntry.CONTENT_URI.buildUpon().appendPath(key).build()
        try {
            contentResolver.query(
                uri, arrayOf(
                    PrefsContract.PrefsEntry.COLUMN_PREF_VALUE,
                    PrefsContract.PrefsEntry.COLUMN_PREF_TYPE
                ), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val valueColumnIndex =
                        cursor.getColumnIndex(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE)
                    val typeColumnIndex =
                        cursor.getColumnIndex(PrefsContract.PrefsEntry.COLUMN_PREF_TYPE)
                    val value =
                        if (valueColumnIndex != -1) cursor.getString(valueColumnIndex) else null
                    val type =
                        if (typeColumnIndex != -1) cursor.getString(typeColumnIndex) else null
                    return Pair(value, type)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading preference data for key: $key", e)
        }
        return Pair(null, null)
    }

    private fun getBooleanPref(key: String, default: Boolean): Boolean {
        val (value, type) = getPrefData(key)
        if (value != null && "Boolean" == type) {
            return value.toBoolean()
        }
        return default
    }

    private fun setValueInProvider(key: String, value: Any?) {
        val uri = PrefsContract.PrefsEntry.CONTENT_URI.buildUpon().appendPath(key).build()
        val values = ContentValues()
        when (value) {
            is String -> {
                values.put(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE, value)
            }

            is Int -> {
                values.put(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE, value)
            }

            is Boolean -> {
                values.put(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE, value)
            }

            is Long -> {
                values.put(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE, value)
            }

            is Float -> {
                values.put(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE, value)
            }

            else -> {
                if (value != null) {
                    Log.e(TAG, "Unsupported type for key: $key with value: $value")
                    return
                }
                values.putNull(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE)
            }
        }
        try {
            val rows = contentResolver.update(uri, values, null, null)
            if (rows == 0) {
                Log.w(TAG, "Update failed or key not found for: $key")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting preference for key: $key", e)
        }
    }

    val socksAddress: String
        get() = getPrefData(SOCKS_ADDR).first ?: "127.0.0.1"

    var socksPort: Int
        get() {
            val value = getPrefData(SOCKS_PORT).first
            val port = value?.toIntOrNull()
            if (value != null && port == null) {
                Log.e(TAG, "Failed to parse SocksPort as Integer: $value")
            }
            return port ?: 10808
        }
        set(port) {
            setValueInProvider(SOCKS_PORT, port.toString())
        }

    val socksUsername: String
        get() = getPrefData(SOCKS_USER).first ?: ""

    val socksPassword: String
        get() = getPrefData(SOCKS_PASS).first ?: ""

    var dnsIpv4: String
        get() = getPrefData(DNS_IPV4).first ?: "8.8.8.8"
        set(addr) {
            setValueInProvider(DNS_IPV4, addr)
        }

    var dnsIpv6: String
        get() = getPrefData(DNS_IPV6).first ?: "2001:4860:4860::8888"
        set(addr) {
            setValueInProvider(DNS_IPV6, addr)
        }

    val udpInTcp: Boolean
        get() = getBooleanPref(UDP_IN_TCP, false)

    var ipv4: Boolean
        get() = getBooleanPref(IPV4, true)
        set(enable) {
            setValueInProvider(IPV4, enable)
        }

    var ipv6: Boolean
        get() = getBooleanPref(IPV6, false)
        set(enable) {
            setValueInProvider(IPV6, enable)
        }

    var global: Boolean
        get() = getBooleanPref(GLOBAL, false)
        set(enable) {
            setValueInProvider(GLOBAL, enable)
        }

    var apps: Set<String?>?
        get() {
            val jsonSet = getPrefData(APPS).first
            return jsonSet?.let {
                try {
                    val type = object : TypeToken<Set<String?>?>() {}.type
                    gson.fromJson<Set<String?>>(it, type)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing APPS StringSet", e)
                    null
                }
            }
        }
        set(apps) {
            val jsonSet = gson.toJson(apps)
            setValueInProvider(APPS, jsonSet)
        }

    var enable: Boolean
        get() = getBooleanPref(ENABLE, false)
        set(enable) {
            setValueInProvider(ENABLE, enable)
        }

    var disableVpn: Boolean
        get() = getBooleanPref(DISABLE_VPN, false)
        set(value) {
            setValueInProvider(DISABLE_VPN, value)
        }

    val tunnelMtu: Int
        get() = 1500

    val tunnelIpv4Address: String
        get() = "198.18.0.1"

    val tunnelIpv4Prefix: Int
        get() = 32

    val tunnelIpv6Address: String
        get() = "fc00::1"

    val tunnelIpv6Prefix: Int
        get() = 128

    val taskStackSize: Int
        get() = 81920

    // VPN Tunnel Maximum Performance Settings
    var tunnelMtuCustom: Int
        get() = getPrefData("TunnelMtuCustom").first?.toIntOrNull() ?: 1500
        set(value) {
            setValueInProvider("TunnelMtuCustom", value.toString())
        }

    var taskStackSizeCustom: Int
        get() = getPrefData("TaskStackSizeCustom").first?.toIntOrNull() ?: 81920
        set(value) {
            setValueInProvider("TaskStackSizeCustom", value.toString())
        }

    var tcpBufferSize: Int
        get() = getPrefData("TcpBufferSize").first?.toIntOrNull() ?: 262144 // 256KB for maximum throughput
        set(value) {
            setValueInProvider("TcpBufferSize", value.toString())
        }

    var limitNofile: Int
        get() = getPrefData("LimitNofile").first?.toIntOrNull() ?: 65535
        set(value) {
            setValueInProvider("LimitNofile", value.toString())
        }

    var connectTimeout: Int
        get() = getPrefData("ConnectTimeout").first?.toIntOrNull() ?: 10000 // 10 seconds
        set(value) {
            setValueInProvider("ConnectTimeout", value.toString())
        }

    var readWriteTimeout: Int
        get() = getPrefData("ReadWriteTimeout").first?.toIntOrNull() ?: 300000 // 5 minutes to prevent broken pipe
        set(value) {
            setValueInProvider("ReadWriteTimeout", value.toString())
        }

    var socks5Pipeline: Boolean
        get() = getBooleanPref("Socks5Pipeline", false)
        set(value) {
            setValueInProvider("Socks5Pipeline", value.toString())
        }

    var tunnelMultiQueue: Boolean
        get() = getBooleanPref("TunnelMultiQueue", false)
        set(value) {
            setValueInProvider("TunnelMultiQueue", value.toString())
        }

    var selectedConfigPath: String?
        get() = getPrefData(SELECTED_CONFIG_PATH).first
        set(path) {
            setValueInProvider(SELECTED_CONFIG_PATH, path)
        }

    var bypassLan: Boolean
        get() = getBooleanPref(BYPASS_LAN, true)
        set(enable) {
            setValueInProvider(BYPASS_LAN, enable)
        }

    var useTemplate: Boolean
        get() = getBooleanPref(USE_TEMPLATE, true)
        set(enable) {
            setValueInProvider(USE_TEMPLATE, enable)
        }

    var httpProxyEnabled: Boolean
        get() = getBooleanPref(HTTP_PROXY_ENABLED, true)
        set(enable) {
            setValueInProvider(HTTP_PROXY_ENABLED, enable)
        }

    var customGeoipImported: Boolean
        get() = getBooleanPref(CUSTOM_GEOIP_IMPORTED, false)
        set(imported) {
            setValueInProvider(CUSTOM_GEOIP_IMPORTED, imported)
        }

    var customGeositeImported: Boolean
        get() = getBooleanPref(CUSTOM_GEOSITE_IMPORTED, false)
        set(imported) {
            setValueInProvider(CUSTOM_GEOSITE_IMPORTED, imported)
        }

    var configFilesOrder: List<String>
        get() {
            val jsonList = getPrefData(CONFIG_FILES_ORDER).first
            return jsonList?.let {
                try {
                    val type = object : TypeToken<List<String>>() {}.type
                    gson.fromJson(it, type)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing CONFIG_FILES_ORDER List<String>", e)
                    emptyList()
                }
            } ?: emptyList()
        }
        set(order) {
            val jsonList = gson.toJson(order)
            setValueInProvider(CONFIG_FILES_ORDER, jsonList)
        }

    var connectivityTestTarget: String
        get() = getPrefData(CONNECTIVITY_TEST_TARGET).first
            ?: "http://www.gstatic.com/generate_204"
        set(value) {
            setValueInProvider(CONNECTIVITY_TEST_TARGET, value)
        }

    var connectivityTestTimeout: Int
        get() = getPrefData(CONNECTIVITY_TEST_TIMEOUT).first?.toIntOrNull() ?: 3000
        set(value) {
            setValueInProvider(CONNECTIVITY_TEST_TIMEOUT, value.toString())
        }

    var geoipUrl: String
        get() = getPrefData(GEOIP_URL).first
            ?: "https://github.com/lhear/v2ray-rules-dat/releases/latest/download/geoip.dat"
        set(value) {
            setValueInProvider(GEOIP_URL, value)
        }

    var geositeUrl: String
        get() = getPrefData(GEOSITE_URL).first
            ?: "https://github.com/lhear/v2ray-rules-dat/releases/latest/download/geosite.dat"
        set(value) {
            setValueInProvider(GEOSITE_URL, value)
        }

    var apiPort: Int
        get() {
            val value = getPrefData(API_PORT).first
            val port = value?.toIntOrNull()
            return port ?: 0
        }
        set(port) {
            setValueInProvider(API_PORT, port.toString())
        }

    var bypassSelectedApps: Boolean
        get() = getBooleanPref(BYPASS_SELECTED_APPS, false)
        set(enable) {
            setValueInProvider(BYPASS_SELECTED_APPS, enable)
        }

    var theme: ThemeMode
        get() = getPrefData(THEME).first?.let { ThemeMode.fromString(it) } ?: ThemeMode.Auto
        set(value) {
            setValueInProvider(THEME, value.value)
        }

    // Aggressive Speed Optimization Settings
    var aggressiveSpeedOptimizations: Boolean
        get() = getBooleanPref(AGGRESSIVE_SPEED_OPTIMIZATIONS, false)
        set(enable) {
            setValueInProvider(AGGRESSIVE_SPEED_OPTIMIZATIONS, enable)
        }

    var connIdleTimeout: Int
        get() = getPrefData(CONN_IDLE_TIMEOUT).first?.toIntOrNull() ?: 300
        set(value) {
            setValueInProvider(CONN_IDLE_TIMEOUT, value.toString())
        }

    var handshakeTimeout: Int
        get() = getPrefData(HANDSHAKE_TIMEOUT).first?.toIntOrNull() ?: 4
        set(value) {
            setValueInProvider(HANDSHAKE_TIMEOUT, value.toString())
        }

    var uplinkOnly: Int
        get() = getPrefData(UPLINK_ONLY).first?.toIntOrNull() ?: 2
        set(value) {
            setValueInProvider(UPLINK_ONLY, value.toString())
        }

    var downlinkOnly: Int
        get() = getPrefData(DOWNLINK_ONLY).first?.toIntOrNull() ?: 5
        set(value) {
            setValueInProvider(DOWNLINK_ONLY, value.toString())
        }

    var dnsCacheSize: Int
        get() = getPrefData(DNS_CACHE_SIZE).first?.toIntOrNull() ?: 5000
        set(value) {
            setValueInProvider(DNS_CACHE_SIZE, value.toString())
        }

    var disableFakeDns: Boolean
        get() = getBooleanPref(DISABLE_FAKE_DNS, false)
        set(enable) {
            setValueInProvider(DISABLE_FAKE_DNS, enable)
        }

    var optimizeRoutingRules: Boolean
        get() = getBooleanPref(OPTIMIZE_ROUTING_RULES, true)
        set(enable) {
            setValueInProvider(OPTIMIZE_ROUTING_RULES, enable)
        }

    var tcpFastOpen: Boolean
        get() = getBooleanPref(TCP_FAST_OPEN, true)
        set(enable) {
            setValueInProvider(TCP_FAST_OPEN, enable)
        }

    var http2Optimization: Boolean
        get() = getBooleanPref(HTTP2_OPTIMIZATION, true)
        set(enable) {
            setValueInProvider(HTTP2_OPTIMIZATION, enable)
        }

    // Extreme RAM/CPU Optimization Settings
    var extremeRamCpuOptimizations: Boolean
        get() = getBooleanPref(EXTREME_RAM_CPU_OPTIMIZATIONS, false)
        set(enable) {
            setValueInProvider(EXTREME_RAM_CPU_OPTIMIZATIONS, enable)
        }

    var extremeConnIdleTimeout: Int
        get() = getPrefData(EXTREME_CONN_IDLE_TIMEOUT).first?.toIntOrNull() ?: 600
        set(value) {
            setValueInProvider(EXTREME_CONN_IDLE_TIMEOUT, value.toString())
        }

    var extremeHandshakeTimeout: Int
        get() = getPrefData(EXTREME_HANDSHAKE_TIMEOUT).first?.toIntOrNull() ?: 8
        set(value) {
            setValueInProvider(EXTREME_HANDSHAKE_TIMEOUT, value.toString())
        }

    var extremeUplinkOnly: Int
        get() = getPrefData(EXTREME_UPLINK_ONLY).first?.toIntOrNull() ?: 8
        set(value) {
            setValueInProvider(EXTREME_UPLINK_ONLY, value.toString())
        }

    var extremeDownlinkOnly: Int
        get() = getPrefData(EXTREME_DOWNLINK_ONLY).first?.toIntOrNull() ?: 16
        set(value) {
            setValueInProvider(EXTREME_DOWNLINK_ONLY, value.toString())
        }

    var extremeDnsCacheSize: Int
        get() = getPrefData(EXTREME_DNS_CACHE_SIZE).first?.toIntOrNull() ?: 10000
        set(value) {
            setValueInProvider(EXTREME_DNS_CACHE_SIZE, value.toString())
        }

    var extremeDisableFakeDns: Boolean
        get() = getBooleanPref(EXTREME_DISABLE_FAKE_DNS, true)
        set(enable) {
            setValueInProvider(EXTREME_DISABLE_FAKE_DNS, enable)
        }

    var extremeRoutingOptimization: Boolean
        get() = getBooleanPref(EXTREME_ROUTING_OPTIMIZATION, true)
        set(enable) {
            setValueInProvider(EXTREME_ROUTING_OPTIMIZATION, enable)
        }

    var maxConcurrentConnections: Int
        get() = getPrefData(MAX_CONCURRENT_CONNECTIONS).first?.toIntOrNull() ?: 0
        set(value) {
            setValueInProvider(MAX_CONCURRENT_CONNECTIONS, value.toString())
        }

    var parallelDnsQueries: Boolean
        get() = getBooleanPref(PARALLEL_DNS_QUERIES, true)
        set(enable) {
            setValueInProvider(PARALLEL_DNS_QUERIES, enable)
        }

    var extremeProxyOptimization: Boolean
        get() = getBooleanPref(EXTREME_PROXY_OPTIMIZATION, true)
        set(enable) {
            setValueInProvider(EXTREME_PROXY_OPTIMIZATION, enable)
        }

    var bypassDomains: List<String>
        get() {
            val jsonList = getPrefData(BYPASS_DOMAINS).first
            return jsonList?.let {
                try {
                    val type = object : TypeToken<List<String>>() {}.type
                    gson.fromJson(it, type)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing BYPASS_DOMAINS List<String>", e)
                    emptyList()
                }
            } ?: emptyList()
        }
        set(domains) {
            val jsonList = gson.toJson(domains)
            setValueInProvider(BYPASS_DOMAINS, jsonList)
        }

    var bypassIps: List<String>
        get() {
            val jsonList = getPrefData(BYPASS_IPS).first
            return jsonList?.let {
                try {
                    val type = object : TypeToken<List<String>>() {}.type
                    gson.fromJson(it, type)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing BYPASS_IPS List<String>", e)
                    emptyList()
                }
            } ?: emptyList()
        }
        set(ips) {
            val jsonList = gson.toJson(ips)
            setValueInProvider(BYPASS_IPS, jsonList)
        }

    var xrayCoreInstanceCount: Int
        get() {
            val value = getPrefData(XRAY_CORE_INSTANCE_COUNT).first
            val count = value?.toIntOrNull()
            // Return value from preferences, or default to 1 if not set or invalid
            val result = when {
                count == null -> 1
                count < 1 -> 1
                count > 4 -> 4
                else -> count
            }
            Log.d(TAG, "xrayCoreInstanceCount get() returning: $result (from prefs: $value, parsed: $count)")
            return result
        }
        set(count) {
            // Clamp value between 1 and 4
            val clampedCount = when {
                count < 1 -> 1
                count > 4 -> 4
                else -> count
            }
            Log.d(TAG, "xrayCoreInstanceCount set() called with: $count, clamping to: $clampedCount")
            setValueInProvider(XRAY_CORE_INSTANCE_COUNT, clampedCount.toString())
        }

    // Telegram notification preferences
    var telegramEnabled: Boolean
        get() = getBooleanPref(TELEGRAM_ENABLED, false)
        set(enable) {
            setValueInProvider(TELEGRAM_ENABLED, enable)
        }

    var telegramNotifyVpnStatus: Boolean
        get() = getBooleanPref(TELEGRAM_NOTIFY_VPN_STATUS, true)
        set(enable) {
            setValueInProvider(TELEGRAM_NOTIFY_VPN_STATUS, enable)
        }

    var telegramNotifyErrors: Boolean
        get() = getBooleanPref(TELEGRAM_NOTIFY_ERRORS, true)
        set(enable) {
            setValueInProvider(TELEGRAM_NOTIFY_ERRORS, enable)
        }

    var telegramNotifyPerformance: Boolean
        get() = getBooleanPref(TELEGRAM_NOTIFY_PERFORMANCE, false)
        set(enable) {
            setValueInProvider(TELEGRAM_NOTIFY_PERFORMANCE, enable)
        }

    var telegramNotifyDnsCache: Boolean
        get() = getBooleanPref(TELEGRAM_NOTIFY_DNS_CACHE, false)
        set(enable) {
            setValueInProvider(TELEGRAM_NOTIFY_DNS_CACHE, enable)
        }

    var telegramNotifyManual: Boolean
        get() = getBooleanPref(TELEGRAM_NOTIFY_MANUAL, true)
        set(enable) {
            setValueInProvider(TELEGRAM_NOTIFY_MANUAL, enable)
        }

    companion object {
        const val SOCKS_ADDR: String = "SocksAddr"
        const val SOCKS_PORT: String = "SocksPort"
        const val SOCKS_USER: String = "SocksUser"
        const val SOCKS_PASS: String = "SocksPass"
        const val DNS_IPV4: String = "DnsIpv4"
        const val DNS_IPV6: String = "DnsIpv6"
        const val IPV4: String = "Ipv4"
        const val IPV6: String = "Ipv6"
        const val GLOBAL: String = "Global"
        const val UDP_IN_TCP: String = "UdpInTcp"
        const val APPS: String = "Apps"
        const val ENABLE: String = "Enable"
        const val SELECTED_CONFIG_PATH: String = "SelectedConfigPath"
        const val BYPASS_LAN: String = "BypassLan"
        const val USE_TEMPLATE: String = "UseTemplate"
        const val HTTP_PROXY_ENABLED: String = "HttpProxyEnabled"
        const val CUSTOM_GEOIP_IMPORTED: String = "CustomGeoipImported"
        const val CUSTOM_GEOSITE_IMPORTED: String = "CustomGeositeImported"
        const val CONFIG_FILES_ORDER: String = "ConfigFilesOrder"
        const val DISABLE_VPN: String = "DisableVpn"
        const val CONNECTIVITY_TEST_TARGET: String = "ConnectivityTestTarget"
        const val CONNECTIVITY_TEST_TIMEOUT: String = "ConnectivityTestTimeout"
        const val GEOIP_URL: String = "GeoipUrl"
        const val GEOSITE_URL: String = "GeositeUrl"
        const val API_PORT: String = "ApiPort"
        const val BYPASS_SELECTED_APPS: String = "BypassSelectedApps"
        const val THEME: String = "Theme"
        
        // Aggressive Speed Optimization Keys
        const val AGGRESSIVE_SPEED_OPTIMIZATIONS: String = "AggressiveSpeedOptimizations"
        const val CONN_IDLE_TIMEOUT: String = "ConnIdleTimeout"
        const val HANDSHAKE_TIMEOUT: String = "HandshakeTimeout"
        const val UPLINK_ONLY: String = "UplinkOnly"
        const val DOWNLINK_ONLY: String = "DownlinkOnly"
        const val DNS_CACHE_SIZE: String = "DnsCacheSize"
        const val DISABLE_FAKE_DNS: String = "DisableFakeDns"
        const val OPTIMIZE_ROUTING_RULES: String = "OptimizeRoutingRules"
        const val TCP_FAST_OPEN: String = "TcpFastOpen"
        const val HTTP2_OPTIMIZATION: String = "Http2Optimization"
        
        // Extreme RAM/CPU Optimization Keys
        const val EXTREME_RAM_CPU_OPTIMIZATIONS: String = "ExtremeRamCpuOptimizations"
        const val EXTREME_CONN_IDLE_TIMEOUT: String = "ExtremeConnIdleTimeout"
        const val EXTREME_HANDSHAKE_TIMEOUT: String = "ExtremeHandshakeTimeout"
        const val EXTREME_UPLINK_ONLY: String = "ExtremeUplinkOnly"
        const val EXTREME_DOWNLINK_ONLY: String = "ExtremeDownlinkOnly"
        const val EXTREME_DNS_CACHE_SIZE: String = "ExtremeDnsCacheSize"
        const val EXTREME_DISABLE_FAKE_DNS: String = "ExtremeDisableFakeDns"
        const val EXTREME_ROUTING_OPTIMIZATION: String = "ExtremeRoutingOptimization"
        
        // Telegram notification keys
        const val TELEGRAM_ENABLED: String = "TelegramEnabled"
        const val TELEGRAM_NOTIFY_VPN_STATUS: String = "TelegramNotifyVpnStatus"
        const val TELEGRAM_NOTIFY_ERRORS: String = "TelegramNotifyErrors"
        const val TELEGRAM_NOTIFY_PERFORMANCE: String = "TelegramNotifyPerformance"
        const val TELEGRAM_NOTIFY_DNS_CACHE: String = "TelegramNotifyDnsCache"
        const val TELEGRAM_NOTIFY_MANUAL: String = "TelegramNotifyManual"
        const val MAX_CONCURRENT_CONNECTIONS: String = "MaxConcurrentConnections"
        const val PARALLEL_DNS_QUERIES: String = "ParallelDnsQueries"
        const val EXTREME_PROXY_OPTIMIZATION: String = "ExtremeProxyOptimization"
        const val BYPASS_DOMAINS: String = "BypassDomains"
        const val BYPASS_IPS: String = "BypassIps"
        const val XRAY_CORE_INSTANCE_COUNT: String = "XrayCoreInstanceCount"
        const val AUTO_START: String = "AutoStart"
        
        // Sticky routing keys
        const val STICKY_ROUTING_ENABLED: String = "StickyRoutingEnabled"
        const val STICKY_ROUTING_CACHE_SIZE: String = "StickyRoutingCacheSize"
        const val STICKY_ROUTING_TTL_MS: String = "StickyRoutingTtlMs"
        
        // Xray log monitoring key
        const val XRAY_LOG_MONITORING_ENABLED: String = "XrayLogMonitoringEnabled"
        
        private const val TAG = "Preferences"
    }
    
    var autoStart: Boolean
        get() = getBooleanPref(AUTO_START, true)
        set(enable) {
            setValueInProvider(AUTO_START, enable)
        }
    
    // Sticky routing preferences
    var stickyRoutingEnabled: Boolean
        get() = getBooleanPref(STICKY_ROUTING_ENABLED, true)
        set(enable) {
            setValueInProvider(STICKY_ROUTING_ENABLED, enable)
        }
    
    var stickyRoutingCacheSize: Int
        get() {
            val value = getPrefData(STICKY_ROUTING_CACHE_SIZE).first
            return value?.toIntOrNull() ?: 1000 // Default 1000 entries
        }
        set(size) {
            setValueInProvider(STICKY_ROUTING_CACHE_SIZE, size)
        }
    
    var stickyRoutingTtlMs: Long
        get() {
            val value = getPrefData(STICKY_ROUTING_TTL_MS).first
            return value?.toLongOrNull() ?: 3600000L // Default 1 hour
        }
        set(ttl) {
            setValueInProvider(STICKY_ROUTING_TTL_MS, ttl)
        }
    
    // Xray log monitoring preference
    var xrayLogMonitoringEnabled: Boolean
        get() = getBooleanPref(XRAY_LOG_MONITORING_ENABLED, false)
        set(enabled) {
            setValueInProvider(XRAY_LOG_MONITORING_ENABLED, enabled)
        }
}



