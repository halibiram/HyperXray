package com.hyperxray.an.common.configFormat

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.hyperxray.an.prefs.Preferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.MalformedURLException
import java.net.URL

class VlessLinkConverter: ConfigFormatConverter {
    override fun detect(content: String): Boolean {
        return content.startsWith("vless://")
    }

    override fun convert(context: Context, content: String): Result<DetectedConfig> {
        return try {
            val url = content.toUri()
            assert(url.scheme == "vless")

            val name = url.fragment ?: ("imported_vless_" + System.currentTimeMillis())
            val address = url.host ?: return Result.failure(MalformedURLException("Missing host"))
            val port = url.port.takeIf { it != -1 } ?: 443
            val id = url.userInfo ?: return Result.failure(MalformedURLException("Missing user info"))

            val type = url.getQueryParameter("type")?.let { if (it == "h2") "http" else it } ?: "tcp"
            val security = url.getQueryParameter("security") ?: "reality"
            val sni = url.getQueryParameter("sni") ?: url.getQueryParameter("peer")
            val fingerprint = url.getQueryParameter("fp") ?: "chrome"
            val flow = url.getQueryParameter("flow") // Don't set default - flow is only for XTLS, not TLS

            val realityPbk = url.getQueryParameter("pbk") ?: ""
            val realityShortId = url.getQueryParameter("sid") ?: ""
            val spiderX = url.getQueryParameter("spx") ?: "/"

            val socksPort = Preferences(context).socksPort

            // Build streamSettings based on security type
            val streamSettingsMap = mutableMapOf<String, Any>(
                "network" to type,
                "security" to security
            )

            when (security) {
                "tls" -> {
                    // TLS settings - use SNI in tlsSettings.serverName
                    val finalSni = sni ?: address // Fallback to address if SNI is empty
                    val tlsSettings = mutableMapOf<String, Any>(
                        "serverName" to finalSni
                    )
                    
                    // If SNI differs from server address, allow insecure connections
                    // This is needed when SNI is set to target domain (e.g., www.youtube.com)
                    // but connecting to a different server (e.g., stol.halibiram.online)
                    if (finalSni != address) {
                        tlsSettings["allowInsecure"] = true
                    } else {
                        tlsSettings["allowInsecure"] = false
                    }
                    
                    // Add fingerprint if present (for uTLS) - include "chrome" as it's a valid fingerprint
                    if (fingerprint.isNotEmpty()) {
                        tlsSettings["fingerprint"] = fingerprint
                    }
                    streamSettingsMap["tlsSettings"] = tlsSettings
                }
                "reality" -> {
                    // Reality settings - use SNI in realitySettings.serverName
                    streamSettingsMap["realitySettings"] = mapOf(
                        "show" to false,
                        "fingerprint" to fingerprint,
                        "serverName" to (sni ?: address),
                        "publicKey" to realityPbk,
                        "shortId" to realityShortId,
                        "spiderX" to spiderX
                    )
                }
                // For other security types (none, etc.), only network and security are set
            }

            // Build config JSON
            val config = JSONObject(
                mapOf(
                    "log" to mapOf("loglevel" to "warning"),
                    "inbounds" to listOf(
                        mapOf(
                            "port" to socksPort,
                            "listen" to "127.0.0.1",
                            "protocol" to "socks",
                            "settings" to mapOf("udp" to true)
                        )
                    ),
                    "outbounds" to listOf(
                        mapOf(
                            "protocol" to "vless",
                            "settings" to mapOf(
                                "vnext" to listOf(
                                    mapOf(
                                        "address" to address,
                                        "port" to port,
                                        "users" to listOf(
                                            mutableMapOf<String, Any>().apply {
                                                put("id", id)
                                                put("encryption", "none")
                                                // Only add flow if explicitly provided and security is not TLS
                                                // Flow is for XTLS only, not for standard TLS
                                                if (flow != null && security != "tls") {
                                                    put("flow", flow)
                                                }
                                            }
                                        )
                                    )
                                )
                            ),
                            "streamSettings" to streamSettingsMap
                        )
                    )
                )
            )

            Result.success(DetectedConfig(name, config.toString(2)))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}
