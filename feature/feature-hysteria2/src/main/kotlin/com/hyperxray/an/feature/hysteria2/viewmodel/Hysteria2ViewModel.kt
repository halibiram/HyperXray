package com.hyperxray.an.feature.hysteria2.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for Hysteria2 configuration management.
 * Handles Hysteria2 protocol-specific settings, bandwidth management, and validation.
 */
class Hysteria2ViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(Hysteria2UiState())
    val uiState: StateFlow<Hysteria2UiState> = _uiState.asStateFlow()

    // Hysteria2-specific configuration methods will be added here
}

/**
 * UI state for Hysteria2 feature.
 */
data class Hysteria2UiState(
    val isLoading: Boolean = false,
    val error: String? = null
)

