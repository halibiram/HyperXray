package com.hyperxray.an.feature.telegram.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hyperxray.an.feature.telegram.presentation.viewmodel.TelegramSettingsViewModel

/**
 * Telegram settings screen
 */
@Composable
fun TelegramSettingsScreen(
    viewModel: TelegramSettingsViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(uiState.showSuccessMessage) {
        if (uiState.showSuccessMessage) {
            uiState.successMessage?.let { message ->
                snackbarHostState.showSnackbar(message)
                viewModel.dismissSuccessMessage()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "Telegram Notifications",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            TelegramConfigCard(
                botToken = uiState.config?.botToken ?: "",
                chatId = uiState.config?.chatId ?: "",
                enabled = uiState.config?.enabled ?: false,
                onBotTokenChange = viewModel::updateBotToken,
                onChatIdChange = viewModel::updateChatId,
                onEnabledChange = viewModel::updateEnabled,
                onSave = viewModel::saveConfig,
                onTest = viewModel::testConnection,
                isSaving = uiState.isSaving,
                isTesting = uiState.isTesting
            )

            NotificationPreferencesCard(
                notifyVpnStatus = uiState.config?.notifyVpnStatus ?: true,
                notifyErrors = uiState.config?.notifyErrors ?: true,
                notifyPerformance = uiState.config?.notifyPerformance ?: false,
                notifyDnsCache = uiState.config?.notifyDnsCache ?: false,
                notifyManual = uiState.config?.notifyManual ?: true,
                onNotifyVpnStatusChange = viewModel::updateNotifyVpnStatus,
                onNotifyErrorsChange = viewModel::updateNotifyErrors,
                onNotifyPerformanceChange = viewModel::updateNotifyPerformance,
                onNotifyDnsCacheChange = viewModel::updateNotifyDnsCache,
                onNotifyManualChange = viewModel::updateNotifyManual
            )
        }
    }
}






