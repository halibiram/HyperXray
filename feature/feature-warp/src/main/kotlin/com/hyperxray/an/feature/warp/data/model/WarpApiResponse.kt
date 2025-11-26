package com.hyperxray.an.feature.warp.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WARP API response model
 */
@Serializable
data class WarpApiResponse(
    val id: String? = null,
    val token: String? = null,
    val config: WarpConfigResponse? = null,
    val account: WarpAccountInfoResponse? = null,
    // Internal fields (not from API)
    var privateKey: String? = null,
    var publicKey: String? = null
)

/**
 * WARP config response
 */
@Serializable
data class WarpConfigResponse(
    val client_id: String? = null,
    val peers: List<WarpPeerResponse>? = null,
    @SerialName("interface")
    val interfaceData: WarpInterfaceResponse? = null,
    val services: WarpServicesResponse? = null
)

/**
 * WARP peer response
 */
@Serializable
data class WarpPeerResponse(
    val public_key: String? = null,
    val endpoint: WarpEndpointResponse? = null
)

/**
 * WARP endpoint response
 */
@Serializable
data class WarpEndpointResponse(
    val v4: String? = null,
    val v6: String? = null,
    val host: String? = null,
    val ports: List<Int>? = null
)

/**
 * WARP interface response
 * Note: 'interface' is a reserved keyword in Kotlin, so we use @SerialName
 */
@Serializable
data class WarpInterfaceResponse(
    val addresses: WarpAddressesResponse? = null
)

/**
 * WARP addresses response
 */
@Serializable
data class WarpAddressesResponse(
    val v4: String? = null,
    val v6: String? = null
)

/**
 * WARP services response
 */
@Serializable
data class WarpServicesResponse(
    val http_proxy: String? = null
)

/**
 * WARP account info response
 */
@Serializable
data class WarpAccountInfoResponse(
    val id: String? = null,
    val account_type: String? = null,
    val created: String? = null,
    val updated: String? = null,
    val premium_data: Long = 0,
    val quota: Long = 0,
    val usage: Long = 0,
    val warp_plus: Boolean = false,
    val referral_count: Int = 0,
    val referral_renewal_countdown: Int = 0,
    val role: String? = null,
    val license: String? = null,
    val ttl: String? = null
)

/**
 * WARP device response
 */
@Serializable
data class WarpDeviceResponse(
    val id: String,
    val name: String? = null,
    val type: String? = null,
    val model: String? = null,
    val created: String? = null,
    val activated: String? = null,
    val active: Boolean = false,
    val role: String? = null
)

