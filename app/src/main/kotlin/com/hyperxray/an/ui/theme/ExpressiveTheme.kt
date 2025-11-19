package com.hyperxray.an.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Material 3 Expressive Color Scheme - Light Theme
 * Features more vibrant colors, higher contrast, and expressive design tokens
 * Modern purple/indigo gradient theme for premium feel
 * Optimized for accessibility, readability, and visual hierarchy in light mode
 */
private val ExpressiveLightColorScheme = lightColorScheme(
    primary = Color(0xFF5B5FEF), // Slightly darker indigo for better contrast (was 0xFF6366F1)
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6DBFF), // Slightly darker container for better definition
    onPrimaryContainer = Color(0xFF1A1B4A), // Darker for better readability
    secondary = Color(0xFF7C3AED), // More vibrant purple for better visibility
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9D5FF), // Slightly darker container
    onSecondaryContainer = Color(0xFF2D1B5A), // Darker for better contrast
    tertiary = Color(0xFF9333EA), // More vibrant purple
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF0E4FF), // Slightly darker container
    onTertiaryContainer = Color(0xFF3D1B6A), // Darker for better readability
    error = Color(0xFFC62828), // Darker red for better contrast (was 0xFFBA1A1A)
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFCDD2), // Slightly darker container
    onErrorContainer = Color(0xFF3D0000), // Darker for better readability
    background = Color(0xFFFFFFFF), // Pure white for maximum contrast
    onBackground = Color(0xFF1C1F1D), // Darker for better readability (was 0xFF1A1D1B)
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1F1D), // Darker for better readability
    surfaceVariant = Color(0xFFE1E9E1), // More defined variant (was 0xFFDFE7DF)
    onSurfaceVariant = Color(0xFF3F4A43), // Darker for better readability (was 0xFF434C45)
    outline = Color(0xFF6B7570), // More visible outline (was 0xFF737C75)
    outlineVariant = Color(0xFFBFC7C1), // More defined variant (was 0xFFC3CDC5)
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2E312F),
    inverseOnSurface = Color(0xFFF5F7F5), // Lighter for better contrast
    inversePrimary = Color(0xFFB8C5FF), // More visible inverse primary
    surfaceDim = Color(0xFFD8DAD8), // Slightly darker for better definition
    surfaceBright = Color(0xFFFFFFFF), // Pure white
    surfaceContainerLowest = Color(0xFFFFFFFF), // Pure white
    surfaceContainerLow = Color(0xFFF2F4F2), // More defined separation (was 0xFFF4F6F4)
    surfaceContainer = Color(0xFFECEEEC), // More defined elevation (was 0xFFEEF0EE)
    surfaceContainerHigh = Color(0xFFE6E8E6), // More defined (was 0xFFE8EAE8)
    surfaceContainerHighest = Color(0xFFE0E2E0), // More defined (was 0xFFE2E4E2)
)

/**
 * Material 3 Expressive Color Scheme - Dark Theme
 * Features more vibrant colors, higher contrast, and expressive design tokens
 * Modern purple/indigo gradient theme for premium feel
 * Optimized for dark mode readability and eye comfort
 */
private val ExpressiveDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF), // Neon Cyan
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF004D57),
    onPrimaryContainer = Color(0xFFE0F7FA),
    secondary = Color(0xFFFF00E5), // Neon Magenta
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF57004E),
    onSecondaryContainer = Color(0xFFFFD6F9),
    tertiary = Color(0xFF7C4DFF), // Electric Purple
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF2A0055),
    onTertiaryContainer = Color(0xFFEBDCFF),
    error = Color(0xFFFF5252), // Neon Red
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF000000), // Obsidian Black
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF050505), // Almost Black
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF121212), // Dark Gray
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF222222),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE0E0E0),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = Color(0xFF00B0C7),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF1A1A1A),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
)

/**
 * Material 3 v2024 Typography Scale
 * Updated to match Material Design 3 typography v2024 specifications
 */
private val ExpressiveTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * Material 3 Shape System v2
 * Corner radius hierarchy: 8dp (small), 12dp (medium), 24dp (large)
 */
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Get Material 3 Expressive Color Scheme
 * Supports dynamic colors on Android 12+ (API 31+)
 * Uses tonal palettes for better color harmony
 */
@Composable
fun expressiveColorScheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
): ColorScheme {
    return when {
        dynamicColor && darkTheme -> {
            // Use dynamic colors with full tonal palette support
            dynamicDarkColorScheme(LocalContext.current)
        }
        dynamicColor && !darkTheme -> {
            // Use dynamic colors with full tonal palette support
            dynamicLightColorScheme(LocalContext.current)
        }
        darkTheme -> ExpressiveDarkColorScheme
        else -> ExpressiveLightColorScheme
    }
}

/**
 * Material 3 Expressive Theme
 * Provides expressive color scheme, typography v2024, and shape system v2
 * Full Material 3 design system with dynamic color support
 */
@Composable
fun ExpressiveMaterialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    content: @Composable () -> Unit
) {
    val colorScheme = expressiveColorScheme(darkTheme = darkTheme, dynamicColor = dynamicColor)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ExpressiveTypography,
        shapes = ExpressiveShapes,
        content = content
    )
}

/**
 * Theme-aware color utilities for log entries and badges
 * Provides consistent colors that adapt to light/dark themes
 */
object LogColors {
    /**
     * DNS-related colors (cyan theme)
     */
    @Composable
    fun dnsBorderColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF06B6D4) else Color(0xFF0288D1) // Darker cyan for better contrast
    }
    
    @Composable
    fun dnsContainerColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF0A4A52).copy(alpha = 0.3f) else Color(0xFFE0F7FA)
    }
    
    @Composable
    fun dnsTextColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF5DD5E8) else Color(0xFF0277BD) // Darker cyan for better contrast
    }
    
    /**
     * Sniffing-related colors (orange theme)
     */
    @Composable
    fun sniffingBorderColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFFFF8A50) else Color(0xFFFF6F00)
    }
    
    @Composable
    fun sniffingContainerColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF5C2E00).copy(alpha = 0.3f) else Color(0xFFFFF3E0)
    }
    
    @Composable
    fun sniffingTextColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFFFFB366) else Color(0xFFE65100)
    }
    
    /**
     * SNI-related colors (purple theme)
     */
    @Composable
    fun sniBorderColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFFBA68C8) else Color(0xFF9C27B0)
    }
    
    /**
     * TCP/UDP connection colors
     */
    @Composable
    fun tcpColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF64B5F6) else Color(0xFF1976D2) // Darker blue for better contrast
    }
    
    @Composable
    fun udpColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF81C784) else Color(0xFF388E3C) // Darker green for better contrast
    }
    
    /**
     * Warning colors (orange theme)
     */
    @Composable
    fun warnContainerColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF5C2E00).copy(alpha = 0.4f) else Color(0xFFFFF3E0)
    }
    
    @Composable
    fun warnTextColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFFFFB366) else Color(0xFFE65100)
    }
    
    /**
     * Gradient colors for badges (theme-aware)
     */
    @Composable
    fun sniGradientColors(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFFBA68C8), // Lighter purple
                Color(0xFF9C27B0)  // Standard purple
            )
        } else {
            listOf(
                Color(0xFF9C27B0), // Standard purple
                Color(0xFF7B1FA2)  // Darker purple
            )
        }
    }
    
    @Composable
    fun sniffingGradientColors(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFFFF8A50), // Lighter orange
                Color(0xFFFF6F00)  // Standard orange
            )
        } else {
            listOf(
                Color(0xFFFF6F00), // Standard orange
                Color(0xFFE65100)  // Darker orange
            )
        }
    }
    
    @Composable
    fun dnsGradientColors(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFF06B6D4), // Lighter cyan
                Color(0xFF00BCD4)  // Standard cyan
            )
        } else {
            listOf(
                Color(0xFF00BCD4), // Standard cyan
                Color(0xFF0097A7)  // Darker cyan
            )
        }
    }
    
    /**
     * Dashboard gradient colors (theme-aware)
     */
    @Composable
    fun dashboardPerformanceGradient(): List<Color> {
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
    fun dashboardTrafficGradient(): List<Color> {
        val colorScheme = MaterialTheme.colorScheme
        return listOf(
            colorScheme.primary,
            colorScheme.secondary,
            colorScheme.tertiary
        )
    }
    
    @Composable
    fun dashboardSystemGradient(): List<Color> {
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
    fun dashboardMemoryGradient(): List<Color> {
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
    fun dashboardTelemetryGradient(): List<Color> {
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
    fun dashboardSuccessColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF34D399) else Color(0xFF059669) // Darker green for better contrast
    }
    
    @Composable
    fun dashboardErrorColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFFF87171) else Color(0xFFEF4444)
    }
    
    @Composable
    fun dashboardWarningColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFFFBBF24) else Color(0xFFF59E0B)
    }
    
    @Composable
    fun dashboardConnectionActiveColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF34D399) else Color(0xFF059669) // Darker green for better contrast
    }
}

