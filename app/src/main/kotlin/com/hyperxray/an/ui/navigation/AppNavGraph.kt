package com.hyperxray.an.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hyperxray.an.common.*
import com.hyperxray.an.ui.screens.AppListScreen
import com.hyperxray.an.ui.screens.ConfigEditScreen
import com.hyperxray.an.ui.screens.MainScreen
import com.hyperxray.an.ui.screens.insights.AiInsightsScreen
import com.hyperxray.an.ui.screens.utils.DnsCacheManagerScreen
import com.hyperxray.an.ui.screens.utils.tools.*
import com.hyperxray.an.ui.screens.insights.AiInsightsContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hyperxray.an.viewmodel.AiInsightsViewModel
import com.hyperxray.an.viewmodel.MainViewModel
import com.hyperxray.an.feature.telegram.presentation.ui.TelegramSettingsScreen
import com.hyperxray.an.feature.telegram.presentation.viewmodel.TelegramSettingsViewModel
import androidx.compose.ui.platform.LocalContext
import android.app.Application
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AppNavHost(
    mainViewModel: MainViewModel
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = ROUTE_MAIN
    ) {
        composable(
            route = ROUTE_MAIN,
            enterTransition = { EnterTransition.None },
            exitTransition = { exitTransition() },
            popEnterTransition = { popEnterTransition() },
            popExitTransition = { ExitTransition.None }
        ) {
            MainScreen(
                mainViewModel = mainViewModel,
                appNavController = navController,
                snackbarHostState = remember { SnackbarHostState() }
            )
        }

        composable(
            route = ROUTE_APP_LIST,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            AppListScreen(
                viewModel = mainViewModel.appListViewModel,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = ROUTE_CONFIG_EDIT,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ConfigEditScreen(
                onBackClick = { navController.popBackStack() },
                snackbarHostState = remember { SnackbarHostState() },
                viewModel = mainViewModel.configEditViewModel
            )
        }

        composable(
            route = ROUTE_AI_INSIGHTS,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            AiInsightsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = ROUTE_TELEGRAM_SETTINGS,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            val context = LocalContext.current
            val application = context.applicationContext as Application
            val viewModel: TelegramSettingsViewModel = viewModel(
                factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return TelegramSettingsViewModel(application) as T
                    }
                }
            )
            TelegramSettingsScreen(
                viewModel = viewModel,
                onBackClick = { navController.popBackStack() }
            )
        }

        // Utils Tool Routes
        composable(
            route = ROUTE_UTILS_DNS_CACHE,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "DNS Cache Manager", navController = navController) {
                DnsCacheManagerScreen()
            }
        }

        composable(
            route = ROUTE_UTILS_OPTIMIZER,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            val viewModel: AiInsightsViewModel = viewModel()
            ToolScreenWrapper(title = "AI Optimizer", navController = navController) {
                AiInsightsContent(
                    viewModel = viewModel,
                    showResetDialog = remember { mutableStateOf(false) },
                    onShowResetDialogChange = { },
                    onResetLearner = { }
                )
            }
        }

        composable(
            route = ROUTE_UTILS_IP_INFO,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "IP Information", navController = navController) {
                IpInfoTool()
            }
        }

        composable(
            route = ROUTE_UTILS_PING_TEST,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "Ping Test", navController = navController) {
                PingTestTool()
            }
        }

        composable(
            route = ROUTE_UTILS_GEOIP_LOOKUP,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "GeoIP Lookup", navController = navController) {
                GeoIpLookupTool()
            }
        }

        composable(
            route = ROUTE_UTILS_NETWORK_INFO,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "Network Info", navController = navController) {
                NetworkInfoTool()
            }
        }

        composable(
            route = ROUTE_UTILS_SPEED_TEST,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "Speed Test", navController = navController) {
                SpeedTestTool()
            }
        }

        composable(
            route = ROUTE_UTILS_PORT_SCANNER,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "Port Scanner", navController = navController) {
                PortScannerTool()
            }
        }

        composable(
            route = ROUTE_UTILS_TRACEROUTE,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "Traceroute", navController = navController) {
                TracerouteTool()
            }
        }

        composable(
            route = ROUTE_UTILS_WAKE_ON_LAN,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "Wake on LAN", navController = navController) {
                WakeOnLanTool()
            }
        }

        composable(
            route = ROUTE_UTILS_SUBNET_CALCULATOR,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "Subnet Calculator", navController = navController) {
                SubnetCalculatorTool()
            }
        }

        composable(
            route = ROUTE_UTILS_WHOIS_LOOKUP,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "Whois Lookup", navController = navController) {
                WhoisLookupTool()
            }
        }

        composable(
            route = ROUTE_UTILS_DNS_LOOKUP,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "DNS Lookup", navController = navController) {
                DnsLookupTool()
            }
        }

        composable(
            route = ROUTE_UTILS_SSL_CERT_CHECKER,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "SSL Certificate Checker", navController = navController) {
                SslCertificateCheckerTool()
            }
        }

        composable(
            route = ROUTE_UTILS_HTTP_HEADERS_VIEWER,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "HTTP Headers Viewer", navController = navController) {
                HttpHeadersViewerTool()
            }
        }

        composable(
            route = ROUTE_UTILS_LAN_SCANNER,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "LAN Scanner", navController = navController) {
                LanScannerTool()
            }
        }

        composable(
            route = ROUTE_UTILS_HTTP_CLIENT,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "HTTP Client", navController = navController) {
                HttpClientTool()
            }
        }

        composable(
            route = ROUTE_UTILS_ASN_LOOKUP,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "ASN Lookup", navController = navController) {
                AsnLookupTool()
            }
        }

        composable(
            route = ROUTE_UTILS_MAC_ADDRESS_LOOKUP,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "MAC Address Lookup", navController = navController) {
                MacAddressLookupTool()
            }
        }

        composable(
            route = ROUTE_UTILS_UNIT_CONVERTER,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "Unit Converter", navController = navController) {
                UnitConverterTool()
            }
        }

        composable(
            route = ROUTE_UTILS_BASE64_CONVERTER,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "Base64 Converter", navController = navController) {
                Base64ConverterTool()
            }
        }

        composable(
            route = ROUTE_UTILS_URL_CONVERTER,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "URL Converter", navController = navController) {
                UrlConverterTool()
            }
        }

        composable(
            route = ROUTE_UTILS_PASSWORD_GENERATOR,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { popExitTransition() }
        ) {
            ToolScreenWrapper(title = "Password Generator", navController = navController) {
                PasswordGeneratorTool()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScreenWrapper(
    title: String,
    navController: NavHostController,
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF000000).copy(alpha = 0.9f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0A))
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            content()
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enterTransition() =
    scaleIn(
        initialScale = 0.8f,
        animationSpec = tween(300, easing = FastOutSlowInEasing)
    ) + slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(400, easing = FastOutSlowInEasing)
    ) + fadeIn(animationSpec = tween(400))

private fun exitTransition() =
    fadeOut(animationSpec = tween(300)) + scaleOut(
        targetScale = 0.9f,
        animationSpec = tween(400, easing = FastOutSlowInEasing)
    )

private fun popEnterTransition() = fadeIn(animationSpec = tween(400)) + scaleIn(
    initialScale = 0.9f,
    animationSpec = tween(400, easing = FastOutSlowInEasing)
)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExitTransition() =
    scaleOut(
        targetScale = 0.8f,
        animationSpec = tween(300, easing = FastOutSlowInEasing)
    ) + slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.End,
        animationSpec = tween(300, easing = FastOutSlowInEasing)
    ) + fadeOut(animationSpec = tween(400))
