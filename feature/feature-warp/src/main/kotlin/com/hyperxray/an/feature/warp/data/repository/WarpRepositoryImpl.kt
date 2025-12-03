package com.hyperxray.an.feature.warp.data.repository

import android.content.Context
import android.util.Log
import com.hyperxray.an.feature.warp.data.datasource.WarpApiDataSource
import com.hyperxray.an.feature.warp.data.mapper.WarpMapper
import com.hyperxray.an.feature.warp.data.model.WarpAccountStorageModel
import com.hyperxray.an.feature.warp.data.storage.WarpAccountStorage
import com.hyperxray.an.feature.warp.data.util.WarpConfigGenerator
import com.hyperxray.an.feature.warp.domain.entity.WarpAccount
import com.hyperxray.an.feature.warp.domain.entity.WarpDevice
import com.hyperxray.an.feature.warp.domain.repository.WarpRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private const val TAG = "WarpRepositoryImpl"

private val importJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * WARP repository implementation
 */
class WarpRepositoryImpl(
    private val context: Context,
    private val apiDataSource: WarpApiDataSource,
    private val storage: WarpAccountStorage
) : WarpRepository {
    
    override suspend fun registerAccount(licenseKey: String?): Result<WarpAccount> {
        return withContext(Dispatchers.IO) {
            try {
                val result = apiDataSource.registerAccount(licenseKey)
                result.fold(
                    onSuccess = { apiResponse ->
                        val account = WarpMapper.toWarpAccount(apiResponse)
                        // Save account after registration
                        storage.saveAccount(account)
                        Log.d(TAG, "Account registered and saved: ${account.accountId}")
                        Result.success(account)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to register account", error)
                        Result.failure(error)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception during registration", e)
                Result.failure(e)
            }
        }
    }
    
    override suspend fun importAccount(accountJson: String): Result<WarpAccount> {
        return withContext(Dispatchers.IO) {
            try {
                val trimmedJson = accountJson.trim()
                
                // Try to parse as full account JSON first
                val account = try {
                    val storageModel = importJson.decodeFromString<WarpAccountStorageModel>(trimmedJson)
                    WarpMapper.fromStorageModel(storageModel)
                } catch (e: Exception) {
                    Log.d(TAG, "Not a full account JSON, trying WireGuard config format...")
                    
                    // Try to parse as WireGuard config
                    if (trimmedJson.contains("[Interface]") && trimmedJson.contains("PrivateKey")) {
                        parseWireGuardConfig(trimmedJson)
                    } else {
                        // Maybe it's just a license key
                        if (trimmedJson.length in 20..50 && !trimmedJson.contains("{")) {
                            Log.d(TAG, "Treating input as license key, registering new account...")
                            return@withContext registerAccount(trimmedJson)
                        }
                        throw IllegalArgumentException("Unsupported import format. Expected: JSON account, WireGuard config, or license key")
                    }
                }
                
                // Save imported account
                storage.saveAccount(account)
                
                // Initialize API connection
                apiDataSource.initializeFromAccount(account.accountId, account.token)
                
                Log.d(TAG, "Account imported and saved: ${account.accountId}")
                Result.success(account)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import account", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Parse WireGuard config format and create WarpAccount
     */
    private fun parseWireGuardConfig(config: String): WarpAccount {
        val lines = config.lines()
        var privateKey: String? = null
        var addresses = mutableListOf<String>()
        
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("PrivateKey", ignoreCase = true) -> {
                    privateKey = trimmed.substringAfter("=").trim()
                }
                trimmed.startsWith("Address", ignoreCase = true) -> {
                    addresses.add(trimmed.substringAfter("=").trim())
                }
            }
        }
        
        requireNotNull(privateKey) { "WireGuard config must contain PrivateKey" }
        
        val ipv4 = addresses.find { it.contains(".") }
        val ipv6 = addresses.find { it.contains(":") }
        
        return WarpAccount(
            accountId = "imported-${System.currentTimeMillis()}",
            token = "",
            privateKey = privateKey,
            publicKey = "",
            config = com.hyperxray.an.feature.warp.domain.entity.WarpConfig(
                clientId = null,
                peers = emptyList(),
                interfaceData = com.hyperxray.an.feature.warp.domain.entity.WarpInterface(
                    addresses = com.hyperxray.an.feature.warp.domain.entity.WarpAddresses(
                        v4 = ipv4,
                        v6 = ipv6
                    )
                ),
                services = null
            ),
            account = com.hyperxray.an.feature.warp.domain.entity.WarpAccountInfo(
                id = null,
                accountType = "imported",
                warpPlus = false
            ),
            created = com.hyperxray.an.feature.warp.data.util.WarpIdGenerator.getTimestamp()
        )
    }
    
    override suspend fun updateLicense(accountId: String, licenseKey: String): Result<WarpAccount> {
        return withContext(Dispatchers.IO) {
            try {
                val result = apiDataSource.updateLicense(accountId, licenseKey)
                result.fold(
                    onSuccess = { apiResponse ->
                        // Reload account info to get updated data with license
                        val accountInfoResult = apiDataSource.getAccountInfo(accountId)
                        accountInfoResult.fold(
                            onSuccess = { updatedResponse ->
                                // Load stored private key to preserve it
                                val storedAccount = storage.loadAccount().getOrNull()
                                val account = WarpMapper.toWarpAccount(updatedResponse.copy(
                                    privateKey = storedAccount?.privateKey,
                                    publicKey = storedAccount?.publicKey
                                ))
                                // Save updated account with license key
                                storage.saveAccount(account)
                                Log.d(TAG, "License updated and account saved: ${account.accountId}")
                                Result.success(account)
                            },
                            onFailure = { error ->
                                Log.e(TAG, "Failed to reload account info after license update", error)
                                Result.failure(error)
                            }
                        )
                    },
                    onFailure = { error ->
                        Result.failure(error)
                    }
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun getAccountInfo(accountId: String): Result<WarpAccount> {
        return withContext(Dispatchers.IO) {
            try {
                val result = apiDataSource.getAccountInfo(accountId)
                result.fold(
                    onSuccess = { apiResponse ->
                        // Load private key from storage
                        val storedAccount = storage.loadAccount().getOrNull()
                        val account = if (storedAccount != null && storedAccount.accountId == accountId) {
                            // Merge with stored private key
                            WarpMapper.toWarpAccount(apiResponse.copy(
                                privateKey = storedAccount.privateKey,
                                publicKey = storedAccount.publicKey
                            ))
                        } else {
                            WarpMapper.toWarpAccount(apiResponse)
                        }
                        Result.success(account)
                    },
                    onFailure = { error ->
                        Result.failure(error)
                    }
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun getDevices(accountId: String): Result<List<WarpDevice>> {
        return withContext(Dispatchers.IO) {
            try {
                val result = apiDataSource.getDevices(accountId)
                result.fold(
                    onSuccess = { deviceResponses ->
                        val devices = deviceResponses.map { WarpMapper.toWarpDevice(it) }
                        Result.success(devices)
                    },
                    onFailure = { error ->
                        Result.failure(error)
                    }
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun removeDevice(accountId: String, deviceId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                apiDataSource.removeDevice(accountId, deviceId)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun regenerateKey(accountId: String): Result<WarpAccount> {
        return withContext(Dispatchers.IO) {
            try {
                val result = apiDataSource.regenerateKey(accountId)
                result.fold(
                    onSuccess = { apiResponse ->
                        val account = WarpMapper.toWarpAccount(apiResponse)
                        storage.saveAccount(account)
                        Result.success(account)
                    },
                    onFailure = { error ->
                        Result.failure(error)
                    }
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun saveAccount(account: WarpAccount): Result<Unit> {
        return withContext(Dispatchers.IO) {
            storage.saveAccount(account)
        }
    }
    
    override suspend fun deleteAccount(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            storage.deleteAccount()
        }
    }
    
    override suspend fun loadAccount(): Result<WarpAccount> {
        return withContext(Dispatchers.IO) {
            val result = storage.loadAccount()
            result.fold(
                onSuccess = { account ->
                    // Initialize API connection when account is loaded
                    apiDataSource.initializeFromAccount(account.accountId, account.token)
                    Log.d(TAG, "Account loaded and API connection initialized: ${account.accountId}")
                },
                onFailure = { }
            )
            result
        }
    }
    
    override suspend fun generateWireGuardConfig(account: WarpAccount, endpoint: String?): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val config = WarpConfigGenerator.generateWireGuardConfig(account, endpoint)
                Result.success(config)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun generateXrayConfig(account: WarpAccount, endpoint: String?): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val config = WarpConfigGenerator.generateXrayConfig(account, endpoint)
                Result.success(config)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun generateSingBoxConfig(account: WarpAccount, endpoint: String?): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val config = WarpConfigGenerator.generateSingBoxConfig(account, endpoint)
                Result.success(config)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun generateMasqueConfig(account: WarpAccount, endpoint: String?): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val config = WarpConfigGenerator.generateMasqueConfig(account, endpoint)
                Result.success(config)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun generateMasqueTunnelConfig(account: WarpAccount, endpoint: String?): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val config = WarpConfigGenerator.generateMasqueTunnelConfig(account, endpoint)
                Result.success(config)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}

