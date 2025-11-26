package com.hyperxray.an.feature.warp.data.storage

import android.content.Context
import android.util.Log
import com.hyperxray.an.feature.warp.domain.entity.WarpAccount
import com.hyperxray.an.feature.warp.domain.entity.WarpAccountInfo
import com.hyperxray.an.feature.warp.domain.entity.WarpAddresses
import com.hyperxray.an.feature.warp.domain.entity.WarpConfig
import com.hyperxray.an.feature.warp.domain.entity.WarpEndpoint
import com.hyperxray.an.feature.warp.domain.entity.WarpInterface
import com.hyperxray.an.feature.warp.domain.entity.WarpPeer
import com.hyperxray.an.feature.warp.domain.entity.WarpServices
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

private const val TAG = "WarpAccountStorage"
private const val ACCOUNT_FILE_NAME = "warp-account.json"

/**
 * Storage for WARP account data
 */
class WarpAccountStorage(
    private val context: Context
) {
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        prettyPrint = true
    }
    
    private val accountFile: File
        get() = File(context.filesDir, ACCOUNT_FILE_NAME)
    
    /**
     * Save account to local storage
     */
    suspend fun saveAccount(account: WarpAccount): Result<Unit> {
        return try {
            val jsonString = json.encodeToString(
                com.hyperxray.an.feature.warp.data.model.WarpAccountStorageModel.serializer(),
                toStorageModel(account)
            )
            accountFile.writeText(jsonString)
            Log.d(TAG, "Account saved to ${accountFile.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save account", e)
            Result.failure(e)
        }
    }
    
    /**
     * Load account from local storage
     */
    suspend fun loadAccount(): Result<WarpAccount> {
        return try {
            if (!accountFile.exists()) {
                return Result.failure(IOException("Account file not found"))
            }
            
            val jsonString = accountFile.readText()
            val storageModel = json.decodeFromString<com.hyperxray.an.feature.warp.data.model.WarpAccountStorageModel>(
                jsonString
            )
            val account = fromStorageModel(storageModel)
            Log.d(TAG, "Account loaded from ${accountFile.absolutePath}")
            Result.success(account)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load account", e)
            Result.failure(e)
        }
    }
    
    /**
     * Check if account exists
     */
    fun accountExists(): Boolean {
        return accountFile.exists()
    }
    
    /**
     * Delete account file
     */
    suspend fun deleteAccount(): Result<Unit> {
        return try {
            if (accountFile.exists()) {
                accountFile.delete()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun toStorageModel(account: WarpAccount): com.hyperxray.an.feature.warp.data.model.WarpAccountStorageModel {
        return com.hyperxray.an.feature.warp.data.model.WarpAccountStorageModel(
            account_id = account.accountId,
            token = account.token,
            private_key = account.privateKey,
            public_key = account.publicKey,
            config = toStorageConfig(account.config),
            account = toStorageAccountInfo(account.account),
            created = account.created ?: com.hyperxray.an.feature.warp.data.util.WarpIdGenerator.getTimestamp()
        )
    }
    
    private fun fromStorageModel(storageModel: com.hyperxray.an.feature.warp.data.model.WarpAccountStorageModel): WarpAccount {
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
    
    private fun toStorageConfig(config: WarpConfig): com.hyperxray.an.feature.warp.data.model.WarpConfigStorageModel {
        return com.hyperxray.an.feature.warp.data.model.WarpConfigStorageModel(
            client_id = config.clientId,
            peers = config.peers.map { peer ->
                com.hyperxray.an.feature.warp.data.model.WarpPeerStorageModel(
                    public_key = peer.publicKey,
                    endpoint = peer.endpoint?.let { ep ->
                        com.hyperxray.an.feature.warp.data.model.WarpEndpointStorageModel(
                            v4 = ep.v4,
                            v6 = ep.v6,
                            host = ep.host,
                            ports = ep.ports
                        )
                    }
                )
            },
            interfaceData = config.interfaceData?.let { iface ->
                com.hyperxray.an.feature.warp.data.model.WarpInterfaceStorageModel(
                    addresses = iface.addresses?.let { addr ->
                        com.hyperxray.an.feature.warp.data.model.WarpAddressesStorageModel(
                            v4 = addr.v4,
                            v6 = addr.v6
                        )
                    }
                )
            },
            services = config.services?.let { svc ->
                com.hyperxray.an.feature.warp.data.model.WarpServicesStorageModel(
                    http_proxy = svc.httpProxy
                )
            }
        )
    }
    
    private fun fromStorageConfig(storageConfig: com.hyperxray.an.feature.warp.data.model.WarpConfigStorageModel): WarpConfig {
        return WarpConfig(
            clientId = storageConfig.client_id,
            peers = storageConfig.peers.map { peer ->
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
            interfaceData = storageConfig.interfaceData?.let { iface ->
                WarpInterface(
                    addresses = iface.addresses?.let { addr ->
                        WarpAddresses(
                            v4 = addr.v4,
                            v6 = addr.v6
                        )
                    }
                )
            },
            services = storageConfig.services?.let { svc ->
                WarpServices(
                    httpProxy = svc.http_proxy
                )
            }
        )
    }
    
    private fun toStorageAccountInfo(accountInfo: WarpAccountInfo): com.hyperxray.an.feature.warp.data.model.WarpAccountInfoStorageModel {
        return com.hyperxray.an.feature.warp.data.model.WarpAccountInfoStorageModel(
            id = accountInfo.id,
            account_type = accountInfo.accountType,
            created = accountInfo.created,
            updated = accountInfo.updated,
            premium_data = accountInfo.premiumData,
            quota = accountInfo.quota,
            usage = accountInfo.usage,
            warp_plus = accountInfo.warpPlus,
            referral_count = accountInfo.referralCount,
            referral_renewal_countdown = accountInfo.referralRenewalCountdown,
            role = accountInfo.role,
            license = accountInfo.license,
            ttl = accountInfo.ttl
        )
    }
    
    private fun fromStorageAccountInfo(storageAccountInfo: com.hyperxray.an.feature.warp.data.model.WarpAccountInfoStorageModel): WarpAccountInfo {
        return WarpAccountInfo(
            id = storageAccountInfo.id,
            accountType = storageAccountInfo.account_type,
            created = storageAccountInfo.created,
            updated = storageAccountInfo.updated,
            premiumData = storageAccountInfo.premium_data,
            quota = storageAccountInfo.quota,
            usage = storageAccountInfo.usage,
            warpPlus = storageAccountInfo.warp_plus,
            referralCount = storageAccountInfo.referral_count,
            referralRenewalCountdown = storageAccountInfo.referral_renewal_countdown,
            role = storageAccountInfo.role,
            license = storageAccountInfo.license,
            ttl = storageAccountInfo.ttl
        )
    }
}

