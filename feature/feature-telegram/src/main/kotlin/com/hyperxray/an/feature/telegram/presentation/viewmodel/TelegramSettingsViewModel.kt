package com.hyperxray.an.feature.telegram.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hyperxray.an.feature.telegram.data.datasource.TelegramApiDataSource
import com.hyperxray.an.feature.telegram.data.datasource.TelegramConfigDataSource
import com.hyperxray.an.feature.telegram.data.repository.TelegramRepositoryImpl
import com.hyperxray.an.feature.telegram.data.storage.SecureStorageManager
import com.hyperxray.an.feature.telegram.domain.entity.TelegramConfig
import com.hyperxray.an.feature.telegram.domain.usecase.GetTelegramConfigUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.SaveTelegramConfigUseCase
import com.hyperxray.an.feature.telegram.domain.usecase.TestTelegramConnectionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for Telegram settings screen
 */
class TelegramSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val secureStorage = SecureStorageManager(application)
    private val configDataSource = TelegramConfigDataSource(application, secureStorage)
    private val apiDataSource = TelegramApiDataSource()
    private val repository = TelegramRepositoryImpl(apiDataSource, configDataSource)
    
    private val getConfigUseCase = GetTelegramConfigUseCase(repository)
    private val saveConfigUseCase = SaveTelegramConfigUseCase(repository)
    private val testConnectionUseCase = TestTelegramConnectionUseCase(repository)

    private val _uiState = MutableStateFlow(TelegramSettingsUiState())
    val uiState: StateFlow<TelegramSettingsUiState> = _uiState.asStateFlow()

    init {
        loadConfig()
    }

    fun loadConfig() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            getConfigUseCase().fold(
                onSuccess = { config ->
                    _uiState.value = _uiState.value.copy(
                        config = config,
                        isLoading = false,
                        error = null
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to load configuration"
                    )
                }
            )
        }
    }

    fun updateBotToken(token: String) {
        _uiState.value = _uiState.value.copy(
            config = _uiState.value.config?.copy(botToken = token) ?: TelegramConfig(
                botToken = token,
                chatId = ""
            )
        )
    }

    fun updateChatId(chatId: String) {
        _uiState.value = _uiState.value.copy(
            config = _uiState.value.config?.copy(chatId = chatId) ?: TelegramConfig(
                botToken = "",
                chatId = chatId
            )
        )
    }

    fun updateEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            config = _uiState.value.config?.copy(enabled = enabled) ?: TelegramConfig(
                botToken = "",
                chatId = "",
                enabled = enabled
            )
        )
    }

    fun updateNotifyVpnStatus(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            config = _uiState.value.config?.copy(notifyVpnStatus = enabled) ?: TelegramConfig(
                botToken = "",
                chatId = "",
                notifyVpnStatus = enabled
            )
        )
    }

    fun updateNotifyErrors(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            config = _uiState.value.config?.copy(notifyErrors = enabled) ?: TelegramConfig(
                botToken = "",
                chatId = "",
                notifyErrors = enabled
            )
        )
    }

    fun updateNotifyPerformance(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            config = _uiState.value.config?.copy(notifyPerformance = enabled) ?: TelegramConfig(
                botToken = "",
                chatId = "",
                notifyPerformance = enabled
            )
        )
    }

    fun updateNotifyDnsCache(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            config = _uiState.value.config?.copy(notifyDnsCache = enabled) ?: TelegramConfig(
                botToken = "",
                chatId = "",
                notifyDnsCache = enabled
            )
        )
    }

    fun updateNotifyManual(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            config = _uiState.value.config?.copy(notifyManual = enabled) ?: TelegramConfig(
                botToken = "",
                chatId = "",
                notifyManual = enabled
            )
        )
    }

    fun saveConfig() {
        val config = _uiState.value.config ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            saveConfigUseCase(config).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = null,
                        showSuccessMessage = true
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = error.message ?: "Failed to save configuration"
                    )
                }
            )
        }
    }

    fun testConnection() {
        val config = _uiState.value.config ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true, error = null)
            testConnectionUseCase(config).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isTesting = false,
                        showSuccessMessage = true,
                        successMessage = "Test message sent successfully!"
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isTesting = false,
                        error = error.message ?: "Failed to send test message"
                    )
                }
            )
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun dismissSuccessMessage() {
        _uiState.value = _uiState.value.copy(showSuccessMessage = false, successMessage = null)
    }
}

/**
 * UI state for Telegram settings screen
 */
data class TelegramSettingsUiState(
    val config: TelegramConfig? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val error: String? = null,
    val showSuccessMessage: Boolean = false,
    val successMessage: String? = null
)

