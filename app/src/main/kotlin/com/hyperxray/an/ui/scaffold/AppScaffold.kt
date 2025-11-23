package com.hyperxray.an.ui.scaffold

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
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
import com.hyperxray.an.viewmodel.LogViewModel
import com.hyperxray.an.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent, // Transparent to show obsidian background
            snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopAppBar(
                currentRoute,
                onCreateNewConfigFileAndEdit,
                onImportConfigFromClipboard,
                onPerformExport,
                onPerformBackup,
                onPerformRestore,
                onSwitchVpnService,
                mainViewModel.controlMenuClickable.collectAsState().value,
                mainViewModel.isServiceEnabled.collectAsState().value,
                logViewModel,
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
        "stats" -> stringResource(R.string.core_stats_title)
        "config" -> stringResource(R.string.configuration)
        "log" -> stringResource(R.string.log)
        "utils" -> "Utils"
        "settings" -> stringResource(R.string.settings)
        else -> stringResource(R.string.app_name)
    }

    val showScrolledColor by remember(
        currentRoute,
        logListState,
        configListState,
        settingsScrollState
    ) {
        derivedStateOf {
            when (currentRoute) {
                "log" -> logListState.firstVisibleItemIndex > 0 || logListState.firstVisibleItemScrollOffset > 0
                "config" -> configListState.firstVisibleItemIndex > 0 || configListState.firstVisibleItemScrollOffset > 0
                "utils" -> false // Utils screen might scroll, but let's keep it false for now or update later
                "settings" -> settingsScrollState.value > 0
                else -> false
            }
        }
    }

    // Animated elevation for modern look
    val elevation by animateFloatAsState(
        targetValue = if (showScrolledColor) 3f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "topbar_elevation"
    )

    // Animated container color transition
    val containerColor by animateFloatAsState(
        targetValue = if (showScrolledColor) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "topbar_color"
    )

    val colorScheme = MaterialTheme.colorScheme
    val animatedContainerColor = androidx.compose.ui.graphics.lerp(
        Color(0xFF000000).copy(alpha = 0.7f), // Obsidian glass base
        Color(0xFF0A0A0A).copy(alpha = 0.8f), // Slightly more opaque when scrolled
        containerColor
    )

    val appBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = animatedContainerColor,
        scrolledContainerColor = animatedContainerColor,
        navigationIconContentColor = Color.White,
        titleContentColor = Color.White,
        actionIconContentColor = Color.White
    )

    val roundedShape = remember(showScrolledColor) {
        RoundedCornerShape(
            bottomStart = if (showScrolledColor) 16.dp else 0.dp,
            bottomEnd = if (showScrolledColor) 16.dp else 0.dp
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
        tonalElevation = if (showScrolledColor) 2.dp else 0.dp,
        shape = roundedShape
    ) {
        CenterAlignedTopAppBar(
            title = {
                if (currentRoute == "log" && isLogSearching) {
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
                        color = Color.White // High contrast on obsidian
                    )
                }
            },
            navigationIcon = {
                if (currentRoute == "log" && isLogSearching) {
                    IconButton(
                        onClick = {
                            onLogSearchingChange(false)
                            onLogSearchQueryChange("")
                        },
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.close_search),
                            tint = colorScheme.onSurface
                        )
                    }
                }
            },
            actions = {
                if (currentRoute == "log" && isLogSearching) {
                    if (logSearchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onLogSearchQueryChange("") },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Clear,
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
            },
            colors = appBarColors
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
            Icons.Default.MoreVert,
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
            Icons.Default.Delete,
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
            Icons.Default.MoreVert,
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
            Icons.Default.MoreVert,
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
            Icons.Default.MoreVert,
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
    val colorScheme = MaterialTheme.colorScheme
    val hapticFeedback = LocalHapticFeedback.current

    // Modern navbar with rounded top corners and elevation
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                spotColor = colorScheme.primary.copy(alpha = 0.15f),
                ambientColor = colorScheme.primary.copy(alpha = 0.08f)
            ),
        color = colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AdvancedNavItem(
                route = ROUTE_STATS,
                currentRoute = currentRoute,
                icon = R.drawable.dashboard,
                label = stringResource(R.string.core_stats_title),
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    navigateToRoute(navController, ROUTE_STATS)
                }
            )
            AdvancedNavItem(
                route = ROUTE_CONFIG,
                currentRoute = currentRoute,
                icon = R.drawable.code,
                label = stringResource(R.string.configuration),
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    navigateToRoute(navController, ROUTE_CONFIG)
                }
            )
            AdvancedNavItem(
                route = ROUTE_LOG,
                currentRoute = currentRoute,
                icon = R.drawable.history,
                label = stringResource(R.string.log),
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    navigateToRoute(navController, ROUTE_LOG)
                }
            )
            AdvancedNavItem(
                route = ROUTE_UTILS,
                currentRoute = currentRoute,
                icon = R.drawable.utils,
                label = "Utils",
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    navigateToRoute(navController, ROUTE_UTILS)
                }
            )
            AdvancedNavItem(
                route = ROUTE_SETTINGS,
                currentRoute = currentRoute,
                icon = R.drawable.settings,
                label = stringResource(R.string.settings),
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    navigateToRoute(navController, ROUTE_SETTINGS)
                }
            )
        }
    }
}

@Composable
private fun RowScope.AdvancedNavItem(
    route: String,
    currentRoute: String?,
    icon: Int,
    label: String,
    onClick: () -> Unit
) {
    val isSelected = currentRoute == route
    val colorScheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Animation values
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else if (isSelected) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 400f
        ),
        label = "nav_item_scale"
    )

    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.15f else 1f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 500f
        ),
        label = "icon_scale"
    )

    val containerAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "container_alpha"
    )

    val iconColor by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "icon_color"
    )

    val textColor by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "text_color"
    )

    val animatedIconColor = androidx.compose.ui.graphics.lerp(
        colorScheme.onSurfaceVariant,
        colorScheme.primary,
        iconColor
    )

    val animatedTextColor = androidx.compose.ui.graphics.lerp(
        colorScheme.onSurfaceVariant,
        colorScheme.primary,
        textColor
    )

    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .scale(scale)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        // Background indicator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .graphicsLayer { alpha = containerAlpha }
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            colorScheme.primaryContainer.copy(alpha = 0.6f),
                            colorScheme.secondaryContainer.copy(alpha = 0.4f)
                        )
                    )
                )
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = icon),
                    contentDescription = label,
                    modifier = Modifier.size(24.dp),
                    tint = animatedIconColor
                )
            }
            
            Spacer(modifier = Modifier.height(2.dp))
            
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 10.sp,
                    letterSpacing = (-0.01).sp
                ),
                color = animatedTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Selection indicator dot
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-2).dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(colorScheme.primary)
            )
        }
    }
}

@Composable
fun AppNavigationRail(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val colorScheme = MaterialTheme.colorScheme
    val hapticFeedback = LocalHapticFeedback.current

    Surface(
        modifier = Modifier
            .width(80.dp)
            .fillMaxSize()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                spotColor = colorScheme.primary.copy(alpha = 0.12f),
                ambientColor = colorScheme.primary.copy(alpha = 0.06f)
            ),
        color = colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AdvancedRailItem(
                route = ROUTE_STATS,
                currentRoute = currentRoute,
                icon = R.drawable.dashboard,
                label = stringResource(R.string.core_stats_title),
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    navigateToRoute(navController, ROUTE_STATS)
                }
            )
            AdvancedRailItem(
                route = ROUTE_CONFIG,
                currentRoute = currentRoute,
                icon = R.drawable.code,
                label = stringResource(R.string.configuration),
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    navigateToRoute(navController, ROUTE_CONFIG)
                }
            )
            AdvancedRailItem(
                route = ROUTE_LOG,
                currentRoute = currentRoute,
                icon = R.drawable.history,
                label = stringResource(R.string.log),
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    navigateToRoute(navController, ROUTE_LOG)
                }
            )
            AdvancedRailItem(
                route = ROUTE_UTILS,
                currentRoute = currentRoute,
                icon = R.drawable.utils,
                label = "Utils",
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    navigateToRoute(navController, ROUTE_UTILS)
                }
            )
            AdvancedRailItem(
                route = ROUTE_SETTINGS,
                currentRoute = currentRoute,
                icon = R.drawable.settings,
                label = stringResource(R.string.settings),
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    navigateToRoute(navController, ROUTE_SETTINGS)
                }
            )
        }
    }
}

@Composable
private fun AdvancedRailItem(
    route: String,
    currentRoute: String?,
    icon: Int,
    label: String,
    onClick: () -> Unit
) {
    val isSelected = currentRoute == route
    val colorScheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Animation values
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else if (isSelected) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 400f
        ),
        label = "rail_item_scale"
    )

    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.2f else 1f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 500f
        ),
        label = "rail_icon_scale"
    )

    val containerAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "rail_container_alpha"
    )

    val iconColor by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "rail_icon_color"
    )

    val animatedIconColor = androidx.compose.ui.graphics.lerp(
        colorScheme.onSurfaceVariant,
        colorScheme.primary,
        iconColor
    )

    Box(
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .scale(scale)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        // Background indicator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .graphicsLayer { alpha = containerAlpha }
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            colorScheme.primaryContainer.copy(alpha = 0.6f),
                            colorScheme.secondaryContainer.copy(alpha = 0.4f)
                        )
                    )
                )
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
                    .graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = icon),
                    contentDescription = label,
                    modifier = Modifier.size(28.dp),
                    tint = animatedIconColor
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 10.sp,
                    letterSpacing = (-0.01).sp
                ),
                color = animatedIconColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        // Selection indicator
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-4).dp)
                    .width(4.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colorScheme.primary)
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
