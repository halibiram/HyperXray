package com.hyperxray.an.core.network.warp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data models for Cloudflare WARP API registration.
 */

/**
 * Registration request payload for Cloudflare WARP API.
 * Based on wgcf implementation: https://github.com/ViRb3/wgcf/blob/master/cloudflare/api.go
 * 
 * Field order matches wgcf's RegisterRequest structure:
 * FcmToken, InstallId, Key, Locale, Model, Tos, Type
 */
@Serializable
data class WarpRegistrationRequest(
    @kotlinx.serialization.SerialName("fcm_token")
    val fcm_token: String = "", // Empty string as per wgcf implementation
    
    @kotlinx.serialization.SerialName("install_id")
    val install_id: String = "", // Empty string as per wgcf implementation
    
    val key: String,
    
    val locale: String = "en_US",
    
    val model: String = "Android", // Device model field (required by API)
    
    val tos: String,
    
    val type: String = "Android"
)

/**
 * Registration response from Cloudflare WARP API.
 */
@Serializable
data class WarpRegistrationResponse(
    val id: String? = null,
    val token: String? = null, // Authentication token for subsequent API calls
    val account: WarpAccount? = null,
    val config: WarpConfig? = null
)

/**
 * Account information in registration response.
 */
@Serializable
data class WarpAccount(
    val id: String? = null,
    val license: String? = null,
    @SerialName("account_type")
    val accountType: String? = null, // "free", "plus", "unlimited", etc.
    @SerialName("premium_data")
    val premiumData: Long? = null, // Premium data quota in bytes
    val quota: Long? = null // Alternative quota field name
)

/**
 * Configuration information in registration response.
 */
@Serializable
data class WarpConfig(
    val `interface`: WarpInterface? = null,
    val peers: List<WarpPeer>? = null
)

/**
 * Interface configuration in registration response.
 */
@Serializable
data class WarpInterface(
    val addresses: WarpAddresses? = null
)

/**
 * Addresses configuration (can be object with v4/v6 or array).
 */
@Serializable
data class WarpAddresses(
    val v4: String? = null,
    val v6: String? = null
)

/**
 * Peer configuration in registration response.
 */
@Serializable
data class WarpPeer(
    val public_key: String? = null,
    val endpoint: WarpEndpoint? = null
)

/**
 * Endpoint configuration.
 */
@Serializable
data class WarpEndpoint(
    val v4: String? = null,
    val v6: String? = null
)

/**
 * Result of WARP identity generation.
 */
data class WarpIdentityResult(
    val success: Boolean,
    val privateKey: String,
    val license: String? = null,
    val localAddress: String? = null,
    val clientId: String? = null,
    val token: String? = null, // Authentication token for API calls
    val accountType: String? = null, // "free", "plus", "unlimited", etc.
    val error: String? = null
)

/**
 * Request payload for license update.
 */
@Serializable
data class WarpLicenseUpdateRequest(
    val license: String
)

/**
 * Result of WARP license update operation.
 */
data class WarpLicenseUpdateResult(
    val success: Boolean,
    val accountType: String? = null, // "free", "plus", "unlimited", etc.
    val license: String? = null,
    val quota: Long? = null, // Premium data quota in bytes (from premium_data field)
    val error: String? = null
)

/**
 * Result of WARP device registration operation.
 */
data class WarpRegistrationResult(
    val success: Boolean,
    val clientId: String? = null,
    val token: String? = null, // Authentication token for API calls
    val accountType: String? = null, // "free", "plus", "unlimited", etc.
    val localAddress: String? = null, // Local address from config (IPv4/IPv6)
    val error: String? = null
)

