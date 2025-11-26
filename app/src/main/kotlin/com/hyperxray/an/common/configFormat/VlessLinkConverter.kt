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

            // Parse enableWarp from query parameter if present
            val enableWarp = url.getQueryParameter("enableWarp")?.toBoolean() ?: false

            // Build VLESS outbound
            val vlessOutbound = mutableMapOf<String, Any>(
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
                                    // CRITICAL: If enableWarp is true, force-disable flow (Vision is incompatible with chaining)
                                    // Only add flow if explicitly provided, security is not TLS, AND enableWarp is false
                                    if (flow != null && security != "tls" && !enableWarp) {
                                        put("flow", flow)
                                    } else if (enableWarp) {
                                        // Force empty flow when WARP chaining is enabled
                                        put("flow", "")
                                    }
                                }
                            )
                        )
                    )
                ),
                "streamSettings" to streamSettingsMap
            )

            // If WARP chaining is enabled, add proxySettings to VLESS outbound
            if (enableWarp) {
                vlessOutbound["proxySettings"] = mapOf("tag" to "warp-out")
            }

            // Build outbounds list
            val outboundsList = mutableListOf<Any>(vlessOutbound)

            // If WARP chaining is enabled, add WireGuard outbound
            if (enableWarp) {
                // Get WARP defaults
                val warpPrivateKey = url.getQueryParameter("warpPrivateKey") ?: "" // User can provide via query param
                val warpEndpoint = url.getQueryParameter("warpEndpoint") ?: "engage.cloudflareclient.com:2408"
                val warpLocalAddress = url.getQueryParameter("warpLocalAddress") ?: "172.16.0.2/32"
                
                // Build WireGuard outbound
                val warpOutbound = mutableMapOf<String, Any>(
                    "tag" to "warp-out",
                    "protocol" to "wireguard",
                    "settings" to mutableMapOf<String, Any>().apply {
                        if (warpPrivateKey.isNotEmpty()) {
                            put("secretKey", warpPrivateKey)
                        } else {
                            // Use placeholder - user will need to set it via UI
                            put("secretKey", "")
                        }
                        put("mtu", 1280)
                        put("workers", 2)
                        put("address", listOf(warpLocalAddress))
                        put("peers", listOf(
                            mapOf(
                                "publicKey" to "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
                                "endpoint" to warpEndpoint,
                                "reserved" to listOf(0, 0, 0)
                            )
                        ))
                    },
                    "streamSettings" to mapOf(
                        "sockopt" to mapOf(
                            "domainStrategy" to "UseIP"
                        )
                    )
                )
                outboundsList.add(warpOutbound)
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
                    "outbounds" to outboundsList
                )
            )

            // If WARP is enabled, add routing rule to route WARP endpoint directly
            if (enableWarp) {
                val warpEndpointHost = url.getQueryParameter("warpEndpoint")?.split(":")?.firstOrNull() 
                    ?: "engage.cloudflareclient.com"
                
                // Try to resolve endpoint to IP (basic approach - full resolution happens at runtime)
                // For now, add routing rule that routes the endpoint domain directly
                val routing = JSONObject().apply {
                    put("domainStrategy", "IPIfNonMatch")
                    put("rules", JSONArray().apply {
                        // Route WARP endpoint directly (prevents handshake timeout loop)
                        if (!warpEndpointHost.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
                            // Domain name - add domain rule
                            put(JSONObject().apply {
                                put("type", "field")
                                put("domain", JSONArray().apply {
                                    put(warpEndpointHost)
                                })
                                put("outboundTag", "direct")
                            })
                        } else {
                            // IP address - add IP rule
                            put(JSONObject().apply {
                                put("type", "field")
                                put("ip", JSONArray().apply {
                                    put(warpEndpointHost)
                                })
                                put("outboundTag", "direct")
                            })
                        }
                        // Default rule: route all traffic through VLESS
                        put(JSONObject().apply {
                            put("type", "field")
                            put("network", "tcp,udp")
                            put("outboundTag", "out")
                        })
                    })
                }
                config.put("routing", routing)
                
                // Add direct outbound for WARP endpoint routing
                val directOutbound = JSONObject().apply {
                    put("protocol", "freedom")
                    put("tag", "direct")
                }
                val outbounds = config.getJSONArray("outbounds")
                outbounds.put(directOutbound)
            }
            
            Result.success(DetectedConfig(name, config.toString(2), enableWarp))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}
