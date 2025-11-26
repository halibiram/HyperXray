package com.hyperxray.an.feature.warp.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WARP registration request
 */
@Serializable
data class WarpRegistrationRequest(
    val key: String,
    @SerialName("install_id")
    val installId: String,
    @SerialName("fcm_token")
    val fcmToken: String,
    val tos: String,
    val type: String,
    val model: String,
    val locale: String,
    @SerialName("warp_enabled")
    val warpEnabled: Boolean
)

/**
 * WARP update license request
 */
@Serializable
data class WarpUpdateLicenseRequest(
    val license: String
)

/**
 * WARP update key request
 */
@Serializable
data class WarpUpdateKeyRequest(
    val key: String
)

