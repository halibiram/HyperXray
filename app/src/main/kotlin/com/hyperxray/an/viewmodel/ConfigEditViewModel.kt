package com.hyperxray.an.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.hyperxray.an.R
import com.hyperxray.an.core.config.utils.ConfigParser
import com.hyperxray.an.common.FilenameValidator
import com.hyperxray.an.data.source.FileManager
import com.hyperxray.an.prefs.Preferences
import com.hyperxray.an.viewmodel.logic.WireGuardHandler
import com.hyperxray.an.viewmodel.logic.StreamHandler
import com.hyperxray.an.viewmodel.logic.RealityHandler
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
import kotlin.random.Random

private const val TAG = "ConfigEditViewModel"

/**
 * Country IP ranges for location spoofing.
 * Maps country codes to their major CIDR blocks.
 */
object CountryIpRanges {
    val ranges = mapOf(
        "TR" to listOf(
            "176.240.0.0/12",
            "195.175.0.0/16",
            "88.255.0.0/16",
            "185.180.0.0/15",
            "212.58.0.0/16"
        ),
        "US" to listOf(
            "104.16.0.0/12",
            "172.64.0.0/13",
            "23.200.0.0/14",
            "104.0.0.0/8",
            "172.0.0.0/8"
        ),
        "DE" to listOf(
            "46.16.0.0/12",
            "85.214.0.0/15",
            "178.63.0.0/16",
            "5.9.0.0/16",
            "188.40.0.0/16"
        ),
        "NL" to listOf(
            "188.166.0.0/16",
            "5.255.0.0/16",
            "178.62.0.0/16",
            "46.21.0.0/16",
            "94.130.0.0/15"
        ),
        "GB" to listOf(
            "51.0.0.0/8",
            "81.0.0.0/8",
            "217.0.0.0/8",
            "5.0.0.0/8"
        ),
        "FR" to listOf(
            "51.15.0.0/16",
            "163.172.0.0/15",
            "51.158.0.0/15",
            "5.196.0.0/16"
        ),
        "JP" to listOf(
            "45.32.0.0/16",
            "103.208.0.0/16",
            "45.76.0.0/16",
            "104.238.0.0/16"
        ),
        "SG" to listOf(
            "103.208.0.0/16",
            "45.32.0.0/16",
            "128.199.0.0/16"
        )
    )
    
    val countryNames = mapOf(
        "TR" to "Turkey",
        "US" to "USA",
        "DE" to "Germany",
        "NL" to "Netherlands",
        "GB" to "United Kingdom",
        "FR" to "France",
        "JP" to "Japan",
        "SG" to "Singapore"
    )
}

/**
 * Generate a random IP address from a CIDR block.
 * @param cidr CIDR notation (e.g., "192.168.1.0/24")
 * @return Random IP address within the CIDR range
 */
fun generateRandomIpFromCidr(cidr: String): String {
    val parts = cidr.split("/")
    if (parts.size != 2) {
        throw IllegalArgumentException("Invalid CIDR format: $cidr")
    }
    
    val ipParts = parts[0].split(".").map { it.toInt() }
    if (ipParts.size != 4) {
        throw IllegalArgumentException("Invalid IP format: ${parts[0]}")
    }
    
    val prefixLength = parts[1].toInt()
    if (prefixLength < 0 || prefixLength > 32) {
        throw IllegalArgumentException("Invalid prefix length: $prefixLength")
    }
    
    // Calculate network address as 32-bit integer
    val networkAddress = (ipParts[0] shl 24) or (ipParts[1] shl 16) or (ipParts[2] shl 8) or ipParts[3]
    
    // Calculate network mask
    val mask = (0xFFFFFFFF.toULong() shl (32 - prefixLength)).toLong()
    val network = networkAddress.toLong() and mask
    
    // Calculate broadcast address
    val hostBits = 32 - prefixLength
    val hostCount = (1L shl hostBits) - 1
    val broadcast = network or hostCount
    
    // Generate random IP between network and broadcast (excluding network and broadcast)
    val randomHost = Random.nextLong(1, hostCount)
    val randomIp = network or randomHost
    
    // Convert back to IP string
    val octet1 = ((randomIp shr 24) and 0xFF).toInt()
    val octet2 = ((randomIp shr 16) and 0xFF).toInt()
    val octet3 = ((randomIp shr 8) and 0xFF).toInt()
    val octet4 = (randomIp and 0xFF).toInt()
    
    return "$octet1.$octet2.$octet3.$octet4"
}

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

    // Logic handlers - delegate protocol-specific logic
    private val wireGuardHandler = WireGuardHandler()
    private val streamHandler = StreamHandler()
    private val realityHandler = RealityHandler()

    // Expose handler state flows as ViewModel state (single source of truth)
    // Stream settings
    val sni: StateFlow<String> = streamHandler.sni
    val streamSecurity: StateFlow<String?> = streamHandler.streamSecurity
    val fingerprint: StateFlow<String> = streamHandler.fingerprint
    val alpn: StateFlow<String> = streamHandler.alpn
    val allowInsecure: StateFlow<Boolean> = streamHandler.allowInsecure
    val enableFragment: StateFlow<Boolean> = streamHandler.enableFragment
    val fragmentLength: StateFlow<String> = streamHandler.fragmentLength
    val fragmentInterval: StateFlow<String> = streamHandler.fragmentInterval
    val enableMux: StateFlow<Boolean> = streamHandler.enableMux
    val muxConcurrency: StateFlow<Int> = streamHandler.muxConcurrency

    // WARP (WireGuard) settings
    val enableWarp: StateFlow<Boolean> = wireGuardHandler.enableWarp
    val warpPrivateKey: StateFlow<String> = wireGuardHandler.warpPrivateKey
    val warpPeerPublicKey: StateFlow<String> = wireGuardHandler.warpPeerPublicKey
    val warpEndpoint: StateFlow<String> = wireGuardHandler.warpEndpoint
    val warpLocalAddress: StateFlow<String> = wireGuardHandler.warpLocalAddress
    val warpClientId: StateFlow<String?> = wireGuardHandler.warpClientId
    val warpLicenseKey: StateFlow<String> = wireGuardHandler.warpLicenseKey
    val warpAccountType: StateFlow<String?> = wireGuardHandler.warpAccountType
    val warpQuota: StateFlow<String> = wireGuardHandler.warpQuota
    val isBindingLicense: StateFlow<Boolean> = wireGuardHandler.isBindingLicense

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
     * Delegates to handlers for protocol-specific parsing.
     */
    private fun parseConfigFields(configContent: String) {
        try {
            val jsonObject = JSONObject(configContent)
            val outbounds = jsonObject.optJSONArray("outbounds") ?: jsonObject.optJSONArray("outbound")
            
            if (outbounds != null && outbounds.length() > 0) {
                val outbound = outbounds.getJSONObject(0)
                
                // Delegate stream settings parsing to StreamHandler
                streamHandler.parseStreamSettings(outbound)
                
                // Parse Reality settings if present
                val streamSettings = outbound.optJSONObject("streamSettings")
                if (streamSettings != null && streamSettings.optString("security") == "reality") {
                    realityHandler.parseRealitySettings(streamSettings)
                }
                
                // Delegate WARP settings parsing to WireGuardHandler
                wireGuardHandler.parseWarpSettings(jsonObject)
            } else {
                // Reset all handlers
                streamHandler.reset()
                realityHandler.reset()
                wireGuardHandler.reset()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse config fields: ${e.message}")
            streamHandler.reset()
            realityHandler.reset()
            wireGuardHandler.reset()
        }
    }
    

    /**
     * Update SNI in config JSON and update text field.
     * Delegates to StreamHandler.
     */
    fun updateSni(newSni: String) {
        try {
            val currentText = _configTextFieldValue.value.text
            val jsonObject = JSONObject(currentText)
            
            streamHandler.updateSni(jsonObject, newSni)
                .onSuccess { updatedContent ->
                    _configTextFieldValue.value = _configTextFieldValue.value.copy(text = updatedContent)
                    _hasConfigChanged.value = true
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to update SNI: ${error.message}", error)
                    _uiEvent.trySend(
                        ConfigEditUiEvent.ShowSnackbar(
                            "Failed to update SNI: ${error.message}"
                        )
                    )
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
     * Delegates to StreamHandler.
     */
    fun updateFingerprint(newFingerprint: String) {
        try {
            val currentText = _configTextFieldValue.value.text
            val jsonObject = JSONObject(currentText)
            
            streamHandler.updateFingerprint(jsonObject, newFingerprint)
                .onSuccess { updatedContent ->
                    _configTextFieldValue.value = _configTextFieldValue.value.copy(text = updatedContent)
                    _hasConfigChanged.value = true
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to update fingerprint: ${error.message}", error)
                    _uiEvent.trySend(
                        ConfigEditUiEvent.ShowSnackbar(
                            "Failed to update fingerprint: ${error.message}"
                        )
                    )
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update fingerprint: ${e.message}", e)
            _uiEvent.trySend(
                ConfigEditUiEvent.ShowSnackbar(
                    "Failed to update fingerprint: ${e.message}"
                )
            )
        }
    }

    /**
     * Update ALPN in config JSON and update text field.
     * Delegates to StreamHandler.
     */
    fun updateAlpn(newAlpn: String) {
        try {
            val currentText = _configTextFieldValue.value.text
            val jsonObject = JSONObject(currentText)
            
            streamHandler.updateAlpn(jsonObject, newAlpn)
                .onSuccess { updatedContent ->
                    _configTextFieldValue.value = _configTextFieldValue.value.copy(text = updatedContent)
                    _hasConfigChanged.value = true
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to update ALPN: ${error.message}", error)
                    _uiEvent.trySend(
                        ConfigEditUiEvent.ShowSnackbar(
                            "Failed to update ALPN: ${error.message}"
                        )
                    )
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update ALPN: ${e.message}", e)
            _uiEvent.trySend(
                ConfigEditUiEvent.ShowSnackbar(
                    "Failed to update ALPN: ${e.message}"
                )
            )
        }
    }

    /**
     * Update allowInsecure in config JSON and update text field.
     * Delegates to StreamHandler.
     */
    fun updateAllowInsecure(newAllowInsecure: Boolean) {
        try {
            val currentText = _configTextFieldValue.value.text
            val jsonObject = JSONObject(currentText)
            
            streamHandler.updateAllowInsecure(jsonObject, newAllowInsecure)
                .onSuccess { updatedContent ->
                    _configTextFieldValue.value = _configTextFieldValue.value.copy(text = updatedContent)
                    _hasConfigChanged.value = true
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to update allowInsecure: ${error.message}", error)
                    _uiEvent.trySend(
                        ConfigEditUiEvent.ShowSnackbar(
                            "Failed to update allowInsecure: ${error.message}"
                        )
                    )
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update allowInsecure: ${e.message}", e)
            _uiEvent.trySend(
                ConfigEditUiEvent.ShowSnackbar(
                    "Failed to update allowInsecure: ${e.message}"
                )
            )
        }
    }

    /**
     * Update fragment (TLS fragmentation) settings in config JSON.
     * Delegates to StreamHandler.
     */
    fun updateFragmentSettings(
        enabled: Boolean,
        length: String = "",
        interval: String = ""
    ) {
        try {
            val currentText = _configTextFieldValue.value.text
            val jsonObject = JSONObject(currentText)
            
            streamHandler.updateFragmentSettings(jsonObject, enabled, length, interval)
                .onSuccess { updatedContent ->
                    _configTextFieldValue.value = _configTextFieldValue.value.copy(text = updatedContent)
                    _hasConfigChanged.value = true
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to update fragment settings: ${error.message}", error)
                    _uiEvent.trySend(
                        ConfigEditUiEvent.ShowSnackbar(
                            "Failed to update fragment settings: ${error.message}"
                        )
                    )
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
     * Delegates to StreamHandler.
     */
    fun updateMuxSettings(enabled: Boolean, concurrency: Int = 8) {
        try {
            val currentText = _configTextFieldValue.value.text
            val jsonObject = JSONObject(currentText)
            
            streamHandler.updateMuxSettings(jsonObject, enabled, concurrency)
                .onSuccess { updatedContent ->
                    _configTextFieldValue.value = _configTextFieldValue.value.copy(text = updatedContent)
                    _hasConfigChanged.value = true
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to update mux settings: ${error.message}", error)
                    _uiEvent.trySend(
                        ConfigEditUiEvent.ShowSnackbar(
                            "Failed to update mux settings: ${error.message}"
                        )
                    )
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
                    ConfigParser.formatConfigContent(_configTextFieldValue.value.text)
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

    /**
     * Update WireGuard over Xray settings in config JSON.
     * Delegates to WireGuardHandler.
     */
    fun updateWarpSettings(
        enabled: Boolean,
        privateKey: String = "",
        endpoint: String = "",
        localAddress: String = ""
    ) {
        try {
            val currentText = _configTextFieldValue.value.text
            val jsonObject = JSONObject(currentText)
            
            // Use handler state flows for defaults if not provided
            val finalEndpoint = endpoint.ifEmpty { wireGuardHandler.warpEndpoint.value }
            val finalLocalAddress = localAddress.ifEmpty { wireGuardHandler.warpLocalAddress.value }
            
            wireGuardHandler.updateWarpSettings(
                configJson = jsonObject,
                enabled = enabled,
                privateKey = privateKey,
                endpoint = finalEndpoint,
                localAddress = finalLocalAddress
            ).onSuccess { updatedContent ->
                _configTextFieldValue.value = _configTextFieldValue.value.copy(text = updatedContent)
                _hasConfigChanged.value = true
                
                if (enabled) {
                    _uiEvent.trySend(
                        ConfigEditUiEvent.ShowSnackbar(
                            "WireGuard over Xray configuration updated. If WireGuard doesn't work, check:\n" +
                            "1. Xray-core version supports WireGuard (v1.8.0+)\n" +
                            "2. Config file has 'warp-out' outbound with 'wireguard' protocol\n" +
                            "3. Routing rules are configured to use 'warp-out' outbound"
                        )
                    )
                }
            }.onFailure { error ->
                val errorMessage = when {
                    error is IllegalArgumentException -> "WARP configuration error: ${error.message}"
                    error is IllegalStateException -> "WARP key generation error: ${error.message}"
                    else -> "Failed to update WARP settings: ${error.message}"
                }
                Log.e(TAG, errorMessage, error)
                _uiEvent.trySend(
                    ConfigEditUiEvent.ShowSnackbar(errorMessage)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WARP settings: ${e.message}", e)
            _uiEvent.trySend(
                ConfigEditUiEvent.ShowSnackbar("Failed to update WARP settings: ${e.message}")
            )
        }
    }
    
    /**
     * Generate new WARP keys and update config.
     * Delegates to WireGuardHandler.
     */
    fun generateWarpKeys() {
        val (privateKey, publicKey) = wireGuardHandler.generateWarpKeys()
        if (privateKey.isNotEmpty()) {
            updateWarpSettings(
                enabled = true,
                privateKey = privateKey,
                endpoint = wireGuardHandler.warpEndpoint.value,
                localAddress = wireGuardHandler.warpLocalAddress.value
            )
            _uiEvent.trySend(
                ConfigEditUiEvent.ShowSnackbar("WARP keys generated successfully")
            )
        } else {
            _uiEvent.trySend(
                ConfigEditUiEvent.ShowSnackbar("Failed to generate WARP keys")
            )
        }
    }
    
    /**
     * Generate WARP identity via Cloudflare API registration.
     * Delegates to WireGuardHandler.
     */
    fun generateWarpIdentity() {
        viewModelScope.launch {
            wireGuardHandler.generateWarpIdentity()
                .onSuccess { result ->
                    val localAddress = result.localAddress.ifEmpty { wireGuardHandler.warpLocalAddress.value }
                    
                    updateWarpSettings(
                        enabled = true,
                        privateKey = result.privateKey,
                        endpoint = wireGuardHandler.warpEndpoint.value,
                        localAddress = localAddress
                    )
                    
                    val message = buildString {
                        append("WARP identity registered successfully")
                        if (result.license != null) {
                            append("\nLicense: ${result.license.take(20)}...")
                        }
                        if (result.localAddress.isNotEmpty()) {
                            append("\nAddress: ${result.localAddress}")
                        }
                        if (result.accountType != null) {
                            append("\nAccount Type: ${result.accountType.uppercase()}")
                        }
                    }
                    
                    _uiEvent.trySend(
                        ConfigEditUiEvent.ShowSnackbar(message)
                    )
                }
                .onFailure { error ->
                    Log.e(TAG, "WARP identity generation failed: ${error.message}", error)
                    _uiEvent.trySend(
                        ConfigEditUiEvent.ShowSnackbar("Registration Failed: ${error.message}")
                    )
                }
        }
    }
    
    /**
     * Create a free WARP account identity (one-click registration).
     * Generates keys, registers with Cloudflare API, and auto-populates config fields.
     * Delegates to WireGuardHandler.
     */
    fun createFreeIdentity() {
        viewModelScope.launch {
            wireGuardHandler.createFreeIdentity()
                .onSuccess { result ->
                    val localAddress = result.localAddress.ifEmpty { wireGuardHandler.warpLocalAddress.value }
                    
                    // Auto-populate WireGuard config fields
                    updateWarpSettings(
                        enabled = true,
                        privateKey = result.privateKey,
                        endpoint = wireGuardHandler.warpEndpoint.value,
                        localAddress = localAddress
                    )
                    
                    val message = buildString {
                        append("✅ Free WARP account created successfully!")
                        append("\nAccount Type: ${result.accountType?.uppercase() ?: "FREE"}")
                        append("\nQuota: ${wireGuardHandler.warpQuota.value}")
                        if (result.localAddress.isNotEmpty()) {
                            append("\nAddress: ${result.localAddress}")
                        }
                    }
                    
                    _uiEvent.trySend(
                        ConfigEditUiEvent.ShowSnackbar(message)
                    )
                }
                .onFailure { error ->
                    Log.e(TAG, "Free WARP account creation failed: ${error.message}", error)
                    _uiEvent.trySend(
                        ConfigEditUiEvent.ShowSnackbar("Free Account Creation Failed: ${error.message}")
                    )
                }
        }
    }
    
    /**
     * Update WARP license key input.
     * Delegates to WireGuardHandler.
     */
    fun updateLicenseKeyInput(licenseKey: String) {
        wireGuardHandler.updateLicenseKeyInput(licenseKey)
    }
    
    /**
     * Bind WARP+ license key to existing account.
     * Delegates to WireGuardHandler.
     */
    fun bindLicenseKey() {
        viewModelScope.launch {
            wireGuardHandler.bindLicenseKey()
                .onSuccess { result ->
                    if (result.privateKey != null) {
                        updateWarpSettings(
                            enabled = true,
                            privateKey = result.privateKey,
                            endpoint = wireGuardHandler.warpEndpoint.value,
                            localAddress = wireGuardHandler.warpLocalAddress.value
                        )
                    }
                    
                    val accountTypeDisplay = when {
                        result.accountType?.equals("plus", ignoreCase = true) == true -> "WARP+"
                        result.accountType?.equals("unlimited", ignoreCase = true) == true -> "WARP Unlimited"
                        result.accountType?.equals("premium", ignoreCase = true) == true -> "WARP Premium"
                        else -> result.accountType?.uppercase() ?: "FREE"
                    }
                    
                    val message = buildString {
                        append("✅ License key bound successfully!")
                        append("\nAccount Type: $accountTypeDisplay")
                        if (result.accountType?.equals("free", ignoreCase = true) == true) {
                            append("\n⚠️ Account is still FREE. Please check your license key.")
                        }
                    }
                    
                    _uiEvent.trySend(
                        ConfigEditUiEvent.ShowSnackbar(message)
                    )
                }
                .onFailure { error ->
                    Log.e(TAG, "License binding failed: ${error.message}", error)
                    _uiEvent.trySend(
                        ConfigEditUiEvent.ShowSnackbar("License Binding Failed: ${error.message}")
                    )
                }
        }
    }
    
    /**
     * Update WARP private key StateFlow without updating config.
     * Delegates to WireGuardHandler.
     */
    fun setWarpPrivateKey(key: String) {
        wireGuardHandler.setWarpPrivateKey(key)
    }

    /**
     * Inject a random client IP from the specified country into the DNS configuration.
     * @param countryCode Two-letter country code (e.g., "TR", "US", "DE")
     */
    fun injectClientIp(countryCode: String) {
        try {
            val countryRanges = CountryIpRanges.ranges[countryCode]
            if (countryRanges == null || countryRanges.isEmpty()) {
                _uiEvent.trySend(
                    ConfigEditUiEvent.ShowSnackbar(
                        "Country code '$countryCode' not supported"
                    )
                )
                return
            }
            
            // Pick a random CIDR from the country's ranges
            val randomCidr = countryRanges.random()
            
            // Generate random IP from the CIDR
            val generatedIp = generateRandomIpFromCidr(randomCidr)
            
            // Parse current JSON content
            val currentText = _configTextFieldValue.value.text
            val jsonObject = JSONObject(currentText)
            
            // Get or create DNS object
            var dnsObject = jsonObject.optJSONObject("dns")
            if (dnsObject == null) {
                dnsObject = JSONObject()
                jsonObject.put("dns", dnsObject)
            }
            
            // Update clientIp field
            dnsObject.put("clientIp", generatedIp)
            
            // Format and update config content
            val formattedContent = ConfigParser.formatConfigContent(jsonObject.toString())
            _configTextFieldValue.value = _configTextFieldValue.value.copy(text = formattedContent)
            _hasConfigChanged.value = true
            
            // Show success message
            val countryName = CountryIpRanges.countryNames[countryCode] ?: countryCode
            _uiEvent.trySend(
                ConfigEditUiEvent.ShowSnackbar(
                    "Added fake client IP: $generatedIp for $countryName"
                )
            )
        } catch (e: JSONException) {
            Log.e(TAG, "Invalid JSON format", e)
            _uiEvent.trySend(
                ConfigEditUiEvent.ShowSnackbar(
                    "Failed to inject client IP: Invalid JSON format"
                )
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid CIDR or IP format", e)
            _uiEvent.trySend(
                ConfigEditUiEvent.ShowSnackbar(
                    "Failed to generate IP: ${e.message}"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject client IP", e)
            _uiEvent.trySend(
                ConfigEditUiEvent.ShowSnackbar(
                    "Failed to inject client IP: ${e.message}"
                )
            )
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
