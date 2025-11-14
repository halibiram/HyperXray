package com.hyperxray.an.ui.scaffold

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.hyperxray.an.R
import com.hyperxray.an.common.ROUTE_CONFIG
import com.hyperxray.an.common.ROUTE_LOG
import com.hyperxray.an.common.ROUTE_OPTIMIZER
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

    Scaffold(
        modifier = Modifier,
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
        bottomBar = {
            AppBottomNavigationBar(navController)
        },
        contentWindowInsets = WindowInsets(0)
    ) { paddingValues ->
        content(paddingValues)
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
        "optimizer" -> "TLS SNI Optimizer"
        "settings" -> stringResource(R.string.settings)
        else -> stringResource(R.string.app_name)
    }

    val defaultTopAppBarColors = TopAppBarDefaults.topAppBarColors()

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
                "optimizer" -> false // Optimizer screen doesn't scroll
                "settings" -> settingsScrollState.value > 0
                else -> false
            }
        }
    }

    val appBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.run {
            if (showScrolledColor) surfaceContainer else surface
        },
        scrolledContainerColor = MaterialTheme.colorScheme.run {
            if (showScrolledColor) surfaceContainer else surface
        },
        navigationIconContentColor = defaultTopAppBarColors.navigationIconContentColor,
        titleContentColor = defaultTopAppBarColors.titleContentColor,
        actionIconContentColor = defaultTopAppBarColors.actionIconContentColor
    )

    TopAppBar(
        title = {
            if (currentRoute == "log" && isLogSearching) {
                TextField(
                    value = logSearchQuery,
                    onValueChange = onLogSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = { Text(stringResource(R.string.search)) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
            } else {
                Text(text = title)
            }
        },
        navigationIcon = {
            if (currentRoute == "log" && isLogSearching) {
                IconButton(onClick = {
                    onLogSearchingChange(false)
                    onLogSearchQueryChange("")
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.close_search)
                    )
                }
            }
        },
        actions = {
            if (currentRoute == "log" && isLogSearching) {
                if (logSearchQuery.isNotEmpty()) {
                    IconButton(onClick = { onLogSearchQueryChange("") }) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = stringResource(R.string.clear_search)
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

    IconButton(
        onClick = onSwitchVpnService,
        enabled = controlMenuClickable
    ) {
        Icon(
            painter = painterResource(
                id = if (isServiceEnabled) R.drawable.pause else R.drawable.play
            ),
            contentDescription = null
        )
    }

    IconButton(onClick = { expanded = true }) {
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
            enabled = isServiceEnabled
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
    val logEntries by logViewModel.logEntries.collectAsStateWithLifecycle()
    val hasLogs = logEntries.isNotEmpty()

    IconButton(onClick = { onLogSearchingChange(true) }) {
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
    IconButton(onClick = { expanded = true }) {
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

    IconButton(onClick = { expanded = true }) {
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

    IconButton(
        onClick = onSwitchVpnService,
        enabled = controlMenuClickable
    ) {
        Icon(
            painter = painterResource(
                id = if (isServiceEnabled) R.drawable.pause else R.drawable.play
            ),
            contentDescription = null
        )
    }

    IconButton(onClick = { expanded = true }) {
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
            enabled = isServiceEnabled
        )
    }
}

@Composable
fun AppBottomNavigationBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val colorScheme = MaterialTheme.colorScheme

    val navItems = listOf(
        NavItem(
            route = ROUTE_STATS,
            iconRes = R.drawable.dashboard,
            label = stringResource(R.string.core_stats_title)
        ),
        NavItem(
            route = ROUTE_CONFIG,
            iconRes = R.drawable.code,
            label = stringResource(R.string.configuration)
        ),
        NavItem(
            route = ROUTE_LOG,
            iconRes = R.drawable.history,
            label = stringResource(R.string.log)
        ),
        NavItem(
            route = ROUTE_OPTIMIZER,
            iconRes = R.drawable.optimizer,
            label = "Optimizer"
        ),
        NavItem(
            route = ROUTE_SETTINGS,
            iconRes = R.drawable.settings,
            label = stringResource(R.string.settings)
        )
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                spotColor = colorScheme.primary.copy(alpha = 0.3f)
            ),
        color = colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            navItems.forEach { item ->
                ModernNavItem(
                    item = item,
                    isSelected = currentRoute == item.route,
                    onClick = { navigateToRoute(navController, item.route) },
                    colorScheme = colorScheme
                )
            }
        }
    }
}

private data class NavItem(
    val route: String,
    val iconRes: Int,
    val label: String
)

@Composable
private fun ModernNavItem(
    item: NavItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    colorScheme: androidx.compose.material3.ColorScheme
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Animations
    val iconScale by animateFloatAsState(
        targetValue = when {
            isSelected -> 1.2f
            isPressed -> 0.9f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 400f
        ),
        label = "icon_scale"
    )

    val containerScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.8f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 500f
        ),
        label = "container_scale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) {
            colorScheme.primaryContainer.copy(alpha = 0.4f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 300),
        label = "background_color"
    )

    val iconColor by animateColorAsState(
        targetValue = if (isSelected) {
            colorScheme.primary
        } else {
            colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        },
        animationSpec = tween(durationMillis = 200),
        label = "icon_color"
    )

    val textColor by animateColorAsState(
        targetValue = if (isSelected) {
            colorScheme.primary
        } else {
            colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        },
        animationSpec = tween(durationMillis = 200),
        label = "text_color"
    )

    Box(
        modifier = Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .scale(containerScale),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Animated icon with background circle
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                // Background circle for selected state
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        colorScheme.primary.copy(alpha = 0.2f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .alpha(if (isSelected) 1f else 0f)
                    )
                }

                // Icon
                Icon(
                    painter = painterResource(id = item.iconRes),
                    contentDescription = item.label,
                    modifier = Modifier
                        .size(24.dp)
                        .scale(iconScale),
                    tint = iconColor
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Animated label
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
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
