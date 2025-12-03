package com.hyperxray.an.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyperxray.an.ui.screens.log.*
import com.hyperxray.an.ui.theme.ScrollbarDefaults
import com.hyperxray.an.viewmodel.LogViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.nanihadesuka.compose.LazyColumnScrollbar

/**
 * Wrapper class for log entries with stable unique IDs.
 * This ensures Compose can efficiently track and recycle items.
 */
@Immutable
data class StableLogEntry(
    val id: Long,
    val content: String
)

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
    val detailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val optionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
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
    var showOptionsSheet by remember { mutableStateOf(false) }
    
    // OPTIMIZATION 1: Create stable log entries with unique IDs
    val stableEntries = remember(filteredEntries) {
        filteredEntries.mapIndexed { index, content ->
            StableLogEntry(
                id = (filteredEntries.size - index).toLong() * 31 + content.hashCode().toLong(),
                content = content
            )
        }
    }
    
    // OPTIMIZATION 2: Move stats calculation to background thread
    var stats by remember { mutableStateOf(LogStats()) }
    LaunchedEffect(filteredEntries) {
        withContext(Dispatchers.Default) {
            val calculatedStats = calculateLogStats(filteredEntries)
            withContext(Dispatchers.Main) {
                stats = calculatedStats
            }
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
    val isScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    
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

    // Active filters count for badge
    val activeFiltersCount = remember(selectedLogLevel, selectedConnectionType, showSniffingOnly, showAiOnly) {
        var count = 0
        if (selectedLogLevel != null) count++
        if (selectedConnectionType != null) count++
        if (showSniffingOnly) count++
        if (showAiOnly) count++
        count
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF030308))
    ) {
        AnimatedBackground(isScrolling = isScrolling)
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Futuristic Header v2.1
            FuturisticHeaderV2(
                isLive = isLive,
                logsPerSecond = logsPerSecond,
                totalLogs = stats.totalCount,
                activeFiltersCount = activeFiltersCount,
                onOptionsClick = { showOptionsSheet = true }
            )
            
            // Compact Stats Panel
            LogStatsPanel(
                stats = stats.copy(logsPerSecond = logsPerSecond),
                isExpanded = isStatsExpanded,
                onToggleExpand = { isStatsExpanded = !isStatsExpanded }
            )
            
            Spacer(modifier = Modifier.height(8.dp))

            // Simplified Toolbar v2.1
            LogToolbarV2(
                searchQuery = searchQuery,
                onSearchQueryChange = { logViewModel.onSearchQueryChange(it) },
                isSearchExpanded = isSearchExpanded,
                onSearchExpandedChange = { isSearchExpanded = it },
                isAutoScrollEnabled = isAutoScrollEnabled,
                onAutoScrollChange = { isAutoScrollEnabled = it },
                onClearLogs = { logViewModel.clearLogs() },
                logFile = logViewModel.getLogFile()
            )

            // Active Filters Chips (quick access)
            if (activeFiltersCount > 0) {
                ActiveFiltersBar(
                    selectedLogLevel = selectedLogLevel,
                    selectedConnectionType = selectedConnectionType,
                    showSniffingOnly = showSniffingOnly,
                    showAiOnly = showAiOnly,
                    onClearLogLevel = { selectedLogLevel = null },
                    onClearConnectionType = { selectedConnectionType = null },
                    onClearSniffing = { showSniffingOnly = false },
                    onClearAi = { showAiOnly = false },
                    onClearAll = {
                        selectedLogLevel = null
                        selectedConnectionType = null
                        showSniffingOnly = false
                        showAiOnly = false
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))

            // Log List
            if (stableEntries.isEmpty()) {
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
                        items(
                            items = stableEntries,
                            key = { entry -> entry.id },
                            contentType = { "log_entry" }
                        ) { stableEntry ->
                            LogEntryItem(
                                logEntry = stableEntry.content,
                                onClick = {
                                    selectedLogEntry = stableEntry.content
                                    scope.launch { detailSheetState.show() }
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

    // Log Detail Sheet
    selectedLogEntry?.let { logEntry ->
        ModalBottomSheet(
            onDismissRequest = {
                scope.launch { detailSheetState.hide() }.invokeOnCompletion { selectedLogEntry = null }
            },
            sheetState = detailSheetState,
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

    // Options Modal Sheet v2.1
    if (showOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsSheet = false },
            sheetState = optionsSheetState,
            containerColor = Color(0xFF080810),
            contentColor = Color(0xFFE0E0E0),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(LogColorPalette.NeonPurple.copy(alpha = 0.5f))
                )
            }
        ) {
            LogOptionsSheet(
                selectedLogLevel = selectedLogLevel,
                selectedConnectionType = selectedConnectionType,
                showSniffingOnly = showSniffingOnly,
                showAiOnly = showAiOnly,
                viewMode = viewMode,
                sortOrder = sortOrder,
                onLogLevelSelected = { selectedLogLevel = it },
                onConnectionTypeSelected = { selectedConnectionType = it },
                onShowSniffingOnlyChanged = { showSniffingOnly = it },
                onShowAiOnlyChanged = { showAiOnly = it },
                onViewModeChange = { viewMode = it },
                onSortOrderChange = { sortOrder = it },
                onExportLogs = { format ->
                    Toast.makeText(context, "Exporting as ${format.name}...", Toast.LENGTH_SHORT).show()
                },
                logFile = logViewModel.getLogFile(),
                onDismiss = { showOptionsSheet = false }
            )
        }
    }
}


@Composable
private fun FuturisticHeaderV2(
    isLive: Boolean,
    logsPerSecond: Float,
    totalLogs: Int,
    activeFiltersCount: Int,
    onOptionsClick: () -> Unit
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
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
                                text = "HYPERXRAY CORE v2.1",
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
                
                // Right side - Options button & Live indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                    
                    // Options Button with badge
                    Box {
                        IconButton(
                            onClick = onOptionsClick,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(LogColorPalette.NeonPurple.copy(alpha = 0.1f))
                                .border(
                                    1.dp,
                                    LogColorPalette.NeonPurple.copy(alpha = 0.3f),
                                    RoundedCornerShape(10.dp)
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Options",
                                tint = LogColorPalette.NeonPurple,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        
                        // Filter count badge
                        if (activeFiltersCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(LogColorPalette.NeonMagenta),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = activeFiltersCount.toString(),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
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

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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

@Composable
private fun AnimatedBackground(isScrolling: Boolean) {
    val targetAlpha = if (isScrolling) 0f else 1f
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(300),
        label = "bgAlpha"
    )
    
    if (animatedAlpha > 0.01f) {
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
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = animatedAlpha }
                .drawBehind {
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
        )
    }
}

@Composable
private fun ActiveFiltersBar(
    selectedLogLevel: LogLevel?,
    selectedConnectionType: ConnectionType?,
    showSniffingOnly: Boolean,
    showAiOnly: Boolean,
    onClearLogLevel: () -> Unit,
    onClearConnectionType: () -> Unit,
    onClearSniffing: () -> Unit,
    onClearAi: () -> Unit,
    onClearAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "ACTIVE:",
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 1.sp
        )
        
        selectedLogLevel?.let {
            FilterChipRemovable(
                text = it.name,
                color = LogColorPalette.getLogLevelColor(it),
                onRemove = onClearLogLevel
            )
        }
        
        selectedConnectionType?.let {
            FilterChipRemovable(
                text = it.name,
                color = LogColorPalette.getConnectionTypeColor(it),
                onRemove = onClearConnectionType
            )
        }
        
        if (showSniffingOnly) {
            FilterChipRemovable(
                text = "SNIFF",
                color = LogColorPalette.NeonOrange,
                onRemove = onClearSniffing
            )
        }
        
        if (showAiOnly) {
            FilterChipRemovable(
                text = "AI",
                color = LogColorPalette.NeonPurple,
                onRemove = onClearAi
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Text(
            text = "CLEAR ALL",
            color = LogColorPalette.NeonRed.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp,
            modifier = Modifier.clickable { onClearAll() }
        )
    }
}

@Composable
private fun FilterChipRemovable(
    text: String,
    color: Color,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp
        )
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Remove",
            tint = color.copy(alpha = 0.7f),
            modifier = Modifier
                .size(12.dp)
                .clickable { onRemove() }
        )
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
