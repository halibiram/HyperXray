package com.hyperxray.an.feature.dashboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Theme-aware color utilities for dashboard
 * Provides consistent colors that adapt to light/dark themes
 */
object DashboardColors {
    /**
     * Dashboard gradient colors (theme-aware)
     */
    @Composable
    fun performanceGradient(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFF00E5FF), // Neon Cyan - High contrast
                Color(0xFF2979FF), // Electric Blue
                Color(0xFF651FFF)  // Deep Purple
            )
        } else {
            listOf(
                Color(0xFF0891B2),
                Color(0xFF2563EB),
                Color(0xFF5B5FEF)
            )
        }
    }
    
    @Composable
    fun trafficGradient(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFF00E5FF), // Neon Cyan - Bright for high contrast
                Color(0xFFD500F9), // Neon Purple
                Color(0xFFFF00E5)  // Neon Magenta
            )
        } else {
            val colorScheme = MaterialTheme.colorScheme
            listOf(
                colorScheme.primary,
                colorScheme.secondary,
                colorScheme.tertiary
            )
        }
    }
    
    @Composable
    fun systemGradient(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFF00E676), // Neon Green
                Color(0xFF00C853), // Vibrant Green
                Color(0xFF64DD17)  // Lime Green
            )
        } else {
            listOf(
                Color(0xFF059669),
                Color(0xFF047857),
                Color(0xFF065F46)
            )
        }
    }
    
    @Composable
    fun memoryGradient(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFFFFD600), // Neon Yellow
                Color(0xFFFFAB00), // Amber
                Color(0xFFFF3D00)  // Deep Orange
            )
        } else {
            listOf(
                Color(0xFFF59E0B),
                Color(0xFFEF4444),
                Color(0xFFDC2626)
            )
        }
    }
    
    @Composable
    fun telemetryGradient(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFFFF4081), // Neon Pink
                Color(0xFFF50057), // Deep Pink
                Color(0xFFC51162)  // Dark Pink
            )
        } else {
            listOf(
                Color(0xFFEC4899),
                Color(0xFFDB2777),
                Color(0xFFBE185D)
            )
        }
    }
    
    @Composable
    fun successColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF34D399) else Color(0xFF059669) // Darker green for better contrast
    }
    
    @Composable
    fun errorColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFFF87171) else Color(0xFFEF4444)
    }
    
    @Composable
    fun warningColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFFFBBF24) else Color(0xFFF59E0B)
    }
    
    @Composable
    fun connectionActiveColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF00FF88) else Color(0xFF059669) // Neon green for high contrast on obsidian
    }
    
    @Composable
    fun dnsCacheGradient(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFF00BCD4), // Cyan
                Color(0xFF0097A7), // Teal
                Color(0xFF00838F)  // Dark Teal
            )
        } else {
            listOf(
                Color(0xFF0891B2),
                Color(0xFF0E7490),
                Color(0xFF155E75)
            )
        }
    }
    
    @Composable
    fun warpGradient(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFFFF6B35), // Cloudflare Orange
                Color(0xFFFF8C42), // Bright Orange
                Color(0xFFFFA500)  // Amber Orange
            )
        } else {
            listOf(
                Color(0xFFF97316),
                Color(0xFFEA580C),
                Color(0xFFDC2626)
            )
        }
    }
}

