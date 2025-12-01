package com.hyperxray.an.ui.scaffold

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import com.hyperxray.an.ui.theme.HyperIcons
import com.hyperxray.an.ui.components.HyperTopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.hyperxray.an.R
import com.hyperxray.an.common.ROUTE_CONFIG
import com.hyperxray.an.common.ROUTE_LOG
import com.hyperxray.an.common.ROUTE_UTILS
import com.hyperxray.an.common.ROUTE_SETTINGS
import com.hyperxray.an.common.ROUTE_STATS
import com.hyperxray.an.common.ROUTE_MAIN
import com.hyperxray.an.common.ROUTE_CONFIG_EDIT
import com.hyperxray.an.common.ROUTE_APP_LIST
import com.hyperxray.an.common.ROUTE_AI_INSIGHTS
import com.hyperxray.an.viewmodel.LogViewModel
import com.hyperxray.an.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    navController: NavHostController,
    snackbarHostState: SnackbarHostState,
    mainViewModel: MainViewModel,
    logViewModel: LogViewModel,
    onCreateNewConfigFileAndEdit: () -> Unit,
    onImportConfigFromClipboard: () -> Unit,
    onPerformExport: () -> Unit,
    onPerformBackup: () -> Unit,
    onPerformRestore: () -> Unit,
    onSwitchVpnService: () -> Unit,
    logListState: LazyListState,
    configListState: LazyListState,
    settingsScrollState: androidx.compose.foundation.ScrollState,
    content: @Composable (paddingValues: androidx.compose.foundation.layout.PaddingValues) -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var isLogSearching by remember { mutableStateOf(false) }
    val logSearchQuery by logViewModel.searchQuery.collectAsState()
    val focusRequester = remember { FocusRequester() }

    // Define root screens (screens that don't show back button)
    val rootScreens = remember {
        setOf(ROUTE_STATS, ROUTE_CONFIG, ROUTE_LOG, ROUTE_UTILS, ROUTE_SETTINGS, ROUTE_MAIN)
    }
    val isRootScreen = currentRoute in rootScreens

    // Initialize scroll behavior for "Enter Always" animation
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // Reset scroll behavior when route changes
    LaunchedEffect(currentRoute) {
        scrollBehavior.state.contentOffset = 0f
    }

    LaunchedEffect(isLogSearching) {
        if (isLogSearching) {
            focusRequester.requestFocus()
        }
    }

    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600 // Tablet threshold
    
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Obsidian background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF000000), // Pure obsidian black
                            Color(0xFF0A0A0A), // Slight gradient for depth
                            Color(0xFF000000)
                        )
                    )
                )
        )
        
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent, // Transparent to show obsidian background
            snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopAppBar(
                currentRoute = currentRoute,
                scrollBehavior = scrollBehavior,
                isRootScreen = isRootScreen,
                navController = navController,
                onCreateNewConfigFileAndEdit = onCreateNewConfigFileAndEdit,
                onImportConfigFromClipboard = onImportConfigFromClipboard,
                onPerformExport = onPerformExport,
                onPerformBackup = onPerformBackup,
                onPerformRestore = onPerformRestore,
                onSwitchVpnService = onSwitchVpnService,
                controlMenuClickable = mainViewModel.controlMenuClickable.collectAsState().value,
                isServiceEnabled = mainViewModel.isServiceEnabled.collectAsState().value,
                logViewModel = logViewModel,
                logListState = logListState,
                configListState = configListState,
                settingsScrollState = settingsScrollState,
                isLogSearching = isLogSearching,
                onLogSearchingChange = { isLogSearching = it },
                logSearchQuery = logSearchQuery,
                onLogSearchQueryChange = { logViewModel.onSearchQueryChange(it) },
                focusRequester = focusRequester,
                mainViewModel = mainViewModel
            )
        },
        bottomBar = if (!isTablet) {
            { AppBottomNavigationBar(navController) }
        } else {
            { /* No bottom bar on tablets - using NavigationRail instead */ }
        },
        contentWindowInsets = WindowInsets(0)
    ) { paddingValues ->
        if (isTablet) {
            // Tablet layout with NavigationRail
            Row(
                modifier = Modifier.fillMaxSize()
            ) {
                AppNavigationRail(navController)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                ) {
                    content(paddingValues)
                }
            }
        } else {
            // Phone layout
            content(paddingValues)
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopAppBar(
    currentRoute: String?,
    scrollBehavior: TopAppBarScrollBehavior,
    isRootScreen: Boolean,
    navController: NavHostController,
    onCreateNewConfigFileAndEdit: () -> Unit,
    onImportConfigFromClipboard: () -> Unit,
    onPerformExport: () -> Unit,
    onPerformBackup: () -> Unit,
    onPerformRestore: () -> Unit,
    onSwitchVpnService: () -> Unit,
    controlMenuClickable: Boolean,
    isServiceEnabled: Boolean,
    logViewModel: LogViewModel,
    logListState: LazyListState,
    configListState: LazyListState,
    settingsScrollState: androidx.compose.foundation.ScrollState,
    isLogSearching: Boolean = false,
    onLogSearchingChange: (Boolean) -> Unit = {},
    logSearchQuery: String = "",
    onLogSearchQueryChange: (String) -> Unit = {},
    focusRequester: FocusRequester = FocusRequester(),
    mainViewModel: MainViewModel
) {
    val title = when (currentRoute) {
        ROUTE_STATS -> stringResource(R.string.core_stats_title)
        ROUTE_CONFIG -> stringResource(R.string.configuration)
        ROUTE_LOG -> stringResource(R.string.log)
        ROUTE_UTILS -> "Utils"
        ROUTE_SETTINGS -> stringResource(R.string.settings)
        ROUTE_CONFIG_EDIT -> stringResource(R.string.configuration)
        ROUTE_APP_LIST -> "App List"
        ROUTE_AI_INSIGHTS -> "AI Insights"
        else -> stringResource(R.string.app_name)
    }

    val colorScheme = MaterialTheme.colorScheme

    // Handle navigation icon click
    val onNavigationIconClick: (() -> Unit)? = when {
        // Log search mode: close search
        currentRoute == ROUTE_LOG && isLogSearching -> {
            {
                onLogSearchingChange(false)
                onLogSearchQueryChange("")
            }
        }
        // Detail screens: show back button
        !isRootScreen -> {
            { navController.popBackStack() }
        }
        // Root screens: no navigation icon (or could show menu if you have a drawer)
        else -> null
    }

    // Custom title for log search mode
    val titleContent: @Composable () -> Unit = {
        if (currentRoute == ROUTE_LOG && isLogSearching) {
            TextField(
                value = logSearchQuery,
                onValueChange = onLogSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { 
                    Text(
                        text = stringResource(R.string.search),
                        style = MaterialTheme.typography.bodyLarge
                    ) 
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = colorScheme.onSurface,
                    unfocusedTextColor = colorScheme.onSurface,
                    focusedPlaceholderColor = colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = colorScheme.onSurfaceVariant
                )
            )
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp
                ),
                color = Color.White
            )
        }
    }

    // Custom actions
    val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (currentRoute == ROUTE_LOG && isLogSearching) {
            if (logSearchQuery.isNotEmpty()) {
                IconButton(
                    onClick = { onLogSearchQueryChange("") },
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Icon(
                        HyperIcons.Clear,
                        contentDescription = stringResource(R.string.clear_search),
                        tint = colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            TopAppBarActions(
                currentRoute = currentRoute,
                onCreateNewConfigFileAndEdit = onCreateNewConfigFileAndEdit,
                onImportConfigFromClipboard = onImportConfigFromClipboard,
                onPerformExport = onPerformExport,
                onPerformBackup = onPerformBackup,
                onPerformRestore = onPerformRestore,
                onSwitchVpnService = onSwitchVpnService,
                controlMenuClickable = controlMenuClickable,
                isServiceEnabled = isServiceEnabled,
                logViewModel = logViewModel,
                onLogSearchingChange = onLogSearchingChange,
                mainViewModel = mainViewModel
            )
        }
    }

    // Use HyperTopAppBar for standard cases, but handle log search specially
    if (currentRoute == ROUTE_LOG && isLogSearching) {
        // Special case: log search mode - use custom implementation
        val showScrolledColor = remember(
            logListState
        ) {
            derivedStateOf {
                logListState.firstVisibleItemIndex > 0 || logListState.firstVisibleItemScrollOffset > 0
            }
        }

        val elevation by animateFloatAsState(
            targetValue = if (showScrolledColor.value) 3f else 0f,
            animationSpec = tween(durationMillis = 300),
            label = "topbar_elevation"
        )

        val containerColor by animateFloatAsState(
            targetValue = if (showScrolledColor.value) 1f else 0f,
            animationSpec = tween(durationMillis = 300),
            label = "topbar_color"
        )

        val animatedContainerColor = androidx.compose.ui.graphics.lerp(
            Color(0xFF000000).copy(alpha = 0.7f),
            Color(0xFF0A0A0A).copy(alpha = 0.8f),
            containerColor
        )

        val roundedShape = remember(showScrolledColor.value) {
            RoundedCornerShape(
                bottomStart = if (showScrolledColor.value) 16.dp else 0.dp,
                bottomEnd = if (showScrolledColor.value) 16.dp else 0.dp
            )
        }

        Surface(
            modifier = Modifier
                .shadow(
                    elevation = elevation.dp,
                    shape = roundedShape,
                    spotColor = colorScheme.primary.copy(alpha = 0.1f),
                    ambientColor = colorScheme.primary.copy(alpha = 0.05f)
                ),
            color = animatedContainerColor,
            tonalElevation = if (showScrolledColor.value) 2.dp else 0.dp,
            shape = roundedShape
        ) {
            CenterAlignedTopAppBar(
                title = titleContent,
                navigationIcon = {
                    onNavigationIconClick?.let {
                        IconButton(onClick = it) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.close_search),
                                tint = Color.White
                            )
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = animatedContainerColor,
                    scrolledContainerColor = animatedContainerColor,
                    navigationIconContentColor = Color.White,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                scrollBehavior = scrollBehavior
            )
        }
    } else {
        // Standard case: use HyperTopAppBar
        HyperTopAppBar(
            title = title,
            scrollBehavior = scrollBehavior,
            onNavigationIconClick = onNavigationIconClick,
            isBackEnabled = !isRootScreen,
            actions = actions
        )
    }
}

@Composable
private fun TopAppBarActions(
    currentRoute: String?,
    onCreateNewConfigFileAndEdit: () -> Unit,
    onImportConfigFromClipboard: () -> Unit,
    onPerformExport: () -> Unit,
    onPerformBackup: () -> Unit,
    onPerformRestore: () -> Unit,
    onSwitchVpnService: () -> Unit,
    controlMenuClickable: Boolean,
    isServiceEnabled: Boolean,
    logViewModel: LogViewModel,
    onLogSearchingChange: (Boolean) -> Unit = {},
    mainViewModel: MainViewModel
) {
    when (currentRoute) {
        "config" -> ConfigActions(
            onCreateNewConfigFileAndEdit = onCreateNewConfigFileAndEdit,
            onImportConfigFromClipboard = onImportConfigFromClipboard,
            onSwitchVpnService = onSwitchVpnService,
            controlMenuClickable = controlMenuClickable,
            isServiceEnabled = isServiceEnabled,
            mainViewModel = mainViewModel
        )

        "stats" -> StatsActions(
            onSwitchVpnService = onSwitchVpnService,
            controlMenuClickable = controlMenuClickable,
            isServiceEnabled = isServiceEnabled,
            mainViewModel = mainViewModel
        )

        "log" -> LogActions(
            onPerformExport = onPerformExport,
            logViewModel = logViewModel,
            onLogSearchingChange = onLogSearchingChange
        )

        "settings" -> SettingsActions(
            onPerformBackup = onPerformBackup,
            onPerformRestore = onPerformRestore
        )
    }
}

@Composable
private fun ConfigActions(
    onCreateNewConfigFileAndEdit: () -> Unit,
    onImportConfigFromClipboard: () -> Unit,
    onSwitchVpnService: () -> Unit,
    controlMenuClickable: Boolean,
    isServiceEnabled: Boolean,
    mainViewModel: MainViewModel
) {
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    IconButton(
        onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onSwitchVpnService()
        },
        enabled = controlMenuClickable
    ) {
        Icon(
            painter = painterResource(
                id = if (isServiceEnabled) R.drawable.pause else R.drawable.play
            ),
            contentDescription = null
        )
    }

    IconButton(
        onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            expanded = true
        }
    ) {
        Icon(
            HyperIcons.MoreVert,
            contentDescription = stringResource(R.string.more)
        )
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.new_profile)) },
            onClick = {
                onCreateNewConfigFileAndEdit()
                expanded = false
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.import_from_clipboard)) },
            onClick = {
                expanded = false
                scope.launch {
                    delay(100)
                    onImportConfigFromClipboard()
                }
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.connectivity_test)) },
            onClick = {
                mainViewModel.testConnectivity()
                expanded = false
            },
            enabled = true
        )
    }
}

@Composable
private fun LogActions(
    onPerformExport: () -> Unit,
    logViewModel: LogViewModel,
    onLogSearchingChange: (Boolean) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val hasLogsToExport by logViewModel.hasLogsToExport.collectAsStateWithLifecycle()
    val hapticFeedback = LocalHapticFeedback.current
    // Use hasLogsToExport instead of collecting entire logEntries list to prevent memory leak
    // hasLogsToExport is true when logs exist, which is sufficient for UI state
    val hasLogs = hasLogsToExport

    IconButton(
        onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onLogSearchingChange(true)
        }
    ) {
        Icon(
            painterResource(id = R.drawable.search),
            contentDescription = stringResource(R.string.search)
        )
    }
    IconButton(
        onClick = { showClearDialog = true },
        enabled = hasLogs
    ) {
        Icon(
            HyperIcons.Delete,
            contentDescription = "Clear logs"
        )
    }
    IconButton(
        onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            expanded = true
        }
    ) {
        Icon(
            HyperIcons.MoreVert,
            contentDescription = stringResource(R.string.more)
        )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.export)) },
            onClick = {
                onPerformExport()
                expanded = false
            },
            enabled = hasLogsToExport
        )
    }
    
    // Clear logs confirmation dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Logs") },
            text = { Text("Are you sure you want to clear all logs? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        logViewModel.clearLogs()
                        showClearDialog = false
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearDialog = false }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsActions(
    onPerformBackup: () -> Unit,
    onPerformRestore: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    IconButton(
        onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            expanded = true
        }
    ) {
        Icon(
            HyperIcons.MoreVert,
            contentDescription = stringResource(R.string.more)
        )
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.backup)) },
            onClick = {
                onPerformBackup()
                expanded = false
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.restore)) },
            onClick = {
                onPerformRestore()
                expanded = false
            }
        )
    }
}

@Composable
private fun StatsActions(
    onSwitchVpnService: () -> Unit,
    controlMenuClickable: Boolean,
    isServiceEnabled: Boolean,
    mainViewModel: MainViewModel
) {
    var expanded by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    IconButton(
        onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onSwitchVpnService()
        },
        enabled = controlMenuClickable
    ) {
        Icon(
            painter = painterResource(
                id = if (isServiceEnabled) R.drawable.pause else R.drawable.play
            ),
            contentDescription = null
        )
    }

    IconButton(
        onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            expanded = true
        }
    ) {
        Icon(
            HyperIcons.MoreVert,
            contentDescription = stringResource(R.string.more)
        )
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.connectivity_test)) },
            onClick = {
                mainViewModel.testConnectivity()
                expanded = false
            },
            enabled = true
        )
    }
}

@Composable
fun AppBottomNavigationBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val hapticFeedback = LocalHapticFeedback.current
    
    // Futuristic neon colors
    val neonCyan = Color(0xFF00F5FF)
    val neonMagenta = Color(0xFFFF00FF)
    val neonPurple = Color(0xFF8B5CF6)
    val deepSpace = Color(0xFF000011)
    val glassWhite = Color(0x15FFFFFF)
    
    // Animated glow effect
    val infiniteTransition = rememberInfiniteTransition(label = "navbarGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "navGlowAlpha"
    )
    
    // Animated gradient offset for holographic effect
    val gradientOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gradientOffset"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Outer glow layer
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .graphicsLayer { alpha = glowAlpha }
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            neonCyan.copy(alpha = 0.2f),
                            neonMagenta.copy(alpha = 0.1f),
                            Color.Transparent
                        ),
                        radius = 600f
                    ),
                    shape = RoundedCornerShape(28.dp)
                )
        )
        
        // Main navbar container with glassmorphism
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(28.dp),
                    spotColor = neonCyan.copy(alpha = 0.3f),
                    ambientColor = neonMagenta.copy(alpha = 0.15f)
                ),
            color = deepSpace.copy(alpha = 0.85f),
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                glassWhite,
                                Color.Transparent,
                                glassWhite.copy(alpha = 0.05f)
                            )
                        )
                    )
                    .drawBehind {
                        // Animated top border glow
                        drawLine(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    neonCyan.copy(alpha = 0.8f * gradientOffset),
                                    neonMagenta.copy(alpha = 0.6f),
                                    neonPurple.copy(alpha = 0.8f * (1f - gradientOffset)),
                                    Color.Transparent
                                )
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 2f
                        )
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FuturisticNavItem(
                        route = ROUTE_STATS,
                        currentRoute = currentRoute,
                        icon = R.drawable.dashboard,
                        label = stringResource(R.string.core_stats_title),
                        neonColor = neonCyan,
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            navigateToRoute(navController, ROUTE_STATS)
                        }
                    )
                    FuturisticNavItem(
                        route = ROUTE_CONFIG,
                        currentRoute = currentRoute,
                        icon = R.drawable.code,
                        label = stringResource(R.string.configuration),
                        neonColor = neonMagenta,
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            navigateToRoute(navController, ROUTE_CONFIG)
                        }
                    )
                    FuturisticNavItem(
                        route = ROUTE_LOG,
                        currentRoute = currentRoute,
                        icon = R.drawable.history,
                        label = stringResource(R.string.log),
                        neonColor = neonPurple,
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            navigateToRoute(navController, ROUTE_LOG)
                        }
                    )
                    FuturisticNavItem(
                        route = ROUTE_UTILS,
                        currentRoute = currentRoute,
                        icon = R.drawable.utils,
                        label = "Utils",
                        neonColor = Color(0xFF00FF88), // Neon Green
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            navigateToRoute(navController, ROUTE_UTILS)
                        }
                    )
                    FuturisticNavItem(
                        route = ROUTE_SETTINGS,
                        currentRoute = currentRoute,
                        icon = R.drawable.settings,
                        label = stringResource(R.string.settings),
                        neonColor = Color(0xFFFF6B00), // Neon Orange
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            navigateToRoute(navController, ROUTE_SETTINGS)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.FuturisticNavItem(
    route: String,
    currentRoute: String?,
    icon: Int,
    label: String,
    neonColor: Color,
    onClick: () -> Unit
) {
    val isSelected = currentRoute == route
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // Neon glow animation for selected state
    val infiniteTransition = rememberInfiniteTransition(label = "navItemGlow_$route")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse_$route"
    )

    // Animation values
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.88f
            isSelected -> 1.05f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = 350f
        ),
        label = "nav_item_scale"
    )

    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.2f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 400f
        ),
        label = "icon_scale"
    )

    val containerAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "container_alpha"
    )

    val colorTransition by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "color_transition"
    )

    val inactiveColor = Color(0xFF6B7280) // Muted gray
    val animatedIconColor = androidx.compose.ui.graphics.lerp(inactiveColor, neonColor, colorTransition)
    val animatedTextColor = androidx.compose.ui.graphics.lerp(inactiveColor.copy(alpha = 0.7f), neonColor, colorTransition)

    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .scale(scale)
            .padding(vertical = 2.dp, horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        // Neon glow background for selected state
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .graphicsLayer { alpha = containerAlpha * glowPulse * 0.4f }
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                neonColor.copy(alpha = 0.5f),
                                neonColor.copy(alpha = 0.2f),
                                Color.Transparent
                            ),
                            radius = 120f
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
            )
        }
        
        // Glass container background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(20.dp))
                .graphicsLayer { alpha = containerAlpha }
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            neonColor.copy(alpha = 0.15f),
                            neonColor.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )
                .drawBehind {
                    // Neon border glow
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                neonColor.copy(alpha = 0.8f * glowPulse),
                                neonColor.copy(alpha = 0.3f),
                                neonColor.copy(alpha = 0.6f * glowPulse)
                            )
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                        style = Stroke(width = 1.5f)
                    )
                }
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .graphicsLayer { 
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                contentAlignment = Alignment.Center
            ) {
                // Icon glow layer for selected state
                if (isSelected) {
                    Icon(
                        painter = painterResource(id = icon),
                        contentDescription = null,
                        modifier = Modifier
                            .size(26.dp)
                            .graphicsLayer { alpha = glowPulse * 0.6f }
                            .blur(4.dp),
                        tint = neonColor
                    )
                }
                // Main icon
                Icon(
                    painter = painterResource(id = icon),
                    contentDescription = label,
                    modifier = Modifier.size(24.dp),
                    tint = animatedIconColor
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Text with glow effect for selected
            Box {
                if (isSelected) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = neonColor.copy(alpha = glowPulse * 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.blur(3.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 9.sp,
                        letterSpacing = if (isSelected) 0.5.sp else 0.sp
                    ),
                    color = animatedTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Top neon indicator line for selected
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 2.dp)
                    .width(24.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                neonColor,
                                Color.Transparent
                            )
                        )
                    )
                    .graphicsLayer { alpha = glowPulse }
            )
        }
    }
}

@Composable
fun AppNavigationRail(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val hapticFeedback = LocalHapticFeedback.current
    
    // Futuristic neon colors
    val neonCyan = Color(0xFF00F5FF)
    val neonMagenta = Color(0xFFFF00FF)
    val neonPurple = Color(0xFF8B5CF6)
    val deepSpace = Color(0xFF000011)
    val glassWhite = Color(0x15FFFFFF)
    
    // Animated glow
    val infiniteTransition = rememberInfiniteTransition(label = "railGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "railGlowAlpha"
    )

    Box(
        modifier = Modifier
            .width(88.dp)
            .fillMaxSize()
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
    ) {
        // Outer glow
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = glowAlpha * 0.5f }
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            neonCyan.copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(28.dp)
                )
        )
        
        Surface(
            modifier = Modifier
                .width(80.dp)
                .fillMaxSize()
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(28.dp),
                    spotColor = neonCyan.copy(alpha = 0.25f),
                    ambientColor = neonMagenta.copy(alpha = 0.1f)
                ),
            color = deepSpace.copy(alpha = 0.9f),
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                glassWhite,
                                Color.Transparent,
                                glassWhite.copy(alpha = 0.05f)
                            )
                        )
                    )
                    .drawBehind {
                        // Animated side border glow
                        drawLine(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    neonCyan.copy(alpha = 0.6f * glowAlpha),
                                    neonMagenta.copy(alpha = 0.4f),
                                    neonPurple.copy(alpha = 0.6f * glowAlpha),
                                    Color.Transparent
                                )
                            ),
                            start = Offset(size.width - 1f, 0f),
                            end = Offset(size.width - 1f, size.height),
                            strokeWidth = 2f
                        )
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 16.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FuturisticRailItem(
                        route = ROUTE_STATS,
                        currentRoute = currentRoute,
                        icon = R.drawable.dashboard,
                        label = stringResource(R.string.core_stats_title),
                        neonColor = neonCyan,
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            navigateToRoute(navController, ROUTE_STATS)
                        }
                    )
                    FuturisticRailItem(
                        route = ROUTE_CONFIG,
                        currentRoute = currentRoute,
                        icon = R.drawable.code,
                        label = stringResource(R.string.configuration),
                        neonColor = neonMagenta,
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            navigateToRoute(navController, ROUTE_CONFIG)
                        }
                    )
                    FuturisticRailItem(
                        route = ROUTE_LOG,
                        currentRoute = currentRoute,
                        icon = R.drawable.history,
                        label = stringResource(R.string.log),
                        neonColor = neonPurple,
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            navigateToRoute(navController, ROUTE_LOG)
                        }
                    )
                    FuturisticRailItem(
                        route = ROUTE_UTILS,
                        currentRoute = currentRoute,
                        icon = R.drawable.utils,
                        label = "Utils",
                        neonColor = Color(0xFF00FF88),
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            navigateToRoute(navController, ROUTE_UTILS)
                        }
                    )
                    FuturisticRailItem(
                        route = ROUTE_SETTINGS,
                        currentRoute = currentRoute,
                        icon = R.drawable.settings,
                        label = stringResource(R.string.settings),
                        neonColor = Color(0xFFFF6B00),
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            navigateToRoute(navController, ROUTE_SETTINGS)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FuturisticRailItem(
    route: String,
    currentRoute: String?,
    icon: Int,
    label: String,
    neonColor: Color,
    onClick: () -> Unit
) {
    val isSelected = currentRoute == route
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // Neon glow animation
    val infiniteTransition = rememberInfiniteTransition(label = "railItemGlow_$route")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "railGlowPulse_$route"
    )

    // Animation values
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.9f
            isSelected -> 1.05f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = 350f
        ),
        label = "rail_item_scale"
    )

    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.25f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 400f
        ),
        label = "rail_icon_scale"
    )

    val containerAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "rail_container_alpha"
    )

    val colorTransition by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "rail_color_transition"
    )

    val inactiveColor = Color(0xFF6B7280)
    val animatedIconColor = androidx.compose.ui.graphics.lerp(inactiveColor, neonColor, colorTransition)

    Box(
        modifier = Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .scale(scale)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        // Neon glow background
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .graphicsLayer { alpha = containerAlpha * glowPulse * 0.4f }
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                neonColor.copy(alpha = 0.5f),
                                neonColor.copy(alpha = 0.2f),
                                Color.Transparent
                            ),
                            radius = 100f
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
            )
        }
        
        // Glass container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .graphicsLayer { alpha = containerAlpha }
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            neonColor.copy(alpha = 0.15f),
                            neonColor.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )
                .drawBehind {
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                neonColor.copy(alpha = 0.8f * glowPulse),
                                neonColor.copy(alpha = 0.3f),
                                neonColor.copy(alpha = 0.6f * glowPulse)
                            )
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                        style = Stroke(width = 1.5f)
                    )
                }
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .graphicsLayer { 
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                contentAlignment = Alignment.Center
            ) {
                // Icon glow
                if (isSelected) {
                    Icon(
                        painter = painterResource(id = icon),
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .graphicsLayer { alpha = glowPulse * 0.6f }
                            .blur(4.dp),
                        tint = neonColor
                    )
                }
                Icon(
                    painter = painterResource(id = icon),
                    contentDescription = label,
                    modifier = Modifier.size(26.dp),
                    tint = animatedIconColor
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Box {
                if (isSelected) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            letterSpacing = 0.3.sp
                        ),
                        color = neonColor.copy(alpha = glowPulse * 0.5f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.blur(3.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 9.sp,
                        letterSpacing = if (isSelected) 0.3.sp else 0.sp
                    ),
                    color = animatedIconColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        // Left neon indicator
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = 0.dp)
                    .width(3.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                neonColor,
                                Color.Transparent
                            )
                        )
                    )
                    .graphicsLayer { alpha = glowPulse }
            )
        }
    }
}

private fun navigateToRoute(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
