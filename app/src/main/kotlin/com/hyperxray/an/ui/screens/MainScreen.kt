package com.hyperxray.an.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.application
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hyperxray.an.common.NAVIGATION_DEBOUNCE_DELAY
import com.hyperxray.an.common.ROUTE_CONFIG
import com.hyperxray.an.common.ROUTE_LOG
import com.hyperxray.an.common.ROUTE_UTILS
import com.hyperxray.an.common.ROUTE_SETTINGS
import com.hyperxray.an.common.ROUTE_STATS
import com.hyperxray.an.common.rememberMainScreenCallbacks
import com.hyperxray.an.common.rememberMainScreenLaunchers
import com.hyperxray.an.ui.navigation.BottomNavHost
import com.hyperxray.an.ui.scaffold.AppScaffold
import com.hyperxray.an.viewmodel.LogViewModel
import com.hyperxray.an.viewmodel.LogViewModelFactory
import com.hyperxray.an.viewmodel.MainViewModel
import com.hyperxray.an.viewmodel.MainViewUiEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    appNavController: NavHostController,
    snackbarHostState: SnackbarHostState
) {
    val bottomNavController = rememberNavController()
    val scope = rememberCoroutineScope()

    val launchers = rememberMainScreenLaunchers(mainViewModel)

    val logViewModel: LogViewModel = viewModel(
        factory = LogViewModelFactory(mainViewModel.application)
    )

    val callbacks = rememberMainScreenCallbacks(
        mainViewModel = mainViewModel,
        logViewModel = logViewModel,
        launchers = launchers,
        applicationContext = mainViewModel.application
    )

    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {}


    var lastNavigationTime = 0L

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            mainViewModel.extractAssetsIfNeeded()
        }
        
        // Check auto start and start VPN if enabled
        scope.launch {
            kotlinx.coroutines.delay(1000) // Wait for UI to initialize
            mainViewModel.checkAndStartAutoVpn(launchers.vpnPrepareLauncher)
        }

        mainViewModel.uiEvent.collectLatest { event ->
            when (event) {
                is MainViewUiEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(
                        event.message,
                        duration = SnackbarDuration.Short
                    )
                }

                is MainViewUiEvent.ShareLauncher -> {
                    shareLauncher.launch(event.intent)
                }

                is MainViewUiEvent.StartService -> {
                    mainViewModel.application.startService(event.intent)
                }

                is MainViewUiEvent.RefreshConfigList -> {
                    mainViewModel.refreshConfigFileList()
                }

                is MainViewUiEvent.Navigate -> {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastNavigationTime >= NAVIGATION_DEBOUNCE_DELAY) {
                        lastNavigationTime = currentTime
                        appNavController.navigate(event.route)
                    }
                }
                
                is MainViewUiEvent.ShowWarpAccountRequiredDialog -> {
                    mainViewModel.setShowWarpAccountDialog(true)
                }
            }
        }
    }

    val logListState = rememberLazyListState()
    val configListState = rememberLazyListState()
    val settingsScrollState = rememberScrollState()

    val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val mainScreenRoutes = listOf(ROUTE_STATS, ROUTE_CONFIG, ROUTE_LOG, ROUTE_UTILS, ROUTE_SETTINGS)

    if (currentRoute in mainScreenRoutes) {
        AppScaffold(
            navController = bottomNavController,
            snackbarHostState = snackbarHostState,
            mainViewModel = mainViewModel,
            logViewModel = logViewModel,
            onCreateNewConfigFileAndEdit = callbacks.onCreateNewConfigFileAndEdit,
            onImportConfigFromClipboard = callbacks.onImportConfigFromClipboard,
            onPerformExport = callbacks.onPerformExport,
            onPerformBackup = callbacks.onPerformBackup,
            onPerformRestore = callbacks.onPerformRestore,
            onSwitchVpnService = callbacks.onSwitchVpnService,
            logListState = logListState,
            configListState = configListState,
            settingsScrollState = settingsScrollState
        ) { paddingValues ->
            BottomNavHost(
                navController = bottomNavController,
                paddingValues = paddingValues,
                mainViewModel = mainViewModel,
                onDeleteConfigClick = callbacks.onDeleteConfigClick,
                logViewModel = logViewModel,
                geoipFilePickerLauncher = launchers.geoipFilePickerLauncher,
                geositeFilePickerLauncher = launchers.geositeFilePickerLauncher,
                logListState = logListState,
                appNavController = appNavController,
                configListState = configListState,
                settingsScrollState = settingsScrollState,
                onSwitchVpnService = callbacks.onSwitchVpnService
            )
        }
    } else {
        BottomNavHost(
            navController = bottomNavController,
            paddingValues = androidx.compose.foundation.layout.PaddingValues(),
            mainViewModel = mainViewModel,
            onDeleteConfigClick = callbacks.onDeleteConfigClick,
            logViewModel = logViewModel,
            geoipFilePickerLauncher = launchers.geoipFilePickerLauncher,
            geositeFilePickerLauncher = launchers.geositeFilePickerLauncher,
            logListState = logListState,
            configListState = configListState,
            settingsScrollState = settingsScrollState,
            onSwitchVpnService = callbacks.onSwitchVpnService
        )
    }
    
    // WARP Account Required Dialog
    WarpAccountRequiredDialog(
        mainViewModel = mainViewModel,
        scope = scope
    )
}


@Composable
private fun WarpAccountRequiredDialog(
    mainViewModel: MainViewModel,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val showDialog by mainViewModel.showWarpAccountModal.collectAsState()
    val isCreating by mainViewModel.isCreatingWarpAccount.collectAsState()
    
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { 
                if (!isCreating) {
                    mainViewModel.setShowWarpAccountDialog(false)
                }
            },
            title = {
                Text("WARP Account Required")
            },
            text = {
                Column {
                    Text(
                        "No WARP account found. A WARP account is required to connect to the VPN."
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Would you like to create a free Cloudflare WARP account now?",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (isCreating) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Creating account...")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            mainViewModel.createWarpAccountAndConnect()
                            mainViewModel.setShowWarpAccountDialog(false)
                        }
                    },
                    enabled = !isCreating
                ) {
                    Text("Create Account")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { mainViewModel.setShowWarpAccountDialog(false) },
                    enabled = !isCreating
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
