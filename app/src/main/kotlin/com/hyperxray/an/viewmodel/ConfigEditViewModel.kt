package com.hyperxray.an.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.hyperxray.an.R
import com.hyperxray.an.common.ConfigUtils
import com.hyperxray.an.common.FilenameValidator
import com.hyperxray.an.data.source.FileManager
import com.hyperxray.an.prefs.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.zip.Deflater

private const val TAG = "ConfigEditViewModel"

/**
 * UI events for ConfigEditViewModel communication.
 */
sealed class ConfigEditUiEvent {
    data class ShowSnackbar(val message: String) : ConfigEditUiEvent()
    data class ShareContent(val content: String) : ConfigEditUiEvent()
    data object NavigateBack : ConfigEditUiEvent()
}

/**
 * ViewModel for editing Xray configuration files.
 * Handles file reading, saving, validation, and sharing via custom URI scheme.
 */
class ConfigEditViewModel(
    application: Application,
    private val initialFilePath: String,
    prefs: Preferences
) :
    AndroidViewModel(application) {

    private var _configFile: File
    private var _originalFilePath: String = initialFilePath

    private val _configTextFieldValue = MutableStateFlow(TextFieldValue())
    val configTextFieldValue: StateFlow<TextFieldValue> = _configTextFieldValue.asStateFlow()

    private val _filename = MutableStateFlow("")
    val filename: StateFlow<String> = _filename.asStateFlow()

    private val _filenameErrorMessage = MutableStateFlow<String?>(null)
    val filenameErrorMessage: StateFlow<String?> = _filenameErrorMessage.asStateFlow()

    private val _uiEvent = Channel<ConfigEditUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    private val _hasConfigChanged = MutableStateFlow(false)
    val hasConfigChanged: StateFlow<Boolean> = _hasConfigChanged.asStateFlow()

    // SNI and security state for UI
    private val _sni = MutableStateFlow<String>("")
    val sni: StateFlow<String> = _sni.asStateFlow()

    private val _streamSecurity = MutableStateFlow<String?>(null)
    val streamSecurity: StateFlow<String?> = _streamSecurity.asStateFlow()

    // Advanced TLS settings state for UI
    private val _fingerprint = MutableStateFlow<String>("chrome")
    val fingerprint: StateFlow<String> = _fingerprint.asStateFlow()

    private val _alpn = MutableStateFlow<String>("default")
    val alpn: StateFlow<String> = _alpn.asStateFlow()

    private val _allowInsecure = MutableStateFlow<Boolean>(false)
    val allowInsecure: StateFlow<Boolean> = _allowInsecure.asStateFlow()

    // DPI Evasion (God Mode) settings state for UI
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

    private val fileManager: FileManager = FileManager(application, prefs)

    init {
        _configFile = File(initialFilePath)
        _filename.value = _configFile.nameWithoutExtension

        viewModelScope.launch(Dispatchers.IO) {
            val content = readConfigFileContent()
            withContext(Dispatchers.Main) {
                _configTextFieldValue.value = _configTextFieldValue.value.copy(text = content)
                // Parse SNI and security from config
                parseConfigFields(content)
            }
        }
    }

    private val File.nameWithoutExtension: String
        get() {
            var name = this.name
            if (name.endsWith(".json")) {
                name = name.substring(0, name.length - ".json".length)
            }
            return name
        }

    private suspend fun readConfigFileContent(): String = withContext(Dispatchers.IO) {
        if (!_configFile.exists()) {
            Log.e(TAG, "Config not found at path: $initialFilePath")
            return@withContext ""
        }
        try {
            _configFile.readText()
        } catch (e: IOException) {
            Log.e(TAG, "Error reading config file", e)
            ""
        }
    }

    fun onConfigContentChange(newValue: TextFieldValue) {
        val oldText = _configTextFieldValue.value.text
        _configTextFieldValue.value = newValue
        _hasConfigChanged.value = true
        // Parse SNI and security from updated config only if text actually changed
        if (oldText != newValue.text) {
            parseConfigFields(newValue.text)
        }
    }

    /**
     * Parse SNI and stream security from config JSON.
     * Updates state flows for UI binding.
     */
    private fun parseConfigFields(configContent: String) {
        try {
            val jsonObject = JSONObject(configContent)
            val outbounds = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
            
            if (outbounds != null && outbounds.length() > 0) {
                val outbound = outbounds.getJSONObject(0)
                val streamSettings = outbound.optJSONObject("streamSettings")
                
                if (streamSettings != null) {
                    val security = streamSettings.optString("security", "").takeIf { it.isNotEmpty() }
                    _streamSecurity.value = security
                    
                    // Parse SNI from tlsSettings (for TLS) or realitySettings (for Reality)
                    when (security) {
                        "tls" -> {
                            val tlsSettings = streamSettings.optJSONObject("tlsSettings")
                            val sniValue = tlsSettings?.optString("serverName", "") ?: ""
                            _sni.value = sniValue
                            
                            // Parse advanced TLS settings
                            val fingerprintValue = tlsSettings?.optString("fingerprint", "chrome") ?: "chrome"
                            _fingerprint.value = fingerprintValue
                            
                            val allowInsecureValue = tlsSettings?.optBoolean("allowInsecure", false) ?: false
                            _allowInsecure.value = allowInsecureValue
                            
                            // Parse ALPN - can be string or array
                            val alpnValue = when {
                                tlsSettings?.has("alpn") == true -> {
                                    val alpnObj = tlsSettings.opt("alpn")
                                    when {
                                        alpnObj is String -> alpnObj
                                        alpnObj is org.json.JSONArray && alpnObj.length() > 0 -> {
                                            // Convert array to comma-separated string
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
                            // Reset TLS-specific fields for Reality
                            _fingerprint.value = "chrome"
                            _alpn.value = "default"
                            _allowInsecure.value = false
                        }
                        else -> {
                            _sni.value = ""
                            // Reset TLS-specific fields
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
                
                // Parse DPI Evasion settings (sockopt and mux at outbound level)
                parseEvasionSettings(outbound)
            } else {
                _streamSecurity.value = null
                _sni.value = ""
                _fingerprint.value = "chrome"
                _alpn.value = "default"
                _allowInsecure.value = false
                // Reset evasion settings
                _enableFragment.value = false
                _fragmentLength.value = "100-200"
                _fragmentInterval.value = "10-30"
                _enableMux.value = false
                _muxConcurrency.value = 8
            }
        } catch (e: Exception) {
            // Invalid JSON or missing fields - reset state
            Log.d(TAG, "Failed to parse config fields: ${e.message}")
            _streamSecurity.value = null
            _sni.value = ""
            _fingerprint.value = "chrome"
            _alpn.value = "default"
            _allowInsecure.value = false
            // Reset evasion settings
            _enableFragment.value = false
            _fragmentLength.value = "100-200"
            _fragmentInterval.value = "10-30"
            _enableMux.value = false
            _muxConcurrency.value = 8
        }
    }

    /**
     * Parse DPI Evasion settings (fragment and mux) from outbound JSON.
     * These settings are at the outbound level, not in streamSettings.
     */
    private fun parseEvasionSettings(outbound: JSONObject) {
        try {
            // Parse sockopt for fragment settings
            val sockopt = outbound.optJSONObject("sockopt")
            if (sockopt != null) {
                val dialerProxy = sockopt.optString("dialerProxy", "")
                if (dialerProxy == "fragment") {
                    _enableFragment.value = true
                    
                    // Parse fragment settings if available
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
            
            // Parse mux settings
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
            // Reset to defaults on error
            _enableFragment.value = false
            _fragmentLength.value = "100-200"
            _fragmentInterval.value = "10-30"
            _enableMux.value = false
            _muxConcurrency.value = 8
        }
    }

    /**
     * Update SNI in config JSON and update text field.
     * Only updates if security is "tls" (not "reality").
     */
    fun updateSni(newSni: String) {
        try {
            val currentText = _configTextFieldValue.value.text
            val jsonObject = JSONObject(currentText)
            val outbounds = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
            
            if (outbounds != null && outbounds.length() > 0) {
                val outbound = outbounds.getJSONObject(0)
                var streamSettings = outbound.optJSONObject("streamSettings")
                
                if (streamSettings != null) {
                    val security = streamSettings.optString("security", "")
                    
                    // Only update SNI for TLS (not Reality)
                    if (security == "tls") {
                        var tlsSettings = streamSettings.optJSONObject("tlsSettings")
                        if (tlsSettings == null) {
                            tlsSettings = JSONObject()
                        }
                        
                        // Get server address for comparison
                        val settings = outbound.optJSONObject("settings")
                        val vnext = settings?.optJSONArray("vnext")
                        val serverAddress = vnext?.getJSONObject(0)?.optString("address", "") ?: ""
                        
                        // Update serverName, fallback to address if empty
                        val finalSni = if (newSni.isBlank()) {
                            serverAddress
                        } else {
                            newSni
                        }
                        tlsSettings.put("serverName", finalSni)
                        
                        // If SNI differs from server address, allow insecure connections
                        // This is needed when SNI is set to target domain (e.g., www.youtube.com)
                        // but connecting to a different server (e.g., stol.halibiram.online)
                        if (finalSni.isNotEmpty() && serverAddress.isNotEmpty() && 
                            finalSni != serverAddress) {
                            tlsSettings.put("allowInsecure", true)
                            Log.d(TAG, "SNI ($finalSni) differs from server address ($serverAddress), setting allowInsecure: true")
                        } else if (!tlsSettings.has("allowInsecure")) {
                            // Keep existing allowInsecure value or default to false
                            tlsSettings.put("allowInsecure", false)
                        }
                        
                        streamSettings.put("tlsSettings", tlsSettings)
                        outbound.put("streamSettings", streamSettings)
                        
                        // Update JSON array
                        if (jsonObject.has("outbounds")) {
                            jsonObject.put("outbounds", outbounds)
                        } else {
                            jsonObject.put("outbound", outbounds)
                        }
                        
                        // Update text field with formatted JSON
                        val updatedContent = jsonObject.toString(2)
                        _configTextFieldValue.value = _configTextFieldValue.value.copy(text = updatedContent)
                        _sni.value = newSni
                        _hasConfigChanged.value = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update SNI: ${e.message}", e)
            _uiEvent.trySend(
                ConfigEditUiEvent.ShowSnackbar(
                    "Failed to update SNI: ${e.message}"
                )
            )
        }
    }

    /**
     * Update fingerprint in config JSON and update text field.
     * Only updates if security is "tls".
     */
    fun updateFingerprint(newFingerprint: String) {
        updateTlsSetting("fingerprint", newFingerprint) { tlsSettings, value ->
            tlsSettings.put("fingerprint", value)
        }
        _fingerprint.value = newFingerprint
    }

    /**
     * Update ALPN in config JSON and update text field.
     * Only updates if security is "tls".
     */
    fun updateAlpn(newAlpn: String) {
        updateTlsSetting("alpn", newAlpn) { tlsSettings, value ->
            if (value == "default") {
                // Remove ALPN if set to default
                tlsSettings.remove("alpn")
            } else {
                // Convert comma-separated string to JSON array
                val alpnArray = org.json.JSONArray()
                value.split(",").forEach { item ->
                    alpnArray.put(item.trim())
                }
                tlsSettings.put("alpn", alpnArray)
            }
        }
        _alpn.value = newAlpn
    }

    /**
     * Update allowInsecure in config JSON and update text field.
     * Only updates if security is "tls".
     */
    fun updateAllowInsecure(newAllowInsecure: Boolean) {
        updateTlsSetting("allowInsecure", newAllowInsecure) { tlsSettings, value ->
            tlsSettings.put("allowInsecure", value)
        }
        _allowInsecure.value = newAllowInsecure
    }

    /**
     * Update fragment (TLS fragmentation) settings in config JSON.
     * Updates sockopt at outbound level for DPI evasion.
     */
    fun updateFragmentSettings(
        enabled: Boolean,
        length: String = "",
        interval: String = ""
    ) {
        try {
            val currentText = _configTextFieldValue.value.text
            val jsonObject = JSONObject(currentText)
            val outbounds = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
            
            if (outbounds != null && outbounds.length() > 0) {
                val outbound = outbounds.getJSONObject(0)
                
                if (enabled) {
                    // Create or update sockopt with fragment settings
                    var sockopt = outbound.optJSONObject("sockopt")
                    if (sockopt == null) {
                        sockopt = JSONObject()
                    }
                    
                    sockopt.put("dialerProxy", "fragment")
                    sockopt.put("tcpNoDelay", true)
                    
                    // Add fragment object with length and interval if provided
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
                    // Remove fragment settings from sockopt
                    val sockopt = outbound.optJSONObject("sockopt")
                    if (sockopt != null) {
                        sockopt.remove("dialerProxy")
                        sockopt.remove("fragment")
                        // Remove sockopt entirely if empty or only has tcpNoDelay
                        if (sockopt.length() == 0 || (sockopt.length() == 1 && sockopt.has("tcpNoDelay"))) {
                            outbound.remove("sockopt")
                        } else {
                            outbound.put("sockopt", sockopt)
                        }
                    }
                }
                
                // Update JSON array
                if (jsonObject.has("outbounds")) {
                    jsonObject.put("outbounds", outbounds)
                } else {
                    jsonObject.put("outbound", outbounds)
                }
                
                // Update text field with formatted JSON
                val updatedContent = jsonObject.toString(2)
                _configTextFieldValue.value = _configTextFieldValue.value.copy(text = updatedContent)
                _enableFragment.value = enabled
                if (length.isNotEmpty()) {
                    _fragmentLength.value = length
                }
                if (interval.isNotEmpty()) {
                    _fragmentInterval.value = interval
                }
                _hasConfigChanged.value = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update fragment settings: ${e.message}", e)
            _uiEvent.trySend(
                ConfigEditUiEvent.ShowSnackbar(
                    "Failed to update fragment settings: ${e.message}"
                )
            )
        }
    }

    /**
     * Update mux (multiplexing) settings in config JSON.
     * Updates mux at outbound level for connection multiplexing.
     */
    fun updateMuxSettings(enabled: Boolean, concurrency: Int = 8) {
        try {
            val currentText = _configTextFieldValue.value.text
            val jsonObject = JSONObject(currentText)
            val outbounds = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
            
            if (outbounds != null && outbounds.length() > 0) {
                val outbound = outbounds.getJSONObject(0)
                
                if (enabled) {
                    // Create or update mux settings
                    val mux = JSONObject()
                    mux.put("enabled", true)
                    mux.put("concurrency", concurrency.coerceIn(1, 1024))
                    outbound.put("mux", mux)
                } else {
                    // Remove mux settings
                    outbound.remove("mux")
                }
                
                // Update JSON array
                if (jsonObject.has("outbounds")) {
                    jsonObject.put("outbounds", outbounds)
                } else {
                    jsonObject.put("outbound", outbounds)
                }
                
                // Update text field with formatted JSON
                val updatedContent = jsonObject.toString(2)
                _configTextFieldValue.value = _configTextFieldValue.value.copy(text = updatedContent)
                _enableMux.value = enabled
                _muxConcurrency.value = concurrency.coerceIn(1, 1024)
                _hasConfigChanged.value = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update mux settings: ${e.message}", e)
            _uiEvent.trySend(
                ConfigEditUiEvent.ShowSnackbar(
                    "Failed to update mux settings: ${e.message}"
                )
            )
        }
    }

    /**
     * Helper function to update TLS settings in config JSON.
     * Only updates if security is "tls".
     */
    private fun <T> updateTlsSetting(
        settingName: String,
        newValue: T,
        updateAction: (JSONObject, T) -> Unit
    ) {
        try {
            val currentText = _configTextFieldValue.value.text
            val jsonObject = JSONObject(currentText)
            val outbounds = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
            
            if (outbounds != null && outbounds.length() > 0) {
                val outbound = outbounds.getJSONObject(0)
                var streamSettings = outbound.optJSONObject("streamSettings")
                
                if (streamSettings != null) {
                    val security = streamSettings.optString("security", "")
                    
                    // Only update for TLS (not Reality)
                    if (security == "tls") {
                        var tlsSettings = streamSettings.optJSONObject("tlsSettings")
                        if (tlsSettings == null) {
                            tlsSettings = JSONObject()
                        }
                        
                        updateAction(tlsSettings, newValue)
                        
                        streamSettings.put("tlsSettings", tlsSettings)
                        outbound.put("streamSettings", streamSettings)
                        
                        // Update JSON array
                        if (jsonObject.has("outbounds")) {
                            jsonObject.put("outbounds", outbounds)
                        } else {
                            jsonObject.put("outbound", outbounds)
                        }
                        
                        // Update text field with formatted JSON
                        val updatedContent = jsonObject.toString(2)
                        val oldText = _configTextFieldValue.value.text
                        _configTextFieldValue.value = _configTextFieldValue.value.copy(text = updatedContent)
                        _hasConfigChanged.value = true
                        
                        // Re-parse config to update state flows (only if content changed)
                        if (oldText != updatedContent) {
                            parseConfigFields(updatedContent)
                        }
                    } else {
                        Log.d(TAG, "Security is not 'tls', skipping $settingName update")
                    }
                } else {
                    Log.d(TAG, "No streamSettings found, skipping $settingName update")
                }
            } else {
                Log.d(TAG, "No outbounds found, skipping $settingName update")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update $settingName: ${e.message}", e)
            _uiEvent.trySend(
                ConfigEditUiEvent.ShowSnackbar(
                    "Failed to update $settingName: ${e.message}"
                )
            )
        }
    }

    fun onFilenameChange(newFilename: String) {
        _filename.value = newFilename
        _filenameErrorMessage.value = validateFilename(newFilename)
        _hasConfigChanged.value = true
    }

    private fun validateFilename(name: String): String? {
        return FilenameValidator.validateFilename(application, name)
    }

    fun saveConfigFile() {
        viewModelScope.launch(Dispatchers.IO) {
            val oldFilePath = _configFile.absolutePath

            var newFilename = _filename.value.trim { it <= ' ' }

            val validationError = validateFilename(newFilename)
            if (validationError != null) {
                _uiEvent.trySend(ConfigEditUiEvent.ShowSnackbar(validationError))
                return@launch
            }

            if (!newFilename.endsWith(".json")) {
                newFilename += ".json"
            }

            val parentDir = _configFile.parentFile
            if (parentDir == null) {
                Log.e(TAG, "Could not determine parent directory.")
                return@launch
            }
            val newFile = File(parentDir, newFilename)

            if (newFile.exists() && newFile.absolutePath != _configFile.absolutePath) {
                _uiEvent.trySend(
                    ConfigEditUiEvent.ShowSnackbar(
                        application.getString(R.string.filename_already_exists)
                    )
                )
                return@launch
            }

            val formattedContent: String
            try {
                formattedContent =
                    ConfigUtils.formatConfigContent(_configTextFieldValue.value.text)
            } catch (e: JSONException) {
                Log.e(TAG, "Invalid JSON format", e)
                _uiEvent.trySend(
                    ConfigEditUiEvent.ShowSnackbar(
                        application.getString(R.string.invalid_config_format)
                    )
                )
                return@launch
            }

            val success = fileManager.renameConfigFile(_configFile, newFile, formattedContent)

            if (success) {
                if (newFile.absolutePath != oldFilePath) {
                    _configFile = newFile
                    _originalFilePath = newFile.absolutePath
                }

                _uiEvent.trySend(
                    ConfigEditUiEvent.ShowSnackbar(
                        application.getString(R.string.config_save_success)
                    )
                )
                _configTextFieldValue.value =
                    _configTextFieldValue.value.copy(text = formattedContent)
                _filename.value = _configFile.nameWithoutExtension
                _hasConfigChanged.value = false
            } else {
                _uiEvent.trySend(
                    ConfigEditUiEvent.ShowSnackbar(
                        application.getString(R.string.save_fail)
                    )
                )
            }
        }
    }

    fun shareConfigFile() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!_configFile.exists()) {
                Log.e(TAG, "Config file not found.")
                _uiEvent.trySend(
                    ConfigEditUiEvent.ShowSnackbar(
                        application.getString(R.string.config_not_found)
                    )
                )
                return@launch
            }
            val content = readConfigFileContent()
            val name = _filename.value

            val input = content.toByteArray(Charsets.UTF_8)
            val outputStream = ByteArrayOutputStream()
            val deflater = Deflater()
            val buffer = ByteArray(1024)
            deflater.setInput(input)
            deflater.finish()
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                outputStream.write(buffer, 0, count)
            }
            deflater.end()
            val compressed = outputStream.toByteArray()
            val encodedContent = Base64.getUrlEncoder().encodeToString(compressed)
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val shareableLink = "hyperxray://config/$encodedName/$encodedContent"
            _uiEvent.trySend(ConfigEditUiEvent.ShareContent(shareableLink))
        }
    }

    fun handleAutoIndent(text: String, newlinePosition: Int): Pair<String, Int> {
        val prevLineStart = text.lastIndexOf('\n', newlinePosition - 1).let {
            if (it == -1) 0 else it + 1
        }
        val prevLine = text.substring(prevLineStart, newlinePosition)
        val leadingSpaces = prevLine.takeWhile { it.isWhitespace() }.length
        val additionalIndent = if (prevLine.trimEnd().let {
                it.endsWith('{') || it.endsWith('[')
            }) 2 else 0
        val shouldDedent = run {
            val nextLineStart = newlinePosition + 1
            nextLineStart < text.length &&
                    text.substring(nextLineStart).substringBefore('\n').trimStart().let {
                        it.startsWith('}') || it.startsWith(']')
                    }
        }
        val finalIndent = (
                leadingSpaces + additionalIndent - if (shouldDedent) 2 else 0
                ).coerceAtLeast(0)
        val indent = " ".repeat(finalIndent)
        val indentedText = StringBuilder(text).insert(newlinePosition + 1, indent).toString()
        return indentedText to (newlinePosition + 1 + finalIndent)
    }
}
