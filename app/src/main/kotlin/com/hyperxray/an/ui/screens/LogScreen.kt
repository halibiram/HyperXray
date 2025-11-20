package com.hyperxray.an.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyperxray.an.R
import com.hyperxray.an.ui.screens.log.ConnectionType
import com.hyperxray.an.ui.screens.log.LogDetailSheet
import com.hyperxray.an.ui.screens.log.LogEntryItem
import com.hyperxray.an.ui.screens.log.LogFilters
import com.hyperxray.an.ui.screens.log.LogLevel
import com.hyperxray.an.ui.theme.ScrollbarDefaults
import com.hyperxray.an.viewmodel.LogViewModel
import kotlinx.coroutines.launch
import my.nanihadesuka.compose.LazyColumnScrollbar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    logViewModel: LogViewModel,
    listState: LazyListState
) {
    val context = LocalContext.current
    val filteredEntries by logViewModel.filteredEntries.collectAsStateWithLifecycle()
    
    // Local state
    val isInitialLoad = remember { mutableStateOf(true) }
    var selectedLogEntry by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    
    // Filter states
    var selectedLogLevel by remember { mutableStateOf<LogLevel?>(null) }
    var selectedConnectionType by remember { mutableStateOf<ConnectionType?>(null) }
    var showSniffingOnly by remember { mutableStateOf(false) }
    var showAiOnly by remember { mutableStateOf(false) }
    
    // Update filters in ViewModel
    LaunchedEffect(selectedLogLevel, selectedConnectionType, showSniffingOnly, showAiOnly) {
        logViewModel.updateFilters(
            level = selectedLogLevel,
            type = selectedConnectionType,
            sniffingOnly = showSniffingOnly,
            aiOnly = showAiOnly
        )
    }

    // Lifecycle management
    DisposableEffect(key1 = Unit) {
        logViewModel.registerLogReceiver(context)
        logViewModel.loadLogs()
        onDispose {
            logViewModel.unregisterLogReceiver(context)
        }
    }

    // Scroll behavior logic
    val isUserScrolledAway = remember { mutableStateOf(false) }
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        if (listState.firstVisibleItemIndex > 0 || 
            (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 20)) {
            isUserScrolledAway.value = true
        } else if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 5) {
            isUserScrolledAway.value = false
        }
    }

    LaunchedEffect(filteredEntries.size) {
        if (filteredEntries.isNotEmpty()) {
            if (isInitialLoad.value) {
                listState.animateScrollToItem(0)
                isInitialLoad.value = false
            } else if (!isUserScrolledAway.value) {
                listState.animateScrollToItem(0)
            }
        }
    }

    // Main Container - Deep Black Background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050505)) // Deepest black
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Futuristic Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF00FFFF).copy(alpha = 0.1f),
                                Color.Transparent,
                                Color(0xFF00FFFF).copy(alpha = 0.1f)
                            )
                        ),
                        shape = CircleShape
                    )
                    .background(Color(0xFF0A0A0A), CircleShape)
                    .padding(vertical = 8.dp, horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "SYSTEM LOGS",
                        color = Color(0xFF00FFFF),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        fontSize = 14.sp
                    )
                    
                    // Live indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF00FF00), CircleShape)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = "LIVE",
                            color = Color(0xFF00FF00).copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Filters
            LogFilters(
                selectedLogLevel = selectedLogLevel,
                selectedConnectionType = selectedConnectionType,
                showSniffingOnly = showSniffingOnly,
                showAiOnly = showAiOnly,
                onLogLevelSelected = { selectedLogLevel = it },
                onConnectionTypeSelected = { selectedConnectionType = it },
                onShowSniffingOnlyChanged = { showSniffingOnly = it },
                onShowAiOnlyChanged = { showAiOnly = it }
            )
            
            Spacer(modifier = Modifier.height(8.dp))

            // Log List
            if (filteredEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "[ NO DATA STREAM ]",
                            color = Color.DarkGray,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                LazyColumnScrollbar(
                    state = listState,
                    settings = ScrollbarDefaults.defaultScrollbarSettings().copy(
                        thumbUnselectedColor = Color(0xFF00FFFF).copy(alpha = 0.3f),
                        thumbSelectedColor = Color(0xFF00FFFF).copy(alpha = 0.6f)
                    )
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 80.dp, top = 8.dp), // Space for FAB
                        reverseLayout = true
                    ) {
                        items(
                            items = filteredEntries,
                            key = { it }
                        ) { logEntry ->
                            LogEntryItem(
                                logEntry = logEntry,
                                onClick = {
                                    selectedLogEntry = logEntry
                                    scope.launch { sheetState.show() }
                                }
                            )
                        }
                    }
                }
            }
        }
        
        // Futuristic Scroll to Top Button
        AnimatedVisibility(
            visible = isUserScrolledAway.value && filteredEntries.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            enter = fadeIn() + slideInVertically(initialOffsetY = { 50 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { 50 })
        ) {
            IconButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(0)
                        isUserScrolledAway.value = false
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFF00FFFF).copy(alpha = 0.1f), CircleShape)
                    .border(1.dp, Color(0xFF00FFFF).copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Scroll to latest",
                    tint = Color(0xFF00FFFF)
                )
            }
        }
    }

    // Detail Sheet
    selectedLogEntry?.let { logEntry ->
        ModalBottomSheet(
            onDismissRequest = {
                scope.launch { sheetState.hide() }.invokeOnCompletion { selectedLogEntry = null }
            },
            sheetState = sheetState,
            containerColor = Color(0xFF101010),
            contentColor = Color(0xFFE0E0E0)
        ) {
            LogDetailSheet(logEntry = logEntry)
        }
    }
}
