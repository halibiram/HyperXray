package com.hyperxray.an.feature.warp.data.repository

import android.content.Context
import android.util.Log
import com.hyperxray.an.feature.warp.data.datasource.WarpApiDataSource
import com.hyperxray.an.feature.warp.data.mapper.WarpMapper
import com.hyperxray.an.feature.warp.data.storage.WarpAccountStorage
import com.hyperxray.an.feature.warp.data.util.WarpConfigGenerator
import com.hyperxray.an.feature.warp.domain.entity.WarpAccount
import com.hyperxray.an.feature.warp.domain.entity.WarpDevice
import com.hyperxray.an.feature.warp.domain.repository.WarpRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "WarpRepositoryImpl"

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
}

