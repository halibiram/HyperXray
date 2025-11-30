package com.hyperxray.an.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * Baseline Profile Generator for HyperXray
 * 
 * Records critical user journeys to optimize cold app startup and runtime performance:
 * 1. App cold startup (MainActivity launch)
 * 2. Dashboard screen (Stats) rendering
 * 3. Profile list (Config) navigation
 * 4. Settings screen navigation
 * 5. Log screen navigation
 * 
 * Generated profiles are automatically included in release builds
 * and distributed via Play Store for improved startup performance.
 * 
 * To generate baseline profiles, run:
 * ./gradlew :baselineprofile:generateBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()
    
    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    
    private val packageName = "com.hyperxray.an"
    private val timeout = 10.seconds.inWholeMilliseconds
    
    /**
     * Records baseline profile for app cold startup.
     * This is the most critical path for performance optimization.
     */
    @Test
    fun startup() {
        baselineProfileRule.collectBaselineProfile(
            packageName = packageName,
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            maxIterations = 15
        ) {
            // Wait for app to fully start
            device.wait(Until.hasObject(By.pkg(packageName).depth(0)), timeout)
            
            // Wait for main content to be visible
            // MainActivity should show MainScreen with Dashboard
            device.wait(Until.hasObject(By.res(packageName, "dashboard")), timeout)
            
            // Additional wait to ensure all initializations are complete
            Thread.sleep(2000)
        }
    }
    
    /**
     * Records baseline profile for dashboard (Stats) screen.
     * This screen is the default landing screen after startup.
     */
    @Test
    fun dashboardScreen() {
        baselineProfileRule.collectBaselineProfile(
            packageName = packageName,
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            maxIterations = 10
        ) {
            // Start from cold state
            pressHome()
            startActivityAndWait()
            
            // Wait for dashboard to be visible (fallback to package check if resource not found)
            try {
                device.wait(Until.hasObject(By.res(packageName, "dashboard")), timeout)
            } catch (e: Exception) {
                // Fallback: wait for any app content
                device.wait(Until.hasObject(By.pkg(packageName).depth(0)), timeout)
            }
            
            // Scroll to ensure all content is loaded
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                10
            )
            
            // Wait for content to stabilize
            Thread.sleep(1000)
        }
    }
    
    /**
     * Records baseline profile for profile list (Config) screen navigation.
     * This is a frequently accessed screen for managing VPN profiles.
     */
    @Test
    fun configScreenNavigation() {
        baselineProfileRule.collectBaselineProfile(
            packageName = packageName,
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            maxIterations = 10
        ) {
            // Start from cold state
            pressHome()
            startActivityAndWait()
            
            // Wait for main screen
            device.wait(Until.hasObject(By.pkg(packageName).depth(0)), timeout)
            Thread.sleep(500) // Wait for UI to stabilize
            
            // Navigate to Config screen (bottom navigation)
            // Try multiple selectors to find config button
            val configButton = device.findObject(By.res(packageName, "config_tab"))
                ?: device.findObject(By.descContains("Config"))
                ?: device.findObject(By.descContains("Profile"))
                ?: device.findObject(By.textContains("Config"))
            configButton?.click()
            Thread.sleep(1000) // Wait for navigation
            
            // Wait for config screen to load (fallback if resource not found)
            try {
                device.wait(Until.hasObject(By.res(packageName, "config_list")), timeout)
            } catch (e: Exception) {
                // Fallback: just wait for app to be visible
                device.wait(Until.hasObject(By.pkg(packageName).depth(0)), timeout)
            }
            
            // Scroll through profile list
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                10
            )
            
            Thread.sleep(1000)
        }
    }
    
    /**
     * Records baseline profile for settings screen navigation.
     */
    @Test
    fun settingsScreenNavigation() {
        baselineProfileRule.collectBaselineProfile(
            packageName = packageName,
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            maxIterations = 10
        ) {
            // Start from cold state
            pressHome()
            startActivityAndWait()
            
            // Wait for main screen
            device.wait(Until.hasObject(By.pkg(packageName).depth(0)), timeout)
            Thread.sleep(500)
            
            // Navigate to Settings screen
            val settingsButton = device.findObject(By.res(packageName, "settings_tab"))
                ?: device.findObject(By.descContains("Settings"))
                ?: device.findObject(By.textContains("Settings"))
            settingsButton?.click()
            Thread.sleep(1000)
            
            // Wait for settings screen to load (fallback if resource not found)
            try {
                device.wait(Until.hasObject(By.res(packageName, "settings_list")), timeout)
            } catch (e: Exception) {
                device.wait(Until.hasObject(By.pkg(packageName).depth(0)), timeout)
            }
            
            // Scroll through settings
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                10
            )
            
            Thread.sleep(1000)
        }
    }
    
    /**
     * Records baseline profile for log screen navigation.
     */
    @Test
    fun logScreenNavigation() {
        baselineProfileRule.collectBaselineProfile(
            packageName = packageName,
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            maxIterations = 10
        ) {
            // Start from cold state
            pressHome()
            startActivityAndWait()
            
            // Wait for main screen
            device.wait(Until.hasObject(By.pkg(packageName).depth(0)), timeout)
            Thread.sleep(500)
            
            // Navigate to Log screen
            val logButton = device.findObject(By.res(packageName, "log_tab"))
                ?: device.findObject(By.descContains("Log"))
                ?: device.findObject(By.textContains("Log"))
            logButton?.click()
            Thread.sleep(1000)
            
            // Wait for log screen to load (fallback if resource not found)
            try {
                device.wait(Until.hasObject(By.res(packageName, "log_list")), timeout)
            } catch (e: Exception) {
                device.wait(Until.hasObject(By.pkg(packageName).depth(0)), timeout)
            }
            
            // Scroll through logs
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                10
            )
            
            Thread.sleep(1000)
        }
    }
    
    /**
     * Records baseline profile for complete user journey:
     * Startup -> Dashboard -> Config -> Settings -> Log
     */
    @Test
    fun completeUserJourney() {
        baselineProfileRule.collectBaselineProfile(
            packageName = packageName,
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            maxIterations = 5
        ) {
            // Cold startup
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg(packageName).depth(0)), timeout)
            Thread.sleep(1000)
            
            // Navigate through all main screens
            val screenSelectors = listOf(
                By.res(packageName, "config_tab"),
                By.descContains("Config"),
                By.textContains("Config"),
                By.res(packageName, "log_tab"),
                By.descContains("Log"),
                By.textContains("Log"),
                By.res(packageName, "settings_tab"),
                By.descContains("Settings"),
                By.textContains("Settings"),
                By.res(packageName, "stats_tab"),
                By.descContains("Stats"),
                By.textContains("Stats")
            )
            
            screenSelectors.forEach { selector ->
                val button = device.findObject(selector)
                button?.click()
                Thread.sleep(1500) // Wait for screen transition
            }
        }
    }
}

