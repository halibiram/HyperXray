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
 * Material 3 Futuristic Light Color Scheme
 * Cyberpunk-inspired light theme with neon accents
 * Features: Electric cyan/magenta gradients, holographic highlights, tech-forward aesthetic
 * Optimized for accessibility while maintaining futuristic visual identity
 */
private val ExpressiveLightColorScheme = lightColorScheme(
    // Primary: Electric Cyan - The signature futuristic color
    primary = Color(0xFF0097A7), // Deep cyan for light mode visibility
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2EBF2), // Soft cyan glow container
    onPrimaryContainer = Color(0xFF003D42), // Deep teal for readability
    
    // Secondary: Neon Magenta - Cyberpunk accent
    secondary = Color(0xFFAD1457), // Deep magenta for light mode
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF8BBD9), // Soft pink glow
    onSecondaryContainer = Color(0xFF4A0025), // Deep magenta text
    
    // Tertiary: Electric Purple - Holographic accent
    tertiary = Color(0xFF6A1B9A), // Deep purple for light mode
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE1BEE7), // Soft purple glow
    onTertiaryContainer = Color(0xFF2E0A40), // Deep purple text
    
    // Error: Neon Red - Alert state
    error = Color(0xFFD32F2F), // Vibrant red
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFCDD2), // Soft red glow
    onErrorContainer = Color(0xFF5D0000), // Deep red text
    
    // Background: Soft tech white with subtle blue tint
    background = Color(0xFFF5F9FC), // Slightly blue-tinted white for tech feel
    onBackground = Color(0xFF0D1B2A), // Deep navy for contrast
    
    // Surface: Layered glass effect
    surface = Color(0xFFFAFCFE), // Near-white with cool undertone
    onSurface = Color(0xFF0D1B2A), // Deep navy text
    surfaceVariant = Color(0xFFE0F2F7), // Cyan-tinted surface
    onSurfaceVariant = Color(0xFF1B3A4B), // Dark teal text
    
    // Outline: Neon grid lines
    outline = Color(0xFF4DD0E1), // Bright cyan outline
    outlineVariant = Color(0xFFB2EBF2), // Soft cyan variant
    
    scrim = Color(0xFF000011), // Deep space scrim
    
    // Inverse: Dark mode preview
    inverseSurface = Color(0xFF0D1B2A), // Deep navy
    inverseOnSurface = Color(0xFFE0F7FA), // Bright cyan text
    inversePrimary = Color(0xFF00E5FF), // Neon cyan
    
    // Surface containers: Holographic glass layers
    surfaceDim = Color(0xFFE0E8EC), // Dimmed glass
    surfaceBright = Color(0xFFFFFFFF), // Bright glass
    surfaceContainerLowest = Color(0xFFFFFFFF), // Base glass
    surfaceContainerLow = Color(0xFFF0F7FA), // Low elevation glass
    surfaceContainer = Color(0xFFE8F4F8), // Medium glass with cyan tint
    surfaceContainerHigh = Color(0xFFE0F0F5), // High elevation glass
    surfaceContainerHighest = Color(0xFFD8ECF2), // Highest glass with stronger cyan
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
     * DNS-related colors (neon cyan theme - futuristic)
     */
    @Composable
    fun dnsBorderColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF00E5FF) else Color(0xFF00ACC1) // Neon cyan
    }
    
    @Composable
    fun dnsContainerColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF0A4A52).copy(alpha = 0.3f) else Color(0xFFE0F7FA).copy(alpha = 0.8f)
    }
    
    @Composable
    fun dnsTextColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF00E5FF) else Color(0xFF006064) // Electric cyan
    }
    
    /**
     * Sniffing-related colors (neon magenta theme - futuristic)
     */
    @Composable
    fun sniffingBorderColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFFFF00E5) else Color(0xFFAD1457) // Neon magenta
    }
    
    @Composable
    fun sniffingContainerColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF5C2E00).copy(alpha = 0.3f) else Color(0xFFFCE4EC).copy(alpha = 0.8f)
    }
    
    @Composable
    fun sniffingTextColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFFFF00E5) else Color(0xFF880E4F) // Electric magenta
    }
    
    /**
     * SNI-related colors (electric purple theme - futuristic)
     */
    @Composable
    fun sniBorderColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF7C4DFF) else Color(0xFF6A1B9A) // Electric purple
    }
    
    /**
     * TCP/UDP connection colors (neon style)
     */
    @Composable
    fun tcpColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF00B0FF) else Color(0xFF0277BD) // Neon blue
    }
    
    @Composable
    fun udpColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF00E676) else Color(0xFF00695C) // Neon green
    }
    
    /**
     * Warning colors (neon amber theme)
     */
    @Composable
    fun warnContainerColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF5C2E00).copy(alpha = 0.4f) else Color(0xFFFFF8E1).copy(alpha = 0.8f)
    }
    
    @Composable
    fun warnTextColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFFFFD600) else Color(0xFFFF6F00) // Neon amber
    }
    
    /**
     * Gradient colors for badges (futuristic neon gradients)
     */
    @Composable
    fun sniGradientColors(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFF7C4DFF), // Electric purple
                Color(0xFFE040FB)  // Neon pink
            )
        } else {
            listOf(
                Color(0xFF6A1B9A), // Deep purple
                Color(0xFFAD1457)  // Deep magenta
            )
        }
    }
    
    @Composable
    fun sniffingGradientColors(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFFFF00E5), // Neon magenta
                Color(0xFFFF6090)  // Neon pink
            )
        } else {
            listOf(
                Color(0xFFAD1457), // Deep magenta
                Color(0xFFC2185B)  // Deep pink
            )
        }
    }
    
    @Composable
    fun dnsGradientColors(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFF00E5FF), // Neon cyan
                Color(0xFF00B0FF)  // Neon blue
            )
        } else {
            listOf(
                Color(0xFF00ACC1), // Deep cyan
                Color(0xFF0097A7)  // Teal
            )
        }
    }
    
    /**
     * Dashboard gradient colors (futuristic holographic style)
     */
    @Composable
    fun dashboardPerformanceGradient(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFF00E5FF), // Neon cyan
                Color(0xFF7C4DFF), // Electric purple
                Color(0xFFFF00E5)  // Neon magenta
            )
        } else {
            listOf(
                Color(0xFF00ACC1), // Deep cyan
                Color(0xFF6A1B9A), // Deep purple
                Color(0xFFAD1457)  // Deep magenta
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
                Color(0xFF00E676), // Neon green
                Color(0xFF00E5FF), // Neon cyan
                Color(0xFF00B0FF)  // Neon blue
            )
        } else {
            listOf(
                Color(0xFF00695C), // Deep teal
                Color(0xFF00838F), // Deep cyan
                Color(0xFF0277BD)  // Deep blue
            )
        }
    }
    
    @Composable
    fun dashboardMemoryGradient(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFFFFD600), // Neon yellow
                Color(0xFFFF6D00), // Neon orange
                Color(0xFFFF1744)  // Neon red
            )
        } else {
            listOf(
                Color(0xFFF57F17), // Deep amber
                Color(0xFFE65100), // Deep orange
                Color(0xFFD32F2F)  // Deep red
            )
        }
    }
    
    @Composable
    fun dashboardTelemetryGradient(): List<Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) {
            listOf(
                Color(0xFFFF00E5), // Neon magenta
                Color(0xFFE040FB), // Neon purple
                Color(0xFF7C4DFF)  // Electric purple
            )
        } else {
            listOf(
                Color(0xFFAD1457), // Deep magenta
                Color(0xFF8E24AA), // Deep purple
                Color(0xFF6A1B9A)  // Deeper purple
            )
        }
    }
    
    @Composable
    fun dashboardSuccessColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF00E676) else Color(0xFF00695C) // Neon green
    }
    
    @Composable
    fun dashboardErrorColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFFFF1744) else Color(0xFFD32F2F) // Neon red
    }
    
    @Composable
    fun dashboardWarningColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFFFFD600) else Color(0xFFF57F17) // Neon amber
    }
    
    @Composable
    fun dashboardConnectionActiveColor(): Color {
        val isDark = isSystemInDarkTheme()
        return if (isDark) Color(0xFF00E5FF) else Color(0xFF00ACC1) // Neon cyan - active connection
    }
}

