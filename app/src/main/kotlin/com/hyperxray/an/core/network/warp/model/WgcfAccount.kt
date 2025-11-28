package com.hyperxray.an.core.network.warp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WgcfAccount: Account model matching wgcf's Account struct.
 * 
 * Maintains exact field names as in wgcf's Go structs.
 * Based on: https://github.com/ViRb3/wgcf/blob/master/cloudflare/api.go
 */
@Serializable
data class WgcfAccount(
    val id: String? = null,
    val license: String? = null,
    
    @SerialName("account_type")
    val accountType: String? = null, // "free", "plus", "unlimited", etc.
    
    @SerialName("premium_data")
    val premiumData: Long? = null, // Premium data quota in bytes
    
    val quota: Long? = null // Alternative quota field name
)

/**
 * WgcfRegisterRequest: Registration request payload matching wgcf's RegisterRequest struct.
 * 
 * Field order and names match wgcf exactly:
 * - install_id: Empty string (wgcf sends empty string)
 * - tos: Timestamp in seconds (Unix timestamp)
 * - key: Base64-encoded Curve25519 public key
 * - fcm_token: Empty string (wgcf sends empty string)
 * - type: "Android"
 * - locale: "en_US"
 */
@Serializable
data class WgcfRegisterRequest(
    @SerialName("install_id")
    val install_id: String = "",
    
    val tos: String,
    
    val key: String,
    
    @SerialName("fcm_token")
    val fcm_token: String = "",
    
    val type: String = "Android",
    
    val locale: String = "en_US"
)

/**
 * WgcfRegistrationResponse: Response from registration endpoint.
 * 
 * Matches wgcf's RegistrationResponse struct exactly.
 */
@Serializable
data class WgcfRegistrationResponse(
    val id: String? = null, // Device ID (CRITICAL - must be stored)
    val token: String? = null, // Authentication token (CRITICAL - must be stored)
    val account: WgcfAccount? = null,
    val config: WgcfConfig? = null
)

/**
 * WgcfConfig: Configuration from registration response.
 * 
 * Contains interface addresses and peer information.
 */
@Serializable
data class WgcfConfig(
    val `interface`: WgcfInterface? = null,
    val peers: List<WgcfPeer>? = null
)

/**
 * WgcfInterface: Interface configuration.
 */
@Serializable
data class WgcfInterface(
    val addresses: WgcfAddresses? = null
)

/**
 * WgcfAddresses: Address configuration (can be object with v4/v6 or array).
 */
@Serializable
data class WgcfAddresses(
    val v4: String? = null,
    val v6: String? = null
)

/**
 * WgcfPeer: Peer configuration.
 */
@Serializable
data class WgcfPeer(
    @SerialName("public_key")
    val publicKey: String? = null,
    
    val endpoint: WgcfEndpoint? = null
)

/**
 * WgcfEndpoint: Endpoint configuration.
 */
@Serializable
data class WgcfEndpoint(
    val v4: String? = null,
    val v6: String? = null,
    val host: String? = null // Some API versions return host directly
)

/**
 * WgcfUpdateConfigRequest: Request body for PATCH /reg/{id}.
 * 
 * Used to enable/disable the device.
 */
@Serializable
data class WgcfUpdateConfigRequest(
    val active: Boolean
)

/**
 * WgcfBindLicenseRequest: Request body for PUT /reg/{id}/account.
 * 
 * Used to bind a license key to the account.
 */
@Serializable
data class WgcfBindLicenseRequest(
    val license: String
)











