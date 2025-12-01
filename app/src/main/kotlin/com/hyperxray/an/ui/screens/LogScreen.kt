package com.hyperxray.an.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyperxray.an.ui.screens.log.*
import com.hyperxray.an.ui.theme.ScrollbarDefaults
import com.hyperxray.an.viewmodel.LogViewModel
import kotlinx.coroutines.delay
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
    val searchQuery by logViewModel.searchQuery.collectAsStateWithLifecycle()
    
    // UI States
    val isInitialLoad = remember { mutableStateOf(true) }
    var selectedLogEntry by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    
    // Filter states
    var selectedLogLevel by remember { mutableStateOf<LogLevel?>(null) }
    var selectedConnectionType by remember { mutableStateOf<ConnectionType?>(null) }
    var showSniffingOnly by remember { mutableStateOf(false) }
    var showAiOnly by remember { mutableStateOf(false) }
    
    // Advanced UI states
    var viewMode by remember { mutableStateOf(LogViewMode.TERMINAL) }
    var sortOrder by remember { mutableStateOf(LogSortOrder.NEWEST_FIRST) }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var isStatsExpanded by remember { mutableStateOf(false) }
    var isAutoScrollEnabled by remember { mutableStateOf(true) }
    var isLive by remember { mutableStateOf(true) }
    
    // Calculate stats
    val stats by remember(filteredEntries) {
        derivedStateOf {
            calculateLogStats(filteredEntries)
        }
    }
    
    // Logs per second calculation
    var logsPerSecond by remember { mutableStateOf(0f) }
    var previousLogCount by remember { mutableStateOf(0) }
    
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val currentCount = filteredEntries.size
            logsPerSecond = (currentCount - previousLogCount).toFloat().coerceAtLeast(0f)
            previousLogCount = currentCount
        }
    }
    
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

    // Scroll behavior
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
            } else if (!isUserScrolledAway.value && isAutoScrollEnabled) {
                listState.animateScrollToItem(0)
            }
        }
    }

    // Animated background
    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val gradientOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gradientOffset"
    )

    // Main Container
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF030308))
            .drawBehind {
                // Subtle animated gradient orbs
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            LogColorPalette.NeonCyan.copy(alpha = 0.03f),
                            Color.Transparent
                        ),
                        center = Offset(gradientOffset % size.width, size.height * 0.3f),
                        radius = size.width * 0.5f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            LogColorPalette.NeonPurple.copy(alpha = 0.02f),
                            Color.Transparent
                        ),
                        center = Offset(size.width - (gradientOffset % size.width), size.height * 0.7f),
                        radius = size.width * 0.4f
                    )
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Futuristic Header
            FuturisticHeader(
                isLive = isLive,
                logsPerSecond = logsPerSecond,
                totalLogs = stats.totalCount
            )
            
            // Stats Panel
            LogStatsPanel(
                stats = stats.copy(logsPerSecond = logsPerSecond),
                isExpanded = isStatsExpanded,
                onToggleExpand = { isStatsExpanded = !isStatsExpanded }
            )
            
            Spacer(modifier = Modifier.height(8.dp))

            // Toolbar
            LogToolbar(
                searchQuery = searchQuery,
                onSearchQueryChange = { logViewModel.onSearchQueryChange(it) },
                isSearchExpanded = isSearchExpanded,
                onSearchExpandedChange = { isSearchExpanded = it },
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
                sortOrder = sortOrder,
                onSortOrderChange = { sortOrder = it },
                isAutoScrollEnabled = isAutoScrollEnabled,
                onAutoScrollChange = { isAutoScrollEnabled = it },
                onClearLogs = { logViewModel.clearLogs() },
                onExportLogs = { format ->
                    Toast.makeText(context, "Exporting as ${format.name}...", Toast.LENGTH_SHORT).show()
                },
                logFile = logViewModel.getLogFile()
            )

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
            
            Spacer(modifier = Modifier.height(4.dp))

            // Log List
            if (filteredEntries.isEmpty()) {
                EmptyLogsView()
            } else {
                LazyColumnScrollbar(
                    state = listState,
                    settings = ScrollbarDefaults.defaultScrollbarSettings().copy(
                        thumbUnselectedColor = LogColorPalette.NeonCyan.copy(alpha = 0.3f),
                        thumbSelectedColor = LogColorPalette.NeonCyan.copy(alpha = 0.6f)
                    )
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 80.dp, top = 4.dp),
                        reverseLayout = true
                    ) {
                        itemsIndexed(
                            items = filteredEntries,
                            key = { index, logEntry -> "${index}_${logEntry.hashCode()}" }
                        ) { _, logEntry ->
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
        
        // Scroll to Top FAB
        AnimatedVisibility(
            visible = isUserScrolledAway.value && filteredEntries.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            enter = fadeIn() + slideInVertically(initialOffsetY = { 50 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { 50 })
        ) {
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(0)
                        isUserScrolledAway.value = false
                    }
                },
                containerColor = LogColorPalette.NeonCyan.copy(alpha = 0.15f),
                contentColor = LogColorPalette.NeonCyan,
                modifier = Modifier
                    .size(48.dp)
                    .border(1.dp, LogColorPalette.NeonCyan.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Scroll to latest"
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
            containerColor = Color(0xFF080810),
            contentColor = Color(0xFFE0E0E0),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(LogColorPalette.NeonCyan.copy(alpha = 0.5f))
                )
            }
        ) {
            LogDetailSheet(logEntry = logEntry)
        }
    }
}


@Composable
private fun FuturisticHeader(
    isLive: Boolean,
    logsPerSecond: Float,
    totalLogs: Int
) {
    val infiniteTransition = rememberInfiniteTransition(label = "header")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "borderAlpha"
    )
    
    val scanLineOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanLine"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Main header container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF0A0A10),
                            Color(0xFF0D0D15),
                            Color(0xFF0A0A10)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            LogColorPalette.NeonCyan.copy(alpha = borderAlpha * 0.3f),
                            LogColorPalette.NeonPurple.copy(alpha = borderAlpha * 0.5f),
                            LogColorPalette.NeonMagenta.copy(alpha = borderAlpha * 0.3f)
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side - Title
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Cyber icon
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(LogColorPalette.NeonCyan.copy(alpha = 0.1f))
                                .border(
                                    1.dp,
                                    LogColorPalette.NeonCyan.copy(alpha = 0.3f),
                                    RoundedCornerShape(8.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = LogColorPalette.NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Column {
                            Text(
                                text = "SYSTEM LOGS",
                                color = LogColorPalette.NeonCyan,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 3.sp,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "HYPERXRAY CORE v2.0",
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
                
                // Right side - Live indicator & count
                Column(horizontalAlignment = Alignment.End) {
                    LiveIndicator(
                        isLive = isLive,
                        logsPerSecond = logsPerSecond
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "$totalLogs ENTRIES",
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLogsView() {
    val infiniteTransition = rememberInfiniteTransition(label = "empty")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Animated icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(LogColorPalette.NeonCyan.copy(alpha = 0.05f))
                    .border(
                        width = 2.dp,
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                LogColorPalette.NeonCyan.copy(alpha = pulseAlpha),
                                Color.Transparent,
                                LogColorPalette.NeonPurple.copy(alpha = pulseAlpha),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = LogColorPalette.NeonCyan.copy(alpha = pulseAlpha),
                    modifier = Modifier.size(36.dp)
                )
            }
            
            Text(
                text = "[ NO DATA STREAM ]",
                color = Color.Gray.copy(alpha = pulseAlpha),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 2.sp
            )
            
            Text(
                text = "Waiting for log entries...",
                color = Color.DarkGray,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            
            // Animated dots
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { index ->
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = index * 200),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot$index"
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(LogColorPalette.NeonCyan.copy(alpha = dotAlpha))
                    )
                }
            }
        }
    }
}

/**
 * Calculate log statistics from entries
 */
private fun calculateLogStats(entries: List<String>): LogStats {
    if (entries.isEmpty()) return LogStats()
    
    var errorCount = 0
    var warnCount = 0
    var infoCount = 0
    var tcpCount = 0
    var udpCount = 0
    var dnsCount = 0
    var sniffCount = 0
    var aiCount = 0
    val domains = mutableSetOf<String>()
    
    entries.forEach { entry ->
        try {
            val level = parseLogLevel(entry)
            when (level) {
                LogLevel.ERROR -> errorCount++
                LogLevel.WARN -> warnCount++
                LogLevel.INFO -> infoCount++
                else -> {}
            }
            
            val connType = parseConnectionType(entry)
            when (connType) {
                ConnectionType.TCP -> tcpCount++
                ConnectionType.UDP -> udpCount++
                else -> {}
            }
            
            if (isDnsLog(entry)) dnsCount++
            if (isSniffingLog(entry)) sniffCount++
            if (isAiLog(entry)) aiCount++
            
            extractSniffedDomain(entry)?.let { domains.add(it) }
            extractDnsQuery(entry)?.let { domains.add(it) }
            extractSNI(entry)?.let { domains.add(it) }
        } catch (e: Exception) {
            // Skip problematic entries
        }
    }
    
    return LogStats(
        totalCount = entries.size,
        errorCount = errorCount,
        warnCount = warnCount,
        infoCount = infoCount,
        tcpCount = tcpCount,
        udpCount = udpCount,
        dnsCount = dnsCount,
        sniffCount = sniffCount,
        aiCount = aiCount,
        uniqueDomains = domains
    )
}
