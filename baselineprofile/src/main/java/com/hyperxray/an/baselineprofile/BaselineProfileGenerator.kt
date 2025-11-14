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

/**
 * Baseline Profile Generator for HyperXray
 * 
 * Records critical user journeys to optimize cold app startup:
 * 1. App startup and MainActivity initialization
 * 2. Dashboard screen launch and rendering
 * 3. Profile list navigation (ConfigScreen)
 * 
 * Generated profiles are automatically included in release builds
 * and distributed via Play Store for improved startup performance.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()
    
    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    
    @Test
    fun generateBaselineProfile() = baselineProfileRule.collectBaselineProfile(
        packageName = "com.hyperxray.an",
        profileBlock = {
            // Journey 1: App Startup and MainActivity
            startActivityAndWait()
            
            // Wait for MainActivity to fully initialize
            // Compose apps may not have traditional resource IDs, so we wait for package
            device.wait(
                Until.hasObject(By.pkg("com.hyperxray.an")),
                5000
            )
            
            // Wait for UI to be ready
            device.waitForIdle(2000)
            
            // Journey 2: Dashboard Launch (default start destination)
            // Dashboard is the default screen, so it's already loaded
            // Wait for dashboard content to render and scroll to profile lazy loading
            device.waitForIdle(1000)
            
            // Scroll dashboard to ensure all components are loaded
            val centerX = device.displayWidth / 2
            val startY = device.displayHeight * 3 / 4
            val endY = device.displayHeight / 4
            
            device.swipe(centerX, startY, centerX, endY, 10)
            device.waitForIdle(1000)
            
            // Journey 3: Navigate to Profile List (ConfigScreen)
            // Try to find and tap the Config tab in bottom navigation
            // Since Compose doesn't always expose resource IDs, we try multiple approaches
            var configTab = device.findObject(By.text("Config"))
            if (configTab == null) {
                configTab = device.findObject(By.textContains("Config"))
            }
            if (configTab == null) {
                // Try clicking at bottom navigation area (Config is typically 2nd tab)
                val bottomNavY = device.displayHeight - 100
                val configTabX = device.displayWidth * 2 / 5 // Approximate position for 2nd tab
                device.click(configTabX, bottomNavY)
            } else {
                configTab.click()
            }
            
            device.waitForIdle(2000)
            
            // Scroll through config list to profile all items
            device.swipe(centerX, startY, centerX, endY, 10)
            device.waitForIdle(1000)
            
            // Return to dashboard (Stats tab is typically first)
            val statsTab = device.findObject(By.text("Stats")) 
                ?: device.findObject(By.textContains("Stats"))
            
            if (statsTab != null) {
                statsTab.click()
            } else {
                // Click at first tab position
                val statsTabX = device.displayWidth / 5
                val bottomNavY = device.displayHeight - 100
                device.click(statsTabX, bottomNavY)
            }
            
            device.waitForIdle(1000)
        }
    )
    
    @Test
    fun generateBaselineProfileStartup() = baselineProfileRule.collectBaselineProfile(
        packageName = "com.hyperxray.an",
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        profileBlock = {
            // Cold startup: Launch app from scratch
            startActivityAndWait()
            
            // Wait for application initialization
            device.waitForIdle(3000)
            
            // Wait for MainActivity UI to be ready
            device.wait(
                Until.hasObject(By.pkg("com.hyperxray.an")),
                5000
            )
        }
    )
    
    @Test
    fun generateBaselineProfileDashboard() = baselineProfileRule.collectBaselineProfile(
        packageName = "com.hyperxray.an",
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        profileBlock = {
            // Warm startup: App already running, focus on dashboard
            pressHome()
            startActivityAndWait()
            
            // Wait for dashboard to fully render
            device.waitForIdle(2000)
            
            // Interact with dashboard components
            // Scroll to trigger lazy loading
            val centerX = device.displayWidth / 2
            val startY = device.displayHeight * 3 / 4
            val endY = device.displayHeight / 4
            
            repeat(3) {
                device.swipe(centerX, startY, centerX, endY, 10)
                device.waitForIdle(500)
            }
        }
    )
    
    @Test
    fun generateBaselineProfileConfigList() = baselineProfileRule.collectBaselineProfile(
        packageName = "com.hyperxray.an",
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        profileBlock = {
            // Navigate to config list
            startActivityAndWait()
            device.waitForIdle(1000)
            
            // Navigate to Config tab
            var configTab = device.findObject(By.text("Config"))
            if (configTab == null) {
                configTab = device.findObject(By.textContains("Config"))
            }
            if (configTab != null) {
                configTab.click()
            } else {
                // Fallback: click at approximate Config tab position
                val configTabX = device.displayWidth * 2 / 5
                val bottomNavY = device.displayHeight - 100
                device.click(configTabX, bottomNavY)
            }
            
            device.waitForIdle(2000)
            
            // Scroll through list to profile lazy loading
            val centerX = device.displayWidth / 2
            val startY = device.displayHeight * 3 / 4
            val endY = device.displayHeight / 4
            
            repeat(2) {
                device.swipe(centerX, startY, centerX, endY, 10)
                device.waitForIdle(500)
            }
        }
    )
}

