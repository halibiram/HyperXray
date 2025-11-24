package com.hyperxray.an.activity

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
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
            enableEdgeToEdge()
            window.isNavigationBarContrastEnforced = false

            // Initialize system night mode in ViewModel
            val currentNightMode = ThemeUtils.getSystemNightMode(
                resources.configuration.uiMode
            )
            mainViewModel.updateSystemNightMode(currentNightMode)

            // Initialize view - observes ViewModel state
            initView()

            // Process initial share intent (strictly delegates to IntentHandler)
            intent?.let { 
                intentHandler.processShareIntent(it, contentResolver, lifecycleScope)
            }
            
            Log.d(TAG, "MainActivity onCreate completed.")
        } catch (e: WindowManager.BadTokenException) {
            // System/framework exception - Activity may be finishing
            Log.e(TAG, "BadTokenException in onCreate (system exception): ${e.message}", e)
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
        } catch (e: IllegalStateException) {
            // System/framework exception - Activity state issue
            Log.e(TAG, "IllegalStateException in initView (system exception): ${e.message}", e)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Strictly delegate to IntentHandler - no parsing logic in Activity
        intent?.let {
            try {
                intentHandler.processShareIntent(it, contentResolver, lifecycleScope)
            } catch (e: WindowManager.BadTokenException) {
                // System/framework exception - Activity may be finishing
                Log.d(TAG, "BadTokenException in onNewIntent (ignored)")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy called.")
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
