package com.hyperxray.an.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modern, reusable TopAppBar component with "Enter Always" scroll behavior support.
 * 
 * This component wraps Material3's CenterAlignedTopAppBar with:
 * - Smooth scroll-based hide/show animations
 * - Dynamic color transitions based on scroll state
 * - Navigation icon support (Back or Menu)
 * - Customizable actions
 * 
 * @param title The title text to display in the center
 * @param scrollBehavior The TopAppBarScrollBehavior from TopAppBarDefaults.enterAlwaysScrollBehavior()
 * @param onNavigationIconClick Callback when navigation icon is clicked
 * @param actions Composable lambda for action icons (e.g., search, more menu)
 * @param isBackEnabled If true, shows back arrow; if false, shows menu icon (or nothing if onNavigationIconClick is null)
 * @param modifier Optional modifier for the component
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HyperTopAppBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    onNavigationIconClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    isBackEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    // Calculate scroll progress (0f = fully expanded, 1f = fully collapsed)
    val scrollProgress = remember(scrollBehavior.state.overlappedFraction) {
        scrollBehavior.state.overlappedFraction.coerceIn(0f, 1f)
    }
    
    // Animated elevation based on scroll
    val elevation by animateFloatAsState(
        targetValue = if (scrollProgress > 0f) 3f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "topbar_elevation"
    )
    
    // Animated container color transition
    val containerColor by animateFloatAsState(
        targetValue = scrollProgress,
        animationSpec = tween(durationMillis = 300),
        label = "topbar_color"
    )
    
    // Interpolate between transparent and opaque obsidian glass
    val animatedContainerColor = androidx.compose.ui.graphics.lerp(
        Color(0xFF000000).copy(alpha = 0.7f), // Obsidian glass base (expanded)
        Color(0xFF0A0A0A).copy(alpha = 0.8f), // Slightly more opaque when scrolled
        containerColor
    )
    
    // Dynamic rounded corners based on scroll state
    val roundedShape = remember(scrollProgress) {
        RoundedCornerShape(
            bottomStart = if (scrollProgress > 0f) 16.dp else 0.dp,
            bottomEnd = if (scrollProgress > 0f) 16.dp else 0.dp
        )
    }
    
    val appBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = animatedContainerColor,
        scrolledContainerColor = animatedContainerColor,
        navigationIconContentColor = Color.White,
        titleContentColor = Color.White,
        actionIconContentColor = Color.White
    )
    
    Surface(
        modifier = modifier
            .shadow(
                elevation = elevation.dp,
                shape = roundedShape,
                spotColor = colorScheme.primary.copy(alpha = 0.1f),
                ambientColor = colorScheme.primary.copy(alpha = 0.05f)
            ),
        color = animatedContainerColor,
        tonalElevation = if (scrollProgress > 0f) 2.dp else 0.dp,
        shape = roundedShape
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.3).sp
                    ),
                    color = Color.White // High contrast on obsidian
                )
            },
            navigationIcon = {
                if (onNavigationIconClick != null) {
                    IconButton(onClick = onNavigationIconClick) {
                        Icon(
                            imageVector = if (isBackEnabled) {
                                Icons.AutoMirrored.Filled.ArrowBack
                            } else {
                                Icons.Default.Menu
                            },
                            contentDescription = if (isBackEnabled) "Back" else "Menu",
                            tint = Color.White
                        )
                    }
                }
            },
            actions = actions,
            colors = appBarColors,
            scrollBehavior = scrollBehavior
        )
    }
}



