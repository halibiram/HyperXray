package com.hyperxray.an.activity

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.DeadObjectException
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.hyperxray.an.common.ThemeUtils
import com.hyperxray.an.feature.profiles.IntentHandler
import com.hyperxray.an.ui.navigation.AppNavHost
import com.hyperxray.an.ui.theme.ExpressiveMaterialTheme
import com.hyperxray.an.viewmodel.MainViewModel
import com.hyperxray.an.viewmodel.MainViewModelFactory
import kotlinx.coroutines.launch

/**
 * Main activity for HyperXray VPN application.
 * 
 * Follows "Dumb Container" / "UI Host" pattern:
 * - Strictly delegates intents to ViewModel/IntentHandler
 * - Observes ViewModel state for theme
 * - No business logic - all logic in ViewModel and feature modules
 * - Only catches system/framework exceptions (BadTokenException, etc.)
 * 
 * Launch Mode Configuration (FIXED):
 * - Uses singleTop launch mode (changed from singleTask)
 * - Notification Intent flags: FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP
 * - Expected behavior:
 *   * If Activity exists at top of task: onNewIntent() called, onCreate() NOT called
 *   * If Activity doesn't exist: onCreate() called normally
 *   * Works correctly with Launcher, Notification, and deeplink intents
 *   * No unnecessary task creation or Activity instance duplication
 * 
 * Testing Steps:
 * 1. Launch app from Launcher icon multiple times - should see onNewIntent() in logcat
 * 2. Launch app from Notification while app is running - should see onNewIntent() in logcat
 * 3. Launch app from Notification while app is closed - should see onCreate() in logcat
 * 4. Check logcat for "LauncherDebug" tag to verify onCreate/onNewIntent behavior
 * 5. Test on MIUI and other custom launchers to ensure consistent behavior
 */
class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels { 
        MainViewModelFactory(application) 
    }
    
    // Intent handler for config sharing (delegates to ViewModel)
    private val intentHandler: IntentHandler by lazy {
        IntentHandler { content ->
            lifecycleScope.launch {
                mainViewModel.handleSharedContent(content)
            }
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // Debug: Log intent flags and Activity state for launch mode verification
            val intentFlags = intent?.flags ?: 0
            val isNewTask = (intentFlags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0
            val isClearTop = (intentFlags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0
            val isSingleTop = (intentFlags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
            val isResetTask = (intentFlags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0
            Log.d(TAG, "onCreate called - taskId=$taskId, flags: NEW_TASK=$isNewTask, CLEAR_TOP=$isClearTop, " +
                    "SINGLE_TOP=$isSingleTop, RESET_TASK=$isResetTask, " +
                    "savedInstanceState=${if (savedInstanceState != null) "not null" else "null"}, " +
                    "isTaskRoot=$isTaskRoot, intent=${intent?.action}")
            
            // NOTE: With singleTop launch mode + FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP:
            // - If Activity exists at top of task: onNewIntent() is called, onCreate() is NOT called
            // - If Activity doesn't exist or is not at top: onCreate() is called normally
            // - This is the expected behavior and fixes the issue with singleTask + NEW_TASK

            enableEdgeToEdge()
            window.isNavigationBarContrastEnforced = false

            // Initialize system night mode in ViewModel
            val currentNightMode = ThemeUtils.getSystemNightMode(
                resources.configuration.uiMode
            )
            mainViewModel.updateSystemNightMode(currentNightMode)

            // Initialize view - observes ViewModel state
            initView()

            // Process initial share intent
            intent?.let { 
                intentHandler.processShareIntent(it, contentResolver, lifecycleScope)
            }
            
            Log.d(TAG, "MainActivity onCreate completed.")
        } catch (e: WindowManager.BadTokenException) {
            // System/framework exception - Activity may be finishing
            Log.e(TAG, "BadTokenException in onCreate (system exception): ${e.message}", e)
        } catch (e: DeadObjectException) {
            // System/framework exception - Window/view has been destroyed
            Log.e(TAG, "DeadObjectException in onCreate (system exception): ${e.message}", e)
        } catch (e: IllegalStateException) {
            // System/framework exception - Activity state issue
            Log.e(TAG, "IllegalStateException in onCreate (system exception): ${e.message}", e)
        }
    }

    private fun initView() {
        try {
            setContent {
                // Observe ViewModel theme state - Activity is "dumb", just passes state to Compose
                val isDark by mainViewModel.isDarkTheme.collectAsState()
                
                // Update status bar appearance based on theme
                LaunchedEffect(isDark) {
                    try {
                        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                        insetsController.isAppearanceLightStatusBars = !isDark
                    } catch (e: WindowManager.BadTokenException) {
                        // System exception - Activity may be finishing, ignore
                        Log.d(TAG, "BadTokenException while updating status bar (ignored)")
                    } catch (e: DeadObjectException) {
                        // System exception - Window/view has been destroyed, ignore
                        Log.d(TAG, "DeadObjectException while updating status bar (ignored)")
                    }
                }

                ExpressiveMaterialTheme(darkTheme = isDark) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AppNavHost(mainViewModel)
                    }
                }
            }
        } catch (e: WindowManager.BadTokenException) {
            // System/framework exception - Activity may be finishing
            Log.e(TAG, "BadTokenException in initView (system exception): ${e.message}", e)
        } catch (e: DeadObjectException) {
            // System/framework exception - Window/view has been destroyed
            Log.e(TAG, "DeadObjectException in initView (system exception): ${e.message}", e)
        } catch (e: IllegalStateException) {
            // System/framework exception - Activity state issue
            Log.e(TAG, "IllegalStateException in initView (system exception): ${e.message}", e)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        
        // Debug: Log intent flags to verify singleTop launch mode is working correctly
        val intentFlags = intent?.flags ?: 0
        val isNewTask = (intentFlags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0
        val isClearTop = (intentFlags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0
        val isSingleTop = (intentFlags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
        Log.d(TAG, "onNewIntent called - singleTop working! taskId=$taskId, flags: NEW_TASK=$isNewTask, " +
                "CLEAR_TOP=$isClearTop, SINGLE_TOP=$isSingleTop, intent=${intent?.action}")
        
        setIntent(intent)
        
        // With singleTop launch mode + CLEAR_TOP + SINGLE_TOP, system brings existing Activity to front
        // and calls onNewIntent() instead of onCreate(). This is the expected behavior.
        if (!isFinishing && !isDestroyed) {
            try {
                // Strictly delegate to IntentHandler - no parsing logic in Activity
                intent?.let {
                    intentHandler.processShareIntent(it, contentResolver, lifecycleScope)
                }
            } catch (e: WindowManager.BadTokenException) {
                // System/framework exception - Activity may be finishing
                Log.d(TAG, "BadTokenException in onNewIntent (ignored)")
            } catch (e: DeadObjectException) {
                // System/framework exception - Window/view has been destroyed
                Log.d(TAG, "DeadObjectException in onNewIntent (ignored)")
            } catch (e: Exception) {
                Log.e(TAG, "Error in onNewIntent: ${e.message}", e)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called - activity is now visible")
        
        // CRITICAL: When Activity is resumed (especially after process death or bring-to-front),
        // ensure ViewModel state is refreshed so all cards update correctly.
        // This fixes the issue where Header Card updates but other cards don't.
        // 
        // FIXED: Changed SharingStarted.WhileSubscribed(5000) to SharingStarted.Lazily in StateFlow definitions
        // to ensure StateFlow restarts after process death. Now we also force refresh all stats
        // to trigger StateFlow updates and ensure UI recomposition.
        try {
            lifecycleScope.launch {
                // Force refresh all stats immediately to ensure UI updates
                // This triggers StateFlow updates which will cause Compose recomposition
                Log.d(TAG, "Refreshing ViewModel stats on resume...")
                mainViewModel.updateCoreStats()
                mainViewModel.updateTelemetryStats()
                
                // CRITICAL FIX: Force read StateFlow values to trigger subscription restart
                // This ensures StateFlow's using SharingStarted.Lazily restart after process death
                // Reading the value forces the StateFlow to start if it hasn't already
                try {
                    val coreStats = mainViewModel.coreStatsState.value
                    val telemetryStats = mainViewModel.telemetryState.value
                    Log.d(TAG, "StateFlow values read: coreStats.uplink=${coreStats.uplink}, telemetryStats=${telemetryStats != null}")
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading StateFlow values: ${e.message}", e)
                }
                
                // Also trigger dashboard adapter refresh if it exists
                // This ensures StateFlow transformations are re-evaluated
                try {
                    Log.d(TAG, "Refreshing dashboard adapter...")
                    val dashboardAdapter = mainViewModel.getOrCreateDashboardAdapter()
                    Log.d(TAG, "Dashboard adapter obtained, calling updateCoreStats()...")
                    dashboardAdapter.updateCoreStats()
                    Log.d(TAG, "Dashboard adapter updateCoreStats() completed, calling updateTelemetryStats()...")
                    dashboardAdapter.updateTelemetryStats()
                    // CRITICAL FIX: Force read dashboard adapter StateFlow values to trigger subscription restart
                    // This ensures transformed StateFlow's using SharingStarted.Lazily restart after process death
                    try {
                        val dashboardCoreStats = dashboardAdapter.coreStatsState.value
                        val dashboardTelemetryStats = dashboardAdapter.telemetryState.value
                        Log.d(TAG, "Dashboard adapter StateFlow values read: coreStats.uplink=${dashboardCoreStats.uplink}, telemetryStats=${dashboardTelemetryStats != null}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error reading dashboard adapter StateFlow values: ${e.message}", e)
                    }
                    
                    Log.d(TAG, "Dashboard adapter refresh completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error refreshing dashboard adapter: ${e.message}", e)
                }
                
                Log.d(TAG, "ViewModel stats refreshed on resume")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing stats on resume: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy called - isFinishing=$isFinishing")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Strictly delegate to ViewModel - no theme calculation logic in Activity
        val currentNightMode = ThemeUtils.getSystemNightMode(newConfig.uiMode)
        mainViewModel.updateSystemNightMode(currentNightMode)
        // Theme state will update automatically via StateFlow, Compose will recompose
        Log.d(TAG, "MainActivity onConfigurationChanged - delegated to ViewModel.")
    }

    companion object {
        const val TAG = "MainActivity"
    }
}
