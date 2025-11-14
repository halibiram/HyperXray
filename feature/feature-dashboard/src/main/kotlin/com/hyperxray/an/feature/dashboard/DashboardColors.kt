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
                Color(0xFF22D3EE), // Brighter cyan
                Color(0xFF3B82F6), // Blue
                Color(0xFF6366F1)  // Indigo
            )
        } else {
            listOf(
                Color(0xFF0891B2), // Darker cyan for better contrast
                Color(0xFF2563EB), // Darker blue for better contrast
                Color(0xFF5B5FEF)  // Darker indigo for better contrast
            )
        }
    }
    
    @Composable
    fun trafficGradient(): List<Color> {
        val colorScheme = MaterialTheme.colorScheme
        return listOf(
            colorScheme.primary,
            colorScheme.secondary,
            colorScheme.tertiary
        )
    }
    
    @Composable
    fun systemGradient(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFF34D399), // Brighter green
                Color(0xFF10B981), // Green
                Color(0xFF059669)  // Darker green
            )
        } else {
            listOf(
                Color(0xFF059669), // Darker green for better contrast
                Color(0xFF047857), // Darker green
                Color(0xFF065F46)  // Darkest green for maximum contrast
            )
        }
    }
    
    @Composable
    fun memoryGradient(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFFFBBF24), // Brighter amber
                Color(0xFFF59E0B), // Amber
                Color(0xFFEF4444)  // Red
            )
        } else {
            listOf(
                Color(0xFFF59E0B), // Amber
                Color(0xFFEF4444), // Red
                Color(0xFFDC2626)  // Darker red
            )
        }
    }
    
    @Composable
    fun telemetryGradient(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFFF472B6), // Brighter pink
                Color(0xFFEC4899), // Pink
                Color(0xFFDB2777)  // Darker pink
            )
        } else {
            listOf(
                Color(0xFFEC4899), // Pink
                Color(0xFFDB2777), // Darker pink
                Color(0xFFBE185D)  // Darkest pink
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
        return if (isDark) Color(0xFF34D399) else Color(0xFF059669) // Darker green for better contrast
    }
}

