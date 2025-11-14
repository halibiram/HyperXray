package com.hyperxray.an.ui.screens

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyperxray.an.R
import com.hyperxray.an.ui.screens.log.ConnectionType
import com.hyperxray.an.ui.screens.log.LogDetailSheet
import com.hyperxray.an.ui.screens.log.LogEntryItem
import com.hyperxray.an.ui.screens.log.LogFilters
import com.hyperxray.an.ui.screens.log.LogLevel
import com.hyperxray.an.ui.screens.log.isAiLog
import com.hyperxray.an.ui.screens.log.isSniffingLog
import com.hyperxray.an.ui.screens.log.parseConnectionType
import com.hyperxray.an.ui.screens.log.parseLogLevel
import com.hyperxray.an.viewmodel.LogViewModel
import kotlinx.coroutines.launch
import my.nanihadesuka.compose.LazyColumnScrollbar
import com.hyperxray.an.ui.theme.ScrollbarDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    logViewModel: LogViewModel,
    listState: LazyListState
) {
    val context = LocalContext.current
    val allEntries by logViewModel.logEntries.collectAsStateWithLifecycle()
    val searchQuery by logViewModel.searchQuery.collectAsStateWithLifecycle()
    val isInitialLoad = remember { mutableStateOf(true) }
    var selectedLogEntry by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    
    // Filter states
    var selectedLogLevel by remember { mutableStateOf<LogLevel?>(null) }
    var selectedConnectionType by remember { mutableStateOf<ConnectionType?>(null) }
    var showSniffingOnly by remember { mutableStateOf(false) }
    var showAiOnly by remember { mutableStateOf(false) }
    
    // Use ViewModel's index-based filtered entries (ultra-fast for 100K entries)
    // MegaLogBuffer handles all filtering with index-based O(1) lookups
    val filteredEntries = logViewModel.filteredEntries.collectAsStateWithLifecycle().value
    
    // Update ViewModel filters when UI filters change
    LaunchedEffect(selectedLogLevel, selectedConnectionType, showSniffingOnly, showAiOnly) {
        logViewModel.updateFilters(
            level = selectedLogLevel,
            type = selectedConnectionType,
            sniffingOnly = showSniffingOnly,
            aiOnly = showAiOnly
        )
    }

    DisposableEffect(key1 = Unit) {
        logViewModel.registerLogReceiver(context)
        logViewModel.loadLogs()
        onDispose {
            logViewModel.unregisterLogReceiver(context)
        }
    }

    // Track if user has scrolled away from top (index 0)
    val isUserScrolledAway = remember { mutableStateOf(false) }
    
    // Monitor scroll position to detect user interaction
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        // If user scrolled away from first item (index 0), disable auto-scroll
        // reverseLayout = true, so index 0 is the newest log at top
        // Use smaller threshold (20dp) for more responsive detection
        if (listState.firstVisibleItemIndex > 0 || 
            (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 20)) {
            isUserScrolledAway.value = true
        } else if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 5) {
            // User scrolled back to top (scrollbar at the end), re-enable auto-scroll
            isUserScrolledAway.value = false
        }
    }

    LaunchedEffect(filteredEntries.size) {
        if (filteredEntries.isNotEmpty()) {
            if (isInitialLoad.value) {
                listState.animateScrollToItem(0)
                isInitialLoad.value = false
            } else if (!isUserScrolledAway.value) {
                // Auto-scroll only if user hasn't scrolled away
                listState.animateScrollToItem(0)
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Filter section
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
            
            // Log list
            if (filteredEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.no_log_entries),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumnScrollbar(
                    state = listState,
                    settings = ScrollbarDefaults.defaultScrollbarSettings()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        reverseLayout = true,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = filteredEntries,
                            key = { it }
                        ) { logEntry ->
                            LogEntryItem(
                                logEntry = logEntry,
                                onClick = {
                                    selectedLogEntry = logEntry
                                    scope.launch {
                                        sheetState.show()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        
        // FloatingActionButton to scroll to latest log (top) - Enhanced
        FloatingActionButton(
            onClick = {
                scope.launch {
                    listState.animateScrollToItem(0)
                    isUserScrolledAway.value = false
                }
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
                defaultElevation = 6.dp,
                pressedElevation = 10.dp
            )
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Scroll to latest log",
                modifier = Modifier.size(24.dp)
            )
        }
    }

    // Log detail bottom sheet
    selectedLogEntry?.let { logEntry ->
        ModalBottomSheet(
            onDismissRequest = {
                scope.launch {
                    sheetState.hide()
                }.invokeOnCompletion {
                    selectedLogEntry = null
                }
            },
            sheetState = sheetState
        ) {
            LogDetailSheet(logEntry = logEntry)
        }
    }
}
