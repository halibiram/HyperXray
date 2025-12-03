package com.hyperxray.an.feature.warp.data.util

import android.util.Base64
import com.hyperxray.an.feature.warp.domain.entity.WarpAccount
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * WARP public key
 */
private const val WARP_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

/**
 * Default WARP endpoint (WireGuard UDP)
 */
private const val WARP_ENDPOINT = "engage.cloudflareclient.com:2408"

/**
 * Default WARP MASQUE endpoint (HTTP/3 QUIC)
 * Cloudflare WARP uses MASQUE for HTTP/3 based tunneling
 */
private const val WARP_MASQUE_ENDPOINT = "engage.cloudflareclient.com:443"

/**
 * Alternative MASQUE endpoints for fallback
 */
private val WARP_MASQUE_ENDPOINTS = listOf(
    "engage.cloudflareclient.com:443",
    "162.159.192.1:443",
    "162.159.193.1:443",
    "162.159.195.1:443"
)

/**
 * MASQUE mode for tunneling
 */
enum class MasqueMode(val value: String) {
    CONNECT_IP("connect-ip"),
    CONNECT_UDP("connect-udp")
}

/**
 * Generator for WARP configuration files
 */
object WarpConfigGenerator {
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    /**
     * Generate WireGuard configuration
     */
    fun generateWireGuardConfig(account: WarpAccount, endpoint: String? = null): String {
        val ipv4 = account.config.interfaceData?.addresses?.v4 ?: "172.16.0.2/32"
        val ipv6 = account.config.interfaceData?.addresses?.v6 ?: "fd01:db8:1111::2/128"
        val wgEndpoint = endpoint ?: WARP_ENDPOINT
        
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${account.privateKey}")
            appendLine("Address = $ipv4")
            appendLine("Address = $ipv6")
            appendLine("DNS = 1.1.1.1")
            appendLine("MTU = 1280")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $WARP_PUBLIC_KEY")
            appendLine("AllowedIPs = 0.0.0.0/0")
            appendLine("AllowedIPs = ::/0")
            appendLine("Endpoint = $wgEndpoint")
        }
    }
    
    /**
     * Generate Xray outbound configuration
     */
    fun generateXrayConfig(account: WarpAccount, endpoint: String? = null): String {
        val ipv4 = account.config.interfaceData?.addresses?.v4 ?: "172.16.0.2/32"
        val ipv6 = account.config.interfaceData?.addresses?.v6
        val wgEndpoint = endpoint ?: WARP_ENDPOINT
        
        val addressList = mutableListOf<String>().apply {
            add(ipv4)
            ipv6?.let { add(it) }
        }
        
        val reserved = getReservedBytes(account)
        
        val xrayOutbound = buildJsonObject {
            put("tag", JsonPrimitive("warp"))
            put("protocol", JsonPrimitive("wireguard"))
            putJsonObject("settings") {
                put("secretKey", JsonPrimitive(account.privateKey))
                putJsonArray("address") {
                    addressList.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("peers") {
                    addJsonObject {
                        put("publicKey", JsonPrimitive(WARP_PUBLIC_KEY))
                        put("endpoint", JsonPrimitive(wgEndpoint))
                    }
                }
                putJsonArray("reserved") {
                    reserved.forEach { add(JsonPrimitive(it)) }
                }
                put("mtu", JsonPrimitive(1280))
                put("kernelMode", JsonPrimitive(false))
            }
        }
        
        return json.encodeToString(xrayOutbound)
    }
    
    /**
     * Generate sing-box outbound configuration
     */
    fun generateSingBoxConfig(account: WarpAccount, endpoint: String? = null): String {
        val ipv4 = account.config.interfaceData?.addresses?.v4 ?: "172.16.0.2/32"
        val ipv6 = account.config.interfaceData?.addresses?.v6
        val wgEndpoint = endpoint ?: WARP_ENDPOINT
        
        val localAddress = mutableListOf<String>().apply {
            add(ipv4.split("/")[0] + "/32")
            ipv6?.let { add(it.split("/")[0] + "/128") }
        }
        
        val (epHost, epPort) = wgEndpoint.split(":")
        
        val reserved = getReservedBytes(account)
        
        val singboxOutbound = buildJsonObject {
            put("type", JsonPrimitive("wireguard"))
            put("tag", JsonPrimitive("warp"))
            put("server", JsonPrimitive(epHost))
            put("server_port", JsonPrimitive(epPort.toInt()))
            putJsonArray("local_address") {
                localAddress.forEach { add(JsonPrimitive(it)) }
            }
            put("private_key", JsonPrimitive(account.privateKey))
            put("peer_public_key", JsonPrimitive(WARP_PUBLIC_KEY))
            putJsonArray("reserved") {
                reserved.forEach { add(JsonPrimitive(it)) }
            }
            put("mtu", JsonPrimitive(1280))
        }
        
        return json.encodeToString(singboxOutbound)
    }
    
    /**
     * Generate MASQUE configuration JSON for native layer
     * 
     * This creates a MasqueConfig JSON that can be passed to the Go native layer
     * for establishing MASQUE over Xray tunneling with WARP credentials.
     * 
     * @param account WARP account with credentials
     * @param endpoint MASQUE proxy endpoint (default: WARP MASQUE endpoint)
     * @param mode MASQUE mode (CONNECT_IP or CONNECT_UDP)
     * @param reconnectAttempts Maximum reconnection attempts
     * @param reconnectDelay Base delay for reconnection in milliseconds
     * @param queueSize Packet queue size
     * @return JSON string for MasqueConfig
     */
    fun generateMasqueConfig(
        account: WarpAccount,
        endpoint: String? = null,
        mode: MasqueMode = MasqueMode.CONNECT_IP,
        reconnectAttempts: Int = 5,
        reconnectDelay: Long = 1000,
        queueSize: Int = 256
    ): String {
        val masqueEndpoint = endpoint ?: WARP_MASQUE_ENDPOINT
        
        val masqueConfig = buildJsonObject {
            put("proxyEndpoint", JsonPrimitive(masqueEndpoint))
            put("mode", JsonPrimitive(mode.value))
            put("reconnectAttempts", JsonPrimitive(reconnectAttempts))
            put("reconnectDelay", JsonPrimitive(reconnectDelay))
            put("queueSize", JsonPrimitive(queueSize))
        }
        
        return json.encodeToString(masqueConfig)
    }
    
    /**
     * Generate full tunnel configuration for MASQUE mode
     * 
     * This creates a complete TunnelConfig JSON that includes both
     * WireGuard credentials (for key exchange) and MASQUE settings.
     * 
     * @param account WARP account with credentials
     * @param masqueEndpoint MASQUE proxy endpoint
     * @param mode MASQUE mode
     * @return JSON string for TunnelConfig with MASQUE mode
     */
    fun generateMasqueTunnelConfig(
        account: WarpAccount,
        masqueEndpoint: String? = null,
        mode: MasqueMode = MasqueMode.CONNECT_IP
    ): String {
        val ipv4 = account.config.interfaceData?.addresses?.v4 ?: "172.16.0.2/32"
        val ipv6 = account.config.interfaceData?.addresses?.v6
        val endpoint = masqueEndpoint ?: WARP_MASQUE_ENDPOINT
        
        val addressList = mutableListOf<String>().apply {
            add(ipv4)
            ipv6?.let { add(it) }
        }
        
        val reserved = getReservedBytes(account)
        
        val tunnelConfig = buildJsonObject {
            // Tunnel mode
            put("tunnelMode", JsonPrimitive("masque"))
            
            // MASQUE configuration
            putJsonObject("masqueConfig") {
                put("proxyEndpoint", JsonPrimitive(endpoint))
                put("mode", JsonPrimitive(mode.value))
                put("reconnectAttempts", JsonPrimitive(5))
                put("reconnectDelay", JsonPrimitive(1000))
                put("queueSize", JsonPrimitive(256))
            }
            
            // WireGuard credentials (still needed for WARP authentication)
            put("privateKey", JsonPrimitive(account.privateKey))
            put("publicKey", JsonPrimitive(WARP_PUBLIC_KEY))
            putJsonArray("addresses") {
                addressList.forEach { add(JsonPrimitive(it)) }
            }
            putJsonArray("reserved") {
                reserved.forEach { add(JsonPrimitive(it)) }
            }
            put("mtu", JsonPrimitive(1280))
        }
        
        return json.encodeToString(tunnelConfig)
    }
    
    /**
     * Generate Xray outbound configuration for MASQUE
     * 
     * Creates an Xray VLESS outbound that will be used to establish
     * the QUIC connection for MASQUE tunneling.
     * 
     * @param account WARP account
     * @param masqueEndpoint MASQUE endpoint
     * @return JSON string for Xray outbound
     */
    fun generateMasqueXrayOutbound(
        account: WarpAccount,
        masqueEndpoint: String? = null
    ): String {
        val endpoint = masqueEndpoint ?: WARP_MASQUE_ENDPOINT
        val (host, port) = endpoint.split(":")
        
        val xrayOutbound = buildJsonObject {
            put("tag", JsonPrimitive("warp-masque"))
            put("protocol", JsonPrimitive("vless"))
            putJsonObject("settings") {
                putJsonArray("vnext") {
                    addJsonObject {
                        put("address", JsonPrimitive(host))
                        put("port", JsonPrimitive(port.toInt()))
                        putJsonArray("users") {
                            addJsonObject {
                                // Use account ID as UUID for VLESS
                                put("id", JsonPrimitive(account.accountId))
                                put("encryption", JsonPrimitive("none"))
                            }
                        }
                    }
                }
            }
            putJsonObject("streamSettings") {
                put("network", JsonPrimitive("quic"))
                put("security", JsonPrimitive("tls"))
                putJsonObject("tlsSettings") {
                    put("serverName", JsonPrimitive(host))
                    put("alpn", buildJsonArray { add(JsonPrimitive("h3")) })
                }
                putJsonObject("quicSettings") {
                    put("security", JsonPrimitive("none"))
                    put("header", buildJsonObject { put("type", JsonPrimitive("none")) })
                }
            }
        }
        
        return json.encodeToString(xrayOutbound)
    }
    
    /**
     * Get available MASQUE endpoints for WARP
     * 
     * @return List of MASQUE endpoint addresses
     */
    fun getMasqueEndpoints(): List<String> = WARP_MASQUE_ENDPOINTS
    
    /**
     * Get default MASQUE endpoint
     * 
     * @return Default MASQUE endpoint address
     */
    fun getDefaultMasqueEndpoint(): String = WARP_MASQUE_ENDPOINT
    
    /**
     * Extract reserved bytes from account (client_id)
     */
    private fun getReservedBytes(account: WarpAccount): List<Int> {
        val clientId = account.config.clientId ?: return listOf(0, 0, 0)
        
        return try {
            val decoded = Base64.decode(clientId, Base64.NO_WRAP)
            decoded.take(3).map { it.toInt() }
        } catch (e: Exception) {
            listOf(0, 0, 0)
        }
    }
}

