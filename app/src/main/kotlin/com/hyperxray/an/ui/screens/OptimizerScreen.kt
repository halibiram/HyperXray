package com.hyperxray.an.ui.screens

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    
    // Responsive layout
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600
    val horizontalPadding = if (isTablet) 24.dp else 16.dp
    val cardSpacing = if (isTablet) 20.dp else 16.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = horizontalPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(cardSpacing)
    ) {
        Text(
            text = "TLS SNI Learner Debug",
            style = MaterialTheme.typography.headlineMedium
        )
        
        // Temperature Card - Modernized
        ModernOptimizerCard(
            title = "Temperature (T)",
            value = String.format("%.3f", temperature),
            description = "Higher = more uniform predictions",
            gradientColors = listOf(
                Color(0xFF6366F1),
                Color(0xFF8B5CF6),
                Color(0xFFA855F7)
            ),
            index = 0
        )
        
        // Service Biases Card - Modernized
        ModernOptimizerCard(
            title = "Service Type Biases (8 classes)",
            gradientColors = listOf(
                Color(0xFF06B6D4),
                Color(0xFF3B82F6),
                Color(0xFF6366F1)
            ),
            index = 1
        ) {
            svcBiases.forEachIndexed { index, bias ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Svc[$index]:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = String.format("%.3f", bias),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (index < svcBiases.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
        
        // Route Biases Card - Modernized
        ModernOptimizerCard(
            title = "Routing Decision Biases (3 routes)",
            gradientColors = listOf(
                Color(0xFF10B981),
                Color(0xFF059669),
                Color(0xFF047857)
            ),
            index = 2
        ) {
            val routeNames = listOf("Proxy (0)", "Direct (1)", "Optimized (2)")
            routeBiases.forEachIndexed { index, bias ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = routeNames[index],
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = String.format("%.3f", bias),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (index < routeBiases.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
        
        // Success Rate Card - Modernized
        ModernOptimizerCard(
            title = "Feedback Statistics",
            gradientColors = listOf(
                Color(0xFFEC4899),
                Color(0xFFDB2777),
                Color(0xFFBE185D)
            ),
            index = 3
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatRow(
                    label = "Success",
                    value = successCount.toString(),
                    color = Color(0xFF10B981)
                )
                StatRow(
                    label = "Fail",
                    value = failCount.toString(),
                    color = Color(0xFFEF4444)
                )
                StatRow(
                    label = "Success Rate",
                    value = "${String.format("%.1f", successRate * 100)}%",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        // Action Buttons - Modernized
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    learner.reset()
                    refreshTrigger++
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Reset Learner", fontWeight = FontWeight.SemiBold)
            }
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
                modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Text("Reload Xray", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ModernOptimizerCard(
    title: String,
    value: String? = null,
    description: String? = null,
    gradientColors: List<Color>,
    index: Int,
    content: @Composable (() -> Unit)? = null
) {
    var isVisible by remember(index) { mutableStateOf(false) }
    
    val cardScale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.95f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 300f
        ),
        label = "card_scale"
    )
    
    val cardAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = 300,
            delayMillis = index * 50
        ),
        label = "card_alpha"
    )

    LaunchedEffect(index) {
        isVisible = true
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(cardScale)
            .alpha(cardAlpha)
            .clip(MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp,
            pressedElevation = 10.dp,
            hoveredElevation = 8.dp
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            gradientColors[0].copy(alpha = 0.12f),
                            gradientColors[1].copy(alpha = 0.08f),
                            gradientColors.getOrNull(2)?.copy(alpha = 0.05f) ?: Color.Transparent
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(gradientColors[0])
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                if (value != null || description != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    if (value != null) {
                        Text(
                            text = value,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = gradientColors[0],
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (description != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                if (content != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}

