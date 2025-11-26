package com.hyperxray.an.common

import android.content.res.Configuration

/**
 * Utility functions for theme calculation.
 * Isolated from Activity to follow "Dumb Container" pattern.
 */
object ThemeUtils {
    /**
     * Calculate if dark theme should be used based on ThemeMode and system configuration.
     * 
     * @param themeMode User's theme preference
     * @param systemNightMode System night mode from Configuration.UI_MODE_NIGHT_MASK
     * @return true if dark theme should be used, false otherwise
     */
    fun calculateIsDark(
        themeMode: ThemeMode,
        systemNightMode: Int
    ): Boolean {
        return when (themeMode) {
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
            ThemeMode.Auto -> systemNightMode == Configuration.UI_MODE_NIGHT_YES
        }
    }
    
    /**
     * Extract system night mode from Configuration.
     * 
     * @param uiMode UI mode from Configuration.uiMode
     * @return System night mode value (Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_NIGHT_NO)
     */
    fun getSystemNightMode(uiMode: Int): Int {
        return uiMode and Configuration.UI_MODE_NIGHT_MASK
    }
}










