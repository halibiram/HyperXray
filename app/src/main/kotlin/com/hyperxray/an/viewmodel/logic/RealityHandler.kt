package com.hyperxray.an.viewmodel.logic

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

private const val TAG = "RealityHandler"

/**
 * Handler for Reality protocol logic.
 * Manages public/private key generation (X25519), shortId generation,
 * and SpiderX/ServerName validation.
 */
class RealityHandler {
    // Reality settings state
    private val _realityPublicKey = MutableStateFlow<String>("")
    val realityPublicKey: StateFlow<String> = _realityPublicKey.asStateFlow()

    private val _realityPrivateKey = MutableStateFlow<String>("")
    val realityPrivateKey: StateFlow<String> = _realityPrivateKey.asStateFlow()

    private val _realityShortId = MutableStateFlow<String>("")
    val realityShortId: StateFlow<String> = _realityShortId.asStateFlow()

    private val _realityServerName = MutableStateFlow<String>("")
    val realityServerName: StateFlow<String> = _realityServerName.asStateFlow()

    private val _realitySpiderX = MutableStateFlow<String>("")
    val realitySpiderX: StateFlow<String> = _realitySpiderX.asStateFlow()

    /**
     * Parse Reality settings from config JSON.
     */
    fun parseRealitySettings(streamSettings: JSONObject) {
        try {
            val realitySettings = streamSettings.optJSONObject("realitySettings")
            if (realitySettings != null) {
                val publicKey = realitySettings.optString("publicKey", "")
                val privateKey = realitySettings.optString("privateKey", "")
                val shortId = realitySettings.optString("shortId", "")
                val serverName = realitySettings.optString("serverName", "")
                val spiderX = realitySettings.optString("spiderX", "")

                _realityPublicKey.value = publicKey
                _realityPrivateKey.value = privateKey
                _realityShortId.value = shortId
                _realityServerName.value = serverName
                _realitySpiderX.value = spiderX
            } else {
                reset()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse Reality settings: ${e.message}")
            reset()
        }
    }

    /**
     * Generate X25519 key pair for Reality.
     * Returns (publicKey, privateKey) as base64 strings.
     */
    fun generateRealityKeys(): Pair<String, String> {
        // TODO: Implement X25519 key generation
        // This should use a cryptographic library to generate X25519 key pairs
        // For now, return empty strings as placeholder
        Log.w(TAG, "Reality key generation not yet implemented")
        return Pair("", "")
    }

    /**
     * Generate short ID for Reality.
     * Short ID is typically 8 characters (hex encoded).
     */
    fun generateShortId(): String {
        // Generate random 4-byte value and encode as hex (8 characters)
        val bytes = ByteArray(4)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Validate Reality server name.
     */
    fun validateServerName(serverName: String): Boolean {
        // Server name should be a valid domain or IP
        return serverName.isNotEmpty() && (
            serverName.matches(Regex("^[a-zA-Z0-9.-]+$")) ||
            serverName.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))
        )
    }

    /**
     * Validate SpiderX parameter.
     */
    fun validateSpiderX(spiderX: String): Boolean {
        // SpiderX is optional, if provided should be a valid string
        return spiderX.isEmpty() || spiderX.matches(Regex("^[a-zA-Z0-9+/=]+$"))
    }

    /**
     * Update Reality settings in config JSON.
     */
    fun updateRealitySettings(
        configJson: JSONObject,
        publicKey: String? = null,
        privateKey: String? = null,
        shortId: String? = null,
        serverName: String? = null,
        spiderX: String? = null
    ): Result<String> {
        return try {
            val outbounds = configJson.optJSONArray("outbounds") ?: configJson.optJSONArray("outbound")

            if (outbounds != null && outbounds.length() > 0) {
                val outbound = outbounds.getJSONObject(0)
                var streamSettings = outbound.optJSONObject("streamSettings")

                if (streamSettings == null) {
                    streamSettings = JSONObject()
                    streamSettings.put("security", "reality")
                }

                var realitySettings = streamSettings.optJSONObject("realitySettings")
                if (realitySettings == null) {
                    realitySettings = JSONObject()
                }

                publicKey?.let { realitySettings.put("publicKey", it) }
                privateKey?.let { realitySettings.put("privateKey", it) }
                shortId?.let { realitySettings.put("shortId", it) }
                serverName?.let { realitySettings.put("serverName", it) }
                spiderX?.let { realitySettings.put("spiderX", it) }

                streamSettings.put("realitySettings", realitySettings)
                streamSettings.put("security", "reality")
                outbound.put("streamSettings", streamSettings)

                if (configJson.has("outbounds")) {
                    configJson.put("outbounds", outbounds)
                } else {
                    configJson.put("outbound", outbounds)
                }

                // Update state
                publicKey?.let { _realityPublicKey.value = it }
                privateKey?.let { _realityPrivateKey.value = it }
                shortId?.let { _realityShortId.value = it }
                serverName?.let { _realityServerName.value = it }
                spiderX?.let { _realitySpiderX.value = it }

                Result.success(configJson.toString(2))
            } else {
                Result.failure(IllegalStateException("No outbounds found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Reality settings: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Reset Reality settings to defaults.
     */
    fun reset() {
        _realityPublicKey.value = ""
        _realityPrivateKey.value = ""
        _realityShortId.value = ""
        _realityServerName.value = ""
        _realitySpiderX.value = ""
    }
}

