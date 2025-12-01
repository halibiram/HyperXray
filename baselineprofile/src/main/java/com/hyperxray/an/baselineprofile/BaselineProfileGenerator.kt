package com.hyperxray.an.baselineprofile

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
 * Records critical user journeys to optimize cold app startup and runtime performance.
 * 
 * To generate baseline profiles, run:
 * ./gradlew :baselineprofile:generateBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()
    
    private val packageName = "com.hyperxray.an"
    private val timeout = 10_000L
    
    /**
     * Records baseline profile for app startup and main user journeys.
     */
    @Test
    fun generateBaselineProfile() {
        baselineProfileRule.collect(
            packageName = packageName,
            maxIterations = 5,
            stableIterations = 3
        ) {
            // Start the app
            pressHome()
            startActivityAndWait()
            
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            
            // Wait for main content to be visible
            device.wait(Until.hasObject(By.pkg(packageName).depth(0)), timeout)
            Thread.sleep(2000)
            
            // Navigate through main screens
            navigateToScreen(device, "config", "Config", "Profile")
            navigateToScreen(device, "log", "Log")
            navigateToScreen(device, "settings", "Settings")
            navigateToScreen(device, "stats", "Stats", "Dashboard")
        }
    }
    
    private fun navigateToScreen(device: UiDevice, vararg keywords: String) {
        for (keyword in keywords) {
            val button = device.findObject(By.res(packageName, "${keyword.lowercase()}_tab"))
                ?: device.findObject(By.descContains(keyword))
                ?: device.findObject(By.textContains(keyword))
            if (button != null) {
                button.click()
                Thread.sleep(1000)
                return
            }
        }
    }
}
