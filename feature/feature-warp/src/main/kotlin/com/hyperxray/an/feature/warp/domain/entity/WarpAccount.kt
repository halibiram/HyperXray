package com.hyperxray.an.feature.warp.domain.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WARP account entity
 */
@Serializable
data class WarpAccount(
    val accountId: String,
    val token: String,
    val privateKey: String,
    val publicKey: String,
    val config: WarpConfig,
    val account: WarpAccountInfo,
    val created: String? = null
)

/**
 * WARP configuration
 */
@Serializable
data class WarpConfig(
    val clientId: String? = null,
    val peers: List<WarpPeer> = emptyList(),
    @SerialName("interface")
    val interfaceData: WarpInterface? = null,
    val services: WarpServices? = null
)

/**
 * WARP peer configuration
 */
@Serializable
data class WarpPeer(
    val publicKey: String,
    val endpoint: WarpEndpoint? = null
)

/**
 * WARP endpoint
 */
@Serializable
data class WarpEndpoint(
    val v4: String? = null,
    val v6: String? = null,
    val host: String? = null,
    val ports: List<Int> = emptyList()
)

/**
 * WARP interface configuration
 * Note: 'interface' is a reserved keyword in Kotlin, so we use @SerialName
 */
@Serializable
data class WarpInterface(
    @SerialName("addresses")
    val addresses: WarpAddresses? = null
)

/**
 * WARP addresses (IPv4 and IPv6)
 */
@Serializable
data class WarpAddresses(
    val v4: String? = null,
    val v6: String? = null
)

/**
 * WARP services
 */
@Serializable
data class WarpServices(
    val httpProxy: String? = null
)

/**
 * WARP account information
 */
@Serializable
data class WarpAccountInfo(
    val id: String? = null,
    val accountType: String? = null,
    val created: String? = null,
    val updated: String? = null,
    val premiumData: Long = 0,
    val quota: Long = 0,
    val usage: Long = 0,
    val warpPlus: Boolean = false,
    val referralCount: Int = 0,
    val referralRenewalCountdown: Int = 0,
    val role: String? = null,
    val license: String? = null,
    val ttl: String? = null
)

/**
 * WARP device information
 */
@Serializable
data class WarpDevice(
    val id: String,
    val name: String? = null,
    val type: String? = null,
    val model: String? = null,
    val created: String? = null,
    val activated: String? = null,
    val active: Boolean = false,
    val role: String? = null
)

/**
 * WARP registration result
 */
sealed class WarpRegistrationResult {
    data class Success(val account: WarpAccount) : WarpRegistrationResult()
    data class Error(val message: String, val cause: Throwable? = null) : WarpRegistrationResult()
}

/**
 * WARP configuration type for generation
 */
enum class WarpConfigType {
    WIREGUARD,
    XRAY,
    SINGBOX,
    MASQUE,
    MASQUE_TUNNEL
}

