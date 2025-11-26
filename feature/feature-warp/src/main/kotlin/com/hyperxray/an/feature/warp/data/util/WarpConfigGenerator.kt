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
 * Default WARP endpoint
 */
private const val WARP_ENDPOINT = "engage.cloudflareclient.com:2408"

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

