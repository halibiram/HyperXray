package com.hyperxray.an.ui.navigation

import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.hyperxray.an.common.ROUTE_CONFIG
import com.hyperxray.an.common.ROUTE_LOG
import com.hyperxray.an.common.ROUTE_UTILS
import com.hyperxray.an.common.ROUTE_SETTINGS
import com.hyperxray.an.common.ROUTE_STATS
import com.hyperxray.an.service.TProxyService
import com.hyperxray.an.ui.screens.ConfigScreen
import com.hyperxray.an.ui.screens.LogScreen
import com.hyperxray.an.ui.screens.UtilsScreen
import com.hyperxray.an.ui.screens.SettingsScreen
import com.hyperxray.an.feature.dashboard.DashboardScreen
import com.hyperxray.an.viewmodel.LogViewModel
import com.hyperxray.an.viewmodel.MainViewModel
import com.hyperxray.an.viewmodel.dashboardViewModel
import java.io.File

private const val TAG = "AppNavGraph"

private val BOTTOM_NAV_ROUTE_INDEX = mapOf(
    ROUTE_STATS to 0,
    ROUTE_CONFIG to 1,
    ROUTE_LOG to 2,
    ROUTE_UTILS to 3,
    ROUTE_SETTINGS to 4
)

private fun NavBackStackEntry.routeIndex(): Int =
    destination.route?.let { BOTTOM_NAV_ROUTE_INDEX[it] } ?: 0

private fun AnimatedContentTransitionScope<NavBackStackEntry>.horizontalSlideEnter(): EnterTransition {
    val targetIndex = targetState.routeIndex()
    val initialIndex = initialState.routeIndex()
    val direction = if (targetIndex > initialIndex) {
        AnimatedContentTransitionScope.SlideDirection.Start
    } else {
        AnimatedContentTransitionScope.SlideDirection.End
    }
    return slideIntoContainer(
        towards = direction,
        animationSpec = tween(200, easing = FastOutSlowInEasing)
    ) + fadeIn(animationSpec = tween(200))
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.horizontalSlideExit(): ExitTransition {
    val targetIndex = targetState.routeIndex()
    val initialIndex = initialState.routeIndex()
    val direction = if (targetIndex > initialIndex) {
        AnimatedContentTransitionScope.SlideDirection.Start
    } else {
        AnimatedContentTransitionScope.SlideDirection.End
    }
    return slideOutOfContainer(
        towards = direction,
        animationSpec = tween(200, easing = FastOutSlowInEasing)
    ) + fadeOut(animationSpec = tween(150))
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enterTransition() =
    horizontalSlideEnter()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exitTransition() =
    horizontalSlideExit()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnterTransition() =
    horizontalSlideEnter()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExitTransition() =
    horizontalSlideExit()

private fun createReloadConfigCallback(mainViewModel: MainViewModel): () -> Unit = {
    Log.d(TAG, "Reload config requested from UI.")
    mainViewModel.startTProxyService(TProxyService.ACTION_RELOAD_CONFIG)
}

private fun createEditConfigCallback(mainViewModel: MainViewModel): (File) -> Unit = { file ->
    Log.d(TAG, "ConfigFragment request: Edit file: ${file.name}")
    mainViewModel.editConfig(file.absolutePath)
}

@Composable
fun BottomNavHost(
    navController: NavHostController,
    paddingValues: PaddingValues,
    mainViewModel: MainViewModel,
    onDeleteConfigClick: (File, () -> Unit) -> Unit,
    logViewModel: LogViewModel,
    geoipFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    geositeFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    logListState: LazyListState,
    configListState: LazyListState,
    settingsScrollState: ScrollState,
    onSwitchVpnService: () -> Unit = {},
    appNavController: NavHostController? = null
) {
    NavHost(
        navController = navController,
        startDestination = ROUTE_STATS,
        modifier = Modifier.padding(paddingValues)
    ) {
        composable(
            route = ROUTE_STATS,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { popEnterTransition() },
            popExitTransition = { popExitTransition() }
        ) {
            DashboardScreen(
                viewModel = mainViewModel.dashboardViewModel,
                onSwitchVpnService = onSwitchVpnService,
                resources = object : com.hyperxray.an.feature.dashboard.DashboardResources {
                    override val drawablePlay: Int = com.hyperxray.an.R.drawable.play
                    override val drawablePause: Int = com.hyperxray.an.R.drawable.pause
                    override val drawableDashboard: Int = com.hyperxray.an.R.drawable.dashboard
                    override val drawableCloudDownload: Int = com.hyperxray.an.R.drawable.cloud_download
                    override val drawableSettings: Int = com.hyperxray.an.R.drawable.settings
                    override val drawableOptimizer: Int = com.hyperxray.an.R.drawable.optimizer
                    override val stringStatsUplink: Int = com.hyperxray.an.R.string.stats_uplink
                    override val stringStatsDownlink: Int = com.hyperxray.an.R.string.stats_downlink
                    override val stringStatsNumGoroutine: Int = com.hyperxray.an.R.string.stats_num_goroutine
                    override val stringStatsNumGc: Int = com.hyperxray.an.R.string.stats_num_gc
                    override val stringStatsUptime: Int = com.hyperxray.an.R.string.stats_uptime
                    override val stringStatsAlloc: Int = com.hyperxray.an.R.string.stats_alloc
                    override val stringVpnDisconnected: Int = com.hyperxray.an.R.string.vpn_disconnected
                }
            )
        }

        composable(
            route = ROUTE_CONFIG,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { popEnterTransition() },
            popExitTransition = { popExitTransition() }
        ) {
            ConfigScreen(
                onReloadConfig = createReloadConfigCallback(mainViewModel),
                onEditConfigClick = createEditConfigCallback(mainViewModel),
                onDeleteConfigClick = onDeleteConfigClick,
                mainViewModel = mainViewModel,
                listState = configListState
            )
        }

        composable(
            route = ROUTE_LOG,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { popEnterTransition() },
            popExitTransition = { popExitTransition() }
        ) {
            LogScreen(
                logViewModel = logViewModel,
                listState = logListState
            )
        }

        composable(
            route = ROUTE_UTILS,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { popEnterTransition() },
            popExitTransition = { popExitTransition() }
        ) {
            UtilsScreen(navController = appNavController)
        }

        composable(
            route = ROUTE_SETTINGS,
            enterTransition = { enterTransition() },
            exitTransition = { exitTransition() },
            popEnterTransition = { popEnterTransition() },
            popExitTransition = { popExitTransition() }
        ) {
            SettingsScreen(
                mainViewModel = mainViewModel,
                geoipFilePickerLauncher = geoipFilePickerLauncher,
                geositeFilePickerLauncher = geositeFilePickerLauncher,
                scrollState = settingsScrollState
            )
        }
    }
}