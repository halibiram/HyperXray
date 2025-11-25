package com.hyperxray.an.ui.theme

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hyperxray.an.R

/**
 * Example usage of HyperIcons design system
 * 
 * This file demonstrates how to use HyperIcons throughout the app.
 * Delete this file after reviewing - it's for reference only.
 */

// ========== Example 1: Navigation Bar ==========
@Composable
fun ExampleNavigationBar() {
    // Before: Icons.Default.Dashboard
    // After: HyperIcons.Dashboard
    Icon(
        imageVector = HyperIcons.Dashboard,
        contentDescription = "Dashboard"
    )
    
    Icon(
        imageVector = HyperIcons.Configs,
        contentDescription = "Configurations"
    )
    
    Icon(
        imageVector = HyperIcons.Logs,
        contentDescription = "Logs"
    )
    
    Icon(
        imageVector = HyperIcons.Settings,
        contentDescription = "Settings"
    )
}

// ========== Example 2: Action Buttons ==========
@Composable
fun ExampleActionButtons() {
    IconButton(onClick = { /* Connect */ }) {
        Icon(
            imageVector = HyperIcons.Connect,
            contentDescription = "Connect"
        )
    }
    
    IconButton(onClick = { /* Disconnect */ }) {
        Icon(
            imageVector = HyperIcons.Disconnect,
            contentDescription = "Disconnect"
        )
    }
    
    IconButton(onClick = { /* Edit */ }) {
        Icon(
            imageVector = HyperIcons.Edit,
            contentDescription = "Edit"
        )
    }
    
    IconButton(onClick = { /* Delete */ }) {
        Icon(
            imageVector = HyperIcons.Delete,
            contentDescription = "Delete"
        )
    }
}

// ========== Example 3: Network/VPN Icons ==========
@Composable
fun ExampleNetworkIcons() {
    // VPN Key icon
    Icon(
        imageVector = HyperIcons.VpnKey,
        contentDescription = "VPN Key"
    )
    
    // Security/Shield icon
    Icon(
        imageVector = HyperIcons.Security,
        contentDescription = "Security"
    )
    
    // Speed/Rocket icon
    Icon(
        imageVector = HyperIcons.RocketLaunch,
        contentDescription = "Speed"
    )
    
    // DNS icon
    Icon(
        imageVector = HyperIcons.Dns,
        contentDescription = "DNS"
    )
}

// ========== Example 4: Status Icons ==========
@Composable
fun ExampleStatusIcons() {
    // Success
    Icon(
        imageVector = HyperIcons.Success,
        contentDescription = "Success"
    )
    
    // Error
    Icon(
        imageVector = HyperIcons.Error,
        contentDescription = "Error"
    )
    
    // Warning
    Icon(
        imageVector = HyperIcons.Warning,
        contentDescription = "Warning"
    )
    
    // Info
    Icon(
        imageVector = HyperIcons.Info,
        contentDescription = "Information"
    )
}

// ========== Example 5: Search and Filter ==========
@Composable
fun ExampleSearchAndFilter() {
    // Search icon
    Icon(
        imageVector = HyperIcons.Search,
        contentDescription = "Search"
    )
    
    // Clear search
    Icon(
        imageVector = HyperIcons.Clear,
        contentDescription = "Clear"
    )
    
    // Filter
    Icon(
        imageVector = HyperIcons.Filter,
        contentDescription = "Filter"
    )
}

// ========== Example 6: Bottom Navigation (Before/After) ==========
@Composable
fun ExampleBottomNavBefore() {
    // ❌ OLD WAY - Don't use this
    // Icon(Icons.Default.Dashboard, "Dashboard")
    // Icon(Icons.Default.Settings, "Settings")
}

@Composable
fun ExampleBottomNavAfter() {
    // ✅ NEW WAY - Use HyperIcons
    Icon(HyperIcons.Dashboard, "Dashboard")
    Icon(HyperIcons.Settings, "Settings")
    Icon(HyperIcons.Logs, "Logs")
    Icon(HyperIcons.Configs, "Configurations")
    Icon(HyperIcons.Utils, "Utils")
}

