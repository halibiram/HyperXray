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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.content.Intent
import com.hyperxray.an.optimizer.LearnerState
import com.hyperxray.an.optimizer.OnDeviceLearner
import java.io.File

/**
 * LearnerDebugScreen: Debug screen showing current learner state.
 * 
 * Displays:
 * - Current temperature T
 * - Service type biases (svcBias[8])
 * - Routing decision biases (routeBias[3])
 * - Last feedback success rate
 * - Option to reset learner
 */
@Composable
fun LearnerDebugScreen() {
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
    
    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
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
                    val intent = Intent("com.hyperxray.REALITY_RELOAD")
                    intent.setPackage(context.packageName)
                    context.sendBroadcast(intent)
                    
                    // Also try the existing reload action
                    val reloadIntent = Intent("com.hyperxray.an.RELOAD_CONFIG")
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
}


