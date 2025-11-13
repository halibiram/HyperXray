package com.hyperxray.an.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material 3 Expressive Color Scheme - Light Theme
 * Features more vibrant colors, higher contrast, and expressive design tokens
 * Modern purple/indigo gradient theme for premium feel
 */
private val ExpressiveLightColorScheme = lightColorScheme(
    primary = Color(0xFF6366F1), // Modern indigo
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF), // Light indigo container
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF8B5CF6), // Purple accent
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE9FE), // Light purple container
    onSecondaryContainer = Color(0xFF3B1F5C),
    tertiary = Color(0xFFA855F7), // Vibrant purple
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E8FF), // Light purple container
    onTertiaryContainer = Color(0xFF4A1F6B),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBFDF9),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDBE5DD),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFBFC9C1),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2E312F),
    inverseOnSurface = Color(0xFFEFF1ED),
    inversePrimary = Color(0xFF6FD9AC),
    surfaceDim = Color(0xFFDBDDD9),
    surfaceBright = Color(0xFFFBFDF9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F7F3),
    surfaceContainer = Color(0xFFEFF1ED),
    surfaceContainerHigh = Color(0xFFE9EBE7),
    surfaceContainerHighest = Color(0xFFE3E5E1),
)

/**
 * Material 3 Expressive Color Scheme - Dark Theme
 * Features more vibrant colors, higher contrast, and expressive design tokens
 * Modern purple/indigo gradient theme for premium feel
 */
private val ExpressiveDarkColorScheme = darkColorScheme(
    primary = Color(0xFF818CF8), // Bright indigo
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF4338CA), // Dark indigo container
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFFA78BFA), // Bright purple
    onSecondary = Color(0xFF3B1F5C),
    secondaryContainer = Color(0xFF6D28D9), // Dark purple container
    onSecondaryContainer = Color(0xFFEDE9FE),
    tertiary = Color(0xFFC084FC), // Vibrant purple
    onTertiary = Color(0xFF4A1F6B),
    tertiaryContainer = Color(0xFF7C3AED), // Dark purple container
    onTertiaryContainer = Color(0xFFF3E8FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF191C1A),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF191C1A),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFBFC9C1),
    outline = Color(0xFF89938B),
    outlineVariant = Color(0xFF404943),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE1E3DF),
    inverseOnSurface = Color(0xFF2E312F),
    inversePrimary = Color(0xFF006C4C),
    surfaceDim = Color(0xFF191C1A),
    surfaceBright = Color(0xFF3F423F),
    surfaceContainerLowest = Color(0xFF0F120F),
    surfaceContainerLow = Color(0xFF1F221F),
    surfaceContainer = Color(0xFF232622),
    surfaceContainerHigh = Color(0xFF2D302D),
    surfaceContainerHighest = Color(0xFF373A37),
)

/**
 * Material 3 Expressive Typography
 * Features more expressive font sizes, weights, and line heights
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
 * Get Material 3 Expressive Color Scheme
 * Supports dynamic colors on Android 12+ (API 31+)
 */
@Composable
fun expressiveColorScheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
): ColorScheme {
    return when {
        dynamicColor && darkTheme -> {
            // Use dynamic colors for expressive design
            val dynamicScheme = dynamicDarkColorScheme(LocalContext.current)
            dynamicScheme.copy(
                // Enhance primary colors for more vibrancy
                primary = dynamicScheme.primary.copy(alpha = 1f),
                primaryContainer = dynamicScheme.primaryContainer.copy(alpha = 1f),
            )
        }
        dynamicColor && !darkTheme -> {
            // Use dynamic colors for expressive design
            val dynamicScheme = dynamicLightColorScheme(LocalContext.current)
            dynamicScheme.copy(
                // Enhance primary colors for more vibrancy
                primary = dynamicScheme.primary.copy(alpha = 1f),
                primaryContainer = dynamicScheme.primaryContainer.copy(alpha = 1f),
            )
        }
        darkTheme -> ExpressiveDarkColorScheme
        else -> ExpressiveLightColorScheme
    }
}

/**
 * Material 3 Expressive Theme
 * Provides expressive color scheme and typography
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
        content = content
    )
}

