package com.hyperxray.an.viewmodel.logic

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

private const val TAG = "StreamHandler"

/**
 * Handler for Stream & Advanced settings logic.
 * Manages transport type selection (TCP/WS/GRPC), security type (TLS/None),
 * SNI, Fingerprint, ALPN management, Fragment, Mux, and Noise (God Mode) settings.
 */
class StreamHandler {
    // SNI and security state
    private val _sni = MutableStateFlow<String>("")
    val sni: StateFlow<String> = _sni.asStateFlow()

    private val _streamSecurity = MutableStateFlow<String?>(null)
    val streamSecurity: StateFlow<String?> = _streamSecurity.asStateFlow()

    // Advanced TLS settings state
    private val _fingerprint = MutableStateFlow<String>("chrome")
    val fingerprint: StateFlow<String> = _fingerprint.asStateFlow()

    private val _alpn = MutableStateFlow<String>("default")
    val alpn: StateFlow<String> = _alpn.asStateFlow()

    private val _allowInsecure = MutableStateFlow<Boolean>(false)
    val allowInsecure: StateFlow<Boolean> = _allowInsecure.asStateFlow()

    // DPI Evasion (God Mode) settings state
    private val _enableFragment = MutableStateFlow<Boolean>(false)
    val enableFragment: StateFlow<Boolean> = _enableFragment.asStateFlow()

    private val _fragmentLength = MutableStateFlow<String>("100-200")
    val fragmentLength: StateFlow<String> = _fragmentLength.asStateFlow()

    private val _fragmentInterval = MutableStateFlow<String>("10-30")
    val fragmentInterval: StateFlow<String> = _fragmentInterval.asStateFlow()

    private val _enableMux = MutableStateFlow<Boolean>(false)
    val enableMux: StateFlow<Boolean> = _enableMux.asStateFlow()

    private val _muxConcurrency = MutableStateFlow<Int>(8)
    val muxConcurrency: StateFlow<Int> = _muxConcurrency.asStateFlow()

    /**
     * Parse stream settings from config JSON.
     * Updates state flows for UI binding.
     */
    fun parseStreamSettings(outbound: JSONObject) {
        try {
            val streamSettings = outbound.optJSONObject("streamSettings")

            if (streamSettings != null) {
                val security = streamSettings.optString("security", "").takeIf { it.isNotEmpty() }
                _streamSecurity.value = security

                when (security) {
                    "tls" -> {
                        val tlsSettings = streamSettings.optJSONObject("tlsSettings")
                        val sniValue = tlsSettings?.optString("serverName", "") ?: ""
                        _sni.value = sniValue

                        val fingerprintValue = tlsSettings?.optString("fingerprint", "chrome") ?: "chrome"
                        _fingerprint.value = fingerprintValue

                        val allowInsecureValue = tlsSettings?.optBoolean("allowInsecure", false) ?: false
                        _allowInsecure.value = allowInsecureValue

                        val alpnValue = when {
                            tlsSettings?.has("alpn") == true -> {
                                val alpnObj = tlsSettings.opt("alpn")
                                when {
                                    alpnObj is String -> alpnObj
                                    alpnObj is org.json.JSONArray && alpnObj.length() > 0 -> {
                                        (0 until alpnObj.length())
                                            .mapNotNull { alpnObj.optString(it, null) }
                                            .joinToString(",")
                                    }
                                    else -> "default"
                                }
                            }
                            else -> "default"
                        }
                        _alpn.value = alpnValue
                    }
                    "reality" -> {
                        val realitySettings = streamSettings.optJSONObject("realitySettings")
                        val sniValue = realitySettings?.optString("serverName", "") ?: ""
                        _sni.value = sniValue
                        _fingerprint.value = "chrome"
                        _alpn.value = "default"
                        _allowInsecure.value = false
                    }
                    else -> {
                        _sni.value = ""
                        _fingerprint.value = "chrome"
                        _alpn.value = "default"
                        _allowInsecure.value = false
                    }
                }
            } else {
                _streamSecurity.value = null
                _sni.value = ""
                _fingerprint.value = "chrome"
                _alpn.value = "default"
                _allowInsecure.value = false
            }

            // Parse DPI Evasion settings
            parseEvasionSettings(outbound)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse stream settings: ${e.message}")
            reset()
        }
    }

    /**
     * Parse DPI Evasion settings (fragment and mux) from outbound JSON.
     */
    private fun parseEvasionSettings(outbound: JSONObject) {
        try {
            val sockopt = outbound.optJSONObject("sockopt")
            if (sockopt != null) {
                val dialerProxy = sockopt.optString("dialerProxy", "")
                if (dialerProxy == "fragment") {
                    _enableFragment.value = true

                    val fragment = sockopt.optJSONObject("fragment")
                    if (fragment != null) {
                        val length = fragment.optString("length", "")
                        val interval = fragment.optString("interval", "")
                        if (length.isNotEmpty()) {
                            _fragmentLength.value = length
                        }
                        if (interval.isNotEmpty()) {
                            _fragmentInterval.value = interval
                        }
                    }
                } else {
                    _enableFragment.value = false
                }
            } else {
                _enableFragment.value = false
            }

            val mux = outbound.optJSONObject("mux")
            if (mux != null) {
                val enabled = mux.optBoolean("enabled", false)
                _enableMux.value = enabled

                val concurrency = mux.optInt("concurrency", 8)
                _muxConcurrency.value = concurrency
            } else {
                _enableMux.value = false
                _muxConcurrency.value = 8
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse evasion settings: ${e.message}")
            _enableFragment.value = false
            _fragmentLength.value = "100-200"
            _fragmentInterval.value = "10-30"
            _enableMux.value = false
            _muxConcurrency.value = 8
        }
    }

    /**
     * Update SNI in config JSON.
     * Only updates if security is "tls" (not "reality").
     */
    fun updateSni(configJson: JSONObject, newSni: String): Result<String> {
        return try {
            val outbounds = configJson.optJSONArray("outbounds") ?: configJson.optJSONArray("outbound")

            if (outbounds != null && outbounds.length() > 0) {
                val outbound = outbounds.getJSONObject(0)
                var streamSettings = outbound.optJSONObject("streamSettings")

                if (streamSettings != null) {
                    val security = streamSettings.optString("security", "")

                    if (security == "tls") {
                        var tlsSettings = streamSettings.optJSONObject("tlsSettings")
                        if (tlsSettings == null) {
                            tlsSettings = JSONObject()
                        }

                        val settings = outbound.optJSONObject("settings")
                        val vnext = settings?.optJSONArray("vnext")
                        val serverAddress = vnext?.getJSONObject(0)?.optString("address", "") ?: ""

                        val finalSni = if (newSni.isBlank()) {
                            serverAddress
                        } else {
                            newSni
                        }
                        tlsSettings.put("serverName", finalSni)

                        if (finalSni.isNotEmpty() && serverAddress.isNotEmpty() &&
                            finalSni != serverAddress) {
                            tlsSettings.put("allowInsecure", true)
                            Log.d(TAG, "SNI ($finalSni) differs from server address ($serverAddress), setting allowInsecure: true")
                        } else if (!tlsSettings.has("allowInsecure")) {
                            tlsSettings.put("allowInsecure", false)
                        }

                        streamSettings.put("tlsSettings", tlsSettings)
                        outbound.put("streamSettings", streamSettings)

                        if (configJson.has("outbounds")) {
                            configJson.put("outbounds", outbounds)
                        } else {
                            configJson.put("outbound", outbounds)
                        }

                        _sni.value = newSni
                        Result.success(configJson.toString(2))
                    } else {
                        Result.failure(IllegalStateException("Security is not 'tls', cannot update SNI"))
                    }
                } else {
                    Result.failure(IllegalStateException("No streamSettings found"))
                }
            } else {
                Result.failure(IllegalStateException("No outbounds found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update SNI: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Update fingerprint in config JSON.
     */
    fun updateFingerprint(configJson: JSONObject, newFingerprint: String): Result<String> {
        return updateTlsSetting(configJson, "fingerprint", newFingerprint) { tlsSettings, value ->
            tlsSettings.put("fingerprint", value)
        }.also {
            if (it.isSuccess) {
                _fingerprint.value = newFingerprint
            }
        }
    }

    /**
     * Update ALPN in config JSON.
     */
    fun updateAlpn(configJson: JSONObject, newAlpn: String): Result<String> {
        return updateTlsSetting(configJson, "alpn", newAlpn) { tlsSettings, value ->
            if (value == "default") {
                tlsSettings.remove("alpn")
            } else {
                val alpnArray = org.json.JSONArray()
                value.split(",").forEach { item ->
                    alpnArray.put(item.trim())
                }
                tlsSettings.put("alpn", alpnArray)
            }
        }.also {
            if (it.isSuccess) {
                _alpn.value = newAlpn
            }
        }
    }

    /**
     * Update allowInsecure in config JSON.
     */
    fun updateAllowInsecure(configJson: JSONObject, newAllowInsecure: Boolean): Result<String> {
        return updateTlsSetting(configJson, "allowInsecure", newAllowInsecure) { tlsSettings, value ->
            tlsSettings.put("allowInsecure", value)
        }.also {
            if (it.isSuccess) {
                _allowInsecure.value = newAllowInsecure
            }
        }
    }

    /**
     * Update fragment (TLS fragmentation) settings in config JSON.
     */
    fun updateFragmentSettings(
        configJson: JSONObject,
        enabled: Boolean,
        length: String = "",
        interval: String = ""
    ): Result<String> {
        return try {
            val outbounds = configJson.optJSONArray("outbounds") ?: configJson.optJSONArray("outbound")

            if (outbounds != null && outbounds.length() > 0) {
                val outbound = outbounds.getJSONObject(0)

                if (enabled) {
                    var sockopt = outbound.optJSONObject("sockopt")
                    if (sockopt == null) {
                        sockopt = JSONObject()
                    }

                    sockopt.put("dialerProxy", "fragment")
                    sockopt.put("tcpNoDelay", true)

                    if (length.isNotEmpty() || interval.isNotEmpty()) {
                        val fragment = JSONObject()
                        if (length.isNotEmpty()) {
                            fragment.put("length", length)
                        }
                        if (interval.isNotEmpty()) {
                            fragment.put("interval", interval)
                        }
                        sockopt.put("fragment", fragment)
                    }

                    outbound.put("sockopt", sockopt)
                } else {
                    val sockopt = outbound.optJSONObject("sockopt")
                    if (sockopt != null) {
                        sockopt.remove("dialerProxy")
                        sockopt.remove("fragment")
                        if (sockopt.length() == 0 || (sockopt.length() == 1 && sockopt.has("tcpNoDelay"))) {
                            outbound.remove("sockopt")
                        } else {
                            outbound.put("sockopt", sockopt)
                        }
                    }
                }

                if (configJson.has("outbounds")) {
                    configJson.put("outbounds", outbounds)
                } else {
                    configJson.put("outbound", outbounds)
                }

                _enableFragment.value = enabled
                if (length.isNotEmpty()) {
                    _fragmentLength.value = length
                }
                if (interval.isNotEmpty()) {
                    _fragmentInterval.value = interval
                }

                Result.success(configJson.toString(2))
            } else {
                Result.failure(IllegalStateException("No outbounds found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update fragment settings: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Update mux (multiplexing) settings in config JSON.
     */
    fun updateMuxSettings(configJson: JSONObject, enabled: Boolean, concurrency: Int = 8): Result<String> {
        return try {
            val outbounds = configJson.optJSONArray("outbounds") ?: configJson.optJSONArray("outbound")

            if (outbounds != null && outbounds.length() > 0) {
                val outbound = outbounds.getJSONObject(0)

                if (enabled) {
                    val mux = JSONObject()
                    mux.put("enabled", true)
                    mux.put("concurrency", concurrency.coerceIn(1, 1024))
                    outbound.put("mux", mux)
                } else {
                    outbound.remove("mux")
                }

                if (configJson.has("outbounds")) {
                    configJson.put("outbounds", outbounds)
                } else {
                    configJson.put("outbound", outbounds)
                }

                _enableMux.value = enabled
                _muxConcurrency.value = concurrency.coerceIn(1, 1024)

                Result.success(configJson.toString(2))
            } else {
                Result.failure(IllegalStateException("No outbounds found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update mux settings: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Helper function to update TLS settings in config JSON.
     */
    private fun <T> updateTlsSetting(
        configJson: JSONObject,
        settingName: String,
        newValue: T,
        updateAction: (JSONObject, T) -> Unit
    ): Result<String> {
        return try {
            val outbounds = configJson.optJSONArray("outbounds") ?: configJson.optJSONArray("outbound")

            if (outbounds != null && outbounds.length() > 0) {
                val outbound = outbounds.getJSONObject(0)
                var streamSettings = outbound.optJSONObject("streamSettings")

                if (streamSettings != null) {
                    val security = streamSettings.optString("security", "")

                    if (security == "tls") {
                        var tlsSettings = streamSettings.optJSONObject("tlsSettings")
                        if (tlsSettings == null) {
                            tlsSettings = JSONObject()
                        }

                        updateAction(tlsSettings, newValue)

                        streamSettings.put("tlsSettings", tlsSettings)
                        outbound.put("streamSettings", streamSettings)

                        if (configJson.has("outbounds")) {
                            configJson.put("outbounds", outbounds)
                        } else {
                            configJson.put("outbound", outbounds)
                        }

                        Result.success(configJson.toString(2))
                    } else {
                        Result.failure(IllegalStateException("Security is not 'tls', skipping $settingName update"))
                    }
                } else {
                    Result.failure(IllegalStateException("No streamSettings found, skipping $settingName update"))
                }
            } else {
                Result.failure(IllegalStateException("No outbounds found, skipping $settingName update"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update $settingName: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Reset all stream settings to defaults.
     */
    fun reset() {
        _sni.value = ""
        _streamSecurity.value = null
        _fingerprint.value = "chrome"
        _alpn.value = "default"
        _allowInsecure.value = false
        _enableFragment.value = false
        _fragmentLength.value = "100-200"
        _fragmentInterval.value = "10-30"
        _enableMux.value = false
        _muxConcurrency.value = 8
    }
}

