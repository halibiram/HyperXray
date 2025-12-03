package com.hyperxray.an.feature.warp.data.mapper

import com.hyperxray.an.feature.warp.data.model.WarpAccountStorageModel
import com.hyperxray.an.feature.warp.data.model.WarpApiResponse
import com.hyperxray.an.feature.warp.data.model.WarpDeviceResponse
import com.hyperxray.an.feature.warp.domain.entity.WarpAccount
import com.hyperxray.an.feature.warp.domain.entity.WarpAccountInfo
import com.hyperxray.an.feature.warp.domain.entity.WarpAddresses
import com.hyperxray.an.feature.warp.domain.entity.WarpConfig
import com.hyperxray.an.feature.warp.domain.entity.WarpDevice
import com.hyperxray.an.feature.warp.domain.entity.WarpEndpoint
import com.hyperxray.an.feature.warp.domain.entity.WarpInterface
import com.hyperxray.an.feature.warp.domain.entity.WarpPeer
import com.hyperxray.an.feature.warp.domain.entity.WarpServices

/**
 * Mapper for converting API responses to domain entities
 */
object WarpMapper {
    
    /**
     * Map API response to domain entity
     */
    fun toWarpAccount(apiResponse: WarpApiResponse): WarpAccount {
        return WarpAccount(
            accountId = apiResponse.id ?: "",
            token = apiResponse.token ?: "",
            privateKey = apiResponse.privateKey ?: "",
            publicKey = apiResponse.publicKey ?: "",
            config = toWarpConfig(apiResponse.config),
            account = toWarpAccountInfo(apiResponse.account),
            created = apiResponse.account?.created
        )
    }
    
    /**
     * Map config response to domain entity
     */
    private fun toWarpConfig(config: com.hyperxray.an.feature.warp.data.model.WarpConfigResponse?): WarpConfig {
        if (config == null) {
            return WarpConfig()
        }
        
        return WarpConfig(
            clientId = config.client_id,
            peers = config.peers?.map { peer ->
                WarpPeer(
                    publicKey = peer.public_key ?: "",
                    endpoint = peer.endpoint?.let { ep ->
                        WarpEndpoint(
                            v4 = ep.v4,
                            v6 = ep.v6,
                            host = ep.host,
                            ports = ep.ports ?: emptyList()
                        )
                    }
                )
            } ?: emptyList(),
            interfaceData = config.interfaceData?.let { iface ->
                WarpInterface(
                    addresses = iface.addresses?.let { addr ->
                        WarpAddresses(
                            v4 = addr.v4,
                            v6 = addr.v6
                        )
                    }
                )
            },
            services = config.services?.let { svc ->
                WarpServices(
                    httpProxy = svc.http_proxy
                )
            }
        )
    }
    
    /**
     * Map account info response to domain entity
     */
    private fun toWarpAccountInfo(accountInfo: com.hyperxray.an.feature.warp.data.model.WarpAccountInfoResponse?): WarpAccountInfo {
        if (accountInfo == null) {
            return WarpAccountInfo()
        }
        
        return WarpAccountInfo(
            id = accountInfo.id,
            accountType = accountInfo.account_type,
            created = accountInfo.created,
            updated = accountInfo.updated,
            premiumData = accountInfo.premium_data,
            quota = accountInfo.quota,
            usage = accountInfo.usage,
            warpPlus = accountInfo.warp_plus,
            referralCount = accountInfo.referral_count,
            referralRenewalCountdown = accountInfo.referral_renewal_countdown,
            role = accountInfo.role,
            license = accountInfo.license,
            ttl = accountInfo.ttl
        )
    }
    
    /**
     * Map device response to domain entity
     */
    fun toWarpDevice(deviceResponse: WarpDeviceResponse): WarpDevice {
        return WarpDevice(
            id = deviceResponse.id,
            name = deviceResponse.name,
            type = deviceResponse.type,
            model = deviceResponse.model,
            created = deviceResponse.created,
            activated = deviceResponse.activated,
            active = deviceResponse.active,
            role = deviceResponse.role
        )
    }
    
    /**
     * Map storage model to domain entity (for import)
     */
    fun fromStorageModel(storageModel: WarpAccountStorageModel): WarpAccount {
        return WarpAccount(
            accountId = storageModel.account_id,
            token = storageModel.token,
            privateKey = storageModel.private_key,
            publicKey = storageModel.public_key,
            config = fromStorageConfig(storageModel.config),
            account = fromStorageAccountInfo(storageModel.account),
            created = storageModel.created
        )
    }
    
    private fun fromStorageConfig(config: com.hyperxray.an.feature.warp.data.model.WarpConfigStorageModel): WarpConfig {
        return WarpConfig(
            clientId = config.client_id,
            peers = config.peers.map { peer ->
                WarpPeer(
                    publicKey = peer.public_key,
                    endpoint = peer.endpoint?.let { ep ->
                        WarpEndpoint(
                            v4 = ep.v4,
                            v6 = ep.v6,
                            host = ep.host,
                            ports = ep.ports
                        )
                    }
                )
            },
            interfaceData = config.interfaceData?.let { iface ->
                WarpInterface(
                    addresses = iface.addresses?.let { addr ->
                        WarpAddresses(
                            v4 = addr.v4,
                            v6 = addr.v6
                        )
                    }
                )
            },
            services = config.services?.let { svc ->
                WarpServices(
                    httpProxy = svc.http_proxy
                )
            }
        )
    }
    
    private fun fromStorageAccountInfo(info: com.hyperxray.an.feature.warp.data.model.WarpAccountInfoStorageModel): WarpAccountInfo {
        return WarpAccountInfo(
            id = info.id,
            accountType = info.account_type,
            created = info.created,
            updated = info.updated,
            premiumData = info.premium_data,
            quota = info.quota,
            usage = info.usage,
            warpPlus = info.warp_plus,
            referralCount = info.referral_count,
            referralRenewalCountdown = info.referral_renewal_countdown,
            role = info.role,
            license = info.license,
            ttl = info.ttl
        )
    }
}

