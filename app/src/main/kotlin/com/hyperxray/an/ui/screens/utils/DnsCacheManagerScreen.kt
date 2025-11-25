package com.hyperxray.an.ui.screens.utils

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Log
import com.hyperxray.an.core.network.dns.DnsCacheManager
import com.hyperxray.an.core.network.dns.DnsCacheManager.DnsCacheEntryUiModel
import com.hyperxray.an.core.network.dns.DnsCacheManager.DnsCacheMetrics
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TAG = "DnsCacheManagerScreen"

// Sort options
enum class SortOption {
    NEWEST,
    MOST_HITS,
    LATENCY_HIGH_LOW
}

// Filter options
enum class FilterOption {
    ALL,
    CACHED,
    EXPIRED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsCacheManagerScreen() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    
    // Initialize DnsCacheManager
    LaunchedEffect(Unit) {
        try {
            DnsCacheManager.initialize(context.applicationContext)
            DnsCacheManager.ensureMetricsJobRunning()
            Log.d(TAG, "DnsCacheManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DnsCacheManager", e)
        }
    }
    
    // Collect metrics from StateFlow
    val metrics by DnsCacheManager.dashboardStats.collectAsStateWithLifecycle()
    
    // UI State
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(SortOption.NEWEST) }
    var filterOption by remember { mutableStateOf(FilterOption.ALL) }
    var showSearchBar by remember { mutableStateOf(true) }
    var showAnalytics by remember { mutableStateOf(true) }
    var selectedEntry by remember { mutableStateOf<DnsCacheEntryUiModel?>(null) }
    var swipedEntryKey by remember { mutableStateOf<String?>(null) }
    var swipeOffset by remember { mutableStateOf(0f) }
    
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    // Filter and sort entries
    val filteredAndSortedEntries = remember(metrics.activeEntries, searchQuery, sortOption, filterOption) {
        val currentTime = System.currentTimeMillis() / 1000
        var filtered = metrics.activeEntries
        
        // Apply search filter
        if (searchQuery.isNotBlank()) {
            filtered = filtered.filter { entry ->
                entry.domain.contains(searchQuery, ignoreCase = true) ||
                entry.ips.any { it.contains(searchQuery, ignoreCase = true) }
            }
        }
        
        // Apply status filter
        filtered = when (filterOption) {
            FilterOption.ALL -> filtered
            FilterOption.CACHED -> filtered.filter { it.expiryTime > currentTime }
            FilterOption.EXPIRED -> filtered.filter { it.expiryTime <= currentTime }
        }
        
        // Apply sort
        filtered = when (sortOption) {
            SortOption.NEWEST -> filtered.sortedByDescending { it.expiryTime }
            SortOption.MOST_HITS -> {
                // Sort by domain popularity (if available in metrics)
                val domainMap = metrics.topDomains.associateBy { it.domain }
                filtered.sortedByDescending { entry ->
                    domainMap[entry.domain]?.hits ?: 0L
                }
            }
            SortOption.LATENCY_HIGH_LOW -> {
                // Sort by average latency (if available)
                filtered.sortedByDescending { entry ->
                    // Use domain-specific latency if available, otherwise use global average
                    metrics.avgHitLatencyMs
                }
            }
        }
        
        filtered
    }
    
    val listState = rememberLazyListState()
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with title and clear button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DNS Inspector",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = Color.White
                )
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showSearchBar = !showSearchBar
                        }
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Toggle Search",
                            tint = if (showSearchBar) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }
                    
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                DnsCacheManager.clearCache()
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Clear Cache",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            // Analytics Card (Collapsible)
            AnimatedVisibility(
                visible = showAnalytics,
                enter = expandVertically(spring()) + fadeIn(),
                exit = shrinkVertically(spring()) + fadeOut()
            ) {
                DnsAnalyticsCard(
                    metrics = metrics,
                    modifier = Modifier.fillMaxWidth(),
                    onToggleCollapse = { showAnalytics = false }
                )
            }
            
            if (!showAnalytics) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAnalytics = true }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand Analytics",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Show Analytics",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Search Bar (Sticky)
            AnimatedVisibility(
                visible = showSearchBar,
                enter = expandVertically(spring()) + fadeIn(),
                exit = shrinkVertically(spring()) + fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search domain or IP...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    
                    // Filter Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChipCustom(
                            selected = filterOption == FilterOption.ALL,
                            onClick = { filterOption = FilterOption.ALL },
                            label = { Text("All") }
                        )
                        FilterChipCustom(
                            selected = filterOption == FilterOption.CACHED,
                            onClick = { filterOption = FilterOption.CACHED },
                            label = { Text("Cached") }
                        )
                        FilterChipCustom(
                            selected = filterOption == FilterOption.EXPIRED,
                            onClick = { filterOption = FilterOption.EXPIRED },
                            label = { Text("Expired") }
                        )
                    }
                    
                    // Sort Options
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Sort:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilterChipCustom(
                            selected = sortOption == SortOption.NEWEST,
                            onClick = { sortOption = SortOption.NEWEST },
                            label = { Text("Newest") }
                        )
                        FilterChipCustom(
                            selected = sortOption == SortOption.MOST_HITS,
                            onClick = { sortOption = SortOption.MOST_HITS },
                            label = { Text("Most Hits") }
                        )
                        FilterChipCustom(
                            selected = sortOption == SortOption.LATENCY_HIGH_LOW,
                            onClick = { sortOption = SortOption.LATENCY_HIGH_LOW },
                            label = { Text("Latency") }
                        )
                    }
                }
            }
            
            // Results count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Entries (${filteredAndSortedEntries.size})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = "Total: ${metrics.entryCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // DNS Entries List
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(
                    items = filteredAndSortedEntries,
                    key = { it.domain }
                ) { entry ->
                    DnsEntryItem(
                        entry = entry,
                        metrics = metrics,
                        onItemClick = {
                            selectedEntry = entry
                            scope.launch { sheetState.show() }
                        },
                        onSwipeToDelete = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Note: DnsCacheManager doesn't have a method to delete individual entries
                            // This would need to be implemented in DnsCacheManager
                            Log.d(TAG, "Delete entry: ${entry.domain}")
                        },
                        isSwiped = swipedEntryKey == entry.domain,
                        onSwipeStateChange = { isSwiped ->
                            swipedEntryKey = if (isSwiped) entry.domain else null
                        }
                    )
                }
                
                if (filteredAndSortedEntries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = if (searchQuery.isNotBlank()) "No entries found" else "No cached entries",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Detail Bottom Sheet
    selectedEntry?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = {
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    selectedEntry = null
                }
            },
            sheetState = sheetState,
            containerColor = Color(0xFF1A1A1A),
            contentColor = Color.White
        ) {
            DnsDetailSheet(
                entry = entry,
                metrics = metrics,
                onFlush = {
                    // Note: Individual entry flush would need to be implemented in DnsCacheManager
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        selectedEntry = null
                    }
                },
                onCopyIp = { ip ->
                    clipboard.setText(AnnotatedString(ip))
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            )
        }
    }
}

@Composable
private fun FilterChipCustom(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@Composable
fun DnsAnalyticsCard(
    metrics: DnsCacheMetrics,
    modifier: Modifier = Modifier,
    onToggleCollapse: () -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Analytics",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                IconButton(onClick = onToggleCollapse) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Collapse",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Donut Chart: Hit vs Miss
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                ) {
                    HitMissDonutChart(
                        hits = metrics.hits,
                        misses = metrics.misses,
                        hitRate = metrics.hitRate
                    )
                }
                
                // Sparkline: Average Latency Trend
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                ) {
                    LatencySparkline(
                        avgHitLatency = metrics.avgHitLatencyMs,
                        avgMissLatency = metrics.avgMissLatencyMs
                    )
                }
            }
            
            // Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnalyticsStatItem(
                    label = "Hit Rate",
                    value = "${metrics.hitRate}%",
                    modifier = Modifier.weight(1f)
                )
                AnalyticsStatItem(
                    label = "Avg Hit Latency",
                    value = "${metrics.avgHitLatencyMs.toInt()}ms",
                    modifier = Modifier.weight(1f)
                )
                AnalyticsStatItem(
                    label = "Avg Miss Latency",
                    value = "${metrics.avgMissLatencyMs.toInt()}ms",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun HitMissDonutChart(
    hits: Long,
    misses: Long,
    hitRate: Int,
    modifier: Modifier = Modifier
) {
    val total = hits + misses
    val hitRatio = if (total > 0) hits.toFloat() / total else 0f
    val missRatio = if (total > 0) misses.toFloat() / total else 0f
    
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val size = size.minDimension
            val strokeWidth = size * 0.15f
            val radius = (size - strokeWidth) / 2f
            val center = Offset(size / 2f, size / 2f)
            
            // Background circle
            drawCircle(
                color = Color(0xFF2A2A2A),
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            
            // Hit segment (green)
            if (hitRatio > 0) {
                drawArc(
                    color = Color(0xFF10B981),
                    startAngle = -90f,
                    sweepAngle = 360f * hitRatio,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            
            // Miss segment (red)
            if (missRatio > 0) {
                drawArc(
                    color = Color(0xFFEF4444),
                    startAngle = -90f + (360f * hitRatio),
                    sweepAngle = 360f * missRatio,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "$hitRate%",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF10B981)
            )
            Text(
                text = "Hit Rate",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun LatencySparkline(
    avgHitLatency: Double,
    avgMissLatency: Double,
    modifier: Modifier = Modifier
) {
    // Simulate trend data (in real implementation, this would come from historical data)
    val trendData = remember(avgHitLatency, avgMissLatency) {
        // Generate 10 data points simulating a trend
        val base = avgHitLatency.coerceIn(0.0, 500.0)
        (0..9).map { index ->
            base + (Math.sin(index * 0.5) * 20) + (Math.random() * 10 - 5)
        }
    }
    
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val padding = 16.dp.value
            
            if (trendData.isNotEmpty()) {
                val maxValue = trendData.maxOrNull() ?: 1.0
                val minValue = trendData.minOrNull() ?: 0.0
                val range = (maxValue - minValue).toFloat()
                
                val points = trendData.mapIndexed { index, value ->
                    val x = padding + (index * (width - padding * 2) / (trendData.size - 1))
                    val normalizedValue = if (range > 0f) ((value - minValue) / range).toFloat() else 0.5f
                    val y = height - padding - (normalizedValue * (height - padding * 2))
                    Offset(x, y)
                }
                
                // Draw line
                for (i in 0 until points.size - 1) {
                    drawLine(
                        color = Color(0xFF3B82F6),
                        start = points[i],
                        end = points[i + 1],
                        strokeWidth = 3.dp.value,
                        cap = StrokeCap.Round
                    )
                }
                
                // Draw points
                points.forEach { point ->
                    drawCircle(
                        color = Color(0xFF3B82F6),
                        radius = 4.dp.value,
                        center = point
                    )
                }
            }
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "${avgHitLatency.toInt()}ms",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF3B82F6)
            )
            Text(
                text = "Avg Latency",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AnalyticsStatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun DnsEntryItem(
    entry: DnsCacheEntryUiModel,
    metrics: DnsCacheMetrics,
    onItemClick: () -> Unit,
    onSwipeToDelete: () -> Unit,
    isSwiped: Boolean,
    onSwipeStateChange: (Boolean) -> Unit
) {
    val currentTime = System.currentTimeMillis() / 1000
    val isExpired = entry.expiryTime <= currentTime
    val timeRemaining = max(0L, entry.expiryTime - currentTime)
    // Use average TTL from metrics as reference, or default to 24 hours (86400 seconds)
    val referenceTtl = if (metrics.avgTtlSeconds > 0) metrics.avgTtlSeconds else 86400L
    val ttlProgress = if (referenceTtl > 0 && !isExpired) {
        (timeRemaining.toFloat() / referenceTtl).coerceIn(0f, 1f)
    } else 0f
    
    // Calculate average latency for this domain (simplified - use global average)
    val avgLatency = metrics.avgHitLatencyMs
    val latencyColor = when {
        avgLatency < 50 -> Color(0xFF10B981) // Green
        avgLatency < 200 -> Color(0xFFF59E0B) // Yellow
        else -> Color(0xFFEF4444) // Red
    }
    
    // Get domain hit count if available
    val domainStats = metrics.topDomains.find { it.domain == entry.domain }
    val hitCount = domainStats?.hits ?: 0L
    
    val maxSwipeDistance = 120.dp
    val animatedOffset by animateFloatAsState(
        targetValue = if (isSwiped) -maxSwipeDistance.value else 0f,
        animationSpec = spring(),
        label = "swipeOffset"
    )
    
    var swipeOffset by remember { mutableStateOf(0f) }
    
    // Reset swipe offset when not swiped
    LaunchedEffect(isSwiped) {
        if (!isSwiped) {
            swipeOffset = 0f
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset(x = if (isSwiped) animatedOffset.dp else swipeOffset.dp)
    ) {
        // Delete background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(16.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(32.dp)
            )
        }
        
        // Main card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(entry.domain) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(swipeOffset) > maxSwipeDistance.value * 0.5f) {
                                onSwipeStateChange(true)
                                onSwipeToDelete()
                            } else {
                                swipeOffset = 0f
                                onSwipeStateChange(false)
                            }
                        }
                    ) { change, dragAmount ->
                        if (!isSwiped) {
                            if (dragAmount < 0) { // Only allow left swipe
                                swipeOffset = (swipeOffset + dragAmount).coerceAtLeast(-maxSwipeDistance.value)
                            } else if (swipeOffset < 0) {
                                swipeOffset = (swipeOffset + dragAmount).coerceAtMost(0f)
                            }
                        }
                    }
                }
                .clickable(onClick = onItemClick),
            colors = CardDefaults.cardColors(
                containerColor = if (isExpired) Color(0xFF2A1A1A) else Color(0xFF1A1A1A)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Favicon/Icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = entry.domain.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Domain name
                    Text(
                        text = entry.domain,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    // IP addresses
                    Text(
                        text = entry.ips.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    // TTL Progress Bar
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = ttlProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = if (isExpired) Color(0xFFEF4444) else Color(0xFF10B981),
                            trackColor = Color(0xFF2A2A2A)
                        )
                        Text(
                            text = if (isExpired) {
                                "Expired"
                            } else {
                                formatTimeRemaining(timeRemaining)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isExpired) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Latency indicator
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(latencyColor)
                        )
                        Text(
                            text = "${avgLatency.toInt()}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = latencyColor
                        )
                    }
                    
                    // Hit count
                    if (hitCount > 0) {
                        Text(
                            text = "$hitCount hits",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DnsDetailSheet(
    entry: DnsCacheEntryUiModel,
    metrics: DnsCacheMetrics,
    onFlush: () -> Unit,
    onCopyIp: (String) -> Unit
) {
    val currentTime = System.currentTimeMillis() / 1000
    val isExpired = entry.expiryTime <= currentTime
    val timeRemaining = max(0L, entry.expiryTime - currentTime)
    val expiryDate = Date(entry.expiryTime * 1000)
    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
    
    val domainStats = metrics.topDomains.find { it.domain == entry.domain }
    val avgLatency = metrics.avgHitLatencyMs
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Domain Details",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        }
        
        HorizontalDivider(color = Color(0xFF2A2A2A))
        
        // Domain name
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Domain",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = entry.domain,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        }
        
        // IP Addresses
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "IP Addresses (${entry.ips.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            entry.ips.forEach { ip ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A2A))
                        .clickable { onCopyIp(ip) }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ip,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Copy",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        
        // TTL Information
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Time To Live",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (isExpired) {
                    "Expired"
                } else {
                    "Expires in ${formatTimeRemaining(timeRemaining)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isExpired) Color(0xFFEF4444) else Color.White
            )
            Text(
                text = "Expiry: ${dateFormat.format(expiryDate)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Statistics
        if (domainStats != null) {
            HorizontalDivider(color = Color(0xFF2A2A2A))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Statistics",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Hits:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${domainStats.hits}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Misses:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${domainStats.misses}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Hit Rate:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${domainStats.hitRate}%",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
        }
        
        // Average Latency
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Avg Latency:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${avgLatency.toInt()}ms",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Flush button
        Button(
            onClick = onFlush,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Flush This Record")
        }
    }
}

private fun formatTimeRemaining(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}
