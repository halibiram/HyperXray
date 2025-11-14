package com.hyperxray.an.feature.vless.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for VLESS configuration management.
 * Handles VLESS protocol-specific settings and validation.
 */
class VlessViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(VlessUiState())
    val uiState: StateFlow<VlessUiState> = _uiState.asStateFlow()

    // VLESS-specific configuration methods will be added here
}

/**
 * UI state for VLESS feature.
 */
data class VlessUiState(
    val isLoading: Boolean = false,
    val error: String? = null
)

