package com.hyperxray.an.activity

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.hyperxray.an.common.ThemeMode
import com.hyperxray.an.core.di.AppContainer
import com.hyperxray.an.core.di.DefaultAppContainer
import com.hyperxray.an.feature.profiles.IntentHandler
import com.hyperxray.an.ui.navigation.AppNavHost
import com.hyperxray.an.ui.theme.ExpressiveMaterialTheme
import com.hyperxray.an.viewmodel.MainViewModel
import com.hyperxray.an.viewmodel.MainViewModelFactory

/**
 * Main activity for HyperXray VPN application.
 * 
 * Responsibilities:
 * - Activity lifecycle management
 * - Theme initialization and configuration changes
 * - Navigation host setup
 * - Intent delegation to feature modules
 * 
 * No business logic - all business logic is in feature modules.
 */
class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels { 
        MainViewModelFactory(application) 
    }
    
    // DI container (simplified - will be expanded in future phases)
    private val appContainer: AppContainer by lazy { 
        DefaultAppContainer(application) 
    }
    
    // Intent handler for config sharing (moved to feature-profiles)
    private lateinit var intentHandler: IntentHandler

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false

        // Initialize intent handler (delegates to MainViewModel for now)
        intentHandler = IntentHandler { content ->
            mainViewModel.handleSharedContent(content)
        }

        mainViewModel.reloadView = { initView() }
        initView()

        // Process share intent (delegated to feature module)
        intent?.let { intentHandler.processShareIntent(it, contentResolver, lifecycleScope) }
        
        Log.d(TAG, "MainActivity onCreate called.")
    }

    private fun initView() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val currentNightMode =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDark = when (mainViewModel.prefs.theme) {
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
            ThemeMode.Auto -> currentNightMode == Configuration.UI_MODE_NIGHT_YES
        }
        insetsController.isAppearanceLightStatusBars = !isDark

        setContent {
            ExpressiveMaterialTheme(darkTheme = isDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(mainViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let {
            intentHandler.processShareIntent(it, contentResolver, lifecycleScope)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy called.")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val currentNightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDark = when (mainViewModel.prefs.theme) {
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
            ThemeMode.Auto -> currentNightMode == Configuration.UI_MODE_NIGHT_YES
        }
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDark
        Log.d(TAG, "MainActivity onConfigurationChanged called.")
    }

    companion object {
        const val TAG = "MainActivity"
    }
}
