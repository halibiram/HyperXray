package com.hyperxray.an.feature.warp.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Storage model for WARP account (matches Python script format)
 */
@Serializable
data class WarpAccountStorageModel(
    val account_id: String,
    val token: String,
    val private_key: String,
    val public_key: String,
    val config: WarpConfigStorageModel,
    val account: WarpAccountInfoStorageModel,
    val created: String
)

/**
 * Storage model for WARP config
 */
@Serializable
data class WarpConfigStorageModel(
    val client_id: String? = null,
    val peers: List<WarpPeerStorageModel> = emptyList(),
    @SerialName("interface")
    val interfaceData: WarpInterfaceStorageModel? = null,
    val services: WarpServicesStorageModel? = null
)

/**
 * Storage model for WARP peer
 */
@Serializable
data class WarpPeerStorageModel(
    val public_key: String,
    val endpoint: WarpEndpointStorageModel? = null
)

/**
 * Storage model for WARP endpoint
 */
@Serializable
data class WarpEndpointStorageModel(
    val v4: String? = null,
    val v6: String? = null,
    val host: String? = null,
    val ports: List<Int> = emptyList()
)

/**
 * Storage model for WARP interface
 */
@Serializable
data class WarpInterfaceStorageModel(
    val addresses: WarpAddressesStorageModel? = null
)

/**
 * Storage model for WARP addresses
 */
@Serializable
data class WarpAddressesStorageModel(
    val v4: String? = null,
    val v6: String? = null
)

/**
 * Storage model for WARP services
 */
@Serializable
data class WarpServicesStorageModel(
    val http_proxy: String? = null
)

/**
 * Storage model for WARP account info
 */
@Serializable
data class WarpAccountInfoStorageModel(
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

