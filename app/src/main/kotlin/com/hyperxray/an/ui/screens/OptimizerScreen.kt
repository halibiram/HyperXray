package com.hyperxray.an.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hyperxray.an.optimizer.LearnerState
import com.hyperxray.an.optimizer.OnDeviceLearner
import com.hyperxray.an.ui.screens.insights.AiInsightsContent
import com.hyperxray.an.viewmodel.AiInsightsViewModel
import kotlinx.coroutines.launch

/**
 * OptimizerScreen: Main screen for TLS SNI Optimizer.
 * 
 * Displays two tabs:
 * - Learner Debug: Shows learner state, biases, and statistics
 * - AI Insights: Shows AI learner dashboard with policies and feedback
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptimizerScreen() {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val aiInsightsViewModel: AiInsightsViewModel = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    val uiState by aiInsightsViewModel.uiState.collectAsStateWithLifecycle()
    
    // Auto-refresh AI Insights every 30 seconds
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30000) // 30 seconds
            if (selectedTabIndex == 1) {
                aiInsightsViewModel.refresh()
            }
        }
    }
    
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            scope.launch {
                snackbarHostState.showSnackbar(error)
            }
        }
    }
    
    LaunchedEffect(uiState.exportMessage) {
        uiState.exportMessage?.let { message ->
            scope.launch {
                snackbarHostState.showSnackbar(message)
            }
        }
    }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tab Row
            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Learner Debug") }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { 
                        selectedTabIndex = 1
                        // Refresh AI Insights when switching to this tab
                        aiInsightsViewModel.refresh()
                    },
                    text = { Text("AI Insights") }
                )
            }
            
            // Tab Content
            when (selectedTabIndex) {
                0 -> {
                    LearnerDebugScreenContent()
                }
                1 -> {
                    val showResetDialog = remember { mutableStateOf(false) }
                    AiInsightsContent(
                        viewModel = aiInsightsViewModel,
                        showResetDialog = showResetDialog,
                        onShowResetDialogChange = { showResetDialog.value = it },
                        onResetLearner = {
                            aiInsightsViewModel.resetLearner()
                        },
                        paddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    )
                }
            }
        }
    }
}

/**
 * LearnerDebugScreenContent: Content of Learner Debug tab (without Scaffold).
 */
@Composable
private fun LearnerDebugScreenContent() {
    val context = LocalContext.current
    val learnerState = remember { LearnerState(context) }
    val learner = remember { OnDeviceLearner(context) }
    
    var refreshTrigger by remember { mutableStateOf(0) }
    
    val temperature = learnerState.getTemperature()
    val svcBiases = learnerState.getSvcBiases()
    val routeBiases = learnerState.getRouteBiases()
    val successCount = learnerState.getSuccessCount()
    val failCount = learnerState.getFailCount()
    val successRate = learnerState.getSuccessRate()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "TLS SNI Learner Debug",
            style = MaterialTheme.typography.headlineMedium
        )
        
        // Temperature Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Temperature (T)",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = String.format("%.3f", temperature),
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Higher = more uniform predictions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // Service Biases Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Service Type Biases (8 classes)",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                svcBiases.forEachIndexed { index, bias ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Svc[$index]:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = String.format("%.3f", bias),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (index < svcBiases.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
        
        // Route Biases Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Routing Decision Biases (3 routes)",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                val routeNames = listOf("Proxy (0)", "Direct (1)", "Optimized (2)")
                routeBiases.forEachIndexed { index, bias ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = routeNames[index],
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = String.format("%.3f", bias),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (index < routeBiases.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
        
        // Success Rate Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Feedback Statistics",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Success: $successCount",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Fail: $failCount",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Success Rate: ${String.format("%.1f", successRate * 100)}%",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        
        // Reset Button
        Button(
            onClick = {
                learner.reset()
                refreshTrigger++
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset Learner")
        }
        
        // Force Reload Button
        Button(
            onClick = {
                // Trigger Xray reload
                val intent = android.content.Intent("com.hyperxray.REALITY_RELOAD")
                intent.setPackage(context.packageName)
                context.sendBroadcast(intent)
                
                // Also try the existing reload action
                val reloadIntent = android.content.Intent("com.hyperxray.an.RELOAD_CONFIG")
                reloadIntent.setPackage(context.packageName)
                context.sendBroadcast(reloadIntent)
                
                refreshTrigger++
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Force Reload Xray")
        }
        
        // Refresh Button
        Button(
            onClick = {
                refreshTrigger++
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Refresh")
        }
    }
}

