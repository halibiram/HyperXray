package com.hyperxray.an.ui.screens.insights

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hyperxray.an.viewmodel.AiInsightsViewModel
import kotlinx.coroutines.launch

/**
 * AI Insights Dashboard Screen.
 * Displays on-device AI learner state, biases, policies, and feedback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiInsightsScreen(
    onBackClick: () -> Unit,
    viewModel: AiInsightsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    var showResetDialog by remember { mutableStateOf(false) }
    
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
        topBar = {
            TopAppBar(
                title = { Text("AI Insights Dashboard") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Loading AI insights...")
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔄 Refresh")
                    }
                    Button(
                        onClick = { showResetDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🧹 Reset")
                    }
                    Button(
                        onClick = { viewModel.exportReport() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📤 Export")
                    }
                }
                
                // Summary Cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryCard(
                        title = "Temperature",
                        value = String.format("%.3f", uiState.temperature),
                        modifier = Modifier.weight(1f)
                    )
                    SummaryCard(
                        title = "Success Rate",
                        value = String.format("%.1f%%", uiState.successRate * 100),
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryCard(
                        title = "Total Feedback",
                        value = uiState.totalFeedback.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    SummaryCard(
                        title = "Last Updated",
                        value = if (uiState.lastUpdated.isNotEmpty()) {
                            uiState.lastUpdated.take(19) // Truncate to reasonable length
                        } else "Never",
                        modifier = Modifier.weight(1f)
                    )
                }
                
                // Service Type Biases
                BiasSectionCard(
                    title = "Service Type Biases",
                    biases = uiState.svcBias,
                    labels = listOf("YouTube", "Netflix", "Twitter", "Instagram", "TikTok", "Twitch", "Spotify", "Other"),
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Route Biases
                BiasSectionCard(
                    title = "Route Decision Biases",
                    biases = uiState.routeBias,
                    labels = listOf("🔵 Proxy", "🟢 Direct", "🟣 Optimized"),
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Current REALITY Policy
                if (uiState.currentPolicy.isNotEmpty()) {
                    PolicyTableCard(
                        title = "Current REALITY Policy",
                        entries = uiState.currentPolicy,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Policy Changes
                if (uiState.policyChanges.isNotEmpty()) {
                    PolicyChangesCard(
                        title = "Policy Changes (Delta)",
                        changes = uiState.policyChanges,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Recent Feedback
                if (uiState.recentFeedback.isNotEmpty()) {
                    FeedbackListCard(
                        title = "Recent Feedback",
                        feedback = uiState.recentFeedback,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
    
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Learner") },
            text = { Text("Are you sure you want to reset all learner biases and temperature to defaults? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetLearner()
                        showResetDialog = false
                    }
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun BiasSectionCard(
    title: String,
    biases: FloatArray,
    labels: List<String>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            biases.forEachIndexed { index, bias ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (index < labels.size) labels[index] else "Bias[$index]",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    LinearProgressIndicator(
                        progress = { (bias + 1f) / 2f }, // Normalize from -1..+1 to 0..1
                        modifier = Modifier
                            .weight(2f)
                            .height(8.dp),
                        color = when {
                            bias > 0.1f -> Color(0xFF4CAF50) // Green for positive
                            bias < -0.1f -> Color(0xFFF44336) // Red for negative
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = String.format("%.3f", bias),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(60.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PolicyTableCard(
    title: String,
    entries: List<com.hyperxray.an.viewmodel.PolicyEntry>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            entries.take(20).forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(2f)) {
                        Text(
                            text = entry.sni,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (entry.routeDecision) {
                                0 -> "🔵 Proxy"
                                1 -> "🟢 Direct"
                                2 -> "🟣 Optimized"
                                else -> "Route ${entry.routeDecision}"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.alpn.ifEmpty { "N/A" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            
            if (entries.size > 20) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "... and ${entries.size - 20} more entries",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PolicyChangesCard(
    title: String,
    changes: List<com.hyperxray.an.viewmodel.PolicyChange>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            changes.take(10).forEachIndexed { index, change ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                
                Column {
                    Text(
                        text = change.sni,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row {
                        Text(
                            text = "Route: ${change.oldRoute} → ${change.newRoute}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (change.oldAlpn != change.newAlpn) {
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "ALPN: ${change.oldAlpn} → ${change.newAlpn}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            
            if (changes.size > 10) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "... and ${changes.size - 10} more changes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FeedbackListCard(
    title: String,
    feedback: List<com.hyperxray.an.viewmodel.FeedbackEntry>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            feedback.take(20).forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(2f)) {
                        Text(
                            text = entry.sni,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = entry.timestamp.take(19),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (entry.success) "✅" else "❌",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = formatLatency(entry.latency),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = formatThroughput(entry.throughput),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            
            if (feedback.size > 20) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "... and ${feedback.size - 20} more entries",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatLatency(latencyMs: Long): String {
    return when {
        latencyMs < 1000 -> "${latencyMs}ms"
        latencyMs < 60000 -> "${latencyMs / 1000.0}s"
        else -> "${latencyMs / 60000.0}min"
    }
}

private fun formatThroughput(throughput: Float): String {
    return when {
        throughput < 1000 -> String.format("%.1f KB/s", throughput)
        throughput < 1000000 -> String.format("%.1f MB/s", throughput / 1000)
        else -> String.format("%.2f GB/s", throughput / 1000000)
    }
}

