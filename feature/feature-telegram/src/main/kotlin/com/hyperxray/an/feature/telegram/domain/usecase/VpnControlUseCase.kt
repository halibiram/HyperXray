package com.hyperxray.an.feature.telegram.domain.usecase

/**
 * VPN control use case
 * Controls VPN connection (connect/disconnect)
 * 
 * Note: This is a placeholder. Actual implementation is in app module
 * to avoid circular dependencies.
 */
interface VpnControlUseCase {
    suspend fun connect(): Result<String>
    suspend fun disconnect(): Result<String>
    suspend fun restart(): Result<String>
    suspend fun getConnectionStatus(): Result<Boolean>
}

