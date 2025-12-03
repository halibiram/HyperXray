package com.hyperxray.an.feature.warp.data.util

import android.util.Base64
import com.hyperxray.an.feature.warp.domain.entity.WarpAccount
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

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
     * FIX: Multiple critical issues addressed:
     * 1. UUID: Generate deterministic UUID from accountId if not already valid
     * 2. ALPN: Added h3-29 for broader server compatibility
     * 3. QUIC Security: Changed from "none" to "aes-128-gcm" for proper encryption
     * 4. Flow: Added xtls-rprx-vision for VLESS+QUIC optimization
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
        
        // FIX: Generate valid UUID from accountId
        // Xray VLESS requires a valid UUID format, not arbitrary strings
        val vlessUuid = generateVlessUuid(account.accountId)
        
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
                                // FIX: Use properly formatted UUID
                                put("id", JsonPrimitive(vlessUuid))
                                put("encryption", JsonPrimitive("none"))
                                // FIX: Add flow for VLESS+QUIC optimization
                                // xtls-rprx-vision provides better performance over QUIC
                                put("flow", JsonPrimitive("xtls-rprx-vision"))
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
                    // FIX: Added h3-29 ALPN for broader compatibility
                    // Some servers require h3-29 instead of just h3
                    put("alpn", buildJsonArray { 
                        add(JsonPrimitive("h3"))
                        add(JsonPrimitive("h3-29"))
                    })
                    // FIX: Allow insecure for WARP's internal PKI
                    put("allowInsecure", JsonPrimitive(true))
                }
                putJsonObject("quicSettings") {
                    // FIX: Changed from "none" to "aes-128-gcm"
                    // QUIC security "none" may be rejected by servers expecting encryption
                    // aes-128-gcm provides proper QUIC payload encryption
                    put("security", JsonPrimitive("aes-128-gcm"))
                    // FIX: Add key for QUIC encryption (derived from account)
                    put("key", JsonPrimitive(deriveQuicKey(account)))
                    put("header", buildJsonObject { put("type", JsonPrimitive("none")) })
                }
            }
        }
        
        return json.encodeToString(xrayOutbound)
    }
    
    /**
     * Generate a valid UUID for VLESS from an account ID
     * 
     * If the accountId is already a valid UUID, returns it as-is.
     * Otherwise, generates a deterministic UUID v5 from the accountId bytes.
     * 
     * @param accountId The WARP account ID
     * @return Valid UUID string for VLESS
     */
    private fun generateVlessUuid(accountId: String): String {
        // Check if accountId is already a valid UUID
        return try {
            UUID.fromString(accountId).toString()
        } catch (e: IllegalArgumentException) {
            // Not a valid UUID - generate deterministic UUID from bytes
            // Using UUID.nameUUIDFromBytes creates a UUID v3 (MD5-based)
            UUID.nameUUIDFromBytes(accountId.toByteArray(Charsets.UTF_8)).toString()
        }
    }
    
    /**
     * Derive QUIC encryption key from account credentials
     * 
     * Creates a deterministic key for QUIC payload encryption.
     * Uses the first 16 characters of the private key (base64).
     * 
     * @param account WARP account
     * @return Key string for QUIC encryption
     */
    private fun deriveQuicKey(account: WarpAccount): String {
        // Use a portion of the private key as QUIC encryption key
        // This ensures deterministic key derivation
        return account.privateKey.take(16)
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

