package com.hyperxray.an.feature.warp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyperxray.an.feature.warp.domain.entity.WarpAccount
import com.hyperxray.an.feature.warp.domain.entity.WarpConfigType
import com.hyperxray.an.feature.warp.domain.entity.WarpDevice
import com.hyperxray.an.feature.warp.domain.usecase.GenerateWarpConfigUseCase
import com.hyperxray.an.feature.warp.domain.usecase.GetWarpDevicesUseCase
import com.hyperxray.an.feature.warp.domain.usecase.ImportWarpAccountUseCase
import com.hyperxray.an.feature.warp.domain.usecase.LoadWarpAccountUseCase
import com.hyperxray.an.feature.warp.domain.usecase.RegisterWarpAccountUseCase
import com.hyperxray.an.feature.warp.domain.usecase.RemoveWarpDeviceUseCase
import com.hyperxray.an.feature.warp.domain.usecase.UpdateWarpLicenseUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for WARP feature
 */
data class WarpUiState(
    val isLoading: Boolean = false,
    val account: WarpAccount? = null,
    val devices: List<WarpDevice> = emptyList(),
    val error: String? = null,
    val generatedConfig: String? = null,
    val configType: WarpConfigType? = null
)

/**
 * ViewModel for WARP feature
 */
class WarpViewModel(
    private val registerAccountUseCase: RegisterWarpAccountUseCase,
    private val importAccountUseCase: ImportWarpAccountUseCase,
    private val updateLicenseUseCase: UpdateWarpLicenseUseCase,
    private val getDevicesUseCase: GetWarpDevicesUseCase,
    private val removeDeviceUseCase: RemoveWarpDeviceUseCase,
    private val generateConfigUseCase: GenerateWarpConfigUseCase,
    private val loadAccountUseCase: LoadWarpAccountUseCase
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(WarpUiState())
    val uiState: StateFlow<WarpUiState> = _uiState.asStateFlow()
    
    init {
        // Load saved account when ViewModel is created
        loadSavedAccount()
    }
    
    /**
     * Load saved account from storage
     */
    private fun loadSavedAccount() {
        viewModelScope.launch {
            loadAccountUseCase()
                .fold(
                    onSuccess = { account ->
                        _uiState.value = _uiState.value.copy(account = account)
                    },
                    onFailure = {
                        // No saved account, that's okay
                    }
                )
        }
    }
    
    /**
     * Register a new WARP account
     */
    fun registerAccount(licenseKey: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            registerAccountUseCase(licenseKey)
                .fold(
                    onSuccess = { account ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            account = account,
                            error = null
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = error.message ?: "Failed to register account"
                        )
                    }
                )
        }
    }
    
    /**
     * Update license key
     */
    fun updateLicense(licenseKey: String) {
        val accountId = _uiState.value.account?.accountId ?: return
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            updateLicenseUseCase(accountId, licenseKey)
                .fold(
                    onSuccess = { account ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            account = account,
                            error = null
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = error.message ?: "Failed to update license"
                        )
                    }
                )
        }
    }
    
    /**
     * Load devices
     */
    fun loadDevices() {
        val accountId = _uiState.value.account?.accountId ?: return
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            getDevicesUseCase(accountId)
                .fold(
                    onSuccess = { devices ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            devices = devices,
                            error = null
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = error.message ?: "Failed to load devices"
                        )
                    }
                )
        }
    }
    
    /**
     * Remove device
     */
    fun removeDevice(deviceId: String) {
        val accountId = _uiState.value.account?.accountId ?: return
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            removeDeviceUseCase(accountId, deviceId)
                .fold(
                    onSuccess = {
                        // Reload devices after removal
                        loadDevices()
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = error.message ?: "Failed to remove device"
                        )
                    }
                )
        }
    }
    
    /**
     * Generate configuration
     */
    fun generateConfig(configType: WarpConfigType, endpoint: String? = null) {
        val account = _uiState.value.account ?: return
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            generateConfigUseCase(account, configType, endpoint)
                .fold(
                    onSuccess = { config ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            generatedConfig = config,
                            configType = configType,
                            error = null
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = error.message ?: "Failed to generate config"
                        )
                    }
                )
        }
    }
    
    /**
     * Import account from JSON, WireGuard config, or license key
     */
    fun importAccount(accountData: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            importAccountUseCase(accountData)
                .fold(
                    onSuccess = { account ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            account = account,
                            error = null
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = error.message ?: "Failed to import account"
                        )
                    }
                )
        }
    }
    
    /**
     * Clear error
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    /**
     * Clear generated config
     */
    fun clearGeneratedConfig() {
        _uiState.value = _uiState.value.copy(generatedConfig = null, configType = null)
    }
}

